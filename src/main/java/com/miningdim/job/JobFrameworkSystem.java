package com.miningdim.job;

import com.miningdim.core.Subsystem;
import com.miningdim.effect.ModJobEffects;
import com.miningdim.effect.VulnerabilityHurtHandler;
import com.miningdim.entry.IMiningPlayerData;
import com.miningdim.entry.MiningCapabilities;
import com.miningdim.menu.ModMenus;
import com.miningdim.network.JobSyncS2C;
import com.miningdim.network.MiningNetwork;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.EnumMap;
import java.util.Map;
import java.util.Optional;

/**
 * 职业框架子系统入口 (JobFramework_Shared_Foundation_DesignSpec 第九/十二章; 模块化铁律 3)。装配:
 *  - 共享效果注册 ({@link ModJobEffects}, modBus) + 易伤单一全局仲裁 ({@link VulnerabilityHurtHandler}, forgeBus);
 *  - 公共 menu 脚手架 DeferredRegister ({@link ModMenus}, modBus);
 *  - 职业框架门面 ({@link IJobService}) 注入 {@link JobServices} 定位器 (构造期立即注入);
 *  - /job 命令独立根 ({@link JobCommands}, RegisterCommandsEvent);
 *  - 登录同步: PlayerLoggedInEvent 下发全职业进度 S2C 给客户端镜像。
 *
 * 职业进度存储 (第 2.3 节已落地): 全职业进度 EnumMap 收敛进 entry 唯一权威玩家 capability
 * ({@link MiningCapabilities}), 本子系统不再持第二套 capability/attach/Clone (已删 job.JobCapability)。
 * 职业进度的注册/挂载/Clone 复制随 entry capability 走; 本子系统只经 {@link MiningCapabilities#get} 取用。
 *
 * 注入顺序: 构造期即 registerJobService (引用绑定, 不依赖其它子系统); 取用他人服务推迟到事件回调,
 * 故对主类 List&lt;Subsystem&gt; 顺序不敏感 (与 entry/economy 同范式)。本子系统已在
 * {@code MiningDim.registerSubsystems()} 实装 (排在 EntrySystem 之后, 确保 entry capability 先注册)。
 */
public final class JobFrameworkSystem implements Subsystem {

    private static final Logger LOGGER = LoggerFactory.getLogger("miningdim/job");

    private final JobServiceImpl jobService = new JobServiceImpl();

    @Override
    public void register(IEventBus modBus, IEventBus forgeBus) {
        // 构造期注入门面 (引用绑定先于任何取用)。
        JobServices.registerJobService(jobService);

        // 职业进度存储已并入 entry 唯一权威 capability (第 2.3 节), 此处不再注册第二套玩家 capability。

        // 共享效果 DeferredRegister (modBus) + menu DeferredRegister (modBus)。
        ModJobEffects.register(modBus);
        ModMenus.register(modBus);

        // 易伤单一全局仲裁点 (forgeBus): 全 mod 唯一一处易伤乘伤 handler (第五章红线)。
        forgeBus.register(new VulnerabilityHurtHandler());

        // /job 命令 + 登录同步 (本子系统自身的 forge 事件)。
        forgeBus.register(this);

        // 平板职业页的 job.progress (进程级静态注册, 与门面就绪无关)。
        JobWebUiActions.registerAll();

        // 管理台职业页的 admin.job.setLevel (改级后要经本实例 syncTo, 故把自己交出去)。
        JobAdminWebUiActions.registerAll(this);

        LOGGER.info("[miningdim] job framework subsystem registered (effects + menu scaffold + /job + job.progress action; progress on entry capability)");
    }

    @SubscribeEvent
    public void onRegisterCommands(RegisterCommandsEvent event) {
        new JobCommands(this).register(event.getDispatcher());
    }

    @SubscribeEvent
    public void onPlayerLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            syncTo(player);
        }
    }

    @SubscribeEvent
    public void onServerStopping(ServerStoppingEvent event) {
        // 清门面引用, 防跨存档/跨重启脏引用 (与 MiningServices.reset 同纪律)。
        JobServices.reset();
    }

    /** 把某玩家全职业进度下发 S2C 同步客户端镜像 (登录 / OP 改级后调用)。 */
    public void syncTo(ServerPlayer player) {
        Optional<IMiningPlayerData> data = MiningCapabilities.get(player);
        if (data.isEmpty()) {
            return; // 能力未挂载 (极端时序): 无可同步, 跳过。
        }
        IMiningPlayerData mpd = data.get();
        Map<JobId, long[]> levels = new EnumMap<>(JobId.class);
        for (JobId job : JobId.values()) {
            JobProgress p = mpd.jobProgress(job);
            levels.put(job, new long[]{p.level(), p.xp(job)});
        }
        MiningNetwork.sendJobSync(player, new JobSyncS2C(levels));
    }

    @Override
    public String name() {
        return "JobFrameworkSystem";
    }
}
