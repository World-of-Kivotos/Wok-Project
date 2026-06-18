package com.miningdim.job.farmer;

import com.miningdim.core.Subsystem;
import com.miningdim.job.JobId;
import com.miningdim.job.JobServices;
import com.miningdim.job.farmer.block.FarmerBlocks;
import com.miningdim.job.farmer.block.FarmerCropBlock;
import com.miningdim.job.farmer.block.FarmerFarmlandBlock;
import com.miningdim.job.farmer.item.FarmerCreativeTab;
import com.miningdim.job.farmer.item.FarmerItems;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.event.entity.player.BonemealEvent;
import net.minecraftforge.event.level.BlockEvent;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 农夫职业子系统入口 (FarmingXP_Mod_DesignSpec; 模块化铁律 3: 自持 DeferredRegister + 自订阅事件)。
 *
 * 自注册:
 *  - 方块/物品/创造页 DeferredRegister ({@link FarmerBlocks}/{@link FarmerItems}/{@link FarmerCreativeTab}, modBus);
 *  - 收获经验结算 ({@link #onCropHarvested}, forgeBus BreakEvent): 只认 mod 作物成熟态破坏;
 *  - 放置上限 + 档位门控 ({@link #onFarmlandPlace}, forgeBus EntityPlaceEvent): 超限/未解锁拒放;
 *  - 耕地破坏回收计数 ({@link #onFarmlandBroken}, 复用 BreakEvent);
 *  - 反作弊骨粉 ({@link #onBonemeal}, forgeBus BonemealEvent): mod 作物禁骨粉。
 *
 * 不持有玩家进度: 经验走共享 {@link JobServices#jobService()} 入账 (JobId.FARMER), 衰减/翻日/升级由框架裁决;
 * 耕地放置计数走 {@link FarmerSavedData} (overworld 持久层)。
 *
 * 集成阶段 (本任务不做): 把本子系统加进 MiningDim.registerSubsystems() 一行 (见结构化输出 subsystemFqn)。
 */
public final class FarmerSystem implements Subsystem {

    private static final Logger LOGGER = LoggerFactory.getLogger("miningdim/job/farmer");

    @Override
    public void register(IEventBus modBus, IEventBus forgeBus) {
        FarmerBlocks.register(modBus);
        FarmerItems.register(modBus);
        FarmerCreativeTab.register(modBus);
        forgeBus.register(this);
        LOGGER.info("[miningdim] farmer job subsystem registered (5 farmland tiers + mod wheat + harvest xp + placement cap)");
    }

    // ============================================================
    // 收获经验结算 (第二章: 只认 mod 作物成熟态破坏并掉落; 第十章: 防重复刷取)
    // ============================================================

    /**
     * mod 作物成熟态被玩家破坏时结算农夫经验。原始经验 = 单作物经验 × 该档耕地产量 (表B):
     *  - 仅 {@link FarmerCropBlock} 且处于成熟态 (isMaxAge) 才结算 (未成熟破坏经验 = 0);
     *  - 下方耕地档位决定产量 (高档产更多 -> 单次结算原始经验更高);
     *  - 入账走框架 {@link JobServices#jobService()#grantXp}, 受每日有效经验软上限衰减 (2000 系) 约束。
     *
     * 经验与作物掉落解耦 (第二章): 本法只算经验, 不动掉落 (loot table 照常掉小麦), 故软上限只削经验不削小麦。
     */
    @SubscribeEvent
    public void onCropHarvested(BlockEvent.BreakEvent event) {
        if (event.isCanceled()) {
            return;
        }
        if (!(event.getPlayer() instanceof ServerPlayer player)) {
            return; // 仅服务端玩家破坏结算 (假玩家/客户端不结算)。
        }
        BlockState state = event.getState();
        if (!(state.getBlock() instanceof FarmerCropBlock crop)) {
            return; // 非 mod 作物 (含耕地破坏走 onFarmlandBroken): 不结算经验。
        }
        if (!crop.isMaxAge(state)) {
            return; // 未成熟破坏: 经验结算 = 0 (第十章: 只认成熟态破坏掉落)。
        }
        if (!(event.getLevel() instanceof ServerLevel level)) {
            return;
        }
        FarmerTier tier = FarmerCropBlock.tierBelow(player.level(), event.getPos());
        if (tier == null) {
            return; // 下方不是 mod 耕地 (原版耕地上的 mod 作物不产经验, 反扩建): 经验 = 0。
        }
        int yield = tier.yieldPerHarvest();

        // 经验入账 (表B: 单作物经验 × 产量), 受框架每日软上限衰减。
        long rawXp = (long) FarmerConstants.SINGLE_CROP_XP * yield;
        JobServices.jobService().grantXp(player, JobId.FARMER, rawXp);

        // 小麦掉落由本处单一权威发放 (loot table 只补种种子, 不掉小麦), 株数 = 该档产量, 与经验/经济计数严格一致
        // (第七章: 小麦产量纯由方块上限 × 单块速率 × 产量决定, 不受经验软上限削减)。
        net.minecraft.world.level.block.Block.popResource(level, event.getPos(),
                new net.minecraft.world.item.ItemStack(FarmerItems.FARMER_WHEAT.get(), yield));
    }

    // ============================================================
    // 放置上限 + 档位门控 (表A 方块上限 + 表B 解锁等级; 设计目标 2/5 反扩建硬封顶)
    // ============================================================

    /**
     * 放置 mod 耕地时校验等级门控 (档位是否解锁) 与方块上限 (已放数是否到顶), 超限/未解锁直接取消放置。
     * 通过则计数 +1 (全局按玩家 UUID 计, 见 {@link FarmlandPlacementGuard} 口径裁决)。
     */
    @SubscribeEvent
    public void onFarmlandPlace(BlockEvent.EntityPlaceEvent event) {
        if (event.isCanceled()) {
            return;
        }
        if (!(event.getEntity() instanceof ServerPlayer player)) {
            return; // 仅服务端玩家放置受闸门约束 (活塞/掉落方块等非玩家放置不计入也不受限)。
        }
        if (!(event.getPlacedBlock().getBlock() instanceof FarmerFarmlandBlock farmland)) {
            return; // 非 mod 耕地: 不约束。
        }
        ServerLevel overworld = player.server.overworld();
        FarmerSavedData data = FarmerSavedData.get(overworld);
        int currentLevel = JobServices.jobService().level(player, JobId.FARMER);
        int placed = data.placedCount(player.getUUID());

        FarmlandPlacementGuard.PlaceResult result =
                FarmlandPlacementGuard.checkPlacement(farmland.tier(), currentLevel, placed);
        switch (result) {
            case REJECT_TIER_LOCKED:
                event.setCanceled(true);
                player.displayClientMessage(
                        Component.translatable("message.miningdim.farmer.tier_locked",
                                farmland.tier().unlockLevel()), true);
                return;
            case REJECT_CAP_REACHED:
                event.setCanceled(true);
                player.displayClientMessage(
                        Component.translatable("message.miningdim.farmer.cap_reached",
                                FarmlandPlacementGuard.capForLevel(currentLevel)), true);
                return;
            case ALLOW:
            default:
                data.increment(player.getUUID());
        }
    }

    /**
     * 破坏 mod 耕地时回收放置计数 (-1)。与 {@link #onCropHarvested} 复用同一 BreakEvent: 该处只认作物,
     * 本处只认耕地, 互不重叠。仅服务端玩家破坏计入回收 (与放置侧对称: 玩家放/玩家破)。
     */
    @SubscribeEvent
    public void onFarmlandBroken(BlockEvent.BreakEvent event) {
        if (event.isCanceled()) {
            return;
        }
        if (!(event.getPlayer() instanceof ServerPlayer player)) {
            return;
        }
        if (!(event.getState().getBlock() instanceof FarmerFarmlandBlock)) {
            return;
        }
        ServerLevel overworld = player.server.overworld();
        FarmerSavedData.get(overworld).decrement(player.getUUID());
    }

    // ============================================================
    // 反作弊: mod 作物禁骨粉 (第十章; 否则每日软上限形同虚设)
    // ============================================================

    /**
     * 骨粉用于 mod 作物时直接取消事件 (不消耗骨粉、不成长、不产经验)。方块层 {@link FarmerCropBlock} 已把
     * isValidBonemealTarget 设 false (vanilla 不会推进), 本处再取消事件作二次封堵 (确保任何骨粉路径都被拦)。
     */
    @SubscribeEvent
    public void onBonemeal(BonemealEvent event) {
        if (event.getBlock().getBlock() instanceof FarmerCropBlock) {
            event.setCanceled(true);
        }
    }

    @Override
    public String name() {
        return "FarmerSystem";
    }
}
