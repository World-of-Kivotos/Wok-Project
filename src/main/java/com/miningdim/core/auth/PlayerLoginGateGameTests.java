package com.miningdim.core.auth;

import com.miningdim.core.MiningConstants;
import com.miningdim.testutil.MockGameTestPlayers;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.util.ReferenceCountUtil;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.network.protocol.game.ClientboundSystemChatPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.animal.Cow;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.item.PrimedTnt;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.Arrow;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Explosion;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.common.util.BlockSnapshot;
import net.minecraftforge.event.CommandEvent;
import net.minecraftforge.event.ServerChatEvent;
import net.minecraftforge.event.entity.living.LivingHurtEvent;
import net.minecraftforge.event.entity.player.AttackEntityEvent;
import net.minecraftforge.event.entity.player.EntityItemPickupEvent;
import net.minecraftforge.event.entity.player.FillBucketEvent;
import net.minecraftforge.event.entity.player.PlayerContainerEvent;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.event.level.BlockEvent;
import net.minecraftforge.event.level.ExplosionEvent;
import net.minecraftforge.eventbus.api.Event;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * 登录门判定表与 AccessHub 反射探针的回归网。
 *
 * dev 与 GameTest 里没有 AccessHub, 于是探针的反射路径用<b>同形的假对象</b>驱动 (方法名、参数、返回类型与
 * 0.5.3 逐条一致, 见 {@link AccessHubLoginProbe} 类注释), 走的是与生产完全相同的 getMethod / invoke 代码;
 * 判定本体 {@link PlayerLoginGate#evaluate} 把模式、绑定、是否专用服务器都当参数吃, 于是专用服务器分支也能在
 * GameTest 服务端 (isDedicatedServer=false) 里逐格覆盖。
 *
 * 强断言总览 (删被测核心逻辑必挂):
 *  - {@link #gateAllowsEveryoneWhenAccessHubIsAbsentOrServerIsNotDedicated}: 零行为变化的前提 —— 真实环境里
 *    没装 AccessHub 就放行, 且非专用服务器 / OFF 无论绑定如何都放行 (出厂默认 AUTO 与配置注入链路见 config 包的
 *    LoginGateConfigGameTests);
 *  - {@link #boundAccessHubDecidesByItsOwnLoginState}: 登录 / 未登录 / auth.enabled=false / 服务没跑起来 四态;
 *  - {@link #brokenOrMissingAccessHubFailsClosedOnDedicatedServers}: API 对不上、调用抛异常、REQUIRED 档缺失
 *    一律拒绝;
 *  - {@link #probeBindingChecksTheExactMethodShapes}: 缺方法 / 返回类型不符 / 缺 clearSession 都绑不上;
 *  - {@link #joinRevokesAnInheritedLoginButNothingElse}: 进服加固只撤"已登录", 不碰未登录与 auth 关闭;
 *  - {@link #forcedVerdictIsPerPlayerAndReleasedOnClose}: GameTest 注入按玩家隔离且 close 即撤;
 *  - {@link #modMenusCloseForDeniedPlayersOnly}: 菜单门只关本 mod 的菜单, 只关被拒玩家的 (经真实事件总线);
 *  - {@link #playerIntentEventsAreCancelledBeforeLogin}: 被接管的每类交互事件经真实总线都在 HIGHEST 被取消,
 *    UNAVAILABLE 不拦原版玩法;
 *  - {@link #commandsBeforeLoginAllowOnlyAccessHubLoginCommands}: 命令只放行 AccessHub 的登录类命令;
 *  - {@link #itemTossedBeforeLoginGoesBackToTheInventory}: 扔出被拦时物品回背包而不是被 Forge 丢弃;
 *  - {@link #loginResultLandingOnAnotherConnectionIsRevoked}: 连接核对 —— 发出 /login 的连接已断开时, 同一 UUID
 *    另一条连接上的登录态被撤销;
 *  - {@link #opAccountAuditListsNamesWithoutAnAccount}: 开服审计只报没账号的 OP 名字, isRegistered 可选;
 *  - {@link #damageAndExplosionsCausedBeforeLoginAreCancelled}: 未登录者造成的伤害 (近战/箭/只填子弹的来源/爆炸)
 *    不结算、引发的爆炸整场取消, UNAVAILABLE 与登录后照常;
 *  - {@link #loginConfirmationFiresOnceWhenTheVerdictFlips}: 登录确认按"进服即放行 / 被拒后翻为放行"各通知一次。
 *
 * {@link PlayerLoginGate#forceVerdictForTest} 只在 GameTest 服务端上生效 (别处调用直接抛, check 也只在那里读注入表),
 * 本类全部用例都跑在 runGameTestServer 里, 故照常可用。
 */
@GameTestHolder(MiningConstants.MODID)
@PrefixGameTestTemplate(false)
public final class PlayerLoginGateGameTests {

    private static final String EMPTY = "empty";
    private static final String BATCH = "login_gate";

    // ============================================================
    // 1. 零行为变化: 没装 AccessHub / 非专用服务器 / OFF
    // ============================================================

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void gateAllowsEveryoneWhenAccessHubIsAbsentOrServerIsNotDedicated(GameTestHelper helper) {
        // 真实环境: dev classpath 上没有 AccessHub, 进程内的绑定必须解析成 ABSENT, 于是真实 check() 对任何玩家都
        // 放行 —— 这是"不装 AccessHub 时行为零变化"的依据之一; 另一半 (出厂默认是 AUTO、门读的是配置现值) 在
        // config 包的 LoginGateConfigGameTests, 本包不引用 config 包。
        helper.assertTrue(PlayerLoginGate.binding().kind == PlayerLoginGate.Binding.Kind.ABSENT,
                "AccessHub is not on the dev classpath, so the process-wide binding must be ABSENT, got "
                        + PlayerLoginGate.binding().kind);
        ServerPlayer player = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);
        // 不论配置现值是哪一档, GameTest 服务端都不是专用服务器, 真实 check() 一律放行。
        helper.assertTrue(PlayerLoginGate.check(player) == PlayerLoginGate.Verdict.ALLOWED,
                "without AccessHub a real check() must allow, got " + PlayerLoginGate.check(player));

        UUID id = UUID.randomUUID();
        PlayerLoginGate.Binding absent = PlayerLoginGate.Binding.absent();
        helper.assertTrue(PlayerLoginGate.evaluate(LoginGateMode.AUTO, absent, true, id)
                        == PlayerLoginGate.Verdict.ALLOWED,
                "AUTO + AccessHub absent must allow even on a dedicated server (other servers, dev runServer)");

        // 非专用服务器上 AccessHub 自己都不启动, 任何模式、任何绑定都放行 —— 连 BROKEN 也不例外, 否则装了
        // AccessHub 客户端组件的玩家连单人存档的平板都打不开。
        FakeAccessHubMod mod = new FakeAccessHubMod();
        PlayerLoginGate.Binding bound = PlayerLoginGate.Binding.of(mod);
        PlayerLoginGate.Binding broken = PlayerLoginGate.Binding.broken("test");
        for (LoginGateMode mode : LoginGateMode.values()) {
            for (PlayerLoginGate.Binding binding : new PlayerLoginGate.Binding[] {absent, bound, broken}) {
                helper.assertTrue(PlayerLoginGate.evaluate(mode, binding, false, id) == PlayerLoginGate.Verdict.ALLOWED,
                        "a non-dedicated server must always allow (mode=" + mode + ", binding=" + binding.kind + ")");
            }
            if (mode == LoginGateMode.OFF) {
                for (PlayerLoginGate.Binding binding : new PlayerLoginGate.Binding[] {absent, bound, broken}) {
                    helper.assertTrue(PlayerLoginGate.evaluate(mode, binding, true, id)
                                    == PlayerLoginGate.Verdict.ALLOWED,
                            "OFF must allow on a dedicated server regardless of binding " + binding.kind);
                }
            }
        }
        helper.succeed();
    }

    // ============================================================
    // 2. 绑定成功: 按 AccessHub 自己的登录态判定
    // ============================================================

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void boundAccessHubDecidesByItsOwnLoginState(GameTestHelper helper) {
        FakeAccessHubMod mod = new FakeAccessHubMod();
        PlayerLoginGate.Binding binding = PlayerLoginGate.Binding.of(mod);
        helper.assertTrue(binding.kind == PlayerLoginGate.Binding.Kind.BOUND,
                "a correctly shaped AccessHub must bind, got " + binding.kind + " (" + binding.problem + ")");

        UUID owner = UUID.randomUUID();
        UUID notLoggedIn = UUID.randomUUID();
        mod.service.authed.add(owner);
        for (LoginGateMode mode : new LoginGateMode[] {LoginGateMode.AUTO, LoginGateMode.REQUIRED}) {
            helper.assertTrue(PlayerLoginGate.evaluate(mode, binding, true, owner) == PlayerLoginGate.Verdict.ALLOWED,
                    mode + ": a player AccessHub reports as logged in must be allowed");
            helper.assertTrue(PlayerLoginGate.evaluate(mode, binding, true, notLoggedIn)
                            == PlayerLoginGate.Verdict.NOT_LOGGED_IN,
                    mode + ": a player who has not /login-ed must get NOT_LOGGED_IN");
        }

        // 管理员重置 / 离线: AccessHub 清掉会话后, 下一次判定必须立刻变成未登录 (证明没有按会话缓存)。
        mod.service.clearSession(owner);
        helper.assertTrue(PlayerLoginGate.evaluate(LoginGateMode.AUTO, binding, true, owner)
                        == PlayerLoginGate.Verdict.NOT_LOGGED_IN,
                "once AccessHub clears the session the very next check must deny (no per-session caching)");

        // auth.enabled=false: AccessHub 自己放行一切 (isAuthed 对所有人都是 false), 本门不得比它更严。
        mod.config.enabled = false;
        helper.assertTrue(PlayerLoginGate.evaluate(LoginGateMode.REQUIRED, binding, true, notLoggedIn)
                        == PlayerLoginGate.Verdict.ALLOWED,
                "auth.enabled=false must allow, matching AccessHub's own behaviour");
        mod.config.enabled = true;

        // 服务对象为 null (AccessHub 启动失败): 无从判定, 关门。
        FakePlayerAuthService service = mod.service;
        mod.service = null;
        helper.assertTrue(PlayerLoginGate.evaluate(LoginGateMode.AUTO, binding, true, owner)
                        == PlayerLoginGate.Verdict.UNAVAILABLE,
                "a null auth service on a dedicated server means AccessHub is not running: must be UNAVAILABLE");
        mod.service = service;
        FakeAccessHubConfig config = mod.config;
        mod.config = null;
        helper.assertTrue(PlayerLoginGate.evaluate(LoginGateMode.AUTO, binding, true, owner)
                        == PlayerLoginGate.Verdict.UNAVAILABLE,
                "a null config on a dedicated server means AccessHub is not running: must be UNAVAILABLE");
        mod.config = config;
        helper.succeed();
    }

    // ============================================================
    // 3. 关门: API 对不上 / 调用抛异常 / REQUIRED 档缺失
    // ============================================================

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void brokenOrMissingAccessHubFailsClosedOnDedicatedServers(GameTestHelper helper) {
        UUID id = UUID.randomUUID();
        PlayerLoginGate.Binding broken = PlayerLoginGate.Binding.of(new Object());
        helper.assertTrue(broken.kind == PlayerLoginGate.Binding.Kind.BROKEN,
                "an object without the AccessHub getters must not bind, got " + broken.kind);
        for (LoginGateMode mode : new LoginGateMode[] {LoginGateMode.AUTO, LoginGateMode.REQUIRED}) {
            helper.assertTrue(PlayerLoginGate.evaluate(mode, broken, true, id) == PlayerLoginGate.Verdict.UNAVAILABLE,
                    mode + ": an installed but unbindable AccessHub must fail closed");
        }
        helper.assertTrue(PlayerLoginGate.evaluate(LoginGateMode.REQUIRED, PlayerLoginGate.Binding.absent(), true, id)
                        == PlayerLoginGate.Verdict.UNAVAILABLE,
                "REQUIRED on a dedicated server without AccessHub must fail closed");

        // 绑上了, 但调用时抛异常 (版本对不上的另一种形态): 同样关门, 而不是把异常抛进网络 handler。
        FakeAccessHubMod mod = new FakeAccessHubMod();
        PlayerLoginGate.Binding binding = PlayerLoginGate.Binding.of(mod);
        mod.service.authed.add(id);
        mod.service.explode = true;
        helper.assertTrue(PlayerLoginGate.evaluate(LoginGateMode.AUTO, binding, true, id)
                        == PlayerLoginGate.Verdict.UNAVAILABLE,
                "an exception thrown by AccessHub while querying must fail closed, even for a logged-in player");
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void probeBindingChecksTheExactMethodShapes(GameTestHelper helper) {
        helper.assertTrue(PlayerLoginGate.Binding.of(new FakeAccessHubMod()).kind == PlayerLoginGate.Binding.Kind.BOUND,
                "the reference fake has the verified shape and must bind");
        helper.assertTrue(PlayerLoginGate.Binding.of(new BoxedReturnMod()).kind == PlayerLoginGate.Binding.Kind.BROKEN,
                "isAuthed returning Boolean instead of boolean is a different API and must not bind");
        helper.assertTrue(PlayerLoginGate.Binding.of(new NoClearSessionMod()).kind
                        == PlayerLoginGate.Binding.Kind.BROKEN,
                "a service without clearSession(UUID) must not bind (the session model has changed)");
        helper.assertTrue(PlayerLoginGate.Binding.of(new NoConfigMod()).kind == PlayerLoginGate.Binding.Kind.BROKEN,
                "a mod without getConfig() must not bind");
        helper.succeed();
    }

    // ============================================================
    // 4. 进服加固: 只撤"残留的已登录"
    // ============================================================

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void joinRevokesAnInheritedLoginButNothingElse(GameTestHelper helper) {
        FakeAccessHubMod mod = new FakeAccessHubMod();
        PlayerLoginGate.Binding binding = PlayerLoginGate.Binding.of(mod);
        UUID inherited = UUID.randomUUID();
        UUID fresh = UUID.randomUUID();
        mod.service.authed.add(inherited);

        helper.assertTrue(PlayerLoginGate.revokeInheritedSession(binding, inherited),
                "a connection that is already 'logged in' at join time must have that login revoked");
        helper.assertTrue(!mod.service.authed.contains(inherited),
                "revocation must go through AccessHub's clearSession");
        helper.assertTrue(PlayerLoginGate.evaluate(LoginGateMode.AUTO, binding, true, inherited)
                        == PlayerLoginGate.Verdict.NOT_LOGGED_IN,
                "after revocation the joining player must /login like everyone else");
        helper.assertTrue(!PlayerLoginGate.revokeInheritedSession(binding, fresh),
                "a player who is not logged in must be left alone");

        mod.config.enabled = false;
        mod.service.authed.add(inherited);
        helper.assertTrue(!PlayerLoginGate.revokeInheritedSession(binding, inherited),
                "with auth.enabled=false there is no login to revoke; AccessHub state must not be touched");
        helper.assertTrue(mod.service.authed.contains(inherited),
                "auth disabled: the session set must be unchanged");
        helper.assertTrue(!PlayerLoginGate.revokeInheritedSession(PlayerLoginGate.Binding.absent(), inherited),
                "without AccessHub there is nothing to revoke");
        helper.succeed();
    }

    // ============================================================
    // 5. GameTest 注入与菜单门
    // ============================================================

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void forcedVerdictIsPerPlayerAndReleasedOnClose(GameTestHelper helper) {
        ServerPlayer denied = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);
        ServerPlayer bystander = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);
        try (PlayerLoginGate.ForcedVerdict ignored =
                     PlayerLoginGate.forceVerdictForTest(denied.getUUID(), PlayerLoginGate.Verdict.NOT_LOGGED_IN)) {
            helper.assertTrue(PlayerLoginGate.check(denied) == PlayerLoginGate.Verdict.NOT_LOGGED_IN,
                    "the forced verdict must apply to its player");
            helper.assertTrue(!PlayerLoginGate.allows(denied), "allows() must mirror check()");
            helper.assertTrue(PlayerLoginGate.check(bystander) == PlayerLoginGate.Verdict.ALLOWED,
                    "a forced verdict must not leak onto other players in the same test batch");
        }
        helper.assertTrue(PlayerLoginGate.check(denied) == PlayerLoginGate.Verdict.ALLOWED,
                "closing the handle must restore the real verdict");
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void modMenusCloseForDeniedPlayersOnly(GameTestHelper helper) {
        ServerPlayer player = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);
        helper.assertTrue(!LoginGateSubsystem.isModMenu(player.inventoryMenu),
                "vanilla menus are never ours: an AccessHub outage must not lock players out of chests");

        // 走真实的 Forge 事件总线: 删掉 LoginGateSubsystem 里那行 addListener、或 MiningDim 里那行子系统注册,
        // 下面"必须关掉"的断言都会挂 —— 直接调 onContainerOpen 测不出接线。
        // 已放行的玩家: 本 mod 菜单照常开着。
        AbstractContainerMenu allowedMenu = openTestMenu(player);
        MinecraftForge.EVENT_BUS.post(new PlayerContainerEvent.Open(player, allowedMenu));
        helper.assertTrue(player.containerMenu == allowedMenu,
                "an allowed player's mod menu must stay open");
        player.closeContainer();

        for (PlayerLoginGate.Verdict denied : new PlayerLoginGate.Verdict[] {
                PlayerLoginGate.Verdict.NOT_LOGGED_IN, PlayerLoginGate.Verdict.UNAVAILABLE}) {
            try (PlayerLoginGate.ForcedVerdict ignored = PlayerLoginGate.forceVerdictForTest(player.getUUID(), denied)) {
                AbstractContainerMenu deniedMenu = openTestMenu(player);
                helper.assertTrue(LoginGateSubsystem.isModMenu(deniedMenu),
                        "precondition: the test menu counts as ours");
                MinecraftForge.EVENT_BUS.post(new PlayerContainerEvent.Open(player, deniedMenu));
                helper.assertTrue(player.containerMenu instanceof InventoryMenu,
                        denied + ": a mod menu opened by a player who is not past the login gate must be closed "
                                + "immediately, so no button or slot click can reach it; still open: "
                                + player.containerMenu);
            }
        }
        helper.succeed();
    }

    // ============================================================
    // 6. 玩家交互事件: HIGHEST 抢在 AccessHub 与本 mod 其它监听器之前取消
    // ============================================================

    /**
     * 每一类被接管的事件都经真实总线 post 一次: 删掉 register 里任何一行 cancelBeforeLogin, 对应断言必挂。
     * 只在"未登录"时 post 会产生副作用的事件 (破坏方块等) —— 它们在 HIGHEST 就被取消, 到不了别的监听器。
     */
    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void playerIntentEventsAreCancelledBeforeLogin(GameTestHelper helper) {
        ServerPlayer player = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);
        ServerLevel level = helper.getLevel();
        BlockPos pos = helper.absolutePos(new BlockPos(1, 2, 1));
        Cow target = helper.spawn(EntityType.COW, new BlockPos(2, 2, 2));
        ItemEntity loot = new ItemEntity(level, pos.getX(), pos.getY(), pos.getZ(), new ItemStack(Items.DIAMOND));
        BlockHitResult hit = new BlockHitResult(Vec3.atCenterOf(pos), Direction.UP, pos, false);

        try (PlayerLoginGate.ForcedVerdict ignored =
                     PlayerLoginGate.forceVerdictForTest(player.getUUID(), PlayerLoginGate.Verdict.NOT_LOGGED_IN)) {
            List<Event> intents = List.of(
                    new PlayerInteractEvent.RightClickItem(player, InteractionHand.MAIN_HAND),
                    new PlayerInteractEvent.RightClickBlock(player, InteractionHand.MAIN_HAND, pos, hit),
                    new PlayerInteractEvent.LeftClickBlock(player, pos, Direction.UP,
                            PlayerInteractEvent.LeftClickBlock.Action.START),
                    new PlayerInteractEvent.EntityInteract(player, InteractionHand.MAIN_HAND, target),
                    new PlayerInteractEvent.EntityInteractSpecific(player, InteractionHand.MAIN_HAND, target, Vec3.ZERO),
                    new AttackEntityEvent(player, target),
                    new BlockEvent.BreakEvent(level, pos, Blocks.STONE.defaultBlockState(), player),
                    new BlockEvent.EntityPlaceEvent(BlockSnapshot.create(level.dimension(), level, pos),
                            Blocks.STONE.defaultBlockState(), player),
                    new EntityItemPickupEvent(player, loot),
                    new FillBucketEvent(player, new ItemStack(Items.BUCKET), level, hit),
                    new ServerChatEvent(player, "hello", Component.literal("hello")));
            for (Event intent : intents) {
                MinecraftForge.EVENT_BUS.post(intent);
                helper.assertTrue(intent.isCanceled(), intent.getClass().getName()
                        + " from a player who has not /login-ed must be cancelled at HIGHEST");
            }
        }

        // 登录态无从判定时原版玩法照常: 只 post 无副作用的两类, 证明 UNAVAILABLE 不被当成"未登录"。
        try (PlayerLoginGate.ForcedVerdict ignored =
                     PlayerLoginGate.forceVerdictForTest(player.getUUID(), PlayerLoginGate.Verdict.UNAVAILABLE)) {
            helper.assertTrue(!LoginGateSubsystem.mustLogInFirst(player),
                    "UNAVAILABLE must not block vanilla gameplay (an AccessHub outage would stop the whole server)");
            ServerChatEvent chat = new ServerChatEvent(player, "hello", Component.literal("hello"));
            MinecraftForge.EVENT_BUS.post(chat);
            helper.assertTrue(!chat.isCanceled(), "UNAVAILABLE: chat must not be cancelled by the login gate");
        }
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void commandsBeforeLoginAllowOnlyAccessHubLoginCommands(GameTestHelper helper) {
        ServerPlayer player = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);
        EmbeddedChannel channel = (EmbeddedChannel) player.connection.connection.channel();
        CommandSourceStack source = player.createCommandSourceStack();
        // dev 里没有 AccessHub, 自建一个只有 /login 的调度器, 让解析结果的根节点名与生产一致。
        CommandDispatcher<CommandSourceStack> accessHub = new CommandDispatcher<>();
        accessHub.register(Commands.literal("login")
                .then(Commands.argument("password", StringArgumentType.word()).executes(context -> 1)));
        CommandDispatcher<CommandSourceStack> vanillaCommands =
                helper.getLevel().getServer().getCommands().getDispatcher();
        drainChatKeys(channel);

        try (PlayerLoginGate.ForcedVerdict ignored =
                     PlayerLoginGate.forceVerdictForTest(player.getUUID(), PlayerLoginGate.Verdict.NOT_LOGGED_IN)) {
            CommandEvent vanilla = new CommandEvent(vanillaCommands.parse("list", source));
            MinecraftForge.EVENT_BUS.post(vanilla);
            helper.assertTrue(vanilla.isCanceled(), "a vanilla command before /login must be cancelled");
            List<String> keys = drainChatKeys(channel);
            helper.assertTrue(keys.equals(List.of(LoginGateSubsystem.COMMAND_BLOCKED_KEY)),
                    "the player must be told which commands work before login, got " + keys);

            CommandEvent garbage = new CommandEvent(vanillaCommands.parse("no_such_command_xyz", source));
            MinecraftForge.EVENT_BUS.post(garbage);
            helper.assertTrue(garbage.isCanceled(), "an unparseable command must be cancelled, as AccessHub does");

            CommandEvent login = new CommandEvent(accessHub.parse("login secret", source));
            MinecraftForge.EVENT_BUS.post(login);
            helper.assertTrue(!login.isCanceled(), "/login itself must pass, or nobody could ever log in");
        }

        CommandEvent afterLogin = new CommandEvent(vanillaCommands.parse("list", source));
        MinecraftForge.EVENT_BUS.post(afterLogin);
        helper.assertTrue(!afterLogin.isCanceled(), "after login commands must work again");
        helper.succeed();
    }

    /**
     * 扔物品: 走原版 Q 键的真实路径 (ServerPlayer.drop -> ForgeHooks.onPlayerTossEvent)。Forge 取消扔出时不放回
     * 物品, 所以只取消不够 —— 登录门取消之后必须把物品放回背包, 避免物品丢失。
     */
    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void itemTossedBeforeLoginGoesBackToTheInventory(GameTestHelper helper) {
        ServerPlayer player = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);
        Inventory inventory = player.getInventory();
        inventory.setItem(inventory.selected, new ItemStack(Items.DIAMOND, 5));

        try (PlayerLoginGate.ForcedVerdict ignored =
                     PlayerLoginGate.forceVerdictForTest(player.getUUID(), PlayerLoginGate.Verdict.NOT_LOGGED_IN)) {
            boolean dropped = player.drop(false);
            helper.assertTrue(!dropped, "a toss before /login must not produce an item entity");
            helper.assertTrue(inventory.countItem(Items.DIAMOND) == 5,
                    "the tossed diamond must be back in the inventory, not lost; have "
                            + inventory.countItem(Items.DIAMOND));
        }

        // 对照: 登录后同一条扔出路径照常产出物品实体 (拿到实体好当场清掉, 不把钻石留在别的用例的场地里)。
        ItemEntity tossed = player.drop(inventory.removeFromSelected(false), false);
        helper.assertTrue(tossed != null, "after login a toss must produce an item entity again");
        helper.assertTrue(inventory.countItem(Items.DIAMOND) == 4,
                "after login one diamond leaves the inventory; have " + inventory.countItem(Items.DIAMOND));
        tossed.discard();
        helper.succeed();
    }

    // ============================================================
    // 7. 连接核对 (LoginRaceGuard): 登录结果与发出 /login 的连接核对
    // ============================================================

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void loginResultLandingOnAnotherConnectionIsRevoked(GameTestHelper helper) {
        FakeAccessHubMod mod = new FakeAccessHubMod();
        PlayerLoginGate.Binding binding = PlayerLoginGate.Binding.of(mod);
        LoginRaceGuard guard = new LoginRaceGuard();
        Object issuingConnection = new Object();
        Object newConnection = new Object();
        long window = LoginRaceGuard.WINDOW_TICKS;

        // 发出 /login 的连接在结果写入前断开, 随后同一 UUID 的另一条连接被报告为已登录: 核对不通过, 撤销。
        UUID orphaned = UUID.randomUUID();
        guard.loginIssued(orphaned, issuingConnection, 1000);
        guard.left(orphaned, issuingConnection, 1006);
        mod.service.authed.add(orphaned);
        helper.assertTrue(decide(binding, guard, orphaned, newConnection, 1010)
                        == PlayerLoginGate.Verdict.NOT_LOGGED_IN,
                "a login that lands on a new connection right after the /login sender dropped must be refused");
        helper.assertTrue(!mod.service.authed.contains(orphaned), "that login must be revoked through clearSession");
        // 撤过一次即结案: 之后在这条连接上真正的 /login 照常生效。
        mod.service.authed.add(orphaned);
        helper.assertTrue(decide(binding, guard, orphaned, newConnection, 1011) == PlayerLoginGate.Verdict.ALLOWED,
                "after one revocation a real /login on the new connection must stand");

        // 新连接上再发一次 /login 不清除孤儿记录: 核对只认发出那次 /login 的连接。
        UUID stubborn = UUID.randomUUID();
        guard.loginIssued(stubborn, issuingConnection, 2000);
        guard.left(stubborn, issuingConnection, 2002);
        guard.loginIssued(stubborn, newConnection, 2003);
        mod.service.authed.add(stubborn);
        helper.assertTrue(decide(binding, guard, stubborn, newConnection, 2004)
                        == PlayerLoginGate.Verdict.NOT_LOGGED_IN,
                "a /login typed on the new connection must not clear the orphaned one");

        // 对照: 发 /login 的连接一直在线, 结果落在它自己身上 —— 不撤。
        UUID steady = UUID.randomUUID();
        guard.loginIssued(steady, issuingConnection, 3000);
        mod.service.authed.add(steady);
        helper.assertTrue(decide(binding, guard, steady, issuingConnection, 3005) == PlayerLoginGate.Verdict.ALLOWED
                        && mod.service.authed.contains(steady),
                "a login that lands on the connection that sent /login must stand");

        // 对照: 断线时离 /login 已过窗口, 结果早已写入, 不算孤儿。
        UUID late = UUID.randomUUID();
        guard.loginIssued(late, issuingConnection, 4000);
        guard.left(late, issuingConnection, 4000 + window + 1);
        mod.service.authed.add(late);
        helper.assertTrue(decide(binding, guard, late, newConnection, 4000 + window + 2)
                        == PlayerLoginGate.Verdict.ALLOWED,
                "a /login sent longer than the window before the disconnect must not orphan");

        // 对照: 孤儿过了期限, 新连接的登录不再追究。
        UUID expired = UUID.randomUUID();
        guard.loginIssued(expired, issuingConnection, 5000);
        guard.left(expired, issuingConnection, 5001);
        mod.service.authed.add(expired);
        helper.assertTrue(decide(binding, guard, expired, newConnection, 5000 + window + 1)
                        == PlayerLoginGate.Verdict.ALLOWED,
                "an orphan past its deadline must no longer revoke anything");

        // 对照: OFF 与非专用服务器上登录门不动 AccessHub 的状态。
        UUID untouched = UUID.randomUUID();
        guard.loginIssued(untouched, issuingConnection, 6000);
        guard.left(untouched, issuingConnection, 6001);
        mod.service.authed.add(untouched);
        helper.assertTrue(PlayerLoginGate.decide(LoginGateMode.OFF, binding, true, guard, untouched,
                        newConnection, 6002) == PlayerLoginGate.Verdict.ALLOWED
                        && PlayerLoginGate.decide(LoginGateMode.AUTO, binding, false, guard, untouched,
                        newConnection, 6002) == PlayerLoginGate.Verdict.ALLOWED
                        && mod.service.authed.contains(untouched),
                "OFF / non-dedicated: the connection check must neither deny nor revoke");
        helper.succeed();
    }

    private static PlayerLoginGate.Verdict decide(PlayerLoginGate.Binding binding, LoginRaceGuard guard, UUID player,
                                                  Object connection, long now) {
        return PlayerLoginGate.decide(LoginGateMode.AUTO, binding, true, guard, player, connection, now);
    }

    // ============================================================
    // 8. 开服审计: 没有 AccessHub 账号的 OP 名字
    // ============================================================

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void opAccountAuditListsNamesWithoutAnAccount(GameTestHelper helper) {
        try {
            FakeAccessHubMod mod = new FakeAccessHubMod();
            mod.service.registered.add("Owner");
            PlayerLoginGate.Binding binding = PlayerLoginGate.Binding.of(mod);
            List<String> missing = PlayerLoginGate.unregisteredNames(binding, new String[] {"Owner", "Ghost"});
            helper.assertTrue(List.of("Ghost").equals(missing),
                    "only the OP name without an AccessHub account must be reported, got " + missing);

            mod.config.enabled = false;
            helper.assertTrue(PlayerLoginGate.unregisteredNames(binding, new String[] {"Ghost"}) == null,
                    "auth.enabled=false: there is nothing to audit");
            mod.config.enabled = true;
            mod.service = null;
            helper.assertTrue(PlayerLoginGate.unregisteredNames(binding, new String[] {"Ghost"}) == null,
                    "AccessHub not running: the audit must skip instead of failing");

            PlayerLoginGate.Binding legacy = PlayerLoginGate.Binding.of(new NoIsRegisteredMod());
            helper.assertTrue(legacy.kind == PlayerLoginGate.Binding.Kind.BOUND,
                    "isRegistered is optional: an AccessHub without it must still bind the gate");
            helper.assertTrue(PlayerLoginGate.unregisteredNames(legacy, new String[] {"Ghost"}) == null,
                    "without isRegistered the audit must skip");
            helper.assertTrue(PlayerLoginGate.unregisteredNames(PlayerLoginGate.Binding.absent(),
                            new String[] {"Ghost"}) == null,
                    "without AccessHub there is nothing to audit");
        } catch (ReflectiveOperationException e) {
            helper.fail("the audit threw " + e);
        }
        helper.succeed();
    }

    // ============================================================
    // 9. 伤害与爆炸: 按来源拦 (近战之外, 别的 mod 经自己的网络包开火也走这里)
    // ============================================================

    /**
     * 未登录者造成的伤害一律不结算、引发的爆炸整场取消; 登录态无从判定与已登录时照常。
     *
     * 走原版 {@code LivingEntity.hurt} 的真实路径 (它在最前面派 LivingAttackEvent), 四种来源各一头牛:
     * 近战 (来源实体即玩家)、箭 (间接来源回溯到射手)、只填了子弹的来源 (别的 mod 自造伤害来源的形状, 靠弹射物
     * 主人兜底)、爆炸 (TNT 回溯到点燃者)。每头牛只挨一次, 不受无敌帧干扰。删掉 register 里任何一行对应的
     * cancelBeforeLogin, 或 culpritOf 的弹射物兜底, 对应断言必挂。
     */
    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void damageAndExplosionsCausedBeforeLoginAreCancelled(GameTestHelper helper) {
        ServerPlayer player = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);
        ServerLevel level = helper.getLevel();
        Arrow arrow = new Arrow(level, player);
        Vec3 center = Vec3.atCenterOf(helper.absolutePos(new BlockPos(2, 2, 2)));
        PrimedTnt tnt = new PrimedTnt(level, center.x, center.y, center.z, player);
        Explosion blast = new Explosion(level, tnt, center.x, center.y, center.z, 2.0F, false,
                Explosion.BlockInteraction.KEEP);

        DamageSource melee = level.damageSources().playerAttack(player);
        DamageSource shot = level.damageSources().arrow(arrow, player);
        DamageSource bareBullet = level.damageSources().arrow(arrow, null);
        DamageSource explosion = level.damageSources().explosion(blast);
        helper.assertTrue(bareBullet.getEntity() == null && bareBullet.getDirectEntity() == arrow,
                "precondition: the bare-bullet source names only the projectile");

        try (PlayerLoginGate.ForcedVerdict ignored =
                     PlayerLoginGate.forceVerdictForTest(player.getUUID(), PlayerLoginGate.Verdict.NOT_LOGGED_IN)) {
            for (DamageSource source : new DamageSource[] {melee, shot, bareBullet, explosion}) {
                Cow target = helper.spawn(EntityType.COW, new BlockPos(1, 2, 1));
                boolean hurt = target.hurt(source, 4.0F);
                helper.assertTrue(!hurt && target.getHealth() == target.getMaxHealth(),
                        source.getMsgId() + " damage caused by a player who has not /login-ed must not land; hurt="
                                + hurt + ", health=" + target.getHealth() + "/" + target.getMaxHealth());
                target.discard();
            }

            Cow target = helper.spawn(EntityType.COW, new BlockPos(1, 2, 1));
            LivingHurtEvent directHurt = new LivingHurtEvent(target, melee, 4.0F);
            MinecraftForge.EVENT_BUS.post(directHurt);
            helper.assertTrue(directHurt.isCanceled(),
                    "LivingHurtEvent from a player who has not /login-ed must be cancelled too (paths that skip hurt())");
            target.discard();

            ExplosionEvent.Start start = new ExplosionEvent.Start(level, blast);
            MinecraftForge.EVENT_BUS.post(start);
            helper.assertTrue(start.isCanceled(),
                    "an explosion set off by a player who has not /login-ed must be cancelled before it breaks blocks");
        }

        // 登录态无从判定: 原版玩法照常 (一次 AccessHub 故障不能让全服打不了怪)。
        try (PlayerLoginGate.ForcedVerdict ignored =
                     PlayerLoginGate.forceVerdictForTest(player.getUUID(), PlayerLoginGate.Verdict.UNAVAILABLE)) {
            Cow target = helper.spawn(EntityType.COW, new BlockPos(1, 2, 1));
            helper.assertTrue(target.hurt(melee, 4.0F) && target.getHealth() < target.getMaxHealth(),
                    "UNAVAILABLE must not block combat");
            target.discard();
        }

        // 对照: 登录后四种来源都照常结算, 爆炸照常进行。
        for (DamageSource source : new DamageSource[] {melee, shot, bareBullet, explosion}) {
            Cow target = helper.spawn(EntityType.COW, new BlockPos(1, 2, 1));
            helper.assertTrue(target.hurt(source, 4.0F) && target.getHealth() < target.getMaxHealth(),
                    "after login " + source.getMsgId() + " damage must land again");
            target.discard();
        }
        ExplosionEvent.Start allowedStart = new ExplosionEvent.Start(level, blast);
        MinecraftForge.EVENT_BUS.post(allowedStart);
        helper.assertTrue(!allowedStart.isCanceled(), "after login an explosion must not be cancelled by the gate");
        helper.succeed();
    }

    // ============================================================
    // 10. 登录确认: 判定翻为放行时通知一次
    // ============================================================

    /**
     * 登录确认的全部状态转换, 同步驱动 (巡检与进服处理直接调, 一个方法体内没有真实 tick 插进来)。
     * 真实 tick 接线与平板推送由 WebUiLoginGateGameTests 经真实巡检覆盖, 婚姻补发由 MarriageLoginGateGameTests 覆盖。
     *
     *  - 进服即放行: 当场 atJoin=true 通知一次, 不进等待表;
     *  - 进服被拒: 不通知, 进等待表; 仍被拒时巡检不通知; 翻为放行后的第一次巡检 atJoin=false 通知且只通知一次;
     *  - 任何入口的拒绝 (含 UNAVAILABLE) 都会进表;
     *  - 离线 (forgetConfirmation) 之后不再通知; 撤销的监听器不再收到。
     */
    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void loginConfirmationFiresOnceWhenTheVerdictFlips(GameTestHelper helper) {
        ServerPlayer player = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);
        MinecraftServer server = helper.getLevel().getServer();
        List<Boolean> fired = new ArrayList<>();
        PlayerLoginGate.ListenerRegistration registration = PlayerLoginGate.onLoginConfirmed((who, atJoin) -> {
            if (who == player) {
                fired.add(atJoin);
            }
        });
        try {
            PlayerLoginGate.confirmAtJoin(player);
            helper.assertTrue(fired.equals(List.of(true)) && !PlayerLoginGate.awaitingConfirmation(player.getUUID()),
                    "a player already allowed at join must be confirmed on the spot (atJoin=true), got " + fired);
            fired.clear();

            try (PlayerLoginGate.ForcedVerdict ignored =
                         PlayerLoginGate.forceVerdictForTest(player.getUUID(), PlayerLoginGate.Verdict.NOT_LOGGED_IN)) {
                PlayerLoginGate.confirmAtJoin(player);
                helper.assertTrue(fired.isEmpty() && PlayerLoginGate.awaitingConfirmation(player.getUUID()),
                        "a player denied at join must be queued, not confirmed, got " + fired);
                PlayerLoginGate.sweepLoginConfirmations(server);
                helper.assertTrue(fired.isEmpty(), "while still denied the sweep must not confirm, got " + fired);
            }
            PlayerLoginGate.sweepLoginConfirmations(server);
            helper.assertTrue(fired.equals(List.of(false)) && !PlayerLoginGate.awaitingConfirmation(player.getUUID()),
                    "the first sweep after the verdict flips must confirm once with atJoin=false, got " + fired);
            PlayerLoginGate.sweepLoginConfirmations(server);
            helper.assertTrue(fired.equals(List.of(false)), "a flip must be confirmed exactly once, got " + fired);
            fired.clear();

            // 任何入口的拒绝都会进表 (平板请求被拒、菜单被关……), UNAVAILABLE 同样。
            try (PlayerLoginGate.ForcedVerdict ignored =
                         PlayerLoginGate.forceVerdictForTest(player.getUUID(), PlayerLoginGate.Verdict.UNAVAILABLE)) {
                PlayerLoginGate.check(player);
            }
            helper.assertTrue(PlayerLoginGate.awaitingConfirmation(player.getUUID()),
                    "any denial seen by check() must queue the player");
            PlayerLoginGate.sweepLoginConfirmations(server);
            helper.assertTrue(fired.equals(List.of(false)), "UNAVAILABLE -> ALLOWED must confirm too, got " + fired);
            fired.clear();

            // 离线的玩家不再等。
            try (PlayerLoginGate.ForcedVerdict ignored =
                         PlayerLoginGate.forceVerdictForTest(player.getUUID(), PlayerLoginGate.Verdict.NOT_LOGGED_IN)) {
                PlayerLoginGate.check(player);
            }
            PlayerLoginGate.forgetConfirmation(player);
            PlayerLoginGate.sweepLoginConfirmations(server);
            helper.assertTrue(fired.isEmpty(), "a player who left must not be confirmed later, got " + fired);
        } finally {
            registration.close();
        }

        try (PlayerLoginGate.ForcedVerdict ignored =
                     PlayerLoginGate.forceVerdictForTest(player.getUUID(), PlayerLoginGate.Verdict.NOT_LOGGED_IN)) {
            PlayerLoginGate.check(player);
        }
        PlayerLoginGate.sweepLoginConfirmations(server);
        helper.assertTrue(fired.isEmpty(), "a closed registration must not be notified any more, got " + fired);
        helper.succeed();
    }

    /** 取出聊天栏 (非动作栏) 系统消息的翻译键。 */
    private static List<String> drainChatKeys(EmbeddedChannel channel) {
        List<String> keys = new ArrayList<>();
        Object outbound;
        while ((outbound = channel.readOutbound()) != null) {
            try {
                if (outbound instanceof ClientboundSystemChatPacket packet && !packet.overlay()
                        && packet.content().getContents() instanceof TranslatableContents translatable) {
                    keys.add(translatable.getKey());
                }
            } finally {
                ReferenceCountUtil.release(outbound);
            }
        }
        return keys;
    }

    /** 模拟服务端打开一个本 mod 的菜单 (类在 com.miningdim 包下)。 */
    private static AbstractContainerMenu openTestMenu(ServerPlayer player) {
        AbstractContainerMenu menu = new AbstractContainerMenu(null, 90) {
            @Override
            public ItemStack quickMoveStack(Player who, int index) {
                return ItemStack.EMPTY;
            }

            @Override
            public boolean stillValid(Player who) {
                return true;
            }
        };
        player.containerMenu = menu;
        return menu;
    }

    // ============================================================
    // 同形假 AccessHub (方法名 / 参数 / 返回类型与 0.5.3 一致)
    // ============================================================

    /** 对应 AccessHubMod: 两个 getter 在"没跑起来"时返回 null。 */
    public static final class FakeAccessHubMod {
        FakePlayerAuthService service = new FakePlayerAuthService();
        FakeAccessHubConfig config = new FakeAccessHubConfig();

        public FakePlayerAuthService getPlayerAuthService() {
            return service;
        }

        public FakeAccessHubConfig getConfig() {
            return config;
        }
    }

    /** 对应 PlayerAuthService。 */
    public static final class FakePlayerAuthService {
        final Set<UUID> authed = new HashSet<>();
        final Set<String> registered = new HashSet<>();
        boolean explode;

        public boolean isAuthed(UUID uuid) {
            if (explode) {
                throw new IllegalStateException("simulated AccessHub failure");
            }
            return uuid != null && authed.contains(uuid);
        }

        public void clearSession(UUID uuid) {
            authed.remove(uuid);
        }

        public boolean isRegistered(String name) {
            return registered.contains(name);
        }
    }

    /** 服务没有可选的 isRegistered: 仍须绑定成功, 只是开服审计跳过。 */
    public static final class NoIsRegisteredMod {
        public NoIsRegisteredService getPlayerAuthService() {
            return new NoIsRegisteredService();
        }

        public FakeAccessHubConfig getConfig() {
            return new FakeAccessHubConfig();
        }
    }

    public static final class NoIsRegisteredService {
        public boolean isAuthed(UUID uuid) {
            return false;
        }

        public void clearSession(UUID uuid) {
        }
    }

    /** 对应 AccessHubConfig。 */
    public static final class FakeAccessHubConfig {
        boolean enabled = true;

        public boolean isPlayerAuthEnabled() {
            return enabled;
        }
    }

    /** isAuthed 返回装箱 Boolean: 形状不同, 不许绑。 */
    public static final class BoxedReturnMod {
        public BoxedReturnService getPlayerAuthService() {
            return new BoxedReturnService();
        }

        public FakeAccessHubConfig getConfig() {
            return new FakeAccessHubConfig();
        }
    }

    public static final class BoxedReturnService {
        public Boolean isAuthed(UUID uuid) {
            return Boolean.TRUE;
        }

        public void clearSession(UUID uuid) {
        }
    }

    /** 服务缺 clearSession: 不许绑。 */
    public static final class NoClearSessionMod {
        public NoClearSessionService getPlayerAuthService() {
            return new NoClearSessionService();
        }

        public FakeAccessHubConfig getConfig() {
            return new FakeAccessHubConfig();
        }
    }

    public static final class NoClearSessionService {
        public boolean isAuthed(UUID uuid) {
            return true;
        }
    }

    /** 缺 getConfig: 不许绑。 */
    public static final class NoConfigMod {
        public FakePlayerAuthService getPlayerAuthService() {
            return new FakePlayerAuthService();
        }
    }
}
