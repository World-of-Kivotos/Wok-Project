# 第三方组件声明 (Third-Party Notices)

本文件是 [LICENSE](LICENSE) 第六条的配套清单。仓库主体内容受 LICENSE 约束（专有，保留一切
权利）；下列第三方材料**不在**该许可的授权范围内，各自受其原始许可条款约束。

清单按"是否随官方构建产物分发"划分——这决定了各许可的义务是否实际落到我方头上。
本文件所列许可信息均取自各组件自身的元数据或官方许可页，不作推测。

---

## 一、随官方构建产物分发的内嵌库

这些库由 Forge JarJar 机制内嵌进产物 jar 的 `META-INF/jarjar/`，随我方分发，
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

---

## 二、编译期 API 依赖（不随产物分发）

以下 mod 在 `build.gradle` 中以 `compileOnly files("libs/...")` 引入，仅用于编译期获取
API 签名。其 jar **不入库**（见 `.gitignore` 的 `/libs/`），**不内嵌**进我方产物，也**不由
我方分发**——玩家需自行从各自官方渠道获取并安装。

| 组件 | 版本 | 自声明许可（取自各 jar 的 `META-INF/mods.toml`） |
| --- | --- | --- |
| TACZ（永恒枪械工坊：零） | 1.20.1-1.1.8-hotfix | `GPL3 / CC BY-NC-ND 4.0` |
| Champions（冠军／强敌再续） | forge-1.20.1-2.1.10.2 | `lgpl-3.0` |
| MCEF | forge-2.1.6-1.20.1 | `LGPL` |

注：上表"自声明许可"是各 mod 作者在自身 `mods.toml` 中填写的字面值，以其项目主页与随附
许可文件为准。TACZ 的 GPL-3.0 声明与本项目专有许可之间的交互，见第五节。

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

## 五、GPL 传染性风险提示（TACZ）

TACZ 自声明含 GPL-3.0。本项目当前对其仅为 `compileOnly` 编译期链接，且不分发 TACZ 本体，
玩家自行安装。但我方代码在运行期与 TACZ 同进程互相调用，按 FSF 对 GPL 的解释，
进程内链接可能构成"组合作品"（combined work）。若将来出现以下任一情形，本项目的专有许可
与 GPL-3.0 之间将产生实质冲突，须在分发前重新评估：

1. 把 TACZ 本体或其派生物与我方产物一并打包分发（含整合包、服务器一键包）；
2. 将 TACZ 代码 fork 或复制进本仓库；
3. 我方代码对 TACZ 的依赖从"可选降级"变为硬性必需，且以未分离的形式分发。

Champions 与 MCEF 为 LGPL，动态链接场景下不产生同等传染性，但仍不得将其本体并入产物 jar
后再以专有许可分发。

---

## 六、变更纪律

新增任何第三方依赖时，必须同步更新本文件；判定顺序为：先确认该依赖是否进入产物 jar
（`jarJar` / shade / 内嵌资源 = 是），再据此归入第一节或第二节。许可字段一律从组件自身的
元数据或官方许可页读取，严禁凭记忆填写。
