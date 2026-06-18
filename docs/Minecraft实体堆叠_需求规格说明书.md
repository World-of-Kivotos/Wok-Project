# Minecraft 实体堆叠(Mob Stacking)需求规格说明书

- 版本:v1.0
- 适用环境:Forge 1.20.1,纯 Forge 服务端(无 Bukkit/Spigot API 依赖)
- 文档用途:(1) 评估候选 mod 的验收清单;(2) 自建 Forge mod 的实现规格,两者通用
- 约束语义:MUST = 强制,SHOULD = 建议,MAY = 可选;状态用纯文本(PASS/FAIL/待实测)
- 核心机制:范围内同种同状态实体合并为单实体,显示名标注堆叠数 N;主动产出(击杀掉落)与被动产出(剪毛/挤奶/产蛋)均按 N 倍结算

## 一、默认参数表(量化基线)

| 配置键 | 类型 | 默认值 | 说明 |
|---|---|---|---|
| merge.radius.horizontal | int(格) | 5 | 水平合并半径 |
| merge.radius.vertical | int(格) | 3 | 垂直合并半径 |
| merge.trigger | enum | on_move | on_move(跨方块时检测)/ interval |
| merge.scan_interval | int(tick) | 100 | 兜底周期扫描(5s);trigger=interval 时为主扫描周期 |
| merge.max_stack_size | int | 64 | 单实体最大堆叠数,超出另起新堆叠 |
| merge.require_moved | bool | true | 仅对移动过的实体尝试合并,降低静止农场扫描开销 |
| drops.death_mode | enum | instant_all | instant_all(整堆瞬死掉全部)/ one_per_kill(每次击杀剥离 1) |
| drops.loot_roll_mode | enum | per_individual | per_individual(逐个独立 roll)/ multiply_base(base×N,不推荐) |
| drops.multiply_xp | bool | true | 经验按堆叠数倍增 |
| passive.shear.enabled | bool | true | 剪毛倍增 |
| passive.milk.enabled | bool | true | 挤奶倍增 |
| passive.egg.enabled | bool | true | 产蛋倍增 |
| exclusions.named | bool | true | 命名(name tag)实体不参与堆叠 |
| exclusions.tamed | bool | true | 驯服实体(狼/猫/马/鹦鹉等)不参与堆叠 |
| exclusions.boss | bool | true | Boss 不参与堆叠 |
| exclusions.blacklist | list | [] | 按 entity id 排除的实体类型 |

## 二、功能需求(FR)

### FR-1 实体合并

- FR-1.1 (MUST) 合并条件须同时满足:同 entity type、同年龄段(成年/幼年)、同变体维度(羊毛颜色、苦力怕充能态、马花色等)、处于 merge.radius 范围内。
- FR-1.2 (MUST) 年龄隔离:幼年仅与幼年合并,成年仅与成年合并;幼年长大后并入对应成年堆叠。
- FR-1.3 (MUST) 堆叠上限:任一实体堆叠数 ≤ merge.max_stack_size,超出部分形成新堆叠实体。
- FR-1.4 (MUST) 显示名格式 `<本地化实体名> xN`,通过独立显示层(packet 或附加 tag)呈现,不得覆盖玩家 name tag 自定义名。
- FR-1.5 (SHOULD) 合并检测遵循 merge.trigger:on_move 模式仅在实体跨方块时触发,避免每 tick 全量扫描(见 NFR-3)。
- FR-1.6 (MUST) 合并保留实体属性:自定义最大生命、移速、装备、药水效果等不得因合并丢失。

### FR-2 主动产出(击杀掉落倍增)

- FR-2.1 (MUST) 击杀堆叠数为 N 的实体,其掉落与经验等价于击杀 N 个独立同种实体。
- FR-2.2 (MUST) loot_roll_mode=per_individual 时,对 N 个虚拟个体分别独立执行原版 LootTable roll,而非 base_drop × N。此为概率掉落(稀有掉落、抢夺加成)统计正确性的强制要求,是本规格最易被现成 mod 漏掉的一项。
- FR-2.3 (MUST) 掉落物按物品最大堆叠(64)分批生成 ItemEntity,避免单次生成超量实体。
- FR-2.4 (MUST) 经验 = Σ(各虚拟个体经验),受 drops.multiply_xp 控制。
- FR-2.5 (MUST) death_mode=instant_all:一次击杀掉落全部 N 份并移除实体;one_per_kill:每次击杀堆叠数减 1、掉落 1 份,直至 0。
- FR-2.6 (SHOULD) 整堆死亡的环境因素(岩浆、跌落、凋灵效果)按 instant_all 语义结算,不得仅结算 1 份。

### FR-3 被动产出(剪毛 / 挤奶 / 产蛋倍增)

- FR-3.1 (MUST) 剪羊毛:对堆叠数 N 的羊单次剪毛,产出 Σ(每只 1~3 随机)份对应颜色羊毛,N 只同时进入"已剪"冷却态。
- FR-3.2 (MUST) 挤奶:单次交互消耗 min(背包空桶数, N) 个空桶,产出等量奶桶;空桶不足时按实际空桶数产出,余量不产。
- FR-3.3 (MUST) 母鸡产蛋:堆叠数 N 的鸡,产蛋吞吐为单鸡的 N 倍(下蛋计时逻辑按 N 个体并行结算)。
- FR-3.4 (MUST) 冷却与速率按"N 个独立个体"语义结算,严禁因堆叠出现免冷却或无限产出。
- FR-3.5 (MAY) 哞菇剪蘑菇、铁傀儡掉花、雪傀儡掉雪等其它被动产出按同语义扩展。

### FR-4 繁殖

- FR-4.1 (MUST) 对堆叠实体投喂繁殖材料的行为须明确:默认每次投喂触发 1 对个体繁殖、产 1 个幼崽、消耗对应材料。
- FR-4.2 (MUST) 新生幼崽受 FR-1.2 年龄隔离,默认不并入成年堆叠。
- FR-4.3 (MUST) 繁殖产生的个体增长受 merge.max_stack_size 约束,不得因繁殖循环导致单实体堆叠数突破上限。

### FR-5 拆分与交互

- FR-5.1 (MUST) 提供从堆叠中分离单个个体的手段(分离工具 / 指定交互),分离后原堆叠数减 1。
- FR-5.2 (MUST) 拴绳语义须固化:默认作用于整堆或先拆出 1 个,二选一写入配置。
- FR-5.3 (SHOULD) 传送、推动、矿车/船载具的堆叠语义须定义,避免堆叠数在载具交互中丢失或翻倍。

## 三、非功能需求(NFR)

- NFR-1 (MUST) 实体数量收敛:堆叠数趋于 S 的农场,世界同种实体数应降至原始数量约 1/S;量化目标见 AC-9。
- NFR-2 (MUST) TPS 红线:启用后,目标负载(60~80 在线)下整体 TPS ≥ 19.5,MSPT < 50ms。
- NFR-3 (MUST) 扫描复杂度:合并检测严禁每 tick 对全体实体做 O(n^2) 配对;须基于区块本地 / 空间分区,配合 require_moved 与 trigger=on_move 控制扫描频次。
- NFR-4 (SHOULD) 网络收敛:堆叠应减少实体 spawn/move 数据包,降低出口带宽占用。
- NFR-5 (MUST) 线程安全:合并、掉落、产出结算均在服务端主线程执行,不得引入并发修改异常。
- NFR-6 (MUST) 持久化:堆叠数随实体 NBT 持久化;区块卸载重载、服务端重启后堆叠数不丢失(见 AC-8)。

## 四、兼容性与排除(C)

- C-1 (MUST) 纯 Forge 1.20.1,零 Bukkit API 依赖。
- C-2 (MUST) 与性能 mod 栈共存(Embeddium / Canary / Starlight / FerriteCore / Krypton 等),不得冲突崩服。
- C-3 (SHOULD) 提供与第三方实体 mod(如 TACz 等)的兼容排除机制(exclusions.blacklist)。
- C-4 (MUST) 命名实体、Boss、驯服宠物默认排除(对应 exclusions.*)。

## 五、验收标准(AC)

每条均须可断言、可自动化;删除被测核心逻辑后对应用例必须 FAIL。

| 编号 | 场景 | 断言(预期) |
|---|---|---|
| AC-1 | 半径内 spawn 20 只成年羊,等待合并 | 该种实体计数 = 1,显示名 = "Sheep x20" |
| AC-2 | 同点 spawn 10 成年 + 5 幼年羊 | 形成 2 个堆叠(x10、x5),互不合并 |
| AC-3 | 击杀 "Cow x8"(instant_all) | 生牛肉总数 = Σ(8 次独立 roll);ItemEntity 按 ≤64 分批;经验 = 8 × 单牛 |
| AC-4 | 击杀 "Zombie x100",统计概率掉落 | 稀有掉落频次符合 100 次独立 roll 的期望(统计容差内),证明非 base×N |
| AC-5 | 对 "Sheep x16" 剪毛一次 | 羊毛数 = Σ(16 次 1~3 随机);16 只全部进入已剪冷却 |
| AC-6 | "Chicken x10" 在时间窗 T 内产蛋 | 蛋数 = 10 × 单鸡速率(容差内) |
| AC-7 | 设 max_stack=16,持续聚集/繁殖 | 任一实体堆叠数 ≤ 16,超出另起新堆叠 |
| AC-8 | stack=12 的牛,卸载区块重载 + 重启服务端 | 堆叠数仍 = 12 |
| AC-9 | 1000 只动物农场,stack ≥ 16 | 世界实体数 ≤ 100;合并扫描 MSPT 贡献 < 0.5ms(spark 实测);TPS ≥ 19.5 |
| AC-10 | 给 1 只羊挂 name tag;另置 1 只驯服狼 | 二者均不参与堆叠 |

## 六、候选 mod 覆盖度核对表

针对两个 Forge mod 按本规格逐项核对。"待实测"项须在测试服实际验证,严禁凭描述判定通过。

| 需求 | Mob Stacker Ind. (frikinjay) | Mob & Item Stacker (DevDr0ggy) |
|---|---|---|
| FR-1 合并 + 年龄隔离 | 支持(描述含状态保留、命名排除) | 支持(描述含 Age Separation) |
| FR-1.4 不覆盖玩家命名 | 命名实体排除,显示层做法待实测 | 待实测 |
| FR-2 击杀掉落 × N | 保留 loot,倍增机制待实测 | 明确支持 loot × N |
| FR-2.2 per_individual 概率正确性 | 待实测(关键风险项) | 待实测(关键风险项) |
| FR-2.4 经验 × N | 待实测 | 明确支持 XP × N |
| FR-2.3 掉落 ≤64 分批 | 待实测 | 明确支持(分批至 64) |
| FR-3 被动产出(剪毛/挤奶/产蛋)× N | 待实测(描述未提) | 待实测(描述未提) |
| NFR-3 扫描复杂度 | 描述称跨方块触发,实测确认 | 待实测 |
| NFR-6 持久化 | 待实测 | 待实测 |
| API 可扩展(自定义合并/死亡) | 提供 API | 待实测 |

核对结论:两者的"击杀掉落 × N"基本覆盖(Mob & Item Stacker 描述更明确),但 FR-3 被动产出倍增 与 FR-2.2 概率掉落正确性 两项,两个 mod 的公开描述均未承诺,须在测试服实测;若不达标,即为自建 mod 的核心理由。
