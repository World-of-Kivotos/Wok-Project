package com.miningdim.menu;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Inventory;

/**
 * 公共 menu 客户端 Screen 基类 (JobFramework_Shared_Foundation_DesignSpec 第六章)。仅客户端使用 ——
 * 引用本类必经 FMLClientSetupEvent.enqueueWork 内的 MenuScreens.register (专用服务器无客户端类, 见
 * DangerSyncS2C.handle 的 DistExecutor 隔离范式), 子类注册时机统一在客户端 setup 监听。
 *
 * 提供通用底图渲染脚手架: 子类传入背景贴图 ResourceLocation 与界面像素尺寸, 本基类负责绘制底图 + 标题 +
 * 库存标签 + 悬浮提示, 子类只需在 {@link #renderExtra} 叠加自己的进度条/图标 (不必重写 renderBg/render)。
 */
public abstract class AbstractMiningScreen<T extends AbstractMiningMenu> extends AbstractContainerScreen<T> {

    private final ResourceLocation background;

    /**
     * @param menu       已打开的服务端 menu (客户端镜像)
     * @param inv        玩家背包
     * @param title      界面标题 Component
     * @param background 背景贴图 ResourceLocation (子类提供; 尺寸须与 imageWidth/imageHeight 一致)
     * @param imageWidth 界面像素宽
     * @param imageHeight 界面像素高
     */
    protected AbstractMiningScreen(T menu, Inventory inv, Component title,
                                   ResourceLocation background, int imageWidth, int imageHeight) {
        super(menu, inv, title);
        if (background == null) {
            throw new IllegalArgumentException("background texture must not be null");
        }
        this.background = background;
        this.imageWidth = imageWidth;
        this.imageHeight = imageHeight;
    }

    @Override
    protected void renderBg(GuiGraphics graphics, float partialTick, int mouseX, int mouseY) {
        int x = (this.width - this.imageWidth) / 2;
        int y = (this.height - this.imageHeight) / 2;
        // 1.20.1 GuiGraphics.blit(ResourceLocation, x, y, u, v, w, h): 绘制整张底图 (u/v 从 0,0 取)。
        graphics.blit(background, x, y, 0, 0, this.imageWidth, this.imageHeight);
        renderExtra(graphics, x, y, mouseX, mouseY, partialTick);
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        this.renderBackground(graphics);
        super.render(graphics, mouseX, mouseY, partialTick);
        this.renderTooltip(graphics, mouseX, mouseY);
    }

    /**
     * 子类叠加自己的进度条/CD 图标/数值 (底图已绘制, 标题/库存标签由 super.render 负责)。
     * 默认空实现 (无附加绘制的纯容器界面无需重写); 非空壳 —— 它是可选扩展点而非未完成逻辑。
     *
     * @param graphics 画布
     * @param leftPos  界面左上角 x (已居中算好)
     * @param topPos   界面左上角 y
     */
    protected void renderExtra(GuiGraphics graphics, int leftPos, int topPos,
                               int mouseX, int mouseY, float partialTick) {
    }
}
