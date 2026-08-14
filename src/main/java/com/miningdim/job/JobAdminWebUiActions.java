package com.miningdim.job;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.miningdim.entry.IMiningPlayerData;
import com.miningdim.entry.MiningCapabilities;
import com.miningdim.webui.server.WebUiPayloads;
import com.miningdim.webui.server.WebUiPermissions;
import com.miningdim.webui.server.WebUiServerDispatcher;
import com.miningdim.webui.server.WebUiServerDispatcher.WebUiAction;
import net.minecraft.server.level.ServerPlayer;

/**
 * 职业管理台的 admin.job.setLevel WebUiAction (W10, OP 门控)。
 *
 * 薄封装, 逐步照抄 {@code /job set} 的既有链路 ({@link JobCommands} 的 set 分支): 解析职业 id -&gt; 取目标玩家
 * 的 entry 唯一权威 capability -&gt; {@link JobProgress#setLevel} -&gt; {@link JobFrameworkSystem#syncTo} 下发 S2C。
 * 命令与面板必须是同一条链路 —— 面板另写一份"改级"逻辑, 迟早出现"命令改的能同步、面板改的不能"这种鬼。
 *
 * syncTo 不可省 (故本类必须持 {@link JobFrameworkSystem} 实例): 职业等级在客户端有一份镜像
 * ({@link ClientJobState}, 登录时经 {@link com.miningdim.network.JobSyncS2C} 灌入), 只改服务端不同步, 目标
 * 玩家的游戏内 HUD 会一直显示旧等级直到下次重登。
 *
 * 权限 (架构铁律 1): 先过 {@link WebUiPermissions#isOp}, 早于任何 payload 解析与副作用 —— 非 OP 连"哪个字段
 * 写错了"都不该知道。前端拿到 player.isOp=true 不等于服务端放行, 本条自己校验。
 */
public final class JobAdminWebUiActions {

    private static final Gson GSON = new Gson();

    /**
     * 职业框架子系统实例 (只为调 {@link JobFrameworkSystem#syncTo})。
     *
     * 进程级单例引用, 由 {@link #registerAll} 在子系统 register 期注入一次; 与 {@link JobServices} 的服务定位器
     * 同范式。之所以不像别的 action 那样纯静态: syncTo 是子系统的实例方法, 而本组无权改它的签名。
     */
    private static JobFrameworkSystem framework;

    private JobAdminWebUiActions() {
    }

    /**
     * 把 admin.job.setLevel 注册进派发器 (由 {@link JobFrameworkSystem#register} 调用一次)。
     *
     * @param system 职业框架子系统实例 (改级后经它 syncTo; null 属装配缺陷, 自然抛)
     */
    public static void registerAll(JobFrameworkSystem system) {
        if (system == null) {
            throw new IllegalArgumentException("JobAdminWebUiActions 需要 JobFrameworkSystem 实例才能在改级后同步客户端");
        }
        framework = system;
        WebUiServerDispatcher.register("admin.job.setLevel", SET_LEVEL);
    }

    // ============================================================
    // admin.job.setLevel: {playerName, jobId, level} -> 改后真值 (OP 门控)
    // ============================================================

    /**
     * 把目标在线玩家的某职业设成指定等级。
     *
     * 回执发的是<b>改完之后从 capability 读回来的真值</b> (level/totalXp), 不是把入参原样回显: setLevel 会把
     * 累计经验一并对齐到该级的整级线 ({@link JobProgress#setLevel}), 面板必须看得见这个副作用, 否则 OP 会以为
     * 自己只动了等级。
     *
     * 只认在线玩家: 离线玩家的 capability 不在内存里, 改了也没有 syncTo 的对象。离线改级属另一个题目 (要落到
     * 存档 NBT), 不在本条的范围内 —— 明确拒绝, 不静默假成功。
     */
    static final WebUiAction SET_LEVEL = (sender, payload) -> {
        WebUiPermissions.requireOp(sender, "admin.job.setLevel");

        String playerName = WebUiPayloads.requiredString(payload, "playerName");
        String jobIdRaw = WebUiPayloads.requiredString(payload, "jobId");
        int level = WebUiPayloads.requiredInt(payload, "level");

        // JobId.byId 兼容 armorer/铸甲师 两个历史别名 (见其实现), 故这里回执发的是归一化后的 job.id()。
        JobId job = JobId.byId(jobIdRaw);
        if (job == null) {
            throw WebUiPayloads.illegalValue("jobId", jobIdRaw, "未知职业 id: " + jobIdRaw);
        }
        if (level < JobXpCurve.MIN_LEVEL || level > JobXpCurve.MAX_LEVEL) {
            throw WebUiPayloads.illegalValue("level", Integer.toString(level),
                    "职业等级必须在 [" + JobXpCurve.MIN_LEVEL + ", " + JobXpCurve.MAX_LEVEL + "] 内");
        }
        ServerPlayer target = sender.getServer().getPlayerList().getPlayerByName(playerName);
        if (target == null) {
            throw WebUiPayloads.illegalValue("playerName", playerName, "玩家不在线: " + playerName);
        }

        // capability 缺失是 Provider 没挂上的环境故障, 不是玩家能应对的业务拒绝: 让它冒泡到 Gateway 的通用兜底
        // (与 JobWebUiActions.playerData 同纪律), 不占错误码表的位置。
        IMiningPlayerData data = MiningCapabilities.get(target).orElseThrow(() -> new IllegalStateException(
                "玩家 " + target.getGameProfile().getName() + " 未挂载矿山玩家数据 capability"));
        JobProgress progress = data.jobProgress(job);
        progress.setLevel(level);
        requireFramework().syncTo(target);

        JsonObject result = new JsonObject();
        result.addProperty("playerName", target.getGameProfile().getName());
        result.addProperty("playerUuid", target.getUUID().toString());
        result.addProperty("jobId", job.id());
        result.addProperty("level", progress.level());
        result.addProperty("totalXp", progress.xp(job));
        return GSON.toJson(result);
    };

    /** 取已注入的职业框架实例; 未注入即注册顺序出了问题, 自然抛不掩盖。 */
    private static JobFrameworkSystem requireFramework() {
        JobFrameworkSystem system = framework;
        if (system == null) {
            throw new IllegalStateException(
                    "JobAdminWebUiActions: 尚未注入 JobFrameworkSystem (检查 Subsystem register 顺序)");
        }
        return system;
    }
}
