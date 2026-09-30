package com.miningdim.district.guard;

import com.miningdim.district.core.DistrictBounds;
import com.miningdim.district.core.PlotArea;
import com.miningdim.district.guard.create.CreateBlockPolicy;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.GameTestServer;
import net.minecraftforge.server.ServerLifecycleHooks;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * GameTest 专用 (设计文档 22.14): 在每条用例自己的结构周围临时装一份区域, 并入一份测试快照装进门面。只在 GameTest 服务端
 * 上可用 (门同 DistrictFeature.forceForTest)。
 *
 * <p>用法: batch 的 {@code @BeforeBatch} 调 {@link #begin}, {@code @AfterBatch} 调 {@link #end}; 用例按结构里的相对坐标
 * {@link #put} 自己的区与地块 (按用例自己的键, 再 put 一次就是改划)。同一个 batch 里的用例并行跑, 区域各在各的结构上,
 * 互不干扰; batch 之间串行, 别的 batch 永远看到 OFF。用 DistrictTestEnv 的旧用例一律不装守卫, 行为不变。
 */
public final class GuardTestZones {

    private static final Map<String, DistrictZoneSnapshot.DistrictInput> ZONES = new LinkedHashMap<>();
    private static GuardSettings settings;

    private GuardTestZones() {
    }

    private static void requireGameTest() {
        if (!(ServerLifecycleHooks.getCurrentServer() instanceof GameTestServer)) {
            throw new IllegalStateException("GuardTestZones is only available on the GameTest server");
        }
    }

    /** batch 开始: 清空区域, 用给定的设置装上 (还没有区域时什么都不拦)。 */
    public static synchronized void begin(GuardSettings batchSettings) {
        requireGameTest();
        ZONES.clear();
        settings = batchSettings;
        publish();
    }

    /** batch 结束: 清空并卸下, 门面回到 OFF。 */
    public static synchronized void end() {
        ZONES.clear();
        settings = null;
        DistrictWorldGuards.reset();
    }

    /** 当前 batch 的设置。 */
    public static synchronized GuardSettings settings() {
        requireGameTest();
        if (settings == null) {
            throw new IllegalStateException("GuardTestZones.begin was not called for this batch");
        }
        return settings;
    }

    /** 换一份设置 (同步用例里临时换, 用完 {@link #useSettings} 换回)。 */
    public static synchronized void useSettings(GuardSettings next) {
        requireGameTest();
        settings = next;
        publish();
    }

    /** 暂时把门面换成 OFF, 返回复原用的句柄 (同步用例里用)。 */
    public static synchronized AutoCloseable suspend() {
        requireGameTest();
        DistrictWorldGuards.reset();
        return () -> {
            synchronized (GuardTestZones.class) {
                publish();
            }
        };
    }

    /**
     * 装上 (或改划) 一个测试区: key 是用例自己的键; 坐标都是结构里的相对坐标 (X/Z 闭区间); plots 每项 {minX, minZ,
     * maxX, maxZ}。返回区 id (= "gt-" + key)。
     */
    public static synchronized String put(GameTestHelper helper, String key, int minX, int minZ, int maxX, int maxZ,
                                          int[]... plots) {
        requireGameTest();
        String districtId = "gt-" + key;
        String dimension = helper.getLevel().dimension().location().toString();
        PlotArea area = absolute(helper, minX, minZ, maxX, maxZ);
        List<DistrictZoneSnapshot.PlotInput> plotInputs = new ArrayList<>();
        int number = 0;
        for (int[] plot : plots) {
            number++;
            plotInputs.add(new DistrictZoneSnapshot.PlotInput(districtId + "-p" + number,
                    absolute(helper, plot[0], plot[1], plot[2], plot[3]), key + "-" + number));
        }
        ZONES.put(key, new DistrictZoneSnapshot.DistrictInput(districtId, key,
                new DistrictBounds(dimension, area.minX(), area.minZ(), area.maxX(), area.maxZ()), plotInputs));
        publish();
        return districtId;
    }

    /**
     * 同上, 但坐标是绝对坐标 (只做判定、不碰方块的同步用例用: 区可以放在远离任何结构的地方)。dimension 为维度 id 串。
     */
    public static synchronized String putAbsolute(String dimension, String key, int minX, int minZ, int maxX,
                                                  int maxZ, int[]... plots) {
        requireGameTest();
        String districtId = "gt-" + key;
        List<DistrictZoneSnapshot.PlotInput> plotInputs = new ArrayList<>();
        int number = 0;
        for (int[] plot : plots) {
            number++;
            plotInputs.add(new DistrictZoneSnapshot.PlotInput(districtId + "-p" + number,
                    new PlotArea(plot[0], plot[1], plot[2], plot[3]), key + "-" + number));
        }
        ZONES.put(key, new DistrictZoneSnapshot.DistrictInput(districtId, key,
                new DistrictBounds(dimension, minX, minZ, maxX, maxZ), plotInputs));
        publish();
        return districtId;
    }

    /** 拿掉一个测试区。 */
    public static synchronized void remove(String key) {
        requireGameTest();
        ZONES.remove(key);
        publish();
    }

    private static PlotArea absolute(GameTestHelper helper, int x0, int z0, int x1, int z1) {
        BlockPos a = helper.absolutePos(new BlockPos(x0, 0, z0));
        BlockPos b = helper.absolutePos(new BlockPos(x1, 0, z1));
        return new PlotArea(Math.min(a.getX(), b.getX()), Math.min(a.getZ(), b.getZ()), Math.max(a.getX(), b.getX()),
                Math.max(a.getZ(), b.getZ()));
    }

    private static void publish() {
        if (settings == null) {
            DistrictWorldGuards.reset();
            return;
        }
        DistrictZoneSnapshot snapshot = DistrictZoneSnapshot.build(new ArrayList<>(ZONES.values()));
        DistrictWorldGuards.publish(new GuardView(settings, snapshot, CreateBlockPolicy.of(settings), true));
    }
}
