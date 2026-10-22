package com.miningdim.job.farmer.granary;

import com.miningdim.core.MiningConstants;
import com.miningdim.job.JobId;
import com.miningdim.job.farmer.granary.block.GranaryBinBlockEntity;
import com.miningdim.job.farmer.granary.block.GranaryBlocks;
import com.miningdim.testutil.MockGameTestPlayers;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import java.util.List;

/**
 * 开放设置页 (囤界面里的按钮改开放名单) 的 GameTest。
 *
 * 这一片守的是**权限与认人**两件事, 都不在界面上:
 * <ol>
 *   <li><b>只有囤主能改</b>: 界面按钮、命令、包都走 {@link GranaryTrustService} 一处判定 ——
 *       陌生人发同样的包必须被拒且名单一字不动;</li>
 *   <li><b>认人不靠猜</b>: 界面只有名字 (输入框), 所以"只认在线玩家"这条规则要钉住 ——
 *       认不出来就明说认不出来, 不许猜、不许往名单里塞一个兑现不了的空 UUID;</li>
 *   <li><b>上限与"自己在名单里"</b>: 16 人上限与囤主本人不入名单这两条是名单自身的规矩, 界面上也得挡住。</li>
 * </ol>
 */
@GameTestHolder(MiningConstants.MODID)
@PrefixGameTestTemplate(false)
public final class GranaryTrustGameTests {

    private static final String EMPTY = "empty";
    private static final String BATCH = "granary_trust";

    /** 每个用例占一段互不重叠的地基。 */
    private static final int SLOT = 24;

    private static final int HIGH = 3;
    private static final int LOW = 1;

    /**
     * 这一批结束后把账本清干净: 粮仓账本是**全服共享**的存档数据, 留着会在后面别的批次
     * (尤其按 tick 哈希挑一座的自查用例) 里变成"不知道哪来的一座粮仓"。
     */
    @net.minecraft.gametest.framework.AfterBatch(batch = BATCH)
    public static void clearLedger(ServerLevel level) {
        GranaryTestLedger.clear(level);
    }

    // ============================================================
    // 1. 囤主的三步: 加 / 改 / 移出 / 转私有
    // ============================================================

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void ownerAddsChangesRemovesAndClearsTheList(GameTestHelper helper) {
        Fixture fixture = Fixture.accept(helper, 1);
        GranaryBinBlockEntity bin = fixture.bin(helper);
        ServerPlayer guest = mockNamed(helper, "trust_guest_1");

        // 加: 只存。
        GranaryTrustService.Result added = GranaryTrustService.apply(fixture.level().getServer(), bin,
                fixture.owner(), GranaryTrustService.Action.ADD, guest.getGameProfile().getName(),
                GranaryAccess.Permission.STORE_ONLY);
        helper.assertTrue(added.outcome() == GranaryTrustService.Outcome.ADDED,
                "囤主加人应当成功, 实得 " + added.outcome());
        helper.assertTrue(bin.access().size() == 1
                        && bin.access().get(0).permission() == GranaryAccess.Permission.STORE_ONLY,
                "名单里应当出现这个人与他这一档权限, 实得 " + bin.access());

        // 再点一次(改成能取): 同一份实现返回 UPDATED, 名单仍是一条。
        GranaryTrustService.Result updated = GranaryTrustService.apply(fixture.level().getServer(), bin,
                fixture.owner(), GranaryTrustService.Action.ADD, guest.getGameProfile().getName(),
                GranaryAccess.Permission.STORE_AND_TAKE);
        helper.assertTrue(updated.outcome() == GranaryTrustService.Outcome.UPDATED,
                "同一个人再点一次是改权限而不是加第二条, 实得 " + updated.outcome());
        helper.assertTrue(bin.access().size() == 1
                        && bin.access().get(0).permission() == GranaryAccess.Permission.STORE_AND_TAKE,
                "权限要改成能取, 实得 " + bin.access());

        // 移出。
        GranaryTrustService.Result removed = GranaryTrustService.apply(fixture.level().getServer(), bin,
                fixture.owner(), GranaryTrustService.Action.REMOVE, guest.getGameProfile().getName(),
                GranaryAccess.Permission.STORE_ONLY);
        helper.assertTrue(removed.outcome() == GranaryTrustService.Outcome.REMOVED && bin.access().isEmpty(),
                "移出之后名单应当空, 实得 " + removed.outcome() + " / " + bin.access());

        // 移一个不在名单里的人: 明说"他不在名单里", 而不是假装成功。
        GranaryTrustService.Result missing = GranaryTrustService.apply(fixture.level().getServer(), bin,
                fixture.owner(), GranaryTrustService.Action.REMOVE, guest.getGameProfile().getName(),
                GranaryAccess.Permission.STORE_ONLY);
        helper.assertTrue(missing.outcome() == GranaryTrustService.Outcome.NOT_LISTED,
                "不在名单里的人要说清楚, 实得 " + missing.outcome());

        // 转私有。
        GranaryTrustService.apply(fixture.level().getServer(), bin, fixture.owner(),
                GranaryTrustService.Action.ADD, guest.getGameProfile().getName(),
                GranaryAccess.Permission.STORE_AND_TAKE);
        GranaryTrustService.Result cleared = GranaryTrustService.apply(fixture.level().getServer(), bin,
                fixture.owner(), GranaryTrustService.Action.CLEAR, "", GranaryAccess.Permission.STORE_ONLY);
        helper.assertTrue(cleared.outcome() == GranaryTrustService.Outcome.CLEARED && bin.access().isEmpty(),
                "转私有之后名单应当空, 实得 " + cleared.outcome() + " / " + bin.access());
        helper.succeed();
    }

    // ============================================================
    // 2. 权限与认人
    // ============================================================

    /** 陌生人发同样的操作: 一律 NOT_OWNER, 名单一字不动 (界面、命令、包共用这一处判定)。 */
    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void strangersCannotTouchTheList(GameTestHelper helper) {
        Fixture fixture = Fixture.accept(helper, 2);
        GranaryBinBlockEntity bin = fixture.bin(helper);
        ServerPlayer stranger = mockNamed(helper, "trust_stranger_2");
        ServerPlayer victim = mockNamed(helper, "trust_victim_2");

        GranaryTrustService.Result add = GranaryTrustService.apply(fixture.level().getServer(), bin, stranger,
                GranaryTrustService.Action.ADD, victim.getGameProfile().getName(),
                GranaryAccess.Permission.STORE_AND_TAKE);
        helper.assertTrue(add.outcome() == GranaryTrustService.Outcome.NOT_OWNER && bin.access().isEmpty(),
                "陌生人加人必须被拒且名单不动, 实得 " + add.outcome() + " / " + bin.access());

        // 先由囤主加进去, 再让陌生人清空: 一样拒绝, 且那一条还在。
        GranaryTrustService.apply(fixture.level().getServer(), bin, fixture.owner(),
                GranaryTrustService.Action.ADD, victim.getGameProfile().getName(),
                GranaryAccess.Permission.STORE_ONLY);
        GranaryTrustService.Result clear = GranaryTrustService.apply(fixture.level().getServer(), bin, stranger,
                GranaryTrustService.Action.CLEAR, "", GranaryAccess.Permission.STORE_ONLY);
        helper.assertTrue(clear.outcome() == GranaryTrustService.Outcome.NOT_OWNER && bin.access().size() == 1,
                "陌生人清空必须被拒且名单不动, 实得 " + clear.outcome() + " / " + bin.access());
        helper.succeed();
    }

    /** 认人: 只认在线玩家 (与命令侧同口径); 认不出来就明说认不出来, 不许往名单里塞一个空 UUID。 */
    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void unknownNamesAreRefusedInsteadOfGuessed(GameTestHelper helper) {
        Fixture fixture = Fixture.accept(helper, 3);
        GranaryBinBlockEntity bin = fixture.bin(helper);

        GranaryTrustService.Result unknown = GranaryTrustService.apply(fixture.level().getServer(), bin,
                fixture.owner(), GranaryTrustService.Action.ADD, "no_such_player_ever",
                GranaryAccess.Permission.STORE_ONLY);
        helper.assertTrue(unknown.outcome() == GranaryTrustService.Outcome.UNKNOWN_PLAYER
                        && bin.access().isEmpty(),
                "unknownName outcome=" + unknown.outcome() + " listSize=" + bin.access().size()
                        + " resolved=" + GranaryTrustService.resolve(fixture.level().getServer(),
                        "no_such_player_ever")
                        + " onlineNames=" + onlineNames(fixture.level()));

        // 在线玩家按名字解析得到。
        ServerPlayer guest = mockNamed(helper, "trust_guest_3");
        helper.assertTrue(GranaryTrustService.resolve(fixture.level().getServer(),
                        guest.getGameProfile().getName()).orElse(null) != null,
                "在线玩家必须解析得出来");

        // 囤主本人: 加自己要被拒 (名单里不该出现自己)。
        GranaryTrustService.Result self = GranaryTrustService.apply(fixture.level().getServer(), bin,
                fixture.owner(), GranaryTrustService.Action.ADD, fixture.owner().getGameProfile().getName(),
                GranaryAccess.Permission.STORE_AND_TAKE);
        helper.assertTrue(self.outcome() == GranaryTrustService.Outcome.IS_OWNER,
                "把自己加进名单要被拒, 实得 " + self.outcome());
        helper.succeed();
    }

    // ============================================================
    // 3. 包与状态回执
    // ============================================================

    /** 两个包编解码逐字还原; 越界动作/权限在解码处兜回安全值; 状态回执带名字与权限。 */
    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void packetsRoundTripAndStateCarriesTheList(GameTestHelper helper) {
        GranaryTrustActionC2S request = new GranaryTrustActionC2S(9,
                GranaryTrustService.Action.REMOVE.ordinal(), "someone", 1);
        FriendlyByteBuf buf = new FriendlyByteBuf(io.netty.buffer.Unpooled.buffer());
        GranaryTrustActionC2S.encode(request, buf);
        GranaryTrustActionC2S decoded = GranaryTrustActionC2S.decode(buf);
        helper.assertTrue(decoded.windowId() == 9
                        && decoded.action() == GranaryTrustService.Action.REMOVE.ordinal()
                        && "someone".equals(decoded.playerName()) && decoded.permission() == 1,
                "请求包要逐字还原, 实得 " + decoded);

        // 越界值: 动作兜到最"无害"的那一档 (转私有), 权限兜到 0。
        FriendlyByteBuf hostile = new FriendlyByteBuf(io.netty.buffer.Unpooled.buffer());
        hostile.writeVarInt(1);
        hostile.writeVarInt(999);
        hostile.writeUtf("x", 32);
        hostile.writeVarInt(-5);
        GranaryTrustActionC2S clamped = GranaryTrustActionC2S.decode(hostile);
        helper.assertTrue(clamped.action() == GranaryTrustService.Action.CLEAR.ordinal()
                        && clamped.permission() == 0,
                "越界动作与权限都要兜底, 实得 " + clamped);

        // 状态回执: 名字 + 权限都在行里。
        Fixture fixture = Fixture.accept(helper, 4);
        GranaryBinBlockEntity bin = fixture.bin(helper);
        ServerPlayer guest = mockNamed(helper, "trust_guest_4");
        GranaryTrustService.Result result = GranaryTrustService.apply(fixture.level().getServer(), bin,
                fixture.owner(), GranaryTrustService.Action.ADD, guest.getGameProfile().getName(),
                GranaryAccess.Permission.STORE_AND_TAKE);
        List<String> lines = GranaryTrustService.lines(fixture.level().getServer(), result.trustees());
        helper.assertTrue(lines.size() == 1, "一条名单一行, 实得 " + lines.size());
        String row = textOf(lines.get(0));
        helper.assertTrue(row.contains("granary.trust.row") && row.contains("granary.access.store_take"),
                "行里要有名字与权限键, 实得 " + row);

        GranaryTrustStateS2C state = new GranaryTrustStateS2C(1, result.outcome().translationKey(), lines);
        FriendlyByteBuf out = new FriendlyByteBuf(io.netty.buffer.Unpooled.buffer());
        GranaryTrustStateS2C.encode(state, out);
        GranaryTrustStateS2C decodedState = GranaryTrustStateS2C.decode(out);
        helper.assertTrue(decodedState.lines().equals(lines)
                        && decodedState.outcomeKey().equals(result.outcome().translationKey()),
                "状态回执要逐字还原, 实得 " + decodedState);

        // 声称很多行的畸形回执: 按实际上限与实际可读截断。
        FriendlyByteBuf lie = new FriendlyByteBuf(io.netty.buffer.Unpooled.buffer());
        lie.writeVarInt(1);
        lie.writeUtf("", 128);
        lie.writeVarInt(9_999);
        lie.writeUtf("{\"text\":\"one\"}", 8_192);
        helper.assertTrue(GranaryTrustStateS2C.decode(lie).lines().size() <= GranaryAccess.MAX_TRUSTEES + 1,
                "回执行数要按上限截断");
        helper.succeed();
    }

    // ============================================================
    // 4. 输入框的预检
    // ============================================================

    /**
     * 预检只回答"如果现在加会怎么样", 不动名单; 它的答案要与真操作一致
     * (否则界面上写着"能加", 点下去却报别的)。
     */
    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void preCheckMatchesWhatTheRealActionWouldDo(GameTestHelper helper) {
        Fixture fixture = Fixture.accept(helper, 5);
        GranaryBinBlockEntity bin = fixture.bin(helper);
        MinecraftServer server = fixture.level().getServer();
        ServerPlayer owner = fixture.owner();
        ServerPlayer guest = mockNamed(helper, "trust_guest_5");

        helper.assertTrue(GranaryTrustService.check(server, bin, owner, "no_such_player_ever")
                        == GranaryTrustService.Outcome.UNKNOWN_PLAYER,
                "认不出的名字要预检成 UNKNOWN_PLAYER");
        helper.assertTrue(GranaryTrustService.check(server, bin, owner, owner.getGameProfile().getName())
                        == GranaryTrustService.Outcome.IS_OWNER,
                "自己要被预检成 IS_OWNER");
        helper.assertTrue(GranaryTrustService.check(server, bin, owner, guest.getGameProfile().getName())
                        == GranaryTrustService.Outcome.ADDED,
                "还没加过的人预检成 ADDED");

        // 真加一次之后, 同一个名字的预检要变成"改权限"。
        GranaryTrustService.apply(server, bin, owner, GranaryTrustService.Action.ADD,
                guest.getGameProfile().getName(), GranaryAccess.Permission.STORE_ONLY);
        helper.assertTrue(GranaryTrustService.check(server, bin, owner, guest.getGameProfile().getName())
                        == GranaryTrustService.Outcome.UPDATED,
                "已在名单里的人预检成 UPDATED");

        // 陌生人预检一律 NOT_OWNER。
        ServerPlayer stranger = mockNamed(helper, "trust_stranger_5");
        helper.assertTrue(GranaryTrustService.check(server, bin, stranger, guest.getGameProfile().getName())
                        == GranaryTrustService.Outcome.NOT_OWNER,
                "不是囤主时预检成 NOT_OWNER");

        // 两个包逐字还原。
        GranaryTrustCheckC2S request = new GranaryTrustCheckC2S(4, "trust_guest_5");
        FriendlyByteBuf buf = new FriendlyByteBuf(io.netty.buffer.Unpooled.buffer());
        GranaryTrustCheckC2S.encode(request, buf);
        helper.assertTrue(GranaryTrustCheckC2S.decode(buf).equals(request), "预检请求要逐字还原");
        GranaryTrustCheckS2C response = new GranaryTrustCheckS2C(4,
                GranaryTrustService.Outcome.UPDATED.translationKey(), "trust_guest_5");
        FriendlyByteBuf out = new FriendlyByteBuf(io.netty.buffer.Unpooled.buffer());
        GranaryTrustCheckS2C.encode(response, out);
        helper.assertTrue(GranaryTrustCheckS2C.decode(out).equals(response), "预检回执要逐字还原");
        helper.succeed();
    }

    // ============================================================
    // 夹具
    // ============================================================

    private record Fixture(ServerPlayer owner, BlockPos centerRelative, ServerLevel level) {

        static Fixture accept(GameTestHelper helper, int slot) {
            ServerPlayer owner = mockNamed(helper, "trust_owner_" + slot);
            setFarmerLevel(owner, 1);
            buildRoom(helper, slot, 3);
            placeCenter(helper, owner, center(slot));
            helper.setBlock(vent(slot), GranaryBlocks.VENT.get());
            helper.assertTrue(GranaryInspection.accept(helper.getLevel(), helper.absolutePos(center(slot)), owner)
                    .accepted(), "夹具: 先验收成粮棚");
            placeBin(helper, owner, binRel(slot));
            return new Fixture(owner, center(slot), helper.getLevel());
        }

        GranaryBinBlockEntity bin(GameTestHelper helper) {
            return (GranaryBinBlockEntity) helper.getBlockEntity(binRelative());
        }

        BlockPos binRelative() {
            return new BlockPos(centerRelative.getX() - 1, centerRelative.getY(), centerRelative.getZ() - 1);
        }
    }

    /**
     * 造一位**有名字**的 mock 玩家。
     *
     * <p>开放设置页是按**名字**认人的 (玩家在输入框里打的就是名字), 而 {@link MockGameTestPlayers} 造的
     * mock 全都叫 {@code test-mock-player} —— 名字重复时 {@code getPlayerByName} 只还名单里的第一个人,
     * 于是"囤主把自己加进名单"会解析到别人身上 (实测: 期望 {@code IS_OWNER} 却拿到 {@code ADDED}),
     * 连"加的人到底是不是他"都无从断言。名字给到唯一, 这条路上的判定才真的被测到。</p>
     */
    private static ServerPlayer mockNamed(GameTestHelper helper, String name) {
        return MockGameTestPlayers.makeMockServerPlayerWithChannel(helper,
                new com.mojang.authlib.GameProfile(java.util.UUID.randomUUID(), name));
    }

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

    private static BlockPos center(int slot) {
        int base = slot * SLOT;
        return new BlockPos(base + 2, LOW, 2);
    }

    private static BlockPos binRel(int slot) {
        int base = slot * SLOT;
        return new BlockPos(base + 1, LOW, 1);
    }

    private static BlockPos vent(int slot) {
        int base = slot * SLOT;
        return new BlockPos(base + 3, HIGH, 3);
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

    /** 诊断用: 当前在线的玩家名 (失败时一眼看出是不是被别的用例的 mock 玩家干扰)。 */
    private static java.util.List<String> onlineNames(ServerLevel level) {
        java.util.List<String> names = new java.util.ArrayList<>();
        level.getServer().getPlayerList().getPlayers()
                .forEach(player -> names.add(player.getGameProfile().getName()));
        return names;
    }

    private static String textOf(String json) {
        Component component = Component.Serializer.fromJson(json);
        return component == null ? "" : component.toString();
    }
}
