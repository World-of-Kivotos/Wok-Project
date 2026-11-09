package com.miningdim.freshness;

import net.minecraftforge.fml.loading.FMLLoader;
import net.minecraftforge.fml.loading.LoadingModList;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

import java.util.List;
import java.util.Set;

/**
 * 可选依赖兼容 mixin 的插件 (Spoilage_Granary_DesignSpec「可选配置（第三方目标）」)。
 *
 * <p>两件事:</p>
 * <ol>
 *   <li>{@link #shouldApplyMixin}: 只在 LoadingModList 里有 {@code farmersdelight} 时应用 FD 那批 mixin。
 *       没装 FD 的正式服连"目标类找不到"这条 WARN 都不会出现 —— 整批跳过, 与 district 的机械动力/Flan
 *       两批同一个做法。</li>
 *   <li>{@link #postApply}: 核对本次 mixin 每个注入处理器是否真的被目标方法调用 (只拷入方法不算织入),
 *       把结论写进系统属性 {@code miningdim.freshness.compat.<mixin 简名>}。可选配置是
 *       {@code injectors.defaultRequire = 0}, 注入点对不上时 Mixin 不抛错、只是什么都没织 —— 不看这一眼,
 *       换了 FD 版本之后"继承悄悄失效"会被当成正常。开服核对与用例读它
 *       ({@link FreshnessCompatStatus})。</li>
 * </ol>
 *
 * <p>这个类**不能放进 mixin 包**: Mixin 拒绝直接加载 mixin 包里的类。只碰 FML 的 loading 包、ASM 与系统
 * 属性, 不加载任何 Minecraft 类。</p>
 */
public final class FreshnessCompatMixinPlugin implements IMixinConfigPlugin {

    /** FD 的 modId。 */
    private static final String FD_MOD_ID = "farmersdelight";

    /** 系统属性前缀 (后面接 mixin 的简名)。 */
    public static final String PROPERTY_PREFIX = "miningdim.freshness.compat.";

    /** 织进去了。 */
    public static final String APPLIED = "applied";

    /** 目标类加载了, 但我们的处理方法一处也没进去 (换版本了 / 签名对不上)。 */
    public static final String MISSING = "missing";

    /** 我们织进目标类的方法名前缀 (见各 mixin 里的 {@code miningdim$...})。 */
    public static final String HANDLER_PREFIX = "miningdim$";

    @Override
    public void onLoad(String mixinPackage) {
    }

    @Override
    public String getRefMapperConfig() {
        return null;
    }

    @Override
    public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
        return farmersDelightPresent();
    }

    @Override
    public void acceptTargets(Set<String> myTargets, Set<String> otherTargets) {
    }

    @Override
    public List<String> getMixins() {
        return null;
    }

    @Override
    public void preApply(String targetClassName, ClassNode targetClass, String mixinClassName,
                        IMixinInfo mixinInfo) {
    }

    @Override
    public void postApply(String targetClassName, ClassNode targetClass, String mixinClassName,
                         IMixinInfo mixinInfo) {
        List<MethodNode> handlers = mixinInfo.getClassNode(0).methods.stream()
                .filter(method -> method.name.contains(HANDLER_PREFIX))
                .filter(FreshnessCompatMixinPlugin::isInjector).toList();
        // Mixin 会拷贝 handler, 即使可选注入匹配数为零; 必须核对本次 mixin 每个 handler 的调用点。
        boolean woven = !handlers.isEmpty() && handlers.stream().allMatch(handler ->
                targetClass.methods.stream().anyMatch(method -> {
                    for (var instruction : method.instructions) {
                        if (instruction instanceof MethodInsnNode call && call.owner.equals(targetClass.name)
                                && (call.name.equals(handler.name) || call.name.endsWith("$" + handler.name))
                                && call.desc.equals(handler.desc)) {
                            return true;
                        }
                    }
                    return false;
                }));
        System.setProperty(PROPERTY_PREFIX + simpleName(mixinInfo.getName()),
                woven ? APPLIED : MISSING);
    }

    private static boolean isInjector(MethodNode method) {
        return hasInjector(method.visibleAnnotations) || hasInjector(method.invisibleAnnotations);
    }

    private static boolean hasInjector(List<AnnotationNode> annotations) {
        return annotations != null && annotations.stream().anyMatch(annotation ->
                annotation.desc.startsWith("Lorg/spongepowered/asm/mixin/injection/")
                        && !annotation.desc.endsWith("/At;"));
    }

    private static String simpleName(String className) {
        int dot = className.lastIndexOf('.');
        return dot < 0 ? className : className.substring(dot + 1);
    }

    /** FD 在不在 (LoadingModList 是加载期可用的那一个; 插件跑在 ModList 建好之前)。 */
    private static boolean farmersDelightPresent() {
        try {
            LoadingModList loading = FMLLoader.getLoadingModList();
            return loading != null && loading.getModFileById(FD_MOD_ID) != null;
        } catch (Throwable ignored) {
            // 插件不一定和游戏代码在同一个类加载器里: 拿不到就按"没装"处理 (宁可不织, 不要崩)。
            return false;
        }
    }
}
