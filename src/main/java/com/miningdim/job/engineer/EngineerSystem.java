package com.miningdim.job.engineer;

import com.miningdim.core.Subsystem;
import com.miningdim.job.engineer.client.ProductionTableScreen;
import com.miningdim.job.engineer.effect.NanoAnvilGuard;
import com.miningdim.job.engineer.effect.NanoEffectTicker;
import com.miningdim.job.engineer.effect.NanoReactorHandler;
import com.miningdim.job.engineer.effect.NanoShieldHandler;
import com.miningdim.job.engineer.armor.PlateArmorDamageHandler;
import com.miningdim.job.engineer.armor.PlateArmorEquipmentHandler;
import com.miningdim.job.engineer.shield.PlasmaShieldHandler;
import com.miningdim.job.engineer.shield.network.PlasmaShieldNetwork;
import net.minecraft.client.gui.screens.MenuScreens;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.fml.config.ModConfig;
import net.minecraftforge.fml.event.config.ModConfigEvent;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 千年工程师子系统入口 (MillenniumEngineer_Mod_DesignSpec 10.1; 模块化铁律 3)。在 register 内完成自己的全部
 * DeferredRegister (Block/Item/BlockEntity/MenuType/CreativeTab) + SERVER 配置 spec + 事件订阅 + 客户端
 * Screen 注册; 跨子系统协作只经职业框架门面 (JobServices) + 易伤等共享 effect, 不 import 他系统实现类。
 *
 * 集成阶段 (本任务不做): 在 MiningDim.registerSubsystems() 追加一行 new EngineerSystem()。
 * 工程师等级/经验/CD 数据走共享职业框架 capability (JobProgress, JobId.ENGINEER), 不新挂 capability
 * (与框架 spec 第 2.3 节一致)。
 *
 * 配置: 因任务铁律禁改中央 config.MiningServerConfig, 工程师自带 SERVER spec (miningdim-engineer.toml),
 * 在此经 ModLoadingContext.registerConfig 注册 (集成阶段若合并进主 config 仅搬运 spec 段, 业务实时 get 不受影响)。
 */
public final class EngineerSystem implements Subsystem {

    private static final Logger LOGGER = LoggerFactory.getLogger("miningdim/engineer");

    @Override
    public void register(IEventBus modBus, IEventBus forgeBus) {
        // 自有 DeferredRegister (注册顺序: Block -> BlockEntity (依赖 Block) -> Item (依赖 Block) -> Menu/Tab)。
        ModEngineerBlocks.register(modBus);
        ModEngineerBlockEntities.register(modBus);
        ModEngineerItems.register(modBus);
        ModEngineerMenus.register(modBus);
        ModEngineerTab.register(modBus);
        ModEngineerSounds.register(modBus);

        // SERVER 配置 spec (10.3 C6: 全部平衡数值进 ForgeConfigSpec)。
        ModLoadingContext.get().registerConfig(ModConfig.Type.SERVER,
                EngineerConfig.SPEC, "miningdim-engineer.toml");
        // 载入与重载后立刻校验插板矩阵长度: defineList 的 Predicate 只管单个元素, 长度写错要到玩家穿戴时
        // 才由 PlayerTickEvent 抛出并打断服务端 tick。只认本 spec, 同总线其它模组的配置事件放行。
        modBus.addListener((ModConfigEvent.Loading event) -> validateOwnConfig(event.getConfig()));
        modBus.addListener((ModConfigEvent.Reloading event) -> validateOwnConfig(event.getConfig()));

        // 平板铸甲师页的 job.engineer.state (档位表/护甲特效/反应堆 CD 只读, 数值实时读 EngineerConfig)。
        EngineerWebUiActions.registerAll();

        // 特效事件订阅 (forgeBus): PlayerTick (重塑/机能修复/护盾) / LivingHurt (护盾免疫窗) /
        // LivingDeath (图腾拦截致死) / AnvilUpdate (禁纳米特效甲铁砧修复)。
        forgeBus.register(new NanoEffectTicker());
        forgeBus.register(new NanoShieldHandler());
        forgeBus.register(new NanoReactorHandler());
        forgeBus.register(new NanoAnvilGuard());
        forgeBus.register(new PlateArmorDamageHandler());
        forgeBus.register(new PlateArmorEquipmentHandler());
        forgeBus.register(new PlasmaShieldHandler());

        // Dedicated packet channel keeps the main channel discriminator sequence untouched.
        modBus.addListener((FMLCommonSetupEvent event) -> event.enqueueWork(PlasmaShieldNetwork::register));

        if (ModList.get().isLoaded("tacz")) {
            com.miningdim.job.engineer.armor.integration.PlateArmorTaczIntegrationBootstrap.assemble(forgeBus);
        }

        // 客户端 Screen 注册 (FMLClientSetupEvent.enqueueWork; 经 DistExecutor 隔离, 防专用服务器触链)。
        modBus.addListener((FMLClientSetupEvent event) ->
                event.enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
                        () -> () -> MenuScreens.register(
                                ModEngineerMenus.PRODUCTION_TABLE.get(), ProductionTableScreen::new))));

        LOGGER.info("[miningdim] armorer subsystem registered (54 plate armors + 18 plasma shields + 3 legacy shield aliases + 6 repair plates + 6 tables + effects + QTE)");
    }

    /** 只校验本子系统自己的 SERVER spec; 同总线上其它模组/子系统的配置事件一律放行。 */
    private static void validateOwnConfig(ModConfig config) {
        if (config.getSpec() == EngineerConfig.SPEC) {
            EngineerConfig.PLATE_ARMOR.validateMatrices();
        }
    }

    @Override
    public String name() {
        return "ArmorerSystem";
    }
}
