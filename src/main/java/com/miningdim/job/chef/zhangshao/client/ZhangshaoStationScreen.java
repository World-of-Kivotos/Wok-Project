package com.miningdim.job.chef.zhangshao.client;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** 未接生产的台子只提供练习按钮，不伪装成可放料的生产容器。 */
public final class ZhangshaoStationScreen extends Screen {
    private final int station;

    public ZhangshaoStationScreen(int station) {
        super(Component.literal(new String[]{"厨锅", "炸锅", "烤炉", "备餐台"}[station]));
        this.station = station;
    }

    @Override
    protected void init() {
        addRenderableWidget(Button.builder(Component.literal("练习"),
                button -> minecraft.setScreen(new ZhangshaoPracticeScreen(station)))
                .bounds(width / 2 - 70, height / 2, 140, 20).build());
        addRenderableWidget(Button.builder(Component.literal("返回"), button -> onClose())
                .bounds(width / 2 - 70, height / 2 + 26, 140, 20).build());
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        renderBackground(graphics);
        graphics.drawCenteredString(font, title, width / 2, height / 2 - 42, 0xFFFFFF);
        graphics.drawCenteredString(font, "练习不消耗材料，不产出菜品或经验", width / 2, height / 2 - 22, 0xBBBBBB);
        super.render(graphics, mouseX, mouseY, partialTick);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
