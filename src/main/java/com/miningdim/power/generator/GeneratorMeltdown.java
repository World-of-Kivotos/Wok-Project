package com.miningdim.power.generator;

import com.miningdim.power.GeneratorMultiblockBlock;
import com.miningdim.power.cable.EnergyCableBlock;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Explosion;
import net.minecraft.world.level.ExplosionDamageCalculator;
import net.minecraft.world.level.block.BaseFireBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/** 熔毁只复用原版爆炸的抗爆和战利品语义，实体伤害始终按规格表的生命百分比单独结算。 */
public final class GeneratorMeltdown {

    private static final Logger LOGGER = LoggerFactory.getLogger("miningdim/power");
    private static final Comparator<BlockPos> BLOCK_POS_ORDER = Comparator.<BlockPos>comparingInt(BlockPos::getX)
            .thenComparingInt(BlockPos::getY)
            .thenComparingInt(BlockPos::getZ);
    private static final float RAY_STEP = 0.3F;
    private static final float RESISTANCE_STEP = 0.22500001F;

    private GeneratorMeltdown() {
    }

    public static void execute(GeneratorBlockEntity controller) {
        if (!(controller.getLevel() instanceof ServerLevel level)) {
            throw new IllegalStateException("generator meltdown requires a server level at " + controller.getBlockPos());
        }
        if (!controller.isMeltdown()) {
            throw new IllegalStateException("generator meltdown requested before terminal state at "
                    + controller.getBlockPos());
        }

        GeneratorSpec.Runtime runtime = controller.runtime();
        BlockPos anchorPos = controller.getBlockPos();
        Vec3 center = Vec3.atCenterOf(anchorPos);
        Explosion context = new Explosion(level, null, center.x, center.y, center.z,
                runtime.scatterRadius(), false, Explosion.BlockInteraction.DESTROY_WITH_DECAY);
        Set<BlockPos> ownStructure = structurePositions(controller);
        List<BlockPos> destructible = collectDestructibleBlocks(level, context, runtime, center, ownStructure,
                controller.spec().sourceVoltage());

        logMeltdown(level, controller);
        GeneratorMultiblockBlock.clearStructureForMeltdown(level, anchorPos);

        context.getToBlow().addAll(destructible);
        context.finalizeExplosion(true);
        burnUnderVoltageCables(level, center, runtime.scatterRadius(), controller.spec().sourceVoltage());
        hurtLivingEntities(level, context, center, runtime);
        placeFires(level, destructible, runtime.maxFirePoints());
    }

    private static Set<BlockPos> structurePositions(GeneratorBlockEntity controller) {
        BlockState anchorState = controller.getBlockState();
        if (!(anchorState.getBlock() instanceof GeneratorMultiblockBlock)) {
            throw new IllegalStateException("generator meltdown controller has non-generator state at "
                    + controller.getBlockPos());
        }
        Set<BlockPos> positions = new HashSet<>();
        for (GeneratorMultiblockBlock.Part part : GeneratorMultiblockBlock.Part.values()) {
            positions.add(GeneratorMultiblockBlock.partPos(controller.getBlockPos(),
                    anchorState.getValue(GeneratorMultiblockBlock.FACING), part));
        }
        return positions;
    }

    private static List<BlockPos> collectDestructibleBlocks(ServerLevel level, Explosion context,
                                                            GeneratorSpec.Runtime runtime, Vec3 center,
                                                            Set<BlockPos> excluded,
                                                            com.miningdim.power.grid.VoltageClass sourceVoltage) {
        ExplosionDamageCalculator calculator = new ExplosionDamageCalculator();
        RandomSource random = level.random;
        Set<BlockPos> candidates = new HashSet<>();
        int radius = runtime.scatterRadius();
        double radiusSquared = (double) radius * radius;

        for (int x = 0; x < 16; x++) {
            for (int y = 0; y < 16; y++) {
                for (int z = 0; z < 16; z++) {
                    if (x != 0 && x != 15 && y != 0 && y != 15 && z != 0 && z != 15) {
                        continue;
                    }
                    double rayX = (float) x / 15.0F * 2.0F - 1.0F;
                    double rayY = (float) y / 15.0F * 2.0F - 1.0F;
                    double rayZ = (float) z / 15.0F * 2.0F - 1.0F;
                    double length = Math.sqrt(rayX * rayX + rayY * rayY + rayZ * rayZ);
                    rayX /= length;
                    rayY /= length;
                    rayZ /= length;
                    float remainingPower = radius * (0.7F + random.nextFloat() * 0.6F);
                    double currentX = center.x;
                    double currentY = center.y;
                    double currentZ = center.z;

                    while (remainingPower > 0.0F) {
                        BlockPos pos = BlockPos.containing(currentX, currentY, currentZ);
                        if (!level.isInWorldBounds(pos) || !level.hasChunkAt(pos)) {
                            break;
                        }
                        BlockState state = level.getBlockState(pos);
                        FluidState fluid = level.getFluidState(pos);
                        Optional<Float> resistance = calculator.getBlockExplosionResistance(
                                context, level, pos, state, fluid);
                        if (resistance.isPresent()) {
                            remainingPower -= (resistance.get() + 0.3F) * 0.3F;
                        }
                        if (remainingPower > 0.0F
                                && calculator.shouldBlockExplode(context, level, pos, state, remainingPower)
                                && !state.isAir()
                                && !excluded.contains(pos)
                                && !(state.getBlock() instanceof GeneratorMultiblockBlock)
                                && !isUnderVoltageCable(state, sourceVoltage)
                                && Vec3.atCenterOf(pos).distanceToSqr(center) <= radiusSquared) {
                            candidates.add(pos.immutable());
                        }
                        currentX += rayX * RAY_STEP;
                        currentY += rayY * RAY_STEP;
                        currentZ += rayZ * RAY_STEP;
                        remainingPower -= RESISTANCE_STEP;
                    }
                }
            }
        }

        List<BlockPos> ordered = new ArrayList<>(candidates);
        ordered.sort(BLOCK_POS_ORDER);
        if (ordered.size() > runtime.maxDestructibleBlocks()) {
            return List.copyOf(ordered.subList(0, runtime.maxDestructibleBlocks()));
        }
        return List.copyOf(ordered);
    }

    private static boolean isUnderVoltageCable(BlockState state,
                                               com.miningdim.power.grid.VoltageClass sourceVoltage) {
        return state.getBlock() instanceof EnergyCableBlock cable
                && sourceVoltage.isHigherThan(cable.material().voltageClass());
    }

    private static void burnUnderVoltageCables(ServerLevel level, Vec3 center, int radius,
                                                com.miningdim.power.grid.VoltageClass sourceVoltage) {
        int minX = (int) Math.floor(center.x - radius);
        int minY = (int) Math.floor(center.y - radius);
        int minZ = (int) Math.floor(center.z - radius);
        int maxX = (int) Math.floor(center.x + radius);
        int maxY = (int) Math.floor(center.y + radius);
        int maxZ = (int) Math.floor(center.z + radius);
        double radiusSquared = (double) radius * radius;
        for (BlockPos pos : BlockPos.betweenClosed(minX, minY, minZ, maxX, maxY, maxZ)) {
            if (!level.hasChunkAt(pos) || Vec3.atCenterOf(pos).distanceToSqr(center) > radiusSquared) {
                continue;
            }
            BlockState state = level.getBlockState(pos);
            if (isUnderVoltageCable(state, sourceVoltage)) {
                level.setBlock(pos, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
            }
        }
    }

    private static void hurtLivingEntities(ServerLevel level, Explosion context, Vec3 center,
                                           GeneratorSpec.Runtime runtime) {
        int radius = runtime.scatterRadius();
        AABB area = new AABB(center.x - radius, center.y - radius, center.z - radius,
                center.x + radius, center.y + radius, center.z + radius);
        for (LivingEntity entity : level.getEntitiesOfClass(LivingEntity.class, area)) {
            double distance = entity.position().distanceTo(center);
            if (distance >= radius) {
                continue;
            }
            double multiplier = 1.0D - distance / radius;
            float damage = (float) (entity.getMaxHealth() * runtime.centerDamageFraction() * multiplier);
            if (damage > 0.0F) {
                entity.hurt(context.getDamageSource(), damage);
            }
        }
    }

    private static void placeFires(ServerLevel level, List<BlockPos> destroyed, int maxFirePoints) {
        int placed = 0;
        for (BlockPos pos : destroyed) {
            if (placed == maxFirePoints) {
                return;
            }
            if (level.getBlockState(pos).isAir() && level.getBlockState(pos.below()).isSolidRender(level, pos.below())) {
                level.setBlockAndUpdate(pos, BaseFireBlock.getState(level, pos));
                placed++;
            }
        }
    }

    private static void logMeltdown(ServerLevel level, GeneratorBlockEntity controller) {
        ItemStack fuel = controller.fuelCore();
        LOGGER.error("generator meltdown dimension={} pos={} spec={} sourceVoltage={} temperatureC={} storedFe={} "
                        + "fuel={} fuelDamage={}/{} networkFault={} bufferRejectionFe={}",
                level.dimension().location(), controller.getBlockPos(), controller.spec().id(),
                controller.spec().sourceVoltage(), controller.temperatureC(), controller.storedFe(),
                BuiltInRegistries.ITEM.getKey(fuel.getItem()), fuel.getDamageValue(), fuel.getMaxDamage(),
                controller.networkFault(), controller.bufferRejectionFe());
    }
}
