# TaskSpec 索引 (2026-08-17 批)

四份可独立交付的实施规格, 每份自带硬约束、验证门与变异验证要求, 可单独喂给执行者 (终端 Codex / 子代理), 不需要额外上下文。

| # | 规格 | 分支 | 阻塞状态 |
|---|---|---|---|
| 1 | [任务系统接入 WebUI 平板面板](TaskSpec_Quest_WebUI_Panel.md) | `feat/quest-webui-panel` | 无, 可直接开 |
| 2 | [矿洞维度禁止设置重生点](TaskSpec_Mining_NoRespawnPoint.md) | `fix/mining-no-respawn-point` | 无, 可直接开 |
| 3 | [矿洞三难度死亡规则](TaskSpec_Mining_DeathRules.md) | `feat/mining-difficulty-death-rules` | **两条待主控拍板** (规格第一节), 未拍板则按默认走并在 PR 标明 |
| 4 | [矿洞进入收费](TaskSpec_Mining_EntryFee.md) | `feat/mining-entry-fee` | 数值待实测; **机制与测量埋点可先做, 默认值必须是 0** |

---

## 一、执行顺序 (有文件冲突, 不要乱序并发)

```
1 (任务面板)  ── 完全独立, 任何时候都能跑, 可与下面三条并行

2 (禁重生点) ──> 3 (死亡规则) ──> 4 (进入收费)
```

冲突点:

- **2 与 3** 都改 `src/main/java/com/miningdim/rules/RulesSystem.java` 的 `register()` 与类注释。
- **3 与 4** 都改 `MiningWebUiActions.overviewRow` 的回执字段, 以及前端 `webui/src/lib/types.ts` 的 `MiningInstanceRow` 与 `webui/src/pages/MiningPage.tsx`。

第 1 条与其余三条零重叠, 可以真并行。

---

## 二、共同的基线数字

- **GameTest 基线 1165 绿** (2026-08-17, main `5852c73`)。每条规格完成后应为 1165 + 新增用例数, **一条都不许比基线少**。
- 判绿**不能只看 gradle 退出码** —— `runGameTestServer` 在服务端数据包加载崩溃时仍可能报 `BUILD SUCCESSFUL`。必须核对日志两行: `N tests are now running!` 与 `All N required tests passed :)`。
- `JAVA_HOME=C:\Users\Xiaoxiao\.gradle\jdks\eclipse_adoptium-17-amd64-windows\jdk-17.0.18+8` (本机默认 Java 21 会在配置阶段报 `Unsupported class file major version 65`)。

---

## 三、四条规格共有的纪律 (每份文档里都重复了一遍, 这里汇总备查)

1. 零 Emoji (代码/注释/提交/文档/日志/测试)。
2. 零 TODO 与空壳代码。
3. 只改规格点名的文件, 旁边的问题口头报告不顺手改。
4. 异常自然冒泡, 不掩盖空值不生吞。
5. 提交信息中文 + Conventional Commits, 零 AI 署名。
6. 测试的质量判据: **删掉被测实现, 断言必须挂**。每条规格都点名了必做的变异验证, 失败信息原文要贴进 PR。
7. **严禁在 Bash 里用 `find`** (本机 Git Bash 的 `find.exe` 有句柄泄露, 单进程可囤三百万句柄不退出)。查找一律 `rg --files` / ripgrep / PowerShell `Get-ChildItem`。

---

## 四、每份规格里"已核实"的部分不要重新调研

四份文档里的 API 签名、事件契约、原版源码片段, 全部经 javap 反汇编或 MC 反编译源码逐条核实过, 包括:

- `PlayerSetSpawnEvent` 带 `@Cancelable`, 且 `ServerPlayer.setRespawnPosition` 在事件被取消时整个提前返回
- `Player.dropEquipment` / `ServerPlayer.restoreFrom` 读的都是**全服共用**的 `RULE_KEEPINVENTORY`, 不存在按维度设置
- `ForgeConfigSpec.ConfigValue` 有 `getDefault()`
- `oresurvey` 这个测量工具在代码与文档里**都不存在**, 从未实现

直接照用即可。反过来, 文档里标了"实施前先读 XX 的真实签名再落笔"的地方 (如 `EnchantmentHelper`), 那是刻意留的核实动作, **不许跳过**。
