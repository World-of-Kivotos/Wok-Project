package com.miningdim.job.miner;

/**
 * 矿工主动技能 + 偏好开关枚举。用于 {@link MinerToggleC2S} 的开关种类编码与 {@link MinerChargeState} 的
 * per-skill CD 计时键。byte 序号 = ordinal(), 网络编码用; 越界还原由 {@link #byOrdinal(int)} 兜底。
 *
 * 区分两类: ACTIVE (探矿/陷阱探/隧道/脱险/降压 走 CD) 与 TOGGLE (连锁/自动入包/自动熔炼 走开关位)。
 * 连锁既是开关又消耗充能池, 故归 TOGGLE; 它的节流是充能池而非 CD。
 */
public enum MinerSkill {

    /** 矿物探测 (脉冲 + 长 CD)。 */
    ORE_SCAN(true),
    /** 陷阱探测 (脉冲 + 长 CD)。 */
    TRAP_SCAN(true),
    /** 隧道挖 (3x3 一段 + CD)。 */
    TUNNEL(true),
    /** 脱险归途 (读条 + 长 CD)。 */
    EVACUATE(true),
    /** 声东击西 (降压窗口 + 长 CD)。 */
    DECOY(true),
    /** 连锁挖矿 (开关 + 充能池)。 */
    CHAIN(false),
    /** 自动入包 (偏好开关)。 */
    AUTO_COLLECT(false),
    /** 自动熔炼 (偏好开关)。 */
    AUTO_SMELT(false);

    private final boolean active;

    MinerSkill(boolean active) {
        this.active = active;
    }

    /** true = 主动技能 (走 CD); false = 开关/偏好。 */
    public boolean active() {
        return active;
    }

    /** byte 序号反查; 越界返回 null (调用方边界兜底, 不静默掩盖)。 */
    public static MinerSkill byOrdinal(int ordinal) {
        MinerSkill[] vals = values();
        if (ordinal < 0 || ordinal >= vals.length) {
            return null;
        }
        return vals[ordinal];
    }
}
