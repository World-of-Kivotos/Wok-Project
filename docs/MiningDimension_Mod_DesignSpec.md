# Minecraft Forge 矿山系统设计文档（Mining Dimension System）

## 文档元信息

- 目标平台: Minecraft 1.20.1 + Forge 47.x + Java 17。所有 API 名称以此版本为准, 不得套用其他版本语法。
- 用途: 本 mod 实现阶段的唯一架构、数值与机制参考; 所有常量以本文档为准, 不得凭记忆改写。
- 状态图例: DECIDED 已定稿 / PENDING 待校验 / REJECTED 已否决(附理由) / TODO 实现期补全。
- 编码前阻塞项: 进入编码前必须先完成第二十二章风险登记中标记为 Spike 的技术预研(region 分区 PoC、离线生成与连通性 PoC、重置异步化 PoC)。
- 本版修订: 针对设计评审反馈, 补全目标平台与版本约束、注册架构、维度与生成模型、矿物数值表、实例生命周期与持久化、网络协议、配置、命令权限、反滥用经济、性能容量、错误处理、测试策略、实现路线图共 11 类工程契约。

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
| `side` | `BOTH` | mod 两端都需安装(自定义维度的 `ChunkGenerator` 类客户端需用于区块同步反序列化) |

`side = BOTH` 是硬约束:自定义 `ChunkGenerator` 与 `BiomeSource` 的 `Codec` 在客户端登录阶段参与维度同步反序列化,若客户端缺失本 mod 将直接断连。此点与第三章"端职责划分"表一致。

### 1.3 依赖与兼容性风险(DECIDED 风险登记)

| 风险项 | 严重度 | 冲突面 | 缓解策略 |
| --- | --- | --- | --- |
| 其他增维度 mod | Minor | 各 mod 维度走独立 datapack `level_stem`,`ResourceKey` 命名空间隔离 | `miningdim:mining` 命名空间唯一,天然无冲突;不抢占 `minecraft:*` 维度 |
| 其他自定义 `ChunkGenerator` mod | Major | 共享 `BuiltInRegistries.CHUNK_GENERATOR` 注册表,`ResourceLocation` 撞名才冲突 | 注册 id 一律 `miningdim:mining_chunk_generator` 前缀化;只读注册,不替换他人条目 |
| 区块生成 / 地形 mod(如 Terralith、TerraForged) | Minor | 仅作用于其声明的维度,本 mod 维度用专属 generator,互不接管 | 本 mod generator 不挂 `minecraft:overworld`,二者维度不重叠 |
| 刷怪管理 mod(如 In Control、刷怪上限调整) | Major | `MobPressureSystem` 的刷怪与第三方刷怪规则叠加,可能突破我方密度上限 | 本 mod 刷怪走 `finalizeSpawn` 显式生成而非依赖自然刷怪规则,并自带每实例硬上限计数;见第十章 |
| 经济 / 掉落 mod | Minor | `OreGenerator` 产出的方块是原版矿石,第三方掉落改动会影响收益曲线 | 矿物权重数值在 `ConfigManager` 暴露,允许服主与第三方平衡;不硬编码掉落 |
| 维度运行时增删类 mod(KubeJS dimension、动态维度) | Major | 若第三方在运行时操作 `MinecraftServer.levels`,可能与我方 region 网格假设冲突 | 我方 REJECTED 运行时增删维度(见第三、四章),仅使用启动期 datapack 维度,不受其影响;但与此类 mod 共存时不保证其行为 |
| Sodium/Rubidium 等渲染 mod | Minor | 纯客户端渲染,不触碰维度逻辑 | 无生成逻辑交互,兼容 |

依赖声明纪律:除 Forge + Minecraft 外,本 mod 默认零强制第三方依赖。任何可选集成(如对 JEI 暴露矿物权重)必须用 `mandatory = false` 软依赖声明,且代码侧用 `ModList.get().isLoaded("jei")` 守卫,严禁因可选 mod 缺失而抛异常。

### 1.4 版本敏感章节声明

下列后续章节包含直接依赖 1.20.1 / Forge 47.x 具体 API 的实现细节,任何 MC 大版本迁移都必须重新核验这些章节,标记为版本敏感(Version-Sensitive):

| 章节 | 版本敏感点 | 1.20.1 关键事实(迁移时重点核验) |
| --- | --- | --- |
| 三、总体架构与模块接口 | 注册时机、单例生命周期 | `DeferredRegister` 在 `FMLConstructModEvent`;`ChunkGenerator`/`BiomeSource` Codec 用 `RegisterEvent` 注册到 `BuiltInRegistries.CHUNK_GENERATOR` / `BIOME_SOURCE` |
| 四、维度与数据包注册 | 维度建模方式 | 维度走 datapack:`dimension_type/*.json` + `dimension/*.json`(`level_stem`);运行时增删维度 REJECTED |
| 七、矿洞生成系统 / 区块生成 | `ChunkGenerator` 抽象方法签名 | `fillFromNoise`、`buildSurface`、`applyCarvers`、`createBiomes` 的 1.20.1 签名;`Codec`(非 1.20.5 的 `MapCodec`) |
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
| G2 | 随机生成矿洞结构 | 随机但可控的洞穴拓扑,非简单噪声挖空 | 七(离线生成三阶段) |
| G3 | 矿道完全连通 | 玩家从出生点可达全部可行走空间,无孤岛死锁 | 七(ConnectivityFix,D4) |
| G4 | 多难度分层矿区 | Easy / Medium / Hard 三区,而非靠 Y 轴分层 | 六(分层矿区) |
| G5 | 矿物与难度挂钩 | 难度越高,高价值矿物权重越高 | 八(OreGenerator) |
| G6 | 高难度带陷阱与动态压力 | 静态陷阱 + 随停留时间上升的动态危险 | 九、十(TrapSystem / MobPressure) |
| G7 | 进入后随机安全出生 | 出生点保证安全且 ∈ 主连通分量 | 十一(SpawnSystem,D4) |
| G8 | 支持整体重置 | 单实例 region 级重置,不影响其他实例 | 十三(重置系统,D1) |
| G9 | 推荐独立维度 | 单一专属维度 `miningdim:mining`,内部网格切 region | 三、四(D1) |

### 2.2 硬约束(贯穿全文,不可协商)

下列约束的优先级高于任何单模块的便利性。每条标注约束类型与可验收的判定口径。

| 编号 | 硬约束 | 类型 | 判定口径(如何验收 FAIL) | 关联决策 |
| --- | --- | --- | --- | --- |
| C1 | 单一独立维度 | 架构 | 启动后 `server.getAllLevels()` 中本 mod 维度恒为 1 个(`miningdim:mining`);出现运行时新建/销毁 `ServerLevel` 即 FAIL | D1 |
| C2 | 实例 = 维度内不重叠 region | 架构 | 任意两实例 `regionBox` AABB 相交即 FAIL;实例间缓冲带 < 1 区块即 FAIL | D1 |
| C3 | 确定性可复现 | 正确性 | 同 `instanceSeed` 两次离线生成,体素 bitset 必须逐位相等;生成后区块逐方块相等 | D2、D3 |
| C4 | 矿洞 100% 连通 | 正确性 | 出生点 BFS 不可达的可行走空气体素数 > 0 即 FAIL(允许被主动填实的小岛,见 D4) | D4 |
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
| 单实例离线体素生成时长 | <= 3000 ms(默认 region 256x384x256) | `OfflineCaveGenerator.generate` 工作线程墙钟 | 在工作线程,不阻塞主线程;超时仅告警不崩 |
| 实例分配引起的主线程单 tick 抖动 | <= 2 ms | 分配时主线程仅做登记与调度,生成异步 | C7 保证;主线程不得直接跑生成 |
| `MiningChunkGenerator` 单区块填充 | <= 1.5 ms | 查表填方块,无跨区块算法 | D2 保证查表为 O(区块体素数) |
| 单实例 danger 评估开销 | <= 0.2 ms / 玩家 / 评估周期 | 每 20 tick 或事件驱动 | D7 降频 |
| 单实例并发刷怪硬上限 | 建议 30 只 | `MobPressureSystem` 计数器 | 防止与第三方刷怪 mod 叠加爆量(见 1.3) |
| 全局实例数上限 | 建议 32(可配,见第十二章 instance.globalCap) | `InstanceManager` | D6,超限拒绝/排队 |
| 内存:单实例 bitset | 约 256x384x256 bit ≈ 3.0 MiB | `BitSet` 扁平一维 | 仅生成期常驻,生成完落盘后可释放 |

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
[生成/玩法层] OfflineCaveGenerator  OreGenerator  SpawnSystem
                    |  (产出 bitset/voxel 视图,被 v 查)
                    v
              MiningChunkGenerator(原版区块生成回调,查表填方块)
                                          ^
                          TrapSystem  MobPressureSystem(运行期玩法,读实例状态)
                    |
[基础设施层]  ConfigManager   PersistenceLayer(SavedData + Capability)   NetworkHandler(信道与包注册)
```

依赖方向表(行依赖列,Y = 允许依赖,空 = 禁止):

| 依赖方 \ 被依赖 | DimMgr | InstMgr | OfflineGen | ChunkGen | OreGen | Trap | MobPress | Spawn | Reset | Net | Config | Persist |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| MiningCommands | Y | Y | | | | | | | Y | Y | Y | |
| NetworkHandler | Y | Y | | | | | | | | | Y | |
| DimensionManager | | Y | | | | | | | | | Y | Y |
| InstanceManager | | | Y | | | | | Y | | Y | Y | Y |
| OfflineCaveGenerator | | | | | | | | | | | Y | |
| MiningChunkGenerator | | Y | | | Y | Y | | | | | Y | |
| OreGenerator | | | | | | | | | | | Y | |
| TrapSystem | | Y | | | | | | | | Y | Y | |
| MobPressureSystem | | Y | | | | | | | | Y | Y | Persist(读 danger) |
| SpawnSystem | | Y | | | | | | | | | Y | |
| ResetSystem | | Y | | | | | | | | Y | Y | Y |

环依赖检查:`MiningChunkGenerator` 依赖 `InstanceManager`(只读查 region/bitset),而 `InstanceManager` 不依赖 `MiningChunkGenerator`,二者不成环。`OfflineCaveGenerator` 是叶子(只依赖 Config),不回调任何业务模块,符合 D2(纯计算)。

### 3.2 单例与生命周期归属

| 模块 | 实例形态 | 生命周期归属 | 创建/销毁时机 |
| --- | --- | --- | --- |
| ConfigManager | 静态(`ForgeConfigSpec` 持有) | mod 类加载 | `FMLConstructModEvent` 注册 spec;`ModConfigEvent` 装载值 |
| NetworkHandler | 静态单例(`SimpleChannel`) | mod 生命周期 | `FMLCommonSetupEvent` 注册包 |
| DimensionManager | 服务端单例 | 绑定 `MinecraftServer` | `ServerStartingEvent` 创建,`ServerStoppingEvent` 释放 |
| InstanceManager | 服务端单例 | 绑定 `MinecraftServer` | `ServerStartedEvent` 从 SavedData 重建,`ServerStoppingEvent` flush 落盘 |
| PersistenceLayer (SavedData) | 服务端,挂矿山维度 `DimensionDataStorage` | 绑定 `ServerLevel(miningdim:mining)` | 首次访问惰性创建,世界保存时序列化 |
| OfflineCaveGenerator | 无状态计算器(可多线程并发实例) | 任务级 | 每次实例分配创建一次性任务,跑完即弃 |
| MiningChunkGenerator | 每维度一个(随 `LevelStem` 反序列化) | 绑定 `ServerLevel` | 服务器启动 `createLevels` 时由 datapack Codec 创建 |
| OreGenerator / TrapSystem / MobPressureSystem / SpawnSystem / ResetSystem | 服务端单例(无状态服务,状态存 InstanceState/Capability) | 绑定 `MinecraftServer` | 随 InstanceManager 创建 |

判定口径:除 `MiningChunkGenerator`(每维度实例,但本 mod 维度恒一个,见 C1)与 `OfflineCaveGenerator`(任务级)外,所有业务模块均为服务端单例,客户端不持有任何业务单例(C5)。

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

OfflineCaveGenerator —— D2 的离线体素生成器。纯计算、无世界写、无 MC 主线程依赖,可在工作线程并发(C7)。按 Skeleton -> NoiseCarving -> ConnectivityFix 三阶段产出整 region 的布尔占用 bitset。确定性由单一 `RandomSource`(`seed` 驱动)与 `hash(seed,x,z,featureId)` 派生保证(D3)。

```java
public interface OfflineCaveGenerator {
    // 在调用线程同步跑完三阶段;不触碰 ServerLevel;同 seed 必返回逐位相等的 VoxelOccupancy
    VoxelOccupancy generate(long seed, RegionBox regionBox, GenParams params);

    interface VoxelOccupancy {                 // 扁平一维 bitset 视图
        boolean isSolid(int localX, int localY, int localZ);
        BlockPos spawnAnchor();                // 主连通分量锚点 = 出生点候选(D4)
        List<BlockPos> spawnCandidates();      // 均 ∈ 主连通分量(D4/G7)
    }
}
```

ConnectivityFix 是三阶段最后一道闸,体现在 `generate` 内部:6-邻接 BFS 以 `regionBox` 为天然边界(box 外恒实心,D4),标记连通分量;体积 < `minIslandSize` 的非主分量填实,否则用 A* 打通隧道,保证 C4。

MiningChunkGenerator —— 原版 `ChunkGenerator` 子类,经 datapack `Codec` 注册到 `BuiltInRegistries.CHUNK_GENERATOR`(1.20.1 为 `Codec<? extends ChunkGenerator>`,非 1.20.5 的 `MapCodec`)。区块回调内只做"世界坐标 -> region 本地坐标 -> 查 `VoxelOccupancy`"填方块,绝不在回调里跑跨区块算法(D2)。

```java
public class MiningChunkGenerator extends ChunkGenerator {
    public static final Codec<MiningChunkGenerator> CODEC = /* RecordCodecBuilder, 字段: biomeSource + settings */;

    @Override protected Codec<? extends ChunkGenerator> codec();
    @Override public CompletableFuture<ChunkAccess> fillFromNoise(   // 查 bitset 填石/空气
        Executor executor, Blender blender, RandomState random,
        StructureManager sm, ChunkAccess chunk);
    @Override public void buildSurface(WorldGenRegion region, StructureManager sm, RandomState rs, ChunkAccess chunk);
    @Override public void applyCarvers(/* 1.20.1 签名 */);           // no-op:挖空已由离线 bitset 完成
    @Override public int getBaseHeight(int x, int z, Heightmap.Types t, LevelHeightAccessor lvl, RandomState rs);
    @Override public NoiseColumn getBaseColumn(int x, int z, LevelHeightAccessor lvl, RandomState rs);
    // region 外区块(缓冲带)恒填实心墙,作为 BFS 边界与实例隔离(D1/D4/C2)
}
```

OreGenerator —— 在 region 体素已定型后,按难度权重把石/深板岩体素替换为矿石。权重 `weight = baseWeight * difficultyMultiplier`(G5),全部数值取自 Config(C6)。确定性同样用 `hash(seed,x,z,"ore")` 派生(D3)。

```java
public interface OreGenerator {
    // 在世界写阶段(server.execute 内)对已生成区块执行;或在离线阶段标注矿石占位再由 ChunkGen 落子
    void placeOres(ServerLevel level, ChunkPos chunk, InstanceState instance);
    // 离线铺矿: 配额轮盘 + 矿脉成簇, 产出不可变铺矿表, 详见第八章 OreGenerator.generate(D2)
}
```

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

SpawnSystem —— G7/D4 安全出生。从 `VoxelOccupancy.spawnCandidates()`(均 ∈ 主连通分量)中筛选满足安全谓词(头顶 2 格空气 / 脚下固体 / 无岩浆 / 非陷阱区)的点。

```java
public interface SpawnSystem {
    BlockPos resolveSpawn(ServerLevel level, InstanceState instance, ServerPlayer player); // 无合法点抛 IllegalStateException(应不发生,C4 保证)
    boolean isSafe(ServerLevel level, BlockPos pos, InstanceState instance);
}
```

ResetSystem —— G8/D1 单实例 region 级重置:仅删除/重生成该 region 的区块,不触碰其他实例,不增删维度(C1)。先疏散玩家,再 `RESETTING` 态下清区块、换新 `instanceSeed` 派生、重跑离线生成。

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
public final class MiningCommands {
    public static void register(CommandDispatcher<CommandSourceStack> dispatcher);
    // /mining enter <difficulty> [shared] | /mining leave | /mining reset <id> <mode>
    // /mining list | /mining purgeOrphans   (后者需 OP 权限等级)
}
```

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

下列时序覆盖 G1/G2/G3 的端到端路径,显式标注线程归属(D8/C7):主线程做编排与世界写,工作线程做纯计算。

```text
1. [主线程] 玩家触发 /mining enter -> MiningCommands 解析参数,鉴权
2. [主线程] InstanceManager.allocate(player, difficulty, shared)
   2a. 检查 activeInstanceCount < maxInstances;超限 -> InstanceLimitException -> 玩家提示(入口层捕获,C9)
   2b. PersistenceLayer.nextInstanceId() + deriveSeed(id)  -> 登记 InstanceState{genState=PENDING}
   2c. DimensionManager.regionBoxForGridIndex(gridIndex) -> regionBox(含缓冲带,C2/D1)
   2d. 提交离线任务到工作线程池,genState=GENERATING,返回 CompletableFuture
3. [工作线程] OfflineCaveGenerator.generate(seed, regionBox, params)
   Skeleton -> NoiseCarving -> ConnectivityFix(6-邻接 BFS + A* 隧道,C4/D4)-> VoxelOccupancy(含 spawnAnchor/candidates)
4. [主线程, server.execute 回调] future 完成 -> 把 VoxelOccupancy 交给 InstanceManager 缓存到 InstanceState
   genState=READY;触发该 region 区块加载
5. [主线程] MiningChunkGenerator.fillFromNoise 查 VoxelOccupancy 填石/空气;region 外区块填实心墙(D1/D2)
6. [主线程] OreGenerator.placeOres + TrapSystem.placeStaticTraps(按区块,server.execute,C6/G5/G6)
7. [主线程] SpawnSystem.resolveSpawn(候选 ∈ 主连通分量)-> 安全出生点(G7/D4)
8. [主线程] 记录玩家进入前现场到 Capability(preEnterPos/GameType,D5)-> 传送 -> onPlayerEnter(refCount++)
9. [主线程] MobPressureSystem 初始化 danger=base;NetworkHandler.sendDangerToClient(展示,C5)
```

关键不变量:步骤 3 是唯一在工作线程的重计算,且不触碰任何 `ServerLevel`(C7);步骤 4~9 全部在主线程或其 `server.execute` 回调内执行世界写(D8)。步骤 5 的区块回调内绝无跨区块算法,只查表(D2),满足 2.3 的单区块填充时延阈值。

### 3.5 端职责划分(logical client / server / both)

C5 服务端权威:所有世界状态与决策在服务端;客户端只渲染与接收同步。下表逐功能标注归属,并标出需要网络包的跨端交互。

| 功能 | 逻辑端 | 网络包 | 方向 | 说明 |
| --- | --- | --- | --- | --- |
| 维度注册 / `LevelStem` 反序列化 | both | 维度同步(原版内置) | S2C | 客户端需本 mod 的 `ChunkGenerator`/`BiomeSource` Codec 才能反序列化(1.2 的 `side=BOTH` 硬约束) |
| 区块方块数据 | both | 区块同步(原版内置) | S2C | 服务端生成,客户端仅渲染 |
| 实例分配 / 引用计数 | server | 无(命令触发) | — | 纯服务端权威,C5 |
| 离线体素生成 | server | 无 | — | 工作线程纯计算,客户端无感 |
| 矿物 / 静态陷阱布设 | server | 无 | — | 落为方块后随区块同步,无专用包 |
| 动态陷阱触发 | server | 无(粒子/音效随原版广播) | — | 服务端 `level.sendParticles` 广播,客户端被动渲染 |
| danger 计算 | server | danger 展示包 | S2C | `sendDangerToClient`,客户端仅画 HUD,不参与计算(C5) |
| danger HUD 渲染 | client | 上条同包 | — | 纯渲染;无 C2S danger 包 |
| 出生点解析 / 传送 | server | 无 | — | 服务端权威,传送后原版同步玩家位置 |
| 进入 / 离开 / 重置命令 | server(执行) + client(/ 命令输入) | Brigadier(原版命令通道) | C2S | 命令在服务端执行;客户端仅发命令文本 |
| 进入前现场(坐标/gamemode)持久化 | server | 无 | — | Capability,服务端存档(D5) |
| 实例列表 / 调试 UI(若有) | both | 实例信息展示包 | S2C(请求 C2S) | PENDING:仅当需要客户端面板时引入;默认用命令文本回显,不开包 |
| 配置(SERVER 类型) | server | 配置同步(原版内置) | S2C | `ModConfig.Type.SERVER` 登录时下发,客户端只读 |

跨端交互最小化原则:本 mod 仅需一个自定义 S2C 展示包(danger HUD),其余跨端均复用原版内置同步(维度、区块、命令、配置)。这把自定义网络面收敛到最小,降低与第三方网络 mod 的协议冲突风险,并满足 C5 服务端权威。

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
| `REGION_GAP` | 32 | 相邻 region 之间缓冲带格数(2 区块) | >=16,实心填充 |
| `REGION_STRIDE_X` | 544 | XZ 网格步长 = SIZE + GAP | 派生量 |
| `REGION_MIN_Y` | -64 | region 底部世界 Y | = 维度 `min_y` |
| `REGION_HEIGHT` | 384 | region 高度 | = 维度 `height`,见 4.3 |
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

`voxelIndex` 即 D2 体素 bitset 的扁平一维下标(布尔占用网格)。落在缓冲带或 region 外的世界坐标由 `InstanceManager.regionAt(worldX, worldZ)` 返回 `null`,`ChunkGenerator` 据此填实心。

region 体素体积估算:256 * 256 * 384 ≈ 2.52e7 体素,1 bit/体素 ≈ 3.0 MiB(`java.util.BitSet`)。这是单实例后台生成阶段(D2)的内存峰值,需纳入 `maxInstances` 上限校验(D6)。若实测内存吃紧,优先下调 `REGION_SIZE`,而非压缩 Y。

### 4.3 Y 轴预算与垂直结构

状态:DECIDED

维度高度对齐原版可用上限,给三层矿区(第六章)留足垂直空间:

| 字段 | 值 | 说明 |
| --- | --- | --- |
| `min_y` | -64 | 与原版 overworld 一致 |
| `height` | 384 | 总高 -64..319,必须为 16 的倍数 |
| `logical_height` | 384 | 传送/区块逻辑高度 |
| `local_y` 范围 | 0..383 | 体素本地 Y |

整段 Y 预算切给三个难度区垂直堆叠(详见 6.2),基材随世界 Y 自然切换(深板岩在 Y<0 区段),从而同时满足"放弃 Y 决定难度"与"用石头/深板岩区分难度"两个旧需求(矛盾消解见 6.1)。

### 4.4 数据包维度注册三件套

状态:DECIDED(Critical 缺口闭合)

1.20.1 维度走数据包动态注册表(datapack registries,服务器启动时从 JSON 加载)。mod 在 jar 内置数据包(`resources/data/...`)中提供以下三类 JSON,服务器启动即注册,无需任何 Java 注册代码;Java 侧只需在 `RegisterEvent` 注册 `ChunkGenerator`/`BiomeSource` 的 Codec(见第五章),JSON 的 `generator.type` 字段据此 ResourceLocation 反序列化。

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
  "logical_height": 384,
  "height": 384,
  "min_y": -64,
  "infiniburn": "#minecraft:infiniburn_overworld",
  "effects": "minecraft:the_nether",
  "monster_spawn_light_level": { "type": "minecraft:uniform", "min_inclusive": 0, "max_inclusive": 7 },
  "monster_spawn_block_light_limit": 0
}
```

字段决策说明:`has_skylight=false` + `has_ceiling=true`(纯地下,无天空光,配合 danger 系统的光照下降);`bed_works`/`respawn_anchor_works=false`(禁止在副本内设重生点,死亡按 D5 capability 还原进入前位置);`natural=false`(禁用床、禁止下界传送门联动);`effects` 选 `minecraft:the_nether` 以去除天空渲染(可换自定义,PENDING)。`monster_spawn_light_level` 给 mob 压力系统(第十章)提供原版刷怪光照接口。

文件 (2) level_stem / 维度实例 `dimension`
路径:`src/main/resources/data/miningdim/dimension/mining.json`

该 JSON 即 `LevelStem` 的序列化形式:绑定 `type`(指向文件 1)与 `generator`(引用第五章注册的自定义 `ChunkGenerator` Codec,其内嵌自定义 `BiomeSource`)。

```json
{
  "type": "miningdim:mining",
  "generator": {
    "type": "miningdim:mining_chunk_generator",
    "biome_source": {
      "type": "miningdim:mining_biome_source"
    },
    "settings": {
      "region_size_x": 256,
      "region_size_z": 256,
      "region_gap": 32,
      "min_y": -64,
      "height": 384
    }
  }
}
```

`generator.type` = `miningdim:mining_chunk_generator` 必须与第五章 `RegisterEvent` 向 `BuiltInRegistries.CHUNK_GENERATOR` 注册 Codec 时用的 ResourceLocation 完全一致;`biome_source.type` 同理对应 `BuiltInRegistries.BIOME_SOURCE`。`settings` 段由 `MiningChunkGenerator` 的 Codec 解码,使其与 4.2 网格常量保持单一数据源(避免 Java 常量与 JSON 双写漂移,二者取一为准,建议以 JSON 为权威,Java 常量仅作默认值)。

文件 (3) 维度生效保障 —— 内置数据包元数据
路径:`src/main/resources/pack.mcmeta`(mod jar 根内置数据包描述,`pack_format` 1.20.1 = 15)

```json
{ "pack": { "description": "miningdim builtin data", "pack_format": 15 } }
```

注:1.20.1 下,mod jar 内 `data/<ns>/dimension/<name>.json` 会作为 builtin datapack 在新建世界/启动时自动加入并注册维度;无需玩家手动放置数据包。已存档世界若在加入此 mod 前创建,新维度也会在下次启动 `createLevels` 时补建(原版按 `LevelStem` 注册表逐项建 `ServerLevel`)。

三件套注册链路总览:

| 文件 | 注册表 | 触发时机 | 引用关系 |
| --- | --- | --- | --- |
| `dimension_type/mining.json` | `minecraft:dimension_type`(datapack registry) | 服务器启动加载 datapack | 被 dimension JSON 的 `type` 引用 |
| `dimension/mining.json` (LevelStem) | `minecraft:dimension`(datapack registry) | `createLevels` 时建 `ServerLevel` | `type`->文件1;`generator`-> CHUNK_GENERATOR codec |
| `pack.mcmeta` | 内置 datapack 识别 | jar 加载 | 使上面两者被识别为有效数据 |

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
| `MiningChunkGenerator` 的 `Codec` | `BuiltInRegistries.CHUNK_GENERATOR` | `RegisterEvent.register(Registries.CHUNK_GENERATOR, ...)` | `RegisterEvent` | mod 事件总线 |
| `MiningBiomeSource` 的 `Codec` | `BuiltInRegistries.BIOME_SOURCE` | `RegisterEvent.register(Registries.BIOME_SOURCE, ...)` | `RegisterEvent` | mod 事件总线 |
| `DimensionType`(`miningdim:mining`) | `minecraft:dimension_type`(datapack) | 内置数据包 JSON | 服务器启动加载 | 启动线程 |
| `LevelStem`(`dimension/mining.json`) | `minecraft:dimension`(datapack) | 内置数据包 JSON | `createLevels` | 启动线程 |
| `Biome`(三难度区各一,若用自定义) | `minecraft:worldgen/biome`(datapack) | 内置数据包 JSON | 服务器启动加载 | 启动线程 |
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

### 5.3 RegisterEvent(ChunkGenerator / BiomeSource 的 Codec)

状态:DECIDED(Major 缺口闭合)

自定义 `ChunkGenerator` 与 `BiomeSource` 的注册对象是它们的 `Codec`(用于 datapack JSON 的反序列化),目标是原版 `BuiltInRegistries.CHUNK_GENERATOR` / `BIOME_SOURCE`。`DeferredRegister` 不直接覆盖这两个原版 codec 注册表,故用 `RegisterEvent` 直注:

```java
@SubscribeEvent
public static void onRegister(RegisterEvent event) {
    event.register(Registries.CHUNK_GENERATOR, helper ->
        helper.register(
            new ResourceLocation("miningdim", "mining_chunk_generator"),
            MiningChunkGenerator.CODEC));   // Codec<MiningChunkGenerator>

    event.register(Registries.BIOME_SOURCE, helper ->
        helper.register(
            new ResourceLocation("miningdim", "mining_biome_source"),
            MiningBiomeSource.CODEC));      // Codec<MiningBiomeSource>
}
```

要点:

- `Registries.CHUNK_GENERATOR` / `Registries.BIOME_SOURCE` 是 `ResourceKey<Registry<Codec<...>>>`,与第四章 JSON 的 `generator.type` / `biome_source.type` 字段值一一对应。ResourceLocation 字符串必须与 JSON 完全一致,否则 datapack 加载时反序列化失败、维度建立崩溃。
- `MiningChunkGenerator.CODEC` 类型为 `Codec<MiningChunkGenerator>`(1.20.1 这两个注册表的元素类型是 `Codec<? extends ChunkGenerator>` / `Codec<? extends BiomeSource>`;`MapCodec` 化是 1.20.5+/NeoForge 的迁移,不适用本版)。Codec 用 `RecordCodecBuilder.create` 描述 `settings` 字段(对齐 4.4 文件2 的 `settings` 段)与内嵌 `BiomeSource` 字段。
- `@SubscribeEvent` 方法挂在 mod 事件总线(`@Mod.EventBusSubscriber(bus = Bus.MOD)`)。`RegisterEvent` 在 mod 构造之后、配置加载之前,对每个注册表各触发一次,故只能在此期注册,运行时调用会因注册表冻结抛 `IllegalStateException` 并崩服。

### 5.4 datapack 注册表对象(维度/生物群系)

状态:DECIDED

`DimensionType`、`LevelStem`、自定义 `Biome` 属 datapack(动态)注册表对象。本设计选择 jar 内置数据包 JSON 直接提供(第四章三件套 + 6.4 biome JSON),不做 Java 端 `DataPackRegistryEvent` 自建注册表(原版已有这些注册表,无需新建)。

可选数据生成(DataGen,非必须):可在 `GatherDataEvent` 用 `DatapackBuiltinEntriesProvider` + `RegistrySetBuilder` 由 Java 代码生成上述 JSON,避免手写。本设计接受手写 JSON,DataGen 标 PENDING(后续若 JSON 字段易错可引入)。

### 5.5 矿物生成机制与原版 Feature 的关系

状态:DECIDED(Major 关系澄清)

需明确区分两条矿物生成路径:

| 路径 | 机制 | 本设计是否采用 |
| --- | --- | --- |
| 原版世界生成 | `Feature` -> `ConfiguredFeature` -> `PlacedFeature`(datapack JSON,挂在 biome 的 features 列表),由区块生成阶段逐块独立放置 | 不采用(REJECTED 用于核心矿物) |
| 离线注入(本设计) | D2 预生成阶段在工作线程算好整 region 体素与矿物分布,`MiningChunkGenerator` 查表填方块(详见第八章) | 采用 |

原因:原版 `PlacedFeature` 在区块回调里逐块独立运行,与 D2"全局算法跑完再逐方块填"的离线模型冲突(无法保证跨区块连通性与确定性 D3)。因此矿物分布由离线阶段统一计算并写入体素数据,不依赖 `ConfiguredFeature/PlacedFeature`。

二者关系:本设计不为核心矿物注册任何 `Feature/ConfiguredFeature/PlacedFeature`;自定义 biome(6.4)的 features 列表中矿物相关条目应留空(或仅保留与玩法无关的纯装饰)。`PlacedFeature` 路径仅在未来需要"原版风格随机点缀"时作为补充,届时单独评估,当前标 REJECTED(用于核心矿物)/ PENDING(用于装饰)。

---

## 六、分层矿区设计

本章重写原第 4 节,消解旧设计"放弃 Y 轴分层"与"用石头/深板岩(本由 Y 决定)区分难度"的自相矛盾,并定死三区的三维摆放与区域到 biome 的映射。

### 6.1 旧设计矛盾与消解

状态:DECIDED(Major 缺口闭合)

旧第 4 节同时主张:(a) 因 Y 轴受限,改用"区域分层"取代 Y 分层;(b) Easy 用石头、Medium 加深板岩、Hard 以深板岩为主。但石头/深板岩的自然切换恰恰由世界 Y 决定(原版深板岩在 Y<0)。若区域只在 XZ 平面铺开而忽略 Y,则三区基材无法自然区分,需人工强制替换方块,既不自然也增成本。

消解方案(DECIDED):三区在单一 region 内垂直堆叠,充分利用第四章锁定的 -64..319 共 384 格 Y 预算。这样:

- 基材随世界 Y 自然切换(Hard 区落在 Y<0 深板岩带,Easy 区落在 Y>=0 石头带),无需逐方块强制替换。
- 仍保留"区域=难度"的隔离语义:难度由所在子盒(sub-box)决定,而非由玩家自由下挖的绝对 Y 决定——玩家在某难度子盒内移动,难度恒定;跨子盒移动需经垂直竖井(见 6.3),难度切换可控。
- "放弃 Y 分层"的真实诉求(不让玩家靠下挖几格就跳难度、不被 384 格上限逼到挤压)得到满足:难度边界是设计放置的子盒边界,不是裸 Y 阈值。

### 6.2 三区三维摆放

状态:DECIDED(尺寸/边界标 PENDING待校验)

在每个 region(本地坐标 0..REGION_SIZE-1,local_y 0..383)内,沿 Y 垂直切三个难度子盒,XZ 各自占满 region 全宽,中间留实心隔层(竖井穿越,见 6.3)。世界 Y 与本地 Y 关系:`worldY = local_y - 64`。

| 难度区 | local_y 区间 | worldY 区间 | 主基材(自然) | 隔层 | 备注 |
| --- | --- | --- | --- | --- | --- |
| Easy | 256..375 | 192..311 | 石头 | 下隔层 246..255 实心 | 顶部 376..383 留实心顶板(对应 has_ceiling) |
| Medium | 128..245 | 64..181 | 石头 + 深板岩过渡(Y=0 跨界由 Medium 下沿触及) | 下隔层 118..127 实心 | 过渡带视觉上石/深板岩混合 |
| Hard | 8..117 | -56..53 | 深板岩为主(Y<0 段) | 底部 0..7 实心底板 | 最深、最高密度矿物、陷阱+动态刷怪 |

说明:

- 各子盒"可雕刻"Y 区间(随机游走/噪声允许触及的 local_y)见 6.5,严格内收于上表区间,避免雕穿隔层或顶/底板。
- 基材切换不靠强制替换:`MiningChunkGenerator` 在填实心(非空洞)体素时,按 `worldY` 选基材(`worldY < 0` -> `DEEPSLATE`,否则 `STONE`),与难度子盒边界正交。因此 Hard 区天然全深板岩,Easy 区天然全石头,Medium 区横跨 Y=64..181 全在石头带(若希望 Medium 含少量深板岩,可下移 Medium 下沿至 worldY<0,PENDING待校验)。
- 上表 Y 切分为建议初值(PENDING待校验):三区各约 110-120 格垂直空间,隔层各 8-10 格。需结合关卡时长与垂直可玩性实测调整;调整时保持总和 + 隔层 = 384 且各边界对齐。

### 6.3 区间连通与跨难度竖井

状态:DECIDED

三区由实心隔层分隔,跨区移动经由离线生成阶段(D2)在隔层中打通的垂直竖井:

- 每个 region 至少保证 1 条主竖井贯穿 Hard->Medium->Easy,纳入 D4 主连通分量;玩家出生点(D4 锚点)默认置于 Easy 区,经主竖井可达 Hard。
- 竖井在 `ConnectivityFix` 阶段(D4 的 A* 打通)作为隧道处理,确保所有难度区与出生点同属主连通分量;非主分量按 D4 规则填实或打通。
- 隔层除竖井开口外恒为实心,作为 D4 BFS 的内部硬边界,防止玩家无意中跨区,使"区域=难度"语义成立。

### 6.4 区域到 biome 的映射(BiomeSource)

状态:DECIDED(Major 缺口闭合)

为复用原版 mob spawn 配置、环境效果与刷怪光照接口,三难度区各映射一个自定义 biome,由自定义 `MiningBiomeSource`(第五章注册)按本地坐标返回:

```java
// MiningBiomeSource#getNoiseBiome 内部逻辑(以 local_y 决定难度区 -> biome)
// quartPos: 参数 x,y,z 是 1/4 区块(biome 分辨率)坐标,需 << 2 还原方块坐标
int worldY = QuartPos.toBlock(y);
int localY = worldY - REGION_MIN_Y;     // 0..383
if (localY >= EASY_MIN_Y)   return easyBiomeHolder;
if (localY >= MEDIUM_MIN_Y) return mediumBiomeHolder;
return hardBiomeHolder;                 // 含 Hard 区与底板
```

要点:

- `BiomeSource` 仅依据 local_y 区间返回 biome holder,不跑跨区块算法(对齐 D2)。region 之外(缓冲带/实心墙)统一返回 `hardBiomeHolder` 或专设 `bufferBiomeHolder`(不影响玩法,因缓冲带全实心,PENDING)。
- `possibleBiomes()` 必须返回三区 biome 的全集(原版用于校验与 spawn 预计算),否则 `Holder` 解析报错。
- 三个 biome 的 JSON 路径:`src/main/resources/data/miningdim/worldgen/biome/mining_easy.json`(同理 `mining_medium.json`、`mining_hard.json`)。biome 字段重点配置:

| biome 字段 | Easy | Medium | Hard | 作用 |
| --- | --- | --- | --- | --- |
| `spawners.monster` | 空或极少 | 中等 | 由 mob 压力系统(第十章)动态接管,JSON 基线偏低 | 原版自然刷怪基线;动态压力另行叠加 |
| `features` | 仅装饰(无核心矿物,见 5.5) | 同左 | 同左 | 核心矿物走离线注入 |
| `effects.fog_color` / `sky_color` | 较亮 | 中 | 较暗 | 配合 danger 光照下降的视觉氛围 |
| `effects.music` / `ambient_sound` | 平静 | 紧张 | 危险 | 难度氛围 |
| `temperature` / `downfall` | 任意(地下无降水) | 同左 | 同左 | 仅影响渲染,设固定值即可 |

- biome 选择"自定义"而非复用原版(如 `minecraft:dripstone_caves`)的理由:需独立控制 `spawners`(给压力系统留干净基线)、`effects`(danger 视觉),复用原版会带入不需要的特性与刷怪表。复用原版 biome 标 REJECTED(对核心三区);若仅做快速原型,可临时复用,标 PENDING。

### 6.5 各区随机游走 Y 区间约束

状态:DECIDED

离线骨架阶段(D2 Skeleton:Random Walk / Room+Corridor)与 NoiseCarving 在各难度子盒内运行时,允许触及的 local_y 必须内收于子盒、不得越界(防止雕穿隔层/顶板/底板,破坏 6.3 的难度隔离与 D4 边界):

| 难度区 | 子盒 local_y | 随机游走允许 local_y | 安全内边距 | 越界处理 |
| --- | --- | --- | --- | --- |
| Easy | 256..375 | 260..371 | 上下各 4 格 | 算法 clamp,不写隔层/顶板 |
| Medium | 128..245 | 132..241 | 上下各 4 格 | 同上 |
| Hard | 8..117 | 12..113 | 上下各 4 格 | 同上 |
| 竖井 | 跨区(隔层内) | 由 ConnectivityFix 显式打通,仅限竖井 XZ 列 | 列宽 PENDING(建议 3x3) | 仅 D4 阶段写隔层,骨架阶段不写 |

约束实现:体素生成器在写空洞前对 local_y 做 `Mth.clamp` 到对应区间;隔层 local_y(118..127、246..255、0..7、376..383)在骨架/噪声阶段恒为实心,只有 D4 的 A* 竖井可在指定 XZ 列开口。安全内边距(4 格)为建议初值,PENDING待校验,需保证隔层在任何随机种子下都不被噪声边缘咬穿(若实测咬穿,增大内边距)。

---

## 七、矿洞生成系统(离线预生成模型)

本章是全文最关键的工程章节,直接回应评审标记的全文最大盲区: Minecraft 区块逐块异步独立生成模型与"随机游走 / 全局 BFS / 跨区块房间走廊"这类全局算法之间的根本冲突。本章按已锁定决策 D2(离线预生成)、D3(确定性)、D4(连通性)展开,给出从内存数据结构、三阶段管线、确定性契约、边界语义到 ChunkGenerator 查表接入与调度时序的完整可实现规格。

本章贯穿的一条铁律: 全局算法只在内存体素网格上运行,运行在工作线程,与 MC 区块系统完全解耦; MiningChunkGenerator 在区块回调里只做"世界坐标 -> region 本地坐标 -> 查 bitset -> 填方块"的纯查表操作,绝不在区块回调里运行任何跨区块算法。

### 7.1 模型选择与理由(为何必须离线预生成)

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

由于落方块阶段退化为"查表",它天然满足 MC 区块系统的全部约束: 每列只读写自身、无跨区块访问、无共享可变状态、对同一坐标永远返回同一结果。全局算法的复杂度被前移到一个不受 MC 调度约束的纯计算阶段。这是本 mod 能同时满足"全连通矿洞"与"MC 区块生成契约"的唯一可行架构,故 DECIDED。

### 7.2 数据表示与内存预算

#### 7.2.1 体素占用网格

单个实例(region)的几何用一个扁平一维布尔体素网格表示,语义为"该格是否为空气(可通行空腔)":

| 项 | 定义 | 建议初值(PENDING 待平衡校验) |
| --- | --- | --- |
| W | region 本地 X 跨度(方块) | 256 |
| H | region 本地 Y 跨度(方块) | 384 |
| D | region 本地 Z 跨度(方块) | 256 |
| 体素总数 | W * H * D | 256 * 384 * 256 = 25,165,824 |
| 存储 | `java.util.BitSet` 或 `long[]`,1 bit/格,true=air | 25,165,824 bit = 3,145,728 字节 ≈ 3.0 MiB |

索引公式(全章统一,不得改序):

```
idx = (y * D + z) * W + x        // x in [0,W), y in [0,H), z in [0,D)
```

选择 `(y, z, x)` 主序的理由: ChunkGenerator 落方块时按列(固定 x,z 遍历 y)访问,而连通性 BFS 与噪声雕刻按 y 层切片访问;`y` 作为最高维使"同一 Y 层的所有体素地址连续",利于层切片缓存局部性,同时列访问的跨步可接受。

边界与缓冲: 体素网格仅覆盖 region 的 bounding box 内部。box 外部恒为实心墙(见 7.7),不进入 bitset,不占内存。

#### 7.2.2 内存预算与上限

| 数据结构 | 单实例大小 | 说明 |
| --- | --- | --- |
| 空气占用 bitset(主网格) | ≈ 3.0 MiB | W*H*D/8 |
| 连通分量标号临时缓冲(int/格,仅 ConnectivityFix 期间存在) | ≈ 96 MiB(int[25.17M]) | 阶段结束即释放;可用 short[](≤32767 分量)降至 ≈ 48 MiB,或用第二个 bitset 仅标记"已访问"降至 3.0 MiB |
| BFS 队列(最坏全体素入队) | ≈ 50 MiB 峰值(int 索引队列) | 用环形 int 队列;见 7.7 节点上限 |
| 骨架节点图(房间 / 路径节点) | < 1 MiB | 数百到数千节点 |

内存治理决策(DECIDED):

- 主网格(1.5 MiB)在实例存活期间常驻;难度分配阶段算完后可考虑落盘缓存(SavedData / 区域文件),内存仅保留 LRU 热实例。
- 连通分量标号缓冲采用"双 bitset"实现(visited bitset + 当前分量 bitset),避免 96 MiB 的 int 标号数组,峰值额外内存压到 ≈ 6 MiB。分量体积统计在 BFS 过程中累加计数即可,不需保留每格标号。
- 全局并发预生成实例数上限 `maxConcurrentGen`(默认 PENDING=2)由 7.9 调度器强制,峰值内存 ≈ maxConcurrentGen * (主网格 + BFS 峰值)。按默认值约 2 * 9 MiB ≈ 18 MiB,可接受。
- 单实例体素维度上限 `maxVoxelDims`(默认 256x384x256)写入 `ForgeConfigSpec`,超出拒绝创建,防止 OOM(Minor 缺口"体素内存预算"由此闭环)。

### 7.3 三阶段管线

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

### 7.4 骨架算法选型表(Stage 1)

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

### 7.5 连通性修复(Stage 3)

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

### 7.6 确定性契约(D3)

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

### 7.7 bounding box 与边界语义(D4)

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
| maxBfsNodes(单次 BFS 最大访问节点) | W*H*D(= 体素总数,25.17M) | 理论上界即全网格;BFS 访问计数超过即判异常并 FAIL |
| maxTunnelLength(单条 A* 隧道最大长度) | 512 格 | 超长隧道说明分量过远,放弃打通改为填实 |
| maxStageMillis(单阶段墙钟超时) | 5000 ms / 阶段(PENDING) | 超时回退更小 box / 更简单算法(7.9) |
| BFS 队列实现 | 环形 int 索引队列,容量 = 体素总数 | 用 int 索引(非 BlockPos 对象)降内存与 GC 压力 |

由于算法在工作线程离线运行,maxStageMillis 仅作熔断,不阻塞主线程;分帧不必要(单实例整体一次算完),但超时熔断必要。

### 7.8 ChunkGenerator 查表接入

#### 7.8.1 MiningChunkGenerator 职责边界(DECIDED)

`MiningChunkGenerator extends net.minecraft.world.level.chunk.ChunkGenerator`,通过 `Codec` 注册到 `BuiltInRegistries.CHUNK_GENERATOR`(经 `RegisterEvent`,见维度章)。其唯一职责: 把世界坐标映射到 region 本地坐标,查 bitset,填 air 或 solid。绝不在任何回调里运行跨区块算法。

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

矿洞为单一 / 少数自定义 biome(按难度分层可用 3 个 biome 表征 Easy/Medium/Hard 区),用自定义 `BiomeSource`(`Codec` 注册到 `BuiltInRegistries.BIOME_SOURCE`,经 `RegisterEvent`)。`BiomeSource#getNoiseBiome(int, int, int, Climate.Sampler)` 按世界 Y / region 分层返回对应难度 biome,供光照 / 氛围 / 怪物列表(第十章压力系统)使用。biome 选择同样是查表(按 region 本地 Y 分层),无随机。

### 7.9 生成调度

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

本章定义矿物在矿山实例中的分布、权重、配额与铺设算法。所有铺矿在 D2 离线预生成阶段由 `OreGenerator` 在体素占用网格确定后、ConnectivityFix 之后执行,严格遵守 D3 确定性(同 `instanceSeed` 逐方块可复现)与 D8 线程纪律(纯计算在工作线程,落方块由 `MiningChunkGenerator` 在区块回调读铺矿表)。本章只定义"产出端"数值;经济侧的回收/销毁/产出上限与重置成本在第十八章统一收口,本章相关上限标注交叉引用。

### 8.1 设计目标与不变量

| 编号 | 不变量 | 状态 | 说明 |
| --- | --- | --- | --- |
| OG-1 | 每实例每矿物有硬上限(maxCount),铺矿计数到达上限即停 | DECIDED | 防止"刷神种":纯概率分布在长尾会偶发超高产实例,必须配额封顶 |
| OG-2 | 矿物只铺在体素网格的实心墙体素(occupied=true)且与空气可达面相邻 | DECIDED | 矿石必须紧贴可挖掘的巷道壁,埋在实心深处的矿石玩家挖不到,属浪费配额 |
| OG-3 | 铺矿在 ConnectivityFix 之后,只在主连通分量可触达壁面铺设 | DECIDED | 依赖第七章:孤岛已被填实或打通,非主分量壁面不铺矿(玩家到不了) |
| OG-4 | 同 instanceSeed 铺矿结果逐方块一致 | DECIDED | 派生 seed = hash(instanceSeed, blockX, blockY, blockZ, "ore");禁止共享可变 Random |
| OG-5 | 难度只改权重与配额,不改算法 | DECIDED | difficultyMultiplier 与 quota 表是唯一难度旋钮 |

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

铺矿表是离线产物,随 region `genState` 持久化或可由 seed 重算(D3 允许重算,但缓存避免重复计算)。`MiningChunkGenerator` 在 `fillFromNoise`/区块填充阶段对落入本区块的坐标查表替换方块,纯读,无跨区块算法(D2)。

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

### 9.1 设计不变量

| 编号 | 不变量 | 状态 | 说明 |
| --- | --- | --- | --- |
| TR-1 | 每个陷阱有玩家可感知线索(视觉/音效/粒子)且有最短反应窗口 >= reactionWindow | DECIDED | 杜绝"无预警即死";reactionWindow 下限见 9.4 |
| TR-2 | 出生点半径 SPAWN_SAFE_R 内、主干道关键节点禁布致死陷阱 | DECIDED | 与第十一章安全半径一致;关键节点 = 主连通分量 BFS 干道交叉点 |
| TR-3 | trapChance = difficulty * localRisk,值域与每区密度上限封顶 | DECIDED | 见 9.3,防止陷阱堆叠成必死走廊 |
| TR-4 | 静态陷阱在离线生成阶段确定布点(D2/D3),动态陷阱在运行期事件驱动 | DECIDED | 静态可复现;动态有节流与实例内预算 |
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

本章重写原文档第9节,严格按 D4 实现,并理顺与第七章连通性修复的依赖顺序。核心:出生点既是"安全落点",又是"主连通分量 BFS 的锚点种子"——先定出生点,再以它为种子标记主分量,再删/打通孤岛,spawn 池只从主分量合法点采样。出生后有静态安全半径 + danger 冻结期,保证玩家不会"一落地就死"。

### 11.1 出生与连通性的依赖顺序(关键)

出生点不是生成后才挑的,而是连通性算法的输入锚点。正确顺序:

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
| 属主分量 | p ∈ mainComponent | (D4 新增)可达性 |
| 可达首矿区 | 从 p 存在到最近矿区的安全通路(11.6 校验) | (新增)避免出生即困死 |

谓词实现为纯函数 `isSafeSpawn(grid, trapTable, mainComponent, p)`,离线阶段批量调用,确定性(D3)。

### 11.3 spawn pool 预生成与缓存

| 项 | 内容 |
| --- | --- |
| 生成时机 | ConnectivityFix 完成后、实例标记为 ready 前,一次性扫描 mainComponent 枚举合法站立点(11.2) |
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

所有出生相关世界写(整地、传送、放兜底平台)经 server.execute() 回主线程(D8);spawn pool 计算、安全谓词、通路校验为离线纯计算(工作线程,D2)。

### 11.9 交叉引用

入口方式(GUI/传送门/NPC/物品)、难度选择、进入前后的玩家流程在第十四章;实例分配/复用/上限在第十二章 InstanceManager(D6);连通分量与孤岛处理在第七章;出生半径内禁布陷阱与第九章 TR-2/9.5 一致;出生即冻结的 danger 与第十章评估同源。

---

## 十二、实例生命周期、并发与持久化

本章定义矿山实例从分配、引用计数、空实例回收到崩溃恢复的完整生命周期,并锁定持久化方案与并发纪律。所有内容严格遵循跨章决策 D5(持久化)、D6(实例分配)、D8(线程纪律),并补齐评审标注的多项 Critical 缺口(实例分配语义、引用计数与回收、持久化整体方案、id/seed 并发安全)。

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
| genState | enum(PENDING/GENERATING/READY/RESETTING/FAILED) | 离线生成与重置状态 | 是 |
| active | boolean(派生) | playerSet 非空即 active,控制是否 tick 压力/陷阱 | 否 |

`genState` 状态机(DECIDED):

```
PENDING --(工作线程开始体素生成)--> GENERATING
GENERATING --(三阶段完成,主线程确认)--> READY
GENERATING --(异常)--> FAILED
READY --(重置触发,实例已清空)--> RESETTING
RESETTING --(区块删除+重生成完成)--> READY
FAILED --(运维/自动重试)--> PENDING
```

仅 `genState == READY` 的实例可接受玩家传送(见第十四章入场流程的 force-load 等待门控)。`GENERATING/RESETTING` 期间分配请求进入该实例的等待队列(见 12.3 背压)。

### 12.2 实例分配语义(DECIDED,补 Critical 缺口)

`allocate(player, difficulty)` 是入场流程唯一入口,返回 `InstanceState`(或背压拒绝码)。私有与共享走不同算法,由 `mining-config.toml` 的 `instance.sharedByDefault`(默认 false)与玩家是否组队共同决定。

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

### 12.3 容量上限与背压(DECIDED,补 Major 缺口)

两级容量限制,均可配置:

| 配置项 | 默认 | 语义 |
| --- | --- | --- |
| instance.globalCap | 32 | 全 mod 同时存活实例上限(含 GENERATING) |
| instance.shareCap | 8 | 单个共享实例的最大在场人数 |
| instance.queueEnabled | true | 超全局上限时排队 vs 直接拒绝 |
| instance.queueTtlTicks | 1200(60s) | 排队请求的等待上限,超时返回失败 |

背压策略(DECIDED):

- `queueEnabled == false`:超 `globalCap` 立即返回 `BackpressureResult(GLOBAL_CAP)`,入场流程提示"矿山繁忙,请稍后"。
- `queueEnabled == true`:请求进入 `allocationQueue`(FIFO,持有 player.uuid + difficulty + enqueueTick)。每当一个实例被 GC 回收(refCount 归零销毁,见 12.6),主线程 `pollQueue()` 取队首重试分配;超 `queueTtlTicks` 的过期请求清出并通知玩家失败。
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
        return miningLevel.getDataStorage().computeIfAbsent(
            new SavedData.Factory<>(MiningSavedData::new, MiningSavedData::load),
            DATA_NAME);
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

`InstanceRecord` 序列化字段对应 12.1 表中标记"持久化=是"的列(instanceId/seed/difficulty/regionBox/playerSet/ownerKey/shared/createdTick/lastEmptyTick/genState)。`refCount/active` 不持久化,启动时由 `playerSet.size()` 重算。

`get` 必须用 `SavedData.Factory`(1.20.1 签名);任何字段修改后必须 `setDirty()`,否则不落盘(评审常见漏点,标 Major)。

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

### 12.6 引用计数、空实例 GC 与离开路径统一(DECIDED,补 Critical 缺口)

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
| instance.emptyTtlTicks | 6000(5min) | refCount 归零后保留多久再销毁 |
| instance.gcScanInterval | 200(10s) | GC 扫描周期 |

每 `gcScanInterval` 在维度 tick 末扫描:对 `playerSet.isEmpty() && lastEmptyTick >= 0 && now - lastEmptyTick >= emptyTtlTicks` 的实例执行销毁:

1. 二次确认 `playerSet.isEmpty()`(防 TTL 内有人重新进入的边界态)。
2. 文件级删除该 region 区块(走第十三章异步重置同款删除路径,绝不逐块 setBlock)。
3. `regionGrid.free(regionBox)`、`instances.remove(instanceId)`、`setDirty()`。
4. instanceId 不回收复用。

宽限期价值:玩家短暂离线/换维度往返时实例仍在,避免反复重生成开销;同时通过 TTL 防止空实例长期占用 `globalCap` 名额。

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
| genState==GENERATING/RESETTING | 上次关服时正在生成,内存态丢失 | 置 PENDING 重新触发离线生成,或直接销毁回收(空实例时) |
| genState==FAILED | 生成失败残留 | 销毁并 free region |
| regionBox 与占用位图冲突 | 数据不一致 | 记 Major 日志,以 instances 为准修正位图 |
| 区块数据存在但无对应 record | 文件残留 region | 异步删除该 region 区块文件 |

重建期间禁止任何玩家分配请求介入(此时玩家未登录,天然安全);全过程主线程串行,符合 D8。

---

## 十三、重置系统

本章重写原文档第 10 节,锁定矿山实例的重置语义。核心约束:绝不同步逐块 setBlock 删除整个 region——那会在主线程一次性卸载/重写上万区块,阻塞超过服务器 watchdog 阈值(默认 60s)直接导致 server crash(标 Critical)。重置必须文件级删除 + 异步分帧重生成,性能指标见第十九章。

### 13.1 重置类型与触发(DECIDED)

| 类型 | 触发源 | 在场玩家处置 | 倒计时广播 |
| --- | --- | --- | --- |
| 手动重置 | 命令 /mining reset \<instanceId\> 或管理员 GUI | 强制撤离 | 可选(配置) |
| 玩家请求刷新 | 玩家对自己私有实例发起 | 仅发起者及队友,需确认 | 否 |
| 定时重置 | 配置周期(如每 N 小时)或实例 createdTick 寿命到期 | 倒计时后撤离 | 是 |
| 空实例 GC 重置 | 12.6 TTL 到期销毁 | 无人(前置已空) | 否 |
| 全局重置 | 命令 /mining reset all(运维) | 逐实例串行撤离 | 是 |

手动 vs 定时区别(DECIDED):手动重置默认立即进入撤离-删除流程(可配短倒计时);定时重置必带倒计时广播且非空时按策略推迟。

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

### 13.4 异步分帧重生成流程(DECIDED,Critical 性能核心)

重置的物理执行分两阶段:区块文件级删除 + 限速重生成。全程不阻塞主线程超过单 tick 预算。

阶段一:文件级删除(DECIDED):

- 矿山实例 region 与其它实例区块不重叠(D1 缓冲带保证),故可安全按 region bbox 范围删除区块。
- 优先文件级删除:卸载 region 内区块(`ServerLevel` 解除 force ticket -> 待自然卸载或主动 `ChunkMap` 卸载),再删除对应 region 文件中的区块项,而非逐 `setBlock(AIR)`。逐块 setBlock 会触发海量光照/邻接更新与网络同步,正是卡死根因。
- 删除范围严格限定 regionBox 内区块坐标,绝不越界触碰相邻实例或缓冲带写入数据的区块。

阶段二:限速重生成(DECIDED):

- 重新触发离线生成(D2):用同一 `instanceSeed`(重置保持 seed 不变即"原样刷新",换 seed 即"全新矿洞",由 `reset(reseed: boolean)` 参数控制,见 13.5)。
- 工作线程重算体素 bitset(纯计算,D8),完成后把待生成区块投入限速队列。
- 限速队列每 tick 预算:`min(reset.maxChunksPerTick, 受单 tick <= reset.maxMillisPerTick 约束)`,默认每 tick 不超过 8 区块且生成耗时不超过 10ms,超预算顺延下一 tick。

```
ResetJob.tick():
  budgetMs = config.reset.maxMillisPerTick      // 默认 10
  count = 0
  start = nanoTime()
  while !pendingChunks.isEmpty()
        && count < config.reset.maxChunksPerTick
        && elapsedMs(start) < budgetMs:
      cpos = pendingChunks.poll()
      generateChunkFromBitset(cpos)             // MiningChunkGenerator 查 bitset 填块
      count++
  if pendingChunks.isEmpty():
      inst.genState = READY
      savedData.setDirty()
      finalizeReset(inst)                       // 重设 spawn、清 danger 残留
```

重置参数(均 PENDING 待校验,给建议初值):

| 配置项 | 建议初值 | 语义 |
| --- | --- | --- |
| reset.maxChunksPerTick | 8 | 单 tick 重生成区块上限 |
| reset.maxMillisPerTick | 10 | 单 tick 重生成耗时上限(ms) |
| reset.countdownSeconds | 30 | 定时/全局重置撤离倒计时 |
| reset.retryDelayTicks | 600(30s) | 非空 DEFER 时的重试间隔 |
| reset.deleteUseFileLevel | true | 是否走文件级删除(false 仅调试用逐块) |

注意:`maxMillisPerTick` 是软约束,单个超大区块仍可能略超;watchdog 默认 60s,本预算下单 tick 远低于阈值,从根本上规避同步删除卡死(交叉引用第十九章性能指标与压测口径)。

### 13.5 reset 接口与 reseed 语义(DECIDED)

重写原伪逻辑 `reset(): seed++ regenerate_world()`(seed++ 违反 D6,删除):

```
reset(instanceId, reseed: boolean, type: ResetType, force: boolean):
  // reseed=false: 保留 instanceSeed,删除并按原 seed 重生成(原样刷新,可复现同一矿洞)
  // reseed=true : 派生新 seed = deriveSeed(globalSeed, instanceId, ++resetGeneration)
  //               同一实例多次 reseed 用 resetGeneration 计数器派生,仍非 seed++
```

`resetGeneration` 随实例持久化,每次 reseed 自增,参与 seed 派生第三维,保证多次刷新不重复且可追溯。reseed=false 用于"再打一遍同一张图",reseed=true 用于"换新图",玩法上对应不同入口(见 14.1)。

### 13.6 全局重置(DECIDED)

`/mining reset all` 定义为逐实例串行:遍历所有实例,逐个走 13.2 流程,异步重置队列天然串行化(同一限速队列),不会并发卸载导致 IO 风暴。全局重置带统一倒计时广播,撤离所有维度内玩家。运维专用,需 OP 权限(见命令权限 14.1)。

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
  1. gateCheck(player, difficulty)        // 难度门控,见 14.4;失败提示并中止
  2. snapshotFallback(player)             // 写 Capability:prevDim/prevPos/prevGameMode
  3. inst = InstanceManager.allocate(player, difficulty)   // 12.2;背压则提示并中止
  4. if inst.genState != READY:
        showWaitingUI(player)             // "矿洞生成中…",监听 READY 回调
        awaitReady(inst, timeout)         // 超时回退,见 14.3
  5. forceLoadSpawnChunks(inst)           // 强加载 spawn 周边区块
  6. awaitChunksLoaded(inst.spawnChunks)  // 确认加载完成再传送(关键防虚空)
  7. spawn = inst.resolveSpawn()          // 主连通分量内的安全出生点(第九章/D4)
  8. player.teleportTo(miningLevel, spawn) // 主线程传送
  9. inst.playerSet.add(player.uuid); setDirty()
  10. cap.currentInstanceId = inst.instanceId
  11. initDanger(player, difficulty)      // 初始化 danger(第十章/D7)
  12. inst.active = true                   // 启动压力/陷阱 tick
```

步骤 4-6 是防掉虚空的核心:绝不在 `genState != READY` 或区块未加载时传送。

### 14.3 force-load 等待门控与竞态处理(DECIDED,Critical 防虚空)

传送前的区块就绪保证:

- `forceLoadSpawnChunks` 用 `ForgeChunkManager.forceChunk(miningLevel, "miningdim", ownerEntity, cx, cz, true, true)`(1.20.1 真实 API)对 spawn 周边 3x3 区块加 ticking ticket。
- `awaitChunksLoaded` 校验 `miningLevel.getChunkSource().hasChunk(cx, cz)` 且区块 `getStatus() == FULL`;未就绪则下一 tick 重检,最多 `enter.chunkWaitTimeoutTicks`(默认 200/10s)。
- 超时则回退:撤销 force ticket,提示玩家"矿洞加载超时",不传送,Capability 不变。

竞态处理(DECIDED):

| 竞态 | 处理 |
| --- | --- |
| 等待 READY 期间实例进入 RESETTING(被运维重置) | awaitReady 检测到 genState 变更,中止入场,回滚 force ticket,提示重试 |
| 等待期间实例被 GC(理论不会:有 pending 进入) | 入场登记会临时占位防 GC(进入等待即 playerSet 预留或 pendingEnter 标记) |
| 玩家在等待期间断线 | 取消该入场任务,撤销 force ticket,不修改 playerSet |
| 多人同时入场同一共享实例 | 各自独立走 force-load,playerSet.add 在主线程串行,无竞态 |

为防止"等待 READY 期间实例被空 GC 销毁",入场一旦 `allocate` 成功即在 `inst` 上置 `pendingEnter++`,GC 与重置均跳过 `pendingEnter > 0` 的实例;传送完成或失败回滚时 `pendingEnter--`。

### 14.4 难度解锁门控(PENDING 待校验,给建议方案)

门控校验 `gateCheck(player, difficulty)`,三选一或组合,具体阈值待平衡:

| 门控机制 | 建议规则(PENDING) | 实现 |
| --- | --- | --- |
| 等级门槛 | Easy 无门槛;Medium 需经验等级 >= 10;Hard >= 25 | 读 `player.experienceLevel` |
| 前置成就/进度 | Hard 需完成"通关 Medium 一次"自定义 advancement | 自定义 advancement criterion |
| 入场券 | Hard 需消耗 1 张 Hard 矿山券物品 | 入场时校验并消耗物品 |

建议默认:启用等级门槛(最轻量),成就与入场券作为可配开关 `gate.useAdvancement`/`gate.useTicket`(默认 false)。门控失败返回明确原因码(LEVEL_TOO_LOW/MISSING_ADVANCEMENT/NO_TICKET),GUI/命令给本地化提示。所有阈值标 PENDING 待平衡校验。

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

```java
public final class MiningNetwork {
    private static final String PROTOCOL_VERSION = "1";
    public static final SimpleChannel CHANNEL = NetworkRegistry.newSimpleChannel(
            new ResourceLocation("miningdim", "main"),
            () -> PROTOCOL_VERSION,
            PROTOCOL_VERSION::equals,   // clientAcceptedVersions
            PROTOCOL_VERSION::equals);  // serverAcceptedVersions

    private static int id = 0;
    private static int nextId() { return id++; }

    // 在 FMLCommonSetupEvent 期(enqueueWork 内)统一注册,保证 id 在两端顺序一致
    public static void register() {
        CHANNEL.registerMessage(nextId(), SelectZoneC2S.class,
                SelectZoneC2S::encode, SelectZoneC2S::decode, SelectZoneC2S::handle,
                Optional.of(NetworkDirection.PLAY_TO_SERVER));
        CHANNEL.registerMessage(nextId(), DangerSyncS2C.class,
                DangerSyncS2C::encode, DangerSyncS2C::decode, DangerSyncS2C::handle,
                Optional.of(NetworkDirection.PLAY_TO_CLIENT));
        CHANNEL.registerMessage(nextId(), TeleportResultS2C.class,
                TeleportResultS2C::encode, TeleportResultS2C::decode, TeleportResultS2C::handle,
                Optional.of(NetworkDirection.PLAY_TO_CLIENT));
        CHANNEL.registerMessage(nextId(), InstanceStatusS2C.class,
                InstanceStatusS2C::encode, InstanceStatusS2C::decode, InstanceStatusS2C::handle,
                Optional.of(NetworkDirection.PLAY_TO_CLIENT));
        // OpenMiningGuiS2C 见 15.5,优先用 NetworkHooks.openScreen 而非自定义包
    }
}
```

关键事实(1.20.1 真实存在,严禁改写):
- `registerMessage` 签名为 `registerMessage(int index, Class<MSG>, BiConsumer<MSG, FriendlyByteBuf> encoder, Function<FriendlyByteBuf, MSG> decoder, BiConsumer<MSG, Supplier<NetworkEvent.Context>> handler, Optional<NetworkDirection> direction)`。显式传入 `NetworkDirection` 用于在握手期做方向校验。
- 注册次序两端必须完全一致(id 决定线缆上的 discriminator),因此用集中式 `register()` 同一份代码两端共用。
- 注册时机:`FMLCommonSetupEvent` 内 `event.enqueueWork(MiningNetwork::register)`(线程安全窗口)。

### 15.3 Packet 总表(DECIDED)

方向缩写:C2S = 客户端到服务端;S2C = 服务端到客户端。处理线程一栏指 handler 内 `enqueueWork` 后最终执行所在的逻辑端主线程。

| Packet | 方向 | 字段 | 触发时机 | 频率 | 处理线程 |
|---|---|---|---|---|---|
| SelectZoneC2S | C2S | `difficulty:enum(EASY/MEDIUM/HARD)`, `partyJoin:boolean`, `requestedInstanceId:long(可选,-1=新建)` | 玩家在矿山 GUI 中点击"进入"按钮 | 事件驱动(单次/点击) | 服务端主线程 |
| OpenMiningGuiS2C | S2C | 见 15.5(优先 `NetworkHooks.openScreen` 走原生菜单同步,不自定义字段) | 玩家激活入口(传送门/物品/命令)请求开界面 | 事件驱动 | 客户端主线程 |
| DangerSyncS2C | S2C | `instanceId:long`, `danger:float`, `dangerMax:float`, `tier:byte(0安全/1警戒/2高危)`, `lightDimFactor:float(0~1)` | danger 评估降频周期产出新值,且与上次相比超过阈值 | 每秒(20 tick)上限,变化驱动 | 客户端主线程 |
| TeleportResultS2C | S2C | `result:enum(SUCCESS/QUEUED/REJECTED_FULL/REJECTED_GENERATING/ERROR)`, `instanceId:long`, `queuePos:int(排队位次,-1=不适用)`, `reasonKey:string(i18n key)` | 服务端处理完进入/离开传送请求后 | 事件驱动 | 客户端主线程 |
| InstanceStatusS2C | S2C | `instanceId:long`, `difficulty:byte`, `genState:enum(PENDING/GENERATING/READY/RESETTING)`, `genProgress:float(0~1)`, `playerCount:int`, `regionBoxMinX/MinZ/MaxX/MaxZ:int` | 玩家订阅某实例(进入/打开 GUI 列表),或该实例生成进度推进、状态变更 | 状态变更驱动,生成期每秒一次进度 | 客户端主线程 |

### 15.4 各 Packet 的 encode/decode/handler 职责(DECIDED)

通用约束:encode 仅做字段顺序写入 `FriendlyByteBuf`;decode 仅做对应顺序读出并构造不可变 record;handler 一律为 `ctx.enqueueWork(() -> {...}); ctx.setPacketHandled(true);` 结构,业务逻辑在 lambda 内主线程跑。枚举用 `buf.writeEnum(...)` / `buf.readEnum(Difficulty.class)`,字符串用 `writeUtf` / `readUtf`。

15.4.1 SelectZoneC2S(C2S,进入意图)

| 项 | 职责 |
|---|---|
| encode | 写 `difficulty`(enum)、`partyJoin`(boolean)、`requestedInstanceId`(long) |
| decode | 按序读回,构造 record |
| handler | enqueueWork 内:`ServerPlayer player = ctx.getSender();` 校验玩家非空、当前不在生成中、未超实例上限;委托 `InstanceManager.requestEnter(player, difficulty, partyJoin, requestedInstanceId)`;最终通过 `TeleportResultS2C` 回包。严禁信任 C2S 的任何坐标/权限字段——difficulty 之外的世界状态全部服务端重算。 |

防作弊要点:C2S 包只表达"意图",不携带传送目标坐标。出生点由服务端 `SpawnSystem` 在主连通分量中选取(D4),客户端无权指定落点。

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
| 数据包 JSON | `data/miningdim/mining_ore/*.json` | 矿物分布表(权重/配额/Y 适用) | `/reload` 重载(见 16.6) |

魔法数字治理总则:原始文档中出现的所有裸数值(trapChance、danger 各权重、baseWeight、分层 Y 边界、出生扫描参数、刷怪频率与上限、实例上限、重置成本与冷却、GC 宽限、加载半径与 TTL)全部抽到本章配置项,代码中严禁再出现同义裸常量。

### 16.2 SERVER 配置项总表(DECIDED;平衡敏感项标 PENDING待校验,但给出建议初值)

作用域列:Instance = 实例治理;Gen = 生成;Layer = 分层;Spawn = 出生;Trap = 陷阱;Danger = 压力;Mob = 刷怪;Reset = 重置;Perf = 性能/生命周期。

16.2.1 实例治理(Instance)

| 参数名 | 类型 | 默认值 | 范围 | 说明 |
|---|---|---|---|---|
| `instance.globalCap` | int | 32 | 1..256 | 全局并发实例上限(D6),超限按 `instance.overflowPolicy` 处理 |
| `instance.overflowPolicy` | enum | REJECT | REJECT/QUEUE | 超上限时拒绝进入或排队 |
| `instance.sharedByDefault` | boolean | false | - | 默认私有实例;true 则同难度共享(D6) |
| `instance.maxPartySize` | int | 4 | 1..16 | 单实例最大组队人数 |
| `instance.regionSizeChunks` | int | 16 | 4..64 | 单实例 region 边长(区块数),决定网格划分(D1) PENDING待校验 |
| `instance.bufferChunks` | int | 1 | 1..8 | region 间实心缓冲带宽度(区块),>=1(D1) |

16.2.2 分层 Y 边界(Layer)

| 参数名 | 类型 | 默认值 | 范围 | 说明 |
|---|---|---|---|---|
| `layer.easyMinY` / `layer.easyMaxY` | int | 192 / 311 | -64..320 | Easy 难度子盒 worldY 区间(与第六章 6.2 子盒一致) |
| `layer.mediumMinY` / `layer.mediumMaxY` | int | 64 / 181 | -64..320 | Medium 子盒 worldY 区间 |
| `layer.hardMinY` / `layer.hardMaxY` | int | -56 / 53 | -64..320 | Hard 子盒 worldY 区间 |
| `layer.enforceOrdering` | boolean | true | - | 启动校验三子盒 worldY 区间互不重叠且 Hard < Medium < Easy,违反则抛配置错 |

注:分层模型权威在第六章 6.2(三难度子盒垂直堆叠,非裸 Y 上界),本配置仅暴露其 worldY 边界供运维微调,改值即改 `MiningBiomeSource` 的难度分区。基材深板岩切换按 `worldY < 0`(见第六/八章)与难度子盒正交,不由本配置决定;矿物 minY/maxY(16.6)须落在对应难度子盒区间内。此边界不改变维度世界高度(min_y=-64,height=384)。

16.2.3 矿物总控(Ore;细分分布走 JSON,见 16.6)

| 参数名 | 类型 | 默认值 | 范围 | 说明 |
|---|---|---|---|---|
| `ore.baseWeight` | int | 100 | 1..10000 | 矿物基础权重基准,`weight = baseWeight * difficultyMultiplier`(原文档公式) PENDING待校验 |
| `ore.globalDensity` | double | 1.0 | 0.0..4.0 | 全局矿物密度缩放,调试/活动用 |
| `ore.useDatapackDistribution` | boolean | true | - | true 时读 JSON 分布;false 时回退到内置默认表 |

16.2.4 难度系数(Difficulty multipliers)

| 参数名 | 类型 | 默认值 | 范围 | 说明 |
|---|---|---|---|---|
| `difficulty.easyMultiplier` | double | 1.0 | 0.1..5.0 | Easy 难度系数(矿物/危险/刷怪统一乘子基线) |
| `difficulty.mediumMultiplier` | double | 1.5 | 0.1..5.0 | Medium 系数 PENDING待校验 |
| `difficulty.hardMultiplier` | double | 2.5 | 0.1..5.0 | Hard 系数 PENDING待校验 |

难度系数语义表(DECIDED):

| 难度 | difficultyMultiplier | 陷阱基率乘子 | 刷怪频率乘子 | danger 初值偏置 |
|---|---|---|---|---|
| EASY | easyMultiplier | 0(无陷阱,呼应原文档) | 0(无动态刷怪) | 0 |
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
| `reset.confirmationWindowSeconds` | int | 15 | 5..120 | 破坏性重置二次确认窗口(见 17 章) |

16.2.10 性能与生命周期(Perf / GC)

| 参数名 | 类型 | 默认值 | 范围 | 说明 |
|---|---|---|---|---|
| `perf.loadRadiusChunks` | int | 4 | 2..16 | 实例激活时强加载区块半径 PENDING待校验 |
| `perf.emptyInstanceTtlSeconds` | int | 300 | 0..86400 | 空实例存活 TTL(5min,与第十二/十九章 6000 tick 一致),超时进入 GC 候选 |
| `perf.gcGraceSeconds` | int | 120 | 0..3600 | GC 宽限期,`lastEmptyTick` 后再等该时长才回收(D6) |
| `perf.gcScanIntervalTicks` | int | 200 | 20..6000 | 孤儿/空实例扫描周期 |
| `perf.maxGenWorkers` | int | 2 | 1..8 | 离线体素生成工作线程数上限(D2/D8) |

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
| `instance.regionSizeChunks` / `instance.bufferChunks` | 是 | 改动使既有 region 网格与已存盘实例 bounding box 失配,运行时改会导致 region 重叠/坐标错位 |
| `layer.*` 子盒边界 | 是 | 已生成实例按旧边界分区/布矿,运行时改产生新旧不一致 |
| 其余平衡参数(trap/danger/mob/spawn/reset/perf) | 否 | 仅影响后续评估,可热生效 |

### 16.5 CLIENT 配置(DECIDED)

| 参数名 | 类型 | 默认值 | 说明 |
|---|---|---|---|
| `client.dangerVisualMode` | enum(OFF/HUD_ONLY/HUD_AND_SCREEN_DIM) | HUD_AND_SCREEN_DIM | 控制 15.4.2 中 `lightDimFactor` 是否驱动屏幕压暗滤镜 |
| `client.showInstanceHud` | boolean | true | 是否显示实例/danger HUD |
| `client.dangerHudScale` | double | 1.0 | HUD 缩放 |

CLIENT 配置仅影响本机渲染,不参与任何世界/平衡逻辑,因此置于 CLIENT 层。

### 16.6 矿物分布走数据包 JSON(DECIDED,呼应 16.2.3)

矿物逐难度分布不硬编码,改为数据包资源 `data/miningdim/mining_ore/<difficulty>.json`,由自定义 `ReloadableResourceManager` listener 解析,支持 `/reload` 热更。示例(`hard.json`):

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
| 分层有序 | `layer.enforceOrdering` 为真时三子盒 worldY 区间不重叠且 Hard < Medium < Easy | 抛 `IllegalStateException` 阻止进入维度 |
| danger 权重非全零 | 三权重之和 > 0 | 记警告并回退 weightZoneDifficulty=1.0 |
| 实例上限与组队 | `globalCap * maxPartySize` 给出理论玩家容量,仅日志提示 | 仅日志 |
| 缓冲带 | `bufferChunks >= 1` | spec 范围已强制,额外断言 |

### 16.8 重载语义(DECIDED)

| 配置层 | 触发方式 | 重载范围 | 限制 |
|---|---|---|---|
| SERVER TOML | `/reload`(serverconfig 在 1.20.1 随数据包重载触发 `ModConfigEvent.Reloading`) | 非 worldRestart 项即时生效;`.worldRestart()` 项需重启服务器/世界 | 标 worldRestart 的 region/layer 改动不在 `/reload` 范围内 |
| 矿物 JSON | `/reload` | 全量重载分布表 | 仅影响重载后新生成/重置的实例,既有已生成方块不动 |
| CLIENT TOML | 修改后即时(Forge 文件监听) | 渲染态即时 | 不影响服务端 |

PENDING待校验:`/reload` 对 serverconfig 的覆盖范围在不同 Forge 47.x 小版本上行为略有差异;落地时以 `ModConfigEvent.Reloading` 是否触发为准,生成/刷怪系统统一从 `*.get()` 实时读值,避免缓存导致重载不生效。

---

## 十七、命令与权限

### 17.1 框架与权限模型(DECIDED)

命令树用 Brigadier 在 `RegisterCommandsEvent` 内注册(`event.getDispatcher().register(...)`)。权限基于原版 OP 等级 `source.hasPermission(int level)`,并在编译期可选对接 Forge `PermissionAPI`(从而被 LuckPerms 等接管);二者关系见 17.5。

OP 等级语义(1.20.1 原版):0 普通玩家,1 绕过出生保护,2 多数命令方块/作弊命令,3 多人管理,4 服务器所有者级(stop/op/ban)。

### 17.2 /mining 命令树总表(DECIDED)

| 子命令 | 参数 | 权限等级 | 类别 | 作用 |
|---|---|---|---|---|
| `/mining enter <difficulty>` | difficulty:enum(easy/medium/hard) | 0 | 玩家 | 等价于 GUI 点进入:请求分配/排队并传送到实例出生点 |
| `/mining enter <difficulty> party` | + 字面量 `party` | 0 | 玩家 | 以组队私有实例进入(D6) |
| `/mining leave` | 无 | 0 | 玩家 | 离开当前实例,按 Capability 记录的进入前维度+坐标+gamemode 还原(D5) |
| `/mining status` | 无 | 0 | 玩家 | 显示自身 instanceId、难度、danger、region 信息 |
| `/mining list` | `[page:int]` | 2 | 管理 | 列出所有活跃实例(id/难度/人数/genState/createdTick) |
| `/mining tp <instanceId>` | instanceId:long | 2 | 管理 | 管理员传送到指定实例出生点(用于巡查) |
| `/mining kick <instanceId\|player>` | 目标 | 3 | 管理 | 将指定实例全部玩家或指定玩家踢回进入前位置 |
| `/mining reset <instanceId>` | instanceId:long | 4 | 破坏性 | 重置单实例 region(删/重生成该 region 区块,D1/D2) |
| `/mining reset all` | 字面量 `all` | 4 | 破坏性 | 重置全部实例(高危) |
| `/mining reset <instanceId> confirm` | + `confirm` | 4 | 破坏性 | 在确认窗口内二次确认实际执行(见 17.4) |

参数类型(DECIDED):difficulty 用自定义 `EnumArgument` 或 `StringArgumentType` + 校验;instanceId 用 `LongArgumentType.longArg(0)`;player 用 `EntityArgument.player()`。

### 17.3 命令实现要点(DECIDED)

| 命令 | 服务端职责 | 线程/权威 |
|---|---|---|
| enter | 校验 difficulty 合法 -> `InstanceManager.requestEnter` -> 成功后服务端在主连通分量选出生点传送 -> 回 `TeleportResultS2C` | 传送/生成仅服务端,呼应 D8 |
| leave | 读玩家 Capability 的进入前状态(维度/坐标/gamemode)-> `server.execute` 内传送还原 -> 清当前 instanceId | 主线程世界写 |
| status | 只读 `InstanceManager` 与 danger Capability,组装反馈文本 | 只读,可直接执行 |
| list | 只读 `InstanceManager` 快照,分页输出 | 只读 |
| reset | 校验冷却(`reset.cooldownSeconds`)与 `reset.requireEmpty` -> 进入确认窗口或直接执行 -> 工作线程重算 bitset,主线程 `server.execute` 重写区块 | 计算工作线程 / 写主线程(D2/D8) |
| kick | 遍历目标玩家,按 leave 逻辑还原其位置 | 主线程世界写 |

防滥用:enter 命令同样不接受客户端坐标,落点由 `SpawnSystem` 决定;非破坏性命令(status/list)对普通玩家开放,破坏性命令严格按等级门槛。

### 17.4 破坏性命令的确认与冷却(DECIDED)

`reset` 为不可逆操作(整 region 区块被重生成),采用"二次确认 + 冷却"双闸:

| 机制 | 规则 |
|---|---|
| 权限门槛 | OP level 4(或 PermissionAPI 节点 `miningdim.command.reset`,见 17.5) |
| 二次确认 | 首次 `/mining reset <id>` 不执行,仅在内存登记一个待确认意图(带时间戳),提示在 `reset.confirmationWindowSeconds`(默认 15s)内重发 `/mining reset <id> confirm`;超时作废 |
| 冷却 | 同一实例两次成功重置间隔须 >= `reset.cooldownSeconds`(默认 300s),未到拒绝并提示剩余秒数 |
| 在场保护 | `reset.requireEmpty=true` 时实例有玩家则拒绝;`reset.kickOnForceReset=true` 时 confirm 阶段先按 kick 逻辑清场再重置 |
| 审计日志 | 每次 reset 记录执行者、instanceId、tick 到服务端日志(Info 级),便于追责 |

`reset all` 额外要求:必须显式 `confirm`,无单实例豁免,且建议仅在维护窗口使用(仅日志提示,不强制)。

### 17.5 权限系统对接(DECIDED)

| 模式 | 实现 | 适用 |
|---|---|---|
| 原版 OP(默认) | `source.hasPermission(level)`,等级见 17.2 表 | 无权限插件的服务器,开箱即用 |
| Forge PermissionAPI(可选) | 注册 `PermissionNode<Boolean>`(如 `miningdim.command.reset`/`miningdim.command.tp`),命令判定改用 `PermissionAPI.getPermission(serverPlayer, NODE)`;LuckPerms 等通过其 Forge 桥接实现 `PermissionHandler` 即可接管 | 需要细粒度/分组授权的服务器 |

对接策略(DECIDED):命令判定封装为单一 `MiningPermissions.check(source, node, fallbackOpLevel)`,内部优先查 PermissionAPI 节点,无对应 handler 时回退 OP 等级。这样无插件环境与 LuckPerms 环境共用一套命令代码,不分叉。

权限节点表(PermissionAPI 模式):

| 节点 | 默认等级回退 | 对应命令 |
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

实例级硬配额(防 A1+A2): 每个 region 在离线预生成阶段(D2)即确定矿物体素总数,记入 `InstanceState.oreBudget`(Map<Block, int>)。玩家挖出一块即原子递减;配额耗尽后该实例不再因重置补充同类高价矿(重置仅重排布局,不放大总量)。这是经济上限的根防线。

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

### 18.7 闸门配置汇总(ForgeConfigSpec)

所有键归于 `abuse` 配置分类(SERVER 配置,见配置章)。判定全部服务端权威。

| 配置路径 | 类型 | 默认值(PENDING) |
|----------|------|------------------|
| `abuse.reset.cooldownTicks` | int [0,) | 6000 |
| `abuse.reset.costItem` | String(ResourceLocation) | minecraft:diamond |
| `abuse.reset.costAmount` | int [0,) | 2 |
| `abuse.reset.dailyLimitPerInstance` | int [0,) | 8 |
| `abuse.reset.dayMode` | enum GAME/REAL | REAL |
| `abuse.economy.daily.diamond` | int [0,) | 64 |
| `abuse.economy.daily.netherite_scrap` | int [0,) | 8 |
| `abuse.economy.decayBase` | double (0,1] | 0.97 |
| `abuse.afk.noBreakTicks` | int [0,) | 2400 |
| `abuse.afk.noMoveBlocks` | double [0,) | 4.0 |
| `abuse.reentry.cooldownTicks` | int [0,) | 1200 |
| `abuse.reentry.retainRatio` | double [0,1] | 0.8 |
| `abuse.death.reentryCooldownTicks` | int [0,) | 1200 |
| `abuse.death.dangerOnDeath` | enum | RESET_TO_ZERO |

---

## 十九、性能与容量指标

本章把"性能稳定""可重置""多实例"等定性目标量化为可压测验收的硬指标,并定义区块加载生命周期、tick 预算与异步生成池。所有指标标 PENDING待校验,需在目标硬件(基准: 4 vCore / 8GB 堆 / SSD)压测后定稿。

### 19.1 ChunkTicket / forceload 生命周期

1.20.1 强制加载有两条路径: 数据驱动的 `/forceload`(持久,经 `ForcedChunksSavedData`)与代码侧 `ServerLevel.setChunkForced` / Forge `ForgeChunkManager.forceChunk(level, modid, owner, x, z, add, ticking)`。本系统用 Forge `ForgeChunkManager`(支持 owner 与 ticking 标志,且参与 Forge 的卸载校验),不用裸 `setChunkForced`(无 owner 维度、不区分 ticking)。

| 阶段 | 触发 | 动作 | ticking 标志 |
|------|------|------|--------------|
| 激活(玩家在区) | 玩家进入 instance 或在其中移动 | 以玩家所在区块为心,按 `load.activeRadius`(建议 8)申请滑动 ticket 集合 | 半径内 `load.tickRadius`(建议 4)区块 ticking=true,其余 ticking=false(仅加载不 tick) |
| 滑动更新 | 玩家移动跨区块边界 | 增量 add 新进入区块 / remove 离开区块,维持以玩家为心的窗口 | 同上,随窗口滑动 |
| 空置 TTL | instance.playerSet 变空 | 记 `lastEmptyTick`;启动 TTL 计时 | 全部降为 ticking=false |
| 卸载释放 | `now - lastEmptyTick > load.emptyTtlTicks`(建议 6000=5min) | `forceChunk(..., add=false)` 释放该 region 全部 ticket,区块走原版卸载 | 释放 |

区分"需 tick 逻辑区"(ticking=true,跑刷怪/坍塌/岩浆/方块更新)与"仅加载区"(ticking=false,玩家可见但无主动逻辑),避免为整个 region force-tick。滑动 ticket 集合以玩家 chunkPos 为心,差量维护;多玩家共享实例时取各玩家窗口并集。

DECIDED: 后方刷怪/坍塌的作用半径必须 <= `load.tickRadius`,确保作用点落在 ticking 区块内;若机制需要在 ticking 窗口外(如远端预坍塌),必须申请短时窗 ticket(`forceChunk` ticking=true)执行后立即释放(用后即释),严禁长期 force-tick 整个 region。

### 19.2 tick 预算

单实例每 tick 主线程开销必须有硬上限。预算分配:

| 子系统 | 频率 | 硬上限 | 超限策略 |
|--------|------|--------|----------|
| danger 评估 | 每 20 tick(D7) | 每玩家 O(1) 字段计算 | 降频已是上限 |
| 动态刷怪 | 事件/周期 | 单实例存活怪 <= `mob.hardCap`(建议 30) | 达上限停刷,不排队 |
| timeSpent 累积 | 每 20 tick | 软封顶饱和(D7) | 到饱和值停增 |
| 坍塌 | 事件触发 | 每 tick 替换方块数 <= `collapse.maxBlocksPerTick`(建议 64) | 优先直接 `setBlock` 替换(不生成 FallingBlockEntity);超量分帧到后续 tick |
| 岩浆 | 事件触发 | 流体更新限制在半径 `lava.maxSpreadRadius`(建议 6)内 | 超出范围不调度流体 tick |

坍塌实现 DECIDED: 默认用 `level.setBlock` 直接把悬空方块替换为对应坠落态/空气,而非批量 spawn `FallingBlockEntity`(实体数爆炸 + 物理 tick 开销)。仅在视觉关键点(玩家正前方小范围)按 `collapse.visualFallingBudget`(建议 8)生成少量真实 FallingBlock 做表现。岩浆喷发 DECIDED: 用有限步数的预定义流体填充而非依赖原版流体无限扩散,半径硬限。

后方刷怪 DECIDED: 生成点必须在玩家 entity-ticking 范围(`load.tickRadius` 内)且通过 spawn 安全校验(见第九章);若候选点在 ticking 窗口外则放弃该次刷怪,不申请额外 ticket(刷怪非关键,从简)。

### 19.3 多实例内存 / 磁盘

| 资源 | 量化口径 | 上限(PENDING) | 控制手段 |
|------|----------|----------------|----------|
| 内存(占用体素 bitset) | region 体素数 / 8 字节 | 单实例 bitset <= `gen.maxRegionVoxels`/8 | region bounding box 量化;典型 256x384x256 region ≈ 25.2M voxel ≈ 3.0 MiB bitset |
| 内存(InstanceState) | 每实例元数据 | 常数级(<1KB,不含 bitset) | bitset 生成完成且区块落盘后可释放,仅保留 seed 可重算 |
| 磁盘(region 区块) | region 占用的 .mca 区域 | 单实例 <= `disk.maxRegionMB`(建议 64MB) | 单维度内 region 分区量化;销毁实例同步删该 region 区块(`ChunkStorage` 删除 + force unload) |
| 全服并发实例 | 活跃 InstanceState 数 | <= `instance.globalCap`(建议 32) | 超限拒绝/排队(见 18.2 与 19.4) |

DECIDED: bitset 在区块全部落盘后释放,InstanceState 仅保留 `seed`+`regionBox`+`genState`,需要时由 seed 确定性重算(D3),不常驻全 region 体素。销毁实例时: 1) 踢出残留玩家(见第二十章);2) 释放 force ticket;3) 删除 region 覆盖的区块数据;4) 从 SavedData 移除 InstanceState。

### 19.4 异步生成与预生成实例池

DECIDED(D2): 离线预生成。体素生成(Skeleton/NoiseCarving/ConnectivityFix)与 BFS 全在工作线程(D8),仅最终 `setBlock` 回主线程(`server.execute`)分帧提交。

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
| 全服并发实例数 | >= 16 稳定运行 | 16 实例各 1 玩家持续活动 30 min | 主世界 TPS >= 19 PASS |
| 单实例内存(常驻) | <= 1MB(释放 bitset 后) | 堆 dump 统计 InstanceState 保留集 | PASS/FAIL |
| 单实例峰值内存(生成中) | <= 12MB(含 bitset) | 生成期采样 | PASS/FAIL |
| 单实例磁盘 | <= 64MB | region .mca 体积统计 | PASS/FAIL |
| 单实例离线生成 P99 延迟 | <= 1500 ms | 100 次生成计时取 P99 | PASS/FAIL |
| 重置期最低主世界 TPS | >= 18 | 4 实例并发重置时采样 | PASS/FAIL |
| 全服实体上限 | 活跃怪总数 <= 实例数 x `mob.hardCap` | 实体计数 | PASS/FAIL |
| 出池进入等待 | 池非空时 <= 50 ms | 进入请求计时 | PASS/FAIL |

---

## 二十、错误处理与边界情况

本章逐场景定义处理策略、日志级别与玩家可见提示文案。遵循 CLAUDE.md 异常纪律: 业务层异常自然冒泡,仅在最外层(命令 Controller / 网络 handler / 进入流程 Gateway)统一兜底;不在业务函数内 try/catch 生吞。本章描述的"兜底"均指最外层兜底或确定性降级路径(非吞异常)。

### 20.1 错误处理总则

| 原则 | 说明 |
|------|------|
| 异常冒泡 | 体素生成、BFS、配额扣减等业务函数遇非法状态直接抛,不本地 catch |
| 最外层兜底 | 进入流程 Gateway、命令 handler、网络 packet handler 各设一个 try/catch,记 ERROR 日志并向玩家回友好文案,绝不让异常崩服 |
| 确定性降级 | 算法层"失败"(如连通性未达标)不是异常,而是返回降级结果(见 20.2),走预定义 fallback |
| 玩家文案 | 所有拒绝/失败经网络下发可本地化文案(translation key `miningdim.msg.*`),不暴露堆栈 |
| 日志规范 | 保留诊断日志(CLAUDE.md);级别见各场景表 |

### 20.2 逐场景处理

| 场景 | 触发条件 | 处理策略 | 日志级别 | 玩家文案(translation key) |
|------|----------|----------|----------|----------------------------|
| 连通性修复后仍不连通 | ConnectivityFix 后主连通分量占比 < `gen.minConnectedRatio`(建议 0.98) | 1) 重试: 用 `hash(seed, retryN)` 派生新子 seed 重跑 NoiseCarving+Fix,最多 `gen.maxRetries`(建议 3)次;2) 降级: 仍失败则强制把所有非主分量空气填实(牺牲体积换 100% 连通);3) 兜底: 降级后主分量体积 < `gen.minVolume` 则该 seed 标 BROKEN,换 instanceId 派生 seed 重生成 | WARN(重试)/ ERROR(降级+换 seed) | `miningdim.msg.gen_retry`(静默或调试可见) |
| 扫遍找不到安全 spawn | spawn 候选扫描(第九章)在主连通分量内无满足安全判定的点 | 强制 fallback: 在主分量锚点(出生点)处铲平建 3x3x3 安全平台(脚下 3x3 固体、头顶 2 格空气、周围清岩浆),该平台坐标登记为 spawn | WARN | 无(对玩家透明,正常出生) |
| 实例池满 / 并发上限 | 活跃实例数 >= `instance.globalCap` 且无空闲池 | 1) 默认: 拒绝并提示;2) 可配 `instance.onFull=QUEUE` 时入队,队首实例释放后出队进入;3) 可配 `instance.dynamicCap` 允许临时超限(受内存看门狗约束) | INFO(拒绝)/ WARN(队列超长) | `miningdim.msg.instances_full`("矿区已满,请稍后再试 / 已加入排队第 N 位") |
| 区块异步生成未完成就传送 | 玩家进入请求时 `genState != READY` 或目标区块未 force-load 完成 | 传送前必须等待: 进入流程置玩家于"加载中"状态(锁定输入/显示加载提示),轮询 `genState==READY` 且目标 chunk 已加载后再 `teleportTo`;超 `enter.timeoutTicks`(建议 200)未就绪则取消进入并退款(若已扣重置/进入成本) | INFO(正常等待)/ ERROR(超时) | `miningdim.msg.preparing`("矿区生成中...") / `miningdim.msg.enter_timeout`("矿区准备超时,已退还费用") |
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

测试分三层: 纯算法 JUnit 单测(工作线程可独立运行的体素/图算法)、Forge GameTest 集成测试(维度/生成/传送等需 server 环境)、性能基准测试。遵循 CLAUDE.md: 断言具体业务结果,严禁 `is not None` 类弱校验(判据: 删掉被测核心逻辑测试必须 FAIL);测试数据含边界值与随机化。

### 21.1 纯算法单元测试(JUnit 5)

体素生成与图算法不依赖 MC server,可纯 JVM 跑,是测试主战场。

| 测试 | 断言(具体业务结果) | 边界/随机化 |
|------|---------------------|-------------|
| 连通性 100% | 生成后对主连通分量 BFS,断言 `可达空气体素 / 总空气体素 == 1.0`(降级路径下亦必为 1.0) | 100 个随机 seed,覆盖最小/最大 region 尺寸 |
| 确定性可复现 | 同 instanceSeed 跑两次,断言两次 bitset 逐位相等(D3) | 随机 50 个 seed,各跑两遍比对 |
| 矿物权重分布 | 大样本(10^5 次抽样)统计各矿物占比,断言落在期望区间 `expected +- 3*stderr`(卡方检验 p>0.05) | 三难度各测;含权重为 0 的矿物必不出现 |
| danger 公式边界 | `danger=f(zoneDifficulty,timeSpent,oreRichness)`: 断言封顶 `f(...)==DANGER_MAX`(超大入参)、衰减单调、离区衰减后值精确等于公式值(D7) | timeSpent=0 / 饱和 / 超饱和;负输入应抛或钳制 |
| trapChance 公式 | `trapChance=difficulty*localRisk`: 断言 difficulty=0 -> 0、上限钳制到 [0,1]、单调 | 边界 0 / 1 / 越界输入 |
| 安全 spawn 判定 | 构造头顶2格空气+脚下固体+无岩浆点判 PASS;缺任一条件判 FAIL(逐条件翻转用例) | 每个安全条件单独失效一次,断言对应 FAIL |
| 配额递减 | oreBudget 并发递减 N 次后断言精确等于 `initial-N` 且不为负 | 多线程并发递减压测原子性 |
| danger 重入冷却(18.5) | 模拟离区->冷却内重入,断言 danger == `max(衰减值, 离开值*retainRatio)`;冷却外重入断言 == 衰减值 | 冷却边界 tick±1 |

DECIDED: "矿洞 100% 连通"做成生成后置自动断言(`assertFullyConnected(bitset, regionBox)`),每次生成(含生产路径,debug 配置开启时)后调用;CI 必跑。

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
| 单实例离线生成耗时 | P99 <= 1500 ms 且生成期间主线程 tick 时间无 > 50ms 尖峰 | 工作线程计时 + 主线程 tickTime 采样 |
| 重置期主世界 TPS | >= 18(见 19.5) | 4 实例并发重置,采样主世界 MSPT |
| 并发实例 TPS | 16 实例活动 TPS >= 19 | 见 19.5 压测脚本 |
| 出池进入延迟 | 池非空 <= 50 ms | 进入请求计时 |

DECIDED: 性能基准断言"异步不卡主线程"——具体化为"生成期间主线程单 tick 时间不超过 50ms(20 TPS 阈值)",而非笼统"流畅";违反即 FAIL。

### 21.4 测试覆盖与门禁

| 门禁 | 要求 |
|------|------|
| 边界覆盖 | 必须覆盖: 找不到 spawn(走 3x3 平台 fallback)、实例满(拒绝/排队)、连通性降级、重置时有玩家、扣费失败 |
| CI 必跑 | JUnit 全量 + GameTest 全量 + 连通性 100% 断言;任一 FAIL 阻断合并 |
| 弱校验禁止 | 评审拒绝 `assertNotNull` 作为唯一断言的测试;每个测试必须有"删核心逻辑则 FAIL"的强断言 |
| 随机种子留存 | 随机化测试失败时打印触发 seed,保证可复现(对应 D3 确定性) |

---

## 二十二、实现路线图与风险登记

### 22.1 编码前必做 Spike(技术验证)

正式里程碑前先做三个 Spike 消解最大不确定性。Spike 只求验证可行性,代码可抛弃。

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
| M2 维度与生成器 | dimension_type/level_stem JSON 数据包、MiningChunkGenerator + BiomeSource(Codec, RegisterEvent) | miningdim:mining 维度可进,按 region 查 bitset 填方块 | M1(SP1,SP2) | 是 | 与 M3 部分并行 |
| M3 离线生成核心 | Skeleton/NoiseCarving/ConnectivityFix + BFS + 分帧提交 + JUnit 连通性/确定性断言 | 100% 连通生成,JUnit 全绿 | M1(SP2) | 是 | 与 M2 并行(纯算法不依赖维度) |
| M4 实例管理与持久化 | InstanceManager + SavedData(实例注册表/计数器)+ 玩家 Capability(prior 数据/instanceId/danger)+ 启动重建/孤儿清理 | 实例增删查持久化,重启后恢复 | M2 | 是 | 与 M5 部分并行 |
| M5 出生与传送 | spawn 扫描 + 3x3 fallback + 进入流程 Gateway(等 force-load)+ ChunkTicket 生命周期 | 安全出生、传送前生成完成、TTL 卸载 | M2,M4 | 是 | - |
| M6 矿区分层与矿物 | 三难度 region 分层、矿物权重分布、oreBudget 配额 | 矿物分布落期望区间(JUnit) | M3 | 否 | 与 M7 并行 |
| M7 陷阱与压力系统 | 静态/动态陷阱、danger 模型(封顶+衰减)、动态刷怪(hardCap)、坍塌(分帧 setBlock)、岩浆限流 | danger 公式 JUnit 绿、刷怪不超 hardCap | M5 | 否 | 与 M6 并行 |
| M8 反滥用经济闸门 | 重置冷却/成本/每日上限、产出软上限、AFK、重入冷却、死亡惩罚(第十八章) | 各闸门 JUnit + GameTest 绿 | M4,M7 | 否 | - |
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
| R3 | 连通性与性能权衡: 保证 100% 连通的 A* 打通/填实代价过高拖慢生成 | High | Medium | 连通性作最后闸(D4);小岛直接填实(minIslandSize)只对大岛 A*;分帧提交;生成 P99 门槛把关 | SP2 / M3 / M9 |
| R4 | 多实例并发离线生成线程安全(共享可变状态/与主线程竞态) | High | Medium | 每任务独立 RandomSource(D3);世界写全经 server.execute(D8);InstanceState 用 ConcurrentHashMap,genState 仅主线程改;maxConcurrentGen 限流 | SP2 / M3 / M4 |
| R5 | ChunkTicket 泄漏(ticket 未释放致区块常驻、内存/磁盘膨胀) | High | Medium | ForgeChunkManager 带 owner(modid)统一管理;空置 TTL 释放;销毁实例强制 forceChunk(add=false);GameTest 断言 TTL 后卸载 | M5 / M9 |
| R6 | 传送到未生成区块导致掉虚空/卡死 | High | Medium | 进入流程强制等 genState==READY 且 chunk FULL 才传送;超时退款取消(20.2) | M5 / M11 |
| R7 | 重置时玩家在区导致数据损坏/卡死 | Medium | Medium | reset 前强制踢回 priorPos,确认 playerSet 空才删区块(20.2) | M11 |
| R8 | 经济闸门数值失衡(过严劝退/过松失防) | Medium | High | 全闸门可配 + 默认保守;数值标 PENDING 留压测调参;接收社区反馈迭代 | M8 / 上线后 |
| R9 | 离线生成内存峰值超标(大 region bitset 常驻) | Medium | Medium | bitset 落盘后释放(D5),仅留 seed 重算;maxRegionVoxels 量化;内存门槛把关 | M3 / M9 |
| R10 | parchment/ForgeGradle 6 环境与 1.20.1 API 漂移 | Low | Low | 锁定 Forge 47.x + 固定 parchment 版本;M0 即验证编译;API 用前核实 | M0 |

---

## 二十三、总体设计定位

本系统的总体定位: 基于独立维度 region 分区的分层随机矿洞 Roguelike 副本系统(instanced layered roguelike mining dungeon)。它把"可重复刷新的随机矿洞副本"落实为以下确定的工程形态:

| 维度 | 定位 | 实现支撑 |
|------|------|----------|
| dungeon-like mining | 矿洞即副本: 每实例是一座有出生点、连通主干、风险分层的地下副本 | region 分区(D1)+ 骨架生成 + 连通性保证(D4) |
| procedural generation | 程序化生成: 三阶段离线算法(Skeleton->NoiseCarving->ConnectivityFix)产出结构 | 离线预生成(D2)+ 确定性 seed(D3) |
| risk-reward | 风险收益对价: 难度分层 + danger 动态压力 + 陷阱,高风险对应高价矿物 | 三难度区 + danger 封顶衰减(D7)+ 经济闸门(第十八章) |
| replayable instances | 可重复实例: 同维度内多 region 多实例,可重置刷新布局 | 实例管理(D6)+ 重置(region 区块重生成)+ 预生成池 |

与原始定位(原文第 12 节)的差异与确定化:

| 项 | 原始表述 | 本设计确定化 |
|----|----------|--------------|
| 维度模型 | "推荐独立维度或实例化" | DECIDED: 单静态维度 miningdim:mining + region 分区(D1),否决运行时动态维度(REJECTED) |
| 分层 | "因 Y 轴限制用区域分层" | DECIDED: region 内水平分三难度区,不依赖 Y 轴 |
| 生成 | "三阶段骨架+连通+噪声" | DECIDED: 三阶段离线预生成(D2),连通性作最后一道闸(D4) |
| 重复性 | "整体重置 seed++ regenerate" | DECIDED: 重置 = 单 region 区块重生成(非 seed++,seed 由 instanceId 派生,D6) |

定位边界(非目标): 本系统不是开放世界探索(region 有界)、不是 PvP 竞技场(默认私有实例)、不追求无限并发实例(受 globalCap 约束,以池化与重置复用代偿)。

---

## 二十四、总结

本设计文档把"服务器内可重复刷新的随机矿洞副本"从玩法构想落实为锁定 Forge 1.20.1 的可交付实现规格。核心玩法意图全部保留: 随机生成矿洞、矿道完全连通、多难度分层、矿物与难度挂钩、高难度带陷阱与动态压力、随机安全出生、整体重置、独立维度。

### 24.1 核心需求落实清单

[x] 随机生成矿洞结构(离线三阶段算法 Skeleton/NoiseCarving/ConnectivityFix,D2)
[x] 矿道完全连通(连通性作最后一道闸,BFS 6-邻接,100% 可达断言,D4)
[x] 多难度分层矿区(region 内水平分 Easy/Medium/Hard 三区,不依赖 Y 轴)
[x] 矿物与难度挂钩(权重分布 + difficultyMultiplier + oreBudget 硬配额)
[x] 高难度陷阱与动态压力(静态/动态陷阱 + danger 封顶衰减模型,D7)
[x] 玩家随机安全出生(安全判定 + spawn pool + 3x3 fallback)
[x] 支持整体重置(单 region 区块重生成,异步化,踢人保护)
[x] 推荐独立维度(单静态维度 miningdim:mining + region 分区,D1)

### 24.2 关键设计决策清单(含本轮新增)

[x] 锁定 Forge 1.20.1 + Forge 47.x + Java 17 + ForgeGradle 6 + parchment(全 API 以此版本为准)
[x] 单维度 region 分区代替运行时动态维度(D1;运行时动态维度 REJECTED,因 1.20.1 注册表启动后冻结)
[x] 离线预生成代替运行时全局算法(D2;规避 MC 区块异步独立生成与全局算法冲突)
[x] 确定性 seed 持久化(D3;同 seed 逐方块可复现,分块随机用 hash 派生)
[x] 连通性作最后一道闸 + BFS 边界即 region bbox(D4;主分量锚点=出生点,小岛填实大岛 A* 打通)
[x] SavedData + Capability 持久化(D5;实例注册表用 SavedData,玩家数据用 Forge Capability + PlayerEvent.Clone)
[x] 实例分配模型(D6;instanceId 自增 long,seed 由全局种子+instanceId 派生,globalCap 限并发)
[x] danger 封顶 + 衰减(D7;DANGER_MAX 封顶,timeSpent 软封顶收敛,降频评估每 20 tick)
[x] 线程纪律(D8;世界写经 server.execute 回主线程,纯计算在工作线程)
[x] 经济闸门防刷(第十八章;重置冷却/成本/每日上限、产出软上限、AFK、重入冷却、死亡惩罚)
[x] ChunkTicket 生命周期(第十九章;ForgeChunkManager 滑动 ticket + 空置 TTL 释放 + 销毁同步删 region)
[x] 异步生成池与量化容量指标(第十九章;warm 池/模板缓存/并发限流 + 压测验收门槛)
[x] 错误处理与边界兜底(第二十章;连通失败降级、spawn fallback、池满、传送等待、重置踢人)
[x] 完整测试策略(第二十一章;JUnit 算法断言 + Forge GameTest 集成 + 性能基准 + CI 门禁)
[x] 实现路线图与风险登记(第二十二章;Spike 先行 + M0-M11 里程碑 + R1-R10 风险表)

### 24.3 交付边界声明

本文档为实现规格,所有标注 PENDING待校验 的平衡数值(冷却/成本/上限/danger 系数/性能门槛初值)均给出建议初值,须在目标硬件压测与玩法测试后定稿;所有 Forge/MC API 名以 1.20.1 + Forge 47.x 为准,实现前按 CLAUDE.md 纪律逐一核实,不得套用其他版本语法。标注 DECIDED 的跨章设计决策(D1-D8 及本章列出项)为架构基线,不得在实现期擅自另立方案;如需变更须走方案复审。
