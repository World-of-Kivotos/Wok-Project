package com.miningdim.job.tarot.codex;

import com.miningdim.job.tarot.TarotArcana;
import com.miningdim.job.tarot.TarotQuality;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.BitSet;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 塔罗图鉴：每名玩家 220 个“形态位”（22 张大阿卡纳 × 5 品质 × 正/逆）。
 *
 * <p>为什么需要它：现有 {@code PackGachaService.grantOrRefund} 对同 cardId 的重复牌只发碎片，背包里
 * 永远不可能同时存在 220 种；第三档皮肤（星辰之主）只能按“历史获得过的形态”判定，不能按当前持有。</p>
 *
 * <p>存储：主世界 {@code data/miningdim_tarot_codex.dat}，每名玩家 4 个 long（256 bit，用 220）。
 * 位索引见 {@link #index(int, TarotQuality, boolean)}。</p>
 */
public final class TarotCodexSavedData extends SavedData {
    public static final String FILE_ID = "miningdim_tarot_codex";
    /** 22 × 5 × 2 = 220。 */
    public static final int FORMS = TarotArcana.COUNT * TarotQuality.values().length * 2;
    private static final int WORDS = (FORMS + 63) / 64;

    private final Map<UUID, long[]> bits = new HashMap<>();

    public static TarotCodexSavedData get(MinecraftServer server) {
        return server.overworld().getDataStorage()
                .computeIfAbsent(TarotCodexSavedData::load, TarotCodexSavedData::new, FILE_ID);
    }

    /** 反序列化；包内可见以便 GameTest 直接验证落盘往返。 */
    static TarotCodexSavedData load(CompoundTag tag) {
        TarotCodexSavedData data = new TarotCodexSavedData();
        ListTag players = tag.getList("Players", Tag.TAG_COMPOUND);
        for (int i = 0; i < players.size(); i++) {
            CompoundTag entry = players.getCompound(i);
            if (!entry.hasUUID("Uuid")) {
                throw new IllegalStateException("Malformed tarot codex player entry at index " + i
                        + ": missing or invalid Uuid");
            }
            UUID uuid = entry.getUUID("Uuid");
            long[] words = entry.getLongArray("Bits");
            if (words.length != WORDS) {
                throw new IllegalStateException("Malformed tarot codex player entry at index " + i
                        + " for Uuid " + uuid + ": Bits must contain " + WORDS
                        + " longs, found " + words.length);
            }
            if (BitSet.valueOf(words).length() > FORMS) {
                throw new IllegalStateException("Malformed tarot codex player entry at index " + i
                        + " for Uuid " + uuid + ": Bits contain forms beyond " + FORMS);
            }
            data.bits.put(uuid, words);
        }
        return data;
    }

    @Override
    public CompoundTag save(CompoundTag tag) {
        ListTag players = new ListTag();
        for (Map.Entry<UUID, long[]> entry : bits.entrySet()) {
            CompoundTag row = new CompoundTag();
            row.putUUID("Uuid", entry.getKey());
            row.putLongArray("Bits", entry.getValue());
            players.add(row);
        }
        tag.put("Players", players);
        return tag;
    }

    /** 形态索引：{@code ((cardId * 5) + quality.ordinal()) * 2 + (upright ? 0 : 1)}，0..219。 */
    public static int index(int cardId, TarotQuality quality, boolean upright) {
        return ((cardId * TarotQuality.values().length) + quality.ordinal()) * 2 + (upright ? 0 : 1);
    }

    /** 记一个形态；返回是否为首次点亮。 */
    public boolean record(UUID uuid, int cardId, TarotQuality quality, boolean upright) {
        long[] words = bits.computeIfAbsent(uuid, key -> new long[WORDS]);
        int bit = index(cardId, quality, upright);
        int word = bit >>> 6;
        long mask = 1L << (bit & 63);
        if ((words[word] & mask) != 0L) {
            return false;
        }
        words[word] |= mask;
        setDirty();
        return true;
    }

    public boolean has(UUID uuid, int cardId, TarotQuality quality, boolean upright) {
        long[] words = bits.get(uuid);
        if (words == null) {
            return false;
        }
        int bit = index(cardId, quality, upright);
        return (words[bit >>> 6] & (1L << (bit & 63))) != 0L;
    }

    /** 已点亮形态数（0..220）。 */
    public int count(UUID uuid) {
        long[] words = bits.get(uuid);
        if (words == null) {
            return 0;
        }
        int total = 0;
        for (long word : words) {
            total += Long.bitCount(word);
        }
        return total;
    }

    /** 已点亮的 cardId 数（0..22），任意品质/朝向都算。 */
    public int arcana(UUID uuid) {
        long[] words = bits.get(uuid);
        if (words == null) {
            return 0;
        }
        int cards = 0;
        for (int cardId = 0; cardId < TarotArcana.COUNT; cardId++) {
            boolean any = false;
            for (int quality = 0; quality < TarotQuality.values().length && !any; quality++) {
                int bit = index(cardId, TarotQuality.values()[quality], true);
                // 正位/逆位任一位点亮即算这张牌已收集。
                any = (words[bit >>> 6] & (1L << (bit & 63))) != 0L
                        || (words[(bit + 1) >>> 6] & (1L << ((bit + 1) & 63))) != 0L;
            }
            if (any) {
                cards++;
            }
        }
        return cards;
    }

    /** 是否拥有任意一张闪耀品质的任意形态。 */
    public boolean hasShiny(UUID uuid) {
        return shinyForms(uuid) > 0;
    }

    /** 已点亮的闪耀形态数（0..44）。 */
    public int shinyForms(UUID uuid) {
        long[] words = bits.get(uuid);
        if (words == null) {
            return 0;
        }
        int count = 0;
        for (int cardId = 0; cardId < TarotArcana.COUNT; cardId++) {
            int bit = index(cardId, TarotQuality.SHINY, true);
            if ((words[bit >>> 6] & (1L << (bit & 63))) != 0L) {
                count++;
            }
            int reversed = bit + 1;
            if ((words[reversed >>> 6] & (1L << (reversed & 63))) != 0L) {
                count++;
            }
        }
        return count;
    }

    /** 22 张大阿卡纳是否每张至少点亮一个形态。 */
    public boolean hasAllArcana(UUID uuid) {
        return arcana(uuid) >= TarotArcana.COUNT;
    }
}
