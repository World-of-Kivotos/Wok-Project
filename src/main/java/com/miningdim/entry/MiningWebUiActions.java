package com.miningdim.entry;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.miningdim.core.Difficulty;
import com.miningdim.core.GenState;
import com.miningdim.core.InstanceState;
import com.miningdim.core.MiningConstants;
import com.miningdim.core.MiningServices;
import com.miningdim.core.RegionBox;
import com.miningdim.job.JobId;
import com.miningdim.job.JobServices;
import com.miningdim.job.miner.MinerLevelGate;
import com.miningdim.reset.AutoResetData;
import com.miningdim.webui.server.WebUiPayloads;
import com.miningdim.webui.server.WebUiServerDispatcher;
import com.miningdim.webui.server.WebUiServerDispatcher.WebUiAction;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

/**
 * 矿洞面板的 mining.* WebUiAction (总览 / 我的状态 / 进入 / 离开)。
 *
 * R1 认知前提 (回执形状全部由它决定): 全服<b>只有三个常驻共享固定区域</b>, 每难度一个, 由
 * {@code InstanceManager.ensureFixedInstances} 在开服时预建、永不 GC、只能被重置。因此本组回执里既没有
 * "创建实例", 也没有"我的副本" —— {@code mining.overview} 恒回三行 (Difficulty.values() 的声明序),
 * 一行就是一整块难度区域。
 *
 * 等级门的权威是代码不是注释: 门槛取 {@link MinerLevelGate} (Easy L1 / Medium L4 / Hard L8), 与
 * {@code EntryGateway.gateCheck} 读的是同一个类同一个方法。{@link GateResult} 类头注释里写的
 * "Medium L10 / Hard L25" 是过期文档口径, 照抄它等于让面板显示一套服务端根本不执行的门槛。
 *
 * 入场唯一权威路径 (硬约束): {@code mining.enter} 只把请求交给 {@link EntryGateway#requestEnter} ——
 * 那是唯一一条同时做了难度门控、写回退态、等生成就绪、等区块 FULL 再传送 (防掉虚空) 的链路。
 * 另外两条存量路径 ({@code /mining enter} 命令的兄弟实现 {@code com.miningdim.command.MiningCommands}
 * 与 {@code SelectZoneC2S}) 都只 allocate 不传送且不过 gateCheck, 照它们抄就是把 bug 复制第三份。
 *
 * 时间一律发 game tick 不发墙钟 (与 job.miner.* 同口径): 服务端手里只有 tick, 换算成服务端墙钟再让 MCEF
 * 客户端拿 Date.now() 去减, 既吃时钟偏移又在 TPS 掉帧时失真。自动刷新的 lastReset 本就是矿山维度 game time
 * ({@link AutoResetData} 的持久化口径), 换算只会引入第二套时钟。
 */
public final class MiningWebUiActions {

    /**
     * 本类专用 Gson: 必须 serializeNulls。
     *
     * 回执里"这一档没有难度/没有区域/没有下次刷新时刻"是真值而不是缺数据, 默认 Gson 会把值为 null 的成员整键
     * 丢掉, 前端拿到 undefined 就分不清"服务端说没有"和"服务端漏发了"。故所有可空键一律显式写 JSON null。
     */
    private static final Gson GSON = new GsonBuilder().serializeNulls().create();

    /**
     * 1 小时的 tick 数。与 {@code AutoResetScheduler.TICKS_PER_HOUR} 同口径 —— 那个常量是私有的, 复用不到,
     * 故此处重写一份; 两处一旦分叉, 面板上的"下次刷新"就会和调度器实际触发时刻对不上。
     */
    private static final long TICKS_PER_HOUR = 3600L * 20L;

    /**
     * 入场编排的持有者 (由 {@link EntrySystem#register} 在装配期注入)。
     *
     * 为什么是静态字段而不是每次现取: {@link EntryGateway} 实例住在 EntrySystem 里且要到 ServerStartedEvent
     * 才建好, 而 action 注册表是进程级静态 —— 只能在注册期把持有者存下来, 到调用时再向它要当时的 gateway。
     * 范式与 {@code EntranceHooks} 的 seam 一致 (注册期单线程写, 运行期只读)。
     */
    private static volatile EntrySystem entrySystem;

    private MiningWebUiActions() {
    }

    /** 把四条 mining.* action 注册进派发器 (由 {@link EntrySystem#register} 调用一次)。 */
    public static void registerAll(EntrySystem owner) {
        if (owner == null) {
            throw new IllegalArgumentException("EntrySystem must not be null");
        }
        entrySystem = owner;
        WebUiServerDispatcher.register("mining.overview", OVERVIEW);
        WebUiServerDispatcher.register("mining.myStatus", MY_STATUS);
        WebUiServerDispatcher.register("mining.enter", ENTER);
        WebUiServerDispatcher.register("mining.leave", LEAVE);
    }

    // ============================================================
    // mining.overview: {} -> 三块固定难度区域 + 我此刻在哪一块
    // ============================================================

    /**
     * 三块常驻区域的一屏总览。行数恒为 3 且顺序恒为 {@code Difficulty.values()} —— 前端按下标画三个卡片,
     * 顺序漂了卡片就串。体积与在线人数无关 (每行只带计数不带名单), 故不需要分页。
     */
    static final WebUiAction OVERVIEW = (sender, payload) -> {
        ServerLevel miningLevel = requireMiningLevel(sender);
        AutoResetData autoReset = AutoResetData.get(miningLevel);
        int minerLevel = JobServices.jobService().level(sender, JobId.MINER);

        JsonArray rows = new JsonArray();
        for (Difficulty difficulty : Difficulty.values()) {
            rows.add(overviewRow(difficulty, minerLevel, autoReset));
        }

        JsonObject result = new JsonObject();
        result.add("instances", rows);
        result.addProperty("minerLevel", minerLevel);
        // 与各行的 lastReset/nextReset 同一时钟 (矿山维度 game time), 前端据此自行算倒计时。
        result.addProperty("gameTime", miningLevel.getGameTime());
        result.addProperty("autoResetWarnSeconds", MiningServices.config().autoResetWarnSeconds());

        InstanceState current = currentRegionOf(sender);
        if (current == null) {
            result.add("myDifficulty", JsonNull.INSTANCE);
        } else {
            result.addProperty("myDifficulty", current.difficulty().configName());
        }
        return GSON.toJson(result);
    };

    private static JsonObject overviewRow(Difficulty difficulty, int minerLevel, AutoResetData autoReset) {
        JsonObject row = new JsonObject();
        row.addProperty("difficulty", difficulty.configName());
        row.addProperty("dropsOnDeath", difficulty == Difficulty.HARD);
        // 服务端只发翻译键 (专用服务端不加载 lang), 中文由客户端 i18n 桥解; 键与入场提示用的是同一批。
        row.addProperty("nameKey", "difficulty.miningdim." + difficulty.configName());
        row.addProperty("requiredMinerLevel", MinerLevelGate.minLevelFor(difficulty));
        row.addProperty("unlocked", MinerLevelGate.canEnter(minerLevel, difficulty));

        InstanceState inst = fixedInstanceFor(difficulty);
        // available=false 只可能出现在"开服重建尚未跑完"的极早期。此时逐个字段发 null 而不是发一行看起来
        // 可进入的假区域 —— 后者会让面板给出一个点了必然失败的按钮。
        row.addProperty("available", inst != null);
        if (inst == null) {
            row.add("instanceId", JsonNull.INSTANCE);
            row.add("genState", JsonNull.INSTANCE);
            row.addProperty("enterable", false);
            row.add("playersInside", JsonNull.INSTANCE);
            row.add("shared", JsonNull.INSTANCE);
            row.add("regionOriginX", JsonNull.INSTANCE);
            row.add("regionOriginZ", JsonNull.INSTANCE);
        } else {
            RegionBox box = inst.regionBox();
            row.addProperty("instanceId", inst.instanceId());
            row.addProperty("genState", inst.genState().name());
            row.addProperty("enterable", inst.genState().isEnterable());
            row.addProperty("playersInside", inst.refCount());
            // R1 下恒为 true。照发不写死: 它是"这块地是不是共享的"的服务端事实, 前端据此确认没有私有副本概念。
            row.addProperty("shared", inst.shared());
            row.addProperty("regionOriginX", box.originX());
            row.addProperty("regionOriginZ", box.originZ());
        }

        int autoResetHours = MiningServices.config().autoResetHours(difficulty);
        row.addProperty("autoResetHours", autoResetHours);
        long lastReset = autoReset.lastReset(difficulty);
        if (lastReset == AutoResetData.NEVER) {
            row.add("lastResetGameTime", JsonNull.INSTANCE);
        } else {
            row.addProperty("lastResetGameTime", lastReset);
        }
        // 关闭定时刷新 (hours<=0) 或从未记录过基准时, "下次刷新"根本不存在, 发 null 而不是发一个算出来的时刻。
        if (autoResetHours > 0 && lastReset != AutoResetData.NEVER) {
            row.addProperty("nextResetGameTime", lastReset + autoResetHours * TICKS_PER_HOUR);
        } else {
            row.add("nextResetGameTime", JsonNull.INSTANCE);
        }
        return row;
    }

    // ============================================================
    // mining.myStatus: {} -> 我此刻在不在矿洞 / 在哪块 / 出生保护还剩多久
    // ============================================================

    /**
     * 我的矿洞状态。不在矿洞时逐字段发真值 (difficulty/regionOrigin 为 null, 剩余保护为 0), 不编造坐标。
     */
    static final WebUiAction MY_STATUS = (sender, payload) -> {
        IMiningPlayerData data = requirePlayerData(sender);
        InstanceState region = currentRegionOf(sender);
        long now = sender.serverLevel().getGameTime();

        JsonObject result = new JsonObject();
        result.addProperty("inside", region != null);
        result.addProperty("inMiningDimension", sender.level().dimension().equals(MiningConstants.MINING_LEVEL));
        if (region == null) {
            result.add("difficulty", JsonNull.INSTANCE);
            result.add("instanceId", JsonNull.INSTANCE);
            result.add("genState", JsonNull.INSTANCE);
            result.add("regionOriginX", JsonNull.INSTANCE);
            result.add("regionOriginZ", JsonNull.INSTANCE);
        } else {
            RegionBox box = region.regionBox();
            result.addProperty("difficulty", region.difficulty().configName());
            result.addProperty("instanceId", region.instanceId());
            result.addProperty("genState", region.genState().name());
            result.addProperty("regionOriginX", box.originX());
            result.addProperty("regionOriginZ", box.originZ());
        }
        // capability 记的"我属于哪个实例"与上面按坐标反查的结果是两个独立事实 (前者在传送前写, 后者是几何),
        // 二者不一致本身就是要给运维看的现场, 故都发。不在任何实例时是哨兵 -1 (NO_INSTANCE), 不是缺省 0。
        result.addProperty("currentInstanceId", data.currentInstanceId());
        result.addProperty("gameTime", now);
        result.addProperty("spawnFreezeUntilGameTime", data.spawnFreezeUntil());
        // 先减后钳: 出生冻结过期后 until 仍留着旧值, 直接发差值会是负数 (面板会显示"还剩 -900 tick")。
        result.addProperty("spawnFreezeRemainingTicks", Math.max(0L, data.spawnFreezeUntil() - now));
        result.addProperty("minerLevel", JobServices.jobService().level(sender, JobId.MINER));
        return GSON.toJson(result);
    };

    // ============================================================
    // mining.enter: {difficulty} -> 受理与否 (真正的传送由 EntryGateway 后续 tick 完成)
    // ============================================================

    /**
     * 请求进入某难度区域。
     *
     * 回执字段叫 {@code accepted} 而不是 {@code entered}: {@link EntryGateway#requestEnter} 是异步的 ——
     * 它同步做门控与回退态快照, 随后 allocate、等生成就绪、等区块 FULL, 传送发生在之后若干 tick 的
     * {@code EntryGateway#tick} 里。此刻回 {@code entered:true} 是撒谎。传送成功/失败的终局由入场链路经
     * 原生 TeleportResult S2C 下发 (不走 webui 通道), 面板要确认是否真进去了应轮询 {@code mining.myStatus}。
     *
     * 两条同步拒绝判据的顺序与 requestEnter 内部逐字一致 (先难度门, 后"已在实例内"), 否则同一次请求在这里
     * 与在权威路径里会给出不同的原因。判据本身不另写一份: 难度门直接问 {@link MinerLevelGate} (gateCheck
     * 问的同一个方法), "已在实例内"直接读 capability 的同一个字段。
     */
    static final WebUiAction ENTER = (sender, payload) -> {
        Difficulty difficulty = parseDifficulty(payload);
        IMiningPlayerData data = requirePlayerData(sender);
        int minerLevel = JobServices.jobService().level(sender, JobId.MINER);

        JsonObject result = new JsonObject();
        result.addProperty("difficulty", difficulty.configName());
        result.addProperty("requiredMinerLevel", MinerLevelGate.minLevelFor(difficulty));
        result.addProperty("minerLevel", minerLevel);
        InstanceState target = fixedInstanceFor(difficulty);
        if (target == null) {
            result.add("instanceId", JsonNull.INSTANCE);
        } else {
            result.addProperty("instanceId", target.instanceId());
        }

        if (!MinerLevelGate.canEnter(minerLevel, difficulty)) {
            // 原因码与 i18n 键都取自 GateResult 本身, 不新造一套字符串 (命令路径提示的是同一句话)。
            return rejected(result, GateResult.LEVEL_TOO_LOW.name(), GateResult.LEVEL_TOO_LOW.reasonKey());
        }
        if (data.currentInstanceId() != IMiningPlayerData.NO_INSTANCE) {
            return rejected(result, "ALREADY_INSIDE", "message.miningdim.enter.already_inside");
        }

        // reseed=false: R1 下三块区域常驻复用, 换图是 /mining reset 与定时自动刷新的职责, 不是玩家入场的副作用。
        requireGateway().requestEnter(sender, difficulty, false);

        result.addProperty("accepted", true);
        result.add("reasonCode", JsonNull.INSTANCE);
        result.add("reasonKey", JsonNull.INSTANCE);
        return GSON.toJson(result);
    };

    // ============================================================
    // mining.leave: {} -> 撤回进入前的现场
    // ============================================================

    /**
     * 主动离开矿洞。整条逻辑委派 {@link EntrySystem#leaveToFallback} —— 那里同时负责传送回 capability 记的
     * 回退点与走 12.6 的统一离开汇聚点 (refCount--/释放强加载/唤醒排队); 面板层自己传送就会漏掉后半截。
     */
    static final WebUiAction LEAVE = (sender, payload) -> {
        boolean left = requireEntrySystem().leaveToFallback(sender);
        JsonObject result = new JsonObject();
        result.addProperty("left", left);
        if (left) {
            result.add("reasonCode", JsonNull.INSTANCE);
            result.add("reasonKey", JsonNull.INSTANCE);
            return GSON.toJson(result);
        }
        result.addProperty("reasonCode", "NOT_INSIDE");
        result.addProperty("reasonKey", "message.miningdim.leave.not_inside");
        return GSON.toJson(result);
    };

    // ============================================================
    // 共用查询 (admin.mining.* 同包复用)
    // ============================================================

    /**
     * 取某难度的固定常驻区域实例; 尚未预建完成时为 null。
     *
     * 经 core 门面 {@code IInstanceManager} 遍历取, 不 import {@code instance} 包的实现类 (模块化铁律 2);
     * R1 下每难度恰有一个存活实例, 故按 difficulty 匹配即可 (口径与 {@code AutoResetScheduler} 完全一致)。
     * RECYCLED 是"已回收待清"的尾巴态, 不算数。
     */
    static InstanceState fixedInstanceFor(Difficulty difficulty) {
        InstanceState[] found = new InstanceState[1];
        MiningServices.instanceManager().forEach(inst -> {
            if (found[0] == null && inst.difficulty() == difficulty && inst.genState() != GenState.RECYCLED) {
                found[0] = inst;
            }
        });
        return found[0];
    }

    /**
     * 难度入参解析。取值域外回 INVALID_REQUEST + params{field,value} (前端据此定位控件并回显被拒的值),
     * 而不是让 {@code Difficulty.byConfigName} 的 IllegalArgumentException 走通用兜底 —— 后者没有 errorCode,
     * 前端只能显示一句英文原文。大小写不敏感与 byConfigName 保持同一口径。
     */
    static Difficulty parseDifficulty(JsonObject payload) {
        String raw = WebUiPayloads.requiredString(payload, "difficulty");
        for (Difficulty difficulty : Difficulty.values()) {
            if (difficulty.configName().equalsIgnoreCase(raw)) {
                return difficulty;
            }
        }
        throw WebUiPayloads.illegalValue("difficulty", raw, "未知的矿洞难度: " + raw);
    }

    /** 矿山维度; 缺失即装配缺陷, 自然抛不掩盖 (ResetSystem 启动期用的是同一句判定)。 */
    static ServerLevel requireMiningLevel(ServerPlayer sender) {
        ServerLevel miningLevel = sender.getServer().getLevel(MiningConstants.MINING_LEVEL);
        if (miningLevel == null) {
            throw new IllegalStateException(
                    "mining dimension " + MiningConstants.MINING_LEVEL.location() + " is not loaded");
        }
        return miningLevel;
    }

    // ============================================================
    // 内部工具
    // ============================================================

    /**
     * 玩家此刻所在的区域实例; 不在矿洞则 null。
     *
     * 维度判定不可省: {@code regionAt} 只比 XZ (Y 与维度都不参与), 而 Easy 区盒是 X/Z ∈ [0,256) —— 主世界
     * 出生点通常就落在里面。只用 regionAt 会把站在主世界出生点的玩家报成"正在困难矿洞里", 和 OreScanService
     * 当初漏掉维度门后变成任意维度透视器是同一个缺口。
     */
    private static InstanceState currentRegionOf(ServerPlayer sender) {
        if (!sender.level().dimension().equals(MiningConstants.MINING_LEVEL)) {
            return null;
        }
        BlockPos pos = sender.blockPosition();
        return MiningServices.instanceManager().regionAt(pos.getX(), pos.getZ());
    }

    private static IMiningPlayerData requirePlayerData(ServerPlayer sender) {
        return MiningCapabilities.get(sender).orElseThrow(() -> new IllegalStateException(
                "player has no mining capability: " + sender.getGameProfile().getName()));
    }

    private static EntrySystem requireEntrySystem() {
        EntrySystem system = entrySystem;
        if (system == null) {
            throw new IllegalStateException("mining.* actions are not wired: EntrySystem.register must call registerAll");
        }
        return system;
    }

    /** 取入场编排; 服务端启动尚未走到 ServerStartedEvent 时它还不存在, 如实抛而不是静默什么都不做。 */
    private static EntryGateway requireGateway() {
        EntryGateway gateway = requireEntrySystem().gateway();
        if (gateway == null) {
            throw new IllegalStateException("entry gateway is not ready yet (server has not finished starting)");
        }
        return gateway;
    }

    /** 同步拒绝的 enter 回执: accepted=false + 机器码 + i18n 键 (三者形状与受理时逐字对齐)。 */
    private static String rejected(JsonObject result, String reasonCode, String reasonKey) {
        result.addProperty("accepted", false);
        result.addProperty("reasonCode", reasonCode);
        result.addProperty("reasonKey", reasonKey);
        return GSON.toJson(result);
    }
}
