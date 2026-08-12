# kit —— 业务页唯一的 UI 契约层

## 分层纪律

```
src/components/ui/    Coss UI 的 copy-paste 产物 (@coss/ui, 建在 Base UI 之上)。上游代码, 只在必要时增补档位。
src/components/kit/   本项目的 UI 契约层。把 Coss 原语按本项目语义词汇收敛成朴素受控签名。
src/pages/            业务页。只从 '@/components/kit' 导入, 不直接碰 @/components/ui/*。
```

违反第三条就等于把换皮成本重新装回 15 个页面里。上一版界面正是因为页面直接消费视觉组件, 换皮才要
重写 8000 行; 而用户明确要求像素风"后面慢慢跑"(`docs/PixelUI_DesignSystem_DesignSpec.md` 标 DEFERRED,
旧实现封存在 `webui/_pixel-archive/`), 届时要改的只是 kit 的内部实现。

**唯一例外**: 功能图标直接 `import { XxxIcon } from 'lucide-react'`。图标是内容不是控件, 收进 kit
只会得到一张永远补不全的名字白名单 —— 上一版的 `PixelIcon` 就是这么卡住的 (26 个名字, 首页/矿洞/
图鉴/开箱/职业五个入口因为没有对应图标, 整条导航只好一个图标都不给)。

## 两条与视觉无关、换皮后依然成立的硬约束

1. **router 只读 hash, 运行期绝不写 `location`**。宿主的授权判定是整串 URL 精确匹配
   (`WebUiBridge.onQuery` 要求 `cefBrowser.getURL()` 等于登记的 `webui.url`), 而 CEF 的 `getURL` 带
   fragment。页面一旦改 hash, 此后所有 `cefQuery` 被以 -3 拒绝 —— 症状是"界面能翻页但所有数据请求全废"。
   详见 `src/router.ts` 文件头。
2. **`callMock` 的 planned 分流必须在生产构建下硬失败**。见 `src/mock/handlers.ts`。缺了这道门,
   50 条尚未接线的假 action 会在真客户端里由内存世界作答。

## 导入方式

```tsx
import { Panel, Button, Currency, Tag, LoadingBlock } from '@/components/kit'
```

## 语义词汇

```ts
type Tone = 'neutral' | 'brand' | 'success' | 'warning' | 'danger' | 'info'
type ControlSize = 'sm' | 'md' | 'lg'
const TONES: readonly Tone[]              // 穷举用, 加档只改 tokens.ts
const CONTROL_SIZES: readonly ControlSize[]
```

`brand` 是用户可调的强调色 (设置页取色器写 `--brand-h` / `--brand-c`, 见 `src/lib/brand.ts`),
只用于焦点环、当前导航项、进度条填充、选中行这些**小面积**位置。主按钮走的是近白/近黑的 `primary`,
不是 brand —— 让 default 跟着强调色走等于把整个界面的基调交给取色器。

## 组件清单

### 容器

| 组件 | 签名 |
| --- | --- |
| `Panel` | `{ title?, description?, actions?: ReactNode, padded?=true, children, className? }` 带标题的卡片分区。页面的主要结构单元。 |
| `Surface` | `{ tone?='neutral', children, className? }` 轻量着色块。包住一段属于某个语义状态的内容。 |

### 控件

| 组件 | 签名 |
| --- | --- |
| `Button` | Coss 原件直转。`variant`: `default` \| `brand` \| `secondary` \| `outline` \| `ghost` \| `link` \| `destructive` \| `destructive-outline`；`size`: `xs` \| `sm` \| `default` \| `lg` \| `xl` \| `icon` \| `icon-xs` \| `icon-sm` \| `icon-lg` \| `icon-xl`；另有 `loading` / `disabled`。注意档位名是 `default` 不是 `md`。 |
| `TextInput` | `{ value, onChange(next: string), placeholder?, disabled?, invalid?, size?, maxLength?, type?: 'text'\|'search'\|'password', onRequestEdit?, className? }` |
| `NumberInput` | `{ value, onChange(next: number), min, max, step?=1, disabled?, size?, className? }` 带增减按钮。 |
| `Dropdown<T extends string>` | `{ value, onChange(next: T), options: {value: T, label: string, disabled?}[], placeholder?, disabled?, size?, className? }` |
| `Toggle` | `{ checked, onChange(next: boolean), label, disabled?, size?, className? }` 复选框 + 文字。 |
| `Meter` | `{ value, max, tone?='brand', size?, label?, valueText?, bare?, className? }` 进度条。`max` 必须为正。 |
| `TabBar` | `{ tabs: {id, label, disabled?, badge?}[], activeId, onChange(id), variant?: 'default'\|'underline', className? }` |
| `Hint` | `{ content: ReactNode, children }` 悬停提示。`TooltipProvider` 已挂在 App 根部。 |

### 状态与回执

| 组件 | 签名 |
| --- | --- |
| `LoadingBlock` | `{ label?, size?, className? }` |
| `ErrorBlock` | `{ message, code?, onRetry?, className? }` 带 `role="alert"`。 |
| `EmptyBlock` | `{ title, hint?, icon?: ReactNode, action?: ReactNode, className? }` icon 传 lucide 元素。 |
| `FeedbackAlert` | `{ tone: 'neutral'\|'success'\|'warning'\|'danger'\|'info', message, title?, action?, className? }` 写操作回执条。**message 放服务端回执原文, 不改写**。 |
| `ConfirmDangerDialog` | `{ open, onOpenChange(open), title, message, confirmLabel, onConfirm, loading?, confirmWord? }` 破坏性操作二次确认。给 `confirmWord` 则要求逐字输入才解锁。 |

### 展示

| 组件 | 签名 |
| --- | --- |
| `Currency` | `{ amount, currency: 'credit'\|'azure', size?, showIcon?=true, signed?, className? }` 双货币。`signed` 时正负自动着色并补 `+` 号。 |
| `formatAmount(n)` | 千分位格式化, 单独导出供拼字符串时用。 |
| `Stat` | `{ label, value: ReactNode, hint?, layout?: 'stacked'\|'inline', className? }` 标签-数值对。 |
| `Tag` | `{ tone?, size?: 'sm'\|'default'\|'lg', children, className? }` 语义徽标。**业务页用这个, 不要直接用 Coss 的 Badge**。 |
| `ItemIcon` | `{ itemId, label?, scale?: 1\|2\|3 }` MC 物品贴图 (三层回退: 原版镜像 → 本 mod 贴图 → 棋盘占位块)。 |
| `ItemSlot` | `{ itemId?, count?, label?, selected?, disabled?, onClick?, scale?, tabIndex?, className? }` 物品格。空格子仍占位。 |
| `ItemSlotGrid` | `{ slots: {itemId?, count?, label?, disabled?}[], columns, selectedSlot?, onSelect(slot), scale?, label?, className? }` 方向键导航 + roving tabindex。 |
| `DataTable<TRow>` | `{ columns, rows, rowKey(row), onRowClick?, selectedRowKey?, emptyHint?, className? }`；列: `{ key, header, render(row), sortValue?(row), numeric? }` 给了 `sortValue` 该列表头可排序 (升 → 降 → 无)。 |
| `Separator` / `Skeleton` | Coss 原件直转。 |

## 排版约定

标题层级只有两级, 别自己发明第三级:

- 页面标题由 `TabletShell` 统一渲染 (`ROUTE_TITLES`)。**页面内不要再画一遍页名** —— 上一版有四个页面
  各自渲染了一个比外壳更大的 h1, 打开必现两遍。
- 分区标题走 `<Panel title="...">`, 不要手写 `<h2>`。
- 分区内的小标题用 `<h3 className="font-medium text-sm text-foreground">`。

字号一律用 Tailwind 默认档 (`text-xs` / `text-sm` / `text-base` / `text-lg`)。正文默认 `text-sm`,
辅助说明 `text-xs text-muted-foreground`。

## exactOptionalPropertyTypes

tsconfig 开着这个档。kit 的全部可选 props 都显式写了 `| undefined`, 所以可以直接把可选 state
传进去 (`selectedSlot={maybeUndefined}`), 不需要条件展开。

## 补充档位 (2026-08-13 换皮收尾时按各页反馈补的)

- `FeedbackAlert` 增加 `onDismiss` 与 `autoDismissMs`：给了 `onDismiss` 就渲染关闭按钮并默认 4 秒
  自动消失（沿用像素版 `PixelToast` 的行为；那份实现的"最新回调 ref"注释一并搬了过来，理由见源码）。
  `action` 槽由本组件负责套 `AlertAction` —— Coss 的 Alert 靠 `data-slot="alert-action"` 决定网格列，
  裸节点会掉到第二行。业务页直接传按钮即可。
- `Meter` 增加 `thresholds`：轨道上的参考刻度（TPS 的 15 那条线、精通度的升段坎）。
- `ItemSlotGrid` 的键盘焦点改为组件内部状态，不再跟随 `selectedSlot`。
  调用方常常拒绝某些格子的选中（挂单页对空格子的 `onSelect` 直接 return），焦点若跟着选中走，
  方向键碰到第一个空格就再也推不动。**焦点是"我在看哪一格"，选中是"我要哪一格"，两者不是一回事。**
