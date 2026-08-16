package com.miningdim.champion.integration;

import com.miningdim.champion.MiningChampionData;
import com.miningdim.champion.MiningChampions;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.AABB;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 冠军近场扫描单次共享快照 (F020/F100 收口)。此前 16 个 integration handler 各自独写一份同形状的"按玩家
 * {@value #VIEW_RANGE} 格 AABB 扫全部 LivingEntity 检出冠军"逻辑, 其中 14 个 SCAN_INTERVAL_TICKS=20 的 handler
 * 同余同相位, 每逢 {@code tickCount % 20 == 0} 全部在同一 tick 起跳做重复的全实体扫描, 成本与场上是否真有冠军
 * 无关, 形成每秒一次的主线程尖峰。本类把该扫描收口成单次静态 memo, 消费方改为直接读快照。
 *
 * memo 语义: 快照按 {@code server + server.getTickCount()} 复用, 只保证【同一 tick 内】跨 handler 一致; 不同 tick
 * 之间不共享 (下一 tick 的首个消费方即触发重扫)。全部消费方均挂 {@code TickEvent.Phase.END}, 与本类的 memo 判据
 * 同一时间基准, 故此保证在现有接线下是安全的。
 *
 * 已知取舍: 同一 tick 内更早跑的 handler 可能已致死/位移/新召出冠军, 快照不会反映这些变化 —— 消费方必须对
 * {@link Sighting#entity()} 逐条重查 {@code isAlive()}; 同 tick 新召唤出的冠军不在本次快照内, 顺延到下一扫描周期
 * 才会被收录 (与旧的各自独立扫描相比, 这只是把"扫描发生的时刻"从每个 handler 各自的 tick 内固定到该 tick 的
 * 第一次调用, 不改变冠军最终会被扫到的事实)。
 */
public final class ChampionProximityScanner {

    /** 冠军近场扫描半径 (格): 沿用被替换前 16 处独立扫描的统一半径 (公开, 供各 handler javadoc 引用)。 */
    public static final double VIEW_RANGE = 48.0D;

    private static MinecraftServer cachedServer;
    private static int cachedTick = -1;
    private static List<Sighting> cached = List.of();

    private ChampionProximityScanner() {
    }

    /**
     * 取当前 tick 的冠军近场快照 (server + tickCount 命中则直接复用, 否则重扫)。
     *
     * @param server 当前服务端
     * @return 本 tick 内可复用的冠军快照列表 (不可变)
     */
    public static List<Sighting> sightings(MinecraftServer server) {
        if (cachedServer == server && cachedTick == server.getTickCount()) {
            return cached;
        }
        Map<UUID, Sighting> byId = new LinkedHashMap<>();
        Set<UUID> notChampion = new HashSet<>();
        for (ServerLevel level : server.getAllLevels()) {
            List<ServerPlayer> players = level.players();
            if (players.isEmpty()) {
                continue;
            }
            for (ServerPlayer player : players) {
                AABB box = player.getBoundingBox().inflate(VIEW_RANGE);
                for (LivingEntity entity : level.getEntitiesOfClass(LivingEntity.class, box, LivingEntity::isAlive)) {
                    UUID id = entity.getUUID();
                    Sighting existing = byId.get(id);
                    if (existing != null) {
                        existing.viewers().add(player);
                        continue;
                    }
                    if (notChampion.contains(id)) {
                        continue; // 本 tick 已判过非冠军: 省掉同一实体被重复解析 capability。
                    }
                    MiningChampionData data = MiningChampions.get(entity).orElse(null);
                    if (data == null || !data.isChampion()) {
                        notChampion.add(id); // 负结果也 memo, 是省掉重复解析的关键。
                        continue;
                    }
                    Sighting sighting = new Sighting(level, entity, data, new HashSet<>());
                    sighting.viewers().add(player);
                    byId.put(id, sighting);
                }
            }
        }
        cached = List.copyOf(byId.values());
        cachedServer = server;
        cachedTick = server.getTickCount();
        return cached;
    }

    /** 清跨存档强实体引用 (供 {@code ChampionSystem#onServerStopping} 调用; 快照持 ServerLevel/LivingEntity 引用)。 */
    public static void reset() {
        cachedServer = null;
        cachedTick = -1;
        cached = List.of();
    }

    /** 一只冠军本 tick 的只读快照: 所在层 + 实体 + 冠军数据 + 本次扫描中看见它的玩家集。 */
    public record Sighting(ServerLevel level, LivingEntity entity, MiningChampionData data, Set<ServerPlayer> viewers) {
    }
}
