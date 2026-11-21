package com.miningdim.job.farmer.granary.client;

import com.miningdim.job.farmer.granary.GranaryCenterMenu;
import com.miningdim.job.farmer.granary.GranaryCenterPage;
import com.miningdim.job.farmer.granary.GranaryCenterPageC2S;
import com.miningdim.job.farmer.granary.GranaryNetwork;
import com.miningdim.menu.AbstractMiningScreen;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Inventory;

import java.util.List;

/**
 * 账台的多页签界面 (客户端, 只在 Dist.CLIENT 加载, 经 GranaryClientSetup + DistExecutor 注册)。
 *
 * 底图**先借原版箱子的** (与囤界面同一条"先在共享脚手架上跑通"的路子); 粮仓自有贴图随美术那一批换 (只改这一行)。
 *
 * 页签在顶部排一行, 点一下只发"我要看第几页"的请求, 内容等服务端回 —— 界面上没有任何判定。
 */
public final class GranaryCenterScreen extends AbstractMiningScreen<GranaryCenterMenu> {

    private static final ResourceLocation BACKGROUND =
            new ResourceLocation("textures/gui/container/generic_54.png");

    /** 与玩家背包 (3x9 + 快捷栏) 对齐的底图尺寸: 高 = 84 + 3*18 + 6 + 18 + 6。 */
    private static final int IMAGE_WIDTH = 176;
    private static final int IMAGE_HEIGHT = 196;

    private static final int TAB_Y = 6;
    private static final int TAB_WIDTH = 34;
    private static final int TAB_HEIGHT = 14;
    private static final int LINES_Y = 26;
    private static final int LINE_HEIGHT = 10;
    private static final int MAX_DRAWN_LINES = 12;

    private int lastSentPage = -1;

    public GranaryCenterScreen(GranaryCenterMenu menu, Inventory inv, Component title) {
        super(menu, inv, title, BACKGROUND, IMAGE_WIDTH, IMAGE_HEIGHT);
    }

    @Override
    protected void init() {
        super.init();
        GranaryCenterClientState.clear();
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        renderBackground(graphics);
        super.render(graphics, mouseX, mouseY, partialTick);
        drawTabs(graphics, mouseX, mouseY);
        drawLines(graphics);
    }

    /** 顶部页签: 选中的那一页高亮; 每帧比对"当前页"与"已请求页", 不一致就补一次请求 (掉包也能自愈)。 */
    private void drawTabs(GuiGraphics graphics, int mouseX, int mouseY) {
        GranaryCenterPage[] pages = GranaryCenterPage.values();
        int x = leftPos + 8;
        GranaryCenterPage current = GranaryCenterClientState.page();
        for (GranaryCenterPage page : pages) {
            boolean selected = page == current;
            int color = selected ? 0xFFFFFFFF : 0xFFB0B0B0;
            graphics.fill(x, topPos + TAB_Y, x + TAB_WIDTH, topPos + TAB_Y + TAB_HEIGHT,
                    selected ? 0x80205020 : 0x40101010);
            graphics.drawString(font, Component.translatable(page.translationKey()), x + 2, topPos + TAB_Y + 3,
                    color, false);
            x += TAB_WIDTH + 2;
        }
        if (lastSentPage != current.ordinal()) {
            lastSentPage = current.ordinal();
            GranaryNetwork.CHANNEL.sendToServer(
                    new GranaryCenterPageC2S(menu.containerId, current.ordinal()));
        }
    }

    /** 正文: 服务端发来的那一页文本 (超过能画的行数就截断显示, 服务端已经给了截断提示)。 */
    private void drawLines(GuiGraphics graphics) {
        List<String> lines = GranaryCenterClientState.lines();
        int drawn = 0;
        for (String json : lines) {
            if (drawn >= MAX_DRAWN_LINES) {
                break;
            }
            Component line = Component.Serializer.fromJson(json);
            if (line == null) {
                continue;
            }
            graphics.drawString(font, line, leftPos + 10, topPos + LINES_Y + drawn * LINE_HEIGHT,
                    0xFF404040, false);
            drawn++;
        }
    }
}