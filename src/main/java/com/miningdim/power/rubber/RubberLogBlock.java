package com.miningdim.power.rubber;

import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.RotatedPillarBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.phys.BlockHitResult;
import org.jetbrains.annotations.Nullable;

/** 可保存割胶冷却的橡胶树干；不提供 ticker。 */
public final class RubberLogBlock extends RotatedPillarBlock implements EntityBlock {

    public static final BooleanProperty TAPPED = BooleanProperty.create("tapped");

    public RubberLogBlock(BlockBehaviour.Properties properties) {
        super(properties);
        registerDefaultState(stateDefinition.any().setValue(AXIS, net.minecraft.core.Direction.Axis.Y).setValue(TAPPED, false));
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        super.createBlockStateDefinition(builder);
        builder.add(TAPPED);
    }

    @Nullable
    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new RubberLogBlockEntity(pos, state);
    }

    @Override
    public InteractionResult use(BlockState state, Level level, BlockPos pos, Player player,
                                 InteractionHand hand, BlockHitResult hit) {
        ItemStack knife = player.getItemInHand(hand);
        if (!knife.is(PowerRubberRegistry.RUBBER_TAPPING_KNIFE.get())) {
            return InteractionResult.PASS;
        }
        if (level.isClientSide) {
            return InteractionResult.SUCCESS;
        }
        if (!(level.getBlockEntity(pos) instanceof RubberLogBlockEntity log)) {
            throw new IllegalStateException("rubber_log missing RubberLogBlockEntity at " + pos);
        }
        if (!log.tryTap(level.getGameTime())) {
            return InteractionResult.CONSUME;
        }

        Block.popResource(level, pos, new ItemStack(PowerRubberRegistry.LATEX.get()));
        knife.hurtAndBreak(1, player, entity -> entity.broadcastBreakEvent(hand));
        level.setBlock(pos, state.setValue(TAPPED, true), Block.UPDATE_ALL);
        return InteractionResult.CONSUME;
    }

    @Override
    public void setPlacedBy(Level level, BlockPos pos, BlockState state, @Nullable LivingEntity placer, ItemStack stack) {
        super.setPlacedBy(level, pos, state, placer, stack);
        if (!level.isClientSide && level.getBlockEntity(pos) instanceof RubberLogBlockEntity log) {
            level.setBlock(pos, state.setValue(TAPPED, log.wasTapped()), Block.UPDATE_ALL);
        }
    }

    @Override
    public ItemStack getCloneItemStack(BlockGetter level, BlockPos pos, BlockState state) {
        ItemStack stack = super.getCloneItemStack(level, pos, state);
        if (level.getBlockEntity(pos) instanceof RubberLogBlockEntity log) {
            log.saveToItem(stack);
        }
        return stack;
    }
}
