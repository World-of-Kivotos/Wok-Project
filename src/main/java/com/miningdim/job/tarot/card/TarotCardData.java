package com.miningdim.job.tarot.card;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.miningdim.job.tarot.TarotCooldownManager;
import com.miningdim.job.tarot.TarotQuality;
import net.minecraft.util.GsonHelper;

import java.util.ArrayList;
import java.util.List;

/**
 * 单张大阿卡纳的牌效数值表 (TarotReader spec 第六章 + 第十一章 datapack 来源)。从 data/miningdim/tarot/cards/
 * &lt;NN&gt;_&lt;id&gt;.json 反序列化, 由 {@link TarotCardLoader} 加载。缺字段抛异常冒泡, 不静默给默认 (C9)。
 *
 * 结构 (与 22 份 JSON 一一对应):
 * <pre>
 * {
 *   "cooldownCategory": "buff",          // utility|buff|combat -> 每卡 CD 分档 (spec 9.5)
 *   "upright":  { "tiers": [ [op...], [op...], [op...], [op...] ] },   // R/SR/SSR/UR 四档
 *   "reversed": { "tiers": [ [op...], [op...], [op...], [op...] ] },
 *   "shiny":    { "cooldownTicks": 12000, "ops": [ op... ] }            // 签名大招 (分钟级 CD)
 * }
 * </pre>
 * tiers 必须恰好 4 项 (R/SR/SSR/UR), 缺/多即结构错误抛出 (spec C9)。
 */
public final class TarotCardData {

    /** 四档 (R/SR/SSR/UR); 闪耀不走档。 */
    public static final int TIER_COUNT = 4;

    private final TarotCooldownManager.Category cooldownCategory;
    private final List<List<TarotEffectOp>> uprightTiers; // size 4
    private final List<List<TarotEffectOp>> reversedTiers; // size 4
    private final List<TarotEffectOp> shinyOps;
    private final int shinyCooldownTicks;

    private TarotCardData(TarotCooldownManager.Category cooldownCategory,
                          List<List<TarotEffectOp>> uprightTiers,
                          List<List<TarotEffectOp>> reversedTiers,
                          List<TarotEffectOp> shinyOps,
                          int shinyCooldownTicks) {
        this.cooldownCategory = cooldownCategory;
        this.uprightTiers = uprightTiers;
        this.reversedTiers = reversedTiers;
        this.shinyOps = shinyOps;
        this.shinyCooldownTicks = shinyCooldownTicks;
    }

    /** 每卡 CD 分档 (spec 9.5)。 */
    public TarotCooldownManager.Category cooldownCategory() {
        return cooldownCategory;
    }

    /** 闪耀签名大招的 CD (ticks; spec 第六章表分钟级)。 */
    public int shinyCooldownTicks() {
        return shinyCooldownTicks;
    }

    /**
     * 取某品质 + 某朝向应执行的操作列表。
     * @param quality    R/SR/SSR/UR 取四档对应朝向; SHINY 忽略朝向返回 shinyOps。
     * @param upright    true 正位 / false 逆位 (SHINY 不分朝向)。
     */
    public List<TarotEffectOp> opsFor(TarotQuality quality, boolean upright) {
        if (quality == TarotQuality.SHINY) {
            return shinyOps;
        }
        int idx = quality.tierIndex();
        if (idx < 0 || idx >= TIER_COUNT) {
            throw new IllegalArgumentException("non-tier quality has no tier ops: " + quality);
        }
        return upright ? uprightTiers.get(idx) : reversedTiers.get(idx);
    }

    public static TarotCardData fromJson(JsonObject root) {
        String cat = GsonHelper.getAsString(root, "cooldownCategory");
        TarotCooldownManager.Category category = TarotCooldownManager.Category.byId(cat);

        List<List<TarotEffectOp>> upright = parseTiers(GsonHelper.getAsJsonObject(root, "upright"));
        List<List<TarotEffectOp>> reversed = parseTiers(GsonHelper.getAsJsonObject(root, "reversed"));

        JsonObject shiny = GsonHelper.getAsJsonObject(root, "shiny");
        int shinyCd = GsonHelper.getAsInt(shiny, "cooldownTicks");
        List<TarotEffectOp> shinyOps = parseOpList(GsonHelper.getAsJsonArray(shiny, "ops"));

        return new TarotCardData(category, upright, reversed, shinyOps, shinyCd);
    }

    private static List<List<TarotEffectOp>> parseTiers(JsonObject orientation) {
        JsonArray tiers = GsonHelper.getAsJsonArray(orientation, "tiers");
        if (tiers.size() != TIER_COUNT) {
            throw new IllegalArgumentException(
                    "tarot card 'tiers' must have exactly 4 entries (R/SR/SSR/UR), got " + tiers.size());
        }
        List<List<TarotEffectOp>> out = new ArrayList<>(TIER_COUNT);
        for (JsonElement tier : tiers) {
            out.add(parseOpList(GsonHelper.convertToJsonArray(tier, "tier")));
        }
        return out;
    }

    private static List<TarotEffectOp> parseOpList(JsonArray arr) {
        List<TarotEffectOp> ops = new ArrayList<>(arr.size());
        for (JsonElement el : arr) {
            ops.add(TarotEffectOp.fromJson(GsonHelper.convertToJsonObject(el, "op")));
        }
        return ops;
    }
}
