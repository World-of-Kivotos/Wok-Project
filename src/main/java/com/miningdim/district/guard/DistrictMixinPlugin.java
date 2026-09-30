package com.miningdim.district.guard;

import net.minecraftforge.fml.loading.FMLLoader;
import net.minecraftforge.fml.loading.LoadingModList;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

import java.util.List;
import java.util.Set;

/**
 * 自管区三份 mixin 配置共用的插件 (设计文档 22.1、22.7、22.20)。不能放进任何 mixin 包: Mixin 拒绝直接加载 mixin 包里的类。
 *
 * <ul>
 *   <li>{@link #shouldApplyMixin}: 机械动力包里的 mixin 只在 LoadingModList 里有 create 时应用 (这时 ModList 还没建好),
 *       Flan 包里的 (个人圈地限制) 只在有 flan 时应用; 原版包一律应用。</li>
 *   <li>{@link #postApply}: 数目标类里对各个处理方法的调用 ({@link MixinHandlerScan}), 把结论写进系统属性
 *       {@code miningdim.district.mixin.<mixin 简名>.<目标简名>}: 全织进去了为 {@code applied}, 否则为
 *       {@code partial: ...}。机械动力的配置 defaultRequire = 0, 注入点对不上时 Mixin 不抛错、只是什么都不织, postApply
 *       照样被调用, 所以不能只看它有没有被调用 (22.7)。插件不一定和游戏代码在同一个类加载器里, 走系统属性最稳; 开服核对
 *       ({@link GuardMixinStatus}) 读它。</li>
 * </ul>
 * 只碰 FMLLoader、ASM 与系统属性, 不加载任何 Minecraft 类。
 */
public final class DistrictMixinPlugin implements IMixinConfigPlugin {

    /** 机械动力 mixin 所在的包 (带结尾的点)。 */
    public static final String CREATE_MIXIN_PACKAGE = "com.miningdim.district.mixin.create.";

    /** 个人圈地限制的 Flan mixin 所在的包 (带结尾的点, 22.20)。 */
    public static final String FLAN_MIXIN_PACKAGE = "com.miningdim.district.mixin.flan.";

    /** Flan 的 modId (与 flan.real.FlanCompat.MOD_ID 相同; 插件不加载别的类, 这里自己写一份)。 */
    private static final String FLAN_MOD_ID = "flan";

    /** 系统属性的前缀。 */
    public static final String PROPERTY_PREFIX = "miningdim.district.mixin.";

    @Override
    public void onLoad(String mixinPackage) {
    }

    @Override
    public String getRefMapperConfig() {
        return null;
    }

    @Override
    public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
        if (mixinClassName.startsWith(CREATE_MIXIN_PACKAGE)) {
            return createPresent();
        }
        if (mixinClassName.startsWith(FLAN_MIXIN_PACKAGE)) {
            return flanPresent();
        }
        return true;
    }

    @Override
    public void acceptTargets(Set<String> myTargets, Set<String> otherTargets) {
    }

    @Override
    public List<String> getMixins() {
        return null;
    }

    @Override
    public void preApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
    }

    @Override
    public void postApply(String targetClassName, ClassNode targetClass, String mixinClassName,
                          IMixinInfo mixinInfo) {
        String verdict;
        try {
            verdict = MixinHandlerScan.verdict(mixinInfo.getClassNode(0), targetClass);
        } catch (RuntimeException | LinkageError failure) {
            // 数不了就不能说织进去了: 按没织全报, 开服核对会列出来。
            verdict = MixinHandlerScan.PARTIAL_PREFIX + "scan failed (" + failure + ")";
        }
        System.setProperty(propertyKey(simpleName(mixinClassName), simpleName(targetClassName)), verdict);
    }

    /** LoadingModList 里有没有 create (读不到时按没有)。 */
    static boolean createPresent() {
        return loading("create");
    }

    /** LoadingModList 里有没有 flan (读不到时按没有, 22.20)。 */
    static boolean flanPresent() {
        return loading(FLAN_MOD_ID);
    }

    private static boolean loading(String modId) {
        try {
            LoadingModList mods = FMLLoader.getLoadingModList();
            return mods != null && mods.getModFileById(modId) != null;
        } catch (RuntimeException | LinkageError unavailable) {
            return false;
        }
    }

    /** 系统属性的键。 */
    public static String propertyKey(String mixinSimpleName, String targetSimpleName) {
        return PROPERTY_PREFIX + mixinSimpleName + "." + targetSimpleName;
    }

    /** 点分或斜杠分隔的类名的简名 (内部类保留 $ 之后的部分之前的全名简名)。 */
    public static String simpleName(String className) {
        String dotted = className.replace('/', '.');
        return dotted.substring(dotted.lastIndexOf('.') + 1);
    }
}
