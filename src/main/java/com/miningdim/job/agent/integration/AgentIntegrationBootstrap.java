package com.miningdim.job.agent.integration;

import com.miningdim.job.agent.AgentSealSeam;
import net.minecraftforge.eventbus.api.IEventBus;

/**
 * 特勤集成层装配入口 (已自研脱离 Champions; 范式对齐 {@code com.miningdim.champion.ChampionSystem})。本包已无
 * 第三方依赖, 由 {@code AgentSystem} 无条件装配 —— handler 内部探测自研 {@code MiningChampions} capability,
 * 不再 import 任何 top.theillusivec4.champions.*, 不再需要 {@code ModList.isLoaded("champions")} 守卫。
 *
 * 装配:
 *  1. 封印 handler ({@link AgentSealHandler}): 挂 forgeBus (到期恢复 tick + 死亡清理)。
 *  2. 加强奖励 + 悬赏结算 handler ({@link AgentRewardHandler}): 挂 forgeBus (HIGHEST 接管精英死亡结算)。
 *  3. 伤害加成 handler ({@link AgentDamageBonusHandler}): 挂 forgeBus (干员对精英少量放大)。
 *  4. 封印 + 扫描接缝 ({@link AgentSealSeam#bind}): 注入封印申请真实现 ({@link AgentSealHandler#requestSealOutcome})
 *     + 扫描快照构建真实现 ({@link AgentScanProbe#buildSnapshot}, 读自研词条表做分级解密) + 服务端停止清执行侧词条
 *     快照, 供 champions-free 的 AgentSystem / 五章面板网络层经接缝调用。
 */
public final class AgentIntegrationBootstrap {

    private AgentIntegrationBootstrap() {
    }

    /**
     * 装配特勤集成层, 挂封印/奖励/伤害加成 handler + bind 封印接缝。
     *
     * @param forgeBus forge 事件总线 (挂封印/奖励/伤害加成 handler)
     */
    public static void assemble(IEventBus forgeBus) {
        forgeBus.register(new AgentSealHandler());
        forgeBus.register(new AgentRewardHandler());
        forgeBus.register(new AgentDamageBonusHandler());
        bindSeam();
    }

    /**
     * 单独暴露封印接缝绑定 (不重复注册 handler), 供 GameTest 用: 现有测试会 {@code AgentSealSeam.unbind} 去测短路
     * 分支 (Champions/集成层未装配态), 测完必须能把真实现装回来 —— 而它们在 {@code com.miningdim.job.agent} 包里
     * 看不见 {@code com.miningdim.job.agent.integration} 包私有的 {@link AgentScanProbe}/{@link AgentSealExecutor},
     * 只能经本类的 public 入口重新 bind; 若测试改调 {@link #assemble}, 会把三个 handler 重复注册进事件总线 (重复
     * 触发)。故把"只绑接缝"单独拆出一个 public 方法。
     */
    public static void bindSeam() {
        AgentSealSeam.bind(
                AgentSealHandler::requestSealOutcome,
                AgentScanProbe::buildSnapshot,
                AgentSealExecutor::reset);
    }
}
