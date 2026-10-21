# 社交平板 · 步战“掏出平板”动画完整版移植

> 状态：M1–M4 全部实现（2026-10-18）；生产客户端（1.20.1-Forge 47.4.22 + MCEF 2.1.6 + `-all.jar`）
> 已跑通 READ / p=1 / 收起三个关键帧，见 §6.4。
> 动作用步战 0.5.0-beta.4 的完整版时间轴，外观用我们的 Slate 11 + 白手套 + HaloLink 页面。

## 0. 来源与归属

- 仓库：`World-of-Kivotos/WOK-Infantry-Warfare-Mode`
- 分支：`claude/掏平板动画`（本仓库提交 `6a4d0ed`，版本 `0.5.0-beta.4`）
- 参考副本：`G:\Wok\_ref_infantry`（浅克隆，仅作源码参考，不参与构建）
- 原始代码版权归步战项目作者；移植到 WOK 的副本在包注释和本文件标注来源。
- 移植策略：**fork 到 `com.miningdim.client.social.tablet`**，不硬依赖步战，也不修改步战仓库。

## 1. 为什么不能直接复用

步战的动画不是一段可调用的特效，而是一个客户端子系统：

- `client/tablet` 下 45 个类；`TabletAnimationController` 直接依赖 `TacticalScreen`、`DeviceArt`、
  `TacticalLivery`、`TacticalPalette`、`TabletHandPass`、HUD 策略和声音表。
- scheme A 在 `RenderHandEvent` 的手部 pass 里画 3D 平板和双手；scheme B 是 2D 兜底；
  开/合/打断/输入/帧捕获/声音都挂在同一个状态机上。
- 步战画的是 `TacticalScreen` 的帧；社交画的是 MCEF `WebUiScreen` 的浏览器贴图。

直接整包拷过来会把步战的战术界面一起带进来，因此按下面的三层适配。

## 2. 实际架构（已实现）

```
SocialTabletAnimation（编排，≈380 行）
  ├─ 动作核心（原样 fork，纯逻辑）
  │    TabletAnimationModel / TabletMotion / TabletPose3D / TabletEasing
  │    TabletMat4 / TabletPoseOps / TabletOp / TabletUnits / TabletPath / TabletTimeMap
  ├─ 设备层
  │    SocialDeviceArt      slate11_shell.png、alpha 轮廓、机身/屏幕比例
  │    SocialTabletRenderer 正面 + 浏览器屏幕 + 挤出侧壁/背板 + 白手套
  │    SocialTabletRenderTypes 正面/浏览器/机身三套 RenderType
  └─ 屏接层（不复制浏览器画面）
       WebUiClient.prepareSocialScreenForAnimation()  3D 期间页面在后台加载
       WebUiClient.showPreparedSocialScreen()         p = 1 时交给 WebUiScreen
```

关键取舍：

- **不做帧捕获**。步战合盖时把屏幕抓成一张 2D 图再缩回手里；我们的浏览器贴图一直活着，
  3D 层在合盖全程直接采样同一张实时贴图，于是 p 从 1 → 0.61 → 0 是一条连续的缩小曲线，
  比“2D 抓帧 + 3D 接管”更干净，也少一套 FBO。
- **屏幕就是整个页面平面**。贴图里屏幕开窗占 960/1024（93.75%），所以机身 = 页面 ⁄ 0.9375，
  页面正好铺满开窗。动画推到 s = 1 时页面铺满视口、机身边框刚好移出屏幕外，
  下一帧出现的 2D 界面与上一帧 3D 画面逐像素对齐。
- **手是自绘方块，不绑死玩家皮肤**：默认仍是白手套（深灰蓝制服袖 + 白色掌部 + 指节/拇指）；
  另有一档 `tabletHands = "skin"`，直接用玩家自己的 64×64 皮肤贴图渲染一只 4×12×4
  （slim 3×12×4）的手臂方块。两档共用“腕点钉在设备下角、前臂向前下方收、推近时随
  `handsExit` 滑出画面”的变换。步战的 `handLocalOps` 在 dev 客户端实测会把前臂横甩到画面
  两侧（见 §6.3），所以握持改用自有变换。

## 3. 类映射

### 直接复用/轻改（纯逻辑）

| 步战 | 社交 | 说明 |
|---|---|---|
| `TabletAnimationModel` | 同名 | 时间轴、KEYS、尺寸常量；颜色常量换 `SocialTabletColors` |
| `TabletEasing` / `TabletPath` / `TabletMode` / `TabletHand` | 同名 | 无外部依赖 |
| `TabletMotion` | 同名 | 状态机/开合/打断/声音 cue |
| `TabletPose3D` / `TabletMat4` / `TabletPoseOps` | 同名 | 3D 姿态/投影 |
| `TabletKeyLatch` / `TabletPassMarks` / `TabletUnits` / `UiRect` | 同名 | 无外部依赖 |
| `TabletCue(s)` | 同名 | 声音表；12 个 ogg 复制到 `assets/miningdim/sounds/tablet/` |
| `TabletDeviceModel` | 同名 | ARGB alpha 轮廓挤出（未改） |

### 替换/新写

| 步战 | 社交 | 说明 |
|---|---|---|
| `TabletAnimationController` | `SocialTabletAnimation` | 只保留开/合、HUD、打断、声音；无 scheme B、无键位转发 |
| `TacticalScreen` / `TacticalShellLayout` | 浏览器贴图 + 全屏 `WebUiScreen` | `SocialDeviceArt`/`WebUiClient` |
| `DeviceArt` / `TacticalLivery` / `TacticalPalette` | `SocialDeviceArt` | Slate 11 银色机身 + 深色玻璃 |
| `TabletReadScope` / `TabletMapFrame` / `TabletFrameCapture` | 不需要 | 浏览器贴图直接采样 |
| `TabletRenderer` / `TabletTextures` / `TabletRenderTypes` | `SocialTabletRenderer` / `SocialTabletRenderTypes` | 正面/屏幕/机身/手套 |
| 步战声音事件 | `miningdim:tablet.*` | `sounds.json` 追加 12 条 |
| `ui.tabletAnimation` | `social.tabletAnimation` | `full` / `quick` / `off` |

## 4. 里程碑

### M1 · 动作核心（完成）
- 24 个纯逻辑类 fork 到 `com.miningdim.client.social.tablet`，本地 `UiRect`/`SocialTabletColors`。
- `compileJava` 通过。

### M2 · Slate 11 设备层（完成）
- `slate11_front.png`（1024×640，透明底、银色机身、屏幕开窗）与 `slate11_shell.png`
  （开窗挖空成深色玻璃，3D 正面用）。
- `TabletD2Geometry` 改为按贴图比例映射，不再依赖 `TacticalShellLayout`。
- 侧壁/背板：`TabletDeviceModel.mesh` 从 shell 贴图 alpha 轮廓挤出，厚 2 cm。
- 白手套：`SocialTabletRenderer.drawGlove`，制服袖 + 白掌 + 指节/拇指；用 dev 客户端的
  8 帧分镜（§6.3）把大小、角度、露出量调到“只在设备下角露一截手套”。

### M3 · 屏幕层 + 控制器（完成）
- `WebUiClient`：拆出 `ensureBrowser`，新增 `prepareSocialScreenForAnimation` /
  `showPreparedSocialScreen` / `cancelPreparedSocialScreen` / `requestSocialCloseWithAnimation`
  与三个宿主钩子；3D 期间页面先加载，p = 1 才 `setScreen`。
- 浏览器贴图以原生 GL id 绑进自定义 RenderType（预乘 alpha 混合，与 `WebBrowser#render` 一致）。
- 开场前把浏览器尺寸/CEF zoom 调成全屏 layout 的同一档，3D→2D 不跳。
- 输入：P 开（`/social` 同路径）、Esc 关；关界面后由动画接管收起。

### M4 · 完整行为（完成，scheme B 为简化兜底）
- 已实现：HUD 让位（保留聊天/标题/玩家列表/调试）、死亡/维度/载具/快捷栏/相机/开火/瞄准打断、
  12 个声音 cue、`social.tabletAnimation` 与 `social.tabletSounds` 配置、
  P 键在开场中跳到结尾、左键点击跳到全屏、第三人称直接开界面、
  手部 pass 缺席时直接显示（简化 scheme B）、`/socialanim` 固定姿态验收命令。
- 未实现：完整 scheme B 的 2D 滑入动画（现在是直接显示）。
- 真客户端验收已完成（§6.4）：生产映射客户端 + MCEF，READ / p=1 / 收起三帧均验证。

## 5. 配置

```toml
[social]
    # full = 完整 3D；quick = 80ms 快开；off = 不播动画，直接开界面
    tabletAnimation = "full"
    tabletSounds = true
    # glove = 白手套（默认）；skin = 玩家自己的皮肤手臂
    tabletHands = "glove"
```

`tabletHands` 也可以在游戏里用 `/socialanim hands glove|skin` 临时切（只影响本次会话，
不改写配置）。皮肤模式拿的是 `minecraft.player.getSkinTextureLocation()`，
slim 皮肤按 `getModelName()` 自动用 3 px 宽手臂。

## 6. 验收

- `gradlew build` 通过（已通过，jar 内含 89 个 tablet 类、3 张贴图、12 个 ogg）。
- 真客户端：P 打开 → 3D 掏出（白手套 + Slate 11）→ p = 1 全屏 HaloLink；
  Esc → 连续缩回手里 → 回到世界。
- 收起全程能看见页面画面（浏览器贴图），不黑屏、不闪白。
- 手部/设备外观是 Slate 11 + 白手套，不是步战战术平板。
- 开/合/打断不重复开场、不抢输入；关闭动画配置可退回 CSS 开场。
- 1080p / 1440p、GUI scale 2/3/4 下屏幕矩形与 2D 界面对齐。
- 30fps 预算内；MCEF 116；GameTest 进程不触客户端类。

### 6.1 固定姿态截图（真机验收辅助）

```
/socialanim freeze 0.3     # 抬起 30%
/socialanim freeze 0.61    # READ（双手托住、推到眼前）
/socialanim freeze 0.8     # 推近中段
/socialanim resume         # 解冻继续
/socialanim reset          # 取消并回到世界
/socialanim hands glove    # 白手套
/socialanim hands skin     # 玩家皮肤手臂
```

冻结时按 F2 截图，手/机身的位置偏差可以直接按帧对比。手部两档的固定姿态对比见
§6.5；注意 `F1`（hideGui）会关掉手部 pass，动画会走 scheme B 直接开界面，测 3D 层时别按。

### 6.2 兜底行为

- 第三人称、或手部 pass 被其它 mod 取消：开场直接显示界面、收起直接结束（简化 scheme B），
  不会出现“看不见的动画”。
- 3D 层抛异常：本次退回直接开界面，日志里留一条 `[SocialTablet] 3D 平板绘制失败`，不崩客户端。
- `social.tabletAnimation = "off"`：P 直接开页面（旧行为）。

### 6.3 dev 客户端分镜验证（2026-10-18）

MCEF 的生产 jar 带 SRG mixin，无法在本机 dev 客户端加载（`CefInitMixin` 的
`@Shadow m_91152_` 找不到），所以用一个临时 harness 把 `SocialTabletAnimation` 的绘制路径
（`Context.of` + `poseAt` + `SocialTabletRenderer.draw`）接到 `RenderHandEvent`，冻结 8 个
姿态抓主帧缓冲。验证到两件真问题：

1. **1.20.1 没有 `minecraft:textures/misc/white.png`**：侧壁/背板/手套原本用的是它，实际绑到
   missing texture（黑/品红），看起来像“没有手”。改为自带
   `assets/miningdim/textures/gui/halolink/white.png`。
2. **步战的 `handLocalOps` 不适合窄边框 Slate**：前臂会横着甩到画面左右下角，形成两大块
   白/红色块。改为自有握持变换（腕点钉在设备下角、前臂向前下方收、缩放 0.72），
   分镜里只剩设备下角露出的一小截白手套 + 指节。

分镜文件：`storyboard-0-0.1.png`（抬起前）、`storyboard-4-0.61.png`（READ，双手托住）、
`storyboard-5-0.75.png`（推近）、`storyboard-7-1.0.png`（p=1，页面区域正好铺满视口）。

3. **原生 GL 贴图的屏幕层已单独验证**：再跑一次 harness，把一张 960×600 的测试卡
   （四角标记 + TOP/LEFT/RIGHT/BOTTOM）用 `DynamicTexture` 上传成原生 GL id，走
   `SocialTabletRenderTypes.browser(id)` 同一条 RenderType。结果：卡片朝向正确、恰好铺满
   玻璃开窗（边框与圆角对齐）、p = 1 时铺满 1280×720 视口。也就是说 MCEF 的浏览器贴图
   接进来时只剩“贴图来源”不同，采样/朝向/对齐这条链路已经验过。
   图：`storyboard-page-0-0.61.png`、`storyboard-page-1-1.0.png`。

已附在真机验收包 `G:\Wok\SocialTablet-Animation-Test\` 里。临时 harness 已删除，仓库里
只剩正式控制器。

### 6.4 生产客户端验收（2026-10-18）

用 `D:\WOK测试` 里的官方 1.20.1-Forge 47.4.22 安装（生产映射）搭了一个干净实例
`G:\Wok\RealClientTest`：只装本 mod 的 `-all.jar` + MCEF 2.1.6 + 它的 `mcef-libraries`，
`--quickPlaySingleplayer` 进测试世界，用一个临时 probe 自动冻结/截图（probe 已删除）。

结果：

- **READ（p=0.61）**：3D Slate 11 在手里，屏幕上是真实 HaloLink 首页（问候语取到玩家名
  `Dev`，说明 `social.bootstrap`/cefQuery/服务端身份链路都通了）；两只白手套在设备下角。
- **p=1.0**：页面铺满 1280×720 视口，与后续 2D 界面逐像素衔接。
- **收起**：从全屏回收到手里，收起曲线与实时页面贴图正常，HUD 全程让位。

**部署教训**：普通 `jar`（19.7 MB）不含 jarJar 内嵌的 sqlite-jdbc，进世界时
`MarketDb.ensureDriverRegistered` 会 `ClassNotFoundException: org.sqlite.JDBC` 并崩集成服务端；
必须分发 `-all.jar`（33.3 MB，含 `META-INF/jarjar/sqlite-jdbc-3.45.3.0.jar`）。验收包已改为 `-all.jar`。

### 6.5 手部两档对比（2026-10-19）

在生产客户端（`G:\Wok\RealClientTest`）用 `/socialanim freeze 0.61` 抓的固定姿态：

- `hands-glove-0.61.png`：默认白手套。轮廓干净，袖口/指节清楚，近景没有贴图放大问题。
- `hands-skin-0.61.png`：`tabletHands = "skin"`。两只手臂直接是玩家皮肤的 4×12×4 方块；
  手臂/指节/拇指同色，但 64×64 皮肤贴到 0.6 格距离上会变成大色块，且原版手臂本来就没有
  手指，近景质感不如手套。
- `hands-compare.png`：两张下角裁切叠在一起。

结论：**皮肤模式可用，但默认仍保持手套**；要“看得出是我的皮肤”就切 `skin`，要干净的
商业产品感就保持 `glove`。两档共用同一套腕点/收手变换，只是几何与贴图不同。

## 7. 风险

1. **手部外观仍需真人审美确认**：尺寸/角度已用 dev 客户端分镜校准到“不挡页面、只在
   下角露一截”；皮肤档在近景会放大 64×64 贴图，若最终选皮肤，还需要再调手臂宽度/露出量
   （`drawSkinArm` 里的 `0.72` 缩放与 `halfW * 0.80` 腕点）。
2. **浏览器贴图生命周期**：MCEF resize 会换贴图 id；RenderType 按 id 缓存，旧 id 会留在
   小表里（不释放，最多几十条），可接受。
3. **放大时的采样**：3D 层是 GL_LINEAR 放大的浏览器贴图，p 接近 1 时文字会比 2D 界面糊一档，
   这是“在手里也能看见实时页面”的代价；p = 1 后由 2D 界面接管。
4. **代码分叉**：fork 后步战更新不会自动同步；M4 后再评估共享库。
