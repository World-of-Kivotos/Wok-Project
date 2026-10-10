烹饪台造型预览工具（station-preview）
=====================================
把原版 1.20.1 的 JSON 方块模型 + 16×16 贴图按游戏规则渲染成一张网页，和农夫乐事的炉灶、厨锅放在一起比。
第一个用它的是“炸锅”（取代随性乐事的炸锅），以后别的烹饪台照样用。
只用 Node 自带模块，不用 npm install。Node：D:\DevTools\node-v22.23.3-win-x64\node.exe（下文写 node）。
所有命令都在本文件夹里运行：cd <仓库>\tools\chef_stations


一、平常的流程
----------------
1. node extract-refs.mjs          从测试端 jar 取参照方块到 refs/（只读 jar，换 MOD 版本后重跑）
2. 在 designs/<key>/ 下写方案（格式见第三节），一个方案一个文件夹，A/B/C 各一个
3. node check.mjs <key>           按原版规则查错；有 ERROR 时退出码为 1
4. node build.mjs                 生成 preview.html
   node serve.mjs                 然后浏览器打开 http://127.0.0.1:5182/
5. node shot.mjs preview.html shots plans/overview.json
                                  无头 Edge 截图：每个方案四个视角 + 工作中、厨房场景、参照、深色、手机宽度
6. 要发布给别人看：node build.mjs --cdn  → preview-cdn.html（three.js 走 jsDelivr，保留 Google 字体）


二、命令说明
------------
node extract-refs.mjs [--mods <mods 目录>] [--vanilla <原版客户端 jar>]
    默认读 D:\WOK测试\versions\1.20.1-Forge_47.4.22\mods 和同目录的 1.20.1-Forge_47.4.22.jar（原版资源）。
    取出：农夫乐事 cooking_pot（含 tray / handle 两个变体）、stove（待机/点火）、skillet（含 tray）、cutting_board、basket；
          随性乐事的炸锅 casualness_delight:deep_frying_pan（含 tray）。
    写出：refs/<mod>/blockstates|models/block|textures/block/...（和 jar 里 assets/<mod>/ 下路径一致）
          refs/minecraft/models/block/*.json   原版父模型的精简副本（block、cube、cube_all、orientable 等，脚本里手写）
          refs/minecraft/textures/block/*.png  地面用的橡木木板、草方块、泥土（从原版 jar 拷；没有 jar 时自己画替代品）
          refs/index.json     每个参照方块：待机/工作中用哪个模型、变体、贴图列表、实际尺寸（px）
          refs/palette.json   每张参照贴图的颜色数和最常用颜色
          refs/contact-sheet.png  所有参照贴图第 0 帧放大到同一尺寸，排在一张图上

node check.mjs <designs/key 或 key 或任意方案目录>
node check.mjs --refs             （拿参照方块自测检查器）
    ERROR（必须改）：JSON 坏了；父模型找不到；坐标超出 -16..32；from > to；旋转角度不是 -45/-22.5/0/22.5/45；
          旋转不是单轴；uv 超出 0..16；uv 旋转不是 0/90/180/270；#变量解析不到；PNG 不存在；
          每帧不是 16×16（声明 hires 时 32×32 只给 warn）；动画贴图高度不能整除；frametime/frames 不合法；
          同一朝向的两个面共面重叠（z-fighting，游戏里会闪）；meta.json 缺字段、y 不是 90 的倍数。
    warn：面积为 0 的面或 uv；半透明像素却不是 translucent；用了透明像素却没写 render_type（游戏默认 solid，透明处发黑）；
          没有 particle；32×32 高清贴图；rescale 配 angle 0（原版会放大 1.414 倍）。
    info：被别的元素包住的面、只取到透明像素的面（都可以删）；伸出方块格外的元素（旋转过的元素按旋转后的实际范围算）；动画帧数和一圈时长；元素/面/贴图统计。

node build.mjs [--cdn] [--out <文件>] [--designs <目录>]
    把 designs/ 下所有方案（按 meta.order，再按文件夹名）和 refs/ 打进一个 HTML，模型和贴图都内嵌成 data URI。
    默认输出 preview.html；--cdn 时默认 preview-cdn.html。
    本地版从 ./vendor/three.module.min.js 加载 three（r160），并去掉 Google 字体链接（离线也能开）；
    浏览器不允许 file:// 下加载模块，所以本地版要用 serve.mjs 或 shot.mjs 打开。
    --cdn：three 用 https://cdn.jsdelivr.net/npm/three@0.160.0/build/three.module.min.js，字体用 Google Fonts 的 Noto Sans SC。
    --designs tools-test/designs --out tools-test/sample.html：用工具链自带的示例方案出页面。
    --out 的目录不存在时会自动建。本地版输出到本文件夹以内时引用 ../vendor（要从本文件夹起服务，shot.mjs 会自动这样做）；
    输出到本文件夹以外（别的目录或别的盘，例如 scratch）时，会把 three 拷一份到输出目录的 vendor/ 下，页面引用 ./vendor。

node serve.mjs [端口] [页面]       默认 5182、preview.html，只服务本文件夹。

node shot.mjs <页面.html 或 http 地址> <输出目录> <plan.json> [--width 1400] [--height 1000] [--dpr 1] [--dark] [--keep]
    本地页面会自动起一个临时服务器；Edge 用 .scratch/ 下的独立配置目录，结束（含 Ctrl+C）时整棵进程树结束、目录删掉
    （--keep 保留配置目录排错）。被强行结束、没来得及清理的配置目录（所属 node 进程已不在、15 分钟没动过），下次运行时顺手删掉。
    等页面 window.__ready 为 true 才开始。某一步 JS 报错或页面有 console.error 时退出码 1。
    plan.json 是步骤数组，按顺序执行：
      {"name":"a-iso", "js":"__focus(['a']); __view('iso','a')", "wait":400}          截整个视口
      {"name":"a-view", "js":"...", "selector":".card[data-key='a'] .view"}             只截这个元素
      {"name":"page", "full":true}                                                     整页长图
      {"name":"keys", "js":"__keys()", "value":true}                                   只打印结果，不截图
      {"name":"{key}-iso", "forEach":"__keys()", "js":"__focus(['{key}']); ..."}       对每个方案展开一步
      可选："width"/"height"/"dpr" 改视口（之后的步骤沿用），"media":{"prefers-color-scheme":"dark"}
    现成的计划：plans/overview.json（全套）、plans/smoke.json（能否打开、有无报错、--cdn 版的 Noto Sans SC 是否加载；本地版为 0 是正常的）、
      plans/review.json（发布前验收：真点按钮切待机/工作中并比对像素、真时钟下动画是否在走、1400 和 400 宽、浅色和深色整页，
      以及横向溢出、文字被裁、厨房标签重叠的检查结果，例：node shot.mjs preview.html shots plans/review.json）。

node tools-test/make-sample.mjs [目录]
    生成工具链自测用的示例方案（默认 tools-test/designs/_sample），顺便演示 lib/png.mjs 画贴图的写法。


三、方案文件夹 designs/<key>/
-----------------------------
目录结构和主 MOD 的 assets/miningdim/ 一样，定稿后 models/ 和 textures/ 可以直接拷进去：
  designs/a/meta.json
  designs/a/models/block/deep_fryer_a.json          → 模型 id  miningdim:block/deep_fryer_a
  designs/a/models/block/deep_fryer_a_on.json
  designs/a/textures/block/deep_fryer_a_side.png    → 贴图 id  miningdim:block/deep_fryer_a_side
  designs/a/textures/block/deep_fryer_a_oil.png + .png.mcmeta   （动画：16 宽的竖条，每帧 16×16）
模型里引用别的 MOD 的东西照常写（farmersdelight:block/cooking_pot_side、minecraft:block/block），会去 refs/ 找。

meta.json：
{
  "key": "a",                    和文件夹名一致
  "tag": "方案 A",               卡片左上角的小字
  "order": 1,                    排序（小的在前）
  "name": "立式油炸槽",
  "tagline": "一句话特点",
  "description": "一段话说明造型",
  "notes": ["机制说明 1", "机制说明 2"],
  "hires": false,                true 时允许 32×32 贴图（只给 warn）
  "namespace": "miningdim",      默认就是 miningdim
  "kitchen": { "under": "fd_stove" },   可选：要放在炉灶上用时写；厨房场景里会在下面垫一台炉灶
  "blocks": [
    { "label": "炸锅", "pos": [0, 0, 0],
      "idle":   { "model": "deep_fryer_a",    "y": 0 },
      "active": { "model": "deep_fryer_a_on", "y": 0 } }
  ]
}
  model 可以写全名 miningdim:block/x，也可以只写 x。y（以及 x）是方块状态旋转，只能 0/90/180/270。
  朝向约定：模型的正面画在 north 面（和农夫乐事炉灶一样），facing=north 时 y=0，east 90，south 180，west 270。
  pos 是世界坐标：从正面看，+x（东）在左边。两格宽的方块想让第二格出现在第一格右边，写 "pos": [-1, 0, 0]。
  顶面（up）贴图的上边缘对着北面，也就是站在正面看时顶面贴图是倒着的；画油面这类有方向的图案时注意。
  工作中模型建议用 parent 继承待机模型，只覆盖要换的贴图（示例方案就是这样）。
  要镂空（透明像素）必须写 "render_type": "minecraft:cutout"，不写的话游戏按 solid 画，透明处发黑。


四、页面里的调试钩子（shot 计划里用）
------------------------------------
  __keys()                          方案 key 列表
  __view(name, key?)                视角：'front' 正面 / 'iso' 斜视 / 'top' 俯视 / 'side' 侧面（看 west 面）；不给 key 就全部
  __cam(yaw, pitch, zoom?, key?)    自由角度（弧度；yaw 0 = 从北面看，正值往东转）；zoom 是相机距离的倍数：1 = 刚好装下，
                                    大于 1 拉远，小于 1 拉近（0.25–4）
  __state(key, 'idle'|'active')     key 可以是方案 key、'kitchen'、'refs'、参照方块 key（fd_stove 等）或 '*'
  __focus(['a','kitchen'])          只显示这些卡片/区块，放大，滚到顶；__focus() 恢复
  __anim(tick) / __anim(null)       动画停在第 tick 个游戏刻 / 恢复播放
  __floor('oak'|'grass')            地面
  __render()                        立刻重画全部视图
  window.__ready / window.__errors  加载完成标志 / 模型和贴图解析错误


五、渲染规则（和游戏的差别）
--------------------------
照搬原版：元素 from/to、单轴旋转和 rescale（含 angle 0 + rescale 也放大的怪癖）、默认 UV、UV 旋转、#变量和父模型链、
cullface（贴着整格不透明方块的面不画）、背面剔除、最近邻取样、cutout 丢 alpha<0.1、cutout_mipped 丢 <0.5、translucent 混合、
方块状态 x/y 旋转、面的明暗（顶 100%、南北 80%、东西 60%、底 50%，shade:false 全亮）、mcmeta 动画（frametime、frames、interpolate）按 20 tick/秒。
不做：环境光遮蔽（AO）、光照等级、粒子、半透明面逐面排序。草地顶面按平原草色 #91bd59 染色（简化模型，没有侧面草边叠层）。


六、文件
--------
  extract-refs.mjs  check.mjs  build.mjs  shot.mjs  serve.mjs
  viewer.js + template.html     页面（build.mjs 把 lib/mcmodel.js 和 viewer.js 拼进 template.html）
  lib/mcmodel.js                原版模型数学（解析父链、#变量、默认 UV、顶点顺序、旋转、动画帧），check 和页面共用
  lib/png.mjs                   PNG 编解码（读：调色板/RGB/RGBA/灰度、1–16 位、tRNS、隔行；写：RGBA8）+ 画贴图的小工具：
                                makeCanvas(w,h,fill?)  setPx  getPx  fillRect  outline  blit  strip(帧数组→动画竖条)  scale  write(c, 路径)  read(路径)
  lib/zip.mjs                   只读 jar/zip   lib/static.mjs  静态服务器
  vendor/three.module.min.js    three r160（本地版用）
  refs/                         参照（extract-refs.mjs 生成，可随时重建）
  designs/                      方案（build 的输入）
  plans/                        截图计划
  tools-test/                   自测：designs/_sample（示例方案）、broken/_broken（故意写错，check 应报 9 个 ERROR）、
                                sample-plan.json、sample.html、shots/、shots-empty/（截图结果）


七、参照方块的数据（给画模型的人）
--------------------------------
  农夫乐事厨锅：锅身 12×10×12（from 2,0,2 到 14,10,14），两侧把手 2×2×6 在 y 7–9，伸到方块边（x 0–2 / 14–16）；
               木勺 2×12×2 绕 z 轴 -22.5°，顶到 y≈14.5；4 个元素；工作中模型不变。
  农夫乐事炉灶：整格方块（orientable_with_bottom）；点火后正面 6 帧×3 tick、顶面 4 帧×8 tick 插值。
  农夫乐事煎锅：14×4×14 浅盘，锅柄伸出方块到 z=27。
  随性乐事炸锅：锅 12×10×12，壁厚 1px，油面在 y=9；炸篮 8.6×10×8.6 高出锅口到 y=12；32×32 贴图（密度是农夫乐事的 2 倍）。
  颜色：农夫乐事的锅具是一条冷灰梯度 #1f1f21 #27272b #2d2d32 #343438 #3f3e42 #494848 #4f4f4f #595858 #5c5c5c #656565 #676161 #727272；
       锅具的小贴图每张只用 3–7 种颜色（炉灶、篮子这种整面贴图 13–20 种），没有半透明像素（alpha 只有 0 和 255）。炉灶砖 #733f31 #7c4536 #8f503f #9b5643 #b1624d，
       砖缝 #8b6e67 #a2867d，火 #c35d1b #ed8c0e。木头（砧板/勺）#674c2c–#997140。详见 refs/palette.json 和 refs/contact-sheet.png。
