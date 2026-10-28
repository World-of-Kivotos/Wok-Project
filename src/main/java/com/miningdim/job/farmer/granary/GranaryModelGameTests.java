package com.miningdim.job.farmer.granary;

import com.miningdim.core.MiningConstants;
import com.miningdim.job.farmer.granary.block.GranaryBinBlock;
import com.miningdim.job.farmer.granary.block.GranaryBlocks;
import com.miningdim.job.farmer.granary.block.GranaryCenterBlock;
import com.miningdim.job.farmer.granary.block.GranaryVentBlock;
import com.miningdim.testutil.MockGameTestPlayers;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.BooleanOp;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

@GameTestHolder(MiningConstants.MODID)
@PrefixGameTestTemplate(false)
public final class GranaryModelGameTests {

    private static final String EMPTY = "empty";
    private static final String BATCH = "granary_model";

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void centerAndVentPlacementFaceThePlayer(GameTestHelper helper) {
        ServerPlayer player = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);
        try {
            BlockPos pos = helper.absolutePos(new BlockPos(1, 1, 1));
            BlockHitResult hit = new BlockHitResult(Vec3.atCenterOf(pos), Direction.UP, pos, false);
            GranaryCenterBlock center = (GranaryCenterBlock) GranaryBlocks.CENTER.get();
            GranaryVentBlock vent = (GranaryVentBlock) GranaryBlocks.VENT.get();
            for (Direction looking : Direction.Plane.HORIZONTAL) {
                player.setYRot(looking.toYRot());
                BlockPlaceContext centerContext = new BlockPlaceContext(helper.getLevel(), player,
                        InteractionHand.MAIN_HAND, new ItemStack(GranaryBlocks.CENTER_ITEM.get()), hit);
                BlockPlaceContext ventContext = new BlockPlaceContext(helper.getLevel(), player,
                        InteractionHand.MAIN_HAND, new ItemStack(GranaryBlocks.VENT_ITEM.get()), hit);
                Direction expected = looking.getOpposite();
                helper.assertTrue(center.getStateForPlacement(centerContext).getValue(GranaryCenterBlock.FACING) == expected,
                        "账台朝向应面向玩家: 玩家朝 " + looking);
                helper.assertTrue(vent.getStateForPlacement(ventContext).getValue(GranaryVentBlock.FACING) == expected,
                        "气窗朝向应面向玩家: 玩家朝 " + looking);
            }
        } finally {
            MockGameTestPlayers.logout(player);
        }
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void componentShapesMatchTheirModels(GameTestHelper helper) {
        BlockPos pos = helper.absolutePos(new BlockPos(1, 1, 1));
        GranaryBinBlock bin = (GranaryBinBlock) GranaryBlocks.BIN.get();
        BlockState binState = bin.defaultBlockState();
        assertCollisionMatchesOutline(helper, binState, pos, "储粮囤");
        AABB binBounds = bin.getShape(binState, helper.getLevel(), pos, CollisionContext.empty()).bounds();
        helper.assertTrue(binBounds.minX == 1.0D / 16.0D && binBounds.maxX == 15.0D / 16.0D
                        && binBounds.minY == 0.0D && binBounds.maxY == 1.0D
                        && binBounds.minZ == 1.0D / 16.0D && binBounds.maxZ == 15.0D / 16.0D,
                "储粮囤外形应为 1..15 的木箱");

        GranaryCenterBlock center = (GranaryCenterBlock) GranaryBlocks.CENTER.get();
        GranaryVentBlock vent = (GranaryVentBlock) GranaryBlocks.VENT.get();
        for (Direction facing : Direction.Plane.HORIZONTAL) {
            BlockState centerState = center.defaultBlockState().setValue(GranaryCenterBlock.FACING, facing);
            assertCollisionMatchesOutline(helper, centerState, pos, "账台");
            VoxelShape shape = center.getShape(centerState, helper.getLevel(), pos, CollisionContext.empty());
            // getFaceShape 的切片 bounds 会扩为单位格，取真实顶层交集验证台面。
            AABB top = Shapes.join(shape, Shapes.box(0, 13.0D / 16.0D, 0, 1, 1, 1), BooleanOp.AND).bounds();
            double minX = switch (facing) {
                case NORTH -> 2.0D / 16.0D;
                case EAST -> 3.0D / 16.0D;
                case SOUTH -> 1.0D / 16.0D;
                case WEST -> 4.0D / 16.0D;
                default -> throw new IllegalStateException("Expected horizontal facing");
            };
            double maxX = switch (facing) {
                case NORTH -> 15.0D / 16.0D;
                case EAST -> 12.0D / 16.0D;
                case SOUTH -> 14.0D / 16.0D;
                case WEST -> 13.0D / 16.0D;
                default -> throw new IllegalStateException("Expected horizontal facing");
            };
            double minZ = switch (facing) {
                case NORTH -> 4.0D / 16.0D;
                case EAST -> 2.0D / 16.0D;
                case SOUTH -> 3.0D / 16.0D;
                case WEST -> 1.0D / 16.0D;
                default -> throw new IllegalStateException("Expected horizontal facing");
            };
            double maxZ = switch (facing) {
                case NORTH -> 13.0D / 16.0D;
                case EAST -> 15.0D / 16.0D;
                case SOUTH -> 12.0D / 16.0D;
                case WEST -> 14.0D / 16.0D;
                default -> throw new IllegalStateException("Expected horizontal facing");
            };
            helper.assertTrue(top.minX == minX && top.maxX == maxX && top.minZ == minZ && top.maxZ == maxZ,
                    "账台顶部轮廓须随朝向旋转: " + facing);

            BlockState ventState = vent.defaultBlockState().setValue(GranaryVentBlock.FACING, facing);
            assertCollisionMatchesOutline(helper, ventState, pos, "气窗");
            assertVentShape(helper, vent, pos, facing);
        }
        helper.succeed();
    }

    private static void assertVentShape(GameTestHelper helper, GranaryVentBlock vent,
                                        BlockPos pos, Direction facing) {
        AABB bounds = vent.getShape(vent.defaultBlockState().setValue(GranaryVentBlock.FACING, facing),
                helper.getLevel(), pos, CollisionContext.empty()).bounds();
        boolean northSouth = facing.getAxis() == Direction.Axis.Z;
        double minX = northSouth ? 0.0D : 6.0D / 16.0D;
        double maxX = northSouth ? 1.0D : 10.0D / 16.0D;
        double minZ = northSouth ? 6.0D / 16.0D : 0.0D;
        double maxZ = northSouth ? 10.0D / 16.0D : 1.0D;
        helper.assertTrue(bounds.minX == minX && bounds.maxX == maxX
                        && bounds.minY == 0.0D && bounds.maxY == 1.0D
                        && bounds.minZ == minZ && bounds.maxZ == maxZ,
                "气窗须为与 FACING 一致的竖直薄框: " + facing);
    }

    private static void assertCollisionMatchesOutline(GameTestHelper helper, BlockState state,
                                                      BlockPos pos, String component) {
        VoxelShape outline = state.getShape(helper.getLevel(), pos, CollisionContext.empty());
        VoxelShape collision = state.getCollisionShape(helper.getLevel(), pos, CollisionContext.empty());
        helper.assertTrue(collision == outline, component + " 的碰撞形状应与轮廓形状一致");
    }
}
