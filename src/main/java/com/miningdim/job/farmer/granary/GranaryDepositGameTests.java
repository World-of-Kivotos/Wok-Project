package com.miningdim.job.farmer.granary;

import com.miningdim.core.MiningConstants;
import com.miningdim.freshness.FreshnessCategories;
import com.miningdim.freshness.FreshnessCategory;
import com.miningdim.freshness.FreshnessConstants;
import com.miningdim.freshness.FreshnessConfig;
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

/**
 * 一键存入的 GameTest (docs/Spoilage_Granary_DesignSpec.md「一键存入」「防新货蹭老格子」)。
 *
 * 锁五件事: 易腐品能存进去并且从这一刻起停表、没启用/粮仓坏了就一件不收、额度用完与生鲜(粮棚)与
 * 已腐烂的一律跳过、已有东西的格子只接受「本小时同一批」追加、同种食物先合并再放。
 * 命令那一条另锁「保留快捷栏」按玩家记住。
 */
@GameTestHolder(MiningConstants.MODID)
@PrefixGameTestTemplate(false)
public final class GranaryDepositGameTests {

    private static final String EMPTY = "empty";
    private static final String BATCH = "granary_deposit";

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
    // 收下与盖章
    // ============================================================

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void depositStoresPerishablesAndStartsTheirClock(GameTestHelper helper) {
        int slot = 0;
        Fixture fixture = accept(helper, slot);
        ServerPlayer player = fixture.owner();
        player.getInventory().setItem(9, new ItemStack(Items.WHEAT, 20));
        player.getInventory().setItem(10, new ItemStack(Items.BREAD, 3));

        GranaryDeposit.Result result = fixture.deposit(helper, player);

        helper.assertTrue(result.accepted(), "启用中的囤应当收得下, 实际 " + result.rejection());
        helper.assertTrue(result.items() == 23, "20 个小麦 + 3 个面包都收下, 实际 " + result.items());
        helper.assertTrue(result.stacks() == 2, "两堆, 实际 " + result.stacks());
        GranaryBinBlockEntity bin = fixture.bin(helper);
        helper.assertTrue(bin.getItem(0).is(Items.WHEAT) && bin.getItem(0).getCount() == 20,
                "小麦进了第一格");
        helper.assertTrue(bin.getItem(1).is(Items.BREAD), "面包进了第二格");
        helper.assertTrue(bin.entryHour(0) == fixture.nowHour() && bin.entryHour(1) == fixture.nowHour(),
                "入仓时刻按本小时记");
        helper.assertTrue(FreshnessData.isStamped(bin.getItem(0)), "没盖章的当场盖章");
        helper.assertTrue(bin.stopping(), "启用中的囤在停表");
        helper.assertTrue(player.getInventory().getItem(9).isEmpty()
                && player.getInventory().getItem(10).isEmpty(), "背包里那两堆都搬空了");
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void nothingIsTakenWhenTheBinOrGranaryIsNotUsable(GameTestHelper helper) {
        int slot = 1;
        ServerLevel level = helper.getLevel();
        ServerPlayer owner = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);
        setFarmerLevel(owner, 1);
        buildRoom(helper, slot, 3);
        placeCenter(helper, owner, center(slot));
        placeBin(helper, owner, bin(slot, LOW, LOW));
        helper.setBlock(vent(slot), GranaryBlocks.VENT.get());
        // 故意**不验收**: 没验收的粮仓不能存。
        owner.getInventory().setItem(9, new ItemStack(Items.WHEAT, 5));

        GranaryBinBlockEntity bin = (GranaryBinBlockEntity) helper.getBlockEntity(bin(slot, LOW, LOW));
        GranaryDeposit.Result result = GranaryDeposit.depositAt(level, bin, owner, true, fixtureHour() * 60L);
        helper.assertTrue(!result.accepted(), "没验收就一件都不收");
        helper.assertTrue(result.rejection() == GranaryDeposit.Rejection.NOT_ACTIVE,
                "囤还是待启用, 实际 " + result.rejection());
        helper.assertTrue(bin.isEmpty(), "囤里还是空的");
        helper.assertTrue(owner.getInventory().getItem(9).getCount() == 5, "东西还在背包里");
        helper.succeed();
    }

    // ============================================================
    // 跳过的三类东西
    // ============================================================

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void exhaustedFreshAndRottenGoodsAreSkipped(GameTestHelper helper) {
        FreshnessConfig.Mode previousMode = FreshnessConfig.MODE.get();
        try {
            FreshnessConfig.MODE.set(FreshnessConfig.Mode.ON);
            int slot = 2;
            Fixture fixture = accept(helper, slot);
            ServerPlayer player = fixture.owner();

            ItemStack exhausted = new ItemStack(Items.WHEAT, 4);
            FreshnessData.stampAt(exhausted, fixture.nowMinute(), FreshnessConstants.STOP_CAP_HOURS);
            ItemStack fresh = new ItemStack(Items.BEEF, 2);                 // 粮棚不收生鲜
            FreshnessData.stamp(fresh, fixture.nowMinute());
            ItemStack rotten = new ItemStack(Items.BREAD, 1);              // 早就烂了
            FreshnessData.stampAt(rotten, fixture.nowMinute() - 40L * 24L * 60L, 0);
            ItemStack keep = new ItemStack(Items.WHEAT, 6);                // 这一堆是好的
            FreshnessData.stamp(keep, fixture.nowMinute());
            // 夹具先自证: 三堆「该被跳过」的确实各自命中了那条规矩 (失败时一眼看出是夹具问题还是收没收对)。
            helper.assertTrue(FreshnessCategories.spoils(exhausted) && GranarySettle.capExhausted(exhausted),
                    "夹具: 额度用完的那堆要真的用完 " + FreshnessData.stoppedHours(exhausted));
            helper.assertTrue(FreshnessCategories.categoryOf(fresh).orElse(null) == FreshnessCategory.FRESH,
                    "夹具: 生牛肉要真的算生鲜, 实际 " + FreshnessCategories.categoryOf(fresh));
            helper.assertTrue(FreshnessCategories.spoils(rotten) && FreshnessData.isRotten(rotten,
                    FreshnessCategories.shelfLifeHours(rotten), fixture.nowMinute()),
                    "夹具: 面包要真的烂了 (保质期 " + FreshnessCategories.shelfLifeHours(rotten)
                            + " 小时, 批次 " + FreshnessData.batchMinute(rotten) + ", 现在 " + fixture.nowMinute() + ")");
            player.getInventory().setItem(9, exhausted);
            player.getInventory().setItem(10, fresh);
            player.getInventory().setItem(11, rotten);
            player.getInventory().setItem(12, keep);

            GranaryDeposit.Result result = fixture.deposit(helper, player);

            helper.assertTrue(result.items() == 6, "只有那 6 个好的收下, 实际 " + result.items());
            helper.assertTrue(result.skips().exhausted() == 1, "额度用完的要点名跳过");
            helper.assertTrue(result.skips().fresh() == 1, "粮棚不收生鲜, 要点名跳过");
            helper.assertTrue(result.skips().rotten() == 1, "已经腐烂的要点名跳过");
            GranaryBinBlockEntity bin = fixture.bin(helper);
            helper.assertTrue(bin.getItem(0).is(Items.WHEAT) && bin.getItem(0).getCount() == 6,
                    "收下的是那一堆好的");
            helper.assertTrue(bin.getItem(1).isEmpty(), "被跳过的一件都没进来");
            helper.succeed();
        } finally {
            FreshnessConfig.MODE.set(previousMode);
        }
    }

    // ============================================================
    // 防新货蹭老格子
    // ============================================================

    /**
     * 开服迁移的老货**不进仓** (规格「开服迁移」: 启用时只剩 6 小时、不能进仓): 它们的停表额度已经记满,
     * 进仓再停表等于白送 21 天; 一键存入里点名跳过, 界面上那一格也放不进去 (两条路必须给同一个答案)。
     */
    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void migratedGoodsAreSkippedAndNeverStored(GameTestHelper helper) {
        FreshnessConfig.Mode previousMode = FreshnessConfig.MODE.get();
        try {
            FreshnessConfig.MODE.set(FreshnessConfig.Mode.ON);
            int slot = 4;
            Fixture fixture = accept(helper, slot);
            ServerPlayer player = fixture.owner();

            ItemStack migrated = new ItemStack(Items.WHEAT, 5);
            // 启用时刻取"现在": 迁移货在启用那一刻正好剩宽限小时, 所以它是**能吃但进不了仓**的那一类
            // (取成 30 天前的话它会先被判成腐烂, 测的就变成腐烂那条路了)。
            helper.assertTrue(com.miningdim.freshness.FreshnessMigration.stampMigrated(migrated,
                            fixture.nowMinute(), FreshnessConstants.MIGRATION_GRACE_HOURS,
                            FreshnessConstants.STOP_CAP_HOURS),
                    "夹具: 这一堆要是迁移货 (带章 + 额度记满 + m 标记)");
            helper.assertTrue(FreshnessData.isMigrated(migrated) && GranarySettle.capExhausted(migrated),
                    "前提: 迁移货的额度本来就是满的 (所以「额度用完」那条判据也会命中它 —— 顺序写反就会报错原因)");
            ItemStack keep = new ItemStack(Items.WHEAT, 6);
            FreshnessData.stamp(keep, fixture.nowMinute());
            ItemStack keepForMenu = keep.copy();
            player.getInventory().setItem(9, migrated);
            player.getInventory().setItem(10, keep);

            GranaryDeposit.Result result = fixture.deposit(helper, player);
            helper.assertTrue(result.items() == 6, "只有那 6 个好的收进来, 实际 " + result.items());
            helper.assertTrue(result.skips().migrated() == 1,
                    "迁移货要被单独点名 (混进别的计数里就没人知道它为什么没进去), 实得 " + result.skips());
            GranaryBinBlockEntity bin = fixture.bin(helper);
            helper.assertTrue(bin.getItem(0).is(Items.WHEAT) && bin.getItem(0).getCount() == 6
                            && !FreshnessData.isMigrated(bin.getItem(0)),
                    "进仓的必须是那一堆好货");

            // 界面那条路: 同一堆迁移货在这一格的 mayPlace 必须是 false。
            GranaryBinMenu menu = new GranaryBinMenu(0, player.getInventory(), bin);
            helper.assertTrue(!menu.slots.get(1).mayPlace(migrated),
                    "界面也不许把迁移货放进囤 (与一键存入同一个答案)");
            helper.assertTrue(menu.slots.get(1).mayPlace(keepForMenu), "好货照常放得进");
            helper.succeed();
        } finally {
            FreshnessConfig.MODE.set(previousMode);
        }
    }
    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void oldSlotsOnlyAcceptThisHoursBatch(GameTestHelper helper) {
        int slot = 3;
        Fixture fixture = accept(helper, slot);
        ServerPlayer player = fixture.owner();
        GranaryBinBlockEntity bin = fixture.bin(helper);

        // 第一格: 上一小时存进去的 10 个小麦 (老格子)。
        ItemStack old = new ItemStack(Items.WHEAT, 10);
        FreshnessData.stamp(old, fixture.nowMinute());
        bin.setItem(0, old);
        bin.markStored(0, fixture.nowHour() - 1L);
        // 第二格: 本小时存进去的 10 个小麦 (同一批, 章也一样)。
        ItemStack sameHour = new ItemStack(Items.WHEAT, 10);
        FreshnessData.stamp(sameHour, fixture.nowMinute());
        bin.setItem(1, sameHour);
        bin.markStored(1, fixture.nowHour());
        player.getInventory().setItem(9, new ItemStack(Items.WHEAT, 7));

        GranaryDeposit.Result result = fixture.deposit(helper, player);

        helper.assertTrue(result.items() == 7, "7 个都要收下 (有本小时的格子可以追加)");
        helper.assertTrue(bin.getItem(0).getCount() == 10, "老格子不动 (新货不许蹭老格子)");
        helper.assertTrue(bin.entryHour(0) == fixture.nowHour() - 1L, "老格子的入仓时刻不能被顶掉");
        helper.assertTrue(bin.getItem(1).getCount() == 17, "本小时那格追加到 17");
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void sameFoodIsMergedBeforeItGoesIn(GameTestHelper helper) {
        int slot = 4;
        Fixture fixture = accept(helper, slot);
        ServerPlayer player = fixture.owner();
        // 同一种食物、同一批、停表额度相同 → 按合并规则先并成一堆。
        ItemStack first = new ItemStack(Items.WHEAT, 10);
        ItemStack second = new ItemStack(Items.WHEAT, 6);
        FreshnessData.stamp(first, fixture.nowMinute());
        FreshnessData.stamp(second, fixture.nowMinute());
        player.getInventory().setItem(9, first);
        player.getInventory().setItem(10, second);

        GranaryDeposit.Result result = fixture.deposit(helper, player);

        helper.assertTrue(result.items() == 16, "16 个都收下, 实际 " + result.items());
        helper.assertTrue(result.stacks() == 1, "合并之后只剩一堆, 实际 " + result.stacks());
        GranaryBinBlockEntity bin = fixture.bin(helper);
        helper.assertTrue(bin.getItem(0).getCount() == 16, "一格装下 16 个");
        helper.assertTrue(bin.getItem(1).isEmpty(), "没有用到第二格");
        helper.succeed();
    }

    // ============================================================
    // 命令: 一键存入 + 保留快捷栏
    // ============================================================

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void commandDepositsAndRemembersTheHotbarSwitch(GameTestHelper helper) {
        int slot = 5;
        Fixture fixture = accept(helper, slot);
        ServerPlayer player = fixture.owner();
        Capture output = new Capture();
        player.getInventory().setItem(0, new ItemStack(Items.WHEAT, 4));   // 快捷栏第一格
        player.getInventory().setItem(9, new ItemStack(Items.BREAD, 3));   // 主背包
        player.moveTo(fixture.center(helper).getX() + 1.5D, fixture.center(helper).getY(),
                fixture.center(helper).getZ() + 0.5D);

        // 默认连快捷栏一起存。
        helper.assertTrue(server(helper).getCommands().performPrefixedCommand(player.createCommandSourceStack()
                .withSource(output), "granary deposit") > 0, "一键存入应当收下东西");
        helper.assertTrue(output.keys().contains("message.miningdim.granary.command.deposit"),
                "要有存入结果: " + output.joined());
        helper.assertTrue(output.numbers().contains(7L), "报出收下的件数 (4 + 3): " + output.joined());
        helper.assertTrue(output.keys().contains("message.miningdim.granary.command.deposit_hint"),
                "要提示「下线前点一下」: " + output.joined());
        helper.assertTrue(player.getInventory().getItem(0).isEmpty(), "默认连快捷栏一起存");

        // 打开「保留快捷栏」再存: 快捷栏那堆留在背包里。
        output.clear();
        helper.assertTrue(server(helper).getCommands().performPrefixedCommand(player.createCommandSourceStack()
                .withSource(output), "granary keephotbar true") == 1, "设置保留快捷栏");
        helper.assertTrue(output.keys().contains("message.miningdim.granary.command.keephotbar_set"),
                "要回显设置: " + output.joined());
        helper.assertTrue(GranaryDeposit.keepsHotbar(player), "开关按玩家记住");

        player.getInventory().setItem(0, new ItemStack(Items.WHEAT, 5));
        output.clear();
        helper.assertTrue(server(helper).getCommands().performPrefixedCommand(player.createCommandSourceStack()
                .withSource(output), "granary deposit") >= 0, "再存一次");
        helper.assertTrue(player.getInventory().getItem(0).getCount() == 5,
                "保留快捷栏之后, 快捷栏那堆留在背包里");
        helper.succeed();
    }

    // ============================================================
    // 夹具
    // ============================================================

    /** 一座验收好的粮棚 + 一个启用中的囤。 */
    private record Fixture(ServerPlayer owner, BlockPos centerRelative, long nowHour) {

        ServerLevel level(GameTestHelper helper) {
            return helper.getLevel();
        }

        BlockPos binRelative() {
            return new BlockPos(centerRelative.getX() - 1, LOW, LOW);
        }

        BlockPos center(GameTestHelper helper) {
            return helper.absolutePos(centerRelative);
        }

        GranaryBinBlockEntity bin(GameTestHelper helper) {
            return (GranaryBinBlockEntity) helper.getBlockEntity(binRelative());
        }

        long nowMinute() {
            return nowHour * 60L;
        }

        GranaryDeposit.Result deposit(GameTestHelper helper, ServerPlayer player) {
            return GranaryDeposit.depositAt(level(helper), bin(helper), player, true, nowMinute());
        }
    }

    private static Fixture accept(GameTestHelper helper, int slot) {
        ServerPlayer owner = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);
        setFarmerLevel(owner, 1);
        buildRoom(helper, slot, 3);
        placeCenter(helper, owner, center(slot));
        placeBin(helper, owner, bin(slot, LOW, LOW));
        helper.setBlock(vent(slot), GranaryBlocks.VENT.get());
        helper.assertTrue(GranaryInspection.accept(helper.getLevel(), helper.absolutePos(center(slot)), owner)
                .accepted(), "夹具: 先验收成粮棚");
        return new Fixture(owner, center(slot), fixtureHour());
    }

    /** 用例里的「现在」: 一个够大的整点, 免得和入仓时刻的哨兵 0 撞上。 */
    private static long fixtureHour() {
        return 3_000_000L;
    }

    private static net.minecraft.server.MinecraftServer server(GameTestHelper helper) {
        return helper.getLevel().getServer();
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
