# 军火台建模工具

军火台 (`munitions_bench`, 六档) WIDE 布局 (2026-09 以后放下的台子) 的「弹药流水线」模型 (方案 B v2) 全部由这里的脚本生成, 不要手改生成物。
全部是零依赖 Node 脚本 (本机便携版在 `D:\DevTools\node-v22.23.3-win-x64`), 光栅预览、PNG、字体借用 `../gunsmith_workstation/`。

| 脚本 | 作用 |
|---|---|
| `generate_munitions_bench.mjs --out <仓库根> [--check]` | 生成全部资源与三份 Java (见下表)。先在内存里生成并校验, 全部通过才写盘 (`--check` 只校验); 内容没变的文件不重写, 最后打印 `wrote X of Y` |
| `render_mb.mjs --out-dir <目录> [--repo <仓库根>] [--mode all\|sheets\|tiers\|motion\|closeup] [--tier ..] [--state ..] [--tick t] [--view V] [--block-light] [--mark]` | 游戏里样子的预览组图: 静态 JSON + 按 Java 摆好的运动件 (运动件默认实体光照; `--block-light` 改成方块面明暗)。工作态不给 `--tick` 取冲头到底的 `STRIKE_TICK` |
| `check_parity.mjs --cand <候选输出目录> --out-dir <目录> [--repo <仓库根>] [--no-java]` | 与方案 B v2 逐像素对拍 + 四个朝向对齐检查 + Java/JS 程序对拍 (见下文) |
| `core.mjs` / `tiers.mjs` / `ber.mjs` | 生成核心 (材质、画布、面描述、剔除、共面检查、图集、切两格、校验) / 六档政策 / 运动件工具 (原版盒式 UV、Java 写出与解析、程序与 applyPose 的 JS 镜像) |

```powershell
$env:Path = 'D:\DevTools\node-v22.23.3-win-x64;' + $env:Path
node tools/munitions_bench/generate_munitions_bench.mjs --out .
node tools/munitions_bench/render_mb.mjs --out-dir <临时目录>
# 对拍: 先让候选生成器把方案 B v2 的逐帧模型写到临时目录 (候选目录只读, 不要写回 .candidates)
cd <gunsmith-bench-models 工作树>\.candidates; node munitions_b\generate.mjs --out <临时目录>\cand_b
$env:JAVA_HOME = 'D:\DevTools\jdk-17'   # 可选: 有 javac 才做 Java/JS 程序对拍
node tools/munitions_bench/check_parity.mjs --cand <临时目录>\cand_b --out-dir <临时目录>\parity
```

## 生成物

| 文件 | 内容 |
|---|---|
| `models/block/munitions_bench{档}_line_{main\|extension}[_active].json` | 静态件 (区块网格, `RenderShape.MODEL`), 待机 / 工作。运动件不进 JSON。普通档 35 + 27 = 62 个元素, 闪耀 71 |
| `models/item/munitions_bench{档}.json` | 物品模型: 缩到 0.46 的整台 + 档位色底板上一发细高整发弹 |
| `textures/block/munitions_bench{档}_atlas.png` / `_particle.png` | 每档一张 128² 图集 (六档布局相同, 只换颜色) + 16² 破坏粒子 |
| `textures/entity/munitions_bench_parts.png` | 运动件贴图 128×64, 六档共用 (生成器逐档画一遍比对, 运动件必须与档位无关) |
| `blockstates/munitions_bench{档}.json` | 32 个变体: `layout=legacy_depth` 原样指向旧 JSON (`munitions_bench{档}_{main\|extension}[_active]`, 生成前逐条核对仓库里现有的旧变体); `layout=wide` 指向上面的 `_line_` 模型。两种布局 y 旋转相同: 北 0 / 东 90 / 南 180 / 西 270 |
| `block/MunitionsBenchProgram.java` | 只替换 `// <generated>` 与 `// </generated>` 之间: `CYCLE_TICKS`、`STRIKE_TICK`、`BELT_PITCH`、关键帧表、待机行。其余 (Pose、sample、idle、冲压时刻) 是手写的 |
| `block/MunitionsBenchGeometry.java` | 整个写出: 轮廓箱 (静态件 + 运动件)、台面高度、各档模型高度、运动件扫过的范围、冲压火花位置、运动件贴图尺寸、`rotated()` |
| `client/MunitionsBenchParts.java` | 整个写出: 运动件的 `createBodyLayer()` 与 `applyPose()` |

`{档}` = `''` / `_medium` / `_high` / `_superior` / `_transcendent` / `_radiant`。场景 (`scene()`) 与方案 B v2 相同 (方案评选时的完整 NOTES 与评审记录只在本机 `gunsmith-bench-models` 工作树的 `.candidates/munitions_b/`, 不进版本库; 要点如下)。

## 设计要点 (方案 B v2「弹药流水线」)

- 一条横跨两格的低矮流水线, 皮带沿 -x 从玩家左手 (副格) 流向右手 (主格), 每步一个弹位 (4 px)。弹位 x: 入口 26.5 / 底火 22.5 / 装药 18.5 / 压弹头 14.5 / 出弹 10.5, z 7.5, 皮带面 y 9。
- 副格: 白色弹壳料斗 + 玻璃落壳管 (入口位正上方), 枪灰装填塔 (底火窗 + 药窗, 一根横梁带底火冲杆和装药管), 台前弹壳托盘 + 控制台。
- 主格: 小四柱压弹头机 (全机唯一高点, 缸顶 22.5), 台前弹头托盘, 敞口橄榄绿弹药箱 (满箱整发弹 + 掀开的箱盖) 与箱后的备用弹药箱。
- 皮带上从左到右: 空壳、空壳、装药壳、整发弹; 台前一排: 弹壳、控制台、弹头、弹药箱, 即配方顺序。
- 一个循环: f0 底火冲杆下探 / f1 装药管下探、冲头下行 / f2 冲头到底、弹头落到壳口、压模发热 / f3-f5 皮带步进一个节距 (冲头回位的 f3-f4 不夹弹头) / f5-f7 末端那发沉进弹药箱。
- 档位: 机身每档中性; 饰色 (两端侧板、皮带侧板下行、压机横梁两侧)、色带 (前护栏、铭牌框、箱盖内的密封框, 闪耀 = 金)、灯色, 外加逐档累加的小件:
  中级 料斗色带 → 高级 料斗顶信号灯 → 极品 料斗正面两侧竖灯条 → 超凡 柜体踢脚条 (色带色) → 闪耀 压机缸顶金座 + 发光宝石 (高 24)。铭牌右侧亮 1..6 格档位刻痕。
- 物品图标: 缩到 0.46 的整台 + 档位色底板上一发细高整发弹, gui 旋转 [26, 200, 0]、平移 [0, 2, 0]、缩放 0.9 (32 px 槽外 0 像素)。

## 坐标系

朝北放置时的像素: x 东、y 上、z 南, 正面 z = 0。整台坐标的原点在**主格西北下角**: 主格 x 0..16 在站在正面的玩家**右手**, 副格 x 16..32 在**左手** (副格在 `facing.getClockWise()` 一侧)。
每格的 JSON 模型与轮廓箱用各自的局部坐标 (副格局部 x = 整台 x - 16); 模型最高 22.5 px (闪耀的宝石 24), 超出格子顶面是有意的 (原版允许 -16..32)。

## 校验 (任何一条失败都不写文件)

- 整台在 x 0..32 / y 0..32 / z 0..16 里; 旋转只用 ±22.5 / ±45; 旋转件不跨两格的切缝 (绕 x 轴的除外)。
- 同向共面 (z-fighting): 待机、工作、物品和普通档逐帧 (运动件与静态件一起) 全查。
- 图集: 区块不越界不重叠、全不透明、同一个绘制键在同一档里画出同样的像素; 各档同一个面落在同一块区域 (否则警告)。
- 每个模型文件: x/z 0..16、y 0..32、厚度 ≥ 0.5 (物品 0.2)、0.25 网格、uv 恰好落在一个图集区块里、`forge_data` 为 0..15 整数、无 cullface、≤ 90 个元素。
- 方块状态: 仓库里现有的 16 个旧变体与旧规则逐条相同, 旧模型文件都在。
- 运动件: 见下节的逐帧核对、接缝核对、档位无关、写出的 Java 解析回来再按 JS 镜像摆一遍与场景比对。
- 轮廓箱: 每个静态元素要么归进 `SHAPE_GROUPS` 的某一组, 要么是 `ORNAMENTS` 里的饰件; 箱子不出格。

## 运动件 (方块实体渲染器)

静态件在区块网格里, 下面这些件由主格方块实体的渲染器每帧画 (待机也画, 停在待机布局)。每个件取场景里某一帧的运动件元素、平移回待机布局, 按 **4 倍尺寸**建 ModelPart
(运动件有 0.25 / 0.75 px 的尺寸、贴图 2-4 texel/px, 原版盒式 UV 按 1 texel/单位算), 渲染时缩回 `PART_SCALE = 0.25`。

| 件 | 元素 | 跟着走 | 显示条件 |
|---|---|---|---|
| `round_in` | 入口位空壳 (x 26.5) | x += beltX | 总是 |
| `round_prime` | 底火位空壳 (x 22.5) | x += beltX | 总是 |
| `round_powder` / `round_powder_charged` | 装药位的壳 (x 18.5), 壳口空 / 已装药 | x += beltX | `!powderCharged` / `powderCharged` |
| `round_seat` / `round_seat_tipped` | 压弹头位 (x 14.5), 装药壳 / 已压上弹头 (壳 + 被甲 + 弹尖) | x += beltX | `!seated` / `seated` |
| `drop` | 皮带末端的整发弹 (x 10.5) | x += beltX, y += dropY | 总是 |
| `ram_rod` | 冲头连杆 (静止时藏在横梁里) | y += ramY | 总是 |
| `ram_die` / `ram_die_hot` | 压模 冷 / 热 (热的自发光) | y += ramY | `dieHeat < 0.5` / `dieHeat >= 0.5` |
| `ram_bullet` | 冲头夹着的弹头 | y += ramY | `ramBulletVisible` |
| `prime_rod` / `powder_tube` | 底火冲杆 (x 22.5) / 装药管 (x 18.5) | y += primeY / powderY | 总是 |

- 尺寸与各面外观完全相同的 cube 共用一块盒式 UV 贴图 (三种壳、被甲 (冲头夹着的那颗带底面, 单独一块)、弹尖、两种压模、三根杆共 11 块)。每个贴图像素按**原版 `ModelPart.Cube` 的面顶点与 uv 角点** (1.20.1 javap 逐条核对, 见 `ber.mjs` 的 `mcCubePolygons`) 反推到世界里的点, 再取方案里那个面在该点的贴图像素, 所以游戏里的样子与方案逐像素相同。
- 面: 方案里去掉的面 (弹壳底、连杆两端等) 用 `addBox(..., EnumSet.of(...))` 不建; ModelPart 的面标签是模型空间的 (y 朝下): 世界的顶面 = `Direction.DOWN`。
  游戏里运动件不剔除背面, 看得见的面必须都建上: 压模的顶面 (冲头下行时露在横梁下的缝里) 与冲头夹着的弹头的底面 (台子放在高处时能从下面看到) 是实装评审时相对方案补上的, 与方案对拍时不画 (见下文对拍)。
- **渲染器契约** (`MunitionsBenchParts` 的类注释里也写着):
  ```java
  poseStack.translate(0.5, 0.0, 0.5);
  poseStack.mulPose(Axis.YP.rotationDegrees(rot));   // rot = MunitionsBenchBlock.partsYRotationDegrees(facing): NORTH 0, EAST -90, SOUTH 180, WEST 90
  poseStack.translate(-0.5, 0.0, -0.5);
  Matrix3f normal = new Matrix3f(poseStack.last().normal());
  poseStack.scale(PART_SCALE, -PART_SCALE, PART_SCALE);
  poseStack.last().normal().set(normal).scale(1.0F, -1.0F, 1.0F);   // 法线矩阵自己设, 见下
  MunitionsBenchParts.applyPose(root, pose);          // pose = MunitionsBenchProgram.sample(...) 或 idle(...)
  // 逐个画 PARTS (root 本身不画): FULL_BRIGHT 里的件用 LightTexture.pack(max(方块光, 12), max(天空光, 12)), 其余用 packedLight;
  // RenderType.entityCutoutNoCull(TEXTURE) (y 翻转后绕序反了, 与组装台机械臂相同)
  ```
  `applyPose` 把每个件从 `getInitialPose()` 平移 `(dx, dy) × UNITS_PER_PX` (y 取反) 并设 `visible`。
  **法线**: 1.20.1 的 `PoseStack.scale` 在三个缩放之积为负时 (一个轴取负; 三轴同为负也一样) 用 `Mth.fastInvCubeRoot` 算法线缩放, 它不收负数,
  法线被放大约 1e25 倍, 写进顶点时每个分量截成 ±1, 实体光照随视角乱跳 (javap 核对过)。diag(s, -s, s) 的逆转置与 diag(1, -1, 1) 同向, 所以把缩放前的法线矩阵右乘它。
- 手写的一侧 (不由生成器写): `client/MunitionsBenchRenderer.java` 按上面的契约画运动件 (LEGACY 台子跳过, 停机直接回待机布局; 件的中心在副格 (x ≥ 16) 时用副格的光照), 冲压那一 tick 从 `SPARK_*` 放火花;
  `block/MunitionsBenchBlockEntity.java` 在 `setBlockState` 里记 ACTIVE 由假变真的 tick 作程序起点 (服务端据它在起点 + `STRIKE_TICK` + 40n 播冲压音, 区块更新标签把它带给后加载区块的客户端; 读档后还不知道起点时, 组区块包的那一刻当场定下);
  `block/MunitionsBenchBlock.java` 用 `MAIN_BOXES` / `EXTENSION_BOXES` 加 `*_PART_BOXES` 按朝向转出轮廓, 碰撞每格一整块实心柱, `benchPixelToWorld` 把整台像素转到世界坐标, `partsYRotationDegrees` 是渲染器的朝向角 (GameTest 拿它和 `benchPixelToWorld` 对)。
- 热压模的底面在方案里是不发光的钢色, 做成一个件后整块按自发光画 (底面从上方看不到)。
- 游戏里运动件按实体光照 (两盏方向光), 侧面比方块面明暗略暗一点 (东西面约 0.50 对 0.60, 南北面 0.74 对 0.80); 对拍用方块面明暗, 预览默认实体光照。

## 关键帧程序 (`MunitionsBenchProgram`)

- 一个循环 8 帧 × 5 tick = 40 tick。关键帧表每行: `tick, beltX, primeY, powderY, ramY, dropY, dieHeat, ramBulletVisible, powderCharged, seated`, 第 0..7 行是方案的 f0..f7, 第 8 行 (tick 40) 是接缝。
- 帧表来自生成器里的 `BELT / PRIME / POWDER / RAM / DROP_Y / DIE_HEAT / RAM_BULLET / POWDER_CHARGED / SEATED`, 场景的逐帧也读同一组表, 两边不会分叉。
- **插值**: 连续量在相邻两行间**线性**插值 (帧表本身就是按缓动取样写的: 皮带 -1 / -2.5 / -4, 出弹 -1.5 / -3.5 / -6.5; 逐帧 smoothstep 会让皮带在中间帧各停一下); 布尔量取"到达的那一行"。取模 `CYCLE_TICKS`, 负数折回, NaN 取首行。`sample(long elapsedTicks, float partialTick, out)` 先在 long 里取模, 台子连开几天也不抖。
- **接缝**: 接缝行 = 下一轮 f0 的机器姿态, 但皮带上的弹仍按这一轮编号 (`beltX = f0 - BELT_PITCH`, 出弹留在箱里, 装药/压弹头状态同 f7)。到下一轮 f0 时弹位整体换一次号, 画面只多出入口位落下的一只新壳, 箱里那发 (已被箱体完全挡住) 消失。生成器核对这两点以外画面不变。
- `STRIKE_TICK` = 冲头最低的那一行 (f2, tick 10), 也是压模最热的一帧; 冲头夹着的弹头此刻正好落在壳口 (`MunitionsBenchGeometry.SPARK_*` = 14.5, 12.5, 7.5, 生成器核对)。服务端在程序起点 + `STRIKE_TICK` + n × 40 播冲压音 (`isStrikeTick` / `nextStrikeTickAfter`), 渲染器在同一时刻在火花位置放粒子。
- `idle()` = 待机布局: 与 f0 相同, 只是底火冲杆收着 (所有偏移 0, 冲头夹着弹头, 装药位与压弹头位都还没做)。

**JS 镜像必须与 Java 一致**: `ber.mjs` 的 `sampleProgram` / `sampleProgramAt` / `idlePose` 逐行对应 `MunitionsBenchProgram.sample` / `idle`, `applyPoseMirror` 对应生成的 `MunitionsBenchParts.applyPose` (直接解析它的 `place(...)` 行); 预览与对拍只读 Java 源 (`parsePartsJava` / `parseProgramJava`), 不读生成器内部的表。改了 Java 的手写部分 (插值、取模、列号) 必须同步改 `ber.mjs`, 改了写出格式必须同步改解析器; `check_parity.mjs` 会单独编译 `MunitionsBenchProgram.java`, 每 0.25 tick 与 JS 镜像对拍。

## 轮廓箱与碰撞 (`MunitionsBenchGeometry`)

- `MAIN_BOXES` / `EXTENSION_BOXES`: 静态件的轮廓箱, 各格局部像素、朝北, 每组元素在该格里那一段的包围盒 (旋转件取旋转后的角点), 向外取整到 0.25 px。组: 底座柜体台面、弹药箱、箱盖、备用弹药箱、皮带、压机前/后立柱、压机横梁与缸、料斗、落壳管、装填塔、装填塔横梁。
- `MAIN_PART_BOXES` / `EXTENSION_PART_BOXES`: 运动件的轮廓箱, 每个件在关键帧表 (含接缝行) 与待机里扫过的包围盒, 切到各格; 被别的盒子包住的并进去。让皮带上的弹、冲头、两根杆都点得中台子。
- 饰件不进轮廓: 台面上的托盘、控制台、急停、运行灯、箱里冒出的弹头, 以及各档加件 (料斗色带、信号灯、料斗灯框、宝石)。踢脚条在底座箱里。
- 轮廓 (选择框 / 右键命中 / 支撑面) = 静态件盒子 + 运动件盒子; **碰撞**不用这些盒子, 是每格一整块实心柱, 高到该格静态盒子的最高点 (主格 22.5, 副格 20.5): 台面只有 8 px, 低于跨步高度 9.6 px, 贴着模型的碰撞会让玩家走上台面站进运动件中间; 柱子也高过起跳高度 (约 20 px)。
- 别的朝向用 `rotated(box, quarterTurns)` 绕格子中心 (8, 8) 转 (俯视顺时针, 与方块状态的 y 旋转同向)。
- `BODY_TOP_PX` = 台面 8; `MODEL_TOP_PX[档]` = 22.5 (闪耀 24); `SHAPE_TOP_PX` = 22.5; 运动件扫过 `PARTS_MIN` .. `PARTS_MAX` (x 5.5..27.5, y 2.5..20.5, z 6.25..8.75), `RENDER_TOP_PX` = 20.5。

## 对拍 (`check_parity.mjs`)

- 游戏 = 静态 `_line_` JSON (两格) + 从 `MunitionsBenchParts.java` 解析、按 `MunitionsBenchProgram.java` 的 JS 镜像摆好的运动件 (方块面明暗); 方案 = 候选生成器自己写出的逐帧 / 待机 / 工作 JSON。
- 用例: 普通档 f0..f7 (t = 0, 5 .. 35) 各 8 个视角 + 3 个运动件特写; 六档待机、六档工作 (t = `STRIKE_TICK`, 方案的 `_active` 就是 f2) 各 8 个视角; 六档 32 px 物品图标。每个视角 420×340, 任一通道有差即算不同; 整幅画面错开亚像素 (正视/侧视的像素中心会压在贴图像素分界上, 取哪一格只看浮点误差)。
- 相对方案有意补上的运动件面 (`DELIBERATE_FACES`: 压模顶面、冲头夹着的弹头底面) 在与方案对拍时不画: 方案按 JSON 的规矩剔除背面, 不画它们就是方案的样子。
- 四个朝向: 各格模型按方块状态 y 旋转烘、副格放在顺时针一侧、运动件按渲染器朝向角烘, 与朝北的整台绕主格中心转过去的四边形集合必须相同。
- **背面检查**: 游戏里运动件用 `entityCutoutNoCull`, 不剔除背面。普通档待机 + 一个循环每 1.25 tick, 7 个俯/平视角 + 2 个仰视角 (台子下面垫着它所在的那一格方块), 运动件各画一遍不剔除 / 剔除, 有一个像素不同就是件的背面露了出来 (`parity-backfaces.png`)。
- 输出 `parity-report.md`、`parity-frames.png`、`parity-tiers.png`、`parity-items.png`, 以及游戏样子的参考图 (实体光照): `game-motion.png` (每 2.5 tick 一列)、`game-base-{active,idle}.png`、`game-tiers.png`、`game-shapes.png` (轮廓箱线框)。有差异时退出码 1。
- 当前结果: 除有意补面外全部 0 像素差异, 没有背面露出, 四个朝向对齐, Java/JS 程序 621 个采样一致。

## 改模型

改 `generate_munitions_bench.mjs` 的 `scene()` / 帧表 / `PART_DEFS` / `SHAPE_GROUPS`, 重新生成; 再用 `render_mb.mjs` 看图。
改了运动件的元素时, `PART_DEFS` 里每个件的来源场景与元素名要跟着改 (生成器逐帧核对摆好的件与场景里的运动件完全相同, 对不上就报错)。
场景一改, 与方案 B v2 的逐像素对拍自然不再为 0; 对拍只用来证明移植没有走样, 之后以本目录的生成器为准。
