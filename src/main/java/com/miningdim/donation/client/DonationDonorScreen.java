package com.miningdim.donation.client;

import com.miningdim.donation.DonationDonorMenu;
import com.miningdim.menu.AbstractMiningScreen;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Inventory;

import java.util.List;

/**
 * 捐赠界面 (复用原版发射器贴图, 3x3 投入格与发射器格位完全重合)。
 *
 * 规则说明不另画贴图: 鼠标悬停在空的投入格上时弹出一段提示 (只收方块、不收带 NBT 的物品、放进即转入、
 * 仓库满时余量留在格里、关界面退还)。判定全在服务端, 这里只是说明文字。
 */
public final class DonationDonorScreen extends AbstractMiningScreen<DonationDonorMenu> {

    private static final ResourceLocation BACKGROUND =
            new ResourceLocation("minecraft", "textures/gui/container/dispenser.png");
    private static final int WIDTH = 176;
    private static final int HEIGHT = 166;

    private static final List<Component> RULES = List.of(
            Component.translatable("screen.miningdim.donation_box.rules.title"),
            Component.translatable("screen.miningdim.donation_box.rules.blocks_only"),
            Component.translatable("screen.miningdim.donation_box.rules.no_nbt"),
            Component.translatable("screen.miningdim.donation_box.rules.one_way"),
            Component.translatable("screen.miningdim.donation_box.rules.refund"));

    public DonationDonorScreen(DonationDonorMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title, BACKGROUND, WIDTH, HEIGHT);
    }

    @Override
    protected void init() {
        super.init();
        this.titleLabelX = (this.imageWidth - this.font.width(this.title)) / 2;
    }

    @Override
    protected void renderTooltip(GuiGraphics graphics, int mouseX, int mouseY) {
        super.renderTooltip(graphics, mouseX, mouseY);
        if (this.menu.getCarried().isEmpty() && this.hoveredSlot != null && !this.hoveredSlot.hasItem()
                && this.hoveredSlot.index < DonationDonorMenu.INPUT_SLOTS) {
            graphics.renderComponentTooltip(this.font, RULES, mouseX, mouseY);
        }
    }
}
