# webui — 游戏内 Web UI 前端

Forge mod `miningdim` 的游戏内 UI 前端。**唯一渲染目标是 MCEF 内嵌的 Chromium**，不面向公网多浏览器，
因此工程内跨浏览器兼容妥协为零：无 polyfill、无 autoprefixer、无 Firefox/Safari 回退分支。

真源文档（改任何设计参数前先读）：

- `../docs/PixelUI_DesignSystem_DesignSpec.md` — 视觉地基，第二章六条硬红线是验收标准
- `../docs/WebUI_Architecture_DesignSpec.md` — 数据地基（桥、服务端权威、分发方式）
- `../docs/WebUI_Frontend_Wiring_Checklist.md` — 接线总表与决策记录 J1-J12

## 开发

```
pnpm install
pnpm dev        # http://localhost:5173/ (strictPort, 端口被占直接失败)
pnpm build      # 守卫脚本 + tsc --noEmit + vite build
pnpm lint       # eslint (含矢量图标禁令与 Tailwind 任意值禁令)
pnpm lint:css   # stylelint (含裸长度值禁令与 border-radius 白名单)
```

客户端配置 `webui.url` 默认指向 `http://localhost:5173/`。dev server 开了 `host: true`，
MCEF 客户端不在本机时把该配置改成开发机的局域网地址即可。

## 与 Java 侧的桥接契约

- 入站：`window.miningdimQuery({request, onSuccess, onFailure})`，封装见 `src/bridge/query.ts`
- 下行事件：页面预置 `window.miningdimOnEvent(name, dataJson)`，由 `src/bridge/events.ts` 在 React 挂载时注册
- 客户端本地 action：`client.i18n`（翻译键 -> 显示名），不走服务端往返

宿主对 cefQuery 的授权是**整串 URL 精确匹配**（`WebUiBridge.onQuery`），因此：
UI 严禁放进 iframe，运行期严禁改 `location`。路由实现见 `src/router.ts` 的头注释。

## 度量

全库唯一的基准长度字面量是 `src/styles/index.css` 里的 `--px`，其余尺寸一律 `calc(var(--px) * n)` 派生。
当前 `--px` 为占位值，按规格第十二章须在批 1 真客户端标定后回填，改这一处即可整套缩放。
