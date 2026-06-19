package com.miningdim.job.agent;

import com.miningdim.core.Subsystem;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.ModList;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 特勤干员子系统入口 (implements core.Subsystem; SpecialAgent_Job_DesignSpec 十三章并入 JobFramework; 模块化铁律 3:
 * 唯一入口, register 内完成本子系统的订阅/接线; MiningDim 主类 List 列尾追加一行即装配)。
 *
 * compileOnly 铁律 (范式对齐 {@code com.miningdim.champion.ChampionSystem}): Champions 是 compileOnly, dev 运行期
 * 不加载。本入口类自身不 import 任何 top.theillusivec4.champions.* —— 所有触 Champions 的装配 (封印真改/探测/加强
 * 奖励/伤害加成 handler) 集中在 {@code com.miningdim.job.agent.integration} 隔离包, 仅在 {@link ModList#isLoaded(String)}
 * ("champions") 为真时经 {@code AgentIntegrationBootstrap} 装配触达。Champions 未加载 (dev / 未装) 时整条 Champions
 * 路径不被类加载 (防 NoClassDefFoundError); 纯逻辑层 (五支线查表/封印账本/加强奖励倍率/悬赏计数/周序/伤害加成%)
 * 照常工作, GameTest 照跑。
 *
 * 接线点:
 *  - 集成层装配: 经 {@code AgentIntegrationBootstrap.assemble} 挂封印/奖励/伤害加成 handler + bind 封印接缝。
 *  - 生命周期清理: ServerStoppingEvent 清纯逻辑封印账本 ({@link SealRegistry#reset}) + 经接缝清执行侧词条快照 +
 *    解封印接缝绑定, 防跨存档脏引用 (执行侧快照清理经 champions-free 的 {@link AgentSealSeam}, 本类不 import Champions)。
 *
 * 等级/经验数据: 走共享职业框架 capability (JobProgress, JobId.AGENT), 不新挂 capability (与军火商同范式)。
 * 悬赏进度/周青辉石软上限: 走自有 {@link AgentBountySavedData} (overworld 持久层, 按 ownerUUID), 与经验态解耦。
 */
public final class AgentSystem implements Subsystem {

    private static final Logger LOGGER = LoggerFactory.getLogger("miningdim/agent");

    /** Champions modid (ModList 守卫; 集成层装配前必查, 防 dev compileOnly 未加载触 NoClassDefFoundError)。 */
    public static final String CHAMPIONS_MODID = "champions";

    @Override
    public void register(IEventBus modBus, IEventBus forgeBus) {
        // 生命周期清理 handler 不触 Champions, 无条件挂 (清纯逻辑层封印账本 + 经接缝清执行侧)。
        forgeBus.register(this);

        if (!ModList.get().isLoaded(CHAMPIONS_MODID)) {
            LOGGER.info("[agent] Champions not loaded; agent champion integration disabled (pure logic still active)");
            return;
        }

        // Champions 已加载: 装配集成层 (封印/奖励/伤害加成 handler 挂 forgeBus + bind 封印接缝)。
        // 集成层入口隔离在独立 bootstrap 类: AgentSystem 不直接 import 任何 Champions 类, 守卫后才触达。
        com.miningdim.job.agent.integration.AgentIntegrationBootstrap.assemble(forgeBus);
        LOGGER.info("[agent] Champions loaded; agent integration assembled (detect + seal + enhanced reward + bounty + damage bonus)");
    }

    @Override
    public String name() {
        return "AgentSystem";
    }

    /**
     * 服务端停止: 清纯逻辑层封印账本 ({@link SealRegistry#reset}) + 经接缝清执行侧词条快照 + 解封印接缝绑定,
     * 防跨存档/跨重启脏引用。AgentBountySavedData 是 overworld 持久层 (随存档落盘), 不在此 reset。
     */
    @SubscribeEvent
    public void onServerStopping(ServerStoppingEvent event) {
        SealRegistry.reset();
        AgentSealSeam.onServerStopping(); // 经接缝清执行侧原词条快照 (Champions 已加载才有实现; 未加载空操作)。
        AgentSealSeam.unbind();
    }
}
