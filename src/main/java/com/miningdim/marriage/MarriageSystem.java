package com.miningdim.marriage;

import com.miningdim.core.Subsystem;
import com.miningdim.entry.IMiningPlayerData;
import com.miningdim.entry.MiningCapabilities;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.living.LivingHurtEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;
import net.minecraftforge.network.NetworkHooks;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Objects;

/**
 * 结婚系统子系统入口 (模块化铁律 3 自注册; 结婚系统 spec 第九章)。
 *
 * 阶段 1 装配: /marriage 命令树 (propose/accept/wed) + 订婚意向瞬态表 {@link MarriageProposals}。
 * 阶段 2 装配 (spec 第四/五/六章):
 *  - 共享背包: MenuType ({@link MarriageRegistration}) + 蹲下右键戒指远程开 ({@link PlayerInteractEvent.RightClickItem}) +
 *    会话登记 {@link MarriageBackpackSessions} (掉线强制关闭) + 客户端 Screen (FMLClientSetupEvent + Dist 隔离)。
 *  - 传送到伴侣: 右键结婚戒指 (不潜行) 起蓄力 {@link MarriageTeleport}; ServerTickEvent 推进; 移动/潜行/受伤打断。
 *  - 离婚: /marriage divorce 经 {@link MarriageDivorce} (再婚冷却 + 成本 + 共享背包清算 + 审计) + 持久历史
 *    {@link MarriageHistory} (再婚冷却 + UUID 对里程碑去重)。
 *  - 登录自愈: capability 婚姻指针指向已解除关系时清指针 (离线配偶离婚后登录自愈)。
 *
 * 注入顺序: 经济门面经 {@link com.miningdim.economy.EconomyServices} 定位器在事件回调取用, 对 register 顺序不敏感。
 */
public final class MarriageSystem implements Subsystem {

    private static final Logger LOGGER = LoggerFactory.getLogger("miningdim/marriage");

    /** 订婚意向瞬态表 (进程级单例; 命令回调读写, 主线程独占)。 */
    private final MarriageProposals proposals = new MarriageProposals();

    /** 共享背包打开会话登记 (掉线强制关闭防 dupe; 进程级单例, 主线程独占)。 */
    private final MarriageBackpackSessions backpackSessions = new MarriageBackpackSessions();

    /** 传送蓄力状态机 (进程级单例; 交互回调起、ServerTickEvent 推进, 主线程独占)。 */
    private final MarriageTeleport teleport = new MarriageTeleport();

    /**
     * 接线校验缝: {@link #register} 把注入给面板的那两张表原样记在这里, 供 GameTest 断言
     * "面板拿到的就是本子系统自己那张表"。
     *
     * 为什么单靠"marriage.* 注册了没有"测不出来: 把这一行改成 {@code registerAll(new MarriageProposals(), ...)},
     * action 照样注册成功, 十条用例也照样全绿 —— 因为它们全都经 {@code MarriageWebUiActions.proposals()} 读同一份
     * 错表, 自洽。而真服上的后果是 A 用 /marriage propose 求的婚 B 在面板上永远看不见, 离婚时也关不掉对方
     * 正开着的共享背包窗口 (spec 第四章要堵死的并发 dupe 窗口)。唯一能证伪它的断言是<b>实例同一性</b>。
     *
     * 生产代码一律经构造注入取用这两张表, 不许读这两个静态字段。
     */
    static MarriageProposals wiredProposals;
    static MarriageBackpackSessions wiredBackpackSessions;

    @Override
    public void register(IEventBus modBus, IEventBus forgeBus) {
        forgeBus.register(this);
        // 共享背包 MenuType 登记: touch 强制 MarriageRegistration 类加载, 使其 MenuType 收进 ModMenus.MENUS pending map
        // (范式同 AgentSystem.touch; ModMenus.MENUS 由 JobFrameworkSystem 统一接 modBus)。
        touch(MarriageRegistration.BACKPACK_MENU);
        // 客户端 Screen 注册 (FMLClientSetupEvent + DistExecutor 隔离; 专用服务器永不触客户端类)。
        modBus.addListener(this::onClientSetup);
        // 婚姻面板的 marriage.* WebUiAction: 注入本子系统的两张瞬态表, 使面板与 /marriage 命令共用同一份婚约意向
        // 与同一份共享背包会话 (各 new 一份 = 命令行求的婚面板看不见, 且离婚强制关窗关不到真正打开的那些窗口)。
        MarriageWebUiActions.registerAll(proposals, backpackSessions);
        wiredProposals = proposals;
        wiredBackpackSessions = backpackSessions;
        LOGGER.info("[miningdim] marriage subsystem registered (rings + ceremony + shared backpack + teleport + divorce)");
    }

    @SubscribeEvent
    public void onRegisterCommands(RegisterCommandsEvent event) {
        new MarriageCommands(proposals, backpackSessions).register(event.getDispatcher());
    }

    /** 客户端 setup: 注册共享背包 Screen (经 DistExecutor 双箭头隔离, 范式同 AgentSystem.onClientSetup)。 */
    private void onClientSetup(FMLClientSetupEvent event) {
        event.enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
                () -> () -> com.miningdim.marriage.client.MarriageBackpackClient.registerScreens()));
    }

    // ============================================================
    // 共享背包: 蹲下右键结婚戒指远程开 / 传送: 右键 (不潜行) 起蓄力
    // ============================================================

    /**
     * 结婚戒指右键交互 (spec 第四/五章): 蹲下 + 右键 -> 远程开共享背包; 不潜行 + 右键 -> 起传送蓄力。
     * 服务端权威: 仅服务端处理 (客户端侧放行触发挥手); 须持结婚戒指 (订婚戒指无功能)。
     */
    @SubscribeEvent
    public void onRightClickRing(PlayerInteractEvent.RightClickItem event) {
        if (event.getLevel().isClientSide()) {
            return;
        }
        if (!(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }
        ItemStack held = event.getItemStack();
        if (!(held.getItem() instanceof RingItem ring) || ring.isEngagement()) {
            return;
        }
        // 主手才响应 (避免主副手双触)。
        if (event.getHand() != InteractionHand.MAIN_HAND) {
            return;
        }
        ServerLevel overworld = player.getServer().overworld();
        if (player.isShiftKeyDown()) {
            openSharedBackpack(player, overworld);
        } else {
            startTeleport(player, overworld);
        }
        // 消费交互 (阻断后续 Item.use / 放置, 避免误触其它行为)。
        event.setCanceled(true);
    }

    /** 蹲下右键: 远程开共享背包 (spec 第四章)。校验已婚 + 关系存在; 经 NetworkHooks.openScreen 透传 marriageId+格数。 */
    private void openSharedBackpack(ServerPlayer player, ServerLevel overworld) {
        IMiningPlayerData data = MiningCapabilities.get(player).orElse(null);
        if (data == null || data.marriageId() == IMiningPlayerData.NO_MARRIAGE) {
            player.displayClientMessage(Component.translatable("message.miningdim.marriage.not_married"), true);
            return;
        }
        MarriageRegistry registry = MarriageRegistry.get(overworld);
        MarriageState state = registry.byId(data.marriageId());
        if (state == null || !state.involves(player.getUUID())) {
            // 指针指向已解除关系: 自愈清指针。
            data.setMarriageId(IMiningPlayerData.NO_MARRIAGE);
            data.setSpouseUUID(null);
            player.displayClientMessage(Component.translatable("message.miningdim.marriage.not_married"), true);
            return;
        }
        MarriageBackpackMenu.Provider provider =
                new MarriageBackpackMenu.Provider(state, registry, backpackSessions, overworld);
        NetworkHooks.openScreen(player, provider, provider::writeExtra);
        backpackSessions.onOpened(state.marriageId(), player);
    }

    /** 不潜行右键: 起传送蓄力 (spec 第五章)。结果码 -> actionbar 提示 (STARTED 的提示已在状态机内发)。 */
    private void startTeleport(ServerPlayer player, ServerLevel overworld) {
        MarriageTeleport.StartResult result = teleport.tryStart(player, overworld);
        if (result != MarriageTeleport.StartResult.STARTED) {
            player.displayClientMessage(teleportStartMessage(result), true);
        }
    }

    private static Component teleportStartMessage(MarriageTeleport.StartResult result) {
        return switch (result) {
            case ALREADY_CHANNELING -> Component.translatable("message.miningdim.marriage.teleport.already");
            case ON_COOLDOWN -> Component.translatable("message.miningdim.marriage.teleport.cooldown");
            case SPOUSE_OFFLINE -> Component.translatable("message.miningdim.marriage.teleport.offline");
            case DIFFERENT_DIMENSION -> Component.translatable("message.miningdim.marriage.teleport.diff_dim");
            case SPOUSE_IN_MINING_DIM -> Component.translatable("message.miningdim.marriage.teleport.mining_dim");
            case NOT_MARRIED, NO_SPOUSE_RESOLVED -> Component.translatable("message.miningdim.marriage.not_married");
            case STARTED -> Component.empty();
        };
    }

    // ============================================================
    // tick / 打断 / 生命周期
    // ============================================================

    @SubscribeEvent
    public void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        // overworld 在服务端启动后恒存在; tick 阶段必有 server。
        net.minecraft.server.MinecraftServer server = net.minecraftforge.server.ServerLifecycleHooks.getCurrentServer();
        if (server != null) {
            teleport.tick(server.overworld());
        }
    }

    /** 蓄力期间任一方受伤即取消传送 (spec 第五章战斗锁: 挨枪传不掉)。 */
    @SubscribeEvent
    public void onLivingHurt(LivingHurtEvent event) {
        if (event.getEntity() instanceof ServerPlayer hurt) {
            teleport.onHurt(hurt.getUUID(), hurt.getServer().overworld());
        }
    }

    /**
     * 玩家登出/掉线: 强制关闭其所在关系的所有共享背包窗口 (spec 第四章防并发 dupe) + 取消其参与的传送蓄力。
     */
    @SubscribeEvent
    public void onPlayerLoggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }
        ServerLevel overworld = player.getServer().overworld();
        IMiningPlayerData data = MiningCapabilities.get(player).orElse(null);
        if (data != null && data.marriageId() != IMiningPlayerData.NO_MARRIAGE) {
            backpackSessions.forceCloseAll(data.marriageId(), overworld);
        }
        teleport.onHurt(player.getUUID(), overworld); // 复用打断: 登出者参与的蓄力取消 (在线一方收提示)。
    }

    /** 登录: capability 婚姻指针指向已解除关系时清指针 (离线配偶离婚后登录自愈; spec 第六章离线侧)。 */
    @SubscribeEvent
    public void onPlayerLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            reconcileMarriagePointer(player);
        }
    }

    /**
     * 校正玩家 capability 婚姻指针: 若指针指向 Registry 中已不存在 / 不含本玩家的关系, 清为未婚 (自愈)。
     * 离线配偶在另一方离婚后, 其指针在此被清。公开静态供测试与离线侧复用。
     */
    public static void reconcileMarriagePointer(ServerPlayer player) {
        IMiningPlayerData data = MiningCapabilities.get(player).orElse(null);
        if (data == null || data.marriageId() == IMiningPlayerData.NO_MARRIAGE) {
            return;
        }
        ServerLevel overworld = player.getServer().overworld();
        MarriageRegistry registry = MarriageRegistry.get(overworld);
        MarriageState state = registry.byId(data.marriageId());
        if (state == null || !state.involves(player.getUUID())) {
            data.setMarriageId(IMiningPlayerData.NO_MARRIAGE);
            data.setSpouseUUID(null);
        }
    }

    @SubscribeEvent
    public void onServerStopping(ServerStoppingEvent event) {
        // 清进程级瞬态 (会话/蓄力), 防跨存档脏引用 (与 SealRegistry.reset 同纪律)。Registry/History 是 SavedData, 随存档落盘不在此清。
        backpackSessions.reset();
        teleport.reset();
    }

    /** 触发 RegistryObject 所在类静态初始化 (使 MenuType 登记被收集; 范式同 AgentSystem.touch)。 */
    private static void touch(Object registryObject) {
        Objects.requireNonNull(registryObject);
    }

    @Override
    public String name() {
        return "MarriageSystem";
    }
}
