package com.miningdim.donation;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Player;

import java.util.ArrayList;
import java.util.List;

/**
 * 管理界面"账本"页的一次快照: 箱主、协管名单、仓库占用、一页累计表与一页流水 (新的在前)。
 *
 * 只在服务端由 {@link #build} 生成, 只发给能管理该箱子的玩家; 客户端只读显示, 从不据此做任何判定。
 * 解码时对每个列表长度设上限, 畸形包最多让账本页显示不全, 不会让客户端按包里的数字去分配内存。
 *
 * 累计表与流水一样由服务端分页: 它每个存取过的玩家一行、没有上限, 整表下发会超包; 而截断成"前 N 名"时
 * 按放入量排序会把只取不放的人 (通常正是协管) 挤到最后截掉, 查账最该看的取出方反而看不到。
 */
public record DonationView(String ownerName, boolean canEditCoAdmins, List<String> coAdmins,
                           int usedSlots, int totalSlots, long automationDeposited,
                           int totalsPage, int totalsPageCount, int totalsCount, List<TotalsRow> totals,
                           int page, int pageCount, int entryCount, List<Row> entries) {

    /** 每页流水条数。 */
    public static final int PAGE_SIZE = 10;

    /** 累计表每页人数。 */
    public static final int TOTALS_PAGE_SIZE = 10;

    private static final int MAX_DECODED_LIST = 64;
    private static final int MAX_NAME_CHARS = 64;
    private static final int MAX_ITEM_ID_CHARS = 256;

    public record TotalsRow(String name, long deposited, long withdrawn) {
    }

    /** 一行流水。automation 为真时 actorName 为空, 由客户端显示成"自动化输入"。 */
    public record Row(long timeMillis, boolean automation, String actorName, boolean deposit, String itemId, int count) {
    }

    /** 页数: 至少 1 页 (空表也显示"第 1/1 页")。 */
    static int pageCount(int rows, int pageSize) {
        return Math.max(1, (rows + pageSize - 1) / pageSize);
    }

    /**
     * @param requestedPage       流水页码 (0 起, 越界夹回)
     * @param requestedTotalsPage 累计表页码 (0 起, 越界夹回)
     */
    static DonationView build(DonationBoxBlockEntity box, Player viewer, int requestedPage, int requestedTotalsPage) {
        DonationLedger ledger = box.ledger();
        List<DonationLedger.Entry> newestFirst = ledger.entriesNewestFirst();
        int pageCount = pageCount(newestFirst.size(), PAGE_SIZE);
        int page = Mth.clamp(requestedPage, 0, pageCount - 1);
        List<Row> rows = new ArrayList<>(PAGE_SIZE);
        int from = page * PAGE_SIZE;
        int to = Math.min(newestFirst.size(), from + PAGE_SIZE);
        for (int i = from; i < to; i++) {
            DonationLedger.Entry e = newestFirst.get(i);
            rows.add(new Row(e.timeMillis(), e.automation(), e.actorName(),
                    e.action() == DonationLedger.Action.DEPOSIT, e.itemId().toString(), e.count()));
        }

        List<DonationLedger.Totals> allTotals = ledger.totals();
        int totalsPageCount = pageCount(allTotals.size(), TOTALS_PAGE_SIZE);
        int totalsPage = Mth.clamp(requestedTotalsPage, 0, totalsPageCount - 1);
        List<TotalsRow> totals = new ArrayList<>(TOTALS_PAGE_SIZE);
        int totalsFrom = totalsPage * TOTALS_PAGE_SIZE;
        int totalsTo = Math.min(allTotals.size(), totalsFrom + TOTALS_PAGE_SIZE);
        for (int i = totalsFrom; i < totalsTo; i++) {
            DonationLedger.Totals t = allTotals.get(i);
            totals.add(new TotalsRow(t.playerName(), t.deposited(), t.withdrawn()));
        }
        return new DonationView(box.ownerName(), box.canEditCoAdmins(viewer), box.coAdminNames(),
                box.storage().usedSlots(), box.storage().getSlots(), ledger.automationDeposited(),
                totalsPage, totalsPageCount, allTotals.size(), totals,
                page, pageCount, newestFirst.size(), rows);
    }

    void write(FriendlyByteBuf buf) {
        buf.writeUtf(ownerName, MAX_NAME_CHARS);
        buf.writeBoolean(canEditCoAdmins);
        buf.writeVarInt(coAdmins.size());
        for (String name : coAdmins) {
            buf.writeUtf(name, MAX_NAME_CHARS);
        }
        buf.writeVarInt(usedSlots);
        buf.writeVarInt(totalSlots);
        buf.writeVarLong(automationDeposited);
        buf.writeVarInt(totalsPage);
        buf.writeVarInt(totalsPageCount);
        buf.writeVarInt(totalsCount);
        buf.writeVarInt(totals.size());
        for (TotalsRow row : totals) {
            buf.writeUtf(row.name(), MAX_NAME_CHARS);
            buf.writeVarLong(row.deposited());
            buf.writeVarLong(row.withdrawn());
        }
        buf.writeVarInt(page);
        buf.writeVarInt(pageCount);
        buf.writeVarInt(entryCount);
        buf.writeVarInt(entries.size());
        for (Row row : entries) {
            buf.writeLong(row.timeMillis());
            buf.writeBoolean(row.automation());
            buf.writeUtf(row.actorName(), MAX_NAME_CHARS);
            buf.writeBoolean(row.deposit());
            buf.writeUtf(row.itemId(), MAX_ITEM_ID_CHARS);
            buf.writeVarInt(row.count());
        }
    }

    static DonationView read(FriendlyByteBuf buf) {
        String owner = buf.readUtf(MAX_NAME_CHARS);
        boolean canEdit = buf.readBoolean();
        int adminCount = boundedSize(buf.readVarInt());
        List<String> admins = new ArrayList<>(adminCount);
        for (int i = 0; i < adminCount; i++) {
            admins.add(buf.readUtf(MAX_NAME_CHARS));
        }
        int used = buf.readVarInt();
        int total = buf.readVarInt();
        long automation = buf.readVarLong();
        int totalsPage = buf.readVarInt();
        int totalsPageCount = buf.readVarInt();
        int totalsCount = buf.readVarInt();
        int totalsRows = boundedSize(buf.readVarInt());
        List<TotalsRow> totals = new ArrayList<>(totalsRows);
        for (int i = 0; i < totalsRows; i++) {
            totals.add(new TotalsRow(buf.readUtf(MAX_NAME_CHARS), buf.readVarLong(), buf.readVarLong()));
        }
        int page = buf.readVarInt();
        int pageCount = buf.readVarInt();
        int entryCount = buf.readVarInt();
        int rowCount = boundedSize(buf.readVarInt());
        List<Row> rows = new ArrayList<>(rowCount);
        for (int i = 0; i < rowCount; i++) {
            rows.add(new Row(buf.readLong(), buf.readBoolean(), buf.readUtf(MAX_NAME_CHARS), buf.readBoolean(),
                    buf.readUtf(MAX_ITEM_ID_CHARS), buf.readVarInt()));
        }
        return new DonationView(owner, canEdit, admins, used, total, automation,
                totalsPage, totalsPageCount, totalsCount, totals, page, pageCount, entryCount, rows);
    }

    private static int boundedSize(int declared) {
        if (declared < 0 || declared > MAX_DECODED_LIST) {
            throw new IllegalArgumentException("donation view list too long: " + declared);
        }
        return declared;
    }
}
