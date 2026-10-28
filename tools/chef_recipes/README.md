# WOK 本体厨师菜品迁移数据

运行 `python tools/chef_recipes/generate.py` 从 `docs/chef-rework/dish-stars.tsv` 和 `tools/chef_mc_practice/source/dish-play.json` 生成 `data/miningdim/chef/production-dishes.json` 与 `metadata-audit.json`。配方食材不从星级计分明细推导；实际配方来源于游戏资源加载后确定的 JSON。

初始 104 道依照 practice 表分台为炸锅 4、烤炉 37、备餐台及冷饮 63。`stations.md` 的分类汇总为 4、36、64，文档按大类描述而非逐 ID 列表。practice 表将牛油果土司以及 More Delight 六种吐司共七道归烤炉，是一项可能的数量差异来源，不能据此断定文档的唯一原意。五道原厨锅炸物另迁入炸锅，其中鱿鱼圈和炸蟹角按炸锅规则采用窜性子，其余采用默认浮性子，最终共 109 道。

`migration-manifest.json` 记录静态抽查使用的来源 JAR 的 SHA-256 和相同 recipe ID 的多源冲突。素材缓存的 Farmer's Delight 1.2.9 与本体测试实例的 1.3.2 并不相同；饺子缓存为 Fabric 版而非 Forge 版。这些缓存不是运行时配方或资源包优先级的事实来源；不得把它们作为现有安装版本的保证。`casualness_delight:crafting_shaped/deep_frying_pan` 是旧炸锅设备合成配方 ID，输出是 `casualness_delight:deep_frying_pan`。

11 组已核实的整件与同款切片关系在生成器的 `REASSEMBLY` 表声明，并写入菜品的可选 `reassemblyItem` 字段；缺少该字段表示尚无同款切片重组关系。迁移按实际原料判断：只要每个必需原料都能由该款切片满足，就保留来源配方和条件，不转为掌勺菜谱，因此不会进入经验、手熟或 CHEF_DISH 结算。判断不依赖 recipe ID、形状或切片数量，也支持原料替代数组。蛋糕七片、披萨及派类四片的已知重组均覆盖；鳕鱼卷等把切片作为另一道菜原料的正常制作继续迁移。新增切片物品或以 tag 代替物品声明的整合包配方，须核对并扩展明确关系，不能凭名称猜测。
