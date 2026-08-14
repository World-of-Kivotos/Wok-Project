package com.miningdim.market;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.stream.JsonReader;
import com.miningdim.core.MiningConstants;
import com.miningdim.economy.AbuseGuard;
import com.miningdim.economy.EconomyConstants;
import com.miningdim.economy.EconomyService;
import com.miningdim.economy.EconomyServices;
import com.miningdim.economy.IEconomyService;
import com.miningdim.economy.PlayerAbuseState;
import com.miningdim.economy.SqliteEconomyLedger;
import com.miningdim.entry.IMiningPlayerData;
import com.miningdim.entry.MiningCapabilities;
import com.miningdim.entry.UiPrefs;
import com.miningdim.job.JobId;
import com.miningdim.job.JobXpCurve;
import com.miningdim.job.JobXpPolicies;
import com.miningdim.job.brewer.BrewerItems;
import com.miningdim.job.brewer.WineNbt;
import com.miningdim.job.brewer.WineQuality;
import com.miningdim.job.brewer.WineType;
import com.miningdim.job.engineer.ModEngineerItems;
import com.miningdim.job.engineer.NanoEffect;
import com.miningdim.job.engineer.NanoNbt;
import com.miningdim.job.engineer.NanoTier;
import com.miningdim.job.munitions.ModMunitionsItems;
import com.miningdim.job.munitions.gunsmith.GunsmithAssemblyRecipe;
import com.miningdim.job.munitions.gunsmith.GunsmithBlueprint;
import com.miningdim.job.munitions.gunsmith.GunsmithBlueprintItem;
import com.miningdim.job.munitions.gunsmith.GunsmithGunStats;
import com.miningdim.job.munitions.gunsmith.GunsmithPartItem;
import com.miningdim.job.munitions.gunsmith.GunsmithPartQuality;
import com.miningdim.job.munitions.gunsmith.GunsmithPlatform;
import com.miningdim.job.munitions.gunsmith.GunsmithPressPart;
import com.miningdim.job.tarot.TarotCardItem;
import com.miningdim.job.tarot.TarotQuality;
import com.miningdim.job.tarot.TarotRegistry;
import com.miningdim.testutil.MockGameTestPlayers;
import com.miningdim.webui.server.WebUiBusinessException;
import com.miningdim.webui.server.WebUiErrorCodes;
import com.miningdim.webui.server.WebUiServerDispatcher;
import com.miningdim.webui.server.WebUiServerDispatcher.WebUiAction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.PlayerList;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import java.io.IOException;
import java.io.StringReader;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;

/**
 * W1 新增的 5 个 player.* action 的 GameTest (player.isOp / player.itemDetail / player.profile /
 * player.prefs.get / player.prefs.set)。与 {@link MarketBridgeGameTests} 同 batch、同款内存 SQLite 与经济门面
 * swap/restore 范式, 同样直接调 handle 拿 resultJson 再解析断言 (服务端纯逻辑, 不经网络层)。
 *
 * 强断言 (删被测核心逻辑必挂):
 *  1. player.isOp 随真实 PlayerList.isOp 翻转;
 *  2. itemDetail 的三个拒绝码各带稳定 params (slot/size), 且 -1 命中的是越界而不是空槽;
 *  3. itemDetail 对枪匠零件/塔罗牌/组装枪给出正确大类与数值行, 且复用 player.inventory 的变体字段;
 *  4. <b>脏 NBT 一律降级不抛</b> —— 裸零件/裸塔罗/坏枪三种都必须回 kind=plain 且带 data.unreadable 标记;
 *  5. profile 的今日信用点是衰减<b>前</b>的毛额 (与实际到账额不等), 青辉石是实发额;
 *  6. profile 的 nextLevelXp 是本级跨度, 满级时与 levelXp 同时发 0;
 *  7. prefs 整份读写往返落到 capability, 且非法值转 INVALID_REQUEST 并在 params 里指名字段;
 *  8. 普通物品也走同一套基础字段与变体字段 (改名物品发 displayName + nameParts, 没改过的两个变体键整键缺席);
 *  9. <b>v1 老枪不按 version 分档少发行、也不降级</b> —— 九行相对增减恒发全, 这是对抗复核 M3 的落点;
 * 10. 酒与纳米两类的数值行与标签按各自子系统的既有口径下发 (变质酒强度恒 0 / tick 不折算成秒);
 * 11. 酒的两条降级也守同一纪律 —— 非有限年份不许让回执长出 NaN/Infinity 字面量, 品质 id 解不出来不许静默落
 *     plain, 两者都必须打 data.unreadable:wine;
 * 12. 七条 action 确实以契约里的名字注册进派发器 (直接调常量的用例发现不了名字打错)。
 */
@GameTestHolder(MiningConstants.MODID)
@PrefixGameTestTemplate(false)
public final class PlayerWebUiW1GameTests {

    private static final String EMPTY = "empty";
    private static final String BATCH = "market";

    /** 主背包槽数 (与 player.inventory / market.place 同一索引空间)。 */
    private static final int MAIN_INVENTORY_SIZE = 36;

    /**
     * 组装枪必发的数值行全集 (九行相对增减 + 零件数)。写死在测试里而不是引用被测常量: 行表是前端要照着做
     * 文案表的契约面, 两边一起改还是绿的等于没测。
     */
    private static final String[] GUN_STAT_KEYS = {
            "damage", "headshot", "range", "handling", "average",
            "fireRate", "verticalRecoil", "horizontalRecoil", "inaccuracy", "partCount"};

    /** 七条 player.* action 的契约名 (前端 SERVER_ACTIONS 逐字用的就是这些字符串)。 */
    private static final String[] PLAYER_ACTION_NAMES = {
            "player.inventory", "player.wallet", "player.isOp", "player.itemDetail",
            "player.profile", "player.prefs.get", "player.prefs.set"};

    // ============================================================
    // 1. player.isOp
    // ============================================================

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void playerIsOpFollowsServerOpList(GameTestHelper helper) {
        ServerPlayer player = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);
        PlayerList playerList = player.getServer().getPlayerList();

        helper.assertTrue(!handle(PlayerWebUiActions.IS_OP, player).get("isOp").getAsBoolean(),
                "新登记的 mock 玩家不是 OP");
        playerList.op(player.getGameProfile());
        try {
            helper.assertTrue(handle(PlayerWebUiActions.IS_OP, player).get("isOp").getAsBoolean(),
                    "被 op 之后 player.isOp 必须翻成 true (判据是真实 PlayerList.isOp)");
        } finally {
            playerList.deop(player.getGameProfile());
        }
        helper.succeed();
    }

    // ============================================================
    // 2. player.itemDetail: 三个拒绝码 + params
    // ============================================================

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void itemDetailRejectsBadSlotWithStableCodesAndParams(GameTestHelper helper) {
        ServerPlayer player = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);
        player.getInventory().clearContent();

        WebUiBusinessException missing = rejection(helper, PlayerWebUiActions.ITEM_DETAIL, player, new JsonObject());
        helper.assertTrue(WebUiErrorCodes.INVALID_REQUEST.equals(missing.errorCode()),
                "缺 slot 字段应回 INVALID_REQUEST, 实得 " + missing.errorCode());
        helper.assertTrue("slot".equals(missing.params().get("field")),
                "缺字段的拒绝必须在 params.field 里指名是哪个字段");

        // 前端自检探针发的就是 -1, 它命中的是越界而不是空槽 —— 两条码不许混为一谈。
        WebUiBusinessException outOfRange =
                rejection(helper, PlayerWebUiActions.ITEM_DETAIL, player, slotPayload(-1));
        helper.assertTrue(WebUiErrorCodes.SLOT_OUT_OF_RANGE.equals(outOfRange.errorCode()),
                "slot=-1 应回 SLOT_OUT_OF_RANGE, 实得 " + outOfRange.errorCode());
        helper.assertTrue("-1".equals(outOfRange.params().get("slot"))
                        && Integer.toString(MAIN_INVENTORY_SIZE).equals(outOfRange.params().get("size")),
                "越界拒绝必须带 slot 与 size 两个占位符实参, 实得 " + outOfRange.params());

        WebUiBusinessException aboveRange =
                rejection(helper, PlayerWebUiActions.ITEM_DETAIL, player, slotPayload(MAIN_INVENTORY_SIZE));
        helper.assertTrue(WebUiErrorCodes.SLOT_OUT_OF_RANGE.equals(aboveRange.errorCode()),
                "slot=" + MAIN_INVENTORY_SIZE + " 已越上界 (合法域是 [0,36)), 实得 " + aboveRange.errorCode());

        WebUiBusinessException empty =
                rejection(helper, PlayerWebUiActions.ITEM_DETAIL, player, slotPayload(5));
        helper.assertTrue(WebUiErrorCodes.SLOT_EMPTY.equals(empty.errorCode()),
                "合法但空的槽位应回 SLOT_EMPTY, 实得 " + empty.errorCode());
        helper.assertTrue("5".equals(empty.params().get("slot")) && !empty.params().containsKey("size"),
                "空槽拒绝只带 slot 一个实参, 实得 " + empty.params());

        JsonObject notAnInt = new JsonObject();
        notAnInt.addProperty("slot", 1.5D);
        WebUiBusinessException fractional =
                rejection(helper, PlayerWebUiActions.ITEM_DETAIL, player, notAnInt);
        helper.assertTrue(WebUiErrorCodes.INVALID_REQUEST.equals(fractional.errorCode()),
                "非整数 slot 应回 INVALID_REQUEST, 实得 " + fractional.errorCode());

        helper.succeed();
    }

    // ============================================================
    // 3. player.itemDetail: 枪匠零件 / 塔罗牌 / 组装枪
    // ============================================================

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void itemDetailDescribesGunsmithPartAndReusesVariantFields(GameTestHelper helper) {
        ServerPlayer player = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);
        player.getInventory().clearContent();
        ItemStack part = GunsmithPartItem.createStack(ModMunitionsItems.GUNSMITH_PART.get(),
                GunsmithPlatform.AR, GunsmithPressPart.GRIP, GunsmithPartQuality.LEGENDARY, 1.4D);
        player.getInventory().setItem(0, part);

        JsonObject detail = handle(PlayerWebUiActions.ITEM_DETAIL, player, slotPayload(0));
        helper.assertTrue("gunsmith_part".equals(detail.get("kind").getAsString()),
                "枪匠零件的大类是 gunsmith_part, 实得 " + detail.get("kind").getAsString());

        JsonObject coefficient = attribute(detail, "coefficient");
        helper.assertTrue(coefficient != null && Double.compare(coefficient.get("value").getAsDouble(), 1.4D) == 0,
                "coefficient 必须逐位等于零件 NBT 里的品质系数 1.4");
        helper.assertTrue(coefficient != null && "flat".equals(coefficient.get("unit").getAsString()),
                "品质系数是绝对值不是百分比增减");
        // 基础变体的三个乘数恒为 1.0, 发三行 +0% 只是噪音。
        helper.assertTrue(attribute(detail, "fireRate") == null,
                "基础变体零件不发 fireRate 这类恒零的偏移行");

        helper.assertTrue(hasTag(detail, "part.quality:" + GunsmithPartQuality.LEGENDARY.id()),
                "标签必须带品质稳定 id (前端靠它出中文, 不是靠服务端下发文案)");
        helper.assertTrue(hasTag(detail, "part.platform:" + GunsmithPlatform.AR.id())
                        && hasTag(detail, "part.slot:" + GunsmithPressPart.GRIP.id()),
                "标签必须带平台与部位稳定 id");

        // 变体字段必须与 player.inventory 同源 —— 另起一套的症状是 195 种零件在详情页又变回同名同图标。
        int expectedModelData = part.getOrCreateTag().getInt("CustomModelData");
        helper.assertTrue(detail.has("customModelData")
                        && detail.get("customModelData").getAsInt() == expectedModelData,
                "itemDetail 必须复用 WebUiItemJson 的 customModelData, 期望 " + expectedModelData);
        helper.assertTrue(detail.has("nameParts") && detail.getAsJsonArray("nameParts").size() >= 2,
                "itemDetail 必须复用 WebUiItemJson 的 nameParts 结构化名字");

        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void itemDetailDescribesTarotCardIdentity(GameTestHelper helper) {
        ServerPlayer player = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);
        player.getInventory().clearContent();
        player.getInventory().setItem(0, TarotCardItem.create(TarotRegistry.TAROT_CARD.get(),
                7, TarotQuality.SR, false, player.getUUID()));

        JsonObject detail = handle(PlayerWebUiActions.ITEM_DETAIL, player, slotPayload(0));
        helper.assertTrue("tarot".equals(detail.get("kind").getAsString()),
                "盖好三键的塔罗牌大类是 tarot, 实得 " + detail.get("kind").getAsString());

        JsonObject cardId = attribute(detail, "cardId");
        helper.assertTrue(cardId != null && cardId.get("value").getAsInt() == 7,
                "cardId 必须原样下发牌面编号 7");
        helper.assertTrue(hasTag(detail, "tarot.quality:" + TarotQuality.SR.id()),
                "标签必须带品质稳定 id");
        // 正逆位发成两个互斥标签, 逆位牌绝不能出现 upright。
        helper.assertTrue(hasTag(detail, "tarot.reversed") && !hasTag(detail, "tarot.upright"),
                "逆位牌必须发 tarot.reversed 且不得同时发 tarot.upright");
        helper.assertTrue(hasTag(detail, "tarot.bound"),
                "带 ownerUUID 的牌必须发 tarot.bound (绑定与否决定这张牌打不打得出)");

        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void itemDetailDescribesAssembledGunWithRelativeStats(GameTestHelper helper) {
        ServerPlayer player = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);
        player.getInventory().clearContent();
        ItemStack gun = assembledM4Gun();
        player.getInventory().setItem(0, gun);

        GunsmithGunStats expected = GunsmithGunStats.from(gun);
        helper.assertTrue(expected != null, "测试自身构造的枪必须能被 GunsmithGunStats 读出来");

        JsonObject detail = handle(PlayerWebUiActions.ITEM_DETAIL, player, slotPayload(0));
        helper.assertTrue("gun".equals(detail.get("kind").getAsString()),
                "组装枪的大类是 gun, 实得 " + detail.get("kind").getAsString());

        // 相对基准的增减量: 服务端已换算完 (系数 - 1.0), 前端不该再减一次。
        JsonObject damage = attribute(detail, "damage");
        helper.assertTrue(damage != null
                        && Double.compare(damage.get("value").getAsDouble(), expected.damage() - 1.0D) == 0,
                "damage 行必须是 (系数 - 1.0) 的增减量");
        helper.assertTrue(damage != null && "percent".equals(damage.get("unit").getAsString()),
                "damage 行的单位是 percent");

        // 这两条复用现成的 -1.0 口径方法, 自己再换算一遍就会与枪械 tooltip 漂移。
        JsonObject verticalRecoil = attribute(detail, "verticalRecoil");
        helper.assertTrue(verticalRecoil != null
                        && Double.compare(verticalRecoil.get("value").getAsDouble(), expected.recoilChange()) == 0,
                "verticalRecoil 必须逐位等于 recoilChange()");
        JsonObject inaccuracy = attribute(detail, "inaccuracy");
        helper.assertTrue(inaccuracy != null
                        && Double.compare(inaccuracy.get("value").getAsDouble(), expected.spreadChange()) == 0,
                "inaccuracy 必须逐位等于 spreadChange()");

        JsonObject partCount = attribute(detail, "partCount");
        helper.assertTrue(partCount != null
                        && partCount.get("value").getAsInt() == expected.parts().size(),
                "partCount 必须等于枪上真实零件数 " + expected.parts().size());

        helper.assertTrue(hasTag(detail, "gun.template:" + GunsmithBlueprint.M4A1.templateId()),
                "标签必须带图纸 templateId");
        helper.assertTrue(hasTag(detail, "gun.platform:" + GunsmithPlatform.AR.id()),
                "标签必须带平台 id");
        helper.assertTrue(!hasTag(detail, "data.unreadable:gun"),
                "读得出来的枪不得带降级标记");

        helper.succeed();
    }

    // ============================================================
    // 4. player.itemDetail: 脏 NBT 一律降级不抛 (D8 的核心)
    // ============================================================

    /**
     * 三种脏数据都是<b>正常游玩产物</b>而不是攻击: 创造模式直给的裸零件与裸塔罗牌、跨平衡改动后缓存 stats
     * 对不上的老枪。让它们冒泡等于"背包里某些物品点开就报错"。
     *
     * 本条同时锁住一个具体陷阱: kind 判定绝不能用 {@code GunsmithPartItem.isGunsmithPart} —— 那个方法内部
     * 就调 requirePartData 会抛, 用它当探针的话裸零件在第一步就炸, 所谓"先探再取"的防线根本不存在。
     */
    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void itemDetailDegradesDirtyNbtInsteadOfThrowing(GameTestHelper helper) {
        ServerPlayer player = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);
        player.getInventory().clearContent();

        // 槽 0: 创造模式直给的裸枪匠零件 (物品 id 对, 一个 NBT 键都没有)。
        player.getInventory().setItem(0, new ItemStack(ModMunitionsItems.GUNSMITH_PART.get()));
        // 槽 1: 创造模式直给的裸塔罗牌 (源码注释自认这是真实场景)。
        player.getInventory().setItem(1, new ItemStack(TarotRegistry.TAROT_CARD.get()));
        // 槽 2: 有枪匠根标签但内容读不出来的枪 (等价于跨版本 / 跨平衡的老枪)。
        ItemStack brokenGun = new ItemStack(Items.IRON_HOE);
        brokenGun.getOrCreateTag().put(GunsmithGunStats.ROOT_KEY, new CompoundTag());
        player.getInventory().setItem(2, brokenGun);

        assertDegraded(helper, player, 0, "gunsmith_part");
        assertDegraded(helper, player, 1, "tarot");
        assertDegraded(helper, player, 2, "gun");
        helper.succeed();
    }

    private static void assertDegraded(GameTestHelper helper, ServerPlayer player, int slot, String expectedKind) {
        JsonObject detail = handle(PlayerWebUiActions.ITEM_DETAIL, player, slotPayload(slot));
        helper.assertTrue("plain".equals(detail.get("kind").getAsString()),
                "槽 " + slot + " 的脏 " + expectedKind + " 必须降级成 plain, 实得 " + detail.get("kind").getAsString());
        // 降级不是静默: 玩家与开发都要看得见是哪一类读不出来。
        helper.assertTrue(hasTag(detail, "data.unreadable:" + expectedKind),
                "槽 " + slot + " 必须带 data.unreadable:" + expectedKind + " 标记");
        helper.assertTrue(detail.getAsJsonArray("attributes").isEmpty(),
                "降级后不得凭空发数值行");
    }

    // ============================================================
    // 5. player.profile: 今日两栏的口径不对称
    // ============================================================

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void profileReportsPreDecayCreditGrossAndNetAzure(GameTestHelper helper) {
        SqliteEconomyLedger ledger = SqliteEconomyLedger.openInMemory();
        IEconomyService prev = swapEconomy(new EconomyService(ledger, new AbuseGuard(), newStateResolver()));
        try {
            ServerPlayer player = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);
            IEconomyService economy = EconomyServices.economyService();

            // 跨两个衰减档 (每档 60000 毛): 只有这样毛额与实发额才不相等, 两者混用才会被本条抓出来。
            long rawCredit = 2L * EconomyConstants.GLOBAL_DAILY_CREDIT_FAUCET_TIER;
            long netCredit = economy.grantDaily(player, rawCredit,
                    EconomyConstants.GLOBAL_DAILY_CREDIT_FAUCET_KEY,
                    EconomyConstants.GLOBAL_DAILY_CREDIT_FAUCET_TIER);
            long grantedAzure = economy.grantAzureDaily(player, 5L, EconomyConstants.AZURE_DAILY_FAUCET_CAP);

            JsonObject profile = handle(PlayerWebUiActions.PROFILE, player);
            helper.assertTrue(profile.get("todayCreditFaucetGross").getAsLong() == rawCredit,
                    "todayCreditFaucetGross 必须是衰减前毛额 " + rawCredit
                            + ", 实得 " + profile.get("todayCreditFaucetGross").getAsLong());
            helper.assertTrue(netCredit < rawCredit && netCredit > 0L,
                    "前提校验: 跨档后实发额必须严格小于毛额 (实发 " + netCredit + " / 毛额 " + rawCredit + ")");
            helper.assertTrue(profile.get("todayCreditFaucetGross").getAsLong() != netCredit,
                    "毛额栏绝不能被写成实发额 (两者此刻分别是 " + rawCredit + " 与 " + netCredit + ")");

            // 青辉石走硬截断, 账本记的天然就是实发额, 与钱包余额同值。
            helper.assertTrue(grantedAzure == 5L, "前提校验: 未撞上限时青辉石应全额发放");
            helper.assertTrue(profile.get("todayAzureIn").getAsLong() == 5L,
                    "todayAzureIn 必须是实发额 5, 实得 " + profile.get("todayAzureIn").getAsLong());

            JsonObject wallet = profile.getAsJsonObject("wallet");
            helper.assertTrue(wallet.get("credit").getAsLong() == netCredit,
                    "wallet.credit 必须是账本真实余额 (= 实发额 " + netCredit + ")");
            helper.assertTrue(wallet.get("azure").getAsLong() == 5L,
                    "wallet.azure 必须是账本真实余额 5");
            helper.assertTrue(player.getGameProfile().getName().equals(profile.get("playerName").getAsString()),
                    "playerName 取服务端校验过的 sender, 不信前端");

            helper.succeed();
        } finally {
            restoreEconomy(prev);
        }
    }

    // ============================================================
    // 6. player.profile: 等级跨度与满级态
    // ============================================================

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void profileDerivesLevelSpanAndZeroesBothAtMaxLevel(GameTestHelper helper) {
        SqliteEconomyLedger ledger = SqliteEconomyLedger.openInMemory();
        IEconomyService prev = swapEconomy(new EconomyService(ledger, new AbuseGuard(), newStateResolver()));
        try {
            ServerPlayer player = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);
            IMiningPlayerData data = MiningCapabilities.get(player).orElseThrow(
                    () -> new IllegalStateException("mock 玩家没有挂上矿山玩家数据 capability"));
            // 5 级整 + 500 点本级进度: 只有"本级已获/本级跨度"这一对口径才能同时对上下面两条断言。
            // 入账日戳必须取 profile 用的那一份 (economy.currentDayStamp), 不能写死一个假日戳 —— dailyXp
            // 现在按日戳判翻日, 拿昨天的戳入账再断言今天读得到 500, 断的是错的行为。
            long today = EconomyServices.economyService().currentDayStamp();
            data.jobProgress(JobId.MINER).setLevel(5);
            data.jobProgress(JobId.MINER).grantXp(JobId.MINER, 500L, today);
            data.jobProgress(JobId.BREWER).setLevel(JobXpCurve.MAX_LEVEL);

            JsonObject profile = handle(PlayerWebUiActions.PROFILE, player);
            JsonArray jobs = profile.getAsJsonArray("jobs");
            helper.assertTrue(jobs.size() == JobId.values().length,
                    "jobs 恒发 " + JobId.values().length + " 条, 实得 " + jobs.size());
            for (int i = 0; i < JobId.values().length; i++) {
                String actual = jobs.get(i).getAsJsonObject().get("jobId").getAsString();
                helper.assertTrue(JobId.values()[i].id().equals(actual),
                        "jobs 顺序必须等于 JobId.values(), 第 " + i + " 条期望 "
                                + JobId.values()[i].id() + " 实得 " + actual);
            }

            long span5 = JobXpCurve.cumulativeXpForLevel(6) - JobXpCurve.cumulativeXpForLevel(5);
            JsonObject miner = jobEntry(jobs, JobId.MINER);
            helper.assertTrue(miner.get("level").getAsInt() == 5, "矿工应为 5 级");
            helper.assertTrue(miner.get("totalXp").getAsLong() == JobXpCurve.cumulativeXpForLevel(5) + 500L,
                    "totalXp 是累计有效经验");
            helper.assertTrue(miner.get("levelXp").getAsLong() == 500L,
                    "levelXp 是本级已获 (500), 实得 " + miner.get("levelXp").getAsLong());
            helper.assertTrue(miner.get("nextLevelXp").getAsLong() == span5,
                    "nextLevelXp 是本级跨度 " + span5 + " 而不是'还差多少', 实得 "
                            + miner.get("nextLevelXp").getAsLong());
            helper.assertTrue(miner.get("dailyXp").getAsLong() == 500L,
                    "dailyXp 是当日已入账有效经验");

            JsonObject brewer = jobEntry(jobs, JobId.BREWER);
            helper.assertTrue(brewer.get("level").getAsInt() == JobXpCurve.MAX_LEVEL, "酿酒师应为满级");
            // 满级两栏同时发 0, 前端据 nextLevelXp===0 判满级并改画结论, 不画 0/0 的 NaN 宽度空槽。
            helper.assertTrue(brewer.get("nextLevelXp").getAsLong() == 0L,
                    "满级 nextLevelXp 必须发 0");
            helper.assertTrue(brewer.get("levelXp").getAsLong() == 0L,
                    "满级 levelXp 必须同时发 0 (否则前端画出 x/0 的进度条)");
            helper.assertTrue(brewer.get("totalXp").getAsLong() == JobXpCurve.GRADUATION_XP,
                    "满级 totalXp 仍是真实累计经验, 不被清零");

            // 未动过的职业是干净的 1 级默认态, 不能被上面两个职业的写入串到。
            JsonObject chef = jobEntry(jobs, JobId.CHEF);
            helper.assertTrue(chef.get("level").getAsInt() == 1 && chef.get("totalXp").getAsLong() == 0L,
                    "没练过的职业是 1 级 0 经验");
            helper.assertTrue(chef.get("nextLevelXp").getAsLong() == JobXpCurve.cumulativeXpForLevel(2),
                    "1 级的本级跨度 = 达到 2 级所需累计经验");

            helper.succeed();
        } finally {
            restoreEconomy(prev);
        }
    }

    /**
     * 昨天吃满额度、今天没开工时, 首屏必须显示"今天一点没用", 而不是昨天的余量。
     *
     * 为什么值得单独一条: dailyXp 无日戳的那个重载直接读字段, 而清零只发生在该职业当天首次入账时。没有这条
     * 断言, 玩家昨天吃满衰减额度后今天开平板会看到进度条画满 + "额度已用尽", 同一份回执里的今日产出却是 0
     * (那一栏走账本 peek, 正确翻日) —— 两句话互相打脸, 且下一铲子照样按第 0 档全额结算。
     */
    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void profileDailyQuotaRollsOverAcrossUtcDay(GameTestHelper helper) {
        SqliteEconomyLedger ledger = SqliteEconomyLedger.openInMemory();
        IEconomyService prev = swapEconomy(new EconomyService(ledger, new AbuseGuard(), newStateResolver()));
        try {
            ServerPlayer player = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);
            IMiningPlayerData data = MiningCapabilities.get(player).orElseThrow(
                    () -> new IllegalStateException("mock 玩家没有挂上矿山玩家数据 capability"));

            long today = EconomyServices.economyService().currentDayStamp();
            long yesterday = today - 1L;
            long softCap = JobXpPolicies.dailySoftCap(JobId.MINER);
            // 昨天吃满: 拿一个远超软上限的原始经验入账, 衰减后 dailyXp 必定顶到 softCap。
            data.jobProgress(JobId.MINER).grantXp(JobId.MINER, softCap * 4L, yesterday);
            helper.assertTrue(data.jobProgress(JobId.MINER).dailyXp(JobId.MINER) > 0L,
                    "前置条件不成立: 昨天那笔没记进 dailyXp, 本条测不到翻日");

            JsonObject profile = handle(PlayerWebUiActions.PROFILE, player);
            JsonObject miner = jobEntry(profile.getAsJsonArray("jobs"), JobId.MINER);

            helper.assertTrue(miner.get("dailyXp").getAsLong() == 0L,
                    "跨 UTC 日后 dailyXp 必须回 0, 实得 " + miner.get("dailyXp").getAsLong());
            helper.assertTrue(miner.get("dailyRemaining").getAsLong() == softCap,
                    "跨 UTC 日后额度必须是满的 " + softCap + ", 实得 " + miner.get("dailyRemaining").getAsLong());

            // 只读不翻日: 查询顺手清零等于把衰减档位洗回第 0 档, 那是印钞。落盘的昨日值必须原样还在。
            helper.assertTrue(data.jobProgress(JobId.MINER).dayStamp() == yesterday,
                    "profile 是只读查询, 不许把 dayStamp 改成今天");
            helper.assertTrue(data.jobProgress(JobId.MINER).dailyXp(JobId.MINER) > 0L,
                    "profile 是只读查询, 不许清掉落盘的 dailyXp");

            helper.succeed();
        } finally {
            restoreEconomy(prev);
        }
    }

    // ============================================================
    // 7. player.prefs.get / set
    // ============================================================

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void prefsSetPersistsToCapabilityAndGetReadsItBack(GameTestHelper helper) {
        ServerPlayer player = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);
        IMiningPlayerData data = MiningCapabilities.get(player).orElseThrow(
                () -> new IllegalStateException("mock 玩家没有挂上矿山玩家数据 capability"));

        // 四项全部与默认值不同: 任何一项被实现写死成默认值都会被本条抓出来。
        helper.assertTrue(UiPrefs.DEFAULT.equals(data.uiPrefs()), "前提校验: 新玩家是整份 DEFAULT");
        JsonObject payload = prefsPayload(true, "en_us", UiPrefs.THEME_LIGHT, 12);

        JsonObject echo = handle(PlayerWebUiActions.PREFS_SET, player, payload);
        assertPrefs(helper, echo, true, "en_us", UiPrefs.THEME_LIGHT, 12, "prefs.set 回执");

        UiPrefs stored = data.uiPrefs();
        helper.assertTrue(new UiPrefs(true, "en_us", UiPrefs.THEME_LIGHT, 12).equals(stored),
                "prefs.set 必须真的写进 capability (换机器不丢的唯一理由), 实得 " + stored);

        assertPrefs(helper, handle(PlayerWebUiActions.PREFS_GET, player),
                true, "en_us", UiPrefs.THEME_LIGHT, 12, "prefs.get 回执");
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void prefsSetRejectsIllegalValuesWithFieldParams(GameTestHelper helper) {
        ServerPlayer player = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);
        IMiningPlayerData data = MiningCapabilities.get(player).orElseThrow(
                () -> new IllegalStateException("mock 玩家没有挂上矿山玩家数据 capability"));

        WebUiBusinessException hue = rejection(helper, PlayerWebUiActions.PREFS_SET, player,
                prefsPayload(false, UiPrefs.DEFAULT_LANGUAGE, UiPrefs.THEME_DARK, UiPrefs.BRAND_HUE_MAX + 1));
        helper.assertTrue(WebUiErrorCodes.INVALID_REQUEST.equals(hue.errorCode()),
                "越界 brandHue 应回 INVALID_REQUEST, 实得 " + hue.errorCode());
        helper.assertTrue("brandHue".equals(hue.params().get("field"))
                        && Integer.toString(UiPrefs.BRAND_HUE_MAX + 1).equals(hue.params().get("value")),
                "取值域外的拒绝必须带 field 与 value 两个实参, 实得 " + hue.params());

        WebUiBusinessException theme = rejection(helper, PlayerWebUiActions.PREFS_SET, player,
                prefsPayload(false, UiPrefs.DEFAULT_LANGUAGE, "compact", UiPrefs.DEFAULT_BRAND_HUE));
        helper.assertTrue("theme".equals(theme.params().get("field"))
                        && "compact".equals(theme.params().get("value")),
                "像素风时代的 theme='compact' 必须被拒并指名字段, 实得 " + theme.params());

        JsonObject missingLanguage = prefsPayload(false, UiPrefs.DEFAULT_LANGUAGE,
                UiPrefs.THEME_DARK, UiPrefs.DEFAULT_BRAND_HUE);
        missingLanguage.remove("language");
        WebUiBusinessException missing =
                rejection(helper, PlayerWebUiActions.PREFS_SET, player, missingLanguage);
        helper.assertTrue("language".equals(missing.params().get("field"))
                        && !missing.params().containsKey("value"),
                "缺字段时只回 field (没有值可回), 实得 " + missing.params());

        JsonObject wrongType = prefsPayload(false, UiPrefs.DEFAULT_LANGUAGE,
                UiPrefs.THEME_DARK, UiPrefs.DEFAULT_BRAND_HUE);
        wrongType.addProperty("brandHue", "250");
        WebUiBusinessException typed = rejection(helper, PlayerWebUiActions.PREFS_SET, player, wrongType);
        helper.assertTrue("brandHue".equals(typed.params().get("field")),
                "类型不符也必须指名字段, 实得 " + typed.params());

        // 写入侧一律拒绝而不是静默钳制: 钳一下就等于让契约声明的取值域失效, 前端永远不知道自己发错了。
        helper.assertTrue(UiPrefs.DEFAULT.equals(data.uiPrefs()),
                "四次被拒之后 capability 必须一字未动, 实得 " + data.uiPrefs());
        helper.succeed();
    }

    // ============================================================
    // 8. player.itemDetail: 普通物品的基础字段与变体字段
    // ============================================================

    /**
     * 大类判定之外的那一半契约: 基础四字段与两个变体字段必须与 player.inventory 逐字同源, 且两个变体字段是
     * <b>可选</b>的 —— 绝大多数物品两个都不发, 回执形状与挂单选物页逐字节相同。
     */
    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void itemDetailKeepsBaseFieldsAndEmptyDetailForPlainStacks(GameTestHelper helper) {
        ServerPlayer player = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);
        player.getInventory().clearContent();

        ItemStack renamed = new ItemStack(Items.STONE, 7);
        renamed.setHoverName(Component.literal("留着卖的石头"));
        player.getInventory().setItem(3, renamed);
        player.getInventory().setItem(4, new ItemStack(Items.STONE));

        JsonObject named = handle(PlayerWebUiActions.ITEM_DETAIL, player, slotPayload(3));
        helper.assertTrue(named.get("slot").getAsInt() == 3,
                "回执必须回显入参 slot (前端靠它把详情对回那一格)");
        helper.assertTrue("minecraft:stone".equals(named.get("itemId").getAsString()),
                "itemId 是注册名, 实得 " + named.get("itemId").getAsString());
        // 物品 id 与翻译键刻意是两个字段: 方块物品的键是 block.* 而不是 item.*, 前端推不出来, 必须服务端给。
        helper.assertTrue("block.minecraft.stone".equals(named.get("descriptionId").getAsString()),
                "descriptionId 是翻译键而不是 itemId, 实得 " + named.get("descriptionId").getAsString());
        helper.assertTrue(named.get("count").getAsInt() == 7,
                "count 是该格真实堆叠数, 实得 " + named.get("count").getAsInt());
        helper.assertTrue("留着卖的石头".equals(named.get("displayName").getAsString()),
                "铁砧改过名的物品必须发 displayName, 实得 " + named.get("displayName"));
        helper.assertTrue("plain".equals(named.get("kind").getAsString()),
                "石头不属于任何子系统, 大类是 plain");
        helper.assertTrue(named.getAsJsonArray("attributes").isEmpty()
                        && named.getAsJsonArray("tags").isEmpty(),
                "无数值行无标签时发空数组而不是缺席键 (前端少一条 undefined 分支)");
        // 改过名之后名字不再是 Item 级默认键, 故变体字段必须把它拍平发出来 (与 player.inventory 同一路径)。
        JsonArray nameParts = named.getAsJsonArray("nameParts");
        helper.assertTrue(nameParts.size() == 1
                        && "留着卖的石头".equals(nameParts.get(0).getAsJsonObject().get("t").getAsString()),
                "改名物品的 nameParts 是一条字面量项, 实得 " + nameParts);

        JsonObject plain = handle(PlayerWebUiActions.ITEM_DETAIL, player, slotPayload(4));
        helper.assertTrue(!plain.has("displayName"),
                "没改过名的物品不得发 displayName (缺席键而不是空串)");
        helper.assertTrue(!plain.has("nameParts") && !plain.has("customModelData"),
                "默认名且无 CustomModelData 时两个变体字段整键缺席, 实得 " + plain);
        helper.assertTrue(plain.get("count").getAsInt() == 1, "单个物品的 count 是 1");
        helper.succeed();
    }

    // ============================================================
    // 9. player.itemDetail: v1 老枪读得出来且不少发行 (对抗复核 M3)
    // ============================================================

    /**
     * v1 时期装配的枪是<b>正常游玩产物</b>: 它早于 version 字段, stats 里没有 v3/v5 才加的三条缓存, 零件里没有
     * v3 才加的 variant。详情页对它既不许整件降级, 也不许"按 version 少发几行" —— 前端的行表是固定的, 行时有
     * 时无等于详情面板的布局随存档年代跳变。
     *
     * 本条同时锁住实现里那句"from() 返回非 null 之后全部 getter 安全"的论断: 若有人把某行改成无条件读缓存键
     * (例如 value("fireRate")), 这把枪会在取值阶段抛, 降级成 plain, 本条即挂。
     */
    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void itemDetailReadsLegacyVersionOneGunWithoutDegrading(GameTestHelper helper) {
        ServerPlayer player = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);
        player.getInventory().clearContent();
        ItemStack legacy = downgradeToVersionOne(assembledM4Gun());
        player.getInventory().setItem(0, legacy);

        GunsmithGunStats expected = GunsmithGunStats.from(legacy);
        helper.assertTrue(expected != null,
                "前提校验: 版本键缺席是 v1 的正常形态, 硬校验路径本就该读得出来");
        helper.assertTrue(Double.compare(expected.fireRateMultiplier(), 1.0D) == 0,
                "前提校验: v1 没有 fireRate 缓存, 走的是恒 1.0 的派生分支");

        JsonObject detail = handle(PlayerWebUiActions.ITEM_DETAIL, player, slotPayload(0));
        helper.assertTrue("gun".equals(detail.get("kind").getAsString()),
                "v1 老枪仍是 gun, 实得 " + detail.get("kind").getAsString());
        helper.assertTrue(!hasTag(detail, "data.unreadable:gun"),
                "读得出来的 v1 老枪不得被判成脏数据");
        for (String key : GUN_STAT_KEYS) {
            helper.assertTrue(attribute(detail, key) != null,
                    "v1 老枪缺了数值行 " + key + " (行表不按 version 分档), 实得 "
                            + detail.getAsJsonArray("attributes"));
        }
        helper.assertTrue(Double.compare(
                        requiredAttribute(helper, detail, "fireRate").get("value").getAsDouble(), 0.0D) == 0,
                "v1 的射速增减量恒 0 (1.0 - 1.0), 实得 "
                        + requiredAttribute(helper, detail, "fireRate").get("value").getAsDouble());
        helper.assertTrue(Double.compare(
                        requiredAttribute(helper, detail, "verticalRecoil").get("value").getAsDouble(),
                        expected.recoilChange()) == 0,
                "verticalRecoil 仍逐位等于 recoilChange() (v1 走 1/后座系数 的派生分支)");
        helper.assertTrue(Double.compare(
                        requiredAttribute(helper, detail, "inaccuracy").get("value").getAsDouble(),
                        expected.spreadChange()) == 0,
                "inaccuracy 仍逐位等于 spreadChange()");
        helper.assertTrue(Double.compare(
                        requiredAttribute(helper, detail, "damage").get("value").getAsDouble(),
                        expected.damage() - 1.0D) == 0,
                "v1 的伤害读的是缓存值本身, 换算仍是 (系数 - 1.0)");
        helper.assertTrue(requiredAttribute(helper, detail, "partCount").get("value").getAsInt()
                        == expected.parts().size(),
                "partCount 等于枪上真实零件数 " + expected.parts().size());
        helper.succeed();
    }

    // ============================================================
    // 10. player.itemDetail: 酒与纳米
    // ============================================================

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void itemDetailDescribesWineVintageAndSpoilage(GameTestHelper helper) {
        ServerPlayer player = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);
        player.getInventory().clearContent();
        ItemStack wine = new ItemStack(BrewerItems.itemFor(WineType.WHISKEY));
        WineNbt.stamp(wine, WineQuality.HIGH, player.getUUID());
        WineNbt.setVintage(wine, 4.0D);
        player.getInventory().setItem(0, wine);

        JsonObject detail = handle(PlayerWebUiActions.ITEM_DETAIL, player, slotPayload(0));
        helper.assertTrue("wine".equals(detail.get("kind").getAsString()),
                "盖过酒章的物品大类是 wine, 实得 " + detail.get("kind").getAsString());
        helper.assertTrue(Double.compare(
                        requiredAttribute(helper, detail, "vintage").get("value").getAsDouble(), 4.0D) == 0,
                "年份原样下发 4.0");
        // 强度 = 年份 x 品质系数 (HIGH=2.0)。发结算口径而不是发原料, 前端不许自己再乘一遍。
        helper.assertTrue(Double.compare(
                        requiredAttribute(helper, detail, "strength").get("value").getAsDouble(), 8.0D) == 0,
                "强度必须是 4.0 x 2.0 = 8.0, 实得 "
                        + requiredAttribute(helper, detail, "strength").get("value").getAsDouble());
        helper.assertTrue(hasTag(detail, "wine.quality:" + WineQuality.HIGH.id()),
                "标签必须带品质稳定 id");
        helper.assertTrue(!hasTag(detail, "wine.spoiled"), "没变质的酒不得带变质标记");

        WineNbt.setSpoiled(wine, true);
        JsonObject spoiled = handle(PlayerWebUiActions.ITEM_DETAIL, player, slotPayload(0));
        helper.assertTrue(hasTag(spoiled, "wine.spoiled"), "变质酒必须带 wine.spoiled");
        helper.assertTrue(Double.compare(
                        requiredAttribute(helper, spoiled, "strength").get("value").getAsDouble(), 0.0D) == 0,
                "变质酒强度恒 0 (与喝酒结算同一口径, 详情页不许另判)");
        helper.assertTrue(Double.compare(
                        requiredAttribute(helper, spoiled, "vintage").get("value").getAsDouble(), 4.0D) == 0,
                "变质不抹掉年份: 年份还在, 只是不再折算出强度");
        helper.succeed();
    }

    /**
     * 年份是裸的 {@code getDouble}, 手改存档 / {@code /data modify} 能往里写进 NaN 或 Infinity。Gson 序列化
     * JsonPrimitive 时内部 setLenient(true), <b>不抛</b>, 而是原样吐出 {@code NaN} / {@code Infinity} 字面量 ——
     * 那串东西回到前端 {@code JSON.parse} 直接失败, 症状是"某一格点开就报一句解析错误", 与真正的病因隔得极远。
     *
     * 故断言的不是"没崩", 而是 (1) 回执文本里没有那两个字面量 (2) 整条回执经得起<b>严格</b> JSON 解析
     * (Gson 的 JsonParser 自身是宽松的, 拿它去验等于没验) (3) 走的是本类既有的降级纪律而不是别的兜底。
     */
    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void itemDetailDegradesWineWithNonFiniteVintage(GameTestHelper helper) {
        ServerPlayer player = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);
        player.getInventory().clearContent();

        ItemStack notANumber = new ItemStack(BrewerItems.itemFor(WineType.WHISKEY));
        WineNbt.stamp(notANumber, WineQuality.HIGH, player.getUUID());
        WineNbt.setVintage(notANumber, Double.NaN);
        player.getInventory().setItem(0, notANumber);

        ItemStack unbounded = new ItemStack(BrewerItems.itemFor(WineType.VODKA));
        WineNbt.stamp(unbounded, WineQuality.LOW, player.getUUID());
        WineNbt.setVintage(unbounded, Double.POSITIVE_INFINITY);
        player.getInventory().setItem(1, unbounded);

        // 前提校验: 两个脏值真的落进了酒章 (setVintage 的 Math.max 对 NaN/Inf 不过滤), 否则下面测的是别的东西。
        helper.assertTrue(Double.isNaN(WineNbt.readVintage(notANumber)),
                "前提校验: NaN 年份必须真的写进酒章, 实得 " + WineNbt.readVintage(notANumber));
        helper.assertTrue(Double.isInfinite(WineNbt.readVintage(unbounded)),
                "前提校验: 无穷年份必须真的写进酒章, 实得 " + WineNbt.readVintage(unbounded));

        assertNonFiniteWineDegrades(helper, player, 0, "NaN");
        assertNonFiniteWineDegrades(helper, player, 1, "Infinity");
        helper.succeed();
    }

    private static void assertNonFiniteWineDegrades(GameTestHelper helper, ServerPlayer player,
                                                    int slot, String literal) {
        String raw = PlayerWebUiActions.ITEM_DETAIL.handle(player, slotPayload(slot));
        helper.assertTrue(!raw.contains(literal),
                "槽 " + slot + " 的回执里出现了 " + literal + " 字面量, 前端 JSON.parse 会直接失败: " + raw);
        assertStrictlyParsable(helper, raw, "槽 " + slot + " 的 itemDetail 回执");

        JsonObject detail = JsonParser.parseString(raw).getAsJsonObject();
        helper.assertTrue("plain".equals(detail.get("kind").getAsString()),
                "槽 " + slot + " 的年份非有限, 必须降级成 plain, 实得 " + detail.get("kind").getAsString());
        helper.assertTrue(hasTag(detail, "data.unreadable:wine"),
                "降级不是静默: 槽 " + slot + " 必须带 data.unreadable:wine, 实得 " + detail.getAsJsonArray("tags"));
        // 先取值验完再写: 半行都不许漏进去, 否则前端会拿到一条只有 vintage 没有 strength 的残缺酒。
        helper.assertTrue(detail.getAsJsonArray("attributes").isEmpty(),
                "槽 " + slot + " 降级后不得留下任何数值行, 实得 " + detail.getAsJsonArray("attributes"));
    }

    /**
     * 品质 id 被后续版本改名 / 删掉的老酒: 酒章根标签俱全, 但 {@code WineQuality.fromId} 认不出来。
     *
     * {@code WineNbt.isWine} 的判据正是"品质解得出来", 只靠它这瓶酒会悄无声息落进 plain 且不带任何标记, 玩家
     * 只会以为它本来就没数据 —— 而枪 / 零件 / 塔罗三支都老老实实打了 {@code data.unreadable}。本条同时锁住反向:
     * 真的没盖过酒章的同款物品不许被这条分支误标, 否则"读不出来"这个信号会被稀释成噪音。
     */
    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void itemDetailMarksWineWithUnknownQualityIdAsUnreadable(GameTestHelper helper) {
        ServerPlayer player = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);
        player.getInventory().clearContent();

        ItemStack legacy = new ItemStack(BrewerItems.itemFor(WineType.WHISKEY));
        WineNbt.stamp(legacy, WineQuality.SUPERB, player.getUUID());
        WineNbt.setVintage(legacy, 3.0D);
        renameWineQualityId(legacy, WineQuality.SUPERB, "superb_renamed_next_version");
        player.getInventory().setItem(0, legacy);

        // 前提校验: 两个探针必须一真一假, 这才是"有酒章但解不出品质"那一格, 否则测的是别的场景。
        helper.assertTrue(WineNbt.hasWineStamp(legacy),
                "前提校验: 改完品质 id 之后酒章根标签必须还在");
        helper.assertTrue(!WineNbt.isWine(legacy),
                "前提校验: 未知品质 id 必须让 isWine 判否 (它的判据就是品质解得出来)");

        JsonObject detail = handle(PlayerWebUiActions.ITEM_DETAIL, player, slotPayload(0));
        helper.assertTrue("plain".equals(detail.get("kind").getAsString()),
                "品质解不出来就没有可发的数值行, 大类落回 plain, 实得 " + detail.get("kind").getAsString());
        helper.assertTrue(hasTag(detail, "data.unreadable:wine"),
                "必须显式标注是这瓶酒读不出来, 而不是静默当普通物品, 实得 " + detail.getAsJsonArray("tags"));
        helper.assertTrue(detail.getAsJsonArray("attributes").isEmpty(),
                "品质解不出来就没有强度可算, 不许发数值行, 实得 " + detail.getAsJsonArray("attributes"));

        // 反向: 没盖过酒章的同款空瓶只是普通物品, 不得被同一条分支标成"酒读不出来"。
        player.getInventory().setItem(1, new ItemStack(BrewerItems.itemFor(WineType.WHISKEY)));
        JsonObject unstamped = handle(PlayerWebUiActions.ITEM_DETAIL, player, slotPayload(1));
        helper.assertTrue("plain".equals(unstamped.get("kind").getAsString())
                        && unstamped.getAsJsonArray("tags").isEmpty(),
                "没盖过酒章的同款物品是干净的 plain, 实得 " + unstamped);

        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void itemDetailDescribesNanoArmorAndPlate(GameTestHelper helper) {
        ServerPlayer player = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);
        player.getInventory().clearContent();

        ItemStack armor = new ItemStack(Items.NETHERITE_CHESTPLATE);
        NanoNbt.writeEffects(armor, EnumSet.of(NanoEffect.SHIELD, NanoEffect.VITALITY));
        // 三个 tick 值刻意互不相同: 任何一行读串了另一行都会被抓出来。
        NanoNbt.setShieldCharges(armor, 3);
        NanoNbt.setShieldRegenTick(armor, 420);
        NanoNbt.setShieldWindowTick(armor, 7);
        player.getInventory().setItem(0, armor);

        JsonObject detail = handle(PlayerWebUiActions.ITEM_DETAIL, player, slotPayload(0));
        helper.assertTrue("nano".equals(detail.get("kind").getAsString()),
                "带纳米特效的护甲大类是 nano, 实得 " + detail.get("kind").getAsString());
        helper.assertTrue(requiredAttribute(helper, detail, "shieldCharges").get("value").getAsInt() == 3,
                "护盾剩余次数原样下发 3");
        helper.assertTrue(requiredAttribute(helper, detail, "shieldRegenTick").get("value").getAsInt() == 420,
                "再生倒计时下发的是 tick 原值, 服务端不折算成秒 (折算口径归前端)");
        helper.assertTrue(requiredAttribute(helper, detail, "shieldWindowTick").get("value").getAsInt() == 7,
                "免疫窗剩余下发的是 tick 原值");
        helper.assertTrue("flat".equals(
                        requiredAttribute(helper, detail, "shieldRegenTick").get("unit").getAsString()),
                "tick 是绝对值, 不是相对基准的百分比增减");
        helper.assertTrue(hasTag(detail, "nano.effect:" + NanoEffect.SHIELD.id())
                        && hasTag(detail, "nano.effect:" + NanoEffect.VITALITY.id()),
                "掷出的每个特效各发一条标签");
        helper.assertTrue(!hasTag(detail, "nano.effect:" + NanoEffect.TOTEM.id()),
                "没掷出来的特效不得凭空出现");
        helper.assertTrue(!hasTag(detail, "nano.xpPending"),
                "受修护甲不是护甲板, 不带生产经验待结算位");

        // 护甲板: 一条特效都没有, 靠生产者盖章进 nano 大类 (漏掉 producer 那一半判据的话它会被判成 plain)。
        ItemStack plate = new ItemStack(ModEngineerItems.plate(NanoTier.HIGH).get());
        NanoNbt.stampProducer(plate, player.getUUID(), true, 4);
        player.getInventory().setItem(1, plate);

        JsonObject plateDetail = handle(PlayerWebUiActions.ITEM_DETAIL, player, slotPayload(1));
        helper.assertTrue("nano".equals(plateDetail.get("kind").getAsString()),
                "盖了生产者章的护甲板大类是 nano, 实得 " + plateDetail.get("kind").getAsString());
        helper.assertTrue(requiredAttribute(helper, plateDetail, "qualityHits").get("value").getAsInt() == 4,
                "品质命中数是三条品质杠杆的还原依据, 必须原样下发 4");
        helper.assertTrue(hasTag(plateDetail, "nano.xpPending"),
                "生产经验待结算的板必须带 nano.xpPending");
        helper.assertTrue(requiredAttribute(helper, plateDetail, "shieldCharges").get("value").getAsInt() == 0,
                "板上没有护盾状态, 该行发 0 而不是缺行 (行表按 kind 固定, 不随单件状态伸缩)");
        helper.succeed();
    }

    // ============================================================
    // 11. 未写过偏好的账号 + action 注册名
    // ============================================================

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void prefsGetReturnsAccountDefaultsBeforeAnyWrite(GameTestHelper helper) {
        ServerPlayer player = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);
        // 从未写过偏好的账号等价于本功能上线前的老存档 (capability 里没有 uiPrefs 子标签)。
        assertPrefs(helper, handle(PlayerWebUiActions.PREFS_GET, player),
                false, UiPrefs.DEFAULT_LANGUAGE, UiPrefs.THEME_DARK, UiPrefs.DEFAULT_BRAND_HUE,
                "未写过偏好的 prefs.get 回执");
        // 默认档必须与前端首帧默认值同值, 否则远端偏好到达前后会闪一次主题/一次强调色。
        helper.assertTrue(UiPrefs.DEFAULT_BRAND_HUE == 250,
                "默认色相钉死 250 (= lib/brand.ts DEFAULT_BRAND.hue 与 index.css 的 --brand-h)");
        helper.assertTrue("dark".equals(UiPrefs.THEME_DARK) && "zh_cn".equals(UiPrefs.DEFAULT_LANGUAGE),
                "默认主题 dark、默认语言 zh_cn (= lib/theme.ts readStored 的默认档与当前唯一可用语言)");
        helper.succeed();
    }

    /**
     * 契约面是 action 名而不是 Java 常量: 其余用例全都直接调 {@code PlayerWebUiActions.XXX} 常量, 注册表里的
     * 名字打错一个字母也照样全绿, 而前端发的是名字。
     */
    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void playerActionsAreRegisteredUnderContractNames(GameTestHelper helper) {
        ensurePlayerActionsRegistered();
        for (String name : PLAYER_ACTION_NAMES) {
            helper.assertTrue(WebUiServerDispatcher.resolve(name) != null,
                    name + " 必须由 PlayerWebUiActions.registerAll 注册进派发器");
        }
        helper.assertTrue(WebUiServerDispatcher.resolve("player.itemdetail") == null,
                "action 名大小写敏感, 不得另注册一个全小写别名");
        helper.assertTrue(WebUiServerDispatcher.resolve("player.prefs") == null,
                "偏好是 get/set 两条独立 action, 不存在合并的 player.prefs");
        helper.succeed();
    }

    // ============================================================
    // 工具
    // ============================================================

    /**
     * 幂等注册 (范式同 {@code WebUiServerGameTests.ensureEchoRegistered}): 派发器的注册表是<b>进程级静态</b>,
     * register 用 putIfAbsent 守卫, 重复注册直接抛。故已注册就什么都不做。
     */
    private static void ensurePlayerActionsRegistered() {
        if (WebUiServerDispatcher.resolve("player.isOp") == null) {
            PlayerWebUiActions.registerAll();
        }
    }

    /**
     * 把一把当前版本的枪改写成 v1 时期的存档形态: 版本键缺席 (v1 早于该字段)、stats 里没有 v3/v5 才加的三条
     * 缓存、零件里没有 v3 才加的 variant。这是老存档里真实存在的形状, 不是随手造的畸形数据。
     */
    private static ItemStack downgradeToVersionOne(ItemStack gun) {
        CompoundTag root = gun.getOrCreateTag().getCompound(GunsmithGunStats.ROOT_KEY);
        root.remove(GunsmithGunStats.VERSION_KEY);
        CompoundTag stats = root.getCompound(GunsmithGunStats.STATS_KEY);
        stats.remove("fireRate");
        stats.remove("verticalRecoil");
        stats.remove("inaccuracy");
        CompoundTag parts = root.getCompound(GunsmithGunStats.PARTS_KEY);
        for (String partId : parts.getAllKeys()) {
            parts.getCompound(partId).remove("variant");
        }
        return gun;
    }

    private static JsonObject handle(WebUiAction action, ServerPlayer sender) {
        return handle(action, sender, new JsonObject());
    }

    private static JsonObject handle(WebUiAction action, ServerPlayer sender, JsonObject payload) {
        return JsonParser.parseString(action.handle(sender, payload)).getAsJsonObject();
    }

    /** 调 action 并要求它抛业务拒绝; 没抛就地判失败 (返回值必非 null, 调用方可直接取字段)。 */
    private static WebUiBusinessException rejection(GameTestHelper helper, WebUiAction action,
                                                    ServerPlayer sender, JsonObject payload) {
        try {
            action.handle(sender, payload);
        } catch (WebUiBusinessException rejected) {
            return rejected;
        }
        helper.fail("该请求本应被业务拒绝, 实际却成功返回了: " + payload);
        throw new IllegalStateException("unreachable: helper.fail already threw");
    }

    private static JsonObject slotPayload(int slot) {
        JsonObject payload = new JsonObject();
        payload.addProperty("slot", slot);
        return payload;
    }

    private static JsonObject prefsPayload(boolean muteToasts, String language, String theme, int brandHue) {
        JsonObject payload = new JsonObject();
        payload.addProperty("muteToasts", muteToasts);
        payload.addProperty("language", language);
        payload.addProperty("theme", theme);
        payload.addProperty("brandHue", brandHue);
        return payload;
    }

    private static void assertPrefs(GameTestHelper helper, JsonObject actual, boolean muteToasts,
                                    String language, String theme, int brandHue, String what) {
        helper.assertTrue(actual.get("muteToasts").getAsBoolean() == muteToasts,
                what + " 的 muteToasts 应为 " + muteToasts);
        helper.assertTrue(language.equals(actual.get("language").getAsString()),
                what + " 的 language 应为 " + language);
        helper.assertTrue(theme.equals(actual.get("theme").getAsString()),
                what + " 的 theme 应为 " + theme);
        helper.assertTrue(actual.get("brandHue").getAsInt() == brandHue,
                what + " 的 brandHue 应为 " + brandHue);
    }

    private static JsonObject attribute(JsonObject detail, String key) {
        for (JsonElement element : detail.getAsJsonArray("attributes")) {
            JsonObject row = element.getAsJsonObject();
            if (key.equals(row.get("key").getAsString())) {
                return row;
            }
        }
        return null;
    }

    /** 取一行数值; 缺行就地判失败并打印全部行 (返回值必非 null, 调用方可直接取字段)。 */
    private static JsonObject requiredAttribute(GameTestHelper helper, JsonObject detail, String key) {
        JsonObject row = attribute(detail, key);
        if (row == null) {
            helper.fail("回执缺少数值行 " + key + ", 实得 " + detail.getAsJsonArray("attributes"));
            throw new IllegalStateException("unreachable: helper.fail already threw");
        }
        return row;
    }

    /**
     * 把酒章里的品质 id 改成 {@code WineQuality} 认不出来的值 (等价于跨版本改名 / 删档的老酒)。
     *
     * 刻意不硬编码 {@code WineNbt} 的根标签名与品质键名 —— 它们是该类的包内私有常量, 测试照抄一份就等于立了
     * 第二份真源, 改名之后测试还会绿。故按"stamp 刚写进去的那个品质 id 字符串"反查落点: 酒章里只有品质一项
     * 是字符串, 年份是 double、酿造者是 UUID。
     */
    private static void renameWineQualityId(ItemStack wine, WineQuality stamped, String unknownId) {
        CompoundTag tag = wine.getTag();
        if (tag == null) {
            throw new IllegalStateException("stamp 之后酒必须带 NBT, 无法构造未知品质的老酒");
        }
        for (String rootKey : tag.getAllKeys()) {
            if (!tag.contains(rootKey, Tag.TAG_COMPOUND)) {
                continue;
            }
            CompoundTag root = tag.getCompound(rootKey);
            for (String key : root.getAllKeys()) {
                if (root.contains(key, Tag.TAG_STRING) && stamped.id().equals(root.getString(key))) {
                    root.putString(key, unknownId);
                    return;
                }
            }
        }
        throw new IllegalStateException("酒章里找不到品质 id " + stamped.id() + ", 无法构造未知品质的老酒");
    }

    /**
     * 按<b>严格</b> JSON 校验整条回执, 不合法就地判失败。
     *
     * 不能拿 {@code JsonParser.parseString} 当判据: 它内部强制 lenient, 会照单全收 {@code NaN} /
     * {@code Infinity} 这类前端 {@code JSON.parse} 一定拒掉的字面量, 用它验等于没验。
     */
    private static void assertStrictlyParsable(GameTestHelper helper, String resultJson, String what) {
        JsonReader strict = new JsonReader(new StringReader(resultJson));
        strict.setLenient(false);
        try {
            strict.skipValue();
        } catch (IOException malformed) {
            helper.fail(what + " 不是严格合法 JSON (前端 JSON.parse 会失败): " + malformed.getMessage()
                    + ", 原文 " + resultJson);
        }
    }

    private static boolean hasTag(JsonObject detail, String tag) {
        for (JsonElement element : detail.getAsJsonArray("tags")) {
            if (tag.equals(element.getAsString())) {
                return true;
            }
        }
        return false;
    }

    private static JsonObject jobEntry(JsonArray jobs, JobId job) {
        for (JsonElement element : jobs) {
            JsonObject entry = element.getAsJsonObject();
            if (job.id().equals(entry.get("jobId").getAsString())) {
                return entry;
            }
        }
        throw new IllegalStateException("player.profile 缺职业条目: " + job.id());
    }

    /** 一把真实的 M4A1 (基座是铁锄, 不依赖 TaCZ 加载): 六个已知系数的零件走真实装配路径盖章。 */
    private static ItemStack assembledM4Gun() {
        EnumMap<GunsmithPressPart, ItemStack> parts = new EnumMap<>(GunsmithPressPart.class);
        parts.put(GunsmithPressPart.CORE, arPart(GunsmithPressPart.CORE, GunsmithPartQuality.COMMON, 1.04D));
        parts.put(GunsmithPressPart.BARREL, arPart(GunsmithPressPart.BARREL, GunsmithPartQuality.IMPROVED, 1.10D));
        parts.put(GunsmithPressPart.BOLT, arPart(GunsmithPressPart.BOLT, GunsmithPartQuality.MILSPEC, 1.20D));
        parts.put(GunsmithPressPart.HANDGUARD,
                arPart(GunsmithPressPart.HANDGUARD, GunsmithPartQuality.PRECISION, 1.30D));
        parts.put(GunsmithPressPart.GRIP, arPart(GunsmithPressPart.GRIP, GunsmithPartQuality.LEGENDARY, 1.40D));
        parts.put(GunsmithPressPart.STOCK, arPart(GunsmithPressPart.STOCK, GunsmithPartQuality.IMPROVED, 1.08D));
        return GunsmithAssemblyRecipe.assemble(
                new ItemStack(Items.IRON_HOE),
                GunsmithBlueprintItem.createStack(
                        ModMunitionsItems.GUNSMITH_BLUEPRINT.get(), GunsmithBlueprint.M4A1),
                parts);
    }

    private static ItemStack arPart(GunsmithPressPart part, GunsmithPartQuality quality, double coefficient) {
        return GunsmithPartItem.createStack(ModMunitionsItems.GUNSMITH_PART.get(),
                GunsmithPlatform.AR, part, quality, coefficient);
    }

    private static IEconomyService swapEconomy(IEconomyService fake) {
        IEconomyService prev = EconomyServices.isRegistered() ? EconomyServices.economyService() : null;
        EconomyServices.registerEconomyService(fake);
        return prev;
    }

    private static void restoreEconomy(IEconomyService prev) {
        if (prev != null) {
            EconomyServices.registerEconomyService(prev);
        } else {
            EconomyServices.reset();
        }
    }

    private static Function<UUID, PlayerAbuseState> newStateResolver() {
        Map<UUID, PlayerAbuseState> states = new HashMap<>();
        return id -> states.computeIfAbsent(id, k -> new PlayerAbuseState());
    }
}
