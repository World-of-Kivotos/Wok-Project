# 枪匠工作站建模工具

机械冲压机 (`gunsmith_press`) 与枪械组装台 (`gunsmith_assembly_bench`) 的方块模型、图集贴图和机械臂几何/动作都由这里的脚本生成, 不要手改生成物。
全部是零依赖 Node 脚本 (本机便携版在 `D:\DevTools\node-v22.23.3-win-x64`)。

| 脚本 | 作用 |
|---|---|
| `generate_press.mjs --out <仓库根>` | 冲压机: 待机/工作两套方块模型、物品模型、`gunsmith_press_atlas.png` |
| `generate_assembly_bench.mjs --out <仓库根>` | 组装台: 四个部位 × 待机/工作共 8 个模型、物品模型、`gunsmith_assembly_atlas.png`、机械臂贴图 (含携带件); 改写 `GunsmithAssemblyBenchRenderer.java` 的 `createBodyLayer()` 与 `GunsmithArmProgram.java` 的 `<generated>` 区块 (几何常量 + 关键帧表)。取件点/安装点按真实几何反解, 写出前逐 tick 细分扫描整段 160 tick 程序的穿模 |
| `render.mjs --repo <仓库根> --block press\|assembly --state idle\|active --out x.png` | 多视角预览组图 (含夜间自发光与 32px 物品栏图标); `--tick 0..160` 看机械臂程序某一时刻; `--contact EYE\|T --every 8` 出机械臂连拍表; `--overlay <目录>` 优先读取另一套资源做对比 |
| `build_compare_html.mjs --repo <仓库根> --config <cfg.json> --out page.html` | 多套方案并排的交互对比页 |
| `build_arm_anim_html.mjs --repo <仓库根> --out page.html [--base 92d21935] [--overlay-dir <目录>]` | 机械臂动作改前/改后同步播放页 (`?t=53` 打开即定格): 自动 `git show <base>:...` 导出旧模型与旧渲染器到临时 overlay (用完即删; 给了 `--overlay-dir` 则保留), 旧动作按旧时间窗 (80 tick 一轮) 播, 新动作按关键帧程序播。`--base` 默认是换成关键帧程序前的最后一个提交 `92d21935`, 不要用 HEAD (关键帧程序提交后 HEAD 已是新动作); 选的提交里渲染器没有 `motionWindow`/`IDLE_UPPER_ARM_Z` 时直接报错 |

`raster.mjs` 是预览的软件光栅核心, 复刻原版 JSON 模型语义 (元素旋转、面 uv、面明暗、Forge `forge_data` 光照), 并直接解析机械臂的 Java 源码 (渲染器的 `createBodyLayer()` 与 `GunsmithArmProgram` 的关键帧表), Node 与浏览器共用。
关键帧插值 (`sampleArmProgram`) 只有这一份 JS 实现, 生成器的穿模扫描也用它; 它与 Java `GunsmithArmProgram.sample` 逐行对应, 改一边必须改另一边。

## 机械臂程序

- 关键帧表每行: `tick, yaw, upperArm, forearm, toolSpin, claw, payload, spark, linear`, 行尾注释是动作名 (预览页显示它)。
- 连续量在相邻两行间 smoothstep 插值, 两行相同即停顿; `payload`/`spark` 取 "到达的那一行" 的值。夹取拆成两行: 爪子合拢那一行不带件, 紧跟的停顿行才带件 (零件在爪子合拢到位后出现); 松开那一行不带件 (爪子一张开零件就算装上)。
- `linear` 列是插值方式: `0` 关节角直接插值, 只允许用在手臂不动的段落 (停顿、夹爪开合、点焊); `1` 竖直下探/抬起, `2` 安全高度平移。`1`/`2` 走同一套计算: 偏航、水平伸出、高度各自缓动, 实时反解大臂/小臂角, 所以竖直段腕部走直线、平移段腕部高度恒定不上浮。
- 生成器 `checkProgram` 把换刀座待机点也当低位: 偏航只能在安全高度改变, 离开/落回任何低位 (换刀座、料盘、枪上) 必须是 `linear = 1`, 手臂移动不许用 `linear = 0`。
- 料盘西端两个黄色角标格是送料位, 上面摆着与携带件同形同位的静态零件 (元素名 `tray_pick_*`)。携带件用 `CubeDeformation` 外扩 0.05-0.08 px, 夹起时不与静态零件共面闪烁; 穿模扫描里静态零件只在自己的取件段对携带件豁免、对爪子放宽到 >= 0。
- 碰撞检测除了臂段表面/体内采样点和元素角点, 还检查元素棱上每 0.05 px 的点 (45° 斜置的枪身脊线会从采样点之间穿过)。
- 服务端按 `spark` 段的起点播放焊接音 (`GunsmithArmProgram.nextWeldTickAfter`), 客户端渲染器在同一时刻于零件接触点生成焊花粒子。
- 客户端程序起点 = 方块实体在 `setBlockState` 里看到 ACTIVE 由假变真的那一 tick (不依赖组装台当时是否在视野里); 区块加载时已在装配中的才退回渲染器首帧计时。程序没走到停靠 (`PARKED_TICK`) 就被切回待机时, 渲染器用 6 tick 缓回待机姿态。
- 改动作 = 改 `generate_assembly_bench.mjs` 的 `buildProgram` / `TRAY_SLOTS` / `PLACES`, 重新生成; 校验不过不会写任何文件。

两个生成脚本都先完整校验 (坐标、旋转角、uv、图集分配、共面重叠、机械臂贴图区域、程序时长与穿模等) 再写文件, 校验失败不会覆盖仓库里的产物。
改了组装台模型的体积后, 记得同步 `GunsmithAssemblyBenchBlock` 里的碰撞箱。
