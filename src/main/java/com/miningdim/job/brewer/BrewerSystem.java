package com.miningdim.job.brewer;

import com.miningdim.core.Subsystem;
import com.miningdim.job.brewer.cellar.WineCellarRegistry;
import com.miningdim.job.brewer.cellar.client.WineCellarClient;
import com.miningdim.job.brewer.station.BrewingStationRegistry;
import com.miningdim.job.brewer.station.client.BrewingStationClient;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.config.ModConfig;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Objects;

/**
 * 酿酒师子系统入口 (模块化铁律 3 自注册)。等级/经验走共享职业框架 capability (JobId.BREWER), 故须在
 * JobFrameworkSystem 之后注册 (见 MiningDim.registerSubsystems)。
 *
 * 本子系统是"至少七天周期性制造职业": 酿酒台酿基酒 (按等级 roll 品质) -> 酒窖箱陈酿年份 (干小麦门控, 年份
 * 时钟用现实挂钟、潮汐 Tide 味保留在月相加成) -> 喝酒按 S = 年份×品质系数 获增益, 闪耀档触发永久 (一条命) 特殊增益。
 *
 * 接入: 酒物品/创造栏/喝酒效果 (阶段 2, 喝酒经 WineItem.finishUsingItem 直接结算, 无需 forgeBus) + 酿酒台
 * (阶段 3) + 酒窖箱 (阶段 4) 的 Block/Item/BlockEntity 自有 DeferredRegister + MenuType (经共享 ModMenus) +
 * 客户端 Screen (经 DistExecutor 隔离) + 闪耀永久层数系统 (阶段 5: 喝闪耀酒固化永久层 -> 9 种永久特殊,
 * 死亡清 / 登录重挂 / 周期回血经 forgeBus 接线; 伏特加烈酒钝感注册为命名减伤源)。
 */
public final class BrewerSystem implements Subsystem {

    private static final Logger LOGGER = LoggerFactory.getLogger("miningdim/brewer");

    /** 金酒永久最大生命管理器 (独立固定 UUID, 不同于塔罗; 阶段 5(iv))。 */
    private final GinMaxHealthManager ginMaxHealth = new GinMaxHealthManager();
    /** 9 种闪耀永久特殊的施加/清除/周期维持 (持金酒 maxHP 管理器)。 */
    private final BrewPermanentBuffs permanentBuffs = new BrewPermanentBuffs(ginMaxHealth);
    /** 死亡清 + 登录重挂 + 周期回血事件接线。 */
    private final BrewPermanentBuffHandlers permanentBuffHandlers = new BrewPermanentBuffHandlers(permanentBuffs);

    @Override
    public void register(IEventBus modBus, IEventBus forgeBus) {
        // 酿酒师 SERVER 配置 (自己的 toml; 不碰中央 MiningServerConfig; F086)。
        ModLoadingContext.get().registerConfig(ModConfig.Type.SERVER, BrewerConfig.SPEC, "miningdim-brewer.toml");

        BrewerItems.register(modBus);
        BrewerTab.register(modBus);
        // 阶段 3/4: 酿酒台 + 酒窖箱 (各自有 Block/Item/BE DeferredRegister; MenuType 走共享 ModMenus.MENUS)。
        BrewingStationRegistry.register(modBus);
        WineCellarRegistry.register(modBus);
        // touch MenuType RegistryObject 强制类加载, 使其登记被收集进共享 ModMenus.MENUS 的 pending map
        // (ModMenus.register(modBus) 由 JobFrameworkSystem 调; 范式同 ChefSystem.touch)。
        touch(BrewingStationRegistry.STATION_MENU);
        touch(WineCellarRegistry.WINE_CELLAR_MENU);

        // 阶段 5: 永久层数系统 + 9 种闪耀永久特殊。
        // 运行期服务装配 (本包静态访问点; 喝酒固化/酿酒台发茅台加成经验经此取用)。
        BrewerRuntime.init(ginMaxHealth, permanentBuffs);
        // 死亡清 / 登录重挂 / 周期回血 + ServerStopping reset 接线。
        forgeBus.register(permanentBuffHandlers);
        forgeBus.register(this);
        // 伏特加烈酒钝感: 注册为命名减伤源连乘进玩家受击单点结算 (范式同凝脂)。
        com.miningdim.combat.PlayerDamageReduction.register(new VodkaNumbness());

        // 客户端 Screen 注册 (FMLClientSetupEvent + DistExecutor 隔离, 专用服务器永不触客户端类)。
        modBus.addListener(this::onClientSetup);
        // 平板酿酒师页的 job.brewer.state (只读层数/配方, 进程级静态注册)。
        BrewerWebUiActions.registerAll();
        LOGGER.info("[miningdim] brewer subsystem registered (items + drink effects + brewing station + wine cellar + permanent buffs + job.brewer.state action)");
    }

    @SubscribeEvent
    public void onServerStopping(ServerStoppingEvent event) {
        // 清运行期态防跨存档脏引用 (与 TarotRuntime.reset 同纪律)。
        BrewerRuntime.reset();
    }

    private void onClientSetup(FMLClientSetupEvent event) {
        event.enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> {
            BrewingStationClient.registerScreens();
            WineCellarClient.registerScreens();
        }));
    }

    /** 触发 RegistryObject 所在类的静态初始化 (使其 MenuType 登记被收集); 同 ChefSystem.touch。 */
    private static void touch(Object registryObject) {
        Objects.requireNonNull(registryObject);
    }

    @Override
    public String name() {
        return "BrewerSystem";
    }
}
