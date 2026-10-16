package com.miningdim.job.farmer.granary;

import com.miningdim.core.MiningConstants;
import com.miningdim.job.JobId;
import com.miningdim.job.farmer.granary.block.GranaryBinBlockEntity;
import com.miningdim.job.farmer.granary.block.GranaryBlocks;
import com.miningdim.testutil.MockGameTestPlayers;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraftforge.common.util.FakePlayer;
import net.minecraftforge.common.util.FakePlayerFactory;
import net.minecraftforge.event.level.BlockEvent;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import java.util.UUID;

/**
 * 粮仓第一块的 GameTest: 储粮囤额度、归属记账与放置裁决
 * (docs/Spoilage_Granary_DesignSpec.md「储粮囤额度」「额度怎么记账」「保护与防复制」)。
 *
 * 锁四件事: 额度表按农夫等级查、额度是**全服合计**(含待领粮单占用)、按坐标记账且拆掉才归还、
 * 放置事件里假玩家一律放不了。
 */
@GameTestHolder(MiningConstants.MODID)
@PrefixGameTestTemplate(false)
public final class GranaryGameTests {

    private static final String EMPTY = "empty";
    private static final String BATCH = "granary_bins";

    private static final BlockPos FIRST = new BlockPos(1, 2, 1);
    private static final BlockPos SECOND = new BlockPos(3, 2, 1);
    private static final BlockPos THIRD = new BlockPos(5, 2, 1);
    private static final BlockPos FOURTH = new BlockPos(7, 2, 1);

    // ============================================================
    // 额度表与裁决 (纯逻辑)
    // ============================================================

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void binCapFollowsTheConfiguredTable(GameTestHelper helper) {
        helper.assertTrue(GranaryBinGuard.capForLevel(1) == 3, "L1 三个储粮囤 (不练农夫的人也是 L1)");
        helper.assertTrue(GranaryBinGuard.capForLevel(2) == 4, "每升一级多一个");
        helper.assertTrue(GranaryBinGuard.capForLevel(9) == 11, "L9 十一个");
        helper.assertTrue(GranaryBinGuard.capForLevel(10) == 13, "L10 毕业额外 +1, 共 13 个");
        helper.assertTrue(GranaryBinGuard.capForLevel(0) == 3, "越界等级钳到 L1");
        helper.assertTrue(GranaryBinGuard.capForLevel(99) == 13, "越界等级钳到 L10");

        helper.assertTrue(GranaryBinGuard.remainingFor(1, 1, 0) == 2, "L1 放了一个还剩两个");
        helper.assertTrue(GranaryBinGuard.remainingFor(1, 3, 0) == 0, "到顶就是 0");
        helper.assertTrue(GranaryBinGuard.remainingFor(1, 2, 1) == 0, "待领粮单占用的额度也算数");
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void placementGuardRejectsAtCapAndCountsPendingClaims(GameTestHelper helper) {
        helper.assertTrue(GranaryBinGuard.checkPlacement(1, 2, 0) == GranaryBinGuard.PlaceResult.ALLOW,
                "还没到顶就放行");
        helper.assertTrue(GranaryBinGuard.checkPlacement(1, 3, 0) == GranaryBinGuard.PlaceResult.REJECT_CAP_REACHED,
                "到顶拒放");
        helper.assertTrue(GranaryBinGuard.checkPlacement(1, 2, 1) == GranaryBinGuard.PlaceResult.REJECT_CAP_REACHED,
                "已放的 + 待领粮单占用的合起来算额度");
        helper.assertTrue(GranaryBinGuard.checkPlacement(10, 12, 0) == GranaryBinGuard.PlaceResult.ALLOW,
                "L10 放到第 12 个仍放行 (上限 13)");

        boolean threw = false;
        try {
            GranaryBinGuard.checkPlacement(1, -1, 0);
        } catch (IllegalArgumentException expected) {
            threw = true;
        }
        helper.assertTrue(threw, "负计数要直接报错, 不静默当成 0");
        helper.succeed();
    }

    // ============================================================
    // 归属账本
    // ============================================================

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void savedDataTracksOwnershipAndDerivedCounts(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        GranarySavedData data = GranarySavedData.get(level);
        UUID owner = UUID.randomUUID();
        var dimension = level.dimension().location();
        BlockPos pos = helper.absolutePos(FIRST);

        helper.assertTrue(data.ownerOf(dimension, pos) == null, "没登记过就是没有主人");
        helper.assertTrue(data.claimBin(dimension, pos, owner, 1), "第一次登记应当成功");
        helper.assertTrue(owner.equals(data.ownerOf(dimension, pos)), "登记后能反查回主人");
        helper.assertTrue(data.seqOf(dimension, pos) == 1, "入册序号照写");
        helper.assertTrue(data.binCount(owner) == 1, "派生计数 +1");

        helper.assertTrue(data.claimBin(dimension, pos, owner, 1), "同一主人的重复登记是幂等的");
        helper.assertTrue(data.binCount(owner) == 1, "幂等登记不重复计数");
        helper.assertTrue(!data.claimBin(dimension, pos, UUID.randomUUID(), 2),
                "同一坐标换主人必须被拒 (记账表就是唯一权威)");

        helper.assertTrue(owner.equals(data.releaseBin(dimension, pos)), "拆除归还的是原主人");
        helper.assertTrue(data.binCount(owner) == 0, "归还后计数归零");
        helper.assertTrue(data.ownerOf(dimension, pos) == null, "归还后没有主人");
        helper.assertTrue(data.releaseBin(dimension, pos) == null, "重复归还不报错也不减成负数");
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void binBlockEntityKeepsInventoryAndRefusesSides(GameTestHelper helper) {
        helper.setBlock(FIRST, GranaryBlocks.BIN.get());
        GranaryBinBlockEntity bin = (GranaryBinBlockEntity) helper.getBlockEntity(FIRST);
        helper.assertTrue(bin != null, "储粮囤方块实体应当已创建");
        helper.assertTrue(bin.getContainerSize() == GranaryConfig.binSlots(), "格数取配置");
        helper.assertTrue(bin.isEmpty(), "新放的囤是空的");

        UUID owner = UUID.randomUUID();
        bin.assignOwner(owner, 2);
        bin.setItem(0, new ItemStack(Items.WHEAT, 12));
        helper.assertTrue(!bin.isEmpty(), "放了东西就不再是空的");
        helper.assertTrue(owner.equals(bin.owner()) && bin.seq() == 2, "归属写在方块实体上");

        // 不接任何一面: 漏斗与 Create 都插不进来 (反漏洞 #34)。
        helper.assertTrue(bin.getSlotsForFace(net.minecraft.core.Direction.UP).length == 0,
                "不对外开放物品接口");
        helper.assertTrue(!bin.canPlaceItemThroughFace(0, new ItemStack(Items.WHEAT), net.minecraft.core.Direction.UP),
                "任何一面都不许插入");
        helper.assertTrue(!bin.canTakeItemThroughFace(0, bin.getItem(0), net.minecraft.core.Direction.DOWN),
                "任何一面都不许抽出");

        // 存读一轮: 格数与内容都按存档走 (配置以后改了也不影响已经放下的囤)。
        CompoundTag saved = bin.saveWithoutMetadata();
        GranaryBinBlockEntity reloaded = new GranaryBinBlockEntity(bin.getBlockPos(), bin.getBlockState());
        reloaded.load(saved);
        helper.assertTrue(reloaded.getContainerSize() == bin.getContainerSize(), "格数随存档");
        helper.assertTrue(reloaded.getItem(0).is(Items.WHEAT) && reloaded.getItem(0).getCount() == 12,
                "内容随存档");
        helper.assertTrue(owner.equals(reloaded.owner()) && reloaded.seq() == 2, "归属随存档");
        helper.succeed();
    }

    // ============================================================
    // 放置事件 (真路径)
    // ============================================================

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void placeEventRegistersOwnershipAndCancelsAtCap(GameTestHelper helper) {
        ServerPlayer player = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);
        setFarmerLevel(player, 1); // L1 -> 三个

        helper.assertTrue(place(helper, player, FIRST), "第一个囤应当放得下");
        helper.assertTrue(place(helper, player, SECOND), "第二个囤应当放得下");
        helper.assertTrue(place(helper, player, THIRD), "第三个囤应当放得下 (L1 上限 3)");
        helper.assertTrue(GranarySystem.binCount(helper.getLevel(), player.getUUID()) == 3, "三个都记在账上");

        helper.assertTrue(!place(helper, player, FOURTH), "第四个囤必须被拒 (L1 上限 3)");
        helper.assertTrue(GranarySystem.binCount(helper.getLevel(), player.getUUID()) == 3,
                "被拒的放置不占额度");
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void placeEventRejectsFakePlayers(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        FakePlayer fake = FakePlayerFactory.getMinecraft(level);
        helper.setBlock(SECOND, GranaryBlocks.BIN.get());
        BlockEvent.EntityPlaceEvent event = new BlockEvent.EntityPlaceEvent(
                net.minecraftforge.common.util.BlockSnapshot.create(level.dimension(), level,
                        helper.absolutePos(SECOND)),
                Blocks.AIR.defaultBlockState(), fake);
        GranarySystem.onBlockPlaced(event);
        helper.assertTrue(event.isCanceled(), "假玩家一律放不了 (额度不能记在假玩家名下)");
        helper.assertTrue(GranarySavedData.get(level)
                .binCount(fake.getUUID()) == 0, "假玩家名下不留记录");
        helper.succeed();
    }

    // ============================================================
    // 夹具
    // ============================================================

    /** 走真实的放置事件路径放一个储粮囤; 返回是否放得下。 */
    private static boolean place(GameTestHelper helper, Player player, BlockPos relative) {
        ServerLevel level = helper.getLevel();
        BlockPos pos = helper.absolutePos(relative);
        helper.setBlock(relative, GranaryBlocks.BIN.get());
        BlockEvent.EntityPlaceEvent event = new BlockEvent.EntityPlaceEvent(
                net.minecraftforge.common.util.BlockSnapshot.create(level.dimension(), level, pos),
                Blocks.AIR.defaultBlockState(), player);
        GranarySystem.onBlockPlaced(event);
        return !event.isCanceled();
    }

    private static void setFarmerLevel(ServerPlayer player, int level) {
        com.miningdim.entry.MiningCapabilities.get(player)
                .orElseThrow(() -> new IllegalStateException("mock 玩家未挂载 capability, 无法摆放职业等级"))
                .jobProgress(JobId.FARMER)
                .setLevel(level);
    }
}
