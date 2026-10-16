package com.miningdim.job.farmer.granary;

import com.miningdim.core.MiningConstants;
import com.miningdim.freshness.FreshnessData;
import com.miningdim.job.JobId;
import com.miningdim.job.farmer.granary.block.GranaryBinBlockEntity;
import com.miningdim.job.farmer.granary.block.GranaryBlocks;
import com.miningdim.testutil.MockGameTestPlayers;
import net.minecraft.commands.CommandSource;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
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

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 待领粮单搬运的 GameTest (docs/Spoilage_Granary_DesignSpec.md「退出、拆走、移出」「有货的囤被移除」)。
 *
 * 锁五件事: 被移出成员的囤**连东西一起**进他自己的待领粮单 (方块没了、额度归还、房主拿不走)、
 * 自己退出的同样进单子但**不停表**、被移出的在单子里**照常停表 7 天** (过了窗口就不再记)、
 * 非玩家路径 (指令 / 爆炸) 拆掉有货的囤时东西**不掉在地上**而是进单子、房主拆未入册的空囤时
 * 囤进放置者的单子 (房主拿不走)。
 */
@GameTestHolder(MiningConstants.MODID)
@PrefixGameTestTemplate(false)
public final class GranaryTransferGameTests {

    private static final String EMPTY = "empty";
    private static final String BATCH = "granary_transfer";

    /** 每条用例占自己的一条槽位 (同一批次的用例并行跑且共用区域)。 */
    private static final int SLOT = 16;

    private static final int LOW = 1;
    private static final int HIGH = 3;

    // ============================================================
    // 被移出 / 自己退出
    // ============================================================

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void removedMemberKeepsTheirStuffInThePendingList(GameTestHelper helper) {
        int slot = 0;
        Fixture fixture = accepted(helper, slot);
        ServerPlayer owner = fixture.owner();
        ServerPlayer guest = fixture.guest();
        ServerLevel level = fixture.level();
        GranarySavedData data = GranarySavedData.get(level);
        long gid = fixture.gid();

        // 客人放一个囤并塞两件东西进去 (小麦盖章, 石头不腐烂)。
        placeBin(helper, guest, bin(slot, HIGH, HIGH));
        BlockPos guestBin = helper.absolutePos(bin(slot, HIGH, HIGH));
        fill(helper, guestBin, new ItemStack(Items.WHEAT, 7), new ItemStack(Items.STONE, 3));
        fixture.refresh(helper);
        helper.assertTrue(data.binState(level.dimension().location(), guestBin) == GranaryBinState.ACTIVE,
                "入册之后客人的囤是启用的");

        // 房主把客人移出: 囤连东西一起进客人的待领粮单。
        owner.moveTo(fixture.centerPos().getX() + 1.5D, fixture.centerPos().getY(),
                fixture.centerPos().getZ() + 0.5D);
        Capture output = new Capture();
        helper.assertTrue(server(helper).getCommands().performPrefixedCommand(owner.createCommandSourceStack()
                .withSource(output), "granary member remove " + guest.getGameProfile().getName()) == 0,
                "移出成员");
        helper.assertTrue(output.keys().contains("message.miningdim.granary.command.member_moved"),
                "回执要说清搬了几个囤: " + output.joined());

        helper.assertTrue(!data.granary(gid).isMember(guest.getUUID()), "名单上没有他了");
        helper.assertTrue(data.ownerOf(level.dimension().location(), guestBin) == null,
                "囤的归属登记归还了 (额度还给他)");
        helper.assertTrue(level.getBlockState(guestBin).isAir(), "方块从世界里拿掉了");
        List<GranaryPending.Entry> pending = data.pendingOf(guest.getUUID());
        helper.assertTrue(pending.size() == 1, "客人的单子上有一条, 实际 " + pending.size());
        GranaryPending.Entry entry = pending.get(0);
        helper.assertTrue(entry.reason() == GranaryPending.Reason.REMOVED, "来路是被移出");
        helper.assertTrue(entry.gid() == gid, "记得原来在哪座粮仓");
        // 一个囤装了两件东西 + 囤本身 = 三堆 (囤也还给放置者)。
        helper.assertTrue(entry.items().size() == 3, "两件东西 + 囤本身, 实际 " + entry.count());
        helper.assertTrue(entry.count() == 11, "7 个小麦 + 3 个石头 + 1 个囤, 实际 " + entry.count());
        helper.assertTrue(entry.graceUntilHour() > entry.atHour(), "不是自己造成的: 单子里照常停表");
        helper.assertTrue(entry.items().stream().anyMatch(stack -> stack.is(Items.WHEAT)
                && stack.getCount() == 7), "小麦原样在单子上");
        helper.assertTrue(GranaryInspection.inspect(level, fixture.centerPos()).enablement().enabled() == 1,
                "粮仓里只剩房主那一个囤");
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void leavingOnYourOwnDoesNotPauseTheClock(GameTestHelper helper) {
        int slot = 1;
        Fixture fixture = accepted(helper, slot);
        ServerPlayer guest = fixture.guest();
        ServerLevel level = fixture.level();
        GranarySavedData data = GranarySavedData.get(level);

        placeBin(helper, guest, bin(slot, HIGH, HIGH));
        BlockPos guestBin = helper.absolutePos(bin(slot, HIGH, HIGH));
        fill(helper, guestBin, new ItemStack(Items.WHEAT, 4));
        fixture.refresh(helper);

        guest.moveTo(fixture.centerPos().getX() + 1.5D, fixture.centerPos().getY(),
                fixture.centerPos().getZ() + 0.5D);
        Capture output = new Capture();
        helper.assertTrue(server(helper).getCommands().performPrefixedCommand(guest.createCommandSourceStack()
                .withSource(output), "granary member leave") == 1, "成员能自己退出");
        helper.assertTrue(output.keys().contains("message.miningdim.granary.command.member_took_away"),
                "回执要说清东西进了自己的单子: " + output.joined());

        List<GranaryPending.Entry> pending = data.pendingOf(guest.getUUID());
        helper.assertTrue(pending.size() == 1, "有一条");
        helper.assertTrue(pending.get(0).reason() == GranaryPending.Reason.LEFT, "来路是自己退出");
        helper.assertTrue(pending.get(0).graceUntilHour() == 0L, "自己退出的不停表");
        helper.assertTrue(!data.granary(fixture.gid()).isMember(guest.getUUID()), "退出之后不在名单上");
        helper.assertTrue(level.getBlockState(guestBin).isAir(), "囤也搬走了");
        helper.succeed();
    }

    // ============================================================
    // 单子里的停表
    // ============================================================

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void pendingWithinTheGraceKeepsStoppingTheClock(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        GranarySavedData data = GranarySavedData.get(level);
        UUID player = UUID.randomUUID();
        ItemStack bread = new ItemStack(Items.BREAD, 1);
        FreshnessData.stampAt(bread, GranaryClock.nowMinute(level), 0);

        long nowHour = GranaryClock.nowHour(level);
        // 被移出: 进单子那一刻起 7 天内在单子里照常停表 (小时数按「存放小时 − 1」记)。
        long deadline = nowHour + GranaryGrace.DAMAGE_HOURS;
        GranaryPending.put(data, player, 1L, GranaryPending.Reason.REMOVED, nowHour, deadline, List.of(bread));
        helper.assertTrue(data.pendingOf(player).get(0).stopsClock(nowHour + 1L), "宽限里在停表");

        // 过了 100 小时: 该记的小时记上 (100 − 1 = 99)。
        helper.assertTrue(GranaryPending.settle(data, player, nowHour + 100L) == 1, "结算动了这一条");
        int credited = FreshnessData.stoppedHours(data.pendingOf(player).get(0).items().get(0));
        helper.assertTrue(credited == 99, "记了 99 小时, 实际 " + credited);

        // 再结算一次不该重复记 (账本上的 C 记住了)。
        helper.assertTrue(GranaryPending.settle(data, player, nowHour + 100L) == 0, "同一次结算不重复记");
        helper.assertTrue(FreshnessData.stoppedHours(data.pendingOf(player).get(0).items().get(0)) == 99,
                "小时数没涨");

        // 过了宽限窗口: 一行都不再记 (窗口里最多记到截止那一刻)。
        GranaryPending.settle(data, player, deadline + 500L);
        int afterGrace = FreshnessData.stoppedHours(data.pendingOf(player).get(0).items().get(0));
        helper.assertTrue(afterGrace == (int) (GranaryGrace.DAMAGE_HOURS - 1L),
                "记到窗口末尾为止 (168 − 1 = 167), 实际 " + afterGrace);

        // 自己退出的那一类: 一次都不记。
        UUID selfLeft = UUID.randomUUID();
        ItemStack wheat = new ItemStack(Items.WHEAT, 1);
        FreshnessData.stampAt(wheat, GranaryClock.nowMinute(level), 0);
        GranaryPending.put(data, selfLeft, 2L, GranaryPending.Reason.LEFT, nowHour, List.of(wheat));
        helper.assertTrue(GranaryPending.settle(data, selfLeft, nowHour + 100L) == 0, "自己退出的不停表");
        helper.assertTrue(FreshnessData.stoppedHours(data.pendingOf(selfLeft).get(0).items().get(0)) == 0,
                "小时数还是 0");
        helper.assertTrue(!data.pendingOf(selfLeft).get(0).stopsClock(nowHour + 100L), "窗口是 0");

        data.setPending(player, List.of());
        data.setPending(selfLeft, List.of());
        helper.succeed();
    }

    // ============================================================
    // 非玩家路径与房主拆未入册的囤
    // ============================================================

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void nonPlayerBreakMovesTheContentsIntoTheList(GameTestHelper helper) {
        int slot = 2;
        Fixture fixture = accepted(helper, slot);
        ServerLevel level = fixture.level();
        GranarySavedData data = GranarySavedData.get(level);
        ServerPlayer guest = fixture.guest();

        placeBin(helper, guest, bin(slot, HIGH, HIGH));
        BlockPos guestBin = helper.absolutePos(bin(slot, HIGH, HIGH));
        fill(helper, guestBin, new ItemStack(Items.BREAD, 6));
        fixture.refresh(helper);

        // 非玩家路径 (指令 / 爆炸 / 活塞): 直接换掉方块。
        helper.setBlock(bin(slot, HIGH, HIGH), Blocks.AIR);
        level.removeBlock(guestBin, false);

        helper.assertTrue(level.getBlockState(guestBin).isAir(), "T-break: block is gone");
        List<GranaryPending.Entry> pending = data.pendingOf(guest.getUUID());
        helper.assertTrue(pending.size() == 1, "东西进了囤主的待领粮单, 实际 " + pending.size()
                + " 条: " + describe(pending));
        helper.assertTrue(pending.get(0).reason() == GranaryPending.Reason.BROKEN,
                "来路是被拆走, 实际 " + pending.get(0).reason() + " (items=" + pending.get(0).count() + ")");
        helper.assertTrue(pending.get(0).items().stream()
                        .anyMatch(stack -> stack.is(Items.BREAD) && stack.getCount() == 6),
                "T-break: bread inside, count=" + pending.get(0).count());
        helper.assertTrue(data.ownerOf(level.dimension().location(), guestBin) == null, "T-break: ownership released");
        // 地上不该有**这一批**东西的掉落物 (规格: 东西从不掉在地上, 拆的人拿不走)。
        // 只认自己那几件: 相邻用例区域里别的测试掉的杂物也在同一个维度里, 按包围盒一概而论会误伤。
        List<net.minecraft.world.entity.item.ItemEntity> nearby =
                level.getEntitiesOfClass(net.minecraft.world.entity.item.ItemEntity.class,
                        new net.minecraft.world.phys.AABB(guestBin).inflate(4.0D));
        helper.assertTrue(nearby.stream().noneMatch(drop -> drop.getItem().is(Items.BREAD)),
                "T-break: bread must not drop, nearby entities=" + nearby.size());
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void theOwnerBreakingAStrangersBinSendsItToThePlacer(GameTestHelper helper) {
        int slot = 3;
        Fixture fixture = accepted(helper, slot);
        ServerLevel level = fixture.level();
        GranarySavedData data = GranarySavedData.get(level);
        ServerPlayer owner = fixture.owner();
        ServerPlayer stranger = fixture.stranger();

        // 陌生人 (没入册) 在屋里放一个空囤。
        placeBin(helper, stranger, bin(slot, HIGH, HIGH));
        BlockPos strangerBin = helper.absolutePos(bin(slot, HIGH, HIGH));
        fixture.refresh(helper);
        helper.assertTrue(!data.granary(fixture.gid()).isMember(stranger.getUUID()), "他没入册");

        BlockState state = level.getBlockState(strangerBin);
        boolean broken = GranaryBlocks.BIN.get().onDestroyedByPlayer(state, level, strangerBin, owner, false,
                state.getFluidState());
        helper.assertTrue(!broken, "房主这一下由搬运处理, 不再走原版的掉落");
        helper.assertTrue(level.getBlockState(strangerBin).isAir(), "方块拿掉了");
        List<GranaryPending.Entry> pending = data.pendingOf(stranger.getUUID());
        helper.assertTrue(pending.size() == 1, "囤进了放置者的待领粮单, 实际 " + pending.size());
        helper.assertTrue(pending.get(0).items().size() == 1
                        && pending.get(0).items().get(0).is(GranaryBlocks.BIN.get().asItem()),
                "单子上就是那个囤本身");
        helper.assertTrue(data.ownerOf(level.dimension().location(), strangerBin) == null,
                "归属归还: 房主拿不走, 也不用替别人占额度");
        helper.succeed();
    }

    // ============================================================
    // 夹具
    // ============================================================

    /** 一座验收过的粮棚: 房主 + 已入册的客人 (+ 一个没入册的陌生人)。 */
    private record Fixture(int slot, ServerLevel level, ServerPlayer owner, ServerPlayer guest,
                           ServerPlayer stranger, long gid, BlockPos centerPos) {

        /** 结构变了之后重算一次 (验收要用)。 */
        void refresh(GameTestHelper helper) {
            GranaryInspection.inspect(level, centerPos);
        }
    }

    private static Fixture accepted(GameTestHelper helper, int slot) {
        ServerLevel level = helper.getLevel();
        ServerPlayer owner = player(helper, "trowner");
        ServerPlayer guest = player(helper, "trguest");
        ServerPlayer stranger = player(helper, "trstranger");
        buildRoom(helper, slot, 3);
        placeCenter(helper, owner, center(slot));
        placeBin(helper, owner, bin(slot, LOW, LOW));
        helper.setBlock(vent(slot), GranaryBlocks.VENT.get());
        BlockPos centerPos = helper.absolutePos(center(slot));
        long gid = GranarySystem.granaryAt(level, centerPos);
        GranarySavedData data = GranarySavedData.get(level);
        data.putGranary(data.granary(gid).withMember(guest.getUUID()));
        helper.assertTrue(GranaryInspection.accept(level, centerPos, owner).accepted(), "fixture: accepted as shed");
        return new Fixture(slot, level, owner, guest, stranger, gid, centerPos);
    }

    /** 往一个囤的方块实体里直接放东西。 */
    private static void fill(GameTestHelper helper, BlockPos pos, ItemStack... stacks) {
        helper.assertTrue(helper.getLevel().getBlockEntity(pos) instanceof GranaryBinBlockEntity,
                "fixture: bin block entity expected");
        GranaryBinBlockEntity bin = (GranaryBinBlockEntity) helper.getLevel().getBlockEntity(pos);
        for (int i = 0; i < stacks.length; i++) {
            // 先盖章再放进去: setItem 会拷贝一份, 盖在原来那一份上等于没盖。
            FreshnessData.stamp(stacks[i], GranaryClock.nowMinute(helper.getLevel()));
            bin.setItem(i, stacks[i]);
        }
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

    private static net.minecraft.server.MinecraftServer server(GameTestHelper helper) {
        return helper.getLevel().getServer();
    }

    /** 单子的可读摘要 (失败信息里用 ASCII, 免得日志编码把中文糊掉)。 */
    private static String describe(List<GranaryPending.Entry> entries) {
        List<String> parts = new ArrayList<>();
        for (GranaryPending.Entry entry : entries) {
            List<String> items = new ArrayList<>();
            for (ItemStack stack : entry.items()) {
                items.add(stack.getCount() + "x" + stack.getItem());
            }
            parts.add("[gid=" + entry.gid() + " reason=" + entry.reason() + " at=" + entry.atHour()
                    + " grace=" + entry.graceUntilHour() + " items=" + items + "]");
        }
        return String.join(", ", parts);
    }

    /** 收命令输出的命令源 (断言看语言键与数字, 不看渲染出来的文字)。 */
    private static final class Capture implements CommandSource {

        private final List<Component> lines = new ArrayList<>();

        @Override
        public void sendSystemMessage(Component component) {
            lines.add(component);
        }

        @Override
        public boolean acceptsSuccess() {
            return true;
        }

        @Override
        public boolean acceptsFailure() {
            return true;
        }

        @Override
        public boolean shouldInformAdmins() {
            return false;
        }

        List<String> keys() {
            List<String> keys = new ArrayList<>();
            for (Component line : lines) {
                collect(line, keys, null);
            }
            return keys;
        }

        List<Long> numbers() {
            List<Long> numbers = new ArrayList<>();
            for (Component line : lines) {
                collect(line, null, numbers);
            }
            return numbers;
        }

        private static void collect(Component component, List<String> keys, List<Long> numbers) {
            if (component.getContents() instanceof TranslatableContents contents) {
                if (keys != null) {
                    keys.add(contents.getKey());
                }
                for (Object arg : contents.getArgs()) {
                    if (arg instanceof Number number && numbers != null) {
                        numbers.add(number.longValue());
                    } else if (arg instanceof Component nested) {
                        collect(nested, keys, numbers);
                    }
                }
            }
            for (Component sibling : component.getSiblings()) {
                collect(sibling, keys, numbers);
            }
        }

        String joined() {
            List<String> rendered = new ArrayList<>();
            for (Component line : lines) {
                rendered.add(line.getString());
            }
            return String.join(" | ", rendered);
        }
    }
}
