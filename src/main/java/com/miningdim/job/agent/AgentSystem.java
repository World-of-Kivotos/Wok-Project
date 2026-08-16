package com.miningdim.job.agent;

import com.miningdim.core.Subsystem;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 特勤干员子系统入口 (implements core.Subsystem; SpecialAgent_Job_DesignSpec 十三章并入 JobFramework; 模块化铁律 3:
 * 唯一入口, register 内完成本子系统的订阅/接线; MiningDim 主类 List 列尾追加一行即装配)。
 *
 * 集成层已自研化 (F024): {@code com.miningdim.job.agent.integration} 隔离包读取自研 {@code MiningChampionData}
 * capability, 全包零第三方 Champions 依赖, 故装配不再受 ModList 守卫约束, 无条件装配。旧版曾用
 * {@code ModList.isLoaded("champions")} 守卫装配 —— 这道守卫本身就是缺陷源: 装了 Champions 后
 * AgentRewardHandler 挂 EventPriority.HIGHEST 先 ContributionTracker.discard 再 return, 等于把全服精英奖励
 * 静默清零, 已一并去掉。
 *
 * 接线点:
 *  - 集成层装配: 经 {@code AgentIntegrationBootstrap.assemble} 挂封印/奖励/伤害加成 handler + bind 封印接缝。
 *  - 生命周期清理: ServerStoppingEvent 清纯逻辑封印账本 ({@link SealRegistry#reset}) + 经接缝清执行侧词条快照,
 *    防跨存档脏引用 (执行侧快照清理经 {@link AgentSealSeam})。
 *
 * 等级/经验数据: 走共享职业框架 capability (JobProgress, JobId.AGENT), 不新挂 capability (与军火商同范式)。
 * 悬赏进度/周青辉石软上限: 走自有 {@link AgentBountySavedData} (overworld 持久层, 按 ownerUUID), 与经验态解耦。
 */
public final class AgentSystem implements Subsystem {

    private static final Logger LOGGER = LoggerFactory.getLogger("miningdim/agent");

    @Override
    public void register(IEventBus modBus, IEventBus forgeBus) {
        // 生命周期清理 handler 无条件挂 (清纯逻辑层封印账本 + 经接缝清执行侧)。
        forgeBus.register(this);

        // 平板特勤页的 job.agent.* (扫描/封印经 AgentSealSeam 接缝走)。
        AgentWebUiActions.registerAll();

        // 集成层已自研化, 无条件装配 (封印/奖励/伤害加成 handler 挂 forgeBus + bind 封印接缝)。
        com.miningdim.job.agent.integration.AgentIntegrationBootstrap.assemble(forgeBus);
        LOGGER.info("[agent] agent integration assembled (detect + seal + enhanced reward + bounty + damage bonus)");
    }

    @Override
    public String name() {
        return "AgentSystem";
    }

    /**
     * 服务端停止: 清纯逻辑层封印账本 ({@link SealRegistry#reset}) + 经接缝清执行侧词条快照,
     * 防跨存档/跨重启脏引用。AgentBountySavedData 是 overworld 持久层 (随存档落盘), 不在此 reset。
     *
     * 不再调用 {@code AgentSealSeam.unbind()} (F024 复核修复): bind 只在 mod 构造期跑一次, 而
     * ServerStopping 解绑后没有任何重新绑定的入口 —— 单人退出世界再进另一个存档时接缝就会恒为未绑定,
     * 扫描/封印永久离线。接缝里只存三个静态方法引用, 不含任何世界/存档状态, 没有跨存档脏引用可言;
     * 真正需要清的执行侧词条快照已由 {@link AgentSealSeam#onServerStopping()} 回调清掉。
     */
    @SubscribeEvent
    public void onServerStopping(ServerStoppingEvent event) {
        SealRegistry.reset();
        AgentSealSeam.onServerStopping(); // 经接缝清执行侧原词条快照。
    }
}
