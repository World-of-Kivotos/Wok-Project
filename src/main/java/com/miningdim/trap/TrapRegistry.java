package com.miningdim.trap;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 静态陷阱协议级伪装注册表 (矿洞维度级 {@link SavedData})。用户裁决: 世界里不允许存在可被 F3/Jade/矿透识破的
 * {@code miningdim:trap_ore} 方块 —— 区块加载时 {@link TrapDisguiseConverter} 把 trap_ore 就地换成真原版矿石,
 * 陷阱身份 (哪个坐标是陷阱、是哪种 {@link StaticTrapKind}) 只存进本注册表。触发 ({@link StaticTrapTrigger})、
 * 探测 ({@link com.miningdim.job.miner.TrapScanService})、连锁 ({@link com.miningdim.job.miner.ChainMiningEngine})
 * 一律查本表判定陷阱身份, 而非查世界方块类型 (世界里已是无法区分真假的普通矿石)。
 *
 * 结构: chunk (long key) -> (packed BlockPos -> StaticTrapKind)。按 chunk 分桶使 {@link #nearby} 与
 * {@link #clearChunk} 只遍历相关桶, {@link #get} 两跳哈希 O(1)。持久化每 chunk 一组并行 LongArray(位置)+ByteArray(种类
 * ordinal), 紧凑。任何持久字段变更即 {@link #setDirty()}。
 *
 * 揭示态 ({@link #markRevealed}/{@link #isRevealed}) 是纯内存缓存 (探测过的玩家对陷阱位的已知), 不序列化 ——
 * 重启丢失可接受 (探测是可重复的低成本操作, 无需跨重启持久); 故其变更不 setDirty。
 *
 * 线程: 仅服务端主线程读写 (区块加载 / BreakEvent / tick 均主线程), 与 {@link com.miningdim.job.munitions.MunitionsSavedData}
 * 同纪律。1.20.1 computeIfAbsent 三参签名 (load, create, name); SavedData.Factory 是 1.20.2+ 不可用。
 */
public final class TrapRegistry extends SavedData {

    /** DimensionDataStorage 数据文件名。 */
    public static final String DATA_NAME = "miningdim_trap_registry";

    private static final String K_CHUNKS = "chunks";
    private static final String K_CHUNK_KEY = "c";
    private static final String K_POSITIONS = "p";
    private static final String K_KINDS = "k";

    /** ordinal 反查表快照 (避免每次反序列化调 values() 克隆数组)。ordinal 编码依赖 {@link StaticTrapKind} 声明顺序稳定。 */
    private static final StaticTrapKind[] KIND_BY_ORDINAL = StaticTrapKind.values();

    /** chunk long key -> (BlockPos.asLong -> 陷阱种类)。分桶供 nearby/clearChunk 局部遍历。 */
    private final Map<Long, Map<Long, StaticTrapKind>> byChunk = new HashMap<>();

    /** 玩家 -> 已探测揭示的陷阱位 (BlockPos.asLong)。纯内存, 不序列化 (重启丢失可接受)。 */
    private final Map<UUID, Set<Long>> revealed = new HashMap<>();

    public TrapRegistry() {
    }

    /** 取/建某维度的陷阱注册表 (陷阱只在矿洞维度, 务必传矿洞 ServerLevel)。 */
    public static TrapRegistry get(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(TrapRegistry::load, TrapRegistry::new, DATA_NAME);
    }

    // ---- 陷阱身份 (持久) ----

    /** 登记一个坐标为指定种类的陷阱 (伪装转换时调, 覆盖同位旧值)。 */
    public void put(BlockPos pos, StaticTrapKind kind) {
        byChunk.computeIfAbsent(new ChunkPos(pos).toLong(), k -> new HashMap<>()).put(pos.asLong(), kind);
        setDirty();
    }

    /** 移除某坐标的陷阱条目 (触发后 / 幽灵条目清理); 桶清空即回收, 避免空桶长期占表。 */
    public void remove(BlockPos pos) {
        long chunkKey = new ChunkPos(pos).toLong();
        Map<Long, StaticTrapKind> inner = byChunk.get(chunkKey);
        if (inner == null) {
            return;
        }
        if (inner.remove(pos.asLong()) != null) {
            if (inner.isEmpty()) {
                byChunk.remove(chunkKey);
            }
            setDirty();
        }
    }

    /** 某坐标的陷阱种类 (无则 null); 两跳哈希 O(1)。 */
    public StaticTrapKind get(BlockPos pos) {
        Map<Long, StaticTrapKind> inner = byChunk.get(new ChunkPos(pos).toLong());
        return inner == null ? null : inner.get(pos.asLong());
    }

    /**
     * 收集以 center 为心、半径 radius 的立方 chunk 范围内全部陷阱条目 (供探测扫描做精确球面过滤)。
     * 只遍历命中的 chunk 桶 (未命中 chunk 无桶, 直接跳过), 不做球面距离判定 —— 距离/致死/上限过滤由调用方
     * ({@link com.miningdim.job.miner.TrapScanService}) 负责, 本方法只按 chunk 粒度收候选。
     */
    public List<Entry> nearby(BlockPos center, int radius) {
        List<Entry> out = new ArrayList<>();
        int minChunkX = (center.getX() - radius) >> 4;
        int maxChunkX = (center.getX() + radius) >> 4;
        int minChunkZ = (center.getZ() - radius) >> 4;
        int maxChunkZ = (center.getZ() + radius) >> 4;
        for (int cx = minChunkX; cx <= maxChunkX; cx++) {
            for (int cz = minChunkZ; cz <= maxChunkZ; cz++) {
                Map<Long, StaticTrapKind> inner = byChunk.get(ChunkPos.asLong(cx, cz));
                if (inner == null) {
                    continue;
                }
                for (Map.Entry<Long, StaticTrapKind> e : inner.entrySet()) {
                    out.add(new Entry(BlockPos.of(e.getKey()), e.getValue()));
                }
            }
        }
        return out;
    }

    /** 清空某 chunk 的全部陷阱条目 (实例重置时调, 防旧条目变幽灵陷阱); 返回被清条目数。 */
    public int clearChunk(ChunkPos chunkPos) {
        Map<Long, StaticTrapKind> inner = byChunk.remove(chunkPos.toLong());
        if (inner == null || inner.isEmpty()) {
            return 0;
        }
        setDirty();
        return inner.size();
    }

    // ---- 揭示态 (内存, 不序列化) ----

    /** 标记某玩家已探测揭示某陷阱位 (只对实际下发给玩家的命中调; 被致死过滤隐藏的不算揭示)。 */
    public void markRevealed(UUID player, BlockPos pos) {
        revealed.computeIfAbsent(player, k -> new HashSet<>()).add(pos.asLong());
    }

    /** 某玩家是否已揭示某陷阱位 (连锁挖矿据此决定跳过已知陷阱而非触发)。 */
    public boolean isRevealed(UUID player, BlockPos pos) {
        Set<Long> set = revealed.get(player);
        return set != null && set.contains(pos.asLong());
    }

    // ---- 序列化 (仅陷阱身份; 揭示态不落盘) ----

    @Override
    public CompoundTag save(CompoundTag tag) {
        ListTag chunks = new ListTag();
        for (Map.Entry<Long, Map<Long, StaticTrapKind>> ce : byChunk.entrySet()) {
            Map<Long, StaticTrapKind> inner = ce.getValue();
            if (inner.isEmpty()) {
                continue;
            }
            long[] positions = new long[inner.size()];
            byte[] kinds = new byte[inner.size()];
            int i = 0;
            for (Map.Entry<Long, StaticTrapKind> e : inner.entrySet()) {
                positions[i] = e.getKey();
                kinds[i] = (byte) e.getValue().ordinal();
                i++;
            }
            CompoundTag chunk = new CompoundTag();
            chunk.putLong(K_CHUNK_KEY, ce.getKey());
            chunk.putLongArray(K_POSITIONS, positions);
            chunk.putByteArray(K_KINDS, kinds);
            chunks.add(chunk);
        }
        tag.put(K_CHUNKS, chunks);
        return tag;
    }

    public static TrapRegistry load(CompoundTag tag) {
        TrapRegistry data = new TrapRegistry();
        ListTag chunks = tag.getList(K_CHUNKS, Tag.TAG_COMPOUND);
        for (int i = 0; i < chunks.size(); i++) {
            CompoundTag chunk = chunks.getCompound(i);
            long[] positions = chunk.getLongArray(K_POSITIONS);
            byte[] kinds = chunk.getByteArray(K_KINDS);
            // 并行数组长度不一致即存档损坏, 让其自然抛 (C9, 不静默截断掩盖); 正常写入路径二者恒等长。
            Map<Long, StaticTrapKind> inner = new HashMap<>(positions.length);
            for (int j = 0; j < positions.length; j++) {
                inner.put(positions[j], KIND_BY_ORDINAL[kinds[j]]);
            }
            if (!inner.isEmpty()) {
                data.byChunk.put(chunk.getLong(K_CHUNK_KEY), inner);
            }
        }
        return data;
    }

    /** (陷阱位, 种类) 元组; {@link #nearby} 的返回元素。 */
    public record Entry(BlockPos pos, StaticTrapKind kind) {
    }
}
