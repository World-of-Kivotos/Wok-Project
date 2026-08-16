package com.miningdim.marriage;

import com.miningdim.economy.Currency;
import com.miningdim.economy.EconomyServices;
import com.miningdim.economy.IEconomyService;
import com.miningdim.entry.IMiningPlayerData;
import com.miningdim.entry.MiningCapabilities;
import net.minecraft.core.NonNullList;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 离婚事务引擎 (结婚系统 spec 第六章闸 2/3)。纯服务端逻辑, 不持状态: 每次从 {@link MarriageRegistry} +
 * {@link MarriageHistory} + capability + 货币门面取权威态结算。
 *
 * 三段式流程 (提交 -&gt; 公示期 -&gt; 生效), 对应设计文档"仿取款 escrow":
 *  1. {@link #file}: 校验已婚 -&gt; 已存在 pending 则原样回执 (不二次扣费) -&gt; 校验经济已注册 -&gt; 先扣发起方离婚成本
 *     (扣不起整笔失败, 不解除关系) -&gt; {@link MarriageState#beginPendingDivorce} 开公示期 -&gt; 强关双方共享背包窗口
 *     (公示期冻结从这一刻开始) -&gt; 双方提示 -&gt; 若公示期时长配 0 (config 关闭) 立即 {@link #settle}, 退回旧的即时语义。
 *  2. {@link #cancel}: 仅发起方可撤回, 全额退回 {@link MarriageState#pendingDivorceCost()}, 关系照旧存续
 *     (fail closed: 经济未注册时不改任何状态, 不能白退关系不退钱)。
 *  3. {@link #confirm}: 配偶 (非发起方那一位) 可提前确认使其立即生效, 语义是"配偶同意提前生效", 不是"配偶被迫确认"
 *     —— 配偶不做任何事时公示期到期照样由 {@link #finalizeMatured} 结算。
 *
 * 三道闸落点: 闸 1 (再婚冷却递增) 在 {@link #settle} 末尾调 {@link MarriageHistory#recordDivorce}; 闸 2 (成本 +
 * escrow) 即上面的 file/cancel/confirm/finalizeMatured; 闸 3 (共享背包清算) 在 {@link #settleSharedBackpack}
 * 按槽归属分配, 无归属的槽按槽号奇偶确定性平分 (旧存档/非菜单写入路径没有归属记录, 全给发起方正是设计文档点名
 * 要防的"离婚资产抢劫")。
 *
 * 清算口径: 分配出的物品统一进 {@link MarriageHistory} 的待领取清算表, 不直接塞玩家背包 —— 在线方由 settle 内
 * 的 {@link #deliverClaims} 立即下发, 离线方在下次登录时由 {@link MarriageSystem} 调同一方法补发。
 *
 * 离线侧: 提交/撤回/确认/结算全程不要求配偶在线。配偶离线时: 提交阶段的知情由登录时补发 filed_notify (见
 * {@link MarriageSystem#onPlayerLoggedIn}); capability 指针在其下次登录由 {@link MarriageSystem#reconcileMarriagePointer}
 * 经 Registry 反查自愈 (关系已 dissolve, forPlayer 返 null -&gt; 清指针); 戒指 NBT 同样在登录时校验回收
 * (marriageId 已不在 Registry -&gt; RingItem 显占位); 清算物走上面的待领取表。
 */
public final class MarriageDivorce {

    private static final Logger LOGGER = LoggerFactory.getLogger("miningdim/marriage");

    private final ServerLevel overworld;
    private final MarriageBackpackSessions sessions;

    public MarriageDivorce(ServerLevel overworld, MarriageBackpackSessions sessions) {
        this.overworld = overworld;
        this.sessions = sessions;
    }

    /** 提交离婚的结果回执 (WebUI 契约的四态联合类型, 前端按这四值渲染, 严禁加值)。 */
    public enum Result {
        OK,
        NOT_MARRIED,
        INSUFFICIENT_FUNDS,
        NO_ECONOMY
    }

    /** 撤回 (cancel) / 确认 (confirm) 的结果回执 (只走命令层, 不进 WebUI 回执)。 */
    public enum PendingAction {
        OK,
        NOT_MARRIED,
        NOT_PENDING,
        NOT_INITIATOR,
        NOT_SPOUSE,
        NO_ECONOMY
    }

    /**
     * 提交离婚的回执。
     *
     * @param result         四态结果码
     * @param alreadyPending 本次提交前关系是否已处于公示期 (true 表示本次调用是"重复提交", 未二次扣费)
     * @param effectiveAtTick 公示期到期生效的 gameTime (result != OK 时无意义, 恒 0)
     * @param initiator      本次 pending 的发起方 (result != OK 时为 null)
     */
    public record Filing(Result result, boolean alreadyPending, long effectiveAtTick, UUID initiator) {
    }

    /**
     * 提交离婚 (spec 第六章闸 2; 发起方执行)。已存在 pending 时幂等返回原状态 (不二次扣费), 否则开启公示期。
     * escrow 配 0 时立即结算, 退回旧的即时语义。
     *
     * @param initiator 发起方 (扣离婚成本对象)
     * @param cost      离婚成本 (信用点; 命令层读 config 传入)
     */
    public Filing file(ServerPlayer initiator, long cost) {
        IMiningPlayerData data = MiningCapabilities.get(initiator).orElse(null);
        if (data == null) {
            throw new IllegalStateException("player mining capability missing during divorce");
        }
        if (data.marriageId() == IMiningPlayerData.NO_MARRIAGE) {
            return new Filing(Result.NOT_MARRIED, false, 0L, null);
        }

        MarriageRegistry registry = MarriageRegistry.get(overworld);
        MarriageState state = registry.byId(data.marriageId());
        if (state == null || !state.involves(initiator.getUUID())) {
            // capability 指针指向已不存在的关系: 自愈清指针, 视为未婚 (不抛, 与 forPlayer 自愈同纪律)。
            data.setMarriageId(IMiningPlayerData.NO_MARRIAGE);
            data.setSpouseUUID(null);
            return new Filing(Result.NOT_MARRIED, false, 0L, null);
        }

        if (state.hasPendingDivorce()) {
            long effectiveAtTick = state.pendingDivorceFiledTick() + MarriageTuning.divorceEscrowTicks();
            return new Filing(Result.OK, true, effectiveAtTick, state.pendingDivorceInitiator());
        }

        if (!EconomyServices.isRegistered()) {
            return new Filing(Result.NO_ECONOMY, false, 0L, null);
        }

        IEconomyService eco = EconomyServices.economyService();
        if (cost > 0 && !eco.tryCharge(initiator, Currency.CREDIT, cost)) {
            return new Filing(Result.INSUFFICIENT_FUNDS, false, 0L, null);
        }

        long now = overworld.getGameTime();
        state.beginPendingDivorce(initiator.getUUID(), now, cost);
        registry.setDirty();

        long marriageId = state.marriageId();
        UUID spouseId = state.spouseOf(initiator.getUUID());

        // 公示期冻结从关掉双方已开窗口开始 (spec 第六章闸 2: 期间共享背包全冻结)。
        sessions.forceCloseAll(marriageId, overworld);

        long escrowTicks = MarriageTuning.divorceEscrowTicks();
        long effectiveAtTick = now + escrowTicks;
        long remainingSeconds = escrowTicks / 20L;

        initiator.sendSystemMessage(Component.translatable("message.miningdim.marriage.divorce.filed", remainingSeconds));
        ServerPlayer spouse = overworld.getServer().getPlayerList().getPlayer(spouseId);
        if (spouse != null) {
            spouse.sendSystemMessage(Component.translatable("message.miningdim.marriage.divorce.filed_notify",
                    initiator.getGameProfile().getName(), remainingSeconds));
        }
        // 离线配偶的知情由登录时补发 filed_notify (见 MarriageSystem#onPlayerLoggedIn)。

        LOGGER.info("[marriage] divorce filed: marriageId={} initiator={} spouse={} cost={} effectiveAtTick={}",
                marriageId, initiator.getUUID(), spouseId, cost, effectiveAtTick);

        if (escrowTicks <= 0L) {
            // 公示期关闭: 立即生效, 退回旧的即时语义。
            settle(state, now);
            return new Filing(Result.OK, false, now, initiator.getUUID());
        }

        return new Filing(Result.OK, false, effectiveAtTick, initiator.getUUID());
    }

    /**
     * 撤回待生效离婚 (spec 第六章闸 2: 公示期内可撤销)。仅发起方可撤回, 全额退回提交时扣的成本, 关系照旧存续。
     * fail closed: 经济服务未注册时不改任何状态 (不能白退关系不退钱), 直接返回 NO_ECONOMY。
     */
    public PendingAction cancel(ServerPlayer actor) {
        IMiningPlayerData data = MiningCapabilities.get(actor).orElse(null);
        if (data == null) {
            throw new IllegalStateException("player mining capability missing during divorce cancel");
        }
        if (data.marriageId() == IMiningPlayerData.NO_MARRIAGE) {
            return PendingAction.NOT_MARRIED;
        }

        MarriageRegistry registry = MarriageRegistry.get(overworld);
        MarriageState state = registry.byId(data.marriageId());
        if (state == null || !state.involves(actor.getUUID())) {
            data.setMarriageId(IMiningPlayerData.NO_MARRIAGE);
            data.setSpouseUUID(null);
            return PendingAction.NOT_MARRIED;
        }
        if (!state.hasPendingDivorce()) {
            return PendingAction.NOT_PENDING;
        }
        if (!actor.getUUID().equals(state.pendingDivorceInitiator())) {
            return PendingAction.NOT_INITIATOR;
        }
        if (!EconomyServices.isRegistered()) {
            return PendingAction.NO_ECONOMY;
        }

        long refunded = state.pendingDivorceCost();
        UUID spouseId = state.spouseOf(actor.getUUID());
        // cost=0 是运维合法配置 (MiningServerConfig.divorceCost 下界为 0): file() 对应地跳过了扣费
        // (cost > 0 && !tryCharge), 这里的退款必须同守卫对称 —— IEconomyService.grant 契约要求 amount > 0,
        // 无守卫会在 cost=0 时抛 ILLEGAL_AMOUNT, 且发生在 clearPendingDivorce 之前, 导致 pending 态卡死撤不回。
        if (refunded > 0) {
            EconomyServices.economyService().grant(actor, Currency.CREDIT, refunded);
        }
        state.clearPendingDivorce();
        registry.setDirty();

        actor.sendSystemMessage(Component.translatable("message.miningdim.marriage.divorce.cancelled"));
        ServerPlayer spouse = overworld.getServer().getPlayerList().getPlayer(spouseId);
        if (spouse != null) {
            spouse.sendSystemMessage(Component.translatable("message.miningdim.marriage.divorce.cancelled_notify",
                    actor.getGameProfile().getName()));
        }

        LOGGER.info("[marriage] divorce cancelled: marriageId={} actor={} refunded={}",
                state.marriageId(), actor.getUUID(), refunded);
        return PendingAction.OK;
    }

    /**
     * 确认待生效离婚立即生效 (spec 第六章闸 2)。语义是"配偶同意提前生效", 不是"配偶被迫确认" —— 配偶不做任何事
     * 时公示期到期照样由 {@link #finalizeMatured} 结算。只有 pending 发起方之外的那一位配偶可以确认。
     */
    public PendingAction confirm(ServerPlayer actor) {
        IMiningPlayerData data = MiningCapabilities.get(actor).orElse(null);
        if (data == null) {
            throw new IllegalStateException("player mining capability missing during divorce confirm");
        }
        if (data.marriageId() == IMiningPlayerData.NO_MARRIAGE) {
            return PendingAction.NOT_MARRIED;
        }

        MarriageRegistry registry = MarriageRegistry.get(overworld);
        MarriageState state = registry.byId(data.marriageId());
        if (state == null || !state.involves(actor.getUUID())) {
            data.setMarriageId(IMiningPlayerData.NO_MARRIAGE);
            data.setSpouseUUID(null);
            return PendingAction.NOT_MARRIED;
        }
        if (!state.hasPendingDivorce()) {
            return PendingAction.NOT_PENDING;
        }
        if (actor.getUUID().equals(state.pendingDivorceInitiator())) {
            return PendingAction.NOT_SPOUSE;
        }

        settle(state, overworld.getGameTime());
        return PendingAction.OK;
    }

    /**
     * 低频到期扫描 (由 {@link MarriageSystem#onServerTick} 每 100 tick 调一次): 对公示期已到期的全部待生效离婚
     * 逐个结算。遍历 {@link MarriageRegistry#all()} 的快照, 因为结算会在遍历中调 dissolve 改主表。
     *
     * @return 本次结算的关系数
     */
    public int finalizeMatured(long nowTick) {
        MarriageRegistry registry = MarriageRegistry.get(overworld);
        long escrowTicks = MarriageTuning.divorceEscrowTicks();
        int settled = 0;
        for (MarriageState state : registry.all()) {
            if (state.hasPendingDivorce() && nowTick - state.pendingDivorceFiledTick() >= escrowTicks) {
                settle(state, nowTick);
                settled++;
            }
        }
        return settled;
    }

    /**
     * 生效结算 (事务顺序, 不留半成品): 按槽归属清算共享背包 -&gt; 强关窗口 + 清会话 -&gt; 清 pending 态 -&gt;
     * dissolve Registry -&gt; 双方收尾 (在线清指针/回戒指/通知/下发清算物, 离线留给登录自愈) -&gt; 记再婚冷却 -&gt; 审计。
     */
    private void settle(MarriageState state, long nowTick) {
        long marriageId = state.marriageId();
        UUID a = state.partnerA();
        UUID b = state.partnerB();
        UUID initiator = state.pendingDivorceInitiator();
        long cost = state.pendingDivorceCost();

        MarriageHistory history = MarriageHistory.get(overworld);
        Map<UUID, Integer> claimedCounts = settleSharedBackpack(state, history);

        sessions.forceCloseAll(marriageId, overworld);
        sessions.onMarriageDissolved(marriageId);

        state.clearPendingDivorce();

        MarriageRegistry registry = MarriageRegistry.get(overworld);
        registry.dissolve(marriageId);

        finalizeParty(a, marriageId);
        finalizeParty(b, marriageId);

        history.recordDivorce(a, b, nowTick);

        LOGGER.info("[marriage] divorce settled: marriageId={} partnerA={} partnerB={} initiator={} cost={} claimedA={} claimedB={}",
                marriageId, a, b, initiator, cost, claimedCounts.getOrDefault(a, 0), claimedCounts.getOrDefault(b, 0));
    }

    /** 在线一方的收尾: 清 capability 指针 + 回戒指 + 通知 + 立即下发其那份清算物; 离线不做, 留给登录自愈。 */
    private void finalizeParty(UUID playerId, long marriageId) {
        ServerPlayer player = overworld.getServer().getPlayerList().getPlayer(playerId);
        if (player == null) {
            return;
        }
        IMiningPlayerData data = MiningCapabilities.get(player).orElse(null);
        if (data != null) {
            data.setMarriageId(IMiningPlayerData.NO_MARRIAGE);
            data.setSpouseUUID(null);
        }
        recycleRings(player, marriageId);
        player.sendSystemMessage(Component.translatable("message.miningdim.marriage.divorce.done"));
        deliverClaims(player);
    }

    /**
     * 共享背包清算 (spec 第六章闸 3: "谁放入谁取回")。逐非空槽取归属, 无归属 (旧存档/非菜单写入路径) 或归属者
     * 不是本关系双方之一时, 按槽号奇偶确定性平分 —— 全给发起方正是设计文档点名要防的"离婚资产抢劫", 按槽号奇偶
     * 是确定性且对称的, 不需要任何未拍板的分配比例。分配结果统一进 {@link MarriageHistory} 待领取表, 不直接塞
     * 玩家背包 (在线/离线下发口径统一, 见类注释)。
     *
     * @return 各归属方分到的物品总数量 (审计日志用)
     */
    private Map<UUID, Integer> settleSharedBackpack(MarriageState state, MarriageHistory history) {
        Map<UUID, Integer> claimedCounts = new HashMap<>();
        NonNullList<ItemStack> inv = state.sharedInv();
        for (int slot = 0; slot < inv.size(); slot++) {
            ItemStack stack = inv.get(slot);
            if (stack.isEmpty()) {
                continue;
            }
            UUID owner = state.depositorOf(slot);
            if (owner == null || !state.involves(owner)) {
                owner = (slot % 2 == 0) ? state.partnerA() : state.partnerB();
            }
            inv.set(slot, ItemStack.EMPTY);
            state.releaseSlot(slot);
            history.queueSettlementClaim(owner, stack);
            claimedCounts.merge(owner, stack.getCount(), Integer::sum);
        }
        return claimedCounts;
    }

    /**
     * 下发该玩家的全部待领取离婚清算物 (settle 内在线立即调, {@link MarriageSystem#onPlayerLoggedIn} 登录时补调)。
     * 背包满则落地, 不吞物; 空表静默返回 (不刷屏)。
     */
    public static void deliverClaims(ServerPlayer player) {
        MarriageHistory history = MarriageHistory.get(player.getServer().overworld());
        List<ItemStack> claims = history.takeSettlementClaims(player.getUUID());
        if (claims.isEmpty()) {
            return;
        }
        for (ItemStack stack : claims) {
            if (!player.getInventory().add(stack)) {
                player.drop(stack, false);
            }
        }
        player.sendSystemMessage(Component.translatable("message.miningdim.marriage.divorce.claims_delivered", claims.size()));
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
