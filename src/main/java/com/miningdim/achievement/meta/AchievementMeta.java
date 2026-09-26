package com.miningdim.achievement.meta;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.miningdim.achievement.tier.AchievementTier;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.GsonHelper;
import org.jetbrains.annotations.Nullable;

/**
 * 一个成就的元数据 ({@code data/miningdim/achievement_meta/<页签>/<名称>.json}, Achievement_System_DesignSpec 4.1):
 * 档位、是否隐藏、可选的成就点覆盖、可选的附带称号。与进度 JSON 由 datagen 从同一份声明同时生成。
 *
 * <p>两种写法:
 * <pre>
 * { "tier": "gold", "hidden": false, "points": 60, "title": "miningdim:mining/ore_codex" }   // points、title 可省
 * { "root": true }                                                                            // 页签根: 0 点、无称号
 * </pre>
 *
 * @param tier           档位; 页签根为 null
 * @param pointsOverride 覆盖档位默认值的成就点; null 取档位默认
 * @param title          附带称号 id; 没有为 null
 */
public record AchievementMeta(ResourceLocation id, @Nullable AchievementTier tier, boolean hidden,
                              @Nullable Integer pointsOverride, @Nullable ResourceLocation title) {

    public AchievementMeta {
        if (tier == null && (hidden || pointsOverride != null || title != null)) {
            throw new IllegalArgumentException("a tab root carries no tier, hidden flag, points or title: " + id);
        }
        if (pointsOverride != null && pointsOverride < 0) {
            throw new IllegalArgumentException("points must not be negative: " + id);
        }
    }

    /** 页签根的元数据。 */
    public static AchievementMeta root(ResourceLocation id) {
        return new AchievementMeta(id, null, false, null, null);
    }

    /** 是否为页签根 (不公告、不发 Toast、0 点)。 */
    public boolean isRoot() {
        return tier == null;
    }

    /** 实际成就点: 页签根 0, 否则覆盖值或档位默认值。 */
    public int points() {
        if (tier == null) {
            return 0;
        }
        return pointsOverride != null ? pointsOverride : tier.defaultPoints();
    }

    /** 元数据文件的 JSON 写法 (datagen 使用)。 */
    public JsonObject toJson() {
        JsonObject json = new JsonObject();
        if (tier == null) {
            json.addProperty("root", true);
            return json;
        }
        json.addProperty("tier", tier.id());
        json.addProperty("hidden", hidden);
        if (pointsOverride != null) {
            json.addProperty("points", pointsOverride);
        }
        if (title != null) {
            json.addProperty("title", title.toString());
        }
        return json;
    }

    /**
     * 解析一条元数据; 任何不合格都抛 {@link JsonParseException} 并带上原因 (未知档位、负点数、写坏的称号 id、
     * 页签根上多写了档位等字段)。
     */
    public static AchievementMeta fromJson(ResourceLocation id, JsonElement element) {
        JsonObject json = GsonHelper.convertToJsonObject(element, "achievement meta");
        if (GsonHelper.getAsBoolean(json, "root", false)) {
            for (String field : new String[]{"tier", "hidden", "points", "title"}) {
                if (json.has(field)) {
                    throw new JsonParseException("a tab root must not declare '" + field + "'");
                }
            }
            return root(id);
        }
        String rawTier = GsonHelper.getAsString(json, "tier");
        AchievementTier tier = AchievementTier.byId(rawTier)
                .orElseThrow(() -> new JsonParseException("unknown tier '" + rawTier + "'"));
        Integer points = null;
        if (json.has("points")) {
            points = GsonHelper.getAsInt(json, "points");
            if (points < 0) {
                throw new JsonParseException("points must not be negative, got " + points);
            }
        }
        ResourceLocation title = null;
        if (json.has("title")) {
            String rawTitle = GsonHelper.getAsString(json, "title");
            title = ResourceLocation.tryParse(rawTitle);
            if (title == null) {
                throw new JsonParseException("malformed title id '" + rawTitle + "'");
            }
        }
        return new AchievementMeta(id, tier, GsonHelper.getAsBoolean(json, "hidden", false), points, title);
    }
}
