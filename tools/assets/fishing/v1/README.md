# 矿石鱼与鱼羹图标首版

生成模式：内置 image_gen。生成器出的原画保留在本目录 `source/`，不进 JAR；
`src/main/resources/assets/miningdim/textures/item/fishing/` 下的最终 PNG 由 `tools/fishing/build_ore_fish_icons.py`
从 `source/` 派生（按 Alpha 包围盒裁切 + 等比缩放到 64x64 居中 + 清掉缩放碎屑）。没有使用参考图直接裁切或程序调色制作新鱼羹。

原画分辨率（1536x1024 与 1254x1254）不能直接当物品贴图：前者不是帧尺寸的整数倍，原版 `SpriteLoader` 会拒绝拼图、
该物品退化成缺失贴图；后者只能被 2 整除一次，会把整张方块/物品图集的 mipmap 从 4 级拉到 1 级。

定 64x64 的两条理由：原版 GUI 缩放最多 4 档、一个物品格恰好 64 真实像素，再高只是白占图集；
更关键的是 `item/generated` 会沿 Alpha 轮廓烘侧面几何（每个「方向 + 锚行/锚列」一个 element），
同一条暗金鱼 1254x1254 原画要 3473 个、256x256 要 372 个、64x64 只要 134 个，不清缩放碎屑会涨到 220。
改美术时请改 `source/` 再重跑脚本，不要直接往资源目录塞原画。

铁鱼、金鱼、绿宝石鱼采用已选 v1，钻石鱼采用真实透明的 v3；暗金鱼源样选用户确认的虹彩 v5，保存为 `dark_gold_selected_v5.png`。最终暗金鱼通过内置工具提取背景，保留暗金矿片与蓝紫、玫红、青绿虹彩。

提示词留档并不完整：本目录有 `gold_prompt_v1.txt`、`emerald_prompt_v1.txt`、`diamond_prompt_v1..v3.txt`、
`dark_gold_*_prompt_v1..v5.txt`，README 正文另存最终暗金鱼与五种鱼羹的提示词；**铁鱼（`iron_ore_fish`）原画的
生成提示词未留档**——既没有 `iron_prompt_v1.txt`，正文里也没有对应段落（下面那条「基础铁羹提示词」是鱼羹，不是鱼）。
重做铁鱼原画时按本目录 `source/iron_ore_fish.png` 的成品风格另拟提示词，不要当成能按原样复现。

最终暗金鱼提示词：Use case: background-extraction. Edit target: the selected dark gold ore fish pixel illustration. Prepare it as a Minecraft inventory sprite PNG. Preserve the identical whole fish, left-facing silhouette, the dark indigo-purple scales, ancient dark gold mineral panels, blue-purple-magenta-cyan iridescent highlights and original pixel geometry. Only remove the entire deep purple backdrop outside the fish to actual RGBA alpha=0 transparency, including gaps around fins and tail. Keep opaque pixels within fish intact. Whole fish centered with clean hard pixel stepped outline and margin. No frame, no shadow, no text. Output a single game-ready square transparent PNG asset.

基础铁羹提示词：Use case: stylized-concept. Asset type: Minecraft pixel-art inventory icon, iron ore fish soup. Draw a single small chunky wooden bowl viewed from slightly above, filled with creamy ivory fish chowder, a few silver-grey fish flesh chunks with tiny iron-grey mineral accents, mushrooms and a little green herb. Appetizing cooked food, no whole fish, no ore ingots. Coarse crisp pixel-art texture, stepped silhouette, broad light/dark clusters, limited colors, Minecraft item illustration. Centered square, fills most canvas with safe margin. True transparent RGBA background alpha=0, no checkerboard, no background, no cast shadow, no text or frame.

## gold_ore_fish_soup

Use case: precise-object-edit. Edit target: the attached iron fish soup inventory icon. Produce its gold ore fish soup variant. Preserve the wooden bowl, same silhouette and viewpoint, ingredients layout, chunky pixel-art style and genuinely transparent RGBA background alpha=0. Change only the fish skin and soup colors to golden amber fish skin pieces, rich warm amber chowder, small green herb garnish. Appetizing food, no whole fish, no literal ore ingots, no gemstones placed on top. No extra objects, no background, no checkerboard, no text, no frame. Single square transparent Minecraft inventory sprite.

## diamond_ore_fish_soup

Use case: precise-object-edit. Edit target: the attached iron fish soup inventory icon. Produce its diamond ore fish soup variant. Preserve the wooden bowl, same silhouette and viewpoint, ingredients layout, chunky pixel-art style and genuinely transparent RGBA background alpha=0. Change only the fish skin and soup colors to pale icy turquoise fish skin pieces with cyan glints, pale cream broth with a faint cool blue tint, green herb garnish. Appetizing food, no whole fish, no literal ore ingots, no gemstones placed on top. No extra objects, no background, no checkerboard, no text, no frame. Single square transparent Minecraft inventory sprite.

## emerald_ore_fish_soup

Use case: precise-object-edit. Edit target: the attached iron fish soup inventory icon. Produce its emerald ore fish soup variant. Preserve the wooden bowl, same silhouette and viewpoint, ingredients layout, chunky pixel-art style and genuinely transparent RGBA background alpha=0. Change only the fish skin and soup colors to deep jade green fish skin pieces with emerald glints, pale cream broth with a faint green tint, fresh green herb garnish. Appetizing food, no whole fish, no literal ore ingots, no gemstones placed on top. No extra objects, no background, no checkerboard, no text, no frame. Single square transparent Minecraft inventory sprite.

## dark_gold_ore_fish_soup

Use case: precise-object-edit. Edit target: the attached iron fish soup inventory icon. Produce its dark_gold ore fish soup variant. Preserve the wooden bowl, same silhouette and viewpoint, ingredients layout, chunky pixel-art style and genuinely transparent RGBA background alpha=0. Change only the fish skin and soup colors to iridescent dark indigo violet fish skin pieces with restrained deep antique gold, magenta and cyan highlights, luminous creamy violet-gold chowder, finest magical delicacy. Appetizing food, no whole fish, no literal ore ingots, no gemstones placed on top. No extra objects, no background, no checkerboard, no text, no frame. Single square transparent Minecraft inventory sprite.
