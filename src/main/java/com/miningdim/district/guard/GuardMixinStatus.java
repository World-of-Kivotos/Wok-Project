package com.miningdim.district.guard;

import com.miningdim.district.flan.real.FlanCompat;
import com.miningdim.district.flan.real.FlanHookTargets;
import com.miningdim.district.guard.create.CreateHookTargets;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.forgespi.language.IModInfo;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

/**
 * 记录并核对哪些守卫 mixin 真的应用上了 (设计文档 22.7、22.20)。机械动力与 Flan 的配置是可选的 (required = false) 且
 * {@code defaultRequire = 0}: 目标类或方法对不上时 Mixin 只记 WARN、停用那一个 mixin; 注入点 (INVOKE、@Redirect) 对不上
 * 时什么都不织, 也不抛 InjectionError (那是 Error, 不看 required, 会让服务端起不来)。为了不让它悄悄失效, 开服
 * (ServerStarted) 先按目标表逐个只加载、不初始化目标类 (还没被用到的目标类现在就经过变换, mixin 才有机会应用), 再读
 * {@link DistrictMixinPlugin} 写下的系统属性, 只有值为 {@code applied} (处理方法全都织进去了, {@link MixinHandlerScan})
 * 才算: 原版应有 6 条 (5 个 mixin, 发射器那个有两个目标), 装了机械动力时应有 {@link CreateHookTargets#HOOKS} 那么多条,
 * 装了 Flan 时个人圈地限制应有 {@link FlanHookTargets#HOOKS} 那么多条 (F1、F2)。
 * 不完整时开服记 ERROR, /district status 显示缺了哪些、各自覆盖什么, OP 上线收到一条红字。功能 OFF 时照样核对, 只记 INFO。
 */
public final class GuardMixinStatus {

    private static final Logger LOGGER = LoggerFactory.getLogger("miningdim/district");

    /** 这一版守卫核对过的机械动力版本。 */
    public static final String VERIFIED_CREATE_VERSION = "6.0.8";

    /** 个人圈地限制核对过的 Flan 版本 (与网关的自检是同一版)。 */
    public static final String VERIFIED_FLAN_VERSION = FlanCompat.VERIFIED_VERSION;

    /** 原版守卫的一个 (mixin, 目标)。 */
    public record WorldTarget(String mixin, String target) {

        public String targetSimpleName() {
            return DistrictMixinPlugin.simpleName(target);
        }
    }

    /** 原版守卫的 6 条 (miningdim.district.mixins.json, required)。 */
    public static final List<WorldTarget> WORLD_TARGETS = List.of(
            new WorldTarget("PistonStructureResolverMixin",
                    "net.minecraft.world.level.block.piston.PistonStructureResolver"),
            new WorldTarget("FlowingFluidMixin", "net.minecraft.world.level.material.FlowingFluid"),
            new WorldTarget("DispenserBlockMixin", "net.minecraft.world.level.block.DispenserBlock"),
            new WorldTarget("DispenserBlockMixin", "net.minecraft.world.level.block.DropperBlock"),
            new WorldTarget("FallingBlockEntityMixin", "net.minecraft.world.entity.item.FallingBlockEntity"),
            new WorldTarget("SpongeBlockMixin", "net.minecraft.world.level.block.SpongeBlock"));

    /**
     * 一次核对的结果。
     *
     * @param worldMissing    没应用上的原版目标 (mixin.目标)
     * @param createInstalled 装了机械动力
     * @param createVersion   机械动力的版本 (没装为 null)
     * @param createMissing   没应用上的机械动力注入点 (没装时为空)
     * @param flanInstalled   装了 Flan
     * @param flanVersion     Flan 的版本 (没装为 null)
     * @param flanMissing     没应用上的个人圈地限制注入点 (没装时为空)
     */
    public record Status(List<String> worldMissing, boolean createInstalled, @Nullable String createVersion,
                         List<CreateHookTargets.Hook> createMissing, boolean flanInstalled,
                         @Nullable String flanVersion, List<FlanHookTargets.Hook> flanMissing) {

        public Status {
            worldMissing = List.copyOf(worldMissing);
            createMissing = List.copyOf(createMissing);
            flanMissing = List.copyOf(flanMissing);
        }

        public int worldApplied() {
            return WORLD_TARGETS.size() - worldMissing.size();
        }

        public int createApplied() {
            return createInstalled ? CreateHookTargets.HOOKS.size() - createMissing.size() : 0;
        }

        /** 机械动力防护完整 (没装机械动力也算完整: 没有要防的)。 */
        public boolean createComplete() {
            return !createInstalled || createMissing.isEmpty();
        }

        /** 版本不是核对过的那一版。 */
        public boolean createVersionUnverified() {
            return createInstalled && !VERIFIED_CREATE_VERSION.equals(createVersion);
        }

        /** 缺的注入点编号, 如 "C1、C8"。 */
        public String missingIds() {
            List<String> ids = new ArrayList<>();
            createMissing.forEach(hook -> ids.add(hook.id()));
            return String.join("、", ids);
        }

        public int flanApplied() {
            return flanInstalled ? FlanHookTargets.HOOKS.size() - flanMissing.size() : 0;
        }

        /** 个人圈地限制完整 (没装 Flan 也算完整: 没有个人领地可拦)。 */
        public boolean flanComplete() {
            return !flanInstalled || flanMissing.isEmpty();
        }

        /** Flan 的版本不是核对过的那一版。 */
        public boolean flanVersionUnverified() {
            return flanInstalled && !VERIFIED_FLAN_VERSION.equals(flanVersion);
        }

        /** 缺的个人圈地限制注入点编号, 如 "F2"。 */
        public String flanMissingIds() {
            List<String> ids = new ArrayList<>();
            flanMissing.forEach(hook -> ids.add(hook.id()));
            return String.join("、", ids);
        }
    }

    @Nullable
    private static volatile Status last;

    private GuardMixinStatus() {
    }

    /** 上一次开服核对的结果; 还没核对过为 null。 */
    @Nullable
    public static Status last() {
        return last;
    }

    /** 系统属性里记着这个 (mixin, 目标简名) 应用上了, 而且处理方法全都织进去了。 */
    public static boolean applied(String key) {
        return MixinHandlerScan.APPLIED.equals(System.getProperty(key));
    }

    /** 没应用全的原因: 系统属性的原值 (没应用为 "not applied")。 */
    private static String detail(String key) {
        String value = System.getProperty(key);
        return value == null ? "not applied" : value;
    }

    /** 开服核对 (ServerStarted): 强制加载目标类, 数系统属性, 记日志。 */
    public static Status check(boolean featureOn) {
        ClassLoader loader = GuardMixinStatus.class.getClassLoader();
        for (WorldTarget target : WORLD_TARGETS) {
            load(target.target(), loader);
        }
        String version = createVersion();
        boolean installed = version != null;
        if (installed) {
            for (CreateHookTargets.Hook hook : CreateHookTargets.HOOKS) {
                load(hook.target(), loader);
            }
        }
        String flanVersion = flanVersion();
        boolean flanInstalled = flanVersion != null;
        if (flanInstalled) {
            for (FlanHookTargets.Hook hook : FlanHookTargets.HOOKS) {
                load(hook.target(), loader);
            }
        }
        Status status = evaluate(GuardMixinStatus::applied, installed, version, flanInstalled, flanVersion);
        last = status;
        report(status, featureOn);
        return status;
    }

    /** 只加载、不初始化 (触发变换); 找不到就算了 (没装机械动力或 Flan)。 */
    private static void load(String className, ClassLoader loader) {
        try {
            Class.forName(className, false, loader);
        } catch (ClassNotFoundException | LinkageError absent) {
            LOGGER.debug("[miningdim] district guard target {} is not loadable: {}", className, absent.toString());
        }
    }

    /** 按"哪些键记着"算一份结果, 当作没装 Flan (GameTest 用合成的集合核对机械动力一节)。 */
    public static Status evaluate(Predicate<String> appliedKeys, boolean createInstalled,
                                  @Nullable String createVersion) {
        return evaluate(appliedKeys, createInstalled, createVersion, false, null);
    }

    /** 按"哪些键记着"算一份结果 (GameTest 用合成的集合核对)。 */
    public static Status evaluate(Predicate<String> appliedKeys, boolean createInstalled,
                                  @Nullable String createVersion, boolean flanInstalled,
                                  @Nullable String flanVersion) {
        List<String> worldMissing = new ArrayList<>();
        for (WorldTarget target : WORLD_TARGETS) {
            if (!appliedKeys.test(DistrictMixinPlugin.propertyKey(target.mixin(), target.targetSimpleName()))) {
                worldMissing.add(target.mixin() + "." + target.targetSimpleName());
            }
        }
        List<CreateHookTargets.Hook> createMissing = new ArrayList<>();
        if (createInstalled) {
            for (CreateHookTargets.Hook hook : CreateHookTargets.HOOKS) {
                if (!appliedKeys.test(DistrictMixinPlugin.propertyKey(hook.mixin(), hook.targetSimpleName()))) {
                    createMissing.add(hook);
                }
            }
        }
        List<FlanHookTargets.Hook> flanMissing = new ArrayList<>();
        if (flanInstalled) {
            for (FlanHookTargets.Hook hook : FlanHookTargets.HOOKS) {
                if (!appliedKeys.test(DistrictMixinPlugin.propertyKey(hook.mixin(), hook.targetSimpleName()))) {
                    flanMissing.add(hook);
                }
            }
        }
        return new Status(worldMissing, createInstalled, createVersion, createMissing, flanInstalled, flanVersion,
                flanMissing);
    }

    private static void report(Status status, boolean featureOn) {
        reportWorldAndCreate(status, featureOn);
        reportFlan(status, featureOn);
    }

    private static void reportWorldAndCreate(Status status, boolean featureOn) {
        if (!status.worldMissing().isEmpty()) {
            List<String> details = new ArrayList<>();
            for (WorldTarget target : WORLD_TARGETS) {
                String key = DistrictMixinPlugin.propertyKey(target.mixin(), target.targetSimpleName());
                if (status.worldMissing().contains(target.mixin() + "." + target.targetSimpleName())) {
                    details.add(target.mixin() + "." + target.targetSimpleName() + " (" + detail(key) + ")");
                }
            }
            LOGGER.error("[miningdim] district plot-boundary guard mixins missing: {}", details);
        }
        if (!status.createInstalled()) {
            LOGGER.info("[miningdim] district guards: vanilla hooks {}/{}; Create is not installed",
                    status.worldApplied(), WORLD_TARGETS.size());
            return;
        }
        if (status.createVersionUnverified()) {
            LOGGER.warn("[miningdim] district guards: Create {} is not verified (hooks were checked against {}); "
                    + "trying the hooks anyway", status.createVersion(), VERIFIED_CREATE_VERSION);
        }
        if (status.createComplete()) {
            LOGGER.info("[miningdim] district guards: vanilla hooks {}/{}, Create hooks {}/{} (Create {})",
                    status.worldApplied(), WORLD_TARGETS.size(), status.createApplied(),
                    CreateHookTargets.HOOKS.size(), status.createVersion());
            return;
        }
        StringBuilder lines = new StringBuilder();
        for (CreateHookTargets.Hook hook : status.createMissing()) {
            lines.append("\n  ").append(hook.id()).append(' ').append(hook.mixin()).append(" -> ")
                    .append(hook.target()).append(": ").append(hook.covers()).append(" [")
                    .append(detail(DistrictMixinPlugin.propertyKey(hook.mixin(), hook.targetSimpleName())))
                    .append(']');
        }
        if (featureOn) {
            LOGGER.error("[miningdim] district Create protection is INCOMPLETE ({}/{} hooks applied, Create {}); "
                            + "missing:{}", status.createApplied(), CreateHookTargets.HOOKS.size(),
                    status.createVersion(), lines);
        } else {
            LOGGER.info("[miningdim] district Create protection would be incomplete ({}/{} hooks applied, Create {}; "
                            + "the district feature is off); missing:{}", status.createApplied(),
                    CreateHookTargets.HOOKS.size(), status.createVersion(), lines);
        }
    }

    /** 个人圈地限制一节 (22.20): 没装 Flan 只记 INFO; 不完整且功能开着记 ERROR, 列出缺的与各自覆盖什么。 */
    private static void reportFlan(Status status, boolean featureOn) {
        if (!status.flanInstalled()) {
            LOGGER.info("[miningdim] district personal claim limit: Flan is not installed (no personal claims to "
                    + "guard)");
            return;
        }
        if (status.flanVersionUnverified()) {
            LOGGER.warn("[miningdim] district personal claim limit: Flan {} is not verified (hooks were checked "
                    + "against {}); trying the hooks anyway", status.flanVersion(), VERIFIED_FLAN_VERSION);
        }
        if (status.flanComplete()) {
            LOGGER.info("[miningdim] district personal claim limit: Flan hooks {}/{} (Flan {})", status.flanApplied(),
                    FlanHookTargets.HOOKS.size(), status.flanVersion());
            return;
        }
        StringBuilder lines = new StringBuilder();
        for (FlanHookTargets.Hook hook : status.flanMissing()) {
            lines.append("\n  ").append(hook.id()).append(' ').append(hook.mixin()).append(" -> ")
                    .append(hook.target()).append('.').append(hook.methodName()).append(": ").append(hook.covers())
                    .append(" [").append(detail(DistrictMixinPlugin.propertyKey(hook.mixin(),
                            hook.targetSimpleName()))).append(']');
        }
        if (featureOn) {
            LOGGER.error("[miningdim] district personal claim limit is INCOMPLETE ({}/{} hooks applied, Flan {}); "
                            + "personal claims can be made inside districts and their buffer; missing:{}",
                    status.flanApplied(), FlanHookTargets.HOOKS.size(), status.flanVersion(), lines);
        } else {
            LOGGER.info("[miningdim] district personal claim limit would be incomplete ({}/{} hooks applied, Flan {}; "
                            + "the district feature is off); missing:{}", status.flanApplied(),
                    FlanHookTargets.HOOKS.size(), status.flanVersion(), lines);
        }
    }

    /** ModList 报告的机械动力版本; 没装为 null。 */
    @Nullable
    public static String createVersion() {
        return modVersion("create");
    }

    /** ModList 报告的 Flan 版本; 没装为 null。 */
    @Nullable
    public static String flanVersion() {
        return modVersion(FlanCompat.MOD_ID);
    }

    @Nullable
    private static String modVersion(String modId) {
        ModList mods = ModList.get();
        if (mods == null || !mods.isLoaded(modId)) {
            return null;
        }
        return mods.getModContainerById(modId)
                .map(container -> container.getModInfo())
                .map(IModInfo::getVersion)
                .map(Object::toString)
                .orElse("?");
    }
}
