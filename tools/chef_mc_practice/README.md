# 掌勺练习像素素材

`art/gen.mjs` 是原始像素素材生成器，`art/lib/png.mjs` 和 `art/lib/zip.mjs` 是它所需的最小库。生成器只从 `WOK_MODS` 指定目录读取物品模型和贴图，输出到 `art/sprites`、`art/icons`、`art/atlas`、`art/preview` 以及 `art/manifest.json`、`art/dishes.json`；不会安装、执行或复制任何 MOD。

在拥有来源 JAR 的机器上，从 `tools/chef_mc_practice/art` 执行：

```text
WOK_MODS=D:/Wok/tmp/chef-playtest-20261021/asset-jars node gen.mjs
```

`source/dish-stars.tsv` 是菜品星级来源，`source/dish-play.json` 是模拟器为示例菜提供的翻面和节拍数据，`source/asset-sources.json` 记录素材 JAR 来源。第三方图标的许可证核对见资源目录下的 `asset-license-notes.json` 与 `META-INF/licenses/zhangshao/ATTRIBUTIONS.txt`。Cultural Delights 的 JAR 明确写有 `All Rights Reserved`，因此生成器不读取该 namespace 的图标，五道示例菜统一使用自产的通用练习菜盘。
