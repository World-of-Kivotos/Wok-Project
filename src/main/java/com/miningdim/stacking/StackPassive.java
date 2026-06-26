package com.miningdim.stacking;

import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.animal.Chicken;
import net.minecraft.world.entity.animal.Cow;
import net.minecraft.world.entity.animal.Sheep;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.ShearsItem;
import net.minecraft.world.level.ItemLike;
import net.minecraft.world.level.block.Blocks;
import net.minecraftforge.event.entity.living.LivingEvent;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * 被动产出倍增 (需求规格 FR-3; AC-5 / AC-6)。三类被动产出对堆叠数 N 的实体按 "N 个独立个体" 语义结算, 严禁因堆叠
 * 出现免冷却或无限产出 (FR-3.4)。
 *
 * 剪毛 (FR-3.1, {@link PlayerInteractEvent.EntityInteract}): 对堆叠 N 羊单次剪毛, 取消原版单只剪毛, 改产
 *   Sigma(每只 1~3 随机) 份对应色羊毛, 并把这单一实体 (代表 N 只) 置 sheared=true —— N 只同时进 "已剪" 态
 *   (羊无时间冷却, 已剪态即冷却态; 重新长毛靠吃草, 与原版同语义)。
 *
 * 挤奶 (FR-3.2, EntityInteract): 对堆叠 N 牛, 手持空桶单次交互消耗 min(背包空桶数, N) 个空桶, 产等量奶桶; 空桶
 *   不足时按实际空桶数产出, 余量不产。取消原版单桶挤奶。
 *
 * 产蛋 (FR-3.3, {@link LivingEvent.LivingTickEvent}): 堆叠 N 鸡的下蛋计时按 N 个体并行结算 —— 每 server tick 额外
 *   扣 (N-1) 点 eggTime, 令原版下蛋计时 (vanilla 每 tick 扣 1) 实际以 N 倍速推进, 单位时间产蛋吞吐 = N x 单鸡速率。
 *   这是 "N 个并行计时器" 的最简实现 (不免冷却: 仍按完整 6000~12000 tick 周期, 只是 N 个个体的计时合并到一个实体上
 *   并行跑); 删此加速则吞吐退回 1x, AC-6 必挂。
 *
 * 线程 (NFR-5): 三事件均在服务端主线程触发; 仅服务端 (level !isClientSide) 结算, 客户端放行不改状态。
 *
 * 纯核心 ({@link #computeShearDrops} / {@link #computeMilkOutput} / {@link #eggTimerDecrement}) 与事件 handler 分离,
 * 供 GameTest 直接驱动断言 (AC-5 羊毛数 = Sigma(N 次 1~3); AC-6 产蛋 N 倍速)。
 */
public final class StackPassive {

    /** 由 {@link StackingSystem#register} 实例化并注册到 forge bus (package-private: 仅本子系统装配)。 */
    StackPassive() {
    }

    /** 羊毛色 -> 对应羊毛方块物品 (与原版 Sheep.ITEM_BY_DYE 同表; 原版该表 private, 此处按规格 FR-3.1 显式重建)。 */
    private static final Map<DyeColor, ItemLike> WOOL_BY_COLOR = buildWoolTable();

    // ============================================================
    // FR-3.1 剪毛
    // ============================================================

    @SubscribeEvent
    public void onEntityInteractShear(PlayerInteractEvent.EntityInteract event) {
        if (!StackingConfig.PASSIVE_SHEAR_ENABLED.get()) {
            return;
        }
        Player player = event.getEntity();
        if (player.level().isClientSide) {
            return;
        }
        if (!(event.getTarget() instanceof Sheep sheep)) {
            return;
        }
        ItemStack tool = event.getItemStack();
        if (!(tool.getItem() instanceof ShearsItem)) {
            return;
        }
        // 仅对真正堆叠 (N>1) 且可剪 (未剪/成年/可堆叠) 的羊接管; 否则放行原版 (单只剪毛 / 已剪不可剪)。
        int stackSize = StackData.getStackSize(sheep);
        if (stackSize <= 1 || !StackMerge.canStack(sheep) || !sheep.readyForShearing()) {
            return;
        }

        List<ItemStack> wool = computeShearDrops(sheep, stackSize);
        for (ItemStack stack : wool) {
            // spawnAtLocation 在羊脚下落地, 与原版剪毛掉落位一致。
            sheep.spawnAtLocation(stack, 1.0F);
        }
        // N 只同时进已剪态 (FR-3.1): 单一实体置 sheared, 代表的 N 只全部已剪。
        sheep.setSheared(true);
        tool.hurtAndBreak(1, player, p -> p.broadcastBreakEvent(event.getHand()));

        event.setCanceled(true);
        event.setCancellationResult(InteractionResult.SUCCESS);
    }

    /**
     * 计算堆叠 N 羊单次剪毛的羊毛产出 (FR-3.1): N 次独立 (每只 1~3 随机) 之和, 按羊当前色一只一只 roll, 再归并成
     * 若干 ItemStack (同色, 数量为 sum)。用羊自身 random 保持与原版同分布。
     *
     * @return 羊毛 ItemStack 列表 (同色, 数量 = Sigma(N 次 nextInt(3)+1)); 总数恒落 [N, 3N]。
     */
    public static List<ItemStack> computeShearDrops(Sheep sheep, int stackSize) {
        if (stackSize < 1) {
            throw new IllegalArgumentException("shear stack size must be >= 1, got " + stackSize);
        }
        ItemLike woolItem = WOOL_BY_COLOR.get(sheep.getColor());
        int total = 0;
        for (int i = 0; i < stackSize; i++) {
            // 原版单只剪毛产 1 + random.nextInt(3) (即 1~3)。逐只独立 roll (FR-3.1 "Sigma(每只 1~3 随机)")。
            total += 1 + sheep.getRandom().nextInt(3);
        }
        List<ItemStack> out = new ArrayList<>();
        int remaining = total;
        ItemStack proto = new ItemStack(woolItem);
        int max = proto.getMaxStackSize();
        while (remaining > 0) {
            int batch = Math.min(remaining, max);
            ItemStack chunk = new ItemStack(woolItem, batch);
            out.add(chunk);
            remaining -= batch;
        }
        return out;
    }

    // ============================================================
    // FR-3.2 挤奶
    // ============================================================

    @SubscribeEvent
    public void onEntityInteractMilk(PlayerInteractEvent.EntityInteract event) {
        if (!StackingConfig.PASSIVE_MILK_ENABLED.get()) {
            return;
        }
        Player player = event.getEntity();
        if (player.level().isClientSide) {
            return;
        }
        if (!(event.getTarget() instanceof Cow cow)) {
            return;
        }
        // MushroomCow 是 Cow 子类但右键空桶产蘑菇汤/挤奶分支不同; 规格 FR-3.2 仅点名普通牛奶, 故仅接管纯 Cow。
        if (cow.getClass() != Cow.class) {
            return;
        }
        ItemStack held = event.getItemStack();
        if (!held.is(Items.BUCKET)) {
            return;
        }
        if (cow.isBaby()) {
            return; // 幼牛不可挤奶 (原版 Cow.mobInteract 限 !isBaby)。
        }
        int stackSize = StackData.getStackSize(cow);
        if (stackSize <= 1 || !StackMerge.canStack(cow)) {
            return;
        }

        InteractionHand hand = event.getHand();
        int produced = doMilk(player, hand, stackSize);
        if (produced <= 0) {
            // 无空桶可用 (生存模式背包无空桶) -> 不接管, 放行原版 (原版同样产不出奶, 玩家手持空桶无果)。
            return;
        }
        event.setCanceled(true);
        event.setCancellationResult(InteractionResult.SUCCESS);
    }

    /**
     * 执行挤奶 (FR-3.2): 消耗 min(背包空桶数, N) 个空桶, 给玩家发等量奶桶。返回实际产出奶桶数。
     *
     * 创造模式: 与原版创造挤奶一致 —— 不耗桶、只给 1 桶奶 (不按 N 刷物, 防创造造成经济异常)。返回 1 表示接管成功。
     */
    private int doMilk(Player player, InteractionHand hand, int stackSize) {
        boolean creative = player.getAbilities().instabuild;
        if (creative) {
            player.playSound(net.minecraft.sounds.SoundEvents.COW_MILK, 1.0F, 1.0F);
            ItemStack milk = new ItemStack(Items.MILK_BUCKET);
            if (!player.addItem(milk)) {
                player.drop(milk, false);
            }
            return 1;
        }
        int produced = computeMilkOutput(countEmptyBuckets(player), stackSize);
        if (produced <= 0) {
            return 0;
        }
        player.playSound(net.minecraft.sounds.SoundEvents.COW_MILK, 1.0F, 1.0F);
        consumeEmptyBuckets(player, hand, produced);
        for (int i = 0; i < produced; i++) {
            ItemStack milk = new ItemStack(Items.MILK_BUCKET);
            if (!player.addItem(milk)) {
                player.drop(milk, false);
            }
        }
        return produced;
    }

    /**
     * 挤奶产出数 = min(空桶数, N) (FR-3.2): 空桶不足时按实际空桶产, 余量不产。纯函数, 供测试直接断言。
     *
     * @param emptyBuckets 玩家背包空桶总数 (含主手那只)
     * @param stackSize    牛堆叠数 N
     */
    public static int computeMilkOutput(int emptyBuckets, int stackSize) {
        if (stackSize < 1) {
            throw new IllegalArgumentException("milk stack size must be >= 1, got " + stackSize);
        }
        if (emptyBuckets < 0) {
            throw new IllegalArgumentException("empty bucket count must be >= 0, got " + emptyBuckets);
        }
        return Math.min(emptyBuckets, stackSize);
    }

    /** 数背包内空桶 (Items.BUCKET) 总数。 */
    private static int countEmptyBuckets(Player player) {
        int count = 0;
        for (ItemStack stack : player.getInventory().items) {
            if (stack.is(Items.BUCKET)) {
                count += stack.getCount();
            }
        }
        // offhand 也算 (玩家可能副手持桶交互)。
        for (ItemStack stack : player.getInventory().offhand) {
            if (stack.is(Items.BUCKET)) {
                count += stack.getCount();
            }
        }
        return count;
    }

    /** 从背包扣除 {@code count} 个空桶 (先扣交互手那只, 再扣其余)。调用方已保证背包空桶 >= count。 */
    private static void consumeEmptyBuckets(Player player, InteractionHand hand, int count) {
        int remaining = count;
        ItemStack inHand = player.getItemInHand(hand);
        if (inHand.is(Items.BUCKET)) {
            int take = Math.min(remaining, inHand.getCount());
            inHand.shrink(take);
            remaining -= take;
        }
        if (remaining <= 0) {
            return;
        }
        for (ItemStack stack : player.getInventory().items) {
            if (remaining <= 0) {
                break;
            }
            if (stack.is(Items.BUCKET)) {
                int take = Math.min(remaining, stack.getCount());
                stack.shrink(take);
                remaining -= take;
            }
        }
        for (ItemStack stack : player.getInventory().offhand) {
            if (remaining <= 0) {
                break;
            }
            if (stack.is(Items.BUCKET)) {
                int take = Math.min(remaining, stack.getCount());
                stack.shrink(take);
                remaining -= take;
            }
        }
        if (remaining > 0) {
            // 调用方保证够扣; 扣不完是状态不一致, 自然冒泡 (异常必痛, 不静默)。
            throw new IllegalStateException("not enough empty buckets to consume: short by " + remaining);
        }
    }

    // ============================================================
    // FR-3.3 产蛋
    // ============================================================

    @SubscribeEvent
    public void onChickenTick(LivingEvent.LivingTickEvent event) {
        if (!StackingConfig.PASSIVE_EGG_ENABLED.get()) {
            return;
        }
        LivingEntity entity = event.getEntity();
        if (entity.level().isClientSide) {
            return;
        }
        if (!(entity instanceof Chicken chicken)) {
            return;
        }
        int stackSize = StackData.getStackSize(chicken);
        if (stackSize <= 1 || !StackMerge.canStack(chicken)) {
            return;
        }
        if (chicken.isBaby() || chicken.isChickenJockey()) {
            return; // 原版幼鸡/鸡骑士不下蛋。
        }
        // 额外扣 (N-1): vanilla aiStep 本 tick 已扣 1, 合计每 tick 推进 N -> 产蛋吞吐 N 倍 (FR-3.3)。
        // 钳制在 1 以上, 避免抢在 vanilla 的 --eggTime <= 0 判定前把计时压到负值导致跳过本次下蛋。
        int decrement = eggTimerDecrement(stackSize);
        chicken.eggTime = Math.max(1, chicken.eggTime - decrement);
    }

    /**
     * 堆叠 N 鸡每 tick 应额外扣的 eggTime (FR-3.3): N-1 (vanilla 已扣 1, 合计 N)。纯函数, 供测试断言 N 倍速。
     */
    public static int eggTimerDecrement(int stackSize) {
        if (stackSize < 1) {
            throw new IllegalArgumentException("egg stack size must be >= 1, got " + stackSize);
        }
        return stackSize - 1;
    }

    private static Map<DyeColor, ItemLike> buildWoolTable() {
        Map<DyeColor, ItemLike> map = new EnumMap<>(DyeColor.class);
        map.put(DyeColor.WHITE, Blocks.WHITE_WOOL);
        map.put(DyeColor.ORANGE, Blocks.ORANGE_WOOL);
        map.put(DyeColor.MAGENTA, Blocks.MAGENTA_WOOL);
        map.put(DyeColor.LIGHT_BLUE, Blocks.LIGHT_BLUE_WOOL);
        map.put(DyeColor.YELLOW, Blocks.YELLOW_WOOL);
        map.put(DyeColor.LIME, Blocks.LIME_WOOL);
        map.put(DyeColor.PINK, Blocks.PINK_WOOL);
        map.put(DyeColor.GRAY, Blocks.GRAY_WOOL);
        map.put(DyeColor.LIGHT_GRAY, Blocks.LIGHT_GRAY_WOOL);
        map.put(DyeColor.CYAN, Blocks.CYAN_WOOL);
        map.put(DyeColor.PURPLE, Blocks.PURPLE_WOOL);
        map.put(DyeColor.BLUE, Blocks.BLUE_WOOL);
        map.put(DyeColor.BROWN, Blocks.BROWN_WOOL);
        map.put(DyeColor.GREEN, Blocks.GREEN_WOOL);
        map.put(DyeColor.RED, Blocks.RED_WOOL);
        map.put(DyeColor.BLACK, Blocks.BLACK_WOOL);
        return map;
    }
}
