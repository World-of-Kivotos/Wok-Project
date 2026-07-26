package com.miningdim.job.farmer;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.loot.LootContext;
import net.minecraft.world.level.storage.loot.parameters.LootContextParams;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.common.loot.IGlobalLootModifier;
import net.minecraftforge.common.loot.LootModifier;
import org.jetbrains.annotations.NotNull;

/** Applies the documented 2/3/4/5/6 tier factor to mature supported crop produce. */
public final class FarmerHarvestLootModifier extends LootModifier {

    public static final Codec<FarmerHarvestLootModifier> CODEC = RecordCodecBuilder.create(
            instance -> codecStart(instance).apply(instance, FarmerHarvestLootModifier::new));

    public FarmerHarvestLootModifier(net.minecraft.world.level.storage.loot.predicates.LootItemCondition[] conditions) {
        super(conditions);
    }

    @Override
    protected @NotNull ObjectArrayList<ItemStack> doApply(ObjectArrayList<ItemStack> generatedLoot,
                                                           LootContext context) {
        BlockState state = context.getParamOrNull(LootContextParams.BLOCK_STATE);
        Vec3 origin = context.getParamOrNull(LootContextParams.ORIGIN);
        if (state == null || origin == null || !FarmerHarvests.isSupportedMatureCrop(state)) {
            return generatedLoot;
        }
        FarmerTier tier = FarmerHarvests.tierFor(
                context.getLevel(), BlockPos.containing(origin), state);
        if (tier != null) {
            FarmerHarvests.multiplyProduce(generatedLoot, state, tier);
        }
        return generatedLoot;
    }

    @Override
    public Codec<? extends IGlobalLootModifier> codec() {
        return CODEC;
    }
}
