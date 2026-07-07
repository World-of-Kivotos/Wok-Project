package com.miningdim.job.brewer.cellar.client;

import com.miningdim.core.MiningConstants;
import com.miningdim.job.brewer.BrewerItems;
import com.miningdim.job.brewer.WineNbt;
import com.miningdim.job.brewer.cellar.WineCellarBlockEntity;
import com.miningdim.job.brewer.cellar.WineCellarMenu;
import com.miningdim.menu.AbstractMiningScreen;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;

/**
 * 酒窖箱客户端 Screen (酿酒师 阶段 4; 仅客户端, 经 FMLClientSetupEvent.enqueueWork 内 MenuScreens.register
 * 注册, 见 {@link com.miningdim.job.brewer.cellar.WineCellarRegistry})。
 *
 * 复用 {@link AbstractMiningScreen} 底图脚手架; 在 {@link #renderExtra} 叠加缺粮提示 (燃料槽空且有酒时高亮警示)
 * 与悬停某酒槽时该瓶年份 / 变质的文案。陈酿结算服务端权威; 客户端 BE 快照仅作显示提示 (重开界面刷新), 不参与判定。
 */
public final class WineCellarScreen extends AbstractMiningScreen<WineCellarMenu> {

    private static final ResourceLocation BG =
            new ResourceLocation(MiningConstants.MODID, "textures/gui/wine_cellar.png");
    private static final int W = 176;
    private static final int H = 166;

    public WineCellarScreen(WineCellarMenu menu, Inventory inv, Component title) {
        super(menu, inv, title, BG, W, H);
    }

    @Override
    protected void renderExtra(GuiGraphics graphics, int leftPos, int topPos,
                               int mouseX, int mouseY, float partialTick) {
        WineCellarBlockEntity be = menu.blockEntity();
        boolean hasWine = false;
        for (int slot = 0; slot < WineCellarBlockEntity.WINE_SLOTS; slot++) {
            if (WineNbt.isWine(be.inventory().getStackInSlot(slot))) {
                hasWine = true;
                break;
            }
        }
        boolean fuelEmpty = be.inventory().getStackInSlot(WineCellarBlockEntity.FUEL_SLOT).isEmpty();

        // 缺粮警示: 有酒但燃料槽空 -> 酒会衰退变质, 醒目提示玩家补干小麦。
        if (hasWine && fuelEmpty) {
            graphics.drawString(this.font, Component.translatable("brewer.cellar.out_of_fuel"),
                    leftPos + 8, topPos + 6, 0xFFC04040, false);
        } else if (!hasWine) {
            graphics.drawString(this.font, Component.translatable("brewer.cellar.empty"),
                    leftPos + 8, topPos + 6, 0xFF808080, false);
        } else {
            graphics.drawString(this.font, Component.translatable("brewer.cellar.aging"),
                    leftPos + 8, topPos + 6, 0xFF408040, false);
        }
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        super.render(graphics, mouseX, mouseY, partialTick);
        renderVintageTooltip(graphics, mouseX, mouseY);
    }

    /** 悬停酒槽时附加显示该瓶年份 / 变质 (在原版物品 tooltip 之外补一行陈酿信息)。 */
    private void renderVintageTooltip(GuiGraphics graphics, int mouseX, int mouseY) {
        if (this.hoveredSlot == null || !this.hoveredSlot.hasItem()) {
            return;
        }
        ItemStack stack = this.hoveredSlot.getItem();
        if (!WineNbt.isWine(stack) || BrewerItems.typeOf(stack) == null) {
            return;
        }
        Component line = WineNbt.isSpoiled(stack)
                ? Component.translatable("brewer.cellar.spoiled")
                : Component.translatable("brewer.cellar.vintage",
                        String.format("%.1f", WineNbt.readVintage(stack)));
        graphics.renderTooltip(this.font, line, mouseX, mouseY);
    }
}
