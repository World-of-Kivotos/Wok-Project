package com.miningdim.job.miner;

import com.miningdim.core.InstanceState;
import com.miningdim.core.MiningConstants;
import com.miningdim.core.MiningServices;
import com.miningdim.core.Subsystem;
import com.miningdim.job.JobId;
import com.miningdim.job.JobServices;
import com.miningdim.job.miner.network.MinerNetwork;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.living.LivingHurtEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.level.BlockEvent;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 矿工子系统入口 (Miner_Job_DesignSpec 第十一章; 模块化铁律 3)。集成阶段在 MiningDim.registerSubsystems()
 * 追加一行 {@code subsystems.add(new com.miningdim.job.miner.MinerSystem())} (本任务不接线, 见 foundationGaps)。
 *
 * 持有: per-player {@link MinerChargeState} (UUID 键, 瞬态运行态, 死亡/登出/换维度清理), 连锁 BFS 引擎单例。
 *
 * 事件订阅 (forgeBus):
 *  - {@link PlayerEvent.BreakSpeed}: 矿洞 region 内挖速加成 + 抗疲劳 (统一结算防互相覆盖)。
 *  - {@link BlockEvent.BreakEvent}: 谁挖谁得经验 (region 内) + 连锁触发 + 自动入包/熔炼 + 省耐久抢拍 (回补在同 tick 末)。
 *  - {@link TickEvent.ServerTickEvent}: 连锁充能回充 + 脱险读条推进 (移动打断) + 省耐久同 tick 末回补核对。
 *  - {@link LivingHurtEvent}: 脱险读条受伤即打断 (不能当 PvP 逃跑后门, 第七章护栏)。
 *  - 玩家生命周期 (登出/换维度/克隆): 清瞬态运行态 (第五章纪律, 防反复进出矿洞泄漏)。
 * 事件订阅 (modBus):
 *  - {@link FMLCommonSetupEvent}: 在 enqueueWork 内注册矿工专属 SimpleChannel 的两个包。
 *
 * 跨子系统只经 core 门面 ({@link MiningServices}) 与职业框架门面 ({@link JobServices}); 不硬 import 对方实现类。
 * 经济计数口径改造 (方案 B) / 隐藏软上限删提示 / 难度门控读矿工等级 / danger 时间项注入 / 陷阱专属源 等
 * 属其它子系统的同步改动, 见 foundationGaps; 本子系统提供查询/计算入口供集成阶段接线。
 */
public final class MinerSystem implements Subsystem {

    private static final Logger LOGGER = LoggerFactory.getLogger("miningdim/job/miner");

    /** 全局单例引用, 供 {@link MinerActions} / 网络 handler 取 per-player 态与服务端时间 (C9: 未注册抛)。 */
    private static volatile MinerSystem instance;

    /** per-player 矿工运行态 (CD/充能/开关)。瞬态, 不持久化; 死亡/登出/换维度清理 (第五章)。 */
    private final Map<UUID, MinerChargeState> playerStates = new ConcurrentHashMap<>();

    /** 连锁/隧道 BFS 引擎 (含防重入标志, 服务端单例)。 */
    private final ChainMiningEngine chainEngine = new ChainMiningEngine();

    @Override
    public void register(IEventBus modBus, IEventBus forgeBus) {
        instance = this;
        forgeBus.register(this);
        // 网络包注册推迟到 FMLCommonSetupEvent.enqueueWork (线程安全窗口, 与 NetworkSystem 同纪律)。
        modBus.addListener((FMLCommonSetupEvent event) -> event.enqueueWork(MinerNetwork::register));
        LOGGER.info("[miningdim] miner subsystem registered (break speed / who-mines-gets-xp / chain / scan / convenience)");
    }

    @Override
    public String name() {
        return "MinerSystem";
    }

    /** 取单例 (网络 handler / MinerActions 入口); 未注册抛 IllegalStateException (C9)。 */
    public static MinerSystem get() {
        MinerSystem ref = instance;
        if (ref == null) {
            throw new IllegalStateException("MinerSystem not registered yet (check subsystem register order)");
        }
        return ref;
    }

    /** 取某玩家矿工运行态 (无则建)。 */
    public MinerChargeState stateOf(ServerPlayer player) {
        return playerStates.computeIfAbsent(player.getUUID(), k -> new MinerChargeState());
    }

    public ChainMiningEngine chainEngine() {
        return chainEngine;
    }

    /** 取某玩家矿工等级 (经职业框架门面)。 */
    public int minerLevel(Player player) {
        return JobServices.jobService().level(player, JobId.MINER);
    }

    // ============================================================
    // 挖速 + 抗疲劳 (BreakSpeed)
    // ============================================================

    /**
     * 矿洞 region 内按矿工等级加挖速, 满级抗疲劳免疫挖掘疲劳 (统一在此一处结算防与挖速互相覆盖)。
     * 与挖掘疲劳/急迫天然叠乘: setNewSpeed(originalNewSpeed * mult)。
     */
    @SubscribeEvent
    public void onBreakSpeed(PlayerEvent.BreakSpeed event) {
        Player player = event.getEntity();
        if (!inMiningRegion(player)) {
            return;
        }
        int level = minerLevel(player);

        float speed = event.getNewSpeed();
        // 抗疲劳 (L4 里程碑): 把挖掘疲劳的减速抵消 —— 用原始速度 (未受疲劳折减的 getOriginalSpeed) 作基线。
        if (MinerSkills.immuneToMiningFatigue(level)
                && player.hasEffect(net.minecraft.world.effect.MobEffects.DIG_SLOWDOWN)) {
            speed = Math.max(speed, event.getOriginalSpeed());
        }
        double mult = MinerSkills.digSpeedMultiplier(level);
        event.setNewSpeed((float) (speed * mult));
    }

    // ============================================================
    // BreakEvent: 谁挖谁得经验 + 连锁 + 便利 + 省耐久
    // ============================================================

    @SubscribeEvent
    public void onBlockBreak(BlockEvent.BreakEvent event) {
        if (!(event.getPlayer() instanceof ServerPlayer player)) {
            return;
        }
        // 连锁回放期间触发的二次 BreakEvent 不再处理 (防重入 + 防经验/连锁重复入账)。
        if (chainEngine.inChainBreak()) {
            return;
        }
        Level level = player.level();
        if (!isMiningDimension(level)) {
            return;
        }
        InstanceState instance = MiningServices.instanceManager()
                .regionAt(event.getPos().getX(), event.getPos().getZ());
        if (instance == null) {
            return; // 缓冲带/region 外: 不计经验/不连锁。
        }

        int minerLevel = minerLevel(player);
        MinerChargeState state = stateOf(player);

        // 谁挖谁得经验: region 内挖矿给挖矿者加原始经验, 框架统一做每日衰减/翻日/升级。
        // 反挂机红线 (第九章 "AFK 态不计经验"): AFK 冻结判定属 economy 子系统 (AbuseGuard.evaluateAfk 写 PlayerAbuseState),
        // 本子系统无 core 门面查询其冻结态。IEconomyService 当前未暴露 isAfkFrozen, 故此处暂无法前置拦截 ——
        // 这是已知接线缺口 (见 notes), 不静默掩盖: 集成阶段 IEconomyService 增 isAfkFrozen 后, 在此前置 return。
        grantMiningXp(player);

        // 省耐久 (L1 被动) 抢拍: BreakEvent 早于 mineBlock 扣耐久, 此处按等级概率掷骰决定是否回补, 命中则登记
        // 当前工具与扣前 damageValue, 同 tick 末 (onServerTick) 核对回补 (净零耐久损耗)。详见 MinerChargeState 字段注释。
        armDurabilitySave(player, minerLevel, state);

        // 连锁挖矿 (开关开 + 已解锁 + 有充能): 起始块是本事件的块, 连带破坏受充能预算约束。
        if (state.toggled(MinerSkill.CHAIN) && MinerSkills.chainUnlocked(minerLevel) && level instanceof ServerLevel sl) {
            int budget = state.currentCharge();
            if (budget > 0) {
                boolean autoCollect = state.toggled(MinerSkill.AUTO_COLLECT) && MinerSkills.autoCollectUnlocked(minerLevel);
                boolean autoSmelt = state.toggled(MinerSkill.AUTO_SMELT) && MinerSkills.autoSmeltBaseUnlocked(minerLevel);
                int broken = chainEngine.chainBreak(player, event.getPos(), sl, budget,
                        (pos, brokenBlock, drops) -> onChainProduce(player, sl, minerLevel, pos, brokenBlock, drops, autoCollect, autoSmelt));
                if (broken > 0) {
                    state.consumeCharge(broken);
                }
            }
        }
    }

    /**
     * 连锁连带破坏的每块产出回放 (反通胀第一道硬约束): 唯一物化这批掉落 (入包或落点 spawn) + 逐块走经济计数口径
     * (产出物个数)。destroyBlock 已 dropBlock=false, 故本回调是产出物的唯一出口, 杜绝双发与计数漂移。
     */
    private void onChainProduce(ServerPlayer player, ServerLevel level, int minerLevel, net.minecraft.core.BlockPos pos,
                                Block brokenBlock, List<ItemStack> drops, boolean autoCollect, boolean autoSmelt) {
        // 唯一物化: 自动入包则入库存 (可附带熔炼), 否则在破坏点 spawn (替代被禁用的原版掉落)。
        if (autoCollect) {
            AutoCollectSmelt.collect(player, minerLevel, ChainMiningEngine.copyDrops(drops), true, autoSmelt);
        } else {
            ChainMiningEngine.spawnDropsAt(level, pos, drops);
        }
        replayEconomyOreCount(player, brokenBlock, drops);
    }

    /**
     * 连锁/隧道连带产出的经济计数回放唯一入口 (反通胀第一道硬约束, Miner_Job_DesignSpec 第十章第一条):
     * 连带破坏绕过了原版 BreakEvent (ChainMiningEngine 用 destroyBlock), 故连带块的产出物个数必须在此显式回放进
     * 经济计数, 严禁静默绕过 EconomySystem 的隐藏软上限 —— 否则满级矿工开连锁可整脉清矿而当日计数恒为 0 (印钞口)。
     *
     * 接线缺口 (BLOCKING, 见 notes): 回放须经 economy 子系统的 AbuseGuard.recordMinedOre, 但其唯一对外门面
     * {@link com.miningdim.economy.IEconomyService} 当前未暴露 "按产出物个数计入当日矿物计数" 的方法 (现有方法均为
     * 货币扣费/入账/卖矿结算), 且 economy 尚无 IEconomyService 实现/定位器 (与 Farmer/Chef/Tarot 同处的 foundationGap)。
     * 跨子系统铁律禁止本包 import economy 实现类或直接调 AbuseGuard。故本方法是已就绪的回放 chokepoint:
     * economy 在 IEconomyService 增 recordMinedOreDrops(ServerPlayer, Block, int count) 并经 MinerEconomyHooks seam 绑定后,
     * 此处一行 hook.recordMinedOreDrops(player, brokenBlock, produced) 即闭环。在该方法落地前, 连带产出计数为 0 是
     * 已知未闭合的反通胀缺口, 不得视为已实现 (诚实标注, 不静默掩盖)。
     */
    void replayEconomyOreCount(ServerPlayer player, Block brokenBlock, List<ItemStack> drops) {
        int produced = ChainMiningEngine.countDropItems(drops);
        if (produced <= 0) {
            return;
        }
        // 接线就绪前显式记录每次未闭合的回放, 保留现场便于集成阶段核对 (非 "已实现" 假象)。
        LOGGER.debug("[miningdim] miner produced {} ore item(s) from {} via chain/tunnel; economy count replay BLOCKED "
                + "on IEconomyService.recordMinedOreDrops (foundationGap, see notes)", produced, brokenBlock);
    }

    /** 给挖矿者矿工经验 (原始经验, 框架做衰减/翻日/升级)。每破坏一块给一份基础经验。 */
    private void grantMiningXp(ServerPlayer player) {
        JobServices.jobService().grantXp(player, JobId.MINER, MINING_XP_PER_BLOCK);
    }

    /** 每破坏一块矿洞方块给的原始经验 (PENDING 标定; 取一个保守初值, 集成阶段进 config)。 */
    private static final long MINING_XP_PER_BLOCK = 10L;

    /**
     * 省耐久抢拍 (L1 被动, Miner_Job_DesignSpec 第五章): 按矿工等级的 {@link MinerSkills#durabilitySaveChance}
     * 概率掷骰; 命中且主手为可损耐久工具时登记当前 stack 与扣耐久前 damageValue, 同 tick 末核对回补。
     *
     * 概率 0 (理论上 L1 即 0.05) 或工具不可损耐久时不登记。掷骰用玩家所在世界的 RandomSource (服务端权威)。
     */
    private void armDurabilitySave(ServerPlayer player, int minerLevel, MinerChargeState state) {
        double chance = MinerSkills.durabilitySaveChance(minerLevel);
        if (chance <= 0.0D) {
            return;
        }
        ItemStack tool = player.getMainHandItem();
        if (!tool.isDamageableItem()) {
            return; // 不可损耐久 (空手/方块/食物): 无耐久可省。
        }
        if (player.serverLevel().getRandom().nextDouble() >= chance) {
            return; // 未命中省耐久概率: 正常扣耐久。
        }
        state.armDurabilitySave(tool, tool.getDamageValue());
    }

    // ============================================================
    // LivingHurt: 脱险读条受伤即打断 (第七章护栏: 不能当 PvP 逃跑后门)
    // ============================================================

    /**
     * 受伤即打断脱险读条 (Miner_Job_DesignSpec 第七章: 读条 ~3s, 受伤/移动即打断, 长 CD = 不能当 PvP 逃跑后门)。
     * 移动打断在 {@link #onServerTick} 每 tick 由 {@link MinerActions#advanceEvacuateChannel} 做; 受伤打断在此。
     *
     * 只对受伤的 ServerPlayer 处理 (撤离态是 per-player 服务端态); 仅在确处于读条中时打断 (无副作用空转)。
     * 不改伤害本身 (不减伤、不取消事件), 只取消读条 —— 与矿脉抗性 (减伤) 严格分离, 守 "不漂战斗力" 红线。
     */
    @SubscribeEvent
    public void onLivingHurt(LivingHurtEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }
        // interruptEvacuateOnHurt 内部已判 evacuating() (不在读条则空转), 此处直接委派 (单一入口, 不重复取态)。
        MinerActions.interruptEvacuateOnHurt(player);
    }

    // ============================================================
    // ServerTick: 充能回充 + 脱险读条推进
    // ============================================================

    @SubscribeEvent
    public void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        ServerLevel mining = event.getServer().getLevel(MiningConstants.MINING_LEVEL);
        if (mining == null) {
            return;
        }
        long now = mining.getGameTime();
        for (ServerPlayer player : mining.players()) {
            MinerChargeState state = stateOf(player);
            int level = minerLevel(player);
            state.tickRecharge(level, now);
            MinerActions.advanceEvacuateChannel(player, state, now);
            // 省耐久同 tick 末回补: ServerTickEvent END 晚于本 tick 内所有 BreakEvent + mineBlock 扣耐久,
            // 故此处核对登记栈的 damageValue 是否上升并回补 (净零损耗); 详见 MinerChargeState 字段注释。
            if (state.hasArmedDurabilitySave()) {
                state.consumeDurabilitySave(player.getMainHandItem());
            }
        }
    }

    // ============================================================
    // 玩家生命周期: 清瞬态运行态 (第五章, 防泄漏)
    // ============================================================

    @SubscribeEvent
    public void onPlayerLoggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
        playerStates.remove(event.getEntity().getUUID());
    }

    @SubscribeEvent
    public void onPlayerChangedDimension(PlayerEvent.PlayerChangedDimensionEvent event) {
        // 离开矿山维度即清运行态 (再进入时按需重建); 反复进出矿洞是最高频泄漏路径 (第五章)。
        if (event.getFrom().equals(MiningConstants.MINING_LEVEL)) {
            playerStates.remove(event.getEntity().getUUID());
        }
    }

    @SubscribeEvent
    public void onPlayerClone(PlayerEvent.Clone event) {
        // 死亡重生: 瞬态 CD/充能不跨死亡保留 (持久进度由 JobCapability 复制, 运行态重置)。
        if (event.isWasDeath()) {
            playerStates.remove(event.getEntity().getUUID());
        }
    }

    // ============================================================
    // 工具
    // ============================================================

    private boolean isMiningDimension(Level level) {
        return level.dimension().equals(MiningConstants.MINING_LEVEL);
    }

    /** 玩家是否在矿洞维度的某实例 region 内 (挖速/经验/连锁的共同守卫)。 */
    private boolean inMiningRegion(Player player) {
        if (!isMiningDimension(player.level())) {
            return false;
        }
        return MiningServices.instanceManager().regionAt(player.getBlockX(), player.getBlockZ()) != null;
    }
}
