package com.miningdim.district.flan;

import com.miningdim.district.core.DistrictBounds;
import com.miningdim.district.core.PlotArea;
import net.minecraft.gametest.framework.GameTestServer;
import net.minecraftforge.server.ServerLifecycleHooks;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;

/**
 * 记录型假网关 (设计文档 8.5), 只给 GameTest 用; 阶段 2 起真 Flan 另有 {@code flan.real.FlanClaimGateway}, 这里
 * 仍用于纯逻辑测试 (推送、按差异写、对账、命令)。
 *
 * <p>只在 GameTest 服务端 ({@link GameTestServer}, 即 runGameTestServer) 上能造出来, 别处构造直接抛
 * IllegalStateException: 它对一切写入都报成功, 又在不变式破坏时抛 {@link AssertionError} —— 在专用服务器或单人
 * 存档里被选上, 住户会以为领地权限已经生效, 一次不变式断言还会顺着主线程把服务器带崩。门槛与登录门的
 * {@code forceVerdictForTest}、{@code GameTestConfigWatchGuard} 同一口径 (卡服务端类型, 不卡
 * forge.enabledGameTestNamespaces: client/server 两个 run 也设了它)。
 *
 * <ul>
 *   <li>内存领地模型: 自管区、子领地、组、成员、默认权限、假玩家白名单、药水。新领地照 Flan 的构造器预填出厂值为真
 *       的默认权限; 建子领地时照搬 Flan 的浅拷贝语义 (共用内层 Map、复制成员与药水快照), 再按接口契约清理干净;
 *       继承来的组名记在一旁, 之后谁往它写就当场报错。</li>
 *   <li>调用记录: {@link #calls()} 按顺序列出每一次调用 (op、领地 id、字符串化的参数)。</li>
 *   <li>故障注入: {@link #failWhen} 持续生效, {@link #failNext} 只对下一次同名调用生效。被注入失败的写入不改模型。</li>
 *   <li>不变式断言: 给管理类写真、往组里写全局权限、往父领地写非 d_ 组、往地块写非 p_ 组或继承来的组, 一律抛
 *       {@link AssertionError}, 让 GameTest 当场失败 —— 这些 bug 在真 Flan 上会把领地设置交出去, 或让改一块地
 *       连带改掉全区。</li>
 *   <li>模拟"有人用了 /flan 或金锄头": {@code drift*} 系列绕过守卫直接改模型, 供对账测试造漂移。</li>
 * </ul>
 * 一个玩家在一块领地里只能在一个组, 由成员表 (UUID -> 组) 的结构本身保证, 与 Flan 的 playersGroups 同构。
 */
public final class RecordingFlanGateway implements FlanGateway {

    /** 一次网关调用。args 全部字符串化, 方便写匹配条件。 */
    public record FlanCall(String op, @Nullable UUID claimId, List<String> args) {

        public FlanCall {
            args = List.copyOf(args);
        }

        public boolean hasArg(String value) {
            return args.contains(value);
        }
    }

    /** 与真网关同一个世界底 (主世界 1.20.1)。 */
    public static final int WORLD_MIN_Y = -64;

    /** 主世界 1.20.1 的世界顶 (2D 领地的 maxY)。 */
    public static final int WORLD_MAX_Y = 320;

    private static final class Claim {
        private final UUID id;
        private final String dimension;
        @Nullable
        private final UUID parentId;
        private String name;
        private PlotArea box;
        private boolean admin = true;
        /** 玩家领地的主人 (管理员领地为 null)。 */
        @Nullable
        private UUID owner;
        /** 3D 领地的顶 (2D 领地为世界顶)。 */
        private int maxY = WORLD_MAX_Y;
        private final Map<String, Map<String, Boolean>> groups = new LinkedHashMap<>();
        private final Set<String> inheritedGroups = new HashSet<>();
        private final Map<UUID, String> members = new LinkedHashMap<>();
        private final Map<String, Boolean> defaults = new LinkedHashMap<>();
        private final Set<UUID> fakePlayers = new LinkedHashSet<>();
        private final Map<String, Integer> potions = new LinkedHashMap<>();
        private final List<UUID> children = new ArrayList<>();
        /** 领地的底 (新建的一律在世界底, 全高)。 */
        private int minY = WORLD_MIN_Y;
        /** 2D (Flan 的 !is3d())。 */
        private boolean flat = true;
        /** 六张放行清单的条目合计。 */
        private int allowListEntries;

        private Claim(UUID id, String dimension, @Nullable UUID parentId, String name, PlotArea box,
                      List<KnownPermission> table) {
            this.id = id;
            this.dimension = dimension;
            this.parentId = parentId;
            this.name = name;
            this.box = box;
            // Flan 的构造器: globalPerm 预填出厂值为真的那些 (non-global 7 个 + 全局 3 个)。
            for (KnownPermission permission : table) {
                if (permission.defaultValue()) {
                    defaults.put(permission.id(), true);
                }
            }
        }

        private ClaimHandle handle() {
            return new ClaimHandle(dimension, id, parentId);
        }
    }

    private record FailureRule(Predicate<FlanCall> when, String error) {
    }

    private final Map<UUID, Claim> claims = new LinkedHashMap<>();
    private final List<FlanCall> calls = new ArrayList<>();
    private final List<FailureRule> failures = new ArrayList<>();
    private final Map<String, Deque<String>> failNext = new HashMap<>();
    private boolean scrubOnCreate = true;
    private List<KnownPermission> table = FlanPermissions.BUILTIN;
    private long tableVersion = 1;
    private int backups;

    /** 只在 GameTest 服务端上可用 (见类注释)。 */
    public RecordingFlanGateway() {
        if (!(ServerLifecycleHooks.getCurrentServer() instanceof GameTestServer)) {
            throw new IllegalStateException("RecordingFlanGateway is a GameTest fake and is only available on the "
                    + "GameTest server");
        }
    }

    // ================================================================
    // 测试用的控制与查询
    // ================================================================

    public List<FlanCall> calls() {
        return List.copyOf(calls);
    }

    public List<FlanCall> callsOf(String op) {
        List<FlanCall> matched = new ArrayList<>();
        for (FlanCall call : calls) {
            if (call.op().equals(op)) {
                matched.add(call);
            }
        }
        return matched;
    }

    /** 清空调用记录 (不动模型与故障注入)。 */
    public void clear() {
        calls.clear();
    }

    /** 此后每一次满足条件的调用都以 error 失败, 直到 {@link #clearFailures}。 */
    public void failWhen(Predicate<FlanCall> when, String error) {
        failures.add(new FailureRule(when, error));
    }

    /** 下一次名为 op 的调用以 error 失败 (可叠加多次)。 */
    public void failNext(String op, String error) {
        failNext.computeIfAbsent(op, ignored -> new ArrayDeque<>()).add(error);
    }

    public void clearFailures() {
        failures.clear();
        failNext.clear();
    }

    /** 只供不变式测试: 建子领地后不做清理, 模拟"忘了清理继承组"的真实现。 */
    public void disableScrubForTest() {
        scrubOnCreate = false;
    }

    /** 模拟 /reload 换了权限表 (新对象、版本加一)。 */
    public void replacePermissionTableForTest(List<KnownPermission> newTable) {
        table = List.copyOf(newTable);
        tableVersion++;
    }

    public boolean exists(UUID claimId) {
        return claims.containsKey(claimId);
    }

    public Map<UUID, String> membersOf(UUID claimId) {
        Claim claim = claims.get(claimId);
        return claim == null ? Map.of() : Map.copyOf(claim.members);
    }

    public Set<String> groupsOf(UUID claimId) {
        Claim claim = claims.get(claimId);
        return claim == null ? Set.of() : Set.copyOf(claim.groups.keySet());
    }

    /** 某组某权限的值; 没设置为 null。 */
    @Nullable
    public Boolean groupPerm(UUID claimId, String group, String flanPermId) {
        Claim claim = claims.get(claimId);
        if (claim == null) {
            return null;
        }
        Map<String, Boolean> perms = claim.groups.get(group);
        return perms == null ? null : perms.get(flanPermId);
    }

    /** 默认 (全局) 权限的值; 没设置为 null。 */
    @Nullable
    public Boolean defaultPerm(UUID claimId, String flanPermId) {
        Claim claim = claims.get(claimId);
        return claim == null ? null : claim.defaults.get(flanPermId);
    }

    @Nullable
    public PlotArea areaOf(UUID claimId) {
        Claim claim = claims.get(claimId);
        return claim == null ? null : claim.box;
    }

    /** 某领地的子领地 id (按建立顺序)。 */
    public List<UUID> childrenOf(UUID claimId) {
        Claim claim = claims.get(claimId);
        return claim == null ? List.of() : List.copyOf(claim.children);
    }

    @Nullable
    public String nameOf(UUID claimId) {
        Claim claim = claims.get(claimId);
        return claim == null ? null : claim.name;
    }

    public int fakePlayerCount(UUID claimId) {
        Claim claim = claims.get(claimId);
        return claim == null ? 0 : claim.fakePlayers.size();
    }

    public int potionCount(UUID claimId) {
        Claim claim = claims.get(claimId);
        return claim == null ? 0 : claim.potions.size();
    }

    public int backupCount() {
        return backups;
    }

    // ---- 模拟 /flan 与金锄头 (绕过守卫, 不记调用) ----

    /** 一块已有的管理员领地, 带着 Flan 默认的 Co-Owner 与 Visitor 两个组 (绑定测试用)。 */
    public UUID driftAdminClaim(String dimension, PlotArea box, String name) {
        Claim claim = new Claim(UUID.randomUUID(), dimension, null, name, box, table);
        claim.groups.put("Co-Owner", new HashMap<>(Map.of("flan:edit_claim", true, "flan:break", true)));
        claim.groups.put("Visitor", new HashMap<>(Map.of("flan:door", true)));
        claims.put(claim.id, claim);
        return claim.id;
    }

    /** 一块玩家领地 (不是管理员领地), 主人随机。 */
    public UUID driftPlayerClaim(String dimension, PlotArea box) {
        return driftPlayerClaim(dimension, box, UUID.randomUUID());
    }

    /** 一块属于 owner 的玩家领地 (不是管理员领地)。 */
    public UUID driftPlayerClaim(String dimension, PlotArea box, UUID owner) {
        Claim claim = new Claim(UUID.randomUUID(), dimension, null, "", box, table);
        claim.admin = false;
        claim.owner = owner;
        claims.put(claim.id, claim);
        return claim.id;
    }

    /** OP 用金锄头在子领地模式下手划的一块子领地 (不清理, 带着继承的组与成员)。 */
    public UUID driftSubclaim(UUID parentId, PlotArea box) {
        Claim parent = claims.get(parentId);
        if (parent == null) {
            throw new IllegalArgumentException("no claim " + parentId);
        }
        Claim plot = new Claim(UUID.randomUUID(), parent.dimension, parent.id, "", box, table);
        plot.groups.putAll(parent.groups);
        plot.inheritedGroups.addAll(parent.groups.keySet());
        plot.members.putAll(parent.members);
        claims.put(plot.id, plot);
        parent.children.add(plot.id);
        return plot.id;
    }

    /** 直接改一格组权限 (value 为 null = 删掉这个键); 组不存在就建。 */
    public void driftGroupPerm(UUID claimId, String group, String flanPermId, @Nullable Boolean value) {
        Map<String, Boolean> perms = requireClaim(claimId).groups.computeIfAbsent(group, ignored -> new HashMap<>());
        if (value == null) {
            perms.remove(flanPermId);
        } else {
            perms.put(flanPermId, value);
        }
    }

    public void driftDefault(UUID claimId, String flanPermId, @Nullable Boolean value) {
        Claim claim = requireClaim(claimId);
        if (value == null) {
            claim.defaults.remove(flanPermId);
        } else {
            claim.defaults.put(flanPermId, value);
        }
    }

    /** 直接改成员 (group 为 null = 移出)。 */
    public void driftMember(UUID claimId, UUID player, @Nullable String group) {
        Claim claim = requireClaim(claimId);
        if (group == null) {
            claim.members.remove(player);
        } else {
            claim.members.put(player, group);
        }
    }

    public void driftFakePlayer(UUID claimId, UUID player) {
        requireClaim(claimId).fakePlayers.add(player);
    }

    public void driftPotion(UUID claimId, String effect, int amplifier) {
        requireClaim(claimId).potions.put(effect, amplifier);
    }

    public void driftName(UUID claimId, String name) {
        requireClaim(claimId).name = name;
    }

    /** 领地的高度形状: 底与是否 2D (像 /flan 圈的管理员领地只往下探 defaultClaimDepth 格, 或者是 3D 领地)。 */
    public void driftShape(UUID claimId, int minY, boolean flat) {
        Claim claim = requireClaim(claimId);
        claim.minY = minY;
        claim.flat = flat;
    }

    /** 3D 领地的一段高度 (底与顶)。 */
    public void driftShape3d(UUID claimId, int minY, int maxY) {
        Claim claim = requireClaim(claimId);
        claim.minY = minY;
        claim.maxY = maxY;
        claim.flat = false;
    }

    /** 往放行清单里放几条 (像 OP 在 /flan menu 里加了"谁都能开的箱子")。 */
    public void driftAllowList(UUID claimId, int entries) {
        requireClaim(claimId).allowListEntries += entries;
    }

    /** 领地的底 (测试核对 extendDistrictClaimToBottom)。 */
    public int minYOf(UUID claimId) {
        return requireClaim(claimId).minY;
    }

    public int allowListCount(UUID claimId) {
        Claim claim = claims.get(claimId);
        return claim == null ? 0 : claim.allowListEntries;
    }

    /** 金锄头改范围 (不查任何冲突)。 */
    public void driftResize(UUID claimId, PlotArea box) {
        requireClaim(claimId).box = box;
    }

    /** /flan adminDelete 或 deleteSubClaim。 */
    public void driftDelete(UUID claimId) {
        Claim claim = claims.remove(claimId);
        if (claim == null) {
            return;
        }
        for (UUID child : List.copyOf(claim.children)) {
            claims.remove(child);
        }
        Claim parent = claim.parentId == null ? null : claims.get(claim.parentId);
        if (parent != null) {
            parent.children.remove(claimId);
        }
    }

    // ================================================================
    // FlanGateway
    // ================================================================

    @Override
    public boolean available() {
        return true;
    }

    @Override
    public Optional<ClaimHandle> findDistrictClaim(String dimension, UUID claimId) {
        Claim claim = claims.get(claimId);
        return claim != null && claim.parentId == null && claim.dimension.equals(dimension)
                ? Optional.of(claim.handle())
                : Optional.empty();
    }

    @Override
    public Optional<ClaimHandle> districtClaimAt(String dimension, int x, int z) {
        for (Claim claim : claims.values()) {
            if (claim.parentId == null && claim.dimension.equals(dimension)
                    && claim.box.overlaps(new PlotArea(x, z, x, z))) {
                return Optional.of(claim.handle());
            }
        }
        return Optional.empty();
    }

    @Override
    public List<ClaimHandle> claimsIntersecting(String dimension, DistrictBounds bounds) {
        List<ClaimHandle> found = new ArrayList<>();
        for (Claim claim : claims.values()) {
            if (claim.parentId == null && claim.dimension.equals(dimension) && claim.box.overlaps(bounds.asArea())) {
                found.add(claim.handle());
            }
        }
        return found;
    }

    @Override
    public List<PersonalClaim> personalClaimsIntersecting(String dimension, PlotArea area) {
        List<PersonalClaim> found = new ArrayList<>();
        for (Claim claim : claims.values()) {
            if (claim.parentId == null && !claim.admin && claim.owner != null && claim.dimension.equals(dimension)
                    && claim.box.overlaps(area)) {
                found.add(new PersonalClaim(claim.handle(), claim.owner, claim.box, claim.flat, claim.minY,
                        claim.maxY));
            }
        }
        return found;
    }

    @Override
    public Optional<ClaimInfo> inspectClaim(ClaimHandle handle) {
        Claim claim = claims.get(handle.claimId());
        if (claim == null) {
            return Optional.empty();
        }
        return Optional.of(new ClaimInfo(claim.handle(), claim.box.minX(), claim.box.minZ(), claim.box.maxX(),
                claim.box.maxZ(), claim.minY, WORLD_MIN_Y, claim.flat, claim.admin, claim.name,
                claim.fakePlayers.size(), claim.potions.size(), claim.allowListEntries));
    }

    @Override
    public FlanResult<ClaimHandle> createDistrictClaim(String dimension, DistrictBounds bounds, String name) {
        String error = record("createDistrictClaim", null, dimension, boxText(bounds.asArea()), name);
        if (error != null) {
            return FlanResult.failure(error);
        }
        for (Claim claim : claims.values()) {
            if (claim.parentId == null && claim.dimension.equals(dimension) && claim.box.overlaps(bounds.asArea())) {
                return FlanResult.failure("和已有的领地重叠");
            }
        }
        // 真网关在建领地时把 Flan 的 defaultGroups 临时换空: 新父领地上没有任何组。
        Claim created = new Claim(UUID.randomUUID(), dimension, null, name, bounds.asArea(), table);
        claims.put(created.id, created);
        return FlanResult.success(created.handle());
    }

    @Override
    public FlanResult<Void> resizeDistrictClaim(ClaimHandle district, DistrictBounds bounds) {
        String error = record("resizeDistrictClaim", district.claimId(), boxText(bounds.asArea()));
        if (error != null) {
            return FlanResult.failure(error);
        }
        Claim claim = claims.get(district.claimId());
        if (claim == null) {
            return FlanResult.failure("领地不存在");
        }
        // 与真网关同一口径: 只核对, 不改。
        return claim.box.equals(bounds.asArea())
                ? FlanResult.success()
                : FlanResult.failure("领地范围和库里不一致，请用金锄头改好后执行 /district bounds sync");
    }

    @Override
    public FlanResult<Void> extendDistrictClaimToBottom(ClaimHandle district) {
        String error = record("extendDistrictClaimToBottom", district.claimId());
        if (error != null) {
            return FlanResult.failure(error);
        }
        Claim claim = claims.get(district.claimId());
        if (claim == null || claim.parentId != null) {
            return FlanResult.failure("领地不存在");
        }
        if (!claim.flat) {
            return FlanResult.failure("3D 领地补不到世界底");
        }
        claim.minY = Math.min(claim.minY, WORLD_MIN_Y);
        return FlanResult.success();
    }

    @Override
    public FlanResult<ClaimHandle> createPlotClaim(ClaimHandle district, PlotArea area, String name, String plotId) {
        String error = record("createPlotClaim", district.claimId(), boxText(area), name, plotId);
        if (error != null) {
            return FlanResult.failure(error);
        }
        Claim parent = claims.get(district.claimId());
        if (parent == null || parent.parentId != null) {
            return FlanResult.failure("自管区领地不存在");
        }
        if (!parent.box.contains(area)) {
            return FlanResult.failure("地块超出自管区的领地范围");
        }
        for (UUID siblingId : parent.children) {
            Claim sibling = claims.get(siblingId);
            if (sibling != null && sibling.box.overlaps(area)) {
                return FlanResult.failure("和同一自管区里的另一块子领地重叠");
            }
        }
        Claim plot = new Claim(UUID.randomUUID(), parent.dimension, parent.id, name, area, table);
        // Flan 的 tryCreateSubClaim: permissions.putAll (共用内层 Map)、playersGroups.putAll、potions.putAll。
        plot.groups.putAll(parent.groups);
        plot.inheritedGroups.addAll(parent.groups.keySet());
        plot.members.putAll(parent.members);
        plot.potions.putAll(parent.potions);
        if (scrubOnCreate) {
            // 接口契约: 返回时已清理干净 —— 删掉继承来的外层键, 对复制过来的成员与药水逐个移出。
            plot.groups.clear();
            plot.inheritedGroups.clear();
            plot.members.clear();
            plot.potions.clear();
        }
        // 接口契约: 再关上 —— 本块的三个 p_ 组对全部非全局权限显式为假, 默认同样全假, 全局权限一律没有键。
        for (String group : List.of(FlanGroupNames.plotOwner(plotId), FlanGroupNames.plotFriend(plotId),
                FlanGroupNames.plotResident(plotId))) {
            Map<String, Boolean> closed = new HashMap<>();
            for (KnownPermission permission : table) {
                if (!permission.global()) {
                    closed.put(permission.id(), false);
                }
            }
            plot.groups.put(group, closed);
        }
        for (KnownPermission permission : table) {
            if (permission.global()) {
                plot.defaults.remove(permission.id());
            } else {
                plot.defaults.put(permission.id(), false);
            }
        }
        claims.put(plot.id, plot);
        parent.children.add(plot.id);
        return FlanResult.success(plot.handle());
    }

    @Override
    public Optional<ClaimHandle> findPlotClaim(ClaimHandle district, UUID plotClaimId) {
        Claim claim = claims.get(plotClaimId);
        return claim != null && district.claimId().equals(claim.parentId)
                ? Optional.of(claim.handle())
                : Optional.empty();
    }

    @Override
    public List<ClaimHandle> listPlotClaims(ClaimHandle district) {
        Claim parent = claims.get(district.claimId());
        if (parent == null) {
            return List.of();
        }
        List<ClaimHandle> children = new ArrayList<>();
        for (UUID id : parent.children) {
            Claim child = claims.get(id);
            if (child != null) {
                children.add(child.handle());
            }
        }
        return children;
    }

    @Override
    public FlanResult<Void> resizePlotClaim(ClaimHandle plot, PlotArea area) {
        String error = record("resizePlotClaim", plot.claimId(), boxText(area));
        if (error != null) {
            return FlanResult.failure(error);
        }
        Claim claim = claims.get(plot.claimId());
        if (claim == null || claim.parentId == null) {
            return FlanResult.failure("子领地不存在");
        }
        Claim parent = claims.get(claim.parentId);
        if (parent != null) {
            if (!parent.box.contains(area)) {
                return FlanResult.failure("地块超出自管区的领地范围");
            }
            for (UUID siblingId : parent.children) {
                Claim sibling = claims.get(siblingId);
                if (sibling != null && sibling != claim && sibling.box.overlaps(area)) {
                    return FlanResult.failure("和同一自管区里的另一块子领地重叠");
                }
            }
        }
        claim.box = area;
        return FlanResult.success();
    }

    @Override
    public FlanResult<Void> deletePlotClaim(ClaimHandle plot) {
        String error = record("deletePlotClaim", plot.claimId());
        if (error != null) {
            return FlanResult.failure(error);
        }
        Claim claim = claims.remove(plot.claimId());
        if (claim == null) {
            return FlanResult.failure("子领地不存在");
        }
        Claim parent = claim.parentId == null ? null : claims.get(claim.parentId);
        if (parent != null) {
            parent.children.remove(claim.id);
        }
        return FlanResult.success();
    }

    @Override
    public FlanResult<Void> setGroupPermission(ClaimHandle handle, String group, String flanPermId, PermValue value) {
        if (FlanPermissions.admin(flanPermId) && value == PermValue.TRUE) {
            throw new AssertionError("district invariant broken: " + flanPermId + " written true in group " + group);
        }
        if (isGlobalPermission(flanPermId)) {
            throw new AssertionError("district invariant broken: global permission " + flanPermId
                    + " written into group " + group + " (Flan refuses it)");
        }
        Claim claim = claims.get(handle.claimId());
        if (claim != null) {
            requireGroupAllowed(claim, group);
        }
        String error = record("setGroupPermission", handle.claimId(), group, flanPermId, value.name());
        if (error != null) {
            return FlanResult.failure(error);
        }
        if (claim == null) {
            return FlanResult.failure("领地不存在");
        }
        if (value == PermValue.UNSET) {
            Map<String, Boolean> perms = claim.groups.get(group);
            if (perms != null) {
                perms.remove(flanPermId);
            }
        } else {
            claim.groups.computeIfAbsent(group, ignored -> new HashMap<>()).put(flanPermId, value == PermValue.TRUE);
        }
        return FlanResult.success();
    }

    @Override
    public FlanResult<Void> setDefaultPermission(ClaimHandle handle, String flanPermId, PermValue value) {
        if (FlanPermissions.admin(flanPermId) && value == PermValue.TRUE) {
            throw new AssertionError("district invariant broken: " + flanPermId + " written true as a default");
        }
        String error = record("setDefaultPermission", handle.claimId(), flanPermId, value.name());
        if (error != null) {
            return FlanResult.failure(error);
        }
        Claim claim = claims.get(handle.claimId());
        if (claim == null) {
            return FlanResult.failure("领地不存在");
        }
        if (value == PermValue.UNSET) {
            claim.defaults.remove(flanPermId);
        } else {
            claim.defaults.put(flanPermId, value == PermValue.TRUE);
        }
        return FlanResult.success();
    }

    @Override
    public FlanResult<Void> deleteGroup(ClaimHandle handle, String group) {
        String error = record("deleteGroup", handle.claimId(), group);
        if (error != null) {
            return FlanResult.failure(error);
        }
        Claim claim = claims.get(handle.claimId());
        if (claim == null) {
            return FlanResult.failure("领地不存在");
        }
        claim.groups.remove(group);
        claim.inheritedGroups.remove(group);
        claim.members.values().removeIf(group::equals);
        return FlanResult.success();
    }

    @Override
    public FlanResult<Void> setMember(ClaimHandle handle, UUID player, @Nullable String group) {
        Claim claim = claims.get(handle.claimId());
        if (claim != null && group != null) {
            requireGroupAllowed(claim, group);
        }
        String error = record("setMember", handle.claimId(), player.toString(), group == null ? "-" : group);
        if (error != null) {
            return FlanResult.failure(error);
        }
        if (claim == null) {
            return FlanResult.failure("领地不存在");
        }
        if (group == null) {
            claim.members.remove(player);
        } else {
            claim.members.put(player, group);
        }
        return FlanResult.success();
    }

    @Override
    public Map<UUID, String> readMembers(ClaimHandle handle) {
        Claim claim = claims.get(handle.claimId());
        return claim == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(claim.members));
    }

    @Override
    public FlanResult<Void> clearExtras(ClaimHandle handle) {
        String error = record("clearExtras", handle.claimId());
        if (error != null) {
            return FlanResult.failure(error);
        }
        Claim claim = claims.get(handle.claimId());
        if (claim == null) {
            return FlanResult.failure("领地不存在");
        }
        claim.fakePlayers.clear();
        claim.potions.clear();
        claim.allowListEntries = 0;
        return FlanResult.success();
    }

    @Override
    public ClaimPermissionSnapshot readPermissions(ClaimHandle handle) {
        Claim claim = claims.get(handle.claimId());
        if (claim == null) {
            return new ClaimPermissionSnapshot(Map.of(), Map.of());
        }
        Map<String, Map<String, Boolean>> groups = new LinkedHashMap<>();
        claim.groups.forEach((name, perms) -> groups.put(name, Map.copyOf(perms)));
        return new ClaimPermissionSnapshot(groups, claim.defaults);
    }

    @Override
    public List<KnownPermission> permissionTable() {
        return table;
    }

    @Override
    public long permissionTableVersion() {
        return tableVersion;
    }

    @Override
    public FlanResult<Void> setClaimName(ClaimHandle handle, String name) {
        String error = record("setClaimName", handle.claimId(), name);
        if (error != null) {
            return FlanResult.failure(error);
        }
        Claim claim = claims.get(handle.claimId());
        if (claim == null) {
            return FlanResult.failure("领地不存在");
        }
        claim.name = name;
        return FlanResult.success();
    }

    @Override
    public FlanResult<String> backupNow(String dimension, String reason) {
        String error = record("backupNow", null, dimension, reason);
        if (error != null) {
            return FlanResult.failure(error);
        }
        backups++;
        return FlanResult.success("recording-" + backups + "-" + reason + ".AdminClaims.json.bak");
    }

    @Override
    public void flush(String dimension) {
        record("flush", null, dimension);
    }

    // ================================================================
    // 内部
    // ================================================================

    private Claim requireClaim(UUID claimId) {
        Claim claim = claims.get(claimId);
        if (claim == null) {
            throw new IllegalArgumentException("no claim " + claimId);
        }
        return claim;
    }

    /** 父领地上只许 d_ 组; 地块上只许 p_ 组, 也不许写继承来的组 (共用内层 Map, 会连带改掉全区)。 */
    private static void requireGroupAllowed(Claim claim, String group) {
        if (claim.parentId == null) {
            if (!group.startsWith(FlanGroupNames.DISTRICT_PREFIX)) {
                throw new AssertionError("district invariant broken: group " + group + " written on a district claim");
            }
            return;
        }
        if (!group.startsWith(FlanGroupNames.PLOT_PREFIX) || claim.inheritedGroups.contains(group)) {
            throw new AssertionError("district invariant broken: plot claim touched inherited or non-plot group "
                    + group);
        }
    }

    /** 记下一次调用并返回注入的失败原因 (没有注入时为 null)。 */
    @Nullable
    private String record(String op, @Nullable UUID claimId, String... args) {
        FlanCall call = new FlanCall(op, claimId, List.of(args));
        calls.add(call);
        Deque<String> queued = failNext.get(op);
        if (queued != null && !queued.isEmpty()) {
            return queued.poll();
        }
        for (FailureRule rule : failures) {
            if (rule.when().test(call)) {
                return rule.error();
            }
        }
        return null;
    }

    private static String boxText(PlotArea area) {
        return area.minX() + "," + area.minZ() + "," + area.maxX() + "," + area.maxZ();
    }
}
