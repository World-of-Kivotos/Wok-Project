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

import java.util.Arrays;

/** 放料、正式掌勺与服务端结算共享原生容器生命周期。 */
public final class FryerScreen extends AbstractContainerScreen<FryerMenu> {
    private static final int W = 360, H = 238;
    private static final long STEP_NS = 50_000_000L;
    private final byte[] pending = new byte[20];
    private ZhangshaoKernel.State state;
    private ZhangshaoKernel.Result result;
    private ItemStack dish = ItemStack.EMPTY;
    private long roundId, lastClock, accumulator, escapeUntil;
    private int countdown, pendingCount, sequence;
    private boolean waiting, gold, bottleRevealed;
    private boolean spaceDown, mouseDown, actionDown, actionMouseDown, holdLatch, actionLatch, escapeDown;
    private boolean ignoreSpaceUntilRelease, ignoreActionUntilRelease;
    private String notice = "";

    public FryerScreen(FryerMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title);
        imageWidth = W;
        imageHeight = H;
        inventoryLabelX = 9;
        inventoryLabelY = 144;
    }

    public void startSession(long id, int seed, int[] params, ItemStack output) {
        ZhangshaoKernel.Params parsed = new ZhangshaoKernel.Params(params);
        if (parsed.station != menu.kind().station()) throw new IllegalArgumentException("烹饪台收到不匹配的参数");
        roundId = id;
        state = ZhangshaoKernel.init(parsed, seed);
        dish = output.copy();
        result = null;
        waiting = false;
        gold = bottleRevealed = false;
        ignoreSpaceUntilRelease = ignoreActionUntilRelease = escapeDown = false;
        countdown = pendingCount = sequence = 0;
        accumulator = escapeUntil = 0;
        lastClock = System.nanoTime();
        notice = "正式掌勺已开局；糊锅或撂勺不退材料和费用。";
        releaseInputs();
    }

    public void revealBottle(long id, boolean isGold) {
        if (state != null && id == roundId) {
            gold = isGold;
            bottleRevealed = true;
        }
    }

    public void serverResult(long id, ZhangshaoKernel.Result outcome, String message) {
        if (state != null && id != roundId) return;
        if (outcome == null) {
            state = null;
        }
        result = outcome;
        waiting = false;
        notice = message;
        releaseInputs();
    }

    private boolean active() {
        return state != null && result == null && !waiting;
    }

    private void releaseInputs() {
        spaceDown = mouseDown = actionDown = actionMouseDown = holdLatch = actionLatch = false;
    }

    private void advanceClock() {
        long now = System.nanoTime();
        long elapsed = lastClock == 0 ? 0 : now - lastClock;
        lastClock = now;
        if (minecraft != null && !minecraft.isWindowActive()) releaseInputs();
        if (!active()) return;
        if (elapsed > 5_000_000_000L) {
            abandon("停顿超过5秒，本锅按家常结算。");
            return;
        }
        accumulator += elapsed;
        while (accumulator >= STEP_NS && active()) {
            accumulator -= STEP_NS;
            if (countdown < 20) {
                countdown++;
                holdLatch = actionLatch = false;
                continue;
            }
            int input = (spaceDown || mouseDown || holdLatch ? 1 : 0) | (actionLatch ? 2 : 0);
            pending[pendingCount++] = (byte) input;
            holdLatch = actionLatch = false;
            boolean bottleWaiting = state.bState == ZhangshaoKernel.B_WAITING;
            ZhangshaoKernel.step(state, input);
            boolean ended = state.end != ZhangshaoKernel.RUNNING;
            boolean bottleAppeared = bottleWaiting && state.bState != ZhangshaoKernel.B_WAITING;
            if (pendingCount == 20 || ended || bottleAppeared) flush(ended);
            if (ended) {
                waiting = true;
                notice = "出锅中，等待服务端结算。";
                releaseInputs();
            }
        }
    }

    private void flush(boolean terminal) {
        if (pendingCount == 0) return;
        FryerNetwork.inputs(menu.pos(), roundId, sequence++, state.tick - pendingCount,
                Arrays.copyOf(pending, pendingCount), ZhangshaoKernel.hash(state), terminal);
        pendingCount = 0;
    }

    private void abandon(String reason) {
        flush(false);
        FryerNetwork.abort(menu.pos(), roundId);
        waiting = true;
        notice = reason;
        releaseInputs();
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
        releaseInputs();
        super.removed();
    }

    @Override
    public void onClose() {
        releaseInputs();
        super.onClose();
    }

    @Override
    public boolean keyPressed(int key, int scanCode, int modifiers) {
        advanceClock();
        if (key == GLFW.GLFW_KEY_ESCAPE && active()) {
            if (escapeDown) return true;
            escapeDown = true;
            ignoreSpaceUntilRelease |= spaceDown;
            ignoreActionUntilRelease |= actionDown;
            releaseInputs();
            long now = System.nanoTime();
            if (now < escapeUntil) abandon("已撂勺，本锅按家常结算。");
            else {
                escapeUntil = now + 1_500_000_000L;
                notice = "再按一次 Esc 撂勺（这锅会变家常菜）";
            }
            return true;
        }
        if (key == GLFW.GLFW_KEY_R || key == GLFW.GLFW_KEY_ENTER) {
            if (!active() && !waiting && !menu.busy()) request(1);
            return true;
        }
        if (active() && key == GLFW.GLFW_KEY_SPACE) {
            if (ignoreSpaceUntilRelease) return true;
            if (!spaceDown && countdown == 20) holdLatch = true;
            spaceDown = true;
            return true;
        }
        if (active() && key == GLFW.GLFW_KEY_F) {
            if (ignoreActionUntilRelease) return true;
            if (!actionDown && countdown == 20) actionLatch = true;
            actionDown = true;
            return true;
        }
        return super.keyPressed(key, scanCode, modifiers);
    }

    @Override
    public boolean keyReleased(int key, int scanCode, int modifiers) {
        advanceClock();
        if (key == GLFW.GLFW_KEY_ESCAPE) { escapeDown = false; return true; }
        if (key == GLFW.GLFW_KEY_SPACE) { spaceDown = ignoreSpaceUntilRelease = false; return true; }
        if (key == GLFW.GLFW_KEY_F) { actionDown = ignoreActionUntilRelease = false; return true; }
        return super.keyReleased(key, scanCode, modifiers);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        advanceClock();
        if (active()) {
            if (button == 0) { mouseDown = holdLatch = true; return true; }
            if (button == 1) {
                if (!actionMouseDown && countdown == 20) actionLatch = true;
                actionMouseDown = true;
                return true;
            }
            return true;
        }
        if (!waiting && !menu.busy() && button == 0) {
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
        if (button == 0) mouseDown = false;
        if (button == 1) actionMouseDown = false;
        if (active() || waiting) return true;
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
            notice = "请放入一份完整配方及所需容器。";
            return;
        }
        if (button == 0) {
            state = null;
            result = null;
            dish = recipe.result();
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
        FryerHud.View view = new FryerHud.View(menu.kind(), mode(), menu.previewRecipe(), menu.busy(),
                menu.getSlot(FryerMenu.OUTPUT).getItem(), menu.value(4), menu.cost(), menu.value(14),
                menu.value(5), menu.value(12), menu.phase(), menu.value(1), menu.value(2), menu.value(6),
                playerInventoryTitle.getString(), inventoryLabelX, inventoryLabelY);
        new FryerHud(font, view, state, result, dish, active(), waiting, bottleRevealed, gold, countdown, notice)
                .renderLabels(g);
    }

    private String mode() {
        if (result != null) return "服务端已结算";
        if (active()) return countdown < 20 ? "掌勺：倒计时" : "掌勺：进行中";
        if (waiting) return "等待服务端结果";
        return switch (menu.phase()) {
            case 1 -> "家常：烹饪中";
            case 2 -> "旁观：他人正在掌勺";
            case 3 -> "出品可收取";
            default -> "放料 / 选择做法";
        };
    }

}
