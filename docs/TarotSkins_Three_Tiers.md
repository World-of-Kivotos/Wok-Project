# 塔罗三档平板皮肤 · 实现说明

> 适用：WOK 本体，modId `miningdim`。整合分支：`codex/tarot-social-pr`；工作区：
> `D:\Wok\build\worktrees\tarot-social-integration`。原规格与成品位于 `G:\Wok\_tarot_skins`（v0.1）；
> 页面样式、交互和图片已内联进社交平板，构建与运行不依赖该原型目录。

## 1. 三档口径

| 档 | id | 名称 | 解锁条件 | 判定字段 |
|---|---|---|---|---|
| T1 | `stardust` | 星屑 | 22 张大阿卡纳各点亮过至少一种形态 | `arcana >= 22` |
| T2 | `shining` | 闪耀星辰 | 点亮过任意一张闪耀品质 | `shinyForms > 0` |
| T3 | `starmaster` | 星辰之主 | 220 种形态（22 × 5 品质 × 正逆）全点亮 | `count >= 220` |

`starlight`（社交成就位）不动。

## 2. 服务端

### 2.1 图鉴持久数据

`com.miningdim.job.tarot.codex.TarotCodexSavedData`（`SavedData`，主世界 `data/miningdim_tarot_codex.dat`）：

- 每名玩家 `long[4]`（220 bit，够 256）；位索引
  `((cardId * 5) + quality.ordinal()) * 2 + (upright ? 0 : 1)`，取值 0..219。
- 判定：`record`（首次点亮返回 true）、`has`、`count`（已点亮形态数）、`arcana`（已点亮 cardId 数）、
  `shinyForms` / `hasShiny`、`hasAllArcana`。
- 为什么必须持久化：`PackGachaService.grantOrRefund` 对同 cardId 重复牌只发碎片，背包里不可能同时存在
  220 种；按“当前持有”判第三档是死条件。

### 2.2 记录入口（全部在发牌成功之后）

| 入口 | 位置 |
|---|---|
| 开包（普通/高级/派生包） | `PackGachaService.makeCard`（所有发牌收敛到这里） |
| 闪耀包自选 SSR | 同上（`grantShinySelection` 走 `makeCard`） |
| 合成 / 正逆翻转 | `TarotCraftService` 两个产出分支 |
| 碎片兑换指定牌 | `TarotShardExchange.exchange` |
| 登录 / 重生补扫 | `TarotCodexEvents`（`PlayerEvent.PlayerLoggedInEvent` / `Clone`）→ `TarotCodex.scan` |

补扫只补“当前看得到”的牌（背包 + 副手），已消耗掉的历史追不回；创造模式的开包、合成、兑换和补扫均不计入图鉴（避免测试污染）。

### 2.3 里程碑 → 本体成就 → 主题解锁

塔罗职业代码不反向依赖成就或展示层。图鉴里程碑先进入 WOK 本体成就目录，再由社交侧读取已完成的正式成就状态：

- `TarotCodex.addListener(MilestoneListener)`：点亮新形态 / 补扫结束后回调，异常向调用方传播，由入口边界记录，避免联动故障被吞。
- `JobHooks` 订阅 `TarotCodex`，按 `milestone` 必填且仅允许 `stardust` / `shining` / `starmaster` 的 `JobTriggers.TAROT_CODEX` 触发对应 advancement。三条正式 id 分别为 `miningdim:profession/tarot_stardust`、`miningdim:profession/tarot_shining`、`miningdim:profession/tarot_starmaster`。
- 原版 advancement 完成后由 `AdvancementEarnEvent` 接入 `AchievementRewardSystem`，按成就系统的快照和去重规则生成待领取奖励；成就点仍需玩家手动领取。
- 社交侧通过完成状态同步解锁皮肤，调用 `SocialStore.unlockTheme`（内部 `contains` 判重，幂等）；玩家档案不存在时跳过，不代建档案。`SocialThemes.unlockKey` 保存完整 advancement id。
- `miningdim-tarot.toml` → `codex.codexUnlockThemes`（默认 true）只控制皮肤解锁，不控制三条正式成就。
- `social.bootstrap` 每次开机前重算一次，登录补扫、配置变更和老玩家都能补对皮肤状态；成就追溯由 `AchievementBackfill` 负责。

| 正式成就 id | 名称 | 本体成就档位 | 默认待领取积分 | 解锁皮肤 |
|---|---|---|---|---|
| `miningdim:profession/tarot_stardust` | 星屑 | 金 | 50 | `stardust` |
| `miningdim:profession/tarot_shining` | 闪耀星辰 | 白金 | 100 | `shining` |
| `miningdim:profession/tarot_starmaster` | 星辰之主 | 传说 | 800 | `starmaster` |

三条声明与配套元数据由本体 `AchievementDeclarations` 同时生成。皮肤达成即解锁，无需先领取积分；积分沿用本体手动领取规则。前两条各自挂在职业根节点，第三条挂在星屑之下，获得闪耀不要求先集齐 22 张。

2026-10-21 审查修复确认：维护者明确保留以上条件、档位、50/100/800 点和默认开启主题解锁。社交数据库迁移与七天保留规则见 [SocialTablet_Storage](SocialTablet_Storage.md)，不清理这些永久解锁。

### 2.4 下发（`social.bootstrap`）

主题项新增 `progress`，另给总览 `tarotCodex`：

```json
{ "id": "starmaster", "name": "星辰之主", "subtitle": "视差星图 · 翻牌 · 洗牌",
  "unlockKey": "miningdim:profession/tarot_starmaster", "unlocked": false,
  "progress": { "now": 137, "max": 220, "percent": 62, "label": "137 / 220" } }
```

```json
"tarotCodex": { "cards": 21, "cardsTotal": 22, "shiny": 0, "codex": 137, "total": 220 }
```

选皮接口不变：`social.theme.select` 仍由 `SocialThemes.unlocked` 校验。**没有**任何客户端解锁写接口。

## 3. 客户端（`assets/miningdim/web/social.html`）

页面在 jar 内以 `data:` URI 内联加载，因此所有资源必须内联：

- 三档 CSS / 交互 JS 由 `_tarot_skins/prod/apply.py` 注入 social.html 的成对标记之间
  （`@@tarot-skins-css@@` / `@@tarot-skins-js@@`），可重复运行、只替换标记区间。
- 牌面用 mod 自己的塔罗牌图（`textures/gui/tarot/cards/00,16,17,18,19,21.png`）转 JPEG base64；
  星云图（T2/T3 背景、T3 行星表面、选择器缩略图）压成 800×500 JPEG，用 CSS 变量 `--sk-nebula` 共用一份。
- 页面体积 132 KiB → 434 KiB（base64 后 ~600 KB data URI）。

### 3.1 三档观感

- 共用：夜空底、深色玻璃面板（不用 `backdrop-filter`）、描金/描紫边框、全屏效果层 `.sk-sky`
  （`z-index:1`，在 `.bg` 之上、`.app` 之下）。
- T1 星屑：静态 canvas（220 星点 + 2 团星云光）一次画完，Hero 单张平放 + 烫金卡框，**0 rAF**。
- T2 闪耀星辰：canvas 一次画完（320 星点 + 十字星芒）+ 纯 CSS 动效（星点呼吸 6.5s、扫光 12s、
  银河带、卡片浮动、全息卡边），**0 rAF**。
- T3 星辰之主：星云图 + 暗紫行星（虹彩光环）+ 1 个 rAF 星图（星点漂移、流星、指针光晕、视差），
  Hero 三张扇形可点翻牌、星座 7 点可点看牌名、洗牌按钮。

### 3.2 性能与降级

- 只有 T3 起 rAF；`document.hidden` → `cancelAnimationFrame` 并给 `<html>` 加 `sk-paused`（CSS 动画暂停）。
- `prefers-reduced-motion: reduce` → 全部静态（不启动 rAF，动效 `animation:none`）。
- 连续 2 秒平均帧 > 22 ms → `<html>` 加 `fx-lite`，停极光/流星/指针光晕，只保留星点与卡面。
- 动画只碰 `opacity` / `transform` / `background-position`。

### 3.3 选择器

- 三张档位卡：档位徽标、名称 + 代号、效果副标题、解锁条件 + 进度条 + `now/max`、状态（已装备/已解锁/未解锁）。
- 未解锁：`点亮预览（15 秒后还原）` —— 只在本机 `applyTheme` 临时应用，不调 `social.theme.select`，
  15 秒或关闭弹窗即还原；预览期间 Hero 角标显示「预览」水印。
- 五个原主题保留在下方；顶部一句话说明「主题只改你自己的平板，别人看不到」；底部按服务端下发的
  `tarotCodex` 显示图鉴总览。

## 4. 验证

| 项 | 结果 |
|---|---|
| 图鉴去重 / 22·闪耀·220 边界 / 落盘往返 | `TarotCodexGameTests` 保留 5 项业务用例；移除位索引实现、空档和上游已排除的非法入参用例 |
| 图鉴损坏记录拒绝 | 单独覆盖缺 UUID、Bits 长度异常，错误包含条目上下文 |
| 三档主题在册、默认锁定、`unlockTheme` 幂等、进度边界 | `SocialGameTests` 新增 2 项 |
| 真实记录触发三档解锁、21/22 与 219/220 门槛、重复记录 | `SocialGameTests.codexRecordsUnlockAllThreeTabletThemes` 通过 |
| 创造模式直接获取、产物记录和背包补扫 | `SocialGameTests.creativeAcquisitionDoesNotRecordCodex` 通过，图鉴保持 0 |
| 首次皮肤整合的历史全量记录（2026-10-21） | 当时日志曾出现 `All 2117 required tests passed` |
| 正式接入本体成就后的全量回归（2026-10-21） | 日志明确出现 `All 2120 required tests passed` |
| 正式成就、数量统计、950 点待领取奖励与即时皮肤 | `codexRecordsUnlockAllThreeTabletThemes` 通过，重复通知仍只有三条奖励 |
| 历史图鉴补发、社交档案稍后建立、配置开关恢复 | 三项新增服务端用例通过；不通过图鉴状态直接解锁皮肤 |
| 编译、数据生成、模块边界、生产 JAR | `compileJava`、`runData`、`verifyModuleBoundaries`、`jarJar` 与 `reobfJarJar` 通过 |
| 成品同步与脚本语法 | 三档 CSS 与原成品一致；全部内联 JS 解析通过；图片占位符已替换 |
| 三档 + 选择器 + 翻牌/洗牌/点星座的视觉 | Chromium 116 渲染截图（`_tarot_skins/prod/shots/`） |
| 解锁链路 | 服务端权威：图鉴触发正式成就，成就完成事件及登录/开机同步解锁；客户端无写入口 |

PR #80 审查修复后的完整回归日志明确出现 `All 2126 required tests passed`，包含正式成就定义、元数据、条件 JSON 往返与非法条件拒收，以及成就到皮肤奖励的完整链路。图鉴内部信任已校验的牌身份与在线玩家；NBT 读档仍在反序列化边界拒绝损坏记录。SQLite 存储、七天清理与编码后回执预算的验证见 [SocialTablet_Storage](SocialTablet_Storage.md)。实机 MCEF 画面不在本轮服务端回归结论内。

## 5. 未完成 / 风险

- **实机 MCEF 出帧未验收**：本机测试环境里 CEF 对**任何**页面都不出首帧（内置 dev 页同样
  `painted=false`、无 `loadError`），平板停在「Loading Web UI…」。已用探针确认与页面体积、主题、
  遮挡/焦点无关，属于环境级 CEF 问题；需要换一台能正常渲染 MCEF 页面的机器重做 §4 最后一行。
- 第三档门槛（220 形态）在真实玩家分布下可能过慢；规格 §5 已给出 110 / 44 的降级方案，本期未改。
- 第二档产出窄（闪耀只能合成到 L10），文案已在选择器写明条件，产出面未调整。

## 6. 整合交付（2026-10-21）

- 在已有社交平板与最新主线合并态上，收口三档主题、图鉴持久化、主题解锁与进度回包；保留平板开合动画、相机及原有五个主题。
- 模块登记表将社交平板的源码、页面、设备贴图和动画声音登记为 `wok-social`，依赖 `wok-achievement` 与 `wok-job-tarot`，MCEF 仍是可选客户端集成。
- 修正统一记录入口遗漏的创造模式限制，并新增两项真实服务端链路用例。原始工作区和根目录其他未提交改动未覆盖。
- 保留旧图鉴数据与已解锁皮肤存档，不删除或重置既有玩家状态。
- 本轮将三档正式注册至本体 `wok-achievement`，并提交生成的三份进度 JSON 与三份元数据 JSON；完整回归 2120 项通过。
- 本会话未提供 Context7，Forge/Minecraft 接口按项目锁定的 1.20.1-47.3.0 依赖源码与字节码签名核对。
- 分发产物：`D:\Wok\dist\tarot-social-integration\miningdim-1.20.1-1.0.33-all.jar`。该文件是 WOK 本体整合 JAR，包含 SQLite 驱动；不是步战附属 MOD。
- 本机 JDK 17.0.19 的 Unix socket 临时路径需要设为短路径。验证时仅对当前构建进程设置 `JAVA_TOOL_OPTIONS=-Djdk.net.unixdomain.tmpdir=D:\Wok\tmp`，未修改工程构建配置。
- 本次未重做实机 MCEF 画面验收；第 5 节的首帧问题继续保留，不能将服务端全绿等同于客户端视觉验收通过。
