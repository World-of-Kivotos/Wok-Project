package com.miningdim.economy;

/**
 * 双货币类型 (经济文档 一/1.2/1.3)。全服只有这两种货币, 永不互换 (附录: 货币互换不允许)。
 *
 * CREDIT 信用点: 全服唯一基础货币, faucet = 任务/刷怪/卖矿卖菜, sink = 卡包/箱子/重置/服务费。
 * 仅可经"收手续费 + 落流水审计"的 DB 交易通道 (跳蚤/交易, 经济文档 0.3-45) 在玩家间转移 (0.3-3),
 * 故 {@link #isTransferable()} 为 true。注意: 货币层 {@link IEconomyService} 刻意不提供 P2P 转移
 * (零成本洗钱后门, 0.3-46); 该可转移性只供 DB 交易层判定标的是否合法可挂单。
 *
 * AZURE 青辉石: 点券式高级货币, 仅 >=6 星精英怪 PvE 掉落入账, 硬绑定玩家不可转移不可交易 (1.2/附录)。
 * 绑定即从根上堵死青辉石的 RMT 通道, 故 {@link #isTransferable()} 为 false —— 这是货币的硬不变量,
 * DB 交易层据此对 AZURE 标的直接拒绝挂单 (货币层无 P2P 入口, 不存在在 SavedData 层零流水转移的可能)。
 */
public enum Currency {

    /** 信用点 (基础货币, 可经 DB 交易通道转移)。 */
    CREDIT(true),

    /** 青辉石 (高级货币, 硬绑定不可转移不可交易)。 */
    AZURE(false);

    private final boolean transferable;

    Currency(boolean transferable) {
        this.transferable = transferable;
    }

    /** 该货币是否允许玩家间转移 (青辉石绑定: false)。 */
    public boolean isTransferable() {
        return transferable;
    }
}
