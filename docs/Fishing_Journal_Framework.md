# WOK 渔业图鉴框架

## 产品边界

本模块并入 WOK 本体，保持 `modId=miningdim`、Java 包根 `com.miningdim` 和单 JAR 交付。入口为 `com.miningdim.job.fisher.FishingSystem`，源码归属 `job/fisher`。它不属于 WOK步战及其附属，也不修改 Tide 的 JAR。

当前提供鱼种目录、查阅界面和收藏进度，并接入五种矿石鱼的钓获、出售与厨师鱼羹，详见 [矿石鱼与鱼羹](Ore_Fish_And_Soup.md)。渔夫经验、职业能力和委托由后续功能接入；收藏记录不冒充亲手钓获，也不发放经验。

## 玩家入口和收藏规则

- 合成：一本书与一个墨囊，无序合成为 `miningdim:fishing_journal`。
- 右键图鉴打开界面；也可执行 `/fishing journal`，普通玩家可用。
- 创造模式在原版“工具与实用物品”分类查找“渔业图鉴”。
- 界面支持分类、名称/注册 ID 搜索、全部/已收录/未收录筛选、分页、鱼种详情和收藏完成度。
- 获得并持有目录中的鱼物品即可收录，交易得到的鱼同样有效。拾取、合成和容器操作即时记录；同一 tick 拿起又放回也会收录，仅查看箱子或交易展示槽不会收录。每秒错峰检查背包与鼠标持物，补充其它 MOD 直接写入背包等路径。打开图鉴和登录也检查持物。
- 出售、食用或存入箱子后保留已经记下的收藏。鱼桶、鱼实体和背包外的箱子不按鱼物品自动收录。
- 图鉴查阅本身不暂停游戏。自动同步只刷新已经打开的图鉴，不抢占其它界面。

## 目录与 Tide 软联动

服务端通过 `SimpleJsonResourceReloadListener` 读取 `data/<namespace>/fishing/journal/*.json`，在登录、打开图鉴、收藏变化及 `/reload` 后下发快照。客户端只渲染服务端目录，不上传收藏状态。

默认目录包括 4 种原版鱼、5 种 WOK 矿石鱼和 66 种 Tide 1.6.5 自有鱼种。Tide 元数据引用其现有描述、地点、条件翻译键及物品模型，不复制其代码、贴图或描述正文。缺少 Tide 时加载 9 种原版与 WOK 鱼；Tide 版本不等于 1.6.5 时跳过该兼容目录并记录版本提示。

分类沿用淡水、海水、地下、深层、群系、结构、岩浆、下界、末地、传说鱼。分类是查阅组织方式，不是 WOK 自定义稀有度或钓获概率。

数据格式示例：

```json
{
  "required_mod": "tide",
  "required_version": "1.6.5",
  "entries": [
    {
      "item": "tide:trout",
      "category": "freshwater",
      "description": "profile.item.tide.trout",
      "habitat": "profile.info.location.freshwater",
      "conditions": "profile.info.climate.cold"
    }
  ]
}
```

`required_mod` 和 `required_version` 可省略；版本限制必须伴随 MOD 限制。`description`、`habitat`、`conditions` 为客户端资源包翻译键，支持中英文切换。数据包可以增加新的文件；覆盖内置条目时应覆盖原文件，重复的物品 ID 会明确报错。新增分类使用 `fishing.miningdim.category.<category>` 翻译键。

目录最多 512 条，分类键为 1–32 位小写字母、数字或下划线，文本键非空且最多 128 字符。已加载 MOD 内出现不存在的物品、重复鱼种或错误字段时拒绝发布这次目录，保留此前完整目录。依赖缺失的兼容文件整体跳过。

## 存档和协议

- SavedData 名：`miningdim_fishing_journal`，位于主世界数据存储。
- 数据版本：1；字段为 `Version`、`Players`，每位玩家记录 UUID `Player` 与物品 ID 列表 `Fish`。
- 收藏以 UUID 归属，跨死亡、重生、维度切换和服务端重启保留；没有记录表示尚未收藏。
- 卸载 MOD/数据包后保留历史鱼种 ID，当前完成度只统计仍在目录中的交集。
- 网络通道：`miningdim:fishing_journal`，协议版本 1，唯一消息为服务端到客户端的目录/收藏快照，含是否主动打开界面的标志。
- 本阶段不增加 `JobId`，不修改现有职业同步包或经验轨道。

## 来源和生成

Tide 1.20.1 官方源码基线：[Lightning-64/Tide，提交 22953b719390](https://github.com/Lightning-64/Tide/tree/22953b719390)。数据映射来自 `common/src/main/java/com/li64/tide/data/journal/JournalLayout.java`。

生成器 `tools/fishing/generate_tide_journal_catalog.ps1` 接受 `-SourcePath` 与 `-JarPath`，固定校验两者 SHA256，并验证 Tide modId、1.6.5 版本、鱼种唯一性、物品模型和英文翻译键。它只读取输入，输出兼容目录 JSON，不联网下载或部署 JAR。

Context7 在本次环境不可用，Forge/Minecraft API 依据工程锁定的 Forge 47.3.0 / Minecraft 1.20.1 本地源码和签名核对，Tide 使用上述同版本官方源码和实际 JAR 核对。

## 验证入口

使用本工程 Gradle Wrapper 与 JDK 17 编译。`FishingJournalGameTests` 提供五条可达业务用例：目录和合成入口、持有收录及幂等、存档与玩家隔离、网络往返、失败热重载保留旧目录。

运行 `runGameTestServer` 后必须确认日志出现 `All N required tests passed`，不能仅以 Gradle 退出码判断。Tide 实例的游戏内图鉴渲染、中文排版、拾取反馈与重连仍需对应客户端实测。

2026-09-05 验证结果：

- 隔离功能工作树 `compileJava` 通过。
- 未安装 Tide：加载 4 条目录，完整 GameTest 日志确认 `All 782 required tests passed`。
- 安装 Tide 1.6.5 与同实例 Cloth Config 11.1.136：加载 70 条目录，完整 GameTest 日志确认 `All 782 required tests passed`。
- Tide 单独启动会缺失 `me.shedaniel.autoconfig.ConfigData`；兼容测试通过临时 Gradle init 脚本加载现有 Tide/Cloth Config JAR，没有将这些依赖加入 WOK 的生产构建。
- 数据生成器重跑后与内置 66 条 Tide 目录的 SHA256 一致。
- 游戏客户端界面尚未实机验收；本次不部署到玩家测试实例。

WOK 本体部署目录仍为 `D:\WOK测试\versions\1.20.1-Forge_47.4.20\mods`。调研时在另一个 `.22` 实例找到 Tide JAR，仅用于只读核对；本任务不向该实例部署。
