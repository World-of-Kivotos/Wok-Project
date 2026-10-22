package com.miningdim.job.farmer.granary.client;

import com.miningdim.job.farmer.granary.GranaryAccess;
import com.miningdim.job.farmer.granary.GranaryBinMenu;
import com.miningdim.job.farmer.granary.GranaryNetwork;
import com.miningdim.job.farmer.granary.GranaryPublicActionC2S;
import com.miningdim.job.farmer.granary.GranaryPublicService;
import com.miningdim.job.farmer.granary.GranaryTrustActionC2S;
import com.miningdim.job.farmer.granary.GranaryTrustCheckC2S;
import com.miningdim.job.farmer.granary.GranaryTrustService;
import com.miningdim.menu.AbstractMiningScreen;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Inventory;

/**
 * 储粮囤的 27 格界面 + **开放设置面板** (规格「开放设置页」: 现在名单用命令管, 这里把它搬进界面)。
 *
 * 面板平时收起 (界面就是原样那 27 格); 点右上角「开放设置」展开: 一行名字输入框 + 三个按钮
 * (只存 / 只存能取 / 移出) + 一个「转私有」, 下面是当前名单与上一次操作的结果。
 *
 * **界面不做任何判定**: 点按钮只发一个请求 ({@link GranaryTrustActionC2S}), 名单与结果码都由服务端回
 * ({@link GranaryTrustClientState}) —— 谁能不能改、名单满没满、名字认不认得出来, 全在
 * {@code GranaryTrustService} 一处。
 */
public final class GranaryBinScreen extends AbstractMiningScreen<GranaryBinMenu> {

    private static final ResourceLocation BACKGROUND =
            new ResourceLocation("textures/gui/container/shulker_box.png");

    /** 与原版潜影盒一致: 176x166 (27 格 + 玩家背包)。 */
    private static final int IMAGE_WIDTH = 176;
    private static final int IMAGE_HEIGHT = 166;

    /** 面板: 标题 + 结果 + 两行名单 + 输入框 + 一排按钮 + 转私有 (6..100, 底图内侧)。 */
    private static final int PANEL_X = 8;
    private static final int PANEL_Y = 6;
    private static final int PANEL_WIDTH = 160;
    private static final int PANEL_HEIGHT = 130;

    private boolean panelOpen;
    private EditBox nameBox;
    private Button openButton;

    public GranaryBinScreen(GranaryBinMenu menu, Inventory inv, Component title) {
        super(menu, inv, title, BACKGROUND, IMAGE_WIDTH, IMAGE_HEIGHT);
    }

    @Override
    protected void init() {
        super.init();
        // 缓存只在"换了另一个囤的界面"时清: 窗口尺寸变化会重建控件, 那一下不该把刚收到的名单抹掉。
        if (GranaryTrustClientState.windowId() != menu.containerId) {
            GranaryTrustClientState.clear();
            GranaryPublicClientState.clear();
        }
        openButton = this.addRenderableWidget(Button.builder(
                        Component.translatable("screen.miningdim.granary.access.toggle"), button -> {
                            panelOpen = !panelOpen;
                            rebuildWidgets();
                        })
                .bounds(leftPos + IMAGE_WIDTH - 58, topPos + 4, 54, 14).build());
        if (panelOpen) {
            nameBox = this.addRenderableWidget(new EditBox(font, leftPos + PANEL_X + 4, topPos + PANEL_Y + 44,
                    PANEL_WIDTH - 8, 14, Component.translatable("screen.miningdim.granary.access.name")));
            nameBox.setMaxLength(16);
            int buttonY = topPos + PANEL_Y + 60;
            this.addRenderableWidget(Button.builder(Component.translatable("granary.access.store_only"),
                            button -> send(GranaryTrustService.Action.ADD, GranaryAccess.Permission.STORE_ONLY))
                    .bounds(leftPos + PANEL_X + 2, buttonY, 44, 14).build());
            this.addRenderableWidget(Button.builder(Component.translatable("granary.access.store_take"),
                            button -> send(GranaryTrustService.Action.ADD, GranaryAccess.Permission.STORE_AND_TAKE))
                    .bounds(leftPos + PANEL_X + 48, buttonY, 44, 14).build());
            this.addRenderableWidget(Button.builder(Component.translatable("screen.miningdim.granary.access.remove"),
                            button -> send(GranaryTrustService.Action.REMOVE, GranaryAccess.Permission.STORE_ONLY))
                    .bounds(leftPos + PANEL_X + 94, buttonY, 44, 14).build());
            this.addRenderableWidget(Button.builder(Component.translatable("screen.miningdim.granary.access.private"),
                            button -> send(GranaryTrustService.Action.CLEAR, GranaryAccess.Permission.STORE_ONLY))
                    .bounds(leftPos + PANEL_X + 4, buttonY + 16, 60, 14).build());
            // 官仓的公开模式: 两个开关 (份额用 /granary public 设, 开关在这里翻)。状态由服务端下发,
            // 没收到过状态就不画, 免得画出一个假开关。
            if (GranaryPublicClientState.known()) {
                this.addRenderableWidget(Button.builder(publicLabel("screen.miningdim.granary.public.donate",
                                GranaryPublicClientState.donate(), GranaryPublicClientState.donateGroups()),
                                button -> sendPublic(GranaryPublicClientState.donate()
                                        ? GranaryPublicService.Action.DONATE_OFF
                                        : GranaryPublicService.Action.DONATE_ON))
                        .bounds(leftPos + PANEL_X + 4, buttonY + 34, 110, 14).build());
                this.addRenderableWidget(Button.builder(publicLabel("screen.miningdim.granary.public.take",
                                GranaryPublicClientState.take(), GranaryPublicClientState.takeGroups()),
                                button -> sendPublic(GranaryPublicClientState.take()
                                        ? GranaryPublicService.Action.TAKE_OFF
                                        : GranaryPublicService.Action.TAKE_ON))
                        .bounds(leftPos + PANEL_X + 4, buttonY + 50, 110, 14).build());
            }
        }
    }

    /** 三种动作都只发一个请求; 权限档只在 ADD 时有意义。 */
    private void send(GranaryTrustService.Action action, GranaryAccess.Permission permission) {
        String name = nameBox == null ? "" : nameBox.getValue();
        GranaryNetwork.CHANNEL.sendToServer(new GranaryTrustActionC2S(menu.containerId,
                action.ordinal(), name, permission.ordinal()));
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        renderBackground(graphics);
        if (panelOpen) {
            // 底板先画: 控件是在 super.render 里画的, 底板画在它后面就会把按钮与输入框盖掉。
            drawPanelBackdrop(graphics);
        }
        maybeCheckName();
        super.render(graphics, mouseX, mouseY, partialTick);
        if (panelOpen) {
            drawPanelText(graphics);
        }
    }

    /** 面板底板 (半透明黑), 只负责"这块是面板"这件事。 */
    private void drawPanelBackdrop(GuiGraphics graphics) {
        graphics.fill(leftPos + PANEL_X, topPos + PANEL_Y, leftPos + PANEL_X + PANEL_WIDTH,
                topPos + PANEL_Y + PANEL_HEIGHT, 0xC0000000);
    }

    /** 输入框内容变了就发一次预检 (同一个名字不重复问)。 */
    private void maybeCheckName() {
        if (nameBox == null) {
            return;
        }
        String name = nameBox.getValue().trim();
        if (name.isEmpty() || name.equals(GranaryTrustClientState.checkName())) {
            return;
        }
        GranaryNetwork.CHANNEL.sendToServer(new GranaryTrustCheckC2S(menu.containerId, name));
    }

    /** 翻一项公开模式 (份额不动, 只开/关)。 */
    private void sendPublic(GranaryPublicService.Action action) {
        GranaryNetwork.CHANNEL.sendToServer(new GranaryPublicActionC2S(menu.containerId, action.ordinal(), 0));
    }

    /** 开关上的字: "公开捐粮: 开 (2 组/天)"。 */
    private static Component publicLabel(String prefix, boolean on, int groups) {
        return Component.translatable(prefix + (on ? ".on" : ".off"),
                on ? groups : 0);
    }

    /** 面板上的字: 标题 / 上一次操作的结果 / 当前名单 (全部来自服务端, 界面自己不算)。 */
    private void drawPanelText(GuiGraphics graphics) {
        graphics.drawString(font, Component.translatable("screen.miningdim.granary.access.title"),
                leftPos + PANEL_X + 4, topPos + PANEL_Y + 4, 0xFFFFFFFF, false);
        // 上一次操作的结果 (结果码由服务端给; 没操作过时不显示)。
        String outcome = GranaryTrustClientState.outcomeKey();
        if (!outcome.isEmpty()) {
            graphics.drawString(font, Component.translatable(outcome),
                    leftPos + PANEL_X + 4, topPos + PANEL_Y + 14, 0xFFFFD54F, false);
        }
        // 输入框预检的那一句 (名字认不出来 / 已在名单里 / 名单满了 / 那是你自己), 由服务端算好下发。
        String checkKey = GranaryTrustClientState.checkKey();
        if (!checkKey.isEmpty() && !GranaryTrustClientState.checkName().isEmpty()) {
            graphics.drawString(font, Component.translatable(checkKey),
                    leftPos + PANEL_X + 4, topPos + PANEL_Y + 60, 0xFF9CCC65, false);
        }
        // 公开模式的结果码 (同样是服务端给的; 没操作过 / 没收到过状态就不画)。
        if (GranaryPublicClientState.known() && !GranaryPublicClientState.outcomeKey().isEmpty()) {
            graphics.drawString(font, Component.translatable(GranaryPublicClientState.outcomeKey()),
                    leftPos + PANEL_X + 4, topPos + PANEL_Y + 118, 0xFFFFD54F, false);
        }
        // 当前名单 (服务端发来的行) —— 面板就这么高, 只画前两行。
        int y = topPos + PANEL_Y + 24;
        int drawn = 0;
        for (String json : GranaryTrustClientState.lines()) {
            if (drawn >= 2) {
                break;
            }
            Component line = Component.Serializer.fromJson(json);
            if (line != null) {
                graphics.drawString(font, line, leftPos + PANEL_X + 4, y, 0xFFE0E0E0, false);
            }
            y += 9;
            drawn++;
        }
    }

}