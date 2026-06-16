package com.miningdim.config;

import com.miningdim.core.MiningServices;
import com.miningdim.core.Subsystem;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.event.config.ModConfigEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 配置子系统入口 (模块化铁律 3, IMiningConfig 提供方)。
 *
 * register 时机 (16.3): 必须在 mod 构造期完成 ModLoadingContext.registerConfig (SERVER + CLIENT),
 * 故本方法体内直接注册两个 spec, 并把 ModConfig 门面注入 MiningServices。跨字段校验 (16.7) 挂在
 * ModConfigEvent.Loading/Reloading 监听上, spec 加载/重载后触发。
 *
 * 注入顺序: ConfigSystem 应排在依赖 config() 的子系统之前 (多数子系统在事件回调期才读 config, 不受约束;
 * 但凡在 register 期就读配置者, 必须让 ConfigSystem 在前)。
 */
public final class ConfigSystem implements Subsystem {

    private static final Logger LOGGER = LoggerFactory.getLogger("miningdim/ConfigSystem");

    private final ModConfig config = new ModConfig();

    @Override
    public void register(IEventBus modBus, IEventBus forgeBus) {
        ModLoadingContext ctx = ModLoadingContext.get();
        ctx.registerConfig(net.minecraftforge.fml.config.ModConfig.Type.SERVER,
                MiningServerConfig.SPEC, "miningdim-server.toml");
        ctx.registerConfig(net.minecraftforge.fml.config.ModConfig.Type.CLIENT,
                MiningClientConfig.SPEC, "miningdim-client.toml");

        // 门面注入: 构造期绑定, 业务系统按接口取用。
        MiningServices.registerConfig(config);

        // 16.7 跨字段一致性校验: Loading 与 Reloading 都校验 (改值后立即复核)。
        modBus.addListener(this::onConfigLoad);
        modBus.addListener(this::onConfigReload);
    }

    private void onConfigLoad(ModConfigEvent.Loading event) {
        if (event.getConfig().getSpec() == MiningServerConfig.SPEC) {
            validateServerConfig();
        }
    }

    private void onConfigReload(ModConfigEvent.Reloading event) {
        if (event.getConfig().getSpec() == MiningServerConfig.SPEC) {
            validateServerConfig();
        }
    }

    /**
     * 16.7 校验表 (R2 后):
     *  - 分层有序校验已删除: 难度由所在 region 决定, 不再有 worldY 子盒, layer.* 配置不存在。
     *  - danger 权重非全零: 三权重之和 > 0, 违反记警告 (实际回退由 danger 系统读值时按 weightZone 兜底, 此处仅告警)。
     *  - 实例容量提示: globalCap * maxPartySize 仅日志。
     */
    private void validateServerConfig() {
        double weightSum = MiningServerConfig.DANGER_WEIGHT_ZONE.get()
                + MiningServerConfig.DANGER_WEIGHT_TIME.get()
                + MiningServerConfig.DANGER_WEIGHT_ORE.get();
        if (weightSum <= 0.0) {
            LOGGER.warn("[miningdim] danger weights sum to {} (all zero); danger eval will rely on zone fallback",
                    weightSum);
        }

        long capacity = (long) MiningServerConfig.GLOBAL_CAP.get() * MiningServerConfig.MAX_PARTY_SIZE.get();
        LOGGER.info("[miningdim] config validated; theoretical player capacity = globalCap*maxPartySize = {}", capacity);
    }
}
