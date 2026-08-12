# WebUI 视觉一致性与完整性批判

评审对象: `webui/` 分支 `feat/webui-frontend-foundation`, 源码 21369 行 (78 个源文件)。
评审口径: 纯静态代码审计 + 机械计数, 未在真客户端 (MCEF) 内渲染过。凡是需要真机才能判定的
结论, 文中一律标注 "未验证"。

本文只回答两个问题: 一、用户反馈的"丑"到底修好了没有; 二、离"能真正用起来"还差什么。
不提出新功能, 不改任何源码。

---

## 一、"丑"修好了吗

结论: **部分修好, 但诊断本身不完整**。

上一轮把"丑"归因为"色彩层缺失", 于是补齐了双主题 token 表、9-slice 灰度上色链路与语义六档。
这些机制本身建得很扎实 (见 1.2)。但机械统计显示: 色彩确实进来了, 却几乎全部堆在一个小控件上,
而占据视觉面积 90% 以上的容器仍然是灰的; 更要紧的是, **"丑"的另一半根因不是颜色, 是度量**
—— 边框比内边距厚一倍, 见 1.5。那一条至今无人提出。

### 1.1 语义色 token 是否真的被各面板使用

统计口径: 页面文件中 `text-/bg-/border-` 前缀命中 `success|warning|danger|info|accent` 的次数
(记作"语义"), 对照命中 `fg|muted|surface|raised|bg` 的次数 (记作"中性")。

| 分组 | 语义 | 中性 | 语义占比 |
| --- | --- | --- | --- |
| 全部页面 | 70 | 333 | 17.4% |
| 扣除三个验证/预览页 | 43 | 260 | **14.2%** |

即产品页面里, 每 7 次颜色决策只有 1 次是语义色, 其余 6 次是中性灰。

**完全没有任何直接语义色类的产品页共 8 个**:
`SettingsPage` / `CodexPage` / `InboxPage` / `HistoryPage` / `MyListingsPage` /
`EngineerPanel` / `ChefPanel` / `BrewerPanel`。

色彩实际是经由哪个通道进入界面的, 统计 `tone=` 的承载控件即可看清:

| 承载控件 | tone= 次数 | 占比 |
| --- | --- | --- |
| `PixelBadge` | 69 | 68% |
| `PixelButton` | 17 | 17% |
| `PixelFrame` | 15 | 15% |
| `PixelProgress` | 2 | 2% |

**判定**: 语义色 token 被使用了, 但 68% 集中在 `PixelBadge` 一个控件上。界面从"全灰"变成了
"灰底 + 彩色小徽标"。色彩没有参与构建层次, 只是点缀。用户再看一眼多半仍会说灰 —— 因为大色块
(容器、表头、选中行、区块底) 依旧一律 neutral。

### 1.2 灰度上色链路是否真的接通

**接通了, 且实现质量高。** 但在页面里几乎没被调用。

链路本身 (`index.css` 的 `[data-pixel-tone]::after` + `PixelFrame` 下发 `--pixel-tone`) 逻辑闭合:
overlay 混合保住了斜面明暗, `mask-border` 双写前缀挖角, `isolation: isolate` 封住混合组,
三种降级表现在注释里逐条写明。`ProtectedFrameStyleKey` 把 `isolation`/`mixBlendMode` 从对外
`style` 类型剔除, 使"覆盖掉上色"在编译期写不出来。这一段是全库设计水准最高的代码之一。

调用侧的账则很难看:

- 页面与外壳中共 **91 处 `<PixelFrame`, 仅 15 处带 `tone=`** (16.5%);
- 这 15 处里还有 3 处是 `ComponentsPage` / `ColorCheckPage` 的穷举演示循环, 属于验证页;
- **产品页面真正用上染色框的只有 12 处**, 且用途高度单一: 全是错误横幅 / 警告横幅 / 危险确认,
  即 `tone` 只在"出事了"时才出现。

也就是说, 为了"一份灰度资产 x N 个颜色变量 = 整套 UI"这条压缩原则专门建的染色机制, 87% 的
框体没有用它。资产压缩的收益兑现了 (只出 3 张图), 视觉收益没有兑现。

### 1.3 层级三档 (window/panel/inset) 是否被正确区分

**组件库内部: 区分得很好。** `PixelInput`/`PixelCheckbox`/`PixelProgress` 轨道 → `inset`,
`PixelButton`/`PixelBadge`/`PixelToast` → `panel`, `PixelModal`/`PixelTooltip`/`PixelSelect`
浮层 → `window`。语义与 `conventions.md` 的定义逐条对得上。

**页面层: 基本是平的。** 统计页面与外壳中显式写出的 `variant=` 属性:

| 档位 | 次数 | 占比 |
| --- | --- | --- |
| `panel` | 59 | **73%** |
| `inset` | 15 | 19% |
| `window` | 7 | 9% |

`window` 那 7 次里, 3 次在 `PixelCheckPage` (验证页)。**产品代码中 `window` 只用了 4 次**:
`TabletShell` 外壳 1 次, `CasePage` 2 次, `AdminPage` 1 次。18 个产品页里 17 个页面级容器
一律 `panel`。

更糟的是嵌套关系。`TabletShell` 的内容区本身就是 `<PixelFrame variant="panel">` (TabletShell.tsx:261),
而绝大多数页面在它里面继续用 `variant="panel"` 包自己的区块 (如 ChefPanel.tsx:35、
BrewerPanel.tsx:80、HomePage 的 `SectionCard`)。于是屏幕上出现的是**同一张资产、同一个斜面方向、
同一个颜色的框, 直接套在自己里面**。9-slice 的层级维度是靠"外凸 vs 内凹的明暗方向相反"表达的,
panel 套 panel 表达不出任何深度差, 只表达出"边框画了两遍"。

正确写法应当是: 页面内的内容井走 `inset` (它才是"陷进面板里"), 只有真正浮起的东西走 `window`。
当前 `inset` 的 15 次里有 5 次集中在 `AdminPage`, 4 次在 `CasePage` —— 只有这两个页面的作者
理解并使用了三档层级。

### 1.4 并行 agent 之间的间距/字号/密度是否一致

存在一条清晰的、可机械定位的**跨批次接缝**: 同一语义层级的标题被两批人给了相差一倍的字号。

字号档只有 `text-1x` / `text-2x` / `text-3x` 三级, 分别等于 24 / 48 / 72 CSS px
(`--font-cell: 12` x 档位 x `--px: 2px`)。档间是 100% 跳变, 没有中间档 ——
`controlSize.ts` 的注释也承认"中间不存在'稍大一点'的合法档"。于是各批次在"区块标题该多大"
上分成了两派:

| `<h2>` 字号 | 归属文件 |
| --- | --- |
| `text-1x` (24px, **与正文完全同号**) | 5 个市场页 (Browse/Sell/MyListings/History/Inbox) + MarriagePage + SettingsPage |
| `text-2x` (48px) | HomePage / MiningPage / CasePage / AdminPage / 全部 8 个职业面板 |

两派各自内部完全自洽, 互相之间差一倍。后果是: 玩家从"职业"页签切到"市场"页签, 区块标题
从 48px 塌到 24px 并与正文融为一体 —— 市场那 5 页在视觉上**没有任何标题层级**。

其余度量的离散度:

- 页面根容器 `gap`: `gap-6` (职业面板 / Inbox / History / Codex)、`gap-4` (Home/Shop/Settings/
  Marriage/Case/Sell/MyListings/Admin)、`gap-3` (Mining/Browse)、`gap-2` (Components)。
  同属市场组的 Browse 用 `gap-3` 而 Inbox 用 `gap-6`, 相差一倍。
- `<h1>` 字号: 5 处 `text-3x`, 3 处 `text-2x`。
- 内边距: `p-4` 61 次、`p-3` 22 次、`p-8` 8 次、`p-2` 4 次、`p-1` 2 次、`p-12` 1 次。
- 表格固定高度: `h-96` 7 次, 另有 `h-128`/`h-80`/`h-64`/`h-48`/`h-32` 各若干, 无统一档。
- 全库仅 1 处 `max-w-*` (`max-w-64`)。宽视口下所有文本行会被拉到整屏宽, 无最大行长约束。

圆角一项无需担心: `borderRadius` 只剩 `none` 档 + `@layer base` 全局压 0 + `PixelFrame` 就近
再压一道, 三重防线, 全库零违规。这一条是真的锁死了。

### 1.5 被漏掉的根因: 边框比内边距厚一倍

这是本次评审最要紧的发现, 且与颜色无关。

`PixelFrame` 的边框宽走独立派生链: `slice x --pixel-scale x 1px` = 8 x 2 = **16 CSS px**。
而间距档走另一条链: `p-4` = `--px x 4` = **8 CSS px**。两条链正交, 谁也没去核对对方的量级。
`controlSize.ts` 的注释甚至明确写了边框"必须单独算进去", 但没人真的算过。

实际比例:

- 全库 98 处内边距里, **87 处 (89%) 的 padding 小于边框宽度**。最常用的 `p-4` 只有边框的一半。
- 内容因此紧贴在一道 16px 厚的斜面上, 观感就是"框子很壮、里面很挤"。
- panel 套 panel 时 (见 1.3), 内容距外壳边缘是 16+8+16+8 = 48px, 其中 32px 是两道方向相同的斜面。

对控件的影响更严重。按当前 token 实算 `PixelButton` 的高度 (行盒 + padding + 两侧边框):

| 档位 | 行盒 | 上下 padding | 上下边框 | 总高 | 边框占比 |
| --- | --- | --- | --- | --- | --- |
| `sm` | 32px | 2x2 | 2x16 | **68px** | 47% |
| `md` | 32px | 2x4 | 2x16 | **72px** | 44% |
| `lg` | 56px | 2x6 | 2x16 | **100px** | 32% |

`sm` 与 `md` 相差 4px, 即 5.9% —— 在真机上肉眼无法区分。三档尺寸被恒定的 32px 边框吃掉了两档。
组件库费力做的 `PixelControlSize` 三档, 在当前 `--pixel-scale` 取值下实际只剩两档可辨。

这不是资产问题, 是 `--px` (2) 与 `--pixel-scale` (2) 这两个旋钮的相对取值没有一起标定。
两者都还是占位值, 都写着"批 1 真客户端标定"——**但标定时必须一起标, 且必须以"边框宽 : 内边距 :
字号"的比例为准绳**, 而不是各自单独取一个"看着像素对齐"的整数。当前 16 : 8 : 24 这组比例,
无论颜色怎么调都会显得笨重。

### 1.6 逐页: 观感最差的 3 处

**Critical - 第 1 处: 四个页面把标题原样画了两遍, 且里面那遍更大**

`TabletShell.tsx:262` 已经渲染了 `<h1 className="text-2x">{ROUTE_TITLES[...]}</h1>`。
但以下四个页面在内容区又画了一遍**字符串完全相同**的 `<h1 className="text-3x">`:

| 页面 | 内层 h1 | `ROUTE_TITLES` 中的外层标题 |
| --- | --- | --- |
| `ShopPage.tsx:302` | 系统商店 | 系统商店 |
| `MiningPage.tsx:197` | 矿洞 | 矿洞 |
| `CodexPage.tsx:267` | 精英怪图鉴 | 精英怪图鉴 |
| `admin/AdminPage.tsx:984` | 管理后台 | 管理后台 |

玩家看到的是: 48px 的"矿洞", 紧接着 72px 的"矿洞"。不是相似, 是逐字相同, 而且下面那个还更大 ——
读起来像渲染出了 bug。四处均为无条件渲染, 不在任何分支内, 打开页面必现。
(`CasePage.tsx:567` 的 `text-3x` 画的是箱子名 `state.displayName`, 不属此列, 正常。)

附带一个语义问题: 每页因此有两个 `<h1>`, 文档大纲被打断。

**Major - 第 2 处: HomePage 首屏七段异步各自弹入, 无高度预留**

`HomePage` 在挂载时并发七条 action (`player.profile` / `economy.today` / `economy.status` /
`marriage.state` / `mining.myStatus` / `mining.overview` / `hub.panels`), 每条被 mock 层加了
**150-400ms 随机延迟** (`handlers.ts` 的 `plannedLatencyMs`), 且各自由独立的 `QueryGate` 收口。

`QueryGate` 在 loading 时渲染的是一个 `size="sm"` 的 `PixelLoading`, ready 后换成真实内容 —— 
两者高度差极大, 而 `SectionCard` 没有任何 `min-h`。于是首屏表现为: 七个区块在 250ms 的窗口内
以随机顺序逐个"炸开", 每炸开一个, 它下面的所有内容整体下移一次。级联跳版 7 次。

雪上加霜的是 `HomePage.tsx:1009-1015` 算了一个 `anyLoading` 并在页首再放一个"同步中"指示器。
首帧因此同时存在 1 个全局 spinner + 最多 7 个局部 spinner, 共 8 个转圈的东西。

按 `QueryGate` 头注释, "每张卡各自过一遍而不是整页一个大 gate"这个决策本身是对的 (慢的一条不该
按住已到达的六块)。问题不在决策, 在于没给卡片预留高度 —— 决策对了, 执行漏了一半。

**Major - 第 3 处: 市场组五页整体塌陷, 以 `MyListingsPage` 为最**

`MyListingsPage` 全页只有: 一行与正文同号的 `<h2 className="text-1x text-fg">我的挂单 (ACTIVE)</h2>`,
外加一张 `PixelTable`。零语义色 (统计口径下 semantic=0), 零 `PixelFrame`, 零层级。表格又被直接
放进外壳那层 `panel` 里, 于是整屏就是"灰框里一张灰表, 上面一行和表格内容一样大的灰字"。

同组另外四页同病: `HistoryPage` / `InboxPage` 的 `h2` 同为 `text-1x` 且 semantic=0;
`BrowsePage` 根 `gap-3` 与 `InboxPage` 根 `gap-6` 相差一倍。市场是记忆项里点名的"旗舰"模块,
却是全库视觉密度最低的一组。

需要说明: 这五页的**代码质量并不差** —— `MyListingsPage` 的撤单二次确认把"手续费不退"写进了
文案, 数据全部走已接线的真 action, 错误三态齐全。它丑不是因为写得糙, 是因为写它的人只用了
`text-1x` 和 `PixelTable` 两件工具, 而组件库里那 24 个控件、6 个 tone、3 档层级一件没动。

---

## 二、离"能真正用起来"还差什么

详细清单见本次交付的 issues 列表。此处只给结论性的四条:

1. **最危险的一条与"能不能用"无关, 与"敢不敢信"有关**: `callMock` 对 50 条 planned action 的
   内存世界分流**没有受 `isMockActive()` 约束**, 假数据会原样进入生产构建并在真客户端里显示。
   已用 `dist/assets/index-B0O21K9N.js` 实测确认假数据字符串在产物内。详见 issues 第 1 条。

2. **"可交互"绝大多数是真的。** 50 条 planned action 全部有会改内存世界的 handler, 写操作
   (卖菜/买卡包/求婚/进矿洞/改余额) 都会回写并经 `revision` 广播到其他面板。真正点了没反应的只有
   受 A14 (宿主中文输入) 阻塞的几个输入框, 且每一处都在界面上明说了原因, 没有假装能用。

3. **组件库覆盖率高, 缺口集中在"多值输入"。** 24 个 L1 控件基本够用; 面板真正凑合的地方是
   没有日期/范围/多选控件, 以及没有分页控件 (`BrowsePage` 自己用两个 `PixelButton` 拼了翻页)。

4. **前端一共发明了 50 条假定契约** (`PLANNED_ACTIONS`), 对照真契约 18 条 —— 即当前界面上
   **73% 的数据来自前端自己发明的形状**。接线工作量的量级由此确定。

---

## 三、优先级建议

按"改动成本 / 观感收益"排序, 供后续批次认领:

| 优先级 | 事项 | 涉及文件 | 成本 |
| --- | --- | --- | --- |
| P0 | `callMock` 的 planned 分流加生产构建拦截 | `mock/handlers.ts` | 小 |
| P0 | 删掉四个页面的重复 `<h1>` | Shop/Mining/Codex/Admin | 极小 |
| P1 | 统一 `<h2>` 字号档 (建议全库收敛到 `text-2x`) | 市场 5 页 + Marriage + Settings | 小 |
| P1 | `--px` 与 `--pixel-scale` 联合标定, 以边框:内边距:字号比例为准绳 | `styles/index.css` | 中, 需真机 |
| P2 | 页面内容井由 `panel` 改 `inset`, 消除同资产自嵌套 | 17 个产品页 | 中 |
| P2 | `SectionCard` 加 `min-h`, 消除首屏级联跳版 | `HomePage.tsx` | 小 |
| P2 | 语义色扩展到容器与表头, 不再只挂在 `PixelBadge` 上 | 全局 | 中 |

---

## 四、评审未覆盖的范围

以下事项本次**没有**验证, 不要据本文认为它们已通过:

- 全部结论均出自静态代码与构建产物分析, **未在 MCEF 内嵌 Chromium 里渲染过一次**。
- 1.5 节的像素尺寸推算基于 `--px: 2px` / `--pixel-scale: 2` 这两个占位值。真值一旦改变, 该节
  的绝对数字全部作废, 但"两条派生链未互相核对"这个结构性问题不受影响。
- 9-slice 上色链路在 MCEF 的 Chromium 版本上是否支持 `mix-blend-mode` 与 `mask-border`, 仍是
  `PENDING`。三种降级表现已在 `index.css` 写明, 需用 `#/color-check` 页在真机上判读。
- 字体缺失 (无 `@font-face`, 当前回退 `ui-monospace`) 下的实际中文渲染效果。
