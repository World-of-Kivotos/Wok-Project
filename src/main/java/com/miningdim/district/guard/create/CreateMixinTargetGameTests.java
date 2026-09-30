package com.miningdim.district.guard.create;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.miningdim.core.MiningConstants;
import com.miningdim.district.guard.DistrictMixinPlugin;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.util.GsonHelper;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * 机械动力注入点的离线核对 (设计文档 22.14): 机械动力不进开发运行时, 注入点靠这里对整合包里的那个 jar 核对。
 *
 * <ul>
 *   <li>{@link #everyHookTargetExistsInTheServerJar}: 只在带 {@code -PcreateJar=<路径>} 时跑 (gameTestServer 设系统属性
 *       {@value #CREATE_JAR_PROPERTY}), 否则按通过算, 但记一条 WARN (什么都没核对)。用运行时自带的 ASM 读那个 jar:
 *       {@link CreateHookTargets} 里每一项的目标类存在; 方法名与描述符存在; 影子字段存在且类型相符; INVOKE 目标确实出现在
 *       该方法的指令里, 次数相符; 反射读写的字段与方法都在。只读, 不复制、不进构建。</li>
 *   <li>{@link #handlerSignaturesMatchTheServerJar}: 同样只在带 {@code -PcreateJar} 时跑 (没带时同样记 WARN): 读 classpath
 *       上编译好的 mixin 类, 按 Mixin 的规则核对每个处理方法的签名、静态与否、{@code @Coerce} 与影子成员 (开发运行时里这些
 *       mixin 不会应用, Mixin 自己的核对到正式服才发生)。</li>
 *   <li>{@link #hookTableMatchesTheMixinConfig}: 不需要 jar: 可选配置的列表恰好 {@value #HOOK_COUNT} 个, 与表一一对应;
 *       {@code injectors.defaultRequire} 是 0, 注解里没有 {@code require} (22.7)。</li>
 * </ul>
 */
@GameTestHolder(MiningConstants.MODID)
@PrefixGameTestTemplate(false)
public final class CreateMixinTargetGameTests {

    public static final String CREATE_JAR_PROPERTY = "miningdim.district.createJar";

    /** 注入点的个数 (C1 ~ C19)。 */
    public static final int HOOK_COUNT = 19;

    private static final String EMPTY = "empty";
    private static final String BATCH = "district_create_offline";

    private static final Logger LOGGER = LoggerFactory.getLogger("miningdim/district");

    private CreateMixinTargetGameTests() {
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void everyHookTargetExistsInTheServerJar(GameTestHelper helper) throws IOException {
        String path = System.getProperty(CREATE_JAR_PROPERTY);
        if (path == null || path.isBlank()) {
            warnSkipped("everyHookTargetExistsInTheServerJar");
            helper.succeed();
            return;
        }
        List<String> problems = new ArrayList<>();
        try (ZipFile jar = new ZipFile(path)) {
            for (CreateHookTargets.Hook hook : CreateHookTargets.HOOKS) {
                ClassNode node = read(jar, CreateHookTargets.internal(hook.target()));
                if (node == null) {
                    problems.add(hook.id() + ": 目标类 " + hook.target() + " 不存在");
                    continue;
                }
                for (CreateHookTargets.Member member : hook.methods()) {
                    if (method(node, member.name(), member.desc()) == null) {
                        problems.add(hook.id() + ": 方法 " + member.name() + member.desc() + " 不存在");
                    }
                }
                for (CreateHookTargets.Invoke invoke : hook.invokes()) {
                    MethodNode in = method(node, invoke.in().name(), invoke.in().desc());
                    int count = in == null ? -1 : countInvokes(in, invoke.owner(), invoke.name(), invoke.desc());
                    if (count != invoke.count()) {
                        problems.add(hook.id() + ": " + invoke.owner() + "." + invoke.name() + invoke.desc() + " 在 "
                                + invoke.in().name() + " 里出现 " + count + " 次, 应为 " + invoke.count());
                    }
                }
                for (CreateHookTargets.Field field : hook.shadows()) {
                    checkField(jar, field, hook.id() + " 影子字段", problems);
                }
            }
            for (CreateHookTargets.Field field : CreateHookTargets.REFLECTED_FIELDS) {
                checkField(jar, field, "反射字段", problems);
            }
            for (CreateHookTargets.Invoke method : CreateHookTargets.REFLECTED_METHODS) {
                ClassNode node = read(jar, method.owner());
                if (node == null || method(node, method.name(), method.desc()) == null) {
                    problems.add("反射方法 " + method.owner() + "." + method.name() + method.desc() + " 不存在");
                }
            }
            if (read(jar, CreateHookTargets.internal(CreateBlockPolicy.KINETIC_INTERFACE)) == null) {
                problems.add("接口 " + CreateBlockPolicy.KINETIC_INTERFACE + " 不存在");
            }
        }
        LOGGER.info("[miningdim] district Create offline check: {} hook(s) against {}: {} problem(s)",
                CreateHookTargets.HOOKS.size(), path, problems.size());
        helper.assertTrue(problems.isEmpty(), "机械动力 jar " + path + " 与注入点表对不上: " + problems);
        helper.succeed();
    }

    /**
     * 处理方法的签名与 jar 里的目标对得上 (同样只在带 {@code -PcreateJar} 时跑)。Mixin 在应用时才核对这些, 而开发运行时没有
     * 机械动力, 可选配置里的 mixin 一个都不会应用, 签名写错只会在正式服上变成一条 WARN 并停用那个 mixin; 所以按 Mixin 0.8.5
     * 的规则在这里先核对一遍:
     * <ul>
     *   <li>{@code @Inject}: 处理方法的参数 = 目标方法的参数 + CallbackInfo (目标返回 void) 或 CallbackInfoReturnable;
     *       返回 void; 静态与否与目标方法相同。</li>
     *   <li>{@code @Redirect} (INVOKE): 参数 = 被调方法的接收者 (不是静态调用时) + 被调方法的参数, 后面可以再接目标方法参数
     *       的前缀 (C16); 返回类型相同; 静态与否与所在的目标方法相同。</li>
     *   <li>参数类型不同的地方只能是处理方法写成 Object 且标了 {@code @Coerce} (机械动力的类型没法在编译期引用)。</li>
     *   <li>{@code @Shadow} 的字段与方法在目标类里有同名、同描述符的成员。</li>
     * </ul>
     */
    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void handlerSignaturesMatchTheServerJar(GameTestHelper helper) throws IOException {
        String path = System.getProperty(CREATE_JAR_PROPERTY);
        if (path == null || path.isBlank()) {
            warnSkipped("handlerSignaturesMatchTheServerJar");
            helper.succeed();
            return;
        }
        List<String> problems = new ArrayList<>();
        int handlers = 0;
        try (ZipFile jar = new ZipFile(path)) {
            for (CreateHookTargets.Hook hook : CreateHookTargets.HOOKS) {
                ClassNode target = read(jar, CreateHookTargets.internal(hook.target()));
                ClassNode mixin = readClasspath("com/miningdim/district/mixin/create/" + hook.mixin());
                if (target == null || mixin == null) {
                    problems.add(hook.id() + ": 读不到目标类或 mixin 类");
                    continue;
                }
                for (MethodNode handler : mixin.methods) {
                    AnnotationNode inject = annotation(handler.visibleAnnotations, INJECT);
                    AnnotationNode redirect = annotation(handler.visibleAnnotations, REDIRECT);
                    if (inject == null && redirect == null) {
                        continue;
                    }
                    handlers++;
                    String where = hook.id() + " " + hook.mixin() + "." + handler.name;
                    String selector = firstString(annotationValue(inject != null ? inject : redirect, "method"));
                    CreateHookTargets.Member member = selector == null ? null : CreateHookTargets.Member.of(selector);
                    MethodNode targetMethod = member == null ? null : method(target, member.name(), member.desc());
                    if (targetMethod == null) {
                        problems.add(where + ": 目标方法 " + selector + " 不存在");
                        continue;
                    }
                    boolean targetStatic = (targetMethod.access & Opcodes.ACC_STATIC) != 0;
                    if (targetStatic != ((handler.access & Opcodes.ACC_STATIC) != 0)) {
                        problems.add(where + ": 静态与否与目标方法 " + member.name() + " 不同");
                    }
                    List<Type> expected = new ArrayList<>();
                    Type expectedReturn;
                    if (inject != null) {
                        expected.addAll(List.of(Type.getArgumentTypes(targetMethod.desc)));
                        expected.add(Type.getReturnType(targetMethod.desc).getSort() == Type.VOID
                                ? Type.getObjectType(CALLBACK_INFO) : Type.getObjectType(CALLBACK_INFO_RETURNABLE));
                        expectedReturn = Type.VOID_TYPE;
                    } else {
                        MethodInsnNode call = redirectedCall(hook, member, targetMethod);
                        if (call == null) {
                            problems.add(where + ": 找不到被重定向的调用");
                            continue;
                        }
                        if (call.getOpcode() != Opcodes.INVOKESTATIC) {
                            expected.add(Type.getObjectType(call.owner));
                        }
                        expected.addAll(List.of(Type.getArgumentTypes(call.desc)));
                        expectedReturn = Type.getReturnType(call.desc);
                    }
                    Type[] actual = Type.getArgumentTypes(handler.desc);
                    if (redirect != null && actual.length > expected.size()) {
                        // @Redirect 可以在被调方法的参数之后接目标方法参数的前缀 (Mixin 0.8.5 的 captureTargetArgs)。
                        Type[] targetArgs = Type.getArgumentTypes(targetMethod.desc);
                        int extra = actual.length - expected.size();
                        for (int i = 0; i < extra && i < targetArgs.length; i++) {
                            expected.add(targetArgs[i]);
                        }
                    }
                    if (actual.length != expected.size()) {
                        problems.add(where + ": 参数个数 " + actual.length + ", 应为 " + expected.size() + " "
                                + expected);
                    } else {
                        for (int i = 0; i < actual.length; i++) {
                            if (!actual[i].equals(expected.get(i)) && !coerced(handler, i, actual[i], expected.get(i))) {
                                problems.add(where + ": 第 " + i + " 个参数是 " + actual[i] + ", 应为 " + expected.get(i)
                                        + " (或标 @Coerce 的 Object)");
                            }
                        }
                    }
                    if (!Type.getReturnType(handler.desc).equals(expectedReturn)) {
                        problems.add(where + ": 返回 " + Type.getReturnType(handler.desc) + ", 应为 " + expectedReturn);
                    }
                }
                checkShadows(hook, mixin, target, problems);
            }
        }
        LOGGER.info("[miningdim] district Create offline check: {} handler(s) in {} mixin(s) against {}: {} problem(s)",
                handlers, CreateHookTargets.HOOKS.size(), path, problems.size());
        helper.assertTrue(handlers >= CreateHookTargets.HOOKS.size(),
                "每个 mixin 至少一个处理方法, 实为 " + handlers + " 个");
        helper.assertTrue(problems.isEmpty(), "处理方法与机械动力 jar " + path + " 对不上: " + problems);
        helper.succeed();
    }

    /**
     * 没带 -PcreateJar: 这条按通过算, 但记一条 WARN, 免得"全过"被当成核对过了 (换整合包里的机械动力 jar 之前必须带上它
     * 再跑一轮, 设计文档 22.14、第二十三章)。
     */
    private static void warnSkipped(String test) {
        LOGGER.warn("[miningdim] district Create offline check {} was SKIPPED (no -PcreateJar=<Create jar>); it counts "
                + "as passed but checked nothing. Run gameTestServer with -PcreateJar before changing the pack's Create "
                + "jar", test);
    }

    private static final String INJECT = "Lorg/spongepowered/asm/mixin/injection/Inject;";
    private static final String REDIRECT = "Lorg/spongepowered/asm/mixin/injection/Redirect;";
    private static final String SHADOW = "Lorg/spongepowered/asm/mixin/Shadow;";
    private static final String COERCE = "Lorg/spongepowered/asm/mixin/injection/Coerce;";
    private static final String CALLBACK_INFO = "org/spongepowered/asm/mixin/injection/callback/CallbackInfo";
    private static final String CALLBACK_INFO_RETURNABLE =
            "org/spongepowered/asm/mixin/injection/callback/CallbackInfoReturnable";

    /** 被 @Redirect 的那个调用: 按注入点表里这个方法的 INVOKE 项 (jar 里的名字) 在目标方法的指令里找。 */
    private static MethodInsnNode redirectedCall(CreateHookTargets.Hook hook, CreateHookTargets.Member member,
                                                 MethodNode targetMethod) {
        for (CreateHookTargets.Invoke invoke : hook.invokes()) {
            if (!invoke.in().equals(member)) {
                continue;
            }
            for (AbstractInsnNode insn : targetMethod.instructions) {
                if (insn instanceof MethodInsnNode call && call.owner.equals(invoke.owner())
                        && call.name.equals(invoke.name()) && call.desc.equals(invoke.desc())) {
                    return call;
                }
            }
        }
        return null;
    }

    /** 处理方法第 i 个参数写成 Object 并标了 @Coerce, 而目标是引用类型。 */
    private static boolean coerced(MethodNode handler, int index, Type actual, Type expected) {
        if (!actual.equals(Type.getType(Object.class)) || expected.getSort() != Type.OBJECT) {
            return false;
        }
        return parameterAnnotated(handler.invisibleParameterAnnotations, index)
                || parameterAnnotated(handler.visibleParameterAnnotations, index);
    }

    private static boolean parameterAnnotated(List<AnnotationNode>[] annotations, int index) {
        return annotations != null && index < annotations.length && annotation(annotations[index], COERCE) != null;
    }

    private static void checkShadows(CreateHookTargets.Hook hook, ClassNode mixin, ClassNode target,
                                     List<String> problems) {
        for (FieldNode field : mixin.fields) {
            if (annotation(field.visibleAnnotations, SHADOW) == null) {
                continue;
            }
            boolean found = false;
            for (FieldNode candidate : target.fields) {
                found |= candidate.name.equals(field.name) && candidate.desc.equals(field.desc);
            }
            if (!found) {
                problems.add(hook.id() + " " + hook.mixin() + ": 影子字段 " + field.name + " " + field.desc
                        + " 在目标类里不存在");
            }
        }
        for (MethodNode shadow : mixin.methods) {
            if (annotation(shadow.visibleAnnotations, SHADOW) != null
                    && method(target, shadow.name, shadow.desc) == null) {
                problems.add(hook.id() + " " + hook.mixin() + ": 影子方法 " + shadow.name + shadow.desc
                        + " 在目标类里不存在");
            }
        }
    }

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

    private static String firstString(Object value) {
        if (value instanceof String text) {
            return text;
        }
        if (value instanceof List<?> list && !list.isEmpty() && list.get(0) instanceof String text) {
            return text;
        }
        return null;
    }

    private static ClassNode readClasspath(String internalName) throws IOException {
        try (InputStream in = CreateMixinTargetGameTests.class.getClassLoader()
                .getResourceAsStream(internalName + ".class")) {
            if (in == null) {
                return null;
            }
            ClassNode node = new ClassNode();
            new ClassReader(in.readAllBytes()).accept(node, ClassReader.SKIP_FRAMES);
            return node;
        }
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void hookTableMatchesTheMixinConfig(GameTestHelper helper) throws IOException {
        JsonObject config = readJson("miningdim.district.create.mixins.json");
        Set<String> listed = new TreeSet<>();
        JsonArray mixins = GsonHelper.getAsJsonArray(config, "mixins");
        for (JsonElement element : mixins) {
            listed.add(element.getAsString());
        }
        Set<String> table = new TreeSet<>();
        Set<String> ids = new TreeSet<>();
        for (CreateHookTargets.Hook hook : CreateHookTargets.HOOKS) {
            table.add(hook.mixin());
            ids.add(hook.id());
        }
        helper.assertTrue(mixins.size() == HOOK_COUNT && CreateHookTargets.HOOKS.size() == HOOK_COUNT
                        && ids.size() == HOOK_COUNT,
                "可选配置恰好 " + HOOK_COUNT + " 个, 表也是 " + HOOK_COUNT + " 项, 实为 " + mixins.size() + " / "
                        + CreateHookTargets.HOOKS.size());
        helper.assertTrue(listed.equals(table), "配置的列表与注入点表一一对应: 配置 " + listed + ", 表 " + table);
        helper.assertTrue(!GsonHelper.getAsBoolean(config, "required")
                        && "com.miningdim.district.mixin.create".equals(GsonHelper.getAsString(config, "package"))
                        && DistrictMixinPlugin.class.getName().equals(GsonHelper.getAsString(config, "plugin")),
                "机械动力的配置是可选的, 包与插件对得上");
        // 22.7: 可选配置里 require 不满足会抛 InjectionError (Error, 不看 required, 服务端起不来), 所以一律 0, 由插件的
        // 织入核对 (MixinHandlerScan) 报"没织全"; 注解里也不许再写 require。
        helper.assertTrue(GsonHelper.getAsInt(GsonHelper.getAsJsonObject(config, "injectors"), "defaultRequire") == 0,
                "机械动力的配置 injectors.defaultRequire 必须是 0");
        for (String mixin : listed) {
            ClassNode node = readClasspath("com/miningdim/district/mixin/create/" + mixin);
            helper.assertTrue(node != null, "读得到 mixin 类 " + mixin);
            for (MethodNode handler : node.methods) {
                for (String desc : List.of(INJECT, REDIRECT)) {
                    AnnotationNode injector = annotation(handler.visibleAnnotations, desc);
                    helper.assertTrue(injector == null || annotationValue(injector, "require") == null,
                            mixin + "." + handler.name + " 不能写 require (用 expect)");
                }
            }
        }
        for (String mixin : listed) {
            String resource = "com/miningdim/district/mixin/create/" + mixin + ".class";
            helper.assertTrue(CreateMixinTargetGameTests.class.getClassLoader().getResource(resource) != null,
                    "mixin 类 " + mixin + " 在 classpath 上");
        }
        JsonObject world = readJson("miningdim.district.mixins.json");
        helper.assertTrue(GsonHelper.getAsBoolean(world, "required")
                        && GsonHelper.getAsJsonArray(world, "mixins").size() == 5
                        && "com.miningdim.district.mixin.world".equals(GsonHelper.getAsString(world, "package"))
                        && DistrictMixinPlugin.class.getName().equals(GsonHelper.getAsString(world, "plugin")),
                "原版的配置是 required, 5 个 mixin");
        helper.succeed();
    }

    private static ClassNode read(ZipFile jar, String internalName) throws IOException {
        ZipEntry entry = jar.getEntry(internalName + ".class");
        if (entry == null) {
            return null;
        }
        try (InputStream in = jar.getInputStream(entry)) {
            ClassNode node = new ClassNode();
            new ClassReader(in.readAllBytes()).accept(node, ClassReader.SKIP_FRAMES);
            return node;
        }
    }

    private static MethodNode method(ClassNode node, String name, String desc) {
        for (MethodNode method : node.methods) {
            if (method.name.equals(name) && method.desc.equals(desc)) {
                return method;
            }
        }
        return null;
    }

    private static int countInvokes(MethodNode method, String owner, String name, String desc) {
        int count = 0;
        for (AbstractInsnNode insn : method.instructions) {
            if (insn instanceof MethodInsnNode call && call.owner.equals(owner) && call.name.equals(name)
                    && call.desc.equals(desc)) {
                count++;
            }
        }
        return count;
    }

    private static void checkField(ZipFile jar, CreateHookTargets.Field field, String what, List<String> problems)
            throws IOException {
        ClassNode node = read(jar, field.owner());
        if (node == null) {
            problems.add(what + ": 类 " + field.owner() + " 不存在");
            return;
        }
        for (FieldNode candidate : node.fields) {
            if (candidate.name.equals(field.name())) {
                if (!candidate.desc.equals(field.desc())) {
                    problems.add(what + " " + field.owner() + "." + field.name() + " 的类型是 " + candidate.desc
                            + ", 应为 " + field.desc());
                }
                return;
            }
        }
        problems.add(what + " " + field.owner() + "." + field.name() + " 不存在");
    }

    private static JsonObject readJson(String path) throws IOException {
        InputStream stream = CreateMixinTargetGameTests.class.getClassLoader().getResourceAsStream(path);
        if (stream == null) {
            throw new AssertionError("运行时 classpath 找不到资源: " + path);
        }
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            return GsonHelper.parse(reader);
        }
    }
}
