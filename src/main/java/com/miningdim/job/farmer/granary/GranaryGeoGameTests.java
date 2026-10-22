package com.miningdim.job.farmer.granary;

import com.miningdim.core.MiningConstants;
import com.miningdim.job.farmer.granary.block.GranaryBinBlockEntity;
import com.miningdim.job.farmer.granary.block.GranaryBlocks;
import com.miningdim.job.farmer.granary.block.GranaryCenterBlockEntity;
import com.miningdim.job.farmer.granary.block.GranaryVentBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

@GameTestHolder(MiningConstants.MODID)
@PrefixGameTestTemplate(false)
public final class GranaryGeoGameTests {

    private static final String EMPTY = "empty";
    private static final String BATCH = "granary_gecko_static_entities";

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void everyMaterialUsesStableGeoEntitiesAndAnimatedRenderShape(GameTestHelper helper) {
        helper.assertTrue(GranaryBlocks.MATERIAL_VARIANTS.size() == 31,
                "粮仓登记了 11 种木材和 20 种新增材质");

        BlockPos binPos = new BlockPos(1, 1, 1);
        BlockPos centerPos = new BlockPos(2, 1, 1);
        BlockPos ventPos = new BlockPos(3, 1, 1);

        for (GranaryBlocks.MaterialVariant variant : GranaryBlocks.MATERIAL_VARIANTS) {
            BlockPos absoluteBinPos = helper.absolutePos(binPos);
            helper.setBlock(binPos, variant.bin().get());
            BlockEntity binEntity = helper.getLevel().getBlockEntity(absoluteBinPos);
            helper.assertTrue(binEntity instanceof GranaryBinBlockEntity bin
                            && bin.getType() == GranaryBlocks.BIN_ENTITY.get()
                            && bin.getAnimatableInstanceCache() != null
                            && bin.getAnimatableInstanceCache() == bin.getAnimatableInstanceCache(),
                    variant.material() + " 储粮囤使用稳定的 GeoBlockEntity 缓存");
            helper.assertTrue(variant.bin().get().getRenderShape(variant.bin().get().defaultBlockState())
                            == RenderShape.ENTITYBLOCK_ANIMATED,
                    variant.material() + " 储粮囤只由实体渲染器绘制");

            BlockPos absoluteCenterPos = helper.absolutePos(centerPos);
            helper.setBlock(centerPos, variant.center().get());
            BlockEntity centerEntity = helper.getLevel().getBlockEntity(absoluteCenterPos);
            helper.assertTrue(centerEntity instanceof GranaryCenterBlockEntity center
                            && center.getType() == GranaryBlocks.CENTER_ENTITY.get()
                            && center.getAnimatableInstanceCache() != null
                            && center.getAnimatableInstanceCache() == center.getAnimatableInstanceCache(),
                    variant.material() + " 账台使用稳定的 GeoBlockEntity 缓存");
            helper.assertTrue(variant.center().get().getRenderShape(variant.center().get().defaultBlockState())
                            == RenderShape.ENTITYBLOCK_ANIMATED,
                    variant.material() + " 账台只由实体渲染器绘制");

            BlockPos absoluteVentPos = helper.absolutePos(ventPos);
            helper.setBlock(ventPos, variant.vent().get());
            BlockEntity ventEntity = helper.getLevel().getBlockEntity(absoluteVentPos);
            helper.assertTrue(ventEntity instanceof GranaryVentBlockEntity vent
                            && vent.getType() == GranaryBlocks.VENT_ENTITY.get()
                            && vent.getAnimatableInstanceCache() != null
                            && vent.getAnimatableInstanceCache() == vent.getAnimatableInstanceCache(),
                    variant.material() + " 气窗正确挂载并复用 GeoBlockEntity 缓存");
            helper.assertTrue(variant.vent().get().getRenderShape(variant.vent().get().defaultBlockState())
                            == RenderShape.ENTITYBLOCK_ANIMATED,
                    variant.material() + " 气窗只由实体渲染器绘制");
        }
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void missingVentEntityIsHydratedWithoutChangingChunkContents(GameTestHelper helper) {
        BlockPos unrelatedBlockPos = new BlockPos(1, 1, 1);
        BlockPos binPos = new BlockPos(2, 1, 1);
        BlockPos ventPos = new BlockPos(3, 1, 1);
        BlockPos absoluteUnrelatedBlockPos = helper.absolutePos(unrelatedBlockPos);
        BlockPos absoluteBinPos = helper.absolutePos(binPos);
        BlockPos absoluteVentPos = helper.absolutePos(ventPos);

        helper.setBlock(unrelatedBlockPos, Blocks.GOLD_BLOCK);
        helper.setBlock(binPos, GranaryBlocks.MATERIAL_VARIANTS.get(0).bin().get());
        helper.setBlock(ventPos, GranaryBlocks.MATERIAL_VARIANTS.get(0).vent().get()
                .defaultBlockState().setValue(com.miningdim.job.farmer.granary.block.GranaryVentBlock.FACING,
                        net.minecraft.core.Direction.EAST));

        LevelChunk chunk = helper.getLevel().getChunkAt(absoluteVentPos);
        BlockEntity binBefore = chunk.getBlockEntity(absoluteBinPos);
        chunk.removeBlockEntity(absoluteVentPos);
        helper.assertTrue(!chunk.getBlockEntities().containsKey(absoluteVentPos),
                "模拟旧存档中未保存气窗 BlockEntity 的情况");

        int created = GranaryGeoCompatibility.hydrateVentEntities(chunk);
        BlockEntity restored = chunk.getBlockEntity(absoluteVentPos);
        helper.assertTrue(created == 1 && restored instanceof GranaryVentBlockEntity
                        && restored.getType() == GranaryBlocks.VENT_ENTITY.get(),
                "兼容入口创建登记类型正确的气窗实体");
        helper.assertTrue(restored.getBlockState().getValue(
                        com.miningdim.job.farmer.granary.block.GranaryVentBlock.FACING)
                        == net.minecraft.core.Direction.EAST,
                "补建实体保留原气窗朝向");
        helper.assertTrue(GranaryGeoCompatibility.hydrateVentEntities(chunk) == 0
                        && chunk.getBlockEntity(absoluteVentPos) == restored,
                "重复迁移不会替换已经存在的实体");
        helper.assertTrue(chunk.getBlockState(absoluteUnrelatedBlockPos).is(Blocks.GOLD_BLOCK)
                        && chunk.getBlockEntity(absoluteBinPos) == binBefore
                        && binBefore.getType() == GranaryBlocks.BIN_ENTITY.get(),
                "迁移不影响同区块内的其他方块和实体");
        helper.succeed();
    }
}
