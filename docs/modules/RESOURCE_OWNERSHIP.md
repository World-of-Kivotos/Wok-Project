# WOK 资源所有权

Forge 仍要求资源位于统一的 `assets/miningdim` 与 `data/miningdim` 命名空间。本次不移动资源 ID，而是按路径和前缀确定业务所有权。

## 1. 所有权映射

| 模块 | 资源路径或前缀 |
| --- | --- |
| WOK-综合装配 | `META-INF/mods.toml`、`pack.mcmeta`、`accesstransformer.cfg` |
| WOK-核心 | 公共语言键、公共菜单/网络 GUI、GameTest 空模板；共享文件按 key 所有权拆分 |
| WOK-全服经验 | 经验轨道、来源 ID、经验提示/HUD 公共语言键；当前不新增独占纹理或存档文件 |
| WOK-矿区副本 | `data/miningdim/dimension*`、`worldgen`、`structures`，以及 `entrance_*`、矿区入口和世界生成资源 |
| WOK-经济 | 货币与经济提示语言键；不拥有市场页面和开箱资产 |
| WOK-WebUI | `assets/miningdim/web/` 通用页面宿主与 WebUI 公共资源 |
| WOK-市场 | 市场 action、列表字段和市场语言键；当前与 WebUI 共用页面文件时按 action 区段维护 |
| WOK-农夫 | `farmer_*`，`recipes/farmer/`，`tags/blocks/farmer_farmland.json` |
| WOK-铸甲师 | `production_table_*`、`nano_plate_*`、`plate_armor_*`、`plasma_shield_*`、护甲/护盾声音与模型 |
| WOK-厨师 | `seasoning_table_*`、`recipes/chef/` 及菜肴效果语言键 |
| WOK-酿酒师 | `brewing_station*`、`wine_cellar*`、`wine_*`、`dried_wheat`、`recipes/brewer/` |
| WOK-塔罗师 | `tarot_*`、`data/miningdim/tarot/`、`sounds/job/tarot/` |
| WOK-军火商 | `munitions_*`、`gunsmith_*`、弹药/推进剂/底火/弹壳、`custom/miningdim_gunsmith/`、军械声音 |
| WOK-特勤干员 | 特勤扫描面板、封印与悬赏相关语言键和客户端资源 |
| WOK-精英怪 | `data/miningdim/affix_setting/`、精英词条/粒子/体型相关资源 |
| WOK-婚姻社交 | `engagement_ring`、`wedding_ring`、共享背包及婚姻语言键 |
| WOK-开箱 | `custom/miningdim_cases/`、`sounds/ui/case/`、箱池/钥匙/开箱 UI 资源 |
| WOK-实体堆叠 | 堆叠配置与语言键；当前无独占模型纹理 |

## 2. 共享文件纪律

- `assets/miningdim/lang/en_us.json` 与 `zh_cn.json` 是物理共享文件，但 key 必须使用模块前缀；职业至少使用 `*.miningdim.<job>.*`。
- 同一次提交只能修改本模块拥有的语言 key。格式化工具不得重排整个共享文件制造跨模块 diff。
- `assets/miningdim/web/index.html` 为物理共享页面。新增功能必须以 action 前缀区分：`market.*`、`case.*`、`system.*`。
- `sounds.json` 或声音注册表中的条目与实际 `.ogg` 一并归属，不得只迁移其中一半。
- 配方引用另一个模块的物品不改变配方所有权。例如 `recipes/brewer/dried_wheat.json` 属酿酒师，不属农夫。

## 3. 生成物与源素材

- `src/main/resources` 只放最终会进入 JAR 的运行期资源。
- `tools/` 放可复现生成脚本，不放 `__pycache__`。
- `docs/assets/<module>/` 放设计稿和经过挑选的说明预览。
- `artifacts/`、`outputs/`、`tmp/` 放本地生成物并由 Git 忽略。
- `dist/` 的 156 个历史跟踪文件需逐个判断是源素材、文档预览还是发布产物；在完成审查前不批量删除。
- 发布 JAR 不进入新提交；历史已跟踪 JAR 另开清理提交处理。

## 4. WOK步战隔离

`standalone/wok-infantry-armor` 及其资源属于 `WOK步战附属-独立护甲`，正式 modId 为 `wok_infantry_armor`，不与本表中的 `WOK-本体护甲` 资源互相覆盖或回灌。

## 5. 独立卡牌 MOD 隔离

`standalone/wok-cardgame` 属于 `WOK-卡牌游戏独立MOD`，正式 modId 为 `wok_cardgame`、Java 包根为 `com.wok.cardgame`。根目录的 `docs/cardgame` 与 `outputs/cardgame` 是历史遗留位置，后续应在卡牌 MOD 自己的仓库迁移；不得因此把卡牌代码或资源登记进 WOK 本体。
