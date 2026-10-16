package com.miningdim.job.farmer.granary;

import com.miningdim.freshness.FreshnessCategories;
import com.miningdim.freshness.FreshnessData;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 待领粮单 (Spoilage_Granary_DesignSpec「退出、拆走、移出」「额度怎么记账」)。
 *
 * 囤主的东西在「自己退出 / 打包 / 被移出 / 囤被别人拆走」时**从不掉在地上**, 而是连同那个囤一起
 * 进这张单子: 房主拿不走、别人捡不到, 只有囤主本人用 {@code /granary claim} 分批领回。
 *
 * 三条规矩:
 * <ul>
 *   <li><b>占额度</b>: 单子里每 27 格 (也就是每个囤) 占囤主 1 个储粮囤额度 —— 这是反漏洞 #65 的关键,
 *       否则「让朋友把自己移出、额度空出来再放新囤」就成了无限冰箱; 领完 (条目空了) 才归还。</li>
 *   <li><b>东西原样</b>: 保鲜章 (批次时刻 h 与已停表小时 f) 随物品一起进出, 领回来接着按原样算,
 *       不重新盖章也不算「迁移货」。</li>
 *   <li><b>烂透的自动清掉</b>: 腐烂满 30 天的自动销毁并记日志 (规格「待领粮单怎么领」), 不是无限仓库。</li>
 * </ul>
 *
 * 登记与领取都走这里, 存档落在 {@link GranarySavedData} 的 {@code Pending} 表上 (账本才是权威)。
 *
 * 这一片只做**单子本身** (账、额度占用、领取、自动清理); 「谁把东西送进来」以及规格里「被移出的人
 * 在宽限期内照常停表、自己打包的不停表」这两条随搬运那一批落地 —— 在那之前, 单子里的东西按物品上
 * 原有的保鲜章继续走, 不额外停表。
 */
public final class GranaryPending {

    /** 腐烂满这么多小时自动清掉。 */
    public static final int AUTO_CLEAR_ROTTEN_HOURS = 30 * 24;

    /** 一个待领条目从哪来 (上线提醒与命令回执要说清「这是怎么没的」)。 */
    public enum Reason {
        /** 自己退出粮仓 ({@code /granary leave})。 */
        LEFT("granary.pending.reason.left"),
        /** 自己远程打包 ({@code /granary pack})。 */
        PACKED("granary.pending.reason.packed"),
        /** 被房主移出成员名单。 */
        REMOVED("granary.pending.reason.removed"),
        /** 囤被别人拆走 (房主拆未入册的空囤 / OP 强拆)。 */
        BROKEN("granary.pending.reason.broken");

        private final String translationKey;

        Reason(String translationKey) {
            this.translationKey = translationKey;
        }

        public String translationKey() {
            return translationKey;
        }
    }

    /**
     * 一张待领条目 = 一个囤的东西。
     *
     * 条目本身就是额度的计量单位 (每个条目占 1 个囤), 所以「条目空了」与「额度归还」是同一件事。
     *
     * @param gid    原来在哪座粮仓 (0 = 没连上粮仓的散囤)
     * @param reason 怎么进单子的
     * @param atHour 进单子的时刻 (挂钟小时)
     * @param items  里面的东西 (含保鲜章, 原样进出)
     */
    public record Entry(long gid, Reason reason, long atHour, List<ItemStack> items) {

        /**
         * 条目**自己持有一份拷贝**。
         *
         * 领取与清理都会动栈 (shrink / 换新栈), 若直接引用调用方的栈, 搬运那一步 (从方块实体里取出来)
         * 就会把方块实体里的东西一并改掉 —— 账本必须自己拿着, 谁也别想再动它。
         */
        public Entry {
            List<ItemStack> owned = new ArrayList<>(items.size());
            for (ItemStack stack : items) {
                if (!stack.isEmpty()) {
                    owned.add(stack.copy());
                }
            }
            items = List.copyOf(owned);
        }

        public boolean isEmpty() {
            return items.isEmpty();
        }

        /** 剩下的件数 (命令回执用)。 */
        public int count() {
            int total = 0;
            for (ItemStack stack : items) {
                total += stack.getCount();
            }
            return total;
        }

        public CompoundTag save() {
            CompoundTag tag = new CompoundTag();
            tag.putLong(GranaryConstants.KEY_GID, gid);
            tag.putString(GranaryConstants.KEY_REASON, reason.name());
            tag.putLong(GranaryConstants.KEY_AT, atHour);
            ListTag items = new ListTag();
            for (ItemStack stack : this.items) {
                if (!stack.isEmpty()) {
                    items.add(stack.save(new CompoundTag()));
                }
            }
            tag.put(GranaryConstants.KEY_ITEMS, items);
            return tag;
        }

        public static Entry load(CompoundTag tag) {
            if (!tag.contains(GranaryConstants.KEY_GID, Tag.TAG_LONG)
                    || !tag.contains(GranaryConstants.KEY_REASON, Tag.TAG_STRING)
                    || !tag.contains(GranaryConstants.KEY_AT, Tag.TAG_LONG)) {
                throw new IllegalArgumentException("Invalid granary pending entry");
            }
            Reason reason = parseReason(tag.getString(GranaryConstants.KEY_REASON));
            ListTag items = tag.getList(GranaryConstants.KEY_ITEMS, Tag.TAG_COMPOUND);
            List<ItemStack> stacks = new ArrayList<>(items.size());
            for (int i = 0; i < items.size(); i++) {
                ItemStack stack = ItemStack.of(items.getCompound(i));
                if (!stack.isEmpty()) {
                    stacks.add(stack);
                }
            }
            return new Entry(tag.getLong(GranaryConstants.KEY_GID), reason,
                    tag.getLong(GranaryConstants.KEY_AT), stacks);
        }
    }

    /**
     * 一次领取的结果。
     *
     * @param claimed     这次领到手的件数
     * @param claimedBins 这次领空的条目数 (= 归还的囤数)
     * @param remaining   还剩几个囤的东西在单子上 (含这次没领完的那一条)
     */
    public record ClaimResult(int claimed, int claimedBins, int remaining) {
    }

    private GranaryPending() {
    }

    /** 存档里的原因名认不出来就是坏档, 直接报错而不是悄悄按「自己退出」放过去。 */
    private static Reason parseReason(String name) {
        for (Reason candidate : Reason.values()) {
            if (candidate.name().equals(name)) {
                return candidate;
            }
        }
        throw new IllegalArgumentException("Invalid granary pending reason: " + name);
    }

    /** 这位玩家的单子上有几个囤 (额度占用)。 */
    public static int binsOwed(GranarySavedData data, UUID player) {
        return data.pendingOf(player).size();
    }

    /**
     * 把东西放进某人的待领粮单。
     *
     * @return 新条目的下标
     */
    public static int put(GranarySavedData data, UUID player, long gid, Reason reason, long atHour,
                          List<ItemStack> items) {
        return data.putPending(player, new Entry(gid, reason, atHour, items));
    }

    /**
     * 领取: 按先进先出把东西放进背包, 放不下的留在单子上。
     *
     * 一件一件地塞, 所以「背包只剩半格」不会把整堆吞掉 —— 领走多少算多少, 剩下的下次再领。
     */
    public static ClaimResult claim(GranarySavedData data, ServerPlayer player, long nowMinute) {
        List<Entry> entries = data.pendingOf(player.getUUID());
        int claimed = 0;
        int claimedBins = 0;
        List<Entry> kept = new ArrayList<>(entries.size());
        for (Entry entry : entries) {
            List<ItemStack> leftover = new ArrayList<>();
            for (ItemStack stack : entry.items()) {
                // 领之前先清掉烂透的: 玩家拿到手的永远不是「早就该销毁」的东西。
                if (isAutoCleared(stack, nowMinute)) {
                    continue;
                }
                claimed += insert(player, stack);
                if (!stack.isEmpty()) {
                    leftover.add(stack);
                }
            }
            if (leftover.isEmpty()) {
                claimedBins++;
            } else {
                kept.add(new Entry(entry.gid(), entry.reason(), entry.atHour(), leftover));
            }
        }
        data.setPending(player.getUUID(), kept);
        return new ClaimResult(claimed, claimedBins, kept.size());
    }

    /**
     * 清掉烂透的东西 (自动销毁, 返回销毁的件数与清空掉的条目数)。
     *
     * 上线与领取前各跑一次: 单子不该是「永远领得回来」的仓库, 烂满 30 天就该没。
     */
    public static PurgeResult purge(GranarySavedData data, UUID player, long nowMinute) {
        List<Entry> entries = data.pendingOf(player);
        if (entries.isEmpty()) {
            return PurgeResult.NONE;
        }
        int destroyed = 0;
        int clearedBins = 0;
        List<Entry> kept = new ArrayList<>(entries.size());
        for (Entry entry : entries) {
            List<ItemStack> leftover = new ArrayList<>();
            for (ItemStack stack : entry.items()) {
                if (isAutoCleared(stack, nowMinute)) {
                    destroyed += stack.getCount();
                    continue;
                }
                leftover.add(stack);
            }
            if (leftover.isEmpty()) {
                clearedBins++;
            } else {
                kept.add(new Entry(entry.gid(), entry.reason(), entry.atHour(), leftover));
            }
        }
        if (destroyed == 0 && clearedBins == 0) {
            return PurgeResult.NONE;
        }
        data.setPending(player, kept);
        return new PurgeResult(destroyed, clearedBins);
    }

    /**
     * 这东西是不是「烂满 30 天、该自动清掉」。
     *
     * 判据是**烂了多久**: 剩余时间已经负到 30 天以上就是它。不腐烂的东西永远不会被清掉 ——
     * 单子里也可能躺着不腐烂的杂物 (玩家把整箱东西塞进囤再被移出)。
     */
    public static boolean isAutoCleared(ItemStack stack, long nowMinute) {
        if (stack.isEmpty()) {
            return true;
        }
        int shelfLife = FreshnessCategories.shelfLifeHours(stack);
        if (shelfLife <= 0) {
            return false;
        }
        long remaining = FreshnessData.remainingMinutes(stack, shelfLife, nowMinute);
        return remaining <= -(long) AUTO_CLEAR_ROTTEN_HOURS * 60L;
    }

    /**
     * 往背包里塞 (先并进同类同章的堆, 再找空格), 剩下的留在传入的栈里。
     *
     * 不调原版的 {@code Inventory.add}: 那条路在创造模式下会把塞不下的东西直接吞掉 (玩家什么都没拿到,
     * 单子上的东西却没了), 而领东西这条路「宁可剩着也不能蒸发」。
     *
     * @return 实际塞进去的件数
     */
    private static int insert(ServerPlayer player, ItemStack stack) {
        Inventory inventory = player.getInventory();
        int moved = 0;
        for (int pass = 0; pass < 2 && !stack.isEmpty(); pass++) {
            for (int slot = 0; slot < Inventory.INVENTORY_SIZE && !stack.isEmpty(); slot++) {
                ItemStack existing = inventory.getItem(slot);
                if (pass == 0) {
                    if (existing.isEmpty() || !ItemStack.isSameItemSameTags(existing, stack)) {
                        continue;
                    }
                    int room = Math.min(existing.getMaxStackSize(), 64) - existing.getCount();
                    int take = Math.min(room, stack.getCount());
                    if (take <= 0) {
                        continue;
                    }
                    existing.grow(take);
                    stack.shrink(take);
                    moved += take;
                } else if (existing.isEmpty()) {
                    int take = Math.min(stack.getMaxStackSize(), stack.getCount());
                    ItemStack placed = stack.copy();
                    placed.setCount(take);
                    inventory.setItem(slot, placed);
                    stack.shrink(take);
                    moved += take;
                }
            }
        }
        if (moved > 0) {
            inventory.setChanged();
        }
        return moved;
    }

    /**
     * 腐烂满 30 天被清掉的东西。
     *
     * @param destroyed   销毁的件数
     * @param clearedBins 因此清空的条目数 (= 归还的囤数)
     */
    public record PurgeResult(int destroyed, int clearedBins) {

        public static final PurgeResult NONE = new PurgeResult(0, 0);

        public boolean changed() {
            return destroyed > 0 || clearedBins > 0;
        }
    }
}
