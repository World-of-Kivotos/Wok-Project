package com.miningdim.job.munitions.client;

import com.miningdim.core.MiningConstants;
import com.miningdim.job.ClientJobState;
import com.miningdim.job.JobId;
import com.miningdim.job.JobXpCurve;
import com.miningdim.job.munitions.MunitionsCaliber;
import com.miningdim.job.munitions.MunitionsLevels;
import com.miningdim.job.munitions.menu.MunitionsBenchMenu;
import com.miningdim.menu.AbstractMiningScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.PlayerFaceRenderer;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Inventory;
import org.lwjgl.glfw.GLFW;

import java.util.Locale;

public final class MunitionsBenchScreen extends AbstractMiningScreen<MunitionsBenchMenu> {

    private static final ResourceLocation BG =
            new ResourceLocation(MiningConstants.MODID, "textures/gui/container/munitions_bench.png");
    private static final ResourceLocation UI_FONT =
            new ResourceLocation(MiningConstants.MODID, "textures/gui/container/munitions_ui_font.png");
    private static final ResourceLocation TITLES =
            new ResourceLocation(MiningConstants.MODID, "textures/gui/container/munitions_titles.png");
    private static final ResourceLocation AMMO_PROFILES =
            new ResourceLocation(MiningConstants.MODID, "textures/gui/container/munitions_ammo_profiles.png");

    private static final int W = 360;
    private static final int H = 240;
    private static final int TEXTURE_SCALE = 3;
    private static final int TEX_W = W * TEXTURE_SCALE;
    private static final int TEX_H = H * TEXTURE_SCALE;
    private static final int TITLE_W = 220;
    private static final int TITLE_H = 32;
    private static final int TITLE_SRC_W = TITLE_W * TEXTURE_SCALE;
    private static final int TITLE_SRC_H = TITLE_H * TEXTURE_SCALE;
    private static final int TITLE_TEX_W = TITLE_SRC_W;
    private static final int TITLE_TEX_H = TITLE_SRC_H * 11;
    private static final int AMMO_PROFILE_W = 154;
    private static final int AMMO_PROFILE_H = 40;
    private static final int AMMO_PROFILE_SRC_W = AMMO_PROFILE_W * TEXTURE_SCALE;
    private static final int AMMO_PROFILE_SRC_H = AMMO_PROFILE_H * TEXTURE_SCALE;
    private static final int AMMO_PROFILE_TEX_W = AMMO_PROFILE_SRC_W;
    private static final int AMMO_PROFILE_TEX_H = AMMO_PROFILE_SRC_H * 10;
    private static final String SMOOTH_GLYPHS = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ_/:.- ";
    private static final int GLYPH_W = 6;
    private static final int GLYPH_H = 8;
    private static final int GLYPH_ADVANCE = 5;
    private static final int GLYPH_TEXTURE_SCALE = 4;
    private static final int GLYPH_SRC_W = GLYPH_W * GLYPH_TEXTURE_SCALE;
    private static final int GLYPH_SRC_H = GLYPH_H * GLYPH_TEXTURE_SCALE;
    private static final int GLYPH_TEX_W = SMOOTH_GLYPHS.length() * GLYPH_SRC_W;
    private static final int GLYPH_TEX_H = GLYPH_SRC_H;

    private static final MunitionsCaliber.Category[] CATEGORY_ORDER = {
            MunitionsCaliber.Category.PISTOL,
            MunitionsCaliber.Category.RIFLE,
            MunitionsCaliber.Category.SHOTGUN,
            MunitionsCaliber.Category.SNIPER,
            MunitionsCaliber.Category.EXPLOSIVE
    };
    private static final int SELECTOR_ROWS = 5;
    private static final int CATEGORY_BTN_X = 30;
    private static final int SUBCALIBER_BTN_X = 56;
    private static final int CAL_BTN_Y = 102;
    private static final int CAL_BTN_W = 22;
    private static final int CAL_BTN_H = 12;
    private static final int CAL_BTN_Y_GAP = 1;

    private static final int PLAYER_FACE_X = 31;
    private static final int PLAYER_FACE_Y = 46;
    private static final int PLAYER_FACE_SIZE = 18;
    private static final int PLAYER_XP_BAR_X = 31;
    private static final int PLAYER_XP_BAR_Y = 74;
    private static final int PLAYER_XP_BAR_W = 49;
    private static final int PLAYER_XP_BAR_H = 3;
    private static final int PLAYER_XP_HOVER_PAD = 3;

    private static final int SLOT_PRIMER_X = 296;
    private static final int SLOT_PRIMER_Y = 158;
    private static final int SLOT_CASING_X = 322;
    private static final int SLOT_CASING_Y = 158;
    private static final int SLOT_BULLET_HEAD_X = 296;
    private static final int SLOT_BULLET_HEAD_Y = 184;
    private static final int SLOT_PROPELLANT_X = 322;
    private static final int SLOT_PROPELLANT_Y = 184;

    private static final int BAR_X = 96;
    private static final int BAR_Y = 139;
    private static final int BAR_W = 172;
    private static final int BAR_H = 5;
    private static final int BUFFER_COUNT_X = 320;
    private static final int BUFFER_COUNT_Y = 130;
    private static final int CRAFT_CONTROL_BACKDROP_X = 24;
    private static final int CRAFT_CONTROL_BACKDROP_Y = 190;
    private static final int CRAFT_CONTROL_BACKDROP_W = 54;
    private static final int CRAFT_CONTROL_BACKDROP_H = 38;
    private static final int CRAFT_BUTTON_X = 29;
    private static final int CRAFT_BUTTON_Y = 196;
    private static final int CRAFT_BUTTON_W = 44;
    private static final int CRAFT_BUTTON_H = 15;
    private static final int CRAFT_MODE_BUTTON_X = 29;
    private static final int CRAFT_MODE_BUTTON_Y = 214;
    private static final int CRAFT_MODE_BUTTON_W = 44;
    private static final int CRAFT_MODE_BUTTON_H = 10;
    private static final int CANCEL_DIALOG_W = 150;
    private static final int CANCEL_DIALOG_H = 72;
    private static final int CANCEL_DIALOG_X = (W - CANCEL_DIALOG_W) / 2;
    private static final int CANCEL_DIALOG_Y = 80;
    private static final int CANCEL_CONFIRM_X = CANCEL_DIALOG_X + 18;
    private static final int CANCEL_KEEP_X = CANCEL_DIALOG_X + 80;
    private static final int CANCEL_BUTTON_Y = CANCEL_DIALOG_Y + 48;
    private static final int CANCEL_BUTTON_W = 52;
    private static final int CANCEL_BUTTON_H = 14;
    private static final int STATUS_LAMP_Y = 20;
    private static final int[] STATUS_LAMP_X = {28, 43, 58};
    private static final int[] STATUS_LAMP_DIM = {0xFF7F3034, 0xFF7D5B2E, 0xFF2F6F4C};
    private static final int[] STATUS_LAMP_BASE = {0xFFE05258, 0xFFE2A53F, 0xFF43BC6E};
    private static final int[] STATUS_LAMP_BRIGHT = {0xFFFF747D, 0xFFFFD569, 0xFF74F79F};
    private static final int[] STATUS_LAMP_GLOW = {0x44FF424C, 0x44FFC64D, 0x4455F091};
    private static final int SEARCH_BUTTON_X = 314;
    private static final int SEARCH_BUTTON_Y = 20;
    private static final int SEARCH_BUTTON_W = 24;
    private static final int SEARCH_BUTTON_H = 24;
    private static final int SEARCH_PANEL_X = 205;
    private static final int SEARCH_PANEL_Y = 45;
    private static final int SEARCH_PANEL_W = 116;
    private static final int SEARCH_PANEL_H = 87;
    private static final int SEARCH_FIELD_X = SEARCH_PANEL_X + 7;
    private static final int SEARCH_FIELD_Y = SEARCH_PANEL_Y + 8;
    private static final int SEARCH_FIELD_W = SEARCH_PANEL_W - 14;
    private static final int SEARCH_FIELD_H = 14;
    private static final int SEARCH_RESULT_X = SEARCH_PANEL_X + 7;
    private static final int SEARCH_RESULT_Y = SEARCH_PANEL_Y + 27;
    private static final int SEARCH_RESULT_W = SEARCH_PANEL_W - 14;
    private static final int SEARCH_RESULT_H = 11;
    private static final int SEARCH_RESULT_GAP = 2;
    private static final int SEARCH_MAX_RESULTS = 5;
    private static final int SEARCH_QUERY_MAX = 18;

    private MunitionsCaliber.Category selectedCategory;
    private boolean confirmingCancel;
    private boolean searchOpen;
    private String searchQuery = "";
    private int categoryScrollOffset;
    private int caliberScrollOffset;
    private int searchScrollOffset;

    public MunitionsBenchScreen(MunitionsBenchMenu menu, Inventory inv, Component title) {
        super(menu, inv, title, BG, W, H);
        this.titleLabelX = 0;
        this.titleLabelY = 0;
        this.inventoryLabelX = 0;
        this.inventoryLabelY = 0;
    }

    @Override
    protected void renderBg(GuiGraphics graphics, float partialTick, int mouseX, int mouseY) {
        int x = (this.width - W) / 2;
        int y = (this.height - H) / 2;
        graphics.blit(BG, x, y, W, H, 0.0F, 0.0F, TEX_W, TEX_H, TEX_W, TEX_H);
        renderDynamicContent(graphics, x, y);
    }

    @Override
    protected void renderLabels(GuiGraphics graphics, int mouseX, int mouseY) {
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        super.render(graphics, mouseX, mouseY, partialTick);
        renderMunitionsXpTooltip(graphics, mouseX, mouseY);
        if (!menu.isCraftingActive()) {
            confirmingCancel = false;
        }
        if (confirmingCancel) {
            renderCancelCraftDialog(graphics);
        }
    }

    private void renderDynamicContent(GuiGraphics graphics, int left, int top) {
        renderRunningIndicators(graphics, left, top);
        renderFactoryTitle(graphics, left, top);
        renderPlayerInfo(graphics, left, top);
        renderAmmoPreview(graphics, left, top);
        renderCaliberTabs(graphics, left, top);
        renderAmmoInfo(graphics, left, top);
        renderProductionProgress(graphics, left, top);
        renderOutputPanel(graphics, left, top);
        renderMaterialInputs(graphics, left, top);
        renderLockState(graphics, left, top);
        renderCraftControls(graphics, left, top);
        renderSearchPanel(graphics, left, top);
    }

    private void renderRunningIndicators(GuiGraphics graphics, int left, int top) {
        if (!menu.isCraftingActive()) {
            return;
        }
        long now = System.currentTimeMillis();
        renderStatusLamps(graphics, left, top, now);
        renderMovingStrip(graphics, left + 84, top + 8, 254, 2, now, 0);
        renderMovingStrip(graphics, left + 96, top + 54, 184, 2, now, 70);
        renderMovingStrip(graphics, left + 95, top + 126, 174, 2, now, 140);
        renderMovingStrip(graphics, left + 95, top + 144, 174, 2, now, 210);
        renderMovingStrip(graphics, left + 280, top + 228, 54, 2, now, 280);
    }

    private static void renderStatusLamps(GuiGraphics graphics, int left, int top, long now) {
        int active = (int) ((now / 170L) % STATUS_LAMP_X.length);
        int soft = (active + STATUS_LAMP_X.length - 1) % STATUS_LAMP_X.length;
        for (int i = 0; i < STATUS_LAMP_X.length; i++) {
            int color = i == active ? STATUS_LAMP_BRIGHT[i] : i == soft ? STATUS_LAMP_BASE[i] : STATUS_LAMP_DIM[i];
            int glow = i == active ? STATUS_LAMP_GLOW[i] : 0x00000000;
            renderStatusLamp(graphics, left + STATUS_LAMP_X[i], top + STATUS_LAMP_Y, color, glow, i == active);
        }
    }

    private static void renderStatusLamp(GuiGraphics graphics, int cx, int cy, int color, int glow, boolean bright) {
        if (bright) {
            graphics.fill(cx - 7, cy - 5, cx + 8, cy + 6, glow);
            graphics.fill(cx - 5, cy - 7, cx + 6, cy + 8, glow);
        }
        graphics.fill(cx - 3, cy - 5, cx + 4, cy + 6, 0x66000000);
        graphics.fill(cx - 5, cy - 3, cx + 6, cy + 4, 0x66000000);
        graphics.fill(cx - 3, cy - 4, cx + 4, cy + 5, color);
        graphics.fill(cx - 4, cy - 3, cx + 5, cy + 4, color);
        graphics.fill(cx - 2, cy - 4, cx + 2, cy - 3, bright ? 0xAAFFFFFF : 0x55FFFFFF);
    }

    private static void renderMovingStrip(GuiGraphics graphics, int x, int y, int w, int h, long now, int offset) {
        graphics.fill(x, y, x + w, y + h, 0x332ED8BF);
        int span = Math.max(18, w / 5);
        int travel = w + span * 2;
        int head = (int) (((now / 10L + offset) % travel) - span);
        fillClipped(graphics, x + head - span / 2, y, span / 2, h, x, x + w, 0x4459FFE0);
        fillClipped(graphics, x + head, y, span, h, x, x + w, 0xDD78FFE7);
        fillClipped(graphics, x + head + span, y, span / 2, h, x, x + w, 0x6652E8D1);
    }

    private static void fillClipped(GuiGraphics graphics, int x, int y, int w, int h,
                                    int minX, int maxX, int argb) {
        int start = Math.max(x, minX);
        int end = Math.min(x + w, maxX);
        if (start < end) {
            graphics.fill(start, y, end, y + h, argb);
        }
    }

    private void renderCaliberTabs(GuiGraphics graphics, int left, int top) {
        int level = menu.effectiveMunitionsLevel();
        int selected = menu.selectedCaliberIndex();
        MunitionsCaliber selectedCaliber = MunitionsCaliber.byIndex(selected);
        MunitionsCaliber.Category category = visibleCategory(selectedCaliber);
        categoryScrollOffset = clamp(categoryScrollOffset, 0, maxCategoryScroll());
        caliberScrollOffset = clamp(caliberScrollOffset, 0, maxCaliberScroll(category));

        for (int row = 0; row < SELECTOR_ROWS; row++) {
            int y = top + CAL_BTN_Y + row * (CAL_BTN_H + CAL_BTN_Y_GAP);
            drawChoiceButton(graphics, left + CATEGORY_BTN_X, y, "", false, true);
            drawChoiceButton(graphics, left + SUBCALIBER_BTN_X, y, "", false, true);
        }

        for (int row = 0; row < CATEGORY_ORDER.length; row++) {
            int optionIndex = row + categoryScrollOffset;
            if (optionIndex >= CATEGORY_ORDER.length || row >= SELECTOR_ROWS) {
                break;
            }
            MunitionsCaliber.Category option = CATEGORY_ORDER[optionIndex];
            int x = left + CATEGORY_BTN_X;
            int y = top + CAL_BTN_Y + row * (CAL_BTN_H + CAL_BTN_Y_GAP);
            boolean enabled = categoryHasUnlockedCaliber(level, option);
            boolean current = option == category;
            drawChoiceButton(graphics, x, y, option.label(), current, enabled);
        }
        drawTinyScrollbar(graphics, left + CATEGORY_BTN_X + CAL_BTN_W + 1, top + CAL_BTN_Y,
                SELECTOR_ROWS * (CAL_BTN_H + CAL_BTN_Y_GAP) - CAL_BTN_Y_GAP,
                categoryScrollOffset, CATEGORY_ORDER.length, SELECTOR_ROWS);

        int row = 0;
        int skipped = 0;
        for (MunitionsCaliber caliber : MunitionsCaliber.values()) {
            if (caliber.category() != category) {
                continue;
            }
            if (skipped++ < caliberScrollOffset) {
                continue;
            }
            if (row >= SELECTOR_ROWS) {
                break;
            }
            int x = left + SUBCALIBER_BTN_X;
            int y = top + CAL_BTN_Y + row * (CAL_BTN_H + CAL_BTN_Y_GAP);
            drawChoiceButton(graphics, x, y, displayCaliberLabel(caliber), caliber.index() == selected,
                    MunitionsLevels.isCaliberUnlocked(level, caliber));
            row++;
        }
        drawTinyScrollbar(graphics, left + SUBCALIBER_BTN_X + CAL_BTN_W + 1, top + CAL_BTN_Y,
                SELECTOR_ROWS * (CAL_BTN_H + CAL_BTN_Y_GAP) - CAL_BTN_Y_GAP,
                caliberScrollOffset, caliberCount(category), SELECTOR_ROWS);
    }

    private void renderPlayerInfo(GuiGraphics graphics, int left, int top) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null) {
            PlayerFaceRenderer.draw(graphics, mc.player.getSkinTextureLocation(),
                    left + PLAYER_FACE_X, top + PLAYER_FACE_Y, PLAYER_FACE_SIZE);
            int fill = playerXpProgressPixels();
            graphics.fill(left + PLAYER_XP_BAR_X, top + PLAYER_XP_BAR_Y,
                    left + PLAYER_XP_BAR_X + fill, top + PLAYER_XP_BAR_Y + PLAYER_XP_BAR_H, 0xFF35D2A4);
            graphics.fill(left + PLAYER_XP_BAR_X, top + PLAYER_XP_BAR_Y,
                    left + PLAYER_XP_BAR_X + fill, top + PLAYER_XP_BAR_Y + 1, 0xFF75F0CA);
        }
    }

    private void renderMunitionsXpTooltip(GuiGraphics graphics, int mouseX, int mouseY) {
        int left = (this.width - W) / 2;
        int top = (this.height - H) / 2;
        if (!inRect(mouseX, mouseY, left + PLAYER_XP_BAR_X, top + PLAYER_XP_BAR_Y - PLAYER_XP_HOVER_PAD,
                PLAYER_XP_BAR_W, PLAYER_XP_BAR_H + PLAYER_XP_HOVER_PAD * 2)) {
            return;
        }
        long shownXp = playerShownXp();
        long nextXp = playerNextLevelXp();
        graphics.renderTooltip(this.font, Component.literal("经验: " + shownXp + "/" + nextXp), mouseX, mouseY);
    }

    private static int playerXpProgressPixels() {
        int level = playerLevel();
        long xp = playerShownXp();
        long levelStart = JobXpCurve.cumulativeXpForLevel(level);
        long next = playerNextLevelXp();
        if (level >= JobXpCurve.MAX_LEVEL || next <= levelStart) {
            return PLAYER_XP_BAR_W;
        }
        long insideLevel = Math.max(0L, Math.min(next - levelStart, xp - levelStart));
        return (int) Math.max(0L, Math.min(PLAYER_XP_BAR_W, insideLevel * PLAYER_XP_BAR_W / (next - levelStart)));
    }

    private static int playerLevel() {
        return Math.max(JobXpCurve.MIN_LEVEL, Math.min(JobXpCurve.MAX_LEVEL, ClientJobState.level(JobId.MUNITIONS)));
    }

    private static long playerShownXp() {
        return Math.max(0L, Math.min(JobXpCurve.GRADUATION_XP, ClientJobState.xp(JobId.MUNITIONS)));
    }

    private static long playerNextLevelXp() {
        int level = playerLevel();
        if (level >= JobXpCurve.MAX_LEVEL) {
            return JobXpCurve.GRADUATION_XP;
        }
        return JobXpCurve.cumulativeXpForLevel(level + 1);
    }

    private void renderFactoryTitle(GuiGraphics graphics, int left, int top) {
        int level = Math.max(0, Math.min(10, menu.effectiveMunitionsLevel()));
        graphics.blit(TITLES, left + 98, top + 22, TITLE_W, TITLE_H,
                0.0F, level * TITLE_SRC_H, TITLE_SRC_W, TITLE_SRC_H, TITLE_TEX_W, TITLE_TEX_H);
    }

    private void renderAmmoPreview(GuiGraphics graphics, int left, int top) {
        MunitionsCaliber caliber = MunitionsCaliber.byIndex(menu.selectedCaliberIndex());
        int row = ammoProfileRow(caliber);
        graphics.blit(AMMO_PROFILES, left + 108, top + 80, AMMO_PROFILE_W, AMMO_PROFILE_H,
                0.0F, row * AMMO_PROFILE_SRC_H,
                AMMO_PROFILE_SRC_W, AMMO_PROFILE_SRC_H, AMMO_PROFILE_TEX_W, AMMO_PROFILE_TEX_H);
    }

    private void renderAmmoInfo(GuiGraphics graphics, int left, int top) {
        MunitionsCaliber caliber = MunitionsCaliber.byIndex(menu.selectedCaliberIndex());
        drawSmoothTextCenteredScaled(graphics, displayCaliberLabel(caliber), left + 245, top + 68, 0xFF68C7AD, 0.9F);
    }

    private void renderProductionProgress(GuiGraphics graphics, int left, int top) {
        int required = menu.productionRequiredTicks();
        int progress = Math.max(0, Math.min(required, menu.productionProgressTicks()));
        int remaining = required <= 0 ? 0 : Math.max(0, required - progress);
        String time = formatTicks(remaining);
        drawSmoothTextCenteredScaled(graphics, time, left + 236, top + 128, 0xFFD9DFEA, 0.9F);
        int filled = required <= 0 ? 0 : (int) ((long) progress * BAR_W / required);
        int innerFilled = Math.max(0, Math.min(BAR_W, filled));
        graphics.fill(left + BAR_X, top + BAR_Y, left + BAR_X + innerFilled, top + BAR_Y + BAR_H, 0xFF30BE99);
        graphics.fill(left + BAR_X, top + BAR_Y, left + BAR_X + innerFilled, top + BAR_Y + 1, 0xFF7AF4CF);
    }

    private void renderOutputPanel(GuiGraphics graphics, int left, int top) {
        String count = menu.bufferedRounds() + "/" + menu.bufferCap();
        float scale = count.length() > 6 ? 0.72F : 0.82F;
        drawSmoothTextCenteredScaled(graphics, count, left + BUFFER_COUNT_X, top + BUFFER_COUNT_Y, 0xFFD6DCE7, scale);
    }

    private void renderMaterialInputs(GuiGraphics graphics, int left, int top) {
        renderGhostPart(graphics, 0, 0, left + SLOT_PRIMER_X, top + SLOT_PRIMER_Y);
        renderGhostPart(graphics, 1, 1, left + SLOT_CASING_X, top + SLOT_CASING_Y);
        renderGhostPart(graphics, 2, 2, left + SLOT_BULLET_HEAD_X, top + SLOT_BULLET_HEAD_Y);
        renderGhostPart(graphics, 3, 3, left + SLOT_PROPELLANT_X, top + SLOT_PROPELLANT_Y);
    }

    private void renderGhostPart(GuiGraphics graphics, int slotIndex, int kind, int x, int y) {
        if (!menu.getSlot(slotIndex).hasItem()) {
            drawGhostSilhouette(graphics, x, y, kind);
        }
    }

    private void renderLockState(GuiGraphics graphics, int left, int top) {
        if (menu.isLocked()) {
            int x = left + 329;
            int y = top + 27;
            graphics.fill(x, y, x + 16, y + 16, 0xCC73242B);
            drawLockIcon(graphics, x + 4, y + 4);
        }
    }

    private void renderCraftControls(GuiGraphics graphics, int left, int top) {
        int panelX = left + CRAFT_CONTROL_BACKDROP_X;
        int panelY = top + CRAFT_CONTROL_BACKDROP_Y;
        graphics.fill(panelX + 2, panelY + 3,
                panelX + CRAFT_CONTROL_BACKDROP_W + 2, panelY + CRAFT_CONTROL_BACKDROP_H + 3, 0xAA000000);
        graphics.fill(panelX, panelY,
                panelX + CRAFT_CONTROL_BACKDROP_W, panelY + CRAFT_CONTROL_BACKDROP_H, 0xFF12141C);
        graphics.fill(panelX + 1, panelY + 1,
                panelX + CRAFT_CONTROL_BACKDROP_W - 1, panelY + CRAFT_CONTROL_BACKDROP_H - 1, 0xFF242633);
        graphics.fill(panelX + 4, panelY + 4,
                panelX + CRAFT_CONTROL_BACKDROP_W - 4, panelY + 5, 0xFF4E5368);
        graphics.fill(panelX + 5, panelY + CRAFT_CONTROL_BACKDROP_H - 4,
                panelX + CRAFT_CONTROL_BACKDROP_W - 5, panelY + CRAFT_CONTROL_BACKDROP_H - 3, 0xFF31D2B4);
        boolean active = menu.isCraftingActive();
        int x = left + CRAFT_BUTTON_X;
        int y = top + CRAFT_BUTTON_Y;
        int fill = active ? 0xFF6A2D39 : 0xFF1DAE96;
        int inner = active ? 0xFF522832 : 0xFF24C7AA;
        graphics.fill(x + 1, y + 2, x + CRAFT_BUTTON_W + 1, y + CRAFT_BUTTON_H + 2, 0xAA000000);
        graphics.fill(x, y, x + CRAFT_BUTTON_W, y + CRAFT_BUTTON_H, 0xFF11131B);
        graphics.fill(x + 1, y + 1, x + CRAFT_BUTTON_W - 1, y + CRAFT_BUTTON_H - 1, fill);
        graphics.fill(x + 3, y + 2, x + CRAFT_BUTTON_W - 3, y + CRAFT_BUTTON_H - 3, inner);
        graphics.fill(x + 4, y + 2, x + CRAFT_BUTTON_W - 4, y + 3, active ? 0xFFB66B78 : 0xFF73F4D5);
        drawVanillaTextCenteredScaled(graphics, active ? "取消" : "制作",
                x + CRAFT_BUTTON_W / 2.0F, y + 4.0F, 0xFFFFFFFF, 0.68F);

        int mx = left + CRAFT_MODE_BUTTON_X;
        int my = top + CRAFT_MODE_BUTTON_Y;
        boolean continuous = menu.isContinuousCrafting();
        graphics.fill(mx + 1, my + 2, mx + CRAFT_MODE_BUTTON_W + 1, my + CRAFT_MODE_BUTTON_H + 2, 0xAA000000);
        graphics.fill(mx, my, mx + CRAFT_MODE_BUTTON_W, my + CRAFT_MODE_BUTTON_H, 0xFF11131B);
        graphics.fill(mx + 1, my + 1, mx + CRAFT_MODE_BUTTON_W - 1, my + CRAFT_MODE_BUTTON_H - 1,
                continuous ? 0xFF2B4856 : 0xFF2A2D38);
        graphics.fill(mx + 3, my + CRAFT_MODE_BUTTON_H - 2, mx + CRAFT_MODE_BUTTON_W - 3, my + CRAFT_MODE_BUTTON_H - 1,
                continuous ? 0xFF31D2B4 : 0xFF4B5061);
        drawVanillaTextCenteredScaled(graphics, continuous ? "连续" : "单次",
                mx + CRAFT_MODE_BUTTON_W / 2.0F, my + 1.5F, continuous ? 0xFF80F0D0 : 0xFFD8DDE8, 0.58F);
    }

    private void renderSearchPanel(GuiGraphics graphics, int left, int top) {
        if (!searchOpen) {
            return;
        }
        int panelX = left + SEARCH_PANEL_X;
        int panelY = top + SEARCH_PANEL_Y;
        graphics.fill(panelX + 3, panelY + 4,
                panelX + SEARCH_PANEL_W + 3, panelY + SEARCH_PANEL_H + 4, 0xAA000000);
        graphics.fill(panelX, panelY, panelX + SEARCH_PANEL_W, panelY + SEARCH_PANEL_H, 0xFF10131B);
        graphics.fill(panelX + 1, panelY + 1,
                panelX + SEARCH_PANEL_W - 1, panelY + SEARCH_PANEL_H - 1, 0xFF242735);
        graphics.fill(panelX + 6, panelY + 5, panelX + SEARCH_PANEL_W - 6, panelY + 6, 0xFF586074);
        graphics.fill(panelX + 6, panelY + SEARCH_PANEL_H - 5,
                panelX + SEARCH_PANEL_W - 6, panelY + SEARCH_PANEL_H - 3, 0xFF31D2B4);

        int buttonX = left + SEARCH_BUTTON_X;
        int buttonY = top + SEARCH_BUTTON_Y;
        graphics.fill(buttonX - 1, buttonY - 1, buttonX + SEARCH_BUTTON_W + 1, buttonY + SEARCH_BUTTON_H + 1,
                0x552FD7C1);
        graphics.fill(buttonX + 3, buttonY + SEARCH_BUTTON_H - 3,
                buttonX + SEARCH_BUTTON_W - 3, buttonY + SEARCH_BUTTON_H - 2, 0xFF31D2B4);

        int fieldX = left + SEARCH_FIELD_X;
        int fieldY = top + SEARCH_FIELD_Y;
        graphics.fill(fieldX, fieldY, fieldX + SEARCH_FIELD_W, fieldY + SEARCH_FIELD_H, 0xFF111722);
        graphics.fill(fieldX + 1, fieldY + 1, fieldX + SEARCH_FIELD_W - 1, fieldY + SEARCH_FIELD_H - 1,
                0xFF1B2030);
        graphics.fill(fieldX + 4, fieldY + SEARCH_FIELD_H - 3,
                fieldX + SEARCH_FIELD_W - 4, fieldY + SEARCH_FIELD_H - 2, 0xFF31D2B4);

        if (searchQuery.isEmpty()) {
            drawSmoothTextScaled(graphics, "SEARCH", fieldX + 6, fieldY + 3, 0xFF707787, 0.62F);
        } else {
            drawSearchQuery(graphics, searchQuery, fieldX + 6, fieldY + 3, SEARCH_FIELD_W - 12);
        }

        int level = menu.effectiveMunitionsLevel();
        int totalResults = searchResultCount();
        searchScrollOffset = clamp(searchScrollOffset, 0, Math.max(0, totalResults - SEARCH_MAX_RESULTS));
        int resultCount = 0;
        int skipped = 0;
        for (MunitionsCaliber caliber : MunitionsCaliber.values()) {
            if (!matchesSearch(caliber, searchQuery)) {
                continue;
            }
            if (skipped++ < searchScrollOffset) {
                continue;
            }
            if (resultCount >= SEARCH_MAX_RESULTS) {
                break;
            }
            renderSearchResult(graphics, left, top, caliber, resultCount, level);
            resultCount++;
        }
        drawTinyScrollbar(graphics, left + SEARCH_PANEL_X + SEARCH_PANEL_W - 5, top + SEARCH_RESULT_Y,
                SEARCH_MAX_RESULTS * (SEARCH_RESULT_H + SEARCH_RESULT_GAP) - SEARCH_RESULT_GAP,
                searchScrollOffset, totalResults, SEARCH_MAX_RESULTS);
        if (totalResults == 0) {
            drawSmoothTextScaled(graphics, "NO MATCH", left + SEARCH_RESULT_X + 8,
                    top + SEARCH_RESULT_Y + 6, 0xFF747B8A, 0.62F);
        }
    }

    private void renderSearchResult(GuiGraphics graphics, int left, int top,
                                    MunitionsCaliber caliber, int row, int level) {
        int x = left + SEARCH_RESULT_X;
        int y = top + SEARCH_RESULT_Y + row * (SEARCH_RESULT_H + SEARCH_RESULT_GAP);
        boolean selected = caliber.index() == menu.selectedCaliberIndex();
        boolean unlocked = MunitionsLevels.isCaliberUnlocked(level, caliber);
        int frame = selected ? 0xFF735735 : 0xFF151821;
        int fill = selected ? 0xFF3A3329 : unlocked ? 0xFF272B38 : 0xFF20222A;
        int text = selected ? 0xFFFFDC8D : unlocked ? 0xFFDCE3EE : 0xFF737987;
        graphics.fill(x, y, x + SEARCH_RESULT_W, y + SEARCH_RESULT_H, frame);
        graphics.fill(x + 1, y + 1, x + SEARCH_RESULT_W - 1, y + SEARCH_RESULT_H - 1, fill);
        graphics.fill(x + 4, y + SEARCH_RESULT_H - 2, x + SEARCH_RESULT_W - 4, y + SEARCH_RESULT_H - 1,
                selected ? 0xFFC6974B : unlocked ? 0xFF31D2B4 : 0xFF4D5360);
        drawSmoothTextScaled(graphics, displayCaliberLabel(caliber), x + 5, y + 2, text, 0.58F);
        drawSmoothTextScaled(graphics, "L" + caliber.unlockLevel(), x + SEARCH_RESULT_W - 18, y + 2,
                unlocked ? 0xFF7CE8CF : 0xFF777D8A, 0.55F);
    }

    private void drawSearchQuery(GuiGraphics graphics, String value, int x, int y, int maxWidth) {
        float scale = 0.62F;
        String visible = value;
        while (!visible.isEmpty() && this.font.width(visible) * scale > maxWidth) {
            visible = visible.substring(1);
        }
        graphics.pose().pushPose();
        graphics.pose().translate(x, y, 0.0F);
        graphics.pose().scale(scale, scale, 1.0F);
        graphics.drawString(this.font, visible, 0, 0, 0xFFE8EDF5, false);
        graphics.pose().popPose();
        if ((System.currentTimeMillis() / 450L) % 2L == 0L) {
            int cursorX = x + (int) (this.font.width(visible) * scale) + 2;
            graphics.fill(cursorX, y, cursorX + 1, y + 8, 0xFF78FFE7);
        }
    }

    private void renderCancelCraftDialog(GuiGraphics graphics) {
        int left = (this.width - W) / 2;
        int top = (this.height - H) / 2;
        int x = left + CANCEL_DIALOG_X;
        int y = top + CANCEL_DIALOG_Y;

        graphics.fill(left, top, left + W, top + H, 0x99000000);
        graphics.fill(x + 3, y + 4, x + CANCEL_DIALOG_W + 3, y + CANCEL_DIALOG_H + 4, 0xAA000000);
        graphics.fill(x, y, x + CANCEL_DIALOG_W, y + CANCEL_DIALOG_H, 0xFF11131B);
        graphics.fill(x + 1, y + 1, x + CANCEL_DIALOG_W - 1, y + CANCEL_DIALOG_H - 1, 0xFF252837);
        graphics.fill(x + 8, y + 7, x + CANCEL_DIALOG_W - 8, y + 8, 0xFF596072);
        graphics.fill(x + 8, y + CANCEL_DIALOG_H - 6, x + CANCEL_DIALOG_W - 8, y + CANCEL_DIALOG_H - 4, 0xFF31D2B4);

        drawVanillaTextCenteredScaled(graphics, "取消制作", x + CANCEL_DIALOG_W / 2.0F, y + 15.0F,
                0xFFF2F5FA, 0.86F);
        drawVanillaTextCenteredScaled(graphics, "已消耗材料不会返还", x + CANCEL_DIALOG_W / 2.0F, y + 31.0F,
                0xFFAEB5C5, 0.66F);

        drawDialogButton(graphics, left + CANCEL_CONFIRM_X, top + CANCEL_BUTTON_Y,
                "确认取消", 0xFF65313B, 0xFF8B3A47, 0xFFFFD6DD);
        drawDialogButton(graphics, left + CANCEL_KEEP_X, top + CANCEL_BUTTON_Y,
                "继续制作", 0xFF234B46, 0xFF2CBF9F, 0xFFE6FFF8);
    }

    private void drawDialogButton(GuiGraphics graphics, int x, int y, String label,
                                  int fill, int accent, int text) {
        graphics.fill(x + 1, y + 2, x + CANCEL_BUTTON_W + 1, y + CANCEL_BUTTON_H + 2, 0xAA000000);
        graphics.fill(x, y, x + CANCEL_BUTTON_W, y + CANCEL_BUTTON_H, 0xFF11131B);
        graphics.fill(x + 1, y + 1, x + CANCEL_BUTTON_W - 1, y + CANCEL_BUTTON_H - 1, fill);
        graphics.fill(x + 4, y + CANCEL_BUTTON_H - 3, x + CANCEL_BUTTON_W - 4, y + CANCEL_BUTTON_H - 2, accent);
        drawVanillaTextCenteredScaled(graphics, label, x + CANCEL_BUTTON_W / 2.0F, y + 4.0F, text, 0.58F);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        int leftPos = (this.width - W) / 2;
        int topPos = (this.height - H) / 2;
        int level = menu.effectiveMunitionsLevel();
        if (confirmingCancel) {
            if (inRect(mouseX, mouseY, leftPos + CANCEL_CONFIRM_X, topPos + CANCEL_BUTTON_Y,
                    CANCEL_BUTTON_W, CANCEL_BUTTON_H)) {
                confirmingCancel = false;
                sendButton(MunitionsBenchMenu.BUTTON_CANCEL_CRAFT);
                return true;
            }
            if (inRect(mouseX, mouseY, leftPos + CANCEL_KEEP_X, topPos + CANCEL_BUTTON_Y,
                    CANCEL_BUTTON_W, CANCEL_BUTTON_H)) {
                confirmingCancel = false;
                return true;
            }
            return true;
        }
        if (handleSearchClick(mouseX, mouseY, button, leftPos, topPos, level)) {
            return true;
        }
        if (inRect(mouseX, mouseY, leftPos + CRAFT_BUTTON_X, topPos + CRAFT_BUTTON_Y,
                CRAFT_BUTTON_W, CRAFT_BUTTON_H)) {
            if (menu.isCraftingActive()) {
                showCancelCraftConfirmation();
            } else {
                sendButton(MunitionsBenchMenu.BUTTON_START_CRAFT);
            }
            return true;
        }
        if (inRect(mouseX, mouseY, leftPos + CRAFT_MODE_BUTTON_X, topPos + CRAFT_MODE_BUTTON_Y,
                CRAFT_MODE_BUTTON_W, CRAFT_MODE_BUTTON_H)) {
            sendButton(MunitionsBenchMenu.BUTTON_TOGGLE_CONTINUOUS);
            return true;
        }
        categoryScrollOffset = clamp(categoryScrollOffset, 0, maxCategoryScroll());
        for (int row = 0; row < SELECTOR_ROWS; row++) {
            int optionIndex = row + categoryScrollOffset;
            if (optionIndex >= CATEGORY_ORDER.length) {
                break;
            }
            int x = leftPos + CATEGORY_BTN_X;
            int y = topPos + CAL_BTN_Y + row * (CAL_BTN_H + CAL_BTN_Y_GAP);
            if (inRect(mouseX, mouseY, x, y, CAL_BTN_W, CAL_BTN_H)) {
                selectedCategory = CATEGORY_ORDER[optionIndex];
                caliberScrollOffset = 0;
                return true;
            }
        }

        MunitionsCaliber.Category category = visibleCategory(MunitionsCaliber.byIndex(menu.selectedCaliberIndex()));
        caliberScrollOffset = clamp(caliberScrollOffset, 0, maxCaliberScroll(category));
        int row = 0;
        int skipped = 0;
        for (MunitionsCaliber caliber : MunitionsCaliber.values()) {
            if (caliber.category() != category) {
                continue;
            }
            if (skipped++ < caliberScrollOffset) {
                continue;
            }
            if (row >= SELECTOR_ROWS) {
                break;
            }
            int x = leftPos + SUBCALIBER_BTN_X;
            int y = topPos + CAL_BTN_Y + row * (CAL_BTN_H + CAL_BTN_Y_GAP);
            if (inRect(mouseX, mouseY, x, y, CAL_BTN_W, CAL_BTN_H)) {
                selectedCategory = category;
                if (MunitionsLevels.isCaliberUnlocked(level, caliber)) {
                    sendButton(caliber.index());
                }
                return true;
            }
            row++;
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double delta) {
        int left = (this.width - W) / 2;
        int top = (this.height - H) / 2;
        if (confirmingCancel) {
            return true;
        }
        if (searchOpen && inRect(mouseX, mouseY, left + SEARCH_PANEL_X, top + SEARCH_PANEL_Y,
                SEARCH_PANEL_W, SEARCH_PANEL_H)) {
            searchScrollOffset = scrollOffset(searchScrollOffset, delta, maxSearchScroll());
            return true;
        }
        int listY = top + CAL_BTN_Y;
        int listH = SELECTOR_ROWS * (CAL_BTN_H + CAL_BTN_Y_GAP) - CAL_BTN_Y_GAP;
        if (inRect(mouseX, mouseY, left + CATEGORY_BTN_X, listY, CAL_BTN_W, listH)) {
            categoryScrollOffset = scrollOffset(categoryScrollOffset, delta, maxCategoryScroll());
            return true;
        }
        MunitionsCaliber.Category category = visibleCategory(MunitionsCaliber.byIndex(menu.selectedCaliberIndex()));
        if (inRect(mouseX, mouseY, left + SUBCALIBER_BTN_X, listY, CAL_BTN_W, listH)
                || inRect(mouseX, mouseY, left + CATEGORY_BTN_X, listY,
                SUBCALIBER_BTN_X + CAL_BTN_W - CATEGORY_BTN_X, listH)) {
            caliberScrollOffset = scrollOffset(caliberScrollOffset, delta, maxCaliberScroll(category));
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, delta);
    }

    @Override
    public boolean charTyped(char codePoint, int modifiers) {
        if (searchOpen) {
            if (!Character.isISOControl(codePoint) && !Character.isSurrogate(codePoint)
                    && searchQuery.length() < SEARCH_QUERY_MAX) {
                searchQuery += codePoint;
                searchScrollOffset = 0;
            }
            return true;
        }
        return super.charTyped(codePoint, modifiers);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (searchOpen) {
            if (keyCode == GLFW.GLFW_KEY_ESCAPE) {
                searchOpen = false;
                return true;
            }
            if (keyCode == GLFW.GLFW_KEY_BACKSPACE) {
                if (!searchQuery.isEmpty()) {
                    searchQuery = searchQuery.substring(0, searchQuery.length() - 1);
                    searchScrollOffset = 0;
                }
                return true;
            }
            if (keyCode == GLFW.GLFW_KEY_DELETE) {
                searchQuery = "";
                searchScrollOffset = 0;
                return true;
            }
            if (keyCode == GLFW.GLFW_KEY_ENTER || keyCode == GLFW.GLFW_KEY_KP_ENTER) {
                MunitionsCaliber first = firstSearchResult(menu.effectiveMunitionsLevel());
                if (first != null && MunitionsLevels.isCaliberUnlocked(menu.effectiveMunitionsLevel(), first)) {
                    selectSearchResult(first);
                }
                return true;
            }
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    private boolean handleSearchClick(double mouseX, double mouseY, int button, int left, int top, int level) {
        if (button != 0) {
            return false;
        }
        if (inRect(mouseX, mouseY, left + SEARCH_BUTTON_X, top + SEARCH_BUTTON_Y,
                SEARCH_BUTTON_W, SEARCH_BUTTON_H)) {
            searchOpen = !searchOpen;
            searchScrollOffset = 0;
            return true;
        }
        if (!searchOpen) {
            return false;
        }
        MunitionsCaliber result = searchResultAt(mouseX, mouseY, left, top);
        if (result != null) {
            if (MunitionsLevels.isCaliberUnlocked(level, result)) {
                selectSearchResult(result);
            }
            return true;
        }
        if (inRect(mouseX, mouseY, left + SEARCH_PANEL_X, top + SEARCH_PANEL_Y,
                SEARCH_PANEL_W, SEARCH_PANEL_H)) {
            return true;
        }
        searchOpen = false;
        return true;
    }

    private MunitionsCaliber searchResultAt(double mouseX, double mouseY, int left, int top) {
        int resultCount = 0;
        int skipped = 0;
        searchScrollOffset = clamp(searchScrollOffset, 0, maxSearchScroll());
        for (MunitionsCaliber caliber : MunitionsCaliber.values()) {
            if (!matchesSearch(caliber, searchQuery)) {
                continue;
            }
            if (skipped++ < searchScrollOffset) {
                continue;
            }
            if (resultCount >= SEARCH_MAX_RESULTS) {
                return null;
            }
            int x = left + SEARCH_RESULT_X;
            int y = top + SEARCH_RESULT_Y + resultCount * (SEARCH_RESULT_H + SEARCH_RESULT_GAP);
            if (inRect(mouseX, mouseY, x, y, SEARCH_RESULT_W, SEARCH_RESULT_H)) {
                return caliber;
            }
            resultCount++;
        }
        return null;
    }

    private MunitionsCaliber firstSearchResult(int level) {
        for (MunitionsCaliber caliber : MunitionsCaliber.values()) {
            if (matchesSearch(caliber, searchQuery) && MunitionsLevels.isCaliberUnlocked(level, caliber)) {
                return caliber;
            }
        }
        return null;
    }

    private void selectSearchResult(MunitionsCaliber caliber) {
        selectedCategory = caliber.category();
        ensureCategoryVisible(selectedCategory);
        caliberScrollOffset = 0;
        searchScrollOffset = 0;
        sendButton(caliber.index());
        searchOpen = false;
    }

    private void sendButton(int id) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.gameMode != null) {
            mc.gameMode.handleInventoryButtonClick(menu.containerId, id);
        }
    }

    private void showCancelCraftConfirmation() {
        confirmingCancel = true;
    }

    private MunitionsCaliber.Category visibleCategory(MunitionsCaliber selectedCaliber) {
        return selectedCategory != null ? selectedCategory : selectedCaliber.category();
    }

    private static boolean categoryHasUnlockedCaliber(int level, MunitionsCaliber.Category category) {
        for (MunitionsCaliber caliber : MunitionsCaliber.values()) {
            if (caliber.category() == category && MunitionsLevels.isCaliberUnlocked(level, caliber)) {
                return true;
            }
        }
        return false;
    }

    private static int maxCategoryScroll() {
        return Math.max(0, CATEGORY_ORDER.length - SELECTOR_ROWS);
    }

    private static int maxCaliberScroll(MunitionsCaliber.Category category) {
        return Math.max(0, caliberCount(category) - SELECTOR_ROWS);
    }

    private int maxSearchScroll() {
        return Math.max(0, searchResultCount() - SEARCH_MAX_RESULTS);
    }

    private static int caliberCount(MunitionsCaliber.Category category) {
        int count = 0;
        for (MunitionsCaliber caliber : MunitionsCaliber.values()) {
            if (caliber.category() == category) {
                count++;
            }
        }
        return count;
    }

    private int searchResultCount() {
        int count = 0;
        for (MunitionsCaliber caliber : MunitionsCaliber.values()) {
            if (matchesSearch(caliber, searchQuery)) {
                count++;
            }
        }
        return count;
    }

    private static int scrollOffset(int current, double delta, int max) {
        int next = current + (delta > 0.0D ? -1 : 1);
        return clamp(next, 0, max);
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private void ensureCategoryVisible(MunitionsCaliber.Category category) {
        int index = categoryIndex(category);
        if (index < 0) {
            return;
        }
        if (index < categoryScrollOffset) {
            categoryScrollOffset = index;
        } else if (index >= categoryScrollOffset + SELECTOR_ROWS) {
            categoryScrollOffset = index - SELECTOR_ROWS + 1;
        }
        categoryScrollOffset = clamp(categoryScrollOffset, 0, maxCategoryScroll());
    }

    private static int categoryIndex(MunitionsCaliber.Category category) {
        for (int i = 0; i < CATEGORY_ORDER.length; i++) {
            if (CATEGORY_ORDER[i] == category) {
                return i;
            }
        }
        return -1;
    }

    private static int choiceTextColor(boolean selected, boolean enabled) {
        return selected ? 0xFFEAD6A6 : enabled ? 0xFFD2D7E1 : 0xFF707582;
    }

    private void drawChoiceButton(GuiGraphics graphics, int x, int y, String label, boolean selected, boolean enabled) {
        int fill = selected ? 0xFF37332B : enabled ? 0xF0282A34 : 0xF022232B;
        int text = choiceTextColor(selected, enabled);
        graphics.fill(x, y, x + CAL_BTN_W, y + CAL_BTN_H, selected ? 0xFF5C4730 : 0xFF151720);
        graphics.fill(x + 1, y + 1, x + CAL_BTN_W - 1, y + CAL_BTN_H - 1, fill);
        graphics.fill(x + 3, y + 2, x + CAL_BTN_W - 4, y + 3, selected ? 0x669B7A44 : 0x334A4F5C);
        if (selected) {
            graphics.fill(x + 1, y + 2, x + 3, y + CAL_BTN_H - 2, 0xFFB48B4C);
            graphics.fill(x + 3, y + CAL_BTN_H - 2, x + CAL_BTN_W - 3, y + CAL_BTN_H - 1, 0xFF96733D);
        }
        if (!label.isEmpty()) {
            drawChoiceTextCentered(graphics, label, x, y, text);
        }
    }

    private void drawChoiceTextCentered(GuiGraphics graphics, String value, int x, int y, int argb) {
        float scale = supportsSmoothText(value) ? 0.78F : 0.68F;
        int width = this.font.width(value);
        float drawX = x + (CAL_BTN_W - width * scale) / 2.0F;
        float drawY = y + (CAL_BTN_H - 8.0F * scale) / 2.0F - 0.5F;
        graphics.pose().pushPose();
        graphics.pose().translate(drawX, drawY, 0.0F);
        graphics.pose().scale(scale, scale, 1.0F);
        graphics.drawString(this.font, value, 0, 0, argb, false);
        graphics.pose().popPose();
    }

    private static void drawTinyScrollbar(GuiGraphics graphics, int x, int y, int h,
                                          int offset, int total, int visible) {
        if (total <= visible) {
            return;
        }
        int max = Math.max(1, total - visible);
        int thumbH = Math.max(8, h * visible / total);
        int thumbY = y + (h - thumbH) * clamp(offset, 0, max) / max;
        graphics.fill(x, y, x + 1, y + h, 0x664B5061);
        graphics.fill(x, thumbY, x + 1, thumbY + thumbH, 0xFF31D2B4);
    }

    private void drawVanillaTextCenteredScaled(GuiGraphics graphics, String value, float centerX, float y,
                                               int argb, float scale) {
        int width = this.font.width(value);
        graphics.pose().pushPose();
        graphics.pose().translate(centerX - width * scale / 2.0F, y, 0.0F);
        graphics.pose().scale(scale, scale, 1.0F);
        graphics.drawString(this.font, value, 0, 0, argb, false);
        graphics.pose().popPose();
    }

    private static boolean supportsSmoothText(String value) {
        String text = value.toUpperCase(Locale.ROOT);
        for (int i = 0; i < text.length(); i++) {
            if (SMOOTH_GLYPHS.indexOf(text.charAt(i)) < 0) {
                return false;
            }
        }
        return true;
    }

    private static int ammoProfileRow(MunitionsCaliber caliber) {
        return switch (caliber) {
            case RIFLE_556 -> 9;
            default -> Math.max(0, Math.min(8, caliber.index()));
        };
    }

    private static String formatTicks(int ticks) {
        if (ticks <= 0) {
            return "--";
        }
        int seconds = Math.max(1, (ticks + 19) / 20);
        int minutes = seconds / 60;
        int remainSeconds = seconds % 60;
        return minutes + ":" + (remainSeconds < 10 ? "0" : "") + remainSeconds;
    }

    private static String displayCaliberLabel(MunitionsCaliber caliber) {
        return caliber.shortLabel();
    }

    private static boolean matchesSearch(MunitionsCaliber caliber, String query) {
        String trimmed = query.trim();
        if (trimmed.isEmpty()) {
            return true;
        }
        String target = searchText(caliber).toLowerCase(Locale.ROOT);
        String direct = trimmed.toLowerCase(Locale.ROOT);
        String compact = compactSearchText(trimmed);
        return target.contains(direct) || compactSearchText(target).contains(compact);
    }

    private static String searchText(MunitionsCaliber caliber) {
        return displayCaliberLabel(caliber) + " "
                + caliber.defaultAmmoPath() + " "
                + caliber.category().name() + " "
                + caliber.category().label() + " "
                + caliberSearchName(caliber);
    }

    private static String compactSearchText(String value) {
        return value.toLowerCase(Locale.ROOT)
                .replace(" ", "")
                .replace("/", "")
                .replace("_", "")
                .replace("-", "")
                .replace(".", "");
    }

    private static String caliberSearchName(MunitionsCaliber caliber) {
        return switch (caliber) {
            case PISTOL -> "9mm 手枪 冲锋枪 手枪弹 冲锋枪弹 pistol smg";
            case RIFLE -> "762 762x39 7.62 步枪 步枪弹 rifle";
            case SHOTGUN -> "12g 12ga 霰弹 散弹 霰弹枪 shotgun";
            case BATTLE -> "762x54 7.62x54 54r 战斗步枪 机枪 机枪弹 battle mg machinegun";
            case SNIPER -> "338 .338 狙击 狙击弹 sniper";
            case BIG_PISTOL -> "50ae 50 ae 大口径手枪 magnum pistol";
            case ANTI_MATERIEL -> "50bmg 50 bmg 反器材 反器材弹 anti materiel antimateriel";
            case EXPLOSIVE -> "40mm 40 m 爆炸 榴弹 火箭弹 rpg explosive grenade";
            case SPECIAL -> "68x51 68x51fury 特种 特种弹 special fury";
            case RIFLE_556 -> "556 556x45 5.56 5.56x45 步枪 步枪弹 rifle";
        };
    }

    private static void drawSmoothTextCentered(GuiGraphics graphics, String value, int centerX, int y, int argb) {
        int width = smoothTextWidth(value);
        drawSmoothText(graphics, value, centerX - width / 2, y, argb);
    }

    private static void drawSmoothTextCenteredScaled(GuiGraphics graphics, String value, int centerX, int y,
                                                     int argb, float scale) {
        int width = smoothTextWidth(value);
        drawSmoothTextScaled(graphics, value, centerX - width * scale / 2.0F, y, argb, scale);
    }

    private static void drawSmoothTextScaled(GuiGraphics graphics, String value, float x, float y,
                                             int argb, float scale) {
        graphics.pose().pushPose();
        graphics.pose().translate(x, y, 0.0F);
        graphics.pose().scale(scale, scale, 1.0F);
        drawSmoothText(graphics, value, 0, 0, argb);
        graphics.pose().popPose();
    }

    private static void drawSmoothText(GuiGraphics graphics, String value, int x, int y, int argb) {
        float alpha = ((argb >>> 24) & 0xFF) / 255.0F;
        float red = ((argb >>> 16) & 0xFF) / 255.0F;
        float green = ((argb >>> 8) & 0xFF) / 255.0F;
        float blue = (argb & 0xFF) / 255.0F;
        graphics.setColor(red, green, blue, alpha);
        int cursor = x;
        String text = value.toUpperCase(Locale.ROOT);
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == ' ') {
                cursor += GLYPH_ADVANCE;
                continue;
            }
            int idx = SMOOTH_GLYPHS.indexOf(c);
            if (idx >= 0) {
                graphics.blit(UI_FONT, cursor, y, GLYPH_W, GLYPH_H,
                        idx * GLYPH_SRC_W, 0.0F, GLYPH_SRC_W, GLYPH_SRC_H, GLYPH_TEX_W, GLYPH_TEX_H);
                cursor += GLYPH_ADVANCE;
            }
        }
        graphics.setColor(1.0F, 1.0F, 1.0F, 1.0F);
    }

    private static int smoothTextWidth(String value) {
        int width = 0;
        String text = value.toUpperCase(Locale.ROOT);
        for (int i = 0; i < text.length(); i++) {
            if (SMOOTH_GLYPHS.indexOf(text.charAt(i)) >= 0) {
                width += GLYPH_ADVANCE;
            }
        }
        return Math.max(0, width - 1);
    }

    private static String trimSmoothText(String value, int maxWidth) {
        StringBuilder builder = new StringBuilder();
        String text = value.toUpperCase(Locale.ROOT);
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            builder.append(SMOOTH_GLYPHS.indexOf(c) >= 0 ? c : '.');
        }
        String result = builder.toString();
        if (smoothTextWidth(result) <= maxWidth) {
            return result;
        }
        int limit = Math.max(0, maxWidth - smoothTextWidth("."));
        while (smoothTextWidth(result) > limit && result.length() > 0) {
            result = result.substring(0, result.length() - 1);
        }
        return result + ".";
    }

    private static void drawLockIcon(GuiGraphics graphics, int x, int y) {
        graphics.fill(x + 2, y, x + 10, y + 2, 0xFFFFD8D0);
        graphics.fill(x + 1, y + 2, x + 3, y + 6, 0xFFFFD8D0);
        graphics.fill(x + 9, y + 2, x + 11, y + 6, 0xFFFFD8D0);
        graphics.fill(x, y + 6, x + 12, y + 13, 0xFFFFD8D0);
        graphics.fill(x + 5, y + 8, x + 7, y + 11, 0xCC73242B);
    }

    private static void drawGhostSilhouette(GuiGraphics graphics, int x, int y, int kind) {
        int body = 0x40D8C37A;
        int edge = 0x55806F3A;
        if (kind == 0) {
            graphics.fill(x + 5, y + 3, x + 11, y + 4, edge);
            graphics.fill(x + 3, y + 5, x + 13, y + 11, body);
            graphics.fill(x + 5, y + 12, x + 11, y + 13, edge);
            graphics.fill(x + 7, y + 7, x + 10, y + 10, 0x55F4E7B8);
            return;
        }
        if (kind == 1) {
            graphics.fill(x + 7, y + 2, x + 11, y + 5, body);
            graphics.fill(x + 5, y + 5, x + 13, y + 13, body);
            graphics.fill(x + 4, y + 13, x + 14, y + 15, edge);
            graphics.fill(x + 10, y + 5, x + 12, y + 13, 0x4CEED28C);
            return;
        }
        if (kind == 2) {
            graphics.fill(x + 7, y + 1, x + 10, y + 3, edge);
            graphics.fill(x + 6, y + 3, x + 11, y + 7, body);
            graphics.fill(x + 5, y + 7, x + 12, y + 14, body);
            graphics.fill(x + 6, y + 14, x + 11, y + 15, edge);
            return;
        }
        graphics.fill(x + 4, y + 9, x + 6, y + 11, body);
        graphics.fill(x + 7, y + 5, x + 9, y + 7, body);
        graphics.fill(x + 9, y + 10, x + 11, y + 12, body);
        graphics.fill(x + 12, y + 7, x + 14, y + 9, body);
        graphics.fill(x + 6, y + 13, x + 8, y + 15, body);
        graphics.fill(x + 12, y + 13, x + 14, y + 15, body);
    }

    private static boolean inRect(double mx, double my, int x, int y, int w, int h) {
        return mx >= x && mx < x + w && my >= y && my < y + h;
    }
}
