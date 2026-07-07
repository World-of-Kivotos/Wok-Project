package com.miningdim.job.brewer;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 酿酒师永久层数持久层 (阶段 5(iii)): 每玩家 per {@link WineType} 0..{@link BrewerConstants#MAX_LAYERS_PER_TYPE} 层。
 * 范式照 {@link com.miningdim.job.farmer.FarmerSavedData} (1.20.1 三参 computeIfAbsent, 写后必 setDirty,
 * 挂 overworld DimensionDataStorage, 文件名 {@value #DATA_NAME})。
 *
 * 语义 (设计锁定): 喝一瓶【闪耀 BRILLIANT】酒按年份加层 (<T1 +0 / [T1,T2) +1 / [T2,T3) +2 / >=T3 +3, 每类型封顶 5);
 * 非闪耀不加层。闪耀酒一酒两用 (当场临时效果照旧 + 加永久层)。死亡清该玩家所有层 (一条命语义); 登录按存的层数
 * 由 {@link BrewPermanentBuffs} 重挂全部永久特殊 (属性/effect 不跨会话, 故必须重挂)。
 *
 * 月光满层固化的良性词条 ({@link MoonshinePerk}) 一并存此 (按玩家存一组 id), 登录重挂据存的 id 直接还原。
 *
 * 线程: 仅服务端主线程读写 (喝酒/死亡/登录均主线程)。
 */
public final class BrewBuffStore extends SavedData {

    /** DimensionDataStorage 数据文件名。 */
    public static final String DATA_NAME = "miningdim_brewer_buffs";

    private static final String K_LAYERS = "layers";
    private static final String K_UUID = "uuid";
    private static final String K_TYPE_PREFIX = "t_"; // 每酒类型一个 int 子键 (t_gin/t_vodka/...)。
    private static final String K_MOONSHINE = "moonshinePerks";
    private static final String K_PERKS = "perks";

    /** 玩家 UUID -> (酒类型 -> 层数)。仅存非零层 (零层即移除条目, 防离场玩家长期占表)。 */
    private final Map<UUID, EnumMap<WineType, Integer>> layers = new HashMap<>();

    /** 玩家 UUID -> 月光满层固化的良性词条选择 (登录重挂据此还原)。 */
    private final Map<UUID, List<MoonshinePerk>> moonshinePerks = new HashMap<>();

    public BrewBuffStore() {
    }

    /** 取/建 overworld 的酿酒永久层数持久数据。务必传 overworld 的 ServerLevel。 */
    public static BrewBuffStore get(ServerLevel overworld) {
        return overworld.getDataStorage().computeIfAbsent(
                BrewBuffStore::load, BrewBuffStore::new, DATA_NAME);
    }

    /** 某玩家某酒类型当前层数 (无记录返回 0)。 */
    public int layers(UUID playerId, WineType type) {
        EnumMap<WineType, Integer> m = layers.get(playerId);
        return m == null ? 0 : m.getOrDefault(type, 0);
    }

    /**
     * 喝一瓶闪耀酒按年份加层 (纯逻辑入口): 先经 {@link #vintageLayerGain} 算本次加层, 与当前层相加封顶 5。
     * 返回加层后的新层数 (供调用方据此重挂特殊)。加层为 0 (嫩闪耀酒) 时不动账、不标脏。
     *
     * @param playerId 玩家 UUID
     * @param type     酒类型
     * @param vintage  本瓶年份 (决定加几层)
     * @return 加层后的新层数 (0..5)
     */
    public int addLayersForVintage(UUID playerId, WineType type, double vintage) {
        int gain = vintageLayerGain(vintage);
        if (gain <= 0) {
            return layers(playerId, type);
        }
        EnumMap<WineType, Integer> m = layers.computeIfAbsent(playerId, k -> new EnumMap<>(WineType.class));
        int next = Math.min(BrewerConstants.MAX_LAYERS_PER_TYPE, m.getOrDefault(type, 0) + gain);
        m.put(type, next);
        setDirty();
        return next;
    }

    /**
     * 年份 -> 本次加层 (设计锁定阈值; 纯函数便于测): &lt;T1 +0 / [T1,T2) +1 / [T2,T3) +2 / &gt;=T3 +3。
     */
    public static int vintageLayerGain(double vintage) {
        if (vintage >= BrewerConstants.VINTAGE_LAYER_T3) {
            return 3;
        }
        if (vintage >= BrewerConstants.VINTAGE_LAYER_T2) {
            return 2;
        }
        if (vintage >= BrewerConstants.VINTAGE_LAYER_T1) {
            return 1;
        }
        return 0;
    }

    /**
     * 清该玩家全部层 + 月光词条选择 (死亡: 一条命语义)。返回清前是否有任何层/词条 (供调用方决定是否需移除身上修饰)。
     */
    public boolean clearAll(UUID playerId) {
        boolean had = layers.remove(playerId) != null;
        had |= moonshinePerks.remove(playerId) != null;
        if (had) {
            setDirty();
        }
        return had;
    }

    /** 某玩家是否有任意永久层 (登录是否需要重挂的快速判定)。 */
    public boolean hasAnyLayers(UUID playerId) {
        EnumMap<WineType, Integer> m = layers.get(playerId);
        if (m == null) {
            return false;
        }
        for (int v : m.values()) {
            if (v > 0) {
                return true;
            }
        }
        return false;
    }

    /** 设置某玩家月光满层固化的良性词条选择 (月光达满层时存一次; 不可变拷贝入表)。 */
    public void setMoonshinePerks(UUID playerId, List<MoonshinePerk> perks) {
        moonshinePerks.put(playerId, List.copyOf(perks));
        setDirty();
    }

    /** 某玩家已固化的月光良性词条 (无则空 list; 登录重挂据此还原)。 */
    public List<MoonshinePerk> moonshinePerks(UUID playerId) {
        return moonshinePerks.getOrDefault(playerId, List.of());
    }

    @Override
    public CompoundTag save(CompoundTag tag) {
        ListTag list = new ListTag();
        for (Map.Entry<UUID, EnumMap<WineType, Integer>> e : layers.entrySet()) {
            CompoundTag entry = new CompoundTag();
            entry.putUUID(K_UUID, e.getKey());
            for (Map.Entry<WineType, Integer> le : e.getValue().entrySet()) {
                if (le.getValue() > 0) {
                    entry.putInt(K_TYPE_PREFIX + le.getKey().id(), le.getValue());
                }
            }
            list.add(entry);
        }
        tag.put(K_LAYERS, list);

        ListTag perks = new ListTag();
        for (Map.Entry<UUID, List<MoonshinePerk>> e : moonshinePerks.entrySet()) {
            CompoundTag entry = new CompoundTag();
            entry.putUUID(K_UUID, e.getKey());
            ListTag ids = new ListTag();
            for (MoonshinePerk p : e.getValue()) {
                CompoundTag pe = new CompoundTag();
                pe.putString("id", p.id());
                ids.add(pe);
            }
            entry.put(K_PERKS, ids);
            perks.add(entry);
        }
        tag.put(K_MOONSHINE, perks);
        return tag;
    }

    public static BrewBuffStore load(CompoundTag tag) {
        BrewBuffStore data = new BrewBuffStore();
        if (tag.contains(K_LAYERS)) {
            ListTag list = tag.getList(K_LAYERS, Tag.TAG_COMPOUND);
            for (int i = 0; i < list.size(); i++) {
                CompoundTag entry = list.getCompound(i);
                if (!entry.hasUUID(K_UUID)) {
                    continue;
                }
                EnumMap<WineType, Integer> m = new EnumMap<>(WineType.class);
                for (WineType type : WineType.values()) {
                    String key = K_TYPE_PREFIX + type.id();
                    if (entry.contains(key)) {
                        int v = entry.getInt(key);
                        if (v > 0) {
                            m.put(type, Math.min(BrewerConstants.MAX_LAYERS_PER_TYPE, v));
                        }
                    }
                }
                if (!m.isEmpty()) {
                    data.layers.put(entry.getUUID(K_UUID), m);
                }
            }
        }
        if (tag.contains(K_MOONSHINE)) {
            ListTag perks = tag.getList(K_MOONSHINE, Tag.TAG_COMPOUND);
            for (int i = 0; i < perks.size(); i++) {
                CompoundTag entry = perks.getCompound(i);
                if (!entry.hasUUID(K_UUID)) {
                    continue;
                }
                ListTag ids = entry.getList(K_PERKS, Tag.TAG_COMPOUND);
                List<MoonshinePerk> chosen = new ArrayList<>();
                for (int j = 0; j < ids.size(); j++) {
                    MoonshinePerk p = MoonshinePerk.fromId(ids.getCompound(j).getString("id"));
                    if (p != null) {
                        chosen.add(p);
                    }
                }
                if (!chosen.isEmpty()) {
                    data.moonshinePerks.put(entry.getUUID(K_UUID), chosen);
                }
            }
        }
        return data;
    }
}
