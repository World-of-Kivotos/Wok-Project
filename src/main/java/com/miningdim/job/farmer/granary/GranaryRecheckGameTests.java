package com.miningdim.job.farmer.granary;

import com.miningdim.core.MiningConstants;
import com.miningdim.job.JobId;
import com.miningdim.job.farmer.granary.block.GranaryBlocks;
import com.miningdim.testutil.MockGameTestPlayers;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.common.util.BlockSnapshot;
import net.minecraftforge.event.level.BlockEvent;
import net.minecraftforge.event.level.ChunkEvent;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

/**
 * 损坏复查调度的 GameTest (docs/Spoilage_Granary_DesignSpec.md「损坏与宽限」「结构扫描器」)。
 *
 * 锁五件事: 方块一变就排队、同一座最快每 20 tick 一次、每 tick 最多真扫一座、每 5 分钟按
 * {@code floorMod(gameTime + 坐标, 6000)} 错峰自查一座、跨到未加载区块时**不判也不去读区块**。
 *
 * 时刻与 tick 都由用例显式传进调度器 ({@code tick(server, gameTime)}), 于是不必真的等到 6000 tick。
 */
@GameTestHolder(MiningConstants.MODID)
@PrefixGameTestTemplate(false)
public final class GranaryRecheckGameTests {

    private static final String EMPTY = "empty";
    private static final String BATCH = "granary_recheck";

    /** 每条用例占自己的一条槽位 (同一批次的用例并行跑且共用区域)。 */
    private static final int SLOT = 16;

    private static final int LOW = 1;
    private static final int HIGH = 3;

    private static final long TICK = 4_000_000L;   // 随便一个大 tick, 避开「还没扫过」的哨兵 0

    /**
     * 这一批结束后把账本清干净: 粮仓账本是**全服共享**的存档数据, 留下会在下一轮 (以及下一批) 的
     * 错峰自查里变成"不知道哪来的一座粮仓", 与 {@link #clashFreeSlot} 想要避免的撞车是同一个病根。
     */
    @net.minecraft.gametest.framework.AfterBatch(batch = BATCH)
    public static void clearLedger(ServerLevel level) {
        GranaryTestLedger.clear(level);
    }

    // ============================================================
    // 方块一变就查
    // ============================================================

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void aBlockChangeAloneFindsTheDamage(GameTestHelper helper) {
        int slot = 0;
        ServerLevel level = helper.getLevel();
        ServerPlayer owner = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);
        setFarmerLevel(owner, 1);
        GranaryRecheck.reset();
        buildRoom(helper, slot, 3);
        placeCenter(helper, owner, center(slot));
        placeBin(helper, owner, bin(slot, LOW, LOW));
        helper.setBlock(vent(slot), GranaryBlocks.VENT.get());
        BlockPos centerPos = helper.absolutePos(center(slot));
        helper.assertTrue(GranaryInspection.accept(level, centerPos, owner, TICK).accepted(), "先验收成粮棚");
        long gid = GranarySavedData.get(level).centerGranary(level.dimension().location(), centerPos);
        helper.assertTrue(GranarySavedData.get(level).granary(gid).status() == GranaryRecord.Status.OK,
                "验收后是完好的");

        // 有人把墙挖了 (走真实的破坏事件路径), 但**没有任何人打开账台**。
        BlockPos wallPos = new BlockPos(slot * SLOT + 2, 2, HIGH);
        helper.setBlock(wallPos, Blocks.OAK_LEAVES);
        GranarySystem.onBlockBroken(new BlockEvent.BreakEvent(level, helper.absolutePos(wallPos),
                level.getBlockState(helper.absolutePos(wallPos)), owner));
        helper.assertTrue(GranaryRecheck.isQueued(level.dimension().location(), centerPos),
                "屋子范围内的方块一变就要排队");

        // 队列是全服共用的 (同一批次里别的用例、别批留下的粮仓都可能排在前面), 所以反复 tick 直到轮到这一座。
        long scanned = tickUntilScanned(helper, centerPos, TICK + 1L);
        helper.assertTrue(scanned > 0L, "队列总要把这一座排到");
        helper.assertTrue(GranarySavedData.get(level).granary(gid).status() == GranaryRecord.Status.DAMAGED,
                "没人开账台, 损坏也要被复查发现");
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void changesFarFromEveryGranaryAreIgnored(GameTestHelper helper) {
        int slot = 1;
        ServerLevel level = helper.getLevel();
        ServerPlayer owner = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);
        buildRoom(helper, slot, 3);
        placeCenter(helper, owner, center(slot));
        // 放置事件自己就会给这座粮仓打脏标记, 所以从「放完之后」从零开始数。
        GranaryRecheck.reset();
        BlockPos centerPos = helper.absolutePos(center(slot));
        var dimension = level.dimension().location();

        // 离这座粮仓 100 格外的一砖一瓦: 与屋子范围 (±24) 没有交集, 不该排队。
        BlockPos far = helper.absolutePos(new BlockPos(slot * SLOT + 100, 4, 100));
        GranaryRecheck.markDirty(level, far);
        helper.assertTrue(!GranaryRecheck.isQueued(dimension, centerPos), "够不着的方块变动不该惊动粮仓");

        // 边界: 正好在 ±24 上的要算 (屋子可能正好盖到那里)。
        BlockPos edge = centerPos.offset(GranaryStructureScanner.HORIZONTAL_RADIUS, 0, 0);
        GranaryRecheck.markDirty(level, edge);
        helper.assertTrue(GranaryRecheck.isQueued(dimension, centerPos), "边界上的变动要算数");
        helper.succeed();
    }

    // ============================================================
    // 冷却与排队
    // ============================================================

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void theSameGranaryIsScannedAtMostOncePerTwentyTicks(GameTestHelper helper) {
        int slot = 2;
        ServerLevel level = helper.getLevel();
        ServerPlayer owner = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);
        GranaryRecheck.reset();
        buildRoom(helper, slot, 3);
        placeCenter(helper, owner, center(slot));
        helper.setBlock(vent(slot), GranaryBlocks.VENT.get());
        BlockPos centerPos = helper.absolutePos(center(slot));
        GranaryRecheck.markDirty(level, centerPos);
        helper.assertTrue(GranaryRecheck.tick(level.getServer(), TICK) == 1, "第一趟立刻扫");

        // 刚扫完又有人动方块: 20 tick 之内不重复扫。
        GranaryRecheck.markDirty(level, helper.absolutePos(center(slot)).offset(1, 0, 0));
        GranaryRecheck.tick(level.getServer(), TICK + 5L);
        helper.assertTrue(GranaryRecheck.lastScanTick(level.dimension().location(), centerPos) == TICK,
                "20 tick 之内不该再扫同一座 (上一趟的 tick 不变)");
        helper.assertTrue(GranaryRecheck.isQueued(level.dimension().location(), centerPos),
                "冷却里也留在队里, 不是丢掉");

        // 满 20 tick: 扫。
        helper.assertTrue(GranaryRecheck.tick(level.getServer(), TICK + 20L) == 1, "满 20 tick 就扫");
        helper.assertTrue(GranaryRecheck.lastScanTick(level.dimension().location(), centerPos) == TICK + 20L,
                "tick 更新");
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void onlyOneGranaryIsScannedPerTick(GameTestHelper helper) {
        int first = 3;
        int second = 4;
        ServerLevel level = helper.getLevel();
        ServerPlayer owner = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);
        buildRoom(helper, first, 3);
        placeCenter(helper, owner, center(first));
        buildRoom(helper, second, 3);
        placeCenter(helper, owner, center(second));
        // 两座都放好之后再从零开始排队 (放置事件自己会打标记)。
        GranaryRecheck.reset();

        BlockPos firstPos = helper.absolutePos(center(first));
        BlockPos secondPos = helper.absolutePos(center(second));
        var dimension = level.dimension().location();
        GranaryRecheck.markDirty(level, firstPos);
        GranaryRecheck.markDirty(level, secondPos);
        helper.assertTrue(GranaryRecheck.isQueued(dimension, firstPos)
                && GranaryRecheck.isQueued(dimension, secondPos), "两座都排上了");

        // 一 tick 只真扫一座: 这一 tick 里被扫到的「我这两座」最多一座。
        long tick = TICK;
        GranaryRecheck.tick(level.getServer(), tick);
        int scannedThisTick = 0;
        if (GranaryRecheck.lastScanTick(dimension, firstPos) == tick) {
            scannedThisTick++;
        }
        if (GranaryRecheck.lastScanTick(dimension, secondPos) == tick) {
            scannedThisTick++;
        }
        helper.assertTrue(scannedThisTick <= 1, "一 tick 最多扫一座, 实际 " + scannedThisTick);

        // 队列会一座一座把两座都排到。
        helper.assertTrue(tickUntilScanned(helper, firstPos, tick + 1L) > 0L, "第一座排到了");
        helper.assertTrue(tickUntilScanned(helper, secondPos, tick + 1L) > 0L, "第二座也排到了");
        helper.assertTrue(GranaryRecheck.lastScanTick(dimension, firstPos)
                != GranaryRecheck.lastScanTick(dimension, secondPos), "两座不是同一 tick 扫的");
        helper.succeed();
    }

    // ============================================================
    // 定期错峰自查
    // ============================================================

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void theSelfCheckPicksTheGranaryWhoseHashMatchesTheTick(GameTestHelper helper) {
        int slot = clashFreeSlot(helper);
        ServerLevel level = helper.getLevel();
        ServerPlayer owner = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);
        setFarmerLevel(owner, 1);
        GranaryRecheck.reset();
        buildRoom(helper, slot, 3);
        placeCenter(helper, owner, center(slot));
        placeBin(helper, owner, bin(slot, LOW, LOW));
        helper.setBlock(vent(slot), GranaryBlocks.VENT.get());
        BlockPos centerPos = helper.absolutePos(center(slot));
        helper.assertTrue(GranaryInspection.accept(level, centerPos, owner, TICK).accepted(), "先验收成粮棚");
        var dimension = level.dimension().location();
        long gid = GranarySavedData.get(level).centerGranary(dimension, centerPos);

        // 悄悄把墙挖了, 但**不打脏标记** (模拟「没人碰过、只是自查发现问题」)。
        helper.setBlock(new BlockPos(slot * SLOT + 2, 2, HIGH), Blocks.OAK_LEAVES);
        GranaryRecheck.reset();
        helper.assertTrue(GranaryRecheck.queuedCount() == 0, "这一趟只有自查, 队列是空的");

        // 到期的那一 tick: floorMod(tick + 打包坐标, 6000) == 0。
        // 挑一个**只有这一座**到期的 tick (账本里别的粮仓也在同一套轮值里, 一 tick 只扫一座)。
        long due = dueTickFor(helper, centerPos);
        helper.assertTrue(due > 0L, "总能找到只轮到这一座的 tick");
        GranaryRecheck.tick(level.getServer(), due - 1L);
        helper.assertTrue(GranaryRecheck.lastScanTick(dimension, centerPos) == 0L,
                "错开的那一 tick 不轮到他");
        GranaryRecheck.tick(level.getServer(), due);
        helper.assertTrue(GranaryRecheck.lastScanTick(dimension, centerPos) == due,
                "轮到的这一 tick 自查一次");
        helper.assertTrue(GranarySavedData.get(level).granary(gid).status() == GranaryRecord.Status.DAMAGED,
                "自查也要能发现损坏");
        helper.succeed();
    }

    /**
     * 找一个「只有这座粮仓到期」的 tick。
     *
     * 轮值公式是全服共用一条的, 而账本里还有别批留下的粮仓 —— 所以要挑一个不撞车的 tick,
     * 否则一 tick 只扫一座的预算会被别人先用掉。
     */
    private static long dueTickFor(GameTestHelper helper, BlockPos mine) {
        var centers = GranarySavedData.get(helper.getLevel().getServer().overworld()).centers();
        for (long tick = TICK; tick < TICK + GranaryRecheck.SELF_CHECK_PERIOD_TICKS; tick++) {
            if (Math.floorMod(tick + mine.asLong(), GranaryRecheck.SELF_CHECK_PERIOD_TICKS) != 0) {
                continue;
            }
            boolean clash = false;
            for (GranarySavedData.PosKey center : centers) {
                if (center.packedPos() != mine.asLong()
                        && Math.floorMod(tick + center.packedPos(),
                        GranaryRecheck.SELF_CHECK_PERIOD_TICKS) == 0) {
                    clash = true;
                    break;
                }
            }
            if (!clash) {
                return tick;
            }
        }
        return -1L;
    }

    /**
     * 挑一段「不撞车」的地基: 轮值余数是 {@code floorMod(tick + 坐标)} 算的, 一座粮仓只会在**一个**余数上到期
     * (6000 tick 一轮)。账本里若还留着别批 / 上一轮跑剩的粮仓, 而它的中心正好与我们的同余, 那一 tick 的预算
     * 就永远被别人先用掉 —— 于是这里不再写死 slot, 而是往右找一段没人同余的地基。
     *
     * <p>这不是为了迁就脏数据: 轮值是全服一条公式, 同余的两座本来就有一座永远排不上, 用例要钉的是"到期的
     * 那一座被扫到", 就不能让结论取决于账本里还躺着什么。</p>
     */
    private static int clashFreeSlot(GameTestHelper helper) {
        var centers = GranarySavedData.get(helper.getLevel().getServer().overworld()).centers();
        for (int slot = 5; slot < 120; slot++) {
            long mine = helper.absolutePos(center(slot)).asLong();
            boolean clash = false;
            for (GranarySavedData.PosKey center : centers) {
                if (center.packedPos() != mine && Math.floorMod(center.packedPos() - mine,
                        GranaryRecheck.SELF_CHECK_PERIOD_TICKS) == 0) {
                    clash = true;
                    break;
                }
            }
            if (!clash) {
                return slot;
            }
        }
        throw new IllegalStateException("找不到一段不撞车的地基");
    }

    // ============================================================
    // 区块
    // ============================================================

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void chunkLoadMarksGranariesInReach(GameTestHelper helper) {
        int slot = 6;
        ServerLevel level = helper.getLevel();
        ServerPlayer owner = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);
        GranaryRecheck.reset();
        buildRoom(helper, slot, 3);
        placeCenter(helper, owner, center(slot));
        BlockPos centerPos = helper.absolutePos(center(slot));
        var dimension = level.dimension().location();

        // 隔着 100 格的那个区块加载: 与屋子范围 (±24) 没交集, 不该排队。
        ChunkPos far = new ChunkPos(centerPos.getX() + 100, centerPos.getZ() + 100);
        GranarySystem.onChunkLoad(new ChunkEvent.Load(level.getChunk(far.x, far.z), false));
        helper.assertTrue(!GranaryRecheck.isQueued(dimension, centerPos), "够不着的区块加载不惊动粮仓");

        // 自己所在的区块加载: 排队 (跨区块的粮仓就是靠这一条补判的)。
        ChunkPos own = new ChunkPos(centerPos);
        GranarySystem.onChunkLoad(new ChunkEvent.Load(level.getChunk(own.x, own.z), false));
        helper.assertTrue(GranaryRecheck.isQueued(dimension, centerPos), "屋子所在的区块加载要排队");
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void unloadedChunksHoldTheStateWithoutReadingThem(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        MinecraftServer server = level.getServer();
        ServerPlayer owner = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);
        GranaryRecheck.reset();

        // 远到没加载的坐标上摆一座粮仓 (只写账本, 不碰世界): 复查既不判它、也不该去把区块读出来。
        BlockPos farAway = new BlockPos(20_000, 64, 20_000);
        var dimension = level.dimension().location();
        GranarySavedData data = GranarySavedData.get(server.overworld());
        long gid = data.nextGranaryId();
        data.claimCenter(dimension, farAway, owner.getUUID(), gid);
        data.putGranary(GranaryRecord.unaccepted(gid, owner.getUUID())
                .accepted(GranaryTier.SHED, owner.getUUID(), TICK));
        ChunkPos chunk = new ChunkPos(farAway);
        helper.assertTrue(!level.getChunkSource().hasChunk(chunk.x, chunk.z), "这个区块本来就没加载");

        GranaryRecheck.markDirty(level, farAway);
        GranaryRecheck.tick(server, TICK);
        helper.assertTrue(GranaryRecheck.lastScanTick(dimension, farAway) == 0L,
                "跨到未加载区块时不判 (没扫就没有扫描记录)");
        helper.assertTrue(!level.getChunkSource().hasChunk(chunk.x, chunk.z),
                "复查绝不该为了判定去把区块读出来");
        helper.assertTrue(data.granary(gid).status() == GranaryRecord.Status.OK,
                "保持原状态 (没有凭空判成损坏)");

        // 收尾: 把账本上这条还回去, 别留给别批。
        GranarySystem.releaseCenter(level, farAway);
        helper.succeed();
    }

    // ============================================================
    // 夹具
    // ============================================================

    /**
     * 反复 tick 直到这座粮仓被扫到。
     *
     * 队列与轮值都是**全服共用一条**的: 同一批次里别的用例、别批留下的粮仓都可能排在这一座前面,
     * 所以用例不能断言全局队列长度, 只能断言「这一座最后会被排到」。
     */
    private static long tickUntilScanned(GameTestHelper helper, BlockPos center, long from) {
        ServerLevel level = helper.getLevel();
        var dimension = level.dimension().location();
        for (long tick = from; tick < from + 200L; tick++) {
            GranaryRecheck.tick(level.getServer(), tick);
            long scanned = GranaryRecheck.lastScanTick(dimension, center);
            if (scanned > 0L) {
                return scanned;
            }
        }
        return 0L;
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
