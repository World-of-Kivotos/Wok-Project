package com.miningdim.caseopening;

import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** The first extensible case catalogue: 17 original skins in a 7/4/3/2/1 rarity split. */
public final class CaseCatalog {

    public static final String CASE_ID = "founders";
    public static final String DISPLAY_NAME = "创始武器箱";

    private static final List<CaseSkin> SKINS = List.of(
            CaseSkin.create("arctic_grid", "极地网格", CaseRarity.BLUE, "m4a1"),
            CaseSkin.create("copper_wasp", "赤铜胡蜂", CaseRarity.BLUE, "ak47"),
            CaseSkin.create("midnight_tide", "午夜潮汐", CaseRarity.BLUE, "glock_17"),
            CaseSkin.create("desert_signal", "荒漠信号", CaseRarity.BLUE, "hk_mp5a5"),
            CaseSkin.create("jade_circuit", "翡翠回路", CaseRarity.BLUE, "scar_l"),
            CaseSkin.create("urban_rain", "都市骤雨", CaseRarity.BLUE, "m1014"),
            CaseSkin.create("ember_trace", "余烬轨迹", CaseRarity.BLUE, "p90"),

            CaseSkin.create("violet_reactor", "紫晶反应堆", CaseRarity.PURPLE, "aug"),
            CaseSkin.create("crimson_current", "绯红电流", CaseRarity.PURPLE, "deagle"),
            CaseSkin.create("cobalt_fang", "钴蓝獠牙", CaseRarity.PURPLE, "ai_awp"),
            CaseSkin.create("neon_rift", "霓虹裂隙", CaseRarity.PURPLE, "vector45"),

            CaseSkin.create("aurora_protocol", "极光协议", CaseRarity.PINK, "hk416d"),
            CaseSkin.create("dragon_glass", "龙息琉璃", CaseRarity.PINK, "ak47"),
            CaseSkin.create("eclipse_bloom", "蚀日花火", CaseRarity.PINK, "m4a1"),

            CaseSkin.create("vermilion_sovereign", "朱雀君临", CaseRarity.RED, "ai_awp"),
            CaseSkin.create("obsidian_crown", "黑曜王冠", CaseRarity.RED, "deagle"),

            CaseSkin.create("gilded_omen", "鎏金神谕", CaseRarity.GOLD, "timeless50")
    );

    private static final Map<String, CaseSkin> BY_ID;
    private static final Map<CaseRarity, List<CaseSkin>> BY_RARITY;

    static {
        Map<String, CaseSkin> byId = new LinkedHashMap<>();
        EnumMap<CaseRarity, List<CaseSkin>> byRarity = new EnumMap<>(CaseRarity.class);
        for (CaseSkin skin : SKINS) {
            if (byId.putIfAbsent(skin.skinId(), skin) != null) {
                throw new IllegalStateException("duplicate case skin id: " + skin.skinId());
            }
        }
        for (CaseRarity rarity : CaseRarity.values()) {
            List<CaseSkin> pool = SKINS.stream().filter(skin -> skin.rarity() == rarity).toList();
            if (pool.isEmpty()) {
                throw new IllegalStateException("case rarity has no skins: " + rarity);
            }
            byRarity.put(rarity, pool);
        }
        int[] expected = {7, 4, 3, 2, 1};
        for (CaseRarity rarity : CaseRarity.values()) {
            if (byRarity.get(rarity).size() != expected[rarity.ordinal()]) {
                throw new IllegalStateException("unexpected skin count for " + rarity);
            }
        }
        BY_ID = Map.copyOf(byId);
        BY_RARITY = Map.copyOf(byRarity);
    }

    private CaseCatalog() {
    }

    public static List<CaseSkin> skins() {
        return SKINS;
    }

    public static List<CaseSkin> skins(CaseRarity rarity) {
        return BY_RARITY.get(rarity);
    }

    public static CaseSkin requireSkin(String skinId) {
        CaseSkin skin = BY_ID.get(skinId);
        if (skin == null) {
            throw new IllegalArgumentException("unknown case skin: " + skinId);
        }
        return skin;
    }

    public static void requireCase(String caseId) {
        if (!CASE_ID.equals(caseId)) {
            throw new IllegalArgumentException("unknown or retired case: " + caseId);
        }
    }
}
