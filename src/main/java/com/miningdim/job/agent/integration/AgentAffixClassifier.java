package com.miningdim.job.agent.integration;

import com.miningdim.champion.AffixDef;
import com.miningdim.champion.AffixPool;
import com.miningdim.job.agent.AgentSystem;
import com.miningdim.job.agent.SealCategory;
import net.minecraft.resources.ResourceLocation;
import top.theillusivec4.champions.api.AffixCategory;
import top.theillusivec4.champions.api.IAffix;

import java.util.EnumMap;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * 真 IAffix -> 特勤封印类别 ({@link SealCategory}) 归类 (SpecialAgent_Job_DesignSpec 六章封印类型门控; 落集成层,
 * 纯逻辑层 {@link SealCategory} 不映射任何 Champions 类型)。
 *
 * 归类两层 (与设计哲学"封印只压词条、高度克制、机制类绝不永久移除"对齐):
 *  1. 只对本工程盖章词条 (registry namespace = miningdim) 归类: 外来 mod 词条 identifier 命名空间不同, 返 null
 *     (不归本工程封印体系, 不可封)。
 *  2. 本工程词条按 {@link AffixDef} 所属池区分: {@link AffixPool#SKILL} 池 (主动有 CD 须预兆的读条核弹: 命定之死/
 *     小男孩/天雷/电磁蓄力等) = {@link SealCategory#MECHANIC} (仅 L8+ 可短暂封印, 窗口更短, 绝不永久移除);
 *     生存/战斗/机动池的被动词条 = {@link SealCategory#PASSIVE} (L3 起可封)。
 *
 * Champions 三分类过滤 (调研铁律): 封印目标只取对玩家施压的词条 —— {@link AffixCategory#OFFENSE} 与
 * {@link AffixCategory#CC} (PVE 减压); 纯 {@link AffixCategory#DEFENSE} 词条封了不改变对玩家的战斗压力 (只是怪更
 * 脆), 不作封印目标 (返 null)。注意本工程 35 词条的 Champions category 由 affix_setting JSON 驱动 (防御≈生存/攻击≈
 * 战斗/CC≈控制), 故"按 AffixPool 取 SealCategory"与"按 AffixCategory 过滤可封性"是两个正交判定: 池决定窗口门控
 * (被动/机制), category 决定该词条是否值得封 (施压 vs 纯防御)。
 *
 * compileOnly 隔离: 本类 import top.theillusivec4.champions.* —— 仅 ModList 守卫下经 {@link AgentIntegrationBootstrap}
 * 触达。
 */
final class AgentAffixClassifier {

    private AgentAffixClassifier() {
    }

    /** registry path (词条名小写) -> AffixDef 反查表 (与 MiningAffixTypes.registryName 同口径: 枚举名小写)。 */
    private static final Map<String, AffixDef> BY_PATH = buildPathIndex();

    private static Map<String, AffixDef> buildPathIndex() {
        Map<String, AffixDef> index = new HashMap<>();
        for (AffixDef def : AffixDef.values()) {
            index.put(def.name().toLowerCase(Locale.ROOT), def);
        }
        return index;
    }

    /** AffixDef -> 该词条 Champions category 缓存 (避免每次封印申请重读 JSON 驱动的 category; 由 affixOf 反推)。 */
    private static final Map<AffixDef, SealCategory> SEAL_CATEGORY = buildSealCategory();

    private static Map<AffixDef, SealCategory> buildSealCategory() {
        Map<AffixDef, SealCategory> map = new EnumMap<>(AffixDef.class);
        for (AffixDef def : AffixDef.values()) {
            map.put(def, def.pool() == AffixPool.SKILL ? SealCategory.MECHANIC : SealCategory.PASSIVE);
        }
        return map;
    }

    /**
     * 把真 IAffix 归类为特勤封印类别。非我方词条 (命名空间非 champions, 或 champions 下但非我方 35 词条) / 纯防御词条 (不施压, 封了无意义)
     * 返 null (= 不可封, 集成层据此跳过该词条不进封印候选)。
     *
     * @param affix 真 IAffix (来自 IChampion.getServer().getAffixes())
     * @return {@link SealCategory#PASSIVE} / {@link SealCategory#MECHANIC}; 不可封返 null
     */
    static SealCategory classify(IAffix affix) {
        if (affix == null) {
            return null;
        }
        ResourceLocation id = affix.getIdentifier();
        // 我方 35 词条经 Champions 的 DeferredRegister 注册, 真 namespace = champions (非 miningdim); 故守卫判 champions,
        // 再由下方 BY_PATH 把 Champions 自家词条 (molten/arctic 等, 不在我方 path 表) 过滤掉。
        if (id == null || !AgentSystem.CHAMPIONS_MODID.equals(id.getNamespace())) {
            return null; // 外来命名空间词条: 不归本工程封印体系。
        }
        AffixDef def = BY_PATH.get(id.getPath());
        if (def == null) {
            return null; // champions 命名空间下但非我方已知 AffixDef (Champions 自家词条 / 异常注册): 不可封。
        }
        // Champions 三分类过滤: 纯防御词条封了不减玩家压力, 不作封印目标。
        if (affix.getCategory() == AffixCategory.DEFENSE) {
            return null;
        }
        return SEAL_CATEGORY.get(def);
    }

    /**
     * 词条注册名 (集成层与纯逻辑层 SealRegistry 同口径的 affixId): identifier 的全限定字符串 (namespace:path),
     * 作为 {@link com.miningdim.job.agent.SealRegistry} 账本键 + 恢复时按 id 匹配真 IAffix。
     *
     * @param affix 真 IAffix
     * @return 全限定注册名 (namespace:path)
     */
    static String affixId(IAffix affix) {
        return affix.getIdentifier().toString();
    }
}
