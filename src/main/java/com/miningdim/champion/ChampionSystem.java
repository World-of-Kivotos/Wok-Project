package com.miningdim.champion;

import com.miningdim.champion.bloodpool.BloodPoolRegistry;
import com.miningdim.champion.reward.ContributionTracker;
import com.miningdim.core.Subsystem;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.ModList;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 精英怪子系统入口 (implements core.Subsystem; ChampionStarAffix spec 第十四章实现拆分)。模块化铁律 3: 唯一入口,
 * 在 register 内完成本子系统的注册/订阅/接线; MiningDim 主类 List 列尾追加一行即装配。
 *
 * compileOnly 铁律 (核心): Champions 是 compileOnly, dev 运行期不加载。本入口类自身不 import 任何
 * top.theillusivec4.champions.* —— 所有触 Champions 的装配 (词条注册 / 升格 promoter / 血池+奖励 handler) 集中在
 * {@code com.miningdim.champion.integration} 隔离包, 仅在 {@link ModList#isLoaded(String)}("champions") 为真时
 * 经 {@link ChampionIntegrationBootstrap} 装配触达。Champions 未加载 (dev / 未装) 时整条 Champions 路径不被类加载
 * (防 NoClassDefFoundError), 纯逻辑层 (星表/血池数学/贡献池/红线/seam holder) 照常工作, GameTest 照跑。
 *
 * 接线点:
 *  - 词条注册: 仿内置 AffixTypes, 把本工程 35 词条追加到 Champions 持有的 AffixRegistry.AFFIXES (随其 RegisterEvent
 *    一并注册)。须在 mod 构造期触发 (早于 RegisterEvent), 故在 {@link #register} 内 (mod 构造) 守卫调用。
 *  - 升格 seam: 经 {@link ChampionSpawnSeam#bind} 注入 ChampionPromoter, 压力子系统 spawnMob 成功后回调升格。
 *  - 血池/奖励 handler: 挂 forgeBus (LivingHurtEvent 改伤+拦死 / LivingDeathEvent 拦死+奖励结算)。
 *  - 生命周期清理: ServerStoppingEvent 清血池/贡献账本/seam, 防跨存档脏引用。
 */
public final class ChampionSystem implements Subsystem {

    private static final Logger LOGGER = LoggerFactory.getLogger("miningdim/champion");

    /** Champions modid (ModList 守卫; 集成层装配前必查, 防 dev compileOnly 未加载触 NoClassDefFoundError)。 */
    public static final String CHAMPIONS_MODID = "champions";

    @Override
    public void register(IEventBus modBus, IEventBus forgeBus) {
        // 生命周期清理 handler 不触 Champions, 无条件挂 (清纯逻辑层注册表)。
        forgeBus.register(this);

        if (!ModList.get().isLoaded(CHAMPIONS_MODID)) {
            LOGGER.info("[champion] Champions not loaded; champion integration disabled (pure logic still active)");
            return;
        }

        // Champions 已加载: 装配集成层 (词条注册 + seam 升格绑定 + 血池/奖励 handler 挂 forgeBus)。
        // 集成层入口隔离在独立 bootstrap 类: ChampionSystem 不直接 import 任何 Champions 类, 守卫后才触达。
        ChampionIntegrationBootstrap.assemble(forgeBus);
        LOGGER.info("[champion] Champions loaded; champion integration assembled (35 affixes + blood pool + rewards)");
    }

    @Override
    public String name() {
        return "ChampionSystem";
    }

    /** 服务端停止: 清纯逻辑层运行态 (血池/贡献账本) + 解 seam 绑定, 防跨存档/跨重启脏引用。 */
    @SubscribeEvent
    public void onServerStopping(ServerStoppingEvent event) {
        BloodPoolRegistry.reset();
        ContributionTracker.reset();
        ChampionSpawnSeam.unbind();
    }
}
