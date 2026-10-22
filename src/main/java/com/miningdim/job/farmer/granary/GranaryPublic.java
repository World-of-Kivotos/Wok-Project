package com.miningdim.job.farmer.granary;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 官仓公开模式 (Spoilage_Granary_DesignSpec「合建: 入册与权限」官仓独有那一条)。
 *
 * 只有**官仓**能开: 两个独立的开关 —— 「公开捐粮」(任何走得进房子的人都能存, 只收未腐烂的易腐食物,
 * 每人每天最多存几组, 默认 2) 与「公开取用」(每人每天最多取几组, 0–9, 默认 1)。
 *
 * <b>一组 = 一次整堆</b>: 规格里的「组」是批量口径, 所以配额按**堆**计 (一次存入放进去的每一堆算一组、
 * 一次取出的每一格算一组), 不按件计 —— 按件计的话「2 组」得先定义每件东西的堆大小, 牛奶桶与小麦的
 * 答案还不一样, 玩家也算不清自己今天还剩多少。
 *
 * <b>翻日按北京时间 08:00</b>: 挂钟是 epoch 分钟 (UTC), 北京 08:00 恰好是 UTC 00:00, 所以日序号就是
 * {@code floorDiv(分钟, 1440)} —— 不需要时区库, 也不会因为服务器时区设置漂移。
 */
public final class GranaryPublic {

    /** 公开捐粮的默认配额 (组/人/天)。 */
    public static final int DEFAULT_DONATE_GROUPS = 2;
    /** 公开取用的默认配额 (组/人/天)。 */
    public static final int DEFAULT_TAKE_GROUPS = 1;
    /** 两种配额的上限 (规格: 取用 0–9; 捐粮同一个手感)。 */
    public static final int MAX_GROUPS = 9;
    /** 一天的分钟数 (翻日在 UTC 00:00 = 北京 08:00)。 */
    public static final long MINUTES_PER_DAY = 1_440L;

    private GranaryPublic() {
    }

    // ============================================================
    // 设置
    // ============================================================

    /** 一个囤的公开设置。关着的开关仍然记着配额, 下次打开不用重设。 */
    public record Settings(boolean donate, int donateGroupsPerDay, boolean take, int takeGroupsPerDay) {

        /** 全关 (新囤、老档没有这个键时的默认值)。 */
        public static final Settings OFF = new Settings(false, DEFAULT_DONATE_GROUPS, false, DEFAULT_TAKE_GROUPS);

        public Settings {
            donateGroupsPerDay = clampGroups(donateGroupsPerDay);
            takeGroupsPerDay = clampGroups(takeGroupsPerDay);
        }

        /** 改捐粮开关 (顺带改配额)。 */
        public Settings withDonate(boolean on, int groups) {
            return new Settings(on, on ? groups : donateGroupsPerDay, take, takeGroupsPerDay);
        }

        /** 改取用开关 (顺带改配额)。 */
        public Settings withTake(boolean on, int groups) {
            return new Settings(donate, donateGroupsPerDay, on, on ? groups : takeGroupsPerDay);
        }

        public boolean anythingOn() {
            return donate || take;
        }

        public CompoundTag save() {
            CompoundTag tag = new CompoundTag();
            tag.putBoolean(GranaryConstants.KEY_DONATE, donate);
            tag.putInt(GranaryConstants.KEY_DONATE_GROUPS, donateGroupsPerDay);
            tag.putBoolean(GranaryConstants.KEY_TAKE, take);
            tag.putInt(GranaryConstants.KEY_TAKE_GROUPS, takeGroupsPerDay);
            return tag;
        }

        /**
         * 读一份设置。
         *
         * 缺键就是关 (老档没有这一项 = 从没开过公开模式); 配额越界按上下限夹回来而不是报错 —— 这一项是
         * 囤主的偏好, 读成关闭等于悄悄改了他的设置, 而夹到合法范围不影响任何人的东西。
         */
        public static Settings load(CompoundTag tag) {
            if (tag == null || tag.isEmpty()) {
                return OFF;
            }
            return new Settings(
                    tag.getBoolean(GranaryConstants.KEY_DONATE),
                    tag.contains(GranaryConstants.KEY_DONATE_GROUPS, Tag.TAG_INT)
                            ? tag.getInt(GranaryConstants.KEY_DONATE_GROUPS) : DEFAULT_DONATE_GROUPS,
                    tag.getBoolean(GranaryConstants.KEY_TAKE),
                    tag.contains(GranaryConstants.KEY_TAKE_GROUPS, Tag.TAG_INT)
                            ? tag.getInt(GranaryConstants.KEY_TAKE_GROUPS) : DEFAULT_TAKE_GROUPS);
        }
    }

    // ============================================================
    // 用量 (按人按天)
    // ============================================================

    /** 一个人某个囤某一天的用量 (单位都是「组」)。 */
    public record Usage(UUID player, long day, int stored, int taken) {

        public Usage plusStored(int groups) {
            return new Usage(player, day, stored + groups, taken);
        }

        public Usage plusTaken(int groups) {
            return new Usage(player, day, stored, taken + groups);
        }
    }

    /** 这个人这一天的用量 (没有记录就是 0)。 */
    public static Usage usageOf(List<Usage> usage, UUID player, long day) {
        for (Usage entry : usage) {
            if (entry.day() == day && entry.player().equals(player)) {
                return entry;
            }
        }
        return new Usage(player, day, 0, 0);
    }

    /** 今天还能捐几组 (开关关着就是 0)。 */
    public static int remainingDonations(Settings settings, Usage usage) {
        return settings.donate() ? Math.max(0, settings.donateGroupsPerDay() - usage.stored()) : 0;
    }

    /** 今天还能取几组 (开关关着就是 0)。 */
    public static int remainingTakes(Settings settings, Usage usage) {
        return settings.take() ? Math.max(0, settings.takeGroupsPerDay() - usage.taken()) : 0;
    }

    /** 档位允不允许开公开模式 (只有官仓)。 */
    public static boolean tierAllows(GranaryTier tier) {
        return tier == GranaryTier.STATE;
    }

    /** 日序号: 北京 08:00 翻日 (= UTC 00:00)。 */
    public static long dayOf(long nowMinute) {
        return Math.floorDiv(nowMinute, MINUTES_PER_DAY);
    }

    /** 配额夹到 0–{@link #MAX_GROUPS}。 */
    public static int clampGroups(int raw) {
        return Math.max(0, Math.min(MAX_GROUPS, raw));
    }

    // ============================================================
    // 存档
    // ============================================================

    public static ListTag saveUsage(List<Usage> usage) {
        ListTag tag = new ListTag();
        for (Usage entry : usage) {
            CompoundTag row = new CompoundTag();
            row.putUUID(GranaryConstants.KEY_PLAYER, entry.player());
            row.putLong(GranaryConstants.KEY_DAY, entry.day());
            row.putInt(GranaryConstants.KEY_STORED, entry.stored());
            row.putInt(GranaryConstants.KEY_TAKEN, entry.taken());
            tag.add(row);
        }
        return tag;
    }

    /** 读用量表; 重复的 (人, 天) 只留第一条 (与名单同一个口径: 不影响语义, 不是坏档)。 */
    public static List<Usage> loadUsage(ListTag tag) {
        List<Usage> usage = new ArrayList<>();
        for (int i = 0; i < tag.size(); i++) {
            CompoundTag row = tag.getCompound(i);
            if (!row.hasUUID(GranaryConstants.KEY_PLAYER)) {
                continue;
            }
            UUID player = row.getUUID(GranaryConstants.KEY_PLAYER);
            long day = row.getLong(GranaryConstants.KEY_DAY);
            if (containsPlayer(usage, player, day)) {
                continue;
            }
            usage.add(new Usage(player, day,
                    Math.max(0, row.getInt(GranaryConstants.KEY_STORED)),
                    Math.max(0, row.getInt(GranaryConstants.KEY_TAKEN))));
        }
        return usage;
    }

    private static boolean containsPlayer(List<Usage> usage, UUID player, long day) {
        for (Usage entry : usage) {
            if (entry.day() == day && entry.player().equals(player)) {
                return true;
            }
        }
        return false;
    }
}
