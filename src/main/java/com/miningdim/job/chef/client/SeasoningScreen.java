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
import org.lwjgl.glfw.GLFW;

import java.util.Locale;

/**
 * 调味台客户端界面。服务端同步的菜单数据是唯一状态来源；本类只负责绘制和发送玩家输入意图。
 * 火候阶段使用按下/释放两个动作，避免客户端用单次点击伪造持续控火；QTE 只发送被点击的目标编号。
 */
public final class SeasoningScreen extends AbstractMiningScreen<SeasoningMenu> {

    private static final ResourceLocation BG =
            new ResourceLocation(MiningConstants.MODID, "textures/gui/seasoning_table.png");
    private static final int WIDTH = 256;
    private static final int HEIGHT = 232;

    private static final int HEAT_BAR_X = 76;
    private static final int HEAT_BAR_Y = 55;
    private static final int HEAT_BAR_W = 164;
    private static final int HEAT_BAR_H = 10;
    private static final int HEAT_BUTTON_X = 76;
    private static final int HEAT_BUTTON_Y = 72;
    private static final int HEAT_BUTTON_W = 164;
    private static final int HEAT_BUTTON_H = 20;
    private static final int START_X = 76;
    private static final int START_Y = 99;
    private static final int START_W = 164;
    private static final int START_H = 20;
    private static final int QTE_X = 76;
    private static final int QTE_Y = 72;
    private static final int QTE_W = 38;
    private static final int QTE_H = 20;
    private static final int QTE_GAP = 4;
    private static final int QUALITY_X = 76;
    private static final int QUALITY_Y = 72;
    private static final int QUALITY_W = 30;
    private static final int QUALITY_H = 20;
    private static final int QUALITY_GAP = 3;

    private boolean heatPressed;
    private int selectedTargetTier = -1;

    public SeasoningScreen(SeasoningMenu menu, Inventory inv, Component title) {
        super(menu, inv, title, BG, WIDTH, HEIGHT);
        this.titleLabelX = 12;
        this.titleLabelY = 8;
        this.inventoryLabelX = 47;
        this.inventoryLabelY = 128;
    }

    @Override
    protected void renderBg(GuiGraphics graphics, float partialTick, int mouseX, int mouseY) {
        int leftPos = (this.width - WIDTH) / 2;
        int topPos = (this.height - HEIGHT) / 2;
        // 不能使用默认 256x256 UV 尺寸：本界面高 232px，否则背景会被纵向采样错位，槽位与文字全部叠在一起。
        graphics.blit(BG, leftPos, topPos, WIDTH, HEIGHT,
                0.0F, 0.0F, WIDTH, HEIGHT, WIDTH, HEIGHT);
        renderExtra(graphics, leftPos, topPos, mouseX, mouseY, partialTick);
    }

    @Override
    protected void renderLabels(GuiGraphics graphics, int mouseX, int mouseY) {
        graphics.drawString(font, title, titleLabelX, titleLabelY, 0xFFF4E3B6, false);
        graphics.drawString(font, playerInventoryTitle,
                inventoryLabelX, inventoryLabelY, 0xFFD5C8B5, false);
    }

    @Override
    protected void renderExtra(GuiGraphics graphics, int leftPos, int topPos,
                               int mouseX, int mouseY, float partialTick) {
        int phase = menu.phase();
        if (phase == 0) {
            ensureSelectedTarget();
        }
        renderStatus(graphics, leftPos, topPos, phase);
        renderHeatBar(graphics, leftPos, topPos);
        if (phase == 2) {
            renderTargets(graphics, leftPos, topPos, mouseX, mouseY, partialTick);
        } else if (phase == 0) {
            renderQualityChoices(graphics, leftPos, topPos, mouseX, mouseY);
            renderButton(graphics, leftPos + START_X, topPos + START_Y,
                    START_W, START_H, Component.translatable("screen.miningdim.chef.start"),
                    0xFF2F7D4D);
            graphics.drawString(font, Component.translatable("screen.miningdim.chef.selectable_cap",
                            qualityText(menu.tierCap())), leftPos + 12, topPos + 102, 0xFFE2BD6B, false);
        } else if (phase == 1) {
            renderButton(graphics, leftPos + HEAT_BUTTON_X, topPos + HEAT_BUTTON_Y,
                    HEAT_BUTTON_W, HEAT_BUTTON_H,
                    Component.translatable(heatPressed
                            ? "screen.miningdim.chef.heat.release"
                            : "screen.miningdim.chef.heat.press"),
                    heatPressed ? 0xFFB86B2B : 0xFF386B87);
        }
        renderSlotLabels(graphics, leftPos, topPos);
        renderOutcome(graphics, leftPos, topPos, phase);
    }

    private void renderStatus(GuiGraphics graphics, int leftPos, int topPos, int phase) {
        Component state = switch (phase) {
            case 1 -> Component.translatable("screen.miningdim.chef.phase.heat");
            case 2 -> Component.translatable("screen.miningdim.chef.phase.season");
            case 3 -> Component.translatable("screen.miningdim.chef.phase.done");
            default -> Component.translatable("screen.miningdim.chef.phase.idle");
        };
        graphics.drawString(font, state, leftPos + 12, topPos + 26, 0xFFE9E2D3, false);
        graphics.drawString(font, Component.translatable("screen.miningdim.chef.time",
                        menu.remainingTicks()), leftPos + 116, topPos + 26, 0xFFC8D1D4, false);
        ChefQuality target = displayedTarget(phase);
        int qteCount = phase == 0 && target != null
                ? menu.targetPreviewQteCount(target) : menu.qteCount();
        graphics.drawString(font, Component.translatable("screen.miningdim.chef.hits",
                        menu.hits(), qteCount), leftPos + 200, topPos + 26, 0xFFC8D1D4, false);
        if (target != null) {
            graphics.drawString(font, Component.translatable("screen.miningdim.chef.target_quality",
                            qualityText(target)), leftPos + 76, topPos + 42, 0xFFE2BD6B, false);
            graphics.drawString(font, Component.translatable("screen.miningdim.chef.success_chance",
                            formatChance(displayedChancePerMille(phase, target))),
                    leftPos + 168, topPos + 42, 0xFF8ED7A7, false);
        }
    }

    private void renderQualityChoices(GuiGraphics graphics, int leftPos, int topPos,
                                      int mouseX, int mouseY) {
        int cap = menu.selectableCap().tier();
        for (ChefQuality quality : ChefQuality.values()) {
            int x = leftPos + QUALITY_X + quality.tier() * (QUALITY_W + QUALITY_GAP);
            boolean enabled = quality.tier() <= cap;
            boolean selected = quality.tier() == selectedTargetTier;
            boolean hovered = enabled && inRect(mouseX, mouseY, x, topPos + QUALITY_Y, QUALITY_W, QUALITY_H);
            int color = !enabled ? 0xFF3B3B3B : selected ? 0xFF8A5B25 : 0xFF30434D;
            if (hovered) {
                color = selected ? 0xFFB47532 : 0xFF47616E;
            }
            renderButton(graphics, x, topPos + QUALITY_Y, QUALITY_W, QUALITY_H,
                    Component.translatable("screen.miningdim.chef.target_quality.short." + quality.id()), color);
        }
    }

    private void renderSlotLabels(GuiGraphics graphics, int leftPos, int topPos) {
        graphics.drawString(font, Component.translatable("screen.miningdim.chef.slot.dish"),
                leftPos + 17, topPos + 42, 0xFFBFAF98, false);
        graphics.drawString(font, Component.translatable("screen.miningdim.chef.slot.seasoning"),
                leftPos + 43, topPos + 42, 0xFFBFAF98, false);
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
                               int mouseX, int mouseY, float partialTick) {
        Component targetPrompt = menu.cueActive()
                ? Component.translatable("screen.miningdim.chef.target", menu.targetIndex() + 1)
                : Component.translatable("screen.miningdim.chef.target.wait");
        graphics.drawString(font, targetPrompt, leftPos + 12, topPos + 99, 0xFFF0CE72, false);
        graphics.drawString(font, Component.translatable("screen.miningdim.chef.target.keyboard_hint"),
                leftPos + 12, topPos + 112, 0xFFB8C6CE, false);
        for (int target = 0; target < 4; target++) {
            int x = leftPos + QTE_X + target * (QTE_W + QTE_GAP);
            int y = topPos + QTE_Y;
            boolean selected = menu.cueActive() && target == menu.targetIndex();
            boolean hovered = inRect(mouseX, mouseY, x, y, QTE_W, QTE_H);
            int color = selected ? 0xFFB47532 : 0xFF30434D;
            if (hovered) {
                color = selected ? 0xFFD59A45 : 0xFF47616E;
            }
            renderButton(graphics, x, y, QTE_W, QTE_H,
                    Component.translatable("screen.miningdim.chef.target.position", target + 1), color);
            if (selected) {
                renderSwayingHitBar(graphics, x, y, partialTick);
            }
        }
    }

    private void renderSwayingHitBar(GuiGraphics graphics, int x, int y, float partialTick) {
        int periodTicks = menu.qteSwayPeriodTicks();
        if (periodTicks <= 0) {
            return;
        }
        double cycle = (minecraft.player.tickCount + partialTick) % periodTicks / periodTicks;
        double triangle = cycle < 0.5D ? cycle * 2.0D : (1.0D - cycle) * 2.0D;
        int trackStart = x + 3;
        int trackEnd = x + QTE_W - 3;
        int markerX = trackStart + (int) Math.round(triangle * (trackEnd - trackStart - 3));
        graphics.fill(trackStart, y + QTE_H - 5, trackEnd, y + QTE_H - 4, 0xFF5A3A1E);
        graphics.fill(markerX, y + QTE_H - 6, markerX + 3, y + QTE_H - 2, 0xFFFFE5A0);
    }

    private void renderOutcome(GuiGraphics graphics, int leftPos, int topPos, int phase) {
        if (phase != 3) {
            return;
        }
        if (menu.failureReason() == 0 && menu.finalQuality() >= 0) {
            Component result = menu.targetMet() == 0
                    ? Component.translatable("screen.miningdim.chef.target_missed", qualityText())
                    : Component.translatable("screen.miningdim.chef.quality", qualityText());
            graphics.drawString(font, result, leftPos + 12, topPos + 98, 0xFFF0CE72, false);
            graphics.drawWordWrap(font, Component.translatable("screen.miningdim.chef.done.take_output"),
                    leftPos + 12, topPos + 108, 232, 0xFFB8C6CE);
            return;
        }
        graphics.drawWordWrap(font, Component.translatable("screen.miningdim.chef.failure",
                        failureText()), leftPos + 12, topPos + 98, 232, 0xFFE58B8B);
    }

    private Component failureText() {
        return Component.translatable("screen.miningdim.chef.failure.reason." + menu.failureReason());
    }

    private Component qualityText() {
        ChefQuality quality = ChefQuality.byTier(menu.finalQuality());
        return qualityText(quality);
    }

    private static Component qualityText(ChefQuality quality) {
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
                int qualityTier = qualityAt(mouseX, mouseY, leftPos, topPos);
                if (qualityTier >= 0 && qualityTier <= menu.selectableCap().tier()) {
                    selectedTargetTier = qualityTier;
                    return true;
                }
                if (inRect(mouseX, mouseY, leftPos + START_X, topPos + START_Y, START_W, START_H)) {
                    ensureSelectedTarget();
                    send(SeasoningGameC2S.Action.START, selectedTargetTier);
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
                int target = menu.cueActive() ? targetAt(mouseX, mouseY, leftPos, topPos) : -1;
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

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        int target = qteTargetForKey(keyCode);
        if (menu.phase() == 2 && menu.cueActive() && target == menu.targetIndex()) {
            send(SeasoningGameC2S.Action.SEASON_HIT, target);
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    private static int qteTargetForKey(int keyCode) {
        return switch (keyCode) {
            case GLFW.GLFW_KEY_1, GLFW.GLFW_KEY_KP_1 -> 0;
            case GLFW.GLFW_KEY_2, GLFW.GLFW_KEY_KP_2 -> 1;
            case GLFW.GLFW_KEY_3, GLFW.GLFW_KEY_KP_3 -> 2;
            case GLFW.GLFW_KEY_4, GLFW.GLFW_KEY_KP_4 -> 3;
            default -> -1;
        };
    }

    private void send(SeasoningGameC2S.Action action, int target) {
        ChefNetwork.CHANNEL.sendToServer(new SeasoningGameC2S(action, target));
    }

    private void ensureSelectedTarget() {
        int cap = menu.selectableCap().tier();
        if (selectedTargetTier < 0) {
            selectedTargetTier = ChefQuality.LOW.tier();
        } else if (selectedTargetTier > cap) {
            selectedTargetTier = cap;
        }
    }

    private ChefQuality displayedTarget(int phase) {
        if (phase == 0) {
            return selectedTargetTier < 0 ? null : ChefQuality.byTier(selectedTargetTier);
        }
        int syncedTier = menu.targetQualityTier();
        return syncedTier < 0 ? null : ChefQuality.byTier(syncedTier);
    }

    private int displayedChancePerMille(int phase, ChefQuality target) {
        return phase == 0 ? menu.targetPreviewChancePerMille(target) : menu.successChancePerMille();
    }

    private static String formatChance(int perMille) {
        return String.format(Locale.ROOT, "%.1f%%", perMille / 10.0D);
    }

    private int qualityAt(double mouseX, double mouseY, int leftPos, int topPos) {
        for (ChefQuality quality : ChefQuality.values()) {
            int x = leftPos + QUALITY_X + quality.tier() * (QUALITY_W + QUALITY_GAP);
            if (inRect(mouseX, mouseY, x, topPos + QUALITY_Y, QUALITY_W, QUALITY_H)) {
                return quality.tier();
            }
        }
        return -1;
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
