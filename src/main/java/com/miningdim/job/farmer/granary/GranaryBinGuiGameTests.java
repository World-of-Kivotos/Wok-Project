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
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.common.util.BlockSnapshot;
import net.minecraftforge.event.level.BlockEvent;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.List;
import java.util.UUID;

/**
 * 储粮囤 27 格界面的 GameTest (docs/Spoilage_Granary_DesignSpec.md「界面」「谁管什么」)。
 *
 * 锁四件事: 界面**逐槽**按开放名单放行 (只存的人放得进拿不出、可存取的人两样都行、名单之外 —— 连房主 ——
 * 两样都不行, 而且名单改了立刻生效)、右键只有该开的人开得了、**经界面放进去的东西与一键存入完全一样**
 * (当场盖章 + 记入仓时刻 + 开始停表)、拿空之后那一格的账跟着清掉。
 *
 * 界面本身 (贴图/按钮) 在 GameTest 里看不到, 这里验的是**服务端这一半**: 槽的放行判定、快捷移动、
 * 打开路径与账目接线。
 */
@GameTestHolder(MiningConstants.MODID)
@PrefixGameTestTemplate(false)
public final class GranaryBinGuiGameTests {

    private static final String EMPTY = "empty";
    private static final String BATCH = "granary_bin_gui";

    /** 这一批结束后把账本清干净 (粮仓账本是全服共享的存档数据, 留着会干扰后面的批次与下一轮的自查)。 */
    @net.minecraft.gametest.framework.AfterBatch(batch = BATCH)
    public static void clearLedger(ServerLevel level) {
        GranaryTestLedger.clear(level);
    }

    /** 每条用例占自己的一条槽位 (同一批次的用例并行跑且共用区域)。 */
    private static final int SLOT = 16;

    private static final int LOW = 1;
    private static final int HIGH = 3;

    /** 容器槽 0..26, 玩家背包 27..62 (主背包 27..53 + 快捷栏 54..62)。 */
    private static final int CONTAINER_SLOTS = 27;
    private static final int PLAYER_FIRST = 27;
    private static final int HOTBAR_LAST = 62;

    // ============================================================
    // 形状与注册
    // ============================================================

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void theMenuIsRegisteredAndHasTheBinShape(GameTestHelper helper) {
        Fixture fixture = accepted(helper, 0);
        GranaryBinMenu menu = fixture.menu();
        helper.assertTrue(ForgeRegistries.MENU_TYPES.getValue(
                        new ResourceLocation(MiningConstants.MODID, "granary_bin")) == GranaryMenus.BIN.get(),
                "MenuType 要登记进注册表 (客户端靠它找 Screen)");
        helper.assertTrue(menu.containerSlotCount() == CONTAINER_SLOTS, "容器槽 27 个, 实际 "
                + menu.containerSlotCount());
        helper.assertTrue(menu.slots.size() == CONTAINER_SLOTS + 36, "加玩家 36 槽共 63, 实际 "
                + menu.slots.size());
        helper.assertTrue(menu.slots.get(0).x == 8 && menu.slots.get(0).y == 18,
                "第一格在 (8, 18): " + menu.slots.get(0).x + "," + menu.slots.get(0).y);
        helper.assertTrue(menu.slots.get(CONTAINER_SLOTS).x == 8 && menu.slots.get(CONTAINER_SLOTS).y == 84,
                "玩家背包从 (8, 84) 起");
        helper.assertTrue(menu.slots.get(HOTBAR_LAST).x == 152 && menu.slots.get(HOTBAR_LAST).y == 142,
                "快捷栏最后一格在 (152, 142): " + menu.slots.get(HOTBAR_LAST).x + ","
                        + menu.slots.get(HOTBAR_LAST).y);
        helper.succeed();
    }

    // ============================================================
    // 逐槽放行
    // ============================================================

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void everySlotFollowsTheAccessListLive(GameTestHelper helper) {
        Fixture fixture = accepted(helper, 1);
        GranaryBinBlockEntity bin = fixture.bin(helper);
        ServerPlayer owner = fixture.owner();
        ServerPlayer guest = fixture.guest();
        ServerPlayer stranger = fixture.stranger();

        // 私有: 只有囤主能放能拿。
        helper.assertTrue(allMayPlace(fixture.menuFor(owner), true), "囤主放得进");
        helper.assertTrue(allMayPickup(fixture.menuFor(owner), true), "囤主拿得出");
        helper.assertTrue(allMayPlace(fixture.menuFor(guest), false), "私有囤: 成员也放不进");
        helper.assertTrue(allMayPlace(fixture.menuFor(stranger), false), "私有囤: 陌生人放不进");

        // 开放给客人「只存」: 放得进、拿不出; 陌生人照旧不行。
        bin.addTrustee(guest.getUUID(), GranaryAccess.Permission.STORE_ONLY);
        GranaryBinMenu guestMenu = fixture.menuFor(guest);
        helper.assertTrue(allMayPlace(guestMenu, true), "「只存」的人放得进");
        helper.assertTrue(allMayPickup(guestMenu, false), "「只存」的人拿不出");
        helper.assertTrue(allMayPlace(fixture.menuFor(stranger), false), "名单外还是不行");

        // 改成「可存可取」: 同一个界面实例立刻放行 (判定是现算的)。
        bin.addTrustee(guest.getUUID(), GranaryAccess.Permission.STORE_AND_TAKE);
        helper.assertTrue(allMayPickup(guestMenu, true), "改成可存可取之后拿得出了 (不用重开界面)");

        // 名单清空: 立刻收回。
        bin.setAccess(List.of());
        helper.assertTrue(allMayPlace(guestMenu, false) && allMayPickup(guestMenu, false),
                "名单清空之后立刻收回 (开着的界面也一样)");
        helper.succeed();
    }

    // ============================================================
    // 快捷移动
    // ============================================================

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void quickMoveFollowsTheList(GameTestHelper helper) {
        Fixture fixture = accepted(helper, 2);
        GranaryBinBlockEntity bin = fixture.bin(helper);
        ServerPlayer guest = fixture.guest();
        ServerPlayer stranger = fixture.stranger();

        // 陌生人: Shift 移不进去, 东西留在原地。
        stranger.getInventory().setItem(9, new ItemStack(Items.WHEAT, 8));
        GranaryBinMenu strangerMenu = fixture.menuFor(stranger);
        helper.assertTrue(strangerMenu.quickMoveStack(stranger, PLAYER_FIRST).isEmpty(),
                "名单外的人 Shift 移不进去");
        helper.assertTrue(bin.isEmpty(), "囤里还是空的");
        helper.assertTrue(stranger.getInventory().getItem(9).getCount() == 8, "东西留在背包里");

        // 名单上「只存」的人: Shift 移得进去, 而且账目从这一刻起算。
        bin.addTrustee(guest.getUUID(), GranaryAccess.Permission.STORE_ONLY);
        guest.getInventory().setItem(9, new ItemStack(Items.WHEAT, 8));
        GranaryBinMenu guestMenu = fixture.menuFor(guest);
        helper.assertTrue(!guestMenu.quickMoveStack(guest, PLAYER_FIRST).isEmpty(), "名单上的人 Shift 移得进去");
        helper.assertTrue(bin.getItem(0).is(Items.WHEAT) && bin.getItem(0).getCount() == 8,
                "8 个小麦进了第一格");
        helper.assertTrue(guest.getInventory().getItem(9).isEmpty(), "背包那一格空了");
        helper.assertTrue(bin.entryHour(0) == GranaryClock.nowHour(fixture.level()),
                "入仓时刻按本小时记 (与一键存入同一条账)");

        // 「只存」的人 Shift 拿不出来 (容器 -> 玩家那一路也要过 mayPickup)。
        helper.assertTrue(guestMenu.quickMoveStack(guest, 0).isEmpty(), "「只存」的人 Shift 拿不出来");
        helper.assertTrue(bin.getItem(0).getCount() == 8, "东西还在囤里");
        helper.succeed();
    }

    // ============================================================
    // 账目接线
    // ============================================================

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void guiInsertStartsTheClockAndTakeClearsIt(GameTestHelper helper) {
        Fixture fixture = accepted(helper, 3);
        GranaryBinBlockEntity bin = fixture.bin(helper);
        ServerLevel level = fixture.level();
        GranaryBinMenu menu = fixture.menu();

        ItemStack stack = new ItemStack(Items.BREAD, 4);
        menu.slots.get(0).set(stack);

        helper.assertTrue(bin.getItem(0).is(Items.BREAD) && bin.getItem(0).getCount() == 4, "面包进了第一格");
        helper.assertTrue(FreshnessData.isStamped(bin.getItem(0)), "经界面放进去的当场盖章 (与一键存入一样)");
        long nowHour = GranaryClock.nowHour(level);
        helper.assertTrue(bin.entryHour(0) == nowHour, "记下了入仓时刻, 实际 " + bin.entryHour(0));

        // 结算按囤里那一套算术走 (存满 24 小时才起算、按存放小时 − 1 记)。
        bin.settleAll(nowHour + 100L);
        helper.assertTrue(bin.creditedHours(0) == 99, "100 小时后记 99 小时, 实际 " + bin.creditedHours(0));
        helper.assertTrue(FreshnessData.stoppedHours(bin.getItem(0)) == 99, "停表小时写在物品上");

        // 拿空: 那一格的账跟着清掉 (下一个放进来的人从零开始)。
        ItemStack taken = menu.slots.get(0).remove(4);
        helper.assertTrue(taken.getCount() == 4, "四件都拿出来了");
        helper.assertTrue(bin.getItem(0).isEmpty(), "格子空了");
        helper.assertTrue(bin.entryHour(0) == 0L && bin.creditedHours(0) == 0, "账目清掉");

        // 往**有东西**的格子追加不动账 (老格子的入仓时刻不能被顶掉)。
        menu.slots.get(1).set(new ItemStack(Items.BREAD, 2));
        long firstEntry = bin.entryHour(1);
        ItemStack appended = bin.getItem(1).copy();
        appended.setCount(4);
        menu.slots.get(1).set(appended);
        helper.assertTrue(bin.entryHour(1) == firstEntry, "追加不动入仓时刻");
        helper.succeed();
    }

    // ============================================================
    // 打开路径
    // ============================================================

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void rightClickOpensOnlyForAllowedPlayers(GameTestHelper helper) {
        Fixture fixture = accepted(helper, 4);
        GranaryBinBlockEntity bin = fixture.bin(helper);
        BlockPos binPos = helper.absolutePos(bin(4, LOW, LOW));
        ServerPlayer owner = fixture.owner();
        ServerPlayer guest = fixture.guest();
        ServerPlayer stranger = fixture.stranger();

        // 私有囤: 成员与陌生人都开不了 (房主开自己的当然可以)。
        helper.assertTrue(use(bin, binPos, stranger) == InteractionResult.CONSUME, "被拒也是服务端处理");
        helper.assertTrue(!(stranger.containerMenu instanceof GranaryBinMenu), "名单外的人菜单没开");
        helper.assertTrue(!(guest.containerMenu instanceof GranaryBinMenu), "成员在私有囤上也开不了");

        InteractionResult opened = use(bin, binPos, owner);
        helper.assertTrue(opened == InteractionResult.CONSUME, "囤主右键由服务端处理, 实际 " + opened);
        helper.assertTrue(owner.containerMenu instanceof GranaryBinMenu,
                "囤主的菜单开起来了, 实际 " + owner.containerMenu.getClass().getSimpleName());
        helper.assertTrue(((GranaryBinMenu) owner.containerMenu).blockEntity() == bin, "开的是那个囤");
        helper.assertTrue(((GranaryBinMenu) owner.containerMenu).mayStore(), "囤主当然能存");

        // 开放给客人「可存可取」之后, 客人也开得了。
        bin.addTrustee(guest.getUUID(), GranaryAccess.Permission.STORE_AND_TAKE);
        use(bin, binPos, guest);
        helper.assertTrue(guest.containerMenu instanceof GranaryBinMenu, "名单上的人开得了");
        helper.assertTrue(((GranaryBinMenu) guest.containerMenu).mayTake(), "可存可取的人能取");
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void theMenuClosesWhenThePlayerWalksAway(GameTestHelper helper) {
        Fixture fixture = accepted(helper, 5);
        ServerPlayer owner = fixture.owner();
        BlockPos binPos = helper.absolutePos(bin(5, LOW, LOW));
        GranaryBinMenu menu = fixture.menu();

        owner.moveTo(binPos.getX() + 0.5D, binPos.getY(), binPos.getZ() + 1.5D);
        helper.assertTrue(menu.stillValid(owner), "站在囤边上界面有效");
        owner.moveTo(binPos.getX() + 0.5D, binPos.getY() + 30.0D, binPos.getZ() + 0.5D);
        helper.assertTrue(!menu.stillValid(owner), "走远了界面自动关 (原版按 8 格判)");
        helper.succeed();
    }

    // ============================================================
    // 夹具
    // ============================================================

    /** 一座验收过的粮棚 + 房主的囤 (启用状态, 所以停表账真在走) + 已入册的客人 + 陌生人。 */
    private record Fixture(ServerLevel level, ServerPlayer owner, ServerPlayer guest, ServerPlayer stranger,
                           GranaryBinBlockEntity binEntity, BlockPos centerPos) {

        GranaryBinBlockEntity bin(GameTestHelper helper) {
            return binEntity;
        }

        GranaryBinMenu menu() {
            return new GranaryBinMenu(1, owner.getInventory(), binEntity);
        }

        /** 给某位玩家开一份菜单 (服务端构造; 与右键开出来的是同一个类与同一套槽)。 */
        GranaryBinMenu menuFor(ServerPlayer player) {
            return new GranaryBinMenu(1, player.getInventory(), binEntity);
        }
    }

    private static Fixture accepted(GameTestHelper helper, int slot) {
        ServerLevel level = helper.getLevel();
        ServerPlayer owner = player(helper, "guiowner");
        ServerPlayer guest = player(helper, "guiguest");
        ServerPlayer stranger = player(helper, "guistranger");
        buildRoom(helper, slot, 3);
        placeCenter(helper, owner, center(slot));
        placeBin(helper, owner, bin(slot, LOW, LOW));
        helper.setBlock(vent(slot), GranaryBlocks.VENT.get());
        BlockPos centerPos = helper.absolutePos(center(slot));
        long gid = GranarySystem.granaryAt(level, centerPos);
        GranarySavedData data = GranarySavedData.get(level);
        data.putGranary(data.granary(gid).withMember(guest.getUUID()));
        helper.assertTrue(GranaryInspection.accept(level, centerPos, owner).accepted(),
                "fixture: accepted as shed");
        GranaryBinBlockEntity bin = (GranaryBinBlockEntity) level.getBlockEntity(
                helper.absolutePos(bin(slot, LOW, LOW)));
        helper.assertTrue(bin != null, "fixture: bin block entity exists");
        return new Fixture(level, owner, guest, stranger, bin, centerPos);
    }

    private static InteractionResult use(GranaryBinBlockEntity bin, BlockPos pos, ServerPlayer player) {
        return GranaryBlocks.BIN.get().use(bin.getBlockState(), bin.getLevel(), pos, player,
                InteractionHand.MAIN_HAND,
                new BlockHitResult(Vec3.atCenterOf(pos), net.minecraft.core.Direction.UP, pos, false));
    }

    private static boolean allMayPlace(GranaryBinMenu menu, boolean expected) {
        for (int i = 0; i < CONTAINER_SLOTS; i++) {
            if (menu.slots.get(i).mayPlace(new ItemStack(Items.WHEAT)) != expected) {
                return false;
            }
        }
        return true;
    }

    private static boolean allMayPickup(GranaryBinMenu menu, boolean expected) {
        for (int i = 0; i < CONTAINER_SLOTS; i++) {
            if (menu.slots.get(i).mayPickup(menu.viewer()) != expected) {
                return false;
            }
        }
        return true;
    }

    private static ServerPlayer player(GameTestHelper helper, String name) {
        String unique = name + "_" + UUID.randomUUID().toString().substring(0, 6);
        ServerPlayer player = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper,
                new com.mojang.authlib.GameProfile(UUID.randomUUID(), unique));
        com.miningdim.entry.MiningCapabilities.get(player)
                .orElseThrow(() -> new IllegalStateException("mock 玩家未挂载 capability"))
                .jobProgress(JobId.FARMER)
                .setLevel(1);
        return player;
    }

    private static void placeCenter(GameTestHelper helper, ServerPlayer player, BlockPos relative) {
        ServerLevel level = helper.getLevel();
        BlockPos pos = helper.absolutePos(relative);
        GranarySystem.releaseCenter(level, pos);
        helper.setBlock(relative, GranaryBlocks.CENTER.get());
        BlockEvent.EntityPlaceEvent event = new BlockEvent.EntityPlaceEvent(
                BlockSnapshot.create(level.dimension(), level, pos), Blocks.AIR.defaultBlockState(), player);
        GranarySystem.onBlockPlaced(event);
        helper.assertTrue(!event.isCanceled(), "fixture: center placeable");
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
        helper.assertTrue(!event.isCanceled(), "fixture: bin placeable");
        GranaryBlocks.BIN.get().setPlacedBy(level, pos, level.getBlockState(pos), player, ItemStack.EMPTY);
    }

    private static BlockPos center(int slot) {
        return new BlockPos(slot * SLOT + 2, 2, 2);
    }

    private static BlockPos vent(int slot) {
        return new BlockPos(slot * SLOT, 2, 2);
    }

    private static BlockPos bin(int slot, int x, int z) {
        return new BlockPos(slot * SLOT + x, LOW, z);
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
}
