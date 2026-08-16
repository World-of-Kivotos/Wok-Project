package com.miningdim.job.tarot;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;

/**
 * 碎片兑换指定牌 (TarotReader spec 第七/十三章 6 反非酋承诺: "重复牌转塔罗碎片, 攒够换指定牌, 给非洲玩家确定性
 * 毕业线")。开包重复牌经 {@link com.miningdim.job.tarot.pack.PackGachaService} 转碎片 (唯一非破碎来源), 攒够
 * {@link TarotConfig#SHARD_EXCHANGE_COST} 张即可经 {@code /tarot exchange <cardId>} 确定性兑换一张指定牌。
 *
 * 毕业线品质 = SSR: 与闪耀卡包自选档对齐 (spec 第七章闪耀包开出自选一张 SSR), 是本 mod 既定的 "确定性获取上限"
 * (UR/闪耀仍只能合成, 防碎片直通最强牌)。兑换出的牌盖 ownerUUID 绑定 (spec 第十章), 正逆位随机。
 *
 * 服务端权威: 先校验碎片足够 (事务安全, 不足不扣不发), 足够则原子扣碎片 + 给牌。纯逻辑抽出供 TDD 断言
 * (扣 N 张碎片精确, 不足则无副作用)。
 *
 * 兑换发的牌同样计入 {@link com.miningdim.job.tarot.pack.TarotPackSavedData} 的净额账本 (复核追加修正,
 * F079 原实现漏了这条通道): 不计入的话, 花碎片兑到 X 再放进箱子, 卡包判重扫不到这笔发放, 会再发一张真 X ——
 * "放进箱子规避重复判定"对这条通道形同虚设。兑换固定发 SSR (见类头), 只写 SSR 档不影响 R/SR/UR 的独立判重。
 */
public final class TarotShardExchange {

    private TarotShardExchange() {
    }

    /** 兑换结果: 是否成功 + 实际消耗碎片数 (失败为 0)。 */
    public record ExchangeResult(boolean success, int shardsSpent) {
    }

    /**
     * 兑换一张指定 cardId 的 SSR 牌, 消耗 {@link TarotConfig#SHARD_EXCHANGE_COST} 张碎片。
     * 碎片不足返回失败且无副作用 (不扣不发); 足够则原子扣碎片 + 给牌。
     *
     * @param cardId 玩家指定的牌 (0-21; 越界抛 IllegalArgumentException 由命令层兜底)
     * @param upright 兑换牌的正逆位 (命令层传入; 兑换是确定性毕业线, 朝向由玩家定)
     */
    public static ExchangeResult exchange(ServerPlayer player, int cardId, boolean upright) {
        if (cardId < 0 || cardId >= TarotArcana.COUNT) {
            throw new IllegalArgumentException("exchange cardId out of range [0,21]: " + cardId);
        }
        int cost = TarotConfig.SHARD_EXCHANGE_COST.get();
        int held = countShards(player);
        if (held < cost) {
            return new ExchangeResult(false, 0);
        }
        consumeShards(player, cost);
        ItemStack card = TarotCardItem.create(
                TarotRegistry.TAROT_CARD.get(), cardId, TarotQuality.SSR, upright, player.getUUID());
        net.minecraftforge.items.ItemHandlerHelper.giveItemToPlayer(player, card);
        com.miningdim.job.tarot.pack.TarotPackSavedData.get(player.getServer().overworld())
                .markCollected(player.getUUID(), cardId, TarotQuality.SSR);
        return new ExchangeResult(true, cost);
    }

    /** 玩家背包 (含副手) 当前持有的塔罗碎片总数。 */
    public static int countShards(ServerPlayer player) {
        int n = 0;
        for (ItemStack s : player.getInventory().items) {
            if (isShard(s)) {
                n += s.getCount();
            }
        }
        for (ItemStack s : player.getInventory().offhand) {
            if (isShard(s)) {
                n += s.getCount();
            }
        }
        return n;
    }

    /** 从背包 (含副手) 扣除 count 张碎片 (按堆顺序扣; 调用方已保证总量足够)。 */
    private static void consumeShards(ServerPlayer player, int count) {
        int remaining = count;
        remaining = drainFrom(player.getInventory().items, remaining);
        remaining = drainFrom(player.getInventory().offhand, remaining);
        if (remaining > 0) {
            throw new IllegalStateException(
                    "shard exchange consumed fewer shards than required (concurrent inventory mutation?)");
        }
    }

    private static int drainFrom(java.util.List<ItemStack> slots, int remaining) {
        for (ItemStack s : slots) {
            if (remaining <= 0) {
                break;
            }
            if (isShard(s)) {
                int take = Math.min(remaining, s.getCount());
                s.shrink(take);
                remaining -= take;
            }
        }
        return remaining;
    }

    private static boolean isShard(ItemStack stack) {
        return !stack.isEmpty() && stack.getItem() == TarotRegistry.TAROT_SHARD.get();
    }
}
