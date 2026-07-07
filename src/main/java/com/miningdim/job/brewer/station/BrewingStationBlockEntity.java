package com.miningdim.job.brewer.station;

import com.miningdim.job.JobId;
import com.miningdim.job.JobServices;
import com.miningdim.job.brewer.BrewerConstants;
import com.miningdim.job.brewer.BrewerItems;
import com.miningdim.job.brewer.WineNbt;
import com.miningdim.job.brewer.WineQuality;
import com.miningdim.job.brewer.WineType;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
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

import java.util.UUID;

/**
 * 酿酒台方块实体 (酿酒师 阶段 3)。职责: 投料 -> 定时酿造 ({@link BrewerConstants#BREW_DURATION_TICKS}) ->
 * 产出 "基酒" (年份 0), 品质按操作者酿酒师等级 roll, 给操作者酿酒经验。
 *
 * 槽位: {@link #INPUT_SLOTS} 个投料输入槽 + 1 输出槽。{@link ContainerData} 只暴露酿造进度 (int) 供客户端进度条
 * 渲染 (服务端权威)。
 *
 * operator 语义 (谁投料/开界面谁是操作者, 谁得经验): 玩家打开菜单时记 {@link #operatorUuid}。酿造中途 operator
 * 离线仍产酒 (品质在产出帧按 "当时在线的 operator 等级" 已无从读 -> 故品质 roll 在 finishBrew 帧按 operator
 * 当前可读等级; operator 离线时按默认 1 级 roll 并跳过经验)。为契合 "operator 离线仍产酒、品质已定" 的要求,
 * 品质在酿造 "开始帧" (进度从 0 起步且 operator 在线时) 即锁定存入 {@link #pendingQuality}, 离线产出时复用该
 * 锁定品质, 故离线产酒品质不退化。
 *
 * 反挂机/反自动化: 不经 getCapability 暴露 IItemHandler (与厨师调味台同范式), 漏斗无法注入/抽取, 必须人手投料/取酒。
 */
public final class BrewingStationBlockEntity extends BlockEntity implements MenuProvider {

    /** 投料输入槽数 (够放最多原料种类 + 余量; 香槟3种料是最多的, 留 5 槽方便分堆投放)。 */
    public static final int INPUT_SLOTS = 5;
    /** 输出槽索引 (在合并 handler 末位)。 */
    public static final int OUTPUT_SLOT = INPUT_SLOTS;
    /** handler 总槽数 (输入 + 1 输出)。 */
    public static final int TOTAL_SLOTS = INPUT_SLOTS + 1;

    /** ContainerData 索引 (仅同步酿造进度, 客户端进度条用)。 */
    public static final int DATA_PROGRESS = 0;
    public static final int DATA_COUNT = 1;

    @Nullable
    private UUID operatorUuid;

    /** 酿造进度 (0..BREW_DURATION_TICKS); 0 = 未在酿造。 */
    private int progress;

    /** 本轮锁定的品质 (开始帧据 operator 等级 roll 锁定, 产出帧复用); null = 未锁定/未在酿造。 */
    @Nullable
    private WineQuality pendingQuality;

    /** 本轮匹配到的酒类型 (开始帧据投料 match 锁定; 防酿造中途增投料改判)。null = 未在酿造。 */
    @Nullable
    private WineType pendingType;

    private final ItemStackHandler inventory = new ItemStackHandler(TOTAL_SLOTS) {
        @Override
        protected void onContentsChanged(int slot) {
            setChanged();
            // 投料槽变动 (玩家改投/取料) 时中断进行中酿造, 防中途换料投机刷品质。
            if (slot < INPUT_SLOTS && progress > 0) {
                abortBrew();
            }
        }

        @Override
        public boolean isItemValid(int slot, ItemStack stack) {
            // 输出槽不接受外部放入 (只由 BE 写); 投料槽接受任意 (配方由 match 校验)。
            return slot < INPUT_SLOTS;
        }
    };

    private final ContainerData dataAccess = new ContainerData() {
        @Override
        public int get(int index) {
            return index == DATA_PROGRESS ? progress : 0;
        }

        @Override
        public void set(int index, int value) {
            // 服务端权威: 客户端不写状态 (ContainerData 双向接口但本菜单单向同步, 留空)。
        }

        @Override
        public int getCount() {
            return DATA_COUNT;
        }
    };

    public BrewingStationBlockEntity(BlockPos pos, BlockState state) {
        super(BrewingStationRegistry.STATION_BE.get(), pos, state);
    }

    public ItemStackHandler inventory() {
        return inventory;
    }

    public ContainerData dataAccess() {
        return dataAccess;
    }

    /** 玩家打开菜单/投料时设为操作者 (谁做谁得经验)。本轮已锁定 (酿造中或已 match 待推进) 则不抢占, 保归属。 */
    public void setOperator(UUID uuid) {
        if (progress == 0 && pendingType == null) {
            this.operatorUuid = uuid;
            setChanged();
        }
    }

    // ---- 服务端 tick: 推进酿造 ----

    /**
     * 服务端每 tick: 若投料匹配某配方且输出槽可放, 推进进度; 满 {@link BrewerConstants#BREW_DURATION_TICKS}
     * 则结算产出。进度从 0 起步的那一帧锁定酒类型与品质 (品质按 operator 当前等级 roll, 在线才有意义)。
     */
    public void serverTick() {
        if (level == null || level.isClientSide) {
            return;
        }
        // 输出槽满 (异物或已堆满) -> 不推进 (产物无处可放, 暂停酿造)。
        if (!canPlaceOutput()) {
            return;
        }
        if (pendingType == null) {
            tryStartBrew();
            if (pendingType == null) {
                return; // 投料不匹配: 不酿造 (进度保持 0)。
            }
        }
        // 推进中仍校验配方仍满足 (投料被外部抽走经 onContentsChanged 已 abort, 此为双保险)。
        if (BrewRecipes.match(inventory) != pendingType) {
            abortBrew();
            return;
        }
        progress++;
        setChanged();
        if (progress >= BrewerConstants.BREW_DURATION_TICKS) {
            finishBrew();
        }
    }

    /** 开始帧: match 投料锁定酒类型, 据 operator 等级 roll 锁定品质 (进度仍由统一 progress++ 从 0 推进)。 */
    private void tryStartBrew() {
        WineType type = BrewRecipes.match(inventory);
        if (type == null) {
            return;
        }
        pendingType = type;
        // 满月酿造闪耀几率翻倍 (读原版 level.getMoonPhase()==0; 蹭潮汐 Tide 满月主题)。
        pendingQuality = BrewQualityRoller.roll(operatorBrewerLevel(), level.getMoonPhase() == 0, level.getRandom());
        setChanged();
    }

    /** 中断本轮酿造 (投料变动/配方不再满足): 进度与锁定状态清零, 不产出。 */
    private void abortBrew() {
        progress = 0;
        pendingType = null;
        pendingQuality = null;
        setChanged();
    }

    /** 完成帧: 消耗投料, 用锁定品质产出基酒盖章放进输出槽, 在线 operator 给酿酒经验。 */
    private void finishBrew() {
        if (pendingType == null || pendingQuality == null) {
            abortBrew();
            return;
        }
        // 完成帧重校配方仍精确满足 (中途增投料经 onContentsChanged abort, 此为时序双保险)。
        if (BrewRecipes.match(inventory) != pendingType) {
            abortBrew();
            return;
        }
        WineType type = pendingType;
        WineQuality quality = pendingQuality;

        // 先收尾本轮 (清进度/锁定), 再扣料 —— 否则 consume 触发的 onContentsChanged 会因 progress>0 再次 abort。
        progress = 0;
        pendingType = null;
        pendingQuality = null;

        BrewRecipes.consume(inventory, type);

        ItemStack output = new ItemStack(BrewerItems.itemFor(type), BrewerConstants.BREW_OUTPUT_COUNT);
        WineNbt.stamp(output, quality, operatorUuid);
        mergeIntoOutput(output);

        grantBrewXp();
        setChanged();
    }

    /** 输出槽是否可放本轮产物 (空, 或已是同物同章且未满)。粗判 "空或未满堆"; 精确同章合并交给 mergeIntoOutput。 */
    private boolean canPlaceOutput() {
        ItemStack out = inventory.getStackInSlot(OUTPUT_SLOT);
        if (out.isEmpty()) {
            return true;
        }
        return out.getCount() + BrewerConstants.BREW_OUTPUT_COUNT <= out.getMaxStackSize();
    }

    /** 把产物放进输出槽 (空则直接放; 同物同 NBT 可叠则叠加; 否则丢弃式短路 —— 进度由 canPlaceOutput 已门控不至此)。 */
    private void mergeIntoOutput(ItemStack produced) {
        ItemStack out = inventory.getStackInSlot(OUTPUT_SLOT);
        if (out.isEmpty()) {
            inventory.setStackInSlot(OUTPUT_SLOT, produced);
            return;
        }
        if (ItemStack.isSameItemSameTags(out, produced)
                && out.getCount() + produced.getCount() <= out.getMaxStackSize()) {
            out.grow(produced.getCount());
            inventory.setStackInSlot(OUTPUT_SLOT, out);
            return;
        }
        // 不可叠 (品质/年份章不同): 掉落到方块上方, 不静默吞酒 (canPlaceOutput 仅粗判数量, 此为 NBT 不同的边界)。
        if (level != null && !level.isClientSide) {
            net.minecraft.world.entity.item.ItemEntity drop = new net.minecraft.world.entity.item.ItemEntity(
                    level,
                    worldPosition.getX() + 0.5,
                    worldPosition.getY() + 1.0,
                    worldPosition.getZ() + 0.5,
                    produced);
            level.addFreshEntity(drop);
        }
    }

    /** operator 在线时给其酿酒经验 (离线则 operator==null 短路, 不入账; 品质已在开始帧锁定不受影响)。 */
    private void grantBrewXp() {
        ServerPlayer operator = onlineOperator();
        if (operator == null) {
            return; // 离线: 跳过经验 (酒仍产出, 见 finishBrew)。
        }
        // 茅台闪耀永久特殊: 职业经验加成 (+10%/层, 满 5 层 +50%)。在 brewer 包内的发放点乘原始经验, 不改框架。
        int maotaiLayers = com.miningdim.job.brewer.BrewBuffStore.get(operator.server.overworld())
                .layers(operator.getUUID(), com.miningdim.job.brewer.WineType.MAOTAI);
        long rawXp = Math.round(BREW_XP_RAW
                * com.miningdim.job.brewer.BrewPermanentBuffs.maotaiXpMultiplier(maotaiLayers));
        // 原始酿酒经验 (经框架每日衰减软上限入账, 酿酒师不自折算)。
        JobServices.jobService().grantXp(operator, JobId.BREWER, rawXp);
    }

    /** 每完成一轮酿造给 operator 的原始酿酒经验 (制造职业的产出经验; 框架统一衰减)。 */
    private static final long BREW_XP_RAW = 20L;

    /** 取 operator 的酿酒师等级 (1-10); 离线/未注册返回 1 (新人默认), 用于开始帧 roll 品质。 */
    private int operatorBrewerLevel() {
        ServerPlayer operator = onlineOperator();
        if (operator == null) {
            return BrewQualityRoller.MIN_LEVEL;
        }
        return JobServices.jobService().level(operator, JobId.BREWER);
    }

    /** 取当前在线的 operator (离线/无 operator/非服务端返回 null)。 */
    @Nullable
    private ServerPlayer onlineOperator() {
        if (operatorUuid == null || !(level instanceof ServerLevel serverLevel)) {
            return null;
        }
        return serverLevel.getServer().getPlayerList().getPlayer(operatorUuid);
    }

    // ---- MenuProvider ----

    @Override
    public Component getDisplayName() {
        return Component.translatable("block.miningdim.brewing_station");
    }

    @Nullable
    @Override
    public AbstractContainerMenu createMenu(int windowId, Inventory inv, Player player) {
        return new BrewingStationMenu(windowId, inv, worldPosition);
    }

    // ---- 持久化 (inventory + 进度 + operator + 锁定类型/品质) ----

    private static final String K_INV = "Inv";
    private static final String K_PROGRESS = "Progress";
    private static final String K_OPERATOR = "Operator";
    private static final String K_TYPE = "PendingType";
    private static final String K_QUALITY = "PendingQuality";

    @Override
    protected void saveAdditional(CompoundTag tag) {
        super.saveAdditional(tag);
        tag.put(K_INV, inventory.serializeNBT());
        tag.putInt(K_PROGRESS, progress);
        if (operatorUuid != null) {
            tag.putUUID(K_OPERATOR, operatorUuid);
        }
        if (pendingType != null) {
            tag.putString(K_TYPE, pendingType.id());
        }
        if (pendingQuality != null) {
            tag.putString(K_QUALITY, pendingQuality.id());
        }
    }

    @Override
    public void load(CompoundTag tag) {
        super.load(tag);
        if (tag.contains(K_INV)) {
            inventory.deserializeNBT(tag.getCompound(K_INV));
        }
        progress = tag.getInt(K_PROGRESS);
        operatorUuid = tag.hasUUID(K_OPERATOR) ? tag.getUUID(K_OPERATOR) : null;
        pendingType = tag.contains(K_TYPE) ? WineType.fromId(tag.getString(K_TYPE)) : null;
        pendingQuality = tag.contains(K_QUALITY) ? WineQuality.fromId(tag.getString(K_QUALITY)) : null;
    }
}
