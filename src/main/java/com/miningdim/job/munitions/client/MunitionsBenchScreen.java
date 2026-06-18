package com.miningdim.job.munitions.client;

import com.miningdim.job.JobId;
import com.miningdim.job.ClientJobState;
import com.miningdim.job.munitions.MunitionsCaliber;
import com.miningdim.job.munitions.MunitionsLevels;
import com.miningdim.job.munitions.menu.MunitionsBenchMenu;
import com.miningdim.menu.AbstractMiningScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Inventory;

/**
 * 军火台客户端 GUI (Munitions_Job_DesignSpec 三/十章)。仅客户端 (经 MenuScreens.register 在 FMLClientSetupEvent
 * 内注册, 见 {@link com.miningdim.job.munitions.MunitionsSystem})。
 *
 * 贴图复用 TACZ 制枪台 GUI (规格三: 引用 {@code tacz:textures/gui/gun_smith_table.png}, 不拷贝其 PNG -> 不触
 * 再分发协议; TACZ 客户端硬依赖, 资源恒在)。这是相对工程师 Screen (自带 modid PNG) 的刻意分歧。
 *
 * 渲染: 口径选择按钮 (等级未达置灰; 服务端权威重校) + 缓冲进度条 (bufferedRounds / bufferCap, 缓冲满即停产提示)
 * + 提炼解锁指示 + 锁状态。服务端权威: 客户端置灰仅提示, 选口径/切锁经 handleInventoryButtonClick (原版通道)。
 */
public final class MunitionsBenchScreen extends AbstractMiningScreen<MunitionsBenchMenu> {

    /** 复用 TACZ 制枪台贴图 (不拷 PNG; TACZ 客户端资源恒在)。 */
    private static final ResourceLocation BG =
            new ResourceLocation("tacz", "textures/gui/gun_smith_table.png");
    private static final int W = 176;
    private static final int H = 166;

    // 口径按钮布局 (顶部一排; 九档)。
    private static final int CAL_BTN_X = 8;
    private static final int CAL_BTN_Y = 16;
    private static final int CAL_BTN_W = 18;
    private static final int CAL_BTN_H = 14;
    private static final int CAL_BTN_GAP = 1;

    // 缓冲进度条布局 (中区)。
    private static final int BAR_X = 8;
    private static final int BAR_Y = 56;
    private static final int BAR_W = 160;
    private static final int BAR_H = 8;

    public MunitionsBenchScreen(MunitionsBenchMenu menu, Inventory inv, Component title) {
        super(menu, inv, title, BG, W, H);
    }

    @Override
    protected void renderExtra(GuiGraphics graphics, int leftPos, int topPos,
                               int mouseX, int mouseY, float partialTick) {
        renderCaliberButtons(graphics, leftPos, topPos);
        renderBufferBar(graphics, leftPos, topPos);
        renderRefineState(graphics, leftPos, topPos);
        renderLockState(graphics, leftPos, topPos);
    }

    private void renderCaliberButtons(GuiGraphics graphics, int leftPos, int topPos) {
        int level = ClientJobState.level(JobId.MUNITIONS);
        int selected = menu.selectedCaliberIndex();
        for (MunitionsCaliber caliber : MunitionsCaliber.values()) {
            int idx = caliber.index();
            int x = leftPos + CAL_BTN_X + idx * (CAL_BTN_W + CAL_BTN_GAP);
            int y = topPos + CAL_BTN_Y;
            boolean unlocked = MunitionsLevels.isCaliberUnlocked(level, caliber);
            int color;
            if (idx == selected) {
                color = 0xFF3FA34D; // 选中: 绿。
            } else if (unlocked) {
                color = 0xFF5A5A5A; // 可选: 灰。
            } else {
                color = 0xFF2A2A2A; // 置灰 (等级未达)。
            }
            graphics.fill(x, y, x + CAL_BTN_W, y + CAL_BTN_H, color);
            graphics.drawString(font, String.valueOf(idx + 1), x + 3, y + 3,
                    unlocked ? 0xFFFFFF : 0x888888, false);
        }
    }

    private void renderBufferBar(GuiGraphics graphics, int leftPos, int topPos) {
        int x0 = leftPos + BAR_X;
        int y0 = topPos + BAR_Y;
        graphics.fill(x0, y0, x0 + BAR_W, y0 + BAR_H, 0xFF101010);
        int cap = menu.bufferCap();
        int filled = cap <= 0 ? 0 : (int) ((long) menu.bufferedRounds() * BAR_W / cap);
        graphics.fill(x0, y0, x0 + Math.min(BAR_W, filled), y0 + BAR_H, 0xFF4D7FA3);
        graphics.drawString(font,
                Component.translatable("message.miningdim.munitions.buffer",
                        menu.bufferedRounds(), cap),
                x0, y0 + BAR_H + 2, 0xFFCCCCCC, false);
    }

    private void renderRefineState(GuiGraphics graphics, int leftPos, int topPos) {
        if (menu.isRefineUnlocked()) {
            graphics.drawString(font,
                    Component.translatable("message.miningdim.munitions.refine_active"),
                    leftPos + 8, topPos + 76, 0xFF3FA34D, false);
        }
    }

    private void renderLockState(GuiGraphics graphics, int leftPos, int topPos) {
        if (menu.isLocked()) {
            graphics.drawString(font,
                    Component.translatable("message.miningdim.munitions.locked_indicator"),
                    leftPos + 8, topPos + 4, 0xFFAA3030, false);
        }
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        int leftPos = (this.width - W) / 2;
        int topPos = (this.height - H) / 2;

        for (MunitionsCaliber caliber : MunitionsCaliber.values()) {
            int idx = caliber.index();
            int x = leftPos + CAL_BTN_X + idx * (CAL_BTN_W + CAL_BTN_GAP);
            int y = topPos + CAL_BTN_Y;
            if (inRect(mouseX, mouseY, x, y, CAL_BTN_W, CAL_BTN_H)) {
                sendButton(idx);
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

    private static boolean inRect(double mx, double my, int x, int y, int w, int h) {
        return mx >= x && mx < x + w && my >= y && my < y + h;
    }
}
