package com.miningdim.district.web;

import com.google.gson.JsonObject;
import com.miningdim.district.DistrictFeature;
import com.miningdim.district.DistrictServices;
import com.miningdim.district.access.Actor;
import com.miningdim.district.access.DistrictAccess;
import com.miningdim.district.access.PlotRelation;
import com.miningdim.district.core.DistrictActionNames;
import com.miningdim.district.core.DistrictRecord;
import com.miningdim.district.core.DistrictRuleException;
import com.miningdim.district.core.DistrictTexts;
import com.miningdim.district.core.MemberRecord;
import com.miningdim.district.core.PlotArea;
import com.miningdim.district.core.PlotLogEntry;
import com.miningdim.district.service.DistrictAdminService;
import com.miningdim.district.service.DistrictContext;
import com.miningdim.district.service.DistrictPermissionService;
import com.miningdim.district.service.DistrictQueryService;
import com.miningdim.district.service.PlotFreezeService;
import com.miningdim.district.service.PlotLayoutService;
import com.miningdim.district.service.PlotMarketService;
import com.miningdim.district.service.PlotOwnerService;
import com.miningdim.district.service.ResidentService;
import com.miningdim.district.store.DistrictStoreException;
import com.miningdim.store.MiningStoreException;
import com.miningdim.webui.server.WebUiBusinessException;
import com.miningdim.webui.server.WebUiErrorCodes;
import com.miningdim.webui.server.WebUiPayloads;
import com.miningdim.webui.server.WebUiPermissions;
import com.miningdim.webui.server.WebUiServerDispatcher;
import com.miningdim.webui.server.WebUiServerDispatcher.WebUiAction;
import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * 自管区的 26 条平板动作 (district.* / plot.* / admin.district.* / admin.plot.*, 设计文档第十四章), 由
 * {@code DistrictSystem.register} 登记进派发器。
 *
 * 每条 handler 只做三件事: 读入参、调服务方法、交 {@link DistrictJson} 构造回执。业务规则、检查顺序与拒绝文案全在服务层
 * (与 /district 命令共用同一批服务方法), 身份在服务层每次从库里现算。几条共同约定:
 * <ul>
 *   <li>服务从 {@link DistrictServices#context()} 在<b>调用时</b>取: GameTest 换上的测试 context 因此能经真实登记的
 *       handler 验证; 取不到 (功能没有开启) 时抛 DISTRICT_DISABLED (设计文档 20.9); 领地对接降级时 20 条写动作同样
 *       回 DISTRICT_DISABLED (文案带降级原因), 六条读动作照常 (P21)。</li>
 *   <li>admin.* 动作的第一句是 {@link WebUiPermissions#requireOp}: 管理员门排在一切入参读取与查区之前。</li>
 *   <li>机器字段 (districtId、plotId 等) 缺失或类型不对当场报 INVALID_REQUEST; 其余字段读成可空的值交服务层, 在服务层
 *       按契约检查顺序校验它的那一步报错 (见 {@link DistrictPayloads})。</li>
 *   <li>{@link #guard} 把 {@link DistrictRuleException} 一对一转成 {@link WebUiBusinessException}; 数据库失败 (事务已
 *       回滚) 记一条 ERROR 后转成 STORE_FAILED —— 这是把失败换成契约里的业务码, 不是吞异常。</li>
 *   <li>写动作一律不进 {@code WebUiBatchAction.BATCHABLE} (批内无防重放); 读动作不写库, 也不做到期收回。</li>
 * </ul>
 */
public final class DistrictWebUiActions {

    private static final Logger LOGGER = LoggerFactory.getLogger("miningdim/district");

    private static final String STORE_FAILED_MESSAGE = "数据库读写失败，这次操作没有生效";

    /** DISTRICT_DISABLED 的文案 (功能没有开启, 服务没有绑定)。 */
    static final String DISABLED_MESSAGE = "自管区功能没有开启";

    private DistrictWebUiActions() {
    }

    // ================================================================
    // 读动作
    // ================================================================

    static final WebUiAction STATE = (sender, payload) -> guard(DistrictActionNames.STATE, sender,
            () -> DistrictJson.state(ctx().queries().state(actorOf(sender))));

    static final WebUiAction DETAIL = (sender, payload) -> {
        String districtId = DistrictPayloads.id(payload, "districtId");
        return guard(DistrictActionNames.DETAIL, sender,
                () -> DistrictJson.detail(ctx().queries().detail(actorOf(sender), districtId)));
    };

    static final WebUiAction PERMISSIONS = (sender, payload) -> {
        String districtId = DistrictPayloads.id(payload, "districtId");
        return guard(DistrictActionNames.PERMISSIONS, sender, () -> {
            DistrictQueryService.PermissionsView view = ctx().queries().permissions(actorOf(sender), districtId);
            return DistrictJson.permissions(view.district(), view.access(), view.cells());
        });
    };

    static final WebUiAction PLOTS = (sender, payload) -> {
        String districtId = DistrictPayloads.id(payload, "districtId");
        return guard(DistrictActionNames.PLOTS, sender,
                () -> DistrictJson.plots(ctx().queries().plots(actorOf(sender), sender, districtId)));
    };

    static final WebUiAction PLOT_DETAIL = (sender, payload) -> {
        String districtId = DistrictPayloads.id(payload, "districtId");
        String plotId = DistrictPayloads.id(payload, "plotId");
        return guard(DistrictActionNames.PLOT_DETAIL, sender,
                () -> DistrictJson.plotDetail(ctx().queries().plotDetail(actorOf(sender), districtId, plotId)));
    };

    static final WebUiAction ARCHIVE = (sender, payload) -> {
        WebUiPermissions.requireOp(sender, DistrictActionNames.ARCHIVE);
        return guard(DistrictActionNames.ARCHIVE, sender,
                () -> DistrictJson.archive(ctx().queries().archive(actorOf(sender))));
    };

    // ================================================================
    // 住户与区务长
    // ================================================================

    static final WebUiAction ADD_RESIDENT = (sender, payload) -> {
        String districtId = DistrictPayloads.id(payload, "districtId");
        String playerName = DistrictPayloads.id(payload, "playerName");
        boolean allowNeverJoined = DistrictPayloads.optionalTrue(payload, "allowNeverJoined");
        return guard(DistrictActionNames.ADD_RESIDENT, sender, () -> {
            Actor actor = actorOf(sender);
            DistrictContext ctx = ctxForWrite();
            ResidentService.AddResult result = ctx.residents().addResident(actor, districtId, playerName,
                    allowNeverJoined);
            JsonObject json = new JsonObject();
            json.add("resident", residentJson(ctx, districtId, result.resident(), actor.op()));
            json.add("logEntry", DistrictJson.districtLog(result.logEntry()));
            json.add("frozenPlot", DistrictJson.plotRef(result.frozenPlot()));
            return json;
        });
    };

    static final WebUiAction REMOVE_RESIDENT = (sender, payload) -> {
        String districtId = DistrictPayloads.id(payload, "districtId");
        String playerName = DistrictPayloads.id(payload, "playerName");
        String reasonKind = DistrictPayloads.optionalString(payload, "reasonKind");
        String reason = DistrictPayloads.optionalString(payload, "reason");
        return guard(DistrictActionNames.REMOVE_RESIDENT, sender, () -> {
            ResidentService.RemoveResult result = ctxForWrite().residents().removeResident(actorOf(sender), districtId,
                    playerName, reasonKind, reason);
            JsonObject json = new JsonObject();
            json.add("logEntry", DistrictJson.districtLog(result.logEntry()));
            json.add("frozenPlot", DistrictJson.plotRef(result.frozenPlot()));
            json.addProperty("reclaimAt", result.reclaimAt());
            json.addProperty("suspendedFriendOfPlots", result.suspendedFriendOfPlots());
            json.addProperty("stillFriendOfPlots", result.stillFriendOfPlots());
            return json;
        });
    };

    static final WebUiAction RETRY_SYNC = (sender, payload) -> {
        WebUiPermissions.requireOp(sender, DistrictActionNames.RETRY_SYNC);
        String districtId = DistrictPayloads.id(payload, "districtId");
        String playerName = DistrictPayloads.id(payload, "playerName");
        return guard(DistrictActionNames.RETRY_SYNC, sender, () -> {
            DistrictContext ctx = ctxForWrite();
            MemberRecord member = ctx.residents().retrySync(actorOf(sender), districtId, playerName);
            JsonObject json = new JsonObject();
            json.add("resident", residentJson(ctx, districtId, member, true));
            return json;
        });
    };

    static final WebUiAction SET_WARDEN = (sender, payload) -> {
        WebUiPermissions.requireOp(sender, DistrictActionNames.SET_WARDEN);
        String districtId = DistrictPayloads.id(payload, "districtId");
        // 缺键是 INVALID_REQUEST, 只有显式的 null 才是撤销。
        String playerName = WebUiPayloads.requiredNullableString(payload, "playerName");
        return guard(DistrictActionNames.SET_WARDEN, sender, () -> {
            DistrictAdminService.WardenResult result = ctxForWrite().admin().setWarden(actorOf(sender), districtId,
                    playerName);
            JsonObject json = new JsonObject();
            json.addProperty("wardenName", result.wardenName());
            json.add("logEntry", DistrictJson.districtLog(result.logEntry()));
            return json;
        });
    };

    static final WebUiAction DELETE = (sender, payload) -> {
        WebUiPermissions.requireOp(sender, DistrictActionNames.DELETE);
        String districtId = DistrictPayloads.id(payload, "districtId");
        return guard(DistrictActionNames.DELETE, sender, () -> {
            DistrictAdminService.UnbindResult result = ctxForWrite().admin().unbind(actorOf(sender), districtId);
            JsonObject json = new JsonObject();
            json.addProperty("districtId", result.districtId());
            json.addProperty("keptMembers", result.keptMembers());
            json.addProperty("keptPlots", result.keptPlots());
            return json;
        });
    };

    // ================================================================
    // 公共区域开关
    // ================================================================

    static final WebUiAction SET_PERMISSION = (sender, payload) -> {
        String districtId = DistrictPayloads.id(payload, "districtId");
        String permissionId = DistrictPayloads.id(payload, "permissionId");
        String audience = DistrictPayloads.optionalString(payload, "audience");
        Boolean enabled = DistrictPayloads.strictBoolean(payload, "enabled");
        return guard(DistrictActionNames.SET_PERMISSION, sender, () -> {
            DistrictPermissionService.SetResult result = ctxForWrite().permissions().setPermission(actorOf(sender),
                    districtId, permissionId, audience, enabled);
            JsonObject json = new JsonObject();
            json.add("item", DistrictJson.permissionItem(result.item(), result.cells(), result.access()));
            json.add("logEntry", DistrictJson.districtLog(result.logEntry()));
            return json;
        });
    };

    static final WebUiAction RESET_PERMISSIONS = (sender, payload) -> {
        String districtId = DistrictPayloads.id(payload, "districtId");
        String scope = DistrictPayloads.optionalString(payload, "scope");
        return guard(DistrictActionNames.RESET_PERMISSIONS, sender, () -> {
            DistrictPermissionService.ResetResult result = ctxForWrite().permissions().resetPermissions(actorOf(sender),
                    districtId, scope);
            return DistrictJson.resetPermissions(result.district(), result.access(), result.cells(),
                    result.logEntries());
        });
    };

    // ================================================================
    // 户主管自己的地块
    // ================================================================

    static final WebUiAction PLOT_SET_PERMISSION = (sender, payload) -> {
        String districtId = DistrictPayloads.id(payload, "districtId");
        String plotId = DistrictPayloads.id(payload, "plotId");
        String permissionId = DistrictPayloads.id(payload, "permissionId");
        String audience = DistrictPayloads.optionalString(payload, "audience");
        Boolean enabled = DistrictPayloads.strictBoolean(payload, "enabled");
        return guard(DistrictActionNames.PLOT_SET_PERMISSION, sender, () -> {
            PlotOwnerService.SetResult result = ctxForWrite().plotOwners().setPermission(actorOf(sender), districtId,
                    plotId, permissionId, audience, enabled);
            JsonObject json = new JsonObject();
            json.add("item", DistrictJson.plotPermissionItem(result.item(), result.cells(),
                    result.relation() == PlotRelation.ADMIN));
            json.add("logEntry", DistrictJson.plotLog(result.logEntry()));
            return json;
        });
    };

    static final WebUiAction PLOT_RESET_PERMISSIONS = (sender, payload) -> {
        String districtId = DistrictPayloads.id(payload, "districtId");
        String plotId = DistrictPayloads.id(payload, "plotId");
        return guard(DistrictActionNames.PLOT_RESET_PERMISSIONS, sender, () -> {
            Actor actor = actorOf(sender);
            DistrictContext ctx = ctxForWrite();
            List<PlotLogEntry> entries = ctx.plotOwners().resetPermissions(actor, districtId, plotId).logEntries();
            return DistrictJson.resetPlotPermissions(ctx.queries().plotDetail(actor, districtId, plotId), entries);
        });
    };

    static final WebUiAction PLOT_ADD_FRIEND = (sender, payload) -> {
        String districtId = DistrictPayloads.id(payload, "districtId");
        String plotId = DistrictPayloads.id(payload, "plotId");
        String playerName = DistrictPayloads.id(payload, "playerName");
        boolean allowNeverJoined = DistrictPayloads.optionalTrue(payload, "allowNeverJoined");
        return guard(DistrictActionNames.PLOT_ADD_FRIEND, sender, () -> friendResult(ctxForWrite().plotOwners()
                .addFriend(actorOf(sender), districtId, plotId, playerName, allowNeverJoined)));
    };

    static final WebUiAction PLOT_REMOVE_FRIEND = (sender, payload) -> {
        String districtId = DistrictPayloads.id(payload, "districtId");
        String plotId = DistrictPayloads.id(payload, "plotId");
        String playerName = DistrictPayloads.id(payload, "playerName");
        return guard(DistrictActionNames.PLOT_REMOVE_FRIEND, sender, () -> {
            PlotLogEntry log = ctxForWrite().plotOwners().removeFriend(actorOf(sender), districtId, plotId, playerName);
            JsonObject json = new JsonObject();
            json.add("logEntry", DistrictJson.plotLog(log));
            return json;
        });
    };

    static final WebUiAction PLOT_RESTORE_FRIEND = (sender, payload) -> {
        String districtId = DistrictPayloads.id(payload, "districtId");
        String plotId = DistrictPayloads.id(payload, "plotId");
        String playerName = DistrictPayloads.id(payload, "playerName");
        return guard(DistrictActionNames.PLOT_RESTORE_FRIEND, sender, () -> friendResult(ctxForWrite().plotOwners()
                .restoreFriend(actorOf(sender), districtId, plotId, playerName)));
    };

    // ================================================================
    // 划地块与买地
    // ================================================================

    static final WebUiAction PLOT_CREATE = (sender, payload) -> {
        String districtId = DistrictPayloads.id(payload, "districtId");
        PlotLayoutService.AreaInput area = DistrictPayloads.area(payload);
        return guard(DistrictActionNames.PLOT_CREATE, sender, () -> {
            Actor actor = actorOf(sender);
            DistrictContext ctx = ctxForWrite();
            PlotLayoutService.LayoutResult result = ctx.layout().create(actor, districtId, area);
            return layoutResult(ctx, actor, districtId, result, false);
        });
    };

    static final WebUiAction PLOT_RESIZE = (sender, payload) -> {
        String districtId = DistrictPayloads.id(payload, "districtId");
        String plotId = DistrictPayloads.id(payload, "plotId");
        PlotLayoutService.AreaInput area = DistrictPayloads.area(payload);
        return guard(DistrictActionNames.PLOT_RESIZE, sender, () -> {
            Actor actor = actorOf(sender);
            DistrictContext ctx = ctxForWrite();
            PlotLayoutService.LayoutResult result = ctx.layout().resize(actor, districtId, plotId, area);
            return layoutResult(ctx, actor, districtId, result, true);
        });
    };

    static final WebUiAction PLOT_DELETE = (sender, payload) -> {
        String districtId = DistrictPayloads.id(payload, "districtId");
        String plotId = DistrictPayloads.id(payload, "plotId");
        return guard(DistrictActionNames.PLOT_DELETE, sender, () -> {
            JsonObject json = new JsonObject();
            json.add("logEntry", DistrictJson.districtLog(ctxForWrite().layout().delete(actorOf(sender), districtId,
                    plotId)));
            return json;
        });
    };

    static final WebUiAction PLOT_BUY = (sender, payload) -> {
        String districtId = DistrictPayloads.id(payload, "districtId");
        String plotId = DistrictPayloads.id(payload, "plotId");
        long expectedPrice = WebUiPayloads.requiredSafeLong(payload, "expectedPrice");
        PlotArea expectedBounds = DistrictPayloads.requiredArea(payload, "expectedBounds");
        return guard(DistrictActionNames.PLOT_BUY, sender, () -> {
            // 买家永远是调用者本人: 契约里没有"替谁买"的参数, 扣的是发送者自己的钱包。
            PlotMarketService.BuyResult result = ctxForWrite().market().buy(actorOf(sender), sender, districtId, plotId,
                    expectedBounds, expectedPrice);
            JsonObject json = new JsonObject();
            json.add("plot", DistrictJson.plotRef(result.plot()));
            json.addProperty("price", result.price());
            json.addProperty("balanceAfter", result.balanceAfter());
            json.add("logEntry", DistrictJson.districtLog(result.logEntry()));
            return json;
        });
    };

    static final WebUiAction SET_PLOT_PRICING = (sender, payload) -> {
        WebUiPermissions.requireOp(sender, DistrictActionNames.SET_PLOT_PRICING);
        String districtId = DistrictPayloads.id(payload, "districtId");
        Long unitPrice = DistrictPayloads.optionalSafeLong(payload, "unitPrice");
        Long minSide = DistrictPayloads.optionalSafeLong(payload, "minSide");
        Long maxSide = DistrictPayloads.optionalSafeLong(payload, "maxSide");
        return guard(DistrictActionNames.SET_PLOT_PRICING, sender, () -> {
            DistrictAdminService.PricingResult result = ctxForWrite().admin().setPlotPricing(actorOf(sender),
                    districtId, unitPrice, minSide, maxSide);
            JsonObject rules = new JsonObject();
            rules.addProperty("edgeGap", result.edgeGap());
            rules.addProperty("minSide", result.minSide());
            rules.addProperty("maxSide", result.maxSide());
            JsonObject json = new JsonObject();
            json.add("rules", rules);
            json.addProperty("unitPrice", result.unitPrice());
            json.add("logEntry", DistrictJson.districtLog(result.logEntry()));
            return json;
        });
    };

    static final WebUiAction SET_PURCHASE_OPEN = (sender, payload) -> {
        WebUiPermissions.requireOp(sender, DistrictActionNames.SET_PURCHASE_OPEN);
        String districtId = DistrictPayloads.id(payload, "districtId");
        Boolean open = DistrictPayloads.strictBoolean(payload, "open");
        return guard(DistrictActionNames.SET_PURCHASE_OPEN, sender, () -> {
            DistrictAdminService.PurchaseOpenResult result = ctxForWrite().admin().setPurchaseOpen(actorOf(sender),
                    districtId, open);
            JsonObject json = new JsonObject();
            json.addProperty("open", result.open());
            json.add("logEntry", DistrictJson.districtLog(result.logEntry()));
            return json;
        });
    };

    // ================================================================
    // 冻结处置
    // ================================================================

    static final WebUiAction PLOT_UNFREEZE = (sender, payload) -> {
        WebUiPermissions.requireOp(sender, DistrictActionNames.PLOT_UNFREEZE);
        String districtId = DistrictPayloads.id(payload, "districtId");
        String plotId = DistrictPayloads.id(payload, "plotId");
        return guard(DistrictActionNames.PLOT_UNFREEZE, sender, () -> {
            PlotFreezeService.UnfreezeResult result = ctxForWrite().freezes().unfreeze(actorOf(sender), districtId,
                    plotId);
            JsonObject json = new JsonObject();
            json.add("plot", DistrictJson.plotRef(result.plot()));
            json.addProperty("ownerName", result.ownerName());
            json.add("logEntry", DistrictJson.districtLog(result.logEntry()));
            return json;
        });
    };

    static final WebUiAction PLOT_RECLAIM_NOW = (sender, payload) -> {
        WebUiPermissions.requireOp(sender, DistrictActionNames.PLOT_RECLAIM_NOW);
        String districtId = DistrictPayloads.id(payload, "districtId");
        String plotId = DistrictPayloads.id(payload, "plotId");
        return guard(DistrictActionNames.PLOT_RECLAIM_NOW, sender, () -> {
            JsonObject json = new JsonObject();
            json.add("logEntry", DistrictJson.districtLog(ctxForWrite().freezes().reclaimNow(actorOf(sender),
                    districtId, plotId)));
            return json;
        });
    };

    // ================================================================
    // 登记
    // ================================================================

    /** 26 条动作, 按设计文档第十四章的顺序 (测试据此核对全部登记)。 */
    static final Map<String, WebUiAction> ACTIONS = actions();

    private static Map<String, WebUiAction> actions() {
        Map<String, WebUiAction> actions = new LinkedHashMap<>();
        actions.put(DistrictActionNames.STATE, STATE);
        actions.put(DistrictActionNames.DETAIL, DETAIL);
        actions.put(DistrictActionNames.ADD_RESIDENT, ADD_RESIDENT);
        actions.put(DistrictActionNames.REMOVE_RESIDENT, REMOVE_RESIDENT);
        actions.put(DistrictActionNames.RETRY_SYNC, RETRY_SYNC);
        actions.put(DistrictActionNames.SET_WARDEN, SET_WARDEN);
        actions.put(DistrictActionNames.DELETE, DELETE);
        actions.put(DistrictActionNames.PERMISSIONS, PERMISSIONS);
        actions.put(DistrictActionNames.SET_PERMISSION, SET_PERMISSION);
        actions.put(DistrictActionNames.RESET_PERMISSIONS, RESET_PERMISSIONS);
        actions.put(DistrictActionNames.PLOTS, PLOTS);
        actions.put(DistrictActionNames.PLOT_DETAIL, PLOT_DETAIL);
        actions.put(DistrictActionNames.PLOT_SET_PERMISSION, PLOT_SET_PERMISSION);
        actions.put(DistrictActionNames.PLOT_RESET_PERMISSIONS, PLOT_RESET_PERMISSIONS);
        actions.put(DistrictActionNames.PLOT_ADD_FRIEND, PLOT_ADD_FRIEND);
        actions.put(DistrictActionNames.PLOT_REMOVE_FRIEND, PLOT_REMOVE_FRIEND);
        actions.put(DistrictActionNames.PLOT_CREATE, PLOT_CREATE);
        actions.put(DistrictActionNames.PLOT_RESIZE, PLOT_RESIZE);
        actions.put(DistrictActionNames.PLOT_DELETE, PLOT_DELETE);
        actions.put(DistrictActionNames.PLOT_BUY, PLOT_BUY);
        actions.put(DistrictActionNames.SET_PLOT_PRICING, SET_PLOT_PRICING);
        actions.put(DistrictActionNames.SET_PURCHASE_OPEN, SET_PURCHASE_OPEN);
        actions.put(DistrictActionNames.PLOT_UNFREEZE, PLOT_UNFREEZE);
        actions.put(DistrictActionNames.PLOT_RECLAIM_NOW, PLOT_RECLAIM_NOW);
        actions.put(DistrictActionNames.PLOT_RESTORE_FRIEND, PLOT_RESTORE_FRIEND);
        actions.put(DistrictActionNames.ARCHIVE, ARCHIVE);
        return java.util.Collections.unmodifiableMap(actions);
    }

    /** 把 26 条动作登记进派发器 (DistrictSystem.register 调用, 须排在 WebUiServerSubsystem 之后)。 */
    public static void registerAll() {
        ACTIONS.forEach(WebUiServerDispatcher::register);
    }

    public static int actionCount() {
        return ACTIONS.size();
    }

    /** 全部动作名 (登记顺序)。 */
    public static List<String> actionNames() {
        return List.copyOf(ACTIONS.keySet());
    }

    /**
     * 其中的写动作 (除 district.state / district.detail / district.permissions / district.plots / plot.detail /
     * admin.district.archive 六条读动作之外的 20 条): 一律不许进批量。
     */
    public static List<String> writeActionNames() {
        List<String> reads = List.of(DistrictActionNames.STATE, DistrictActionNames.DETAIL,
                DistrictActionNames.PERMISSIONS, DistrictActionNames.PLOTS, DistrictActionNames.PLOT_DETAIL,
                DistrictActionNames.ARCHIVE);
        return ACTIONS.keySet().stream().filter(name -> !reads.contains(name)).toList();
    }

    // ================================================================
    // 公共
    // ================================================================

    /**
     * 当前绑定的服务。功能没有开启 (OFF, 服务不绑定) 时抛 DISTRICT_DISABLED (20.9), 照 QUEST_DISABLED 的先例: 不可同 id
     * 重试、无 params, 不再落进派发器的通用兜底。
     */
    private static DistrictContext ctx() {
        if (!DistrictServices.isRegistered()) {
            throw new WebUiBusinessException(WebUiErrorCodes.DISTRICT_DISABLED, DISABLED_MESSAGE, false);
        }
        return DistrictServices.context();
    }

    /**
     * 写动作取服务 (20 条, 见 {@link #writeActionNames()}): 除了 {@link #ctx()} 的门, 领地对接降级 (DEGRADED: 开着但
     * Flan 用不了, 或运行中熔断) 时同样回 DISTRICT_DISABLED, 文案带上降级原因 —— 这时库照写、Flan 却不跟着改: 买地扣了
     * 钱却没有保护, 移出住户、删地块、冻结之后旧的领地权限还留着 (P21, 20.9)。六条读动作与 OP 的 /district 命令照常,
     * 供排障。导航入口在 DEGRADED 时本来就隐藏, 这道门挡的是还开着的平板与直接输入的网址。
     */
    private static DistrictContext ctxForWrite() {
        DistrictContext ctx = ctx();
        DistrictFeature.Status status = DistrictFeature.status();
        if (status.state() == DistrictFeature.State.DEGRADED) {
            throw new WebUiBusinessException(WebUiErrorCodes.DISTRICT_DISABLED,
                    DistrictTexts.districtReadOnly(status.reason()), false);
        }
        return ctx;
    }

    /** 平板请求的操作人: 身份真实性由派发器入口的登录门保证, 本模块不重复做。 */
    static Actor actorOf(ServerPlayer sender) {
        return Actor.player(sender.getUUID(), sender.getGameProfile().getName(), WebUiPermissions.isOp(sender));
    }

    /**
     * 执行一条动作并写出回执: 业务拒绝一对一转成 {@link WebUiBusinessException}; 数据库失败 (事务已回滚) 记一条 ERROR
     * (带 action 名与发送者) 后转成 STORE_FAILED。其余异常照常冒到派发器的通用兜底。
     */
    static String guard(String action, ServerPlayer sender, Supplier<JsonObject> body) {
        try {
            return DistrictJson.write(body.get());
        } catch (DistrictRuleException rejection) {
            throw new WebUiBusinessException(rejection.code().wire(), rejection.getMessage(), false,
                    rejection.params());
        } catch (DistrictStoreException | MiningStoreException failure) {
            LOGGER.error("[miningdim] district action {} failed on the database for {} ({}); the transaction was "
                    + "rolled back", action, sender.getGameProfile().getName(), sender.getUUID(), failure);
            throw new WebUiBusinessException(WebUiErrorCodes.STORE_FAILED, STORE_FAILED_MESSAGE, false);
        }
    }

    private static JsonObject residentJson(DistrictContext ctx, String districtId, MemberRecord member,
                                           boolean admin) {
        DistrictRecord district = ctx.repo().liveDistrict(districtId).orElseThrow(() -> new IllegalStateException(
                "district " + districtId + " vanished right after a successful write"));
        return DistrictJson.resident(ctx.queries().residentView(district, member), admin);
    }

    private static JsonObject friendResult(PlotOwnerService.FriendResult result) {
        JsonObject json = new JsonObject();
        json.add("friend", DistrictJson.friend(result.friend(), result.isResident()));
        json.add("logEntry", DistrictJson.plotLog(result.logEntry()));
        return json;
    }

    /** plot.create / plot.resize 的回执: 写入后的地块按调用者视角 (管理员或本区区务长) 裁剪。 */
    private static JsonObject layoutResult(DistrictContext ctx, Actor actor, String districtId,
                                           PlotLayoutService.LayoutResult result, boolean withOwnerNotified) {
        DistrictRecord district = ctx.repo().liveDistrict(districtId).orElseThrow(() -> new IllegalStateException(
                "district " + districtId + " vanished right after a successful write"));
        DistrictAccess access = ctx.access().access(actor, district);
        JsonObject json = new JsonObject();
        json.add("plot", DistrictJson.plotSummary(district, ctx.queries().plotSummary(district, result.plot()),
                access));
        json.add("logEntry", DistrictJson.districtLog(result.logEntry()));
        if (withOwnerNotified) {
            json.addProperty("ownerNotified", result.ownerNotified());
        }
        return json;
    }
}
