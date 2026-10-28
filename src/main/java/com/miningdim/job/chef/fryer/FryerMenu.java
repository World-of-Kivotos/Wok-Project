package com.miningdim.job.chef.fryer;

import com.miningdim.job.chef.ChefConfig;
import com.miningdim.job.chef.ChefExperience;
import com.miningdim.job.chef.SeasoningTag;
import com.miningdim.job.chef.station.CookingStationBlock;
import com.miningdim.menu.AbstractMiningMenu;
import com.miningdim.menu.MenuValidity;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ContainerData;
import net.minecraft.world.inventory.ContainerLevelAccess;
import net.minecraft.world.inventory.DataSlot;
import net.minecraft.world.inventory.SimpleContainerData;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraftforge.items.ItemStackHandler;
import net.minecraftforge.items.SlotItemHandler;

/** 炸锅生产容器；客户端库存只接收菜单同步，不写客户端世界中的方块实体。 */
public final class FryerMenu extends AbstractMiningMenu {
    public static final int INPUT_START = 0, INPUT_END = FryerRecipe.INPUT_SLOTS, CONTAINER = FryerRecipe.CONTAINER_SLOT, SEASONING = FryerRecipe.SEASONING_SLOT, OUTPUT = FryerRecipe.OUTPUT_SLOT, SLOTS = FryerRecipe.INVENTORY_SLOTS;
    public static final int DATA_SIZE = 15;
    private final FryerBlockEntity blockEntity;
    private final ItemStackHandler inventory;
    private final ContainerData data;
    private final BlockPos pos;
    private final Level level;
    private final CookingStationBlock.Kind kind;
    private final DataSlot[] costParts = {DataSlot.standalone(), DataSlot.standalone()};

    public FryerMenu(int id, Inventory playerInventory, FryerBlockEntity be) {
        super(FryerRegistry.menuType(), id, SLOTS, MenuValidity.ofBlock(
                ContainerLevelAccess.create(be.getLevel(), be.getBlockPos()), be.getBlockState().getBlock()));
        blockEntity = be;
        inventory = be.inventory();
        data = viewerData(be, playerInventory.player);
        pos = be.getBlockPos();
        level = playerInventory.player.level();
        kind = be.kind();
        addSlots(playerInventory);
    }

    public FryerMenu(int id, Inventory playerInventory, BlockPos pos) {
        super(FryerRegistry.menuType(), id, SLOTS, MenuValidity.ofBlock(
                ContainerLevelAccess.create(playerInventory.player.level(), pos),
                playerInventory.player.level().getBlockState(pos).getBlock()));
        blockEntity = null;
        inventory = new ItemStackHandler(SLOTS);
        data = new SimpleContainerData(DATA_SIZE);
        this.pos = pos.immutable();
        level = playerInventory.player.level();
        kind = ((CookingStationBlock) level.getBlockState(pos).getBlock()).kind();
        addSlots(playerInventory);
    }

    private static ContainerData viewerData(FryerBlockEntity be, Player viewer) {
        ContainerData station = be.data();
        return new ContainerData() {
            @Override public int get(int index) {
                if (index == 14) return ChefExperience.level(viewer);
                if (index != 5 && index != 6 && index != 12) return station.get(index);
                ResourceLocation dish = be.displayDishId();
                if (dish == null) return 0;
                FryerPlayerData records = FryerPlayerData.get((ServerLevel) viewer.level());
                return switch (index) {
                    case 5 -> records.mastery(viewer.getUUID(), dish);
                    case 6 -> records.best(viewer.getUUID(), dish);
                    case 12 -> Math.min(100, records.progress(viewer.getUUID(), dish));
                    default -> throw new IllegalStateException("Fryer viewer data " + index);
                };
            }
            @Override public void set(int index, int value) { station.set(index, value); }
            @Override public int getCount() { return station.getCount(); }
        };
    }
    private void addSlots(Inventory playerInventory) {
        for (int index = INPUT_START; index < INPUT_END; index++) addProductionSlot(index, 18 + index * 18, 48);
        addProductionSlot(CONTAINER, 180, 48);
        addProductionSlot(SEASONING, 216, 48);
        addProductionSlot(OUTPUT, 252, 48);
        addPlayerInventory(playerInventory, 9, 156);
        addDataSlots(data);
        for (DataSlot part : costParts) addDataSlot(part);
        if (!level.isClientSide) refreshCost();
    }

    private void addProductionSlot(int index, int x, int y) {
        addSlot(new SlotItemHandler(inventory, index, x, y) {
            @Override
            public boolean mayPlace(ItemStack stack) {
                if (busy() || index == OUTPUT) return false;
                if (index == SEASONING) return SeasoningTag.isSeasoning(stack);
                return inventory.isItemValid(index, stack);
            }

            @Override
            public boolean mayPickup(Player player) {
                if (index == OUTPUT) return blockEntity != null ? blockEntity.canTakeOutput() : phase() != 1 && phase() != 2;
                return !busy();
            }

            @Override
            public void setChanged() {
                super.setChanged();
                if (blockEntity != null) blockEntity.inventoryChanged();
            }
        });
    }

    public FryerBlockEntity blockEntity() { return blockEntity; }
    public BlockPos pos() { return pos; }
    public BlockPos position() { return pos; }
    public int value(int index) { return data.get(index); }
    public int phase() { return value(0); }
    public boolean busy() { return blockEntity != null ? blockEntity.busy() : phase() == 1 || phase() == 2; }
    public CookingStationBlock.Kind kind() { return kind; }
    public FryerRecipe previewRecipe() { return FryerBlockEntity.findRecipe(level, inventory, kind()); }

    public int cost() {
        int cost = 0;
        for (int index = 0; index < costParts.length; index++) cost |= (costParts[index].get() & 0xFFFF) << (index * 16);
        return cost;
    }

    private void refreshCost() {
        int cost = ChefConfig.seasoningCreditCost();
        for (int index = 0; index < costParts.length; index++) costParts[index].set((cost >>> (index * 16)) & 0xFFFF);
    }

    @Override
    public void broadcastChanges() {
        if (!level.isClientSide) refreshCost();
        super.broadcastChanges();
    }

    @Override
    public boolean clickMenuButton(Player player, int button) {
        if (!(player instanceof ServerPlayer serverPlayer) || blockEntity == null
                || player.containerMenu != this || !stillValid(player) || blockEntity.busy()) return false;
        if (button == 0 || button == 1) {
            blockEntity.start(serverPlayer, button == 1);
            return true;
        }
        return false;
    }

    @Override
    public void removed(Player player) {
        super.removed(player);
        if (player instanceof ServerPlayer serverPlayer && blockEntity != null && blockEntity.busy()
                && blockEntity.isOperator(serverPlayer)) {
            blockEntity.abort(serverPlayer, blockEntity.roundId(), "关闭烹饪台界面，本锅按家常结算。");
        }
    }

    @Override
    public ItemStack quickMoveStack(Player player, int index) {
        if (index < 0 || index >= slots.size()) return ItemStack.EMPTY;
        Slot slot = slots.get(index);
        if (!slot.hasItem() || !slot.mayPickup(player)) return ItemStack.EMPTY;
        ItemStack original = slot.getItem().copy();
        if (index < SLOTS) {
            // 每次只交付一个目标槽；原版 QUICK_MOVE 循环会继续处理剩余栈。
            // 单次只规划一个目标槽，物品转移后由本 tick 的后台检查点合并保存。
            for (int pass = 0; pass < 2; pass++) {
                for (int target = slots.size() - 1; target >= SLOTS; target--) {
                    Slot destination = slots.get(target);
                    ItemStack existing = destination.getItem();
                    if (!destination.mayPlace(original) || (pass == 0 ? existing.isEmpty() : !existing.isEmpty())) continue;
                    if (!existing.isEmpty() && !ItemStack.isSameItemSameTags(existing, original)) continue;
                    int capacity = Math.min(destination.getMaxStackSize(), original.getMaxStackSize()) - existing.getCount();
                    if (capacity <= 0) continue;
                    ItemStack taken = inventory.extractItem(index, Math.min(capacity, original.getCount()), false);
                    if (taken.isEmpty()) return ItemStack.EMPTY;
                    ItemStack delivered = existing.isEmpty() ? taken.copy() : existing.copy();
                    if (!existing.isEmpty()) delivered.grow(taken.getCount());
                    destination.set(delivered);
                    slot.onTake(player, taken);
                    return original;
                }
            }
            return ItemStack.EMPTY;
        }
        if (busy()) return ItemStack.EMPTY;
        int start = INPUT_START, end = INPUT_END;
        if (isRecipeContainer(original)) { start = CONTAINER; end = CONTAINER + 1; }
        else if (SeasoningTag.isSeasoning(original) && !isRecipeIngredient(original)) { start = SEASONING; end = SEASONING + 1; }
        for (int target = start; target < end; target++) {
            ItemStack simulated = inventory.insertItem(target, original, true);
            if (simulated.getCount() == original.getCount()) continue;
            // 用副本规划目标槽，成功接收后才减少玩家源槽。
            ItemStack remainder = inventory.insertItem(target, original.copy(), false);
            int transferred = original.getCount() - remainder.getCount();
            if (transferred == 0) return ItemStack.EMPTY;
            slot.set(remainder);
            slot.onTake(player, original.copyWithCount(transferred));
            return original;
        }
        return ItemStack.EMPTY;
    }

    private boolean isRecipeContainer(ItemStack stack) {
        return level.getRecipeManager().getAllRecipesFor(FryerRecipeRegistry.recipeType(kind())).stream()
                .anyMatch(recipe -> recipe.containerMatches(stack));
    }

    private boolean isRecipeIngredient(ItemStack stack) {
        return level.getRecipeManager().getAllRecipesFor(FryerRecipeRegistry.recipeType(kind())).stream()
                .anyMatch(recipe -> recipe.inputs().stream().anyMatch(input -> input.ingredient().test(stack)));
    }
}
