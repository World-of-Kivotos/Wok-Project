# TaskSpec: 矿洞三难度死亡规则

状态: **有两条待拍板** (见第一节), 机制部分可先实施 | 分支: `feat/mining-difficulty-death-rules`

---

## 零、执行者须知 (硬约束, 违反即返工)

1. **严禁 Emoji**。代码、注释、提交信息、文档、日志、测试用例一律零 Emoji。
2. **严禁 TODO 与空壳代码**。
3. **严禁越界修改**。只改本规格点名的文件。
4. **异常必须痛**。不许掩盖空值, 不许 try-catch 生吞。
5. **提交信息用中文 + Conventional Commits**, 严禁任何 AI 署名。
6. **写入不等于成功**, 见第七节验证门。
7. 查找文件用 `rg --files` 或 ripgrep, **严禁在 Bash 里用 `find`**。
8. **严禁臆造未拍板的业务数值与规则**。第一节的两条未定项若仍未拍板, 按文中给出的默认实施, 并在 PR 描述里显式列出"我按默认走了这两条"。

```
JAVA_HOME=C:\Users\Xiaoxiao\.gradle\jdks\eclipse_adoptium-17-amd64-windows\jdk-17.0.18+8
gradlew.bat compileJava
gradlew.bat runGameTestServer
```

---

## 一、待拍板 (实施前请主控确认; 未确认则按默认执行并在 PR 里标明)

### D1: "忽视绑定诅咒这种"的范围

用户原话: "高级的最难精英怪, 死亡掉落。忽视绑定诅咒这种"。

两种读法:

- **(A) 窄读**: 只指原版的绑定诅咒 (`Enchantments.BINDING_CURSE`)。
- **(B) 宽读**: "这种"泛指**一切死亡保留机制**, 含其它 mod 的 soulbound / 灵魂绑定 / 墓碑。

**默认取 (B)**, 但必须诚实交付能力边界:

| 机制 | 能不能压过 | 说明 |
|---|---|---|
| 原版绑定诅咒 | 能, 且是白送的 | `Inventory.dropAll()` 本来就不看绑定诅咒——它只拦"在背包界面里手动脱下来", 不拦死亡掉落 |
| 原版 keepInventory gamerule | 能 | 见第三节机制 |
| 本 mod 自己的任何保留逻辑 | 能 | 全在我们手里 |
| **第三方 mod 的 soulbound / 墓碑** | **不能保证** | 它们通常挂在 `PlayerEvent.Clone` 或自己的死亡钩子上恢复物品; 我们在 `LivingDropsEvent` 清空背包后, 它们仍可能在重生那一刻把东西塞回去 |

**不许在代码注释或 PR 里把第三方 soulbound 写成"已压过"。**如实写"已覆盖原版全部保留路径; 第三方保留类 mod 需上线后真机逐个验"。

### D2: 高级难度死亡掉不掉经验

用户只说了"死亡掉落", 没说经验。

**默认: 只掉物品, 经验不动。**理由: 经验在本服的用途主要是附魔与职业系统, 与"矿洞硬核档"的物品风险是两件事; 且用户在同一句里对比的是"死亡不掉落"这个物品语义。

若要连经验一起掉, 是另一处改动 (见第四节 4.4 的备注), 不要自作主张加。

---

## 二、需求

用户决策原文:

> 初级是 save 的, 死亡不掉落, 安全, 中级死亡不掉落, 有小难度精英怪, 高级的最难精英怪, 死亡掉落。忽视绑定诅咒这种

> "死亡掉落"在高级明说

即:

| 难度 | 死亡掉落 | 玩家提示 |
|---|---|---|
| EASY | 否 | 无 |
| MEDIUM | 否 | 无 |
| HARD | **是** | **必须明说** (用户明确要求) |

精英怪强度分档不属于本任务 (已在精英怪子系统)。本任务只做死亡掉落规则 + 高级档的明示。

---

## 三、机制 (已用 MC 源码核实, 不要重新调研, 更不要凭记忆改写)

### 3.1 原版只有一个全局开关

`net/minecraft/world/entity/player/Player.java`:

```java
protected void dropEquipment() {
   super.dropEquipment();
   if (!this.level().getGameRules().getBoolean(GameRules.RULE_KEEPINVENTORY)) {
      this.destroyVanishingCursedItems();
      this.inventory.dropAll();
   }
}
```

`net/minecraft/server/level/ServerPlayer.java:1178 restoreFrom`:

```java
} else if (this.level().getGameRules().getBoolean(GameRules.RULE_KEEPINVENTORY) || pThat.isSpectator()) {
   this.getInventory().replaceWith(pThat.getInventory());
   this.experienceLevel = pThat.experienceLevel;
   ...
}
```

**`GameRules` 挂在服务器的 `WorldData` 上, 全服共用一份, 不存在"某个维度单独设 keepInventory"这回事。**所以"按难度分档"必须由 mod 实现, 原版给不了。

### 3.2 选定的实现方向

**前提: 服务器全局 `keepInventory = true`** (这是本服既定的"死亡不掉落"环境)。

在这个前提下:

- EASY / MEDIUM: **一行代码都不用写**, 原版 keepInventory 已经是想要的行为。
- HARD: 需要**强制掉落**, 即压过 keepInventory。

**实施前必须先确认服务器的实际 gamerule** (`/gamerule keepInventory`)。若它其实是 false, 那 EASY/MEDIUM 现在正在掉东西, 本规格的方向要整个重来 (那种情况下要实现的是"自定义保留"而不是"自定义掉落", 复杂度高一个量级)。确认结果写进 PR 描述。

代码里同时加一道**启动自检**: 服务器启动时读一次 `keepInventory`, 为 false 时打 WARN 日志, 明写"EASY/MEDIUM 的死亡不掉落当前未生效"。不要静默——这正是那种上线三个月没人发现的错。

### 3.3 强制掉落挂在哪个事件

挂 `LivingDropsEvent`, **不是** `LivingDeathEvent`。

理由是与既有代码的协作: `src/main/java/com/miningdim/economy/EconomySystem.java:272` 的 `onDeathDrops` 已经在同一个事件上按 `EconomyConstants.DEATH_DROP_MODE` (KEEP_IN_PLACE / DESPAWN_FAST / VOID) 处理矿洞死亡掉落物。若我们改在 `LivingDeathEvent` 里自己 spawn ItemEntity, 这批物品就绕过了那套处理, 于是同一个维度里"强制掉的"和"正常掉的"两批物品行为不一致, 且这种不一致极难归因。

正确做法: 在 `LivingDropsEvent` 里把背包内容**加进 `event.getDrops()`**, 然后清空背包。这样它们照常流经 `EconomySystem.onDeathDrops`。

- `event.getDrops()` 返回的集合是可变的 (`EconomySystem` 已经在对它 `.clear()` 与遍历)。
- 本处理器必须用 `EventPriority.HIGH`, 跑在 `EconomySystem` (默认 NORMAL) **之前**, 否则加进去的那批赶不上 DEATH_DROP_MODE 的处理。

清空背包这一步是关键: 不清空的话, 稍后 `restoreFrom` 会因 keepInventory=true 把原背包整个复制给重生后的玩家 —— 结果是**物品既掉在地上又留在背包里**, 一次死亡凭空复制一整套装备。这是本任务最危险的一处, 必须有测试锁死 (见第六节)。

### 3.4 与原版语义对齐的两点

- **消失诅咒**: 原版在掉落前会 `destroyVanishingCursedItems()` 销毁带消失诅咒的物品。强制掉落路径必须做同样的事, 否则高级矿洞成了消失诅咒的免疫区。
- **绑定诅咒**: 不用做任何事。`Inventory.dropAll()` 与逐槽遍历都不看它。D1 的窄读部分天然满足。

---

## 四、实施

### 4.1 落点

新文件 `src/main/java/com/miningdim/rules/MiningDeathRules.java`, 由 `RulesSystem.register` 挂总线 (与禁重生点那条同一子系统; 若 `fix/mining-no-respawn-point` 尚未合并, 本分支自行在 `RulesSystem` 里加注册, 合并时解冲突)。

**不要写进 `EconomySystem`**——那是经济子系统, 死亡掉落规则是矿洞规则, 混进去会让 `EconomySystem` 变成什么都管的杂物间。

### 4.2 难度判定

照抄 `EconomySystem.onDeath` 的现成写法 (它已经在做同一件事):

```java
BlockPos pos = player.blockPosition();
InstanceState instance = MiningServices.instanceManager().regionAt(pos.getX(), pos.getZ());
```

**必须先判维度再判 region。**`regionAt` 只比 XZ 不看维度, 而 Easy 区盒是 X/Z ∈ [0,256), 主世界出生点通常就落在里面——只用 `regionAt` 会把死在主世界出生点的玩家判成"死在困难矿洞"。这个坑 `MiningWebUiActions.currentRegionOf` 的注释里有完整记录, 去读一遍。

`instance == null` (在矿洞维度但不在任何 region 内) 时**不强制掉落**, 按默认行为走。

### 4.3 高级档的明示

用户要求"死亡掉落在高级明说"。两处:

1. **进入时**: `EntryGateway.completeEnter` 已经在发一条 translatable 的进入提示 (`message.miningdim.enter.entered_hint`)。HARD 难度**额外**再发一条警告 (红色), 内容明写"本区死亡掉落全部物品"。不要改那条既有提示的文案, 加一条新的。
2. **WebUI 矿洞面板**: `MiningWebUiActions.overviewRow` 每行加一个 `dropsOnDeath: boolean` 字段, 前端在高级卡片上画醒目标记。前端改动落在 `webui/src/pages/MiningPage.tsx` 与 `webui/src/lib/types.ts` 的 `MiningInstanceRow`。

**服务端只发布尔量, 不发文案**——这是本仓库 WebUI 的既定分工 (见 `HubWebUiActions` 类注释的 D2 决策: 展示层信息一律归前端, 服务端只权威事实)。

对应 lang 键加到 `src/main/resources/assets/miningdim/lang/` 下**实际存在**的语言文件里 (先 `rg --files` 确认有哪些)。

### 4.4 明确的非目标

- **不改 `EconomyConstants.DEATH_DROP_MODE`** 及其三种模式的行为。
- **不改死亡再入冷却 / danger 清零** (`EconomySystem.onDeath` 的 18.6 逻辑) 一行。
- **不动经验** (D2 默认)。若日后要掉经验, 落点是 `restoreFrom` 那三行的对应覆盖, 属另一任务。
- **不改精英怪强度分档**。
- **不做墓碑 / 物品找回**。没有拍板过。

---

## 五、需要主控在真机确认的事

以下 dev 环境结构性验不了, 列进 PR 描述的验收清单:

1. 服务器 `/gamerule keepInventory` 的实际取值 (决定本规格前提是否成立)。
2. 装了第三方 soulbound / 墓碑类 mod 时, 高级矿洞死亡到底掉不掉 (见 D1 的能力边界表)。
3. 高级矿洞死亡后重生, 背包确实是空的且地上确实有一整套 (即第 3.3 节那条"既掉又留"的复制 bug 没发生)。

测试服: `shinoyuki@192.168.10.139` (MCSManager)。

---

## 六、测试要求

新建 `src/main/java/com/miningdim/rules/MiningDeathRulesGameTests.java`。

**质量判据: 删掉被测实现, 断言必须挂。**严禁 `assertTrue(x != null)` 这类永远通过的弱校验。

必须覆盖的五条:

1. **高级档强制掉落**: 玩家在 HARD region 死亡 -> `event.getDrops()` 里出现背包里那件可辨识的物品。
2. **高级档清空背包**: 同一场景下, 断言 `player.getInventory()` 里那件物品**已经没了**。
   这一条是防第 3.3 节那个"既掉又留"的复制 bug 的唯一防线。删掉清空背包那步, 第 1 条仍然全绿, 只有这条会挂——**所以它绝不能省**。
3. **初级/中级不掉**: 玩家在 EASY region 死亡 -> `event.getDrops()` 不含背包物品, 且背包内容原封不动。MEDIUM 同理 (两个难度各一条, 或参数化一条覆盖两档)。
4. **维度门**: 玩家在**主世界** X/Z 落在 Easy 区盒范围内 (例如 (10, 10)) 死亡 -> 不触发任何强制掉落。这一条锁死 4.2 节那个"先判维度"的坑; 去掉维度判定它立刻挂。
5. **消失诅咒**: 高级档死亡时带消失诅咒的物品被销毁, 不出现在 `getDrops()` 里。

背包物品用 `player.getInventory().setItem(9, stack)` 放置。**不要用 `Inventory.add()`**——它会消耗传入的 stack, 之后再读那个引用拿到的是空栈 (本仓库已经踩过一次)。**也不要用槽位 0**——那是主手, 语义不同。

---

## 七、验证门 (全绿之前不许报告完成)

```
set JAVA_HOME=C:\Users\Xiaoxiao\.gradle\jdks\eclipse_adoptium-17-amd64-windows\jdk-17.0.18+8
gradlew.bat compileJava
gradlew.bat runGameTestServer

cd webui && pnpm build && pnpm lint && pnpm lint:css
```

**判绿不能只看 gradle 退出码**, 必须核对日志里的:

```
N tests are now running!
All N required tests passed :)
```

基线 **1165 绿**, 完成后应为 1165 + 新增用例数, 一条都不许少。

变异验证必做, 至少两处: 删掉"清空背包"那步 (第 2 条必须挂)、删掉维度判定 (第 4 条必须挂)。失败信息原文贴进 PR。

---

## 八、交付

分支 `feat/mining-difficulty-death-rules`, 从最新 `main` 切出。建议两个原子提交: "强制掉落规则 + 测试"、"高级档明示 (聊天提示 + 面板字段 + 前端)"。

PR 描述必须含:
- D1 / D2 按哪种读法实施的
- `/gamerule keepInventory` 的实测值
- 第五节三条真机验收项 (未做的如实标未做)
- 变异验证的两段失败信息原文
