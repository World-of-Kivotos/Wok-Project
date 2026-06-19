package com.miningdim;

import com.miningdim.core.MiningConstants;
import com.miningdim.core.Subsystem;
import com.miningdim.registry.ModBlocks;
import com.miningdim.registry.ModItems;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * mod 主类 / 唯一入口 (@Mod). 模块化铁律 3: 只持有一个 List<Subsystem> 逐个 register,
 * 增删功能 = 改 {@link #registerSubsystems()} 一行。主类自身不写任何业务逻辑、不订阅业务事件。
 *
 * 装配顺序 (重要, 见 MiningServices 注释的注入顺序契约): List 顺序即门面注入顺序。凡某子系统在
 * register 期 (mod 构造) 就要取用另一子系统的服务, 被依赖者须排在前面。本工程多数子系统把服务取用
 * 推迟到事件回调 (服务端启动后), 故对顺序不敏感; 仅以下硬约束必须满足:
 *   1. ConfigSystem 最先: 它在 mod 构造期 registerConfig 并注入 IMiningConfig; InstanceManager 构建期读 config。
 *   2. WorldgenSystem 早于 InstanceSystem: instance 调度器在分配时取 offlineGenerator(), 且 instance 在
 *      ServerStartedEvent 把 worldgen 的体素查表 seam (MiningVoxelLookup) 接上离线调度器。
 *   3. NetworkSystem 早于消费 network() 的子系统的服务端启动逻辑 (构造期注入即满足)。
 *
 * 跨子系统冲突的集成裁决 (阶段2, 见 README "已知架构裁决"):
 *   - 玩家 Capability 与入场/离开/登录恢复路径以 entry 子系统为唯一权威 (EntrySystem + MiningCapabilities):
 *     它实现了设计文档 14.2 完整防虚空进入链路与 14.6 登录恢复, reset 子系统亦依赖其能力。职业进度 EnumMap
 *     亦并入此唯一权威 capability (JobFramework_Shared_Foundation_DesignSpec 第 2.3 节)。并行开发期 persistence
 *     包曾另产出一套等价玩家 Capability (二者同时挂载会重复 attach 能力并重复触发离开/登录恢复, 双重传送/双重
 *     引用计数), 现已按第 2.3 节删除该死包 (仅保留 InstanceManager 仍用的 MiningSavedData); InstanceSystem
 *     只保留实例后端 (InstanceManager/SavedData/区块加载/GC)。
 *   - /mining 命令树以 entry.MiningCommands 为唯一权威 (匹配设计文档 14.1 DECIDED: enter/leave/reset/info,
 *     且 enter 走 EntryGateway 真实传送)。command 包的 CommandSystem 是并行期产出的另一套 /mining (其 enter
 *     仅 allocate 不传送, 与 14.2 不符), 不接入主类以避免 Brigadier 双根冲突。
 */
@Mod(MiningConstants.MODID)
public final class MiningDim {

    private static final Logger LOGGER = LoggerFactory.getLogger("miningdim");

    /** 全部子系统入口, 按注入顺序排列 (见类注释)。 */
    private final List<Subsystem> subsystems = new ArrayList<>();

    public MiningDim() {
        IEventBus modBus = FMLJavaModLoadingContext.get().getModEventBus();
        IEventBus forgeBus = MinecraftForge.EVENT_BUS;

        // 基础 registry: 方块/物品的 DeferredRegister 接上 modBus (自订阅 RegisterEvent)。
        ModBlocks.register(modBus);
        ModItems.register(modBus);

        // ChunkGenerator / BiomeSource 的 Codec 直注 (RegisterEvent): 由 WorldgenSystem 在其 register 内
        // 订阅 RegisterEvent 完成 (注册逻辑随子系统走, 见 worldgen.WorldgenSystem.onRegister)。

        registerSubsystems();
        for (Subsystem sub : subsystems) {
            LOGGER.info("[miningdim] registering subsystem: {}", sub.name());
            sub.register(modBus, forgeBus);
        }

        LOGGER.info("[miningdim] all subsystems wired; {} subsystem(s) registered", subsystems.size());
    }

    /**
     * 子系统装配点 (模块化铁律 3): 增删功能 = 改此方法。顺序约束见类注释。
     */
    private void registerSubsystems() {
        // 1. 配置最先注入 (其他子系统在服务端启动逻辑里读 config; InstanceManager 构建期读 config)。
        subsystems.add(new com.miningdim.config.ConfigSystem());
        // 2. 网络门面 (构造期注入, 供命令/进入流程在运行期下发包)。
        subsystems.add(new com.miningdim.network.NetworkSystem());
        // 3. 世界生成: 注入 IOfflineGenerator + 注册两个 Codec, 须早于 InstanceSystem。
        subsystems.add(new com.miningdim.worldgen.WorldgenSystem());
        // 4. 实例后端: InstanceManager / SavedData / 区块强加载调度 / GC (玩家 Capability 归 entry, 见类注释)。
        subsystems.add(new com.miningdim.instance.InstanceSystem());
        // 5. 区块强加载窗口维护 (依赖 instanceManager()/config(), 运行期取用)。
        subsystems.add(new com.miningdim.chunk.ChunkSystem());
        // 6. 重置服务门面 (依赖 entry 的玩家回退态; 运行期取用 instanceManager/offlineGenerator)。
        subsystems.add(new com.miningdim.reset.ResetSystem());
        // 7. 安全出生服务门面 (实现 ISpawnService, 进入流程在运行期调用)。
        subsystems.add(new com.miningdim.spawn.SpawnSystem());
        // 8. 矿物离线铺设 + 查表 (无事件, 静态查表入口)。
        subsystems.add(new com.miningdim.ore.OreSystem());
        // 9. 陷阱: 静态布点查表 + 动态陷阱 tick 引擎。
        subsystems.add(new com.miningdim.trap.TrapSystem());
        // 10. 动态压力刷怪 (运行期 tick 取 instanceManager/network/config)。
        subsystems.add(new com.miningdim.pressure.PressureSystem());
        // 11. 经济/反滥用闸门 (事件型, 无对外门面)。
        subsystems.add(new com.miningdim.economy.EconomySystem());
        // 12. 放置规则: 矿山维度内放置白名单 (R7, 事件型, 无对外门面)。
        subsystems.add(new com.miningdim.rules.RulesSystem());
        // 13. 边界兜底 + 启动期维度自检 (事件型, 无对外门面)。
        subsystems.add(new com.miningdim.error.ErrorSystem());
        // 14. 入场子系统 (唯一权威): 玩家 Capability + /mining 命令树 + 进入/离开/登录恢复编排。
        //     EntrySystem 在 ServerStartedEvent 把入场触发器 bind 进 entrance seam (EntranceHooks),
        //     供入口方块转调; 二者经 seam 解耦, 接线在启动期完成, 故对 register 顺序不敏感。
        subsystems.add(new com.miningdim.entry.EntrySystem());
        // 15. 入口方块子系统 (R4): 三难度入口方块的方块实体类型 + 创造物品栏注册。
        subsystems.add(new com.miningdim.entrance.EntranceSystem());

        // 16. 职业框架地基 (JobFramework_Shared_Foundation): 职业进度 Capability + 共享效果/menu 脚手架
        //     + 易伤单一全局仲裁 + IJobService 门面注入 + /job 命令 + 登录同步。须排在所有具体职业之前:
        //     各职业在事件回调内经 JobServices.jobService() 读等级/给经验, 框架须先注入门面。
        subsystems.add(new com.miningdim.job.JobFrameworkSystem());
        // 17. 矿工职业: 挖速加成 / 谁挖谁得经验 / 连锁挖矿 / 矿物探测 / 便利技能 (依赖职业框架门面)。
        subsystems.add(new com.miningdim.job.miner.MinerSystem());
        // 18. 农夫职业: 分档耕地 + mod 小麦 + 收购闸门 (依赖职业框架门面)。
        subsystems.add(new com.miningdim.job.farmer.FarmerSystem());
        // 19. 千年工程师职业: 六档纳米护甲板 + 生产台 GUI/校准 QTE + 修复曲线 (依赖职业框架门面)。
        subsystems.add(new com.miningdim.job.engineer.EngineerSystem());
        // 20. 塔罗师职业: 塔罗牌 datapack 牌效 + 卡包 gacha + 合成台 (依赖职业框架门面)。
        subsystems.add(new com.miningdim.job.tarot.TarotSystem());
        // 21. 厨师职业: 五档调味台 + 火候小游戏 + 菜肴效果 (依赖职业框架门面 + 共享 menu 脚手架)。
        subsystems.add(new com.miningdim.job.chef.ChefSystem());
        // 22. 军火商职业: 军火台被动产线 + 双推进剂弹药制造 + 工费 sink (依赖职业框架门面 + 货币门面 + TACZ compileOnly)。
        subsystems.add(new com.miningdim.job.munitions.MunitionsSystem());
        // 23. 精英怪星级词条 (Champions compileOnly): 35 词条注册 + 按矿洞难度升格冠军 + 6star+ 血池拦死 +
        //     贡献池奖励并入 credit_faucet 主闸。纯逻辑层 (星表/血池/红线/贡献池) 始终生效; 真词条/血池/奖励
        //     仅 ModList.isLoaded("champions") 为真时装配。压力子系统 spawnMob 经 ChampionSpawnSeam 回调升格,
        //     故须排在压力子系统 (第 10) 之后 (seam 在启动期 bind, 对 register 顺序不敏感, 此处列尾即可)。
        subsystems.add(new com.miningdim.champion.ChampionSystem());
        // 24. 特勤干员职业 (Champions compileOnly): 探测精英 + 临时封印词条 (不叠加, 到期恢复) + 加强奖励 (按初始
        //     星级×等级倍率, 经 grantDaily 并入 credit_faucet 主闸, 复用精英怪贡献池按伤害分) + 日常/周常悬赏 (周
        //     青辉石 AZURE 绑定) + 对精英少量伤害加成。纯逻辑层 (五支线查表/封印账本/加强奖励倍率/悬赏计数/周序)
        //     始终生效; 真探测/真封印/奖励结算仅 ModList.isLoaded("champions") 为真时装配。复用已落地 champion 子系统
        //     的贡献池/盖章 NBT (经 IChampion 探测), 故须排在 ChampionSystem 之后 (handler 挂 forgeBus, 对 register
        //     顺序不敏感, 列尾即可)。等级/经验走共享职业框架 capability (JobId.AGENT), 故须在 JobFrameworkSystem 之后。
        subsystems.add(new com.miningdim.job.agent.AgentSystem());
        // 24b. 酿酒师职业: 至少七天周期的制造职业 (酿酒台按等级 roll 品质酿基酒 + 酒窖箱陈酿年份 + 喝酒按
        //     S=年份×品质系数 获增益 + 闪耀档永久一条命增益)。年份时钟同读原版 level 时钟 (与潮汐 Tide mod 同源,
        //     零跨 mod 依赖)。等级/经验走共享职业框架 capability (JobId.BREWER), 故须在 JobFrameworkSystem 之后;
        //     事件订阅在其 register 内挂 forgeBus, 对 register 顺序不敏感, 列于职业簇末即可。
        subsystems.add(new com.miningdim.job.brewer.BrewerSystem());

        // 25. Web UI 服务端派发 (服务端权威, 无 MCEF): 填充 WebUiServerDispatcher 动作注册表 (system.echo 等),
        //     经 MiningNetwork.CHANNEL 收 C2S 意图并下发 S2C 响应/事件。须排在 NetworkSystem (第 2) 之后,
        //     依赖其 CHANNEL 已注册三包 (构造期注入即满足)。服务端安全, 不 classload 任何 MCEF。
        subsystems.add(new com.miningdim.webui.server.WebUiServerSubsystem());
        // 26. 跳蚤市场服务端 (服务端权威 P2P 交易通道, 纯服务端无 MCEF): SQLite 托管挂单/流水/离线待结 +
        //     成交手续费 sink + 铜铁日 cap + 6 个 market.* action 注册进派发器。须排在经济子系统 (第 11) 之后
        //     —— 买卖结算回调 EconomyServices 的 tryCharge/grant 原子接口, 经济门面须先注入; 须排在网络 (第 2) +
        //     Web UI 服务端派发 (第 25) 之后 —— 复用 WebUiServerDispatcher.register 挂 action, 派发器须先就绪。
        //     生命周期事件 (ServerStarting 开库建表 / ServerStopping 关库 / PlayerLoggedIn 结算离线待结) 在其
        //     register 内订阅 forgeBus, 对 register 顺序不敏感; 仅上述门面/派发器依赖约束此处列序。
        subsystems.add(new com.miningdim.market.MarketSubsystem());
        // 27. Web UI 客户端外壳 (MCEF 浏览器/Screen/路由): register 内全部客户端逻辑用 DistExecutor.safeRunWhenOn
        //     (Dist.CLIENT) 关进 client-only lambda, 故主类无条件加入列表即可 (服务端 GameTest 进程不 classload
        //     MCEF, 不崩)。同样须在 NetworkSystem 之后, 依赖 CHANNEL 已注册。
        subsystems.add(new com.miningdim.client.webui.WebUiClientSubsystem());
    }
}
