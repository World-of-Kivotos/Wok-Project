package com.miningdim.job.agent.integration;

import com.miningdim.job.agent.AgentSealSeam;
import net.minecraftforge.eventbus.api.IEventBus;

/**
 * 特勤 Champions 集成层装配入口 (Champions 触点收敛中转; compileOnly 铁律落点; 范式对齐
 * {@code com.miningdim.champion.ChampionIntegrationBootstrap})。本类引用 integration 包内 import
 * top.theillusivec4.champions.* 的 handler ({@link AgentSealHandler}/{@link AgentRewardHandler}/
 * {@link AgentDamageBonusHandler}) 与执行侧 ({@link AgentSealExecutor}), 故本类加载会连带触发 Champions 类加载。
 *
 * 铁律: 本类只能被 {@code com.miningdim.job.agent.AgentSystem.register} 在 {@code ModList.isLoaded("champions")}
 * 守卫为真时调用。JVM 惰性类加载: AgentSystem 对 {@link #assemble} 的调用在守卫 if 之后才执行, 故 Champions 未
 * 加载时本类及 integration 包永不被类加载 (dev GameTest 安全, 不触 NoClassDefFoundError)。
 *
 * 装配:
 *  1. 封印 handler ({@link AgentSealHandler}): 挂 forgeBus (到期恢复 tick + 死亡清理)。
 *  2. 加强奖励 + 悬赏结算 handler ({@link AgentRewardHandler}): 挂 forgeBus (HIGHEST 接管精英死亡结算)。
 *  3. 伤害加成 handler ({@link AgentDamageBonusHandler}): 挂 forgeBus (干员对精英少量放大)。
 *  4. 封印 + 扫描接缝 ({@link AgentSealSeam#bind}): 注入封印申请真实现 ({@link AgentSealHandler#requestSealOutcome})
 *     + 扫描快照构建真实现 ({@code AgentScanProbe.buildSnapshot}, 读真词条做分级解密) + 服务端停止清执行侧词条快照,
 *     供 champions-free 的 AgentSystem / 五章面板网络层经接缝调用 (AgentSystem / 网络层不直接 import 任何 Champions 类)。
 */
public final class AgentIntegrationBootstrap {

    private AgentIntegrationBootstrap() {
    }

    /**
     * 装配特勤集成层 (仅 Champions 已加载时调用)。public 仅因 {@code AgentSystem} 跨包 (com.miningdim.job.agent)
     * 调用; compileOnly 安全由 AgentSystem 的 {@code ModList.isLoaded("champions")} 守卫保证 (本类在守卫 if 之后才
     * 被引用, JVM 惰性类加载, dev 不触达), 非由访问修饰符保证。
     *
     * @param forgeBus forge 事件总线 (挂封印/奖励/伤害加成 handler)
     */
    public static void assemble(IEventBus forgeBus) {
        forgeBus.register(new AgentSealHandler());
        forgeBus.register(new AgentRewardHandler());
        forgeBus.register(new AgentDamageBonusHandler());

        // 封印 + 扫描接缝: 封印申请真实现 = AgentSealHandler.requestSealOutcome (聚合校验/占槽/真改, 返接缝级
        // SealOutcome); 扫描快照构建 = AgentScanProbe.buildSnapshot (读真词条 + 分级解密); 服务端停止清理 = 清执行
        // 侧原词条快照 (纯逻辑账本 SealRegistry.reset 由 AgentSystem champions-free 侧清)。
        AgentSealSeam.bind(
                AgentSealHandler::requestSealOutcome,
                AgentScanProbe::buildSnapshot,
                AgentSealExecutor::reset);
    }
}
