package com.miningdim.job.agent.integration;

import com.miningdim.champion.AffixDef;
import com.miningdim.champion.MiningChampionData;
import com.miningdim.champion.MiningChampions;
import com.miningdim.job.agent.AgentBountySavedData;
import com.miningdim.job.agent.AgentLevels;
import com.miningdim.job.agent.AgentSealSeam;
import com.miningdim.job.agent.SealCategory;
import com.miningdim.job.agent.SealPlan;
import com.miningdim.job.agent.SealRegistry;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.Level;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.living.LivingDeathEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

import java.util.UUID;

/**
 * 特勤封印接线 (SpecialAgent_Job_DesignSpec 六章封印支线; 已自研脱离 Champions)。串起三层:
 *  (1) 校验门 ({@link SealPlan#plan}): 干员等级 / 精英星级 / 词条类别三门;
 *  (2) 槽位 + 不叠加裁决 ({@link SealRegistry#applySeal}): 每精英固定槽容量, 多干员同怪不叠加 (互斥/先到先得);
 *  (3) 真改执行 ({@link AgentSealExecutor}): 增量移除目标词条 + 持增量快照供到期恢复。
 *
 * 探测源已改自研 {@link MiningChampions#get}, 不再触任何 top.theillusivec4.champions.*。
 */
public final class AgentSealHandler {

    /**
     * 实体暂时找不到时 (区块卸载 / 跨维度) 仍保留恢复快照重试的宽限期 (tick)。不是封印窗口本身, 是"到期后还能
     * 再等多久找回实体"的容忍上限, 超过则放弃 (discard, 见 {@link #processExpiredSeals})。20 分钟 = 20*60*20 tick。
     */
    private static final long RESTORE_GRACE_TICKS = 24000L;

    /**
     * 服务端处理一次封印申请 (六章: 面板点已解密词条 -> 服务端校验 -> 占槽 -> 真改)。校验/占槽失败返带原因的
     * {@link Result} (供面板回显失败提示), 不抛异常 (校验失败是正常业务分支)。仅本工程盖章精英可封。
     *
     * @param agent    申请封印的干员 (服务端玩家; 提供等级 + ownerUUID)
     * @param target   目标精英实体
     * @param affixId  目标词条线上标识 (与 {@link AgentAffixClassifier#affixId} 同口径, 即 {@link AffixDef#name()})
     * @return 封印结果 (ok 带到期 tick + 类别; 失败带 {@link FailReason})
     */
    public static Result requestSeal(ServerPlayer agent, LivingEntity target, String affixId) {
        if (agent == null || target == null || affixId == null) {
            return Result.fail(FailReason.NO_TARGET);
        }
        MiningChampionData champ = MiningChampions.get(target).orElse(null);
        if (champ == null || !champ.isChampion()) {
            return Result.fail(FailReason.NO_TARGET); // 非本工程盖章精英: 不可封。
        }
        int star = champ.star();

        // 定位目标词条 (未知 affixId 恒不可封)。
        AffixDef def = AgentAffixClassifier.affixOf(affixId);
        if (def == null) {
            return Result.fail(FailReason.AFFIX_NOT_SEALABLE);
        }

        // 互斥门先于装配门判 (F024 复核修正): 封印成功必然把该词条从 capability 移除 (AgentSealExecutor.sealAffix),
        // 故已封印中的词条 champ.has(def) 恒为 false, 若先判装配门, AFFIX_ALREADY_SEALED 会成为结构性不可达 ——
        // 第二人 (或同一人) 对同一条已封词条再申请, 永远只能拿到 AFFIX_NOT_SEALABLE 这个不精确的回执。
        long nowTick = target.level().getGameTime();
        if (SealRegistry.isAffixSealed(target.getUUID(), affixId, nowTick)) {
            return Result.fail(FailReason.AFFIX_ALREADY_SEALED);
        }

        // 装配门 (类别决定窗口门控; 不可封词条返 null)。
        if (!champ.has(def)) {
            return Result.fail(FailReason.AFFIX_NOT_SEALABLE);
        }
        SealCategory category = AgentAffixClassifier.classify(def);
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
        SealRegistry.ApplyResult apply = SealRegistry.applySeal(
                target.getUUID(), agent.getUUID(), affixId, category, level, star, nowTick);
        if (!apply.ok()) {
            return Result.fail(fromRegistry(apply.reason()));
        }

        // 真改: 增量移除目标词条 + 持增量快照。占槽已成功, 此处真改失败 (当前未装配该词条) 不回滚槽 (槽到期自释放),
        // 但回报真改未生效让面板知晓 (异常态: 占槽与实体词条不一致, 罕见, 由到期恢复兜底)。
        boolean mutated = AgentSealExecutor.sealAffix(target, champ, def, apply.expiryTick() + RESTORE_GRACE_TICKS);
        if (!mutated) {
            return Result.fail(FailReason.AFFIX_NOT_SEALABLE);
        }

        // 入职标志置位 (用户定: 玩家真正执行任一特勤活计时置 activeAgent): 封印申请成功是现存唯一已接线的特勤活计
        // 入口, 此处把申请者标记为做过特勤工作, 其特勤专属福利 (加强奖励 / 对精英伤害放大) 由此解锁。其余活计入口
        // (扫描探测脉冲 / 接悬赏 / 悬赏击杀记账) 属 b 阶段面板接线, 待接线时在各入口同样 markActiveAgent (见交付 notes)。
        AgentBountySavedData.get(agent.server.overworld()).markActiveAgent(agent.getUUID());

        return Result.success(apply.expiryTick(), category);
    }

    /**
     * 封印申请的接缝级入口 (五章面板点已解密词条 -> C2S -> 经 {@code AgentSealSeam} 调用)。把 {@link #requestSeal}
     * 的聚合 {@link Result} 翻译为 champions-free 的 {@link AgentSealSeam.SealOutcome} 供网络层回执玩家。绝不返
     * NOT_BOUND (那由接缝未绑定路径产生, 不会进到本集成层)。
     */
    public static AgentSealSeam.SealOutcome requestSealOutcome(ServerPlayer agent, LivingEntity target, String affixId) {
        Result result = requestSeal(agent, target, affixId);
        if (result.ok()) {
            return AgentSealSeam.SealOutcome.OK;
        }
        return switch (result.reason()) {
            case NO_TARGET -> AgentSealSeam.SealOutcome.NO_TARGET;
            case AFFIX_NOT_SEALABLE -> AgentSealSeam.SealOutcome.AFFIX_NOT_SEALABLE;
            case CATEGORY_LOCKED -> AgentSealSeam.SealOutcome.CATEGORY_LOCKED;
            case STAR_TOO_HIGH -> AgentSealSeam.SealOutcome.STAR_TOO_HIGH;
            case ALL_SLOTS_OCCUPIED -> AgentSealSeam.SealOutcome.ALL_SLOTS_OCCUPIED;
            case AFFIX_ALREADY_SEALED -> AgentSealSeam.SealOutcome.AFFIX_ALREADY_SEALED;
            case ON_COOLDOWN -> AgentSealSeam.SealOutcome.ON_COOLDOWN;
        };
    }

    /**
     * 到期恢复 tick (F077 修法): 对持封印态精英, 检查 {@link SealRegistry} 活跃封印, 全到期则增量恢复。按封印
     * 当刻记下的维度 ({@link AgentSealExecutor#dimensionOf}) 定位实体, 不再写死矿洞维度 (旧版只查矿洞维度导致
     * 精英跨维度/在其它维度被封时词条被永久剥夺)。实体暂时找不到 (区块卸载 / 跨维度未加载) 时保留快照重试,
     * 只有过了 {@link #RESTORE_GRACE_TICKS} 宽限期才放弃 (discard), 不再一找不到就立即丢快照。
     *
     * 已知边界: 快照只在进程内存, 服务端在封印窗口内重启仍会永久丢那条词条; 彻底修法要把被封词条随实体 NBT
     * 存盘, 那要改 {@link MiningChampionData} (champion 包), 本轮受分支边界约束不做。
     *
     * @param server 当前服务端实例
     */
    static void processExpiredSeals(MinecraftServer server) {
        if (AgentSealExecutor.snapshotCount() == 0) {
            return; // 无封印态精英: 本 tick 空转。
        }
        for (UUID championId : AgentSealExecutor.snapshotChampions()) {
            ResourceKey<Level> dim = AgentSealExecutor.dimensionOf(championId);
            if (dim == null) {
                continue; // 快照已被别处清理 (并发到期恢复/死亡): 跳过。
            }
            ServerLevel level = server.getLevel(dim);
            if (level == null) {
                continue; // 维度未加载: 下 tick 再来。
            }
            long nowTick = level.getGameTime();
            if (!SealRegistry.activeSeals(championId, nowTick).isEmpty()) {
                continue; // 仍有未到期封印: 不恢复。
            }
            Entity entity = level.getEntity(championId);
            if (entity instanceof LivingEntity living && living.isAlive()) {
                MiningChampionData champ = MiningChampions.get(living).orElse(null);
                if (champ != null && champ.isChampion()) {
                    AgentSealExecutor.restoreAffixes(living, champ, championId);
                    continue;
                }
            }
            // 实体暂时不在场 (区块卸载 / 跨维度): 保留快照重试, 只有过了宽限期才放弃, 不再一找不到就 discard。
            if (nowTick >= AgentSealExecutor.restoreDeadlineTick(championId)) {
                AgentSealExecutor.discard(championId);
            }
        }
    }

    /** 到期恢复 tick 入口: 只做 phase==END 判断后转调 {@link #processExpiredSeals} (供 GameTest 直接驱动)。 */
    @SubscribeEvent
    public void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        processExpiredSeals(event.getServer());
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
            case ON_COOLDOWN -> FailReason.ON_COOLDOWN;
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
        /** 该词条不可封 (未知 affixId / 未装配该词条 / 纯防御词条 / 真改未生效)。 */
        AFFIX_NOT_SEALABLE,
        /** 本类别封印未解锁 (被动 L&lt;3 / 机制 L&lt;8)。 */
        CATEGORY_LOCKED,
        /** 目标星级超过干员可封星级。 */
        STAR_TOO_HIGH,
        /** 全部封印槽已被占 (防叠叠乐拒绝点)。 */
        ALL_SLOTS_OCCUPIED,
        /** 该词条已被封印中 (互斥, 不因第二人再封延长)。 */
        AFFIX_ALREADY_SEALED,
        /** 该干员该词条类别仍在封印 CD 内 (六章封印 CD 强制点)。 */
        ON_COOLDOWN
    }
}
