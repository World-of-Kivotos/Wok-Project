package com.miningdim.district.flan.real;

import com.miningdim.district.flan.FlanPermissionPolicy;
import com.miningdim.district.flan.FlanPermissions;
import com.miningdim.district.flan.KnownPermission;
import net.minecraft.server.MinecraftServer;
import org.jetbrains.annotations.Nullable;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 开服自检 (设计文档 20.2): 版本门 + 签名表 + 只读探查。<b>只用类名字符串和反射, 不引用任何 Flan 类型</b>, 所以没装
 * Flan 的服务器也能加载本类; 只有全部通过, DistrictSystem 才第一次碰 {@link FlanBridge} / {@link FlanClaimGateway}。
 *
 * <p>任何一项不通过: 调用方记一条 ERROR (每个问题一行), 功能进入 DEGRADED, 一次写入都不做。不做会改领地的试探
 * (建一块临时领地再删掉会标脏, 在正式存档里留下痕迹); Flan 的行为交给 GameTest 核对。
 */
public final class FlanCompat {

    /** Flan 的 Forge modId。 */
    public static final String MOD_ID = "flan";
    /** 唯一核对过的版本 (服主批准的 flan-1.20.1-1.11.16-forge.jar)。别的版本一律不认, 即使签名全对。 */
    public static final String VERIFIED_VERSION = "1.20.1-1.11.16";

    private static final String P = "io.github.flemmli97.flan.";
    private static final String STORAGE = P + "claim.ClaimStorage";
    private static final String CLAIM = P + "claim.Claim";
    private static final String BOX = P + "claim.ClaimBox";
    private static final String ALLOWED_LIST = P + "claim.AllowedRegistryList";
    private static final String CONFIG_HANDLER = P + "config.ConfigHandler";
    private static final String CONFIG = P + "config.Config";
    private static final String PERMISSION_MANAGER = P + "api.permission.PermissionManager";
    private static final String CLAIM_PERMISSION = P + "api.permission.ClaimPermission";

    private static final String LEVEL = "net.minecraft.server.level.ServerLevel";
    private static final String POS = "net.minecraft.core.BlockPos";
    private static final String PLAYER = "net.minecraft.server.level.ServerPlayer";
    private static final String RL = "net.minecraft.resources.ResourceLocation";
    private static final String UUID_T = "java.util.UUID";
    private static final String STRING = "java.lang.String";
    private static final String MAP = "java.util.Map";
    private static final String LIST = "java.util.List";
    private static final String SET = "java.util.Set";
    private static final String BOOL = "boolean";
    private static final String INT = "int";
    private static final String VOID = "void";

    /** 签名表里一项的种类。 */
    public enum Kind {
        METHOD,
        CONSTRUCTOR,
        FIELD
    }

    /** 对一项的额外要求。 */
    public enum Req {
        STATIC,
        PUBLIC,
        FINAL,
        NOT_FINAL
    }

    /**
     * 签名表里的一项: 全限定类名 + 成员名 + 参数类型 + 返回 (字段) 类型 + 要求。方法与构造器一律要求 public;
     * 字段按 reqs 查。
     */
    public record Signature(String owner, Kind kind, String name, List<String> params, String type, Set<Req> reqs) {

        public Signature {
            params = List.copyOf(params);
            reqs = reqs.isEmpty() ? Set.of() : Set.copyOf(EnumSet.copyOf(reqs));
        }

        public static Signature method(String owner, String name, String returnType, String... params) {
            return new Signature(owner, Kind.METHOD, name, List.of(params), returnType, Set.of(Req.PUBLIC));
        }

        public static Signature staticMethod(String owner, String name, String returnType, String... params) {
            return new Signature(owner, Kind.METHOD, name, List.of(params), returnType, Set.of(Req.PUBLIC, Req.STATIC));
        }

        public static Signature constructor(String owner, String... params) {
            return new Signature(owner, Kind.CONSTRUCTOR, "<init>", List.of(params), VOID, Set.of(Req.PUBLIC));
        }

        public static Signature field(String owner, String name, String type, Req... reqs) {
            return new Signature(owner, Kind.FIELD, name, List.of(), type, reqs.length == 0 ? Set.of() : Set.of(reqs));
        }

        public String describe() {
            return switch (kind) {
                case METHOD -> (reqs.contains(Req.STATIC) ? "static " : "") + owner + "#" + name + "("
                        + String.join(", ", params) + ") -> " + type;
                case CONSTRUCTOR -> owner + "#<init>(" + String.join(", ", params) + ")";
                case FIELD -> owner + "." + name + " : " + type + " " + reqs;
            };
        }
    }

    /** 自检结论: 通过与否、问题清单 (每个问题一行)、Flan 版本 (没装为 null)、读到的权限表 (探查通过时)。 */
    public record Result(boolean ok, List<String> problems, @Nullable String version, List<KnownPermission> table) {

        public Result {
            problems = List.copyOf(problems);
            table = List.copyOf(table);
        }

        static Result of(List<String> problems, @Nullable String version, List<KnownPermission> table) {
            return new Result(problems.isEmpty(), problems, version, table);
        }
    }

    private FlanCompat() {
    }

    /** 生产代码用到的全部 Flan 签名 (20.2 的签名表)。只有 GameTest 用的调用不在表里, 由测试自己保证。 */
    public static List<Signature> expectedSignatures() {
        List<Signature> table = new ArrayList<>();
        table.add(Signature.staticMethod(STORAGE, "get", STORAGE, LEVEL));
        table.add(Signature.method(STORAGE, "createAdminClaim", CLAIM, POS, POS, LEVEL, BOOL));
        table.add(Signature.method(STORAGE, "getClaimsAt", LIST, INT, INT));
        table.add(Signature.method(STORAGE, "getFromUUID", CLAIM, UUID_T));
        table.add(Signature.method(STORAGE, "getClaims", MAP));
        table.add(Signature.method(STORAGE, "save", VOID, "net.minecraft.server.MinecraftServer",
                "net.minecraft.resources.ResourceKey"));
        table.add(Signature.field(STORAGE, "ADMIN_CLAIMS", STRING, Req.PUBLIC, Req.STATIC, Req.FINAL));

        table.add(Signature.constructor(CLAIM, POS, POS, UUID_T, LEVEL));
        table.add(Signature.method(CLAIM, "tryCreateSubClaim", SET, POS, POS, BOOL));
        table.add(Signature.method(CLAIM, "getAllSubclaims", LIST));
        table.add(Signature.method(CLAIM, "deleteSubClaim", BOOL, CLAIM));
        table.add(Signature.method(CLAIM, "copySizes", VOID, CLAIM));
        table.add(Signature.method(CLAIM, "getClaimID", UUID_T));
        table.add(Signature.method(CLAIM, "getOwner", UUID_T));
        table.add(Signature.method(CLAIM, "parentClaim", CLAIM));
        table.add(Signature.method(CLAIM, "isSubclaim", BOOL));
        table.add(Signature.method(CLAIM, "isAdminClaim", BOOL));
        table.add(Signature.method(CLAIM, "isRemoved", BOOL));
        table.add(Signature.method(CLAIM, "getLevel", LEVEL));
        table.add(Signature.method(CLAIM, "is3d", BOOL));
        table.add(Signature.method(CLAIM, "getDimensions", BOX));
        table.add(Signature.method(CLAIM, "editPerms", BOOL, PLAYER, STRING, RL, INT, BOOL));
        table.add(Signature.method(CLAIM, "editGlobalPerms", BOOL, PLAYER, RL, INT));
        table.add(Signature.method(CLAIM, "groups", LIST));
        table.add(Signature.method(CLAIM, "groupHasPerm", INT, STRING, RL));
        table.add(Signature.method(CLAIM, "permEnabled", INT, RL));
        table.add(Signature.method(CLAIM, "setPlayerGroup", BOOL, UUID_T, STRING, BOOL));
        table.add(Signature.method(CLAIM, "getAllowedFakePlayerUUID", LIST));
        table.add(Signature.method(CLAIM, "modifyFakePlayerUUID", BOOL, UUID_T, BOOL));
        table.add(Signature.method(CLAIM, "getPotions", MAP));
        table.add(Signature.method(CLAIM, "removePotion", VOID, "net.minecraft.world.effect.MobEffect"));
        table.add(Signature.method(CLAIM, "setClaimName", VOID, STRING));
        table.add(Signature.method(CLAIM, "getClaimName", STRING));
        table.add(Signature.method(CLAIM, "setDirty", VOID, BOOL));
        table.add(Signature.method(CLAIM, "isDirty", BOOL));
        table.add(Signature.method(CLAIM, "toJson", "com.google.gson.JsonObject", "com.google.gson.JsonObject"));
        table.add(Signature.method(CLAIM, "extendDownwards", VOID, POS));
        table.add(Signature.field(CLAIM, "permissions", MAP, Req.FINAL));
        table.add(Signature.field(CLAIM, "playersGroups", MAP, Req.FINAL));
        for (String list : List.of("allowedItems", "allowedUseBlocks", "allowedPlaceBlocks", "allowedBreakBlocks",
                "allowedEntityAttack", "allowedEntityUse")) {
            table.add(Signature.field(CLAIM, list, ALLOWED_LIST, Req.PUBLIC, Req.FINAL));
        }
        table.add(Signature.method(ALLOWED_LIST, "size", INT));
        table.add(Signature.method(ALLOWED_LIST, "removeAllowedItem", VOID, INT));

        for (String accessor : List.of("minX", "minY", "minZ", "maxX", "maxY", "maxZ")) {
            table.add(Signature.method(BOX, accessor, INT));
        }

        table.add(Signature.field(CONFIG_HANDLER, "CONFIG", CONFIG, Req.PUBLIC, Req.STATIC));
        table.add(Signature.field(CONFIG, "defaultGroups", MAP, Req.PUBLIC, Req.NOT_FINAL));

        table.add(Signature.field(PERMISSION_MANAGER, "INSTANCE", PERMISSION_MANAGER, Req.PUBLIC, Req.STATIC));
        table.add(Signature.method(PERMISSION_MANAGER, "getAll", "java.util.Collection"));
        table.add(Signature.method(PERMISSION_MANAGER, "get", CLAIM_PERMISSION, RL));
        table.add(Signature.method(PERMISSION_MANAGER, "isGlobalPermission", BOOL, RL));

        table.add(Signature.method(CLAIM_PERMISSION, "getId", RL));
        for (String flag : List.of("defaultVal", "global", "requireExplicitSet")) {
            table.add(Signature.field(CLAIM_PERMISSION, flag, BOOL, Req.PUBLIC, Req.FINAL));
        }
        return List.copyOf(table);
    }

    /**
     * 完整自检: 版本门、签名表、只读探查。
     *
     * @param version Flan 模组容器的版本串 (没装为 null)
     * @param table   要核对的签名表 (生产用 {@link #expectedSignatures()}; 测试传一张故意写错的)
     * @param server  探查用 (主世界的 ClaimStorage); 为 null 时跳过探查
     */
    public static Result check(@Nullable String version, List<Signature> table, ClassLoader loader,
                               @Nullable MinecraftServer server) {
        List<String> problems = new ArrayList<>();
        if (version == null) {
            problems.add("Flan is not loaded");
            return Result.of(problems, null, List.of());
        }
        if (!VERIFIED_VERSION.equals(version)) {
            problems.add("Flan version " + version + " is not the verified " + VERIFIED_VERSION
                    + " (behaviour differences are not visible in signatures)");
        }
        problems.addAll(checkSignatures(table, loader));
        if (!problems.isEmpty()) {
            return Result.of(problems, version, List.of());
        }
        List<KnownPermission> permissions = new ArrayList<>();
        problems.addAll(probe(loader, server, permissions));
        return Result.of(problems, version, problems.isEmpty() ? permissions : List.of());
    }

    /** 逐项反射查找签名表 (不初始化任何类), 返回问题清单。 */
    public static List<String> checkSignatures(List<Signature> table, ClassLoader loader) {
        List<String> problems = new ArrayList<>();
        for (Signature signature : table) {
            String problem = checkOne(signature, loader);
            if (problem != null) {
                problems.add(problem);
            }
        }
        return problems;
    }

    @Nullable
    private static String checkOne(Signature signature, ClassLoader loader) {
        Class<?> owner;
        try {
            owner = Class.forName(signature.owner(), false, loader);
        } catch (ClassNotFoundException | LinkageError missing) {
            return "missing class " + signature.owner() + " (for " + signature.describe() + ")";
        }
        try {
            switch (signature.kind()) {
                case METHOD -> {
                    Method method = owner.getDeclaredMethod(signature.name(), paramTypes(signature, loader));
                    if (!method.getReturnType().getName().equals(signature.type())) {
                        return "return type of " + signature.describe() + " is " + method.getReturnType().getName();
                    }
                    return modifierProblem(signature, method.getModifiers());
                }
                case CONSTRUCTOR -> {
                    Constructor<?> constructor = owner.getDeclaredConstructor(paramTypes(signature, loader));
                    return modifierProblem(signature, constructor.getModifiers());
                }
                case FIELD -> {
                    Field field = owner.getDeclaredField(signature.name());
                    if (!field.getType().getName().equals(signature.type())) {
                        return "type of " + signature.describe() + " is " + field.getType().getName();
                    }
                    return modifierProblem(signature, field.getModifiers());
                }
                default -> {
                    return "unknown signature kind " + signature.kind();
                }
            }
        } catch (NoSuchMethodException | NoSuchFieldException missing) {
            return "missing " + signature.describe();
        } catch (ClassNotFoundException | LinkageError missing) {
            return "cannot resolve a parameter type of " + signature.describe() + ": " + missing;
        }
    }

    @Nullable
    private static String modifierProblem(Signature signature, int modifiers) {
        Set<Req> reqs = signature.reqs();
        if (reqs.contains(Req.PUBLIC) && !Modifier.isPublic(modifiers)) {
            return signature.describe() + " is not public";
        }
        if (reqs.contains(Req.STATIC) != Modifier.isStatic(modifiers)) {
            return signature.describe() + (Modifier.isStatic(modifiers) ? " is static" : " is not static");
        }
        if (reqs.contains(Req.FINAL) && !Modifier.isFinal(modifiers)) {
            return signature.describe() + " is not final";
        }
        if (reqs.contains(Req.NOT_FINAL) && Modifier.isFinal(modifiers)) {
            return signature.describe() + " is final";
        }
        return null;
    }

    private static Class<?>[] paramTypes(Signature signature, ClassLoader loader) throws ClassNotFoundException {
        Class<?>[] types = new Class<?>[signature.params().size()];
        for (int i = 0; i < types.length; i++) {
            types[i] = typeOf(signature.params().get(i), loader);
        }
        return types;
    }

    private static Class<?> typeOf(String name, ClassLoader loader) throws ClassNotFoundException {
        return switch (name) {
            case "boolean" -> boolean.class;
            case "int" -> int.class;
            case "long" -> long.class;
            case "double" -> double.class;
            case "float" -> float.class;
            default -> Class.forName(name, false, loader);
        };
    }

    // ================================================================
    // 只读探查
    // ================================================================

    /**
     * 只读探查, 不改任何领地: ADMIN_CLAIMS 是 "!AdminClaims" (备份恢复靠这个文件名); ConfigHandler.CONFIG 不为 null;
     * PermissionManager 的权限表非空、必需的 id 都在、全局的恰好是 {@link FlanPermissions#GLOBAL}; 主世界的
     * ClaimStorage 不为 null (Flan 的 mixin 已生效)。读到的权限表放进 out。
     */
    private static List<String> probe(ClassLoader loader, @Nullable MinecraftServer server, List<KnownPermission> out) {
        List<String> problems = new ArrayList<>();
        try {
            Object adminClaims = Class.forName(STORAGE, true, loader).getField("ADMIN_CLAIMS").get(null);
            if (!"!AdminClaims".equals(adminClaims)) {
                problems.add("ClaimStorage.ADMIN_CLAIMS is " + adminClaims + ", expected !AdminClaims");
            }
            Object config = Class.forName(CONFIG_HANDLER, true, loader).getField("CONFIG").get(null);
            if (config == null) {
                problems.add("ConfigHandler.CONFIG is null");
            }
            problems.addAll(readPermissionTable(loader, out));
            if (server != null) {
                Method get = Class.forName(STORAGE, true, loader).getMethod("get",
                        Class.forName(LEVEL, false, loader));
                if (get.invoke(null, server.overworld()) == null) {
                    problems.add("ClaimStorage.get(overworld) is null (Flan's level mixin did not apply)");
                }
            }
        } catch (ReflectiveOperationException | RuntimeException | LinkageError failure) {
            problems.add("probing Flan failed: " + failure);
        }
        return problems;
    }

    /** 反射读 PermissionManager.INSTANCE.getAll(), 转成 KnownPermission 并核对必需的 id 与全局标志。 */
    static List<String> readPermissionTable(ClassLoader loader, List<KnownPermission> out)
            throws ReflectiveOperationException {
        Class<?> managerClass = Class.forName(PERMISSION_MANAGER, true, loader);
        Object manager = managerClass.getField("INSTANCE").get(null);
        Object all = managerClass.getMethod("getAll").invoke(manager);
        if (!(all instanceof Collection<?> permissions) || permissions.isEmpty()) {
            return List.of("PermissionManager.getAll() is empty (datapacks not loaded?)");
        }
        Class<?> permissionClass = Class.forName(CLAIM_PERMISSION, true, loader);
        Method getId = permissionClass.getMethod("getId");
        Field global = permissionClass.getField("global");
        Field defaultVal = permissionClass.getField("defaultVal");
        Field explicit = permissionClass.getField("requireExplicitSet");
        List<KnownPermission> table = new ArrayList<>();
        for (Object permission : permissions) {
            table.add(new KnownPermission(String.valueOf(getId.invoke(permission)), global.getBoolean(permission),
                    defaultVal.getBoolean(permission), explicit.getBoolean(permission)));
        }
        List<String> problems = new ArrayList<>(FlanPermissionPolicy.of(table).problems());
        Set<String> globals = new LinkedHashSet<>();
        table.stream().filter(KnownPermission::global).forEach(permission -> globals.add(permission.id()));
        if (!globals.equals(FlanPermissions.GLOBAL)) {
            problems.add("Flan's global permissions are " + globals + ", expected exactly " + FlanPermissions.GLOBAL);
        }
        out.addAll(table);
        return problems;
    }

    /**
     * Flan 配置里的 permissionLevel (管理命令要求的权限等级, 默认 2; 反射读, 不引用 Flan 的类型)。读不到 (没装、配置还没
     * 加载) 为 null。低于 2 时 1 级 OP (P14, 不例外) 也能用 Flan 的管理命令绕过个人圈地限制 (22.20): 开服记 WARN,
     * /district status 注明。
     */
    @Nullable
    public static Integer permissionLevel(ClassLoader loader) {
        try {
            Object config = Class.forName(CONFIG_HANDLER, true, loader).getField("CONFIG").get(null);
            if (config == null) {
                return null;
            }
            Object level = config.getClass().getField("permissionLevel").get(config);
            return level instanceof Integer value ? value : null;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError unavailable) {
            return null;
        }
    }

    /** 开服 INFO 用: Flan 配置里与自管区有关的几项的当前值 (反射读, 读不到的写 ?)。 */
    public static String describeConfig(ClassLoader loader) {
        try {
            Object config = Class.forName(CONFIG_HANDLER, true, loader).getField("CONFIG").get(null);
            if (config == null) {
                return "CONFIG is null";
            }
            StringBuilder text = new StringBuilder();
            for (String name : List.of("defaultClaimDepth", "subClaimsInheritParentDepth", "minClaimsize",
                    "permissionLevel", "configVersion", "preConfigVersion")) {
                text.append(name).append('=').append(config.getClass().getField(name).get(config)).append(", ");
            }
            Object groups = config.getClass().getField("defaultGroups").get(config);
            text.append("defaultGroups=").append(groups instanceof Map<?, ?> map ? map.keySet() : groups);
            return text.toString();
        } catch (ReflectiveOperationException | RuntimeException | LinkageError failure) {
            return "? (" + failure + ")";
        }
    }
}
