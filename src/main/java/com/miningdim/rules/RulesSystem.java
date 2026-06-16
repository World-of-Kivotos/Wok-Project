package com.miningdim.rules;

import com.miningdim.core.MiningConstants;
import com.miningdim.core.Subsystem;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.Block;
import net.minecraftforge.event.level.BlockEvent;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * R7 放置规则子系统入口 (模块化铁律 3)。在矿山维度 (miningdim:mining) 内, 放置非白名单方块
 * (rules.placeWhitelist) 一律取消并提示放置者; 挖矿/破坏不受限。
 *
 * 监听两个 forge 放置事件:
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
        // 纯 forge 总线运行期事件 (方块放置), 不涉及 mod 总线注册。
        forgeBus.register(this);
        LOGGER.info("[miningdim] rules subsystem registered (mining-dimension placement whitelist enforced)");
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
