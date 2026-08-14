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
import net.minecraft.world.inventory.ContainerData;
import net.minecraft.world.inventory.ContainerLevelAccess;
import net.minecraft.world.inventory.SimpleContainerData;
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

    private static final int DATA_LAST_OUTCOME = 0;
    private static final int DATA_OUTCOME_SEQUENCE = 1;
    private static final int DATA_REVEAL_CARD = 2;
    private static final int DATA_COUNT = 3;
    private static final int NO_OUTCOME = 0;
    private static final int NO_REVEAL_CARD = 0;

    private final ContainerLevelAccess access;
    private final BlockPos pos;
    private final ItemStackHandler inputHandler;
    private final ContainerData outcomeData;

    public TarotCraftMenu(int windowId, Inventory playerInv, BlockPos pos) {
        super(TarotRegistry.CRAFT_MENU.get(), windowId, TarotCraftBlockEntity.INPUT_SLOTS,
                MenuValidity.ofBlock(ContainerLevelAccess.create(playerInv.player.level(), pos),
                        TarotRegistry.CRAFT_TABLE.get()));
        this.pos = pos;
        this.access = ContainerLevelAccess.create(playerInv.player.level(), pos);
        this.inputHandler = resolveInputHandler(playerInv, pos);
        this.outcomeData = new SimpleContainerData(DATA_COUNT);
        addDataSlots(outcomeData);

        // Two inputs flank the central astrolabe. Coordinates match tarot_craft.png exactly.
        addSlot(new CardSlot(inputHandler, 0, 32, 51));
        addSlot(new CardSlot(inputHandler, 1, 168, 51));
        // Player inventory: 9x3 at y=142 and hotbar at y=200.
        addPlayerInventory(playerInv, 28, 142);
    }

    /** Safe client-side quality preview. Empty means missing, malformed, mismatched, or Shiny input. */
    public java.util.Optional<TarotQuality> previewInputQuality() {
        ItemStack a = getSlot(0).getItem();
        ItemStack b = getSlot(1).getItem();
        if (a.isEmpty() || b.isEmpty()
                || !(a.getItem() instanceof TarotCardItem)
                || !(b.getItem() instanceof TarotCardItem)) {
            return java.util.Optional.empty();
        }
        try {
            TarotQuality qa = TarotCardItem.quality(a);
            TarotQuality qb = TarotCardItem.quality(b);
            if (qa != qb || qa == TarotQuality.SHINY || qa.next() == null) {
                return java.util.Optional.empty();
            }
            return java.util.Optional.of(qa);
        } catch (RuntimeException malformedCard) {
            return java.util.Optional.empty();
        }
    }

    /** Whether at least one of the two visual input slots currently contains a stack. */
    public boolean hasAnyInput() {
        return getSlot(0).hasItem() || getSlot(1).hasItem();
    }

    /** Sequence used by the client to trigger exactly one animation per completed craft. */
    public int outcomeSequence() {
        return outcomeData.get(DATA_OUTCOME_SEQUENCE);
    }

    /** Most recent server-authoritative craft result, or empty before the first completed craft. */
    public java.util.Optional<TarotCraftService.Result> lastOutcome() {
        int encoded = outcomeData.get(DATA_LAST_OUTCOME);
        TarotCraftService.Result[] results = TarotCraftService.Result.values();
        if (encoded <= NO_OUTCOME || encoded > results.length) {
            return java.util.Optional.empty();
        }
        return java.util.Optional.of(results[encoded - 1]);
    }

    /**
     * Snapshot of the successful product used only by the client-side reveal animation. The real
     * product is still granted immediately and authoritatively on the server.
     */
    public java.util.Optional<RevealCard> lastRevealCard() {
        int encoded = outcomeData.get(DATA_REVEAL_CARD);
        if (encoded == NO_REVEAL_CARD) {
            return java.util.Optional.empty();
        }
        int cardId = (encoded & 0x1F) - 1;
        int qualityOrdinal = ((encoded >>> 5) & 0x07) - 1;
        boolean upright = (encoded & (1 << 8)) != 0;
        if (cardId < 0 || cardId >= com.miningdim.job.tarot.TarotArcana.COUNT
                || qualityOrdinal < 0 || qualityOrdinal >= TarotQuality.values().length) {
            return java.util.Optional.empty();
        }
        return java.util.Optional.of(new RevealCard(
                cardId, TarotQuality.byOrdinal(qualityOrdinal), upright));
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
        // 归属门 (与 TarotCraftService.resolve 同判据): 别人的牌与无主牌都不能当材料 —— 判据在服务里,
        // 这里只是把同一条规则提前成一句提示, 免得玩家点了没反应还不知道为什么。
        java.util.UUID crafter = player.getUUID();
        if (!crafter.equals(TarotCardItem.owner(a)) || !crafter.equals(TarotCardItem.owner(b))) {
            player.displayClientMessage(Component.translatable("message.miningdim.tarot.craft.not_owner"), true);
            return false;
        }

        TarotCraftService.CraftOutcome outcome =
                TarotRuntime.craft().resolve(player, a, b, level.getRandom());

        int revealCard = encodeRevealCard(outcome.product());
        applyOutcome(player, level, outcome);
        publishOutcome(outcome.result(), revealCard);
        return true;
    }

    /** Publishes the result after all authoritative inventory mutations have completed. */
    private void publishOutcome(TarotCraftService.Result result, int revealCard) {
        outcomeData.set(DATA_LAST_OUTCOME, result.ordinal() + 1);
        outcomeData.set(DATA_REVEAL_CARD, revealCard);
        outcomeData.set(DATA_OUTCOME_SEQUENCE, outcomeData.get(DATA_OUTCOME_SEQUENCE) + 1);
    }

    private static int encodeRevealCard(ItemStack product) {
        if (product.isEmpty() || !(product.getItem() instanceof TarotCardItem)) {
            return NO_REVEAL_CARD;
        }
        try {
            int cardId = TarotCardItem.cardId(product);
            int qualityOrdinal = TarotCardItem.quality(product).ordinal();
            int uprightFlag = TarotCardItem.upright(product) ? (1 << 8) : 0;
            return (cardId + 1) | ((qualityOrdinal + 1) << 5) | uprightFlag;
        } catch (RuntimeException malformedProduct) {
            return NO_REVEAL_CARD;
        }
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

    /** Compact client-safe identity for the post-animation card reveal. */
    public record RevealCard(int cardId, TarotQuality quality, boolean upright) {
    }
}
