package com.miningdim.job.farmer;

import com.miningdim.job.JobId;
import com.miningdim.job.JobServices;
import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.loot.LootContext;
import net.minecraft.world.level.storage.loot.parameters.LootContextParams;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.common.loot.IGlobalLootModifier;
import net.minecraftforge.common.loot.LootModifier;
import net.minecraftforge.common.util.FakePlayer;
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
        // 全局掉落修改器没有 BreakEvent 那种"仅玩家"的隐含保证: Block.getDrops 的无实体重载
        // (活塞推毁、爆炸、各类自动化 API) 同样会走到这里, 且 THIS_ENTITY 在那些路径下为 null。
        // json 侧已挂 entity_properties 条件, 此处再独立拦一道 —— 两层互不依赖, 条件被误改回空数组时
        // 本方法仍能自行拒绝非玩家来源。KILLER_ENTITY 在方块破坏上下文中从不赋值, 不能用作判据。
        // FakePlayer 必须单独排除: 它是 ServerPlayer 的子类, 且 getType() 同样返回 minecraft:player,
        // 因此 instanceof 与 json 里的 entity_properties 条件都拦不住它。自动化模组正是借它伪装成玩家
        // 来驱动收割, 放行即等于把增产直接送给机器农场。
        Entity harvester = context.getParamOrNull(LootContextParams.THIS_ENTITY);
        if (!(harvester instanceof ServerPlayer player) || harvester instanceof FakePlayer) {
            return generatedLoot;
        }
        FarmerTier tier = FarmerHarvests.tierFor(
                context.getLevel(), BlockPos.containing(origin), state);
        if (tier == null) {
            return generatedLoot;
        }
        // 与耕地放置门 (FarmlandPlacementGuard) 同一口径: 未解锁该档位者不享受其倍率, 退化为不放大。
        // 否则 1 级玩家在他人闪耀耕地上收割即可拿满 6 倍, 与"按职业等级递进"的经济设计相悖。
        if (!tier.isUnlockedAt(JobServices.jobService().level(player, JobId.FARMER))) {
            return generatedLoot;
        }
        FarmerHarvests.multiplyProduce(generatedLoot, state, tier);
        return generatedLoot;
    }

    @Override
    public Codec<? extends IGlobalLootModifier> codec() {
        return CODEC;
    }
}
