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
    }
}
