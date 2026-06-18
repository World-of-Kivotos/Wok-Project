package com.miningdim.job.engineer.client;

import com.miningdim.core.MiningConstants;
import com.miningdim.job.JobId;
import com.miningdim.job.ClientJobState;
import com.miningdim.job.engineer.EngineerConfig;
import com.miningdim.job.engineer.EngineerLevels;
import com.miningdim.job.engineer.NanoTier;
import com.miningdim.job.engineer.menu.ProductionTableMenu;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Inventory;
import com.miningdim.menu.AbstractMiningScreen;

/**
 * 生产台客户端 GUI (MillenniumEngineer_Mod_DesignSpec 4 / 10.5)。仅客户端 (经 MenuScreens.register 在
 * FMLClientSetupEvent 内注册, 见 {@link com.miningdim.job.engineer.EngineerSystem})。
 *
 * 渲染: 档位选择按钮 (三道门不满置灰; 矿石档由服务端选档校验, 客户端按等级 + 机器档预置灰提示) + 进度条 +
 * 纳米校准扫描条 (实时游标 + 随机绿区, 数据经 ContainerData 从服务端同步) + 锁状态指示。
 *
 * 服务端权威 (C5): 客户端置灰仅提示; 选档 / 校准点击 / 切锁均经 {@code handleInventoryButtonClick}
 * (原版 clickMenuButton 通道, 不新开网络包), 服务端重校三道门。全 1.20.1 写法。
 */
public final class ProductionTableScreen extends AbstractMiningScreen<ProductionTableMenu> {

    private static final ResourceLocation BG =
            new ResourceLocation(MiningConstants.MODID, "textures/gui/production_table.png");
    private static final int W = 176;
    private static final int H = 166;

    // 档位按钮布局 (顶部一排; 六档)。
    private static final int TIER_BTN_X = 8;
    private static final int TIER_BTN_Y = 16;
    private static final int TIER_BTN_W = 26;
    private static final int TIER_BTN_H = 14;
    private static final int TIER_BTN_GAP = 2;

    // 校准扫描条布局 (中下区)。
    private static final int BAR_X = 8;
    private static final int BAR_Y = 56;
    private static final int BAR_W = 160;
    private static final int BAR_H = 10;

    public ProductionTableScreen(ProductionTableMenu menu, Inventory inv, Component title) {
        super(menu, inv, title, BG, W, H);
    }

    @Override
    protected void renderExtra(GuiGraphics graphics, int leftPos, int topPos,
                               int mouseX, int mouseY, float partialTick) {
        renderTierButtons(graphics, leftPos, topPos);
        renderCalibrationBar(graphics, leftPos, topPos);
        renderLockState(graphics, leftPos, topPos);
    }

    private void renderTierButtons(GuiGraphics graphics, int leftPos, int topPos) {
        int machineTier = menu.machineTierIndex();
        int level = ClientJobState.level(JobId.ENGINEER);
        int selected = menu.selectedTierIndex();
        for (NanoTier tier : NanoTier.values()) {
            int idx = tier.index();
            int x = leftPos + TIER_BTN_X + idx * (TIER_BTN_W + TIER_BTN_GAP);
            int y = topPos + TIER_BTN_Y;
            boolean unlocked = EngineerLevels.isTierUnlocked(level, tier);
            boolean machineOk = idx <= machineTier;
            boolean enabled = unlocked && machineOk;
            int color;
            if (idx == selected) {
                color = 0xFF3FA34D; // 选中: 绿。
            } else if (enabled) {
                color = 0xFF5A5A5A; // 可选: 灰。
            } else {
                color = 0xFF2A2A2A; // 置灰 (等级/机器未达)。
            }
            graphics.fill(x, y, x + TIER_BTN_W, y + TIER_BTN_H, color);
            graphics.drawString(font, String.valueOf(idx + 1), x + 4, y + 3, enabled ? 0xFFFFFF : 0x888888, false);
        }
    }

    private void renderCalibrationBar(GuiGraphics graphics, int leftPos, int topPos) {
        int x0 = leftPos + BAR_X;
        int y0 = topPos + BAR_Y;
        // 条底。
        graphics.fill(x0, y0, x0 + BAR_W, y0 + BAR_H, 0xFF101010);

        int logicalWidth = EngineerConfig.CALIBRATION_BAR_WIDTH.get();
        int greenWidth = EngineerConfig.CALIBRATION_GREEN_WIDTH.get();
        if (logicalWidth <= 0) {
            return;
        }
        // 绿区。
        int gStart = scale(menu.greenStart(), logicalWidth, BAR_W);
        int gEnd = scale(menu.greenStart() + greenWidth, logicalWidth, BAR_W);
        graphics.fill(x0 + gStart, y0, x0 + gEnd, y0 + BAR_H, 0xFF3FA34D);
        // 游标。
        int cur = scale(menu.cursor(), logicalWidth, BAR_W);
        graphics.fill(x0 + cur, y0 - 2, x0 + cur + 2, y0 + BAR_H + 2, 0xFFFFFFFF);

        // 进度条 (条下方一行)。
        int goal = EngineerConfig.CALIBRATION_PROGRESS_GOAL.get();
        int progW = goal <= 0 ? 0 : (int) ((long) menu.progress() * BAR_W / goal);
        graphics.fill(x0, y0 + BAR_H + 4, x0 + Math.min(BAR_W, progW), y0 + BAR_H + 8, 0xFF4D7FA3);
    }

    private void renderLockState(GuiGraphics graphics, int leftPos, int topPos) {
        if (menu.isLocked()) {
            graphics.drawString(font,
                    Component.translatable("message.miningdim.engineer.locked_indicator"),
                    leftPos + 8, topPos + 4, 0xFFAA3030, false);
        }
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        int leftPos = (this.width - W) / 2;
        int topPos = (this.height - H) / 2;

        // 档位按钮点击 -> clickMenuButton(idx)。
        for (NanoTier tier : NanoTier.values()) {
            int idx = tier.index();
            int x = leftPos + TIER_BTN_X + idx * (TIER_BTN_W + TIER_BTN_GAP);
            int y = topPos + TIER_BTN_Y;
            if (inRect(mouseX, mouseY, x, y, TIER_BTN_W, TIER_BTN_H)) {
                sendButton(idx);
                return true;
            }
        }
        // 扫描条区域点击 -> 一次校准点击 (反挂机: 必须主动点)。
        if (inRect(mouseX, mouseY, leftPos + BAR_X, topPos + BAR_Y - 2, BAR_W, BAR_H + 4)) {
            sendButton(ProductionTableMenu.BUTTON_CALIBRATE);
            return true;
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    private void sendButton(int id) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.gameMode != null) {
            mc.gameMode.handleInventoryButtonClick(menu.containerId, id);
        }
    }

    private static int scale(int logical, int logicalWidth, int pixelWidth) {
        return (int) ((long) logical * pixelWidth / logicalWidth);
    }

    private static boolean inRect(double mx, double my, int x, int y, int w, int h) {
        return mx >= x && mx < x + w && my >= y && my < y + h;
    }
}
