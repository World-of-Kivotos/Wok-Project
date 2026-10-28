# WOK 本体掌勺小游戏 MC 测试

产品线为 WOK 本体，modId `miningdim`，Minecraft 1.20.1 / Forge 47.x。源码在 `com.miningdim.job.chef.zhangshao` 和 `com.miningdim.job.chef.fryer`。

当前提交分支为 `codex/chef-stack-05-production`，直接基于 #99 的 `codex/chef-stack-04-fryer`。九台外观均开放生存合成与单份正式生产，共用九原料槽、容器、调料和出品槽。炸锅压火、烤炉翻面、备餐台摆料和冷饮节拍均由服务端选择参数并重演输入。家常免费且无经验，掌勺默认每份 5 信用点。下文 1.0.35 至 1.0.39 的包、六槽菜单和六道菜谱为历史记录，本轮新源码尚未部署到本体测试目录。

所有玩家默认厨师 L1，普通菜无需额外入职或激活即可掌勺；战斗菜既有等级规则保留。

练习模式从各正式菜单的「练习」按钮进入，使用网页原型 simVersion 2 的同一 Java 内核与参数；练习不扣料、不扣信用点、不产出菜品、经验或手熟。41 种新固定职业效果、批量与快手、FD 厨锅正式掌勺接管仍在后续范围。

本轮迁移表覆盖 109 个菜品 ID（炸锅 9、烤炉 37、备餐台及冷饮 63），在 RecipeManager 对最终选中的 JSON 反序列化前按同 ID 改为对应台子类型。保留来源条件、实际食材、容器、产量和时间，覆盖同一道菜的工作台、熔炉、烟熏炉、营火、厨锅和旧炸锅入口。旧炸锅设备合成及四条 WOK 临时重复菜谱按精确 ID 移除，两条原版鱼基础菜保留。无来源 MOD 时不伪造菜谱。版本 2 的炸锅库存和未完成事务按槽位迁移为版本 3。

## 进入

- 炸锅、烤炉和备餐台右键打开原生生产菜单，空闲时点击「练习」。
- 潜行且任一手持方块时保留原版放置；创造模式双手空着潜行右键继续保留外观预览。
- 农夫乐事厨锅保持全部原有右键交互，从原生界面右侧的「练习」按钮进入。进入练习前正常关闭容器，不改库存或烹饪进度。
- `/chef practice [pot|fryer|oven|prep]` 仅作权限等级 2 的管理员调试入口，打开对应台子选择界面。普通玩家无需命令。

## 操作

上方按钮循环选择台子、星级、等级、性子和手熟。练习允许任意等级，选择不写入玩家职业数据。

- 回车或点击开始：一秒倒数后开局。
- 空格或左键按住：火候条上浮；松开下落，用火候条框住食材。
- F 或右键：炸锅压火、烤炉翻面、备餐台按节拍摆料。
- R：重开。1.5 秒内两次按 Esc：退出；界面不暂停单人世界。
- 右上角「帮助」：查看七页玩法说明；用按钮或左右方向键翻页，Esc 或「返回练习」关闭。查看期间只暂停本局练习，游戏世界继续运行；返回后不会补跑看说明的时间。

练习结果显示品质、分数、准确率、欠火/过火、调料瓶、Perfect、本台失误和回放校验。实时输入结束后重演同一局，比对状态哈希及完整结果；不一致会在界面和客户端日志明确报错。正式局不暂停世界，也不提供免费 R 重开；掌勺时关闭菜单或糊锅均结算家常菜，不退原料和已扣费用。单独选择家常制作免费。进行中的锅在重载时依据事务 journal 退费退料；服务端已结算的锅依据持久化状态恢复成品。普通取放遵循下述硬崩服回退窗口。

## 1.0.37 炸锅菜谱历史记录

1.0.37 当时有两道无外部依赖的基础测试菜，以及四道由 Casualness 原生菜迁入的可选菜。该历史版本的六条 recipe ID 与以下内容保留用于追溯；当前正式菜已改为保留来源 recipe ID 的动态迁移，另外五道炸物也已列入迁移表。

| 配方 ID | 配料 / 容器 | 产出 | 星级 / motion | 加载条件 |
| --- | --- | --- | --- | --- |
| `miningdim:chef/fryer/starter_fried_cod` | `minecraft:cod` ×1 | `minecraft:cooked_cod` ×1 | ★1 / 4 | 无 |
| `miningdim:chef/fryer/starter_fried_salmon` | `minecraft:salmon` ×1 | `minecraft:cooked_salmon` ×1 | ★2 / 4 | 无 |
| `miningdim:chef/fryer/fried_dumpling` | `casualness_delight:raw_fried_dumpling` ×1 | `casualness_delight:fried_dumpling` ×1 | ★3 / 4 | `casualness_delight` 已加载 |
| `miningdim:chef/fryer/spring_roll` | `casualness_delight:raw_spring_roll` ×1 | `casualness_delight:spring_roll` ×1 | ★2 / 4 | `casualness_delight` 已加载 |
| `miningdim:chef/fryer/plate_of_fried_dumpling` | `casualness_delight:fried_dumpling` ×1；容器 `minecraft:bowl` ×1 | `casualness_delight:plate_of_fried_dumpling` ×1 | ★4 / 4 | `casualness_delight` 已加载 |
| `miningdim:chef/fryer/spring_roll_medley` | `casualness_delight:spring_roll` ×6；容器 `minecraft:bowl` ×1 | `casualness_delight:spring_roll_medley` ×1 | ★4 / 4 | `casualness_delight` 已加载 |

六道配方的 cookTime 均为 200。基础菜没有容器；四道迁入菜配方按来源菜谱保留独立容器需求。掌勺时关闭菜单或糊锅结算家常菜，保留本次扣费。扣费以 `CHEF_FRYER` 域按操作 ID 幂等记录，只扣信用点，青辉石余额不变；开箱仍使用原来的双币规则。

## 当前测试步骤

先在本体测试环境取商用或中式炸锅，放置后右键，将生鳕鱼或生鲑鱼放入原料槽；检查家常制作不扣信用点、不加经验且出品数量正确。再选择掌勺，检查开局暂存物料、默认 5 信用点费用和完成后的服务端品质。检查关闭菜单和糊锅时的家常结算、Shift 放料和取菜。田园炉上油锅需要下方热源。检查重载恢复中的旧区块数据退费退料、已结算成品恢复、普通取放回退及旧菜单关闭不重复结算。装有 Casualness 的环境再逐项核对四道可选菜和两种容器菜。练习路径继续验证各台子「练习」按钮、四台小游戏输入、R 重开及七页帮助；帮助只暂停练习局，游戏世界继续运行。

## 测试包

使用本体目录 `D:\WOK测试\versions\1.20.1-Forge_47.4.20\mods`。客户端和服务器均须使用同一个测试包，重启 Minecraft 后生效。

为保留当前测试 JAR 中尚未汇总进主线的其他模块，测试产物通过 `tools/chef_mc_practice/package-playtest.ps1 -Fryer -Version 1.0.37-fryer-playtest` 将功能分支构建并重混淆后的炸锅模块叠加到 1.0.36 本体测试 JAR。脚本只替换炸锅相关类、单信用点账本实现、三种炸锅合成配方，并加入炸锅类和菜谱；清单更新版本。本次保留 5817 项原条目，逐项核对内容相同，替换 17 项、写入 56 项功能条目；基包中这些既有类也已与上一版功能分支构建逐项对照相同。

已部署 `miningdim-1.20.1-1.0.37-fryer-playtest-all.jar`，SHA256 为 `2873B204321ABC5F657AB313674FE87F3406509336D502D89923A444EF83CA58`。旧 1.0.36 包保留在同版本目录的 `wok-backups/chef-fryer-1.0.37/`。完整构建产物和条目校验报告保留在本地。源码提交位于功能分支，本次没有合并或推送 main。

## 验证

内核金样本包含全部随机数与哈希向量、15 个固定业务 case 和四台回放结果。新增入口用例验证九台路由、原交互保留、网络边界与练习不扣料不加经验。

基础接入的业务验收必须同时满足对应工程编译通过、GameTest 日志出现 `All N required tests passed`。客户端手感与实际中文排版仍以本次游戏内试玩反馈为准。

1.0.35 基础接入执行 `compileJava runGameTestServer jarJar -Pmod_version=1.0.35-chef-playtest` 成功；最终日志为 `All 2101 required tests passed`，其中掌勺批次包含 3 项 GameTest。完整基线模块仍由主线测试套件校验。

历史 1.0.36 产物仅新增客户端分页帮助和练习暂停查看，当时只执行 `compileJava verifyModuleBoundaries jarJar`，未重复全量 GameTest。该历史产物记录不代表本轮源码复验范围。

2026-10-27，帮助页重接 #97 入口修复后的代码提交 `432a9f10` 已执行 `compileJava verifyModuleBoundaries runGameTestServer`，日志原文为 `All 2101 required tests passed :)`。日志保留在 `tmp/chef-pr-rework-20261027/98-verify.log`；本轮文档修订只澄清历史与当前验证，不改动 Java 或资源。

1.0.37 炸锅生产执行 `compileJava verifyModuleBoundaries runGameTestServer jarJar -Pmod_version=1.0.37-fryer-playtest` 成功，日志明确出现 `All 2106 required tests passed`，并完成 `reobfJarJar`。新增 5 项炸锅 GameTest 覆盖真实家常出品、材料和容器匹配、拒绝开局不扣料扣费、实时输入重演与单信用点结算、非法/重复输入幂等、旧区块恢复及失效菜单隔离；失败用例还使用不可写的 journal 暂存路径验证 Shift 放料不产生未交付副本。失败测试世界已单独保留。没有启动原生客户端进行视觉试玩，中文排版与手感由本次游戏测试确认。

当前环境无 Context7 工具，Minecraft/Forge API 按工程锁定的 Minecraft 1.20.1 / Forge 47.3.0 映射 JAR 与现有源码核对签名，并通过编译和专用服务器加载验证。

2026-10-27 审查修订拆为 #100 支撑层和 #99 完整生产层，生产 Java 增删分别为 732 和 1450 行。支撑层独立完整测试为 `All 2106 required tests passed :)`，日志 `tmp/chef-pr-rework-20261027/100-load-guard-verify.log`。`5b37e360` 后续补上帮助鼠标翻页的同帧双击边界，已编译并通过模块检查；它与两层炸锅修订一起纳入最终完整链验证。

最终完整链执行 `compileJava verifyModuleBoundaries runGameTestServer jarJar reobfJarJar -Pmod_version=1.0.39-fryer-pr-review`，日志原文为 `All 2111 required tests passed :)`，构建和重混淆打包全部通过。日志 `tmp/chef-pr-rework-20261027/99-load-guard-final-verify.log`。两个存档写入点仍为版本 2；已核对 1.0.38 本体测试 JAR 及历史实现均写 2，删除没有核实来源的 v1 兼容，并验证不支持版本及缺事务队列会被拒绝。

加载保护同时覆盖原版存储层：已有或不可确认访问的 journal 读取失败时向外报错，仅在文件确认不存在时才允许新建。新增用例让损坏文件经过真实 `DimensionDataStorage`，验证错误不会变成空档，原文件字节完全保留。

早先一次完整复验在原子替换 journal 文件时失败，原日志 `tmp/chef-pr-rework-20261027/99-production-verify-2.log` 保留。未定位到具体系统原因，也没有添加重试或吞错；带异常诊断的重测和上述最终完整链均通过。诊断日志保留在同任务目录的 `jvm-exceptions/`。本轮未部署新包，客户端中文排版、低帧翻页、原生菜单操作与四道可选菜仍待实机验收。

2026-10-27 追加修复 #99 的拆台菜单同步崩服：菜单数据先检查方块实体是否已移除，移除后不再查询原坐标的热源。新增回归用例实际打开菜单、拆除炸锅、同步旧菜单并执行玩家 tick，验证可用状态归零且菜单正常关闭。未加修复时该用例明确报出 `Fryer transaction attached to another block`；修复后完整编译、模块校验、GameTest 和重混淆打包通过，日志原文为 `All 2112 required tests passed :)`。复现和通过日志分别保留于 `tmp/chef-pr-rework-20261027/99-removed-menu-red.log` 与 `99-removed-menu-green.log`，本层生产 Java 为 1451 行。API 已按工程锁定的映射 JAR 核对，本次仍未部署测试包。

## 2026-10-28 最新裁决与待验证范围

所有玩家默认厨师 L1，普通菜无需额外入职或激活即可掌勺；战斗菜既有等级规则保留。下方和上方既有测试日志、版本和部署记录均为历史结果，不代表本轮修订已经通过验证或部署。

炸锅普通槽位取放按 tick 合并后台保存，硬崩服时可能回退至最近保存状态，与原版容器的取放回退窗口一致。玩家背包与台子跨文件保存，不承诺原子性。开局、结算和拆台仍为同步交易，等待 journal 落盘。恢复按台子与 journal 的 revision 对照；仅在真实区块写入且 flush 确认后回收已覆盖的 journal，Forge Save 事件不是落盘确认。旧玩家 journal 从 v2 升级至 v3 时保留历史记录，并解除 65536 条上限。

本轮修订的编译、GameTest、打包及客户端实机验收状态以最终完整链验证为准；尚未进行客户端实机验收。应实测多人查看者数据、普通取放、开局和结算恢复、拆台、重载，以及中文排版和操作手感。
## 后续范围与验证状态

三类工作台均接入单份家常制作与掌勺生产。家常制作免费、不授予经验；掌勺默认每份 5 信用点，服务端在开局暂存食材和容器，并通过输入回放计算品质。掌勺时关闭菜单或糊锅结算家常菜，不退原料和已扣费用。历史版本部署不代表本轮源码已部署。

下一阶段包括 41 种新固定职业效果、批量与快手、FD 厨锅正式掌勺接管及旧调味系统后续迁移。旧调味存量兼容仍须保留。正式局不暂停世界，也不提供免费 R 重开；练习帮助的暂停只影响练习局。生产与效果数据仍以对应设计规格为准，JAR/TSV 配料差异只记录在审计中，不改动真源表。最终测试包只部署到 WOK 本体测试目录。

## 2026-10-28 三类正式生产验证

使用锁定的 Minecraft 1.20.1 / Forge 47.3.0、Farmer's Delight 1.3.2 和 JDK 17.0.19，执行 `compileJava verifyModuleBoundaries runGameTestServer jarJar reobfJarJar -Pmod_version=1.0.40-chef-production` 成功。日志原文为 `All 2120 required tests passed :)`，完整日志保留在 `D:\Wok\tmp\chef-production-validation-f-drive.log`。新增八项用例覆盖真实蛋糕返还三个桶、曲奇八份产量、Shift 糖作为原料、九原料和容器分配、台子隔离、烤炉翻面和冷饮参数、重复撂勺、版本 2 检查点恢复、损坏暂存拒绝、条件配方与原料替代保留及旧入口精确移除。

首次加载验证发现 Mixin 包范围包含普通迁移类，已将混入类隔离到 `recipe.mixin` 子包修复。D 盘首次实际测试有三项因账本 `.tmp` 原子替换失败；独立单线程 JDK 探针在 D 盘 1000 次中复现三次 `AccessDeniedException`，F 盘同操作 2000 次无失败。最终仅通过本地临时 Gradle 初始化脚本将测试世界移至 F 盘，未修改生产文件写入策略、未添加重试或吞错。D 盘失败现场和探针保留于 `tmp/chef-production-validation-r2.log` 与 `tmp/chef-production-io-probe/`；尚不能确定占用文件的进程。

客户端与服务端均须更新本轮包；生产菜单和菜谱同步协议升级为 2，拒绝旧客户端。当前环境没有 Context7，涉及 Minecraft、Forge、Mixin 和来源菜谱的签名及默认值均以锁定映射 JAR、Forge 源码、Mixin 字节码和来源模组序列化器核对。尚未执行原生客户端视觉验收，也未在包含全部乐事来源模组的完整整合包中逐菜实测；本轮构建尚未替换当前综合测试包。
