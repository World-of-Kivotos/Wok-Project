package com.miningdim.job.chef;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.food.FoodData;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

/**
 * 耐饥减饥饿衰减 (Chef_Job_DesignSpec 第十一章: 减饥饿衰减, 窗口型)。
 *
 * 实现 (1.20.1 无 "降低 exhaustion 累积" 的事件钩子, exhaustion 字段不可读): 在玩家 tick 周期性按耐饥比例
 * 回补饱和值, 净效果 = 饥饿衰减变慢 (饱和先消耗才掉饱食, 持续补饱和即延缓掉饱食)。回补量 = 耐饥比例 x 基准,
 * 仅当玩家饱食未满时补 (避免无意义溢出), 且不直接改 exhaustion (无私有字段访问)。
 *
 * 周期 {@value #INTERVAL_TICKS} tick 一次, 与窗口状态机解耦 (本类只读 active 比例)。
 */
public final class ChefHungerHandler {

    /** 回补周期 (tick): 每 2 秒补一次, 与原版饥饿衰减节律相当但反向。 */
    private static final int INTERVAL_TICKS = 40;
    /** 每周期基准回补饱和 (满耐饥 90% 时实际回补 = 基准 x 0.9)。 */
    private static final float BASE_SATURATION_REFILL = 1.0F;

    @SubscribeEvent
    public void onPlayerTick(TickEvent.PlayerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        if (!(event.player instanceof ServerPlayer player)) {
            return;
        }
        if (player.tickCount % INTERVAL_TICKS != 0) {
            return;
        }
        int reducePerMille = ChefWindowEffectState.hungerReducePerMille(player.getUUID());
        if (reducePerMille <= 0) {
            return;
        }
        FoodData food = player.getFoodData();
        // 仅在还有饱食可维持时回补饱和 (饱食=0 时回补无意义, 应直接吃东西)。
        if (food.getFoodLevel() <= 0) {
            return;
        }
        float refill = BASE_SATURATION_REFILL * (reducePerMille / 1000.0F);
        // 饱和不得超过当前饱食值 (原版约束: saturation <= foodLevel)。
        float maxSat = food.getFoodLevel();
        float newSat = Math.min(maxSat, food.getSaturationLevel() + refill);
        if (newSat > food.getSaturationLevel()) {
            food.setSaturation(newSat);
        }
    }
}
