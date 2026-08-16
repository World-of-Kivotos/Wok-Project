package com.miningdim.marriage;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 婚姻历史持久层 (结婚系统 spec 第六章离婚三闸的持久基座; 仿 {@link MarriageRegistry} 挂 overworld DimensionDataStorage)。
 * {@link MarriageRegistry} 只持"当前生效关系", 离婚后 {@link MarriageState} 被移除 —— 但下面三类数据必须跨关系存活:
 *
 *  1. 再婚冷却 (闸 1): 离婚后 N 天禁再婚, 冷却随离婚次数递增。冷却基于"玩家", 关系已解除故不能再存 MarriageState;
 *     本表按 playerUUID 存 {@code divorceCount} + {@code remarryAllowedTick} (下次允许再婚的最早 gameTime)。
 *  2. 一次性福利去重 (闸 3): 去重键 = "双方 UUID 对 + 里程碑", 换 marriageId 也不重发同一里程碑。本表按规范化
 *     UUID 对键 (小 UUID 在前, 与谁先 propose 无关) 存已领里程碑集合, 跨任意次结离对该对去重。
 *  3. 清算待领取表 (闸 2/3): 离婚生效时按槽归属分配的共享背包物品, 若归属方当时离线, 没有别处可放 ——
 *     关系已 dissolve, 共享背包容器随之作废, 无法再开窗口补发。本表按 playerUUID 存一份待领取物品队列,
 *     登录时 (见 {@link MarriageDivorce#deliverClaims}) 或在线立即下发。
 *
 * 线程纪律: 仅服务端主线程读写; 任何写后 setDirty 才落盘 (与 MarriageRegistry 同纪律)。
 */
public final class MarriageHistory extends SavedData {

    public static final String DATA_NAME = "miningdim_marriage_history";

    private static final String K_PLAYERS = "players";
    private static final String K_PLAYER_MOST = "uuidMost";
    private static final String K_PLAYER_LEAST = "uuidLeast";
    private static final String K_DIVORCE_COUNT = "divorceCount";
    private static final String K_REMARRY_ALLOWED = "remarryAllowedTick";
    private static final String K_PAIRS = "pairMilestones";
    private static final String K_PAIR_KEY = "pairKey";
    private static final String K_PAIR_MILESTONES = "milestones";
    private static final String K_SETTLEMENT_CLAIMS = "settlementClaims";
    private static final String K_CLAIM_ITEMS = "items";

    /** 单玩家的再婚闸态 (divorceCount 递增 + 下次允许再婚 tick)。 */
    private static final class PlayerHistory {
        int divorceCount;
        long remarryAllowedTick;
    }

    /** playerUUID -> 再婚闸态。 */
    private final Map<UUID, PlayerHistory> players = new HashMap<>();

    /** 规范化 UUID 对键 -> 该对已领里程碑集合 (跨任意次结离去重)。 */
    private final Map<String, Set<String>> pairMilestones = new HashMap<>();

    /** playerUUID -> 待领取的离婚清算物品队列 (离线时暂存, 登录或在线立即下发)。 */
    private final Map<UUID, List<ItemStack>> settlementClaims = new HashMap<>();

    public MarriageHistory() {
    }

    public static MarriageHistory get(ServerLevel overworld) {
        return overworld.getDataStorage().computeIfAbsent(
                MarriageHistory::load, MarriageHistory::new, DATA_NAME);
    }

    // ---- 再婚冷却闸 (spec 第六章闸 1) ----

    /**
     * 记一次离婚 (离婚结算时调): 双方 divorceCount++ 并按各自新次数设再婚冷却截止 tick。冷却天数随离婚次数递增
     * (见 {@link MarriageTuning#remarryCooldownTicks})。
     *
     * @param a       配偶 A
     * @param b       配偶 B
     * @param nowTick 当前 gameTime (冷却起算点)
     */
    public void recordDivorce(UUID a, UUID b, long nowTick) {
        bumpDivorce(a, nowTick);
        bumpDivorce(b, nowTick);
        setDirty();
    }

    private void bumpDivorce(UUID player, long nowTick) {
        PlayerHistory h = players.computeIfAbsent(player, k -> new PlayerHistory());
        h.divorceCount += 1;
        h.remarryAllowedTick = nowTick + MarriageTuning.remarryCooldownTicks(h.divorceCount);
    }

    /** 该玩家当前是否处于再婚冷却中 (nowTick < 允许再婚 tick)。从未离婚返回 false。 */
    public boolean isOnRemarryCooldown(UUID player, long nowTick) {
        PlayerHistory h = players.get(player);
        return h != null && nowTick < h.remarryAllowedTick;
    }

    /** 该玩家再婚冷却剩余 tick (无冷却返回 0)。 */
    public long remarryCooldownRemaining(UUID player, long nowTick) {
        PlayerHistory h = players.get(player);
        if (h == null) {
            return 0L;
        }
        return Math.max(0L, h.remarryAllowedTick - nowTick);
    }

    /** 该玩家累计离婚次数 (再婚冷却递增基数; 测试/诊断)。 */
    public int divorceCount(UUID player) {
        PlayerHistory h = players.get(player);
        return h == null ? 0 : h.divorceCount;
    }

    // ---- 一次性福利去重闸 (spec 第六章闸 3: 双方 UUID 对 + 里程碑) ----

    /**
     * 该 UUID 对是否已领过某里程碑 (换 marriageId 也算同一对, 不重发)。
     */
    public boolean hasPairClaimed(UUID a, UUID b, String milestoneId) {
        Set<String> set = pairMilestones.get(pairKey(a, b));
        return set != null && set.contains(milestoneId);
    }

    /**
     * 标记该 UUID 对已领某里程碑; 返回是否为首次领取 (false = 已领过, 调用方不重发)。首次领取时 setDirty。
     */
    public boolean claimPairMilestone(UUID a, UUID b, String milestoneId) {
        Set<String> set = pairMilestones.computeIfAbsent(pairKey(a, b), k -> new HashSet<>());
        boolean firstTime = set.add(milestoneId);
        if (firstTime) {
            setDirty();
        }
        return firstTime;
    }

    /** 规范化 UUID 对键: 小 UUID 在前 (与谁先 propose / 谁是 partnerA 无关, 同一对恒同键)。 */
    public static String pairKey(UUID a, UUID b) {
        if (a.compareTo(b) <= 0) {
            return a + "_" + b;
        }
        return b + "_" + a;
    }

    // ---- 清算待领取表 (spec 第六章闸 2/3: 离婚结算按槽归属分配, 离线方暂存待领) ----

    /** 排一件清算物到该玩家的待领取队列 (离婚结算调; 空栈直接返回)。 */
    public void queueSettlementClaim(UUID player, ItemStack stack) {
        if (stack.isEmpty()) {
            return;
        }
        settlementClaims.computeIfAbsent(player, k -> new ArrayList<>()).add(stack.copy());
        setDirty();
    }

    /** 取出并移除该玩家全部待领取清算物 (无则返回空表); 非空时 setDirty。 */
    public List<ItemStack> takeSettlementClaims(UUID player) {
        List<ItemStack> claims = settlementClaims.remove(player);
        if (claims == null || claims.isEmpty()) {
            return List.of();
        }
        setDirty();
        return claims;
    }

    /** 该玩家待领取清算物条目数 (非物品总数; 测试/诊断用)。 */
    public int settlementClaimCount(UUID player) {
        List<ItemStack> claims = settlementClaims.get(player);
        return claims == null ? 0 : claims.size();
    }

    // ---- 持久化 ----

    @Override
    public CompoundTag save(CompoundTag tag) {
        ListTag playerList = new ListTag();
        for (Map.Entry<UUID, PlayerHistory> e : players.entrySet()) {
            CompoundTag pt = new CompoundTag();
            pt.putLong(K_PLAYER_MOST, e.getKey().getMostSignificantBits());
            pt.putLong(K_PLAYER_LEAST, e.getKey().getLeastSignificantBits());
            pt.putInt(K_DIVORCE_COUNT, e.getValue().divorceCount);
            pt.putLong(K_REMARRY_ALLOWED, e.getValue().remarryAllowedTick);
            playerList.add(pt);
        }
        tag.put(K_PLAYERS, playerList);

        ListTag pairList = new ListTag();
        for (Map.Entry<String, Set<String>> e : pairMilestones.entrySet()) {
            CompoundTag pt = new CompoundTag();
            pt.putString(K_PAIR_KEY, e.getKey());
            ListTag milestones = new ListTag();
            for (String m : e.getValue()) {
                milestones.add(StringTag.valueOf(m));
            }
            pt.put(K_PAIR_MILESTONES, milestones);
            pairList.add(pt);
        }
        tag.put(K_PAIRS, pairList);

        ListTag claimList = new ListTag();
        for (Map.Entry<UUID, List<ItemStack>> e : settlementClaims.entrySet()) {
            if (e.getValue().isEmpty()) {
                continue;
            }
            CompoundTag ct = new CompoundTag();
            ct.putLong(K_PLAYER_MOST, e.getKey().getMostSignificantBits());
            ct.putLong(K_PLAYER_LEAST, e.getKey().getLeastSignificantBits());
            ListTag items = new ListTag();
            for (ItemStack stack : e.getValue()) {
                items.add(stack.save(new CompoundTag()));
            }
            ct.put(K_CLAIM_ITEMS, items);
            claimList.add(ct);
        }
        tag.put(K_SETTLEMENT_CLAIMS, claimList);
        return tag;
    }

    public static MarriageHistory load(CompoundTag tag) {
        MarriageHistory data = new MarriageHistory();
        ListTag playerList = tag.getList(K_PLAYERS, Tag.TAG_COMPOUND);
        for (int i = 0; i < playerList.size(); i++) {
            CompoundTag pt = playerList.getCompound(i);
            UUID id = new UUID(pt.getLong(K_PLAYER_MOST), pt.getLong(K_PLAYER_LEAST));
            PlayerHistory h = new PlayerHistory();
            h.divorceCount = pt.getInt(K_DIVORCE_COUNT);
            h.remarryAllowedTick = pt.getLong(K_REMARRY_ALLOWED);
            data.players.put(id, h);
        }
        ListTag pairList = tag.getList(K_PAIRS, Tag.TAG_COMPOUND);
        for (int i = 0; i < pairList.size(); i++) {
            CompoundTag pt = pairList.getCompound(i);
            String key = pt.getString(K_PAIR_KEY);
            ListTag milestones = pt.getList(K_PAIR_MILESTONES, Tag.TAG_STRING);
            Set<String> set = new HashSet<>();
            for (int j = 0; j < milestones.size(); j++) {
                set.add(milestones.getString(j));
            }
            data.pairMilestones.put(key, set);
        }

        ListTag claimList = tag.getList(K_SETTLEMENT_CLAIMS, Tag.TAG_COMPOUND);
        for (int i = 0; i < claimList.size(); i++) {
            CompoundTag ct = claimList.getCompound(i);
            UUID id = new UUID(ct.getLong(K_PLAYER_MOST), ct.getLong(K_PLAYER_LEAST));
            ListTag items = ct.getList(K_CLAIM_ITEMS, Tag.TAG_COMPOUND);
            List<ItemStack> claims = new ArrayList<>(items.size());
            for (int j = 0; j < items.size(); j++) {
                claims.add(ItemStack.of(items.getCompound(j)));
            }
            data.settlementClaims.put(id, claims);
        }
        return data;
    }
}
