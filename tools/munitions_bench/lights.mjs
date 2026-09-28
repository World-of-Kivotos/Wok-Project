// 军火台 (WIDE 布局, 弹药流水线) 的运行灯效: 效果程序、贴在哪 (目标面)、每档颜色、Java 常量的写出与解析, 以及
// block/MunitionsBenchLights.java 的 JS 镜像。零依赖 (档位颜色取 tiers.mjs, 朝向变换与计数屏共用 counter.mjs 的 benchToBlock,
// 皮带位移取 ber.mjs 的 sampleProgram = MunitionsBenchProgram.sample 的镜像)。方案评选时的候选 (预览页、评审记录) 在本机
// .candidates/munitions_lights/, 不进版本库; 这里是用户拍板后的实装版。
//
// 做什么: 工作时在静态模型已有的发光灯带上, 只在程序里真实发生动作的那一刻叠一层短暂的彩色四边形 (方块实体渲染器每帧画),
//   不加方块状态、不发逐 tick 的包、不改静态模型。输入全是客户端已有的: ACTIVE (方块状态) + 档位 (方块) + 同步标签里的程序起点
//   与满仓; 程序时间与运动件同一个时钟; 待机时的满仓闪烁用客户端自己的 gameTime; 相机到主格中心的距离 (呼吸在远处渐隐)。
//
// 按档位 (用户拍板, 这是唯一的行为, 没有 config): 功能反馈全档都有 —— 工位指示灯 / 冲压闪光 / 落箱脉冲 / 满仓提示;
//   运行呼吸从高级 (档 2) 起, 皮带追光从极品 (档 3) 起, 宝石脉冲只有闪耀 (档 5, 也只有它有宝石)。见 EFFECTS[].unlock。
//
// 三方共用这一份 (分工同 counter.mjs):
//   - 生成器 (generate_munitions_bench.mjs): 把这里的目标面、时间与透明度、每档颜色写进 MunitionsBenchGeometry 的 LIGHT_* 常量
//     (lightsJavaLines; 写完用 parseLightsJava 解析回来必须与 lightsLayout() 相同), 并按刚生成的 JSON 核对前提:
//     targetProblems (每个目标矩形落在每档模型那个元素的那个面上、shade 标志与自发光等级、grow 的边是元素的棱、
//     且正好接上同组往回胀的覆盖面 (growProblems), 不胀的边不与同组相接)、
//     programProblems (脉冲的峰与关键帧表对得上)、frameProblems (任何时刻覆盖层不重叠、α 不落在 (0, 0.1)、不超过 MAX_QUADS);
//   - Java (block/MunitionsBenchLights.java): compute / benchCorners / blockCorners 与这里的 lightFrame + levels + lightOverlays /
//     overlayCorners / blockCorners 逐行对应 —— **改一边必须改另一边**; check_parity.mjs 单独编译它与这里逐值对拍;
//   - 预览 (render_lights.mjs): 先核对 Java 的 LIGHT_* 与这里相同, 再用这里的函数画。
//
// 游戏里的三条硬约束 (预览 lraster.mjs 照样模拟):
//   1. rendertype_text_background.fsh: `if (color.a < 0.1) discard;` (1.20.1 client.jar 原文), 被丢的片元不写颜色也不写深度。
//      所以任何四边形每个顶点的 α 要么 ≥ ALPHA_CUTOFF, 要么整块不画 (追光彗尾的渐变两端都要 ≥ 0.1, 见 chaseRects);
//      Java 写顶点时 α 按 round(a × 255) 取整, a ≥ 0.1 → ≥ 26/255 > 0.1。
//   2. textBackground 上传时按四边形中心到相机的距离从远到近排序 (sortOnUpload), 并写深度: 同一块面上不能叠两层半透明四边形
//      (后画的那层被先画那层的深度挡掉, 与顺序有关就出鬼影)。这里保证任何两个覆盖四边形在面上都不重叠 (呼吸与工位灯在同一条
//      前沿灯带上: 灯带切段, 工位灯那段的颜色在 CPU 上先合成, 见 stripRects; 落箱与满仓同组但一个只在工作、一个只在待机)。
//   3. 覆盖层颜色 = rgb × level.getShade(面方向按朝向转过去, 目标的 shade 标志): 与静态元素那一面的明暗一致
//      (宝石元素是 shade:false, 六个面都 × 1.0; 其它灯带 shade 默认 true: 北南 0.8 / 上 1.0 / 东西 0.6)。
//
// BER 契约 (client/MunitionsBenchCounterRenderer 在计数屏的字之后, 用同一个 textBackground 的 VertexConsumer, 额外 draw call 0):
//   相机离主格中心 > MAX_DISTANCE_BLOCKS (= COUNTER_MAX_DISTANCE_BLOCKS, 24) 格不画; LEGACY 台子不画;
//   角 = blockCorners (与计数屏同一个朝向变换, 不做运动件的 y 翻转), 顶点顺序从面外看逆时针 (textBackground 剔除背面);
//   光照 LightTexture.FULL_BRIGHT (比部分静态灯带的自发光亮: strip / can_strip / gem 15 严丝合缝, crown_light 14, rail_b 11);
//   程序时间与运动件相同 (程序起点 + partialTick; 起点比本地时钟快时停在首帧)。
//
// 时间 (浮点 tick, 已加 partialTick; partial 是 Java 的 float, 各时钟在 double 里加 = 精确, 见 lightFrame):
//   档位越高生产动画越快 (tiers.mjs cycleTicks = MunitionsBenchProgram.CYCLE_TICKS_BY_TIER: 普通 40 .. 闪耀 20 tick 一个循环, 2 倍速)。
//   脉冲、工位灯、追光包络都定义在 40 tick 的程序时间里, 所以跟着运动件一起变快; 呼吸与满仓闪烁是游戏时间, 不随档位变。
//   cycleTick  = 程序时间 = ((elapsed mod 该档循环) + partial) × CYCLE_TICKS / 该档循环  —— = MunitionsBenchProgram.programTick
//                (与运动件 sample(long, float, tier, Pose) 同一个映射, 在 long 里取模)
//   elapsed    = 开工以来的程序时间 = (elapsed + partial) × CYCLE_TICKS / 该档循环 (只用来判断跨接缝的尾巴发生过没有)
//   breathTick = floorMod(elapsed, BREATH_PERIOD_TICKS) + partial     (游戏时间)
//   clockTick  = floorMod(gameTime, FULL_BLINK_PERIOD_TICKS) + partial   (待机的满仓闪烁, 游戏时间)
//   跨循环接缝的脉冲尾巴 (落箱 t 35 → 下一轮 t 3、入口灯 t 38 → t 3, 程序时间) 只在那一拍真的发生过时画: d ≤ elapsed (见 pulse)。
//
// 光敏安全 (频率按最快的闪耀档 = 2 倍速算; programProblems 逐档核对, 上限 MAX_FLASH_HZ = 3 Hz): 每块灯面每个循环最多亮暗一次
//   (工位灯各段、横梁冲压闪光、落箱脉冲: 普通 0.5 Hz, 闪耀 1 Hz), 只有宝石两次 (冲压白闪 t 10 + 落箱回响 t 35: 闪耀 2 Hz);
//   满仓 0.5 Hz 与呼吸 (4 s 一周、只往亮的一侧、幅度 0.2) 走游戏时间, 不随档位变快; 追光与皮带同速 (每一点每循环只过一次头),
//   瞬时频率 = 1.5 Hz × 倍速, 闪耀正好 3.0 Hz = 上限 (用户认可: 只是后护栏背光两行 23 × 1 px 的小面)。其余都低于 3 Hz。
//   最大的闪光面是压机横梁灯条 5 × 0.5 px (北面 + 顶面 + 两端)。
// 读得清: 不碰计数屏 (字和满度条都不动); 除了追光的暗槽, 所有覆盖层都只往亮的方向拉, 工作态任何时刻都不比静态模型暗。
import { TIERS } from './tiers.mjs';
import { benchToBlock } from './counter.mjs';
import { sampleProgram, programTick, cycleTicksOf, requireTier } from './ber.mjs';

export const LIFT_PX = 0.125;                 // 覆盖层浮出面外 (px) = COUNTER_LIFT_PX: 24 格内 24 位深度不会闪, 仍小于任何相邻台阶
export const GEM_LIFT_PX = 0.03125;           // 宝石只有 1 px, 浮 0.125 会大出 25% 像一层壳; 1/32 px 在 24 格处仍有约 3 倍深度余量
export const ALPHA_CUTOFF = 0.1;              // rendertype_text_background.fsh 的 discard 阈值
export const MAX_DISTANCE_BLOCKS = 24;        // = COUNTER_MAX_DISTANCE_BLOCKS (生成器核对)
export const FADE_START_BLOCKS = 20;          // 常亮的大面积层 (呼吸) 在 20..24 格渐隐, 不在 24 格整条跳变
export const CYCLE_TICKS = 40;                // = MunitionsBenchProgram.CYCLE_TICKS (程序时间, 生成器核对; 各档的实际循环长度见 tiers.mjs cycleTicks)
export const STRIKE_TICK = 10;                // = MunitionsBenchProgram.STRIKE_TICK (程序时间)
export const MAX_FLASH_HZ = 3;                // 光敏上限: 任何一块灯面亮暗 (追光: 彗星扫过一点) 都不超过 3 Hz, 逐档核对 (programProblems)
export const BELT_PITCH = 4;                  // = MunitionsBenchProgram.BELT_PITCH
export const DROP_LAND_TICK = 35;             // 关键帧 f7: 出弹整发没入弹药箱 (dropY 到底)
export const MAX_QUADS = 32;                  // Java 定长数组的上限 (各档实际最多几个见生成器输出 "lights: max quads", 生成器逐 0.25 tick 核对)

// ---------------------------------------------------------------- 工位指示灯 (事件脉冲)
// 台面前沿通长灯带 strip 上、每个弹位正下方一段 LED_WIDTH_PX 宽 (北面 + 顶面)。数组按 x 从小到大 (= Java 的循环顺序; 切段要按 x 走)。
// 从玩家那一侧 (台前) 看, x 大的在左: 入口 → 底火 → 装药 → 压弹头 在 t 38 → 0 → 5 → 10 从左往右依次一闪, 出弹在 t 35。
// peak = 该工位动作到底的那一刻 (key = 关键帧表里的列, programProblems 核对 "该列第一次到最小值的 tick = peak");
// 入口 = 新壳在接缝处落进入口位 (f0), 错开到 t 38, 不和底火同一刻, 整排读起来是一次从左到右的扫过。
export const LED_WIDTH_PX = 1.5;
export const LED_PULSE = { attack: 1, decay: 5 };
export const LEDS = [
    { name: 'out', label: '出弹', x: 10.5, peak: DROP_LAND_TICK, key: 'dropY' },
    { name: 'press', label: '压弹头', x: 14.5, peak: STRIKE_TICK, key: 'ramY' },
    { name: 'powder', label: '装药', x: 18.5, peak: 5, key: 'powderY' },
    { name: 'prime', label: '底火', x: 22.5, peak: 0, key: 'primeY' },
    { name: 'feed', label: '入口', x: 26.5, peak: 38, key: null },
];
export const LED_MERGE_EPS = 1 / 256;         // 工位灯亮度低于这个 = 不单独切段 (并进呼吸段)

export const STRIKE_PULSE = { peak: STRIKE_TICK, attack: 1, decay: 6 };   // 冲压闪光: α = sqrt(脉冲) = 6 tick 线性衰减, 颜色 = mix(hi, 白, 脉冲)
export const DROP_PULSE = { peak: DROP_LAND_TICK, attack: 2, decay: 8 };  // 落箱脉冲
export const DROP_ALPHA = 0.9;
export const GEM_PULSE = { peak: STRIKE_TICK, attack: 1, decay: 8 };      // 宝石: 冲压白闪
export const GEM_ECHO_PULSE = { peak: DROP_LAND_TICK, attack: 1, decay: 6 };   // 宝石: 落箱回响
export const GEM_ECHO_LEVEL = 0.8;
export const FULL_BLINK_PERIOD_TICKS = 40;    // 满仓 0.5 Hz: 梯形 0..4 渐亮, 4..16 亮, 16..20 渐暗, 20..40 灭
export const FULL_BLINK_RAMP = [0, 4, 16, 20];
export const FULL_ALPHA = 0.95;
export const BREATH_PERIOD_TICKS = 80;        // 运行呼吸: 4 s 一周, 起点最亮, 只往 hi 拉 α 0.12..0.32 (下限高于 0.1, 不会被 discard 截断)
export const BREATH_ALPHA = [0.12, 0.32];
export const CHASE_WINDOW = [9, 12, 23, 27];  // 皮带追光只在皮带步进 (t 10..25) 时亮: smoothstep(9 → 12) × (1 − smoothstep(23 → 27))
export const CHASE_SPEED = 1;                 // 与皮带同速 (彗星与弹同步, 每一点每循环过一次头)
export const CHASE_TAIL_PX = 2.5;             // 彗尾长 (px); 一个节距里其余 1.5 px 是暗槽
export const CHASE_ALPHA = [0.9, 0.6];        // 头 / 暗槽

// ---------------------------------------------------------------- 贴在哪 (静态元素名 + 面 + 面上的范围, 整台像素, 朝北)
// 数组下标 = Java 的目标下标 (LIGHT_TARGET_*)。el = 生成器 scene() 里的元素名 (跨两格的元素在 JSON 里是 name~main / name~extension);
// targetProblems 逐档核对: 矩形落在该元素的该面上; light = 该面 forge_data block_light (工作态); shade = 元素的 shade 标志;
// grow 的每条边都在元素盒子的棱上。grow = [x0, x1, y0, y1, z0, z1]: 这条边往外胀 lift (1) 还是不胀 (0)。一组面 (如横梁灯条的北 / 顶 /
// 两端) 相邻的棱都胀, 拼成一个闭合的壳; 不和别的覆盖面相邻的棱不胀 (免得伸到别的零件上)。法线轴上的两个标志必须是 0。
// growProblems 核对这条规矩: 胀的边必须正好接上同组另一个 (也往回胀的) 覆盖面, 不胀的边不许和同组的覆盖面相接 (会留缝)。
// 只盖露在外面的部分: rail_b 北面顶上一行 (y 10..10.5) 是钢; can_strip 的顶面 x 1..8 被弹药箱压着, 只盖露出来的 x 0.5..1。
// 所以 can_strip 北面切两段: x 0.5..1 (上面是露出来的顶面) 往上胀接 can_u; x 1..8 上面是弹药箱的正面 (z 1 与灯带北面齐平), 不往上胀,
// 否则 1/8 px 的灯色会画到箱身上 (东端面同理: 箱身东面在 x 8 与它齐平, 只往前胀接北面)。
const G = (key, el, face, lo, hi, light, grow, o = {}) => ({ key, el, face, lo, hi, light, grow, shade: o.shade !== false, lift: o.lift != null ? o.lift : LIFT_PX });
export const TARGETS = [
    G('strip_n', 'strip', 'north', [8.5, 7, 0], [31.5, 7.5, 0], 15, [0, 0, 0, 1, 0, 0]),      // 台面前沿通长灯带
    G('strip_u', 'strip', 'up', [8.5, 7.5, 0], [31.5, 7.5, 0.5], 15, [0, 0, 0, 0, 1, 0]),
    G('rail_b_n', 'rail_b', 'north', [8, 9, 9.5], [31, 10, 9.5], 11, [0, 0, 0, 0, 0, 0]),   // 皮带后护栏背光 (两行灯)
    G('crown_n', 'crown_light', 'north', [12, 19, 3.25], [17, 19.5, 3.25], 14, [1, 1, 0, 1, 0, 0]),   // 压机横梁灯条
    G('crown_u', 'crown_light', 'up', [12, 19.5, 3.25], [17, 19.5, 3.75], 14, [1, 1, 0, 0, 1, 0]),
    G('crown_w', 'crown_light', 'west', [12, 19, 3.25], [12, 19.5, 3.75], 14, [0, 0, 0, 1, 1, 0]),
    G('crown_e', 'crown_light', 'east', [17, 19, 3.25], [17, 19.5, 3.75], 14, [0, 0, 0, 1, 1, 0]),
    G('can_n_open', 'can_strip', 'north', [0.5, 2.5, 1], [1, 3, 1], 15, [1, 0, 0, 1, 0, 0]),   // 弹药箱下货架前沿灯带: 顶面露出来的那一小段
    G('can_n_under', 'can_strip', 'north', [1, 2.5, 1], [8, 3, 1], 15, [0, 1, 0, 0, 0, 0]),   //   压在弹药箱正面下的那一段
    G('can_u', 'can_strip', 'up', [0.5, 3, 1], [1, 3, 1.5], 15, [1, 0, 0, 0, 1, 0]),
    G('can_w', 'can_strip', 'west', [0.5, 2.5, 1], [0.5, 3, 1.5], 15, [0, 0, 0, 1, 1, 0]),
    G('can_e', 'can_strip', 'east', [8, 2.5, 1], [8, 3, 1.5], 15, [0, 0, 0, 0, 1, 0]),
    G('gem_n', 'gem', 'north', [14, 23, 7], [15, 24, 7], 15, [1, 1, 0, 1, 0, 0], { shade: false, lift: GEM_LIFT_PX }),   // 闪耀: 压机缸顶宝石
    G('gem_s', 'gem', 'south', [14, 23, 8], [15, 24, 8], 15, [1, 1, 0, 1, 0, 0], { shade: false, lift: GEM_LIFT_PX }),
    G('gem_e', 'gem', 'east', [15, 23, 7], [15, 24, 8], 15, [0, 0, 0, 1, 1, 1], { shade: false, lift: GEM_LIFT_PX }),
    G('gem_w', 'gem', 'west', [14, 23, 7], [14, 24, 8], 15, [0, 0, 0, 1, 1, 1], { shade: false, lift: GEM_LIFT_PX }),
    G('gem_u', 'gem', 'up', [14, 24, 7], [15, 24, 8], 15, [1, 1, 0, 0, 1, 1], { shade: false, lift: GEM_LIFT_PX }),
];
export const TI = Object.fromEntries(TARGETS.map((t, i) => [t.key, i]));
/** 目标组 (下标 = Java 的 GROUP_*, LIGHT_GROUPS 的行)。 */
export const GROUP_NAMES = ['strip', 'rail', 'crown', 'can', 'gem'];
export const GROUPS = {
    strip: [TI.strip_n, TI.strip_u],
    rail: [TI.rail_b_n],
    crown: [TI.crown_n, TI.crown_u, TI.crown_w, TI.crown_e],
    can: [TI.can_n_open, TI.can_n_under, TI.can_u, TI.can_w, TI.can_e],
    gem: [TI.gem_n, TI.gem_s, TI.gem_e, TI.gem_w, TI.gem_u],
};

// ---------------------------------------------------------------- 效果 (下标 = Java 的 EFFECT_* = 画的顺序; 掩码位 = 1 << 下标)
// unlock = 从哪一档起有 (普通 0 .. 闪耀 5); idle = 待机时才画 (满仓), 其余只在工作时画。
export const EFFECTS = [
    { id: 'chase', name: '皮带追光', unlock: 3, group: 'rail' },
    { id: 'breath', name: '运行呼吸', unlock: 2, group: 'strip' },
    { id: 'leds', name: '工位指示灯', unlock: 0, group: 'strip' },
    { id: 'strike', name: '冲压闪光', unlock: 0, group: 'crown' },
    { id: 'drop', name: '落箱脉冲', unlock: 0, group: 'can' },
    { id: 'full', name: '满仓提示', unlock: 0, group: 'can', idle: true },
    { id: 'gem', name: '宝石脉冲', unlock: 5, group: 'gem' },
];
export const EI = Object.fromEntries(EFFECTS.map((e, i) => [e.id, i]));
/** 这一档有的效果 (掩码, MunitionsBenchLights.effectMask): 档位越界按普通档。 */
export function effectMask(tier) {
    const t = tier >= 0 && tier < TIERS.length ? tier : 0;
    let m = 0;
    EFFECTS.forEach((e, i) => { if (t >= e.unlock) m |= 1 << i; });
    return m;
}

// ---------------------------------------------------------------- 颜色 (每档一行, Java 里是生成器写出的 LIGHT_COLOURS[档][角色])
export const MIX = (a, b, t) => [0, 1, 2].map((i) => Math.round(a[i] + (b[i] - a[i]) * t));
export const WHITE = [255, 255, 255];
export const AMBER = [255, 178, 52];          // = counter.mjs AMBER.on (满仓的满度条), 与六档灯色都分得开
/** 颜色角色 (下标 = Java 的 ROLE_*)。 */
export const PALETTE_ROLES = ['led', 'flashLo', 'pulse', 'amber', 'gemFlash', 'gemEcho', 'breathHi', 'head', 'trough'];
/**
 * 一档的灯效颜色。roles = tiers.mjs 的 TIERS[i].roles, tier = 下标。led 工位灯; flashLo 冲压闪光回落到的颜色 (峰值是纯白);
 * pulse 落箱脉冲; amber 满仓; gemFlash / gemEcho 宝石 (冲压白闪 / 落箱回响; 闪耀的回响是饰带金, α 0.8 才读得出金色);
 * breathHi 呼吸; head / trough 追光头 / 暗槽 (闪耀的头是金色)。
 */
export function lightPalette(roles, tier) {
    const L = roles.light, B = roles.band, rad = tier === 5;
    return {
        led: MIX(L.hi, WHITE, 0.45),
        flashLo: L.hi,
        pulse: MIX(L.hi, WHITE, 0.5),
        amber: AMBER,
        gemFlash: MIX(L.hi, WHITE, 0.7), gemEcho: rad ? B.hi : L.hi,
        breathHi: L.hi,
        head: rad ? MIX(B.hi, WHITE, 0.35) : MIX(L.hi, WHITE, 0.25),
        trough: MIX(L.on, L.dim, 0.9),
    };
}
/** 每档一行 [角色] 的 0xRRGGBB (MunitionsBenchGeometry.LIGHT_COLOURS)。 */
export function colourTable(tiers = TIERS) {
    return tiers.map((T, t) => { const p = lightPalette(T.roles, t); return PALETTE_ROLES.map((r) => (p[r][0] << 16) | (p[r][1] << 8) | p[r][2]); });
}
const COLOURS = colourTable(TIERS);
/** 某档的颜色 {角色: [r, g, b]} (Java 取 LIGHT_COLOURS[档] 那一行; 档位越界按普通档)。 */
export function paletteOf(tier) {
    const row = COLOURS[tier >= 0 && tier < COLOURS.length ? tier : 0];
    return Object.fromEntries(PALETTE_ROLES.map((r, i) => [r, [(row[i] >> 16) & 255, (row[i] >> 8) & 255, row[i] & 255]]));
}

// ---------------------------------------------------------------- 曲线
/**
 * t 折回 [0, p) (MunitionsBenchLights.wrap): % (fmod) 是精确的, 非负原样取余, 负数再加一个 p。不写成 ((t % p) + p) % p: 按档位映射过的
 * 程序时间有满 53 位尾数, 先加 p 会舍掉末位, 第一轮开工时脉冲的 d 比 elapsed 大一个 ulp, 底火灯 (峰 0) 闪断 (中级档的 GameTest 抓到的)。
 */
export const wrap = (t, p) => { const r = t % p; return r < 0 ? r + p : r; };
const clamp01 = (v) => (v < 0 ? 0 : v > 1 ? 1 : v);
/** smoothstep(a, b, t) (a < b)。 */
export const ramp = (t, a, b) => { const k = clamp01((t - a) / (b - a)); return k * k * (3 - 2 * k); };
/**
 * 一次脉冲, 峰在 peak: 前 attack tick 二次缓入 ((1 + d/attack)²), 后 decay tick 二次衰减 ((1 - d/decay)²); 相位按 CYCLE_TICKS 折回。
 * elapsed = 程序时间: 峰已经过去 d tick, 但程序才跑了不到 d tick (第一轮开工时跨接缝的尾巴) → 这一拍没发生过, 不画。
 */
export function pulse(t, peak, attack, decay, elapsed = Infinity) {
    const d = wrap(t - peak, CYCLE_TICKS);
    if (d <= decay) { if (d > elapsed) return 0; const k = 1 - d / decay; return k * k; }
    const e = d - CYCLE_TICKS;
    if (attack > 0 && e >= -attack) { const k = 1 + e / attack; return k * k; }
    return 0;
}
/** 呼吸的距离渐隐: FADE_START_BLOCKS 格起淡出, MAX_DISTANCE_BLOCKS 格为 0 (渲染器在 24 格外本来就不画)。distance 缺省 = 近处。 */
export const distanceFade = (d) => (d == null ? 1 : 1 - ramp(d, FADE_START_BLOCKS, MAX_DISTANCE_BLOCKS));

// ---------------------------------------------------------------- 一帧的输入 (MunitionsBenchLights.compute 的开头)
const floorMod = (a, m) => ((a % m) + m) % m;
/**
 * 渲染器给的输入 → 各时钟。inp: {tier (档位 0..5, 必须给: 定循环长度, 也记在返回的帧上给 lightOverlays 定效果与颜色; 越界的整数按普通档),
 * active, full, elapsedTicks (整 tick, 程序起点起; 待机时不用), gameTime (整 tick), partialTick, distance};
 * program = parseProgramJava / 生成器的帧表 (取 beltX 与 cycleByTier, 与 MunitionsBenchProgram 同一张表)。
 * 起点比本地时钟快 (elapsed < 0) 时停在首帧 (与运动件相同: sample(0))。
 * partialTick 在 Java 里是 float: 先 Math.fround 成同一个 float, 各时钟 = 整 tick + 它, 在 double 里精确 (Java 同样在 double 里加;
 * 循环 tick 若按 float 加会比 elapsed 大一个 ulp, 第一轮的底火灯就被 d ≤ elapsed 误判成没发生过); 程序时间再 × CYCLE_TICKS / 该档循环,
 * cycleTick 与 elapsed 按同一个顺序算, 第一轮里两者逐位相同。皮带取样与运动件一样用 float 的程序时间 (Java 的 (float) cycle)。
 * Java 的 compute(frame, tier, ..) 一次调用只有一个档位, 所以档位只在这里给一次 (帧上的 tier), 不许时间按一档、颜色按另一档。
 */
export function lightFrame(inp, program) {
    const active = !!inp.active;
    const partial = Math.fround(inp.partialTick || 0);
    const tier = requireTier(inp.tier, 'lightFrame');
    let e = active ? inp.elapsedTicks : 0, p = partial;
    if (e < 0) { e = 0; p = 0; }
    const cycleTick = programTick(program, e, p, tier);
    return {
        tier, active, full: !!inp.full,
        cycleTick, elapsed: (e + p) * program.cycleTicks / cycleTicksOf(program, tier),
        breathTick: floorMod(e, BREATH_PERIOD_TICKS) + p,
        clockTick: floorMod(inp.gameTime || 0, FULL_BLINK_PERIOD_TICKS) + partial,
        beltX: active ? sampleProgram(program, Math.fround(cycleTick)).beltX : 0,
        distance: inp.distance,
    };
}

/**
 * 各效果此刻的标量 (0..1; breath 是呼吸层的 α)。s = lightFrame(...) (预览可以直接给 {active, full, cycleTick, elapsed?, breathTick, clockTick})。
 */
export function levels(s) {
    const t = s.cycleTick, el = s.elapsed == null ? Infinity : s.elapsed;
    const out = { leds: [0, 0, 0, 0, 0], strike: 0, drop: 0, full: 0, gem: 0, gemEcho: 0, breath: 0, chase: 0 };
    if (s.active) {
        for (let i = 0; i < LEDS.length; i++) out.leds[i] = pulse(t, LEDS[i].peak, LED_PULSE.attack, LED_PULSE.decay, el);
        out.strike = pulse(t, STRIKE_PULSE.peak, STRIKE_PULSE.attack, STRIKE_PULSE.decay, el);
        out.drop = pulse(t, DROP_PULSE.peak, DROP_PULSE.attack, DROP_PULSE.decay, el);
        out.gem = pulse(t, GEM_PULSE.peak, GEM_PULSE.attack, GEM_PULSE.decay, el);
        out.gemEcho = GEM_ECHO_LEVEL * pulse(t, GEM_ECHO_PULSE.peak, GEM_ECHO_PULSE.attack, GEM_ECHO_PULSE.decay, el);
        // 呼吸: 起点最亮, α BREATH_ALPHA[0]..[1] 往 hi 拉 (只往亮的一侧)
        out.breath = BREATH_ALPHA[0] + (BREATH_ALPHA[1] - BREATH_ALPHA[0]) * (0.5 + 0.5 * Math.cos(2 * Math.PI * s.breathTick / BREATH_PERIOD_TICKS));
        // 追光: 只在皮带步进时亮, 前后淡入淡出
        out.chase = ramp(t, CHASE_WINDOW[0], CHASE_WINDOW[1]) * (1 - ramp(t, CHASE_WINDOW[2], CHASE_WINDOW[3]));
    } else if (s.full) {
        // 满仓: 梯形闪
        const c = wrap(s.clockTick, FULL_BLINK_PERIOD_TICKS);
        out.full = ramp(c, FULL_BLINK_RAMP[0], FULL_BLINK_RAMP[1]) * (1 - ramp(c, FULL_BLINK_RAMP[2], FULL_BLINK_RAMP[3]));
    }
    return out;
}

// ---------------------------------------------------------------- 矩形 (MunitionsBenchLights.Frame 的一条)
// {effect (EFFECTS 下标), target (TARGETS 下标), lo, hi (整台像素, 胀之前), grad (渐变轴 0 x / 1 y / 2 z, -1 = 纯色), c0, c1 ([r, g, b, a], rgb 0..255 整数)}
const rgba = (rgb, a) => [rgb[0], rgb[1], rgb[2], a];
function push(out, effect, target, c0, c1 = c0, grad = -1, lo = null, hi = null) {
    const T = TARGETS[target];
    out.push({ effect, target, lo: lo || T.lo.slice(), hi: hi || T.hi.slice(), grad, c0, c1 });
}
function group(out, effect, g, c) { for (const t of GROUPS[g]) push(out, effect, t, c); }
/**
 * 前沿灯带 (北面 + 顶面): 呼吸 α = b (0 = 关), 工位灯 α = leds[i] (0 = 关)。按 x 从小到大走, 亮着的工位灯各切一段, 其余是呼吸段 ——
 * 每一点只被一个四边形盖住 (textBackground 排序 + 写深度, 叠两层会出鬼影)。工位灯那段 = 呼吸再盖工位灯, 在 CPU 上先合成:
 *   A = 1 − (1 − b)(1 − I),  rgb = (I·led + (1 − I)·b·breathHi) / A。
 */
function stripRects(out, b, leds, pal) {
    const S = TARGETS[TI.strip_n], X0 = S.lo[0], X1 = S.hi[0];
    const seg = (effect, x0, x1, c) => {
        for (const t of GROUPS.strip) { const T = TARGETS[t]; push(out, effect, t, c, c, -1, [x0, T.lo[1], T.lo[2]], [x1, T.hi[1], T.hi[2]]); }
    };
    let x = X0;
    for (let i = 0; i < LEDS.length; i++) {
        const I = leds[i], A = 1 - (1 - b) * (1 - I);
        if (I < LED_MERGE_EPS || A < ALPHA_CUTOFF) continue;
        const x0 = LEDS[i].x - LED_WIDTH_PX / 2, x1 = LEDS[i].x + LED_WIDTH_PX / 2;
        if (b > 0 && x0 > x) seg(EI.breath, x, x0, rgba(pal.breathHi, b));
        seg(EI.leds, x0, x1, rgba([0, 1, 2].map((k) => Math.round((I * pal.led[k] + (1 - I) * b * pal.breathHi[k]) / A)), A));
        x = x1;
    }
    if (b > 0 && X1 > x) seg(EI.breath, x, X1, rgba(pal.breathHi, b));
}
/**
 * 追光: rail_b 北面发光两行上的彗星 (头在 -x 端, 尾往 +x 渐暗), 周期 = 节距, 与皮带同速 (CHASE_SPEED)。
 * 头与暗槽的 α 都要 ≥ ALPHA_CUTOFF 才画 (彗尾是头 → 暗槽的顶点色渐变, 一端低于 0.1 就会被 discard 截掉半截), 所以按暗槽 (较小的那个) 判。
 */
function chaseRects(out, env, beltX, pal) {
    const T = TARGETS[TI.rail_b_n], X0 = T.lo[0], X1 = T.hi[0], P = BELT_PITCH, tail = CHASE_TAIL_PX;
    const aH = CHASE_ALPHA[0] * env, aT = CHASE_ALPHA[1] * env;
    if (aT < ALPHA_CUTOFF) return;
    const cH = rgba(pal.head, aH), cT = rgba(pal.trough, aT);
    const lerp4 = (a, b, k) => [0, 1, 2, 3].map((i) => (i < 3 ? Math.round(a[i] + (b[i] - a[i]) * k) : a[i] + (b[i] - a[i]) * k));
    const shift = CHASE_SPEED * beltX;                       // beltX 0 .. -BELT_PITCH, 图案往 -x 走
    const first = X0 + wrap(shift, P) - P;                   // 第一颗彗星的头 (≤ X0)
    for (let h = first; h < X1; h += P) {
        const g0 = Math.max(h, X0), g1 = Math.min(h + tail, X1);
        if (g1 > g0 + 1e-6) push(out, EI.chase, TI.rail_b_n, lerp4(cH, cT, (g0 - h) / tail), lerp4(cH, cT, (g1 - h) / tail), 0, [g0, T.lo[1], T.lo[2]], [g1, T.hi[1], T.hi[2]]);
        const t0 = Math.max(h + tail, X0), t1 = Math.min(h + P, X1);
        if (t1 > t0 + 1e-6) push(out, EI.chase, TI.rail_b_n, cT, cT, -1, [t0, T.lo[1], T.lo[2]], [t1, T.hi[1], T.hi[2]]);
    }
}
/**
 * 此刻要画的覆盖层 (MunitionsBenchLights.compute 的输出)。s = lightFrame(...); 档位 (决定有哪些效果与颜色) 取帧上的 s.tier,
 * 与时间映射同一个 (手搭的 s 没有 tier 时第二个参数给; 两个都给就必须相同, 否则报错)。
 * 顺序固定 (Java 同序, 对拍按顺序比; 游戏里上传时还会再按距离排序, 因为互不重叠, 顺序不影响画面):
 *   追光 → 前沿灯带 (呼吸 + 工位灯) → 冲压 → 落箱 → 满仓 → 宝石。
 */
export function lightOverlays(s, tier = s.tier) {
    requireTier(tier, 'lightOverlays');
    if (s.tier != null && s.tier !== tier) throw new Error(`lightOverlays: tier ${tier} differs from the frame's tier ${s.tier} (Java times and colours one tier per compute)`);
    const L = levels(s), out = [], mask = effectMask(tier), pal = paletteOf(tier);
    const on = (id) => (mask & (1 << EI[id])) !== 0;
    if (on('chase') && L.chase > 0) chaseRects(out, L.chase, s.beltX || 0, pal);
    {
        let b = on('breath') ? L.breath * distanceFade(s.distance) : 0;
        if (b < ALPHA_CUTOFF) b = 0;
        const leds = on('leds') ? L.leds : [0, 0, 0, 0, 0];
        stripRects(out, b, leds, pal);
    }
    if (on('strike')) {
        const a = Math.sqrt(L.strike);                        // = 6 tick 线性衰减
        if (a >= ALPHA_CUTOFF) group(out, EI.strike, 'crown', rgba(MIX(pal.flashLo, WHITE, L.strike), a));
    }
    if (on('drop') && DROP_ALPHA * L.drop >= ALPHA_CUTOFF) group(out, EI.drop, 'can', rgba(pal.pulse, DROP_ALPHA * L.drop));
    if (on('full') && FULL_ALPHA * L.full >= ALPHA_CUTOFF) group(out, EI.full, 'can', rgba(pal.amber, FULL_ALPHA * L.full));
    if (on('gem')) {
        const flash = L.gem >= L.gemEcho, g = flash ? L.gem : L.gemEcho;
        if (g >= ALPHA_CUTOFF) group(out, EI.gem, 'gem', rgba(flash ? pal.gemFlash : pal.gemEcho, g));
    }
    return out;
}

// ---------------------------------------------------------------- 角 (整台像素, 朝北) 与朝向
export const FACE_NORMALS = { north: [0, 0, -1], south: [0, 0, 1], east: [1, 0, 0], west: [-1, 0, 0], up: [0, 1, 0], down: [0, -1, 0] };
export const FACE_AXIS = { north: 2, south: 2, east: 0, west: 0, up: 1, down: 1 };
/** 面的编号 = 原版 Direction.get3DDataValue() (Java 的 FACE_*; 渲染器用 Direction.from3DDataValue 取回)。 */
export const FACE_CODES = { down: 0, up: 1, north: 2, south: 3, west: 4, east: 5 };
export const FACE_BY_CODE = Object.fromEntries(Object.entries(FACE_CODES).map(([k, v]) => [v, k]));
/**
 * 一条矩形的四个角: 面内按目标的 grow 往外胀 lift, 再沿面外法线浮出 lift; 顺序 = 从面外看逆时针 (绕序法线 = 面外法线; textBackground
 * 剔除背面, 顺序反了整块消失), 每个角带颜色 (grad 轴上靠 lo 那一半取 c0, 靠 hi 那一半取 c1)。MunitionsBenchLights.benchCorners 的一条:
 *   north: (x1,y1) (x1,y0) (x0,y0) (x0,y1)   south: (x0,y1) (x0,y0) (x1,y0) (x1,y1)
 *   east:  (z1,y1) (z1,y0) (z0,y0) (z0,y1)   west:  (z0,y1) (z0,y0) (z1,y0) (z1,y1)
 *   up:    (x0,z0) (x0,z1) (x1,z1) (x1,z0)   down:  (x0,z1) (x0,z0) (x1,z0) (x1,z1)
 * 返回 {pts: [[x,y,z] x 4], cols: [[r,g,b,a] x 4], normal}。o 也可以是 {face, lo, hi, grow, lift, grad, c0, c1} (自检用)。
 */
export function overlayCorners(o) {
    const T = o.target != null ? TARGETS[o.target] : o;
    const face = T.face, lift = T.lift, g = T.grow || [0, 0, 0, 0, 0, 0], n = FACE_NORMALS[face];
    const lo = o.lo.map((v, a) => v - g[2 * a] * lift), hi = o.hi.map((v, a) => v + g[2 * a + 1] * lift);
    const [x0, y0, z0] = lo, [x1, y1, z1] = hi;
    let pts;
    switch (face) {
        case 'north': pts = [[x1, y1, z0], [x1, y0, z0], [x0, y0, z0], [x0, y1, z0]]; break;
        case 'south': pts = [[x0, y1, z1], [x0, y0, z1], [x1, y0, z1], [x1, y1, z1]]; break;
        case 'east': pts = [[x1, y1, z1], [x1, y0, z1], [x1, y0, z0], [x1, y1, z0]]; break;
        case 'west': pts = [[x0, y1, z0], [x0, y0, z0], [x0, y0, z1], [x0, y1, z1]]; break;
        case 'up': pts = [[x0, y1, z0], [x0, y1, z1], [x1, y1, z1], [x1, y1, z0]]; break;
        case 'down': pts = [[x0, y0, z1], [x0, y0, z0], [x1, y0, z0], [x1, y0, z1]]; break;
        default: throw new Error('bad face ' + face);
    }
    const mid = o.grad < 0 ? 0 : (lo[o.grad] + hi[o.grad]) / 2;
    const cols = pts.map((p) => (o.grad < 0 || p[o.grad] <= mid ? o.c0 : o.c1));
    pts = pts.map((p) => [p[0] + n[0] * lift, p[1] + n[1] * lift, p[2] + n[2] * lift]);
    return { pts, cols, normal: n };
}
/** 覆盖层的最高点 (px, 整台): 每个目标整块画时 (胀过、浮出后) 角的最大 y = 闪耀宝石顶面 24 + 1/32。生成器据此抬高 RENDER_TOP_PX。 */
export function overlayTopPx() {
    const c0 = [0, 0, 0, 1];
    return Math.max(...TARGETS.map((T, t) => Math.max(...overlayCorners({ target: t, lo: T.lo, hi: T.hi, grad: -1, c0, c1: c0 }).pts.map((p) => p[1]))));
}
/** 摆进主格 (方块坐标, MunitionsBenchLights.blockCorners): 每条矩形的四个角过一遍计数屏同一个 benchToBlock。 */
export function blockCorners(rects, yRot) {
    return rects.map((o) => overlayCorners(o).pts.map((p) => benchToBlock(p, yRot)));
}
/** 面朝向转到世界 (MunitionsBenchLights.worldFace): 水平面按朝向角俯视顺时针转 round(-yRot / 90) 个 90° (北 → 东 → 南 → 西), 上下不变。 */
export function worldFace(face, yRot) {
    const ring = ['north', 'east', 'south', 'west'];
    const i = ring.indexOf(face);
    if (i < 0) return face;
    return ring[floorMod(i + Math.round(-yRot / 90), 4)];
}

// ---------------------------------------------------------------- 自检
/** 顶点顺序自检 (六个面): 绕序法线必须与面外法线相同。 */
export function windingProblems() {
    const out = [];
    const box = { north: [[0, 0, 0], [2, 1, 0]], south: [[0, 0, 1], [2, 1, 1]], east: [[1, 0, 0], [1, 1, 2]], west: [[0, 0, 0], [0, 1, 2]], up: [[0, 1, 0], [2, 1, 1]], down: [[0, 0, 0], [2, 0, 1]] };
    for (const [face, [lo, hi]] of Object.entries(box)) {
        const { pts, normal } = overlayCorners({ face, lo, hi, lift: 0, grow: null, grad: -1, c0: [0, 0, 0, 1], c1: [0, 0, 0, 1] });
        const a = pts[1].map((v, i) => v - pts[0][i]), b = pts[2].map((v, i) => v - pts[0][i]);
        const c = [a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0]];
        const L = Math.hypot(...c);
        if (!(L > 0) || c.some((v, i) => Math.abs(v / L - normal[i]) > 1e-9)) out.push(`${face}: winding ${c.map((v) => (v / L).toFixed(2))} != normal ${normal}`);
    }
    return out;
}
/**
 * 壳的规矩 (TARGETS 上面的注释): 每个目标面内的四条边里, 胀 (grow = 1) 的边必须正好接上同组的另一个覆盖面 —— 它朝这条边往外的方向
 * (x1 边接东面、y1 边接顶面 ..)、就在这条边的平面上、从本面的平面往元素里伸、沿边的方向整段盖住这条边, 并且自己也往回胀 (两边各胀 lift,
 * 在棱外 lift 处相接成闭合的壳); 不胀的边不许和同组的覆盖面相接 (相接处会漏出一道 lift 宽的静态色缝)。
 * 于是胀的边不会伸到别的零件上 (弹药箱压着的 can_strip 北面那段不许往上胀)。与模型无关, 只看 TARGETS / GROUPS。
 */
export function growProblems() {
    const P = [];
    const SIGN = { north: -1, south: 1, west: -1, east: 1, down: -1, up: 1 };
    const FACE_OF = { '0,1': 'east', '0,-1': 'west', '1,1': 'up', '1,-1': 'down', '2,1': 'south', '2,-1': 'north' };
    const near = (a, b) => Math.abs(a - b) < 1e-6;
    TARGETS.forEach((T, i) => {
        const ax = FACE_AXIS[T.face], s = SIGN[T.face], plane = T.lo[ax];
        const group = GROUP_NAMES.find((n) => GROUPS[n].includes(i));
        if (!group) return;   // targetProblems 另报
        for (const a of [0, 1, 2]) {
            if (a === ax) continue;
            const b = 3 - ax - a;
            for (const side of [0, 1]) {
                const c = side ? T.hi[a] : T.lo[a], want = FACE_OF[`${a},${side ? 1 : -1}`];
                const touching = GROUPS[group].filter((j) => j !== i).map((j) => TARGETS[j]).filter((U) => U.face === want && near(U.lo[a], c)
                    && near(s < 0 ? U.lo[ax] : U.hi[ax], plane) && Math.min(U.hi[b], T.hi[b]) - Math.max(U.lo[b], T.lo[b]) > 1e-6);
                const edge = `${T.key} edge ${'xyz'[a]}${side} (${'xyz'[a]} = ${c})`;
                if (T.grow[2 * a + side]) {
                    const closes = touching.some((U) => U.lo[b] <= T.lo[b] + 1e-6 && U.hi[b] >= T.hi[b] - 1e-6 && U.grow[2 * ax + (s < 0 ? 0 : 1)]);
                    if (!closes) P.push(`${edge}: grows, but no ${want} overlay of group ${group} growing back closes the shell there (the lift would spill onto the next part)`);
                } else if (touching.length) {
                    P.push(`${edge}: meets ${touching.map((U) => U.key).join(', ')} but does not grow (leaves a lift-wide seam of static colour)`);
                }
            }
        }
    });
    return P;
}
/** 同一帧的覆盖四边形在面上两两不重叠 (textBackground 排序 + 写深度的前提)。返回问题列表。 */
export function overlapProblems(rects) {
    const out = [];
    for (let i = 0; i < rects.length; i++) for (let j = i + 1; j < rects.length; j++) {
        const a = rects[i], b = rects[j], fa = TARGETS[a.target].face, fb = TARGETS[b.target].face;
        if (fa !== fb) continue;
        const ax = FACE_AXIS[fa];
        if (Math.abs(a.lo[ax] - b.lo[ax]) > 1e-6) continue;
        const ov = [0, 1, 2].filter((k) => k !== ax).every((k) => Math.min(a.hi[k], b.hi[k]) - Math.max(a.lo[k], b.lo[k]) > 1e-6);
        if (ov) out.push(`${EFFECTS[a.effect].id}:${TARGETS[a.target].key} overlaps ${EFFECTS[b.effect].id}:${TARGETS[b.target].key}`);
    }
    return out;
}

// ---------------------------------------------------------------- 前提核对 (生成器按刚生成的 JSON 调; 预览 / 对拍按仓库里的 JSON 调)
/**
 * 目标面核对。models[tier] = {active: 元素, idle: 元素}, 元素 = 静态 JSON 的 element 平移到整台坐标:
 *   {name (去掉 ~main / ~extension), from, to, faces (JSON faces), shade (bool), rotated (bool)}。
 * 每档只核对这一档会用到的目标 (宝石只在闪耀), 工作态查全部效果, 待机态只查待机效果 (满仓) 的目标; 自发光等级只在工作态查。
 */
export function targetProblems(models) {
    const P = [...windingProblems(), ...growProblems()];
    const SIDE = { north: [2, 'from'], south: [2, 'to'], west: [0, 'from'], east: [0, 'to'], down: [1, 'from'], up: [1, 'to'] };
    const lightOf = (e, face) => { const f = e.faces[face]; return (f && f.forge_data && f.forge_data.block_light) || 0; };
    TARGETS.forEach((T, i) => {
        const g = T.grow, [ax] = SIDE[T.face];
        if (g.length !== 6 || g[2 * ax] || g[2 * ax + 1]) P.push(`${T.key}: grow on the normal axis`);
        if (Math.abs(T.lo[ax] - T.hi[ax]) > 1e-9) P.push(`${T.key}: lo / hi differ on the normal axis`);
        if (!(T.lift > 0)) P.push(`${T.key}: lift must be > 0`);
        if (!GROUP_NAMES.some((n) => GROUPS[n].includes(i))) P.push(`${T.key}: in no group`);
    });
    for (let tier = 0; tier < models.length; tier++) for (const state of ['active', 'idle']) {
        const els = models[tier][state];
        const used = new Set();
        EFFECTS.forEach((e) => { if (tier >= e.unlock && (state === 'idle') === !!e.idle) for (const t of GROUPS[e.group]) used.add(t); });
        for (const t of used) {
            const T = TARGETS[t], [ax, side] = SIDE[T.face];
            const cands = els.filter((e) => e.name === T.el && !e.rotated && e.faces[T.face] && Math.abs(e[side][ax] - T.lo[ax]) < 1e-6);
            const where = `tier ${tier} ${state} ${T.key}`;
            if (!cands.length) { P.push(`${where}: no ${T.el}.${T.face} at ${'xyz'[ax]} = ${T.lo[ax]}`); continue; }
            const [a0, a1] = [0, 1, 2].filter((a) => a !== ax);
            // 沿第一根面内轴: 各段首尾相接、盖住 [lo, hi]; 第二根面内轴: 每段都盖住
            const segs = cands.map((e) => [e.from[a0], e.to[a0]]).sort((p, q) => p[0] - q[0]);
            let reach = T.lo[a0];
            for (const [s0, s1] of segs) if (s0 <= reach + 1e-6 && s1 > reach) reach = s1;
            const inside = reach >= T.hi[a0] - 1e-6 && segs[0][0] <= T.lo[a0] + 1e-6
                && cands.every((e) => T.lo[a1] >= e.from[a1] - 1e-6 && T.hi[a1] <= e.to[a1] + 1e-6);
            if (!inside) P.push(`${where}: [${T.lo}]-[${T.hi}] not inside ${T.el}.${T.face}`);
            for (const e of cands) {
                if (e.shade !== T.shade) P.push(`${where}: element shade ${e.shade} but target shade ${T.shade}`);
                if (state === 'active' && lightOf(e, T.face) !== T.light) P.push(`${where}: ${T.el}.${T.face} block_light ${lightOf(e, T.face)} != target light ${T.light}`);
            }
            // grow: 胀的边必须在元素盒子的棱上 (不在面中间伸出去)
            for (const a of [a0, a1]) {
                const eLo = Math.min(...cands.map((e) => e.from[a])), eHi = Math.max(...cands.map((e) => e.to[a]));
                if (T.grow[2 * a] && Math.abs(T.lo[a] - eLo) > 1e-6) P.push(`${where}: grows ${'xyz'[a]}0 but ${T.lo[a]} is not the element edge ${eLo}`);
                if (T.grow[2 * a + 1] && Math.abs(T.hi[a] - eHi) > 1e-6) P.push(`${where}: grows ${'xyz'[a]}1 but ${T.hi[a]} is not the element edge ${eHi}`);
            }
        }
    }
    // 工位灯: 按 x 排序、每段都在前沿灯带里、互不重叠
    const S = TARGETS[TI.strip_n];
    for (let i = 0; i < LEDS.length; i++) {
        const x0 = LEDS[i].x - LED_WIDTH_PX / 2, x1 = LEDS[i].x + LED_WIDTH_PX / 2;
        if (x0 < S.lo[0] - 1e-9 || x1 > S.hi[0] + 1e-9) P.push(`LED ${LEDS[i].name} leaves the front strip`);
        if (i > 0 && !(x0 >= LEDS[i - 1].x + LED_WIDTH_PX / 2 - 1e-9)) P.push('LEDS must be sorted by x and must not overlap (strip segmentation walks them in order)');
    }
    return P;
}
/** JSON 模型 (两格) → targetProblems 的元素表 (整台坐标)。cells = [{model (JSON), offset [x, y, z]}]。 */
export function elementsOf(cells) {
    const out = [];
    for (const { model, offset } of cells) for (const e of model.elements) {
        out.push({
            name: String(e.name || '').replace(/~.*$/, ''), from: e.from.map((v, i) => v + offset[i]), to: e.to.map((v, i) => v + offset[i]),
            faces: e.faces || {}, shade: e.shade !== false, rotated: !!(e.rotation && e.rotation.angle),
        });
    }
    return out;
}
/** 时间前提: 与关键帧表 (prog = {cycleTicks, strikeTick, pitch, rows}) 对得上。 */
export function programProblems(prog) {
    const P = [];
    if (prog.cycleTicks !== CYCLE_TICKS) P.push(`CYCLE_TICKS ${prog.cycleTicks} != ${CYCLE_TICKS}`);
    if (prog.strikeTick !== STRIKE_TICK) P.push(`STRIKE_TICK ${prog.strikeTick} != ${STRIKE_TICK}`);
    if (prog.pitch !== BELT_PITCH) P.push(`BELT_PITCH ${prog.pitch} != ${BELT_PITCH}`);
    // 工位灯的峰 = 该列第一次到最小值 (动作到底) 的关键帧; 入口灯在出弹落定之后、接缝 (新壳落进入口位) 之前
    const rows = prog.rows.slice(0, -1);
    const firstMin = (k) => { const m = Math.min(...rows.map((r) => r[k])); return rows.find((r) => r[k] === m).tick; };
    for (const led of LEDS) {
        if (led.key) { const t = firstMin(led.key); if (t !== led.peak) P.push(`LED ${led.name}: ${led.key} bottoms out at t ${t}, peak is ${led.peak}`); }
        else if (!(led.peak > DROP_LAND_TICK && led.peak < CYCLE_TICKS)) P.push(`LED ${led.name}: feed peak ${led.peak} not between drop landing and the seam`);
    }
    if (firstMin('ramY') !== STRIKE_TICK) P.push(`ram bottoms out at t ${firstMin('ramY')} != STRIKE_TICK`);
    if (firstMin('dropY') !== DROP_LAND_TICK) P.push(`DROP_LAND_TICK: dropY bottoms out at t ${firstMin('dropY')}`);
    if (STRIKE_PULSE.peak !== STRIKE_TICK || GEM_PULSE.peak !== STRIKE_TICK) P.push('strike / gem flash must peak at STRIKE_TICK');
    if (DROP_PULSE.peak !== DROP_LAND_TICK || GEM_ECHO_PULSE.peak !== DROP_LAND_TICK) P.push('drop pulse / gem echo must peak at DROP_LAND_TICK');
    // 追光包络包住皮带步进: 皮带最后一个静止帧 (起步) 在淡入段里, 第一个到位帧 (停下) 在淡出段里
    const moving = prog.rows.filter((r) => r.beltX !== 0 && r.beltX !== -BELT_PITCH);
    const start = Math.max(...prog.rows.filter((r) => r.beltX === 0 && r.tick < Math.min(...moving.map((m) => m.tick))).map((r) => r.tick));
    const end = Math.min(...prog.rows.filter((r) => r.beltX === -BELT_PITCH).map((r) => r.tick));
    if (!(CHASE_WINDOW[0] <= start && start <= CHASE_WINDOW[1] && CHASE_WINDOW[2] <= end && end <= CHASE_WINDOW[3])) P.push(`chase window ${CHASE_WINDOW} does not wrap the belt step ${start}..${end}`);
    if (!(ALPHA_CUTOFF < BREATH_ALPHA[0] && BREATH_ALPHA[0] < BREATH_ALPHA[1])) P.push('breath alpha must stay above the discard cutoff');
    // 每档的速度: 表与 tiers.mjs 相同 (生成器 / 预览 / 对拍读的是同一份), 冲压时刻 = STRIKE_TICK 映射过去的整 tick
    const want = TIERS.map((T) => T.cycleTicks);
    const cyc = prog.cycleByTier || [], stk = prog.strikeByTier || [];
    if (cyc.join() !== want.join()) P.push(`CYCLE_TICKS_BY_TIER ${cyc} != tiers.mjs cycleTicks ${want}`);
    cyc.forEach((c, t) => { if (stk[t] * CYCLE_TICKS !== STRIKE_TICK * c) P.push(`tier ${t}: strike tick ${stk[t]} is not STRIKE_TICK scaled to a ${c}-tick cycle`); });
    // 光敏, 逐档 (photosensitivity): 追光与各路脉冲都不超过 MAX_FLASH_HZ
    for (const [t, r] of photosensitivity(prog).entries()) {
        if (r.chase > MAX_FLASH_HZ + 1e-9) P.push(`tier ${t}: belt chase flashes at ${r.chase} Hz (> ${MAX_FLASH_HZ} Hz)`);
        for (const [ch, hz] of Object.entries(r.pulses)) if (hz > MAX_FLASH_HZ + 1e-9) P.push(`tier ${t}: ${ch} flashes at ${hz} Hz (> ${MAX_FLASH_HZ} Hz)`);
    }
    P.push(...firstCycleProblems(prog));
    return P;
}
/**
 * 第一轮开工: 开工之后真的发生过的拍 (循环里的程序时间已过了它的峰) 与跑了很久之后同一相位逐位相同, 不许被 d ≤ elapsed 误判成没发生过;
 * 六档 × 开工后第一个循环的每一 tick × 九个 partialTick (五个不是 0.25 的倍数)。峰在上一轮的尾巴 (本来就不画) 不在这里查。
 * (wrap 写成 ((t % p) + p) % p 时, 按档位映射过的程序时间会多一个 ulp, 中级档起报这一条。)
 */
export function firstCycleProblems(prog) {
    const P = [];
    const partials = [0, 0.1, 0.15957803, 0.25, 0.3, 0.4065408, 0.5, 0.8456556, 0.9999];
    const beats = [['strike', STRIKE_PULSE.peak], ['drop', DROP_PULSE.peak], ['gem', GEM_PULSE.peak], ['gemEcho', GEM_ECHO_PULSE.peak], ...LEDS.map((l, i) => ['led_' + l.name, l.peak, i])];
    for (let tier = 0; tier < TIERS.length; tier++) {
        const c = cycleTicksOf(prog, tier), later = 10080 * 4;
        for (let e = 0; e < c; e++) for (const p of partials) {
            const a = lightFrame({ tier, active: true, elapsedTicks: e, partialTick: p }, prog);
            const b = lightFrame({ tier, active: true, elapsedTicks: later + e, partialTick: p }, prog);
            const la = levels(a), lb = levels(b);
            for (const [k, peak, led] of beats) {
                if (a.cycleTick < peak) continue;
                const va = led == null ? la[k] : la.leds[led], vb = led == null ? lb[k] : lb.leds[led];
                if (va !== vb) { P.push(`tier ${tier} first cycle e ${e} + ${p}: ${k} (peak ${peak}) already happened but draws ${va}, later ${vb}`); return P; }
            }
        }
    }
    return P;
}
/**
 * 光敏, 每档一条 {cycleTicks, chase, pulses: {路: Hz}}:
 *   chase = 皮带追光的瞬时频率 = 图案速度 (帧表里皮带最快的一段, px / 程序 tick × 倍速 CYCLE_TICKS / 该档循环 = px / 游戏 tick) × CHASE_SPEED / 节距 × 20 tick/s
 *           (每一点每 "节距 / 速度" 过一次彗星头; 普通 1.5 Hz, 闪耀 3.0 Hz);
 *   pulses = 这一档画得出的各路脉冲实测的闪烁频率: 按 lightFrame + levels 在游戏时间里跑该档 FLASH_WINDOW_CYCLES 个整循环 (每 1/8 tick),
 *           数这一路从 < 0.5 升到 ≥ 0.5 的次数 ÷ 秒数 (路 = 冲压 / 落箱 / 宝石 (白闪与回响取大的, 与渲染器画的一样, 只有闪耀) / 各工位灯段)。
 * 呼吸 (80 tick) 与满仓 (40 tick) 走游戏时间, 与档位无关 (0.25 / 0.5 Hz), 不在这里。
 * 追光只在极品 (档 3) 起解锁, 但 chase 每档都按倍速算出来并核对 (保守: 以后下放追光也不会超限); chaseUnlocked 标出这一档画不画它。
 */
export const FLASH_WINDOW_CYCLES = 4;
export function photosensitivity(prog) {
    let fastest = 0;
    for (let i = 1; i < prog.rows.length; i++) fastest = Math.max(fastest, Math.abs(prog.rows[i].beltX - prog.rows[i - 1].beltX) / (prog.rows[i].tick - prog.rows[i - 1].tick));
    const pulses = flashRates(prog);
    return (prog.cycleByTier || []).map((c, t) => ({
        cycleTicks: c, chase: CHASE_SPEED * fastest * (CYCLE_TICKS / c) / BELT_PITCH * 20, chaseUnlocked: (effectMask(t) & (1 << EI.chase)) !== 0, pulses: pulses[t],
    }));
}
function flashRates(program) {
    return TIERS.map((T, tier) => {
        const mask = effectMask(tier), on = (id) => (mask & (1 << EI[id])) !== 0;
        const c = cycleTicksOf(program, tier), cycles = FLASH_WINDOW_CYCLES;
        const count = {}, prev = {};
        // 从一个很久以前开工的循环起点 (10080 = 各档循环与呼吸周期的公倍数) 跑整 cycles 个循环, 每 1/8 游戏 tick 一个样本
        for (let q = 0; q < c * cycles * 8; q++) {
            const L = levels(lightFrame({ tier, active: true, full: false, elapsedTicks: 10080 + Math.floor(q / 8), gameTime: 0, partialTick: (q % 8) / 8 }, program));
            const ch = {};
            if (on('strike')) ch.strike = L.strike;
            if (on('drop')) ch.drop = L.drop;
            if (on('gem')) ch.gem = Math.max(L.gem, L.gemEcho);
            if (on('leds')) LEDS.forEach((l, i) => { ch['led_' + l.name] = L.leds[i]; });
            for (const [k, v] of Object.entries(ch)) {
                if (!(k in count)) count[k] = 0;
                if (q > 0 && prev[k] < 0.5 && v >= 0.5) count[k]++;
                prev[k] = v;
            }
        }
        return Object.fromEntries(Object.entries(count).map(([k, n]) => [k, n / (c * cycles / 20)]));
    });
}
/**
 * 覆盖层核对: 六档 × (工作 / 待机 / 待机满仓 / 工作满仓) × 游戏时间 0..160 每 1/8 tick (呼吸两整周, 含第一轮的接缝尾巴; 程序时间里
 * 最快的闪耀档也是每 0.25 tick 一个样本) × 几个距离: 两两不重叠、每个顶点的 α ≥ ALPHA_CUTOFF、rgb 是 0..255 整数、不超过 MAX_QUADS。
 * 返回 {problems, max: [每档最多几个]}。
 */
export function frameProblems(program) {
    const P = [], max = TIERS.map(() => 0);
    for (let tier = 0; tier < TIERS.length; tier++) for (const [active, full] of [[true, false], [false, false], [false, true], [true, true]]) {
        for (const distance of [0, 21.5, 23.99]) for (let q = 0; q <= 1280; q++) {
            const e = Math.floor(q / 8), partial = (q % 8) / 8;
            const rects = lightOverlays(lightFrame({ tier, active, full, elapsedTicks: e, gameTime: e + 7, partialTick: partial, distance }, program));
            max[tier] = Math.max(max[tier], rects.length);
            const where = `tier ${tier} ${active ? 'work' : 'idle'}${full ? '+full' : ''} d ${distance} t ${e + partial}`;
            const ov = overlapProblems(rects);
            if (ov.length) { P.push(`${where}: ${ov[0]}`); return { problems: P, max }; }
            for (const r of rects) for (const c of [r.c0, r.c1]) {
                if (!(c[3] >= ALPHA_CUTOFF && c[3] <= 1)) { P.push(`${where}: ${EFFECTS[r.effect].id} alpha ${c[3]} outside [cutoff, 1]`); return { problems: P, max }; }
                if (c.slice(0, 3).some((v) => !Number.isInteger(v) || v < 0 || v > 255)) { P.push(`${where}: ${EFFECTS[r.effect].id} bad rgb ${c}`); return { problems: P, max }; }
            }
            if (rects.length > MAX_QUADS) { P.push(`${where}: ${rects.length} quads > MAX_QUADS`); return { problems: P, max }; }
            const mask = effectMask(tier);
            const stray = rects.find((r) => !(mask & (1 << r.effect)));
            if (stray) { P.push(`${where}: ${EFFECTS[stray.effect].id} is not unlocked at this tier`); return { problems: P, max }; }
        }
    }
    return { problems: P, max };
}

// ---------------------------------------------------------------- Java 常量 (MunitionsBenchGeometry 的 LIGHT_* 段)
/** 写进 Java 的全部常量 (parseLightsJava 解析出来的是同一个形状)。 */
export function lightsLayout() {
    return {
        alphaCutoff: ALPHA_CUTOFF, maxDistance: MAX_DISTANCE_BLOCKS, fadeStart: FADE_START_BLOCKS, maxQuads: MAX_QUADS, ledMergeEps: LED_MERGE_EPS,
        targets: TARGETS.map((T) => ({ key: T.key, face: FACE_CODES[T.face], rect: [...T.lo, ...T.hi], grow: [...T.grow], lift: T.lift, shade: T.shade })),
        groups: GROUP_NAMES.map((n) => [...GROUPS[n]]),
        ledX: LEDS.map((l) => l.x), ledPeaks: LEDS.map((l) => l.peak), ledWidth: LED_WIDTH_PX, ledPulse: [LED_PULSE.attack, LED_PULSE.decay],
        strikePulse: [STRIKE_PULSE.peak, STRIKE_PULSE.attack, STRIKE_PULSE.decay],
        dropPulse: [DROP_PULSE.peak, DROP_PULSE.attack, DROP_PULSE.decay], dropAlpha: DROP_ALPHA,
        gemPulse: [GEM_PULSE.peak, GEM_PULSE.attack, GEM_PULSE.decay],
        gemEchoPulse: [GEM_ECHO_PULSE.peak, GEM_ECHO_PULSE.attack, GEM_ECHO_PULSE.decay], gemEchoLevel: GEM_ECHO_LEVEL,
        fullPeriod: FULL_BLINK_PERIOD_TICKS, fullRamp: [...FULL_BLINK_RAMP], fullAlpha: FULL_ALPHA,
        breathPeriod: BREATH_PERIOD_TICKS, breathAlpha: [...BREATH_ALPHA],
        chaseWindow: [...CHASE_WINDOW], chaseSpeed: CHASE_SPEED, chaseTail: CHASE_TAIL_PX, chaseAlpha: [...CHASE_ALPHA],
        unlock: EFFECTS.map((e) => e.unlock),
        colours: colourTable(TIERS),
    };
}
/** 数的 Java 写法: 精确 (这里的数都是二进制有限小数或 Java 的 double 字面量本身), 不像生成器的 f1 那样舍到 4 位 (1/32 会变 0.0313)。 */
const jd = (v) => { const s = String(v); if (!Number.isFinite(v) || /e/i.test(s)) throw new Error('lights: cannot write ' + v); return s.includes('.') ? s : s + '.0'; };
const jf = (v) => { if (Math.fround(v) !== v) throw new Error('lights: ' + v + ' is not exact as a float'); return jd(v) + 'F'; };
/** 生成器写出的 Java 行 (类体里, 4 格缩进)。 */
export function lightsJavaLines(L = lightsLayout()) {
    const da = (v) => '{' + v.map(jd).join(', ') + '}';
    const fa = (v) => '{' + v.map(jf).join(', ') + '}';
    const ia = (v) => '{' + v.join(', ') + '}';
    const hx = (v) => '0x' + v.toString(16).toUpperCase().padStart(6, '0');
    const FACE_NAME = FACE_BY_CODE;
    return [
        '    // ---- 运行灯效 (MunitionsBenchLights): 工作时在静态灯带上叠一层随程序动作的发光四边形, 按档位解锁 ----',
        '    // 与 tools/munitions_bench/lights.mjs 同一份 (生成器写出后解析回来核对, 并按生成的 JSON 核对每个目标面); 画法在 MunitionsBenchLights。',
        '    /** 覆盖层 α 低于这个不画 (rendertype_text_background.fsh 的 discard 阈值; 每个顶点都要 ≥ 它)。 */',
        `    public static final double LIGHT_ALPHA_CUTOFF = ${jd(L.alphaCutoff)};`,
        '    /** 相机离主格中心超过这么多格不画 (= COUNTER_MAX_DISTANCE_BLOCKS); 运行呼吸从 LIGHT_FADE_START_BLOCKS 起渐隐。 */',
        `    public static final float LIGHT_MAX_DISTANCE_BLOCKS = ${jf(L.maxDistance)};`,
        `    public static final float LIGHT_FADE_START_BLOCKS = ${jf(L.fadeStart)};`,
        '    /** 一帧最多几个覆盖四边形 (MunitionsBenchLights.Frame 的定长数组; 生成器逐 0.25 tick 核对六档都不超过它)。 */',
        `    public static final int LIGHT_MAX_QUADS = ${L.maxQuads};`,
        '    /** 工位灯亮度低于这个就不单独切段 (并进呼吸段)。 */',
        `    public static final double LIGHT_LED_MERGE_EPSILON = ${jd(L.ledMergeEps)};`,
        '    /**',
        '     * 覆盖层贴的静态元素面 (整台像素, 朝北), 下标 = MunitionsBenchLights 的目标下标。面 = 原版 Direction.get3DDataValue() (下 0 上 1 北 2 南 3 西 4 东 5);',
        '     * 矩形 {x0, y0, z0, x1, y1, z1} (法线轴上两值相同 = 面所在的平面); 胀 {x0, x1, y0, y1, z0, z1} = 这条边往外胀 LIFT (相邻两个覆盖面在棱上接成壳);',
        '     * 浮出 = 沿面外法线离开面的距离 (px); 明暗 = 元素的 shade 标志 (渲染器按 level.getShade(面方向, 它) 乘颜色)。',
        '     */',
        `    public static final int[] LIGHT_TARGET_FACES = ${ia(L.targets.map((t) => t.face))};`,
        '    public static final float[][] LIGHT_TARGET_RECTS_PX = {',
        ...L.targets.map((t, i) => `            ${fa(t.rect)}, // ${i} ${t.key}: ${TARGETS[i] ? TARGETS[i].el : '?'}.${FACE_NAME[t.face]}`),
        '    };',
        '    public static final int[][] LIGHT_TARGET_GROW = {',
        ...L.targets.map((t, i) => `            ${ia(t.grow)}, // ${i} ${t.key}`),
        '    };',
        `    public static final float[] LIGHT_TARGET_LIFT_PX = ${fa(L.targets.map((t) => t.lift))};`,
        `    public static final boolean[] LIGHT_TARGET_SHADED = {${L.targets.map((t) => String(t.shade)).join(', ')}};`,
        '    /** 目标组 (下标 = MunitionsBenchLights.GROUP_*: 前沿灯带 / 后护栏背光 / 压机横梁灯条 / 弹药箱下灯带 / 闪耀宝石), 每组一起亮的目标。 */',
        '    public static final int[][] LIGHT_GROUPS = {',
        ...L.groups.map((g, i) => `            ${ia(g)}, // ${GROUP_NAMES[i]}`),
        '    };',
        '    /** 工位指示灯 (前沿灯带上、各弹位正下方一段 LIGHT_LED_WIDTH_PX 宽; 按 x 从小到大 = 出弹 / 压弹头 / 装药 / 底火 / 入口) 的中心 x 与脉冲峰 (循环 tick)。 */',
        `    public static final float[] LIGHT_LED_X_PX = ${fa(L.ledX)};`,
        `    public static final double[] LIGHT_LED_PEAK_TICKS = ${da(L.ledPeaks)};`,
        `    public static final float LIGHT_LED_WIDTH_PX = ${jf(L.ledWidth)};`,
        '    /** 脉冲 {缓入 tick, 衰减 tick} / {峰 tick, 缓入, 衰减}: 峰前二次缓入, 峰后二次衰减 (MunitionsBenchLights.pulse)。 */',
        `    public static final double[] LIGHT_LED_PULSE = ${da(L.ledPulse)};`,
        `    public static final double[] LIGHT_STRIKE_PULSE = ${da(L.strikePulse)};`,
        `    public static final double[] LIGHT_DROP_PULSE = ${da(L.dropPulse)};`,
        `    public static final double LIGHT_DROP_ALPHA = ${jd(L.dropAlpha)};`,
        `    public static final double[] LIGHT_GEM_PULSE = ${da(L.gemPulse)};`,
        `    public static final double[] LIGHT_GEM_ECHO_PULSE = ${da(L.gemEchoPulse)};`,
        `    public static final double LIGHT_GEM_ECHO_LEVEL = ${jd(L.gemEchoLevel)};`,
        '    /** 满仓提示 (待机): 客户端时钟每 LIGHT_FULL_BLINK_PERIOD_TICKS 一次梯形闪, 拐点 {起, 全亮, 开始暗, 灭} (tick)。 */',
        `    public static final int LIGHT_FULL_BLINK_PERIOD_TICKS = ${L.fullPeriod};`,
        `    public static final double[] LIGHT_FULL_BLINK_RAMP = ${da(L.fullRamp)};`,
        `    public static final double LIGHT_FULL_ALPHA = ${jd(L.fullAlpha)};`,
        '    /** 运行呼吸: 周期 (tick) 与 α {最低, 最高} (只往亮的一侧)。 */',
        `    public static final int LIGHT_BREATH_PERIOD_TICKS = ${L.breathPeriod};`,
        `    public static final double[] LIGHT_BREATH_ALPHA = ${da(L.breathAlpha)};`,
        '    /** 皮带追光: 包络拐点 {淡入起, 淡入止, 淡出起, 淡出止} (循环 tick)、图案速度 (× 皮带位移)、彗尾长 (px)、α {头, 暗槽}。 */',
        `    public static final double[] LIGHT_CHASE_WINDOW = ${da(L.chaseWindow)};`,
        `    public static final double LIGHT_CHASE_SPEED = ${jd(L.chaseSpeed)};`,
        `    public static final double LIGHT_CHASE_TAIL_PX = ${jd(L.chaseTail)};`,
        `    public static final double[] LIGHT_CHASE_ALPHA = ${da(L.chaseAlpha)};`,
        '    /** 每个效果从哪一档起有 (下标 = MunitionsBenchLights.EFFECT_*: 追光 / 呼吸 / 工位灯 / 冲压 / 落箱 / 满仓 / 宝石)。 */',
        `    public static final int[] LIGHT_UNLOCK_TIERS = ${ia(L.unlock)};`,
        '    /**',
        '     * 颜色 0xRRGGBB, 下标 [档位 0..5][角色 = MunitionsBenchLights.ROLE_*: 工位灯 / 冲压回落色 / 落箱 / 满仓琥珀 / 宝石白闪 / 宝石回响 /',
        '     * 呼吸 / 追光头 / 追光暗槽], 由 tiers.mjs 的档位灯色推出 (lights.mjs lightPalette)。',
        '     */',
        '    public static final int[][] LIGHT_COLOURS = {',
        ...L.colours.map((row, t) => `            {${row.map(hx).join(', ')}}, // 档位 ${t}`),
        '    };',
    ];
}
/** 解析 MunitionsBenchGeometry.java 的 LIGHT_* 段 → 与 lightsLayout() 同形 (目标名取自行尾注释)。 */
export function parseLightsJava(src) {
    const num = (s) => parseFloat(String(s).trim().replace(/[FfDd]$/, ''));
    const one = (n) => { const m = new RegExp(`static final (?:float|int|double) ${n} = ([-\\w.]+);`).exec(src); if (!m) throw new Error('lights parse: ' + n); return num(m[1]); };
    const arr = (n) => { const m = new RegExp(`static final (?:float|int|double|boolean)\\[\\] ${n} = \\{([^}]*)\\};`).exec(src); if (!m) throw new Error('lights parse: ' + n); return m[1].split(',').map((s) => s.trim()); };
    const rows = (n) => {
        const m = new RegExp(`static final (?:float|int)\\[\\]\\[\\] ${n} = \\{([\\s\\S]*?)\\n\\s*\\};`).exec(src);
        if (!m) throw new Error('lights parse: ' + n);
        return [...m[1].matchAll(/\{([^{}]*)\},?[ \t]*(?:\/\/[ \t]*([^\n]*))?/g)].map((x) => ({ vals: x[1].split(',').map((s) => s.trim()), note: (x[2] || '').trim() }));
    };
    const faces = arr('LIGHT_TARGET_FACES').map(Number), rects = rows('LIGHT_TARGET_RECTS_PX'), grow = rows('LIGHT_TARGET_GROW');
    const lift = arr('LIGHT_TARGET_LIFT_PX').map(num), shade = arr('LIGHT_TARGET_SHADED').map((s) => s === 'true');
    return {
        alphaCutoff: one('LIGHT_ALPHA_CUTOFF'), maxDistance: one('LIGHT_MAX_DISTANCE_BLOCKS'), fadeStart: one('LIGHT_FADE_START_BLOCKS'), maxQuads: one('LIGHT_MAX_QUADS'), ledMergeEps: one('LIGHT_LED_MERGE_EPSILON'),
        targets: faces.map((face, i) => ({ key: (/^\d+ (\w+)/.exec(rects[i].note) || [])[1], face, rect: rects[i].vals.map(num), grow: grow[i].vals.map(Number), lift: lift[i], shade: shade[i] })),
        groups: rows('LIGHT_GROUPS').map((r) => r.vals.map(Number)),
        ledX: arr('LIGHT_LED_X_PX').map(num), ledPeaks: arr('LIGHT_LED_PEAK_TICKS').map(num), ledWidth: one('LIGHT_LED_WIDTH_PX'), ledPulse: arr('LIGHT_LED_PULSE').map(num),
        strikePulse: arr('LIGHT_STRIKE_PULSE').map(num),
        dropPulse: arr('LIGHT_DROP_PULSE').map(num), dropAlpha: one('LIGHT_DROP_ALPHA'),
        gemPulse: arr('LIGHT_GEM_PULSE').map(num),
        gemEchoPulse: arr('LIGHT_GEM_ECHO_PULSE').map(num), gemEchoLevel: one('LIGHT_GEM_ECHO_LEVEL'),
        fullPeriod: one('LIGHT_FULL_BLINK_PERIOD_TICKS'), fullRamp: arr('LIGHT_FULL_BLINK_RAMP').map(num), fullAlpha: one('LIGHT_FULL_ALPHA'),
        breathPeriod: one('LIGHT_BREATH_PERIOD_TICKS'), breathAlpha: arr('LIGHT_BREATH_ALPHA').map(num),
        chaseWindow: arr('LIGHT_CHASE_WINDOW').map(num), chaseSpeed: one('LIGHT_CHASE_SPEED'), chaseTail: one('LIGHT_CHASE_TAIL_PX'), chaseAlpha: arr('LIGHT_CHASE_ALPHA').map(num),
        unlock: arr('LIGHT_UNLOCK_TIERS').map(Number),
        colours: rows('LIGHT_COLOURS').map((r) => r.vals.map((s) => parseInt(s, 16))),
    };
}
/** 两份布局是否相同 (键排序后比较); 返回第一处不同的说明或 null。 */
export function layoutDiff(a, b) {
    const canon = (v) => (Array.isArray(v) ? v.map(canon) : v && typeof v === 'object' ? Object.fromEntries(Object.keys(v).sort().map((k) => [k, canon(v[k])])) : v);
    const A = canon(a), B = canon(b);
    for (const k of new Set([...Object.keys(A), ...Object.keys(B)])) if (JSON.stringify(A[k]) !== JSON.stringify(B[k])) return `${k}: ${JSON.stringify(A[k])} != ${JSON.stringify(B[k])}`;
    return null;
}
