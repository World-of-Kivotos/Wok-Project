package com.miningdim.job.munitions;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.miningdim.core.MiningConstants;
import com.miningdim.economy.AbuseGuard;
import com.miningdim.economy.Currency;
import com.miningdim.economy.EconomyService;
import com.miningdim.economy.EconomyServices;
import com.miningdim.economy.EconomyLedger;
import com.miningdim.economy.SqliteEconomyLedger;
import com.miningdim.economy.IEconomyService;
import com.miningdim.economy.PlayerAbuseState;
import com.miningdim.job.IJobService;
import com.miningdim.job.JobId;
import com.miningdim.job.JobProgress;
import com.miningdim.job.JobServices;
import com.miningdim.job.munitions.block.MunitionsBenchBlock;
import com.miningdim.job.munitions.block.MunitionsBenchBlockEntity;
import com.miningdim.job.munitions.block.MunitionsBenchCounter;
import com.miningdim.job.munitions.block.MunitionsBenchGeometry;
import com.miningdim.job.munitions.block.MunitionsBenchProgram;
import com.miningdim.job.munitions.menu.MunitionsBenchMenu;
import com.miningdim.testutil.EntityBaseline;
import com.miningdim.testutil.MockGameTestPlayers;
import com.mojang.math.Axis;
import io.netty.channel.embedded.EmbeddedChannel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.BeforeBatch;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.network.protocol.game.ClientboundSoundPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.BooleanOp;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import net.minecraftforge.common.capabilities.ForgeCapabilities;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;
import net.minecraftforge.items.IItemHandler;
import net.minecraftforge.items.ItemStackHandler;
import org.joml.Matrix4f;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;

/**
 * 军火商核心逻辑 GameTest (Munitions_Job_DesignSpec 四/五/六/七/九章测试断言)。断言具体业务数值 (删被测核心逻辑
 * 测试必挂, 禁 is-not-null 弱校验; 含边界值)。
 *
 * compileOnly 铁律 (本任务硬约束): 全部断言只触纯逻辑业务结果 (产多少发/什么口径/扣多少料/扣多少工费/给多少经验),
 * 绝不进物化路径 ({@link MunitionsAmmoFactory#materialize} / TACZ {@code AmmoItemBuilder}) —— dev GameTest 运行期
 * TACZ 未加载, 进物化即 NoClassDefFoundError。BE 用例只调 {@link MunitionsBenchBlockEntity#trySelectCaliber} 与
 * {@link MunitionsBenchBlockEntity#settleForOwner}, 二者的产能/料/工费/经验路径全程纯逻辑; 物化点
 * refreshOutputStack 在 TACZ 未加载时 materialize 短路返回 EMPTY (输出槽留空), 不抛, 故 BE 结算可安全跑。
 *
 * 数值断言均以 {@link MunitionsConfig} 默认值为准 (C6: GameTest 用 config 默认真值)。纯逻辑用 template = "empty"
 * (职业框架已建 data/miningdim/structures/empty.nbt)。
 */
@GameTestHolder(MiningConstants.MODID)
@PrefixGameTestTemplate(false)
public final class MunitionsGameTests {

    private static final String EMPTY = "empty";
    private static final String BATCH = "munitions";
    /** 军火台冲压音节拍用例独占的批 (要把职业门面替身挂一百多 tick, 见 wideBenchStrikeSoundFollowsTheProgramPhase)。 */
    private static final String TIMING_BATCH = "munitions_bench_timing";
    /** 普通档的档位下标 (运动程序按档位取速度: 普通 40 tick 一个循环, 即程序原速)。 */
    private static final int BASE_TIER = 0;
    /** 计数屏同步用例独占的批 (要挂几十 tick 数方块实体数据包, 见 wideBenchCounterPushesOneUpdatePerVisibleChange)。 */
    private static final String COUNTER_SYNC_BATCH = "munitions_bench_counter_sync";

    /**
     * 批前钩子: 绑定 MunitionsConfig 默认值 (本子系统集成阶段才接进 MiningDim, runGameTestServer 时其 SERVER spec
     * 未经 Forge 加载; 不绑定则 dev 环境下 ConfigValue.get() 抛 IllegalStateException)。
     */
    @BeforeBatch(batch = BATCH)
    public static void beforeMunitionsBatch(ServerLevel level) {
        MunitionsConfig.ensureLoadedForTest();
    }

    @BeforeBatch(batch = TIMING_BATCH)
    public static void beforeMunitionsBenchTimingBatch(ServerLevel level) {
        MunitionsConfig.ensureLoadedForTest();
    }

    @BeforeBatch(batch = COUNTER_SYNC_BATCH)
    public static void beforeMunitionsBenchCounterSyncBatch(ServerLevel level) {
        MunitionsConfig.ensureLoadedForTest();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void benchLayoutKeepsLegacyDepthAndUsesWideForNewModel(GameTestHelper helper) {
        MunitionsBenchBlock bench = (MunitionsBenchBlock) ModMunitionsBlocks.MUNITIONS_BENCH.get();
        BlockPos origin = new BlockPos(4, 1, 4);

        BlockState legacyMain = bench.defaultBlockState().setValue(MunitionsBenchBlock.FACING, Direction.NORTH);
        helper.assertTrue(legacyMain.getValue(MunitionsBenchBlock.LAYOUT)
                        == MunitionsBenchBlock.Layout.LEGACY_DEPTH,
                "missing layout property must deserialize to legacy depth for existing worlds");
        helper.assertTrue(MunitionsBenchBlock.extensionPos(origin, legacyMain).equals(origin.south()),
                "legacy north-facing bench keeps its extension behind the main block");
        helper.assertTrue(legacyMain.getRenderShape() == RenderShape.MODEL,
                "legacy layout keeps the original static model");

        BlockState wideMain = legacyMain.setValue(MunitionsBenchBlock.LAYOUT, MunitionsBenchBlock.Layout.WIDE);
        BlockPos wideExtensionPos = origin.east();
        BlockState wideExtension = wideMain.setValue(MunitionsBenchBlock.PART, MunitionsBenchBlock.Part.EXTENSION);
        helper.assertTrue(MunitionsBenchBlock.extensionPos(origin, wideMain).equals(wideExtensionPos),
                "new north-facing bench occupies two horizontal blocks from left to right");
        helper.assertTrue(MunitionsBenchBlock.mainPos(wideExtensionPos, wideExtension).equals(origin),
                "wide extension resolves back to its main block");
        helper.assertTrue(wideMain.getRenderShape() == RenderShape.MODEL,
                "wide main block draws its static line model in the chunk mesh; only the moving parts come "
                        + "from the block entity renderer");
        helper.assertTrue(wideExtension.getRenderShape() == RenderShape.MODEL,
                "wide extension draws its static line model too; MODEL also keeps vanilla's breaking crack "
                        + "overlay and crack particles (ParticleEngine.crack skips RenderShape.INVISIBLE)");

        VoxelShape legacyCollision = legacyMain.getCollisionShape(helper.getLevel(), origin);
        assertBounds(helper, legacyCollision, 0.0D, 0.0D, 0.0D, 1.0D, 1.0D, 1.0D,
                "legacy bench keeps its original one-block collision box");
        assertBounds(helper, legacyMain.getShape(helper.getLevel(), origin), 0.0D, 0.0D, 0.0D, 1.0D, 1.0D, 1.0D,
                "legacy bench keeps its original one-block outline");

        for (MunitionsBenchBlock.Part part : MunitionsBenchBlock.Part.values()) {
            boolean isMain = part == MunitionsBenchBlock.Part.MAIN;
            float[][] staticBoxes = isMain ? MunitionsBenchGeometry.MAIN_BOXES : MunitionsBenchGeometry.EXTENSION_BOXES;
            float[][] partBoxes = isMain ? MunitionsBenchGeometry.MAIN_PART_BOXES : MunitionsBenchGeometry.EXTENSION_PART_BOXES;
            // 顶面的期望值取自基础档静态模型 JSON 这个独立真相源, 而不是照抄实现里的常数。
            double modelTopPixels = MunitionsBenchAssets.blockModelBoundsPixels(
                    MunitionsBenchAssets.lineModelName("munitions_bench", part.getSerializedName(), false))[4];
            for (Direction facing : Direction.Plane.HORIZONTAL) {
                BlockState state = wideMain.setValue(MunitionsBenchBlock.PART, part)
                        .setValue(MunitionsBenchBlock.FACING, facing);
                String label = facing + "-facing wide " + part.getSerializedName();
                VoxelShape collision = state.getCollisionShape(helper.getLevel(), origin);
                VoxelShape outline = state.getShape(helper.getLevel(), origin);
                int turns = MunitionsBenchBlock.quarterTurns(facing);

                // 碰撞: 整格一块实心柱, 高到这一格静态模型的最高点。台面只有 8 px (低于 9.6 px 的跨步高度), 贴着模型的碰撞
                // 会让玩家一步走上台面、站进运动件中间; 柱子也高过起跳高度, 跳不上去。
                helper.assertFalse(Shapes.joinIsNotEmpty(collision,
                                Block.box(0.0D, 0.0D, 0.0D, 16.0D, modelTopPixels, 16.0D), BooleanOp.NOT_SAME),
                        label + " collision must be one solid column up to the static model top " + modelTopPixels
                                + "px, got " + collision.toAabbs());
                helper.assertTrue(modelTopPixels > 20.04D,
                        label + " collision column must be taller than a player's jump (about 20.03px), got "
                                + modelTopPixels + "px");

                // 轮廓 (选择框 / 右键命中): 静态件的大块 + 运动件扫过的范围, 按方块状态转过去; 都在碰撞柱里。
                helper.assertFalse(Shapes.joinIsNotEmpty(outline,
                                Shapes.or(geometryShape(staticBoxes, turns), geometryShape(partBoxes, turns)),
                                BooleanOp.NOT_SAME),
                        label + " outline must be the MunitionsBenchGeometry static + moving-part boxes turned with "
                                + "the blockstate");
                helper.assertFalse(Shapes.joinIsNotEmpty(outline, collision, BooleanOp.ONLY_FIRST),
                        label + " outline must stay inside the collision column");
                AABB bounds = outline.bounds();
                helper.assertTrue(bounds.minX >= 0.0D && bounds.minZ >= 0.0D && bounds.minY == 0.0D
                                && bounds.maxX <= 1.0D && bounds.maxZ <= 1.0D,
                        label + " outline must stay inside its own cell, got " + bounds);
                helper.assertFalse(Shapes.joinIsNotEmpty(
                                Block.box(0.0D, 0.0D, 0.0D, 16.0D, MunitionsBenchGeometry.BODY_TOP_PX, 16.0D),
                                outline, BooleanOp.ONLY_FIRST),
                        label + " outline must fill the whole cabinet up to the worktop at "
                                + MunitionsBenchGeometry.BODY_TOP_PX + "px");
                double topPixels = bounds.maxY * 16.0D;
                helper.assertTrue(Math.abs(topPixels - modelTopPixels) <= 0.5D,
                        label + " outline top " + topPixels + "px must follow the static model top "
                                + modelTopPixels + "px");
                // 支撑面按轮廓算: 碰撞柱的整面不能让火把贴在台面上方的空气里。
                helper.assertFalse(Shapes.joinIsNotEmpty(state.getBlockSupportShape(helper.getLevel(), origin),
                                outline, BooleanOp.NOT_SAME),
                        label + " support shape must follow the visible outline, not the collision column");
            }
        }

        // 运动件点得中台子: 待机布局里皮带上各弹位那发弹的壳身正中 (皮带面 + 半个壳高) 与冲头夹着的弹头正中,
        // 四个朝向下都要落在它所在那一格的轮廓里, 否则右键会穿过它打到后面的方块。
        // 这些位置取自设计尺寸 (MunitionsBenchGeometry 的弹位、皮带面、弹高), 不取生成的轮廓盒子。
        float caseMiddle = MunitionsBenchGeometry.BELT_TOP_PX + MunitionsBenchGeometry.ROUND_CASE_HEIGHT_PX / 2.0F;
        float[][] movingPartPoints = new float[MunitionsBenchGeometry.SLOT_X_PX.length + 1][];
        for (int slot = 0; slot < MunitionsBenchGeometry.SLOT_X_PX.length; slot++) {
            movingPartPoints[slot] = new float[]{MunitionsBenchGeometry.SLOT_X_PX[slot], caseMiddle, MunitionsBenchGeometry.SLOT_Z_PX};
        }
        movingPartPoints[MunitionsBenchGeometry.SLOT_X_PX.length] = new float[]{MunitionsBenchGeometry.SPARK_X,
                MunitionsBenchGeometry.RAM_BULLET_REST_BOTTOM_PX + MunitionsBenchGeometry.ROUND_BULLET_HEIGHT_PX / 2.0F,
                MunitionsBenchGeometry.SPARK_Z};
        helper.assertTrue(MunitionsBenchGeometry.SLOT_X_PX[0] > 16.0F
                        && MunitionsBenchGeometry.SLOT_X_PX[MunitionsBenchGeometry.SLOT_X_PX.length - 1] < 16.0F,
                "the belt runs from the entry slot over the extension to the exit slot over the main cell");
        for (Direction facing : Direction.Plane.HORIZONTAL) {
            BlockState main = wideMain.setValue(MunitionsBenchBlock.FACING, facing);
            BlockPos extensionPos = MunitionsBenchBlock.extensionPos(origin, main);
            for (float[] point : movingPartPoints) {
                Vec3 at = MunitionsBenchBlock.benchPixelToWorld(origin, facing, point[0], point[1], point[2]);
                BlockPos cell = BlockPos.containing(at.x, origin.getY(), at.z);
                helper.assertTrue(cell.equals(origin) || cell.equals(extensionPos),
                        facing + "-facing: moving part at " + point[0] + "px must sit in one of the two cells, got " + cell);
                BlockState cellState = cell.equals(origin) ? main
                        : main.setValue(MunitionsBenchBlock.PART, MunitionsBenchBlock.Part.EXTENSION);
                Vec3 local = at.subtract(cell.getX(), cell.getY(), cell.getZ());
                boolean hit = false;
                for (AABB box : cellState.getShape(helper.getLevel(), cell).toAabbs()) {
                    hit |= box.contains(local);
                }
                helper.assertTrue(hit, facing + "-facing: the moving part at bench pixel (" + point[0] + ", " + point[1]
                        + ", " + point[2] + ") must be inside the outline so a right click on it opens the bench");
            }
        }

        // 手性: 压机 (主格最高的那块) 立在主格靠副格的一侧, 四个朝向都要跟着转过去。
        for (Direction facing : Direction.Plane.HORIZONTAL) {
            BlockState main = wideMain.setValue(MunitionsBenchBlock.FACING, facing);
            VoxelShape shape = main.getShape(helper.getLevel(), origin);
            double top = shape.max(Direction.Axis.Y);
            AABB press = null;
            for (AABB box : shape.toAabbs()) {
                if (Math.abs(box.maxY - top) < 1.0E-6D) {
                    press = press == null ? box : press.minmax(box);
                }
            }
            Direction toward = MunitionsBenchBlock.extensionDirection(main);
            helper.assertTrue(press != null && touchesCellFace(press, toward)
                            && !touchesCellFace(press, toward.getOpposite()),
                    facing + "-facing outline must rotate with the machine: the press head belongs against the "
                            + toward + " face (towards the extension), got " + press);
        }
        helper.succeed();
    }

    private static VoxelShape geometryShape(float[][] northBoxes, int quarterTurns) {
        VoxelShape shape = Shapes.empty();
        for (float[] northBox : northBoxes) {
            float[] box = MunitionsBenchGeometry.rotated(northBox, quarterTurns);
            shape = Shapes.or(shape, Block.box(box[0], box[1], box[2], box[3], box[4], box[5]));
        }
        return shape;
    }

    private static boolean touchesCellFace(AABB box, Direction face) {
        return switch (face) {
            case EAST -> box.maxX >= 1.0D - 1.0E-6D;
            case WEST -> box.minX <= 1.0E-6D;
            case SOUTH -> box.maxZ >= 1.0D - 1.0E-6D;
            case NORTH -> box.minZ <= 1.0E-6D;
            default -> false;
        };
    }

    private static void assertBounds(GameTestHelper helper, VoxelShape shape,
                                     double minX, double minY, double minZ,
                                     double maxX, double maxY, double maxZ, String label) {
        AABB bounds = shape.bounds();
        boolean matches = Math.abs(bounds.minX - minX) < 1.0E-6D && Math.abs(bounds.minY - minY) < 1.0E-6D
                && Math.abs(bounds.minZ - minZ) < 1.0E-6D && Math.abs(bounds.maxX - maxX) < 1.0E-6D
                && Math.abs(bounds.maxY - maxY) < 1.0E-6D && Math.abs(bounds.maxZ - maxZ) < 1.0E-6D;
        helper.assertTrue(matches, label + "; expected [" + minX + "," + minY + "," + minZ + "]-["
                + maxX + "," + maxY + "," + maxZ + "] but got " + bounds);
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void wideBenchPlacementClaimsClockwiseCellAndRejectsBlockedFootprint(GameTestHelper helper) {
        MunitionsBenchBlock bench = (MunitionsBenchBlock) ModMunitionsBlocks.MUNITIONS_BENCH.get();
        BlockItem item = (BlockItem) ModMunitionsItems.MUNITIONS_BENCH_ITEM.get();
        ServerPlayer player = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);
        player.setYRot(0.0F);

        BlockPos mainRelative = new BlockPos(2, 1, 2);
        BlockPlaceContext context = benchPlacementContext(helper, player, item, mainRelative);
        BlockState placement = bench.getStateForPlacement(context);
        helper.assertTrue(placement != null, "clear footprint must produce a placement state");
        helper.assertTrue(placement.getValue(MunitionsBenchBlock.LAYOUT) == MunitionsBenchBlock.Layout.WIDE,
                "player placement must use the wide layout, not the legacy compatibility layout");

        helper.assertTrue(item.place(context).consumesAction(), "BlockItem placement must succeed");
        BlockPos mainAbsolute = helper.absolutePos(mainRelative);
        Direction facing = placement.getValue(MunitionsBenchBlock.FACING);
        BlockPos extensionAbsolute = mainAbsolute.relative(facing.getClockWise());
        BlockState mainState = helper.getLevel().getBlockState(mainAbsolute);
        BlockState extensionState = helper.getLevel().getBlockState(extensionAbsolute);
        helper.assertTrue(mainState.getBlock() == bench
                        && mainState.getValue(MunitionsBenchBlock.PART) == MunitionsBenchBlock.Part.MAIN,
                "clicked cell must hold the main half");
        helper.assertTrue(extensionState.getBlock() == bench
                        && extensionState.getValue(MunitionsBenchBlock.PART) == MunitionsBenchBlock.Part.EXTENSION
                        && extensionState.getValue(MunitionsBenchBlock.FACING) == facing
                        && extensionState.getValue(MunitionsBenchBlock.LAYOUT) == MunitionsBenchBlock.Layout.WIDE,
                "the clockwise neighbour must hold a matching extension half");
        helper.assertTrue(helper.getLevel().getBlockEntity(mainAbsolute) instanceof MunitionsBenchBlockEntity,
                "only the main half owns a block entity");
        helper.assertTrue(helper.getLevel().getBlockEntity(extensionAbsolute) == null,
                "the extension half must not own a block entity");

        helper.getLevel().removeBlock(mainAbsolute, false);
        helper.getLevel().removeBlock(extensionAbsolute, false);

        // 副格被占时必须整体拒绝, 而不是把占位方块覆盖掉。这一条正是能杀掉 getClockWise -> getOpposite
        // 变异的断言: 方向写错时占位检查会去看另一侧的空气, 然后 setPlacedBy 把箱子直接抹掉。
        BlockPos blockedRelative = mainRelative.relative(facing.getClockWise());
        helper.setBlock(blockedRelative, Blocks.CHEST);
        BlockPlaceContext blockedContext = benchPlacementContext(helper, player, item, mainRelative);
        helper.assertTrue(bench.getStateForPlacement(blockedContext) == null,
                "an occupied clockwise cell must abort placement");
        helper.assertTrue(!item.place(blockedContext).consumesAction(),
                "BlockItem placement must fail when the extension cell is occupied");
        helper.assertBlockPresent(Blocks.CHEST, blockedRelative);
        helper.assertBlockPresent(Blocks.AIR, mainRelative);
        helper.succeed();
    }

    private static BlockPlaceContext benchPlacementContext(GameTestHelper helper, ServerPlayer player,
                                                           BlockItem item, BlockPos mainRelative) {
        helper.setBlock(mainRelative.below(), Blocks.STONE);
        BlockPos supportAbsolute = helper.absolutePos(mainRelative.below());
        BlockHitResult hit = new BlockHitResult(
                Vec3.atCenterOf(supportAbsolute).add(0.0D, 0.5D, 0.0D),
                Direction.UP, supportAbsolute, false);
        return new BlockPlaceContext(helper.getLevel(), player, InteractionHand.MAIN_HAND,
                new ItemStack(item), hit);
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void wideBenchDropsExactlyOnceFromEitherHalf(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        MunitionsBenchBlock bench = (MunitionsBenchBlock) ModMunitionsBlocks.MUNITIONS_BENCH_HIGH.get();
        BlockPos mainPos = helper.absolutePos(new BlockPos(0, 1, 0));
        BlockState mainState = bench.defaultBlockState()
                .setValue(MunitionsBenchBlock.FACING, Direction.NORTH)
                .setValue(MunitionsBenchBlock.PART, MunitionsBenchBlock.Part.MAIN)
                .setValue(MunitionsBenchBlock.LAYOUT, MunitionsBenchBlock.Layout.WIDE)
                .setValue(MunitionsBenchBlock.ACTIVE, false);
        BlockPos extensionPos = MunitionsBenchBlock.extensionPos(mainPos, mainState);
        helper.assertTrue(extensionPos.equals(mainPos.east()),
                "north-facing wide bench must put its extension on the clockwise side");

        // 进场先清一次: 取样盒在副格一侧比结构清场多探出一格, 上一轮停在盒里的同种掉落物不清掉会被数成本次拆出来的。
        clearBenchDrops(level, mainPos, extensionPos);
        try {
            placeBenchPair(level, mainPos, extensionPos, mainState);
            level.destroyBlock(extensionPos, true);
            assertBenchRemoved(helper, level, mainPos, extensionPos, "wide extension-first destruction");
            assertSingleBenchDrop(helper, level, mainPos, extensionPos, "wide extension-first destruction");
            clearBenchDrops(level, mainPos, extensionPos);

            placeBenchPair(level, mainPos, extensionPos, mainState);
            level.destroyBlock(mainPos, true);
            assertBenchRemoved(helper, level, mainPos, extensionPos, "wide main-first destruction");
            assertSingleBenchDrop(helper, level, mainPos, extensionPos, "wide main-first destruction");
            helper.succeed();
        } finally {
            // 断言中途失败时拆出来的那一个也要收走, 留在原地就是下一轮同一格的残留。
            clearBenchDrops(level, mainPos, extensionPos);
        }
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void wideBenchMirrorKeepsBothHalvesLinked(GameTestHelper helper) {
        MunitionsBenchBlock bench = (MunitionsBenchBlock) ModMunitionsBlocks.MUNITIONS_BENCH.get();
        BlockPos origin = new BlockPos(4, 1, 4);
        for (Direction facing : Direction.Plane.HORIZONTAL) {
            BlockState wide = bench.defaultBlockState()
                    .setValue(MunitionsBenchBlock.FACING, facing)
                    .setValue(MunitionsBenchBlock.LAYOUT, MunitionsBenchBlock.Layout.WIDE);
            for (Mirror mirror : Mirror.values()) {
                BlockState mirrored = wide.mirror(mirror);
                Direction expected = mirror.mirror(MunitionsBenchBlock.extensionDirection(wide));
                helper.assertTrue(MunitionsBenchBlock.extensionDirection(mirrored) == expected,
                        "mirroring " + facing + " with " + mirror + " must map the extension side to "
                                + expected + ", got " + MunitionsBenchBlock.extensionDirection(mirrored)
                                + "; a chirality mismatch makes updateShape delete both halves");
                BlockState mirroredExtension = mirrored.setValue(MunitionsBenchBlock.PART,
                        MunitionsBenchBlock.Part.EXTENSION);
                BlockPos mirroredExtensionPos = MunitionsBenchBlock.extensionPos(origin, mirrored);
                helper.assertTrue(MunitionsBenchBlock.mainPos(mirroredExtensionPos, mirroredExtension)
                                .equals(origin),
                        "mirrored halves must still resolve back to each other");
            }
            BlockState legacy = wide.setValue(MunitionsBenchBlock.LAYOUT,
                    MunitionsBenchBlock.Layout.LEGACY_DEPTH);
            helper.assertTrue(MunitionsBenchBlock.extensionDirection(legacy.mirror(Mirror.LEFT_RIGHT))
                            == Mirror.LEFT_RIGHT.mirror(MunitionsBenchBlock.extensionDirection(legacy)),
                    "legacy depth layout is mirror symmetric and must keep its plain behaviour");
        }
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void wideBenchRenderBoundingBoxCoversBothCellsUpToTheMovingParts(GameTestHelper helper) {
        ServerPlayer owner = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);
        MunitionsBenchBlockEntity bench = newBench(helper, owner);
        BlockPos absolute = bench.getBlockPos();
        for (Direction facing : Direction.Plane.HORIZONTAL) {
            BlockState wide = bench.getBlockState()
                    .setValue(MunitionsBenchBlock.LAYOUT, MunitionsBenchBlock.Layout.WIDE)
                    .setValue(MunitionsBenchBlock.FACING, facing);
            helper.getLevel().setBlock(absolute, wide, Block.UPDATE_CLIENTS);
            AABB box = bench.getRenderBoundingBox();
            BlockPos extension = MunitionsBenchBlock.extensionPos(absolute, wide);
            helper.assertTrue(box.contains(Vec3.atCenterOf(absolute)) && box.contains(Vec3.atCenterOf(extension)),
                    facing + " render bounds must cover both halves of the machine");
            helper.assertTrue(box.minY <= absolute.getY()
                            && box.maxY - absolute.getY() >= MunitionsBenchGeometry.RENDER_TOP_PX / 16.0D,
                    facing + " render bounds must reach the top of the moving parts ("
                            + MunitionsBenchGeometry.RENDER_TOP_PX + "px), got " + box);
            // 运动件扫过的整个范围 (整台像素, 朝北) 按朝向转到世界里, 八个角都得在包围盒里, 否则转身时会被视锥裁掉。
            for (int corner = 0; corner < 8; corner++) {
                Vec3 at = MunitionsBenchBlock.benchPixelToWorld(absolute, facing,
                        (corner & 1) == 0 ? MunitionsBenchGeometry.PARTS_MIN[0] : MunitionsBenchGeometry.PARTS_MAX[0],
                        (corner & 2) == 0 ? MunitionsBenchGeometry.PARTS_MIN[1] : MunitionsBenchGeometry.PARTS_MAX[1],
                        (corner & 4) == 0 ? MunitionsBenchGeometry.PARTS_MIN[2] : MunitionsBenchGeometry.PARTS_MAX[2]);
                helper.assertTrue(box.inflate(1.0E-6D).contains(at),
                        facing + " render bounds must contain the moving-part sweep corner " + at + ", got " + box);
            }
        }
        helper.succeed();
    }

    /**
     * 运动件渲染器的朝向角 ({@link MunitionsBenchBlock#partsYRotationDegrees}) 必须与方块状态的 y 旋转 / 冲压点换算
     * ({@link MunitionsBenchBlock#benchPixelToWorld}) 同向: 渲染器对朝北的整台坐标做 translate(.5, 0, .5) · Axis.YP · translate(-.5, 0, -.5),
     * 这里用同一串变换把几个点转过去, 与 benchPixelToWorld 逐点比较; 只改其中一边 (角度表写反、或换算方向改了) 这条就挂。
     */
    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void wideBenchPartsRotationMatchesTheBlockstate(GameTestHelper helper) {
        MunitionsBenchBlock bench = (MunitionsBenchBlock) ModMunitionsBlocks.MUNITIONS_BENCH.get();
        // 副格入口位那发的壳身正中 (设计尺寸: 第一个弹位、皮带面 + 半个壳高)
        float[] entryRound = {MunitionsBenchGeometry.SLOT_X_PX[0],
                MunitionsBenchGeometry.BELT_TOP_PX + MunitionsBenchGeometry.ROUND_CASE_HEIGHT_PX / 2.0F,
                MunitionsBenchGeometry.SLOT_Z_PX};
        float[][] points = {
                {MunitionsBenchGeometry.SPARK_X, MunitionsBenchGeometry.SPARK_Y, MunitionsBenchGeometry.SPARK_Z},
                entryRound,
                {1.0F, 20.0F, 15.0F}, // 主格西南角上方
        };
        for (Direction facing : Direction.Plane.HORIZONTAL) {
            Matrix4f renderer = new Matrix4f()
                    .translate(0.5F, 0.0F, 0.5F)
                    .rotate(Axis.YP.rotationDegrees(MunitionsBenchBlock.partsYRotationDegrees(facing)))
                    .translate(-0.5F, 0.0F, -0.5F);
            for (float[] point : points) {
                Vector3f drawn = renderer.transformPosition(new Vector3f(point[0] / 16.0F, point[1] / 16.0F, point[2] / 16.0F));
                Vec3 expected = MunitionsBenchBlock.benchPixelToWorld(BlockPos.ZERO, facing, point[0], point[1], point[2]);
                helper.assertTrue(Math.abs(drawn.x - expected.x) < 1.0E-4D && Math.abs(drawn.y - expected.y) < 1.0E-4D
                                && Math.abs(drawn.z - expected.z) < 1.0E-4D,
                        facing + ": the renderer draws bench pixel (" + point[0] + ", " + point[1] + ", " + point[2]
                                + ") at " + drawn + " but benchPixelToWorld puts it at " + expected);
            }
            // 副格那一发必须画在副格里 (副格恒在 facing.getClockWise() 一侧)。
            BlockState wide = bench.defaultBlockState().setValue(MunitionsBenchBlock.FACING, facing)
                    .setValue(MunitionsBenchBlock.LAYOUT, MunitionsBenchBlock.Layout.WIDE);
            Vector3f inExtension = renderer.transformPosition(
                    new Vector3f(entryRound[0] / 16.0F, entryRound[1] / 16.0F, entryRound[2] / 16.0F));
            helper.assertTrue(BlockPos.containing(inExtension.x, 0.0D, inExtension.z)
                            .equals(MunitionsBenchBlock.extensionPos(BlockPos.ZERO, wide)),
                    facing + ": the entry-slot round must be drawn over the extension cell, got " + inExtension);
        }
        helper.succeed();
    }

    // ============================================================
    // 弹药流水线运动程序 (MunitionsBenchProgram): 服务端冲压音与客户端运动件共用的帧表
    // ============================================================

    /**
     * 期望值取设计口径本身 (8 帧 × 5 tick = 40 tick 一个循环, 冲头在第 3 帧 f2 = tick 10 压到底), 不照抄实现常量 ——
     * 常量写错也照样绿的断言测不出东西。
     */
    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void benchProgramStrikesOncePerCycleAndIdlesAtRest(GameTestHelper helper) {
        helper.assertTrue(MunitionsBenchProgram.CYCLE_TICKS == 40,
                "the munitions line cycle is 8 frames x 5 ticks = 40 ticks, got " + MunitionsBenchProgram.CYCLE_TICKS);
        helper.assertTrue(MunitionsBenchProgram.STRIKE_TICK == 10,
                "the ram bottoms out on frame f2 = tick 10, got " + MunitionsBenchProgram.STRIKE_TICK);

        MunitionsBenchProgram.Pose idle = MunitionsBenchProgram.idle(new MunitionsBenchProgram.Pose());
        helper.assertTrue(idle.beltX == 0.0F && idle.primeY == 0.0F && idle.powderY == 0.0F && idle.ramY == 0.0F
                        && idle.dropY == 0.0F && idle.dieHeat == 0.0F,
                "the idle pose is the rest layout: every moving part at its zero offset and a cold die");
        helper.assertTrue(idle.ramBulletVisible && !idle.powderCharged && !idle.seated,
                "idle: the ram holds the next bullet, the powder slot is still empty and the round under the ram "
                        + "is not seated yet");

        // 冲头的最低点必须恰在 STRIKE_TICK, 且那一刻压模是热的、还夹着弹头 (弹头正落在壳口上)。
        MunitionsBenchProgram.Pose pose = new MunitionsBenchProgram.Pose();
        float lowest = Float.MAX_VALUE;
        float lowestAt = -1.0F;
        for (int step = 0; step < 160; step++) {
            float t = step * 0.25F;
            MunitionsBenchProgram.sample(t, pose);
            if (pose.ramY < lowest - 1.0E-6F) {
                lowest = pose.ramY;
                lowestAt = t;
            }
        }
        MunitionsBenchProgram.sample(10.0F, pose);
        helper.assertTrue(lowestAt == 10.0F && pose.ramY < 0.0F,
                "the ram must reach its single lowest point at tick 10, lowest " + lowest + " at " + lowestAt);
        helper.assertTrue(pose.dieHeat >= 0.5F && pose.ramBulletVisible && pose.powderCharged && !pose.seated,
                "at the strike the die glows and still holds the bullet over the charged case");

        // 帧表与弹的尺寸对得上 (弹缩小时重调过冲程): 期望关系取设计本身 —— 冲压时刻夹着的弹头底正好落在壳口 (火花处),
        // 底火冲杆 / 装药管下探到底正好顶到壳口、从不插进壳里; 位置 = MunitionsBenchGeometry 的静止位下端 + 程序的 y 偏移。
        float mouth = MunitionsBenchGeometry.BELT_TOP_PX + MunitionsBenchGeometry.ROUND_CASE_HEIGHT_PX;
        helper.assertTrue(Math.abs(mouth - MunitionsBenchGeometry.SPARK_Y) < 1.0E-5F,
                "the strike sparks at the case mouth (belt top + case height = " + mouth + "), got SPARK_Y "
                        + MunitionsBenchGeometry.SPARK_Y);
        float seatedAt = MunitionsBenchGeometry.RAM_BULLET_REST_BOTTOM_PX + pose.ramY;
        helper.assertTrue(Math.abs(seatedAt - mouth) < 1.0E-5F,
                "at the strike the held bullet must sit exactly on the case mouth " + mouth + ", its bottom is at " + seatedAt);
        float primeLowest = Float.MAX_VALUE;
        float powderLowest = Float.MAX_VALUE;
        float bulletLowest = Float.MAX_VALUE;
        for (int step = 0; step < 160; step++) {
            MunitionsBenchProgram.sample(step * 0.25F, pose);
            primeLowest = Math.min(primeLowest, MunitionsBenchGeometry.PRIME_ROD_REST_BOTTOM_PX + pose.primeY);
            powderLowest = Math.min(powderLowest, MunitionsBenchGeometry.POWDER_TUBE_REST_BOTTOM_PX + pose.powderY);
            if (pose.ramBulletVisible) {
                bulletLowest = Math.min(bulletLowest, MunitionsBenchGeometry.RAM_BULLET_REST_BOTTOM_PX + pose.ramY);
            }
        }
        helper.assertTrue(Math.abs(primeLowest - mouth) < 1.0E-5F && Math.abs(powderLowest - mouth) < 1.0E-5F,
                "the primer rod and the powder tube must reach down exactly to the case mouth " + mouth + ", got "
                        + primeLowest + " / " + powderLowest);
        helper.assertTrue(Math.abs(bulletLowest - mouth) < 1.0E-5F,
                "the held bullet never dips below the case mouth, lowest " + bulletLowest);
        MunitionsBenchProgram.sample(0.0F, pose);
        helper.assertTrue(Math.abs(MunitionsBenchGeometry.PRIME_ROD_REST_BOTTOM_PX + pose.primeY - mouth) < 1.0E-5F,
                "f0: the primer rod sits on the case mouth");
        MunitionsBenchProgram.sample(5.0F, pose);
        helper.assertTrue(Math.abs(MunitionsBenchGeometry.POWDER_TUBE_REST_BOTTOM_PX + pose.powderY - mouth) < 1.0E-5F,
                "f1: the powder tube sits on the case mouth");

        MunitionsBenchProgram.sample(0.0F, pose);
        helper.assertTrue(pose.primeY < 0.0F && !pose.powderCharged && !pose.seated && pose.dieHeat < 0.5F,
                "f0: the primer rod is down on a fresh case, nothing charged or seated, die cold");
        MunitionsBenchProgram.sample(12.5F, pose);
        helper.assertTrue(!pose.ramBulletVisible && pose.seated && pose.powderCharged,
                "right after the strike the ram rises empty and the round below is seated");
        MunitionsBenchProgram.sample(25.0F, pose);
        helper.assertTrue(pose.ramBulletVisible && pose.beltX < 0.0F && pose.dropY < 0.0F,
                "f5: the belt has advanced, the ram holds the next bullet and the finished round sinks into the can");
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void benchProgramLoopsSeamlesslyAndWrapsLongClocks(GameTestHelper helper) {
        MunitionsBenchProgram.Pose a = new MunitionsBenchProgram.Pose();
        MunitionsBenchProgram.Pose b = new MunitionsBenchProgram.Pose();
        // 循环内连续: 每 1/4 tick 任何一个运动件最多挪 0.5 px (帧表是线性插值, 不该有跳变)。
        for (int step = 0; step < 159; step++) {
            MunitionsBenchProgram.sample(step * 0.25F, a);
            MunitionsBenchProgram.sample((step + 1) * 0.25F, b);
            float jump = Math.max(Math.max(Math.abs(a.beltX - b.beltX), Math.abs(a.primeY - b.primeY)),
                    Math.max(Math.max(Math.abs(a.powderY - b.powderY), Math.abs(a.ramY - b.ramY)),
                            Math.abs(a.dropY - b.dropY)));
            helper.assertTrue(jump <= 0.5F, "the program jumps " + jump + "px between ticks " + step * 0.25F
                    + " and " + (step + 1) * 0.25F);
        }

        // 循环接缝: 机器姿态首尾相接; 皮带上的弹差整一个节距 (到下一轮弹位整体换号, 画面不变)。
        MunitionsBenchProgram.sample(39.999F, a);
        MunitionsBenchProgram.sample(0.0F, b);
        helper.assertTrue(Math.abs(a.primeY - b.primeY) < 1.0E-2F && Math.abs(a.powderY - b.powderY) < 1.0E-2F
                        && Math.abs(a.ramY - b.ramY) < 1.0E-2F && Math.abs(a.dieHeat - b.dieHeat) < 1.0E-2F
                        && a.ramBulletVisible == b.ramBulletVisible,
                "the machine pose at the end of a cycle must match the start of the next one");
        helper.assertTrue(Math.abs((a.beltX + MunitionsBenchProgram.BELT_PITCH) - b.beltX) < 1.0E-2F,
                "the belt must end a cycle exactly one pitch past where it starts, got " + a.beltX + " -> " + b.beltX);
        helper.assertTrue(MunitionsBenchProgram.BELT_PITCH == 4.0F,
                "one belt step is one cartridge slot (4 px), got " + MunitionsBenchProgram.BELT_PITCH);

        // 取模: 负数折回, 多转几圈同一相位; long 版先在 long 里取模, 台子连开几天也不抖。
        for (float t : new float[]{0.0F, 3.5F, 12.5F, 27.75F}) {
            MunitionsBenchProgram.sample(t, a);
            MunitionsBenchProgram.sample(t + 80.0F, b);
            helper.assertTrue(samePose(a, b), "sample must repeat every cycle (t = " + t + ")");
            MunitionsBenchProgram.sample(t - 40.0F, b);
            helper.assertTrue(samePose(a, b), "negative times must fold back into the cycle (t = " + t + ")");
        }
        MunitionsBenchProgram.sample(12.5F, a);
        MunitionsBenchProgram.sample(40L * 1_000_000_007L + 12L, 0.5F, BASE_TIER, b);
        helper.assertTrue(samePose(a, b),
                "a clock billions of ticks old must sample the same phase as its remainder, got beltX "
                        + b.beltX + " vs " + a.beltX);

        helper.assertTrue(MunitionsBenchProgram.isStrikeTick(10L, BASE_TIER) && MunitionsBenchProgram.isStrikeTick(50L, BASE_TIER)
                        && MunitionsBenchProgram.isStrikeTick(40L * 1_000_000L + 10L, BASE_TIER),
                "the base tier strikes at start + 10 + 40n");
        helper.assertTrue(!MunitionsBenchProgram.isStrikeTick(0L, BASE_TIER) && !MunitionsBenchProgram.isStrikeTick(11L, BASE_TIER)
                        && !MunitionsBenchProgram.isStrikeTick(-30L, BASE_TIER),
                "no strike off the beat, and none before the program started");
        helper.assertTrue(MunitionsBenchProgram.nextStrikeTickAfter(0L, BASE_TIER) == 10L
                        && MunitionsBenchProgram.nextStrikeTickAfter(9L, BASE_TIER) == 10L
                        && MunitionsBenchProgram.nextStrikeTickAfter(10L, BASE_TIER) == 50L
                        && MunitionsBenchProgram.nextStrikeTickAfter(49L, BASE_TIER) == 50L,
                "nextStrikeTickAfter must return the first strike strictly after the given tick");
        helper.succeed();
    }

    /**
     * 用户拍板: 档位越高生产动画越快, 均匀阶梯到 2 倍速 —— 一个循环 普通 40 / 中级 36 / 高级 32 / 极品 28 / 超凡 24 / 闪耀 20 tick,
     * 冲压在循环的 1/4 (10 / 9 / 8 / 7 / 6 / 5)。期望值取这张拍板的表本身, 不照抄常量。关键帧仍在 40 tick 的程序时间里:
     * 游戏时间按 programTick 映射过去, 循环内单调递增、到该档循环的整数倍正好折回 0、冲压那一 tick 正好是程序的 STRIKE_TICK
     * (冲头最低), 连开几天的时钟 (long) 与它的余数逐位相同; 冲压音 / 火花的拍子 (isStrikeTick / nextStrikeTickAfter) 与之一致;
     * 渲染器每帧的两个决定 (sampleRunning 的姿态、strikeBetween 的火花) 逐档与服务端同拍; 每块台子构造时给的档位与它的注册名对得上。
     * 循环里的失败信息只在失败时拼。
     */
    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void benchProgramRunsFasterByTierOnAUniformLadderUpToDoubleSpeed(GameTestHelper helper) {
        int[] approvedCycles = {40, 36, 32, 28, 24, 20};
        helper.assertTrue(MunitionsBenchProgram.tierCount() == MunitionsBenchAssets.TIER_IDS.length
                        && approvedCycles.length == MunitionsBenchAssets.TIER_IDS.length,
                "one cycle length per bench tier, got " + MunitionsBenchProgram.tierCount());
        MunitionsBenchProgram.Pose pose = new MunitionsBenchProgram.Pose();
        MunitionsBenchProgram.Pose expectedPose = new MunitionsBenchProgram.Pose();
        MunitionsBenchProgram.Pose strikePose = MunitionsBenchProgram.sample((float) MunitionsBenchProgram.STRIKE_TICK,
                new MunitionsBenchProgram.Pose());
        float[] partials = {0.0F, 0.1F, 0.15957803F, 0.25F, 0.5F, 0.75F, 0.8456556F, 0.9999F};
        for (int tier = 0; tier < approvedCycles.length; tier++) {
            int cycle = approvedCycles[tier];
            int strike = cycle / 4;
            helper.assertTrue(MunitionsBenchProgram.cycleTicks(tier) == cycle,
                    "tier " + tier + " must run a " + cycle + "-tick cycle, got " + MunitionsBenchProgram.cycleTicks(tier));
            helper.assertTrue(MunitionsBenchProgram.strikeTick(tier) == strike,
                    "tier " + tier + " must strike a quarter into its cycle (tick " + strike + "), got " + MunitionsBenchProgram.strikeTick(tier));
            helper.assertTrue(40.0D / cycle <= 2.0D, "tier " + tier + " runs faster than the approved 2x");

            // 映射: 两个循环里按游戏时间排好的样本, 程序时间在 [0, 40) 里、循环内严格递增、到整循环正好折回 0
            double previous = -1.0D;
            for (long e = 0L; e < 2L * cycle; e++) {
                for (float partial : partials) {
                    double t = MunitionsBenchProgram.programTick(e, partial, tier);
                    if (!(t >= 0.0D && t < MunitionsBenchProgram.CYCLE_TICKS)) {
                        helper.fail("tier " + tier + " e " + e + " + " + partial + ": program time " + t + " leaves [0, 40)");
                    }
                    boolean wrapped = e % cycle == 0L && partial == 0.0F;
                    if (wrapped) {
                        if (t != 0.0D) {
                            helper.fail("tier " + tier + ": the program must wrap to exactly 0 at " + e + ", got " + t);
                        }
                    } else if (!(t > previous)) {
                        helper.fail("tier " + tier + ": program time must increase within a cycle, " + previous + " then " + t
                                + " at " + e + " + " + partial);
                    }
                    previous = t;
                }
            }
            helper.assertTrue(MunitionsBenchProgram.programTick(cycle - 1L, 0.9999F, tier) > 39.99D,
                    "tier " + tier + ": the last moment of a cycle is the end of the program");
            // 冲压那一 tick = 程序的 STRIKE_TICK (冲头最低), 姿态与程序时间 10 逐位相同
            helper.assertTrue(MunitionsBenchProgram.programTick(strike, 0.0F, tier) == MunitionsBenchProgram.STRIKE_TICK,
                    "tier " + tier + ": its strike tick " + strike + " must map exactly onto the program's strike");
            MunitionsBenchProgram.sample(strike, 0.0F, tier, pose);
            helper.assertTrue(samePose(pose, strikePose) && pose.ramY < 0.0F && pose.dieHeat >= 0.5F,
                    "tier " + tier + ": the ram bottoms out on tick " + strike + ", ramY " + pose.ramY);
            // 连开几天: long 里取模, 与余数逐位相同
            for (long days : new long[]{1_000_000_007L, 123_456_789_011L}) {
                for (float partial : partials) {
                    long e = days * cycle + strike + 3L;
                    if (MunitionsBenchProgram.programTick(e, partial, tier) != MunitionsBenchProgram.programTick(strike + 3L, partial, tier)) {
                        helper.fail("tier " + tier + ": a clock " + e + " ticks old drifts off its remainder");
                    }
                }
            }
            // 冲压音 / 火花的拍子: 起点 + strike + cycle × n, 起点之前没有
            for (long e = -3L * cycle; e <= 5L * cycle; e++) {
                boolean expected = e >= 0L && Math.floorMod(e, (long) cycle) == strike;
                if (MunitionsBenchProgram.isStrikeTick(e, tier) != expected) {
                    helper.fail("tier " + tier + ": isStrikeTick(" + e + ") should be " + expected);
                }
                long next = MunitionsBenchProgram.nextStrikeTickAfter(e, tier);
                if (!(next > e && next - e <= cycle && Math.floorMod(next, (long) cycle) == strike)) {
                    helper.fail("tier " + tier + ": nextStrikeTickAfter(" + e + ") = " + next);
                }
            }

            // 渲染器每帧的决定 (MunitionsBenchRenderer 直接调这两个): 工作时的姿态 = 这一档的映射, 起点比本地时钟快时停在首帧;
            // 火花 = (上一帧, 这一帧] 里有没有冲压 tick, 与服务端逐 tick 的 isStrikeTick 同拍 (逐帧、隔几帧、隔一两个循环)
            MunitionsBenchProgram.sample(0.0F, expectedPose);
            for (long e = -3L; e < 0L; e++) {
                MunitionsBenchProgram.sampleRunning(e, 0.5F, tier, pose);
                if (!samePose(pose, expectedPose)) {
                    helper.fail("tier " + tier + ": a start tick " + (-e) + " ahead of the client clock must hold the first frame");
                }
            }
            for (long e = 0L; e < 2L * cycle; e++) {
                for (float partial : partials) {
                    MunitionsBenchProgram.sampleRunning(e, partial, tier, pose);
                    MunitionsBenchProgram.sample((float) MunitionsBenchProgram.programTick(e, partial, tier), expectedPose);
                    if (!samePose(pose, expectedPose)) {
                        helper.fail("tier " + tier + ": the running pose at " + e + " + " + partial + " must be this tier's mapping");
                    }
                }
            }
            MunitionsBenchProgram.sampleRunning(strike, 0.0F, tier, pose);
            helper.assertTrue(samePose(pose, strikePose), "tier " + tier + ": the renderer's ram bottoms out on the strike tick " + strike);
            for (long since = -2L * cycle; since <= 3L * cycle; since++) {
                for (long gap : new long[]{-1L, 0L, 1L, 2L, 3L, cycle - 1L, cycle, cycle + 1L, 2L * cycle + 1L}) {
                    long until = since + gap;
                    boolean beat = false;
                    for (long t = since + 1L; t <= until; t++) {
                        beat |= MunitionsBenchProgram.isStrikeTick(t, tier);
                    }
                    if (MunitionsBenchProgram.strikeBetween(since, until, tier) != beat) {
                        helper.fail("tier " + tier + ": strikeBetween(" + since + ", " + until + ") must be " + beat
                                + " like the server's isStrikeTick beats in that window");
                    }
                }
            }
        }
        // 越界的档位 (不该发生) 按普通档
        helper.assertTrue(MunitionsBenchProgram.cycleTicks(-1) == 40 && MunitionsBenchProgram.cycleTicks(6) == 40
                        && MunitionsBenchProgram.strikeTick(99) == 10,
                "an out-of-range tier falls back to the base speed");
        // 方块的档位 (构造时给定, 渲染器与服务端都按它取速度): 期望按 ModMunitionsBlocks 的档位顺序写死, 再与注册名对上
        // (注册名 = 资产文件名 / 计数屏颜色的档位, MunitionsBenchAssets.TIER_IDS)
        Block[] blocks = {
                ModMunitionsBlocks.MUNITIONS_BENCH.get(), ModMunitionsBlocks.MUNITIONS_BENCH_MEDIUM.get(),
                ModMunitionsBlocks.MUNITIONS_BENCH_HIGH.get(), ModMunitionsBlocks.MUNITIONS_BENCH_SUPERIOR.get(),
                ModMunitionsBlocks.MUNITIONS_BENCH_TRANSCENDENT.get(), ModMunitionsBlocks.MUNITIONS_BENCH_RADIANT.get()};
        for (int tier = 0; tier < blocks.length; tier++) {
            int got = ((MunitionsBenchBlock) blocks[tier]).tier();
            String path = net.minecraftforge.registries.ForgeRegistries.BLOCKS.getKey(blocks[tier]).getPath();
            helper.assertTrue(got == tier && MunitionsBenchAssets.TIER_IDS[tier].equals(path),
                    path + " must be tier " + tier + " (" + MunitionsBenchAssets.TIER_IDS[tier] + "), got " + got);
        }
        helper.succeed();
    }

    private static boolean samePose(MunitionsBenchProgram.Pose a, MunitionsBenchProgram.Pose b) {
        return Math.abs(a.beltX - b.beltX) < 1.0E-4F && Math.abs(a.primeY - b.primeY) < 1.0E-4F
                && Math.abs(a.powderY - b.powderY) < 1.0E-4F && Math.abs(a.ramY - b.ramY) < 1.0E-4F
                && Math.abs(a.dropY - b.dropY) < 1.0E-4F && Math.abs(a.dieHeat - b.dieHeat) < 1.0E-4F
                && a.ramBulletVisible == b.ramBulletVisible && a.powderCharged == b.powderCharged
                && a.seated == b.seated;
    }

    /**
     * 服务端冲压音与客户端运动件同拍: 程序起点是 ACTIVE 由假变真的 tick, 冲压音恰在起点 + 该档冲压时刻 + 该档循环 × n 播
     * (用户拍板的速度阶梯: 普通 10 + 40n, 高级 8 + 32n, 闪耀 5 + 20n), 从 WIDE 台子的冲压点出声; LEGACY 老台子 (主格中上方出声)
     * 同样按档位的节拍 (闪耀 5 + 20n);
     * 同一 tick 里先灭后亮 (连续模式换批, 客户端只看得到 "一直亮着") 不重置相位, 隔 tick 重新点亮才重来;
     * 发给客户端的区块更新标签带着这个起点 (玩家走远再回来也能对上拍); 区块加载时已在工作、没经过翻转的台子 (高级) 在组区块包的
     * 那一刻就定下起点, 冲压音也按它走。
     * <p>
     * 单独一个 batch: 本用例要把职业门面替身挂 100 多 tick, 不能与同批其他用例的同步替换互相覆盖。
     * 冲压音从 mock 玩家的 EmbeddedChannel 出站队列里读 (ServerLevel.playSound 按距离广播 ClientboundSoundPacket)。
     */
    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = TIMING_BATCH, timeoutTicks = 200)
    public static void wideBenchStrikeSoundFollowsTheProgramPhase(GameTestHelper helper) {
        ServerPlayer player = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);
        IJobService prevJob = swapJob(new FixedLevelJobService(5));
        boolean handedOff = false;
        try {
            ServerLevel level = helper.getLevel();
            MunitionsBenchBlockEntity be = newBench(helper, player, ModMunitionsBlocks.MUNITIONS_BENCH.get()); // 普通: 40 tick 一个循环
            BlockPos mainPos = be.getBlockPos();
            BlockState wide = be.getBlockState()
                    .setValue(MunitionsBenchBlock.FACING, Direction.NORTH)
                    .setValue(MunitionsBenchBlock.LAYOUT, MunitionsBenchBlock.Layout.WIDE);
            placeBenchPair(level, mainPos, MunitionsBenchBlock.extensionPos(mainPos, wide), wide);
            player.moveTo(mainPos.getX() + 0.5D, mainPos.getY(), mainPos.getZ() - 2.5D);
            EmbeddedChannel channel = (EmbeddedChannel) player.connection.connection.channel();
            Vec3 strikeAt = MunitionsBenchBlock.benchPixelToWorld(mainPos, Direction.NORTH,
                    MunitionsBenchGeometry.SPARK_X, MunitionsBenchGeometry.SPARK_Y, MunitionsBenchGeometry.SPARK_Z);
            List<Long> strikes = new ArrayList<>();
            collectStrikeSounds(channel, Long.MIN_VALUE, Map.of());

            helper.assertTrue(be.trySelectCaliber(MunitionsCaliber.RIFLE, player), "select RIFLE at L5");
            stockParts(be, 1);
            helper.assertTrue(be.tryStartCraft(player), "owner starts a manual craft");
            long start = level.getGameTime();
            helper.assertTrue(be.programStartTick() == start,
                    "the program starts on the tick ACTIVE turns on, got " + be.programStartTick() + " vs " + start);
            helper.assertTrue(be.getUpdateTag().getLong("ProgramStartTick") == start,
                    "the chunk update tag must carry the program start for clients that load the chunk later");
            MunitionsBenchBlockEntity clientCopy = new MunitionsBenchBlockEntity(mainPos, level.getBlockState(mainPos));
            clientCopy.handleUpdateTag(be.getUpdateTag());
            helper.assertTrue(clientCopy.programStartTick() == start && clientCopy.owner() == null,
                    "a client copy reads only the program start (and the counter) from the update tag, never load()");

            // 区块加载时台子已在工作 (读档后状态本来就是 ACTIVE, 没经过翻转, 服务端也还没 tick 过它): 区块包早于这台机器的
            // 第一次 tick (视距边缘的区块根本不 tick), 所以发区块的那一刻服务端就要把起点定下, 更新标签带着它, 冲压音也按它走。
            // 这一台是高级 (32 tick 一个循环)。
            BlockPos loadedPos = mainPos.south(2);
            BlockState loadedState = ModMunitionsBlocks.MUNITIONS_BENCH_HIGH.get().defaultBlockState()
                    .setValue(MunitionsBenchBlock.FACING, Direction.NORTH)
                    .setValue(MunitionsBenchBlock.LAYOUT, MunitionsBenchBlock.Layout.WIDE)
                    .setValue(MunitionsBenchBlock.ACTIVE, true);
            placeBenchPair(level, loadedPos, MunitionsBenchBlock.extensionPos(loadedPos, loadedState), loadedState);
            MunitionsBenchBlockEntity loaded = (MunitionsBenchBlockEntity) level.getBlockEntity(loadedPos);
            helper.assertTrue(loaded != null && loaded.programStartTick() == MunitionsBenchBlockEntity.NO_PROGRAM_START,
                    "a bench that comes up already ACTIVE has seen no flip yet");
            long loadedStart = level.getGameTime();
            helper.assertTrue(loaded.getUpdateTag().getLong("ProgramStartTick") == loadedStart
                            && loaded.programStartTick() == loadedStart,
                    "the first chunk packet of an already-running bench must fix the program start on the spot, got tag "
                            + loaded.getUpdateTag() + " / start " + loaded.programStartTick());
            loaded.setOwner(player.getUUID());
            loaded.getCapability(ForgeCapabilities.ENERGY).ifPresent(storage -> storage.receiveEnergy(Integer.MAX_VALUE, false));
            helper.assertTrue(loaded.trySelectCaliber(MunitionsCaliber.RIFLE, player), "select RIFLE at L5 (loaded bench)");
            stockParts(loaded, 1);
            helper.assertTrue(loaded.tryStartCraft(player) && loaded.programStartTick() == loadedStart,
                    "crafting on a bench that is already lit is no flip: the phase the chunk packet announced stays");
            Vec3 loadedStrikeAt = MunitionsBenchBlock.benchPixelToWorld(loadedPos, Direction.NORTH,
                    MunitionsBenchGeometry.SPARK_X, MunitionsBenchGeometry.SPARK_Y, MunitionsBenchGeometry.SPARK_Z);
            List<Long> loadedStrikes = new ArrayList<>();

            // 闪耀: 20 tick 一个循环 (2 倍速), 与普通那台同一 tick 开工
            // 摆在本格结构清场够得着、又不与邻格清场重叠的地方 (相对主格 x[-1,+2]、z[-3,+2]): 清场范围外的台子连同方块实体
            // 跨轮留在存档里, 上一轮若断在取消制作之前, 这一轮读回来的就是还在 "制作中" 的旧实体, 选口径会被拒。
            BlockPos radiantPos = mainPos.north(2).east();
            BlockState radiantState = ModMunitionsBlocks.MUNITIONS_BENCH_RADIANT.get().defaultBlockState()
                    .setValue(MunitionsBenchBlock.FACING, Direction.NORTH)
                    .setValue(MunitionsBenchBlock.LAYOUT, MunitionsBenchBlock.Layout.WIDE);
            placeBenchPair(level, radiantPos, MunitionsBenchBlock.extensionPos(radiantPos, radiantState), radiantState);
            MunitionsBenchBlockEntity radiant = (MunitionsBenchBlockEntity) level.getBlockEntity(radiantPos);
            helper.assertTrue(radiant != null, "the radiant bench has a block entity");
            radiant.setOwner(player.getUUID());
            radiant.getCapability(ForgeCapabilities.ENERGY).ifPresent(storage -> storage.receiveEnergy(Integer.MAX_VALUE, false));
            helper.assertTrue(radiant.trySelectCaliber(MunitionsCaliber.RIFLE, player), "select RIFLE at L5 (radiant bench)");
            stockParts(radiant, 1);
            helper.assertTrue(radiant.tryStartCraft(player), "owner starts a manual craft on the radiant bench");
            long radiantStart = level.getGameTime();
            helper.assertTrue(radiant.programStartTick() == radiantStart, "the radiant program starts on its flip");
            Vec3 radiantStrikeAt = MunitionsBenchBlock.benchPixelToWorld(radiantPos, Direction.NORTH,
                    MunitionsBenchGeometry.SPARK_X, MunitionsBenchGeometry.SPARK_Y, MunitionsBenchGeometry.SPARK_Z);
            List<Long> radiantStrikes = new ArrayList<>();

            // LEGACY 老台子 (没有运动件, 在主格中上方出声): 冲压音同样跟着程序时间按档位变快, 闪耀的 LEGACY 与 WIDE 同一个 5 + 20n
            // (拍板的是 "冲压音 = 起点 + 该档冲压时刻 + 该档循环 × n", 不分布局; 这一台把这个选择钉住)。副格在它南面 (LEGACY = 朝向的反面)。
            // 位置同样落在本格清场范围内 (理由见闪耀那台), 与另外三台互不相邻; 副格让开主格西北角那一格:
            // 用例失败时框架会在那里放讲台, 压在上面的半台被顶掉, 整台连料一起撒出来。
            BlockPos legacyPos = mainPos.north(3).west();
            BlockState legacyState = ModMunitionsBlocks.MUNITIONS_BENCH_RADIANT.get().defaultBlockState()
                    .setValue(MunitionsBenchBlock.FACING, Direction.NORTH)
                    .setValue(MunitionsBenchBlock.LAYOUT, MunitionsBenchBlock.Layout.LEGACY_DEPTH);
            placeBenchPair(level, legacyPos, MunitionsBenchBlock.extensionPos(legacyPos, legacyState), legacyState);
            MunitionsBenchBlockEntity legacy = (MunitionsBenchBlockEntity) level.getBlockEntity(legacyPos);
            helper.assertTrue(legacy != null, "the legacy radiant bench has a block entity");
            legacy.setOwner(player.getUUID());
            legacy.getCapability(ForgeCapabilities.ENERGY).ifPresent(storage -> storage.receiveEnergy(Integer.MAX_VALUE, false));
            helper.assertTrue(legacy.trySelectCaliber(MunitionsCaliber.RIFLE, player), "select RIFLE at L5 (legacy radiant bench)");
            stockParts(legacy, 1);
            helper.assertTrue(legacy.tryStartCraft(player), "owner starts a manual craft on the legacy radiant bench");
            long legacyStart = level.getGameTime();
            helper.assertTrue(legacy.programStartTick() == legacyStart, "the legacy program starts on its flip");
            Vec3 legacyStrikeAt = new Vec3(legacyPos.getX() + 0.5D, legacyPos.getY() + 0.72D, legacyPos.getZ() + 0.5D);
            List<Long> legacyStrikes = new ArrayList<>();
            Map<Vec3, List<Long>> listeners = Map.of(strikeAt, strikes, loadedStrikeAt, loadedStrikes, radiantStrikeAt, radiantStrikes,
                    legacyStrikeAt, legacyStrikes);

            helper.onEachTick(() -> collectStrikeSounds(channel, level.getGameTime(), listeners));
            handedOff = true;
            helper.runAfterDelay(100L, () -> {
                try {
                    collectStrikeSounds(channel, level.getGameTime(), listeners);
                    // 期望取拍板的速度阶梯本身 (普通 40 / 高级 32 / 闪耀 20 tick 一个循环, 冲压在 1/4 处), 不照抄常量。
                    // 比较到 now - 2 为止 (回调与本 tick 方块实体 tick 的先后不影响结论), 之后收到的也必须在拍上。
                    long until = level.getGameTime() - 2L;
                    List<Long> baseExpected = beats(start, 10L, 40L, until);
                    List<Long> highExpected = beats(loadedStart, 8L, 32L, until);
                    List<Long> radiantExpected = beats(radiantStart, 5L, 20L, until);
                    List<Long> legacyExpected = beats(legacyStart, 5L, 20L, until);
                    helper.assertTrue(baseExpected.size() >= 2 && highExpected.size() >= 2 && radiantExpected.size() >= 4
                                    && legacyExpected.size() >= 4,
                            "precondition: the window covers several beats, got " + baseExpected + " / " + highExpected + " / "
                                    + radiantExpected + " / " + legacyExpected);
                    helper.assertTrue(upTo(strikes, until).equals(baseExpected) && onBeat(strikes, start, 10L, 40L),
                            "the base bench must play the press sound exactly at start + 10 + 40n (start " + start + "), expected "
                                    + baseExpected + ", got " + strikes);
                    helper.assertTrue(upTo(loadedStrikes, until).equals(highExpected) && onBeat(loadedStrikes, loadedStart, 8L, 32L),
                            "a high bench that was already running when its chunk was sent must strike at the start its "
                                    + "update tag announced + 8 + 32n (start " + loadedStart + "), expected " + highExpected
                                    + ", got " + loadedStrikes);
                    helper.assertTrue(upTo(radiantStrikes, until).equals(radiantExpected) && onBeat(radiantStrikes, radiantStart, 5L, 20L),
                            "the radiant bench runs at double speed: press sound exactly at start + 5 + 20n (start "
                                    + radiantStart + "), expected " + radiantExpected + ", got " + radiantStrikes);
                    helper.assertTrue(upTo(legacyStrikes, until).equals(legacyExpected) && onBeat(legacyStrikes, legacyStart, 5L, 20L),
                            "a legacy radiant bench keeps the tier's press cadence too: exactly at start + 5 + 20n (start "
                                    + legacyStart + "), expected " + legacyExpected + ", got " + legacyStrikes);
                    loaded.cancelCraft(player);
                    radiant.cancelCraft(player);
                    legacy.cancelCraft(player);
                    BlockState lit = level.getBlockState(mainPos);
                    helper.assertTrue(lit.getValue(MunitionsBenchBlock.ACTIVE), "the craft keeps the bench ACTIVE");

                    // 同一 tick 里灭了又亮: 客户端看来一直亮着, 相位不能重来。
                    level.setBlock(mainPos, lit.setValue(MunitionsBenchBlock.ACTIVE, false), Block.UPDATE_CLIENTS);
                    level.setBlock(mainPos, lit, Block.UPDATE_CLIENTS);
                    helper.assertTrue(be.programStartTick() == start,
                            "relighting within the same tick must keep the phase, got " + be.programStartTick());

                    // 这一 tick 灭掉, 下一 tick 由手动制作重新点亮: 客户端看得到两次翻转, 相位从重新点亮的 tick 重来。
                    long stoppedAt = level.getGameTime();
                    level.setBlock(mainPos, lit.setValue(MunitionsBenchBlock.ACTIVE, false), Block.UPDATE_CLIENTS);
                    helper.runAfterDelay(2L, () -> {
                        try {
                            helper.assertTrue(level.getBlockState(mainPos).getValue(MunitionsBenchBlock.ACTIVE)
                                            && be.programStartTick() == stoppedAt + 1L,
                                    "a relight on a later tick restarts the program there, got "
                                            + be.programStartTick() + " (stopped at " + stoppedAt + ")");
                            be.cancelCraft(player);
                            helper.succeed();
                        } finally {
                            restoreJob(prevJob);
                        }
                    });
                } catch (RuntimeException | Error failure) {
                    restoreJob(prevJob);
                    throw failure;
                }
            });
        } finally {
            if (!handedOff) {
                restoreJob(prevJob);
            }
        }
    }

    /** 起点 + strike + cycle × n (n ≥ 0) 里不晚于 until 的那些 tick。 */
    private static List<Long> beats(long start, long strike, long cycle, long until) {
        List<Long> out = new ArrayList<>();
        for (long t = start + strike; t <= until; t += cycle) {
            out.add(t);
        }
        return out;
    }

    /** 列表里不晚于 until 的那些。 */
    private static List<Long> upTo(List<Long> ticks, long until) {
        return ticks.stream().filter(t -> t <= until).toList();
    }

    /** 每一个都在拍上 (起点 + strike + cycle × n)。 */
    private static boolean onBeat(List<Long> ticks, long start, long strike, long cycle) {
        return ticks.stream().allMatch(t -> t >= start + strike && Math.floorMod(t - start - strike, cycle) == 0L);
    }

    /**
     * 把出站队列里的包读空; 本 tick 在某个冲压点 (strikesAt 的键) 播出的冲压音记进对应的列表 (别的声音、别处一律忽略;
     * 传空表就是只读空队列)。
     */
    private static void collectStrikeSounds(EmbeddedChannel channel, long now, Map<Vec3, List<Long>> strikesAt) {
        Object message;
        while ((message = channel.readOutbound()) != null) {
            if (!(message instanceof ClientboundSoundPacket sound)
                    || sound.getSound().value() != ModMunitionsSounds.MUNITIONS_BENCH_WELD.get()) {
                continue;
            }
            for (Map.Entry<Vec3, List<Long>> entry : strikesAt.entrySet()) {
                Vec3 strikeAt = entry.getKey();
                if (Math.abs(sound.getX() - strikeAt.x) < 0.2D && Math.abs(sound.getY() - strikeAt.y) < 0.2D
                        && Math.abs(sound.getZ() - strikeAt.z) < 0.2D) {
                    entry.getValue().add(now);
                }
            }
        }
    }

    // ============================================================
    // 弹药箱计数屏 (方案 C): 数字格式 / 满度条 / 满仓 / 显示键 / 同步标签 / 画字的位置
    // ============================================================

    /**
     * 期望值取方案 C 定下的口径 (与 tools/munitions_bench/counter.mjs 的样例同一组), 不抄实现: ≤ 9999 原样, 上万 "12.4K",
     * 上百万 "3.2M", 只舍不入、去掉小数末尾的 0、最多 5 个字且放得进 22 qt 的发数框; 负数当空箱, 超大按 "999G"。
     * 满度条 = floor(发数 × 22 / 上限), 有弹至少 1 格; 满仓 = 有弹且装不下下一批; 显示键只跟着看得见的东西变。
     */
    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void benchCounterFormatsBarsAndKeysLikeTheApprovedDesign(GameTestHelper helper) {
        Object[][] formats = {
                {0L, "0"}, {1L, "1"}, {347L, "347"}, {9999L, "9999"}, {10000L, "10K"}, {10099L, "10K"},
                {12480L, "12.4K"}, {123456L, "123K"}, {999999L, "999K"}, {1000000L, "1M"}, {1010000L, "1.01M"},
                {3200000L, "3.2M"}, {12345678L, "12.3M"}, {2147483647L, "2.14G"},
                {-1L, "0"}, {Long.MIN_VALUE, "0"}, {999_999_999_999L, "999G"}, {1_000_000_000_000L, "999G"},
                {Long.MAX_VALUE, "999G"},
        };
        helper.assertTrue(MunitionsBenchGeometry.COUNTER_COUNT_QT[2] == 22 && MunitionsBenchGeometry.COUNTER_BAR_QT[2] == 22,
                "option C: the count box and the fill bar are both 22 qt (5.5 px) wide inside the 24 qt lid window");
        for (Object[] sample : formats) {
            long rounds = (Long) sample[0];
            String text = MunitionsBenchCounter.format(rounds);
            helper.assertTrue(sample[1].equals(text), "format(" + rounds + ") must be " + sample[1] + ", got " + text);
            int width = MunitionsBenchCounter.FONT_4X7.textWidth(text) * MunitionsBenchGeometry.COUNTER_COUNT_TEXEL_QT;
            helper.assertTrue(text.length() <= 5 && width <= MunitionsBenchGeometry.COUNTER_COUNT_QT[2],
                    "\"" + text + "\" must fit the count box: " + text.length() + " chars, " + width + " qt");
        }

        int[][] bars = {{0, 800, 0}, {-3, 800, 0}, {1, 800, 1}, {347, 800, 9}, {400, 800, 11}, {800, 800, 22},
                {5000, 800, 22}, {5, 0, 22}};
        for (int[] bar : bars) {
            helper.assertTrue(MunitionsBenchCounter.barCells(bar[0], bar[1]) == bar[2],
                    "barCells(" + bar[0] + ", cap " + bar[1] + ") must be " + bar[2] + ", got "
                            + MunitionsBenchCounter.barCells(bar[0], bar[1]));
        }
        helper.assertFalse(MunitionsBenchCounter.isFull(460, 500, 40), "exactly one batch of room left is not full");
        helper.assertTrue(MunitionsBenchCounter.isFull(461, 500, 40), "one round short of a batch is full (the bar turns amber)");
        helper.assertTrue(MunitionsBenchCounter.isFull(4000, 4000, 70), "the default top cap filled to the brim is full");
        helper.assertTrue(MunitionsBenchCounter.cannotTakeBatch(0, 30, 40) && !MunitionsBenchCounter.isFull(0, 30, 40),
                "an empty buffer is never shown full (there is no bar), even with a cap below one batch");

        long shown = MunitionsBenchCounter.displayKey(12480, 1, 500, true);
        helper.assertTrue(shown == MunitionsBenchCounter.displayKey(12479, 1, 500, true),
                "taking one round from 12.4K changes nothing visible, so the key must not change");
        helper.assertTrue(shown != MunitionsBenchCounter.displayKey(9999, 1, 500, true), "12.4K -> 9999 is visible");
        long base = MunitionsBenchCounter.displayKey(347, 1, 800, false);
        helper.assertTrue(base != MunitionsBenchCounter.displayKey(346, 1, 800, false), "below 10000 every round shows");
        helper.assertTrue(base != MunitionsBenchCounter.displayKey(347, 9, 800, false), "a calibre change shows on the can");
        helper.assertTrue(base != MunitionsBenchCounter.displayKey(347, 1, 700, false), "9 -> 10 bar cells shows");
        helper.assertTrue(base == MunitionsBenchCounter.displayKey(347, 1, 790, false),
                "a cap change that moves no bar cell is invisible");
        helper.assertTrue(MunitionsBenchCounter.displayKey(461, 1, 500, false)
                        != MunitionsBenchCounter.displayKey(461, 1, 500, true), "the amber full bar shows");
        long empty = MunitionsBenchCounter.displayKey(0, 1, 500, true);
        helper.assertTrue(empty == MunitionsBenchCounter.displayKey(0, -1, 800, false) && empty != MunitionsBenchCounter.NO_KEY,
                "an empty buffer shows a dim 0 only: calibre, cap and full do not show");
        helper.assertTrue(new MunitionsBenchCounter.Shown(347, 1, 800, false).displayKey() == base,
                "Shown.displayKey is the same key");

        // 画什么: 发数右对齐贴着发数框右沿; 满度条从条左端起; 口径 "7.62" (13 qt) 居中在 28 qt 宽的箱身正面
        int right = MunitionsBenchGeometry.COUNTER_COUNT_QT[0] + MunitionsBenchGeometry.COUNTER_COUNT_QT[2];
        int[] zero = MunitionsBenchCounter.rects(0, "7.62", 800, true);
        helper.assertTrue(zero.length > 0 && allRects(zero, r -> r[0] == MunitionsBenchCounter.FACE_WINDOW
                        && r[1] == MunitionsBenchCounter.ROLE_ZERO) && maxRect(zero, 4, -1, -1) == right,
                "an empty buffer draws only a dim right-aligned 0: no bar, no calibre, got " + Arrays.toString(zero));
        int[] full = MunitionsBenchCounter.rects(347, "7.62", 800, false);
        helper.assertTrue(maxRect(full, 4, MunitionsBenchCounter.FACE_WINDOW, MunitionsBenchCounter.ROLE_COUNT) == right,
                "the count is right-aligned in the count box");
        int[] bar = MunitionsBenchGeometry.COUNTER_BAR_QT;
        helper.assertTrue(countRects(full, r -> r[1] == MunitionsBenchCounter.ROLE_BAR && r[2] == bar[0] && r[3] == bar[1]
                        && r[4] == bar[0] + 9 && r[5] == bar[1] + bar[3]) == 1,
                "347 of 800 lights 9 bar cells from the left end, got " + Arrays.toString(full));
        helper.assertTrue(minRect(full, 2, MunitionsBenchCounter.FACE_STENCIL, MunitionsBenchCounter.ROLE_STENCIL) == 7
                        && maxRect(full, 4, MunitionsBenchCounter.FACE_STENCIL, MunitionsBenchCounter.ROLE_STENCIL) == 20
                        && minRect(full, 3, MunitionsBenchCounter.FACE_STENCIL, MunitionsBenchCounter.ROLE_STENCIL)
                        == MunitionsBenchGeometry.COUNTER_STENCIL_Y_QT,
                "the calibre stencil 7.62 is centred on the can front (x 7..20 qt)");
        helper.assertTrue(countRects(MunitionsBenchCounter.rects(461, "7.62", 500, true),
                        r -> r[1] == MunitionsBenchCounter.ROLE_BAR_FULL) == 1,
                "a full buffer draws the amber bar");
        helper.assertTrue(countRects(MunitionsBenchCounter.rects(347, null, 800, false),
                        r -> r[0] == MunitionsBenchCounter.FACE_STENCIL) == 0,
                "no calibre label, no stencil");

        // 颜色: 六档一行; 待机比工作暗; 满仓琥珀与模板字黄漆每档相同
        helper.assertTrue(MunitionsBenchGeometry.COUNTER_COLOURS.length == MunitionsBenchAssets.TIER_IDS.length,
                "one colour row per tier");
        for (int tier = 0; tier < MunitionsBenchGeometry.COUNTER_COLOURS.length; tier++) {
            int working = MunitionsBenchCounter.colour(tier, MunitionsBenchCounter.ROLE_COUNT, true);
            int idle = MunitionsBenchCounter.colour(tier, MunitionsBenchCounter.ROLE_COUNT, false);
            helper.assertTrue(luma(idle) < luma(working), "tier " + tier + ": idle digits must be dimmer than working");
            helper.assertTrue(MunitionsBenchCounter.colour(tier, MunitionsBenchCounter.ROLE_BAR_FULL, true) == 0xFFB234
                            && MunitionsBenchCounter.colour(tier, MunitionsBenchCounter.ROLE_STENCIL, false) == 0xF4C22C,
                    "tier " + tier + ": the full bar is amber and the can stencil is hazard yellow in every tier");
        }
        helper.succeed();
    }

    /**
     * 同步标签只带计数屏看得见的四个值 (+ 工作时的程序起点), 方块实体数据包与区块包同一份; 客户端两条入口都只读这几个值,
     * 不走 load() (台主、缓冲字段都不动); 满仓按下一批的口径算 (没选口径时按缓冲里的); 空包 (null 标签) 不清掉已有的状态。
     */
    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void benchCounterUpdateTagCarriesOnlyTheDisplayedState(GameTestHelper helper) {
        ServerPlayer player = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);
        MunitionsBenchBlockEntity be = newBench(helper, player);
        int cap = MunitionsLevels.bufferPerTable(1);
        seedBuffer(be, MunitionsCaliber.RIFLE, 347);

        CompoundTag tag = be.getUpdateTag();
        helper.assertTrue(tag.getAllKeys().equals(Set.of("BufferedRounds", "BufferedCaliber", "BufferCap", "BufferFull")),
                "an idle bench's update tag carries the four counter values only (no inventory / owner), got "
                        + tag.getAllKeys());
        helper.assertTrue(tag.getInt("BufferedRounds") == 347 && tag.getInt("BufferedCaliber") == MunitionsCaliber.RIFLE.index()
                        && tag.getInt("BufferCap") == cap && !tag.getBoolean("BufferFull"),
                "the update tag must carry 347 rounds of RIFLE, cap " + cap + ", not full; got " + tag);
        if (!(be.getUpdatePacket() instanceof ClientboundBlockEntityDataPacket packet)) {
            throw new IllegalStateException("the munitions bench must send a block entity data packet");
        }
        helper.assertTrue(tag.equals(packet.getTag()), "the data packet must carry the same tag as the chunk packet");

        MunitionsBenchBlockEntity client = new MunitionsBenchBlockEntity(be.getBlockPos(), be.getBlockState());
        client.onDataPacket(new Connection(PacketFlow.CLIENTBOUND), packet);
        MunitionsBenchCounter.Shown expected = new MunitionsBenchCounter.Shown(347, MunitionsCaliber.RIFLE.index(), cap, false);
        helper.assertTrue(expected.equals(client.clientCounter()),
                "onDataPacket must read the counter, got " + client.clientCounter());
        helper.assertTrue(client.owner() == null && client.bufferedRounds() == 0,
                "onDataPacket must not load() the tag into the client copy (owner / buffer stay untouched)");
        MunitionsBenchBlockEntity chunkClient = new MunitionsBenchBlockEntity(be.getBlockPos(), be.getBlockState());
        chunkClient.handleUpdateTag(tag);
        helper.assertTrue(expected.equals(chunkClient.clientCounter()), "the chunk packet path reads the same counter");
        // 没有 level 的副本 (别的模组的客户端预览) 组标签时交出同步来的值, 不读服务端状态与 SERVER config
        CompoundTag levelless = chunkClient.getUpdateTag();
        helper.assertTrue(levelless.equals(tag),
                "a level-less copy must hand back the synced counter, not its own (empty) server state; got " + levelless);
        // 别的模组发来整份存档 NBT (同名的 BufferedRounds / BufferedCaliber, 但没有 BufferCap): 不动计数屏
        CompoundTag saved = be.saveWithoutMetadata();
        helper.assertTrue(saved.contains("BufferedRounds") && !saved.contains("BufferCap"),
                "precondition: the save shares BufferedRounds with the update tag but has no BufferCap; got " + saved.getAllKeys());
        seedBuffer(be, MunitionsCaliber.RIFLE, 12);
        chunkClient.handleUpdateTag(be.saveWithoutMetadata());
        helper.assertTrue(expected.equals(chunkClient.clientCounter()),
                "a full save NBT (no BufferCap) must not replace the counter with cap 0, got " + chunkClient.clientCounter());

        // 装不下下一批 (步枪 L1 一批 40 发, 只剩 20 发空间) = 满仓; 没选口径时按缓冲里的口径算
        seedBuffer(be, MunitionsCaliber.RIFLE, cap - 20);
        helper.assertTrue(be.getUpdateTag().getBoolean("BufferFull"),
                "a buffer 20 rounds short of the cap cannot take a 40-round RIFLE batch: full");

        be.onOutputTaken(player, cap - 20);
        CompoundTag emptied = be.getUpdateTag();
        helper.assertTrue(emptied.getInt("BufferedRounds") == 0 && emptied.getInt("BufferedCaliber") == -1
                        && !emptied.getBoolean("BufferFull"),
                "an emptied buffer sends 0 rounds, no calibre and not full, got " + emptied);
        client.handleUpdateTag(emptied);
        helper.assertTrue(client.clientCounter().equals(new MunitionsBenchCounter.Shown(0, -1, cap, false)),
                "the client copy follows the emptied buffer, got " + client.clientCounter());

        ClientboundBlockEntityDataPacket nullTagPacket = ClientboundBlockEntityDataPacket.create(be, ignored -> new CompoundTag());
        helper.assertTrue(nullTagPacket.getTag() == null, "precondition: vanilla turns an empty packet tag into null");
        client.handleUpdateTag(tag);
        client.onDataPacket(new Connection(PacketFlow.CLIENTBOUND), nullTagPacket);
        helper.assertTrue(expected.equals(client.clientCounter()), "a data packet without a tag must leave the counter as it was");
        helper.succeed();
    }

    /**
     * 画字的位置与绕序: 渲染器摆角用的 {@link MunitionsBenchCounter#blockCorners} (朝向 rotY → scale(1/16) → 窗面再绕 x 转箱盖的角度,
     * 箱身不转; 渲染器只把结果交给 BER 的 poseStack) 把整块窗 / 整块箱身正面摆到的四个角, 必须正是静态 JSON 里箱盖窗后本体
     * (can_lid) / 箱身 (can) 的北面按 JSON 的旋转与方块状态的朝向转过去、再往面外挪 LIFT 的地方, 顺序 左上 → 左下 → 右下 → 右上,
     * 四个朝向下绕序都朝外 (textBackground 剔除背面, 反了字就没了); 字浮在面外 LIFT, 比凹窗的深度浅 (藏在框条前沿之后)。
     * 一屏真实的字 (12.4K / 7.62 / 满仓) 的每个角也都落在各自的面里。
     */
    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void wideBenchCounterFacesLandOnTheLidWindowAndTheCanFront(GameTestHelper helper) {
        JsonObject model = MunitionsBenchAssets.blockModel(MunitionsBenchAssets.lineModelName("munitions_bench", "main", false));
        JsonObject lid = modelElement(model, "can_lid");
        JsonObject can = modelElement(model, "can");
        float[] lidFrom = floats(lid.getAsJsonArray("from"));
        float[] lidTo = floats(lid.getAsJsonArray("to"));
        float[] canFrom = floats(can.getAsJsonArray("from"));
        float[] canTo = floats(can.getAsJsonArray("to"));
        JsonObject rotation = lid.getAsJsonObject("rotation");
        helper.assertTrue(rotation != null && "x".equals(rotation.get("axis").getAsString()) && !can.has("rotation"),
                "the lid turns about x in the model and the can front does not turn");
        float jsonAngle = rotation.get("angle").getAsFloat();
        float[] jsonOrigin = floats(rotation.getAsJsonArray("origin"));
        helper.assertTrue(MunitionsBenchGeometry.COUNTER_LIFT_PX > 0.0F
                        && MunitionsBenchGeometry.COUNTER_LIFT_PX < MunitionsBenchGeometry.COUNTER_RECESS_PX,
                "the digits float off the window face but stay behind the frame strips");

        float lift = MunitionsBenchGeometry.COUNTER_LIFT_PX;
        int[] window = MunitionsBenchGeometry.COUNTER_WINDOW_QT;
        int[] stencilSize = MunitionsBenchGeometry.COUNTER_STENCIL_FACE_QT;
        // 整块窗与整块箱身正面当作两个矩形, 交给渲染器摆角的同一个方法
        int[] wholeFaces = {
                MunitionsBenchCounter.FACE_WINDOW, MunitionsBenchCounter.ROLE_COUNT,
                window[0], window[1], window[0] + window[2], window[1] + window[3],
                MunitionsBenchCounter.FACE_STENCIL, MunitionsBenchCounter.ROLE_STENCIL, 0, 0, stencilSize[0], stencilSize[1],
        };
        int[] screen = MunitionsBenchCounter.rects(12480, MunitionsCaliber.RIFLE.shortLabel(), 500, true);
        helper.assertTrue(countRects(screen, r -> r[0] == MunitionsBenchCounter.FACE_STENCIL) > 0
                        && countRects(screen, r -> r[1] == MunitionsBenchCounter.ROLE_BAR_FULL) == 1,
                "precondition: the sample screen has digits, a full bar and a calibre stencil");
        for (Direction facing : Direction.Plane.HORIZONTAL) {
            float yRotation = MunitionsBenchBlock.partsYRotationDegrees(facing);
            float[] drawn = MunitionsBenchCounter.blockCorners(wholeFaces, yRotation);
            helper.assertTrue(drawn.length == 2 * MunitionsBenchCounter.CORNER_FLOATS, "two rects, four corners each");
            // 窗: 左上 (to.x, to.y) → 左下 (to.x, from.y) → 右下 (from.x, from.y) → 右上 (from.x, to.y), 在 can_lid 北面外 LIFT
            float lidZ = lidFrom[2] - lift;
            assertCorners(helper, facing + " window", drawn, 0, new Vec3[]{
                    modelPoint(facing, lidTo[0], lidTo[1], lidZ, jsonOrigin, jsonAngle),
                    modelPoint(facing, lidTo[0], lidFrom[1], lidZ, jsonOrigin, jsonAngle),
                    modelPoint(facing, lidFrom[0], lidFrom[1], lidZ, jsonOrigin, jsonAngle),
                    modelPoint(facing, lidFrom[0], lidTo[1], lidZ, jsonOrigin, jsonAngle)});
            float canZ = canFrom[2] - lift;
            assertCorners(helper, facing + " can front", drawn, MunitionsBenchCounter.CORNER_FLOATS, new Vec3[]{
                    modelPoint(facing, canTo[0], canTo[1], canZ, null, 0.0F),
                    modelPoint(facing, canTo[0], canFrom[1], canZ, null, 0.0F),
                    modelPoint(facing, canFrom[0], canFrom[1], canZ, null, 0.0F),
                    modelPoint(facing, canFrom[0], canTo[1], canZ, null, 0.0F)});
            assertFacesOut(helper, facing + " window", drawn, 0,
                    modelPoint(facing, lidTo[0], lidTo[1], lidFrom[2] - 1.0F, jsonOrigin, jsonAngle)
                            .subtract(modelPoint(facing, lidTo[0], lidTo[1], lidFrom[2], jsonOrigin, jsonAngle)));
            assertFacesOut(helper, facing + " can front", drawn, MunitionsBenchCounter.CORNER_FLOATS,
                    modelPoint(facing, canTo[0], canTo[1], canFrom[2] - 1.0F, null, 0.0F)
                            .subtract(modelPoint(facing, canTo[0], canTo[1], canFrom[2], null, 0.0F)));

            // 一屏真实的字: 每个角都在各自那块面的四个角围成的矩形里 (按面上的两条边投影, 允许浮点误差)
            float[] corners = MunitionsBenchCounter.blockCorners(screen, yRotation);
            for (int r = 0; r * MunitionsBenchCounter.RECT_INTS < screen.length; r++) {
                int faceOffset = screen[r * MunitionsBenchCounter.RECT_INTS] == MunitionsBenchCounter.FACE_WINDOW
                        ? 0 : MunitionsBenchCounter.CORNER_FLOATS;
                for (int k = 0; k < 4; k++) {
                    assertInsideFace(helper, facing + " rect " + r + " corner " + k, drawn, faceOffset,
                            corners, r * MunitionsBenchCounter.CORNER_FLOATS + k * 3);
                }
            }
        }
        helper.succeed();
    }

    /** 模型像素点 (朝北) 先按 JSON 元素的旋转 (绕 x, 右手系) 转, 再按方块状态的朝向转到世界 (格, 主格在原点)。 */
    private static Vec3 modelPoint(Direction facing, float x, float y, float z, float[] origin, float angleDegrees) {
        double py = y;
        double pz = z;
        if (origin != null) {
            double a = Math.toRadians(angleDegrees);
            double dy = y - origin[1];
            double dz = z - origin[2];
            py = origin[1] + dy * Math.cos(a) - dz * Math.sin(a);
            pz = origin[2] + dy * Math.sin(a) + dz * Math.cos(a);
        }
        return MunitionsBenchBlock.benchPixelToWorld(BlockPos.ZERO, facing, x, py, pz);
    }

    /** blockCorners 里从 offset 起的四个角 (左上, 左下, 右下, 右上) 必须依次落在 expected 上 (方块坐标, 主格在原点)。 */
    private static void assertCorners(GameTestHelper helper, String label, float[] corners, int offset, Vec3[] expected) {
        String[] names = {"top-left", "bottom-left", "bottom-right", "top-right"};
        for (int k = 0; k < 4; k++) {
            Vec3 drawn = corner(corners, offset + k * 3);
            helper.assertTrue(drawn.distanceTo(expected[k]) < 1.0E-4D,
                    label + " " + names[k] + ": the renderer puts it at " + drawn + " but the static model has it at " + expected[k]);
        }
    }

    /** 渲染器的顶点顺序 (左上, 左下, 右下, 右上) 叉乘出的法线必须朝面外 (outward = 模型里往北挪一点的方向)。 */
    private static void assertFacesOut(GameTestHelper helper, String label, float[] corners, int offset, Vec3 outward) {
        Vec3 topLeft = corner(corners, offset);
        Vec3 normal = corner(corners, offset + 3).subtract(topLeft)
                .cross(corner(corners, offset + 6).subtract(topLeft)).normalize();
        double dot = normal.dot(outward.normalize());
        helper.assertTrue(dot > 0.999D, label + ": the TL, BL, BR, TR winding must face out of the model (dot " + dot
                + "), otherwise textBackground culls the digits");
    }

    /** corners[p..p+2] 落在 face[f..] (左上, 左下, 右下, 右上) 围成的矩形里: 沿两条边的投影在 [0, 1] 内, 且与它同一平面。 */
    private static void assertInsideFace(GameTestHelper helper, String label, float[] face, int f, float[] corners, int p) {
        Vec3 topLeft = corner(face, f);
        Vec3 down = corner(face, f + 3).subtract(topLeft);
        Vec3 right = corner(face, f + 9).subtract(topLeft);
        Vec3 d = corner(corners, p).subtract(topLeft);
        double s = d.dot(down) / down.lengthSqr();
        double t = d.dot(right) / right.lengthSqr();
        double off = Math.abs(d.dot(down.cross(right).normalize()));
        double eps = 1.0E-4D;
        helper.assertTrue(s >= -eps && s <= 1.0D + eps && t >= -eps && t <= 1.0D + eps && off < eps,
                label + ": " + corner(corners, p) + " is outside its face (down " + s + ", right " + t + ", off the plane " + off + ")");
    }

    private static Vec3 corner(float[] corners, int i) {
        return new Vec3(corners[i], corners[i + 1], corners[i + 2]);
    }

    private static JsonObject modelElement(JsonObject model, String name) {
        for (JsonElement element : model.getAsJsonArray("elements")) {
            JsonObject object = element.getAsJsonObject();
            if (object.has("name") && name.equals(object.get("name").getAsString())) {
                return object;
            }
        }
        throw new IllegalStateException("munitions bench model has no element named " + name);
    }

    private static float[] floats(JsonArray array) {
        float[] out = new float[array.size()];
        for (int i = 0; i < out.length; i++) {
            out[i] = array.get(i).getAsFloat();
        }
        return out;
    }

    private static boolean allRects(int[] rects, java.util.function.Predicate<int[]> test) {
        return countRects(rects, test) == rects.length / MunitionsBenchCounter.RECT_INTS;
    }

    private static int countRects(int[] rects, java.util.function.Predicate<int[]> test) {
        int n = 0;
        for (int i = 0; i + MunitionsBenchCounter.RECT_INTS <= rects.length; i += MunitionsBenchCounter.RECT_INTS) {
            if (test.test(Arrays.copyOfRange(rects, i, i + MunitionsBenchCounter.RECT_INTS))) {
                n++;
            }
        }
        return n;
    }

    /** 某面某角色的矩形里, 第 field 个 int 的最大值 (face / role 为 -1 = 不限)。 */
    private static int maxRect(int[] rects, int field, int face, int role) {
        int best = Integer.MIN_VALUE;
        for (int i = 0; i + MunitionsBenchCounter.RECT_INTS <= rects.length; i += MunitionsBenchCounter.RECT_INTS) {
            if ((face < 0 || rects[i] == face) && (role < 0 || rects[i + 1] == role)) {
                best = Math.max(best, rects[i + field]);
            }
        }
        return best;
    }

    private static int minRect(int[] rects, int field, int face, int role) {
        int best = Integer.MAX_VALUE;
        for (int i = 0; i + MunitionsBenchCounter.RECT_INTS <= rects.length; i += MunitionsBenchCounter.RECT_INTS) {
            if ((face < 0 || rects[i] == face) && (role < 0 || rects[i + 1] == role)) {
                best = Math.min(best, rects[i + field]);
            }
        }
        return best;
    }

    private static int luma(int rgb) {
        return (rgb >> 16 & 0xFF) * 3 + (rgb >> 8 & 0xFF) * 6 + (rgb & 0xFF);
    }

    /**
     * 计数屏只在看得见的样子变了时推给客户端: 读档后第一次结算推一次; 上万以后取走 1 发 ("12.4K" 不变)、重复结算都不推;
     * 同一 tick 里两次看得见的变化合并成恰好一个方块实体数据包 (包体是发送那一刻的标签); 取空推一次空箱。
     * 从 mock 玩家的 EmbeddedChannel 出站队列里数主格的 ClientboundBlockEntityDataPacket (与冲压音节拍用例同一手法)。
     * 单独一个 batch: 要挂几十 tick 数包, 不与同批其他用例的方块更新混在一起。
     */
    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = COUNTER_SYNC_BATCH, timeoutTicks = 100)
    public static void wideBenchCounterPushesOneUpdatePerVisibleChange(GameTestHelper helper) {
        ServerPlayer player = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);
        ServerLevel level = helper.getLevel();
        MunitionsBenchBlockEntity be = newBench(helper, player);
        BlockPos mainPos = be.getBlockPos();
        BlockState wide = be.getBlockState()
                .setValue(MunitionsBenchBlock.FACING, Direction.NORTH)
                .setValue(MunitionsBenchBlock.LAYOUT, MunitionsBenchBlock.Layout.WIDE);
        placeBenchPair(level, mainPos, MunitionsBenchBlock.extensionPos(mainPos, wide), wide);
        // mock 玩家的连接不 tick, 挪到台前后要自己登记区块追踪, 区块里的方块更新才会发给它
        player.moveTo(mainPos.getX() + 0.5D, mainPos.getY(), mainPos.getZ() - 2.5D);
        level.getChunkSource().move(player);
        EmbeddedChannel channel = (EmbeddedChannel) player.connection.connection.channel();
        int cap = MunitionsLevels.bufferPerTable(1);
        int rifle = MunitionsCaliber.RIFLE.index();
        seedBuffer(be, MunitionsCaliber.RIFLE, 12480);
        helper.assertTrue(be.syncedCounterKeyForTest() == MunitionsBenchCounter.NO_KEY,
                "an in-place load must forget the last pushed counter");

        helper.startSequence()
                .thenIdle(5)
                .thenExecute(() -> {
                    // 台主在线, 每 tick 都结算: 读档后的第一次结算已把 12.4K 推出去 (放台子的方块更新也带着同一份标签)
                    List<CompoundTag> pushed = counterPackets(channel, mainPos);
                    helper.assertTrue(!pushed.isEmpty() && pushed.get(pushed.size() - 1).getInt("BufferedRounds") == 12480,
                            "the first settle after the load must push 12480 rounds to the watching player, got " + pushed);
                    helper.assertTrue(be.syncedCounterKeyForTest() == MunitionsBenchCounter.displayKey(12480, rifle, cap, true),
                            "the pushed key is 12.4K / RIFLE / full bar / full");
                    be.onOutputTaken(player, 1); // 12479: 还是 "12.4K", 条满, 满仓
                    be.settleForOwner(player);   // 结算什么也没变
                })
                .thenIdle(3)
                .thenExecute(() -> {
                    List<CompoundTag> pushed = counterPackets(channel, mainPos);
                    helper.assertTrue(pushed.isEmpty(), "no visible change must push nothing, got " + pushed);
                    be.onOutputTaken(player, 2480); // 9999
                    be.onOutputTaken(player, 1);    // 9998, 同一 tick
                })
                .thenIdle(3)
                .thenExecute(() -> {
                    List<CompoundTag> pushed = counterPackets(channel, mainPos);
                    helper.assertTrue(pushed.size() == 1,
                            "two visible changes in one tick must reach the client as exactly one packet, got " + pushed);
                    CompoundTag tag = pushed.get(0);
                    helper.assertTrue(tag.getInt("BufferedRounds") == 9998 && tag.getInt("BufferedCaliber") == rifle
                                    && tag.getInt("BufferCap") == cap && tag.getBoolean("BufferFull"),
                            "the packet carries the state at send time: 9998 RIFLE, cap " + cap + ", full; got " + tag);
                    be.onOutputTaken(player, 9998); // 取空
                })
                .thenIdle(3)
                .thenExecute(() -> {
                    List<CompoundTag> pushed = counterPackets(channel, mainPos);
                    helper.assertTrue(pushed.size() == 1 && pushed.get(0).getInt("BufferedRounds") == 0
                                    && pushed.get(0).getInt("BufferedCaliber") == -1 && !pushed.get(0).getBoolean("BufferFull"),
                            "emptying the buffer must push one empty counter, got " + pushed);
                    MunitionsBenchBlockEntity client = new MunitionsBenchBlockEntity(mainPos, wide);
                    client.handleUpdateTag(pushed.get(0));
                    helper.assertTrue(client.clientCounter().equals(new MunitionsBenchCounter.Shown(0, -1, cap, false)),
                            "the client copy shows the empty can, got " + client.clientCounter());
                })
                .thenSucceed();
    }

    /** 把出站队列读空, 返回主格的方块实体数据包里的标签 (按到达顺序; 别处、别的包一律忽略)。 */
    private static List<CompoundTag> counterPackets(EmbeddedChannel channel, BlockPos pos) {
        List<CompoundTag> out = new ArrayList<>();
        Object message;
        while ((message = channel.readOutbound()) != null) {
            if (message instanceof ClientboundBlockEntityDataPacket packet && packet.getPos().equals(pos)
                    && packet.getTag() != null) {
                out.add(packet.getTag());
            }
        }
        return out;
    }

    // ============================================================
    // 6.1 产能曲线查表 (台数/速率/缓冲逐级精确)
    // ============================================================

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void capacityCurveLookups(GameTestHelper helper) {
        // L1 地基: 1 台 / 50 发每时 / 500 缓冲。
        helper.assertTrue(MunitionsLevels.tableCount(1) == 1, "L1 tableCount must be 1");
        helper.assertTrue(MunitionsLevels.ratePerTable(1) == 50, "L1 rate must be 50/hr");
        helper.assertTrue(MunitionsLevels.bufferPerTable(1) == 500, "L1 buffer must be 500");

        // L10 毕业: 6 台 / 210 发每时 / 4000 缓冲 (任务锚点)。
        helper.assertTrue(MunitionsLevels.tableCount(10) == 6, "L10 tableCount must be 6");
        helper.assertTrue(MunitionsLevels.ratePerTable(10) == 210, "L10 rate must be 210/hr");
        helper.assertTrue(MunitionsLevels.bufferPerTable(10) == 4000, "L10 buffer must be 4000");

        // clampLevel 防越界查表 (0 -> L1 下界; 99 -> L10 上界)。删 clampLevel 这两断言越界崩或读错档。
        helper.assertTrue(MunitionsLevels.tableCount(0) == MunitionsLevels.tableCount(1),
                "level below MIN clamps to L1 capacity");
        helper.assertTrue(MunitionsLevels.bufferPerTable(99) == MunitionsLevels.bufferPerTable(10),
                "level above MAX clamps to L10 capacity");
        helper.succeed();
    }

    // ============================================================
    // 6.1 口径等级门 + 四章 L6 提炼解锁
    // ============================================================

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void caliberAndRefineGates(GameTestHelper helper) {
        // 步枪 unlockLevel = L3: L1 锁, L2 锁, L3 解锁 (等级门)。
        helper.assertFalse(MunitionsLevels.isCaliberUnlocked(1, MunitionsCaliber.RIFLE),
                "L1 player CANNOT use RIFLE caliber (rifle gated to L3)");
        helper.assertFalse(MunitionsLevels.isCaliberUnlocked(2, MunitionsCaliber.RIFLE),
                "L2 still cannot use RIFLE (needs L3)");
        helper.assertTrue(MunitionsLevels.isCaliberUnlocked(3, MunitionsCaliber.RIFLE),
                "L3 unlocks RIFLE caliber");
        // 手枪 L1 / 特种 L10 端点。
        helper.assertTrue(MunitionsLevels.isCaliberUnlocked(1, MunitionsCaliber.PISTOL),
                "PISTOL unlocked from L1");
        helper.assertFalse(MunitionsLevels.isCaliberUnlocked(9, MunitionsCaliber.SPECIAL),
                "SPECIAL gated to L10, L9 cannot use it");
        helper.assertTrue(MunitionsLevels.isCaliberUnlocked(10, MunitionsCaliber.SPECIAL),
                "L10 unlocks SPECIAL (graduation caliber)");

        // highestUnlockedCaliber 单调: L1 仅手枪, L5 到战斗机枪 (L5 门), L10 到特种。
        helper.assertTrue(MunitionsLevels.highestUnlockedCaliber(1) == MunitionsCaliber.PISTOL,
                "L1 highest unlocked is PISTOL");
        helper.assertTrue(MunitionsLevels.highestUnlockedCaliber(5) == MunitionsCaliber.BATTLE,
                "L5 highest unlocked is BATTLE (battle rifle gate)");
        helper.assertTrue(MunitionsLevels.highestUnlockedCaliber(10) == MunitionsCaliber.SPECIAL,
                "L10 highest unlocked is SPECIAL");

        // 四章 L6 提炼利润质变线: L5 未解锁, L6 解锁。
        helper.assertFalse(MunitionsLevels.isRefineUnlocked(5), "L5 has NOT unlocked propellant refining");
        helper.assertTrue(MunitionsLevels.isRefineUnlocked(6), "L6 unlocks refining (profit inflection)");
        helper.succeed();
    }

    // ============================================================
    // 口径枚举: 序号往返 + 真 TACZ 默认弹药 path + 缩产系数 (无 TACZ 触达)
    // ============================================================

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void caliberEnumMapping(GameTestHelper helper) {
        // byIndex/index 往返自洽 (NBT/ContainerData 同步基石)。
        for (MunitionsCaliber c : MunitionsCaliber.values()) {
            helper.assertTrue(MunitionsCaliber.byIndex(c.index()) == c,
                    "byIndex(index) round-trips " + c);
        }
        // 越界回退 PISTOL (防数组越界崩, 不掩盖业务)。
        helper.assertTrue(MunitionsCaliber.byIndex(-1) == MunitionsCaliber.PISTOL,
                "negative index falls back to PISTOL");
        helper.assertTrue(MunitionsCaliber.byIndex(999) == MunitionsCaliber.PISTOL,
                "out-of-range index falls back to PISTOL");

        // 真默认枪包弹药 path (data/tacz/index/ammo/<path>.json; 仅字符串, 不构造 ResourceLocation / 触 com.tacz.*)。
        helper.assertTrue("762x39".equals(MunitionsCaliber.RIFLE.defaultAmmoPath()),
                "RIFLE default ammo path is 762x39, got " + MunitionsCaliber.RIFLE.defaultAmmoPath());
        helper.assertTrue("556x45".equals(MunitionsCaliber.RIFLE_556.defaultAmmoPath()),
                "RIFLE_556 default ammo path is 556x45, got " + MunitionsCaliber.RIFLE_556.defaultAmmoPath());
        helper.assertTrue(MunitionsCaliber.RIFLE_556.category() == MunitionsCaliber.Category.RIFLE,
                "RIFLE_556 appears under rifle ammo category");
        helper.assertTrue("40mm".equals(MunitionsCaliber.EXPLOSIVE.defaultAmmoPath()),
                "EXPLOSIVE default ammo path is 40mm, got " + MunitionsCaliber.EXPLOSIVE.defaultAmmoPath());
        helper.assertTrue("9mm".equals(MunitionsCaliber.PISTOL.defaultAmmoPath()),
                "PISTOL default ammo path is 9mm");
        helper.assertTrue(MunitionsCaliber.TACZ_NAMESPACE.equals("tacz"),
                "caliber namespace is constant tacz");

        // 解锁等级与 6.1 等级门一致 (枚举绑定真源)。
        helper.assertTrue(MunitionsCaliber.PISTOL.unlockLevel() == 1, "PISTOL unlock L1");
        helper.assertTrue(MunitionsCaliber.RIFLE.unlockLevel() == 3, "RIFLE unlock L3");
        helper.assertTrue(MunitionsCaliber.SPECIAL.unlockLevel() == 10, "SPECIAL unlock L10");

        // 缩产系数: 步枪基准 1.0; 高阶弹 (反器材/爆炸) 严格 < 步枪 (单发料重出弹少)。
        helper.assertTrue(MunitionsCaliber.RIFLE.yieldFactor() == 1.0,
                "RIFLE yield factor is baseline 1.0");
        helper.assertTrue(MunitionsCaliber.ANTI_MATERIEL.yieldFactor() < MunitionsCaliber.RIFLE.yieldFactor(),
                "ANTI_MATERIEL yield factor strictly below rifle (shrink)");
        helper.assertTrue(MunitionsCaliber.EXPLOSIVE.yieldFactor() < MunitionsCaliber.RIFLE.yieldFactor(),
                "EXPLOSIVE yield factor strictly below rifle (shrink)");
        helper.succeed();
    }

    // ============================================================
    // 单批口径实发数 (四章配方): L5 步枪直造 40, L6 步枪提炼 70 (利润质变线) + 高阶弹缩产
    // ============================================================

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void roundsPerBatchRefineInflection(GameTestHelper helper) {
        // L5 步枪 (提炼未解锁): 直造基准 40 × 1.0 = 40。
        helper.assertTrue(MunitionsProduction.roundsPerBatch(MunitionsCaliber.RIFLE, 5) == 40,
                "L5 rifle batch is direct 40 rounds (refine not yet unlocked)");
        // L6 步枪 (提炼解锁): 提炼基准 70 × 1.0 = 70 (翻倍利润质变线)。
        helper.assertTrue(MunitionsProduction.roundsPerBatch(MunitionsCaliber.RIFLE, 6) == 70,
                "L6 rifle batch jumps to refined 70 rounds (profit inflection)");
        // 删 isRefineUnlocked 分支 (恒用 DIRECT 40) -> L6 步枪仍 40, 上面 ==70 断言挂。

        // 高阶弹缩产: 同 L6 提炼基准 70, 反器材 (0.25) / 爆炸 (0.15) 严格 < 步枪 70。
        int rifleL6 = MunitionsProduction.roundsPerBatch(MunitionsCaliber.RIFLE, 6);
        int antiL6 = MunitionsProduction.roundsPerBatch(MunitionsCaliber.ANTI_MATERIEL, 6);
        int explosiveL6 = MunitionsProduction.roundsPerBatch(MunitionsCaliber.EXPLOSIVE, 6);
        helper.assertTrue(antiL6 < rifleL6,
                "ANTI_MATERIEL batch (" + antiL6 + ") strictly fewer than rifle (" + rifleL6 + ")");
        helper.assertTrue(explosiveL6 < rifleL6,
                "EXPLOSIVE batch (" + explosiveL6 + ") strictly fewer than rifle (" + rifleL6 + ")");
        // 精确缩产: floor(70 × 0.25) = 17; floor(70 × 0.15) = 10。删缩产 (恒用基准) 此两断言挂。
        helper.assertTrue(antiL6 == (int) Math.floor(70 * MunitionsCaliber.ANTI_MATERIEL.yieldFactor()),
                "ANTI_MATERIEL L6 = floor(70 * 0.25) = 17, got " + antiL6);
        helper.assertTrue(explosiveL6 == (int) Math.floor(70 * MunitionsCaliber.EXPLOSIVE.yieldFactor()),
                "EXPLOSIVE L6 = floor(70 * 0.15) = 10, got " + explosiveL6);
        // 缩产后至少 1 发 (有料即产, 不静默吞 0)。
        helper.assertTrue(MunitionsProduction.roundsPerBatch(MunitionsCaliber.EXPLOSIVE, 5) >= 1,
                "batch floor is >=1 round (never silently zero)");
        helper.succeed();
    }

    // ============================================================
    // 速率->tick 换算 (11.3): ceil(ticksPerRateHour / rate), >=1
    // ============================================================

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void ticksPerRoundCeil(GameTestHelper helper) {
        int hourTicks = MunitionsConfig.TICKS_PER_RATE_HOUR.get(); // 72000
        // L1 rate 50: ceil(72000 / 50) = 1440 tick/发。
        int expectL1 = (hourTicks + 50 - 1) / 50;
        helper.assertTrue(MunitionsProduction.ticksPerRound(1) == expectL1,
                "L1 ticksPerRound = ceil(72000/50) = " + expectL1 + ", got " + MunitionsProduction.ticksPerRound(1));
        helper.assertTrue(expectL1 == 1440, "ticksPerRound L1 numeric anchor = 1440");
        // L10 rate 210: ceil(72000 / 210) = 343 (向上取整, 71820/210=342 余 -> 343)。
        int expectL10 = (hourTicks + 210 - 1) / 210;
        helper.assertTrue(MunitionsProduction.ticksPerRound(10) == expectL10,
                "L10 ticksPerRound = ceil(72000/210) = " + expectL10);
        helper.assertTrue(expectL10 == 343, "ticksPerRound L10 numeric anchor = 343 (ceil, not floor 342)");
        // 高速率永不到 0 (>=1 下界, 防瞬产/0 除)。
        helper.assertTrue(MunitionsProduction.ticksPerRound(10) >= 1, "ticksPerRound floored to >=1");
        helper.succeed();
    }

    // ============================================================
    // 理论产能 (五章 总产能 = 台数 × 速率): capacity = floor(elapsed/ticksPerRound) × tableCount
    // ============================================================

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void theoreticalRoundsCapacity(GameTestHelper helper) {
        int level = 1;
        int perRound = MunitionsProduction.ticksPerRound(level); // 1440
        // 恰好 10 发的时间 × 3 台 = 30 发 (台数线性乘子)。
        long elapsed = perRound * 10L;
        helper.assertTrue(MunitionsProduction.theoreticalRounds(elapsed, 1, level) == 10L,
                "1 table over 10-round time yields 10 rounds");
        helper.assertTrue(MunitionsProduction.theoreticalRounds(elapsed, 3, level) == 30L,
                "3 tables over same time yields 3x = 30 rounds (capacity = per-table × tableCount). "
                        + "Delete the × tableCount and this must fail.");
        // 不足一发的时间 -> 0 (向下取整, 不产半发)。
        helper.assertTrue(MunitionsProduction.theoreticalRounds(perRound - 1, 5, level) == 0L,
                "sub-one-round time yields 0 (floor, no fractional rounds)");
        // 边界: 0 流逝 / 0 台 -> 0。
        helper.assertTrue(MunitionsProduction.theoreticalRounds(0L, 4, level) == 0L, "0 elapsed -> 0");
        helper.assertTrue(MunitionsProduction.theoreticalRounds(elapsed, 0, level) == 0L, "0 tables -> 0");
        helper.succeed();
    }

    // ============================================================
    // 工费 (九章 sink): floor(rounds × 1.5) 经 ×10 锚价整数化为 floor(rounds × 15 / 10)
    // ============================================================

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void workFeeIntegerized(GameTestHelper helper) {
        // 精确 CP: 10 发 -> 15; 100 发 -> 150; 1 发 -> floor(1.5) = 1; 7 发 -> floor(10.5) = 10。
        helper.assertTrue(MunitionsProduction.workFee(10) == 15L, "10 rounds work fee = 15 CP (1.5/round)");
        helper.assertTrue(MunitionsProduction.workFee(100) == 150L, "100 rounds work fee = 150 CP");
        helper.assertTrue(MunitionsProduction.workFee(1) == 1L, "1 round = floor(1.5) = 1 CP");
        helper.assertTrue(MunitionsProduction.workFee(7) == 10L, "7 rounds = floor(7*15/10) = floor(10.5) = 10 CP");
        helper.assertTrue(MunitionsProduction.workFee(3) == 4L, "3 rounds = floor(3*15/10) = floor(4.5) = 4 CP");
        // 边界: 0 发 0 工费。
        helper.assertTrue(MunitionsProduction.workFee(0) == 0L, "0 rounds = 0 fee");
        // 删 /10 (恒按 ×10 锚价不还原) -> 10 发会变 150, 上面 ==15 断言挂。
        helper.succeed();
    }

    // ============================================================
    // 产弹经验 (七章 谁产谁得): floor(rounds × perRoundMilli / 1000)
    // ============================================================

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void produceXpByRounds(GameTestHelper helper) {
        int perMilli = MunitionsConfig.PRODUCE_XP_PER_ROUND_MILLI.get(); // 1000 = 1 xp/round
        // perMilli 1000: 每发 1 经验 -> N 发 = N 经验。
        helper.assertTrue(MunitionsProduction.produceXp(40) == (long) 40 * perMilli / 1000L,
                "40 rounds produce xp = floor(40 * perMilli / 1000)");
        helper.assertTrue(MunitionsProduction.produceXp(40) == 40L, "40 rounds = 40 raw xp at default 1000 milli");
        helper.assertTrue(MunitionsProduction.produceXp(0) == 0L, "0 rounds = 0 xp");
        // 更多发更多经验 (单调; 谁产谁得按发数线性)。
        helper.assertTrue(MunitionsProduction.produceXp(70) > MunitionsProduction.produceXp(40),
                "more rounds yield strictly more raw xp (70 > 40)");
        helper.succeed();
    }

    // ============================================================
    // 离线追算核心 settle (五章): rounds == batches × roundsPerBatch; 三门取最小 (时间/缓冲/料)
    // ============================================================

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void settleClampedByMaterial(GameTestHelper helper) {
        // 料瓶颈: 充裕时间 + 充裕缓冲, 但四件套只够 2 批。
        int level = 5; // 直造 40 发/批 (步枪)。
        int perBatch = MunitionsProduction.roundsPerBatch(MunitionsCaliber.RIFLE, level); // 40
        long bigElapsed = MunitionsProduction.ticksPerRound(level) * 100_000L; // 远超需求。
        int bigBuffer = 100_000;
        // 备料 = 2 批 x 各件单批 cost (对默认 cost 变更鲁棒, propellantCost 默认 2 -> 备 4)。
        MunitionsProduction.Result r = MunitionsProduction.settle(
                MunitionsCaliber.RIFLE, level, 1, bigElapsed, bigBuffer,
                2 * MunitionsConfig.RECIPE_PRIMER_COST.get(),
                2 * MunitionsConfig.RECIPE_CASING_COST.get(),
                2 * MunitionsConfig.RECIPE_BULLET_HEAD_COST.get(),
                2 * MunitionsConfig.RECIPE_PROPELLANT_COST.get(), Integer.MAX_VALUE);
        helper.assertTrue(r.batchesConsumed() == 2, "material caps to exactly 2 batches, got " + r.batchesConsumed());
        helper.assertTrue(r.roundsProduced() == 2 * perBatch,
                "rounds == batches × roundsPerBatch = 2*40 = 80, got " + r.roundsProduced());
        helper.assertTrue(r.primerConsumed() == 2 * MunitionsConfig.RECIPE_PRIMER_COST.get(),
                "primer consumed = 2 batches, got " + r.primerConsumed());
        helper.assertTrue(r.casingConsumed() == 2 * MunitionsConfig.RECIPE_CASING_COST.get(),
                "casing consumed = 2 batches, got " + r.casingConsumed());
        helper.assertTrue(r.bulletHeadConsumed() == 2 * MunitionsConfig.RECIPE_BULLET_HEAD_COST.get(),
                "bullet head consumed = 2 batches, got " + r.bulletHeadConsumed());
        helper.assertTrue(r.propellantConsumed() == 2 * MunitionsConfig.RECIPE_PROPELLANT_COST.get(),
                "propellant consumed = 2 batches, got " + r.propellantConsumed());
        // 工费/经验与实产发数挂钩: 80 发 -> floor(80*1.5)=120 CP; 80 raw xp。
        helper.assertTrue(r.workFeeCredits() == MunitionsProduction.workFee(80),
                "work fee tracks produced rounds (80 -> 120 CP)");
        helper.assertTrue(r.workFeeCredits() == 120L, "80 rounds work fee numeric = 120 CP");
        helper.assertTrue(r.rawXp() == MunitionsProduction.produceXp(80), "raw xp tracks produced rounds (80)");
        helper.succeed();
    }

    /**
     * 电力门。电不足不是停产而是减产, 这是"电不够就一颗一颗慢慢造"的落点。
     * 同时钉死"每批电费与口径无关": 电价按步枪当量收, 单批实发数又正好乘了同一个 yieldFactor,
     * 两者约掉。若改成按实发数收电, 大口径的每 FE 产出会是小口径的十倍, 小口径彻底死掉。
     */
    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void settleClampedByPowerAndCostIsCaliberIndependent(GameTestHelper helper) {
        int level = 1;
        long bigElapsed = 20L * MunitionsConfig.TICKS_PER_RATE_HOUR.get();
        int feePerBatch = MunitionsProduction.feCostPerBatch(level);
        helper.assertTrue(feePerBatch == MunitionsConfig.DIRECT_ROUNDS_PER_BATCH.get()
                        * MunitionsConfig.FE_PER_RIFLE_EQUIVALENT_ROUND.get(),
                "每批电费必须等于基础批发数乘每发电价, 得到 " + feePerBatch);

        MunitionsProduction.Result twoBatches = MunitionsProduction.settle(
                MunitionsCaliber.RIFLE, level, 1, bigElapsed, 100_000,
                9999, 9999, 9999, 9999, feePerBatch * 2);
        helper.assertTrue(twoBatches.batchesConsumed() == 2,
                "两批的电只能产两批, 得到 " + twoBatches.batchesConsumed());
        helper.assertTrue(twoBatches.feConsumed() == feePerBatch * 2,
                "扣电必须等于批数乘每批电费, 得到 " + twoBatches.feConsumed());

        MunitionsProduction.Result shortOfOne = MunitionsProduction.settle(
                MunitionsCaliber.RIFLE, level, 1, bigElapsed, 100_000,
                9999, 9999, 9999, 9999, feePerBatch - 1);
        helper.assertFalse(shortOfOne.produced(),
                "不足一批的电必须一发不产, 得到 " + shortOfOne.roundsProduced());
        helper.assertTrue(shortOfOne.feConsumed() == 0, "不产就不得扣电");

        // 同等级同电量下, 不同口径消耗的电必须完全一致 —— 大口径只是发数少、每发更贵。
        MunitionsProduction.Result rifle = MunitionsProduction.settle(
                MunitionsCaliber.RIFLE, level, 1, bigElapsed, 100_000,
                9999, 9999, 9999, 9999, feePerBatch);
        MunitionsProduction.Result antiMateriel = MunitionsProduction.settle(
                MunitionsCaliber.ANTI_MATERIEL, level, 1, bigElapsed, 100_000,
                9999, 9999, 9999, 9999, feePerBatch);
        helper.assertTrue(rifle.feConsumed() == antiMateriel.feConsumed(),
                "同等级每批电费必须与口径无关, 步枪 " + rifle.feConsumed()
                        + " 反器材 " + antiMateriel.feConsumed());
        helper.assertTrue(rifle.roundsProduced() > antiMateriel.roundsProduced(),
                "同样一批电, 大口径的发数必须更少(缩产系数), 步枪 " + rifle.roundsProduced()
                        + " 反器材 " + antiMateriel.roundsProduced());
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void settleClampedByBufferAndTime(GameTestHelper helper) {
        int level = 5;
        int perBatch = MunitionsProduction.roundsPerBatch(MunitionsCaliber.RIFLE, level); // 40

        // 缓冲瓶颈: 料/时间充裕, 但缓冲只剩 100 发 -> 落整到 2 批 (80 发), 第 3 批 (>100) 不产。
        int bufferRemaining = 100;
        long bigElapsed = MunitionsProduction.ticksPerRound(level) * 100_000L;
        MunitionsProduction.Result byBuffer = MunitionsProduction.settle(
                MunitionsCaliber.RIFLE, level, 1, bigElapsed, bufferRemaining, 9999, 9999, 9999, 9999, Integer.MAX_VALUE);
        helper.assertTrue(byBuffer.batchesConsumed() == bufferRemaining / perBatch,
                "buffer 100 / 40-per-batch floors to 2 batches, got " + byBuffer.batchesConsumed());
        helper.assertTrue(byBuffer.roundsProduced() == 2 * perBatch,
                "buffer-clamped to 80 rounds (does not overfill the 100-round space), got "
                        + byBuffer.roundsProduced());
        helper.assertTrue(byBuffer.roundsProduced() <= bufferRemaining,
                "produced never exceeds buffer remaining (buffer-full stops production)");

        // 时间瓶颈: 料/缓冲充裕, 但流逝只够 1 批的步枪当量时间 -> 1 批 (40 发)。
        long oneBatchTime = MunitionsProduction.ticksPerRound(level) * (long) perBatch; // 恰好 40 发时间。
        MunitionsProduction.Result byTime = MunitionsProduction.settle(
                MunitionsCaliber.RIFLE, level, 1, oneBatchTime, 9999, 9999, 9999, 9999, 9999, Integer.MAX_VALUE);
        helper.assertTrue(byTime.batchesConsumed() == 1,
                "elapsed sufficient for exactly 1 batch yields 1 batch, got " + byTime.batchesConsumed());
        helper.assertTrue(byTime.roundsProduced() == perBatch, "time-clamped to 1 batch = 40 rounds");
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void settleNonProductiveBoundaries(GameTestHelper helper) {
        int level = 5;
        long bigElapsed = MunitionsProduction.ticksPerRound(level) * 100_000L;
        // 未选口径 (null) -> NONE。
        helper.assertTrue(MunitionsProduction.settle(null, level, 1, bigElapsed, 9999, 9999, 9999, 9999, 9999, Integer.MAX_VALUE)
                == MunitionsProduction.Result.NONE, "null caliber forfeits (NONE)");
        // 缓冲已满 (剩 0) -> NONE。
        helper.assertFalse(MunitionsProduction.settle(MunitionsCaliber.RIFLE, level, 1, bigElapsed, 0, 9999, 9999, 9999, 9999, Integer.MAX_VALUE)
                .produced(), "buffer full (remaining 0) produces nothing");
        // 料不足一批 -> NONE。
        helper.assertFalse(MunitionsProduction.settle(MunitionsCaliber.RIFLE, level, 1, bigElapsed, 9999, 0, 9999, 9999, 9999, Integer.MAX_VALUE)
                .produced(), "missing primer produces nothing (先查后扣, 不白产)");
        // 0 流逝 -> NONE (未达时间不产)。
        helper.assertFalse(MunitionsProduction.settle(MunitionsCaliber.RIFLE, level, 1, 0L, 9999, 9999, 9999, 9999, 9999, Integer.MAX_VALUE)
                .produced(), "0 elapsed produces nothing");
        // 时间不足一整批 (1 发的时间, 但一批要 40 发) -> NONE (按整批走料)。
        long subBatchTime = MunitionsProduction.ticksPerRound(level) * 1L;
        helper.assertFalse(MunitionsProduction.settle(MunitionsCaliber.RIFLE, level, 1, subBatchTime, 9999, 9999, 9999, 9999, 9999, Integer.MAX_VALUE)
                .produced(), "time for <1 full batch produces nothing (整批走料, 不产半批)");
        helper.succeed();
    }

    // ============================================================
    // 放置计数持久层 (5/10.5 台数上限校验基石): benchCount/increment/decrement
    // ============================================================

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void savedDataBenchCount(GameTestHelper helper) {
        ServerLevel overworld = helper.getLevel().getServer().overworld();
        MunitionsSavedData data = MunitionsSavedData.get(overworld);
        UUID who = UUID.randomUUID(); // 全新 UUID 防跨测试串扰。

        helper.assertTrue(data.benchCount(who) == 0, "unknown player has 0 benches");
        helper.assertTrue(data.increment(who) == 1, "first placement -> count 1");
        helper.assertTrue(data.increment(who) == 2, "second placement -> count 2");
        helper.assertTrue(data.benchCount(who) == 2, "benchCount reads back 2");
        helper.assertTrue(data.decrement(who) == 1, "break one -> count 1");
        helper.assertTrue(data.decrement(who) == 0, "break last -> count 0 (entry pruned)");
        helper.assertTrue(data.benchCount(who) == 0, "pruned player reads 0 again");
        // 计数不低于 0 (破坏多于放置不变负)。
        helper.assertTrue(data.decrement(who) == 0, "decrement below 0 clamps to 0 (no negative count)");
        helper.succeed();
    }

    // ============================================================
    // BE 选口径服务端权威等级门 (6.1): 拒未解锁口径 + 拒缓冲口径冲突 (用 MockGameTestPlayers)
    // ============================================================

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void benchSelectCaliberLevelGate(GameTestHelper helper) {
        ServerPlayer player = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);
        IJobService prevJob = swapJob(new FixedLevelJobService(3)); // L3: 步枪解锁, 狙击 (L6) 不解锁。
        try {
            MunitionsBenchBlockEntity be = newBench(helper, player);

            // L3 选步枪 (unlock L3): 接受。
            helper.assertTrue(be.trySelectCaliber(MunitionsCaliber.RIFLE, player),
                    "L3 player selects RIFLE (unlock L3) accepted");
            helper.assertTrue(be.selectedCaliber() == MunitionsCaliber.RIFLE, "selected caliber persisted as RIFLE");

            // L3 选狙击 (unlock L6): 服务端重校等级门拒绝, 选中口径不变 (不信客户端置灰)。
            helper.assertFalse(be.trySelectCaliber(MunitionsCaliber.SNIPER, player),
                    "L3 player CANNOT select SNIPER (gated to L6) - server rejects");
            helper.assertTrue(be.selectedCaliber() == MunitionsCaliber.RIFLE,
                    "rejected selection leaves prior caliber unchanged");
            helper.succeed();
        } finally {
            restoreJob(prevJob);
        }
    }

    // ============================================================
    // BE 选口径缓冲冲突拒切 (防混口径堆叠): 缓冲非空且口径不同时拒绝换口径
    // ============================================================

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void benchSelectCaliberBufferMismatchRejected(GameTestHelper helper) {
        ServerPlayer player = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);
        // L4: 步枪 (L3) 与霰弹 (L4) 均解锁, 排除等级门干扰, 单验缓冲冲突门。
        IJobService prevJob = swapJob(new FixedLevelJobService(4));
        try {
            MunitionsBenchBlockEntity be = newBench(helper, player);
            helper.assertTrue(be.trySelectCaliber(MunitionsCaliber.RIFLE, player), "select RIFLE at L4 accepted");

            // 经 NBT 注入缓冲非空 (50 发步枪): 模拟已产步枪弹未取。
            seedBuffer(be, MunitionsCaliber.RIFLE, 50);

            // 缓冲是步枪且非空, 换霰弹 (虽 L4 已解锁) 被拒 (须先取空缓冲再换), 选中口径仍步枪。
            helper.assertFalse(be.trySelectCaliber(MunitionsCaliber.SHOTGUN, player),
                    "switching caliber with non-empty buffer of a different caliber is rejected (no mixed stacking)");
            helper.assertTrue(be.selectedCaliber() == MunitionsCaliber.RIFLE,
                    "rejected caliber switch leaves RIFLE selected");
            // 同口径 (步枪) 重选恒可 (不视为冲突)。
            helper.assertTrue(be.trySelectCaliber(MunitionsCaliber.RIFLE, player),
                    "re-selecting the SAME buffered caliber is always accepted");
            helper.succeed();
        } finally {
            restoreJob(prevJob);
        }
    }

    // ============================================================
    // BE 离线追算端到端 (五/七/九章): 在线主人一次性补产 -> 扣料 + 入缓冲 + 工费销毁 (按发数) + 谁产谁得经验
    // ============================================================

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void benchSettleChargesFeeAndGrantsXp(GameTestHelper helper) {
        ServerPlayer player = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);
        RecordingJobService job = new RecordingJobService(5); // L5: 步枪直造 40/批。
        IJobService prevJob = swapJob(job);
        EconomyLedger ledger = SqliteEconomyLedger.openInMemory();
        IEconomyService prevEco = swapEconomy(freshEconomy(ledger));
        try {
            MunitionsBenchBlockEntity be = newBench(helper, player);
            helper.assertTrue(be.trySelectCaliber(MunitionsCaliber.RIFLE, player), "select RIFLE at L5");

            // 备料 2 批四件套 -> 应产恰好 80 发 (料瓶颈, 时间/缓冲充裕)。
            stockParts(be, 2);

            // 预存余额够付 80 发工费 (120 CP); 余额充足时先查后扣放行本批。
            ledger.credit(player.getUUID(), Currency.CREDIT, 1000L);

            // 把 lastSettleTick 回拨到远古 (经 NBT 注入), 使本次 settle 的 elapsed 远超需求 -> 离线一次性补产
            // (确定性: 不依赖 GameTest 世界时钟推进, elapsed = now - 远古 >> 任何门槛, 实产受料瓶颈夹到 2 批)。
            backdateSettleTick(be, helper, MunitionsProduction.ticksPerRound(5) * 100_000L);
            be.settleForOwner(player);

            // 实产 80 发入缓冲 (料瓶颈夹到 2 批)。
            helper.assertTrue(be.bufferedRounds() == 80,
                    "online owner catch-up produces exactly 80 rounds (2 batches of 40), got " + be.bufferedRounds());
            // 真扣料: 四件套全部归零 (整批走料)。
            assertPartCounts(helper, be, 0);
            // 工费销毁入账 (sink): 余额按发数扣 120 CP (1000 -> 880)。
            helper.assertTrue(ledger.balance(player.getUUID(), Currency.CREDIT) == 880L,
                    "work fee sink destroys 120 CP for 80 rounds (1000-120=880), got "
                            + ledger.balance(player.getUUID(), Currency.CREDIT));
            // 谁产谁得: 经框架 grantXp 给在线主人, 原始经验 = 80 发 (按发数)。
            helper.assertTrue(job.grantXpCalls == 1, "grantXp called exactly once for the producing settle");
            helper.assertTrue(job.lastJob == JobId.MUNITIONS, "xp credited to MUNITIONS job");
            helper.assertTrue(job.lastRawXp == 80L,
                    "raw xp granted equals produced rounds (80), got " + job.lastRawXp);
            helper.succeed();
        } finally {
            restoreJob(prevJob);
            restoreEconomy(prevEco);
        }
    }

    /**
     * 已移除的主人 (死亡到重生之间的旧实体) 一律不结算。
     *
     * 2026-08-18 真服连环崩服回归: 台主一死, 旧 ServerPlayer 被标记 KILLED 且 capability 被 invalidate,
     * 但仍留在 PlayerList 里被军火台取到, 结算读职业等级即抛 IllegalStateException 崩掉服务端 tick。
     * 本例断言"已移除即不结算": 备了料、时间也回拨到远古 (不设防必产 80 发), 主人已移除则一发不产、
     * 料不扣、经验不给。删掉 settleForOwner 里的 isRemoved 守卫, 这三条断言全挂。
     */
    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void benchSkipsSettleForRemovedOwner(GameTestHelper helper) {
        ServerPlayer player = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);
        RecordingJobService job = new RecordingJobService(5);
        IJobService prevJob = swapJob(job);
        EconomyLedger ledger = SqliteEconomyLedger.openInMemory();
        IEconomyService prevEco = swapEconomy(freshEconomy(ledger));
        try {
            MunitionsBenchBlockEntity be = newBench(helper, player);
            helper.assertTrue(be.trySelectCaliber(MunitionsCaliber.RIFLE, player), "select RIFLE at L5");
            stockParts(be, 2);
            ledger.credit(player.getUUID(), Currency.CREDIT, 1000L);
            backdateSettleTick(be, helper, MunitionsProduction.ticksPerRound(5) * 100_000L);

            // 玩家死亡: 旧实体被标记移除 (Forge 随即失效其 capability), 但对象本身仍可被持有与传入。
            player.remove(net.minecraft.world.entity.Entity.RemovalReason.KILLED);
            be.settleForOwner(player);

            helper.assertTrue(be.bufferedRounds() == 0,
                    "已移除的主人不得产出, 实得 " + be.bufferedRounds());
            assertPartCounts(helper, be, 2);
            helper.assertTrue(job.grantXpCalls == 0,
                    "已移除的主人不得入账经验, 实得 " + job.grantXpCalls + " 次");
            helper.assertTrue(ledger.balance(player.getUUID(), Currency.CREDIT) == 1000L,
                    "已移除的主人不得扣工费, 实得 " + ledger.balance(player.getUUID(), Currency.CREDIT));
            helper.succeed();
        } finally {
            restoreJob(prevJob);
            restoreEconomy(prevEco);
        }
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void benchSettleForfeitsBatchWhenBalanceShort(GameTestHelper helper) {
        ServerPlayer player = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);
        RecordingJobService job = new RecordingJobService(5);
        IJobService prevJob = swapJob(job);
        EconomyLedger ledger = SqliteEconomyLedger.openInMemory();
        IEconomyService prevEco = swapEconomy(freshEconomy(ledger));
        try {
            MunitionsBenchBlockEntity be = newBench(helper, player);
            be.trySelectCaliber(MunitionsCaliber.RIFLE, player);
            stockParts(be, 2);
            // 余额仅 10 CP, 不够 80 发的 120 CP 工费 -> 本批作废 (扣不动则料不扣、缓冲不增、经验不给)。
            ledger.credit(player.getUUID(), Currency.CREDIT, 10L);

            backdateSettleTick(be, helper, MunitionsProduction.ticksPerRound(5) * 100_000L);
            be.settleForOwner(player);

            helper.assertTrue(be.bufferedRounds() == 0,
                    "insufficient balance forfeits the batch: no rounds buffered, got " + be.bufferedRounds());
            assertPartCounts(helper, be, 2);
            helper.assertTrue(ledger.balance(player.getUUID(), Currency.CREDIT) == 10L,
                    "balance untouched when charge fails (transaction-safe, no partial sink)");
            helper.assertTrue(job.grantXpCalls == 0, "no xp granted when batch forfeited (no production)");
            helper.succeed();
        } finally {
            restoreJob(prevJob);
            restoreEconomy(prevEco);
        }
    }

    // ============================================================
    // BE 工费扣不动时保留时间戳, 余额补足后一次性补产整段离线窗口 (munitions-01 回归)
    // ============================================================

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void benchSettlePreservesWindowWhenFeeFails(GameTestHelper helper) {
        ServerPlayer player = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);
        RecordingJobService job = new RecordingJobService(5); // L5: 步枪直造 40/批。
        IJobService prevJob = swapJob(job);
        EconomyLedger ledger = SqliteEconomyLedger.openInMemory();
        IEconomyService prevEco = swapEconomy(freshEconomy(ledger));
        try {
            MunitionsBenchBlockEntity be = newBench(helper, player);
            be.trySelectCaliber(MunitionsCaliber.RIFLE, player);
            // 备料 2 批 (14 铜 / 32 火药) -> 跨整段窗口期望产 80 发 (料瓶颈, 时间/缓冲充裕)。
            stockParts(be, 2);

            // 把 lastSettleTick 回拨到远古, 使本次 settle 的 elapsed 远超需求 (整段离线窗口)。记下回拨后的旧时间戳。
            backdateSettleTick(be, helper, MunitionsProduction.ticksPerRound(5) * 100_000L);
            long settleTickBeforeShortBalance = readSettleTick(be);

            // 第一次结算: 余额仅 10 CP, 不够 80 发的 120 CP 工费 -> 工费扣不动, 本批作废。
            ledger.credit(player.getUUID(), Currency.CREDIT, 10L);
            be.settleForOwner(player);

            // munitions-01 核心断言: 工费扣不动时时间戳必须保持旧值 (本段 elapsed 窗口未作废, 留待下次再追);
            // 缓冲为 0、料未扣、余额未动、未给经验。删掉 "扣费成功后才推进时间戳" 修复 -> 时间戳被提前推进到 now,
            // 此断言 (== 旧值) 必挂。
            helper.assertTrue(readSettleTick(be) == settleTickBeforeShortBalance,
                    "fee charge failure must NOT advance lastSettleTick (offline window retained for retry), expected "
                            + settleTickBeforeShortBalance + " got " + readSettleTick(be));
            helper.assertTrue(be.bufferedRounds() == 0,
                    "fee-failed settle buffers nothing, got " + be.bufferedRounds());
            assertPartCounts(helper, be, 2);
            helper.assertTrue(ledger.balance(player.getUUID(), Currency.CREDIT) == 10L,
                    "balance untouched when fee charge fails");
            helper.assertTrue(job.grantXpCalls == 0, "no xp granted when fee charge fails");

            // 补足余额后第二次结算: 因时间戳被保留, elapsed 仍为整段离线窗口 -> 一次性补产跨整窗的期望 80 发
            // (料瓶颈夹到 2 批), 工费 120 CP 扣成功 (1000+10 -> 890), 经验 80 一次性入主人。
            ledger.credit(player.getUUID(), Currency.CREDIT, 1000L); // 余额 1010 CP, 够付 120。
            be.settleForOwner(player);

            helper.assertTrue(be.bufferedRounds() == 80,
                    "after balance restored, the retained elapsed window is settled in one shot to 80 rounds, got "
                            + be.bufferedRounds());
            assertPartCounts(helper, be, 0);
            helper.assertTrue(ledger.balance(player.getUUID(), Currency.CREDIT) == 890L,
                    "work fee 120 CP charged once on the retained window (1010-120=890), got "
                            + ledger.balance(player.getUUID(), Currency.CREDIT));
            helper.assertTrue(job.grantXpCalls == 1, "xp granted exactly once on the successful catch-up settle");
            helper.assertTrue(job.lastRawXp == 80L,
                    "catch-up raw xp equals the full-window produced rounds (80), got " + job.lastRawXp);
            helper.succeed();
        } finally {
            restoreJob(prevJob);
            restoreEconomy(prevEco);
        }
    }

    // ============================================================
    // BE 输出槽 Shift 整栈取弹端到端回收缓冲 (munitions-output): 经 Menu.quickMoveStack 模拟 Shift 取整栈,
    // 断言 bufferedRounds 按真实取走发数精确回收 (非据基类传入的移除后残留 EMPTY 栈结算)
    // ============================================================

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void benchOutputShiftTakeRecyclesBuffer(GameTestHelper helper) {
        ServerPlayer player = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);
        // L5: 步枪解锁 (RIFLE 门 L3), 排除等级门干扰; settle 在无料时不产, 不污染本测的缓冲种子。
        IJobService prevJob = swapJob(new FixedLevelJobService(5));
        try {
            // --- 用例 A: 整栈取走 == 缓冲发数 (取单栈上限 64 内的 48, 保整栈一次性移走) -> bufferedRounds 归零 + bufferedCaliber 清空 ---
            MunitionsBenchBlockEntity beFull = newBench(helper, player);
            MunitionsBenchMenu menuFull = openBenchMenu(beFull, player);

            // 经 NBT 注入缓冲 48 发步枪 (权威发数, 单栈内取整栈); 输出槽物化为等量占位栈 (dev 无 TACZ, 不走真物化路径,
            // 占位 ItemStack 模拟主人在线访问帧 refreshOutputStack 物化出的可视弹栈)。注: 缓冲若超单栈上限(64)需分多次取,
            // 由用例 B 覆盖; 本例验单栈内整取的全额回收。
            seedBuffer(beFull, MunitionsCaliber.RIFLE, 48);
            beFull.inventory().setStackInSlot(MunitionsBenchBlockEntity.SLOT_OUTPUT,
                    new ItemStack(ModMunitionsItems.PRIMER.get(), 48));
            helper.assertTrue(beFull.bufferedRounds() == 48, "seeded buffer is 48 rounds before Shift-take");

            // Shift 取整栈 (走 AbstractMiningMenu.quickMoveStack): 基类把整栈移入玩家背包后, 传给 OutputSlot.onTake
            // 的是移除后残留 EMPTY 栈。修复前据此残留栈结算 -> onOutputTaken 首行 isEmpty 即 return, 缓冲永不回收。
            ItemStack moved = menuFull.quickMoveStack(player, MunitionsBenchBlockEntity.SLOT_OUTPUT);

            helper.assertTrue(moved.getCount() == 48,
                    "Shift moved the entire 48-count output stack into player inventory, got " + moved.getCount());
            helper.assertTrue(beFull.inventory().getStackInSlot(MunitionsBenchBlockEntity.SLOT_OUTPUT).isEmpty(),
                    "output slot is emptied after full Shift-take (no item duplication left behind)");
            // munitions-output 核心断言: 取走整栈 48 发 -> bufferedRounds 精确回收到 0 (让出空间继续产)。
            // 删 "据快照差值算真实取走量" 修复 (退回据基类传入的残留 EMPTY 栈) -> onOutputTaken 据 EMPTY 短路,
            // bufferedRounds 仍为 48, 此断言 (==0) 必挂。
            helper.assertTrue(beFull.bufferedRounds() == 0,
                    "Shift-taking the full stack recycles all 48 buffered rounds to 0 (buffer freed), got "
                            + beFull.bufferedRounds());

            // --- 用例 B: 取走量 < 缓冲发数 (单栈 64 < 缓冲 100) -> 精确减 64, 余 36 (证非 "粗暴归零" 短路) ---
            MunitionsBenchBlockEntity bePartial = newBench(helper, player);
            MunitionsBenchMenu menuPartial = openBenchMenu(bePartial, player);
            seedBuffer(bePartial, MunitionsCaliber.RIFLE, 100);
            bePartial.inventory().setStackInSlot(MunitionsBenchBlockEntity.SLOT_OUTPUT,
                    new ItemStack(ModMunitionsItems.PRIMER.get(), 64));

            ItemStack movedPartial = menuPartial.quickMoveStack(player, MunitionsBenchBlockEntity.SLOT_OUTPUT);

            helper.assertTrue(movedPartial.getCount() == 64,
                    "Shift moved the 64-count visualized stack, got " + movedPartial.getCount());
            // 真实取走量 64 从权威 100 发缓冲精确扣减 -> 余 36 (非 0; 证按真实取走量结算而非整批清零)。
            helper.assertTrue(bePartial.bufferedRounds() == 36,
                    "taking 64 of 100 buffered rounds leaves exactly 36, got " + bePartial.bufferedRounds());
            helper.succeed();
        } finally {
            restoreJob(prevJob);
        }
    }

    // ============================================================
    // 军火台电量同步 (界面电力块): 原版 ClientboundContainerSetDataPacket 把 ContainerData 按 int16 过线,
    // 电量/容量以 kFE 拆两个 15 位半字, 每个半字必须落在 [0, 0x7FFF]; 按 short 过线再拼回的 FE 与服务端一致。
    // 退回 "直发 FE" 或只发一个 int16 时, 默认容量 32,000,000 FE 过线即回绕, 本测必挂。
    // ============================================================

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void benchEnergySyncSurvivesInt16Wire(GameTestHelper helper) {
        ServerPlayer player = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);
        IJobService prevJob = swapJob(new FixedLevelJobService(5));
        try {
            // newBench 默认把内部缓冲充满 (= benchEnergyCapacity)。
            MunitionsBenchBlockEntity be = newBench(helper, player);
            net.minecraft.world.inventory.ContainerData data = be.dataAccess();
            helper.assertTrue(data.getCount() == MunitionsBenchBlockEntity.DATA_COUNT(),
                    "bench ContainerData exposes DATA_COUNT slots, got " + data.getCount());
            int[] energyIndices = {
                    MunitionsBenchBlockEntity.DATA_ENERGY_KFE_LO,
                    MunitionsBenchBlockEntity.DATA_ENERGY_KFE_HI,
                    MunitionsBenchBlockEntity.DATA_ENERGY_CAPACITY_KFE_LO,
                    MunitionsBenchBlockEntity.DATA_ENERGY_CAPACITY_KFE_HI};
            for (int index : energyIndices) {
                int value = data.get(index);
                helper.assertTrue(value >= 0 && value <= 0x7FFF,
                        "energy data slot " + index + " must fit a non-negative int16, got " + value);
            }

            long unit = MunitionsBenchBlockEntity.ENERGY_SYNC_UNIT_FE;
            long expectedFe = MunitionsConfig.BENCH_ENERGY_CAPACITY.get() / unit * unit;
            // 模拟过线: 原版按 short 写读 (符号扩展)。
            long storedFe = MunitionsBenchBlockEntity.unpackKfeToFe(
                    (short) data.get(MunitionsBenchBlockEntity.DATA_ENERGY_KFE_LO),
                    (short) data.get(MunitionsBenchBlockEntity.DATA_ENERGY_KFE_HI));
            long capacityFe = MunitionsBenchBlockEntity.unpackKfeToFe(
                    (short) data.get(MunitionsBenchBlockEntity.DATA_ENERGY_CAPACITY_KFE_LO),
                    (short) data.get(MunitionsBenchBlockEntity.DATA_ENERGY_CAPACITY_KFE_HI));
            helper.assertTrue(storedFe == expectedFe,
                    "stored FE survives the int16 wire, expected " + expectedFe + " got " + storedFe);
            helper.assertTrue(capacityFe == expectedFe,
                    "capacity FE survives the int16 wire, expected " + expectedFe + " got " + capacityFe);

            // Menu 访问器与 BE 同源 (服务端 menu 直接读 BE 的 dataAccess)。
            MunitionsBenchMenu menu = openBenchMenu(be, player);
            helper.assertTrue(menu.storedEnergyFe() == expectedFe && menu.energyCapacityFe() == expectedFe,
                    "menu energy accessors match the bench, got " + menu.storedEnergyFe() + " / "
                            + menu.energyCapacityFe());

            // 大值: 配置上限级别 (2,000,000,000 FE) 的高半字非零, 两半仍在 15 位内, 拼回无损。
            int big = 2_000_000_000;
            int lo = MunitionsBenchBlockEntity.kfeLowHalf(big);
            int hi = MunitionsBenchBlockEntity.kfeHighHalf(big);
            helper.assertTrue(lo >= 0 && lo <= 0x7FFF && hi > 0 && hi <= 0x7FFF,
                    "large FE splits into two 15-bit halves, got lo=" + lo + " hi=" + hi);
            helper.assertTrue(MunitionsBenchBlockEntity.unpackKfeToFe((short) lo, (short) hi) == 2_000_000_000L,
                    "large FE round-trips through the int16 wire");
            helper.succeed();
        } finally {
            restoreJob(prevJob);
        }
    }

    // ============================================================
    // 军火台缓冲同步: bufferL1..L10 配置上限 10,000,000, 缓冲发数 / 上限同样按 int16 过线。直发时上限 40000
    // 过线变成 -25536 (界面钳成 0), 70000 变成 4464, 新界面据此把"开始制造"误判成"缓冲已满"挡掉。
    // 两值都拆成两个 15 位半字; 本测把 L1 上限调到 40000、缓冲塞 35000 发, 按 short 过线后必须原样拼回。
    // ============================================================

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void benchBufferSyncSurvivesInt16Wire(GameTestHelper helper) {
        ServerPlayer player = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);
        IJobService prevJob = swapJob(new FixedLevelJobService(1));
        int originalBuffer = MunitionsConfig.BUFFER_L1.get();
        try {
            // 配置改动与还原都在本测同一次调用里完成, 同批其它用例看不到这个值。
            MunitionsConfig.BUFFER_L1.set(40_000);
            MunitionsBenchBlockEntity be = newBench(helper, player);
            // 先开菜单 (onAccess 把台主等级缓存刷成 1 级 -> 上限读 BUFFER_L1), 再塞缓冲。
            MunitionsBenchMenu menu = openBenchMenu(be, player);
            seedBuffer(be, MunitionsCaliber.PISTOL, 35_000);
            net.minecraft.world.inventory.ContainerData data = be.dataAccess();
            helper.assertTrue(data.getCount() == MunitionsBenchBlockEntity.DATA_COUNT(),
                    "bench ContainerData exposes DATA_COUNT slots, got " + data.getCount());
            int[] bufferIndices = {
                    MunitionsBenchBlockEntity.DATA_BUFFERED_ROUNDS,
                    MunitionsBenchBlockEntity.DATA_BUFFERED_ROUNDS_HI,
                    MunitionsBenchBlockEntity.DATA_BUFFER_CAP,
                    MunitionsBenchBlockEntity.DATA_BUFFER_CAP_HI};
            for (int index : bufferIndices) {
                int value = data.get(index);
                helper.assertTrue(value >= 0 && value <= 0x7FFF,
                        "buffer data slot " + index + " must fit a non-negative int16, got " + value);
            }

            // 模拟过线: 原版按 short 写读 (符号扩展)。
            int wireCap = MunitionsBenchBlockEntity.unpackHalves15(
                    (short) data.get(MunitionsBenchBlockEntity.DATA_BUFFER_CAP),
                    (short) data.get(MunitionsBenchBlockEntity.DATA_BUFFER_CAP_HI));
            int wireBuffered = MunitionsBenchBlockEntity.unpackHalves15(
                    (short) data.get(MunitionsBenchBlockEntity.DATA_BUFFERED_ROUNDS),
                    (short) data.get(MunitionsBenchBlockEntity.DATA_BUFFERED_ROUNDS_HI));
            helper.assertTrue(wireCap == 40_000, "buffer cap 40000 survives the int16 wire, got " + wireCap);
            helper.assertTrue(wireBuffered == 35_000,
                    "35000 buffered rounds survive the int16 wire, got " + wireBuffered);
            helper.assertTrue(menu.bufferCap() == 40_000 && menu.bufferedRounds() == 35_000,
                    "menu buffer accessors reassemble the halves, got " + menu.bufferedRounds() + " / "
                            + menu.bufferCap());

            // 配置上限 10,000,000 同样无损; 负值按 0, 超 30 位钳到 30 位上限而不是回绕。
            int big = 10_000_000;
            int lo = MunitionsBenchBlockEntity.lowHalf15(big);
            int hi = MunitionsBenchBlockEntity.highHalf15(big);
            helper.assertTrue(lo >= 0 && lo <= 0x7FFF && hi > 0 && hi <= 0x7FFF,
                    "10,000,000 splits into two 15-bit halves, got lo=" + lo + " hi=" + hi);
            helper.assertTrue(MunitionsBenchBlockEntity.unpackHalves15((short) lo, (short) hi) == big,
                    "10,000,000 round-trips through the int16 wire");
            helper.assertTrue(MunitionsBenchBlockEntity.lowHalf15(-5) == 0
                            && MunitionsBenchBlockEntity.highHalf15(-5) == 0,
                    "negative values sync as 0");
            helper.assertTrue(MunitionsBenchBlockEntity.unpackHalves15(
                            MunitionsBenchBlockEntity.lowHalf15(Integer.MAX_VALUE),
                            MunitionsBenchBlockEntity.highHalf15(Integer.MAX_VALUE)) == 0x3FFFFFFF,
                    "values above 30 bits clamp to the 30-bit maximum instead of wrapping");
            helper.succeed();
        } finally {
            MunitionsConfig.BUFFER_L1.set(originalBuffer);
            restoreJob(prevJob);
        }
    }

    // ============================================================
    // 单次 / 连续 的幂等"设为"按钮: 界面分段开关连点同一段时不能像切换那样被翻回去; 仍然只认台主。
    // ============================================================

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void benchSetContinuousIsIdempotentAndOwnerOnly(GameTestHelper helper) {
        ServerPlayer owner = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);
        ServerPlayer stranger = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);
        IJobService prevJob = swapJob(new FixedLevelJobService(5));
        try {
            MunitionsBenchBlockEntity be = newBench(helper, owner);
            MunitionsBenchMenu menu = openBenchMenu(be, owner);
            helper.assertFalse(menu.isContinuousCrafting(), "a fresh bench starts in single mode");

            // 连点两次"连续": 结果仍是连续 (切换按钮在这里会翻回单次)。
            helper.assertTrue(menu.clickMenuButton(owner, MunitionsBenchMenu.BUTTON_SET_CONTINUOUS),
                    "owner sets continuous");
            helper.assertTrue(menu.clickMenuButton(owner, MunitionsBenchMenu.BUTTON_SET_CONTINUOUS),
                    "repeating set-continuous is accepted");
            helper.assertTrue(menu.isContinuousCrafting(), "two set-continuous clicks leave the bench continuous");

            // 往返期间先点连续再点单次: 停在最后点的单次。
            helper.assertTrue(menu.clickMenuButton(owner, MunitionsBenchMenu.BUTTON_SET_SINGLE),
                    "owner sets single");
            helper.assertTrue(menu.clickMenuButton(owner, MunitionsBenchMenu.BUTTON_SET_SINGLE),
                    "repeating set-single is accepted");
            helper.assertFalse(menu.isContinuousCrafting(), "two set-single clicks leave the bench single");

            helper.assertFalse(be.setContinuousCrafting(stranger, true), "stranger cannot set continuous mode");
            helper.assertFalse(menu.isContinuousCrafting(), "rejected stranger call leaves the mode unchanged");
            helper.succeed();
        } finally {
            restoreJob(prevJob);
        }
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void benchSettleNoMaterialNoProduction(GameTestHelper helper) {
        ServerPlayer player = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);
        RecordingJobService job = new RecordingJobService(5);
        IJobService prevJob = swapJob(job);
        EconomyLedger ledger = SqliteEconomyLedger.openInMemory();
        IEconomyService prevEco = swapEconomy(freshEconomy(ledger));
        try {
            MunitionsBenchBlockEntity be = newBench(helper, player);
            be.trySelectCaliber(MunitionsCaliber.RIFLE, player);
            ledger.credit(player.getUUID(), Currency.CREDIT, 1000L);
            // 空料槽: 即便时间充裕也不产 (料门 0 批)。
            backdateSettleTick(be, helper, MunitionsProduction.ticksPerRound(5) * 100_000L);
            be.settleForOwner(player);

            helper.assertTrue(be.bufferedRounds() == 0, "no material -> no rounds buffered");
            helper.assertTrue(ledger.balance(player.getUUID(), Currency.CREDIT) == 1000L,
                    "no production means no work-fee sink (balance unchanged)");
            helper.assertTrue(job.grantXpCalls == 0, "no production means no xp grant");
            helper.succeed();
        } finally {
            restoreJob(prevJob);
            restoreEconomy(prevEco);
        }
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void doubleBlockBenchDropsExactlyOnce(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        MunitionsBenchBlock bench = (MunitionsBenchBlock) ModMunitionsBlocks.MUNITIONS_BENCH_HIGH.get();
        BlockPos mainPos = helper.absolutePos(new BlockPos(0, 1, 0));
        BlockState mainState = bench.defaultBlockState()
                .setValue(MunitionsBenchBlock.FACING, Direction.NORTH)
                .setValue(MunitionsBenchBlock.PART, MunitionsBenchBlock.Part.MAIN)
                .setValue(MunitionsBenchBlock.ACTIVE, false);
        BlockPos extensionPos = MunitionsBenchBlock.extensionPos(mainPos, mainState);

        // 进场先清一次: 取样盒在副格一侧比结构清场多探出一格, 上一轮停在盒里的同种掉落物不清掉会被数成本次拆出来的。
        clearBenchDrops(level, mainPos, extensionPos);
        try {
            placeBenchPair(level, mainPos, extensionPos, mainState);
            level.destroyBlock(extensionPos, true);
            assertBenchRemoved(helper, level, mainPos, extensionPos, "extension-first destruction");
            assertSingleBenchDrop(helper, level, mainPos, extensionPos, "extension-first destruction");
            clearBenchDrops(level, mainPos, extensionPos);

            placeBenchPair(level, mainPos, extensionPos, mainState);
            level.destroyBlock(mainPos, true);
            assertBenchRemoved(helper, level, mainPos, extensionPos, "main-first destruction");
            assertSingleBenchDrop(helper, level, mainPos, extensionPos, "main-first destruction");
            helper.succeed();
        } finally {
            // 第二次拆出来的那一个没人收, 留在原地就是下一轮同一格的残留。
            clearBenchDrops(level, mainPos, extensionPos);
        }
    }

    private static void placeBenchPair(ServerLevel level, BlockPos mainPos, BlockPos extensionPos,
                                       BlockState mainState) {
        level.setBlock(mainPos, mainState, Block.UPDATE_CLIENTS);
        level.setBlock(extensionPos,
                mainState.setValue(MunitionsBenchBlock.PART, MunitionsBenchBlock.Part.EXTENSION),
                Block.UPDATE_CLIENTS);
    }

    private static void assertBenchRemoved(GameTestHelper helper, ServerLevel level, BlockPos mainPos,
                                           BlockPos extensionPos, String path) {
        helper.assertTrue(level.getBlockState(mainPos).isAir() && level.getBlockState(extensionPos).isAir(),
                path + " must remove both bench blocks");
    }

    private static void assertSingleBenchDrop(GameTestHelper helper, ServerLevel level, BlockPos mainPos,
                                              BlockPos extensionPos, String path) {
        int count = benchDrops(level, mainPos, extensionPos).stream()
                .mapToInt(entity -> entity.getItem().getCount())
                .sum();
        helper.assertTrue(count == 1, path + " must drop exactly one bench item, got " + count);
    }

    private static void clearBenchDrops(ServerLevel level, BlockPos mainPos, BlockPos extensionPos) {
        benchDrops(level, mainPos, extensionPos).forEach(ItemEntity::discard);
    }

    private static List<ItemEntity> benchDrops(ServerLevel level, BlockPos mainPos, BlockPos extensionPos) {
        AABB bounds = new AABB(mainPos, extensionPos.offset(1, 1, 1)).inflate(2.0D);
        return level.getEntitiesOfClass(ItemEntity.class, bounds,
                entity -> entity.getItem().is(ModMunitionsItems.MUNITIONS_BENCH_HIGH_ITEM.get()));
    }

    // ============================================================
    // 手动制作路径 (双模式; 审查 M-1/M-2/M-3/M-5 回归)
    // ============================================================

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void manualCraftAtomicSettlement(GameTestHelper helper) {
        ServerPlayer player = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);
        RecordingJobService job = new RecordingJobService(5); // L5: 步枪直造 40/批。
        IJobService prevJob = swapJob(job);
        EconomyLedger ledger = SqliteEconomyLedger.openInMemory();
        IEconomyService prevEco = swapEconomy(freshEconomy(ledger));
        try {
            MunitionsBenchBlockEntity be = newBench(helper, player);
            helper.assertTrue(be.trySelectCaliber(MunitionsCaliber.RIFLE, player), "select RIFLE at L5");
            stockParts(be, 1);
            ledger.credit(player.getUUID(), Currency.CREDIT, 1000L);

            // 开工帧只校验不扣料 (M-2): 开工成功后四件套仍原样在槽。
            helper.assertTrue(be.tryStartCraft(player), "owner starts a manual craft");
            assertPartCounts(helper, be, 1);

            // 取消零损失 (M-2): 材料原样, 缓冲/余额分文不动。
            helper.assertTrue(be.cancelCraft(player), "owner cancels the running craft");
            assertPartCounts(helper, be, 1);
            helper.assertTrue(be.bufferedRounds() == 0, "cancel leaves buffer untouched");
            helper.assertTrue(ledger.balance(player.getUUID(), Currency.CREDIT) == 1000L,
                    "cancel charges nothing");

            // 完成帧原子结算: 重新开工, 回拨 craftingStartTick 使批已到期, settleForOwner 走手动完成分支
            // -> 扣料 + 入缓冲 + 工费 + 经验一次落账 (删 finishActiveCraft 的 consume/charge 任一环此组断言必挂)。
            helper.assertTrue(be.tryStartCraft(player), "restart the craft");
            backdateCraftStart(be, helper);
            be.settleForOwner(player);
            helper.assertTrue(be.bufferedRounds() == 40,
                    "finished manual batch buffers exactly 40 rounds, got " + be.bufferedRounds());
            assertPartCounts(helper, be, 0);
            long expectedBalance = 1000L - MunitionsProduction.workFee(40);
            helper.assertTrue(ledger.balance(player.getUUID(), Currency.CREDIT) == expectedBalance,
                    "work fee for 40 rounds charged exactly once on completion, got "
                            + ledger.balance(player.getUUID(), Currency.CREDIT));
            helper.assertTrue(job.lastRawXp == 40L, "raw xp equals produced rounds (40)");
            helper.succeed();
        } finally {
            restoreJob(prevJob);
            restoreEconomy(prevEco);
        }
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void manualCraftForfeitsWithoutMaterialsButKeepsThem(GameTestHelper helper) {
        ServerPlayer player = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);
        RecordingJobService job = new RecordingJobService(5);
        IJobService prevJob = swapJob(job);
        EconomyLedger ledger = SqliteEconomyLedger.openInMemory();
        IEconomyService prevEco = swapEconomy(freshEconomy(ledger));
        try {
            MunitionsBenchBlockEntity be = newBench(helper, player);
            be.trySelectCaliber(MunitionsCaliber.RIFLE, player);
            stockParts(be, 1);
            // 余额不足 40 发工费 (60 CP): 完成帧扣费失败 -> 本批作废, 但材料分文不扣 ("扣不动则料不扣")。
            ledger.credit(player.getUUID(), Currency.CREDIT, 10L);
            helper.assertTrue(be.tryStartCraft(player), "start with insufficient balance (charge deferred)");
            backdateCraftStart(be, helper);
            be.settleForOwner(player);
            helper.assertTrue(be.bufferedRounds() == 0, "failed fee forfeits the batch: nothing buffered");
            assertPartCounts(helper, be, 1);
            helper.assertTrue(ledger.balance(player.getUUID(), Currency.CREDIT) == 10L,
                    "failed charge leaves balance untouched");
            helper.assertFalse(be.saveWithoutMetadata().getBoolean("CraftingActive"),
                    "failed batch stops the machine (no continuous spin)");
            helper.succeed();
        } finally {
            restoreJob(prevJob);
            restoreEconomy(prevEco);
        }
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void manualCraftOwnerOnly(GameTestHelper helper) {
        ServerPlayer owner = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);
        ServerPlayer stranger = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);
        RecordingJobService job = new RecordingJobService(5);
        IJobService prevJob = swapJob(job);
        EconomyLedger ledger = SqliteEconomyLedger.openInMemory();
        IEconomyService prevEco = swapEconomy(freshEconomy(ledger));
        try {
            MunitionsBenchBlockEntity be = newBench(helper, owner);
            be.trySelectCaliber(MunitionsCaliber.RIFLE, owner);
            stockParts(be, 1);
            // 未上锁也不放行 (M-3): 开工/连续开关/取消全部限台主, 产量/工费/经验天然同源 owner。
            helper.assertFalse(be.tryStartCraft(stranger), "stranger cannot start on an unlocked bench");
            helper.assertFalse(be.toggleContinuousCrafting(stranger), "stranger cannot toggle continuous mode");
            helper.assertTrue(be.tryStartCraft(owner), "owner starts");
            helper.assertFalse(be.cancelCraft(stranger), "stranger cannot cancel the owner batch");
            helper.assertTrue(be.cancelCraft(owner), "owner cancels");
            helper.succeed();
        } finally {
            restoreJob(prevJob);
            restoreEconomy(prevEco);
        }
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void benchTierClampsEffectiveLevel(GameTestHelper helper) {
        ServerPlayer player = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);
        RecordingJobService job = new RecordingJobService(6); // L6: 狙击解锁线。
        IJobService prevJob = swapJob(job);
        EconomyLedger ledger = SqliteEconomyLedger.openInMemory();
        IEconomyService prevEco = swapEconomy(freshEconomy(ledger));
        try {
            // MEDIUM 台 maxEffectiveLevel=4: L6 玩家被钳到 L4, SNIPER (L6 门) 拒选; RIFLE (L3 门) 放行。
            // 删 effectiveLevelFor 的 Math.min 钳制, SNIPER 断言必挂。
            MunitionsBenchBlockEntity medium =
                    newBench(helper, player, ModMunitionsBlocks.MUNITIONS_BENCH_MEDIUM.get());
            helper.assertFalse(medium.trySelectCaliber(MunitionsCaliber.SNIPER, player),
                    "medium bench clamps L6 owner to L4: sniper rejected");
            helper.assertTrue(medium.trySelectCaliber(MunitionsCaliber.RIFLE, player),
                    "rifle (L3 gate) still selectable under the clamp");
            // 旧注册名恢复全档 (M-5 存量兼容): 同一 L6 玩家在旧台 SNIPER 放行 (回归降档必挂)。
            MunitionsBenchBlockEntity legacy =
                    newBench(helper, player, ModMunitionsBlocks.MUNITIONS_BENCH.get());
            helper.assertTrue(legacy.trySelectCaliber(MunitionsCaliber.SNIPER, player),
                    "legacy munitions_bench keeps full capability for existing benches");
            helper.succeed();
        } finally {
            restoreJob(prevJob);
            restoreEconomy(prevEco);
        }
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void quickMoveNeverMergesIntoOutputSlot(GameTestHelper helper) {
        ServerPlayer player = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);
        RecordingJobService job = new RecordingJobService(5);
        IJobService prevJob = swapJob(job);
        try {
            MunitionsBenchBlockEntity be = newBench(helper, player);
            MunitionsBenchMenu menu = openBenchMenu(be, player);
            // 构造 M-7 场景 (注入放在开 menu 之后, 防 onAccess 的输出刷新覆盖注入): 输出槽注入与玩家手中
            // 同种的物品 (测试注入绕过 mayPlace), 料槽占满 -> vanilla moveItemStackTo 的合并分支若目标区间含
            // 输出槽, 会把玩家的 8 个并进输出槽 (随后被 refreshOutputStack 覆盖销毁)。修复后目标区间止步
            // 输出槽, 输出槽数量必须保持 8。
            be.inventory().setStackInSlot(MunitionsBenchBlockEntity.SLOT_OUTPUT,
                    new ItemStack(ModMunitionsItems.PRIMER.get(), 8));
            be.inventory().setStackInSlot(MunitionsBenchBlockEntity.SLOT_PRIMER,
                    new ItemStack(ModMunitionsItems.PRIMER.get(), 64));
            player.getInventory().setItem(0, new ItemStack(ModMunitionsItems.PRIMER.get(), 8));
            int playerSlotIndex = -1;
            for (int i = 5; i < menu.slots.size(); i++) {
                if (menu.slots.get(i).getItem().is(ModMunitionsItems.PRIMER.get())) {
                    playerSlotIndex = i;
                    break;
                }
            }
            helper.assertTrue(playerSlotIndex >= 0, "player primer stack visible in menu");
            menu.quickMoveStack(player, playerSlotIndex);
            helper.assertTrue(be.inventory().getStackInSlot(MunitionsBenchBlockEntity.SLOT_OUTPUT)
                            .getCount() == 8,
                    "shift-clicked primers must NOT merge into the output slot (kept 8), got "
                            + be.inventory().getStackInSlot(MunitionsBenchBlockEntity.SLOT_OUTPUT).getCount());
            helper.succeed();
        } finally {
            restoreJob(prevJob);
        }
    }

    // ============================================================
    // Full_Repo_Audit_2026-08 补测: F001 输出槽分栈/写入不变量, F009 台数按台主回收, F010 选口径限台主,
    // F015 4->5 槽迁移, F049 被动产线驱动 ACTIVE, F051 反漏斗只写
    // ============================================================

    /**
     * F001 (Critical): 输出槽必须按 TACZ 弹自己的单栈上限分栈, 不能把 480 发权威缓冲整坨怼进一个栈。
     * dev 无 TACZ, 默认 materializer 恒返 EMPTY, 结构上测不到这条 —— 注入返回原版物品 (真实 getMaxStackSize)
     * 的替身撬开这条盲区 (compileOnly 铁律不破: 替身只返回原版 ItemStack, 不触 com.tacz.*)。
     *
     * 删 refreshOutputStack 里的 Math.min 分栈 -> 输出槽 count 会变成 480, 第一条 ==64 的断言必挂。
     */
    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void benchOutputStackSplitsToItemMaxThenRefillsAfterPartialTake(GameTestHelper helper) {
        ServerPlayer player = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);
        MunitionsBenchBlockEntity be = newBench(helper, player);
        try {
            MunitionsAmmoFactory.registerMaterializer((caliber, count) ->
                    new ItemStack(ModMunitionsItems.PRIMER.get(), count));

            seedBuffer(be, MunitionsCaliber.RIFLE, 480);
            be.onAccess(player);

            ItemStack output = be.inventory().getStackInSlot(MunitionsBenchBlockEntity.SLOT_OUTPUT);
            helper.assertTrue(output.getCount() == 64,
                    "output slot splits to the item's own stack limit (64), not the full 480 buffered, got "
                            + output.getCount());
            helper.assertTrue(output.is(ModMunitionsItems.PRIMER.get()), "output slot holds the materialized item");
            helper.assertTrue(be.bufferedRounds() == 480,
                    "authoritative bufferedRounds is untouched by the visual split, got " + be.bufferedRounds());

            MunitionsBenchMenu menu = openBenchMenu(be, player);
            ItemStack moved = menu.quickMoveStack(player, MunitionsBenchBlockEntity.SLOT_OUTPUT);

            helper.assertTrue(moved.getCount() == 64,
                    "shift-take moved the full 64-count visualized stack, got " + moved.getCount());
            helper.assertTrue(be.bufferedRounds() == 416,
                    "taking 64 of 480 buffered rounds leaves exactly 416, got " + be.bufferedRounds());
            helper.assertTrue(be.inventory().getStackInSlot(MunitionsBenchBlockEntity.SLOT_OUTPUT).getCount() == 64,
                    "output slot is refilled to the item max (64) out of the remaining 416-round buffer, got "
                            + be.inventory().getStackInSlot(MunitionsBenchBlockEntity.SLOT_OUTPUT).getCount());
            helper.succeed();
        } finally {
            MunitionsAmmoFactory.resetMaterializer();
        }
    }

    /**
     * 复核 (blocker, 取代原 benchLegacyMigrationRejectsUnknownSlotCountAndOversizedOutputWrite): 旧版对"未知
     * 槽数"与"输出槽超栈"两种损坏都直接在 load() 里 throw, 而 BlockEntity.loadStatic 会把 load() 抛出的
     * Throwable 吞掉并丢弃整台 BE (forge-1.20.1-47.4.20 sources 核实: catch Throwable 后 return null, 之后
     * getBlockEntity(CHECK) 不会补建) —— owner/缓冲/四格料全部消失, 比它原本要防的"抹平库存"更狠, 且旧版
     * 测试只验证了"抛了", 没验证"抛完这台还在不在", 把这个回归锁成了契约。改为两种损坏都不再抛: 未知槽数把
     * 原内容进待掉落队列 + 重置库存形状; 超栈输出槽按物品自报上限落一栈、余量进待掉落队列。
     *
     * 断言链: (1) load() 不抛; (2) owner 在两种损坏后都还在 (证明 BE 没被 Forge 整体丢弃); (3) 未知槽数场景
     * 库存重置为空的当前形状, 原有的 1 个铁锭堆叠经首个 serverTick 精确吐成掉落物; (4) 超栈场景输出槽被钳到
     * 底火单栈上限 64, 溢出的 36 发经首个 serverTick 精确吐成掉落物。
     *
     * 删 migrateInventoryShape 的未知槽数分支或 settleLegacyOutputStack 的钳位逻辑 (退回 throw / 不钳直接落槽)
     * -> 对应断言必挂 (要么 load() 重新抛出, 要么输出槽出现超过 64 的堆叠)。
     */
    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void benchLegacyMigrationDegradesInsteadOfDiscardingTheWholeBlockEntity(GameTestHelper helper) {
        ServerPlayer player = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);

        // 未知槽数 (3, 既非当前 5 槽也非旧档 4 槽) 必须保留 BE (owner 还在), 原内容进待掉落队列而不是抛异常丢整台。
        MunitionsBenchBlockEntity beUnknownSize = newBench(helper, player);
        UUID unknownSizeOwner = beUnknownSize.owner();
        ItemStackHandler unknownSizeFixture = new ItemStackHandler(3);
        unknownSizeFixture.setStackInSlot(0, new ItemStack(Items.IRON_INGOT, 5));
        net.minecraft.nbt.CompoundTag unknownSizeTag = beUnknownSize.saveWithoutMetadata();
        unknownSizeTag.put("Inv", unknownSizeFixture.serializeNBT());
        beUnknownSize.load(unknownSizeTag); // 必须不抛。
        helper.assertTrue(unknownSizeOwner != null && unknownSizeOwner.equals(beUnknownSize.owner()),
                "unknown slot count must not wipe the owner (BE must survive, not get discarded by loadStatic)");
        helper.assertTrue(
                beUnknownSize.inventory().getStackInSlot(MunitionsBenchBlockEntity.SLOT_PRIMER).isEmpty()
                        && beUnknownSize.inventory().getStackInSlot(MunitionsBenchBlockEntity.SLOT_CASING).isEmpty()
                        && beUnknownSize.inventory().getStackInSlot(MunitionsBenchBlockEntity.SLOT_BULLET_HEAD)
                                .isEmpty()
                        && beUnknownSize.inventory().getStackInSlot(MunitionsBenchBlockEntity.SLOT_PROPELLANT)
                                .isEmpty()
                        && beUnknownSize.inventory().getStackInSlot(MunitionsBenchBlockEntity.SLOT_OUTPUT).isEmpty(),
                "inventory shape must be reset to empty current-layout slots after an unknown slot count");
        // 只认本用例吐出来的掉落物: 盒里可能躺着上一轮的残留 (清场那一刻该区块的实体还没读完盘就漏掉了), 也可能是
        // 别的用例留下的, 按绝对数量数会把它们算进去。两段场景的台子在同一个绝对坐标, 取样盒相同, 共用这一条基线。
        AABB dropBox = new AABB(beUnknownSize.getBlockPos()).inflate(2.0D);
        EntityBaseline<ItemEntity> dropBaseline =
                EntityBaseline.capture(helper.getLevel(), ItemEntity.class, dropBox);
        try {
            beUnknownSize.serverTick();
            List<ItemEntity> unknownSizeDrops = dropBaseline.fresh();
            helper.assertTrue(unknownSizeDrops.size() == 1 && unknownSizeDrops.get(0).getItem().getCount() == 5
                            && unknownSizeDrops.get(0).getItem().is(Items.IRON_INGOT),
                    "the unknown-shape inventory's only stack (5 iron ingots) must be queued for drop, not vanished");
            // newBench 固定用 (0,1,0), 第二段场景的方块会摆在同一个绝对坐标: 先把这批掉落物清场, 不然第二段的
            // dropBox 查询 (同一位置) 会把这批铁锭也算进去, 误判超栈溢出份数。
            unknownSizeDrops.forEach(net.minecraft.world.entity.Entity::discard);

            // 旧档输出槽超栈 (100 > 底火单栈上限 64) 必须按上限落槽 + 余量排队掉落, 不能在 load() 里抛出丢整台。
            MunitionsBenchBlockEntity beOversizedOutput = newBench(helper, player);
            UUID oversizedOutputOwner = beOversizedOutput.owner();
            ItemStackHandler oversizedOutputFixture = new ItemStackHandler(4);
            oversizedOutputFixture.setStackInSlot(3, new ItemStack(ModMunitionsItems.PRIMER.get(), 100));
            net.minecraft.nbt.CompoundTag oversizedOutputTag = beOversizedOutput.saveWithoutMetadata();
            oversizedOutputTag.put("Inv", oversizedOutputFixture.serializeNBT());
            beOversizedOutput.load(oversizedOutputTag); // 必须不抛。
            helper.assertTrue(oversizedOutputOwner != null && oversizedOutputOwner.equals(beOversizedOutput.owner()),
                    "an oversized legacy output stack must not wipe the owner (BE must survive)");
            ItemStack settledOutput =
                    beOversizedOutput.inventory().getStackInSlot(MunitionsBenchBlockEntity.SLOT_OUTPUT);
            helper.assertTrue(settledOutput.is(ModMunitionsItems.PRIMER.get()) && settledOutput.getCount() == 64,
                    "legacy output is clamped to the item's own stack limit (64), got " + settledOutput);
            beOversizedOutput.serverTick();
            List<ItemEntity> oversizedOutputDrops = dropBaseline.fresh();
            int overflowTotal = 0;
            for (ItemEntity entity : oversizedOutputDrops) {
                helper.assertTrue(entity.getItem().is(ModMunitionsItems.PRIMER.get()),
                        "unexpected overflow drop item " + entity.getItem());
                overflowTotal += entity.getItem().getCount();
            }
            helper.assertTrue(overflowTotal == 36,
                    "the 36-round overflow (100 - 64 clamped) must be queued for drop, got " + overflowTotal);
            helper.succeed();
        } finally {
            // 第二段吐出来的 36 发底火 (以及断言中途失败时还没清的铁锭) 不收走, 就是下一轮同一格的残留。
            dropBaseline.discardFresh();
        }
    }

    /**
     * F015 (4->5 槽迁移): 旧档发射药/输出原样搬到新槽位, 类型未知的 legacy 0/1 进待掉落队列而不是被静默销毁,
     * 首个 serverTick 把队列精确吐成两件世界掉落物 (逐件核对物品与数量, 不只数个数)。
     *
     * 删掉待掉落队列 (migrateLegacyFourSlot 里 pendingLegacyDrops.add 那两处) -> 掉落物断言 (==2) 必挂。
     */
    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void benchLegacyFourSlotMigrationRestoresPropellantAndOutputThenDropsTheRest(GameTestHelper helper) {
        ServerPlayer player = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);
        MunitionsBenchBlockEntity be = newBench(helper, player);

        ItemStackHandler legacy = new ItemStackHandler(4);
        legacy.setStackInSlot(0, new ItemStack(Items.COPPER_INGOT, 7));
        legacy.setStackInSlot(1, new ItemStack(Items.GUNPOWDER, 16));
        legacy.setStackInSlot(2, new ItemStack(ModMunitionsItems.PROPELLANT.get(), 5));
        legacy.setStackInSlot(3, new ItemStack(ModMunitionsItems.PRIMER.get(), 3));
        net.minecraft.nbt.CompoundTag tag = be.saveWithoutMetadata();
        tag.put("Inv", legacy.serializeNBT());
        be.load(tag);

        ItemStack propellant = be.inventory().getStackInSlot(MunitionsBenchBlockEntity.SLOT_PROPELLANT);
        helper.assertTrue(propellant.is(ModMunitionsItems.PROPELLANT.get()) && propellant.getCount() == 5,
                "legacy slot 2 (propellant) migrates to the new SLOT_PROPELLANT unchanged (5), got " + propellant);
        ItemStack output = be.inventory().getStackInSlot(MunitionsBenchBlockEntity.SLOT_OUTPUT);
        helper.assertTrue(output.is(ModMunitionsItems.PRIMER.get()) && output.getCount() == 3,
                "legacy slot 3 (old output) migrates to the new SLOT_OUTPUT unchanged (3), got " + output);
        helper.assertTrue(be.inventory().getStackInSlot(MunitionsBenchBlockEntity.SLOT_PRIMER).isEmpty()
                        && be.inventory().getStackInSlot(MunitionsBenchBlockEntity.SLOT_CASING).isEmpty()
                        && be.inventory().getStackInSlot(MunitionsBenchBlockEntity.SLOT_BULLET_HEAD).isEmpty(),
                "type-unknown legacy slots 0/1 (copper/gunpowder) must NOT leak into any new input slot");

        // 只认这次冲出来的掉落物: 盒里可能躺着上一轮的残留 (清场那一刻该区块的实体还没读完盘就漏掉了), 也可能是
        // 别的用例留下的, 按绝对数量数会把它们算进去。
        AABB dropBox = new AABB(be.getBlockPos()).inflate(2.0D);
        EntityBaseline<ItemEntity> dropBaseline =
                EntityBaseline.capture(helper.getLevel(), ItemEntity.class, dropBox);
        try {
            be.serverTick(); // 冲掉待掉落队列。

            List<ItemEntity> drops = dropBaseline.fresh();
            helper.assertTrue(drops.size() == 2,
                    "exactly 2 legacy stacks (copper ingot + gunpowder) are queued for drop, got " + drops.size());
            int copperCount = 0;
            int gunpowderCount = 0;
            for (ItemEntity entity : drops) {
                ItemStack stack = entity.getItem();
                if (stack.is(Items.COPPER_INGOT)) {
                    copperCount += stack.getCount();
                } else if (stack.is(Items.GUNPOWDER)) {
                    gunpowderCount += stack.getCount();
                } else {
                    helper.fail("unexpected legacy drop item " + stack);
                }
            }
            helper.assertTrue(copperCount == 7, "dropped copper ingots must total exactly 7, got " + copperCount);
            helper.assertTrue(gunpowderCount == 16, "dropped gunpowder must total exactly 16, got " + gunpowderCount);
            helper.succeed();
        } finally {
            // 吐出来的铜锭和火药不收走, 就是下一轮同一格的残留。
            dropBaseline.discardFresh();
        }
    }

    /**
     * F049: 被动产线 (非手动开工) 也必须驱动方块 ACTIVE 状态。备 2 批料, 只回拨 1 批的时间 (时间瓶颈), 结算后
     * 恰产 1 批、还剩 1 批料且缓冲有空间 -> 机器仍要亮灯。清空四个料槽再结算一次 -> 无料可产, 机器必须灭灯。
     *
     * 删 canAccumulateProduction 的新判据 (退回旧的直接复用 craftingActive) -> 被动产线从不置位
     * craftingActive, canAccumulateProduction 恒 false, 第一条 ACTIVE==true 断言必挂。
     */
    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void passiveProductionDrivesActiveBlockState(GameTestHelper helper) {
        ServerPlayer player = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);
        IJobService prevJob = swapJob(new FixedLevelJobService(5));
        EconomyLedger ledger = SqliteEconomyLedger.openInMemory();
        IEconomyService prevEco = swapEconomy(freshEconomy(ledger));
        try {
            MunitionsBenchBlockEntity be = newBench(helper, player);
            be.trySelectCaliber(MunitionsCaliber.RIFLE, player);
            stockParts(be, 2);
            ledger.credit(player.getUUID(), Currency.CREDIT, 1000L);

            int perBatch = MunitionsProduction.roundsPerBatch(MunitionsCaliber.RIFLE, 5);
            long oneBatchTime = MunitionsProduction.ticksPerRound(5) * (long) perBatch;
            backdateSettleTick(be, helper, oneBatchTime);
            be.settleForOwner(player);

            helper.assertTrue(be.bufferedRounds() == perBatch,
                    "sanity: exactly one batch produced (time-clamped), got " + be.bufferedRounds());
            assertPartCounts(helper, be, 1);
            BlockPos benchPos = be.getBlockPos();
            helper.assertTrue(helper.getLevel().getBlockState(benchPos).getValue(MunitionsBenchBlock.ACTIVE),
                    "bench with remaining material and buffer room must be ACTIVE after a productive settle");

            be.inventory().setStackInSlot(MunitionsBenchBlockEntity.SLOT_PRIMER, ItemStack.EMPTY);
            be.inventory().setStackInSlot(MunitionsBenchBlockEntity.SLOT_CASING, ItemStack.EMPTY);
            be.inventory().setStackInSlot(MunitionsBenchBlockEntity.SLOT_BULLET_HEAD, ItemStack.EMPTY);
            be.inventory().setStackInSlot(MunitionsBenchBlockEntity.SLOT_PROPELLANT, ItemStack.EMPTY);
            backdateSettleTick(be, helper, 1L);
            be.settleForOwner(player);
            helper.assertFalse(helper.getLevel().getBlockState(benchPos).getValue(MunitionsBenchBlock.ACTIVE),
                    "bench with no material left must go inactive (ACTIVE=false) after settling");
            helper.succeed();
        } finally {
            restoreJob(prevJob);
            restoreEconomy(prevEco);
        }
    }

    /**
     * 复核 (major, F049 同源): 台主持续在线时 serverTick 每 tick 都追算一次, elapsed 恒为 1 tick, 单 tick 换算
     * 的理论发数不够一整批。旧判据 (canAccumulateProduction 的料/缓冲/口径三道静态门) 在这种"料齐但流逝时间
     * 不够" 的状态下恒真, 会把机器点成常亮却一发都出不来。本用例只回拨 1 tick (远不够一整批), 断言这个真实
     * 的"在线空转"状态下 ACTIVE 必须是 false、且确实没有任何产出。
     *
     * 退回旧判据 (canAccumulateProduction 而非 result.produced() 驱动 active) -> ACTIVE 断言必挂。
     */
    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void passiveProductionStaysInactiveWhenElapsedTimeIsTooShortForABatch(GameTestHelper helper) {
        ServerPlayer player = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);
        IJobService prevJob = swapJob(new FixedLevelJobService(5));
        EconomyLedger ledger = SqliteEconomyLedger.openInMemory();
        IEconomyService prevEco = swapEconomy(freshEconomy(ledger));
        try {
            MunitionsBenchBlockEntity be = newBench(helper, player);
            be.trySelectCaliber(MunitionsCaliber.RIFLE, player);
            stockParts(be, 2);
            ledger.credit(player.getUUID(), Currency.CREDIT, 1000L);

            // 料/缓冲/口径三道静态门全过, 但只给 1 tick 流逝 (远不够一整批所需的上万 tick) —— 这正是台主持续
            // 在线时 serverTick 每 tick 都会遇到的真实状态。
            backdateSettleTick(be, helper, 1L);
            be.settleForOwner(player);

            helper.assertTrue(be.bufferedRounds() == 0,
                    "sanity: 1 elapsed tick cannot complete a batch, got " + be.bufferedRounds());
            assertPartCounts(helper, be, 2);
            BlockPos benchPos = be.getBlockPos();
            helper.assertFalse(helper.getLevel().getBlockState(benchPos).getValue(MunitionsBenchBlock.ACTIVE),
                    "bench with materials ready but insufficient elapsed time must stay inactive, "
                            + "not falsely light up as if producing");
            helper.succeed();
        } finally {
            restoreJob(prevJob);
            restoreEconomy(prevEco);
        }
    }

    /**
     * 复核 (major, F049 同源): 扣费失败分支曾经每次都清零焊接音节流 (当年的随机计时器) 并 {@code setMachineActive(false)},
     * 而工费失败不推进 lastSettleTick (先查后扣, 扣不动则本批作废但保留流逝窗口), 于是台主持续在线、始终缺钱时,
     * 每 tick 都会重演 "重新点亮 -> 立刻拉黑" 的循环, 音效节流被清零后每 tick 都重新达标, 造成音效轰炸 + 方块
     * 更新包风暴 (现在的冲压音跟着运动程序走, 每 tick 重新点亮则程序每 tick 从头来、永远到不了冲压点)。本用例模拟连续两次 "料/缓冲/时间都够, 唯独差信用点" 的结算, 断言 ACTIVE 在两次失败结算之间
     * 保持不变 (不闪烁), 且材料/缓冲分文不动 (扣不动则本批真的作废); 随后补上信用点, 断言产出最终按
     * "下次再追" 的契约正常完成, 证明这不是简单粗暴地让失败分支永久生效, 而是保留了正确的重试语义。
     *
     * 删掉本轮修复 (在扣费失败分支重新加回 setMachineActive(false)) -> 第二次结算后的
     * ACTIVE 稳定性断言必挂 (会先被拉黑, 与第一次的 ACTIVE=true 矛盾, 断言即为"两次读到的状态相同"因而失败)。
     */
    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void passiveProductionKeepsActiveStableAcrossRepeatedFeeFailures(GameTestHelper helper) {
        ServerPlayer player = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);
        IJobService prevJob = swapJob(new FixedLevelJobService(5));
        EconomyLedger ledger = SqliteEconomyLedger.openInMemory();
        IEconomyService prevEco = swapEconomy(freshEconomy(ledger)); // 玩家账户 0 CP, 工费必扣不动。
        try {
            MunitionsBenchBlockEntity be = newBench(helper, player);
            be.trySelectCaliber(MunitionsCaliber.RIFLE, player);
            stockParts(be, 3);

            int perBatch = MunitionsProduction.roundsPerBatch(MunitionsCaliber.RIFLE, 5);
            long oneBatchTime = MunitionsProduction.ticksPerRound(5) * (long) perBatch;
            BlockPos benchPos = be.getBlockPos();

            backdateSettleTick(be, helper, oneBatchTime);
            be.settleForOwner(player);
            helper.assertTrue(be.bufferedRounds() == 0,
                    "sanity: no CP means the fee charge fails, nothing produced yet, got " + be.bufferedRounds());
            assertPartCounts(helper, be, 3);
            boolean activeAfterFirstFailure =
                    helper.getLevel().getBlockState(benchPos).getValue(MunitionsBenchBlock.ACTIVE);
            helper.assertTrue(activeAfterFirstFailure,
                    "a settle that is only blocked on the CP fee (material/buffer/time all satisfied) must stay lit");

            // 再来一次"缺钱失败", 模拟台主持续在线时下一 tick 重演同样的判定 (材料/缓冲/时间照常满足)。
            backdateSettleTick(be, helper, oneBatchTime);
            be.settleForOwner(player);
            helper.assertTrue(be.bufferedRounds() == 0, "still no CP: still nothing produced");
            assertPartCounts(helper, be, 3);
            boolean activeAfterSecondFailure =
                    helper.getLevel().getBlockState(benchPos).getValue(MunitionsBenchBlock.ACTIVE);
            helper.assertTrue(activeAfterSecondFailure == activeAfterFirstFailure,
                    "ACTIVE must not flicker between repeated fee failures (was " + activeAfterFirstFailure
                            + ", now " + activeAfterSecondFailure + ")");

            // 补上信用点, 证明 "扣不动则本批作废, 下次再追" 的契约仍然成立 —— 不是把失败分支焊死成永久生效。
            ledger.credit(player.getUUID(), Currency.CREDIT, 1000L);
            backdateSettleTick(be, helper, oneBatchTime);
            be.settleForOwner(player);
            helper.assertTrue(be.bufferedRounds() == perBatch,
                    "once CP is available, the retained production window must still complete, got "
                            + be.bufferedRounds());
            assertPartCounts(helper, be, 2);
            helper.succeed();
        } finally {
            restoreJob(prevJob);
            restoreEconomy(prevEco);
        }
    }

    /**
     * F051: getCapability(ITEM_HANDLER) 只暴露反漏斗包装 —— 漏斗那种只走 extractItem/insertItem 的调用方,
     * extract 恒空 (反抽不走), insert 照常成功 (塞料不受影响)。
     *
     * 删 InsertOnlyRangedWrapper 对 extractItem 的覆写 (RangedWrapper 恢复默认双向代理) -> extractItem 会真的
     * 抽走底火, 第一条 isEmpty() 断言必挂。
     */
    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void inputCapabilityIsInsertOnlyAgainstHoppers(GameTestHelper helper) {
        ServerPlayer player = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);
        MunitionsBenchBlockEntity be = newBench(helper, player);
        be.inventory().setStackInSlot(MunitionsBenchBlockEntity.SLOT_PRIMER,
                new ItemStack(ModMunitionsItems.PRIMER.get(), 8));

        IItemHandler h = be.getCapability(ForgeCapabilities.ITEM_HANDLER, null)
                .orElseThrow(() -> new IllegalStateException("munitions bench must expose an ITEM_HANDLER capability"));

        ItemStack extracted = h.extractItem(0, 64, false);
        helper.assertTrue(extracted.isEmpty(),
                "hopper-side extractItem must be blocked (anti-hopper), got " + extracted.getCount() + " extracted");
        helper.assertTrue(be.inventory().getStackInSlot(MunitionsBenchBlockEntity.SLOT_PRIMER).getCount() == 8,
                "primer count must be untouched by the blocked extraction, got "
                        + be.inventory().getStackInSlot(MunitionsBenchBlockEntity.SLOT_PRIMER).getCount());

        ItemStack insertLeftover = h.insertItem(0, new ItemStack(ModMunitionsItems.PRIMER.get(), 4), false);
        helper.assertTrue(insertLeftover.isEmpty(),
                "hopper-side insertItem into the primer slot must succeed, leftover=" + insertLeftover.getCount());
        helper.assertTrue(be.inventory().getStackInSlot(MunitionsBenchBlockEntity.SLOT_PRIMER).getCount() == 12,
                "primer count after inserting 4 more must be 12, got "
                        + be.inventory().getStackInSlot(MunitionsBenchBlockEntity.SLOT_PRIMER).getCount());
        helper.succeed();
    }

    /**
     * F010: 选口径服务端权威归属门 —— 路人不能改台主的选中口径, 也不能借这一次点击把台主攒下的离线时间窗抹掉
     * (trySelectCaliber 接受时会把 lastSettleTick 推到 now, 拒绝路径绝不能有这个副作用)。
     *
     * 删 trySelectCaliber 里的 isOwner 门 -> 路人的 PISTOL 请求会被接受, 时间戳被推进到 now、selectedCaliber
     * 被改成 PISTOL, 前两条断言必挂。
     */
    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void trySelectCaliberRejectsNonOwnerAndPreservesSettleWindow(GameTestHelper helper) {
        ServerPlayer owner = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);
        ServerPlayer stranger = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);
        IJobService prevJob = swapJob(new FixedLevelJobService(5));
        EconomyLedger ledger = SqliteEconomyLedger.openInMemory();
        IEconomyService prevEco = swapEconomy(freshEconomy(ledger));
        try {
            MunitionsBenchBlockEntity be = newBench(helper, owner);
            helper.assertTrue(be.trySelectCaliber(MunitionsCaliber.RIFLE, owner), "owner selects RIFLE at L5");

            int perBatch = MunitionsProduction.roundsPerBatch(MunitionsCaliber.RIFLE, 5);
            long window = MunitionsProduction.ticksPerRound(5) * (long) perBatch; // 恰好一批的流逝量。
            backdateSettleTick(be, helper, window);
            long settleTickAfterBackdate = readSettleTick(be);

            helper.assertFalse(be.trySelectCaliber(MunitionsCaliber.PISTOL, stranger),
                    "a stranger cannot switch caliber on someone else's bench");
            helper.assertTrue(readSettleTick(be) == settleTickAfterBackdate,
                    "rejected stranger selection must NOT touch lastSettleTick (owner's offline window preserved), "
                            + "expected " + settleTickAfterBackdate + " got " + readSettleTick(be));
            helper.assertTrue(be.selectedCaliber() == MunitionsCaliber.RIFLE,
                    "rejected stranger selection leaves RIFLE selected (not PISTOL, not null)");

            // 台主自己结算一次: 证明整段回拨窗口确实还在, 没被路人那次被拒的调用抹掉。
            stockParts(be, 1);
            ledger.credit(owner.getUUID(), Currency.CREDIT, 1000L);
            be.settleForOwner(owner);
            helper.assertTrue(be.bufferedRounds() == perBatch,
                    "owner settle after the rejected stranger call still catches up the full retained window ("
                            + perBatch + " rounds), got " + be.bufferedRounds());
            helper.succeed();
        } finally {
            restoreJob(prevJob);
            restoreEconomy(prevEco);
        }
    }

    /**
     * F009: 台数计数的回收必须按台主 (BE 持有的 owner), 而不是破坏者。用 level.destroyBlock (不带玩家上下文)
     * 覆盖爆炸/指令这类没有 PlayerInteractEvent/BreakEvent 的破坏路径。
     *
     * 删 MunitionsBenchBlock.onRemove 里的回收逻辑 -> owner 的计数在破坏后仍是 1, 第一条 ==0 断言必挂。
     */
    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void benchRemovalRecoversCountForOwnerNotBreaker(GameTestHelper helper) {
        ServerPlayer owner = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);
        ServerPlayer breaker = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);
        MunitionsSavedData savedData = MunitionsSavedData.get(owner.server.overworld());
        try {
            helper.assertTrue(savedData.increment(owner.getUUID()) == 1, "seed: owner placed 1 bench");

            MunitionsBenchBlockEntity be = newBench(helper, owner);
            BlockPos benchPos = be.getBlockPos();
            helper.getLevel().destroyBlock(benchPos, false);

            helper.assertTrue(savedData.benchCount(owner.getUUID()) == 0,
                    "destroying the bench through a no-player path (explosion/command) still recovers the owner's "
                            + "count to 0, got " + savedData.benchCount(owner.getUUID()));
            helper.assertTrue(savedData.benchCount(breaker.getUUID()) == 0,
                    "the breaker is not the owner and placed nothing, so their count stays 0, got "
                            + savedData.benchCount(breaker.getUUID()));
            helper.succeed();
        } finally {
            // decrement 天然钳零 (见 MunitionsSavedData), 无论断言前半程 onRemove 是否已经归零, 这里都安全地
            // 把种子计数收干净, 不给同批次其它用例留下污染的 SavedData。
            savedData.decrement(owner.getUUID());
        }
    }

    /**
     * 复核 (blocker, F009 同源): 撞放置上限被 EntityMultiPlaceEvent 取消后, ForgeHooks.onPlaceItemIntoWorld
     * 会把已捕获的两半快照 restore 回 AIR (level.restoringBlockSnapshots=true 期间), 触发的正是
     * MunitionsBenchBlock.onRemove 同一条路径 —— 但这次放置从未 increment 过 (event 在 increment 之前就
     * setCanceled)。若 onRemove 照常 decrement, 每撞一次上限就能白送自己一格额度, 可无限刷台。
     *
     * 用 restoringBlockSnapshots 直接驱动 (对齐 vanilla Block.popResource/popExperience 同一套跳过纪律的验证
     * 手法), 不必手搭一整条 BlockItem.place 流水线: 先用真实 increment 记一台已放置的台, 再在
     * restoringBlockSnapshots=true 期间移除它 (模拟快照回滚), 断言计数不受影响; 随后在正常状态下 (标志位为
     * false) 移除同一台, 断言计数照常回收, 证明新增的判据只挡快照回滚这一种场景, 不影响真实破坏路径。
     *
     * 删 onRemove 里新加的 {@code !level.restoringBlockSnapshots} 判据 -> 第一条 (回滚期不扣) 断言必挂。
     */
    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void benchRemovalDuringSnapshotRestoreDoesNotRecoverCount(GameTestHelper helper) {
        ServerPlayer owner = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);
        MunitionsSavedData savedData = MunitionsSavedData.get(owner.server.overworld());
        try {
            helper.assertTrue(savedData.increment(owner.getUUID()) == 1,
                    "seed: owner has 1 genuinely counted placement (mirrors a real MunitionsSystem.onBenchPlace)");

            MunitionsBenchBlockEntity be = newBench(helper, owner);
            BlockPos benchPos = be.getBlockPos();
            net.minecraft.world.level.Level level = helper.getLevel();

            // 模拟 ForgeHooks.onPlaceItemIntoWorld 取消放置后回滚快照的那个窗口: 此时的 setBlock(AIR) 与真实
            // 破坏走的是同一个 LevelChunk.setBlockState -> oldState.onRemove 路径。
            // 标志位挂在全进程共用的主世界上: setBlock 一旦抛出而没放回, 此后所有用例的方块掉落与台数回收都会被跳过,
            // 所以记下进来时的值, 在 finally 里原样放回。
            boolean wasRestoringSnapshots = level.restoringBlockSnapshots;
            level.restoringBlockSnapshots = true;
            try {
                level.setBlock(benchPos, net.minecraft.world.level.block.Blocks.AIR.defaultBlockState(), 3);
            } finally {
                level.restoringBlockSnapshots = wasRestoringSnapshots;
            }

            helper.assertTrue(savedData.benchCount(owner.getUUID()) == 1,
                    "a block update during snapshot restore (cancelled placement rollback) must NOT decrement "
                            + "a count that was never incremented for it, got "
                            + savedData.benchCount(owner.getUUID()));

            // 正常破坏路径不受影响: 上面的 restore 已把该位置清成 AIR, 在同一相对坐标重新放一台 (newBench 固定
            // 用 (0,1,0)), 在标志位为 false 时正常移除, 计数照常回收到 0 —— 证明新判据只挡快照回滚这一种场景。
            newBench(helper, owner);
            helper.getLevel().destroyBlock(benchPos, false);
            helper.assertTrue(savedData.benchCount(owner.getUUID()) == 0,
                    "a genuine destroy outside snapshot-restore still recovers the count normally, got "
                            + savedData.benchCount(owner.getUUID()));
            helper.succeed();
        } finally {
            savedData.decrement(owner.getUUID());
        }
    }

    // ---- 测试辅助 ----

    /**
     * 把开工中的批次 craftingStartTick 经 NBT 注入回拨到远古, 使下一次 settleForOwner 判定本批已到期
     * (与 backdateSettleTick 同一确定性手段; 开工状态 CraftingActive/CraftingCaliber 随 NBT 往返原样保留)。
     */
    private static void backdateCraftStart(MunitionsBenchBlockEntity be, GameTestHelper helper) {
        net.minecraft.nbt.CompoundTag tag = be.saveWithoutMetadata();
        tag.putLong("CraftingStartTick", helper.getLevel().getGameTime() - 10_000_000L);
        be.load(tag);
    }

    /** 在 helper 世界 (0,1,0) 放一个军火台 BE, 设主人为 player, 锚定首帧时间戳并返回。 */
    private static MunitionsBenchBlockEntity newBench(GameTestHelper helper, ServerPlayer owner) {
        return newBench(helper, owner, ModMunitionsBlocks.MUNITIONS_BENCH_HIGH.get());
    }

    private static MunitionsBenchBlockEntity newBench(GameTestHelper helper, ServerPlayer owner,
                                                      net.minecraft.world.level.block.Block block) {
        BlockPos rel = new BlockPos(0, 1, 0);
        helper.setBlock(rel, block);
        BlockPos abs = helper.absolutePos(rel);
        net.minecraft.world.level.block.entity.BlockEntity raw = helper.getLevel().getBlockEntity(abs);
        if (!(raw instanceof MunitionsBenchBlockEntity be)) {
            throw new IllegalStateException("munitions bench BE not present at " + abs);
        }
        be.setOwner(owner.getUUID());
        // 军械台现在吃电: 除电力门本身的用例外, 其余用例关注的是料/时间/缓冲/权限等维度,
        // 故这里默认把内部缓冲充满, 免得每个既有用例都要重复一遍充电样板。
        be.getCapability(net.minecraftforge.common.capabilities.ForgeCapabilities.ENERGY)
                .ifPresent(storage -> storage.receiveEnergy(Integer.MAX_VALUE, false));
        return be;
    }

    /**
     * 在 owner 上为给定军火台 BE 打开一个真 {@link MunitionsBenchMenu} (经其构造器从 level@pos 解析回该 BE),
     * 供 quickMoveStack 端到端走基类 Shift 移物路径 (munitions-output 输出槽回收缓冲断言)。窗口 id 任意 (1)。
     */
    private static MunitionsBenchMenu openBenchMenu(MunitionsBenchBlockEntity be, ServerPlayer owner) {
        return new MunitionsBenchMenu(1, owner.getInventory(), be.getBlockPos());
    }

    /**
     * 把 BE 的 lastSettleTick 经 NBT 注入回拨到 (当前 gameTime - ticksAgo), 使下一次 settleForOwner 的 elapsed
     * 恰为 ticksAgo。GameTest 世界主时钟不可在单测内直控, 故用 BlockEntity 持久化往返注入时间戳是唯一确定性手段:
     * saveWithoutMetadata() 取全状态 (含 owner/选中口径/料槽), 仅改 LastSettleTick 再 load 回, 其余状态原样保留。
     */
    private static void backdateSettleTick(MunitionsBenchBlockEntity be, GameTestHelper helper, long ticksAgo) {
        long now = helper.getLevel().getGameTime();
        net.minecraft.nbt.CompoundTag tag = be.saveWithoutMetadata();
        tag.putLong("LastSettleTick", now - ticksAgo);
        // 注入 "已锚定首帧" 标志, 使下一次 settle 直接按回拨流逝量补产, 不被首帧初始化门吞掉 (与 ticker 是否已跑过解耦,
        // 确定性: 测试不依赖 BE 在测试体执行前是否恰好 tick 过一次)。
        tag.putBoolean("SettleInitialized", true);
        be.load(tag);
    }

    /** 备料 batches 批: 每槽塞 batches x 单批 cost (对 config 默认 cost 变更鲁棒, propellantCost 默认 2)。 */
    private static void stockParts(MunitionsBenchBlockEntity be, int batches) {
        be.inventory().setStackInSlot(MunitionsBenchBlockEntity.SLOT_PRIMER,
                new ItemStack(ModMunitionsItems.PRIMER.get(), batches * MunitionsConfig.RECIPE_PRIMER_COST.get()));
        be.inventory().setStackInSlot(MunitionsBenchBlockEntity.SLOT_CASING,
                new ItemStack(ModMunitionsItems.CASING.get(), batches * MunitionsConfig.RECIPE_CASING_COST.get()));
        be.inventory().setStackInSlot(MunitionsBenchBlockEntity.SLOT_BULLET_HEAD,
                new ItemStack(ModMunitionsItems.BULLET_HEAD.get(),
                        batches * MunitionsConfig.RECIPE_BULLET_HEAD_COST.get()));
        be.inventory().setStackInSlot(MunitionsBenchBlockEntity.SLOT_PROPELLANT,
                new ItemStack(ModMunitionsItems.PROPELLANT.get(),
                        batches * MunitionsConfig.RECIPE_PROPELLANT_COST.get()));
    }

    /** 断言各槽剩余恰为 expectedBatches 批的备料量 (0 = 整批走料全消耗)。 */
    private static void assertPartCounts(GameTestHelper helper, MunitionsBenchBlockEntity be, int expectedBatches) {
        helper.assertTrue(be.inventory().getStackInSlot(MunitionsBenchBlockEntity.SLOT_PRIMER).getCount()
                        == expectedBatches * MunitionsConfig.RECIPE_PRIMER_COST.get(),
                "primer count expected " + expectedBatches + " batches");
        helper.assertTrue(be.inventory().getStackInSlot(MunitionsBenchBlockEntity.SLOT_CASING).getCount()
                        == expectedBatches * MunitionsConfig.RECIPE_CASING_COST.get(),
                "casing count expected " + expectedBatches + " batches");
        helper.assertTrue(be.inventory().getStackInSlot(MunitionsBenchBlockEntity.SLOT_BULLET_HEAD).getCount()
                        == expectedBatches * MunitionsConfig.RECIPE_BULLET_HEAD_COST.get(),
                "bullet head count expected " + expectedBatches + " batches");
        helper.assertTrue(be.inventory().getStackInSlot(MunitionsBenchBlockEntity.SLOT_PROPELLANT).getCount()
                        == expectedBatches * MunitionsConfig.RECIPE_PROPELLANT_COST.get(),
                "propellant count expected " + expectedBatches + " batches");
    }

    private static long readSettleTick(MunitionsBenchBlockEntity be) {
        return be.saveWithoutMetadata().getLong("LastSettleTick");
    }

    /** 经 NBT 注入缓冲态 (已产某口径若干发未取), 测换口径冲突门 (BufferedRounds/BufferedCaliber 持久键)。 */
    private static void seedBuffer(MunitionsBenchBlockEntity be, MunitionsCaliber caliber, int rounds) {
        net.minecraft.nbt.CompoundTag tag = be.saveWithoutMetadata();
        tag.putInt("BufferedRounds", rounds);
        tag.putInt("BufferedCaliber", caliber.index());
        be.load(tag);
    }

    private static IJobService swapJob(IJobService fake) {
        IJobService prev;
        try {
            prev = JobServices.jobService();
        } catch (IllegalStateException notRegistered) {
            prev = null;
        }
        JobServices.registerJobService(fake);
        return prev;
    }

    private static void restoreJob(IJobService prev) {
        if (prev != null) {
            JobServices.registerJobService(prev);
        } else {
            JobServices.reset();
        }
    }

    private static IEconomyService swapEconomy(IEconomyService fake) {
        IEconomyService prev = EconomyServices.isRegistered() ? EconomyServices.economyService() : null;
        EconomyServices.registerEconomyService(fake);
        return prev;
    }

    private static void restoreEconomy(IEconomyService prev) {
        if (prev != null) {
            EconomyServices.registerEconomyService(prev);
        } else {
            EconomyServices.reset();
        }
    }

    /** 真 EconomyService (内存账本 + AbuseGuard + 惰性玩家态解析器); tryCharge 走真 sink 语义。 */
    private static IEconomyService freshEconomy(EconomyLedger ledger) {
        Map<UUID, PlayerAbuseState> states = new HashMap<>();
        Function<UUID, PlayerAbuseState> resolver = id -> states.computeIfAbsent(id, k -> new PlayerAbuseState());
        return new EconomyService(ledger, new AbuseGuard(), resolver);
    }

    /** 定级职业门面替身 (level/grantXp 不计数, 仅供 trySelectCaliber 的等级门读取)。 */
    private static final class FixedLevelJobService implements IJobService {
        private final int level;

        FixedLevelJobService(int level) {
            this.level = level;
        }

        @Override
        public int level(Player player, JobId job) {
            return level;
        }

        @Override
        public long totalXp(Player player, JobId job) {
            return 0L;
        }

        @Override
        public long grantXp(Player player, JobId job, long rawXp) {
            return rawXp;
        }

        @Override
        public JobProgress progress(Player player, JobId job) {
            throw new UnsupportedOperationException("not exercised by munitions caliber-gate tests");
        }
    }

    /** 记录 grantXp 调用的职业门面替身 (settle 谁产谁得断言用); level 给定值供产能查表。 */
    private static final class RecordingJobService implements IJobService {
        private final int level;
        int grantXpCalls = 0;
        JobId lastJob = null;
        long lastRawXp = Long.MIN_VALUE;

        RecordingJobService(int level) {
            this.level = level;
        }

        @Override
        public int level(Player player, JobId job) {
            return level;
        }

        @Override
        public long totalXp(Player player, JobId job) {
            return 0L;
        }

        @Override
        public long grantXp(Player player, JobId job, long rawXp) {
            grantXpCalls++;
            lastJob = job;
            lastRawXp = rawXp;
            return rawXp;
        }

        @Override
        public JobProgress progress(Player player, JobId job) {
            throw new UnsupportedOperationException("not exercised by munitions settle tests");
        }
    }
}
