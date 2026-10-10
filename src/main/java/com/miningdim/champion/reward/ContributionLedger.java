package com.miningdim.champion.reward;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 单只冠军的贡献账本 (方案 D2): 玩家 UUID → {净伤, 毛伤, 首次命中 tick, 最近命中 tick}。
 *
 * 挂在冠军 capability ({@code MiningChampionData}) 上随实体 NBT 持久化, 生命周期与实体绑定:
 *  - 服务端重启 / 区块卸载 / 换维度: 随实体存盘读回, 与同样持久化的影子血 (current_hp) 口径一致, 不清零;
 *  - 自然 despawn / 实例重置 / 苦力怕自爆 (走 discard, 不发死亡事件): 随实体一起消失, 不再在内存里残留条目。
 *
 * 两道容量闸 (都在写入时顺手执行, 结算不做额外清理):
 *  - 过期: 最近命中早于 now − {@link #EXPIRY_TICKS} 的条目删除;
 *  - 上限: 条目超过 {@link #MAX_ENTRIES} 时淘汰净伤最小者 (同值先淘汰最近命中更早者, 再按 UUID 兜底定序)。
 *
 * 纯数据 + NBT, 无世界/实体引用, 与 {@code MiningChampionData} 同范式。服务端主线程串行读写, 不做并发保护。
 */
public final class ContributionLedger {

    /** 单只冠军账本条目上限 (NBT 膨胀闸)。 */
    public static final int MAX_ENTRIES = 64;

    /** 条目过期时长: 最近命中早于 now − 72,000 tick (1 小时) 的条目在下次写入时删除。 */
    public static final long EXPIRY_TICKS = 72_000L;

    private static final String NBT_PLAYER = "player";
    private static final String NBT_NET = "net";
    private static final String NBT_GROSS = "gross";
    private static final String NBT_FIRST = "first";
    private static final String NBT_LAST = "last";

    private final Map<UUID, Entry> entries = new HashMap<>();

    /** 单玩家累计态。 */
    private static final class Entry {
        double net;
        double gross;
        final long firstHitTick;
        long lastHitTick;

        Entry(long firstHitTick) {
            this.firstHitTick = firstHitTick;
            this.lastHitTick = firstHitTick;
        }
    }

    /**
     * 累加一笔命中。净伤 ≤0 (含重甲整次免疫) 的命中整笔不记: 它不扣血, 既不该开条目, 也不该刷新近期门槛用的
     * 最近命中 tick。毛伤非法 (负/NaN) 时按 0 记, 它只供诊断, 不值得因此丢掉一笔有效净伤。
     *
     * @param playerId    造成伤害的玩家
     * @param grossDamage 本次毛伤 (诊断)
     * @param netDamage   本次实际扣掉的血
     * @param nowTick     当前 gameTime
     */
    public void record(UUID playerId, double grossDamage, double netDamage, long nowTick) {
        if (playerId == null) {
            throw new IllegalArgumentException("playerId must not be null");
        }
        if (!(netDamage > 0.0D) || Double.isInfinite(netDamage)) {
            return;
        }
        pruneExpired(nowTick);
        Entry entry = entries.computeIfAbsent(playerId, id -> new Entry(Math.max(0L, nowTick)));
        entry.net += netDamage;
        if (grossDamage > 0.0D && !Double.isInfinite(grossDamage)) {
            entry.gross += grossDamage;
        }
        entry.lastHitTick = Math.max(entry.lastHitTick, nowTick);
        evictOverflow();
    }

    /** 删除最近命中早于 now − {@link #EXPIRY_TICKS} 的条目。 */
    private void pruneExpired(long nowTick) {
        long cutoff = nowTick - EXPIRY_TICKS;
        entries.values().removeIf(e -> e.lastHitTick < cutoff);
    }

    /** 超过上限时逐个淘汰净伤最小者。 */
    private void evictOverflow() {
        while (entries.size() > MAX_ENTRIES) {
            UUID victim = null;
            Entry worst = null;
            for (Map.Entry<UUID, Entry> e : entries.entrySet()) {
                if (worst == null || isWorse(e.getValue(), e.getKey(), worst, victim)) {
                    worst = e.getValue();
                    victim = e.getKey();
                }
            }
            entries.remove(victim);
        }
    }

    /** a 是否比 b 更该被淘汰: 净伤更小; 同值时最近命中更早; 再同则 UUID 更大者 (纯定序兜底)。 */
    private static boolean isWorse(Entry a, UUID aId, Entry b, UUID bId) {
        int byNet = Double.compare(a.net, b.net);
        if (byNet != 0) {
            return byNet < 0;
        }
        if (a.lastHitTick != b.lastHitTick) {
            return a.lastHitTick < b.lastHitTick;
        }
        return aId.compareTo(bId) > 0;
    }

    public boolean isEmpty() {
        return entries.isEmpty();
    }

    public int size() {
        return entries.size();
    }

    public void clear() {
        entries.clear();
    }

    /**
     * 快照成有序贡献记录: 按首次命中 tick 升序, 同 tick 按 UUID 升序 (确定序, 不依赖 HashMap 遍历序; 下游最大
     * 余数法的平手裁决另有自己的全序, 但日志与测试仍需要可复现的输出序)。
     *
     * @param onlineResolver 结算时现查在线 (玩家可能中途登出)
     */
    public List<DamageContribution> snapshot(ContributionTracker.OnlineResolver onlineResolver) {
        if (onlineResolver == null) {
            throw new IllegalArgumentException("onlineResolver must not be null");
        }
        List<Map.Entry<UUID, Entry>> sorted = new ArrayList<>(entries.entrySet());
        sorted.sort(Comparator
                .comparingLong((Map.Entry<UUID, Entry> e) -> e.getValue().firstHitTick)
                .thenComparing(Map.Entry::getKey));
        List<DamageContribution> out = new ArrayList<>(sorted.size());
        for (Map.Entry<UUID, Entry> e : sorted) {
            Entry v = e.getValue();
            out.add(new DamageContribution(e.getKey(), v.net, v.gross, v.firstHitTick, v.lastHitTick,
                    onlineResolver.isOnline(e.getKey())));
        }
        return out;
    }

    /** 序列化为条目列表 (空账本返回空列表, 调用方据此不写键)。 */
    public ListTag serializeNBT() {
        ListTag list = new ListTag();
        for (Map.Entry<UUID, Entry> e : entries.entrySet()) {
            Entry v = e.getValue();
            CompoundTag tag = new CompoundTag();
            tag.putUUID(NBT_PLAYER, e.getKey());
            tag.putDouble(NBT_NET, v.net);
            tag.putDouble(NBT_GROSS, v.gross);
            tag.putLong(NBT_FIRST, v.firstHitTick);
            tag.putLong(NBT_LAST, v.lastHitTick);
            list.add(tag);
        }
        return list;
    }

    /**
     * 从条目列表还原 (先清空)。脏条目 (缺 UUID / 净伤非正或 NaN / 负首伤 tick) 单条跳过, 不让一条坏数据毁掉整本
     * 账; 最近命中早于首次命中时抬到首次命中。读回后同样执行上限闸 (手改存档塞超额条目)。
     */
    public void deserializeNBT(ListTag list) {
        entries.clear();
        if (list == null) {
            return;
        }
        for (int i = 0; i < list.size(); i++) {
            CompoundTag tag = list.getCompound(i);
            if (!tag.hasUUID(NBT_PLAYER)) {
                continue;
            }
            double net = tag.getDouble(NBT_NET);
            long first = tag.getLong(NBT_FIRST);
            if (!(net > 0.0D) || Double.isInfinite(net) || first < 0L) {
                continue;
            }
            Entry entry = new Entry(first);
            entry.net = net;
            double gross = tag.getDouble(NBT_GROSS);
            entry.gross = (gross > 0.0D && !Double.isInfinite(gross)) ? gross : 0.0D;
            entry.lastHitTick = Math.max(first, tag.getLong(NBT_LAST));
            entries.put(tag.getUUID(NBT_PLAYER), entry);
        }
        evictOverflow();
    }
}
