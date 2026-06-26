package com.miningdim.stacking;

import com.miningdim.core.Subsystem;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.entity.EntityTypeTest;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 实体堆叠子系统入口 (需求规格阶段 1; implements core.Subsystem; 模块化铁律 3 自注册)。
 *
 * 装配: 在主类 {@code registerSubsystems()} 加一行 (marriage 之后)。register 内只挂事件 (ServerTickEvent 周期扫描
 * + ServerStoppingEvent 清瞬态)。配置 SPEC 由本系统 registerConfig 到 SERVER 级 (与 ChefSystem 同范式: 各职业/子
 * 系统自持配置, 不碰中央 MiningServerConfig)。
 *
 * 触发与性能 (NFR-3 禁每 tick O(n^2)):
 *  - 周期扫描: 每 {@code merge.scanIntervalTicks} (默认 100=5s) 对每个已加载 {@link ServerLevel} 扫一次, 非每 tick。
 *  - 区块本地配对: 把存活 LivingEntity 按 {@link ChunkPos} 分桶, 仅桶内候选交给 {@link StackMerge#mergeCandidates}
 *    两两就近合并 —— 不做全世界 O(n^2) 全配对。
 *  - require_moved: trigger=ON_MOVE 且 requireMoved=true 时, 仅 "自上次扫描后跨方块" 的实体进入候选 (静止农场不
 *    反复扫)。用进程级 {@code Map<entityId, packedBlockPos>} 记录上次位置; 新生实体 (无记录) 视为已移动, 必参与
 *    首次合并 (AC-1 spawn 即合并)。
 *
 * 线程 (NFR-5): 全部在服务端主线程 (ServerTickEvent END 阶段) 执行, 无并发。
 *
 * 持久化 (NFR-6): 堆叠数由 {@link StackData} 写入 entity.getPersistentData(), 随实体 NBT 自动落盘, 本系统不持有
 * 任何需落盘状态 —— lastSeenPos 仅是 require_moved 优化用的瞬态缓存, 跨重启丢失无害 (重启后所有实体一律视为已移动)。
 */
public final class StackingSystem implements Subsystem {

    private static final Logger LOGGER = LoggerFactory.getLogger("miningdim/stacking");

    /** 进程级瞬态: entityId -> 上次扫描时的打包方块坐标 (require_moved 用)。主线程独占, 不需并发容器。 */
    private final Map<Integer, Long> lastSeenPos = new HashMap<>();

    /** 距上次周期扫描的 tick 计数 (达 scanIntervalTicks 触发一次扫描)。 */
    private int sinceLastScan;

    @Override
    public void register(IEventBus modBus, IEventBus forgeBus) {
        net.minecraftforge.fml.ModLoadingContext.get().registerConfig(
                net.minecraftforge.fml.config.ModConfig.Type.SERVER,
                StackingConfig.SPEC, "miningdim-stacking.toml");
        forgeBus.register(this);
        // 阶段 2 产出倍增 handler (各自无状态, 注册独立监听对象): 击杀掉落 (FR-2) / 被动产出 (FR-3) / 繁殖 (FR-4)。
        forgeBus.register(new StackDeath());
        forgeBus.register(new StackPassive());
        forgeBus.register(new StackBreed());
        LOGGER.info("[miningdim] stacking subsystem registered (merge + persistence + drops/passive/breed; FR-1..4 / NFR-6)");
    }

    @SubscribeEvent
    public void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        MinecraftServer server = net.minecraftforge.server.ServerLifecycleHooks.getCurrentServer();
        if (server == null) {
            return;
        }
        int interval = StackingConfig.MERGE_SCAN_INTERVAL.get();
        if (++sinceLastScan < interval) {
            return;
        }
        sinceLastScan = 0;
        // 本轮 (全维度) 见过的可堆叠实体 id; 扫描末尾据此回收 lastSeenPos 中已消失者 (跨维度统一 prune,
        // 避免按维度 prune 时把别的维度实体误逐出)。
        java.util.Set<Integer> seenThisScan = new java.util.HashSet<>();
        for (ServerLevel level : server.getAllLevels()) {
            scanLevel(level, seenThisScan);
        }
        lastSeenPos.keySet().retainAll(seenThisScan);
    }

    /**
     * 扫一个维度: 收集存活可堆叠 LivingEntity, 按区块分桶, require_moved 过滤后桶内合并。
     * 把本维度见到的可堆叠实体 id 记入 seenThisScan (调用方据全集回收 lastSeenPos)。
     */
    private void scanLevel(ServerLevel level, java.util.Set<Integer> seenThisScan) {
        boolean requireMoved = StackingConfig.MERGE_TRIGGER.get() == StackingConfig.MergeTrigger.ON_MOVE
                && StackingConfig.MERGE_REQUIRE_MOVED.get();

        Map<ChunkPos, List<Entity>> buckets = new HashMap<>();
        // 含至少一个 "本轮移动过" 实体的区块 (require_moved 下只对这些区块跑合并)。一旦某区块有任意移动,
        // 则该区块内全部可堆叠实体 (含静止的堆叠锚) 一并参与 —— 否则静止锚永远吸不进新到的散怪 (require_moved 漏并)。
        java.util.Set<ChunkPos> chunksWithMovement = new java.util.HashSet<>();

        // 用 EntityTypeTest 精确取 LivingEntity (底层走 EntitySection 索引), 避免对全量实体逐个 instanceof。
        List<? extends LivingEntity> all = level.getEntities(
                EntityTypeTest.forClass(LivingEntity.class), e -> true);

        for (LivingEntity e : all) {
            if (!StackMerge.canStack(e)) {
                // 不可堆叠 (命名/驯服/Boss/blacklist): 不参与, 也不占 require_moved 记录。
                continue;
            }
            seenThisScan.add(e.getId());
            ChunkPos chunk = new ChunkPos(e.blockPosition());
            buckets.computeIfAbsent(chunk, k -> new ArrayList<>()).add(e);

            long packed = BlockPos.asLong(e.getBlockX(), e.getBlockY(), e.getBlockZ());
            if (!requireMoved || hasMoved(e.getId(), packed)) {
                chunksWithMovement.add(chunk);
            }
            lastSeenPos.put(e.getId(), packed);
        }

        for (Map.Entry<ChunkPos, List<Entity>> entry : buckets.entrySet()) {
            List<Entity> bucket = entry.getValue();
            // require_moved: 跳过整轮无移动的区块 (静止农场不反复扫, NFR-3)。非 require_moved 模式 chunksWithMovement
            // 含全部区块 (上面 !requireMoved 恒加入)。
            if (bucket.size() > 1 && chunksWithMovement.contains(entry.getKey())) {
                StackMerge.mergeCandidates(bucket);
            }
        }
    }

    /** 与上次记录比较是否跨方块。无记录 (新生实体) 视为已移动 (返回 true), 保证首次必参与合并。 */
    private boolean hasMoved(int entityId, long packed) {
        Long prev = lastSeenPos.get(entityId);
        return prev == null || prev != packed;
    }

    @SubscribeEvent
    public void onServerStopping(ServerStoppingEvent event) {
        // 清瞬态 require_moved 缓存, 防跨存档脏引用 (与其它子系统 reset 同纪律)。堆叠数是实体 NBT, 随存档落盘不在此清。
        lastSeenPos.clear();
        sinceLastScan = 0;
    }

    @Override
    public String name() {
        return "StackingSystem";
    }
}
