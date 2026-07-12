package com.miningdim.job.miner;

import com.miningdim.core.InstanceState;
import com.miningdim.core.MiningConstants;
import com.miningdim.core.MiningServices;
import com.miningdim.core.Subsystem;
import com.miningdim.economy.EconomyServices;
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
 * 矿工子系统入口 (Miner_Job_DesignSpec 第十一章; 模块化铁律 3)。已在 {@code MiningDim.registerSubsystems()} 实装
 * (经 modBus/forgeBus 自注册其全部事件订阅与专属网络包)。
 *
 * 持有: per-player {@link MinerChargeState} (UUID 键, 瞬态运行态, 死亡/登出/换维度清理), 连锁 BFS 引擎单例。
 *
 * 事件订阅 (forgeBus):
 *  - {@link PlayerEvent.BreakSpeed}: 矿洞 region 内挖速加成 + 抗疲劳 (统一结算防互相覆盖)。
 *  - {@link BlockEvent.BreakEvent}: AFK 冻结即跳过 (反挂机) -> 谁挖谁得经验 (region 内) + 连锁触发 (连带产出经货币门面
 *    回放当日矿物计数 + 时运额外掉落) + 自动入包/熔炼 + 省耐久抢拍 (回补在同 tick 末)。
 *  - {@link TickEvent.ServerTickEvent}: 连锁充能回充 + 脱险读条推进 (移动打断) + 省耐久同 tick 末回补核对。
 *  - {@link LivingHurtEvent}: 脱险读条受伤即打断 (不能当 PvP 逃跑后门, 第七章护栏) + 矿脉抗性陷阱专属来源减伤。
 *  - 玩家生命周期 (登出/换维度/克隆): 清瞬态运行态 (第五章纪律, 防反复进出矿洞泄漏)。
 * 事件订阅 (modBus):
 *  - {@link FMLCommonSetupEvent}: 在 enqueueWork 内注册矿工专属 SimpleChannel 的两个包。
 *
 * 跨子系统只经 core 门面 ({@link MiningServices}) / 职业框架门面 ({@link JobServices}) / 货币门面定位器
 * ({@link EconomyServices}); 不硬 import 对方实现类。经济当日矿物计数回放 (方案 B, 含时运额外) 与 AFK 冻结查询经
 * {@link EconomyServices#economyService()} 取 IEconomyService 完成; 难度门控读矿工等级 / danger 时间项注入 (pressure)
 * 属其它子系统的同步接线。静态陷阱已由 trap 子系统落地 (TrapOreBlock + StaticTrapTrigger, 方案 C datapack 布点),
 * 触发效果走 isTrapSource 认可的原版环境伤类型, 矿脉抗性自动覆盖; 专属 TrapDamageSource 为可选未来收紧点 (见 isTrapSource 注释)。
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
        // 矿脉抗性 (陷阱专属来源减伤): 作为独立命名减伤源迁入玩家减伤单点结算 (减伤统一)。isTrapSource 现按
        // 第七章降级路径识别环境陷阱伤 (落石/岩浆/着火/非玩家爆炸等), 在矿洞内 + L5 解锁时真实减伤; trap 暴露
        // 专属源后只需在 isTrapSource 收紧, 本接线不动。捕获 this 取 region/等级。
        com.miningdim.combat.PlayerDamageReduction.register(new com.miningdim.combat.PlayerDamageReduction.ReductionSource() {
            @Override
            public String name() {
                return "矿脉抗性";
            }

            @Override
            public double rate(net.minecraft.world.entity.player.Player victim, net.minecraft.world.damagesource.DamageSource source) {
                if (!inMiningRegion(victim) || !MinerSurvival.isTrapSource(source)) {
                    return 0.0D;
                }
                return MinerSkills.trapDamageReduction(minerLevel(victim));
            }
        });
        // 网络包注册推迟到 FMLCommonSetupEvent.enqueueWork (线程安全窗口, 与 NetworkSystem 同纪律)。
        modBus.addListener((FMLCommonSetupEvent event) -> event.enqueueWork(MinerNetwork::register));
        LOGGER.info("[miningdim] miner subsystem registered (break speed / who-mines-gets-xp / chain / scan / convenience)");
    }

    @SubscribeEvent
    public void onServerStarted(net.minecraftforge.event.server.ServerStartedEvent event) {
        // Major 缺陷四接线: 把矿工耐压的 danger 时间项系数 (MinerSkills.dangerTimeFactor, 封底 0.6) 绑进压力子系统的
        // seam (DangerJobFactor)。压力 tick 经此取系数缩放 tWin 累积/衰减, 二者经 seam 解耦 (压力不 import miner,
        // miner 不 import 压力实现类, 只用其暴露的接线点; 同 entry->entrance.EntranceHooks 范式)。provider 在每次
        // tick 求值故等级实时生效 (升级即变); 取等级走职业框架门面 (JobServices)。在 ServerStartedEvent 接线 (而非
        // register) 与 entry->EntranceHooks 同纪律: 单机退到标题再进档会再次 start, 须每次启动重绑 (register 仅 mod 构造跑一次)。
        com.miningdim.pressure.DangerJobFactor.bind(
                player -> (float) MinerSkills.dangerTimeFactor(minerLevel(player)));
    }

    @SubscribeEvent
    public void onServerStopping(net.minecraftforge.event.server.ServerStoppingEvent event) {
        // 清压力 seam 引用, 防跨存档/跨重启脏引用 (与 JobServices.reset / EntranceHooks.unbind 同纪律)。
        com.miningdim.pressure.DangerJobFactor.unbind();
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

        // 反挂机红线 (第九章 "AFK 态不计经验/不计产矿"): AFK 冻结态下不发经验且不连锁/不抢省耐久, 直接 return
        // 杜绝挂机刷经验与连锁清矿。冻结查询经货币门面 (见 grantMiningXpUnlessAfk), 与产矿计数 AFK 拦截同口径。
        if (!grantMiningXpUnlessAfk(player)) {
            return;
        }

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
     * 连锁连带破坏的每块产出回放 (反通胀第一道硬约束): 先按矿工等级时运 (方案 B) 追加额外掉落 -> 唯一物化这批掉落
     * (入包或落点 spawn) -> 逐块走经济计数口径 (产出物个数, 含时运额外)。destroyBlock 已 dropBlock=false, 故本回调是
     * 产出物的唯一出口, 杜绝双发与计数漂移; 时运在物化与计数之前施加, 保证额外产出既掉出来又计入隐藏软上限。
     */
    private void onChainProduce(ServerPlayer player, ServerLevel level, int minerLevel, net.minecraft.core.BlockPos pos,
                                Block brokenBlock, List<ItemStack> drops, boolean autoCollect, boolean autoSmelt) {
        // 矿脉时运 (L4 起): 在唯一物化前追加额外掉落 (服务端 RandomSource 权威); 额外产出随之物化并计入方案 B 计数。
        List<ItemStack> withFortune = MinerFortune.withFortuneExtras(drops, minerLevel, level.getRandom());
        // 唯一物化: 自动入包则入库存 (可附带熔炼), 否则在破坏点 spawn (替代被禁用的原版掉落)。
        if (autoCollect) {
            AutoCollectSmelt.collect(player, minerLevel, withFortune, true, autoSmelt);
        } else {
            ChainMiningEngine.spawnDropsAt(level, pos, withFortune);
        }
        replayEconomyOreCount(player, brokenBlock, withFortune);
    }

    /**
     * 连锁/隧道连带产出的经济计数回放唯一入口 (反通胀第一道硬约束, Miner_Job_DesignSpec 第十章第一条):
     * 连带破坏绕过了原版 BreakEvent (ChainMiningEngine 用 destroyBlock dropBlock=false), 故连带块的产出物个数必须在此
     * 显式回放进经济当日矿物计数, 严禁静默绕过隐藏软上限 —— 否则满级矿工开连锁可整脉清矿而当日计数恒为 0 (印钞口)。
     *
     * 跨子系统经货币门面定位器 {@link EconomyServices#economyService()} 取 {@link com.miningdim.economy.IEconomyService}
     * (不 import economy 实现类, 守模块化铁律 2): 内部 recordMinedOreDrops 按产出物个数 (方案 B) 累加进与原版单块挖矿
     * ({@link com.miningdim.economy.EconomySystem#onBlockBreak}) 同一玩家态的当日计数, 共用同一隐藏软上限。
     * 非高价矿 / AFK 冻结 / producedCount&lt;=0 由门面内部判定不计 (返回 -1), 故连锁白名单内的普通方块 (铁/铜/煤/石)
     * 不属高价矿时门面自然 no-op; 当白名单未来含高价矿时计数随产出物个数同步增长, 反通胀约束不被连锁绕过。
     */
    void replayEconomyOreCount(ServerPlayer player, Block brokenBlock, List<ItemStack> drops) {
        int produced = ChainMiningEngine.countDropItems(drops);
        if (produced <= 0) {
            return;
        }
        // 经货币门面回放产出物个数进当日矿物计数 (方案 B); 高价矿种判定/AFK 拦截/软上限均在门面内部, 本处只供个数。
        EconomyServices.economyService().recordMinedOreDrops(player, brokenBlock, produced);
    }

    /**
     * 反挂机门控的挖矿经验发放 (第九章红线 "AFK 态不计经验"): 经货币门面 {@link EconomyServices#economyService()}
     * 的 isAfkFrozen 只读查询冻结态 (不 import economy 实现类, 守模块化铁律 2); 冻结时不发经验返回 false, 否则发一份
     * 基础原始经验 (框架统一做每日衰减/翻日/升级) 返回 true。返回值供 {@link #onBlockBreak} 决定是否继续后续连锁/省耐久
     * (冻结即整条 break 处理短路, 与产矿计数 AFK 拦截同口径)。拆出为包级方法供 GameTest 直接断言冻结门控。
     *
     * @return true=非冻结且已发经验 (可继续后续处理); false=AFK 冻结, 未发经验 (调用方应短路)
     */
    boolean grantMiningXpUnlessAfk(ServerPlayer player) {
        if (EconomyServices.economyService().isAfkFrozen(player)) {
            return false;
        }
        JobServices.jobService().grantXp(player, JobId.MINER, MINING_XP_PER_BLOCK);
        return true;
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
    // LivingHurt: 脱险读条受伤即打断 + 矿脉抗性减伤 (第七章护栏 + 守不漂战斗力红线)
    // ============================================================

    /**
     * 矿工受伤统一处理 (Miner_Job_DesignSpec 第七章):
     *  - 脱险读条受伤即打断 (读条 ~3s, 受伤/移动即打断, 长 CD = 不能当 PvP 逃跑后门): 委派
     *    {@link MinerActions#interruptEvacuateOnHurt} (内部判 evacuating, 不在读条则空转)。
     *  - 矿脉抗性 (L5 被动) 减伤: 仅当来源为 "陷阱专属来源" ({@link MinerSurvival#isTrapSource}) 时按矿工等级减伤,
     *    event.setAmount 施加。守红线: 对怪/枪/玩家 TNT 等非陷阱来源 isTrapSource 返回 false -> 零减免, 不动伤害。
     *
     * 二者严格分离: 打断只取消读条不改伤害; 减伤只缩放陷阱专属来源的伤害, 不取消事件、不加战力。
     *
     * 陷阱专属来源识别现状: trap 子系统专属 DamageSource 未落地, 动态陷阱借原版环境伤机制造伤; 故
     * {@link MinerSurvival#isTrapSource} 按第七章降级路径识别环境陷阱伤类型集合 (落石/钟乳石/铁砧/岩浆/着火/
     * 炽热地面/非玩家爆炸), 矿洞内 L5+ 减伤经上方 register 注册源真实生效。集合排除一切战斗来源与玩家 TNT
     * (PLAYER_EXPLOSION), 守不漂战斗力红线。trap 暴露专属源后只在 isTrapSource 收紧, 本接线不动。
     */
    @SubscribeEvent
    public void onLivingHurt(LivingHurtEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }
        // interruptEvacuateOnHurt 内部已判 evacuating() (不在读条则空转), 此处直接委派 (单一入口, 不重复取态)。
        MinerActions.interruptEvacuateOnHurt(player);
        // 矿脉抗性减伤已迁至玩家减伤单点结算 (见 register 注册的"矿脉抗性"源); 本处只保留脱险读条打断, 不改伤害。
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
        // 死亡重生: 瞬态 CD/充能不跨死亡保留 (持久进度由 entry 唯一权威 capability 复制, 运行态重置)。
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
        // 客户端安全: PlayerEvent.BreakSpeed 在客户端也触发 (客户端预测挖速/破坏动画), 而 instanceManager 是服务端
        // 子系统、客户端从未注册 —— 客户端再调 regionAt 会抛 IllegalStateException 直接崩客户端 (即"矿洞维度挖一下就崩"
        // 的真因; 主世界因 isMiningDimension 提前 return 才从没暴露)。客户端以"在矿洞维度"为准放行 (region 间 gap 是
        // 基岩挖不动, 放宽无副作用, 且挖速加成须在客户端同样生效否则客户端慢速预测会拖住实际挖掘); 服务端走完整 region 判定。
        if (player.level().isClientSide()) {
            return true;
        }
        return MiningServices.instanceManager().regionAt(player.getBlockX(), player.getBlockZ()) != null;
    }
}
