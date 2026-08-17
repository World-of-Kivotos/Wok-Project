package com.miningdim.entry;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.miningdim.core.Difficulty;
import com.miningdim.core.IResetService;
import com.miningdim.core.InstanceState;
import com.miningdim.core.MiningServices;
import com.miningdim.webui.server.WebUiPayloads;
import com.miningdim.webui.server.WebUiPermissions;
import com.miningdim.webui.server.WebUiServerDispatcher;
import com.miningdim.webui.server.WebUiServerDispatcher.WebUiAction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 矿洞管理台的 admin.mining.* WebUiAction (按难度重置一整块常驻区域)。
 *
 * 二次确认的责任在前端: 活跃的 {@code /mining reset} (entry 包那棵命令树) <b>没有</b>二次确认, 带确认与冷却
 * 的那套住在 {@code com.miningdim.command} 的死代码里, 从未接进主类。本 action 照活跃实现办, 服务端<b>不</b>
 * 加确认 —— 面板按钮必须自己弹确认框, 不要指望服务端拦下误点。
 *
 * 权限: 每个 admin.* 动作在自己的 handler 内独立过 {@link WebUiPermissions#isOp} (架构铁律 1: 前端拿到
 * isOp=true 只是渲染决策, 服务端一律重判)。
 *
 * 重置是异步且可能被前置条件拒绝的: {@link IResetService#reset} 在实例不可重置 / 有人在场且 requireEmpty 时
 * 返回 failedFuture 而不是抛。故本 action 先自己算一遍前置条件 ({@link #planReset}), 拒绝时同步回执说明原因,
 * 只有确定会被受理才真去调 reset —— 否则玩家点完按钮只会看到一条"成功"和一个什么都没变的区域。
 */
public final class MiningAdminWebUiActions {

    private static final Logger LOGGER = LoggerFactory.getLogger("miningdim/entry");

    /** 可空键 (reasonCode/instanceId) 必须显式发 JSON null, 理由同 {@code MiningWebUiActions.GSON}。 */
    private static final Gson GSON = new GsonBuilder().serializeNulls().create();

    private MiningAdminWebUiActions() {
    }

    /** 把 admin.mining.* action 注册进派发器 (由 {@link EntrySystem#register} 调用一次)。 */
    public static void registerAll() {
        WebUiServerDispatcher.register("admin.mining.reset", RESET);
    }

    // ============================================================
    // admin.mining.reset: {difficulty, reseed?} -> {accepted, evictedPlayers, ...}  (OP 门控)
    // ============================================================

    /**
     * 重置某难度的整块常驻区域。
     *
     * 步骤与活跃命令路径 ({@code MiningCommands#reset}) 一致: 先按配置决定要不要清场 -> evacuate 撤离在场玩家
     * 回各自进入前坐标 -> reset。默认 NEW_SEED (换图), 与 {@code /mining reset all} 和定时自动刷新同口径;
     * 传 {@code reseed:false} 才用 SAME_SEED (原样重建, 确定性验收用)。
     */
    static final WebUiAction RESET = (sender, payload) -> {
        WebUiPermissions.requireOp(sender, "admin.mining.reset");
        Difficulty difficulty = MiningWebUiActions.parseDifficulty(payload);
        // 缺键即默认换图; 给了就必须是布尔 (类型不符走 INVALID_REQUEST, 不静默当成 false)。
        boolean reseed = true;
        if (payload.has("reseed") && !payload.get("reseed").isJsonNull()) {
            reseed = WebUiPayloads.requiredBoolean(payload, "reseed");
        }
        IResetService.ResetMode mode = reseed
                ? IResetService.ResetMode.NEW_SEED
                : IResetService.ResetMode.SAME_SEED;
        ServerLevel miningLevel = MiningWebUiActions.requireMiningLevel(sender);

        JsonObject result = new JsonObject();
        result.addProperty("difficulty", difficulty.configName());
        result.addProperty("mode", mode.name());
        result.addProperty("requestedAtGameTime", miningLevel.getGameTime());

        InstanceState inst = MiningWebUiActions.fixedInstanceFor(difficulty);
        if (inst == null) {
            // 三块常驻区域在开服重建末尾预建, 缺失只可能是启动缺陷; 如实抛而不是回一条"重置成功"。
            throw new IllegalStateException(
                    "no resident region instance for difficulty " + difficulty.configName());
        }
        result.addProperty("instanceId", inst.instanceId());
        result.addProperty("genState", inst.genState().name());

        ResetPlan plan = planReset(inst,
                MiningServices.config().resetRequireEmpty(), MiningServices.config().resetKickOnForce());
        result.addProperty("evictedPlayers", plan.evictedPlayers());
        if (!plan.accepted()) {
            result.addProperty("accepted", false);
            result.addProperty("reasonCode", plan.reasonCode());
            /*
             * 拒绝也必须留一行。此前只有受理路径记日志, 于是"区域里还有人"或"正在生成中"导致的拒绝在服务端
             * 侧毫无现场 —— 只有点按钮那个人在面板上看过一句提示, 事后无从复查。实测踩过: 排查一次"重置没
             * 生效"时, 日志里查不到该难度的任何记录, 而"没请求过"与"被拒了"这两种完全不同的情况长得一模一样。
             */
            LOGGER.info("[miningdim] WebUI admin {} was REFUSED a {} reset of {} region (instance {}): "
                            + "reasonCode={}, genState={}, players present={}",
                    sender.getGameProfile().getName(), mode, difficulty.configName(), inst.instanceId(),
                    plan.reasonCode(), inst.genState(), plan.evictedPlayers());
            return GSON.toJson(result);
        }

        if (plan.evacuateFirst()) {
            // 撤离必须在删区块之前完成 (13.3), 且它同时把玩家从 playerSet 里摘掉, reset 的 requireEmpty
            // 前置条件才会成立。离线玩家由 evacuate 标记待撤离, 登录时再送回。
            MiningServices.resetService().evacuate(inst, sender.getServer());
        }
        long instanceId = inst.instanceId();
        String operator = sender.getGameProfile().getName();
        LOGGER.info("[miningdim] WebUI admin {} requested {} reset of {} region (instance {}), evicting {} player(s)",
                operator, mode, difficulty.configName(), instanceId, plan.evictedPlayers());
        // future 的终局必须有人接: 面板已经拿到同步回执走了, 失败若无人记录就彻底没有现场。
        MiningServices.resetService().reset(instanceId, mode).whenComplete((ignored, error) -> {
            if (error != null) {
                LOGGER.warn("[miningdim] WebUI admin reset of instance {} requested by {} FAILED: {}",
                        instanceId, operator, error.toString());
            } else {
                LOGGER.info("[miningdim] WebUI admin reset of instance {} requested by {} complete",
                        instanceId, operator);
            }
        });

        result.addProperty("accepted", true);
        result.add("reasonCode", JsonNull.INSTANCE);
        return GSON.toJson(result);
    };

    // ============================================================
    // 前置裁决 (纯函数)
    // ============================================================

    /**
     * 一次重置请求的前置裁决结果。
     *
     * @param accepted       是否会真正下发给 {@link IResetService#reset}
     * @param reasonCode     被拒原因机器码 (受理时为 null)
     * @param evictedPlayers 会被踢出该区域的玩家数 (= 裁决那一刻的在场人数; 含离线待撤离者)
     * @param evacuateFirst  下发 reset 之前是否要先清场
     */
    record ResetPlan(boolean accepted, String reasonCode, int evictedPlayers, boolean evacuateFirst) {
    }

    /**
     * 纯函数裁决: 这次重置该不该受理, 要不要先清场, 会踢掉几个人。
     *
     * 三条判据逐条对应 {@code ResetSystem.reset} 的前置校验 —— 它在这些情况下只会返回 failedFuture,
     * 提前判出来才能给玩家一条同步且说得清的拒绝, 而不是"受理成功但什么也没发生"。
     */
    static ResetPlan planReset(InstanceState inst, boolean requireEmpty, boolean kickOnForce) {
        int occupants = inst.refCount();
        // 13.2: 只有 READY / READY_FALLBACK 能重置; GENERATING / RESETTING / 已回收一律拒。
        if (!inst.genState().isEnterable()) {
            return new ResetPlan(false, "NOT_RESETTABLE", occupants, false);
        }
        if (occupants == 0) {
            return new ResetPlan(true, null, 0, false);
        }
        if (kickOnForce) {
            return new ResetPlan(true, null, occupants, true);
        }
        // 有人在场、又不许强制清场: requireEmpty 开着就必然被 reset 拒, 关着则可以带人重置 (配置如此选择)。
        if (requireEmpty) {
            return new ResetPlan(false, "OCCUPIED", occupants, false);
        }
        return new ResetPlan(true, null, occupants, false);
    }

}
