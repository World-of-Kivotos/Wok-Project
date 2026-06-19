package com.miningdim.job.brewer;

import com.miningdim.core.Subsystem;
import net.minecraftforge.eventbus.api.IEventBus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 酿酒师子系统入口 (模块化铁律 3 自注册)。等级/经验走共享职业框架 capability (JobId.BREWER), 故须在
 * JobFrameworkSystem 之后注册 (见 MiningDim.registerSubsystems)。
 *
 * 本子系统是"至少七天周期性制造职业": 酿酒台酿基酒 (按等级 roll 品质) -> 酒窖箱陈酿年份 (干小麦门控, 年份
 * 时钟用现实挂钟、潮汐 Tide 味保留在月相加成) -> 喝酒按 S = 年份×品质系数 获增益, 闪耀档触发永久 (一条命) 特殊增益。
 *
 * 分期接入 (阶段 2 本提交): 酒物品 (干小麦 + 九种酒) + 创造栏 + 喝酒效果; 喝酒经 {@link WineItem} 的
 * finishUsingItem 覆写直接结算 (非事件), 故无需订阅 forgeBus。酿酒台 / 酒窖箱 / 永久增益 / 配方 在后续阶段
 * 于此 register 内继续接入 (DeferredRegister + forge 事件), 零结构改动。
 */
public final class BrewerSystem implements Subsystem {

    private static final Logger LOGGER = LoggerFactory.getLogger("miningdim/brewer");

    @Override
    public void register(IEventBus modBus, IEventBus forgeBus) {
        BrewerItems.register(modBus);
        BrewerTab.register(modBus);
        LOGGER.info("[miningdim] brewer subsystem registered (items: dried wheat + 9 wines + drink effects)");
    }

    @Override
    public String name() {
        return "BrewerSystem";
    }
}
