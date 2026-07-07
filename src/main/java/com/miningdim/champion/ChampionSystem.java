package com.miningdim.champion;

import com.miningdim.champion.bloodpool.BloodPoolRegistry;
import com.miningdim.champion.integration.ChampionAttackHandler;
import com.miningdim.champion.integration.ChampionBloodPoolHandler;
import com.miningdim.champion.integration.ChampionBossBarHandler;
import com.miningdim.champion.integration.ChampionCounterUnitHandler;
import com.miningdim.champion.integration.ChampionDeathMarkHandler;
import com.miningdim.champion.integration.ChampionDotTickHandler;
import com.miningdim.champion.integration.ChampionParticleHandler;
import com.miningdim.champion.integration.ChampionPromoter;
import com.miningdim.champion.integration.ChampionRewardHandler;
import com.miningdim.champion.integration.ChampionSelfEffectHandler;
import com.miningdim.champion.integration.ChampionSelfRepairHandler;
import com.miningdim.champion.integration.ChampionSummonHandler;
import com.miningdim.champion.integration.ChampionVisualDisruptionHandler;
import com.miningdim.champion.reward.ContributionTracker;
import com.miningdim.core.Subsystem;
import net.minecraft.world.entity.Entity;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 精英怪子系统入口 (implements core.Subsystem; ChampionStarAffix spec 第十四章实现拆分)。模块化铁律 3: 唯一入口,
 * 在 register 内完成本子系统的注册/订阅/接线; MiningDim 主类 List 列尾追加一行即装配。
 *
 * 自研 (2026-07-07 脱离 Champions mod 依赖): 精英怪不再基于第三方 Champions mod, 改为自研 —— "标记冠军 + 存星级/
 * 词条"走我方 {@link MiningChampions} capability, 升格/血池/减伤/攻击/DoT/自身被动/血条/粒子/奖励全是本工程代码,
 * 零 top.theillusivec4.champions.* 依赖。故本入口【无条件】注册 (不再 ModList.isLoaded("champions") 守卫), 且
 * integration 层从"dev 不加载的 compileOnly mod、只能真服验"变为 dev GameTest 可加载可验。
 *
 * 接线点:
 *  - 冠军 capability: {@link MiningChampions} 同时挂 modBus (RegisterCapabilities) + forgeBus (AttachCapabilities 给 Mob)。
 *  - 效果聚合器反泄漏清理: {@link ChampionEffectRegistries.CleanupHandler} 挂 forgeBus。
 *  - 升格 seam: {@link ChampionSpawnSeam#bind} 注入 {@link ChampionPromoter}, 压力子系统 spawnMob 成功后回调。
 *  - 效果 handler 全挂 forgeBus (血池减伤+拦死 / 攻击 on-hit / DoT 秒结算 / 自身被动 / 奖励 / BOSS 血条 / 词条粒子)。
 *  - 生命周期清理: ServerStoppingEvent 清血池/贡献账本/聚合器/seam, 防跨存档脏引用。
 */
public final class ChampionSystem implements Subsystem {

    private static final Logger LOGGER = LoggerFactory.getLogger("miningdim/champion");

    @Override
    public void register(IEventBus modBus, IEventBus forgeBus) {
        // 冠军 capability: 注册能力类型 (modBus) + 给每个 Mob 挂 Provider (forgeBus 泛型监听)。
        // 混总线对象不可整体 register (与 entry.EntrySystem 同坑): RegisterCapabilitiesEvent 是 IModBusEvent 走 modBus,
        // AttachCapabilitiesEvent<Entity> 是 forge 泛型事件走 forgeBus.addGenericListener; 整体 register 会在另一类事件抛
        // IllegalArgumentException。故按方法各挂正确总线 + 泛型过滤 Entity。
        MiningChampions champions = new MiningChampions();
        modBus.addListener(champions::onRegisterCapabilities);
        forgeBus.addGenericListener(Entity.class, champions::onAttachCapabilities);

        // 生命周期清理 + 效果聚合器反泄漏清理订阅者。
        forgeBus.register(this);
        forgeBus.register(new ChampionEffectRegistries.CleanupHandler());

        // 升格 seam: 压力子系统 spawnMob 成功后经 ChampionSpawnSeam.promote 回调本 promoter 升格 (自研, 无 Champions)。
        ChampionSpawnSeam.bind(new ChampionPromoter());

        // 效果 handler 全挂 forgeBus (自研后无条件注册, 不再依赖 Champions 加载)。
        forgeBus.register(new ChampionBloodPoolHandler());   // 净减伤单点 + 血池拦死 + 渲染镜像
        forgeBus.register(new ChampionRewardHandler());      // 贡献池盖章双门槛 + 经济闸
        forgeBus.register(new ChampionBossBarHandler());     // 自建 BOSS 血条 (名/星级/词条名)
        forgeBus.register(new ChampionAttackHandler());      // 攻击类 on-hit (即时伤/DoT刷层/易伤/损甲/混沌限频)
        forgeBus.register(new ChampionDotTickHandler());     // DoT 每秒结算 + 寒霜减速
        forgeBus.register(new ChampionParticleHandler());    // 词条签名环境粒子
        forgeBus.register(new ChampionSelfEffectHandler());  // 自身被动 (再生/易燃再生/反震反伤/高速移动/超速)
        // 自足技能 (Stage2 批3): 各技能独立 handler, onServerUpdate 状态机 + 红线聚合器/跨冠军锁。
        forgeBus.register(new ChampionVisualDisruptionHandler()); // 视觉干扰: 周期失明 (控制聚合闸)
        forgeBus.register(new ChampionSelfRepairHandler());       // 自我修复: 低血定身读条回血 (近战打断)
        forgeBus.register(new ChampionCounterUnitHandler());      // 反击单元: 锁定窗反伤 (三层封顶)
        forgeBus.register(new ChampionSummonHandler());           // 支援召唤: 低星同型援军 (经济全排除)
        forgeBus.register(new ChampionDeathMarkHandler());        // 命定之死: 动态 DPS 阈值标记/处决

        // 调试命令 /mchampion summon (取代已移除的 Champions /champions summon; OP 真服按需召唤指定星级+词条冠军)。
        forgeBus.addListener(this::onRegisterCommands);

        LOGGER.info("[champion] self-hosted champion system registered (capability + spawn promoter + effect handlers, no Champions dependency)");
    }

    @Override
    public String name() {
        return "ChampionSystem";
    }

    /** 注册 /mchampion 调试命令 (自研冠军按需召唤; 取代已移除的 Champions /champions summon)。 */
    private void onRegisterCommands(RegisterCommandsEvent event) {
        ChampionCommands.register(event.getDispatcher());
    }

    /** 服务端停止: 清纯逻辑层运行态 (血池/贡献账本/聚合器/锁定登记) + 解 seam 绑定, 防跨存档/跨重启脏引用。 */
    @SubscribeEvent
    public void onServerStopping(ServerStoppingEvent event) {
        BloodPoolRegistry.reset();
        ContributionTracker.reset();
        ChampionEffectRegistries.reset();
        ChampionTargetLocks.reset();
        ChampionSpawnSeam.unbind();
    }
}
