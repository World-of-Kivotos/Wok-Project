package com.miningdim.job.chef;

import com.miningdim.menu.AbstractMiningMenu;
import com.miningdim.menu.MenuValidity;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.ContainerData;
import net.minecraft.world.inventory.ContainerLevelAccess;
import net.minecraft.world.inventory.DataSlot;
import net.minecraft.world.inventory.SimpleContainerData;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.items.SlotItemHandler;

/**
 * 调味台容器菜单 (Chef_Job_DesignSpec 第四章; 复用 {@link AbstractMiningMenu} 脚手架)。
 *
 * 2 个容器槽: 槽 0 = 成品菜输入 (仅接受带 FoodProperties 的食物), 槽 1 = 可选调料 (仅接受 seasonings tag)。
 * ContainerData 暴露火候/QTE 状态供客户端 Screen 渲染 (服务端权威, 客户端只渲染防作弊)。
 *
 * 经 {@link com.miningdim.menu.ModMenus#blockMenuType} 注册 (extraData 首读 BlockPos), 服务端 openScreen
 * 时由 {@link SeasoningTableBlock} 写入 pos; 客户端据此重建同位置 menu + 走 {@link MenuValidity#ofBlock}。
 */
public final class SeasoningMenu extends AbstractMiningMenu {

    /** 容器槽数 (输入 + 调料)。 */
    public static final int CONTAINER_SLOTS = 2;
    public static final int SLOT_INPUT = 0;
    public static final int SLOT_SEASONING = 1;

    // ---- ContainerData 索引 (服务端 -> 客户端同步小游戏状态) ----
    public static final int DATA_PHASE = 0;       // 0=空闲 1=火候中 2=调味中 3=完成
    public static final int DATA_HEAT = 1;        // 当前火候 (0-HEAT_MAX)
    public static final int DATA_HITS = 2;        // 调味命中数
    public static final int DATA_CUE_ACTIVE = 3;  // 当前是否有活跃调味时机点 (0/1)
    public static final int DATA_TIER_CAP = 4;    // 台档上限 tier (0-4)
    public static final int DATA_CUE_TARGET = 5;
    public static final int DATA_HEATING = 6;
    public static final int DATA_REMAINING_TICKS = 7;
    public static final int DATA_FAILURE_REASON = 8;
    public static final int DATA_FINAL_QUALITY = 9;
    public static final int DATA_HEAT_MAX = 10;
    public static final int DATA_GREEN_START = 11;
    public static final int DATA_GREEN_END = 12;
    public static final int DATA_QTE_COUNT = 13;
    public static final int DATA_TARGET_QUALITY = 14;
    public static final int DATA_SUCCESS_CHANCE_PER_MILLE = 15;
    public static final int DATA_TARGET_MET = 16;
    public static final int DATA_BASE_CHANCE_LOW = 17;
    public static final int DATA_BASE_CHANCE_MEDIUM = 18;
    public static final int DATA_BASE_CHANCE_HIGH = 19;
    public static final int DATA_BASE_CHANCE_EXTRAORDINARY = 20;
    public static final int DATA_BASE_CHANCE_RADIANT = 21;
    public static final int DATA_SIZE = 22;

    private final ContainerData data;
    private final SeasoningTableBlockEntity blockEntity;
    private final DataSlot playerQualityCap = DataSlot.standalone();

    /** 服务端构造 (由 BlockEntity.createMenu 调)。 */
    public SeasoningMenu(int windowId, Inventory playerInv, SeasoningTableBlockEntity be) {
        super(ChefMenus.SEASONING_MENU.get(), windowId, CONTAINER_SLOTS,
                MenuValidity.ofBlock(
                        ContainerLevelAccess.create(be.getLevel(), be.getBlockPos()),
                        be.getBlockState().getBlock()));
        this.blockEntity = be;
        this.data = be.dataAccess();
        addContainerSlots(be);
        addPlayerInventory(playerInv, 47, 140);
        addDataSlots(data);
        playerQualityCap.set(ChefQualityResolver.qualityCapForLevel(ChefExperience.level(playerInv.player)).tier());
        addDataSlot(playerQualityCap);
    }

    /** 客户端构造 (blockMenuType extraData 读 BlockPos 后调; 无 BlockEntity 引用, 用占位数据 + 远端槽)。 */
    public SeasoningMenu(int windowId, Inventory playerInv, BlockPos pos) {
        super(ChefMenus.SEASONING_MENU.get(), windowId, CONTAINER_SLOTS,
                MenuValidity.ofBlock(
                        ContainerLevelAccess.create(playerInv.player.level(), pos),
                        clientBlock(playerInv, pos)));
        this.blockEntity = clientBlockEntity(playerInv, pos);
        this.data = new SimpleContainerData(DATA_SIZE);
        addContainerSlots(blockEntity);
        addPlayerInventory(playerInv, 47, 140);
        addDataSlots(data);
        addDataSlot(playerQualityCap);
    }

    private void addContainerSlots(SeasoningTableBlockEntity be) {
        // 输入槽 (仅食物) + 调料槽 (仅 seasonings)。坐标按界面布局。
        this.addSlot(new SlotItemHandler(be.inputSlots(), SLOT_INPUT, 18, 55) {
            @Override
            public boolean mayPlace(ItemStack stack) {
                return !be.isActive() && stack.getFoodProperties(null) != null;
            }

            @Override
            public boolean mayPickup(net.minecraft.world.entity.player.Player player) {
                return !be.isActive();
            }
        });
        this.addSlot(new SlotItemHandler(be.inputSlots(), SLOT_SEASONING, 44, 55) {
            @Override
            public boolean mayPlace(ItemStack stack) {
                return !be.isActive() && SeasoningTag.isSeasoning(stack);
            }

            @Override
            public boolean mayPickup(net.minecraft.world.entity.player.Player player) {
                return !be.isActive();
            }
        });
    }

    /** 客户端取方块 (用于 MenuValidity.ofBlock 的方块比对); 不在则回 SMITHING 占位仅供 stillValid 不崩。 */
    private static net.minecraft.world.level.block.Block clientBlock(Inventory inv, BlockPos pos) {
        return inv.player.level().getBlockState(pos).getBlock();
    }

    /** 客户端侧从世界取 BlockEntity (用于槽容器绑定); 缺失时新建临时实体避免 NPE (槽空, 不影响渲染)。 */
    private static SeasoningTableBlockEntity clientBlockEntity(Inventory inv, BlockPos pos) {
        if (inv.player.level().getBlockEntity(pos) instanceof SeasoningTableBlockEntity be) {
            return be;
        }
        return new SeasoningTableBlockEntity(pos, inv.player.level().getBlockState(pos));
    }

    // ---- 客户端渲染读取 ----

    public int phase() {
        return data.get(DATA_PHASE);
    }

    public int heat() {
        return data.get(DATA_HEAT);
    }

    public int hits() {
        return data.get(DATA_HITS);
    }

    public boolean cueActive() {
        return data.get(DATA_CUE_ACTIVE) != 0;
    }

    public int cueTarget() {
        return data.get(DATA_CUE_TARGET);
    }

    public int targetIndex() {
        return cueTarget();
    }

    public boolean heating() {
        return data.get(DATA_HEATING) != 0;
    }

    public int remainingTicks() {
        return data.get(DATA_REMAINING_TICKS);
    }

    public int failureReason() {
        return data.get(DATA_FAILURE_REASON);
    }

    public int finalQuality() {
        return data.get(DATA_FINAL_QUALITY);
    }

    public int heatMax() {
        return data.get(DATA_HEAT_MAX);
    }

    public int greenStart() {
        return data.get(DATA_GREEN_START);
    }

    public int greenEnd() {
        return data.get(DATA_GREEN_END);
    }

    public int qteCount() {
        return data.get(DATA_QTE_COUNT);
    }

    public int targetQualityTier() {
        return data.get(DATA_TARGET_QUALITY);
    }

    public int successChancePerMille() {
        return data.get(DATA_SUCCESS_CHANCE_PER_MILLE);
    }

    public int targetMet() {
        return data.get(DATA_TARGET_MET);
    }

    public int targetBaseChancePerMille(ChefQuality quality) {
        return data.get(DATA_BASE_CHANCE_LOW + quality.tier());
    }

    public ChefQuality tierCap() {
        return ChefQuality.byTier(data.get(DATA_TIER_CAP));
    }

    public ChefQuality selectableCap() {
        return ChefQuality.min(tierCap(), ChefQuality.byTier(playerQualityCap.get()));
    }

    public SeasoningTableBlockEntity blockEntity() {
        return blockEntity;
    }

    @Override
    public void removed(net.minecraft.world.entity.player.Player player) {
        super.removed(player);
        if (!player.level().isClientSide && player instanceof net.minecraft.server.level.ServerPlayer serverPlayer
                && blockEntity.isActive()) {
            blockEntity.cancelCooking(serverPlayer, "调味已取消：关闭调味台不会消耗材料。");
        }
    }

    @Override
    public ItemStack quickMoveStack(net.minecraft.world.entity.player.Player player, int index) {
        if (blockEntity.isActive() && index >= 0 && index < CONTAINER_SLOTS) {
            return ItemStack.EMPTY;
        }
        return super.quickMoveStack(player, index);
    }
}
