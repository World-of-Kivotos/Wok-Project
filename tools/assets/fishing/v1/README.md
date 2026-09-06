# 矿石鱼与鱼羹图标首版

生成模式：内置 image_gen。最终 PNG 位于 `src/main/resources/assets/miningdim/textures/item/fishing/`，均保留生成器的原始分辨率和真实 Alpha。没有使用参考图直接裁切或程序调色制作新鱼羹。

铁鱼、金鱼、绿宝石鱼采用已选 v1，钻石鱼采用真实透明的 v3；暗金鱼源样选用户确认的虹彩 v5，保存为 `dark_gold_selected_v5.png`。最终暗金鱼通过内置工具提取背景，保留暗金矿片与蓝紫、玫红、青绿虹彩。

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
