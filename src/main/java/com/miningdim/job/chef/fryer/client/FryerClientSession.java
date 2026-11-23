package com.miningdim.job.chef.fryer.client;

import com.miningdim.job.chef.zhangshao.ZhangshaoKernel;
import net.minecraft.world.item.ItemStack;
import org.lwjgl.glfw.GLFW;

import java.util.Arrays;

/** 正式掌勺的客户端会话。屏幕提供单调时钟与输入，服务端回执决定最终结果。 */
final class FryerClientSession {
    interface Transport {
        void inputs(long round, int sequence, int firstTick, byte[] inputs, int hash, boolean terminal);
        void abort(long round);
    }

    private static final long STEP_NS = 50_000_000L;
    private final Transport transport;
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

    FryerClientSession(Transport transport) {
        this.transport = transport;
    }

    void startSession(long id, int seed, int[] params, ItemStack output, int station, long now) {
        ZhangshaoKernel.Params parsed = new ZhangshaoKernel.Params(params);
        if (parsed.station != station) throw new IllegalArgumentException("烹饪台收到不匹配的参数");
        roundId = id;
        state = ZhangshaoKernel.init(parsed, seed);
        dish = output.copy();
        result = null;
        waiting = false;
        gold = bottleRevealed = false;
        ignoreSpaceUntilRelease = ignoreActionUntilRelease = escapeDown = false;
        countdown = pendingCount = sequence = 0;
        accumulator = escapeUntil = 0;
        lastClock = now;
        notice = "正式掌勺已开局；糊锅或撂勺不退材料和费用。";
        releaseInputs();
    }

    void revealBottle(long id, boolean isGold) {
        if (state != null && id == roundId) {
            gold = isGold;
            bottleRevealed = true;
        }
    }

    void serverResult(long id, ZhangshaoKernel.Result outcome, String message) {
        if (state != null && id != roundId) return;
        if (outcome == null) {
            state = null;
        }
        result = outcome;
        waiting = false;
        notice = message;
        releaseInputs();
    }

    boolean active() {
        return state != null && result == null && !waiting;
    }

    void releaseInputs() {
        spaceDown = mouseDown = actionDown = actionMouseDown = holdLatch = actionLatch = false;
    }

    void advanceClock(long now, boolean focused) {
        if (!focused) releaseInputs();
        if (!active()) return;
        long elapsed = now - lastClock;
        lastClock = now;
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
        transport.inputs(roundId, sequence++, state.tick - pendingCount,
                Arrays.copyOf(pending, pendingCount), ZhangshaoKernel.hash(state), terminal);
        pendingCount = 0;
    }

    private void abandon(String reason) {
        flush(false);
        transport.abort(roundId);
        waiting = true;
        notice = reason;
        releaseInputs();
    }

    boolean keyPressed(int key, long now) {
        if (key == GLFW.GLFW_KEY_ESCAPE && active()) {
            if (escapeDown) return true;
            escapeDown = true;
            ignoreSpaceUntilRelease |= spaceDown;
            ignoreActionUntilRelease |= actionDown;
            releaseInputs();
            if (now < escapeUntil) abandon("已撂勺，本锅按家常结算。");
            else {
                escapeUntil = now + 1_500_000_000L;
                notice = "再按一次 Esc 撂勺（这锅会变家常菜）";
            }
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
        return false;
    }

    boolean keyReleased(int key) {
        if (key == GLFW.GLFW_KEY_ESCAPE) { escapeDown = false; return true; }
        if (key == GLFW.GLFW_KEY_SPACE) { spaceDown = ignoreSpaceUntilRelease = false; return true; }
        if (key == GLFW.GLFW_KEY_F) { actionDown = ignoreActionUntilRelease = false; return true; }
        return false;
    }

    boolean mouseClicked(int button) {
        if (!active()) return false;
        if (button == 0) mouseDown = holdLatch = true;
        if (button == 1) {
            if (!actionMouseDown && countdown == 20) actionLatch = true;
            actionMouseDown = true;
        }
        return true;
    }

    boolean mouseReleased(int button) {
        if (button == 0) mouseDown = false;
        if (button == 1) actionMouseDown = false;
        return active() || waiting;
    }

    void previewDish(ItemStack output) {
        state = null;
        result = null;
        dish = output;
    }

    void showNotice(String message) {
        notice = message;
    }

    boolean waiting() { return waiting; }
    boolean bottleRevealed() { return bottleRevealed; }
    boolean gold() { return gold; }
    int countdown() { return countdown; }
    String notice() { return notice; }
    ItemStack dish() { return dish; }
    ZhangshaoKernel.State state() { return state; }
    ZhangshaoKernel.Result result() { return result; }

    String mode(int phase) {
        if (result != null) return "服务端已结算";
        if (active()) return countdown < 20 ? "掌勺：倒计时" : "掌勺：进行中";
        if (waiting) return "等待服务端结果";
        return switch (phase) {
            case 1 -> "家常：烹饪中";
            case 2 -> "旁观：他人正在掌勺";
            case 3 -> "出品可收取";
            default -> "放料 / 选择做法";
        };
    }
}
