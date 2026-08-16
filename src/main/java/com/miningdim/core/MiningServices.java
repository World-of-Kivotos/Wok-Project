package com.miningdim.core;

/**
 * 服务定位器 (模块化铁律 2)。跨子系统协作的唯一中转: 每个子系统在自己的 Subsystem.register 内
 * 把门面实现注入这里, 其他子系统按接口取用, 从不 import 对方实现类。
 *
 * 字段为显式强类型 (非 stringly-typed map): 编译期即可校验门面类型, 且零反射/零装箱。
 * 全部静态, 生命周期 = mod 进程。注入只在 mod 构造/服务端启动期发生 (单线程), 故无需锁;
 * 运行期只读 (get*)。
 *
 * 异常契约 (C9): get* 在对应服务未注入时抛 IllegalStateException 自然冒泡, 严禁返回 null 掩盖
 * "子系统未加载/注入顺序错"的真实缺陷。注入顺序由主类 List<Subsystem> 决定; 若 A 在 register 期
 * 就要用 B 的服务, 须保证 B 排在 A 前, 或把取用推迟到事件回调 (服务端启动后) 而非 register 当场。
 */
public final class MiningServices {

    private MiningServices() {
    }

    private static IInstanceManager instanceManager;
    private static IOfflineGenerator offlineGenerator;
    private static IMiningConfig config;
    private static IMiningNetwork network;
    private static IResetService resetService;
    private static ISpawnService spawnService;

    /**
     * 实例重置监听器 (13.4/D3)。与上面的单例门面不同, 这一路是多播的: 陷阱静态表、铺矿表、出生池、
     * 刷怪调度态四个子系统都要在实例被滑动重置后各自清缓存, 不能只挂一个实现。
     */
    private static final java.util.List<IInstanceResetListener> instanceResetListeners =
            new java.util.concurrent.CopyOnWriteArrayList<>();

    // ---- 注册 (子系统在 Subsystem.register 内调用) ----

    public static void registerInstanceManager(IInstanceManager service) {
        instanceManager = requireNonNull(service, "IInstanceManager");
    }

    public static void registerOfflineGenerator(IOfflineGenerator service) {
        offlineGenerator = requireNonNull(service, "IOfflineGenerator");
    }

    public static void registerConfig(IMiningConfig service) {
        config = requireNonNull(service, "IMiningConfig");
    }

    public static void registerNetwork(IMiningNetwork service) {
        network = requireNonNull(service, "IMiningNetwork");
    }

    public static void registerResetService(IResetService service) {
        resetService = requireNonNull(service, "IResetService");
    }

    public static void registerSpawnService(ISpawnService service) {
        spawnService = requireNonNull(service, "ISpawnService");
    }

    // ---- 获取 (业务代码按接口取用; 未注入抛 IllegalStateException, 不返回 null) ----

    public static IInstanceManager instanceManager() {
        return require(instanceManager, "IInstanceManager");
    }

    public static IOfflineGenerator offlineGenerator() {
        return require(offlineGenerator, "IOfflineGenerator");
    }

    public static IMiningConfig config() {
        return require(config, "IMiningConfig");
    }

    public static IMiningNetwork network() {
        return require(network, "IMiningNetwork");
    }

    public static IResetService resetService() {
        return require(resetService, "IResetService");
    }

    public static ISpawnService spawnService() {
        return require(spawnService, "ISpawnService");
    }

    /** 注册一个实例重置监听器 (13.4/D3); 允许多个子系统各自注册, 按注册序逐个调用。 */
    public static void registerInstanceResetListener(IInstanceResetListener listener) {
        instanceResetListeners.add(requireNonNull(listener, "IInstanceResetListener"));
    }

    /**
     * 广播"实例已重置"事件 (主线程, 由 reset 子系统在滑动完成后调用)。按注册序逐个调用监听器;
     * 严禁在此吞监听器异常 —— 缓存清理失败必须冒泡到 reset 子系统的最外层, 而不是被静默忽略。
     */
    public static void fireInstanceReset(long instanceId) {
        for (IInstanceResetListener listener : instanceResetListeners) {
            listener.onInstanceReset(instanceId);
        }
    }

    /** 服务端停止时清空, 防止跨存档/跨重启的脏引用 (供 ServerStoppingEvent 调用; 可选)。 */
    public static void reset() {
        instanceManager = null;
        offlineGenerator = null;
        config = null;
        network = null;
        resetService = null;
        spawnService = null;
        instanceResetListeners.clear();
    }

    private static <T> T require(T service, String name) {
        if (service == null) {
            throw new IllegalStateException(
                    "MiningServices: " + name + " not registered yet (check Subsystem register order)");
        }
        return service;
    }

    private static <T> T requireNonNull(T service, String name) {
        if (service == null) {
            throw new IllegalArgumentException("Cannot register null " + name);
        }
        return service;
    }
}
