package com.miningdim.district.guard;

import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.util.ArrayList;
import java.util.List;

/**
 * 核对一个 mixin 的处理方法真的织进了目标类 (设计文档 22.7): {@link DistrictMixinPlugin#postApply} 调它, 只用 ASM。
 *
 * <p>为什么要看: 机械动力的配置 {@code injectors.defaultRequire = 0} (22.1): 注入点对不上时 Mixin 0.8.5 不再抛
 * InjectionError (那是 Error, 不看配置的 required, 会让整个服务端起不来), 而是悄悄什么都不织; postApply 照样被调用。
 * 所以"应用上了"不能只看 postApply 有没有被调用, 要数目标类里对处理方法的调用。
 *
 * <p>数法: mixin 里标了 {@code @Inject}、{@code @Redirect}、{@code @ModifyArg} 的每个处理方法, 合并进目标类时被改名为
 * {@code <前缀>$<编号>$<原名>} (如 {@code handler$zza000$miningdim$guardDistrict}); 在目标类全部方法的指令里数 owner 是目标类、
 * 名字等于原名或以 {@code "$" + 原名} 结尾的调用, 至少要有注解里 {@code expect} 那么多次 (没写为 1; C11 两处写 2)。
 * {@code expect} 只在 Mixin 的调试选项 DEBUG_INJECTORS 打开时才被 Mixin 自己核对, 平时不影响应用。
 */
public final class MixinHandlerScan {

    /** 全部织进去了。 */
    public static final String APPLIED = "applied";

    /** 没织全时系统属性值的前缀 (后面跟缺了哪些)。 */
    public static final String PARTIAL_PREFIX = "partial: ";

    private static final List<String> INJECTOR_ANNOTATIONS = List.of(
            "Lorg/spongepowered/asm/mixin/injection/Inject;",
            "Lorg/spongepowered/asm/mixin/injection/Redirect;",
            "Lorg/spongepowered/asm/mixin/injection/ModifyArg;");

    private MixinHandlerScan() {
    }

    /**
     * @param mixin  mixin 类 (没合并之前的样子)
     * @param target 合并之后的目标类
     * @return {@link #APPLIED}, 或 {@link #PARTIAL_PREFIX} 加上 "处理方法 实际次数/应有次数" 的清单
     */
    public static String verdict(ClassNode mixin, ClassNode target) {
        List<String> missing = new ArrayList<>();
        for (MethodNode handler : mixin.methods) {
            AnnotationNode injector = injector(handler);
            if (injector == null) {
                continue;
            }
            int expected = Math.max(1, intValue(injector, "expect", 1));
            int calls = countCalls(target, handler.name);
            if (calls < expected) {
                missing.add(handler.name + " " + calls + "/" + expected);
            }
        }
        return missing.isEmpty() ? APPLIED : PARTIAL_PREFIX + String.join(", ", missing);
    }

    /** 目标类里对这个处理方法 (原名或改名之后) 的调用次数。 */
    public static int countCalls(ClassNode target, String handlerName) {
        String suffix = "$" + handlerName;
        int count = 0;
        for (MethodNode method : target.methods) {
            if (method.instructions == null) {
                continue;
            }
            for (AbstractInsnNode insn : method.instructions) {
                if (insn instanceof MethodInsnNode call && call.owner.equals(target.name)
                        && (call.name.equals(handlerName) || call.name.endsWith(suffix))) {
                    count++;
                }
            }
        }
        return count;
    }

    private static AnnotationNode injector(MethodNode method) {
        for (List<AnnotationNode> annotations : List.of(nullToEmpty(method.visibleAnnotations),
                nullToEmpty(method.invisibleAnnotations))) {
            for (AnnotationNode annotation : annotations) {
                if (INJECTOR_ANNOTATIONS.contains(annotation.desc)) {
                    return annotation;
                }
            }
        }
        return null;
    }

    private static List<AnnotationNode> nullToEmpty(List<AnnotationNode> annotations) {
        return annotations == null ? List.of() : annotations;
    }

    private static int intValue(AnnotationNode annotation, String name, int fallback) {
        if (annotation.values == null) {
            return fallback;
        }
        for (int i = 0; i + 1 < annotation.values.size(); i += 2) {
            if (name.equals(annotation.values.get(i)) && annotation.values.get(i + 1) instanceof Integer value) {
                return value;
            }
        }
        return fallback;
    }
}
