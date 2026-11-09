package com.miningdim.freshness;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.event.level.BlockEvent;
import net.minecraftforge.event.level.ChunkDataEvent;
import net.minecraftforge.event.level.ChunkEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

/**
 * 放置食物记录的三处接线 (Spoilage_Granary_DesignSpec「物品数据与存档」「观察点」)。
 *
 * <ul>
 *   <li><b>摆下去</b>: 玩家把易腐品摆成方块时记一笔 (位置 → 方块 id + 批次时刻 + 已停表小时);</li>
 *   <li><b>区块存档</b>: 跟着区块存盘 (ChunkDataEvent.Save)、开档读回来 (Load)、卸载丢掉内存那份 (Unload);</li>
 *   <li><b>继承</b>在 {@link FreshnessLootModifier} 里 (掉落生成时按位置取记录, 取完就忘)。</li>
 * </ul>
 *
 * 只在服务端做: 客户端没有权威账本, 也不该往别人的存档里写东西。
 */
public final class FreshnessPlacedFoodHooks {

    private FreshnessPlacedFoodHooks() {
    }

    /** 摆下一个易腐品方块: 记一笔 (不是易腐品就什么都不做)。 */
    @SubscribeEvent
    public static void onBlockPlaced(BlockEvent.EntityPlaceEvent event) {
        if (event.isCanceled() || !(event.getLevel() instanceof ServerLevel level)) {
            return;
        }
        if (!(event.getEntity() instanceof Player player)) {
            return;
        }
        ItemStack held = heldItem(player, event.getPlacedBlock());
        if (held.isEmpty()) {
            return;
        }
        FreshnessPlacedFood.record(level, event.getPos(), held, event.getPlacedBlock());
    }

    @SubscribeEvent
    public static void onChunkLoad(ChunkDataEvent.Load event) {
        if (!(event.getLevel() instanceof ServerLevel level)) {
            return;
        }
        FreshnessPlacedFood.loadFrom(level.dimension(), event.getChunk().getPos(), event.getData());
    }

    @SubscribeEvent
    public static void onChunkSave(ChunkDataEvent.Save event) {
        if (!(event.getLevel() instanceof ServerLevel level)) {
            return;
        }
        FreshnessPlacedFood.saveTo(level.dimension(), event.getChunk().getPos(), event.getData());
    }

    @SubscribeEvent
    public static void onChunkUnload(ChunkEvent.Unload event) {
        if (!(event.getLevel() instanceof ServerLevel level)) {
            return;
        }
        FreshnessPlacedFood.unload(level.dimension(), event.getChunk().getPos());
    }

    /** 只包住目标食物方块的 use，来源在异常路径也不会泄漏到下一次交互。 */
    public static net.minecraft.world.InteractionResult usePlacedFood(
            net.minecraft.world.level.block.state.BlockState state, net.minecraft.world.level.Level level,
            Player player, InteractionHand hand, net.minecraft.world.phys.BlockHitResult hit) {
        if (!(level instanceof ServerLevel serverLevel) || !FreshnessConfig.stampingEnabled()) {
            return state.use(level, player, hand, hit);
        }
        ItemStack source = FreshnessPlacedFood.sourceAt(serverLevel, hit.getBlockPos());
        if (source.isEmpty()) {
            return state.use(level, player, hand, hit);
        }
        long now = FreshnessClockData.nowMinute(serverLevel);
        if (FreshnessConfig.replacementEnabled() && FreshnessCheck.isRottenNow(source, now)) {
            FreshnessPlacedFood.forget(serverLevel.dimension(), hit.getBlockPos());
            serverLevel.removeBlock(hit.getBlockPos(), false);
            net.minecraft.world.level.block.Block.popResource(serverLevel, hit.getBlockPos(), FreshnessSettle.rotStack(1));
            return net.minecraft.world.InteractionResult.CONSUME;
        }
        FreshnessSourceContext.begin(source, FreshnessProcessing.NO_BONUS);
        try {
            return state.use(level, player, hand, hit);
        } finally {
            FreshnessSourceContext.end();
            if (serverLevel.getBlockState(hit.getBlockPos()).isAir()) {
                FreshnessPlacedFood.forget(serverLevel.dimension(), hit.getBlockPos());
            }
        }
    }

    /**
     * 玩家手上那个「就是刚摆下去的那个方块」的物品。
     *
     * 事件不带手, 所以两只手都看一眼; 都对不上 (比如方块是被别的途径摆下的) 就返回空, 什么都不记。
     */
    private static ItemStack heldItem(Player player, net.minecraft.world.level.block.state.BlockState placed) {
        for (InteractionHand hand : InteractionHand.values()) {
            ItemStack stack = player.getItemInHand(hand);
            if (!stack.isEmpty() && stack.getItem() instanceof net.minecraft.world.item.BlockItem blockItem
                    && blockItem.getBlock() == placed.getBlock()) {
                return stack;
            }
        }
        return ItemStack.EMPTY;
    }
}
