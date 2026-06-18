package com.miningdim.job.chef;

import com.miningdim.job.JobId;
import com.miningdim.job.JobServices;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerData;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.items.ItemStackHandler;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.UUID;

/**
 * 调味台方块实体 (Chef_Job_DesignSpec 第四章核心: 服务端时序权威小游戏状态)。
 *
 * 职责:
 *  - 持成品菜输入槽 + 调料槽 ({@link ItemStackHandler});
 *  - 火候小游戏 ({@link ChefHeatGame}) + 调味 QTE 时机点/命中计数的服务端权威状态机 (operatorUUID 锁谁在做);
 *  - {@link #serverTick} 推进火候与调味时机点; 客户端经 {@link ContainerData} 只渲染;
 *  - 完成时 ({@link #finishCooking}) 据综合分 + 双重封顶解析品质, 掷效果盖章, 记经验给 operatorUUID。
 *
 * 防作弊 (第四章): 火候推进与命中评分全服务端; 客户端 C2S ({@link SeasoningGameC2S}) 只发 "点击" 意图,
 * 服务端按当前 heat 评分 + 校验 operator 是开界面者。
 */
public final class SeasoningTableBlockEntity extends BlockEntity implements MenuProvider {

    /** 调味时机点持续 tick (玩家须在窗口内点击命中)。 */
    private static final int CUE_WINDOW_TICKS = 20;
    /** 调味时机点之间的间隔 tick。 */
    private static final int CUE_GAP_TICKS = 25;
    /** 一道菜总调味时机点数 (命中比 = hits/此值)。 */
    private static final int TOTAL_CUES = 4;

    private static final int PHASE_IDLE = 0;
    private static final int PHASE_HEAT = 1;
    private static final int PHASE_SEASON = 2;
    private static final int PHASE_DONE = 3;

    // 输入/调料槽仅由调味台菜单访问 (不经 getCapability 暴露 IItemHandler): 反挂机设计 (漏斗/机器刷不了菜,
    // Chef spec 第七章), 故不挂物品能力, 自动化无法注入/抽取。
    private final ItemStackHandler inputSlots = new ItemStackHandler(SeasoningMenu.CONTAINER_SLOTS) {
        @Override
        protected void onContentsChanged(int slot) {
            setChanged();
        }
    };

    private final ChefHeatGame heatGame = new ChefHeatGame();

    /** 谁在做 (开界面/打小游戏的厨师 UUID; 谁做谁得经验)。null = 无人占用。 */
    @Nullable
    private UUID operatorUUID;

    private int phase = PHASE_IDLE;
    private int hits;
    private int cueTimer;
    private boolean cueActive;
    private int cuesSpawned;

    /** ContainerData: 服务端写, 客户端读渲染 (小游戏状态)。 */
    private final ContainerData dataAccess = new ContainerData() {
        @Override
        public int get(int index) {
            return switch (index) {
                case SeasoningMenu.DATA_PHASE -> phase;
                case SeasoningMenu.DATA_HEAT -> heatGame.heat();
                case SeasoningMenu.DATA_HITS -> hits;
                case SeasoningMenu.DATA_CUE_ACTIVE -> cueActive ? 1 : 0;
                case SeasoningMenu.DATA_TIER_CAP -> tierCap().tier();
                default -> 0;
            };
        }

        @Override
        public void set(int index, int value) {
            // 服务端权威: 客户端不写状态 (空实现, ContainerData 双向接口但本菜单单向同步)。
        }

        @Override
        public int getCount() {
            return SeasoningMenu.DATA_SIZE;
        }
    };

    public SeasoningTableBlockEntity(BlockPos pos, BlockState state) {
        super(ChefBlockEntities.SEASONING_TABLE.get(), pos, state);
    }

    public ItemStackHandler inputSlots() {
        return inputSlots;
    }

    public ContainerData dataAccess() {
        return dataAccess;
    }

    /** 本台品质上限 (从方块读取, 单一真源)。非调味台块挂本 BE 属装配错误, 自然抛 (C9 不掩盖)。 */
    public ChefQuality tierCap() {
        if (getBlockState().getBlock() instanceof SeasoningTableBlock table) {
            return table.tierCap();
        }
        throw new IllegalStateException(
                "SeasoningTableBlockEntity attached to non-table block at " + worldPosition);
    }

    // ---- 服务端 tick: 推进火候 + 调味时机点 ----

    public void serverTick() {
        if (phase == PHASE_HEAT) {
            heatGame.tick();
            // 火候锁定 (玩家点出锅) 或过火 -> 进入调味阶段。
            if (heatGame.isLocked() || heatGame.overcooked()) {
                phase = PHASE_SEASON;
                cueTimer = CUE_GAP_TICKS;
                cueActive = false;
                cuesSpawned = 0;
            }
        } else if (phase == PHASE_SEASON) {
            tickSeason();
        }
    }

    private void tickSeason() {
        cueTimer--;
        if (cueActive) {
            if (cueTimer <= 0) {
                // 时机点窗口结束未命中: 关闭, 进入下一个间隔。
                cueActive = false;
                cueTimer = CUE_GAP_TICKS;
            }
        } else {
            if (cueTimer <= 0) {
                if (cuesSpawned >= TOTAL_CUES) {
                    finishCooking();
                    return;
                }
                cueActive = true;
                cuesSpawned++;
                cueTimer = CUE_WINDOW_TICKS;
            }
        }
    }

    // ---- C2S 输入入口 (服务端校验) ----

    /** 开始做菜 (玩家点 "开始" 按钮): 校验输入是食物 + 占用 operator + 进入火候阶段。 */
    public void startCooking(ServerPlayer operator) {
        if (phase != PHASE_IDLE) {
            return; // 已在做: 忽略 (防并发/连点)。
        }
        ItemStack input = inputSlots.getStackInSlot(SeasoningMenu.SLOT_INPUT);
        if (input.isEmpty() || input.getFoodProperties(operator) == null) {
            return; // 非食物: 不开始。
        }
        operatorUUID = operator.getUUID();
        phase = PHASE_HEAT;
        hits = 0;
        cuesSpawned = 0;
        cueActive = false;
        heatGame.reset();
        setChanged();
    }

    /** 玩家点击 "出锅" (火候阶段): 服务端按当前 heat 锁定。校验是 operator。 */
    public void clickHeat(ServerPlayer player) {
        if (phase != PHASE_HEAT || !isOperator(player)) {
            return;
        }
        heatGame.click(heatGame.heat());
        setChanged();
    }

    /** 玩家点击命中调味时机点 (调味阶段): 仅当有活跃时机点时计命中。校验是 operator。 */
    public void clickSeason(ServerPlayer player) {
        if (phase != PHASE_SEASON || !isOperator(player) || !cueActive) {
            return;
        }
        hits++;
        cueActive = false;
        cueTimer = CUE_GAP_TICKS;
        setChanged();
    }

    private boolean isOperator(ServerPlayer player) {
        return operatorUUID != null && operatorUUID.equals(player.getUUID());
    }

    // ---- 完成做菜: 解析品质 + 掷效果 + 盖章 + 记经验 ----

    private void finishCooking() {
        phase = PHASE_DONE;
        cueActive = false;
        if (!(level instanceof net.minecraft.server.level.ServerLevel serverLevel) || operatorUUID == null) {
            resetToIdle();
            return;
        }
        ServerPlayer operator = serverLevel.getServer().getPlayerList().getPlayer(operatorUUID);
        ItemStack input = inputSlots.getStackInSlot(SeasoningMenu.SLOT_INPUT);
        if (operator == null || input.isEmpty() || input.getFoodProperties(operator) == null) {
            resetToIdle();
            return;
        }

        // 经济 sink (信用点扣费): 经 ChefEconomyHooks; 经济未接线时放行不扣 (不阻塞核心循环, 见 foundationGaps),
        // 已接线且余额不足时拒绝做菜 (菜不盖章, 返还 idle, 玩家保有未调味的原菜)。
        if (!ChefEconomyHooks.tryChargeTableUse(operator, ChefConfig.TABLE_USE_COST_CREDIT.get())) {
            resetToIdle();
            return;
        }

        int chefLevel = JobServices.jobService().level(operator, JobId.CHEF);
        ChefQuality achieved = ChefQualityResolver.resolve(
                heatGame.accuracyScore(), hits, TOTAL_CUES, tierCap(), chefLevel);

        SeasoningBias bias = SeasoningTag.biasOf(inputSlots.getStackInSlot(SeasoningMenu.SLOT_SEASONING));
        List<ChefEffectInstance> effects = SeasoningEffectRoller.rollAll(
                serverLevel.random, chefLevel, achieved, bias, hits);

        // 一次小游戏只做一份 (Chef_Job_DesignSpec 7.5 反挂机), 盖章份数与 XP/经济 sink 严格 1:1 (见 produceSingleDish)。
        produceSingleDish(inputSlots, operator, operatorUUID, achieved, effects);

        // 消耗一份调料 (有则扣)。
        ItemStack seasoning = inputSlots.getStackInSlot(SeasoningMenu.SLOT_SEASONING);
        if (!seasoning.isEmpty()) {
            seasoning.shrink(1);
            inputSlots.setStackInSlot(SeasoningMenu.SLOT_SEASONING, seasoning);
        }

        // 谁做谁得经验 (按达成品质; 经共享 LevelingService 每日衰减软上限入账, 厨师不自实现衰减)。
        ChefXpHandler.award(operator, achieved);

        resetToIdle();
    }

    /**
     * 单份做菜核心 (Chef_Job_DesignSpec 7.5 反挂机红线): 从输入槽切出恰好 1 份盖章, 剩余 count-1 未调味原菜留在
     * 输入槽 (玩家须逐份再打小游戏), 盖章成品交给操作者背包 (满则脚下掉落, 不吞菜)。整组盖章会把一次小游戏放大
     * N 倍产出并稀释经济 sink, 故此处强制 1:1。抽出为包级静态便于 GameTest 直接驱动 (与 finishCooking 同代码路径)。
     *
     * @param slots       调味台输入/调料 handler (原地从 SLOT_INPUT 切 1 份)
     * @param operator    操作厨师 (盖章成品入其背包)
     * @param operatorUUID 操作者 UUID (写进成品 NBT 作归属凭据)
     * @param achieved    达成品质
     * @param effects     掷出的效果实例
     */
    static void produceSingleDish(ItemStackHandler slots, ServerPlayer operator, UUID operatorUUID,
                                  ChefQuality achieved, List<ChefEffectInstance> effects) {
        ItemStack input = slots.getStackInSlot(SeasoningMenu.SLOT_INPUT);
        ItemStack result = input.split(1); // 切走 1 份, input 余 count-1。
        ChefQualityNbt.stamp(result, achieved, effects);
        ChefQualityNbt.setOperator(result, operatorUUID);
        slots.setStackInSlot(SeasoningMenu.SLOT_INPUT, input);
        if (!operator.getInventory().add(result)) {
            operator.drop(result, false);
        }
    }

    private void resetToIdle() {
        phase = PHASE_IDLE;
        hits = 0;
        cuesSpawned = 0;
        cueActive = false;
        operatorUUID = null;
        heatGame.reset();
        setChanged();
    }

    // ---- MenuProvider ----

    @Override
    public Component getDisplayName() {
        return Component.translatable(getBlockState().getBlock().getDescriptionId());
    }

    @Nullable
    @Override
    public AbstractContainerMenu createMenu(int windowId, Inventory inv, Player player) {
        return new SeasoningMenu(windowId, inv, this);
    }

    // ---- 持久化 (槽内容; 小游戏瞬时状态不存, 重载即回 IDLE) ----

    @Override
    protected void saveAdditional(CompoundTag tag) {
        super.saveAdditional(tag);
        tag.put("Inventory", inputSlots.serializeNBT());
    }

    @Override
    public void load(CompoundTag tag) {
        super.load(tag);
        if (tag.contains("Inventory")) {
            inputSlots.deserializeNBT(tag.getCompound("Inventory"));
        }
    }
}
