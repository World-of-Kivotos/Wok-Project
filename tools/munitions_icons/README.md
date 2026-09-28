# 军火台原料图标

军火台四个原料的物品贴图由这里的脚本生成, 不要手改生成物:

| 物品 | 贴图 |
|---|---|
| 底火 `miningdim:primer` | `textures/item/primer.png` |
| 弹壳 `miningdim:casing` | `textures/item/casing.png` |
| 弹头 `miningdim:bullet_head` | `textures/item/bullet_head.png` |
| 发射药 `miningdim:propellant` | `textures/item/propellant.png` |

风格是 2026-09 图标重绘时选定的方向 C「细像素」: 32×32、成簇色块、3/4 视角、左上受光、每种材质一条 5~7 阶色带 (暗部偏暖、亮部偏黄, 与原版金/铜同样的色相偏移), 外描边后清掉孤立像素。alpha 只有 0/255, 否则手持时的 3D 挤出会破。物品模型是 `item/generated`, 换贴图不用改模型。

全部是零依赖 Node 脚本 (本机便携版在 `D:\DevTools\node-v22.23.3-win-x64`):

| 脚本 | 作用 |
|---|---|
| `generate_input_icons.mjs [输出目录]` | 画四个图标写进 `src/main/resources/assets/miningdim/textures/item/` (或给定目录); 有半透明像素就报错不写 |
| `check_input_icons.mjs [图标目录]` | 只读自检: 覆盖率、军火台深色槽位 (`#292a35`) 上外圈看不清的比例、四者之间与原版图标的剪影重合度 (IoU)、平均色差。原版对比需要客户端资源 jar (`MC_CLIENT_EXTRA_JAR`, 默认读 ForgeGradle 缓存), 找不到就跳过 |

画法要点 (改之前先读 `generate_input_icons.mjs` 里各函数的注释):

- 底火: 镍杯杯口朝上斜放, 杯底暗红击发药上压一个 Y 形黄铜击砧; 后面叠一枚小杯, 剪影不像雪人也不像小锅。
- 弹壳: 斜放, 硬台阶瓶肩 + 肩下 1px 暗线, 抽壳槽、底缘、开口可见。
- 弹头: 竖放的铜被甲尖头弹, 底部露铅; 与斜放的弹壳一竖一斜, 色弱玩家也能分开。
- 发射药: 黑色滚花盖的玻璃药瓶, 灰绿色无烟药粒装到瓶肩, 标签上的实心火焰是全图唯一的红色, 旁边撒出一小撮药粒; 不能画成原版火药那样的灰堆或黄沙。

军火台新界面 (三种风格) 的空槽提示直接用这四个物品图标画淡色剪影, 换贴图后自动跟着变; 旧版界面 `MunitionsBenchScreen.drawGhostSilhouette` 里手画的剪影不随贴图变化, 该界面会被新界面整体替换, 未改。
