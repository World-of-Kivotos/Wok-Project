package com.miningdim.district.flan;

import com.miningdim.district.core.DistrictBounds;
import com.miningdim.district.core.DistrictTexts;
import com.miningdim.district.core.PlotArea;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * 降级网关 (设计文档 8.5、20.2): 功能开着但 Flan 用不了 (没装、版本不符、自检不过、运行中熔断) 时绑定。查询返回空,
 * 一切写入都如实回"领地对接未启用：{原因}" —— 面板因此如实显示"领地权限没有生效", 数据库照常是真相, Flan 修好、
 * 重启之后开服对账会补齐。
 */
public final class DisabledFlanGateway implements FlanGateway {

    private final String reason;

    public DisabledFlanGateway(String reason) {
        this.reason = Objects.requireNonNull(reason, "reason");
    }

    /** 降级的原因 (如"Flan 没有安装")。 */
    public String reason() {
        return reason;
    }

    private <T> FlanResult<T> refuse() {
        return FlanResult.failure(DistrictTexts.flanDisabled(reason));
    }

    @Override
    public boolean available() {
        return false;
    }

    @Override
    public String unavailableReason() {
        return DistrictTexts.flanDisabled(reason);
    }

    @Override
    public Optional<ClaimHandle> findDistrictClaim(String dimension, UUID claimId) {
        return Optional.empty();
    }

    @Override
    public Optional<ClaimHandle> districtClaimAt(String dimension, int x, int z) {
        return Optional.empty();
    }

    @Override
    public List<ClaimHandle> claimsIntersecting(String dimension, DistrictBounds bounds) {
        return List.of();
    }

    @Override
    public List<PersonalClaim> personalClaimsIntersecting(String dimension, PlotArea area) {
        return List.of();
    }

    @Override
    public Optional<ClaimInfo> inspectClaim(ClaimHandle claim) {
        return Optional.empty();
    }

    @Override
    public FlanResult<ClaimHandle> createDistrictClaim(String dimension, DistrictBounds bounds, String name) {
        return refuse();
    }

    @Override
    public FlanResult<Void> resizeDistrictClaim(ClaimHandle district, DistrictBounds bounds) {
        return refuse();
    }

    @Override
    public FlanResult<Void> extendDistrictClaimToBottom(ClaimHandle district) {
        return refuse();
    }

    @Override
    public FlanResult<ClaimHandle> createPlotClaim(ClaimHandle district, PlotArea area, String name, String plotId) {
        return refuse();
    }

    @Override
    public Optional<ClaimHandle> findPlotClaim(ClaimHandle district, UUID plotClaimId) {
        return Optional.empty();
    }

    @Override
    public List<ClaimHandle> listPlotClaims(ClaimHandle district) {
        return List.of();
    }

    @Override
    public FlanResult<Void> resizePlotClaim(ClaimHandle plot, PlotArea area) {
        return refuse();
    }

    @Override
    public FlanResult<Void> deletePlotClaim(ClaimHandle plot) {
        return refuse();
    }

    @Override
    public FlanResult<Void> setGroupPermission(ClaimHandle claim, String group, String flanPermId, PermValue value) {
        return refuse();
    }

    @Override
    public FlanResult<Void> setDefaultPermission(ClaimHandle claim, String flanPermId, PermValue value) {
        return refuse();
    }

    @Override
    public FlanResult<Void> deleteGroup(ClaimHandle claim, String group) {
        return refuse();
    }

    @Override
    public FlanResult<Void> setMember(ClaimHandle claim, UUID player, @Nullable String group) {
        return refuse();
    }

    @Override
    public Map<UUID, String> readMembers(ClaimHandle claim) {
        return Map.of();
    }

    @Override
    public FlanResult<Void> clearExtras(ClaimHandle claim) {
        return refuse();
    }

    @Override
    public ClaimPermissionSnapshot readPermissions(ClaimHandle claim) {
        return new ClaimPermissionSnapshot(Map.of(), Map.of());
    }

    @Override
    public List<KnownPermission> permissionTable() {
        return FlanPermissions.BUILTIN;
    }

    @Override
    public long permissionTableVersion() {
        return 1;
    }

    @Override
    public FlanResult<Void> setClaimName(ClaimHandle claim, String name) {
        return refuse();
    }

    @Override
    public FlanResult<String> backupNow(String dimension, String reason) {
        return refuse();
    }

    @Override
    public void flush(String dimension) {
        // 没有领地可写, 也就没有要落盘的东西。
    }
}
