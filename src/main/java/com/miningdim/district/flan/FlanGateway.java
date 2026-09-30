package com.miningdim.district.flan;

import com.miningdim.district.core.DistrictBounds;
import com.miningdim.district.core.DistrictTexts;
import com.miningdim.district.core.PlotArea;
import org.jetbrains.annotations.Nullable;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * 低层 Flan 网关 (设计文档 8.1, 阶段 2 的增改见 20.1 与 20.7)。签名里不出现任何 Flan 的类; 各服务只经
 * {@link DistrictFlanSync} 与 {@link DistrictReconciler} 使用它。所有调用都在服务端主线程。
 *
 * <p>三个实现:
 * <ul>
 *   <li>{@code flan.real.FlanClaimGateway}: 真 Flan (开服自检通过才会被加载);</li>
 *   <li>{@link DisabledFlanGateway}: 降级 (没装 Flan、版本不符、自检不过、运行中熔断), 写入如实报失败并带原因;</li>
 *   <li>{@link RecordingFlanGateway}: 只在 GameTest 服务端上可用的内存假网关。</li>
 * </ul>
 * 所有写方法: 值与现状相同就不写 (不标脏), 直接报成功。
 */
public interface FlanGateway {

    /** 本实现能否真正写领地 (Disabled 与熔断后的真网关为 false; 回执不看它, 只看每次写入的 FlanResult)。 */
    boolean available();

    /**
     * {@link #available()} 为假时给人看的原因 ("领地对接未启用：{原因}"): 降级与熔断时查询一律返回空, 调用方不能把
     * "查不到"当成"领地被删了" (那会置对账标记、把错的原因写进住户与地块的生效状态)。
     */
    default String unavailableReason() {
        return DistrictTexts.flanDisabled("?");
    }

    /** 这个维度现在有没有加载 (没加载时查询同样返回空, 也不能当成"领地被删了")。 */
    default boolean dimensionLoaded(String dimension) {
        return true;
    }

    // ---- storage / findDistrict ----

    Optional<ClaimHandle> findDistrictClaim(String dimension, UUID claimId);

    /** 那一列 (只比 X/Z) 上的顶层领地。 */
    Optional<ClaimHandle> districtClaimAt(String dimension, int x, int z);

    /** 与这片范围 (X/Z) 相交的全部顶层领地, 管理员领地与玩家领地都算。 */
    List<ClaimHandle> claimsIntersecting(String dimension, DistrictBounds bounds);

    /**
     * 与这片 X/Z 范围相交的顶层个人领地 (owner 不为 null, 22.20): 自管区外围的清单、摘要与计数用。降级、熔断、维度没加载时
     * 为空。
     */
    List<PersonalClaim> personalClaimsIntersecting(String dimension, PlotArea area);

    /** 范围、是否管理员领地、是否 2D 全高、名字、假玩家白名单与药水的条数; 找不到为空。 */
    Optional<ClaimInfo> inspectClaim(ClaimHandle claim);

    // ---- createDistrict / resizeDistrict ---- (deleteDistrict 刻意不提供: 解绑不删领地)

    /** 契约: 返回的父领地上没有任何组 (Flan 的默认组在建领地时临时换空)。 */
    FlanResult<ClaimHandle> createDistrictClaim(String dimension, DistrictBounds bounds, String name);

    /** 真网关只核对范围是否一致, 不改领地 (改范围走金锄头 + /district bounds sync, 20.6)。 */
    FlanResult<Void> resizeDistrictClaim(ClaimHandle district, DistrictBounds bounds);

    /**
     * 把 2D 父领地的底补到世界底 (Flan 自己放方块时也调的 extendDownwards: 只降低 minY、标脏, 不动 X/Z 与区块索引)。
     * Flan 只按顶层领地判定保护, 父领地不含的高度在任何地块里都是野外 (20.3); /flan 圈的管理员领地默认只往下探
     * defaultClaimDepth 格。已经到底时直接报成功; 3D 领地补不了, 报失败。
     */
    FlanResult<Void> extendDistrictClaimToBottom(ClaimHandle district);

    // ---- createPlot / findPlot / resizePlot / deletePlot ----

    /**
     * 契约: 返回时子领地已清理干净 (继承来的组、成员快照与药水都已删掉), 并处于"关闭"状态 —— 与冻结相同: 本块的三个
     * p_&lt;plotId&gt;_… 组都在、对全部非全局权限显式为假, 地块默认对全部非全局权限显式为假, 全局权限一律没有键。
     * 之后的差异写入只需"放"该放的格子; 写库或写差异之间出了任何事, 地块都停在谁也进不去的一侧, 对账也能按组名认出它
     * (20.3)。清理或关闭失败时半成品已删 (20.2 第 5 条)。
     */
    FlanResult<ClaimHandle> createPlotClaim(ClaimHandle district, PlotArea area, String name, String plotId);

    Optional<ClaimHandle> findPlotClaim(ClaimHandle district, UUID plotClaimId);

    /** 父领地下的全部子领地, 包括不是本模块建的。 */
    List<ClaimHandle> listPlotClaims(ClaimHandle district);

    FlanResult<Void> resizePlotClaim(ClaimHandle plot, PlotArea area);

    FlanResult<Void> deletePlotClaim(ClaimHandle plot);

    // ---- createGroup / setGroupPerm / setDefaultPerm / deleteGroup ----

    FlanResult<Void> setGroupPermission(ClaimHandle claim, String group, String flanPermId, PermValue value);

    FlanResult<Void> setDefaultPermission(ClaimHandle claim, String flanPermId, PermValue value);

    /** 删掉一个组与它的成员 (不受组名守卫: 收编与对账要删 Flan 的默认组和别的外来组)。 */
    FlanResult<Void> deleteGroup(ClaimHandle claim, String group);

    // ---- setMember / removeMember / readMembers ----

    /** group 为 null = 移出该领地的一切组。一个玩家在一块领地里只能在一个组。 */
    FlanResult<Void> setMember(ClaimHandle claim, UUID player, @Nullable String group);

    Map<UUID, String> readMembers(ClaimHandle claim);

    /** 清空假玩家白名单、药水与六张放行清单。 */
    FlanResult<Void> clearExtras(ClaimHandle claim);

    // ---- readPerms / listPerms ----

    ClaimPermissionSnapshot readPermissions(ClaimHandle claim);

    /** 当前已知的全部 Flan 权限 (/reload 之后重读)。 */
    List<KnownPermission> permissionTable();

    /** 权限表每重读一次加一。 */
    long permissionTableVersion();

    /** 由 {@link #permissionTable()} 派生的 id 集合。 */
    default Set<String> knownPermissions() {
        Set<String> ids = new LinkedHashSet<>();
        permissionTable().forEach(permission -> ids.add(permission.id()));
        return ids;
    }

    default boolean isGlobalPermission(String flanPermId) {
        for (KnownPermission permission : permissionTable()) {
            if (permission.id().equals(flanPermId)) {
                return permission.global();
            }
        }
        return false;
    }

    /** 数据包整服重载 (/reload) 之后: 作废权限表缓存。 */
    default void onPermissionsReloaded() {
    }

    // ---- name / backup / flush ----

    FlanResult<Void> setClaimName(ClaimHandle claim, String name);

    /** 不论节流, 立刻备份一个维度的管理员领地 (20.5); 返回文件名。 */
    FlanResult<String> backupNow(String dimension, String reason);

    /** 某维度的备份目录与最新一份的文件名 (/district status); 没有备份机制的实现为空。 */
    default Optional<String> backupStatus(String dimension) {
        return Optional.empty();
    }

    /** 显式存盘 (只在建、绑、重建父领地之后调; /save-off 期间跳过, 20.5)。 */
    void flush(String dimension);
}
