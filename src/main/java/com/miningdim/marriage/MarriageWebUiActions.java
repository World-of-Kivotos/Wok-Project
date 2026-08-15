package com.miningdim.marriage;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.miningdim.config.MiningServerConfig;
import com.miningdim.economy.EconomyServices;
import com.miningdim.economy.IEconomyService;
import com.miningdim.webui.server.WebUiBusinessException;
import com.miningdim.webui.server.WebUiErrorCodes;
import com.miningdim.webui.server.WebUiItemJson;
import com.miningdim.webui.server.WebUiPayloads;
import com.miningdim.webui.server.WebUiServerDispatcher;
import com.miningdim.webui.server.WebUiServerDispatcher.WebUiAction;
import net.minecraft.core.NonNullList;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * 婚姻面板的 marriage.* WebUiAction (W6 七条: state / buyRing / propose / respond / wed / divorce / sharedInv)。
 *
 * 六条是命令层 ({@link MarriageCommands}) 的薄封装 —— 同一份 {@link MarriageEngine} / {@link MarriageDivorce} /
 * {@link MarriageProposals}, 面板与 /marriage 命令走的是同一条裁决链, 两条路径判据分叉即等于开后门。唯一新写的是
 * "谁向我求婚"的反查 ({@link MarriageProposals#proposersFor})。
 *
 * <h2>为什么本类需要注入两个实例</h2>
 * 婚约意向表与共享背包会话表都是 {@link MarriageSystem} 持有的进程级单例。面板若各自 new 一份, 结果是:
 * 命令行求的婚在面板上看不见 (两张意向表), 而离婚时 {@link MarriageBackpackSessions#forceCloseAll} 关的是
 * 一张空会话表 —— 双方开着的共享背包窗口不会被关, 那正是 spec 第四章要堵死的并发 dupe 窗口。故 registerAll
 * 收下 {@link MarriageSystem} 的两个实例, 静态绑定后供全部 handler 取用。
 *
 * <h2>失败态一律回机器码, 不回中文</h2>
 * 典礼六态 ({@link MarriageEngine.Reason} 除 OK 外) 与离婚三态 ({@link MarriageDivorce.Result} 除 OK 外) 是
 * <b>正常业务结果</b>而非异常, 故走 success=true 的回执体 {@code {ok:false, outcomeCode, messageKey, messageArgs}},
 * 不占 {@link WebUiErrorCodes} 的命名空间 (那张表只收真正的调用失败)。messageKey 是 /marriage 命令对同一结果所用的
 * lang 键原文 —— 面板与聊天栏因此不可能出现两套口径; 中文由客户端 I18n 解 (专用服务端不加载 lang)。
 *
 * <h2>时间一律发 tick</h2>
 * 婚龄 / 再婚冷却 / 典礼时刻全部是 overworld {@code getGameTime()} 轴上的服务器运行 tick, 与
 * {@link MarriageTuning} 的口径同源。服务端手里没有可信墙钟, 转成 epoch 再让页面拿 Date.now() 去减, 既吃时钟偏移
 * 又在 TPS 掉帧时失真 (同 job.miner.* 的既有纪律)。
 */
public final class MarriageWebUiActions {

    /**
     * 本类专用 Gson: 必须 serializeNulls。
     *
     * "未婚/配偶离线/没有 outgoing 意向"这些真值在回执里就是 null, 默认 Gson 会把整个键丢掉, 前端拿到 undefined
     * 与"服务端漏发了这个字段"无法区分。变体字段 (displayName / customModelData / nameParts) 仍是条件追加的
     * 可选键 —— 从不写入的键不会因 serializeNulls 凭空出现。
     */
    private static final Gson GSON = new GsonBuilder().serializeNulls().create();

    /**
     * marriage.state 单次回执最多带回的 incoming 求婚条数。
     *
     * 上限不是为了省字节, 是因为 {@link MarriageProposals} 允许全服每个在线玩家各向同一人求婚一次: 大服里这条
     * 列表能长到几百条, 撞上 {@link WebUiServerDispatcher} 的 32767 字符收口后整条 state 会被替换成
     * RESPONSE_TOO_LARGE —— 玩家看到的是"婚姻面板打不开", 与病因隔了十万八千里。截断后由 incomingProposalTotal
     * 与 incomingProposalsTruncated 如实告知被截了多少。
     */
    private static final int MAX_INCOMING_PROPOSALS = 32;

    /** 婚约意向表 (由 {@link MarriageSystem} 注入; 与 /marriage 命令共用同一张表)。 */
    private static volatile MarriageProposals proposals;

    /** 共享背包会话表 (由 {@link MarriageSystem} 注入; 离婚强制关窗必须作用于真正开着的那些窗口)。 */
    private static volatile MarriageBackpackSessions sessions;

    private MarriageWebUiActions() {
    }

    /**
     * 把七条 marriage.* action 注册进派发器 (由 {@link MarriageSystem#register} 调用一次)。
     *
     * @param proposalTable 婚姻子系统的婚约意向表单例
     * @param sessionTable  婚姻子系统的共享背包会话表单例
     */
    public static void registerAll(MarriageProposals proposalTable, MarriageBackpackSessions sessionTable) {
        // 先绑实例再登记 action: 反过来会留出一个"已可被调用但依赖还是 null"的窗口。
        proposals = Objects.requireNonNull(proposalTable, "proposalTable");
        sessions = Objects.requireNonNull(sessionTable, "sessionTable");
        WebUiServerDispatcher.register("marriage.state", STATE);
        WebUiServerDispatcher.register("marriage.buyRing", BUY_RING);
        WebUiServerDispatcher.register("marriage.propose", PROPOSE);
        WebUiServerDispatcher.register("marriage.respond", RESPOND);
        WebUiServerDispatcher.register("marriage.wed", WED);
        WebUiServerDispatcher.register("marriage.divorce", DIVORCE);
        WebUiServerDispatcher.register("marriage.sharedInv", SHARED_INV);
    }

    /**
     * 已绑定的婚约意向表。未绑定即装配缺陷 (有人绕过 registerAll 直接调 handler), 自然抛暴露, 不静默 new 一张
     * 空表 —— 那会让命令行求的婚在面板上凭空消失, 症状离病因极远。
     */
    static MarriageProposals proposals() {
        MarriageProposals bound = proposals;
        if (bound == null) {
            throw new IllegalStateException("marriage.* actions used before MarriageWebUiActions.registerAll");
        }
        return bound;
    }

    /** 已绑定的共享背包会话表 (未绑定同 {@link #proposals()} 自然抛)。 */
    static MarriageBackpackSessions sessions() {
        MarriageBackpackSessions bound = sessions;
        if (bound == null) {
            throw new IllegalStateException("marriage.* actions used before MarriageWebUiActions.registerAll");
        }
        return bound;
    }

    // ============================================================
    // marriage.state: {} -> 婚姻面板一屏所需的全部只读态
    // ============================================================

    /**
     * 婚姻只读态聚合。
     *
     * 关系态取 {@link MarriageRegistry#forPlayer} 而不是 capability 的 marriageId 指针: Registry 是权威, 指针只是
     * 玩家侧缓存 (IMiningPlayerData 类注释), 且 forPlayer 自带陈旧索引自愈。
     *
     * 共享背包等级/格数按 {@link MarriageTuning} 现算, 与 {@link MarriageBackpackMenu.Provider} 逐字同源 ——
     * 面板显示的格数与真正开出来的格数不允许是两个数。{@link MarriageState#sharedInvLevel()} 那个持久字段全库
     * 无写入方, 不作展示依据。
     */
    static final WebUiAction STATE = (sender, payload) -> {
        MinecraftServer server = sender.getServer();
        ServerLevel overworld = server.overworld();
        long now = overworld.getGameTime();
        UUID me = sender.getUUID();

        MarriageState state = MarriageRegistry.get(overworld).forPlayer(me);
        MarriageHistory history = MarriageHistory.get(overworld);
        long remarryCooldownTicks = history.remarryCooldownRemaining(me, now);

        JsonObject result = new JsonObject();
        result.addProperty("nowTick", now);
        result.addProperty("status", statusOf(state, acceptedPartners(me), remarryCooldownTicks));
        result.addProperty("divorceCount", history.divorceCount(me));
        result.addProperty("remarryCooldownTicks", remarryCooldownTicks);

        if (state == null) {
            result.add("marriageId", JsonNull.INSTANCE);
            result.add("spouseUuid", JsonNull.INSTANCE);
            result.add("spouseName", JsonNull.INSTANCE);
            result.addProperty("spouseOnline", false);
            result.add("weddedAtTick", JsonNull.INSTANCE);
            result.addProperty("marriedDays", 0L);
            // 未婚没有共享背包: 0 是真值 (没有容器), 不是"取不到数据"的占位。
            result.addProperty("sharedInvLevel", 0);
            result.addProperty("sharedInvSlots", 0);
        } else {
            UUID spouseId = state.spouseOf(me);
            ServerPlayer spouse = server.getPlayerList().getPlayer(spouseId);
            int level = MarriageTuning.backpackLevel(state.marriedSinceTick(), now);
            result.addProperty("marriageId", state.marriageId());
            result.addProperty("spouseUuid", spouseId.toString());
            addNameOrNull(result, "spouseName", spouse);
            result.addProperty("spouseOnline", spouse != null);
            result.addProperty("weddedAtTick", state.marriedSinceTick());
            result.addProperty("marriedDays", MarriageTuning.marriedDays(state.marriedSinceTick(), now));
            result.addProperty("sharedInvLevel", level);
            result.addProperty("sharedInvSlots", visibleSlots(level));
        }

        result.addProperty("engagementRingOwned", engagementRingOwned(sender));
        result.addProperty("ringPriceCredit", (long) MiningServerConfig.MARRIAGE_ENGAGEMENT_COST.get());
        result.addProperty("weddingCostCredit", (long) MiningServerConfig.MARRIAGE_WEDDING_COST.get());
        result.addProperty("divorceCostCredit", (long) MiningServerConfig.MARRIAGE_DIVORCE_COST.get());

        result.add("milestones", milestonesJson(state, history));

        List<UUID> incoming = proposals().proposersFor(me);
        JsonArray incomingJson = new JsonArray();
        for (UUID proposer : incoming) {
            if (incomingJson.size() >= MAX_INCOMING_PROPOSALS) {
                break;
            }
            incomingJson.add(incomingProposalJson(server, proposer, me));
        }
        result.add("incomingProposals", incomingJson);
        result.addProperty("incomingProposalTotal", incoming.size());
        result.addProperty("incomingProposalsTruncated", incoming.size() > incomingJson.size());

        UUID outgoingTarget = proposals().targetOf(me);
        if (outgoingTarget == null) {
            result.add("outgoingProposal", JsonNull.INSTANCE);
        } else {
            result.add("outgoingProposal", outgoingProposalJson(server, me, outgoingTarget));
        }
        return GSON.toJson(result);
    };

    // ============================================================
    // marriage.buyRing: {} -> {costCredit, wallet, engagementRingOwned}
    // ============================================================

    /**
     * 买一枚订婚戒指 (花钱操作; 复用 {@link MarriageEngine#buyEngagementRing})。
     *
     * 扣款与发货的顺序由引擎定死: 先 tryCharge 再造戒指, 扣不动就一分不扣也不发 (事务安全); 扣成功后戒指若因
     * 背包满塞不进, 引擎把它掉在脚下而不是吞掉 —— 玩家已经付过钱了。故本回执的 engagementRingOwned 是<b>扫背包
     * 得到的真值</b>而非恒 true: 背包满的那一次它会是 false, 而戒指就在脚边, 前端据此提示"检查脚下"。
     *
     * 经济未注册先于扣款判掉: 引擎在这种情况下抛 IllegalStateException, 那会走 Gateway 的无 errorCode 通用兜底,
     * 玩家只能拿到一句裸文本。ECONOMY_OFFLINE 是既有的、语义完全对得上的业务码。
     */
    static final WebUiAction BUY_RING = (sender, payload) -> {
        if (!EconomyServices.isRegistered()) {
            throw new WebUiBusinessException(WebUiErrorCodes.ECONOMY_OFFLINE,
                    "经济子系统未注册, 本次不扣费也不发戒指", false);
        }
        long cost = MiningServerConfig.MARRIAGE_ENGAGEMENT_COST.get();
        IEconomyService economy = EconomyServices.economyService();
        MarriageEngine engine = new MarriageEngine(sender.getServer().overworld());
        if (!engine.buyEngagementRing(sender, cost)) {
            throw new WebUiBusinessException(WebUiErrorCodes.INSUFFICIENT_FUNDS,
                    "信用点不足, 订婚戒指需要 " + cost, false,
                    Map.of("cost", Long.toString(cost),
                            "currency", "CREDIT",
                            "balance", Long.toString(economy.creditBalance(sender))));
        }

        JsonObject result = new JsonObject();
        result.addProperty("costCredit", cost);
        result.add("wallet", walletJson(economy, sender));
        result.addProperty("engagementRingOwned", engagementRingOwned(sender));
        return GSON.toJson(result);
    };

    // ============================================================
    // marriage.propose: {targetName} -> {proposalId, proposerUuid, targetUuid, targetName, accepted}
    // ============================================================

    /**
     * 向某在线玩家表达订婚意向 (登记 proposer -> target 的单向意向, 覆盖自己旧的那一条)。
     *
     * 与 /marriage propose 逐条对齐: 同样只认在线玩家、同样不校验"我已婚" (重婚在典礼那一步由
     * {@link MarriageEngine.Reason#ALREADY_MARRIED} 拦), 并且同样给对方发一条聊天提示 —— 少了这条提示, 从面板发出
     * 的求婚对方在打开面板之前完全无感 (求婚实时推送是清单 E4 的另一个缺口)。
     */
    static final WebUiAction PROPOSE = (sender, payload) -> {
        String targetName = WebUiPayloads.requiredString(payload, "targetName");
        ServerPlayer target = requireOnlinePlayer(sender, "targetName", targetName);
        if (sender.getUUID().equals(target.getUUID())) {
            throw WebUiPayloads.illegalValue("targetName", targetName, "不能向自己求婚");
        }

        proposals().propose(sender.getUUID(), target.getUUID());
        target.sendSystemMessage(Component.translatable("message.miningdim.marriage.propose.received",
                sender.getGameProfile().getName()));

        JsonObject result = new JsonObject();
        result.addProperty("proposalId", sender.getUUID().toString());
        result.addProperty("proposerUuid", sender.getUUID().toString());
        result.addProperty("targetUuid", target.getUUID().toString());
        result.addProperty("targetName", target.getGameProfile().getName());
        result.addProperty("accepted", false);
        return GSON.toJson(result);
    };

    // ============================================================
    // marriage.respond: {proposalId, accept} -> {proposalId, proposerUuid, proposerName, accepted, status, spouseName}
    // ============================================================

    /**
     * 应答一条收到的求婚 (accept=true 接受, false 拒绝并清掉该意向)。
     *
     * proposalId 就是求婚方的 UUID 字符串: 一名玩家同一时刻只持一条 outgoing 意向 ({@link MarriageProposals} 的
     * 语义), 求婚方 UUID 因此已经是这条意向的完整主键, 另发一个自增 id 只会多一张需要与意向表同步失效的表。
     *
     * 应答前必须校验"这条意向确实指向本人": {@link MarriageProposals#clear} 只按 proposer 删除、不看目标, 少了这道
     * 校验, 任何人都能凭一个 UUID 把别人的婚约拒掉。
     */
    static final WebUiAction RESPOND = (sender, payload) -> {
        String proposalId = WebUiPayloads.requiredString(payload, "proposalId");
        boolean accept = WebUiPayloads.requiredBoolean(payload, "accept");
        UUID proposer = parseProposalId(proposalId);
        UUID me = sender.getUUID();
        MarriageProposals table = proposals();
        if (!me.equals(table.targetOf(proposer))) {
            throw WebUiPayloads.illegalValue("proposalId", proposalId, "没有这样一条待你应答的求婚");
        }

        MinecraftServer server = sender.getServer();
        ServerPlayer proposerPlayer = server.getPlayerList().getPlayer(proposer);
        if (accept) {
            // targetOf 刚校验过指向本人, 且两者读的是同一张正向表, 故这次 accept 必定命中同一条意向。
            table.accept(proposer, me);
            if (proposerPlayer != null) {
                proposerPlayer.sendSystemMessage(Component.translatable(
                        "message.miningdim.marriage.accept.notify", sender.getGameProfile().getName()));
            }
        } else {
            table.clear(proposer);
        }

        ServerLevel overworld = server.overworld();
        long now = overworld.getGameTime();
        MarriageState state = MarriageRegistry.get(overworld).forPlayer(me);
        long remarryCooldownTicks = MarriageHistory.get(overworld).remarryCooldownRemaining(me, now);

        JsonObject result = new JsonObject();
        result.addProperty("proposalId", proposalId);
        result.addProperty("proposerUuid", proposer.toString());
        addNameOrNull(result, "proposerName", proposerPlayer);
        result.addProperty("accepted", accept);
        // 接受求婚不等于已婚: 典礼 (marriage.wed) 才建立关系, 故这里的 status 通常是 engaged。
        result.addProperty("status", statusOf(state, acceptedPartners(me), remarryCooldownTicks));
        if (state == null) {
            result.add("spouseName", JsonNull.INSTANCE);
        } else {
            addNameOrNull(result, "spouseName", server.getPlayerList().getPlayer(state.spouseOf(me)));
        }
        return GSON.toJson(result);
    };

    // ============================================================
    // marriage.wed: {partnerName?} -> {ok, outcomeCode, messageKey, messageArgs, partnerUuid, partnerName, marriageId, weddedAtTick}
    // ============================================================

    /**
     * 办典礼 (双方各付一半 weddingCost, 事务性; 结果码见类注释)。
     *
     * partnerName 可省: 命令行必须指名伴侣, 而面板上"跟谁办"通常是唯一确定的 —— 已被接受的婚约只有一份时直接用它。
     * 但确实可能有多份 (多人向我求婚且我都点了接受), 此时不许替玩家猜: 回 INVALID_REQUEST 并在 params.field 指出
     * 缺的是 partnerName, 前端据此把面板从"办典礼"切成"选一位"。
     *
     * 引擎的六种失败原因原样回 outcomeCode; 另有两种在引擎之前就短路的结果码, 它们不属于
     * {@link MarriageEngine.Reason}: NO_ACCEPTED_PROPOSAL (没有已接受的婚约) 与 PARTNER_OFFLINE (唯一那位不在线,
     * 而典礼要求双方在场 —— 引擎收的是两个 ServerPlayer)。
     */
    static final WebUiAction WED = (sender, payload) -> {
        MinecraftServer server = sender.getServer();
        ServerLevel overworld = server.overworld();
        UUID me = sender.getUUID();
        MarriageProposals table = proposals();

        ServerPlayer partner;
        JsonElement rawPartnerName = payload.get("partnerName");
        if (rawPartnerName != null && !rawPartnerName.isJsonNull()) {
            String partnerName = WebUiPayloads.requiredString(payload, "partnerName");
            partner = requireOnlinePlayer(sender, "partnerName", partnerName);
            if (me.equals(partner.getUUID())) {
                throw WebUiPayloads.illegalValue("partnerName", partnerName, "不能和自己办典礼");
            }
        } else {
            Set<UUID> candidates = acceptedPartners(me);
            if (candidates.isEmpty()) {
                // 连伴侣是谁都还不知道, 而 no_accepted_proposal 那条 lang 键要一个玩家名占位符 —— 填不出实参就
                // 不发 key (让前端用自己那句"你还没有已接受的婚约"), 发一条渲染出来缺半截的文案比不发更糟。
                return wedResponse(false, "NO_ACCEPTED_PROPOSAL", null, messageArgs(),
                        null, null, null, null);
            }
            if (candidates.size() > 1) {
                throw new WebUiBusinessException(WebUiErrorCodes.INVALID_REQUEST,
                        "有多份已接受的婚约, 必须指名 partnerName", false,
                        Map.of("field", "partnerName",
                                "candidateCount", Integer.toString(candidates.size())));
            }
            UUID only = candidates.iterator().next();
            ServerPlayer resolved = server.getPlayerList().getPlayer(only);
            if (resolved == null) {
                return wedResponse(false, "PARTNER_OFFLINE", null, messageArgs(),
                        only, null, null, null);
            }
            partner = resolved;
        }

        // 婚约须已被接受 (任一方向), 判据与 /marriage wed 逐字一致。
        UUID partnerId = partner.getUUID();
        String partnerName = partner.getGameProfile().getName();
        if (!table.isAccepted(me, partnerId) && !table.isAccepted(partnerId, me)) {
            return wedResponse(false, "NO_ACCEPTED_PROPOSAL",
                    "message.miningdim.marriage.wed.no_accepted_proposal", messageArgs(partnerName),
                    partnerId, partnerName, null, null);
        }

        long totalCost = MiningServerConfig.MARRIAGE_WEDDING_COST.get();
        // officiant 传 null: 证婚人机制是 spec 第十二章 PENDING, 命令层同样传 null (不在面板侧先编一个出来)。
        MarriageEngine.WeddingResult outcome = new MarriageEngine(overworld).wed(sender, partner, totalCost, null);
        if (!outcome.success()) {
            return wedResponse(false, outcome.reason().name(), wedMessageKey(outcome.reason()),
                    messageArgs(), partnerId, partnerName, null, null);
        }

        // 典礼成功的三个收尾与命令层一致: 清双方残留意向 + 全服广播 (少了清意向, 面板上那条婚约会一直挂着)。
        table.clear(me);
        table.clear(partnerId);
        server.getPlayerList().broadcastSystemMessage(Component.translatable(
                "message.miningdim.marriage.wed.broadcast", sender.getGameProfile().getName(), partnerName), false);

        MarriageState state = MarriageRegistry.get(overworld).byId(outcome.marriageId());
        // 典礼时刻回读关系本身的 marriedSinceTick, 不在此重取一次 getGameTime —— 那是另一次采样, 会与婚龄基准差一拍。
        return wedResponse(true, MarriageEngine.Reason.OK.name(),
                wedMessageKey(MarriageEngine.Reason.OK),
                messageArgs(sender.getGameProfile().getName(), partnerName),
                partnerId, partnerName, outcome.marriageId(), state.marriedSinceTick());
    };

    // ============================================================
    // marriage.divorce: {} -> {ok, outcomeCode, messageKey, messageArgs, costCredit, divorceCount, remarryCooldownTicks, formerSpouseUuid}
    // ============================================================

    /**
     * 离婚 (发起方付成本 + 共享背包清算退回发起方 + 强制关双方窗口 + 再婚冷却递增)。
     *
     * costCredit 回的是本次离婚的<b>定价</b>而非已扣额: ok=false 的三种结果一分未扣 (NOT_MARRIED / NO_ECONOMY 在
     * 扣费前短路, INSUFFICIENT_FUNDS 是扣不动), 前端据 ok 决定说"已扣"还是"需要"。
     *
     * divorceCount 与 remarryCooldownTicks 是结算<b>之后</b>重读的值 —— 冷却随离婚次数递增, 玩家在点下按钮的那一刻
     * 最需要知道的就是"这次要等多久"。
     */
    static final WebUiAction DIVORCE = (sender, payload) -> {
        ServerLevel overworld = sender.getServer().overworld();
        UUID me = sender.getUUID();
        MarriageState before = MarriageRegistry.get(overworld).forPlayer(me);
        UUID formerSpouse = before == null ? null : before.spouseOf(me);

        long cost = MiningServerConfig.MARRIAGE_DIVORCE_COST.get();
        MarriageDivorce.Result outcome = new MarriageDivorce(overworld, sessions()).divorce(sender, cost);

        MarriageHistory history = MarriageHistory.get(overworld);
        long now = overworld.getGameTime();
        boolean ok = outcome == MarriageDivorce.Result.OK;

        JsonObject result = new JsonObject();
        result.addProperty("ok", ok);
        result.addProperty("outcomeCode", outcome.name());
        result.addProperty("messageKey", divorceMessageKey(outcome));
        result.add("messageArgs", outcome == MarriageDivorce.Result.INSUFFICIENT_FUNDS
                ? messageArgs(Long.toString(cost))
                : messageArgs());
        result.addProperty("costCredit", cost);
        result.addProperty("divorceCount", history.divorceCount(me));
        result.addProperty("remarryCooldownTicks", history.remarryCooldownRemaining(me, now));
        if (ok && formerSpouse != null) {
            result.addProperty("formerSpouseUuid", formerSpouse.toString());
        } else {
            result.add("formerSpouseUuid", JsonNull.INSTANCE);
        }
        return GSON.toJson(result);
    };

    // ============================================================
    // marriage.sharedInv: {} -> {married, marriageId, level, slots, capacity, items[]}
    // ============================================================

    /**
     * 共享背包内容的只读快照 (取放仍走原版 menu; 本 action 一个字节都不写容器)。
     *
     * 槽位 JSON 与 {@code player.inventory} 逐字同形 (含 {@link WebUiItemJson#appendVariant} 的变体两字段) ——
     * 另发明一种槽位结构, 结果就是同一份渲染组件要分叉成两套, 而 195 种枪匠零件会在其中一套里退回同名同图标。
     *
     * 只回当前等级暴露的前 N 格: 容器恒 54 格, 等级只控暴露子集 (spec 第四章), 把没解锁的格子也发出去等于让面板
     * 显示玩家还打不开的东西。白名单已在容器层强制 ({@link SharedBackpackWhitelist}), 只读展示不重复校验。
     *
     * 未婚不是错误而是正常答案 (同 player.isOp 的纪律): 回 {@code married=false} 的完整空回执, 不抛业务码。
     */
    static final WebUiAction SHARED_INV = (sender, payload) -> {
        ServerLevel overworld = sender.getServer().overworld();
        long now = overworld.getGameTime();
        MarriageState state = MarriageRegistry.get(overworld).forPlayer(sender.getUUID());

        JsonObject result = new JsonObject();
        result.addProperty("capacity", MarriageState.SHARED_INV_SIZE);
        JsonArray items = new JsonArray();
        if (state == null) {
            result.addProperty("married", false);
            result.add("marriageId", JsonNull.INSTANCE);
            result.addProperty("level", 0);
            result.addProperty("slots", 0);
            result.add("items", items);
            return GSON.toJson(result);
        }

        int level = MarriageTuning.backpackLevel(state.marriedSinceTick(), now);
        int slots = visibleSlots(level);
        NonNullList<ItemStack> inv = state.sharedInv();
        for (int slot = 0; slot < slots; slot++) {
            ItemStack stack = inv.get(slot);
            if (stack.isEmpty()) {
                continue;
            }
            JsonObject item = new JsonObject();
            item.addProperty("slot", slot);
            item.addProperty("itemId", ForgeRegistries.ITEMS.getKey(stack.getItem()).toString());
            // 发翻译键而不是中文名: 专用服务端不加载 lang, 且 itemId 推不出翻译键 (物品是 item.*, 方块是 block.*)。
            item.addProperty("descriptionId", stack.getDescriptionId());
            item.addProperty("count", stack.getCount());
            if (stack.hasCustomHoverName()) {
                item.addProperty("displayName", stack.getHoverName().getString());
            }
            WebUiItemJson.appendVariant(item, stack);
            items.add(item);
        }

        result.addProperty("married", true);
        result.addProperty("marriageId", state.marriageId());
        result.addProperty("level", level);
        result.addProperty("slots", slots);
        result.add("items", items);
        return GSON.toJson(result);
    };

    // ============================================================
    // 取数 / JSON helper
    // ============================================================

    /**
     * 该玩家当前所有"已被接受"的婚约对手 (两个方向合并, 同一人只算一次)。
     *
     * 双向合并的理由: A 向 B 求婚 B 接受, 与 B 向 A 求婚 A 接受, 是同一桩婚事的两种走法; /marriage wed 的判据也是
     * 两个方向任一成立。用 LinkedHashSet 去重并保留稳定顺序 —— 顺序决定"唯一候选"的判定与面板列表的稳定性。
     */
    private static Set<UUID> acceptedPartners(UUID me) {
        MarriageProposals table = proposals();
        Set<UUID> partners = new LinkedHashSet<>();
        UUID outgoing = table.targetOf(me);
        if (outgoing != null && table.isAccepted(me, outgoing)) {
            partners.add(outgoing);
        }
        for (UUID proposer : table.proposersFor(me)) {
            if (table.isAccepted(proposer, me)) {
                partners.add(proposer);
            }
        }
        return partners;
    }

    /**
     * 面板顶部那一个状态词。
     *
     * 优先级 married &gt; engaged &gt; cooldown &gt; single: 四者并非互斥 (再婚冷却中照样可以有已接受的婚约), 而
     * 玩家最需要先看到的是"我现在处在哪一步"。冷却这件事不会因此丢失 —— remarryCooldownTicks 恒发, 它才是典礼
     * 会不会被 REMARRY_COOLDOWN 拦下的真实判据。
     */
    private static String statusOf(MarriageState state, Set<UUID> acceptedPartners, long remarryCooldownTicks) {
        if (state != null) {
            return "married";
        }
        if (!acceptedPartners.isEmpty()) {
            return "engaged";
        }
        if (remarryCooldownTicks > 0L) {
            return "cooldown";
        }
        return "single";
    }

    /** 该玩家主背包里是否有一枚订婚戒指 (典礼前置; 与 {@code MarriageEngine} 找戒指的判据同一条)。 */
    private static boolean engagementRingOwned(ServerPlayer player) {
        for (ItemStack stack : player.getInventory().items) {
            if (stack.getItem() instanceof RingItem ring && ring.isEngagement()) {
                return true;
            }
        }
        return false;
    }

    /**
     * 某共享背包等级实际暴露的格数, 钳到容器真实大小。
     *
     * config 的 backpackSlots 校验上限就是 54, 钳一道是防"配置被手改超界"时面板画出打不开的幽灵格子 ——
     * 容器只有 {@link MarriageState#SHARED_INV_SIZE} 格, 多出来的格子背后什么都没有。
     */
    private static int visibleSlots(int level) {
        return Math.min(MarriageTuning.backpackVisibleSlots(level), MarriageState.SHARED_INV_SIZE);
    }

    /** 双货币余额 (形状与 player.wallet 一致, 前端复用同一个钱包组件)。 */
    private static JsonObject walletJson(IEconomyService economy, ServerPlayer player) {
        JsonObject wallet = new JsonObject();
        wallet.addProperty("credit", economy.creditBalance(player));
        wallet.addProperty("azure", economy.heartstoneBalance(player));
        return wallet;
    }

    /**
     * 一次性里程碑。当前全系统只定义了一个 ({@link MarriageEngine#MILESTONE_FIRST_MARRIAGE}), 仍发成数组是因为
     * 它天然是一张表; 达成时刻没有落盘 (只存了"领没领过"), 故不发 achievedAt —— 编一个时间戳出来比不发更糟。
     *
     * 两个布尔口径不同, 都要发: claimedByPair 是"这对 UUID 历史上领过" (跨结离婚去重的真源),
     * claimedInCurrentMarriage 是"本段关系内领的"。离婚再复婚时前者 true 而后者 false, 那正是不重发福利的原因。
     */
    private static JsonArray milestonesJson(MarriageState state, MarriageHistory history) {
        JsonArray milestones = new JsonArray();
        JsonObject firstMarriage = new JsonObject();
        firstMarriage.addProperty("milestoneId", MarriageEngine.MILESTONE_FIRST_MARRIAGE);
        firstMarriage.addProperty("claimedInCurrentMarriage",
                state != null && state.hasClaimedMilestone(MarriageEngine.MILESTONE_FIRST_MARRIAGE));
        firstMarriage.addProperty("claimedByPair", state != null && history.hasPairClaimed(
                state.partnerA(), state.partnerB(), MarriageEngine.MILESTONE_FIRST_MARRIAGE));
        milestones.add(firstMarriage);
        return milestones;
    }

    /** 一条"别人向我发出的"求婚。 */
    private static JsonObject incomingProposalJson(MinecraftServer server, UUID proposer, UUID target) {
        ServerPlayer proposerPlayer = server.getPlayerList().getPlayer(proposer);
        JsonObject json = new JsonObject();
        json.addProperty("proposalId", proposer.toString());
        json.addProperty("proposerUuid", proposer.toString());
        addNameOrNull(json, "proposerName", proposerPlayer);
        json.addProperty("proposerOnline", proposerPlayer != null);
        json.addProperty("accepted", proposals().isAccepted(proposer, target));
        return json;
    }

    /** 我发出的那一条求婚 (一人至多一条)。 */
    private static JsonObject outgoingProposalJson(MinecraftServer server, UUID me, UUID target) {
        ServerPlayer targetPlayer = server.getPlayerList().getPlayer(target);
        JsonObject json = new JsonObject();
        json.addProperty("proposalId", me.toString());
        json.addProperty("targetUuid", target.toString());
        addNameOrNull(json, "targetName", targetPlayer);
        json.addProperty("targetOnline", targetPlayer != null);
        json.addProperty("accepted", proposals().isAccepted(me, target));
        return json;
    }

    /**
     * 写一个玩家名字段; 玩家不在线写 null。
     *
     * 离线玩家的名字本 mod 现在拿不到 (全库零 GameProfileCache 用法, 清单 A16 的后端缺口), 与其回一个空串让面板
     * 渲染出一行没有名字的玩家, 不如如实回 null 让前端显示占位。
     */
    private static void addNameOrNull(JsonObject json, String key, ServerPlayer player) {
        if (player == null) {
            json.add(key, JsonNull.INSTANCE);
        } else {
            json.addProperty(key, player.getGameProfile().getName());
        }
    }

    /**
     * 按名字找在线玩家; 找不到即以 INVALID_REQUEST 拒绝并在 params 指名是哪个字段的哪个值。
     *
     * 只认在线玩家 (与 /marriage 的 EntityArgument.player 同口径): 求婚与典礼都要求对方此刻在场, 而离线名解析
     * 本 mod 尚无能力 (清单 A16)。大小写不敏感, 与原版按名找人的口径一致。
     */
    private static ServerPlayer requireOnlinePlayer(ServerPlayer sender, String field, String name) {
        for (ServerPlayer candidate : sender.getServer().getPlayerList().getPlayers()) {
            if (candidate.getGameProfile().getName().equalsIgnoreCase(name)) {
                return candidate;
            }
        }
        throw WebUiPayloads.illegalValue(field, name, "找不到该在线玩家 (只能对在线玩家操作)");
    }

    /** proposalId -> 求婚方 UUID; 形状不对即 INVALID_REQUEST (params 带被拒的原值)。 */
    private static UUID parseProposalId(String proposalId) {
        try {
            return UUID.fromString(proposalId);
        } catch (IllegalArgumentException malformed) {
            // 唯一允许的 catch: 把 JDK 的格式异常翻译成带 field/value 的入参拒绝, 不吞任何业务错误。
            throw WebUiPayloads.illegalValue("proposalId", proposalId, "proposalId 必须是求婚方的 UUID");
        }
    }

    /** 典礼结果码对应的 lang 键 (与 {@code MarriageCommands.weddingFailureMessage} 同一张映射, 不另起一套文案)。 */
    private static String wedMessageKey(MarriageEngine.Reason reason) {
        return switch (reason) {
            case OK -> "message.miningdim.marriage.wed.broadcast";
            case SELF_MARRIAGE -> "message.miningdim.marriage.self";
            case ALREADY_MARRIED -> "message.miningdim.marriage.wed.already_married";
            case NO_ENGAGEMENT_RING -> "message.miningdim.marriage.wed.no_ring";
            case INSUFFICIENT_FUNDS -> "message.miningdim.marriage.wed.insufficient";
            case NO_ECONOMY -> "message.miningdim.marriage.wed.no_economy";
            case REMARRY_COOLDOWN -> "message.miningdim.marriage.wed.remarry_cooldown";
        };
    }

    /** 离婚结果码对应的 lang 键 (与 {@code MarriageCommands.divorce} 的 switch 同一张映射)。 */
    private static String divorceMessageKey(MarriageDivorce.Result result) {
        return switch (result) {
            case OK -> "message.miningdim.marriage.divorce.done";
            case NOT_MARRIED -> "message.miningdim.marriage.not_married";
            case INSUFFICIENT_FUNDS -> "message.miningdim.marriage.divorce.insufficient";
            case NO_ECONOMY -> "message.miningdim.marriage.wed.no_economy";
        };
    }

    /** messageKey 的占位符实参 (与该 lang 键的 %s 一一对应; 无占位符即空数组)。 */
    private static JsonArray messageArgs(String... values) {
        JsonArray args = new JsonArray();
        for (String value : values) {
            args.add(value);
        }
        return args;
    }

    /**
     * 典礼回执的唯一构造点。八个字段无论成败都写满 (缺的写 null): 成功与失败两条路径各自拼一份形状不同的回执,
     * 前端就得为同一个 action 维护两套解析。
     */
    private static String wedResponse(boolean ok, String outcomeCode, String messageKey, JsonArray messageArgs,
                                      UUID partnerUuid, String partnerName, Long marriageId, Long weddedAtTick) {
        JsonObject json = new JsonObject();
        json.addProperty("ok", ok);
        json.addProperty("outcomeCode", outcomeCode);
        if (messageKey == null) {
            json.add("messageKey", JsonNull.INSTANCE);
        } else {
            json.addProperty("messageKey", messageKey);
        }
        json.add("messageArgs", messageArgs);
        if (partnerUuid == null) {
            json.add("partnerUuid", JsonNull.INSTANCE);
        } else {
            json.addProperty("partnerUuid", partnerUuid.toString());
        }
        if (partnerName == null) {
            json.add("partnerName", JsonNull.INSTANCE);
        } else {
            json.addProperty("partnerName", partnerName);
        }
        if (marriageId == null) {
            json.add("marriageId", JsonNull.INSTANCE);
        } else {
            json.addProperty("marriageId", marriageId);
        }
        if (weddedAtTick == null) {
            json.add("weddedAtTick", JsonNull.INSTANCE);
        } else {
            json.addProperty("weddedAtTick", weddedAtTick);
        }
        return GSON.toJson(json);
    }
}
