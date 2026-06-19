package com.miningdim.job.agent;

/**
 * 封印词条类别门控 (SpecialAgent_Job_DesignSpec 六章封印支线 + 第四章总表)。
 *
 * 封印只压"词条效果"且高度克制, 按词条类别分两门:
 *  - {@link #PASSIVE} 被动词条 (护甲/减伤/子弹抗性/吸血等常驻效果): L3 起可临时封印。
 *  - {@link #MECHANIC} 机制/核心类词条 (命定之死/小男孩/天雷等读条核弹技能): 仅 L8+ 可短暂封印,
 *    绝不永久移除, 窗口比被动更短 (3s->5s)。
 *
 * 本枚举只承载"门控类别"语义, 不映射 Champions 任何 IAffixType —— 真词条到类别的归类落在集成层
 * (compileOnly, ModList 守卫), 纯逻辑层只按类别 + 干员等级 + 目标星级裁决可否封印 (见 {@link SealPlan})。
 */
public enum SealCategory {

    /** 被动词条: L3 起可封 (六章被动支线)。 */
    PASSIVE,

    /** 机制/核心类词条 (命定之死/小男孩/天雷): 仅 L8+ 可短暂封印, 绝不永久移除 (六章类型门控)。 */
    MECHANIC
}
