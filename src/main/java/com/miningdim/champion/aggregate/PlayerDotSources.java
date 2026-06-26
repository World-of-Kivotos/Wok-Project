package com.miningdim.champion.aggregate;

import com.miningdim.champion.AffixDef;
import com.miningdim.champion.AffixQuality;
import com.miningdim.champion.ChampionAttackValues;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 单玩家在册 DoT 源的【层数 + 3s 刷新窗】权威模型 (ChampionStarAffix spec 7.2 燃烧/寒霜: 命中后 3s 刷新窗内
 * 每秒持续扣血, 不要求每秒都有新命中)。
 *
 * 为何独立于 {@link PlayerDotAccumulator}: 累加器只缓冲"本秒已记入的名义伤害"并每秒 flush, 它不知道"哪些 DoT
 * 源仍在 3s 窗内、当前几层"。原实现把这段层数+窗状态藏在受击 handler 的 per-pair 私有字段里, tick handler 看不到,
 * 导致玩家风筝/冠军够不到的那秒无新命中 -> 无人 record -> 累加器为空 -> 不扣血 (DoT 退化成"仅命中秒扣血")。本类
 * 把该状态提升为 per-player 可被 tick handler 每秒读取的权威: 命中只负责【刷层 + 续窗】(refresh), tick handler 每秒
 * 遍历仍在窗内的每个源 (activeSources) 按当前层数重算本秒名义伤害再 record 进累加器 flush。窗口过期 (>3s 无续期)
 * 由 {@link #pruneExpired} 自然清源停伤。
 *
 * 键 = (冠军 UUID 串, 词条): 区分同一玩家身上多冠军/多词条的独立层数与刷新窗。同源刷新累加叠层 (上限 5), 窗外
 * 衰减清零再叠 (spec 7.2: 最大 5 层 3s 刷新)。寒霜源的层数另供 handler 每秒按层施减速 (走控制聚合 ≤50%)。
 *
 * 线程纪律: 服务端主线程 tick 串行写, 非线程安全 (与其它聚合器一致)。不碰世界/实体 (只持源标识 + 枚举 + int +
 * long), GameTest 直接断言。
 */
public final class PlayerDotSources {

    /** DoT 刷新窗时长 (tick): 命中后 3s = 60tick 内持续扣血, 窗内续命中续期 (spec 7.2: 3s 刷新窗)。 */
    public static final long DOT_WINDOW_TICKS = 60L;

    /** 在册 DoT 源 (源标识 -> 层数+窗+品质条目); LinkedHashMap 保插入序 = tick 遍历逐源保序。 */
    private final Map<DotKey, Entry> sources = new LinkedHashMap<>();

    /**
     * 命中刷层 + 续窗: 同一 (冠军, 词条) 源若仍在窗内则叠层 (上限 {@link ChampionAttackValues#DOT_MAX_STACKS}),
     * 窗外 (上次刷新 +3s 已过) 先衰减清零再从 1 层叠起; 无论叠层与否都把窗末推进到 nowTick+3s (续期)。品质以本次
     * 命中为准 (同源换品质以最新命中的品质重算名义伤害)。
     *
     * @param championId 施加 DoT 的冠军 UUID (源去重键)
     * @param def        DoT 词条 (BURNING / FROST)
     * @param quality    本次命中该词条品质 (tick 时按此重算名义伤害)
     * @param nowTick    当前 gameTime tick
     */
    public void refresh(UUID championId, AffixDef def, AffixQuality quality, long nowTick) {
        if (championId == null) {
            throw new IllegalArgumentException("championId must not be null");
        }
        if (def == null) {
            throw new IllegalArgumentException("def must not be null");
        }
        if (quality == null) {
            throw new IllegalArgumentException("quality must not be null");
        }
        DotKey key = new DotKey(championId, def);
        Entry entry = sources.get(key);
        if (entry == null) {
            entry = new Entry();
            sources.put(key, entry);
        }
        entry.refresh(quality, nowTick);
    }

    /**
     * 清掉所有刷新窗已过期 (windowEndTick &lt; nowTick) 的源: tick handler 每秒 flush 前调用, 使过期源不再贡献
     * 本秒名义伤害 (窗口过期自然停伤)。窗末 == nowTick 视为仍在窗内 (该秒仍持续扣血, 下一秒才过期)。
     *
     * @param nowTick 当前 gameTime tick
     */
    public void pruneExpired(long nowTick) {
        sources.entrySet().removeIf(e -> e.getValue().windowEndTick < nowTick);
    }

    /**
     * 当前仍在刷新窗内的活跃 DoT 源快照 (词条 + 品质 + 当前层数), 按记入序。tick handler 据此对每个源按当前
     * 层数调 {@link ChampionAttackValues#burningTickHp}/{@link ChampionAttackValues#frostFreezeTickHp} 重算本秒
     * 名义伤害补记进累加器, 并对寒霜源按层施减速。先调 {@link #pruneExpired} 再取此快照, 保只含未过期源。
     *
     * @return 活跃源列表 (空 = 无在册 DoT)
     */
    public List<ActiveSource> activeSources() {
        List<ActiveSource> out = new ArrayList<>(sources.size());
        for (Map.Entry<DotKey, Entry> e : sources.entrySet()) {
            Entry entry = e.getValue();
            out.add(new ActiveSource(e.getKey().championId, e.getKey().def, entry.quality, entry.stacks));
        }
        return out;
    }

    /** 当前在册源数 (诊断/测试用; 未 prune 的过期源也计入, 故测试应先 prune)。 */
    public int sourceCount() {
        return sources.size();
    }

    /** 是否无任何在册源 (供注册表判断该玩家 DoT 是否已全空, 决定清理)。 */
    public boolean isEmpty() {
        return sources.isEmpty();
    }

    /** 某 (冠军, 词条) 源当前层数 (诊断/测试用; 不在册返 0)。 */
    public int stacksOf(UUID championId, AffixDef def) {
        Entry entry = sources.get(new DotKey(championId, def));
        return entry == null ? 0 : entry.stacks;
    }

    /** 活跃源快照条目 (不可变): 哪个冠军的哪个词条、什么品质、当前几层。 */
    public static final class ActiveSource {

        private final UUID championId;
        private final AffixDef def;
        private final AffixQuality quality;
        private final int stacks;

        ActiveSource(UUID championId, AffixDef def, AffixQuality quality, int stacks) {
            this.championId = championId;
            this.def = def;
            this.quality = quality;
            this.stacks = stacks;
        }

        /** 施加 DoT 的冠军 UUID。 */
        public UUID championId() {
            return championId;
        }

        /** DoT 词条 (BURNING / FROST)。 */
        public AffixDef def() {
            return def;
        }

        /** 该源品质 (重算名义伤害用)。 */
        public AffixQuality quality() {
            return quality;
        }

        /** 当前层数 (1-{@link ChampionAttackValues#DOT_MAX_STACKS})。 */
        public int stacks() {
            return stacks;
        }
    }

    /** per-(冠军, 词条) 层数 + 刷新窗末 + 品质条目。 */
    private static final class Entry {

        private int stacks = 0;
        private long windowEndTick = Long.MIN_VALUE;
        private AffixQuality quality;

        void refresh(AffixQuality newQuality, long nowTick) {
            if (windowEndTick != Long.MIN_VALUE && nowTick > windowEndTick) {
                stacks = 0; // 刷新窗外: 层数衰减清零, 重新叠起。
            }
            if (stacks < ChampionAttackValues.DOT_MAX_STACKS) {
                stacks++;
            }
            quality = newQuality;
            windowEndTick = nowTick + DOT_WINDOW_TICKS;
        }
    }

    /** 源去重键 (冠军 UUID + 词条)。equals/hashCode 据二者, 供 LinkedHashMap 去重。 */
    private static final class DotKey {

        private final UUID championId;
        private final AffixDef def;

        DotKey(UUID championId, AffixDef def) {
            this.championId = championId;
            this.def = def;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (!(o instanceof DotKey other)) {
                return false;
            }
            return championId.equals(other.championId) && def == other.def;
        }

        @Override
        public int hashCode() {
            return championId.hashCode() * 31 + def.hashCode();
        }
    }
}
