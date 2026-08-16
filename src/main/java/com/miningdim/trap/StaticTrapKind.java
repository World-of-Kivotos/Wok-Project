package com.miningdim.trap;

import net.minecraft.util.StringRepresentable;

/**
 * 静态陷阱种类 —— {@link TrapType} 四个 {@link TrapType.Stage#STATIC} 子集的 blockstate 枚举投影。
 *
 * 方案 C (vanilla-noise datapack 迁移, 取代已废弃的离线体素布点): 静态陷阱不再由离线 {@code StaticTrapGenerator}
 * 算体素表, 而是作为 {@link com.miningdim.trap.block.TrapOreBlock} 的 {@code EnumProperty<StaticTrapKind>} 值,
 * 由四个 {@code minecraft:ore} 型 configured_feature 各自指定一种、在真实世界石层散布 (同 ore_emerald 布点范式)。
 * 挖到时 {@link StaticTrapTrigger} 读本枚举分发效果; 探测 {@link com.miningdim.job.miner.TrapScanService} 扫真实世界
 * 识别 TrapOreBlock 并读本枚举还原 TrapType。
 *
 * 与 {@link TrapType} 分离而非直接把 TrapType 做成 EnumProperty: TrapType 含 3 个 DYNAMIC 成员, 若整体做 property
 * 会允许把动态类型当方块布进世界 (语义错误)。本枚举只暴露 4 个可布点的静态类型, 各持一个 {@link #trapType()} 投影。
 */
public enum StaticTrapKind implements StringRepresentable {

    /** TNT 矿脉: 挖到引信方块触发, 反应窗口 30 tick 后非玩家爆炸 (power = TrapType.damage)。 */
    TNT_VEIN("tnt_vein", TrapType.TNT_VEIN),

    /** 岩浆袋: 挖破薄壁后原位喷出一格岩浆, 接触走原版 LAVA 伤害。 */
    LAVA_POCKET("lava_pocket", TrapType.LAVA_POCKET),

    /** 崩塌矿道: 挖到支撑触发, 预警 10 tick 后头顶落砂砾 (FallingBlock, 累计封顶)。 */
    COLLAPSING_TUNNEL("collapsing_tunnel", TrapType.COLLAPSING_TUNNEL),

    /** 假矿石: 挖伪装矿石触发, 非玩家爆炸 (power = TrapType.damage)。 */
    FAKE_ORE("fake_ore", TrapType.FAKE_ORE);

    private final String serializedName;
    private final TrapType trapType;

    StaticTrapKind(String serializedName, TrapType trapType) {
        this.serializedName = serializedName;
        this.trapType = trapType;
    }

    /** 对应的 {@link TrapType} (提供 lethal / reactionWindowTicks / damage / radius)。 */
    public TrapType trapType() {
        return trapType;
    }

    @Override
    public String getSerializedName() {
        return serializedName;
    }

    /** 序列化名反查静态陷阱种类 (调试命令参数解析用); 未知名返回 null, 由调用方转失败文案 (不静默兜底默认种类)。 */
    public static StaticTrapKind byName(String name) {
        for (StaticTrapKind kind : values()) {
            if (kind.serializedName.equals(name)) {
                return kind;
            }
        }
        return null;
    }
}
