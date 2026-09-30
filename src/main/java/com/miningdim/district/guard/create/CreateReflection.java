package com.miningdim.district.guard.create;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * 机械动力里包内可见或只能反射拿到的成员 (设计文档 22.6), 第一次用到时解析一次: MovementContext 的 position 与 world、
 * PaveResult.FAIL、SpaceType.BLOCKING、PlacementInfo 的字段、BezierConnection.getBounds、
 * noDropWhenContraptionReplaceBlocks。机械动力在 FML 里是自动模块, 包全部开放, setAccessible 可用。
 *
 * <p>只有机械动力的 mixin 会调到这里 (它们只在装了机械动力时应用)。取不到时返回 null / 退路值, 每项只记一次 WARN。
 * 不引用任何机械动力的类型。
 */
public final class CreateReflection {

    private static final Logger LOGGER = LoggerFactory.getLogger("miningdim/district");

    private CreateReflection() {
    }

    /** 一项反射成员, 懒解析, 失败只记一次 WARN。 */
    private abstract static class Lazy<T> {
        private final String what;
        private volatile boolean resolved;
        @Nullable
        private volatile T value;

        Lazy(String what) {
            this.what = what;
        }

        @Nullable
        T get() {
            if (!resolved) {
                synchronized (this) {
                    if (!resolved) {
                        try {
                            value = resolve();
                        } catch (ReflectiveOperationException | RuntimeException | LinkageError failure) {
                            LOGGER.warn("[miningdim] district: cannot resolve Create member {} ({}); the related "
                                    + "guard falls back to its conservative default", what, failure.toString());
                        }
                        resolved = true;
                    }
                }
            }
            return value;
        }

        abstract T resolve() throws ReflectiveOperationException;
    }

    private static Class<?> create(String name) throws ClassNotFoundException {
        return Class.forName(name, false, CreateReflection.class.getClassLoader());
    }

    private static Field field(String owner, String name) throws ReflectiveOperationException {
        Field field = create(owner).getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }

    private static final Lazy<Field> CONTEXT_POSITION = new Lazy<>("MovementContext.position") {
        @Override
        Field resolve() throws ReflectiveOperationException {
            return field(CreateHookTargets.MOVEMENT_CONTEXT, "position");
        }
    };

    private static final Lazy<Field> CONTEXT_WORLD = new Lazy<>("MovementContext.world") {
        @Override
        Field resolve() throws ReflectiveOperationException {
            return field(CreateHookTargets.MOVEMENT_CONTEXT, "world");
        }
    };

    private static final Lazy<Object> PAVE_FAIL = new Lazy<>("RollerMovementBehaviour$PaveResult.FAIL") {
        @Override
        Object resolve() throws ReflectiveOperationException {
            return enumConstant(CreateHookTargets.PAVE_RESULT, "FAIL");
        }
    };

    private static final Lazy<Object> SPACE_BLOCKING = new Lazy<>("FluidFillingBehaviour$SpaceType.BLOCKING") {
        @Override
        Object resolve() throws ReflectiveOperationException {
            return enumConstant(CreateHookTargets.SPACE_TYPE, "BLOCKING");
        }
    };

    private static final Lazy<Field[]> PLACEMENT_FIELDS = new Lazy<>("TrackPlacement$PlacementInfo fields") {
        @Override
        Field[] resolve() throws ReflectiveOperationException {
            String owner = CreateHookTargets.PLACEMENT_INFO;
            return new Field[]{field(owner, "valid"), field(owner, "pos1"), field(owner, "pos2"),
                    field(owner, "end1Extent"), field(owner, "end2Extent"), field(owner, "axis1"),
                    field(owner, "axis2"), field(owner, "curve")};
        }
    };

    private static final Lazy<Method> CURVE_BOUNDS = new Lazy<>("BezierConnection.getBounds") {
        @Override
        Method resolve() throws ReflectiveOperationException {
            Method method = create(CreateHookTargets.BEZIER_CONNECTION).getMethod("getBounds");
            method.setAccessible(true);
            return method;
        }
    };

    private static final Lazy<Object> NO_DROP_CONFIG = new Lazy<>(
            "AllConfigs.server().kinetics.noDropWhenContraptionReplaceBlocks") {
        @Override
        Object resolve() throws ReflectiveOperationException {
            Object server = create(CreateHookTargets.ALL_CONFIGS).getMethod("server").invoke(null);
            Field kineticsField = server.getClass().getField("kinetics");
            Object kinetics = kineticsField.get(server);
            Field option = kinetics.getClass().getField("noDropWhenContraptionReplaceBlocks");
            return option.get(kinetics);
        }
    };

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static Object enumConstant(String owner, String name) throws ClassNotFoundException {
        return Enum.valueOf((Class) create(owner), name);
    }

    /** 装置部件的位置 (MovementContext.position); 取不到为 null。 */
    @Nullable
    public static Vec3 contextPosition(Object movementContext) {
        Field field = CONTEXT_POSITION.get();
        if (field == null || movementContext == null) {
            return null;
        }
        try {
            return (Vec3) field.get(movementContext);
        } catch (IllegalAccessException | ClassCastException | IllegalArgumentException failure) {
            return null;
        }
    }

    /** 装置部件所在的世界 (MovementContext.world); 取不到为 null。 */
    @Nullable
    public static Level contextWorld(Object movementContext) {
        Field field = CONTEXT_WORLD.get();
        if (field == null || movementContext == null) {
            return null;
        }
        try {
            return (Level) field.get(movementContext);
        } catch (IllegalAccessException | ClassCastException | IllegalArgumentException failure) {
            return null;
        }
    }

    /** RollerMovementBehaviour$PaveResult.FAIL; 取不到为 null (调用方放行)。 */
    @Nullable
    public static Object paveFail() {
        return PAVE_FAIL.get();
    }

    /** FluidFillingBehaviour$SpaceType.BLOCKING; 取不到为 null (调用方放行)。 */
    @Nullable
    public static Object spaceBlocking() {
        return SPACE_BLOCKING.get();
    }

    /** 装置拆装时 Create 自己的"被挡住就不掉落"配置; 读不到按默认 (假, 即掉落)。 */
    public static boolean noDropWhenContraptionReplaceBlocks() {
        Object option = NO_DROP_CONFIG.get();
        if (option == null) {
            return false;
        }
        try {
            Object value = option.getClass().getMethod("get").invoke(option);
            return Boolean.TRUE.equals(value);
        } catch (ReflectiveOperationException | RuntimeException failure) {
            return false;
        }
    }

    /** 轨道的一次放置 (TrackPlacement$PlacementInfo) 读出来的几何。 */
    public record TrackInfo(Object info, BlockPos pos1, BlockPos pos2, int end1Extent, int end2Extent, Vec3 axis1,
                            Vec3 axis2, @Nullable AABB curveBounds) {
    }

    /** 读一个 PlacementInfo 的几何; 读不出为 null。 */
    @Nullable
    public static TrackInfo trackInfo(@Nullable Object info) {
        Field[] fields = PLACEMENT_FIELDS.get();
        if (info == null || fields == null) {
            return null;
        }
        try {
            Object curve = fields[7].get(info);
            AABB bounds = null;
            if (curve != null) {
                Method getBounds = CURVE_BOUNDS.get();
                if (getBounds == null) {
                    return null;
                }
                bounds = (AABB) getBounds.invoke(curve);
            }
            return new TrackInfo(info, (BlockPos) fields[1].get(info), (BlockPos) fields[2].get(info),
                    fields[3].getInt(info), fields[4].getInt(info), (Vec3) fields[5].get(info),
                    (Vec3) fields[6].get(info), bounds);
        } catch (ReflectiveOperationException | RuntimeException failure) {
            return null;
        }
    }

    /** 把 PlacementInfo 的 valid 置假; 做不到返回 false。 */
    public static boolean invalidate(Object info) {
        Field[] fields = PLACEMENT_FIELDS.get();
        if (fields == null) {
            return false;
        }
        try {
            fields[0].setBoolean(info, false);
            return true;
        } catch (IllegalAccessException | RuntimeException failure) {
            return false;
        }
    }
}
