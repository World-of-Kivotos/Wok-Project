# WOK 综合玩法 MOD（技术 ID：miningdim）

WOK 服务器的综合玩法 MOD，运行于 Minecraft 1.20.1 + MinecraftForge 47.x + Java 17。第一阶段继续采用单一 MOD、单一 JAR、单一 `mods.toml`，在仓库内部按功能模块治理。

`miningdim` 是当前兼容性技术 ID，不代表仓库只有矿区功能。现有注册 ID、资源命名空间、存档键和网络协议在模块整理期间保持不变。

当前功能包括全服经验、矿区副本、职业体系、经济市场、电力、任务、附魔、精英战斗、开箱、社交及服务器支撑功能。全部文档的总索引见 [`docs/README.md`](docs/README.md)；仓库模块总表见 [`docs/modules/README.md`](docs/modules/README.md)，完整整理方案见 [`docs/WOK_Repository_Module_Plan.md`](docs/WOK_Repository_Module_Plan.md)。

`WOK` 与 `WOK步战` 是两个不同产品边界。本仓库根工程是 WOK 本体；WOK步战核心及其可独立安装的附属 MOD 不得混入本体模块。

## 目标平台 (锁定, 不得套用其他版本语法)

- Minecraft 1.20.1
- MinecraftForge 1.20.1-47.3.0 (取 47.x; `mods.toml` versionRange `[47,)`)
- Java 17 (LTS, `--release 17`)
- 构建: ForgeGradle 6.x + Gradle wrapper 8.1.1
- Mappings: parchment 2023.09.03-1.20.1
- 维度走数据包 (`data/miningdim/dimension/mining.json`)，运行时不增删维度。
- 自定义 BiomeSource 注册的是 `Codec`（非 MapCodec；MapCodec 化是 1.20.5+）；维度 generator 用原版 `minecraft:noise` + `miningdim:mining` noise settings，本仓库无自定义 ChunkGenerator（已按 F021/F032 下线，见 `worldgen/WorldgenSystem.java` 类注释）。

## 先读这三份

| 文档 | 用途 |
| --- | --- |
| [分支协作.md](分支协作.md) | 分支模型、中文 Conventional Commits 规范、合回 main 前的编译 + GameTest 硬门 |
| [docs/README.md](docs/README.md) | `docs/` 下全部设计规格、任务单、审查报告与手册的总索引 |
| [docs/modules/README.md](docs/modules/README.md) | 仓库模块总表与资源/依赖所有权（机器可读真源是 `docs/modules/module-registry.json`） |

## 构建步骤 (重要)

本机若装的是 Gradle 9 / Java 21，直接 `gradle build` 会失败：本工程的 toolchain 锁定 JDK 17，Gradle 锁定 8.1.1。

`gradle/wrapper/` 已随仓库入库（`gradle-wrapper.jar` + 锁定 8.1.1 的 `gradle-wrapper.properties`），克隆后直接用 `gradlew`/`gradlew.bat` 即可，**不要**运行 `gradle wrapper` 重新生成——该命令会用本机已装的 Gradle（很可能就是上面说的 Gradle 9）重写 `gradle-wrapper.properties`，抹掉文件里 `# 工程强制 pin Gradle 8.1.1 ...` 的 pin 说明以及 `networkTimeout`/`validateDistributionUrl` 配置。

1. 准备一个 JDK 17 供 Gradle toolchain 解析（Adoptium/Temurin 17 即可）。Gradle 会自动发现已注册的 JDK；若未发现，可显式指定：

   ```
   gradlew.bat build -Porg.gradle.java.installations.paths=<JDK17 安装目录>
   ```

   或设置 `JAVA_HOME` 指向 JDK 17 后直接：

   ```
   gradlew.bat build
   ```

   构建产物：`build/libs/miningdim-1.20.1-<mod_version>.jar`（版本号取 `gradle.properties` 的 `mod_version`，已 reobf）。**正式部署要用的是 jarJar 产出的 `miningdim-1.20.1-<mod_version>-all.jar`**——只有它内嵌了 `META-INF/jarjar/` 下的 sqlite-jdbc 驱动；把普通 jar 丢进正式服，跳蚤市场/开箱的 SQLite 存储会在开库时抛 `No suitable driver found`。

> 注意：源码用 `--release 17` 编译，禁用任何 Java 18+ 语法。Gradle daemon 已关闭（`org.gradle.daemon=false`），ForgeGradle 在并行配置下偶发 mapping 任务竞态，故 `org.gradle.parallel=false`。

## 常用任务

| 任务 | 说明 |
| --- | --- |
| `gradlew build` | 构建发布 jar（编译 + 资源处理 + reobf） |
| `gradlew runClient` | 启动带 mod 的客户端 |
| `gradlew runServer` | 启动带 mod 的专用服务端 |
| `gradlew runGameTestServer` | 运行 GameTest（命名空间 `miningdim`） |
| `gradlew runData` | 运行 Forge DataGen，重新生成 `src/generated/resources/`。该目录的生成结果**是入库交付物**，并由 `sourceSets.main.resources` 纳入主资源（build.gradle 的 `data` run 与 `srcDir 'src/generated/resources'`）；改过 datagen provider 或其依赖的模型/配方后，必须重跑本任务并把生成结果一并提交 |
| `gradlew verifyModuleRegistry` | 校验 `module-registry.json` 的模块 ID、目标依赖图、债务编号与循环依赖，并打印当期模块数与例外数 |
| `gradlew verifyModuleBoundaries` | 按 package/资源所有权扫描全部源码，拒绝未登记的跨模块引用，并核对债务表与登记表编号一致 |

## 架构总览

主类 `com.miningdim.MiningDim` 只持有一个 `List<Subsystem>`，在 mod 构造期逐个 `register(modBus, forgeBus)`。增删功能 = 改 `registerSubsystems()` 一行。

```
                       com.miningdim.core  (契约层, 不可变)
   MiningServices (服务定位器) · Subsystem · IInstanceManager · IMiningConfig
   IMiningNetwork · IResetService · ISpawnService · IInstanceResetListener
   InstanceState · RegionBox · VoxelOccupancy · Difficulty · GenState · SeedUtil
                                  ▲  (各子系统只依赖 core 契约 + MiningServices)
        ┌──────────┬──────────┬───┴───────┬──────────┬───────────┬──────────┐
     config     network    worldgen    instance     chunk       reset      ...
   (IMiningConfig)(IMiningNetwork)(—)(IInstanceManager)
```

以下是最初矿区子系统的装配基线；当前 WOK 本体的完整模块清单与所有权以 [`docs/modules/module-registry.json`](docs/modules/module-registry.json) 为准，渲染成表见 [`docs/modules/README.md`](docs/modules/README.md)。模块数量不在本文重复钉死，`gradlew verifyModuleRegistry` 会打印当期实数。List 顺序仍是门面注入顺序，见 `MiningDim` 类注释的硬约束。

| 顺序 | 子系统入口 | 职责 | 注入的 core 门面 |
| --- | --- | --- | --- |
| 1 | `config.ConfigSystem` | ForgeConfigSpec (SERVER+CLIENT) + 16.7 校验 | `IMiningConfig` |
| 2 | `network.NetworkSystem` | SimpleChannel + 逐包 registerMessage | `IMiningNetwork` |
| 3 | `worldgen.WorldgenSystem` | 注册 `MiningBiomeSource` 的 BiomeSource Codec（维度 generator 走 `minecraft:noise`） | —（无 core 门面） |
| 4 | `instance.InstanceSystem` | 实例后端：region 分配/引用计数/排队背压/GC + SavedData + 区块 force-load | `IInstanceManager` |
| 5 | `chunk.ChunkSystem` | 玩家为心的滑动 ticket 窗口、空置 TTL 释放 | —（组内门面 `ChunkServices`） |
| 6 | `reset.ResetSystem` | 单实例 region 级重置（分帧状态机）/撤离 | `IResetService` |
| 7 | `spawn.SpawnSystem` | 安全出生点池/谓词/兜底平台 | `ISpawnService` |
| 8 | `ore.OreSystem` | 仍在 `MiningDim` 装配，但其核心用途（供已下线的 `MiningChunkGenerator` 查铺矿表）已随 F021/F032 消失；现行矿石铺放走 worldgen 的 `placed_feature`，`OreScanService` 也已改扫真实世界而不再读它的体素表。类注释尚未改口 | —（无 core 门面） |
| 9 | `trap.TrapSystem` | 静态陷阱布点查表 + 动态陷阱 tick 引擎 | —（无 core 门面） |
| 10 | `pressure.PressureSystem` | 动态压力评估/身后刷怪/HUD danger 下发 | —（经 `IMiningNetwork` 推 HUD） |
| 11 | `economy.EconomySystem` | 反滥用闸门（重置冷却/矿物软上限/AFK/死亡惩罚） | —（事件型） |
| 12 | `rules.RulesSystem` | 放置规则：矿山维度内放置白名单（R7） | —（事件型） |
| 13 | `error.ErrorSystem` | 启动期维度自检 + 边界兜底文案 | —（事件型） |
| 14 | `entry.EntrySystem` | 玩家 Capability + `/mining` 命令树 + 进入/离开/登录恢复编排 | —（玩家 Capability 经 `entry.MiningCapabilities` 对外） |

目标架构要求跨模块协作只经公开门面或 seam。历史反向引用逐条带 `D###` 编号登记在登记表的 `boundaryExceptions`，渲染结果与清偿进度见 [`docs/modules/DEPENDENCY_DEBT.md`](docs/modules/DEPENDENCY_DEBT.md)；活跃条数、已清偿条数和每条例外的触发文件清单（`evidence`）都由 `gradlew verifyModuleBoundaries` 逐条核对，该任务同时拒绝新增未登记的跨模块引用，故本文不再抄写会过期的条数。全服经验统一经过 `progression.IExperienceService` 的“轨道 + 来源”路由，现有职业存档由兼容适配器继续承载。维度 generator 走原版 `minecraft:noise` 按需生成（见 `data/miningdim/dimension/mining.json`），worldgen 子系统只负责把 `MiningBiomeSource.CODEC` 注册进 `Registries.BIOME_SOURCE`（注册逻辑集中在 `registry.ModRegistration` 作为单一真源）；自定义 ChunkGenerator 与离线体素生成管线已按 F021/F032 下线，不再有离线体素 seam（见 `worldgen/WorldgenSystem.java` 与 `ore/OreSystem.java` 的类注释）。

## 已知架构裁决 (阶段2 集成)

并行开发期出现了两处等价但相互冲突的实现，集成时按设计文档 DECIDED 选定唯一权威，落败的一套或已删除、或保留在仓库但不接入主类：

1. **玩家 Capability 与进入/离开/登录恢复**：以 `entry` 子系统为唯一权威（`EntrySystem` + `MiningCapabilities`，实现了 14.2 完整防虚空进入链路与 14.6 登录恢复，`reset` 子系统亦依赖其能力）。`persistence` 包曾有一套等价玩家 Capability（`PlayerMiningCapability`/`PlayerMiningEvents`），同时挂载会重复 attach 能力并重复触发 `onPlayerLeave`/登录恢复（双重传送、双重引用计数），已整体删除——该包现在只剩 `MiningSavedData.java` 一个文件（实例注册表持久层），继续作为 InstanceManager 的后端。`InstanceSystem` 只保留实例后端，不注册任何玩家 Capability。
2. **`/mining` 命令树**：以 `entry.MiningCommands` 为唯一权威（匹配 14.1 DECIDED：`enter <difficulty> [reseed]` / `leave` / `reset <id> [reseed]` / `reset all` / `info [id]`，且 `enter` 走 `EntryGateway` 真实传送）。`command.CommandSystem` 是另一套 `/mining`（其 `enter` 仅 allocate 不传送），不接入主类以避免 Brigadier 双根冲突。

> 后续若要把 `command` 包的运维扩展（`list`/`tp`/`kick`/权限分级）并入唯一命令树，应迁移到 `entry.MiningCommands` 之下，而非同时注册两个根。

## 已知 PENDING 待办

- **数值初值校验**：`core.MiningConstants` 的 region 几何（`REGION_SIZE_*`/`REGION_GAP`/`REGION_STRIDE_*`）与难度子盒 worldY 边界、`config.MiningServerConfig` 的全表默认值，均为设计文档标注的 PENDING待校验 初值，需实测平衡后定稿。维度 JSON 的 `generator.settings` 与 `MiningConstants` 须保持一致。
- **`InstanceState` 持久化字段缺口**：`resetGeneration`（NEW_SEED 派生第三维）已由 `persistence.MiningSavedData` 以**全局计数器**形式落盘（NBT 键 `resetGeneration`，save/load 双向都写全）；缺口是 12.5 实例字段表未收 per-instance 粒度，NEW_SEED 派生目前全实例共用同一代数。`economy` 的实例级重置计数（`lastResetTick`/`resetCountToday`）以 instanceId 侧存。两者都待 `InstanceState` 扩展持久字段后迁回。
- **玩家级持久数据归属**：`economy.PlayerAbuseState`（当日矿物计数/各冷却）与 `pressure` 的运行态当前各自内存自持。设计文档（18.3/D5）标注应存玩家 Capability。待统一到 `entry.IMiningPlayerData` 或 core 出现玩家数据门面后迁入持久层。
- **跨子系统 danger 接线**：`pressure` 经 `IMiningNetwork` 推 HUD danger。其中 `trap` 的 danger 门控**已接完**——`PressureSystem.register` 末尾经 `TrapSystem.setDangerSource(...)` 注入 danger 读取适配器，`PressureGameTests` 以变异验证锁死（删掉该注入，引擎退回 0f stub，用例必挂）。**剩余缺口**：`pressure` 的 `oreTerm` 仍传 0（见 `pressure/MobPressureSystem.java` 与 `pressure/Danger.java` 的注释），待矿物子系统提供"局部富矿度"读取门面后补一行适配器。
- **退役 region 磁盘回收（部分完成）**：重置走 D3 滑动方案——`ResetJob` 的 REGEN 阶段调 `IInstanceManager.slideRegion` 把实例整块滑到一块从未生成过的新坐标，不再有"按新 bitset 重填区块"这回事（离线生成已下线，维度按 `minecraft:noise` 按需生成）。被遗弃的旧坐标区块由 `reset.RetiredRegionGc` 逐区块 `ChunkStorage.write(pos, null)` 回收（每 100 tick 清一批，`RetiredRegionGcGameTests` 实测锁死这条路径）。**剩余缺口**：只清地形区块 `world/region/`，实体区块 `world/entities/` 未清（其存储句柄挂在 `ServerLevel.entityManager` 私有字段上）；`instance.InstanceManager.destroyInstance` 走的仍是纯逻辑回收路径（释放强加载 + free region + 标 RECYCLED），未接入该 GC。
- **组队**：私有实例 `resolveOwnerKey` 现回退 `player.uuid`，组队 `teamId` 解析待组队子系统接入（14.5）。
- **`IMiningNetwork.openGui` 门面悬空（待裁决，非待实现）**：`menu.ModMenus` 已是跨职业共享的 MenuType 注册中心，酒窖/酿造台/调味台/生产台/军械三台（装配台、冲压机、军火台）/塔罗制卡台与闪耀卡包/婚姻共享背包/发电机/预热机/空分/提纯/储能/低温控制器共 16 个方块实体或菜单类已实现 `MenuProvider`，并各自直接调用 `NetworkHooks.openScreen`；全仓对 `network.MiningNetwork.openGui` 的调用点为 0，该方法仍按 C9 抛 `UnsupportedOperationException`。待决：删掉这条 core 门面，还是把它改造成统一转发入口、收编现有的 `NetworkHooks.openScreen` 调用点。

## 模块化约定

- 单 `mods.toml`、单 JAR。Java 所有权按 `module-registry.json` 的 `javaPackages`（精确包）与 `javaPackagePrefixes`（最长前缀）判定，资源所有权按 `resourcePaths` 与 `resourceNamePrefixes` 判定；两边都落不到的文件会被 `verifyModuleBoundaries` 判为无主并失败，共享文件必须显式登记在 `sharedResources`。
- 所有玩法经验必须提交给 `IExperienceService`；模块声明稳定来源 ID，不得自行修改其他模块的经验存档或衰减计数。
- 目标依赖必须在登记表中声明且保持无环；历史反向引用只能使用已有 `boundaryExceptions`，严禁新增未登记实现依赖。
- 每个子系统实现 `com.miningdim.core.Subsystem`，在 `register(modBus, forgeBus)` 内完成自注册并把服务实例注入 `MiningServices`。
- 世界写操作（setBlock/传送/刷怪/重置）必须在服务端主线程（`server.execute` 或 ServerTickEvent）；纯计算（体素生成/BFS）在独立工作线程。

矿区规格见 [`docs/MiningDimension_Mod_DesignSpec.md`](docs/MiningDimension_Mod_DesignSpec.md)；WOK 全仓模块、资源和依赖治理见 [`docs/modules/README.md`](docs/modules/README.md)；其余设计规格、审查报告与手册统一从 [`docs/README.md`](docs/README.md) 进；已交付的任务单与冻结的审查快照在 [`docs/archive/`](docs/archive/README.md)。

## 许可

Copyright (c) 2026 ShinoyukiMiyako / World of Kivotos，保留一切权利。

本仓库是**专有软件，不是开源项目**。源码公开可见不构成任何许可授予：未经著作权人书面授权，
禁止复制、克隆、修改、再分发、逆向、再实现，以及用于 AI 训练或语料检索。完整条款见
[LICENSE](LICENSE)，其中另有两项明示的有限例外——玩家安装并运行官方 jar 连入官方服务器，
以及授权协作者为向本仓库贡献而进行的本地开发。

本项目依赖或内嵌的第三方组件不在上述许可范围内，各自受其原始许可约束，清单见
[THIRD-PARTY-NOTICES.md](THIRD-PARTY-NOTICES.md)。
