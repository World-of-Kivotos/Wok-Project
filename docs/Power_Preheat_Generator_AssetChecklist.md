# 前期发电机（煤炭 / 地热）美术资源需求清单

## 一、文档说明

- 用途：煤炭发电机与地热发电机的美术需求与资产状态清单。逻辑与数值已全部实现并通过测试，本文只列美术资产，**不含任何待实现的机制**。
- 机制真源：[Power_Economy_Rebalance_DesignSpec.md](Power_Economy_Rebalance_DesignSpec.md) 第二章。本文不改机制。
- 当前状态：**全部资产已有占位且游戏内可正常显示**，不是紫黑格。占位由 `tools/build_preheat_generator_textures.py`（方块贴图）与 `tools/build_power_ui_assets.py`（界面底图）程序生成，配色取自既有 power 界面调色板。美术出成品时**直接覆盖同名 PNG 即可，无需改动任何 JSON 或 Java**。
- 验收保险：`PreheatGeneratorGameTests.blockAssetsExistAndMatchModels` 会断言模型引用的每一张贴图真实存在且为 16x16。替换后跑 `gradlew runGameTestServer` 即可确认没有漏文件或改错尺寸。
- 命名铁律：一律小写下划线，与注册 id 完全一致。大小写或拼写不符会导致材质丢失（紫黑格），且不会有任何报错。
- 禁止事项：文件名、图层名、注释中不得出现 emoji。

## 二、方块贴图（8 张，P1）

全部为 **16x16 PNG**，放置于 `src/main/resources/assets/miningdim/textures/block/`。

模型走原版 `minecraft:block/orientable` 父模型，即：顶面用 `top`、正面用 `front`、其余四面共用 `side`。底面自动复用 `top`。

### 2.1 煤炭发电机

| 文件名 | 用途 | 占位现状 | 美术要求 |
| --- | --- | --- | --- |
| `coal_generator_top.png` | 顶面 | 钢灰板 + 中央凹槽 | 工业锅炉顶盖，可带排烟口 |
| `coal_generator_side.png` | 四侧面 | 钢灰板 + 横向散热栅 | 铆接钢板 + 散热栅，与顶面同材质语言 |
| `coal_generator_front.png` | 正面（熄火） | 深色炉门 + 冷观火口 | 炉门紧闭、观火口无光 |
| `coal_generator_front_on.png` | 正面（燃烧） | 炉门 + 琥珀色火光 | 与熄火态**同构图**，仅观火口透出火光 |

定位参考：这是玩家最早接触的发电设备，风格应偏"手工铆接的粗糙锅炉"，不要过于精密或高科技。

### 2.2 地热发电机

| 文件名 | 用途 | 占位现状 | 美术要求 |
| --- | --- | --- | --- |
| `geothermal_generator_top.png` | 顶面 | 玄武岩底 + 青色导管 | 岩基顶盖 + 热交换管路 |
| `geothermal_generator_side.png` | 四侧面 | 玄武岩底 + 竖向散热柱 | 玄武岩质外壳 + 竖向散热柱 |
| `geothermal_generator_front.png` | 正面（冷却） | 岩缝 + 无光热窗 | 热交换窗关闭、无岩浆光 |
| `geothermal_generator_front_on.png` | 正面（工作） | 岩缝 + 岩浆橙光 | 与冷却态**同构图**，热交换窗透出岩浆光 |

定位参考：它必须建在岩浆源上方，风格应偏"嵌进地热带的岩石构造"，与煤炭机的钢铁感形成对比。

### 2.3 两个共同的硬要求

1. **`_front` 与 `_front_on` 必须构图一致，只差发光部分。** 游戏里这两张会随 `lit` 状态实时切换，构图不一致会导致视觉跳变。
2. **熄火态不得有任何发光像素。** 玩家靠正面是否发光判断机器在不在工作，这是唯一的外部状态指示。

## 三、界面底图（1 张，P2）

| 文件名 | 路径 | 画布 | 有效区域 |
| --- | --- | --- | --- |
| `preheat_generator.png` | `src/main/resources/assets/miningdim/textures/gui/power/` | 256x256 | 左上角 218x222 |

煤炭机与地热机**共用这一张**底图。现有占位由 `tools/build_power_ui_assets.py` 的 `build_preheat_generator()` 生成，与提纯机、空分机等既有界面同风格。

界面元素坐标（**代码已按此渲染，改图必须保持这些位置**）：

| 元素 | 坐标 | 尺寸 | 说明 |
| --- | --- | --- | --- |
| 燃料槽 | (101, 36) | 18x18 | 地热机该槽恒为禁用态，但底图仍需绘制 |
| 温度条 | (20, 74) | 178x7 | 代码填充红色，底图只需画凹槽轨道 |
| 能量条 | (20, 94) | 178x7 | 代码填充青色 |
| 燃烧条 | (20, 114) | 178x7 | 代码填充琥珀色；地热机此条恒为空 |
| 玩家背包 | (28, 142) | 9x3 格 | 标准 18px 网格 |
| 快捷栏 | (28, 200) | 9x1 格 | 同上 |

三条量条的**填充色由代码绘制**，底图只需要画空轨道（凹槽 + 描边），不要预先画填充。

若要精修，建议保留：温度条在最上（它是这台机器的第一读数——温度决定功率），能量条居中，燃烧条最下且视觉权重最低。

## 四、不需要的资产

- **物品图标**：两台机器的物品模型直接复用方块模型（`models/item/*.json` 的 parent 指向 `miningdim:block/*`），不需要单独的 2D 图标。
- **燃料物品图标**：煤炭机吃原版可燃物（煤、木炭、煤块及任何 mod 燃料），没有自有燃料物品。
- **粒子 / 音效**：本期未接，如需另议。

## 五、替换流程

1. 覆盖 `src/main/resources/assets/miningdim/textures/block/` 下的目标 PNG（保持文件名与 16x16 尺寸）。
2. 如需精修界面，覆盖 `assets/miningdim/textures/gui/power/preheat_generator.png`（保持 256x256 画布与第三章坐标）。
3. 跑 `gradlew runGameTestServer`，确认 `blockAssetsExistAndMatchModels` 通过。
4. 若不再需要程序占位，可删除 `tools/build_preheat_generator_textures.py`；但建议保留，它记录了占位的构图依据。

**注意**：程序生成脚本与手绘成品是互斥的——重跑 `build_preheat_generator_textures.py` 会覆盖掉手绘 PNG。美术定稿后请勿再执行该脚本。
