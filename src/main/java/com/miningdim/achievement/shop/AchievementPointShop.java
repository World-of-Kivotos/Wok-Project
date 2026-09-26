package com.miningdim.achievement.shop;

import com.miningdim.achievement.AchievementServices;
import com.miningdim.achievement.AchievementStoreException;
import com.miningdim.achievement.reward.AchievementRewardRepository;
import com.miningdim.achievement.reward.LedgerReason;
import com.miningdim.achievement.reward.PointBalance;
import com.miningdim.store.MiningStoreException;
import com.miningdim.title.GrantResult;
import com.miningdim.title.TitleServices;
import com.miningdim.title.TitleSource;
import com.miningdim.title.store.TitleStoreException;
import net.minecraft.advancements.Advancement;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.UUID;

/**
 * 成就点商店的兑换入口 (Achievement_System_DesignSpec 8.4); G 面板的 achievement.pointShopBuy 调这里, 不直接碰仓库。
 *
 * <p>一次兑换是 MiningStore 共享连接上的<b>一个</b>事务: 按流水数出已兑换次数 → 查限购 → 扣点 (余额不足即失败) →
 * 写一条 shop_buy 流水 (ref 为商品 id) → 称号类商品经 {@code ITitleService#grantInTransaction} 在同一条连接上发放
 * (来源 point_shop, source_ref 为商品 id)。任何一步失败整个事务回滚, 一点不扣、一条流水都不留。
 *
 * <p>事务之外的两道前置检查都在扣点之前、且不写库: 商品要求的成就是否已获得; 物品类商品的背包空间是否放得下
 * (8.4: 没有空间就不扣点)。事务提交后才发东西: 称号类补发"获得称号"提示, 物品类把带绑定盖章的物品放进背包。
 * 服务端单线程, 检查空间与放进背包之间不会插进别的操作; 万一还是放不下 (理论上不会发生), 剩余部分掉在玩家脚下,
 * 不吞掉已经付过点的物品。
 *
 * <p>全部方法在服务端主线程调用。与领取同一口径, 共享连接上已开着调用方的事务时直接抛 {@link IllegalStateException}:
 * 并入外层时自己的回滚会被外层吞掉, 提交后才发的东西也会先于真正落盘发出。
 */
public final class AchievementPointShop {

    private static final Logger LOGGER = LoggerFactory.getLogger("miningdim/achievement");

    private AchievementPointShop() {
    }

    /**
     * 兑换一件商品。
     *
     * @throws IllegalStateException 共享连接上正开着调用方的事务
     */
    public static PurchaseResult buy(ServerPlayer player, ResourceLocation goodsId) {
        PointShopGoods goods = AchievementServices.pointShop().goods(goodsId).orElse(null);
        if (goods == null) {
            return PurchaseResult.rejected(PurchaseResult.Status.GOODS_UNKNOWN, null);
        }
        AchievementRewardRepository rewards = AchievementServices.rewards();
        PointShopRepository shop = AchievementServices.pointShopRepository();
        if (rewards.inOpenTransaction()) {
            throw new IllegalStateException("achievement point shop purchase must not run inside the caller's open "
                    + "transaction: it commits or rolls back its own transaction and delivers only after its commit");
        }
        if (!requirementMet(player, goods)) {
            return PurchaseResult.rejected(PurchaseResult.Status.REQUIREMENT_UNMET, goods);
        }
        UUID uuid = player.getUUID();
        ItemStack stack = null;
        if (goods.type() == PointShopGoods.Type.TITLE) {
            if (!titleDefined(goods.titleId())) {
                return PurchaseResult.rejected(PurchaseResult.Status.GOODS_UNKNOWN, goods);
            }
            if (TitleServices.titleService().owned(uuid).contains(goods.titleId())) {
                return PurchaseResult.rejected(PurchaseResult.Status.TITLE_OWNED, goods);
            }
        } else {
            stack = goods.createStack(uuid);
            if (!fits(player.getInventory(), stack)) {
                return PurchaseResult.rejected(PurchaseResult.Status.INVENTORY_FULL, goods);
            }
        }

        long now = System.currentTimeMillis();
        Committed committed;
        try {
            committed = rewards.inTransaction(tx -> {
                int purchased = shop.purchaseCount(uuid, goods.id());
                Integer limit = goods.effectiveLimit();
                if (limit != null && purchased >= limit) {
                    throw new PurchaseAborted(PurchaseResult.Status.LIMIT_REACHED, purchased, null);
                }
                if (!rewards.debit(uuid, goods.price())) {
                    throw new PurchaseAborted(PurchaseResult.Status.POINTS_INSUFFICIENT, purchased,
                            rewards.points(uuid));
                }
                rewards.appendLedger(uuid, -goods.price(), LedgerReason.SHOP_BUY, goods.id().toString(), now);
                if (goods.type() == PointShopGoods.Type.TITLE) {
                    GrantResult granted = TitleServices.titleService().grantInTransaction(tx, uuid, goods.titleId(),
                            TitleSource.POINT_SHOP, goods.id().toString());
                    if (granted == GrantResult.ALREADY_OWNED) {
                        throw new PurchaseAborted(PurchaseResult.Status.TITLE_OWNED, purchased, null);
                    }
                    if (granted != GrantResult.GRANTED) {
                        throw new PurchaseAborted(PurchaseResult.Status.GOODS_UNKNOWN, purchased, null);
                    }
                }
                // 余额在事务内读: 提交之后再读一旦失败, 会把已经提交的兑换误报成失败。
                return new Committed(purchased + 1, rewards.points(uuid));
            });
        } catch (PurchaseAborted aborted) {
            LOGGER.info("[miningdim] achievement point shop purchase of {} by {} ({}) refused: {}", goods.id(),
                    player.getGameProfile().getName(), uuid, aborted.status);
            return switch (aborted.status) {
                case LIMIT_REACHED -> new PurchaseResult(aborted.status, goods, null, aborted.purchased);
                case POINTS_INSUFFICIENT -> new PurchaseResult(aborted.status, goods, aborted.balance, 0);
                default -> PurchaseResult.rejected(aborted.status, goods);
            };
        } catch (AchievementStoreException | TitleStoreException | MiningStoreException failure) {
            LOGGER.error("[miningdim] achievement point shop purchase of {} by {} ({}) failed; nothing was charged",
                    goods.id(), player.getGameProfile().getName(), uuid, failure);
            return PurchaseResult.rejected(PurchaseResult.Status.STORE_FAILED, goods);
        }

        // 事务已提交: 这才发东西。
        if (stack == null) {
            TitleServices.titleService().notifyGranted(player, goods.titleId());
        } else {
            deliver(player, stack, goods);
        }
        LOGGER.info("[miningdim] achievement point shop purchase: {} ({}) bought {} for {} point(s), balance {}, "
                        + "purchase #{}", player.getGameProfile().getName(), uuid, goods.id(), goods.price(),
                committed.balance().balance(), committed.purchased());
        return PurchaseResult.purchased(goods, committed.balance(), committed.purchased());
    }

    /** 商品要求的成就是否已获得; 没有要求为 true, 要求的进度没加载 (比如加载条件不满足) 为 false。 */
    public static boolean requirementMet(ServerPlayer player, PointShopGoods goods) {
        ResourceLocation required = goods.requiresAdvancement();
        if (required == null) {
            return true;
        }
        Advancement advancement = player.server.getAdvancements().getAdvancement(required);
        return advancement != null && player.getAdvancements().getOrStartProgress(advancement).isDone();
    }

    /** 称号类商品引用的称号此刻是否有加载的定义 (称号门面未注入时按没有处理)。 */
    public static boolean titleDefined(@Nullable ResourceLocation titleId) {
        return titleId != null && TitleServices.isRegistered()
                && TitleServices.titleService().definition(titleId).isPresent();
    }

    /**
     * 主背包 (36 格) 放不放得下这一堆: 空格按最大堆叠数计, 同物品同 NBT 的格子按剩余空间计。不算副手 ——
     * 宁可偶尔多报一次"背包已满", 也不能扣了点才发现放不下。
     */
    private static boolean fits(Inventory inventory, ItemStack stack) {
        int room = 0;
        int cap = Math.min(stack.getMaxStackSize(), inventory.getMaxStackSize());
        for (ItemStack slot : inventory.items) {
            if (slot.isEmpty()) {
                room += cap;
            } else if (ItemStack.isSameItemSameTags(slot, stack)) {
                room += Math.max(0, cap - slot.getCount());
            }
            if (room >= stack.getCount()) {
                return true;
            }
        }
        return false;
    }

    private static void deliver(ServerPlayer player, ItemStack stack, PointShopGoods goods) {
        player.getInventory().add(stack);
        if (stack.isEmpty()) {
            return;
        }
        LOGGER.warn("[miningdim] achievement point shop goods {} did not fit into the inventory of {} after the "
                + "space check; dropping {} at the player's feet", goods.id(), player.getGameProfile().getName(), stack);
        player.drop(stack, false);
    }

    /** 兑换事务提交的内容: 含本次在内的兑换次数, 与提交时的成就点。 */
    private record Committed(int purchased, PointBalance balance) {
    }

    /** 兑换中途放弃: 抛出即让事务整体回滚, 由 {@link #buy} 转成对应的结果。 */
    private static final class PurchaseAborted extends RuntimeException {

        private final PurchaseResult.Status status;
        private final int purchased;
        /** 余额不足时事务内读到的成就点; 其余为 null。 */
        @Nullable
        private final PointBalance balance;

        PurchaseAborted(PurchaseResult.Status status, int purchased, @Nullable PointBalance balance) {
            super(status.name(), null, false, false);
            this.status = status;
            this.purchased = purchased;
            this.balance = balance;
        }
    }
}
