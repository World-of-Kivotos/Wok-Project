# 枪匠工作站建模工具

机械冲压机 (`gunsmith_press`) 与枪械组装台 (`gunsmith_assembly_bench`) 的方块模型、图集贴图和机械臂几何都由这里的脚本生成, 不要手改生成物。
全部是零依赖 Node 脚本 (本机便携版在 `D:\DevTools\node-v22.23.3-win-x64`)。

| 脚本 | 作用 |
|---|---|
| `generate_press.mjs --out <仓库根>` | 冲压机: 待机/工作两套方块模型、物品模型、`gunsmith_press_atlas.png` |
| `generate_assembly_bench.mjs --out <仓库根>` | 组装台: 四个部位 × 待机/工作共 8 个模型、物品模型、`gunsmith_assembly_atlas.png`、机械臂贴图; 并只改写 `GunsmithAssemblyBenchRenderer.java` 的姿态常量与 `createBodyLayer()`, 生成前按真实动画时间轴扫描机械臂穿模 |
| `render.mjs --repo <仓库根> --block press\|assembly --state idle\|active --out x.png` | 多视角预览组图 (含夜间自发光与 32px 物品栏图标); `--overlay <目录>` 优先读取另一套资源做对比 |
| `build_compare_html.mjs --repo <仓库根> --config <cfg.json> --out page.html` | 多套方案并排的交互对比页 |

`raster.mjs` 是预览的软件光栅核心, 复刻原版 JSON 模型语义 (元素旋转、面 uv、面明暗、Forge `forge_data` 光照) 并直接解析机械臂的 Java 源码, Node 与浏览器共用。

两个生成脚本都先完整校验 (坐标、旋转角、uv、图集分配、共面重叠等) 再写文件, 校验失败不会覆盖仓库里的产物。
改了组装台模型的体积后, 记得同步 `GunsmithAssemblyBenchBlock` 里的碰撞箱。
