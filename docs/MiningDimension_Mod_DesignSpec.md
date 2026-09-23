# Minecraft Forge 矿山系统设计文档（Mining Dimension System）

## 文档元信息

- 目标平台: Minecraft 1.20.1 + Forge 47.x + Java 17。所有 API 名称以此版本为准, 不得套用其他版本语法。
- 用途: 本 mod 实现阶段的唯一架构、数值与机制参考; 所有常量以本文档为准, 不得凭记忆改写。
- 状态图例: DECIDED 已定稿 / PENDING 待校验 / REJECTED 已否决(附理由) / SUPERSEDED 已被后续方案取代(正文保留作演进归档) / TODO 实现期补全。
- 最后核对日期: 2026-09-20。核对基线: 分支 docs/audit-2026-09(基线 main bd0c4588)。归属模块键: `wok-mining`。
- 数值真源: 凡本文档与 `src/main/java/com/miningdim/core/MiningConstants.java`、`src/main/java/com/miningdim/config/MiningServerConfig.java`、`src/main/resources/data/miningdim/` 下的 JSON 冲突, 一律以代码/数据包为准, 并回头订正本文档。
- 编码前阻塞项(已消化, SUPERSEDED): 原要求先完成第二十二章的三个 Spike(region 分区 PoC、离线生成与连通性 PoC、重置异步化 PoC)。其中 SP2 随离线管线下线一并作废, SP1/SP3 的结论已被 R1 固定区域与 D3 滑动重置取代, 见下方架构变更声明与 22.1。
- 本版修订: 针对设计评审反馈, 补全目标平台与版本约束、注册架构、维度与生成模型、矿物数值表、实例生命周期与持久化、网络协议、配置、命令权限、反滥用经济、性能容量、错误处理、测试策略、实现路线图共 11 类工程契约。

### 架构变更声明(必读)

本文档是长周期规格, 第六、七、十二、十三章的原始方案已被实现期的三次架构裁决整体取代。阅读任何章节前先看这里, 避免把已判废的设计当现行规格:

| 变更 | 原方案(本文档旧正文) | 现行实现 | 代码锚点 |
| --- | --- | --- | --- |
| F021/F032 生成管线 | 自定义 `MiningChunkGenerator` + 离线体素三阶段(Skeleton/NoiseCarving/ConnectivityFix)+ `OfflineCaveGenerator` / `GenerationScheduler` | 原版 `minecraft:noise` + 自定义 `noise_settings`(`final_density=1` 全实心)+ 原版 carver 挖空 + 数据包 `placed_feature` 铺矿布陷阱; 上述三个类在仓库中已不存在 | `worldgen/WorldgenSystem.java` 类注释;`data/miningdim/dimension/mining.json` |
| R1/R2 难度模型 | 单 region 内按 `worldY` 垂直堆叠三个难度子盒 | 三块固定 region 沿 X 轴并排, 一整块 region 就是一个难度; 难度由所在 region 决定, `worldY` 不参与 | `core/MiningConstants.java` 类注释与 `EASY/MEDIUM/HARD_CELL_X`;`worldgen/MiningBiomeSource.java` |
| D3 滑动重置 | 区块文件级删除 + 限速重生成 | `slideRegion` 把整块 region 滑到一块从未生成过的新世界坐标(世界 X 游标单向推进), 旧坐标区块交由 `RetiredRegionGc` 异步节流回收 | `reset/ResetJob.java` 类注释;`reset/RetiredRegionGc.java` |

维度高度同样被订正: `REGION_HEIGHT` 早期为 384, 现已收到 192, 全文旧稿按 384 推导的一切 Y 区间、体素数与内存估算随之作废。现值 `height=192`、`min_y=-64`、`worldY -64..127`(见 4.3)。

第七章 7.1 起的离线管线正文、第十二章 12.2/12.3/12.6 的动态分配算法均标 SUPERSEDED 保留, 只作设计演进归档, 不得当作实现参考。

---

## 一、目标平台与版本约束(DECIDED)

本章锁定整套实现的运行平台与工具链版本。后续所有章节出现的 Forge / Minecraft API、注册时机、数据包格式、Codec 类型,均以本章锁定的版本为唯一基准;凡与本章冲突的写法一律视为缺陷。版本锁定状态为 DECIDED,非经全文评审不得变更。

### 1.1 平台与工具链锁定矩阵

| 维度 | 锁定值 | 状态 | 不得套用的版本陷阱 |
| --- | --- | --- | --- |
| Minecraft | 1.20.1 | DECIDED | 不得套用 1.20.4+ 的 custom payload 网络 API;不得套用 1.20.5+ 的 `MapCodec` 注册签名 |
| 模组加载器 | Forge 47.x(MinecraftForge,非 NeoForge) | DECIDED | 不得使用 NeoForge 专属包路径 `net.neoforged.*`;不得使用 NeoForge 的 `DeferredRegister` 重载差异 |
| Java | 17(LTS, `--release 17`) | DECIDED | 不得用 Java 21 的 record pattern / 虚拟线程语法;工作线程用平台线程池 |
| 构建系统 | ForgeGradle 6.x + Gradle 8.1.1 | DECIDED | 不得套用 FG5 的 `minecraft { mappings channel: ... }` 旧 DSL |
| Mappings | official + parchment(`org.parchmentmc.data:parchment-1.20.1`) | DECIDED | parchment 仅提供参数名/注释,类名与官方一致;不得引用 MCP/Yarn 名 |
| modid | `miningdim` | DECIDED | 全 mod 资源前缀统一;`ResourceLocation` namespace 恒为 `miningdim` |
| 维度 ResourceKey | `miningdim:mining`(`ResourceKey<Level>`) | DECIDED | 见第三、四章,单一静态维度 |

构建脚本关键约束(`build.gradle`):

```text
mappings channel: 'parchment', version: '2023.09.03-1.20.1'   // parchment for 1.20.1
minecraft 'net.minecraftforge:forge:1.20.1-47.3.0'            // 47.x, 取 .toml 兼容下限
java { toolchain { languageVersion = JavaLanguageVersion.of(17) } }
```

注:Forge 具体补丁号(47.3.0)为建议初值,标 PENDING待校验;只要落在 47.x 区间且 `>= [47,)` 即满足约束,锁定的是主版本号 47 而非补丁号。

### 1.2 mods.toml 关键字段(DECIDED)

`src/main/resources/META-INF/mods.toml` 必须包含且仅以下列约束声明加载语义。版本范围语法遵循 Maven Version Range,Forge 用 `[lo,hi)` 半开区间。

```toml
modLoader = "javafml"
loaderVersion = "[47,)"          # FML 主版本下限,与 Forge 47.x 对齐
license = "All Rights Reserved"

[[mods]]
modId = "miningdim"
version = "${file.jarVersion}"
displayName = "Mining Dimension System"

[[dependencies.miningdim]]
modId = "forge"
mandatory = true
versionRange = "[47,)"           # 接受任意 47.x 及以上 Forge
ordering = "NONE"
side = "BOTH"

[[dependencies.miningdim]]
modId = "minecraft"
mandatory = true
versionRange = "[1.20.1,1.20.2)" # 仅 1.20.1,排斥 1.20.2+ 误装
ordering = "NONE"
side = "BOTH"
```

字段约束说明:

| 字段 | 锁定值 | 约束理由 |
| --- | --- | --- |
| `modLoader` | `javafml` | 纯 Java mod,非 Kotlin/脚本加载器 |
| `loaderVersion` | `[47,)` | FML 主版本下限,与 MC 1.20.1 对应的 Forge 主版本一致 |
| Minecraft `versionRange` | `[1.20.1,1.20.2)` | 上界开区间,硬性拒绝在 1.20.2+ 误加载导致维度数据包格式不兼容 |
| Forge `versionRange` | `[47,)` | 不锁补丁号,避免每次 Forge 小版本升级即报不兼容 |
| `side` | `BOTH` | mod 两端都需安装(自定义 `BiomeSource` 的 Codec 客户端需用于维度同步反序列化) |

`side = BOTH` 是硬约束:自定义 `BiomeSource`(`MiningBiomeSource`)的 `Codec` 在客户端登录阶段参与维度同步反序列化,若客户端缺失本 mod 将直接断连。这也是 `core.RegionLayout` 必须放在 core 且不依赖任何服务端单例的原因——客户端反序列化 Codec 时会走同一条 `getNoiseBiome` 路径。此点与第三章"端职责划分"表一致。自定义 `ChunkGenerator` 已随 F021/F032 下线, 生成器侧不再有客户端反序列化需求。

### 1.3 依赖与兼容性风险(DECIDED 风险登记)

| 风险项 | 严重度 | 冲突面 | 缓解策略 |
| --- | --- | --- | --- |
| 其他增维度 mod | Minor | 各 mod 维度走独立 datapack `level_stem`,`ResourceKey` 命名空间隔离 | `miningdim:mining` 命名空间唯一,天然无冲突;不抢占 `minecraft:*` 维度 |
| 其他自定义 `BiomeSource` mod | Minor | 共享 `BuiltInRegistries.BIOME_SOURCE` 注册表,`ResourceLocation` 撞名才冲突 | 本 mod 唯一注册项 `miningdim:mining_biome_source` 已 modid 前缀化;只增不改,不替换他人条目。`CHUNK_GENERATOR` 注册表本 mod 零条目(F021/F032 后不再自定义生成器) |
| 区块生成 / 地形 mod(如 Terralith、TerraForged) | Minor | 仅作用于其声明的维度,本 mod 维度用专属 `noise_settings` + 专属 `BiomeSource`,互不接管 | 本 mod 的 `miningdim:mining` noise settings 不挂 `minecraft:overworld`,二者维度不重叠 |
| 刷怪管理 mod(如 In Control、刷怪上限调整) | Major | `MobPressureSystem` 的刷怪与第三方刷怪规则叠加,可能突破我方密度上限 | 本 mod 刷怪走 `finalizeSpawn` 显式生成而非依赖自然刷怪规则,并自带每实例硬上限计数;见第十章 |
| 经济 / 掉落 mod | Minor | `OreGenerator` 产出的方块是原版矿石,第三方掉落改动会影响收益曲线 | 矿物权重数值在 `ConfigManager` 暴露,允许服主与第三方平衡;不硬编码掉落 |
| 维度运行时增删类 mod(KubeJS dimension、动态维度) | Major | 若第三方在运行时操作 `MinecraftServer.levels`,可能与我方 region 网格假设冲突 | 我方 REJECTED 运行时增删维度(见第三、四章),仅使用启动期 datapack 维度,不受其影响;但与此类 mod 共存时不保证其行为 |
| Sodium/Rubidium 等渲染 mod | Minor | 纯客户端渲染,不触碰维度逻辑 | 无生成逻辑交互,兼容 |

依赖声明纪律:除 Forge + Minecraft 外,本 mod 默认零强制第三方依赖。任何可选集成(如对 JEI 暴露矿物权重)必须用 `mandatory = false` 软依赖声明,且代码侧用 `ModList.get().isLoaded("jei")` 守卫,严禁因可选 mod 缺失而抛异常。

### 1.4 版本敏感章节声明

下列后续章节包含直接依赖 1.20.1 / Forge 47.x 具体 API 的实现细节,任何 MC 大版本迁移都必须重新核验这些章节,标记为版本敏感(Version-Sensitive):

| 章节 | 版本敏感点 | 1.20.1 关键事实(迁移时重点核验) |
| --- | --- | --- |
| 三、总体架构与模块接口 | 注册时机、单例生命周期 | `DeferredRegister` 在 `FMLConstructModEvent`;`BiomeSource` Codec 用 `RegisterEvent` 注册到 `BuiltInRegistries.BIOME_SOURCE` |
| 四、维度与数据包注册 | 维度建模方式 | 维度走 datapack:`dimension_type/*.json` + `dimension/*.json`(`level_stem`)+ `worldgen/noise_settings/*.json`;运行时增删维度 REJECTED |
| 七、矿洞生成系统 / 区块生成 | `noise_settings` JSON 结构与 `surface_rule` 语法 | 1.20.1 的 `noise_router`/`final_density`/`vertical_gradient`/`minecraft:biome` 条件写法;`BiomeSource` 用 `Codec`(非 1.20.5 的 `MapCodec`) |
| 十、动态刷怪 | 刷怪 API | `Mob.finalizeSpawn`、`MobSpawnType.SPAWNER`(与第十章 10.5 一致);`ServerLevel.addFreshEntityWithPassengers` |
| 持久化层 | SavedData / Capability API | `SavedData` + `DimensionDataStorage`;`AttachCapabilitiesEvent<Entity>` + `ICapabilitySerializable` + `PlayerEvent.Clone`(1.20.1 仍为 Capability,非 1.20.5 attachment) |
| 网络层(NetworkHandler) | 网络信道 API | `NetworkRegistry.newSimpleChannel` + `SimpleChannel.registerMessage`;custom payload(1.20.4+)不适用 |
| 配置层 | 配置 API | `ForgeConfigSpec` + `ModConfigEvent`;`registerConfig(ModConfig.Type.SERVER, ...)` |
| 命令层 | 命令 API | Brigadier `CommandDispatcher`;`RegisterCommandsEvent` |
| 集成测试 | GameTest API | Forge `GameTest` + `@GameTestHolder` / `RegisterGameTestsEvent` |

迁移到 1.20.4 需要重写网络层(custom payload);迁移到 1.20.5/1.21 需要额外重写 Codec 注册(`MapCodec`)与持久化层(data attachment 取代部分 Capability)。这是已知的、可预期的版本债务,在此显式登记。

---

## 二、设计目标与核心约束(DECIDED)

本章把原始文档第 1 节的玩法意图提炼为可验收的目标条目,并补充一组贯穿全文、不可协商的硬约束。所有后续模块设计与数值选型都必须落在这些约束之内;任何模块若违反硬约束,视为设计缺陷,优先级高于功能完整度。

### 2.1 核心玩法目标(源自原文第 1 节)

| 编号 | 目标 | 玩法意图(保留原文) | 对应章节 |
| --- | --- | --- | --- |
| G1 | 可重复刷新的随机矿洞副本 | 服务器内可反复进入、可整体重置的实例化矿区 | 三、四、十三(重置系统) |
| G2 | 随机生成矿洞结构 | 随机但可控的洞穴拓扑 | 七 7.0(原版 carver 挖空, 由 seed 决定) |
| G3 | 矿道完全连通 | 玩家从出生点可达全部可行走空间,无孤岛死锁 | 七 7.0(已降级: 原版 carver 不提供全局连通保证, 详见 7.0.5) |
| G4 | 多难度分层矿区 | Easy / Medium / Hard 三区,而非靠 Y 轴分层 | 六(三 region 并排, 一区一难度) |
| G5 | 矿物与难度挂钩 | 难度越高,高价值矿物权重越高 | 八(OreGenerator) |
| G6 | 高难度带陷阱与动态压力 | 静态陷阱 + 随停留时间上升的动态危险 | 九、十(TrapSystem / MobPressure) |
| G7 | 进入后随机安全出生 | 出生点保证安全(头顶净空/脚下固体/无岩浆/非陷阱区);"∈ 主连通分量"一项已随 D4 作废 | 十一(SpawnSystem) |
| G8 | 支持整体重置 | 单实例 region 级重置,不影响其他实例 | 十三(重置系统,D1) |
| G9 | 推荐独立维度 | 单一专属维度 `miningdim:mining`,内部网格切 region | 三、四(D1) |

### 2.2 硬约束(贯穿全文,不可协商)

下列约束的优先级高于任何单模块的便利性。每条标注约束类型与可验收的判定口径。

| 编号 | 硬约束 | 类型 | 判定口径(如何验收 FAIL) | 关联决策 |
| --- | --- | --- | --- | --- |
| C1 | 单一独立维度 | 架构 | 启动后 `server.getAllLevels()` 中本 mod 维度恒为 1 个(`miningdim:mining`);出现运行时新建/销毁 `ServerLevel` 即 FAIL | D1 |
| C2 | 实例 = 维度内不重叠 region | 架构 | 任意两实例 `regionBox` AABB 相交即 FAIL;实例间缓冲带 < 1 区块即 FAIL | D1 |
| C3 | 确定性可复现 | 正确性 | 同一 region 世界坐标 + 同一世界 seed 下,原版 `minecraft:noise` 两次生成的区块必须逐方块相等(原版自身保证) | D3 |
| C4 | 矿洞 100% 连通 | 正确性 | SUPERSEDED: 随 ConnectivityFix 一并下线。现行 `minecraft:cave`/`cave_extra_underground`/`canyon` carver 不提供全局可达断言, 出生点安全性改由出生系统(第十一章)在已生成区块上就近扫描保证 | 见 7.0.5 |
| C5 | 服务端权威 | 安全 | 任何世界状态(方块、实例、danger、传送)只由服务端写;客户端仅接收同步包,不得本地决定 | D8、网络层 |
| C6 | 所有平衡数值可配置 | 工程 | 矿物权重、陷阱概率、danger 系数、实例上限等出现硬编码字面量即 FAIL | ConfigManager |
| C7 | 线程纪律 | 并发安全 | 世界写操作不在主线程(非 `server.execute()` 回调内)即 FAIL;纯计算阻塞主线程即 FAIL | D8 |
| C8 | 性能可压测可验收 | 性能 | 离线生成时长、单实例分配 tick 抖动、刷怪开销有量化阈值且有 GameTest/压测脚本 | 见 2.3 |
| C9 | 异常自然冒泡 | 工程 | 业务函数内用 `Optional.orElse` 掩盖空值 / 裸 `try-catch` 吞异常即 FAIL;仅最外层(命令/事件入口/网络 handler)统一捕获 | 全局规范 |
| C10 | 持久化完整 | 正确性 | 服务器重启后实例注册表、id/seed 计数器、玩家级数据(进入前坐标/gamemode、当前实例、danger)必须完整重建;孤儿实例必须被清理 | D5 |

### 2.3 性能与验收阈值(PENDING待校验,给出建议初值)

下列阈值用于 C8 验收。数值为建议初值,需在目标服务器硬件上压测校准,标 PENDING待校验;但实现必须先按初值埋点与断言。

| 指标 | 建议初值 | 测量点 | 备注 |
| --- | --- | --- | --- |
| 单实例离线体素生成时长 | SUPERSEDED: 离线管线已下线, 无此测量点 | 原 `OfflineCaveGenerator.generate` 工作线程墙钟 | 现行地形由原版 `minecraft:noise` 随区块加载按需生成, 时延受原版区块生成预算管辖 |
| 实例分配引起的主线程单 tick 抖动 | <= 2 ms | 分配时主线程仅做登记与路由(R1 下为查三固定实例) | C7 保证;主线程不得直接跑生成 |
| 单区块地形生成 | 与原版地下区块同量级 | 原版 `NoiseBasedChunkGenerator` 区块生成耗时 | 本 mod 不再介入区块填充回调, 无自有预算项 |
| 单实例 danger 评估开销 | <= 0.2 ms / 玩家 / 评估周期 | 每 20 tick 或事件驱动 | D7 降频 |
| 单实例并发刷怪硬上限 | 建议 30 只 | `MobPressureSystem` 计数器 | 防止与第三方刷怪 mod 叠加爆量(见 1.3) |
| 全局实例数上限 | R1 下恒为 3(每难度一个固定实例);`instance.globalCap` 仅对已下线的动态分配路径有意义 | `InstanceManager` | 见 12.1a |
| 内存:单实例 bitset | SUPERSEDED: 无常驻体素 bitset。历史口径下 region 体素数为 256x192x256 = 12,582,912, 1 bit/体素 ≈ 1.5 MiB | 原 `BitSet` 扁平一维 | 离线管线下线后不再分配该结构 |

### 2.4 非目标(Out of Scope,显式排除)

明确声明不做,避免范围蔓延:

- 运行时动态创建/销毁维度(REJECTED,见 D1 与第四章)。
- 跨服(多 `MinecraftServer`)实例共享 / 数据库后端。本设计持久化只用单服存档(SavedData + Capability)。
- 客户端独立世界生成或单机存档迁移工具。
- 自定义维度的天空盒/光照渲染特效(仅复用原版维度类型 JSON 的环境参数)。
- 玩家经济结算 / 战利品交易系统(矿物产出后交由原版与第三方经济 mod 处理,见 1.3)。

---

## 三、总体架构与模块接口

本章把原文第 2 节的"模块清单"升级为带职责边界、接口签名、依赖方向与调用时序的架构规格。每个模块给出一句话职责边界与核心 Java 接口签名(方法名 / 入参 / 返回 / 受检异常约定)。接口签名为实现契约,字段命名与第二章约束、D1~D8 决策严格对齐。

### 3.1 分层与依赖方向(禁环)

架构分四层,依赖只能自上而下或同层向基础设施层,严禁反向或形成环。基础设施层(Config / Persistence / Network)被上层依赖,但不得反向依赖业务模块。

```text
[入口层]      MiningCommands      NetworkHandler(服务端入站处理)
                    |                       |
                    v                       v
[编排层]      DimensionManager  -->  InstanceManager  -->  ResetSystem
                                          |   |  \
                                          v   v   v
[生成/玩法层] ChunkTicketManager   SpawnSystem   TrapSystem / MobPressureSystem
                                          ^
              地形本身不在本 mod 的调用链上: 由原版 minecraft:noise +
              data/miningdim/worldgen/ 下的 noise_settings / biome / placed_feature 承担;
              本 mod 只经 MiningBiomeSource 参与"这一列属哪个难度 biome"的判定。
                    |
[基础设施层]  ConfigManager   PersistenceLayer(SavedData + Capability)   NetworkHandler(信道与包注册)
```

依赖方向表(行依赖列,Y = 允许依赖,空 = 禁止):

| 依赖方 \ 被依赖 | DimMgr | InstMgr | BiomeSrc | ChunkTicket | Trap | MobPress | Spawn | Reset | Net | Config | Persist |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| MiningCommands(entry) | Y | Y | | | | | | Y | Y | Y | |
| NetworkHandler | Y | Y | | | | | | | | Y | |
| DimensionManager | | Y | | | | | | | | Y | Y |
| InstanceManager | | | | Y | | | Y | | Y | Y | Y |
| MiningBiomeSource | | | | | | | | | | | |
| ChunkTicketManager | | Y | | | | | | | | Y | |
| TrapSystem | | Y | | | | | | | Y | Y | |
| MobPressureSystem | | Y | | | | | | | Y | Y | Persist(读 danger) |
| SpawnSystem | | Y | | | | | | | | Y | |
| ResetSystem | | Y | | Y | | | | | Y | Y | Y |

环依赖检查:`MiningBiomeSource` 是叶子 —— 它只读 `core.RegionLayout` 的静态快照,不依赖 `InstanceManager` 或任何服务端单例(客户端也会反序列化它的 Codec 并调用 `getNoiseBiome`,依赖服务端单例会直接 NPE 崩客户端)。`InstanceManager` 在启动重建与每次滑动后单向写入该快照,方向唯一,不成环。

SUPERSEDED(F021/F032):原表中的 `OfflineCaveGenerator`、`MiningChunkGenerator`、`OreGenerator` 三列已随离线体素管线整体删除,不再存在于依赖图中。矿物与陷阱的铺设改由数据包 `placed_feature` 在原版 feature 阶段完成,不经本 mod 的调用链(见 7.0 与 8.x 的状态标注)。

### 3.2 单例与生命周期归属

| 模块 | 实例形态 | 生命周期归属 | 创建/销毁时机 |
| --- | --- | --- | --- |
| ConfigManager | 静态(`ForgeConfigSpec` 持有) | mod 类加载 | `FMLConstructModEvent` 注册 spec;`ModConfigEvent` 装载值 |
| NetworkHandler | 静态单例(`SimpleChannel`) | mod 生命周期 | `FMLCommonSetupEvent` 注册包 |
| DimensionManager | 服务端单例 | 绑定 `MinecraftServer` | `ServerStartingEvent` 创建,`ServerStoppingEvent` 释放 |
| InstanceManager | 服务端单例 | 绑定 `MinecraftServer` | `ServerStartedEvent` 从 SavedData 重建,`ServerStoppingEvent` flush 落盘 |
| PersistenceLayer (SavedData) | 服务端,挂矿山维度 `DimensionDataStorage` | 绑定 `ServerLevel(miningdim:mining)` | 首次访问惰性创建,世界保存时序列化 |
| MiningBiomeSource | 每维度一个(随 `LevelStem` 反序列化);客户端亦持有一份 | 绑定 `ServerLevel` / `ClientLevel` | 服务器启动 `createLevels`、客户端登录维度同步时由 datapack Codec 创建 |
| RegionLayout(静态快照) | 纯静态 `volatile` 快照,无实例 | 进程级 | 类初始化取编译期默认几何;`InstanceManager` 在启动重建与每次 `slideRegion` 后写入权威值 |
| TrapSystem / MobPressureSystem / SpawnSystem / ResetSystem / ChunkTicketManager | 服务端单例(无状态服务,状态存 InstanceState/Capability) | 绑定 `MinecraftServer` | 随 InstanceManager 创建 |

判定口径:除 `MiningBiomeSource`(每维度实例,但本 mod 维度恒一个,见 C1;且客户端为反序列化必须持有)与 `RegionLayout`(进程级静态快照)外,所有业务模块均为服务端单例,客户端不持有任何业务单例(C5)。

SUPERSEDED(F021/F032):原表中的 `OfflineCaveGenerator`(任务级)与 `MiningChunkGenerator`(每维度)两行随离线管线删除,已无对应类。

### 3.3 核心模块接口契约

异常约定遵循 C9:接口不声明吞异常的 `throws`,业务错误(参数非法、状态不变量破坏)抛 `IllegalStateException` / `IllegalArgumentException` 自然冒泡,仅入口层(命令/网络 handler/事件)统一捕获并反馈玩家。下列签名中的 `InstanceId` 为持久自增 `long` 的封装(D6),`RegionBox` 为 region 的 `BoundingBox`(D1)。

DimensionManager —— 持有并暴露唯一矿山维度,提供维度键与 `ServerLevel` 解析,封装 region 网格坐标换算。不创建/销毁维度(C1)。

```java
public interface DimensionManager {
    ResourceKey<Level> miningDimensionKey();          // 恒返回 miningdim:mining
    ServerLevel miningLevel(MinecraftServer server);  // 解析已存在的 ServerLevel;不存在则 IllegalStateException
    RegionBox regionBoxForGridIndex(long gridIndex);  // 网格序号 -> region AABB(含 >=1 区块缓冲带,D1)
    long gridIndexForBlockPos(BlockPos pos);          // 世界坐标 -> 所属 region 网格序号
}
```

InstanceManager —— 实例生命周期权威。维护 `instanceId -> InstanceState`,负责分配、引用计数、并发上限、状态机推进。所有世界写经 `server.execute`(C7)。

```java
public interface InstanceManager {
    // 分配:超出上限抛 InstanceLimitException(入口层捕获转玩家提示);异步生成,返回的 future 在生成完成后兑现
    CompletableFuture<InstanceHandle> allocate(ServerPlayer requester, Difficulty difficulty, boolean shared);

    Optional<InstanceState> get(long instanceId);
    void onPlayerEnter(long instanceId, ServerPlayer player);   // refCount++/playerSet add
    void onPlayerLeave(long instanceId, ServerPlayer player);   // refCount--;归零记 lastEmptyTick
    void tick(MinecraftServer server);                          // 推进 genState,回收空闲超时实例
    long activeInstanceCount();
    Collection<InstanceState> snapshot();                       // 只读快照,供命令/调试
}
```

`InstanceState`(数据载体,持久化字段对齐 D6):

```java
public record InstanceState(
    long instanceId, long seed, Difficulty difficulty, RegionBox regionBox,
    int refCount, Set<UUID> playerSet, long createdTick, long lastEmptyTick,
    GenState genState) {}                  // genState: PENDING, GENERATING, READY, READY_FALLBACK, RESETTING, FAILED, RECYCLED(全文统一枚举)
```

MiningBiomeSource —— 原版 `BiomeSource` 子类,经 `RegisterEvent` 把 `Codec` 注册到 `BuiltInRegistries.BIOME_SOURCE`(1.20.1 为 `Codec<? extends BiomeSource>`,非 1.20.5 的 `MapCodec`)。职责唯一:判定"这一列属于哪块 region",据此返回难度 biome 或基岩墙 biome(6.4)。y 参数直接丢弃(R2:整列同 biome)。

```java
public class MiningBiomeSource extends BiomeSource {
    public static final Codec<MiningBiomeSource> CODEC;

    @Override protected Codec<? extends BiomeSource> codec();
    @Override protected Stream<Holder<Biome>> collectPossibleBiomes();  // easy/medium/hard/wall 四元
    @Override public Holder<Biome> getNoiseBiome(int x, int y, int z, Climate.Sampler sampler);
    // 内部: RegionLayout.current().difficultyAt(QuartPos.toBlock(x), QuartPos.toBlock(z))
    //       返回 null(三块 region 之外)即归 mining_wall,由 surface_rule 填纯基岩(D1)
}
```

SUPERSEDED(F021/F032):原 3.3 在此处定义的 `OfflineCaveGenerator`(Skeleton -> NoiseCarving -> ConnectivityFix 三阶段产出 `VoxelOccupancy` bitset)、`ConnectivityFix`(6-邻接 BFS + A* 打通)与 `MiningChunkGenerator`(区块回调查表填方块)三份接口契约已整体作废,对应类在仓库中不存在。地形改由原版 `minecraft:noise` + `data/miningdim/worldgen/noise_settings/mining.json` 按需生成,挖空由原版 carver 承担,详见 7.0。

SUPERSEDED(F021/F032):`OreGenerator` 的"离线铺矿表 + ChunkGenerator 落子"契约同样作废。`ore/OreGenerator.java` 与 `ore/OrePlacement.java` 类文件仍在仓库中(其 javadoc 仍引用已删除的 `MiningChunkGenerator`,属已知过期注释),但已无任何生成期消费方;实际铺矿由数据包 `placed_feature` 在原版 feature 阶段完成(见 5.5 与第八章章首状态块)。

TrapSystem —— 静态陷阱(TNT 矿、岩浆池、崩塌矿道、假矿石)在生成阶段布设;动态陷阱(身后刷苦力怕、局部坍塌、岩浆喷发)在运行期由玩家事件触发。布设概率 `trapChance = difficulty * localRisk`(G6),数值可配。

```java
public interface TrapSystem {
    void placeStaticTraps(ServerLevel level, ChunkPos chunk, InstanceState instance);          // 生成期,server.execute
    void onPlayerTick(ServerPlayer player, InstanceState instance, int dangerLevel);            // 运行期,触发动态陷阱
    boolean isTrapBlock(BlockPos pos, InstanceState instance);                                  // 供 SpawnSystem 排除出生点
}
```

MobPressureSystem —— D7 危险压力。每玩家独立 `danger`(`DANGER_MAX` 封顶),`danger` 由 `zoneDifficulty + timeSpent(软封顶收敛+衰减) + oreRichness` 组成,评估降频(每 20 tick 或事件驱动)。随 `danger` 提升刷怪频率/后方生成(G6)。danger 存玩家 Capability(D5),刷怪走显式 `finalizeSpawn` 并受单实例硬上限约束(2.3、1.3)。

```java
public interface MobPressureSystem {
    int evaluateDanger(ServerPlayer player, InstanceState instance);   // 降频调用;返回 [0, DANGER_MAX]
    void applyPressure(ServerPlayer player, InstanceState instance, int danger);  // 刷怪/环境;server.execute
    void onPlayerLeaveRegion(ServerPlayer player);                     // 触发 timeSpent 衰减(D7)
}
```

SpawnSystem —— G7 安全出生。在已 force-load 就绪的真实区块上环形扫描,筛选满足安全谓词(头顶 2 格空气 / 脚下固体 / 无岩浆 / 非陷阱区)的点;不再有离线体素视图与主连通分量候选集(`spawn/SpawnSystem.java` 类注释)。

```java
public interface SpawnSystem {
    BlockPos resolveSpawn(ServerLevel level, InstanceState instance, ServerPlayer player); // 无合法点抛 IllegalStateException(应不发生,C4 保证)
    boolean isSafe(ServerLevel level, BlockPos pos, InstanceState instance);
}
```

ResetSystem —— G8/D1 单实例 region 级重置:先疏散玩家,再 `RESETTING` 态下把整块 region 滑到一块从未生成过的新世界坐标(D3 滑动重置),不触碰其他实例,不增删维度(C1)。旧坐标区块留在磁盘上由 `RetiredRegionGc` 异步回收,详见 13.4。

```java
public interface ResetSystem {
    CompletableFuture<Void> reset(long instanceId, ResetMode mode);  // mode: SAME_SEED(原样重建) | NEW_SEED(刷新随机)
    void evacuate(InstanceState instance, MinecraftServer server);   // 疏散玩家回进入前坐标(读 Capability,D5)
}
```

NetworkHandler —— 1.20.1 `NetworkRegistry.newSimpleChannel`(custom payload 1.20.4+ 不适用)。封装信道与包注册;服务端权威(C5),客户端包仅承载展示数据(danger HUD、实例信息)。

```java
public final class NetworkHandler {
    public static final SimpleChannel CHANNEL;     // newSimpleChannel(new ResourceLocation("miningdim","main"), ...)
    public static void register();                 // FMLCommonSetupEvent;registerMessage(id++, ...)
    public static void sendDangerToClient(ServerPlayer player, int danger);  // S2C 展示包
}
```

ConfigManager —— `ForgeConfigSpec`,所有平衡数值的唯一来源(C6)。`ModConfig.Type.SERVER`(实例/数值为服务端权威)。

```java
public final class ConfigManager {
    public static ForgeConfigSpec SERVER_SPEC;
    // 暴露:regionSize, bufferChunks, maxInstances, minIslandSize, oreWeights(per difficulty),
    //       trapChanceBase, dangerMax, dangerCoeffs, mobSpawnCap, instanceIdleRetireTicks ...
    public static int maxInstances();
    public static int minIslandSize();
    public static OreWeightTable oreWeights(Difficulty d);
}
```

MiningCommands —— Brigadier,`RegisterCommandsEvent`。运营/调试入口:进入/离开/重置/列实例/强制清理孤儿。

```java
public final class MiningCommands {                    // 实际落点: com.miningdim.entry.MiningCommands
    void register(CommandDispatcher<CommandSourceStack> dispatcher);   // 由 EntrySystem 在 RegisterCommandsEvent 调用
    // /mining enter <difficulty> [reseed] | /mining leave | /mining info [instanceId]
    // /mining reset <instanceId> [reseed] | /mining reset all        (reset 分支需 OP level 2)
}
```

注:线上唯一注册的 `/mining` 根在 `entry` 包;`com.miningdim.command` 包里另有一套同名实现未接入主类,是死代码(见第十七章章首状态块)。

PersistenceLayer —— D5。实例注册表 + 全局 id/seed 计数器用 `SavedData`(挂矿山维度 `DimensionDataStorage`);玩家级数据(进入前维度+坐标+gamemode、当前 instanceId、danger)用 Forge Capability(`AttachCapabilitiesEvent<Entity>` + `ICapabilitySerializable`,配 `PlayerEvent.Clone` 复制,D5)。启动时重建 InstanceManager 内存视图并清理孤儿(C10)。

```java
public final class MiningSavedData extends SavedData {
    public static MiningSavedData get(ServerLevel miningLevel);   // computeIfAbsent on DimensionDataStorage
    public long nextInstanceId();                                 // 持久自增,不复用
    public long deriveSeed(long instanceId);                      // 全局种子 + instanceId 派生(D6,非 seed++)
    public void putInstance(InstanceState s);
    public Collection<InstanceState> allInstances();
    @Override public CompoundTag save(CompoundTag tag);
}

public interface IMiningPlayerData {                              // Capability 数据
    GlobalPos preEnterPos();   GameType preEnterGameType();       // 进入前现场(D5/G7 回程)
    OptionalLong currentInstanceId();
    int danger();   void setDanger(int danger);                   // D7
    CompoundTag serializeNBT();   void deserializeNBT(CompoundTag tag);
}
```

### 3.4 生成主流程调用时序(实例分配)

下列时序覆盖 G1/G2 的端到端路径,显式标注线程归属(D8/C7)。R1 固定区域模型下不再有"分配即生成"这一步:三个难度实例在开服时已预建并恒为 `READY`,入场只做路由 + 区块就绪等待。

```text
1. [主线程] 玩家触发 /mining enter -> entry.MiningCommands 解析参数 -> EntryGateway.requestEnter
2. [主线程] gateCheck: 矿工职业等级门槛(MinerLevelGate)+ 入场费余额预判(14.4/16.2.11)
3. [主线程] InstanceManager.allocate(player, difficulty)
   -> 直接路由到该难度的固定实例(fixedInstanceFor);不新建、不复用扫描、不背压
4. [主线程] ChunkTicketManager 对出生候选区块加 force-load ticket,逐 tick 轮询至区块 FULL(14.3 防虚空)
5. [原版区块生成线程] minecraft:noise 按需生成该区块: noise_settings 的 final_density=1 先填满,
   biome 由 MiningBiomeSource 判定(难度 biome 或 mining_wall),surface_rule 据 biome 换基材/填基岩,
   原版 carver 挖出洞穴,placed_feature 阶段铺矿与布陷阱
6. [主线程] SpawnSystem.findSpawn 在已就绪的真实区块上环形扫描安全站立点(G7,第十一章)
7. [主线程] entryFee > 0 时 EconomyServices.tryCharge 扣款,失败回滚 pendingEnter(14.2 步骤 7.5)
8. [主线程] 写 Capability(preEnterPos/GameType,D5)-> teleportTo -> onPlayerEnter(refCount++)
9. [主线程] MobPressureSystem 初始化 danger 与出生冻结窗口;MiningNetwork 下发 DangerSyncS2C(展示,C5)
```

关键不变量:本 mod 自身已无工作线程重计算路径(步骤 5 的区块生成由原版调度,不归本 mod 管);步骤 1~4、6~9 全部在主线程或其 `server.execute` 回调内执行(D8)。Capability 必须在 `teleportTo` 之前写——跨维度传送会令 Forge 在同 tick 内暂时失效玩家 capability 的 `LazyOptional`(`entry/EntryGateway.java` 有实测崩因记录)。

### 3.5 端职责划分(logical client / server / both)

C5 服务端权威:所有世界状态与决策在服务端;客户端只渲染与接收同步。下表逐功能标注归属,并标出需要网络包的跨端交互。

| 功能 | 逻辑端 | 网络包 | 方向 | 说明 |
| --- | --- | --- | --- | --- |
| 维度注册 / `LevelStem` 反序列化 | both | 维度同步(原版内置) | S2C | 客户端需本 mod 的 `BiomeSource` Codec 才能反序列化(1.2 的 `side=BOTH` 硬约束) |
| 区块方块数据 | both | 区块同步(原版内置) | S2C | 服务端生成,客户端仅渲染 |
| 实例分配 / 引用计数 | server | 无(命令触发) | — | 纯服务端权威,C5 |
| 地形生成(`minecraft:noise` + carver) | server | 无 | — | 原版区块生成流程,客户端只收区块同步 |
| 矿物 / 静态陷阱布设 | server | 无 | — | 走数据包 `placed_feature`,落为方块后随区块同步,无专用包 |
| 动态陷阱触发 | server | 无(粒子/音效随原版广播) | — | 服务端 `level.sendParticles` 广播,客户端被动渲染 |
| danger 计算 | server | danger 展示包 | S2C | `sendDangerToClient`,客户端仅画 HUD,不参与计算(C5) |
| danger HUD 渲染 | client | 上条同包 | — | 纯渲染;无 C2S danger 包 |
| 出生点解析 / 传送 | server | 无 | — | 服务端权威,传送后原版同步玩家位置 |
| 进入 / 离开 / 重置命令 | server(执行) + client(/ 命令输入) | Brigadier(原版命令通道) | C2S | 命令在服务端执行;客户端仅发命令文本 |
| 进入前现场(坐标/gamemode)持久化 | server | 无 | — | Capability,服务端存档(D5) |
| 实例列表 / 调试 UI(若有) | both | 实例信息展示包 | S2C(请求 C2S) | PENDING:仅当需要客户端面板时引入;默认用命令文本回显,不开包 |
| 配置(SERVER 类型) | server | 配置同步(原版内置) | S2C | `ModConfig.Type.SERVER` 登录时下发,客户端只读 |

跨端交互最小化原则:矿区自身的跨端需求只有三个 S2C 展示包(danger HUD、传送结果、实例状态),其余跨端均复用原版内置同步(维度、区块、命令、配置)。同一条信道后来还承载了职业框架与 Web UI 桥的包(见 15.3 现行总表),但矿区业务本身没有新增 C2S 面。这把自定义网络面收敛到最小,降低与第三方网络 mod 的协议冲突风险,并满足 C5 服务端权威。

---

## 四、维度与世界结构模型(DECIDED)

本章把跨章决策 D1(单一静态维度 + region 网格分区)落地为可实现的世界结构规格,并给出 1.20.1 数据包维度注册三件套的文件路径与字段骨架。所有"运行时动态创建维度"的方案在 4.5 节作为 REJECTED 归档。

### 4.1 总体模型:一维度多 region

状态:DECIDED

全 mod 只注册一个静态专属维度 `miningdim:mining`,在服务器启动(`MinecraftServer` 调用 `createLevels` 读取 `WorldGenSettings` 的 `LevelStem` 时)随内置数据包一次性建立。所有矿山实例共享这一个 `ServerLevel`,通过把维度内部空间按固定三维网格切成互不重叠的 region(区域 bounding box)来隔离:

- 一个实例(`InstanceState`)恰好占用一个 region。
- region 之间留 >=1 区块(>=16 格)的实心缓冲带,且 region 边界外恒为实心墙(对应 D4 的 BFS 边界)。
- "多实例"= 同一维度内多个 region;"重置单实例"= 仅删除并重生成该 region 覆盖的区块,不触碰其他 region。

| 概念 | 实现载体 | 说明 |
| --- | --- | --- |
| 维度 | `ServerLevel`(key=`miningdim:mining`) | 启动注册,生命周期 = 服务器生命周期 |
| 实例 | `InstanceState`(D6) | 逻辑对象,1 实例 = 1 region |
| region | `BoundingBox`(`net.minecraft.world.level.levelgen.structure.BoundingBox`) | 三维整数盒,网格对齐 |
| 缓冲带 | region 间空隙 + 边界实心墙 | 防跨实例穿墙、为 BFS 提供天然边界 |

### 4.2 region 网格与坐标转换

状态:DECIDED(尺寸标 PENDING待校验)

region 在 XZ 平面按固定步长平铺成无限网格(Y 方向单层,占满整个维度高度预算)。网格索引 `(gx, gz)` 与 `instanceId` 解耦:`instanceId` 自增,分配时由 `InstanceManager` 用一个空闲网格槽位映射函数 `slotOf(instanceId)`(如沿 Ulam 螺旋或行优先扫描)取得 `(gx, gz)`,保证不复用未回收槽位。

region 尺寸建议(PENDING待校验,需结合区块加载压力实测):

| 参数 | 建议初值 | 含义 | 备注 |
| --- | --- | --- | --- |
| `REGION_SIZE_X` | 256 | region 在 X 方向格数(16 区块) | 必须为 16 的整数倍,利于区块对齐 |
| `REGION_SIZE_Z` | 256 | region 在 Z 方向格数(16 区块) | 同上 |
| `REGION_GAP` | 32 | 相邻 region 之间缓冲带格数(2 区块) | `= BUFFER_CHUNKS * 16`,实心填充 |
| `REGION_STRIDE_X` / `REGION_STRIDE_Z` | 288 | XZ 网格步长 = SIZE + GAP = 256 + 32 | 派生量。该值已被既有存档几何冻结(`MiningConstants.java` 注明 stride 288 依赖 `BUFFER_CHUNKS` 不变):改 SIZE 或 GAP 会使旧存档的 region 网格错位废图 |
| `REGION_MIN_Y` | -64 | region 底部世界 Y | = 维度 `min_y` |
| `REGION_HEIGHT` | 192 | region 高度 | = 维度 `height`,见 4.3;`InstanceManager` 启动期与 `ServerLevel.getHeight()` 自检,不一致直接抛 |
| `REGION_ORIGIN_X/Z` | 0 | 网格原点世界坐标 | 远离主城/出生点,避免与原版结构冲突无意义(本维度无原版结构) |

世界坐标 <-> region 本地坐标转换由 `InstanceManager` 单一提供(`ChunkGenerator` 不得自行猜测网格,只查 `InstanceManager`):

```
regionOriginX(gx) = REGION_ORIGIN_X + gx * REGION_STRIDE_X
regionOriginZ(gz) = REGION_ORIGIN_Z + gz * REGION_STRIDE_Z
localX = worldX - regionOriginX(gx)          // [0, REGION_SIZE_X)
localY = worldY - REGION_MIN_Y               // [0, REGION_HEIGHT)
localZ = worldZ - regionOriginZ(gz)          // [0, REGION_SIZE_Z)
voxelIndex = (localY * REGION_SIZE_Z + localZ) * REGION_SIZE_X + localX
```

`voxelIndex` 原是 D2 体素 bitset 的扁平一维下标;离线管线下线后它只剩两处用途:`RegionBox.worldVoxelIndex` 的坐标序约定,以及矿物/陷阱旧数据结构的索引口径。落在缓冲带或 region 外的世界坐标由 `InstanceManager.regionAt(worldX, worldZ)` 返回 `null`;现行实现里"区域外填实心"不再由 Java 判定,而是由 `MiningBiomeSource` 把这些列判成 `mining_wall` 群系、再经 `surface_rule` 填纯基岩(见 6.3)。

region 体素体积(仅作容量口径参考):256 * 256 * 192 = 12,582,912 体素,1 bit/体素 ≈ 1.5 MiB。SUPERSEDED:该量曾是离线生成阶段的内存峰值,现行实现不分配任何整 region 体素结构,故不再进入任何内存上限校验。

### 4.3 Y 轴预算与垂直结构

状态:DECIDED

维度高度取 192 格。它同时是 region 高度,不再切分给多个难度子盒——R2 之后一整块 region 只属一个难度(见第六章)。

| 字段 | 值 | 说明 |
| --- | --- | --- |
| `min_y` | -64 | 与原版 overworld 一致 |
| `height` | 192 | 总高 -64..127,必须为 16 的倍数 |
| `logical_height` | 192 | 传送/区块逻辑高度 |
| `local_y` 范围 | 0..191 | region 本地 Y(`REGION_FULL_MIN/MAX_LOCAL_Y`) |
| `worldY` 范围 | -64..127 | `REGION_FULL_MIN/MAX_WORLD_Y` |

高度的唯一真源是两份同值的数据包 JSON:`data/miningdim/dimension_type/mining.json` 的 `height`/`logical_height`,与 `data/miningdim/worldgen/noise_settings/mining.json` 的 `noise.height`(`min_y` 同理两处同值)。Java 侧 `MiningConstants.REGION_HEIGHT` 必须与之相等,`InstanceManager` 启动期拿 `ServerLevel.getHeight()` 做三方自检,不一致直接抛异常。

基材不再靠"随世界 Y 自然切换",改由 `noise_settings/mining.json` 的 `surface_rule` 按 biome 分支决定:`default_block` 为 `minecraft:stone`(Easy 即维持石头);命中 `miningdim:mining_hard` 的列整列换深板岩;命中 `miningdim:mining_medium` 的列按 `vertical_gradient`(绝对 Y 24 以下必深板岩、40 以上必石头,中间按梯度随机)做过渡。这样"用石头/深板岩区分难度"与"难度不由 Y 决定"两个需求同时成立,也不再需要早期方案那样的大纵深垂直预算。

注(已知代码遗留):`core/Difficulty.java` 里还挂着一份 `MaterialPalette` 难度调色板(含 TUFF/ANDESITE 等点缀),但全仓无任何消费方——它是自定义 `ChunkGenerator` 时代的产物,现行基材口径以上述 `surface_rule` 为准。

### 4.4 数据包维度注册四件套

状态:DECIDED(Critical 缺口闭合)

1.20.1 维度走数据包动态注册表(datapack registries,服务器启动时从 JSON 加载)。mod 在 jar 内置数据包(`resources/data/...`)中提供以下四类 JSON,服务器启动即注册,无需任何 Java 注册代码;Java 侧只需在 `RegisterEvent` 注册 `BiomeSource` 的 Codec(见第五章),JSON 的 `generator.biome_source.type` 字段据此 ResourceLocation 反序列化。

文件 (1) 维度类型 `dimension_type`
路径:`src/main/resources/data/miningdim/dimension_type/mining.json`

```json
{
  "ultrawarm": false,
  "natural": false,
  "piglin_safe": false,
  "respawn_anchor_works": false,
  "bed_works": false,
  "has_raids": false,
  "has_skylight": false,
  "has_ceiling": true,
  "coordinate_scale": 1.0,
  "ambient_light": 0.0,
  "logical_height": 192,
  "height": 192,
  "min_y": -64,
  "infiniburn": "#minecraft:infiniburn_overworld",
  "effects": "minecraft:the_nether",
  "monster_spawn_light_level": 7,
  "monster_spawn_block_light_limit": 0
}
```

字段决策说明:`has_skylight=false` + `has_ceiling=true`(纯地下,无天空光,配合 danger 系统的光照下降);`bed_works`/`respawn_anchor_works=false`(禁止在副本内设重生点,死亡按 D5 capability 还原进入前位置);`natural=false`(禁用床、禁止下界传送门联动);`effects` 选 `minecraft:the_nether` 以去除天空渲染(可换自定义,PENDING)。`monster_spawn_light_level` 给 mob 压力系统(第十章)提供原版刷怪光照接口;本设计取整数简写 `7`,语义是"每次判定都用恒定阈值 7"。该字段的对象形式(`{"type":"minecraft:uniform","min_inclusive":0,"max_inclusive":7}`)在 1.20.1 同样合法,但语义是"每次判定从 0..7 随机取阈值",与本设计不同,不得互换照抄。

文件 (2) level_stem / 维度实例 `dimension`
路径:`src/main/resources/data/miningdim/dimension/mining.json`

该 JSON 即 `LevelStem` 的序列化形式:绑定 `type`(指向文件 1)与 `generator`。generator 用原版 `minecraft:noise`,`settings` 指向本 mod 自带的噪声设置(文件 4),`biome_source` 是本 mod 唯一注册的自定义 Codec。

```json
{
  "type": "miningdim:mining",
  "generator": {
    "type": "minecraft:noise",
    "settings": "miningdim:mining",
    "biome_source": {
      "type": "miningdim:mining_biome_source"
    }
  }
}
```

`biome_source.type` = `miningdim:mining_biome_source` 必须与第五章 `RegisterEvent` 向 `BuiltInRegistries.BIOME_SOURCE` 注册 Codec 时用的 ResourceLocation 完全一致,否则 datapack 反序列化失败、维度建立崩溃。

`generator.type` 原为自定义的 `miningdim:mining_chunk_generator`,随 F021/F032 一并废止;`CHUNK_GENERATOR` 注册表现在没有任何 `miningdim` 条目,`settings` 也不再是内联的网格参数对象,而是一个指向 `worldgen/noise_settings/mining.json` 的 ResourceLocation。4.2 的网格常量因此只存在于 Java 侧(`MiningConstants` 与 `instance.regionSizeChunks`/`bufferChunks` 配置),不再有"JSON 与 Java 双写"问题。

文件 (3) 维度生效保障 —— 内置数据包元数据
路径:`src/main/resources/pack.mcmeta`(mod jar 根内置数据包描述,`pack_format` 1.20.1 = 15)

```json
{ "pack": { "description": "miningdim builtin data", "pack_format": 15 } }
```

注:1.20.1 下,mod jar 内 `data/<ns>/dimension/<name>.json` 会作为 builtin datapack 在新建世界/启动时自动加入并注册维度;无需玩家手动放置数据包。已存档世界若在加入此 mod 前创建,新维度也会在下次启动 `createLevels` 时补建(原版按 `LevelStem` 注册表逐项建 `ServerLevel`)。

文件 (4) 噪声设置 `worldgen/noise_settings`
路径:`src/main/resources/data/miningdim/worldgen/noise_settings/mining.json`

`generator.settings` 指向的就是它,是现行地形的真正主体(字段全貌见 7.0.2)。本章只锁高度口径:`noise.min_y = -64`、`noise.height = 192`,必须与文件 (1) 的 `min_y`/`height` 同值,否则原版在建 `ServerLevel` 时即报高度不匹配。

四件套注册链路总览:

| 文件 | 注册表 | 触发时机 | 引用关系 |
| --- | --- | --- | --- |
| `dimension_type/mining.json` | `minecraft:dimension_type`(datapack registry) | 服务器启动加载 datapack | 被 dimension JSON 的 `type` 引用 |
| `dimension/mining.json` (LevelStem) | `minecraft:dimension`(datapack registry) | `createLevels` 时建 `ServerLevel` | `type`->文件1;`generator.settings`->文件4;`generator.biome_source`-> BIOME_SOURCE codec |
| `worldgen/noise_settings/mining.json` | `minecraft:worldgen/noise_settings`(datapack registry) | 服务器启动加载 datapack | 被 dimension JSON 的 `generator.settings` 引用;其 `surface_rule` 反向引用 `worldgen/biome/*` 的 biome 键 |
| `pack.mcmeta` | 内置 datapack 识别 | jar 加载 | 使上面三者被识别为有效数据 |

### 4.5 REJECTED:运行时动态创建/销毁维度

状态:REJECTED

被否决方案:为每个矿山实例在运行时动态 `new ServerLevel` 并注册进 `MinecraftServer.levels`,实例销毁时移除维度。

否决理由(对应 D1):

1. 无公开运行时 API。原版 1.20.1 仅在服务器启动 `MinecraftServer.createLevels` 阶段从 `WorldGenSettings` 读取 `LevelStem` 注册表逐项构建 `ServerLevel`;构建完成后维度相关注册表随存档冻结,不存在公开的运行时新增/移除 `ServerLevel` 接口。
2. mixin 注入脆弱。强行 mixin `MinecraftServer.levels`(一个 `Map<ResourceKey<Level>, ServerLevel>`)属高风险 hack:需手动接管 `ServerLevel` 的 tick、保存、卸载、`ForgeChunkManager` ticket、维度数据存储目录创建,极易与其他 mod 冲突且跨快照崩。
3. 客户端同步复杂。维度 registry 需同步到客户端(`ClientboundLoginPacket`/维度同步逻辑),运行时动态维度需自定义同步与客户端 `ClientLevel` 创建,工作量与风险远超收益。
4. 存档目录管理复杂。每维度对应 `dimensions/<ns>/<path>` 存档子目录,动态增删涉及文件系统生命周期与崩溃恢复,易产生孤儿目录。

替代结论:采用 D1 的"单维度 + region 网格"。重置实例只需删除/重生成 region 区块(`ServerLevel` 始终存活),规避了上述全部问题。

---

## 五、注册架构(Forge 1.20.1)

状态:DECIDED

本章给出所有注册对象的清单、目标注册表、注册方式与时机。1.20.1 的注册分三类机制:DeferredRegister(Forge 包装注册表,mod 构造期)、RegisterEvent(直注 `BuiltInRegistries`,如 codec)、datapack 注册表(JSON,随内置数据包,见第四章)。错时机或错线程注册会直接崩服,故时机是硬约束。

### 5.1 注册对象总清单

状态:DECIDED(Major 缺口闭合)

| 对象 | 目标注册表 | 注册方式 | 时机(事件) | 线程 |
| --- | --- | --- | --- | --- |
| 自定义方块(假矿石、陷阱触发块等) | `ForgeRegistries.BLOCKS` | `DeferredRegister<Block>` | mod 构造期 -> `RegisterEvent`(BLOCKS) | mod 事件总线 |
| 对应 `BlockItem` 及道具 | `ForgeRegistries.ITEMS` | `DeferredRegister<Item>` | mod 构造期 -> `RegisterEvent`(ITEMS) | mod 事件总线 |
| `BlockEntityType`(陷阱/标记方块实体) | `ForgeRegistries.BLOCK_ENTITY_TYPES` | `DeferredRegister<BlockEntityType<?>>` | mod 构造期 | mod 事件总线 |
| `MiningBiomeSource` 的 `Codec` | `BuiltInRegistries.BIOME_SOURCE` | `RegisterEvent.register(Registries.BIOME_SOURCE, ...)` | `RegisterEvent` | mod 事件总线 |
| `DimensionType`(`miningdim:mining`) | `minecraft:dimension_type`(datapack) | 内置数据包 JSON | 服务器启动加载 | 启动线程 |
| `LevelStem`(`dimension/mining.json`) | `minecraft:dimension`(datapack) | 内置数据包 JSON | `createLevels` | 启动线程 |
| `NoiseGeneratorSettings`(`worldgen/noise_settings/mining.json`) | `minecraft:worldgen/noise_settings`(datapack) | 内置数据包 JSON | 服务器启动加载 | 启动线程 |
| `Biome`(三难度区 + `mining_wall` 基岩墙,共四个) | `minecraft:worldgen/biome`(datapack) | 内置数据包 JSON | 服务器启动加载 | 启动线程 |
| `ConfiguredFeature` / `PlacedFeature`(矿物、装饰、陷阱) | `minecraft:worldgen/configured_feature` / `placed_feature`(datapack) | 内置数据包 JSON(能源类矿物由 datagen 产出) | 服务器启动加载 | 启动线程 |
| `SavedData`(实例注册表/计数器,D5) | 无注册表(按需 `computeIfAbsent`) | `DimensionDataStorage.computeIfAbsent` | 运行时(首次访问) | 主线程 |
| 玩家 `Capability`(D5) | Forge capability | `RegisterCapabilitiesEvent` + `AttachCapabilitiesEvent` | `RegisterCapabilitiesEvent`(注册类型);`AttachCapabilitiesEvent<Entity>`(挂载) | mod / forge 事件总线 |
| 网络通道(进入/重置/danger 同步) | Forge `SimpleChannel` | `NetworkRegistry.newSimpleChannel` | `FMLCommonSetupEvent`(`enqueueWork`) | 主线程(setup) |
| 配置 | `ForgeConfigSpec` | `ModLoadingContext.registerConfig` | mod 构造期 | mod 事件总线 |
| 命令(Brigadier) | 无注册表 | `RegisterCommandsEvent` | `RegisterCommandsEvent` | forge 事件总线 |
| GameTest | Forge GameTest | `@GameTestHolder` + `@GameTest` | 测试运行期 | 测试 |

注:`NetworkRegistry.newSimpleChannel` 是 1.20.1 的正确网络 API;1.20.4+ 的 custom payload 机制不适用,严禁套用。

### 5.2 DeferredRegister(方块/物品/方块实体)

状态:DECIDED

方块、物品、方块实体用 `DeferredRegister` 在 mod 构造期声明,Forge 在对应 `RegisterEvent` 触发时统一注入。骨架:

```java
public final class MiningRegistries {
    public static final DeferredRegister<Block> BLOCKS =
        DeferredRegister.create(ForgeRegistries.BLOCKS, "miningdim");
    public static final DeferredRegister<Item> ITEMS =
        DeferredRegister.create(ForgeRegistries.ITEMS, "miningdim");
    public static final DeferredRegister<BlockEntityType<?>> BLOCK_ENTITIES =
        DeferredRegister.create(ForgeRegistries.BLOCK_ENTITY_TYPES, "miningdim");

    public static final RegistryObject<Block> FAKE_ORE =
        BLOCKS.register("fake_ore", () -> new FakeOreBlock(
            BlockBehaviour.Properties.copy(Blocks.STONE)));

    public static final RegistryObject<Item> FAKE_ORE_ITEM =
        ITEMS.register("fake_ore", () -> new BlockItem(
            FAKE_ORE.get(), new Item.Properties()));

    public static void init(IEventBus modBus) {
        BLOCKS.register(modBus);
        ITEMS.register(modBus);
        BLOCK_ENTITIES.register(modBus);
    }
}
```

`init(modBus)` 在 mod 主类构造函数中调用(`FMLJavaModLoadingContext.get().getModEventBus()`)。`DeferredRegister.register(modBus)` 内部订阅 `RegisterEvent`,因此最终注册仍发生在 `RegisterEvent` 期,只是写法被包装。`RegistryObject.get()` 仅可在注册完成之后调用,严禁在静态初始化或构造期 `.get()`。

### 5.3 RegisterEvent(BiomeSource 的 Codec)

状态:DECIDED(Major 缺口闭合)

自定义 `BiomeSource` 的注册对象是它的 `Codec`(用于 datapack JSON 的反序列化),目标是原版 `BuiltInRegistries.BIOME_SOURCE`。`DeferredRegister` 不直接覆盖这个原版 codec 注册表,故用 `RegisterEvent` 直注。注册逻辑集中在 `registry/ModRegistration.java` 作为单一真源,`WorldgenSystem` 只订阅并委派:

```java
public static void onRegister(RegisterEvent event) {
    event.register(Registries.BIOME_SOURCE, helper ->
        helper.register(
            MiningConstants.BIOME_SOURCE_ID,   // miningdim:mining_biome_source
            MiningBiomeSource.CODEC));         // Codec<MiningBiomeSource>
}
```

要点:

- `Registries.BIOME_SOURCE` 是 `ResourceKey<Registry<Codec<? extends BiomeSource>>>`,与第四章 JSON 的 `generator.biome_source.type` 字段值一一对应。ResourceLocation 字符串必须与 JSON 完全一致(故取 `MiningConstants.BIOME_SOURCE_ID` 单点定义),否则 datapack 加载时反序列化失败、维度建立崩溃。
- `MiningBiomeSource.CODEC` 类型为 `Codec<MiningBiomeSource>`(1.20.1 该注册表的元素类型是 `Codec<? extends BiomeSource>`;`MapCodec` 化是 1.20.5+/NeoForge 的迁移,不适用本版)。
- 监听方法挂在 mod 事件总线。`RegisterEvent` 在 mod 构造之后、配置加载之前,对每个注册表各触发一次,故只能在此期注册,运行时调用会因注册表冻结抛 `IllegalStateException` 并崩服。

SUPERSEDED(F021/F032):本节原来还有一条向 `Registries.CHUNK_GENERATOR` 注册 `MiningChunkGenerator.CODEC` 的分支。自定义生成器下线后该分支已删除,`CHUNK_GENERATOR` 注册表里没有任何 `miningdim` 条目 —— 这是判断"文档是否过期"的一条快速取证:`grep "CHUNK_GENERATOR" src/` 零命中即说明本节新稿正确。

### 5.4 datapack 注册表对象(维度/生物群系)

状态:DECIDED

`DimensionType`、`LevelStem`、`NoiseGeneratorSettings`、自定义 `Biome`、`ConfiguredFeature`/`PlacedFeature` 均属 datapack(动态)注册表对象。本设计选择 jar 内置数据包 JSON 直接提供(第四章四件套 + 6.4 biome JSON + 7.0.3 的 feature JSON),不做 Java 端 `DataPackRegistryEvent` 自建注册表(原版已有这些注册表,无需新建)。

数据生成(DataGen)现状:维度四件套与三难度 + `mining_wall` 四个 biome 是手写 JSON,落在 `src/main/resources/data/miningdim/` 下;能源类矿物的 `placed_feature`(`ore_bauxite_*`/`ore_borax_*`/`ore_silver_*`/`ore_tin_*`/`ore_nickel_hard`/`ore_chromium_hard`/`ore_tungsten_hard` 等)由 `GatherDataEvent` 产出,落在 `src/generated/resources/data/miningdim/worldgen/placed_feature/` 下。查这些 feature 时两个目录都要看,只搜 `src/main/resources` 会误判成"引用了不存在的 feature"。

### 5.5 矿物生成机制与原版 Feature 的关系

状态:DECIDED(已随 F021/F032 反转,本节为现行结论)

矿物生成只剩一条路径:原版 `Feature` -> `ConfiguredFeature` -> `PlacedFeature`(datapack JSON,挂在 biome 的 `features` 列表),由区块生成的 feature 阶段逐块独立放置。

| 路径 | 机制 | 本设计是否采用 |
| --- | --- | --- |
| 原版世界生成 | `PlacedFeature` 挂 biome features 列表,逐区块独立放置 | 采用(全部矿物、装饰与静态陷阱) |
| 离线注入 | 原 D2 方案:工作线程算好整 region 体素与铺矿表,`MiningChunkGenerator` 查表落子 | REJECTED(F021/F032,连同整条离线管线删除) |

反转理由:自定义 `ChunkGenerator` 下线后,已不存在"在区块回调里查铺矿表"的落子点,离线铺矿表没有任何消费方;而原版 `PlacedFeature` 本身就是逐区块确定性的(由世界 seed + 区块坐标派生),与现行 `minecraft:noise` 生成链天然契合。

落地形态:三难度 biome(6.4)的 `features` 列表直接引用原版矿物 feature(`minecraft:ore_coal_upper`/`ore_iron_*`/`ore_copper` 等)与本 mod 自有 feature(`miningdim:misc_*` 石材点缀、`miningdim:ore_*` 能源矿与稀有矿、`miningdim:trap_*` 静态陷阱)。`mining_wall` biome 的 features 全空(它整列填基岩,不需要也不允许任何 feature)。第八章的权重/配额数值表因此降级为"设计意图与平衡参考",不再是生成期算法的输入。

---

## 六、难度分区设计(R1/R2)

本章定死三个难度区的摆放、隔离与 biome 映射。原稿的"单 region 内按 worldY 垂直堆叠三个难度子盒"方案已被 R1/R2 整体推翻,6.1 保留其演进记录,6.2 起为现行实现。

### 6.1 方案演进:从垂直子盒到"难度=区域"

状态:6.1 的旧方案 REJECTED(R1/R2 推翻);现行方案见 6.2

旧第 4 节同时主张:(a) 因 Y 轴受限,改用"区域分层"取代 Y 分层;(b) Easy 用石头、Medium 加深板岩、Hard 以深板岩为主。当时的消解方案是"三区在单一 region 内沿 Y 垂直堆叠,基材随世界 Y 自然切换(Hard 落在 Y<0 深板岩带)",并据此定下 Easy `worldY 192..311` / Medium `64..181` / Hard `-56..53` 三个子盒与其间的实心隔层、跨区竖井。

该方案被 R1/R2 推翻,理由有三:

1. 垂直预算不够了。维度高度后来由 384 收到 192(`worldY -64..127`,见 4.3),旧表里的 Easy 子盒 `worldY 192..311` 整段落到维度之外,Medium 的 `64..181` 上沿也越界,只有 Hard 的 `-56..53` 仍完整落在范围内 —— 三层堆叠模型本身已不成立。
2. 自定义 `ChunkGenerator` 下线后(F021/F032),"填实心体素时按 worldY 选基材"这个落子点消失,基材切换只能走 `surface_rule`,而 `surface_rule` 的天然判据是 biome 而非子盒。
3. 子盒模型要求隔层 + 竖井 + `ConnectivityFix` 打通三件事同时成立,而 `ConnectivityFix` 已随离线管线删除,隔层再无可控开口手段。

R1/R2 裁决:难度 = 一整块独立 region。三块固定 region 在 XZ 平面沿 X 轴并排,一整块就是一个难度,`worldY` 完全不参与难度判定;旧的难度子盒 Y 常量已从 `MiningConstants` 删除。"不让玩家靠下挖几格就跳难度"这个原始诉求由此更彻底地满足:玩家在一块 region 里无论挖到哪个 Y,难度恒定;换难度只能经入场流程重新进入另一块 region,不存在"走过去"的路径。

### 6.2 三固定区域的 XZ 摆放

状态:DECIDED(R1)

三块 region 各 `256 x 192 x 256`(`REGION_SIZE_X` x `REGION_HEIGHT` x `REGION_SIZE_Z`),沿 X 轴并排,Z 列恒为 `FIXED_REGION_CELL_Z = 0`:

| 难度区 | 网格单元 X 列 | 常量 | region 全高 local_y | region 全高 worldY | 主基材 |
| --- | --- | --- | --- | --- | --- |
| Easy | 0 | `EASY_CELL_X` | 0..191 | -64..127 | 石头(`default_block`) |
| Medium | 1 | `MEDIUM_CELL_X` | 0..191 | -64..127 | 石头,绝对 Y 24 以下渐变为深板岩 |
| Hard | 2 | `HARD_CELL_X` | 0..191 | -64..127 | 整列深板岩 |

几何要点:

- 单元 X 列彼此差 1,经 `REGION_STRIDE_X = REGION_SIZE_X + REGION_GAP = 288` 平移后,相邻两块 region 之间天然留出 `REGION_GAP = BUFFER_CHUNKS * 16 = 32` 格缓冲带,满足 C2 不相交 + D1 实心隔离。
- 三块 region 的 `RegionBox` 由 `RegionGrid.fixedRegionFor(difficulty)` 据运行期 stride 派生,是全 mod 单一权威,其余子系统不得自行推算网格。
- 编译期几何只是初始值:D3 滑动重置会把每块 region 整体挪到新的世界坐标。判"某点属哪块 region"必须查运行期快照 `RegionLayout.current()`,不能用编译期单元几何(用了会把滑动后的新区判成网格外,整块生成成实心基岩)。
- 三块 region 的高度占满整个维度(`REGION_FULL_MIN_LOCAL_Y = 0` .. `REGION_FULL_MAX_LOCAL_Y = 191`),不再有难度子盒、隔层与顶/底板的概念。封顶封底由 `surface_rule` 的基岩渐变承担(见 6.3)。

### 6.3 区域隔离:`mining_wall` 群系 + `surface_rule` 基岩封边

状态:DECIDED(D1 的纯 datapack 实现)

D1 的"实心隔离"不再靠 `ChunkGenerator` 在 region 外填实心墙,而是落在数据包这条链上:

1. `MiningBiomeSource` 把三块 region 之外的所有列(缓冲带 + 网格外)判给第四个群系 `miningdim:mining_wall`(`data/miningdim/worldgen/biome/mining_wall.json`)。
2. `noise_settings/mining.json` 的 `surface_rule` 里有一条 `minecraft:biome` 条件 `biome_is: ["miningdim:mining_wall"]`,命中即整列填 `minecraft:bedrock`。
3. `mining_wall` 的 `carvers.air` 为空列表、`features` 全空,故没有任何 carver 或 feature 能在这些列里挖洞或铺东西;三难度区的 carver 一旦挖到基岩也会停下(原版 carver 不破基岩)。

三条合起来就把三个难度盒子彼此封死,且不依赖任何 Java 侧的边界判定。同一套 `surface_rule` 还负责维度的封顶封底:两条 `vertical_gradient` 规则(`miningdim:bedrock_floor` 在底部 0..5 格渐变、`miningdim:bedrock_roof` 在顶部 5 格内渐变)把维度上下边界填成基岩,对应 `dimension_type` 的 `has_ceiling = true`。

`possibleBiomes()` 因此必须是四元集合(easy/medium/hard/wall)——少一个 `wall`,原版在校验或 spawn 预计算阶段解析 `Holder` 就会报错。

### 6.4 区域到 biome 的映射(BiomeSource)

状态:DECIDED(R2)

为复用原版 mob spawn 配置、环境效果与刷怪光照接口,三难度区各映射一个自定义 biome,区外映射 `mining_wall`,由自定义 `MiningBiomeSource`(第五章注册)按 XZ 查 region 返回:

```java
// MiningBiomeSource#getNoiseBiome:参数 x,y,z 是 1/4 区块(biome 分辨率)坐标,
// 需经 QuartPos.toBlock 还原方块坐标;y 直接丢弃 —— R2 下整列同一 biome。
Difficulty d = RegionLayout.current().difficultyAt(QuartPos.toBlock(x), QuartPos.toBlock(z));
if (d == null) return wall;            // 缓冲带 + 网格外 -> mining_wall(surface_rule 填纯基岩)
return switch (d) {
    case EASY -> easy;
    case MEDIUM -> medium;
    case HARD -> hard;
};
```

要点:

- `BiomeSource` 只做一次 AABB 包含判定,不跑跨区块算法,也不依赖任何服务端单例(客户端反序列化 Codec 后同样会调用它,依赖单例即 NPE 崩客户端)。
- `RegionLayout` 是 `core` 里的纯静态 `volatile` 快照,由 `InstanceManager` 在启动重建与每次 `slideRegion` 后写入;类初始化时的默认快照按编译期几何构造,只保证服务端重建前与客户端反序列化时不 NPE。
- `possibleBiomes()` 返回 `Stream.of(easy, medium, hard, wall)` 四元全集。
- 四个 biome 的 JSON 路径:`src/main/resources/data/miningdim/worldgen/biome/` 下的 `mining_easy.json`、`mining_medium.json`、`mining_hard.json`、`mining_wall.json`。前三者的 biome 字段重点配置:

| biome 字段 | Easy | Medium | Hard | 作用 |
| --- | --- | --- | --- | --- |
| `spawners` | 全部为空(含 monster) | 同左 | 同左 | 三难度的原版自然刷怪基线一律清零,刷怪完全由第十章的压力系统显式接管(这正是"自定义 biome 而非复用原版"的首要理由) |
| `features` | 原版基础矿 + `miningdim:misc_*` 石材点缀 + 两种静态陷阱 | 增加金/红石/青金石与 `ore_bauxite/borax/tin/silver_medium`,陷阱增加 TNT 矿脉与岩浆囊 | 钻石/远古残骸/绿宝石 + 全部能源矿 hard 变体 + 四种陷阱 + 岩浆泉 | 矿物与静态陷阱全部走 `PlacedFeature`(见 5.5) |
| `carvers.air` | `minecraft:cave` / `cave_extra_underground` / `canyon` | 同左 | 同左 | 洞穴本体由原版 carver 挖出(见 7.0.1) |
| `effects` | `fog_color` / `sky_color` / `water_color` / `water_fog_color` 四项,较亮 | 中 | 较暗 | 配合 danger 光照下降的视觉氛围。三份 JSON 都没有配 `music` / `ambient_sound`,氛围音效是待补项 |
| `temperature` / `downfall` | 固定值(地下无降水,`has_precipitation=false`) | 同左 | 同左 | 仅影响渲染 |

`mining_wall` 不在上表内:它的 `carvers.air` 与 `features` 都是空列表,`spawn_costs` 为空,唯一作用是给 `surface_rule` 提供"这一列填基岩"的判据。

- biome 选择"自定义"而非复用原版(如 `minecraft:dripstone_caves`)的理由:需独立控制 `spawners`(给压力系统留干净基线)、`effects`(danger 视觉)与 `features`(难度矿表),复用原版会带入不需要的特性与刷怪表。复用原版 biome 标 REJECTED。

### 6.5 可雕刻 Y 区间与难度边界

状态:DECIDED(R2 重写)

R2 之后不存在难度子盒,因此也不存在"随机游走必须内收于子盒"这类约束。现行的 Y 约束只有两条,且都由数据包承担:

| 约束 | 生效范围 | 落地手段 |
| --- | --- | --- |
| 可雕刻 Y 区间 | 整块 region 全高 `local_y 0..191`(`worldY -64..127`) | 原版 carver 自身的 Y 区间配置 + `surface_rule` 的上下基岩渐变兜底 |
| 上下封边不被咬穿 | 底部 `above_bottom 0..5`、顶部 `below_top 0..5` | `vertical_gradient` 基岩规则在 `surface_rule` 里优先于其他分支执行,carver 碰基岩即停 |
| 难度之间不连通 | 三块 region 之间的缓冲带与网格外 | `mining_wall` 群系整列基岩(6.3);该群系 `carvers.air` 为空,不会被挖穿 |

SUPERSEDED:原 6.5 的"子盒 local_y / 随机游走允许 local_y / 安全内边距 4 格 / 竖井仅由 ConnectivityFix 开口"整表随子盒模型与离线管线一并作废。跨难度竖井这一概念也不再存在——三块 region 在世界里相距 288 格且之间是纯基岩,物理上无法互通,换难度只能走入场流程。

---

## 七、矿洞生成系统

状态:7.0 为现行实现(DECIDED);7.1 至 7.9 为已判废的离线预生成方案(SUPERSEDED,F021/F032,2026-08-17 起),整体保留作设计演进归档,严禁当作实现参考。

判废的边界很清楚:`MiningChunkGenerator`、`MiningVoxelLookup`、`IOfflineGenerator`、`OfflineCaveGenerator`、`GenerationScheduler` 这几个类在仓库中已全部不存在(`grep -rn "class MiningChunkGenerator" src/` 零命中);`core/VoxelOccupancy.java`、`ore/OreGenerator.java`、`trap/StaticTrapPlacement.java` 等接口文件还在,但其 javadoc 里点名的消费方已被删除,属已知过期注释。

### 7.0 现行实现: 原版 `minecraft:noise` 按需生成(DECIDED)

#### 7.0.1 总体形态

维度地形完全交给原版生成链,本 mod 只提供数据与一个 `BiomeSource`:

```text
玩家进入/区块被 ticket 拉起
        |
        v
原版 ServerChunkCache 推进 ChunkStatus 状态机(逐区块、异步、互相隔离)
        |
        +-- noise    : NoiseBasedChunkGenerator 读 miningdim:mining noise_settings
        |              final_density = 1 -> 整块 region 先填满 default_block(石头)
        |
        +-- surface  : surface_rule 按 biome 分支换基材 / 填基岩(6.3)
        |              biome 由 MiningBiomeSource 判定(6.4)
        |
        +-- carvers  : 原版 cave / cave_extra_underground / canyon 挖出洞穴本体
        |
        +-- features : biome features 列表铺矿、放石材点缀、布静态陷阱(5.5)
        |
        v
区块 FULL,玩家可落地
```

本 mod 在这条链上的全部介入点只有三处:提供 `noise_settings`/`biome`/`feature` 三类 datapack JSON、注册 `MiningBiomeSource` 的 Codec、以及经 `RegionLayout` 告诉 `BiomeSource`"三块 region 现在在哪"。区块填充回调、carver、feature 的执行一律是原版行为,本 mod 不覆写、不 mixin。

#### 7.0.2 `noise_settings/mining.json` 关键字段

| 字段 | 值 | 作用 |
| --- | --- | --- |
| `sea_level` | -65 | 低于 `min_y`,等价"无海平面",避免原版在空腔里灌水 |
| `aquifers_enabled` / `ore_veins_enabled` | 均 false | 关掉含水层与原版矿脉,矿物完全由 biome features 决定 |
| `default_block` / `default_fluid` | `minecraft:stone` / `minecraft:air` | 默认基材石头;默认流体空气(与无海平面配套) |
| `noise.min_y` / `noise.height` | -64 / 192 | 必须与 `dimension_type` 同值(4.3) |
| `noise.size_horizontal` / `size_vertical` | 1 / 2 | 噪声单元尺寸;在 `final_density` 恒为常数时不影响结果 |
| `noise_router.final_density` | 1 | 恒正 = 整个维度先填成实心石头。这是"先填满再挖空"模型的关键一行 |
| `noise_router` 其余项 | 全 0 | 关掉大陆性/侵蚀/深度/山脊/温度/植被等一切原版地表噪声 |
| `spawn_target` | `[]` | 不给原版出生点搜索任何目标,出生由本 mod 的 SpawnSystem 决定 |
| `surface_rule` | 见 6.3 | 基岩封顶封底 + `mining_wall` 整列基岩 + 难度基材分支 |

#### 7.0.3 数据包侧的矿物与陷阱

`configured_feature` 与 `placed_feature` 两个目录各有一组同名条目,手写部分在 `src/main/resources/data/miningdim/worldgen/` 下:

| 类别 | 条目 | 说明 |
| --- | --- | --- |
| 石材点缀 | `misc_andesite` / `misc_granite` / `misc_diorite` / `misc_tuff` / `misc_dirt` / `misc_gravel` | 三难度共用 |
| 难度专属点缀 | `misc_amethyst_geode`(Medium / Hard)、`misc_spring_lava`(仅 Hard) | 紫晶洞从 Medium 起才有;岩浆泉只在 Hard |
| 本 mod 矿物 | `ore_ancient_debris` / `ore_emerald`(仅 Hard) | 原版同名矿走 `minecraft:ore_*` 现成条目,不重复定义 |
| 能源类矿物 | `ore_bauxite_*` / `ore_borax_*` / `ore_tin_*` / `ore_silver_*` / `ore_nickel_hard` / `ore_chromium_hard` / `ore_tungsten_hard` | 由 datagen 产出,落在 `src/generated/resources/` 下(见 5.4) |
| 静态陷阱 | `trap_fake_ore` / `trap_collapsing_tunnel` / `trap_tnt_vein` / `trap_lava_pocket` | Easy 只挂前两种(非致死),Medium/Hard 四种齐全 |
| 原版结构 | `minecraft:monster_room` / `monster_room_deep` | 三难度共用的刷怪笼房间,是本维度唯一保留的原版结构类 feature |

难度差异由"每个 biome 的 features 列表挂哪些条目"表达,不再由 Java 侧的权重表决定(第八章因此降级为平衡参考)。

#### 7.0.4 确定性(D3)

确定性由原版保证:同一世界 seed + 同一区块坐标,`minecraft:noise` 与 carver、feature 的输出逐方块可复现。本 mod 的"换新图"因此不靠改 seed 实现,而靠 D3 滑动重置把 region 挪到一段全新的世界坐标——新坐标对应的原版噪声输入不同,地形自然全新,且绝不会撞上任何玩家挖过的旧图(13.4)。

`InstanceState.seed` 字段仍然保留并持久化(12.4 的派生规则不变),但它现在只用于本 mod 自有的确定性派生(陷阱触发、压力抖动等),不再参与地形生成。

#### 7.0.5 已知能力退化(据实报备)

| 原承诺 | 现状 | 影响与缓解 |
| --- | --- | --- |
| C4 矿洞 100% 连通(BFS + A* 打通) | 已取消。原版 carver 不提供任何全局可达保证,可能产生与主洞系不连通的孤立空腔 | 出生点安全性改由 SpawnSystem 在已就绪的真实区块上环形扫描 + 3x3 兜底平台保证(第十一章),玩家不会落进封闭空腔;"全图可达"不再是可验收指标 |
| 生成进度可见(`InstanceStatusS2C.genProgress`) | 无独立生成阶段,实例登记即 `READY`,进度恒为满 | GUI 侧不再显示生成进度条 |
| 单实例体素内存预算 | 不再分配任何整 region 体素结构 | 2.3 / 7.2 / 19.3 的相关阈值随之作废 |

### 7.1 [SUPERSEDED] 模型选择与理由(为何必须离线预生成)

#### 7.1.1 原版区块生成模型的硬约束

Minecraft 1.20.1 的区块生成是逐区块(per-chunk)、跨线程并行、互相隔离的。`ServerChunkCache` 通过 `ChunkMap` 调度,每个区块在 `ChunkStatus` 状态机(`empty -> structure_starts -> ... -> noise -> surface -> carvers -> features -> ...`)上推进,`noise` 阶段调用 `ChunkGenerator#fillFromNoise(Blender, RandomState, StructureManager, ChunkAccess)`,传入的是一个 `ProtoChunk`(`ChunkAccess` 的实现)。该模型对全局算法有三条致命约束:

| 约束 | 具体表现 | 对全局算法的影响 |
| --- | --- | --- |
| ProtoChunk 访问边界 | `fillFromNoise` 阶段只能安全读写当前 `ChunkAccess` 自身的 16x16 列;访问邻居区块需经 `WorldGenRegion`,且仅 `features` 阶段提供有限半径(`writeRadiusCutoff`)的邻居访问 | 随机游走 / 房间走廊会跨越任意多个区块,`noise` 阶段无合法 API 读写相邻区块体素 |
| 并行与生成顺序不确定 | 不同区块在不同 worker 线程(`Util.backgroundExecutor()`)上并行推进, 区块 A 与相邻区块 B 的生成先后无保证 | 跨区块全局 BFS / Flood Fill 需要"先看到全部体素再标记连通分量",而逐块生成时永远看不到全图 |
| 跨区块写入死锁风险 | 在区块回调里反向去拉取/锁定相邻区块会与 `ChunkMap` 的票据(ticket)/加载状态机争用,导致死锁或 `Accessing ... out of bounds` | 任何"在生成 A 时顺手改 B"的实现都会触发 MC 的越界断言或加载死锁 |

进一步地,`RandomState` / `PositionalRandomFactory` 是位置派生的随机源,为每一列 / 每一格按坐标独立 hash 出随机序列,本身不具备"按访问顺序串行推进的单一 Random"语义。若强行在区块回调里共享一个可变 `java.util.Random` 跨区块推进序列,会因并行访问产生数据竞争与不可复现结果——这正是评审标记的 Critical 缺口"随机源未种子化 / 跨区块共享可变 Random"。

结论(DECIDED): 随机游走、全局连通性 BFS、Room+Corridor 这三类骨架算法都是全局算法(需要一次性看到并写入整个 region 的体素),与 MC 逐块异步独立生成模型在 API、线程、确定性三个维度上根本不兼容,无法在 `fillFromNoise` 等区块回调内直接实现。REJECTED 方案: 在 `ChunkGenerator` 回调里跑随机游走 / 跨区块 BFS。

#### 7.1.2 离线预生成如何规避冲突

离线预生成把"算法"与"落方块"彻底拆成两个阶段,运行在两类完全不同的执行环境:

| 维度 | 全局算法阶段(离线) | 落方块阶段(区块回调) |
| --- | --- | --- |
| 运行位置 | 后台工作线程(自管线程池,非 MC chunk worker) | MC chunk worker 线程,`fillFromNoise` 内 |
| 数据载体 | 纯内存体素 bitset(整 region 一次性持有),不碰任何 MC 区块对象 | 仅当前 `ChunkAccess` 的 16x16 列 |
| 算法形态 | 随机游走 / 全局 BFS / A* 隧道,可任意跨"格"访问 | 无算法,纯查表 `bitset.get(idx)` 决定 air/solid |
| 随机源 | 单一 `RandomSource` 串行驱动 + 坐标派生(见 7.6) | 不使用随机源(完全确定的查表) |
| 跨区块依赖 | 在内存网格里自由跨区块,无 MC 加载约束 | 零跨区块依赖,每列独立可并行 |

由于落方块阶段退化为"查表",它天然满足 MC 区块系统的全部约束: 每列只读写自身、无跨区块访问、无共享可变状态、对同一坐标永远返回同一结果。全局算法的复杂度被前移到一个不受 MC 调度约束的纯计算阶段。当时的结论是"这是能同时满足全连通矿洞与 MC 区块生成契约的唯一可行架构"。

该结论已于 F021/F032 被推翻:实际取舍是放弃"全连通"这一承诺(见 7.0.5),换取整条自定义生成链的删除。现行方案见 7.0。

### 7.2 [SUPERSEDED] 数据表示与内存预算

#### 7.2.1 体素占用网格

单个实例(region)的几何用一个扁平一维布尔体素网格表示,语义为"该格是否为空气(可通行空腔)":

| 项 | 定义 | 建议初值(PENDING 待平衡校验) |
| --- | --- | --- |
| W | region 本地 X 跨度(方块) | 256 |
| H | region 本地 Y 跨度(方块) | 192(= `REGION_HEIGHT`) |
| D | region 本地 Z 跨度(方块) | 256 |
| 体素总数 | W * H * D | 256 * 192 * 256 = 12,582,912 |
| 存储 | `java.util.BitSet` 或 `long[]`,1 bit/格,true=air | 12,582,912 bit = 1,572,864 字节 ≈ 1.5 MiB |

数字订正说明:本节旧稿按 `H = 384` 计得 25,165,824 体素 / 3.0 MiB。`REGION_HEIGHT` 早期确为 384,后来收到 192(`MiningConstants.REGION_HEIGHT = 192`,与两份 JSON 的 `height` 三方对齐,见 4.3),由 384 派生的 7.2.2 内存预算、19.3 峰值门槛因此一并偏高一倍,已随本次修订统一按 192 重算。

索引公式(全章统一,不得改序):

```
idx = (y * D + z) * W + x        // x in [0,W), y in [0,H), z in [0,D)
```

选择 `(y, z, x)` 主序的理由: ChunkGenerator 落方块时按列(固定 x,z 遍历 y)访问,而连通性 BFS 与噪声雕刻按 y 层切片访问;`y` 作为最高维使"同一 Y 层的所有体素地址连续",利于层切片缓存局部性,同时列访问的跨步可接受。

边界与缓冲: 体素网格仅覆盖 region 的 bounding box 内部。box 外部恒为实心墙(见 7.7),不进入 bitset,不占内存。

#### 7.2.2 内存预算与上限

| 数据结构 | 单实例大小 | 说明 |
| --- | --- | --- |
| 空气占用 bitset(主网格) | ≈ 1.5 MiB | W*H*D/8 = 12,582,912/8 |
| 连通分量标号临时缓冲(int/格,仅 ConnectivityFix 期间存在) | ≈ 48 MiB(int[12.58M]) | 阶段结束即释放;可用 short[](≤32767 分量)降至 ≈ 24 MiB,或用第二个 bitset 仅标记"已访问"降至 1.5 MiB |
| BFS 队列(最坏全体素入队) | ≈ 25 MiB 峰值(int 索引队列) | 用环形 int 队列;见 7.7 节点上限 |
| 骨架节点图(房间 / 路径节点) | < 1 MiB | 数百到数千节点 |

内存治理决策(DECIDED):

- 主网格(1.5 MiB)在实例存活期间常驻;难度分配阶段算完后可考虑落盘缓存(SavedData / 区域文件),内存仅保留 LRU 热实例。
- 连通分量标号缓冲采用"双 bitset"实现(visited bitset + 当前分量 bitset),避免 48 MiB 的 int 标号数组,峰值额外内存压到 ≈ 3 MiB。分量体积统计在 BFS 过程中累加计数即可,不需保留每格标号。
- 全局并发预生成实例数上限 `maxConcurrentGen`(默认 2)由 7.9 调度器强制,峰值内存 ≈ maxConcurrentGen * (主网格 + BFS 峰值)。
- 单实例体素维度上限 `maxVoxelDims`(256x192x256)原计划写入 `ForgeConfigSpec`;该配置键最终未落地,`MiningServerConfig` 里没有它。

### 7.3 [SUPERSEDED] 三阶段管线

#### 7.3.1 固定阶段顺序

矿洞生成在内存网格上按**固定不可调换**的三阶段执行:

```
        instanceSeed + difficulty + regionBox
                      |
                      v
        +-----------------------------+
        |  Stage 1: Skeleton          |  输入: 空网格(全 solid)
        |  按难度选骨架算法           |  输出: 连通的主通道空气掩码 + 节点图
        +-----------------------------+
                      |  air mask v1
                      v
        +-----------------------------+
        |  Stage 2: NoiseCarving      |  输入: air mask v1
        |  3D 噪声扩挖/侵蚀细节        |  输出: air mask v2(更自然,可能引入新孤岛)
        +-----------------------------+
                      |  air mask v2
                      v
        +-----------------------------+
        |  Stage 3: ConnectivityFix   |  输入: air mask v2 + 出生点锚点
        |  连通分量标记/填岛/A*打通    |  输出: air mask final(保证主分量全连通)
        +-----------------------------+
                      |  air mask final (frozen)
                      v
            写入实例 bitset (immutable 视图供 ChunkGenerator 查表)
```

#### 7.3.2 为何连通性必须是最后一道闸(Major 缺口闭环)

评审标记"三阶段顺序逻辑回路(Major)"。锁定顺序为 Skeleton -> NoiseCarving -> ConnectivityFix,理由如下:

| 备选顺序 | 问题 |
| --- | --- |
| Carving 在 Connectivity 之前(被采纳) | NoiseCarving 会侵蚀出与主通道不相连的孤立空腔(新孤岛),但 ConnectivityFix 在其后运行,能把这些新孤岛一并纳入"填实或打通"的处理,连通承诺在最终输出上成立 |
| Connectivity 在 Carving 之前(REJECTED) | 先修连通再雕刻,Carving 又引入新孤岛且无后续闸门,最终网格不再保证连通,直接违反 D4 的"连通性作最后一道闸"承诺 |

铁律(DECIDED): ConnectivityFix 必须是写入 bitset 前的最后一个修改空气掩码的阶段。任何在其之后还会改动空气/实心的步骤(包括矿物替换、陷阱腔体)都不得新增"玩家可达性"层面的空腔——矿物只替换实心方块类型不改空腔拓扑;陷阱腔体若需新增空腔,必须在 ConnectivityFix 之前注入或自身保证就近接入主分量(见第七章与陷阱章的接口约定)。

#### 7.3.3 阶段接口

三阶段统一在实例本地坐标系的布尔网格上操作,接口签名(伪签名,实际为内部纯计算类):

```
interface VoxelStage {
    // grid: 当前空气掩码 (in/out);  ctx: 种子/难度/bbox/锚点
    void apply(VoxelGrid grid, GenContext ctx);
}
```

`GenContext` 字段: `instanceSeed(long)`, `difficulty(enum Easy/Medium/Hard)`, `regionBox(BoundingBox 本地)`, `spawnAnchor(本地坐标,Stage3 前由 SpawnSystem 预选候选见第九/十一章)`, `rootRandom(RandomSource)`。

### 7.4 [SUPERSEDED] 骨架算法选型表(Stage 1)

#### 7.4.1 难度到算法映射(DECIDED)

为回应 Minor 缺口"三种骨架混用",此处把每难度区绑定一个确定算法,统一接口、统一种子驱动,避免无序混用:

| 难度区 | 骨架算法 | 形态目标 | 关键参数(PENDING 待平衡) |
| --- | --- | --- | --- |
| Easy | Random Walk(多源随机游走隧道) | 自然蜿蜒洞穴,通道宽松,迷路风险低 | walkers=6, stepsPerWalker=W*1.5, tunnelRadius=2, branchProb=0.15 |
| Medium | Hybrid(Random Walk 主干 + 稀疏房间挂载) | 主干自然 + 若干房间节点,中等复杂度 | walkers=4, rooms=8, roomSize=5..9, corridorRadius=1.5 |
| Hard | Room+Corridor(地牢式,图连接房间) | 房间密集、走廊网格化、规整迷宫感 | rooms=18, roomSize=4..8, extraEdges=0.25(额外环边防止纯树状) |

三算法均实现同一接口 `SkeletonAlgo`:

```
interface SkeletonAlgo {
    // 输入: bbox + 派生 seed;  输出: 空气掩码 + 保证连通的节点图
    SkeletonResult generate(BoundingBox localBox, long skeletonSeed);
}
record SkeletonResult(VoxelGrid airMask, NodeGraph graph) {}
```

#### 7.4.2 骨架连通性的内建保证

无论哪种算法,Stage 1 必须输出一个**自身已连通**的骨架(节点图为连通图),为后续阶段提供一个明确的"主分量种子":

| 算法 | 连通性内建机制 |
| --- | --- |
| Random Walk | 所有 walker 从同一起点集合出发,或 walker 起点串联(第 i 个 walker 起点取自前序已挖路径上的点),保证轨迹并集连通 |
| Hybrid | 先生成主干 walk(连通),房间逐个用一条直/L 形走廊接到最近的已连通节点,挂载即连通 |
| Room+Corridor | 房间作为图节点,先用最小生成树(MST)连成树(保证连通),再按 extraEdges 比例加环边;每条边用走廊在网格上挖通 |

骨架节点图同时为 Stage 3 提供出生点锚点的落点参考: 出生候选点优先取自骨架房间 / 主干节点,确保候选点初始即在连通骨架上(降低 Stage 3 把出生点判为孤岛的概率)。

### 7.5 [SUPERSEDED] 连通性修复(Stage 3)

#### 7.5.1 连通分量标记(Critical / Major 缺口闭环)

回应 Critical 缺口"BFS 无 bounding box"与 Major 缺口"删除孤岛缺判据":

| 参数 | 取值 | 说明(DECIDED) |
| --- | --- | --- |
| 邻接类型 | 6-邻接(±x, ±y, ±z) | 玩家行走 / 可达性语义;不使用 18/26 邻接,因斜向不一定可走(D4) |
| BFS 边界 | region bbox 即硬边界 | box 外恒实心墙,BFS 访问到 box 边界自然终止,绝不越界(D4) |
| 越界处理 | 任何 `local 坐标 ∉ [0,W)x[0,H)x[0,D)` 的邻居直接跳过 | 等价于"墙",杜绝评审标记的"BFS 无边界"无限扩张 |
| 主分量锚点 | spawnAnchor(出生候选点) | 含 spawnAnchor 的连通分量即主分量(D4) |

标记流程:

1. 以 spawnAnchor 为起点做一次 6-邻接 BFS,标记出主连通分量(visited bitset),累加其体积 `mainVolume`。
2. 扫描全网格,对每个"是空气且未被主分量 visited"的格,作为新分量种子再做局部 BFS,得到该分量体积 `vol` 与其格集合(用临时 bitset)。
3. 对每个非主分量按 7.5.2 判据处理(填实或打通)。

#### 7.5.2 孤岛处理判据(Major 缺口闭环)

非主连通分量的处置规则,全部参数化:

| 判据 | 动作 | 参数(PENDING 待平衡) |
| --- | --- | --- |
| `vol < minIslandSize` | 填实(该分量所有格置 solid),从可达空间剔除 | minIslandSize=64(格) |
| `vol >= minIslandSize` | 用 A* 打通隧道接入主分量 | 见 7.5.3 |
| 分量含已声明的出生 / 关键候选点 | 强制打通(忽略 minIslandSize 下限) | 关键点不得被填实 |

填实是默认动作,避免地图布满无法到达的小空腔噪声;只有体积足够大(值得保留)的腔体才花成本打隧道。

#### 7.5.3 A* 隧道打通(DECIDED)

对需要保留的非主分量,选其与主分量之间"最近的一对表面点",用 A* 在实心区域里求一条低成本路径,沿路径挖半径 r 的隧道:

| 项 | 规则 | 参数(PENDING 待平衡) |
| --- | --- | --- |
| 端点选取 | 该分量边界格集合与主分量边界格集合中,曼哈顿距离最近的一对 `(pA, pB)` | 用分量边界格的空间哈希加速最近点对查询 |
| A* 代价 | g = 已挖实心格数;h = 到 pB 的曼哈顿距离;穿实心 cost=1,穿已有空气 cost=0 | 优先复用已有空腔,减少新挖体积 |
| A* 搜索域 | 限制在 region bbox 内(同 7.7 边界);越界格不可扩展 | 防止 A* 越界 |
| 隧道半径 | 沿路径每点挖半径 r 球形空腔 | tunnelRadius=1(直径 3,玩家 2 格高可走需保证至少一处 2 格净空,落方块时纵向补挖至 2 格) |
| 打通后校验 | 打通后该分量并入主分量 visited,继续处理下一个分量 | 全部处理完后做一次全局复核 BFS(见 7.6 回归) |

净空保证: 隧道半径 1 的球形腔在纯水平段可能只有局部 2 格高不连续,故落方块前对隧道中心线强制保证连续"头顶 2 格空气 + 脚下固体"(与第九章出生安全空间判据一致),避免打通后路径仍不可走。

#### 7.5.4 出生点硬约束声明(与第十一章呼应)

DECIDED 硬约束(本章声明,第九 / 十一章遵守): Stage 3 结束后,出生点与所有 spawn 候选点必须 ∈ 主连通分量。Stage 3 的复核 BFS 必须验证 `所有候选点 ∈ mainComponent`,否则该实例生成判 FAIL,触发回退(见 7.9)。即"出生点须 ∈ 主连通分量"是生成成功的必要条件,不是事后补救。

### 7.6 [SUPERSEDED] 确定性契约(D3,现行口径见 7.0.4)

#### 7.6.1 种子层级(Critical 缺口闭环)

回应 Critical 缺口"随机源未种子化":全 mod 随机严格分层,禁止任何未种子化或跨区块共享的可变 Random。

| 层级 | 定义 | 来源 |
| --- | --- | --- |
| masterSeed | 全 mod 主种子,持久化于 SavedData(D5) | 世界创建时确定 |
| instanceSeed | 单实例种子,持久且固定(D3/D6) | `instanceSeed = mix(masterSeed, instanceId)`(SplitMix64 风格混合,不用 seed++) |
| stageSeed | 各阶段派生种子 | `hash(instanceSeed, stageId)` |
| featureSeed | 分块 / 分特征派生种子 | `hash(instanceSeed, x, z, featureId)` |

派生哈希统一用确定性 64 位 finalizer(SplitMix64 / Murmur 风格,纯函数,跨 JVM 跨版本稳定),禁止用 `Objects.hash`(JDK 内部可变、不保证跨版本稳定)。

#### 7.6.2 单一串行随机源(DECIDED)

| 规则 | 说明 |
| --- | --- |
| 全局阶段单一 RandomSource | 每个 Stage 用一个由 stageSeed 构造的 `RandomSource`(`net.minecraft.world.level.levelgen.RandomSource`,如 `LegacyRandomSource` / `XoroshiroRandomSource`),在该阶段内串行推进 |
| 分块随机用派生 seed | 任何"按坐标 / 按特征"的随机一律 `hash(instanceSeed, x, z, featureId)` 现派生一个独立 RandomSource,用完即弃 |
| 禁止跨区块共享可变 Random | 严禁把一个 `Random` 实例在多个区块 / 多个 worker 间传递推进(D3) |
| 串行执行保证 | 三阶段在单个工作线程内串行执行,不在阶段内部再并行,确保 RandomSource 推进顺序确定 |

#### 7.6.3 逐方块可复现回归契约(DECIDED)

| 测试约定 | 内容 |
| --- | --- |
| 同种子双跑 diff | 用同一 `(instanceSeed, difficulty, regionBox)` 跑两次完整三阶段,对最终 bitset 做逐 bit XOR,必须全 0(逐方块一致) |
| 跨平台稳定 | 在不同 OS / JVM 上对固定 seed 的输出哈希(如 bitset 的 SHA-256)必须一致,验证派生哈希与 RandomSource 的可移植性 |
| 复核 BFS 确定 | Stage 3 复核 BFS 的连通判定结果对同 seed 必须一致(用于回归断言"出生点 ∈ 主分量") |
| 测试载体 | Forge GameTest(`@GameTest`)+ 纯单元测试(体素阶段不依赖 MC 世界,可纯 JVM 单测) |

回归断言示例(纯计算层,不依赖 MC 世界):

```
long seed = 0xC0FFEEL;
VoxelGrid a = pipeline.run(seed, Difficulty.HARD, box);
VoxelGrid b = pipeline.run(seed, Difficulty.HARD, box);
assertArrayEquals(a.toLongArray(), b.toLongArray());   // 逐 bit 一致
assertTrue(a.allCandidatesInMainComponent());          // 出生点 ∈ 主分量
```

### 7.7 [SUPERSEDED] bounding box 与边界语义(D4,现行口径见 6.3)

#### 7.7.1 边界即墙(Critical 缺口闭环)

回应 Critical 缺口"BFS 无 bounding box":

| 语义 | 规则(DECIDED) |
| --- | --- |
| box 外恒实心 | region bbox 之外的世界坐标恒为实心墙方块,不属于任何实例体素网格,ChunkGenerator 对这些坐标直接填 solid |
| BFS 天然终止 | 因 box 外为墙且 box 内坐标受 `[0,W)x[0,H)x[0,D)` 限制,6-邻接 BFS 触及边界即停,无需特判"未生成区块" |
| 禁运行时按需加载远区块 | 严禁为了做连通性判定而在运行时按需加载相邻 region 的远区块——全局算法只在内存网格上跑,内存网格自带边界(Major 缺口"未生成区块语义"闭环) |
| 未生成区块语义 | 体素网格在实例分配时一次性整体算完(7.9),不存在"半生成"中间态;ChunkGenerator 查表前,实例 genState 必须为 READY,否则该区块填占位实心并标记重生成 |

#### 7.7.2 多实例 region 布局(D1/D6)

| 项 | 规则 |
| --- | --- |
| 网格切分 | miningdim:mining 维度内按固定网格切成互不重叠的 region,每实例占一个 region bounding box |
| 缓冲带 | 实例间留 >= 1 区块(16 方块)实心缓冲带,缓冲带恒 solid,确保相邻实例体素网格物理隔离、BFS 互不串扰 |
| 本地坐标系 | 每个 region 有独立本地原点(box.minX/minY/minZ);算法全程用本地坐标 `[0,W)x[0,H)x[0,D)` |
| 重置粒度 | 重置单实例 = 仅删除 / 重生成该 region 的区块(D1),不影响其他 region |

#### 7.7.3 BFS 体素上限与防 OOM(Minor 缺口闭环)

虽为离线计算,仍设硬上限防止异常输入导致 OOM / 死循环:

| 上限 | 取值(PENDING 待校验) | 触发动作 |
| --- | --- | --- |
| maxBfsNodes(单次 BFS 最大访问节点) | W*H*D(= 体素总数,12.58M) | 理论上界即全网格;BFS 访问计数超过即判异常并 FAIL |
| maxTunnelLength(单条 A* 隧道最大长度) | 512 格 | 超长隧道说明分量过远,放弃打通改为填实 |
| maxStageMillis(单阶段墙钟超时) | 5000 ms / 阶段(PENDING) | 超时回退更小 box / 更简单算法(7.9) |
| BFS 队列实现 | 环形 int 索引队列,容量 = 体素总数 | 用 int 索引(非 BlockPos 对象)降内存与 GC 压力 |

由于算法在工作线程离线运行,maxStageMillis 仅作熔断,不阻塞主线程;分帧不必要(单实例整体一次算完),但超时熔断必要。

### 7.8 [SUPERSEDED] ChunkGenerator 查表接入

#### 7.8.1 MiningChunkGenerator 职责边界(REJECTED,F021/F032)

本小节至 7.8.4 描述的自定义生成器整体作废:`MiningChunkGenerator` 类已删除,`CHUNK_GENERATOR` 注册表无任何 `miningdim` 条目,`dimension/mining.json` 的 `generator.type` 现为 `minecraft:noise`(4.4)。下列方法覆写表只作"当年打算怎么接"的归档,任何按它去实现或验收的行为都是错的。

原文:`MiningChunkGenerator extends net.minecraft.world.level.chunk.ChunkGenerator`,通过 `Codec` 注册到 `BuiltInRegistries.CHUNK_GENERATOR`(经 `RegisterEvent`,见维度章)。其唯一职责: 把世界坐标映射到 region 本地坐标,查 bitset,填 air 或 solid。绝不在任何回调里运行跨区块算法。

#### 7.8.2 1.20.1 ChunkGenerator 关键方法处理策略

下表为 1.20.1 `ChunkGenerator` 抽象方法 / 关键覆写点在本设计下的处理(方法签名以 1.20.1 official/parchment 为准):

| 方法 | 1.20.1 签名要点 | 本设计处理 |
| --- | --- | --- |
| `fillFromNoise` | `CompletableFuture<ChunkAccess> fillFromNoise(Blender, RandomState, StructureManager, ChunkAccess)` | 核心落方块点。对该 chunk 内每个 `(x,z)` 列,逐 y 查 region bitset: air -> `AIR`,solid -> 按难度分层填 `STONE` / `DEEPSLATE`(见第四 / 六章);box 外坐标直接填 solid。纯查表,无算法。`CompletableFuture.completedFuture(chunk)` 同步返回(查表无需异步) |
| `buildSurface` | `void buildSurface(WorldGenRegion, StructureManager, RandomState, ChunkAccess)` | 留空(no-op)。矿洞无地表概念,表面规则不适用;不调用 `SurfaceRules` |
| `applyCarvers` | `void applyCarvers(WorldGenRegion, long seed, RandomState, BiomeManager, StructureManager, ChunkAccess, GenerationStep.Carving)` | 留空(no-op)。雕刻已在离线 NoiseCarving 阶段完成,禁止原版 carver 二次破坏拓扑(否则破坏连通承诺) |
| `applyBiomeDecoration` / features | 经 `BiomeGenerationSettings` | 矿物 / 陷阱 / 装饰通过受控 `PlacedFeature` 或离线注入实现,且不得新增可达性空腔(见 7.3.2 铁律) |
| `getBaseHeight` | `int getBaseHeight(int x, int z, Heightmap.Types, LevelHeightAccessor, RandomState)` | 返回该列 region 内"最高实心面"的世界 Y;box 外列返回 box 顶(全实心)。供 spawn / 结构查询用 |
| `getBaseColumn` | `NoiseColumn getBaseColumn(int x, int z, LevelHeightAccessor, RandomState)` | 按 bitset 该列逐 y 生成 `BlockState[]`(air/solid),包成 `NoiseColumn`。与 fillFromNoise 同源查表,保证一致 |
| `createStructures` / `createReferences` | 结构起点 / 引用 | 不放置原版结构(矿洞为自定义离线生成);可留空或返回空集 |
| `getGenDepth` / `getMinY` / `getSeaLevel` | 高度参数 | 由维度 `dimension_type` JSON 与本设计 H/min_y 决定;`getSeaLevel` 返回 region 底(无海) |
| `codec` | `protected Codec<? extends ChunkGenerator> codec()` | 返回注册的 `MiningChunkGenerator.CODEC`(`Codec`),编码 BiomeSource + 维度参数引用 |
| `getSpawnHeight` | `int getSpawnHeight(LevelHeightAccessor)` | 不用于实例出生(出生由 SpawnSystem 在主分量内选点,见第九章);返回 box 内安全默认 Y |

#### 7.8.3 世界坐标 <-> region 本地坐标转换

| 转换 | 公式 | 说明 |
| --- | --- | --- |
| 世界 -> 本地 | `lx = wx - box.minX; ly = wy - box.minY; lz = wz - box.minZ` | box 由 InstanceState.regionBox 提供(D6) |
| 本地越界判定 | `lx ∉ [0,W) ∨ ly ∉ [0,H) ∨ lz ∉ [0,D)` => box 外 => 填 solid | 含缓冲带,天然实心墙(7.7) |
| 本地 -> 索引 | `idx = (ly * D + lz) * W + lx` | 与 7.2 同一公式,全章唯一 |
| 区块 -> region 归属 | 由 chunk 世界坐标落在哪个 region 网格单元决定(7.7.2 固定网格) | InstanceManager 提供 chunkPos -> instanceId 反查 |

落方块伪逻辑(运行在 fillFromNoise,纯查表):

```
for (lx in 0..15) for (lz in 0..15) {            // chunk 内 16x16 列
    wx = chunkMinX + lx; wz = chunkMinZ + lz;
    instance = InstanceManager.instanceAt(wx, wz);
    if (instance == null || instance.genState != READY) {
        fillColumnSolid(chunk, lx, lz);            // 缓冲带 / 未就绪 -> 实心
        continue;
    }
    box = instance.regionBox;
    for (wy in box.minY..box.maxY) {
        boolean air = instance.voxels.get(localIdx(wx, wy, wz, box));
        BlockState s = air ? AIR : solidFor(difficultyZoneAt(wy), instance);  // STONE/DEEPSLATE
        chunk.setBlockState(new BlockPos(wx, wy, wz), s, false);
    }
    // box.minY 以下 / maxY 以上: 实心墙(封顶封底)
}
```

#### 7.8.4 BiomeSource 接入

本小节是 7.8 里唯一存活下来的部分,但判据已改:`BiomeSource` 仍然存在且仍经 `RegisterEvent` 注册 Codec,只是不再"按世界 Y / region 分层"返回 biome,而是按 XZ 查 region(R2),且群系从 3 个增为 4 个(多一个 `mining_wall`)。现行规格见 6.4,本处不再重复。

### 7.9 [SUPERSEDED] 生成调度

#### 7.9.1 离线生成时序(D2/D8)

| 步骤 | 线程 | 动作 |
| --- | --- | --- |
| 1 实例分配 | 主线程 | InstanceManager 分配 instanceId / instanceSeed / regionBox,InstanceState.genState = PENDING,持久化(D5/D6) |
| 2 提交生成任务 | 主线程 -> 工作线程池 | 向自管 `ExecutorService`(非 MC chunk worker)提交 `VoxelGenTask(instanceSeed, difficulty, box)` |
| 3 三阶段计算 | 工作线程 | 串行跑 Skeleton -> NoiseCarving -> ConnectivityFix(7.3),全程内存网格,不碰 MC 世界 |
| 4 复核与冻结 | 工作线程 | 复核 BFS(出生点 ∈ 主分量,7.5.4);通过则把 bitset 冻结为 immutable |
| 5 回主线程提交 | 工作线程 -> `server.execute()` | 通过 `MinecraftServer#execute(Runnable)` 把"genState = READY + 触发区块加载/出生"回主线程(D8: 世界写操作必经主线程) |
| 6 玩家进入 | 主线程 | genState=READY 后才允许传送玩家进入(7.9.3);未就绪则玩家在等待态 / 大厅 |

#### 7.9.2 并发与限流

| 项 | 规则 | 参数(PENDING) |
| --- | --- | --- |
| 工作线程池 | 固定大小 `ExecutorService`,与 MC chunk worker 隔离 | poolSize = maxConcurrentGen = 2 |
| 全局并发生成上限 | 同时计算的实例数 <= maxConcurrentGen,超出排队 | 见 D6 全局实例上限 |
| 内存上限联动 | maxConcurrentGen * 单实例峰值内存 <= genMemoryBudget | genMemoryBudget=256 MiB |
| 取消 | 实例在生成途中被销毁(玩家全退 + 超时回收)时,Future.cancel,工作线程检查中断点尽快退出 | 阶段间设中断检查点 |

#### 7.9.3 超时回退策略(DECIDED)

回应"超时回退更小 box / 更简单算法":

| 触发 | 回退动作 | 顺序 |
| --- | --- | --- |
| 单阶段超 maxStageMillis | 记录 WARN,按下表降级重试一次 | 1 |
| 降级 1: 算法简化 | Hard 的 Room+Corridor 降级为 Hybrid,Hybrid 降级为 Random Walk(更快、更少节点) | 2 |
| 降级 2: 缩小 box | 体素维度按比例缩小(如 0.75x),减少体素总数与 BFS 规模 | 3 |
| 降级 3: 兜底房间 | 仍超时则生成一个保证连通的极简结构(单一大房间 + 直线主廊 + 出生点),genState=READY_FALLBACK | 4 |
| 复核 FAIL(出生点 ∉ 主分量) | 重跑 ConnectivityFix(强制把出生点所在分量打通为主分量);仍 FAIL 则降级 3 兜底 | 与超时同级 |

兜底结构保证: 降级 3 的极简结构在算法上恒连通(构造即连通,无需 BFS 验证),确保任何输入下实例都能进入 READY,玩家永不卡在"永远生成不出来"的状态。READY_FALLBACK 实例可在后台空闲时异步重生成为完整结构并热替换(可选,PENDING)。

#### 7.9.4 与实例生命周期的衔接

| 阶段 | genState | 玩家可进入 | 说明 |
| --- | --- | --- | --- |
| 分配后 | PENDING | 否 | 已占 regionBox,体素未算 |
| 计算中 | GENERATING | 否 | 工作线程跑三阶段 |
| 就绪 | READY / READY_FALLBACK | 是 | bitset 冻结,可查表落方块 + 传送 |
| 重置中 | RESETTING | 否 | 删除该 region 区块 + 用新 instanceSeed 重算(D1/D6),回到 GENERATING |
| 回收 | RECYCLED | 否 | refCount=0 且超时,释放体素内存 + 标记 region 可复用 |

genState 持久化于 InstanceState(D5),服务器重启时: READY 实例从存档区块直接复用(体素 bitset 可重算或从缓存恢复);PENDING / GENERATING 中断态在启动重建时重新提交生成任务或判孤儿清理(D5)。

---

## 八、矿物生成与数值表

状态:本章的数值意图(难度梯度、稀有矿归属、配额封顶的必要性)仍然有效并已在数据包里落地;但"铺矿算法"这一层(8.2 权重轮盘、8.4 配额、8.5 成簇、8.6 壁面统计、8.8 落地清单)描述的离线执行路径已随 F021/F032 作废,全部标 SUPERSEDED。

现行铺矿形态见 5.5 与 7.0.3:矿物由数据包 `placed_feature` 在原版 feature 阶段逐区块放置,难度差异由"每个难度 biome 的 features 列表挂哪些条目"表达。`ore/OreType.java` 里的权重/密度/配额枚举字段仍在仓库中,但已无生成期消费方,只能当平衡参考读,不能当实现读——尤其注意其中能源类矿物的旧离线配额已全部置零(类注释明确"真实生成完全由 datapack worldgen 接管")。

本章只定义"产出端"数值;经济侧的回收/销毁/产出上限与重置成本在第十八章统一收口,本章相关上限标注交叉引用。

### 8.1 设计目标与不变量

| 编号 | 不变量 | 状态 | 说明 |
| --- | --- | --- | --- |
| OG-1 | 每实例每矿物有硬上限(maxCount),铺矿计数到达上限即停 | SUPERSEDED | 意图仍在(防"刷神种"),但实现手段换成 `placed_feature` 的每区块放置修饰器(`minecraft:count` + `in_square` + `height_range` + `biome`),是"每区块期望个数"而非实例级配额;实例级产出封顶改由第十八章的经济软上限承担 |
| OG-2 | 矿物只铺在体素网格的实心墙体素(occupied=true)且与空气可达面相邻 | SUPERSEDED | 原版矿物 feature 不区分"贴壁/深埋",埋在实心里的矿石确实会存在。这是换用数据包路径后接受的品质折价 |
| OG-3 | 铺矿在 ConnectivityFix 之后,只在主连通分量可触达壁面铺设 | REJECTED | `ConnectivityFix` 与主连通分量概念已随离线管线删除(7.0.5);feature 阶段无从知道"玩家能否走到这里" |
| OG-4 | 同 instanceSeed 铺矿结果逐方块一致 | SUPERSEDED | 确定性仍然成立,但改由原版保证(世界 seed + 区块坐标),与 `InstanceState.seed` 无关(7.0.4) |
| OG-5 | 难度只改权重与配额,不改算法 | DECIDED | 仍然成立:难度差异只体现在各难度 biome 的 features 列表与各 feature 的放置参数上 |

### 8.2 权重模型

基础公式(沿用原文档玩法意图):

```
effectiveWeight(ore, difficulty) = baseWeight(ore) * difficultyMultiplier(ore, difficulty)
```

`effectiveWeight` 是矿物在"加权随机抽取下一个待铺矿种"时的相对概率权重,不是绝对数量;绝对数量由 8.4 的配额(quota)封顶。抽取算法:对一个候选铺矿点,先用配额未满的矿种集合构造加权轮盘(权重 = effectiveWeight),抽中矿种,再按矿脉成簇规则(8.5)落簇。

baseWeight 与 difficultyMultiplier 建议初值(平衡数值 PENDING 待校验,先给可运行初值):

| 矿种(方块) | baseWeight | mult Easy | mult Medium | mult Hard | 备注 |
| --- | --- | --- | --- | --- | --- |
| coal_ore / deepslate_coal_ore | 100 | 1.30 | 1.00 | 0.70 | 低区高产,深区不再是主要收益 |
| copper_ore / deepslate_copper_ore | 60 | 1.10 | 1.00 | 0.80 | 平价工业矿,全区稳定 |
| iron_ore / deepslate_iron_ore | 70 | 1.20 | 1.10 | 0.90 | 主力金属,各区都给 |
| gold_ore / deepslate_gold_ore | 25 | 0.40 | 1.00 | 1.60 | 难度越高越多,Hard 主收益之一 |
| redstone_ore / deepslate_redstone_ore | 40 | 0.60 | 1.00 | 1.20 | 中后期需求,深区偏多 |
| lapis_ore / deepslate_lapis_ore | 18 | 0.80 | 1.00 | 1.10 | 附魔向,平缓 |
| emerald_ore / deepslate_emerald_ore | 6 | 0.20 | 0.60 | 1.40 | 稀有,几乎只在 Hard 见 |
| diamond_ore / deepslate_diamond_ore | 8 | 0.15 | 0.70 | 2.20 | Hard 核心稀有收益 |
| ancient_debris | 2 | 0.00 | 0.10 | 1.00 | 仅 Hard,极低;经济敏感见 8.4 硬上限 |

矿种枚举落地为 `enum OreType`,字段 `Block stoneVariant`、`Block deepslateVariant`、`int baseWeight`、`float[] mult`(索引对应 `Difficulty.ordinal()`)。`stoneVariant`/`deepslateVariant` 按落点 Y 是否 < 0(或按 region 内深板岩阈值)二选一,与第四章分层一致;阈值常量 `DEEPSLATE_Y_THRESHOLD = 0`(PENDING,可按 region 高度重定)。

矿种方块引用 `net.minecraft.world.level.block.Blocks` 真实字段(`Blocks.COAL_ORE`、`Blocks.DEEPSLATE_DIAMOND_ORE`、`Blocks.ANCIENT_DEBRIS` 等),不新注册矿石方块。

### 8.3 难度分布意图核对(对照原文档第6节)

| 难度 | coal | iron | gold | diamond | emerald | ancient_debris | 设计意图 |
| --- | --- | --- | --- | --- | --- | --- | --- |
| Easy | 多 | 多 | 极少 | 极少 | 几乎无 | 无 | 新手区,保底金属流 |
| Medium | 中 | 稳定 | 出现 | 偶现 | 少 | 极低 | 过渡区,钻石开始可期 |
| Hard | 少 | 中 | 多 | 多 | 见到 | 极低但存在 | 高风险高收益,稀有矿主产区 |

该表与 8.2 的乘子方向一致,作为人工校验锚点:若调参后某难度实际产出与本表定性描述冲突,以本表意图为准回退乘子。

### 8.4 每实例矿物配额(核心:消除"无上限刷神种")

每实例在分配阶段(D6,确定 `regionBox` 与 `instanceSeed` 后)为每个矿种计算一个目标产量 `targetCount` 与硬上限 `maxCount`。配额按"每千个可铺壁面体素"的密度归一,再乘难度系数,使不同 region 体积下密度一致、副本间方差受控。

```
wallBudget       = 主连通分量可铺壁面体素数(8.6 统计得出)
densityPerK(ore) = 每 1000 壁面体素的目标矿块数(下表)
rawTarget(ore)   = densityPerK(ore) * wallBudget / 1000
targetCount(ore) = clamp(round(rawTarget * jitter), 0, maxCount(ore))
jitter           = 0.90 + 0.20 * deriveFloat(instanceSeed, "quota", oreOrdinal)   // [0.90,1.10)
```

`deriveFloat` 由 `hash(instanceSeed, salt, ordinal)` 产生 `[0,1)` 浮点(D3),保证同 seed 配额一致且实例间有受控抖动。`jitter` 仅 ±10%,把副本间方差压在窄带内(消除 Major: 密度无上限)。

densityPerK 与 maxCount 建议初值(PENDING 待校验;maxCount 是评审要求的硬上限):

| 矿种 | densityPerK Easy | densityPerK Medium | densityPerK Hard | maxCount Easy | maxCount Medium | maxCount Hard |
| --- | --- | --- | --- | --- | --- | --- |
| coal | 28 | 22 | 14 | 900 | 700 | 480 |
| copper | 16 | 16 | 13 | 520 | 520 | 440 |
| iron | 20 | 20 | 18 | 640 | 640 | 600 |
| gold | 3 | 7 | 13 | 110 | 230 | 420 |
| redstone | 8 | 12 | 16 | 260 | 380 | 520 |
| lapis | 4 | 5 | 6 | 140 | 170 | 210 |
| emerald | 0.3 | 1.0 | 3.0 | 12 | 36 | 96 |
| diamond | 0.5 | 1.6 | 4.5 | 18 | 56 | 150 |
| ancient_debris | 0 | 0.15 | 0.6 | 0 | 6 | 20 |

注:densityPerK 与 8.2 的 effectiveWeight 是双轨控制——effectiveWeight 决定"先铺哪种"的抽取顺序与混合手感,densityPerK/maxCount 决定"每种总量"的硬封顶。两轨需大致同向(高乘子矿种配同向更高的密度),否则会出现"权重高但配额低很快铺满后被跳过"的退化。`ancient_debris` 的 maxCount 与产出价值同时受第十八章经济上限二次约束,本章 maxCount 为产出端初值,十八章可下调不可上调。

### 8.5 矿脉成簇与铺设规则

原版矿石按矿脉(vein)成簇分布,纯单点散铺手感差。铺矿以"簇"为单位消耗配额:

| 参数 | coal | copper | iron | gold | redstone | lapis | emerald | diamond | ancient_debris |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| veinSizeMin | 4 | 3 | 3 | 2 | 4 | 3 | 1 | 1 | 1 |
| veinSizeMax | 12 | 8 | 8 | 5 | 9 | 6 | 2 | 4 | 2 |

落簇算法(确定性,工作线程):

1. 在主连通分量可铺壁面体素集合中,用 `deriveLong(instanceSeed, "veinAnchor", placedCount)` 选一个未占用锚点体素。
2. 抽矿种(8.2 加权轮盘,已满配额矿种剔除)。
3. `veinSize = veinSizeMin + deriveInt(...) % (veinSizeMax - veinSizeMin + 1)`,但不超过该矿种 `maxCount - placed(ore)`。
4. 从锚点做有界 BFS(只沿 occupied 且贴空气面的体素扩展),取前 `veinSize` 个体素写入铺矿表 `Map<BlockPos, OreType>`(或并行的扁平数组),`placed(ore) += veinSize`。
5. 重复,直到所有矿种配额满或可铺壁面耗尽。耗尽时按实际铺设量收尾,不报错。

SUPERSEDED:铺矿表原是离线产物,由 `MiningChunkGenerator` 在区块填充阶段查表替换方块。该消费方已删除(7.8),铺矿表现在没有任何落子路径;实际铺矿走 `placed_feature`(7.0.3)。

### 8.6 可铺壁面体素统计

`wallBudget` 在 ConnectivityFix 后单遍扫描主连通分量得到:

```
对每个 occupied 体素 v:
  若 v 的 6-邻接中存在属于主连通分量的空气体素(air 且 mainComponent):
     wallBudget++  且标记 v 为 placeable
```

`placeable` 集合即落簇候选池。该统计 O(N) 单遍,与连通分量标记可合并到同一遍扫描(第七章 BFS 收尾时顺带产出),避免二次遍历整网格。

### 8.7 收益推演例子(Hard, 单实例)

设某 Hard 实例参与铺矿的可达子区体积约 80x40x80(说明性子盒示例,非 region 全尺寸,region 全尺寸以第四章 4.2 为准),体素总数 256000,经 NoiseCarving 后空气率约 35%,实心壁体素约 166400,其中贴空气可挖面 `wallBudget ≈ 24000`(经验比例,PENDING 待真实生成统计校正)。

| 矿种 | densityPerK Hard | rawTarget = d*24000/1000 | maxCount Hard | targetCount(取 jitter≈1.0) | 估算簇数(均簇大小) |
| --- | --- | --- | --- | --- | --- |
| diamond | 4.5 | 108 | 150 | 108 | ~43 簇(均 2.5) |
| gold | 13 | 312 | 420 | 312 | ~89 簇(均 3.5) |
| iron | 18 | 432 | 600 | 432 | ~78 簇(均 5.5) |
| emerald | 3.0 | 72 | 96 | 72 | ~58 簇(均 1.5) |
| ancient_debris | 0.6 | 14.4 | 20 | 14 | ~11 簇(均 1.3) |

推演读法:一个 Hard 实例约产 108 钻石矿块、312 金矿块、14 古残骸,均受 maxCount 封顶,jitter ±10% 内浮动。换算到玩家收益时叠加 Fortune 与冶炼,经济净值与重置成本的平衡在第十八章核算;本章保证的是产出端确定性与上限,杜绝"同一 seed 偶发翻倍"或"无上限堆矿"。

### 8.8 OreGenerator 落地清单

| 项 | 内容 |
| --- | --- |
| 触发时机 | InstanceManager 分配实例并完成 Skeleton/NoiseCarving/ConnectivityFix 后,在工作线程调用 `OreGenerator.generate(instanceSeed, regionBox, voxelGrid, mainComponentMask)` |
| 输入 | instanceSeed、regionBox、occupied bitset、mainComponent 掩码、difficulty |
| 输出 | 不可变铺矿表(坐标 -> OreType),写入 region genState |
| 随机源 | 仅 `hash(instanceSeed, ...)` 派生,无共享可变 Random(D3) |
| 线程 | 纯计算,工作线程;落方块由 ChunkGenerator 主线程读表(D8) |
| 失败处理 | 壁面不足导致配额无法铺满 -> 按实铺量收尾并记 WARN 日志;不抛异常、不静默吞掉数据缺口 |
| 交叉引用 | 经济产出上限/重置成本约束见第十八章;分层 Y 阈值与第四章一致;连通分量来源见第七章 |

---

## 九、陷阱系统

本章重写原文档第7节,定义静态与动态陷阱。核心原则:陷阱制造风险而非制造"不可避免的猝死"。每类陷阱必须满足"可感知线索 + 反应窗口"不变量(评审 Major: 无预警即死不公平)。难度门控、死亡惩罚、装备要求归第十四/十八章,本章只交叉引用。

状态说明:动态陷阱(运行期事件驱动,`trap/DynamicTrapEngine.java`)全章有效。静态陷阱的**布点路径**已随 F021/F032 换代——现由数据包 `placed_feature` 在原版 feature 阶段放置(`trap_fake_ore` / `trap_collapsing_tunnel` / `trap_tnt_vein` / `trap_lava_pocket`,7.0.3),各难度挂哪几种由 biome 的 features 列表决定(Easy 只挂前两种)。因此本章凡以"离线布点""主连通分量可达壁面""BFS 干道交叉点"为前提的布点过滤规则(9.5 的 TR-2 落地、9.3 的每区密度上限统计口径等)均已失效,标 SUPERSEDED;它们描述的是当年离线阶段能做、而原版 feature 阶段做不到的事。陷阱的"可感知线索 + 反应窗口"不变量本身不受影响,仍由各陷阱的 `configured_feature` 形态与运行期逻辑保证。

### 9.1 设计不变量

| 编号 | 不变量 | 状态 | 说明 |
| --- | --- | --- | --- |
| TR-1 | 每个陷阱有玩家可感知线索(视觉/音效/粒子)且有最短反应窗口 >= reactionWindow | DECIDED | 杜绝"无预警即死";reactionWindow 下限见 9.4 |
| TR-2 | 出生点半径 SPAWN_SAFE_R 内、主干道关键节点禁布致死陷阱 | DECIDED | 与第十一章安全半径一致;关键节点 = 主连通分量 BFS 干道交叉点 |
| TR-3 | trapChance = difficulty * localRisk,值域与每区密度上限封顶 | DECIDED | 见 9.3,防止陷阱堆叠成必死走廊 |
| TR-4 | 静态陷阱布点走数据包 `placed_feature`(`trap_fake_ore` / `trap_collapsing_tunnel` / `trap_tnt_vein` / `trap_lava_pocket`,见 7.0.3),动态陷阱在运行期事件驱动 | DECIDED(F021/F032 修订,原为离线生成阶段确定布点) | 静态由原版 feature 的区块确定性保证可复现;动态有节流与实例内预算 |
| TR-5 | 所有世界写(放置/爆炸/落沙/刷怪)经 server.execute() 回主线程(D8) | DECIDED | 动态陷阱在 tick 回调里只决策,落地走主线程队列 |

### 9.2 陷阱分类总览

| 类别 | 陷阱 | 触发方式 | 致死性 | 阶段 |
| --- | --- | --- | --- | --- |
| 静态 | TNT 矿脉(touch-charge) | 挖到引信方块 | 高(可被线索规避) | 离线布点 |
| 静态 | 岩浆池/岩浆袋 | 挖破薄壁/踩空 | 高 | 离线布点 |
| 静态 | 崩塌矿道(gravel/sand 承重) | 移除支撑方块 | 中 | 离线布点 |
| 静态 | 假矿石爆炸(fake ore) | 挖掘伪装矿石 | 中 | 离线布点 |
| 动态 | 身后刷苦力怕 | danger 阈值 + 玩家背向 | 中 | 运行期 |
| 动态 | 局部坍塌 | danger + 概率 tick | 中 | 运行期 |
| 动态 | 岩浆喷发 | danger + 概率 tick | 高(强线索) | 运行期 |

### 9.3 trapChance 取值域与每区密度上限

```
trapChance(zone, cell) = clamp(difficultyFactor(zone) * localRisk(cell), 0, TRAP_CHANCE_MAX)
```

| 项 | Easy | Medium | Hard | 说明 |
| --- | --- | --- | --- | --- |
| difficultyFactor | 0.00 | 0.35 | 1.00 | Easy 全程无静态致死陷阱(新手区),仅保留崩塌/假矿等非致死提示性陷阱可选关闭 |
| localRisk 值域 | [0,1] | [0,1] | [0,1] | 由矿密度、是否狭窄死路、距出生点距离派生 |
| TRAP_CHANCE_MAX | 0.00 | 0.12 | 0.25 | 单格触发概率硬上限(PENDING) |
| 每 16x16x16 子区致死陷阱数上限 | 0 | 2 | 4 | 防"必死走廊"(PENDING) |
| 两个致死陷阱最小间距 | - | 6 格 | 5 格 | 保证连续触发间有喘息 |

`localRisk` 建议构成:`localRisk = 0.5*oreRichnessNorm + 0.3*deadEndNorm + 0.2*depthNorm`,各项归一到 [0,1]。富矿、死路、深处更危险,符合 risk-reward。布点确定性:`deriveFloat(instanceSeed, "trap", x,y,z) < trapChance` 则该格为陷阱候选,再过 9.5 的禁布过滤与密度上限。

### 9.4 静态陷阱规格表

伤害以 1.20.1 半心=1.0 计;reactionWindow 为"线索出现到伤害落地"的最短玩家可反应时间。

| 陷阱 | 可感知线索 | reactionWindow | 伤害 | 作用半径 | 触发概率上限 | 每区密度上限 | 备注 |
| --- | --- | --- | --- | --- | --- | --- | --- |
| TNT 矿脉 | 引信方块纹理异色 + 挖掘时嘶嘶音(play `SoundEvents.TNT_PRIMED`)+ 红色粒子,点燃后 fuse 30 tick(1.5s) | >= 30 tick | 爆炸威力 power=3.0(略低于原版 TNT 4.0) | 半径约 4 格 | TRAP_CHANCE_MAX | 子区上限内 | 用 `Level.explode(...,Level.ExplosionInteraction.MOB)` 避免大范围破坏地形;fuse 期间玩家可跑出半径 |
| 岩浆池/岩浆袋 | 薄壁透光 + 高温粒子(`ParticleTypes.FLAME`)+ 挖前可见橙光从缝隙渗出 | 即时(规避靠预判) | 接触岩浆 4.0/0.5s(原版机制) | 池体积 | TRAP_CHANCE_MAX | 子区上限内 | 不做"破壁瞬间塞满整条巷道"的版本;岩浆体积 <= 2x2x2,留逃生退路 |
| 崩塌矿道 | 头顶 gravel/sand 纹理 + 细沙下落粒子预抖(放置前 10 tick 触发预警粒子与 `SoundEvents.SAND_BREAK`) | >= 10 tick | 单块下落 2.0(`FallingBlockEntity` 原版伤害,封顶不超过 6.0 累计) | 1x3 列 | TRAP_CHANCE_MAX | 子区上限内 | 单块静态陷阱用真实 `FallingBlockEntity`,不瞬移玩家,封顶避免长柱叠死;大规模动态坍塌改走 `setBlock`(见第十九章 19.2) |
| 假矿石爆炸 | 矿石纹理略有裂纹变体 + 挖掘进度异常(挖第一下播放低沉音) | 一次挖掘间隔(可中断) | power=2.0 小爆炸 | 半径约 2.5 格 | TRAP_CHANCE_MAX | 子区上限的一半 | 比 TNT 弱,惩罚"无脑挖亮矿";只伤玩家不毁大量方块 |

致死类(TNT、岩浆)在 Easy 难度 difficultyFactor=0 时不生成(9.3)。

### 9.5 静态陷阱禁布过滤(TR-2)

布点候选经过滤后才落地,过滤顺序:

1. 距出生点 <= SPAWN_SAFE_R(第十一章,建议 8 格):剔除全部致死陷阱。
2. 位于主干道关键节点(主连通分量 BFS 干道度数 >= 3 的交叉体素)及其 1 格邻域:剔除致死陷阱(保留非致死提示陷阱可选)。
3. 该 16x16x16 子区致死陷阱已达上限(9.3):剔除。
4. 与已落地致死陷阱间距 < 最小间距(9.3):剔除。
5. 陷阱方块所在体素必须 ∈ 主连通分量可达壁面(玩家够得到才有意义)。

过滤后剩余候选写入静态陷阱表 `Map<BlockPos, TrapType>`,随 region genState 持久化,确定性可复现(D3)。

### 9.6 动态陷阱规格

动态陷阱由 danger(第十章)阈值门控,运行期触发,有实例级预算与节流。

| 陷阱 | 触发条件 | 可感知线索 | reactionWindow | 伤害/效果 | 节流 |
| --- | --- | --- | --- | --- | --- |
| 身后刷苦力怕 | danger >= DANGER_THRESH_CREEPER 且玩家有背向方位 | 生成瞬间播放 `SoundEvents.CREEPER_PRIMED` 方位提示音 + 短暂粒子 | 苦力怕原版引信 30 tick | 原版 creeper 爆炸(可被听声回头处理) | 见 9.7 最小生成距离/视野/冷却 |
| 局部坍塌 | danger 概率 tick(每 20 tick 评估) | 顶部预警粒子 + `SoundEvents.GRAVEL_BREAK` 预抖 10 tick | >= 10 tick | 1-3 列 FallingBlock,累计伤害封顶 6.0 | 每玩家每 >= 200 tick 至多一次 |
| 岩浆喷发 | danger 概率 tick | 地面裂纹粒子 + 红光 + `SoundEvents.LAVA_POP` 持续 20 tick | >= 20 tick | 喷出岩浆柱,接触 4.0/0.5s,5 tick 后自动回收 | 每实例同时至多 1 处;冷却 >= 300 tick |

### 9.7 身后刷怪安全约束(评审重点)

| 约束 | 取值 | 说明 |
| --- | --- | --- |
| 最小生成距离 minSpawnDist | 8 格 | 不得贴脸生成 |
| 最大生成距离 maxSpawnDist | 20 格 | 太远无压力意义 |
| 必须不在视野内 | 生成点须在玩家视锥外(dot(look, dir) < cos(70°)) | 用 `player.getLookAngle()` 与方向向量夹角判定 |
| 必须有提示音 | 生成即播方位音(TR-1) | 给玩家回头/听声辨位的反应窗口 |
| 必须可达且非陷阱区 | 生成点 ∈ 主连通分量可站立点,且不在静态陷阱半径内 | 复用第十一章站立点校验 |
| 同玩家冷却 | >= 100 tick | 防连刷围杀 |
| 计入实例 mob 预算 | 是 | 与第十章单实例硬上限(<=30)共享计数 |

### 9.8 动态陷阱挂载点与开销

| 项 | 方案 | 说明 |
| --- | --- | --- |
| 挂载事件 | `TickEvent.LevelTickEvent`(Phase.END,仅 `level.dimension()==MINING_DIM`)或 `TickEvent.ServerTickEvent` 中遍历矿山实例 | 只在矿山维度跑,不污染主世界 tick |
| 评估频率 | danger 评估每 20 tick;动态陷阱判定挂在同一节流计数上(D7) | 避免每 tick 全实例扫描 |
| 遍历范围 | 仅遍历"有在线玩家的实例",空实例跳过 | refCount==0 实例不参与动态陷阱 |
| 落地线程 | 决策在 tick 线程,放置/爆炸/刷怪经 `server.execute()`(D8) | 不在 tick 中途直接写世界引发并发问题 |
| 开销控制 | 每实例每评估周期动态陷阱触发次数封顶(如 <= 1) | 与 9.6 各陷阱冷却叠加,O(实例数 * 在线玩家数) |

### 9.9 交叉引用

死亡惩罚(掉落/送回原维度/danger 重置)、装备门控(进入难度的护甲阈值)、重置成本与陷阱触发的经济联动,均在第十四章(玩家流程)与第十八章(经济与平衡)定义,本章不重复,仅保证陷阱"可感知、可规避、有上限"。

---

## 十、动态压力系统(刷怪)

本章重写原文档第8节,严格按 D7 实现。核心:danger 是每玩家独立的压力标量,有硬封顶与软收敛曲线,绝不单调累加到失控(评审 Critical: danger 无封顶必失控)。danger 驱动刷怪节奏、单波数量与环境压力,但保证装备达标玩家可持续作业(不是"超时必死")。刷怪走原版规则校验并自管理实例内计数,避免与原版 NaturalSpawner 打架。

### 10.1 设计不变量

| 编号 | 不变量 | 状态 | 说明 |
| --- | --- | --- | --- |
| DG-1 | danger ∈ [0, DANGER_MAX],硬封顶 | DECIDED | 消除单调累加失控 |
| DG-2 | timeSpent 经软封顶收敛曲线,离区/降频时衰减 | DECIDED | 避免"超时必死"劝退(D7) |
| DG-3 | danger 每玩家独立 | DECIDED | 挂玩家 Capability(D5) |
| DG-4 | danger 评估每 20 tick 或事件驱动 | DECIDED | 不每 tick 算 |
| DG-5 | 单实例怪物硬上限 MAX_MOBS_PER_INSTANCE(建议 30) | DECIDED | 自管理计数,不依赖原版 cap |
| DG-6 | 刷怪用 addFreshEntity + Mob.checkSpawnRules,落地走主线程(D8) | DECIDED | 合法生成,避免穿墙/非法点 |
| DG-7 | 存在 danger 上限对应的"可持续作业"稳态:满 danger 下装备达标玩家 DPS/防御足以清场 | DECIDED | 见 10.6 稳态校验 |

### 10.2 danger 组成与量纲

```
danger = clamp(
           W_ZONE   * zoneTerm
         + W_TIME   * timeTerm
         + W_ORE    * oreTerm,
         0, DANGER_MAX)
```

各加项先各自归一到 [0,1],再线性加权;权重和不必为 1(clamp 封顶兜底),但建议归一便于直觉。

| 加项 | 含义 | 归一公式 | 量纲 |
| --- | --- | --- | --- |
| zoneTerm | 所在难度区基础压力 | Easy=0.2, Medium=0.55, Hard=1.0(查表常量) | [0,1] |
| timeTerm | 在实例内持续作业时间的软封顶函数 | `1 - exp(-k * tWin)`,见 10.3 | [0,1) |
| oreTerm | 附近矿物富集度(贪婪惩罚) | `clamp(nearbyOreValue / ORE_NORM, 0, 1)` | [0,1] |

建议权重(PENDING 待校验):`W_ZONE=0.45, W_TIME=0.35, W_ORE=0.20`,`DANGER_MAX=1.0`。如此满 zone(Hard)+满 time+满 ore 时 danger=clamp(0.45+0.35+0.20)=1.0,恰好打满,语义清晰。

### 10.3 timeSpent 软封顶与衰减(消除"超时必死")

```
tWin   = 滑动窗口内的"活跃作业 tick"计数(见下)
timeTerm = 1 - exp(-K_TIME * tWin / TIME_SCALE)
```

| 参数 | 建议值 | 说明 |
| --- | --- | --- |
| K_TIME | 1.0 | 收敛速率;曲线在 tWin≈TIME_SCALE 时达约 0.63,2*TIME_SCALE 时约 0.86,渐近 1 不超 |
| TIME_SCALE | 12000 tick(约 10 分钟) | 达到大半压力的时间尺度(PENDING) |
| 衰减规则 | 离开实例 region 或评估降频时,tWin 每 20 tick 衰减 DECAY_PER_EVAL | 让短暂撤退能回血,不惩罚正常节奏 |
| DECAY_PER_EVAL | 8 tick/评估 | 衰减速率(PENDING);约为累积速率的一部分,使"撤一会儿"显著降压 |
| 累积规则 | 玩家在 region 内主动作业(挖掘/移动/战斗)时 tWin += 20/评估;纯挂机可设增速减半 | 软封顶 + 衰减共同保证 timeTerm 永不把玩家推向必死 |

关键性质:`exp` 软封顶使 timeTerm 渐近 1 但永不超过,叠加 W_TIME=0.35,单靠时间最多贡献 0.35 danger,绝不会出现"时间越长 danger 无界增长"。这是对评审 Critical(无封顶单调累加)的直接修复。

### 10.4 danger 分段映射表(danger -> 刷怪节奏/环境)

danger 评估后查下表得到当前刷怪参数(每 20 tick 刷新),阈值与数值 PENDING 待校验:

| danger 区间 | 刷怪间隔(tick) | 单波数量 | 允许怪物类型 | 环境光照削弱 | 说明 |
| --- | --- | --- | --- | --- | --- |
| [0.00, 0.20) | 不主动刷怪 | 0 | - | 0 | 安全期/低压,仅出生保护区附近 |
| [0.20, 0.40) | 400 | 1 | zombie, spider | 0 | 轻压力 |
| [0.40, 0.60) | 280 | 1-2 | zombie, spider, skeleton | -1 等级感知(粒子/迷雾,非真实改 lightmap) | 中压 |
| [0.60, 0.80) | 180 | 2-3 | + creeper(走 9.7 约束) | -2 感知 | 高压,身后刷怪启用 |
| [0.80, 1.00] | 120 | 3-4 | + 偶发 cave_spider/witch | -3 感知 | 满压,但受 MAX_MOBS_PER_INSTANCE 封顶 |

光照削弱用客户端感知效果(迷雾/粒子/音效)实现压迫感,不真实修改世界 lightmap(避免与区块光照系统冲突、避免持久化副作用)。

单波数量与间隔共同受 MAX_MOBS_PER_INSTANCE 约束:若当前实例存活 mob 已达上限,本波跳过(只更新计时,不强塞)。

### 10.5 刷怪流程与实例内计数(避免与 NaturalSpawner 打架)

| 步骤 | 实现 | 说明 |
| --- | --- | --- |
| 1 计数检查 | `if (instanceMobCount(instanceId) >= MAX_MOBS_PER_INSTANCE) return;` | 自管理计数,不读原版 mobcap |
| 2 选点 | 在该玩家 danger 触发半径内、主连通分量可站立点采样;creeper 额外过 9.7 视野/距离 | 复用第十一章站立点校验 |
| 3 合法性校验 | 构造实体后 `mob.checkSpawnRules(level, MobSpawnType.SPAWNER)` 或 `NaturalSpawner.isSpawnPositionOk` 等价校验 | 用原版规则确保位置合法,避免穿墙/淹没 |
| 4 落地 | `server.execute(() -> { level.addFreshEntity(mob); registerToInstance(instanceId, mob); })` | 主线程落地(D8),注册进实例计数与生命周期跟踪 |
| 5 标记 | 给 mob 打 PersistentData 标记 `miningdim:instance=instanceId` | 用于离场清理、计数回收、重置时定向清除 |
| 6 回收 | 监听 `LivingDeathEvent`/实体移除,`instanceMobCount--`;实例重置时按标记批量移除 | 防计数泄漏导致永远刷不出或刷爆 |

实例内计数存于 InstanceState(D6 的 InstanceState 扩展一个 `int liveMobCount` 或 `Set<UUID> liveMobs`),`MAX_MOBS_PER_INSTANCE=30`(DG-5)。不调用原版 mobcap,因此不与 NaturalSpawner 的全局上限互相挤兑。原版自然刷怪可在该维度通过 biome spawn 配置关闭(spawners 置空),只保留本系统主动刷怪。

### 10.6 可持续作业稳态校验(DG-7)

满 danger(Hard, danger≈1.0)下的刷怪压强必须可被"达标装备玩家"清掉,否则等于变相超时必死。

| 量 | 满 danger 估值 | 说明 |
| --- | --- | --- |
| 刷怪间隔 | 120 tick(6s) | 10.4 |
| 单波数量 | 3-4 | 10.4 |
| 稳态入怪速率 | 约 3.5 / 6s ≈ 0.58 mob/s | 受 MAX_MOBS 封顶,实际稳态低于此 |
| 达标玩家清怪速率(钻石剑+战吼/普攻) | 单 zombie 约 2-3 击致死,>= 1 mob/s | 远高于入怪速率 |
| 结论 | 稳态可清场 | 装备达标玩家可持续作业(DG-7 满足),时间压力来自"分心挖矿时被堆怪",而非数值碾压 |

该校验为定性论证,数值 PENDING,需 GameTest(Forge GameTest)用模拟玩家 DPS 实测调参。装备门控阈值(进入 Hard 的护甲/武器要求)在第十四章。

### 10.7 挂载、节流与持久化

| 项 | 方案 |
| --- | --- |
| danger 存储 | 玩家 Capability(D5):`danger(float)`、`tWin(int)`、`lastEvalTick(long)`、`instanceId(long)` |
| 评估挂载 | `TickEvent.ServerTickEvent`/`LevelTickEvent`(仅矿山维度),每 20 tick 对在矿山内的在线玩家评估(DG-4) |
| 事件驱动加评 | 击杀大量怪、挖到高价矿、触发陷阱等可即时追加一次评估(不必等 20 tick) |
| 离区处理 | 玩家离开 region/维度:tWin 进入衰减,danger 随之回落;切维度时 Capability 经 PlayerEvent.Clone 保留/重置(D5) |
| 线程 | 评估纯计算可在 tick 线程内完成(轻量);刷怪落地走 server.execute()(D8) |
| 与重置联动 | 实例重置(第十五/十八章)时清空该实例所有玩家 danger 与 liveMobs |

---

## 十一、玩家出生系统

本章重写原文档第9节。原稿核心是"出生点既是安全落点,又是主连通分量 BFS 的锚点种子",整套依赖第七章的连通性修复;该链路随 F021/F032 删除,11.1 记录了现行顺序。现行核心只剩两条:在已 force-load 就绪的真实区块上扫出安全落点(找不到就强制建平台),以及出生后的静态安全半径 + danger 冻结期,保证玩家不会"一落地就死"。本章凡出现 `mainComponent` / `spawnAnchor` / 离线扫描的段落均属历史归档。

### 11.1 [SUPERSEDED] 出生与连通性的依赖顺序

状态:SUPERSEDED(F021/F032)。本节整表建立在离线体素网格与 `ConnectivityFix` 之上,二者已删除。

现行顺序(`spawn/SpawnSystem.java`):入场流程先 force-load 出生候选区块并等到 `FULL`(14.3),再在主线程于真实 `ServerLevel` 上做环形扫描找安全站立点;扫描半径与 `EntryGateway` force-load 的 3x3 区块窗口对齐(最坏对齐下向外保证 16 格),找不到则按 11.5 建 3x3 兜底平台。没有 `spawnAnchor`、没有 `mainComponent`、没有离线 spawn pool 预算阶段;pool 是按 `instanceId` 缓存的运行期扫描结果,带 TTL 占用防叠人(11.4)。

以下为原方案归档:

| 步骤 | 阶段 | 动作 | 依赖/产出 |
| --- | --- | --- | --- |
| 1 | NoiseCarving 后 | 在体素网格中挑一个候选出生体素 `spawnAnchor`(满足 11.3 安全谓词的空气体素,优先靠近 region 几何中心或固定锚区) | 产出 spawnAnchor |
| 2 | ConnectivityFix 起点 | 以 spawnAnchor 为 BFS 种子,6-邻接洪泛标记主连通分量 `mainComponent`(D4) | 产出 mainComponent 掩码 |
| 3 | ConnectivityFix 主体 | 非主分量:体积 < minIslandSize 填实,否则 A* 打通隧道并入主分量(第七章) | 主分量扩张 |
| 4 | ConnectivityFix 后 | 在 mainComponent 内枚举所有满足安全谓词的站立点,构成 spawn pool 缓存 | 产出 spawnPool |
| 5 | 铺矿/陷阱 | OreGenerator(八章)、TrapGenerator(九章)只在 mainComponent 上作业;出生半径内禁陷阱 | 一致性 |

不变量 SP-0:出生点与所有 spawn 候选点必须 ∈ mainComponent(D4)。因 spawnAnchor 是 BFS 种子,它天然属于主分量;pool 从 mainComponent 枚举,天然满足。

### 11.2 安全出生点谓词(必须安全空间)

一个体素 `p` 是合法站立/出生点,当且仅当全部满足:

| 谓词 | 条件 | 对应原文档"必须安全空间" |
| --- | --- | --- |
| 头顶净空 | p 与 p.above() 均为空气(>= 2 格净空) | 头顶 2 格空气 |
| 脚下固体 | p.below() 为可站立实心方块(非岩浆/非掉落方块/非空气) | 脚下固体 |
| 无岩浆邻接 | p 的 3x3x3 邻域无 lava 流体 | 周围无岩浆 |
| 非陷阱区 | p 不在任何静态陷阱半径内 + 不在出生安全半径外的致死陷阱内 | 不在陷阱区 |
| 属主分量 | SUPERSEDED:`mainComponent` 已不存在(7.0.5) | 原 D4 可达性 |
| 可达首矿区 | SUPERSEDED:依赖离线可达性分析,同上作废 | 原"避免出生即困死" |

谓词现由 `SpawnSystem.isSafe(ServerLevel, BlockPos, InstanceState)` 在真实世界方块状态上复核(头顶净空 / 脚下固体 / 无岩浆),而非离线批量纯函数;确定性由"同一已生成区块的方块状态不变"给出,不再需要 D3 派生。"出生即困死"的兜底改为 11.5 的强制 3x3 安全平台。

### 11.3 spawn pool 预生成与缓存

| 项 | 内容 |
| --- | --- |
| 生成时机 | SUPERSEDED:原为 ConnectivityFix 完成后一次性扫描 mainComponent。现行是入场时按 `instanceId` 惰性扫描已 force-load 就绪的真实区块(11.1) |
| 存储 | `List<BlockPos> spawnPool` 写入 region genState 持久化(D5);玩家进入直接随机取点,不再实时扫描 |
| 取点随机 | `deriveInt(instanceSeed, "spawnPick", pickCounter) % pool.size()`(D3 确定性可复现)或运行期非确定性随机均可,见 11.4 并发 |
| 池容量下限 | 若 pool.size() < MIN_SPAWN_POOL(建议 8),记 WARN 并触发兜底平台(11.5) | 
| spawnAnchor 处理 | spawnAnchor 始终在 pool 首位,作为默认/兜底首选点 |

### 11.4 并发取点原子占用(评审 Major)

多玩家同时进入同一共享实例时,必须避免两人取到同一出生点叠人。

| 机制 | 实现 |
| --- | --- |
| 占用表 | InstanceState 维护 `Set<BlockPos> occupiedSpawns` 或带 TTL 的占用 map(进入后 N tick 释放) |
| 原子取点 | 取点操作在 server 主线程串行执行(D8),`server.execute(() -> pickAndReserve(...))`;主线程单线程天然互斥,等价原子 |
| 取点逻辑 | 从 spawnPool 顺序/随机找第一个不在 occupiedSpawns 的点,占用后传送;占用 TTL(如 60 tick)后释放,供后续玩家复用 |
| 池耗尽 | 所有点被占用且无释放:对最后到达者在 spawnAnchor 周围做微扰找邻近合法空位,仍无则兜底平台(11.5) |
| 与持久化呼应 | occupiedSpawns 为运行期瞬态(不持久化),实例卸载/重置清空;持久的是 spawnPool 本身(D5) |

取点必须在主线程串行,是因为"读 pool + 标记占用 + 传送"三步非原子会竞态;借 server.execute() 串行化是 1.20.1 既有且最稳的互斥手段,无需额外锁。

### 11.5 兜底安全平台(找不到安全点)

当 spawnPool 为空、耗尽或所有候选失效时,强制构建 3x3 安全平台保证玩家可落地(评审:找不到安全点必须兜底,不可让玩家卡进方块或虚空)。

| 步骤 | 动作 |
| --- | --- |
| 1 选址 | 取 spawnAnchor(或 region 中心可达空域);若该处不达标,沿 mainComponent 向上找首个有 >=3 格净空的位置 |
| 2 整地 | server.execute() 中:在脚下铺 3x3 实心方块(如 `Blocks.STONE`),清出 3x3x3 空气净空,清除半径内岩浆 |
| 3 标记 | 平台中心记为临时安全点,纳入 occupiedSpawns(TTL 占用) |
| 4 日志 | 记 WARN(`spawnPool exhausted, built fallback platform at ...`),便于后续调参发现池过小;不静默吞 |
| 5 通路 | 校验平台到最近矿区的安全通路(11.6),若断则沿 A* 短打一条 1x2 通道 |

兜底平台保证"任何情况下玩家都有合法落点",是出生系统的最后一道闸,与 D4 连通性闸思路一致。

### 11.6 到首矿区安全通路校验

出生点合法不等于"出生后能开始游戏"。需校验从出生点到最近矿区存在安全通路:

```
从 spawn 沿 mainComponent 做 6-邻接 BFS,
寻找首个"附近 R 格内存在已铺矿石"的可达站立点;
路径上不得穿越致死静态陷阱半径(动态陷阱不计,因其有线索可避)。
若不可达 -> 视该 spawn 不合法,从 pool 剔除;
若全 pool 都不可达最近矿区 -> 触发兜底平台 + A* 短通道(11.5 step5)。
```

该校验在预生成阶段对 pool 批量做一遍,结果缓存,避免运行期重复 BFS。

### 11.7 出生后保护:静态安全半径 + danger 冻结期

| 保护 | 取值 | 说明 |
| --- | --- | --- |
| 静态安全半径 SPAWN_SAFE_R | 8 格(PENDING) | 半径内:禁刷怪(动态压力第十章不在此半径选点)、禁致死陷阱(第九章 TR-2/9.5) |
| danger 冻结期 | 出生后 SPAWN_FREEZE_TICKS=200 tick(10s,PENDING) | 期间 danger 评估暂停且钳为低值(< 0.20),不主动刷怪;让玩家整理装备、辨明方向 |
| 冻结实现 | Capability 记 `spawnFreezeUntil = currentTick + 200`;第十章评估时 `if (tick < spawnFreezeUntil) danger = min(danger, 0.15)` | 与第十章 danger 评估同源,单点控制 |
| 安全半径可视 | 出生点可选放置临时光源/信标粒子标识安全区边界 | 帮助玩家识别"出了这圈开始有压力" |

冻结期是对评审 Minor(出生安全期)的直接落实:玩家落地后有明确无压力窗口,杜绝"传送进来瞬间被刷怪/陷阱秒杀"。

### 11.8 出生流程时序与挂载

| 步骤 | 线程 | 动作 |
| --- | --- | --- |
| 1 | 主线程 | 玩家请求进入(入口 GUI/传送门,第十四章),InstanceManager 分配/复用实例,确保 genState=ready |
| 2 | 主线程 | `server.execute()` 中原子取点(11.4),得到 spawnPos |
| 3 | 主线程 | 记录玩家进入前维度/坐标/gamemode 到 Capability(D5),用于返回 |
| 4 | 主线程 | `player.teleportTo(miningLevel, x+0.5, y, z+0.5, ...)` 或 `changeDimension` 传送到 spawnPos |
| 5 | 主线程 | 初始化玩家 Capability:instanceId、danger=0、tWin=0、spawnFreezeUntil=tick+200 |
| 6 | 主线程 | 占用 spawn 点(TTL),刷新 occupiedSpawns;广播进入提示 |

所有出生相关世界写(整地、传送、放兜底平台)经 server.execute() 回主线程(D8)。spawn pool 计算与安全谓词现在读的是真实 `ServerLevel` 方块状态,因此必须与世界写同在主线程,不能下放工作线程——这与旧稿把它们归为离线纯计算的线程归属正好相反(11.1)。

### 11.9 交叉引用

入口方式(GUI/传送门/NPC/物品)、难度选择、进入前后的玩家流程在第十四章;实例分配/复用/上限在第十二章 InstanceManager(D6);连通分量与孤岛处理在第七章;出生半径内禁布陷阱与第九章 TR-2/9.5 一致;出生即冻结的 danger 与第十章评估同源。

---

## 十二、实例生命周期、并发与持久化

本章定义矿山实例从分配、引用计数、空实例回收到崩溃恢复的完整生命周期,并锁定持久化方案与并发纪律。所有内容严格遵循跨章决策 D5(持久化)、D6(实例分配)、D8(线程纪律)。

R1 现状说明(必读,决定本章哪些小节对运行期有效):

当前版本是 R1 固定区域模型。开服时(`InstanceManager.rebuildFromStorage` 末尾)预建恰好三个固定难度实例,每难度一个,`shared = true`,常驻不 GC;`allocate(player, difficulty)` 只做一次路由,直接返回 `fixedInstanceFor(difficulty)`。由此:

| 小节 | 对运行期是否生效 | 说明 |
| --- | --- | --- |
| 12.1 数据结构与状态机 | 生效 | 字段定义与持久化口径不变 |
| 12.1a 固定实例生命周期 | 生效 | 本次新增,描述 R1 的真实路径 |
| 12.2 实例分配语义 | 不生效 | `allocatePrivate`/`allocateShared`/`createInstance` 代码仍在,但 R1 下不被调用 |
| 12.3 容量上限与背压 | 不生效 | `backpressureOrQueue`/`pollQueue` 不被触发;实例数恒为 3,永远不会撞 `globalCap` |
| 12.4 id 与 seed 分配 | 部分生效 | 计数器与派生规则仍在;但只在预建三个固定实例时各用一次 |
| 12.5 持久化方案 | 生效 | 另有 R1 专属字段 `fixedInstanceId`(见 12.1a) |
| 12.6 引用计数与空实例 GC | 部分生效 | 引用计数生效;固定实例永不进入 GC,`lastEmptyTick` 恒为 -1 |
| 12.7 无人在场时暂停 tick | 生效 | |
| 12.8 启动重建与孤儿清理 | 生效 | 重建末尾额外承担三固定实例的预建/认领 |

把 12.2/12.3/12.6 的算法当成现状去做运维判断会得出完全错误的结论(典型误判:以为超 `globalCap` 会排队、以为没人时实例会被回收重建)。

### 12.1 核心数据结构与状态机(DECIDED)

实例的运行时视图由 `InstanceManager` 持有,单例,生命周期绑定矿山维度的 `ServerLevel`。`InstanceState` 为内存视图,其权威副本由 `SavedData` 持久化(见 12.5)。

`InstanceState` 字段定义:

| 字段 | 类型 | 语义 | 持久化 |
| --- | --- | --- | --- |
| instanceId | long | 持久自增主键,全 mod 唯一 | 是 |
| seed | long | 实例确定性种子(见 12.4) | 是 |
| difficulty | enum(EASY/MEDIUM/HARD) | 难度档,决定矿物/陷阱/压力参数 | 是 |
| regionBox | BoundingBox | 该实例独占的 region 包围盒(区块对齐) | 是 |
| refCount | int(派生) | == playerSet.size(),不独立持久化 | 否 |
| playerSet | Set\<UUID\> | 当前在场玩家集合 | 是 |
| ownerKey | OwnerKey | 私有实例的归属键(玩家 UUID 或队伍 id);共享实例为 null | 是 |
| shared | boolean | 是否共享实例 | 是 |
| createdTick | long | 创建时的 server game time | 是 |
| lastEmptyTick | long | 最近一次 refCount 归零的 tick;非空时为 -1 | 是 |
| genState | enum(PENDING/GENERATING/READY/READY_FALLBACK/RESETTING/FAILED/RECYCLED) | 实例可用性与重置状态 | 是(七值全部序列化,按 `name()` 存取) |
| active | boolean(派生) | playerSet 非空即 active,控制是否 tick 压力/陷阱 | 否 |

`genState` 取值口径统一(DECIDED):全文只有一份枚举,七值,与 `core/GenState.java` 一致;3.3、7.9.3/7.9.4 与本表不得各写一份。旧稿本表只列五值(漏 `READY_FALLBACK` 与 `RECYCLED`),已订正——实现者若按旧五值裁剪持久化白名单,反序列化会丢状态。

```
PENDING --(登记后待生成)--> GENERATING
GENERATING --(生成完成)--> READY(或降级 READY_FALLBACK)
GENERATING --(异常)--> FAILED
READY --(重置触发,实例已清空)--> RESETTING
RESETTING --(重置完成)--> READY
FAILED --(运维/自动重试)--> PENDING
任意 --(空实例 GC 销毁)--> RECYCLED
```

可进入判定是 `isEnterable() = READY || READY_FALLBACK`,不是"仅 READY"(见第十四章入场流程的 force-load 等待门控);私有实例复用判定用 `isAlive() = PENDING|GENERATING|READY|READY_FALLBACK|RESETTING`。

R1 现状:离线生成阶段已下线(7.0),实例登记即置 `READY`,故 `PENDING`/`GENERATING` 在正常路径上不再出现;`RECYCLED` 对三个固定实例永不发生。`RESETTING` 仍然会出现——它由 13.4 的滑动重置驱动。

### 12.1a 固定实例的真实生命周期(DECIDED,R1)

三个固定难度实例是当前唯一存在的实例,其生命周期与 12.2/12.3/12.6 描述的动态实例完全不同:

| 环节 | 行为 |
| --- | --- |
| 预建时机 | 开服 `rebuildFromStorage` 末尾。每难度一个,`shared = true`,`ownerKey = null` |
| 几何来源 | `RegionGrid.fixedRegionFor(difficulty)`,单元 X 列取 `Difficulty.regionCellX()`,Z 列恒为 `FIXED_REGION_CELL_Z`(6.2) |
| 认领方式 | 靠持久化的 `fixedInstanceId`(每难度一个,存在 `MiningSavedData`)认领,**不能**靠编译期几何比对 —— D3 滑动重置会把 region 整体挪走,几何早已不在原位 |
| 存量存档兜底 | 尚未写过 `fixedInstanceId` 的老存档走一次性迁移认领 `claimFixedByLegacyGeometry`:只比 XZ 原点,且必须用 `MiningConstants` 里写死的历史几何(SIZE=256 / STRIDE=288),不能用 config 派生几何(见 16.2.1 对 `bufferChunks` 的警告) |
| 分配 | `allocate(player, difficulty)` 直接路由到对应固定实例;不新建、不扫描复用池、不背压、不排队 |
| 回收 | 永不回收。不进入空实例 GC,不进入动态回收,`lastEmptyTick` 恒为 -1 |
| 销毁/换图 | 唯一变更路径是 `ResetService` 重置(第十三章):实例对象本身存活,只是 `regionBox` 与 `seed` 被换掉 |
| `regionAt(x, z)` | 仍按当前 `RegionLayout` 快照返回包含该点的固定实例 |

由此,运维口径上"矿区有几个实例"的答案恒为 3;`/mining info` 列出的也永远是这三个 id。

### 12.2 [SUPERSEDED] 实例分配语义(代码保留,R1 模式下不生效)

状态:SUPERSEDED。以下算法是动态分配路径的设计,当前 R1 固定区域模型不经过此路径(`allocatePrivate` / `allocateShared` / `createInstance` 代码仍在 `InstanceManager` 里,但没有调用点)。现行分配见 12.1a。

`allocate(player, difficulty)` 是入场流程唯一入口,返回 `InstanceState`(或背压拒绝码)。私有与共享走不同算法,由 `serverconfig/miningdim-server.toml` 的 `instance.sharedByDefault`(默认 false)与玩家是否组队共同决定。

分配决策表:

| 场景 | ownerKey 取值 | 复用条件 | 否则 |
| --- | --- | --- | --- |
| 单人私有 | player.uuid | 存在 ownerKey==该 uuid 且 difficulty 匹配且未销毁的实例 | 新建 |
| 组队私有 | teamId(见组队规则 14.5) | 存在 ownerKey==teamId 且 difficulty 匹配的实例 | 新建 |
| 共享 | null | 该 difficulty 共享池中存在 playerSet.size() < shareCap 的实例 | 池全满则新建,达全局上限则背压 |

私有分配算法(主线程执行):

```
allocatePrivate(player, difficulty):
  key = resolveOwnerKey(player)            // 单人=uuid;组队=teamId
  existing = index.byOwner.get(key, difficulty)
  if existing != null && existing.genState in {READY, GENERATING, PENDING}:
      return existing                       // 私有实例对归属者唯一,直接复用
  if totalInstances >= globalCap:
      return BackpressureResult(reason=GLOBAL_CAP)   // 见 12.3
  return createInstance(key, difficulty, shared=false)
```

共享分配算法(主线程执行):

```
allocateShared(player, difficulty):
  pool = index.sharedPool.get(difficulty)   // List<InstanceState>,按 createdTick 升序
  for inst in pool:
      if inst.genState in {READY, GENERATING, PENDING} && inst.playerSet.size() < shareCap:
          return inst                       // 最早创建且未满者,利于聚合玩家
  if totalInstances >= globalCap:
      return BackpressureResult(reason=GLOBAL_CAP)
  return createInstance(null, difficulty, shared=true)
```

`createInstance` 流程(DECIDED):

1. `instanceId = counter.nextInstanceId()`(持久自增,见 12.4)。
2. `seed = deriveSeed(globalSeed, instanceId)`(派生,非 seed++)。
3. `regionBox = regionGrid.claimNextFreeRegion()`(网格分配,实例间留 >=1 区块实心缓冲带,符合 D1)。
4. 写入 `SavedData` 并 `setDirty()`,`genState = PENDING`。
5. 提交体素生成任务到工作线程池(纯计算,符合 D8);生成完成后由工作线程 `server.execute()` 回主线程把 `genState` 置 `READY`。

注意:分配阶段不写任何方块,不强制实例立刻 `READY`;入场流程负责等待 `READY` 后再传送(见 14.3),避免玩家掉虚空。

### 12.3 [SUPERSEDED] 容量上限与背压(代码保留,R1 模式下不生效)

状态:SUPERSEDED。以下是动态分配路径的设计。R1 下实例数恒为 3,永远撞不到 `globalCap`,`backpressureOrQueue` / `pollQueue` 不会被触发,也不存在排队位次。`TeleportResultS2C` 的 `QUEUED` 结果码与 `queuePos` 字段因此在现行版本里永不出现。

两级容量限制,均可配置:

| 配置项 | 默认 | 语义 |
| --- | --- | --- |
| instance.globalCap | 32 | 全 mod 同时存活实例上限 |
| instance.shareCap | 8 | 单个共享实例的最大在场人数 |
| instance.overflowPolicy | REJECT | 撞上限时 REJECT 拒绝 / QUEUE 排队(spec 里的真实键名,旧稿写作 `queueEnabled` 的布尔项从未落地) |

注:旧稿另列的 `instance.queueTtlTicks`(排队等待上限)在 `MiningServerConfig` 中不存在,排队超时时长没有配置入口。

背压策略:

- `overflowPolicy == REJECT`:超 `globalCap` 立即返回 `BackpressureResult(GLOBAL_CAP)`,入场流程提示"矿山繁忙,请稍后"。
- `overflowPolicy == QUEUE`:请求进入 `allocationQueue`(FIFO,持有 player.uuid + difficulty + enqueueTick)。每当一个实例被 GC 回收(refCount 归零销毁,见 12.6),主线程 `pollQueue()` 取队首重试分配。
- 排队不占用 region,不分配 instanceId,纯请求级等待,避免资源提前占用。

region 网格本身的容量与 `globalCap` 解耦:`regionGrid` 理论可分配区域数远大于 `globalCap`,真正约束是 `globalCap` 与服务器算力。被 GC 的实例其 region 标记为 free 供复用。

### 12.4 instanceId 与 seed 分配(DECIDED,补 Major 并发安全)

id 与 seed 的分配是并发安全的关键路径。锁定规则:

- `instanceId` 用持久自增 `long`,由 `SavedData` 中的 `nextInstanceId` 计数器提供;`nextInstanceId()` 读取当前值、返回、自增并 `setDirty()`。绝不复用已销毁实例的 id(避免 Capability 里残留的旧 instanceId 误命中)。
- `seed` 派生而非 seed++:`deriveSeed(globalSeed, instanceId)` 用 `com.google.common.hash.Hashing` 或与体素生成一致的 hash 混合,保证 id 相邻的实例 seed 不相邻、不可预测,符合 D3 确定性与 D6。

```
deriveSeed(globalSeed, instanceId):
  // 与 D3 全局阶段 RandomSource 同源的混合;此处用 SplitMix64 风格 finalizer
  z = globalSeed ^ (instanceId * 0x9E3779B97F4A7C15L)
  z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L
  z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL
  return z ^ (z >>> 31)
```

`globalSeed` 在矿山维度首次创建时确定并持久化(取存档主 seed 与一个 mod 常量混合),全程不变。

并发安全(DECIDED,D8):`nextInstanceId()`、`deriveSeed`、`regionGrid.claimNextFreeRegion()`、`createInstance`、所有计数增减都只在主线程执行。任何来自网络包处理、工作线程回调、命令线程的分配/回收请求都必须经 `server.execute(...)` 串行回主线程,杜绝 `nextInstanceId` 竞态与 region 重复分配。`InstanceManager` 内部不使用额外锁——单线程串行即正确性边界。

### 12.5 持久化方案(DECIDED,补 Critical 缺口)

持久化分两层,严格遵循 D5。

第一层:实例注册表与全局计数器用 `SavedData`(挂矿山维度的 `DimensionDataStorage`)。

```java
public class MiningSavedData extends SavedData {
    private static final String DATA_NAME = "miningdim_instances";
    private long nextInstanceId = 1L;
    private long globalSeed;
    private final Map<Long, InstanceRecord> instances = new HashMap<>();
    private final BitSet regionOccupancy = new BitSet();

    public static MiningSavedData get(ServerLevel miningLevel) {
        // 1.20.1 的三参签名: (Function<CompoundTag,T> load, Supplier<T> create, String name)
        return miningLevel.getDataStorage().computeIfAbsent(
            MiningSavedData::load, MiningSavedData::new, DATA_NAME);
    }

    @Override
    public CompoundTag save(CompoundTag tag) {
        tag.putLong("nextInstanceId", nextInstanceId);
        tag.putLong("globalSeed", globalSeed);
        ListTag list = new ListTag();
        for (InstanceRecord rec : instances.values()) list.add(rec.toNbt());
        tag.put("instances", list);
        tag.putByteArray("regionOccupancy", regionOccupancy.toByteArray());
        return tag;
    }
    // load(CompoundTag) 对称还原
}
```

`InstanceRecord` 序列化字段对应 12.1 表中标记"持久化=是"的列(instanceId/seed/difficulty/regionBox/playerSet/ownerKey/shared/createdTick/lastEmptyTick/genState)。`refCount/active` 不持久化,启动时由 `playerSet.size()` 重算。`genState` 按 `name()` 存七值全集,不得按旧五值表裁剪白名单(12.1)。

R1 另有两组字段落在同一份 `SavedData` 上:每难度一个的 `fixedInstanceId`(12.1a 的认领依据),以及 D3 滑动重置的世界 X 游标(`allocateRegionOriginX`,13.4)。

`get` 必须用 1.20.1 的三参 `computeIfAbsent(load, create, name)`;`SavedData.Factory` 是 1.20.2+ 才引入的类型,本目标版本严禁套用——照旧稿写会直接编译失败,而旧稿还特意声称那是"1.20.1 签名"。任何字段修改后必须 `setDirty()`,否则不落盘(评审常见漏点,标 Major)。

第二层:玩家级数据用 Forge Capability。承载"进入矿山前的回退状态 + 当前实例 + danger",用于死亡/换维度/断线重连恢复(见 14.6)。

| Capability 字段 | 语义 | 复制规则(PlayerEvent.Clone) |
| --- | --- | --- |
| prevDimension | ResourceKey\<Level\> 进入前所在维度 | 始终复制 |
| prevPos | Vec3 进入前坐标 | 始终复制 |
| prevGameMode | GameType 进入前游戏模式 | 始终复制 |
| currentInstanceId | long 当前所在实例;不在矿山为 -1 | 死亡(wasDeath)按 14.6 策略处理;换维度复制 |
| danger | float 当前危险值(见第十章 D7) | wasDeath 时按 D7 决定清零或保留 |

实现要点(1.20.1 Forge,DECIDED):

- `AttachCapabilitiesEvent<Entity>` 上判断 `event.getObject() instanceof Player` 后 attach。
- Provider 实现 `ICapabilitySerializable<CompoundTag>`,内部持有 `LazyOptional<MiningPlayerData>`。
- 监听 `PlayerEvent.Clone`:`event.isWasDeath()` 为 true 是死亡重生,需手动从 `getOriginal()` 拷贝(死亡时原实体能力会重置);换维度(wasDeath=false)同样拷贝。拷贝前对原 Provider 调 `reviveCaps()` 再读取,读完 `invalidateCaps()`。
- 跨维度切换的能力可见性:1.20.1 中 `getOriginal()` 实体在 Clone 时其 caps 已 invalidate,必须 `reviveCaps()` 临时恢复后读取,这是 1.20.1 的强制写法。

### 12.6 引用计数、空实例 GC 与离开路径统一(引用计数 DECIDED;GC 部分 R1 下不生效)

状态:本节的引用计数与离开路径统一仍然生效;"空实例 GC 与 region free 复用"部分对三个固定实例不触发——它们 `shared = true` 且常驻,`lastEmptyTick` 恒为 -1,永远进不了 GC 候选。

`refCount == playerSet.size()`,不独立维护计数器,杜绝两者漂移。所有"玩家离开实例"的路径必须统一汇聚到单一 `onPlayerLeaveInstance(player, instanceId, reason)`,在主线程执行 `playerSet.remove(uuid)`。

必须监听的全部离开路径(评审重点,逐一覆盖):

| 事件 | 触发场景 | 处理 |
| --- | --- | --- |
| PlayerEvent.PlayerLoggedOutEvent | 玩家断线/退出 | 离开当前实例,但保留 Capability 回退态以便重连 |
| PlayerEvent.PlayerChangedDimensionEvent | 主动离开矿山维度 | from==mining 时离开实例 |
| PlayerEvent.PlayerRespawnEvent | 在矿山死亡后重生到他处 | 若重生不在原实例则离开 |
| LivingDeathEvent / 死亡传送 | 死亡瞬间(配置死亡是否踢出实例) | 见 14.6 死亡策略 |
| 命令 /mining leave、退出 GUI | 主动撤离 | 传送回 prevPos 并离开 |

GC 流程(DECIDED):

```
onPlayerLeaveInstance(player, instanceId, reason):
  inst = index.get(instanceId); if inst == null: return
  inst.playerSet.remove(player.uuid)
  savedData.setDirty()
  if inst.playerSet.isEmpty():
      inst.active = false                          // 暂停 tick(见 12.7)
      inst.lastEmptyTick = server.getGameTime()
      releaseForceLoadTickets(inst)                // 取消强加载
      // 不立即销毁,进入 TTL 宽限期
  pollQueue()                                       // 腾出名额,唤醒排队请求
```

空实例宽限期与销毁(DECIDED):

| 配置项 | 默认 | 语义 |
| --- | --- | --- |
| perf.emptyInstanceTtlSeconds | 300(5min) | refCount 归零后保留多久再进入 GC 候选 |
| perf.gcGraceSeconds | 120 | 进入候选后再等多久才真正回收 |
| perf.gcScanIntervalTicks | 200(10s) | GC 扫描周期 |

键名以 16.2.10 与 `MiningServerConfig` 的 `perf` 段为准(旧稿写作 `instance.emptyTtlTicks` / `instance.gcScanInterval`,这两个键不存在)。单位也统一成秒,不要再用 6000 tick 的写法。

每 `gcScanInterval` 在维度 tick 末扫描:对 `playerSet.isEmpty() && lastEmptyTick >= 0 && now - lastEmptyTick >= emptyTtlTicks` 的实例执行销毁:

1. 二次确认 `playerSet.isEmpty()`(防 TTL 内有人重新进入的边界态)。
2. 释放该 region 的全部强加载 ticket。
3. `regionGrid.free(regionBox)`、`instances.remove(instanceId)`、`setDirty()`。
4. instanceId 不回收复用。

注:旧稿此处的第 2 步是"文件级删除该 region 区块"。文件级删除随 13.4 的滑动重置一并废止,退役 region 的磁盘回收统一交给 `RetiredRegionGc` 逐区块异步节流处理(13.4)。

宽限期价值:玩家短暂离线/换维度往返时实例仍在,避免反复重生成开销;同时通过 TTL 防止空实例长期占用 `globalCap` 名额。R1 下这条路径对三个固定实例不触发。

### 12.7 无人在场时暂停 tick(DECIDED,补 Minor 缺口)

`active == false`(playerSet 为空)的实例必须停止一切非必要 tick,降低空实例对 TPS 的拖累:

| 子系统 | active=false 时行为 |
| --- | --- |
| MobPressureSystem(第十章) | 完全暂停,不评估 danger、不刷怪 |
| 动态陷阱(第七章) | 暂停身后刷怪/坍塌/岩浆喷发调度 |
| danger 衰减 | 玩家已离开,danger 随 Capability 走,不在实例侧 tick |
| 区块强加载 ticket | `releaseForceLoadTickets` 取消,允许区块卸载省内存 |
| GC 扫描 | 仍参与(由维度级定时器驱动,非实例 tick) |

实现:矿山维度的 tick 回调(`LevelTickEvent` END 阶段或自定义维度 tick)只遍历 `activeInstances` 子集驱动压力/陷阱;`active` 翻转时维护该子集。强加载用 `ServerLevel.setChunkForced` 或 `ForgeChunkManager.forceChunk(level, modId, owner, x, z, add, ticking)`(1.20.1 真实 API),实例创建/有人进入时加,清空时移除。

### 12.8 启动重建与孤儿清理(DECIDED)

服务器启动(`ServerStartedEvent` 后,矿山 `ServerLevel` 可用时)从 `SavedData` 重建 `InstanceManager` 内存视图:

1. `MiningSavedData.get(miningLevel)` 加载注册表与计数器。
2. 对每条 `InstanceRecord` 重建 `InstanceState`;`playerSet` 此刻应视为空(玩家尚未登录),`refCount` 归零,`active=false`,`lastEmptyTick` 取存档值或置当前 tick。
3. `regionGrid` 由 `regionOccupancy` BitSet 还原占用,与 `instances` 的 regionBox 交叉校验。

孤儿清理(DECIDED):

| 孤儿类型 | 判定 | 处理 |
| --- | --- | --- |
| genState==GENERATING/RESETTING | 上次关服时重置被打断,内存态丢失 | 置 READY 或重跑一次重置;空实例时直接销毁回收(固定实例不销毁) |
| genState==FAILED | 重置失败残留 | 销毁并 free region(固定实例改为重跑重置) |
| regionBox 与占用位图冲突 | 数据不一致 | 记 Major 日志,以 instances 为准修正位图。注意固定/滑动实例的几何不在网格 stride 上,`RegionGrid.isAligned` 判否即豁免位图校验,不得直接走 `slotForRegion`(会抛 IAE) |
| 区块数据存在但无对应 record | 退役 region 的残留区块 | 交给 `RetiredRegionGc` 逐区块异步回收(13.4),不按 .mca 文件整删 |

重建期间禁止任何玩家分配请求介入(此时玩家未登录,天然安全);全过程主线程串行,符合 D8。重建末尾额外做一件 R1 专属的事:预建/认领三个固定难度实例并写入 `RegionLayout` 快照(12.1a)。

---

## 十三、重置系统

本章重写原文档第 10 节,锁定矿山实例的重置语义。核心约束不变:绝不同步逐块 setBlock 删除整个 region——那会在主线程一次性卸载/重写上万区块,阻塞超过服务器 watchdog 阈值(默认 60s)直接导致 server crash(标 Critical)。

满足该约束的手段已换代:原方案是"区块文件级删除 + 限速重生成",现行方案是 D3 滑动重置——把整块 region 挪到一块从未生成过的新世界坐标,旧坐标的区块原样留在磁盘上不再访问,重置本身既不删也不重生成,主线程开销接近于零。详见 13.4。

### 13.1 重置类型与触发(DECIDED)

| 类型 | 触发源 | 在场玩家处置 | 倒计时广播 |
| --- | --- | --- | --- |
| 手动重置 | 命令 `/mining reset <instanceId> [reseed]`(OP level 2) | 强制撤离 | 否(线上实现无倒计时,也无二次确认,见 17.2) |
| 玩家请求刷新 | 未实现 | — | — |
| 定时重置(R6) | `AutoResetScheduler` 按难度独立计时:`reset.autoResetHoursEasy/Medium/Hard` 到期 | 倒计时结束后 `IResetService.evacuate` 撤离 | 是,`reset.autoResetWarnSeconds`(默认 60s)内每秒向该区在场玩家广播剩余秒数 |
| 空实例 GC 重置 | 12.6 TTL 到期销毁 | 无人(前置已空) | 否。R1 下对三个固定实例不触发 |
| 全局重置 | 命令 `/mining reset all`(OP level 2) | 逐实例串行撤离 | 否 |

手动 vs 定时区别:手动重置立即进入撤离-滑动流程;定时重置必带 `autoResetWarnSeconds` 倒计时广播。

R6 定时重置的实现要点(`reset/AutoResetScheduler.java`):由 `ResetSystem` 每 20 tick(每秒)驱动一次;每难度维护独立的 Phase 与剩余秒数,互不影响;计时时钟统一取矿山维度的 `getGameTime()`(只在服务端运行时推进,且 vanilla 自身持久化,重启后比对正确);到点先撤离再以 `IResetService.ResetMode.NEW_SEED` 触发重置;`lastReset` 持久化于 `AutoResetData`,供重启后续算;任一 `autoResetHours*` 配成 0 即关闭该难度的自动重置。

### 13.2 重置状态机与前置条件(DECIDED)

重置只能作用于 `genState == READY` 的实例;进入重置即置 `RESETTING`,期间拒绝一切传入分配/传送。

```
requestReset(instanceId, type, force):
  inst = index.get(instanceId)
  if inst == null || inst.genState != READY: return REJECT_BAD_STATE
  if inst.playerSet not empty:
      if type == 定时 && !force:
          schedule retry after retryDelay; broadcast countdown; return DEFERRED
      else:
          evacuateAll(inst)              // 强制撤离,见 13.3
  if inst.playerSet not empty after evacuate:   // 撤离失败(极端边界)
      return REJECT_OCCUPIED
  inst.genState = RESETTING
  enqueueResetJob(inst)                  // 进入异步重置队列,见 13.4
  return ACCEPTED
```

非空实例处置策略(DECIDED,补 Critical 缺口):

| 策略配置 reset.occupiedPolicy | 行为 |
| --- | --- |
| DEFER(默认,定时重置) | 推迟到无人,或倒计时结束强制撤离 |
| FORCE(手动/运维) | 立即撤离所有在场玩家后重置 |
| REJECT | 非空直接拒绝(仅玩家请求刷新场景) |

### 13.3 在场玩家强制撤离(DECIDED,补 Critical 缺口)

撤离必须在删除区块之前完成,且覆盖"正在传送中/刚断线重连"的边界态,否则玩家会被卡在被删除的区块里掉虚空。

撤离流程(主线程,D8):

```
evacuateAll(inst):
  for uuid in snapshot(inst.playerSet):
      player = server.getPlayerList().getPlayer(uuid)
      if player == null:                  // 离线玩家:在场集合里但已断线
          markPendingEvacuation(uuid)      // 标记,登录时立即送回(见 14.6)
          inst.playerSet.remove(uuid)
          continue
      cap = player.getCapability(MINING_CAP)
      target = resolveFallback(cap)        // prevDimension+prevPos;无效则维度 spawn
      player.teleportTo(target.level, target.x, target.y, target.z, ...)  // 主线程
      onPlayerLeaveInstance(player, inst.instanceId, EVACUATED)
```

边界态处理(DECIDED):

| 边界态 | 风险 | 处理 |
| --- | --- | --- |
| 玩家正在传送进入该实例(force-load 等待中) | 撤离与入场竞态 | 入场流程在 `genState==RESETTING` 时中止并回滚,见 14.3 |
| 玩家刚断线但仍在 playerSet | 无在线实体可传送 | `markPendingEvacuation`,登录时由 14.6 送回回退点 |
| 玩家死亡动画/重生窗口 | teleportTo 时机敏感 | 延迟到 PlayerRespawnEvent 后或直接改其重生坐标为回退点 |

撤离目标优先级:Capability.prevDimension+prevPos(有效性校验:维度存在、坐标安全) > 该玩家主世界重生点 > 主世界 spawn。

### 13.4 滑动重置流程(DECIDED,D3;Critical 性能核心)

重置的物理执行是一个四阶段分帧状态机,由 `ResetSystem` 每服务端 tick 推进一次 `ResetJob`。核心手法:不删区块、不重生成,而是把实例整块滑到一块从未生成过的新世界坐标。

| 阶段 | 线程 | 动作 |
| --- | --- | --- |
| UNLOAD | 主线程 | 撤离在场玩家;释放该实例的全部区块强加载 ticket(必须先于几何变更,否则旧 owner / 旧 box 发出的 ticket 撤不掉);按 `mode` 计算本次的目标 seed |
| REGEN | 主线程 | 调 `IInstanceManager.slideRegion` 把 region 整体滑到 `MiningSavedData` 游标分配的新坐标,写回新 `regionBox`/`seed` 后直接置 `READY`(离线生成已下线,地形由 `minecraft:noise` 按需生成);随后逐 tick 轮询 `genState` 直到 `isEnterable()` / `FAILED` / 超时。通常首帧即通过,轮询只作失败态兜底 |
| SETTLE | 主线程 | 清该实例的影子态(`liveMobs`),广播 `MiningServices.fireInstanceReset` 驱动陷阱/矿物/出生/压力等子系统按实例缓存失效重算;停满 `MIN_SETTLE_TICKS`(2)后落定 |
| DONE | 主线程 | `genState = READY`(幂等),兑现 reset future |

REGEN 阶段的等待超时为 `REGEN_TIMEOUT_TICKS = 6000`(5 分钟),超时判失败,绝不无限挂着 future。本任务自身不做任何加载限速——区块由玩家进入时 `chunk/ChunkTicketManager` 的滑动窗口按需强加载,重置流程里没有"限速重生成"这个概念。

滑动重置的四条硬约束(全部是不可调的正确性前提,不是调参项):

| 编号 | 约束 | 真源 | 违反后果 |
| --- | --- | --- | --- |
| (a) | 相邻两代 region 之间必须留出 `SLIDE_SEPARATION_BLOCKS = 1024` 格空白,大于原版最大视距 32 区块(512 格) | `MiningConstants.SLIDE_SEPARATION_BLOCKS` | 间隔不足时,旧 region 里的玩家会提前把下一块 region 的区块按 `mining_wall` 生成成整列基岩,滑过去就是一坨实心基岩 |
| (b) | 世界 X 游标 `MiningSavedData.allocateRegionOriginX` 只增不减,越过 `MAX_REGION_WORLD_X = 25,000,000` 直接抛 `IllegalStateException`,绝不回绕复用旧坐标 | `MiningConstants.MAX_REGION_WORLD_X` | 复用旧坐标就是把玩家挖空的旧图当新图发。每次重置推进 `REGION_SIZE_X + SLIDE_SEPARATION_BLOCKS = 1280` 格,默认三难度重置节奏下约 888 天耗尽,耗尽后重置彻底不可用 |
| (c) | 世界 X 越过 2^23(约 298 天)后,原版实体位置与渲染的 float 精度开始劣化 | 原版浮点精度 | 已知遗留,与 (b) 同根:坐标空间单调消耗 |
| (d) | 退役 region 的地形区块由 `reset/RetiredRegionGc` 异步节流回收:每 100 tick 最多清 16 个区块,逐区块经 `ChunkStorage.write(pos, null)` 从 `.mca` 扇区表摘除,一块 region 共 256 区块、约 16 轮 80 秒清完 | `RetiredRegionGc` | 不能按 `.mca` 文件整删:一个 `.mca` 覆盖 32x32 区块(512x512 格),而 region 只有 256x256 格,多块 region 会落在同一个文件里,按文件删会连带毁掉邻居 |

关于 (d) 的回收边界(据实报备):只覆盖 `world/region/` 的地形区块,不含 `world/entities/`(存储句柄挂在 `ServerLevel.entityManager` 私有字段上,不反射/不 mixin 拿不到)与 `world/poi/`(本维度恒空:无村民、床不可用、无任何 POI 方块)。每个区块清盘前单独判两件事——当前未加载、且不在任何强加载票下;有一条不满足就跳过留到下一轮。这是延迟回收,不是"不回收"。

重置相关配置键(以 `MiningServerConfig` 的 `reset` 段为唯一真源,共八项,详见 16.2.9):`cooldownSeconds`、`requireEmpty`、`kickOnForceReset`、`confirmationWindowSeconds`、`autoResetHoursEasy/Medium/Hard`、`autoResetWarnSeconds`。

旧稿列出的 `reset.maxChunksPerTick`、`reset.maxMillisPerTick`、`reset.countdownSeconds`、`reset.retryDelayTicks`、`reset.deleteUseFileLevel` 五个键在代码中一个都不存在,已随限速重生成与文件级删除一并作废。倒计时口径统一引用 `reset.autoResetWarnSeconds`(默认 60 秒),不要再写 30 秒的 `countdownSeconds`。`ResetJob.tick()` 的 `pendingChunks` / `generateChunkFromBitset` 伪码同样作废,现行状态机见上表。

### 13.5 reset 接口与 reseed 语义(DECIDED)

重写原伪逻辑 `reset(): seed++ regenerate_world()`(seed++ 违反 D6,删除):

```
reset(instanceId, mode: ResetMode)      // 代码签名: IResetService.reset(long, ResetMode)
  // SAME_SEED: 复用原 seed,resetGeneration 不变
  // NEW_SEED : resetGeneration+1,派生新 seed = deriveSeed(globalSeed, instanceId, resetGeneration)
  //            同一实例多次刷新用 resetGeneration 计数器派生,仍非 seed++
```

`resetGeneration` 随实例持久化,每次 `NEW_SEED` 自增,参与 seed 派生第三维,保证多次刷新不重复且可追溯。

重要口径变化(D3 滑动之后):`SAME_SEED` 不再等于"再打一遍同一张图"。地形现在由原版 `minecraft:noise` 按世界坐标生成(7.0.4),而任何一次重置都会把 region 滑到一段新的世界坐标,因此两种模式产出的地形都是全新的;`seed` 只决定本 mod 自有的派生随机(陷阱触发、压力抖动等)。`SAME_SEED` 现在的实际用途是"重置但不扰动这些派生随机",不是复现同一张图。命令层的 `[reseed]` 字面量对应 `NEW_SEED`,不带即 `SAME_SEED`。

`AutoResetScheduler` 的定时重置一律用 `NEW_SEED`。

### 13.6 全局重置(DECIDED)

`/mining reset all` 定义为逐实例串行:遍历所有实例(R1 下即三个固定难度实例),逐个走 13.2 流程。滑动重置本身不做区块 IO,不存在并发卸载导致 IO 风暴的问题;退役区块的回收由 `RetiredRegionGc` 统一节流(13.4)。运维专用,需 OP level 2(见 14.1 与 17.2)。

线上实现不带倒计时广播、不带二次确认——`entry.MiningCommands.resetAll()` 立即执行(17.2)。

---

## 十四、玩家进入与传送流程

本章重写原文档第 11 节,锁定从入口触发到落地出生的完整链路,补齐难度门控、组队规则、断线重连恢复等 Major 缺口。核心安全约束:传送前必须确保目标区块 force-load 完成且 `genState==READY`,否则玩家掉虚空。

### 14.1 入口与命令(DECIDED)

| 入口 | 形态 | 说明 |
| --- | --- | --- |
| 入口 GUI | 自定义 `AbstractContainerMenu` + Screen,选难度/刷新模式 | 主入口;reseed=false(再打同图)与 reseed=true(换图)为两个按钮 |
| 传送门方块 | 自定义方块,右键触发分配 | 难度由方块变体或相邻告示牌决定 |
| 入场物品 | 自定义物品(矿山券),右键消耗触发 | 与难度门控的"入场券"机制复用(见 14.4) |
| NPC | 村民职业/自定义实体对话 | 触发同一 GUI |
| 命令 | /mining enter \<difficulty\> [reseed] | 玩家级;/mining reset、/mining reset all 需 OP(level 2) |

命令树(Brigadier,DECIDED):

```
/mining
  enter <difficulty: easy|medium|hard> [reseed]      // 玩家可用
  leave                                               // 撤离回回退点
  reset <instanceId> [reseed]                         // OP
  reset all                                           // OP
  info [instanceId]                                   // 查询实例状态
```

### 14.2 进入主流程(DECIDED)

完整链路(全程主线程编排,纯计算下放工作线程,D8):

```
enter(player, difficulty, reseed):
  1. entryFee = config.entryFee(difficulty)          // 16.2.11;0 = 免费
     gateCheck(player, difficulty, entryFee)         // 难度门控 + 余额判定,见 14.4;失败提示并中止
                                                     // 余额不足返回 GateResult.INSUFFICIENT_FUNDS 并提示差额
  2. snapshotFallback(player)             // 写 Capability:prevDim/prevPos/prevGameMode
  3. inst = InstanceManager.allocate(player, difficulty)   // R1 下直接路由到固定实例,见 12.1a
  4. if !inst.genState.isEnterable():
        awaitReady(inst, timeout)         // 超时回退,见 14.3(R1 下实例恒为 READY,此分支不走)
  5. forceLoadSpawnChunks(inst)           // 强加载 spawn 周边 3x3 区块
  6. awaitChunksLoaded(inst.spawnChunks)  // 确认加载完成再传送(关键防虚空)
  7. spawn = SpawnSystem.findSpawn(miningLevel, inst)      // 真实区块上环形扫描安全站立点(第十一章)
  7.5 if entryFee > 0:
        if !EconomyServices.economyService().tryCharge(player, CREDIT, entryFee):
            rollback(pendingEnter); 提示 INSUFFICIENT_FUNDS; 中止
        // 余额门与此处可能相隔多个 tick,故必须按请求时快照的费用再扣一次;
        // 免费配置必须短路,货币层拒绝 amount <= 0。费用是纯 sink,不转入任何玩家账户
  8. cap.currentInstanceId = inst.instanceId; initDanger(player, difficulty)
     player.teleportTo(miningLevel, spawn) // Capability 必须写在传送之前,见 3.4 末尾
  9. inst.playerSet.add(player.uuid); setDirty()
  10. inst.active = true                   // 启动压力/陷阱 tick
```

步骤 4-6 是防掉虚空的核心:绝不在实例不可进入或区块未加载时传送。步骤 7.5 的扣费落点在"解析出生点之后、传送之前",与 `EntryGateway.completeEnter` 一致。

### 14.3 force-load 等待门控与竞态处理(DECIDED,Critical 防虚空)

传送前的区块就绪保证:

- `forceLoadSpawnChunks` 用 `ForgeChunkManager.forceChunk(miningLevel, "miningdim", ownerEntity, cx, cz, true, true)`(1.20.1 真实 API)对 spawn 周边 3x3 区块加 ticking ticket。
- `awaitChunksLoaded` 校验 `miningLevel.getChunkSource().hasChunk(cx, cz)` 且区块 `getStatus() == FULL`;未就绪则下一 tick 重检,最多 `EntryGateway.CHUNK_WAIT_TIMEOUT_TICKS`(200 tick = 10s;硬编码常量,不是配置键)。force-load 窗口是以出生候选点为心的 3x3 区块(`SPAWN_FORCE_RADIUS_CHUNKS = 1`),`SpawnSystem` 的扫描半径必须与之对齐(16 格),否则会扫到未强加载的区块。
- 超时则回退:撤销 force ticket,提示玩家"矿洞加载超时",不传送,Capability 不变。

竞态处理(DECIDED):

| 竞态 | 处理 |
| --- | --- |
| 等待 READY 期间实例进入 RESETTING(被运维重置) | awaitReady 检测到 genState 变更,中止入场,回滚 force ticket,提示重试 |
| 等待期间实例被 GC(理论不会:有 pending 进入) | 入场登记会临时占位防 GC(进入等待即 playerSet 预留或 pendingEnter 标记) |
| 玩家在等待期间断线 | 取消该入场任务,撤销 force ticket,不修改 playerSet |
| 多人同时入场同一共享实例 | 各自独立走 force-load,playerSet.add 在主线程串行,无竞态 |

为防止"等待 READY 期间实例被空 GC 销毁",入场一旦 `allocate` 成功即在 `inst` 上置 `pendingEnter++`,GC 与重置均跳过 `pendingEnter > 0` 的实例;传送完成或失败回滚时 `pendingEnter--`。

### 14.4 难度解锁门控(DECIDED,已落地)

门控校验 `gateCheck(player, difficulty, entryFee)`,现行版本有两道闸,均由 `entry/EntryGateway.java` 强制执行:

| 门控机制 | 规则 | 实现 | 状态 |
| --- | --- | --- | --- |
| 等级门槛 | Easy 需矿工职业等级 >= 1;Medium >= 4;Hard >= 8 | `JobServices.jobService().level(player, JobId.MINER)` -> `MinerLevelGate.canEnter`;不读 `player.experienceLevel` | DECIDED,已落地 |
| 入场费余额 | `entryFee > 0` 时要求 CREDIT 余额 >= 费用;实际扣款在 14.2 步骤 7.5 | `EconomyServices.economyService().creditBalance(player)` | DECIDED,已落地(默认三档均为 0,即当前不收费) |
| 前置成就/进度 | 未实现 | 原设想:Hard 需完成"通关 Medium 一次"自定义 advancement | TODO,无代码 |
| 入场券 | 未实现 | 原设想:Hard 需消耗 1 张 Hard 矿山券物品 | TODO,无代码;入场成本诉求已由入场费机制承接 |

等级门槛的数值真源是 `job/miner/MinerConstants.java` 的 `MIN_LEVEL` / `MEDIUM_MIN_MINER_LEVEL`(=4) / `HARD_MIN_MINER_LEVEL`(=8),裁决见 `Miner_Job_DesignSpec.md` 第八章;本文档不重复维护具体数值。

口径变更记录:旧稿写的是"Medium 需经验等级 >= 10;Hard >= 25,读 `player.experienceLevel`"。集成阶段(Miner_Job_DesignSpec 第八章)已把口径从原版经验等级改为矿工职业等级,数值一并换成 4/8。`docs/archive/delivered/TaskSpec_Mining_EntryFee.md` 早已点名这处漂移,本次一并订正。

门控失败返回明确原因码(`LEVEL_TOO_LOW` / `INSUFFICIENT_FUNDS`),GUI/命令给本地化提示。入场费三档数值(16.2.11)仍标 PENDING 待产出实测后标定,等级门槛不再是 PENDING。

### 14.5 组队规则(DECIDED 结构,数值 PENDING)

组队基于原版 `PlayerTeam`(Scoreboard team)或自定义队伍系统,`teamId` 作为私有实例 ownerKey。

| 规则项 | 决策 | 说明 |
| --- | --- | --- |
| 人数上限 | teamCap 默认 4(PENDING) | 超上限拒绝加入该实例 |
| danger 随人数缩放 | 启用,danger *= 1 + (n-1)*dangerPerExtraPlayer | dangerPerExtraPlayer 默认 0.15(PENDING) |
| 矿物归属 | 谁挖归谁,无共享池 | 与原版掉落一致 |
| 怪物掉落归属 | 原版仇恨/最后命中归属 | 不特殊处理 |
| 连带伤害(友伤) | 默认关闭,跟随服务器 PvP 设置 | 陷阱 AOE 对队友是否生效配置 trap.friendlyFire 默认 false |
| 中途加入 | 允许,队友 enter 时复用同 teamId 实例(若未满) | 走 allocatePrivate 命中既有 |
| 中途加入的 danger | 按当前实例 danger 接入,不重置 | 新成员继承实例压力态 |

组队私有实例:队伍任一成员 enter 时 `resolveOwnerKey` 返回 `teamId`,命中既有实例则复用,人数校验 `playerSet.size() < teamCap`。

### 14.6 断线/崩溃/关服重连恢复(DECIDED,补 Major 缺口)

恢复入口:`PlayerEvent.PlayerLoggedInEvent`。核心:玩家上次若在矿山实例,需判定该实例是否仍存活且未被重置。

登录恢复决策(DECIDED):

```
onLogin(player):
  cap = player.getCapability(MINING_CAP)
  if cap.currentInstanceId == -1: return        // 上次不在矿山,正常登录
  inst = InstanceManager.get(cap.currentInstanceId)
  loginDim = player.level().dimension()

  // 情况 A:被标记待撤离(实例已被重置/GC,见 13.3 markPendingEvacuation)
  if isPendingEvacuation(player.uuid) || inst == null || inst.genState != READY:
      sendBackToFallback(player, cap)            // 传回 prevDimension+prevPos
      clearPendingEvacuation(player.uuid); cap.currentInstanceId = -1
      return

  // 情况 B:实例存活且玩家落点仍在该 region 内
  if loginDim == MINING && inst.regionBox.contains(player.blockPosition()):
      inst.playerSet.add(player.uuid)            // 重新计入,恢复在场
      inst.active = true
      restoreDanger(player, cap)
      return

  // 情况 C:实例存活但玩家落点异常(不在 region 内)
  sendBackToFallback(player, cap); cap.currentInstanceId = -1
```

关键校验(DECIDED):

- "实例仍存活未被重置":`inst != null && inst.genState == READY` 且 `inst.instanceId == cap.currentInstanceId`(id 不复用保证不会误命中新实例,见 12.4)。
- 落点在 region 内:`inst.regionBox.contains(pos)`,防止存档损坏/坐标漂移导致卡进实心墙或缓冲带。
- 关服期间该实例若已被孤儿清理(12.8)或 GC,则 `inst == null`,走回退。

`sendBackToFallback` 用 Capability 的 `prevDimension/prevPos/prevGameMode`;若回退坐标也失效(维度被删等),降级到主世界 spawn 并记 Major 日志。

### 14.7 生命周期联动总表(DECIDED)

进入/退出与第十二章 refCount 的呼应,统一收口:

| 玩家事件 | playerSet 变化 | active 影响 | Capability 变化 |
| --- | --- | --- | --- |
| enter 成功 | add(uuid) | 置 true | 写 prev*、currentInstanceId |
| /mining leave | remove(uuid) | 空则 false | currentInstanceId=-1,清 danger |
| 死亡(配置踢出) | remove(uuid) | 空则 false | 按 14.6/D7 处理 danger |
| 换维度离开 | remove(uuid) | 空则 false | currentInstanceId=-1 |
| 断线 | remove(uuid) | 空则 false | 保留 currentInstanceId 待重连 |
| 重连且实例存活 | add(uuid) | 置 true | 保持 currentInstanceId |
| 重连但实例已重置/GC | 不加入 | 不变 | currentInstanceId=-1,回退 |
| 被强制撤离(重置) | remove(uuid) | 空则 false | currentInstanceId=-1,传回回退点 |

所有 playerSet 增减、active 翻转、Capability 写入均经 `server.execute()` 串行到主线程(D8),与 12.4/12.6 并发纪律一致。

---

## 十五、网络协议

### 15.1 设计原则与技术选型(DECIDED)

本系统的目标平台为 Minecraft 1.20.1 + Forge 47.x。该版本网络层使用 Forge 自有的 `SimpleChannel`,通过 `NetworkRegistry.newSimpleChannel(...)` 创建,逐 packet 调用 `channel.registerMessage(...)` 注册。注意 Mojang 原生的 custom payload(`CustomPacketPayload` + `PayloadRegistrar`)是 1.20.4+ 才引入的 API,本平台不适用,严禁套用。

| 编号 | 原则 | 说明 | 状态 |
|---|---|---|---|
| N1 | 服务端权威 | 所有世界写操作(实例生成/重置/方块写入/传送/刷怪)仅在逻辑服务端执行;客户端只发意图(C2S)与接收结果(S2C)做渲染/UI。 | DECIDED |
| N2 | 包驱动渲染态 | 玩家"环境压力"的视觉表现(若涉及客户端渲染滤镜、HUD)不得让客户端自行推断,必须由 `DangerSyncS2C` 携带服务端计算后的 danger 值驱动。 | DECIDED |
| N3 | 频率纪律 | 高频状态(danger)按"每秒"而非"每 tick"同步,降低带宽与序列化开销(对应 D7 评估降频)。 | DECIDED |
| N4 | 线程回主线程 | 所有 packet handler 内部对世界/玩家的访问,必须经 `ctx.enqueueWork(...)` 切回对应逻辑端主线程,handler 入口处于网络 I/O 线程,直接碰世界对象是数据竞争。 | DECIDED |
| N5 | 协议版本兼容判定 | channel 携带 `PROTOCOL_VERSION`,客户端/服务端版本不匹配时握手拒绝(`requireServer`/`requireClient` 语义),防止跨版本字段错位解析。 | DECIDED |

### 15.2 Channel 构建与版本协商(DECIDED)

下面是结构示例,不是实时快照;`PROTOCOL_VERSION` 的现值与 `register()` 的完整包序一律以源码 `network/MiningNetwork.java` 为准,本文档不重复维护逐行内容(包表见 15.3)。

```java
public final class MiningNetwork implements IMiningNetwork {
    private static final String PROTOCOL_VERSION = "2";
    public static final SimpleChannel CHANNEL = NetworkRegistry.newSimpleChannel(
            new ResourceLocation("miningdim", "main"),
            () -> PROTOCOL_VERSION,
            PROTOCOL_VERSION::equals,   // clientAcceptedVersions
            PROTOCOL_VERSION::equals);  // serverAcceptedVersions

    private int nextId = 0;
    private int nextId() { return nextId++; }

    // 在 FMLCommonSetupEvent 期(enqueueWork 内)统一注册,保证 id 在两端顺序一致
    public void register() {
        CHANNEL.registerMessage(nextId(), DangerSyncS2C.class,
                DangerSyncS2C::encode, DangerSyncS2C::decode, DangerSyncS2C::handle,
                Optional.of(NetworkDirection.PLAY_TO_CLIENT));
        // ... 其余包按 15.3 的顺序依次登记 ...
    }
}
```

协议版本变更规则(DECIDED):协议字段、尤其是 packet discriminator 集合的增删,必须同步 bump `PROTOCOL_VERSION`。理由是 discriminator 按注册顺序自增分配——删掉中间一个包,其后所有包的编号会整体下移一位;若新旧版本混用而协议版本不变,握手会通过但包体被错位解码(静默数据损坏),提版本能让不匹配的客户端在握手期直接被拒,而不是放行后崩在解码阶段。现值 `"2"`,`1 -> 2` 对应删除特勤原生扫描面板的两个包(F065)。

关键事实(1.20.1 真实存在,严禁改写):
- `registerMessage` 签名为 `registerMessage(int index, Class<MSG>, BiConsumer<MSG, FriendlyByteBuf> encoder, Function<FriendlyByteBuf, MSG> decoder, BiConsumer<MSG, Supplier<NetworkEvent.Context>> handler, Optional<NetworkDirection> direction)`。显式传入 `NetworkDirection` 用于在握手期做方向校验。
- 注册次序两端必须完全一致(id 决定线缆上的 discriminator),因此用集中式 `register()` 同一份代码两端共用。
- 注册时机:`FMLCommonSetupEvent` 内 `event.enqueueWork(MiningNetwork::register)`(线程安全窗口)。

### 15.3 Packet 总表(DECIDED)

方向缩写:C2S = 客户端到服务端;S2C = 服务端到客户端。处理线程一栏指 handler 内 `enqueueWork` 后最终执行所在的逻辑端主线程。

本表按 `MiningNetwork.register()` 的真实注册顺序排列;discriminator 由该顺序自增分配,故顺序本身就是协议的一部分,不得随意调换。字段以各 record 的构造签名为准。

| # | Packet | 方向 | 字段 | 触发时机 | 频率 | 处理线程 |
|---|---|---|---|---|---|---|
| 0 | DangerSyncS2C | S2C | `instanceId:long`, `danger:float`, `dangerMax:float`, `tier:byte(0安全/1警戒/2高危)`, `lightDimFactor:float(0~1)` | danger 评估降频周期产出新值,且与上次相比超过阈值 | 每秒(20 tick)上限,变化驱动 | 客户端主线程 |
| 1 | TeleportResultS2C | S2C | `result:byte(SUCCESS/QUEUED/REJECTED_FULL/REJECTED_GENERATING/ERROR 的序号)`, `instanceId:long`, `queuePos:int(-1=不适用)`, `reasonKey:String(i18n key)` | 服务端处理完进入/离开传送请求后 | 事件驱动 | 客户端主线程 |
| 2 | InstanceStatusS2C | S2C | `instanceId:long`, `difficulty:byte`, `genState:byte`, `genProgress:float(0~1)`, `playerCount:int`, regionBox 四个 int 边界 | 玩家订阅某实例(进入/打开 GUI 列表)或该实例状态变更 | 状态变更驱动 | 客户端主线程 |
| 3 | JobSyncS2C | S2C | 全职业进度快照 | 登录同步 / 等级变化 | 事件驱动 | 客户端主线程 |
| 4 | C2SWebUiRequest | C2S | Web UI 桥请求 | 面板内操作 | 事件驱动 | 服务端主线程 |
| 5 | S2CWebUiResponse | S2C | Web UI 桥回执 | 对应请求处理完 | 事件驱动 | 客户端主线程 |
| 6 | S2CWebUiEvent | S2C | Web UI 桥主动事件 | 服务端状态推送 | 事件驱动 | 客户端主线程 |
| 7 | ChampionSizeS2C | S2C | 精英怪体型系数 | 精英怪生成/属性变更 | 事件驱动 | 客户端主线程 |

跨模块说明:3 至 7 号包不属于矿区业务,分别归职业框架(`JobSyncS2C`)、Web UI 桥契约第 4 节(三个 WebUi 包)与精英怪词条规格(`ChampionSizeS2C`);它们复用同一条 `CHANNEL` 并在此集中登记 discriminator,是本信道的既定纪律。`TeleportResultS2C` 的 `QUEUED` 结果码与 `queuePos` 在 R1 下永不出现(12.3),`InstanceStatusS2C.genProgress` 恒为满(7.0.5),二者保留是为了协议兼容,不是现行行为。

REJECTED(F087):`SelectZoneC2S`(原 discriminator 0)已整包删除注销 —— 它零发送方、绕过矿工等级门直接 allocate、且从不传送玩家,是半成品入场路径。入场需求由 Web UI(`MiningWebUiActions`)与入口方块 / 命令覆盖。详见 15.4.1。

REJECTED:`OpenMiningGuiS2C` 从未注册过,见 15.5 —— GUI 打开走原版 `NetworkHooks.openScreen`。旧稿把它列进本表属记录错误。

### 15.4 各 Packet 的 encode/decode/handler 职责(DECIDED)

通用约束:encode 仅做字段顺序写入 `FriendlyByteBuf`;decode 仅做对应顺序读出并构造不可变 record;handler 一律为 `ctx.enqueueWork(() -> {...}); ctx.setPacketHandled(true);` 结构,业务逻辑在 lambda 内主线程跑。枚举用 `buf.writeEnum(...)` / `buf.readEnum(Difficulty.class)`,字符串用 `writeUtf` / `readUtf`。

15.4.1 SelectZoneC2S(REJECTED,F087)

该包已整包删除,`network` 包下不存在 `SelectZoneC2S.java`,`register()` 里也没有它的登记。删除理由(`MiningNetwork.java` 记载):零发送方、绕过矿工等级门直接 `allocate`、且从不传送玩家 —— 一条会让玩家"进了副本但人还在原地"的半成品路径。替代路径是 Web UI 请求包(`MiningWebUiActions`)与入口方块 / `/mining enter` 命令,两者都走 `EntryGateway.requestEnter` 的完整链路(14.2)。

防作弊要点仍然成立并适用于现行入场路径:客户端只表达"意图",不携带传送目标坐标;出生点由服务端 `SpawnSystem` 选取,客户端无权指定落点。

15.4.2 DangerSyncS2C(S2C,压力同步)

| 项 | 职责 |
|---|---|
| encode | 写 `instanceId`、`danger`、`dangerMax`、`tier`、`lightDimFactor` |
| decode | 按序读回 |
| handler | enqueueWork 内(客户端):写入客户端侧 `ClientDangerState` 单例,供 HUD/渲染层读取;不触发任何世界写入。`lightDimFactor` 仅用于客户端渲染滤镜(屏幕变暗 overlay),不修改世界实际光照数据。 |

N2 落地说明:"高 danger 时光照下降"如果指真实的方块光照变化,则属世界状态,由服务端写入并经正常区块同步;如果指玩家屏幕渲染压暗(氛围),则是纯客户端表现,必须由本包的 `lightDimFactor` 驱动,客户端不得自行根据本地猜测的 danger 推算。两类需求在配置中分别有开关(见 16 章 `dangerVisualMode`)。

15.4.3 TeleportResultS2C(S2C,传送结果)

| 项 | 职责 |
|---|---|
| encode | 写 `result`(enum)、`instanceId`、`queuePos`、`reasonKey` |
| decode | 按序读回 |
| handler | enqueueWork 内(客户端):根据 result 弹出对应 toast/聊天提示(用 `reasonKey` 做本地化),若 QUEUED 显示排队位次;不执行任何传送动作(传送已在服务端完成或被拒,客户端仅反馈)。 |

15.4.4 InstanceStatusS2C(S2C,实例状态)

| 项 | 职责 |
|---|---|
| encode | 写 `instanceId`、`difficulty`、`genState`、`genProgress`、`playerCount`、`regionBox` 四个 int 边界 |
| decode | 按序读回 |
| handler | enqueueWork 内(客户端):更新 GUI 列表/进度条;GENERATING 时显示 `genProgress` 进度;READY 时启用"进入"按钮。 |

### 15.5 GUI 打开:优先用 NetworkHooks.openScreen(DECIDED)

1.20.1 打开服务端驱动的容器界面,标准做法是服务端调用 `NetworkHooks.openScreen(ServerPlayer, MenuProvider, FriendlyByteBuf extraData)`,Forge 自动下发界面打开包并同步 `MenuType`。因此矿山入口 GUI 不自定义 `OpenMiningGuiS2C` 数据包,改为:

```java
// 服务端,主线程内(如方块/物品 use 回调、命令)
NetworkHooks.openScreen(serverPlayer, new MiningMenuProvider(availableInstances), buf -> {
    // 在 extraData 中写入实例列表快照,供客户端 MenuScreen 初始化
    buf.writeVarInt(availableInstances.size());
    for (InstanceSummary s : availableInstances) s.write(buf);
});
```

| 决策 | 内容 | 状态 |
|---|---|---|
| GUI 打开通道 | `NetworkHooks.openScreen` + 自定义 `AbstractContainerMenu`/`MenuProvider`,extraData 携带实例摘要 | DECIDED |
| 自定义 OpenMiningGuiS2C | REJECTED:与原生菜单同步机制重复,易产生客户端无 MenuType 的崩溃;仅当需要打开"非容器型纯渲染面板"时才回退到自定义 S2C 包 | REJECTED(默认) |
| 后续实例状态刷新 | 界面打开后,用 `InstanceStatusS2C` 增量推送(不重开界面) | DECIDED |

### 15.6 线程与权威性纪律(DECIDED,呼应 D8)

| 操作 | 允许执行端 | 落点 |
|---|---|---|
| 体素生成 / BFS / A*(纯计算) | 服务端工作线程 | 不碰世界对象,产物为内存 bitset |
| setBlock / 传送 / 刷怪 / 重置 | 服务端主线程 | 必须 `server.execute(...)`(世界写) |
| packet 反序列化(encode/decode) | 网络 I/O 线程 | 仅读写 `FriendlyByteBuf`,无世界访问 |
| packet 业务处理(handler 体) | 对应逻辑端主线程 | `ctx.enqueueWork(...)` 内 |
| HUD / 渲染滤镜 / GUI | 客户端主线程 | 只读 `ClientDangerState` 与同步态,无世界写 |

边界用例 PASS 判据:删除 `DangerSyncS2C` 后,客户端 danger HUD 应停在旧值且不再变化(证明客户端确实不自算 danger);删除服务端 `server.execute` 包裹后,在工作线程直接 `setBlock` 应触发 Mojang 的线程断言崩溃(证明线程纪律有实际约束力)。

---

## 十六、配置系统

### 16.1 技术选型与文件布局(DECIDED)

主配置采用 Forge 的 `ForgeConfigSpec`,注册为 SERVER 级(`ModLoadingContext.get().registerConfig(ModConfig.Type.SERVER, SPEC)`)。原因:矿山实例生成、刷怪、难度、实例上限等均为服务端权威逻辑,客户端无需也不应覆盖;SERVER config 在专用服务器上随存档走(`<world>/serverconfig/miningdim-server.toml`),单人则在存档目录内,保证"同一存档同一套平衡参数"。

| 配置层 | 文件 | 内容 | 重载支持 |
|---|---|---|---|
| SERVER(TOML) | `serverconfig/miningdim-server.toml` | 全部平衡/生成/刷怪/陷阱/实例治理参数 | 见 16.8 |
| CLIENT(TOML) | `config/miningdim-client.toml` | 仅渲染相关(danger 视觉模式、HUD 开关) | 客户端即时 |
| 数据包 JSON | `data/miningdim/worldgen/{biome,configured_feature,placed_feature}/*.json` | 矿物/装饰/陷阱的实际分布(见 7.0.3) | 随数据包重载 |

16.1 表旧稿列的 `data/miningdim/mining_ore/*.json` 从未落地:该目录在仓库中不存在,也没有对应的加载器(见 16.6)。矿物权重/密度/配额的 Java 数值表在 `ore/OreType.java`(枚举字段 baseWeight / multipliers / densityPerK / maxCount),但已无生成期消费方,只能当平衡参考读。

魔法数字治理总则:原始文档中出现的所有裸数值(trapChance、danger 各权重、baseWeight、出生扫描参数、刷怪频率与上限、实例上限、重置成本与冷却、GC 宽限、加载半径与 TTL)全部抽到本章配置项,代码中严禁再出现同义裸常量。已知例外两处,均已登记:第十八章的 `abuse.*` 闸门数值仍硬编码在 `economy/EconomyConstants.java`(见 18.7),`RetiredRegionGc` 的回收节流常量刻意不暴露(见 13.4)。

### 16.2 SERVER 配置项总表(DECIDED;平衡敏感项标 PENDING待校验,但给出建议初值)

作用域:Instance = 实例治理;Ore = 矿物总控;Difficulty = 难度系数;Trap = 陷阱;Danger = 压力;Mob = 刷怪;Spawn = 出生;Reset = 重置;Perf = 性能/生命周期;Entry = 入场。原有的 Layer(分层)作用域已随 R2 删除(16.2.2)。

覆盖范围说明:`MiningServerConfig` 里还有 `rules`(R7 方块放置白名单)、`marriage`(婚姻系统)、`movement` 三段,分别归各自的模块规格文档管辖,本章不重复登记。

16.2.1 实例治理(Instance)

| 参数名 | 类型 | 默认值 | 范围 | 说明 |
|---|---|---|---|---|
| `instance.globalCap` | int | 32 | 1..256 | 全局并发实例上限(D6),超限按 `instance.overflowPolicy` 处理。R1 下实例数恒为 3,该键实际不起作用(12.3) |
| `instance.overflowPolicy` | enum | REJECT | REJECT/QUEUE | 超上限时拒绝进入或排队。同上,R1 下不触发 |
| `instance.sharedByDefault` | boolean | false | - | 默认私有实例;true 则同难度共享(D6)。R1 下固定实例恒为 shared,该键不参与判定 |
| `instance.maxPartySize` | int | 4 | 1..16 | 单实例最大组队人数 |
| `instance.regionSizeChunks` | int | 16 | 4..64 | 单实例 region 边长(区块数),决定运行期网格 stride(D1)。worldRestart;改后只影响重启后新建/滑动产生的 region,既有 region 的几何以存档为权威 |
| `instance.bufferChunks` | int | 2 | 1..8 | region 间实心缓冲带宽度(区块),>=1(D1)。worldRestart。必须与 `MiningConstants.BUFFER_CHUNKS` 相等,否则运行期 stride(SIZE+GAP)与既有存档几何失配 |

`instance.bufferChunks` 的警告不是理论风险:该默认值从 1 改到 2 时,新旧几何不一致曾导致老的固定实例被判成"未认领"而重复新建,新 region 与玩家已挖空的旧地形在世界里直接重叠。代码为此专门保留了 `claimFixedByLegacyGeometry` 兜底,且该兜底必须用写死的历史几何(SIZE=256 / STRIDE=288)而非 config 派生几何来匹配(12.1a)。任何再次改动这两个键的提案都要先评估存量存档的认领路径。

16.2.2 分层 Y 边界(Layer)—— 已删除

R2 之后难度由所在 region 决定,不再按 `worldY` 分带,`layer.*` 这一整段配置已从 `MiningServerConfig` 与配置门面中删除(代码里只留一行注释标明删除原因)。曾经存在的 `layer.easyMinY/easyMaxY`、`mediumMinY/mediumMaxY`、`hardMinY/hardMaxY`、`enforceOrdering` 七个键现在一个都不会被写进 `serverconfig/miningdim-server.toml`,在 toml 里手写它们不会有任何效果。

难度分区的现行判据见 6.4:`RegionLayout.current().difficultyAt(blockX, blockZ)`,唯一可调的几何旋钮是 16.2.1 的 `regionSizeChunks` / `bufferChunks`。

16.2.3 矿物总控(Ore)

| 参数名 | 类型 | 默认值 | 范围 | 说明 |
|---|---|---|---|---|
| `ore.baseWeight` | int | 100 | 1..10000 | 矿物基础权重基准,`weight = baseWeight * difficultyMultiplier`(原文档公式) PENDING待校验 |
| `ore.globalDensity` | double | 1.0 | 0.0..4.0 | 全局矿物密度缩放,调试/活动用 |
| `ore.useDatapackDistribution` | boolean | true | - | 原意:true 时读 JSON 分布,false 回退内置默认表。当前无任何业务代码读取此键(只有 `ModConfig`/`IMiningConfig` 的透传 getter,以及 `PressureGameTests`/`DangerCurveGameTests` 两处显式 `throw unused()` 的测试桩),改它对生成结果零影响 |

16.2.4 难度系数(Difficulty multipliers)

| 参数名 | 类型 | 默认值 | 范围 | 说明 |
|---|---|---|---|---|
| `difficulty.easyMultiplier` | double | 1.0 | 0.1..5.0 | Easy 难度系数(矿物/危险/刷怪统一乘子基线) |
| `difficulty.mediumMultiplier` | double | 1.5 | 0.1..5.0 | Medium 系数 PENDING待校验 |
| `difficulty.hardMultiplier` | double | 2.5 | 0.1..5.0 | Hard 系数 PENDING待校验 |

难度系数语义表(DECIDED):

| 难度 | difficultyMultiplier | 陷阱基率乘子 | 刷怪频率乘子 | danger 初值偏置 |
|---|---|---|---|---|
| EASY | easyMultiplier | 0(`TrapParams.FACTOR_EASY = 0.00`:新手区无**致死**陷阱。非致死的假矿石与崩塌矿道仍会由 `placed_feature` 铺进 Easy 区,见 7.0.3) | 0(无动态刷怪) | 0 |
| MEDIUM | mediumMultiplier | 1.0 | 1.0 | 中 |
| HARD | hardMultiplier | 1.0 | 1.0 | 高 |

16.2.5 陷阱(Trap)

| 参数名 | 类型 | 默认值 | 范围 | 说明 |
|---|---|---|---|---|
| `trap.baseChance` | double | 0.04 | 0.0..1.0 | 陷阱基础概率,`trapChance = baseChance * difficulty * localRisk`(原文档) PENDING待校验 |
| `trap.localRiskMax` | double | 2.0 | 1.0..5.0 | 局部风险上限(localRisk 封顶) |
| `trap.dynamicEnabled` | boolean | true | - | 是否启用动态陷阱(身后刷苦力怕/局部坍塌/岩浆喷发) |
| `trap.minSpacingBlocks` | int | 6 | 1..32 | 同类陷阱最小间距,避免成簇 PENDING待校验 |

16.2.6 压力 danger(Danger,呼应 D7)

| 参数名 | 类型 | 默认值 | 范围 | 说明 |
|---|---|---|---|---|
| `danger.max` | double | 1.0 | 0.1..10.0 | DANGER_MAX 封顶(归一化 [0,1],量纲与第十章 10.2/10.4 一致) |
| `danger.weightZoneDifficulty` | double | 1.0 | 0.0..10.0 | `danger = wZone*zoneDifficulty + wTime*timeSpent + wOre*oreRichness` 中的 zone 权重 PENDING待校验 |
| `danger.weightTimeSpent` | double | 0.5 | 0.0..10.0 | timeSpent 权重 PENDING待校验 |
| `danger.weightOreRichness` | double | 0.3 | 0.0..10.0 | oreRichness 权重 PENDING待校验 |
| `danger.timeSoftCap` | double | 60.0 | 1.0..600.0 | timeSpent 软封顶收敛点(秒),曲线 `t' = cap*(1-e^(-t/cap))` PENDING待校验 |
| `danger.decayPerTickAway` | double | 0.2 | 0.0..10.0 | 离区/降频时每评估周期衰减量 PENDING待校验 |
| `danger.evalIntervalTicks` | int | 20 | 1..200 | danger 评估周期(D7 降频),与 DangerSyncS2C 频率一致 |

16.2.7 刷怪(Mob)

| 参数名 | 类型 | 默认值 | 范围 | 说明 |
|---|---|---|---|---|
| `mob.spawnIntervalTicks` | int | 100 | 20..1200 | 基础刷怪评估间隔,实际间隔 `= base / (1 + danger/danger.max)` PENDING待校验 |
| `mob.maxPerPlayer` | int | 8 | 0..64 | 每玩家周边活跃 mod 刷怪上限 |
| `mob.maxPerInstance` | int | 30 | 0..256 | 单实例活跃 mod 刷怪上限(防卡服) |
| `mob.behindPlayerChance` | double | 0.5 | 0.0..1.0 | 高 danger 时"后方生成"概率 |
| `mob.spawnRadius` | int | 24 | 4..64 | 刷怪生成半径(方块) |

16.2.8 出生扫描(Spawn,呼应原文档第 9 节与 D4)

| 参数名 | 类型 | 默认值 | 范围 | 说明 |
|---|---|---|---|---|
| `spawn.headroomBlocks` | int | 2 | 1..4 | 头顶需空气格数 |
| `spawn.requireSolidFloor` | boolean | true | - | 脚下须固体 |
| `spawn.lavaAvoidRadius` | int | 3 | 0..8 | 周围禁岩浆半径 |
| `spawn.avoidTrapZones` | boolean | true | - | 出生点不在陷阱区 |
| `spawn.poolSize` | int | 8 | 1..64 | 预生成 spawn pool 候选点数量 |
| `spawn.mustBeMainComponent` | boolean | true | - | 出生点须 ∈ 主连通分量(D4,强制 true,设 false 仅调试) |

16.2.9 重置(Reset,呼应原文档第 10 节)

| 参数名 | 类型 | 默认值 | 范围 | 说明 |
|---|---|---|---|---|
| `reset.cooldownSeconds` | int | 300 | 0..86400 | 同一实例两次重置最小冷却 PENDING待校验 |
| `reset.requireEmpty` | boolean | true | - | 重置前实例须无玩家(true 时有人则拒绝) |
| `reset.kickOnForceReset` | boolean | true | - | OP 强制重置时是否先踢出在场玩家 |
| `reset.confirmationWindowSeconds` | int | 15 | 5..120 | 破坏性重置二次确认窗口。注:线上命令树没有确认节点,该键当前无读取点(17.4) |
| `reset.autoResetHoursEasy` | int | 6 | 0..168 | R6:Easy 区自动重置周期(小时),0 = 关闭该难度自动重置 |
| `reset.autoResetHoursMedium` | int | 4 | 0..168 | R6:Medium 区自动重置周期 |
| `reset.autoResetHoursHard` | int | 2 | 0..168 | R6:Hard 区自动重置周期 |
| `reset.autoResetWarnSeconds` | int | 60 | 0..600 | R6:自动重置撤离前的倒计时广播秒数(全文倒计时口径统一引用本键) |

本段八项即 `MiningServerConfig` 的 `reset` 段全集,不多不少。R6 的调度行为见 13.1。

16.2.10 性能与生命周期(Perf / GC)

| 参数名 | 类型 | 默认值 | 范围 | 说明 |
|---|---|---|---|---|
| `perf.loadRadiusChunks` | int | 4 | 2..16 | 实例激活时强加载区块半径 PENDING待校验 |
| `perf.emptyInstanceTtlSeconds` | int | 300 | 0..86400 | 空实例存活 TTL(5min,与第十二/十九章 6000 tick 一致),超时进入 GC 候选 |
| `perf.gcGraceSeconds` | int | 120 | 0..3600 | GC 宽限期,`lastEmptyTick` 后再等该时长才回收(D6) |
| `perf.gcScanIntervalTicks` | int | 200 | 20..6000 | 孤儿/空实例扫描周期 |
| `perf.maxGenWorkers` | int | 2 | 1..8 | 原为离线体素生成工作线程数上限(D2/D8)。离线管线下线后无读取点,键仍在 spec 里 |

`perf.loadRadiusChunks` 是本 mod 唯一的强加载半径入口;19.1 提到的 tick 半径不是独立配置项,由它派生(见 19.1)。

16.2.11 入场(Entry)

| 参数名 | 类型 | 默认值 | 范围 | 说明 |
|---|---|---|---|---|
| `entry.labelEasy` | String | `Easy 矿洞 / 右键进入` | - | R4:Easy 入口方块上方的浮空文案(直接显示原文,不走翻译键) |
| `entry.labelMedium` | String | `Medium 矿洞 / 右键进入` | - | 同上 |
| `entry.labelHard` | String | `Hard 矿洞 / 右键进入` | - | 同上 |
| `entry.entryFeeEasy` | long | 0 | 0..Long.MAX_VALUE | Easy 入场费,单位 CREDIT。0 = 免费 PENDING待校验 |
| `entry.entryFeeMedium` | long | 0 | 0..Long.MAX_VALUE | Medium 入场费 PENDING待校验 |
| `entry.entryFeeHard` | long | 0 | 0..Long.MAX_VALUE | Hard 入场费 PENDING待校验 |

入场费是 `docs/archive/delivered/TaskSpec_Mining_EntryFee.md` 要求的硬门槛机制:默认 0 表示"待产出实测后再标定",不是"机制不存在"。判定与扣款链路见 14.2 步骤 1 与 7.5、14.4。费用是纯 sink,不转入任何玩家账户。

### 16.3 配置类骨架(DECIDED)

```java
public final class MiningServerConfig {
    public static final ForgeConfigSpec SPEC;
    public static final ForgeConfigSpec.IntValue MAX_CONCURRENT;
    public static final ForgeConfigSpec.DoubleValue TRAP_BASE_CHANCE;
    public static final ForgeConfigSpec.DoubleValue DANGER_MAX;
    public static final ForgeConfigSpec.IntValue DANGER_EVAL_INTERVAL;
    // ... 其余按 16.2 全表声明 ...

    static {
        ForgeConfigSpec.Builder b = new ForgeConfigSpec.Builder();
        b.push("instance");
        MAX_CONCURRENT = b.comment("Global concurrent instance cap (D6)")
                          .defineInRange("globalCap", 32, 1, 256);
        // regionSizeChunks 改变会使既有 region 网格失效,标记 worldRestart
        REGION_SIZE = b.comment("Region edge length in chunks; world restart required")
                       .worldRestart()
                       .defineInRange("regionSizeChunks", 16, 4, 64);
        b.pop();

        b.push("trap");
        TRAP_BASE_CHANCE = b.comment("trapChance = baseChance * difficulty * localRisk")
                            .defineInRange("baseChance", 0.04, 0.0, 1.0);
        b.pop();

        b.push("danger");
        DANGER_MAX = b.defineInRange("max", 1.0, 0.1, 10.0);
        DANGER_EVAL_INTERVAL = b.defineInRange("evalIntervalTicks", 20, 1, 200);
        b.pop();

        SPEC = b.build();
    }
}
```

注册时机(DECIDED):在 mod 构造函数内 `ModLoadingContext.get().registerConfig(ModConfig.Type.SERVER, MiningServerConfig.SPEC, "miningdim-server.toml")`。读取时机:任何业务代码读 `MAX_CONCURRENT.get()`,严禁在 spec 未加载(`ModConfigEvent.Loading` 之前)读取——生成系统、刷怪系统在世界已加载后才运行,天然安全。

### 16.4 worldRestart 标记策略(DECIDED)

| 参数 | 是否 worldRestart | 理由 |
|---|---|---|
| `instance.regionSizeChunks` / `instance.bufferChunks` | 是 | 改动使既有 region 网格与已存盘实例 bounding box 失配,运行时改会导致 region 重叠/坐标错位(16.2.1 的实测故障) |
| 其余平衡参数(trap/danger/mob/spawn/reset/perf/entry) | 否 | 仅影响后续评估,可热生效 |

`layer.*` 一行已随 16.2.2 整段删除:那组键不存在,自然也谈不上 worldRestart。`MiningServerConfig` 里真正调了 `.worldRestart()` 的只有上表第一行的两个键。

### 16.5 CLIENT 配置(DECIDED)

| 参数名 | 类型 | 默认值 | 说明 |
|---|---|---|---|
| `client.dangerVisualMode` | enum(OFF/HUD_ONLY/HUD_AND_SCREEN_DIM) | HUD_AND_SCREEN_DIM | 控制 15.4.2 中 `lightDimFactor` 是否驱动屏幕压暗滤镜 |
| `client.showInstanceHud` | boolean | true | 是否显示实例/danger HUD |
| `client.dangerHudScale` | double | 1.0 | HUD 缩放 |

CLIENT 配置仅影响本机渲染,不参与任何世界/平衡逻辑,因此置于 CLIENT 层。

### 16.6 矿物分布走专用数据包 JSON(DECIDED 设计,未实现)

状态:本节描述的 `mining_ore` 方案从未落地。`src/main/resources/data/miningdim/mining_ore/` 目录不存在,也没有任何对应的重载监听器;`ore.useDatapackDistribution` 虽然在 spec 里且默认 true,但没有任何业务代码读它(16.2.3)。

需要澄清两点,避免读者按本节去施工:

1. 矿物"走数据包"这个方向其实已经实现了,只是走的是原版 `placed_feature` 而不是本节杜撰的自定义格式(见 5.5 与 7.0.3)。难度差异由各难度 biome 的 features 列表表达,调平衡改的是那些 JSON。
2. 如果日后仍要做一层自有的矿物分布表,应参照仓库既有的 `TarotCardLoader` / `GunsmithComponentRuleLoader`(均 `extends SimpleJsonResourceReloadListener`)新建 loader。1.20.1 没有可供业务扩展的 `ReloadableResourceManager` listener 类型,本节旧稿的这个说法是错的。

以下为原设计归档。矿物逐难度分布不硬编码,改为数据包资源 `data/miningdim/mining_ore/<difficulty>.json`,支持 `/reload` 热更。示例(`hard.json`):

```json
{
  "difficulty": "hard",
  "entries": [
    { "block": "minecraft:gold_ore",        "baseWeight": 80, "minY": -64, "maxY": -16, "quota": 64 },
    { "block": "minecraft:diamond_ore",      "baseWeight": 40, "minY": -64, "maxY": -8,  "quota": 32 },
    { "block": "minecraft:ancient_debris",   "baseWeight": 2,  "minY": -64, "maxY": -48, "quota": 4  }
  ]
}
```

| 字段 | 含义 |
|---|---|
| `baseWeight` | 进入 `weight = baseWeight * difficultyMultiplier * ore.globalDensity` |
| `minY/maxY` | 该矿在本难度内的 Y 适用带 |
| `quota` | 单实例该矿块数上限(防爆矿) |

设计理由:整数硬编码分布表违反可配置原则,且服主调平衡需改源重编译;走 datapack 后,服主/整合包作者可纯资源覆盖,且 `/reload` 即时生效。`ore.useDatapackDistribution=false` 时回退内置默认表(保证缺资源不崩)。

### 16.7 配置校验(DECIDED)

在 `ModConfigEvent.Loading` / `ModConfigEvent.Reloading` 监听器内做跨字段一致性校验,违反则记 Major 级日志并按"拒绝启动生成"或"夹取到合法值"处理:

| 校验项 | 规则 | 违反处理 |
|---|---|---|
| 维度高度一致 | `MiningConstants.REGION_HEIGHT` 必须等于矿山 `ServerLevel.getHeight()`(即两份 JSON 的 `height`) | `InstanceManager` 启动期自检,不一致直接抛,不允许带病启动 |
| 缓冲带与编译期常量一致 | `instance.bufferChunks` 应等于 `MiningConstants.BUFFER_CHUNKS`(16.2.1) | 不等会使运行期 stride 与既有存档几何失配,靠 `claimFixedByLegacyGeometry` 兜底认领 |
| danger 权重非全零 | 三权重之和 > 0 | 记警告并回退 weightZoneDifficulty=1.0 |
| 实例上限与组队 | `globalCap * maxPartySize` 给出理论玩家容量,仅日志提示 | 仅日志 |
| 缓冲带 | `bufferChunks >= 1` | spec 范围已强制,额外断言 |

### 16.8 重载语义(DECIDED)

| 配置层 | 触发方式 | 重载范围 | 限制 |
|---|---|---|---|
| SERVER TOML | `/reload`(serverconfig 在 1.20.1 随数据包重载触发 `ModConfigEvent.Reloading`) | 非 worldRestart 项即时生效;`.worldRestart()` 项需重启服务器/世界 | 标 worldRestart 的 `regionSizeChunks`/`bufferChunks` 改动不在 `/reload` 范围内 |
| worldgen 数据包(biome / feature / noise_settings) | `/reload` 不改已生成区块 | 只影响重载后新生成的区块 | 矿区的"新区块"只在重置滑到新坐标后才大量出现,故改矿表的生效时机实际由重置节奏决定 |
| CLIENT TOML | 修改后即时(Forge 文件监听) | 渲染态即时 | 不影响服务端 |

PENDING待校验:`/reload` 对 serverconfig 的覆盖范围在不同 Forge 47.x 小版本上行为略有差异;落地时以 `ModConfigEvent.Reloading` 是否触发为准,生成/刷怪系统统一从 `*.get()` 实时读值,避免缓存导致重载不生效。

---

## 十七、命令与权限

状态说明(必读):本章旧稿描述的是 `com.miningdim.command` 包那套实现,而那套**从未接入主类**。`MiningDim.registerSubsystems()` 没有装配 `command.CommandSystem`,理由记在 `MiningDim.java` 类注释里:该包的 `enter` 只 `allocate` 不传送玩家,与 14.2 的防虚空链路不符,接进来还会与 `entry` 包的 `/mining` 根冲突(Brigadier 双根)。因此整个 `command` 包对运行期零效果,是已写好但明确弃用的死代码。

线上唯一生效的 `/mining` 树在 `com.miningdim.entry.MiningCommands`,由 `EntrySystem` 在 `RegisterCommandsEvent` 注册,与 14.1 完全一致:`enter <difficulty> [reseed]` / `leave` / `info [instanceId]` / `reset <instanceId> [reseed]` / `reset all`。

由此,本章各节的状态:

| 小节 | 状态 |
| --- | --- |
| 17.1 框架与权限模型 | 部分生效:Brigadier + `RegisterCommandsEvent` + OP 等级判定成立;`PermissionAPI` 对接未接线 |
| 17.2 命令树总表 | 已按线上真实树重写(本次修订) |
| 17.3 命令实现要点 | 逐行标注归属,见该节 |
| 17.4 二次确认与冷却 | 未落地,线上无确认节点、无冷却判定 |
| 17.5 权限系统对接 | 未落地,节点常量只在死代码包里预留 |

### 17.1 框架与权限模型(DECIDED)

命令树用 Brigadier 在 `RegisterCommandsEvent` 内注册(`event.getDispatcher().register(...)`)。权限基于原版 OP 等级 `source.hasPermission(int level)`,并在编译期可选对接 Forge `PermissionAPI`(从而被 LuckPerms 等接管);二者关系见 17.5。

OP 等级语义(1.20.1 原版):0 普通玩家,1 绕过出生保护,2 多数命令方块/作弊命令,3 多人管理,4 服务器所有者级(stop/op/ban)。

### 17.2 /mining 命令树总表(DECIDED,与 `entry.MiningCommands` 一致)

线上真实树(权限等级按 `source.hasPermission(int)` 判定,`OP_LEVEL = 2`):

| 子命令 | 参数 | 权限等级 | 类别 | 作用 |
|---|---|---|---|---|
| `/mining enter <difficulty>` | difficulty:word(easy/medium/hard,补全项来自 `Difficulty` 枚举) | 0 | 玩家 | 走 `EntryGateway.requestEnter` 完整入场链路(14.2) |
| `/mining enter <difficulty> reseed` | + 字面量 `reseed` | 0 | 玩家 | 同上,`reseed=true` 透传(当前仅记日志与预留扩展) |
| `/mining leave` | 无 | 0 | 玩家 | 离开当前实例,按 Capability 记录的进入前维度+坐标+gamemode 还原(D5) |
| `/mining info` | 无 | 0 | 玩家 | 显示自身所在实例信息;不在实例内时提示 |
| `/mining info <instanceId>` | instanceId:long | 0 | 玩家 | 按 id 查实例信息 |
| `/mining reset <instanceId>` | instanceId:long | 2 | 破坏性 | 以 `SAME_SEED` 重置单实例(滑动到新坐标,13.4) |
| `/mining reset <instanceId> reseed` | + 字面量 `reseed` | 2 | 破坏性 | 以 `NEW_SEED` 重置单实例 |
| `/mining reset all` | 字面量 `all` | 2 | 破坏性 | 逐实例串行重置全部实例(13.6) |

REJECTED / 未接线的子命令(仅存在于未接入主类的 `command` 包,是死代码;去服上敲会直接报未知命令):

| 子命令 | 死代码里的形态 | 状态 |
|---|---|---|
| `/mining enter <difficulty> party` | 组队私有实例进入 | REJECTED:`entry` 树用 `reseed` 占同一位置;组队私有路径在 R1 下也无意义 |
| `/mining status` | 显示自身 instanceId/难度/danger/region | REJECTED:能力由 `/mining info` 覆盖 |
| `/mining list [page]` | 分页列出所有活跃实例 | 未接线 |
| `/mining tp <instanceId>` | 管理员传送到指定实例巡查 | 未接线 |
| `/mining kick <instanceId\|player>` | 踢回进入前位置 | 未接线 |
| `/mining reset ... confirm` | 二次确认字面量节点 | 未接线,见 17.4 |
| `/mining trap place ...` | 调试用陷阱放置 | 未接线 |

参数类型(线上实现):difficulty 用 `StringArgumentType.word()` + `Difficulty.byConfigName` 校验,补全项由枚举生成(新增难度自动同步),非法值在入口层兜底提示而非抛红字堆栈;instanceId 用 `LongArgumentType.longArg()`。

权限等级订正记录:旧稿给 reset 写的是 level 4、给 kick 写的是 level 3。线上 `entry.MiningCommands` 只有一个 `OP_LEVEL = 2`,作用在整个 `reset` 分支上(含 `reset all`)。按旧稿去配权限组会把本该拦住的人放进来。

### 17.3 命令实现要点(DECIDED)

| 命令 | 归属包 | 服务端职责 | 线程/权威 |
|---|---|---|---|
| enter | entry(生效) | 校验 difficulty 合法 -> `EntryGateway.requestEnter`(门控 + 分配 + force-load 等待 + 扣费 + 传送)-> 回 `TeleportResultS2C` | 全程主线程,呼应 D8 |
| leave | entry(生效) | 读玩家 Capability 的进入前状态(维度/坐标/gamemode)-> 主线程传送还原 -> 清当前 instanceId | 主线程世界写 |
| info | entry(生效) | 只读实例注册表与玩家 Capability,组装反馈文本 | 只读 |
| reset / reset all | entry(生效) | 校验 OP level 2 -> 直接调 `IResetService.reset`;滑动重置本身不做区块重写 | 主线程,13.4 状态机分帧推进 |
| status / list / tp / kick / trap | command(死代码) | 未接线,运行期不可达 | — |

防滥用:enter 命令不接受客户端坐标,落点由 `SpawnSystem` 决定;非破坏性命令对普通玩家开放,破坏性命令按 OP level 2 门槛。

据实报备:`entry.MiningCommands` 的 `reset()` / `resetAll()` 目前只 `LOGGER.info` 记了实例数,没有把执行者身份写进日志,17.4 承诺的审计日志尚未落地。

### 17.4 破坏性命令的确认与冷却(设计,未落地)

状态:本节的"二次确认 + 冷却"双闸在线上命令树里**一条都没有生效**。`entry.MiningCommands` 的 reset 分支是 OP level 2 + 立即执行:没有 `confirm` 字面量节点,不读 `reset.confirmationWindowSeconds`,不做冷却判定,也不接 `PermissionAPI`。`command` 包里那套 `ResetConfirmations`(含 `LEVEL_RESET = 4` 与 `confirm` 节点)随整个包一起未接入。

| 机制 | 设计规则 | 线上实际 |
|---|---|---|
| 权限门槛 | OP level 4(或 PermissionAPI 节点 `miningdim.command.reset`,见 17.5) | OP level 2,硬编码常量,无 PermissionAPI |
| 二次确认 | 首次 `/mining reset <id>` 只登记待确认意图,需在 `reset.confirmationWindowSeconds`(默认 15s)内重发 `... confirm` | 无确认节点,首次即执行 |
| 冷却 | 同一实例两次成功重置间隔须 >= `reset.cooldownSeconds`(默认 300s) | 命令层不判冷却 |
| 在场保护 | `reset.requireEmpty` / `reset.kickOnForceReset` | 配置键存在;撤离由 13.3/13.4 的 UNLOAD 阶段承担 |
| 审计日志 | 每次 reset 记录执行者、instanceId、tick | 只记了实例数,未记执行者(17.3) |

如果"二次确认 + 冷却 + 更高 OP 等级"仍是业务期望的最终形态,应作为一个明确的工程任务:把 `command` 包里那套确认/冷却逻辑迁进 `entry.MiningCommands` 并接上配置读取,而不是继续留着两套互不相通的实现让文档与代码各说各话。在此之前,本节按"未落地设计"读。

### 17.5 权限系统对接(设计,未接线)

状态:线上只有原版 OP 模式。`PermissionAPI` 对接从未接线——`command/MiningPermissions.java` 里预留了节点名常量与 `check(source, node, fallbackOpLevel)` 的形态,但没有任何 `PermissionNode` 被注册,判定恒走 OP 回退;而且这整个文件本身就在未接入主类的死代码包里。

| 模式 | 实现 | 状态 |
|---|---|---|
| 原版 OP(默认) | `source.hasPermission(level)`,等级见 17.2 表 | 生效,且是唯一生效的模式 |
| Forge PermissionAPI(可选) | 注册 `PermissionNode<Boolean>`,命令判定改用 `PermissionAPI.getPermission(serverPlayer, NODE)`;LuckPerms 等通过其 Forge 桥接接管 | 未接线 |

下表是设计期的节点规划,不是现状;节点名与默认回退等级都还没有对应的运行期行为,配权限插件时不要按它去授权。

| 节点 | 设计的默认等级回退 | 对应命令 |
|---|---|---|
| `miningdim.command.enter` | 0 | enter/leave/status |
| `miningdim.command.admin.list` | 2 | list/tp |
| `miningdim.command.admin.kick` | 3 | kick |
| `miningdim.command.reset` | 4 | reset(含 all) |

### 17.6 反馈本地化(DECIDED)

所有命令反馈用 `Component.translatable("commands.miningdim.<key>", args)` 而非硬编码英文,失败用 `createCommandException` / `source.sendFailure`,成功用 `source.sendSuccess(() -> component, broadcastToOps)`;破坏性命令成功时 `broadcastToOps=true`,普通查询 `false`。

---

## 十八、反滥用与经济闸门

本章定义防止玩家滥用实例重置、矿物刷取与 danger 规避的全部闸门。所有闸门作用于"实例(instance)"或"经济产出"层面,而非单纯限制单个玩家行为;闸门数据持久化于 InstanceManager(SavedData,见第五章 D5)与玩家 Capability(见第五章 D5)。本章所有平衡数值标注 PENDING待校验,但均给出建议初值,以便压测调参。

### 18.1 设计目标与威胁模型

| 编号 | 滥用向量 | 危害 | 闸门归属 |
|------|----------|------|----------|
| A1 | 高频重置刷取地表矿物/结构 | 破坏经济、磁盘抖动、TPS 抖动 | 重置冷却 + 成本 + 每日上限(18.2) |
| A2 | 反复进出新实例只挖高价矿物层 | 钻石/下界残骸通胀 | 每玩家每日产出软上限 + 配额(18.3) |
| A3 | 挂机(AFK)放置刷怪点刷掉落 | 刷怪经济失衡、服务器空转 | AFK 检测暂停(18.4) |
| A4 | 进-退-再进重置 danger 规避高压 | 抹掉风险收益对价 | 重入冷却 + danger 不随离区清零(18.5,交叉引用 D7) |
| A5 | Hard 区零成本试错(死了再来) | 高难度风险无实际成本 | 死亡惩罚(18.6) |

设计原则: 闸门必须可配置(ForgeConfigSpec,见配置章),默认值偏保守;闸门拒绝行为必须有明确玩家可见文案(见第二十章);所有闸门均为服务端权威判定,客户端仅展示。

### 18.2 实例重置闸门

重置(reset)定义见第十章: 仅删除/重生成单个 region 的区块(D1),不销毁维度。重置闸门三件套:

| 闸门 | 配置键 | 建议初值(PENDING) | 作用域 | 判定时机 |
|------|--------|---------------------|--------|----------|
| 重置冷却 | `reset.cooldownTicks` | 6000 tick(5 min) | 单实例 instanceId | 上次 reset 完成 tick + cooldown > now 则拒绝 |
| 重置成本 | `reset.costItem` / `reset.costAmount` | minecraft:diamond x 2 | 发起重置的玩家 | 扣费失败则拒绝,先校验后扣 |
| 每日重置上限 | `reset.dailyLimitPerInstance` | 8 次/实例/日 | 单实例 instanceId | 跨越游戏日(daytime 回绕)或真实日重置计数 |

判定顺序(全部 PASS 才执行重置): 冷却 -> 每日上限 -> 成本校验 -> 扣费 -> 异步重生成(见第十九章预生成)。计数字段 `lastResetTick`、`resetCountToday`、`resetDayStamp` 存于 `InstanceState`(D6),随 SavedData 持久化。

"每日"口径: 默认按服务端真实日(`System.currentTimeMillis()` 取 UTC 日序);可配 `reset.dayMode=GAME|REAL`。GAME 模式按维度 dayTime / 24000L 取整变化触发翻日。翻日时统一在 InstanceManager 周期 tick(每 200 tick 巡检)里批量清零,不在玩家请求路径里做时钟比较以外的写。

### 18.3 矿物产出闸门与配额

交叉引用第八章(矿物配额)。本节定义"每玩家每日高价矿物软上限"与"实例级矿物总量配额"两层。

实例级硬配额(防 A1+A2): SUPERSEDED。该方案要求在离线预生成阶段就数清整个 region 的矿物体素总数并写入 `InstanceState.oreBudget`,而离线阶段已不存在(7.0);`placed_feature` 是逐区块放置的,区块未生成时无从统计总量。现在的经济根防线只剩下面的每玩家每日产出软上限这一层,实例级总量不再封顶——这是换用数据包生成后暴露出的实质缺口,需要在经济总表层面重新评估。

每玩家每日产出软上限(防 A2):

| 矿物 | 配置键 | 软上限建议初值(PENDING) | 超限行为 |
|------|--------|--------------------------|----------|
| 钻石 | `economy.daily.diamond` | 64 | 超限后掉落正常,但若接外部收购系统则收购价递减(见下) |
| 下界残骸 | `economy.daily.netherite_scrap` | 8 | 同上 |
| 金 | `economy.daily.gold` | 256 | 同上 |

软上限"软"在: 不阻止挖掘(避免破坏挖矿手感),而是驱动收购价递减曲线。递减价 `price(n) = basePrice * max(floorRatio, decayBase^(max(0, n - softCap)))`,建议 `decayBase=0.97`、`floorRatio=0.25`。若服务器未接经济插件,本闸门退化为统计计数(仅用于 18.4 AFK 判定与排行),不改变掉落。计数存玩家 Capability 字段 `dailyOreCount`(Map),翻日清零。

DECIDED: 配额是硬上限(总量恒定),软上限是价格调节;二者正交,同时生效。

### 18.4 AFK / 挂机检测

目的: 防 A3(放置式刷怪点挂机刷掉落)。检测信号与处置:

| 信号 | 阈值(PENDING) | 说明 |
|------|----------------|------|
| 无挖掘动作 | `afk.noBreakTicks` = 2400 tick(2 min) | 最近一次 `BlockEvent.BreakEvent` 在矿山维度的 tick 距今超阈值 |
| 无显著位移 | `afk.noMoveBlocks` = 4 格 | 滑动窗口内位移平方和低于阈值 |

二者同时满足判定为 AFK。AFK 玩家进入"经济冻结"态(玩家 Capability `afkFrozen=true`):

1. 暂停其周围 danger 累积(timeSpent 停增,见 D7);
2. 暂停以其为锚点的后方刷怪/动态压力刷怪(见第十章),已存在的怪正常存活;
3. AFK 期间该玩家造成的刷怪掉落不计入 `dailyOreCount` 经济统计;
4. 恢复条件: 一次有效 `BreakEvent` 或位移超过 `afk.noMoveBlocks`,立即解冻。

实现: AFK 评估挂在 danger 降频 tick(每 20 tick,D7)同批执行,避免额外定时器。判定纯读字段,无世界写,可在主线程 danger 评估回调内完成。

### 18.5 danger 重入冷却(防"进-退-再进")

交叉引用 D7(danger 模型)。攻击场景: 玩家在高 danger 时退出实例,期望再进时 danger 归零。防御策略分两层:

| 层 | 机制 | 配置键 / 字段 |
|----|------|---------------|
| L1 danger 不随离区清零 | 离区只触发衰减(D7 软封顶收敛 + 离区降频衰减),不归零;danger 值与 `lastDangerTick` 存玩家 Capability,跨进出保留 | `danger.decayPerTickOffInstance` 建议 0.5/tick |
| L2 重入冷却 | 玩家离开某 instanceId 后,`reentry.cooldownTicks` 内再进同实例,进入时 danger 不从衰减后值起算,而取 `max(衰减后值, 上次离开值 * reentry.retainRatio)` | `reentry.cooldownTicks`=1200、`reentry.retainRatio`=0.8 |

字段 `lastInstanceId`、`lastLeaveTick`、`lastLeaveDanger` 存玩家 Capability。L2 仅对"同实例快速重入"生效,换实例不继承(不同 region 风险独立)。这样玩家无法靠秒退秒进清空压力,但正常长时间离开后再来仍享受衰减。

### 18.6 死亡惩罚规则

目的: 防 A5,使 Hard 区"死亡"具实际成本。死亡处理在 `LivingDeathEvent` / `PlayerEvent.Clone(wasDeath=true)` 中执行(D5 Clone 复制玩家 Capability)。

| 规则项 | 配置键 | 建议初值(PENDING) | 说明 |
|--------|--------|---------------------|------|
| 掉落物处理 | `death.dropMode` | KEEP_IN_PLACE | KEEP_IN_PLACE: 掉落物留在死亡点 region;DESPAWN_FAST: 缩短 despawn;VOID: 直接清除(硬核向) |
| 是否锁实例 | `death.lockInstanceTicks` | 0(默认不锁) | >0 时该玩家死亡后此实例对其加再入冷却,模拟"被清出矿区" |
| 复活点 | `death.respawnMode` | OVERWORLD_ORIGIN | 复活回进入前记录的维度+坐标+gamemode(D5 玩家级数据),而非实例内 |
| 再入冷却 | `death.reentryCooldownTicks` | 1200 tick(1 min) | 死亡后再次进入任意实例的全局冷却 |
| danger 处理 | `death.dangerOnDeath` | RESET_TO_ZERO | 死亡是 danger 的合法出口: 死亡清零 danger(与 18.5 离区不清零对照,死亡有掉落代价) |

DECIDED: 复活点恒为"进入前坐标"(D5 已持久化 priorDimension/priorPos/priorGameMode),绝不在实例内复活,避免死亡-即时重试的零成本循环。danger 死亡清零是设计上的风险出口: 玩家可用"死亡"换取压力清零,但要付掉落与再入冷却代价,形成风险收益对价。

### 18.7 闸门配置汇总(当前硬编码,`abuse.*` 配置段待接线)

当前实现:下列数值全部硬编码在 `economy/EconomyConstants.java`,`MiningServerConfig` 里**没有** `abuse` 配置段。服主按旧稿去 `serverconfig/miningdim-server.toml` 里翻 `abuse.*` 会一无所获。代码自己也注明了这一点:"在 ConfigSystem 把 abuse.* 接入 ForgeConfigSpec 并扩展配置门面之前,本闸门子系统自带这些初值作为唯一来源……一旦暴露 getter,这些常量应改为读配置(留待接线)"。

这与 2.2 硬约束 C6"所有平衡数值可配置"直接冲突,已作为已知例外登记(见 16.1 总则与 22.3 风险表 R11)。

| 规划的配置路径 | 类型 | 当前值 | 代码常量名(`EconomyConstants`) |
|----------|------|------|------|
| `abuse.reset.cooldownTicks` | int [0,) | 6000 | `RESET_COOLDOWN_TICKS` |
| `abuse.reset.costItem` | String(ResourceLocation) | minecraft:diamond | `RESET_COST_ITEM` |
| `abuse.reset.costAmount` | int [0,) | 2 | `RESET_COST_AMOUNT` |
| `abuse.reset.dailyLimitPerInstance` | int [0,) | 8 | `RESET_DAILY_LIMIT_PER_INSTANCE` |
| `abuse.reset.dayMode` | enum GAME/REAL | REAL | `RESET_DAY_MODE` |
| `abuse.economy.daily.diamond` | int [0,) | 64 | `DAILY_SOFTCAP_DIAMOND` |
| `abuse.economy.daily.netherite_scrap` | int [0,) | 8 | `DAILY_SOFTCAP_NETHERITE_SCRAP` |
| `abuse.economy.decayBase` | double (0,1] | 0.97 | `ECONOMY_DECAY_BASE` |
| `abuse.afk.noBreakTicks` | int [0,) | 2400 | `AFK_NO_BREAK_TICKS` |
| `abuse.afk.noMoveBlocks` | double [0,) | 4.0 | `AFK_NO_MOVE_BLOCKS` |
| `abuse.reentry.cooldownTicks` | int [0,) | 1200 | `REENTRY_COOLDOWN_TICKS` |
| `abuse.reentry.retainRatio` | double [0,1] | 0.8 | `REENTRY_RETAIN_RATIO` |
| `abuse.death.reentryCooldownTicks` | int [0,) | 1200 | `DEATH_REENTRY_COOLDOWN_TICKS` |
| `abuse.death.dangerOnDeath` | enum | RESET_TO_ZERO | `DEATH_DANGER_MODE` |

一处例外已经接线:重置冷却。`AbuseGuard` 优先读配置门面的 `reset.cooldownSeconds`(16.2.9,默认 300s = 6000 tick)并换算成 tick,`EconomyConstants.RESET_COOLDOWN_TICKS` 只作配置缺省时的对齐基准与文档锚点。判定全部服务端权威。

---

## 十九、性能与容量指标

本章把"性能稳定""可重置""多实例"等定性目标量化为可压测验收的硬指标,并定义区块加载生命周期、tick 预算与异步生成池。所有指标标 PENDING待校验,需在目标硬件(基准: 4 vCore / 8GB 堆 / SSD)压测后定稿。

### 19.1 ChunkTicket / forceload 生命周期

1.20.1 强制加载有两条路径: 数据驱动的 `/forceload`(持久,经 `ForcedChunksSavedData`)与代码侧 `ServerLevel.setChunkForced` / Forge `ForgeChunkManager.forceChunk(level, modid, owner, x, z, add, ticking)`。本系统用 Forge `ForgeChunkManager`(支持 owner 与 ticking 标志,且参与 Forge 的卸载校验),不用裸 `setChunkForced`(无 owner 维度、不区分 ticking)。

| 阶段 | 触发 | 动作 | ticking 标志 |
|------|------|------|--------------|
| 激活(玩家在区) | 玩家进入 instance 或在其中移动 | 以玩家所在区块为心,按 `perf.loadRadiusChunks`(默认 4,范围 2..16)申请滑动 ticket 集合 | 内圈 `tickRadius` 区块 ticking=true,其余 ticking=false(仅加载不 tick) |
| 滑动更新 | 玩家移动跨区块边界 | 增量 add 新进入区块 / remove 离开区块,维持以玩家为心的窗口 | 同上,随窗口滑动 |
| 空置 TTL | instance.playerSet 变空 | 记 `lastEmptyTick`;启动 TTL 计时 | 全部降为 ticking=false |
| 卸载释放 | `now - lastEmptyTick > perf.emptyInstanceTtlSeconds`(默认 300 秒 = 5min) | `forceChunk(..., add=false)` 释放该 region 全部 ticket,区块走原版卸载 | 释放 |

键名与默认值以 16.2.10 的 `perf` 段为唯一真源。旧稿写的 `load.activeRadius` / `load.tickRadius` / `load.emptyTtlTicks` 三个键在 spec 里都不存在(根本没有 `load` 配置段),且与 16.2.10 自相矛盾,已订正。单位统一用秒,不再写 6000 tick。

`tickRadius` 不是独立配置项:`ChunkTicketManager.refreshWindow` 里由加载半径派生 —— `tickRadius = Math.max(1, activeRadius / 2)`,即向下取整的一半、至少为 1。按当前默认 `loadRadiusChunks = 4` 推出 `tickRadius = 2`。旧稿"activeRadius 建议 8 / tickRadius 建议 4"是两级独立可调的写法,与实现不符,也与实际默认值差一倍;若确实需要独立可调的 tick 半径,那是一个尚未实现的增强项,须另立 TODO,不能当现状写。

区分"需 tick 逻辑区"(ticking=true,跑刷怪/坍塌/岩浆/方块更新)与"仅加载区"(ticking=false,玩家可见但无主动逻辑),避免为整个 region force-tick。滑动 ticket 集合以玩家 chunkPos 为心,差量维护;多玩家共享实例时取各玩家窗口并集。

DECIDED: 后方刷怪/坍塌的作用半径必须 <= 派生出的 `tickRadius`,确保作用点落在 ticking 区块内;若机制需要在 ticking 窗口外(如远端预坍塌),必须申请短时窗 ticket(`forceChunk` ticking=true)执行后立即释放(用后即释),严禁长期 force-tick 整个 region。

### 19.2 tick 预算

单实例每 tick 主线程开销必须有硬上限。预算分配:

| 子系统 | 频率 | 硬上限 | 超限策略 |
|--------|------|--------|----------|
| danger 评估 | 每 20 tick(D7) | 每玩家 O(1) 字段计算 | 降频已是上限 |
| 动态刷怪 | 事件/周期 | 单实例存活怪 <= `mob.maxPerInstance`(默认 30,见 16.2.7) | 达上限停刷,不排队 |
| timeSpent 累积 | 每 20 tick | 软封顶饱和(D7) | 到饱和值停增 |
| 坍塌 | 事件触发 | 每 tick 替换方块数 <= `collapse.maxBlocksPerTick`(建议 64) | 优先直接 `setBlock` 替换(不生成 FallingBlockEntity);超量分帧到后续 tick |
| 岩浆 | 事件触发 | 流体更新限制在半径 `lava.maxSpreadRadius`(建议 6)内 | 超出范围不调度流体 tick |

坍塌实现 DECIDED: 默认用 `level.setBlock` 直接把悬空方块替换为对应坠落态/空气,而非批量 spawn `FallingBlockEntity`(实体数爆炸 + 物理 tick 开销)。仅在视觉关键点(玩家正前方小范围)按 `collapse.visualFallingBudget`(建议 8)生成少量真实 FallingBlock 做表现。岩浆喷发 DECIDED: 用有限步数的预定义流体填充而非依赖原版流体无限扩散,半径硬限。

后方刷怪 DECIDED: 生成点必须在玩家 entity-ticking 范围(由 `perf.loadRadiusChunks` 派生的 tickRadius 内,见 19.1)且通过 spawn 安全校验(见第十一章);若候选点在 ticking 窗口外则放弃该次刷怪,不申请额外 ticket(刷怪非关键,从简)。

### 19.3 多实例内存 / 磁盘

| 资源 | 量化口径 | 上限(PENDING) | 控制手段 |
|------|----------|----------------|----------|
| 内存(占用体素 bitset) | SUPERSEDED:不再分配 | — | 离线管线下线(7.0)。按现行 192 高的历史口径:256x192x256 = 12.58M voxel ≈ 1.5 MiB bitset(旧稿的 25.2M / 3.0 MiB 是按早期 384 高算的) |
| 内存(InstanceState) | 每实例元数据 | 常数级(<1KB) | R1 下只有三个实例,总量可忽略 |
| 磁盘(region 区块) | region 占用的 .mca 区域 | 单实例 <= 64MB(建议值,无配置键) | 滑动重置会持续产生退役 region,磁盘真实占用取决于 `RetiredRegionGc` 的回收进度(13.4),不是单实例上限能框住的 |
| 全服并发实例 | 活跃 InstanceState 数 | R1 下恒为 3 | `instance.globalCap` 对固定实例不起作用(12.3) |

DECIDED: InstanceState 只保留 `seed`+`regionBox`+`genState` 等元数据,不常驻任何体素结构。销毁实例(仅对动态实例,固定实例不销毁)时: 1) 踢出残留玩家(见第二十章);2) 释放 force ticket;3) 从 SavedData 移除 InstanceState;4) 退役区块交 `RetiredRegionGc` 异步回收。

磁盘容量的真正风险点已换成坐标空间而非单实例体积:滑动游标每次重置推进 1280 格,`MAX_REGION_WORLD_X = 25,000,000` 在默认重置节奏下约 888 天耗尽(13.4 约束 b)。这是需要长期监控的指标,旧表里没有。

### 19.4 [SUPERSEDED] 异步生成与预生成实例池

状态:SUPERSEDED(F021/F032)。本节整节建立在离线预生成之上,现行版本没有生成任务、没有工作线程池、也没有预生成池——三个固定实例常驻且恒为 `READY`,"进入等待"这个问题本身已经消失(玩家进入时的唯一等待是 14.3 的区块 force-load 就绪)。表中 `pool.warmSize` / `pool.templateCache` / `pool.maxConcurrentGen` / `gen.setBlockPerTick` 四个配置键在 `MiningServerConfig` 中均不存在。

以下为原方案归档。DECIDED(D2): 离线预生成。体素生成(Skeleton/NoiseCarving/ConnectivityFix)与 BFS 全在工作线程(D8),仅最终 `setBlock` 回主线程(`server.execute`)分帧提交。

预生成池(防进入等待):

| 机制 | 配置键 | 建议初值(PENDING) | 说明 |
|------|--------|---------------------|------|
| 空闲备货池 | `pool.warmSize` | 每难度 2 个 | 后台预先算好 bitset 的 InstanceState 待命,玩家进入直接出池零等待 |
| 模板缓存 | `pool.templateCache` | LRU 8 | 相同 `seed+difficulty` 的体素结果缓存,命中直接复用 bitset(重置同布局/调试有用) |
| 进入排队限流 | `pool.maxConcurrentGen` | 2 | 同时进行的离线生成任务上限,超出排队,防工作线程过载 |
| 分帧 setBlock | `gen.setBlockPerTick` | 每 tick <= 4096 方块 | 提交阶段每 tick 写方块上限,避免单 tick 卡顿 |

提交流水线: 工作线程算完整个 region bitset -> 切成 per-chunk 写任务队列 -> 主线程每 tick 从队列取 <= `gen.setBlockPerTick` 个方块写入并标记 chunk dirty -> 全部写完标记 `genState=READY` -> 出池可进入。生成全程不阻塞主线程,主线程仅承担分帧 setBlock。

线程安全 DECIDED: bitset 计算阶段无共享可变状态(每任务独立 RandomSource,D3);提交阶段所有世界写经 `server.execute` 串行化到主线程,工作线程绝不直接触碰 `Level`。InstanceManager 的 InstanceState 增删用并发安全容器(`ConcurrentHashMap`)+ 仅主线程改 `genState`。

### 19.5 量化验收指标(压测门槛)

下表为压测验收门槛,任一 FAIL 阻断发布。基准硬件: 4 vCore / 8GB 堆 / SSD。

| 指标 | 目标(PENDING) | 测量方法 | 验收 |
|------|----------------|----------|------|
| 三区同时在线人数 | 三个固定实例各 `shareCap` 人持续活动 30 min | 压测脚本 | 主世界 TPS >= 19 PASS |
| 单实例内存(常驻) | <= 1MB | 堆 dump 统计 InstanceState 保留集 | PASS/FAIL |
| 单实例峰值内存(生成中) | SUPERSEDED:无独立生成阶段 | — | 旧门槛 12MB 系按早期 384 高的 3.0 MiB bitset 推出,按现行 192 高应为 1.5 MiB;该指标本身已随离线管线作废 |
| 单实例磁盘 | <= 64MB(单代 region) | region .mca 体积统计 | PASS/FAIL |
| 退役 region 回收速度 | 一块 region(256 区块)<= 100 秒清完 | `RetiredRegionGc` 节流参数推算:每 100 tick 清 16 个,约 16 轮 80 秒 | PASS/FAIL |
| 重置期最低主世界 TPS | >= 18 | 3 实例并发重置时采样 | PASS/FAIL |
| 全服实体上限 | 活跃怪总数 <= 实例数 x `mob.maxPerInstance` | 实体计数 | PASS/FAIL |
| 入场等待 | 区块 force-load 就绪 <= `EntryGateway.CHUNK_WAIT_TIMEOUT_TICKS`(200 tick = 10s,硬编码常量,无配置键) | 进入请求计时 | PASS/FAIL |

---

## 二十、错误处理与边界情况

本章逐场景定义处理策略、日志级别与玩家可见提示文案。遵循硬约束 C9(2.2 节): 业务层异常自然冒泡,仅在最外层(命令 Controller / 网络 handler / 进入流程 Gateway)统一兜底;不在业务函数内 try/catch 生吞。本章描述的"兜底"均指最外层兜底或确定性降级路径(非吞异常)。

### 20.1 错误处理总则

| 原则 | 说明 |
|------|------|
| 异常冒泡 | 体素生成、BFS、配额扣减等业务函数遇非法状态直接抛,不本地 catch |
| 最外层兜底 | 进入流程 Gateway、命令 handler、网络 packet handler 各设一个 try/catch,记 ERROR 日志并向玩家回友好文案,绝不让异常崩服 |
| 确定性降级 | 算法层"失败"(如连通性未达标)不是异常,而是返回降级结果(见 20.2),走预定义 fallback |
| 玩家文案 | 所有拒绝/失败经网络下发可本地化文案(translation key `miningdim.msg.*`),不暴露堆栈 |
| 日志规范 | 保留诊断日志,不随缺陷修复顺手删除;级别见各场景表 |

### 20.2 逐场景处理

| 场景 | 触发条件 | 处理策略 | 日志级别 | 玩家文案(translation key) |
|------|----------|----------|----------|----------------------------|
| 连通性修复后仍不连通 | SUPERSEDED:`ConnectivityFix` 与 `gen.minConnectedRatio` / `gen.maxRetries` / `gen.minVolume` 均不存在(7.0.5) | 该场景已不可能触发。现行版本不对连通率作任何断言,孤立空腔被接受为原版 carver 的正常产物 | — | — |
| 扫遍找不到安全 spawn | spawn 候选扫描(第十一章)在已 force-load 的真实区块内无满足安全判定的点 | 强制 fallback: 在候选中心处铲平建 3x3 安全平台(脚下 3x3 固体、头顶 2 格空气、周围清岩浆),该平台坐标登记为 spawn | WARN | 无(对玩家透明,正常出生) |
| 实例池满 / 并发上限 | R1 下不可能触发:实例数恒为 3(12.3) | 保留作动态分配路径的设计:拒绝并提示,或按 `instance.overflowPolicy=QUEUE` 入队 | INFO(拒绝) | `miningdim.msg.instances_full` |
| 区块异步生成未完成就传送 | 玩家进入请求时目标区块未 force-load 完成(`genState` 在 R1 下恒为 READY) | 传送前必须等待: 进入流程置玩家于加载中状态,轮询目标 chunk 为 FULL 后再 `teleportTo`;超 `EntryGateway.CHUNK_WAIT_TIMEOUT_TICKS`(200 tick,硬编码)未就绪则取消进入;入场费在扣款前失败故无需退款,扣款后失败走 `notifyInsufficientFundsAndRollback` 回滚 | INFO(正常等待)/ ERROR(超时) | `miningdim.msg.preparing` / `miningdim.msg.enter_timeout` |
| 重置时实例内仍有玩家 | reset 请求时 `playerSet` 非空 | 重置前先把所有在区玩家传回进入前坐标(D5 priorDimension/priorPos),广播提示,确认 `playerSet` 空后才执行区块删除+重生成;传送失败的玩家(下线等)在其上线 Clone/Login 时纠正 | WARN | `miningdim.msg.reset_evict`("该矿区正在重置,你已被送回") |
| 传送目标维度未加载 | miningdim:mining ServerLevel 为 null(异常,维度应启动注册) | 这是配置/数据包错误,属不可恢复: 记 ERROR,拒绝进入,提示管理员检查数据包 | ERROR | `miningdim.msg.dimension_missing`("矿山维度未正确加载,请联系管理员") |
| 玩家进入中断线 | 进入流程进行中玩家断线 | 回滚: 释放为其申请的临时 ticket,扣费若已发生则在重连时退款或记账;不留孤儿 force-load | WARN | 无(下次登录处理) |
| 配额/扣费竞态 | 多玩家同实例并发挖矿物 / 并发扣重置费 | oreBudget 用原子递减(`AtomicInteger` 或主线程串行);扣费在主线程串行校验后扣,杜绝双花 | DEBUG | 无 |

### 20.3 启动期孤儿数据清理

交叉引用 D5(启动重建 InstanceManager)。服务器启动 `ServerStartedEvent` 时:

| 检查 | 处理 |
|------|------|
| SavedData 中 InstanceState 引用的 region 区块已不存在 | 标记该实例 genState=NEEDS_REGEN,首次进入时重生成 |
| InstanceState 存在但所有引用玩家 Capability 均无该 instanceId(孤儿实例) | refCount 归零且超 `instance.orphanTtlTicks` 则删除并释放磁盘 |
| 玩家 Capability 中 currentInstanceId 指向不存在的实例 | 清空该字段,玩家视为不在任何实例 |
| 多 head / 数据损坏(SavedData 反序列化失败) | ERROR 日志,该条跳过(不崩服),记录待人工核查 |

DECIDED: 启动清理只读 SavedData 与玩家数据做内存重建与孤儿标记,不在启动期跑重型区块操作(重生成延迟到首次进入,避免拖慢启动)。

---

## 二十一、测试策略

测试分三层: 纯算法断言、Forge GameTest 集成测试(维度/生成/传送等需 server 环境)、性能基准测试。现状: 仓库未引入 JUnit(`src/test` 为空)也没有 CI,已落地的自动化测试全部是 Forge GameTest,纯算法断言同样写成 GameTest 在服务端进程里跑,共用脚手架在 `com.miningdim.testutil`。用例纪律: 断言具体业务结果,严禁 `is not None` 类弱校验(判据: 删掉被测核心逻辑测试必须 FAIL);用例只取线上可达的输入,边界取业务契约(config 上下界、等级临界、栈上限、余额为零等),不为上游已保证不会出现的状态写用例。

### 21.1 纯算法断言

本表是设计期清单,原计划以 JUnit 5 纯 JVM 承载;仓库未引入 JUnit,已落地的断言以 GameTest 形式存在(如 danger 时间因子下限、spawn 回退平台),其余行的覆盖情况未逐行核对。标 SUPERSEDED 的行,其被测实现已下线。

| 测试 | 断言(具体业务结果) | 边界/取样 |
|------|---------------------|-------------|
| 连通性 100% | SUPERSEDED:`ConnectivityFix` 已下线(7.0.5),现行版本不对连通率作任何断言 | — |
| 确定性可复现 | SUPERSEDED:离线体素 bitset 随离线管线下线(7.0),确定性由原版 `minecraft:noise` 自身保证(C3) | — |
| 矿物权重分布 | 大样本(10^5 次抽样)统计各矿物占比,断言落在期望区间 `expected +- 3*stderr`(卡方检验 p>0.05) | 三难度各测;含权重为 0 的矿物必不出现 |
| danger 公式边界 | `danger=f(zoneDifficulty,timeSpent,oreRichness)`: 断言封顶 `f(...)==DANGER_MAX`(超大入参)、衰减单调、离区衰减后值精确等于公式值(D7) | timeSpent=0 / 饱和 / 超饱和;负输入应抛或钳制 |
| trapChance 公式 | `trapChance=difficulty*localRisk`: 断言 difficulty=0 -> 0、上限钳制到 [0,1]、单调 | 边界 0 / 1 / 越界输入 |
| 安全 spawn 判定 | 构造头顶2格空气+脚下固体+无岩浆点判 PASS;缺任一条件判 FAIL(逐条件翻转用例) | 每个安全条件单独失效一次,断言对应 FAIL |
| 配额递减 | oreBudget 并发递减 N 次后断言精确等于 `initial-N` 且不为负 | 多线程并发递减压测原子性 |
| danger 重入冷却(18.5) | 模拟离区->冷却内重入,断言 danger == `max(衰减值, 离开值*retainRatio)`;冷却外重入断言 == 衰减值 | 冷却边界 tick±1 |

SUPERSEDED(7.0.5): 原 DECIDED「矿洞 100% 连通做成生成后置自动断言,CI 必跑」随 `ConnectivityFix` 一并下线,现行版本不对连通率作断言。

### 21.2 Forge GameTest 集成测试

需 server 环境的行为用 Forge GameTest(`@GameTest` + `@GameTestHolder(modid)`,注册到 `RegisterGameTestsEvent`)。

| 测试 | 步骤与断言 |
|------|-----------|
| 维度注册存在 | 断言 `server.getLevel(MINING_LEVEL_KEY) != null` 且 dimension_type 参数符合预期 |
| 进入流程端到端 | 玩家执行进入命令 -> 等 `genState==READY` -> 断言玩家维度==miningdim:mining 且落点通过安全判定且在主连通分量内 |
| 传送前 force-load 完成 | 断言传送瞬间目标 chunk 已 `ChunkHolder` FULL 状态(不在未生成块传送) |
| 重置踢人 | 玩家在实例内触发 reset -> 断言玩家被传回 priorPos 维度且实例区块已重生成(抽样方块与新 seed 一致) |
| 实例满拒绝 | 占满 globalCap -> 再进入断言收到 `instances_full` 文案且未创建新实例 |
| 死亡复活点(18.6) | 实例内致死 -> 断言复活在 priorDimension/priorPos 且 danger 清零 |
| Capability 跨维度/死亡复制 | 进入记录 prior 数据 -> 死亡 -> 断言 Clone 后 Capability 字段完整(D5 PlayerEvent.Clone) |
| ChunkTicket TTL 释放 | 玩家全部离开 -> 推进 `emptyTtlTicks` -> 断言 region 区块已卸载(force ticket 释放) |

### 21.3 性能基准测试

| 基准 | 通过门槛(PENDING) | 方法 |
|------|---------------------|------|
| 单实例离线生成耗时 | SUPERSEDED:无离线生成阶段,该基准不再适用(7.0) | — |
| 重置期主世界 TPS | >= 18(见 19.5) | 3 个固定实例并发重置,采样主世界 MSPT |
| 并发实例 TPS | 三区满员活动 TPS >= 19 | 见 19.5 压测脚本 |
| 出池进入延迟 | 池非空 <= 50 ms | 进入请求计时 |

DECIDED: 性能基准断言"异步不卡主线程"——具体化为"生成期间主线程单 tick 时间不超过 50ms(20 TPS 阈值)",而非笼统"流畅";违反即 FAIL。

### 21.4 测试覆盖与门禁

| 门禁 | 要求 |
|------|------|
| 边界覆盖 | 必须覆盖: 找不到 spawn(走 3x3 平台 fallback)、实例满(拒绝/排队)、重置时有玩家、扣费失败 |
| 合并门 | 仓库无 CI,由合并人本地执行 `compileJava` + `verifyModuleBoundaries` + `runGameTestServer`,判据是日志行而非退出码(见仓库根 `分支协作.md` 第四节);任一 FAIL 阻断合并 |
| 弱校验禁止 | 评审拒绝 `assertNotNull` 作为唯一断言的测试;每个测试必须有"删核心逻辑则 FAIL"的强断言 |
| 随机种子留存 | 随机化测试失败时打印触发 seed,保证可复现(对应 D3 确定性) |

---

## 二十二、实现路线图与风险登记

### 22.1 [已消化] 编码前必做 Spike(技术验证)

状态:三个 Spike 都已不再是编码前阻塞项。SP1 的结论被 R1 固定区域模型取代(region 不再动态分配,三块固定 region 的隔离由 `mining_wall` + `surface_rule` 保证,见 6.3);SP2 随离线管线一并作废(F021/F032,见 7.0);SP3 的结论被 D3 滑动重置取代(重置不再有区块删除与重生成,TPS 风险点消失,见 13.4)。

本节保留作演进归档。正式里程碑前先做三个 Spike 消解最大不确定性。Spike 只求验证可行性,代码可抛弃。

| Spike | 验证问题 | 产出 | 判定标准 |
|-------|----------|------|----------|
| SP1 region 分区 PoC | 单维度内按网格切互不重叠 region(D1)是否可行: 自定义 ChunkGenerator 按"世界坐标->region 本地坐标->查 bitset"填方块,region 间实心缓冲带是否隔离 | 最小 ChunkGenerator(Codec 注册到 CHUNK_GENERATOR),两个 region 各填不同图案 | 两 region 互不串扰、缓冲带实心 PASS |
| SP2 离线生成+连通性 PoC | 工作线程跑 Skeleton->NoiseCarving->ConnectivityFix 全局算法 + BFS 连通,最终分帧 setBlock 回主线程(D2/D8)是否能产出 100% 连通且不卡主线程 | 独立可跑的体素生成器 + `assertFullyConnected` + 分帧提交器 | 连通率 100% 且主线程无 >50ms 尖峰 PASS |
| SP3 重置异步化 PoC | 重置(删 region 区块 + 异步重生成 + 踢人)能否在主世界 TPS >= 18 下完成(D8 线程纪律) | 重置流程原型 + TPS 采样 | 重置期主世界 TPS >= 18 PASS |

DECIDED: 三个 Spike 全 PASS 才进入 M2 之后的正式实现;任一 FAIL 触发方案复审(尤其 SP1 失败需重审 D1 region 分区决策)。

### 22.2 里程碑(按依赖排序)

| 里程碑 | 内容 | 可验收产出 | 依赖 | 关键路径 | 可并行 |
|--------|------|-----------|------|----------|--------|
| M0 脚手架 | modid=miningdim 项目骨架、ForgeGradle 6、parchment、DeferredRegister 占位、ForgeConfigSpec 骨架 | 空 mod 能加载进 1.20.1 | - | 是 | - |
| M1 Spike | SP1/SP2/SP3(见 22.1) | 三 PoC 报告 + PASS/FAIL | M0 | 是 | SP1/SP2/SP3 互相可并行 |
| M2 维度与生成器 | 维度四件套 JSON(dimension_type / level_stem / noise_settings / pack.mcmeta)、BiomeSource(Codec, RegisterEvent)、四个 biome JSON | miningdim:mining 维度可进,三区按 region 判难度,区外基岩封死 | M1 | 是 | 与 M3 部分并行 |
| M3 [已作废] 离线生成核心 | Skeleton/NoiseCarving/ConnectivityFix + BFS + 分帧提交 + JUnit 连通性/确定性断言 | — | — | — | 随 F021/F032 整体删除,能力由原版 carver + placed_feature 承接 |
| M4 实例管理与持久化 | InstanceManager + SavedData(实例注册表/计数器)+ 玩家 Capability(prior 数据/instanceId/danger)+ 启动重建/孤儿清理 | 实例增删查持久化,重启后恢复 | M2 | 是 | 与 M5 部分并行 |
| M5 出生与传送 | spawn 扫描 + 3x3 fallback + 进入流程 Gateway(等 force-load)+ ChunkTicket 生命周期 | 安全出生、传送前生成完成、TTL 卸载 | M2,M4 | 是 | - |
| M6 矿区分层与矿物 | 三难度 region 分层、矿物权重分布、oreBudget 配额 | 矿物分布落期望区间(原计划 JUnit 大样本统计;仓库未引入 JUnit,此项未自动化) | M3 | 否 | 与 M7 并行 |
| M7 陷阱与压力系统 | 静态/动态陷阱、danger 模型(封顶+衰减)、动态刷怪(hardCap)、坍塌(分帧 setBlock)、岩浆限流 | danger 公式 GameTest 绿、刷怪不超 hardCap | M5 | 否 | 与 M6 并行 |
| M8 反滥用经济闸门 | 重置冷却/成本/每日上限、产出软上限、AFK、重入冷却、死亡惩罚(第十八章) | 各闸门 GameTest 绿 | M4,M7 | 否 | - |
| M9 预生成池与性能 | warm 池、模板缓存、并发限流、ChunkTicket 调优、压测达 19.5 指标 | 19.5 量化门槛全 PASS | M5,M3 | 是 | - |
| M10 命令/网络/UI | Brigadier 命令、SimpleChannel 进入 GUI/文案下发、配置完善 | 命令可用、进入 GUI 可选难度 | M5,M8 | 否 | 与 M11 并行 |
| M11 测试与硬化 | GameTest 全量、边界用例、CI 门禁、错误处理文案(第二十/二十一章) | CI 全绿、20.2 场景全覆盖 | M5,M8 | 否 | - |

关键路径: M0 -> M1 -> M2 -> M4 -> M5 -> M7 -> M8 -> M11。M3/M6 沿算法支线并行,M9 性能贯穿后期。

### 22.3 风险登记表

概率/影响: High/Medium/Low。

| ID | 风险 | 影响 | 概率 | 缓解 | 验证里程碑 |
|----|------|------|------|------|------------|
| R1 | 单维度 region 分区(D1)不满足"多实例隔离"需求(串扰/缓冲带不够) | High(动摇核心架构) | Low | SP1 先验;region 间 >=1 区块实心缓冲带;ChunkGenerator 严格按 regionBox 裁剪;若失败回退评估每实例独立维度的数据包预注册方案 | SP1 / M2 |
| R2 | 运行时动态维度方案被否(REJECTED)后,静态单维度对"无限实例"有上限 | Medium | High(已知约束) | 接受有限并发(globalCap),用 region 复用 + 重置代替无限新建;池化降低创建成本 | M4 / M9 |
| R3 | 连通性与性能权衡: 保证 100% 连通的 A* 打通/填实代价过高拖慢生成 | High | Medium | 已关闭: F021/F032 直接放弃 100% 连通承诺,整条离线管线删除;出生安全改由 SpawnSystem 真实区块扫描 + 3x3 兜底平台保证(7.0.5) | 已关闭 |
| R4 | 多实例并发离线生成线程安全(共享可变状态/与主线程竞态) | High | Medium | 已关闭: 本 mod 不再有自有生成线程,区块生成全归原版调度;世界写仍全经 server.execute(D8) | 已关闭 |
| R5 | ChunkTicket 泄漏(ticket 未释放致区块常驻、内存/磁盘膨胀) | High | Medium | ForgeChunkManager 带 owner(modid)统一管理;空置 TTL 释放;销毁实例强制 forceChunk(add=false);GameTest 断言 TTL 后卸载 | M5 / M9 |
| R6 | 传送到未生成区块导致掉虚空/卡死 | High | Medium | 进入流程强制等 genState==READY 且 chunk FULL 才传送;超时退款取消(20.2) | M5 / M11 |
| R7 | 重置时玩家在区导致数据损坏/卡死 | Medium | Medium | reset 的 UNLOAD 阶段强制撤离并释放全部 ticket,确认后才换几何(13.4);滑动重置不删区块,原"确认 playerSet 空才删区块"的口径已不适用 | M11 |
| R11 | `abuse.*` 闸门数值未接入 ForgeConfigSpec,硬编码在 `EconomyConstants`,违反 C6"所有平衡数值可配置" | Medium | 已发生 | 已登记为已知例外(18.7);接线任务待排期,接线前服主无法调这组数值 | 待排期 |
| R12 | 滑动重置的世界 X 游标单调消耗: 约 888 天撞 `MAX_REGION_WORLD_X` 后重置彻底不可用;越过 2^23 起原版实体位置/渲染 float 精度劣化 | High(功能彻底失效) | 低频但确定会发生 | 已在代码里以抛异常的方式硬暴露,绝不静默回绕复用旧坐标;候选缓解方向: 旧坐标确认落盘删除后回收游标区间,或改单轴推进为 Z 轴换行的二维铺开(13.4 约束 b/c) | 待排期 |
| R8 | 经济闸门数值失衡(过严劝退/过松失防) | Medium | High | 全闸门可配 + 默认保守;数值标 PENDING 留压测调参;接收社区反馈迭代 | M8 / 上线后 |
| R9 | 离线生成内存峰值超标(大 region bitset 常驻) | Medium | Medium | 已关闭: 不再分配任何整 region 体素结构(19.3) | 已关闭 |
| R10 | parchment/ForgeGradle 6 环境与 1.20.1 API 漂移 | Low | Low | 锁定 Forge 47.x + 固定 parchment 版本;M0 即验证编译;API 用前核实 | M0 |

---

## 二十三、总体设计定位

本系统的总体定位: 基于独立维度 region 分区的分层随机矿洞 Roguelike 副本系统(instanced layered roguelike mining dungeon)。它把"可重复刷新的随机矿洞副本"落实为以下确定的工程形态:

| 维度 | 定位 | 实现支撑 |
|------|------|----------|
| dungeon-like mining | 矿洞即副本: 每个难度区是一座有出生点、有风险梯度的地下副本 | 三固定 region 分区(D1/R1)+ `mining_wall` 基岩封边(6.3) |
| procedural generation | 程序化生成: 原版 `minecraft:noise` 全实心 + 原版 carver 挖空 + `placed_feature` 铺矿布陷阱 | 数据包驱动(7.0)+ 原版自身的确定性(7.0.4) |
| risk-reward | 风险收益对价: 难度分区 + danger 动态压力 + 陷阱,高风险对应高价矿物 | 三难度区 + danger 封顶衰减(D7)+ 经济闸门(第十八章) |
| replayable instances | 可重复实例: 三个常驻难度区,可定时/手动滑动到全新坐标刷新布局 | 固定实例(R1)+ D3 滑动重置 + R6 定时自动重置 |

与原始定位(原文第 12 节)的差异与确定化:

| 项 | 原始表述 | 本设计确定化 |
|----|----------|--------------|
| 维度模型 | "推荐独立维度或实例化" | DECIDED: 单静态维度 miningdim:mining + region 分区(D1),否决运行时动态维度(REJECTED) |
| 分层 | "因 Y 轴限制用区域分层" | DECIDED(R1/R2): 三个独立 region 沿 X 轴并排,一 region 一难度,难度完全不依赖 Y 轴。注意这既不是"按 Y 垂直分层",也不是"一个 region 内水平分三区" |
| 生成 | "三阶段骨架+连通+噪声" | DECIDED(F021/F032 修订): 原版 `minecraft:noise` 先填实心 + 原版 carver 挖空 + `placed_feature` 铺装;三阶段离线算法与 100% 连通承诺已判废 |
| 重复性 | "整体重置 seed++ regenerate" | DECIDED(D3): 重置 = 整块 region 滑到一段从未生成过的世界坐标(非 seed++;seed 仍由 instanceId 派生,但不再决定地形) |

定位边界(非目标): 本系统不是开放世界探索(region 有界)、不是 PvP 竞技场(三区共享,但玩法上不鼓励对抗)、不追求无限并发实例(R1 下恒为三个常驻难度区,以滑动重置代偿"换新图"的需求)。

---

## 二十四、总结

本设计文档把"服务器内可重复刷新的随机矿洞副本"从玩法构想落实为锁定 Forge 1.20.1 的可交付实现规格。核心玩法意图基本保留: 随机生成矿洞、多难度分区、矿物与难度挂钩、高难度带陷阱与动态压力、随机安全出生、整体重置、独立维度。唯一被放弃的是"矿道完全连通"——实现期改用原版生成链后无法再给出全局可达断言,详见 7.0.5 与 24.1。

### 24.1 核心需求落实清单

[x] 随机生成矿洞结构(原版 `minecraft:noise` 全实心 + `cave`/`cave_extra_underground`/`canyon` carver 挖空,7.0)
[ ] 矿道完全连通 —— 已放弃。离线三阶段与 ConnectivityFix 随 F021/F032 删除,原版 carver 不提供全局可达断言;出生安全改由 SpawnSystem 真实区块扫描 + 3x3 兜底平台保证(7.0.5)
[x] 多难度分区矿区(三个独立 region 沿 X 轴并排,一 region 一难度,难度与 Y 无关;R1/R2)
[x] 矿物与难度挂钩(各难度 biome 的 `features` 列表差异化,7.0.3;Java 权重表已降级为平衡参考)
[x] 高难度陷阱与动态压力(静态陷阱走 `placed_feature`,动态陷阱 + danger 封顶衰减模型,D7)
[x] 玩家随机安全出生(真实区块环形扫描 + 安全谓词 + 3x3 兜底平台,第十一章)
[x] 支持整体重置(D3 滑动:整块 region 滑到全新坐标 + 退役区块异步回收,13.4)
[x] 推荐独立维度(单静态维度 miningdim:mining + region 分区,D1)
[x] 定时自动重置(R6,每难度独立周期 + 倒计时广播,13.1)

### 24.2 关键设计决策清单(含本轮新增)

[x] 锁定 Forge 1.20.1 + Forge 47.x + Java 17 + ForgeGradle 6 + parchment(全 API 以此版本为准)
[x] 单维度 region 分区代替运行时动态维度(D1;运行时动态维度 REJECTED,因 1.20.1 注册表启动后冻结)
[ ] 离线预生成代替运行时全局算法(D2)—— 已判废(F021/F032)。改用原版 `minecraft:noise` + carver + `placed_feature`,`MiningChunkGenerator`/`OfflineCaveGenerator`/`GenerationScheduler` 全部删除,详见 7.0
[x] 确定性 seed 持久化(D3;但地形确定性现由原版按世界坐标保证,`InstanceState.seed` 只驱动本 mod 自有派生随机,7.0.4)
[ ] 连通性作最后一道闸 + BFS 边界即 region bbox(D4)—— 已判废,与 D2 同批删除;实心边界改由 `mining_wall` + `surface_rule` 基岩封边承担(6.3)
[x] SavedData + Capability 持久化(D5;实例注册表用 SavedData,玩家数据用 Forge Capability + PlayerEvent.Clone;`computeIfAbsent` 用 1.20.1 三参签名,`SavedData.Factory` 是 1.20.2+ 不可用,12.5)
[ ] 实例分配模型(D6;instanceId 自增 long,seed 由全局种子+instanceId 派生,globalCap 限并发)—— 动态分配路径代码保留但 R1 下不触发,现行是三固定实例路由(12.1a)
[x] danger 封顶 + 衰减(D7;DANGER_MAX 封顶,timeSpent 软封顶收敛,降频评估每 20 tick)
[x] 线程纪律(D8;世界写经 server.execute 回主线程,纯计算在工作线程)
[x] 经济闸门防刷(第十八章;重置冷却/成本/每日上限、产出软上限、AFK、重入冷却、死亡惩罚)
[x] ChunkTicket 生命周期(第十九章;ForgeChunkManager 滑动 ticket + 空置 TTL 释放;`tickRadius` 由 `perf.loadRadiusChunks` 派生,非独立配置)
[ ] 异步生成池与量化容量指标(第十九章;warm 池/模板缓存/并发限流)—— 随离线管线一并作废(19.4);压测验收门槛保留并按 R1/D3 重写(19.5)
[x] 错误处理与边界兜底(第二十章;spawn fallback、传送等待超时、重置踢人)
[x] 完整测试策略(第二十一章;Forge GameTest 承载算法断言与集成测试 + 性能基准 + 本地合并门;无 JUnit、无 CI)
[x] 实现路线图与风险登记(第二十二章;M0-M11 里程碑 + R1-R12 风险表)

本轮架构修订新增的决策项:

[x] R1 固定三区域模型:开服预建恰好三个固定难度实例,shared 且常驻不 GC,`allocate` 退化为路由(12.1a)
[x] R2 难度=区域:三块 region 沿 X 轴并排,`BiomeSource` 按 XZ 查 region 定难度,y 不参与(6.2/6.4)
[x] D1 的纯 datapack 实现:第四个群系 `mining_wall` + `surface_rule` 基岩封边,取代 ChunkGenerator 填实心墙(6.3)
[x] F021/F032 生成管线换代:原版 `minecraft:noise` + carver + `placed_feature` 取代离线三阶段(7.0)
[x] D3 滑动重置:世界 X 游标单向推进到从未生成过的坐标,退役区块交 `RetiredRegionGc` 异步回收(13.4)
[x] R6 定时自动重置:每难度独立周期 + `autoResetWarnSeconds` 倒计时 + `AutoResetData` 持久化(13.1)
[x] 难度门控改矿工职业等级:Easy L1 / Medium L4 / Hard L8,不再读 `player.experienceLevel`(14.4)
[x] 入场费机制:`entry.entryFee*` 三档 CREDIT 纯 sink,门控预判 + 传送前扣款(14.2/16.2.11)

### 24.3 交付边界声明

本文档为实现规格,所有标注 PENDING待校验 的平衡数值(冷却/成本/上限/danger 系数/性能门槛初值)均给出建议初值,须在目标硬件压测与玩法测试后定稿;所有 Forge/MC API 名以 1.20.1 + Forge 47.x 为准,实现前以本地反编译源码或 javap 逐一核实,不得套用其他版本语法。标注 DECIDED 的跨章设计决策(D1-D8 及本章列出项)为架构基线,不得在实现期擅自另立方案;如需变更须走方案复审。
