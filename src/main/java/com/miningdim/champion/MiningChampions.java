package com.miningdim.champion;

import com.miningdim.core.MiningConstants;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraftforge.common.capabilities.Capability;
import net.minecraftforge.common.capabilities.CapabilityManager;
import net.minecraftforge.common.capabilities.CapabilityToken;
import net.minecraftforge.common.capabilities.RegisterCapabilitiesEvent;
import net.minecraftforge.event.AttachCapabilitiesEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Optional;

/**
 * 自研冠军 Capability 的注册、挂载与取用中心 (取代 Champions 的 {@code ChampionCapability})。集中托管
 * {@link MiningChampionData}, 对外提供 {@link #get(LivingEntity)} / {@link #isChampion(LivingEntity)} 静态入口,
 * 供全部效果 handler (减伤/攻击/DoT/自身被动/血条/粒子/奖励) 读星级 + 词条→品质, 不再触任何 Champions 类。
 *
 * 事件挂总线 (由 {@link ChampionSystem#register} 把本实例同时挂 modBus + forgeBus):
 *  - {@link RegisterCapabilitiesEvent} (modBus): 注册能力类型;
 *  - {@link AttachCapabilitiesEvent}&lt;Entity&gt; (forgeBus): 给每个 {@link Mob} 挂 Provider + invalidate 监听
 *    (玩家非 Mob 不挂; 普通怪也挂但 star=0 非冠军, promoter 盖章的才 star≥1)。
 *
 * 与玩家 capability ({@code entry.MiningCapabilities}) 同范式。本类不 import 任何 top.theillusivec4.champions.*,
 * 故 dev GameTest 可加载 + 可对 mock Mob 挂 cap 断言 (自研后 integration 层脱离"compileOnly mod dev 不加载"限制)。
 */
public final class MiningChampions {

    /** 由 {@link ChampionSystem#register} new 一个实例挂双总线; 静态取用方法无需实例。 */
    MiningChampions() {
    }

    private static final Logger LOGGER = LoggerFactory.getLogger("miningdim/champion");

    /** Provider 挂载键 (AttachCapabilitiesEvent.addCapability)。 */
    private static final ResourceLocation CHAMPION_DATA_ID =
            new ResourceLocation(MiningConstants.MODID, "champion_data");

    /** 能力句柄; CapabilityManager.get 在 RegisterCapabilitiesEvent 后非空。 */
    public static final Capability<MiningChampionData> CHAMPION_DATA =
            CapabilityManager.get(new CapabilityToken<>() {
            });

    /** modBus 事件: 注册能力类型。 */
    @SubscribeEvent
    public void onRegisterCapabilities(RegisterCapabilitiesEvent event) {
        event.register(MiningChampionData.class);
        LOGGER.info("[champion] registered champion data capability (self-hosted, no Champions dependency)");
    }

    /** forgeBus 事件: 给每个 Mob 挂 Provider (普通怪默认非冠军)。玩家/非 Mob 实体不挂。 */
    @SubscribeEvent
    public void onAttachCapabilities(AttachCapabilitiesEvent<Entity> event) {
        if (!(event.getObject() instanceof Mob)) {
            return;
        }
        MiningChampionProvider provider = new MiningChampionProvider();
        event.addCapability(CHAMPION_DATA_ID, provider);
        event.addListener(provider::invalidate);
    }

    /** 取某实体的冠军数据 (未挂载/非 Mob 时 empty; 调用方据此短路)。 */
    public static Optional<MiningChampionData> get(LivingEntity entity) {
        if (entity == null) {
            return Optional.empty();
        }
        return entity.getCapability(CHAMPION_DATA).resolve();
    }

    /** 是否本工程盖章的冠军 (有 capability 且 star≥1)。 */
    public static boolean isChampion(LivingEntity entity) {
        return get(entity).map(MiningChampionData::isChampion).orElse(false);
    }
}
