package com.miningdim.job.farmer.granary;

import com.miningdim.core.MiningConstants;
import com.miningdim.job.JobId;
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
 * 合建成员的 GameTest (docs/Spoilage_Granary_DesignSpec.md「合建: 入册与权限」「谁能拆」)。
 *
 * 锁六件事: 只有房主或 OP 能改成员、成员能自己退出而房主不能、**没入册的囤不计入任何要求**、
 * 移出成员后对方的囤变待启用 (只能取不能存)、还有别人的囤时账台拆不掉、`/granary audit` 会给
 * 每位成员列一行「成员 → 房主」。
 *
 * 临时口径 (随待领粮单分片收口): 移出的囤现在是「待启用」; 正式口径是进囤主的待领粮单、占额度、
 * 宽限期内照常停表 —— 那张表落地之前不假装有。
 */
@GameTestHolder(MiningConstants.MODID)
@PrefixGameTestTemplate(false)
public final class GranaryMemberGameTests {

    private static final String EMPTY = "empty";
    private static final String BATCH = "granary_member";

    /** 这一批结束后把账本清干净 (粮仓账本是全服共享的存档数据, 留着会干扰后面的批次与下一轮的自查)。 */
    @net.minecraft.gametest.framework.AfterBatch(batch = BATCH)
    public static void clearLedger(ServerLevel level) {
        GranaryTestLedger.clear(level);
    }

    /** 每条用例占自己的一条槽位 (同一批次的用例并行跑且共用区域)。 */
    private static final int SLOT = 16;

    private static final int LOW = 1;
    private static final int HIGH = 3;

    /** /granary audit 的 OP 门槛 (与 GranaryCommands 里那把尺一致)。 */
    private static final int OP_LEVEL = 2;

    // ============================================================
    // 入册与权限
    // ============================================================

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void onlyTheOwnerOrOperatorCanChangeMembers(GameTestHelper helper) {
        int slot = 0;
        ServerLevel level = helper.getLevel();
        Capture output = new Capture();
        ServerPlayer owner = player(helper, "mowner");
        ServerPlayer guest = player(helper, "mguest");
        ServerPlayer stranger = player(helper, "mstranger");
        buildRoom(helper, slot, 3);
        placeCenter(helper, owner, center(slot));
        BlockPos centerPos = helper.absolutePos(center(slot));
        long gid = GranarySystem.granaryAt(level, centerPos);

        // 陌生人 (非房主、非 OP): 改不了成员。
        stranger.moveTo(centerPos.getX() + 1.5D, centerPos.getY(), centerPos.getZ() + 0.5D);
        output.clear();
        int denied = server(helper).getCommands().performPrefixedCommand(stranger.createCommandSourceStack()
                .withSource(output).withPermission(0), "granary member add " + guest.getGameProfile().getName());
        helper.assertTrue(denied == 0, "非房主不能入册成员 (result=" + denied + ", keys=" + output.keys() + ")");
        helper.assertTrue(output.keys().contains("message.miningdim.granary.command.member_not_owner"),
                "要说明只有房主能改: " + output.joined());
        helper.assertTrue(GranarySavedData.get(level).granary(gid).members().isEmpty(),
                "名单没被动过 (members=" + GranarySavedData.get(level).granary(gid).members() + ")");

        // 房主: 能加。
        owner.moveTo(centerPos.getX() + 1.5D, centerPos.getY(), centerPos.getZ() + 0.5D);
        // 夹具自证: 命令的玩家参数是**按名字**在玩家列表里找人的, 先确认找得到、找得对。
        ServerPlayer byName = server(helper).getPlayerList()
                .getPlayerByName(guest.getGameProfile().getName());
        helper.assertTrue(byName == guest, "夹具: 按名字要能找到这位客人 (byName="
                + (byName == null ? "null" : byName.getUUID() + "/" + byName.getGameProfile().getName())
                + ", guest=" + guest.getUUID() + "/" + guest.getGameProfile().getName() + ")");
        helper.assertTrue(GranaryQuery.nearest(level, owner.position()).orElseThrow().gid() == gid,
                "夹具: 就近找到的要是这一座 (myGid=" + gid + ", nearby="
                        + GranaryQuery.nearest(level, owner.position()).orElseThrow().gid() + ")");
        output.clear();
        int added = server(helper).getCommands().performPrefixedCommand(owner.createCommandSourceStack()
                .withSource(output), "granary member add " + guest.getGameProfile().getName());
        helper.assertTrue(added == 1, "房主能入册 (result=" + added + ", keys=" + output.keys() + ")");
        helper.assertTrue(GranarySavedData.get(level).granary(gid).isMember(guest.getUUID()),
                "客人进名单了 (members=" + GranarySavedData.get(level).granary(gid).members()
                        + ", guest=" + guest.getUUID() + ", owner=" + owner.getUUID() + ")");
        helper.assertTrue(output.keys().contains("message.miningdim.granary.command.member_added"),
                "要回显入册: " + output.joined());

        // 房主自己不用入册。
        output.clear();
        int self = server(helper).getCommands().performPrefixedCommand(owner.createCommandSourceStack()
                .withSource(output), "granary member add " + owner.getGameProfile().getName());
        helper.assertTrue(self == 0, "房主本来就是成员 (result=" + self + ", keys=" + output.keys() + ")");
        helper.assertTrue(output.keys().contains("message.miningdim.granary.command.member_is_owner"),
                "要说明房主本来就是成员: " + output.joined());
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void membersCanLeaveButTheOwnerCannot(GameTestHelper helper) {
        int slot = 1;
        ServerLevel level = helper.getLevel();
        Capture output = new Capture();
        ServerPlayer owner = player(helper, "mowner");
        ServerPlayer guest = player(helper, "mguest");
        buildRoom(helper, slot, 3);
        placeCenter(helper, owner, center(slot));
        BlockPos centerPos = helper.absolutePos(center(slot));
        long gid = GranarySystem.granaryAt(level, centerPos);
        GranarySavedData data = GranarySavedData.get(level);
        data.putGranary(data.granary(gid).withMember(guest.getUUID()));

        // 房主不能退出自己的粮仓。
        owner.moveTo(centerPos.getX() + 1.5D, centerPos.getY(), centerPos.getZ() + 0.5D);
        output.clear();
        helper.assertTrue(server(helper).getCommands().performPrefixedCommand(owner.createCommandSourceStack()
                .withSource(output), "granary member leave") == 0, "房主不能退自己的粮仓");
        helper.assertTrue(output.keys().contains("message.miningdim.granary.command.member_owner_cannot_leave"),
                "要说清怎么办 (转让或拆掉): " + output.joined());

        // 成员可以自己退出。
        guest.moveTo(centerPos.getX() + 1.5D, centerPos.getY(), centerPos.getZ() + 0.5D);
        output.clear();
        helper.assertTrue(server(helper).getCommands().performPrefixedCommand(guest.createCommandSourceStack()
                .withSource(output), "granary member leave") == 1, "成员能退出");
        helper.assertTrue(!data.granary(gid).isMember(guest.getUUID()), "退出后不在名单上");
        helper.assertTrue(output.keys().contains("message.miningdim.granary.command.member_left"),
                "要回显退出: " + output.joined());

        // 不在名单上的人退出: 如实报错。
        output.clear();
        helper.assertTrue(server(helper).getCommands().performPrefixedCommand(guest.createCommandSourceStack()
                .withSource(output), "granary member leave") == 0, "已经退出了就不能再退");
        helper.assertTrue(output.keys().contains("message.miningdim.granary.command.member_not_member"),
                "要说明不在名单上: " + output.joined());
        helper.succeed();
    }

    // ============================================================
    // 没入册的囤不计入任何要求
    // ============================================================

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void unenrolledBinsDoNotCountTowardsAnything(GameTestHelper helper) {
        int slot = 2;
        ServerLevel level = helper.getLevel();
        ServerPlayer owner = player(helper, "mowner");
        ServerPlayer stranger = player(helper, "mstranger");
        buildRoom(helper, slot, 3);
        placeCenter(helper, owner, center(slot));
        placeBin(helper, owner, bin(slot, LOW, LOW));
        placeBin(helper, stranger, bin(slot, HIGH, HIGH));      // 陌生人的囤: 没入册
        helper.setBlock(vent(slot), GranaryBlocks.VENT.get());
        BlockPos centerPos = helper.absolutePos(center(slot));
        long gid = GranarySystem.granaryAt(level, centerPos);
        helper.assertTrue(GranaryInspection.accept(level, centerPos, owner).accepted(), "先验收成粮棚");

        GranarySavedData data = GranarySavedData.get(level);
        UUID ownerId = owner.getUUID();
        helper.assertTrue(data.binState(level.dimension().location(), helper.absolutePos(bin(slot, LOW, LOW)))
                == GranaryBinState.ACTIVE, "房主自己的囤启用");
        helper.assertTrue(data.binState(level.dimension().location(), helper.absolutePos(bin(slot, HIGH, HIGH)))
                == GranaryBinState.PENDING, "没入册的囤是待启用 (不计入任何要求)");
        GranaryInspection.Report report = GranaryInspection.inspect(level, centerPos);
        helper.assertTrue(report.enablement().enabled() == 1,
                "可支撑数只算入册的那一个, 实际启用 " + report.enablement().enabled());

        // 入册之后: 陌生人的囤也启用。
        data.putGranary(data.granary(gid).withMember(stranger.getUUID()));
        GranaryInspection.Report after = GranaryInspection.inspect(level, centerPos);
        helper.assertTrue(after.enablement().enabled() == 2, "入册之后两个都启用");
        helper.assertTrue(data.binState(level.dimension().location(), helper.absolutePos(bin(slot, HIGH, HIGH)))
                == GranaryBinState.ACTIVE, "入册之后就启用了");
        helper.assertTrue(data.ownerOf(level.dimension().location(), helper.absolutePos(bin(slot, HIGH, HIGH)))
                .equals(stranger.getUUID()), "归属还是陌生人的 (合建各归各)");
        helper.assertTrue(!ownerId.equals(stranger.getUUID()), "夹具用的是两个不同的人");
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void removingAMemberTurnsTheirBinsIntoWaiting(GameTestHelper helper) {
        int slot = 3;
        ServerLevel level = helper.getLevel();
        ServerPlayer owner = player(helper, "mowner");
        ServerPlayer guest = player(helper, "mguest");
        buildRoom(helper, slot, 3);
        placeCenter(helper, owner, center(slot));
        placeBin(helper, owner, bin(slot, LOW, LOW));
        placeBin(helper, guest, bin(slot, HIGH, HIGH));
        helper.setBlock(vent(slot), GranaryBlocks.VENT.get());
        BlockPos centerPos = helper.absolutePos(center(slot));
        long gid = GranarySystem.granaryAt(level, centerPos);
        GranarySavedData data = GranarySavedData.get(level);
        data.putGranary(data.granary(gid).withMember(guest.getUUID()));
        helper.assertTrue(GranaryInspection.accept(level, centerPos, owner).accepted(), "先验收成粮棚");
        helper.assertTrue(data.binState(level.dimension().location(), helper.absolutePos(bin(slot, HIGH, HIGH)))
                == GranaryBinState.ACTIVE, "客人的囤在启用");

        // 移出客人: 他的囤变待启用 (只能取不能存), 而且不再占部件配额。
        data.putGranary(data.granary(gid).withoutMember(guest.getUUID()));
        GranaryInspection.Report report = GranaryInspection.inspect(level, centerPos);
        helper.assertTrue(data.binState(level.dimension().location(), helper.absolutePos(bin(slot, HIGH, HIGH)))
                == GranaryBinState.PENDING, "移出之后客人的囤变待启用");
        helper.assertTrue(report.enablement().enabled() == 1, "配额只算房主那一个");
        helper.assertTrue(guest.getUUID().equals(data.ownerOf(level.dimension().location(),
                helper.absolutePos(bin(slot, HIGH, HIGH)))), "东西还是客人的 (房主拿不走)");
        helper.assertTrue(!data.granary(gid).isMember(guest.getUUID()), "名单上没有他了");
        helper.succeed();
    }

    // ============================================================
    // 拆账台与拆囤
    // ============================================================

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void theCenterCannotBeBrokenWhileOthersKeepBins(GameTestHelper helper) {
        int slot = 4;
        ServerLevel level = helper.getLevel();
        ServerPlayer owner = player(helper, "mowner");
        ServerPlayer guest = player(helper, "mguest");
        buildRoom(helper, slot, 3);
        placeCenter(helper, owner, center(slot));
        placeBin(helper, guest, bin(slot, LOW, LOW));
        BlockPos centerPos = helper.absolutePos(center(slot));
        long gid = GranarySystem.granaryAt(level, centerPos);
        GranarySavedData data = GranarySavedData.get(level);
        data.putGranary(data.granary(gid).withMember(guest.getUUID()));

        BlockState state = level.getBlockState(centerPos);
        helper.assertTrue(!GranaryBlocks.CENTER.get().onDestroyedByPlayer(state, level, centerPos, owner, false,
                state.getFluidState()), "还有客人的囤时账台拆不掉");
        helper.assertTrue(data.granary(gid) != null, "粮仓还在");

        // 客人退出 (囤也没了) 之后, 房主能拆。
        data.releaseBin(level.dimension().location(), helper.absolutePos(bin(slot, LOW, LOW)));
        data.putGranary(data.granary(gid).withoutMember(guest.getUUID()));
        helper.assertTrue(GranaryBlocks.CENTER.get().onDestroyedByPlayer(state, level, centerPos, owner, false,
                state.getFluidState()), "没有别人的囤了就能拆");
        helper.succeed();
    }

    // ============================================================
    // 名单
    // ============================================================

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void memberListShowsEveryonesBinCounts(GameTestHelper helper) {
        int slot = 5;
        ServerLevel level = helper.getLevel();
        Capture output = new Capture();
        ServerPlayer owner = player(helper, "mowner");
        ServerPlayer guest = player(helper, "mguest");
        buildRoom(helper, slot, 3);
        placeCenter(helper, owner, center(slot));
        placeBin(helper, owner, bin(slot, LOW, LOW));
        placeBin(helper, guest, bin(slot, HIGH, HIGH));
        helper.setBlock(vent(slot), GranaryBlocks.VENT.get());
        BlockPos centerPos = helper.absolutePos(center(slot));
        long gid = GranarySystem.granaryAt(level, centerPos);
        GranarySavedData data = GranarySavedData.get(level);
        data.putGranary(data.granary(gid).withMember(guest.getUUID()));
        helper.assertTrue(GranaryInspection.accept(level, centerPos, owner).accepted(), "先验收成粮棚");

        guest.moveTo(centerPos.getX() + 1.5D, centerPos.getY(), centerPos.getZ() + 0.5D);
        output.clear();
        helper.assertTrue(server(helper).getCommands().performPrefixedCommand(guest.createCommandSourceStack()
                .withSource(output), "granary member list") == 2, "名单上有房主与客人两个人");
        helper.assertTrue(output.keys().contains("message.miningdim.granary.command.member_header"),
                "要有表头: " + output.joined());
        helper.assertTrue(java.util.Collections.frequency(output.keys(),
                "message.miningdim.granary.command.member_row") == 2, "一人一行: " + output.joined());
        helper.assertTrue(output.numbers().contains(1L), "每人的囤数与启用数都在行里: " + output.joined());
        helper.succeed();
    }

    // ============================================================
    // 盘点里的成员配对
    // ============================================================

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void auditListsMemberPairs(GameTestHelper helper) {
        int slot = 6;
        ServerLevel level = helper.getLevel();
        Capture output = new Capture();
        ServerPlayer owner = player(helper, "mowner");
        ServerPlayer guest = player(helper, "mguest");
        buildRoom(helper, slot, 3);
        placeCenter(helper, owner, center(slot));
        placeBin(helper, owner, bin(slot, LOW, LOW));
        helper.setBlock(vent(slot), GranaryBlocks.VENT.get());
        BlockPos centerPos = helper.absolutePos(center(slot));
        long gid = GranarySystem.granaryAt(level, centerPos);
        GranarySavedData data = GranarySavedData.get(level);

        // 全服的粮仓是共用一张账本的 (同批别的用例、上一轮跑剩的都在), 所以只比「入册前后的差」。
        output.clear();
        helper.assertTrue(server(helper).getCommands().performPrefixedCommand(owner.createCommandSourceStack()
                .withSource(output).withPermission(OP_LEVEL), "granary audit") >= 1, "OP 能盘点");
        int before = java.util.Collections.frequency(output.keys(),
                "message.miningdim.granary.command.audit_member");

        data.putGranary(data.granary(gid).withMember(guest.getUUID()));
        output.clear();
        helper.assertTrue(server(helper).getCommands().performPrefixedCommand(owner.createCommandSourceStack()
                .withSource(output).withPermission(OP_LEVEL), "granary audit") >= 1, "OP 能盘点");
        int after = java.util.Collections.frequency(output.keys(),
                "message.miningdim.granary.command.audit_member");
        helper.assertTrue(after == before + 1, "入册一位成员就该多一行「成员 → 房主」(before=" + before
                + ", after=" + after + ", keys=" + output.keys() + ")");
        helper.assertTrue(output.keys().contains("message.miningdim.granary.command.audit_row"),
                "粮仓那一行照旧: " + output.joined());
        // 三对配对齐全之后不该再有「还缺哪对」的话 (开放名单那一批把最后
        // 一对「囤主 → 名单」补上了)。
        helper.assertTrue(!output.keys().contains("message.miningdim.granary.command.audit_missing_pairs"),
                "不该再有缺配对的说明: " + output.joined());
        helper.succeed();
    }

    // ============================================================
    // 存档
    // ============================================================

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void membersSurviveSaveAndLoad(GameTestHelper helper) {
        UUID owner = UUID.randomUUID();
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        GranaryRecord record = GranaryRecord.unaccepted(4242L, owner)
                .withMember(first).withMember(second).withMember(owner);
        helper.assertTrue(record.members().size() == 2, "房主不写进成员表 (隐含是成员)");
        helper.assertTrue(record.isMember(owner) && record.isMember(first), "两个都算成员");
        helper.assertTrue(record.withMember(first) == record, "重复入册是幂等的");
        helper.assertTrue(record.withoutMember(second).members().size() == 1, "移出一个就少一个");
        helper.assertTrue(record.withoutMember(UUID.randomUUID()) == record, "移出不在名单上的人是幂等的");

        GranaryRecord loaded = GranaryRecord.load(record.save());
        helper.assertTrue(loaded.equals(record), "成员表原样存读");
        helper.assertTrue(loaded.members().contains(first), "顺序与内容都保住");
        helper.assertTrue(GranaryRecord.load(record.withoutMember(first).save()).members().size() == 1,
                "移出之后存读也一致");
        helper.succeed();
    }

    // ============================================================
    // 夹具
    // ============================================================

    /**
     * 造一位有名字的 mock 玩家。
     *
     * 名字要满足两条: **全局唯一**且不超过 16 个字符。前者是因为命令的玩家参数按名字在玩家列表里找人
     * 不会自己下线 —— 同一批次里别的用例、甚至上一轮跑剩的同名玩家都还在列表里, 名字重了就会抓到别人; 另外名字不能超过 16 字符 —— 命令的玩家参数用的是原版的名字文法
     * (实测就是抓到了另一个同名玩家)。所以这里给每个名字挂一个随机后缀。
     */
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
