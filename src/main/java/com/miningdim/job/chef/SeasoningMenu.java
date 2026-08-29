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
    public static final int DATA_TIER_CAP = 4;    // 调味台档位 tier (0-4)
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
    public static final int DATA_QTE_SWAY_PERIOD = 17;
    public static final int DATA_SIZE = 18;

    // ---- DATA_PHASE 取值 (调味台状态机的唯一编码; BlockEntity 与客户端 Screen 共用本组常量) ----
    public static final int PHASE_IDLE = 0;
    public static final int PHASE_HEAT = 1;
    public static final int PHASE_SEASON = 2;
    public static final int PHASE_DONE = 3;

    private final ContainerData data;
    private final SeasoningTableBlockEntity blockEntity;
    /** 开这个菜单的玩家 (预览按其实时厨师等级重算; 服务端与客户端各自持自己那份)。 */
    private final net.minecraft.world.entity.player.Player viewer;
    private final DataSlot[] targetPreviewChances = createDataSlots(ChefQuality.values().length);
    private final DataSlot[] targetPreviewQteCounts = createDataSlots(ChefQuality.values().length);

    /** 服务端构造 (由 BlockEntity.createMenu 调)。 */
    public SeasoningMenu(int windowId, Inventory playerInv, SeasoningTableBlockEntity be) {
        super(ChefMenus.SEASONING_MENU.get(), windowId, CONTAINER_SLOTS,
                MenuValidity.ofBlock(
                        ContainerLevelAccess.create(be.getLevel(), be.getBlockPos()),
                        be.getBlockState().getBlock()));
        this.blockEntity = be;
        this.viewer = playerInv.player;
        this.data = be.dataAccess();
        addContainerSlots(be);
        addPlayerInventory(playerInv, 47, 140);
        addDataSlots(data);
        addTargetPreviewDataSlots();
        refreshTargetPreview();
    }

    /** 客户端构造 (blockMenuType extraData 读 BlockPos 后调; 无 BlockEntity 引用, 用占位数据 + 远端槽)。 */
    public SeasoningMenu(int windowId, Inventory playerInv, BlockPos pos) {
        super(ChefMenus.SEASONING_MENU.get(), windowId, CONTAINER_SLOTS,
                MenuValidity.ofBlock(
                        ContainerLevelAccess.create(playerInv.player.level(), pos),
                        clientBlock(playerInv, pos)));
        this.blockEntity = clientBlockEntity(playerInv, pos);
        this.viewer = playerInv.player;
        this.data = new SimpleContainerData(DATA_SIZE);
        addContainerSlots(blockEntity);
        addPlayerInventory(playerInv, 47, 140);
        addDataSlots(data);
        addTargetPreviewDataSlots();
    }

    private static DataSlot[] createDataSlots(int count) {
        DataSlot[] slots = new DataSlot[count];
        for (int i = 0; i < slots.length; i++) {
            slots[i] = DataSlot.standalone();
        }
        return slots;
    }

    private void addTargetPreviewDataSlots() {
        for (DataSlot chance : targetPreviewChances) {
            addDataSlot(chance);
        }
        for (DataSlot count : targetPreviewQteCounts) {
            addDataSlot(count);
        }
    }

    private void addContainerSlots(SeasoningTableBlockEntity be) {
        // 输入槽 (仅食物) + 调料槽 (仅 seasonings)。坐标按界面布局。
        this.addSlot(new SlotItemHandler(be.inputSlots(), SLOT_INPUT, 18, 55) {
            @Override
            public boolean mayPlace(ItemStack stack) {
                return !transactionActive() && stack.getFoodProperties(null) != null;
            }

            @Override
            public boolean mayPickup(net.minecraft.world.entity.player.Player player) {
                return !transactionActive();
            }
        });
        this.addSlot(new SlotItemHandler(be.inputSlots(), SLOT_SEASONING, 44, 55) {
            @Override
            public boolean mayPlace(ItemStack stack) {
                return !transactionActive() && SeasoningTag.isSeasoning(stack);
            }

            @Override
            public boolean mayPickup(net.minecraft.world.entity.player.Player player) {
                return !transactionActive();
            }
        });
    }

    /**
     * 做菜进行中 (槽位锁的判据)。必须读菜单自己同步的 DATA_PHASE 而不是 BlockEntity.isActive():
     * phase 只存在于服务端 (不进 saveAdditional, 也无 getUpdateTag), 客户端那份 BlockEntity 的 phase 恒为 0,
     * 两端会得出相反结论, 客户端会预测出"食材可以取走"再被服务端纠正包拉回, 表现为物品闪烁回弹。
     */
    private boolean transactionActive() {
        int phase = phase();
        return phase == PHASE_HEAT || phase == PHASE_SEASON;
    }

    /**
     * 刷新五个目标品质的预览槽 (达成率 + QTE 数)。
     *
     * 口径是"完美操作 (控火满精度 + 全部时机点命中) 下的可达值": 面板是选目标用的决策界面, 用零表现地板值会把
     * 闪耀这类高目标显示成远低于实际可达的数字 (L1 低级台闪耀 0.5% vs 实际 4.5%), 玩家据此误判为不可能。
     * 每 tick 重算而非开菜单时算死: 不关面板连续做菜升级后 (ChefXpHandler.award 即时结算), 服务端已按新等级
     * 出题, 预览必须跟上。
     */
    private void refreshTargetPreview() {
        int chefLevel = ChefExperience.level(viewer);
        ChefQuality tableTier = blockEntity.tierCap();
        for (ChefQuality quality : ChefQuality.values()) {
            int cueCount = ChefQteTiming.cueCountFor(quality, chefLevel, tableTier);
            targetPreviewChances[quality.tier()].set(ChefQualityResolver.successChancePerMille(
                    quality, 1.0D, cueCount, cueCount, chefLevel, tableTier));
            targetPreviewQteCounts[quality.tier()].set(cueCount);
        }
    }

    @Override
    public void broadcastChanges() {
        // 只有服务端菜单持真 BlockEntity 与权威等级; 客户端那份只接收同步值 (且 blockEntity 可能是占位实例)。
        if (!viewer.level().isClientSide) {
            refreshTargetPreview();
        }
        super.broadcastChanges();
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

    public int qteSwayPeriodTicks() {
        return data.get(DATA_QTE_SWAY_PERIOD);
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

    public int targetPreviewChancePerMille(ChefQuality quality) {
        return targetPreviewChances[quality.tier()].get();
    }

    public int targetPreviewQteCount(ChefQuality quality) {
        return targetPreviewQteCounts[quality.tier()].get();
    }

    public ChefQuality tierCap() {
        return ChefQuality.byTier(data.get(DATA_TIER_CAP));
    }

    /**
     * 输入槽里的东西现在能不能开工 (与服务端 startCooking 的准入条件同源)。
     * 服务端对不合格输入的拒绝已改为静默 (防改包客户端刷日志与回包放大), 所以界面必须在玩家点下去之前
     * 就把"开始调味"按钮画成禁用并且不发包 —— 判据只读 stack, 两端结论一致。
     */
    public boolean inputSeasonable() {
        return SeasoningTableBlockEntity.isEligibleInput(getSlot(SLOT_INPUT).getItem(), viewer);
    }

    public SeasoningTableBlockEntity blockEntity() {
        return blockEntity;
    }

    /**
     * 关界面即取消本人的调味事务 (材料不消耗)。必须先校验关闭者就是当前 operator: SeasoningTableBlock.use 对
     * 任何玩家都开界面, 若不校验, 第二个人右键看一眼再按 Esc (或走远 8 格由 stillValid 自动关容器) 就会把真正
     * 操作者的火候与命中进度全部打掉, 且取消提示还发到了旁观者屏幕上。
     */
    @Override
    public void removed(net.minecraft.world.entity.player.Player player) {
        super.removed(player);
        if (!player.level().isClientSide && player instanceof net.minecraft.server.level.ServerPlayer serverPlayer
                && blockEntity.isActive() && blockEntity.isOperator(serverPlayer)) {
            blockEntity.cancelCooking(serverPlayer, "调味已取消：关闭调味台不会消耗材料。");
        }
    }

    @Override
    public ItemStack quickMoveStack(net.minecraft.world.entity.player.Player player, int index) {
        if (transactionActive() && index >= 0 && index < CONTAINER_SLOTS) {
            return ItemStack.EMPTY;
        }
        return super.quickMoveStack(player, index);
    }
}
