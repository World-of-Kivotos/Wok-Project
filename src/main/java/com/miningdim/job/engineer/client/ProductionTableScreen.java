package com.miningdim.job.engineer.client;

import com.miningdim.core.MiningConstants;
import com.miningdim.job.ClientJobState;
import com.miningdim.job.JobId;
import com.miningdim.job.engineer.EngineerConfig;
import com.miningdim.job.engineer.EngineerLevels;
import com.miningdim.job.engineer.ModEngineerItems;
import com.miningdim.job.engineer.NanoTier;
import com.miningdim.job.engineer.menu.ProductionTableMenu;
import com.miningdim.menu.AbstractMiningScreen;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.ArrayList;
import java.util.List;

/**
 * Client screen for the nano production table. All authoritative selection and
 * calibration checks remain in the server-side menu and block entity.
 */
public final class ProductionTableScreen extends AbstractMiningScreen<ProductionTableMenu> {

    private static final ResourceLocation BG =
            new ResourceLocation(MiningConstants.MODID, "textures/gui/production_table.png");
    private static final int W = 256;
    private static final int H = 256;

    private static final int TIER_BTN_X = 20;
    private static final int TIER_BTN_Y = 24;
    private static final int TIER_BTN_W = 32;
    private static final int TIER_BTN_H = 33;
    private static final int TIER_BTN_GAP = 4;
    private static final int[] TIER_COLORS = {
            0xFFAEB8C2, 0xFFF2B72B, 0xFF28D7F2,
            0xFFA96CFF, 0xFFFF6A21, 0xFFFFE69B
    };

    private static final int BAR_X = 52;
    private static final int BAR_Y = 128;
    private static final int BAR_W = 152;
    private static final int BAR_H = 9;
    private static final int PROGRESS_X = 33;
    private static final int PROGRESS_Y = 143;
    private static final int PROGRESS_SEGMENTS = 12;
    private static final int PROGRESS_SEGMENT_W = 14;
    private static final int PROGRESS_SEGMENT_GAP = 2;

    private static final int LOCK_X = 229;
    private static final int LOCK_Y = 8;
    private static final int LOCK_W = 8;
    private static final int LOCK_H = 11;

    public ProductionTableScreen(ProductionTableMenu menu, Inventory inv, Component title) {
        super(menu, inv, title, BG, W, H);
    }

    @Override
    protected void renderExtra(GuiGraphics graphics, int leftPos, int topPos,
                               int mouseX, int mouseY, float partialTick) {
        renderTierButtons(graphics, leftPos, topPos, mouseX, mouseY);
        renderCalibrationBar(graphics, leftPos, topPos);
        renderLockState(graphics, leftPos, topPos);
    }

    @Override
    protected void renderLabels(GuiGraphics graphics, int mouseX, int mouseY) {
        graphics.drawString(font, title, 24, 10, 0xFFD9F7FF, false);
        graphics.drawString(font, playerInventoryTitle, 48, 154, 0xFF8DA4B8, false);
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        super.render(graphics, mouseX, mouseY, partialTick);
        renderControlTooltip(graphics, mouseX, mouseY);
    }

    private void renderTierButtons(GuiGraphics graphics, int leftPos, int topPos,
                                   int mouseX, int mouseY) {
        int selected = menu.selectedTierIndex();
        for (NanoTier tier : NanoTier.values()) {
            int idx = tier.index();
            int x = leftPos + TIER_BTN_X + idx * (TIER_BTN_W + TIER_BTN_GAP);
            int y = topPos + TIER_BTN_Y;
            boolean enabled = tierEnabled(tier);
            boolean hovered = inRect(mouseX, mouseY, x, y, TIER_BTN_W, TIER_BTN_H);
            int accent = enabled ? TIER_COLORS[idx] : 0xFF34404A;
            int overlay = enabled
                    ? hovered ? 0x44172E38 : idx == selected ? 0x33162A35 : 0x00000000
                    : 0xBB03060A;
            if (overlay != 0) {
                graphics.fill(x + 1, y + 1,
                        x + TIER_BTN_W - 1, y + TIER_BTN_H - 1, overlay);
            }
            renderRecipeCard(graphics, tier, x, y, enabled ? accent : 0xFF65727D);
            drawOutline(graphics, x, y, TIER_BTN_W, TIER_BTN_H, accent);
            if (idx == selected) {
                graphics.fill(x + 4, y + TIER_BTN_H - 3,
                        x + TIER_BTN_W - 4, y + TIER_BTN_H - 1, TIER_COLORS[idx]);
            }
        }
    }

    /**
     * Each tier selector is also a compact recipe card: input material, conversion
     * arrow, repair-kit output, and the document-defined base input/output ratio.
     * This keeps the six decorative frames from becoming unexplained controls.
     */
    private void renderRecipeCard(GuiGraphics graphics, NanoTier tier, int x, int y, int textColor) {
        ItemStack input = recipeInput(tier);
        ItemStack output = new ItemStack(ModEngineerItems.plate(tier).get());

        renderScaledItem(graphics, input, x + 3, y + 4, 0.75F);
        graphics.drawString(font, ">", x + 14, y + 6, 0xFF91D9E8, false);
        renderScaledItem(graphics, output, x + 19, y + 4, 0.75F);

        String ratio = tier.oreCost() + "→" + (tier.isRadiant() ? "?" : tier.outputCount());
        graphics.drawString(font, ratio,
                x + (TIER_BTN_W - font.width(ratio)) / 2, y + 20, textColor, false);
    }

    private static void renderScaledItem(GuiGraphics graphics, ItemStack stack,
                                         int x, int y, float scale) {
        graphics.pose().pushPose();
        graphics.pose().translate(x, y, 100.0F);
        graphics.pose().scale(scale, scale, 1.0F);
        graphics.renderItem(stack, 0, 0);
        graphics.pose().popPose();
    }

    private static ItemStack recipeInput(NanoTier tier) {
        ItemStack[] inputs = tier.oreIngredient().getItems();
        return inputs.length == 0 ? ItemStack.EMPTY : inputs[0];
    }

    private void renderCalibrationBar(GuiGraphics graphics, int leftPos, int topPos) {
        int x0 = leftPos + BAR_X;
        int y0 = topPos + BAR_Y;
        graphics.fill(x0, y0, x0 + BAR_W, y0 + BAR_H, 0xFF071923);

        int logicalWidth = EngineerConfig.CALIBRATION_BAR_WIDTH.get();
        int greenWidth = EngineerConfig.CALIBRATION_GREEN_WIDTH.get();
        if (logicalWidth <= 0) {
            return;
        }

        int gStart = scale(menu.greenStart(), logicalWidth, BAR_W);
        int gEnd = scale(menu.greenStart() + greenWidth, logicalWidth, BAR_W);
        graphics.fill(x0 + gStart, y0, x0 + gEnd, y0 + BAR_H, 0xFF216B43);
        graphics.fill(x0 + gStart, y0, x0 + gEnd, y0 + 1, 0xFF49D77E);

        int cursor = scale(menu.cursor(), logicalWidth, BAR_W);
        graphics.fill(x0 + cursor, y0 - 2, x0 + cursor + 2, y0 + BAR_H + 2, 0xFFE9FBFF);

        int goal = EngineerConfig.CALIBRATION_PROGRESS_GOAL.get();
        int tierIndex = menu.selectedTierIndex() >= 0
                ? menu.selectedTierIndex() : menu.machineTierIndex();
        int progressColor = TIER_COLORS[Math.max(0, Math.min(TIER_COLORS.length - 1, tierIndex))];
        int filledSegments = goal <= 0 ? 0 : (int) Math.min(PROGRESS_SEGMENTS,
                ((long) menu.progress() * PROGRESS_SEGMENTS + goal - 1L) / goal);
        int progressY = topPos + PROGRESS_Y;
        for (int i = 0; i < filledSegments; i++) {
            int progressX = leftPos + PROGRESS_X
                    + i * (PROGRESS_SEGMENT_W + PROGRESS_SEGMENT_GAP);
            graphics.fill(progressX + 1, progressY,
                    progressX + PROGRESS_SEGMENT_W - 1, progressY + 5, progressColor);
            graphics.fill(progressX + 2, progressY,
                    progressX + PROGRESS_SEGMENT_W - 2, progressY + 1, 0xFF75EFFF);
        }
    }

    private void renderLockState(GuiGraphics graphics, int leftPos, int topPos) {
        if (!menu.isLocked()) {
            return;
        }
        int x = leftPos + LOCK_X;
        int y = topPos + LOCK_Y;
        graphics.fill(x + 1, y + 3, x + 7, y + 9, 0xFF351019);
        drawOutline(graphics, x + 1, y + 3, 6, 6, 0xFFFF5368);
        graphics.fill(x + 2, y, x + 6, y + 1, 0xFFFF5368);
        graphics.fill(x + 1, y + 1, x + 2, y + 4, 0xFFFF5368);
        graphics.fill(x + 6, y + 1, x + 7, y + 4, 0xFFFF5368);
    }

    private void renderControlTooltip(GuiGraphics graphics, int mouseX, int mouseY) {
        int leftPos = (this.width - W) / 2;
        int topPos = (this.height - H) / 2;

        for (NanoTier tier : NanoTier.values()) {
            int x = leftPos + TIER_BTN_X + tier.index() * (TIER_BTN_W + TIER_BTN_GAP);
            int y = topPos + TIER_BTN_Y;
            if (inRect(mouseX, mouseY, x, y, TIER_BTN_W, TIER_BTN_H)) {
                graphics.renderComponentTooltip(font, tierTooltip(tier), mouseX, mouseY);
                return;
            }
        }

        if (inRect(mouseX, mouseY, leftPos + 22, topPos + 125, 212, 25)) {
            List<Component> tooltip = List.of(
                    Component.translatable("gui.miningdim.engineer.production_table.calibrate")
                            .withStyle(ChatFormatting.AQUA),
                    Component.translatable("gui.miningdim.engineer.production_table.progress",
                            menu.progress(), EngineerConfig.CALIBRATION_PROGRESS_GOAL.get())
                            .withStyle(ChatFormatting.GRAY),
                    Component.translatable("gui.miningdim.engineer.production_table.quality",
                            menu.quality(), EngineerConfig.CALIBRATION_QUALITY_BONUS_THRESHOLD.get())
                            .withStyle(ChatFormatting.GRAY),
                    Component.translatable("gui.miningdim.engineer.production_table.duration",
                            menu.elapsedTicks(), menu.requiredTicks())
                            .withStyle(ChatFormatting.GRAY));
            graphics.renderComponentTooltip(font, tooltip, mouseX, mouseY);
            return;
        }

        if (menu.isLocked() && inRect(mouseX, mouseY, leftPos + LOCK_X, topPos + LOCK_Y,
                LOCK_W, LOCK_H)) {
            graphics.renderComponentTooltip(font,
                    List.of(Component.translatable("message.miningdim.engineer.locked_indicator")
                            .withStyle(ChatFormatting.RED)), mouseX, mouseY);
        }
    }

    private List<Component> tierTooltip(NanoTier tier) {
        List<Component> tooltip = new ArrayList<>();
        ItemStack input = recipeInput(tier);
        ItemStack output = new ItemStack(ModEngineerItems.plate(tier).get());
        tooltip.add(output.getHoverName().copy().withStyle(ChatFormatting.AQUA));
        String recipeKey = tier.isRadiant()
                ? "gui.miningdim.engineer.production_table.recipe_chance"
                : "gui.miningdim.engineer.production_table.recipe";
        tooltip.add(Component.translatable(recipeKey,
                        input.getHoverName(), tier.oreCost(), output.getHoverName(), tier.outputCount())
                .withStyle(ChatFormatting.GRAY));
        if (tier.isRadiant()) {
            tooltip.add(Component.translatable(
                            "gui.miningdim.engineer.production_table.success_chance",
                            Math.round(EngineerConfig.RADIANT_SUCCESS_CHANCE.get() * 100.0D))
                    .withStyle(ChatFormatting.GOLD));
            int refund = EngineerConfig.RADIANT_FAIL_REFUND.get();
            if (refund > 0) {
                tooltip.add(Component.translatable(
                                "gui.miningdim.engineer.production_table.fail_refund",
                                refund, new ItemStack(Items.NETHERITE_SCRAP).getHoverName())
                        .withStyle(ChatFormatting.DARK_GRAY));
            }
        }
        tooltip.add(Component.translatable("gui.miningdim.engineer.production_table.bonus_output")
                .withStyle(ChatFormatting.DARK_AQUA));

        int level = ClientJobState.level(JobId.ENGINEER);
        if (!EngineerLevels.isTierUnlocked(level, tier)) {
            tooltip.add(Component.translatable("gui.miningdim.engineer.production_table.requires_level",
                    tier.unlockLevel()).withStyle(ChatFormatting.RED));
        }
        if (tier.index() > menu.machineTierIndex()) {
            NanoTier machineTier = NanoTier.byIndex(menu.machineTierIndex());
            tooltip.add(Component.translatable("gui.miningdim.engineer.production_table.requires_machine",
                    Component.translatable("tier.miningdim.nano." + machineTier.name().toLowerCase()))
                    .withStyle(ChatFormatting.RED));
        }

        NanoTier oreTier = NanoTier.maxTierForOre(menu.getSlot(0).getItem());
        if (oreTier == null) {
            tooltip.add(Component.translatable("gui.miningdim.engineer.production_table.requires_ore")
                    .withStyle(ChatFormatting.RED));
        } else if (!tier.allowedByOre(oreTier)) {
            tooltip.add(Component.translatable("gui.miningdim.engineer.production_table.ore_too_low")
                    .withStyle(ChatFormatting.RED));
        }

        if (tier.index() == menu.selectedTierIndex()) {
            tooltip.add(Component.translatable("gui.miningdim.engineer.production_table.selected")
                    .withStyle(ChatFormatting.GREEN));
        } else if (tierEnabled(tier)) {
            tooltip.add(Component.translatable("gui.miningdim.engineer.production_table.select")
                    .withStyle(ChatFormatting.YELLOW));
        }
        return tooltip;
    }

    private boolean tierEnabled(NanoTier tier) {
        int level = ClientJobState.level(JobId.ENGINEER);
        NanoTier oreTier = NanoTier.maxTierForOre(menu.getSlot(0).getItem());
        return EngineerLevels.isTierUnlocked(level, tier)
                && tier.index() <= menu.machineTierIndex()
                && tier.allowedByOre(oreTier);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        int leftPos = (this.width - W) / 2;
        int topPos = (this.height - H) / 2;

        for (NanoTier tier : NanoTier.values()) {
            int x = leftPos + TIER_BTN_X + tier.index() * (TIER_BTN_W + TIER_BTN_GAP);
            int y = topPos + TIER_BTN_Y;
            if (inRect(mouseX, mouseY, x, y, TIER_BTN_W, TIER_BTN_H)) {
                if (tierEnabled(tier)) {
                    sendButton(tier.index());
                }
                return true;
            }
        }

        if (inRect(mouseX, mouseY, leftPos + BAR_X, topPos + BAR_Y - 2,
                BAR_W, BAR_H + 4)) {
            sendButton(ProductionTableMenu.BUTTON_CALIBRATE);
            return true;
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    private void sendButton(int id) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.gameMode != null) {
            minecraft.gameMode.handleInventoryButtonClick(menu.containerId, id);
        }
    }

    private static int scale(int logical, int logicalWidth, int pixelWidth) {
        return (int) ((long) logical * pixelWidth / logicalWidth);
    }

    private static boolean inRect(double mouseX, double mouseY, int x, int y, int w, int h) {
        return mouseX >= x && mouseX < x + w && mouseY >= y && mouseY < y + h;
    }

    private static void drawOutline(GuiGraphics graphics, int x, int y, int w, int h, int color) {
        graphics.fill(x, y, x + w, y + 1, color);
        graphics.fill(x, y + h - 1, x + w, y + h, color);
        graphics.fill(x, y, x + 1, y + h, color);
        graphics.fill(x + w - 1, y, x + w, y + h, color);
    }
}
