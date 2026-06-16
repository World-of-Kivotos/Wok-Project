package com.miningdim.entry;

import com.miningdim.core.MiningConstants;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraftforge.common.capabilities.Capability;
import net.minecraftforge.common.capabilities.CapabilityManager;
import net.minecraftforge.common.capabilities.CapabilityToken;
import net.minecraftforge.common.capabilities.RegisterCapabilitiesEvent;
import net.minecraftforge.event.AttachCapabilitiesEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Optional;

/**
 * 玩家 Capability 的注册、挂载与跨实例复制 (设计文档 12.5)。集中托管 {@link IMiningPlayerData} 能力,
 * 对外提供 {@link #get(Player)} 静态取用入口, 供 entry/reset/MobPressureSystem 读写玩家回退态与 danger,
 * 不需各处重复 LazyOptional 解包样板。
 *
 * 事件挂总线 (12.5 / Subsystem.register 接线):
 *  - {@link RegisterCapabilitiesEvent} (modBus): 注册能力类型;
 *  - {@link AttachCapabilitiesEvent}<Entity> (forgeBus): 给 Player 实体挂 Provider + invalidate 监听;
 *  - {@link PlayerEvent.Clone} (forgeBus): 死亡重生 / 换维度时复制数据 (1.20.1 须 reviveCaps 后读原实体)。
 */
public final class MiningCapabilities {

    /** 包级构造: 仅 EntrySystem 在 register 内 new 一个实例订阅事件; 静态工具方法 (get/PLAYER_DATA) 无需实例。 */
    MiningCapabilities() {
    }

    private static final Logger LOGGER = LoggerFactory.getLogger("miningdim/entry");

    /** Provider 挂载键 (AttachCapabilitiesEvent.addCapability)。 */
    private static final ResourceLocation PLAYER_DATA_ID =
            new ResourceLocation(MiningConstants.MODID, "player_data");

    /** 能力句柄 (12.5)。CapabilityManager.get 在 RegisterCapabilitiesEvent 后非空。 */
    public static final Capability<IMiningPlayerData> PLAYER_DATA =
            CapabilityManager.get(new CapabilityToken<>() {
            });

    /** modBus 事件: 注册能力类型。 */
    @SubscribeEvent
    public void onRegisterCapabilities(RegisterCapabilitiesEvent event) {
        event.register(IMiningPlayerData.class);
        LOGGER.info("[miningdim] registered player data capability");
    }

    /** forgeBus 事件: 给每个 Player 实体挂 Provider 并登记 invalidate 监听。 */
    @SubscribeEvent
    public void onAttachCapabilities(AttachCapabilitiesEvent<Entity> event) {
        if (!(event.getObject() instanceof Player)) {
            return;
        }
        MiningPlayerDataProvider provider = new MiningPlayerDataProvider();
        event.addCapability(PLAYER_DATA_ID, provider);
        event.addListener(provider::invalidate);
    }

    /**
     * forgeBus 事件: 玩家克隆 (死亡重生 / 换维度) 复制矿山数据 (12.5)。
     * 1.20.1 强制写法: 原实体 caps 在 Clone 时已 invalidate, 必须 reviveCaps 临时恢复后读取, 读完 invalidateCaps。
     * 死亡重生 (wasDeath=true) 与换维度 (wasDeath=false) 都全字段复制 —— 回退态/currentInstanceId/danger
     * 跨这两种重建都需保留 (14.6 重连恢复依赖 currentInstanceId 仍在)。
     */
    @SubscribeEvent
    public void onPlayerClone(PlayerEvent.Clone event) {
        Player original = event.getOriginal();
        Player target = event.getEntity();
        original.reviveCaps();
        try {
            Optional<MiningPlayerData> from = rawData(original);
            Optional<MiningPlayerData> to = rawData(target);
            if (from.isPresent() && to.isPresent()) {
                to.get().copyFrom(from.get());
            }
        } finally {
            original.invalidateCaps();
        }
    }

    /** 取玩家矿山数据接口 (未挂载/已失效时 empty; 调用方据此短路, 不静默掩盖)。 */
    public static Optional<IMiningPlayerData> get(Player player) {
        return player.getCapability(PLAYER_DATA).resolve();
    }

    /** 取底层可变数据 (Clone 复制专用, 直接拿 MiningPlayerData 走 copyFrom)。 */
    private static Optional<MiningPlayerData> rawData(Player player) {
        // LazyOptional.map 返回 java.util.Optional; 仅当底层实现确为 MiningPlayerData 时取出。
        return player.getCapability(PLAYER_DATA)
                .resolve()
                .filter(d -> d instanceof MiningPlayerData)
                .map(d -> (MiningPlayerData) d);
    }
}
