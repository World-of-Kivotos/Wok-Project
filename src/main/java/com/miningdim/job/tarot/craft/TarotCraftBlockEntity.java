package com.miningdim.job.tarot.craft;

import com.miningdim.job.tarot.TarotRegistry;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.common.capabilities.Capability;
import net.minecraftforge.common.capabilities.ForgeCapabilities;
import net.minecraftforge.common.util.LazyOptional;
import net.minecraftforge.items.IItemHandler;
import net.minecraftforge.items.ItemStackHandler;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * 塔罗合成台方块实体 (TarotReader spec 第八章)。持两输入槽 ItemStackHandler, 实现 {@link MenuProvider}
 * 打开 {@link TarotCraftMenu}。槽内容随方块持久化, 破坏时由 loot table 掉落给玩家 (避免吞牌)。
 */
public final class TarotCraftBlockEntity extends BlockEntity implements MenuProvider {

    /** 合成台两输入槽 (spec 第八章: 2 张同品质牌输入)。 */
    public static final int INPUT_SLOTS = 2;

    private final ItemStackHandler inventory = new ItemStackHandler(INPUT_SLOTS) {
        @Override
        protected void onContentsChanged(int slot) {
            setChanged();
        }

        @Override
        public int getSlotLimit(int slot) {
            return 1; // 卡牌 stacksTo(1); 每槽一张。
        }
    };

    private final LazyOptional<IItemHandler> itemHandler = LazyOptional.of(() -> inventory);

    public TarotCraftBlockEntity(BlockPos pos, BlockState state) {
        super(TarotRegistry.CRAFT_TABLE_BE.get(), pos, state);
    }

    public ItemStackHandler inventory() {
        return inventory;
    }

    @Override
    public @NotNull <T> LazyOptional<T> getCapability(@NotNull Capability<T> cap, @Nullable net.minecraft.core.Direction side) {
        if (cap == ForgeCapabilities.ITEM_HANDLER) {
            return itemHandler.cast();
        }
        return super.getCapability(cap, side);
    }

    @Override
    public void invalidateCaps() {
        super.invalidateCaps();
        itemHandler.invalidate();
    }

    @Override
    protected void saveAdditional(CompoundTag tag) {
        super.saveAdditional(tag);
        tag.put("Inventory", inventory.serializeNBT());
    }

    @Override
    public void load(CompoundTag tag) {
        super.load(tag);
        if (tag.contains("Inventory")) {
            inventory.deserializeNBT(tag.getCompound("Inventory"));
        }
    }

    /** 破坏方块时把输入槽里的牌还给世界 (避免吞牌; 由 Block.onRemove 调用)。 */
    public java.util.List<ItemStack> dropContents() {
        java.util.List<ItemStack> drops = new java.util.ArrayList<>();
        for (int i = 0; i < inventory.getSlots(); i++) {
            ItemStack s = inventory.getStackInSlot(i);
            if (!s.isEmpty()) {
                drops.add(s);
            }
        }
        return drops;
    }

    // ---- MenuProvider ----

    @Override
    public Component getDisplayName() {
        return Component.translatable("container.miningdim.tarot_craft");
    }

    @Nullable
    @Override
    public AbstractContainerMenu createMenu(int windowId, Inventory playerInv, Player player) {
        return new TarotCraftMenu(windowId, playerInv, getBlockPos());
    }

    /** 服务端打开合成 GUI 时写 extraData (BlockPos; 与 ModMenus.blockMenuType 的 readBlockPos 对应)。 */
    public void writeExtraData(ServerPlayer player, FriendlyByteBuf buf) {
        buf.writeBlockPos(getBlockPos());
    }
}
