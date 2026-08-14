package com.miningdim.entry;

import com.miningdim.core.MiningConstants;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

/**
 * WebUI 界面偏好 ({@link UiPrefs}) 的存档兼容与复制 GameTest。
 *
 * 锁三条硬约束:
 *  1. 读路径遇脏数据只回退不抛 —— 这条路径跑在玩家 capability 反序列化时, 没有 Gateway 兜底, 抛出去的症状是
 *     玩家进不来 (旧存档缺键、手改存档类型错、服务端后来收窄取值域, 三类都必须活着回来);
 *  2. 写路径遇非法值必须抛 —— 静默钳制等于让契约声明的取值域失效, 前端永远不知道自己发了什么;
 *  3. copyFrom 带上偏好 —— copyFrom 是手写逐字段, 加字段漏拷贝编译器不报错, 症状是玩家一死主题就复位。
 */
@GameTestHolder(MiningConstants.MODID)
@PrefixGameTestTemplate(false)
public final class UiPrefsGameTests {

    private static final String EMPTY = "empty";
    private static final String BATCH = "entry";

    /** 合法偏好必须原样存档往返 (若 sanitized 无脑返回 DEFAULT, 本条即挂)。 */
    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void uiPrefsRoundTripKeepsValues(GameTestHelper helper) {
        UiPrefs stored = new UiPrefs(true, "en_us", UiPrefs.THEME_LIGHT, 12);
        MiningPlayerData source = new MiningPlayerData();
        source.setUiPrefs(stored);

        MiningPlayerData loaded = new MiningPlayerData();
        loaded.deserializeNBT(source.serializeNBT());

        helper.assertTrue(loaded.uiPrefs().muteToasts(), "muteToasts 往返保持 true");
        helper.assertTrue("en_us".equals(loaded.uiPrefs().language()), "language 往返保持 en_us");
        helper.assertTrue(UiPrefs.THEME_LIGHT.equals(loaded.uiPrefs().theme()), "theme 往返保持 light");
        helper.assertTrue(loaded.uiPrefs().brandHue() == 12, "brandHue 往返保持 12");
        helper.succeed();
    }

    /** 旧存档整个子标签缺席: 全默认, 且不抛。 */
    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void uiPrefsMissingSubTagFallsBackToDefault(GameTestHelper helper) {
        // 模拟本功能上线前的存档: 顶层 tag 里根本没有 uiPrefs 这个键。
        CompoundTag legacy = new MiningPlayerData().serializeNBT();
        legacy.remove("uiPrefs");

        MiningPlayerData loaded = new MiningPlayerData();
        loaded.deserializeNBT(legacy);

        helper.assertTrue(UiPrefs.DEFAULT.equals(loaded.uiPrefs()), "缺子标签回退整份 DEFAULT");
        helper.succeed();
    }

    /**
     * 键都在、值非法: 逐字段各自回退, 不抛。
     * theme='compact' 是像素风时代真存在过的档, 服务端收窄取值域后老存档就长这样。
     */
    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void uiPrefsIllegalValuesFallBackPerField(GameTestHelper helper) {
        CompoundTag prefs = new CompoundTag();
        prefs.putString("theme", "compact");
        prefs.putInt("brandHue", 999);
        prefs.putString("language", "");
        // muteToasts 键刻意整个缺席, 与其余三个"键在值错"混在同一份脏数据里。

        CompoundTag root = new MiningPlayerData().serializeNBT();
        root.put("uiPrefs", prefs);

        MiningPlayerData loaded = new MiningPlayerData();
        loaded.deserializeNBT(root);

        helper.assertTrue(!loaded.uiPrefs().muteToasts(), "muteToasts 缺键回退 false");
        helper.assertTrue(UiPrefs.DEFAULT_LANGUAGE.equals(loaded.uiPrefs().language()), "空串 language 回退 zh_cn");
        helper.assertTrue(UiPrefs.THEME_DARK.equals(loaded.uiPrefs().theme()), "未知 theme 回退 dark");
        helper.assertTrue(loaded.uiPrefs().brandHue() == UiPrefs.DEFAULT_BRAND_HUE, "越界 brandHue 回退 250");
        helper.succeed();
    }

    /** 键都在、类型全错 (手改存档 / 跨版本换过字段类型): 全默认, 不抛。 */
    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void uiPrefsWrongTypesFallBackToDefault(GameTestHelper helper) {
        CompoundTag prefs = new CompoundTag();
        prefs.putString("muteToasts", "true");
        prefs.putInt("language", 7);
        prefs.putInt("theme", 3);
        prefs.putString("brandHue", "250");

        CompoundTag root = new MiningPlayerData().serializeNBT();
        root.put("uiPrefs", prefs);

        MiningPlayerData loaded = new MiningPlayerData();
        loaded.deserializeNBT(root);

        helper.assertTrue(UiPrefs.DEFAULT.equals(loaded.uiPrefs()), "类型全错回退整份 DEFAULT");
        helper.succeed();
    }

    /** 子标签存成非 Compound (最脏的一档): 仍回退, 不抛。 */
    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void uiPrefsSubTagWrongTypeFallsBackToDefault(GameTestHelper helper) {
        CompoundTag root = new MiningPlayerData().serializeNBT();
        root.putString("uiPrefs", "dark");

        MiningPlayerData loaded = new MiningPlayerData();
        loaded.deserializeNBT(root);

        helper.assertTrue(UiPrefs.DEFAULT.equals(loaded.uiPrefs()), "子标签类型错回退整份 DEFAULT");
        helper.succeed();
    }

    /** copyFrom 必须带上偏好 (死亡重生 / 换维度经 PlayerEvent.Clone 走这里)。 */
    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void uiPrefsSurviveCopyFrom(GameTestHelper helper) {
        MiningPlayerData from = new MiningPlayerData();
        from.setUiPrefs(new UiPrefs(true, "en_us", UiPrefs.THEME_LIGHT, UiPrefs.BRAND_HUE_MAX));

        MiningPlayerData to = new MiningPlayerData();
        to.copyFrom(from);

        helper.assertTrue(to.uiPrefs().muteToasts(), "muteToasts 跨 Clone 保留");
        helper.assertTrue("en_us".equals(to.uiPrefs().language()), "language 跨 Clone 保留");
        helper.assertTrue(UiPrefs.THEME_LIGHT.equals(to.uiPrefs().theme()), "theme 跨 Clone 保留");
        helper.assertTrue(to.uiPrefs().brandHue() == UiPrefs.BRAND_HUE_MAX, "brandHue 跨 Clone 保留");
        helper.succeed();
    }

    /**
     * 写路径逐字段拒绝非法值 (player.prefs.set 的服务端底线)。
     * 取值域端点 0 / 360 属合法, 一并锁住, 防止后人把边界写成开区间。
     */
    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void uiPrefsWritePathRejectsIllegalValues(GameTestHelper helper) {
        helper.assertTrue(throwsOnConstruct(false, UiPrefs.DEFAULT_LANGUAGE, UiPrefs.THEME_DARK, 361),
                "brandHue=361 越上界必须抛");
        helper.assertTrue(throwsOnConstruct(false, UiPrefs.DEFAULT_LANGUAGE, UiPrefs.THEME_DARK, -1),
                "brandHue=-1 越下界必须抛");
        helper.assertTrue(throwsOnConstruct(false, UiPrefs.DEFAULT_LANGUAGE, "compact", 250),
                "未知 theme 必须抛");
        helper.assertTrue(throwsOnConstruct(false, "", UiPrefs.THEME_DARK, 250),
                "空 language 必须抛");
        helper.assertTrue(throwsOnConstruct(false, "ZH_CN", UiPrefs.THEME_DARK, 250),
                "大写 language 必须抛 (取值域限定小写)");
        helper.assertTrue(throwsOnConstruct(false, "zh_cn_extra_long_x", UiPrefs.THEME_DARK, 250),
                "超 16 字符 language 必须抛");
        helper.assertTrue(throwsOnConstruct(false, null, UiPrefs.THEME_DARK, 250),
                "null language 必须抛");

        helper.assertTrue(!throwsOnConstruct(false, UiPrefs.DEFAULT_LANGUAGE, UiPrefs.THEME_DARK, UiPrefs.BRAND_HUE_MIN),
                "brandHue=0 是合法端点");
        helper.assertTrue(!throwsOnConstruct(true, "en_us", UiPrefs.THEME_LIGHT, UiPrefs.BRAND_HUE_MAX),
                "brandHue=360 是合法端点");
        helper.succeed();
    }

    private static boolean throwsOnConstruct(boolean muteToasts, String language, String theme, int brandHue) {
        try {
            new UiPrefs(muteToasts, language, theme, brandHue);
            return false;
        } catch (IllegalArgumentException expected) {
            return true;
        }
    }
}
