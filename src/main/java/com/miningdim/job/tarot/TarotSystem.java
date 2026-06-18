package com.miningdim.job.tarot;

import com.miningdim.core.Subsystem;
import com.miningdim.job.tarot.card.TarotCardLoader;
import com.miningdim.job.tarot.client.TarotClientSetup;
import com.miningdim.job.tarot.craft.TarotCraftService;
import com.miningdim.job.tarot.pack.PackGachaService;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraftforge.event.AddReloadListenerEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.living.LivingDeathEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 塔罗师子系统门面 (TarotReader spec 第十一章; 模块化铁律 3)。持本职业全部 DeferredRegister 并自注册:
 *  - {@link TarotRegistry} (Item/Block/BlockEntityType; MenuType 经公共 ModMenus) -> modBus;
 *  - {@link TarotCreativeTab} (专属创造页) -> modBus;
 *  - 配置 {@link TarotConfig} (SERVER 级, 自带 spec 段 miningdim-tarot.toml) -> ModLoadingContext;
 *  - 牌效 datapack 加载器 {@link TarotCardLoader} -> AddReloadListenerEvent (forgeBus);
 *  - 运行期服务装配进 {@link TarotRuntime} (cooldown/scheduler/maxHealth/loader/effectEngine/gacha/craft);
 *  - forge 事件: ServerTick 推进调度器, 登出/死亡/换维度统一清最大生命修饰 + 调度队列 (spec 第十二章防泄漏);
 *  - 客户端 setup (品质边框 predicate + MenuScreens) 仅 Dist.CLIENT 接线。
 *
 * 等级走 entry/job capability (JobServices), 不污染 MiningServices (spec 第十一章); 易伤效果由 JobFramework
 * 共享 ModJobEffects 注册 (本子系统不重复注册)。集成阶段把 new TarotSystem() 加进 MiningDim.registerSubsystems()。
 */
public final class TarotSystem implements Subsystem {

    private static final Logger LOGGER = LoggerFactory.getLogger("miningdim/tarot");

    private final TarotCooldownManager cooldown = new TarotCooldownManager();
    private final ScheduledEffectManager scheduler = new ScheduledEffectManager();
    private final MaxHealthModifierManager maxHealth = new MaxHealthModifierManager();
    private final TarotCardLoader cardLoader = new TarotCardLoader();
    private final TarotEffectEngine effectEngine = new TarotEffectEngine(maxHealth, scheduler);
    private final PackGachaService gacha = new PackGachaService();
    private final TarotCraftService craft = new TarotCraftService();
    /** 战斗窗口事件接线 (免疫击退/吸血/反伤/无敌/复活契约; spec 第六章有状态机制)。 */
    private final TarotCombatHandlers combatHandlers = new TarotCombatHandlers();

    @Override
    public void register(IEventBus modBus, IEventBus forgeBus) {
        // 运行期服务装配 (本包静态访问点; use 入口经此取用)。
        TarotRuntime.init(cooldown, scheduler, maxHealth, cardLoader, effectEngine, gacha, craft);

        // DeferredRegister 自注册 (modBus); MenuType 经 ModMenus 由 JobFramework 统一接 modBus,
        // 但本子系统的 RegistryObject 在 TarotRegistry 静态初始化时已登记进 ModMenus.MENUS, 故此处只接 Item/Block/BE。
        TarotRegistry.register(modBus);
        TarotCreativeTab.register(modBus);

        // 独立 SERVER 配置段 (自带 spec, 不污染中央 MiningServerConfig)。
        net.minecraftforge.fml.ModLoadingContext.get().registerConfig(
                net.minecraftforge.fml.config.ModConfig.Type.SERVER,
                TarotConfig.SPEC, "miningdim-tarot.toml");

        // forge 运行期事件 (datapack 重载 / tick / 玩家生命周期清理)。
        forgeBus.register(this);
        // 战斗窗口事件 (LivingHurt/LivingKnockBack/LivingDeath 读 TarotCombatState 窗口快照)。
        forgeBus.register(combatHandlers);

        // 客户端 setup 仅在客户端接线 (专用服务器不触客户端类链)。
        if (TarotClientSetup.isClient()) {
            TarotClientSetup.register(modBus);
        }

        LOGGER.info("[miningdim] tarot subsystem registered (cards + packs + craft + effects + datapack loader)");
    }

    // ---- datapack 牌效表加载 ----

    @SubscribeEvent
    public void onAddReloadListener(AddReloadListenerEvent event) {
        event.addListener(cardLoader);
    }

    // ---- 调度器推进 (全局 tick; spec 第十二章 ServerTickEvent 全局时钟) ----

    @SubscribeEvent
    public void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        MinecraftServer server = event.getServer();
        scheduler.tick(server);
        TarotCombatState.tick(server);
    }

    // ---- 属性修饰符 + 调度队列清理 (登出/死亡/换维度; spec 第十二章防泄漏) ----

    @SubscribeEvent
    public void onLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            cleanup(player);
        }
    }

    @SubscribeEvent
    public void onDeath(LivingDeathEvent event) {
        if (event.isCanceled()) {
            // 复活契约 (TarotCombatHandlers) 已拦截本次致死: 玩家未真死, 不清窗口/调度 (continuation 仍有效)。
            return;
        }
        if (event.getEntity() instanceof ServerPlayer player) {
            // 真死: 清最大生命修饰 (防重生后残留) + 调度队列 + 战斗窗口 (spec: 死亡清队列防泄漏)。
            maxHealth.remove(player);
            scheduler.cancelFor(player.getUUID());
            TarotCombatState.clearAll(player.getUUID());
        }
    }

    @SubscribeEvent
    public void onChangeDimension(PlayerEvent.PlayerChangedDimensionEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            // 换维度 (进出矿洞=最高频泄漏路径, spec 第十二章): 统一移除 maxHealth 修饰防泄漏。
            // 调度队列保留 (换维度玩家仍在线, 周期治疗等可继续; 仅 maxHealth attribute 修饰需清, 因换维度
            // 会重建属性实例, 不清会残留旧修饰)。战斗窗口同样清 (跨维度战斗上下文失效)。
            maxHealth.remove(player);
            TarotCombatState.clearAll(player.getUUID());
        }
    }

    @SubscribeEvent
    public void onClone(PlayerEvent.Clone event) {
        // 死亡重生/换维度复制: 新实体不应继承临时 maxHealth 修饰 (transient, 不随存档; 显式清新实体防极端残留)。
        if (event.getEntity() instanceof ServerPlayer target) {
            maxHealth.remove(target);
        }
    }

    @SubscribeEvent
    public void onServerStopping(ServerStoppingEvent event) {
        // 清运行期态防跨存档脏引用 (与 MiningServices.reset / JobServices.reset 同纪律)。
        TarotEconomyHooks.unbind();
        TarotRuntime.reset();
    }

    private void cleanup(ServerPlayer player) {
        maxHealth.remove(player);
        scheduler.cancelFor(player.getUUID());
        cooldown.clear(player.getUUID());
        gacha.clear(player.getUUID());
        TarotCombatState.clearAll(player.getUUID());
    }

    @Override
    public String name() {
        return "TarotSystem";
    }
}
