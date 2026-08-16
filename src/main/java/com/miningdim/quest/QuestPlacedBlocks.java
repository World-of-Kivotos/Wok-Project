package com.miningdim.quest;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 记住玩家刚放下的、任务会数的方块, 好在它被挖掉时不计数。
 *
 * <b>堵的是什么</b>: {@link com.miningdim.quest.objective.MineBlockObjective} 数的是破坏事件而不是材料, 于是
 * 精准采集挖一块矿 -> 计数 +1 -> 放回去 -> 再挖 -> 再 +1 可以一直循环。矿工那条经济 faucet 不吃这个问题
 * (精准采集拿回的是矿石方块本身, 材料没有增殖, 卖矿收入不变; 矿工经验又只在矿洞 region 内给, 而矿洞维度被
 * {@code RulesSystem} 的白名单禁止放置矿石), <b>它是任务系统独有的洞</b>。
 *
 * <b>量级</b>: 任务一天只能领一次奖, 所以这不是印钞, 是把当天的日常从几分钟压缩到几十秒 —— 是白嫖手感而非
 * 经济漏洞。修法按这个量级来: 一个有界的近期放置表, 不引入持久层, 不改动其它子系统。
 *
 * <b>只跟踪任务真正会数的方块</b> (见 {@link QuestPool#tracksMinedBlock}): 玩家盖房子放的泥土石头若也进表,
 * 几千格就能把矿石记录挤出去, 表反而成了摆设。只收矿石类, 表就小得可以常驻内存。
 *
 * 有界 LRU 而非无界集合: 无界表等于给了玩家一条用放置行为撑爆服务端内存的路。撑满之后最旧的记录被挤掉,
 * 那块方块再被挖就会重新计数 —— 但撑满本身要放满 {@value #MAX_TRACKED} 个矿石方块, 比老老实实去挖还费劲,
 * 攻击面被压到不划算。
 *
 * 线程: 只在服务端主线程读写 (方块放置与破坏事件均在主线程), 故用普通 LinkedHashMap 不加锁。
 */
public final class QuestPlacedBlocks {

    /** 近期放置表的容量上限。 */
    private static final int MAX_TRACKED = 4096;

    /** 维度 + 打包坐标。跨维度同坐标必须区分开, 否则在主世界放一块就能让矿洞同坐标的那块不计数。 */
    private record Key(ResourceKey<Level> dimension, long packedPos) {
    }

    private static final Map<Key, Boolean> RECENT = new LinkedHashMap<>(512, 0.75F, false) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<Key, Boolean> eldest) {
            return size() > MAX_TRACKED;
        }
    };

    private QuestPlacedBlocks() {
    }

    /** 记下一次玩家放置。 */
    public static void markPlaced(Level level, BlockPos pos) {
        RECENT.put(keyOf(level, pos), Boolean.TRUE);
    }

    /**
     * 该坐标是不是玩家刚放下的; 是则<b>取走</b>这条记录并返回 true。
     *
     * 取走而不是保留: 一块被放下又挖掉的方块, 这次不计数就够了; 留着只会让同一坐标上后来自然生成或别人放的
     * 方块也被无故跳过。
     */
    public static boolean consumeIfPlaced(Level level, BlockPos pos) {
        return RECENT.remove(keyOf(level, pos)) != null;
    }

    /** 当前记录条数 (诊断与测试用)。 */
    public static int trackedCount() {
        return RECENT.size();
    }

    /** 停服清空 (进程内瞬时状态, 不跨存档)。 */
    public static void reset() {
        RECENT.clear();
    }

    private static Key keyOf(Level level, BlockPos pos) {
        return new Key(level.dimension(), pos.asLong());
    }
}
