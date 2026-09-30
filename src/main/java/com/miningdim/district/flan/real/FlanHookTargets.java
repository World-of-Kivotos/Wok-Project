package com.miningdim.district.flan.real;

import java.util.List;

/**
 * 个人圈地限制的两个注入点 (设计文档 22.20): 目标类名、方法描述符与 Hook 表, 只有字符串与记录, 不引用 Flan 的任何类型
 * (同 {@link FlanCompat}), 没装 Flan 也能加载。
 *
 * <p>为什么放在 flan/real: 边界规则 ({@code verifyModuleBoundaries}) 按字面查, Flan 的包名只许出现在 flan/real 之下,
 * 字符串常量也算。mixin 包 ({@code com.miningdim.district.mixin.flan}) 的注解引用这里的 {@code static final String},
 * 编译时内联; 守卫的开服核对 ({@code guard.GuardMixinStatus}) 与 GameTest 读同一张 {@link #HOOKS} 表。
 *
 * <p>全部对服主批准的 {@code flan-1.20.1-1.11.16-forge.jar} 用 javap 核对过。Flan 自己的类名、方法名没有混淆 (选择器
 * remap = false); 描述符里的 Minecraft 类在正式服也是官方类名; 注入点是 HEAD, 不需要 refmap。
 */
public final class FlanHookTargets {

    private FlanHookTargets() {
    }

    /** 目标类: Flan 的领地存储。 */
    public static final String CLAIM_STORAGE = "io.github.flemmli97.flan.claim.ClaimStorage";

    private static final String POS = "Lnet/minecraft/core/BlockPos;";
    private static final String PLAYER = "Lnet/minecraft/server/level/ServerPlayer;";
    private static final String CLAIM = "Lio/github/flemmli97/flan/claim/Claim;";

    /** F1: {@code ClaimStorage.createClaim(BlockPos, BlockPos, ServerPlayer) -> boolean}: 金锄头新圈、/flan add 系列。 */
    public static final String CREATE_CLAIM = "createClaim(" + POS + POS + PLAYER + ")Z";

    /** F2: {@code ClaimStorage.resizeClaim(Claim, BlockPos from, BlockPos to, ServerPlayer) -> boolean}: 拖角、/flan expand。 */
    public static final String RESIZE_CLAIM = "resizeClaim(" + CLAIM + POS + POS + PLAYER + ")Z";

    /**
     * 一个注入点。
     *
     * @param id     F1、F2
     * @param mixin  mixin 的简名 (miningdim.district.flan.mixins.json 里的名字)
     * @param target 目标类 (点分全名)
     * @param method 注入的方法 (名字 + 描述符)
     * @param covers 失效时少了什么 (给 /district status 与开服 ERROR 用)
     */
    public record Hook(String id, String mixin, String target, String method, String covers) {

        public String targetSimpleName() {
            return target.substring(target.lastIndexOf('.') + 1);
        }

        /** 方法名 (描述符之前的部分)。 */
        public String methodName() {
            return method.substring(0, method.indexOf('('));
        }

        /** 方法描述符。 */
        public String methodDesc() {
            return method.substring(method.indexOf('('));
        }
    }

    public static final List<Hook> HOOKS = List.of(
            new Hook("F1", "FlanCreateClaimMixin", CLAIM_STORAGE, CREATE_CLAIM,
                    "新圈个人领地 (金锄头普通与 3D 模式, /flan add、add rect、add all)"),
            new Hook("F2", "FlanResizeClaimMixin", CLAIM_STORAGE, RESIZE_CLAIM,
                    "把个人领地扩进禁圈区 (金锄头拖角, /flan expand)"));
}
