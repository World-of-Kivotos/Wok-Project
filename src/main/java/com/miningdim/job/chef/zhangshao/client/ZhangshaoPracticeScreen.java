package com.miningdim.job.chef.zhangshao.client;

import com.miningdim.job.chef.zhangshao.ZhangshaoConfig;
import com.miningdim.job.chef.zhangshao.ZhangshaoKernel;
import com.miningdim.job.chef.zhangshao.ZhangshaoPracticeDish;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.logging.Level;
import java.util.logging.Logger;

/** Client-only practice: no container, inventory mutation, XP or network traffic. */
public final class ZhangshaoPracticeScreen extends Screen {
    private static final Logger LOGGER = Logger.getLogger(ZhangshaoPracticeScreen.class.getName());
    private static final int W = 340, H = 240;
    private static final long STEP_NS = 50_000_000L, STALL_NS = 5_000_000_000L;
    private static final String[] STATIONS = {"厨锅", "炸锅", "烤炉", "备餐台"};
    private static final String[] MOTIONS = {"混", "窜", "稳", "沉", "浮"};
    private static final String[] QUALITIES = {"家常", "低品质", "中品质", "高品质", "超凡", "闪耀"};
    private static final int[] QUALITY_COLORS = {0xFFB7B7B7, 0xFFD8D8D8, 0xFF80D478, 0xFF74D6E2, 0xFFD29EFF, 0xFFFFD45D};
    private static final int[] ATLAS_HEIGHT = {64, 160, 256, 64};
    private static final String[] ATLAS_NAMES = {"pot", "fryer", "oven", "prep"};
    private static final int[][] TRACK_RECT = {{0, 0}, {47, 0}, {65, 0}, {0, 0}};
    private static final int[][] BAR_RECT = {{188, 0}, {171, 0}, {21, 241}, {21, 45}};
    private static final ResourceLocation COMMON = texture("common");
    private static final ResourceLocation DISHES = texture("dishes");
    private static final ResourceLocation DISH_TRACK = texture("dishes_track");

    private enum Phase { READY, COUNTDOWN, RUNNING, ENDED }

    private final Random seedRandom = new Random();
    // Bottle type is a separate private draw, never inferred from the public simulation seed.
    private final Random bottleRandom = new Random();
    private final List<Integer> inputs = new ArrayList<>();
    private final List<Hit> hits = new ArrayList<>();
    private int station, star = 1, level = 1, motion, mastery;
    private int seed, gold, countdown;
    private Phase phase = Phase.READY;
    private ZhangshaoKernel.State state, predicted;
    private ZhangshaoKernel.Result result;
    private int predictedInput = -1, predictedTick = -1;
    private boolean spaceDown, mouseDown, actionKeyDown, actionMouseDown, holdLatch, actionLatch;
    private boolean escapeDown;
    private boolean helpOpen;
    private int helpPage;
    private boolean ignoreSpaceUntilRelease, ignoreActionUntilRelease;
    private long lastClock, accumulator, escapeUntil;
    private String notice = "", verification = "";
    private boolean verificationFailed;
    private float scale = 1;
    private int originX, originY;

    public ZhangshaoPracticeScreen(int initialStation) {
        super(Component.literal("掌勺练习"));
        if (initialStation < 0 || initialStation > 3) throw new IllegalArgumentException("未知掌勺台: " + initialStation);
        station = initialStation;
        motion = dish().motion();
        prepare(false);
    }

    private static ResourceLocation texture(String atlas) {
        return new ResourceLocation("miningdim", "zhangshao/atlas/" + atlas + ".png");
    }

    private ZhangshaoPracticeDish dish() {
        return ZhangshaoPracticeDish.forStation(station).get(star - 1);
    }

    @Override
    protected void init() {
        scale = Math.min(1.0F, Math.min((float) width / W, (float) height / H));
        originX = (int) ((width - W * scale) / 2);
        originY = (int) ((height - H * scale) / 2);
        releaseInputs();
        if (lastClock == 0) lastClock = System.nanoTime();
    }

    private void prepare(boolean start) {
        ZhangshaoPracticeDish dish = dish();
        ZhangshaoConfig.Options options = new ZhangshaoConfig.Options();
        options.station = station;
        options.star = star;
        options.level = level;
        options.motion = motion;
        options.mastery = mastery;
        options.flips = dish.flips();
        options.beats = dish.beats();
        options.drink = dish.drink();
        ZhangshaoKernel.Params params = new ZhangshaoKernel.Params(new ZhangshaoConfig().buildParams(options));
        seed = seedRandom.nextInt();
        state = ZhangshaoKernel.init(params, seed);
        gold = state.bKind != ZhangshaoKernel.KIND_NONE && params.goldPm > 0
                && bottleRandom.nextInt(params.bottlePm) < params.goldPm ? 1 : 0;
        phase = start ? Phase.COUNTDOWN : Phase.READY;
        countdown = 0;
        inputs.clear();
        hits.clear();
        result = null;
        predicted = null;
        accumulator = 0;
        lastClock = System.nanoTime();
        escapeUntil = 0;
        verification = "";
        verificationFailed = false;
        notice = "";
        releaseInputs();
    }

    private boolean activeRound() {
        return phase == Phase.COUNTDOWN || phase == Phase.RUNNING;
    }

    private int inputNow() {
        return (spaceDown || mouseDown || holdLatch ? 1 : 0) | (actionLatch ? 2 : 0);
    }

    private void releaseInputs() {
        spaceDown = mouseDown = actionKeyDown = actionMouseDown = holdLatch = actionLatch = false;
    }

    private void advanceClock() {
        long now = System.nanoTime();
        long elapsed = now - lastClock;
        lastClock = now;
        if (minecraft != null && !minecraft.isWindowActive()) {
            releaseInputs();
            escapeDown = false;
            ignoreSpaceUntilRelease = ignoreActionUntilRelease = false;
        }
        if (!activeRound() || helpOpen) return;
        try {
            if (elapsed > STALL_NS) {
                abort("停顿超过5秒，本局已中止；按 R 重开");
                return;
            }
            accumulator += elapsed;
            while (accumulator >= STEP_NS && activeRound()) {
                accumulator -= STEP_NS;
                if (phase == Phase.COUNTDOWN) {
                    holdLatch = actionLatch = false;
                    if (++countdown == 20) phase = Phase.RUNNING;
                } else {
                    int input = inputNow();
                    inputs.add(input);
                    holdLatch = actionLatch = false;
                    ZhangshaoKernel.step(state, input);
                    predicted = null;
                    if (state.end != ZhangshaoKernel.RUNNING) finish();
                }
            }
        } catch (RuntimeException ex) {
            phase = Phase.ENDED;
            releaseInputs();
            verificationFailed = true;
            verification = "运行错误：" + ex.getClass().getSimpleName();
            notice = String.valueOf(ex.getMessage());
            LOGGER.log(Level.SEVERE, "掌勺练习运行失败 station=" + station + " seed=" + seed + " tick=" + state.tick, ex);
        }
    }

    private void abort(String reason) {
        ZhangshaoKernel.abandon(state);
        notice = reason;
        finish();
    }

    private void finish() {
        phase = Phase.ENDED;
        releaseInputs();
        int[] traceInputs = inputs.stream().mapToInt(Integer::intValue).toArray();
        ZhangshaoKernel.Ext ext = new ZhangshaoKernel.Ext(gold, 0,
                ZhangshaoKernel.detectMechanical(traceInputs, traceInputs.length, state.p), 0);
        ZhangshaoKernel.Result live = ZhangshaoKernel.result(state, ext);
        ZhangshaoKernel.State replayState;
        boolean completeTrace;
        if (state.end == ZhangshaoKernel.ABANDON) {
            // Abandonment is an external event, rather than an input byte; reproduce it after the recorded inputs.
            replayState = ZhangshaoKernel.init(state.p, seed);
            for (int input : traceInputs) ZhangshaoKernel.step(replayState, input);
            ZhangshaoKernel.abandon(replayState);
            result = ZhangshaoKernel.result(replayState, ext);
            completeTrace = replayState.tick == traceInputs.length;
        } else {
            ZhangshaoKernel.Trace trace = ZhangshaoKernel.replay(state.p, seed, traceInputs, ext);
            replayState = trace.state;
            result = trace.result;
            completeTrace = trace.consumed == traceInputs.length && trace.padded == 0 && trace.invalid == 0;
        }
        verificationFailed = !completeTrace || ZhangshaoKernel.hash(state) != ZhangshaoKernel.hash(replayState)
                || !comparable(live).equals(comparable(result));
        verification = verificationFailed ? "回放校验失败！详见日志" : "回放一致  " + result.hash;
        if (verificationFailed) {
            LOGGER.severe("掌勺练习回放不一致 seed=" + seed + " inputs=" + traceInputs.length
                    + " live=" + comparable(live) + " replay=" + comparable(result));
        }
    }

    private static Map<String, Object> comparable(ZhangshaoKernel.Result result) {
        Map<String, Object> map = new LinkedHashMap<>(result.toMap());
        map.put("negatives", Arrays.toString(result.negatives));
        return map;
    }

    @Override
    public void tick() {
        advanceClock();
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public void removed() {
        releaseInputs();
        if (activeRound()) ZhangshaoKernel.abandon(state);
        super.removed();
    }

    @Override
    public void onClose() {
        releaseInputs();
        if (activeRound()) ZhangshaoKernel.abandon(state);
        super.onClose();
    }

    @Override
    public boolean keyPressed(int key, int scanCode, int modifiers) {
        advanceClock();
        if (helpOpen) {
            if (key == GLFW.GLFW_KEY_ESCAPE && !escapeDown) {
                escapeDown = true;
                closeHelp();
            } else if (key == GLFW.GLFW_KEY_LEFT && helpPage > 0) {
                helpPage--;
            } else if (key == GLFW.GLFW_KEY_RIGHT && helpPage + 1 < ZhangshaoPracticeHelp.PAGES.size()) {
                helpPage++;
            }
            return true;
        }
        if (key == GLFW.GLFW_KEY_ESCAPE) {
            if (escapeDown) return true;
            escapeDown = true;
            ignoreSpaceUntilRelease |= spaceDown;
            ignoreActionUntilRelease |= actionKeyDown;
            releaseInputs();
            long now = System.nanoTime();
            if (now < escapeUntil) onClose();
            else {
                escapeUntil = now + 1_500_000_000L;
                notice = "再按一次 Esc 退出练习（1.5秒内）";
            }
            return true;
        }
        if (key == GLFW.GLFW_KEY_R) { prepare(true); return true; }
        if (key == GLFW.GLFW_KEY_ENTER && !activeRound()) { prepare(true); return true; }
        if (key == GLFW.GLFW_KEY_SPACE) {
            if (ignoreSpaceUntilRelease) return true;
            if (!spaceDown && phase == Phase.RUNNING) holdLatch = true;
            spaceDown = true;
            return true;
        }
        if (key == GLFW.GLFW_KEY_F) {
            if (ignoreActionUntilRelease) return true;
            if (!actionKeyDown && phase == Phase.RUNNING) actionLatch = true;
            actionKeyDown = true;
            return true;
        }
        return super.keyPressed(key, scanCode, modifiers);
    }

    @Override
    public boolean keyReleased(int key, int scanCode, int modifiers) {
        advanceClock();
        if (key == GLFW.GLFW_KEY_ESCAPE) { escapeDown = false; return true; }
        if (key == GLFW.GLFW_KEY_SPACE) { spaceDown = ignoreSpaceUntilRelease = false; return true; }
        if (key == GLFW.GLFW_KEY_F) { actionKeyDown = ignoreActionUntilRelease = false; return true; }
        return super.keyReleased(key, scanCode, modifiers);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        advanceClock();
        double x = (mouseX - originX) / scale, y = (mouseY - originY) / scale;
        if (button == 0) {
            for (Hit hit : hits) {
                if (x >= hit.x && x < hit.x + hit.w && y >= hit.y && y < hit.y + hit.h) {
                    hit.action.run();
                    return true;
                }
            }
            if (helpOpen) return true;
            if (activeRound()) {
                mouseDown = true;
                if (phase == Phase.RUNNING) holdLatch = true;
                return true;
            }
        }
        if (helpOpen) return true;
        if (button == 1 && activeRound()) {
            if (!actionMouseDown && phase == Phase.RUNNING) actionLatch = true;
            actionMouseDown = true;
            return true;
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        advanceClock();
        if (button == 0) { mouseDown = false; return true; }
        if (button == 1) { actionMouseDown = false; return true; }
        return super.mouseReleased(mouseX, mouseY, button);
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        advanceClock();
        g.fill(0, 0, width, height, 0xDB12151A);
        g.pose().pushPose();
        g.pose().translate(originX, originY, 0);
        g.pose().scale(scale, scale, 1);
        panel(g, 0, 0, W, H, 0xFFC6C6C6);
        hits.clear();
        if (helpOpen) {
            drawHelp(g, mouseX, mouseY);
            g.pose().popPose();
            return;
        }
        text(g, "掌勺练习", 9, 8, 0xFF303030);
        text(g, "练习：不扣料 / 不出菜 / 无经验", 78, 8, 0xFF505050);
        button(g, 287, 5, 44, 16, "帮助", true, false, this::openHelp, mouseX, mouseY);
        boolean editable = !activeRound();
        for (int i = 0; i < 4; i++) {
            final int next = i;
            button(g, 9 + i * 82, 23, 77, 18, STATIONS[i], editable, station == i, () -> {
                station = next;
                motion = dish().motion();
                prepare(false);
            }, mouseX, mouseY);
        }
        button(g, 9, 45, 77, 18, "星级 " + star + "  +", editable, false, () -> {
            star = star % 7 + 1; motion = dish().motion(); prepare(false);
        }, mouseX, mouseY);
        button(g, 91, 45, 77, 18, "等级 " + level + "  +", editable, false, () -> {
            level = level % 10 + 1; prepare(false);
        }, mouseX, mouseY);
        button(g, 173, 45, 77, 18, "性子 " + MOTIONS[motion] + "  +", editable, false, () -> {
            motion = (motion + 1) % 5; prepare(false);
        }, mouseX, mouseY);
        button(g, 255, 45, 77, 18, "手熟 " + mastery + "  +", editable, false, () -> {
            mastery = (mastery + 1) % 6; prepare(false);
        }, mouseX, mouseY);
        g.blit(DISHES, 10, 70, (star - 1) * 16, station * 16, 16, 16, 112, 64);
        text(g, dish().name(), 31, 74, 0xFF303030);
        text(g, "按住上浮，松开下落", 209, 74, 0xFF505050);
        inset(g, 9, 91, 93, 119);
        inset(g, 108, 91, 224, 119);
        drawTrack(g);
        if (phase == Phase.READY) drawReady(g);
        else if (phase == Phase.COUNTDOWN) drawCountdown(g);
        else if (phase == Phase.RUNNING) drawLive(g);
        else drawResult(g);
        button(g, 9, 215, 93, 18, activeRound() ? "R 重新开始" : "开始 / 再来一份", true, false,
                () -> prepare(true), mouseX, mouseY);
        String footer = notice.isEmpty() ? "空格/左键 火候    F/右键 动作    双Esc退出" : notice;
        text(g, fit(footer, 219), 110, 220, verificationFailed ? 0xFFA02020 : 0xFF404040);
        g.pose().popPose();
    }

    private void openHelp() {
        helpOpen = true;
        helpPage = 0;
        ignoreSpaceUntilRelease |= spaceDown;
        ignoreActionUntilRelease |= actionKeyDown;
        releaseInputs();
        hits.clear();
        escapeUntil = 0;
    }

    private void closeHelp() {
        helpOpen = false;
        releaseInputs();
        hits.clear();
        lastClock = System.nanoTime();
    }

    private void drawHelp(GuiGraphics g, int mouseX, int mouseY) {
        ZhangshaoPracticeHelp.Page page = ZhangshaoPracticeHelp.PAGES.get(helpPage);
        text(g, "帮助：" + page.title(), 10, 10, 0xFF303030);
        text(g, (helpPage + 1) + " / " + ZhangshaoPracticeHelp.PAGES.size(), 292, 10, 0xFF505050);
        inset(g, 9, 29, 322, 169);
        int y = 37;
        for (String paragraph : page.paragraphs()) {
            for (var line : font.split(Component.literal(paragraph), 304)) {
                g.drawString(font, line, 18, y, 0xFFEEEEEE, false);
                y += 11;
            }
            y += 4;
        }
        text(g, "方向键翻页 / Esc 返回；游戏世界继续运行", 10, 203, 0xFF404040);
        button(g, 9, 215, 77, 18, "上一页", helpPage > 0, false,
                () -> { if (helpPage > 0) helpPage--; }, mouseX, mouseY);
        button(g, 91, 215, 77, 18, "下一页", helpPage + 1 < ZhangshaoPracticeHelp.PAGES.size(), false,
                () -> { if (helpPage + 1 < ZhangshaoPracticeHelp.PAGES.size()) helpPage++; }, mouseX, mouseY);
        button(g, 173, 215, 158, 18, "返回练习", true, false, this::closeHelp, mouseX, mouseY);
    }

    private void drawTrack(GuiGraphics g) {
        ZhangshaoKernel.State next = state;
        double alpha = 0;
        if (phase == Phase.RUNNING) {
            int input = inputNow();
            if (predicted == null || predictedTick != state.tick || predictedInput != input) {
                predicted = state.copy();
                ZhangshaoKernel.step(predicted, input);
                predictedTick = state.tick;
                predictedInput = input;
            }
            next = predicted;
            alpha = (double) accumulator / STEP_NS;
        }
        int[] track = TRACK_RECT[station], bar = BAR_RECT[station];
        ResourceLocation atlas = texture(ATLAS_NAMES[station]);
        slice(g, atlas, 31, 95, 26, 110, track[0], track[1], 26, 44, 3, 6, 256, ATLAS_HEIGHT[station]);
        double yb = lerp(state.yb, next.yb, alpha), yt = lerp(state.yt, next.yt, alpha);
        int barHeight = (int) Math.round(state.p.H * 98.0 / state.p.track);
        int barY = trackY(yb + state.p.H);
        int barX = state.inPrev != 0 ? bar[0] : bar[0] - 21;
        slice(g, atlas, 34, barY, 20, barHeight, barX, bar[1], 20, 8, 2, 2, 256, ATLAS_HEIGHT[station]);
        if (level <= 3 && state.tgt != ZhangshaoKernel.NONE && Math.abs(state.tgt - state.yt) > state.p.H / 2) {
            g.blit(COMMON, 35, trackY(state.tgt) - 9, 131, 181, 18, 18, 256, 240);
        }
        g.blit(DISH_TRACK, 35, trackY(yt) - 9, (star - 1) * 18, station * 18, 18, 18, 126, 72);
        if (state.bState == ZhangshaoKernel.B_PRESENT) {
            int by = trackY(state.bPos) - 8;
            // Gold is revealed only while the live perfect condition survives, matching the prototype.
            g.blit(COMMON, 53, by, gold != 0 && livePerfect() ? 167 : 150, 181, 16, 16, 256, 240);
            g.fill(55, by + 16, 69, by + 19, 0xFF262626);
            g.fill(55, by + 16, 55 + state.K * 14 / 1_000_000, by + 19, 0xFFFFD354);
        }
        g.fill(79, 98, 89, 203, 0xFF202020);
        int fill = state.P * 101 / state.p.progressMax;
        g.fill(81, 201 - fill, 87, 201, state.P >= 750_000 ? 0xFFFFBB58 : 0xFF76BC63);
        int marks = station == ZhangshaoKernel.OVEN ? state.p.F : station == ZhangshaoKernel.PREP ? state.p.segs : 0;
        for (int i = 1; i <= marks; i++) {
            int y = 201 - ZhangshaoKernel.markAt(state.p, i, marks) * 101 / state.p.progressMax;
            g.fill(77, y, 91, y + 1, 0xFFEEE5C6);
        }
        text(g, "熟", 79, 94, 0xFFEFEFEF);
        if (phase == Phase.RUNNING && state.inPrev == 0) text(g, state.yt > state.yb + state.p.H ? "上" : "下", 15, trackY(yt) - 4, 0xFFFFD475);
    }

    private static double lerp(int from, int to, double alpha) { return from + (to - from) * alpha; }
    private int trackY(double value) { return 199 - (int) Math.round(value * 98 / state.p.track); }

    private void drawReady(GuiGraphics g) {
        text(g, "用火候条框住移动的食材", 117, 100, 0xFFF0E5CB);
        text(g, "空格 / 左键：按住上浮", 117, 116, 0xFFE2E2E2);
        text(g, "松开：下落；熟度满则出锅", 117, 130, 0xFFE2E2E2);
        text(g, stationHelp(), 117, 148, 0xFFFFD780);
        text(g, "瓶子：用火候条框住，蓄满收取", 117, 164, 0xFFE2E2E2);
        text(g, "L1-3 显示落点虚影", 117, 180, 0xFFAFC5DA);
        text(g, "上方选项点击循环；回车开始", 117, 195, 0xFFB7B7B7);
    }

    private String stationHelp() {
        return switch (station) {
            case 0 -> "厨锅：持续框住，积起旺火";
            case 1 -> "炸锅：油温过高前，F 降温";
            case 2 -> "烤炉：看到「翻面」再按 F";
            case 3 -> "备餐：摆料时，节拍到线按 F";
            default -> throw new IllegalStateException("station " + station);
        };
    }

    private void drawCountdown(GuiGraphics g) {
        text(g, "准备下锅", 180, 103, 0xFFF6E8CA);
        int remaining = 20 - countdown;
        String number = remaining > 13 ? "3" : remaining > 6 ? "2" : "1";
        g.pose().pushPose();
        g.pose().translate(207, 124, 0);
        g.pose().scale(3, 3, 1);
        text(g, number, 0, 0, 0xFFFFD66F);
        g.pose().popPose();
        text(g, "倒数1秒；此时不记操作", 144, 172, 0xFFDEDEDE);
        text(g, stationHelp(), 117, 194, 0xFFB9CBE0);
    }

    private boolean livePerfect() {
        if (state.perfect == 0) return false;
        return switch (station) {
            case 0 -> true;
            case 1 -> state.bursts == 0 && state.hot + state.cold <= state.p.cleanTicks;
            case 2 -> state.flipAllGood != 0;
            case 3 -> state.miss == 0 && state.mash == 0;
            default -> throw new IllegalStateException("station " + station);
        };
    }

    private void drawLive(GuiGraphics g) {
        ZhangshaoKernel.Result live = ZhangshaoKernel.result(state, null);
        text(g, "当前 " + QUALITIES[live.quality + 1] + "  " + live.score + "分", 117, 99, QUALITY_COLORS[live.quality + 1]);
        g.blit(COMMON, 265, 97, livePerfect() ? 59 : 0, 202, 58, livePerfect() ? 13 : 14, 256, 240);
        String time = state.inSeg != 0 ? "摆料中（时限暂停）" : "时间 " + seconds(state.pt) + " / " + seconds(state.p.CAP) + "秒";
        text(g, time, 117, 115, 0xFFE2E2E2);
        g.fill(117, 128, 322, 131, 0xFF202020);
        g.fill(117, 128, 117 + (state.p.CAP - state.pt) * 205 / state.p.CAP, 131,
                state.p.CAP - state.pt <= 60 ? 0xFFFF775F : 0xFF7EBCD6);
        text(g, "准确 " + percent(live.accuracy) + "  欠火 " + percent(live.underPm) + "  " + (station == 3 ? "多盐 " : "过火 ") + percent(live.overPm), 117, 136, 0xFFD2D2D2);
        if (station == ZhangshaoKernel.PREP && state.inSeg != 0) drawBeats(g);
        else drawStation(g);
        String bottle = switch (state.bState) {
            case ZhangshaoKernel.B_WAITING -> "调料瓶尚未出现";
            case ZhangshaoKernel.B_PRESENT -> "调料瓶收取 " + percent(state.K / 1000);
            case ZhangshaoKernel.B_CAUGHT -> "调料瓶已收取";
            case ZhangshaoKernel.B_NONE -> "本局无调料瓶";
            default -> throw new IllegalStateException("bState " + state.bState);
        };
        text(g, bottle, 117, 195, 0xFFD6C393);
    }

    private void drawStation(GuiGraphics g) {
        if (station == 0) {
            text(g, "火力 " + state.h + "/1000", 117, 153, 0xFFFFCF79);
            meter(g, 117, 168, 205, state.h, state.p.heatMax, 0xFFED9C4F);
            text(g, "框住食材积火，离条会快速熄火", 117, 181, 0xFFC8C8C8);
        } else if (station == 1) {
            text(g, "油温 " + state.T + "   " + (state.cd > 0 ? "冷却 " + seconds(state.cd) + "s" : "F 可降温"), 117, 153, 0xFFFFCF79);
            meter(g, 117, 168, 205, state.T, state.p.oilBurst, 0xFFDFC762);
            g.fill(117 + 205 * state.p.oilCold / 1000, 167, 118 + 205 * state.p.oilCold / 1000, 177, 0xFF96CCFF);
            g.fill(117 + 205 * state.p.oilHot / 1000, 167, 118 + 205 * state.p.oilHot / 1000, 177, 0xFFFF6B50);
            text(g, "爆油 " + state.bursts + "次；适宜油温 300-749", 117, 181, 0xFFC8C8C8);
        } else if (station == 2) {
            text(g, state.prompt >= 0 ? "翻面！按 F / 右键" : "等待翻面提示", 117, 155, state.prompt >= 0 ? 0xFFFFD268 : 0xFFD8D8D8);
            text(g, "已翻 " + state.flipIdx + "/" + state.p.F + "  正好 " + state.nGood + "次", 117, 171, 0xFFE4E4E4);
            text(g, "早 " + state.nEarly + "  晚 " + state.nLate + "  焦 " + state.nBurnt + "  自动 " + state.nAuto, 117, 183, 0xFFC8C8C8);
        } else {
            text(g, "先框住食材，熟度到标记后摆料", 117, 155, 0xFFD8D8D8);
            text(g, "摆料阶段跟着节拍按 F / 右键", 117, 171, 0xFFFFCF79);
            text(g, "漏拍 " + state.miss + "  乱按 " + state.mash, 117, 183, 0xFFC8C8C8);
        }
    }

    private void drawBeats(GuiGraphics g) {
        text(g, "节拍到白线按 F / 右键", 117, 152, 0xFFFFD268);
        g.fill(117, 166, 323, 183, 0xFF282828);
        int line = 151;
        g.fill(line, 166, line + 2, 183, 0xFFF0F0F0);
        int[] beats = state.segBeats[state.segIdx];
        for (int i = state.bi; i < beats.length; i++) {
            int x = line + (beats[i] - state.segT) * 5;
            if (x >= 120 && x <= 318) g.fill(x - 3, 171, x + 4, 178, i == state.bi ? 0xFFFFD268 : 0xFF88ACD7);
        }
        text(g, "正好 " + state.perfB + "  可以 " + state.okB + "  漏 " + state.miss + "  乱 " + state.mash, 117, 184, 0xFFD8D8D8);
    }

    private void drawResult(GuiGraphics g) {
        if (result == null) {
            text(g, "练习运行失败", 117, 101, 0xFFFF7B72);
            text(g, fit(verification, 205), 117, 123, 0xFFFF7B72);
            text(g, "错误已写入日志；R 可重新开始", 117, 150, 0xFFEEEEEE);
            return;
        }
        String end = switch (result.end) {
            case ZhangshaoKernel.DONE -> "出锅";
            case ZhangshaoKernel.BURNT -> "糊锅";
            case ZhangshaoKernel.TIMEOUT -> "到点出锅";
            case ZhangshaoKernel.ABANDON -> "已中止";
            default -> throw new IllegalStateException("end " + result.end);
        };
        text(g, end + " · " + QUALITIES[result.quality + 1] + "  " + result.score + "分", 117, 99, QUALITY_COLORS[result.quality + 1]);
        text(g, "准确 " + percent(result.accuracy), 117, 116, 0xFFE5E5E5);
        text(g, "耗时 " + seconds(result.ticks) + "秒", 229, 116, 0xFFE5E5E5);
        text(g, "欠火 " + percent(result.underPm), 117, 130, 0xFFD1D1D1);
        text(g, (station == 3 ? "多盐 " : "过火 ") + percent(result.overPm), 229, 130, 0xFFD1D1D1);
        text(g, "Perfect " + (result.perfect != 0 ? "达成" : "未达成"), 117, 144, result.perfect != 0 ? 0xFFFFD268 : 0xFFC0C0C0);
        text(g, "扣分 " + result.pen, 229, 144, 0xFFD1D1D1);
        String bottle = result.bottle != 0 ? (result.gold != 0 ? "金瓶收取" : "普通瓶收取") : "未收取";
        text(g, "调料瓶：" + bottle, 117, 158, 0xFFE6D3A2);
        String mistakes = switch (station) {
            case 0 -> "平均火力 " + result.avgHeat + " / 1000";
            case 1 -> "爆油 " + result.bursts + "  热 " + percent(result.hotPm) + "  冷 " + percent(result.coldPm);
            case 2 -> "早 " + result.flipEarly + " 晚 " + result.flipLate + " 焦 " + result.flipBurnt + " 自动 " + result.flipAuto;
            case 3 -> "正好 " + result.beatPerfect + " 可以 " + result.beatOk + " 漏 " + result.beatMiss + " 乱 " + result.beatMash;
            default -> throw new IllegalStateException("station " + station);
        };
        text(g, mistakes, 117, 172, 0xFFD1D1D1);
        text(g, fit(verification, 205), 117, 193, verificationFailed ? 0xFFFF7469 : 0xFF91CD8E);
    }

    private static String percent(int pm) { return String.format(Locale.ROOT, "%.1f%%", pm / 10.0); }
    private static String seconds(int ticks) { return String.format(Locale.ROOT, "%.1f", ticks / 20.0); }
    private String fit(String value, int width) { return font.plainSubstrByWidth(value, width); }
    private void text(GuiGraphics g, String text, int x, int y, int color) { g.drawString(font, text, x, y, color, false); }

    private static void meter(GuiGraphics g, int x, int y, int w, int value, int max, int color) {
        g.fill(x, y, x + w, y + 8, 0xFF202020);
        g.fill(x + 1, y + 1, x + 1 + value * (w - 2) / max, y + 7, color);
    }

    private static void panel(GuiGraphics g, int x, int y, int w, int h, int color) {
        g.fill(x, y, x + w, y + h, 0xFF101010);
        g.fill(x + 1, y + 1, x + w - 1, y + h - 1, 0xFF555555);
        g.fill(x + 1, y + 1, x + w - 2, y + h - 2, 0xFFFFFFFF);
        g.fill(x + 3, y + 3, x + w - 3, y + h - 3, color);
    }

    private static void inset(GuiGraphics g, int x, int y, int w, int h) {
        g.fill(x, y, x + w, y + h, 0xFFFFFFFF);
        g.fill(x, y, x + w - 1, y + h - 1, 0xFF555555);
        g.fill(x + 1, y + 1, x + w - 1, y + h - 1, 0xFF373737);
    }

    private void button(GuiGraphics g, int x, int y, int w, int h, String label, boolean enabled,
                        boolean selected, Runnable action, int mouseX, int mouseY) {
        double mx = (mouseX - originX) / scale, my = (mouseY - originY) / scale;
        boolean hovered = enabled && mx >= x && mx < x + w && my >= y && my < y + h;
        panel(g, x, y, w, h, !enabled ? 0xFF999999 : selected ? 0xFF8BAF80 : hovered ? 0xFFDBD6BA : 0xFFC6C6C6);
        text(g, label, x + (w - font.width(label)) / 2, y + 5, enabled ? 0xFF303030 : 0xFF656565);
        if (enabled) hits.add(new Hit(x, y, w, h, action));
    }

    /** Keep the pixel bevels fixed while stretching only the plain interior of an atlas sprite. */
    private static void slice(GuiGraphics g, ResourceLocation atlas, int x, int y, int w, int h,
                              int u, int v, int sw, int sh, int borderX, int borderY, int tw, int th) {
        int[] dx = {x, x + borderX, x + w - borderX};
        int[] dy = {y, y + borderY, y + h - borderY};
        int[] dw = {borderX, w - 2 * borderX, borderX};
        int[] dh = {borderY, h - 2 * borderY, borderY};
        int[] su = {u, u + borderX, u + sw - borderX};
        int[] sv = {v, v + borderY, v + sh - borderY};
        int[] uw = {borderX, sw - 2 * borderX, borderX};
        int[] vh = {borderY, sh - 2 * borderY, borderY};
        for (int row = 0; row < 3; row++) for (int col = 0; col < 3; col++) {
            g.blit(atlas, dx[col], dy[row], dw[col], dh[row], su[col], sv[row], uw[col], vh[row], tw, th);
        }
    }

    private record Hit(int x, int y, int w, int h, Runnable action) {}
}
