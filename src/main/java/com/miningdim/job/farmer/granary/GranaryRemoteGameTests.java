package com.miningdim.job.farmer.granary;

import com.miningdim.core.MiningConstants;
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
 * 远程退出与打包的 GameTest (docs/Spoilage_Granary_DesignSpec.md「退出、拆走、移出」: /granary leave
 * 与 /granary pack **可以远程**)。
 *
 * 锁四件事: 不在粮仓边上也能 `/granary leave <编号>` 退出并把自己的囤搬进待领粮单 (反漏洞 #60 的出路)、
 * `/granary pack <序号>` 按 `/granary bins` 的序号远程打包一个囤 (连同东西进单子、不停表、额度归还)、
 * 序号越界与「不是自己的囤」各自如实报错, 以及远程那条路不会去替玩家重算没加载的粮仓。
 */
@GameTestHolder(MiningConstants.MODID)
@PrefixGameTestTemplate(false)
public final class GranaryRemoteGameTests {

    private static final String EMPTY = "empty";
    private static final String BATCH = "granary_remote";

    /** 每条用例占自己的一条槽位 (同一批次的用例并行跑且共用区域)。 */
    private static final int SLOT = 16;

    private static final int LOW = 1;
    private static final int HIGH = 3;

    // ============================================================
    // 远程打包
    // ============================================================

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void packPacksABinFromAnywhere(GameTestHelper helper) {
        int slot = 0;
        Fixture fixture = accepted(helper, slot);
        ServerLevel level = fixture.level();
        GranarySavedData data = GranarySavedData.get(level);
        ServerPlayer owner = fixture.owner();
        BlockPos binPos = helper.absolutePos(bin(slot, LOW, LOW));
        fill(helper, binPos, new ItemStack(Items.BREAD, 5), new ItemStack(Items.STONE, 2));

        // 站在离粮仓很远的地方 (打包是远程的)。
        owner.moveTo(binPos.getX() + 200.5D, binPos.getY(), binPos.getZ() + 200.5D);
        Capture output = new Capture();
        helper.assertTrue(server(helper).getCommands().performPrefixedCommand(owner.createCommandSourceStack()
                .withSource(output), "granary pack 1") == 1, "远程打包第 1 个囤: " + output.joined());
        helper.assertTrue(output.keys().contains("message.miningdim.granary.command.pack"),
                "要有回执: " + output.joined());
        helper.assertTrue(output.numbers().contains(7L), "回执写明 5 + 2 = 7 件: " + output.joined());

        helper.assertTrue(level.getBlockState(binPos).isAir(), "方块从世界里拿掉了");
        helper.assertTrue(data.ownerOf(level.dimension().location(), binPos) == null, "额度归还");
        List<GranaryPending.Entry> pending = data.pendingOf(owner.getUUID());
        helper.assertTrue(pending.size() == 1, "单子上有一条, 实际 " + pending.size());
        helper.assertTrue(pending.get(0).reason() == GranaryPending.Reason.PACKED, "来路是自己打包");
        helper.assertTrue(pending.get(0).graceUntilHour() == 0L, "自己打包**不停表**");
        helper.assertTrue(pending.get(0).count() == 8, "7 件东西 + 囤本身, 实际 " + pending.get(0).count());
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void packRejectsBadIndexAndOtherPeoplesBins(GameTestHelper helper) {
        int slot = 1;
        Fixture fixture = accepted(helper, slot);
        ServerLevel level = fixture.level();
        ServerPlayer owner = fixture.owner();
        ServerPlayer guest = fixture.guest();
        Capture output = new Capture();

        // 客人 (成员) 也有一个囤, 但他打包不了房主的那个 (清单只列自己的)。
        placeBin(helper, guest, bin(slot, HIGH, HIGH));
        BlockPos guestBin = helper.absolutePos(bin(slot, HIGH, HIGH));
        fill(helper, guestBin, new ItemStack(Items.BREAD, 3));
        fixture.refresh(helper);
        BlockPos ownerBin = helper.absolutePos(bin(slot, LOW, LOW));

        // 房主只有 1 个囤 (客人那个不在他的清单里): 序号 2 越界。
        owner.moveTo(ownerBin.getX() + 100.5D, ownerBin.getY(), ownerBin.getZ() + 100.5D);
        output.clear();
        helper.assertTrue(server(helper).getCommands().performPrefixedCommand(owner.createCommandSourceStack()
                .withSource(output), "granary pack 2") == 0, "序号越界报错");
        helper.assertTrue(output.keys().contains("message.miningdim.granary.command.pack_bad_index"),
                "要说清序号不对: " + output.joined());

        // 序号 1 是房主自己的那个: 打包它, 客人的囤一点不动。
        output.clear();
        helper.assertTrue(server(helper).getCommands().performPrefixedCommand(owner.createCommandSourceStack()
                .withSource(output), "granary pack 1") == 1, "第 1 个是自己的囤: " + output.joined());
        helper.assertTrue(level.getBlockState(ownerBin).isAir(), "房主自己的囤搬走了");
        helper.assertTrue(!level.getBlockState(guestBin).isAir(), "客人的囤还在");
        helper.assertTrue(GranarySavedData.get(level).ownerOf(level.dimension().location(), guestBin)
                .equals(guest.getUUID()), "客人的归属没被动");
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void packOnlyTouchesTheOwnersBins(GameTestHelper helper) {
        int slot = 2;
        Fixture fixture = accepted(helper, slot);
        ServerLevel level = fixture.level();
        ServerPlayer owner = fixture.owner();
        ServerPlayer guest = fixture.guest();
        GranarySavedData data = GranarySavedData.get(level);

        placeBin(helper, guest, bin(slot, HIGH, HIGH));
        BlockPos guestBin = helper.absolutePos(bin(slot, HIGH, HIGH));
        fill(helper, guestBin, new ItemStack(Items.BREAD, 3));
        fixture.refresh(helper);

        // 客人远程打包自己的那一个: 房主的囤不受影响。
        BlockPos ownerBin = helper.absolutePos(bin(slot, LOW, LOW));
        guest.moveTo(guestBin.getX() + 150.5D, guestBin.getY(), guestBin.getZ() + 150.5D);
        Capture output = new Capture();
        helper.assertTrue(server(helper).getCommands().performPrefixedCommand(guest.createCommandSourceStack()
                .withSource(output), "granary pack 1") == 1, "客人打包自己的囤: " + output.joined());
        helper.assertTrue(level.getBlockState(guestBin).isAir(), "客人的囤搬走了");
        helper.assertTrue(!level.getBlockState(ownerBin).isAir(), "房主的囤还在");
        helper.assertTrue(data.ownerOf(level.dimension().location(), ownerBin).equals(owner.getUUID()),
                "房主的归属没被动");
        helper.assertTrue(data.pendingOf(owner.getUUID()).isEmpty(), "房主的单子还是空的");
        helper.succeed();
    }

    // ============================================================
    // 远程退出
    // ============================================================

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void leaveWorksRemotely(GameTestHelper helper) {
        int slot = 3;
        Fixture fixture = accepted(helper, slot);
        ServerLevel level = fixture.level();
        GranarySavedData data = GranarySavedData.get(level);
        ServerPlayer guest = fixture.guest();
        GranaryBinBlockEntity guestBinEntity = placeBin(helper, guest, bin(slot, HIGH, HIGH));
        BlockPos guestBin = helper.absolutePos(bin(slot, HIGH, HIGH));
        fill(helper, guestBin, new ItemStack(Items.WHEAT, 4));
        fixture.refresh(helper);
        helper.assertTrue(guestBinEntity != null, "夹具: 客人的囤放得下");

        // 站在很远的地方用编号退出。
        guest.moveTo(guestBin.getX() - 300.5D, guestBin.getY(), guestBin.getZ() - 300.5D);
        Capture output = new Capture();
        helper.assertTrue(server(helper).getCommands().performPrefixedCommand(guest.createCommandSourceStack()
                .withSource(output), "granary leave " + fixture.gid()) == 1,
                "远程退出: " + output.joined());
        helper.assertTrue(output.keys().contains("message.miningdim.granary.command.member_took_away"),
                "回执要说清囤进了自己的单子: " + output.joined());

        helper.assertTrue(!data.granary(fixture.gid()).isMember(guest.getUUID()), "名单上没有他了");
        helper.assertTrue(level.getBlockState(guestBin).isAir(), "他的囤跟着搬走了");
        List<GranaryPending.Entry> pending = data.pendingOf(guest.getUUID());
        helper.assertTrue(pending.size() == 1 && pending.get(0).reason() == GranaryPending.Reason.LEFT,
                "单子上一条「自己退出」");
        helper.assertTrue(pending.get(0).graceUntilHour() == 0L, "自己退出不停表");

        // 不是这座粮仓的成员: 如实报错。
        output.clear();
        ServerPlayer stranger = fixture.stranger();
        helper.assertTrue(server(helper).getCommands().performPrefixedCommand(stranger.createCommandSourceStack()
                .withSource(output), "granary leave " + fixture.gid()) == 0, "不是成员退不了");
        helper.assertTrue(output.keys().contains("message.miningdim.granary.command.member_not_member"),
                "要说清不在名单上: " + output.joined());

        // 编号不存在: 如实报错。
        output.clear();
        helper.assertTrue(server(helper).getCommands().performPrefixedCommand(stranger.createCommandSourceStack()
                .withSource(output), "granary leave 999999") == 0, "不存在的粮仓退不了");
        helper.assertTrue(output.keys().contains("message.miningdim.granary.command.no_such_granary"),
                "要说清没有这座粮仓: " + output.joined());
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void theOwnerStillCannotLeave(GameTestHelper helper) {
        int slot = 4;
        Fixture fixture = accepted(helper, slot);
        ServerPlayer owner = fixture.owner();
        Capture output = new Capture();
        helper.assertTrue(server(helper).getCommands().performPrefixedCommand(owner.createCommandSourceStack()
                .withSource(output), "granary leave " + fixture.gid()) == 0, "房主退不了自己的粮仓 (远程也不行)");
        helper.assertTrue(output.keys().contains("message.miningdim.granary.command.member_owner_cannot_leave"),
                "要说清要么转让要么拆掉: " + output.joined());
        helper.succeed();
    }

    // ============================================================
    // 夹具
    // ============================================================

    private record Fixture(int slot, ServerLevel level, ServerPlayer owner, ServerPlayer guest,
                           ServerPlayer stranger, long gid, BlockPos centerPos) {

        void refresh(GameTestHelper helper) {
            GranaryInspection.inspect(level, centerPos);
        }
    }

    private static Fixture accepted(GameTestHelper helper, int slot) {
        ServerLevel level = helper.getLevel();
        ServerPlayer owner = player(helper, "remowner");
        ServerPlayer guest = player(helper, "remguest");
        ServerPlayer stranger = player(helper, "remstranger");
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
        return new Fixture(slot, level, owner, guest, stranger, gid, centerPos);
    }

    /** 往一个囤的方块实体里直接放东西。 */
    private static void fill(GameTestHelper helper, BlockPos pos, ItemStack... stacks) {
        GranaryBinBlockEntity bin = (GranaryBinBlockEntity) helper.getLevel().getBlockEntity(pos);
        for (int i = 0; i < stacks.length; i++) {
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

    private static GranaryBinBlockEntity placeBin(GameTestHelper helper, ServerPlayer player, BlockPos relative) {
        ServerLevel level = helper.getLevel();
        BlockPos pos = helper.absolutePos(relative);
        GranarySavedData.get(level).releaseBin(level.dimension().location(), pos);
        helper.setBlock(relative, GranaryBlocks.BIN.get());
        BlockEvent.EntityPlaceEvent event = new BlockEvent.EntityPlaceEvent(
                BlockSnapshot.create(level.dimension(), level, pos), Blocks.AIR.defaultBlockState(), player);
        GranarySystem.onBlockPlaced(event);
        helper.assertTrue(!event.isCanceled(), "fixture: bin placeable");
        GranaryBlocks.BIN.get().setPlacedBy(level, pos, level.getBlockState(pos), player, ItemStack.EMPTY);
        return level.getBlockEntity(pos) instanceof GranaryBinBlockEntity bin ? bin : null;
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

        void clear() {
            lines.clear();
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
