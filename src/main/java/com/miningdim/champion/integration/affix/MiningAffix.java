package com.miningdim.champion.integration.affix;

import com.miningdim.champion.AffixDef;
import top.theillusivec4.champions.common.affix.core.BasicAffix;

/**
 * 本工程 35 词条的统一基类 (Champions 集成层; ChampionStarAffix spec 第七章词条 + 9A.2 净减伤单点铁律)。
 * extends Champions {@link BasicAffix} (= AbstractBasicAffix, 已提供 getIdentifier/getCategory/getTier/
 * isEnabled/applySetting/getSetting 等全套样板, 数据从 affix_setting JSON 注入), 故本类只需:
 *  (1) 持一个不可变 {@link AffixDef} 句柄, 把"已注册的 IAffix 实例"反向关联回纯逻辑词条定义, 供集中式受击
 *      handler (净减伤/DoT/反伤聚合) 读词条池/数值, 而非各词条自己 onHurt 串行减伤 (那会穿透 49% 净减伤红线)。
 *  (2) 不重写任何战斗钩子 (onHurt/onAttack/onDeath...): 战斗结算一律走玩家侧/受击侧单点 LivingHurtEvent 聚合
 *      (ChampionBloodPoolHandler) 与集中奖励 handler, 词条本体只承载身份+元数据 (第五/九章红线: 全局唯一减伤/
 *      易伤结算点)。category 由 affix_setting JSON 的 category 字段驱动 (AbstractBasicAffix.getCategory 读
 *      affixSetting.category), 故本类不重写 getCategory, JSON 与四池语义对齐 (防御≈生存/攻击≈战斗/CC≈控制技能)。
 *
 * compileOnly 隔离: 本类 import top.theillusivec4.champions.* —— 属 integration 隔离包, 仅
 * ModList.isLoaded("champions") 守卫下被 {@link MiningAffixTypes} 装配触达, dev GameTest 不加载。
 */
public class MiningAffix extends BasicAffix {

    private final AffixDef def;

    public MiningAffix(AffixDef def) {
        this.def = def;
    }

    /** 本词条对应的纯逻辑定义 (池/数值/互斥/最低★); 集中式 handler 据此解释数值。 */
    public AffixDef def() {
        return def;
    }
}
