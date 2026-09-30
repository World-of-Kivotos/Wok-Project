package com.miningdim.district.guard;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.miningdim.core.MiningConstants;
import com.miningdim.district.DistrictTestEnv;
import com.miningdim.district.flan.real.FlanCompat;
import com.miningdim.district.flan.real.FlanHookTargets;
import com.miningdim.district.guard.create.CreateHookTargets;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.util.GsonHelper;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * 守卫 mixin 的记录与核对 (设计文档 22.7、22.14、22.20): 原版的 6 条真的应用上了; 开发运行时没有机械动力; 个人圈地限制的
 * F1、F2 在有 Flan 的开发运行时里应用上了; 合成的"已应用"集合缺了几项时报得出来。
 */
@GameTestHolder(MiningConstants.MODID)
@PrefixGameTestTemplate(false)
public final class GuardStatusGameTests {

    private static final String EMPTY = "empty";
    private static final String BATCH = "district_guard_status";

    private GuardStatusGameTests() {
    }

    /** 强制加载目标类之后, 原版的 6 条都记着 (插件的 postApply 写的系统属性)。 */
    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void worldGuardMixinsAreApplied(GameTestHelper helper) {
        GuardMixinStatus.Status status = GuardMixinStatus.check(false);
        helper.assertTrue(status.worldMissing().isEmpty() && status.worldApplied() == 6,
                "原版守卫 6/6 应用上, 缺: " + status.worldMissing());
        for (GuardMixinStatus.WorldTarget target : GuardMixinStatus.WORLD_TARGETS) {
            String key = DistrictMixinPlugin.propertyKey(target.mixin(), target.targetSimpleName());
            helper.assertTrue(MixinHandlerScan.APPLIED.equals(System.getProperty(key)),
                    "系统属性 " + key + " 记着 applied (处理方法全都织进去了), 实为 " + System.getProperty(key));
        }
        helper.succeed();
    }

    /**
     * 织入核对 (22.7): 处理方法 (改名之后 handler$…$原名) 在目标类里被调到 expect 次 (没写为 1) 才算 applied, 少了报
     * partial。机械动力的配置 defaultRequire = 0, 注入点对不上时 Mixin 不抛错、只是不织, 全靠这里报出来。
     */
    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void handlerScanCountsWovenCalls(GameTestHelper helper) {
        ClassNode mixin = new ClassNode();
        mixin.methods.add(handler("miningdim$once", null));
        mixin.methods.add(handler("miningdim$twice", 2));
        MethodNode plain = new MethodNode(Opcodes.ACC_PRIVATE, "notAHandler", "()V", null, null);
        mixin.methods.add(plain);

        ClassNode target = new ClassNode();
        target.name = "gt/Target";
        MethodNode body = new MethodNode(Opcodes.ACC_PUBLIC, "tick", "()V", null, null);
        body.instructions.add(call("gt/Target", "handler$zza000$miningdim$once"));
        body.instructions.add(call("gt/Target", "redirect$zzb000$miningdim$twice"));
        body.instructions.add(call("gt/Other", "redirect$zzb000$miningdim$twice"));
        target.methods.add(body);
        String partial = MixinHandlerScan.verdict(mixin, target);
        helper.assertTrue(partial.startsWith(MixinHandlerScan.PARTIAL_PREFIX) && partial.contains("miningdim$twice 1/2")
                        && !partial.contains("miningdim$once"),
                "expect = 2 的只织进一处 (别的类的调用不算): partial, 实为 " + partial);

        body.instructions.add(call("gt/Target", "redirect$zzb000$miningdim$twice"));
        helper.assertTrue(MixinHandlerScan.APPLIED.equals(MixinHandlerScan.verdict(mixin, target)),
                "两处都织进去了: applied");
        ClassNode empty = new ClassNode();
        empty.name = "gt/Empty";
        helper.assertTrue(MixinHandlerScan.verdict(mixin, empty).contains("miningdim$once 0/1"),
                "一处都没织: partial");
        helper.succeed();
    }

    private static MethodNode handler(String name, Integer expect) {
        MethodNode method = new MethodNode(Opcodes.ACC_PRIVATE, name, "()V", null, null);
        AnnotationNode inject = new AnnotationNode("Lorg/spongepowered/asm/mixin/injection/Inject;");
        if (expect != null) {
            inject.visit("expect", expect);
        }
        method.visibleAnnotations = new ArrayList<>(List.of(inject));
        return method;
    }

    private static MethodInsnNode call(String owner, String name) {
        return new MethodInsnNode(Opcodes.INVOKESPECIAL, owner, name, "()V", false);
    }

    /** 开发运行时没有机械动力: 状态为"未安装机械动力", 机械动力防护算完整 (没有要防的)。 */
    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void statusReportsCreateAbsentInDev(GameTestHelper helper) {
        GuardMixinStatus.Status status = GuardMixinStatus.last();
        helper.assertTrue(status != null, "开服 (ServerStarted) 时核对过一次");
        boolean createLoaded = ModList.get().isLoaded("create");
        helper.assertTrue(status.createInstalled() == createLoaded, "装没装机械动力与 ModList 一致");
        if (!createLoaded) {
            helper.assertTrue(status.createComplete() && status.createMissing().isEmpty()
                            && status.createApplied() == 0 && status.createVersion() == null
                            && !status.createVersionUnverified(),
                    "没装机械动力: 不缺、不报版本, 实为 " + status);
            for (CreateHookTargets.Hook hook : CreateHookTargets.HOOKS) {
                String key = DistrictMixinPlugin.propertyKey(hook.mixin(), hook.targetSimpleName());
                helper.assertTrue(System.getProperty(key) == null, "没装机械动力时 " + key + " 不该应用");
            }
        }
        helper.succeed();
    }

    /**
     * 个人圈地限制的两个 mixin (22.20): 开发运行时有 Flan, F1、F2 的系统属性恰好是 applied, 状态 2/2; -PwithoutFlan 那一轮
     * 两个都不应用, 状态是"未安装 Flan"、算完整 (没有个人领地可拦)。
     */
    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void flanClaimMixinsAreApplied(GameTestHelper helper) {
        GuardMixinStatus.Status status = GuardMixinStatus.check(false);
        boolean flanLoaded = ModList.get().isLoaded(FlanCompat.MOD_ID);
        helper.assertTrue(status.flanInstalled() == flanLoaded, "装没装 Flan 与 ModList 一致");
        for (FlanHookTargets.Hook hook : FlanHookTargets.HOOKS) {
            String key = DistrictMixinPlugin.propertyKey(hook.mixin(), hook.targetSimpleName());
            if (flanLoaded) {
                helper.assertTrue(MixinHandlerScan.APPLIED.equals(System.getProperty(key)),
                        hook.id() + " 的系统属性 " + key + " 记着 applied, 实为 " + System.getProperty(key));
            } else {
                helper.assertTrue(System.getProperty(key) == null, "没装 Flan 时 " + key + " 不该应用");
            }
        }
        if (flanLoaded) {
            helper.assertTrue(status.flanComplete() && status.flanApplied() == 2 && status.flanMissing().isEmpty()
                            && FlanCompat.VERIFIED_VERSION.equals(status.flanVersion())
                            && !status.flanVersionUnverified(),
                    "开发运行时的 Flan (核对过的那一版): 个人圈地限制 2/2, 实为 " + status);
        } else {
            helper.assertTrue(DistrictTestEnv.withoutFlanRun(), "开发运行时一定有 Flan, 除非是 -PwithoutFlan 那一轮");
            helper.assertTrue(status.flanComplete() && status.flanApplied() == 0 && status.flanVersion() == null,
                    "没装 Flan: 算完整, 不报版本, 实为 " + status);
        }
        helper.succeed();
    }

    /** 合成的"已应用"集合缺 F2: 状态列出 F2 与它覆盖什么; Flan 版本不是核对过的那一版时报"未经核对"。 */
    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void statusFlagsMissingFlanHooks(GameTestHelper helper) {
        FlanHookTargets.Hook f2 = FlanHookTargets.HOOKS.get(1);
        String f2Key = DistrictMixinPlugin.propertyKey(f2.mixin(), f2.targetSimpleName());
        GuardMixinStatus.Status status = GuardMixinStatus.evaluate(key -> !key.equals(f2Key), false, null, true,
                FlanCompat.VERIFIED_VERSION);
        helper.assertTrue(!status.flanComplete() && status.flanApplied() == 1 && "F2".equals(status.flanMissingIds())
                        && status.flanMissing().equals(List.of(f2)) && !f2.covers().isBlank()
                        && !status.flanVersionUnverified() && status.createComplete()
                        && status.worldMissing().isEmpty(),
                "缺 F2: 1/2, 列出 F2 与它覆盖什么, 实为 " + status);
        GuardMixinStatus.Status newer = GuardMixinStatus.evaluate(key -> true, false, null, true, "1.20.1-1.11.17");
        helper.assertTrue(newer.flanComplete() && newer.flanApplied() == 2 && newer.flanVersionUnverified(),
                "版本不符记\"未经核对\", 照常尝试注入");
        GuardMixinStatus.Status absent = GuardMixinStatus.evaluate(key -> false, false, null, false, null);
        helper.assertTrue(absent.flanComplete() && absent.flanApplied() == 0 && absent.flanMissing().isEmpty(),
                "没装 Flan: 不缺");
        helper.assertTrue(FlanHookTargets.HOOKS.stream().map(FlanHookTargets.Hook::id).toList()
                        .equals(List.of("F1", "F2")),
                "注入点按 F1、F2 排");
        helper.succeed();
    }

    /**
     * miningdim.district.flan.mixins.json 恰好 2 个 mixin, 与 FlanHookTargets.HOOKS 一一对应 (目标类、注入的方法); 可选
     * (required = false)、defaultRequire = 0, 注解里没有 require (22.7 的教训: require 不满足会抛 Error, 可选配置也崩服)。
     */
    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void flanHookTableMatchesTheMixinConfig(GameTestHelper helper) throws IOException {
        JsonObject config = readJson("miningdim.district.flan.mixins.json");
        List<String> listed = new ArrayList<>();
        for (JsonElement element : GsonHelper.getAsJsonArray(config, "mixins")) {
            listed.add(element.getAsString());
        }
        helper.assertTrue(listed.equals(FlanHookTargets.HOOKS.stream().map(FlanHookTargets.Hook::mixin).toList()),
                "配置的列表与注入点表一一对应, 实为 " + listed);
        helper.assertTrue(!GsonHelper.getAsBoolean(config, "required")
                        && "com.miningdim.district.mixin.flan".equals(GsonHelper.getAsString(config, "package"))
                        && DistrictMixinPlugin.class.getName().equals(GsonHelper.getAsString(config, "plugin"))
                        && DistrictMixinPlugin.FLAN_MIXIN_PACKAGE.equals(GsonHelper.getAsString(config, "package") + ".")
                        && GsonHelper.getAsInt(GsonHelper.getAsJsonObject(config, "injectors"), "defaultRequire") == 0,
                "Flan 的配置是可选的, defaultRequire = 0, 包与插件对得上");
        for (FlanHookTargets.Hook hook : FlanHookTargets.HOOKS) {
            ClassNode node = readClass("com/miningdim/district/mixin/flan/" + hook.mixin());
            helper.assertTrue(node != null, "读得到 mixin 类 " + hook.mixin());
            AnnotationNode mixin = annotation(node.invisibleAnnotations, MIXIN);
            if (mixin == null) {
                mixin = annotation(node.visibleAnnotations, MIXIN);
            }
            helper.assertTrue(mixin != null && List.of(hook.target()).equals(annotationValue(mixin, "targets")),
                    hook.mixin() + " 的 @Mixin 目标是 " + hook.target());
            int handlers = 0;
            for (MethodNode method : node.methods) {
                AnnotationNode inject = annotation(method.visibleAnnotations, INJECT);
                if (inject == null) {
                    continue;
                }
                handlers++;
                helper.assertTrue(List.of(hook.method()).equals(annotationValue(inject, "method"))
                                && annotationValue(inject, "require") == null
                                && method.name.startsWith("miningdim$"),
                        hook.mixin() + "." + method.name + ": 注入 " + hook.method() + ", 不写 require");
            }
            helper.assertTrue(handlers == 1, hook.mixin() + " 恰好一个处理方法, 实为 " + handlers);
        }
        helper.succeed();
    }

    private static final String MIXIN = "Lorg/spongepowered/asm/mixin/Mixin;";
    private static final String INJECT = "Lorg/spongepowered/asm/mixin/injection/Inject;";

    private static AnnotationNode annotation(List<AnnotationNode> annotations, String desc) {
        if (annotations == null) {
            return null;
        }
        for (AnnotationNode annotation : annotations) {
            if (annotation.desc.equals(desc)) {
                return annotation;
            }
        }
        return null;
    }

    private static Object annotationValue(AnnotationNode annotation, String name) {
        if (annotation.values == null) {
            return null;
        }
        for (int i = 0; i + 1 < annotation.values.size(); i += 2) {
            if (name.equals(annotation.values.get(i))) {
                return annotation.values.get(i + 1);
            }
        }
        return null;
    }

    private static ClassNode readClass(String internalName) throws IOException {
        try (InputStream in = GuardStatusGameTests.class.getClassLoader().getResourceAsStream(internalName + ".class")) {
            if (in == null) {
                return null;
            }
            ClassNode node = new ClassNode();
            new ClassReader(in.readAllBytes()).accept(node, ClassReader.SKIP_FRAMES);
            return node;
        }
    }

    private static JsonObject readJson(String path) throws IOException {
        InputStream stream = GuardStatusGameTests.class.getClassLoader().getResourceAsStream(path);
        if (stream == null) {
            throw new AssertionError("运行时 classpath 找不到资源: " + path);
        }
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            return GsonHelper.parse(reader);
        }
    }

    /** 合成的"已应用"集合缺 C1、C8: 状态列出这两项与各自覆盖什么; 版本不是 6.0.8 时报"未经核对"。 */
    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void statusFlagsIncompleteCreateHooks(GameTestHelper helper) {
        Set<String> missing = Set.of("C1", "C8");
        GuardMixinStatus.Status status = GuardMixinStatus.evaluate(key -> CreateHookTargets.HOOKS.stream()
                        .filter(hook -> missing.contains(hook.id()))
                        .noneMatch(hook -> DistrictMixinPlugin.propertyKey(hook.mixin(), hook.targetSimpleName())
                                .equals(key)),
                true, "6.0.8");
        helper.assertTrue(!status.createComplete() && status.createApplied() == CreateHookTargets.HOOKS.size() - 2
                        && "C1、C8".equals(status.missingIds()) && !status.createVersionUnverified()
                        && status.worldMissing().isEmpty(),
                "缺 C1、C8, 17/19, 实为 " + status.missingIds() + " " + status.createApplied());
        helper.assertTrue(status.createMissing().stream().allMatch(hook -> !hook.covers().isBlank()),
                "各自覆盖什么写得出来");
        GuardMixinStatus.Status complete = GuardMixinStatus.evaluate(key -> true, true, "6.0.9");
        helper.assertTrue(complete.createComplete() && complete.createApplied() == CreateHookTargets.HOOKS.size()
                && complete.createVersionUnverified(), "版本不符记\"未经核对\", 照常尝试注入");
        GuardMixinStatus.Status absent = GuardMixinStatus.evaluate(key -> false, false, null);
        helper.assertTrue(absent.createComplete() && absent.worldApplied() == 0 && absent.worldMissing().size() == 6,
                "没装机械动力时只看原版的 6 条");
        helper.assertTrue(List.copyOf(CreateHookTargets.HOOKS).stream().map(CreateHookTargets.Hook::id).toList()
                        .equals(List.of("C1", "C2", "C3", "C4", "C5", "C6", "C7", "C8", "C9", "C10", "C11", "C12",
                                "C13", "C14", "C15", "C16", "C17", "C18", "C19")),
                "注入点按 C1 ~ C19 排");
        helper.succeed();
    }
}
