# WOK 本体掌勺小游戏 MC 测试

产品线为 WOK 本体，modId `miningdim`，Minecraft 1.20.1 / Forge 47.x。源码在 `com.miningdim.job.chef.zhangshao`、`com.miningdim.job.chef.fryer` 和 `com.miningdim.job.chef.pot`。

当前提交分支为 `codex/chef-stack-06-pot`，直接基于 #101 的 `codex/chef-stack-05-production`。九台外观均开放生存合成与单份正式生产，共用九原料槽、容器、调料和出品槽；FD 厨锅沿用原六输入和盛菜槽。厨锅攒火候、炸锅压火、烤炉翻面、备餐台摆料和冷饮节拍均由服务端选择参数并重演输入。家常免费且无经验，掌勺默认每份 5 信用点。下文 1.0.35 至 1.0.40 的包和验证为历史记录，本轮新源码尚未部署到本体测试目录。

所有玩家默认厨师 L1，普通菜无需额外入职或激活即可掌勺；战斗菜既有等级规则保留。

练习模式从各正式菜单的「练习」按钮进入，使用网页原型 simVersion 2 的同一 Java 内核与参数；练习不扣料、不扣信用点、不产出菜品、经验或手熟。FD 厨锅单份正式掌勺已接入，验证见本文末节；41 种新固定职业效果、批量与快手仍在后续范围。

本轮菜表覆盖 204 个菜品 ID，其中 95 道保留 FD 厨锅原配方并接显式掌勺，另外 109 道（炸锅 9、烤炉 37、备餐台及冷饮 63）在 RecipeManager 对最终选中的 JSON 反序列化前按同 ID 改为对应台子类型。保留来源条件、实际食材、容器、产量和时间。旧炸锅设备合成及四条 WOK 临时重复菜谱按精确 ID 移除，两条原版鱼基础菜在表外保留。无来源 MOD 时不伪造菜谱。版本 2 的三台库存和未完成事务按槽位迁移为版本 3，厨锅保留 FD 原生 NBT 并附加掌勺事务。

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

正式局客户端在调料瓶出现的模拟 tick 立即发送当前输入，服务端回放确认后揭晓瓶子种类；金瓶通常在一次网络往返后显示金色，收到种类消息前先显示普通色。备餐摆料冻结时不会按墙钟提前揭晓。

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

下一阶段包括 41 种新固定职业效果、批量与快手及旧调味系统后续迁移。旧调味存量兼容仍须保留。正式局不暂停世界，也不提供免费 R 重开；练习帮助的暂停只影响练习局。生产与效果数据仍以对应设计规格为准，JAR/TSV 配料差异只记录在审计中，不改动真源表。最终测试包只部署到 WOK 本体测试目录。

## 2026-10-28 三类正式生产验证

使用锁定的 Minecraft 1.20.1 / Forge 47.3.0、Farmer's Delight 1.3.2 和 JDK 17.0.19，执行 `compileJava verifyModuleBoundaries runGameTestServer jarJar reobfJarJar -Pmod_version=1.0.40-chef-production` 成功。日志原文为 `All 2120 required tests passed :)`，完整日志保留在 `D:\Wok\tmp\chef-production-validation-f-drive.log`。新增八项用例覆盖真实蛋糕返还三个桶、曲奇八份产量、Shift 糖作为原料、九原料和容器分配、台子隔离、烤炉翻面和冷饮参数、重复撂勺、版本 2 检查点恢复、损坏暂存拒绝、条件配方与原料替代保留及旧入口精确移除。

首次加载验证发现 Mixin 包范围包含普通迁移类，已将混入类隔离到 `recipe.mixin` 子包修复。D 盘首次实际测试有三项因账本 `.tmp` 原子替换失败；独立单线程 JDK 探针在 D 盘 1000 次中复现三次 `AccessDeniedException`，F 盘同操作 2000 次无失败。最终仅通过本地临时 Gradle 初始化脚本将测试世界移至 F 盘，未修改生产文件写入策略、未添加重试或吞错。D 盘失败现场和探针保留于 `tmp/chef-production-validation-r2.log` 与 `tmp/chef-production-io-probe/`；尚不能确定占用文件的进程。

客户端与服务端均须更新本轮包；生产菜单和菜谱同步协议升级为 2，拒绝旧客户端。当前环境没有 Context7，涉及 Minecraft、Forge、Mixin 和来源菜谱的签名及默认值均以锁定映射 JAR、Forge 源码、Mixin 字节码和来源模组序列化器核对。尚未执行原生客户端视觉验收，也未在包含全部乐事来源模组的完整整合包中逐菜实测；本轮构建尚未替换当前综合测试包。

## 2026-10-28 PR101 维护者复核修复

同款切片重组选择保留来源合成、排除掌勺迁移；不把没有新增食材的重组计入经验、手熟或 CHEF_DISH。按原 JAR 核实 11 组整件与切片关系，通过 `reassemblyItem` 匹配实际原料，覆盖改名配方、有形、无形和条件包装，保留来源条件及数量。鳕鱼卷等使用切片制作不同菜品的配方继续迁移。已为水果沙拉的真实资源断言加 Farmer's Delight 守卫，原版蛋糕测试始终执行。金瓶保留模拟出现后回放确认的实现并同步规格，删除失效右键分流和无调用重载，管理员练习命令与真实交互测试保留。

上一轮对齐时，用户授权将 #100、#99、#101 依序 rebase 到 `dev` 的 `fdbc5e71`，当时前两层的 range-diff 均为相同补丁，没有混入该轮修复；所有旧提交保留本地备份。

无 Farmer's Delight 环境执行编译、模块检查和完整 GameTest，通过日志原文为 `All 2123 required tests passed :)`，日志 `D:\Wok\tmp\chef-review-without-fd.log`。测试期间仅临时移出本工作区的可选开发 JAR，完成后已恢复并核对 SHA-256。带 Farmer's Delight 1.3.2 环境编译与模块检查通过，但完整测试在 `manualOvenAndDrinkParametersAndAbortAreServerOwned` 的账本 `.tmp` 原子替换处有一项失败；本轮新增迁移断言没有失败，日志 `D:\Wok\tmp\chef-review-with-fd.log` 保留。F 盘本轮也出现该文件替换问题，先前探针成功不能保证后续不会发生；生产写盘策略未改动，也未加入重试或吞错。带依赖全量不能标记为本地通过，须结合更新后的 CI 与维护者质量门确认。

## 2026-10-28 五项复审修复与最终验证

#100 支撑层修复检查点累计和退款异常，玩家 journal v3 保留已发布 v2 的全部记录。普通库存按 tick 合并后台压缩、fsync 和原子替换，同一世界最多一份在途快照；开局、结算、拆台与关服保存仍等待持久化。只有真实区块 store 与 flush 成功才按写入 token、快照身份回收；区块合并写入使旧 token 失效。加载较新版本时跳过旧日志但不提前删除。新放置以高版本空快照覆盖旧墓碑，防止旧区块复活已掉落库存。

#99 删除无效资格判断，普通菜全员默认厨师 L1 可掌勺，战斗菜原有设计限制保留。菜单按当前查看者及配方、活动菜品或待取出品读取个人等级、手熟和最佳，不改变操作者或内核。#101 已继承这些修复并保留九槽、旧库存迁移和 11 组切片保护。用户已明确接受普通取放的原版硬崩服回退窗口，不承诺背包与台子跨文件原子。

使用 Minecraft 1.20.1 / Forge 47.3.0 / JDK 17.0.19、Farmer's Delight 1.3.2：#100 独立编译、模块检查和完整 GameTest 通过，日志 `D:\Wok\tmp\chef-support-v2b-validation.log` 原文为 `All 2112 required tests passed :)`。最终 #101 完整链编译、模块检查、GameTest、jarJar、reobfJarJar 通过，日志 `D:\Wok\tmp\chef-production-v2-validation.log` 原文为 `All 2130 required tests passed :)`。产物为 `miningdim-1.20.1-1.0.42-chef-review-all.jar`，未部署。

#99 独立编译和模块检查通过；两次独立全量各有一项 Windows journal 原子替换失败，分别在方块朝向和重复撂勺用例，日志 `D:\Wok\tmp\chef-fryer-v2-validation.log`、`chef-fryer-v2b-validation.log` 保留。最终 #101 无 FD 的额外一轮同样在外观预览用例发生一次原子替换失败，日志 `D:\Wok\tmp\chef-production-v2-no-fd-validation.log` 保留；这些轮次不能标为全绿，且完整链通过不冒充 #99 独立通过。未再通过重复全量碰运气，没有添加生产重试或吞错；需要更新后独立 CI 与维护者质量门确认。

无 FD 验证结束后开发依赖已恢复，SHA-256 为 `47aed94bb9192c4978fe199f9d3ca5895aeccfbe5b5a0869fdf3aa011c2641f7`。独立静态复核和集成复核未发现指定范围内残留的 Critical/Major。客户端布局、手感、全来源 MOD 整合包及硬崩服实机演练尚未验收。
## 2026-10-28 厨锅单份掌勺验证

FD 1.3.2 原生厨锅菜单新增「掌勺」，保留原六输入、热源、盛碗和自动家常路径。剩余 95 道厨锅菜接入同一元数据目录，设计表共 204 个唯一 ID 全覆盖：厨锅 95、炸锅 9、烤炉 37、备餐台及冷饮 63。清单外另有两道基础鱼菜；来源 MOD 和有效配方仍须存在。

使用 Minecraft 1.20.1 / Forge 47.3.0 / Farmer's Delight 1.3.2 / JDK 17.0.19 执行 `compileJava verifyModuleBoundaries runGameTestServer jarJar reobfJarJar -Pmod_version=1.0.41-chef-pot`，全部成功。最终服务器日志原文为 `All 2128 required tests passed :)`，保留于 `build/chef-pot-final-server.log`；构建完整日志为 `build/chef-pot-final-validation.log`。新增五条厨锅 GameTest 覆盖真实实时回放、费用和经验、漏斗及第二玩家隔离、关闭菜单及拆锅、原生家常恢复、重载退款幂等、余桶、盛宴和双份饺子；另两条菜表用例核对 204 道覆盖与 FD 原配方保留。

生成产物为 `build/libs/miningdim-1.20.1-1.0.41-chef-pot-all.jar`，已核对 `modId=miningdim`、SQLite 内嵌驱动、204 道菜表、FD mixin 配置和生产 SRG refmap。SHA-256：`D207286F08BB003B2259E863256D1D427817504A9B2466CE26381B0FCBDD1E4F`。菜表生成器重复运行得到相同哈希。

验证环境的默认 Windows 短 TEMP 路径导致 JDK AF_UNIX 连接失败，按该 JDK 附带源码支持的配置，将本次进程 `JAVA_TOOL_OPTIONS` 设置为 `-Djdk.net.unixdomain.tmpdir=F:\CODEX\worktrees\chef-pot-production\Wok\build\sockets` 后运行；没有修改生产网络或错误处理策略。启动时发现的 Mixin 数组初始化不兼容、重载时合法空预留槽编码误判均已修复，原失败日志保留于 `build/chef-pot-gametest-r1-server.log` 和 `build/chef-pot-gametest-r2-server.log`。

本层掌勺通道协议升级为 3，客户端与服务端须一起更新。原生厨锅没有新增调料槽；41 种固定职业效果、批量与快手仍属后续范围。未执行真人客户端视觉验收和装齐全部来源 MOD 的逐菜实机制作，也未替换当前综合测试包。Context7 当前不可用，第三方接口以锁定 FD、Forge、Mixin 的实际 JAR、映射及 JDK 源码核对。

## 2026-10-28 厨锅 PR 审查前同步验证

厨锅层已同步前置 PR #101 的 `fa4a8173`，保留 11 组同款切片重组元数据和两项新增回归用例。204 道元数据断言始终执行，原生厨锅资源断言仅在 Farmer's Delight 存在时执行，保持可选依赖环境兼容。

在同一 Minecraft 1.20.1 / Forge 47.3.0 / Farmer's Delight 1.3.2 / JDK 17.0.19 环境重新执行 `compileJava verifyModuleBoundaries runGameTestServer jarJar reobfJarJar -Pmod_version=1.0.42-chef-pot-review`，全部成功。服务器日志原文为 `All 2130 required tests passed :)`；完整构建日志保留于 `build/chef-pot-pr-validation.log`，`run/logs/latest.log` 快照保留于 `build/chef-pot-pr-server.log`。本轮继续使用上述 JDK 本地 socket 临时目录配置。

新产物为 `build/libs/miningdim-1.20.1-1.0.42-chef-pot-review-all.jar`，SHA-256 为 `048BEBBB4E502CFC8D9559629ABB14241FD9F866010B046BB873C0FFBEA32869`。已核对本体 modId、内嵌 SQLite、204 道菜表、95 道厨锅菜、11 组重组关系及厨锅三类生产 refmap。实机验收和部署状态与上一节相同。

## 2026-10-28 PR102 跟进：拆锅旧菜单隔离

等待首轮自动审查时，本地复核发现拆锅后的旧菜单同步会重新创建已清除的库存检查点，可能使已掉落食材进入同位置的新锅。新增回归用例在修复前明确失败：`Synchronizing the removed menu must not recreate the dropped inventory checkpoint`；本轮只有该项失败，日志保留于 `build/chef-pot-review-regression-red.log` 和 `build/chef-pot-review-regression-red-server.log`。

修复禁止已移除的厨锅恢复或写入检查点，并锁定旧库存处理器；菜单有效性同时检查方块实体身份，替换为同类新锅也会使旧菜单失效。回归用例覆盖拆锅、旧菜单同步和点击、同位置放新锅及玩家 tick 关闭旧菜单。

使用同一锁定环境执行 `compileJava verifyModuleBoundaries runGameTestServer jarJar reobfJarJar -Pmod_version=1.0.43-chef-pot-review-fix`，全部成功，服务器日志原文为 `All 2131 required tests passed :)`。构建与服务器日志分别保留于 `build/chef-pot-review-fix-validation.log` 和 `build/chef-pot-review-fix-server.log`。新菜单有效性注入的生产 refmap 已核对为 `m_6875_`，JAR 内仍为 204 道菜与 95 道厨锅菜。

产物为 `build/libs/miningdim-1.20.1-1.0.43-chef-pot-review-fix-all.jar`，SHA-256 为 `775A06BC0EB0CAA5E865F2CBB15864A94052B8727CB6A21DD11A4974892FF6FB`。本轮未部署，实机验收状态不变。

## 2026-10-28 PR102 首轮审查问题修复

首轮审查针对 `4a6bb879` 提出三项问题：生存玩家缺碗拆锅会额外掉落一个厨锅、Farmer's Delight 版本声明宽于已验证范围、客户端和集成服务端并发修改静态 `IdentityHashMap`。本地补充的真实 `player.gameMode.destroyBlock` 用例已复现多锅掉落，另一条原生取方块用例复现取样提前结算。两项失败和完整失败现场保留于 `build/chef-pot-review-break-red.log` 与 `build/chef-pot-review-break-red-server.log`；取样的独立失败记录为 `build/chef-pot-review-pick-red.log` 与对应 `-server.log`。

拆锅恢复 Farmer's Delight 原生回收规则：`onRemove` 仅结算未结束的掌勺，厨锅物品及锅内菜品由原生 loot 和 `copy_meal` 一次性保存，不再自行额外生成厨锅或提前清空锅内菜品。待盛出的菜继续写入该厨锅物品；状态改由每个方块实体独立持有，移除共享静态 Map，也使原生在移除方块实体后执行 loot 时仍能读取待盛出的菜。回归覆盖无碗的免费家常制作、进行中掌勺的生存拆锅及含品质的待盛出菜保存，检查厨锅数量增量为 1。

取方块使用单独的只读序列化范围，既不结算或退款，也不将操作 ID、预留食材等活动事务写入取样物品；实际拆锅仍按上述原生流程结算。旧菜单检查点隔离修复继续保留。

Farmer's Delight 保持可选依赖，已按实际 JAR 的版本声明将 `mods.toml` 收紧为 `[1.20.1-1.3.2]`，保留厨锅注入失败时拒绝启动的检查；其他 FD 版本需另行验证后扩展声明。该选择不改变单份费用、品质或职业成长规则。

带 FD 1.3.2 的最终代码执行 `compileJava verifyModuleBoundaries runGameTestServer jarJar reobfJarJar -Pmod_version=1.0.44-chef-pot-review-fix` 全部成功，日志原文为 `All 2133 required tests passed :)`。构建和服务器快照分别保留于 `build/chef-pot-review-final-validation.log` 与 `build/chef-pot-review-final-server.log`；厨锅八项用例均通过。已核对 `stillValid` 与 `getCloneItemStack` 的生产 refmap 分别指向 `m_6875_` 与 `m_7397_`，并核对菜表 204 道、厨锅 95 道、内嵌 SQLite 及 FD 精确依赖范围。

最终产物为 `build/libs/miningdim-1.20.1-1.0.44-chef-pot-review-fix-all.jar`，SHA-256 为 `287BCCAE2FFC51117831823B692FF189FBC6F987FE3476DD45F067A0C2C5E5B2`。本轮未部署，真人客户端与完整来源 MOD 逐菜实机验收仍待执行。

另外通过本地临时 Gradle 初始化脚本，仅从测试运行时排除 FD，保留其编译 API，重新执行编译、模块检查和完整 GameTest 成功，日志原文同为 `All 2133 required tests passed :)`。运行前断言测试 classpath 中没有 FD JAR，加载日志也确认 FD 缺失；这轮验证可选依赖与无 FD 类加载，厨锅具体业务由上述带 FD 套件覆盖。日志保留于 `build/chef-pot-review-without-fd-validation.log` 与 `build/chef-pot-review-without-fd-server.log`。临时脚本最初的任务注册时序错误保留于 `build/chef-pot-review-without-fd-init-r1.log`，修正后重跑通过；生产 Gradle 配置未改动。

## 2026-10-28 同步前置复审后的厨锅验证

推送前发现前置层已整理到 `bd5ca38d`，厨锅两条提交随之 rebase；旧提交保留在 `codex/chef-stack-06-pot-before-review-sync`。保留前置的五项复审修复、区块写入 mixin、默认厨师 L1 规则和完整验证记录，厨锅仅追加自己的增量。

厨锅同步适配 journal v3 与真实区块落盘回执：保存 `FryerRevision`，按 revision 比较区块与 journal，普通库存按 tick 后台合并，关键交易继续同步保存。拆除写入高版本空墓碑，原生厨锅物品放回时在 `setPlacedBy` 保存新版本，保留物品携带的菜品并覆盖旧墓碑。生存拆锅回归已补上从真实掉落物到物品放回原位的路径；旧菜单用例同时检查它不能把空墓碑换回已掉落库存。

同步后的代码在 FD 1.3.2 环境执行 `compileJava verifyModuleBoundaries runGameTestServer jarJar reobfJarJar -Pmod_version=1.0.45-chef-pot-review-fix` 全部成功，原文为 `All 2140 required tests passed :)`。完整日志为 `build/chef-pot-review-synced-validation.log`，服务器快照为 `build/chef-pot-review-synced-server.log`。新增放置注入的生产映射已核对为 `m_6402_`，两套厨师 mixin 配置均保留。

本轮最终产物为 `build/libs/miningdim-1.20.1-1.0.45-chef-pot-review-fix-all.jar`，SHA-256 为 `B38E7FC583093DD8C0ACCBAD4079834069501730AA13EE46F5D55F212BF01197`。未部署，实机验收状态不变。

同一源码通过临时脚本排除 FD 运行时后，编译、模块检查和完整 GameTest 再次成功，原文为 `All 2140 required tests passed :)`，且运行前 classpath 断言确认 FD 不存在。日志为 `build/chef-pot-review-synced-without-fd-validation.log` 与 `build/chef-pot-review-synced-without-fd-server.log`。这些结果包含新前置层的七项新增用例，替代前述 2133 项作为本轮推送的最终验证结果。
