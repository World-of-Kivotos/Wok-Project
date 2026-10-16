package com.miningdim.freshness;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;

/**
 * 嵌套容器下钻 (规格「消费点改造 · 市场」与「嵌套容器」一节)。
 *
 * 上架、浏览、购买、物品详情都要回答同一个问题: **这一件东西里面装了什么**。潜影盒套潜影盒、篮子、
 * FD 厨锅物品、Create 包裹都是「一件物品装着一堆物品」的形态 —— 只看最外层那一格的话, 把烂货塞进
 * 潜影盒就能绕过所有消费点。这里把「怎么读、能钻多深」收在一处, 免得每个消费点各写一套 NBT 路径。
 *
 * 契约:
 * <ul>
 *   <li>**只读**: {@link #flatten} / {@link #minRemainingMinutes} / {@link #anyRotten} /
 *       {@link #rottenSinceMinute} 都不改动物品;</li>
 *   <li>**读不出来就当没装东西**: 标签类型不对、物品 id 非法、深度超限都只是少读几层, 绝不抛异常
 *       (市场那条路上来的是玩家自己拼的 NBT, 抛异常等于让一个畸形潜影盒把上架请求打崩);</li>
 *   <li>**盖章是唯一会写的地方** ({@link #stampDeep}), 只给易腐品盖, 且幂等。</li>
 * </ul>
 *
 * 内容物存放键按「嵌套容器清单」逐个登记 (见 {@link #CONTENT_PATHS}): 覆盖原版方块实体容器
 * (潜影盒/箱子/篮子等 {@code BlockEntityTag.Items}) 与 Farmer's Delight 厨锅物品
 * ({@code BlockEntityTag.Inventory.Items})。Create 包裹这类还没进清单的容器等对到实际存档键再补一行。
 */
public final class FreshnessNested {

    private FreshnessNested() {
    }

    /**
     * 下钻层数上限: 本体之下最多再钻 {@value} 层。恶意套娃 (潜影盒套潜影盒套...) 不该让一次上架请求
     * 递归到栈溢出, 也不该让 6 小时的检查变成一次指数级遍历。
     */
    public static final int MAX_DEPTH = 3;

    /**
     * 各类嵌套容器的内容物路径 (相对物品根标签的键链, 末段是一个「槽位复合标签的列表」)。
     * 每一条都在此登记出处, 不写「猜的路径」: 猜错只会静默漏检, 比不检更危险。
     */
    private static final List<List<String>> CONTENT_PATHS = List.of(
            // 原版方块实体容器 (潜影盒、箱子、篮子这类「物品形态的容器」)
            List.of("BlockEntityTag", "Items"),
            // Farmer's Delight 厨锅/平底锅的物品形态: Inventory.Items
            List.of("BlockEntityTag", "Inventory", "Items"));

    /**
     * 这一件东西**本身 + 所有嵌套内容**, 展平成一个列表 (本体排第一, 之后按声明顺序与槽位序)。
     * 深度封顶 {@link #MAX_DEPTH} 层, 读不出来的层直接跳过。
     */
    public static List<ItemStack> flatten(ItemStack stack) {
        List<ItemStack> out = new ArrayList<>();
        collect(stack, MAX_DEPTH, out);
        return out;
    }

    private static void collect(ItemStack stack, int remainingDepth, List<ItemStack> out) {
        if (stack == null || stack.isEmpty()) {
            return;
        }
        out.add(stack);
        if (remainingDepth <= 0) {
            return;
        }
        for (ItemStack inner : contents(stack)) {
            collect(inner, remainingDepth - 1, out);
        }
    }

    /** 直接装在 {@code container} 里的东西 (一层, 不递归); 读不出来就是空列表。 */
    public static List<ItemStack> contents(ItemStack container) {
        List<ItemStack> out = new ArrayList<>();
        if (container == null || container.isEmpty() || !container.hasTag()) {
            return out;
        }
        CompoundTag root = container.getTag();
        for (List<String> path : CONTENT_PATHS) {
            ListTag slots = slotsAt(root, path);
            if (slots == null) {
                continue;
            }
            for (int index = 0; index < slots.size(); index++) {
                ItemStack inner = readSlot(slots, index);
                if (!inner.isEmpty()) {
                    out.add(inner);
                }
            }
        }
        return out;
    }

    /**
     * 按路径取槽位列表; 路径上任何一段缺失或类型不对都返回 null (当作这一个容器没装东西)。
     *
     * 只要末段是列表就收下, **不要求整张列表的元素类型都是复合标签**: 手改过的 NBT 可以往列表里插一行字符串,
     * 而"元素类型不符就整张列表不看"正好给了走私者一个把烂货藏进去的口子 (插一行垃圾, 后面那些装着烂货的
     * 复合标签就全被跳过了)。逐行判类型 (见 {@link #readSlot}) 既安全又读得全。
     */
    private static ListTag slotsAt(CompoundTag root, List<String> path) {
        Tag cursor = root;
        for (String key : path) {
            if (!(cursor instanceof CompoundTag compound)) {
                return null;
            }
            cursor = compound.get(key);
            if (cursor == null) {
                return null;
            }
        }
        return cursor instanceof ListTag list ? list : null;
    }

    /**
     * 把槽位复合标签读成物品; **读不出来返回空** (不是复合标签、id 非法、结构畸形都算), 绝不让畸形 NBT
     * 抛到调用方。
     */
    private static ItemStack readSlot(ListTag slots, int index) {
        // getCompound 对非复合标签的行返回空复合标签 (不抛), 于是"列表里混了一行字符串"只是少读一行。
        CompoundTag slotTag = slots.getCompound(index);
        if (slotTag.isEmpty() || !slotTag.contains("id", Tag.TAG_STRING)) {
            return ItemStack.EMPTY;
        }
        try {
            return ItemStack.of(slotTag);
        } catch (RuntimeException malformed) {
            return ItemStack.EMPTY;
        }
    }

    /**
     * 给本体与所有嵌套内容里的**易腐品**盖章 (幂等), 返回这次真盖了几件。
     *
     * 上架前调它: 市场托管期间东西照常腐烂 (反漏洞 #17), 所以盖章时刻就是托管起点 —— 不盖的话
     * 一件没章的货在托管期间「永不腐烂」, 等于把市场当冰箱用。
     */
    public static int stampDeep(ItemStack stack, long nowMinute) {
        return stampInto(stack, MAX_DEPTH, nowMinute);
    }

    /**
     * 递归盖章并**把每一层写回它的容器**。
     *
     * 为什么必须回写: {@link ItemStack#of(CompoundTag)} 读出来的槽位是**副本** (它的 tag 也是 copy 出来的),
     * 只给副本盖章的话, 存档里那份嵌套内容一个章都不会有 —— 看起来跑了、其实什么都没发生。
     * 所以每钻完一层就把它重新序列化回槽位。
     */
    private static int stampInto(ItemStack container, int remainingDepth, long nowMinute) {
        if (container == null || container.isEmpty()) {
            return 0;
        }
        int stamped = stampSelf(container, nowMinute) ? 1 : 0;
        if (remainingDepth <= 0 || !container.hasTag()) {
            return stamped;
        }
        CompoundTag root = container.getTag();
        for (List<String> path : CONTENT_PATHS) {
            ListTag slots = slotsAt(root, path);
            if (slots == null) {
                continue;
            }
            for (int index = 0; index < slots.size(); index++) {
                ItemStack inner = readSlot(slots, index);
                if (inner.isEmpty()) {
                    continue;
                }
                stamped += stampInto(inner, remainingDepth - 1, nowMinute);
                CompoundTag rewritten = new CompoundTag();
                inner.save(rewritten);
                slots.set(index, rewritten);
            }
        }
        return stamped;
    }

    /** 给一件东西自己盖章 (不是易腐品、已经盖过章就都不动), 返回是否真盖了。 */
    private static boolean stampSelf(ItemStack stack, long nowMinute) {
        if (!FreshnessCategories.spoils(stack) || FreshnessData.isStamped(stack)) {
            return false;
        }
        FreshnessData.stamp(stack, nowMinute);
        return true;
    }

    /**
     * 本体与所有嵌套内容里**最少的剩余分钟数** (不腐烂的层不参与)。没有任何易腐层时返回
     * {@link Long#MAX_VALUE}。已腐烂的层返回 ≤ 0 的值 —— 调用方据此拒绝上架/出售。
     */
    public static long minRemainingMinutes(ItemStack stack, long nowMinute) {
        long min = Long.MAX_VALUE;
        for (ItemStack layer : flatten(stack)) {
            long remaining = FreshnessCheck.remainingMinutes(layer, nowMinute);
            if (remaining < min) {
                min = remaining;
            }
        }
        return min;
    }

    /** 本体或任意一层嵌套内容是否**已经腐烂**。 */
    public static boolean anyRotten(ItemStack stack, long nowMinute) {
        for (ItemStack layer : flatten(stack)) {
            if (FreshnessCheck.isRottenNow(layer, nowMinute)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 最早腐烂的那一层**开始腐烂的时刻** (分钟序号); 没有腐烂层返回 {@link Long#MIN_VALUE}。
     *
     * 市场的「腐烂满 30 天的挂单自动关闭」用它: 一单被烂货拖了多久, 按**烂得最久**的那一层算,
     * 而不是按下架时间算 —— 上架时保证过每层都还剩 6 小时。
     */
    public static long rottenSinceMinute(ItemStack stack, long nowMinute) {
        long earliest = Long.MIN_VALUE;
        for (ItemStack layer : flatten(stack)) {
            long remaining = FreshnessCheck.remainingMinutes(layer, nowMinute);
            if (remaining > 0L) {
                continue;
            }
            long rottenSince = nowMinute + remaining;
            if (earliest == Long.MIN_VALUE || rottenSince < earliest) {
                earliest = rottenSince;
            }
        }
        return earliest;
    }
}
