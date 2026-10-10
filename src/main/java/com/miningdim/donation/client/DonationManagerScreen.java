package com.miningdim.donation.client;

import com.miningdim.donation.DonationManagerMenu;
import com.miningdim.menu.AbstractMiningScreen;
import net.minecraft.client.gui.components.Button;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Inventory;

/**
 * 管理界面 (复用原版 6 行大箱子贴图 generic_54)。右上角"账本"按钮打开 {@link DonationLedgerScreen}
 * 查看流水、累计与协管名单; 返回时回到本界面, 容器会话在此期间保持打开。
 */
public final class DonationManagerScreen extends AbstractMiningScreen<DonationManagerMenu> {

    private static final ResourceLocation BACKGROUND =
            new ResourceLocation("minecraft", "textures/gui/container/generic_54.png");
    private static final int WIDTH = 176;
    private static final int HEIGHT = 222;
    private static final int BUTTON_WIDTH = 44;
    private static final int BUTTON_HEIGHT = 12;

    public DonationManagerScreen(DonationManagerMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title, BACKGROUND, WIDTH, HEIGHT);
        this.inventoryLabelY = HEIGHT - 94;
    }

    @Override
    protected void init() {
        super.init();
        addRenderableWidget(Button.builder(Component.translatable("screen.miningdim.donation_box.ledger.open"),
                        button -> openLedger())
                .bounds(this.leftPos + this.imageWidth - BUTTON_WIDTH - 6, this.topPos + 4, BUTTON_WIDTH, BUTTON_HEIGHT)
                .build());
    }

    private void openLedger() {
        if (this.minecraft != null && this.menu.getCarried().isEmpty()) {
            this.minecraft.setScreen(new DonationLedgerScreen(this));
        }
    }
}
