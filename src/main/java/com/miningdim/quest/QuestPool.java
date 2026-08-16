package com.miningdim.quest;

import com.miningdim.core.Difficulty;
import com.miningdim.quest.objective.AmmoSpentObjective;
import com.miningdim.quest.objective.GunKillObjective;
import com.miningdim.quest.objective.KillEntityObjective;
import com.miningdim.quest.objective.MineBlockObjective;
import com.miningdim.quest.objective.MiningExtractionObjective;
import com.miningdim.quest.objective.SpecialMobKillObjective;
import com.miningdim.quest.objective.SpecialMobKind;
import com.miningdim.quest.objective.TurnInItemObjective;
import com.miningdim.quest.objective.VillagerTradeObjective;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.TagKey;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.item.Items;

import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 全服共享的任务内容池: 按来源索引的定义表 + 按 id 的反查表 + 任务线表。
 *
 * 不可变且线程安全 (构造期一次性建表, 运行期只读)。定义本身也不可变 ({@link QuestDefinition} 是 record,
 * {@link QuestObjective} 实现均为 record), 因此同一个定义实例被所有玩家共享不会串号。
 *
 * 内容<b>写在代码里而非 JSON 数据包</b>: 首批只有十几条, 走数据包要额外背一整套 codec、重载时机与校验错误
 * 处理, 复杂度与问题规模不匹配 (YAGNI)。奖励金额已经外置到配置 ({@link QuestRewards}), 而金额才是真正需要
 * 频繁调的部分; 内容本身改动频率低且改动必然伴随代码 (新目标类型)。条数长到需要非程序员编辑时再迁数据包。
 */
public final class QuestPool {

    /** TaCZ 狙击枪分类字符串 (对应 {@code GunTabType.SNIPER})。 */
    public static final String GUN_TYPE_SNIPER = "sniper";

    /** 神射手任务线 id; {@link QuestTaczHooks} 检出玩家持有狙击枪后按此 id 解锁。 */
    public static final String CHAIN_MARKSMAN = "marksman";

    private final Map<String, QuestDefinition> byId;
    private final Map<QuestSource, List<QuestDefinition>> bySource;
    private final Map<String, QuestChain> chains;

    /** {@link #minedBlockTags} 的缓存 (池不可变, 算一次即可)。 */
    private volatile Set<TagKey<Block>> minedTagsCache;

    private QuestPool(List<QuestDefinition> definitions, List<QuestChain> chainList) {
        Map<String, QuestDefinition> ids = new LinkedHashMap<>();
        Map<QuestSource, List<QuestDefinition>> sources = new EnumMap<>(QuestSource.class);
        for (QuestDefinition definition : definitions) {
            register(ids, sources, definition);
        }
        Map<String, QuestChain> chainsById = new LinkedHashMap<>();
        for (QuestChain chain : chainList) {
            if (chainsById.put(chain.id(), chain) != null) {
                throw new IllegalStateException("duplicate quest chain id: " + chain.id());
            }
            // 任务线的各阶段也要进 id 反查表: 存档只存阶段 id, 重启后要能反查回定义。
            for (QuestDefinition stage : chain.stages()) {
                register(ids, sources, stage);
            }
        }
        this.byId = Map.copyOf(ids);
        Map<QuestSource, List<QuestDefinition>> frozen = new EnumMap<>(QuestSource.class);
        sources.forEach((source, list) -> frozen.put(source, List.copyOf(list)));
        this.bySource = frozen;
        this.chains = Map.copyOf(chainsById);
    }

    private static void register(Map<String, QuestDefinition> ids,
                                 Map<QuestSource, List<QuestDefinition>> sources,
                                 QuestDefinition definition) {
        if (ids.put(definition.id(), definition) != null) {
            // 重复 id 会让存档反查取到错误的定义 (进度对不上目标), 属装配缺陷, 启动即炸而非运行期发现。
            throw new IllegalStateException("duplicate quest id: " + definition.id());
        }
        sources.computeIfAbsent(definition.source(), key -> new ArrayList<>()).add(definition);
    }

    /**
     * 本池里所有挖掘类目标关心的方块标签 (首次调用时算一遍并缓存; 池子不可变故结果恒定)。
     *
     * 给 {@link QuestPlacedBlocks} 用: 只有落在这些标签里的方块才值得记"是玩家放的", 否则玩家盖房子放的
     * 泥土石头几千格就能把矿石记录挤出有界表。
     */
    public Set<TagKey<Block>> minedBlockTags() {
        Set<TagKey<Block>> cached = minedTagsCache;
        if (cached == null) {
            Set<TagKey<Block>> tags = new LinkedHashSet<>();
            for (QuestDefinition definition : byId.values()) {
                if (definition.objective() instanceof MineBlockObjective mine) {
                    tags.add(mine.target());
                }
            }
            cached = Set.copyOf(tags);
            minedTagsCache = cached;
        }
        return cached;
    }

    /** 该方块是否被本池的某个挖掘类目标关心 (即挖掉它有可能给某条任务计数)。 */
    public boolean tracksMinedBlock(BlockState state) {
        for (TagKey<Block> tag : minedBlockTags()) {
            if (state.is(tag)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 按 id 反查定义。返回 null 表示该 id 已不在内容池中 (定义被删或改名) —— 调用方 (存档加载) 应当丢弃这条
     * 进度而不是抛异常: 内容更新后旧存档必然出现失配, 让整块存档加载失败的代价远大于丢一条任务进度。
     */
    public QuestDefinition byId(String id) {
        return byId.get(id);
    }

    public QuestChain chain(String id) {
        return chains.get(id);
    }

    public Collection<QuestChain> chains() {
        return chains.values();
    }

    /** 某来源下的全部定义 (只读)。 */
    public List<QuestDefinition> bySource(QuestSource source) {
        return bySource.getOrDefault(source, List.of());
    }

    /**
     * 从指定来源随机抽取 count 条互不重复的定义。
     *
     * 优先从"排除表之外"抽 (排除表通常是玩家当前已持有的任务, 保证重摇能摇出不一样的); 若排除后候选不足,
     * 再从全量里补齐。这条兜底是刻意的: 内容池小于槽位数时, 玩家点重摇应当得到一条任务 (哪怕重复), 而不是
     * 撞上一个服务端异常 —— 内容不足是策划问题, 不该让玩家的操作失败。
     *
     * @param excludeIds 优先排除的定义 id
     * @return 实际抽到的定义 (该来源完全没有定义时返回空表)
     */
    public List<QuestDefinition> draw(QuestSource source, int count, RandomSource random, Set<String> excludeIds) {
        if (count < 1) {
            throw new IllegalArgumentException("draw count must be >= 1, got " + count);
        }
        List<QuestDefinition> preferred = new ArrayList<>(bySource(source));
        preferred.removeIf(definition -> excludeIds.contains(definition.id()));
        List<QuestDefinition> drawn = new ArrayList<>(count);
        takeRandom(preferred, count, random, drawn);
        if (drawn.size() < count) {
            List<QuestDefinition> fallback = new ArrayList<>(bySource(source));
            fallback.removeIf(definition -> drawn.contains(definition));
            takeRandom(fallback, count - drawn.size(), random, drawn);
        }
        return drawn;
    }

    /**
     * 难档判据: 难度 &gt;= 2 算难档, 难度 1 算简单档。
     *
     * 日常按 "3 简单 + 1 难" 分层发牌 (主控 2026-08-16 定), 分层的唯一依据就是这条; 它同时也是重摇的档位依据
     * —— 重摇必须在同档内摇, 否则花信用点把难档换成简单档就是一条固定套利。
     */
    public static boolean isHardTier(QuestDefinition definition) {
        return definition.difficulty() >= HARD_TIER_MIN_DIFFICULTY;
    }

    /** 难档的最低难度值。 */
    public static final int HARD_TIER_MIN_DIFFICULTY = 2;

    /**
     * 在指定难度档内抽取 count 条互不重复的定义。
     *
     * 该档完全没有内容时回落到全池 ({@link #draw}): 宁可发一条不合档的任务, 也不给玩家一个空槽 —— 内容分布
     * 不均是策划问题, 不该表现成界面上少一格。
     */
    public List<QuestDefinition> drawTier(QuestSource source, boolean hardTier, int count, RandomSource random,
                                          Set<String> excludeIds) {
        if (count < 1) {
            throw new IllegalArgumentException("draw count must be >= 1, got " + count);
        }
        List<QuestDefinition> tier = new ArrayList<>();
        for (QuestDefinition definition : bySource(source)) {
            if (isHardTier(definition) == hardTier) {
                tier.add(definition);
            }
        }
        if (tier.isEmpty()) {
            return draw(source, count, random, excludeIds);
        }
        List<QuestDefinition> preferred = new ArrayList<>(tier);
        preferred.removeIf(definition -> excludeIds.contains(definition.id()));
        List<QuestDefinition> drawn = new ArrayList<>(count);
        takeRandom(preferred, count, random, drawn);
        if (drawn.size() < count) {
            List<QuestDefinition> fallback = new ArrayList<>(tier);
            fallback.removeIf(drawn::contains);
            takeRandom(fallback, count - drawn.size(), random, drawn);
        }
        return drawn;
    }

    private static void takeRandom(List<QuestDefinition> candidates, int count, RandomSource random,
                                   List<QuestDefinition> sink) {
        for (int i = 0; i < count && !candidates.isEmpty(); i++) {
            sink.add(candidates.remove(random.nextInt(candidates.size())));
        }
    }

    /**
     * 内置内容池。
     *
     * 每日 12 条 / 每周 5 条 / 特殊 3 条 —— 每日条数远多于 4 个槽位是刻意的: 槽位数与池子等大时"重摇"退化成
     * 洗牌, 玩家每天看到的是同一组任务。
     *
     * <b>日常与周常一律不用"交易"作判据 (主控 2026-08-16 定)。</b> 任务的定位是系统给玩家的保底收入, 保底
     * 成立的前提是完成量受正常游玩速度约束; 而村民交易在原版里是可无限 farm 的 (绿宝石农场挂上, 交易次数
     * 要多少有多少), 拿它当周期任务判据等于把一个无上限动作换成有保障的信用点产出。玩家间市场成交同理 ——
     * 那是转移不是产出, 两个号对敲即可刷任务 (见 docs 与既往审计记录的跨账号洗额度问题), 故市场动作一条都
     * 没有接进任务事实层。
     *
     * 交易类判据只留在<b>特殊任务</b>里: 它由随机事件抛出, 受概率闸 + 冷却 + 在途上限三重约束, 不是可重复
     * 刷的产出口。
     */
    public static QuestPool builtin() {
        List<QuestDefinition> definitions = new ArrayList<>();

        // ---- 日常 · 简单档 (难度 1): 正常游玩顺手完成, 不改变玩家当天的行程 ----
        definitions.add(new QuestDefinition("daily.kill.zombie", QuestSource.DAILY, "清理亡灵",
                new KillEntityObjective(EntityType.ZOMBIE, 15), 1));
        definitions.add(new QuestDefinition("daily.kill.skeleton", QuestSource.DAILY, "白骨消殒",
                new KillEntityObjective(EntityType.SKELETON, 12), 1));
        definitions.add(new QuestDefinition("daily.kill.spider", QuestSource.DAILY, "驱虫作业",
                new KillEntityObjective(EntityType.SPIDER, 12), 1));
        definitions.add(new QuestDefinition("daily.mine.coal", QuestSource.DAILY, "燃料采集",
                new MineBlockObjective(BlockTags.COAL_ORES, "煤矿石", 32), 1));
        definitions.add(new QuestDefinition("daily.mine.copper", QuestSource.DAILY, "线材原料",
                new MineBlockObjective(BlockTags.COPPER_ORES, "铜矿石", 24), 1));
        definitions.add(new QuestDefinition("daily.mine.iron", QuestSource.DAILY, "钢铁配额",
                new MineBlockObjective(BlockTags.IRON_ORES, "铁矿石", 24), 1));
        definitions.add(new QuestDefinition("daily.extract.any", QuestSource.DAILY, "例行下矿",
                MiningExtractionObjective.any(1), 1));
        definitions.add(new QuestDefinition("daily.turnin.rotten", QuestSource.DAILY, "腐肉回收",
                new TurnInItemObjective(Items.ROTTEN_FLESH, 64), 1));
        definitions.add(new QuestDefinition("daily.turnin.bone", QuestSource.DAILY, "骨料回收",
                new TurnInItemObjective(Items.BONE, 32), 1));
        definitions.add(new QuestDefinition("daily.ammo.any", QuestSource.DAILY, "打靶训练",
                AmmoSpentObjective.anyGun(60), 1));

        // ---- 日常 · 难档 (难度 2-3): 每天只发 1 条, 需要玩家专门跑一趟 ----
        definitions.add(new QuestDefinition("daily.kill.creeper", QuestSource.DAILY, "拆弹专家",
                new KillEntityObjective(EntityType.CREEPER, 8), 2));
        definitions.add(new QuestDefinition("daily.kill.pillager", QuestSource.DAILY, "边境肃清",
                new KillEntityObjective(EntityType.PILLAGER, 10), 2));
        definitions.add(new QuestDefinition("daily.kill.drowned", QuestSource.DAILY, "水下清淤",
                new KillEntityObjective(EntityType.DROWNED, 10), 2));
        definitions.add(new QuestDefinition("daily.mine.redstone", QuestSource.DAILY, "红石订单",
                new MineBlockObjective(BlockTags.REDSTONE_ORES, "红石矿石", 16), 2));
        definitions.add(new QuestDefinition("daily.mine.lapis", QuestSource.DAILY, "青金配额",
                new MineBlockObjective(BlockTags.LAPIS_ORES, "青金石矿石", 12), 2));
        definitions.add(new QuestDefinition("daily.mine.gold", QuestSource.DAILY, "贵金属采集",
                new MineBlockObjective(BlockTags.GOLD_ORES, "金矿石", 12), 2));
        definitions.add(new QuestDefinition("daily.turnin.gunpowder", QuestSource.DAILY, "火药征集",
                new TurnInItemObjective(Items.GUNPOWDER, 48), 2));
        definitions.add(new QuestDefinition("daily.ammo.sniper", QuestSource.DAILY, "精确射击训练",
                new AmmoSpentObjective(GUN_TYPE_SNIPER, 40), 2));
        definitions.add(new QuestDefinition("daily.extract.medium", QuestSource.DAILY, "中级勘探",
                MiningExtractionObjective.of(Difficulty.MEDIUM, 2), 2));
        definitions.add(new QuestDefinition("daily.extract.hard", QuestSource.DAILY, "深层勘探",
                MiningExtractionObjective.of(Difficulty.HARD, 1), 3));

        // ---- 周常: 目标是 1-3 天完成, 因此全部落在难档; 多数靠硬指标, 稀有怪只占一小部分 ----
        definitions.add(new QuestDefinition("weekly.mine.diamond", QuestSource.WEEKLY, "钻石周供",
                new MineBlockObjective(BlockTags.DIAMOND_ORES, "钻石矿石", 16), 3));
        definitions.add(new QuestDefinition("weekly.mine.emerald", QuestSource.WEEKLY, "绿宝石周供",
                new MineBlockObjective(BlockTags.EMERALD_ORES, "绿宝石矿石", 8), 3));
        definitions.add(new QuestDefinition("weekly.mine.iron", QuestSource.WEEKLY, "钢铁周供",
                new MineBlockObjective(BlockTags.IRON_ORES, "铁矿石", 180), 2));
        definitions.add(new QuestDefinition("weekly.kill.zombie", QuestSource.WEEKLY, "尸潮周清",
                new KillEntityObjective(EntityType.ZOMBIE, 150), 2));
        definitions.add(new QuestDefinition("weekly.kill.pillager", QuestSource.WEEKLY, "劫掠者周清",
                new KillEntityObjective(EntityType.PILLAGER, 60), 3));
        definitions.add(new QuestDefinition("weekly.ammo.any", QuestSource.WEEKLY, "弹药消耗周报",
                AmmoSpentObjective.anyGun(800), 2));
        definitions.add(new QuestDefinition("weekly.extract.hard", QuestSource.WEEKLY, "深层周巡",
                MiningExtractionObjective.of(Difficulty.HARD, 5), 3));
        definitions.add(new QuestDefinition("weekly.turnin.totem", QuestSource.WEEKLY, "图腾征集",
                new TurnInItemObjective(Items.TOTEM_OF_UNDYING, 8), 3));
        // 下界之星 x3 = 打三只凋灵; 与击杀末影龙同属本池最高档, 刻意各留一条给硬核玩家。
        definitions.add(new QuestDefinition("weekly.turnin.netherstar", QuestSource.WEEKLY, "星辰征集",
                new TurnInItemObjective(Items.NETHER_STAR, 3), 3));
        definitions.add(new QuestDefinition("weekly.kill.enderdragon", QuestSource.WEEKLY, "屠龙",
                new KillEntityObjective(EntityType.ENDER_DRAGON, 1), 3));
        // 稀有变种怪只占周常池的两条, 且优先给"任意变种"这条 —— 点名单一变种时完成与否很大程度看刷怪运气,
        // 那种手感是"抽不到"而不是"难"。点名小鸡骑士的那条作彩蛋保留, 抽到打不出来还可以花信用点重摇。
        definitions.add(new QuestDefinition("weekly.kill.specialmob", QuestSource.WEEKLY, "异种讨伐",
                SpecialMobKillObjective.any(3), 3));
        definitions.add(new QuestDefinition("weekly.kill.chickenjockey", QuestSource.WEEKLY, "鸡飞狗跳",
                new SpecialMobKillObjective(SpecialMobKind.CHICKEN_JOCKEY, 1), 3));

        definitions.add(new QuestDefinition("special.village.trade", QuestSource.SPECIAL, "路过的商机",
                new VillagerTradeObjective(1), 1));
        definitions.add(new QuestDefinition("special.village.trade.bulk", QuestSource.SPECIAL, "村口的大单",
                new VillagerTradeObjective(5), 2));
        definitions.add(new QuestDefinition("special.village.guard", QuestSource.SPECIAL, "村庄守卫",
                new KillEntityObjective(EntityType.PILLAGER, 5), 2));

        QuestChain marksman = new QuestChain(CHAIN_MARKSMAN, "神射手", List.of(
                new QuestDefinition("marksman.1", QuestSource.HIDDEN, "神射手 I - 校枪",
                        GunKillObjective.ofType(GUN_TYPE_SNIPER, 5), 1),
                new QuestDefinition("marksman.2", QuestSource.HIDDEN, "神射手 II - 定点",
                        GunKillObjective.headshot(GUN_TYPE_SNIPER, 3), 2),
                new QuestDefinition("marksman.3", QuestSource.HIDDEN, "神射手 III - 中距离",
                        GunKillObjective.longRangeHeadshot(GUN_TYPE_SNIPER, 50, 2), 3),
                new QuestDefinition("marksman.4", QuestSource.HIDDEN, "神射手 IV - 远距离",
                        GunKillObjective.longRangeHeadshot(GUN_TYPE_SNIPER, 80, 1), 3)));

        return new QuestPool(definitions, List.of(marksman));
    }
}
