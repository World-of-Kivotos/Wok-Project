package com.miningdim.marriage;

import com.miningdim.economy.Currency;
import com.miningdim.economy.EconomyServices;
import com.miningdim.economy.IEconomyService;
import com.miningdim.entry.IMiningPlayerData;
import com.miningdim.entry.MiningCapabilities;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.UUID;

/**
 * 离婚事务引擎 (结婚系统 spec 第六章)。纯服务端逻辑, 不持状态: 每次从 {@link MarriageRegistry} + {@link MarriageHistory}
 * + capability + 货币门面取权威态结算。三道闸 (spec 第六章): 再婚冷却递增 ({@link MarriageHistory#recordDivorce}) +
 * 离婚成本 ({@link IEconomyService#tryCharge}) + 共享背包清算 (本期简化: 平分/退回发起方 + 审计流水)。
 *
 * 事务顺序 (异常必痛, 不留半成品): 校验已婚 -> 先扣发起方离婚成本 (扣不起整笔失败, 不解除关系) -> 清算共享背包
 * (内容退回发起方; 给不下落地) -> 强制关双方共享背包窗口 + 清会话 -> dissolve Registry -> 清双方 capability 指针 +
 * 回收双方戒指 NBT -> recordDivorce (再婚冷却递增)。任一前置不过即返回失败码, 货币与关系态不变。
 *
 * 配偶离线处理: 配偶可能离线。在线侧 (发起方) capability 直接清; 离线配偶的 capability 指针在其下次登录由
 * {@link MarriageSystem#reconcileMarriagePointer} 经 Registry 反查自愈 (关系已 dissolve, forPlayer 返 null ->
 * 清指针)。离线配偶的戒指无法即时回收 NBT, 同样在登录时校验 (戒指 marriageId 已不在 Registry -> RingItem 显占位)。
 */
public final class MarriageDivorce {

    private static final Logger LOGGER = LoggerFactory.getLogger("miningdim/marriage");

    /** 共享背包清算里程碑 id (审计/去重用; 非一次性福利, 仅审计标记)。 */
    private static final String AUDIT_SETTLE = "shared_backpack_settlement";

    private final ServerLevel overworld;
    private final MarriageBackpackSessions sessions;

    public MarriageDivorce(ServerLevel overworld, MarriageBackpackSessions sessions) {
        this.overworld = overworld;
        this.sessions = sessions;
    }

    /** 离婚结果 (回执)。 */
    public enum Result {
        OK,
        NOT_MARRIED,
        INSUFFICIENT_FUNDS,
        NO_ECONOMY
    }

    /**
     * 办离婚 (spec 第六章; 发起方执行)。
     *
     * @param initiator 发起方 (扣离婚成本对象)
     * @param cost      离婚成本 (信用点; 命令层读 config 传入)
     * @return 离婚结果回执
     */
    public Result divorce(ServerPlayer initiator, long cost) {
        IMiningPlayerData data = MiningCapabilities.get(initiator).orElse(null);
        if (data == null) {
            throw new IllegalStateException("player mining capability missing during divorce");
        }
        if (data.marriageId() == IMiningPlayerData.NO_MARRIAGE) {
            return Result.NOT_MARRIED;
        }
        if (!EconomyServices.isRegistered()) {
            return Result.NO_ECONOMY;
        }

        MarriageRegistry registry = MarriageRegistry.get(overworld);
        MarriageState state = registry.byId(data.marriageId());
        if (state == null || !state.involves(initiator.getUUID())) {
            // capability 指针指向已不存在的关系: 自愈清指针, 视为未婚 (不抛, 与 forPlayer 自愈同纪律)。
            data.setMarriageId(IMiningPlayerData.NO_MARRIAGE);
            data.setSpouseUUID(null);
            return Result.NOT_MARRIED;
        }

        // 先扣发起方离婚成本 (扣不起整笔失败, 不解除关系)。
        IEconomyService eco = EconomyServices.economyService();
        if (cost > 0 && !eco.tryCharge(initiator, Currency.CREDIT, cost)) {
            return Result.INSUFFICIENT_FUNDS;
        }

        long marriageId = state.marriageId();
        UUID a = state.partnerA();
        UUID b = state.partnerB();
        UUID spouseId = state.spouseOf(initiator.getUUID());
        long now = overworld.getGameTime();

        // 清算共享背包: 内容退回发起方 (本期简化"平分/退回发起方"; 谁放入谁取回的逐物流水留候选功能)。给不下落地。
        settleSharedBackpack(state, initiator);

        // 强制关双方共享背包窗口 + 清会话 (内容已清算, 关窗即停一切并发操作)。
        sessions.forceCloseAll(marriageId, overworld);
        sessions.onMarriageDissolved(marriageId);

        // dissolve Registry (移出主表 + 清双方反查索引)。
        registry.dissolve(marriageId);

        // 清发起方 capability 指针 + 回收其戒指 NBT (在线侧即时)。
        data.setMarriageId(IMiningPlayerData.NO_MARRIAGE);
        data.setSpouseUUID(null);
        recycleRings(initiator, marriageId);

        // 配偶若在线: 即时清其 capability + 回收戒指; 离线则登录时自愈 (见类注释)。
        ServerPlayer spouse = overworld.getServer().getPlayerList().getPlayer(spouseId);
        if (spouse != null) {
            IMiningPlayerData spouseData = MiningCapabilities.get(spouse).orElse(null);
            if (spouseData != null) {
                spouseData.setMarriageId(IMiningPlayerData.NO_MARRIAGE);
                spouseData.setSpouseUUID(null);
            }
            recycleRings(spouse, marriageId);
        }

        // 再婚冷却递增 (闸 1; 双方 divorceCount++ 并设冷却截止)。
        MarriageHistory history = MarriageHistory.get(overworld);
        history.recordDivorce(a, b, now);

        LOGGER.info("[marriage] divorce settled: marriageId={} initiator={} spouse={} cost={} audit={}",
                marriageId, initiator.getUUID(), spouseId, cost, AUDIT_SETTLE);
        return Result.OK;
    }

    /**
     * 共享背包清算 (本期简化: 全部退回发起方; 谁放入谁取回的逐物流水分割是候选功能)。逐非空槽取出给发起方,
     * 背包满则落地, 不吞物 (审计日志记总件数)。
     */
    private void settleSharedBackpack(MarriageState state, ServerPlayer initiator) {
        int returned = 0;
        var inv = state.sharedInv();
        for (int slot = 0; slot < inv.size(); slot++) {
            ItemStack stack = inv.get(slot);
            if (stack.isEmpty()) {
                continue;
            }
            inv.set(slot, ItemStack.EMPTY);
            returned += stack.getCount();
            if (!initiator.getInventory().add(stack)) {
                initiator.drop(stack, false);
            }
        }
        if (returned > 0) {
            LOGGER.info("[marriage] shared backpack settlement: marriageId={} returnedItems={} to={}",
                    state.marriageId(), returned, initiator.getUUID());
        }
    }

    /**
     * 回收某玩家背包内属于该 marriageId 的结婚戒指 NBT (spec 第六章: 离婚回收戒指 NBT)。把盖了本关系 marriageId 的
     * 结婚戒指换回空白订婚戒指 (NBT 清除身份盖章, 不再白嫖已解除关系的福利; 戒指物归还玩家, 不没收)。
     */
    private void recycleRings(ServerPlayer player, long marriageId) {
        var items = player.getInventory().items;
        for (int i = 0; i < items.size(); i++) {
            ItemStack stack = items.get(i);
            if (stack.getItem() instanceof RingItem ring && !ring.isEngagement()
                    && RingItem.marriageId(stack) == marriageId) {
                // 结婚戒指 -> 空白订婚戒指 (清盖章; 物归玩家)。
                player.getInventory().setItem(i,
                        RingItem.createEngagement(com.miningdim.registry.ModItems.ENGAGEMENT_RING.get()));
            }
        }
    }
}
