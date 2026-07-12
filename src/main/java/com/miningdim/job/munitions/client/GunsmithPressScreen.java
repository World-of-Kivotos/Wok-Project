package com.miningdim.job.munitions.client;

import com.miningdim.core.MiningConstants;
import com.miningdim.job.ClientJobState;
import com.miningdim.job.JobId;
import com.miningdim.job.JobXpCurve;
import com.miningdim.job.munitions.block.GunsmithPressBlockEntity;
import com.miningdim.job.munitions.gunsmith.GunsmithPartQuality;
import com.miningdim.job.munitions.gunsmith.GunsmithPlatform;
import com.miningdim.job.munitions.gunsmith.GunsmithPressPart;
import com.miningdim.job.munitions.menu.GunsmithPressMenu;
import com.miningdim.menu.AbstractMiningScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.PlayerFaceRenderer;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Inventory;

public final class GunsmithPressScreen extends AbstractMiningScreen<GunsmithPressMenu> {

    private static final ResourceLocation BG =
            new ResourceLocation(MiningConstants.MODID, "textures/gui/container/gunsmith_press.png");
    private static final int PART_ICON_TEX = 64;
    private static final int PART_ICON_SIZE = 22;

    private static final int W = 360;
    private static final int H = 240;
    private static final int TEXTURE_SCALE = 3;
    private static final int TEX_W = W * TEXTURE_SCALE;
    private static final int TEX_H = H * TEXTURE_SCALE;

    private static final int PLAYER_FACE_X = 29;
    private static final int PLAYER_FACE_Y = 39;
    private static final int PLAYER_FACE_SIZE = 18;
    private static final int PLAYER_LEVEL_X = 52;
    private static final int PLAYER_LEVEL_Y = 43;
    private static final int PLAYER_XP_BAR_X = 29;
    private static final int PLAYER_XP_BAR_Y = 65;
    private static final int PLAYER_XP_BAR_W = 46;
    private static final int PLAYER_XP_BAR_H = 2;
    private static final int PLAYER_XP_HOVER_PAD = 3;

    private static final int PLATFORM_X = 27;
    private static final int PLATFORM_Y = 74;
    private static final int PLATFORM_W = 52;
    private static final int PLATFORM_H = 14;
    private static final int PLATFORM_ARROW_W = 12;

    private static final int PART_X = 27;
    private static final int PART_Y = 92;
    private static final int PART_W = 52;
    private static final int PART_H = 14;
    private static final int PART_GAP = 4;

    private static final int QUALITY_X = 103;
    private static final int QUALITY_Y = 124;
    private static final int QUALITY_W = 32;
    private static final int QUALITY_H = 13;
    private static final int QUALITY_GAP = 4;

    private static final int START_X = 27;
    private static final int START_Y = 204;
    private static final int START_W = 53;
    private static final int START_H = 30;

    private static final int PREVIEW_X = 103;
    private static final int PREVIEW_Y = 66;
    private static final int PREVIEW_W = 168;
    private static final int PREVIEW_H = 48;
    private static final int OUTPUT_SLOT_X = 178;
    private static final int OUTPUT_SLOT_Y = 92;
    private static final int TIME_PANEL_X = 292;
    private static final int TIME_PANEL_Y = 184;
    private static final int TIME_PANEL_W = 54;
    private static final int TIME_PANEL_H = 36;

    public GunsmithPressScreen(GunsmithPressMenu menu, Inventory inv, Component title) {
        super(menu, inv, title, BG, W, H);
        this.titleLabelX = 0;
        this.titleLabelY = 0;
        this.inventoryLabelX = 0;
        this.inventoryLabelY = 0;
    }

    @Override
    protected void renderBg(GuiGraphics graphics, float partialTick, int mouseX, int mouseY) {
        int left = (this.width - W) / 2;
        int top = (this.height - H) / 2;
        graphics.blit(BG, left, top, W, H, 0.0F, 0.0F, TEX_W, TEX_H, TEX_W, TEX_H);
        renderDynamic(graphics, left, top, mouseX, mouseY);
    }

    @Override
    protected void renderLabels(GuiGraphics graphics, int mouseX, int mouseY) {
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        super.render(graphics, mouseX, mouseY, partialTick);
        renderHoverTooltip(graphics, mouseX, mouseY);
        renderMunitionsXpTooltip(graphics, mouseX, mouseY);
    }

    private void renderDynamic(GuiGraphics graphics, int left, int top, int mouseX, int mouseY) {
        GunsmithPlatform platform = menu.selectedPlatform();
        GunsmithPressPart part = menu.selectedPart();
        GunsmithPartQuality quality = menu.selectedQuality();
        int pulse = (int) ((System.currentTimeMillis() / 180L) % 6L);

        drawScaledText(graphics, "机械冲压机", left + 98, top + 20, 0xFFE9EDF7, 1.65F);
        drawScaledText(graphics, "枪匠零件冲压线", left + 99, top + 43, 0xFF8E98AA, 0.82F);
        drawScaledText(graphics, "材料 / 工时", left + 292, top + 62, 0xFFDDE4F1, 0.86F);

        renderPlayerInfo(graphics, left, top);
        renderPlatformButtons(graphics, left, top);
        renderPartButtons(graphics, left, top, platform);
        renderPartPreview(graphics, left, top, platform, part, quality, pulse,
                menu.isPressing(), menu.getSlot(GunsmithPressBlockEntity.SLOT_OUTPUT).hasItem());
        renderQualityButtons(graphics, left, top);
        renderCostSummary(graphics, left, top, part, quality);
        renderStartButton(graphics, left, top, mouseX, mouseY);
        renderSlotHints(graphics, left, top);
        renderTimePanel(graphics, left, top);
    }

    private void renderPlatformButtons(GuiGraphics graphics, int left, int top) {
        GunsmithPlatform selected = menu.selectedPlatform();
        int x = left + PLATFORM_X;
        int y = top + PLATFORM_Y;
        boolean hover = inRect(this.minecraftMouseX(), this.minecraftMouseY(), x, y, PLATFORM_W, PLATFORM_H);
        int edge = hover ? 0xFF62E6C8 : 0xFF333844;
        graphics.fill(x, y, x + PLATFORM_W, y + PLATFORM_H, edge);
        graphics.fill(x + 1, y + 1, x + PLATFORM_W - 1, y + PLATFORM_H - 1, 0xFF202631);
        graphics.fill(x + PLATFORM_ARROW_W, y + 1, x + PLATFORM_ARROW_W + 1, y + PLATFORM_H - 1, 0xFF343B49);
        graphics.fill(x + PLATFORM_W - PLATFORM_ARROW_W - 1, y + 1, x + PLATFORM_W - PLATFORM_ARROW_W, y + PLATFORM_H - 1,
                0xFF343B49);
        drawCenteredScaledText(graphics, "<", x + PLATFORM_ARROW_W / 2.0F, y + 3.0F,
                0xFFB8C0CE, 0.62F);
        drawCenteredScaledText(graphics, ">", x + PLATFORM_W - PLATFORM_ARROW_W / 2.0F, y + 3.0F,
                0xFFB8C0CE, 0.62F);
        drawFittedCenteredText(graphics, tr(selected.labelKey()),
                x + PLATFORM_W / 2.0F, y + 3.0F, PLATFORM_W - PLATFORM_ARROW_W * 2 - 2,
                0xFFBFFBEF, 0.68F, 0.48F);
    }

    private void renderPartButtons(GuiGraphics graphics, int left, int top, GunsmithPlatform platform) {
        GunsmithPressPart selected = menu.selectedPart();
        for (GunsmithPressPart part : GunsmithPressPart.values()) {
            int x = left + PART_X;
            int y = top + PART_Y + part.index() * (PART_H + PART_GAP);
            boolean current = part == selected;
            boolean hover = inRect(this.minecraftMouseX(), this.minecraftMouseY(), x, y, PART_W, PART_H);
            int outer = current ? 0xFFB98C4D : hover ? 0xFF586172 : 0xFF333844;
            int inner = current ? 0xFF3A3128 : 0xFF232631;
            graphics.fill(x, y, x + PART_W, y + PART_H, outer);
            graphics.fill(x + 1, y + 1, x + PART_W - 1, y + PART_H - 1, inner);
            if (current) {
                graphics.fill(x + 2, y + PART_H - 2, x + PART_W - 2, y + PART_H - 1, 0xFFD9A854);
            }
            drawCenteredScaledText(graphics, platformPartName(platform, part), x + PART_W / 2.0F, y + 3.0F,
                    current ? 0xFFF3D7A2 : 0xFFD1D8E4, 0.72F);
        }
    }

    private void renderPartPreview(GuiGraphics graphics, int left, int top,
                                   GunsmithPlatform platform, GunsmithPressPart part,
                                   GunsmithPartQuality quality, int pulse,
                                   boolean active, boolean hasOutput) {
        int x = left + PREVIEW_X;
        int y = top + PREVIEW_Y;
        int slotX = left + OUTPUT_SLOT_X;
        int slotY = top + OUTPUT_SLOT_Y;
        int ramDrop = active ? 3 + pulse / 2 : 0;

        graphics.fill(x + 6, y + 39, x + PREVIEW_W - 6, y + 43, 0xDD0B0E15);
        graphics.fill(x + 14, y + 2, x + PREVIEW_W - 14, y + 5, 0xFF697384);
        graphics.fill(x + 17, y + 5, x + PREVIEW_W - 17, y + 7, active ? 0xFF4EF4D9 : 0xFF2E8D83);
        graphics.fill(x + 22, y + 6, x + 28, y + 35, 0xFF343B47);
        graphics.fill(x + PREVIEW_W - 28, y + 6, x + PREVIEW_W - 22, y + 35, 0xFF343B47);
        graphics.fill(x + 20, y + 34, x + PREVIEW_W - 20, y + 37, 0xFF222733);

        graphics.fill(x + 61, y + 8, x + 85, y + 13, 0xFF767F8C);
        graphics.fill(x + 67, y + 13, x + 79, y + 20 + ramDrop, 0xFF4F5865);
        graphics.fill(x + 53, y + 20 + ramDrop, x + 93, y + 27 + ramDrop, 0xFF8A94A1);
        graphics.fill(x + 59, y + 27 + ramDrop, x + 87, y + 31 + ramDrop, 0xFF3B424D);
        graphics.fill(x + 55, y + 21 + ramDrop, x + 91, y + 22 + ramDrop, 0xFFB4BCC8);

        graphics.fill(slotX - 10, slotY + 18, slotX + 28, slotY + 22, 0xFF141821);
        graphics.fill(slotX - 6, slotY + 14, slotX + 24, slotY + 18, 0xFF2B303B);
        graphics.fill(slotX - 8, slotY + 14, slotX - 4, slotY + 18, 0xFFB9853A);
        graphics.fill(slotX + 20, slotY + 14, slotX + 24, slotY + 18, 0xFFB9853A);
        graphics.fill(slotX - 1, slotY - 1, slotX + 19, slotY + 19, 0xFF485062);
        graphics.fill(slotX + 1, slotY + 1, slotX + 17, slotY + 17, 0xFF151923);
        graphics.fill(slotX + 3, slotY + 3, slotX + 15, slotY + 4, 0xFF313947);

        if (!active && !hasOutput) {
            drawPartIcon(graphics, slotX - 2, slotY - 3, platform, part, quality);
        }
        if (active) {
            int sparkX = slotX + 9;
            int sparkY = slotY + 18;
            for (int i = 0; i < 6; i++) {
                int sx = sparkX - 18 + ((pulse * 7 + i * 11) % 36);
                int sy = sparkY - 4 + ((pulse + i * 3) % 9);
                int color = (i & 1) == 0 ? 0xFFFFC45C : 0xFFFF7447;
                graphics.fill(sx, sy, sx + 2, sy + 1, color);
                graphics.fill(sx + 1, sy + 1, sx + 2, sy + 3, 0xAAFFD87A);
            }
        }
    }

    private void renderQualityButtons(GuiGraphics graphics, int left, int top) {
        GunsmithPartQuality selected = menu.selectedQuality();
        for (GunsmithPartQuality quality : GunsmithPartQuality.values()) {
            int x = left + QUALITY_X + quality.index() * (QUALITY_W + QUALITY_GAP);
            int y = top + QUALITY_Y;
            boolean current = quality == selected;
            int qualityColor = qualityOutlineColor(quality);
            int fill = current ? 0xFF252A34 : 0xFF222631;
            int edge = current ? qualityColor : withAlpha(qualityColor, 0x80);
            graphics.fill(x, y, x + QUALITY_W, y + QUALITY_H, edge);
            graphics.fill(x + 1, y + 1, x + QUALITY_W - 1, y + QUALITY_H - 1, fill);
            if (current) {
                graphics.fill(x + 2, y + QUALITY_H - 2, x + QUALITY_W - 2, y + QUALITY_H - 1, qualityColor);
            }
            drawCenteredScaledText(graphics, tr(quality.labelKey()), x + QUALITY_W / 2.0F, y + 3.0F,
                    current ? qualityColor : 0xFFB8C0CE, 0.64F);
        }
    }

    private void renderCostSummary(GuiGraphics graphics, int left, int top,
                                   GunsmithPressPart part, GunsmithPartQuality quality) {
        int mult = quality.materialMultiplier();
        int parts = part.partsCost() * mult;
        int alloy = part.alloyCost() * mult;
        int polymer = part.polymerCost() * mult;
        drawScaledText(graphics, "零件 x" + parts, left + 103, top + 142, 0xFFC5CDD9, 0.68F);
        drawScaledText(graphics, "合金 x" + alloy, left + 154, top + 142, 0xFFC5CDD9, 0.68F);
        drawScaledText(graphics, "板材 x" + polymer, left + 203, top + 142, 0xFFC5CDD9, 0.68F);
        drawScaledText(graphics, formatTicks(quality.requiredTicks()), left + 251, top + 142,
                0xFF83EAD2, 0.68F);
    }

    private void renderStartButton(GuiGraphics graphics, int left, int top, int mouseX, int mouseY) {
        int x = left + START_X;
        int y = top + START_Y;
        boolean hover = inRect(mouseX, mouseY, x, y, START_W, START_H);
        int outer = hover ? 0xFF35E2C2 : 0xFF2EC7AA;
        int inner = hover ? 0xFF185E55 : 0xFF144C46;
        graphics.fill(x, y, x + START_W, y + START_H, outer);
        graphics.fill(x + 1, y + 1, x + START_W - 1, y + START_H - 1, inner);
        graphics.fill(x + 3, y + 3, x + START_W - 3, y + 4, hover ? 0xFF83F7DE : 0xFF55DCC2);
        graphics.fill(x + 3, y + START_H - 4, x + START_W - 3, y + START_H - 3, 0xFFFFC866);
        drawCenteredScaledText(graphics, "开始冲压", x + START_W / 2.0F, y + 11.0F, 0xFFEAFBF7, 0.68F);
    }

    private void renderSlotHints(GuiGraphics graphics, int left, int top) {
        drawCenteredScaledText(graphics, "零件", left + 303.0F, top + 79.0F, 0xFF7F8795, 0.55F);
        drawCenteredScaledText(graphics, "合金", left + 329.0F, top + 79.0F, 0xFF7F8795, 0.55F);
        drawCenteredScaledText(graphics, "板材", left + 303.0F, top + 105.0F, 0xFF7F8795, 0.55F);
    }

    private void renderTimePanel(GuiGraphics graphics, int left, int top) {
        int x = left + TIME_PANEL_X;
        int y = top + TIME_PANEL_Y;
        int required = Math.max(0, menu.productionRequiredTicks());
        int progress = Math.max(0, Math.min(required, menu.productionProgressTicks()));
        int shownTicks = menu.isPressing() ? Math.max(0, required - progress) : required;
        int barW = TIME_PANEL_W - 10;
        int fill = required <= 0 ? 0 : (int) ((long) progress * barW / required);

        graphics.fill(x + 2, y + 3, x + TIME_PANEL_W + 2, y + TIME_PANEL_H + 3, 0xAA000000);
        graphics.fill(x, y, x + TIME_PANEL_W, y + TIME_PANEL_H, 0xFF111620);
        graphics.fill(x + 1, y + 1, x + TIME_PANEL_W - 1, y + TIME_PANEL_H - 1, 0xFF232938);
        graphics.fill(x + 4, y + 4, x + TIME_PANEL_W - 4, y + 5, 0xFF556071);
        graphics.fill(x + 5, y + TIME_PANEL_H - 8, x + TIME_PANEL_W - 5, y + TIME_PANEL_H - 5, 0xFF11141B);
        graphics.fill(x + 5, y + TIME_PANEL_H - 8, x + 5 + fill, y + TIME_PANEL_H - 5,
                menu.isPressing() ? 0xFF35D2A4 : 0xFF3D4658);
        graphics.fill(x + 5, y + TIME_PANEL_H - 8, x + 5 + fill, y + TIME_PANEL_H - 7,
                menu.isPressing() ? 0xFF78F4D1 : 0xFF657086);

        drawCenteredScaledText(graphics, menu.isPressing() ? "剩余时间" : "制作时间",
                x + TIME_PANEL_W / 2.0F, y + 8.0F, 0xFFAEB8C8, 0.55F);
        drawCenteredScaledText(graphics, formatTicks(shownTicks),
                x + TIME_PANEL_W / 2.0F, y + 19.0F, menu.isPressing() ? 0xFF83EAD2 : 0xFFE6ECF5, 0.82F);
    }

    private void renderPlayerInfo(GuiGraphics graphics, int left, int top) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) {
            return;
        }
        PlayerFaceRenderer.draw(graphics, mc.player.getSkinTextureLocation(),
                left + PLAYER_FACE_X, top + PLAYER_FACE_Y, PLAYER_FACE_SIZE);
        drawScaledText(graphics, "LV", left + PLAYER_LEVEL_X, top + PLAYER_LEVEL_Y, 0xFF8E98AA, 0.62F);
        drawScaledText(graphics, String.valueOf(playerLevel()),
                left + PLAYER_LEVEL_X + 13, top + PLAYER_LEVEL_Y - 1, 0xFFE9EDF7, 0.82F);
        int fill = playerXpProgressPixels();
        graphics.fill(left + PLAYER_XP_BAR_X, top + PLAYER_XP_BAR_Y,
                left + PLAYER_XP_BAR_X + PLAYER_XP_BAR_W, top + PLAYER_XP_BAR_Y + PLAYER_XP_BAR_H,
                0xFF1A2029);
        graphics.fill(left + PLAYER_XP_BAR_X, top + PLAYER_XP_BAR_Y,
                left + PLAYER_XP_BAR_X + fill, top + PLAYER_XP_BAR_Y + PLAYER_XP_BAR_H,
                0xFF35D2A4);
        graphics.fill(left + PLAYER_XP_BAR_X, top + PLAYER_XP_BAR_Y,
                left + PLAYER_XP_BAR_X + fill, top + PLAYER_XP_BAR_Y + 1,
                0xFF75F0CA);
    }

    private void renderMunitionsXpTooltip(GuiGraphics graphics, int mouseX, int mouseY) {
        int left = (this.width - W) / 2;
        int top = (this.height - H) / 2;
        if (!inRect(mouseX, mouseY, left + PLAYER_XP_BAR_X, top + PLAYER_XP_BAR_Y - PLAYER_XP_HOVER_PAD,
                PLAYER_XP_BAR_W, PLAYER_XP_BAR_H + PLAYER_XP_HOVER_PAD * 2)) {
            return;
        }
        graphics.renderTooltip(this.font,
                Component.literal("经验: " + playerShownXp() + "/" + playerNextLevelXp()), mouseX, mouseY);
    }

    private void drawPartIcon(GuiGraphics graphics, int x, int y, GunsmithPlatform platform,
                              GunsmithPressPart part, GunsmithPartQuality quality) {
        ResourceLocation texture = new ResourceLocation(MiningConstants.MODID,
                "textures/item/gunsmith_part_" + platform.id() + "_" + part.id() + "_" + quality.id() + ".png");
        graphics.blit(texture, x, y, PART_ICON_SIZE, PART_ICON_SIZE,
                0.0F, 0.0F, PART_ICON_TEX, PART_ICON_TEX, PART_ICON_TEX, PART_ICON_TEX);
    }

    private static int qualityOutlineColor(GunsmithPartQuality quality) {
        return switch (quality) {
            case COMMON -> 0xFFE9EEF7;
            case IMPROVED -> 0xFF47E37C;
            case MILSPEC -> 0xFF56A8FF;
            case PRECISION -> 0xFFC56CFF;
            case LEGENDARY -> 0xFFFF4F5E;
        };
    }

    private static int withAlpha(int argb, int alpha) {
        return (Math.max(0, Math.min(255, alpha)) << 24) | (argb & 0x00FFFFFF);
    }

    private void renderHoverTooltip(GuiGraphics graphics, int mouseX, int mouseY) {
        int left = (this.width - W) / 2;
        int top = (this.height - H) / 2;
        int platformX = left + PLATFORM_X;
        int platformY = top + PLATFORM_Y;
        if (inRect(mouseX, mouseY, platformX, platformY, PLATFORM_W, PLATFORM_H)) {
            graphics.renderTooltip(this.font,
                    Component.literal(tr(menu.selectedPlatform().labelKey()) + " 平台"), mouseX, mouseY);
            return;
        }
        for (GunsmithPressPart part : GunsmithPressPart.values()) {
            int x = left + PART_X;
            int y = top + PART_Y + part.index() * (PART_H + PART_GAP);
            if (inRect(mouseX, mouseY, x, y, PART_W, PART_H)) {
                graphics.renderTooltip(this.font,
                        Component.literal(partQualityName(menu.selectedPlatform(), part, menu.selectedQuality())
                                + " - " + tr(part.roleKey())), mouseX, mouseY);
                return;
            }
        }
        for (GunsmithPartQuality quality : GunsmithPartQuality.values()) {
            int x = left + QUALITY_X + quality.index() * (QUALITY_W + QUALITY_GAP);
            int y = top + QUALITY_Y;
            if (inRect(mouseX, mouseY, x, y, QUALITY_W, QUALITY_H)) {
                graphics.renderTooltip(this.font,
                        Component.literal(partQualityName(menu.selectedPlatform(), menu.selectedPart(), quality)), mouseX, mouseY);
                return;
            }
        }
        if (inRect(mouseX, mouseY, left + START_X, top + START_Y, START_W, START_H)) {
            graphics.renderTooltip(this.font,
                    Component.translatable("tooltip.miningdim.gunsmith_press.start"), mouseX, mouseY);
        }
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button == 0) {
            int left = (this.width - W) / 2;
            int top = (this.height - H) / 2;
            int platformX = left + PLATFORM_X;
            int platformY = top + PLATFORM_Y;
            if (inRect(mouseX, mouseY, platformX, platformY, PLATFORM_W, PLATFORM_H)) {
                int delta = mouseX < platformX + PLATFORM_ARROW_W ? -1 : 1;
                sendButton(GunsmithPressMenu.BUTTON_PLATFORM_BASE + shiftedPlatformIndex(delta));
                return true;
            }
            for (GunsmithPressPart part : GunsmithPressPart.values()) {
                int x = left + PART_X;
                int y = top + PART_Y + part.index() * (PART_H + PART_GAP);
                if (inRect(mouseX, mouseY, x, y, PART_W, PART_H)) {
                    sendButton(part.index());
                    return true;
                }
            }
            for (GunsmithPartQuality quality : GunsmithPartQuality.values()) {
                int x = left + QUALITY_X + quality.index() * (QUALITY_W + QUALITY_GAP);
                int y = top + QUALITY_Y;
                if (inRect(mouseX, mouseY, x, y, QUALITY_W, QUALITY_H)) {
                    sendButton(GunsmithPressMenu.BUTTON_QUALITY_BASE + quality.index());
                    return true;
                }
            }
            if (inRect(mouseX, mouseY, left + START_X, top + START_Y, START_W, START_H)) {
                sendButton(GunsmithPressMenu.BUTTON_START_PREVIEW);
                return true;
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    private void sendButton(int id) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.gameMode != null) {
            mc.gameMode.handleInventoryButtonClick(menu.containerId, id);
        }
    }

    private double minecraftMouseX() {
        Minecraft mc = Minecraft.getInstance();
        return mc.mouseHandler.xpos() * mc.getWindow().getGuiScaledWidth() / mc.getWindow().getScreenWidth();
    }

    private double minecraftMouseY() {
        Minecraft mc = Minecraft.getInstance();
        return mc.mouseHandler.ypos() * mc.getWindow().getGuiScaledHeight() / mc.getWindow().getScreenHeight();
    }

    private String tr(String key) {
        return Component.translatable(key).getString();
    }

    private String platformPartName(GunsmithPlatform platform, GunsmithPressPart part) {
        return tr(platform.labelKey()) + tr(part.labelKey());
    }

    private String partQualityName(GunsmithPlatform platform, GunsmithPressPart part, GunsmithPartQuality quality) {
        return platformPartName(platform, part) + " " + tr(quality.labelKey());
    }

    private int shiftedPlatformIndex(int delta) {
        int count = GunsmithPlatform.values().length;
        if (count <= 1) {
            return menu.selectedPlatformIndex();
        }
        return Math.floorMod(menu.selectedPlatformIndex() + delta, count);
    }

    private void drawScaledText(GuiGraphics graphics, String value, float x, float y, int argb, float scale) {
        graphics.pose().pushPose();
        graphics.pose().translate(x, y, 0.0F);
        graphics.pose().scale(scale, scale, 1.0F);
        graphics.drawString(this.font, value, 0, 0, argb, false);
        graphics.pose().popPose();
    }

    private void drawCenteredScaledText(GuiGraphics graphics, String value, float centerX, float y,
                                        int argb, float scale) {
        int width = this.font.width(value);
        drawScaledText(graphics, value, centerX - width * scale / 2.0F, y, argb, scale);
    }

    private void drawFittedCenteredText(GuiGraphics graphics, String value, float centerX, float y,
                                        int maxWidth, int argb, float preferredScale, float minScale) {
        int width = Math.max(1, this.font.width(value));
        float scale = Math.min(preferredScale, Math.max(minScale, maxWidth / (float) width));
        drawCenteredScaledText(graphics, value, centerX, y, argb, scale);
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

    private static String formatTicks(int ticks) {
        int seconds = Math.max(1, (ticks + 19) / 20);
        int minutes = seconds / 60;
        int remainSeconds = seconds % 60;
        return minutes + ":" + (remainSeconds < 10 ? "0" : "") + remainSeconds;
    }

    private static boolean inRect(double mx, double my, int x, int y, int w, int h) {
        return mx >= x && mx < x + w && my >= y && my < y + h;
    }
}
