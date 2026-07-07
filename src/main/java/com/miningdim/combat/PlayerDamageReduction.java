package com.miningdim.combat;

import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.player.Player;
import net.minecraftforge.event.entity.living.LivingHurtEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 玩家受击减伤的【单点乘法结算】(对标精英怪 {@link com.miningdim.champion.ChampionRedlines}: 各源剩余系数连乘
 * + 保底钳)。各职业把自己的【命名减伤源】register 进来 (凝脂 / 矿脉抗性 / 烈酒钝感 …), 本处在 LivingHurtEvent
 * {@link EventPriority#LOWEST} 统一结算:
 *
 *   keep = max(∏(1 - rᵢ), {@link CombatConstants#PLAYER_MIN_KEEP}); 最终伤害 = amount × keep
 *
 * 为何单点而非各职业各自 setAmount: 乘法顺序无关、各自 setAmount 也能乘对, 但 (1) 无法施加"等效总减伤上限"
 * (PLAYER_MAX_REDUCTION 全局帽), (2) 散落不可审计。单点把两者都解决。LOWEST 使其在易伤放大
 * ({@link com.miningdim.effect.VulnerabilityHurtHandler}, 默认优先级) 之后跑 —— 先放大后减伤, 顺序确定。
 *
 * 精英怪受击走另一套 (ChampionRedlines / ChampionBloodPoolHandler), 与本处互不干扰 (本处仅 victim 为 Player 生效)。
 * 注册表为静态: 各职业 register/setup 期登记源, handler 运行期读表, 对 register 顺序不敏感。
 */
public final class PlayerDamageReduction {

    /** 一个命名减伤源: 对某次受击给出减伤率 [0,1] (scope 不符/未激活返回 0)。 */
    public interface ReductionSource {

        /** 源名 (审计/诊断)。 */
        String name();

        /** 本次受击的减伤率 [0,1]; 不适用返回 0。 */
        double rate(Player victim, DamageSource source);
    }

    private static final List<ReductionSource> SOURCES = new CopyOnWriteArrayList<>();

    /** 包级可见: 仅 {@link CombatSystem} 实例化一次挂 forgeBus (实例 @SubscribeEvent handler); 其余方法皆静态。 */
    PlayerDamageReduction() {
    }

    /** 注册一个命名减伤源 (各职业在自己 register/setup 内调一次)。 */
    public static void register(ReductionSource source) {
        if (source == null) {
            throw new IllegalArgumentException("reduction source must not be null");
        }
        SOURCES.add(source);
    }

    /** 清空所有源 (仅 GameTest 隔离用)。 */
    public static void unregisterAll() {
        SOURCES.clear();
    }

    /** 当前已注册源数 (诊断/测试)。 */
    public static int sourceCount() {
        return SOURCES.size();
    }

    /**
     * 各减伤率连乘后的剩余系数, 钳到全局帽 (纯函数, 便于 GameTest): keep = max(∏(1-rᵢ), PLAYER_MIN_KEEP)。
     * 任意 rᵢ ∉ [0,1] 抛 IllegalArgumentException (不掩盖脏值, 与 ChampionRedlines 同纪律)。
     */
    public static double keepFactor(double... rates) {
        double keep = 1.0D;
        for (double r : rates) {
            if (r < 0.0D || r > 1.0D) {
                throw new IllegalArgumentException("reduction rate out of [0,1]: " + r);
            }
            keep *= (1.0D - r);
        }
        return Math.max(keep, CombatConstants.PLAYER_MIN_KEEP);
    }

    /** 单点受击结算 (LOWEST: 在易伤放大之后)。仅玩家受击生效; 精英怪受击走 ChampionRedlines。 */
    @SubscribeEvent(priority = EventPriority.LOWEST)
    public void onLivingHurt(LivingHurtEvent event) {
        if (SOURCES.isEmpty()) {
            return;
        }
        if (!(event.getEntity() instanceof Player player)) {
            return;
        }
        // 收集本次"生效"的减伤率 (源不适用返 0, 跳过); 脏值 (负/>1) 不在此静默吞或钳, 交 keepFactor 统一裁决,
        // 与纯函数同口径 (combat-effect-01: live 路径此前 r<=0 静默 continue + Math.min(1,r) 静默钳, 与 keepFactor
        // 的脏值拒收契约分叉 —— 脏减伤源被掩盖而非暴露)。
        double[] rates = new double[SOURCES.size()];
        int n = 0;
        for (ReductionSource source : SOURCES) {
            double r = source.rate(player, event.getSource());
            if (r == 0.0D) {
                continue; // 源不适用 (scope 不符/未激活): 不计入, 不影响结算。
            }
            rates[n++] = r;
        }
        if (n == 0) {
            return; // 无任何生效减伤源: 不改伤害。
        }
        // keepFactor 对任一脏 rate (负/>1) 抛 IllegalArgumentException (不静默吞), 并完成连乘 + 全局帽钳制。
        double keep = keepFactor(java.util.Arrays.copyOf(rates, n));
        event.setAmount((float) (event.getAmount() * keep));
    }
}
