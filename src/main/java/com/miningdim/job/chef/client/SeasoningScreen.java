package com.miningdim.job.chef.client;

import com.miningdim.core.MiningConstants;
import com.miningdim.job.chef.ChefNetwork;
import com.miningdim.job.chef.ChefQuality;
import com.miningdim.job.chef.SeasoningGameC2S;
import com.miningdim.job.chef.SeasoningMenu;
import com.miningdim.menu.AbstractMiningScreen;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Inventory;

/**
 * 调味台客户端界面。服务端同步的菜单数据是唯一状态来源；本类只负责绘制和发送玩家输入意图。
 * 火候阶段使用按下/释放两个动作，避免客户端用单次点击伪造持续控火；QTE 只发送被点击的目标编号。
 */
public final class SeasoningScreen extends AbstractMiningScreen<SeasoningMenu> {

    private static final ResourceLocation BG =
            new ResourceLocation(MiningConstants.MODID, "textures/gui/seasoning_table.png");
    private static final int WIDTH = 256;
    private static final int HEIGHT = 190;

    private static final int HEAT_BAR_X = 24;
    private static final int HEAT_BAR_Y = 47;
    private static final int HEAT_BAR_W = 208;
    private static final int HEAT_BAR_H = 8;
    private static final int HEAT_BUTTON_X = 24;
    private static final int HEAT_BUTTON_Y = 64;
    private static final int HEAT_BUTTON_W = 208;
    private static final int HEAT_BUTTON_H = 18;
    private static final int START_X = 24;
    private static final int START_Y = 64;
    private static final int START_W = 100;
    private static final int START_H = 18;
    private static final int QTE_X = 24;
    private static final int QTE_Y = 64;
    private static final int QTE_W = 46;
    private static final int QTE_H = 18;
    private static final int QTE_GAP = 8;

    private boolean heatPressed;

    public SeasoningScreen(SeasoningMenu menu, Inventory inv, Component title) {
        super(menu, inv, title, BG, WIDTH, HEIGHT);
    }

    @Override
    protected void renderExtra(GuiGraphics graphics, int leftPos, int topPos,
                               int mouseX, int mouseY, float partialTick) {
        int phase = menu.phase();
        renderStatus(graphics, leftPos, topPos, phase);
        renderHeatBar(graphics, leftPos, topPos);
        if (phase == 2) {
            renderTargets(graphics, leftPos, topPos, mouseX, mouseY);
        } else if (phase == 0) {
            renderButton(graphics, leftPos + START_X, topPos + START_Y,
                    START_W, START_H, Component.translatable("screen.miningdim.chef.start"),
                    0xFF2F7D4D);
        } else if (phase == 1) {
            renderButton(graphics, leftPos + HEAT_BUTTON_X, topPos + HEAT_BUTTON_Y,
                    HEAT_BUTTON_W, HEAT_BUTTON_H,
                    Component.translatable(heatPressed
                            ? "screen.miningdim.chef.heat.release"
                            : "screen.miningdim.chef.heat.press"),
                    heatPressed ? 0xFFB86B2B : 0xFF386B87);
        }
        renderOutcome(graphics, leftPos, topPos, phase);
    }

    private void renderStatus(GuiGraphics graphics, int leftPos, int topPos, int phase) {
        Component state = switch (phase) {
            case 1 -> Component.translatable("screen.miningdim.chef.phase.heat");
            case 2 -> Component.translatable("screen.miningdim.chef.phase.season");
            case 3 -> Component.translatable("screen.miningdim.chef.phase.done");
            default -> Component.translatable("screen.miningdim.chef.phase.idle");
        };
        graphics.drawString(font, state, leftPos + 24, topPos + 8, 0xFFE6EEF2, false);
        graphics.drawString(font, Component.translatable("screen.miningdim.chef.time",
                        menu.remainingTicks()), leftPos + 24, topPos + 20, 0xFFB8C6CE, false);
        graphics.drawString(font, Component.translatable("screen.miningdim.chef.hits",
                        menu.hits(), menu.qteCount()), leftPos + 126, topPos + 20, 0xFFB8C6CE, false);
        graphics.drawString(font, Component.translatable("screen.miningdim.chef.tier_cap",
                        menu.tierCap().id()), leftPos + 24, topPos + 34, 0xFFD7B86A, false);
    }

    private void renderHeatBar(GuiGraphics graphics, int leftPos, int topPos) {
        int x = leftPos + HEAT_BAR_X;
        int y = topPos + HEAT_BAR_Y;
        graphics.fill(x, y, x + HEAT_BAR_W, y + HEAT_BAR_H, 0xFF18232B);
        int heatMax = Math.max(1, menu.heatMax());
        int greenLow = scale(menu.greenStart(), heatMax, HEAT_BAR_W);
        int greenHigh = scale(menu.greenEnd(), heatMax, HEAT_BAR_W);
        graphics.fill(x + greenLow, y, x + greenHigh, y + HEAT_BAR_H, 0xFF2E7D52);
        int heat = scale(menu.heat(), heatMax, HEAT_BAR_W);
        graphics.fill(x + heat, y - 2, x + heat + 2, y + HEAT_BAR_H + 2, 0xFFEFF8FF);
    }

    private void renderTargets(GuiGraphics graphics, int leftPos, int topPos,
                               int mouseX, int mouseY) {
        Component targetPrompt = menu.cueActive()
                ? Component.translatable("screen.miningdim.chef.target", menu.targetIndex() + 1)
                : Component.translatable("screen.miningdim.chef.target.wait");
        graphics.drawString(font, targetPrompt, leftPos + QTE_X, topPos + 53, 0xFFF0CE72, false);
        for (int target = 0; target < 4; target++) {
            int x = leftPos + QTE_X + target * (QTE_W + QTE_GAP);
            int y = topPos + QTE_Y;
            boolean selected = target == menu.targetIndex();
            boolean hovered = inRect(mouseX, mouseY, x, y, QTE_W, QTE_H);
            int color = selected ? 0xFFB47532 : 0xFF30434D;
            if (hovered) {
                color = selected ? 0xFFD59A45 : 0xFF47616E;
            }
            renderButton(graphics, x, y, QTE_W, QTE_H,
                    Component.translatable("screen.miningdim.chef.target.position", target + 1), color);
        }
    }

    private void renderOutcome(GuiGraphics graphics, int leftPos, int topPos, int phase) {
        if (phase != 3) {
            return;
        }
        if (menu.failureReason() == 0 && menu.finalQuality() >= 0) {
            graphics.drawString(font, Component.translatable("screen.miningdim.chef.quality",
                            qualityText()), leftPos + 24, topPos + 88, 0xFFF0CE72, false);
            graphics.drawString(font, Component.translatable("screen.miningdim.chef.done.take_output"),
                    leftPos + 24, topPos + 101, 0xFFB8C6CE, false);
            return;
        }
        graphics.drawString(font, Component.translatable("screen.miningdim.chef.failure",
                        failureText()), leftPos + 24, topPos + 88, 0xFFE58B8B, false);
    }

    private Component failureText() {
        return Component.translatable("screen.miningdim.chef.failure.reason." + menu.failureReason());
    }

    private Component qualityText() {
        ChefQuality quality = ChefQuality.byTier(menu.finalQuality());
        return Component.translatable(quality.prefixKey());
    }

    private void renderButton(GuiGraphics graphics, int x, int y, int width, int height,
                              Component label, int color) {
        graphics.fill(x, y, x + width, y + height, 0xFF121B20);
        graphics.fill(x + 1, y + 1, x + width - 1, y + height - 1, color);
        graphics.drawString(font, label,
                x + (width - font.width(label)) / 2,
                y + (height - 8) / 2, 0xFFFFFFFF, false);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button != 0) {
            return super.mouseClicked(mouseX, mouseY, button);
        }
        int leftPos = (width - WIDTH) / 2;
        int topPos = (height - HEIGHT) / 2;
        switch (menu.phase()) {
            case 0 -> {
                if (inRect(mouseX, mouseY, leftPos + START_X, topPos + START_Y, START_W, START_H)) {
                    send(SeasoningGameC2S.Action.START, -1);
                    return true;
                }
            }
            case 1 -> {
                if (inRect(mouseX, mouseY, leftPos + HEAT_BUTTON_X, topPos + HEAT_BUTTON_Y,
                        HEAT_BUTTON_W, HEAT_BUTTON_H)) {
                    heatPressed = true;
                    send(SeasoningGameC2S.Action.HEAT_PRESS, -1);
                    return true;
                }
            }
            case 2 -> {
                int target = targetAt(mouseX, mouseY, leftPos, topPos);
                if (target >= 0) {
                    send(SeasoningGameC2S.Action.SEASON_HIT, target);
                    return true;
                }
            }
            default -> { }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        if (button == 0 && heatPressed) {
            heatPressed = false;
            send(SeasoningGameC2S.Action.HEAT_RELEASE, -1);
            return true;
        }
        return super.mouseReleased(mouseX, mouseY, button);
    }

    private void send(SeasoningGameC2S.Action action, int target) {
        ChefNetwork.CHANNEL.sendToServer(new SeasoningGameC2S(action, target));
    }

    private int targetAt(double mouseX, double mouseY, int leftPos, int topPos) {
        for (int target = 0; target < 4; target++) {
            int x = leftPos + QTE_X + target * (QTE_W + QTE_GAP);
            if (inRect(mouseX, mouseY, x, topPos + QTE_Y, QTE_W, QTE_H)) {
                return target;
            }
        }
        return -1;
    }

    private static int scale(int value, int maximum, int width) {
        return (int) ((long) value * width / maximum);
    }

    private static boolean inRect(double mouseX, double mouseY, int x, int y, int width, int height) {
        return mouseX >= x && mouseX < x + width && mouseY >= y && mouseY < y + height;
    }
}
