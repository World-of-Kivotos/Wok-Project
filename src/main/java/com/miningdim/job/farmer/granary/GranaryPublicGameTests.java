package com.miningdim.job.farmer.granary;

import com.miningdim.core.MiningConstants;
import com.miningdim.freshness.FreshnessData;
import com.miningdim.job.JobId;
import com.miningdim.job.farmer.granary.block.GranaryBinBlockEntity;
import com.miningdim.job.farmer.granary.block.GranaryBlocks;
import com.miningdim.testutil.MockGameTestPlayers;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.common.util.BlockSnapshot;
import net.minecraftforge.event.level.BlockEvent;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import java.util.List;
import java.util.UUID;

/**
 * 官仓公开模式的 GameTest (docs/Spoilage_Granary_DesignSpec.md「合建: 入册与权限」官仓独有那一条)。
 *
 * 锁四件事, 全在服务端:
 * <ol>
 *   <li><b>只有官仓能开</b>: 粮棚与冷库一律 {@code NOT_STATE}; 档位降回去之后公开模式自动失效
 *       (不需要额外的"降档时关掉"钩子);</li>
 *   <li><b>只有囤主能开</b>: 房主与名单上的人都不能替别人的囤开口子;</li>
 *   <li><b>配额按人按天</b>: 一组 = 一次整堆, 日序号按北京 08:00 翻; 捐粮与取用各自算自己的账,</li>
 *   <li><b>囤主与名单上的人不受公开配额约束</b> —— 公开模式只是"多开一扇门", 不是给原本能进的人上锁。</li>
 * </ol>
 */
@GameTestHolder(MiningConstants.MODID)
@PrefixGameTestTemplate(false)
public final class GranaryPublicGameTests {

    private static final String EMPTY = "empty";
    private static final String BATCH = "granary_public";

    /** 这一批结束后把账本清干净 (粮仓账本是全服共享的存档数据, 留着会干扰后面的批次与下一轮的自查)。 */
    @net.minecraft.gametest.framework.AfterBatch(batch = BATCH)
    public static void clearLedger(ServerLevel level) {
        GranaryTestLedger.clear(level);
    }

    private static final int SLOT = 16;
    private static final int LOW = 1;
    private static final int HIGH = 3;

    /** 用例里的「现在」: 一个够大的整点 (避开入仓时刻的哨兵 0)。 */
    private static final long NOW_HOUR = 3_000_000L;

    // ============================================================
    // 1. 翻日与配额的算术
    // ============================================================

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void dayRollsOverAtBeijingEightAndUsageRoundTrips(GameTestHelper helper) {
        // 挂钟是 epoch 分钟 (UTC): 北京 08:00 恰好是 UTC 00:00, 所以日序号就是 floorDiv(分钟, 1440)。
        helper.assertTrue(GranaryPublic.dayOf(0L) == 0L, "UTC 00:00 是新的一天的开头");
        helper.assertTrue(GranaryPublic.dayOf(GranaryPublic.MINUTES_PER_DAY - 1L) == 0L,
                "北京 07:59 还是前一天");
        helper.assertTrue(GranaryPublic.dayOf(GranaryPublic.MINUTES_PER_DAY) == 1L,
                "北京 08:00 翻日 (UTC 00:00)");
        helper.assertTrue(GranaryPublic.clampGroups(-3) == 0
                        && GranaryPublic.clampGroups(99) == GranaryPublic.MAX_GROUPS,
                "配额夹在 0..9");

        GranaryPublic.Settings settings = new GranaryPublic.Settings(true, 3, true, 2);
        GranaryPublic.Settings loaded = GranaryPublic.Settings.load(settings.save());
        helper.assertTrue(loaded.equals(settings), "设置原样存读, 实得 " + loaded);
        helper.assertTrue(GranaryPublic.Settings.load(new net.minecraft.nbt.CompoundTag())
                .equals(GranaryPublic.Settings.OFF), "老档没有这个键就是全关");

        UUID player = UUID.randomUUID();
        List<GranaryPublic.Usage> usage = List.of(new GranaryPublic.Usage(player, 7L, 1, 2));
        List<GranaryPublic.Usage> loadedUsage = GranaryPublic.loadUsage(GranaryPublic.saveUsage(usage));
        helper.assertTrue(loadedUsage.equals(usage), "用量表原样存读, 实得 " + loadedUsage);
        GranaryPublic.Usage today = GranaryPublic.usageOf(loadedUsage, player, 7L);
        helper.assertTrue(GranaryPublic.remainingDonations(settings, today) == 2,
                "捐粮还差 2 组 (3 - 1)");
        helper.assertTrue(GranaryPublic.remainingTakes(settings, today) == 0,
                "取用已经用完 (2 - 2)");
        helper.assertTrue(GranaryPublic.usageOf(loadedUsage, player, 8L).stored() == 0,
                "翻日之后重新从 0 开始算");
        helper.succeed();
    }

    // ============================================================
    // 2. 谁能开
    // ============================================================

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void onlyTheOwnerOfAStateGranaryCanOpenTheDoors(GameTestHelper helper) {
        Fixture fixture = accept(helper, 1);
        ServerLevel level = helper.getLevel();
        GranaryBinBlockEntity bin = fixture.bin(helper);

        // 粮棚 (默认验收出来的那一档) 不能开: 公开模式是官仓独有的。
        GranaryPublicService.Result shed = GranaryPublicService.apply(level, bin, fixture.owner(),
                GranaryPublicService.Action.DONATE_ON, 2);
        helper.assertTrue(shed.outcome() == GranaryPublicService.Outcome.NOT_STATE,
                "粮棚不能开公开捐粮, 实得 " + shed.outcome());

        // 陌生人 (哪怕是房主) 也不能替囤主开。
        ServerPlayer stranger = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);
        GranaryPublicService.Result strangerResult = GranaryPublicService.apply(level, bin, stranger,
                GranaryPublicService.Action.DONATE_ON, 2);
        helper.assertTrue(strangerResult.outcome() == GranaryPublicService.Outcome.NOT_OWNER,
                "只有囤主能开, 实得 " + strangerResult.outcome());

        // 升成官仓之后就能开。
        fixture.becomeStateGranary(helper);
        GranaryPublicService.Result state = GranaryPublicService.apply(level, bin, fixture.owner(),
                GranaryPublicService.Action.DONATE_ON, 2);
        helper.assertTrue(state.outcome() == GranaryPublicService.Outcome.ENABLED
                        && state.settings().donate() && state.settings().donateGroupsPerDay() == 2,
                "官仓可以开公开捐粮, 实得 " + state.outcome() + " / " + state.settings());
        helper.assertTrue(GranaryPublicService.donationOpen(level, bin), "开了就是开着");

        // 档位降回去 (这里直接改登记项, 与降档命令走的是同一份账本): 公开模式自动失效。
        fixture.becomeTier(helper, GranaryTier.COLD);
        helper.assertTrue(!GranaryPublicService.donationOpen(level, bin),
                "档位降回冷库之后公开模式不再生效");
        helper.assertTrue(!GranaryPublicService.mayDonate(level, bin, stranger, NOW_HOUR * 60L),
                "陌生人也不能再靠它进来");
        helper.succeed();
    }

    // ============================================================
    // 3. 公开捐粮: 陌生人能存, 但按人按天限组
    // ============================================================

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void strangersCanDonateOnlyUpToTheDailyQuota(GameTestHelper helper) {
        Fixture fixture = accept(helper, 2);
        ServerLevel level = helper.getLevel();
        GranaryBinBlockEntity bin = fixture.bin(helper);
        fixture.becomeStateGranary(helper);
        GranaryPublicService.apply(level, bin, fixture.owner(), GranaryPublicService.Action.DONATE_ON, 1);
        ServerPlayer stranger = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);

        stranger.getInventory().setItem(9, new ItemStack(Items.WHEAT, 5));
        stranger.getInventory().setItem(10, new ItemStack(Items.BREAD, 3));
        GranaryDeposit.Result first = GranaryDeposit.depositAt(level, bin, stranger, true, NOW_HOUR * 60L);
        helper.assertTrue(first.accepted(), "公开捐粮开着时陌生人能存, 实得 " + first.rejection());
        helper.assertTrue(first.stacks() == 1, "一天 1 组: 只收下第一堆, 实得 " + first.stacks() + " 堆");
        helper.assertTrue(first.skips().limited() == 1, "剩下那堆要报成「今天配额用完了」, 实得 "
                + first.skips());
        helper.assertTrue(stranger.getInventory().getItem(10).getCount() == 3,
                "配额用完的那堆还在背包里");

        // 再来一次: 今天的配额已经用完了。
        stranger.getInventory().setItem(9, new ItemStack(Items.WHEAT, 4));
        GranaryDeposit.Result second = GranaryDeposit.depositAt(level, bin, stranger, true, NOW_HOUR * 60L);
        helper.assertTrue(second.rejection() == GranaryDeposit.Rejection.DAILY_LIMIT,
                "配额用完之后要明说, 实得 " + second.rejection());
        helper.assertTrue(stranger.getInventory().getItem(9).getCount() == 4, "东西一件没动");

        // 翻日之后配额重新满上 (北京 08:00 = UTC 00:00)。
        long nextDay = (GranaryPublic.dayOf(NOW_HOUR * 60L) + 1L) * GranaryPublic.MINUTES_PER_DAY;
        helper.assertTrue(GranaryPublicService.mayDonate(level, bin, stranger, nextDay),
                "翻日之后又能捐了");
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void ownerAndTrusteesAreNotSubjectToThePublicQuota(GameTestHelper helper) {
        Fixture fixture = accept(helper, 3);
        ServerLevel level = helper.getLevel();
        GranaryBinBlockEntity bin = fixture.bin(helper);
        fixture.becomeStateGranary(helper);
        GranaryPublicService.apply(level, bin, fixture.owner(), GranaryPublicService.Action.DONATE_ON, 1);

        // 囤主: 公开配额是"给陌生人的门", 原本就能进的人不受它约束。
        fixture.owner().getInventory().setItem(9, new ItemStack(Items.WHEAT, 5));
        fixture.owner().getInventory().setItem(10, new ItemStack(Items.BREAD, 3));
        GranaryDeposit.Result own = GranaryDeposit.depositAt(level, bin, fixture.owner(), true,
                NOW_HOUR * 60L);
        helper.assertTrue(own.accepted() && own.stacks() == 2,
                "囤主照常存, 不受公开配额约束, 实得 " + own.stacks() + " 堆 / " + own.rejection());

        // 名单上「只存」的人同理。
        ServerPlayer trustee = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);
        bin.addTrustee(trustee.getUUID(), GranaryAccess.Permission.STORE_ONLY);
        trustee.getInventory().setItem(9, new ItemStack(Items.WHEAT, 5));
        trustee.getInventory().setItem(10, new ItemStack(Items.BREAD, 3));
        GranaryDeposit.Result trusted = GranaryDeposit.depositAt(level, bin, trustee, true,
                NOW_HOUR * 60L);
        helper.assertTrue(trusted.accepted() && trusted.stacks() == 2,
                "名单上的人照常存, 实得 " + trusted.stacks() + " 堆 / " + trusted.rejection());
        helper.assertTrue(!GranaryPublicService.mayDonate(level, bin, trustee, NOW_HOUR * 60L),
                "名单上的人走自己那条路, 不算公开捐粮");
        helper.succeed();
    }

    // ============================================================
    // 4. 公开取用: 菜单的槽按配额放行
    // ============================================================

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void publicTakeGatesTheBinSlotsAndRunsOutAtTheQuota(GameTestHelper helper) {
        Fixture fixture = accept(helper, 4);
        ServerLevel level = helper.getLevel();
        GranaryBinBlockEntity bin = fixture.bin(helper);
        fixture.becomeStateGranary(helper);
        fixture.owner().getInventory().setItem(9, new ItemStack(Items.WHEAT, 5));
        GranaryDeposit.depositAt(level, bin, fixture.owner(), true, NOW_HOUR * 60L);
        GranaryPublicService.apply(level, bin, fixture.owner(), GranaryPublicService.Action.TAKE_ON, 1);
        ServerPlayer stranger = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);

        GranaryBinMenu menu = new GranaryBinMenu(0, stranger.getInventory(), bin);
        helper.assertTrue(menu.slots.get(0).mayPickup(stranger),
                "公开取用今天还有 1 组: 第一格能拿");
        long day = GranaryPublic.dayOf(GranaryPublicService.nowMinute(level));
        helper.assertTrue(bin.recordPublicTake(stranger.getUUID(), day, 1), "记一组公开取用");
        helper.assertTrue(!menu.slots.get(0).mayPickup(stranger), "配额用完之后第一格不能拿了");
        helper.assertTrue(!bin.recordPublicTake(stranger.getUUID(), day, 1), "超出配额的记账要被拒");

        // 翻日之后又能拿。
        long nextMinute = (day + 1L) * GranaryPublic.MINUTES_PER_DAY;
        helper.assertTrue(GranaryPublicService.mayTake(level, bin, stranger, nextMinute),
                "翻日之后又能取了");
        helper.succeed();
    }

    // ============================================================
    // 5. 只收未腐烂的易腐食物; 私有囤照旧关门
    // ============================================================

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void onlyFreshPerishablesCanBeDonated(GameTestHelper helper) {
        Fixture fixture = accept(helper, 5);
        ServerLevel level = helper.getLevel();
        GranaryBinBlockEntity bin = fixture.bin(helper);
        fixture.becomeStateGranary(helper);
        GranaryPublicService.apply(level, bin, fixture.owner(), GranaryPublicService.Action.DONATE_ON, 2);
        ServerPlayer stranger = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);
        long nowMinute = NOW_HOUR * 60L;
        helper.assertTrue(GranaryPublicService.mayDonate(level, bin, stranger, nowMinute),
                "公开捐粮开着, 陌生人可以捐");

        helper.assertTrue(GranaryPublicService.donatable(new ItemStack(Items.WHEAT, 1), nowMinute),
                "小麦是未腐烂的易腐食物");
        ItemStack rotten = new ItemStack(Items.BREAD, 1);
        FreshnessData.stampAt(rotten, nowMinute - 40L * 24L * 60L, 0);
        helper.assertTrue(!GranaryPublicService.donatable(rotten, nowMinute), "已经烂的不能捐");
        helper.assertTrue(!GranaryPublicService.donatable(new ItemStack(Items.IRON_INGOT, 1), nowMinute),
                "不腐烂的东西不能捐");

        // 私有囤 (公开模式关着) 在官仓里也照旧关门。
        GranaryPublicService.apply(level, bin, fixture.owner(), GranaryPublicService.Action.DONATE_OFF, 0);
        helper.assertTrue(!GranaryPublicService.mayDonate(level, bin, stranger, nowMinute),
                "关掉之后陌生人不能捐");
        GranaryDeposit.Result rejected = GranaryDeposit.depositAt(level, bin, stranger, true, nowMinute);
        helper.assertTrue(rejected.rejection() == GranaryDeposit.Rejection.NOT_ALLOWED,
                "关掉之后存入也要拒绝, 实得 " + rejected.rejection());
        helper.succeed();
    }

    // ============================================================
    // 6. 界面开关的线格式
    // ============================================================

    /** 两个包逐字还原; 越界动作/配额在解码处兜回安全值 (网络来的东西一律当不可信)。 */
    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void publicPacketsRoundTripAndClamp(GameTestHelper helper) {
        GranaryPublicActionC2S request = new GranaryPublicActionC2S(9,
                GranaryPublicService.Action.TAKE_ON.ordinal(), 3);
        net.minecraft.network.FriendlyByteBuf buf =
                new net.minecraft.network.FriendlyByteBuf(io.netty.buffer.Unpooled.buffer());
        GranaryPublicActionC2S.encode(request, buf);
        GranaryPublicActionC2S decoded = GranaryPublicActionC2S.decode(buf);
        helper.assertTrue(decoded.windowId() == 9
                        && decoded.action() == GranaryPublicService.Action.TAKE_ON.ordinal()
                        && decoded.groups() == 3,
                "请求包要逐字还原, 实得 " + decoded);

        net.minecraft.network.FriendlyByteBuf hostile =
                new net.minecraft.network.FriendlyByteBuf(io.netty.buffer.Unpooled.buffer());
        hostile.writeVarInt(1);
        hostile.writeVarInt(999);
        hostile.writeVarInt(70);
        GranaryPublicActionC2S clamped = GranaryPublicActionC2S.decode(hostile);
        helper.assertTrue(clamped.action() == GranaryPublicService.Action.DONATE_OFF.ordinal()
                        && clamped.groups() == GranaryPublic.MAX_GROUPS,
                "越界动作与配额都要兜底, 实得 " + clamped);

        GranaryPublicStateS2C state = new GranaryPublicStateS2C(9, "message.miningdim.granary.public.enabled",
                true, 2, false, 1, true);
        net.minecraft.network.FriendlyByteBuf out =
                new net.minecraft.network.FriendlyByteBuf(io.netty.buffer.Unpooled.buffer());
        GranaryPublicStateS2C.encode(state, out);
        helper.assertTrue(GranaryPublicStateS2C.decode(out).equals(state), "状态包要逐字还原");
        helper.succeed();
    }

    // ============================================================
    // 夹具
    // ============================================================

    /** 一座验收好的粮棚 + 一个启用中的囤 (官仓那一档由 {@link #becomeStateGranary} 改登记项得到)。 */
    private record Fixture(ServerPlayer owner, BlockPos centerRelative) {

        BlockPos binRelative() {
            return new BlockPos(centerRelative.getX() - 1, LOW, LOW);
        }

        GranaryBinBlockEntity bin(GameTestHelper helper) {
            return (GranaryBinBlockEntity) helper.getBlockEntity(binRelative());
        }

        /** 把登记项改成官仓 (与验收命令走同一份账本: 档位只在验收那一刻定, 之后改档就是改这一行)。 */
        void becomeStateGranary(GameTestHelper helper) {
            becomeTier(helper, GranaryTier.STATE);
        }

        void becomeTier(GameTestHelper helper, GranaryTier tier) {
            ServerLevel level = helper.getLevel();
            GranarySavedData data = GranarySavedData.get(level);
            BlockPos centerPos = helper.absolutePos(centerRelative);
            long gid = data.centerGranary(level.dimension().location(), centerPos);
            GranaryRecord record = data.granary(gid);
            data.putGranary(record.accepted(tier, owner.getUUID(), NOW_HOUR));
            // 官仓验收要求更大的屋子; 这一批只测公开模式, 所以账本里的囤直接置为启用 (与官仓一致)。
            data.setBinState(level.dimension().location(), helper.absolutePos(binRelative()),
                    GranaryBinState.ACTIVE, NOW_HOUR);
            helper.assertTrue(data.granary(gid).tier() == tier, "夹具: 档位要改成 " + tier);
        }
    }

    private static Fixture accept(GameTestHelper helper, int slot) {
        ServerPlayer owner = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);
        setFarmerLevel(owner, 1);
        buildRoom(helper, slot, 3);
        placeCenter(helper, owner, center(slot));
        placeBin(helper, owner, binRel(slot));
        helper.setBlock(vent(slot), GranaryBlocks.VENT.get());
        helper.assertTrue(GranaryInspection.accept(helper.getLevel(), helper.absolutePos(center(slot)), owner)
                .accepted(), "夹具: 先验收成粮棚");
        return new Fixture(owner, center(slot));
    }

    private static void placeCenter(GameTestHelper helper, ServerPlayer player, BlockPos relative) {
        ServerLevel level = helper.getLevel();
        BlockPos pos = helper.absolutePos(relative);
        GranarySystem.releaseCenter(level, pos);
        helper.setBlock(relative, GranaryBlocks.CENTER.get());
        BlockEvent.EntityPlaceEvent event = new BlockEvent.EntityPlaceEvent(
                BlockSnapshot.create(level.dimension(), level, pos), Blocks.AIR.defaultBlockState(), player);
        GranarySystem.onBlockPlaced(event);
        helper.assertTrue(!event.isCanceled(), "账台应当放得下");
        GranaryBlocks.CENTER.get().setPlacedBy(level, pos, level.getBlockState(pos), player, ItemStack.EMPTY);
    }

    private static void placeBin(GameTestHelper helper, ServerPlayer player, BlockPos relative) {
        ServerLevel level = helper.getLevel();
        BlockPos pos = helper.absolutePos(relative);
        GranarySavedData.get(level).releaseBin(level.dimension().location(), pos);
        helper.setBlock(relative, GranaryBlocks.BIN.get());
        BlockEvent.EntityPlaceEvent event = new BlockEvent.EntityPlaceEvent(
                BlockSnapshot.create(level.dimension(), level, pos), Blocks.AIR.defaultBlockState(), player);
        GranarySystem.onBlockPlaced(event);
        helper.assertTrue(!event.isCanceled(), "储粮囤应当放得下");
        GranaryBlocks.BIN.get().setPlacedBy(level, pos, level.getBlockState(pos), player, ItemStack.EMPTY);
    }

    private static BlockPos center(int slot) {
        return new BlockPos(slot * SLOT + 2, LOW, 2);
    }

    private static BlockPos binRel(int slot) {
        return new BlockPos(slot * SLOT + 1, LOW, 1);
    }

    private static BlockPos vent(int slot) {
        return new BlockPos(slot * SLOT + 3, HIGH, 3);
    }

    private static void buildRoom(GameTestHelper helper, int slot, int width) {
        int base = slot * SLOT;
        for (int x = 0; x <= width + 1; x++) {
            for (int y = 0; y <= HIGH + 1; y++) {
                for (int z = 0; z <= width + 1; z++) {
                    boolean shell = x == 0 || x == width + 1 || y == 0 || y == HIGH + 1 || z == 0 || z == width + 1;
                    BlockState state = shell ? Blocks.STONE.defaultBlockState() : Blocks.AIR.defaultBlockState();
                    helper.setBlock(new BlockPos(base + x, y, z), state);
                }
            }
        }
    }

    private static void setFarmerLevel(ServerPlayer player, int level) {
        com.miningdim.entry.MiningCapabilities.get(player)
                .orElseThrow(() -> new IllegalStateException("mock 玩家未挂载 capability"))
                .jobProgress(JobId.FARMER)
                .setLevel(level);
    }
}
