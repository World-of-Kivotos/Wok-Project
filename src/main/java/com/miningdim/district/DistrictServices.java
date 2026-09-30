package com.miningdim.district;

import com.miningdim.district.service.DistrictContext;

/**
 * 自管区模块的对外入口与运行期定位器 (仿 TitleServices / EconomyServices 平行定位器范式)。
 * {@link DistrictSystem} 在 ServerStartingEvent 绑定, ServerStoppingEvent 解除; GameTest 用 {@link DistrictTestEnv}
 * 换上测试用的 context, 结束后复原。
 */
public final class DistrictServices {

    private DistrictServices() {
    }

    private static volatile DistrictContext context;

    /** 绑定 (null 抛 IllegalArgumentException)。 */
    public static void register(DistrictContext ctx) {
        if (ctx == null) {
            throw new IllegalArgumentException("Cannot register a null DistrictContext");
        }
        context = ctx;
    }

    /** 取当前绑定 (未绑定抛 IllegalStateException, 不返回 null)。 */
    public static DistrictContext context() {
        DistrictContext current = context;
        if (current == null) {
            throw new IllegalStateException(
                    "DistrictServices: context not registered yet (the district subsystem binds it at server start)");
        }
        return current;
    }

    public static boolean isRegistered() {
        return context != null;
    }

    /** 解除绑定 (停服时调用, 防跨存档脏引用)。 */
    public static void reset() {
        context = null;
    }
}
