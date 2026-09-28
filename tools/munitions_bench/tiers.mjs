// 军火台 (munitions_bench) 六档的 TIER POLICY, 写成数据。零依赖, core.mjs、生成器与预览都用它。
// 由方案评选时的候选脚手架 (.candidates/munitions_common/tiers.mjs) 原样移植。
//
// 政策:
//   机身在每一档都保持中性配色 (枪灰 / 钢 / 警示黄黑 / 黄铜 / 橄榄绿弹药箱)。档位只通过三条通道体现:
//   (1) 指定"饰件"上的饰色 (侧板、色带、铭牌) —— 角色 trim / band;
//   (2) 工作时灯带与发光件的颜色 —— 角色 light / glow;
//   (3) 逐档累加的小五金 (每档在低一档的基础上加 1-2 个小件) —— 见 addOns()。
//   普通 = 不上饰色 (钢色) + 与家族一致的青色灯; 中级绿 #54C879; 高级蓝 #5A9CE8; 极品紫 #A66CE0;
//   超凡红 #E0525C; 闪耀 = 象牙 #EDE6D6 饰板 + 金 #E0B23E 色带, 灯为紫罗兰 #D773FF。
//
// 颜色按"角色"给出 (trim.base / trim.hi / light.on ...), 生成器按角色登记图集色块, 所以六档图集的布局完全相同,
// 同一个元素在每一档用同一组 uv, 只是图集里那一格的颜色不同。
//
// 生产动画的速度 (2026-09 用户拍板: 档位越高动画越快, 均匀阶梯到 2 倍速): 每档一个循环的实际长度 cycleTicks (游戏 tick)
//   普通 40 / 中级 36 / 高级 32 / 极品 28 / 超凡 24 / 闪耀 20 (1× / 1.11× / 1.25× / 1.43× / 1.67× / 2×)。关键帧仍在 40 tick 的
//   程序时间里写, 游戏里按 程序 tick = ((已过 tick mod cycleTicks) + partialTick) × 40 / cycleTicks 映射 (MunitionsBenchProgram.programTick);
//   冲压时刻随之是循环的 1/4 (10 / 9 / 8 / 7 / 6 / 5)。生成器把这张表写进 MunitionsBenchProgram 的 CYCLE_TICKS_BY_TIER /
//   STRIKE_TICKS_BY_TIER (Java 两端与 ber.mjs / lights.mjs 的镜像都读这一份), 并核对: 普通 = 程序长度、逐档变快、冲压时刻是整 tick、
//   最快不超过 2 倍 (皮带追光的瞬时频率 = 1.5 Hz × 倍速, 2 倍正好是光敏上限 3 Hz)。
//
// 角色 (每档都有, 结构相同):
//   trim  {base, hi, lo, dk}          大面积饰板: 侧板、门板、铭牌底框
//   band  {base, hi, lo, dk}          窄色带: 边条、铭牌描边、档位刻痕 (闪耀 = 金; 其余与 trim 同色系)
//   light {on, hi, mid, dim, dk, off} 灯带: on 工作发光, hi 灯芯高光, mid 中间色, dim 待机暗灯, dk 屏幕深底, off 断电
//   glow  {on, soft}                  大面积自发光 (屏幕字、铭牌字、光环): on = 灯色, soft = 压暗一档的底光

const hex = (s) => [parseInt(s.slice(1, 3), 16), parseInt(s.slice(3, 5), 16), parseInt(s.slice(5, 7), 16)];
const mixc = (a, b, t) => a.map((v, i) => Math.round(v + (b[i] - v) * t));
const WHITE = [255, 255, 255], BLACK = [0, 0, 0];
const GUN_DARK = hex('#2E333C'), GUN = hex('#5A6373'), SCREEN_DK = hex('#10151A');

/** 由一个基色推四档明暗 (与材质的 hi/base/lo/dk 同一套比例)。 */
export function shades(base) {
    const b = typeof base === 'string' ? hex(base) : base;
    return { base: b, hi: mixc(b, WHITE, 0.38), lo: mixc(b, BLACK, 0.3), dk: mixc(b, BLACK, 0.55) };
}

/** 由灯色推灯带的六个角色色。 */
export function lightRamp(base) {
    const b = typeof base === 'string' ? hex(base) : base;
    return {
        on: mixc(b, WHITE, 0.18), hi: mixc(b, WHITE, 0.62), mid: b,
        dim: mixc(b, GUN_DARK, 0.55), dk: mixc(b, SCREEN_DK, 0.78), off: mixc(b, GUN, 0.6),
    };
}

// 家族青色灯 (冲压机 / 组装台的实测值), 普通档原样用
const CYAN_LIGHT = { on: hex('#60F4F0'), hi: hex('#C8FFFC'), mid: hex('#24C8D6'), dim: hex('#2C7682'), dk: hex('#163A44'), off: hex('#468C96') };
// 普通档"不上饰色": 饰板 = 钢, 色带 = 镀铬
const STEEL = { base: hex('#B0B9C3'), hi: hex('#D6DDE4'), lo: hex('#7F8994'), dk: hex('#5E6772') };
const CHROME = { base: hex('#C9D1DA'), hi: hex('#EEF2F6'), lo: hex('#8E98A3'), dk: hex('#5F6873') };
const IVORY = { base: hex('#EDE6D6'), hi: hex('#FFFCF4'), lo: hex('#BDB5A3'), dk: hex('#8C8577') };

/** 每档一个生产循环的实际长度 (游戏 tick), 下标 = 档位 0..5 (见文件头 "生产动画的速度")。 */
export const CYCLE_TICKS_BY_TIER = [40, 36, 32, 28, 24, 20];

function tier(index, key, label, en, trim, band, light, note) {
    const L = light;
    return {
        index, key, suffix: index === 0 ? '' : '_' + key, id: 'munitions_bench' + (index === 0 ? '' : '_' + key),
        label, en, note, cycleTicks: CYCLE_TICKS_BY_TIER[index],
        roles: {
            trim, band, light: L,
            glow: { on: L.on, soft: mixc(L.mid, GUN_DARK, 0.3) },
        },
        // 出图里的档位色标 (饰色; 普通档用灯色, 因为它没有饰色)
        swatch: index === 0 ? L.mid : (index === 5 ? band.base : trim.base),
    };
}

/** 六档, 下标 0..5 = 普通..闪耀; suffix 与注册名一一对应 (MunitionsBenchAssets.TIER_IDS 同序), 不可改。 */
export const TIERS = [
    tier(0, 'base', '普通', 'BASE', STEEL, CHROME, CYAN_LIGHT, '无饰色 (钢), 青色灯'),
    tier(1, 'medium', '中级', 'MEDIUM', shades('#54C879'), shades(mixc(hex('#54C879'), WHITE, 0.12)), lightRamp('#54C879'), '绿 #54C879'),
    tier(2, 'high', '高级', 'HIGH', shades('#5A9CE8'), shades(mixc(hex('#5A9CE8'), WHITE, 0.12)), lightRamp('#5A9CE8'), '蓝 #5A9CE8'),
    tier(3, 'superior', '极品', 'SUPERIOR', shades('#A66CE0'), shades(mixc(hex('#A66CE0'), WHITE, 0.12)), lightRamp('#A66CE0'), '紫 #A66CE0'),
    tier(4, 'transcendent', '超凡', 'TRANSCENDENT', shades('#E0525C'), shades(mixc(hex('#E0525C'), WHITE, 0.12)), lightRamp('#E0525C'), '红 #E0525C'),
    tier(5, 'radiant', '闪耀', 'RADIANT', IVORY, shades('#E0B23E'), lightRamp('#D773FF'), '象牙 #EDE6D6 + 金 #E0B23E, 紫罗兰灯 #D773FF'),
];
export const TIER_SUFFIXES = TIERS.map((t) => t.suffix);
export const TIER_KEYS = TIERS.map((t) => t.key);
export const ROLE_GROUPS = { trim: ['base', 'hi', 'lo', 'dk'], band: ['base', 'hi', 'lo', 'dk'], light: ['on', 'hi', 'mid', 'dim', 'dk', 'off'], glow: ['on', 'soft'] };

/** 'high' | '_high' | 2 | 'munitions_bench_high' → 下标。 */
export function tierIndex(t) {
    if (typeof t === 'number') { if (t >= 0 && t < 6) return t; throw new Error('bad tier ' + t); }
    const s = String(t).replace(/^munitions_bench/, '').replace(/^_/, '');
    const i = s === '' ? 0 : TIERS.findIndex((x) => x.key === s);
    if (i < 0) throw new Error('unknown tier ' + t);
    return i;
}

/**
 * 逐档累加的小五金: 当前档及以下各档的构建函数依次执行 (普通档没有加件)。
 *   addOns(ctx.tier, { medium: () => box(...), high: () => ..., superior, transcendent, radiant })
 * 返回实际执行了的档位 key 列表。
 */
export function addOns(tierIdx, perTier) {
    const ran = [];
    for (let i = 1; i <= tierIdx; i++) {
        const k = TIERS[i].key;
        const fn = Array.isArray(perTier) ? perTier[i] : perTier[k];
        if (typeof fn === 'function') { fn(i, TIERS[i]); ran.push(k); }
    }
    return ran;
}

/** tier >= min (min 可写 key 或下标)。 */
export const atLeast = (tierIdx, min) => tierIdx >= tierIndex(min);
