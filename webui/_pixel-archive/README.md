# _pixel-archive —— 像素风实现的封存区

这里放的是 2026-08-13 之前那一版**像素风**游戏内 UI 的全部实现。它没有被删除，因为像素风只是
**推迟**（`docs/PixelUI_DesignSystem_DesignSpec.md` 全文已标 DEFERRED），不是作废——先开服，
像素化风格后面慢慢跑。

本目录不参与构建：不在 `tsconfig.json` 的 `include` 里，不被 eslint 检查，vite 也不会打包它。
改动这里的文件对线上产物零影响。

## 目录内容

| 路径 | 内容 |
| --- | --- |
| `src/components/pixel/` | 23 个像素控件 + `conventions.md`（props/档位/无障碍约定，是强制依据）+ `README.md`（9-slice 与灰度上色的选型推导） |
| `src/pages/PixelCheckPage.tsx` | 像素单点验证页（`#/pixel-check`）：像素网格、9-slice 边框、字体渲染的真机取样 |
| `src/pages/ColorCheckPage.tsx` | 配色对照页（`#/color-check`）：并排看染色/未染色框，用来判断 `mix-blend-mode` 在 MCEF 里是否真生效 |
| `src/pages/ComponentsPage.tsx` | 旧版组件预览页（新版同名文件已在 `src/pages/` 下重写，用的是新组件） |
| `src/dev/assertPixelGrid.ts` | 运行期守卫：`--px` 必须是整数 px |
| `tools/gen-nineslice.mjs` | 9-slice 边框资产生成器 |
| `tools/gen-icons.mjs` | 16×16 功能图标生成器（与 `PixelIcon` 的名字表双向校验） |
| `public/ui/` | 三张 9-slice 边框 PNG + 26 张 16×16 图标 PNG |
| `VISUAL_REVIEW.md` | 对这一版的机械化批判。**重启前必读**，1.1 节的语义色使用率统计与 1.5 节的度量问题是最有价值的部分 |

## 重启像素风时怎么做

**不要**把这些文件整体挪回 `src/`。业务页现在只依赖 `src/components/kit/` 这一层契约
（`<Panel>` / `<Tag tone="danger">` / `<Meter>` 这类语义签名），正确做法是**用像素实现重写 kit 的内部**，
业务页一行不动。这正是当初那次换皮要重写 8000 行、而下一次不必的原因。

大致步骤：

1. 先读 `docs/PixelUI_DesignSystem_DesignSpec.md` 顶部的 DEFERRED 说明——那四条是上一轮实测出来的
   教训，尤其第 1 条（边框吃光内容盒）是"丑"的主因，必须先解决再动手。
2. 把本目录 `src/components/pixel/` 拷回 `src/components/pixel/`，恢复 `public/ui/` 资产。
3. 恢复构建期守卫：`scripts/verify-pixel-guards.mjs` 与 `assertPixelGrid.ts`
   （前者已随本次换皮删除，从 git 历史取回：`git log --diff-filter=D -- webui/scripts/verify-pixel-guards.mjs`）。
4. **改 `src/components/kit/` 的实现，保持其全部导出签名不变**（签名表见 `src/components/kit/README.md`）。
5. 恢复 `#/pixel-check` 与 `#/color-check` 两条路由（`src/router.ts` + `src/App.tsx`）。

## 与像素风一起撤除、但与视觉无关的东西

这两条在换皮时**没有**被撤除，将来也不能撤——它们与视觉风格正交：

- `src/router.ts` 只读 `location.hash`、运行期绝不写 `location`（宿主授权是整串 URL 精确匹配）。
- `src/mock/handlers.ts` 里 planned action 的生产构建硬失败门（否则假世界会进真客户端）。

## 与 Tailwind 版本有关的一处提醒

封存时工程是 Tailwind v3 + `tailwind.config.ts`（`borderRadius` 只剩 `none`、`spacing` 按 `--px` 重定义、
`corePlugins` 关掉 blur/dropShadow）。换皮时已升到 **Tailwind v4**（CSS-first 配置，无 config 文件）。
`components/pixel/` 里的类名（`text-1x` / `p-4` = 8px / `shadow-hard`）全部是按 v3 那套配置写的，
重启时要么在 v4 的 `@theme` 里重建等价档位，要么逐个换算——直接拷回去会静默无样式。
