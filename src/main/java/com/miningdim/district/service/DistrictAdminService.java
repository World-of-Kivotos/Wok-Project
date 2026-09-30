package com.miningdim.district.service;

import com.miningdim.district.DistrictLimits;
import com.miningdim.district.access.Actor;
import com.miningdim.district.core.Academy;
import com.miningdim.district.core.DistrictActionNames;
import com.miningdim.district.core.DistrictActorRole;
import com.miningdim.district.core.DistrictBounds;
import com.miningdim.district.core.DistrictError;
import com.miningdim.district.core.DistrictLogAction;
import com.miningdim.district.core.DistrictLogEntry;
import com.miningdim.district.core.DistrictRecord;
import com.miningdim.district.core.DistrictRuleException;
import com.miningdim.district.core.DistrictTexts;
import com.miningdim.district.core.MemberRecord;
import com.miningdim.district.core.PlotActorRole;
import com.miningdim.district.core.PlotGeometry;
import com.miningdim.district.core.PlotLogAction;
import com.miningdim.district.core.PlotLogEntry;
import com.miningdim.district.core.PlotRecord;
import com.miningdim.district.flan.ClaimHandle;
import com.miningdim.district.flan.ClaimInfo;
import com.miningdim.district.flan.DistrictReconciler;
import com.miningdim.district.flan.FlanGateway;
import com.miningdim.district.flan.FlanGroupNames;
import com.miningdim.district.flan.FlanResult;
import com.miningdim.district.notice.DistrictNoticeKind;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 管理员对自管区本身的操作: 任命 / 撤销区务长 (admin.district.setWarden)、解绑 (admin.district.delete)、
 * 定价与尺寸 (admin.district.setPlotPricing)、开放购买 (admin.district.setPurchaseOpen), 以及只经 /district
 * 命令做的建区、改范围、区规、把人移出已解绑学院。
 *
 * 命令专属的失败 (学院不存在、学院已有自管区、范围重叠、范围非法) 由结果对象返回, 不进 WebUiErrorCodes: 它们从不走平板。
 */
public final class DistrictAdminService extends ServiceSupport {

    /** admin.district.setWarden 的结果: 撤销时 wardenName 为 null, logEntry 是 revoke; 任命时 logEntry 是 appoint 那一条。 */
    public record WardenResult(@Nullable String wardenName, DistrictLogEntry logEntry) {
    }

    /** admin.district.delete 的结果。 */
    public record UnbindResult(String districtId, int keptMembers, int keptPlots) {
    }

    /** admin.district.setPlotPricing 的结果: 三个值都没变时 logEntry 为 null。 */
    public record PricingResult(int edgeGap, int minSide, int maxSide, int unitPrice,
                                @Nullable DistrictLogEntry logEntry) {
    }

    /** admin.district.setPurchaseOpen 的结果: 值没变时 logEntry 为 null。 */
    public record PurchaseOpenResult(boolean open, @Nullable DistrictLogEntry logEntry) {
    }

    /** 命令专属失败的种类。 */
    public enum CommandOutcome {
        OK,
        ACADEMY_UNKNOWN,
        ACADEMY_ALREADY_BOUND,
        ACADEMY_STILL_BOUND,
        BOUNDS_INVALID,
        OVERLAPS_DISTRICT,
        PLOTS_OUTSIDE,
        DISTRICT_NOT_FOUND,
        NOT_MEMBER,
        /** 领地对接没有生效 (功能降级), 这条命令要真 Flan。detail: 原因。 */
        FLAN_UNAVAILABLE,
        /** 这片范围已有 Flan 领地。detail: 相交的领地, 管理员领地带"(admin)"。 */
        FLAN_CLAIM_EXISTS,
        /** 那一列上没有领地 / 库里记着的父领地在 Flan 里找不到。 */
        CLAIM_NOT_FOUND,
        /** 那块领地不是管理员领地。 */
        CLAIM_NOT_ADMIN,
        /**
         * 那块领地是 3D 的 (只保护一段高度): Flan 只按顶层领地判定, 它不含的高度在每块地里都是野外, 而 3D 领地没法往下补。
         * detail: 领地 id。2D 但底不在世界底的照收, 收编时补到世界底。
         */
        CLAIM_NOT_FULL_HEIGHT,
        /** 那块领地已被别的在用自管区绑着。detail: 那个自管区。 */
        CLAIM_ALREADY_BOUND,
        /** 执行 recreate 时父领地其实还在。 */
        CLAIM_PRESENT,
        /** 本区已有父领地: 范围以领地为准, 用金锄头改好后执行 bounds sync。 */
        BOUNDS_FOLLOW_CLAIM,
        /**
         * bind / relink 还没确认: detail 是预览的每一行; bind 的最后一个元素是其上子领地的个数 (确认后会全部删掉),
         * relink 的最后一个元素是要改认的领地 id。
         */
        PREVIEW,
        /** Flan 写入失败。detail: 原因。 */
        FLAN_FAILED
    }

    /**
     * 命令的结果。district 在成功时是写入后的行; detail 是失败时的补充 (重叠的自管区 id、落到范围外的地块编号、
     * 被移出的人名); previewBounds 只在 bind / relink 的 PREVIEW 里有: 确认之后自管区的范围 (命令层按它数外围的个人
     * 领地, 22.20)。
     */
    public record CommandResult(CommandOutcome outcome, @Nullable DistrictRecord district, List<String> detail,
                                @Nullable DistrictBounds previewBounds) {

        public CommandResult {
            detail = List.copyOf(detail);
        }

        public CommandResult(CommandOutcome outcome, @Nullable DistrictRecord district, List<String> detail) {
            this(outcome, district, detail, null);
        }

        public boolean ok() {
            return outcome == CommandOutcome.OK;
        }

        static CommandResult of(CommandOutcome outcome, String... detail) {
            return new CommandResult(outcome, null, List.of(detail));
        }
    }

    DistrictAdminService(DistrictContext ctx) {
        super(ctx);
    }

    // ================================================================
    // admin.district.setWarden 任命 / 撤销区务长
    // ================================================================

    /**
     * @param typedName null = 撤销 (必须是显式的 null; 缺键由平板层报 INVALID_REQUEST, 不等于撤销)
     */
    public WardenResult setWarden(Actor actor, String districtId, @Nullable String typedName) {
        requireOp(actor, DistrictActionNames.SET_WARDEN);
        requireNoOpenTransaction("setWarden");
        sweep(districtId);
        long at = ctx.now();
        WardenResult result = repo.inTransaction(() -> {
            DistrictRecord district = requireLive(districtId);
            String previous = district.wardenName();
            if (typedName == null) {
                if (previous == null) {
                    throw new DistrictRuleException(DistrictError.WARDEN_NOT_APPOINTED, "本区现在没有区务长");
                }
                repo.setWarden(district.districtId(), null, null);
                DistrictLogEntry log = writeDistrictLog(districtLog(district, actor, at, DistrictLogAction.REVOKE,
                        previous, null));
                // 收件人取清掉之前读到的区务长 (22.11)。
                ctx.notices().enqueueUnlessSelf(actor, district.wardenUuid(), DistrictNoticeKind.WARDEN_REVOKED,
                        List.of(district.displayName()), district.districtId(), null);
                return new WardenResult(null, log);
            }
            MemberRecord candidate = memberOfDistrict(district, typedName).orElseThrow(() -> new DistrictRuleException(
                    DistrictError.NOT_RESIDENT, DistrictTexts.echo(typedName) + " 还不是本区住户，请先把 TA 加进来再任命",
                    DistrictRuleException.params("playerName", DistrictTexts.echo(typedName))));
            if (district.isWarden(candidate.uuid())) {
                throw new DistrictRuleException(DistrictError.ALREADY_WARDEN, candidate.name() + " 已经是本区区务长",
                        DistrictRuleException.params("playerName", candidate.name()));
            }
            // 换人 = 先撤旧的再任命新的, 两条都记, 免得记录里看起来像同时有两个区务长。
            if (previous != null) {
                writeDistrictLog(districtLog(district, actor, at, DistrictLogAction.REVOKE, previous, null));
                ctx.notices().enqueueUnlessSelf(actor, district.wardenUuid(), DistrictNoticeKind.WARDEN_REVOKED,
                        List.of(district.displayName()), district.districtId(), null);
            }
            repo.setWarden(district.districtId(), candidate.uuid(), candidate.name());
            DistrictLogEntry log = writeDistrictLog(districtLog(district, actor, at, DistrictLogAction.APPOINT,
                    candidate.name(), null));
            ctx.notices().enqueueUnlessSelf(actor, candidate.uuid(), DistrictNoticeKind.WARDEN_APPOINTED,
                    List.of(district.displayName()), district.districtId(), null);
            return new WardenResult(candidate.name(), log);
        });
        AUDIT.info("[miningdim] {} ({}) set the warden of {} to {}", actor.name(), actor.uuid(), districtId,
                result.wardenName() == null ? "(none)" : result.wardenName());
        return result;
    }

    // ================================================================
    // admin.district.delete 解绑
    // ================================================================

    /**
     * 只解绑: 学院名单保留 (仍约束一人一学院), 地块、三列、朋友、记录、墓碑、开关、定价一行都不删, 区务长清空,
     * 不调用任何 Flan 方法 (领地原样保留), 不写本区记录 (契约没有这个动作, 归档行本身带着解绑时间和操作人)。
     */
    public UnbindResult unbind(Actor actor, String districtId) {
        requireOp(actor, DistrictActionNames.DELETE);
        requireNoOpenTransaction("unbind");
        sweep(districtId);
        long at = ctx.now();
        UnbindResult result = repo.inTransaction(() -> {
            DistrictRecord district = requireLive(districtId);
            int members = repo.countMembers(district.academyId());
            int plots = repo.plotsOf(district.districtId()).size();
            repo.unbind(district.districtId(), at, actor.name(), members, plots);
            return new UnbindResult(district.districtId(), members, plots);
        });
        AUDIT.info("[miningdim] {} ({}) unbound district {} (kept {} member(s), {} plot(s))", actor.name(),
                actor.uuid(), districtId, result.keptMembers(), result.keptPlots());
        return result;
    }

    // ================================================================
    // admin.district.setPlotPricing 定价与尺寸
    // ================================================================

    /**
     * @param unitPrice 不是整数时为 null (报 INVALID_PRICE)
     * @param minSide   不是整数时为 null (报 INVALID_SIZE_LIMIT)
     * @param maxSide   同上
     */
    public PricingResult setPlotPricing(Actor actor, String districtId, @Nullable Long unitPrice,
                                        @Nullable Long minSide, @Nullable Long maxSide) {
        requireOp(actor, DistrictActionNames.SET_PLOT_PRICING);
        requireNoOpenTransaction("setPlotPricing");
        sweep(districtId);
        long at = ctx.now();
        PricingResult result = repo.inTransaction(() -> {
            DistrictRecord district = requireLive(districtId);
            if (unitPrice == null || unitPrice < 1 || unitPrice > DistrictLimits.MAX_UNIT_PRICE) {
                throw new DistrictRuleException(DistrictError.INVALID_PRICE, "每格单价要是正整数（上限 1,000,000）",
                        DistrictRuleException.params("field", "unitPrice"));
            }
            String badSize = minSide == null ? "minSide"
                    : maxSide == null ? "maxSide"
                    : minSide < 1 ? "minSide"
                    : maxSide > DistrictLimits.MAX_SIDE_LIMIT ? "maxSide"
                    : minSide > maxSide ? "minSide"
                    : null;
            if (badSize != null) {
                throw new DistrictRuleException(DistrictError.INVALID_SIZE_LIMIT,
                        "尺寸上下限要是正整数，且下限不能大于上限（上限 1024）",
                        DistrictRuleException.params("field", badSize));
            }
            int price = unitPrice.intValue();
            int min = minSide.intValue();
            int max = maxSide.intValue();
            List<String> parts = new ArrayList<>();
            if (price != district.unitPrice()) {
                parts.add("单价 " + district.unitPrice() + " → " + price + " 信用点/格");
            }
            if (min != district.minSide() || max != district.maxSide()) {
                parts.add("每边 " + district.minSide() + "~" + district.maxSide() + " → " + min + "~" + max + " 格");
            }
            if (parts.isEmpty()) {
                return new PricingResult(DistrictLimits.EDGE_GAP, min, max, price, null);
            }
            repo.setPricing(district.districtId(), price, min, max);
            DistrictLogEntry log = writeDistrictLog(districtLog(district, actor, at,
                    DistrictLogAction.SET_PLOT_PRICING, null, String.join("；", parts)));
            return new PricingResult(DistrictLimits.EDGE_GAP, min, max, price, log);
        });
        if (result.logEntry() != null) {
            AUDIT.info("[miningdim] {} ({}) set pricing of {}: {}", actor.name(), actor.uuid(), districtId,
                    result.logEntry().reason());
        }
        return result;
    }

    // ================================================================
    // admin.district.setPurchaseOpen 开放购买
    // ================================================================

    /** @param open 不是 JSON 布尔时为 null (报 INVALID_REQUEST {field: open}, 绝不当成 false) */
    public PurchaseOpenResult setPurchaseOpen(Actor actor, String districtId, @Nullable Boolean open) {
        requireOp(actor, DistrictActionNames.SET_PURCHASE_OPEN);
        requireNoOpenTransaction("setPurchaseOpen");
        sweep(districtId);
        long at = ctx.now();
        PurchaseOpenResult result = repo.inTransaction(() -> {
            DistrictRecord district = requireLive(districtId);
            if (open == null) {
                throw DistrictRuleException.invalidRequest("open", null);
            }
            if (open == district.purchaseOpen()) {
                return new PurchaseOpenResult(open, null);
            }
            repo.setPurchaseOpen(district.districtId(), open);
            DistrictLogEntry log = writeDistrictLog(districtLog(district, actor, at,
                    DistrictLogAction.SET_PURCHASE_OPEN, null,
                    open ? DistrictTexts.PURCHASE_OPENED : DistrictTexts.PURCHASE_PAUSED));
            if (open) {
                // 只发在线的, 不入队 (22.10 purchase_opened, P28): 提交之后广播。
                repo.afterCommit(() -> ctx.notices().broadcastPurchaseOpened(district.districtId(), actor.uuid()));
            }
            return new PurchaseOpenResult(open, log);
        });
        if (result.logEntry() != null) {
            AUDIT.info("[miningdim] {} ({}) set purchase of {} {}", actor.name(), actor.uuid(), districtId,
                    open ? "open" : "closed");
        }
        return result;
    }

    // ================================================================
    // 命令: 建区、改范围、区规、移出已解绑学院的成员
    // ================================================================

    /**
     * 为学院建一个自管区。districtId: 第一次绑定为 academyId, 之后第 n 次为 &lt;academyId&gt;-&lt;n&gt; (P2)。初始化开关表,
     * 单价默认 5 (或给定值)、边长 8–48、购买关闭, 不带区务长 (任命只走 setWarden); 提交后建父领地并写满开关 (失败只置
     * 对账标记)。不能和同一维度里其他未解绑的自管区重叠, 也不能压到别的学院已解绑自管区的范围 (见 overlappingDistrict)。
     */
    public CommandResult createDistrict(Actor actor, String academyId, DistrictBounds bounds,
                                        @Nullable Integer unitPrice) {
        requireNoOpenTransaction("createDistrict");
        long at = ctx.now();
        CommandResult result = repo.inTransaction(() -> {
            Optional<Academy> academy = repo.academy(academyId);
            if (academy.isEmpty()) {
                return CommandResult.of(CommandOutcome.ACADEMY_UNKNOWN, academyId);
            }
            Optional<DistrictRecord> live = repo.liveDistrictOfAcademy(academyId);
            if (live.isPresent()) {
                return CommandResult.of(CommandOutcome.ACADEMY_ALREADY_BOUND, live.get().districtId());
            }
            int price = unitPrice == null ? DistrictLimits.DEFAULT_UNIT_PRICE : unitPrice;
            if (bounds.minX() > bounds.maxX() || bounds.minZ() > bounds.maxZ() || price < 1
                    || price > DistrictLimits.MAX_UNIT_PRICE) {
                return CommandResult.of(CommandOutcome.BOUNDS_INVALID);
            }
            Optional<DistrictRecord> overlapping = overlappingDistrict(academyId, bounds, null);
            if (overlapping.isPresent()) {
                return CommandResult.of(CommandOutcome.OVERLAPS_DISTRICT, overlapping.get().districtId());
            }
            // 领地对接生效时, 这片范围与已有的顶层领地 (管理员与玩家的) 相交就拒绝: 父领地建不起来, 同一学院重新绑回自己的
            // 旧地也会撞上这里 —— 那种情形改用 /district bind 收编旧的父领地 (20.6)。只读 Flan, 不写。
            List<String> existing = intersectingClaims(bounds);
            if (!existing.isEmpty()) {
                return new CommandResult(CommandOutcome.FLAN_CLAIM_EXISTS, null, existing);
            }
            String districtId = nextDistrictId(academyId);
            DistrictRecord district = new DistrictRecord(districtId, academyId, academy.get().districtDisplayName(),
                    bounds, List.of(), null, null, price, DistrictLimits.DEFAULT_MIN_SIDE,
                    DistrictLimits.DEFAULT_MAX_SIDE, false, 1, null, false, at, actor.name(), null, null, null, null);
            repo.insertDistrict(district);
            repo.insertDistrictDefaults(districtId);
            repo.afterCommit(() -> ctx.flanSync().initializeDistrict(districtId));
            return new CommandResult(CommandOutcome.OK, district, List.of());
        });
        if (result.ok() && result.district() != null) {
            AUDIT.info("[miningdim] {} ({}) created district {} for academy {} at {}", actor.name(), actor.uuid(),
                    result.district().districtId(), academyId, bounds);
            return new CommandResult(CommandOutcome.OK,
                    repo.liveDistrict(result.district().districtId()).orElse(result.district()), List.of());
        }
        return result;
    }

    /**
     * 这片范围会压到哪个自管区 (同一维度, 闭区间相交): 未解绑的一律算; 已解绑 (归档) 的也算 —— 解绑刻意不删 Flan 领地,
     * 旧的父领地与地块子领地都还在那片地上, 别的学院再绑上去就会和它们叠在一起 (12.3)。唯一的例外是同一个学院把自己
     * 原来那片地重新绑回来 (P2 的重新绑定, 旧子领地怎么处理留给阶段 2 的对账)。
     *
     * @param exceptDistrictId 不和自己比 (改范围时是本区; 建区时为 null)
     */
    private Optional<DistrictRecord> overlappingDistrict(String academyId, DistrictBounds bounds,
                                                         @Nullable String exceptDistrictId) {
        for (DistrictRecord other : repo.liveDistricts()) {
            if (!other.districtId().equals(exceptDistrictId) && other.bounds().overlaps(bounds)) {
                return Optional.of(other);
            }
        }
        for (DistrictRecord archived : repo.archivedDistricts(Integer.MAX_VALUE)) {
            if (!archived.academyId().equals(academyId) && archived.bounds().overlaps(bounds)) {
                return Optional.of(archived);
            }
        }
        return Optional.empty();
    }

    private String nextDistrictId(String academyId) {
        if (repo.anyDistrict(academyId).isEmpty()) {
            return academyId;
        }
        int n = Math.max(2, repo.districtsOfAcademy(academyId).size() + 1);
        while (repo.anyDistrict(academyId + "-" + n).isPresent()) {
            n++;
        }
        return academyId + "-" + n;
    }

    /**
     * 这片范围上已有的顶层 Flan 领地 (管理员领地后缀"(admin)"); 领地对接没有生效时无从查起, 返回空。
     */
    private List<String> intersectingClaims(DistrictBounds bounds) {
        if (!ctx.gateway().available()) {
            return List.of();
        }
        List<String> found = new ArrayList<>();
        for (ClaimHandle claim : ctx.gateway().claimsIntersecting(bounds.dimension(), bounds)) {
            boolean admin = ctx.gateway().inspectClaim(claim).map(ClaimInfo::adminClaim).orElse(false);
            found.add(claim.claimId() + (admin ? " (admin)" : ""));
        }
        return found;
    }

    /**
     * 改自管区范围, 只改库 (A15)。阶段 2 起只在本区<b>还没有父领地</b>时可用 (功能降级时建的区): 已有父领地时范围以
     * Flan 为准, 拒绝并提示改用金锄头 + {@link #syncBoundsFromClaim} (BOUNDS_FOLLOW_CLAIM, 20.6)。与建区同一套重叠规则
     * (其他未解绑的区、别的学院已解绑的区); 有地块会落到新范围外或离边界不足 2 格时拒绝, 并列出这些地块的编号; 提交后
     * 按库里的范围建父领地 (建不成置对账标记)。
     */
    public CommandResult setBounds(Actor actor, String districtId, DistrictBounds bounds) {
        requireNoOpenTransaction("setBounds");
        CommandResult result = repo.inTransaction(() -> {
            Optional<DistrictRecord> found = repo.liveDistrict(districtId);
            if (found.isEmpty()) {
                return CommandResult.of(CommandOutcome.DISTRICT_NOT_FOUND, districtId);
            }
            DistrictRecord district = found.get();
            if (district.flanClaimId() != null) {
                return CommandResult.of(CommandOutcome.BOUNDS_FOLLOW_CLAIM, districtId);
            }
            if (bounds.minX() > bounds.maxX() || bounds.minZ() > bounds.maxZ()) {
                return CommandResult.of(CommandOutcome.BOUNDS_INVALID);
            }
            CommandResult rejected = checkNewBounds(district, bounds);
            if (rejected != null) {
                return rejected;
            }
            repo.setBounds(districtId, bounds);
            repo.afterCommit(() -> ctx.flanSync().initializeDistrict(districtId));
            return new CommandResult(CommandOutcome.OK, district, List.of());
        });
        if (result.ok()) {
            AUDIT.info("[miningdim] {} ({}) set the bounds of {} to {}", actor.name(), actor.uuid(), districtId,
                    bounds);
            return new CommandResult(CommandOutcome.OK, repo.liveDistrict(districtId).orElse(null), List.of());
        }
        return result;
    }

    /** 新范围的校验 (改范围与 bounds sync 共用): 重叠规则, 以及每块地都在新范围内、离边界不少于 2 格。 */
    @Nullable
    private CommandResult checkNewBounds(DistrictRecord district, DistrictBounds bounds) {
        Optional<DistrictRecord> overlapping = overlappingDistrict(district.academyId(), bounds, district.districtId());
        if (overlapping.isPresent()) {
            return CommandResult.of(CommandOutcome.OVERLAPS_DISTRICT, overlapping.get().districtId());
        }
        List<String> outside = new ArrayList<>();
        for (PlotRecord plot : repo.plotsOf(district.districtId())) {
            // 只查范围与边距: 尺寸上下限与重叠在改范围时不变。
            DistrictRuleException problem = PlotGeometry.check(bounds, plot.area(), DistrictLimits.EDGE_GAP,
                    1, Integer.MAX_VALUE, List.of());
            if (problem != null || !bounds.dimension().equals(district.bounds().dimension())) {
                outside.add(plot.code());
            }
        }
        return outside.isEmpty() ? null : new CommandResult(CommandOutcome.PLOTS_OUTSIDE, null, outside);
    }

    /**
     * /district bounds &lt;id&gt; sync (20.6): 读父领地的 X/Z (OP 在游戏里用金锄头改过), 按改范围的规则校验后写库; 与库里
     * 相同时回 OK, detail 为 "unchanged"。不通过时库不动 (对账每一轮都会报告范围漂移, 超出父领地的那部分地块没有保护)。
     */
    public CommandResult syncBoundsFromClaim(Actor actor, String districtId) {
        requireNoOpenTransaction("syncBoundsFromClaim");
        Optional<DistrictRecord> found = repo.liveDistrict(districtId);
        if (found.isEmpty()) {
            return CommandResult.of(CommandOutcome.DISTRICT_NOT_FOUND, districtId);
        }
        if (!ctx.gateway().available()) {
            return CommandResult.of(CommandOutcome.FLAN_UNAVAILABLE);
        }
        DistrictRecord district = found.get();
        Optional<ClaimInfo> info = district.flanClaimId() == null
                ? Optional.empty()
                : ctx.gateway().findDistrictClaim(district.bounds().dimension(), district.flanClaimId())
                .flatMap(ctx.gateway()::inspectClaim);
        if (info.isEmpty()) {
            return CommandResult.of(CommandOutcome.CLAIM_NOT_FOUND, String.valueOf(district.flanClaimId()));
        }
        DistrictBounds bounds = new DistrictBounds(district.bounds().dimension(), info.get().minX(),
                info.get().minZ(), info.get().maxX(), info.get().maxZ());
        if (bounds.equals(district.bounds())) {
            return new CommandResult(CommandOutcome.OK, district, List.of("unchanged"));
        }
        CommandResult result = repo.inTransaction(() -> {
            DistrictRecord current = requireLive(districtId);
            CommandResult rejected = checkNewBounds(current, bounds);
            if (rejected != null) {
                return rejected;
            }
            repo.setBounds(districtId, bounds);
            return new CommandResult(CommandOutcome.OK, current, List.of());
        });
        if (result.ok()) {
            AUDIT.info("[miningdim] {} ({}) synced the bounds of {} from its Flan claim: {} -> {}", actor.name(),
                    actor.uuid(), districtId, district.bounds(), bounds);
            return new CommandResult(CommandOutcome.OK, repo.liveDistrict(districtId).orElse(null), List.of());
        }
        return result;
    }

    // ================================================================
    // 绑定已有的管理员领地、重建父领地 (20.6)
    // ================================================================

    /**
     * /district bind &lt;academyId&gt; &lt;pos&gt; [confirm]: 把那一列上的顶层管理员领地收编成这个学院的自管区。不确认时只预览
     * (PREVIEW, detail 是预览的每一行), 一个字都不写; 确认后插入自管区行 (flan_claim_id 直接写成这块领地的 id, 范围取
     * 领地的 X/Z), 提交后收编: 强制备份、删掉其上全部子领地、全部组与成员、假玩家、药水与放行清单, 设名字, 底补到世界底,
     * 显式存盘, 写满父领地的状态 (失败置对账标记)。
     *
     * <p>检查顺序: 学院存在; 学院没有在用的自管区; 领地对接生效; 那里有领地; 是管理员领地; 是 2D 领地 (Flan 只按顶层
     * 领地判定, 3D 领地不含的高度在每块地里都是野外, 又补不了; 20.3); 没有被别的在用自管区绑着 (在事务里查: 服务器线程
     * 单线程, 不另加唯一索引); 被已解绑的区绑过时只能是同一个学院; 以领地的 X/Z 作范围通过与建区相同的重叠规则。
     */
    public CommandResult bindDistrict(Actor actor, String academyId, String dimension, int x, int z, boolean confirm) {
        requireNoOpenTransaction("bindDistrict");
        long at = ctx.now();
        FlanGateway gateway = ctx.gateway();
        CommandResult result = repo.inTransaction(() -> {
            Optional<Academy> academy = repo.academy(academyId);
            if (academy.isEmpty()) {
                return CommandResult.of(CommandOutcome.ACADEMY_UNKNOWN, academyId);
            }
            Optional<DistrictRecord> live = repo.liveDistrictOfAcademy(academyId);
            if (live.isPresent()) {
                return CommandResult.of(CommandOutcome.ACADEMY_ALREADY_BOUND, live.get().districtId());
            }
            if (!gateway.available()) {
                return CommandResult.of(CommandOutcome.FLAN_UNAVAILABLE);
            }
            Optional<ClaimInfo> found = gateway.districtClaimAt(dimension, x, z).flatMap(gateway::inspectClaim);
            if (found.isEmpty()) {
                return CommandResult.of(CommandOutcome.CLAIM_NOT_FOUND, dimension + " " + x + " " + z);
            }
            ClaimInfo info = found.get();
            UUID claimId = info.handle().claimId();
            if (!info.adminClaim()) {
                return CommandResult.of(CommandOutcome.CLAIM_NOT_ADMIN, claimId.toString());
            }
            if (!info.flat()) {
                return CommandResult.of(CommandOutcome.CLAIM_NOT_FULL_HEIGHT, claimId.toString());
            }
            for (DistrictRecord other : repo.liveDistricts()) {
                if (claimId.equals(other.flanClaimId())) {
                    return CommandResult.of(CommandOutcome.CLAIM_ALREADY_BOUND, other.districtId());
                }
            }
            for (DistrictRecord archived : repo.archivedDistricts(Integer.MAX_VALUE)) {
                if (claimId.equals(archived.flanClaimId()) && !archived.academyId().equals(academyId)) {
                    return CommandResult.of(CommandOutcome.OVERLAPS_DISTRICT, archived.districtId());
                }
            }
            DistrictBounds bounds = new DistrictBounds(dimension, info.minX(), info.minZ(), info.maxX(), info.maxZ());
            Optional<DistrictRecord> overlapping = overlappingDistrict(academyId, bounds, null);
            if (overlapping.isPresent()) {
                return CommandResult.of(CommandOutcome.OVERLAPS_DISTRICT, overlapping.get().districtId());
            }
            if (!confirm) {
                return new CommandResult(CommandOutcome.PREVIEW, null, preview(info, bounds), bounds);
            }
            String districtId = nextDistrictId(academyId);
            DistrictRecord district = new DistrictRecord(districtId, academyId, academy.get().districtDisplayName(),
                    bounds, List.of(), null, null, DistrictLimits.DEFAULT_UNIT_PRICE, DistrictLimits.DEFAULT_MIN_SIDE,
                    DistrictLimits.DEFAULT_MAX_SIDE, false, 1, claimId, false, at, actor.name(), null, null, null, null);
            repo.insertDistrict(district);
            repo.insertDistrictDefaults(districtId);
            repo.afterCommit(() -> ctx.flanSync().adoptDistrictClaim(districtId));
            return new CommandResult(CommandOutcome.OK, district, List.of());
        });
        if (result.ok() && result.district() != null) {
            AUDIT.info("[miningdim] {} ({}) bound Flan admin claim {} as district {} of academy {} at {}", actor.name(),
                    actor.uuid(), result.district().flanClaimId(), result.district().districtId(), academyId,
                    result.district().bounds());
            return new CommandResult(CommandOutcome.OK,
                    repo.liveDistrict(result.district().districtId()).orElse(result.district()), List.of());
        }
        return result;
    }

    /** bind 预览的每一行 (只读 Flan)。 */
    private List<String> preview(ClaimInfo info, DistrictBounds bounds) {
        FlanGateway gateway = ctx.gateway();
        List<ClaimHandle> subclaims = gateway.listPlotClaims(info.handle());
        int moduleMade = 0;
        for (ClaimHandle sub : subclaims) {
            if (gateway.readPermissions(sub).groups().keySet().stream()
                    .anyMatch(group -> group.startsWith(FlanGroupNames.PLOT_PREFIX))) {
                moduleMade++;
            }
        }
        List<String> lines = new ArrayList<>();
        lines.add("claim " + info.handle().claimId() + " '" + info.name() + "' " + bounds);
        lines.add(heightLine(info));
        lines.add("subclaims " + subclaims.size() + " (" + moduleMade + " made by this module)");
        lines.add("groups " + gateway.readPermissions(info.handle()).groups().keySet() + ", members "
                + gateway.readMembers(info.handle()).size());
        lines.add("fake players " + info.fakePlayers() + ", potions " + info.potions() + ", allow-list entries "
                + info.allowListEntries() + " (all cleared)");
        lines.add(String.valueOf(subclaims.size()));
        return lines;
    }

    /** 预览里的高度一行: 2D 且到底 / 2D 但底偏高 (会补到世界底)。 */
    private static String heightLine(ClaimInfo info) {
        return info.fullHeight()
                ? "height: 2D, full height"
                : "height: 2D, bottom y " + info.minY() + " (extended down to the world bottom " + info.worldMinY()
                + "; below it every plot would be unprotected wilderness)";
    }

    /**
     * /district claim &lt;id&gt; recreate (20.6): 库里记着的父领地在 Flan 里找不到时 (有人执行了 /flan adminDelete, 或恢复了
     * 更早的备份), OP 确认后按库里的范围新建一块 (检查与步骤同新建), 新 id 写回库, 再跑一轮 resync 把全部地块按库重建。
     */
    public CommandResult recreateDistrictClaim(Actor actor, String districtId) {
        requireNoOpenTransaction("recreateDistrictClaim");
        Optional<DistrictRecord> found = repo.liveDistrict(districtId);
        if (found.isEmpty()) {
            return CommandResult.of(CommandOutcome.DISTRICT_NOT_FOUND, districtId);
        }
        FlanGateway gateway = ctx.gateway();
        if (!gateway.available()) {
            return CommandResult.of(CommandOutcome.FLAN_UNAVAILABLE);
        }
        DistrictRecord district = found.get();
        if (district.flanClaimId() != null
                && gateway.findDistrictClaim(district.bounds().dimension(), district.flanClaimId()).isPresent()) {
            return CommandResult.of(CommandOutcome.CLAIM_PRESENT, district.flanClaimId().toString());
        }
        List<String> existing = intersectingClaims(district.bounds());
        if (!existing.isEmpty()) {
            return new CommandResult(CommandOutcome.FLAN_CLAIM_EXISTS, null, existing);
        }
        FlanResult<ClaimHandle> created = ctx.flanSync().recreateDistrictClaim(districtId);
        if (!created.ok() || created.value() == null) {
            return CommandResult.of(CommandOutcome.FLAN_FAILED, created.error() == null ? "?" : created.error());
        }
        DistrictReconciler.DistrictProgress resync = ctx.reconciler().reconcileDistrict(districtId,
                DistrictReconciler.Mode.EXPLICIT);
        AUDIT.info("[miningdim] {} ({}) recreated the Flan claim of {} as {} ({} ok / {} failed in the resync)",
                actor.name(), actor.uuid(), districtId, created.value().claimId(), resync.succeeded(), resync.failed());
        return new CommandResult(CommandOutcome.OK, repo.liveDistrict(districtId).orElse(district),
                List.of(created.value().claimId().toString(), String.valueOf(resync.succeeded()),
                        String.valueOf(resync.failed())));
    }

    /**
     * /district claim &lt;id&gt; relink &lt;pos&gt; [confirm] (20.6): 本区在库里记着的父领地在 Flan 里找不到了 (或还没记下),
     * 而那片地上其实有一块管理员领地 —— 恢复了不同时刻的领地备份或 miningdim.db、建父领地之后没能把 id 写进库 ——
     * recreate 会因"这片范围已有领地"被拒, bind 又因"学院已有自管区"被拒。relink 让本区改认那一列上的顶层管理员领地:
     * 检查同 bind (管理员领地、2D、没被别的在用区绑着、被已解绑的区绑过时只能是同一个学院), 范围取领地的 X/Z 并按改范围的
     * 规则校验 (重叠、每块地都在范围内); 不确认只预览。确认后写库 (领地 id 与范围), 再跑一轮 resync: 先强制备份, 按库
     * 重写父领地 (组、成员、杂项、补到世界底), 认回带着本区组名的地块子领地, 删掉其上外来的子领地, 缺的地块重建。
     * 维度取本区所在的维度。
     */
    public CommandResult relinkDistrictClaim(Actor actor, String districtId, int x, int z, boolean confirm) {
        requireNoOpenTransaction("relinkDistrictClaim");
        Optional<DistrictRecord> found = repo.liveDistrict(districtId);
        if (found.isEmpty()) {
            return CommandResult.of(CommandOutcome.DISTRICT_NOT_FOUND, districtId);
        }
        FlanGateway gateway = ctx.gateway();
        if (!gateway.available()) {
            return CommandResult.of(CommandOutcome.FLAN_UNAVAILABLE);
        }
        DistrictRecord district = found.get();
        String dimension = district.bounds().dimension();
        if (district.flanClaimId() != null && gateway.findDistrictClaim(dimension, district.flanClaimId()).isPresent()) {
            return CommandResult.of(CommandOutcome.CLAIM_PRESENT, district.flanClaimId().toString());
        }
        Optional<ClaimInfo> located = gateway.districtClaimAt(dimension, x, z).flatMap(gateway::inspectClaim);
        if (located.isEmpty()) {
            return CommandResult.of(CommandOutcome.CLAIM_NOT_FOUND, dimension + " " + x + " " + z);
        }
        ClaimInfo info = located.get();
        UUID claimId = info.handle().claimId();
        if (!info.adminClaim()) {
            return CommandResult.of(CommandOutcome.CLAIM_NOT_ADMIN, claimId.toString());
        }
        if (!info.flat()) {
            return CommandResult.of(CommandOutcome.CLAIM_NOT_FULL_HEIGHT, claimId.toString());
        }
        DistrictBounds bounds = new DistrictBounds(dimension, info.minX(), info.minZ(), info.maxX(), info.maxZ());
        CommandResult result = repo.inTransaction(() -> {
            DistrictRecord current = requireLive(districtId);
            for (DistrictRecord other : repo.liveDistricts()) {
                if (!other.districtId().equals(districtId) && claimId.equals(other.flanClaimId())) {
                    return CommandResult.of(CommandOutcome.CLAIM_ALREADY_BOUND, other.districtId());
                }
            }
            for (DistrictRecord archived : repo.archivedDistricts(Integer.MAX_VALUE)) {
                if (claimId.equals(archived.flanClaimId()) && !archived.academyId().equals(current.academyId())) {
                    return CommandResult.of(CommandOutcome.OVERLAPS_DISTRICT, archived.districtId());
                }
            }
            CommandResult rejected = checkNewBounds(current, bounds);
            if (rejected != null) {
                return rejected;
            }
            if (!confirm) {
                List<String> lines = new ArrayList<>(preview(info, bounds));
                lines.remove(lines.size() - 1);
                lines.add(1, "district bounds " + current.bounds() + (bounds.equals(current.bounds()) ? " (unchanged)"
                        : " -> " + bounds));
                lines.add(claimId.toString());
                return new CommandResult(CommandOutcome.PREVIEW, null, lines, bounds);
            }
            repo.setDistrictClaimId(districtId, claimId);
            if (!bounds.equals(current.bounds())) {
                repo.setBounds(districtId, bounds);
            }
            return new CommandResult(CommandOutcome.OK, current, List.of());
        });
        if (!result.ok()) {
            return result;
        }
        DistrictReconciler.DistrictProgress resync = ctx.reconciler().reconcileDistrict(districtId,
                DistrictReconciler.Mode.EXPLICIT);
        AUDIT.info("[miningdim] {} ({}) relinked district {} to Flan admin claim {} at {} (was {}; {} ok / {} failed "
                        + "in the resync)", actor.name(), actor.uuid(), districtId, claimId, bounds,
                district.flanClaimId(), resync.succeeded(), resync.failed());
        return new CommandResult(CommandOutcome.OK, repo.liveDistrict(districtId).orElse(district),
                List.of(claimId.toString(), String.valueOf(resync.succeeded()), String.valueOf(resync.failed())));
    }

    /** 维护区规 (整张替换)。 */
    public CommandResult setRules(Actor actor, String districtId, List<String> rules) {
        requireNoOpenTransaction("setRules");
        Optional<DistrictRecord> found = repo.liveDistrict(districtId);
        if (found.isEmpty()) {
            return CommandResult.of(CommandOutcome.DISTRICT_NOT_FOUND, districtId);
        }
        repo.inTransaction(() -> {
            repo.setRules(districtId, rules);
            return null;
        });
        AUDIT.info("[miningdim] {} ({}) set {} rule(s) for {}", actor.name(), actor.uuid(), rules.size(), districtId);
        return new CommandResult(CommandOutcome.OK, repo.liveDistrict(districtId).orElse(null), List.of());
    }

    /**
     * 把人移出已解绑学院的名单 (12.4, P12): 只对没有在用自管区的学院生效。此人若还登记为已解绑自管区里一块地的户主,
     * 先清空户主 (不写 Flan) 并在那块地的当前任期写一行 vacate, 再删名单行。
     */
    public CommandResult kickFromUnboundAcademy(Actor actor, String academyId, String typedName) {
        requireNoOpenTransaction("kickFromUnboundAcademy");
        long at = ctx.now();
        CommandResult result = repo.inTransaction(() -> {
            if (repo.academy(academyId).isEmpty()) {
                return CommandResult.of(CommandOutcome.ACADEMY_UNKNOWN, academyId);
            }
            Optional<DistrictRecord> live = repo.liveDistrictOfAcademy(academyId);
            if (live.isPresent()) {
                return CommandResult.of(CommandOutcome.ACADEMY_STILL_BOUND, live.get().districtId());
            }
            Optional<MemberRecord> member = repo.memberByNameLower(DistrictTexts.lower(typedName.trim()))
                    .filter(m -> m.academyId().equals(academyId));
            if (member.isEmpty()) {
                return CommandResult.of(CommandOutcome.NOT_MEMBER, typedName);
            }
            Optional<PlotRecord> owned = repo.plotOwnedBy(member.get().uuid());
            if (owned.isPresent()) {
                PlotRecord plot = owned.get();
                repo.releaseOwner(plot.plotId());
                writePlotLog(new PlotLogEntry(0, plot.plotId(), plot.districtId(), plot.tenure(), at, actor.uuid(),
                        actor.name(), PlotActorRole.ADMIN, PlotLogAction.VACATE, member.get().name(),
                        DistrictTexts.PLOT_NOTE_KICK_ARCHIVED, null, null, false));
            }
            repo.deleteMember(member.get().uuid());
            return CommandResult.of(CommandOutcome.OK, member.get().name());
        });
        if (result.ok()) {
            AUDIT.info("[miningdim] {} ({}) removed {} from the roster of unbound academy {}", actor.name(),
                    actor.uuid(), result.detail(), academyId);
        }
        return result;
    }

    private static DistrictLogEntry districtLog(DistrictRecord district, Actor actor, long at,
                                                DistrictLogAction action, @Nullable String target,
                                                @Nullable String reason) {
        return new DistrictLogEntry(0, district.districtId(), at, actor.uuid(), actor.name(),
                DistrictActorRole.ADMIN, action, target, reason, null, null);
    }
}
