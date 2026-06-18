package com.miningdim.job.tarot;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraftforge.event.entity.living.LivingDeathEvent;
import net.minecraftforge.event.entity.living.LivingHurtEvent;
import net.minecraftforge.event.entity.living.LivingKnockBackEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;

/**
 * 塔罗师战斗窗口事件接线 (TarotReader spec 第六/十二章)。读 {@link TarotCombatState} 的 per-player 窗口快照,
 * 在 forge 战斗事件上施加有状态机制。由 {@link TarotSystem} new 一个实例注册到 forgeBus。
 *
 * 与共享易伤 {@link com.miningdim.effect.VulnerabilityHurtHandler} 的关系: 二者都订阅 LivingHurtEvent 但语义独立
 * (易伤=乘伤, 本类=无敌归零/反伤/吸血), 各自独立结算不冲突。无敌窗用最高优先级在伤害结算前归零, 提前结束本次受伤。
 *
 * 防递归: 反伤对攻击者回击用绕过本类的 magic 伤害源 (不触发吸血/反伤的再判定; 攻击者若也有反伤窗会再读, 但
 * 回击伤害来自 caster 而非 victim, 链条天然不闭环)。
 */
public final class TarotCombatHandlers {

    /** package-private: 仅 {@link TarotSystem} 实例化注册事件。 */
    TarotCombatHandlers() {
    }

    /** 免疫击退窗 (倒吊人逆位/力量闪耀): 窗口内强度归零 (严禁 AttributeModifier; 仿厨师稳膛红线)。 */
    @SubscribeEvent
    public void onKnockback(LivingKnockBackEvent event) {
        if (!(event.getEntity() instanceof Player player)) {
            return;
        }
        MinecraftServer server = player.getServer();
        if (server == null) {
            return;
        }
        if (TarotCombatState.hasWindow(player.getUUID(), TarotCombatState.WindowKind.KNOCKBACK_IMMUNITY,
                server.getTickCount())) {
            event.setStrength(0.0F);
        }
    }

    /** 受伤侧: 无敌窗归零伤害 (真免疫); 反伤窗回击攻击者 (单次封顶)。 */
    @SubscribeEvent
    public void onLivingHurtVictim(LivingHurtEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer victim)) {
            return;
        }
        MinecraftServer server = victim.getServer();
        if (server == null) {
            return;
        }
        long now = server.getTickCount();

        // 无敌窗 (愚者闪耀): 全免疫, 伤害归零并提前返回 (不再吃反伤等后续)。
        if (TarotCombatState.hasWindow(victim.getUUID(), TarotCombatState.WindowKind.INVULNERABLE, now)) {
            event.setAmount(0.0F);
            return;
        }

        // 反伤窗 (正义正位): 把本次受到伤害的 percent 回击攻击者, 单次封顶 perHitCap。
        double reflectPct = TarotCombatState.reflectPercent(victim.getUUID(), now);
        if (reflectPct > 0.0D && event.getSource().getEntity() instanceof LivingEntity attacker
                && attacker != victim) {
            double cap = TarotCombatState.reflectPerHitCap(victim.getUUID(), now);
            double reflected = Math.min(event.getAmount() * reflectPct, cap);
            if (reflected > 0.0D && attacker.level() instanceof ServerLevel level) {
                // 用 magic 源 (绕过攻击者护甲/抗性的近战格挡; 不递归触发本类吸血)。
                attacker.hurt(level.damageSources().magic(), (float) reflected);
            }
        }
    }

    /** 出伤侧: 吸血窗 (倒吊人逆位/恶魔) —— 使用者对敌造成伤害后, 回血 percent。 */
    @SubscribeEvent
    public void onLivingHurtSource(LivingHurtEvent event) {
        DamageSource source = event.getSource();
        if (!(source.getEntity() instanceof ServerPlayer attacker)) {
            return;
        }
        if (event.getEntity() == attacker) {
            return; // 自伤不吸血。
        }
        MinecraftServer server = attacker.getServer();
        if (server == null) {
            return;
        }
        double pct = TarotCombatState.lifestealPercent(attacker.getUUID(), server.getTickCount());
        if (pct > 0.0D) {
            attacker.heal((float) (event.getAmount() * pct));
        }
    }

    /**
     * 复活契约 (死神逆位): 拦截 1 次致死, 复活回血 (一次性; 仿 NanoReactorHandler 的拦截致死)。
     * HIGH 优先级: 须早于 {@link TarotSystem#onDeath} 的清理 (后者读 isCanceled 跳过, 保留 continuation)。
     */
    @SubscribeEvent(priority = EventPriority.HIGH)
    public void onLivingDeath(LivingDeathEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }
        MinecraftServer server = player.getServer();
        if (server == null) {
            return;
        }
        double revive = TarotCombatState.consumeDeathContract(player.getUUID(), server.getTickCount());
        if (revive < 0.0D) {
            return; // 无契约或已用过: 不拦, 玩家正常死亡。
        }
        event.setCanceled(true);
        player.setHealth((float) Math.min(revive, player.getMaxHealth()));
        player.clearFire();
    }
}
