package com.miningdim.power.client;

import com.miningdim.menu.AbstractMiningMenu;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Inventory;

import java.util.List;

/** Shared rendering shell for the power subsystem's pixel-art machine containers. */
public abstract class AbstractPowerMachineScreen<T extends AbstractMiningMenu>
        extends AbstractContainerScreen<T> {

    protected static final int STANDARD_WIDTH = 218;
    protected static final int STANDARD_HEIGHT = 222;
    protected static final int CONTROLLER_HEIGHT = 176;

    protected static final int TITLE_COLOR = 0xFFEAF8FF;
    protected static final int INVENTORY_TITLE_COLOR = 0xFFA6C9DC;
    protected static final int PRIMARY_TEXT_COLOR = 0xFFD8E8F0;
    protected static final int MUTED_TEXT_COLOR = 0xFF8DA4B8;
    protected static final int ENERGY_COLOR = 0xFF28D7F2;
    protected static final int PROCESS_COLOR = 0xFF49D77E;
    protected static final int INFUSION_COLOR = 0xFFA96CFF;
    protected static final int WARNING_COLOR = 0xFFF2B72B;
    protected static final int DANGER_COLOR = 0xFFFF5368;

    private static final int ATLAS_SIZE = 256;
    private static final int TITLE_X = 20;
    private static final int TITLE_Y = 8;
    private static final int INVENTORY_TITLE_X = 28;

    private final ResourceLocation background;
    private final int inventoryTitleY;

    protected AbstractPowerMachineScreen(T menu, Inventory inventory, Component title,
                                         ResourceLocation background, int height, int inventoryTitleY) {
        super(menu, inventory, title);
        if (background == null) {
            throw new IllegalArgumentException("power machine background must not be null");
        }
        this.background = background;
        this.inventoryTitleY = inventoryTitleY;
        this.imageWidth = STANDARD_WIDTH;
        this.imageHeight = height;
    }

    @Override
    protected final void renderBg(GuiGraphics graphics, float partialTick, int mouseX, int mouseY) {
        graphics.blit(background, leftPos, topPos, 0.0F, 0.0F,
                imageWidth, imageHeight, ATLAS_SIZE, ATLAS_SIZE);
        renderMachine(graphics, leftPos, topPos, mouseX, mouseY, partialTick);
    }

    @Override
    protected final void renderLabels(GuiGraphics graphics, int mouseX, int mouseY) {
        graphics.drawString(font, title, TITLE_X, TITLE_Y, TITLE_COLOR, false);
        graphics.drawString(font, playerInventoryTitle,
                INVENTORY_TITLE_X, inventoryTitleY, INVENTORY_TITLE_COLOR, false);
    }

    @Override
    public final void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        renderBackground(graphics);
        super.render(graphics, mouseX, mouseY, partialTick);
        renderTooltip(graphics, mouseX, mouseY);
        renderMachineTooltip(graphics, mouseX, mouseY);
    }

    protected abstract void renderMachine(GuiGraphics graphics, int left, int top,
                                          int mouseX, int mouseY, float partialTick);

    protected abstract void renderMachineTooltip(GuiGraphics graphics, int mouseX, int mouseY);

    protected final void drawMeter(GuiGraphics graphics, int x, int y, int width, int height,
                                   long value, long capacity, int fillColor) {
        int innerWidth = width - 2;
        int filled = meterFillWidth(value, capacity, innerWidth);
        if (filled <= 0) {
            return;
        }
        graphics.fill(x + 1, y + 1, x + 1 + filled, y + height - 1, fillColor);
        graphics.fill(x + 1, y + 1, x + 1 + filled, y + 2, brighten(fillColor));
    }

    protected final void drawFittedString(GuiGraphics graphics, Component text,
                                          int x, int y, int maxWidth, int color) {
        int textWidth = font.width(text);
        if (textWidth <= maxWidth) {
            graphics.drawString(font, text, x, y, color, false);
            return;
        }
        float scale = (float) maxWidth / textWidth;
        graphics.pose().pushPose();
        graphics.pose().translate(x, y, 0.0F);
        graphics.pose().scale(scale, scale, 1.0F);
        graphics.drawString(font, text, 0, 0, color, false);
        graphics.pose().popPose();
    }

    protected final void drawFittedCenteredString(GuiGraphics graphics, Component text,
                                                  int centerX, int y, int maxWidth, int color) {
        int textWidth = font.width(text);
        float scale = textWidth <= maxWidth ? 1.0F : (float) maxWidth / textWidth;
        graphics.pose().pushPose();
        graphics.pose().translate(centerX, y, 0.0F);
        graphics.pose().scale(scale, scale, 1.0F);
        graphics.drawString(font, text, -textWidth / 2, 0, color, false);
        graphics.pose().popPose();
    }

    protected final void showTooltip(GuiGraphics graphics, List<Component> lines,
                                     int mouseX, int mouseY) {
        graphics.renderComponentTooltip(font, lines, mouseX, mouseY);
    }

    protected static boolean inside(double mouseX, double mouseY, int x, int y, int width, int height) {
        return mouseX >= x && mouseX < x + width && mouseY >= y && mouseY < y + height;
    }

    static int meterFillWidth(long value, long capacity, int width) {
        if (capacity <= 0L || width <= 0) {
            return 0;
        }
        long clamped = Math.max(0L, Math.min(value, capacity));
        return (int) (clamped * width / capacity);
    }

    private static int brighten(int color) {
        int alpha = color & 0xFF000000;
        int red = Math.min(255, ((color >>> 16) & 0xFF) + 36);
        int green = Math.min(255, ((color >>> 8) & 0xFF) + 36);
        int blue = Math.min(255, (color & 0xFF) + 36);
        return alpha | (red << 16) | (green << 8) | blue;
    }
}
