# 捐赠箱设计规格

状态：DECIDED（2026-09-27 在 `feat/donation-box` 落地，尚未合入 main，客户端界面尚未实机目测）

| 项 | 值 |
|---|---|
| 模块键 | `wok-donation`（玩法类，只依赖 `wok-core`） |
| 代码包 | `com.miningdim.donation`（客户端界面在 `donation/client`） |
| 装配入口 | `DonationBoxModule`（模块交付清单：注册 ID、存档键、资源归属与验证入口见 [modules/donation/README.md](modules/donation/README.md)） |
| 注册 ID | 方块/物品/方块实体 `miningdim:donation_box`；界面 `miningdim:donation_box_donor`、`miningdim:donation_box_manager` |
| 网络 | 模块自有 SimpleChannel `miningdim:donation`（协议版本 1） |
| 资源前缀 | `donation_box*`；语言键 `*.miningdim.donation_box.*` |
| 测试 | `DonationBoxGameTests`，batch `donation`，24 条 |

## 一、定位

学院自管区由自管区管理统筹、玩家自费建设。信用点不能直接转账，而物品在 MC 里本来就可以自由转移；捐赠箱不创造新的转移能力，只是给"把建材交给自管区"这件事一个固定入口：只进不出、只收建材、全程有账可查。

**不发任何回报**：不给信用点、经验或贡献值，不接经济模块。它既不是 faucet 也不是 sink，因此不经过[经济收支总表](Economy_BalanceSheet_DesignSpec.md)登记。与 Flan 领地模组没有联动。

## 二、方块

| 项 | 规格 |
|---|---|
| 朝向 | 水平四向，正面朝向放置者 |
| 硬度 / 抗爆 | 方块属性 -1（与基岩同口径，即"不可破坏"）；箱主与 OP 按名义硬度 2.5（与木桶同）计算挖掘进度 / 1200（黑曜石量级） |
| 活塞 | 推不动（`PushReaction.BLOCK`） |
| 实体破坏 | 凋灵、末影龙等一律无效（`canEntityDestroy` 恒假）；末影人只搬 `enderman_holdable` 标签里的方块 |
| 爆炸 | 抗性挡住常规爆炸；即使某个爆炸把它列进了待炸名单，也既不移除（`onBlockExploded` 不动作）也不掉落（`canDropFromExplosion` 恒假） |
| 掉落 | 战利品表只掉一个普通箱子物品，不带任何内容物；仓库内容在方块被移除时散落在原地 |
| 指令替换 | 方块实体实现原版 `Clearable`，与原版箱子同义：`/clone ... move` 是纯移动（仓库随箱走，源位置不散落）；`/setblock`、`/fill` 的 replace 模式与结构放置直接清空仓库而不散落；`/setblock ... destroy` 走正常移除，仓库散落 |
| 配方 | 有序：`木板 漏斗 木板 / 木板 箱子 木板 / 木板 木板 木板`（木板为 `#minecraft:planks`） |
| 创造物品栏 | 原版"功能方块"页与本 mod 主页 |
| 模型 | 全部引用原版贴图：顶面 `barrel_top_open`，正面 `composter_side`，侧面 `barrel_side`，底面 `barrel_bottom` |

硬度写成 -1 是为了挡住只看硬度的非玩家破坏路径：这类路径不经过 `BreakEvent`，也不问 `canEntityDestroy`，硬度为负时把方块视为不可破坏；而方块被移除时仓库会整仓散落，`onRemove` 又无法否决移除，所以只能在硬度这一层挡住。不查硬度就直接 `destroyBlock` 的模组仍然挡不住（见第九节）。

没有加入 `minecraft:mineable/axe`：该原版标签文件已由电力模块的 datagen 生成在 `src/generated`，在主资源里手写同路径文件会触发 `processResources` 的资源路径冲突。后果只是斧头不加速，箱主徒手约 4 秒可拆。

## 三、身份与权限

所有判定都在服务端。客户端只经区块同步拿到箱主身份，用于让无权者的挖掘进度保持为 0。

| 动作 | 箱主 | 协管 | OP（权限等级 >= 2） | 其他玩家 |
|---|---|---|---|---|
| 打开捐赠界面放入 | 否（直接进管理界面） | 否（同左） | 否（同左） | 是 |
| 打开管理界面存取仓库 | 是 | 是 | 是 | 否 |
| 查看流水、累计、协管名单 | 是 | 是 | 是 | 否 |
| 增删协管 | 是 | 否 | 是 | 否 |
| 拆除箱子 | 是 | 否 | 是 | 否 |
| 转让箱主 | 否 | 否 | 是（指令） | 否 |

- **箱主**：放置者（UUID + 名字）。非玩家放置（指令、结构）与假玩家放置（机械手等）都没有箱主，此时只有 OP 能管理。
- **协管**：至多 8 人，箱主或 OP 在游戏内按玩家名添加/移除（账本页或指令）。名字解析先找在线玩家，再查服务器 profile cache，**只查缓存、不联网**：原版按名查缓存未命中会在主线程同步请求 Mojang 接口，把这个开关交给任意箱主等于给了一个卡服按钮。因此只能添加进过本服的玩家，查不到明确提示失败。查缓存是只读的：直接查按名字索引的表，不经过原版会改写"最近访问"序号的查询（`usercache.json` 落盘只按这个序号保留最近 1000 人，任何箱主反复查名字不能把别人挤出缓存）。
- **撤权即时生效**：协管被移除、箱主被转让后，正开着管理界面的失权者当即被关界面；即使界面还没来得及关，服务端也会整体忽略其点击。
- **破坏保护**三层互为兜底：挖掘进度为 0（客户端也生效，不出现"挖得动"的假象）→ 服务端 `BreakEvent`（HIGH 优先级，覆盖创造模式瞬间破坏）→ 方块自身 `onDestroyedByPlayer` 拒绝。非玩家路径另由硬度 -1 挡住（见第二节）。
- 物品上携带的 `BlockEntityTag` 只在 OP 放置时生效，普通玩家放不出"预装仓库"的箱子。
- 创造模式 Ctrl+鼠标中键选取箱子时，服务端不会把真实数据写进物品（`saveToItem` 刻意不写）：原版服务端会用服务端方块实体覆盖选取物品的 `BlockEntityTag` 且不校验距离，默认实现会让 OP 放下即复制整仓，也会让创造模式玩家伪造坐标远程读仓库。

## 四、界面

**捐赠界面**：3x3 投入格 + 玩家背包，复用原版发射器贴图。放进投入格的物品在服务端每 tick（以及每次点击后）尽量转入内部仓库；仓库放不下的余量留在投入格，捐赠者可以自己拿回；关界面时先再转一次，剩下的全部退还，背包满则掉在脚下。捐赠界面没有任何绑定仓库的槽位，捐赠者看不到也取不走仓库。悬停在空投入格上显示规则说明。

**管理界面**：内部仓库 54 格（6x9）+ 玩家背包，复用原版大箱子贴图，完整存取。右上角"账本"按钮打开账本页（流水 / 累计 / 协管三个标签），关闭账本页回到管理界面，容器会话保持打开。账本请求与协管增删两个网络包按玩家限速（账本请求每 5 tick 至多一次、协管增删每 10 tick 至多一次，按玩家而不是按界面计，关了再开不重置），超出的静默丢弃：成功的协管增删每次写一行永久审计，不限速就能被改过的客户端刷爆日志、拖慢主线程。流水每页 10 条、累计每页 10 人，都由服务端分页下发；累计页脚显示真实总人数。累计表每个存取过的玩家一行、没有上限，不能整表下发，也不能截成"前 N 名"：按放入量降序截断会把只取不放的人（通常正是协管）截掉，查账最该看的取出方反而看不到。

两种界面的 Shift 点击都走原版 `moveItemStackTo`，目标槽位的 `mayPlace` 过白名单，因此守恒且不会把白名单外的物品塞进去。

## 五、白名单

判定顺序（`DonationWhitelist`，界面投入、管理界面存入、Shift 点击、漏斗输入共用这一处）：

1. 必须是 `BlockItem`（种子、告示牌这类"放下来是方块"的物品也算）。
2. 可直接食用的物品不收：胡萝卜、马铃薯、甜浆果、发光浆果在原版里是 `ItemNameBlockItem`（放下去是作物），只看第 1 条会被当成建材收进来。蛋糕、食物模组的派与盛宴这类"整块放置的食物"作为物品本身不可食用，这一条管不到，靠拒收标签：默认只列了原版蛋糕，模组的食物方块由服主按需追加。
3. 带任何物品 NBT 一律拒收：堵住装满东西的潜影盒、带 `BlockEntityTag` 的箱子、改过名或附过魔的方块。
4. 创造限定的 `GameMasterBlockItem`（命令方块、结构方块、拼图方块）在代码里硬拒。
5. 数据驱动拒收标签 `miningdim:donation_box_denied`，默认包含：捐赠箱自身、全部 17 种潜影盒、`#forge:ores`（可选引用）、钻石块、绿宝石块、下界合金块、远古残骸、刷怪笼、蛋糕，以及其余创造限定方块（连锁/循环命令方块、结构空位、屏障、光源方块、基岩、末地传送门框架、强化深板岩、紫水晶母岩、石化橡木台阶、各类虫蚀方块）。服主可用数据包往这个标签追加条目。

## 六、自动化

- 漏斗、管道等可以从**顶面和四个侧面**输入，同样过白名单。
- **任何面都不能抽取**：能力只暴露一个抽取恒为空的视图（`getStackInSlot` 返回副本），底面连这个视图都不暴露；方块实体不实现原版 `Container`，原版漏斗的非能力路径也够不到仓库。
- **空面不暴露**：不带面的查询（`side == null`）拿不到物品能力。这是 Jade、The One Probe 等信息显示模组的读法：服务端 Jade 会逐格读出物品能力的内容，发给任何看向方块的玩家，暴露了就等于让捐赠者看到仓库。漏斗、管道查询时都带具体的面，不受影响。
- 自动化输入在流水里记为"自动化输入"，同一窗口（5 分钟，6000 tick）内按物品聚合，窗口到期由方块实体 tick 落成一条；未到期的聚合随存档保存，方块被拆时先落账再散落。

## 七、存取流水

只用于查账，不产生回报。

- **聚合**：同一次界面会话内按"玩家 + 物品"聚合，放入与取出分开，会话结束落账，先放入后取出。管理界面按"每次点击前后仓库快照求差"把存取精确记在点击者名下（点击在服务端主线程上是原子的）。
- **字段**：时间（现实时间毫秒）、玩家名 + UUID（自动化输入无 UUID）、动作（放入/取出）、物品 id、数量。
- **方块实体内保存**：最近 300 条（环形，满了丢最旧的），以及每位玩家的累计放入/取出件数和自动化输入累计（累计不随环形淘汰）。全部随方块实体 NBT 落盘。
- **永久记录**：每条流水、每次协管增删、每次转让、每次放置都写一行服务端日志。移除（拆箱）写一行 `REMOVED` 汇总（破坏者、箱主、散落组数与件数；玩家拆除记玩家身份，其余路径记 `non-player`），再按物品逐行写 `REMOVED_DROP` 明细：拆箱不经过管理界面的逐次记账，是唯一一次倒出整仓的路径，这几行是它仅有的取出记录。指令清空（`CLEARED`，见第二节"指令替换"）写一行汇总加每种物品一行 `CLEARED_ITEM` 明细。`/clone ... move` 的源位置同样会记一次 `CLEARED`，这些物品其实随箱子到了目标位置。独立 logger `miningdim/donation`，带维度与坐标，例如：

  ```
  [donation] dim=minecraft:overworld pos=16,-58,393 DEPOSIT actor=Alice uuid=... item=minecraft:stone count=35
  [donation] dim=minecraft:overworld pos=31,-58,393 OWNER_TRANSFER by=console from=Bob(...) to=Carol(...)
  [donation] dim=minecraft:overworld pos=51,-58,393 REMOVED by=Carol(...) owner=Bob(...) dropped_stacks=3 dropped_items=18
  [donation] dim=minecraft:overworld pos=51,-58,393 REMOVED_DROP actor=Carol uuid=... item=minecraft:stone count=15
  ```

  建议服主在 log4j 配置里把 `miningdim/donation` 分流到独立文件长期保存。

## 八、指令

所有子命令都用坐标指定箱子（客户端会自动补全准星所指方块的坐标）。"OP"指玩家本人是 OP，不看指令来源的权限等级：原版告示牌的 `run_command` 以点击者身份、固定权限等级 2 执行，FTB Quests 的 elevate_perms 命令奖励同理，只看来源等级的话，任何玩家点一块预制牌就能对任意坐标查账。没有实体的来源只认真控制台与 RCON（权限等级 4）；命令方块、数据包函数（默认 2）一律按无权处理，既不能查账，也不能以控制台身份增删协管或转让箱主。

根指令谁都能执行，所以失败提示本身不能泄露信息：非 OP 玩家对"区块未加载 / 坐标越界 / 该处不是捐赠箱 / 无权管理"只得到同一句"该位置没有你能管理的捐赠箱"，否则按网格扫坐标就能测出别人的活动范围和所有捐赠箱的位置；探测也不会加载区块。OP 与控制台仍拿到细分的错误。

| 指令 | 谁能用 |
|---|---|
| `/donationbox info <pos>` | 能管理该箱子的人：箱主、协管、OP |
| `/donationbox log <pos> [page]` | 同上 |
| `/donationbox coadmin list <pos>` | 同上 |
| `/donationbox coadmin add <pos> <name>` | 箱主、OP |
| `/donationbox coadmin remove <pos> <name>` | 箱主、OP（按名单里记下的名字移除，离线或改名也能移除） |
| `/donationbox transfer <pos> <name>` | 仅 OP（换自管区管理时用；协管名单整体清空、原箱主不自动降为协管，需要留用的人由新箱主重新添加。否则原箱主事先把小号加成协管，交接后仍能整仓取物。审计行记下被清掉的协管） |

## 九、已知限制

- **滥用面（待服主拍板）**：任何玩家都能合成捐赠箱，放置者即箱主；非箱主挖不动、BreakEvent 被取消，爆炸、活塞、凋灵、末影龙以及只看硬度的非玩家破坏路径都毁不掉。于是在没有领地保护的地方（公共道路、轨道、别人未圈地的建筑出入口），恶意玩家可以把箱子当成别人移除不了的阻挡物，只有 OP 能清理。这是"可合成 + 只有箱主与 OP 能拆 + 抗爆"三条已拍板规则的组合结果，不是实现错误，放宽任何一条都要服主决定。可选方向：允许任何人拆除"仓库为空且除箱主外无人存入过"的箱子（散落为空，不会丢物；但箱主自己放一件就能绕过）；或维持现状，由 OP 巡查清理。拍板前的处置：OP 用 `/donationbox info <坐标>` 查箱主，在 `miningdim/donation` 日志里按 `PLACED` 行（带维度、坐标与放置者）巡查，直接拆除（仓库照常散落，并留下带 OP 身份的 `REMOVED` 审计）。
- 内部仓库只有 54 格。满了以后捐赠者的余量会留在投入格、关界面时退还，箱子不会自己扩容，需要箱主/协管及时搬运。
- 投入格是每次会话独享的临时容器。它每 tick 都会被清空到仓库，只有仓库满时才会留东西；此时若服务器崩溃，投入格里的余量会丢失（与原版工作台合成格同一性质）。
- 方块实体里只留最近 300 条流水，更早的只在服务端日志里。
- 协管只能是进过本服的玩家（profile cache 查得到的）；协管名单记的是添加时的名字，改名后仍显示旧名，但按旧名仍可移除。
- 顶面与侧面的自动化视图如实返回仓库内容（只是取不出）。有人贴着箱子放一个会读取相邻物品能力的设备（如 AE2 存储总线），就能看到仓库清单；这需要在箱子旁边动工，有领地保护的地方做不到。不改成"读出为空"，是因为不少物流模组按 `getStackInSlot` 规划投递，读空会让它们往满仓里反复投递、来回弹回。
- 只检查物品 NBT（`tag`），不检查 Forge 能力附加数据（`ForgeCaps`）。若有模组把容器内容存在能力数据里而不是 NBT 里，需要把该物品加进拒收标签。
- 放置审计日志写在放置当刻；若放置随后被领地模组撤销，日志里会留下一行没有对应方块的 PLACED 记录。
- 斧头不加速拆除（见第二节）。
- 硬度 -1 只能挡住"先查硬度再破坏"的调用方。若有模组不查硬度就直接 `Level.destroyBlock`，箱子仍会被移除并整仓散落；新增会拆方块的模组时要核对它的破坏判据。Jade 一类信息显示模组可能把捐赠箱标成"不可破坏"，这只是显示，箱主与 OP 照常可拆。
- 界面（捐赠界面、管理界面、账本页）目前只通过编译与服务端 GameTest 验证，布局尚未实机目测。

## 十、验证

`DonationBoxGameTests`（batch `donation`）覆盖：

| 用例 | 覆盖 |
|---|---|
| `donatedBlocksMoveIntoStorageAndPlacerBecomesOwner` | 放置者成为箱主；投入即转入仓库；会话落一条流水 |
| `donorCannotReachStorageAndAutomationCannotExtract` | 捐赠界面无仓库槽位，各种点击与双击收集都碰不到仓库；五个面抽取为空，底面与空面不暴露 |
| `whitelistRefusesNonBlocksNbtCarriersAndDeniedTag` | 非方块、装满钻石的潜影盒、改名方块、拒收标签、创造限定方块被拒；界面、仓库、自动化三条路径都拒 |
| `fullStorageLeavesRemainderInInputAndRefundsOnClose` | 仓库满时余量留在投入格、关界面退还、总数守恒；背包满时掉在脚下 |
| `shiftClickConservesItemsAndHonorsWhitelistInBothMenus` | 两种界面 Shift 点击守恒且遵守白名单 |
| `strangersAndCoAdminsCannotBreakTheBox` | 陌生人与协管挖掘进度为 0、生存/创造破坏都被取消、方块自身兜底；活塞、凋灵、末影龙 |
| `hardnessGatedBreakersCannotBreakTheBoxButTheOwnerStillMinesIt` | 硬度为负（只看硬度的非玩家破坏路径拆不动）；箱主按名义硬度走真实生存挖掘拆掉箱子，陌生人挥满同样时长无效 |
| `ownerAndOperatorBreakDropContentsAndAPlainBox` | 箱主、OP 拆除散落全部内容物并掉一个不带内容物的箱子 |
| `explosionsNeitherDestroyNorDuplicateTheBox` | 抗性、不掉落；被强行列入待炸名单也不被移除（对照石块被炸掉） |
| `coAdminCanWithdrawUntilRemovedThenIsLockedOut` | 添加协管后可取；移除后界面被关、旧界面点击被忽略、再开只能捐赠 |
| `coAdminListCapsAtEightResolvesCachedNamesAndRejectsUnknown` | 上限 8；离线玩家经真实 profile cache 实例解析；解析不到、超长名、无权编辑、箱主自身、重复各自失败 |
| `hoppersFeedWhitelistedBlocksButNothingCanBeExtracted` | 原版漏斗从上方输入且过白名单；下方漏斗抽不出；自动化窗口聚合成一条 |
| `ledgerAggregatesSessionsTracksTotalsAndSurvivesNbt` | 会话聚合、累计、自动化窗口、NBT 往返（含未到期窗口）、300 条环形上限 |
| `operatorTransfersOwnershipByCommand` | 非 OP 转让被拒；OP 与控制台经指令转让；转让清空原箱主任命的全部协管；未知名字不改变箱主 |
| `ownerlessBoxIsOperatorOnlyAndClientSyncCarriesOnlyTheOwner` | 非玩家/假玩家放置无箱主、只归 OP；假玩家开不了界面；区块同步只带箱主身份 |
| `creativePickBlockNeitherLeaksNorCopiesTheStorage` | 走真实创造模式物品栏包：选取物品不带仓库/流水/协管；OP 放下得到空仓新箱，原箱不变 |
| `cloneMoveRelocatesTheBoxWithoutDuplicatingItsStorage` | `/clone ... replace move` 仓库随箱移动、源位置不散落；`/setblock ... replace` 清空而不散落 |
| `sidelessQueriesLikeJadeCannotReadTheStorage` | 按 Jade 的读法（空面取物品能力再逐格读）读不到任何东西；带面的自动化输入照常可用 |
| `removalAuditNamesTheBreakerAndItemizesTheDrops` | OP 拆别人的箱子：审计汇总行写明破坏者与箱主，按物品逐行记件数（同种合并）；非玩家移除记为 `non-player` |
| `ledgerTotalsArePagedServerSideSoWithdrawOnlyRowsStayVisible` | 42 人的累计表逐页翻完每人恰好一次、只取不放的一行在最后一页、总人数真实、越界页码夹回；快照网络编码往返不变 |
| `ledgerAndCoAdminPacketsAreRateLimitedPerPlayer` | 走包处理入口：冷却内的账本请求与协管增删被丢弃且不产生变更；重开界面不重置额度；冷却后恢复受理 |
| `commandFailuresRevealNothingToNonOperators` | 非 OP 对别人的箱子、已加载空地、未加载远处执行各子命令，回复完全相同且不加载区块；OP 仍得细分错误，箱主照常可用 |
| `elevatedSourcesAreNotOperators` | 非 OP 点击的告示牌来源（权限 2）查不到别人的账、转让不了；无实体的 2 级来源（命令方块/数据包函数）查不到账、不能增删协管或转让；OP 点牌与真控制台照常可用 |
| `coAdminNameLookupLeavesProfileCacheRecencyUntouched` | 在真实 profile cache 实例上按名字解析最久未访问者与查无此人的名字后，缓存落盘顺序不变 |
