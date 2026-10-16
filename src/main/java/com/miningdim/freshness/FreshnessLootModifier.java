package com.miningdim.freshness;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.storage.loot.LootContext;
import net.minecraft.world.level.storage.loot.predicates.LootItemCondition;
import net.minecraftforge.common.loot.IGlobalLootModifier;
import net.minecraftforge.common.loot.LootModifier;
import org.jetbrains.annotations.NotNull;

/**
 * 全局掉落修改器: 任何战利品生成的那一刻给易腐品盖章 (规格「观察点」第一行)。
 *
 * 覆盖方块 (含任意来源的收割)、实体掉落、宝箱、钓鱼与考古 —— 这些路径拿不到统一的玩家事件, 但都过战利品表,
 * 所以这里是「凭空冒出来的食物」的唯一收口 (反漏洞 #12: 机械收割机、Deployer 收的作物也在内)。
 *
 * 只盖章, 不换腐烂物: 刚生成的战利品不可能已经过期; 已经过期的东西由持有侧的观察点负责换掉。
 */
public final class FreshnessLootModifier extends LootModifier {

    public static final Codec<FreshnessLootModifier> CODEC = RecordCodecBuilder.create(
            instance -> codecStart(instance).apply(instance, FreshnessLootModifier::new));

    public FreshnessLootModifier(LootItemCondition[] conditions) {
        super(conditions);
    }

    @Override
    protected @NotNull ObjectArrayList<ItemStack> doApply(ObjectArrayList<ItemStack> generatedLoot,
                                                          LootContext context) {
        if (!FreshnessConfig.stampingEnabled()) {
            return generatedLoot;
        }
        long nowMinute = FreshnessClockData.nowMinute(context.getLevel());
        // 放置食物按记录继承 (规格「观察点」第一行): 摆成方块的食物拆下来还是原来那一批, 不是新货。
        java.util.Optional<FreshnessPlacedFood.Placed> placed = FreshnessPlacedFood.takeForLoot(context);
        for (ItemStack stack : generatedLoot) {
            if (stack.isEmpty() || !FreshnessCategories.spoils(stack)) {
                continue;
            }
            FreshnessData.clamp(stack, nowMinute);
            if (placed.isPresent()) {
                FreshnessData.stampAt(stack, placed.get().batchMinute(), placed.get().stoppedHours());
            } else {
                FreshnessData.stamp(stack, nowMinute);
            }
        }
        return generatedLoot;
    }

    @Override
    public Codec<? extends IGlobalLootModifier> codec() {
        return CODEC;
    }
}
