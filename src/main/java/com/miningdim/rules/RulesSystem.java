package com.miningdim.rules;

import com.miningdim.core.MiningConstants;
import com.miningdim.core.Subsystem;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.Block;
import net.minecraftforge.event.entity.player.PlayerSetSpawnEvent;
import net.minecraftforge.event.level.BlockEvent;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * R7 矿山维度规则子系统入口 (模块化铁律 3)。在矿山维度 (miningdim:mining) 内, 放置非白名单方块
 * (rules.placeWhitelist) 一律取消并提示放置者; 指向矿山维度的重生点设置同样会被取消。
 *
 * 监听两个 forge 放置事件与重生点设置事件:
 *  - {@link BlockEvent.EntityPlaceEvent}: 单方块放置 (绝大多数情形)。
 *  - {@link BlockEvent.EntityMultiPlaceEvent}: 单次放置触发多方块 (如床), Forge 以独立事件类分发,
 *    EntityPlaceEvent 监听器收不到它, 故必须单独订阅 (EntityMultiPlaceEvent extends EntityPlaceEvent,
 *    但 EventBus 按精确事件类派发)。
 *
 * 本子系统无对外 core 门面 (R7 无对应 IXxx), 故不向 MiningServices 注入服务, 仅做事件接线;
 * 与 EconomySystem/ErrorSystem 同属"无对外门面"的事件型子系统。跨子系统只读 IMiningConfig
 * (经 PlacementRules), 不 import 其他子系统实现类 (铁律 2)。
 *
 * 线程: 放置事件回调在服务端主线程 (维度内放置必经服务端权威路径)。
 */
public final class RulesSystem implements Subsystem {

    private static final Logger LOGGER = LoggerFactory.getLogger("miningdim/rules");

    @Override
    public void register(IEventBus modBus, IEventBus forgeBus) {
        // 纯 forge 总线运行期事件 (方块放置与重生点设置), 不涉及 mod 总线注册。
        forgeBus.register(this);
        LOGGER.info("[miningdim] rules subsystem registered (mining-dimension placement and respawn rules enforced)");
    }

    @SubscribeEvent
    public void onEntityPlace(BlockEvent.EntityPlaceEvent event) {
        enforce(event, event.getPlacedBlock().getBlock());
    }

    @SubscribeEvent
    public void onEntityMultiPlace(BlockEvent.EntityMultiPlaceEvent event) {
        // 多方块放置 (如床): getPlacedBlock 为首个 snapshot 的方块, 足以代表本次放置的方块种类。
        enforce(event, event.getPlacedBlock().getBlock());
    }

    @SubscribeEvent
    public void onSetSpawn(PlayerSetSpawnEvent event) {
        // 判据是重生点指向的维度, 不是玩家当前所在维度: 人在矿洞里把重生点设回主世界是正常操作。
        // 清除重生点时 Forge 会把 null 坐标对应的维度改为主世界, 因此清除已有重生点永远不会被此闸拦截。
        if (!event.getSpawnLevel().equals(MiningConstants.MINING_LEVEL)) {
            return;
        }
        event.setCanceled(true);
        if (event.getEntity() instanceof ServerPlayer player) {
            player.sendSystemMessage(Component.translatable("message.miningdim.rules.spawn_denied"));
        }
    }

    /**
     * 统一判定与拦截: 仅当事件发生在矿山维度且放置方块不在白名单时取消, 并提示放置者 (若为玩家)。
     * 维度外/客户端侧/白名单内一律放行。
     */
    private void enforce(BlockEvent.EntityPlaceEvent event, Block placed) {
        if (!isMiningDimension(event.getLevel())) {
            return;
        }
        if (PlacementRules.isPlacementAllowed(placed)) {
            return;
        }
        event.setCanceled(true);
        // 提示放置者: 仅当放置实体是服务端玩家时发提示 (发射器/活塞等非玩家放置无对象可提示)。
        Entity placer = event.getEntity();
        if (placer instanceof ServerPlayer player) {
            player.sendSystemMessage(Component.translatable("message.miningdim.rules.placement_denied"));
        }
    }

    /**
     * 事件发生维度是否为矿山维度。EntityPlaceEvent 的 getLevel() 返回 LevelAccessor (无 dimension()),
     * 维度键挂在 Level 上, 故先窄化到 Level 再比对维度键; 客户端侧 Level (isClientSide) 不参与判定。
     */
    private boolean isMiningDimension(LevelAccessor levelAccessor) {
        if (!(levelAccessor instanceof Level level) || level.isClientSide()) {
            return false;
        }
        return level.dimension().equals(MiningConstants.MINING_LEVEL);
    }
}
