package com.miningdim.job.farmer.granary;

import com.miningdim.core.MiningConstants;
import com.miningdim.job.JobId;
import com.miningdim.job.farmer.granary.block.GranaryBlocks;
import com.miningdim.testutil.MockGameTestPlayers;
import net.minecraft.commands.CommandSource;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.server.MinecraftServer;
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

/**
 * /granary 命令的 GameTest (docs/Spoilage_Granary_DesignSpec.md「界面」「/granary 命令」「/granary audit」)。
 *
 * 锁四件事: status 读的是站着的那座粮仓 (与账台右键同一批行)、bins 列出自己的囤与额度、
 * audit 要 OP 且列出「验收人 → 房主」配对、rescan 是不走队列的运营兜底 (能把悄悄破掉的屋子判成损坏)。
 *
 * 命令走真实派发 ({@code performPrefixedCommand}), 输出用一个记账的命令源收下来再断言。
 */
@GameTestHolder(MiningConstants.MODID)
@PrefixGameTestTemplate(false)
public final class GranaryCommandGameTests {

    private static final String EMPTY = "empty";
    private static final String BATCH = "granary_command";

    /** 这一批结束后把账本清干净 (粮仓账本是全服共享的存档数据, 留着会干扰后面的批次与下一轮的自查)。 */
    @net.minecraft.gametest.framework.AfterBatch(batch = BATCH)
    public static void clearLedger(ServerLevel level) {
        GranaryTestLedger.clear(level);
    }

    /** 每条用例占自己的一条槽位 (同一批次的用例并行跑且共用区域)。 */
    private static final int SLOT = 16;

    private static final int LOW = 1;
    private static final int HIGH = 3;

    // ============================================================
    // /granary status
    // ============================================================

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void statusReportsTheGranaryYouStandIn(GameTestHelper helper) {
        int slot = 0;
        ServerLevel level = helper.getLevel();
        Capture output = new Capture();
        ServerPlayer owner = player(helper, output, 1);
        buildRoom(helper, slot, 3);
        placeCenter(helper, owner, center(slot));
        placeBin(helper, owner, bin(slot, LOW, LOW));
        helper.setBlock(vent(slot), GranaryBlocks.VENT.get());
        BlockPos centerPos = helper.absolutePos(center(slot));
        helper.assertTrue(GranaryInspection.accept(level, centerPos, owner).accepted(), "先验收成粮棚");

        // 站进屋里 (账台旁边那一格)。
        owner.moveTo(centerPos.getX() + 1.5D, centerPos.getY(), centerPos.getZ() + 0.5D);
        output.clear();
        helper.assertTrue(server(helper).getCommands().performPrefixedCommand(owner.createCommandSourceStack()
                .withSource(output), "granary status") == 1, "/granary status 应当可执行");

        helper.assertTrue(output.keys().contains("message.miningdim.granary.report"),
                "要用结构与状态那一行 (与账台右键同一批行): " + output.joined());
        helper.assertTrue(output.numbers().contains(1L),
                "要报出启用的囤数 (1 个): " + output.joined());
        helper.assertTrue(GranarySavedData.get(level).granary(
                GranarySavedData.get(level).centerGranary(level.dimension().location(), centerPos))
                .tier() == GranaryTier.SHED, "命令读的是账本里那一档");
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void statusSaysWhenThereIsNoGranaryNearby(GameTestHelper helper) {
        int slot = 1;
        Capture output = new Capture();
        ServerPlayer owner = player(helper, output, 1);
        // 站在离任何粮仓都很远的地方 (这一批别的用例的屋子都在 ±几百格外)。
        owner.moveTo(helper.absolutePos(center(slot)).getX() + 500.0D, 80.0D,
                helper.absolutePos(center(slot)).getZ() + 500.0D);
        output.clear();
        helper.assertTrue(server(helper).getCommands().performPrefixedCommand(owner.createCommandSourceStack()
                .withSource(output), "granary status") == 0, "附近没有粮仓就是失败返回");
        helper.assertTrue(output.keys().contains("message.miningdim.granary.command.none_nearby"),
                "要如实说没有粮仓: " + output.joined());
        helper.succeed();
    }

    // ============================================================
    // /granary bins
    // ============================================================

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void binsListsOwnBinsAndRemainingQuota(GameTestHelper helper) {
        int slot = 2;
        Capture output = new Capture();
        ServerPlayer owner = player(helper, output, 1);
        buildRoom(helper, slot, 3);
        placeCenter(helper, owner, center(slot));
        placeBin(helper, owner, bin(slot, LOW, LOW));
        placeBin(helper, owner, bin(slot, HIGH, HIGH));
        helper.setBlock(vent(slot), GranaryBlocks.VENT.get());
        helper.assertTrue(GranaryInspection.accept(helper.getLevel(), helper.absolutePos(center(slot)), owner)
                .accepted(), "先验收成粮棚");

        output.clear();
        helper.assertTrue(server(helper).getCommands().performPrefixedCommand(owner.createCommandSourceStack()
                .withSource(output), "granary bins") == 2, "两个囤就是返回 2 (命令结果拿去接脚本也说得通)");
        helper.assertTrue(output.keys().contains("message.miningdim.granary.command.bins_header"),
                "要有表头: " + output.joined());
        helper.assertTrue(java.util.Collections.frequency(output.keys(),
                "message.miningdim.granary.command.bin_row") == 2, "一个囤一行: " + output.joined());
        helper.assertTrue(output.keys().contains("message.miningdim.granary.command.bins_quota"),
                "要报还剩几个: " + output.joined());
        // 表头参数: 2 个囤、上限 3、农夫 L1; 末尾还有「还能再放 1 个」。
        helper.assertTrue(output.numbers().contains(2L) && output.numbers().contains(3L)
                && output.numbers().contains(1L), "数字要报对: " + output.joined());

        // 没放过囤的人: 一句话说清, 仍然算执行成功。
        Capture strangerOutput = new Capture();
        ServerPlayer stranger = player(helper, strangerOutput, 1);
        helper.assertTrue(server(helper).getCommands().performPrefixedCommand(stranger.createCommandSourceStack()
                .withSource(strangerOutput), "granary bins") == 1, "没囤也要正常执行");
        helper.assertTrue(strangerOutput.keys().contains("message.miningdim.granary.command.bins_empty"),
                "如实说没有: " + strangerOutput.joined());
        helper.succeed();
    }

    // ============================================================
    // /granary audit (OP)
    // ============================================================

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void auditNeedsOperatorAndListsTheInspectorPairing(GameTestHelper helper) {
        int slot = 3;
        Capture output = new Capture();
        ServerPlayer owner = player(helper, output, 1);
        ServerPlayer inspector = player(helper, output, 5);
        buildRoom(helper, slot, 4);
        placeCenter(helper, owner, center(slot));
        placeBin(helper, owner, bin(slot, LOW, LOW));
        helper.setBlock(vent(slot), GranaryBlocks.VENT.get());
        BlockPos centerPos = helper.absolutePos(center(slot));
        helper.assertTrue(GranaryInspection.accept(helper.getLevel(), centerPos, owner).accepted(), "先验收成粮棚");
        long gid = GranarySavedData.get(helper.getLevel())
                .centerGranary(helper.getLevel().dimension().location(), centerPos);
        helper.assertTrue(GranaryInspection.accept(helper.getLevel(), centerPos, inspector, 9_999_999L).accepted()
                || GranarySavedData.get(helper.getLevel()).granary(gid).acceptedBy() != null,
                "记下验收人 (这一档不满足也没关系, 上一档已经记过)");

        // 非 OP: 命令不可见 (Brigadier 的权限门直接拒掉)。
        output.clear();
        helper.assertTrue(server(helper).getCommands().performPrefixedCommand(owner.createCommandSourceStack()
                .withSource(output).withPermission(0), "granary audit") == 0, "普通玩家不能盘点全服");
        helper.assertTrue(!output.keys().contains("message.miningdim.granary.command.audit_header"),
                "被拒时不该吐出盘点内容: " + output.joined());

        // OP: 一行一座粮仓 + 「验收人 → 房主」配对。
        output.clear();
        helper.assertTrue(server(helper).getCommands().performPrefixedCommand(owner.createCommandSourceStack()
                .withSource(output).withPermission(2), "granary audit") >= 1, "OP 能盘点");
        helper.assertTrue(output.keys().contains("message.miningdim.granary.command.audit_header"),
                "要有表头: " + output.joined());
        helper.assertTrue(output.keys().contains("message.miningdim.granary.command.audit_row"),
                "每座粮仓一行: " + output.joined());
        helper.assertTrue(output.keys().contains("message.miningdim.granary.command.audit_accepted"),
                "要列出「验收人 → 房主」配对: " + output.joined());
        // 三对配对至此齐全 (成员 → 房主 与 囤主 → 名单 由各自的批次钉住), 这里只钉「不再有缺哪对的说明」。
        helper.assertTrue(!output.keys().contains("message.miningdim.granary.command.audit_missing_pairs"),
                "三对配对齐全之后不该再有「还缺哪对」的话: " + output.joined());
        helper.assertTrue(output.numbers().contains(gid), "新验收的那座要出现在盘点里: " + output.joined());
        helper.succeed();
    }

    // ============================================================
    // /granary rescan (OP)
    // ============================================================

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void rescanIsTheOperatorEscapeHatch(GameTestHelper helper) {
        int slot = 4;
        Capture output = new Capture();
        ServerPlayer owner = player(helper, output, 1);
        buildRoom(helper, slot, 3);
        placeCenter(helper, owner, center(slot));
        placeBin(helper, owner, bin(slot, LOW, LOW));
        helper.setBlock(vent(slot), GranaryBlocks.VENT.get());
        ServerLevel level = helper.getLevel();
        BlockPos centerPos = helper.absolutePos(center(slot));
        helper.assertTrue(GranaryInspection.accept(level, centerPos, owner).accepted(), "先验收成粮棚");
        long gid = GranarySavedData.get(level).centerGranary(level.dimension().location(), centerPos);

        // 悄悄把墙拆了, 也不打脏标记 (复查调度的自动路径没被触发)。
        helper.setBlock(new BlockPos(slot * SLOT + 2, 2, HIGH), Blocks.OAK_LEAVES);
        owner.moveTo(centerPos.getX() + 1.5D, centerPos.getY(), centerPos.getZ() + 0.5D);
        output.clear();
        helper.assertTrue(server(helper).getCommands().performPrefixedCommand(owner.createCommandSourceStack()
                .withSource(output).withPermission(2), "granary rescan") == 1, "OP 能手动复查");
        helper.assertTrue(GranarySavedData.get(level).granary(gid).status() == GranaryRecord.Status.DAMAGED,
                "手动复查要能把悄悄破掉的屋子判成损坏");
        helper.assertTrue(output.keys().contains("message.miningdim.granary.command.rescan"),
                "回显要写清复查了哪一座: " + output.joined());
        helper.assertTrue(output.numbers().contains(gid), "回显里要有粮仓编号: " + output.joined());

        // 普通玩家没有这条兜底。
        helper.assertTrue(server(helper).getCommands().performPrefixedCommand(owner.createCommandSourceStack()
                .withSource(new Capture()).withPermission(0), "granary rescan") == 0, "非 OP 不能用 rescan");
        helper.succeed();
    }

    // ============================================================
    // 夹具
    // ============================================================

    /**
     * 收命令输出的命令源。
     *
     * 断言看**语言键与参数**, 不看渲染出来的文字: GameTest 服务端的解析语言是 en_us, 拿中文文案去比
     * 只会随语言环境红 (第一版就是这么红的), 而「用了哪条消息、参数是几」才是命令真正要保证的东西。
     */
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

        /** 收到的语言键 (整棵组件树: sendFailure 会把消息挂成兄弟节点)。 */
        List<String> keys() {
            List<String> keys = new ArrayList<>();
            for (Component line : lines) {
                collect(line, keys, null);
            }
            return keys;
        }

        /** 收到的数字参数 (语言无关的那部分)。 */
        List<Long> numbers() {
            List<Long> numbers = new ArrayList<>();
            for (Component line : lines) {
                collect(line, null, numbers);
            }
            return numbers;
        }

        /** 走一遍组件树: 语言键与数字都可能挂在兄弟节点或参数里。 */
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

        /** 渲染出来的文字, 只用在失败信息里方便人看。 */
        String joined() {
            List<String> rendered = new ArrayList<>();
            for (Component line : lines) {
                rendered.add(line.getString());
            }
            return String.join(" | ", rendered);
        }
    }

    private static MinecraftServer server(GameTestHelper helper) {
        return helper.getLevel().getServer();
    }

    private static ServerPlayer player(GameTestHelper helper, Capture output, int farmerLevel) {
        ServerPlayer player = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);
        setFarmerLevel(player, farmerLevel);
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

    private static void setFarmerLevel(ServerPlayer player, int level) {
        com.miningdim.entry.MiningCapabilities.get(player)
                .orElseThrow(() -> new IllegalStateException("mock 玩家未挂载 capability, 无法摆放职业等级"))
                .jobProgress(JobId.FARMER)
                .setLevel(level);
    }
}
