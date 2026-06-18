package com.miningdim.job.chef;

import com.miningdim.menu.AbstractMiningMenu;
import com.miningdim.menu.MenuValidity;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.ContainerData;
import net.minecraft.world.inventory.ContainerLevelAccess;
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
    public static final int DATA_SIZE = 5;

    private final ContainerData data;
    private final SeasoningTableBlockEntity blockEntity;

    /** 服务端构造 (由 BlockEntity.createMenu 调)。 */
    public SeasoningMenu(int windowId, Inventory playerInv, SeasoningTableBlockEntity be) {
        super(ChefMenus.SEASONING_MENU.get(), windowId, CONTAINER_SLOTS,
                MenuValidity.ofBlock(
                        ContainerLevelAccess.create(be.getLevel(), be.getBlockPos()),
                        be.getBlockState().getBlock()));
        this.blockEntity = be;
        this.data = be.dataAccess();
        addContainerSlots(be);
        addPlayerInventory(playerInv, 8, 84);
        addDataSlots(data);
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
        addPlayerInventory(playerInv, 8, 84);
        addDataSlots(data);
    }

    private void addContainerSlots(SeasoningTableBlockEntity be) {
        // 输入槽 (仅食物) + 调料槽 (仅 seasonings)。坐标按界面布局。
        this.addSlot(new SlotItemHandler(be.inputSlots(), SLOT_INPUT, 44, 35) {
            @Override
            public boolean mayPlace(ItemStack stack) {
                return stack.getFoodProperties(null) != null;
            }
        });
        this.addSlot(new SlotItemHandler(be.inputSlots(), SLOT_SEASONING, 80, 35) {
            @Override
            public boolean mayPlace(ItemStack stack) {
                return SeasoningTag.isSeasoning(stack);
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

    public ChefQuality tierCap() {
        return ChefQuality.byTier(data.get(DATA_TIER_CAP));
    }

    public SeasoningTableBlockEntity blockEntity() {
        return blockEntity;
    }
}
