package com.miningdim.stacking;

import net.minecraft.nbt.CompoundTag;
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
 *   Sigma(每只 1~3 随机) 份对应色羊毛, 并把 N 只个体全部置为 "待长毛" 态 (单一实体 sheared=true 代表全体已剪,
 *   同时记 N 笔恢复账)。恢复须靠吃草逐只结算 —— 每消耗一格草只让 1 只个体长回羊毛, 需要 N 次独立吃草 (N 格草)
 *   整堆才重新可剪; 这是 FR-3.4 "严禁因堆叠出现免冷却" 在剪毛上的落地 (原版一次吃草即整堆回满会把草地节流除以 N)。
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

    /**
     * 剪毛恢复账的持久化键 (F095 修复): 记录堆叠中还有多少只个体处于 "待长毛" 态。只由本文件的剪毛特性读写,
     * 不进 {@link StackData} —— 该文件的持久化键属于堆叠数本身, 恢复计数是剪毛特性私有的结算状态。
     */
    private static final String REGROW_KEY = "miningdim:ShearRegrowPending";

    /**
     * 读恢复账。缺键视为 0 (未处于恢复流程)。用当前 stackSize 钳制上限 —— 玩家从堆叠里拆出个体后 N 会变小,
     * 若此前记的 pending 仍是旧 N 下的值, 会虚高到超过当前堆叠规模; clamp 到 [0, stackSize] 是与拆分特性的
     * 隐式契约: 恢复账按新 N 自我纠偏, 不需要拆分那一侧联动改这个键。
     */
    private static int regrowPending(Sheep sheep, int stackSize) {
        CompoundTag tag = sheep.getPersistentData();
        if (!tag.contains(REGROW_KEY)) {
            return 0;
        }
        return Math.max(0, Math.min(tag.getInt(REGROW_KEY), stackSize));
    }

    /** 写恢复账。pending <= 0 视为账已清, 移除键 (而非写 0, 保持 "无键=未在恢复流程" 的缺键语义一致)。 */
    private static void setRegrowPending(Sheep sheep, int pending) {
        if (pending <= 0) {
            sheep.getPersistentData().remove(REGROW_KEY);
        } else {
            sheep.getPersistentData().putInt(REGROW_KEY, pending);
        }
    }

    /**
     * 是否仍有未走完的恢复账 (findings 2/4/6 第二条路径修复)。供 {@link StackMatchKey} 计算等价键时使用: 原版
     * {@code Sheep.ate()} 与本类的 {@link #onSheepRegrowTick} 都挂 {@code LivingTickEvent}, 而 ate() 由 AI 目标在
     * 同一 tick 内更晚触发 —— 每次真实吃草后, 有整整 1 tick 处于 "ate() 已把 isSheared() 翻成 false, 但本类尚未
     * 在下一次 regrowTick 里把它钳回 true" 的窗口。若周期扫描 (最长间隔 100 tick) 恰好落在这 1 tick 内, 单看
     * isSheared() 会把这只 "还剩恢复账没走完" 的羊误判成 "已完全长毛", 使其与真正满毛的羊同键、可能被合并 ——
     * 恢复账本身 (REGROW_KEY) 不受这 1 tick 观测滞后影响 (只有 regrowTick 的显式扣账才会改它), 故用
     * "isSheared() || 恢复账未清" 作为等价键计算的口径, 比单看 isSheared() 更鲁棒。
     */
    static boolean hasPendingRegrowLedger(Sheep sheep) {
        return sheep.getPersistentData().getInt(REGROW_KEY) > 0;
    }

    /**
     * 合并期恢复账结算 (findings 2/4/6 第一条路径修复): {@link StackMerge#mergeGroup} 在把 {@code other} 的
     * {@code moved} 只个体并入 {@code anchor} 之前调用本方法, 把 {@code other} 的恢复账按比例搬一份过去 ——
     * 修复前合并核心只对 {@link StackData} 求和, 从不碰 {@value #REGROW_KEY}, 被并方的恢复账随 discard 一并
     * 丢失, 使 "先分别剪毛再合并" 成为绕过 F095 免冷却节流的捷径。
     *
     * 双方进入这里前已经过 {@link StackMatchKey} 等价键判定为同组 (同色 + 同 "有效已剪态"), 故两侧的恢复账要么
     * 都为 0 (未剪), 要么都 >0 (已剪); 无论哪种, 直接按 moved/otherSize 比例搬账都是安全的纯数学操作, 无需在此
     * 重复判定 isSheared()。
     *
     * @param anchorSize / otherSize 合并前 (调用时刻) 各自的堆叠数, 用于恢复账的钳制上限与比例换算
     * @param moved                  本次实际并入 anchor 的个体数 (0 &lt; moved &lt;= otherSize)
     */
    static void mergeRegrowLedger(Sheep anchor, int anchorSize, Sheep other, int otherSize, int moved) {
        int anchorPending = regrowPending(anchor, anchorSize);
        int otherPending = regrowPending(other, otherSize);
        if (anchorPending <= 0 && otherPending <= 0) {
            return; // 双方均无待恢复账 (常见的 "未剪态合并" 路径), 无需搬账。
        }
        int movedPending;
        if (moved >= otherSize) {
            // other 整体并入 anchor: 其全部恢复账一并带走。
            movedPending = otherPending;
        } else {
            // other 仅部分并入 (anchor 满, FR-1.3 溢出另起堆叠): 按 moved/otherSize 比例拆账, 就近取整,
            // 避免恢复账在拆分后凭空放大或蒸发; 剩余部分留在 other 身上。
            movedPending = Math.round(otherPending * (moved / (float) otherSize));
            setRegrowPending(other, otherPending - movedPending);
        }
        setRegrowPending(anchor, anchorPending + movedPending);
    }

    /**
     * 把堆叠 (代表 N 只个体) 整体标记为已剪, 并开出 N 笔恢复账 —— 需要 N 次独立吃草才整堆恢复可剪 (FR-3.4)。
     * 剪毛 handler 与 GameTest 共用此入口, 避免 "标记已剪 + 开恢复账" 这两步在两处各写一份而漂移。
     */
    public static void markStackSheared(Sheep sheep, int stackSize) {
        sheep.setSheared(true);
        setRegrowPending(sheep, stackSize);
    }

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
        if (regrowPending(sheep, stackSize) > 0) {
            // 这堆还有个体没吃草长回羊毛 (F095): 恢复账未清就不能再收割一次, 否则等于免冷却二次剪毛。
            // 只 return 会放行原版 Forge onSheared 分支, 玩家仍能在这一帧白拿一次羊毛 —— 必须显式取消交互,
            // 并把显示态复位为已剪 (readyForShearing 在这条分支下理论上已是 false, 这里是防御性兜底)。
            sheep.setSheared(true);
            event.setCanceled(true);
            event.setCancellationResult(InteractionResult.FAIL);
            return;
        }

        List<ItemStack> wool = computeShearDrops(sheep, stackSize);
        for (ItemStack stack : wool) {
            // spawnAtLocation 在羊脚下落地, 与原版剪毛掉落位一致。
            sheep.spawnAtLocation(stack, 1.0F);
        }
        // N 只全部进入待长毛态, 并开出 N 笔恢复账 (FR-3.1 / FR-3.4)。
        markStackSheared(sheep, stackSize);
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

    /**
     * 羊毛恢复账推进 (F095): 逐 tick 检测原版 {@code Sheep.ate()} 是否刚把 sheared 翻回 false (即有 1 只个体
     * 吃草长回了羊毛), 据此扣减恢复账并把显示态钳回已剪, 直到 N 笔账全部走完才放行整堆恢复可剪。
     *
     * 与既有 {@link #onChickenTick} 并列的独立 tick handler, 同吃 {@link LivingEvent.LivingTickEvent}。
     */
    @SubscribeEvent
    public void onSheepRegrowTick(LivingEvent.LivingTickEvent event) {
        LivingEntity entity = event.getEntity();
        if (entity.level().isClientSide) {
            return;
        }
        if (!(entity instanceof Sheep sheep)) {
            return;
        }
        if (!StackingConfig.PASSIVE_SHEAR_ENABLED.get()) {
            return;
        }
        regrowTick(sheep);
    }

    /**
     * {@link #onSheepRegrowTick} 的纯逻辑主体, 供 GameTest 在没有 EatBlockGoal 可调度的环境下直接驱动断言。
     *
     * 故意不用 {@link StackMerge#canStack} 门控: 一堆羊若因被拴绳 / 命名等原因中途退出可合并集合, 它此前开出的
     * 恢复账仍必须走完 —— 否则这堆羊会永久卡在已剪态, 谁都无法再把它剪回可剪。
     *
     * @return true 表示本次 tick 消耗了一步恢复账 (即结算了一次吃草)
     */
    public static boolean regrowTick(Sheep sheep) {
        int stackSize = StackData.getStackSize(sheep);
        if (stackSize <= 1) {
            return false;
        }
        if (sheep.isSheared() && !sheep.getPersistentData().contains(REGROW_KEY)) {
            // 兜底补记: 发射器剪毛 (IForgeShearable.onSheared 不经 PlayerInteractEvent.EntityInteract)、其它 mod
            // 剪毛、或 "已剪的羊被合并成堆叠" 这三条入口都不经 onEntityInteractShear, 若不在此补记账, 这堆羊会
            // 呈已剪态却无恢复计数, 一次吃草即可整堆回满, 反而成了绕过 FR-3.4 节流的捷径。
            setRegrowPending(sheep, stackSize);
            return false;
        }
        int pending = regrowPending(sheep, stackSize);
        if (pending <= 0) {
            return false;
        }
        if (sheep.isSheared()) {
            return false; // 还没吃到草 (原版 ate() 未触发), 保持已剪态, 本 tick 无消耗。
        }
        // 走到这里说明原版 ate() 刚把 sheared 翻回 false: 有一只个体吃了一格草长回了羊毛。
        pending--;
        setRegrowPending(sheep, pending);
        if (pending > 0) {
            sheep.setSheared(true); // 还有个体没长回来, 整堆继续显示已剪、不可再剪。
        }
        // pending 归 0 时不再 setSheared —— 羊恢复到可剪态。
        return true;
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
