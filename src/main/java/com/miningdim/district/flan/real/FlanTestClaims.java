package com.miningdim.district.flan.real;

import com.google.gson.JsonObject;
import com.miningdim.district.core.PlotArea;
import com.mojang.authlib.GameProfile;
import io.github.flemmli97.flan.api.ClaimHandler;
import io.github.flemmli97.flan.claim.Claim;
import io.github.flemmli97.flan.claim.ClaimBox;
import io.github.flemmli97.flan.claim.ClaimStorage;
import io.github.flemmli97.flan.player.ClaimMode;
import net.minecraft.core.BlockPos;
import net.minecraft.core.UUIDUtil;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.ServerOpListEntry;
import org.jetbrains.annotations.Nullable;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;

/**
 * 真 Flan GameTest 的工具 (设计文档 20.10), 只给 {@code FlanRealGameTests} 与 {@code DistrictTestEnv} 用, 生产代码不用。
 * 这里的调用 (deleteClaim、transferOwner、私有 addClaim、ClaimHandler.canInteract、getForPermissionCheck) 不进开服自检的
 * 签名表, 由测试自己保证: 签名一变, 这个类编译不过或用例直接失败。
 *
 * <ul>
 *   <li>槽位: 每条用例一个固定槽位, X/Z 从 2,000,000 起、每个槽位错开 5,000 格, 远离原点附近的 GameTest 结构和别的用例。
 *       领地是全高 2D, 建领地和判定都不加载区块。</li>
 *   <li>玩家: 判定用 {@code new ServerPlayer(server, level, profile)}, 不放进世界、不进玩家列表。不用 GameTest 的
 *       mock 玩家: 那是匿名子类, Flan 按 {@code getClass() != ServerPlayer.class} 把它当成假玩家, 权限会被改写成
 *       flan:fake_player。</li>
 *   <li>OP: GameTest 服务端的 getOperatorUserPermissionLevel() 恒为 0, PlayerList.op 给不出 2 级; 这里直接往 OP 名单里放一条
 *       4 级的记录, 用完移除。</li>
 * </ul>
 */
public final class FlanTestClaims {

    /** 槽位原点与间距。 */
    public static final int SLOT_BASE = 2_000_000;
    public static final int SLOT_STRIDE = 5_000;
    /** 一个槽位的边长 (相邻槽位之间留 1,000 格空隙)。 */
    public static final int SLOT_SIZE = 4_000;
    /** 判定用的高度 (全高领地, 任意高度都在领地里)。 */
    public static final int PROBE_Y = 64;

    private FlanTestClaims() {
    }

    /** 用完要复原的东西 (close 不抛受检异常)。 */
    @FunctionalInterface
    public interface Restore extends AutoCloseable {
        @Override
        void close();
    }

    // ================================================================
    // 槽位、世界与清理 (签名里只有本模块的类型: DistrictTestEnv 也调它们)
    // ================================================================

    /** 第 slot 号槽位的范围。 */
    public static PlotArea slotArea(int slot) {
        int minX = SLOT_BASE + slot * SLOT_STRIDE;
        return new PlotArea(minX, SLOT_BASE, minX + SLOT_SIZE - 1, SLOT_BASE + SLOT_SIZE - 1);
    }

    public static ServerLevel level(MinecraftServer server, String dimension) {
        ServerLevel level = server.getLevel(ResourceKey.create(Registries.DIMENSION, new ResourceLocation(dimension)));
        if (level == null) {
            throw new IllegalStateException("dimension " + dimension + " is not loaded");
        }
        return level;
    }

    /** 删掉与这片范围相交的全部顶层领地 (管理员与玩家的, 连同子领地), 返回删了几块。用例开始前与结束后各调一次。 */
    public static int deleteTopLevelClaims(MinecraftServer server, String dimension, PlotArea area) {
        ServerLevel level = level(server, dimension);
        ClaimStorage storage = ClaimStorage.get(level);
        int deleted = 0;
        for (Set<Claim> owned : List.copyOf(storage.getClaims().values())) {
            for (Claim claim : List.copyOf(owned)) {
                if (!claim.isRemoved() && areaOf(claim.getDimensions()).overlaps(area)) {
                    storage.deleteClaim(claim, true, ClaimMode.DEFAULT, level);
                    deleted++;
                }
            }
        }
        return deleted;
    }

    // ================================================================
    // 读 Flan
    // ================================================================

    public static ClaimStorage storage(ServerLevel level) {
        return ClaimStorage.get(level);
    }

    /** 顶层领地 (没删的); 找不到为 null。 */
    @Nullable
    public static Claim claim(ServerLevel level, @Nullable UUID id) {
        if (id == null) {
            return null;
        }
        Claim claim = ClaimStorage.get(level).getFromUUID(id);
        return claim == null || claim.isRemoved() ? null : claim;
    }

    /** 某块父领地下的子领地 (没删的); 找不到为 null。 */
    @Nullable
    public static Claim subclaim(Claim parent, @Nullable UUID id) {
        for (Claim sub : parent.getAllSubclaims()) {
            if (sub.getClaimID().equals(id) && !sub.isRemoved()) {
                return sub;
            }
        }
        return null;
    }

    /** 全部顶层领地 (管理员与玩家的) 的 toJson, 按 id 排好。用来断言"前后一个字都没变"。 */
    public static Map<UUID, String> snapshot(ServerLevel level) {
        Map<UUID, String> out = new TreeMap<>();
        for (Set<Claim> owned : List.copyOf(ClaimStorage.get(level).getClaims().values())) {
            for (Claim claim : List.copyOf(owned)) {
                if (!claim.isRemoved()) {
                    out.put(claim.getClaimID(), claim.toJson(new JsonObject()).toString());
                }
            }
        }
        return out;
    }

    /** 管理员领地的块数。 */
    public static int adminClaimCount(ServerLevel level) {
        Set<Claim> admin = ClaimStorage.get(level).getClaims().get(null);
        return admin == null ? 0 : (int) admin.stream().filter(claim -> !claim.isRemoved()).count();
    }

    /** 组 -&gt; (权限 -&gt; 值) 的活 Map (只读用; 核对内层 Map 是不是同一个对象)。 */
    public static Map<String, Map<ResourceLocation, Boolean>> groupMaps(Claim claim) {
        try {
            return FlanReflection.resolve().permissions(claim);
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException("Claim.permissions is not reachable", failure);
        }
    }

    /** 成员 -&gt; 组 (只读)。 */
    public static Map<UUID, String> members(Claim claim) {
        try {
            return Map.copyOf(FlanReflection.resolve().playersGroups(claim));
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException("Claim.playersGroups is not reachable", failure);
        }
    }

    public static PlotArea areaOf(ClaimBox box) {
        return new PlotArea(box.minX(), box.minZ(), box.maxX(), box.maxZ());
    }

    // ================================================================
    // 模拟"有人用了 /flan 或金锄头" (公开 API 或测试专用的反射)
    // ================================================================

    /** 世界底上的一角。 */
    public static BlockPos low(ServerLevel level, int x, int z) {
        return new BlockPos(x, level.getMinBuildHeight(), z);
    }

    /** 判定用的点 (全高领地, 取 {@link #PROBE_Y})。 */
    public static BlockPos probe(int x, int z) {
        return new BlockPos(x, PROBE_Y, z);
    }

    /** 像 OP 用 /flan 那样圈一块管理员领地 (带 Flan 配置里的默认组 Co-Owner、Visitor), 两角点在世界底 (全高)。 */
    public static Claim rawAdminClaim(ServerLevel level, PlotArea area) {
        Claim claim = ClaimStorage.get(level).createAdminClaim(low(level, area.minX(), area.minZ()),
                low(level, area.maxX(), area.maxZ()), level, false);
        if (claim == null) {
            throw new IllegalStateException("admin claim " + area + " overlaps an existing claim");
        }
        return claim;
    }

    /**
     * 像 OP 站在地面上用 /flan 圈地那样: 两角点都在高度 y。2D 时 Flan 只往下探 defaultClaimDepth 格 (底在 y 减去它),
     * 3D 时只保护 y 这一段。
     */
    public static Claim rawAdminClaimAt(ServerLevel level, PlotArea area, int y, boolean threeD) {
        Claim claim = ClaimStorage.get(level).createAdminClaim(new BlockPos(area.minX(), y, area.minZ()),
                new BlockPos(area.maxX(), y, area.maxZ()), level, threeD);
        if (claim == null) {
            throw new IllegalStateException("admin claim " + area + " overlaps an existing claim");
        }
        return claim;
    }

    /** 与这片范围相交的顶层管理员领地 (没删的)。 */
    public static List<Claim> adminClaimsIn(ServerLevel level, PlotArea area) {
        Set<Claim> admin = ClaimStorage.get(level).getClaims().get(null);
        if (admin == null) {
            return List.of();
        }
        return admin.stream().filter(claim -> !claim.isRemoved() && areaOf(claim.getDimensions()).overlaps(area))
                .toList();
    }

    /** 测试专用的反射: 直接改 2D 领地的底 (模拟恢复了一份底还没补过的老备份)。改完标脏。 */
    public static void setMinY(Claim claim, int minY) {
        try {
            java.lang.reflect.Field field = Claim.class.getDeclaredField("minY");
            field.setAccessible(true);
            field.setInt(claim, minY);
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException("Claim.minY is not reachable", failure);
        }
        claim.setDirty(true);
    }

    /** 把这个维度的领地存盘 (与 Flan 在世界存盘时做的一样: 只有有脏领地时才重写文件, 存完清掉脏标记)。 */
    public static void save(ServerLevel level) {
        ClaimStorage.get(level).save(level.getServer(), level.dimension());
    }

    /**
     * 一块玩家领地 (先圈管理员领地, 再 transferOwner 给这个玩家): 不经 createClaim, 个人圈地限制 (22.20) 拦不到它 ——
     * 用来摆"设限之前就压着外围的老领地"。
     */
    public static Claim rawPlayerClaim(ServerLevel level, PlotArea area, UUID owner) {
        Claim claim = rawAdminClaim(level, area);
        ClaimStorage.get(level).transferOwner(claim, owner);
        return claim;
    }

    /** 同上, 两角点在高度 y (3D 时只保护 y 这一段)。 */
    public static Claim rawPlayerClaimAt(ServerLevel level, PlotArea area, int y, boolean threeD, UUID owner) {
        Claim claim = rawAdminClaimAt(level, area, y, threeD);
        ClaimStorage.get(level).transferOwner(claim, owner);
        return claim;
    }

    /** 这个玩家名下的顶层领地 (没删的)。 */
    public static List<Claim> playerClaims(ServerLevel level, UUID owner) {
        return ClaimStorage.get(level).allClaimsFromPlayer(owner).stream().filter(claim -> !claim.isRemoved())
                .toList();
    }

    /** 像 /flan adminDelete 那样删掉一块顶层领地 (连同子领地)。 */
    public static void adminDelete(ServerLevel level, Claim claim) {
        ClaimStorage.get(level).deleteClaim(claim, true, ClaimMode.DEFAULT, level);
    }

    /** 直接在父领地下划一块子领地 (像 OP 用金锄头的子领地模式), 返回新子领地; 它带着浅拷贝来的组与成员。 */
    public static Claim rawSubclaim(ServerLevel level, Claim parent, PlotArea area) {
        Set<Claim> conflicts = parent.tryCreateSubClaim(low(level, area.minX(), area.minZ()),
                low(level, area.maxX(), area.maxZ()), false);
        if (!conflicts.isEmpty()) {
            throw new IllegalStateException("subclaim " + area + " overlaps " + conflicts);
        }
        List<Claim> subs = parent.getAllSubclaims();
        return subs.get(subs.size() - 1);
    }

    /**
     * 模拟 OP 用金锄头改顶层领地的范围 (Flan 的 resizeClaim 走的就是这三步): 移出索引、copySizes、反射调私有的
     * addClaim 放回去。生产代码刻意不这么做 (设计文档 20.6)。
     */
    public static void simulateGoldenHoeResize(ServerLevel level, Claim claim, PlotArea area) {
        ClaimStorage storage = ClaimStorage.get(level);
        storage.deleteClaim(claim, false, ClaimMode.DEFAULT, level);
        claim.copySizes(new Claim(low(level, area.minX(), area.minZ()), low(level, area.maxX(), area.maxZ()),
                claim.getOwner(), level));
        addToStorage(storage, claim);
        claim.setDirty(true);
    }

    // ================================================================
    // 判定
    // ================================================================

    /** 一个真 ServerPlayer (类恰好是 ServerPlayer: Flan 不把它当假玩家), UUID 取名字的离线 UUID。 */
    public static ServerPlayer realPlayer(ServerLevel level, String name) {
        return new ServerPlayer(level.getServer(), level,
                new GameProfile(UUIDUtil.createOfflinePlayerUUID(name), name));
    }

    /** Flan 自己的判定入口 (公开 API): 这个玩家在这个位置能不能做这件事。 */
    public static boolean can(ServerPlayer player, BlockPos pos, String permission) {
        return ClaimHandler.canInteract(player, pos, new ResourceLocation(permission));
    }

    /** 全局权限 (区域规则) 的判定: 没有玩家, 没有组, 也没有 OP 绕过。 */
    public static boolean regionAllows(ServerLevel level, BlockPos pos, String permission) {
        return ClaimStorage.get(level).getForPermissionCheck(pos).canInteract(null, new ResourceLocation(permission),
                pos, false);
    }

    /** 把这个玩家临时放进 OP 名单 (给定等级), close 时移除。 */
    public static Restore op(ServerPlayer player, int permissionLevel) {
        MinecraftServer server = player.getServer();
        if (server == null) {
            throw new IllegalStateException("player has no server");
        }
        GameProfile profile = player.getGameProfile();
        server.getPlayerList().getOps().add(new ServerOpListEntry(profile, permissionLevel, false));
        return () -> server.getPlayerList().getOps().remove(profile);
    }

    /**
     * 模拟一次存盘 + 重启: 把顶层领地按 Flan 存盘的格式 toJson, 从存储里删掉, 再用 Flan 读盘的 {@code Claim.fromJson}
     * 读回一个新对象, 反射调私有的 addClaim 放回存储。返回读回来的领地 (地块嵌在它的子领地里, 各自是独立的副本)。
     */
    public static Claim simulateSaveAndReload(ServerLevel level, Claim claim) {
        JsonObject json = claim.toJson(new JsonObject());
        UUID owner = claim.getOwner();
        ClaimStorage storage = ClaimStorage.get(level);
        storage.deleteClaim(claim, true, ClaimMode.DEFAULT, level);
        Claim reloaded = Claim.fromJson(json, owner, level);
        addToStorage(storage, reloaded);
        return reloaded;
    }

    /** 反射调 ClaimStorage 私有的 addClaim (区块索引、id 索引、按主人的集合三处一起放)。 */
    private static void addToStorage(ClaimStorage storage, Claim claim) {
        try {
            Method add = ClaimStorage.class.getDeclaredMethod("addClaim", Claim.class);
            add.setAccessible(true);
            add.invoke(storage, claim);
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException("ClaimStorage.addClaim is not reachable", failure);
        }
    }
}
