# Kivotos Armorer

从 World of Kivotos 主项目独立出来的纯护甲 Forge MOD。

## 内容

- 54 件六档插板护甲，保留独立模型、贴图、防弹/穿甲/物理防护、承压、机动与耐久逻辑。穿戴模型是离线烘焙的 2× 精度网格（`assets/kivotos_armorer/armor_meshes/plate_armor_<id>.json`，逐面 UV 图集贴图），逐字节取自 WOK 本体 2026-10-04 的插板护甲重做（`3d0c4b72`）；旧的 49 个手写 `*ArmorModel` 与图层注册已删除。
- 18 件六档电浆护盾，保留能量、过热、散热、充能、HUD、受击反馈和音效。
- 3 个旧电浆护盾 ID 兼容物品，仅用于旧存档/命令兼容，不显示在创造栏。
- 可选 TaCZ 弹种识别与统一损甲兼容。

不包含生产台、方块实体、校准 QTE、职业经验、纳米护甲板、配方生产链或主 MOD 的其他系统。

## 环境

- Minecraft 1.20.1
- Forge 47.3.0
- Java 17
- TaCZ 1.1.8 hotfix（运行期可选降级，但编译期必需：`armor/integration` 包直接 import `com.tacz.guns.api`，缺 jar 一定编译失败）

## 构建

1. 从官方渠道获取 `tacz-1.20.1-1.1.8-hotfix.jar`。它是第三方 jar，不随本仓库分发，需自行创建 `standalone/kivotos-armorer/libs/` 目录并把 jar 放进去（`build.gradle` 以 `compileOnly files('libs/tacz-1.20.1-1.1.8-hotfix.jar')` 引用它，路径与文件名必须完全一致）。
2. 执行构建：

```powershell
./gradlew.bat build
```

成品位于 `build/libs/kivotos_armorer-1.20.1-1.1.0.jar`。

`build` 会经 `check` 执行 `verifyPlateArmorMeshes`，在普通 JVM 里逐件校验打包的烘焙网格与穿戴贴图（结构、法线、
部位锚点、贴图尺寸与不透明像素、客户端解析器能否接受），任一件违约即构建失败；同步本体美术后也可单独执行
`./gradlew.bat verifyPlateArmorMeshes`。

## 接入别的项目

最简单的方式是把成品 JAR 放进目标整合包的 `mods`。若目标也是 ForgeGradle 工程，可把 JAR 放进目标工程的
`libs`，再按本地依赖方式加入；本 MOD 使用独立 `kivotos_armorer` 命名空间，不要求加载原 `miningdim` MOD。

## 版本

### 1.1.0

- 54 件插板护甲的穿戴模型换成烘焙网格管线：新增 `PlateArmorMesh`、`PlateArmorMeshCache`、
  `PlateArmorBakedModel`，`PlateArmorClient` 改为按网格绘制；网格缺失或损坏时退回原版胸甲模型并打一次警告。
- 删除 49 个手写 `*ArmorModel`、`PlateArmorModelDefinition` 与 `PlateArmorClientRegistration`
  （旧的 `RegisterLayerDefinitions` 注册随管线一并移除，新管线在模型内部自建空骨架）。
- 美术同步 WOK 本体 `3d0c4b72`：新增 54 个 `armor_meshes/plate_armor_<id>.json`，替换 54 张
  `textures/models/armor/plate_armor_<id>_layer_1.png` 为逐面图集，并更新 3 个物品图标
  （`plate_armor_kirasa_n_green`、`plate_armor_tv115`、`plate_armor_6b5_15_flora`）与名称条目。
- 观感变化：`6b45_general`、`6b45_medic`、`jaypc_olive`、`jaypc_black`、`mbss`、`paca`、`tv115` 这 7 件的旧模型
  把肩带或护肩挂在手臂部位上，会随摆臂；新网格两臂为空，肩部结构改由躯干承载（6B45 两件、MBSS、TV115 伸出到躯干
  两侧，JPC 两件与 PACA 收在躯干宽度内），走路摆臂时不再跟随手臂。这是本体重做的设计结果，不是同步遗漏。
- 新增构建期任务 `verifyPlateArmorMeshes`（校验代码在 `src/verify`，不进 JAR），校验项与阈值取自本体
  `3d0c4b72` 的网格契约用例。
- 物品 ID、数值、配置、电浆护盾与 TaCZ 集成都未改动。

### 1.0.0

- 首个独立版本。

