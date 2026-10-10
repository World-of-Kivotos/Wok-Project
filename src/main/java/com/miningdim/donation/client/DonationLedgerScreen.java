package com.miningdim.donation.client;

import com.miningdim.donation.DonationBoxBlockEntity;
import com.miningdim.donation.DonationNetwork;
import com.miningdim.donation.DonationView;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.item.Item;
import org.jetbrains.annotations.Nullable;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

/**
 * 捐赠箱账本页 (从管理界面打开, 关闭即回到管理界面)。三个标签:
 *  - 流水: 最近的存取记录, 新的在前, 每页 {@link DonationView#PAGE_SIZE} 条, 翻页向服务端请求;
 *  - 累计: 每位玩家的累计放入/取出件数与自动化输入总数, 每页 {@link DonationView#TOTALS_PAGE_SIZE} 人,
 *    同样由服务端分页 (人数没有上限, 页脚显示总人数);
 *  - 协管: 名单; 箱主与 OP 可在此按玩家名添加、逐个移除 (服务端再判一次权限)。
 *
 * 数据全部来自服务端快照 {@link DonationView}, 本页不缓存、不推断; 打开时请求第一页, 协管变更后服务端
 * 主动回一份新快照并附一行结果提示。
 */
public final class DonationLedgerScreen extends Screen {

    private static final int PANEL_MAX_WIDTH = 340;
    private static final int PANEL_MAX_HEIGHT = 224;
    private static final int ROW_HEIGHT = 11;

    private static final int PANEL = 0xF0151B24;
    private static final int BORDER = 0xFF5B7089;
    private static final int TEXT = 0xFFE8E8E8;
    private static final int MUTED = 0xFF9AA7B5;
    private static final int DEPOSIT = 0xFF8FD59B;
    private static final int WITHDRAW = 0xFFE3A07F;
    private static final int NOTICE = 0xFFF1D98A;

    private enum Tab {
        LOG("screen.miningdim.donation_box.ledger.tab.log"),
        TOTALS("screen.miningdim.donation_box.ledger.tab.totals"),
        ADMINS("screen.miningdim.donation_box.ledger.tab.admins");

        private final String key;

        Tab(String key) {
            this.key = key;
        }
    }

    private final Screen parent;
    @Nullable
    private DonationView view;
    @Nullable
    private Component notice;
    private Tab tab = Tab.LOG;

    private final List<Button> tabButtons = new ArrayList<>();
    private final List<Button> removeButtons = new ArrayList<>();
    private Button prevButton;
    private Button nextButton;
    private Button addButton;
    private EditBox nameBox;

    public DonationLedgerScreen(Screen parent) {
        super(Component.translatable("screen.miningdim.donation_box.ledger.title"));
        this.parent = parent;
    }

    /** 服务端快照到达。 */
    void accept(DonationView view, @Nullable Component notice) {
        this.view = view;
        if (notice != null) {
            this.notice = notice;
        }
        updateWidgets();
    }

    private int panelWidth() {
        return Math.min(PANEL_MAX_WIDTH, this.width - 16);
    }

    private int panelHeight() {
        return Math.min(PANEL_MAX_HEIGHT, this.height - 16);
    }

    private int left() {
        return (this.width - panelWidth()) / 2;
    }

    private int top() {
        return (this.height - panelHeight()) / 2;
    }

    private int contentTop() {
        return top() + 58;
    }

    private int footerY() {
        return top() + panelHeight() - 22;
    }

    private int noticeY() {
        return top() + panelHeight() - 36;
    }

    /** 协管页的输入框放在 8 行名单正下方。 */
    private int editY() {
        return contentTop() + DonationBoxBlockEntity.MAX_CO_ADMINS * 13 + 4;
    }

    @Override
    protected void init() {
        String typed = this.nameBox == null ? "" : this.nameBox.getValue();
        this.tabButtons.clear();
        this.removeButtons.clear();
        int left = left();
        int top = top();
        int width = panelWidth();

        addRenderableWidget(Button.builder(Component.translatable("screen.miningdim.donation_box.ledger.back"),
                button -> onClose()).bounds(left + width - 56, top + 4, 50, 16).build());

        int tabX = left + 6;
        for (Tab each : Tab.values()) {
            Button button = addRenderableWidget(Button.builder(Component.translatable(each.key), b -> selectTab(each))
                    .bounds(tabX, top + 36, 64, 16).build());
            this.tabButtons.add(button);
            tabX += 68;
        }

        this.prevButton = addRenderableWidget(Button.builder(Component.literal("<"), b -> changePage(-1))
                .bounds(left + width / 2 - 70, footerY(), 20, 16).build());
        this.nextButton = addRenderableWidget(Button.builder(Component.literal(">"), b -> changePage(1))
                .bounds(left + width / 2 + 50, footerY(), 20, 16).build());

        for (int i = 0; i < DonationBoxBlockEntity.MAX_CO_ADMINS; i++) {
            int index = i;
            this.removeButtons.add(addRenderableWidget(Button.builder(
                            Component.translatable("screen.miningdim.donation_box.ledger.remove"), b -> removeAt(index))
                    .bounds(left + width - 60, contentTop() + i * 13 - 2, 52, 12).build()));
        }

        int editY = editY();
        this.nameBox = addRenderableWidget(new EditBox(this.font, left + 8, editY, 120, 16,
                Component.translatable("screen.miningdim.donation_box.ledger.name_hint")));
        this.nameBox.setMaxLength(16);
        this.nameBox.setValue(typed);
        this.addButton = addRenderableWidget(Button.builder(Component.translatable("screen.miningdim.donation_box.ledger.add"),
                b -> addTyped()).bounds(left + 132, editY, 52, 16).build());

        updateWidgets();
        if (this.view == null) {
            DonationNetwork.requestView(0, 0);
        }
    }

    private void selectTab(Tab target) {
        this.tab = target;
        updateWidgets();
    }

    /** 两张表都由服务端分页: 翻一张时带上另一张的当前页, 回来的快照两边都不跳页。 */
    private void changePage(int delta) {
        if (this.view == null) {
            return;
        }
        if (this.tab == Tab.LOG) {
            int target = Mth.clamp(this.view.page() + delta, 0, this.view.pageCount() - 1);
            if (target != this.view.page()) {
                DonationNetwork.requestView(target, this.view.totalsPage());
            }
        } else if (this.tab == Tab.TOTALS) {
            int target = Mth.clamp(this.view.totalsPage() + delta, 0, this.view.totalsPageCount() - 1);
            if (target != this.view.totalsPage()) {
                DonationNetwork.requestView(this.view.page(), target);
            }
        }
    }

    private void addTyped() {
        String name = this.nameBox.getValue().trim();
        if (!name.isEmpty()) {
            DonationNetwork.requestCoAdminEdit(true, name);
            this.nameBox.setValue("");
        }
    }

    private void removeAt(int index) {
        if (this.view != null && index < this.view.coAdmins().size()) {
            DonationNetwork.requestCoAdminEdit(false, this.view.coAdmins().get(index));
        }
    }

    private void updateWidgets() {
        if (this.prevButton == null) {
            return;
        }
        for (int i = 0; i < this.tabButtons.size(); i++) {
            this.tabButtons.get(i).active = Tab.values()[i] != this.tab;
        }
        boolean paged = this.tab != Tab.ADMINS;
        this.prevButton.visible = paged;
        this.nextButton.visible = paged;
        if (this.view != null && this.tab == Tab.LOG) {
            this.prevButton.active = this.view.page() > 0;
            this.nextButton.active = this.view.page() < this.view.pageCount() - 1;
        } else if (this.view != null && this.tab == Tab.TOTALS) {
            this.prevButton.active = this.view.totalsPage() > 0;
            this.nextButton.active = this.view.totalsPage() < this.view.totalsPageCount() - 1;
        }
        boolean editing = this.tab == Tab.ADMINS && this.view != null && this.view.canEditCoAdmins();
        this.nameBox.visible = editing;
        this.nameBox.setEditable(editing);
        this.addButton.visible = editing;
        int admins = this.view == null ? 0 : this.view.coAdmins().size();
        this.addButton.active = admins < DonationBoxBlockEntity.MAX_CO_ADMINS;
        for (int i = 0; i < this.removeButtons.size(); i++) {
            this.removeButtons.get(i).visible = editing && i < admins;
        }
    }

    @Override
    public void tick() {
        super.tick();
        this.nameBox.tick();
        // 服务端关掉了管理界面 (撤权、箱子被拆、走远): 账本页也不该继续挂着。
        if (this.minecraft != null && this.minecraft.player != null && this.parent instanceof DonationManagerScreen manager
                && this.minecraft.player.containerMenu != manager.getMenu()) {
            this.minecraft.setScreen(null);
        }
    }

    @Override
    public void onClose() {
        if (this.minecraft == null) {
            return;
        }
        if (this.minecraft.player != null && this.parent instanceof DonationManagerScreen manager
                && this.minecraft.player.containerMenu == manager.getMenu()) {
            this.minecraft.setScreen(this.parent);
        } else {
            this.minecraft.setScreen(null);
        }
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        renderBackground(graphics);
        int left = left();
        int top = top();
        int width = panelWidth();
        int height = panelHeight();
        graphics.fill(left - 1, top - 1, left + width + 1, top + height + 1, BORDER);
        graphics.fill(left, top, left + width, top + height, PANEL);
        graphics.drawString(this.font, this.title, left + 8, top + 8, TEXT, false);

        if (this.view == null) {
            graphics.drawString(this.font, Component.translatable("screen.miningdim.donation_box.ledger.loading"),
                    left + 8, contentTop(), MUTED, false);
        } else {
            renderHeader(graphics, left, top, width);
            switch (this.tab) {
                case LOG -> renderLog(graphics, left, width);
                case TOTALS -> renderTotals(graphics, left, width);
                case ADMINS -> renderAdmins(graphics, left, width);
            }
        }
        if (this.notice != null) {
            graphics.drawString(this.font, this.font.plainSubstrByWidth(this.notice.getString(), width - 16),
                    left + 8, noticeY(), NOTICE, false);
        }
        super.render(graphics, mouseX, mouseY, partialTick);
    }

    private void renderHeader(GuiGraphics graphics, int left, int top, int width) {
        DonationView v = this.view;
        Component owner = v.ownerName().isEmpty()
                ? Component.translatable("screen.miningdim.donation_box.ledger.no_owner")
                : Component.literal(v.ownerName());
        Component line = Component.translatable("screen.miningdim.donation_box.ledger.header",
                owner, v.usedSlots(), v.totalSlots(), v.coAdmins().size(), DonationBoxBlockEntity.MAX_CO_ADMINS);
        graphics.drawString(this.font, this.font.plainSubstrByWidth(line.getString(), width - 16),
                left + 8, top + 22, MUTED, false);
    }

    private void renderLog(GuiGraphics graphics, int left, int width) {
        DonationView v = this.view;
        if (v.entries().isEmpty()) {
            graphics.drawString(this.font, Component.translatable("screen.miningdim.donation_box.ledger.empty"),
                    left + 8, contentTop(), MUTED, false);
        }
        SimpleDateFormat format = new SimpleDateFormat("MM-dd HH:mm");
        int y = contentTop();
        for (DonationView.Row row : v.entries()) {
            String time = format.format(new Date(row.timeMillis()));
            String actor = row.automation()
                    ? Component.translatable("screen.miningdim.donation_box.ledger.automation").getString()
                    : row.actorName();
            Component action = Component.translatable(row.deposit()
                    ? "screen.miningdim.donation_box.ledger.deposit"
                    : "screen.miningdim.donation_box.ledger.withdraw");
            int x = left + 8;
            graphics.drawString(this.font, time, x, y, MUTED, false);
            x += this.font.width("00-00 00:00") + 6;
            graphics.drawString(this.font, this.font.plainSubstrByWidth(actor, 96), x, y, TEXT, false);
            x += 100;
            graphics.drawString(this.font, action, x, y, row.deposit() ? DEPOSIT : WITHDRAW, false);
            x += this.font.width(action) + 6;
            String item = itemName(row.itemId()) + " x" + row.count();
            graphics.drawString(this.font, this.font.plainSubstrByWidth(item, left + width - 8 - x), x, y, TEXT, false);
            y += ROW_HEIGHT;
        }
        graphics.drawCenteredString(this.font, Component.translatable("screen.miningdim.donation_box.ledger.page",
                v.page() + 1, v.pageCount(), v.entryCount()), left + width / 2, footerY() + 4, MUTED);
    }

    private void renderTotals(GuiGraphics graphics, int left, int width) {
        DonationView v = this.view;
        int y = contentTop();
        graphics.drawString(this.font, Component.translatable("screen.miningdim.donation_box.ledger.automation_total",
                v.automationDeposited()), left + 8, y, MUTED, false);
        y += ROW_HEIGHT + 2;
        if (v.totals().isEmpty()) {
            graphics.drawString(this.font, Component.translatable("screen.miningdim.donation_box.ledger.empty"),
                    left + 8, y, MUTED, false);
        }
        for (DonationView.TotalsRow row : v.totals()) {
            graphics.drawString(this.font, this.font.plainSubstrByWidth(row.name(), 110), left + 8, y, TEXT, false);
            graphics.drawString(this.font, Component.translatable("screen.miningdim.donation_box.ledger.totals_row",
                    row.deposited(), row.withdrawn()), left + 124, y, TEXT, false);
            y += ROW_HEIGHT;
        }
        graphics.drawCenteredString(this.font, Component.translatable("screen.miningdim.donation_box.ledger.totals_page",
                v.totalsPage() + 1, v.totalsPageCount(), v.totalsCount()), left + width / 2, footerY() + 4, MUTED);
    }

    private void renderAdmins(GuiGraphics graphics, int left, int width) {
        DonationView v = this.view;
        int y = contentTop();
        if (v.coAdmins().isEmpty()) {
            graphics.drawString(this.font, Component.translatable("screen.miningdim.donation_box.ledger.no_admins"),
                    left + 8, y, MUTED, false);
        }
        for (String name : v.coAdmins()) {
            graphics.drawString(this.font, this.font.plainSubstrByWidth(name, width - 80), left + 8, y, TEXT, false);
            y += 13;
        }
        if (!v.canEditCoAdmins()) {
            graphics.drawString(this.font, Component.translatable("screen.miningdim.donation_box.ledger.read_only"),
                    left + 8, footerY() + 4, MUTED, false);
        }
    }

    private static String itemName(String id) {
        ResourceLocation key = ResourceLocation.tryParse(id);
        if (key == null || !BuiltInRegistries.ITEM.containsKey(key)) {
            return id;
        }
        Item item = BuiltInRegistries.ITEM.get(key);
        return item.getDescription().getString();
    }
}
