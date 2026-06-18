package com.miningdim.job;

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
 * 玩家职业数据 capability 的注册、挂载与跨实例复制 (实现手册 "新 Capability" 范式; 对齐
 * entry.MiningCapabilities)。集中托管 {@link IJobPlayerData}, 对外提供 {@link #get(Player)} 静态取用入口。
 *
 * 事件挂总线 ({@link JobFrameworkSystem#register} 接线):
 *  - {@link RegisterCapabilitiesEvent} (modBus): 注册能力类型;
 *  - {@link AttachCapabilitiesEvent}&lt;Entity&gt; (forgeBus): 给 Player 实体挂 Provider + invalidate 监听;
 *  - {@link PlayerEvent.Clone} (forgeBus): 死亡重生/换维度复制全职业进度 (1.20.1 须 reviveCaps 后读原实体)。
 */
public final class JobCapability {

    /** 包级构造: 仅 JobFrameworkSystem 在 register 内 new 一个实例订阅事件; 静态工具 (get/JOB_DATA) 无需实例。 */
    JobCapability() {
    }

    private static final Logger LOGGER = LoggerFactory.getLogger("miningdim/job");

    /** Provider 挂载键 (AttachCapabilitiesEvent.addCapability)。 */
    private static final ResourceLocation JOB_DATA_ID =
            new ResourceLocation(MiningConstants.MODID, "job_data");

    /** 能力句柄。CapabilityManager.get 在 RegisterCapabilitiesEvent 后非空。 */
    public static final Capability<IJobPlayerData> JOB_DATA =
            CapabilityManager.get(new CapabilityToken<>() {
            });

    /** modBus 事件: 注册能力类型。 */
    @SubscribeEvent
    public void onRegisterCapabilities(RegisterCapabilitiesEvent event) {
        event.register(IJobPlayerData.class);
        LOGGER.info("[miningdim] registered job player data capability");
    }

    /** forgeBus 事件: 给每个 Player 实体挂 Provider 并登记 invalidate 监听。 */
    @SubscribeEvent
    public void onAttachCapabilities(AttachCapabilitiesEvent<Entity> event) {
        if (!(event.getObject() instanceof Player)) {
            return;
        }
        JobPlayerDataProvider provider = new JobPlayerDataProvider();
        event.addCapability(JOB_DATA_ID, provider);
        event.addListener(provider::invalidate);
    }

    /**
     * forgeBus 事件: 玩家克隆 (死亡重生/换维度) 复制全职业进度 (第 2.4 节)。
     * 1.20.1 强制写法: 原实体 caps 在 Clone 时已 invalidate, 必须 reviveCaps 临时恢复后读取, 读完 invalidateCaps。
     */
    @SubscribeEvent
    public void onPlayerClone(PlayerEvent.Clone event) {
        Player original = event.getOriginal();
        Player target = event.getEntity();
        original.reviveCaps();
        try {
            Optional<JobPlayerData> from = rawData(original);
            Optional<JobPlayerData> to = rawData(target);
            if (from.isPresent() && to.isPresent()) {
                to.get().copyFrom(from.get());
            }
        } finally {
            original.invalidateCaps();
        }
    }

    /** 取玩家职业数据接口 (未挂载/已失效时 empty; 调用方据此短路, 不静默掩盖)。 */
    public static Optional<IJobPlayerData> get(Player player) {
        return player.getCapability(JOB_DATA).resolve();
    }

    /** 取底层可变数据 (Clone 复制专用)。 */
    private static Optional<JobPlayerData> rawData(Player player) {
        return player.getCapability(JOB_DATA)
                .resolve()
                .filter(d -> d instanceof JobPlayerData)
                .map(d -> (JobPlayerData) d);
    }
}
