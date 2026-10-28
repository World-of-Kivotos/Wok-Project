package com.miningdim.job.chef.pot.client;

import com.miningdim.job.chef.fryer.FryerNetwork;
import com.miningdim.job.chef.pot.PotMenuStatus;
import com.miningdim.job.chef.zhangshao.ZhangshaoKernel;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.ItemStack;
import org.lwjgl.glfw.GLFW;
import vectorwing.farmersdelight.common.block.entity.container.CookingPotMenu;

import java.util.Arrays;
import java.util.Locale;

/** The FD menu stays open for the entire production round. */
public final class PotClient {
    private static final long STEP_NS = 50_000_000L;
    private static final String[] QUALITIES = {"家常", "低品质", "中品质", "高品质", "超凡", "闪耀"};
    private static PotClient current;
    private final Screen screen;
    private final BlockPos pos;
    private final byte[] pending = new byte[20];
    private final ItemStack dish;
    private final long roundId;
    private final ZhangshaoKernel.State state;
    private long clock, accumulator, escapeUntil;
    private int countdown, count, sequence;
    private boolean space, mouse, latch, escape, ignoreSpace, waiting, finished, gold, revealed;
    private ZhangshaoKernel.Result result;
    private String notice = "正式掌勺：糊锅或撂勺按家常结算。";

    private PotClient(Screen screen, BlockPos pos, long roundId, int seed, int[] params, ItemStack dish) {
        ZhangshaoKernel.Params parsed = new ZhangshaoKernel.Params(params);
        if (parsed.station != ZhangshaoKernel.POT) throw new IllegalArgumentException("厨锅收到不匹配的参数");
        this.screen = screen;
        this.pos = pos.immutable();
        this.roundId = roundId;
        this.state = ZhangshaoKernel.init(parsed, seed);
        this.dish = dish.copy();
        clock = System.nanoTime();
    }

    public static boolean isPot(Screen screen) {
        return screen != null && screen.getClass().getName().equals("vectorwing.farmersdelight.client.gui.CookingPotScreen");
    }

    private static BlockPos position(Screen screen) {
        return ((CookingPotMenu) ((AbstractContainerScreen<?>) screen).getMenu()).blockEntity.getBlockPos();
    }

    public static void start(Screen screen) { FryerNetwork.startPot(position(screen)); }

    public static void session(BlockPos pos, long roundId, int seed, int[] params, ItemStack dish) {
        Screen screen = Minecraft.getInstance().screen;
        if (isPot(screen) && position(screen).equals(pos)) current = new PotClient(screen, pos, roundId, seed, params, dish);
    }

    public static void bottle(long roundId, boolean gold) {
        if (current != null && current.roundId == roundId) { current.gold = gold; current.revealed = true; }
    }

    public static void result(long roundId, ZhangshaoKernel.Result result, String message) {
        if (current == null || current.roundId != roundId) return;
        current.result = result;
        current.finished = true;
        current.waiting = false;
        current.notice = message;
        current.release();
    }

    private boolean active() { return !finished && !waiting; }
    private void release() { space = mouse = latch = false; }
    private void flush(boolean terminal) {
        if (count == 0) return;
        FryerNetwork.inputs(pos, roundId, sequence++, state.tick - count, Arrays.copyOf(pending, count),
                ZhangshaoKernel.hash(state), terminal);
        count = 0;
    }
    private void abandon(String reason) {
        flush(false);
        FryerNetwork.abort(pos, roundId);
        waiting = true;
        notice = reason;
        release();
    }
    private void advance() {
        long now = System.nanoTime();
        long elapsed = now - clock;
        clock = now;
        if (!Minecraft.getInstance().isWindowActive()) release();
        if (!active()) return;
        if (elapsed > 5_000_000_000L) { abandon("停顿超过5秒，本锅按家常结算。"); return; }
        accumulator += elapsed;
        while (accumulator >= STEP_NS && active()) {
            accumulator -= STEP_NS;
            if (countdown < 20) { countdown++; latch = false; continue; }
            int input = space || mouse || latch ? 1 : 0;
            pending[count++] = (byte) input;
            latch = false;
            boolean bottleWaiting = state.bState == ZhangshaoKernel.B_WAITING;
            ZhangshaoKernel.step(state, input);
            boolean ended = state.end != ZhangshaoKernel.RUNNING;
            if (count == 20 || ended || (bottleWaiting && state.bState != ZhangshaoKernel.B_WAITING)) flush(ended);
            if (ended) { waiting = true; notice = "出锅中，等待服务端结算。"; release(); }
        }
    }
    public static boolean render(Screen screen, GuiGraphics g) {
        PotClient s = current;
        if (s == null || s.screen != screen) {
            renderStatus(screen, g);
            return false;
        }
        s.advance();
        var font = Minecraft.getInstance().font;
        int x = (screen.width - 300) / 2, y = (screen.height - 176) / 2;
        g.fill(x, y, x + 300, y + 176, 0xFF101820);
        g.fill(x + 3, y + 3, x + 297, y + 173, 0xFF243340);
        g.drawString(font, "厨锅正式掌勺：" + s.dish.getHoverName().getString(), x + 10, y + 9, 0xFFF2E2B8, false);
        if (s.finished && s.result == null) {
            g.drawString(font, "本锅按家常结算", x + 100, y + 40, 0xFFFFD780, false);
            g.drawString(font, "按 Esc 返回厨锅", x + 100, y + 78, 0xFFCCD6DD, false);
        } else if (s.result != null) {
            g.drawString(font, QUALITIES[s.result.quality + 1] + "  " + s.result.score + "分", x + 100, y + 40, 0xFFFFD780, false);
            g.drawString(font, "平均火力 " + s.result.avgHeat + "/1000", x + 100, y + 56, 0xFFFFFFFF, false);
            g.drawString(font, "按 Esc 返回厨锅", x + 100, y + 78, 0xFFCCD6DD, false);
        } else {
            ZhangshaoKernel.Result live = ZhangshaoKernel.result(s.state, null);
            g.drawString(font, s.waiting ? "等待服务端结算" : s.countdown < 20 ? "准备下锅 " + (3 - s.countdown * 3 / 20) : "按住空格或左键控火", x + 100, y + 32, 0xFFFFFFFF, false);
            g.drawString(font, "熟度 " + percent(s.state.P / 1000) + "  准确 " + percent(live.accuracy), x + 100, y + 50, 0xFFFFFFFF, false);
            g.drawString(font, "火力 " + s.state.h + "/1000  时间 " + String.format(Locale.ROOT, "%.1f", s.state.pt / 20.0) + "秒", x + 100, y + 66, 0xFFFFD780, false);
            g.drawString(font, "当前 " + QUALITIES[live.quality + 1] + (s.state.perfect != 0 ? "  Perfect" : ""), x + 100, y + 82, 0xFFFFFFFF, false);
            String bottle = s.state.bState == ZhangshaoKernel.B_PRESENT ? "调料瓶收取 " + percent(s.state.K / 1000)
                    : s.state.bState == ZhangshaoKernel.B_CAUGHT ? (s.revealed && s.gold ? "金瓶已收取" : "调料瓶已收取") : "框住食材积起火力";
            g.drawString(font, bottle, x + 100, y + 98, 0xFFCCD6DD, false);
            CookingPotMenu menu = (CookingPotMenu) ((AbstractContainerScreen<?>) screen).getMenu();
            PotMenuStatus status = (PotMenuStatus) menu;
            g.drawString(font, "费用 " + status.wok$cost() + "  热源 " + (menu.isHeated() ? "有" : "无"), x + 100, y + 114, 0xFFCCD6DD, false);
            int trackX = x + 36, trackY = y + 31, height = 105;
            g.fill(trackX, trackY, trackX + 25, trackY + height, 0xFF090F14);
            int barBottom = trackY + height - s.state.yb * height / s.state.p.track;
            int barTop = trackY + height - (s.state.yb + s.state.p.H) * height / s.state.p.track;
            g.fill(trackX + 3, barTop, trackX + 22, barBottom, 0xFF75AEDA);
            int targetY = trackY + height - s.state.yt * height / s.state.p.track;
            g.fill(trackX, targetY - 2, trackX + 25, targetY + 3, 0xFFFFD354);
            if (s.state.bState == ZhangshaoKernel.B_PRESENT) {
                int bottleY = trackY + height - s.state.bPos * height / s.state.p.track;
                g.fill(trackX + 26, bottleY - 3, trackX + 33, bottleY + 4, s.revealed && s.gold ? 0xFFFFD354 : 0xFFE8E8E8);
            }
        }
        g.drawString(font, font.plainSubstrByWidth(s.notice, 280), x + 10, y + 151, 0xFFFFD98B, false);
        return true;
    }
    private static void renderStatus(Screen screen, GuiGraphics g) {
        AbstractContainerScreen<?> container = (AbstractContainerScreen<?>) screen;
        CookingPotMenu menu = (CookingPotMenu) container.getMenu();
        PotMenuStatus status = (PotMenuStatus) menu;
        var font = Minecraft.getInstance().font;
        int x = container.getGuiLeft(), y = Math.max(0, container.getGuiTop() - 22);
        String line = "掌勺费用 " + status.wok$cost() + " 信用点  热源 " + (menu.isHeated() ? "有" : "无");
        String state = status.wok$busy() ? "旁观：他人正在掌勺，熟度 " + percent(status.wok$progress()) : "厨锅空闲：家常由农夫乐事自动烹饪";
        int width = Math.max(font.width(line), font.width(state)) + 8;
        g.fill(x, y, x + width, y + 22, 0xE6101820);
        g.drawString(font, line, x + 4, y + 2, 0xFFFFD98B, false);
        g.drawString(font, state, x + 4, y + 12, 0xFFFFFFFF, false);
    }
    private static String percent(int value) { return String.format(Locale.ROOT, "%.1f%%", value / 10.0); }
    public static boolean key(Screen screen, int key, boolean down) {
        PotClient s = current;
        if (s == null || s.screen != screen) return false;
        s.advance();
        if (!s.active()) {
            if (key == GLFW.GLFW_KEY_ESCAPE && down && s.finished) { current = null; return true; }
            return true;
        }
        if (key == GLFW.GLFW_KEY_ESCAPE) {
            if (!down) { s.escape = false; return true; }
            if (s.escape) return true;
            s.escape = true; s.ignoreSpace |= s.space; s.release();
            long now = System.nanoTime();
            if (now < s.escapeUntil) s.abandon("已撂勺，本锅按家常结算。");
            else { s.escapeUntil = now + 1_500_000_000L; s.notice = "再按一次 Esc 撂勺，本锅按家常结算。"; }
            return true;
        }
        if (key == GLFW.GLFW_KEY_SPACE) {
            if (!down) s.ignoreSpace = false;
            if (s.ignoreSpace) return true;
            if (down && !s.space && s.countdown == 20) s.latch = true;
            s.space = down;
        }
        return true;
    }
    public static boolean mouse(Screen screen, int button, boolean down) {
        PotClient s = current;
        if (s == null || s.screen != screen) return false;
        s.advance();
        if (s.active() && button == 0) { s.mouse = down; if (down && s.countdown == 20) s.latch = true; }
        return true;
    }
    public static void closed(Screen screen) { if (current != null && current.screen == screen) current = null; }
}
