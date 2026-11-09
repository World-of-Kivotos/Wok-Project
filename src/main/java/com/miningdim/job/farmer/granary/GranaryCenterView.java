package com.miningdim.job.farmer.granary;

import com.miningdim.job.farmer.granary.block.GranaryBinBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 账台界面的**页内容** (服务端算好、序列化成 JSON 文本发给客户端)。
 *
 * 为什么传文本而不是传结构化数据: 界面上要显示的本来就是"已经成句的报告"(与 {@code /granary status}、
 * {@code /granary bins} 同一批行) —— 传 Component 的 JSON 直接复用现成的取数函数, 也不会出现"界面上另算一套
 * 判定"这种分叉。上限 {@link #MAX_LINES} 行: 一页塞几百行既没人看, 也把包撑大。
 *
 * 服务端权威: 客户端点页签只是"请求哪一页", 内容永远由服务端现算 (清单可能已经变了)。
 */
public final class GranaryCenterView {

    private GranaryCenterView() {
    }

    /** 一页最多几行 (超出的截断, 提示见 {@link #TRUNCATED_KEY})。 */
    public static final int MAX_LINES = 40;

    public static final String TRUNCATED_KEY = "granary.center.truncated";
    public static final String NOT_A_GRANARY_KEY = "granary.center.not_a_granary";
    public static final String NO_BINS_KEY = "granary.center.no_bins";
    public static final String NO_MEMBERS_KEY = "granary.center.no_members";
    public static final String NO_SHARED_KEY = "granary.center.no_shared";
    public static final String NO_LEDGER_KEY = "granary.center.no_ledger";

    /**
     * 算一页的内容。
     *
     * @param report 账台所在那座粮仓的巡查报告; 不属于任何粮仓时为 {@code null} (只有「我的囤」那一页还能看)
     * @return 每行一个序列化后的 Component (JSON 文本), 顺序即显示顺序
     */
    public static List<String> lines(GranaryCenterPage page, ServerLevel level, ServerPlayer viewer,
                                     GranaryInspection.Report report) {
        List<Component> raw = switch (page) {
            case STRUCTURE -> structure(level, viewer, report);
            case MY_BINS -> myBins(level, viewer);
            case MEMBERS -> members(level, viewer, report);
            case SHARED -> shared(level, viewer);
            case LEDGER -> ledger(level, viewer, report);
        };
        List<String> out = new ArrayList<>();
        int limit = Math.min(raw.size(), MAX_LINES);
        for (int i = 0; i < limit; i++) {
            out.add(Component.Serializer.toJson(raw.get(i)));
        }
        if (raw.size() > limit) {
            out.add(Component.Serializer.toJson(Component.translatable(TRUNCATED_KEY, raw.size() - limit)));
        }
        return out;
    }

    /** 结构与验收: 与 {@code /granary status} 逐字同一批行。 */
    private static List<Component> structure(ServerLevel level, ServerPlayer viewer,
                                             GranaryInspection.Report report) {
        if (report == null) {
            return List.of(Component.translatable(NOT_A_GRANARY_KEY));
        }
        return GranaryQuery.statusLines(level, report, viewer);
    }

    /** 我的囤: 我在所有粮仓里的囤 (含别处那座粮仓里的), 每行带坐标、状态与粮仓编号。 */
    private static List<Component> myBins(ServerLevel level, ServerPlayer viewer) {
        List<GranaryQuery.BinRow> mine = GranaryQuery.binsOf(level, viewer.getUUID());
        if (mine.isEmpty()) {
            return List.of(Component.translatable(NO_BINS_KEY));
        }
        List<Component> lines = new ArrayList<>();
        for (GranaryQuery.BinRow row : mine) {
            lines.add(Component.translatable("granary.center.bin_row",
                    GranaryQuery.where(row.pos()),
                    Component.translatable(row.state().translationKey()),
                    row.state().acceptsDeposits() ? "✔" : "✘",
                    row.gid()));
        }
        return lines;
    }

    /** 成员: 这座粮仓的合建成员 (房主单列一行)。 */
    private static List<Component> members(ServerLevel level, ServerPlayer viewer,
                                           GranaryInspection.Report report) {
        if (report == null) {
            return List.of(Component.translatable(NOT_A_GRANARY_KEY));
        }
        List<Component> lines = new ArrayList<>();
        lines.add(Component.translatable("granary.center.member_owner",
                GranaryQuery.nameOf(level.getServer(), report.record().owner())));
        List<UUID> members = new ArrayList<>(report.record().members());
        if (members.isEmpty()) {
            lines.add(Component.translatable(NO_MEMBERS_KEY));
            return lines;
        }
        for (UUID member : members) {
            lines.add(Component.translatable("granary.center.member_row",
                    GranaryQuery.nameOf(level.getServer(), member)));
        }
        return lines;
    }

    /**
     * 别人开放给我的: 遍历粮仓账本里**当前维度且已加载**的储粮囤, 挑出开放名单里我进得去、又不是我自己的那些。
     *
     * 只走 {@link GranaryQuery#binsOf} 那两条路太窄 (它们只有"我的囤 / 某座粮仓的囤"), 所以这里扫账本 + 方块实体;
     * 纪律与 {@code /granary audit} 相同 —— **绝不为界面加载区块** (`isLoaded` 不通过就跳过)。
     */
    private static List<Component> shared(ServerLevel level, ServerPlayer viewer) {
        List<Component> lines = new ArrayList<>();
        for (GranarySavedData.PosKey key : GranarySavedData.get(level.getServer().overworld()).bins()) {
            if (!key.dimension().equals(level.dimension().location()) || !level.isLoaded(key.pos())) {
                continue;
            }
            if (!(level.getBlockEntity(key.pos()) instanceof GranaryBinBlockEntity bin)) {
                continue;
            }
            UUID owner = bin.owner();
            if (owner == null || owner.equals(viewer.getUUID())) {
                continue; // 自己的囤在「我的囤」那一页
            }
            boolean canTake = GranaryAccess.mayTake(owner, bin.access(), viewer.getUUID());
            boolean canStore = GranaryAccess.mayStore(owner, bin.access(), viewer.getUUID());
            if (!canTake && !canStore) {
                continue;
            }
            lines.add(Component.translatable("granary.center.shared_row",
                    GranaryQuery.where(key.pos()),
                    GranaryQuery.nameOf(level.getServer(), owner),
                    Component.translatable(GranaryAccess.permissionOf(bin.access(), viewer.getUUID())
                            .translationKey())));
        }
        if (lines.isEmpty()) {
            return List.of(Component.translatable(NO_SHARED_KEY));
        }
        return lines;
    }

    /**
     * 流水: 这座粮仓最近的存取记录 (谁存谁取, 最多 {@link GranaryJournal#MAX_ENTRIES} 条)。
     *
     * 顺序是**最新在前** (看流水先看刚刚发生了什么); 一页多少行由 {@link #lines} 那边统一裁。
     */
    private static List<Component> ledger(ServerLevel level, ServerPlayer viewer,
                                          GranaryInspection.Report report) {
        if (report == null) {
            return List.of(Component.translatable(NOT_A_GRANARY_KEY));
        }
        GranarySavedData data = GranarySavedData.get(level.getServer().overworld());
        List<GranaryJournal.Entry> entries = data.journalOf(report.gid());
        if (entries.isEmpty()) {
            return List.of(Component.translatable(NO_LEDGER_KEY));
        }
        long nowHour = GranaryClock.nowHour(level);
        List<Component> lines = new ArrayList<>();
        for (int i = entries.size() - 1; i >= 0; i--) {
            GranaryJournal.Entry entry = entries.get(i);
            lines.add(Component.translatable("granary.center.ledger_row",
                    hoursAgo(nowHour - entry.hour()),
                    GranaryQuery.nameOf(level.getServer(), entry.actor()),
                    Component.translatable(entry.kind().translationKey()),
                    Component.translatable(entry.itemKey()),
                    entry.count()));
        }
        return lines;
    }

    /** 「几小时前」: 0 就是「刚刚」(流水按小时记, 与粮仓其余账目同一把钟)。 */
    private static Component hoursAgo(long hours) {
        return hours <= 0L
                ? Component.translatable("granary.center.just_now")
                : Component.translatable("granary.center.hours_ago", hours);
    }

    /** 账台所在位置的那座粮仓报告 (不属于任何粮仓时为 null)。 */
    public static GranaryInspection.Report reportAt(ServerLevel level, BlockPos pos) {
        GranaryInspection.Report report = GranaryInspection.inspect(level, pos);
        return report.orphan() ? null : report;
    }
}