package com.miningdim.core.auth;

import java.lang.reflect.Method;
import java.util.Objects;
import java.util.UUID;

/**
 * AccessHub (modid {@value #MOD_ID}, 闭源) 登录态的反射探针。
 *
 * 为什么用反射而不是 compileOnly: AccessHub 闭源且不随仓库分发, 编进来就要求每个构建者自备一个拿不到的 jar;
 * 它又只在专用服务器上运作, dev 与 GameTest 里永远没有它。本类只依赖下面五个必需与一个可选的公开方法,
 * 2026-09 对照 shinoyuki_accesshub-0.5.3.jar 逐条核实过 (类与方法均为 public, 模组无 module-info,
 * 包对外可见):
 * <ul>
 *   <li>{@code AccessHubMod#getPlayerAuthService()}: 返回 PlayerAuthService。ServerStartingEvent 之前、
 *       非专用服务器上、以及 AccessHub 自己初始化抛异常时都是 null。</li>
 *   <li>{@code AccessHubMod#getConfig()}: 返回 AccessHubConfig 接口, null 的时机同上。</li>
 *   <li>{@code AccessHubConfig#isPlayerAuthEnabled()}: 配置键 auth.enabled, 每次调用现读,
 *       {@code /accesshub reload} 可在运行期改掉它。为 false 时 AccessHub 自己也不做任何限制。</li>
 *   <li>{@code PlayerAuthService#isAuthed(UUID)}: 就是 {@code authedSessions.contains(uuid)}。那个集合是
 *       {@code ConcurrentHashMap.newKeySet()}, 任意线程读都安全; 写入全在服务端主线程。</li>
 *   <li>{@code PlayerAuthService#clearSession(UUID)}: 从上述集合移除, 只用于防御性撤销 (进服时仍残留的登录态、
 *       连接核对不通过的登录态, 见 {@link LoginRaceGuard})。</li>
 * </ul>
 * 另有一个<b>可选</b>方法, 缺了不影响绑定, 只让开服审计跳过:
 * <ul>
 *   <li>{@code PlayerAuthService#isRegistered(String)}: 按名字查账号是否存在 (查库; 查库失败抛
 *       IllegalStateException)。</li>
 * </ul>
 *
 * 绑定只核对"形状" (方法名、参数、返回类型), 不核对类名: 必需方法形状对不上一律绑定失败, 由调用方关门
 * (fail closed); 形状全对时类名叫什么不影响语义, 这也让 GameTest 能用同形的假对象走一遍真实的反射路径。
 *
 * 服务与配置对象每次现调 getter 取, 不缓存: AccessHub 在每次 ServerStartingEvent 里重建它们, 缓存下来的
 * 旧实例就是一份再也不会被写入的死集合。缓存的只有 Method 对象本身。
 */
final class AccessHubLoginProbe {

    /** AccessHub 的 modid (AccessHubMod.MOD_ID 的字面值)。 */
    static final String MOD_ID = "shinoyuki_accesshub";

    /** 一次查询的结果。 */
    enum State {
        /** 本次连接已经 /login (或设备认证) 成功。 */
        AUTHED,
        /** 登录功能开着, 但本次连接还没通过。 */
        NOT_AUTHED,
        /** auth.enabled=false: AccessHub 自己也放行一切, 本 mod 不能比它更严。 */
        AUTH_DISABLED,
        /** 服务或配置对象还是 null: AccessHub 没有在这台服务器上跑起来 (或启动途中抛了异常)。 */
        NOT_STARTED
    }

    private final Object mod;
    private final Method getPlayerAuthService;
    private final Method getConfig;
    private final Method isPlayerAuthEnabled;
    private final Method isAuthed;
    private final Method clearSession;
    /** 可选 (见类注释); null = 这版 AccessHub 没有它, 开服账号审计跳过。 */
    private final Method isRegistered;

    private AccessHubLoginProbe(Object mod, Method getPlayerAuthService, Method getConfig,
                                Method isPlayerAuthEnabled, Method isAuthed, Method clearSession,
                                Method isRegistered) {
        this.mod = mod;
        this.getPlayerAuthService = getPlayerAuthService;
        this.getConfig = getConfig;
        this.isPlayerAuthEnabled = isPlayerAuthEnabled;
        this.isAuthed = isAuthed;
        this.clearSession = clearSession;
        this.isRegistered = isRegistered;
    }

    /**
     * 按形状绑定一个 AccessHub 模组实例。任一必需方法缺失或返回类型不符即抛 {@link NoSuchMethodException}。
     *
     * clearSession 也是必需项而不是"有就用": 它缺席说明 AccessHub 的会话模型已经变了, 此时连 isAuthed 的语义
     * 都不再有人核实过, 放行比拒绝危险得多。isRegistered 则真是可有可无: 它只喂开服审计的一条日志, 与放不放行
     * 无关。
     */
    static AccessHubLoginProbe bind(Object mod) throws NoSuchMethodException {
        Objects.requireNonNull(mod, "mod");
        Class<?> modClass = mod.getClass();
        Method getPlayerAuthService = modClass.getMethod("getPlayerAuthService");
        Method getConfig = modClass.getMethod("getConfig");
        Class<?> serviceType = getPlayerAuthService.getReturnType();
        Class<?> configType = getConfig.getReturnType();
        Method isPlayerAuthEnabled = requireReturn(configType.getMethod("isPlayerAuthEnabled"), boolean.class);
        Method isAuthed = requireReturn(serviceType.getMethod("isAuthed", UUID.class), boolean.class);
        Method clearSession = requireReturn(serviceType.getMethod("clearSession", UUID.class), void.class);
        Method isRegistered;
        try {
            isRegistered = requireReturn(serviceType.getMethod("isRegistered", String.class), boolean.class);
        } catch (NoSuchMethodException e) {
            isRegistered = null;
        }
        return new AccessHubLoginProbe(mod, getPlayerAuthService, getConfig, isPlayerAuthEnabled, isAuthed,
                clearSession, isRegistered);
    }

    private static Method requireReturn(Method method, Class<?> expected) throws NoSuchMethodException {
        if (method.getReturnType() != expected) {
            throw new NoSuchMethodException(method.getDeclaringClass().getName() + "#" + method.getName()
                    + " returns " + method.getReturnType().getName() + ", expected " + expected.getName());
        }
        return method;
    }

    /**
     * 查某玩家本次连接的登录态。
     *
     * 先看配置再看服务: auth.enabled=false 时 AccessHub 不设任何限制 (它自己的 disabled() 分支), 本 mod 与它
     * 口径一致; 反过来, 配置开着而服务对象是 null, 说明 AccessHub 启动失败, 那才是"无法判定"。
     */
    State stateOf(UUID player) throws ReflectiveOperationException {
        Object config = getConfig.invoke(mod);
        if (config == null) {
            return State.NOT_STARTED;
        }
        if (!Boolean.TRUE.equals(isPlayerAuthEnabled.invoke(config))) {
            return State.AUTH_DISABLED;
        }
        Object service = getPlayerAuthService.invoke(mod);
        if (service == null) {
            return State.NOT_STARTED;
        }
        return Boolean.TRUE.equals(isAuthed.invoke(service, player)) ? State.AUTHED : State.NOT_AUTHED;
    }

    /**
     * 该玩家若"已登录"就撤掉这份登录态, 返回是否撤了。只给"这份登录态不属于当前连接"的时刻用 (见
     * PlayerLoginGate 的两个调用点: 进服那一刻, 与连接核对不通过时)。
     */
    boolean revokeIfAuthed(UUID player) throws ReflectiveOperationException {
        if (stateOf(player) != State.AUTHED) {
            return false;
        }
        clearSession.invoke(getPlayerAuthService.invoke(mod), player);
        return true;
    }

    /** 这版 AccessHub 是否提供开服账号审计要用的 isRegistered(String)。 */
    boolean canAuditAccounts() {
        return isRegistered != null;
    }

    /**
     * 该名字在 AccessHub 里是否已有账号 (查库)。调用方须先确认 {@link #canAuditAccounts()} 且 {@link #stateOf}
     * 不是 NOT_STARTED / AUTH_DISABLED; 服务对象为 null 时抛 IllegalStateException。
     */
    boolean isRegistered(String name) throws ReflectiveOperationException {
        if (isRegistered == null) {
            throw new IllegalStateException("this AccessHub has no isRegistered(String)");
        }
        Object service = getPlayerAuthService.invoke(mod);
        if (service == null) {
            throw new IllegalStateException("AccessHub's player auth service is not running");
        }
        return Boolean.TRUE.equals(isRegistered.invoke(service, name));
    }

    /** 诊断用: 绑定到的实际类名 (日志里据此判断装的是不是那个核实过的版本)。 */
    String describe() {
        return mod.getClass().getName() + " (service " + getPlayerAuthService.getReturnType().getName()
                + ", config " + getConfig.getReturnType().getName()
                + (isRegistered == null ? ", no isRegistered: OP account audit disabled" : "") + ")";
    }
}
