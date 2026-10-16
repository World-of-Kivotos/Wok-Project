package com.miningdim.freshness;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimplePreparableReloadListener;
import net.minecraft.util.profiling.ProfilerFiller;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeManager;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraftforge.event.AddReloadListenerEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.util.ObfuscationReflectionHelper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Field;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 配方过滤的接线: 在每次数据包 reload 的 **apply 阶段**扫一遍合成配方并删掉「压缩块拆回」那些
 * (规格「压缩方块与可逆转换」规则 1: 开服时和每次 {@code /reload} 后都要跑)。
 *
 * 为什么挂在 reload 监听器上: Forge 把事件监听器追加在原版监听器**之后**, 而 {@code SimpleReloadInstance}
 * 是"并行准备、顺序 apply" —— 于是本监听器的 apply 一定跑在 {@code RecipeManager} 的 apply 之后、玩家收到
 * 配方同步包之前。客户端与 JEI 从一开始看到的就是删过之后的表, 不存在"先显示、后消失"的闪烁;
 * {@code /reload} 会重跑整条链, 所以"每次 reload 后"是免费的。
 *
 * 为什么用反射读那张私有表而不是写 mixin: FG3 的 mixin 注解处理器按 searge 表解析字段目标, 对
 * {@code RecipeManager.byType} 解析不出来 (访问器与 {@code @Shadow} 都试过, 编译期/运行期都过不去);
 * Forge 自己的 {@link ObfuscationReflectionHelper} 正是为"按 SRG 名取字段、开发期自动换回官方名"准备的。
 * 取不到时**大声报错并跳过** (而不是崩服): 字段名在 1.20.1 是锁定的, 真取不到说明有人动了原版结构,
 * 那时该做的是重新核对而不是让服务器起不来 —— 用例里有端到端的断言盯着这一步有没有真的生效。
 */
public final class FreshnessRecipeFilterHooks extends SimplePreparableReloadListener<Void> {

    private static final String NAME = "miningdim_freshness_recipe_filter";

    private static final Logger LOGGER = LoggerFactory.getLogger("miningdim/freshness");

    /** {@code RecipeManager.byType} 的 SRG 名 (1.20.1)。 */
    private static final String FIELD_BY_TYPE = "f_44007_";

    /** {@code RecipeManager.byName} 的 SRG 名 (1.20.1): 按 id 查表用的那一张, 也得一起删干净。 */
    private static final String FIELD_BY_NAME = "f_199900_";

    private static Field byTypeField;
    private static Field byNameField;
    private static boolean lookupFailed;

    @Override
    protected Void prepare(ResourceManager resourceManager, ProfilerFiller profiler) {
        return null; // 准备阶段没有可并行做的事: 判定要读配方表本身
    }

    /**
     * reload 的 apply 阶段: 配置已就绪时直接过滤。
     *
     * **初次加载要跳过**: 服务端数据包链在 SERVER 配置就绪**之前**就跑完了 (Forge 在读配置前会直接抛
     * "Cannot get config value before config is loaded"), 那一次交给 {@link #onServerStarted} —— 它在配置
     * 就绪之后、玩家进服之前跑, 于是"开服时"这一半照样覆盖到。{@code /reload} 发生在运行期, 配置必然是
     * 就绪的, 所以"每次 reload 后"也照样覆盖到。
     */
    @Override
    protected void apply(Void unused, ResourceManager resourceManager, ProfilerFiller profiler) {
        if (!FreshnessConfig.SPEC.isLoaded()) {
            return;
        }
        if (!FreshnessConfig.RECIPE_FILTER_ENABLED.get()) {
            return;
        }
        MinecraftServer server = net.minecraftforge.server.ServerLifecycleHooks.getCurrentServer();
        if (server == null) {
            return; // 数据生成等场景没有服务器: 跳过 (客户端不删配方, 它跟着服务端同步)
        }
        run(server.getRecipeManager(), FreshnessConfig.RECIPE_FILTER_WHITELIST.get());
    }

    /** 开服那一次 (初次数据包加载跑在配置就绪之前, 见 {@link #apply})。 */
    @SubscribeEvent
    public static void onServerStarted(net.minecraftforge.event.server.ServerStartedEvent event) {
        if (!FreshnessConfig.RECIPE_FILTER_ENABLED.get()) {
            return;
        }
        run(event.getServer().getRecipeManager(), FreshnessConfig.RECIPE_FILTER_WHITELIST.get());
    }

    /**
     * 跑一遍过滤 (用例可直接调)。
     *
     * 管理器内部有两张表: {@code byType} (按类型分组, 外层可变、子表不可变) 与 {@code byName} (按 id 查)。
     * 两张都要处理 —— 只看一张的话, 不是"合成格还能用"就是"JEI 里还看得见"。
     *
     * @return 删掉了几条
     */
    static int run(RecipeManager manager, List<? extends String> whitelist) {
        if (manager == null || lookupFailed) {
            return 0;
        }
        try {
            Map<RecipeType<?>, Map<ResourceLocation, Recipe<?>>> byType = liveTable(manager, true);
            if (byType == null) {
                return 0;
            }
            Map<ResourceLocation, Recipe<?>> crafting = byType.get(RecipeType.CRAFTING);
            List<ResourceLocation> removed =
                    FreshnessRecipeFilter.collectRemovals(crafting, FreshnessRecipeFilter.parseWhitelist(whitelist));
            if (removed.isEmpty()) {
                return 0;
            }
            /*
             * 两张表整体换成"筛过一遍"的可变副本, 而不是就地删:
             * 1.20.1 的表是 ImmutableMap (就地 put/remove 直接抛 UnsupportedOperationException, 第一次做这
             * 件事时就是这么挂的); 而 Forge 把这两个字段的 final 去掉了 (javap 可见), 因此可以整体换掉。
             * 换掉之后两张表仍然一致 (byType 决定客户端收到什么、byName 决定按 id 查得到什么)。
             */
            Map<RecipeType<?>, Map<ResourceLocation, Recipe<?>>> rebuiltByType = new LinkedHashMap<>(byType);
            rebuiltByType.put(RecipeType.CRAFTING,
                    FreshnessRecipeFilter.rebuildWithout(crafting, removed));
            byTypeField.set(manager, rebuiltByType);

            Map<ResourceLocation, Recipe<?>> byName = liveTable(manager, false);
            if (byName != null) {
                byNameField.set(manager, FreshnessRecipeFilter.rebuildWithout(byName, removed));
            }
            FreshnessRecipeFilter.logRemoved(removed);
            return removed.size();
        } catch (ReflectiveOperationException | RuntimeException unavailable) {
            lookupFailed = true;
            LOGGER.error("[miningdim] freshness recipe filter: 改不动 RecipeManager 的配方表 "
                            + "(字段 {} / {}), 压缩块拆回的配方这一版不会被删; 请核对原版结构后修这一处",
                    FIELD_BY_TYPE, FIELD_BY_NAME, unavailable);
            return 0;
        }
    }

    /** 取管理器内部的一张表 (byType=true 取 byType, false 取 byName); 取不到返回 null (已记日志)。 */
    @SuppressWarnings("unchecked")
    private static <T> T liveTable(RecipeManager manager, boolean byType) throws ReflectiveOperationException {
        if (byType) {
            if (byTypeField == null) {
                byTypeField = ObfuscationReflectionHelper.findField(RecipeManager.class, FIELD_BY_TYPE);
                byTypeField.setAccessible(true);
            }
            return (T) byTypeField.get(manager);
        }
        if (byNameField == null) {
            byNameField = ObfuscationReflectionHelper.findField(RecipeManager.class, FIELD_BY_NAME);
            byNameField.setAccessible(true);
        }
        return (T) byNameField.get(manager);
    }

    @Override
    public String getName() {
        return NAME;
    }

    @SubscribeEvent
    public static void onAddReloadListener(AddReloadListenerEvent event) {
        event.addListener(new FreshnessRecipeFilterHooks());
    }

    /** 仅供用例: 忘掉缓存过的字段与失败标记。 */
    static void resetForTest() {
        byTypeField = null;
        byNameField = null;
        lookupFailed = false;
    }
}