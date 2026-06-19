package com.miningdim.job.agent.integration;

import com.miningdim.core.MiningConstants;
import com.miningdim.job.agent.AgentBountySavedData;
import com.miningdim.job.agent.AgentLevels;
import com.miningdim.job.agent.SealCategory;
import com.miningdim.job.agent.SealPlan;
import com.miningdim.job.agent.SealRegistry;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.living.LivingDeathEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import top.theillusivec4.champions.api.IAffix;
import top.theillusivec4.champions.api.IChampion;

import java.util.List;
import java.util.UUID;

/**
 * 特勤封印接线 (SpecialAgent_Job_DesignSpec 六章封印支线; Champions 集成层)。串起三层:
 *  (1) 校验门 ({@link SealPlan#plan}): 干员等级 / 精英星级 / 词条类别三门;
 *  (2) 槽位 + 不叠加裁决 ({@link SealRegistry#applySeal}): 每精英固定槽容量, 多干员同怪不叠加 (互斥/先到先得);
 *  (3) 真改执行 ({@link AgentSealExecutor}): setAffixes 临时移除目标词条 + 持原集快照供到期恢复。
 *
 * 封印申请入口 {@link #requestSeal} 由 b 阶段扫描面板的服务端处理调用 (玩家点已解密词条 -> 服务端发本请求);
 * 本任务交付服务端可调的封印 API + 到期恢复 tick + 死亡清理, 面板 UI/网络包属后续 (留 deferred)。
 *
 * 到期恢复 tick ({@link #onServerTick}): 每 tick 对持封印态的精英 (经 {@link AgentSealExecutor#snapshotChampions})
 * 调 {@link SealRegistry#activeSeals} 触发到期剔除; 当某精英已无活跃封印且仍持原集快照时, 一次性
 * {@link AgentSealExecutor#restoreAffixes} 恢复原词条集 (六章窗口到期恢复)。范式对齐
 * {@code ChampionBloodPoolHandler.onServerTick} (只遍在册精英快照, 非全世界扫描)。
 *
 * 死亡清理 ({@link #onChampionDeath}): 精英死亡时清纯逻辑账本 + 执行侧快照 (实体已亡, 不触恢复; 防泄漏)。
 *
 * compileOnly 隔离: 本类 import top.theillusivec4.champions.* —— 仅 ModList 守卫下经 {@link AgentIntegrationBootstrap}
 * 挂 forgeBus + 装进 {@link AgentSealSeam}, dev 不加载。
 */
public final class AgentSealHandler {

    /**
     * 服务端处理一次封印申请 (六章: 面板点已解密词条 -> 服务端校验 -> 占槽 -> 真改)。校验/占槽失败返带原因的
     * {@link Result} (供面板回显失败提示), 不抛异常 (校验失败是正常业务分支)。仅本工程盖章精英可封。
     *
     * 流程: 找目标精英 (经 IChampion 探测 + isOurChampion 门) -> 读初始星级 -> 在其当前词条列表里按 affixId 定位
     * 真 IAffix 并经 {@link AgentAffixClassifier} 归类 (得 SealCategory; 不可封词条直接拒) -> {@link SealPlan#plan}
     * 三门校验 -> {@link SealRegistry#applySeal} 占槽 (不叠加裁决) -> 占槽成功才 {@link AgentSealExecutor#sealAffix}
     * 真移除词条。
     *
     * @param agent    申请封印的干员 (服务端玩家; 提供等级 + ownerUUID)
     * @param target   目标精英实体
     * @param affixId  目标词条全限定注册名 (面板传; 与 {@link AgentAffixClassifier#affixId} 同口径)
     * @return 封印结果 (ok 带到期 tick + 类别; 失败带 {@link FailReason})
     */
    public static Result requestSeal(ServerPlayer agent, LivingEntity target, String affixId) {
        if (agent == null || target == null || affixId == null) {
            return Result.fail(FailReason.NO_TARGET);
        }
        IChampion champion = AgentChampionData.championOf(target);
        if (champion == null || !AgentChampionData.isOurChampion(champion)) {
            return Result.fail(FailReason.NO_TARGET); // 非本工程盖章精英: 不可封。
        }
        int star = AgentChampionData.starOf(champion);
        if (star < 1) {
            return Result.fail(FailReason.NO_TARGET);
        }

        // 在当前词条列表里定位目标真 IAffix + 归类 (类别决定窗口门控; 不可封词条返 null)。
        SealCategory category = locateCategory(champion, affixId);
        if (category == null) {
            return Result.fail(FailReason.AFFIX_NOT_SEALABLE);
        }

        int level = AgentLevels.agentLevel(agent);

        // 门 1-2: 等级 / 星级 / 类别三门 (SealPlan)。
        SealPlan.Result plan = SealPlan.plan(level, star, category);
        if (!plan.ok()) {
            return Result.fail(fromPlan(plan.reason()));
        }

        // 门 3: 槽位 + 不叠加裁决 (SealRegistry)。
        long nowTick = target.level().getGameTime();
        SealRegistry.ApplyResult apply = SealRegistry.applySeal(
                target.getUUID(), agent.getUUID(), affixId, category, level, star, nowTick);
        if (!apply.ok()) {
            return Result.fail(fromRegistry(apply.reason()));
        }

        // 真改: 移除目标词条 + 持原集快照。占槽已成功, 此处真改失败 (列表已无该词条) 不回滚槽 (槽到期自释放),
        // 但回报真改未生效让面板知晓 (异常态: 占槽与实体词条不一致, 罕见, 由到期恢复兜底)。
        boolean mutated = AgentSealExecutor.sealAffix(champion, target.getUUID(), affixId);
        if (!mutated) {
            return Result.fail(FailReason.AFFIX_NOT_SEALABLE);
        }

        // 入职标志置位 (用户定: 玩家真正执行任一特勤活计时置 activeAgent): 封印申请成功是现存唯一已接线的特勤活计
        // 入口, 此处把申请者标记为做过特勤工作, 其特勤专属福利 (加强奖励 / 对精英伤害放大) 由此解锁。其余活计入口
        // (扫描探测脉冲 / 接悬赏 / 悬赏击杀记账) 属 b 阶段面板接线, 待接线时在各入口同样 markActiveAgent (见交付 notes)。
        AgentBountySavedData.get(agent.server.overworld()).markActiveAgent(agent.getUUID());

        return Result.success(apply.expiryTick(), category);
    }

    /** 在精英当前词条列表里按 affixId 找真 IAffix 并归类 (不可封 / 列表无此词条返 null)。 */
    private static SealCategory locateCategory(IChampion champion, String affixId) {
        for (IAffix affix : champion.getServer().getAffixes()) {
            if (affixId.equals(AgentAffixClassifier.affixId(affix))) {
                return AgentAffixClassifier.classify(affix);
            }
        }
        return null;
    }

    /**
     * 到期恢复 tick (六章窗口到期恢复): 对持封印态精英检查活跃封印, 全到期则恢复原词条集。只遍在册精英快照
     * (通常极少), 非全世界实体扫描。精英已离场 (实体找不到) 时仍清纯逻辑账本 + 快照防泄漏。
     */
    @SubscribeEvent
    public void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        if (AgentSealExecutor.snapshotCount() == 0) {
            return; // 无封印态精英: 本 tick 空转。
        }
        MinecraftServer server = event.getServer();
        ServerLevel mining = server.getLevel(MiningConstants.MINING_LEVEL);
        if (mining == null) {
            return; // 维度未加载 (启动早期): 本 tick 跳过, 不刷屏。
        }
        long nowTick = mining.getGameTime();

        for (UUID championId : AgentSealExecutor.snapshotChampions()) {
            // 触发到期剔除 (activeSeals 内部 removeIf 已到期项); 仍有活跃封印则保持封印态。
            List<SealRegistry.ActiveSeal> active = SealRegistry.activeSeals(championId, nowTick);
            if (!active.isEmpty()) {
                continue; // 仍有未到期封印: 不恢复。
            }
            // 已无活跃封印但仍持快照 -> 恢复原词条集 (实体在场才真改; 离场则仅清快照防泄漏)。
            Entity entity = mining.getEntity(championId);
            if (entity instanceof LivingEntity living && living.isAlive()) {
                IChampion champion = AgentChampionData.championOf(living);
                if (champion != null) {
                    AgentSealExecutor.restoreAffixes(champion, championId);
                    continue;
                }
            }
            // 实体离场 / 失去 capability: 不能真改, 仅清执行侧快照 (纯逻辑账本已在 activeSeals 空时自清)。
            AgentSealExecutor.discard(championId);
        }
    }

    /** 精英死亡: 清纯逻辑封印账本 + 执行侧快照 (实体已亡不触恢复)。 */
    @SubscribeEvent
    public void onChampionDeath(LivingDeathEvent event) {
        UUID championId = event.getEntity().getUUID();
        SealRegistry.discard(championId);
        AgentSealExecutor.discard(championId);
    }

    private static FailReason fromPlan(SealPlan.FailReason reason) {
        return switch (reason) {
            case CATEGORY_LOCKED -> FailReason.CATEGORY_LOCKED;
            case STAR_TOO_HIGH -> FailReason.STAR_TOO_HIGH;
        };
    }

    private static FailReason fromRegistry(SealRegistry.FailReason reason) {
        return switch (reason) {
            case ALL_SLOTS_OCCUPIED -> FailReason.ALL_SLOTS_OCCUPIED;
            case AFFIX_ALREADY_SEALED -> FailReason.AFFIX_ALREADY_SEALED;
        };
    }

    /** 封印申请的集成层结果 (聚合 SealPlan / SealRegistry / 真改三级失败原因; 供面板统一回显)。 */
    public record Result(boolean ok, long expiryTick, SealCategory category, FailReason reason) {

        static Result success(long expiryTick, SealCategory category) {
            return new Result(true, expiryTick, category, null);
        }

        static Result fail(FailReason reason) {
            return new Result(false, 0L, null, reason);
        }
    }

    /** 封印申请失败原因 (集成层聚合; lang key agent.seal.fail.* 据此)。 */
    public enum FailReason {
        /** 目标非本工程盖章精英 / 不存在。 */
        NO_TARGET,
        /** 该词条不可封 (外来 mod 词条 / 纯防御词条 / 列表无此词条)。 */
        AFFIX_NOT_SEALABLE,
        /** 本类别封印未解锁 (被动 L&lt;3 / 机制 L&lt;8)。 */
        CATEGORY_LOCKED,
        /** 目标星级超过干员可封星级。 */
        STAR_TOO_HIGH,
        /** 全部封印槽已被占 (防叠叠乐拒绝点)。 */
        ALL_SLOTS_OCCUPIED,
        /** 该词条已被封印中 (互斥, 不因第二人再封延长)。 */
        AFFIX_ALREADY_SEALED
    }
}
