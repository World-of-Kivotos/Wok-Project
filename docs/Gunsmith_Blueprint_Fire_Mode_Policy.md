# 枪匠图纸射击模式策略

## 事故原因

枪匠装配流程曾通过 builder 生成成品枪数据，却没有在 builder 初始化阶段写入源枪的当前射击模式。builder 的默认值随后被写为 `UNKNOWN`，导致成品枪虽然仍引用完整的原枪模式列表，实际射击表现却异常。该问题不能用硬编码半自动掩盖：模式列表的内容、顺序和当前模式初始值都是枪械数据的一部分。

## 当前修复

装配时以源枪射击模式列表为真源。`GunsmithFireModePolicy` 提供不依赖 TACZ 类型的公开静态泛型校验；下列四条是普通装配路径 `preserveAndSelectFirst` 的规则，强制三连发分支另见“三连发枪机例外”一节：

1. 源模式列表必须非空。
2. 成品模式列表必须非空。
3. 两个列表必须长度相同、逐项相等且顺序相同。
4. 校验通过后返回源列表第一项，作为成品当前射击模式的初始值，与 TACZ 原生制枪逻辑一致。

任一条件不满足即拒绝装配结果，不能回退到 `UNKNOWN`，也不能自行补齐、排序或去重。

## 未来图纸硬规则

- 每一张普通 `GunsmithBlueprint` 必须让 `assembledGunId` 等于该图纸的 `gunId`；新图纸不得把输出悄悄指向替代枪数据。
- 每一张图纸的成品必须与其源枪逐项保留射击模式。`auto, semi` 与 `semi, auto` 是不同数据，不能视为等价。
- 普通装配路径（`preserveAndSelectFirst`）下，成品缺少任一源模式、增加源枪没有的模式、列表为空，或顺序发生变化，均为数据错误，必须拒绝。该条不适用于下文的强制三连发分支。
- legacy M4 的自定义枪械数据同样适用本规则：其模式列表必须与对应源枪逐项、按原顺序完全一致，不能因兼容旧模板而放宽校验。
- 策略层只处理泛型模式值与列表比较；TACZ 类型转换留在桥接层，避免把第三方类型扩散到业务规则和 GameTest 中。

当前已核对：HK416D 与 M16A1 保留 `auto, semi`，初始为 `auto`；M16A4 保留 `burst, semi`，初始为 `burst`。

## 三连发枪机例外

上节硬规则只约束普通装配路径。装配用枪机的 `variant().forcesBurstFireMode()` 为真时（当前唯一型号是改装级的 AR三连发枪机 `ar_three_round_burst_bolt`），`GunsmithAssemblyRecipe.assembledGunId(stack, parts)` 不再返回图纸自身的 `gunId`，而是改指向 `GunsmithGunFactory.burstGunId(blueprint)` 给出的 `miningdim:{templateId}_gunsmith_burst` 替代枪数据。该分支只对 AR 平台开放，其他平台传入 `burstGunId` 直接抛异常。

此时装配改走 `GunsmithFireModePolicy.forceThreeRoundBurst`，校验规则为：

1. 源模式列表与成品模式列表仍必须非空且不含 `null`。
2. 成品模式列表必须恰好等于 `[burst]`，不要求与源枪逐项相等；源枪的 `auto`、`semi` 被替代枪数据成建制丢弃，是本分支的预期结果而非数据错误。
3. 替代枪数据的 `burst_data.count` 必须为 `3`。
4. 替代枪数据的 `burst_data.continuous_shoot` 必须为 `false`，即每轮点射都要求重新扣扳机。
5. 校验通过后返回 `burst`，作为成品当前射击模式的初始值。

任一条件不满足同样拒绝装配：`GunsmithGunFactory` 记一条 ERROR 并返回 `ItemStack.EMPTY`，不回退到普通装配路径，也不自行补齐模式。

替代枪数据与图纸是两份独立数据：图纸保存的 `gunId`（如 `tacz:m16a1`）与 TaCZ 源枪数据都不被改写，四张 AR 图纸各自配一份 `{templateId}_gunsmith_burst_data.json`（M4A1、M16A1、M16A4、HK416D），其 `fire_mode` 固定为 `["burst"]`。

## 验证清单

- `auto + semi` 对 `auto + semi` 校验通过并返回 `auto`。
- `burst + semi` 对 `burst + semi` 校验通过并返回 `burst`。
- `semi + burst` 对 `semi + burst` 校验通过并返回 `semi`，证明第一项与顺序均被保留。
- 成品缺少模式时抛出异常。
- 成品增加源枪不存在的模式时抛出异常。
- 成品模式顺序变化时抛出异常。
- 源与成品模式列表为空时抛出异常。
- 图纸目录遍历断言所有普通图纸的 `assembledGunId == gunId`。
- 强制三连发分支在成品模式列表恰为 `[burst]`、`burstCount` 为 `3`、`continuousBurst` 为 `false` 时校验通过并返回 `burst`。
- 成品模式列表除 `burst` 外还保留第二个模式时抛出异常。
- `burstCount` 不为 `3` 时抛出异常。
- `continuousBurst` 为 `true` 时抛出异常。

上述 GameTest 使用字符串模式列表验证纯业务策略；它不加载，也不要求开发环境加载 TACZ。
