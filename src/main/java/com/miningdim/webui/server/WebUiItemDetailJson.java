package com.miningdim.webui.server;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.miningdim.job.brewer.WineNbt;
import com.miningdim.job.engineer.NanoEffect;
import com.miningdim.job.engineer.NanoNbt;
import com.miningdim.job.munitions.ModMunitionsItems;
import com.miningdim.job.munitions.gunsmith.GunsmithGunStats;
import com.miningdim.job.munitions.gunsmith.GunsmithPartItem;
import com.miningdim.job.munitions.gunsmith.GunsmithPartQuality;
import com.miningdim.job.munitions.gunsmith.GunsmithPartVariant;
import com.miningdim.job.tarot.TarotCardItem;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.ItemStack;

/**
 * player.itemDetail 的"这件东西是什么 + 它的数值"那一半 (基础四字段与变体两字段由
 * {@link WebUiItemJson} 负责, 两者互补不重叠)。
 *
 * <h2>为什么住在 webui.server 而不是业务包</h2>
 * 它要同时读枪匠 / 塔罗 / 酿酒 / 纳米四个子系统的 NBT。放进任何一个业务包都会让那个包横向 import 另外三个;
 * 而 webui.server 本来就是"把各子系统的状态序列化给界面"的聚合层, 跨子系统读取是它的职责本身。
 *
 * <h2>脏 NBT 一律降级, 不冒泡 (决策 D8)</h2>
 * 这里的"脏"绝大多数不是攻击, 是正常游玩产物: 创造模式直给的裸塔罗牌 (源码自认的真实场景)、跨平衡改动的
 * 老枪 (缓存 stats 与按当前平衡表重算的值精确比对失配即读不出来)。让它们冒泡等于"背包里某些物品点开就报错"。
 * 故:
 *  1. 大类判定一律用非抛探针 —— 特别注意 {@code GunsmithPartItem.isGunsmithPart} <b>不是</b>探针, 它内部就调
 *     requirePartData 会抛; 判定必须用 {@code stack.is(GUNSMITH_PART)};
 *  2. 探针过了仍可能抛的两处, 复用属主类内部的降级包装 ({@code GunsmithGunStats.tryFrom} /
 *     {@code GunsmithPartItem.tryPartData}), 不在本层写 try-catch 生吞;
 *  3. 降级不是静默: kind 落回 plain, 且 tags 必带一条 {@code data.unreadable:<原本的大类>}, 玩家与开发都看得见。
 *
 * <h2>文案不在这里</h2>
 * attributes 的 key 与 tags 全是稳定机器码, 不带 label 也不带中文: 专用服务端不加载 lang, 服务端拼中文违反
 * "直给中文一律删"的纪律。中文由前端按码自查, 与 hub.panels 的 label 留前端是同一条纪律。
 */
public final class WebUiItemDetailJson {

    /** 相对基准的增减量 (0.12 = +12%); 服务端已按 (系数 - 1.0) 换算完毕, 前端只管乘 100 加百分号。 */
    private static final String UNIT_PERCENT = "percent";
    /** 绝对值, 单位由 key 自身语义决定 (tick 就是 tick, 服务端不折算成秒)。 */
    private static final String UNIT_FLAT = "flat";

    private static final String KIND_PLAIN = "plain";
    private static final String KIND_GUN = "gun";
    private static final String KIND_GUNSMITH_PART = "gunsmith_part";
    private static final String KIND_TAROT = "tarot";
    private static final String KIND_WINE = "wine";
    private static final String KIND_NANO = "nano";

    /** 降级标签前缀; 后缀是"它本来该是哪一类", 而不是笼统的一句"数据坏了"。 */
    private static final String TAG_UNREADABLE = "data.unreadable:";

    /** 乘子距 1.0 多远才算"真有效果"; 与 {@code GunsmithPartItem.addEffect} 的中性判据取同一个数。 */
    private static final double NEUTRAL_MULTIPLIER_EPSILON = 0.0005D;

    private WebUiItemDetailJson() {
    }

    /**
     * 把大类 / 数值行 / 标签三个字段补进已经填好基础字段的回执对象。
     *
     * @param target 调用方已填好 slot / itemId / descriptionId / count 与变体字段的对象, 本方法只<b>追加</b>
     * @param stack  该槽位的真实 ItemStack (调用方已保证非空)
     */
    public static void appendDetail(JsonObject target, ItemStack stack) {
        JsonArray attributes = new JsonArray();
        JsonArray tags = new JsonArray();
        String kind = describe(stack, attributes, tags);
        target.addProperty("kind", kind);
        // 两者恒存在: 无数值行 / 无标签时是空数组而不是缺席键, 前端少一条 undefined 分支。
        target.add("attributes", attributes);
        target.add("tags", tags);
    }

    /**
     * 判大类并顺带填好该类的数值行与标签。判定顺序即此处的分支顺序, 由"最具体的身份优先"决定:
     * 组装枪的根标签 &gt; 枪匠零件的物品 id &gt; 塔罗物品类型 &gt; 酒章 &gt; 纳米状态。
     */
    private static String describe(ItemStack stack, JsonArray attributes, JsonArray tags) {
        CompoundTag tag = stack.getTag();
        if (tag != null && tag.contains(GunsmithGunStats.ROOT_KEY)) {
            // 有根标签 = 这东西本来就是一把组装枪; 读不出来才是异常态, 不是"它不是枪"。
            GunsmithGunStats gun = GunsmithGunStats.tryFrom(stack);
            if (gun == null) {
                tags.add(TAG_UNREADABLE + KIND_GUN);
                return KIND_PLAIN;
            }
            appendGun(gun, attributes, tags);
            return KIND_GUN;
        }
        if (stack.is(ModMunitionsItems.GUNSMITH_PART.get())) {
            GunsmithPartItem.PartData part = GunsmithPartItem.tryPartData(stack);
            if (part == null) {
                tags.add(TAG_UNREADABLE + KIND_GUNSMITH_PART);
                return KIND_PLAIN;
            }
            appendGunsmithPart(part, attributes, tags);
            return KIND_GUNSMITH_PART;
        }
        if (stack.getItem() instanceof TarotCardItem) {
            if (!TarotCardItem.hasReadableCardIdentity(stack)) {
                tags.add(TAG_UNREADABLE + KIND_TAROT);
                return KIND_PLAIN;
            }
            appendTarot(stack, attributes, tags);
            return KIND_TAROT;
        }
        if (WineNbt.isWine(stack)) {
            if (!appendWine(stack, attributes, tags)) {
                tags.add(TAG_UNREADABLE + KIND_WINE);
                return KIND_PLAIN;
            }
            return KIND_WINE;
        }
        if (WineNbt.hasWineStamp(stack)) {
            // 带酒章却解不出品质 (id 随版本改名/删除) —— 与枪/零件/塔罗同规格标注。isWine 判据是品质非 null,
            // 单靠它这类酒会悄无声息落进 plain, 玩家只会以为这瓶酒本来就没数据。
            tags.add(TAG_UNREADABLE + KIND_WINE);
            return KIND_PLAIN;
        }
        if (!NanoNbt.effects(stack).isEmpty() || NanoNbt.producer(stack).isPresent()) {
            appendNano(stack, attributes, tags);
            return KIND_NANO;
        }
        return KIND_PLAIN;
    }

    /**
     * 组装枪的九行相对增减 + 零件数。
     *
     * 全部 getter 在 {@code tryFrom} 返回非 null 之后都是安全的: 构造器已按 version 分档把要用到的 stats 校验
     * 过一遍, version&lt;3 的 fireRate 与 version&lt;5 的 verticalRecoil / inaccuracy 走的是派生分支而不是读缺键。
     *
     * 后坐与散布三行取的是 {@code verticalRecoilMultiplier() / horizontalRecoilMultiplier() /
     * inaccuracyMultiplier()}: 这三个表达式与 {@code GunsmithStatMultipliers.of} 真正下发给 TaCZ 的
     * verticalRecoil / recoil / inaccuracy 三个分量逐位相同, 面板与枪械实际手感因此不可能漂移。
     * 垂直那行不能用 {@code recoilChange()} —— 它只含全向后坐, 势力组件的额外垂直加成 (红东高压导气 x3.0)
     * 不在里面, 拿它当垂直行会把真实的 +300% 显示成 +33%。
     *
     * 刻意不做: 不调 TaCZ 桥换算绝对伤害 / 射程。那依赖 TaCZ 加载与索引命中, empty 分支会让详情面板的行时有
     * 时无; 本批只发相对基准的增减量。
     */
    private static void appendGun(GunsmithGunStats gun, JsonArray attributes, JsonArray tags) {
        percent(attributes, "damage", gun.damage() - 1.0D);
        percent(attributes, "headshot", gun.headshot() - 1.0D);
        percent(attributes, "range", gun.range() - 1.0D);
        percent(attributes, "handling", gun.handling() - 1.0D);
        percent(attributes, "average", gun.average() - 1.0D);
        percent(attributes, "fireRate", gun.fireRateMultiplier() - 1.0D);
        percent(attributes, "verticalRecoil", gun.verticalRecoilMultiplier() - 1.0D);
        percent(attributes, "horizontalRecoil", gun.horizontalRecoilMultiplier() - 1.0D);
        percent(attributes, "inaccuracy", gun.inaccuracyMultiplier() - 1.0D);
        flat(attributes, "partCount", gun.parts().size());

        tags.add("gun.platform:" + gun.platform());
        tags.add("gun.template:" + gun.template());
    }

    /**
     * 零件的品质系数 + 该型号在这一品质下的全部属性偏移 (与零件 tooltip 逐条同源, 顺序也一致)。
     *
     * <h2>为什么按"偏离中性才发"而不是按变体分档</h2>
     * 旧口径是"BASE 不发, 非 BASE 固定发 fireRate / verticalRecoil / inaccuracy 三行"。组件规则表扩到十维之后
     * 这条近似判据两头都错: 红冬赤雪 A 型枪机的卖点是伤害 +25% / 穿甲 -25% / 后坐 +35%, 而它的 fireRate、
     * verticalRecoil、spread 恰好全是 1.0 —— 平板上于是只剩三行 +0%, 玩家会判定这枚传奇组件"没有任何特殊属性"。
     * 改成逐项判中性: 阈值与 {@code GunsmithPartItem.addEffect} 的中性判据同一个数, 平板上不出现的行在物品
     * tooltip 上一定也是灰的 +0%, 两套展示不可能再各说各话。
     *
     * 强制火力模式与最大耐久修正没有"乘子"形态可判, 前者发成标签, 后者按乘子同规格发一行百分比。
     */
    private static void appendGunsmithPart(GunsmithPartItem.PartData part, JsonArray attributes, JsonArray tags) {
        flat(attributes, "coefficient", part.coefficient());
        GunsmithPartVariant variant = part.variant();
        GunsmithPartQuality quality = part.quality();
        effect(attributes, "damage", variant.damageMultiplier(quality));
        effect(attributes, "headshot", variant.headshotMultiplier(quality));
        effect(attributes, "fireRate", variant.fireRateMultiplier(quality));
        effect(attributes, "range", variant.rangeMultiplier(quality));
        effect(attributes, "ammoSpeed", variant.ammoSpeedMultiplier(quality));
        effect(attributes, "armorIgnore", variant.armorIgnoreMultiplier(quality));
        effect(attributes, "inaccuracy", variant.spreadMultiplier(quality));
        effect(attributes, "recoil", variant.recoilMultiplier(quality));
        effect(attributes, "verticalRecoil", variant.verticalRecoilMultiplier(quality));
        effect(attributes, "adsSpeed", variant.adsSpeedMultiplier(quality));
        effect(attributes, "maximumDurability", variant.maximumDurabilityMultiplier());

        tags.add("part.platform:" + part.platform().id());
        tags.add("part.slot:" + part.part().id());
        tags.add("part.variant:" + variant.id());
        tags.add("part.quality:" + part.quality().id());
        if (variant.forcesBurstFireMode()) {
            // 火力模式是"有/无", 不是可乘的数值; 与 tarot.bound / wine.spoiled 同规格发成无值标签。
            tags.add("part.burstFire");
        }
    }

    /** 牌面身份。正逆位发成两个互斥标签而不是一个布尔, 与其余标签同一形态, 前端一张码表全解完。 */
    private static void appendTarot(ItemStack stack, JsonArray attributes, JsonArray tags) {
        flat(attributes, "cardId", TarotCardItem.cardId(stack));

        tags.add("tarot.quality:" + TarotCardItem.quality(stack).id());
        tags.add(TarotCardItem.upright(stack) ? "tarot.upright" : "tarot.reversed");
        if (TarotCardItem.owner(stack) != null) {
            // 绑定与否影响"这张牌你打不打得出", 是玩家真正要看的一行。
            tags.add("tarot.bound");
        }
    }

    /**
     * 酒的年份与强度。变质酒的 strength 恒 0, 那正是 {@code WineNbt.strength} 自己的口径, 不在此另判。
     *
     * 返回 false = 数值不可用, 调用方按降级处理; 此时保证一个 attribute 都没写进去 (先取值验完再写)。
     *
     * 为什么单独验有限性: {@code WineNbt.readVintage} 是裸的 {@code getDouble}, 手改存档或 /data modify 能
     * 写进 Infinity / NaN。Gson 序列化 JsonPrimitive 时内部 setLenient(true), 不抛异常, 而是原样吐出
     * {@code NaN} / {@code Infinity} 字面量 —— 那串东西回到前端 JSON.parse 直接失败, 症状是"某一格点开就报
     * 一句解析错误", 与真正的病因隔得极远。四大类里只有酒这一支上游没有把关 (枪走 value() 的 isFinite,
     * 零件 coefficient 在 requireCoefficient 里校验, 塔罗与纳米全是 int)。
     */
    private static boolean appendWine(ItemStack stack, JsonArray attributes, JsonArray tags) {
        double vintage = WineNbt.readVintage(stack);
        double strength = WineNbt.strength(stack);
        if (!Double.isFinite(vintage) || !Double.isFinite(strength)) {
            return false;
        }
        flat(attributes, "vintage", vintage);
        flat(attributes, "strength", strength);

        // isWine() 的判据就是 readQuality() != null, 走到这里它必非 null。
        tags.add("wine.quality:" + WineNbt.readQuality(stack).id());
        if (WineNbt.isSpoiled(stack)) {
            tags.add("wine.spoiled");
        }
        return true;
    }

    /** 纳米护甲 / 护甲板状态。三个 tick 值原样下发不折算成秒 —— 折算口径归前端, 服务端只给权威数值。 */
    private static void appendNano(ItemStack stack, JsonArray attributes, JsonArray tags) {
        flat(attributes, "shieldCharges", NanoNbt.shieldCharges(stack));
        flat(attributes, "shieldRegenTick", NanoNbt.shieldRegenTick(stack));
        flat(attributes, "shieldWindowTick", NanoNbt.shieldWindowTick(stack));
        flat(attributes, "qualityHits", NanoNbt.qualityHits(stack));

        for (NanoEffect effect : NanoNbt.effects(stack)) {
            tags.add("nano.effect:" + effect.id());
        }
        if (NanoNbt.isProductionXpPending(stack)) {
            tags.add("nano.xpPending");
        }
    }

    /**
     * 乘子偏离中性才发一行, 否则整行不发。
     *
     * 阈值 0.0005 不是随手取的: 它就是 {@code GunsmithPartItem.addEffect} 判"这一行是灰的中性值"用的那个数,
     * 抄同一个常量才能保证平板与物品 tooltip 的显隐完全同步。
     */
    private static void effect(JsonArray attributes, String key, double multiplier) {
        if (Math.abs(multiplier - 1.0D) > NEUTRAL_MULTIPLIER_EPSILON) {
            percent(attributes, key, multiplier - 1.0D);
        }
    }

    private static void percent(JsonArray attributes, String key, double value) {
        attributes.add(stat(key, value, UNIT_PERCENT));
    }

    private static void flat(JsonArray attributes, String key, double value) {
        attributes.add(stat(key, value, UNIT_FLAT));
    }

    private static JsonObject stat(String key, double value, String unit) {
        JsonObject row = new JsonObject();
        row.addProperty("key", key);
        row.addProperty("value", value);
        row.addProperty("unit", unit);
        return row;
    }
}
