# WOK-厨师模块

模块键：`wok-job-chef`
技术 modId：`miningdim`
公开入口：`com.miningdim.job.chef.ChefModule`
内部运行时：`com.miningdim.job.chef.ChefSystem`

## 功能边界

本模块负责任意 `ItemStack.getFoodProperties(entity)` 成品食物的品质盖章、五档调味台、火候与调味 QTE、调料方向标签和厨师效果结算契约。品质仍写在原食物的 NBT 上，不注册新的菜肴物品。

客户端界面显示服务端 `ContainerData`：剩余时间、火候、台档/等级可选上限、目标品质、实时成功率、当前 QTE 目标、命中数、失败原因和最终品质。开始包为 `START(targetQualityTier)`，其余输入为 `HEAT_PRESS`、`HEAT_RELEASE`、`SEASON_HIT(target)`；服务端重新校验目标品质上限并独立掷结果，客户端不得自行决定品质或效果。

## 配置

服务端配置文件为 `miningdim-chef.toml`，由 `ChefConfig.SPEC` 注册。配置分组包括：`xp`、`amplify`、`nourish_food`、`aftertaste_saturation`、`nourish_heal`、`shield`、`grease`、`aftertaste_regen`、`stable_aim`、`endurance`、`refresh`、`night_sight`、`satiation`、`exploration`、`effect_pool`、`minigame`、`quality_resolution`、`negatives` 和 `economy`。

品质倍率、五档目标基础成功率、控火加成、单次 QTE 加成、战斗向效果的最大生命值比例、小游戏参数和信用点做菜成本均从此配置读取。客户端只读取同步状态，不参与服务端概率结算。

## 注册清单

| 注册类别 | ID |
| --- | --- |
| 方块 | `miningdim:seasoning_table_low`、`medium`、`high`、`extraordinary`、`radiant` |
| 方块实体 | `miningdim:seasoning_table` |
| 方块物品 | 五个与方块同名的 `miningdim:seasoning_table_*` |
| 菜单 | `miningdim:seasoning_table` |
| 创造标签页 | `miningdim:miningdim_chef` |
| 网络频道 | `miningdim:chef` |
| 经验轨道 | `miningdim:job/chef` |
| 经验来源 | `miningdim:chef/seasoning_complete` |
| 窗口 MobEffect | `miningdim:chef_endurance`、`miningdim:chef_satiation`、`miningdim:chef_shield`、`miningdim:chef_grease`、`miningdim:chef_aftertaste_regen`、`miningdim:chef_stable_aim`、`miningdim:chef_fire_quell`、`miningdim:chef_gills`、`miningdim:chef_feather`、`miningdim:chef_firefly` |

## 外部联动与资源所有权

GeckoLib 是客户端与服务端都必须安装的运行时依赖，锁定 `4.8.2`，允许范围为 `[4.8.2,4.9)`。双格调味台只在左侧主格保存方块实体、库存和事务；主格的 `GeoBlockRenderer` 负责一次性渲染横跨两格的完整工位，右侧副格只承担碰撞和交互。空闲与烹饪状态由服务端同步到左右两格，分别播放 `chef.idle` 与 `chef.cooking`；烹饪动画包括火焰、蒸汽、锅盖、汤勺、刀具和调料罐。

`farmersdelight` 和 `flavor_immersed_daily` 都是可选依赖。FD 的 `#farmersdelight:feasts` 通过 `unseasonable` 标签默认禁止调味；FID 调料使用可选 item tag 条目，缺失时不会阻止 WOK 启动。

增香效果黑名单包含原版金苹果、附魔金苹果，以及 FID 1.1.0.3 官方 JAR 中核定的 32 个效果。该 JAR SHA-256 为 `C9CE8AFBC6FEBAB2A94AD45247A3D3FCEC32978516E3335134E46ECC0EEF7778`；资源中只放 32 个 optional 条目，`sesameglide` 与 `sesamedoor` 不列入黑名单。

本模块拥有 `seasoning_table_*` blockstate/model、`geo/block/seasoning_table.geo.json`、`animations/block/seasoning_table.animation.json`、五档静态方块纹理与五档 `seasoning_table_*_geo.png` 动态模型图集、调味台 GUI、十个窗口效果图标、`recipes/chef/`、`tags/items/seasonings/*.json`、`tags/items/seasonings.json`、`tags/items/unseasonable.json`、`tags/items/chef_amplify_item_blacklist.json` 和 `tags/mob_effects/chef_amplify_effect_blacklist.json`。共享语言文件仅由本模块维护厨师前缀键。

运行期 PNG 和 GeckoLib JSON 由 `tools/generate_chef_assets.py` 与 `tools/generate_chef_gecko_assets.py` 可复现生成；挑选后的模型说明预览保存在 `docs/assets/chef/`，不得把 `tools/__pycache__` 或本地发布 JAR 纳入资源提交。
