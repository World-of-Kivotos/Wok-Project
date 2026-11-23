package com.miningdim.job.chef.fryer.client;

import com.miningdim.job.chef.fryer.FryerMenu;
import com.miningdim.job.chef.fryer.FryerNetwork;
import com.miningdim.job.chef.fryer.FryerRecipe;
import com.miningdim.job.chef.zhangshao.ZhangshaoKernel;
import com.miningdim.job.chef.zhangshao.client.ZhangshaoPracticeScreen;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import org.lwjgl.glfw.GLFW;

/** 放料、正式掌勺与服务端结算共享原生容器生命周期。 */
public final class FryerScreen extends AbstractContainerScreen<FryerMenu> {
    private static final int W = 360, H = 238;
    private final FryerClientSession session = new FryerClientSession(new FryerClientSession.Transport() {
        @Override
        public void inputs(long round, int sequence, int firstTick, byte[] inputs, int hash, boolean terminal) {
            FryerNetwork.inputs(menu.pos(), round, sequence, firstTick, inputs, hash, terminal);
        }

        @Override
        public void abort(long round) {
            FryerNetwork.abort(menu.pos(), round);
        }
    });

    public FryerScreen(FryerMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title);
        imageWidth = W;
        imageHeight = H;
        inventoryLabelX = 9;
        inventoryLabelY = 144;
    }

    public void startSession(long id, int seed, int[] params, ItemStack output) {
        session.startSession(id, seed, params, output, menu.kind().station(), System.nanoTime());
    }

    public void revealBottle(long id, boolean isGold) {
        session.revealBottle(id, isGold);
    }

    public void serverResult(long id, ZhangshaoKernel.Result outcome, String message) {
        session.serverResult(id, outcome, message);
    }

    private void advanceClock() {
        session.advanceClock(System.nanoTime(), minecraft == null || minecraft.isWindowActive());
    }

    @Override
    protected void containerTick() {
        super.containerTick();
        advanceClock();
    }

    @Override
    public boolean isPauseScreen() { return false; }

    @Override
    public void removed() {
        session.releaseInputs();
        super.removed();
    }

    @Override
    public void onClose() {
        session.releaseInputs();
        super.onClose();
    }

    @Override
    public boolean keyPressed(int key, int scanCode, int modifiers) {
        advanceClock();
        if (session.keyPressed(key, System.nanoTime())) return true;
        if (key == GLFW.GLFW_KEY_R || key == GLFW.GLFW_KEY_ENTER) {
            if (!session.active() && !session.waiting() && !menu.busy()) request(1);
            return true;
        }
        return super.keyPressed(key, scanCode, modifiers);
    }

    @Override
    public boolean keyReleased(int key, int scanCode, int modifiers) {
        advanceClock();
        if (session.keyReleased(key)) return true;
        return super.keyReleased(key, scanCode, modifiers);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        advanceClock();
        if (session.mouseClicked(button)) return true;
        if (!session.waiting() && !menu.busy() && button == 0) {
            for (int id = 0; id < 3; id++) {
                if (inside(mouseX, mouseY, 12 + id * 78, 73, 72, 18)) {
                    request(id);
                    return true;
                }
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        advanceClock();
        if (session.mouseReleased(button)) return true;
        return super.mouseReleased(mouseX, mouseY, button);
    }

    private void request(int button) {
        if (button == 2) {
            minecraft.player.closeContainer();
            minecraft.setScreen(new ZhangshaoPracticeScreen(menu.kind().station()));
            return;
        }
        FryerRecipe recipe = menu.previewRecipe();
        if (recipe == null) {
            session.showNotice("请放入一份完整配方及所需容器。");
            return;
        }
        if (button == 0) {
            session.previewDish(recipe.result());
        }
        minecraft.gameMode.handleInventoryButtonClick(menu.containerId, button);
    }

    private boolean inside(double x, double y, int rx, int ry, int w, int h) {
        return x >= leftPos + rx && x < leftPos + rx + w && y >= topPos + ry && y < topPos + ry + h;
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        advanceClock();
        renderBackground(g);
        super.render(g, mouseX, mouseY, partialTick);
        renderTooltip(g, mouseX, mouseY);
    }

    @Override
    protected void renderBg(GuiGraphics g, float partialTick, int mouseX, int mouseY) {
        FryerHud.renderBackground(g, leftPos, topPos, menu.slots);
    }

    @Override
    protected void renderLabels(GuiGraphics g, int mouseX, int mouseY) {
        FryerHud.View view = new FryerHud.View(menu.kind(), session.mode(menu.phase()), menu.previewRecipe(), menu.busy(),
                menu.getSlot(FryerMenu.OUTPUT).getItem(), menu.value(4), menu.cost(), menu.value(14),
                menu.value(5), menu.value(12), menu.phase(), menu.value(1), menu.value(2), menu.value(6),
                playerInventoryTitle.getString(), inventoryLabelX, inventoryLabelY);
        new FryerHud(font, view, session.state(), session.result(), session.dish(), session.active(), session.waiting(),
                session.bottleRevealed(), session.gold(), session.countdown(), session.notice())
                .renderLabels(g);
    }
}
