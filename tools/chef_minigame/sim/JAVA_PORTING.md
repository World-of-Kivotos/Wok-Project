# 掌勺内核 · Java 1:1 复刻说明（simVersion 2）

> 权威实现是 `sim.js`（规则来源 `../spec.md` §3–§14、§18.2、§18.4、§18.7、§19）。
> Java 参考实现已经同步到 v2 并对拍通过：`java/ZhangshaoKernel.java`（内核）、`java/ZhangshaoConfig.java`（服务端构造参数块）、`java/GoldenCheck.java`（无依赖对拍程序）。
> 对拍结果（JDK 17）：`golden.json` 的 PRNG/哈希向量 + 15 组用例全部逐位一致；另用同格式随机生成 1,885 组用例（4 台 × ★1–7 × L1–10 × 5 性子 × 手熟/批量/翻面数/拍数/冷饮/改过配置 × 13 种输入源 × 随机 `ext.gold`）也全部一致（未入库）。
> 移植进主 MOD 时**直接拷这三个文件**（改包名即可），再按本文 §10 把 `GoldenCheck` 换成 JUnit / GameTest。

```
javac -encoding UTF-8 -d out java/*.java
java -cp out com.miningdim.job.chef.zhangshao.GoldenCheck golden.json
# → OK: vectors + 15 cases match golden.json
```

## v1 → v2 改了什么（Java 侧要动的地方）

| 位置 | v1 | v2 |
|---|---|---|
| `SIM_VERSION` | 1 | 2 |
| 参数块 | 139 项（A 27 + B 112） | **144 项（A 32 + B 112）**：A #15 `normalPm` → `bottlePm`（有瓶‰ = 普通 + 金，封顶 1000）；#16 `goldPm` 内核不再读；新增 #27–31 `bFill`、`bAppearMin`、`bAppearMax`、`bOffMin`、`bOffMax`。B 段删 `appearMin/appearMax/offsetMin/offsetMax/fill/flipEarlyZone`，加 `oilHotRiseDiv`、`flipEarlyGoodTicks/MildTicks/ZoneTicks`、`penEarlyMild`、`negEarlyMildUnder` |
| 开局 rB | `kindRoll` 定种类 | `bottleRoll < bottlePm` 只定「有没有瓶」（`bKind` 0/1）；`bAt`、`bOff` 用 A 段的范围。抽签次数不变（4 个） |
| 瓶的种类 | 种子决定 | **服务端 `SecureRandom` 私下抽**，结算时作为 `Ext.gold` 传入（不进种子、参数块、状态哈希） |
| 抓瓶 | `K += fill` | `K += bFill`（= fill × MAST / 1000） |
| 炸锅升温 | `T += rise` | `T ≥ oilHot` 时 `rise = rise / oilHotRiseDiv` 再加 |
| 烤炉提示前按 | `P ≥ mark − flipEarlyZone` → 翻早 | 按 `d = mark − P` 与 `g0 = fullGain(p)` 分三档（§6 表） |
| 结算 | `bKind == GOLD` | `gold = bKind != NONE && ext.gold != 0`；`bottleKind` 由它算 |
| 默认性子 | 烤炉沉 | 烤炉混（`defaultMotion`） |
| 配置默认值 | — | 见 `ZhangshaoConfig` 字段（spec §20 已更新） |

---

## 0. 速查：JS ↔ Java 对照

| sim.js | Java | 说明 |
|---|---|---|
| `buildParams(opts, config)` | `new ZhangshaoConfig().buildParams(Options)` | 服务端开局时算参数块 int[144] |
| `decodeParams(block)` | `new Params(int[])` | 内核只读参数块，不读配置 |
| `initState(block, seed)` / `createGame(opts)` | `init(Params, int seed)` | 开局，所有抽签都在这里 |
| `stepInPlace(s, input)` | `step(State, int input)` | 推进 1 tick（就地） |
| `step(s, input)`（纯函数版） | `s.copy()` + `step` | JS 的纯函数版只是先浅拷贝 |
| `hashState(s)` | `hash(State)` | §19.3 逐字 FNV-1a |
| `fullGain(p)` | `fullGain(Params)` | 满速涨速（烤炉翻早分档用） |
| `result(s, ext)` | `result(State, Ext)` | 结算，字段同名同序（`Result.toMap()`）；`ext.gold` = 服务端私下抽的瓶种类 |
| `detectMechanical(inputs, p, count)` | `detectMechanical(int[], int count, Params)` | §18.7 本局机械节奏 |
| `replayTrace(cfg, inputs, ext)` | `replay(Params, seed, int[], Ext)` | 服务端回放 |
| `abandon(s)` | `abandon(State)` | 撂勺 |
| `hex32(h)` | `hex32(h)` = `String.format("%08x", h)` | 哈希显示 |

---

## 1. 类型与运算：逐条对照

全部状态、参数、中间量都是 **32 位有符号 int**。Java 端**不用** `long`、`float`、`double`（`long` 会掩盖本该一致的回绕；浮点会算岔）。

| JS 写法 | Java 写法 | 要点 |
|---|---|---|
| `x \| 0` | （不写） | JS 用它把 double 收回 int32；Java 的 int 天然如此 |
| `(a / b) \| 0`（a、b ≥ 0） | `a / b` | |
| `tdiv(a, b)`（带符号，b > 0） | `a / b` | **Java 整除本来就向零截断**，与 tdiv 完全相同。不要用 `Math.floorDiv` |
| `Math.imul(a, b)` | `a * b` | 只出现在 PRNG 与哈希里，依赖回绕 |
| `a >>> k` | `a >>> k` | 只出现在 PRNG、哈希、`a8` 位操作、`kickBits` |
| `(input >> 1) & 1` | `(input >> 1) & 1` | 唯一的 `>>`，input 非负。**不要用 `>>` 当除法**（对负数是向下取整） |
| `a % n` | `a % n` | 只对非负数取模（`next()>>>1`、`a8>>>2`） |
| `Math.min/max/abs` | `Math.min/max/abs` | 都是 int 版本；`abs` 的参数绝不会是 `Integer.MIN_VALUE` |
| 布尔状态 0/1 | `int` 0/1 | `inPrev`、`perfect`、`inSeg`、`flipAllGood` 等都存 int（进哈希）；`inBar`、`pinned`、`bIn`、`burst` 只是本 tick 的局部量，Java 用 `boolean` 无妨 |
| `-x`（x 可能为 0） | `-x` | JS 会产生 `-0`，`\| 0` 后即 0；Java 没有这个问题 |

### 1.1 最大中间值（全部 < 2^31 = 2,147,483,648，默认参数下）
| 表达式 | 上界 |
|---|---|
| `(a3 − yt) × pct` | 799,999 × 99 ≈ 7.92×10^7 |
| `(101 + 2D) × dartUnit` | 301 × 1,760 = 529,760 |
| `D × steadyMul` | 100 × 20 = 2,000 |
| `accel × inBarNum` | 3,960 × 6 = 23,760 |
| `vb × bounceTopNum`（|vb| 无限速，但轨道有限：≤ √(2×3960×10^6) ≈ 89,000） | ≈ 1.8×10^5 |
| `vt`（追目的地 ≤ 800,000/10 + 炸锅/烤炉溅射） | < 2×10^5 |
| `GAIN × mul`（mul ≤ 1,300） | 4,689 × 1,300 ≈ 6.1×10^6 |
| `g × PACE`、`g × MAST`、`g × BPACE` | < 1.1×10^7 |
| `g0 × flipEarlyZoneTicks`（g0 ≤ 8,205） | 1.6×10^5 |
| `h × heatMulSpan` | 1,000 × 600 = 6×10^5 |
| `(progressMax − P0) × k`（k ≤ 8） | 750,000 × 8 = 6×10^6 |
| `baseCapTicks × 1,000,000`、`stationCapPm × BPACE` | 3×10^8、1.25×10^6 |
| `fill × MAST`、`appearMax × 1000`、`H × offsetMaxPm`（服务端构造参数块时） | 4.8×10^7、6×10^4、2.24×10^8 |
| `I × 1000`、`hot × 1000`、`hSum`（S ≤ CAP ≤ 1,136） | ≈ 1.14×10^6 |
| `P + g`、`P − 60,000 − l`、`K + bFill` | 约 −7×10^4 … 1.05×10^6 |

服务器改配置时，`ZhangshaoConfig` 加载后要按 spec §20.14 做跨键校验（sim.js 的 `validateConfig` 是参考），其中「中间积 < 2^31」用最大参数代入上表逐条检查。

---

## 2. 随机数（spec §4.1、§18.2）

```java
public static int fmix32(int h) { h ^= h >>> 16; h *= 0x85EBCA6B; h ^= h >>> 13; h *= 0xC2B2AE35; h ^= h >>> 16; return h; }

public static final class Mulberry32 {
    public int a;
    public int next() {
        a += 0x6D2B79F5;
        int t = (a ^ (a >>> 15)) * (1 | a);
        t = (t + (t ^ (t >>> 7)) * (61 | t)) ^ t;
        return t ^ (t >>> 14);
    }
    public int rnd(int n) { return (next() >>> 1) % n; }          // n > 0
    public int range(int lo, int hi) { return lo + rnd(hi - lo + 1); } // 含两端
}
```

- 十六进制字面量 `0x85EBCA6B`、`0xC2B2AE35`、`0x811C9DC5` 在 Java 里是合法的**负 int**，直接写即可。
- 三条子流：`rT = fmix32(seed ^ 0x0F00D5)`、`rB = fmix32(seed ^ 0x0B0771E)`、`rS = fmix32(seed ^ 0x057A7)`。`seed` 是 int32（服务端 `SecureRandom.nextInt()`）。
- **抽签纪律（与输入无关）**：

| 流 | 何时 | 抽几次 | 顺序 |
|---|---|---|---|
| rT | 开局 | 1 | `tgt = range(fishMin, fishMax − 1)` |
| rT | 每个正式 tick（摆料段不抽） | `fishSubsteps × 8` = 24 | 每子步：`a1 rnd(newDestDiv)`、`a2 range(pctRandMin, pctRandMax)`、`a3 range(fishMin, fishMax − 1)`、`a4 range(approachDivMin, approachDivMax)`、`a5 rnd(hopChanceDiv)`、`a6 range(hopMin, hopMax)`、`a7 rnd(dartChanceDiv)`、`a8 next()` |
| rB | 开局 | 4 | `bottleRoll rnd(1000)`、`bAt range(bAppearMin, bAppearMax)`、`bOff range(bOffMin, bOffMax)`、`bDir rnd(2)` |
| rS | 开局 | 9 | 8 × `jit[k] = beatJitter[rnd(3)]`，然后 `kickBits = next()` |

  8 个数**必须全部抽完再用**——哪怕分支用不到（`a6`、`a8` 经常用不到）。任何「需要时才抽」的写法都会让抽签次数依赖输入（炸锅爆油、烤炉翻面会改变食材速度）。
- **瓶的种类不走这三条子流**：服务端开局时另用 `SecureRandom`，有瓶时 `gold = secure.nextInt(bottlePm) < goldPm`，存在服务端对局里，结算传 `new Ext(gold, slow, mechanical, suspect)`。客户端内核永远拿不到它（只在瓶出现时收到 `ZhangshaoBottle` 包用来画金光）。
- golden.json 的 `vectors` 给了 Mulberry32（含 `0`、`−1`、`Integer.MIN_VALUE` 等边界状态）、`rnd/range`、`fmix32`、三条子流种子、FNV-1a 的已知答案。

---

## 3. 参数块（spec §18.4）

`int[144]`：A 段 #0–31 是本局派生量（服务端按星级/等级/手熟/台子/批量算好），B 段 #32–143 是规则常量（按 §20 各表自上而下的顺序；**只收内核与结算会读的键**，A 段已按级/按星解析掉的数组和瓶参数不重复放，网络/流程/经济类键不放）。客户端**只读参数块**，所以服务器改配置不用玩家更新模组。`paramsHash = fnv1a(block)`。

### 3.1 A 段（`ZhangshaoConfig.buildParams` 逐项）
| # | 名 | 推导（L = 厨师等级，m = 手熟，N = 份数，MAST = 1000 + masteryGainPm × m） |
|---|---|---|
| 0 | simVersion | 2 |
| 1 | station | 0 厨锅 / 1 炸锅 / 2 烤炉 / 3 备餐台（冷饮也是 3） |
| 2 | star | 1–7 |
| 3 | level | 1–10 |
| 4 | motion | 0 混 / 1 窜 / 2 稳 / 3 沉 / 4 浮；没指定时：炸锅浮、冷饮浮、其余混 |
| 5 | D | `clamp(starDifficulty[★−1] + motionOffset[motion], 0, 100)` |
| 6 | H | `max(heightMin, heightBase + heightPerLevel × (L−1))` |
| 7 | P0 | `progressStart + masteryStartBonus × m` |
| 8 | GAIN | `gain` |
| 9 | LOSS | `L ≥ perkPatienceLevel ? lossPatience : loss` |
| 10 | PACE | `stationPacePm[station]` |
| 11 | MAST | `1000 + masteryGainPm × m` |
| 12 | N | 1–6 |
| 13 | BPACE | `batchPacePm[N−1]` |
| 14 | CAP | `baseCapTicks × 1,000,000 / (stationCapPm[station] × BPACE)` |
| 15 | bottlePm | `min(1000, normal + gold)`，normal = `normalBase + normalPerLevel × (L−1)`，gold 见 #16 |
| 16 | goldPm | `min(gold, bottlePm)`；gold = `N > 1` 或 被冷却/日上限/可疑分封掉 → 0，否则 `goldBase + goldPerLevel × (L−1)`。**内核不读**，给界面和服务端私下抽签用 |
| 17 | drainPm | `L ≥ perkSharpEyeLevel ? drainSharpEye : drain` |
| 18 | BB | `L ≥ perkSteadyHandsLevel ? 1 : bounceBottomNum` |
| 19 | F | 烤炉：翻面次数（默认 `flipsDefault`）；其它台 0 |
| 20 | flipGood | 烤炉：`flipGoodByLevel[L−1]`；其它台 0 |
| 21 | oilCd | 炸锅：`oilCdByLevel[L−1]`；其它台 0 |
| 22 | beats | 备餐台：冷饮 `drinkBeats`，否则 `clamp(拍数, beatsMin, beatsMax)`；其它台 0 |
| 23 | segs | 备餐台：`segmentsByStar[★−1]`；其它台 0 |
| 24 | beatGap | 备餐台：`beatGapByStar[★−1]`（冷饮再加 `drinkGapDelta`）；其它台 0 |
| 25 | jitterOn | 备餐台：冷饮 0、否则 1；其它台 0 |
| 26 | goodW | 备餐台：`beatGoodWByLevel[L−1]`；其它台 0 |
| 27 | bFill | `fill × MAST / 1000` |
| 28 | bAppearMin | `appearMin × 1000 / MAST` |
| 29 | bAppearMax | `appearMax × 1000 / MAST` |
| 30 | bOffMin | `H × offsetMinPm / 1000` |
| 31 | bOffMax | `H × offsetMaxPm / 1000` |

「其它台填 0」是规范的一部分（参数哈希要一致）。#27–31 所有台子都算（没瓶也算，哈希要一致）。

### 3.2 B 段（下标 = 名）
```
32 track             33 accel             34 inBarNum          35 inBarDen          36 bounceTopNum
37 bounceDen         38 pinTicks          39 fishMin           40 fishMax           41 sinkLine
42 floatLine         43 fishSubsteps      44 newDestDiv        45 steadyMul         46 pctRandMin
47 pctRandMax        48 approachDivMin    49 approachDivMax    50 approachRelax     51 arriveEps
52 hopChanceDiv      53 hopMin            54 hopMax            55 dartChanceDiv     56 dartMin
57 dartUnit          58 driftStep         59 driftMax          60 progressMax       61 flipHoldCap
62 perfectGraceTicks 63 scoreBonus        64 heatStart         65 heatUp            66 heatDown
67 heatMax           68 heatMulBase       69 heatMulSpan       70 penDiv            71 underDiv
72 oilStart          73 oilRiseIn         74 oilRiseOut        75 oilRiseStarDiv    76 oilRiseProgDiv
77 oilHotRiseDiv     78 oilDrop           79 oilCold           80 oilHot            81 oilBurst
82 oilAfterBurst     83 coldMulPm         84 hotMulPm          85 burstProgressLoss 86 burstKick
87 penHotDiv         88 penColdDiv        89 penBurst          90 negHotDiv         91 negColdDiv
92 negBurst          93 cleanTicks        94 flipLate          95 flipAuto          96 flipEarlyGoodTicks
97 flipEarlyMildTicks 98 flipEarlyZoneTicks 99 burnMulPm       100 flipKickBase     101 flipKickPerStar
102 penLate          103 penEarlyMild     104 penEarly         105 penBurntBase     106 penBurntCap
107 penAuto          108 negEarlyMildUnder 109 negEarlyUnder   110 negLateOver      111 negBurntBase
112 negBurntPerTick  113 negBurntCap      114 negAutoOver      115 beatJitter0      116 beatJitter1
117 beatJitter2      118 beatGapMin       119 beatLead         120 beatTail         121 beatPerfectW
122 penOk            123 penMiss          124 penMash          125 negMissPm        126 negMash
127 tierExtraordinary 128 tierHigh        129 tierMedium       130 timeoutCap       131 batchCap
132 slowCap          133 mechanicalCap    134 thresholdLow     135 thresholdMedium  136 thresholdHigh
137 maxLow           138 maxMedium        139 maxHigh          140 mechanicalMinRuns 141 mechanicalSamePct
142 suspectCapRadiant 143 suspectCapHigh
```
默认值见 `sim.js` 的 `DEFAULT_CONFIG` / `ZhangshaoConfig` 字段初值（与 spec §20 一致）。品质码：−1 家常、0 低、1 中、2 高、3 超凡、4 闪耀（`timeoutCap` 等用这套码）。

---

## 4. 状态（spec §4.3、§19.3）

| 类别 | 字段 | 说明 |
|---|---|---|
| 进哈希（顺序即哈希顺序） | `tick, pt, end, inSeg, segIdx, segT, bi, yb, vb, inPrev, pin, yt, vt, tgt, ad, P, S, I, U, O, outRun, perfect, bKind, bState, bPos, K, h, hSum, T, cd, hot, cold, bursts, flipIdx, prompt, burn, flipPen, flipUnder, flipOver, flipAllGood, perfB, okB, miss, mash, kickN, rT.a, rB.a, rS.a` | 48 个 int（v2 字段没变，`bKind` 的含义变了） |
| 开局后只读 | `bAt, bOff, bDir, kickBits, jit[8], segBeats[][]` | 由种子决定；不进哈希（它们由 seed 与参数唯一确定） |
| 展示计数（不进哈希，进结算结果） | `nGood, nLate, nEarly, nBurnt, nAuto` | 烤炉各评价次数（提示前 3 tick 内按算进 `nGood`，翻早两档都算进 `nEarly`） |

码值：`end` 0 进行中 / 1 出锅 / 2 糊锅 / 3 到点 / 4 撂勺；`bState` 0 等待出现 / 1 在场 / 2 已抓到 / 3 本局无瓶；**`bKind` 0 无瓶 / 1 有瓶（种类内核不知道）**；结算结果的 `bottleKind` 才用 0 无 / 1 普通 / 2 金；`tgt == −1` 表示没有目的地。

初值（`init` 里逐个赋值，**不依赖台子**）：`yb = (track − H) / 2`、`inPrev = 1`、`yt = track / 2`、`tgt = NONE`（随后被开局第一跳覆盖）、`P = P0`、`perfect = 1`、`h = heatStart`、`T = oilStart`、`prompt = −1`、`flipAllGood = 1`，其余 0。

---

## 5. `init(Params, seed)`（spec §4.2）

1. 建三条子流（§2）。
2. `tgt = rT.range(fishMin, fishMax − 1)`。
3. rB 依次：`bottleRoll = rnd(1000)`、`bAt = range(bAppearMin, bAppearMax)`、`bOff = range(bOffMin, bOffMax)`、`bDir = rnd(2)`。`bottleRoll < bottlePm` → `bKind = 1`、`bState = 0`；否则 `bKind = 0`、`bState = 3`。
4. rS：8 个 `jit`，再 `kickBits = next()`。
5. 备餐台节拍表：`segs == 2` 时第一段 `(n + 1) / 2` 拍、第二段其余；每段第一拍在 `beatLead`，之后每拍 `+= max(beatGapMin, beatGap + (jitterOn ? jit[k] : 0))`，`k` 是这一拍在**整局**的序号（0 起，段首拍也占一个序号但不用它的 jit）。v2 默认 `segs` 全是 1，两段的路径由 golden 的 configOverride 用例覆盖。

---

## 6. `step(State, input)`：逐段说明（spec §5，顺序一步不能换）

| 段 | 要点（易错处加粗） |
|---|---|
| 入口 | `end != 0` 直接返回。`hold = input & 1`、`act = (input >> 1) & 1`。**保留位不在这里检查**（由 replay 判撂勺）。 |
| ⓪ 摆料段 | `inSeg != 0` 时只跑 `prepSegmentTick(act)` 然后 `tick++` 返回：**`pt` 不加、不抽 rT、火候条/食材/熟度/瓶全冻结、hold 忽略**。判拍顺序：先判「漏拍」（`t > Tm[bi] + goodW`，一 tick 最多记一个），再判本 tick 的 `act`（`|t − Tm[bi]| ≤ beatPerfectW` 正好 / `≤ goodW` 可以 / 否则乱按且不消耗这一拍）；`segT++` 后 `segT ≥ Tm[c−1] + goodW + beatTail + 1` 即出段、`segIdx++`。 |
| ① stationPre | 炸锅：**先** `if (cd > 0) cd--`，**再** `if (act && cd == 0)` 压火（`T = max(0, T − oilDrop)`、`cd = oilCd`）。烤炉（仅 act）：`prompt ≥ 0` → 按 `dt = pt − prompt` 评正好/稍晚/焦面（焦面扣分用当前 `burn`），然后 `flipIdx++、prompt = −1、burn = 0`、溅射；否则若 `flipIdx < F`：`d = mark(flipIdx+1) − P`、`g0 = fullGain(p)`；**`d ≤ g0 × flipEarlyGoodTicks` → 正好（`nGood++`、`flipIdx++`、溅射，`flipAllGood` 不动）**；`d ≤ g0 × flipEarlyMildTicks` → 翻早·轻（`penEarlyMild`、`negEarlyMildUnder`）；`d ≤ g0 × flipEarlyZoneTicks` → 翻早·重（`penEarly`、`negEarlyUnder`）；两档翻早都 `nEarly++、flipAllGood = 0、flipIdx++`、溅射，**prompt 不动**；更早无效。 |
| ② 火候条 | `top = track − H`。按住且贴边 → `vb = 0`。`a = ±accel`，**用上一 tick 的 `inPrev`** 决定是否 `a = a × inBarNum / inBarDen`。`vb += a; yb += vb`；越顶 `vb = −(vb × bounceTopNum / bounceDen)`，越底用 `BB`。`pin` = 贴边连续计数，`pinned = pin ≥ pinTicks`。 |
| ③ 食材 | `fishSubsteps` 个子步，每步先抽满 8 个数（§2），再按 spec §7.2 的 1–5 步。Java 的 `/` 就是 tdiv。见 §9-1 的 `−1` 怪癖。 |
| ④ 判定 | `inBar = yb ≤ yt ≤ yb + H && !pinned`（判的是**两者都移动之后**）。不在条内的方向：食材在条上方 → 欠火；下方 → 过火；几何上在条内但顶死 → `yb == 0 ? 欠火 : 过火`。 |
| ⑤ 调料瓶 | `bState == 0 && pt == bAt` → 出现：`off = bDir ? bOff : −bOff`，`pos = yt + off`，越界就 `yt − off`，再夹到 `[fishMin, fishMax]`，`bState = 1`。**同一 tick 就开始判抓取**：`bIn = !pinned && yb ≤ bPos ≤ yb + H`；在条内 **`K += bFill`**，`K ≥ 1,000,000` → `K = 1,000,000、bState = 2`；否则 `K = max(0, K − drainPm)`。`bIn` 是局部量。 |
| ⑥ stationMid | 厨锅：`h` 涨/掉后 `mul = heatMulBase + h × heatMulSpan / 1000`。炸锅：`rise = (inBar ? oilRiseIn : oilRiseOut) + (★ − 1) / oilRiseStarDiv + P / oilRiseProgDiv`（**P 是本 tick 更新前的值**）；**`T ≥ oilHot`（加之前的 T）时 `rise = rise / oilHotRiseDiv`**；`T += rise`；然后 爆油（`T = oilAfterBurst`、`bursts++`、溅射、**mul 保持 1000**）/ 过热（`mul = hotMulPm`）/ 冷油 三选一。烤炉：`prompt ≥ 0 && pt − prompt > flipGood + flipLate` → `burn++、mul = burnMulPm`。 |
| ⑦ 熟度 | `g = GAIN; g = g×mul/1000; g = g×PACE/1000; g = g×MAST/1000; g = g×BPACE/1000`（**每步都截断，顺序固定**）；`l = LOSS; l = l×PACE/1000; l = l×BPACE/1000`。框住 `P += g`；否则若 **`bState` 仍为 1（在场）且 `bIn`** → 不变（瓶护；本 tick 刚抓到的瓶已是 2，不护）；否则 `P −= l`。爆油再 `P −= burstProgressLoss`。烤炉 `flipIdx < F` 时 `P = min(P, flipHoldCap)`。 |
| ⑧ 统计 | `S++`；框住 `I++、outRun = 0`；否则按方向 `U++` 或 `O++`、`outRun++`，`outRun > perfectGraceTicks` → `perfect = 0`。厨锅 `hSum += h`。 |
| ⑨ stationPost | 烤炉：**先**判自动翻（`prompt ≥ 0 && pt − prompt ≥ flipAuto`），**再**判出提示（`prompt < 0 && flipIdx < F && P ≥ mark(flipIdx+1)` → `prompt = pt + 1`，用的是**自增前**的 pt，所以下一 tick `dt = 0`）。备餐台：`segIdx < segs && P ≥ mark(segIdx+1, segs)` → `inSeg = 1、segT = 0、bi = 0`（下一 tick 起进段）。 |
| ⑩ 收尾 | `inPrev = inBar`；`pt++、tick++`；**依次**判 `P ≥ progressMax`（出锅，P 夹到满）→ `P ≤ 0`（糊锅，P 夹到 0）→ `pt ≥ CAP`（到点）。 |

辅助：`mark(k, n) = P0 + (progressMax − P0) × k / (n + 1)`；`fullGain(p) = ((GAIN × PACE / 1000) × MAST / 1000) × BPACE / 1000`（逐步截断，等于 ⑦ 里 mul = 1000 时的涨速）；溅射 `kick(amt)`：`up = (kickBits >>> (kickN & 31)) & 1; kickN++; vt += up ? amt : −amt`（烤炉 `amt = flipKickBase + flipKickPerStar × ★`，炸锅 `burstKick`）。

---

## 7. `result(State, Ext)`（spec §14）

1. `S' = max(1, S)`；`C = I×1000/S'`；`under = U×1000/S'`、`over = O×1000/S'`、`mess = 0`。
2. 本台：厨锅 `avgHeat = hSum/S'`，`pen = (1000−avgHeat)/penDiv`，`under += (1000−avgHeat)/underDiv`；炸锅 `hotPm/coldPm = hot/cold×1000/S'`，`pen = hotPm/penHotDiv + coldPm/penColdDiv + penBurst×bursts`，`over += hotPm/negHotDiv + negBurst×bursts`，`mess += coldPm/negColdDiv`，`clean = bursts==0 && hot+cold ≤ cleanTicks`；烤炉 `pen = flipPen`、`under += flipUnder`、`over += flipOver`、`clean = flipAllGood`；备餐台 `pen = penOk×okB + penMiss×miss + penMash×mash`，`mess += miss×negMissPm/beats + negMash×mash`，`clean = miss==0 && mash==0`。每个 `/` 都是非负整除，**分别截断后再相加**。
3. `bottle = bState == 2`；**`gold = bKind != 0 && ext.gold != 0`；`bottleKind = bKind == 0 ? 0 : gold ? 2 : 1`**；`score = max(0, C − pen + scoreBonus×bottle)`；`perfect = end==出锅 && perfect && clean`。
4. 品质：糊锅/撂勺 → −1（家常，不挑负面）。否则从上往下：闪耀（perfect && bottle && **gold** && N==1）→ 超凡（出锅 && (perfect || score ≥ tierExtraordinary)）→ 高 → 中 → 低，得 `qualityRaw`；再套上限取最小：到点 `timeoutCap`、`N>1` `batchCap`、`slow` `slowCap`、`mechanical` `mechanicalCap`、`suspect ≥ suspectCapHigh` → 高，否则 `≥ suspectCapRadiant` → 超凡。**到点不是出锅**，所以到点局最多是「高」再封「中」。
5. 负面（只在最终品质 低/中/高）：门槛/条数按品质取；三值按**值降序、相等时 夹生 > 烧焦 > 倒胃**做稳定插入排序，依次挑 `≥ 门槛` 的直到条数满；到点且没挑到夹生 → 追加夹生，若总数 > 2 去掉**原来挑中的最后一条**（即最小的那条）。输出按码 0,1,2 升序。
6. `Result` 字段与 sim.js 同名同序（`toMap()`），golden 逐键比较。

---

## 8. `detectMechanical` 与 `replay`（spec §18.6、§18.7）

- **机械节奏**：只看已消耗输入的 bit0。切成游程，去掉第一段和最后一段；按住段、松开段各自 ≥ `mechanicalMinRuns`（v2：32）段，且各自「出现最多的段长」的段数 × 100 ≥ `mechanicalSamePct`（v2：100，即全部相同）× 段数 → 1。计数用数组（不要依赖 HashMap 遍历顺序）。摆料段内的输入也算在内（hold 位在段内被内核忽略，但仍是玩家的按键节奏）。
- **replay**：从 `init` 开始逐 tick：取第 i 个输入（用完了按 0 补、`padded++`）；**保留位非 0 → `end = 4`（撂勺）、该字节不算已消耗、立即停止**；否则 `step`、`i++`；每当 `tick % 20 == 0` 记一次 `hex32(hash)`。结束后 `mechanical = ext.mechanical || detectMechanical(已消耗输入, consumed)`，`result(state, {gold, slow, mechanical, suspect})`。golden 的输入恰好在结束那一 tick 用完（`consumed == inputCount`、`padded == 0`）。
- 服务端每收到一包就推进一段，用的就是这个循环的增量版本；包的 RLE / 防快进 / 防慢放（v2：每包滞后检查 + 终局 ×1.1 + 2 秒）/ 断流在内核外（spec §18.6）。
- **客户端**：掉帧时一帧里把欠的 tick **一次补跑完**（不要像 v1 网页原型那样每帧封顶 250 ms，那等于悄悄慢放）；客户端自己停住超过 5 秒就按撂勺。

---

## 9. 不要「修」的怪癖（两端必须同样处理）

1. **`tgt` 的 −1 撞车**：小跳 `yt − a6` 或急窜 `yt − size` 恰好算出 −1 时与 `NONE` 同值，于是不夹取、被当成「没有目的地」。概率极低（约 1/88,000 的小跳在贴底时），规格原文就是这样写的。Java 必须同样用 `int tgt == -1` 判定，**不要**改成 `boolean hasTarget`。
2. 到点那一 tick 若恰好也满足「进摆料段」条件：先置 `inSeg = 1`，随后 ⑩ 判到点结束，这一段不会被执行，节拍不记漏拍。
3. 瓶的出现用的是 `pt`（正式 tick），摆料段不推进 `pt`，所以瓶永远出现在正式段里。
4. 摆料段里按动作键：拍都判完后再按 → 忽略（不算乱按）；按在两拍窗口之间 → 乱按。
5. 炸锅压火冷却：`cd` 在 stationPre 先减再判，所以「`cd == 1` 的 tick 按下」是有效的。
6. `step` 不检查保留位；只有 `replay` 把非法字节判成撂勺。
7. 烤炉提示前按的「正好」：`d` 可能 ≤ 0 的情况在正常推进里不会出现（P 越过翻面点的那一 tick 的 stationPost 就会挂提示），但 `d ≤ g0 × 3` 的判法对 `d ≤ 0` 也成立，不用特判。
8. 炸锅升温减半看的是**加之前**的 T：从 748 升上来的那一 tick 不减半。

---

## 10. golden.json 与 Java 测试

格式：
```jsonc
{
  "simVersion": 2,
  "paramFields": [...144 个名字...], "hashFields": [...48 个名字...],
  "vectors": { "mulberry32": [{"state", "next":[6], "stateAfter"}], "rndRange": {...}, "fmix32": [[in, out]], "streams": [...], "fnv1a": {...} },
  "cases": [{
    "name", "description",
    "config": {"station","star","chefLevel","motion",...},   // 测 ZhangshaoConfig.buildParams
    "configOverride": {"segmentsByStar": [...]},             // 可选：覆盖配置字段（Java 用反射改 ZhangshaoConfig 的同名字段）
    "seed", "params": [144 个 int], "paramsHash", "initHash",
    "ext": {"gold": 0 或 1},                                 // 服务端私下抽的瓶种类，回放与结算时传入
    "inputCount", "inputsRle": [[长度, 字节], ...],
    "hashes": ["每 20 tick 的状态哈希", ...], "finalHash",
    "result": { ...与 Result.toMap() 同键... }
  }]
}
```

15 组用例覆盖：厨锅正常出锅（中品质带负面、抓到瓶）、新手到点（300 tick 封顶「中」补夹生）、批量 6 份封顶「高」、每 tick 交替宏被机械节奏封顶「中」、炸锅多次爆油（过热区慢升后再爆）、炸锅每 3 秒瞟一次油温（过热斜坡、不爆油）、炸锅完美 + 金瓶（`ext.gold = 1`）= 闪耀、烤炉翻 3 面（稍晚/焦面）+ 手熟 5、狂按动作键翻早·重 ×2、提示前 2 tick 预判按 = 正好、提前约 7 tick 按 = 翻早·轻、烤炉自动翻、备餐台一段 7 拍新手、备餐台配置成两段的新手到点（configOverride）、冷饮 6 拍等距。

JUnit 5 + Gson（Forge 环境自带 Gson）示例：
```java
@Test
void zhangshaoGolden() throws Exception {
    JsonObject g = JsonParser.parseString(Files.readString(Path.of("src/test/resources/zhangshao/golden.json"))).getAsJsonObject();
    assertEquals(ZhangshaoKernel.SIM_VERSION, g.get("simVersion").getAsInt());
    for (JsonElement ce : g.getAsJsonArray("cases")) {
        JsonObject c = ce.getAsJsonObject();
        int seed = c.get("seed").getAsInt();
        int[] params = new Gson().fromJson(c.get("params"), int[].class);
        ZhangshaoKernel.Params p = new ZhangshaoKernel.Params(params);
        assertEquals(c.get("paramsHash").getAsString(), ZhangshaoKernel.hex32(p.hash()));
        assertEquals(c.get("initHash").getAsString(), ZhangshaoKernel.hex32(ZhangshaoKernel.hash(ZhangshaoKernel.init(p, seed))));
        IntStream.Builder in = IntStream.builder();
        for (JsonElement run : c.getAsJsonArray("inputsRle")) {
            JsonArray r = run.getAsJsonArray();
            for (int k = 0; k < r.get(0).getAsInt(); k++) in.add(r.get(1).getAsInt());
        }
        ZhangshaoKernel.Ext ext = new ZhangshaoKernel.Ext();
        if (c.has("ext")) ext.gold = c.getAsJsonObject("ext").get("gold").getAsInt();
        ZhangshaoKernel.Trace tr = ZhangshaoKernel.replay(p, seed, in.build().toArray(), ext);
        assertEquals(new Gson().fromJson(c.get("hashes"), List.class), tr.hashes, c.get("name").getAsString());
        assertEquals(c.get("finalHash").getAsString(), tr.finalHash);
        JsonObject want = c.getAsJsonObject("result");
        JsonObject got = new Gson().toJsonTree(tr.result.toMap()).getAsJsonObject();
        assertEquals(want, got, c.get("name").getAsString());
    }
}
```
GameTest 版本同理：`@GameTest(template = "empty")`，从 `getResourceAsStream("/data/miningdim/zhangshao/golden.json")` 读，断言失败 `helper.fail(...)`，最后 `helper.succeed()`。`GoldenCheck.checkCase` 是可以照抄的完整断言列表（含 `ZhangshaoConfig` 参数块构造与 configOverride 的检查）。

spec §19.2 要求的大套件（≥ 50 种子 × 5 输入脚本 × 4 台 × 3 星 × 2 等级 + 边界用例）可以用同一格式由 `gen-golden.mjs` 扩出来；本次交付的 15 组是首批，另用约 1,900 组随机用例在本机做过一次性对拍（未入库）。

---

## 11. 改规则的流程

1. 改 `sim.js`（规则）或 `DEFAULT_CONFIG`（参数）；规则改动必须 `SIM_VERSION + 1`。
2. `node build-inline.mjs`（更新网页内联版）→ `node gen-golden.mjs`（重生 golden）→ `node tests.mjs`。
3. 同步改 `ZhangshaoKernel.java` / `ZhangshaoConfig.java`，跑 `GoldenCheck`（或 CI 里的 JUnit）。
4. 只改参数（不改规则）时 simVersion 不变，但 golden 里的 `params`/`paramsHash` 会变，也要重生。
5. 平衡：`node dish-play.mjs`（菜表改了才需要）→ `node bots.mjs balance 300`（写 balance.md / balance.json，20 项检查要全过）→ 回上级目录 `node build.mjs` 重建网页。

## 12. 评审清单（每次改内核都过一遍）

- [ ] 没有 `long` / `float` / `double` / `Math.round` / `Math.floorDiv` / `Math.floorMod`。
- [ ] 带符号除法只用 `/`；没有用 `>>` 做除法；`%` 的左操作数一定非负。
- [ ] 每个子步先抽满 8 个随机数；rB、rS 开局后不再抽；瓶的种类不进任何子流。
- [ ] tick 内顺序与 spec §5 一致；stationPre/Mid/Post 的先后没动。
- [ ] 新增状态字段同时加进 `hash()`、`copy()`、golden 的 `hashFields`，并升 simVersion。
- [ ] 结算里每个除法单独截断后再相加；负面挑选是稳定排序；`gold` 只来自 `Ext`。
- [ ] golden 全绿。
