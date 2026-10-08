package com.miningdim.core.auth;

import com.miningdim.core.Subsystem;
import com.mojang.brigadier.ParseResults;
import com.mojang.brigadier.context.ParsedCommandNode;
import com.mojang.brigadier.tree.CommandNode;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.common.util.FakePlayer;
import net.minecraftforge.event.CommandEvent;
import net.minecraftforge.event.ServerChatEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.item.ItemTossEvent;
import net.minecraftforge.event.entity.living.LivingAttackEvent;
import net.minecraftforge.event.entity.living.LivingHurtEvent;
import net.minecraftforge.event.entity.player.AttackEntityEvent;
import net.minecraftforge.event.entity.player.EntityItemPickupEvent;
import net.minecraftforge.event.entity.player.FillBucketEvent;
import net.minecraftforge.event.entity.player.PlayerContainerEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.event.level.BlockEvent;
import net.minecraftforge.event.level.ExplosionEvent;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.eventbus.api.Event;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.IEventBus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Function;

/**
 * 登录门的事件侧接线 (判定本身在 {@link PlayerLoginGate})。
 *
 * <ol>
 *   <li>开服后立刻解析一次 AccessHub 绑定, 让"绑定失败"在启动日志里当场可见, 而不是等第一个玩家点平板; 顺带列出
 *       尚未注册 AccessHub 账号的 OP 名字, 提醒尽快注册 ({@link PlayerLoginGate#auditOpAccounts})。</li>
 *   <li>进服时撤销残留的登录态 (LOWEST 优先级, 排在 AccessHub 自己的清理之后), 见
 *       {@link PlayerLoginGate#revokeInheritedSession(ServerPlayer)}; 紧接着已放行的玩家当场触发"登录已确认",
 *       被拒的交给巡检 ({@link PlayerLoginGate#onLoginConfirmed})。</li>
 *   <li>玩家交互事件在 <b>HIGHEST</b> 优先级取消 (只对 NOT_LOGGED_IN), 见下。</li>
 *   <li>未登录玩家造成的伤害与爆炸在 HIGHEST 取消, 见下。</li>
 *   <li>登录结果与连接的核对 (命令监听、离线、逐 tick), 见 {@link LoginRaceGuard}; 登录确认巡检 (每
 *       {@link PlayerLoginGate#CONFIRMATION_SWEEP_INTERVAL_TICKS} tick)。</li>
 *   <li>本 mod 的菜单在未通过登录门的玩家手里一打开就关掉。菜单按钮 (clickMenuButton) 与格子点击都是原版包,
 *       本 mod 没有逐包的入口可拦; 但它们都要求服务端这边正开着那个菜单, 所以在"打开"这一个点上关门就同时挡住了
 *       全部七个带按钮的菜单、所有带输出格的菜单, 以及今后新增的菜单。</li>
 * </ol>
 *
 * 为什么交互事件要在 HIGHEST 取消: AccessHub 在开服 (ServerStarting) 时才注册自己的监听器, 优先级是默认的 NORMAL、且不接已取消的事件; 本 mod 的
 * 监听器在构造期就注册了, 同优先级按注册先后执行, 于是全部排在它前面 (堆叠生物的分堆/拴绳/剪毛/挤奶/繁殖挂在
 * EntityInteract, 结婚戒指挂在 RightClickItem)。逐个监听器补判定容易漏掉今后新增的监听器; 这里在 HIGHEST 统一
 * 取消同一批事件, 之后所有 receiveCanceled=false 的监听器 (本 mod 的与别的 mod 的) 都不会再收到它, 保证未登录
 * 玩家的交互不会被任何监听器处理。
 *
 * 对 NOT_LOGGED_IN 而言原版行为不变: AccessHub 本来就会取消这些事件, 这里只是取消得更早; AccessHub 的监听器
 * 未生效时 (比如它启动途中出错), 这一层同样有效。
 * UNAVAILABLE 不在这里拦: 登录态无从判定时全员都是 UNAVAILABLE, 连原版交互一起拦, 一次 AccessHub 故障就会让整个
 * 服务器没法玩 —— 本 mod 自己的功能 (平板、键位包、菜单、戒指、命令) 仍各自在入口处对 UNAVAILABLE 关门。
 *
 * 取消的事件与 AccessHub 0.5.3 的 PlayerAuthListener 逐条对齐, 只取其中属于"玩家的主动意图"的部分:
 * 右键物品/方块、左键方块、实体交互 (两种)、攻击实体、破坏/放置方块、扔出/捡起物品、装桶、
 * 聊天、命令 (放行 AccessHub 登录前允许的那几个命令)。没有跟进的 (本 mod 对它们要么不监听, 要么只做限制性的取消,
 * 没有替玩家办事的监听器可被抢先): 骑乘 (进服时原版恢复坐骑也走这个事件, 不再多加一层)、睡觉 (事件不可取消,
 * 抢在前面设结果挡不住后续监听器)、受击 (保护的是未登录玩家本人, AccessHub 自己管)、设出生点 (床与重生锚都要
 * 先右键方块, 已被拦下)。
 *
 * 伤害另算一层: AttackEntityEvent 只覆盖原版近战, 弹射物与别的 mod 自行结算的伤害不经过它。于是按<b>伤害来源</b>
 * 拦: LivingAttackEvent 与 LivingHurtEvent (后者兜住绕开 hurt() 直接结算的路径) 的来源实体 (直接或间接, 弹射物
 * 回溯到发射者) 是未登录玩家即取消; ExplosionEvent.Start 的间接来源 (TNT 点燃者、弹射物发射者) 是未登录玩家即
 * 整场爆炸取消, 连方块破坏一起拦下。
 * 只拦伤害与爆炸, 不拦弹射物生成: 弹射物本身不伤人, 在生成处拦还会让别的 mod 已经扣掉的弹药白白消耗。
 */
public final class LoginGateSubsystem implements Subsystem {

    private static final Logger LOGGER = LoggerFactory.getLogger("miningdim/login-gate");

    /** 本 mod 菜单类的包前缀 (所有菜单实现都在 com.miningdim 之下)。 */
    private static final String MOD_PACKAGE_PREFIX = "com.miningdim.";

    /** AccessHub 0.5.3 登录前放行的命令根 (与其 AuthCommandNames.ALLOWED 一致)。 */
    static final Set<String> PRE_LOGIN_COMMANDS = Set.of("login", "l", "register", "reg", "changepassword", "enroll");

    /** 会触发异步密码校验的命令根 (/login 与别名 /l), 供 {@link LoginRaceGuard} 记录。 */
    private static final Set<String> LOGIN_COMMANDS = Set.of("login", "l");

    static final String COMMAND_BLOCKED_KEY = "message.miningdim.login_gate.command_blocked";

    @Override
    public void register(IEventBus modBus, IEventBus forgeBus) {
        forgeBus.addListener(LoginGateSubsystem::onServerStarted);
        forgeBus.addListener(LoginGateSubsystem::onServerStopping);
        forgeBus.addListener(EventPriority.LOWEST, LoginGateSubsystem::onPlayerLoggedIn);
        forgeBus.addListener(LoginGateSubsystem::onPlayerLoggedOut);
        forgeBus.addListener(LoginGateSubsystem::onServerTick);
        forgeBus.addListener(LoginGateSubsystem::onContainerOpen);

        cancelBeforeLogin(forgeBus, PlayerInteractEvent.RightClickItem.class, PlayerInteractEvent::getEntity);
        cancelBeforeLogin(forgeBus, PlayerInteractEvent.RightClickBlock.class, PlayerInteractEvent::getEntity);
        cancelBeforeLogin(forgeBus, PlayerInteractEvent.LeftClickBlock.class, PlayerInteractEvent::getEntity);
        cancelBeforeLogin(forgeBus, PlayerInteractEvent.EntityInteract.class, PlayerInteractEvent::getEntity);
        cancelBeforeLogin(forgeBus, PlayerInteractEvent.EntityInteractSpecific.class, PlayerInteractEvent::getEntity);
        cancelBeforeLogin(forgeBus, AttackEntityEvent.class, AttackEntityEvent::getEntity);
        cancelBeforeLogin(forgeBus, BlockEvent.BreakEvent.class, BlockEvent.BreakEvent::getPlayer);
        cancelBeforeLogin(forgeBus, BlockEvent.EntityPlaceEvent.class, BlockEvent.EntityPlaceEvent::getEntity);
        cancelBeforeLogin(forgeBus, EntityItemPickupEvent.class, EntityItemPickupEvent::getEntity);
        cancelBeforeLogin(forgeBus, FillBucketEvent.class, FillBucketEvent::getEntity);
        forgeBus.addListener(EventPriority.HIGHEST, false, ItemTossEvent.class, LoginGateSubsystem::onItemToss);
        forgeBus.addListener(EventPriority.HIGHEST, false, ServerChatEvent.class, LoginGateSubsystem::onChat);
        forgeBus.addListener(EventPriority.HIGHEST, false, CommandEvent.class, LoginGateSubsystem::onCommand);

        // 伤害与爆炸按来源拦 (见类注释)。
        cancelBeforeLogin(forgeBus, LivingAttackEvent.class, event -> culpritOf(event.getSource()));
        cancelBeforeLogin(forgeBus, LivingHurtEvent.class, event -> culpritOf(event.getSource()));
        cancelBeforeLogin(forgeBus, ExplosionEvent.Start.class,
                event -> event.getExplosion().getIndirectSourceEntity());
    }

    private static void onServerStarted(ServerStartedEvent event) {
        PlayerLoginGate.binding();
        PlayerLoginGate.auditOpAccounts(event.getServer());
    }

    private static void onServerStopping(ServerStoppingEvent event) {
        PlayerLoginGate.resetServerState();
    }

    private static void onPlayerLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer player && !(player instanceof FakePlayer)) {
            PlayerLoginGate.revokeInheritedSession(player);
            PlayerLoginGate.confirmAtJoin(player);
        }
    }

    private static void onPlayerLoggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.getEntity() instanceof ServerPlayer player && !(player instanceof FakePlayer)) {
            PlayerLoginGate.noteLogout(player);
            PlayerLoginGate.forgetConfirmation(player);
        }
    }

    private static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        // 先做连接核对, 再巡检登录确认: 同一 tick 里被撤销的那份登录态不该被当成"刚登录"通知出去。
        PlayerLoginGate.sweepRacedLogins(event.getServer());
        if (event.getServer().getTickCount() % PlayerLoginGate.CONFIRMATION_SWEEP_INTERVAL_TICKS == 0) {
            PlayerLoginGate.sweepLoginConfirmations(event.getServer());
        }
    }

    /**
     * 一次伤害的肇事者: 来源实体 (DamageSource 已把弹射物、TNT 回溯到发射者 / 点燃者); 没有来源实体、但直接实体是
     * 弹射物时, 取弹射物的主人 —— 别的 mod 自造的伤害来源可能只填了子弹这一个实体。
     */
    static Entity culpritOf(DamageSource source) {
        Entity culprit = source.getEntity();
        if (culprit == null && source.getDirectEntity() instanceof Projectile projectile) {
            culprit = projectile.getOwner();
        }
        return culprit;
    }

    // ============================================================
    // 玩家交互事件: HIGHEST 取消 (见类注释)
    // ============================================================

    /**
     * 该实体是否是一个必须先 /login 的真玩家。只认 NOT_LOGGED_IN (UNAVAILABLE 不拦原版玩法, 见类注释); FakePlayer
     * (机械臂一类自动化) 放过, 它们本来就不是谁的意图, 也从来不会"登录"。
     */
    static boolean mustLogInFirst(Entity actor) {
        return actor instanceof ServerPlayer player && !(player instanceof FakePlayer)
                && PlayerLoginGate.check(player) == PlayerLoginGate.Verdict.NOT_LOGGED_IN;
    }

    /** 在 HIGHEST 挂一个"未登录即取消"的监听器。只收未被取消的事件: 已被别人取消的不必再判。 */
    private static <E extends Event> void cancelBeforeLogin(IEventBus forgeBus, Class<E> type,
                                                            Function<E, ? extends Entity> actor) {
        forgeBus.addListener(EventPriority.HIGHEST, false, type, event -> {
            if (mustLogInFirst(actor.apply(event))) {
                event.setCanceled(true);
            }
        });
    }

    /**
     * 扔出物品: 取消扔出时把物品放回背包, 避免物品丢失。
     *
     * Forge 在 ItemTossEvent 被取消时不会把物品放回任何地方 (ForgeHooks.onPlayerTossEvent 返回 null), 只取消的话
     * 物品就没了, 所以这里取消之后自己放回背包; 背包放不下的那部分仍会随取消一起丢失。
     */
    static void onItemToss(ItemTossEvent event) {
        // 断线清理不是玩家的意图: 原版在玩家离线之后才把合成格与光标上的物品掉在脚下 (Player.remove, 此时背包已经
        // 存过盘), 而登录态在 PlayerLoggedOutEvent 里就被清了, 刚才还登录着的正常玩家这时也判成未登录。在这里拦下
        // 并"放回背包", 放回的是一个不会再存盘的背包, 物品就没了。
        if (event.getPlayer() instanceof ServerPlayer leaving && leaving.hasDisconnected()) {
            return;
        }
        if (!mustLogInFirst(event.getPlayer())) {
            return;
        }
        event.setCanceled(true);
        ItemStack tossed = event.getEntity().getItem();
        event.getPlayer().getInventory().add(tossed);
        if (!tossed.isEmpty()) {
            LOGGER.debug("{} tossed {} before /login and the inventory is full; the item is discarded",
                    event.getPlayer().getGameProfile().getName(), tossed);
        }
    }

    /** 聊天: 取消并提示 (AccessHub 自己取消时也会提示; 这里抢在它前面, 它的提示就不会再发)。 */
    static void onChat(ServerChatEvent event) {
        ServerPlayer player = event.getPlayer();
        if (mustLogInFirst(player)) {
            event.setCanceled(true);
            player.sendSystemMessage(PlayerLoginGate.rejectionMessage(PlayerLoginGate.Verdict.NOT_LOGGED_IN));
        }
    }

    /**
     * 命令: 未登录只放行 AccessHub 的登录类命令, 其余取消并提示; 顺带记下 /login 供连接核对。
     *
     * 命令根的判定与 AccessHub 一致: 取已解析的第一个节点名, 一个节点都没解析出来 (乱打的命令) 按不放行处理。
     */
    static void onCommand(CommandEvent event) {
        ParseResults<CommandSourceStack> parse = event.getParseResults();
        if (!(parse.getContext().getSource().getEntity() instanceof ServerPlayer player)
                || player instanceof FakePlayer) {
            return;
        }
        String root = rootLiteral(parse);
        if (root != null && PRE_LOGIN_COMMANDS.contains(root)) {
            if (LOGIN_COMMANDS.contains(root)) {
                PlayerLoginGate.noteLoginCommand(player);
            }
            return;
        }
        PlayerLoginGate.Verdict verdict = PlayerLoginGate.check(player);
        if (verdict == PlayerLoginGate.Verdict.NOT_LOGGED_IN) {
            event.setCanceled(true);
            player.sendSystemMessage(Component.translatable(COMMAND_BLOCKED_KEY));
        } else if (verdict == PlayerLoginGate.Verdict.UNAVAILABLE && isModCommand(parse)) {
            // 登录态无从判定时原版命令照常 (见类注释), 但本 mod 的命令与平板、菜单一样关门: 不做正版验证的服务器上
            // 这时谁都能顶着别人的名字进来, /tarot pack buy、/marriage buyring 花的是被冒名者账本里的钱,
            // OP 名字还能 /economy grant。
            event.setCanceled(true);
            player.sendSystemMessage(PlayerLoginGate.rejectionMessage(PlayerLoginGate.Verdict.UNAVAILABLE));
        }
    }

    private static String rootLiteral(ParseResults<CommandSourceStack> parse) {
        List<ParsedCommandNode<CommandSourceStack>> nodes = parse.getContext().getNodes();
        return nodes.isEmpty() ? null : nodes.get(0).getNode().getName();
    }

    /**
     * 这条命令是不是本 mod 注册的: 看它的根节点之下有没有哪个执行体出自本 mod 的包 (与菜单同一个判据)。
     * 按包认而不是手抄一份命令根名单: 名单会在新增命令时漏掉, 而漏掉的后果是那条命令在登录校验失灵时照常可用。
     */
    static boolean isModCommand(ParseResults<CommandSourceStack> parse) {
        List<ParsedCommandNode<CommandSourceStack>> nodes = parse.getContext().getNodes();
        return !nodes.isEmpty() && hasModExecutor(nodes.get(0).getNode(), new HashSet<>());
    }

    private static boolean hasModExecutor(CommandNode<CommandSourceStack> node,
                                          Set<CommandNode<CommandSourceStack>> visited) {
        // 别名用 redirect 指回原节点, 重定向可以成环: 走过的不再走。
        if (!visited.add(node)) {
            return false;
        }
        if (node.getCommand() != null && node.getCommand().getClass().getName().startsWith(MOD_PACKAGE_PREFIX)) {
            return true;
        }
        if (node.getRedirect() != null && hasModExecutor(node.getRedirect(), visited)) {
            return true;
        }
        for (CommandNode<CommandSourceStack> child : node.getChildren()) {
            if (hasModExecutor(child, visited)) {
                return true;
            }
        }
        return false;
    }

    // ============================================================
    // 本 mod 的菜单
    // ============================================================

    /**
     * 未通过登录门的玩家打开本 mod 菜单 -> 立即关闭并在动作栏说明原因。
     *
     * FakePlayer (机械臂一类自动化) 放过: 它们没有客户端, 发不出按钮与格子点击包, 关它们的菜单只会弄坏自动化。
     *
     * AccessHub 正常工作时它自己也会关掉未登录玩家打开的一切容器, 这里在那种情况下只是重复; 它补的是两种情形:
     * AccessHub 的玩家监听器未生效 (比如它启动途中出错), 以及登录态无从判定 (UNAVAILABLE)。只管本 mod 的菜单而不是
     * 所有容器: UNAVAILABLE 时全员被拒, 若连原版箱子也一并关掉, 一次 AccessHub 故障就会让整个服务器没法玩。
     */
    static void onContainerOpen(PlayerContainerEvent.Open event) {
        if (!(event.getEntity() instanceof ServerPlayer player) || player instanceof FakePlayer) {
            return;
        }
        if (!isModMenu(event.getContainer())) {
            return;
        }
        PlayerLoginGate.Verdict verdict = PlayerLoginGate.check(player);
        if (verdict.allowed()) {
            return;
        }
        LOGGER.debug("Closed {} for {}: login gate verdict {}", event.getContainer().getClass().getName(),
                player.getGameProfile().getName(), verdict);
        player.closeContainer();
        player.displayClientMessage(PlayerLoginGate.rejectionMessage(verdict), true);
    }

    static boolean isModMenu(AbstractContainerMenu menu) {
        return menu.getClass().getName().startsWith(MOD_PACKAGE_PREFIX);
    }
}
