# 像素组件库 API 约定（冻结）

适用范围：`src/components/pixel/` 下的全部 L0/L1/L2 通用控件，以及消费它们的业务面板。

真源：`docs/PixelUI_DesignSystem_DesignSpec.md`（视觉规格与六条硬红线）、
`docs/WebUI_Frontend_Wiring_Checklist.md` 第二章（组件清单与分层）。
同目录 `README.md` 讲的是 9-slice 灰度上色链路**为什么长这样**，本文讲的是**控件对外长什么样**，两者不重叠。

本文只管公开面：props 名、档位词汇、类名与颜色的取用面、无障碍下限、文件落点。组件内部怎么实现不管；
但凡本文写了名字的，一律照抄，不得另起一套同义词。

## 零、为什么要冻结

L1/L2 共 20 个控件由多个并行批次交付，之后还有 8 个面板批次消费它们。API 一旦发散，得到的不是
"20 个控件"而是"20 套风格"：同一个语义有 `tone` / `variant` / `kind` 三种叫法，同一个尺寸有
`size` / `scale` / `dense` 三种档位，面板层每接一个控件都要重新读一遍源码。这个成本是乘法级的，
且发散后再统一等于把 20 个文件连同它们的调用点全部重写。

已经落地的 `PixelFrame.tsx` 与 `PixelIcon.tsx` 是本文所有规矩的参考实现。写新控件前先读这两个文件，
本文没写到的写法，照抄它们即可。

## 一、通用 props 形状

### 1.1 基线

```ts
export interface PixelXxxProps {
  // 语义/尺寸档位（见第二节）
  tone?: PixelFrameTone
  size?: PixelControlSize
  // 状态（见第三节）
  disabled?: boolean
  // 内容与回调（见第四节）
  children?: ReactNode
  onClick?: () => void
  // 逃生口
  className?: string
}
```

- `className?: string` 每个控件都必须有，且拼在组件自身类名**之后**（照抄 `PixelIcon` 的
  `className === undefined ? sizeClass : \`${sizeClass} ${className}\``）。
  但要清楚它保证的是什么：同属性的两个 Tailwind 工具类谁生效取决于生成 CSS 里的先后，
  **不是 class 属性里的先后**。所以 `className` 只能用来加组件自己不管的属性（外边距、定位、宽度），
  不是"覆盖内建样式"的通道。需要变的东西开成 prop，别指望调用方用 `className` 压。
- `style` 只在确有布局逃生需求时才开，开就必须是 `Omit<CSSProperties, ProtectedXxxStyleKey>`，
  把本组件承载红线的属性从类型层剔除，并在渲染时把 `...style` 展开在最前（照抄 `PixelFrame`）。
  类型层挡"写不出来"，展开顺序挡"覆盖不掉"，两道缺一不可。
- `ref` 需要被量测或被聚焦的控件才开，直接写进 props：`ref?: Ref<HTMLButtonElement>`。
  React 19 不用 `forwardRef`（已废弃），`PixelFrame` 就是现成写法。

### 1.2 严禁全量透传 HTML 属性

不许 `extends ButtonHTMLAttributes<HTMLButtonElement>`，不许 `...rest` 展开到根元素。三条理由：

1. 全量透传会把 `style` 里的红线属性从类型层重新漏回来，第 1.1 节那道防线直接作废；
2. 组件的真实 API 面变成"HTML 全集减去几个"，读源码的人无从知道哪些是被支持的；
3. 透传来的 `onKeyDown` / `onFocus` 会与组件自己的键盘处理静默互相覆盖，不报错。

需要哪个原生属性就显式声明哪个（`maxLength`、`inputMode`、`autoComplete` 之类逐个加）。

## 二、档位词汇（size / scale / tone / variant）

四个词各有严格分工，不许串用，也不许新造第五个维度。

### 2.1 `scale` —— 位图控件的整数放大倍率

用于渲染 16x16 位图源的控件：`PixelIcon`、`ItemIcon`、`PixelSlot`、`PixelSlotGrid`。

```ts
scale?: 1 | 2 | 3   // 默认 1
```

元素边长恒为 `16 * scale` 个像素格（类名 `h-16 w-16` / `h-32 w-32` / `h-48 w-48`）。
只能这么取：边长写成 16k 格时，实际缩放倍率 = k x（`--px` 的 px 数），恒为整数。
换成"任意格数"就会出现 12 格配 16 像素源图这种 0.75 倍缩放，症状是边缘糊一圈半透明像素而**不报错**
（硬红线第 4 条）。`--px` 的真实取值还没在真客户端标定，不能靠"当前是 2、除得尽"来赌。

同一排里的物品图标与功能图标必须取同一个 `scale`，否则两套图标的像素密度不同，一眼可见。

### 2.2 `size` —— 文本控件的三档

用于以文字为主体的控件：Button / Input / Select / Stepper / Checkbox / Badge / Currency / Progress /
Modal 等。

```ts
import type { PixelControlSize } from './controlSize'
import { PIXEL_CONTROL_PADDING_CLASS, PIXEL_CONTROL_TEXT_CLASS } from './controlSize'

size?: PixelControlSize   // 'sm' | 'md' | 'lg'，默认 'md'
```

union 与两张类表都在 `controlSize.ts` 里，**一律 import 复用，不许在自己文件里重写这三个字面量**
（理由与 tone 相同：加档时会漏改，且漏改不报错）。

三档的度量表（内容盒，单位是像素格，不含 9-slice 边框）：

| size | `PIXEL_CONTROL_PADDING_CLASS` | `PIXEL_CONTROL_TEXT_CLASS` | 行盒高 | 内容盒总高 |
|---|---|---|---|---|
| sm | `px-3 py-1` | `text-1x` | 16 格 | 18 格 |
| md | `px-4 py-2` | `text-1x` | 16 格 | 20 格 |
| lg | `px-6 py-3` | `text-2x` | 28 格 | 34 格 |

行盒高来自 `tailwind.config.ts` 的 `leading()`：`(--font-cell * n + 4) * --px`，`--font-cell` 当前是 12。

两张表拆开是为了让行内文本件（`PixelCurrency`、紧凑档的 `PixelBadge`）只取字号而不吃控件内边距；
需要盒子的控件把两张表拼起来。字号只有 1x/2x/3x 三个合法档——点阵字体在非设计档位的整数倍下必糊，
所以"想稍微大一点"只能靠间距，不能靠字号。

一条必须记住的账：控件若用 `PixelFrame` 包边，外框还要加 `2 * slice * --pixel-scale` 个 **CSS 像素**，
这条链走的是资产放大倍率而不是 `--px` 网格（理由见 `index.css` 里 `--pixel-scale` 一段）。
所以并排控件对齐时，必须把边框算进去，且同一行的控件必须取同一个 `--pixel-scale`（行内控件一律传
`scale={1}`，别继承 `:root` 的 2）。

### 2.3 `tone` —— 语义档

```ts
import type { PixelFrameTone } from './PixelFrame'
tone?: PixelFrameTone   // 'neutral' | 'accent' | 'success' | 'warning' | 'danger' | 'info'，默认 'neutral'
```

一律 `import type` 复用 `PixelFrameTone`，**严禁在别的文件里把这六个字面量再写一遍**（加第七档时会漏改）。
更严禁另造 `variant: 'primary' | 'secondary' | 'ghost'` 这类第二套语义词汇——那是 Web 默认审美的词表，
本设计系统的语义维度只有 tone 一个。

各档的用途：neutral 常规；accent 主行动（一屏至多一个）；success 完成态；warning 需注意；
danger 破坏性操作；info 中性提示。

### 2.4 `variant` —— 层级档，只有 PixelFrame 有

`window` / `panel` / `inset` 三档对应外凸窗口、平面板、内凹凹槽，**各自是一份独立资产**
（外凸与内凹的高光方向相反，换色表达不出来，见规格第七章）。

因此：任何其它组件都不许新增自己的 `variant` 维度。需要形状差异先报告，因为那意味着要美术出新资产，
不是前端能自己决定的事。

## 三、状态 props

### 3.1 `disabled?: boolean`（默认 false）

三件事必须同时做，缺一不可：

1. 原生可聚焦元素给原生 `disabled` 属性；自绘交互元素给 `aria-disabled={true}` 并去掉 `tabIndex`；
2. 换色到静默档（文字 `text-muted`，容器 tone 落回 `neutral`），不改尺寸；
3. **回调由组件自己拦住不触发**，不许指望调用方在 handler 里判 disabled。

### 3.2 `loading?: boolean`（默认 false）

只出现在会发起桥请求的控件（`PixelButton` 与以它为基的提交类）。

**严禁与 `disabled` 合并成一个 prop**：两者语义不同（不可用 vs 忙碌），无障碍表达不同
（`aria-disabled` vs `aria-busy`），生命周期也不同（loading 结束后控件恢复可用）。

要求：拒绝触发回调；外框尺寸不得跳变（忙碌态不许把文字整段换掉导致宽度变化）；
忙碌表达一律用 `PixelLoading`，**任何组件不得自绘动效**。

### 3.3 `invalid?: boolean`（输入类，默认 false）

换 `tone="danger"` + `aria-invalid={true}`。错误文案由面板给（放在控件外），控件不内置文案字典——
错误码中文化在服务端还没做（接线清单 A10），控件内置字典等于提前编造契约。

## 四、回调与受控

### 4.1 回调命名（冻结）

| 语义 | prop 名 | 参数 |
|---|---|---|
| 值变更 | `onChange` | 新值本身 |
| 选中项变更 | `onSelect` | 被选中项的 id / slot 号 |
| 点击 | `onClick` | 无参 |
| 关闭浮层 | `onClose` | 无参 |
| 确认 / 取消 | `onConfirm` / `onCancel` | 无参 |
| 重试 | `onRetry` | 无参 |

严禁 `onValueChange` / `onUpdate` / `handleXxx` 这类同义词。

**回调一律传领域值，不传 DOM 事件对象**。本库的控件多是自绘的，把 `MouseEvent` 抛给调用方等于让面板层
去猜哪个元素才是事件源。确实需要阻止冒泡一类的处理，在组件内部做完。

### 4.2 一律受控

承载业务值的组件不得用 `useState` 在内部兜底一份值。数据源是服务端权威（见架构真源），
组件内藏一份副本必然与回执打架，且乐观更新该由面板决定而不是控件。`value` 不传就是没有值。

纯本地视觉态（hover、focus、下拉展开、滚动位置）由组件自持，不上抛、不要求调用方管。

### 4.3 不许吞异常

组件不得 `try/catch` 包住调用方给的回调。异常必须自然冒泡，由最外层统一捕获。
同理，`?? 0` / `|| '未知'` 这类兜底在控件层一律禁止——缺值就是缺值，画出来必须能看出缺。

## 五、尺寸与间距

### 5.1 只用 spacing 键

`tailwind.config.ts` 已把 spacing 的键重定义为**像素格倍数本身**（`p-4` = 4 个像素格 = `calc(var(--px) * 4)`）。
可用档位只有这些：

```
0 1 2 3 4 5 6 7 8 10 12 14 16 20 24 32 40 48 64 80 96 128
```

缺档位不许用任意值 `w-[13px]`（eslint `tailwindcss/no-arbitrary-value` 已封），也不许在 `style` 里写裸 px。
真缺就报告给我加档，别在自己文件里绕。

### 5.2 严禁拼接类名

```ts
// 错：Tailwind 只扫源码里的完整字面量，拼出来的类不会被生成，且不报错——症状是"样式静默不生效"
className={`h-${String(cells)} w-${String(cells)}`}

// 对：档位穷举成完整字面量表（照抄 PixelIcon 的 SCALE_CLASS）
const TONE_TEXT_CLASS: Record<PixelFrameTone, string> = {
  neutral: 'text-fg',
  accent: 'text-accent',
  success: 'text-success',
  warning: 'text-warning',
  danger: 'text-danger',
  info: 'text-info',
}
```

用 `Record<字面量union, string>` 而不是 `Partial` 或普通对象：少写一档编译器报缺键，多写一档报多余键，
档位表与 union 的成对关系由 tsc 盯着。

同时要知道这条写法绕过了什么：eslint 的 `tailwindcss/no-custom-classname` 只看 `className` 属性，
常量表里的字符串它不校验。所以**表里每个类名都必须人工到 `tailwind.config.ts` 里核对确实存在**，
打错一个字母不会有任何提示。

### 5.3 不引入 `cn()` / `clsx` 一类 helper

理由同上：`eslint-plugin-tailwindcss` 只扫 `className` 属性和它认识的少数 callees，
`scripts/verify-pixel-guards.mjs` 的正则同样只匹配 `className=`。套一层自绘 helper 会让
`no-custom-classname` 与 `no-arbitrary-value` 对整个组件库静默失效。

条件类名写成模板字符串里的三元，两个分支都是完整字面量：

```ts
className={`${PIXEL_CONTROL_PADDING_CLASS[size]} ${PIXEL_CONTROL_TEXT_CLASS[size]} ${
  disabled ? 'text-muted' : TONE_TEXT_CLASS[tone]
}`}
```

### 5.4 行内 style 的唯一合法长度形式

只有 Tailwind 确实没有对应工具类时才用 `style`（`background-size`、`grid-template-columns` 之类），
且长度一律 `calc(var(--px) * n)`，禁裸 `px` / `rem`。`ItemIcon` 的占位块是现成例子。

## 六、颜色

### 6.1 只用语义 token 类名

| 用途 | 可用类名 |
|---|---|
| 背景三层 | `bg-bg`（页面底）`bg-surface`（面板底）`bg-raised`（悬停行/次级面板） |
| 前景 | `text-fg` `text-muted` `text-on-accent`（强调色填充块上的字） |
| 边框色 | `border-border` `border-border-strong` |
| 强调三态 | `bg-accent` `hover:bg-accent-hover` `active:bg-accent-active`（`text-accent` 同理） |
| 语义四色 | `success` `warning` `danger` `info`（可作 `text-*` / `bg-*` / `border-*`） |
| 硬阴影 | `shadow-hard` `shadow-hard-2` `shadow-hard-3` `shadow-none` |

严禁十六进制字面量、`rgb()` / `rgba()`、以及 `bg-accent/50` 这类 alpha 修饰。
半透明会把"离散状态色"变成不可预测的中间色，且与 9-slice 色层"必须不透明"的前提冲突（见 `README.md` 第六节）。

`--color-tone-*` 一律不得直接当颜色用：它是喂给 overlay 混合的**基色**，直接刷出来和最终框体色对不上
（同一支基色在 window/panel/inset 下算出三种不同的中心块色）。容器要上色，走 `PixelFrame` 的 `tone`。

### 6.2 两个必踩的坑

1. **颜色 token `border` 与边框宽工具类 `border` 同名。** 只写 `border` 拿到的是宽度，
   颜色会落到 Tailwind 的默认值 `#e5e7eb`（gray-200，实测确认）——一个不在本设计系统里的硬编码近白色，
   暗色档下是一条刺眼的白线。凡用边框宽类，必须同时显式给颜色：`border border-border`。
2. **亮暗双主题不需要任何 `dark:` 变体。** 颜色全部指向 CSS 变量，换主题只换变量。
   写 `dark:bg-xxx` 等于给组件多背一份颜色分支，且必然与 `:root.light` 那套打架。

## 七、交互态

### 7.1 一律靠换色

hover / active / focus / disabled 四态全部只换颜色，**零新增资产**（规格第七章的压缩原则：
状态与语义维度靠换色，只有层级维度出图）。

### 7.2 唯一被批准的位移

按下去的手感，只允许这一个配方：

```ts
'shadow-hard active:translate-y-1 active:shadow-none'
```

位移量必须是整格（`translate-y-1` = 1 个像素格）。**严禁 `translate-y-1/2` 一类分数档**——
那是百分比，必落半像素。严禁 `scale-*` / `rotate-*` / `skew-*`：非整数倍缩放与旋转必然产生半像素，
是硬红线第 4 条的直接违反。

### 7.3 禁平滑过渡

不许 `transition-*` / `duration-*` / `animate-*`。像素风没有渐变过渡，状态差必须是离散色的瞬时切换
（这也是 `index.css` 里 accent 要有三支独立 token 而不是靠调明度的原因）。

需要动效的场景（忙碌、开箱滚动）走整帧位图切换，且只在 `PixelLoading` 一处实现，别的组件不许自绘。

### 7.4 hover 不许改布局

hover 时不得改 padding / 字号 / 尺寸。布局跳动本身就是缺陷，而且改尺寸是半像素的又一个入口。

## 八、文件与命名

### 8.1 落点

- 通用控件一律 `src/components/pixel/<组件名>.tsx`，一个文件一个公开组件，文件名与组件名逐字相同。
- **具名导出，不用 default export**（default 导出会让重命名与全库搜索失效，与现有两个原语保持一致）。
- 仅供本组件内部使用的子件留在同一文件且不导出。确需拆文件的放同目录、以父组件名开头
  （`PixelTableRow.tsx`），且**不进 barrel**。

### 8.2 barrel（`index.ts`）的并行编辑纪律

每个组件落地时，在 `src/components/pixel/index.ts` 的导出区按字母序**追加一行**：

```ts
export * from './PixelButton'
```

这是并行开发下唯一会被多人同时改的文件。改之前重新 `Read`，只加自己那一行，
遇到冲突就重读重加——别整段重写，那会把别人刚加的行冲掉。

### 8.3 目录边界（一眼可查的判据）

`src/components/pixel/` 下的文件**严禁 import** `lib/bridge`、`lib/types`、`lib/actions`、`router`。
控件不认识业务与桥，数据一律由面板经 props 注入。这条判据用一次 grep 就能查，不要越线。

`PixelCurrency` 是唯一碰到领域概念的控件，它也只在自己文件里声明
`type PixelCurrencyKind = 'credit' | 'azure'`，不 import 契约类型。
注意 `market.place` 的 `currency: 'CREDIT'` 是**大写**的传输契约，与展示层这套小写枚举不是一个东西，
不许互相套用（前者是发给服务端的值，后者只决定画哪个币种图标）。

### 8.4 命名

| 种类 | 形式 | 例 |
|---|---|---|
| 组件 | `Pixel<名词>`（物品图标例外，沿用 `ItemIcon`） | `PixelButton` |
| props 接口 | `<组件名>Props` | `PixelButtonProps` |
| 档位 union | `<组件名><概念>` | `PixelIconName` / `PixelIconScale` |
| 导出常量清单 | 全大写下划线 | `PIXEL_ICON_NAMES` |

### 8.5 注释

文件头注释必须写清"这东西凭什么是一个组件"以及与红线相关的取舍，**不复述代码字面意思**。
叙述一律简体中文，标识符与 API 名保留原文。**零 emoji**（含颜色圆点、勾叉标记）。
状态用 PASS/FAIL，严重度用 Critical/Major/Minor，勾选用 `[x]` / `[ ]`。

严禁 `// TODO: implement` 与空壳实现。缺信息就报告缺什么，不要静默留空。

## 九、无障碍下限

MCEF 内嵌浏览器里鼠标定位可能不精准（DPI 与 GUI Scale 叠加，规格第十二章仍是 PENDING），
**键盘必须是一条完整可用的通路**，不是可选增强。以下十条是下限，不是目标。

1. 可交互元素一律用原生可聚焦标签（`<button type="button">` / `<input>`）。
   `<button>` 必须显式写 `type="button"`——默认值是 `submit`，一旦被包进 `<form>` 就会触发提交。
   自绘交互元素必须**同时**给 `role` + `tabIndex={0}` + 键盘处理，三者缺一等于做了个鼠标专用控件。
2. Enter 与 Space 必须等价于点击。原生 `<button>` 自带；自绘元素自行处理 `keydown` 并对 Space
   `preventDefault()`（否则会滚页）。
3. Esc 关闭浮层：Modal / ConfirmDanger / Select 展开态 / Tooltip。
4. 方向键导航：Tabs 左右切换，下拉与列表上下移动，SlotGrid 四向移动。
5. 焦点必须肉眼可辨。原生 focus ring 是带圆角的矢量描边，与像素风冲突，可以 `outline-none`，
   但**必须换成 `focus-visible:` 的换色或换边框**（如 `focus-visible:border-border-strong`）。
   只关不换视为缺陷。
6. 浮层打开时焦点移入浮层，关闭时还回触发元素，且焦点不得跑出浮层——MCEF 里没有第二个窗口可切，
   焦点丢了就只能用鼠标救。
7. 图标是唯一语义载体时（纯图标按钮）必须给名字；与文字并列时不给（`aria-hidden`），否则读屏读两遍。
   两个图标组件对"不给 `label`"的处理**不同**，别混：`PixelIcon` 省略即视为装饰性（`aria-hidden`）；
   `ItemIcon` 省略则回退为 `itemId`——物品图标基本不存在纯装饰的用法（它标识的是一件可交易的物品），
   整块对读屏隐藏比读一遍 id 更糟。物品与名字并排时，把 `useItemNames` 解出的显示名传进 `label`，
   而不是省略它。
8. **无障碍名 prop 一律叫 `label`**，不叫 `alt` / `title` / `ariaLabel`。
9. 禁用 `title` 属性做提示：原生 tooltip 由系统渲染，既不是像素风，延迟也不可控。提示一律走 `PixelTooltip`。
10. **中文输入当前是 BLOCKED**（接线清单 A14：MC EditBox 叠加未实现）。文本输入控件必须在只有
    数字与英文输入的前提下可用，严禁把中文输入当既有能力去设计交互（比如"必须输入中文关键字才能搜索"）。

## 十、组件词汇表（名字已冻结）

只锁 props 的**名字与语义**，不锁实现。表里没列到的 props 自行按前九节的规矩加。

### L1

| 组件 | 冻结的 props | 备注 |
|---|---|---|
| `PixelButton` | `tone` `size` `disabled` `loading` `icon?: PixelIconName` `label?` `onClick` `children` | 纯图标按钮 = 只给 `icon` 不给 `children`，此时 `label` 必填 |
| `PixelInput` | `value` `onChange(next: string)` `placeholder?` `disabled` `invalid?` `size` `maxLength?` | 见九-10 的中文输入约束 |
| `PixelSelect` | `value` `options: readonly {value,label}[]` `onChange(next)` `disabled` `size` | 自绘下拉；原生 `<select>` 的弹层是系统渲染，不许用 |
| `PixelStepper` | `value: number` `onChange(next: number)` `min` `max` `step?` `disabled` `size` | 市场的数量/价格输入靠它绕开中文输入 |
| `PixelCheckbox` | `checked` `onChange(next: boolean)` `label` `disabled` `size` | `label` 必填且点击区包含文字 |
| `PixelSlot` | `itemId?` `count?` `label?` `selected?` `disabled?` `onClick?` `scale` | 不给 `itemId` 即空槽；底用 `PixelFrame variant="inset"` |
| `PixelSlotGrid` | `slots` `columns: number` `selectedSlot?` `onSelect(slot: number)` `scale` | `slot` 号沿用服务端槽位索引，不要另编下标 |
| `PixelTable` | `columns` `rows` `rowKey(row) => string` `onRowClick?` `emptyHint?` | 泛型行类型；单元格渲染由 `columns[].render` 给 |
| `PixelScrollArea` | `children` `className?` `orientation?` | 高度由调用方经 `className` 给（组件本身不设高，无冲突）；原生滚动条是圆角矢量必须自绘 |
| `PixelTabs` | `tabs: readonly {id,label,icon?}[]` `activeId` `onChange(id: string)` | |
| `PixelProgress` | `value: number` `max: number` `tone` `size` `label?` | 越界值按端点画，但不许用 `?? 0` 把缺值抹平 |
| `PixelBadge` | `tone` `size` `children` | |
| `PixelTooltip` | `content` `children` `placement?` | 必须同时响应 hover 与 focus |
| `PixelCurrency` | `amount: number` `currency: 'credit' \| 'azure'` `size` `showIcon?` | 千分位分组只在本组件内实现，面板不许自己拼字符串；大小写陷阱见八-3 |

### L2

| 组件 | 冻结的 props | 备注 |
|---|---|---|
| `PixelLoading` | `label?` `size` | 全库唯一允许实现动效的地方 |
| `PixelEmpty` | `title` `hint?` `icon?: PixelIconName` | |
| `PixelError` | `message` `code?` `onRetry?` | 不内置错误码字典（A10 未做） |
| `PixelConfirmDanger` | `open` `title` `message` `confirmLabel` `onConfirm` `onCancel` `loading` | 打开时默认焦点落"取消" |
| `PixelModal` | `open` `title` `onClose` `children` `size` | 用 `PixelFrame variant="window"` |
| `PixelToast` | `tone` `message` `onDismiss` | 纯展示件。队列与挂载点本批**不定**——那要动全局布局，而路由与 `App.tsx` 由 hub 统一维护 |

## 十一、不许动的东西

| 文件 | 原因 |
|---|---|
| `tailwind.config.ts` / `src/styles/index.css` | token 与档位表是全库单点。缺档位/缺颜色先报告，12 个 agent 各加各的必然打架 |
| `package.json` | 并行批次会冲突；且矢量图标库与非点阵字体包已被守卫封死，本来也加不进来 |
| `src/router.ts` / `src/App.tsx` | 路由表由 hub 统一维护 |
| `src/mock/` | 由 mock 批次统一维护 |
| `PixelFrame.tsx` / `PixelIcon.tsx` | L0 原语是全部控件的地基；且 `verify-pixel-guards.mjs` 用正则读 `PIXEL_FRAME_ASSETS`，改写法会让守卫直接判失败 |

六条硬红线的工程保证（Tailwind theme 覆盖、`corePlugins` 关停、stylelint 规则、
`scripts/verify-pixel-guards.mjs`）一律不许绕过。**严禁**用 `eslint-disable` / `stylelint-disable`
去关这些规则——`index.css` 里那三处 `stylelint-disable` 是登记在案的例外，例外的总数是可数的，
新增一处就是破线。

## 十二、tsconfig 的四个严格档（会咬人）

1. `exactOptionalPropertyTypes: true`：往 `prop?: T` 上传 `prop={undefined}` 是**编译错误**。
   条件传值写成展开：`{...(x === undefined ? {} : { prop: x })}`（`PixelFrame` 里有现成的）。
2. `noUncheckedIndexedAccess: true`：数组下标与索引签名取值的类型是 `T | undefined`，必须显式判空。
   这也是档位表要写成 `Record<字面量union, T>`（不受影响）而不是数组的原因。
3. `verbatimModuleSyntax: true`：只用于类型的导入必须写 `import type`，否则报错。
4. `noUnusedLocals` / `noUnusedParameters`：留着"以后要用"的变量过不了编译。

## 十三、交付前自检（必须全绿才算完成）

```
pnpm exec tsc --noEmit
pnpm exec eslint .
node scripts/verify-pixel-guards.mjs
pnpm exec stylelint "src/**/*.css"     # 仅当改了 CSS
```

机器查不到、必须人工过的两条：

- [ ] className 常量表里的每个类名，都在 `tailwind.config.ts` 里确实存在（拼错不报错，只是没样式）；
- [ ] 每个可交互元素用键盘走一遍：Tab 能聚焦、焦点看得见、Enter/Space 能触发、Esc 能退出浮层。
