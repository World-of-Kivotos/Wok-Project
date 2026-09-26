package com.miningdim.achievement.trigger;

import com.google.gson.JsonObject;
import com.google.gson.JsonSyntaxException;
import com.miningdim.achievement.AchievementIds;
import com.miningdim.job.JobId;
import com.miningdim.job.JobXpCurve;
import net.minecraft.advancements.critereon.AbstractCriterionTriggerInstance;
import net.minecraft.advancements.critereon.ContextAwarePredicate;
import net.minecraft.advancements.critereon.DeserializationContext;
import net.minecraft.advancements.critereon.SerializationContext;
import net.minecraft.advancements.critereon.SimpleCriterionTrigger;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.GsonHelper;
import org.jetbrains.annotations.Nullable;

import java.util.Map;

/**
 * {@code miningdim:job_level} (6.2): 职业等级达标。条件字段:
 * <ul>
 *   <li>{@code level}: 必填, 1~10, 至少达到的等级;</li>
 *   <li>{@code job}: 可选, 按 {@link JobId#byId} 解析 (兼容旧别名 armorer), 写出时一律用稳定 id; 缺省表示任意职业;</li>
 *   <li>{@code jobs_count}: 可选, 1~8, 缺省 1: 至少有这么多个职业达到 {@code level}。写了 {@code job} 时只能是 1,
 *       否则条件永远达不成, 按写错处理。</li>
 * </ul>
 * 触发时带上玩家全部八个职业的当前等级 ({@link JobHooks#checkJobLevels}), 条件在这一份快照上判定, 所以
 * "全部 8 个职业满级"不论最后升上去的是哪个职业都能判出来。
 */
public final class JobLevelTrigger extends SimpleCriterionTrigger<JobLevelTrigger.TriggerInstance> {

    static final ResourceLocation ID = AchievementIds.id("job_level");

    /** jobs_count 的缺省值与下界。 */
    private static final int ONE_JOB = 1;

    JobLevelTrigger() {
    }

    @Override
    public ResourceLocation getId() {
        return ID;
    }

    @Override
    protected TriggerInstance createInstance(JsonObject json, ContextAwarePredicate player,
                                             DeserializationContext context) {
        int level = TriggerJson.requiredIntInRange(json, "level", JobXpCurve.MIN_LEVEL, JobXpCurve.MAX_LEVEL);
        JobId job = json.has("job") ? parseJob(GsonHelper.getAsString(json, "job")) : null;
        int jobsCount = TriggerJson.intInRange(json, "jobs_count", ONE_JOB, ONE_JOB, JobId.values().length);
        if (job != null && jobsCount != ONE_JOB) {
            throw new JsonSyntaxException("'jobs_count' must be 1 when 'job' is given, got " + jobsCount);
        }
        return new TriggerInstance(player, level, job, jobsCount);
    }

    /**
     * 按玩家全部职业的当前等级核对他尚未完成的 job_level 条件。
     *
     * @param levels 八个职业各自的当前等级
     */
    public void trigger(ServerPlayer player, Map<JobId, Integer> levels) {
        trigger(player, instance -> instance.matches(levels));
    }

    private static JobId parseJob(String raw) {
        JobId job = JobId.byId(raw);
        if (job == null) {
            throw new JsonSyntaxException("unknown job '" + raw + "'");
        }
        return job;
    }

    public static final class TriggerInstance extends AbstractCriterionTriggerInstance {

        private final int level;
        @Nullable
        private final JobId job;
        private final int jobsCount;

        public TriggerInstance(ContextAwarePredicate player, int level, @Nullable JobId job, int jobsCount) {
            super(ID, player);
            this.level = level;
            this.job = job;
            this.jobsCount = jobsCount;
        }

        /** 任意一个职业达到 level。 */
        public static TriggerInstance anyJob(int level) {
            return new TriggerInstance(ContextAwarePredicate.ANY, level, null, ONE_JOB);
        }

        /** 至少 jobsCount 个职业达到 level。 */
        public static TriggerInstance jobsAtLeast(int level, int jobsCount) {
            return new TriggerInstance(ContextAwarePredicate.ANY, level, null, jobsCount);
        }

        boolean matches(Map<JobId, Integer> levels) {
            if (job != null) {
                return levels.getOrDefault(job, JobXpCurve.MIN_LEVEL) >= level;
            }
            long reached = levels.values().stream().filter(current -> current >= level).count();
            return reached >= jobsCount;
        }

        @Override
        public JsonObject serializeToJson(SerializationContext context) {
            JsonObject json = super.serializeToJson(context);
            json.addProperty("level", level);
            if (job != null) {
                json.addProperty("job", job.id());
            }
            if (jobsCount != ONE_JOB) {
                json.addProperty("jobs_count", jobsCount);
            }
            return json;
        }
    }
}
