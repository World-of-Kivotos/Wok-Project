package com.miningdim.champion;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.miningdim.champion.bloodpool.BloodPool;
import com.miningdim.champion.bloodpool.BloodPoolRegistry;
import com.miningdim.core.Difficulty;
import com.miningdim.webui.server.WebUiBusinessException;
import com.miningdim.webui.server.WebUiErrorCodes;
import com.miningdim.webui.server.WebUiPayloads;
import com.miningdim.webui.server.WebUiServerDispatcher;
import com.miningdim.webui.server.WebUiServerDispatcher.WebUiAction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 精英怪图鉴的 champion.* WebUiAction (W8): 静态词条/星级图鉴 + 按实体查在场冠军。
 *
 * 落在 {@code com.miningdim.champion} 包内是因为它读的全是本包的权威表 ({@link AffixDef} / {@link StarRank} /
 * {@link MiningChampionData}) 与血池注册表 —— 在别处重写一份"词条 -&gt; 数值"的映射, 那份副本迟早与真正结算时
 * 用的数值分叉, 而图鉴分叉的后果是玩家照着面板算出的伤害对不上实战。
 *
 * 量纲纪律 (本组最容易踩的坑): 35 条词条的 5 档数值语义各不相同 —— 0.35 可能是"减伤 35%"、可能是"每秒回 0.35
 * 点血"、也可能是"周期 0.35 秒"。故每条词条自带 {@code primaryUnit} (有副数值的再带 {@code secondaryUnit}),
 * 前端据它决定把 0.35 显示成 "35%" 还是 "0.35 HP/s"。跨词条比大小在这里没有意义, 图鉴也不该提供这种排序。
 *
 * 回执体积: champion.codex 是四张枚举表 (35 词条 + 5 品质 + 10 星级 + 3 难度) 的全量 dump, 没有任何随运行期
 * 数据增长的成分, 上限即枚举基数; champion.inspect 的词条数上限同为 35 (AffixDef 基数)。两者都由
 * {@link ChampionWebUiGameTests} 逐次实测长度并断言不逼近下行 32767 上限, 不指望派发器的收口兜底。
 */
public final class ChampionWebUiActions {

    /**
     * 默认 {@link Gson} (不 serializeNulls)。本类<b>不发任何 null</b>: 没有副数值的词条整对键
     * ({@code secondaryUnit} / {@code secondaryValues}) 直接不写, 而不是写一个 null 占位 —— 于是前端契约里
     * 这两个键是可选 (?:) 而非 {@code | null}。
     */
    private static final Gson GSON = new Gson();

    /** {@link #INSPECT} 拒绝时 params 里的原因位: 找不到实体。 */
    static final String REASON_ENTITY_NOT_FOUND = "ENTITY_NOT_FOUND";

    /** {@link #INSPECT} 拒绝时 params 里的原因位: 找到了但不是本工程盖章的精英怪。 */
    static final String REASON_NOT_A_CHAMPION = "NOT_A_CHAMPION";

    /** 血量取自 6★+ 自定义血池 (战斗权威值, 可远超 1024)。 */
    static final String HEALTH_SOURCE_BLOOD_POOL = "BLOOD_POOL";

    /** 血量取自 vanilla generic.max_health (1-5★ 且有效血未破 1024 时的权威值)。 */
    static final String HEALTH_SOURCE_VANILLA = "VANILLA_MAX_HEALTH";

    private ChampionWebUiActions() {
    }

    /** 把两条 champion.* action 注册进派发器 (由 {@link ChampionSystem#register} 调用一次)。 */
    public static void registerAll() {
        WebUiServerDispatcher.register("champion.codex", CODEX);
        WebUiServerDispatcher.register("champion.inspect", INSPECT);
    }

    // ============================================================
    // champion.codex: {} -> 35 词条 + 5 品质 + 10 星级 + 3 难度分布 (纯静态 dump)
    // ============================================================

    /**
     * 图鉴全量 dump。每次调用现算而不是缓存一份静态字符串: 数据源全是枚举常量, 现算的代价只有几十微秒,
     * 而缓存要多担一份"类初始化期异常变成 ExceptionInInitializerError"的风险, 不值当。
     *
     * 不收任何入参 (也就没有分页): 四张表的行数由枚举基数封死, 不存在"数据长大撑爆回执"的路径。
     */
    static final WebUiAction CODEX = (sender, payload) -> {
        JsonObject result = new JsonObject();
        // 6★ 起走自定义血池 (spec 6.2): 前端据此在星级表上画出"血量口径换轨"的那条线。
        result.addProperty("customBloodPoolMinStar", StarRank.CUSTOM_BLOOD_POOL_MIN_STAR);
        // 掷星是难度区间内均匀取整 (ChampionSpawnPolicy.rollStar), 不是加权表 —— 说清楚, 免得前端自造权重。
        result.addProperty("starRollMode", "UNIFORM_INCLUSIVE");
        result.add("qualities", qualitiesJson());
        result.add("affixes", affixesJson());
        result.add("stars", starsJson());
        result.add("distribution", distributionJson());
        return GSON.toJson(result);
    };

    /** 5 档品质: 成本系数 + 展示色 (词条成本 = ceil(baseCost x costMultiplier), 见每条词条的 costs 数组)。 */
    private static JsonArray qualitiesJson() {
        JsonArray qualities = new JsonArray();
        for (AffixQuality quality : AffixQuality.values()) {
            JsonObject row = new JsonObject();
            row.addProperty("qualityId", quality.name());
            row.addProperty("tier", quality.valueIndex());
            row.addProperty("costMultiplier", quality.costMultiplier());
            row.addProperty("displayColorRgb", quality.displayColor());
            qualities.add(row);
        }
        return qualities;
    }

    /** 35 条词条 (顺序 = {@link AffixDef} 声明序 = spec 第七章的四池分节顺序)。 */
    private static JsonArray affixesJson() {
        JsonArray affixes = new JsonArray();
        for (AffixDef def : AffixDef.values()) {
            JsonObject row = new JsonObject();
            row.addProperty("affixId", def.name());
            row.addProperty("nameKey", def.displayNameKey());
            row.addProperty("pool", def.pool().name());
            row.addProperty("baseCost", def.baseCost());
            row.addProperty("minStar", def.minStar());
            row.addProperty("isSkill", def.isSkill());
            // 无互斥也发真值 "NONE" 而不是 null: MutexFlag.NONE 是枚举里实打实的一档, 换成 null 等于让前端
            // 分不清"这条真的不互斥"与"服务端漏发了这个字段"。
            row.addProperty("mutexFlag", def.mutexFlag().name());
            row.addProperty("minQuality", def.minUsableQuality().name());
            row.addProperty("primaryUnit", primaryUnitOf(def));

            JsonArray primaryValues = new JsonArray();
            JsonArray availableTiers = new JsonArray();
            JsonArray costs = new JsonArray();
            for (AffixQuality quality : AffixQuality.values()) {
                double primary = def.valueFor(quality);
                primaryValues.add(primary);
                // 0 在本表里恒为"该档不存在"的占位 (重型护甲/刚毅的前导两档、小男孩/命定的前导三档、自我修复
                // 的中级档那个 "—"), 没有任何词条的合法数值是 0。显式发一个布尔而不是让前端去猜 0 的含义 ——
                // 猜错的结果是图鉴上多出一排"减伤 0%"的假档位。
                availableTiers.add(primary != 0.0D);
                costs.add(def.costAt(quality));
            }
            row.add("primaryValues", primaryValues);
            row.add("availableTiers", availableTiers);
            row.add("costs", costs);

            if (def.hasSecondaryValues()) {
                row.addProperty("secondaryUnit", secondaryUnitOf(def));
                JsonArray secondaryValues = new JsonArray();
                for (AffixQuality quality : AffixQuality.values()) {
                    secondaryValues.add(def.secondaryValueFor(quality));
                }
                row.add("secondaryValues", secondaryValues);
            }
            affixes.add(row);
        }
        return affixes;
    }

    /** 10 星主数据表 (四池预算 / 两个上限 / 最高品质 / 基础有效 HP / 单击基线 + 派生的红线与血池口径)。 */
    private static JsonArray starsJson() {
        JsonArray stars = new JsonArray();
        for (StarRank rank : StarRank.values()) {
            JsonObject row = new JsonObject();
            row.addProperty("star", rank.star());
            row.addProperty("survivalBudget", rank.survivalBudget());
            row.addProperty("combatBudget", rank.combatBudget());
            row.addProperty("mobilityBudget", rank.mobilityBudget());
            row.addProperty("skillBudget", rank.skillBudget());
            row.addProperty("maxAffixes", rank.maxAffixes());
            row.addProperty("maxSkills", rank.maxSkills());
            row.addProperty("maxQuality", rank.maxQuality().name());
            row.addProperty("baseEffectiveHp", rank.baseEffectiveHp());
            // 两列都是 %maxHP 小数: baseSingleHitPct 是该星普通单击的设计基线, normalHitCapPct 是红线 3 的
            // 硬封顶。发两列是因为它们回答的是两个问题 (期望值 / 不可越的上限), 合成一列就永远说不清。
            row.addProperty("baseSingleHitPct", rank.baseSingleHitPct());
            row.addProperty("normalHitCapPct", rank.normalHitCapPct());
            row.addProperty("usesCustomBloodPool", rank.usesCustomBloodPool());
            row.addProperty("barColorRgb", rank.barColorRgb());
            stars.add(row);
        }
        return stars;
    }

    /** 三档矿洞难度的升格率与掷星区间 ({@link ChampionSpawnPolicy} 是这三行的唯一真源)。 */
    private static JsonArray distributionJson() {
        JsonArray distribution = new JsonArray();
        for (Difficulty difficulty : Difficulty.values()) {
            JsonObject row = new JsonObject();
            row.addProperty("difficulty", difficulty.name());
            row.addProperty("configName", difficulty.configName());
            row.addProperty("promoteChance", ChampionSpawnPolicy.promoteChance(difficulty));
            row.addProperty("minStar", ChampionSpawnPolicy.minStar(difficulty));
            row.addProperty("maxStar", ChampionSpawnPolicy.maxStar(difficulty));
            distribution.add(row);
        }
        return distribution;
    }

    // ============================================================
    // champion.inspect: {entityId} -> 该实体的星级/词条/血量
    // ============================================================

    /**
     * 按网络实体 id 查在场精英怪。
     *
     * 只查发送者所在维度 ({@code sender.serverLevel().getEntity}): 网络实体 id 只在单个维度内唯一, 跨维度查
     * 既查不准也没有业务场景。
     *
     * 血量是本条的要害。6★ 起 (或有效血破 1024 的低星巨大化怪) 的战斗权威是自定义血池, vanilla 那份被属性
     * 上限钳在 1024 只作渲染镜像 —— 拿 vanilla 的 1024 去除以池子的 6000 算血条比例, 结果是错的。故回执把
     * 两套都发出去并用 {@code healthSource} 明说 {@code health}/{@code maxHealth} 取自哪一套, 前端画条一律
     * 只用这一对, {@code vanillaHealth}/{@code vanillaMaxHealth} 仅供对账诊断。
     *
     * 查不到实体 / 不是精英怪一律抛业务拒绝, 绝不返回一份全 0 的成功回执冒充"这只怪什么都没有"。
     */
    static final WebUiAction INSPECT = (sender, payload) -> {
        int entityId = WebUiPayloads.requiredInt(payload, "entityId");
        Entity entity = sender.serverLevel().getEntity(entityId);
        if (entity == null) {
            throw inspectRejection(entityId, REASON_ENTITY_NOT_FOUND,
                    "实体 " + entityId + " 不在你所在的维度, 或已被移除");
        }
        if (!(entity instanceof LivingEntity living)) {
            throw inspectRejection(entityId, REASON_NOT_A_CHAMPION,
                    "实体 " + entityId + " 不是生物, 不可能是精英怪");
        }
        MiningChampionData champion = MiningChampions.get(living).orElse(null);
        if (champion == null || !champion.isChampion()) {
            throw inspectRejection(entityId, REASON_NOT_A_CHAMPION,
                    "实体 " + entityId + " 不是精英怪 (未被盖章)");
        }

        StarRank rank = StarRank.ofStar(champion.star());
        BloodPool pool = BloodPoolRegistry.get(living.getUUID());
        // 判据取血池注册表的在册事实, 而不是 rank.usesCustomBloodPool(): 建池条件是 "6★+ 【或】有效血破 1024"
        // (见 ChampionPromoter.applyBaseHealth), 低星巨大化怪也可能在册, 按星级反推会漏掉那一类。
        boolean customBloodPool = pool != null;
        double health = customBloodPool ? pool.currentHp() : living.getHealth();
        double maxHealth = customBloodPool ? pool.maxHp() : living.getMaxHealth();

        JsonObject result = new JsonObject();
        result.addProperty("entityId", entityId);
        result.addProperty("entityTypeId", entityTypeId(living));
        // 只发翻译键: 专用服务器不加载 lang, 中文由 MCEF 宿主所在客户端解 (与 admin.listItems 同纪律)。
        result.addProperty("entityDescriptionId", living.getType().getDescriptionId());
        result.addProperty("star", champion.star());
        result.addProperty("maxQuality", rank.maxQuality().name());
        result.addProperty("summonedByAffix", champion.isSummonedByAffix());
        result.addProperty("effectiveHp", champion.effectiveHp());
        result.addProperty("customBloodPool", customBloodPool);
        result.addProperty("healthSource", customBloodPool ? HEALTH_SOURCE_BLOOD_POOL : HEALTH_SOURCE_VANILLA);
        result.addProperty("health", health);
        result.addProperty("maxHealth", maxHealth);
        result.addProperty("healthFraction", ChampionBossBarText.progress(health, maxHealth));
        result.addProperty("vanillaHealth", living.getHealth());
        result.addProperty("vanillaMaxHealth", living.getMaxHealth());

        JsonArray affixes = new JsonArray();
        for (Map.Entry<AffixDef, AffixQuality> entry : champion.affixes().entrySet()) {
            AffixDef def = entry.getKey();
            AffixQuality quality = entry.getValue();
            JsonObject row = new JsonObject();
            row.addProperty("affixId", def.name());
            row.addProperty("nameKey", def.displayNameKey());
            row.addProperty("pool", def.pool().name());
            row.addProperty("isSkill", def.isSkill());
            row.addProperty("quality", quality.name());
            row.addProperty("tier", quality.valueIndex());
            row.addProperty("cost", def.costAt(quality));
            row.addProperty("primaryUnit", primaryUnitOf(def));
            row.addProperty("primaryValue", def.valueFor(quality));
            if (def.hasSecondaryValues()) {
                row.addProperty("secondaryUnit", secondaryUnitOf(def));
                row.addProperty("secondaryValue", def.secondaryValueFor(quality));
            }
            affixes.add(row);
        }
        result.add("affixes", affixes);
        return GSON.toJson(result);
    };

    /**
     * champion.inspect 的业务拒绝。
     *
     * 借用 {@link WebUiErrorCodes#INVALID_REQUEST} 并在 params 里多带一位 {@code reason} 区分两种拒绝
     * (找不到实体 / 不是精英怪): 错误码表是跨组共享文件, 本组无权新增码。前端可先按 reason 分两句文案,
     * 待主控补上专用错误码后再切。params 用 LinkedHashMap 而非 Map.of, 保证回执里键序稳定可逐字节比对。
     */
    private static WebUiBusinessException inspectRejection(int entityId, String reason, String message) {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("field", "entityId");
        params.put("value", Integer.toString(entityId));
        params.put("reason", reason);
        return new WebUiBusinessException(WebUiErrorCodes.INVALID_REQUEST, message, false, params);
    }

    /** 实体注册 id (形如 minecraft:zombie)。查不到属装配缺陷 (实体已在世界里却没注册), 自然抛不掩盖。 */
    private static String entityTypeId(LivingEntity living) {
        ResourceLocation key = ForgeRegistries.ENTITY_TYPES.getKey(living.getType());
        if (key == null) {
            throw new IllegalStateException("实体类型未在 ENTITY_TYPES 注册: " + living.getType());
        }
        return key.toString();
    }

    // ============================================================
    // 量纲表 (逐条取自 AffixDef 各成员的结算语义注释)
    // ============================================================

    /**
     * 主数值的量纲。发错一条, 前端就会把"减伤 35%"画成"减伤 0.35%", 或者把"周期 9 秒"当成"900% 加成"。
     *
     * 命名约定: {@code fraction_*} 是 0-1 小数比率 (前端 x100 显示成百分比), {@code flat_*} 是绝对数值,
     * {@code seconds_*} 是秒, {@code *_count} 是个数, {@code multiplier} 是纯倍率。
     */
    private static String primaryUnitOf(AffixDef def) {
        return switch (def) {
            // 减伤/抗性类: 数值即减伤比率, 并入净减伤 75% 钳制。
            case COMPOSITE_ARMOR, UHMWPE_ARMOR, HEAVY_ARMOR -> "fraction_damage_reduction";
            // 脱战再生按 %maxHP/s; 易燃再生与自我修复是 FLAT HP/s (同为"每秒回血"但量纲不同, 不可混)。
            case REGEN_TISSUE -> "fraction_maxhp_per_second";
            case FLAMMABLE_REGEN, SELF_REPAIR -> "flat_hp_per_second";
            case DEFLECTOR_SHIELD -> "fraction_dodge_chance";
            // 刚毅护盾是单次伤害封顶的绝对 HP, 且品质越高数值越小 = 越硬 (前端别按"大即强"排序)。
            case FORTITUDE_SHIELD -> "flat_hp_damage_cap";
            case THORNS, ARMOR_PIERCING, ELECTRO_CHARGE, THUNDER, LITTLE_BOY -> "fraction_maxhp";
            case GIGANTISM -> "fraction_max_health_bonus";
            case MINIATURIZATION -> "fraction_max_health_penalty";
            case BURNING, FROST -> "fraction_maxhp_per_second_per_stack";
            case REND -> "fraction_vulnerability_per_stack";
            case HEAVY_CANNON, BLOODLUST -> "fraction_damage_bonus";
            case CORROSIVE -> "durability_points_per_hit";
            case DOUBLE_STRIKE, QUADRUPLE_STRIKE -> "hit_count";
            // 混沌重击的 5 档恒 1: 它是"有没有这条"的开关, 数值不参与结算, 明说成 flag 免得被画成一条平坦曲线。
            case CHAOS_STRIKE -> "flag";
            case SPRINT, OVERDRIVE -> "fraction_move_speed_bonus";
            // 施放周期/CD 秒: 品质越高数值越小 = 越频繁 (同样别按"大即强"排序)。
            case BLINK, TACTICAL_BLINK, CAESAR_SWAP -> "seconds_cooldown";
            case PHASE_WALK, VISUAL_DISRUPTION -> "seconds_duration";
            case DEATH_MARK -> "multiplier";
            case COUNTER_UNIT -> "fraction_reflect";
            case BLADE_WALTZ, SUMMON_SUPPORT -> "count";
        };
    }

    /**
     * 副数值的量纲 (仅 {@link AffixDef#hasSecondaryValues()} 为真的 5 条词条有)。
     *
     * 给新加了副数值却没登记量纲的词条留一个响亮的失败: 静默发一个空串会让前端把点数当成秒来显示。
     */
    private static String secondaryUnitOf(AffixDef def) {
        return switch (def) {
            case GIGANTISM -> "fraction_size_bonus";
            case MINIATURIZATION -> "fraction_size_penalty";
            case FROST -> "fraction_slow_per_stack";
            case THUNDER -> "strike_count";
            case SUMMON_SUPPORT -> "concurrent_count";
            default -> throw new IllegalStateException("词条有副数值却没登记副数值量纲: " + def.name());
        };
    }
}
