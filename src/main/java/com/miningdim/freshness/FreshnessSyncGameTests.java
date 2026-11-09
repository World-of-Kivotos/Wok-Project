package com.miningdim.freshness;

import com.miningdim.core.MiningConstants;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;


/**
 * 客户端显示那一片的**可测部分**: 同步包、两端共用的保质期读取口、显示算术与提醒频率。
 *
 * 画的部分 (像素、字体、装饰器注册) 只能在实机上看, 列入测试服清单; 但这一片真正的风险不在画, 而在三处:
 * <ol>
 *   <li><b>客户端读 SERVER 配置</b> —— 真去读会直接抛 (开发期) 或读到空值 (生产期): 因此保质期必须走
 *       {@link FreshnessShelfLives}, 且"同步值优先"这条要在用例里钉死;</li>
 *   <li><b>包里带的不是服务端此刻的真相</b> (钟或保质期任意一个), 客户端就会一直画错的数;</li>
 *   <li><b>提醒频率</b>: 每 tick 都提醒与每小时最多一次在体感上完全是两个东西。</li>
 * </ol>
 */
@GameTestHolder(MiningConstants.MODID)
@PrefixGameTestTemplate(false)
public final class FreshnessSyncGameTests {

    private static final String EMPTY = "empty";
    private static final String BATCH = "freshness_sync";

    // ============================================================
    // 1. 同步包的载荷与线格式
    // ============================================================

    /** 包里必须是**服务端此刻**的钟与保质期, 而不是某个常量或旧值。 */
    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void payloadCarriesTheServersClockAndShelfLives(GameTestHelper helper) {
        var level = helper.getLevel();
        long expectedNow = FreshnessClockData.nowMinute(level);
        FreshnessSyncS2C payload = FreshnessSync.payloadFor(level);
        helper.assertTrue(payload.nowMinute() == expectedNow,
                "包里的钟必须是服务端此刻的分钟序号, 实得 " + payload.nowMinute() + " 期望 " + expectedNow);
        int[] expected = FreshnessShelfLives.currentValues();
        helper.assertTrue(java.util.Arrays.equals(expected, payload.shelfLives()),
                "包里的保质期必须是当下配置里的六个值, 实得 " + java.util.Arrays.toString(payload.shelfLives()));
        helper.assertTrue(payload.modeOrDefault() == FreshnessConfig.MODE.get(),
                "包里的档位必须是当下配置的档位, 实得 " + payload.modeOrDefault());
        helper.succeed();
    }

    /** 线格式: 编码再解码要一模一样 (含六个 varint 与越界保质期的钳制)。 */
    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void packetRoundTripsThroughTheBuffer(GameTestHelper helper) {
        FreshnessSyncS2C original = new FreshnessSyncS2C(12_345_678L, 42L,
                FreshnessConfig.Mode.ON.ordinal(), new int[]{12, 36, 48, 48, 48, 168});
        FriendlyByteBuf buf = new FriendlyByteBuf(io.netty.buffer.Unpooled.buffer());
        FreshnessSyncS2C.encode(original, buf);
        FreshnessSyncS2C decoded = FreshnessSyncS2C.decode(buf);
        helper.assertTrue(decoded.nowMinute() == original.nowMinute()
                        && decoded.offsetHours() == original.offsetHours()
                        && decoded.modeOrDefault() == FreshnessConfig.Mode.ON,
                "钟/偏移/档位都要逐字还原, 实得 " + decoded);
        helper.assertTrue(java.util.Arrays.equals(original.shelfLives(), decoded.shelfLives()),
                "六类保质期要逐字还原, 实得 " + java.util.Arrays.toString(decoded.shelfLives()));

        // 越界值 (网络来的东西一律当不可信): 保质期钳到 1..8760, 档位越界按试运行算。
        FriendlyByteBuf hostile = new FriendlyByteBuf(io.netty.buffer.Unpooled.buffer());
        hostile.writeLong(1L);
        hostile.writeLong(0L);
        hostile.writeByte(99);
        hostile.writeByte(6);
        for (int i = 0; i < 6; i++) {
            hostile.writeVarInt(i == 0 ? -5 : 999_999);
        }
        FreshnessSyncS2C clamped = FreshnessSyncS2C.decode(hostile);
        helper.assertTrue(clamped.shelfLives()[0] == 1 && clamped.shelfLives()[5] == 8_760,
                "越界保质期必须钳到 1..8760, 实得 " + java.util.Arrays.toString(clamped.shelfLives()));
        helper.assertTrue(clamped.modeOrDefault() == FreshnessConfig.Mode.DRY_RUN,
                "档位越界按试运行算 (最保守的那一档), 实得 " + clamped.modeOrDefault());

        // 长度对不上 (版本不一致): 退回出厂默认值, 而不是拿半份数组去索引。
        FriendlyByteBuf shortPayload = new FriendlyByteBuf(io.netty.buffer.Unpooled.buffer());
        shortPayload.writeLong(5L);
        shortPayload.writeLong(0L);
        shortPayload.writeByte(1);
        shortPayload.writeByte(2);
        shortPayload.writeVarInt(10);
        shortPayload.writeVarInt(20);
        FreshnessSyncS2C mismatched = FreshnessSyncS2C.decode(shortPayload);
        helper.assertTrue(mismatched.shelfLives().length == FreshnessCategory.values().length,
                "长度对不上时要用足六个类别的默认值, 实得 " + mismatched.shelfLives().length);
        helper.succeed();
    }

    // ============================================================
    // 2. 保质期读取口: 同步值优先, 且服务端走配置
    // ============================================================

    /**
     * 客户端只认同步下来的保质期: 写进去之后, 全模块的"算剩余"必须立刻按新值算 (否则客户端与服务端会给出
     * 两个不同的数字, 玩家不知道该信哪个)。清掉之后回到兜底值。
     */
    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void syncedShelfLivesDriveTheSharedQueries(GameTestHelper helper) {
        int[] before = FreshnessShelfLives.currentValues();
        try {
            int[] synced = new int[]{1, 2, 3, 4, 5, 6};
            FreshnessShelfLives.applySynced(synced);
            helper.assertTrue(FreshnessShelfLives.syncedFromServer(), "写进去之后要认得这是同步值");
            for (FreshnessCategory category : FreshnessCategory.values()) {
                helper.assertTrue(FreshnessShelfLives.hours(category) == synced[category.ordinal()],
                        category + " 应当按同步值算, 实得 " + FreshnessShelfLives.hours(category));
            }
            // 共享查询 (消费点、客户端显示都走它) 也必须跟着变: 面包按熟食 3 小时算时, 剩余必须按 3 小时算。
            ItemStack bread = new ItemStack(Items.BREAD, 1);
            FreshnessData.stamp(bread, 1_000L);
            // 章是整点的 (stamp 会向下取整到整点小时), 所以剩余 = 批次 + 保质期 − 现在, 不能直接写 3 小时。
            long expected = FreshnessData.batchMinute(bread) + 3L * 60L - 1_000L;
            helper.assertTrue(FreshnessCheck.remainingMinutes(bread, 1_000L) == expected,
                    "共享查询要按同步后的保质期算 (熟食 3 小时), 期望 " + expected + " 实得 "
                            + FreshnessCheck.remainingMinutes(bread, 1_000L));
        } finally {
            FreshnessShelfLives.clearSynced();
        }
        helper.assertTrue(!FreshnessShelfLives.syncedFromServer(), "清掉之后不再认同步值");
        helper.assertTrue(java.util.Arrays.equals(before, FreshnessShelfLives.currentValues()),
                "清掉之后回到兜底值 (服务端即配置), 实得 "
                        + java.util.Arrays.toString(FreshnessShelfLives.currentValues()));
        helper.succeed();
    }

    // ============================================================
    // 3. 显示算术与提醒频率
    // ============================================================

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void displayArithmeticMatchesTheSpec(GameTestHelper helper) {
        // 角落那行数字: <1h / 5h / 2d (一小时以内不给 0h —— 0h 会被读成"已经没了")。
        helper.assertTrue("rot".equals(FreshnessDisplay.label(0L)), "烂了报 rot");
        helper.assertTrue("<1h".equals(FreshnessDisplay.label(59L)), "一小时以内报 <1h");
        helper.assertTrue("1h".equals(FreshnessDisplay.label(60L)), "整一小时报 1h");
        helper.assertTrue("2d".equals(FreshnessDisplay.label(2L * 24L * 60L)), "整两天报 2d");
        helper.assertTrue("1d".equals(FreshnessDisplay.label(2L * 24L * 60L - 60L)),
                "差一分钟到两天报 1d (按小时截断, 不四舍五入成 2d)");
        helper.assertTrue("5h".equals(FreshnessDisplay.label(5L * 60L + 30L)), "小时取整");

        // 档位: 阈值 24 / 6 小时; 不腐烂的东西是 UNTIMED。
        helper.assertTrue(FreshnessDisplay.level(25L * 60L, false) == FreshnessDisplay.Level.FRESH, ">24h 是新鲜");
        helper.assertTrue(FreshnessDisplay.level(24L * 60L, false) == FreshnessDisplay.Level.WARN, "=24h 转黄");
        helper.assertTrue(FreshnessDisplay.level(6L * 60L, false) == FreshnessDisplay.Level.DANGER, "=6h 转红");
        helper.assertTrue(FreshnessDisplay.level(-1L, false) == FreshnessDisplay.Level.ROTTEN, "烂了是 ROTTEN");
        helper.assertTrue(FreshnessDisplay.level(Long.MAX_VALUE, true) == FreshnessDisplay.Level.UNTIMED,
                "不腐烂的东西是 UNTIMED");
        helper.succeed();
    }

    /** 创造物品栏和 JEI 的悬停信息只为易腐食物添加新鲜度。 */
    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void tooltipShowsFreshnessOnlyForPerishableFood(GameTestHelper helper) {
        var player = com.miningdim.testutil.MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);
        com.miningdim.freshness.client.FreshnessClientState.apply(
                FreshnessSync.payloadFor(helper.getLevel()));
        try {
            for (ItemStack stack : java.util.List.of(new ItemStack(Items.STONE),
                    new ItemStack(Items.OAK_PLANKS), new ItemStack(Items.IRON_SWORD),
                    new ItemStack(Items.GOLDEN_APPLE), ItemStack.EMPTY)) {
                var lines = tooltipFor(stack, player);
                helper.assertTrue(lines.isEmpty(), "nonperishable item must have no freshness tooltip: " + stack);
            }
            ItemStack beef = new ItemStack(Items.BEEF);
            long now = FreshnessClockData.nowMinute(helper.getLevel());
            FreshnessData.stampAt(beef, now - 60L, 0);
            var freshLines = tooltipFor(beef, player);
            helper.assertTrue(freshLines.size() == 2
                            && freshLines.get(1).getContents() instanceof net.minecraft.network.chat.contents.TranslatableContents remaining
                            && remaining.getKey().equals("tooltip.miningdim.freshness.remaining"),
                    "hovering food shows its category and remaining shelf life");
            FreshnessData.stampAt(beef,
                    now - FreshnessCategories.shelfLifeHours(beef) * 60L + 59L, 0);
            var subHourLines = tooltipFor(beef, player);
            helper.assertTrue(subHourLines.size() == 2
                            && subHourLines.get(1).getContents()
                                    instanceof net.minecraft.network.chat.contents.TranslatableContents remainingSubHour
                            && remainingSubHour.getKey().equals("tooltip.miningdim.freshness.remaining")
                            && remainingSubHour.getArgs().length == 1
                            && remainingSubHour.getArgs()[0] instanceof net.minecraft.network.chat.Component amount
                            && amount.getContents()
                                    instanceof net.minecraft.network.chat.contents.TranslatableContents subHour
                            && subHour.getKey().equals(
                                    "tooltip.miningdim.freshness.less_than_one_hour"),
                    "food with less than one hour remaining shows the localized sub-hour label");
            FreshnessData.stampAt(beef, now - FreshnessCategories.shelfLifeHours(beef) * 60L, 0);
            var rottenLines = tooltipFor(beef, player);
            helper.assertTrue(rottenLines.size() == 2
                            && rottenLines.get(1).getContents() instanceof net.minecraft.network.chat.contents.TranslatableContents rotten
                            && rotten.getKey().equals("tooltip.miningdim.freshness.rotten"),
                    "hovering expired food shows its rotten state");
        } finally {
            com.miningdim.freshness.client.FreshnessClientState.clear();
        }
        helper.succeed();
    }

    private static java.util.List<net.minecraft.network.chat.Component> tooltipFor(
            ItemStack stack, net.minecraft.server.level.ServerPlayer player) {
        java.util.List<net.minecraft.network.chat.Component> lines = new java.util.ArrayList<>();
        com.miningdim.freshness.client.FreshnessTooltipHandler.onItemTooltip(
                new net.minecraftforge.event.entity.player.ItemTooltipEvent(
                        stack, player, lines, net.minecraft.world.item.TooltipFlag.NORMAL));
        return lines;
    }

    /** 提醒频率: 每小时最多一次, 且只提醒"还没烂、但一小时内会烂"的东西。 */
    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void reminderCadenceIsAtMostOncePerHour(GameTestHelper helper) {
        long now = 5_000_000L;
        helper.assertTrue(FreshnessReminder.shouldWarn(true, now, Long.MIN_VALUE),
                "第一次 (没提醒过) 该提醒");
        helper.assertTrue(!FreshnessReminder.shouldWarn(true, now + 59L, now),
                "同一小时内不许再提醒");
        helper.assertTrue(FreshnessReminder.shouldWarn(true, now + 60L, now),
                "满一小时之后可以再提醒");
        helper.assertTrue(!FreshnessReminder.shouldWarn(false, now + 120L, now),
                "没有快烂的东西时不提醒 (哪怕冷却过了)");

        helper.assertTrue(FreshnessReminder.isExpiringSoon(30L, false), "剩 30 分钟算快烂了");
        helper.assertTrue(!FreshnessReminder.isExpiringSoon(61L, false), "剩 61 分钟不算");
        helper.assertTrue(!FreshnessReminder.isExpiringSoon(-5L, false),
                "已经烂了的不提醒 (那是结算那条路的事)");
        helper.assertTrue(!FreshnessReminder.isExpiringSoon(10L, true),
                "不腐烂的东西永远不提醒");
        helper.assertTrue(FreshnessReminder.isCheckTick(0L) && FreshnessReminder.isCheckTick(20L)
                        && !FreshnessReminder.isCheckTick(21L),
                "每 20 tick 扫一次");
        helper.succeed();
    }
}
