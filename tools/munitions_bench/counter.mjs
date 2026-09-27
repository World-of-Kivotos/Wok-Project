// 军火台弹药箱计数屏 (方案 C "弹药箱计数"): 布局、字体、数字格式、满度条、满仓、显示键, 以及预览用的四边形。零依赖。
//
// 掀开的箱盖 (8 px 宽, 0.75 px 厚) 内面凹进去一块小窗: 上面一条满度条, 下面 4x7 大字的缓冲发数; 口径像真弹药箱那样
// 用黄漆模板字印在箱身正面 (按方块光照明, 不自发光, 缓冲空时不写)。窗下留黄漆子弹 (箱盖的标志), 档位色密封框保留。
//
// 三方共用这一份:
//   - 生成器 (generate_munitions_bench.mjs): 按 DISPLAY 建箱盖的凹窗 / 箱身的空标签、画静态底板, 并把布局与每档颜色写进
//     MunitionsBenchGeometry.java 的 COUNTER_* 常量 (写完解析回来核对);
//   - Java (block/MunitionsBenchCounter.java): 数字格式、字形、排版、满度条、满仓、显示键、角的摆法 (facePoint / benchCorners /
//     blockCorners = 这里的 facePoint / rectCorners / blockCorners) 与这里逐行对应 —— **改一边必须改另一边**;
//     check_parity.mjs 单独编译那个类, 与这里逐值对拍; 渲染器与 GameTest 摆角都调 blockCorners;
//   - 预览 (render_mb.mjs): 只读 Java 里的 COUNTER_* 常量 (parseCounterJava), 不读这里的 DISPLAY, 所以预览里的字就是
//     BER 按 Java 常量会画的样子。
//
// 坐标: 整台朝北, 世界像素 (x 东, y 上, z 南, 正面 z = 0)。显示面的布局单位是 qt (0.25 px = 箱盖 4 倍贴图的一个贴图像素),
// 显示面左上角 = 从正面看的左上角 (北面: 左 = 东 = +x), u 向右 (-x), v 向下 (-y)。
//
// BER 画字规则 (client/MunitionsBenchCounterRenderer.java; 下面的变换与顶点顺序都在 MunitionsBenchCounter.blockCorners 里算好,
// 渲染器把算好的角原样交给 BER 的 poseStack):
//   相机离主格中心超过 MAX_DIST_BLOCKS 格不画;
//   poseStack.translate(0.5, 0, 0.5); mulPose(Axis.YP.rotationDegrees(partsYRotationDegrees(facing))); translate(-0.5, 0, -0.5);
//   poseStack.scale(1 / 16f, 1 / 16f, 1 / 16f);   // 只缩放, 不要运动件那种 (s, -s, s) 的 y 翻转 (会把绕序再反一次)
//   窗面在旋转的箱盖上: translate(o); mulPose(Axis.XP.rotationDegrees(22.5f)); translate(-o); 箱身正面不旋转;
//   RenderType.textBackground() (POSITION_COLOR_LIGHTMAP: 没有贴图, 没有按法线的明暗, 剔除背面);
//   每个矩形 (x0, y0, x1, y1 qt): xl = tl.x - x0 * QT, xr = tl.x - x1 * QT, yt = tl.y - y0 * QT, yb = tl.y - y1 * QT, z = tl.z - LIFT,
//   顶点顺序 = 从正面看 左上 → 左下 → 右下 → 右上 (逆时针, 与原版 FaceInfo.NORTH 相同);
//   窗里的字与满度条 LightTexture.FULL_BRIGHT (颜色原样); 箱身模板字用方块实体的 packedLight, 颜色再乘 level.getShade(朝向, true),
//   与箱身这一面的静态明暗一致, 看上去是漆上去的。不要用 entity* 渲染类型 (按法线打两盏方向光, 字的亮度会随台子朝向变)。

export const QT = 0.25;             // 布局单位 (px)
export const RECESS = 0.25;         // 屏窗凹进框条前沿 (px)
export const LIFT = 0.125;          // 字浮在窗面 (或箱身) 外 (px) = 1/128 格, 仍在框条前沿之后
export const MAX_DIST_BLOCKS = 24;  // 超过这个距离 BER 不画字 (字已经读不出, 也避开远处的深度精度)

// ---------------------------------------------------------------- 布局
// el = 箱盖整体 (前沿 z = el.from[2]); 模型规则: 元素每个方向至少 0.5 px 厚, 所以凹窗 = 满厚的四条框条 + 窗后一块本体 (箱盖加厚到 0.75 px,
// 背面正好靠上备用弹药箱的上沿)。size = 箱盖内面的 qt 尺寸; window = 屏窗; bar = 满度条 (在字上面: 远看时箱里的弹挡不到它);
// count = 发数 (右对齐在 [x, x + w) 里, texel = 字体一个贴图像素占几个 qt)。
// stencil = 箱身正面 [1,3,1]-[8,8.5,10] (28 x 22 qt, 不凹): 口径模板字水平居中在第 y 行, label = 生成器画的深色标签区 (只在 JS 侧核对字放得下)。
export const DISPLAY = {
    el: { from: [0.5, 8.5, 10], to: [8.5, 17.5, 10.75], rot: { origin: [4.5, 8.5, 10.5], axis: 'x', angle: 22.5 } },
    size: [32, 36], recess: RECESS,
    window: { x: 4, y: 10, w: 24, h: 12 },
    bar: { x: 5, y: 11, w: 22, h: 2 },
    count: { x: 5, y: 14, w: 22, font: '4x7', texel: 1 },
    stencil: { el: { from: [1, 3, 1], to: [8, 8.5, 10], rot: null }, size: [28, 22], y: 8, font: '3x5', texel: 1, label: { x: 4, y: 6, w: 20, h: 9 } },
};

/**
 * 渲染用的布局 (与 MunitionsBenchGeometry 的 COUNTER_* 常量一一对应; parseCounterJava 解析出来的是同一个形状)。
 * window.tl = 窗面左上角 (凹进去的本体北面, 旋转前), stencil.tl = 箱身正面左上角。
 */
export function layoutFromDisplay(D = DISPLAY) {
    return {
        qt: QT, lift: LIFT, recess: D.recess, maxDist: MAX_DIST_BLOCKS,
        window: {
            tl: [D.el.to[0], D.el.to[1], D.el.from[2] + D.recess], face: [...D.size],
            rot: { origin: [...D.el.rot.origin], angle: D.el.rot.angle },
            rect: { ...D.window },
        },
        bar: { ...D.bar },
        count: { x: D.count.x, y: D.count.y, w: D.count.w, texel: D.count.texel },
        stencil: { tl: [D.stencil.el.to[0], D.stencil.el.to[1], D.stencil.el.from[2]], face: [...D.stencil.size], y: D.stencil.y, texel: D.stencil.texel },
    };
}

// ---------------------------------------------------------------- 字体 (MunitionsBenchCounter.FONT_4X7 / FONT_3X5)
// 每字一行一个掩码, 最高位 = 最左一列。'.' 是窄字 (1 列宽); 字表里没有的字按空格 (全空, 字宽照算)。
// 3x5 与 core.mjs 的 FONT3x5 同一套字形 (机身上 AMMO 模板字就是它), 只留口径标签用得到的字。
export const FONT_4x7 = {
    id: '4x7', w: 4, h: 7,
    glyphs: {
        0: [6, 9, 9, 9, 9, 9, 6], 1: [2, 6, 2, 2, 2, 2, 7], 2: [6, 9, 1, 2, 4, 8, 15], 3: [14, 1, 1, 6, 1, 1, 14],
        4: [2, 6, 10, 10, 15, 2, 2], 5: [15, 8, 14, 1, 1, 9, 6], 6: [6, 8, 8, 14, 9, 9, 6], 7: [15, 1, 2, 2, 4, 4, 4],
        8: [6, 9, 9, 6, 9, 9, 6], 9: [6, 9, 9, 7, 1, 1, 6],
        K: [9, 9, 10, 12, 10, 9, 9], M: [9, 15, 15, 9, 9, 9, 9], G: [6, 9, 8, 11, 9, 9, 6], ' ': [0, 0, 0, 0, 0, 0, 0],
    },
    narrow: { '.': [0, 0, 0, 0, 0, 0, 1] },
};
export const FONT_3x5 = {
    id: '3x5', w: 3, h: 5,
    glyphs: {
        0: [7, 5, 5, 5, 7], 1: [2, 6, 2, 2, 7], 2: [7, 1, 7, 4, 7], 3: [7, 1, 3, 1, 7], 4: [5, 5, 7, 1, 1],
        5: [7, 4, 7, 1, 7], 6: [7, 4, 7, 5, 7], 7: [7, 1, 1, 2, 2], 8: [7, 5, 7, 5, 7], 9: [7, 5, 7, 1, 7],
        A: [2, 5, 7, 5, 5], B: [6, 5, 6, 5, 6], E: [7, 4, 6, 4, 7], G: [3, 4, 5, 5, 3], M: [5, 7, 7, 5, 5],
        R: [6, 5, 6, 5, 5], X: [5, 5, 2, 5, 5], ' ': [0, 0, 0, 0, 0],
    },
    narrow: { '.': [0, 0, 0, 0, 1] },
};
export const FONTS = { '4x7': FONT_4x7, '3x5': FONT_3x5 };

/** 一个字的宽 (字体像素) 与行掩码 (MunitionsBenchCounter.glyphWidth / glyphRows)。 */
export function glyphWidth(font, ch) { return font.narrow[ch] ? 1 : font.w; }
export function glyphRows(font, ch) { return font.narrow[ch] || font.glyphs[ch] || font.glyphs[' ']; }
/** 一串字的宽 (字体像素): 字宽 + 1 列字距, 末尾不留字距 (MunitionsBenchCounter.textWidth)。 */
export function textWidth(font, str) {
    let x = 0;
    for (const ch of String(str)) x += glyphWidth(font, ch) + 1;
    return Math.max(0, x - 1);
}

// ---------------------------------------------------------------- 数字格式 (MunitionsBenchCounter.format)
/** 超过这个数按它显示 ("999G"): 保证最多 5 个字、放得进发数框; int 缓冲 (上限 21.4 亿 = "2.14G") 碰不到。 */
export const MAX_ROUNDS = 999_999_999_999;
/**
 * 缓冲发数 → 屏上的字 (最多 5 个字符, 只舍不入: 屏上的数永远不多报):
 *   ≤ 0 → "0" (空箱, 画成暗色);  1..9999 原样;  10,000..999,999 → "12.4K" / "123K";  ≥ 1,000,000 → "1.23M" / "12.3M" / "123M";
 *   ≥ 10^9 → "1.23G" .. "999G" (超过 MAX_ROUNDS 按它算)。小数末尾的 0 去掉 ("3.20M" → "3.2M", "10.0K" → "10K")。
 *   默认 config 的缓冲上限是 500..4000 发 (bufferL1..L10), 所以正常只会看到 1–4 位数; K / M 只在服主把上限调大时出现。
 */
export function formatCount(rounds) {
    let n = Math.floor(Number(rounds) || 0);
    if (n <= 0) return '0';
    if (n > MAX_ROUNDS) n = MAX_ROUNDS;
    if (n <= 9999) return String(n);
    const unit = n >= 1e9 ? 1e9 : n >= 1e6 ? 1e6 : 1e3;
    const suffix = unit === 1e3 ? 'K' : unit === 1e6 ? 'M' : 'G';
    const v100 = Math.floor(n / (unit / 100));   // 两位小数, 截断 (= floor(n * 100 / unit), 不会溢出)
    let t;
    if (v100 >= 10000) t = String(Math.floor(v100 / 100));
    else if (v100 >= 1000) t = Math.floor(v100 / 100) + '.' + (Math.floor(v100 / 10) % 10);
    else t = Math.floor(v100 / 100) + '.' + (Math.floor(v100 / 10) % 10) + (v100 % 10);
    if (t.includes('.')) t = t.replace(/0+$/, '').replace(/\.$/, '');
    return t + suffix;
}

// ---------------------------------------------------------------- 满度条 / 满仓 / 显示键
/** 满度条亮几格 (qt): 空 = 0; 上限 ≤ 0 = 整条; 否则 floor(发数 × 条宽 / 上限), 有弹至少 1 格, 最多整条 (MunitionsBenchCounter.barCells)。 */
export function barCells(L, rounds, cap) {
    const w = L.bar.w;
    if (rounds <= 0) return 0;
    if (cap <= 0) return w;
    return Math.max(1, Math.min(w, Math.floor((rounds * w) / cap)));
}
/** 缓冲装不下一整批 (MunitionsBenchCounter.cannotTakeBatch; 方块实体的开工门、持续指示灯与满仓条共用)。 */
export const cannotTakeBatch = (rounds, cap, batchRounds) => cap - rounds < batchRounds;
/** 满仓 = 有弹且装不下下一批 (条变琥珀色; 机器就是因为这个停的)。 */
export const isFull = (rounds, cap, batchRounds) => rounds > 0 && cannotTakeBatch(rounds, cap, batchRounds);

/** 显示键里屏上字的编码: 每字 4 位, 0 = 没有字。 */
export const KEY_CHARS = '0123456789.KMG';
/**
 * 显示键 (MunitionsBenchCounter.displayKey): 屏上看得见的样子 = 发数的字 + 箱身口径 + 满度条格数 + 满仓; 任一变了键才变,
 * 服务端据此决定要不要发方块更新。字 (≤ 5 个) 占低 20 位, 口径序号 + 1 (空 = 0) 在 2^20, 格数在 2^28, 满仓在 2^36。
 */
export function displayKey(L, rounds, caliberIndex, cap, full) {
    const text = formatCount(rounds);
    let key = 0;
    for (const ch of text) key = key * 16 + (KEY_CHARS.indexOf(ch) + 1);
    const cal = rounds > 0 && caliberIndex >= 0 ? Math.min(caliberIndex, 254) + 1 : 0;
    const cells = barCells(L, rounds, cap);
    const f = rounds > 0 && full ? 1 : 0;
    return key + cal * 2 ** 20 + cells * 2 ** 28 + f * 2 ** 36;
}

// ---------------------------------------------------------------- 画什么 (MunitionsBenchCounter.rects)
export const FACE_WINDOW = 0, FACE_STENCIL = 1;
export const ROLE_COUNT = 0, ROLE_ZERO = 1, ROLE_BAR = 2, ROLE_BAR_FULL = 3, ROLE_STENCIL = 4;
export const ROLE_NAMES = ['count', 'zero', 'bar', 'barFull', 'stencil'];
/** 一串字 → 矩形 (qt): 逐行从左到右, 同一行相邻亮点并成一条 (字距是空列, 不会跨字并)。 */
function pushText(out, face, role, font, str, ox, oy, t) {
    const chars = [...String(str)];
    for (let r = 0; r < font.h; r++) {
        let x = 0, run = -1;
        for (const ch of chars) {
            const w = glyphWidth(font, ch), rows = glyphRows(font, ch);
            for (let c = 0; c <= w; c++) {   // c = w 是字距 (空列)
                const lit = c < w && (rows[r] & (1 << (w - 1 - c))) !== 0;
                if (lit && run < 0) run = x + c;
                if (!lit && run >= 0) { out.push({ face, role, x0: ox + run * t, y0: oy + r * t, x1: ox + (x + c) * t, y1: oy + (r + 1) * t }); run = -1; }
            }
            x += w + 1;
        }
    }
}
/**
 * 计数屏要画的矩形 (qt, 各自显示面的左上角起), 顺序: 发数 → 满度条 → 箱身口径。
 * state: {rounds, caliberLabel (字符串; null = 不写), cap, full}。发数 0 画暗色 "0" (ROLE_ZERO), 没有满度条也不写口径。
 */
export function counterRects(L, state) {
    const out = [];
    const rounds = Math.max(0, Math.floor(Number(state.rounds) || 0));
    const empty = rounds <= 0;
    const text = formatCount(rounds);
    const c = L.count;
    pushText(out, FACE_WINDOW, empty ? ROLE_ZERO : ROLE_COUNT, FONT_4x7, text, c.x + c.w - textWidth(FONT_4x7, text) * c.texel, c.y, c.texel);
    if (!empty) {
        const b = L.bar, n = barCells(L, rounds, state.cap);
        out.push({ face: FACE_WINDOW, role: state.full ? ROLE_BAR_FULL : ROLE_BAR, x0: b.x, y0: b.y, x1: b.x + n, y1: b.y + b.h });
        if (state.caliberLabel) {
            const s = L.stencil, w = textWidth(FONT_3x5, state.caliberLabel) * s.texel;
            pushText(out, FACE_STENCIL, ROLE_STENCIL, FONT_3x5, state.caliberLabel, Math.floor((s.face[0] - w) / 2), s.y, s.texel);
        }
    }
    return out;
}

/** MunitionsCaliber.shortLabel, 按 index (check_parity.mjs 从 MunitionsCaliber.java 解析出来核对)。 */
export const CALIBER_LABELS = ['9MM', '7.62', '12G', '54R', '.338', '50AE', 'BMG', '40M', '68X', '5.56'];

// ---------------------------------------------------------------- 颜色 (light = 档位灯色六个角色 {on, hi, mid, dim, dk, off})
export const MIX = (a, b, t) => [0, 1, 2].map((i) => Math.round(a[i] + (b[i] - a[i]) * t));
export const AMBER = { on: [255, 178, 52], idle: [226, 146, 40] };   // 满仓的满度条 (与六档灯色都分得开)
export const STENCIL = [244, 194, 44];                              // 黄漆模板字 (= core HAZ_Y)
/**
 * 屏的颜色: bg = 屏底 (静态贴图, 生成器画; 带档位色调, 待机也不是死黑), scan = 扫描线, rim = 上沿框条的影子, track = 满度条的槽 (静态),
 * 其余由 BER 画: count 发数 (工作 = 档位灯色 on; 待机 ≈ 工作的 0.7, 对比度仍 ≥ 4.5:1), zero 空箱的 "0" (暗, 像没点亮),
 * bar 满度条 (满仓 = 琥珀)。
 */
export function counterColours(light, working) {
    const bg = working ? MIX(light.dk, [6, 9, 12], 0.35) : MIX(light.dk, [8, 10, 13], 0.42);
    return {
        bg,
        scan: MIX(bg, [0, 0, 0], 0.28),
        rim: MIX(bg, [0, 0, 0], 0.5),
        track: MIX(bg, light.dim, working ? 0.42 : 0.34),
        count: working ? light.on : MIX(light.on, light.dim, 0.3),
        zero: working ? MIX(light.dim, light.mid, 0.3) : MIX(light.dim, light.mid, 0.12),
        bar: working ? light.mid : MIX(light.mid, light.dim, 0.3),
        barFull: working ? AMBER.on : AMBER.idle,
        stencil: STENCIL,
    };
}
/** 每档一行 [角色 × 2 + (待机 ? 1 : 0)] 的 0xRRGGBB (MunitionsBenchGeometry.COUNTER_COLOURS)。 */
export function colourTable(tiers) {
    const hex = (c) => (c[0] << 16) | (c[1] << 8) | c[2];
    return tiers.map((T) => {
        const w = counterColours(T.roles.light, true), i = counterColours(T.roles.light, false);
        return ROLE_NAMES.flatMap((role) => [hex(w[role]), hex(i[role])]);
    });
}
/** 某档某角色的颜色 [r, g, b] (MunitionsBenchCounter.colour): 档位越界按普通档。 */
export const colourOf = (L, tier, role, working) => {
    const row = L.colours[tier >= 0 && tier < L.colours.length ? tier : 0];
    const v = row[role * 2 + (working ? 0 : 1)];
    return [(v >> 16) & 255, (v >> 8) & 255, v & 255];
};

// ---------------------------------------------------------------- Java 常量 (MunitionsBenchGeometry 的 COUNTER_* 段)
/** 生成器写出的 Java 行 (类体里, 4 格缩进)。f1 = 生成器的 float 格式化。 */
export function counterJavaLines(L, colours, f1) {
    const fa = (v) => '{' + v.map(f1).join(', ') + '}';
    const ia = (v) => '{' + v.join(', ') + '}';
    const hx = (v) => '0x' + v.toString(16).toUpperCase().padStart(6, '0');
    const W = L.window, S = L.stencil;
    return [
        '    // ---- 弹药箱计数屏 (方案 C): 掀开的箱盖内面一块凹窗 (上 满度条, 下 4x7 大字发数), 口径是箱身正面的黄漆模板字 ----',
        '    // 布局与 tools/munitions_bench/counter.mjs 同一份 (生成器写出后解析回来核对); 字形、排版、格式在 MunitionsBenchCounter。',
        '    /** 布局单位 (px): 1 qt = 0.25 px, 即箱盖 4 倍贴图的一个贴图像素。下面的 *_QT 都以它为单位, 面上 u 向右 (北面 = -x)、v 向下。 */',
        `    public static final float COUNTER_QT_PX = ${f1(L.qt)};`,
        '    /** 字浮在窗面 / 箱身外的距离 (px, 1/128 格): 离面够远, 渲染器画字的距离内 24 位深度不会闪; 仍在框条前沿之后。 */',
        `    public static final float COUNTER_LIFT_PX = ${f1(L.lift)};`,
        '    /** 屏窗凹进框条前沿的深度 (px)。 */',
        `    public static final float COUNTER_RECESS_PX = ${f1(L.recess)};`,
        '    /** 相机离主格中心超过这么多格就不画字 (读不出, 也避开远处的深度精度)。 */',
        `    public static final float COUNTER_MAX_DISTANCE_BLOCKS = ${f1(L.maxDist)};`,
        '    /** 窗面 (箱盖里凹进去的那块本体的北面) 左上角, 整台坐标 (px, 朝北, 箱盖旋转之前); 从正面看的左 = 东 = +x。 */',
        `    public static final float[] COUNTER_WINDOW_FACE_TOP_LEFT = ${fa(W.tl)};`,
        '    /** 箱盖内面的尺寸 {宽, 高} (qt)。 */',
        `    public static final int[] COUNTER_LID_FACE_QT = ${ia(W.face)};`,
        '    /** 箱盖的旋转 (与静态 JSON 的 can_lid 元素相同): 绕 x 轴 +COUNTER_LID_ROTATION_X_DEGREES 度, 原点 (px)。 */',
        `    public static final float[] COUNTER_LID_ROTATION_ORIGIN = ${fa(W.rot.origin)};`,
        `    public static final float COUNTER_LID_ROTATION_X_DEGREES = ${f1(W.rot.angle)};`,
        '    /** 屏窗 {x, y, w, h} (qt, 箱盖内面左上角起)。 */',
        `    public static final int[] COUNTER_WINDOW_QT = ${ia([W.rect.x, W.rect.y, W.rect.w, W.rect.h])};`,
        '    /** 满度条 {x, y, w, h} (qt): 亮的格数 = MunitionsBenchCounter.barCells, 从左往右。 */',
        `    public static final int[] COUNTER_BAR_QT = ${ia([L.bar.x, L.bar.y, L.bar.w, L.bar.h])};`,
        '    /** 发数 {x, y, w} (qt): 4x7 字, 右对齐在 [x, x + w) 里; 字体一个像素占 COUNTER_COUNT_TEXEL_QT 个 qt。 */',
        `    public static final int[] COUNTER_COUNT_QT = ${ia([L.count.x, L.count.y, L.count.w])};`,
        `    public static final int COUNTER_COUNT_TEXEL_QT = ${L.count.texel};`,
        '    /** 箱身正面 (口径模板字) 左上角 (px, 不旋转, 不凹) 与尺寸 {宽, 高} (qt)。 */',
        `    public static final float[] COUNTER_STENCIL_FACE_TOP_LEFT = ${fa(S.tl)};`,
        `    public static final int[] COUNTER_STENCIL_FACE_QT = ${ia(S.face)};`,
        '    /** 口径 3x5 模板字: 顶行 y (qt), 水平居中在箱身正面; 字体一个像素占 COUNTER_STENCIL_TEXEL_QT 个 qt。 */',
        `    public static final int COUNTER_STENCIL_Y_QT = ${S.y};`,
        `    public static final int COUNTER_STENCIL_TEXEL_QT = ${S.texel};`,
        '    /**',
        '     * 颜色 0xRRGGBB, 下标 [档位 0..5][角色 × 2 + (待机 ? 1 : 0)], 角色 = MunitionsBenchCounter.ROLE_* (发数 / 空箱的 0 / 满度条 /',
        '     * 满仓的满度条 / 箱身模板字), 由 tiers.mjs 的档位灯色推出 (counter.mjs counterColours)。',
        '     */',
        '    public static final int[][] COUNTER_COLOURS = {',
        ...colours.map((row, t) => `            {${row.map(hx).join(', ')}}, // 档位 ${t}`),
        '    };',
    ];
}

/** 解析 MunitionsBenchGeometry.java 的 COUNTER_* 段 → 与 layoutFromDisplay 同形的布局 (+ colours)。预览只认这份。 */
export function parseCounterJava(src) {
    const num = (s) => parseFloat(String(s).replace(/[FfDd]/g, ''));
    const one = (n) => { const m = new RegExp(`static final (?:float|int) ${n} = ([-\\w.]+);`).exec(src); if (!m) throw new Error('counter parse: ' + n); return num(m[1]); };
    const arr = (n) => { const m = new RegExp(`static final (?:float|int)\\[\\] ${n} = \\{([^}]*)\\};`).exec(src); if (!m) throw new Error('counter parse: ' + n); return m[1].split(',').map(num); };
    const cb = /static final int\[\]\[\] COUNTER_COLOURS = \{([\s\S]*?)\n\s*\};/.exec(src);
    if (!cb) throw new Error('counter parse: COUNTER_COLOURS');
    const colours = [...cb[1].matchAll(/\{([^{}]*)\}/g)].map((m) => m[1].split(',').map((s) => parseInt(s.trim(), 16)));
    const win = arr('COUNTER_WINDOW_QT'), bar = arr('COUNTER_BAR_QT'), cnt = arr('COUNTER_COUNT_QT');
    return {
        qt: one('COUNTER_QT_PX'), lift: one('COUNTER_LIFT_PX'), recess: one('COUNTER_RECESS_PX'), maxDist: one('COUNTER_MAX_DISTANCE_BLOCKS'),
        window: {
            tl: arr('COUNTER_WINDOW_FACE_TOP_LEFT'), face: arr('COUNTER_LID_FACE_QT'),
            rot: { origin: arr('COUNTER_LID_ROTATION_ORIGIN'), angle: one('COUNTER_LID_ROTATION_X_DEGREES') },
            rect: { x: win[0], y: win[1], w: win[2], h: win[3] },
        },
        bar: { x: bar[0], y: bar[1], w: bar[2], h: bar[3] },
        count: { x: cnt[0], y: cnt[1], w: cnt[2], texel: one('COUNTER_COUNT_TEXEL_QT') },
        stencil: { tl: arr('COUNTER_STENCIL_FACE_TOP_LEFT'), face: arr('COUNTER_STENCIL_FACE_QT'), y: one('COUNTER_STENCIL_Y_QT'), texel: one('COUNTER_STENCIL_TEXEL_QT') },
        colours,
    };
}

// ---------------------------------------------------------------- 预览用的四边形 (raster.mjs 的格式)
function rotX(p, rot) {
    if (!rot) return p;
    const a = rot.angle * Math.PI / 180, c = Math.cos(a), s = Math.sin(a), o = rot.origin;
    const y = p[1] - o[1], z = p[2] - o[2];
    return [p[0], y * c - z * s + o[1], y * s + z * c + o[2]];
}
function rotDir(v, rot) {
    if (!rot) return v;
    const a = rot.angle * Math.PI / 180, c = Math.cos(a), s = Math.sin(a);
    return [v[0], v[1] * c - v[2] * s, v[1] * s + v[2] * c];
}
const SOLID = new Map();
function solid(col) {
    const k = col.join(',');
    if (!SOLID.has(k)) SOLID.set(k, { width: 1, height: 1, data: new Uint8ClampedArray([col[0], col[1], col[2], 255]) });
    return SOLID.get(k);
}
/** 面上 qt 点 → 世界像素 (整台朝北, 含箱盖旋转与外移)。face = FACE_WINDOW | FACE_STENCIL。 */
export function facePoint(L, face, u, v, lift = L.lift) {
    const F = face === FACE_STENCIL ? { tl: L.stencil.tl, rot: null } : { tl: L.window.tl, rot: L.window.rot };
    return rotX([F.tl[0] - u * L.qt, F.tl[1] - v * L.qt, F.tl[2] - lift], F.rot);
}
const sub = (a, b) => [a[0] - b[0], a[1] - b[1], a[2] - b[2]];
const cross = (a, b) => [a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0]];
const unit = (v) => { const l = Math.hypot(...v); return v.map((x) => x / l); };
/** 四个角的绕序法线 (右手: (p1 - p0) x (p2 - p0))。逆时针 (从外面看) = 外法线。 */
export function windingNormal(pts) { return unit(cross(sub(pts[1], pts[0]), sub(pts[2], pts[0]))); }
/** 从正面看: 左上, 左下, 右下, 右上 (逆时针, 原版 FaceInfo.NORTH 的顺序; Java 渲染器同序)。 */
export function cornersTLBLBRTR(r) { return [[r.x0, r.y0], [r.x0, r.y1], [r.x1, r.y1], [r.x1, r.y0]]; }
/** 一个矩形的四个角 (整台像素, 朝北, 浮在面外 LIFT), 顺序同上 (MunitionsBenchCounter.benchCorners 的一个矩形)。 */
export function rectCorners(L, r) { return cornersTLBLBRTR(r).map(([u, v]) => facePoint(L, r.face, u, v)); }
/**
 * 按台子朝向摆进主格 (方块坐标, MunitionsBenchCounter.blockCorners): translate(0.5, 0, 0.5) → 绕 y 转 yRot 度 (右手系,
 * = partsYRotationDegrees: 北 0 / 东 -90 / 南 180 / 西 90) → translate(-0.5, 0, -0.5) → scale(1/16)。返回每个矩形四个 [x, y, z]。
 */
export function blockCorners(L, rects, yRot) {
    const a = yRot * Math.PI / 180, c = Math.cos(a), s = Math.sin(a);
    return rects.map((r) => rectCorners(L, r).map(([px, py, pz]) => {
        const x = px / 16 - 0.5, z = pz / 16 - 0.5;
        return [0.5 + x * c + z * s, py / 16, 0.5 - x * s + z * c];
    }));
}
/** 顶点顺序自检: 两个面的绕序法线都必须与面的外法线 (北面, 窗面再转过箱盖的角度) 同向, 否则 textBackground 会把字当背面剔掉。 */
export function windingProblems(L) {
    const out = [];
    for (const face of [FACE_WINDOW, FACE_STENCIL]) {
        const pts = cornersTLBLBRTR({ x0: 0, y0: 0, x1: 4, y1: 4 }).map(([u, v]) => facePoint(L, face, u, v));
        const wn = windingNormal(pts), fn = rotDir([0, 0, -1], face === FACE_STENCIL ? null : L.window.rot);
        const dot = wn[0] * fn[0] + wn[1] * fn[1] + wn[2] * fn[2];
        if (dot < 0.999) out.push(`counter face ${face}: vertex winding faces ${wn.map((x) => x.toFixed(3))}, the face normal is ${fn.map((x) => x.toFixed(3))}`);
    }
    return out;
}
/**
 * BER 画的计数屏 → raster 四边形 (世界像素, 整台朝北)。窗里的字: 全亮 (shade 1, light 15, 不受明暗与朝向影响);
 * 箱身模板字: 方块明暗 (shade null → 按法线取原版面明暗) + 环境光 (light 0)。法线取自顶点绕序 (见 windingNormal)。
 * state: {rounds, caliber (MunitionsCaliber 序号, -1 / null = 空), cap, full, working}; tier = 档位下标 (颜色)。
 */
export function counterQuads(L, tier, state) {
    const cal = state.caliber === null || state.caliber === undefined || state.caliber < 0 ? null : CALIBER_LABELS[state.caliber] || null;
    return counterRects(L, { rounds: state.rounds, caliberLabel: cal, cap: state.cap, full: !!state.full }).map((r) => {
        const pts = rectCorners(L, r);
        const lit = r.face === FACE_STENCIL;
        return {
            pts, uvs: [[0, 0], [0, 1], [1, 1], [1, 0]], normal: windingNormal(pts), image: solid(colourOf(L, tier, r.role, !!state.working)), uvScale: 1,
            cull: true, shade: lit ? null : 1, light: lit ? 0 : 15, tag: 'counter',
        };
    });
}
/** 屏窗中心 (世界像素), 出图取景用。 */
export function displayCentre(L) {
    const W = L.window.rect;
    return facePoint(L, FACE_WINDOW, W.x + W.w / 2, W.y + W.h / 2, 0);
}

// ---------------------------------------------------------------- 对比度 (WCAG 相对亮度), 生成器核对待机字的对比度
const lin = (c) => { c /= 255; return c <= 0.04045 ? c / 12.92 : ((c + 0.055) / 1.055) ** 2.4; };
export const luminance = (rgb) => 0.2126 * lin(rgb[0]) + 0.7152 * lin(rgb[1]) + 0.0722 * lin(rgb[2]);
export function contrast(a, b) { const x = luminance(a), y = luminance(b); return (Math.max(x, y) + 0.05) / (Math.min(x, y) + 0.05); }
