package com.miningdim.achievement.trigger;

import com.miningdim.achievement.AchievementIds;
import com.miningdim.job.JobExperienceTracks;
import com.miningdim.job.JobId;
import com.miningdim.job.agent.AgentEvents;
import com.miningdim.job.brewer.BrewerEvents;
import com.miningdim.job.chef.ChefEvents;
import com.miningdim.job.engineer.EngineerEvents;
import com.miningdim.job.munitions.MunitionsEvents;
import com.miningdim.job.tarot.TarotEvents;
import com.miningdim.progression.ExperienceAward;
import com.miningdim.progression.ExperienceServices;
import com.miningdim.progression.IExperienceService;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.common.util.FakePlayer;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * 职业相关的 P2 钩子 (Achievement_System_DesignSpec 9.3、9.10): 把各玩法模块在自己包里提供的多播监听接口接到
 * 成就的统计项与触发器上。依赖方向是成就依赖玩法模块, 玩法模块不引用成就模块。
 *
 * <ul>
 *   <li>{@code wok-experience} 的经验发放 ({@link ExperienceServices#registerAwardListener}): 来源为农夫收获或采摘的,
 *       给 farmer_harvests 加 1; 职业轨道的等级上升时, 读全部八个职业的当前等级触发 job_level。</li>
 *   <li>厨师出菜、在线酿酒、塔罗出牌、特勤封印、生产台出板、军火台产弹落账: 各自触发对应的触发器。</li>
 * </ul>
 *
 * <p>异常纪律: 广播方一律不吞监听器异常, 所以这里每个监听器都经 {@link #guarded} 包一层, 自己捕获并记错误日志,
 * 绝不让一次成就判定打断调味台、酿酒台、经验发放等调用方。FakePlayer 一律跳过。
 *
 * <p>本类只在 mod 构造期注册一次 ({@link #register}); 各广播方的监听表随进程存在, 不随停服清空, 这里也就没有停服钩子。
 */
public final class JobHooks {

    private static final Logger LOGGER = LoggerFactory.getLogger("miningdim/achievement");

    /** 农夫模块的两个经验来源 id (按来源 id 识别, 不引用农夫模块)。 */
    static final Set<ResourceLocation> FARMER_HARVEST_SOURCES =
            Set.of(AchievementIds.id("farmer/harvest"), AchievementIds.id("farmer/pick"));

    /** 八条职业经验轨道 -> 职业。 */
    private static final Map<ResourceLocation, JobId> JOB_TRACKS = jobTracks();

    private JobHooks() {
    }

    /**
     * mod 构造期调用: 挂职业统计项的 DeferredRegister, FMLCommonSetup 里登记职业触发器并建好统计项, 再把监听器注册进
     * 各广播方。
     */
    public static void register(IEventBus modBus) {
        JobStats.register(modBus);
        modBus.addListener((FMLCommonSetupEvent event) -> event.enqueueWork(() -> {
            JobTriggers.register();
            JobStats.bindFormatters();
        }));
        ExperienceServices.registerAwardListener((player, award, levelBefore) -> {
            if (player instanceof ServerPlayer serverPlayer) {
                guarded("experience_award", serverPlayer, () -> onExperienceAward(serverPlayer, award, levelBefore));
            }
        });
        ChefEvents.addDishListener((chef, quality, target) ->
                guarded("chef_dish", chef, () -> JobTriggers.CHEF_DISH.trigger(chef, quality, target)));
        BrewerEvents.addBrewListener((brewer, type, quality) ->
                guarded("brew_complete", brewer, () -> JobTriggers.BREW_COMPLETE.trigger(brewer, type, quality)));
        TarotEvents.addPlayListener((player, cardId, quality) ->
                guarded("tarot_play", player, () -> JobTriggers.TAROT_PLAY.trigger(player, cardId, quality)));
        AgentEvents.addSealListener((agent, star, affix, category) ->
                guarded("agent_seal", agent, () -> JobTriggers.AGENT_SEAL.trigger(agent, star, affix, category)));
        EngineerEvents.addPlateListener((engineer, tier, plates) -> guarded("nano_plate_produced", engineer,
                () -> JobTriggers.NANO_PLATE_PRODUCED.trigger(engineer, tier)));
        MunitionsEvents.addBatchListener((owner, caliber, rounds) ->
                guarded("munitions_batch", owner, () -> JobTriggers.MUNITIONS_BATCH.trigger(owner)));
    }

    /**
     * 按玩家全部八个职业的当前等级核对 job_level (读 {@code wok-experience} 里各职业轨道的快照)。经验发放让某个职业
     * 升级时由本类调用; 上线追溯 (9.9) 也经这里补查职业等级 —— {@code /job set} 等直接改等级的操作不经经验路由,
     * 要等下一次任意职业升级或下一次追溯才会被判到。
     */
    public static void checkJobLevels(ServerPlayer player) {
        IExperienceService experience = ExperienceServices.experienceService();
        Map<JobId, Integer> levels = new EnumMap<>(JobId.class);
        JOB_TRACKS.forEach((track, job) -> levels.put(job, experience.snapshot(player, track).level()));
        JobTriggers.JOB_LEVEL.trigger(player, levels);
    }

    /** 一笔经验已经落到轨道: 农夫收获计数, 以及职业升级时的等级判定。 */
    static void onExperienceAward(ServerPlayer player, ExperienceAward award, int levelBefore) {
        if (FARMER_HARVEST_SOURCES.contains(award.sourceId())) {
            AchievementStats.award(player, JobStats.FARMER_HARVESTS, 1);
        }
        if (award.snapshot().level() > levelBefore && JOB_TRACKS.containsKey(award.trackId())) {
            checkJobLevels(player);
        }
    }

    /**
     * 执行一个监听动作: FakePlayer 直接跳过; 运行期异常就地捕获并记错误日志 (写明哪个接口、哪个玩家), 不冒回广播方。
     */
    static void guarded(String seam, ServerPlayer player, Runnable action) {
        if (player instanceof FakePlayer) {
            return;
        }
        try {
            action.run();
        } catch (RuntimeException failure) {
            LOGGER.error("[miningdim] achievement {} listener failed for {}; the producer carries on", seam,
                    player.getGameProfile().getName(), failure);
        }
    }

    private static Map<ResourceLocation, JobId> jobTracks() {
        Map<ResourceLocation, JobId> tracks = new LinkedHashMap<>();
        for (JobId job : JobId.values()) {
            tracks.put(JobExperienceTracks.track(job), job);
        }
        return Collections.unmodifiableMap(tracks);
    }
}
