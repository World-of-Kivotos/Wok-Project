# WebUI 中文输入 (IME) 设计规格 — W11

状态: **DEFERRED (已推迟, 未实现)**
所属: WebUI 全量接线 W11 横切分支
前置文档: `WebUI_Architecture_DesignSpec.md`、`WebUI_Wiring_Execution_Scope.md` 第四章 W11

本文档不含可直接照抄的实现方案。它的作用是: 把已验证的硬事实钉死、把只能真机解决的未知项列成实验协议、
并**撤销一条已经写进代码注释的错误前提**——那条前提会把下一个动手的人直接送进死胡同。

---

## 一、为什么推迟

W11 与其余 11 个接线分支零耦合 (它不碰任何 action、不碰契约层), 所以推迟它不阻塞任何东西。
反过来, 它有两条别的分支没有的性质:

1. **无法用 GameTest 验证**。IME 是操作系统输入法 + 窗口系统 + CEF 三方交互, 服务端 GameTest 进程连
   MCEF 都不 classload。唯一验收手段是真客户端手工操作。
2. **方案选型依赖实测结果**, 而不是依赖读码。见第三章——在真机上敲一次中文之前, 连"当前到底能不能打中文"
   都是未知的, 此时定方案就是赌。

故裁定: 先把事实与实验协议落文档, 实测有结果后再定方案、再动代码。

---

## 二、已验证事实

### 2.1 LWJGL 3.3.1 的 GLFW 绑定没有任何 IME 组字 API

Minecraft 1.20.1 用 LWJGL 3.3.1。对该版本 `lwjgl-glfw` 的 classes jar 做符号检查:

```
javap -cp ~/.gradle/caches/.../lwjgl-glfw-3.3.1.jar org.lwjgl.glfw.GLFW \
  | grep -i "preedit|composition"
```

结果**零命中**。(注意: 用 `ime` 做关键字会得到 `glfwWaitEventsTimeout` / `glfwGetTime` / `glfwGetTimerValue`
这类假命中——匹配的是 `Time`/`Timer` 里的 `ime` 三个字母, 不是输入法。这个坑值得记一笔, 它正是错误前提的来源之一。)

存在的字符输入回调只有一个:

```
public static GLFWCharCallback glfwSetCharCallback(long, GLFWCharCallbackI);
```

即: **GLFW 只会把"已上屏"的字符交给应用**。组字中间态 (preedit)、候选词列表、组字光标位置, 一概不经过 GLFW。
上游 GLFW 的 IME 支持 PR 长期未并入 3.3/3.4 主线, LWJGL 也就无从暴露。

**推论**: 任何"在 Java 侧接管组字过程"的方案在当前依赖版本下**不可实现**, 除非替换 GLFW 原生库
(自行编译 IME fork 并替换 natives)——那是另一个量级的工程, 且会与 Forge/MC 的 natives 解包机制冲突。

### 2.2 组字由操作系统完成, 应用只收结果

Windows 上 IMM32/TSF 在窗口层完成拼音组字, 上屏时以 `WM_CHAR` 投递给窗口。GLFW 把它转成 char 回调,
Minecraft 的 `KeyboardHandler` 再转给当前 `Screen.charTyped`。

也就是说, **通往 `WebUiScreen.charTyped` 的这条路本来就会送来中文字符**。

### 2.3 现有代码已经在转发 charTyped

[`WebUiScreen.charTyped`](../src/main/java/com/miningdim/client/webui/WebUiScreen.java#L181-L190) 现状:

```java
public boolean charTyped(char codePoint, int modifiers) {
    if (browser != null) {
        browser.sendKeyTyped(codePoint, modifiers);
        return true;
    }
    return super.charTyped(codePoint, modifiers);
}
```

结合 2.2, 这意味着**中文很可能已经能打进去了**, 只是没人在真机上试过。这正是第三章实验协议存在的理由。

### 2.4 必须同步修正的错误注释

`WebUiScreen` 类头注释第 24-26 行与 `charTyped` 内第 183-185 行, 均写着:

> 完整 IME 需叠加一个不可见原版 EditBox 捕获 GLFW IME 组字事件 (preedit / commit)

**这句话的前提是错的** (见 2.1: GLFW 不投递 preedit 事件, 叠 EditBox 也拿不到)。
它把一条不存在的技术路径写成了"接口位", 下一个接手的人会照着找一个永远找不到的回调。

处置: W11 真正动工时, **第一个提交必须是删掉这两处错误注释**, 换成本文档 2.1 的结论 + 指向本文档。
在此之前不单独改动——它属于 W11 的范围, 不夹带进别的分支 (「一个 PR 只承载一个模块」)。

---

## 三、实验协议 (动手写代码之前必须先跑完)

环境: 固定测试服 `shinoyuki@192.168.10.139` (见记忆 `test-server-access`), 真客户端连服, 打开 WebUI 面板。
输入法: 至少覆盖 微软拼音 (Windows 自带) 与 一款第三方 (搜狗/微信)——两者对候选窗与焦点的处理差异很大。

逐项记录 PASS / FAIL / 部分:

| # | 实验 | 观察什么 | 为什么这条决定方案 |
|---|---|---|---|
| E1 | 面板内 `<input>` 聚焦, 切中文输入法, 敲 `nihao` 再选词 | 汉字是否上屏到 input | FAIL 则整条 charTyped 路不通, 需查 CEF 焦点; PASS 则只剩体验问题 |
| E2 | 同上, 观察候选窗出现在屏幕什么位置 | 候选窗是否跟随网页内光标 | 几乎必然不跟随 (见第四章), 决定要不要做定位 |
| E3 | 组字中途按退格 | 是删组字缓冲还是删已上屏字符 | 决定要不要拦 `keyPressed` 的 BACKSPACE |
| E4 | 组字中途按 ESC | 是取消组字还是**关掉整个面板** | 高危: `keyPressed` 第 158 行 ESC 无条件 `onClose()` |
| E5 | 中英切换 (Shift / Ctrl+Space) | 切换键有没有被当普通按键喂给 CEF | 决定修饰键过滤范围 |
| E6 | 剪贴板粘贴中文 (Ctrl+V) | 是否粘进去、有没有乱码 | 粘贴不经 IME, 是中文输入的兜底通道 |
| E7 | 焦点在 CEF 内 input 与 MC 原生界面之间来回切 | 切回来后还能不能继续输入 | 决定 `setFocus` 时机 |
| E8 | 输入含扩展区汉字 (如 𠀀) 或 Emoji | 是否乱码/丢字 | `charTyped` 的 `char` 是 16 位, 代理对要分两次投递 |

**E4 是已知高危项**, 不需要实测就能从代码读出: `keyPressed` 在任何情况下遇到 `GLFW_KEY_ESCAPE` 都直接
`onClose()` 关面板。中文用户按 ESC 取消组字是肌肉记忆, 现在的行为是把整个面板关掉、正在填的表单全丢。
这一条无论 E1 结果如何都要修。

---

## 四、候选路线

在 E1 有结果之前不做取舍。三条路线按 E1/E2 的结果分叉:

### 路线 A — 什么都不做, 只修边角 (若 E1 PASS)

前提: 中文本来就能上屏。那么 W11 的实际内容缩水成三条小修:

1. ESC 在 CEF 有焦点且处于文本输入态时不关面板 (E4)
2. 代理对分投, 修扩展区汉字 (E8)
3. 删掉第 2.4 节的错误注释

这是**成本最低且最可能正确**的路线。第三章之所以必须先跑, 就是为了确认能不能走这条。

### 路线 B — 候选窗定位 (若 E2 显示候选窗位置离谱)

候选窗由操作系统绘制, 位置来自窗口向 IMM 报告的组字光标矩形。问题是:
网页内 caret 的真实位置只有 CEF 渲染进程知道, 而 MC 侧只有一张离屏贴图。

要做就得建一条 **JS -> Java 的 caret 上报通道**: 页面在 `selectionchange` / focus 时把当前输入框的
视口坐标经既有 bridge 回传, Java 侧换算成窗口像素, 再调 Win32 `ImmSetCompositionWindow`。

代价: 引入 JNA/平台相关代码, 且只对 Windows 生效; 换来的收益只是候选窗位置好看。
**倾向不做**——除非 E2 显示候选窗压在输入框正上方挡住内容, 属于可用性问题而非美观问题。

### 路线 C — 旁路输入 (若 E1 FAIL)

若中文根本进不去 CEF, 退而求其次: 在 MC 层叠一个真实的原版 `EditBox` (它本来就能打中文),
用户在这个原生控件里输入, 确认后把整串文本经 bridge 一次性注入网页的目标输入框。

注意这与 2.4 那条错误注释**不是一回事**: 这里的 EditBox 是**真的当输入框用**并拿它的最终文本,
不是去"捕获 preedit 事件"(那个不存在)。

代价: 交互割裂 (点一下网页输入框, 弹出一个 MC 原生输入条), 且每个需要文本输入的页面都要配合。
**只在 E1 FAIL 时才考虑**。

---

## 五、这件事对前端设计的约束 (现在就生效)

在 W11 落地之前, 前端**不得假定中文可输入**。具体:

- 凡是需要玩家输入中文的功能 (按玩家名搜索、给挂单写备注), 一律提供**免输入的替代路径**:
  在线玩家列表点选、最近交互对象列表、下拉候选。
- `marriage.propose` 的 `targetName` 是本轮唯一强依赖文本输入的 action。W6 接线时**必须同时给出点选入口**,
  不能只留一个输入框。
- 搜索框可以留, 但不能是唯一入口。

这条约束是 W11 推迟的直接代价, 写在这里是为了让它别被忘掉——每多一个页面按"输入框优先"来设计,
W11 落地后要回头改的就多一页。

---

## 六、验收口径

W11 真正做完时必须满足:

| 项 | 口径 |
|---|---|
| 实验回归 | 第三章 E1-E8 全部记录结果, 且 E4 必须 PASS |
| 输入法覆盖 | 微软拼音 + 至少一款第三方输入法各跑一遍 |
| 不回归 | 英文输入、快捷键、ESC 关面板 (在非输入态) 行为不变 |
| 注释 | 第 2.4 节两处错误注释已删除 |
| 纪律 | diff 内零 Emoji; 中文 Conventional Commits; 无 AI 署名 |

**不接受只跑单测**——本分支没有任何单测能证明它工作。

---

## 七、明确不在 W11 范围

| 项 | 理由 |
|---|---|
| 替换 GLFW natives 为 IME fork | 与 Forge/MC natives 解包机制冲突, 量级远超收益 |
| Linux / macOS 的 IME | 公服客户端事实上全是 Windows; 真有需求再单开 |
| 输入法候选窗美化/主题化 | 候选窗归操作系统绘制, 应用无权干预样式 |
| 语音输入、手写输入 | 无需求 |
