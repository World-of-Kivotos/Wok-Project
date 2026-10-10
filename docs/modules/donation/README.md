# WOK-捐赠箱模块

模块键：`wok-donation`
技术 modId：`miningdim`
公开入口：`com.miningdim.donation.DonationBoxModule`
设计规格：[docs/DonationBox_DesignSpec.md](../../DonationBox_DesignSpec.md)（定位、规则、权限、白名单、流水与已知限制；本文件是模块交付清单，管注册 ID、存档键、资源归属与验证入口）

当前状态：2026-09-27 在 `feat/donation-box` 新建，尚未合入 main。模块一出生就用独立的 `*Module` 装配入口，按 [`../README.md`](../README.md)「文档所有权与子 README」判定规则第 1 条，本文件随模块一起交付。

## 1. 功能边界

本模块负责：

- 捐赠箱方块、方块实体与两种界面：捐赠者的 3x3 投入格，箱主/协管/OP 的 54 格仓库与账本页。
- 可捐物品白名单：只收 `BlockItem`、带 NBT 一律拒收、创造限定方块硬拒、数据驱动拒收标签 `miningdim:donation_box_denied`。
- 箱主、协管（至多 8 人）、OP 三级权限，破坏保护，OP 转让箱主。
- 存取流水（方块实体内环形 300 条 + 每人累计）与独立审计 logger `miningdim/donation`。
- `/donationbox` 指令与账本页的两个 C2S 网络包（按玩家限速）。

本模块不负责：

- 任何回报。不发信用点、经验或贡献值，因此不依赖 `wok-economy` 与 `wok-experience`，也不进收支总表。
- 领地判定。与 Flan 等领地模组没有联动。

## 2. 当前所有权

### Java 源码

- `src/main/java/com/miningdim/donation/`；客户端界面在 `donation/client`，只经 `DistExecutor` 双箭头调入，专用服务器不触任何客户端类。
- 登记表 `javaPackagePrefixes: ["com.miningdim.donation"]`，`gradlew verifyModuleBoundaries` 逐文件核对归属。
- 文件数与 GameTest 数不在本文钉死，当期实数见 [`../INVENTORY.md`](../INVENTORY.md)。

### 专属资源

- `assets/miningdim/blockstates/donation_box.json`
- `assets/miningdim/models/block/donation_box.json`、`assets/miningdim/models/item/donation_box.json`
- `data/miningdim/loot_tables/blocks/donation_box.json`
- `data/miningdim/recipes/donation_box.json`
- `data/miningdim/tags/items/donation_box_denied.json`
- `assets/miningdim/lang/en_us.json` 与 `zh_cn.json` 中 `*.miningdim.donation_box.*` 键。

登记表口径：`resourcePaths` 为空，上面的文件全部靠 `resourceNamePrefixes` 的 `donation_box` 按文件名前缀认领；两个语言文件登记在 `sharedResources`，提交只能动 `donation_box` 段的键。全部资源手写，没有 datagen 产物。模型与界面复用原版贴图（木桶、堆肥桶、发射器、大箱子），没有独占纹理。没有加入 `minecraft:mineable/axe`：该原版标签文件由电力模块的 datagen 生成，手写同路径文件会与之冲突。

## 3. 冻结的兼容身份

合入 main 之后不得修改：

| 类别 | ID |
| --- | --- |
| 方块、方块物品、方块实体 | `miningdim:donation_box` |
| 菜单 | `miningdim:donation_box_donor`、`miningdim:donation_box_manager`（登记在共享的 `ModMenus.MENUS`；extraData 首读 `BlockPos`） |
| 物品标签 | `miningdim:donation_box_denied` |
| 网络频道 | `miningdim:donation`，协议版本 `1`；discriminator `0` = `ViewRequest`（C2S）、`1` = `CoAdminEdit`（C2S）、`2` = `ViewSync`（S2C） |
| 指令根 | `/donationbox` |
| 审计 logger | `miningdim/donation` |

协议版本 `1` 从未发布过，本分支合入前对包格式的调整不升版本；合入 main 之后，任何包格式改动都必须同时升协议版本。

### 方块实体 NBT 键

改名等于清档，第一阶段一律冻结：

| 位置 | 键 |
| --- | --- |
| 根 | `Storage`（54 格 `ItemStackHandler` 序列化）、`Ledger`、`OwnerId`（UUID，无箱主时缺省）、`OwnerName`、`CoAdmins`（列表，每行 `Id` + `Name`） |
| `Ledger` | `Entries`、`Totals`、`AutomationDeposited`、`PendingAutomation`、`PendingSince`（游戏刻，窗口未开时缺省） |
| `Ledger.Entries` 每行 | `Time`（现实毫秒）、`Actor`（UUID，自动化输入缺省）、`Name`、`Action`（`DEPOSIT`/`WITHDRAW`）、`Item`、`Count` |
| `Ledger.Totals` 每行 | `Actor`、`Name`、`Deposited`、`Withdrawn` |
| `Ledger.PendingAutomation` 每行 | `Item`、`Count` |

区块同步只带 `OwnerId`、`OwnerName`；仓库、流水与协管名单从不进区块包，`saveToItem` 也不写进物品。

## 4. 依赖登记

### 必需依赖

| 模块 | 使用内容 |
| --- | --- |
| `wok-core` | `Subsystem`、`MiningConstants.MODID`、`ModMenus`（共享 MenuType 登记与 `blockMenuType`）、`AbstractMiningMenu`、`MenuValidity`、`ModCreativeTabs.MINING_TAB` |

被 `wok-app` 依赖（`MiningDim` 装配本模块）。

### 外部模组

没有可选联动，也不 import 任何外部模组的类。以下交互是按原版/Forge 契约设计的防御，不是联动：

| 外部 MOD | 交互 | 处置 |
| --- | --- | --- |
| 只按硬度判定的破坏类模组 | 不经过 `BreakEvent`，只按硬度决定是否移除方块 | 方块硬度 -1，这类路径视为不可破坏 |
| Jade、The One Probe | 以空面读取物品能力并把内容发给看向方块的玩家 | 空面不暴露物品能力 |

## 5. 配置

不注册配置文件。协管上限 8、流水环形 300 条、自动化聚合窗口 6000 tick、网络包限速（账本请求 5 tick、协管增删 10 tick）都是代码常量，数值与理由见设计规格。

## 6. 验证清单

GameTest 持有者 `DonationBoxGameTests`，batch `donation`；用例清单与各自覆盖的规则见设计规格「验证」一节。

每次整理提交至少执行：

1. `gradlew.bat compileJava verifyModuleBoundaries`
2. `gradlew.bat runGameTestServer`，以 `run/logs/latest.log` 末尾的 `All N required tests passed` 为准，不信 gradle 退出码
3. 对冻结 ID 做文本回归搜索：`donation_box`、`donation_box_donor`、`donation_box_manager`、`donation_box_denied`、`miningdim:donation`
4. 三个界面（捐赠界面、管理界面、账本页）至今只经编译与服务端 GameTest 验证，改动界面后要实机目测
