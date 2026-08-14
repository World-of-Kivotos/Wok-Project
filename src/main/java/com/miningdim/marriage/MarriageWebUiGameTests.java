package com.miningdim.marriage;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.miningdim.config.MiningServerConfig;
import com.miningdim.core.MiningConstants;
import com.miningdim.economy.Currency;
import com.miningdim.economy.EconomyConstants;
import com.miningdim.economy.EconomyException;
import com.miningdim.economy.EconomyServices;
import com.miningdim.economy.IEconomyService;
import com.miningdim.registry.ModItems;
import com.miningdim.testutil.MockGameTestPlayers;
import com.miningdim.webui.server.WebUiBusinessException;
import com.miningdim.webui.server.WebUiErrorCodes;
import com.miningdim.webui.server.WebUiServerDispatcher;
import com.miningdim.webui.server.WebUiServerDispatcher.WebUiAction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * W6 婚姻七条 marriage.* action 的 GameTest。
 *
 * 主线是"面板与 /marriage 命令必须是同一条裁决链"的四处副作用真验:
 *  1. 反查索引 ({@link MarriageProposals#proposersFor}) 绝不比正向表活得久 —— 改口求婚与拒绝之后, 旧目标那边
 *     必须立刻查不到 (这是本组唯一新写的逻辑, 也是最容易留下幽灵求婚的地方);
 *  2. 花钱的三条 (buyRing / wed / divorce) 逐笔对账: 扣了多少、失败时一分未扣、失败时不留半成品关系;
 *  3. 应答别人的婚约必须先校验"这条确实指向本人" —— {@link MarriageProposals#clear} 只按 proposer 删,
 *     少了这道校验就是一键拆散任意两人;
 *  4. 共享背包只读快照的格数与真菜单 ({@link MarriageBackpackMenu.Provider}) 同源, 且未解锁的格子一格不发。
 *
 * 经济一律用记账替身 {@link WalletEconomy} 经 {@link EconomyServices} 定位器 swap/restore (同 MarriageGameTests
 * 的纪律; 那份替身是私有嵌套类, 跨类复用不到, 故本类自带一份并额外实现青辉石余额 —— buyRing 回执要发双币钱包)。
 *
 * mock 玩家同名 ("test-mock-player") 是 {@link MockGameTestPlayers} 的既定形态, 故凡走"按玩家名找人"的用例
 * 都先按同一规则 (在线列表首个同名者) 解析出目标再断言, 不假设解析结果就是本用例新造的那位。
 */
@GameTestHolder(MiningConstants.MODID)
@PrefixGameTestTemplate(false)
public final class MarriageWebUiGameTests {

    private static final String EMPTY = "empty";
    private static final String BATCH = "webui_w6";

    private static final String STATE = "marriage.state";
    private static final String BUY_RING = "marriage.buyRing";
    private static final String PROPOSE = "marriage.propose";
    private static final String RESPOND = "marriage.respond";
    private static final String WED = "marriage.wed";
    private static final String DIVORCE = "marriage.divorce";
    private static final String SHARED_INV = "marriage.sharedInv";

    // ============================================================
    // 1. 反查索引: 与正向表同生共死
    // ============================================================

    /**
     * 改口求婚 / 拒绝之后, 旧目标那边必须立刻查不到 —— 这正是"另建一张 target -&gt; proposer 索引表"最容易漏掉的
     * 两处失效点。把 {@link MarriageProposals#proposersFor} 换成一张不同步的索引, 本条立刻挂。
     */
    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void proposalReverseLookupNeverOutlivesTheForwardTable(GameTestHelper helper) {
        MarriageProposals table = new MarriageProposals();
        UUID target = UUID.randomUUID();
        UUID other = UUID.randomUUID();
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();

        table.propose(first, target);
        table.propose(second, target);
        helper.assertTrue(table.proposersFor(target).equals(List.of(first, second)),
                "两条指向同一人的意向都要反查得到且按登记序, 实得 " + table.proposersFor(target));
        helper.assertTrue(table.proposersFor(other).isEmpty(),
                "没人向 other 求婚时反查必须是空表, 实得 " + table.proposersFor(other));

        // accept 只改那一条的已接受位, 不改反查成员 (两者读的是同一个意向对象)。
        table.accept(first, target);
        helper.assertTrue(table.proposersFor(target).equals(List.of(first, second)),
                "接受一条不得让另一条从反查里消失, 实得 " + table.proposersFor(target));
        helper.assertTrue(table.isAccepted(first, target) && !table.isAccepted(second, target),
                "已接受位只落在被接受的那一条上");

        // first 改向 other 求婚: 旧目标必须立刻查不到 first, 且旧的已接受位一并作废。
        table.propose(first, other);
        helper.assertTrue(table.proposersFor(target).equals(List.of(second)),
                "改口求婚后旧目标不得再看到 first (幽灵求婚), 实得 " + table.proposersFor(target));
        helper.assertTrue(table.proposersFor(other).equals(List.of(first)),
                "改口后新目标要看得到 first, 实得 " + table.proposersFor(other));
        helper.assertTrue(!table.isAccepted(first, target),
                "改口后对旧目标的已接受位必须作废 (否则可凭旧婚约直接办典礼)");

        table.clear(second);
        helper.assertTrue(table.proposersFor(target).isEmpty(),
                "clear 后目标侧不得残留, 实得 " + table.proposersFor(target));
        table.clear(first);
        helper.assertTrue(table.proposersFor(other).isEmpty(),
                "clear 后新目标侧同样不得残留, 实得 " + table.proposersFor(other));
        helper.succeed();
    }

    // ============================================================
    // 2. marriage.state 未婚基线
    // ============================================================

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void marriageStateReportsTheSingleBaselineWithExplicitNulls(GameTestHelper helper) {
        ServerPlayer player = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);
        player.getInventory().clearContent();

        JsonObject state = handle(helper, STATE, player, new JsonObject());
        helper.assertTrue("single".equals(state.get("status").getAsString()),
                "新号未婚且无婚约无冷却 -> single, 实得 " + state.get("status").getAsString());
        helper.assertTrue(state.get("marriageId").isJsonNull() && state.get("spouseUuid").isJsonNull()
                        && state.get("spouseName").isJsonNull() && state.get("weddedAtTick").isJsonNull(),
                "未婚的四个字段必须是显式 null (键不许整个消失, 否则前端分不清'未婚'与'服务端漏发')");
        helper.assertTrue(!state.get("spouseOnline").getAsBoolean(), "未婚不得报告配偶在线");
        helper.assertTrue(state.get("marriedDays").getAsLong() == 0L
                        && state.get("divorceCount").getAsInt() == 0
                        && state.get("remarryCooldownTicks").getAsLong() == 0L,
                "新号婚龄/离婚次数/再婚冷却全为 0");
        helper.assertTrue(state.get("sharedInvLevel").getAsInt() == 0 && state.get("sharedInvSlots").getAsInt() == 0,
                "未婚没有共享背包, 等级与格数都是 0");
        helper.assertTrue(!state.get("engagementRingOwned").getAsBoolean(), "空背包里没有订婚戒指");

        long ringPrice = MiningServerConfig.MARRIAGE_ENGAGEMENT_COST.get();
        long weddingCost = MiningServerConfig.MARRIAGE_WEDDING_COST.get();
        long divorceCost = MiningServerConfig.MARRIAGE_DIVORCE_COST.get();
        helper.assertTrue(state.get("ringPriceCredit").getAsLong() == ringPrice && ringPrice > 0L,
                "戒指定价取 config 实时值, 实得 " + state.get("ringPriceCredit").getAsLong());
        helper.assertTrue(state.get("weddingCostCredit").getAsLong() == weddingCost
                        && state.get("divorceCostCredit").getAsLong() == divorceCost,
                "典礼/离婚定价同样取 config, 面板据此画按钮上的价格");

        helper.assertTrue(state.get("outgoingProposal").isJsonNull(), "没求过婚 -> outgoingProposal 是 null");
        helper.assertTrue(state.getAsJsonArray("incomingProposals").isEmpty()
                        && state.get("incomingProposalTotal").getAsInt() == 0
                        && !state.get("incomingProposalsTruncated").getAsBoolean(),
                "没人求婚 -> 空列表 + 总数 0 + 未截断");

        JsonObject milestone = state.getAsJsonArray("milestones").get(0).getAsJsonObject();
        helper.assertTrue(MarriageEngine.MILESTONE_FIRST_MARRIAGE.equals(milestone.get("milestoneId").getAsString()),
                "里程碑 id 取 Java 真值 " + MarriageEngine.MILESTONE_FIRST_MARRIAGE
                        + ", 实得 " + milestone.get("milestoneId").getAsString());
        helper.assertTrue(!milestone.get("claimedByPair").getAsBoolean()
                        && !milestone.get("claimedInCurrentMarriage").getAsBoolean(),
                "未婚时两个领取位都是 false");

        // 戒指位是扫背包算出来的, 不是常量: 给一枚就该翻真。
        player.getInventory().add(RingItem.createEngagement(ModItems.ENGAGEMENT_RING.get()));
        helper.assertTrue(handle(helper, STATE, player, new JsonObject()).get("engagementRingOwned").getAsBoolean(),
                "背包里有订婚戒指后 engagementRingOwned 必须翻真");
        helper.succeed();
    }

    // ============================================================
    // 3. marriage.state 已婚: 配偶/婚龄/共享背包格数与真菜单同源
    // ============================================================

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void marriageStateReportsSpouseAndSharedInvSlotsMatchingTheRealMenu(GameTestHelper helper) {
        WalletEconomy economy = new WalletEconomy();
        IEconomyService previous = swapEconomy(economy);
        try {
            ServerLevel overworld = helper.getLevel().getServer().overworld();
            ServerPlayer a = richMock(helper, economy);
            ServerPlayer b = richMock(helper, economy);
            long marriageId = wedPair(overworld, a, b);

            MarriageRegistry registry = MarriageRegistry.get(overworld);
            MarriageState married = registry.byId(marriageId);
            JsonObject state = handle(helper, STATE, a, new JsonObject());

            helper.assertTrue("married".equals(state.get("status").getAsString()),
                    "典礼后 status=married, 实得 " + state.get("status").getAsString());
            helper.assertTrue(state.get("marriageId").getAsLong() == marriageId,
                    "回执的 marriageId 必须是新建关系的 id");
            helper.assertTrue(b.getUUID().toString().equals(state.get("spouseUuid").getAsString()),
                    "配偶 UUID 指向 B");
            helper.assertTrue(b.getGameProfile().getName().equals(state.get("spouseName").getAsString())
                            && state.get("spouseOnline").getAsBoolean(),
                    "配偶在线时要给出名字并报告在线");
            helper.assertTrue(state.get("weddedAtTick").getAsLong() == married.marriedSinceTick(),
                    "典礼时刻回读关系自身的 marriedSinceTick, 实得 " + state.get("weddedAtTick").getAsLong());
            helper.assertTrue(state.get("marriedDays").getAsLong() == 0L, "刚办完典礼婚龄是 0 天");

            // 面板格数必须等于真正开出来的窗口格数 —— 两个数分叉就是"看得见打不开"。
            int menuSlots = new MarriageBackpackMenu.Provider(
                    married, registry, new MarriageBackpackSessions(), overworld).visibleSlots();
            helper.assertTrue(state.get("sharedInvSlots").getAsInt() == menuSlots,
                    "共享背包格数要与 MarriageBackpackMenu.Provider 同源 (" + menuSlots
                            + "), 实得 " + state.get("sharedInvSlots").getAsInt());
            helper.assertTrue(state.get("sharedInvLevel").getAsInt()
                            == MarriageTuning.backpackLevel(married.marriedSinceTick(), overworld.getGameTime()),
                    "共享背包等级按婚龄现算");

            JsonObject milestone = state.getAsJsonArray("milestones").get(0).getAsJsonObject();
            helper.assertTrue(milestone.get("claimedByPair").getAsBoolean()
                            && milestone.get("claimedInCurrentMarriage").getAsBoolean(),
                    "首次结婚的一次性里程碑在典礼时被这对 UUID 领走, 两个位都该是 true");
            helper.succeed();
        } finally {
            restoreEconomy(previous);
        }
    }

    // ============================================================
    // 4. propose -> respond(accept): 双方面板都看得见同一条婚约
    // ============================================================

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void proposeThenAcceptShowsUpOnBothPanelsAsTheSameProposal(GameTestHelper helper) {
        ServerPlayer one = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);
        ServerPlayer two = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);
        String sharedName = one.getGameProfile().getName();
        // 全部 mock 玩家同名, 按名解析必然命中在线列表里第一个同名者; 求婚方取另一位, 免得踩到"不能向自己求婚"。
        ServerPlayer target = firstOnlineNamed(helper, sharedName);
        ServerPlayer proposer = target.getUUID().equals(one.getUUID()) ? two : one;

        JsonObject payload = new JsonObject();
        payload.addProperty("targetName", sharedName);
        JsonObject proposed = handle(helper, PROPOSE, proposer, payload);
        try {
            helper.assertTrue(target.getUUID().toString().equals(proposed.get("targetUuid").getAsString()),
                    "按名解析必须落到在线列表首个同名者身上, 实得 " + proposed.get("targetUuid").getAsString());
            helper.assertTrue(proposer.getUUID().toString().equals(proposed.get("proposalId").getAsString())
                            && proposer.getUUID().toString().equals(proposed.get("proposerUuid").getAsString()),
                    "proposalId 就是求婚方 UUID (一人一条 outgoing, 不另发自增 id)");
            helper.assertTrue(sharedName.equals(proposed.get("targetName").getAsString())
                            && !proposed.get("accepted").getAsBoolean(),
                    "刚发出的求婚尚未被接受");

            MarriageProposals table = proposals();
            helper.assertTrue(target.getUUID().equals(table.targetOf(proposer.getUUID())),
                    "意向必须真的落进面板与命令共用的那张表");
            helper.assertTrue(table.proposersFor(target.getUUID()).contains(proposer.getUUID()),
                    "反查侧必须同步看到这条求婚");

            JsonObject incoming = findProposal(
                    handle(helper, STATE, target, new JsonObject()).getAsJsonArray("incomingProposals"),
                    proposer.getUUID());
            helper.assertTrue(incoming != null, "被求婚方的面板必须列出这条 incoming 求婚");
            helper.assertTrue(!incoming.get("accepted").getAsBoolean()
                            && incoming.get("proposerOnline").getAsBoolean()
                            && sharedName.equals(incoming.get("proposerName").getAsString()),
                    "incoming 条目要带上未接受 + 求婚方在线 + 求婚方名字");

            JsonObject respondPayload = new JsonObject();
            respondPayload.addProperty("proposalId", proposer.getUUID().toString());
            respondPayload.addProperty("accept", true);
            JsonObject accepted = handle(helper, RESPOND, target, respondPayload);
            helper.assertTrue(accepted.get("accepted").getAsBoolean()
                            && proposer.getUUID().toString().equals(accepted.get("proposerUuid").getAsString()),
                    "接受回执要回明是哪条婚约被接受了");
            helper.assertTrue(table.isAccepted(proposer.getUUID(), target.getUUID()),
                    "接受必须写进共用的意向表 (否则 /marriage wed 那一步查不到)");

            JsonObject proposerState = handle(helper, STATE, proposer, new JsonObject());
            JsonObject outgoing = proposerState.getAsJsonObject("outgoingProposal");
            helper.assertTrue(target.getUUID().toString().equals(outgoing.get("targetUuid").getAsString())
                            && outgoing.get("accepted").getAsBoolean(),
                    "求婚方面板上那条 outgoing 必须变成已接受");
            helper.assertTrue("engaged".equals(proposerState.get("status").getAsString()),
                    "有一条已接受的婚约 -> engaged, 实得 " + proposerState.get("status").getAsString());
            helper.succeed();
        } finally {
            // 意向表是进程级单例, 用完必须清, 否则后面的用例会看到这条残留婚约。
            proposals().clear(proposer.getUUID());
        }
    }

    // ============================================================
    // 5. respond 的两道拒绝: 不是你的婚约不许动 / proposalId 形状要对
    // ============================================================

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void respondRefusesToTouchAProposalAddressedToSomeoneElse(GameTestHelper helper) {
        ServerPlayer proposer = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);
        ServerPlayer target = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);
        ServerPlayer stranger = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);
        MarriageProposals table = proposals();
        table.propose(proposer.getUUID(), target.getUUID());
        try {
            WebUiBusinessException notMine = rejection(helper, RESPOND, stranger,
                    respondPayload(proposer.getUUID().toString(), false));
            helper.assertTrue(WebUiErrorCodes.INVALID_REQUEST.equals(notMine.errorCode())
                            && "proposalId".equals(notMine.params().get("field")),
                    "应答别人的婚约要回 INVALID_REQUEST 并指名字段, 实得 "
                            + notMine.errorCode() + " " + notMine.params());
            helper.assertTrue(target.getUUID().equals(table.targetOf(proposer.getUUID())),
                    "被拒的那次一个字节都不许改意向表 (否则任何人都能一键拆散两人)");

            WebUiBusinessException malformed = rejection(helper, RESPOND, target,
                    respondPayload("not-a-uuid", true));
            helper.assertTrue(WebUiErrorCodes.INVALID_REQUEST.equals(malformed.errorCode())
                            && "proposalId".equals(malformed.params().get("field"))
                            && "not-a-uuid".equals(malformed.params().get("value")),
                    "proposalId 形状不对要回显被拒的值, 实得 " + malformed.params());

            JsonObject declined = handle(helper, RESPOND, target,
                    respondPayload(proposer.getUUID().toString(), false));
            helper.assertTrue(!declined.get("accepted").getAsBoolean(),
                    "拒绝回执的 accepted 必须是 false");
            helper.assertTrue(table.targetOf(proposer.getUUID()) == null,
                    "拒绝后正向表里那条意向必须消失");
            helper.assertTrue(!table.proposersFor(target.getUUID()).contains(proposer.getUUID()),
                    "拒绝后反查侧必须同步消失 (幽灵求婚)");
            helper.assertTrue(findProposal(handle(helper, STATE, target, new JsonObject())
                            .getAsJsonArray("incomingProposals"), proposer.getUUID()) == null,
                    "被拒的求婚不得再出现在面板的 incoming 列表里");
            helper.succeed();
        } finally {
            table.clear(proposer.getUUID());
        }
    }

    // ============================================================
    // 6. marriage.buyRing: 逐笔对账 + 两种拒绝
    // ============================================================

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void buyRingChargesExactlyOncePerRingAndRejectsWhenBroke(GameTestHelper helper) {
        WalletEconomy economy = new WalletEconomy();
        IEconomyService previous = swapEconomy(economy);
        try {
            ServerPlayer player = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);
            player.getInventory().clearContent();
            long cost = MiningServerConfig.MARRIAGE_ENGAGEMENT_COST.get();
            // 余额刻意不是成本的整数倍: "扣光余额"的错误实现会在第二枚之后露馅。
            economy.setCredit(player, cost * 2L + 7L);
            economy.setAzure(player, 42L);

            JsonObject bought = handle(helper, BUY_RING, player, new JsonObject());
            helper.assertTrue(bought.get("costCredit").getAsLong() == cost,
                    "回执的定价取 config, 实得 " + bought.get("costCredit").getAsLong());
            helper.assertTrue(economy.credit(player) == cost + 7L,
                    "买一枚恰扣一次成本, 实得余额 " + economy.credit(player));
            helper.assertTrue(engagementRings(player) == 1,
                    "买一枚就该真进背包一枚, 实得 " + engagementRings(player));
            helper.assertTrue(bought.get("engagementRingOwned").getAsBoolean(),
                    "戒指进了背包 -> engagementRingOwned 为真");
            JsonObject wallet = bought.getAsJsonObject("wallet");
            helper.assertTrue(wallet.get("credit").getAsLong() == cost + 7L && wallet.get("azure").getAsLong() == 42L,
                    "钱包回执是扣款后的双币真值, 实得 " + wallet);

            handle(helper, BUY_RING, player, new JsonObject());
            helper.assertTrue(engagementRings(player) == 2 && economy.credit(player) == 7L,
                    "第二枚同样恰扣一次, 实得 " + engagementRings(player) + " 枚 / 余额 " + economy.credit(player));

            WebUiBusinessException broke = rejection(helper, BUY_RING, player, new JsonObject());
            helper.assertTrue(WebUiErrorCodes.INSUFFICIENT_FUNDS.equals(broke.errorCode()),
                    "余额不足要回 INSUFFICIENT_FUNDS, 实得 " + broke.errorCode());
            helper.assertTrue(Long.toString(cost).equals(broke.params().get("cost"))
                            && "7".equals(broke.params().get("balance"))
                            && "CREDIT".equals(broke.params().get("currency")),
                    "拒绝要带上定价与当前余额供前端拼文案, 实得 " + broke.params());
            helper.assertTrue(engagementRings(player) == 2 && economy.credit(player) == 7L,
                    "被拒的那次不扣钱也不发戒指");

            EconomyServices.reset();
            WebUiBusinessException offline = rejection(helper, BUY_RING, player, new JsonObject());
            helper.assertTrue(WebUiErrorCodes.ECONOMY_OFFLINE.equals(offline.errorCode())
                            && offline.params().isEmpty(),
                    "经济未注册要回 ECONOMY_OFFLINE 且无占位符实参, 实得 "
                            + offline.errorCode() + " " + offline.params());
            helper.assertTrue(engagementRings(player) == 2, "经济掉线时同样不发戒指");
            helper.succeed();
        } finally {
            restoreEconomy(previous);
        }
    }

    // ============================================================
    // 7. marriage.wed: 成功对账 + 失败六态里的两条 + 不留半成品
    // ============================================================

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void wedChargesBothHalvesAndReportsEngineOutcomeCodes(GameTestHelper helper) {
        WalletEconomy economy = new WalletEconomy();
        IEconomyService previous = swapEconomy(economy);
        MarriageProposals table = proposals();
        ServerPlayer a = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);
        ServerPlayer b = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);
        ServerPlayer c = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);
        ServerPlayer d = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);
        try {
            ServerLevel overworld = helper.getLevel().getServer().overworld();
            MarriageRegistry registry = MarriageRegistry.get(overworld);
            long totalCost = MiningServerConfig.MARRIAGE_WEDDING_COST.get();
            economy.setCredit(a, 1_000_000L);
            economy.setCredit(b, 1_000_000L);
            giveEngagementRing(a);
            giveEngagementRing(b);
            table.propose(a.getUUID(), b.getUUID());
            table.accept(a.getUUID(), b.getUUID());

            JsonObject wed = handle(helper, WED, a, new JsonObject());
            helper.assertTrue(wed.get("ok").getAsBoolean() && "OK".equals(wed.get("outcomeCode").getAsString()),
                    "双方有戒指有钱且婚约已接受 -> 典礼成功, 实得 " + wed.get("outcomeCode").getAsString());
            helper.assertTrue("message.miningdim.marriage.wed.broadcast".equals(wed.get("messageKey").getAsString())
                            && wed.getAsJsonArray("messageArgs").size() == 2,
                    "成功用的是 /marriage 广播那条 lang 键, 且带双方名字两个实参");
            long marriageId = wed.get("marriageId").getAsLong();
            MarriageState married = registry.byId(marriageId);
            helper.assertTrue(married != null && married.involves(a.getUUID()) && married.involves(b.getUUID()),
                    "关系必须真的落进 MarriageRegistry");
            helper.assertTrue(wed.get("weddedAtTick").getAsLong() == married.marriedSinceTick(),
                    "典礼时刻回读关系自身的 marriedSinceTick");
            helper.assertTrue(b.getUUID().toString().equals(wed.get("partnerUuid").getAsString()),
                    "回执要指明伴侣是谁 (面板不必再猜)");
            helper.assertTrue(economy.credit(a) == 1_000_000L - (totalCost + 1L) / 2L
                            && economy.credit(b) == 1_000_000L - totalCost / 2L,
                    "双方各付一半 (奇数由发起方多付 1), 实得 A=" + economy.credit(a) + " B=" + economy.credit(b));
            helper.assertTrue(table.targetOf(a.getUUID()) == null && table.targetOf(b.getUUID()) == null,
                    "典礼成功后双方残留意向必须清掉 (否则面板上婚约一直挂着)");
            helper.assertTrue("married".equals(handle(helper, STATE, a, new JsonObject())
                            .get("status").getAsString()),
                    "典礼后面板状态转 married");

            // 失败态一: 没有已接受的婚约 (引擎之前就短路, 故无 partner)。
            JsonObject noProposal = handle(helper, WED, c, new JsonObject());
            helper.assertTrue(!noProposal.get("ok").getAsBoolean()
                            && "NO_ACCEPTED_PROPOSAL".equals(noProposal.get("outcomeCode").getAsString())
                            && noProposal.get("partnerUuid").isJsonNull()
                            && noProposal.get("marriageId").isJsonNull(),
                    "没婚约时回 NO_ACCEPTED_PROPOSAL, 且 partnerUuid/marriageId 是显式 null, 实得 " + noProposal);

            // 失败态二: 婚约齐备但双方没戒指 -> 引擎的 NO_ENGAGEMENT_RING, 且一分不扣、不留半成品关系。
            economy.setCredit(c, 1_000_000L);
            economy.setCredit(d, 1_000_000L);
            c.getInventory().clearContent();
            d.getInventory().clearContent();
            table.propose(c.getUUID(), d.getUUID());
            table.accept(c.getUUID(), d.getUUID());
            JsonObject noRing = handle(helper, WED, c, new JsonObject());
            helper.assertTrue(!noRing.get("ok").getAsBoolean()
                            && "NO_ENGAGEMENT_RING".equals(noRing.get("outcomeCode").getAsString()),
                    "没戒指要原样回引擎的 NO_ENGAGEMENT_RING, 实得 " + noRing.get("outcomeCode").getAsString());
            helper.assertTrue("message.miningdim.marriage.wed.no_ring".equals(noRing.get("messageKey").getAsString()),
                    "失败 lang 键与 /marriage wed 的同一张映射, 实得 " + noRing.get("messageKey").getAsString());
            helper.assertTrue(registry.forPlayer(c.getUUID()) == null && registry.forPlayer(d.getUUID()) == null,
                    "失败的典礼不许留下半成品关系");
            helper.assertTrue(economy.credit(c) == 1_000_000L && economy.credit(d) == 1_000_000L,
                    "失败的典礼一分都不许扣, 实得 C=" + economy.credit(c) + " D=" + economy.credit(d));
            helper.succeed();
        } finally {
            table.clear(a.getUUID());
            table.clear(c.getUUID());
            restoreEconomy(previous);
        }
    }

    /**
     * 同时有两份已接受的婚约时, 服务端不许替玩家挑一个办典礼 —— 回 INVALID_REQUEST 并指出缺的是 partnerName。
     * 把那道候选数判断删掉 (改成取迭代器第一个), 本条立刻挂在"没结成婚"上。
     */
    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void wedRefusesToGuessWhenTwoAcceptedProposalsExist(GameTestHelper helper) {
        WalletEconomy economy = new WalletEconomy();
        IEconomyService previous = swapEconomy(economy);
        MarriageProposals table = proposals();
        ServerPlayer me = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);
        ServerPlayer suitorOne = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);
        ServerPlayer suitorTwo = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);
        try {
            economy.setCredit(me, 1_000_000L);
            economy.setCredit(suitorOne, 1_000_000L);
            economy.setCredit(suitorTwo, 1_000_000L);
            giveEngagementRing(me);
            giveEngagementRing(suitorOne);
            giveEngagementRing(suitorTwo);
            table.propose(suitorOne.getUUID(), me.getUUID());
            table.accept(suitorOne.getUUID(), me.getUUID());
            table.propose(suitorTwo.getUUID(), me.getUUID());
            table.accept(suitorTwo.getUUID(), me.getUUID());

            WebUiBusinessException ambiguous = rejection(helper, WED, me, new JsonObject());
            helper.assertTrue(WebUiErrorCodes.INVALID_REQUEST.equals(ambiguous.errorCode())
                            && "partnerName".equals(ambiguous.params().get("field"))
                            && "2".equals(ambiguous.params().get("candidateCount")),
                    "两份已接受婚约时要让前端补 partnerName, 实得 "
                            + ambiguous.errorCode() + " " + ambiguous.params());
            helper.assertTrue(MarriageRegistry.get(helper.getLevel().getServer().overworld())
                            .forPlayer(me.getUUID()) == null,
                    "拒绝的那次绝不许把人跟其中一位结掉");
            helper.succeed();
        } finally {
            table.clear(suitorOne.getUUID());
            table.clear(suitorTwo.getUUID());
            restoreEconomy(previous);
        }
    }

    // ============================================================
    // 8. marriage.divorce: 扣一次成本 + 冷却递增 + 重复离婚不再扣
    // ============================================================

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void divorceDissolvesOnceChargesOnceAndReportsTheNewCooldown(GameTestHelper helper) {
        WalletEconomy economy = new WalletEconomy();
        IEconomyService previous = swapEconomy(economy);
        try {
            ServerLevel overworld = helper.getLevel().getServer().overworld();
            ServerPlayer a = richMock(helper, economy);
            ServerPlayer b = richMock(helper, economy);
            wedPair(overworld, a, b);
            MarriageRegistry registry = MarriageRegistry.get(overworld);
            long cost = MiningServerConfig.MARRIAGE_DIVORCE_COST.get();
            long creditBefore = economy.credit(a);

            JsonObject divorced = handle(helper, DIVORCE, a, new JsonObject());
            helper.assertTrue(divorced.get("ok").getAsBoolean()
                            && "OK".equals(divorced.get("outcomeCode").getAsString())
                            && "message.miningdim.marriage.divorce.done"
                            .equals(divorced.get("messageKey").getAsString()),
                    "已婚发起方离婚成功, 实得 " + divorced.get("outcomeCode").getAsString());
            helper.assertTrue(divorced.get("costCredit").getAsLong() == cost
                            && economy.credit(a) == creditBefore - cost,
                    "离婚成本恰扣一次, 实得余额 " + economy.credit(a));
            helper.assertTrue(b.getUUID().toString().equals(divorced.get("formerSpouseUuid").getAsString()),
                    "回执要指明跟谁离的");
            helper.assertTrue(registry.forPlayer(a.getUUID()) == null && registry.forPlayer(b.getUUID()) == null,
                    "关系必须从 Registry 解除 (双方都查不到)");
            helper.assertTrue(divorced.get("divorceCount").getAsInt() == 1,
                    "离婚次数记 1, 实得 " + divorced.get("divorceCount").getAsInt());
            helper.assertTrue(divorced.get("remarryCooldownTicks").getAsLong()
                            == MarriageTuning.remarryCooldownTicks(1),
                    "再婚冷却是按'第 1 次离婚'算出来的那个 tick 数, 实得 "
                            + divorced.get("remarryCooldownTicks").getAsLong());
            helper.assertTrue("cooldown".equals(handle(helper, STATE, a, new JsonObject())
                            .get("status").getAsString()),
                    "离婚后未婚且在冷却中 -> status=cooldown (前提: config remarryCooldownDays 非 0, 默认 7)");

            long creditAfter = economy.credit(a);
            JsonObject again = handle(helper, DIVORCE, a, new JsonObject());
            helper.assertTrue(!again.get("ok").getAsBoolean()
                            && "NOT_MARRIED".equals(again.get("outcomeCode").getAsString())
                            && "message.miningdim.marriage.not_married"
                            .equals(again.get("messageKey").getAsString()),
                    "已经离过的人再点一次要回 NOT_MARRIED, 实得 " + again.get("outcomeCode").getAsString());
            helper.assertTrue(economy.credit(a) == creditAfter,
                    "失败的离婚一分都不许扣, 实得 " + economy.credit(a));
            helper.assertTrue(again.get("divorceCount").getAsInt() == 1,
                    "失败的离婚不许把次数推高 (冷却会跟着翻倍)");
            helper.succeed();
        } finally {
            restoreEconomy(previous);
        }
    }

    // ============================================================
    // 9. marriage.sharedInv: 只读快照, 未解锁的格子一格不发
    // ============================================================

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void sharedInvMirrorsTheAuthoritativeContainerUpToTheUnlockedSlots(GameTestHelper helper) {
        WalletEconomy economy = new WalletEconomy();
        IEconomyService previous = swapEconomy(economy);
        try {
            ServerLevel overworld = helper.getLevel().getServer().overworld();
            ServerPlayer a = richMock(helper, economy);
            ServerPlayer b = richMock(helper, economy);
            long marriageId = wedPair(overworld, a, b);
            MarriageState married = MarriageRegistry.get(overworld).byId(marriageId);

            int slots = new MarriageBackpackMenu.Provider(married, MarriageRegistry.get(overworld),
                    new MarriageBackpackSessions(), overworld).visibleSlots();
            helper.assertTrue(slots > 0 && slots < MarriageState.SHARED_INV_SIZE,
                    "前置条件: 婚龄 0 天只解锁第 1 级 (config backpackSlots 首项 9 < 54), 实得 " + slots);
            married.sharedInv().set(0, new ItemStack(Items.REDSTONE, 16));
            // 放一件在最后一格 (等级未解锁): 它属于容器但不属于本次可见面, 一个字节都不该出现在回执里。
            married.sharedInv().set(MarriageState.SHARED_INV_SIZE - 1, new ItemStack(Items.BREAD, 3));

            JsonObject inv = handle(helper, SHARED_INV, a, new JsonObject());
            helper.assertTrue(inv.get("married").getAsBoolean()
                            && inv.get("marriageId").getAsLong() == marriageId
                            && inv.get("capacity").getAsInt() == MarriageState.SHARED_INV_SIZE
                            && inv.get("slots").getAsInt() == slots,
                    "回执的关系/容量/可见格数要与真菜单同源, 实得 " + inv);

            JsonArray items = inv.getAsJsonArray("items");
            helper.assertTrue(items.size() == 1, "只有可见面内的那一格有货, 实得 " + items.size() + " 条");
            JsonObject redstone = items.get(0).getAsJsonObject();
            helper.assertTrue(redstone.get("slot").getAsInt() == 0
                            && "minecraft:redstone".equals(redstone.get("itemId").getAsString())
                            && redstone.get("count").getAsInt() == 16
                            && Items.REDSTONE.getDescriptionId().equals(redstone.get("descriptionId").getAsString()),
                    "槽位 JSON 与 player.inventory 逐字同形 (slot/itemId/descriptionId/count), 实得 " + redstone);

            ServerPlayer single = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);
            JsonObject empty = handle(helper, SHARED_INV, single, new JsonObject());
            helper.assertTrue(!empty.get("married").getAsBoolean()
                            && empty.get("marriageId").isJsonNull()
                            && empty.get("level").getAsInt() == 0
                            && empty.get("slots").getAsInt() == 0
                            && empty.getAsJsonArray("items").isEmpty(),
                    "未婚是正常答案而不是错误: 回 married=false 的完整空回执, 实得 " + empty);

            // 权威容器是本关系持久数据, 用完清干净 (下个用例可能复用同一存档)。
            married.sharedInv().set(0, ItemStack.EMPTY);
            married.sharedInv().set(MarriageState.SHARED_INV_SIZE - 1, ItemStack.EMPTY);
            helper.succeed();
        } finally {
            restoreEconomy(previous);
        }
    }

    // ============================================================
    // 10. 注册名
    // ============================================================

    /**
     * 面板拿到的必须<b>就是</b>子系统自己那两张表 (实例同一性, 不是"内容碰巧一样")。
     *
     * 这条与"注册了没有"是两回事, 且是本模块最核心的不变量: 把 {@link MarriageSystem#register} 里的
     * {@code registerAll(proposals, backpackSessions)} 换成 {@code registerAll(new MarriageProposals(),
     * new MarriageBackpackSessions())}, action 照样注册成功、其余用例照样全绿 —— 它们全经
     * {@code MarriageWebUiActions.proposals()} 读同一份错表, 自洽。只有实例同一性能证伪它。
     */
    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void marriagePanelSharesTheSubsystemTablesNotACopy(GameTestHelper helper) {
        requireRegistered();
        helper.assertTrue(MarriageSystem.wiredProposals != null && MarriageSystem.wiredBackpackSessions != null,
                "前提校验: MarriageSystem.register 必须已经跑过并记下注入的两张表");
        helper.assertTrue(MarriageWebUiActions.proposals() == MarriageSystem.wiredProposals,
                "面板读的婚约意向表必须与 /marriage 命令是同一个实例 —— 各 new 一份的话, 命令行求的婚"
                        + "面板永远看不见");
        helper.assertTrue(MarriageWebUiActions.sessions() == MarriageSystem.wiredBackpackSessions,
                "面板用的共享背包会话表同样必须是同一个实例 —— 否则离婚时 forceCloseAll 作用在一张空表上, "
                        + "双方正开着的共享背包窗口不会被关掉 (spec 第四章的并发 dupe 窗口)");
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void marriageActionsAreRegisteredUnderContractNames(GameTestHelper helper) {
        requireRegistered();
        for (String action : List.of(STATE, BUY_RING, PROPOSE, RESPOND, WED, DIVORCE, SHARED_INV)) {
            helper.assertTrue(WebUiServerDispatcher.resolve(action) != null,
                    action + " 必须由 MarriageWebUiActions.registerAll 注册进派发器");
        }
        helper.assertTrue(WebUiServerDispatcher.resolve("marriage.buyring") == null
                        && WebUiServerDispatcher.resolve("marriage.accept") == null
                        && WebUiServerDispatcher.resolve("marriage.sharedInventory") == null,
                "不得注册别名 action (accept/decline 合并在 marriage.respond 里)");
        helper.succeed();
    }

    // ============================================================
    // 工具
    // ============================================================

    /**
     * 注册守卫。<b>刻意不在测试侧补注册</b>: registerAll 要绑的是子系统自己那两张活表, 测试侧现 new 两张顶上,
     * 全部用例都会在那份自娱自乐的副本上自洽通过 —— 而本模块最核心的不变量恰恰是"面板改的就是 /marriage
     * 命令读的那一张表"。补注册等于把这条不变量从测试里删掉: 把 MarriageSystem.register 里的接线整行删掉
     * 或换成 new 出来的空表, 十条用例照样全绿, 真服上 B 却永远看不到 A 的求婚。
     *
     * 没注册就是 {@link MarriageSystem#register} 的接线掉了, 直接炸。
     */
    private static void requireRegistered() {
        if (WebUiServerDispatcher.resolve(STATE) == null) {
            throw new IllegalStateException(
                    "marriage.* action 未注册: MarriageSystem.register 没有调用 MarriageWebUiActions.registerAll");
        }
    }

    private static JsonObject handle(GameTestHelper helper, String action, ServerPlayer sender, JsonObject payload) {
        return JsonParser.parseString(handler(helper, action).handle(sender, payload)).getAsJsonObject();
    }

    private static WebUiBusinessException rejection(GameTestHelper helper, String action,
                                                    ServerPlayer sender, JsonObject payload) {
        try {
            handler(helper, action).handle(sender, payload);
        } catch (WebUiBusinessException rejected) {
            return rejected;
        }
        helper.fail("该请求本应被业务拒绝, 实际却成功返回了: " + action + " " + payload);
        throw new IllegalStateException("unreachable: helper.fail already threw");
    }

    private static WebUiAction handler(GameTestHelper helper, String action) {
        requireRegistered();
        WebUiAction handler = WebUiServerDispatcher.resolve(action);
        if (handler == null) {
            helper.fail("action " + action + " 未注册进派发器");
            throw new IllegalStateException("unreachable: helper.fail already threw");
        }
        return handler;
    }

    /**
     * 面板与 /marriage 命令共用的那张婚约意向表。
     *
     * 经 {@link #requireRegistered()} 取而不是直接 new: 用例要验的正是"面板改的就是命令行读的那一张表",
     * 自己 new 一张只会验出一个自娱自乐的副本。
     */
    private static MarriageProposals proposals() {
        requireRegistered();
        return MarriageWebUiActions.proposals();
    }

    private static JsonObject respondPayload(String proposalId, boolean accept) {
        JsonObject payload = new JsonObject();
        payload.addProperty("proposalId", proposalId);
        payload.addProperty("accept", accept);
        return payload;
    }

    /** 在线列表里第一个叫这个名字的玩家 (与 action 侧按名找人的口径一致: 大小写不敏感, 首个命中)。 */
    private static ServerPlayer firstOnlineNamed(GameTestHelper helper, String name) {
        for (ServerPlayer candidate : helper.getLevel().getServer().getPlayerList().getPlayers()) {
            if (candidate.getGameProfile().getName().equalsIgnoreCase(name)) {
                return candidate;
            }
        }
        throw new IllegalStateException("no online player named " + name + " right after creating one");
    }

    /** incoming 列表里 proposalId 等于该求婚方的那一条; 没有返回 null (列表里可能还有别的用例留下的条目)。 */
    private static JsonObject findProposal(JsonArray proposals, UUID proposer) {
        for (JsonElement element : proposals) {
            JsonObject row = element.getAsJsonObject();
            if (proposer.toString().equals(row.get("proposalId").getAsString())) {
                return row;
            }
        }
        return null;
    }

    /** 造一个有钱的 mock 玩家 (典礼/离婚都要扣费)。 */
    private static ServerPlayer richMock(GameTestHelper helper, WalletEconomy economy) {
        ServerPlayer player = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);
        economy.setCredit(player, 1_000_000L);
        return player;
    }

    private static void giveEngagementRing(ServerPlayer player) {
        player.getInventory().clearContent();
        player.getInventory().add(RingItem.createEngagement(ModItems.ENGAGEMENT_RING.get()));
    }

    private static int engagementRings(ServerPlayer player) {
        int found = 0;
        for (ItemStack stack : player.getInventory().items) {
            if (stack.getItem() instanceof RingItem ring && ring.isEngagement()) {
                found++;
            }
        }
        return found;
    }

    /** 直接经引擎办一场典礼 (面板路径之外的前置装配; 双方须已有钱)。 */
    private static long wedPair(ServerLevel overworld, ServerPlayer a, ServerPlayer b) {
        giveEngagementRing(a);
        giveEngagementRing(b);
        MarriageEngine.WeddingResult result = new MarriageEngine(overworld)
                .wed(a, b, MiningServerConfig.MARRIAGE_WEDDING_COST.get(), null);
        if (!result.success()) {
            throw new IllegalStateException("test setup wedding failed: " + result.reason());
        }
        return result.marriageId();
    }

    private static IEconomyService swapEconomy(WalletEconomy fake) {
        IEconomyService previous = EconomyServices.isRegistered() ? EconomyServices.economyService() : null;
        EconomyServices.registerEconomyService(fake);
        return previous;
    }

    private static void restoreEconomy(IEconomyService previous) {
        if (previous != null) {
            EconomyServices.registerEconomyService(previous);
        } else {
            EconomyServices.reset();
        }
    }

    /**
     * 记账替身: 只实现婚姻路径真正走到的四个方法 (双币余额 + tryCharge + grant), 其余一律抛
     * UnsupportedOperationException 暴露误用, 不静默返默认值。
     *
     * 与 MarriageGameTests 里那份的差别是本份带青辉石余额 —— marriage.buyRing 的回执要发双币钱包, 用只认信用点的
     * 替身会在 heartstoneBalance 上炸掉。
     */
    private static final class WalletEconomy implements IEconomyService {

        private final Map<UUID, Long> credit = new HashMap<>();
        private final Map<UUID, Long> azure = new HashMap<>();

        void setCredit(ServerPlayer player, long amount) {
            credit.put(player.getUUID(), amount);
        }

        void setAzure(ServerPlayer player, long amount) {
            azure.put(player.getUUID(), amount);
        }

        long credit(ServerPlayer player) {
            return credit.getOrDefault(player.getUUID(), 0L);
        }

        @Override
        public long creditBalance(ServerPlayer player) {
            return credit(player);
        }

        @Override
        public long heartstoneBalance(ServerPlayer player) {
            return azure.getOrDefault(player.getUUID(), 0L);
        }

        @Override
        public boolean tryCharge(ServerPlayer player, Currency currency, long amount) {
            if (amount <= 0L) {
                throw new EconomyException(EconomyException.Reason.ILLEGAL_AMOUNT, "amount must be > 0: " + amount);
            }
            Map<UUID, Long> book = currency == Currency.CREDIT ? credit : azure;
            long balance = book.getOrDefault(player.getUUID(), 0L);
            if (balance < amount) {
                return false;
            }
            book.put(player.getUUID(), balance - amount);
            return true;
        }

        @Override
        public void grant(ServerPlayer player, Currency currency, long amount) {
            if (amount <= 0L) {
                throw new EconomyException(EconomyException.Reason.ILLEGAL_AMOUNT, "amount must be > 0: " + amount);
            }
            Map<UUID, Long> book = currency == Currency.CREDIT ? credit : azure;
            book.merge(player.getUUID(), amount, Long::sum);
        }

        @Override
        public boolean tryChargeDaily(ServerPlayer player, Currency currency, long amount,
                                      String dailyKey, long dailyCap) {
            throw new UnsupportedOperationException("mock: tryChargeDaily not used by marriage web ui tests");
        }

        @Override
        public long settleOreSale(ServerPlayer player, EconomyConstants.HighValueOre ore,
                                  int countSoFar, double basePrice) {
            throw new UnsupportedOperationException("mock: settleOreSale not used by marriage web ui tests");
        }

        @Override
        public int recordMinedOreDrops(ServerPlayer player, Block block, int producedCount) {
            throw new UnsupportedOperationException("mock: recordMinedOreDrops not used by marriage web ui tests");
        }

        @Override
        public long grantDaily(ServerPlayer player, long rawCredit, String faucetKey, long dailyCap) {
            throw new UnsupportedOperationException("mock: grantDaily not used by marriage web ui tests");
        }

        @Override
        public long grantAzureDaily(ServerPlayer player, long amount, long dailyCap) {
            throw new UnsupportedOperationException("mock: grantAzureDaily not used by marriage web ui tests");
        }

        @Override
        public boolean isAfkFrozen(ServerPlayer player) {
            return false;
        }
    }
}
