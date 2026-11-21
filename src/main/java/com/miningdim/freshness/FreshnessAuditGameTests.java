package com.miningdim.freshness;

import com.miningdim.core.MiningConstants;
import net.minecraft.commands.CommandSource;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import java.util.ArrayList;
import java.util.List;

/**
 * {@code /freshness audit} 的 GameTest (docs/Spoilage_Granary_DesignSpec.md「开服迁移」末尾与「安全阀」)。
 *
 * 锁四件事: 无章与已腐烂各自数得对、非容器与非易腐品不算数、**只统计已加载区块且不为巡查加载区块**、
 * 半径之外的不进来; 命令那一条另锁 OP 门与返回值 (需要处理的件数)。
 */
@GameTestHolder(MiningConstants.MODID)
@PrefixGameTestTemplate(false)
public final class FreshnessAuditGameTests {

    private static final String EMPTY = "empty";
    private static final String BATCH = "freshness_audit";

    /** 每条用例占自己的一条槽位 (同一批次的用例并行跑且共用区域, 巡查半径要落在自己槽位里)。 */
    private static final int SLOT = 16;

    /** 巡查半径: 槽位宽 16, 取 6 就够罩住本用例摆的东西, 又够不到隔壁用例。 */
    private static final int RADIUS = 6;

    private static BlockPos chest(int slot, int dx, int dz) {
        return new BlockPos(slot * SLOT + dx, 1, dz);
    }

    private static BlockPos origin(int slot) {
        return new BlockPos(slot * SLOT, 0, 0);
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void scanCountsUnstampedAndRotten(GameTestHelper helper) {
        int slot = 0;
        clear(helper, slot);
        ServerLevel level = helper.getLevel();
        BlockPos origin = helper.absolutePos(origin(slot));
        long nowMinute = FreshnessClockData.nowMinute(level);

        // 箱子: 3 件无章面包 + 1 件盖过章的小麦。
        Container chest = chest(helper, chest(slot, 2, 0), Blocks.CHEST);
        chest.setItem(0, new ItemStack(Items.BREAD, 3));
        ItemStack stamped = new ItemStack(Items.WHEAT, 1);
        FreshnessData.stamp(stamped, nowMinute);
        chest.setItem(1, stamped);

        // 木桶: 1 件早就烂掉的面包 (盖了一个保质期之前的章) + 2 个石头 (不腐烂)。
        Container barrel = chest(helper, chest(slot, 0, 2), Blocks.BARREL);
        ItemStack rotten = new ItemStack(Items.BREAD, 1);
        int shelfLife = FreshnessCategories.shelfLifeHours(rotten);
        FreshnessData.stampAt(rotten, nowMinute - (long) shelfLife * 60L - 60L, 0);
        barrel.setItem(0, rotten);
        barrel.setItem(1, new ItemStack(Items.STONE, 2));

        // 不是容器 (告示牌) 与半径外的箱子都不该进来。
        helper.setBlock(chest(slot, -2, 0), Blocks.OAK_SIGN);
        Container farChest = chest(helper, chest(slot, 0, 30), Blocks.CHEST);   // 半径外: 不该进来
        farChest.setItem(0, new ItemStack(Items.BREAD, 5));

        BlockPos chestPos = helper.absolutePos(chest(slot, 2, 0));
        Container verify = (Container) level.getBlockEntity(chestPos);
        String dump = "same=" + (verify == chest) + " slot0=" + verify.getItem(0).getCount() + "x"
                + verify.getItem(0).getItem() + " slot1=" + verify.getItem(1).getCount() + "x"
                + verify.getItem(1).getItem();
        FreshnessAudit.Report report = FreshnessAudit.scan(level, chestPos, 3);
        String context = " chest[" + dump + "] barrel[" + slotDump(barrel) + "] report[" + describe(report) + "]";
        helper.assertTrue(report.containers() == 2, "两个有东西的容器, 实际 " + report.containers() + context);
        helper.assertTrue(report.perishables() == 5, "可食用/易腐 5 件 (3 无章面包 + 1 章小麦 + 1 烂面包), 实际 "
                + report.perishables() + context);
        helper.assertTrue(report.unstamped() == 3, "无章 3 件, 实际 " + report.unstamped() + context);
        helper.assertTrue(report.rotten() == 1, "已腐烂 1 件, 实际 " + report.rotten() + context);
        helper.assertTrue(!report.clean(), "有要处理的就不算干净");
        helper.assertTrue(report.problems() == 4, "要处理 3 + 1 = 4 件, 实际 " + report.problems());
        helper.assertTrue(report.findings().size() == 2, "两个容器各报一条, 实际 "
                + report.findings().size());
        helper.assertTrue(report.findings().get(0).unstamped() == 3, "无章多的排前面, 实际 "
                + describe(report));
        helper.assertTrue(report.findings().get(0).pos().equals(helper.absolutePos(chest(slot, 2, 0))),
                "第一条是那个箱子: " + report.findings().get(0).pos());
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void scanIgnoresCleanAndNonContainerBlocks(GameTestHelper helper) {
        int slot = 1;
        clear(helper, slot);
        ServerLevel level = helper.getLevel();
        BlockPos origin = helper.absolutePos(origin(slot));
        long nowMinute = FreshnessClockData.nowMinute(level);

        // 一个空箱子 + 一个只装石头的箱子: 都不算「有问题」。
        chest(helper, chest(slot, 2, 0), Blocks.CHEST);
        Container stoneOnly = chest(helper, chest(slot, 0, 2), Blocks.BARREL);
        stoneOnly.setItem(0, new ItemStack(Items.STONE, 64));
        helper.setBlock(chest(slot, -2, 0), Blocks.OAK_SIGN);

        FreshnessAudit.Report report = FreshnessAudit.scan(level,
                helper.absolutePos(chest(slot, 0, 2)), 3);
        // 只认自己摆的那两个坐标: 同批别的用例、上一轮留下的容器都可能落在半径里。
        helper.assertTrue(report.entries().stream().anyMatch(entry ->
                        entry.pos().equals(helper.absolutePos(chest(slot, 0, 2)))),
                "装石头的木桶算「有东西的容器」: " + describe(report));
        helper.assertTrue(report.entries().stream().noneMatch(entry ->
                        entry.pos().equals(helper.absolutePos(chest(slot, 2, 0)))),
                "空箱子不算 (里面一件东西都没有): " + describe(report));
        helper.assertTrue(report.perishables() == 0 && report.unstamped() == 0 && report.rotten() == 0,
                "石头不是易腐品: " + describe(report));
        helper.assertTrue(report.clean(), "干净的时候要如实报干净");

        // 盖过章的东西不算无章 (同一个箱子补一件)。
        Container chest = (Container) level.getBlockEntity(helper.absolutePos(chest(slot, 2, 0)));
        ItemStack fresh = new ItemStack(Items.BREAD, 2);
        FreshnessData.stamp(fresh, nowMinute);
        chest.setItem(0, fresh);
        FreshnessAudit.Report stampedOnly = FreshnessAudit.scan(level, helper.absolutePos(chest(slot, 2, 0)), 3);
        helper.assertTrue(stampedOnly.unstamped() == 0, "盖过章的算易腐品但不算命中的「无章」: "
                + describe(stampedOnly));
        helper.assertTrue(stampedOnly.entries().stream().anyMatch(entry ->
                        entry.pos().equals(helper.absolutePos(chest(slot, 2, 0))) && entry.perishables() == 2),
                "箱子里那两件盖过章的面包算易腐品: " + describe(stampedOnly));
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void scanNeverLoadsChunks(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos origin = helper.absolutePos(origin(2));
        // 远处 (按区块算远了 40 个区块) 的坐标: 巡查不许把它读出来。
        BlockPos far = origin.offset(40 * 16, 0, 40 * 16);
        helper.assertTrue(!level.getChunkSource().hasChunk(far.getX() >> 4, far.getZ() >> 4),
                "夹具: 那个区块本来就没加载");
        FreshnessAudit.Report report = FreshnessAudit.scan(level, far, 8);
        helper.assertTrue(report.containers() == 0, "没加载的区块里什么都没有 (也不该被读出来)");
        helper.assertTrue(!level.getChunkSource().hasChunk(far.getX() >> 4, far.getZ() >> 4),
                "巡查跑完之后那个区块还是没加载");
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void theCommandReportsAndNeedsOperator(GameTestHelper helper) {
        int slot = 3;
        clear(helper, slot);
        ServerLevel level = helper.getLevel();
        BlockPos origin = helper.absolutePos(origin(slot));
        Container chest = chest(helper, chest(slot, 2, 0), Blocks.CHEST);
        chest.setItem(0, new ItemStack(Items.BREAD, 4));

        Capture output = new Capture();
        var source = helper.getLevel().getServer().createCommandSourceStack()
                .withLevel(level).withPosition(net.minecraft.world.phys.Vec3.atCenterOf(
                        helper.absolutePos(chest(slot, 2, 0))))
                .withSource(output);
        // 非 OP: 根字面量就带权限门, 直接拒掉。
        helper.assertTrue(level.getServer().getCommands().performPrefixedCommand(
                source.withPermission(0), "freshness audit 3") == 0, "非 OP 用不了");
        helper.assertTrue(output.texts().stream().noneMatch(text -> text.contains("无章")),
                "被拒时不该吐出巡查结果: " + output.texts());

        output.clear();
        helper.assertTrue(level.getServer().getCommands().performPrefixedCommand(
                source.withPermission(2), "freshness audit 3") == 4, "OP 跑一次, 返回值 = 要处理的 4 件");
        helper.assertTrue(output.texts().stream().anyMatch(text -> text.contains("无章 4")),
                "要报出无章的件数: " + output.texts());

        // 站到十格开外再看同一个半径: 那个箱子在半径外, 干净收场 (返回值 0)。
        output.clear();
        var farSource = helper.getLevel().getServer().createCommandSourceStack()
                .withLevel(level)
                .withPosition(net.minecraft.world.phys.Vec3.atCenterOf(
                        helper.absolutePos(chest(slot, 2, 10))))
                .withSource(output);
        helper.assertTrue(level.getServer().getCommands().performPrefixedCommand(
                farSource.withPermission(2), "freshness audit 3") == 0, "半径外的不算");
        helper.assertTrue(output.texts().stream().anyMatch(text -> text.contains("没发现")),
                "干净时说清楚: " + output.texts());
        helper.succeed();
    }

    // ============================================================
    // 夹具
    // ============================================================

    /**
     * 把自己那条槽位的地基与周围清空。
     *
     * GameTest 的世界跨轮保留: 上一轮留下的箱子与里面的东西都还在, 不清掉就会被巡查一起数进来
     * (巡查本来就是「把附近所有容器都数一遍」)。所以每条用例先把自己的地盘还原成空。
     */
    private static void clear(GameTestHelper helper, int slot) {
        int base = slot * SLOT;
        for (int x = -4; x <= 8; x++) {
            for (int y = -1; y <= 3; y++) {
                for (int z = -4; z <= 8; z++) {
                    helper.setBlock(new BlockPos(base + x, y, z), Blocks.AIR);
                }
            }
        }
    }
    /** 在相对坐标放一个容器方块, 返回它的容器 (方块实体)。 */
    private static Container chest(GameTestHelper helper, BlockPos relative, net.minecraft.world.level.block.Block block) {
        helper.setBlock(relative, block);
        BlockPos absolute = helper.absolutePos(relative);
        helper.assertTrue(helper.getLevel().getBlockEntity(absolute) instanceof Container,
                "fixture: container block entity expected at " + absolute);
        return (Container) helper.getLevel().getBlockEntity(absolute);
    }

    private static String slotDump(Container container) {
        List<String> parts = new ArrayList<>();
        for (int slot = 0; slot < Math.min(container.getContainerSize(), 4); slot++) {
            ItemStack stack = container.getItem(slot);
            parts.add(slot + ":" + stack.getCount() + "x" + stack.getItem()
                    + (FreshnessData.isStamped(stack) ? "#h" + FreshnessData.batchMinute(stack) : "#none"));
        }
        return String.join(", ", parts);
    }

    private static String describe(FreshnessAudit.Report report) {
        List<String> parts = new ArrayList<>();
        for (FreshnessAudit.Finding finding : report.findings()) {
            parts.add(finding.pos() + " " + finding.block() + " perish=" + finding.perishables()
                    + " unstamped=" + finding.unstamped() + " rotten=" + finding.rotten());
        }
        return "containers=" + report.containers() + " perish=" + report.perishables()
                + " unstamped=" + report.unstamped() + " rotten=" + report.rotten()
                + " findings=" + parts;
    }

    /** 收命令输出的命令源 (断言看文字里的数字)。 */
    private static final class Capture implements CommandSource {

        private final List<String> texts = new ArrayList<>();

        @Override
        public void sendSystemMessage(Component component) {
            texts.add(component.getString());
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
            texts.clear();
        }

        List<String> texts() {
            return List.copyOf(texts);
        }
    }
}
