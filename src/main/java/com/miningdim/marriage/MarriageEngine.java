package com.miningdim.marriage;

import com.miningdim.economy.Currency;
import com.miningdim.economy.EconomyServices;
import com.miningdim.economy.IEconomyService;
import com.miningdim.entry.IMiningPlayerData;
import com.miningdim.entry.MiningCapabilities;
import com.miningdim.registry.ModItems;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;

import java.util.UUID;

/**
 * 典礼/订婚的事务性结算引擎 (结婚系统 spec 第二/三章)。纯服务端逻辑, 不持状态: 每次调用从
 * {@link MarriageRegistry}(overworld SavedData) + 玩家 capability ({@link MiningCapabilities}) +
 * 货币门面 ({@link EconomyServices}) 取权威态做结算。命令层 ({@link MarriageCommands}) 与 GameTest 均经此引擎。
 *
 * 事务红线 (spec 第三章反小号闸): 典礼成本"双方各付一半信用点", 不可单方扣 —— 先扣 A 半价, 再扣 B 半价;
 * 若 B 余额不足, 立即把已扣的 A 半价 {@link IEconomyService#grant} 退回, 整笔典礼失败, 不留半成品
 * (无 MarriageState 登记 / 戒指仍订婚态 / 双方净额不变)。校验顺序: 自己不能跟自己结 -> 双方均未婚 ->
 * 双方各持订婚戒指 -> 事务扣费 -> 登记关系 + 盖结婚戒指 + 写双方 capability 指针。
 *
 * 经济门面经 {@link EconomyServices} 定位器取用 (与 market/职业同范式), GameTest 可 swap mock 验事务性,
 * 不依赖真经济。
 */
public final class MarriageEngine {

    private final ServerLevel overworld;

    public MarriageEngine(ServerLevel overworld) {
        this.overworld = overworld;
    }

    /** 典礼结果 (回执): 成功携 marriageId, 失败携失败原因枚举。 */
    public record WeddingResult(boolean success, long marriageId, Reason reason) {

        public static WeddingResult ok(long marriageId) {
            return new WeddingResult(true, marriageId, Reason.OK);
        }

        public static WeddingResult fail(Reason reason) {
            return new WeddingResult(false, -1L, reason);
        }
    }

    /** 典礼失败原因 (命令层据此选 lang 文案; 业务结果不吞)。 */
    public enum Reason {
        OK,
        SELF_MARRIAGE,
        ALREADY_MARRIED,
        NO_ENGAGEMENT_RING,
        INSUFFICIENT_FUNDS,
        NO_ECONOMY,
        REMARRY_COOLDOWN
    }

    /** 首次结婚的一次性福利里程碑 id (spec 第六章: 双方 UUID 对去重, 换 marriageId 不重发)。 */
    public static final String MILESTONE_FIRST_MARRIAGE = "first_marriage";

    /**
     * 买一枚订婚戒指 (spec 第二章 /marriage buyring): 事务扣 engagementCost 信用点, 成功则发一枚空白订婚戒指。
     * 余额不足返 false 不扣不发 (事务安全)。
     *
     * @param player 购买者 (服务端)
     * @param cost   订婚戒指成本 (信用点; 由命令层读 config 传入)
     * @return true=扣费成功并发戒指; false=余额不足 (未扣未发)
     */
    public boolean buyEngagementRing(ServerPlayer player, long cost) {
        if (!EconomyServices.isRegistered()) {
            throw new IllegalStateException("economy service not registered; cannot buy engagement ring");
        }
        IEconomyService eco = EconomyServices.economyService();
        if (cost > 0 && !eco.tryCharge(player, Currency.CREDIT, cost)) {
            return false;
        }
        ItemStack ring = RingItem.createEngagement(ModItems.ENGAGEMENT_RING.get());
        // 给不下 (背包满) 时落地, 不吞掉戒指 (玩家已付费)。
        if (!player.getInventory().add(ring)) {
            player.drop(ring, false);
        }
        return true;
    }

    /**
     * 办典礼 (spec 第三章): 事务性结算双方各付一半 weddingCost -> 登记 MarriageState -> 双方订婚戒指换结婚戒指
     * + 写双方 capability 指针。任一前置校验不过或扣费失败则整笔回滚, 不留半成品。
     *
     * @param a              发起方 (typically /marriage wed 的执行者)
     * @param b              对方 (已 accept 的伴侣)
     * @param totalCost      典礼总成本 (信用点; 由命令层读 config 传入); 双方各付 totalCost/2 (奇数余 1 由 a 多付)
     * @param officiant      证婚人 UUID (可空)
     * @return 典礼结果回执
     */
    public WeddingResult wed(ServerPlayer a, ServerPlayer b, long totalCost, UUID officiant) {
        if (a.getUUID().equals(b.getUUID())) {
            return WeddingResult.fail(Reason.SELF_MARRIAGE);
        }
        if (!EconomyServices.isRegistered()) {
            return WeddingResult.fail(Reason.NO_ECONOMY);
        }

        IMiningPlayerData dataA = MiningCapabilities.get(a).orElse(null);
        IMiningPlayerData dataB = MiningCapabilities.get(b).orElse(null);
        if (dataA == null || dataB == null) {
            // capability 缺失是装配缺陷, 非业务态: 暴露不掩盖。
            throw new IllegalStateException("player mining capability missing during wedding");
        }

        MarriageRegistry registry = MarriageRegistry.get(overworld);
        // 双方均须未婚 (capability 指针 + Registry 反查双重校验; 杜绝重婚, spec 第三/七章)。
        if (dataA.marriageId() != IMiningPlayerData.NO_MARRIAGE
                || dataB.marriageId() != IMiningPlayerData.NO_MARRIAGE
                || registry.forPlayer(a.getUUID()) != null
                || registry.forPlayer(b.getUUID()) != null) {
            return WeddingResult.fail(Reason.ALREADY_MARRIED);
        }

        // 再婚冷却闸 (spec 第六章闸 1): 任一方处于离婚后再婚冷却中则拒绝 (冷却随离婚次数递增)。
        long nowForCooldown = overworld.getGameTime();
        MarriageHistory history = MarriageHistory.get(overworld);
        if (history.isOnRemarryCooldown(a.getUUID(), nowForCooldown)
                || history.isOnRemarryCooldown(b.getUUID(), nowForCooldown)) {
            return WeddingResult.fail(Reason.REMARRY_COOLDOWN);
        }

        // 双方各须持一枚订婚戒指 (典礼把它换成结婚戒指; spec 第二章流程)。
        int slotA = findEngagementRingSlot(a);
        int slotB = findEngagementRingSlot(b);
        if (slotA < 0 || slotB < 0) {
            return WeddingResult.fail(Reason.NO_ENGAGEMENT_RING);
        }

        // 事务扣费 (双方各付一半, 不可单方扣): 奇数总价时 a 多付 1 (ceil), b 付 floor。
        long halfA = (totalCost + 1) / 2;
        long halfB = totalCost / 2;
        IEconomyService eco = EconomyServices.economyService();
        if (halfA > 0 && !eco.tryCharge(a, Currency.CREDIT, halfA)) {
            return WeddingResult.fail(Reason.INSUFFICIENT_FUNDS);
        }
        if (halfB > 0 && !eco.tryCharge(b, Currency.CREDIT, halfB)) {
            // B 付不起: 把已扣的 A 半价退回, 整笔失败 (不留半成品)。
            if (halfA > 0) {
                eco.grant(a, Currency.CREDIT, halfA);
            }
            return WeddingResult.fail(Reason.INSUFFICIENT_FUNDS);
        }

        // 扣费成功后才落结构性变更 (登记关系 -> 盖戒指 -> 写指针)。
        long now = overworld.getGameTime();
        MarriageState state = registry.createMarriage(a.getUUID(), b.getUUID(), now);

        String nameA = a.getGameProfile().getName();
        String nameB = b.getGameProfile().getName();
        a.getInventory().setItem(slotA, RingItem.createWedding(
                ModItems.WEDDING_RING.get(), a.getUUID(), nameA, b.getUUID(), nameB,
                state.marriageId(), now, officiant));
        b.getInventory().setItem(slotB, RingItem.createWedding(
                ModItems.WEDDING_RING.get(), b.getUUID(), nameB, a.getUUID(), nameA,
                state.marriageId(), now, officiant));

        dataA.setMarriageId(state.marriageId());
        dataA.setSpouseUUID(b.getUUID());
        dataB.setMarriageId(state.marriageId());
        dataB.setSpouseUUID(a.getUUID());

        // 一次性福利去重 (spec 第六章闸 3): 首次结婚里程碑以"双方 UUID 对"为键, 该对此前已领过 (结离再婚) 则不重发。
        // claimedMilestones 同时记进本段关系 (随 MarriageState 落盘) 与 UUID 对历史 (跨关系去重)。
        if (history.claimPairMilestone(a.getUUID(), b.getUUID(), MILESTONE_FIRST_MARRIAGE)) {
            state.claimMilestone(MILESTONE_FIRST_MARRIAGE);
        }

        return WeddingResult.ok(state.marriageId());
    }

    /** 玩家主背包中第一枚订婚戒指的槽位; 无则 -1。 */
    private static int findEngagementRingSlot(ServerPlayer player) {
        for (int i = 0; i < player.getInventory().items.size(); i++) {
            ItemStack stack = player.getInventory().items.get(i);
            if (stack.getItem() instanceof RingItem ring && ring.isEngagement()) {
                return i;
            }
        }
        return -1;
    }
}
