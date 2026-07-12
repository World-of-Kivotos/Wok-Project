package com.miningdim.job.miner.client;

import com.miningdim.core.MiningConstants;
import com.miningdim.job.miner.network.MinerStatusS2C;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RegisterGuiOverlaysEvent;
import net.minecraftforge.client.gui.overlay.ForgeGui;
import net.minecraftforge.client.gui.overlay.VanillaGuiOverlay;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * 矿工状态 HUD (全库首个原生 {@code IGuiOverlay}; 矿工是 FF14 式非 GUI 职业, 唯一 UI 面就是这块常驻 HUD)。
 * 仅客户端加载: {@link RegisterGuiOverlaysEvent} 是 mod 总线事件, 故挂 MOD 总线 + Dist.CLIENT (与
 * {@link MinerKeyMappings.ModBus} 同范式, 避免专用服务器 ClassNotFound)。
 *
 * 数据一律来自服务端 {@link MinerStatusS2C} (缓存于 {@link MinerStatusClient}), 客户端不推算任何 CD/充能。
 * 渲染位置左上角 (避开原版底部血条/物品栏与顶部无遮挡区); 只在矿洞维度 ({@link MiningConstants#MINING_LEVEL})
 * 且未按 F1 隐藏 HUD 时显示。纯 fill/drawString 绘制, 无美术资源。
 *
 * 注册在 HOTBAR 之上 (registerAbove): z 序在物品栏之后绘制, 但因布局在左上角与物品栏物理不重叠。
 */
@Mod.EventBusSubscriber(modid = MiningConstants.MODID, value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.MOD)
public final class MinerHudOverlay {

    private MinerHudOverlay() {
    }

    private static final String OVERLAY_ID = "miner_status";

    // ---- 布局 (像素) ----
    private static final int MARGIN = 4;
    private static final int PAD = 3;
    private static final int LINE_H = 10;
    /** 标签与状态值之间的水平间距。 */
    private static final int GAP = 6;
    private static final int BAR_W = 100;
    private static final int BAR_H = 4;
    /** 充能条与其后开关行之间的额外竖向间隔。 */
    private static final int BAR_GAP = 3;

    // ---- 颜色 (ARGB) ----
    private static final int COLOR_BG = 0x90000000;
    private static final int COLOR_HEADER = 0xFFFFE066;
    private static final int COLOR_LABEL = 0xFFCCCCCC;
    private static final int COLOR_TEXT = 0xFFFFFFFF;
    private static final int COLOR_ON = 0xFF55E06B;
    private static final int COLOR_OFF = 0xFF888888;
    private static final int COLOR_READY = 0xFF55E06B;
    private static final int COLOR_CD = 0xFFFFC94D;
    private static final int COLOR_LOCKED = 0xFF888888;
    private static final int COLOR_BAR_BG = 0xFF3A3A3A;
    private static final int COLOR_BAR_FILL = 0xFF44C851;

    @SubscribeEvent
    public static void onRegisterOverlays(RegisterGuiOverlaysEvent event) {
        event.registerAbove(VanillaGuiOverlay.HOTBAR.id(), OVERLAY_ID, MinerHudOverlay::render);
    }

    /** {@code IGuiOverlay.render} 签名 (ForgeGui, GuiGraphics, float partialTick, int screenW, int screenH)。 */
    private static void render(ForgeGui gui, GuiGraphics g, float partialTick, int screenWidth, int screenHeight) {
        Minecraft mc = gui.getMinecraft();
        // F1 隐藏 HUD / 尚未进入世界: 不渲染。
        if (mc.options.hideGui || mc.player == null || mc.level == null) {
            return;
        }
        // 仅矿洞维度显示 (离开矿洞即消失; 客户端以本地维度键判定, 不依赖服务端再发"关闭"包)。
        if (!mc.level.dimension().equals(MiningConstants.MINING_LEVEL)) {
            MinerStatusClient.clear(); // 离开矿洞维度即丢快照, 防再入时新包到达前闪现旧数据。
            return;
        }
        MinerStatusS2C s = MinerStatusClient.current();
        if (s == null) {
            return; // 尚未收到任何状态包 (刚进维度的极短窗口): 不画。
        }

        Font font = mc.font;
        boolean chainUnlocked = s.poolMax() > 0; // 连锁未解锁 (池上限 0) 时不画充能条。

        Component header = Component.translatable("category.miningdim.miner");
        Component chargeText = Component.translatable("hud.miningdim.miner.charge", s.charge(), s.poolMax());

        Component chainLabel = Component.translatable("skill.miningdim.miner.chain");
        Component collectLabel = Component.translatable("skill.miningdim.miner.auto_collect");
        Component smeltLabel = Component.translatable("skill.miningdim.miner.auto_smelt");
        Component oreLabel = Component.translatable("skill.miningdim.miner.ore_scan");
        Component trapLabel = Component.translatable("skill.miningdim.miner.trap_scan");

        Component chainVal = s.chainOn() ? onLabel() : offLabel();
        Component collectVal = s.autoCollectOn() ? onLabel() : offLabel();
        Component smeltVal = s.autoSmeltOn() ? onLabel() : offLabel();
        Component oreVal = cdValue(s.oreScanCdTicks());
        Component trapVal = cdValue(s.trapScanCdTicks());

        // 先量宽画背景 (背景须在文字下层, 故 fill 前算好最大行宽)。
        int maxW = font.width(header);
        if (chainUnlocked) {
            maxW = Math.max(maxW, font.width(chargeText));
            maxW = Math.max(maxW, BAR_W);
        }
        maxW = Math.max(maxW, rowWidth(font, chainLabel, chainVal));
        maxW = Math.max(maxW, rowWidth(font, collectLabel, collectVal));
        maxW = Math.max(maxW, rowWidth(font, smeltLabel, smeltVal));
        maxW = Math.max(maxW, rowWidth(font, oreLabel, oreVal));
        maxW = Math.max(maxW, rowWidth(font, trapLabel, trapVal));

        int textLines = chainUnlocked ? 7 : 6; // header + [charge] + 3 toggle + 2 scan
        int panelW = maxW + PAD * 2;
        int panelH = PAD * 2 + textLines * LINE_H + (chainUnlocked ? BAR_H + BAR_GAP : 0);
        g.fill(MARGIN, MARGIN, MARGIN + panelW, MARGIN + panelH, COLOR_BG);

        int x = MARGIN + PAD;
        int y = MARGIN + PAD;

        g.drawString(font, header, x, y, COLOR_HEADER);
        y += LINE_H;

        if (chainUnlocked) {
            g.drawString(font, chargeText, x, y, COLOR_TEXT);
            y += LINE_H;
            g.fill(x, y, x + BAR_W, y + BAR_H, COLOR_BAR_BG);
            int filled = (int) ((long) BAR_W * s.charge() / s.poolMax());
            filled = Math.max(0, Math.min(BAR_W, filled));
            g.fill(x, y, x + filled, y + BAR_H, COLOR_BAR_FILL);
            y += BAR_H + BAR_GAP;
        }

        y = drawRow(g, font, x, y, chainLabel, chainVal, s.chainOn() ? COLOR_ON : COLOR_OFF);
        y = drawRow(g, font, x, y, collectLabel, collectVal, s.autoCollectOn() ? COLOR_ON : COLOR_OFF);
        y = drawRow(g, font, x, y, smeltLabel, smeltVal, s.autoSmeltOn() ? COLOR_ON : COLOR_OFF);
        y = drawRow(g, font, x, y, oreLabel, oreVal, cdColor(s.oreScanCdTicks()));
        drawRow(g, font, x, y, trapLabel, trapVal, cdColor(s.trapScanCdTicks()));
    }

    /** 画一行 "标签 值", 返回下一行 y。 */
    private static int drawRow(GuiGraphics g, Font font, int x, int y, Component label, Component value, int valueColor) {
        g.drawString(font, label, x, y, COLOR_LABEL);
        g.drawString(font, value, x + font.width(label) + GAP, y, valueColor);
        return y + LINE_H;
    }

    private static int rowWidth(Font font, Component label, Component value) {
        return font.width(label) + GAP + font.width(value);
    }

    /** CD tick -> 展示值: 未解锁/就绪/剩余秒 (秒向上取整, 避免 CD 未满仍显示 0s)。 */
    private static Component cdValue(int ticks) {
        if (ticks == MinerStatusS2C.CD_LOCKED) {
            return Component.translatable("hud.miningdim.miner.locked");
        }
        if (ticks <= 0) {
            return Component.translatable("hud.miningdim.miner.ready");
        }
        int seconds = (ticks + 19) / 20;
        return Component.translatable("hud.miningdim.miner.seconds", seconds);
    }

    private static int cdColor(int ticks) {
        if (ticks == MinerStatusS2C.CD_LOCKED) {
            return COLOR_LOCKED;
        }
        if (ticks <= 0) {
            return COLOR_READY;
        }
        return COLOR_CD;
    }

    private static Component onLabel() {
        return Component.translatable("hud.miningdim.miner.on");
    }

    private static Component offLabel() {
        return Component.translatable("hud.miningdim.miner.off");
    }
}
