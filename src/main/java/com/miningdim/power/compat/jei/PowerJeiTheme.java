package com.miningdim.power.compat.jei;

import mezz.jei.api.gui.drawable.IDrawable;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;

/** 代码绘制共享像素框架，使 JEI 外观不依赖额外纹理资源。 */
final class PowerJeiTheme {

    static final int WIDTH = 154;
    static final int HEIGHT = 80;
    static final int FIRST_INPUT_X = 8;
    static final int SECOND_INPUT_X = 42;
    static final int OUTPUT_X = 128;
    static final int SLOT_Y = 10;

    private static final int ARROW_X = 88;
    private static final int ARROW_Y = 10;
    private static final int FRAME_SHADOW = 0xFF080A0D;
    private static final int FRAME_HIGHLIGHT = 0xFF59616D;
    private static final int FRAME_EDGE = 0xFF303742;
    private static final int FRAME_FACE = 0xFF171C23;
    private static final int PANEL_FACE = 0xFF20262F;
    private static final int PANEL_INSET = 0xFF10151B;
    private static final int TRACK_SHADOW = 0xFF080C10;
    private static final int ACCENT = 0xFF31D2B4;
    private static final int ACCENT_DIM = 0xFF17675A;
    private static final int[] ROW_COLORS = {0xFFE4E9F0, 0xFF9AA4B2, 0xFF58DCC4, 0xFFF0B65B};

    private PowerJeiTheme() {
    }

    static void drawBackground(GuiGraphics graphics) {
        drawBeveledPanel(graphics, 0, 0, WIDTH, HEIGHT, FRAME_FACE);
        drawBeveledPanel(graphics, 3, 3, WIDTH - 6, 32, PANEL_FACE);
        drawBeveledPanel(graphics, 3, 37, WIDTH - 6, HEIGHT - 40, PANEL_INSET);
        graphics.fill(5, 40, 7, HEIGHT - 5, ACCENT);
    }

    static void drawPurifierFlow(GuiGraphics graphics, IDrawable arrow, IDrawable plus) {
        drawTrack(graphics, 60, ARROW_X);
        drawTrack(graphics, ARROW_X + arrow.getWidth(), OUTPUT_X);
        plus.draw(graphics, 29, 12);
        arrow.draw(graphics, ARROW_X, ARROW_Y);
    }

    static void drawAirFlow(GuiGraphics graphics, IDrawable arrow) {
        drawBeveledPanel(graphics, 8, 10, 52, 18, PANEL_INSET);
        graphics.fill(14, 14, 48, 15, ACCENT_DIM);
        graphics.fill(18, 18, 52, 19, ACCENT);
        graphics.fill(12, 22, 46, 23, ACCENT_DIM);
        drawTrack(graphics, 60, ARROW_X);
        drawTrack(graphics, ARROW_X + arrow.getWidth(), OUTPUT_X);
        arrow.draw(graphics, ARROW_X, ARROW_Y);
    }

    static void drawRows(GuiGraphics graphics, Component first, Component second,
                         Component third, Component fourth) {
        Font font = Minecraft.getInstance().font;
        Component[] rows = {first, second, third, fourth};
        for (int index = 0; index < rows.length; index++) {
            graphics.drawString(font, rows[index], 10, 39 + index * 10, ROW_COLORS[index], false);
        }
    }

    private static void drawTrack(GuiGraphics graphics, int startX, int endX) {
        graphics.fill(startX, 18, endX, 20, TRACK_SHADOW);
        graphics.fill(startX, 18, endX, 19, ACCENT_DIM);
    }

    private static void drawBeveledPanel(GuiGraphics graphics, int x, int y, int width, int height, int faceColor) {
        graphics.fill(x, y, x + width, y + height, FRAME_SHADOW);
        graphics.fill(x, y, x + width - 1, y + height - 1, FRAME_HIGHLIGHT);
        graphics.fill(x + 1, y + 1, x + width - 1, y + height - 1, FRAME_EDGE);
        graphics.fill(x + 2, y + 2, x + width - 2, y + height - 2, faceColor);
    }
}
