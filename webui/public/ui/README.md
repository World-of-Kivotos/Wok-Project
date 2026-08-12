# 9-slice 边框资产（占位）

本目录的 3 张 PNG 是**程序生成的占位资产**，不是美术产出。存在的唯一目的是让 `PixelFrame` 与
`border-image` 链路在美术交付前就能跑通并接受真客户端验证（接线清单批 1 单点验证）。

真源规格：`docs/PixelUI_DesignSystem_DesignSpec.md` 第四章 4.4（资产规格）、第六章（灰度上色）、
第七章（资产清单压缩原则）。

## 一、资产清单与规格

| 文件 | 层级用途 | 尺寸 | slice | 明暗方向 |
|---|---|---|---|---|
| `frame-window.png` | 外凸窗口框（平板 / 弹窗） | 24x24 | 8 | 左上亮、右下暗 |
| `frame-panel.png` | 平面板（分区 / 卡片） | 24x24 | 8 | 无方向（均匀） |
| `frame-inset.png` | 内凹凹槽（输入框 / 列表底 / 进度槽） | 24x24 | 8 | 左上暗、右下亮 |

共同规格：

- **PNG 8 位灰度 + alpha**（colorType 4）。资产只提供形状与明暗关系，不携带任何色相。

  > 现状澄清（Major，未接线）：规格第六章"颜色一律由 CSS 变量给"这一半**在 9-slice 边框上尚未实现**。
  > `border-image` 是把位图原样画上去的，中间没有 mask/tint 环节，因此这三张图当前就以自身灰度显形，
  > `--color-*` 一个都影响不到它们；`fill` 中心块还会盖住元素自己的 `background-color`。
  > 规格 6.1 的单色蒙版（`background-color` + `mask`）只适用于图标那类单通道形状，套不到需要保留斜面
  > 明暗的边框上——边框上色要另走 `mask-border` 或分层合成，属未决方案。在它落地前，**换色只对边框
  > 以外的部分成立**，评审配色时不要把边框的灰当成"主题色没配好"。
- **1x 像素密度，严禁预放大**。放大交给 `image-rendering: pixelated` 与整数倍 `border-width`。
- **alpha 只有 0 与 255 两种取值**，无抗锯齿、无渐变模糊；四角各挖空 1 像素，形成阶梯状硬边圆角。
- 24 = slice x 3，故四角 / 四边 / 中心恰好各 8x8。

三档为什么必须是三张图而不是一张换色：外凸与内凹的斜面明暗方向相反，`background-color` /
`mask` 换色改不出明暗方向。状态（normal/hover/pressed/disabled）与语义（普通/强调/危险）
则**不得**增发资产，一律靠换色表达。

## 二、美术替换规则

美术产出后**按同尺寸（24x24）、同 slice（8）直接覆盖同名文件即可**，前端零改动。

若美术必须改变边框粗细（即 slice 值变化）：

1. `slice` 值必须与资产实际边框像素宽**精确相等**，差一像素即整体错位，且错位在小尺寸控件上尤其刺眼。
2. 资产的 slice 与 CSS 的 `border-image-slice` 是**成对维护**的一对值，改一处必须同步改另一处。
   前端侧的取值由 `PixelFrame` 从资产元数据（`{ src, slice }`）按 variant 读入，三档层级各持有独立值——
   `src/styles/index.css` 刻意没有给 `--pixel-slice` 全局默认值，正是为了不制造"看起来能用但和资产对不上"的错位来源。
3. `border-width` 必须取 slice 的**整数倍**（`calc(var(--pixel-slice) * var(--pixel-scale) * 1px)`），
   非整数倍破坏像素对齐。
4. 边框含图案时 `border-image-repeat` 用 `round`；纯色细边才可用 `stretch`。

替换后请跑一次 `node tools/gen-nineslice.mjs --check`：它会报告现有文件与生成结果不一致（预期行为，
说明占位资产已被真资产取代），此时可连同生成脚本一并删除。

## 三、边条均匀性约束（拉伸正确性的前置条件）

占位资产的四条边沿延展轴是**均匀的**（横边每行沿 x 恒定、竖边每列沿 y 恒定），中心块是单一灰度值。
这使得 `border-image-repeat` 取 `round` / `repeat` / `stretch` 三种模式结果完全一致，把"模式选错"
这个变量从批 1 验证里消掉。

美术资产可以在边上带图案（此时必须用 `round`），但**中心块必须保持单一值或可双轴拉伸的纯色**，
任何中心图案都会在大尺寸容器上被抹成条带。

## 四、重新生成

```
node tools/gen-nineslice.mjs          # 写盘并自检
node tools/gen-nineslice.mjs --check  # 只校验一致性, 不写盘
```

生成器（`webui/tools/gen-nineslice.mjs`）零第三方依赖（`node:zlib` + 手写 PNG 分块），在出图前后做四层自检：

1. alpha 二值性，且**只有四角允许透明**（别处 alpha=0 就是边框上破了个洞）；
2. 边条沿延展轴均匀、中心块单值——均匀性按 `[灰度, alpha]` 整对比较，只比灰度挡不住"同色但透明"的缝；
3. 外凸 / 内凹 / 平面板的明暗极性，**四条边全验**，并要求 window 与 inset 互为镜像；
4. PNG 往返解码与源像素逐字节相等。

确定性范围：**同一 Node（同 zlib）下同输入必得同字节**，反复重跑不产生 git 噪声。跨 Node 大版本不作此保证——
DEFLATE 允许多种合法编码，换实现后解出的像素完全一样、字节流却可能变，此时 `--check` 会报 STALE；
那不是资产坏了，重跑写盘即可。正确性的真判据是上面四层像素级自检，不是字节相等。

## 五、publicDir 冲突的处置（已闭环）

`webui/vite.config.ts` 把 `publicDir` 指向了 `../src/main/resources/assets/miningdim/textures`
（为的是让 mod 物品贴图直接映射为静态资源，避免复制副本造成双源漂移）。Vite 只支持一个 publicDir，
因此**本目录不是站点静态根**：按 `/ui/frame-window.png` 取图在 dev 下拿到的是 SPA 回退的 index.html。

现行处置是"引用方式"而非"搬家"：`PixelFrame.tsx` 以 ESM `import` 引用这三张 PNG
（`import frameWindowUrl from '../../../public/ui/frame-window.png'`），由 Vite 当普通资产打包，
自动带 hash 与正确的相对基址，dev 与 build 两端都不依赖 publicDir 的归属。文件仍留在本目录，
美术"按同名覆盖"的替换流程因此完全不受影响；`dist` 里也不会出现本 README。

构建期尺寸守卫已同步覆盖本目录：`scripts/verify-pixel-guards.mjs` 的扫描根为 `src/assets` 与
`public` 两处，且扫到 0 张 PNG 即判失败——避免资产迁移后守卫退化成静默的"PASS（0 张）"。

守卫另有两道专为 9-slice 加的检查，替换资产时会直接撞上，请照它的报错改：

- **逐文件 size/slice 契约**：守卫从 `PixelFrame.tsx` 解出 `PIXEL_FRAME_ASSETS` 的三条登记，逐张核对
  PNG 的真实边长必须等于登记的 `size`，且 `size >= slice*2+1`。通用的 16/24 白名单管不住这件事——
  把 24x24 换成一张合法的 16x16，白名单照样放行，但 `slice` 仍是 8，左右切片之和正好吃满图宽，
  中心块与四条边条宽度全为 0，控件静默失去边框。若 `PIXEL_FRAME_ASSETS` 的写法或档位有变，
  守卫会因为解不出三条登记而直接失败（刻意如此，不退化成"跳过检查"）。
- **PNG 完整性**：走完整分块并逐块校验 CRC，要求 IHDR 在首、IDAT 与 IEND 齐全。只看"签名对 + 长度够"
  会让被截断的文件报出正确宽高并通过，而 Chromium 解不开它时 `border-image` 是静默无边框、不报错。
