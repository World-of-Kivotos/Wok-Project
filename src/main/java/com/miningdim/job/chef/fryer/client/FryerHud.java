package com.miningdim.job.chef.fryer.client;

import com.miningdim.job.chef.fryer.FryerRecipe;
import com.miningdim.job.chef.station.CookingStationBlock;
import com.miningdim.job.chef.zhangshao.ZhangshaoKernel;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/** 炸锅只读绘制；输入、时钟和服务端状态由屏幕管理。 */
public final class FryerHud {
    private static final int W = 360, H = 238;
    private static final String[] QUALITIES = {"家常", "低品质", "中品质", "高品质", "超凡", "闪耀"};
    private static final String[] MOTIONS = {"混", "窜", "稳", "沉", "浮"};
    private static final int[] RECOMMENDED = {1, 2, 3, 5, 6, 8, 9};
    public record View(CookingStationBlock.Kind kind, String mode, FryerRecipe recipe, boolean busy, ItemStack output, int star,
                       int cost, int level, int mastery, int progress, int phase, int cooked,
                       int remaining, int best, String inventoryTitle, int inventoryLabelX, int inventoryLabelY) {}
    private final Font font;
    private final View view;
    private final ZhangshaoKernel.State state;
    private final ZhangshaoKernel.Result result;
    private final ItemStack dish;
    private final boolean active, waiting, bottleRevealed, gold;
    private final int countdown;
    private final String notice;
    public FryerHud(Font font, View view, ZhangshaoKernel.State state, ZhangshaoKernel.Result result,
                    ItemStack dish, boolean active, boolean waiting, boolean bottleRevealed,
                    boolean gold, int countdown, String notice) {
        this.font = font; this.view = view; this.state = state; this.result = result; this.dish = dish;
        this.active = active; this.waiting = waiting; this.bottleRevealed = bottleRevealed; this.gold = gold;
        this.countdown = countdown; this.notice = notice;
    }
    private boolean active() { return active; }
    public static void renderBackground(GuiGraphics g, int leftPos, int topPos, List<Slot> slots) {
        g.fill(leftPos, topPos, leftPos + W, topPos + H, 0xFF101820);
        g.fill(leftPos + 5, topPos + 5, leftPos + W - 5, topPos + H - 5, 0xFF243340);
        for (Slot slot : slots) {
            g.fill(leftPos + slot.x - 1, topPos + slot.y - 1, leftPos + slot.x + 17, topPos + slot.y + 17, 0xFF0B1218);
        }
        g.fill(leftPos + 180, topPos + 153, leftPos + 350, topPos + 231, 0xFF14212A);
    }
    public void renderLabels(GuiGraphics g) {
        text(g, view.kind().displayName(), 10, 8, 0xFFF2E2B8);
        text(g, view.mode(), 58, 8, 0xFFCCE8F8);
        FryerRecipe recipe = view.recipe();
        ItemStack shown = state != null && (active() || waiting || result != null) ? dish
                : recipe != null ? recipe.result() : view.busy() ? dish : view.output();
        int star = state != null && (active() || waiting || result != null) ? state.p.star
                : recipe == null ? view.star() : recipe.star();
        if (!shown.isEmpty()) {
            text(g, fit(shown.getHoverName().getString(), 150), 10, 23, 0xFFFFFFFF);
            if (star > 0) text(g, "星级 " + star + "  推荐等级 " + RECOMMENDED[star - 1], 166, 23, 0xFFFFDA85);
            else text(g, "重新放料后预览星级", 166, 23, 0xFFCCD6DD);
        } else text(g, "放入完整配方后显示菜品", 10, 23, 0xFFCCD6DD);
        text(g, "原料", 18, 36, 0xFFCCD6DD);
        text(g, "容器", 176, 36, 0xFFCCD6DD);
        text(g, "调料", 212, 36, 0xFFCCD6DD);
        text(g, "出品", 248, 36, 0xFFCCD6DD);
        if (!active() && !waiting && !view.busy()) {
            button(g, "家常", 12, view.recipe() != null);
            button(g, result == null ? "掌勺" : "再来一份", 90, view.recipe() != null);
            button(g, "练习", 168, true);
        } else text(g, active() ? "空格/左键控条；F/右键操作" : "生产中，材料槽已锁定", 12, 78, 0xFFFFFFFF);
        if (state != null && (active() || waiting)) drawLive(g);
        else drawIdle(g, recipe);
        text(g, view.inventoryTitle(), view.inventoryLabelX(), view.inventoryLabelY(), 0xFFCCD6DD);
        if (result != null) drawResult(g);
        else drawHelp(g);
        if (!notice.isEmpty()) text(g, fit(notice, 338), 10, 132, 0xFFFFD98B);
    }

    private void drawIdle(GuiGraphics g, FryerRecipe recipe) {
        text(g, "掌勺费用 " + view.cost() + " 信用点/份；家常免费", 12, 99, 0xFFDFE8EC);
        text(g, "厨师等级 " + view.level() + "  手熟 " + view.mastery() + " / " + view.progress(), 12, 111, 0xFFCCD6DD);
        if (view.phase() == 1) text(g, "熟度 " + percent(view.cooked()) + "  剩余 " + seconds(view.remaining()) + "秒", 12, 123, 0xFFFFDA85);
        else if (recipe != null) text(g, (recipe.drink() ? "冷饮  " : "") + "性子 " + MOTIONS[recipe.motion()] + "  个人最佳 " + view.best() + "分", 12, 123, 0xFFCCD6DD);
    }

    private void drawLive(GuiGraphics g) {
        ZhangshaoKernel.Result live = ZhangshaoKernel.result(state, null);
        text(g, "熟度 " + percent(state.P / 1000) + "  准确 " + percent(live.accuracy), 12, 98, 0xFFFFFFFF);
        text(g, "欠火 " + percent(live.underPm) + (state.p.station == ZhangshaoKernel.PREP ? "  多盐 " : "  过火 ") + percent(live.overPm), 12, 110, 0xFFCCD6DD);
        if (state.p.station == ZhangshaoKernel.FRYER) {
            text(g, "油温 " + state.T + "  " + (state.cd > 0 ? "冷却 " + seconds(state.cd) + "秒" : "F 可压火"), 12, 122, 0xFFFFDA85);
            text(g, "爆油 " + state.bursts + "次", 198, 122, 0xFFFFDA85);
        } else if (state.p.station == ZhangshaoKernel.OVEN) {
            text(g, state.prompt >= 0 ? "翻面！按 F / 右键" : "等待翻面提示", 12, 122, 0xFFFFDA85);
            text(g, "已翻 " + state.flipIdx + "/" + state.p.F, 198, 122, 0xFFFFDA85);
        } else {
            text(g, (state.p.jitterOn == 0 ? "冷饮：" : "") + (state.inSeg != 0 ? "摆料中，时限暂停" : "熟度到标记后摆料"), 12, 122, 0xFFFFDA85);
            text(g, "漏 " + state.miss + " 乱 " + state.mash, 198, 122, 0xFFFFDA85);
        }
        text(g, "时间 " + seconds(state.pt) + "/" + seconds(state.p.CAP), 198, 98, 0xFFFFFFFF);
        boolean clean = switch (state.p.station) {
            case ZhangshaoKernel.FRYER -> state.bursts == 0 && state.hot + state.cold <= state.p.cleanTicks;
            case ZhangshaoKernel.OVEN -> state.flipAllGood != 0;
            case ZhangshaoKernel.PREP -> state.miss == 0 && state.mash == 0;
            default -> throw new IllegalStateException("station " + state.p.station);
        };
        text(g, state.perfect != 0 && clean ? "Perfect 保持" : "Perfect 已失", 198, 110, 0xFFFFDA85);
        if (state.p.station == ZhangshaoKernel.PREP && state.inSeg != 0) drawBeats(g);
        else drawTrack(g);
        if (countdown < 20) {
            g.fill(190, 95, 300, 131, 0xFF090F14);
            g.pose().pushPose();
            g.pose().translate(237, 98, 0);
            g.pose().scale(2, 2, 1);
            text(g, Integer.toString(3 - countdown * 3 / 20), 0, 0, 0xFFFFDA85);
            g.pose().popPose();
            text(g, "下锅倒数，不记输入", 198, 119, 0xFFFFFFFF);
        }
    }

    private void drawTrack(GuiGraphics g) {
        int x = 314, y = 38, height = 90;
        g.fill(x, y, x + 28, y + height, 0xFF090F14);
        int barY = y + height - (state.yb + state.p.H) * height / state.p.track;
        int barH = state.p.H * height / state.p.track;
        g.fill(x + 2, barY, x + 26, barY + barH, state.inPrev != 0 ? 0xFF498C65 : 0xFFC4524E);
        int targetY = y + height - state.yt * height / state.p.track;
        g.fill(x + 7, targetY - 2, x + 21, targetY + 3, 0xFFFFE4A0);
        if (state.bState == ZhangshaoKernel.B_PRESENT) {
            int bottleY = y + height - state.bPos * height / state.p.track;
            g.fill(x - 7, bottleY - 2, x - 2, bottleY + 3,
                    bottleRevealed && gold ? 0xFFFFD044 : 0xFFB2D5ED);
        }
    }

    private void drawBeats(GuiGraphics g) {
        int x = 314, y = 38, height = 90, line = y + height - 18;
        g.fill(x, y, x + 28, y + height, 0xFF090F14);
        g.fill(x + 2, line, x + 26, line + 2, 0xFFFFFFFF);
        int[] beats = state.segBeats[state.segIdx];
        for (int i = state.bi; i < beats.length; i++) {
            int beatY = line - (beats[i] - state.segT) * 4;
            if (beatY >= y + 2 && beatY < y + height - 2)
                g.fill(x + 5, beatY - 2, x + 23, beatY + 3, i == state.bi ? 0xFFFFD268 : 0xFF88ACD7);
        }
    }

    private void drawHelp(GuiGraphics g) {
        text(g, view.kind().displayName() + "基本规则", 186, 157, 0xFFF2E2B8);
        text(g, "空格/左键：按住上升，松开下降", 186, 169, 0xFFDFE8EC);
        text(g, "让火候条框住食材，熟度满出品", 186, 181, 0xFFDFE8EC);
        String action = switch (view.kind()) {
            case DEEP_FRYER -> "F/右键：压火，避免爆油";
            case BAKING_OVEN -> "F/右键：按提示翻面";
            case PREP_COUNTER -> "F/右键：摆料时跟随节拍";
        };
        text(g, action, 186, 193, 0xFFDFE8EC);
        text(g, "瓶子：" + bottleText(), 186, 205, 0xFFFFDA85);
        text(g, "无暂停；双Esc撂勺出家常", 186, 217, 0xFFDFE8EC);
    }

    private String bottleText() {
        if (state == null) return "用火候条覆盖瓶子收取";
        return switch (state.bState) {
            case ZhangshaoKernel.B_NONE -> "本局未出现";
            case ZhangshaoKernel.B_WAITING -> "尚未出现";
            case ZhangshaoKernel.B_CAUGHT -> "已收取" + (bottleRevealed && gold ? "金瓶" : "");
            case ZhangshaoKernel.B_PRESENT -> (bottleRevealed && gold ? "金瓶 " : "普通瓶 ") + percent(state.K / 1000);
            default -> throw new IllegalStateException("瓶状态 " + state.bState);
        };
    }

    private void drawResult(GuiGraphics g) {
        text(g, "服务端品质：" + quality(result.quality), 186, 157, 0xFFFFDA85);
        text(g, "准确 " + percent(result.accuracy) + "  扣分 " + result.pen, 186, 169, 0xFFFFFFFF);
        text(g, "欠火 " + percent(result.underPm) + (result.station == ZhangshaoKernel.PREP ? "  多盐 " : "  过火 ") + percent(result.overPm), 186, 181, 0xFFDFE8EC);
        String details = switch (result.station) {
            case ZhangshaoKernel.FRYER -> "爆油 " + result.bursts;
            case ZhangshaoKernel.OVEN -> "翻面 " + result.flips + " 正好 " + result.flipGood;
            case ZhangshaoKernel.PREP -> "正好 " + result.beatPerfect + " 漏拍 " + result.beatMiss;
            default -> throw new IllegalStateException("station " + result.station);
        };
        text(g, details + "  Perfect " + (result.perfect == 1 ? "达成" : "未达成"), 186, 193, 0xFFDFE8EC);
        String negatives = "无";
        if (result.negatives.length > 0) {
            String[] names = {"夹生", "烧焦", "倒胃"};
            negatives = String.join(" / ", Arrays.stream(result.negatives).mapToObj(i -> names[i]).toList());
        }
        text(g, "失误：" + negatives, 186, 205, 0xFFDFE8EC);
        text(g, result.bottle == 1 ? "调料瓶：已收取" : result.bottleKind == 0 ? "调料瓶：未出现" : "调料瓶：错过", 186, 217, 0xFFDFE8EC);
    }

    private void button(GuiGraphics g, String label, int x, boolean enabled) {
        g.fill(x, 73, x + 72, 91, enabled ? 0xFF356C7B : 0xFF414B52);
        text(g, label, x + (72 - font.width(label)) / 2, 78, enabled ? 0xFFFFFFFF : 0xFFB1BBC1);
    }

    private void text(GuiGraphics g, String text, int x, int y, int color) { g.drawString(font, text, x, y, color, false); }

    private String fit(String text, int pixels) { return font.plainSubstrByWidth(text, pixels); }

    private static String quality(int quality) { return QUALITIES[quality + 1]; }

    private static String percent(int pm) { return String.format(Locale.ROOT, "%.1f%%", pm / 10.0); }

    private static String seconds(int ticks) { return String.format(Locale.ROOT, "%.1f", ticks / 20.0); }
}
