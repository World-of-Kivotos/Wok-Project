# Mining Dimension System (miningdim)

Minecraft 1.20.1 + MinecraftForge 47.x + Java 17 的矿山副本维度 mod。单一 mod、单一 jar、单一 `mods.toml`，内部强模块化。

可重复刷新的随机矿洞副本维度：单一独立维度内按 region 网格切分多实例，离线确定性生成，全连通矿洞，三难度垂直分层。

## 目标平台 (锁定, 不得套用其他版本语法)

- Minecraft 1.20.1
- MinecraftForge 1.20.1-47.3.0 (取 47.x; `mods.toml` versionRange `[47,)`)
- Java 17 (LTS, `--release 17`)
- 构建: ForgeGradle 6.x + Gradle wrapper 8.1.1
- Mappings: parchment 2023.09.03-1.20.1
- 维度走数据包 (`data/miningdim/dimension/mining.json`)，运行时不增删维度。
- 自定义 ChunkGenerator/BiomeSource 注册的是 `Codec`（非 MapCodec；MapCodec 化是 1.20.5+）。

## 构建步骤 (重要)

本机若装的是 Gradle 9 / Java 21，直接 `gradle build` 会失败：本工程的 toolchain 锁定 JDK 17，Gradle 锁定 8.1.1。

1. `gradle-wrapper.jar` 是二进制文件，无法由文本生成。首次克隆后，用本机已装的任意 Gradle 仅执行这一步生成 wrapper（之后一律用 wrapper）：

   ```
   gradle wrapper --gradle-version 8.1.1
   ```

   该命令补齐 `gradle/wrapper/gradle-wrapper.jar`，使 `gradle-wrapper.properties` 中 pin 的 8.1.1 生效。

2. 准备一个 JDK 17 供 Gradle toolchain 解析（Adoptium/Temurin 17 即可）。Gradle 会自动发现已注册的 JDK；若未发现，可显式指定：

   ```
   gradlew.bat build -Porg.gradle.java.installations.paths=<JDK17 安装目录>
   ```

   或设置 `JAVA_HOME` 指向 JDK 17 后直接：

   ```
   gradlew.bat build
   ```

   构建产物：`build/libs/miningdim-1.20.1-1.0.0.jar`（已 reobf）。

> 注意：源码用 `--release 17` 编译，禁用任何 Java 18+ 语法。Gradle daemon 已关闭（`org.gradle.daemon=false`），ForgeGradle 在并行配置下偶发 mapping 任务竞态，故 `org.gradle.parallel=false`。

## 常用任务

| 任务 | 说明 |
| --- | --- |
| `gradlew build` | 构建发布 jar（编译 + 资源处理 + reobf） |
| `gradlew runClient` | 启动带 mod 的客户端 |
| `gradlew runServer` | 启动带 mod 的专用服务端 |
| `gradlew runGameTestServer` | 运行 GameTest（命名空间 `miningdim`） |

## 架构总览

主类 `com.miningdim.MiningDim` 只持有一个 `List<Subsystem>`，在 mod 构造期逐个 `register(modBus, forgeBus)`。增删功能 = 改 `registerSubsystems()` 一行。

```
                       com.miningdim.core  (契约层, 不可变)
   MiningServices (服务定位器) · Subsystem · IInstanceManager · IMiningConfig
   IMiningNetwork · IOfflineGenerator · IResetService · ISpawnService
   InstanceState · RegionBox · VoxelOccupancy · Difficulty · GenState · SeedUtil
                                  ▲  (各子系统只依赖 core 契约 + MiningServices)
        ┌──────────┬──────────┬───┴───────┬──────────┬───────────┬──────────┐
     config     network    worldgen    instance     chunk       reset      ...
   (IMiningConfig)(IMiningNetwork)(IOfflineGenerator)(IInstanceManager)
```

子系统装配顺序（List 顺序 = 门面注入顺序，见 `MiningDim` 类注释的硬约束）：

| 顺序 | 子系统入口 | 职责 | 注入的 core 门面 |
| --- | --- | --- | --- |
| 1 | `config.ConfigSystem` | ForgeConfigSpec (SERVER+CLIENT) + 16.7 校验 | `IMiningConfig` |
| 2 | `network.NetworkSystem` | SimpleChannel + 逐包 registerMessage | `IMiningNetwork` |
| 3 | `worldgen.WorldgenSystem` | 离线洞穴生成 + 注册两个 Codec | `IOfflineGenerator` |
| 4 | `instance.InstanceSystem` | 实例后端：region 分配/引用计数/排队背压/GC + SavedData + 区块 force-load | `IInstanceManager` |
| 5 | `chunk.ChunkSystem` | 玩家为心的滑动 ticket 窗口、空置 TTL 释放 | —（组内门面 `ChunkServices`） |
| 6 | `reset.ResetSystem` | 单实例 region 级重置（分帧状态机）/撤离 | `IResetService` |
| 7 | `spawn.SpawnSystem` | 安全出生点池/谓词/兜底平台 | `ISpawnService` |
| 8 | `ore.OreSystem` | 离线铺矿 + 查表（静态 `get()`） | —（无 core 门面） |
| 9 | `trap.TrapSystem` | 静态陷阱布点查表 + 动态陷阱 tick 引擎 | —（无 core 门面） |
| 10 | `pressure.PressureSystem` | 动态压力评估/身后刷怪/HUD danger 下发 | —（经 `IMiningNetwork` 推 HUD） |
| 11 | `economy.EconomySystem` | 反滥用闸门（重置冷却/矿物软上限/AFK/死亡惩罚） | —（事件型） |
| 12 | `error.ErrorSystem` | 启动期维度自检 + 边界兜底文案 | —（事件型） |
| 13 | `entry.EntrySystem` | 玩家 Capability + `/mining` 命令树 + 进入/离开/登录恢复编排 | —（玩家 Capability 经 `entry.MiningCapabilities` 对外） |

跨子系统协作只经 `MiningServices` 取门面，从不 import 对方实现类。worldgen 的 `MiningChunkGenerator` 经 `worldgen.MiningVoxelLookup` 静态 seam 取冻结体素：集成层（`instance.InstanceSystem.onServerStarted`）把离线调度器的 `voxelsOf` 接进该 seam（单向依赖，无环）。

## 已知架构裁决 (阶段2 集成)

并行开发期出现了两处等价但相互冲突的实现，集成时按设计文档 DECIDED 选定唯一权威，另一套保留在仓库但不接入主类：

1. **玩家 Capability 与进入/离开/登录恢复**：以 `entry` 子系统为唯一权威（`EntrySystem` + `MiningCapabilities`，实现了 14.2 完整防虚空进入链路与 14.6 登录恢复，`reset` 子系统亦依赖其能力）。`persistence` 包另有一套等价玩家 Capability（`PlayerMiningCapability`/`PlayerMiningEvents`），若同时挂载会重复 attach 能力并重复触发 `onPlayerLeave`/登录恢复（双重传送、双重引用计数）。故 `InstanceSystem` 只保留实例后端，不再注册该套玩家 Capability。`persistence.MiningSavedData`（实例注册表持久层）仍是 InstanceManager 的后端，正常使用。
2. **`/mining` 命令树**：以 `entry.MiningCommands` 为唯一权威（匹配 14.1 DECIDED：`enter <difficulty> [reseed]` / `leave` / `reset <id> [reseed]` / `reset all` / `info [id]`，且 `enter` 走 `EntryGateway` 真实传送）。`command.CommandSystem` 是另一套 `/mining`（其 `enter` 仅 allocate 不传送），不接入主类以避免 Brigadier 双根冲突。

> 后续若要把 `command` 包的运维扩展（`list`/`tp`/`kick`/权限分级）并入唯一命令树，应迁移到 `entry.MiningCommands` 之下，而非同时注册两个根。

## 已知 PENDING 待办

- **数值初值校验**：`core.MiningConstants` 的 region 几何（`REGION_SIZE_*`/`REGION_GAP`/`REGION_STRIDE_*`）与难度子盒 worldY 边界、`config.MiningServerConfig` 的全表默认值，均为设计文档标注的 PENDING待校验 初值，需实测平衡后定稿。维度 JSON 的 `generator.settings` 与 `MiningConstants` 须保持一致。
- **`InstanceState` 持久化字段缺口**：`reset` 的 `resetGeneration`（NEW_SEED 派生第三维）当前进程内 `Map<Long,Integer>` 跟踪，未随实例落盘；`economy` 的实例级重置计数（`lastResetTick`/`resetCountToday`）以 instanceId 侧存。待 `InstanceState` 扩展持久字段后迁回。
- **玩家级持久数据归属**：`economy.PlayerAbuseState`（当日矿物计数/各冷却）与 `pressure` 的运行态当前各自内存自持。设计文档（18.3/D5）标注应存玩家 Capability。待统一到 `entry.IMiningPlayerData` 或 core 出现玩家数据门面后迁入持久层。
- **跨子系统 danger 接线**：`pressure` 经 `IMiningNetwork` 推 HUD danger，但 (a) `trap` 的 danger 门控需压力子系统经 `TrapSystem.setDangerSource(...)` 注入读取；(b) `pressure` 的 `oreTerm` 暂传 0，待矿物子系统提供"局部富矿度"读取门面。二者均为已声明的接线缺口（非空壳），需在对应子系统稳定公开注入入口后补一行适配器。
- **物理区块删除**：`instance.InstanceManager.destroyInstance` 当前仅释放强加载 + 逻辑回收，物理区块文件删除待 `reset` 流程接入；`reset` 的区块重建依赖 `MiningChunkGenerator` 在下次区块加载时按新 bitset 重填。
- **组队**：私有实例 `resolveOwnerKey` 现回退 `player.uuid`，组队 `teamId` 解析待组队子系统接入（14.5）。
- **GUI**：`network.MiningNetwork.openGui` 因本期无 `MenuProvider` 按 C9 抛 `UnsupportedOperationException` 明确暴露缺失能力，待 GUI 子系统提供菜单后接入。

## 模块化约定

- 单 `mods.toml`，单 jar。每个子系统是自包含 package，仅依赖 `com.miningdim.core` 契约。
- 跨子系统交互只经 `core` 门面接口 + `MiningServices` 服务定位器，严禁子系统间硬编码 import 对方实现类，禁止循环依赖。
- 每个子系统实现 `com.miningdim.core.Subsystem`，在 `register(modBus, forgeBus)` 内完成自注册并把服务实例注入 `MiningServices`。
- 世界写操作（setBlock/传送/刷怪/重置）必须在服务端主线程（`server.execute` 或 ServerTickEvent）；纯计算（体素生成/BFS）在独立工作线程。

完整规格见 `docs/MiningDimension_Mod_DesignSpec.md`。

## 许可

Copyright (c) 2026 ShinoyukiMiyako / World of Kivotos，保留一切权利。

本仓库是**专有软件，不是开源项目**。源码公开可见不构成任何许可授予：未经著作权人书面授权，
禁止复制、克隆、修改、再分发、逆向、再实现，以及用于 AI 训练或语料检索。完整条款见
[LICENSE](LICENSE)，其中另有两项明示的有限例外——玩家安装并运行官方 jar 连入官方服务器，
以及授权协作者为向本仓库贡献而进行的本地开发。

本项目依赖或内嵌的第三方组件不在上述许可范围内，各自受其原始许可约束，清单见
[THIRD-PARTY-NOTICES.md](THIRD-PARTY-NOTICES.md)。
