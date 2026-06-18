package com.miningdim.job.chef.client;

import com.miningdim.core.MiningConstants;
import com.miningdim.job.chef.ChefHeatGame;
import com.miningdim.job.chef.ChefNetwork;
import com.miningdim.job.chef.SeasoningGameC2S;
import com.miningdim.job.chef.SeasoningMenu;
import com.miningdim.menu.AbstractMiningScreen;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Inventory;

/**
 * 调味台客户端 Screen (Chef_Job_DesignSpec 第四章; 仅客户端, 经 FMLClientSetupEvent.enqueueWork 内
 * MenuScreens.register 注册, 见 {@link com.miningdim.job.chef.ChefSystem})。
 *
 * 渲染服务端权威的小游戏状态 (火候条 + 调味时机点指示), 玩家点击发 C2S ({@link SeasoningGameC2S}); 服务端
 * 按自己的 heat/cue 权威状态校验结算 (客户端 click 不携带任何数值, 防作弊)。
 *
 * 复用 {@link AbstractMiningScreen} 脚手架 (底图 + 标题 + 库存标签); 火候条/时机点在 {@link #renderExtra} 叠加。
 */
public final class SeasoningScreen extends AbstractMiningScreen<SeasoningMenu> {

    private static final ResourceLocation BG =
            new ResourceLocation(MiningConstants.MODID, "textures/gui/seasoning_table.png");
    private static final int WIDTH = 176;
    private static final int HEIGHT = 166;

    /** 火候条像素布局 (相对界面左上角)。 */
    private static final int HEAT_BAR_X = 40;
    private static final int HEAT_BAR_Y = 20;
    private static final int HEAT_BAR_W = 96;
    private static final int HEAT_BAR_H = 6;

    /** "开始/出锅/命中" 操作按钮区 (整界面下半作为点击区, 简化为整界面响应)。 */
    public SeasoningScreen(SeasoningMenu menu, Inventory inv, Component title) {
        super(menu, inv, title, BG, WIDTH, HEIGHT);
    }

    @Override
    protected void renderExtra(GuiGraphics graphics, int leftPos, int topPos,
                               int mouseX, int mouseY, float partialTick) {
        int phase = menu.phase();
        // 火候条底槽。
        graphics.fill(leftPos + HEAT_BAR_X, topPos + HEAT_BAR_Y,
                leftPos + HEAT_BAR_X + HEAT_BAR_W, topPos + HEAT_BAR_Y + HEAT_BAR_H, 0xFF202020);
        // 绿区高亮。
        int greenLow = HEAT_BAR_W * ChefHeatGame.GREEN_LOW / ChefHeatGame.HEAT_MAX;
        int greenHigh = HEAT_BAR_W * ChefHeatGame.GREEN_HIGH / ChefHeatGame.HEAT_MAX;
        graphics.fill(leftPos + HEAT_BAR_X + greenLow, topPos + HEAT_BAR_Y,
                leftPos + HEAT_BAR_X + greenHigh, topPos + HEAT_BAR_Y + HEAT_BAR_H, 0xFF2E7D32);
        // 当前火候指针。
        int heatPx = HEAT_BAR_W * Math.min(menu.heat(), ChefHeatGame.HEAT_MAX) / ChefHeatGame.HEAT_MAX;
        graphics.fill(leftPos + HEAT_BAR_X + heatPx, topPos + HEAT_BAR_Y - 2,
                leftPos + HEAT_BAR_X + heatPx + 2, topPos + HEAT_BAR_Y + HEAT_BAR_H + 2, 0xFFFFFFFF);

        // 状态文案 + 命中数 (走 lang key, 命中数用 translatable 带参; 中/英同步, 不硬编码英文)。
        Component label = switch (phase) {
            case 1 -> Component.translatable("chef.screen.heat");
            case 2 -> menu.cueActive()
                    ? Component.translatable("chef.screen.season_now", menu.hits())
                    : Component.translatable("chef.screen.season_wait", menu.hits());
            case 3 -> Component.translatable("chef.screen.done");
            default -> Component.translatable("chef.screen.start");
        };
        graphics.drawString(this.font, label, leftPos + 8, topPos + 50, 0xFFFFFF, false);
        // 调味时机点活跃时画一个醒目方块提示。
        if (phase == 2 && menu.cueActive()) {
            graphics.fill(leftPos + 78, topPos + 60, leftPos + 98, topPos + 80, 0xFFFFC107);
        }
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button == 0) {
            int phase = menu.phase();
            // 点击发对应 C2S 意图 (服务端按权威状态结算; 客户端不判定)。
            switch (phase) {
                case 0 -> ChefNetwork.CHANNEL.sendToServer(new SeasoningGameC2S(SeasoningGameC2S.Action.START));
                case 1 -> ChefNetwork.CHANNEL.sendToServer(new SeasoningGameC2S(SeasoningGameC2S.Action.HEAT_CLICK));
                case 2 -> ChefNetwork.CHANNEL.sendToServer(new SeasoningGameC2S(SeasoningGameC2S.Action.SEASON_HIT));
                default -> { /* 完成态: 无操作 (菜已盖章, 玩家取走即可)。 */ }
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }
}
