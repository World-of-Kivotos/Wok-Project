# 枪匠工作站建模工具

机械冲压机 (`gunsmith_press`) 与枪械组装台 (`gunsmith_assembly_bench`) 的方块模型、图集贴图和机械臂几何/动作都由这里的脚本生成, 不要手改生成物。
全部是零依赖 Node 脚本 (本机便携版在 `D:\DevTools\node-v22.23.3-win-x64`)。

| 脚本 | 作用 |
|---|---|
| `generate_press.mjs --out <仓库根>` | 冲压机: 待机/工作两套方块模型、物品模型、`gunsmith_press_atlas.png` |
| `generate_assembly_bench.mjs --out <仓库根> [--margins]` | 组装台: 四个部位 × 待机/工作共 8 个模型、物品模型、`gunsmith_assembly_atlas.png`、机械臂贴图 (含携带件); 改写 `GunsmithAssemblyBenchRenderer.java` 的 `createBodyLayer()` 与 `GunsmithArmProgram.java` 的 `<generated>` 区块 (几何常量 + 放件下沉参数 + 关键帧表), 整个写出 `GunsmithGunBed.java` (枪床常量)。取件点/安装点按真实几何反解, 写出前逐 tick 细分扫描整段 160 tick 程序的穿模 (放件下沉的几种组合各扫一遍, 见下文), 以及渲染器从安装点缓回待机 (`easeToDock`) 的轨迹; 内容没变的文件不重写。`--margins` 另外二分出每种组合下的最小间隙 (只打印) |
| `render.mjs --repo <仓库根> --block press\|assembly --state idle\|active --out x.png` | 多视角预览组图 (含夜间自发光与 32px 物品栏图标); `--tick 0..160` 看机械臂程序某一时刻; `--contact EYE\|T --every 8` 出机械臂连拍表; `--overlay <目录>` 优先读取另一套资源做对比; `--drops auto\|<枪机>,<枪托件>` 机械臂的放件下沉量 (默认 auto: 按台上的枪算, 与游戏同一规则, 空台 = `MAX_PLACE_DROP`; `0,0` = 关键帧表原样) |
| `build_compare_html.mjs --repo <仓库根> --config <cfg.json> --out page.html` | 多套方案并排的交互对比页 |
| `render.mjs ... --block assembly --gun <枪 id> [--packs <tacz 目录>] [--lod]` | 台上放这把 TACZ 枪 (裸枪, 按摆放规则侧躺在枪床上), 组图、特写、连拍表都生效; `--frame bed` 是枪床特写取景, `--frame weld` 是两个安装点特写, `--closeup FEYE` 是玩家站在台前的视角; `--lod` 画 LOD 模型 + LOD 贴图 (游戏里高模只在 8 格内且每帧最多 3 台, 其余用 LOD; 没有 LOD 的枪退回高模, 32 格外不画) |
| `render.mjs --repo <仓库根> --guns all\|<id,id> [--view FEYE] [--frame bed\|weld] [--state active\|--tick t[,t...]] [--lod] [--cols n --cellW w --cellH h] --out x.png` | 枪械表: 每把蓝图枪一格 (`--tick` 给多个时刻时每把枪每个时刻一格), 同一视角, 标 id / 台上长度 / 朝上一侧 / 两个安装点的下沉量; 机械臂按每把枪自己的下沉量摆 |
| `render.mjs --repo <仓库根> --gun <枪 id> --fixed-frame --out x.png` | 只画这把枪, 留在 TACZ FIXED 定位系: 左侧 / 右侧 / 俯视 + TACZ 槽位图标, 查贴图镜像或上下颠倒 |
| `check_bench_guns.mjs [--repo <根>] [--packs <tacz 目录>] [--guns id,id] [--lod\|--high] [--results results.json] [--json out.json]` | 台上真枪的摆放与机械臂校验 (见下文), 默认高模与 LOD 都查, 打印逐枪表格, 有硬失败时退出码 1 |
| `build_gun_gallery_html.mjs [--repo <根>] [--packs <tacz 目录>] --out page.html` | 台上真枪的交互预览页 (按 claude.ai Artifact 页面约定写, 可直接发布): 按平台分组选枪、待机/工作、机械臂程序播放与时间轴 (带关键帧动作)、玩家视角等预设, 每把枪附台上长度 (px / 格)、朝上一侧、下沉量与校验结果; `#m4a1` 这样的裸记号打开即选中那把枪 |
| `build_arm_anim_html.mjs --repo <仓库根> --out page.html [--base 92d21935] [--overlay-dir <目录>]` | 机械臂动作改前/改后同步播放页 (`?t=53` 打开即定格): 自动 `git show <base>:...` 导出旧模型与旧渲染器到临时 overlay (用完即删; 给了 `--overlay-dir` 则保留), 旧动作按旧时间窗 (80 tick 一轮) 播, 新动作按关键帧程序播。`--base` 默认是换成关键帧程序前的最后一个提交 `92d21935`, 不要用 HEAD (关键帧程序提交后 HEAD 已是新动作); 选的提交里渲染器没有 `motionWindow`/`IDLE_UPPER_ARM_Z` 时直接报错 |

`raster.mjs` 是预览的软件光栅核心, 复刻原版 JSON 模型语义 (元素旋转、面 uv、面明暗、Forge `forge_data` 光照), 并直接解析机械臂的 Java 源码 (渲染器的 `createBodyLayer()` 与 `GunsmithArmProgram` 的关键帧表), Node 与浏览器共用。
关键帧插值 (`sampleArmProgram`) 只有这一份 JS 实现, 生成器的穿模扫描也用它; 它与 Java `GunsmithArmProgram.sample` 逐行对应, 改一边必须改另一边。

## 机械臂程序

- 关键帧表每行: `tick, yaw, upperArm, forearm, toolSpin, claw, payload, spark, linear, station`, 行尾注释是动作名 (预览页显示它)。
- 连续量在相邻两行间 smoothstep 插值, 两行相同即停顿; `payload`/`spark` 取 "到达的那一行" 的值。夹取拆成两行: 爪子合拢那一行不带件, 紧跟的停顿行才带件 (零件在爪子合拢到位后出现); 松开那一行不带件 (爪子一张开零件就算装上)。
- `linear` 列是插值方式: `0` 关节角直接插值, 只允许用在手臂不动的段落 (停顿、夹爪开合、点焊); `1` 竖直下探/抬起, `2` 安全高度平移。`1`/`2` 走同一套计算: 偏航、水平伸出、高度各自缓动, 实时反解大臂/小臂角, 所以竖直段腕部走直线、平移段腕部高度恒定不上浮。
- 生成器 `checkProgram` 把换刀座待机点也当低位: 偏航只能在安全高度改变, 离开/落回任何低位 (换刀座、料盘、枪上) 必须是 `linear = 1`, 手臂移动不许用 `linear = 0`。
- 料盘西端两个黄色角标格是送料位, 上面摆着与携带件同形同位的静态零件 (元素名 `tray_pick_*`)。携带件用 `CubeDeformation` 外扩 0.05-0.08 px, 夹起时不与静态零件共面闪烁; 穿模扫描里静态零件只在自己的取件段对携带件豁免、对爪子放宽到 >= 0。
- 碰撞检测除了臂段表面/体内采样点和元素角点, 还检查元素棱上每 0.05 px 的点 (细长或斜置元素的棱会从采样点之间穿过)。
- 两个安装点 (`PLACES`) 都在枪床上的隐形枪体包络 `gun_envelope` 顶面 (见下节), 不依赖台上具体是哪把枪; 安全高度 = 最高的接触点 + 3 px。运行时再按台上的枪下沉 (下一条)。
- **放件下沉**: 关键帧表按包络 (最厚的枪) 反解安装点, 台上的枪更薄时零件和焊花会悬空, 所以两个安装点在运行时按台上的枪往下沉:
  - `station` 列标出安装点的低位行 (`1` 枪机、`2` 枪托件: 下探就位、停顿、点焊、松开), 其余行为 `0`。
  - `GunsmithArmProgram.sample(tick, boltDrop, stockDrop, out)` (JS `sampleArmProgram(program, tick, boltDrop, stockDrop)` 逐行对应): 带 `station` 的行腕部高度按该安装点的下沉量降低; 两端各按自己的下沉量降低后再插值、反解, 下探/抬起仍是竖直直线; `linear = 0` 的行只要带下沉也走同一套反解, 停顿与点焊停在降低后的姿态。下沉量在里面再夹到 `[0, MAX_PLACE_DROP]` (非正、NaN 按 0)。两个下沉量都为 0 时与旧的 `sample(tick, out)` 逐位相同 (`sample(tick, out)` 就是下沉 0, 服务端的点焊时刻不受影响)。`wristPosition`/`contactPosition` 从关节角算, 焊花自然落在枪上。
  - 生成区块里的参数: `BOLT_PLACE_MIN_X/MAX_X/MIN_Z/MAX_Z/BOTTOM_Y` 与 `STOCK_PLACE_*` = 未下沉的放件姿态下携带件 (含 `CubeDeformation` 外扩) 底面的范围与底面 y (朝北整台像素; 范围向外、底面向下取整到 1e-4); `PLACE_CLEARANCE` = 0.02; `MAX_PLACE_DROP` = 空床时零件正好落在床面上方 `PLACE_CLEARANCE` 的下沉量 (两个安装点取小的)。
  - 下沉量的算法 (Java `GunsmithBenchGunLayout.placeDrop` 与 JS `raster.mjs` 的 `placeDrop` / `gunStationTop` / `benchGunPlaceDrops` 两边一致): 安装点 s 的枪顶 `gunTop` = 台上画出来的枪的方块 (见下节的裸枪规则) 里, 台上 AABB 与底面范围严格重叠 (`maxX > MIN_X && minX < MAX_X && maxZ > MIN_Z && minZ < MAX_Z`) 的那些的 AABB 最大 y (方块 AABB = 8 个原始角点 (不含 inflate) 在 FIXED 定位系里的 AABB 经摆放变换后的像; 摆放的旋转只是换轴/取反, 所以是精确的); 没有重叠的方块时取 `TOP_Y`。下沉量 = `clamp(BOTTOM_Y - gunTop - PLACE_CLEARANCE, 0, MAX_PLACE_DROP)`; 台上没画枪 (空台、加载中、失败、没装 TaCZ) 时取 `MAX_PLACE_DROP`。
  - 生成器校验: 两个安装点各取 0 / `MAX_PLACE_DROP` 的四种组合 (外加两处都取一半), 每种都查整段程序反解可达、竖直段不偏、降低的行正好降了下沉量, 并做整段穿模扫描。任一端带下沉的程序段不查隐形包络 (零件要落到比包络低的真枪上; 真枪由 `check_bench_guns.mjs` 按各自的下沉量查), 只查台子本身; 其余段落照旧按包络避让。
  - 注意: 下沉只看零件底面范围里的枪顶; 两只爪子在范围外 (z 向各伸出约 1 px), 爪尖比零件底面高 1 px, 枪在爪子下方更高的部分由 `check_bench_guns.mjs` 逐把查。
- 服务端按 `spark` 段的起点播放焊接音 (`GunsmithArmProgram.nextWeldTickAfter`), 客户端渲染器在同一时刻于零件接触点生成焊花粒子。
- 客户端程序起点 = 方块实体在 `setBlockState` 里看到 ACTIVE 由假变真的那一 tick (不依赖组装台当时是否在视野里); 区块加载时已在装配中的才退回渲染器首帧计时。程序没走到停靠 (`PARKED_TICK`) 就被切回待机时, 渲染器用 6 tick (`RETURN_TICKS`) 按关节角缓回待机姿态 (`easeToDock`), 放件下沉在缓回的前 35% 里收回 (否则从下沉姿态直接缓回, 爪子会扫过料盘上的枪托件)。生成器从渲染器源码读这两个值, 对两个安装点窗口 (下探起点..抬起终点) 里每 0.5 tick 一个起点、0..`MAX_PLACE_DROP` 共 9 档下沉量扫整段缓回, 穿进台子即校验失败; `easeToDock` 的写法变了 (对不上生成器里的几行正则) 也报错, 要一起改 `sweepEaseToDock`。
- 改动作 = 改 `generate_assembly_bench.mjs` 的 `buildProgram` / `TRAY_SLOTS` / `PLACES`, 重新生成; 校验不过不会写任何文件。

## 枪床与台上的枪

组装台上没有静态的枪。前排 (主格 + 侧格, z < 16) 是一张平放的枪床: 钢底板上前沿一条刻度扫描灯带 (刻度零点在托底, 每 1 px 一小格、每 4 px 一长格; 工作态发光, 兼做维修垫后沿的定位灯), 后面是青色防滑胶垫 (2 px 网格, 两端零位角标); 东端橙色托底挡块 (朝西一面是胶垫, 顶上状态灯工作态亮橙), 西端一对定位销。
床面 (灯带 + 胶垫) 在 z 7.75..15.75, 即枪能占的 `AXIS_Z ± HALF_WIDTH` 前后各宽 0.25 (`BED_MARGIN`), 最宽的枪也不伸出床沿; 维修垫与垫上的零件因此整体北移了 0.25 (z 1.75..7.75)。
运行时由 `GunsmithAssemblyBenchRenderer` 把台里那把枪的真实 TACZ 模型侧躺在胶垫上 (空台不画枪)。

- 摆放常量在生成器的 `GUN_BED` 里, 生成器把它整个写成 `GunsmithGunBed.java` (Java 渲染器与 GameTest 用), 同时 `export { GUN_BED }` 给 JS 预览 import, 两边读同一份数值, 不要手改 `GunsmithGunBed.java`。
  坐标系同上 (朝北整台局部像素): `BUTT_X` 枪托端 x (挡块胶垫面), `TOP_Y` 床面 y, `AXIS_Z` 枪包围盒 z 中心, `MAX_LENGTH` 渲染长度上限, `HALF_WIDTH` 剖面高度的一半上限 (3.75, 就是隐形包络的 z 半宽: 摆放规则的宽度压缩保证任何枪都在机械臂扫描用的包络 z 范围里, 两者不许分开改), `SIZE_A`/`SIZE_P` 尺寸曲线, `SIDE_UP_THRESHOLD` 朝上一侧的判定阈值, `ENVELOPE_THICKNESS` 隐形包络厚度。
- 摆放规则 (Java `GunsmithBenchGunLayout` 与 JS 预览的实现逐条一致, 改一边必须改另一边): 输入是枪在 TACZ FIXED 定位系 (未缩放 px; 枪口 -X, 枪顶 +Y, 枪的左侧 +Z) 里画出来的方块的包围盒 B (方块的原始边界, 不含 inflate), 以及枪口参考点 (`muzzle_pos` / `muzzle_flash` / `muzzle_default` 中第一个存在的骨骼, 不管它画不画) 的 z0 (都没有取 B 的 z 中心)。
  **画出来的方块** = TACZ 1.1.8 给裸枪显示 stack (台上的枪不带配件) 画的, 纯按模型结构判定, 不读运行时的 `visible` (JS `raster.mjs taczBareDrawn`, Java 同一规则), 高模与 LOD 一样。不画:
  (a) `lefthand_pos`/`righthand_pos` 子树; (b) `muzzle_flash`、`bullet_in_barrel`、`bullet_in_mag`、`bullet_chain`、`mount`、`sight_folded`、`mag_extended_1..3`、`handguard_tactical`、`additional_magazine`、`laser_beam` 子树;
  (c) `attachment_adapter` 下: 它每个具名子节点的整棵子树 (`BedrockGunModel.attachmentAdapterNodeRender` 只显示枪上配件点名的子节点, 裸枪一个都不显示), 以及它自己带 `rotation` 的方块 (新格式加载时包成无名子节点, 无名子节点永远不显示); 它自己不带 rotation 的方块照画。
  所以台上的枪就是游戏里没装配件的样子: M4A1 没有枪托 (成品的 `tacz:stock_m4ss` 是配件, 不画), RPK、AKM、MP5A5 的枪托转接件也不画, 枪托端照样抵住挡块。
  1. `L` = B 的 x 长度, 非正或非有限 → 不画。
  2. 渲染长度 `min(MAX_LENGTH, SIZE_A * L^SIZE_P)`, 缩放 `k` = 它 / L; `k *` B 的 y 长度 > `2 * HALF_WIDTH` 时 `k` 再压到正好。
  3. `(B.maxZ - z0) - (z0 - B.minZ) > SIDE_UP_THRESHOLD` → 左侧朝上 `(X, Y, Z) → (X, Z, -Y)`, 否则右侧朝上 `(X, Y, Z) → (X, -Z, Y)` (枪顶朝 +z 即机械臂, 握把/弹匣朝玩家; 装着的包里只有司登 `wyyc1991:stl` 的侧插弹匣会左侧朝上)。
  4. 平移到包围盒最大 x = `BUTT_X`、最小 y = `TOP_Y`、z 中心 = `AXIS_Z`。
  参考长度 (高模): M1911 约 8.8 px, UZI 14.4, MP5A5 14.8 (剖面宽度压到 2 × `HALF_WIDTH`), HK416D 18.7 (同样被宽度压缩), UMP45 18.7, M4A1 19.9, RPK 20.4, AKM 21.6, 步枪/狙击枪/M1887 封顶 23.2。LOD 模型各自测量, 例如 M4A1 的 LOD 自带枪托, 20.97 px。
- 生成器校验 (`gunBedChecks`): 真枪要占的空间 x ∈ [BUTT_X - MAX_LENGTH, BUTT_X]、z ∈ AXIS_Z ± HALF_WIDTH、y ∈ [TOP_Y, TOP_Y + 6] 里没有任何可见元素; 这块空间的 x 全长、z 向再前后各宽 `BED_MARGIN` 的范围里每 0.25 px 都有顶面正好在 `TOP_Y` 的元素托着; `BUTT_X` 处有挡块的西面; 隐形包络在上述空间内且 z 半宽等于 `HALF_WIDTH`。常量与几何对不上就不写文件。
- 隐形枪体包络 `gun_envelope`: x ∈ [BUTT_X - MAX_LENGTH, BUTT_X]、y ∈ [TOP_Y, TOP_Y + ENVELOPE_THICKNESS]、z ∈ AXIS_Z ± HALF_WIDTH 的一块 `proxy` 元素, 六个面全是 null (`down` 也要显式写 null, 否则默认是底色面), 所以不进方块模型、不进图集、不做共面检查、也不遮挡别的面 (`cullHidden` 跳过 proxy), 只当机械臂的碰撞体和 (未下沉的) 安装面。
  现有 21 把枪 (高模与 LOD) 平躺后厚度都 < 2.25 px (最厚的 RPK 约 2.1 px), 只有司登的侧插弹匣 (x 14.6..16.3, z 10.0..10.7) 高出包络, 不在两个安装点下方, 也低于安全高度下携带件的底面。
  例外是 SPR15HB 的 LOD 模型: 有一个方块 inflate 为负 (画出来比原始边界小), 按原始边界摆放、量枪顶时它厚 2.36 px, 画出来的枪离床面约 0.5 px, 两个安装点的下沉量被夹到 0 (零件停在包络顶面, 离画出来的枪约 0.4 px); 只在用 LOD 时出现 (8 格外, 或每帧 3 台的高模名额用完时), 不穿模。
  包络后沿 (AXIS_Z + HALF_WIDTH) 不能超过 15.5: 再往后, 料盘取件时张开的左爪就会进入它 0.3 px 的间隙。
- `GunsmithAssemblyBenchBlock` 的碰撞/选取箱在枪床上方给枪留了一块 (x 3..27.5 含定位销, z 8..15.5, 高到 y 13), 玩家站不进台上的枪; 机械臂不参与碰撞。
- 物品图标里仍是一把固定的步枪 (`itemRifle`, 只进物品模型, 不进方块模型也不当碰撞体)。

## 台上真枪的预览与校验

预览与校验读的是测试端装着的真枪包 (`--packs`, 默认 `D:\WOK测试\versions\1.20.1-Forge_47.4.20\tacz`, 只读), 画的是渲染器用的同一个模型: 默认 `display.model` 高模 + `display.texture` 原图 (渲染器用 `GunDisplayInstance.getGunModel()` 画的高模), `--lod` 换成 `display.lod` 的模型与贴图。游戏里高模只在 8 格内且每帧最多 3 台 (`GunsmithBenchGunBudget`: 7.5 格内才换上高模、8.5 格外才换回; 名额按台留给上一帧最近的 3 台 (这一帧没画到也留着), 别的台要比其中最远的那台近出 0.5 格以上才挤得进来, 晚一帧换人), 其余用 LOD; 没有 LOD 时游戏与预览都退回高模, 游戏里 32 格外不画。
台上的枪一律是裸枪显示 stack 画出来的样子 (上节的 `taczBareDrawn` 规则), 摆放、下沉量、校验与画面用同一组方块, 没有第二种画法。

- `taczpack.mjs` (仅 Node): 零依赖读枪包。`TaczPacks(dir)` 把目录下每个文件夹、每个 `.zip` (包根 = `gunpack.meta.json` 所在目录; zip64 + deflate, 用 `node:zlib`, 不解压到磁盘) 当成一个包, 按目录顺序取第一个提供者; `resolveGun(packs, id, {lodGeo})` 走 TACZ 的索引链 `data/<ns>/index/guns/<path>.json` → `display` → `geo_models` 高模 + 贴图, 另带 LOD (`lodGeo: true` 时连 geo 与贴图一起读; 文件缺失只记 notes, TACZ 会静默改用高模)、槽位图标、`zh_cn` 显示名; `readBlueprints(repo)` 从 `GunsmithBlueprint.java` 读出蓝图枪表; `bakeBenchGun(packs, id, bed, {lod})` = 解析 + 烘焙到枪床上。
- `raster.mjs` 的 `bakeBedrockGeo(json, image, {bed, frame, bbox})` (Node 与浏览器共用) 按 TACZ 1.1.8 `BedrockModel` 的加载约定烘焙 Bedrock geo:
  根骨骼 `(px, 24 - py, pz)`、子骨骼相对父骨骼 (y 取反)、`T · Rz · Ry · Rx` 原始角度; 方块有 `rotation` 时包一层以方块 pivot 为原点的子节点; 盒式 UV 尺寸按 `(int)` 截断、`mirror` 交换 x 并反转顶点 (骨骼的 `mirror` 是方块的默认值); 逐面 UV 与 JSON 键 east/west、up/down 互换 (`FaceUVsItem.getFace`); `inflate` 只放大几何; UV 按 geo 的 `texture_width/height` 计 (PNG 常是它的 2-4 倍)。
  只收 `taczBareDrawn` 判为画出来的方块, 再乘 TACZ FIXED 定位系 (`scale(-1,-1,1)` · fixed 骨骼世界矩阵的逆), `frame: 'bench'` 时按 `layoutGunOnBed` 摆到枪床上。背面剔除看顶点绕序 (与游戏的 `entityCutout` 一致), 厚度为 0 的方块只出两个大面。`_illuminated` 骨骼 (含子树) 全亮。
  包围盒 B 默认用方块的原始边界 (`bbox: 'raw'`, 即 Java 能从 `BedrockCubeBox`/`BedrockCubePerFace` 的 `minX..maxZ` 读到的值, 不含 inflate); 每个方块另给 `aabb` = 8 个原始角点在 FIXED 定位系里的 AABB 经摆放变换后的像 (安装点枪顶用它)。`stats.adapterCubes` 是 `attachment_adapter` 下没画的方块数。
- **JS ↔ Java 对照规则**: `layoutGunOnBed(B, z0, bed)` 是摆放规则在 JS 里唯一的实现, 与 Java `com.miningdim.job.munitions.block.GunsmithBenchGunLayout` 逐步对应 (同样的 1-5 步、同样的量: `L`、`T`、`k`、朝上一侧、`R`、`t`), 改一边必须同时改另一边; 画哪些方块 (`taczBareDrawn` 与 `TACZ_HAND_BONES`、`TACZ_HIDDEN_BONES`、`TACZ_ADAPTER_BONE`)、枪口参考点顺序 (`TACZ_MUZZLE_NODES`)、安装点枪顶与下沉量 (`gunStationTop`、`placeDrop`, 合起来是 `benchGunPlaceDrops`) 同样两边一致。数值一律来自生成器的 `GUN_BED` (= `GunsmithGunBed.java`) 与 `GunsmithArmProgram` 的生成区块。
- `check_bench_guns.mjs`: 对每把蓝图枪的高模与 LOD 模型 (有的话):
  (a) 整把枪在枪床留空范围内 (x 全长、y 留空高度、z = 隐形包络的 z 范围 `AXIS_Z ± HALF_WIDTH`), xz 投影全部落在床面 (顶面在 `TOP_Y` 的 `bed_*` 元素) 上, 且不穿入任何方块元素 (生成器 `buildScene` 的待机 + 工作态元素, 不含隐形包络; 贴着床面与挡块算接触, 容差 0.001 px)。这几项按原始边界查 (摆放规则的约定); 画出来的 inflate 另查, 伸进台子只报 WARN (几把 LOD 模型的 inflate 伸进床面 0.001-0.003 px), 负 inflate 让画出来的枪离床面 / 挡块有缝也报 WARN。
  (b) 按这把枪自己的放件下沉量 (`benchGunPlaceDrops`, 与游戏同一规则) 以生成器的扫描密度 (每 tick 8 份) 走完仓库里 `GunsmithArmProgram.java` 的整段程序, 臂段 / 爪子 / 携带件 (含 `CubeDeformation` 外扩) 与枪的每个方块 (含 inflate) 做 OBB 分离轴检测; 任何时刻任何部件都不许穿入 (携带件落到枪上也只许贴上), 反解必须全程可达; 安装窗口 (任一端带 `station` 列的程序段) 外离枪不到生成器的 0.3 px 报 WARN。
  (c) 两个安装点的点焊姿态: 携带件底面与它投影范围内枪的实际顶面 (逐 0.05 px 竖直线与方块 OBB 求交) 的竖直间隙, 目标 `PLACE_CLEARANCE`; 规则按方块 AABB 量枪顶, 所以斜放的方块或只擦到底面范围边上的方块会让零件多悬空一点 (现有枪最多约 0.57 px, KAR98K 的 LOD)。
  表格列出每个模型的原始长度、台上长度、朝上一侧、台上包围盒、两个下沉量、两个点焊间隙、零件 / 夹爪 / 其余臂段离枪最近的距离。`--results` 另把 FIXED 定位系包围盒 (含 inflate) 与枪口 z 和侦察脚本的 `results.json` 逐把对照 (侦察时还没有 `attachment_adapter` 规则, 有转接件方块被隐藏的 4 把枪不符只记 note)。
- `build_gun_gallery_html.mjs`: 单文件预览页, 按 claude.ai Artifact 的页面约定写 (没有 doctype / html / head / body, 以 `<title>` 开头; 颜色全是 `:root` 令牌, 浅色为底、深色两处重定义; 手机宽度可用; 系统要求减少动效时不自动播放, 停在第一次点焊)。内联 `raster.mjs` (去掉 `export`)、全部蓝图枪 (geo 只留加载需要的键, 不画的方块去掉)、台子两套模型、机械臂与每把枪的校验结果; 浏览器端与 Node 同一条 `bakeBedrockGeo` → `layoutGunOnBed` → `benchGunPlaceDrops` 链。深链只用裸记号: `#m4a1`、`#m1887_long` (枪 id 的路径部分)。
  贴图能无损缩到 geo 分辨率 (逐像素颜色与镂空不变) 才缩, 否则内联原图: 现有枪包的 PNG 不是 geo 分辨率的整数倍放大, 平均缩小会翻转一半以上不透明像素的镂空 (例如司登枪管护套整片消失)。台子 + 枪 + 地面按视角缓存成一层, 播放时每帧只光栅机械臂。
- 预览与游戏的其它已知差别: 正交投影 (玩家视角只取视线方向: 台前 1.5 格、眼高 1.62 格看枪床中心, 俯角约 21°); 光照是近似的实体光照; 镂空阈值 alpha 128 (游戏 `entityCutout` 是 0.1); 不画配件 (台上的显示 stack 本来就不带配件, 成品 M4A1 的 `tacz:stock_m4ss` 枪托在台上看不到, 这是定下的取舍)。

两个生成脚本都先完整校验 (坐标、旋转角、uv、图集分配、共面重叠、机械臂贴图区域、程序时长与穿模等) 再写文件, 校验失败不会覆盖仓库里的产物。
改了组装台模型的体积 (包括枪床与留给枪的空间) 后, 记得同步 `GunsmithAssemblyBenchBlock` 里的碰撞箱。
