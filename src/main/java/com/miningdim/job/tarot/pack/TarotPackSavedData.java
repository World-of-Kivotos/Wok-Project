package com.miningdim.job.tarot.pack;

import com.miningdim.job.tarot.TarotArcana;
import com.miningdim.job.tarot.TarotQuality;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** Persistent pity, daily acquisition and per-card-per-quality collection counters for the tarot pack system. */
public final class TarotPackSavedData extends SavedData {

    public static final String DATA_NAME = "miningdim_tarot_packs";

    private static final String K_PLAYERS = "players";
    private static final String K_UUID = "uuid";
    private static final String K_PITY = "advancedNoSsr";
    private static final String K_DAY = "dayStamp";
    private static final String K_ACQUIRED = "acquiredToday";
    private static final String K_COLLECTED = "collectedCards";
    private static final String K_C_ID = "id";
    private static final String K_C_QUALITY = "q";
    private static final String K_C_COUNT = "n";

    private final Map<UUID, PlayerState> players = new HashMap<>();

    private static final class PlayerState {
        int advancedNoSsr;
        long dayStamp;
        int acquiredToday;
        /**
         * 塔罗牌是消耗品 (F079 复核修正): 记的不是"是否曾经拿过"这个永久事实, 而是"当前经本 mod 追踪的
         * 已发-已耗净额" —— 开包/闪耀自选/碎片兑换/合成产物发牌时 +1, 用牌燃烧/合成材料耗用时 -1, 净额 > 0
         * 才算"重复"。key = cardId * 8 + TarotQuality.ordinal() (品质独立记账: R 收过不再挡 SSR 首次)。
         */
        final Map<Integer, Integer> collectedCounts = new HashMap<>();
    }

    private static int collectedKey(int cardId, TarotQuality quality) {
        return cardId * 8 + quality.ordinal();
    }

    public static TarotPackSavedData get(ServerLevel overworld) {
        return overworld.getDataStorage().computeIfAbsent(
                TarotPackSavedData::load, TarotPackSavedData::new, DATA_NAME);
    }

    public int advancedNoSsrStreak(UUID playerId) {
        PlayerState state = players.get(playerId);
        return state == null ? 0 : state.advancedNoSsr;
    }

    public void setAdvancedNoSsrStreak(UUID playerId, int streak) {
        if (streak < 0) {
            throw new IllegalArgumentException("pity streak must be >= 0");
        }
        PlayerState state = players.computeIfAbsent(playerId, ignored -> new PlayerState());
        if (state.advancedNoSsr != streak) {
            state.advancedNoSsr = streak;
            setDirty();
        }
    }

    public int acquiredToday(UUID playerId, long todayStamp) {
        PlayerState state = players.get(playerId);
        return state == null || state.dayStamp != todayStamp ? 0 : state.acquiredToday;
    }

    public boolean canAcquire(UUID playerId, int count, int dailyCap, long todayStamp) {
        requireCount(count, dailyCap);
        return acquiredToday(playerId, todayStamp) <= dailyCap - count;
    }

    /** Records paid or derived packs after all other transaction checks have succeeded. */
    public void recordAcquired(UUID playerId, int count, int dailyCap, long todayStamp) {
        if (!canAcquire(playerId, count, dailyCap, todayStamp)) {
            throw new IllegalStateException("tarot daily pack limit exceeded");
        }
        PlayerState state = players.computeIfAbsent(playerId, ignored -> new PlayerState());
        if (state.dayStamp != todayStamp) {
            state.dayStamp = todayStamp;
            state.acquiredToday = 0;
        }
        state.acquiredToday += count;
        setDirty();
    }

    /** Atomically consumes one derived-pack allowance on the server thread. */
    public boolean tryRecordDerived(UUID playerId, int dailyCap, long todayStamp) {
        if (!canAcquire(playerId, 1, dailyCap, todayStamp)) {
            return false;
        }
        recordAcquired(playerId, 1, dailyCap, todayStamp);
        return true;
    }

    private static void requireCount(int count, int dailyCap) {
        if (count <= 0) {
            throw new IllegalArgumentException("pack count must be > 0");
        }
        if (dailyCap < 0) {
            throw new IllegalArgumentException("daily pack cap must be >= 0");
        }
    }

    /**
     * 玩家当前是否"净持有"该 cardId+quality (F079 复核修正后的精确口径, 供 {@link PackGachaService} 判重复):
     * 已发-已耗净额 > 0。放进箱子不耗账 (净额不变, 仍算持有, 挡不掉开包判重), 但打出/合成耗用会真扣账
     * (净额归零后可再从包里抽到同一张牌) —— 塔罗牌是消耗品, 账本不能比背包记性更好。
     */
    public boolean hasCollected(UUID playerId, int cardId, TarotQuality quality) {
        PlayerState state = players.get(playerId);
        if (state == null) {
            return false;
        }
        Integer count = state.collectedCounts.get(collectedKey(cardId, quality));
        return count != null && count > 0;
    }

    /**
     * 玩家是否在任一品质上净持有该 cardId (面板只读态用, TarotWebUiActions 的 collected 栏): 五档中任一档
     * 净额 > 0 即真。面板不分品质展示, 精确的逐品质判重仍由 {@link #hasCollected(UUID, int, TarotQuality)} 做。
     */
    public boolean hasCollectedAny(UUID playerId, int cardId) {
        PlayerState state = players.get(playerId);
        if (state == null) {
            return false;
        }
        for (TarotQuality quality : TarotQuality.values()) {
            Integer count = state.collectedCounts.get(collectedKey(cardId, quality));
            if (count != null && count > 0) {
                return true;
            }
        }
        return false;
    }

    /** 记一张 cardId+quality 的牌被发给玩家 (净额 +1)。cardId 越界直接抛, 越界值意味着调用方逻辑本身已经坏了。 */
    public void markCollected(UUID playerId, int cardId, TarotQuality quality) {
        if (cardId < 0 || cardId >= TarotArcana.COUNT) {
            throw new IllegalArgumentException("cardId out of range [0," + (TarotArcana.COUNT - 1) + "]: " + cardId);
        }
        PlayerState state = players.computeIfAbsent(playerId, ignored -> new PlayerState());
        int key = collectedKey(cardId, quality);
        state.collectedCounts.merge(key, 1, Integer::sum);
        setDirty();
    }

    /**
     * 记一张 cardId+quality 的牌被玩家耗用 (净额 -1, 消耗品语义)。净额已是 0/未记录时是合法的 no-op ——
     * 该牌可能是从未经账本发放的 (op /give 直给), 耗用一张账本从没见过的牌不是逻辑错误, 不抛。
     */
    public void releaseCollected(UUID playerId, int cardId, TarotQuality quality) {
        PlayerState state = players.get(playerId);
        if (state == null) {
            return;
        }
        int key = collectedKey(cardId, quality);
        Integer count = state.collectedCounts.get(key);
        if (count == null || count <= 0) {
            return;
        }
        if (count == 1) {
            state.collectedCounts.remove(key);
        } else {
            state.collectedCounts.put(key, count - 1);
        }
        setDirty();
    }

    @Override
    public CompoundTag save(CompoundTag tag) {
        ListTag list = new ListTag();
        for (Map.Entry<UUID, PlayerState> entry : players.entrySet()) {
            PlayerState state = entry.getValue();
            CompoundTag player = new CompoundTag();
            player.putUUID(K_UUID, entry.getKey());
            player.putInt(K_PITY, state.advancedNoSsr);
            player.putLong(K_DAY, state.dayStamp);
            player.putInt(K_ACQUIRED, state.acquiredToday);
            ListTag collected = new ListTag();
            for (Map.Entry<Integer, Integer> entry2 : state.collectedCounts.entrySet()) {
                int key = entry2.getKey();
                CompoundTag row = new CompoundTag();
                row.putInt(K_C_ID, key / 8);
                row.putInt(K_C_QUALITY, key % 8);
                row.putInt(K_C_COUNT, entry2.getValue());
                collected.add(row);
            }
            player.put(K_COLLECTED, collected);
            list.add(player);
        }
        tag.put(K_PLAYERS, list);
        return tag;
    }

    public static TarotPackSavedData load(CompoundTag tag) {
        TarotPackSavedData data = new TarotPackSavedData();
        ListTag list = tag.getList(K_PLAYERS, Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++) {
            CompoundTag player = list.getCompound(i);
            if (!player.hasUUID(K_UUID)) {
                continue;
            }
            PlayerState state = new PlayerState();
            state.advancedNoSsr = Math.max(0, player.getInt(K_PITY));
            state.dayStamp = player.getLong(K_DAY);
            state.acquiredToday = Math.max(0, player.getInt(K_ACQUIRED));
            // 旧存档 (本键落地前生成的, 或本次复核前的布尔 K_COLLECTED 格式) 无匹配子键时 getList 返回空表,
            // 即空账本, 不是错误状态 —— 与旧格式互不兼容属预期 (F079 在同一未发布分支内改了两次账本形状)。
            ListTag collected = player.getList(K_COLLECTED, Tag.TAG_COMPOUND);
            for (int j = 0; j < collected.size(); j++) {
                CompoundTag row = collected.getCompound(j);
                if (!row.contains(K_C_ID) || !row.contains(K_C_QUALITY) || !row.contains(K_C_COUNT)) {
                    continue;
                }
                int cardId = row.getInt(K_C_ID);
                int qualityOrdinal = row.getInt(K_C_QUALITY);
                int count = row.getInt(K_C_COUNT);
                if (cardId < 0 || cardId >= TarotArcana.COUNT
                        || qualityOrdinal < 0 || qualityOrdinal >= TarotQuality.values().length
                        || count <= 0) {
                    continue;
                }
                state.collectedCounts.put(cardId * 8 + qualityOrdinal, count);
            }
            data.players.put(player.getUUID(K_UUID), state);
        }
        return data;
    }
}
