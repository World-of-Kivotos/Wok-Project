# webui — 游戏内 Web UI 前端

Forge mod `miningdim` 的游戏内 UI 前端。**唯一渲染目标是 MCEF 内嵌的 Chromium**，不面向公网多浏览器，
因此工程内跨浏览器兼容妥协为零：无 polyfill、无 autoprefixer、无 Firefox/Safari 回退分支。

## 视觉风格

**经典白到黑中性灰阶 + 单一可调强调色。** 技术栈是 React 19 + Tailwind v4 +
[Coss UI](https://coss.com/ui)（建在 Base UI 之上的 copy-paste 组件库，源码进仓库，非运行时依赖）。

- 中性色一律零彩度（`oklch(L 0 0)`）。带色相的"灰"与游戏 3D 画面的场景光叠加后会漂移，
  表现为同一个面板在草原和洞穴里不是一个颜色。
- 强调色只开放**色相与彩度两个自由度，亮度锁死**——设置页的取色器因此不可能调出看不清的组合。
  见 `src/lib/brand.ts`。它只用于焦点环、当前导航项、进度条填充、选中行这些小面积位置；
  主按钮走的是近白/近黑的 `primary`。

**像素风不是作废，是推迟。** `docs/PixelUI_DesignSystem_DesignSpec.md` 全文标 DEFERRED，
旧实现与资产封存在 `_pixel-archive/`（含重启步骤与上一轮的实测教训）。

## 渲染目标的特性基线（已核实）

MCEF `2.1.6-1.20.1` 内嵌 **Chromium 116.0.5845.190**（上游 README 载明）。本工程用到的所有
现代 CSS 特性都在这条基线之内，逐条对照过：

| 特性 | 起始版本 | 用在哪 |
| --- | --- | --- |
| Tailwind v4 官方基线 | Chrome 111 | 整个样式层 |
| `oklch()` | Chrome 111 | 全部颜色令牌 |
| `color-mix()` | Chrome 111 | Tailwind 的透明度修饰符（`bg-brand/12`）、`outline-color` |
| CSS 嵌套 | Chrome 112 | Tailwind 产出的样式 |
| `:has()` | Chrome 105 | Coss UI 组件的状态选择器 |
| `text-wrap: balance` | Chrome 114 | Coss 的 `Empty` / `Alert` |

**刻意避开的**：`oklch(from …)` 相对颜色语法（Chrome 119 才有，超出基线）。强调色因此走
"三个通道各自一个变量"的写法（`oklch(0.64 var(--brand-c) var(--brand-h))`）而不是从基色派生。
改颜色系统前先确认新写法仍在 116 之内——超出基线的表现是**颜色整个失效变透明**，而不是报错。

## 分层纪律

```
src/components/ui/    Coss UI 的 copy-paste 产物。上游代码，只在必要时增补档位。
src/components/kit/   本项目的 UI 契约层。签名表见 src/components/kit/README.md
src/pages/            业务页。只从 '@/components/kit' 导入，不直接碰 @/components/ui/*
```

第三条是换皮成本的全部来源。上一版界面因为页面直接消费视觉组件，换皮要重写 8000 行；
现在换皮只需改 kit 的内部实现。**唯一例外**是功能图标直接 `import { XxxIcon } from 'lucide-react'`。

真源文档（改任何设计参数前先读）：

- `../docs/WebUI_Architecture_DesignSpec.md` — 数据地基（桥、服务端权威、分发方式）
- `../docs/WebUI_Frontend_Wiring_Checklist.md` — 接线总表与决策记录 J1-J12
- `src/components/kit/README.md` — 组件契约（业务页唯一该读的那份）
- `../docs/PixelUI_DesignSystem_DesignSpec.md` — 视觉地基，**当前 DEFERRED**

## 开发

```
pnpm install
pnpm dev        # http://localhost:5173/ (strictPort, 端口被占直接失败)
pnpm build      # tsc --noEmit + vite build
pnpm lint       # eslint
pnpm lint:css   # stylelint
```

客户端配置 `webui.url` 默认指向 `http://localhost:5173/`。dev server 开了 `host: true`，
MCEF 客户端不在本机时把该配置改成开发机的局域网地址即可。

设计评审入口：假数据模式下侧栏底部的「组件与配色预览」（`#/components`）——
一屏穷举全部语义档与控件尺寸，换皮回归时先看这一页。

## 与 Java 侧的桥接契约

- 入站：`window.miningdimQuery({request, onSuccess, onFailure})`，封装见 `src/bridge/query.ts`
- 下行事件：页面预置 `window.miningdimOnEvent(name, dataJson)`，由 `src/bridge/events.ts` 在 React 挂载时注册
- 客户端本地 action：`client.i18n`（翻译键 -> 显示名），不走服务端往返

## 两条与视觉无关、换皮后依然成立的硬约束

1. **UI 严禁放进 iframe，运行期严禁改 `location`。** 宿主对 cefQuery 的授权是整串 URL 精确匹配
   （`WebUiBridge.onQuery`），而 CEF 的 `getURL()` 带 fragment。页面一旦改 hash，此后所有 cefQuery
   会被以 -3 拒绝——症状是"界面能翻页但所有数据请求全废"。路由实现见 `src/router.ts` 的头注释。
2. **`callMock` 的 planned 分流在生产构建下必须硬失败。** 见 `src/mock/handlers.ts`。缺了这道门，
   50 条尚未接线的假 action 会在真客户端里由内存世界作答。

## mod 贴图挂载

`vite.config.ts` 的 `modTexturesPlugin` 把 mod 的 `item/` 与 `block/` 贴图挂到 `/mc/` 下
（dev 走中间件，build 期复制进 `dist/mc/`）。刻意不用 `publicDir` 指过去——那会让 `public/` 变成
一个"看着像 public 实际不被服务"的假目录。`/mc/` 下任何不解析的请求必须真回 404，
不能落进 SPA fallback：`ItemIcon` 的三层回退链正是靠 404 逐级下探的。
