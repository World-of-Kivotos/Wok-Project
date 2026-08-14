package com.miningdim.job;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.miningdim.entry.IMiningPlayerData;
import com.miningdim.entry.MiningCapabilities;
import com.miningdim.webui.server.WebUiServerDispatcher;
import com.miningdim.webui.server.WebUiServerDispatcher.WebUiAction;
import net.minecraft.server.level.ServerPlayer;

/**
 * 职业框架层的 job.* WebUiAction (职业总览页的 8 条进度)。
 *
 * 与 {@code player.profile} 的 jobs[] 同形且同实现 (共用 {@link JobProgressJson#of}); 独立成一条只为省掉
 * 钱包与 faucet 那 3 次 SQLite —— 职业页既不显示余额也不显示今日收入, 走 profile 是白打三次库。
 *
 * 前端契约 (webui/src/lib/types.ts): job.progress -&gt; {jobs:[{jobId,level,totalXp,levelXp,nextLevelXp,
 * dailyXp,dailyRemaining}]}, 恒 8 条, 顺序 = {@link JobId#values()} 声明序。
 */
public final class JobWebUiActions {

    private static final Gson GSON = new Gson();

    private JobWebUiActions() {
    }

    /** 把 job.progress 注册进派发器 (由 {@link JobFrameworkSystem#register} 调用一次)。 */
    public static void registerAll() {
        WebUiServerDispatcher.register("job.progress", PROGRESS);
    }

    /**
     * 发送者全 8 职业的进度。
     *
     * 一次 resolve capability 后遍历 8 个职业, 不许对每个职业各调一次 {@link IJobService} —— 那会 resolve
     * 八次 capability (与 player.profile 同纪律)。
     *
     * 日戳取 {@link JobServiceImpl#currentUtcDayStamp()} 而不是经济门面的 currentDayStamp: 这里比较的是
     * {@link JobProgress#dayStamp()}, 而那个字段的唯一写入方就是 grantXp 传进去的这个值 —— 读比较基准就该用
     * 它的写入源。走经济门面则会让职业总览页硬依赖经济子系统: 矿山维度缺失时门面故意不注入 (ServerStopping
     * 后也会 reset), 那时整页拿到一条没有 errorCode 的通用失败, 而本条 action 本来一次库都不打。
     * 两个时钟不会漂移: 三处实现都是逐字相同的 Instant.now().atZone(UTC).toLocalDate().toEpochDay() 纯表达式。
     */
    static final WebUiAction PROGRESS = (sender, payload) -> {
        IMiningPlayerData data = playerData(sender);
        long todayStamp = JobServiceImpl.currentUtcDayStamp();

        JsonArray jobs = new JsonArray();
        for (JobId job : JobId.values()) {
            jobs.add(JobProgressJson.of(job, data.jobProgress(job), todayStamp));
        }
        JsonObject result = new JsonObject();
        result.add("jobs", jobs);
        return GSON.toJson(result);
    };

    /**
     * 取发送者的玩家 capability。
     *
     * capability 缺失不给 errorCode: 那是 Provider 没挂上的环境故障, 不是玩家能理解也无法应对的业务拒绝。
     * 让 IllegalStateException 自然冒泡到 Gateway 的通用兜底, 守住"errorCode 表只收真正的业务拒绝"这条纪律。
     */
    private static IMiningPlayerData playerData(ServerPlayer sender) {
        return MiningCapabilities.get(sender).orElseThrow(() -> new IllegalStateException(
                "玩家 " + sender.getGameProfile().getName() + " 未挂载矿山玩家数据 capability"));
    }
}
