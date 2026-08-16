package com.miningdim.stacking;

import com.miningdim.core.Subsystem;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.entity.EntityTypeTest;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.EntityJoinLevelEvent;
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
 * + ServerStoppingEvent 清瞬态 + EntityJoinLevelEvent 旧存档消毒)。配置 SPEC 由本系统 registerConfig 到 SERVER 级
 * (与 ChefSystem 同范式: 各职业/子系统自持配置, 不碰中央 MiningServerConfig)。
 *
 * 白名单准入 (主控决策 D1): 周期扫描已收敛到 {@link StackMerge#canMerge} 作为唯一遍历 predicate, 只把
 * minecraft:pig / chicken / sheep / cow 四种 {@link Animal} 纳入候选, 其余 LivingEntity (含玩家/村民/盔甲架/
 * 矿洞刷怪/自研精英怪) 从不进入 buckets, 详见 {@link #scanLevel}。
 *
 * 总开关 ({@link StackingConfig#ENABLED}, F094 修复配套): false 时 {@link #onServerTick} 直接短路, 不发生任何新
 * 合并; 已成堆叠不受影响, 掉落 (FR-2)/被动产出 (FR-3)/繁殖 (FR-4)/拆分与拴绳 (FR-5) 各自独立监听事件, 均不经本方法。
 *
 * 触发与性能 (NFR-3 禁每 tick O(n^2)):
 *  - 周期扫描: 每 {@code merge.scanIntervalTicks} (默认 100=5s) 对每个已加载 {@link ServerLevel} 扫一次, 非每 tick。
 *  - 区块本地配对: 把存活可堆叠 {@link Animal} 按 {@link ChunkPos} 分桶, 仅桶内候选交给
 *    {@link StackMerge#mergeCandidates} 两两就近合并 —— 不做全世界 O(n^2) 全配对。
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
        // 阶段 2 产出倍增 handler (各自无状态, 注册独立监听对象): 击杀掉落 (FR-2) / 被动产出 (FR-3) / 繁殖 (FR-4) /
        // 拆分与拴绳 (FR-5)。
        forgeBus.register(new StackDeath());
        forgeBus.register(new StackPassive());
        forgeBus.register(new StackBreed());
        forgeBus.register(new StackSplit());
        LOGGER.info("[miningdim] stacking subsystem registered (merge + persistence + drops/passive/breed/split; FR-1..5 / NFR-6)");
    }

    @SubscribeEvent
    public void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        if (!StackingConfig.ENABLED.get()) {
            // 总开关关停: 不发生新合并 (F094 修复配套)。已成堆叠不受影响 —— 掉落/被动/拆分 handler 各自独立监听,
            // 不经过本方法。放在 sinceLastScan 自增之前 return, 关停期间不空耗计数。
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
     * 扫一个维度: 收集存活可堆叠 {@link Animal} (白名单四种), 按区块分桶, require_moved 过滤后桶内合并。
     * 把本维度见到的可堆叠实体 id 记入 seenThisScan (调用方据全集回收 lastSeenPos)。
     *
     * F094 性能修复: {@code getEntities(EntityTypeTest, Predicate)} 底层 (ServerLevel.java:772-776 ->
     * LevelEntityGetterAdapter.java:32-34 -> EntityLookup.java:19-27) 在遍历内、add 进 List 之前就执行 predicate ——
     * EntityLookup 只有 byId/byUuid 两张表, 没有任何按 EntityType 的索引, {@code for(T t : this.byId.values())}
     * 是对全量实体的线性遍历。故按四个 EntityType 分别调 getEntities 等于跑四趟全量遍历, 是性能倒退; 正确做法是把
     * {@link StackMerge#canMerge} (合并候选判据, 非结算侧 canStack —— 见该方法 Javadoc) 作为唯一一次遍历的
     * predicate 下沉传入, 只有通过白名单+二次过滤且当下确实可被合并的动物才会被 add 进 List、才会被后续代码触碰
     * persistentData。严禁"优化"回按类型分别取。
     */
    private void scanLevel(ServerLevel level, java.util.Set<Integer> seenThisScan) {
        boolean requireMoved = StackingConfig.MERGE_TRIGGER.get() == StackingConfig.MergeTrigger.ON_MOVE
                && StackingConfig.MERGE_REQUIRE_MOVED.get();

        Map<ChunkPos, List<Entity>> buckets = new HashMap<>();
        // 含至少一个 "本轮移动过" 实体的区块 (require_moved 下只对这些区块跑合并)。一旦某区块有任意移动,
        // 则该区块内全部可堆叠实体 (含静止的堆叠锚) 一并参与 —— 否则静止锚永远吸不进新到的散怪 (require_moved 漏并)。
        java.util.Set<ChunkPos> chunksWithMovement = new java.util.HashSet<>();

        List<? extends Animal> candidates = level.getEntities(
                EntityTypeTest.forClass(Animal.class), StackMerge::canMerge);

        for (Animal e : candidates) {
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

    /**
     * 旧存档脏堆叠数据消毒 (F004/F005 历史遗留回收)。白名单化前的旧版本对任意 LivingEntity (含玩家/村民/
     * 盔甲架/僵尸骷髅/精英怪) 都可能写下 {@link StackData#KEY}; 白名单化后这些实体永久无法再走 canStack, 其
     * persistentData 里的堆叠数据成为死数据, 且若实体仍带着本系统当年打的 "xN" CustomName, 会持续误导服主
     * 与玩家 (以为它是活跃堆叠)。本 handler 在实体加入维度时一次性核对并回收。
     *
     * 判定链刻意分层, 前两层是廉价短路 (绝大多数实体在这里就返回, 不触碰 persistentData 分配):
     *  1) !hasCustomName(): O(1) 的 entityData 读。本系统写 StackSize&gt;1 时必然经 applyLabel 打了 CustomName,
     *     残留 StackSize&lt;=1 的实体 (从未真正合并过, 或已被拆到 1) 无 CustomName 也无害, 直接放行。
     *  2) isStackableType() 为真: 白名单内四种动物, StackData 是合法在用数据, 不消毒。
     *  3) !hasStackData(): 无堆叠数据可消毒。
     *  到这里说明是旧版本给非白名单实体写下的脏堆叠数。仅当当前 CustomName 恰好等于 {@link StackMerge#applyLabel}
     *  会生成的那串时才清名 —— 名字不匹配说明玩家后来用命名牌覆盖过, 必须保留玩家命名; 无论清不清名, 都整体回收
     *  persistentData 并记诊断日志 (供服主核账, 不得删)。
     */
    @SubscribeEvent
    public void onEntityJoinLevel(EntityJoinLevelEvent event) {
        if (event.getLevel().isClientSide) {
            return;
        }
        Entity entity = event.getEntity();
        if (!entity.hasCustomName()) {
            return;
        }
        if (StackMerge.isStackableType(entity)) {
            return;
        }
        if (!StackData.hasStackData(entity)) {
            return;
        }

        int staleSize = StackData.getStackSize(entity);
        net.minecraft.network.chat.Component expectedLabel = net.minecraft.network.chat.Component.empty()
                .append(entity.getType().getDescription())
                .append(net.minecraft.network.chat.Component.literal(" x" + staleSize));
        net.minecraft.network.chat.Component actualName = entity.getCustomName();
        if (actualName != null && actualName.getString().equals(expectedLabel.getString())) {
            entity.setCustomName(null);
            entity.setCustomNameVisible(false);
        }
        StackData.clearStackData(entity);
        LOGGER.warn("[miningdim] sanitized stale stacking data on non-whitelisted entity {} (was stack size {}); "
                        + "pre-whitelist versions could write stack data to any LivingEntity",
                net.minecraft.world.entity.EntityType.getKey(entity.getType()), staleSize);
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
