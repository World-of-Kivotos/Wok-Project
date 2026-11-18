package com.miningdim.job.farmer.granary;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 粮仓流水 (Spoilage_Granary_DesignSpec「官仓」「官仓流水」)。
 *
 * 记的是**谁对哪个囤做了什么**: 存进去、取出来 (以及官仓公开模式下的捐粮/取用)。反漏洞 #70 要求
 * 「流水写明谁存谁取」—— 公开取用一旦开着, 光靠"每人每天限 N 组"挡不住小号, 还得有人能查。
 *
 * <p>每座粮仓保留最近 {@link #MAX_ENTRIES} 条 (规格: 官仓 300 条)。环形保留: 满了丢最旧的, 不做
 * 任何聚合 —— 聚合过的流水在追查时正好少掉需要的那一条。</p>
 */
public final class GranaryJournal {

    /** 每座粮仓保留几条 (规格「流水保留 300 条」)。 */
    public static final int MAX_ENTRIES = 300;

    /** 一条流水记的是哪一类动作。 */
    public enum Kind {
        /** 名单内 (或囤主) 存进去。 */
        STORE("granary.journal.store"),
        /** 名单内 (或囤主) 取出来。 */
        TAKE("granary.journal.take"),
        /** 官仓公开捐粮。 */
        DONATE("granary.journal.donate"),
        /** 官仓公开取用。 */
        PUBLIC_TAKE("granary.journal.public_take");

        private final String translationKey;

        Kind(String translationKey) {
            this.translationKey = translationKey;
        }

        public String translationKey() {
            return translationKey;
        }
    }

    /**
     * 一条流水。
     *
     * @param hour    记这一条的挂钟小时 (与粮仓其余账目同一把钟)
     * @param actor   谁做的 (按 UUID 记: 名字会改, UUID 不会)
     * @param kind    哪一类动作
     * @param bin     哪个囤 (维度 + 坐标)
     * @param itemKey 物品的描述键 (存键而不是存 id: 渲染时直接 translatable, 不必再查注册表)
     * @param count   几件
     */
    public record Entry(long hour, UUID actor, Kind kind, net.minecraft.resources.ResourceLocation dimension,
                        BlockPos bin, String itemKey, int count) {

        public CompoundTag save() {
            CompoundTag tag = new CompoundTag();
            tag.putLong(GranaryConstants.KEY_HOUR, hour);
            tag.putUUID(GranaryConstants.KEY_PLAYER, actor);
            tag.putString(GranaryConstants.KEY_KIND, kind.name());
            tag.putString(GranaryConstants.KEY_DIMENSION, dimension.toString());
            tag.putLong(GranaryConstants.KEY_POS, bin.asLong());
            tag.putString(GranaryConstants.KEY_ITEM, itemKey);
            tag.putInt(GranaryConstants.KEY_COUNT, count);
            return tag;
        }

        /** 读一条; 认不出的动作与缺项一律报错 (流水是**证据**, 读成半份比不读更糟)。 */
        public static Entry load(CompoundTag tag) {
            if (!tag.hasUUID(GranaryConstants.KEY_PLAYER)
                    || !tag.contains(GranaryConstants.KEY_DIMENSION, Tag.TAG_STRING)
                    || !tag.contains(GranaryConstants.KEY_POS, Tag.TAG_LONG)
                    || !tag.contains(GranaryConstants.KEY_ITEM, Tag.TAG_STRING)
                    || !tag.contains(GranaryConstants.KEY_COUNT, Tag.TAG_INT)
                    || !tag.contains(GranaryConstants.KEY_HOUR, Tag.TAG_LONG)) {
                throw new IllegalArgumentException("Incomplete granary journal entry");
            }
            return new Entry(tag.getLong(GranaryConstants.KEY_HOUR),
                    tag.getUUID(GranaryConstants.KEY_PLAYER),
                    parseKind(tag.getString(GranaryConstants.KEY_KIND)),
                    new net.minecraft.resources.ResourceLocation(tag.getString(GranaryConstants.KEY_DIMENSION)),
                    BlockPos.of(tag.getLong(GranaryConstants.KEY_POS)),
                    tag.getString(GranaryConstants.KEY_ITEM),
                    tag.getInt(GranaryConstants.KEY_COUNT));
        }

        private static Kind parseKind(String name) {
            for (Kind candidate : Kind.values()) {
                if (candidate.name().equals(name)) {
                    return candidate;
                }
            }
            throw new IllegalArgumentException("Invalid granary journal kind: " + name);
        }
    }

    private GranaryJournal() {
    }

    /** 一件东西记得住的流水行 (物品描述键 + 件数)。 */
    public static Entry entry(ServerLevel level, UUID actor, Kind kind, BlockPos bin, ItemStack stack, int count) {
        return new Entry(GranaryClock.nowHour(level), actor, kind, level.dimension().location(),
                bin.immutable(), stack.getItem().getDescriptionId(), count);
    }

    /**
     * 记一条流水 (位置 -> 它所在的粮仓)。
     *
     * 不在任何粮仓里的散囤不记: 流水是"这座粮仓"的账, 没有粮仓就没有账本可写。
     */
    public static void record(ServerLevel level, BlockPos bin, UUID actor, Kind kind, ItemStack stack, int count) {
        if (level == null || bin == null || actor == null || stack.isEmpty() || count <= 0) {
            return;
        }
        GranarySavedData data = GranarySavedData.get(level.getServer().overworld());
        long gid = data.granaryOfBin(level.dimension().location(), bin);
        if (gid <= 0L) {
            return;
        }
        data.appendJournal(gid, entry(level, actor, kind, bin, stack, count));
    }

    /** 追加一条并保持上限; 返回保留后的列表 (调用方负责写回账本)。 */
    public static List<Entry> append(List<Entry> entries, Entry entry) {
        List<Entry> copy = new ArrayList<>(entries == null ? List.of() : entries);
        copy.add(entry);
        while (copy.size() > MAX_ENTRIES) {
            copy.remove(0);
        }
        return copy;
    }

    public static ListTag save(List<Entry> entries) {
        ListTag tag = new ListTag();
        for (Entry entry : entries) {
            tag.add(entry.save());
        }
        return tag;
    }

    /** 读一张流水表; 超过上限的只留最新的那几条 (上限是硬规矩, 不是坏档)。 */
    public static List<Entry> load(ListTag tag) {
        List<Entry> entries = new ArrayList<>(Math.min(tag.size(), MAX_ENTRIES));
        int start = Math.max(0, tag.size() - MAX_ENTRIES);
        for (int i = start; i < tag.size(); i++) {
            entries.add(Entry.load(tag.getCompound(i)));
        }
        return entries;
    }
}
