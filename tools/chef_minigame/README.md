# 掌勺小游戏原型

规则以 `docs/chef-rework/minigame-spec.md` 为准。Node 只用内置模块，不需要 `npm install`。

| 路径 | 内容 |
|---|---|
| `sim/sim.js` | 确定性内核：20 tick/秒、32 位整数定点、Mulberry32 随机数；`createGame` / `step` / `result` / `replay` |
| `sim/tests.mjs` | 内核测试：`node sim/tests.mjs` |
| `sim/bots.mjs` | 机器人平衡仿真，输出 `sim/balance.md` / `balance.json` |
| `sim/JAVA_PORTING.md`、`sim/java/` | Java 逐函数移植参考；`GoldenCheck` 用 `sim/golden.json` 对拍 |
| `sim/dish-play.tsv` | 菜表补充列（性子 / 翻面数 / 拍数）的提案 |
| `web/`、`build.mjs` | 网页试玩原型；`node build.mjs` 生成 `minigame.html` 与 `publish-minigame.html` |
| `art/gen.mjs` | 原型用的像素素材生成器 |
| `tools/` | 无头 Edge 截图脚本与截图计划 |

`art/gen.mjs` 会从测试端 mods 目录（默认 `D:\WOK测试\versions\1.20.1-Forge_47.4.22\mods`，可用 `WOK_MODS` 改）里只读取农夫乐事及附属的物品贴图当菜品图标。这些第三方贴图和生成出来的素材、页面都没有入库；要构建页面，先跑一次 `node art/gen.mjs`。
