package com.miningdim.job.chef;

import com.miningdim.economy.EconomyServices;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerData;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraftforge.items.ItemStackHandler;
import org.jetbrains.annotations.Nullable;
import software.bernie.geckolib.animatable.GeoBlockEntity;
import software.bernie.geckolib.core.animatable.instance.AnimatableInstanceCache;
import software.bernie.geckolib.core.animation.AnimatableManager;
import software.bernie.geckolib.core.animation.AnimationController;
import software.bernie.geckolib.core.animation.AnimationState;
import software.bernie.geckolib.core.animation.RawAnimation;
import software.bernie.geckolib.core.object.PlayState;
import software.bernie.geckolib.util.GeckoLibUtil;

import java.util.List;
import java.util.ArrayList;
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
public final class SeasoningTableBlockEntity extends BlockEntity implements MenuProvider, GeoBlockEntity {

    private static final int PHASE_IDLE = 0;
    private static final int PHASE_HEAT = 1;
    private static final int PHASE_SEASON = 2;
    private static final int PHASE_DONE = 3;
    private static final RawAnimation IDLE_ANIMATION = RawAnimation.begin().thenLoop("chef.idle");
    private static final RawAnimation COOKING_ANIMATION = RawAnimation.begin().thenLoop("chef.cooking");

    // 输入/调料槽仅由调味台菜单访问 (不经 getCapability 暴露 IItemHandler): 反挂机设计 (漏斗/机器刷不了菜,
    // Chef spec 第七章), 故不挂物品能力, 自动化无法注入/抽取。
    private final ItemStackHandler inputSlots = new ItemStackHandler(SeasoningMenu.CONTAINER_SLOTS) {
        @Override
        protected void onContentsChanged(int slot) {
            setChanged();
        }
    };

    private final ChefHeatGame heatGame = new ChefHeatGame();
    private final AnimatableInstanceCache animationCache = GeckoLibUtil.createInstanceCache(this);

    /** 谁在做 (开界面/打小游戏的厨师 UUID; 谁做谁得经验)。null = 无人占用。 */
    @Nullable
    private UUID operatorUUID;

    private int phase = PHASE_IDLE;
    private int hits;
    private int cueTimer;
    private boolean cueActive;
    private int cuesSpawned;
    private int cueTarget = -1;
    private int heatTicks;
    private int failureReason;
    private int finalQuality = -1;
    private boolean greenZoneFeedbackPlayed;

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
                case SeasoningMenu.DATA_CUE_TARGET -> cueTarget;
                case SeasoningMenu.DATA_HEATING -> heatGame.heating() ? 1 : 0;
                case SeasoningMenu.DATA_REMAINING_TICKS -> remainingTicks();
                case SeasoningMenu.DATA_FAILURE_REASON -> failureReason;
                case SeasoningMenu.DATA_FINAL_QUALITY -> finalQuality;
                case SeasoningMenu.DATA_HEAT_MAX -> ChefConfig.heatMax();
                case SeasoningMenu.DATA_GREEN_START -> ChefConfig.heatGreenStart();
                case SeasoningMenu.DATA_GREEN_END -> ChefConfig.heatGreenEnd();
                case SeasoningMenu.DATA_QTE_COUNT -> ChefConfig.qteCount();
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

    /** Empties the two input slots for block-break drops after any active transaction is cancelled. */
    public List<ItemStack> dropContents() {
        List<ItemStack> drops = new ArrayList<>(SeasoningMenu.CONTAINER_SLOTS);
        for (int slot = 0; slot < SeasoningMenu.CONTAINER_SLOTS; slot++) {
            ItemStack stack = inputSlots.getStackInSlot(slot);
            if (!stack.isEmpty()) {
                drops.add(stack);
                inputSlots.setStackInSlot(slot, ItemStack.EMPTY);
            }
        }
        return drops;
    }

    /** 本台品质上限 (从方块读取, 单一真源)。非调味台块挂本 BE 属装配错误, 自然抛 (C9 不掩盖)。 */
    public ChefQuality tierCap() {
        if (getBlockState().getBlock() instanceof SeasoningTableBlock table) {
            return ChefQuality.min(table.tierCap(), ChefQuality.byTier(ChefConfig.seasoningTableMaxTier()));
        }
        throw new IllegalStateException(
                "SeasoningTableBlockEntity attached to non-table block at " + worldPosition);
    }

    // ---- 服务端 tick: 推进火候 + 调味时机点 ----

    public void serverTick() {
        if (!isActive()) {
            setAnimationActive(false);
            return;
        }
        setAnimationActive(true);
        ServerPlayer operator = currentOperator();
        if (operator == null || !operatorStillControlsTable(operator)) {
            cancelCooking(operator, "调味已取消：操作状态失效，材料已保留。");
            return;
        }
        if (phase == PHASE_HEAT) {
            heatTicks++;
            heatGame.tick(heatTicks > ChefConfig.seasoningScoringStartTick());
            if (!greenZoneFeedbackPlayed && heatGame.heat() >= ChefConfig.heatGreenStart()
                    && heatGame.heat() <= ChefConfig.heatGreenEnd()) {
                greenZoneFeedbackPlayed = true;
                playFeedback(SoundEvents.EXPERIENCE_ORB_PICKUP, 0.65F, 1.25F,
                        ParticleTypes.HAPPY_VILLAGER, 6);
            }
            if (heatTicks >= ChefConfig.seasoningDurationTicks()) {
                phase = PHASE_SEASON;
                cueTimer = randomCueGap();
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
                playFeedback(SoundEvents.FIRE_EXTINGUISH, 0.70F, 1.15F, ParticleTypes.SMOKE, 5);
                cueTimer = randomCueGap();
            }
        } else {
            if (cueTimer <= 0) {
                if (cuesSpawned >= ChefConfig.qteCount()) {
                    finishCooking();
                    return;
                }
                cueActive = true;
                cuesSpawned++;
                cueTarget = level.random.nextInt(4);
                cueTimer = ChefConfig.qteWindowTicks();
            }
        }
    }

    // ---- C2S 输入入口 (服务端校验) ----

    /** 开始做菜 (玩家点 "开始" 按钮): 校验输入是食物 + 占用 operator + 进入火候阶段。 */
    public boolean startCooking(ServerPlayer operator) {
        if (phase == PHASE_DONE) {
            resetToIdle();
        }
        if (phase != PHASE_IDLE) {
            reject(operator, "START", "调味台已被占用");
            return false;
        }
        ItemStack input = inputSlots.getStackInSlot(SeasoningMenu.SLOT_INPUT);
        if (input.isEmpty() || input.getFoodProperties(operator) == null) {
            reject(operator, "START", "输入不是可食用成品");
            return false;
        }
        if (ChefQualityNbt.hasQuality(input)) {
            reject(operator, "START", "该菜肴已有 MiningChef 品质");
            return false;
        }
        if (SeasoningEligibility.isUnseasonable(input)) {
            reject(operator, "START", "该菜肴被不可调味标签禁止");
            return false;
        }
        operatorUUID = operator.getUUID();
        phase = PHASE_HEAT;
        heatTicks = 0;
        hits = 0;
        cuesSpawned = 0;
        cueActive = false;
        cueTarget = -1;
        failureReason = 0;
        finalQuality = -1;
        greenZoneFeedbackPlayed = false;
        heatGame.reset();
        setAnimationActive(true);
        setChanged();
        return true;
    }

    /** 玩家点击 "出锅" (火候阶段): 服务端按当前 heat 锁定。校验是 operator。 */
    public boolean pressHeat(ServerPlayer player) {
        if (phase != PHASE_HEAT || !isOperator(player)) {
            reject(player, "HEAT_PRESS", "阶段或操作者不匹配");
            return false;
        }
        if (!heatGame.press()) {
            reject(player, "HEAT_PRESS", "控火已处于按下状态");
            return false;
        }
        playFeedback(SoundEvents.LAVA_POP, 0.75F, 1.20F, ParticleTypes.FLAME, 4);
        setChanged();
        return true;
    }

    /** 玩家点击命中调味时机点 (调味阶段): 仅当有活跃时机点时计命中。校验是 operator。 */
    public boolean releaseHeat(ServerPlayer player) {
        if (phase != PHASE_HEAT || !isOperator(player)) {
            reject(player, "HEAT_RELEASE", "阶段或操作者不匹配");
            return false;
        }
        if (!heatGame.release()) {
            reject(player, "HEAT_RELEASE", "控火尚未按下");
            return false;
        }
        setChanged();
        return true;
    }

    public boolean hitSeason(ServerPlayer player, int target) {
        if (phase != PHASE_SEASON || !isOperator(player) || !cueActive || target != cueTarget) {
            reject(player, "SEASON_HIT", "阶段、操作者或目标不匹配");
            return false;
        }
        hits++;
        cueActive = false;
        cueTarget = -1;
        playFeedback(SoundEvents.EXPERIENCE_ORB_PICKUP, 0.75F, 1.50F, ParticleTypes.CRIT, 6);
        if (cuesSpawned == ChefConfig.qteCount()) {
            finishCooking();
            return true;
        }
        cueTimer = randomCueGap();
        setChanged();
        return true;
    }

    private boolean isOperator(ServerPlayer player) {
        return operatorUUID != null && operatorUUID.equals(player.getUUID());
    }

    private static boolean isEligibleInput(ItemStack input, ServerPlayer operator) {
        return !input.isEmpty() && input.getFoodProperties(operator) != null
                && !ChefQualityNbt.hasQuality(input) && !SeasoningEligibility.isUnseasonable(input);
    }

    // ---- 完成做菜: 解析品质 + 掷效果 + 盖章 + 记经验 ----

    private void finishCooking() {
        phase = PHASE_DONE;
        cueActive = false;
        setAnimationActive(false);
        if (!(level instanceof net.minecraft.server.level.ServerLevel serverLevel) || operatorUUID == null) {
            resetToIdle();
            return;
        }
        ServerPlayer operator = serverLevel.getServer().getPlayerList().getPlayer(operatorUUID);
        ItemStack input = inputSlots.getStackInSlot(SeasoningMenu.SLOT_INPUT);
        // isRemoved: 死亡到重生之间旧实体仍在 PlayerList 里但 capability 已失效, 下面读厨师等级会撞上
        // "capability 缺失"抛出并崩掉服务端 tick (2026-08-18 军火台同源崩服)。已移除即当离线处理。
        if (operator == null || operator.isRemoved()
                || !isEligibleInput(input, operator)
                || !heatGame.hasValidControlInput() || hits == 0) {
            cancelCooking(operator, "调味失败：必须至少完成一次有效控火和一次调味命中，材料已保留。");
            return;
        }

        // 经济 sink (信用点扣费): 经 EconomyServices 定位器; 经济未注入时放行不扣 (不阻塞核心循环),
        // 已注入且余额不足时拒绝做菜 (菜不盖章, 返还 idle, 玩家保有未调味的原菜)。
        if (!tryChargeTableUse(operator, ChefConfig.seasoningCreditCost())) {
            cancelCooking(operator, "调味失败：信用点不足，材料已保留。");
            return;
        }

        int chefLevel = ChefExperience.level(operator);
        ChefQuality achieved = ChefQualityResolver.resolve(
                heatGame.accuracyScore(), hits, ChefConfig.qteCount(), tierCap(), chefLevel);

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
        finalQuality = achieved.tier();
        playFeedback(SoundEvents.PLAYER_LEVELUP, 0.85F, 1.0F, ParticleTypes.HAPPY_VILLAGER, 14);
        setChanged();
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

    /**
     * 调味台做菜信用点 sink (Chef_Job_DesignSpec 7.2 经济 sink): 经 {@link EconomyServices} 定位器取门面扣费。
     * 经济子系统未注入 ({@link EconomyServices#isRegistered()} false) 或 cost &lt;= 0 时放行不扣 (经济未上线不阻塞
     * 核心循环; 注入后自动开始扣费) —— "经济可选 sink" 语义, 非掩盖空值: 余额足扣返 true, 不足返 false 由调用方
     * 据此拒绝做菜。直接走定位器而非 per-job static bind seam (审查 Major: 消除无 bind 调用方的悬空 seam)。
     *
     * @param operator 操作厨师 (服务端)
     * @param cost     做菜信用点成本 ({@link ChefConfig#TABLE_USE_COST_CREDIT}); &lt;= 0 视为免费直接放行
     * @return 是否允许做菜 (扣费成功 / 免费 / 经济未注入 = true; 余额不足 = false)
     */
    private static boolean tryChargeTableUse(ServerPlayer operator, long cost) {
        if (cost <= 0L || !EconomyServices.isRegistered()) {
            return true;
        }
        return EconomyServices.economyService().tryCharge(operator, com.miningdim.economy.Currency.CREDIT, cost);
    }

    private void resetToIdle() {
        phase = PHASE_IDLE;
        heatTicks = 0;
        hits = 0;
        cuesSpawned = 0;
        cueActive = false;
        cueTarget = -1;
        cueTimer = 0;
        failureReason = 0;
        finalQuality = -1;
        greenZoneFeedbackPlayed = false;
        operatorUUID = null;
        heatGame.reset();
        setAnimationActive(false);
        setChanged();
    }

    public boolean isActive() {
        return phase == PHASE_HEAT || phase == PHASE_SEASON;
    }

    /** Called by menu and block lifecycle paths; active inputs remain untouched. */
    public void cancelCooking(@Nullable ServerPlayer operator, String message) {
        if (!isActive() && phase != PHASE_DONE) {
            return;
        }
        phase = PHASE_DONE;
        heatTicks = 0;
        cueActive = false;
        cueTarget = -1;
        cueTimer = 0;
        failureReason = 1;
        finalQuality = -1;
        operatorUUID = null;
        heatGame.reset();
        setAnimationActive(false);
        setChanged();
        if (operator != null) {
            operator.displayClientMessage(Component.literal(message), true);
        }
    }

    public void cancelForBlockBreak() {
        cancelCooking(currentOperator(), "调味已取消：调味台被破坏，材料已掉落。");
    }

    private int remainingTicks() {
        if (phase == PHASE_HEAT) {
            return Math.max(0, ChefConfig.seasoningDurationTicks() - heatTicks);
        }
        return phase == PHASE_SEASON ? Math.max(0, cueTimer) : 0;
    }

    private int randomCueGap() {
        return ChefConfig.qteMinIntervalTicks()
                + level.random.nextInt(ChefConfig.qteMaxIntervalTicks() - ChefConfig.qteMinIntervalTicks() + 1);
    }

    private void playFeedback(SoundEvent sound, float volume, float pitch, ParticleOptions particle, int count) {
        if (level instanceof ServerLevel serverLevel) {
            double x = worldPosition.getX() + 0.5D;
            double y = worldPosition.getY() + 1.0D;
            double z = worldPosition.getZ() + 0.5D;
            serverLevel.playSound(null, x, y, z, sound, SoundSource.BLOCKS, volume, pitch);
            serverLevel.sendParticles(particle, x, y, z, count, 0.25D, 0.15D, 0.25D, 0.02D);
        }
    }

    @Nullable
    private ServerPlayer currentOperator() {
        if (!(level instanceof net.minecraft.server.level.ServerLevel serverLevel) || operatorUUID == null) {
            return null;
        }
        return serverLevel.getServer().getPlayerList().getPlayer(operatorUUID);
    }

    private boolean operatorStillControlsTable(ServerPlayer operator) {
        return operator.distanceToSqr(worldPosition.getX() + 0.5D, worldPosition.getY() + 0.5D,
                worldPosition.getZ() + 0.5D) <= 64.0D
                && operator.containerMenu instanceof SeasoningMenu menu
                && menu.blockEntity() == this;
    }

    void reject(ServerPlayer player, String action, String reason) {
        org.slf4j.LoggerFactory.getLogger("miningdim/chef").warn(
                "Rejected seasoning action {} from {} at {}: {}", action, player.getGameProfile().getName(),
                worldPosition, reason);
        player.displayClientMessage(Component.literal("调味操作被拒绝：" + reason), true);
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

    public boolean isAnimationActive() {
        return getBlockState().getValue(SeasoningTableBlock.ACTIVE);
    }

    private void setAnimationActive(boolean active) {
        if (level != null && getBlockState().getBlock() instanceof SeasoningTableBlock table
                && !getBlockState().getValue(SeasoningTableBlock.SECONDARY)) {
            table.setStructureActive(level, worldPosition, active);
        }
    }

    @Override
    public void registerControllers(AnimatableManager.ControllerRegistrar controllers) {
        controllers.add(new AnimationController<>(this, "workstation", 4, this::selectAnimation));
    }

    private PlayState selectAnimation(AnimationState<SeasoningTableBlockEntity> state) {
        return state.setAndContinue(isAnimationActive() ? COOKING_ANIMATION : IDLE_ANIMATION);
    }

    @Override
    public AnimatableInstanceCache getAnimatableInstanceCache() {
        return animationCache;
    }

    @Override
    public AABB getRenderBoundingBox() {
        BlockState state = getBlockState();
        if (!(state.getBlock() instanceof SeasoningTableBlock) || state.getValue(SeasoningTableBlock.SECONDARY)) {
            return super.getRenderBoundingBox();
        }
        BlockPos secondary = worldPosition.relative(state.getValue(SeasoningTableBlock.FACING).getClockWise());
        return new AABB(
                Math.min(worldPosition.getX(), secondary.getX()), worldPosition.getY(),
                Math.min(worldPosition.getZ(), secondary.getZ()),
                Math.max(worldPosition.getX(), secondary.getX()) + 1.0D, worldPosition.getY() + 2.0D,
                Math.max(worldPosition.getZ(), secondary.getZ()) + 1.0D);
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
