# Kivotos Armorer

从 World of Kivotos 主项目独立出来的纯护甲 Forge MOD。

## 内容

- 54 件六档插板护甲，保留独立模型、贴图、防弹/穿甲/物理防护、承压、机动与耐久逻辑。
- 18 件六档电浆护盾，保留能量、过热、散热、充能、HUD、受击反馈和音效。
- 3 个旧电浆护盾 ID 兼容物品，仅用于旧存档/命令兼容，不显示在创造栏。
- 可选 TaCZ 弹种识别与统一损甲兼容。

不包含生产台、方块实体、校准 QTE、职业经验、纳米护甲板、配方生产链或主 MOD 的其他系统。

## 环境

- Minecraft 1.20.1
- Forge 47.3.0
- Java 17
- TaCZ 1.1.8 hotfix（可选运行依赖；仅编译 API）

## 构建

```powershell
./gradlew.bat build
```

成品位于 `build/libs/kivotos_armorer-1.20.1-1.0.0.jar`。

## 接入别的项目

最简单的方式是把成品 JAR 放进目标整合包的 `mods`。若目标也是 ForgeGradle 工程，可把 JAR 放进目标工程的
`libs`，再按本地依赖方式加入；本 MOD 使用独立 `kivotos_armorer` 命名空间，不要求加载原 `miningdim` MOD。
