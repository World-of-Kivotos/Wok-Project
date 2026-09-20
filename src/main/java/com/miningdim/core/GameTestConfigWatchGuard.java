package com.miningdim.core;

import com.electronwill.nightconfig.core.file.FileWatcher;
import net.minecraft.gametest.framework.GameTestServer;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.config.ModConfig;
import net.minecraftforge.fml.event.config.ModConfigEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * runGameTestServer 期间摘掉本 mod 配置文件的 nightconfig 文件监视器。
 *
 * <h3>为什么需要</h3>
 * Forge 的 {@code ConfigFileTypeHandler.reader} 给每个配置文件建了一个 {@code CommentedFileConfig},
 * 建的时候开了 {@code autosave()}, 同时通过 {@code FileWatcher.defaultInstance().addWatch} 挂了监视器。
 * 两件事合起来的后果是: 只要有人调 {@code ForgeConfigSpec.ConfigValue.set}, 整份 TOML 就会被同步写回磁盘,
 * 监视器线程随即在<b>另一个线程</b>上把这份文件重新 load 进内存。
 *
 * 生产环境这正是想要的行为 —— 运营改完 toml, 下一次读就跟着变, {@code *StateReadsConfigLive} 那族用例
 * 断言的就是它。但 GameTest 里是测试自己在高频写配置, 于是自己触发自己的异步重载, 三个后果:
 * <ol>
 *   <li>写文件与监视器读文件在 Windows 上撞车, 抛
 *       {@code ConfigLoadingException: Failed loading config file ... }, Forge 把文件改名成 {@code .bak}
 *       重建 —— 表现为若干用例报 "另一个程序正在使用此文件";</li>
 *   <li>重载失败会让内存里那份配置退化成默认值, 于是<b>任何</b>正在读配置的用例都可能拿到错值。
 *       实测见过 {@code munitionsStateReadsConfigLive} / {@code engineerStateReadsConfigLive}
 *       在恢复原值后读回来仍是探针值, 以及 {@code networkOverheatsUnderSustainedLoadThenRecovers}
 *       的平衡态吞吐算成 0;</li>
 *   <li>这些失败随机器负载浮动, 与被测代码无关, 会把"质量门全绿"这个判据本身变得不可信。</li>
 * </ol>
 *
 * <h3>为什么这么修</h3>
 * 只摘监视器, 不动别的: {@code ConfigValue.set} 照常写内存并清缓存, {@code get} 照常实时读,
 * 所以 {@code *StateReadsConfigLive} 要断言的"没有在初始化时缓存"依然被完整覆盖; 消失的只是
 * 测试环境里没有意义、且只会自己打自己的那条异步重载路径。
 *
 * 门槛卡在 {@code GameTestServer} 这个具体类型上, 不是卡 {@code forge.enabledGameTestNamespaces} ——
 * 后者 client/server/gameTestServer 三个 run 都设了, 会把 dev 服务器上手改 toml 热重载的能力一并关掉。
 */
public final class GameTestConfigWatchGuard {

    private static final Logger LOGGER = LoggerFactory.getLogger("miningdim/core/config-watch");

    /** 本 mod 已加载配置的磁盘路径; 由 ModConfigEvent.Loading 收集, 与加载顺序无关。 */
    private static final Set<Path> LOADED_CONFIG_FILES = ConcurrentHashMap.newKeySet();

    /** 已摘掉监视器的文件; 为空表示 detachAll 还没跑过。供契约测试核对。 */
    private static final Set<Path> DETACHED_FILES = ConcurrentHashMap.newKeySet();

    private GameTestConfigWatchGuard() {
    }

    /** 实际摘掉监视器的配置文件快照 (GameTest 契约用)。 */
    public static Set<Path> detachedFiles() {
        return Set.copyOf(DETACHED_FILES);
    }

    public static void register(IEventBus modBus, IEventBus forgeBus) {
        modBus.addListener((ModConfigEvent.Loading event) -> remember(event.getConfig()));
        forgeBus.addListener((ServerStartedEvent event) -> {
            if (event.getServer() instanceof GameTestServer) {
                detachAll();
            }
        });
    }

    private static void remember(ModConfig config) {
        if (!MiningConstants.MODID.equals(config.getModId())) {
            return;
        }
        // getFullPath 读的是已经建好的 CommentedFileConfig, Loading 事件触发时它必然在位
        // (ConfigTracker.openConfig 先 setConfigData 再 fireEvent)。
        LOADED_CONFIG_FILES.add(config.getFullPath());
    }

    private static void detachAll() {
        FileWatcher watcher = FileWatcher.defaultInstance();
        for (Path path : LOADED_CONFIG_FILES) {
            watcher.removeWatch(path);
            DETACHED_FILES.add(path);
        }
        LOGGER.info("[miningdim] GameTest 环境: 已摘掉 {} 份配置文件的热重载监视器, 避免测试自写自重载互相打架",
                DETACHED_FILES.size());
    }
}
