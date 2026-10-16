package com.miningdim.job.farmer.granary;

import com.miningdim.freshness.FreshnessCategories;
import com.miningdim.freshness.FreshnessCategory;
import com.miningdim.freshness.FreshnessClockData;
import com.miningdim.freshness.FreshnessData;
import com.miningdim.freshness.FreshMerge;
import com.miningdim.job.farmer.granary.block.GranaryBinBlockEntity;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;

/**
 * 「一键存入」(Spoilage_Granary_DesignSpec「一键存入」「防新货蹭老格子」)。
 *
 * 上学玩家唯一要记住的动作, 所以规矩要一次写对:
 * <ol>
 *   <li><b>只收易腐品</b>: 豁免品、不腐烂的东西、已经腐烂的东西、停表额度用完的东西一律跳过
 *       (规格「所有档都不收: 带厨师品质的菜、豁免品、已用完 21 天额度的东西、已经腐烂的东西」);</li>
 *   <li><b>粮棚不收生鲜</b> (冷库与官仓才收), 这一条按**档位**判;</li>
 *   <li><b>先合并再放</b>: 同种食物按 {@link FreshMerge} 的规则先合成一堆, 然后才进格子;</li>
 *   <li><b>防新货蹭老格子</b>: 已经有东西的格子只接受「本小时存入的同一批」追加, 其它新货只能进空格子
 *       —— 不然新货会把老格子的入仓时刻顶掉, 等于给老货续命;</li>
 *   <li><b>先结算再存</b>: 动手之前把这个囤结算到当前小时 (与「菜单开着每到整点先结算」同一条规矩);</li>
 *   <li>没盖过章的当场盖章, 于是它从这一刻开始算保质期。</li>
 * </ol>
 *
 * 能不能存由**账本**说了算 (囤的启用状态 + 粮仓是否完好), 不看方块实体上的那个镜像开关。
 * 界面做出来之后, 按钮直接调这里; 现在先用 {@code /granary deposit} 触发。
 */
public final class GranaryDeposit {

    /** 一次存入的结果 (提示与用例都读它)。 */
    public record Result(int items, int stacks, Skips skips, Rejection rejection) {

        public boolean accepted() {
            return rejection == Rejection.NONE;
        }
    }

    /** 被跳过的原因分类 (提示里逐条报, 不混成一个数)。 */
    public record Skips(int exhausted, int fresh, int rotten, int noRoom) {

        public int total() {
            return exhausted + fresh + rotten + noRoom;
        }
    }

    /** 为什么一件都没收下。 */
    public enum Rejection {
        /** 收下了 (可能只收了一部分)。 */
        NONE,
        /** 这个囤不是启用状态 (待启用 / 停用 / 没入册)。 */
        NOT_ACTIVE,
        /** 粮仓没验收或者损坏了。 */
        GRANARY_DOWN,
        /** 这个囤没开放给这位玩家 (私有囤, 或者名单上是别人)。 */
        NOT_ALLOWED
    }

    /** 保留快捷栏的开关在玩家 persistentData 里的键 (规格: 按玩家记住)。 */
    private static final String KEY_ROOT = "miningdim_granary";
    private static final String KEY_KEEP_HOTBAR = "KeepHotbar";

    private GranaryDeposit() {
    }

    // ============================================================
    // 保留快捷栏 (按玩家记住)
    // ============================================================

    /** 这位玩家的一键存入要不要跳过快捷栏。 */
    public static boolean keepsHotbar(Player player) {
        CompoundTag root = player.getPersistentData().getCompound(KEY_ROOT);
        return root.getBoolean(KEY_KEEP_HOTBAR);
    }

    public static void setKeepsHotbar(Player player, boolean keep) {
        CompoundTag root = player.getPersistentData().getCompound(KEY_ROOT);
        root.putBoolean(KEY_KEEP_HOTBAR, keep);
        player.getPersistentData().put(KEY_ROOT, root);
    }

    // ============================================================
    // 存入
    // ============================================================

    /** 一键存入一个囤 (默认连快捷栏一起存)。 */
    public static Result deposit(ServerLevel level, GranaryBinBlockEntity bin, Player player) {
        return deposit(level, bin, player, !keepsHotbar(player));
    }

    public static Result deposit(ServerLevel level, GranaryBinBlockEntity bin, Player player,
                                 boolean includeHotbar) {
        return depositAt(level, bin, player, includeHotbar, FreshnessClockData.nowMinute(level));
    }

    /**
     * 同上, 但把「现在」显式传进来 —— 传的是**分钟**, 小时数由它换算。
     *
     * 两者必须来自同一把钟: 盖章、判腐烂用分钟, 入仓时刻与批次规矩用小时; 分开传的话很容易出现
     * 「按 A 时刻判腐烂、按 B 时刻记入仓」这种只有用例才会踩到的错位。
     *
     * @param includeHotbar 连快捷栏一起存吗 (玩家可以打开「保留快捷栏」)
     */
    public static Result depositAt(ServerLevel level, GranaryBinBlockEntity bin, Player player,
                                   boolean includeHotbar, long nowMinute) {
        long nowHour = nowMinute / 60L;
        GranarySavedData data = GranarySavedData.get(level.getServer().overworld());
        ResourceLocation dimension = level.dimension().location();
        // 开放名单先判: 私有的囤除了囤主谁都存不进去 (房主也不例外, 反漏洞 #68)。
        if (!GranaryAccess.mayStore(bin.owner(), bin.access(), player.getUUID())) {
            return new Result(0, 0, new Skips(0, 0, 0, 0), Rejection.NOT_ALLOWED);
        }
        if (!data.binState(dimension, bin.getBlockPos()).acceptsDeposits()) {
            return new Result(0, 0, new Skips(0, 0, 0, 0), Rejection.NOT_ACTIVE);
        }
        GranaryRecord record = data.granary(data.granaryOfBin(dimension, bin.getBlockPos()));
        if (record == null || !record.acceptsDeposits()) {
            return new Result(0, 0, new Skips(0, 0, 0, 0), Rejection.GRANARY_DOWN);
        }
        GranaryTier tier = record.tier();

        // 先结算: 与「菜单开着每到整点先结算」同一条规矩, 免得后面的入仓时刻把老账冲掉。
        bin.settleAll(nowHour);

        List<Integer> slots = candidateSlots(player, includeHotbar);
        mergeFirst(player, slots);

        int items = 0;
        int stacks = 0;
        int exhausted = 0;
        int fresh = 0;
        int rotten = 0;
        for (int slot : slots) {
            ItemStack stack = player.getInventory().getItem(slot);
            if (stack.isEmpty() || !FreshnessCategories.spoils(stack) || FreshnessCategories.isExempt(stack)) {
                continue;
            }
            int shelfLifeHours = FreshnessCategories.shelfLifeHours(stack);
            if (FreshnessData.isRotten(stack, shelfLifeHours, nowMinute)) {
                rotten++;
                continue;
            }
            if (GranarySettle.capExhausted(stack)) {
                exhausted++;
                continue;
            }
            if (!tier.acceptsFresh() && isFresh(stack)) {
                fresh++;
                continue;
            }
            if (!FreshnessData.isStamped(stack)) {
                FreshnessData.stamp(stack, nowMinute);
            }
            int before = stack.getCount();
            int moved = insert(bin, stack, nowHour);
            if (moved > 0) {
                items += moved;
                stacks++;
            } else {
                // 一格都放不下 (囤满或被批次规矩挡住)。
                stack.setCount(before);
            }
        }
        return new Result(items, stacks, new Skips(exhausted, fresh, rotten, 0), Rejection.NONE);
    }

    // ============================================================
    // 内部
    // ============================================================

    /** 从背包里往囤里放一批; 返回实际放进去的件数。 */
    private static int insert(GranaryBinBlockEntity bin, ItemStack source, long nowHour) {
        int moved = 0;
        for (int slot = 0; slot < bin.getContainerSize() && !source.isEmpty(); slot++) {
            ItemStack existing = bin.getItem(slot);
            if (existing.isEmpty()) {
                int take = Math.min(source.getCount(), source.getMaxStackSize());
                bin.setItem(slot, source.copyWithCount(take));
                source.shrink(take);
                // 空格子: 入仓时刻就是现在 (这一格的本趟已计归零)。
                bin.markStored(slot, nowHour);
                moved += take;
                continue;
            }
            // 防新货蹭老格子: 只有「本小时存入的同一批」能追加 —— 老格子的入仓时刻不能被动。
            if (!ItemStack.isSameItemSameTags(existing, source) || bin.entryHour(slot) != nowHour) {
                continue;
            }
            int room = existing.getMaxStackSize() - existing.getCount();
            if (room <= 0) {
                continue;
            }
            int take = Math.min(room, source.getCount());
            existing.grow(take);
            source.shrink(take);
            moved += take;
        }
        return moved;
    }

    /** 一键存入覆盖的格子: 主背包 + (可选) 快捷栏 + 副手。 */
    private static List<Integer> candidateSlots(Player player, boolean includeHotbar) {
        Inventory inventory = player.getInventory();
        List<Integer> slots = new ArrayList<>();
        int mainStart = Inventory.getSelectionSize();                 // 0..8 是快捷栏
        for (int slot = mainStart; slot < 36; slot++) {
            slots.add(slot);
        }
        if (includeHotbar) {
            for (int slot = 0; slot < mainStart; slot++) {
                slots.add(slot);
            }
        }
        slots.add(40);                                                // 副手
        slots.removeIf(slot -> slot >= inventory.getContainerSize());
        return slots;
    }

    /** 先合并: 同种食物按「同种食物合并」的规则合成一堆, 再往囤里放。 */
    private static void mergeFirst(Player player, List<Integer> slots) {
        Inventory inventory = player.getInventory();
        for (int target : slots) {
            ItemStack into = inventory.getItem(target);
            if (into.isEmpty() || into.getCount() >= into.getMaxStackSize()) {
                continue;
            }
            for (int source : slots) {
                if (source == target) {
                    continue;
                }
                ItemStack from = inventory.getItem(source);
                if (from.isEmpty()) {
                    continue;
                }
                // 合并规则 (含「剩余相差不超过窗口」「停表额度相同」) 全在 FreshMerge 里, 这里不重复实现。
                FreshMerge.mergeInto(into, from);
            }
        }
    }

    private static boolean isFresh(ItemStack stack) {
        return FreshnessCategories.categoryOf(stack).orElse(null) == FreshnessCategory.FRESH;
    }
}
