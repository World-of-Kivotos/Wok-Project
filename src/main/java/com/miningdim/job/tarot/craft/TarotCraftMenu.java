package com.miningdim.job.tarot.craft;

import com.miningdim.job.tarot.TarotCardItem;
import com.miningdim.job.tarot.TarotQuality;
import com.miningdim.job.tarot.TarotRegistry;
import com.miningdim.job.tarot.TarotRuntime;
import com.miningdim.menu.AbstractMiningMenu;
import com.miningdim.menu.MenuValidity;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.ContainerLevelAccess;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.items.IItemHandler;
import net.minecraftforge.items.ItemStackHandler;
import net.minecraftforge.items.SlotItemHandler;

/**
 * 合成 GUI (TarotReader spec 第八章)。两输入槽 (取自 {@link TarotCraftBlockEntity} 的 ItemStackHandler,
 * 客户端镜像用临时 handler) + 玩家背包。点 "合成按钮" 经 {@link #clickMenuButton} 服务端裁决四结果。
 *
 * 复用公共脚手架 {@link AbstractMiningMenu} (正确 quickMoveStack/stillValid)。槽只收卡牌 (SlotItemHandler
 * 限制), 服务端校验两张同品质且非闪耀才允许合成 (闪耀已是顶档)。
 */
public final class TarotCraftMenu extends AbstractMiningMenu {

    /** 合成按钮 id (clickMenuButton 的 buttonId)。 */
    public static final int BUTTON_CRAFT = 0;

    private final ContainerLevelAccess access;
    private final BlockPos pos;
    private final ItemStackHandler inputHandler;

    public TarotCraftMenu(int windowId, Inventory playerInv, BlockPos pos) {
        super(TarotRegistry.CRAFT_MENU.get(), windowId, TarotCraftBlockEntity.INPUT_SLOTS,
                MenuValidity.ofBlock(ContainerLevelAccess.create(playerInv.player.level(), pos),
                        TarotRegistry.CRAFT_TABLE.get()));
        this.pos = pos;
        this.access = ContainerLevelAccess.create(playerInv.player.level(), pos);
        this.inputHandler = resolveInputHandler(playerInv, pos);

        // 两输入槽 (横排), 只接受卡牌。
        addSlot(new CardSlot(inputHandler, 0, 44, 35));
        addSlot(new CardSlot(inputHandler, 1, 80, 35));
        // 玩家背包 (脚手架统一铺 36 槽)。
        addPlayerInventory(playerInv, 8, 84);
    }

    /** 服务端取真实 BE 的 handler; 客户端 (无 BE 或非同步态) 用临时空 handler 镜像 (槽内容随同步包刷新)。 */
    private static ItemStackHandler resolveInputHandler(Inventory playerInv, BlockPos pos) {
        Player player = playerInv.player;
        if (!player.level().isClientSide
                && player.level().getBlockEntity(pos) instanceof TarotCraftBlockEntity be) {
            return be.inventory();
        }
        return new ItemStackHandler(TarotCraftBlockEntity.INPUT_SLOTS);
    }

    @Override
    public boolean clickMenuButton(Player player, int buttonId) {
        if (buttonId != BUTTON_CRAFT) {
            return false;
        }
        if (!(player instanceof ServerPlayer serverPlayer)
                || !(player.level() instanceof ServerLevel level)) {
            return false;
        }
        return tryCraft(serverPlayer, level);
    }

    /** 服务端裁决一次合成 (spec 第八章四结果)。校验两输入同品质且可升档, 失败提示并 no-op。 */
    private boolean tryCraft(ServerPlayer player, ServerLevel level) {
        ItemStack a = inputHandler.getStackInSlot(0);
        ItemStack b = inputHandler.getStackInSlot(1);
        if (a.isEmpty() || b.isEmpty()) {
            player.displayClientMessage(Component.translatable("message.miningdim.tarot.craft.need_two"), true);
            return false;
        }
        if (!(a.getItem() instanceof TarotCardItem) || !(b.getItem() instanceof TarotCardItem)) {
            return false;
        }
        TarotQuality qa = TarotCardItem.quality(a);
        TarotQuality qb = TarotCardItem.quality(b);
        if (qa != qb || qa == TarotQuality.SHINY) {
            player.displayClientMessage(Component.translatable("message.miningdim.tarot.craft.bad_inputs"), true);
            return false;
        }
        TarotQuality to = qa.next();
        if (to == TarotQuality.SHINY
                && com.miningdim.job.tarot.TarotLeveling.level(player) < TarotQuality.SHINY.requiredLevel()) {
            player.displayClientMessage(Component.translatable("message.miningdim.tarot.craft.need_l10"), true);
            return false;
        }

        TarotCraftService.CraftOutcome outcome =
                TarotRuntime.craft().resolve(player, a, b, level.getRandom());

        applyOutcome(player, level, outcome);
        return true;
    }

    /** 按四结果消耗输入并发放产物/碎片 (spec 第八章: 破碎耗 1、大破碎耗 2、成功/逆转耗 2 出 1)。 */
    private void applyOutcome(ServerPlayer player, ServerLevel level, TarotCraftService.CraftOutcome outcome) {
        switch (outcome.result()) {
            case SUCCESS, REVERSE -> {
                inputHandler.setStackInSlot(0, ItemStack.EMPTY);
                inputHandler.setStackInSlot(1, ItemStack.EMPTY);
                giveOrDrop(player, outcome.product());
            }
            case SHATTER -> {
                // 耗 1 张 (槽 0), 返 1 碎片。
                inputHandler.setStackInSlot(0, ItemStack.EMPTY);
                giveOrDrop(player, TarotCraftService.makeShards(outcome.shardRefund()));
            }
            case BIG_SHATTER -> {
                inputHandler.setStackInSlot(0, ItemStack.EMPTY);
                inputHandler.setStackInSlot(1, ItemStack.EMPTY);
                giveOrDrop(player, TarotCraftService.makeShards(outcome.shardRefund()));
            }
            default -> throw new IllegalStateException("unhandled craft result: " + outcome.result());
        }
        player.displayClientMessage(Component.translatable(
                "message.miningdim.tarot.craft.result." + outcome.result().name().toLowerCase()), true);
    }

    private static void giveOrDrop(ServerPlayer player, ItemStack stack) {
        if (stack.isEmpty()) {
            return;
        }
        net.minecraftforge.items.ItemHandlerHelper.giveItemToPlayer(player, stack);
    }

    @Override
    public void removed(Player player) {
        super.removed(player);
        // 关界面时把仍在输入槽的牌还给玩家 (BE handler 已持久, 仅客户端镜像 handler 需回吐; 服务端 BE 保留)。
        if (player.level().isClientSide) {
            return;
        }
        // 服务端: 输入仍在 BE handler 里 (随方块持久), 不在此回吐 (玩家下次开界面仍可取)。
    }

    /** 只接受塔罗卡牌的输入槽。 */
    private static final class CardSlot extends SlotItemHandler {
        CardSlot(IItemHandler handler, int index, int x, int y) {
            super(handler, index, x, y);
        }

        @Override
        public boolean mayPlace(ItemStack stack) {
            return stack.getItem() instanceof TarotCardItem;
        }

        @Override
        public int getMaxStackSize() {
            return 1;
        }
    }
}
