# 称号系统 设计规格文档

## 文档元信息

- 用途：称号系统（`wok-title`）实现阶段的架构与机制参考。与代码不符时以代码为准并回写本文档。
- 目标平台：Minecraft 1.20.1 + Forge 47.x + Java 17。API 名称以此版本为准。
- 状态图例：DECIDED 已定稿 / PENDING 待拍板或待标定数值 / TODO 实现期补全。
- 姊妹文档：[Achievement_System_DesignSpec](Achievement_System_DesignSpec.md)。成就是称号的第一个发放方，但称号系统**不依赖**成就系统。
- 部署规模（2026-09-26 口径）：在线峰值约 50 人、上限 100 人以内，历史玩家不足一千；将来可能改为多子服架构。

---

## 一、系统定位 (DECIDED)

1. 称号是**纯外观**的身份标识，不提供任何战斗力或经济收益，不可交易、不可转让。
2. 玩家可拥有多个称号，**同一时刻只佩戴一个**，也可以不佩戴。
3. 称号显示在三处：**聊天前缀、Tab 列表、头顶名牌**（第五章）。
4. 称号系统是独立的底层模块，对外只暴露发放、回收、佩戴接口。成就、成就点商店、婚姻、活动、管理员都是它的**调用方**。
5. 不是每个成就都附带称号；称号从哪里来由调用方决定，称号系统只记录来源。

---

## 二、模块边界 (DECIDED)

| 项 | 值 |
|---|---|
| 模块 ID | `wok-title` |
| 分类 | `foundation`（让任意玩法模块都能依赖它，而不产生循环依赖） |
| Java 包 | `com.miningdim.title`（服务端）、`com.miningdim.client.title`（名牌渲染） |
| 依赖 | `wok-core`、`wok-store`、`wok-webui` |
| 资源 | `data/miningdim/titles`、`assets/miningdim/lang` 中以 `title.` 开头的键 |
| 对外入口 | `com.miningdim.title.ITitleService`，由 `TitleServices.titleService()` 取得 |

**依赖方向硬约束**：`wok-title` 不得引用任何玩法模块（成就、婚姻、职业……）。
需要"某件事发生后发称号"的，一律由那个玩法模块调用 `ITitleService.grant`。
违反这条会被 `gradlew verifyModuleBoundaries` 拦下。

`wok-app` 的 `dependencies` 需加入 `wok-title`；`MiningDim.registerSubsystems()` 中
`TitleSystem` 须排在 `WebUiServerSubsystem` 之后、任何称号调用方之前。

---

## 三、称号定义（数据包）(DECIDED)

路径：`data/miningdim/titles/<id>.json`，由 `SimpleJsonResourceReloadListener` 加载，
在 `AddReloadListenerEvent` 注册，支持 `/reload` 热重载。

```json
{
  "text": { "translate": "title.miningdim.mining.deep_diver" },
  "rarity": "diamond",
  "style": { "gradient": ["#6FF2FF", "#7FB0FF", "#D59CFF"], "bold": true },
  "description": { "translate": "title.miningdim.mining.deep_diver.desc" },
  "sort": 400
}
```

| 字段 | 必填 | 说明 |
|---|---|---|
| `text` | 是 | 称号文字，Component JSON；一律用 `translate`，键名带 `title.` 前缀 |
| `rarity` | 是 | 七档之一：`bronze` `silver` `gold` `platinum` `diamond` `master` `legend`，与成就档位共用色板 |
| `style` | 否 | 覆盖稀有度的默认颜色：`color`（单色）或 `gradient`（2~3 个色标）加 `bold`。婚姻、活动这类特殊称号使用 |
| `description` | 否 | 获取说明，显示在"我的称号"页签 |
| `sort` | 否 | 排序权重，缺省取稀有度的默认值 |

- 称号 id 即资源路径，例如 `miningdim:mining/deep_diver`。
- 定义被删除后，玩家持有记录**保留不删**，但在显示和列表中跳过；定义恢复后自动复原。
- 加载时校验：未知 `rarity`、渐变色标不足 2 个或多于 3 个、颜色格式非法，一律跳过该条并在日志告警，不影响其余定义。

### 3.1 七档色板 (DECIDED)

与成就档位共用。前三档单色，白金起为逐字渐变加粗体，档位越高越华丽。

| 稀有度 | 游戏内颜色 | 粗体 | 16 色降级 |
|---|---|---|---|
| 铜 bronze | `#C8834A` | 否 | `§6` |
| 银 silver | `#D0D7DE` | 否 | `§7` |
| 金 gold | `#FFCC33` | 否 | `§e` |
| 白金 platinum | 渐变 `#FFFFFF → #FFF1C9 → #FFD66B` | 是 | `§f§l` |
| 钻石 diamond | 渐变 `#6FF2FF → #7FB0FF → #D59CFF` | 是 | `§b§l` |
| 大师 master | 渐变 `#A55CFF → #E05CFF → #FF5CB8` | 是 | `§d§l` |
| 传说 legend | 渐变 `#FF3D3D → #FF8A1F → #FFD23F` | 是 | `§c§l` |

- 渐变的实现方式：服务端把称号文字逐字拆成多个带 RGB 颜色的 Component 片段。原版支持，聊天、Tab 和 L 键界面都能显示。
- G 面板的浅色主题下，主色对比度不足，因此每档另外定义一组加深色，放在前端的 CSS 变量 `--tier-*` 里，在深色和浅色主题之间切换（TODO：实现时标定具体色值）。
- 色板在 Java 侧只有一份真源 `com.miningdim.title.TierPalette`，成就模块直接引用它，不得各自复制色值。

---

## 四、存储 (DECIDED)

复用 `wok-store` 的世界级 SQLite，在 `MiningSchema` 末尾**追加**迁移（已发布的迁移不得修改）。

```sql
CREATE TABLE title_owned (
  player_uuid TEXT NOT NULL,
  title_id    TEXT NOT NULL,
  source      TEXT NOT NULL,   -- achievement / point_shop / marriage / event / admin
  source_ref  TEXT,            -- 例如成就 id、商品 id
  granted_at  INTEGER NOT NULL,
  PRIMARY KEY (player_uuid, title_id)
);
CREATE TABLE title_equipped (
  player_uuid TEXT PRIMARY KEY,
  title_id    TEXT NOT NULL
);
```

- 数据库访问全部经过 `TitleRepository` 接口，当前实现为 `SqliteTitleRepository`。以后改为多子服时，新增共享数据库（如 MySQL）的实现即可，业务代码不改。
- **内存缓存只保存在线玩家**：登录时加载，登出时移出。按当前规模，读写在主线程同步完成即可，不引入异步线程。
- `grant` 按主键幂等：重复发放同一称号不会报错，也不会覆盖最早的来源。
- 调用方需要把"扣成就点"和"发称号"放进同一个事务时，`ITitleService` 提供 `grantInTransaction(Connection, ...)` 这一变体。成就点商店购买称号就是这种情况。

---

## 五、三处显示 (DECIDED)

显示用的 Component 统一由 `TitleRenderer.prefix(titleId)` 生成，格式为 `[称号] `，三处共用。

| 位置 | 实现 | 刷新时机 |
|---|---|---|
| 聊天前缀 | Forge `PlayerEvent.NameFormat`，把前缀拼到显示名前面 | 佩戴变化时调用 `player.refreshDisplayName()` |
| Tab 列表 | Forge `PlayerEvent.TabListNameFormat` | 佩戴变化时调用 `player.refreshTabListName()`，原版自动广播 |
| 头顶名牌 | 客户端 `RenderNameTagEvent`，在名字**上方单独一行**渲染称号 | 见下方同步协议 |

- **聊天前缀**：1.20.1 的聊天发送者名取自 `getDisplayName()`，所以改显示名就够了。不需要监听 `ServerChatEvent`，也不会破坏聊天签名。副作用是死亡消息、原版进度广播里也会带上称号，这是预期行为。
- **不使用计分板队伍前缀**：一个玩家只能加入一个队伍，会和以后的组队、PvP 阵营功能冲突。
- **头顶名牌同步协议**：新增 S2C 包 `S2CTitleSync(entityId, @Nullable Component)`，在 `MiningNetwork.register()` 中注册。发送时机：
  - `PlayerEvent.StartTracking`：观察者开始看到某个玩家时，发送该玩家的称号
  - 玩家登录或切换维度：发送自己的称号
  - 佩戴变化：用 `PacketDistributor.TRACKING_ENTITY_AND_SELF` 发送给所有正在看着他的人
  - 包里直接携带 Component，客户端不需要本地称号表
  - 只在状态变化时发包，**不做逐 tick 同步**
- 客户端缓存 `Map<entityId, Component>`，在实体移除和切换维度时清理。
- 以后可以只在名牌上为高档称号加流光动画（客户端渲染可控）。聊天和 Tab 只显示静态效果。**PENDING**，不在 P1 范围内。

---

## 六、对外接口 (DECIDED)

```java
public interface ITitleService {
    GrantResult grant(ServerPlayer player, ResourceLocation titleId, TitleSource source, @Nullable String sourceRef);
    GrantResult grantInTransaction(Connection tx, UUID player, ResourceLocation titleId, TitleSource source, @Nullable String sourceRef);
    boolean revoke(UUID player, ResourceLocation titleId);          // 若正在佩戴则同时卸下
    EquipResult equip(ServerPlayer player, @Nullable ResourceLocation titleId); // null = 不佩戴
    Set<ResourceLocation> owned(UUID player);
    Optional<ResourceLocation> equipped(UUID player);
    Optional<TitleDefinition> definition(ResourceLocation titleId);
}
```

- `TitleSource` 是一个枚举：`ACHIEVEMENT`、`POINT_SHOP`、`MARRIAGE`、`EVENT`、`ADMIN`。新增来源时追加枚举值。
- 离线玩家也可以 `grant`（按 UUID 写库，不经过缓存），供以后婚姻、活动系统批量补发使用。
- 获得称号时，给玩家本人发一条聊天提示："获得称号 [xxx]"，悬停可查看说明。**不做全服广播**（全服广播交给成就的原版公告）。
- `equip` 前服务端必须校验称号已拥有且定义存在，不相信客户端传入的值。

---

## 七、G 面板："我的称号"页签 (DECIDED)

位于 G 面板"成就点商店"页（路由 `/achievement-shop`）的第二个页签。页面本身由成就模块负责（见成就文档第八章），这个页签的数据来自称号模块。

| action | 类型 | 说明 |
|---|---|---|
| `title.list` | 只读，可加入 batch | 返回全部称号定义（含未拥有的）、拥有状态、来源说明、当前佩戴 |
| `title.equip` | 写 | 参数 `{ titleId: string \| null }`；`null` 表示卸下 |

- 界面：卡片网格，每张卡片带预览（"玩家名 + 称号"的实际效果），当前佩戴的卡片高亮，另有一张"不佩戴"卡片。
- 未拥有的称号显示为灰色，并注明获取途径（哪个成就，或多少成就点）。
- 错误码在 `WebUiErrorCodes` 中新增 `TITLE_NOT_OWNED`、`TITLE_UNKNOWN`，并在 `webui/src/lib/errorText.ts` 中映射成玩家可读的提示。
- Java 侧在 `TitleWebUiActions.registerAll()` 中注册，由 `TitleSystem.register()` 调用。`title.list` 同时登记进 `WebUiBatchAction.BATCHABLE` 和 `webui/src/lib/batch.ts`。

---

## 八、管理员命令 (DECIDED)

命令根为 `/mtitle`，需要权限等级 2。命名参照 `/mchampion`：原版已经有 `/title` 命令，不能重名；仓库里也没有统一的 `/wok` 命令根，各模块各自注册命令根。以后可能会统一成 `/wok` 指令集，届时迁移到那里。

| 命令 | 作用 |
|---|---|
| `/mtitle grant <玩家> <称号id>` | 发放，来源记为 `ADMIN` |
| `/mtitle revoke <玩家> <称号id>` | 回收 |
| `/mtitle list <玩家>` | 查看拥有的称号和当前佩戴 |
| `/mtitle equip <玩家> <称号id\|none>` | 代玩家佩戴或卸下 |

称号 id 参数提供补全，候选来自当前已加载的定义。

---

## 九、性能约束 (DECIDED)

1. 显示路径（`NameFormat`、`TabListNameFormat`）只读内存缓存，不查数据库。前缀 Component 按称号 id 缓存，数据包重载时清空。
2. 名牌同步只在状态变化时发包，不做逐 tick 同步。
3. 数据库只在登录、登出、发放、回收、佩戴这几个低频点访问。

---

## 十、测试 (TODO)

GameTest 放在 `com.miningdim.title`，使用 `testutil.TempStoreDb` 提供的临时库：

- `grant` 幂等；`revoke` 正在佩戴的称号后自动卸下
- 未拥有或定义缺失时 `equip` 返回失败
- 定义删除后持有记录保留，定义恢复后显示复原
- `grantInTransaction` 事务回滚后没有残留
- `title.list` / `title.equip` 的 WebUI 契约（仿照 `QuestWebUiGameTests`）
- 定义校验：非法颜色或非法稀有度会被跳过，且不影响其余定义

---

## 十一、分期

| 期 | 内容 |
|---|---|
| P1 | 模块骨架、定义加载、存储、`ITitleService`、三处显示、同步包、管理员命令、GameTest |
| P2 | "我的称号"页签（随成就点商店页一起上线） |
| P3 | 婚姻称号接入（`Marriage_System_DesignSpec` 中规划的夫妻称号改由本系统发放）、名牌流光动画 |

## 十二、待定项

| # | 事项 | 状态 |
|---|---|---|
| 1 | G 面板浅色主题下七档加深色的具体色值 | PENDING |
| 2 | 名牌流光动画是否要做 | PENDING |
| 3 | 多子服架构下称号数据的共享方案 | PENDING（接口已预留） |
