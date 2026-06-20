package com.miningdim.job.chef;

import com.miningdim.core.Subsystem;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.config.ModConfig;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 厨师子系统入口 (Chef_Job_DesignSpec 第九章; 模块化铁律 3 自注册模式)。
 *
 * 集成阶段把 {@code subsystems.add(new com.miningdim.job.chef.ChefSystem())} 加进
 * MiningDim.registerSubsystems() 一行即接入 (本任务不改 MiningDim.java)。
 *
 * register 内自注册:
 *  - 自己 package 的 DeferredRegister: ChefBlocks/ChefItems/ChefBlockEntities/ChefTabs (modBus);
 *    MenuType 经共享 ModMenus DeferredRegister (由 JobFrameworkSystem 接 modBus, 厨师只往其上登记, 故触类
 *    {@link ChefMenus} 确保静态登记被收集);
 *  - 厨师 SERVER 配置 SPEC (自己的 toml, 不碰中央 MiningServerConfig);
 *  - forge 事件订阅: 吃菜结算 / tooltip / 窗口效果状态机 / 抗击退 / 爆炸减伤 / 耐饥;
 *  - 厨师专属 SimpleChannel packet 注册 (FMLCommonSetupEvent.enqueueWork 线程安全窗口);
 *  - 客户端 MenuScreens.register (FMLClientSetupEvent.enqueueWork, 经 DistExecutor 隔离)。
 *
 * 跨子系统 (经验/货币) 只经 core 门面 + JobServices/IEconomyService, 不 import 对方实现类。
 */
public final class ChefSystem implements Subsystem {

    private static final Logger LOGGER = LoggerFactory.getLogger("miningdim/chef");

    private final ChefWindowEffectState windowState = new ChefWindowEffectState();

    @Override
    public void register(IEventBus modBus, IEventBus forgeBus) {
        // 厨师 SERVER 配置 (自己的 toml; 不碰中央 MiningServerConfig)。
        ModLoadingContext.get().registerConfig(ModConfig.Type.SERVER, ChefConfig.SPEC, "miningdim-chef.toml");

        // 自 package DeferredRegister。
        ChefBlocks.register(modBus);
        ChefItems.register(modBus);
        ChefBlockEntities.register(modBus);
        ChefTabs.register(modBus);
        // 触类: 确保 ChefMenus 的 static MenuType 登记被收集进共享 ModMenus.MENUS (其 .register(modBus)
        // 由 JobFrameworkSystem 调; 此处引用静态字段触发类加载, 使登记在 mod-bus 注册前已入 pending map)。
        touch(ChefMenus.SEASONING_MENU);

        // 设置阶段: packet 注册 + 黑名单诊断 (modBus, 主线程安全窗口)。
        modBus.addListener(this::onCommonSetup);
        modBus.addListener(this::onClientSetup);

        // forge 事件订阅。
        forgeBus.register(new ChefConsumeHandler());
        forgeBus.register(new ChefTooltipHandler());
        forgeBus.register(new ChefKnockbackHandler());
        forgeBus.register(new ChefHungerHandler());
        forgeBus.register(windowState);
        // 凝脂 (爆炸减伤): 迁入玩家减伤单点结算, 不再自挂 LivingHurtEvent (减伤统一, 见 ChefGreaseReduction)。
        com.miningdim.combat.PlayerDamageReduction.register(new ChefGreaseReduction());

        LOGGER.info("[miningdim] chef subsystem registered (5 seasoning tables + minigame + effects + amplify blacklist)");
    }

    private void onCommonSetup(FMLCommonSetupEvent event) {
        event.enqueueWork(() -> {
            ChefNetwork.register();
            SeasoningBlacklist.logLoadedFidCombatEffects();
        });
    }

    private void onClientSetup(FMLClientSetupEvent event) {
        // 客户端 Screen 注册经 DistExecutor 隔离 (防专用服务器加载期触链; 与 JobSyncS2C.handle 同范式)。
        event.enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
                () -> () -> com.miningdim.job.chef.client.ChefClientSetup.registerScreens()));
    }

    /** 触发 RegistryObject 所在类的静态初始化 (使其 DeferredRegister.register(name,...) 登记被收集)。 */
    private static void touch(Object registryObject) {
        // 引用即足以触发类加载; 无运行逻辑。参数仅为强制求值, 防 JIT 优化掉。
        java.util.Objects.requireNonNull(registryObject);
    }

    @Override
    public String name() {
        return "ChefSystem";
    }
}
