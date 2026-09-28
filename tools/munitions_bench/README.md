# 军火台建模工具

军火台 (`munitions_bench`, 六档) WIDE 布局 (2026-09 以后放下的台子) 的「弹药流水线」模型 (方案 B v2) 全部由这里的脚本生成, 不要手改生成物。
全部是零依赖 Node 脚本 (本机便携版在 `D:\DevTools\node-v22.23.3-win-x64`), 光栅预览、PNG、字体借用 `../gunsmith_workstation/`。

| 脚本 | 作用 |
|---|---|
| `generate_munitions_bench.mjs --out <仓库根> [--check]` | 生成全部资源与三份 Java (见下表)。先在内存里生成并校验, 全部通过才写盘 (`--check` 只校验); 内容没变的文件不重写, 最后打印 `wrote X of Y` |
| `render_mb.mjs --out-dir <目录> [--repo <仓库根>] [--mode all\|sheets\|tiers\|motion\|closeup\|counter] [--tier ..] [--state ..] [--tick t] [--view V] [--block-light] [--mark] [--counter rounds[,cap[,caliber[,full]]]]` | 游戏里样子的预览组图: 静态 JSON + 按 Java 摆好的运动件 (运动件默认实体光照; `--block-light` 改成方块面明暗)。工作态不给 `--tick` 取冲头到底的 `STRIKE_TICK`。`--counter` 按 Java 的 `COUNTER_*` 常量画计数屏的字; `--mode counter` 出 `mb-counter.png` (六个样例的箱盖特写) |
| `check_parity.mjs [--cand <候选输出目录>] --out-dir <目录> [--repo <仓库根>] [--no-java]` | 四个朝向对齐检查 + 背面检查 + Java/JS 程序对拍 + Java/JS 计数屏对拍 (见下文); 与方案 B v2 逐像素对拍只在给了 `--cand` 时做 (弹缩小之后本来就对不上, 平时不给) |
| `counter.mjs` | 弹药箱计数屏 (方案 C) 的布局真源 (`DISPLAY`)、颜色、Java 常量的写出与解析, 以及 `MunitionsBenchCounter.java` 的 JS 镜像 (格式、字形、排版、满度条、满仓、显示键、颜色、角的摆法 `facePoint` / `rectCorners` / `blockCorners`, 朝向变换 `benchToBlock` 与灯效共用) 和预览四边形 |
| `lights.mjs` | 运行灯效的真源 (目标面 `TARGETS`、时间与透明度、档位阶梯 `EFFECTS[].unlock`、每档颜色 `lightPalette`)、Java 常量 `LIGHT_*` 的写出与解析、前提核对 (`targetProblems` / `programProblems` / `frameProblems`), 以及 `MunitionsBenchLights.java` 的 JS 镜像 (`lightFrame` / `levels` / `lightOverlays` / `overlayCorners` / `blockCorners` / `worldFace`), 见下文"运行灯效" |
| `render_lights.mjs --out-dir <目录> [--repo <仓库根>] [--mode all\|cycle\|ladder\|full\|close\|speed] [--tier ..]` | 灯效预览组图 (夜里玩家视角的透视, 用 `lraster.mjs` 按游戏里的方式画 textBackground 批次: 按距离排序、写深度、α < 0.1 丢弃、α 取整成字节): `lights-cycle-<档>.png` (一个循环 10 帧, 默认普通 / 高级 / 极品 / 闪耀)、`lights-ladder.png`、`lights-full.png`、`lights-close.png` (这四种按程序 tick 取帧, 各档同一个动作相位), `speed-strip.png` (档位速度: 同一秒 20 游戏 tick 每 2 tick 一帧, 默认普通 / 高级 / 闪耀各一行, 每格标游戏 tick T 与程序 tick P)。画之前核对 `LIGHT_*` = lights.mjs、仓库 JSON 满足目标面前提、帧表与每档速度的前提 (含光敏) |
| `lraster.mjs` | 灯效预览的光栅器 (透视 / 正交相机、顶点色半透明四边形、textBackground 的排序与 discard), 由方案评选时的候选原样搬来 |
| `core.mjs` / `tiers.mjs` / `ber.mjs` | 生成核心 (材质、画布、面描述、剔除、共面检查、图集、切两格、校验) / 六档政策 (颜色、加件, 以及每档的生产速度 `cycleTicks`, 见"档位速度") / 运动件工具 (原版盒式 UV、Java 写出与解析、程序、时间映射与 applyPose 的 JS 镜像) |

```powershell
$env:Path = 'D:\DevTools\node-v22.23.3-win-x64;' + $env:Path
node tools/munitions_bench/generate_munitions_bench.mjs --out .
node tools/munitions_bench/render_mb.mjs --out-dir <临时目录>
node tools/munitions_bench/render_lights.mjs --out-dir <临时目录>\lights
$env:JAVA_HOME = 'D:\DevTools\jdk-17'   # 可选: 有 javac 才做 Java/JS 程序对拍、计数屏对拍与灯效对拍
node tools/munitions_bench/check_parity.mjs --out-dir <临时目录>\parity
# (历史) 与方案 B v2 逐像素对拍: 候选生成器把逐帧模型写到临时目录后加 --cand <临时目录>\cand_b。弹缩小 (见下文"弹的尺寸") 之后
# 弹、弹药箱顶、托盘、料斗顶、冲头与两根杆都按设计与 B v2 不同, 这一项只剩考古用途。
```

## 生成物

| 文件 | 内容 |
|---|---|
| `models/block/munitions_bench{档}_line_{main\|extension}[_active].json` | 静态件 (区块网格, `RenderShape.MODEL`), 待机 / 工作。运动件不进 JSON。普通档主格 41 + 副格 27 = 68 个元素, 闪耀 44 + 33 = 77 (箱盖 = 窗后本体 + 四条框条) |
| `models/item/munitions_bench{档}.json` | 物品模型: 缩到 0.46 的整台 + 档位色底板上一发细高整发弹 |
| `textures/block/munitions_bench{档}_atlas.png` / `_particle.png` | 每档一张 128² 图集 (六档布局相同, 只换颜色) + 16² 破坏粒子 |
| `textures/entity/munitions_bench_parts.png` | 运动件贴图 128×64, 六档共用 (生成器逐档画一遍比对, 运动件必须与档位无关) |
| `blockstates/munitions_bench{档}.json` | 32 个变体: `layout=legacy_depth` 原样指向旧 JSON (`munitions_bench{档}_{main\|extension}[_active]`, 生成前逐条核对仓库里现有的旧变体); `layout=wide` 指向上面的 `_line_` 模型。两种布局 y 旋转相同: 北 0 / 东 90 / 南 180 / 西 270 |
| `block/MunitionsBenchProgram.java` | 只替换 `// <generated>` 与 `// </generated>` 之间: `CYCLE_TICKS`、`STRIKE_TICK` (程序时间)、`CYCLE_TICKS_BY_TIER` / `STRIKE_TICKS_BY_TIER` (每档的循环长度与冲压时刻, 游戏 tick, 来自 `tiers.mjs`; 私有数组, 外面经 `cycleTicks` / `strikeTick` / `tierCount` 读)、`BELT_PITCH`、关键帧表、待机行。其余 (Pose、sample、programTick、idle、冲压时刻的判断) 是手写的 |
| `block/MunitionsBenchGeometry.java` | 整个写出: 轮廓箱 (静态件 + 运动件)、台面高度、各档模型高度、运动件扫过的范围、冲压火花位置、流水线设计尺寸 (皮带面、弹位、壳高 / 弹头高、静止位时冲头夹着的弹头底与两根杆的下端, 从待机场景量出来, GameTest 用它们核对帧表)、运动件贴图尺寸、`rotated()`, 计数屏的 `COUNTER_*` (显示面、布局、每档颜色, 见下文), 以及运行灯效的 `LIGHT_*` (目标面、组、工位灯、脉冲时间与透明度、档位阶梯、每档颜色, 见下文) |
| `client/MunitionsBenchParts.java` | 整个写出: 运动件的 `createBodyLayer()` 与 `applyPose()` |

`{档}` = `''` / `_medium` / `_high` / `_superior` / `_transcendent` / `_radiant`。场景 (`scene()`) 源自方案 B v2 (方案评选时的完整 NOTES 与评审记录只在本机 `gunsmith-bench-models` 工作树的 `.candidates/munitions_b/`, 不进版本库; 要点如下),
之后按用户意见改过两处: 弹药箱换成计数屏方案 C (下文"弹药箱计数屏"), 所有弹缩到约 2/3 (下文"弹的尺寸")。

## 设计要点 (方案 B v2「弹药流水线」)

- 一条横跨两格的低矮流水线, 皮带沿 -x 从玩家左手 (副格) 流向右手 (主格), 每步一个弹位 (4 px)。弹位 x: 入口 26.5 / 底火 22.5 / 装药 18.5 / 压弹头 14.5 / 出弹 10.5, z 7.5, 皮带面 y 9。
- 副格: 白色弹壳料斗 + 玻璃落壳管 (入口位正上方), 枪灰装填塔 (底火窗 + 药窗, 一根横梁带底火冲杆和装药管), 台前弹壳托盘 + 控制台。
- 主格: 小四柱压弹头机 (全机唯一高点, 缸顶 22.5), 台前弹头托盘, 敞口橄榄绿弹药箱 (满箱整发弹 + 掀开的箱盖 = 计数屏, 见下节) 与箱后的备用弹药箱。
- 皮带上从左到右: 空壳、空壳、装药壳、整发弹; 台前一排: 弹壳、控制台、弹头、弹药箱, 即配方顺序。所有弹同一个比例, 见"弹的尺寸"。
- 一个循环: f0 底火冲杆下探 / f1 装药管下探、冲头下行 / f2 冲头到底、弹头落到壳口、压模发热 / f3-f5 皮带步进一个节距 (冲头回位的 f3-f4 不夹弹头) / f5-f7 末端那发沉进弹药箱。
- 档位: 机身每档中性; 饰色 (两端侧板、皮带侧板下行、压机横梁两侧)、色带 (前护栏、铭牌框、箱盖内的密封框, 闪耀 = 金)、灯色, 外加逐档累加的小件:
  中级 料斗色带 → 高级 料斗顶信号灯 → 极品 料斗正面两侧竖灯条 → 超凡 柜体踢脚条 (色带色) → 闪耀 压机缸顶金座 + 发光宝石 (高 24)。铭牌右侧亮 1..6 格档位刻痕。
- 物品图标: 缩到 0.46 的整台 + 档位色底板上一发细高整发弹, gui 旋转 [26, 200, 0]、平移 [0, 2, 0]、缩放 0.9 (32 px 槽外 0 像素)。
- 生产动画的速度按档位变快 (用户拍板, 均匀阶梯到 2 倍速), 见下节。

## 档位速度 (2026-09 用户拍板)

| 档 | 普通 | 中级 | 高级 | 极品 | 超凡 | 闪耀 |
|---|---|---|---|---|---|---|
| 一个循环 (游戏 tick) | 40 | 36 | 32 | 28 | 24 | 20 |
| 倍速 | 1× | 1.11× | 1.25× | 1.43× | 1.67× | 2× |
| 冲压时刻 (循环的 1/4) | 10 | 9 | 8 | 7 | 6 | 5 |
| 皮带追光 (瞬时, 极品起才有) | — | — | — | 2.14 Hz | 2.5 Hz | 3.0 Hz |
| 冲压闪光 / 落箱 / 各工位灯 | 0.5 Hz | 0.56 Hz | 0.63 Hz | 0.71 Hz | 0.83 Hz | 1 Hz |

(普通 / 中级 / 高级没有追光; 生成器仍按倍速算出 1.5 / 1.67 / 1.88 Hz 一并核对 (保守, 以后下放追光也不会超限), 打印时加括号。)

- 表只有一份: `tiers.mjs` 的 `CYCLE_TICKS_BY_TIER` (`TIERS[i].cycleTicks`), 生成器写进 `MunitionsBenchProgram.CYCLE_TICKS_BY_TIER` / `STRIKE_TICKS_BY_TIER`,
  Java 两端 (客户端渲染器 + 服务端冲压音) 与 JS 镜像 (`ber.mjs` / `lights.mjs`, 读解析出来的 Java 或生成器的同一份表) 都读它。
- 关键帧仍在 40 tick 的**程序时间**里写 (生成器的校验、接触核对、预览的 `t` 都在程序时间里); 游戏时间按
  `程序 tick = ((已过 tick mod 该档循环) + partialTick) × 40 / 该档循环` 映射 (`MunitionsBenchProgram.programTick`, 在 long 里取模, 台子连开几天也不抖;
  double 里按 先加、再乘、再除 的顺序算, 与 `ber.mjs programTick` 逐位相同)。循环内单调、到该档循环的整数倍正好折回 0; 普通档就是原来的 `elapsed mod 40 + partial`。
- 跟着程序走、一起变快的: 皮带 / 杆 / 冲头 / 出弹、冲压火花、服务端冲压音 (起点 + `strikeTick(档)` + `cycleTicks(档)` × n, 都是整 tick)、工位灯 / 冲压闪光 / 落箱脉冲 / 宝石 / 追光 (它们定义在程序时间里)。
  **不**变快的 (游戏时间): 运行呼吸 (80 tick 一周) 与待机满仓的琥珀闪烁 (40 tick 一周)。
- 档位来自方块: `MunitionsBenchBlock.tier()`, 注册时由 `ModMunitionsBlocks.registerBench(注册名, 档位, ..)` 给进构造器 (越界直接抛异常), 不查注册表
  (Forge 的 `getKey` 对没注册的方块返回 `minecraft:air` 而不是 null, 按注册名查会在注册前把普通档记死); GameTest 逐块核对它与注册名在 `MunitionsBenchAssets.TIER_IDS` 里的下标相同。
  `cycleTicks` / `strikeTick` 收到越界的档位 (不该发生) 按普通档。
- LEGACY 老台子 (2026-09 以前放下的, 没有运动件) 的冲压音也按档位的节拍 (拍板的是"冲压音 = 起点 + 该档冲压时刻 + 该档循环 × n", 不分布局; 同档的新旧台子听起来一样快),
  timing GameTest 里有一台闪耀的 LEGACY 钉住这个选择; 它的随机火花 (`animateTick`) 不跟程序, 不变。
- 生成器核对: 普通 = 程序长度 40、逐档严格变快且步长相同、冲压时刻是整 tick、最快不超过 2 倍; 光敏逐档 ≤ 3 Hz (`lights.mjs photosensitivity`: 追光按皮带最快一段 × 倍速算,
  脉冲按 `lightFrame + levels` 在游戏时间里跑 4 个整循环实测), 生成时打印 `tier speed` 与 `photosensitivity` 两行 (追光括号里的是没解锁的档)。改成 18 tick 的闪耀试过一次: 报 "不是均匀阶梯 / 冲压 4.5 不是整 tick / 2.22× 超过 2 倍 / 追光 3.33 Hz"。

## 弹的尺寸 (2026-09 缩到约 2/3)

用户看了实装觉得"子弹显得太大", 所有弹药道具统一缩到约 2/3, 共用生成器里的一个比例 `R` (机器件 —— 杆、压模、压机、弹药箱、箱盖计数屏、模板字与图标 —— 不缩):

| | 方案 B v2 | 现在 |
|---|---|---|
| 一发弹 (皮带上的弹、出弹) | 壳 2 x 2 x 3.5 + 被甲 1.5 宽 x 1.25 + 弹尖 1 x 1, 全高 5.75, 壳口 y 12.5 | 壳 1.5 x 1.5 x 2.5 + 被甲 1 宽 x 0.75 + 弹尖 0.5 x 0.75, 全高 4, 壳口 y 11.5 |
| 冲头夹着的弹头 / 箱里立着的弹头 | 被甲 + 弹尖 2.25 高 | 1.5 高 (箱里的是静态 JSON: 弹尖 0.5 px 正好是模型规则的最薄) |
| 弹药箱顶 | 3 x 4 格 2 px 的整发弹 (d 2) + 5 颗立体弹头 | `CAN_GRID` 4 x 5 格紧挨着的 1.5 px 整发弹 (d 4) + `CAN_3D` 6 颗立体弹头 |
| 弹头托盘 | 3 颗 1.5 px 宽的弹头 (d 2) | 5 + 4 颗错开两排, 1 px 宽 x 1.5 长 (d 4) |
| 弹壳托盘 / 料斗顶 | 1.5 px 的壳 (d 2, 本来就是新比例) | 同尺寸重画到 d 4 (有肩、瓶颈、0.5 px 壳口); 料斗顶 3 x 2 → 4 / 3 / 4 三排 |

- 宽度只能取 0.5 的整数倍 (弹位中心在 .5 上, 半宽要落在 0.25 网格上), 所以壳 1.5 / 被甲 1 / 弹尖 0.5。弹药道具的贴图密度 `RD` = 4 (1 贴图像素 = 0.25 px, 与运动件的盒式 UV 同一分辨率);
  壳口 (俯视) = 肩 → 瓶颈亮唇 → 0.5 px 的口 (空 = 黑, 装药 = 灰), 料斗顶的空壳同一画法。
- 弹位 x、节距 4、皮带面 9、`CYCLE_TICKS` 40 / `STRIKE_TICK` 10 都不变; 壳口低了 1 px, 帧表与几件机器随之重调 (生成器的接触核对见"校验"):
  - 底火冲杆 / 装药管: 行程仍 -1.5, 杆往下加长 1 px (静止位 13..16.5), 下探到底正好顶到壳口 11.5 (只加大行程的话杆顶会离开横梁悬空)。
  - 压弹头冲头: 夹着的弹头挂在压模底 (静止位弹头底 15.25), 行程 0 / -1.75 / -3.75 (原来 0 / -1 / -2), 冲压时刻弹头正好落在壳口; 连杆从 2 加长到 3.75 (= 行程),
    压到底时连杆顶正好还在横梁底面, 静止时整根藏在横梁 / 法兰 / 液压缸里 (运动件最高点 `PARTS_MAX` y 因此到 22.25)。
  - 出弹: dropY 0 / -1 / -2.5 / -4.75 (原来 -1.5 / -3.5 / -6.5; 按弹高同比例, 最后一帧弹尖顶 8.25 比箱顶低 0.25)。
  - 玻璃落壳管 (本来就与新壳同径 1.5) 下口从 12.5 放到 11.5, 正好接在入口位那只壳的壳口上 (循环接缝时新壳像是从管里落出来);
    管身贴图随之从 7 行变 9 行, 管里的叠壳只画到下管箍之上 (仍是两只壳 + 玻璃 + 上下镀铬管箍)。
- 图集: 弹药道具改成 d 4 以后原来一行一行排的图集 128² 放不下 (占用 82%), `core.mjs planAtlas` 改成天际线排布 (排到 117 行, 仍是 128²)。

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
- 接触 (`contactChecks`, 改弹的尺寸或帧表时靠它): 逐帧 + 待机, 皮带上的壳站在皮带面上、出弹在皮带面 + dropY; 两根杆顶一直在装填塔横梁里、杆底从不低于壳口;
  压模静止时顶面贴横梁底, 连杆顶一直在压机横梁里、连杆底 = 压模顶, 夹着的弹头挂在压模底; 底火冲杆 (f0) / 装药管 (f1) 正好顶到壳口、落在壳的截面里;
  冲压时刻 (f2) 夹着的弹头正好在装药壳的壳口上, 且与下一帧压好的那发弹头 (平移回同一皮带位置) 完全相同; 落壳管下口正好在入口位的壳口上、截面相同;
  最后一帧出弹整发没入弹药箱; 出弹掉进箱子一路 (相邻两帧的包围盒) 不碰箱里立着的弹头。再按写出的 Java 每 0.25 tick 摆一遍运动件 (含待机), 两两只许贴面不许穿插;
  与各档待机 / 工作的静态件 (旋转件取包围盒) 也只许贴面, 例外只有 `MOVING_VS_STATIC` 的几对: 两根杆在装填塔横梁里、连杆在压机横梁 / 法兰 / 液压缸里滑动,
  出弹沉进弹药箱, 以及出弹从皮带末端翻下去时擦过末端的角 (f4 → f5 皮带与出弹都线性插值, tick 20.25..22.25; 限深: 弹的中心已过皮带末端、下沉 < 0.5 px, 现在最深 0.45)。
  (改坏冲程 / 杆长 / dropY / 箱里立着的弹头位置、压模顶进横梁、落壳管伸进壳各试过一次, 都会报。)
- 流水线设计尺寸 (`lineDesign`): 皮带上的五发共用一个皮带面与弹位 z、弹位相隔一个节距、壳高 / 弹头高与 `R` 一致; 写进 Geometry 给 GameTest。
- 轮廓箱: 每个静态元素要么归进 `SHAPE_GROUPS` 的某一组, 要么是 `ORNAMENTS` 里的饰件; 箱子不出格。
- 计数屏: 各档待机 / 工作的 `can_lid` 本体、四条框条、`can` 与 `COUNTER_*` 一致; 两个显示面的顶点绕序朝外; 满度条与发数框在窗里; 各种格式形状里最宽的数与十种口径都放得下;
  `COUNTER_*` 写出后解析回来与布局 / 颜色相同。待机字对比度 < 4.5:1 只报 WARN。
- 运行灯效 (`lights.mjs`, 按刚生成的那份 JSON): 每档会用到的每个目标矩形都落在那一档工作态 (满仓提示另查待机态) 模型里同名元素的那个面上 (跨两格的元素拼起来盖住)、
  元素的 shade 标志与目标相同、工作态的 `block_light` 与目标相同、grow 的边是元素的棱; 胀的边正好接上同组往回胀的覆盖面、不胀的边不与同组相接 (`growProblems`: 壳是闭合的, 也不会伸到别的零件上); 六个面的绕序; 工位灯按 x 排序不重叠、在前沿灯带里;
  脉冲的峰与帧表对得上 (各工位动作第一次到底的 tick、冲头最低 = `STRIKE_TICK`、出弹没入 = 35、入口灯在 35..40 之间)、追光包络包住皮带步进、每档的循环表 = tiers.mjs 且冲压时刻是映射过去的整 tick、
  光敏逐档 ≤ 3 Hz (追光瞬时频率与各路脉冲, 见"档位速度"); 第一轮开工时已经发生过的拍与跑久了逐位相同 (六档 × 每 tick × 九个 partialTick, `firstCycleProblems`);
  六档 × 工作 / 待机 / 满仓 × 游戏时间 0..160 每 1/8 tick (按该档的速度映射, 最快的闪耀在程序时间里也是每 0.25 tick 一个样本) × 三个距离: 不重叠、每个顶点 α ∈ [0.1, 1]、rgb 是整数、不超过 `LIGHT_MAX_QUADS`、没有未解锁的效果;
  `LIGHT_*` 写出后解析回来与 `lightsLayout()` 相同; `MAX_DISTANCE_BLOCKS` = 计数屏的。(挪动横梁灯条的目标、改错宝石的 shade、错开一个工位灯的峰、在不是棱的边上胀、让弹药箱下那段灯带往上胀 / 横梁端面忘了往前胀, 各试过一次, 都会报。)

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
| `ram_rod` | 冲头连杆 (长 3.75 = 行程, 静止时藏在横梁 / 法兰 / 液压缸里) | y += ramY | 总是 |
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
- 手写的一侧 (不由生成器写): `client/MunitionsBenchRenderer.java` 按上面的契约画运动件 (LEGACY 台子跳过, 停机直接回待机布局; 件的中心在副格 (x ≥ 16) 时用副格的光照; 姿态 = `MunitionsBenchProgram.sampleRunning(已过 tick, partialTick, 档位, pose)`, 档位越高越快, 起点比本地时钟快时停在首帧), 冲压那一 tick (`strikeTick(档)` + `cycleTicks(档)` × n; 判断 = `strikeBetween(上一帧, 这一帧, 档位)`) 从 `SPARK_*` 放火花, 档位一并交给计数屏 / 灯效
  (每帧的两个决定都在纯 Java 的 `MunitionsBenchProgram` 里, GameTest 与对拍逐档核对它们与服务端的 `isStrikeTick` 同拍; 渲染器里只剩 "档位 = `bench.tier()`、三处都传它" 这一行胶水, 靠评审);
  `block/MunitionsBenchBlockEntity.java` 在 `setBlockState` 里记 ACTIVE 由假变真的 tick 作程序起点 (服务端据它在起点 + `strikeTick(档)` + `cycleTicks(档)` × n 播冲压音, 区块更新标签把它带给后加载区块的客户端; 读档后还不知道起点时, 组区块包的那一刻当场定下);
  `block/MunitionsBenchBlock.java` 用 `MAIN_BOXES` / `EXTENSION_BOXES` 加 `*_PART_BOXES` 按朝向转出轮廓, 碰撞每格一整块实心柱, `benchPixelToWorld` 把整台像素转到世界坐标, `partsYRotationDegrees` 是渲染器的朝向角 (GameTest 拿它和 `benchPixelToWorld` 对), `tier()` 是档位 (注册时给进构造器, 渲染器与冲压音共用)。
- 热压模的底面在方案里是不发光的钢色, 做成一个件后整块按自发光画 (底面从上方看不到)。
- 游戏里运动件按实体光照 (两盏方向光), 侧面比方块面明暗略暗一点 (东西面约 0.50 对 0.60, 南北面 0.74 对 0.80); 对拍用方块面明暗, 预览默认实体光照。

## 弹药箱计数屏 (方案 C "弹药箱计数")

用户在三个方案里选定的 C (评选时的候选脚本在本工作树的 `.candidates/munitions_counter/`, 不进版本库; 生成器现在的静态资源与候选 `--variant C`
的输出逐字节相同, 预览组图与候选的 `counter-C-*.png` 逐像素相同)。

- **静态件** (生成器, 布局在 `counter.mjs` 的 `DISPLAY`): 箱盖放宽到 8 px、加厚到 0.75 px (`[0.5,8.5,10]-[8.5,17.5,10.75]`, 仍后仰 22.5°、原点 `[4.5,8.5,10.5]`),
  内面 32 x 36 qt (1 qt = 0.25 px = 4 倍贴图的一个贴图像素): 档位色密封框 → 上 AMMO、中 凹窗 (x 4 y 10, 24 x 12 qt, 凹进 0.25 px)、下 黄漆子弹 + 黄条。
  模型规则要求元素至少 0.5 px 厚, 所以凹窗 = 窗后一块本体 `can_lid` (北面 = 窗面) + 四条满厚框条 `can_lid_{t,b,l,r}`, 各块北面从同一张整面设计图裁出。
  窗里的屏底 (带档位色调 + 扫描线)、满度条的槽是静态贴图, 工作时窗面自发光。箱身正面 (`can`, 28 x 22 qt) 原来写死的 "7.62" 换成一块深色空标签。
- **渲染器画的** (`client/MunitionsBenchCounterRenderer`, 在运动件之后): 窗里上面一条满度条 (x 5 y 11, 22 x 2 qt), 下面 4x7 字的缓冲发数 (x 5 y 14, 22 qt 宽, 右对齐);
  箱身正面第 8 行居中的 3x5 口径黄漆模板字 (`MunitionsCaliber.shortLabel`, 缓冲空时不写)。
  - 数字格式 (`MunitionsBenchCounter.format` = `counter.mjs formatCount`): ≤ 9999 原样; `12.4K` / `123K`; `1.23M` / `3.2M`; `2.14G`; 只舍不入, 小数末尾的 0 去掉, 最多 5 个字; ≤ 0 = 暗色 `0`, 超过 999,999,999,999 按 `999G`。默认 config 的上限 500..4000, 正常只见 1–4 位数。
  - 满度条 = floor(发数 × 22 / 上限), 有弹至少 1 格; **满仓** (缓冲装不下下一批 = 方块实体开工门同一条 `cannotTakeBatch`) 条变琥珀色。
  - 颜色 (`COUNTER_COLOURS[档][角色 × 2 + 待机]`, 由 `tiers.mjs` 的灯色经 `counterColours` 推出): 工作 = 档位灯色 on, 待机 ≈ 0.7; 空箱的 0 暗; 满仓琥珀 `#FFB234`; 模板字 `#F4C22C`。
    评审的目标是待机字对比度 ≥ 4.5:1, 超凡档只有 4.39:1 (生成器报 WARN, 颜色按认可的方案原样)。
- **BER 契约** (Java 与 `counter.mjs` 的预览同一套)。下面的变换与顶点顺序都在纯 Java 的 `MunitionsBenchCounter.blockCorners(rects, partsYRotationDegrees(facing))`
  (= `counter.mjs blockCorners`, 窗 / 箱身一点 = `facePoint`) 里算好, 渲染器只上色、把角原样交给 BER 的 poseStack, 按 (计数屏状态, 朝向) 缓存, 每帧不分配;
  GameTest (`wideBenchCounterFacesLandOnTheLidWindowAndTheCanFront`) 与 `check_parity.mjs` 核对的就是这个方法:
  ```java
  // 相机离主格中心 > COUNTER_MAX_DISTANCE_BLOCKS (24) 格不画
  translate(0.5, 0, 0.5); rotY(partsYRotationDegrees(facing)); translate(-0.5, 0, -0.5); scale(1 / 16f);   // 不要运动件的 (s, -s, s) y 翻转, 会把绕序再反一次
  // 箱身: 不转; 窗: 绕 COUNTER_LID_ROTATION_ORIGIN 按 x 轴转 COUNTER_LID_ROTATION_X_DEGREES (右手系, 同 JSON / FaceBakery)
  RenderType.textBackground()                             // POSITION_COLOR_LIGHTMAP: 没有贴图、不按法线打光、剔除背面
  // 矩形 (qt): xl = tl.x - x0*QT, xr = tl.x - x1*QT, yt = tl.y - y0*QT, yb = tl.y - y1*QT, z = tl.z - COUNTER_LIFT_PX (0.125 px)
  // 顶点 左上 → 左下 → 右下 → 右上 (从正面看逆时针 = 原版 FaceInfo.NORTH); 窗里 LightTexture.FULL_BRIGHT, 箱身 packedLight × level.getShade(facing, true)
  ```
  字浮在窗面外 0.125 px, 仍在 0.25 px 深的框条前沿之后; 不要换 entity* 渲染类型 (两盏方向光会让字的亮度随朝向变)。LEGACY 台子不画。
  已知: `text_background` 按整级取光照图 (`texelFetch`), 方块面在两级之间线性取样, 所以箱身模板字在不满级的光照下会比箱面亮约半级 (评审估算: 夜里方块光 3 时约亮 20%; 预览两者同样打光, 看不出); 实机在火把旁看一眼, 太显眼就把模板字改进静态模型。
- **同步** (`MunitionsBenchBlockEntity`): 更新标签 = `ProgramStartTick` (工作时) + `BufferedRounds` / `BufferedCaliber` (空 = -1) / `BufferCap` / `BufferFull`,
  区块包与 `getUpdatePacket()` (= `ClientboundBlockEntityDataPacket.create(this)`) 同一份; `onDataPacket` 与 `handleUpdateTag` 只读这几个键, 从不 `load()`;
  计数屏只认带 `BufferCap` 的标签 (发数 / 口径与存档同名, 别的模组发来的整份存档 NBT 没有上限, 不动计数屏)。没有 level 的实例 (客户端预览副本) 组标签时交出同步来的值, 不读 SERVER config。
  服务端在 `settleForOwner` (tick 结算 / 开 GUI 的每条出口)、`onOutputTaken`、`trySelectCaliber`、`tryStartCraft` 的等级门之后比较显示键 (`MunitionsBenchCounter.displayKey` = 屏上的字 + 口径 + 格数 + 满仓),
  变了才 `sendBlockUpdated(UPDATE_CLIENTS)`, 同一 tick 的几次变化合并成一包; 四个值与上次查时相同 (绝大多数 tick) 连键都不算; `load()` 清掉去重值。台主离线不结算, 显示不会变; 服主改 config 上限在台主在线的下一次结算里看到。
  包不是 null 以后, ACTIVE 每次翻转的方块更新也带这份小标签 (客户端的程序起点随之校成服务端的), 读档后第一次结算也会把区块包里已有的值再推一次: 都只是 5 个键, 不去重
  (不要在 `getUpdateTag` 里记键: 新进视距的玩家的区块包若恰好在变化与检查之间组好, 已在看的玩家就收不到那次变化)。
- **改布局**: 改 `counter.mjs` 的 `DISPLAY` 重新生成 (生成器核对场景里的箱盖 / 箱身与布局一致、绕序朝外、最宽的数与各口径放得下, 写出的 `COUNTER_*` 解析回来必须相同);
  改格式、字形、排版、满度条或显示键的规则, `MunitionsBenchCounter.java` 与 `counter.mjs` 两边一起改, 再跑 `check_parity.mjs` 的计数屏对拍。

## 运行灯效 (`MunitionsBenchLights`)

用户在预览页 (方案评选时的候选、评审与修订记录在本工作树的 `.candidates/munitions_lights/`, 不进版本库) 上认可的灯效, 按档位解锁 (用户拍板, 这是唯一的行为, 没有 config)。
工作时在静态模型已有的发光灯带上, 只在程序里真实发生动作的那一刻叠一层短暂的彩色四边形; 不加方块状态、不发包、不改静态模型、方块实体一行没改
(输入全是客户端已有的: ACTIVE、档位 (方块)、同步标签里的程序起点与满仓、`gameTime`、相机距离)。

| 效果 | 档位 | 贴在哪 (元素.面) | 时间 (程序 tick) | 颜色 / α |
|---|---|---|---|---|
| 工位指示灯 | 全档 | `strip` 北 + 顶, 各弹位正下方 1.5 px 一段 | 入口 38 → 底火 0 → 装药 5 → 压弹头 10 (从玩家看从左往右扫过), 出弹 35; 1 tick 缓入、5 tick 衰减 (都是程序 tick, 见表下) | mix(灯 hi, 白, .45), α = 脉冲 |
| 冲压闪光 | 全档 | `crown_light` 北 + 顶 + 两端 | 峰 = `STRIKE_TICK` (与冲压音、火花同一刻), α = √脉冲 = 6 tick 线性衰减 | 纯白 → 灯 hi |
| 落箱脉冲 | 全档 | `can_strip` 北 + 露出的顶 + 两端 | 峰 35 (出弹没入弹药箱), 2 起 8 落, 跨接缝到下一轮 t 3 | mix(hi, 白, .5), α 0.9 × 脉冲 |
| 满仓提示 | 全档 | 同上 | 只在待机且满仓: 客户端时钟 40 tick 一周梯形 (0–4 亮起, 4–16 亮, 16–20 暗下) | 琥珀 `#FFB234`, α 0.95 |
| 运行呼吸 | 高级 (档 2) 起 | `strip` 北 + 顶, 整条 (工位灯那段在 CPU 上先合成) | 80 tick 一周余弦, 只往亮的一侧; 20..24 格渐隐 | 灯 hi, α 0.12..0.32 |
| 皮带追光 | 极品 (档 3) 起 | `rail_b` 北面发光的两行 | 只在皮带步进 (包络 9→12 淡入, 23→27 淡出), 与皮带同速, 每 4 px 一颗彗星 (2.5 px 渐变尾 + 1.5 px 暗槽) | 头 mix(hi, 白, .25) (闪耀金) α 0.9, 暗槽 α 0.6 |
| 宝石脉冲 | 闪耀 (档 5) | `gem` 北 / 南 / 东 / 西 / 顶 | 冲压白闪 (1 起 8 落) / 落箱回响 × 0.8 (1 起 6 落), 取大的 | mix(hi, 白, .7) / 饰带金 |

- 表里的时间都是**程序 tick** (40 一个循环): 与运动件一起按档位变快 (见"档位速度", 闪耀 2 倍); 运行呼吸与满仓闪烁是游戏时间, 不变。
- **一帧** (`MunitionsBenchLights.compute(frame, 档, ACTIVE, 满仓, 开工以来的整 tick, gameTime, partialTick, 距离)`, 结果写进调用方预先分配的定长 `Frame`, 每帧不分配):
  循环 tick = `MunitionsBenchProgram.programTick(elapsed, partial, 档)` = `((elapsed mod 该档循环) + partial) × 40 / 该档循环` (与运动件的 `sample(long, float, 档, Pose)` 同一个映射),
  开工以来的程序时间 = `(elapsed + partial) × 40 / 该档循环` (按同一个顺序算, 第一轮里与循环 tick 逐位相同), 呼吸相位 = `floorMod(elapsed, 80) + partial`, 待机时钟 = `floorMod(gameTime, 40) + partial` (后两个是游戏时间);
  起点比本地时钟快 (elapsed < 0) 时与运动件一样停在首帧; 第一轮开工时跨接缝的尾巴 (落箱 / 入口灯) "没发生过", 不画 (`d ≤ elapsed`, 程序时间)。
  `d = wrap(t − 峰, 40)` 的 `wrap` 用 fmod (非负原样取余, 负数加 40): 映射过的程序时间有满 53 位尾数, 旧写法 `((t % p) + p) % p` 先加 p 会舍掉末位,
  第一轮里 d 比 elapsed 大一个 ulp, 中级档起开工头一 tick 的底火灯闪断 (档位速度落地时 GameTest 抓到; 生成器的 `firstCycleProblems` 现在也逐档查这一条)。
  JS 镜像与 Java 一样一次只有一个档位: `lightFrame({tier, ..})` 必须给档位 (整数, 漏给直接报错, 不静默按普通档), 记在返回的帧上 (`s.tier`);
  `lightOverlays(s)` 的效果与颜色取帧上的档位 (手搭的帧可以另给, 两处不同也报错), 预览不会出现 "普通档的速度配闪耀的颜色"。`ber.mjs` 的 `programTick` / `cycleTicksOf` 等同样要求给档位 (`requireTier`)。
  顺序固定: 追光 → 前沿灯带 (呼吸 + 工位灯) → 冲压 → 落箱 → 满仓 → 宝石。最多 `LIGHT_MAX_QUADS` = 32, 实际各档最多 9 / 9 / 19 / 22 / 22 / 27 (生成器逐 0.25 tick 数)。
- **三条硬约束** (lights.mjs 文件头): `rendertype_text_background.fsh` 丢掉 α < 0.1 的片元 → 每个顶点的 α 要么 ≥ 0.1 要么整块不画 (追光按暗槽的 α 判, 彗尾渐变两端都 ≥ 0.1;
  顶点 α 按 `alphaByte` = round(a × 255) 写, ≥ 26); textBackground 按距离排序并写深度 → 同一块面上任何两个四边形不重叠 (呼吸与工位灯切段, 工位灯段 `A = 1 − (1 − b)(1 − I)`,
  `rgb = (I·led + (1 − I)·b·hi) / A`); 颜色 × `level.getShade(面方向按朝向转过去, 目标的 shade 标志)` (宝石 shade:false 各面 × 1.0, 与静态面一致)。
- **目标面** (`TARGETS` → `LIGHT_TARGET_*`): 元素名 + 面 + 面上的范围 (整台像素, 朝北); 只盖露在外面的部分 (`rail_b` 北面顶上一行是钢, `can_strip` 顶面只露 x 0.5..1; 所以它的北面切两段: x 0.5..1 往上胀接顶面, x 1..8 上面是与它齐平的弹药箱正面, 不往上胀, 东端面也只往前胀, 否则 1/8 px 的灯色会画到箱身上);
  grow = 哪几条边往外胀 lift, 同一元素相邻的覆盖面在棱上接成闭合的壳 (不留静态色的缝); 浮出 lift = 0.125 px (= `COUNTER_LIFT_PX`), 宝石 1/32 px (1 px 的宝石浮 0.125 会像一层壳)。
  `FULL_BRIGHT` 比部分静态灯带的自发光亮: strip / can_strip / gem 15 严丝合缝, crown_light 14, rail_b 11。
- **BER 契约** (`MunitionsBenchCounterRenderer`, 计数屏的字之后): 同一个 `RenderType.textBackground()` 的 VertexConsumer (额外 draw call 0, 不放在任何提前 return 之后),
  距离门与计数屏共用 (> 24 格不画); 角 = `MunitionsBenchLights.blockCorners(frame, partsYRotationDegrees(朝向), ..)` (朝向变换 = 计数屏的 `MunitionsBenchCounter.benchToBlock`,
  不做运动件的 y 翻转), 顶点顺序从面外看逆时针, 每个角自己的顶点色; 面明暗方向 = `Direction.from3DDataValue(worldFace(LIGHT_TARGET_FACES[目标], 朝向角))`;
  `LightTexture.FULL_BRIGHT`; 开工以来的 tick 与档位与运动件同一个 (`MunitionsBenchRenderer` 算好传进来)。LEGACY 台子不画。
- **光敏** (上限 3 Hz, 生成器与 GameTest 逐档核对): 每块灯面每个循环最多亮暗一次 (工位灯各段、冲压闪光、落箱脉冲: 普通 0.5 Hz, 闪耀 1 Hz), 只有宝石两次 (冲压白闪 + 落箱回响; 宝石只有闪耀, 2 Hz);
  满仓 0.5 Hz 与呼吸 4 s 一周走游戏时间, 不随档位变; 追光瞬时 = 1.5 Hz × 倍速, 闪耀正好 3.0 Hz = 上限 (用户认可: 只是后护栏背光两行 23 × 1 px 的小面)。除追光暗槽外都只往亮的方向拉。
- **渲染包围盒**: 方块实体的渲染包围盒 (视锥剔除用) 高到 `RENDER_TOP_PX` = 24.25 px, 由生成器取运动件 (22.25) 与覆盖层最高点 (宝石顶面浮出后 24.03, `lights.mjs overlayTopPx`)
  里高的那个向上取整; 方块实体代码没改, 只是这个常量变了 (GameTest 核对每个覆盖层的角都在它以下)。
  **已知 / 有意不做**: 前沿覆盖层浮出正面 1/128 格, 包围盒水平方向仍是两整格 (要改就得动方块实体): 只有画面里恰好只剩这 1/8 px 时才会被剔掉, 看不出来。
- **改灯效**: 改 `lights.mjs` 的目标面 / 时间 / 颜色, 重新生成 (前提核对不过不写文件); 改画法 (曲线、切段、合成、顺序、角), `MunitionsBenchLights.java` 与 `lights.mjs` 两边一起改,
  再跑 `check_parity.mjs` 的灯效对拍与 `render_lights.mjs` 看图。GameTest (`MunitionsBenchLightsGameTests`) 核对: 六档各自的速度下脉冲的峰都在程序动作到底的那一刻 (从 `MunitionsBenchProgram` 量出来;
  冲压闪光 / 压弹头工位灯 / 宝石白闪在游戏时间里正好落在该档的冲压 tick)、第一轮开工时没发生过的尾巴不画而发生过的拍 (六档 × 任意 float 的 partialTick) 与跑久了逐位相同、
  光敏 (六档每路脉冲每循环正好一次、宝石两次, 实测 ≤ 3 Hz; 追光 = 1.5 Hz × 倍速 ≤ 3 Hz; 呼吸与满仓闪烁与普通档逐位相同, 不随档位变快)、
  档位阶梯、α 从不落在 (0, 0.1)、不重叠、不溢出、24 格处呼吸已淡出, 以及每个四边形在四个朝向下都贴在各档静态 JSON 那条灯带的那个面外 (与 `benchPixelToWorld` 相同、绕序朝外、
  `worldFace` 就是它朝的方向、shade 标志与元素相同)、角都在渲染包围盒顶 `RENDER_TOP_PX` 以下。失败信息只在失败时拼 (通过时不为九万多帧造字符串)。

## 关键帧程序 (`MunitionsBenchProgram`)

- 一个循环 8 帧 × 5 tick = 40 tick。关键帧表每行: `tick, beltX, primeY, powderY, ramY, dropY, dieHeat, ramBulletVisible, powderCharged, seated`, 第 0..7 行是方案的 f0..f7, 第 8 行 (tick 40) 是接缝。
- 帧表来自生成器里的 `BELT / PRIME / POWDER / RAM / DROP_Y / DIE_HEAT / RAM_BULLET / POWDER_CHARGED / SEATED`, 场景的逐帧也读同一组表, 两边不会分叉。
- **插值**: 连续量在相邻两行间**线性**插值 (帧表本身就是按缓动取样写的: 皮带 -1 / -2.5 / -4, 出弹 -1 / -2.5 / -4.75; 逐帧 smoothstep 会让皮带在中间帧各停一下); 布尔量取"到达的那一行"。取模 `CYCLE_TICKS`, 负数折回, NaN 取首行。
  `sample(long elapsedTicks, float partialTick, int tier, out)` = `sample((float) programTick(elapsedTicks, partialTick, tier), out)`: 按档位的循环长度映射到程序时间 (见"档位速度"), 先在 long 里取模, 台子连开几天也不抖。
- **接缝**: 接缝行 = 下一轮 f0 的机器姿态, 但皮带上的弹仍按这一轮编号 (`beltX = f0 - BELT_PITCH`, 出弹留在箱里, 装药/压弹头状态同 f7)。到下一轮 f0 时弹位整体换一次号, 画面只多出入口位落下的一只新壳, 箱里那发 (已被箱体完全挡住) 消失。生成器核对这两点以外画面不变。
- `STRIKE_TICK` = 冲头最低的那一行 (f2, tick 10), 也是压模最热的一帧; 冲头夹着的弹头此刻正好落在壳口 (`MunitionsBenchGeometry.SPARK_*` = 14.5, 11.5, 7.5, 生成器核对; GameTest 另用 `RAM_BULLET_REST_BOTTOM_PX` + 此刻的 ramY 核对一遍)。
- 冲程 (弹缩小后): 底火冲杆 / 装药管 -1.5 (f0 / f1), 冲头 0 / -1.75 / -3.75 (f0 / f1 / f2)。两根杆下探到底与冲压时刻的弹头底都正好是壳口 (`BELT_TOP_PX` + `ROUND_CASE_HEIGHT_PX` = `SPARK_Y`), GameTest `benchProgramStrikesOncePerCycleAndIdlesAtRest` 按 Geometry 的静止位下端 + 程序偏移核对。
  服务端在程序起点 + `strikeTick(档)` + n × `cycleTicks(档)` 播冲压音 (`isStrikeTick(e, 档)` / `nextStrikeTickAfter(e, 档)`; 普通 10 + 40n .. 闪耀 5 + 20n), 渲染器在同一时刻在火花位置放粒子
  (`strikeBetween(上一帧, 这一帧, 档)` = 这段里有没有 `isStrikeTick` 的拍); 渲染器工作时的姿态 = `sampleRunning(e, partial, 档, out)` (e < 0 停在首帧, 否则 = `sample(long, float, 档)`)。
  该档的冲压 tick 映射到程序时间正好是 `STRIKE_TICK` (生成器与 Java 的静态初始化都核对)。GameTest `benchProgramRunsFasterByTierOnAUniformLadderUpToDoubleSpeed` 按拍板的表核对每档的循环 / 冲压、映射单调且整循环折回 0、冲压那一 tick 冲头最低、长时钟与余数逐位相同、拍子、
  渲染器的两个决定 (`sampleRunning` 逐档 = 该档的映射且起点前停在首帧; `strikeBetween` 对逐帧 / 隔几帧 / 隔一两个循环的窗口都 = 服务端逐 tick 的 `isStrikeTick`)、方块的 `tier()` 与注册名对应的档位相同;
  `wideBenchStrikeSoundFollowsTheProgramPhase` (batch `munitions_bench_timing`) 从 mock 玩家的出站包里收冲压音: 普通 10 + 40n、区块包时已在工作的高级 8 + 32n、闪耀 5 + 20n、闪耀的 LEGACY 老台子 5 + 20n。
- `idle()` = 待机布局: 与 f0 相同, 只是底火冲杆收着 (所有偏移 0, 冲头夹着弹头, 装药位与压弹头位都还没做)。

**JS 镜像必须与 Java 一致**: `ber.mjs` 的 `sampleProgram` / `sampleProgramAt` / `programTick` / `cycleTicksOf` / `strikeTickOf` / `isStrikeTickMirror` / `nextStrikeTickAfterMirror` / `sampleRunningMirror` / `strikeBetweenMirror` / `idlePose` 逐行对应 `MunitionsBenchProgram.sample` / `programTick` / `cycleTicks` / `strikeTick` / `isStrikeTick` / `nextStrikeTickAfter` / `sampleRunning` / `strikeBetween` / `idle` (档位参数必须给, 见 `requireTier`), `applyPoseMirror` 对应生成的 `MunitionsBenchParts.applyPose` (直接解析它的 `place(...)` 行); 预览与对拍只读 Java 源 (`parsePartsJava` / `parseProgramJava`, 含每档的循环表), 不读生成器内部的表。改了 Java 的手写部分 (插值、取模、时间映射的运算顺序、列号) 必须同步改 `ber.mjs`, 改了写出格式必须同步改解析器; `check_parity.mjs` 会单独编译 `MunitionsBenchProgram.java`, 与 JS 镜像对拍 (程序时间逐位、姿态 1e-5)。

## 轮廓箱与碰撞 (`MunitionsBenchGeometry`)

- `MAIN_BOXES` / `EXTENSION_BOXES`: 静态件的轮廓箱, 各格局部像素、朝北, 每组元素在该格里那一段的包围盒 (旋转件取旋转后的角点), 向外取整到 0.25 px。组: 底座柜体台面、弹药箱、箱盖、备用弹药箱、皮带、压机前/后立柱、压机横梁与缸、料斗、落壳管、装填塔、装填塔横梁。
- `MAIN_PART_BOXES` / `EXTENSION_PART_BOXES`: 运动件的轮廓箱, 每个件在关键帧表 (含接缝行) 与待机里扫过的包围盒, 切到各格; 被别的盒子包住的并进去。让皮带上的弹、冲头、两根杆都点得中台子。
- 饰件不进轮廓: 台面上的托盘、控制台、急停、运行灯、箱里冒出的弹头, 以及各档加件 (料斗色带、信号灯、料斗灯框、宝石)。踢脚条在底座箱里。箱盖组 = 窗后本体 + 四条框条。
- 轮廓 (选择框 / 右键命中 / 支撑面) = 静态件盒子 + 运动件盒子; **碰撞**不用这些盒子, 是每格一整块实心柱, 高到该格静态盒子的最高点 (主格 22.5, 副格 20.5): 台面只有 8 px, 低于跨步高度 9.6 px, 贴着模型的碰撞会让玩家走上台面站进运动件中间; 柱子也高过起跳高度 (约 20 px)。
- 别的朝向用 `rotated(box, quarterTurns)` 绕格子中心 (8, 8) 转 (俯视顺时针, 与方块状态的 y 旋转同向)。
- `BODY_TOP_PX` = 台面 8; `MODEL_TOP_PX[档]` = 22.5 (闪耀 24); `SHAPE_TOP_PX` = 22.5; 运动件扫过 `PARTS_MIN` .. `PARTS_MAX` (x 5.75..27.25, y 4.25..22.25, z 6.25..8.75; 顶是静止时藏在压机里的连杆顶, 底是没入弹药箱的出弹), `RENDER_TOP_PX` = 24.25 (运动件与灯效覆盖层里高的那个, 见"运行灯效")。
- 流水线设计尺寸 (GameTest 用, 不照抄数字): `BELT_TOP_PX` 9, `SLOT_X_PX` {26.5, 22.5, 18.5, 14.5, 10.5}, `SLOT_Z_PX` 7.5, `ROUND_CASE_HEIGHT_PX` 2.5, `ROUND_BULLET_HEIGHT_PX` 1.5,
  静止位下端 `RAM_BULLET_REST_BOTTOM_PX` 15.25 / `PRIME_ROD_REST_BOTTOM_PX` 13 / `POWDER_TUBE_REST_BOTTOM_PX` 13。轮廓测试用各弹位壳身正中与夹着的弹头正中点选。

## 对拍 (`check_parity.mjs`)

- 游戏 = 静态 `_line_` JSON (两格) + 从 `MunitionsBenchParts.java` 解析、按 `MunitionsBenchProgram.java` 的 JS 镜像摆好的运动件 (方块面明暗); 方案 = 候选生成器自己写出的逐帧 / 待机 / 工作 JSON。
- 用例: 普通档 f0..f7 (t = 0, 5 .. 35) 各 8 个视角 + 3 个运动件特写; 六档待机、六档工作 (t = `STRIKE_TICK`, 方案的 `_active` 就是 f2) 各 8 个视角; 六档 32 px 物品图标。每个视角 420×340, 任一通道有差即算不同; 整幅画面错开亚像素 (正视/侧视的像素中心会压在贴图像素分界上, 取哪一格只看浮点误差)。
- 相对方案有意补上的运动件面 (`DELIBERATE_FACES`: 压模顶面、冲头夹着的弹头底面) 在与方案对拍时不画: 方案按 JSON 的规矩剔除背面, 不画它们就是方案的样子。
- 四个朝向: 各格模型按方块状态 y 旋转烘、副格放在顺时针一侧、运动件按渲染器朝向角烘, 与朝北的整台绕主格中心转过去的四边形集合必须相同。
- **背面检查**: 游戏里运动件用 `entityCutoutNoCull`, 不剔除背面。普通档待机 + 一个循环每 1.25 tick, 7 个俯/平视角 + 2 个仰视角 (台子下面垫着它所在的那一格方块), 运动件各画一遍不剔除 / 剔除, 有一个像素不同就是件的背面露了出来 (`parity-backfaces.png`)。
- **计数屏对拍** (不需要 `--cand`): 单独编译 `MunitionsBenchCounter.java` + `MunitionsBenchGeometry.java`, 与 `counter.mjs` 逐值比较:
  格式 (定点 + 各数量级 3000 个伪随机数)、两套字形与字宽、满度条、满仓、显示键、rects (发数 × 口径 × 上限 × 满仓)、颜色表 (`colourOf`, 含档位越界回退)、
  渲染器摆角用的 `benchCorners` / `blockCorners` (四个朝向角, 容差 1e-5), 以及 Java 运行时的 `COUNTER_*` 与 `parseCounterJava` 从源码解析出的布局;
  另外核对 `CALIBER_LABELS` 与 `MunitionsCaliber.java` 的 shortLabel。
- **程序对拍** (不需要 `--cand`): 单独编译 `MunitionsBenchProgram.java`: `sample(float)` 程序时间 -10..90 每 0.25 tick; 每档的时间映射 (档位 -1..6, 越界按普通档) ×
  九个 partialTick (四个不是 0.25 的倍数) × (每档逐 tick 两个多循环, 含起点之前 + 很大的 long: 连开几天、`10080 × 10^11 + 7`、2^52 − 1), `programTick` 逐位相同、`sample(long, float, tier)` 容差 1e-5;
  `cycleTicks` / `strikeTick`、`isStrikeTick` / `nextStrikeTickAfter` 每档 -45..125; 渲染器的两个决定: `sampleRunning` (每档 -3..两个多循环 × 九个 partialTick) 与
  `strikeBetween` (每档 since -45..125 × 窗口 -1..41 tick, 另核对每个窗口 = JS 里逐 tick 数服务端 `isStrikeTick` 的拍); `idle()`; Java 解析出来的 `CYCLE_TICKS_BY_TIER` = tiers.mjs。
- **灯效对拍** (不需要 `--cand`): `MunitionsBenchGeometry.java` 的 `LIGHT_*` 解析回来与 `lights.mjs` 的 `lightsLayout()` 相同 (防手改);
  单独编译 `MunitionsBenchLights` + `Geometry` + `Counter` + `Program`, 六档 (+ 越界的 -1 / 6) × 工作 / 待机 / 待机满仓 / 工作满仓 × 开工后 -2..170 每 0.25 tick (每档按自己的速度映射)
  × 轮换的相机距离, 另加六档 × 四种状态 × 开工后 -1..90 × 七个不是 0.25 倍数的 float partialTick (含第一轮开工的头几 tick), 以及六档 × 很大的 long (到 2^52 − 1) × 0.25 倍数与七个任意的 partialTick,
  逐帧比较时钟、各效果标量、四边形 (效果、目标、矩形、渐变轴、两端颜色与 α)、`benchCorners` (角 + 顶点色)、
  `blockCorners` (四个朝向轮换), 以及 `effectMask`、`worldFace`; 再核对样本里每档出现过的效果正好是档位阶梯。呼吸用 `StrictMath.cos` (fdlibm, 与 JS 逐位相同)。
- 输出 `parity-report.md`、`parity-frames.png`、`parity-tiers.png`、`parity-items.png` (给了 `--cand` 时), 以及游戏样子的参考图 (实体光照): `game-motion.png` (每 2.5 tick 一列)、`game-base-{active,idle}.png`、`game-tiers.png`、`game-shapes.png` (轮廓箱线框)。有差异时退出码 1。
- 当前结果: 换上计数屏方案 C 之前, 与方案 B v2 除有意补面外全部 0 像素差异 (之后箱盖与箱身正面、再之后弹缩到 2/3 连同冲头 / 两根杆 / 落壳管 / 箱顶 / 托盘 / 料斗顶,
  都按设计与 B v2 不同, 不再对拍; 改动靠同视角的前后对比图人工看)。弹缩小之后 (不给 `--cand`): 四个朝向对齐 (每朝向 382 个四边形), 没有背面露出,
  Java/JS 程序 11138 个采样一致 (其中 4680 个档位时间映射), 计数屏 6877 个值一致 (含四个朝向的 blockCorners 416 组、benchCorners 104 组),
  灯效 328185 个值一致 (33772 帧 / 125519 个四边形; 把 Java 的循环 tick 改回 float 加法时报 14240 处不同, 即评审抓到的第一轮底火灯闪断)。
  档位速度的变异各试过一次: 灯效不按档位映射 (174450 处不同)、程序映射改成乘 40 / 循环 (程序 501 + 灯效 188 处, 不再逐位相同)、取模改成 `%` (程序 252 处)、
  只把 Java 的 `wrap` 改回 `(t % p + p) % p` (灯效 488 处)。

## 改模型

改 `generate_munitions_bench.mjs` 的 `scene()` / 帧表 / `PART_DEFS` / `SHAPE_GROUPS`, 重新生成; 再用 `render_mb.mjs` 看图。
改了运动件的元素时, `PART_DEFS` 里每个件的来源场景与元素名要跟着改 (生成器逐帧核对摆好的件与场景里的运动件完全相同, 对不上就报错)。
场景一改, 与方案 B v2 的逐像素对拍自然不再为 0; 对拍只用来证明移植没有走样, 之后以本目录的生成器为准。
