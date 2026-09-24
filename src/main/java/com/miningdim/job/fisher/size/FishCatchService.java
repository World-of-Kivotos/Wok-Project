package com.miningdim.job.fisher.size;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;

import java.util.List;
import java.util.function.Function;

/**
 * 成功钓获后的体型结算: 逐栈掷体长/体重, 给物品打体型档标签, 记入玩家个人记录, 并在动作栏回报本次尺寸。
 *
 * 只由两条"确定会落地"的钓获路径调用:
 * <ul>
 *   <li>原版钓竿: {@code ItemFishedEvent} 最低优先级、未取消时 (事件拿到的是与实际掉落同一批 ItemStack 引用,
 *       原地打标签即作用于生成的掉落物; 矿石鱼替换在事件之前完成, 所以矿石鱼也会被测量);</li>
 *   <li>Tide 钓竿: {@code TideOreFishMixin} 注入点, 小游戏判定成功之后、生成掉落物之前 (Tide 不发
 *       {@code ItemFishedEvent})。</li>
 * </ul>
 * 生物掉落、箱子战利品、交易与 /give 得到的鱼一律不测量, 按标准档处理, 刷怪/鱼桶农场因此产不出奖杯。
 * 没有体型档案的物品 (垃圾、宝藏、木箱) 直接跳过; 已有体型标签的栈不重复结算。
 */
public final class FishCatchService {
    private FishCatchService() {
    }

    public static int onSuccessfulCatch(ServerPlayer player, List<ItemStack> catches) {
        return onSuccessfulCatch(player, catches, profile -> FishSizeRoller.roll(profile, player.getRandom()));
    }

    /** 测量器可注入, 供 GameTest 固定分位; 返回本次实际测量的栈数。 */
    public static int onSuccessfulCatch(ServerPlayer player, List<ItemStack> catches,
                                        Function<FishSizeProfile, FishMeasurement> measurer) {
        int measured = 0;
        long now = player.serverLevel().getGameTime();
        for (ItemStack stack : catches) {
            if (stack.isEmpty() || FishSizeNbt.isMeasured(stack)) {
                continue;
            }
            FishSizeProfile profile = FishSizeCatalog.INSTANCE.profile(stack);
            if (profile == null) {
                continue;
            }
            FishMeasurement measurement = measurer.apply(profile);
            FishSizeNbt.stamp(stack, measurement, player.getUUID(), player.getGameProfile().getName(), now);
            boolean newBest = FishingRecords.record(player, profile.itemId(), measurement, stack.getCount());
            notifyCatch(player, stack, measurement, newBest);
            measured++;
        }
        return measured;
    }

    private static void notifyCatch(ServerPlayer player, ItemStack stack, FishMeasurement measurement, boolean newBest) {
        MutableComponent fishName = Component.empty().append(stack.getHoverName())
                .withStyle(stack.getRarity().getStyleModifier());
        String length = FishSizeFormat.length(measurement.lengthMm());
        String weight = FishSizeFormat.weight(measurement.weightMg());
        MutableComponent message = Component.translatable("message.miningdim.fishing.catch",
                fishName, length, weight, measurement.sizeClass().displayName());
        if (newBest) {
            message.append(" ").append(Component.translatable("message.miningdim.fishing.catch.record")
                    .withStyle(ChatFormatting.GOLD));
        }
        player.displayClientMessage(message, true);
        if (measurement.sizeClass() == FishSizeClass.TROPHY) {
            player.sendSystemMessage(Component.translatable("message.miningdim.fishing.catch.trophy",
                    fishName, length, weight).withStyle(ChatFormatting.GOLD));
        }
    }
}
