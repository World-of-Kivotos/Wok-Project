package com.miningdim.champion.integration;

import com.miningdim.champion.MiningChampions;
import net.minecraftforge.event.entity.living.LivingConversionEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

/**
 * 精英不被原版的生物转化换掉 (僵尸溺水变溺尸、猪灵系在主世界僵尸化、骷髅冻成流浪者、僵尸村民被治愈等)。
 *
 * <p>转化走 {@code Mob.convertTo}: 新建一只实体, 只拷位置、装备、自定义名等几样, 然后 discard 旧的 —— 不发死亡事件,
 * capability 也不跟过去。于是星级、词条、血池、贡献账本与奖励一起蒸发, 留下一只普通生物; 世界 BOSS 承诺的"常驻"
 * 只挡得住自然消失, 挡不住这条路 (追着玩家走进水里的僵尸 BOSS 四十多秒后就成了一只溺尸)。唯一的否决口是
 * {@link LivingConversionEvent.Pre}。
 */
public final class ChampionConversionGuard {

    /** 否决之后隔多久原版再来问一次 (tick): 不推迟的话, 泡在水里的僵尸每 tick 都会发一次事件。 */
    public static final int RETRY_DELAY_TICKS = 200;

    @SubscribeEvent
    public void onConversion(LivingConversionEvent.Pre event) {
        if (MiningChampions.isChampion(event.getEntity())) {
            event.setCanceled(true);
            event.setConversionTimer(RETRY_DELAY_TICKS);
        }
    }
}
