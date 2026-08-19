# 电力系统美术资源清单（前期发电机 + 三级储电）

## 一、文档说明

- 用途：记录煤炭发电机、地热发电机与三级储电的 20 张方块贴图和 2 张界面底图。逻辑与数值以 [Power_Economy_Rebalance_DesignSpec.md](Power_Economy_Rebalance_DesignSpec.md) 第二章为真源，本文只定义美术资产与渲染契约。
- 当前状态：本清单中的 22 张 PNG 均按最终资产管理，采用原版 Minecraft 的硬边像素语言；它们不是临时稿，也不保留后续美术替换依赖。
- 方块资产真源：`tools/build_preheat_generator_textures.py`，可复现生成 8 张煤炭／地热发电机贴图与 12 张三级储电贴图。
- 界面资产真源：`tools/build_power_ui_assets.py`，可复现生成 `generator.png`、`preheat_generator.png`、`power_cell.png`、`metallurgic_purifier.png`、`air_separation.png`、`low_temperature_controller.png` 六张 power 界面；本文只登记其中 `preheat_generator.png` 与 `power_cell.png`。
- 命名铁律：文件名一律使用小写下划线，并与注册 id、模型引用完全一致。大小写或拼写不符会导致材质丢失。
- 禁止事项：文件名、图层名、注释与文档中不得出现表情符号。

### 1.1 当前自动验收范围

- `PreheatGeneratorGameTests.blockAssetsExistAndMatchModels` 覆盖五台设备的 20 张唯一方块贴图，核对 blockstate、待机／工作模型、`orientable` 父模型、贴图引用、物品模型、16x16 尺寸、全不透明、无纯白像素，以及 `front`／`front_on` 的变化范围。
- `PowerUiContractGameTests` 覆盖六张 power 界面；其中本清单的 2 张界面会接受 256x256 RGBA、218x222 逻辑边界、槽位坐标、alpha、透明像素 RGB 与 `.mcmeta` 禁用检查。
- 因此本清单的直接资产覆盖量为 20 张方块贴图加 2 张界面底图。测试负责守住格式和引用，不替代 1:1 游戏内视觉检查。

## 二、方块贴图（20 张，已定稿）

全部为 **16x16 RGBA PNG**，放置于 `src/main/resources/assets/miningdim/textures/block/`。模型使用原版 `minecraft:block/orientable` 父模型：顶面使用 `top`，正面使用 `front`，其余四面使用 `side`，底面复用 `top`。

硬像素与白边防护契约：

1. 20 张贴图的每个像素都必须完全不透明，alpha 固定为 255。
2. 贴图任何位置都不得使用纯白 `#FFFFFF`，因此外圈也不得出现纯白描边；高光使用偏冷的浅钢色。
3. 所有线条、倒角、铆钉、栅格与发光簇均对齐 1 像素整数网格；禁止抗锯齿、模糊、亚像素描边与插值缩放。
4. 材质层次依靠有限色板、明暗块和 1 像素高光表达，保持原版 Minecraft 方块贴图的硬边与可读性。

### 2.1 煤炭发电机（4 张）

| 文件名 | 用途 | 最终构图 | 视觉约束 |
| --- | --- | --- | --- |
| `coal_generator_top.png` | 顶面 | 钢灰铆接顶板 + 中央凹槽 | 粗糙工业锅炉顶盖，可读作排烟／检修结构 |
| `coal_generator_side.png` | 四侧面 | 钢灰板 + 横向散热栅 | 与顶面同材质语言，避免高科技精密感 |
| `coal_generator_front.png` | 正面待机态 | 深色炉门 + 冷观火口 | 工作窗无火光 |
| `coal_generator_front_on.png` | 正面工作态 | 同一炉门 + 琥珀色火光 | 仅工作窗像素可相对待机态变化 |

定位参考：这是玩家最早接触的发电设备，观感为手工铆接的粗糙锅炉。

### 2.2 地热发电机（4 张）

| 文件名 | 用途 | 最终构图 | 视觉约束 |
| --- | --- | --- | --- |
| `geothermal_generator_top.png` | 顶面 | 玄武岩底 + 青色热交换导管 | 岩基顶盖与管路必须在 16x16 下清晰分层 |
| `geothermal_generator_side.png` | 四侧面 | 玄武岩壳 + 竖向散热柱 | 与煤炭机的钢铁感形成材质对比 |
| `geothermal_generator_front.png` | 正面待机态 | 冷却岩缝 + 无光热交换窗 | 工作窗无岩浆光 |
| `geothermal_generator_front_on.png` | 正面工作态 | 同一岩缝 + 岩浆橙光 | 仅工作窗像素可相对待机态变化 |

定位参考：设备必须建在岩浆源上方，观感为嵌入地热带的岩石构造。

### 2.3 三级储电（12 张）

每档均由 `top`、`side`、`front`、`front_on` 四张同名前缀贴图组成。

| 档位 | 四张文件 | 最终配色 | 视觉约束 |
| --- | --- | --- | --- |
| 一级 | `industrial_power_cell_top.png`、`industrial_power_cell_side.png`、`industrial_power_cell_front.png`、`industrial_power_cell_front_on.png` | 钢灰底 + 青色能量窗 | 铆接钢壳，工业感最强 |
| 二级 | `modern_power_cell_top.png`、`modern_power_cell_side.png`、`modern_power_cell_front.png`、`modern_power_cell_front_on.png` | 蓝灰底 + 亮青能量窗 | 外壳更精密，保留可读的散热结构 |
| 三级 | `future_power_cell_top.png`、`future_power_cell_side.png`、`future_power_cell_front.png`、`future_power_cell_front_on.png` | 深蓝紫底 + 紫色能量窗 | 高能感，与未来设备共享材质语言 |

三档需要在同屏中一眼可区分，主要依靠底色明度与能量窗色相，不依赖 16x16 下不可读的细碎纹理。储电的 `lit` 状态表示本 tick 有进出电流；待机态能量窗不得发光。

### 2.4 待机／工作正面差异契约

`front` 与 `front_on` 的机壳、边框、铆钉、阴影和构图必须逐像素一致，并且至少有一个工作窗像素发生变化。允许变化的坐标范围如下，坐标原点为贴图左上角：

| 设备前缀 | 允许变化范围 |
| --- | --- |
| `coal_generator` | x=6..9，y=7..10 |
| `geothermal_generator` | x 属于 5..6 或 9..10，且 y 属于 6..7 或 9..10 |
| `industrial_power_cell` | x=5..10，y=5..7 |
| `modern_power_cell` | x=6..9，y=5..8 |
| `future_power_cell` | x=5..10，y=5..10 |

## 三、界面底图（2 张，已定稿）

| 文件名 | 路径 | 物理画布 | 逻辑可见区域 | 共用者 | 生成函数 |
| --- | --- | --- | --- | --- | --- |
| `preheat_generator.png` | `src/main/resources/assets/miningdim/textures/gui/power/` | 256x256 | 左上角 218x222 | 煤炭机 + 地热机 | `build_preheat_generator()` |
| `power_cell.png` | 同上 | 256x256 | 左上角 218x222 | 三档储电 | `build_power_cell()` |

两张界面延续六张 power 界面的深色钢框、冷色金属、高对比凹槽和 1 像素硬边体系。量条填充色由 Screen 代码绘制，底图只保留空轨道、描边和刻度，不预绘填充值。

### 3.1 前期发电机界面坐标

| 元素 | 坐标 | 尺寸 | 说明 |
| --- | --- | --- | --- |
| 燃料槽 | (101, 36) | 18x18 | 地热机该槽恒为禁用态，但底图仍保留槽框 |
| 温度条 | (20, 74) | 178x7 | 代码填充红色；温度决定输出，视觉顺序最高 |
| 能量条 | (20, 94) | 178x7 | 代码填充青色 |
| 燃烧条 | (20, 114) | 178x7 | 代码填充琥珀色；地热机恒为空 |
| 玩家背包 | (28, 142) | 9x3 格 | 标准 18 像素网格 |
| 快捷栏 | (28, 200) | 9x1 格 | 标准 18 像素网格 |

### 3.2 三级储电界面坐标

| 元素 | 坐标 | 尺寸 | 说明 |
| --- | --- | --- | --- |
| 主容量表 | (20, 36) | 178x18 | 代码绘制容量填充 |
| 输入表 | (20, 84) | 178x7 | 代码绘制输入速率填充 |
| 输出表 | (20, 104) | 178x7 | 代码绘制输出速率填充 |
| 玩家背包 | (28, 142) | 9x3 格 | 标准 18 像素网格 |
| 快捷栏 | (28, 200) | 9x1 格 | 标准 18 像素网格 |

三级储电没有机器槽位，`power_cell.png` 不得绘制任何机器槽框。

### 3.3 UI 硬像素与白边防护契约

1. 文件必须是 256x256、8 bit RGBA PNG；逻辑可见区域固定为 x=0..217、y=0..221，并且可见内容必须触及 x=217 与 y=221。
2. 逻辑区域之外必须全透明；全图 alpha 只允许 0 或 255，不允许半透明抗锯齿像素。
3. alpha 为 0 的像素，其 RGB 也必须为 0，避免纹理采样时把隐藏颜色带到透明边缘形成白边或彩边。
4. 禁止生成对应的 `.png.mcmeta`，尤其禁止 `blur` 线性过滤；所有轮廓、倒角、槽框与刻度保持 1 像素硬边。
5. 槽框左上像素必须保持钢框色 `#FF4D606F`，且底图槽框坐标必须与 Menu 槽位坐标完全一致。

## 四、不需要的资产

- 物品图标：五台设备的物品模型直接复用各自方块模型，不需要独立 2D 图标。
- 燃料物品图标：煤炭机使用原版可燃物，没有自有燃料物品。
- 粒子与音效：本清单不定义这两类资源。

## 五、改稿与复现流程

1. 方块改稿先修改 `tools/build_preheat_generator_textures.py` 中对应设备的生成逻辑；界面改稿分别修改 `tools/build_power_ui_assets.py` 的 `build_preheat_generator()` 或 `build_power_cell()`。
2. 运行两份真源脚本重新生成 PNG。未来若从外部绘图工具导入同名 PNG，无需修改 JSON 或 Java，但必须把最终像素同步回生成脚本，避免下一次复现覆盖成旧稿。
3. 核对 20 张方块贴图仍为 16x16，并核对两张界面仍为 256x256、逻辑区域 218x222、坐标不变；不得附带启用模糊的 `.png.mcmeta`。
4. 运行 `.\gradlew.bat runGameTestServer`，确认 `PreheatGeneratorGameTests.blockAssetsExistAndMatchModels` 与 `PowerUiContractGameTests` 的资产契约通过。
5. 生成脚本属于最终资产真源，不得在定稿后删除，也不得只修改输出 PNG 而让脚本与成品分叉。
