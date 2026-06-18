package com.miningdim.job.tarot.pack;

import com.miningdim.job.tarot.TarotArcana;
import com.miningdim.job.tarot.TarotRegistry;
import com.miningdim.job.tarot.TarotRuntime;
import com.miningdim.menu.AbstractMiningMenu;
import com.miningdim.menu.MenuValidity;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.items.ItemHandlerHelper;
import org.jetbrains.annotations.Nullable;

/**
 * 闪耀卡包自选 GUI (TarotReader spec 第七章: 开出后自选一张 SSR, 不含 UR/闪耀)。无方块 (远程 menu),
 * 经公共脚手架 {@link com.miningdim.menu.ModMenus#remoteMenuType} 注册。
 *
 * 选择交互: 22 张大阿卡纳按钮, 点击经 {@link #clickMenuButton} (buttonId = cardId 0-21) 服务端校验合法性后
 * 给一张该牌的 SSR (正逆随机), 并关闭界面 (一次性自选)。无背包槽 (纯按钮选择)。
 *
 * stillValid: 远程谓词恒 true (开界面后即可选; 一次性, 选完即关; spec 无距离约束)。
 */
public final class ShinyPackSelectMenu extends AbstractMiningMenu {

    private boolean consumed = false;

    public ShinyPackSelectMenu(int windowId, Inventory playerInv, FriendlyByteBuf extraData) {
        // 无容器槽 (containerSlotCount=0); 远程有效性谓词: 未消费且玩家存活即有效。
        super(TarotRegistry.SHINY_SELECT_MENU.get(), windowId, 0,
                MenuValidity.ofRemote(p -> p.isAlive()));
        // extraData 无附加字段 (Provider.writeExtraData 不写); 不读, 保持与远程工厂签名一致。
    }

    @Override
    public boolean clickMenuButton(Player player, int buttonId) {
        if (consumed) {
            return false;
        }
        if (buttonId < 0 || buttonId >= TarotArcana.COUNT) {
            return false;
        }
        if (!(player instanceof ServerPlayer serverPlayer)) {
            return false;
        }
        // 扣费与消耗包推迟到此刻 (spec 修正: 玩家 ESC 不选则零损失)。原子: 扣青辉石 + 消耗 1 包都成功才发牌。
        if (!TarotPackItem.chargeAndConsumeShiny(serverPlayer)) {
            serverPlayer.displayClientMessage(
                    Component.translatable("message.miningdim.tarot.pack.cannot_afford"), true);
            serverPlayer.closeContainer();
            return false;
        }
        // 服务端校验合法性后给一张自选 SSR (spec 第七章)。
        ItemStack card = TarotRuntime.gacha()
                .grantShinySelection(serverPlayer, buttonId, serverPlayer.getRandom());
        ItemHandlerHelper.giveItemToPlayer(serverPlayer, card);
        consumed = true;
        serverPlayer.closeContainer();
        return true;
    }

    @Override
    public ItemStack quickMoveStack(Player player, int index) {
        // 无容器槽, 无快速移动 (纯按钮选择界面)。
        return ItemStack.EMPTY;
    }

    /** 服务端打开自选 GUI 的 MenuProvider (无 BlockPos; 远程 menu)。 */
    public static final class Provider implements MenuProvider {

        @Override
        public Component getDisplayName() {
            return Component.translatable("container.miningdim.tarot_shiny_select");
        }

        @Nullable
        @Override
        public AbstractContainerMenu createMenu(int windowId, Inventory playerInv, Player player) {
            // 服务端侧用空 buf 构造 (远程工厂在客户端读同一空 buf)。
            return new ShinyPackSelectMenu(windowId, playerInv,
                    new FriendlyByteBuf(io.netty.buffer.Unpooled.buffer()));
        }
    }
}
