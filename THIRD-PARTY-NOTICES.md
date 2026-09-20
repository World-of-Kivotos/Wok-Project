# 第三方组件声明 (Third-Party Notices)

本文件是 [LICENSE](LICENSE) 第六条的配套清单。仓库主体内容受 LICENSE 约束（专有，保留一切
权利）；下列第三方材料**不在**该许可的授权范围内，各自受其原始许可条款约束。

清单按"是否随官方构建产物分发"划分——这决定了各许可的义务是否实际落到我方头上。
本文件所列许可信息均取自各组件自身的元数据或官方许可页，不作推测。

---

## 一、随官方构建产物分发的内嵌材料

本节收两类：一是由 Forge JarJar 机制内嵌进产物 jar 的 `META-INF/jarjar/` 的第三方库（下表），
二是打进 `src/main/resources` 因而随 jar 一起分发的第三方派生资源（见本节末）。两类都随我方分发，
因此其署名与许可声明义务由我方承担。

| 组件 | 版本 | 许可 | 引入方式 |
| --- | --- | --- | --- |
| `org.xerial:sqlite-jdbc` | 3.45.3.0 | Apache License 2.0，并含 BSD 2-Clause 部分 | `build.gradle` 显式 `jarJar` 声明 |
| `org.slf4j:slf4j-api` | 1.7.36 | MIT License | sqlite-jdbc 的传递依赖，由 JarJar 一并内嵌 |

### org.xerial:sqlite-jdbc

主体以 Apache License 2.0 发布。其中源自 Zentus SQLiteJDBC 的部分另附 BSD 2-Clause
许可，版权归 Copyright (c) 2006, David Crawshaw。两份许可全文随该库分发，位于内嵌 jar 内的
`META-INF/maven/org.xerial/sqlite-jdbc/LICENSE`（Apache-2.0）与
`META-INF/maven/org.xerial/sqlite-jdbc/LICENSE.zentus`（BSD 2-Clause）。

该库封装的 SQLite 引擎本身由其作者置于公有领域（public domain）。

### org.slf4j:slf4j-api

以 MIT License 发布，许可全文见 https://www.slf4j.org/license.html 。该库并非
`build.gradle` 中的直接声明项，而是随 sqlite-jdbc 由 JarJar 传递内嵌，故一并列出。

### 随产物分发的 TaCZ 派生配置（开箱枪皮 display JSON）

开箱模块的 17 份 `case_*_display.json`（位于
`src/main/resources/assets/miningdim/custom/miningdim_cases/assets/miningdim/display/guns/`）不是原创
文件：`tools/generate_case_assets.py` 从 `libs/tacz-1.20.1-1.1.8-hotfix.jar` 内
`assets/tacz/custom/tacz_default_gun/assets/tacz/display/guns/<gun>_display.json` 把整个 JSON 对象读出，
只替换 `texture` 字段、删掉 `lod`，其余（`transform` 缩放数值、`muzzle_flash`、`hud`/`slot`/`animation`/
`state_machine`/`sounds` 等引用）原样保留后写进 `src/main/resources`，因而**随我方产物 jar 分发**。
按第六节的判定顺序（内嵌资源 = 进入产物 jar），它们归本节而非第二节。

这是 TaCZ 配置数据的派生拷贝，不是单纯的"保留资源引用"。TaCZ 自声明许可含 `CC BY-NC-ND 4.0`
（见第二节），其 ND（禁止演绎）条款与本项目"演绎后再分发"之间的冲突评估见第五节。开箱资产的
来源与再分发边界详见 [docs/CASE_ASSET_PROVENANCE.md](docs/CASE_ASSET_PROVENANCE.md)。

---

## 二、编译期与开发期依赖（不随产物分发）

以下 mod 参与我方编译期 API 校验或开发期运行，引入方式有三种：`compileOnly files("libs/...")`
（本地 deobf jar）、远程 Maven 坐标（`compileOnly` 或 `implementation`）、以及只在开发运行时加载的
`runtimeOnly` 本地 jar。归入本节的判据不是具体的 Gradle 配置名，而是**是否随我方产物 jar 分发**：
本节全部条目都**不经 jarJar/shade 内嵌**进产物，也**不由我方分发**——玩家需自行从各自官方渠道
获取并安装；其中的本地 jar 还**不入库**（见 `.gitignore` 的 `/libs/`）。

| 组件 | 版本 | 自声明许可（取自各 jar 的 `META-INF/mods.toml`） | 引入方式 |
| --- | --- | --- | --- |
| GeckoLib 4 | 4.8.2（`geckolib-forge-1.20.1`） | `MIT` | `implementation fg.deobf(...)`，Cloudsmith Maven |
| TACZ（永恒枪械工坊：零） | 1.20.1-1.1.8-hotfix | `GPL3 / CC BY-NC-ND 4.0` | `compileOnly files("libs/...")` |
| Champions（冠军／强敌再续） | forge-1.20.1-2.1.10.2 | `lgpl-3.0` | `compileOnly files("libs/...")` |
| MCEF | forge-2.1.6-1.20.1 | `LGPL` | `compileOnly files("libs/...")` |
| JEI（Just Enough Items） | 15.20.0.135（`common-api` + `forge-api`） | 许可待补 | `compileOnly fg.deobf(...)`，BlameJared Maven |
| Jade | 11.13.2+forge | `CC BY-NC-SA 4.0` | `compileOnly fg.deobf(...)`，Modrinth Maven |
| Farmer's Delight | 1.20.1-1.3.2 | 许可待补 | 仅开发期 `runtimeOnly`，`libs/` 下存在该 jar 时才加载 |
| flavor_immersed_daily | 1.1.0.3-forge-1.20.1 | 许可待补 | 仅开发期 `runtimeOnly`，`libs/` 下存在该 jar 时才加载 |

注：上表"自声明许可"是各 mod 作者在自身 `mods.toml` 中填写的字面值，以其项目主页与随附
许可文件为准。标"许可待补"的三项本地取不到元数据——JEI 的两个 api jar 内只有 `META-INF/MANIFEST.MF`、
既无 `mods.toml` 也无 LICENSE 条目，Farmer's Delight 与 flavor_immersed_daily 的 jar 不在本机 `libs/` 下；
按第六节纪律不得凭记忆填写，须在能取证时补齐。TACZ 的 GPL-3.0 声明与本项目专有许可之间的交互，见第五节。

GeckoLib 的前置强度高于本节其余条目：它在 `mods.toml` 中是 `mandatory = true`、`side = "BOTH"`、
`versionRange = "[4.8.2,4.9)"` 的硬前置（厨师双格料理工位与六档军火台的方块实体骨骼渲染依赖它），
玩家不装则本 mod 直接拒绝加载；而本节其余条目在 `mods.toml` 中均为 `mandatory = false`。它仍归本节
而非第一节，理由只有一条：`jarJar` 声明只覆盖 sqlite-jdbc，GeckoLib 不进我方产物 jar。

---

## 三、运行与构建平台（不随产物分发）

| 组件 | 版本 | 许可 / 条款 |
| --- | --- | --- |
| Minecraft: Java Edition | 1.20.1 | Minecraft 最终用户许可协议（Mojang EULA） |
| MinecraftForge | 1.20.1-47.3.0 | LGPL 2.1 |
| ParchmentMC 映射 | 2023.09.03-1.20.1 | 仅构建期使用，不进入产物；条款以 ParchmentMC 官方发布为准 |

本项目是 Minecraft 的非官方第三方模组，与 Mojang Studios 及 Microsoft 无隶属或背书关系。

---

## 四、仓库内工具

| 文件 | 说明 | 许可 |
| --- | --- | --- |
| `gradle/wrapper/gradle-wrapper.jar` | Gradle Wrapper 引导器，构建工具链的一部分 | Apache License 2.0（Gradle 项目） |

---

## 五、TACZ 许可风险提示（GPL 传染性 + 资产 ND 条款）

TACZ 自声明含 GPL-3.0。本项目当前对其仅为 `compileOnly` 编译期链接，且不分发 TACZ 本体，
玩家自行安装。但我方代码在运行期与 TACZ 同进程互相调用，按 FSF 对 GPL 的解释，
进程内链接可能构成"组合作品"（combined work）。若将来出现以下任一情形，本项目的专有许可
与 GPL-3.0 之间将产生实质冲突，须在分发前重新评估：

1. 把 TACZ 本体或其派生物与我方产物一并打包分发（含整合包、服务器一键包）；
2. 将 TACZ 代码 fork 或复制进本仓库；
3. 我方代码对 TACZ 的依赖从"可选降级"变为硬性必需，且以未分离的形式分发。

Champions 与 MCEF 为 LGPL，动态链接场景下不产生同等传染性，但仍不得将其本体并入产物 jar
后再以专有许可分发。

**已发生的事实（需单独评估，不属于上述三条假设）**：开箱模块随产物分发的 17 份枪械 display JSON
是 TaCZ 同名配置的派生拷贝（见第一节末）。它触及的不是 GPL-3.0 这条线，而是 TaCZ 对其资产自声明的
`CC BY-NC-ND 4.0`——ND（NoDerivatives，禁止演绎）条款禁止分发演绎作品，而"读出原 JSON、替换
`texture` 字段后随我方 jar 发布"按字面即构成演绎并再分发，比 GPL 的组合作品问题更直接。该事实至今
未取得授权确认。可选处置方向：向 TaCZ 作者取得书面再分发／演绎许可；改为运行期从玩家本地 TaCZ jar
读取 display 配置而不入我方产物；或完全自写 display JSON（不复用 TaCZ 的 transform／动画／状态机
数值）。结论落定前不应再扩大同类派生资产的数量。

---

## 六、变更纪律

新增任何第三方依赖时，必须同步更新本文件；判定顺序为：先确认该依赖是否进入产物 jar
（`jarJar` / shade / 内嵌资源 = 是），再据此归入第一节或第二节。许可字段一律从组件自身的
元数据或官方许可页读取，严禁凭记忆填写；一时取不到就写"许可待补"并保留取证缺口，不得猜。

开箱模块（`com.miningdim.caseopening`）的资产来源另有专文登记：
[docs/CASE_ASSET_PROVENANCE.md](docs/CASE_ASSET_PROVENANCE.md)。改动开箱的纹理、音效或 display JSON
生成逻辑（`tools/generate_case_assets.py`）时，必须同步更新那份文档与本文件第一节末的派生配置说明。
