package com.miningdim.job.munitions.client;

import com.miningdim.core.MiningConstants;
import com.miningdim.job.ClientJobState;
import com.miningdim.job.JobId;
import com.miningdim.job.JobXpCurve;
import com.miningdim.job.munitions.block.GunsmithPressBlockEntity;
import com.miningdim.job.munitions.gunsmith.GunsmithPartQuality;
import com.miningdim.job.munitions.gunsmith.GunsmithPartVariant;
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

import java.util.List;
import java.util.Locale;

public final class GunsmithPressScreen extends AbstractMiningScreen<GunsmithPressMenu> {

    private static final ResourceLocation BG =
            new ResourceLocation(MiningConstants.MODID, "textures/gui/container/gunsmith_press.png");
    private static final int PART_ICON_TEX = 64;
    private static final int DETAIL_ICON_SIZE = 40;

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

    private static final int VARIANT_POPUP_OFFSET_X = 4;
    private static final int VARIANT_POPUP_W = 136;
    private static final int VARIANT_POPUP_PADDING = 3;
    private static final int VARIANT_POPUP_ITEM_H = 18;
    private static final int VARIANT_POPUP_GAP = 1;
    private static final float VARIANT_POPUP_Z = 300.0F;

    private static final int QUALITY_X = 103;
    private static final int QUALITY_Y = 124;
    private static final int QUALITY_W = 32;
    private static final int QUALITY_H = 13;
    private static final int QUALITY_GAP = 4;

    private static final int DETAIL_X = 94;
    private static final int DETAIL_Y = 58;
    private static final int DETAIL_W = 188;
    private static final int DETAIL_H = 63;

    private static final int START_X = 292;
    private static final int START_Y = 146;
    private static final int START_W = 54;
    private static final int START_H = 24;

    private static final int MATERIAL_PANEL_X = 287;
    private static final int MATERIAL_PANEL_Y = 58;
    private static final int MATERIAL_PANEL_W = 63;
    private static final int MATERIAL_PANEL_H = 84;
    private static final int SLOT_GUN_PARTS_X = 294;
    private static final int SLOT_ALLOY_X = 320;
    private static final int SLOT_POLYMER_X = 294;
    private static final int SLOT_OUTPUT_X = 320;
    private static final int SLOT_TOP_Y = 84;
    private static final int SLOT_BOTTOM_Y = 110;
    private static final int TIME_PANEL_X = 292;
    private static final int TIME_PANEL_Y = 184;
    private static final int TIME_PANEL_W = 54;
    private static final int TIME_PANEL_H = 36;

    private GunsmithPlatform variantMenuPlatform;
    private GunsmithPressPart variantMenuPart;
    private GunsmithPlatform pendingPlatform;

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
        refreshPendingPlatform();
        super.render(graphics, mouseX, mouseY, partialTick);
        if (menu.isPressing()
                || (variantMenuPlatform != null && variantMenuPlatform != menu.selectedPlatform())) {
            closeVariantMenu();
        }
        renderVariantPopup(graphics, (this.width - W) / 2, (this.height - H) / 2, mouseX, mouseY);
        renderHoverTooltip(graphics, mouseX, mouseY);
        renderMunitionsXpTooltip(graphics, mouseX, mouseY);
    }

    private void renderDynamic(GuiGraphics graphics, int left, int top, int mouseX, int mouseY) {
        GunsmithPlatform platform = menu.selectedPlatform();
        GunsmithPressPart part = menu.selectedPart();
        GunsmithPartVariant variant = menu.selectedVariant();
        GunsmithPartQuality quality = menu.selectedQuality();

        drawScaledText(graphics, tr("block.miningdim.gunsmith_press"),
                left + 98, top + 20, 0xFFE9EDF7, 1.65F);
        drawScaledText(graphics, tr("screen.miningdim.gunsmith_press.subtitle"),
                left + 99, top + 43, 0xFF8E98AA, 0.82F);

        renderPlayerInfo(graphics, left, top);
        renderPlatformButtons(graphics, left, top, mouseX, mouseY);
        renderPartButtons(graphics, left, top, platform, mouseX, mouseY);
        renderCurrentComponentSummary(graphics, left, top, part, variant);
        renderComponentDetail(graphics, left, top, platform, part, variant, quality);
        renderQualityButtons(graphics, left, top, mouseX, mouseY);
        renderMaterialPanel(graphics, left, top);
        renderStartButton(graphics, left, top, mouseX, mouseY);
        renderTimePanel(graphics, left, top);
    }

    private void renderPlatformButtons(GuiGraphics graphics, int left, int top, int mouseX, int mouseY) {
        GunsmithPlatform selected = menu.selectedPlatform();
        int x = left + PLATFORM_X;
        int y = top + PLATFORM_Y;
        boolean locked = selectionLocked();
        boolean hover = !locked && inRect(mouseX, mouseY, x, y, PLATFORM_W, PLATFORM_H);
        int edge = locked ? 0xFF292E38 : hover ? 0xFF62E6C8 : 0xFF333844;
        graphics.fill(x, y, x + PLATFORM_W, y + PLATFORM_H, edge);
        graphics.fill(x + 1, y + 1, x + PLATFORM_W - 1, y + PLATFORM_H - 1,
                locked ? 0xFF191D25 : 0xFF202631);
        graphics.fill(x + PLATFORM_ARROW_W, y + 1, x + PLATFORM_ARROW_W + 1, y + PLATFORM_H - 1, 0xFF343B49);
        graphics.fill(x + PLATFORM_W - PLATFORM_ARROW_W - 1, y + 1, x + PLATFORM_W - PLATFORM_ARROW_W, y + PLATFORM_H - 1,
                0xFF343B49);
        drawCenteredScaledText(graphics, "<", x + PLATFORM_ARROW_W / 2.0F, y + 3.0F,
                locked ? 0xFF5E6571 : 0xFFB8C0CE, 0.62F);
        drawCenteredScaledText(graphics, ">", x + PLATFORM_W - PLATFORM_ARROW_W / 2.0F, y + 3.0F,
                locked ? 0xFF5E6571 : 0xFFB8C0CE, 0.62F);
        drawFittedCenteredText(graphics, tr(selected.labelKey()),
                x + PLATFORM_W / 2.0F, y + 3.0F, PLATFORM_W - PLATFORM_ARROW_W * 2 - 2,
                locked ? 0xFF737B87 : 0xFFBFFBEF, 0.68F, 0.48F);
    }

    private void renderPartButtons(GuiGraphics graphics, int left, int top, GunsmithPlatform platform,
                                   int mouseX, int mouseY) {
        GunsmithPressPart selected = menu.selectedPart();
        boolean locked = selectionLocked();
        int row = 0;
        for (GunsmithPressPart part : platform.supportedParts()) {
            int x = left + PART_X;
            int y = top + PART_Y + row * (PART_H + PART_GAP);
            boolean current = part == selected;
            boolean hasVariants = GunsmithPartVariant.availableFor(platform, part).size() > 1;
            boolean popupOwner = isVariantMenuOpenFor(platform, part);
            boolean hover = !locked && inRect(mouseX, mouseY, x, y, PART_W, PART_H);
            int outer = locked ? 0xFF292E38 : popupOwner ? 0xFF35D2A4
                    : current ? 0xFFB98C4D : hover ? 0xFF586172 : 0xFF333844;
            int inner = locked ? 0xFF191D25 : popupOwner ? 0xFF163C38
                    : current ? 0xFF3A3128 : 0xFF232631;
            graphics.fill(x, y, x + PART_W, y + PART_H, outer);
            graphics.fill(x + 1, y + 1, x + PART_W - 1, y + PART_H - 1, inner);
            if (current && !locked && !popupOwner) {
                graphics.fill(x + 2, y + PART_H - 2, x + PART_W - 2, y + PART_H - 1, 0xFFD9A854);
            }
            float labelCenter = x + PART_W / 2.0F - (hasVariants ? 3.0F : 0.0F);
            drawFittedCenteredText(graphics, tr(part.slotKey()), labelCenter, y + 3.0F,
                    PART_W - (hasVariants ? 13 : 4), locked ? 0xFF737B87
                            : popupOwner ? 0xFFC8FFF3 : current ? 0xFFF3D7A2 : 0xFFD1D8E4,
                    0.72F, 0.48F);
            if (hasVariants) {
                drawCenteredScaledText(graphics, popupOwner ? "<" : ">", x + PART_W - 6.0F, y + 3.0F,
                        locked ? 0xFF5E6571 : popupOwner ? 0xFF8FFFF0 : 0xFF62E6C8, 0.62F);
            }
            row++;
        }
    }

    private void renderCurrentComponentSummary(GuiGraphics graphics, int left, int top,
                                               GunsmithPressPart part, GunsmithPartVariant variant) {
        int x = left + 25;
        int y = top + 198;
        graphics.fill(x, y, x + 57, y + 32, 0xFF333844);
        graphics.fill(x + 2, y + 2, x + 55, y + 30, 0xFF20232E);
        drawCenteredScaledText(graphics, tr("screen.miningdim.gunsmith_press.current_component"),
                x + 28.5F, y + 5.0F, 0xFF8E98AA, 0.52F);
        drawFittedCenteredText(graphics, variantMenuLabel(part, variant),
                x + 28.5F, y + 17.0F, 49, 0xFFC8FFF3, 0.62F, 0.38F);
    }

    private void renderVariantPopup(GuiGraphics graphics, int left, int top, int mouseX, int mouseY) {
        if (!isVariantMenuOpen() || menu.isPressing()) {
            return;
        }
        List<GunsmithPartVariant> variants = openVariants();
        if (variants.size() <= 1) {
            closeVariantMenu();
            return;
        }
        int row = partRow(variantMenuPlatform, variantMenuPart);
        if (row < 0) {
            closeVariantMenu();
            return;
        }
        int x = variantPopupX(left);
        int y = variantPopupY(top);
        int height = variantPopupHeight(variants.size());
        boolean authoritative = variantMenuOwnerIsAuthoritative();
        graphics.pose().pushPose();
        graphics.pose().translate(0.0F, 0.0F, VARIANT_POPUP_Z);
        graphics.fill(x + 3, y + 4, x + VARIANT_POPUP_W + 3, y + height + 4, 0x99000000);
        graphics.fill(x, y, x + VARIANT_POPUP_W, y + height, 0xFF687781);
        graphics.fill(x + 1, y + 1, x + VARIANT_POPUP_W - 1, y + height - 1, 0xFF0B1016);

        for (int index = 0; index < variants.size(); index++) {
            GunsmithPartVariant variant = variants.get(index);
            int itemX = x + VARIANT_POPUP_PADDING;
            int itemY = y + VARIANT_POPUP_PADDING
                    + index * (VARIANT_POPUP_ITEM_H + VARIANT_POPUP_GAP);
            boolean current = authoritative && variant == menu.selectedVariant();
            boolean hover = inRect(mouseX, mouseY, itemX, itemY,
                    VARIANT_POPUP_W - VARIANT_POPUP_PADDING * 2, VARIANT_POPUP_ITEM_H);
            int outer = !authoritative ? 0xFF292E38
                    : current ? 0xFF35D2A4 : hover ? 0xFFB98C4D : 0xFF35414D;
            int inner = !authoritative ? 0xFF191D25
                    : current ? 0xFF163C38 : hover ? 0xFF3A3128 : 0xFF171E26;
            graphics.fill(itemX, itemY, x + VARIANT_POPUP_W - VARIANT_POPUP_PADDING,
                    itemY + VARIANT_POPUP_ITEM_H, outer);
            graphics.fill(itemX + 1, itemY + 1, x + VARIANT_POPUP_W - VARIANT_POPUP_PADDING - 1,
                    itemY + VARIANT_POPUP_ITEM_H - 1, inner);
            if (current) {
                graphics.fill(itemX + 2, itemY + 2, itemX + 4,
                        itemY + VARIANT_POPUP_ITEM_H - 2, 0xFF83EAD2);
            }
            drawFittedText(graphics, variantMenuLabel(variantMenuPart, variant), itemX + 7, itemY + 5,
                    VARIANT_POPUP_W - VARIANT_POPUP_PADDING * 2 - 12,
                    !authoritative ? 0xFF737B87
                            : current ? 0xFFC8FFF3 : hover ? 0xFFF3D7A2 : 0xFFD1D8E4,
                    0.68F, 0.46F);
        }
        graphics.pose().popPose();
    }

    private void renderComponentDetail(GuiGraphics graphics, int left, int top,
                                       GunsmithPlatform platform, GunsmithPressPart part,
                                       GunsmithPartVariant variant, GunsmithPartQuality quality) {
        int x = left + DETAIL_X;
        int y = top + DETAIL_Y;
        int qualityColor = qualityOutlineColor(quality);
        graphics.fill(x + 2, y + 3, x + DETAIL_W + 2, y + DETAIL_H + 3, 0x77000000);
        graphics.fill(x, y, x + DETAIL_W, y + DETAIL_H, 0xFF42505D);
        graphics.fill(x + 1, y + 1, x + DETAIL_W - 1, y + DETAIL_H - 1, 0xFF111820);
        graphics.fill(x + 1, y + 1, x + 4, y + DETAIL_H - 1, qualityColor);
        graphics.fill(x + 7, y + 7, x + 53, y + 53, 0xFF26313B);
        graphics.fill(x + 9, y + 9, x + 51, y + 51, 0xFF080D12);
        drawPartIcon(graphics, x + 10, y + 10, DETAIL_ICON_SIZE, platform, part, variant, quality);

        String name = variantDisplayName(platform, part, variant);
        drawFittedText(graphics, name, x + 59, y + 6, DETAIL_W - 65,
                qualityColor, 0.82F, 0.48F);
        drawFittedText(graphics,
                Component.translatable("screen.miningdim.gunsmith_press.compatibility",
                        Component.translatable(platform.labelKey()), Component.translatable(part.slotKey())).getString(),
                x + 59, y + 20, DETAIL_W - 65, 0xFF8E9CAA, 0.58F, 0.42F);

        double fireRateMin = (variant.fireRateMultiplier(quality.minCoefficient()) - 1.0D) * 100.0D;
        double fireRateMax = (variant.fireRateMultiplier(quality.maxCoefficient()) - 1.0D) * 100.0D;
        double verticalRecoilMin = (variant.verticalRecoilMultiplier(quality.minCoefficient()) - 1.0D) * 100.0D;
        double verticalRecoilMax = (variant.verticalRecoilMultiplier(quality.maxCoefficient()) - 1.0D) * 100.0D;
        drawFittedText(graphics,
                Component.translatable("screen.miningdim.gunsmith_press.effect.semi_auto_fire_rate",
                        formatSignedPercentRange(fireRateMin, fireRateMax)).getString(),
                x + 59, y + 34, DETAIL_W - 65, fireRateMax > 0.0D ? 0xFF62E6C8 : 0xFFAEB8C8,
                0.62F, 0.46F);
        drawFittedText(graphics,
                Component.translatable("screen.miningdim.gunsmith_press.effect.vertical_recoil",
                        formatSignedPercentRange(verticalRecoilMin, verticalRecoilMax)).getString(),
                x + 59, y + 47, DETAIL_W - 65, verticalRecoilMax > 0.0D ? 0xFFFF6B68 : 0xFFAEB8C8,
                0.62F, 0.46F);
    }

    private void renderQualityButtons(GuiGraphics graphics, int left, int top, int mouseX, int mouseY) {
        GunsmithPartQuality selected = menu.selectedQuality();
        boolean locked = selectionLocked();
        for (GunsmithPartQuality quality : GunsmithPartQuality.values()) {
            int x = left + QUALITY_X + quality.index() * (QUALITY_W + QUALITY_GAP);
            int y = top + QUALITY_Y;
            boolean current = quality == selected;
            boolean hover = !locked && inRect(mouseX, mouseY, x, y, QUALITY_W, QUALITY_H);
            int qualityColor = qualityOutlineColor(quality);
            int fill = locked ? 0xFF191D25 : current ? 0xFF252A34 : hover ? 0xFF2A303C : 0xFF222631;
            int edge = locked ? 0xFF292E38 : current ? qualityColor : withAlpha(qualityColor, 0x80);
            graphics.fill(x, y, x + QUALITY_W, y + QUALITY_H, edge);
            graphics.fill(x + 1, y + 1, x + QUALITY_W - 1, y + QUALITY_H - 1, fill);
            if (current && !locked) {
                graphics.fill(x + 2, y + QUALITY_H - 2, x + QUALITY_W - 2, y + QUALITY_H - 1, qualityColor);
            }
            drawCenteredScaledText(graphics, tr(quality.labelKey()), x + QUALITY_W / 2.0F, y + 3.0F,
                    locked ? 0xFF737B87 : current ? qualityColor : 0xFFB8C0CE, 0.64F);
        }
    }

    private void renderMaterialPanel(GuiGraphics graphics, int left, int top) {
        int x = left + MATERIAL_PANEL_X;
        int y = top + MATERIAL_PANEL_Y;
        graphics.fill(x, y, x + MATERIAL_PANEL_W, y + MATERIAL_PANEL_H, 0xFF42505D);
        graphics.fill(x + 1, y + 1, x + MATERIAL_PANEL_W - 1, y + MATERIAL_PANEL_H - 1, 0xFF111820);
        drawCenteredScaledText(graphics, tr("screen.miningdim.gunsmith_press.materials"),
                x + MATERIAL_PANEL_W / 2.0F, y + 4.0F, 0xFFDDE4F1, 0.58F);

        renderInputSlotStatus(graphics, left, top, SLOT_GUN_PARTS_X, SLOT_TOP_Y,
                GunsmithPressBlockEntity.SLOT_GUN_PARTS, menu.requiredGunParts(), top + 73);
        renderInputSlotStatus(graphics, left, top, SLOT_ALLOY_X, SLOT_TOP_Y,
                GunsmithPressBlockEntity.SLOT_ALLOY, menu.requiredAlloy(), top + 73);
        renderInputSlotStatus(graphics, left, top, SLOT_POLYMER_X, SLOT_BOTTOM_Y,
                GunsmithPressBlockEntity.SLOT_POLYMER, menu.requiredPolymer(), top + 132);
        renderOutputSlotStatus(graphics, left, top);
    }

    private void renderInputSlotStatus(GuiGraphics graphics, int left, int top, int slotX, int slotY,
                                       int slotIndex, int required, int statusY) {
        int present = menu.inputCount(slotIndex);
        boolean enough = present >= required;
        int border = enough ? 0xFF35D2A4 : 0xFFE0525C;
        drawSlotFrame(graphics, left + slotX, top + slotY, border);
        drawCenteredScaledText(graphics, present + "/" + required,
                left + slotX + 9.0F, statusY, enough ? 0xFF83EAD2 : 0xFFFF7774, 0.52F);
    }

    private void renderOutputSlotStatus(GuiGraphics graphics, int left, int top) {
        boolean hasOutput = menu.getSlot(GunsmithPressBlockEntity.SLOT_OUTPUT).hasItem();
        drawSlotFrame(graphics, left + SLOT_OUTPUT_X, top + SLOT_BOTTOM_Y,
                hasOutput ? 0xFFC56CFF : 0xFF556071);
        drawCenteredScaledText(graphics, tr("screen.miningdim.gunsmith_press.output"),
                left + SLOT_OUTPUT_X + 9.0F, top + 132.0F,
                hasOutput ? 0xFFD9A5FF : 0xFF7F8795, 0.48F);
    }

    private void renderStartButton(GuiGraphics graphics, int left, int top, int mouseX, int mouseY) {
        int x = left + START_X;
        int y = top + START_Y;
        boolean active = menu.isPressing();
        boolean ready = !isPlatformSyncPending() && menu.canStart();
        boolean hover = ready && inRect(mouseX, mouseY, x, y, START_W, START_H);
        int outer = active ? 0xFFB9853A : ready ? (hover ? 0xFF62E6C8 : 0xFF35D2A4) : 0xFF454D5A;
        int inner = active ? 0xFF473623 : ready ? (hover ? 0xFF185E55 : 0xFF144C46) : 0xFF222731;
        graphics.fill(x, y, x + START_W, y + START_H, outer);
        graphics.fill(x + 1, y + 1, x + START_W - 1, y + START_H - 1, inner);
        graphics.fill(x + 3, y + 3, x + START_W - 3, y + 4,
                active ? 0xFFFFC866 : ready ? (hover ? 0xFF9FFFF0 : 0xFF55DCC2) : 0xFF59616D);
        graphics.fill(x + 3, y + START_H - 4, x + START_W - 3, y + START_H - 3,
                active ? 0xFFFFA54F : ready ? 0xFFFFC866 : 0xFF343A45);
        drawFittedCenteredText(graphics,
                tr(active ? "screen.miningdim.gunsmith_press.pressing" : "screen.miningdim.gunsmith_press.start"),
                x + START_W / 2.0F, y + 8.0F, START_W - 6,
                active ? 0xFFFFE4B5 : ready ? 0xFFEAFBF7 : 0xFF858E9B, 0.68F, 0.48F);
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

        drawCenteredScaledText(graphics,
                tr(menu.isPressing()
                        ? "screen.miningdim.gunsmith_press.remaining_time"
                        : "screen.miningdim.gunsmith_press.craft_time"),
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
                Component.translatable("gui.miningdim.munitions.xp_tooltip",
                        playerShownXp(), playerNextLevelXp()), mouseX, mouseY);
    }

    private void drawPartIcon(GuiGraphics graphics, int x, int y, int size,
                              GunsmithPlatform platform, GunsmithPressPart part,
                              GunsmithPartVariant variant, GunsmithPartQuality quality) {
        String variantSuffix = variant == GunsmithPartVariant.BASIC ? "" : "_" + variant.id();
        ResourceLocation texture = new ResourceLocation(MiningConstants.MODID,
                "textures/item/gunsmith_part_" + platform.id() + "_" + part.id()
                        + variantSuffix + "_" + quality.id() + ".png");
        graphics.blit(texture, x, y, size, size,
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
        if (renderVariantPopupTooltip(graphics, mouseX, mouseY, left, top)) {
            return;
        }
        int platformX = left + PLATFORM_X;
        int platformY = top + PLATFORM_Y;
        if (inRect(mouseX, mouseY, platformX, platformY, PLATFORM_W, PLATFORM_H)) {
            graphics.renderTooltip(this.font,
                    Component.translatable("screen.miningdim.gunsmith_press.platform_tooltip",
                            Component.translatable(menu.selectedPlatform().labelKey())), mouseX, mouseY);
            return;
        }
        int row = 0;
        for (GunsmithPressPart part : menu.selectedPlatform().supportedParts()) {
            int x = left + PART_X;
            int y = top + PART_Y + row * (PART_H + PART_GAP);
            if (inRect(mouseX, mouseY, x, y, PART_W, PART_H)) {
                graphics.renderTooltip(this.font,
                        Component.translatable("screen.miningdim.gunsmith_press.slot_tooltip",
                                Component.translatable(part.slotKey()), Component.translatable(part.roleKey())),
                        mouseX, mouseY);
                return;
            }
            row++;
        }
        for (GunsmithPartQuality quality : GunsmithPartQuality.values()) {
            int x = left + QUALITY_X + quality.index() * (QUALITY_W + QUALITY_GAP);
            int y = top + QUALITY_Y;
            if (inRect(mouseX, mouseY, x, y, QUALITY_W, QUALITY_H)) {
                renderWrappedTooltip(graphics,
                        Component.translatable("screen.miningdim.gunsmith_press.quality_tooltip",
                                Component.literal(variantDisplayName(menu.selectedPlatform(), menu.selectedPart(),
                                        menu.selectedVariant())), Component.translatable(quality.labelKey())),
                        mouseX, mouseY);
                return;
            }
        }
        if (inRect(mouseX, mouseY, left + DETAIL_X, top + DETAIL_Y, DETAIL_W, DETAIL_H)) {
            renderWrappedTooltip(graphics,
                    Component.translatable(menu.selectedVariant().descriptionKey()), mouseX, mouseY);
            return;
        }
        if (renderMaterialTooltip(graphics, mouseX, mouseY, left, top)) {
            return;
        }
        if (inRect(mouseX, mouseY, left + START_X, top + START_Y, START_W, START_H)) {
            String key;
            if (isPlatformSyncPending()) {
                key = "screen.miningdim.gunsmith_press.syncing_selection";
            } else if (menu.isPressing()) {
                key = "message.miningdim.gunsmith_press.busy";
            } else if (menu.getSlot(GunsmithPressBlockEntity.SLOT_OUTPUT).hasItem()) {
                key = "message.miningdim.gunsmith_press.output_blocked";
            } else if (menu.canStart()) {
                key = "tooltip.miningdim.gunsmith_press.start";
            } else {
                key = "message.miningdim.gunsmith_press.missing_materials";
            }
            graphics.renderTooltip(this.font, Component.translatable(key), mouseX, mouseY);
        }
    }

    private boolean renderVariantPopupTooltip(GuiGraphics graphics, int mouseX, int mouseY,
                                              int left, int top) {
        if (!isVariantMenuOpen() || menu.isPressing()) {
            return false;
        }
        List<GunsmithPartVariant> variants = openVariants();
        int x = variantPopupX(left);
        int y = variantPopupY(top);
        for (int index = 0; index < variants.size(); index++) {
            int itemX = x + VARIANT_POPUP_PADDING;
            int itemY = y + VARIANT_POPUP_PADDING
                    + index * (VARIANT_POPUP_ITEM_H + VARIANT_POPUP_GAP);
            if (inRect(mouseX, mouseY, itemX, itemY,
                    VARIANT_POPUP_W - VARIANT_POPUP_PADDING * 2, VARIANT_POPUP_ITEM_H)) {
                renderWrappedTooltip(graphics,
                        Component.translatable(variants.get(index).descriptionKey()), mouseX, mouseY);
                return true;
            }
        }
        return inRect(mouseX, mouseY, x, y, VARIANT_POPUP_W, variantPopupHeight(variants.size()));
    }

    private boolean renderMaterialTooltip(GuiGraphics graphics, int mouseX, int mouseY, int left, int top) {
        if (renderInputMaterialTooltip(graphics, mouseX, mouseY, left, top,
                SLOT_GUN_PARTS_X, SLOT_TOP_Y, GunsmithPressBlockEntity.SLOT_GUN_PARTS,
                menu.requiredGunParts(), "screen.miningdim.gunsmith_press.material.gun_parts")) {
            return true;
        }
        if (renderInputMaterialTooltip(graphics, mouseX, mouseY, left, top,
                SLOT_ALLOY_X, SLOT_TOP_Y, GunsmithPressBlockEntity.SLOT_ALLOY,
                menu.requiredAlloy(), "screen.miningdim.gunsmith_press.material.alloy")) {
            return true;
        }
        if (renderInputMaterialTooltip(graphics, mouseX, mouseY, left, top,
                SLOT_POLYMER_X, SLOT_BOTTOM_Y, GunsmithPressBlockEntity.SLOT_POLYMER,
                menu.requiredPolymer(), "screen.miningdim.gunsmith_press.material.polymer")) {
            return true;
        }
        if (inRect(mouseX, mouseY, left + SLOT_OUTPUT_X - 2, top + SLOT_BOTTOM_Y - 2, 22, 22)) {
            graphics.renderTooltip(this.font,
                    Component.translatable("screen.miningdim.gunsmith_press.output"), mouseX, mouseY);
            return true;
        }
        return false;
    }

    private boolean renderInputMaterialTooltip(GuiGraphics graphics, int mouseX, int mouseY,
                                               int left, int top, int slotX, int slotY,
                                               int slotIndex, int required, String materialKey) {
        if (!inRect(mouseX, mouseY, left + slotX - 2, top + slotY - 2, 22, 22)) {
            return false;
        }
        graphics.renderTooltip(this.font,
                Component.translatable("screen.miningdim.gunsmith_press.material_status",
                        Component.translatable(materialKey), menu.inputCount(slotIndex), required),
                mouseX, mouseY);
        return true;
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        refreshPendingPlatform();
        int left = (this.width - W) / 2;
        int top = (this.height - H) / 2;
        if (button != 0) {
            boolean overPopup = isMouseOverVariantPopup(mouseX, mouseY, left, top);
            closeVariantMenu();
            if (overPopup) {
                return true;
            }
            return super.mouseClicked(mouseX, mouseY, button);
        }
        if (menu.isPressing()) {
            closeVariantMenu();
            return super.mouseClicked(mouseX, mouseY, button);
        }
        if (isPlatformSyncPending()) {
            closeVariantMenu();
            return super.mouseClicked(mouseX, mouseY, button);
        }
        if (clickVariantPopup(mouseX, mouseY, left, top)) {
            return true;
        }

        int platformX = left + PLATFORM_X;
        int platformY = top + PLATFORM_Y;
        if (inRect(mouseX, mouseY, platformX, platformY, PLATFORM_ARROW_W, PLATFORM_H)) {
            closeVariantMenu();
            requestPlatform(shiftedPlatformIndex(-1));
            return true;
        }
        if (inRect(mouseX, mouseY, platformX + PLATFORM_W - PLATFORM_ARROW_W,
                platformY, PLATFORM_ARROW_W, PLATFORM_H)) {
            closeVariantMenu();
            requestPlatform(shiftedPlatformIndex(1));
            return true;
        }

        int row = 0;
        GunsmithPlatform platform = menu.selectedPlatform();
        for (GunsmithPressPart part : platform.supportedParts()) {
            int x = left + PART_X;
            int y = top + PART_Y + row * (PART_H + PART_GAP);
            if (inRect(mouseX, mouseY, x, y, PART_W, PART_H)) {
                boolean wasOpen = isVariantMenuOpenFor(platform, part);
                List<GunsmithPartVariant> variants = GunsmithPartVariant.availableFor(platform, part);
                sendButton(GunsmithPressMenu.BUTTON_PART_BASE + row);
                if (variants.size() > 1 && !wasOpen) {
                    openVariantMenu(platform, part);
                } else {
                    closeVariantMenu();
                }
                return true;
            }
            row++;
        }

        closeVariantMenu();
        for (GunsmithPartQuality quality : GunsmithPartQuality.values()) {
            int x = left + QUALITY_X + quality.index() * (QUALITY_W + QUALITY_GAP);
            int y = top + QUALITY_Y;
            if (inRect(mouseX, mouseY, x, y, QUALITY_W, QUALITY_H)) {
                sendButton(GunsmithPressMenu.BUTTON_QUALITY_BASE + quality.index());
                return true;
            }
        }
        if (menu.canStart()
                && inRect(mouseX, mouseY, left + START_X, top + START_Y, START_W, START_H)) {
            sendButton(GunsmithPressMenu.BUTTON_START_PREVIEW);
            return true;
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    private boolean clickVariantPopup(double mouseX, double mouseY, int left, int top) {
        if (!isVariantMenuOpen()) {
            return false;
        }
        List<GunsmithPartVariant> variants = openVariants();
        int x = variantPopupX(left);
        int y = variantPopupY(top);
        boolean authoritative = variantMenuOwnerIsAuthoritative();
        for (int index = 0; index < variants.size(); index++) {
            int itemX = x + VARIANT_POPUP_PADDING;
            int itemY = y + VARIANT_POPUP_PADDING
                    + index * (VARIANT_POPUP_ITEM_H + VARIANT_POPUP_GAP);
            if (inRect(mouseX, mouseY, itemX, itemY,
                    VARIANT_POPUP_W - VARIANT_POPUP_PADDING * 2, VARIANT_POPUP_ITEM_H)) {
                if (authoritative) {
                    sendButton(GunsmithPressMenu.BUTTON_VARIANT_BASE + variants.get(index).index());
                    closeVariantMenu();
                }
                return true;
            }
        }
        return inRect(mouseX, mouseY, x, y, VARIANT_POPUP_W, variantPopupHeight(variants.size()));
    }

    private boolean isMouseOverVariantPopup(double mouseX, double mouseY, int left, int top) {
        if (!isVariantMenuOpen() || menu.isPressing()) {
            return false;
        }
        List<GunsmithPartVariant> variants = openVariants();
        return inRect(mouseX, mouseY, variantPopupX(left), variantPopupY(top),
                VARIANT_POPUP_W, variantPopupHeight(variants.size()));
    }

    private void requestPlatform(int platformIndex) {
        pendingPlatform = GunsmithPlatform.byIndex(platformIndex);
        sendButton(GunsmithPressMenu.BUTTON_PLATFORM_BASE + platformIndex);
    }

    private void sendButton(int id) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.gameMode != null) {
            mc.gameMode.handleInventoryButtonClick(menu.containerId, id);
        }
    }

    private String tr(String key) {
        return Component.translatable(key).getString();
    }

    private void renderWrappedTooltip(GuiGraphics graphics, Component text, int mouseX, int mouseY) {
        graphics.renderTooltip(this.font, this.font.split(text, 220), mouseX, mouseY);
    }

    private boolean isVariantMenuOpen() {
        return variantMenuPlatform != null && variantMenuPart != null;
    }

    private boolean isPlatformSyncPending() {
        return pendingPlatform != null && menu.selectedPlatform() != pendingPlatform;
    }

    private boolean selectionLocked() {
        return menu.isPressing() || isPlatformSyncPending();
    }

    private void refreshPendingPlatform() {
        if (pendingPlatform == null) {
            return;
        }
        if (menu.isPressing() || menu.selectedPlatform() == pendingPlatform) {
            pendingPlatform = null;
        }
    }

    private boolean isVariantMenuOpenFor(GunsmithPlatform platform, GunsmithPressPart part) {
        return variantMenuPlatform == platform && variantMenuPart == part;
    }

    private boolean variantMenuOwnerIsAuthoritative() {
        return isVariantMenuOpen() && !menu.isPressing()
                && menu.selectedPlatform() == variantMenuPlatform
                && menu.selectedPart() == variantMenuPart;
    }

    private void openVariantMenu(GunsmithPlatform platform, GunsmithPressPart part) {
        variantMenuPlatform = platform;
        variantMenuPart = part;
    }

    private void closeVariantMenu() {
        variantMenuPlatform = null;
        variantMenuPart = null;
    }

    private List<GunsmithPartVariant> openVariants() {
        return GunsmithPartVariant.availableFor(variantMenuPlatform, variantMenuPart);
    }

    private static int partRow(GunsmithPlatform platform, GunsmithPressPart target) {
        int row = 0;
        for (GunsmithPressPart part : platform.supportedParts()) {
            if (part == target) {
                return row;
            }
            row++;
        }
        return -1;
    }

    private int variantPopupX(int left) {
        return left + PART_X + PART_W + VARIANT_POPUP_OFFSET_X;
    }

    private int variantPopupY(int top) {
        int rawY = top + PART_Y + partRow(variantMenuPlatform, variantMenuPart) * (PART_H + PART_GAP);
        int popupHeight = variantPopupHeight(openVariants().size());
        int maximumY = top + H - popupHeight - 8;
        return Math.max(top + 8, Math.min(rawY, maximumY));
    }

    private static int variantPopupHeight(int variantCount) {
        return VARIANT_POPUP_PADDING * 2 + variantCount * VARIANT_POPUP_ITEM_H
                + (variantCount - 1) * VARIANT_POPUP_GAP;
    }

    private String variantMenuLabel(GunsmithPressPart part, GunsmithPartVariant variant) {
        if (variant == GunsmithPartVariant.BASIC) {
            return tr(part.labelKey());
        }
        return tr(variant.labelKey());
    }

    private String variantDisplayName(GunsmithPlatform platform, GunsmithPressPart part,
                                      GunsmithPartVariant variant) {
        if (variant == GunsmithPartVariant.BASIC) {
            return tr(platform.labelKey()) + tr(part.labelKey());
        }
        return tr(variant.labelKey());
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

    private void drawFittedText(GuiGraphics graphics, String value, float x, float y,
                                int maxWidth, int argb, float preferredScale, float minScale) {
        int width = Math.max(1, this.font.width(value));
        float scale = Math.min(preferredScale, Math.max(minScale, maxWidth / (float) width));
        drawScaledText(graphics, value, x, y, argb, scale);
    }

    private static void drawSlotFrame(GuiGraphics graphics, int x, int y, int border) {
        graphics.fill(x - 2, y - 2, x + 20, y + 20, border);
        graphics.fill(x - 1, y - 1, x + 19, y + 19, 0xFF080C10);
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

    private static String formatSignedPercent(double percentage) {
        return String.format(Locale.ROOT, "%+.1f%%", percentage);
    }

    private static String formatSignedPercentRange(double minimum, double maximum) {
        if (Math.abs(maximum - minimum) < 0.0005D) {
            return formatSignedPercent(minimum);
        }
        return formatSignedPercent(minimum) + "~" + formatSignedPercent(maximum);
    }

    private static boolean inRect(double mx, double my, int x, int y, int w, int h) {
        return mx >= x && mx < x + w && my >= y && my < y + h;
    }
}
