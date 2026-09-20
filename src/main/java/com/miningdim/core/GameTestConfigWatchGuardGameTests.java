package com.miningdim.core;

import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

/**
 * {@link GameTestConfigWatchGuard} 的契约测试。
 *
 * 守卫本身是为了让质量门可信而存在的: 它一旦被摘掉或者挂错时机, 表面上什么都不会红, 只是全套用例
 * 重新开始随机变色 —— 而随机变色恰恰最容易被当成"重跑一次就好"糊过去。所以这里必须有一条会红的断言。
 *
 * 判据取磁盘这个独立事实源: 世界存档 serverconfig 目录下每一份 {@code miningdim-*.toml}, 都必须出现在
 * 守卫实际摘过监视器的清单里。少一份就说明那一份的热重载还在, 也就还会自写自重载。
 */
@GameTestHolder(MiningConstants.MODID)
@PrefixGameTestTemplate(false)
public final class GameTestConfigWatchGuardGameTests {

    private static final String EMPTY = "empty";
    private static final String BATCH = "core_config_watch_guard";

    private GameTestConfigWatchGuardGameTests() {
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void everyServerConfigFileHasItsHotReloadWatcherDetached(GameTestHelper helper) {
        Set<Path> detached = GameTestConfigWatchGuard.detachedFiles();
        helper.assertTrue(!detached.isEmpty(),
                "守卫没跑: GameTestServer 起来了却一份配置监视器都没摘, 检查 MiningDim 里的 register 调用"
                        + "与 ServerStartedEvent 的挂载");

        Path serverConfigDir = helper.getLevel().getServer().getWorldPath(
                net.minecraft.world.level.storage.LevelResource.ROOT).resolve("serverconfig");
        List<Path> onDisk = ownConfigFiles(serverConfigDir);
        helper.assertTrue(!onDisk.isEmpty(),
                "serverconfig 目录下一份 miningdim-*.toml 都没有, 断言失去意义: " + serverConfigDir);

        // 逐份比对: 磁盘上存在的, 守卫必须都摘过。比"数量相等"更严, 且不会因为新增配置而误红 ——
        // 新增一份配置只要走的是标准 registerConfig, 就会被 ModConfigEvent.Loading 一并收走。
        for (Path file : onDisk) {
            Path normalized = file.toAbsolutePath().normalize();
            boolean covered = detached.stream()
                    .anyMatch(path -> path.toAbsolutePath().normalize().equals(normalized));
            helper.assertTrue(covered,
                    normalized.getFileName() + " 的热重载监视器没被摘掉; 它会在测试写配置时异步重载,"
                            + " 读到半截文件就把内存配置打成默认值");
        }
        helper.succeed();
    }

    private static List<Path> ownConfigFiles(Path serverConfigDir) {
        if (!Files.isDirectory(serverConfigDir)) {
            throw new IllegalStateException("找不到世界存档的 serverconfig 目录: " + serverConfigDir);
        }
        try (Stream<Path> files = Files.list(serverConfigDir)) {
            return files.filter(path -> {
                String name = path.getFileName().toString();
                return name.startsWith(MiningConstants.MODID + "-") && name.endsWith(".toml");
            }).toList();
        } catch (IOException exception) {
            throw new IllegalStateException("读取 serverconfig 目录失败: " + serverConfigDir, exception);
        }
    }
}
