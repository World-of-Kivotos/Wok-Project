package com.miningdim.job.fisher.journal.client;

import com.miningdim.job.fisher.journal.FishingJournalEntry;
import com.miningdim.job.fisher.journal.FishingJournalSnapshot;
import com.miningdim.job.fisher.quality.FishQuality;
import com.miningdim.job.fisher.size.FishRecord;
import com.miningdim.job.fisher.size.FishSizeFormat;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.item.ItemStack;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Read-only client presentation of one fishing journal snapshot. Filtering and selection are deliberately local;
 * server state is never inferred or changed from this screen.
 */
public final class FishingJournalScreen extends Screen {

    private static final List<String> CATEGORY_ORDER = List.of(
            "freshwater", "saltwater", "underground", "depths", "biome", "structure", "lava", "nether", "end", "legendary");
    private static final int OUTER_MARGIN = 8;
    private static final int HEADER_HEIGHT = 36;
    private static final int FOOTER_HEIGHT = 28;
    private static final int ROW_HEIGHT = 25;
    private static final int NAVY = 0xFF10243B;
    private static final int PANEL = 0xF21A3452;
    private static final int PANEL_ALT = 0xE8172E49;
    private static final int BORDER = 0xFF6F99B9;
    private static final int CREAM = 0xFFF1E7C8;
    private static final int MUTED = 0xFFB9C8D5;
    private static final int ACCENT = 0xFF78BAD4;
    private static final int COLLECTED = 0xFF8FD59B;
    private static final int MISSING = 0xFFDA9B8A;

    private FishingJournalSnapshot snapshot;
    private EditBox searchBox;
    private Filter filter = Filter.ALL;
    private String category;
    private ResourceLocation selectedItemId;
    private int page;
    private int categoryScroll;
    private int detailScroll;

    private Button allButton;
    private Button collectedButton;
    private Button missingButton;
    private Button previousButton;
    private Button nextButton;
    private Button closeButton;

    public FishingJournalScreen(FishingJournalSnapshot snapshot) {
        super(Component.translatable("screen.miningdim.fishing_journal.title"));
        this.snapshot = snapshot;
    }

    /** Replaces only server data while preserving locally controlled search, category, filter, page, and selection. */
    public void updateSnapshot(FishingJournalSnapshot snapshot) {
        this.snapshot = snapshot;
        normalizeState();
    }

    @Override
    protected void init() {
        String searchText = this.searchBox == null ? "" : this.searchBox.getValue();
        int x = OUTER_MARGIN;
        int searchWidth = Math.max(96, Math.min(240, this.width / 3));
        this.searchBox = this.addRenderableWidget(new EditBox(this.font, x, 19, searchWidth, 18,
                Component.translatable("screen.miningdim.fishing_journal.search")));
        this.searchBox.setValue(searchText);
        updateSearchSuggestion(searchText);
        this.searchBox.setResponder(value -> {
            updateSearchSuggestion(value);
            this.page = 0;
            ensureSelectedVisible();
        });

        this.allButton = this.addRenderableWidget(filterButton(Filter.ALL));
        this.collectedButton = this.addRenderableWidget(filterButton(Filter.COLLECTED));
        this.missingButton = this.addRenderableWidget(filterButton(Filter.MISSING));
        this.previousButton = this.addRenderableWidget(Button.builder(Component.literal("<"), button -> changePage(-1)).build());
        this.nextButton = this.addRenderableWidget(Button.builder(Component.literal(">"), button -> changePage(1)).build());
        this.closeButton = this.addRenderableWidget(Button.builder(
                Component.translatable("screen.miningdim.fishing_journal.close"), button -> onClose()).build());
        layoutWidgets();
        normalizeState();
    }

    private Button filterButton(Filter target) {
        return Button.builder(Component.translatable(target.translationKey), button -> {
            this.filter = target;
            this.page = 0;
            ensureSelectedVisible();
        }).build();
    }

    private void updateSearchSuggestion(String value) {
        this.searchBox.setSuggestion(value.isEmpty()
                ? Component.translatable("screen.miningdim.fishing_journal.search").getString()
                : null);
    }

    private void layoutWidgets() {
        int x = OUTER_MARGIN;
        int y = 19;
        int searchWidth = Math.max(96, Math.min(240, this.width / 3));
        this.searchBox.setX(x);
        this.searchBox.setY(y);
        this.searchBox.setWidth(searchWidth);

        int filterX = x + searchWidth + 6;
        int remaining = Math.max(30, this.width - filterX - OUTER_MARGIN);
        int closeWidth = Math.min(76, Math.max(52, remaining / 5));
        int filterWidth = Math.max(26, Math.min(82, (remaining - closeWidth - 12) / 3));
        setBounds(this.allButton, filterX, y, filterWidth, 18);
        setBounds(this.collectedButton, filterX + filterWidth + 3, y, filterWidth, 18);
        setBounds(this.missingButton, filterX + (filterWidth + 3) * 2, y, filterWidth, 18);
        setBounds(this.closeButton, this.width - OUTER_MARGIN - closeWidth, y, closeWidth, 18);

        int footerY = this.height - OUTER_MARGIN - FOOTER_HEIGHT + 5;
        setBounds(this.previousButton, this.width / 2 - 76, footerY, 30, 18);
        setBounds(this.nextButton, this.width / 2 + 46, footerY, 30, 18);
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        renderBackground(graphics);
        Layout layout = layout();
        drawFrame(graphics, layout);
        super.render(graphics, mouseX, mouseY, partialTick);
        renderCategories(graphics, layout, mouseX, mouseY);
        renderEntries(graphics, layout, mouseX, mouseY);
        renderDetail(graphics, layout);
        renderFooter(graphics, layout);
    }

    private void drawFrame(GuiGraphics graphics, Layout layout) {
        graphics.fill(0, 0, this.width, this.height, NAVY);
        graphics.fill(OUTER_MARGIN, OUTER_MARGIN, this.width - OUTER_MARGIN, this.height - OUTER_MARGIN, 0xFF0C1D31);
        graphics.fill(OUTER_MARGIN, OUTER_MARGIN, this.width - OUTER_MARGIN, OUTER_MARGIN + 1, BORDER);
        graphics.fill(OUTER_MARGIN, this.height - OUTER_MARGIN - 1, this.width - OUTER_MARGIN, this.height - OUTER_MARGIN, BORDER);
        graphics.drawString(this.font, this.title, OUTER_MARGIN, 8, CREAM, false);
        graphics.fill(layout.categoryX, layout.bodyY, layout.categoryX + layout.categoryWidth, layout.bodyBottom, PANEL);
        graphics.fill(layout.listX, layout.bodyY, layout.listX + layout.listWidth, layout.listBottom, PANEL_ALT);
        if (!layout.compact) {
            graphics.fill(layout.detailX, layout.bodyY, layout.detailX + layout.detailWidth, layout.bodyBottom, PANEL);
        } else {
            graphics.fill(layout.detailX, layout.detailY, layout.detailX + layout.detailWidth, layout.detailY + layout.detailHeight, PANEL);
        }
    }

    private void renderCategories(GuiGraphics graphics, Layout layout, int mouseX, int mouseY) {
        int rowHeight = categoryRowHeight(layout);
        int y = layout.bodyY + 7;
        drawCategoryRow(graphics, layout, null, y, mouseX, mouseY);
        y += rowHeight + 2;
        List<String> categories = categories();
        this.categoryScroll = Mth.clamp(this.categoryScroll, 0, maxCategoryScroll(layout, categories));
        int capacity = categoryCapacity(layout);
        int last = Math.min(categories.size(), this.categoryScroll + capacity);
        for (int index = this.categoryScroll; index < last; index++) {
            String value = categories.get(index);
            drawCategoryRow(graphics, layout, value, y, mouseX, mouseY);
            y += rowHeight;
        }
    }

    private void drawCategoryRow(GuiGraphics graphics, Layout layout, String value, int y, int mouseX, int mouseY) {
        boolean selected = value == null ? this.category == null : value.equals(this.category);
        boolean hovered = mouseX >= layout.categoryX + 3 && mouseX < layout.categoryX + layout.categoryWidth - 3
                && mouseY >= y - 2 && mouseY < y + categoryRowHeight(layout) - 1;
        if (selected || hovered) {
            graphics.fill(layout.categoryX + 3, y - 2, layout.categoryX + layout.categoryWidth - 3,
                    y + categoryRowHeight(layout) - 1,
                    selected ? 0xFF315A78 : 0xFF284863);
        }
        Component label = value == null
                ? Component.translatable("screen.miningdim.fishing_journal.category_all")
                : Component.translatable("fishing.miningdim.category." + value);
        int count = value == null ? this.snapshot.entries().size() : categoryCount(value);
        String text = this.font.plainSubstrByWidth(label.getString(), layout.categoryWidth - 28);
        graphics.drawString(this.font, text, layout.categoryX + 6, y, selected ? CREAM : MUTED, false);
        String countText = Integer.toString(count);
        graphics.drawString(this.font, countText,
                layout.categoryX + layout.categoryWidth - 6 - this.font.width(countText), y, ACCENT, false);
    }

    private void renderEntries(GuiGraphics graphics, Layout layout, int mouseX, int mouseY) {
        List<FishingJournalEntry> entries = visibleEntries();
        int pageSize = Math.max(1, (layout.listBottom - layout.bodyY - 8) / ROW_HEIGHT);
        int pages = pageCount(entries.size(), pageSize);
        this.page = Mth.clamp(this.page, 0, pages - 1);
        int first = this.page * pageSize;
        int last = Math.min(entries.size(), first + pageSize);
        if (entries.isEmpty()) {
            graphics.drawCenteredString(this.font, Component.translatable("screen.miningdim.fishing_journal.empty"),
                    layout.listX + layout.listWidth / 2, layout.bodyY + 16, MUTED);
            return;
        }
        for (int index = first; index < last; index++) {
            FishingJournalEntry entry = entries.get(index);
            int row = index - first;
            int y = layout.bodyY + 4 + row * ROW_HEIGHT;
            boolean selected = entry.itemId().equals(this.selectedItemId);
            boolean hovered = mouseX >= layout.listX + 3 && mouseX < layout.listX + layout.listWidth - 3
                    && mouseY >= y && mouseY < y + ROW_HEIGHT - 2;
            if (selected || hovered) {
                graphics.fill(layout.listX + 3, y, layout.listX + layout.listWidth - 3, y + ROW_HEIGHT - 2,
                        selected ? 0xFF315A78 : 0xFF274762);
            }
            ItemStack stack = stackFor(entry);
            graphics.renderItem(stack, layout.listX + 7, y + 3);
            boolean known = isCollected(entry);
            int statusColor = known ? COLLECTED : MISSING;
            graphics.fill(layout.listX + 27, y + 5, layout.listX + 30, y + 17, statusColor);
            int available = Math.max(20, layout.listWidth - 41);
            String name = this.font.plainSubstrByWidth(stack.getHoverName().getString(), available);
            graphics.drawString(this.font, name, layout.listX + 34, y + 3, known ? qualityColor(stack) : MUTED, false);
            Component status = Component.translatable(known
                    ? "screen.miningdim.fishing_journal.collected"
                    : "screen.miningdim.fishing_journal.missing");
            graphics.drawString(this.font, this.font.plainSubstrByWidth(status.getString(), available),
                    layout.listX + 34, y + 13, statusColor, false);
        }
    }

    private void renderDetail(GuiGraphics graphics, Layout layout) {
        FishingJournalEntry entry = selectedEntry();
        int x = layout.detailX + 7;
        int y = layout.detailY + 7;
        int width = layout.detailWidth - 14;
        if (entry == null) {
            graphics.drawCenteredString(this.font, Component.translatable("screen.miningdim.fishing_journal.select_entry"),
                    layout.detailX + layout.detailWidth / 2, y + 10, MUTED);
            return;
        }
        ItemStack stack = stackFor(entry);
        graphics.renderItem(stack, x, y);
        graphics.drawString(this.font, this.font.plainSubstrByWidth(stack.getHoverName().getString(), width - 23),
                x + 22, y + 3, qualityColor(stack), false);
        int contentY = y + (layout.compact ? 19 : 28);
        int bottom = layout.detailY + layout.detailHeight - 3;
        List<DetailLine> lines = detailLines(entry, width, layout.compact);
        int capacity = Math.max(1, (bottom - contentY + 1) / 10);
        int maxScroll = Math.max(0, lines.size() - capacity);
        this.detailScroll = Mth.clamp(this.detailScroll, 0, maxScroll);
        for (int index = this.detailScroll; index < Math.min(lines.size(), this.detailScroll + capacity); index++) {
            DetailLine line = lines.get(index);
            graphics.drawString(this.font, line.text(), x, contentY + (index - this.detailScroll) * 10, line.color(), false);
        }
    }

    private List<DetailLine> detailLines(FishingJournalEntry entry, int width, boolean compact) {
        List<DetailLine> lines = new ArrayList<>();
        if (!compact) {
            lines.add(new DetailLine(Component.translatable("fishing.miningdim.category." + entry.category()).getVisualOrderText(), ACCENT));
        }
        FishQuality quality = FishQuality.of(stackFor(entry));
        if (quality != null) {
            lines.add(new DetailLine(Component.translatable("screen.miningdim.fishing_journal.quality").getVisualOrderText(), ACCENT));
            lines.add(new DetailLine(quality.displayName().getVisualOrderText(), argb(quality.color())));
        }
        appendDetailSection(lines, width, "screen.miningdim.fishing_journal.description", entry.descriptionKey());
        appendDetailSection(lines, width, "screen.miningdim.fishing_journal.habitat", entry.habitatKey());
        appendDetailSection(lines, width, "screen.miningdim.fishing_journal.conditions", entry.conditionsKey());
        appendRecordSection(lines, width, entry);
        return lines;
    }

    /** 亲手钓获记录; 与"已收录"无关 —— 交易或捡来的鱼会收录, 但没有钓获记录。 */
    private void appendRecordSection(List<DetailLine> lines, int width, FishingJournalEntry entry) {
        lines.add(new DetailLine(Component.translatable("screen.miningdim.fishing_journal.records").getVisualOrderText(), ACCENT));
        FishRecord record = this.snapshot.records().get(entry.itemId());
        if (record == null) {
            lines.add(new DetailLine(Component.translatable("screen.miningdim.fishing_journal.records.none").getVisualOrderText(), MUTED));
            return;
        }
        appendWrapped(lines, width, Component.translatable("screen.miningdim.fishing_journal.records.count", record.count()));
        appendWrapped(lines, width, Component.translatable("screen.miningdim.fishing_journal.records.best",
                FishSizeFormat.length(record.bestLengthMm()), FishSizeFormat.weight(record.bestWeightMg())));
    }

    private void appendWrapped(List<DetailLine> lines, int width, Component text) {
        for (net.minecraft.util.FormattedCharSequence line : this.font.split(text, Math.max(20, width))) {
            lines.add(new DetailLine(line, CREAM));
        }
    }

    private static int qualityColor(ItemStack stack) {
        FishQuality quality = FishQuality.of(stack);
        return quality == null ? CREAM : argb(quality.color());
    }

    private static int argb(ChatFormatting formatting) {
        Integer rgb = formatting.getColor();
        return rgb == null ? CREAM : 0xFF000000 | rgb;
    }

    private void appendDetailSection(List<DetailLine> lines, int width, String labelKey, String valueKey) {
        lines.add(new DetailLine(Component.translatable(labelKey).getVisualOrderText(), ACCENT));
        for (net.minecraft.util.FormattedCharSequence line : this.font.split(Component.translatable(valueKey), Math.max(20, width))) {
            lines.add(new DetailLine(line, CREAM));
        }
    }

    private void renderFooter(GuiGraphics graphics, Layout layout) {
        int collected = collectedCount();
        int total = this.snapshot.entries().size();
        int progressWidth = Math.max(48, Math.min(170, this.width / 5));
        int progressX = OUTER_MARGIN + 4;
        int progressY = this.height - OUTER_MARGIN - 16;
        graphics.drawString(this.font, Component.translatable("screen.miningdim.fishing_journal.progress", collected, total),
                progressX, progressY - 10, CREAM, false);
        graphics.fill(progressX, progressY, progressX + progressWidth, progressY + 5, 0xFF31475A);
        int filled = total == 0 ? 0 : (int) ((long) collected * progressWidth / total);
        graphics.fill(progressX, progressY, progressX + filled, progressY + 5, COLLECTED);

        int pageSize = Math.max(1, (layout.listBottom - layout.bodyY - 8) / ROW_HEIGHT);
        int pages = pageCount(visibleEntries().size(), pageSize);
        graphics.drawCenteredString(this.font, Component.translatable("screen.miningdim.fishing_journal.page", this.page + 1, pages),
                this.width / 2, this.height - OUTER_MARGIN - 13, MUTED);
        this.previousButton.active = this.page > 0;
        this.nextButton.active = this.page < pages - 1;
        this.allButton.active = this.filter != Filter.ALL;
        this.collectedButton.active = this.filter != Filter.COLLECTED;
        this.missingButton.active = this.filter != Filter.MISSING;
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        Layout layout = layout();
        String clickedCategory = categoryAt(layout, mouseX, mouseY);
        if (clickedCategory != null) {
            this.category = clickedCategory.isEmpty() ? null : clickedCategory;
            this.page = 0;
            ensureSelectedVisible();
            return true;
        }
        FishingJournalEntry entry = entryAt(layout, mouseX, mouseY);
        if (entry != null) {
            this.selectedItemId = entry.itemId();
            this.detailScroll = 0;
            return true;
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double delta) {
        Layout layout = layout();
        if (mouseX >= layout.listX && mouseX < layout.listX + layout.listWidth
                && mouseY >= layout.bodyY && mouseY < layout.listBottom) {
            changePage(delta < 0.0D ? 1 : -1);
            return true;
        }
        if (mouseX >= layout.categoryX && mouseX < layout.categoryX + layout.categoryWidth
                && mouseY >= layout.bodyY && mouseY < layout.bodyBottom) {
            this.categoryScroll = Mth.clamp(this.categoryScroll + (delta < 0.0D ? 1 : -1),
                    0, maxCategoryScroll(layout, categories()));
            return true;
        }
        if (mouseX >= layout.detailX && mouseX < layout.detailX + layout.detailWidth
                && mouseY >= layout.detailY && mouseY < layout.detailY + layout.detailHeight) {
            changeDetailScroll(layout, delta < 0.0D ? 1 : -1);
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, delta);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (keyCode == GLFW.GLFW_KEY_ESCAPE) {
            onClose();
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    private void changePage(int delta) {
        Layout layout = layout();
        int pageSize = Math.max(1, (layout.listBottom - layout.bodyY - 8) / ROW_HEIGHT);
        List<FishingJournalEntry> entries = visibleEntries();
        this.page = Mth.clamp(this.page + delta, 0, pageCount(entries.size(), pageSize) - 1);
        int first = this.page * pageSize;
        if (first < entries.size()) {
            this.selectedItemId = entries.get(first).itemId();
            this.detailScroll = 0;
        }
    }

    private void normalizeState() {
        if (this.category != null && !categories().contains(this.category)) {
            this.category = null;
        }
        if (this.selectedItemId != null && selectedEntry() == null) {
            this.selectedItemId = null;
        }
        this.categoryScroll = Mth.clamp(this.categoryScroll, 0, maxCategoryScroll(layout(), categories()));
        ensureSelectedVisible();
    }

    private void ensureSelectedVisible() {
        List<FishingJournalEntry> entries = visibleEntries();
        Layout layout = layout();
        int pageSize = Math.max(1, (layout.listBottom - layout.bodyY - 8) / ROW_HEIGHT);
        this.page = Mth.clamp(this.page, 0, pageCount(entries.size(), pageSize) - 1);
        int first = this.page * pageSize;
        int last = Math.min(entries.size(), first + pageSize);
        int selectedIndex = indexOf(entries, this.selectedItemId);
        if (selectedIndex < first || selectedIndex >= last) {
            this.selectedItemId = first < entries.size() ? entries.get(first).itemId() : null;
            this.detailScroll = 0;
            return;
        }
    }

    private String categoryAt(Layout layout, double mouseX, double mouseY) {
        if (mouseX < layout.categoryX + 3 || mouseX >= layout.categoryX + layout.categoryWidth - 3) {
            return null;
        }
        if (mouseY < layout.bodyY + 5 || mouseY >= layout.bodyBottom) {
            return null;
        }
        List<String> categories = categories();
        int rowHeight = categoryRowHeight(layout);
        int allY = layout.bodyY + 7;
        if (mouseY >= allY - 2 && mouseY < allY + rowHeight - 1) {
            return "";
        }
        int y = allY + rowHeight + 2;
        int capacity = categoryCapacity(layout);
        for (int visibleIndex = 0; visibleIndex < capacity; visibleIndex++) {
            int index = this.categoryScroll + visibleIndex;
            if (index >= categories.size()) {
                return null;
            }
            if (mouseY >= y - 2 && mouseY < y + rowHeight - 1) {
                return categories.get(index);
            }
            y += rowHeight;
        }
        return null;
    }

    private FishingJournalEntry entryAt(Layout layout, double mouseX, double mouseY) {
        if (mouseX < layout.listX + 3 || mouseX >= layout.listX + layout.listWidth - 3
                || mouseY < layout.bodyY + 4 || mouseY >= layout.listBottom) {
            return null;
        }
        int row = (int) ((mouseY - (layout.bodyY + 4)) / ROW_HEIGHT);
        int pageSize = Math.max(1, (layout.listBottom - layout.bodyY - 8) / ROW_HEIGHT);
        if (row >= pageSize) {
            return null;
        }
        int index = this.page * pageSize + row;
        List<FishingJournalEntry> entries = visibleEntries();
        return index >= 0 && index < entries.size() ? entries.get(index) : null;
    }

    private List<FishingJournalEntry> visibleEntries() {
        String query = this.searchBox == null ? "" : this.searchBox.getValue().trim().toLowerCase(Locale.ROOT);
        List<FishingJournalEntry> result = new ArrayList<>();
        for (FishingJournalEntry entry : this.snapshot.entries()) {
            if (this.category != null && !this.category.equals(entry.category())) {
                continue;
            }
            boolean collected = isCollected(entry);
            if (this.filter == Filter.COLLECTED && !collected || this.filter == Filter.MISSING && collected) {
                continue;
            }
            ItemStack stack = stackFor(entry);
            if (!query.isEmpty() && !stack.getHoverName().getString().toLowerCase(Locale.ROOT).contains(query)
                    && !entry.itemId().toString().toLowerCase(Locale.ROOT).contains(query)) {
                continue;
            }
            result.add(entry);
        }
        return result;
    }

    private List<String> categories() {
        Set<String> available = new LinkedHashSet<>();
        for (FishingJournalEntry entry : this.snapshot.entries()) {
            available.add(entry.category());
        }
        List<String> result = new ArrayList<>();
        for (String fixed : CATEGORY_ORDER) {
            if (available.remove(fixed)) {
                result.add(fixed);
            }
        }
        result.addAll(available.stream().sorted().toList());
        return result;
    }

    private int categoryCount(String category) {
        int count = 0;
        for (FishingJournalEntry entry : this.snapshot.entries()) {
            if (category.equals(entry.category())) {
                count++;
            }
        }
        return count;
    }

    private int collectedCount() {
        int count = 0;
        for (FishingJournalEntry entry : this.snapshot.entries()) {
            if (isCollected(entry)) {
                count++;
            }
        }
        return count;
    }

    private boolean isCollected(FishingJournalEntry entry) {
        return this.snapshot.collected().contains(entry.itemId());
    }

    private FishingJournalEntry selectedEntry() {
        if (this.selectedItemId == null) {
            return null;
        }
        for (FishingJournalEntry entry : this.snapshot.entries()) {
            if (this.selectedItemId.equals(entry.itemId())) {
                return entry;
            }
        }
        return null;
    }

    private static ItemStack stackFor(FishingJournalEntry entry) {
        return BuiltInRegistries.ITEM.get(entry.itemId()).getDefaultInstance();
    }

    private static int pageCount(int count, int pageSize) {
        return Math.max(1, (count + pageSize - 1) / pageSize);
    }

    private static int indexOf(List<FishingJournalEntry> entries, ResourceLocation itemId) {
        if (itemId == null) {
            return -1;
        }
        for (int i = 0; i < entries.size(); i++) {
            if (itemId.equals(entries.get(i).itemId())) {
                return i;
            }
        }
        return -1;
    }

    private static int categoryRowHeight(Layout layout) {
        return layout.compact ? 12 : 16;
    }

    private int categoryCapacity(Layout layout) {
        int rowHeight = categoryRowHeight(layout);
        int firstCategoryY = layout.bodyY + 7 + rowHeight + 2;
        return Math.max(0, (layout.bodyBottom - firstCategoryY) / rowHeight);
    }

    private int maxCategoryScroll(Layout layout, List<String> categories) {
        return Math.max(0, categories.size() - categoryCapacity(layout));
    }

    private void changeDetailScroll(Layout layout, int delta) {
        FishingJournalEntry entry = selectedEntry();
        if (entry == null) {
            return;
        }
        int contentY = layout.detailY + 7 + (layout.compact ? 19 : 28);
        int capacity = Math.max(1, (layout.detailY + layout.detailHeight - 3 - contentY + 1) / 10);
        int maxScroll = Math.max(0, detailLines(entry, layout.detailWidth - 14, layout.compact).size() - capacity);
        this.detailScroll = Mth.clamp(this.detailScroll + delta, 0, maxScroll);
    }

    private static void setBounds(Button button, int x, int y, int width, int height) {
        button.setX(x);
        button.setY(y);
        button.setWidth(width);
        button.setHeight(height);
    }

    private Layout layout() {
        int bodyY = OUTER_MARGIN + HEADER_HEIGHT;
        int bodyBottom = this.height - OUTER_MARGIN - FOOTER_HEIGHT;
        int availableWidth = this.width - OUTER_MARGIN * 2;
        boolean compact = this.width < 560 || this.height < 330;
        int categoryWidth = compact ? Math.max(78, availableWidth / 4) : Math.max(122, availableWidth / 6);
        int categoryX = OUTER_MARGIN;
        int listX = categoryX + categoryWidth + 5;
        if (!compact) {
            int detailWidth = Math.max(160, availableWidth / 4);
            int listWidth = this.width - OUTER_MARGIN - listX - detailWidth - 5;
            int detailX = listX + listWidth + 5;
            return new Layout(categoryX, categoryWidth, listX, listWidth, detailX, detailWidth,
                    bodyY, bodyBottom, bodyBottom, bodyY, bodyBottom - bodyY, false);
        }
        int detailHeight = Math.max(56, Math.min(92, (bodyBottom - bodyY) / 3));
        int listBottom = bodyBottom - detailHeight - 5;
        int listWidth = this.width - OUTER_MARGIN - listX;
        return new Layout(categoryX, categoryWidth, listX, listWidth, listX, listWidth,
                bodyY, bodyBottom, listBottom, listBottom + 5, detailHeight, true);
    }

    private record Layout(int categoryX, int categoryWidth, int listX, int listWidth, int detailX, int detailWidth,
                          int bodyY, int bodyBottom, int listBottom, int detailY, int detailHeight, boolean compact) {
    }

    private record DetailLine(net.minecraft.util.FormattedCharSequence text, int color) {
    }

    private enum Filter {
        ALL("screen.miningdim.fishing_journal.filter_all"),
        COLLECTED("screen.miningdim.fishing_journal.filter_collected"),
        MISSING("screen.miningdim.fishing_journal.filter_missing");

        private final String translationKey;

        Filter(String translationKey) {
            this.translationKey = translationKey;
        }
    }
}
