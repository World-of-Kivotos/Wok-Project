package com.miningdim.job.agent.integration;

import com.miningdim.champion.AffixDef;
import com.miningdim.champion.AffixPool;
import com.miningdim.job.agent.SealCategory;

import java.util.EnumMap;
import java.util.HashMap;
import java.util.Map;

/**
 * 自研 {@link AffixDef} -> 特勤封印类别 ({@link SealCategory}) 归类 (SpecialAgent_Job_DesignSpec 六章封印类型门控)。
 * 本工程精英怪已自研脱离 Champions (盖章唯一写入点 {@code ChampionPromoter.applyChampion}, 取用入口
 * {@code MiningChampions.get}), 本类是纯查表, 不 import 任何 top.theillusivec4.champions.*。
 *
 * 归类两层 (与设计哲学"封印只压词条、高度克制、机制类绝不永久移除"对齐):
 *  1. 池决定是否值得封 ({@link AffixPool#SURVIVAL} = 纯防御, 封了不减玩家压力, 不作封印目标, 返 null)。
 *  2. 池决定窗口门控: {@link AffixPool#SKILL} 池 (主动有 CD 须预兆的读条核弹: 命定之死/小男孩/天雷/电磁蓄力等)
 *     = {@link SealCategory#MECHANIC} (仅 L8+ 可短暂封印, 窗口更短, 绝不永久移除); 战斗/机动池的被动词条 =
 *     {@link SealCategory#PASSIVE} (L3 起可封)。
 *
 * 等价迁移依据 (已逐条核对, 零行为漂移): 旧代码用 Champions 的 AffixCategory.DEFENSE 排除纯防御词条; 本工程 35
 * 条词条的 category 由 src/main/resources/data/miningdim/affix_setting/*.json 驱动, 逐条核对该 35 个 json 后确认:
 * 10 条 {@link AffixPool#SURVIVAL} 全 category=defence, 10 条 {@link AffixPool#COMBAT} + 5 条
 * {@link AffixPool#MOBILITY} 全 offense, 10 条 {@link AffixPool#SKILL} 全 cc。故 "AffixDef.pool()==SURVIVAL
 * 即不可封" 精确复刻旧 AffixCategory.DEFENSE 判据。
 */
final class AgentAffixClassifier {

    private AgentAffixClassifier() {
    }

    /** 枚举名 (name()) -> AffixDef 反查表 (与线上 affixId 口径同源)。 */
    private static final Map<String, AffixDef> BY_NAME = buildNameIndex();

    private static Map<String, AffixDef> buildNameIndex() {
        Map<String, AffixDef> index = new HashMap<>();
        for (AffixDef def : AffixDef.values()) {
            index.put(def.name(), def);
        }
        return index;
    }

    /** AffixDef -> 封印类别缓存 (SURVIVAL 池不进本表, get 返 null 即不可封)。 */
    private static final Map<AffixDef, SealCategory> SEAL_CATEGORY = buildSealCategory();

    private static Map<AffixDef, SealCategory> buildSealCategory() {
        Map<AffixDef, SealCategory> map = new EnumMap<>(AffixDef.class);
        for (AffixDef def : AffixDef.values()) {
            if (def.pool() == AffixPool.SURVIVAL) {
                continue; // 纯防御词条: 不进封印类别表, classify 据此返 null。
            }
            map.put(def, def.pool() == AffixPool.SKILL ? SealCategory.MECHANIC : SealCategory.PASSIVE);
        }
        return map;
    }

    /**
     * 词条线上标识 (与 {@link com.miningdim.job.agent.SealRegistry} 账本键 + 面板 affixId 同口径): 直接取
     * {@link AffixDef#name()} (如 BURNING / SUMMON_SUPPORT)。依据: 同工程的精英图鉴 action 已用同一格式
     * (ChampionWebUiActions.java row.addProperty("affixId", def.name())), 且 {@code MiningChampionData} 的 NBT
     * 本来就以枚举名为键; 前端 AgentPanel.tsx 只把 affixId 当不透明串原样回传, 无解析, SealRegistry 的账本键是
     * 进程内存 String, 无持久兼容问题。
     *
     * @param def 词条定义
     * @return 线上 affixId
     */
    static String affixId(AffixDef def) {
        return def.name();
    }

    /**
     * affixId -> AffixDef 反查 (客户端 C2S 封印申请携带的串)。未知返 null, 不抛 (客户端可以送任意串, 这是正常
     * 业务分支, 由调用方转译成 AFFIX_NOT_SEALABLE 回执)。
     *
     * @param affixId 线上词条标识
     * @return 对应 AffixDef; 未知返 null
     */
    static AffixDef affixOf(String affixId) {
        if (affixId == null) {
            return null;
        }
        return BY_NAME.get(affixId);
    }

    /**
     * 把 AffixDef 归类为特勤封印类别。null / 纯防御词条 (SURVIVAL 池, 不施压, 封了无意义) 返 null (= 不可封,
     * 集成层据此跳过该词条不进封印候选)。
     *
     * @param def 词条定义 (可为 null)
     * @return {@link SealCategory#PASSIVE} / {@link SealCategory#MECHANIC}; 不可封返 null
     */
    static SealCategory classify(AffixDef def) {
        if (def == null) {
            return null;
        }
        return SEAL_CATEGORY.get(def);
    }

    /**
     * 词条显示名 lang key (客户端 Component.translatable 渲染)。
     *
     * @param def 词条定义
     * @return {@link AffixDef#displayNameKey()}
     */
    static String displayKey(AffixDef def) {
        return def.displayNameKey();
    }
}
