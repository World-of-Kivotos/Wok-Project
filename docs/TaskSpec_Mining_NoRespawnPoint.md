# TaskSpec: 矿洞维度禁止设置重生点

状态: 待实施 | 分支: `fix/mining-no-respawn-point` | 预估: 1 个方法 + 1 条 lang 键 + 2 条 GameTest

---

## 零、执行者须知 (硬约束, 违反即返工)

1. **严禁 Emoji**。代码、注释、提交信息、文档、日志、测试用例一律零 Emoji。
2. **严禁 TODO 与空壳代码**。
3. **严禁越界修改**。只改本规格点名的文件。旁边代码有问题在 PR 里口头报告。
4. **异常必须痛**。不许 `?? 0` / `|| "未知"` 掩盖空值, 不许 try-catch 生吞。
5. **提交信息用中文 + Conventional Commits**, 严禁任何 AI 署名。
6. **写入不等于成功**, 见第六节验证门。
7. 查找文件用 `rg --files` 或 ripgrep, **严禁在 Bash 里用 `find`** (本机 `find.exe` 有句柄泄露)。

```
JAVA_HOME=C:\Users\Xiaoxiao\.gradle\jdks\eclipse_adoptium-17-amd64-windows\jdk-17.0.18+8
gradlew.bat compileJava
gradlew.bat runGameTestServer
```

---

## 一、需求与判据

用户决策原文: "维度里不能设置重生点, 可以做代码的禁止"。

**要防的真实后果**: 矿洞是会被整块重置的 (定时自动刷新 + `/mining reset`)。玩家若把重生点设在矿洞里, 重置之后那个坐标要么是实心岩体要么是新地形, 死亡重生直接卡死或掉进虚空。这不是洁癖, 是一条会产出工单的实际故障。

---

## 二、现状核实 (已核实, 不要重新调研)

`src/main/resources/data/miningdim/dimension_type/mining.json` 第 5-6 行**已经**是:

```json
"respawn_anchor_works": false,
"bed_works": false,
```

即原版的两条路径 (床 / 重生锚) 在矿洞里**已经**失效, 且床会像在下界一样爆炸。

> 用户决策: 床爆炸这件事**不做任何提示**, 当彩蛋留着。本任务不许加床相关的提示文案。

所以本任务堵的是**剩下的非原版路径**: `/spawnpoint` 命令、第三方 mod 的传送石 / 墓碑 / 复活点道具, 以及未来任何调用 `ServerPlayer.setRespawnPosition` 的代码。这是纵深防御, 不是重复劳动——但 PR 描述里要如实写明"原版两条路径本来就已经堵住", 别把它说成从零开始的修复。

---

## 三、机制 (已用 MC 源码核实, 照此实现)

`net/minecraft/server/level/ServerPlayer.java:1568`:

```java
public void setRespawnPosition(ResourceKey<Level> pDimension, @Nullable BlockPos pPosition,
                               float pAngle, boolean pForced, boolean pSendMessage) {
   if (net.minecraftforge.event.ForgeEventFactory.onPlayerSpawnSet(
           this, pPosition == null ? Level.OVERWORLD : pDimension, pPosition, pForced)) return;
   ...
}
```

即: 事件被取消 -> 方法**整个提前返回**, 重生点一个字段都不会被写。这是唯一入口, 所有设置重生点的路径 (命令、床、锚、mod) 最终都要经过它。

事件类 (javap 实测):

```
@Cancelable
public class net.minecraftforge.event.entity.player.PlayerSetSpawnEvent extends PlayerEvent {
    public boolean isForced();
    public BlockPos getNewSpawn();
    public ResourceKey<Level> getSpawnLevel();
}
```

`@Cancelable` 已确认存在, `setCanceled(true)` 可用。

### 三条必须理解的语义

**(1) 判据是 `getSpawnLevel()`, 不是玩家当前所在维度。**

要防的是"重生点指向矿洞", 不是"人在矿洞里做了某个动作"。玩家人在矿洞、把重生点设在主世界是完全正常的; 而人在主世界、通过某个 mod 把重生点设进矿洞才是要拦的那件事。写成"玩家当前在矿洞就拦"会同时漏拦真问题并误伤正常操作。

**(2) 清除重生点永远不会被本闸拦住。**

看上面那行源码: `pPosition == null` 时传给事件的 dimension 被强制换成 `Level.OVERWORLD`。所以"清空重生点"这个动作的 `getSpawnLevel()` 恒为主世界, 判据永远不命中。

这是一条**必须写进代码注释**的安全性质: 否则后来人会担心"万一有人在旧版本里已经把重生点设进矿洞了, 这道闸会不会让他永远清不掉", 答案是不会。

**(3) `getNewSpawn()` 可能为 null**, 就是上面那种清除的情形。任何取它的地方 (比如日志) 必须判空, 不许直接 `.toString()`。

---

## 四、实施

### 4.1 落点: `src/main/java/com/miningdim/rules/RulesSystem.java`

这个子系统的职责就是"矿山维度内的规则" (类注释: R7 放置规则子系统, 在矿山维度内放置非白名单方块一律取消并提示)。重生点禁令与放置白名单是同一类东西, 放这里不需要新建子系统。

已有可复用件:
- `isMiningDimension(LevelAccessor)` —— 但它收的是 `LevelAccessor`, 本任务判的是 `ResourceKey<Level>`, 直接比 `MiningConstants.MINING_LEVEL` 即可, **不要为此改那个方法的签名** (它有两个现成调用方)。
- 提示范式: `player.sendSystemMessage(Component.translatable("message.miningdim.rules.placement_denied"))`。

新增:

```java
@SubscribeEvent
public void onSetSpawn(PlayerSetSpawnEvent event) {
    // 判据是"重生点落在哪个维度", 不是"玩家现在站在哪个维度" —— 人在矿洞里把重生点设回主世界是正常操作,
    // 要拦的是重生点指向矿洞: 矿洞会被整块重置, 重置后那个坐标要么是实心岩体要么是新地形。
    if (!event.getSpawnLevel().equals(MiningConstants.MINING_LEVEL)) {
        return;
    }
    event.setCanceled(true);
    if (event.getEntity() instanceof ServerPlayer player) {
        player.sendSystemMessage(Component.translatable("message.miningdim.rules.spawn_denied"));
    }
}
```

**注意**: `PlayerEvent.getEntity()` 返回 `Player` (客户端也可能有一份), 故必须 `instanceof ServerPlayer` 窄化后再发消息, 与 `enforce` 里对 placer 的处理同一纪律。

同时更新 `RulesSystem` 的类注释与 `register()` 里那句 log —— 现在它已不只管放置。

### 4.2 lang 键

`src/main/resources/assets/miningdim/lang/zh_cn.json` (以及若存在的 `en_us.json`) 加:

```
"message.miningdim.rules.spawn_denied": "矿洞里不能设置重生点 —— 这块地会被整块刷新。"
```

先 `rg --files -g "*.json" src/main/resources/assets/miningdim/lang` 确认实际有哪些语言文件, 有几个就补几个, 不要凭空新建。

### 4.3 明确的非目标

- **不碰 `dimension_type/mining.json`**, 床/锚已经是 false。
- **不加床爆炸提示** (用户决策: 留作彩蛋)。
- **不改死亡重生落点逻辑**。那属于另一条任务 (`TaskSpec_Mining_DeathRules.md`), 本分支一行不碰。
- **不做"检测并迁移已有的矿洞重生点"**。存量数据清理是另一件事, 且没有用户拍板, 不许自作主张写迁移。

---

## 五、测试要求

加进 `src/main/java/com/miningdim/rules/` 下的 GameTest (若无既有 holder 则新建 `RulesSpawnGameTests.java`, 范式参考同目录/同包既有测试)。

**质量判据: 删掉 `event.setCanceled(true)` 那一行, 断言必须挂。**

三条:

1. **拦得住**: 构造一个 `PlayerSetSpawnEvent`, `spawnLevel = MiningConstants.MINING_LEVEL`, 投到总线, 断言 `event.isCanceled() == true`。
2. **不误伤**: 同样构造但 `spawnLevel = Level.OVERWORLD`, 断言 `event.isCanceled() == false`。**这一条不许省**——只写第 1 条的话, 把判据写成"无条件取消"也能全绿, 那会让全服所有人都设不了重生点。
3. **清除不被拦**: 按第三节 (2) 的语义, 构造 `getNewSpawn() == null` 且 `spawnLevel = Level.OVERWORLD` 的事件 (这正是原版清除路径传进来的形状), 断言未取消。

事件构造用公开构造器 `new PlayerSetSpawnEvent(Player, ResourceKey<Level>, BlockPos, boolean)`, 玩家用既有的 `MockGameTestPlayers` 助手 (全库搜一下它的实际类名与方法, `com.miningdim.quest.QuestGameTests` 里有用法)。

---

## 六、验证门 (全绿之前不许报告完成)

```
set JAVA_HOME=C:\Users\Xiaoxiao\.gradle\jdks\eclipse_adoptium-17-amd64-windows\jdk-17.0.18+8
gradlew.bat compileJava
gradlew.bat runGameTestServer
```

**判绿不能只看 gradle 退出码**, 必须在日志里核对:

```
N tests are now running!
All N required tests passed :)
```

当前基线 **1165 绿**, 完成后应为 1165 + 新增用例数, 一条都不许少。

变异验证必做: 删掉 `setCanceled(true)` -> 确认第 1 条挂 -> 还原; 再把判据改成无条件取消 -> 确认第 2 条挂 -> 还原 -> 复跑全绿。两次失败信息原文贴进 PR 描述。

---

## 七、交付

分支 `fix/mining-no-respawn-point`, 从最新 `main` 切出, 单个原子提交即可。PR 描述必须写明: 原版床/锚路径本来就已经堵住 (见第二节), 本次堵的是命令与第三方 mod 路径。
