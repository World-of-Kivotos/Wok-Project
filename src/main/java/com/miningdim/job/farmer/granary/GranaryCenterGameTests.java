package com.miningdim.job.farmer.granary;

import com.miningdim.core.MiningConstants;
import com.miningdim.job.JobId;
import com.miningdim.job.farmer.granary.block.GranaryBlocks;
import com.miningdim.testutil.MockGameTestPlayers;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import java.util.List;

/**
 * 账台多页签界面的 GameTest (规格「账台的多页签界面」)。
 *
 * 这一片的风险不在"画得好不好看", 而在三处**界面与判定分叉**的可能:
 * <ol>
 *   <li><b>页内容与服务端不一致</b>: 界面上另算一套判定, 就会出现"界面说能存、服务端说不行"。所以页内容
 *       必须由 {@link GranaryCenterView} 现算 (结构页与 {@code /granary status} 是同一批行), 用例直接断言;</li>
 *   <li><b>越界页序号</b>: 页序号走网络包, 改包客户端能发任意数 —— 解码与处理两处都要兜回第一页;</li>
 *   <li><b>包体无界</b>: 一页行数与单行长度都必须有上限, 否则一座大粮仓能把包撑爆。</li>
 * </ol>
 *
 * 画的部分 (页签、字色) 只能在实机上看, 列入测试服清单。
 */
@GameTestHolder(MiningConstants.MODID)
@PrefixGameTestTemplate(false)
public final class GranaryCenterGameTests {

    private static final String EMPTY = "empty";
    private static final String BATCH = "granary_center";

    /** 每个用例占一段互不重叠的地基 (与世界坐标无关, 但同一批次里几个用例会同时跑)。 */
    private static final int SLOT = 32;

    private static final int HIGH = 3;
    private static final int LOW = 1;

    /** 这一批结束后把账本清干净 (粮仓账本是全服共享的, 留着会干扰别的批次的自查用例)。 */
    @net.minecraft.gametest.framework.AfterBatch(batch = BATCH)
    public static void clearLedger(ServerLevel level) {
        GranaryTestLedger.clear(level);
    }

    // ============================================================
    // 1. 页内容
    // ============================================================

    /** 结构页 = 与 {@code /granary status} 同一批行 (含粮仓编号、档位、状态), 而不是界面自己编的几句话。 */
    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void structurePageReusesTheStatusLines(GameTestHelper helper) {
        int slot = 1;
        Fixture fixture = Fixture.accept(helper, slot);
        GranaryInspection.Report report = GranaryCenterView.reportAt(fixture.level(),
                helper.absolutePos(center(slot)));
        helper.assertTrue(report != null, "账台边上应当能查出这座粮仓");

        List<String> structure = GranaryCenterView.lines(GranaryCenterPage.STRUCTURE, fixture.level(),
                fixture.owner(), report);
        List<Component> expected = GranaryQuery.statusLines(fixture.level(), report, fixture.owner());
        helper.assertTrue(structure.size() == expected.size(),
                "结构页的行数必须与 /granary status 一致 (同一批行), 实得 " + structure.size()
                        + " 期望 " + expected.size());
        helper.assertTrue(textOf(structure.get(0)).contains(String.valueOf(report.gid())),
                "第一行要带粮仓编号, 实得 " + textOf(structure.get(0)));

        // 不属于任何粮仓的账台: 结构页要明说, 而不是给一片空白。
        List<String> orphan = GranaryCenterView.lines(GranaryCenterPage.STRUCTURE, fixture.level(),
                fixture.owner(), null);
        helper.assertTrue(orphan.size() == 1
                        && textOf(orphan.get(0)).contains("not_a_granary"),
                "查不到粮仓时结构页要明说, 实得 " + orphan);
        fixture.close(helper);
        helper.succeed();
    }

    /** 我的囤页: 没有囤时说清楚; 有囤时每行带坐标与状态 (与 {@code /granary bins} 同源)。 */
    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void myBinsPageListsBinsOrSaysThereAreNone(GameTestHelper helper) {
        int slot = 2;
        Fixture fixture = Fixture.accept(helper, slot);

        List<String> before = GranaryCenterView.lines(GranaryCenterPage.MY_BINS, fixture.level(),
                fixture.owner(), null);
        helper.assertTrue(before.size() == 1 && textOf(before.get(0)).contains("no_bins"),
                "还没放囤时要明说「没有囤」, 实得 " + before);

        fixture.placeBin(helper, bin(slot));
        List<String> after = GranaryCenterView.lines(GranaryCenterPage.MY_BINS, fixture.level(),
                fixture.owner(), null);
        helper.assertTrue(after.size() == 1, "一个囤就一行, 实得 " + after.size());
        String row = textOf(after.get(0));
        helper.assertTrue(row.contains(",") || row.contains("["),
                "行里要带坐标 (玩家靠它找到那个囤), 实得 " + row);
        helper.assertTrue(row.contains("granary.bin_state."),
                "行里要带状态键 (启用/待启用/停用), 实得 " + row);
        fixture.close(helper);
        helper.succeed();
    }

    /** 成员页: 房主单列 + 成员逐个列出 (没有成员时明说)。 */
    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void membersPageListsOwnerAndMembers(GameTestHelper helper) {
        int slot = 3;
        Fixture fixture = Fixture.accept(helper, slot);
        GranaryInspection.Report report = GranaryCenterView.reportAt(fixture.level(),
                helper.absolutePos(center(slot)));
        List<String> lines = GranaryCenterView.lines(GranaryCenterPage.MEMBERS, fixture.level(),
                fixture.owner(), report);
        helper.assertTrue(lines.size() == 2 && textOf(lines.get(0)).contains("member_owner")
                        && textOf(lines.get(1)).contains("no_members"),
                "房主一行 + 没有成员一行, 实得 " + lines);
        fixture.close(helper);
        helper.succeed();
    }

    // ============================================================
    // 2. 页序号与包
    // ============================================================

    /** 页序号越界一律兜回第一页 (页序号走网络包, 改包客户端能发任意数)。 */
    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void outOfRangePageFallsBackToTheFirstPage(GameTestHelper helper) {
        helper.assertTrue(GranaryCenterPage.byIndex(-1) == GranaryCenterPage.STRUCTURE, "负数兜回第一页");
        helper.assertTrue(GranaryCenterPage.byIndex(999) == GranaryCenterPage.STRUCTURE, "太大兜回第一页");
        for (GranaryCenterPage page : GranaryCenterPage.values()) {
            helper.assertTrue(GranaryCenterPage.byIndex(page.ordinal()) == page,
                    "合法序号要原样返回 (" + page + ")");
        }
        helper.succeed();
    }

    /** 线格式: 两个包编解码逐字还原; 反过来解码一份超长/越界的包也不许炸。 */
    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void packetsRoundTripAndBoundTheirPayload(GameTestHelper helper) {
        GranaryCenterPageC2S request = new GranaryCenterPageC2S(7, GranaryCenterPage.MEMBERS.ordinal());
        FriendlyByteBuf buf = new FriendlyByteBuf(io.netty.buffer.Unpooled.buffer());
        GranaryCenterPageC2S.encode(request, buf);
        GranaryCenterPageC2S decodedRequest = GranaryCenterPageC2S.decode(buf);
        helper.assertTrue(decodedRequest.windowId() == 7
                        && decodedRequest.page() == GranaryCenterPage.MEMBERS.ordinal(),
                "请求包要逐字还原, 实得 " + decodedRequest);

        GranaryCenterPageS2C response = new GranaryCenterPageS2C(7, GranaryCenterPage.LEDGER.ordinal(),
                List.of("{\"text\":\"a\"}", "{\"text\":\"b\"}"));
        FriendlyByteBuf out = new FriendlyByteBuf(io.netty.buffer.Unpooled.buffer());
        GranaryCenterPageS2C.encode(response, out);
        GranaryCenterPageS2C decoded = GranaryCenterPageS2C.decode(out);
        helper.assertTrue(decoded.windowId() == 7 && decoded.page() == GranaryCenterPage.LEDGER.ordinal()
                        && decoded.lines().equals(response.lines()),
                "页内容包要逐字还原, 实得 " + decoded);

        // 声称有海量行的畸形包: 解码按上限截断, 不许按对方说的数去分配。
        FriendlyByteBuf hostile = new FriendlyByteBuf(io.netty.buffer.Unpooled.buffer());
        hostile.writeVarInt(1);
        hostile.writeVarInt(0);
        hostile.writeVarInt(1_000_000);
        hostile.writeUtf("{\"text\":\"only-one\"}");
        GranaryCenterPageS2C clamped = GranaryCenterPageS2C.decode(hostile);
        helper.assertTrue(clamped.lines().size() <= GranaryCenterView.MAX_LINES + 1,
                "行数要按上限截断, 实得 " + clamped.lines().size());
        helper.succeed();
    }

    // ============================================================
    // 3. 界面真的开得起来
    // ============================================================

    /** 右键账台开的应当是这一套界面 (而不是以前的聊天流), 且窗口里认得出是账台菜单。 */
    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void usingTheCenterOpensTheMenu(GameTestHelper helper) {
        int slot = 4;
        Fixture fixture = Fixture.accept(helper, slot);
        ServerPlayer owner = fixture.owner();
        BlockPos pos = helper.absolutePos(center(slot));
        fixture.level().getBlockState(pos).use(fixture.level(), owner, net.minecraft.world.InteractionHand.MAIN_HAND,
                new net.minecraft.world.phys.BlockHitResult(net.minecraft.world.phys.Vec3.atCenterOf(pos),
                        net.minecraft.core.Direction.UP, pos, false));
        helper.assertTrue(owner.containerMenu instanceof GranaryCenterMenu,
                "右键账台应当开多页签界面, 实得 " + owner.containerMenu);
        GranaryCenterMenu menu = (GranaryCenterMenu) owner.containerMenu;
        helper.assertTrue(menu.centerPos().equals(pos), "菜单要记住是哪一座账台");
        helper.assertTrue(menu.slots.size() == 36, "界面里只有玩家背包那 36 格 (不挂任何容器格)");
        fixture.close(helper);
        helper.succeed();
    }

    // ============================================================
    // 夹具
    // ============================================================

    /** 一座已验收的粮仓 + 一位农夫玩家 (与 GranaryDepositGameTests 的搭建同款, 这里按本类需要收窄)。 */
    private record Fixture(ServerPlayer owner, BlockPos centerRelative, ServerLevel level) {

        static Fixture accept(GameTestHelper helper, int slot) {
            ServerPlayer owner = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);
            setFarmerLevel(owner, 1);
            buildRoom(helper, slot, 3);
            BlockPos centerRelative = center(slot);
            placeCenter(helper, owner, centerRelative);
            helper.setBlock(vent(slot), GranaryBlocks.VENT.get());
            GranaryInspection.Acceptance acceptance =
                    GranaryInspection.accept(helper.getLevel(), helper.absolutePos(centerRelative), owner);
            helper.assertTrue(acceptance.accepted(),
                    "夹具: 先验收成粮棚, 实得 " + acceptance.rejection());
            return new Fixture(owner, centerRelative, helper.getLevel());
        }

        void placeBin(GameTestHelper helper, BlockPos relative) {
            GranaryCenterGameTests.placeBin(helper, owner, relative);
        }

        /**
         * 收尾: 把这座粮仓从**全服共享的账本**里删干净 (囤与账台记录)。
         *
         * 粮仓账本是存档级数据, 跨批次共享 —— 留着这几座, 后面"按 tick 哈希挑一座自查"的用例就找不到
         * "只轮到自己"的 tick 了。每个用例成功时都必须清掉。
         */
        void close(GameTestHelper helper) {
            GranarySavedData data = GranarySavedData.get(level);
            for (GranarySavedData.PosKey key : java.util.List.copyOf(data.bins())) {
                BlockPos pos = BlockPos.of(key.packedPos());
                if (key.dimension().equals(level.dimension().location()) && insideRoom(helper, pos)) {
                    data.releaseBin(key.dimension(), pos);
                }
            }
            data.releaseCenter(level.dimension().location(), helper.absolutePos(centerRelative));
            long gid = data.centerGranary(level.dimension().location(), helper.absolutePos(centerRelative));
            if (gid >= 0L) {
                data.removeGranary(gid);
            }
        }

        /** 这个囤在不在本用例那间屋里 (按坐标范围判, 不碰别人的房间)。 */
        private boolean insideRoom(GameTestHelper helper, BlockPos abs) {
            BlockPos min = helper.absolutePos(new BlockPos(centerRelative.getX() - 2, 0, centerRelative.getZ() - 2));
            BlockPos max = helper.absolutePos(new BlockPos(centerRelative.getX() + 2, HIGH + 1, centerRelative.getZ() + 2));
            return abs.getX() >= min.getX() && abs.getX() <= max.getX()
                    && abs.getZ() >= min.getZ() && abs.getZ() <= max.getZ();
        }
    }

    private static BlockPos center(int slot) {
        int base = slot * SLOT;
        return new BlockPos(base + 2, LOW, 2);
    }

    private static BlockPos bin(int slot) {
        int base = slot * SLOT;
        return new BlockPos(base + 1, LOW, 1);
    }

    private static BlockPos vent(int slot) {
        int base = slot * SLOT;
        return new BlockPos(base + 3, HIGH, 3);
    }

    /** 3x3 室内 + 石壳 (与粮仓其余用例同一套房间尺寸, 免得结构判定各写一套)。 */
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

    /** 摆一座账台: 走真正的放置事件 (账本按事件登记), 与粮仓其余用例同一套搭法。 */
    private static void placeCenter(GameTestHelper helper, ServerPlayer player, BlockPos relative) {
        ServerLevel level = helper.getLevel();
        BlockPos pos = helper.absolutePos(relative);
        GranarySystem.releaseCenter(level, pos);
        helper.setBlock(relative, GranaryBlocks.CENTER.get());
        net.minecraftforge.event.level.BlockEvent.EntityPlaceEvent event =
                new net.minecraftforge.event.level.BlockEvent.EntityPlaceEvent(
                        net.minecraftforge.common.util.BlockSnapshot.create(level.dimension(), level, pos),
                        Blocks.AIR.defaultBlockState(), player);
        GranarySystem.onBlockPlaced(event);
        helper.assertTrue(!event.isCanceled(), "账台应当放得下");
        GranaryBlocks.CENTER.get().setPlacedBy(level, pos, level.getBlockState(pos), player, ItemStack.EMPTY);
    }

    /** 摆一个储粮囤 (同样走放置事件: 归属与额度都在那里记账)。 */
    private static void placeBin(GameTestHelper helper, ServerPlayer player, BlockPos relative) {
        ServerLevel level = helper.getLevel();
        BlockPos pos = helper.absolutePos(relative);
        GranarySavedData.get(level).releaseBin(level.dimension().location(), pos);
        helper.setBlock(relative, GranaryBlocks.BIN.get());
        net.minecraftforge.event.level.BlockEvent.EntityPlaceEvent event =
                new net.minecraftforge.event.level.BlockEvent.EntityPlaceEvent(
                        net.minecraftforge.common.util.BlockSnapshot.create(level.dimension(), level, pos),
                        Blocks.AIR.defaultBlockState(), player);
        GranarySystem.onBlockPlaced(event);
        helper.assertTrue(!event.isCanceled(), "储粮囤应当放得下");
        GranaryBlocks.BIN.get().setPlacedBy(level, pos, level.getBlockState(pos), player, ItemStack.EMPTY);
    }

    private static void setFarmerLevel(ServerPlayer player, int level) {
        com.miningdim.entry.MiningCapabilities.get(player)
                .orElseThrow(() -> new IllegalStateException("mock 玩家未挂载 capability, 无法摆放职业等级"))
                .jobProgress(JobId.FARMER)
                .setLevel(level);
    }

    /** 把序列化后的那一行还原成文本 (断言只看键与数字, 不看渲染出来的字形)。 */
    private static String textOf(String json) {
        Component component = Component.Serializer.fromJson(json);
        return component == null ? "" : component.toString();
    }
}
