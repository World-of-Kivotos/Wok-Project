package com.miningdim.core.auth;

import net.minecraft.gametest.framework.GameTestServer;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.server.ServerLifecycleHooks;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

/**
 * 登录门: 玩家通过 AccessHub 的 /login 之前, 本 mod 不处理他发来的任何有副作用或读私有数据的请求。
 *
 * 为什么需要: 服务器是离线模式, 名字不经 Mojang 验证, 玩家身份以 AccessHub 的 /login 为准。本 mod 自己的请求
 * 入口 (网络包、菜单、事件监听) 由本门按 AccessHub 的登录态统一把关: 未通过 /login 的连接不得触发本 mod 的任何操作。
 *
 * 判定只在服务端做, 每次请求现查, 不按会话缓存: AccessHub 的管理员重置会在玩家在线时清掉登录态, 缓存会让
 * 那次重置对本 mod 失效。
 *
 * 调用方拿到 {@link Verdict} 后自己决定怎么拒 (WebUI 回业务错误码, 键位包静默丢弃, 菜单直接关掉, 玩家交互
 * 事件在 HIGHEST 优先级取消 —— 见 {@link LoginGateSubsystem})。
 * 所有调用点都在服务端主线程 (网络包 handler 都经 enqueueWork 切回主线程), AccessHub 的登录态集合本身
 * 也允许任意线程读, 故本类不做额外同步; 只有一次性的 AccessHub 绑定解析加了锁, 保证"绑定失败"的 ERROR
 * 只打一遍。
 *
 * 另有两项防御性检查 (不要删):
 * <ul>
 *   <li>连接核对 —— {@link LoginRaceGuard}: 把登录结果与发出 /login 的连接核对, 那条连接已断开时撤销该登录,
 *       经 {@link #check} 与逐 tick 巡检执行;</li>
 *   <li>开服审计 —— {@link #auditOpAccounts}: 列出尚未注册 AccessHub 账号的 OP 名字, 提醒尽快注册。</li>
 * </ul>
 *
 * 登录完成的通知: /login 是异步的, 被拒过的玩家由逐 tick 巡检重查, 判定翻为放行时通知
 * {@link #onLoginConfirmed} 注册的监听器 (见 {@link LoginConfirmations})。别的模块要"等玩家登录后再做"的事
 * (撤掉平板的登录提示、补发进服时压下的私密通知) 挂在这里, 不要自己轮询。
 *
 * 模式 (security.loginGate) 由配置子系统在 register 期经 {@link #bindModeSource} 注入, 本包不引用 config 包:
 * core 是最底层, 配置包依赖它而不是反过来。
 */
public final class PlayerLoginGate {

    private static final Logger LOGGER = LoggerFactory.getLogger("miningdim/login-gate");

    /** 一次判定的结果。 */
    public enum Verdict {
        /** 放行。 */
        ALLOWED,
        /** 装了 AccessHub、登录开着, 但这条连接还没 /login。 */
        NOT_LOGGED_IN,
        /** 登录态无从判定 (AccessHub 没跑起来 / API 对不上 / REQUIRED 档下缺失), 按拒绝处理。 */
        UNAVAILABLE;

        public boolean allowed() {
            return this == ALLOWED;
        }
    }

    /**
     * AccessHub 绑定结果。ModList 在模组加载完成后不再变化, 故整个进程只解析一次。
     */
    static final class Binding {

        enum Kind {
            /** 没装 AccessHub。 */
            ABSENT,
            /** 装了且 API 形状与核实过的版本一致。 */
            BOUND,
            /** 装了但 API 对不上 (或拿不到模组实例)。 */
            BROKEN
        }

        final Kind kind;
        final AccessHubLoginProbe probe;
        final String problem;

        /**
         * 运行期调用 AccessHub 失败只打一次 ERROR (之后降为 DEBUG), 否则每个请求一条堆栈。
         *
         * 挂在绑定上而不是进程级静态: GameTest 用假绑定故意制造失败, 进程级闩会被它们先拨掉, 同一进程里真实绑定
         * 随后出的故障就只剩 DEBUG。各绑定各自一只闩, 生产里只有 {@link #binding()} 那一个。
         */
        final AtomicBoolean invocationFailureLogged = new AtomicBoolean();

        /** "装了 AccessHub 却没跑起来" 只打一次 ERROR, 理由与挂法同上。 */
        final AtomicBoolean notStartedLogged = new AtomicBoolean();

        private Binding(Kind kind, AccessHubLoginProbe probe, String problem) {
            this.kind = kind;
            this.probe = probe;
            this.problem = problem;
        }

        static Binding absent() {
            return new Binding(Kind.ABSENT, null, null);
        }

        static Binding broken(String problem) {
            return new Binding(Kind.BROKEN, null, Objects.requireNonNull(problem, "problem"));
        }

        /**
         * 按形状绑定一个 AccessHub 模组实例 (生产里是 ModList 给的那个, GameTest 里是同形的假对象)。
         *
         * 这里接住的三类异常各有来由: 方法缺失 / 返回类型不符是 {@link ReflectiveOperationException};
         * 反射访问被模块系统拒绝是 {@link RuntimeException} (InaccessibleObjectException 等); 被引用的
         * 类加载不出来是 {@link LinkageError}。三者对本门的含义相同 —— 无从判定, 关门。
         */
        static Binding of(Object modInstance) {
            try {
                return new Binding(Kind.BOUND, AccessHubLoginProbe.bind(modInstance), null);
            } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
                return broken(e.toString());
            }
        }
    }

    /** 已解析的 AccessHub 绑定; null = 还没解析过。 */
    private static volatile Binding binding;

    /** 登录门模式的来源 (见 {@link #bindModeSource}); null = 配置子系统还没注册。 */
    private static volatile Supplier<LoginGateMode> modeSource;

    /**
     * GameTest 注入的逐玩家判定 (见 {@link #forceVerdictForTest})。只在 GameTest 服务端上登记、也只在那里被读
     * ({@link #forcedVerdict}); 专用服务器与单人存档上它恒为空且从不被查。
     */
    private static final Map<UUID, Verdict> FORCED_VERDICTS = new ConcurrentHashMap<>();

    /** 登录结果与连接的核对 (见 {@link LoginRaceGuard})。 */
    private static final LoginRaceGuard RACE_GUARD = new LoginRaceGuard();

    /** 被拒过、等着判定翻为放行的玩家与"登录已确认"监听器 (见 {@link #onLoginConfirmed})。 */
    private static final LoginConfirmations CONFIRMATIONS = new LoginConfirmations();

    /** 登录确认巡检的间隔: 5 tick (0.25 秒)。/login 生效到平板撤提示, 最多多等这么久。 */
    static final int CONFIRMATION_SWEEP_INTERVAL_TICKS = 5;

    private PlayerLoginGate() {
    }

    /**
     * 注入登录门模式的来源 (配置子系统在 register 期调用, 传 {@code MiningServerConfig.LOGIN_GATE_MODE::get})。
     *
     * 每次判定现读, 不缓存: 运维改 security.loginGate (比如应急切到 OFF) 后下一次请求即生效。
     */
    public static void bindModeSource(Supplier<LoginGateMode> source) {
        modeSource = Objects.requireNonNull(source, "source");
    }

    /** 当前生效的模式。来源没注入是装配缺陷 (配置子系统必须排在任何判定之前), 按 C9 直接抛, 不猜一个默认值。 */
    public static LoginGateMode mode() {
        Supplier<LoginGateMode> source = modeSource;
        if (source == null) {
            throw new IllegalStateException("PlayerLoginGate: the security.loginGate source is not bound yet "
                    + "(ConfigSystem must register before anything checks the login gate)");
        }
        return source.get();
    }

    /**
     * 判定该玩家此刻能否让本 mod 处理他的请求。
     *
     * 被拒 (任何一种) 的玩家顺手记进"等待登录确认"表, 之后由逐 tick 巡检发现他登录了 (见 {@link #onLoginConfirmed})。
     */
    public static Verdict check(ServerPlayer player) {
        MinecraftServer server = player.getServer();
        Verdict verdict = forcedVerdict(server, player.getUUID());
        if (verdict == null) {
            verdict = decide(mode(), binding(), server.isDedicatedServer(), RACE_GUARD, player.getUUID(),
                    connectionOf(player), server.getTickCount());
        }
        if (!verdict.allowed()) {
            CONFIRMATIONS.await(player.getUUID());
        }
        return verdict;
    }

    /** GameTest 注入的判定; 不在 GameTest 服务端上一律视为没有 (见 {@link #forceVerdictForTest})。 */
    private static Verdict forcedVerdict(MinecraftServer server, UUID player) {
        if (FORCED_VERDICTS.isEmpty() || !(server instanceof GameTestServer)) {
            return null;
        }
        return FORCED_VERDICTS.get(player);
    }

    /**
     * {@link #evaluate} 加上连接核对: AccessHub 说"已登录", 但这份登录态来自一条已经断开的连接发出的 /login
     * (见 {@link LoginRaceGuard}) 时, 当场撤销并按未登录处理。参数全由调用方给, 供 GameTest 驱动。
     */
    static Verdict decide(LoginGateMode mode, Binding binding, boolean dedicatedServer, LoginRaceGuard guard,
                          UUID player, Object connection, long now) {
        Verdict verdict = evaluate(mode, binding, dedicatedServer, player);
        if (verdict != Verdict.ALLOWED || mode == LoginGateMode.OFF || !dedicatedServer
                || binding.kind != Binding.Kind.BOUND || !guard.suspects(player, connection, now)) {
            return verdict;
        }
        try {
            return revokeRacedLogin(binding, guard, player) ? Verdict.NOT_LOGGED_IN : verdict;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            // 核对不通过, 撤销却失败: 关门。
            logInvocationFailure(binding, e);
            return Verdict.UNAVAILABLE;
        }
    }

    /**
     * 经 AccessHub 撤销一份连接核对不通过 ({@link LoginRaceGuard#suspects}) 的登录态, 返回是否撤了。调用方先完成核对。
     *
     * 只在 AccessHub 确实报告 AUTHED 时撤 (auth 关闭时 isAuthed 对谁都是 false, 没有可撤的)。撤过一次即结案:
     * 孤儿 /login 的结果最多写入一次, 之后正常 /login 不再受影响。
     */
    private static boolean revokeRacedLogin(Binding binding, LoginRaceGuard guard, UUID player)
            throws ReflectiveOperationException {
        boolean revoked = binding.probe.revokeIfAuthed(player);
        if (revoked) {
            guard.resolved(player);
            LOGGER.warn("[miningdim] revoked the AccessHub login of {}: the connection that sent /login had "
                            + "disconnected (within {} ticks), so the login is not accepted for the current "
                            + "connection (defensive check). The player must /login again.",
                    player, LoginRaceGuard.WINDOW_TICKS);
        }
        return revoked;
    }

    /**
     * 登录门此刻是否真在强制 AccessHub 的登录态 (专用服务器、非 OFF、已绑定 AccessHub)。连接核对与开服审计只在
     * 这种情况下做事。
     */
    private static boolean enforcing(MinecraftServer server) {
        return server.isDedicatedServer() && mode() != LoginGateMode.OFF && binding().kind == Binding.Kind.BOUND;
    }

    private static Object connectionOf(ServerPlayer player) {
        return player.connection == null ? null : player.connection.connection;
    }

    /**
     * 玩家发出了 /login (或别名 /l): 由 {@link LoginGateSubsystem} 的命令监听器在命令执行之前调用。只记"未登录时"
     * 发的 —— 已登录时 AccessHub 直接回"你已登录", 不起异步校验, 也就没有需要核对的登录结果。
     */
    static void noteLoginCommand(ServerPlayer player) {
        MinecraftServer server = player.getServer();
        if (!enforcing(server) || connectionOf(player) == null) {
            return;
        }
        Binding bound = binding();
        AccessHubLoginProbe.State state;
        try {
            state = bound.probe.stateOf(player.getUUID());
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            logInvocationFailure(bound, e);
            return;
        }
        if (state == AccessHubLoginProbe.State.NOT_AUTHED) {
            RACE_GUARD.loginIssued(player.getUUID(), connectionOf(player), server.getTickCount());
        }
    }

    /** 玩家断开: 他若刚发过 /login 且结果可能尚未写入, 那次 /login 记为孤儿, 供连接核对。 */
    static void noteLogout(ServerPlayer player) {
        Object connection = connectionOf(player);
        if (connection != null) {
            RACE_GUARD.left(player.getUUID(), connection, player.getServer().getTickCount());
        }
    }

    /**
     * 每 tick 巡检孤儿 /login (平时孤儿表为空, 只读一次 isEmpty)。不能只靠 {@link #check}: 登录态一生效, 原版交互
     * 也随之放开, 核对不能等到玩家碰本 mod 的入口时才做。
     */
    static void sweepRacedLogins(MinecraftServer server) {
        if (!RACE_GUARD.hasOrphans()) {
            return;
        }
        if (!enforcing(server)) {
            RACE_GUARD.clear();
            return;
        }
        long now = server.getTickCount();
        Binding bound = binding();
        for (UUID id : RACE_GUARD.liveOrphans(now)) {
            ServerPlayer online = server.getPlayerList().getPlayer(id);
            if (online == null || !RACE_GUARD.suspects(id, connectionOf(online), now)) {
                continue;
            }
            boolean revoked;
            try {
                revoked = revokeRacedLogin(bound, RACE_GUARD, id);
            } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
                logInvocationFailure(bound, e);
                continue;
            }
            if (revoked) {
                CONFIRMATIONS.await(id);
                online.sendSystemMessage(rejectionMessage(Verdict.NOT_LOGGED_IN));
            }
        }
    }

    /** 停服时清空连接核对状态与"等待登录确认"表 (单人存档可以反复开关同一进程里的服务端)。 */
    static void resetServerState() {
        RACE_GUARD.clear();
        CONFIRMATIONS.clearAwaiting();
    }

    // ============================================================
    // 登录确认: 被拒过的玩家判定翻为放行时通知监听器
    // ============================================================

    /**
     * "登录已确认"监听器。
     *
     * 触发时机 (每条连接至少一次, 都在服务端主线程):
     * <ul>
     *   <li>{@code atJoin=true}: 进服那一刻 (PlayerLoggedInEvent 的 LOWEST, 排在残留登录态的撤销之后) 就已放行 ——
     *       单人 / 局域网 / 没装 AccessHub 的服务器上, 每个玩家都走这一条;</li>
     *   <li>{@code atJoin=false}: 进服时 (或之后任何一次判定) 被拒, 随后由逐 tick 巡检发现判定翻为放行 ——
     *       AccessHub 服务器上 /login 生效、或管理员重置后重新 /login。</li>
     * </ul>
     * 所以"进服后要等登录才能发给他的东西"挂在这里即可覆盖所有服务器形态, 不必自己判断有没有 AccessHub。
     */
    @FunctionalInterface
    public interface LoginConfirmedListener {
        void onLoginConfirmed(ServerPlayer player, boolean atJoin);
    }

    /**
     * 注册一个"登录已确认"监听器 (子系统在 register 期调用; 生产注册不撤销)。返回的句柄供 GameTest 撤销。
     * 监听器抛出的异常只记 ERROR, 不影响其它监听器 (见 {@link LoginConfirmations#fire})。
     */
    public static ListenerRegistration onLoginConfirmed(LoginConfirmedListener listener) {
        return CONFIRMATIONS.add(listener);
    }

    /** {@link #onLoginConfirmed} 的撤销句柄。 */
    public static final class ListenerRegistration implements AutoCloseable {

        private final Runnable remove;

        ListenerRegistration(Runnable remove) {
            this.remove = remove;
        }

        @Override
        public void close() {
            remove.run();
        }
    }

    /**
     * 进服 (由 {@link LoginGateSubsystem} 在 PlayerLoggedInEvent 的 LOWEST 调用, 排在 {@link #revokeInheritedSession}
     * 之后): 已放行就立刻以 atJoin=true 通知; 被拒则 {@link #check} 已把他记进等待表, 交给巡检。
     */
    static void confirmAtJoin(ServerPlayer player) {
        if (check(player).allowed()) {
            CONFIRMATIONS.forget(player.getUUID());
            CONFIRMATIONS.fire(player, true);
        }
    }

    /** 玩家断开: 不再等他。 */
    static void forgetConfirmation(ServerPlayer player) {
        CONFIRMATIONS.forget(player.getUUID());
    }

    /**
     * 巡检等待表 (由 {@link LoginGateSubsystem} 每 {@link #CONFIRMATION_SWEEP_INTERVAL_TICKS} tick 调用; 表空时只读一次
     * isEmpty)。重查每个被拒过的在线玩家, 判定翻为放行的移出表并以 atJoin=false 通知监听器; 已离线的直接移出。
     *
     * 重查走的就是 {@link #check}, 与任何一次真实请求同一条路径 (含连接核对), 不另立判定。
     */
    static void sweepLoginConfirmations(MinecraftServer server) {
        if (!CONFIRMATIONS.hasAwaiting()) {
            return;
        }
        for (UUID id : CONFIRMATIONS.awaitingSnapshot()) {
            ServerPlayer online = server.getPlayerList().getPlayer(id);
            if (online == null) {
                CONFIRMATIONS.forget(id);
                continue;
            }
            if (check(online).allowed()) {
                CONFIRMATIONS.forget(id);
                CONFIRMATIONS.fire(online, false);
            }
        }
    }

    /** 该玩家是否在等待登录确认 (GameTest 用)。 */
    static boolean awaitingConfirmation(UUID player) {
        return CONFIRMATIONS.isAwaiting(player);
    }

    /**
     * 开服审计: 列出尚未注册 AccessHub 账号的 OP 名字 (ERROR), 提醒尽快注册; 由 {@link LoginGateSubsystem} 在
     * ServerStarted 调用。
     *
     * 登录门认的是 AccessHub 的登录态, 本身不管账号归属; 每个 OP 名字都应当尽早有自己的 AccessHub 账号。
     */
    static void auditOpAccounts(MinecraftServer server) {
        if (!enforcing(server)) {
            return;
        }
        String[] opNames = server.getPlayerList().getOpNames();
        List<String> missing;
        try {
            missing = unregisteredNames(binding(), opNames);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            LOGGER.warn("[miningdim] could not check whether every OP has an AccessHub account", e);
            return;
        }
        if (missing == null) {
            return;
        }
        if (missing.isEmpty()) {
            LOGGER.info("[miningdim] login gate: all {} OP name(s) have an AccessHub account.", opNames.length);
        } else {
            LOGGER.error("[miningdim] these OP names have no AccessHub account yet: {}. Register an AccessHub "
                    + "account for every OP name as soon as possible, or remove the name from ops.json.", missing);
        }
    }

    /**
     * {@link #auditOpAccounts} 的本体: {@code names} 里没有 AccessHub 账号的那些; null = 无从审计 (没绑定、auth
     * 关闭、服务没跑起来、或这版 AccessHub 没有 isRegistered)。绑定由参数给, GameTest 用假对象驱动。
     */
    static List<String> unregisteredNames(Binding binding, String[] names) throws ReflectiveOperationException {
        if (binding.kind != Binding.Kind.BOUND || !binding.probe.canAuditAccounts()) {
            return null;
        }
        UUID nobody = new UUID(0L, 0L);
        AccessHubLoginProbe.State state = binding.probe.stateOf(nobody);
        if (state == AccessHubLoginProbe.State.AUTH_DISABLED || state == AccessHubLoginProbe.State.NOT_STARTED) {
            return null;
        }
        List<String> missing = new ArrayList<>();
        for (String name : names) {
            if (!binding.probe.isRegistered(name)) {
                missing.add(name);
            }
        }
        return missing;
    }

    /** {@link #check} 的布尔简写, 给"拒绝就静默丢弃"的调用点用。 */
    public static boolean allows(ServerPlayer player) {
        return check(player).allowed();
    }

    /**
     * 被拒原因的游戏内文案 (给动作栏用; 平板走 WebUI 错误码, 不用这个)。只在玩家主动的一次交互 (开菜单、
     * 右键物品) 后发一条, 不给客户端可高频触发的包回消息。
     */
    public static Component rejectionMessage(Verdict verdict) {
        return Component.translatable(verdict == Verdict.NOT_LOGGED_IN
                ? "message.miningdim.login_gate.required"
                : "message.miningdim.login_gate.unavailable");
    }

    /**
     * 判定本体, 与全局状态解耦 (模式、绑定、是否专用服务器都由参数给), 供 GameTest 逐格覆盖判定表。
     *
     * 判定顺序即优先级:
     * <ol>
     *   <li>OFF: 放行 (应急开关)。</li>
     *   <li>非专用服务器: 放行。AccessHub 只在专用服务器上启动, 单人 / 局域网 / GameTest 没有登录这一步可等;
     *       在这里关门只会让装了 AccessHub 客户端组件的玩家连单人存档的平板都打不开。</li>
     *   <li>没装 AccessHub: AUTO 放行, REQUIRED 拒绝 (正式服上它缺失说明部署出了问题)。</li>
     *   <li>装了但 API 对不上: 拒绝。</li>
     *   <li>AccessHub 没跑起来 (服务对象为 null): 拒绝。</li>
     *   <li>auth.enabled=false: 放行, 与 AccessHub 自己的口径一致。</li>
     *   <li>否则按 isAuthed 放行或要求登录; 调用本身抛异常则拒绝。</li>
     * </ol>
     */
    static Verdict evaluate(LoginGateMode mode, Binding binding, boolean dedicatedServer, UUID player) {
        if (mode == LoginGateMode.OFF || !dedicatedServer) {
            return Verdict.ALLOWED;
        }
        if (binding.kind == Binding.Kind.ABSENT) {
            return mode == LoginGateMode.REQUIRED ? Verdict.UNAVAILABLE : Verdict.ALLOWED;
        }
        if (binding.kind == Binding.Kind.BROKEN) {
            return Verdict.UNAVAILABLE;
        }
        AccessHubLoginProbe.State state;
        try {
            state = binding.probe.stateOf(player);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            logInvocationFailure(binding, e);
            return Verdict.UNAVAILABLE;
        }
        return switch (state) {
            case AUTHED, AUTH_DISABLED -> Verdict.ALLOWED;
            case NOT_AUTHED -> Verdict.NOT_LOGGED_IN;
            case NOT_STARTED -> {
                if (binding.notStartedLogged.compareAndSet(false, true)) {
                    LOGGER.error("[miningdim] AccessHub is installed but its player auth service is not running "
                            + "(did AccessHub fail to start? see its log). Every mod request from players is "
                            + "rejected until it runs, or until security.loginGate is set to OFF.");
                }
                yield Verdict.UNAVAILABLE;
            }
        };
    }

    /**
     * 进服那一刻撤销"残留的"登录态 (由 {@link LoginGateSubsystem} 在 PlayerLoggedInEvent 的 LOWEST 优先级调用,
     * 排在 AccessHub 自己的 NORMAL 优先级监听之后)。
     *
     * 一条刚建立的连接不可能已经合法地登录过: /login 要等玩家发命令, 设备认证要等一次网络往返, 而这两者的
     * 结果都在服务端主线程写入, 插不进 placeNewPlayer 这一次同步调用。所以此刻若仍是"已登录", 说明 AccessHub 的
     * 进出服清理没有运行 (比如它启动途中出错), 残留的登录态不属于这条新连接, 撤销之 (防御性检查)。
     */
    static void revokeInheritedSession(ServerPlayer player) {
        if (!player.getServer().isDedicatedServer() || mode() == LoginGateMode.OFF) {
            return;
        }
        if (revokeInheritedSession(binding(), player.getUUID())) {
            LOGGER.error("[miningdim] AccessHub still reported {} ({}) as logged in when the connection was "
                            + "created, which means AccessHub's own join/leave cleanup is not running. The "
                            + "leftover login was revoked; the player must /login again. Check AccessHub's "
                            + "startup log.", player.getGameProfile().getName(), player.getUUID());
        }
    }

    /** {@link #revokeInheritedSession(ServerPlayer)} 的本体, 绑定由参数给 (GameTest 用假对象驱动)。 */
    static boolean revokeInheritedSession(Binding binding, UUID player) {
        if (binding.kind != Binding.Kind.BOUND) {
            return false;
        }
        try {
            return binding.probe.revokeIfAuthed(player);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            logInvocationFailure(binding, e);
            return false;
        }
    }

    /**
     * 进程内唯一的 AccessHub 绑定 (首次调用时解析并记一条日志)。
     *
     * 放在同步方法里解析而不是双检锁里内联: 解析只发生一次, 锁的代价无关紧要, 而"绑定失败的 ERROR 只打一遍"
     * 需要它。
     */
    static Binding binding() {
        Binding resolved = binding;
        return resolved != null ? resolved : resolveBinding();
    }

    private static synchronized Binding resolveBinding() {
        if (binding != null) {
            return binding;
        }
        Binding resolved;
        if (!ModList.get().isLoaded(AccessHubLoginProbe.MOD_ID)) {
            resolved = Binding.absent();
            LOGGER.info("[miningdim] AccessHub ({}) is not installed; the login gate lets every request through "
                    + "unless security.loginGate=REQUIRED on a dedicated server.", AccessHubLoginProbe.MOD_ID);
        } else {
            Optional<Object> modInstance = ModList.get().getModObjectById(AccessHubLoginProbe.MOD_ID);
            resolved = modInstance.map(Binding::of)
                    .orElseGet(() -> Binding.broken("the mod container has no mod instance"));
            if (resolved.kind == Binding.Kind.BOUND) {
                LOGGER.info("[miningdim] login gate bound to AccessHub: {}", resolved.probe.describe());
            } else {
                LOGGER.error("[miningdim] AccessHub is installed but its login API does not match the verified "
                        + "shape ({}). Every mod request from players on a dedicated server is rejected "
                        + "(fail closed) until this is fixed, or until security.loginGate is set to OFF.",
                        resolved.problem);
            }
        }
        binding = resolved;
        return resolved;
    }

    private static void logInvocationFailure(Binding failing, Throwable error) {
        if (failing.invocationFailureLogged.compareAndSet(false, true)) {
            LOGGER.error("[miningdim] querying AccessHub's login state failed; the request is rejected "
                    + "(fail closed). Further failures are logged at DEBUG.", error);
        } else {
            LOGGER.debug("[miningdim] querying AccessHub's login state failed again", error);
        }
    }

    // ============================================================
    // GameTest 注入
    // ============================================================

    /**
     * GameTest 专用: 强制某个玩家的判定结果, 直到返回的句柄 close。
     *
     * 按玩家而不是全局覆盖: 同一批 GameTest 在同一个服务端里交错运行, 全局开关会让别的用例的请求也被拒。
     * mock 玩家的 UUID 每次随机, 于是不同用例之间天然互不干扰。
     *
     * 只在 GameTest 服务端 ({@link GameTestServer}, 即 runGameTestServer) 上有效, 两头都卡: 别处调用直接抛
     * IllegalStateException, {@link #check} 也只在 GameTest 服务端上读这张表。它是 public 的 (各模块的 GameTest
     * 都要用), 卡在服务端类型上才能保证专用服务器与单人存档里任何代码都无法借它改写判定。门槛与
     * {@code GameTestConfigWatchGuard} 同一口径 (不卡 forge.enabledGameTestNamespaces: client/server 两个 run 也设了它)。
     */
    public static ForcedVerdict forceVerdictForTest(UUID player, Verdict verdict) {
        if (!(ServerLifecycleHooks.getCurrentServer() instanceof GameTestServer)) {
            throw new IllegalStateException("forceVerdictForTest is only available on the GameTest server");
        }
        FORCED_VERDICTS.put(Objects.requireNonNull(player, "player"), Objects.requireNonNull(verdict, "verdict"));
        return new ForcedVerdict(player);
    }

    /** {@link #forceVerdictForTest} 的撤销句柄; 用 try-with-resources 保证用例失败时也撤掉。 */
    public static final class ForcedVerdict implements AutoCloseable {

        private final UUID player;

        private ForcedVerdict(UUID player) {
            this.player = player;
        }

        @Override
        public void close() {
            FORCED_VERDICTS.remove(player);
        }
    }
}
