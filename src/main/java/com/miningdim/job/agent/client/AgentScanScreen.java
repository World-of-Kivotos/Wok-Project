package com.miningdim.job.agent.client;

import com.miningdim.core.MiningConstants;
import com.miningdim.job.agent.network.AgentSealRequestC2S;
import com.miningdim.job.agent.panel.AgentScanEntry;
import com.miningdim.job.agent.panel.AgentScanMenu;
import com.miningdim.job.agent.panel.AgentScanSnapshot;
import com.miningdim.menu.AbstractMiningScreen;
import com.miningdim.network.MiningNetwork;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Inventory;

import java.util.List;

/**
 * 战术扫描面板客户端屏幕 (SpecialAgent_Job_DesignSpec 五章; 仅客户端逻辑端加载, 经 {@link AgentScanClient}
 * + DistExecutor 隔离注册)。最小可渲染骨架 (本任务不追美术, 像素级留 runClient): 按行列出扫描快照词条 ——
 * 已解密词条显示真名 + 类别色 + 可点 (点击发 {@link AgentSealRequestC2S} 封印); 未解密词条显示 "加密" 占位且不可点;
 * 已封印词条标注 "封印中" 且不可重复点。
 *
 * 数据源: {@link ClientAgentScanState} 镜像 (服务端经 {@code AgentScanSyncS2C} 推; 服务端权威, 客户端不自算)。
 * 服务端推新快照时经 {@link AgentScanClientReceiver} 调 {@link #onSnapshotUpdated} 即时反映。
 *
 * 点击 -> C2S 纪律 (与塔罗 ShinyPackSelectScreen 不同): 封印需回传 (目标网络 id + 词条注册名) 两字段, vanilla
 * clickMenuButton 仅一个 int 不足, 故走自有 {@link AgentSealRequestC2S} C2S (服务端再校验资格/分级/星级/槽位)。
 */
public final class AgentScanScreen extends AbstractMiningScreen<AgentScanMenu> {

    private static final ResourceLocation BG =
            new ResourceLocation(MiningConstants.MODID, "textures/gui/container/agent_scan.png");
    private static final int W = 176;
    private static final int H = 166;

    // 词条行布局 (相对界面左上角; 最小骨架, 像素留 runClient 调)。
    private static final int LIST_X = 8;
    private static final int LIST_Y = 20;
    private static final int ROW_H = 12;
    private static final int LIST_W = W - 16;

    // 行底色 (ARGB; 已解密可封=暖, 已封印=灰, 加密=暗)。
    private static final int COLOR_SEALABLE = 0xFFFFFFFF;
    private static final int COLOR_SEALED = 0xFF808080;
    private static final int COLOR_LOCKED = 0xFF555555;
    private static final int COLOR_ENCRYPTED = 0xFF333333;

    public AgentScanScreen(AgentScanMenu menu, Inventory inv, Component title) {
        super(menu, inv, title, BG, W, H);
    }

    /** 服务端推新快照时由 {@link AgentScanClientReceiver} 调 (客户端主线程); 镜像已写, 本处仅为刷新挂钩 (重绘自动)。 */
    public void onSnapshotUpdated() {
        // 镜像由 ClientAgentScanState 持; render 每帧读最新, 无需在此缓存。保留方法作刷新接缝 (后续可加动效)。
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        int row = rowAt(mouseX, mouseY);
        if (row >= 0) {
            AgentScanSnapshot snapshot = ClientAgentScanState.snapshot();
            if (snapshot != null && row < snapshot.entries().size()) {
                AgentScanEntry entry = snapshot.entries().get(row);
                // 仅已解密且可封且未封印的行才发封印请求 (服务端会再校验; 此处仅 UI 预过滤防无意义包)。
                if (entry.decrypted() && entry.sealable() && !entry.sealed()) {
                    MiningNetwork.CHANNEL.sendToServer(
                            new AgentSealRequestC2S(snapshot.targetNetworkId(), entry.affixId()));
                    return true;
                }
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    /** 鼠标命中第几行词条 (-1 = 未命中任何行 / 超出列表)。 */
    private int rowAt(double mouseX, double mouseY) {
        AgentScanSnapshot snapshot = ClientAgentScanState.snapshot();
        if (snapshot == null) {
            return -1;
        }
        int lx = this.leftPos + LIST_X;
        int ly = this.topPos + LIST_Y;
        int rows = snapshot.entries().size();
        for (int i = 0; i < rows; i++) {
            int y = ly + i * ROW_H;
            if (mouseX >= lx && mouseX < lx + LIST_W && mouseY >= y && mouseY < y + ROW_H - 1) {
                return i;
            }
        }
        return -1;
    }

    @Override
    protected void renderExtra(GuiGraphics graphics, int leftPos, int topPos, int mouseX, int mouseY, float pt) {
        AgentScanSnapshot snapshot = ClientAgentScanState.snapshot();
        if (snapshot == null) {
            // 尚未收到扫描快照: 空态提示 (最小骨架; 像素/美术留 runClient)。
            graphics.drawString(this.font, Component.translatable("gui.miningdim.agent_scan.empty"),
                    leftPos + LIST_X, topPos + LIST_Y, 0xFFAAAAAA, false);
            return;
        }
        // 顶部目标摘要 (星级 + 干员等级)。
        graphics.drawString(this.font,
                Component.translatable("gui.miningdim.agent_scan.header", snapshot.star(), snapshot.agentLevel()),
                leftPos + LIST_X, topPos + 8, 0xFFFFFFFF, false);

        List<AgentScanEntry> entries = snapshot.entries();
        int lx = leftPos + LIST_X;
        int ly = topPos + LIST_Y;
        for (int i = 0; i < entries.size(); i++) {
            AgentScanEntry entry = entries.get(i);
            int y = ly + i * ROW_H;
            Component label;
            int color;
            if (!entry.decrypted()) {
                label = Component.translatable("gui.miningdim.agent_scan.encrypted");
                color = COLOR_ENCRYPTED;
            } else if (entry.sealed()) {
                label = Component.translatable("gui.miningdim.agent_scan.sealed",
                        Component.translatable(entry.displayKey()));
                color = COLOR_SEALED;
            } else if (entry.sealable()) {
                label = Component.translatable(entry.displayKey());
                color = COLOR_SEALABLE;
            } else {
                // 已解密但不可封 (类别/星级门未过): 显示真名但灰显不可点。
                label = Component.translatable(entry.displayKey());
                color = COLOR_LOCKED;
            }
            graphics.drawString(this.font, label, lx, y, color, false);
        }
    }
}
