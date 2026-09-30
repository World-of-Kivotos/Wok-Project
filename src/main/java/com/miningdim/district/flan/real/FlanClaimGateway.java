package com.miningdim.district.flan.real;

import com.miningdim.district.DistrictFeature;
import com.miningdim.district.core.DistrictBounds;
import com.miningdim.district.core.DistrictTexts;
import com.miningdim.district.core.PlotArea;
import com.miningdim.district.flan.ClaimHandle;
import com.miningdim.district.flan.ClaimInfo;
import com.miningdim.district.flan.ClaimPermissionSnapshot;
import com.miningdim.district.flan.FlanGateway;
import com.miningdim.district.flan.FlanGroupNames;
import com.miningdim.district.flan.FlanPermissions;
import com.miningdim.district.flan.FlanResult;
import com.miningdim.district.flan.KnownPermission;
import com.miningdim.district.flan.LogThrottle;
import com.miningdim.district.flan.PermValue;
import com.miningdim.district.flan.PersonalClaim;
import io.github.flemmli97.flan.claim.AllowedRegistryList;
import io.github.flemmli97.flan.claim.Claim;
import io.github.flemmli97.flan.claim.ClaimBox;
import io.github.flemmli97.flan.claim.ClaimStorage;
import io.github.flemmli97.flan.config.Config;
import io.github.flemmli97.flan.config.ConfigHandler;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.effect.MobEffect;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/**
 * 真 Flan 网关 (设计文档 20.7, Flan 1.20.1-1.11.16)。只在开服自检 ({@link FlanCompat}) 通过之后经 {@link FlanBridge}
 * 构造; 全模块只有 flan/real 包 import Flan。
 *
 * <p>失败保护 (20.2):
 * <ul>
 *   <li>每个方法先查是否在服务器线程上, 不是就报失败、不碰 Flan (Flan 全是无锁的 HashMap / ArrayList);</li>
 *   <li>包住 RuntimeException 与 LinkageError, 一律转成 "Flan 调用出错（异常类名），详见服务器日志" —— 异常漏出去的话,
 *       afterCommit 会吞掉它, 事务里先写的"临时失败"原样留着, 没人知道;</li>
 *   <li>LinkageError (自检之后本不该有) 让网关熔断: 此后所有写入直接失败, 功能转为 DEGRADED, 重启才恢复;</li>
 *   <li>写前预检: 句柄解析得到、权限 id 在当前表里、地块范围在父领地内且不压兄弟地块; 不过就不写;</li>
 *   <li>每个会改动领地的方法, 第一次改动某个维度之前先确认 10 分钟内有过备份, 备份失败就不改 (20.5);</li>
 *   <li>值与现状相同就不写 (不标脏), 直接报成功。</li>
 * </ul>
 * 组名守卫: 父领地上只许 d_ 组, 地块上只许 p_ 组; 管理类权限写真、往组里写全局权限、未知 id 一律拒绝。
 */
public final class FlanClaimGateway implements FlanGateway {

    private static final Logger LOGGER = LoggerFactory.getLogger("miningdim/district");

    private final MinecraftServer server;
    private final FlanReflection reflection;
    private final FlanPermissionTable permissions;
    private final FlanClaimBackups backups;
    private final LogThrottle throttle;
    /** 父领地 id -&gt; (子领地 id -&gt; 子领地), 只在服务器线程上读写 (见 {@link #subclaimOf})。 */
    private final Map<UUID, Map<UUID, Claim>> subclaimIndex = new HashMap<>();
    private volatile boolean fused;

    FlanClaimGateway(MinecraftServer server, FlanReflection reflection, FlanPermissionTable permissions,
                     FlanClaimBackups backups, LongSupplier clock) {
        this.server = server;
        this.reflection = reflection;
        this.permissions = permissions;
        this.backups = backups;
        this.throttle = new LogThrottle(clock);
    }

    // ================================================================
    // 失败保护
    // ================================================================

    /** 写方法的外壳: 熔断、线程、异常。 */
    private <T> FlanResult<T> write(String op, Supplier<FlanResult<T>> body) {
        if (fused) {
            return FlanResult.failure(DistrictTexts.flanDisabled(DistrictTexts.FLAN_REASON_FUSED));
        }
        if (!server.isSameThread()) {
            if (throttle.loud("thread|" + op)) {
                LOGGER.error("[miningdim] district: Flan gateway {} called off the server thread ({}); refused",
                        op, Thread.currentThread().getName(), new IllegalStateException("off-thread Flan call"));
            }
            return FlanResult.failure("Flan 只能在服务器线程上调用，本次没有写入");
        }
        try {
            return body.get();
        } catch (LinkageError linkage) {
            fuse(op, linkage);
            return FlanResult.failure("Flan 调用出错（" + linkage.getClass().getSimpleName() + "），详见服务器日志");
        } catch (RuntimeException failure) {
            logThrown(op, failure);
            return FlanResult.failure("Flan 调用出错（" + failure.getClass().getSimpleName() + "），详见服务器日志");
        }
    }

    /** 读方法的外壳: 出错时返回 fallback (并记 ERROR)。 */
    private <T> T read(String op, T fallback, Supplier<T> body) {
        if (fused || !server.isSameThread()) {
            if (!fused && throttle.loud("thread|" + op)) {
                LOGGER.error("[miningdim] district: Flan gateway {} called off the server thread ({}); refused", op,
                        Thread.currentThread().getName());
            }
            return fallback;
        }
        try {
            return body.get();
        } catch (LinkageError linkage) {
            fuse(op, linkage);
            return fallback;
        } catch (RuntimeException failure) {
            logThrown(op, failure);
            return fallback;
        }
    }

    /**
     * Flan 抛出的异常: 同一个方法、同一类异常每小时只记一次带堆栈的 ERROR, 其余降成一行 DEBUG —— 一个持续出错的读
     * (对账每一轮每块地都要读好几次) 不能每 5 分钟刷一屏堆栈。
     */
    private void logThrown(String op, RuntimeException failure) {
        if (throttle.loud("threw|" + op + "|" + failure.getClass().getName())) {
            LOGGER.error("[miningdim] district: Flan gateway {} threw (repeats of this within the hour are logged at "
                    + "DEBUG)", op, failure);
        } else {
            LOGGER.debug("[miningdim] district: Flan gateway {} threw {}: {}", op, failure.getClass().getName(),
                    failure.getMessage());
        }
    }

    private void fuse(String op, LinkageError linkage) {
        if (!fused) {
            fused = true;
            LOGGER.error("[miningdim] district: Flan gateway {} hit {}; the gateway is fused until restart (every "
                    + "Flan write fails from now on)", op, linkage.getClass().getSimpleName(), linkage);
            DistrictFeature.fuse(DistrictTexts.FLAN_REASON_FUSED);
        }
    }

    @Nullable
    private ServerLevel level(String dimension) {
        return server.getLevel(ResourceKey.create(Registries.DIMENSION, new ResourceLocation(dimension)));
    }

    private static <T> FlanResult<T> noLevel(String dimension) {
        return FlanResult.failure("维度 " + dimension + " 没有加载");
    }

    private static <T> FlanResult<T> noClaim() {
        return FlanResult.failure("领地在 Flan 里找不到了");
    }

    /** 句柄 -&gt; Claim: 顶层按 claimUUIDMap, 地块在父领地的子领地里按 id 找; 已删除的不算。 */
    @Nullable
    private Claim resolve(ClaimHandle handle) {
        ServerLevel level = level(handle.dimension());
        if (level == null) {
            return null;
        }
        ClaimStorage storage = ClaimStorage.get(level);
        if (handle.parentId() == null) {
            Claim claim = storage.getFromUUID(handle.claimId());
            return claim != null && !claim.isRemoved() && !claim.isSubclaim() ? claim : null;
        }
        Claim parent = storage.getFromUUID(handle.parentId());
        if (parent == null || parent.isRemoved() || parent.isSubclaim()) {
            subclaimIndex.remove(handle.parentId());
            return null;
        }
        return subclaimOf(parent, handle.claimId());
    }

    /**
     * 父领地下按 id 找子领地。getAllSubclaims() 每次都整张复制 (ImmutableList.copyOf), 逐块线性找的话, 每次写一块地
     * 都要扫一遍全区, 大区里"加一名住户 → 触达每块地"就成了平方级。这里按父领地缓存一张 id 索引: 命中时核对它没被删、
     * 仍挂在这块父领地对象下 (父领地被读盘换成新对象时旧索引自然失效), 不符或没命中就整张重建一次。
     */
    @Nullable
    private Claim subclaimOf(Claim parent, UUID id) {
        Map<UUID, Claim> index = subclaimIndex.get(parent.getClaimID());
        Claim hit = index == null ? null : index.get(id);
        if (hit != null && !hit.isRemoved() && hit.parentClaim() == parent) {
            return hit;
        }
        Map<UUID, Claim> rebuilt = new HashMap<>();
        for (Claim sub : parent.getAllSubclaims()) {
            if (!sub.isRemoved()) {
                rebuilt.put(sub.getClaimID(), sub);
            }
        }
        subclaimIndex.put(parent.getClaimID(), rebuilt);
        return rebuilt.get(id);
    }

    private static ClaimHandle handleOf(String dimension, Claim claim) {
        Claim parent = claim.parentClaim();
        return new ClaimHandle(dimension, claim.getClaimID(), parent == null ? null : parent.getClaimID());
    }

    /** 第一次改动某个维度之前: 10 分钟内有过备份, 否则先备一份; 失败就不写。 */
    @Nullable
    private String beforeWrite(ServerLevel level, String dimension) {
        FlanResult<Void> backup = backups.ensureRecent(level, dimension);
        if (!backup.ok() && throttle.loud("backup|" + dimension)) {
            LOGGER.error("[miningdim] district: no recent Flan backup of {} could be written; Flan writes in this "
                    + "dimension are refused until one succeeds", dimension);
        }
        return backup.ok() ? null : DistrictTexts.FLAN_BACKUP_FAILED;
    }

    private static boolean sameXz(ClaimBox box, PlotArea area) {
        return box.minX() == area.minX() && box.minZ() == area.minZ() && box.maxX() == area.maxX()
                && box.maxZ() == area.maxZ();
    }

    private static PlotArea areaOf(ClaimBox box) {
        return new PlotArea(box.minX(), box.minZ(), box.maxX(), box.maxZ());
    }

    private static BlockPos lowCorner(ServerLevel level, int x, int z) {
        return new BlockPos(x, level.getMinBuildHeight(), z);
    }

    @Nullable
    private KnownPermission known(String flanPermId) {
        for (KnownPermission permission : permissions.table()) {
            if (permission.id().equals(flanPermId)) {
                return permission;
            }
        }
        return null;
    }

    // ================================================================
    // 查询
    // ================================================================

    @Override
    public boolean available() {
        return !fused;
    }

    @Override
    public String unavailableReason() {
        return DistrictTexts.flanDisabled(DistrictTexts.FLAN_REASON_FUSED);
    }

    @Override
    public boolean dimensionLoaded(String dimension) {
        return read("dimensionLoaded", true, () -> level(dimension) != null);
    }

    @Override
    public Optional<ClaimHandle> findDistrictClaim(String dimension, UUID claimId) {
        return read("findDistrictClaim", Optional.empty(), () -> {
            Claim claim = resolve(new ClaimHandle(dimension, claimId, null));
            return claim == null ? Optional.empty() : Optional.of(handleOf(dimension, claim));
        });
    }

    @Override
    public Optional<ClaimHandle> districtClaimAt(String dimension, int x, int z) {
        return read("districtClaimAt", Optional.empty(), () -> {
            ServerLevel level = level(dimension);
            if (level == null) {
                return Optional.empty();
            }
            for (Claim claim : List.copyOf(ClaimStorage.get(level).getClaimsAt(x >> 4, z >> 4))) {
                ClaimBox box = claim.getDimensions();
                if (!claim.isRemoved() && box.minX() <= x && x <= box.maxX() && box.minZ() <= z && z <= box.maxZ()) {
                    return Optional.of(handleOf(dimension, claim));
                }
            }
            return Optional.empty();
        });
    }

    @Override
    public List<ClaimHandle> claimsIntersecting(String dimension, DistrictBounds bounds) {
        return read("claimsIntersecting", List.of(), () -> {
            ServerLevel level = level(dimension);
            if (level == null) {
                return List.of();
            }
            PlotArea area = bounds.asArea();
            List<ClaimHandle> found = new ArrayList<>();
            // getClaims() 是活的 playerClaimMap: 先复制再遍历。null 键下是管理员领地。
            for (Set<Claim> owned : List.copyOf(ClaimStorage.get(level).getClaims().values())) {
                for (Claim claim : List.copyOf(owned)) {
                    if (!claim.isRemoved() && areaOf(claim.getDimensions()).overlaps(area)) {
                        found.add(handleOf(dimension, claim));
                    }
                }
            }
            return found;
        });
    }

    /**
     * 与 {@link #claimsIntersecting} 同法 (22.20): 复制 getClaims() 里全部非 null 键 (玩家领地; null 键下是管理员领地) 下的
     * 领地, 逐块比 X/Z。只读。
     */
    @Override
    public List<PersonalClaim> personalClaimsIntersecting(String dimension, PlotArea area) {
        return read("personalClaimsIntersecting", List.of(), () -> {
            ServerLevel level = level(dimension);
            if (level == null) {
                return List.of();
            }
            List<PersonalClaim> found = new ArrayList<>();
            for (Map.Entry<UUID, Set<Claim>> owned : List.copyOf(ClaimStorage.get(level).getClaims().entrySet())) {
                if (owned.getKey() == null) {
                    continue;
                }
                for (Claim claim : List.copyOf(owned.getValue())) {
                    ClaimBox box = claim.getDimensions();
                    UUID owner = claim.getOwner();
                    if (!claim.isRemoved() && !claim.isAdminClaim() && owner != null
                            && areaOf(box).overlaps(area)) {
                        found.add(new PersonalClaim(handleOf(dimension, claim), owner, areaOf(box), !claim.is3d(),
                                box.minY(), box.maxY()));
                    }
                }
            }
            return found;
        });
    }

    @Override
    public Optional<ClaimInfo> inspectClaim(ClaimHandle handle) {
        return read("inspectClaim", Optional.empty(), () -> {
            Claim claim = resolve(handle);
            ServerLevel level = level(handle.dimension());
            if (claim == null || level == null) {
                return Optional.empty();
            }
            ClaimBox box = claim.getDimensions();
            return Optional.of(info(handle, claim, level, box));
        });
    }

    /**
     * 玩家领地的 getClaimName() 要经玩家名缓存把主人的名字格式化进去 (ClaimUtils.fetchUsername)。没有玩家名缓存的服务端
     * (GameTest 服务端) 上它直接空指针: 本模块从不管玩家领地的名字 (只要"不是管理员领地"这一条), 读不到就当空串, 不让
     * 整个 inspectClaim 失败。管理员领地与它的子领地不查缓存 (主人名固定为 "Admin"), 照常读。
     */
    private static ClaimInfo info(ClaimHandle handle, Claim claim, ServerLevel level, ClaimBox box) {
        String name;
        if (claim.isAdminClaim()) {
            name = claim.getClaimName();
        } else {
            try {
                name = claim.getClaimName();
            } catch (RuntimeException noProfileCache) {
                name = "";
            }
        }
        int allowListEntries = 0;
        for (AllowedRegistryList<?> list : allowLists(claim)) {
            allowListEntries += list.size();
        }
        return new ClaimInfo(handleOf(handle.dimension(), claim), box.minX(), box.minZ(), box.maxX(), box.maxZ(),
                box.minY(), level.getMinBuildHeight(), !claim.is3d(), claim.isAdminClaim(), name,
                claim.getAllowedFakePlayerUUID().size(), claim.getPotions().size(), allowListEntries);
    }

    /**
     * 六张放行清单。Flan 的放方块、拆方块、用方块、用物品、打实体、用实体几个事件在顶层领地上先查它们, 命中即放行, 根本
     * 不走 canInteract —— 父领地上的一条 minecraft:chest 会让全区每块地的箱子 (冻结的也算) 谁都能开 (20.3)。
     */
    private static List<AllowedRegistryList<?>> allowLists(Claim claim) {
        return List.of(claim.allowedItems, claim.allowedUseBlocks, claim.allowedPlaceBlocks, claim.allowedBreakBlocks,
                claim.allowedEntityAttack, claim.allowedEntityUse);
    }

    @Override
    public Optional<ClaimHandle> findPlotClaim(ClaimHandle district, UUID plotClaimId) {
        return read("findPlotClaim", Optional.empty(), () -> {
            Claim claim = resolve(new ClaimHandle(district.dimension(), plotClaimId, district.claimId()));
            return claim == null ? Optional.empty() : Optional.of(handleOf(district.dimension(), claim));
        });
    }

    @Override
    public List<ClaimHandle> listPlotClaims(ClaimHandle district) {
        return read("listPlotClaims", List.of(), () -> {
            Claim parent = resolve(district);
            if (parent == null) {
                return List.of();
            }
            List<ClaimHandle> children = new ArrayList<>();
            for (Claim sub : parent.getAllSubclaims()) {
                if (!sub.isRemoved()) {
                    children.add(handleOf(district.dimension(), sub));
                }
            }
            return children;
        });
    }

    @Override
    public Map<UUID, String> readMembers(ClaimHandle handle) {
        return read("readMembers", Map.of(), () -> {
            Claim claim = resolve(handle);
            return claim == null ? Map.of() : new LinkedHashMap<>(reflection.playersGroups(claim));
        });
    }

    @Override
    public ClaimPermissionSnapshot readPermissions(ClaimHandle handle) {
        return read("readPermissions", new ClaimPermissionSnapshot(Map.of(), Map.of()), () -> {
            Claim claim = resolve(handle);
            if (claim == null) {
                return new ClaimPermissionSnapshot(Map.of(), Map.of());
            }
            List<KnownPermission> table = permissions.table();
            Map<String, Map<String, Boolean>> groups = new LinkedHashMap<>();
            for (String group : claim.groups()) {
                Map<String, Boolean> values = new LinkedHashMap<>();
                for (KnownPermission permission : table) {
                    int value = claim.groupHasPerm(group, new ResourceLocation(permission.id()));
                    if (value != -1) {
                        values.put(permission.id(), value == 1);
                    }
                }
                groups.put(group, values);
            }
            Map<String, Boolean> defaults = new LinkedHashMap<>();
            for (KnownPermission permission : table) {
                int value = claim.permEnabled(new ResourceLocation(permission.id()));
                if (value != -1) {
                    defaults.put(permission.id(), value == 1);
                }
            }
            return new ClaimPermissionSnapshot(groups, defaults);
        });
    }

    @Override
    public List<KnownPermission> permissionTable() {
        return read("permissionTable", FlanPermissions.BUILTIN, permissions::table);
    }

    @Override
    public long permissionTableVersion() {
        return read("permissionTableVersion", 0L, permissions::version);
    }

    @Override
    public void onPermissionsReloaded() {
        permissions.invalidate();
    }

    // ================================================================
    // 自管区父领地
    // ================================================================

    /**
     * createAdminClaim, 两角的 Y 都取世界底: 深度下探被压到世界底, 结果是全高 2D。Flan 的 defaultGroups (Co-Owner 有
     * 全部权限含 edit_*) 只在 Claim 的构造里套用: 这个 public 非 final 的字段临时换成空 Map, finally 里换回 (换字段
     * 与 /flan reload 的 Config.load() 都在服务器线程上, 不会穿插)。返回 null 表示与某块顶层领地重叠。
     */
    @Override
    public FlanResult<ClaimHandle> createDistrictClaim(String dimension, DistrictBounds bounds, String name) {
        return write("createDistrictClaim", () -> {
            ServerLevel level = level(dimension);
            if (level == null) {
                return noLevel(dimension);
            }
            String blocked = beforeWrite(level, dimension);
            if (blocked != null) {
                return FlanResult.failure(blocked);
            }
            ClaimStorage storage = ClaimStorage.get(level);
            Config config = ConfigHandler.CONFIG;
            Map<String, Map<ResourceLocation, Boolean>> saved = config.defaultGroups;
            Claim claim;
            config.defaultGroups = new HashMap<>();
            try {
                claim = storage.createAdminClaim(lowCorner(level, bounds.minX(), bounds.minZ()),
                        lowCorner(level, bounds.maxX(), bounds.maxZ()), level, false);
            } finally {
                config.defaultGroups = saved;
            }
            if (claim == null) {
                return FlanResult.failure("和已有的领地重叠");
            }
            // 领地此刻已经进了 ClaimStorage: 之后任何一步出错都不能再报失败 —— 报失败的话库里不记它的 id, 下一次又去
            // 建、永远"重叠"。起名与删组交给随后的整块写入与对账补齐 (它们按库比较名字与组)。
            try {
                claim.setClaimName(DistrictTexts.claimName(name));
                if (!claim.groups().isEmpty()) {
                    // 不该发生 (默认组已换空); 万一有, 当场删掉, 父领地上绝不留别的组。
                    LOGGER.warn("[miningdim] district: new admin claim {} came with groups {}; removing them",
                            claim.getClaimID(), claim.groups());
                    reflection.permissions(claim).clear();
                    claim.setDirty(true);
                }
            } catch (RuntimeException afterCreate) {
                LOGGER.warn("[miningdim] district: naming or clearing the new admin claim {} failed; the next write "
                        + "and the reconciliation finish it", claim.getClaimID(), afterCreate);
            }
            return FlanResult.success(handleOf(dimension, claim));
        });
    }

    @Override
    public FlanResult<Void> extendDistrictClaimToBottom(ClaimHandle district) {
        return write("extendDistrictClaimToBottom", () -> {
            ServerLevel level = level(district.dimension());
            Claim claim = resolve(district);
            if (level == null || claim == null || claim.isSubclaim()) {
                return noClaim();
            }
            if (claim.is3d()) {
                return FlanResult.failure("这是 3D 领地，补不到世界底；请用 /flan 删掉后按 2D 重新圈地");
            }
            ClaimBox box = claim.getDimensions();
            int bottom = level.getMinBuildHeight();
            if (box.minY() <= bottom) {
                return FlanResult.success();
            }
            String blocked = beforeWrite(level, district.dimension());
            if (blocked != null) {
                return FlanResult.failure(blocked);
            }
            // Flan 自己放方块时也这么调 (ItemInteractEvents): 只降低 2D 领地的 minY、标脏、通知网页地图, 不动 X/Z 与区块索引。
            claim.extendDownwards(new BlockPos(box.minX(), bottom, box.minZ()));
            return claim.getDimensions().minY() <= bottom
                    ? FlanResult.success()
                    : FlanResult.failure("Flan 没有把领地的底补到世界底");
        });
    }

    /** 不改领地, 只核对范围是否一致 (改范围走金锄头 + /district bounds sync, 20.6)。 */
    @Override
    public FlanResult<Void> resizeDistrictClaim(ClaimHandle district, DistrictBounds bounds) {
        return write("resizeDistrictClaim", () -> {
            Claim claim = resolve(district);
            if (claim == null) {
                return noClaim();
            }
            return sameXz(claim.getDimensions(), bounds.asArea())
                    ? FlanResult.success()
                    : FlanResult.failure("领地范围和库里不一致，请用金锄头改好后执行 /district bounds sync");
        });
    }

    // ================================================================
    // 地块
    // ================================================================

    /** 地块范围的预检: 在父领地的 X/Z 内, 且不压兄弟地块 (Flan 只查兄弟重叠, 不查包含)。 */
    @Nullable
    private static String checkPlotArea(Claim parent, @Nullable Claim self, PlotArea area) {
        if (!area.wellFormed()) {
            return "地块范围不合法";
        }
        if (!areaOf(parent.getDimensions()).contains(area)) {
            return "地块超出自管区的领地范围";
        }
        for (Claim sibling : parent.getAllSubclaims()) {
            if (sibling != self && !sibling.isRemoved() && areaOf(sibling.getDimensions()).overlaps(area)) {
                return "和同一自管区里的另一块子领地重叠";
            }
        }
        return null;
    }

    /**
     * tryCreateSubClaim (空集即成功), 新地块是 getAllSubclaims() 的最后一个; 然后清理: 删掉继承来的全部组键 (反射,
     * 它们与父领地共用内层 Map)、移出复制来的成员、删掉复制来的药水, 设名字、标脏; 再关上 (见 {@link #close}): 本块的
     * 三个 p_ 组与地块默认对全部非全局权限显式为假, 全局权限一律没有键。任一步失败 → deleteSubClaim 删掉这块半成品再报
     * 失败, 绝不留下带着继承组、或者还开着的地块。
     */
    @Override
    public FlanResult<ClaimHandle> createPlotClaim(ClaimHandle district, PlotArea area, String name, String plotId) {
        return write("createPlotClaim", () -> {
            ServerLevel level = level(district.dimension());
            Claim parent = resolve(district);
            if (level == null || parent == null || parent.isSubclaim()) {
                return FlanResult.failure("自管区领地不存在");
            }
            String problem = checkPlotArea(parent, null, area);
            if (problem != null) {
                return FlanResult.failure(problem);
            }
            String blocked = beforeWrite(level, district.dimension());
            if (blocked != null) {
                return FlanResult.failure(blocked);
            }
            Set<Claim> conflicts = parent.tryCreateSubClaim(lowCorner(level, area.minX(), area.minZ()),
                    lowCorner(level, area.maxX(), area.maxZ()), false);
            if (!conflicts.isEmpty()) {
                return FlanResult.failure("和同一自管区里的另一块子领地重叠");
            }
            List<Claim> subs = parent.getAllSubclaims();
            Claim plot = subs.get(subs.size() - 1);
            try {
                if (!sameXz(plot.getDimensions(), area)) {
                    throw new IllegalStateException("the new subclaim covers " + plot.getDimensions()
                            + " instead of " + area);
                }
                scrub(plot, name);
                close(plot, plotId);
            } catch (RuntimeException | LinkageError failure) {
                parent.deleteSubClaim(plot);
                LOGGER.error("[miningdim] district: scrubbing new plot claim {} failed; deleted it again",
                        plot.getClaimID(), failure);
                if (failure instanceof LinkageError linkage) {
                    throw linkage;
                }
                return FlanResult.failure("清理新地块失败，已删掉这块地的领地（" + failure.getClass().getSimpleName()
                        + "）");
            }
            return FlanResult.success(handleOf(district.dimension(), plot));
        });
    }

    private void scrub(Claim plot, String name) {
        Map<String, Map<ResourceLocation, Boolean>> groups = reflection.permissions(plot);
        for (String key : List.copyOf(groups.keySet())) {
            groups.remove(key);
        }
        for (UUID member : List.copyOf(reflection.playersGroups(plot).keySet())) {
            plot.setPlayerGroup(member, null, true);
        }
        for (MobEffect effect : List.copyOf(plot.getPotions().keySet())) {
            plot.removePotion(effect);
        }
        plot.setClaimName(DistrictTexts.claimName(name));
        plot.setDirty(true);
        if (!plot.groups().isEmpty() || !reflection.playersGroups(plot).isEmpty() || !plot.getPotions().isEmpty()) {
            throw new IllegalStateException("plot " + plot.getClaimID() + " still carries inherited state after "
                    + "scrubbing");
        }
    }

    /**
     * 新地块的"关闭"状态 (与冻结相同): 清理完的子领地没有组, globalPerm 里只有构造器按出厂值预填的真值 (can_stay、
     * pickup、enderchest… 与全局的 enderman、lock_items、snow_golem), 其余权限一律落到父领地的默认 (公共区域的外人列)。
     * 在库里记下它的 id、按差异写好之前, 这样的地块对外人是开着的。这里先把本块的三个 p_ 组建好、对全部非全局权限写假,
     * 地块默认同样全假, 全局权限一律去掉键 (跟随父领地); 之后的差异写入只"放"该放的格子, 对账也能按组名认出它。
     */
    private void close(Claim plot, String plotId) {
        List<KnownPermission> table = permissions.table();
        for (String group : List.of(FlanGroupNames.plotOwner(plotId), FlanGroupNames.plotFriend(plotId),
                FlanGroupNames.plotResident(plotId))) {
            for (KnownPermission permission : table) {
                if (!permission.global()
                        && !plot.editPerms(null, group, new ResourceLocation(permission.id()), 0, true)) {
                    throw new IllegalStateException("Flan refused closing " + group + " " + permission.id());
                }
            }
        }
        for (KnownPermission permission : table) {
            ResourceLocation id = new ResourceLocation(permission.id());
            int mode = permission.global() ? -1 : 0;
            if (plot.permEnabled(id) != mode && !plot.editGlobalPerms(null, id, mode)) {
                throw new IllegalStateException("Flan refused closing the default " + permission.id());
            }
        }
    }

    /** 范围相同就不动; 否则 copySizes(new Claim(两角, null, level)) (子领地不在区块索引里, 不用重排)。 */
    @Override
    public FlanResult<Void> resizePlotClaim(ClaimHandle plot, PlotArea area) {
        return write("resizePlotClaim", () -> {
            ServerLevel level = level(plot.dimension());
            Claim claim = resolve(plot);
            if (level == null || claim == null || claim.parentClaim() == null) {
                return FlanResult.failure("子领地不存在");
            }
            if (sameXz(claim.getDimensions(), area)) {
                return FlanResult.success();
            }
            String problem = checkPlotArea(claim.parentClaim(), claim, area);
            if (problem != null) {
                return FlanResult.failure(problem);
            }
            String blocked = beforeWrite(level, plot.dimension());
            if (blocked != null) {
                return FlanResult.failure(blocked);
            }
            claim.copySizes(new Claim(lowCorner(level, area.minX(), area.minZ()),
                    lowCorner(level, area.maxX(), area.maxZ()), null, level));
            return FlanResult.success();
        });
    }

    @Override
    public FlanResult<Void> deletePlotClaim(ClaimHandle plot) {
        return write("deletePlotClaim", () -> {
            ServerLevel level = level(plot.dimension());
            Claim claim = resolve(plot);
            if (level == null || claim == null || claim.parentClaim() == null) {
                return FlanResult.failure("子领地不存在");
            }
            String blocked = beforeWrite(level, plot.dimension());
            if (blocked != null) {
                return FlanResult.failure(blocked);
            }
            return claim.parentClaim().deleteSubClaim(claim)
                    ? FlanResult.success()
                    : FlanResult.failure("Flan 没有删掉这块子领地");
        });
    }

    // ================================================================
    // 组、默认、成员
    // ================================================================

    /** 组名守卫: 父领地只许 d_ 组, 地块只许 p_ 组。 */
    @Nullable
    private static String groupProblem(Claim claim, String group) {
        if (claim.isSubclaim()) {
            return group.startsWith(FlanGroupNames.PLOT_PREFIX) ? null : "地块上只许写本模块的 p_ 组，拒绝写 " + group;
        }
        return group.startsWith(FlanGroupNames.DISTRICT_PREFIX) ? null : "自管区上只许写本区居民组，拒绝写 " + group;
    }

    private static int mode(PermValue value) {
        return switch (value) {
            case TRUE -> 1;
            case FALSE -> 0;
            case UNSET -> -1;
        };
    }

    @Override
    public FlanResult<Void> setGroupPermission(ClaimHandle handle, String group, String flanPermId, PermValue value) {
        return write("setGroupPermission", () -> {
            ServerLevel level = level(handle.dimension());
            Claim claim = resolve(handle);
            if (level == null || claim == null) {
                return noClaim();
            }
            String problem = groupProblem(claim, group);
            if (problem != null) {
                return FlanResult.failure(problem);
            }
            if (FlanPermissions.admin(flanPermId) && value == PermValue.TRUE) {
                return FlanResult.failure("管理类权限 " + flanPermId + " 不许写真");
            }
            KnownPermission permission = known(flanPermId);
            if (permission == null) {
                return FlanResult.failure("Flan 权限表里没有 " + flanPermId);
            }
            if (permission.global()) {
                return FlanResult.failure(flanPermId + " 是全局权限，不能按组写");
            }
            ResourceLocation id = new ResourceLocation(flanPermId);
            if (claim.groupHasPerm(group, id) == mode(value)) {
                return FlanResult.success();
            }
            String blocked = beforeWrite(level, handle.dimension());
            if (blocked != null) {
                return FlanResult.failure(blocked);
            }
            return claim.editPerms(null, group, id, mode(value), true)
                    ? FlanResult.success()
                    : FlanResult.failure("Flan 拒绝了这次写入（" + group + " " + flanPermId + "）");
        });
    }

    @Override
    public FlanResult<Void> setDefaultPermission(ClaimHandle handle, String flanPermId, PermValue value) {
        return write("setDefaultPermission", () -> {
            ServerLevel level = level(handle.dimension());
            Claim claim = resolve(handle);
            if (level == null || claim == null) {
                return noClaim();
            }
            if (FlanPermissions.admin(flanPermId) && value == PermValue.TRUE) {
                return FlanResult.failure("管理类权限 " + flanPermId + " 不许写真");
            }
            if (known(flanPermId) == null) {
                return FlanResult.failure("Flan 权限表里没有 " + flanPermId);
            }
            ResourceLocation id = new ResourceLocation(flanPermId);
            int current = claim.permEnabled(id);
            // 顶层领地存盘时 GlobalPerms 只存值为真的 id: 假与缺省判定相同, 视为一致。
            boolean same = current == mode(value) || (!claim.isSubclaim() && value == PermValue.FALSE && current == -1);
            if (same) {
                return FlanResult.success();
            }
            String blocked = beforeWrite(level, handle.dimension());
            if (blocked != null) {
                return FlanResult.failure(blocked);
            }
            return claim.editGlobalPerms(null, id, mode(value))
                    ? FlanResult.success()
                    : FlanResult.failure("Flan 拒绝了这次写入（默认 " + flanPermId + "）");
        });
    }

    /** 反射删组键, 再把这个组的成员逐个移出, 最后自己标脏 (直接改私有字段不会标脏)。 */
    @Override
    public FlanResult<Void> deleteGroup(ClaimHandle handle, String group) {
        return write("deleteGroup", () -> {
            ServerLevel level = level(handle.dimension());
            Claim claim = resolve(handle);
            if (level == null || claim == null) {
                return noClaim();
            }
            Map<String, Map<ResourceLocation, Boolean>> groups = reflection.permissions(claim);
            List<UUID> members = new ArrayList<>();
            reflection.playersGroups(claim).forEach((member, memberGroup) -> {
                if (group.equals(memberGroup)) {
                    members.add(member);
                }
            });
            if (!groups.containsKey(group) && members.isEmpty()) {
                return FlanResult.success();
            }
            String blocked = beforeWrite(level, handle.dimension());
            if (blocked != null) {
                return FlanResult.failure(blocked);
            }
            groups.remove(group);
            for (UUID member : members) {
                claim.setPlayerGroup(member, null, true);
            }
            claim.setDirty(true);
            return FlanResult.success();
        });
    }

    @Override
    public FlanResult<Void> setMember(ClaimHandle handle, UUID player, @Nullable String group) {
        return write("setMember", () -> {
            ServerLevel level = level(handle.dimension());
            Claim claim = resolve(handle);
            if (level == null || claim == null) {
                return noClaim();
            }
            if (group != null) {
                String problem = groupProblem(claim, group);
                if (problem != null) {
                    return FlanResult.failure(problem);
                }
            }
            String current = reflection.playersGroups(claim).get(player);
            if (group == null ? current == null : group.equals(current)) {
                return FlanResult.success();
            }
            String blocked = beforeWrite(level, handle.dimension());
            if (blocked != null) {
                return FlanResult.failure(blocked);
            }
            return claim.setPlayerGroup(player, group, true)
                    ? FlanResult.success()
                    : FlanResult.failure("Flan 拒绝了这次成员写入");
        });
    }

    /**
     * modifyFakePlayerUUID(uuid, true) 是移除且不标脏; 药水逐个 removePotion; 放行清单从尾部逐条 removeAllowedItem(int)
     * (公开、会标脏; 不用 read(new JsonArray()): 它只清列表不清内部的名字索引, 之后 addAllowedItem 会把同名条目悄悄
     * 拒掉; 从尾部删则不会让索引里其余条目的下标错位); 最后自己标脏。
     */
    @Override
    public FlanResult<Void> clearExtras(ClaimHandle handle) {
        return write("clearExtras", () -> {
            ServerLevel level = level(handle.dimension());
            Claim claim = resolve(handle);
            if (level == null || claim == null) {
                return noClaim();
            }
            List<String> fakePlayers = claim.getAllowedFakePlayerUUID();
            List<AllowedRegistryList<?>> lists = allowLists(claim);
            boolean anyAllowed = lists.stream().anyMatch(list -> list.size() > 0);
            if (fakePlayers.isEmpty() && claim.getPotions().isEmpty() && !anyAllowed) {
                return FlanResult.success();
            }
            String blocked = beforeWrite(level, handle.dimension());
            if (blocked != null) {
                return FlanResult.failure(blocked);
            }
            for (String fake : List.copyOf(fakePlayers)) {
                claim.modifyFakePlayerUUID(UUID.fromString(fake), true);
            }
            for (MobEffect effect : List.copyOf(claim.getPotions().keySet())) {
                claim.removePotion(effect);
            }
            for (AllowedRegistryList<?> list : lists) {
                while (list.size() > 0) {
                    int before = list.size();
                    list.removeAllowedItem(before - 1);
                    if (list.size() >= before) {
                        throw new IllegalStateException("Flan did not remove an allow-list entry of claim "
                                + claim.getClaimID());
                    }
                }
            }
            claim.setDirty(true);
            return FlanResult.success();
        });
    }

    @Override
    public FlanResult<Void> setClaimName(ClaimHandle handle, String name) {
        return write("setClaimName", () -> {
            ServerLevel level = level(handle.dimension());
            Claim claim = resolve(handle);
            if (level == null || claim == null) {
                return noClaim();
            }
            String clean = DistrictTexts.claimName(name);
            if (clean.equals(claim.getClaimName())) {
                return FlanResult.success();
            }
            String blocked = beforeWrite(level, handle.dimension());
            if (blocked != null) {
                return FlanResult.failure(blocked);
            }
            claim.setClaimName(clean);
            return FlanResult.success();
        });
    }

    // ================================================================
    // 备份与存盘
    // ================================================================

    @Override
    public Optional<String> backupStatus(String dimension) {
        ClaimBackupFiles files = backups.files();
        String latest = files.latest(dimension);
        return Optional.of(files.directory(dimension) + (latest == null ? " (none yet)" : " -> " + latest));
    }

    @Override
    public FlanResult<String> backupNow(String dimension, String reason) {
        return write("backupNow", () -> {
            ServerLevel level = level(dimension);
            if (level == null) {
                return noLevel(dimension);
            }
            return backups.backup(level, dimension, reason);
        });
    }

    /** 显式存盘 (只在建、绑、重建父领地之后): /save-off 期间 (level.noSave) 跳过, 不在外部备份的窗口里写盘。 */
    @Override
    public void flush(String dimension) {
        FlanResult<Void> ignored = write("flush", () -> {
            ServerLevel level = level(dimension);
            if (level == null) {
                return noLevel(dimension);
            }
            if (!level.noSave) {
                ClaimStorage.get(level).save(server, level.dimension());
            }
            return FlanResult.success();
        });
        if (!ignored.ok()) {
            LOGGER.warn("[miningdim] district: explicit Flan save of {} failed: {}", dimension, ignored.error());
        }
    }
}
