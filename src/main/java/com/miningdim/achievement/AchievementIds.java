package com.miningdim.achievement;

import com.miningdim.core.MiningConstants;
import net.minecraft.resources.ResourceLocation;

import java.util.List;

/**
 * 成就进度 id 的口径 (Achievement_System_DesignSpec 第五章、6.5): 哪些进度归本模块管、哪些是页签根、哪些计入成就数量,
 * 以及文案键的拼法。一致性校验、成就数量候选列表与 datagen 共用这一处判据, 不各写一份。
 */
public final class AchievementIds {

    /** 六个页签, 顺序即 L 键界面里的页签顺序 (第五章)。 */
    public static final List<String> TABS = List.of("mining", "combat", "profession", "economy", "social", "meta");

    /** 每个页签根进度的名称: {@code miningdim:<页签>/root}。 */
    public static final String ROOT_NAME = "root";

    /** 以成就数量为条件的页签; 它自己的成就不计入成就数量 (6.5)。 */
    public static final String META_TAB = "meta";

    /** 原版配方解锁进度的路径前缀。miningdim 命名空间下的配方进度 (电力模块 datagen 产出) 不是成就。 */
    private static final String RECIPE_PREFIX = "recipes/";

    private static final String LANG_PREFIX = "achievement." + MiningConstants.MODID + ".";

    private AchievementIds() {
    }

    /** {@code miningdim:<path>}。 */
    public static ResourceLocation id(String path) {
        return new ResourceLocation(MiningConstants.MODID, path);
    }

    /** 某页签的根进度 id。 */
    public static ResourceLocation root(String tab) {
        return id(tab + "/" + ROOT_NAME);
    }

    /**
     * 是否归本模块管的进度: miningdim 命名空间下、配方进度以外的全部进度。一致性校验要求它们每一条都有元数据
     * (4.1), 所以这里刻意不按页签白名单筛 —— 别处误放进 miningdim 命名空间的进度也该被校验报出来。
     */
    public static boolean isAchievement(ResourceLocation id) {
        return MiningConstants.MODID.equals(id.getNamespace()) && !id.getPath().startsWith(RECIPE_PREFIX);
    }

    /** 是否为页签根进度 ({@code <页签>/root})。 */
    public static boolean isRoot(ResourceLocation id) {
        String path = id.getPath();
        int slash = path.indexOf('/');
        return isAchievement(id) && slash > 0 && slash == path.lastIndexOf('/')
                && path.substring(slash + 1).equals(ROOT_NAME);
    }

    /** 是否计入成就数量 (6.5): 本模块的进度, 不含各页签根与 meta 页签自己的成就, 含隐藏成就。 */
    public static boolean isCountable(ResourceLocation id) {
        return isAchievement(id) && !isRoot(id) && !id.getPath().startsWith(META_TAB + "/");
    }

    /** 标题翻译键: {@code achievement.miningdim.<页签>.<名称>.title}。 */
    public static String titleKey(ResourceLocation id) {
        return LANG_PREFIX + id.getPath().replace('/', '.') + ".title";
    }

    /** 描述翻译键: {@code achievement.miningdim.<页签>.<名称>.desc}。 */
    public static String descriptionKey(ResourceLocation id) {
        return LANG_PREFIX + id.getPath().replace('/', '.') + ".desc";
    }
}
