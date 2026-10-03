# 插板护甲穿戴模型：设计 → 预览 → 导出

54 件可穿戴插板护甲（`PlateArmorVariant`，只占胸甲槽）的穿戴模型都在这里用 JS "设计"出来：
每件一个脚本，返回一组方块和每个方块的逐像素上色函数。预览页和游戏用的是**同一份设计、同一套算法**：
`export.mjs` 把设计离线烘焙成四边形网格 JSON + PNG 贴图，游戏里一个很小的自定义 `HumanoidModel` 按网格直接画，
所以进游戏看到的和预览里一模一样。不用 GeckoLib。

所有设计统一用折中 2× 画法（`'M'`：半像素网格、每格 2 个贴图像素、每个面单独分一块贴图）。
`'A'`（1×）和 `'B'`（4×）只在比较画法时用，导出只认 `'M'`。

## 文件

| 文件 | 作用 |
| --- | --- |
| `designs.js` | 公共工具：噪声、迷彩、`kit(mode)` 画法工具箱（描边、MOLLE、圆环…）、`box()` / `octa()`、`ARMORS` 注册表、预览用的玩家皮肤 |
| `armors/<key>.js` | 一件一个：`ARMORS.<key> = function (mode) { ... return { S, px, cell, boxes }; }` |
| `armors/<key>.meta.json` | 预览页的名字、等级、原图链接、改动说明 |
| `items.json` | 页面 key → 物品 id 的对照（如 `jaypc` → `jaypc_olive`，`kirasa` → `kirasa_n_green`，id 可以数字开头如 `6b23_1_digital_flora`），也定预览页的排序 |
| `STYLE.md` | 画法规矩（离皮肤外套层的距离、挂件怎么贴实、颜色怎么取…） |
| `check.mjs` | 单件结构检查（无浏览器） |
| `viewer.js` + `template.html` + `build.mjs` | 预览页 |
| `serve.mjs` | 本地静态服务（预览页是 ES module，不能 `file://` 直接开） |
| `export.mjs` | 导出游戏用的网格 + 贴图 |
| `parity.mjs` | 独立核对导出结果和预览页逐项一致 |
| `tools/shot.mjs` | 无头 Edge 截图脚本（评审用，可选） |

不入库（见 `.gitignore`）：`vendor/`、生成的 `*.html`、`current_embed.js`、`.check_*` 临时文件、`out/`、`scratch*/`。

## 流程

下面的 `node` 用 Node 22（本机是 `D:\DevTools\node-v22.23.3-win-x64\node.exe`），都在本目录下运行。

1. **改设计**：编辑 `armors/<key>.js`（公共的东西在 `designs.js`）。
2. **结构检查**：`node check.mjs <key> M`
   查部件名、尺寸、整块埋在皮肤外套层里的方块、离外套层不足 0.25px 的面、共面闪烁、悬空挂件、上色函数报错 / 返回非法颜色。
   `ok: false` 必须修；`warnings` 逐条看。
3. **预览**：`node build.mjs`（全部）或 `node build.mjs --only <key> --out preview-<key>.html`（单件）
   - 本地构建从 `vendor/three.module.min.js` 读 three.js，需要先放一份 **three@0.160.0**：
     `https://cdn.jsdelivr.net/npm/three@0.160.0/build/three.module.min.js` → `vendor/three.module.min.js`；
     或者加 `--cdn` 直接走 CDN（发布用）。
   - `node serve.mjs` 后打开 `http://localhost:5181/`（默认 `armor-preview.html`，单件页写文件名）。
   - 有 `current_embed.js`（旧 Java 模型的对照数据，不入库）时左边显示旧模型；没有就只显示新模型。
     旧的手写 `*ArmorModel.java` 已经删了，当前源码树生成不出这份数据，要对照只能从删除前的提交里恢复那些类再生成。
4. **导出**：`node export.mjs`（全部 54 件）或 `node export.mjs <key|id> ...`（只导这几件）
   打印每件的方块数、各部件四边形数、丢掉的全透明面、贴图尺寸。会**覆盖**同名贴图。
5. **核对**：`node parity.mjs`（默认用 `vendor/three.module.min.js`，和第 3 步预览用的是同一份；放在别处就加 `--three <路径>`）
   不复用 `export.mjs` 的代码：直接从 `viewer.js` 源码抠出 `polys / packFaces / packDesign / geometryFor / paintAtlas / partsFromFlat`，
   配真的 three.js 重算一遍，和导出的 JSON 逐个四边形比（位置、UV、法线误差 ≤ 1e-3，数量和顺序一致），
   再把 PNG 解码后和新画的贴图逐像素比。必须 54/54 PASS。
6. **进游戏**：在仓库根目录
   `$env:JAVA_HOME='D:\DevTools\jdk-17'; .\gradlew.bat --offline build`（实机看用 `runClient`）。

> 改了 `viewer.js` 里 `polys / packFaces / geometryFor / paintAtlas` 的算法，`export.mjs` 要跟着改，`parity.mjs` 会先报出来。

## 产物位置

| 产物 | 路径 |
| --- | --- |
| 网格 | `src/main/resources/assets/miningdim/armor_meshes/plate_armor_<id>.json` |
| 贴图 | `src/main/resources/assets/miningdim/textures/models/armor/plate_armor_<id>_layer_1.png`（`PlateArmorItem.getArmorTexture` 返回的就是它，路径没变） |

`<id>` 是 `items.json` 里的物品 id（= `PlateArmorVariant.id()`），不是页面 key。这两类文件都是生成的，**不要手改**，改设计再导出。

## 数据约定（导出端和 Java 端都按这个来）

网格文件：

```json
{ "format": 1, "item": "plate_armor_<id>", "textureSize": [宽px, 高px],
  "parts": { "body": [ 四边形... ], "right_arm": [ ... ], "left_arm": [ ... ] } }
```

- 部件只有 `body` / `right_arm` / `left_arm`。`right_arm`、`left_arm` 可以是空数组（大多数背心没有手臂部件），
  **`body` 必须非空**：Java 端拒收空 body（整件退回原版模型），`export.mjs` 遇到空 body 直接报错。头、帽子层、腿不画。
- 每个四边形是一个 23 个数的扁平数组：`x0,y0,z0,u0,v0, x1,y1,z1,u1,v1, x2,y2,z2,u2,v2, x3,y3,z3,u3,v3, nx,ny,nz`。
- **位置**：部件局部的 MC `ModelPart` 坐标，单位像素，和 `CubeListBuilder.addBox` 用的数一样（y 向下，正面是 -z，x<0 是穿戴者右边）。
  身体原点 (0,0,0)；手臂方块已经是手臂局部坐标（右臂方块 x 在 -3..1），手臂原点 (-5,2,0) / (5,2,0) 由 `HumanoidModel` 的部件姿态施加，**不烘焙**进网格。
- **旋转方块**（`box.rot = [rx,ry,rz]` 度，绕 `box.pivot`，默认方块中心）：位置已经转好，
  和 `viewer.js geometryFor` 一样 —— MC 坐标里 `THREE.Euler(rx,ry,rz,'ZYX')`，即矩阵 Rz·Ry·Rx（原版 PartPose 的顺序）。
- **顶点顺序**：每个面按 `polys(b).verts` 的顺序（原版 `ModelPart.Polygon` 顺序）；
  面的贴图矩形 `[u1,v1,u2,v2] = packFaces` 给的 `b.faceUV[face]`，顶点 0→(u2,v1)、1→(u1,v1)、2→(u1,v2)、3→(u2,v2)，
  再除以贴图尺寸（贴图单位 tw、th）得到 0..1 的 u、v，v 向下（图片第 0 行是 v=0）。
- **法线**：面在未旋转 MC 空间里的朝外轴向 —— top (0,-1,0)、bottom (0,1,0)、right (-1,0,0)、left (1,0,0)、front (0,0,-1)、back (0,0,1)
  （和原版 `Direction` 对这几个面的取值一致），再乘上方块旋转，单位长度。
- 零面积的面不出；整块贴图矩形全透明的面也丢掉（画了也看不见）。
- 取整：位置、法线到 1e-4；UV 到 1e-8，并且**朝面内取整**（每个面的 u1、v1 向上取，u2、v2 向下取）。
  各个面的贴图矩形之间不留缝，四舍五入会把恰好落在整像素上的边往外推一点，边上的像素就可能采到隔壁面的颜色；
  贴图边长不超过 256 时整像素边 k/宽 在 1e-8 下本来就精确，朝内取整不会动它们。

贴图：RGBA PNG，尺寸 = `packFaces` 的 (tw·S, th·S)，S = 设计的 `S`（`'M'` 是 2）；`textureSize` 就是 PNG 的像素尺寸。
像素和预览页 `paintAtlas` 逐个相同：上色函数返回 `null` 的像素 alpha 0（写成 0,0,0,0，和浏览器画布一致），颜色夹到 0..255 后按 `Uint8ClampedArray` 规则取整。

Java 端（客户端）：一个 `HumanoidModel<LivingEntity>` 子类，`renderToBuffer` 里对可见的 body / rightArm / leftArm
各自 `pushPose` → `part.translateAndRotate` → 把每个四边形的 4 个顶点按原版 `ModelPart.Cube.compile` 的方式发出去
（位置 = 姿态矩阵 × (x/16, y/16, z/16)，法线 = 法线矩阵 × (nx,ny,nz)）→ `popPose`。
它由 `IClientItemExtensions.getHumanoidArmorModel` 在胸甲槽返回，Forge 会把原模型的部件姿态和可见性拷过来，走路、潜行、细手臂都自动跟上。
网格从客户端 ResourceManager 懒加载、按护甲缓存、资源重载时清空；缺文件或文件坏了记一次警告，退回原版模型。
注意贴图已是逐面图集，原版模型按 64×32 箱式 uv 取色，所以退回时显示的是**贴图错乱的原版胸甲**，看到它先去日志找那条警告。

服务端质量门 `PlateArmorMeshGameTests`（`runGameTestServer`）逐件读打进 JAR 的网格和贴图：结构、部位锚点
（挂点没被重复烘焙、左右臂没写反、单位没错）、每个四边形的法线与绕序一致、uv 是正向矩形、贴图路径取自
`PlateArmorItem.getArmorTexture`、PNG 尺寸等于 `textureSize`、每个四边形的贴图矩形里至少有一个不透明像素
（只重导了网格或只重导了贴图会在这里断）。它不比几何和预览是否一致，那一步只有 `parity.mjs` 做。

## 已知限制

- 不支持盔甲纹饰（Trim）：原版纹饰层也走烘焙模型，但用的是纹饰图集，按本件图集归一化的 uv 映射过去是错乱的图案。
  插板护甲不在 `#minecraft:trimmable_armor` 里，生存拿不到，只有用命令写 Trim NBT 才会看到。
- 预览里没有玩家皮肤和头部转动，和皮肤表面共面的面在预览里看不出来，进游戏才会闪（z-fighting）。
  `check.mjs` 只查外套层外侧 0.25px 以内的面，**不查**落在皮肤本体表面上的面（例如躯干顶面 y=0、底面 y=12）。
  处理办法有两种先例：把盒子抬出外套层（`gzhel_k`、`korund_vm_black` 的背心顶在 y=-0.5），
  或者把和皮肤重叠的那部分格子画成透明（`paca` 的 `lower body sides` 底面、`b6b5_*` 的 `collar base` 端面）。
