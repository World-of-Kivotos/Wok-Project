package com.miningdim.champion;

import net.minecraft.nbt.CompoundTag;

import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;

/**
 * 精英怪【自研冠军数据】(Champions mod 依赖的替代品; ChampionStarAffix spec 第六/七章)。挂在 Mob 实体上的
 * Forge capability 数据: 该怪是否冠军 (星级 ≥1) + 装配的词条→品质映射 + 总有效血 (贡献池分母)。
 *
 * 取代 Champions 的 IChampion capability + 我方 DATA_KEY NBT: 此前"给实体标记冠军 + 存星级/词条"靠 Champions
 * 的 capability 与其 rank 系统, 效果 handler 读 IChampion.getServer().getAffixes()/getData。自研后本类是【唯一权威】——
 * spawn 期 promoter 经 {@link #promote} 写入, 全部效果 handler 经 {@link MiningChampions#get} 读 star/affixes,
 * 不再触任何 top.theillusivec4.champions.* (故 integration 层从"只能真服验"变为 dev GameTest 可验)。
 *
 * 纯数据 + NBT 序列化, 无世界/实体引用 (与 {@code MiningPlayerData} 同范式), GameTest 直接断言。词条以 def→品质
 * 直存 (非 Champions 的 registryName 字符串), 星级即 {@link StarRank} 星值; 数值语义解释仍下沉 {@link AffixDef}/
 * {@link ChampionAffixValues} 等纯逻辑。非冠军 (star=0) 不写 NBT (防每只普通怪 NBT 膨胀)。
 *
 * currentHp 随 NBT 持久是为了让 6★+ 影子血池在服务端重启/区块重载后可被原样重建 (F040): 否则
 * {@code BloodPoolRegistry.get} 返 null 会让战斗权威静默切回 vanilla 血, 违反 spec 6.2 #2。
 *
 * sealedAffixes (被临时封印而摘下的词条→原品质) 随 NBT 持久是为了让封印真正"临时" (SpecialAgent spec 六/九章
 * "绝不永久削废"): 封印方把词条从词条表摘掉的同一刻把原品质登记在这里, 登记跟着实体一起存盘、跨维度、随区块卸载
 * 重载。此前这份恢复源只在封印方的进程内存里, 封印窗口内服务端重启、或封完让区块卸载超过恢复宽限期, 被封词条就
 * 永久蒸发 —— 精英仍按初始星级发全额奖励池, 却被永久削弱。本类只负责"存得住、取一次", 何时恢复由封印方决定
 * (本类不知道封印窗口多长, 也不反向依赖任何职业模块)。
 */
public final class MiningChampionData {

    /** 星级 0 = 非冠军 (默认态; 每只 Mob 挂本 capability 但仅 promoter 盖章的才 star≥1)。 */
    public static final int NOT_CHAMPION = 0;

    private static final String NBT_STAR = "star";
    private static final String NBT_EFFECTIVE_HP = "effective_hp";
    private static final String NBT_AFFIXES = "affixes";
    private static final String NBT_SUMMONED = "summoned_by_affix";
    private static final String NBT_CURRENT_HP = "current_hp";
    private static final String NBT_WORLD_BOSS = "world_boss";
    private static final String NBT_SEALED_AFFIXES = "sealed_affixes";

    private int star = NOT_CHAMPION;
    private final EnumMap<AffixDef, AffixQuality> affixes = new EnumMap<>(AffixDef.class);
    private double effectiveHp = 0.0D;
    private boolean summonedByAffix = false;
    private double currentHp = 0.0D;
    private boolean worldBoss = false;
    /** 被临时封印而从 {@link #affixes} 摘下的词条→摘下时的品质 (封印恢复源, 随 NBT 持久; 见类注释)。 */
    private final EnumMap<AffixDef, AffixQuality> sealedAffixes = new EnumMap<>(AffixDef.class);

    /** 是否已被盖章为冠军 (star ∈ [1,10])。非冠军的默认 capability 恒 false。 */
    public boolean isChampion() {
        return star >= StarRank.MIN_STAR && star <= StarRank.MAX_STAR;
    }

    /** 星级 (1-10; 0 = 非冠军)。 */
    public int star() {
        return star;
    }

    /** 总有效血 (贡献池盖章门槛分母; 6★+ = 血池 maxHp, 1-5★ = 星表基础有效血, 巨大化后为实际有效血)。 */
    public double effectiveHp() {
        return effectiveHp;
    }

    /** 当前血量 (F040: 随 NBT 持久, 供 6★+ 影子血池在实体重新入世时按此重建, 而非恒回满血)。 */
    public double currentHp() {
        return currentHp;
    }

    /** 写入当前血量 (受击/回血落账点调用; 纯赋值, 越界与否由血池层的构造校验负责, 本层不做 clamp 掩盖)。 */
    public void setCurrentHp(double hp) {
        this.currentHp = hp;
    }

    /** 装配词条→品质 (不可变视图; 遍历顺序 = AffixDef 声明序)。 */
    public Map<AffixDef, AffixQuality> affixes() {
        return Collections.unmodifiableMap(affixes);
    }

    /** 某词条的品质 (未装配返 null)。 */
    public AffixQuality quality(AffixDef def) {
        return affixes.get(def);
    }

    /** 是否装配某词条。 */
    public boolean has(AffixDef def) {
        return affixes.containsKey(def);
    }

    /**
     * 是否支援召唤词条召出的召唤物 (spec 7.4 支援 [红队] 经济闸: summonedByAffix=true 不参与货币/经验/掉落/
     * 贡献结算, BOSS 血条亦不出条)。随 NBT 持久 —— 区块卸载重载后排除口径不丢。
     */
    public boolean isSummonedByAffix() {
        return summonedByAffix;
    }

    /** 盖章为词条召唤物 (支援召唤 handler 在 promote 后调用; clear/重新 promote 会复位)。 */
    public void markSummonedByAffix() {
        this.summonedByAffix = true;
    }

    /**
     * 是否管理员召唤的世界 BOSS (ChampionStarAffix spec 第十章; 对外经 {@link WorldBoss#isWorldBoss} 读)。随 NBT
     * 持久 —— 区块卸载重载、服务端重启后身份不丢, 击倒公告与成就的击杀过滤都认这个标记。
     */
    public boolean isWorldBoss() {
        return worldBoss;
    }

    /**
     * 盖章为世界 BOSS ({@link WorldBoss#spawn} 在 promote 之后调用; clear/重新 promote 会复位)。只有已盖章的冠军
     * 才能标记: 非冠军不写 NBT, 标了也存不下来。
     */
    public void markWorldBoss() {
        if (!isChampion()) {
            throw new IllegalStateException("only a promoted champion can be marked as a world boss");
        }
        this.worldBoss = true;
    }

    /**
     * spawn 期盖章 (promoter 调用): 设星级 + 词条→品质 + 有效血。覆盖旧态 (重生/重复盖章防残留)。
     *
     * @param star           星级 (须 ∈ [1,10])
     * @param newAffixes     词条→品质映射 (拷入, 不持外部引用)
     * @param effectiveHp    总有效血 (须 &gt;0)
     */
    public void promote(int star, Map<AffixDef, AffixQuality> newAffixes, double effectiveHp) {
        if (star < StarRank.MIN_STAR || star > StarRank.MAX_STAR) {
            throw new IllegalArgumentException("champion star out of [1,10]: " + star);
        }
        if (newAffixes == null) {
            throw new IllegalArgumentException("affixes must not be null");
        }
        if (!(effectiveHp > 0.0D) || Double.isNaN(effectiveHp)) {
            throw new IllegalArgumentException("effectiveHp must be > 0, got " + effectiveHp);
        }
        this.star = star;
        this.affixes.clear();
        this.affixes.putAll(newAffixes);
        this.effectiveHp = effectiveHp;
        this.summonedByAffix = false; // 重新盖章即普通冠军; 召唤物身份由 markSummonedByAffix 在 promote 后补盖。
        this.worldBoss = false; // 世界 BOSS 身份同理, 由 markWorldBoss 在 promote 后补盖。
        this.currentHp = effectiveHp; // 新盖章的冠军恒为满血 (spawn 期/命令召唤同一入口, 无旧血量可延续)。
        // 重新盖章是一只新冠军: 旧词条表的封印登记若留着, 到期会被合并进这张新词条表, 凭空多出词条。
        this.sealedAffixes.clear();
    }

    /**
     * 只换掉词条→品质映射, 星级、有效血、当前血量、召唤物标记、世界 BOSS 标记与被封词条登记一律不动 (特勤封印到期恢复
     * 用: 把被封的词条合并回当前词条表)。与 {@link #promote} 的区别正在这里 —— promote 是重新盖章, 会复位两个身份标记并
     * 回满当前血量, 拿它做恢复会把被封印过的世界 BOSS 变回普通冠军。
     *
     * @param newAffixes 词条→品质映射 (拷入, 不持外部引用)
     * @throws IllegalStateException 尚未盖章 (非冠军没有可替换的词条表, 也不写 NBT)
     */
    public void replaceAffixes(Map<AffixDef, AffixQuality> newAffixes) {
        if (newAffixes == null) {
            throw new IllegalArgumentException("affixes must not be null");
        }
        if (!isChampion()) {
            throw new IllegalStateException("only a promoted champion has affixes to replace");
        }
        EnumMap<AffixDef, AffixQuality> copy = new EnumMap<>(AffixDef.class);
        copy.putAll(newAffixes);
        this.affixes.clear();
        this.affixes.putAll(copy);
    }

    /** 清为非冠军态 (deserialize 前重置 / 显式清除)。 */
    public void clear() {
        this.star = NOT_CHAMPION;
        this.affixes.clear();
        this.effectiveHp = 0.0D;
        this.summonedByAffix = false;
        this.currentHp = 0.0D;
        this.worldBoss = false;
        this.sealedAffixes.clear();
    }

    /**
     * 摘除某词条 (一次性技能语义: 小男孩 LITTLE_BOY 起手即摘防重触发, spec 7.4 波2)。仅从词条→品质映射中移除该条,
     * 星级/有效血/召唤物标记/其它词条一律不动; 未装配则 no-op。返回是否确有移除 (供调用方幂等自查/日志)。
     *
     * NBT 同步: 本类无实体引用不能主动落盘, 但 {@link #serializeNBT} 遍历 {@link #affixes} 生成词条子 tag, 移除后
     * 下次存盘 (区块保存/卸载, 经 {@code MiningChampionProvider}) 自然不再写本条, {@link #deserializeNBT} 读回亦不含
     * —— 摘除即随往返落地, 不残留 (GameTest 以 serialize->deserialize 往返断言此不变量)。
     *
     * @param def 待摘词条
     * @return 移除前是否装配该词条 (true = 确有移除)
     */
    public boolean removeAffix(AffixDef def) {
        return affixes.remove(def) != null;
    }

    /**
     * 登记一条因临时封印而从词条表摘下的词条及其摘下时的品质 (封印方写穿本表, 恢复源即本表, 见类注释)。合并语义:
     * 已登记的其它词条保留 (8★+ 两个封印槽先后封两条), 同一词条重复登记以最新品质为准。本方法不动词条表本身 —— 摘
     * 词条仍由调用方经 {@link #removeAffix} 完成; 两步都在服务端主线程同一 tick 内, 存盘不会落在两步之间。
     *
     * @param def     被封词条
     * @param quality 该词条被摘下时的品质 (恢复时按此原样放回)
     * @throws IllegalStateException 尚未盖章 (非冠军不写 NBT, 登记了也存不下来)
     */
    public void recordSealedAffix(AffixDef def, AffixQuality quality) {
        if (def == null || quality == null) {
            throw new IllegalArgumentException("sealed affix and its quality must not be null");
        }
        if (!isChampion()) {
            throw new IllegalStateException("only a promoted champion can have sealed affixes");
        }
        sealedAffixes.put(def, quality);
    }

    /** 当前被封印中、待恢复的词条→原品质 (不可变视图; 遍历顺序 = AffixDef 声明序; 无登记返空视图)。 */
    public Map<AffixDef, AffixQuality> sealedAffixes() {
        return Collections.unmodifiableMap(sealedAffixes);
    }

    /** 是否有被封印中、待恢复的词条 (入世/tick 对账据此快速跳过绝大多数实体)。 */
    public boolean hasSealedAffixes() {
        return !sealedAffixes.isEmpty();
    }

    /**
     * 取走一条待恢复的被封词条并把它从登记里删掉 (恢复方据此把它增量合并回词条表)。按条取而不是整份取: 各条封印的
     * 窗口各自到期 (8★+ 两个封印槽先后封两条, 机制类窗口又远短于被动类), 到期的那条要先放回, 仍在窗口内的得继续留在
     * 登记里。取走即清, 同一条登记只能被恢复一次 —— 到期 tick、入世对账等多条恢复路径谁先到谁恢复, 后到者拿到 null
     * 空转, 不会重复合并。
     *
     * @param def 待恢复的词条
     * @return 该词条被摘下时的品质; 未登记 (没被封印 / 已由另一条路径恢复过) 返 null
     */
    public AffixQuality takeSealedAffix(AffixDef def) {
        return sealedAffixes.remove(def);
    }

    /**
     * 序列化 NBT (随实体存盘, 冠军跨存档/区块卸载重载保留)。非冠军 (star=0) 返回空 tag —— 每只普通 Mob 都挂本
     * capability, 空写防全世界怪 NBT 膨胀。
     */
    public CompoundTag serializeNBT() {
        CompoundTag tag = new CompoundTag();
        if (!isChampion()) {
            return tag;
        }
        tag.putInt(NBT_STAR, star);
        tag.putDouble(NBT_EFFECTIVE_HP, effectiveHp);
        tag.putDouble(NBT_CURRENT_HP, currentHp);
        if (summonedByAffix) {
            tag.putBoolean(NBT_SUMMONED, true); // 仅召唤物写键 (普通冠军不膨胀 NBT)。
        }
        if (worldBoss) {
            tag.putBoolean(NBT_WORLD_BOSS, true); // 仅世界 BOSS 写键, 同上。
        }
        tag.put(NBT_AFFIXES, writeAffixMap(affixes));
        if (!sealedAffixes.isEmpty()) {
            // 仅封印窗口内的冠军写键, 同上; 与词条表同格式 (词条名 -> 品质 ordinal)。
            tag.put(NBT_SEALED_AFFIXES, writeAffixMap(sealedAffixes));
        }
        return tag;
    }

    /**
     * 反序列化 NBT (存盘读回)。脏/缺失 star 视为非冠军; 未知词条名 (版本漂移删词条) / 越界品质 ordinal 静默跳过
     * 该条 (不抛, 不让单条脏词条毁掉整只冠军还原)。被封词条登记同一容忍口径; 旧存档 (本键上线前) 缺键即无登记。
     */
    public void deserializeNBT(CompoundTag tag) {
        clear();
        if (tag == null || !tag.contains(NBT_STAR)) {
            return;
        }
        int s = tag.getInt(NBT_STAR);
        if (s < StarRank.MIN_STAR || s > StarRank.MAX_STAR) {
            return; // 脏星级: 当非冠军。
        }
        this.star = s;
        this.effectiveHp = tag.getDouble(NBT_EFFECTIVE_HP);
        // 向后兼容: 旧存档 (本键上线前已盖章的冠军) 没有 NBT_CURRENT_HP, 缺键按满血续战而非按 0 血 (0 血会让
        // 重建的血池 install 时以死态出现)。这是本类唯一一处"缺键不视为脏数据"的容忍 —— 与其它字段 (星级/词条)
        // 的"脏则整体回退非冠军/单条跳过"不同, 因为旧存档的冠军本身是合法态, 只是缺一个新引入的字段。
        this.currentHp = tag.contains(NBT_CURRENT_HP) ? tag.getDouble(NBT_CURRENT_HP) : this.effectiveHp;
        this.summonedByAffix = tag.getBoolean(NBT_SUMMONED);
        this.worldBoss = tag.getBoolean(NBT_WORLD_BOSS);
        readAffixMap(tag.getCompound(NBT_AFFIXES), affixes);
        readAffixMap(tag.getCompound(NBT_SEALED_AFFIXES), sealedAffixes); // 缺键时 getCompound 返空 tag: 无登记。
    }

    /** 词条→品质映射写成子 tag (词条名 -> 品质 ordinal); 词条表与被封词条登记共用同一格式。 */
    private static CompoundTag writeAffixMap(Map<AffixDef, AffixQuality> source) {
        CompoundTag affixTag = new CompoundTag();
        for (Map.Entry<AffixDef, AffixQuality> e : source.entrySet()) {
            affixTag.putInt(e.getKey().name(), e.getValue().ordinal());
        }
        return affixTag;
    }

    /** 把 {@link #writeAffixMap} 格式的子 tag 读进 target; 未知词条名 / 越界品质 ordinal 跳过该条。 */
    private static void readAffixMap(CompoundTag affixTag, EnumMap<AffixDef, AffixQuality> target) {
        AffixQuality[] qualities = AffixQuality.values();
        for (String key : affixTag.getAllKeys()) {
            AffixDef def = affixByName(key);
            if (def == null) {
                continue; // 未知词条名 (版本漂移): 跳过。
            }
            int ordinal = affixTag.getInt(key);
            if (ordinal < 0 || ordinal >= qualities.length) {
                continue; // 越界品质 ordinal: 跳过。
            }
            target.put(def, qualities[ordinal]);
        }
    }

    /** 词条名 -> AffixDef (未知返 null, 不抛; 供 NBT 还原容忍版本漂移删词条)。 */
    private static AffixDef affixByName(String name) {
        for (AffixDef def : AffixDef.values()) {
            if (def.name().equals(name)) {
                return def;
            }
        }
        return null;
    }
}
