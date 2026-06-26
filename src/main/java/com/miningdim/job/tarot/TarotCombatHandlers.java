package com.miningdim.job.tarot;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraftforge.event.entity.living.LivingDeathEvent;
import net.minecraftforge.event.entity.living.LivingHurtEvent;
import net.minecraftforge.event.entity.living.LivingKnockBackEvent;
import net.minecraftforge.event.entity.living.MobEffectEvent;
import net.minecraftforge.eventbus.api.Event;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;

import java.util.UUID;

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

    /**
     * 免疫窗 (太阳/世界/力量/恶魔闪耀的 IMMUNITY op "免疫缓慢/失明/反胃/凋零..."): 对持窗玩家拒绝施加其免疫集内的
     * MobEffect。{@code MobEffectEvent.Applicable} HasResult: DENY 阻止施加。按 effect 注册名比对窗口存的免疫集
     * (引擎写入时已校验是真 effect)。易伤的免疫不在此处 (走 {@link com.miningdim.effect.VulnerabilityHurtHandler}
     * 仲裁点跳过放大, 易伤本体仍可挂在身上作 HUD/被净化判定)。
     */
    @SubscribeEvent
    public void onMobEffectApplicable(MobEffectEvent.Applicable event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }
        MinecraftServer server = player.getServer();
        if (server == null) {
            return;
        }
        MobEffect effect = event.getEffectInstance().getEffect();
        ResourceLocation key = BuiltInRegistries.MOB_EFFECT.getKey(effect);
        if (key == null) {
            return;
        }
        if (TarotCombatState.immuneToEffect(player.getUUID(), key.toString(), server.getTickCount())) {
            event.setResult(Event.Result.DENY);
        }
    }

    /**
     * 受伤侧: 无敌窗归零伤害 (真免疫); 延迟记账冻死窗挂账并冻结致命伤 (倒吊人闪耀); 反伤窗回击 (正义正位);
     * 累计反击窗逐攻击者累计伤害 (正义闪耀, 窗口结束统一回击)。
     */
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

        // 无敌窗 (愚者闪耀): 全免疫, 伤害归零并提前返回 (不再吃反伤/记账等后续)。
        if (TarotCombatState.hasWindow(victim.getUUID(), TarotCombatState.WindowKind.INVULNERABLE, now)) {
            event.setAmount(0.0F);
            return;
        }

        // 延迟记账冻死窗 (倒吊人闪耀): 本次伤害挂账; 若会致命则把伤害削到 "留 1 滴血" (冻结不死), 否则照常承伤。
        // 挂起账本在窗口结束按 50% 结算 (TarotEffectEngine.settleLedger)。spec "致命伤冻结不死"。
        if (TarotCombatState.hasLedger(victim.getUUID(), now)) {
            TarotCombatState.recordLedgerDamage(victim.getUUID(), event.getAmount(), now);
            float lethalGuard = victim.getHealth() - 1.0F; // 最多扣到剩 1 血 (冻结致命伤)。
            if (event.getAmount() > lethalGuard) {
                event.setAmount(Math.max(0.0F, lethalGuard));
            }
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

        // 累计反击窗 (正义闪耀): 逐攻击者累计本次承伤 (基于事件原始伤害, 含被记账窗削减前的量); 窗口结束统一回击。
        if (TarotCombatState.hasReflectAccum(victim.getUUID(), now)
                && event.getSource().getEntity() instanceof LivingEntity src && src != victim) {
            TarotCombatState.recordReflectAccum(victim.getUUID(), src.getUUID(), event.getAmount(), now);
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
     * 复活契约 (死神逆位): 拦截 1 次致死, 复活回血 (一次性; 仿 NanoReactorHandler 的拦截致死)。命中即取消事件返回,
     * 不触发恋人绑定连死 (玩家未真死)。HIGH 优先级: 须早于 {@link TarotSystem#onDeath} 的清理 (后者读 isCanceled
     * 跳过, 保留 continuation)。
     *
     * 未被契约拦截的真死: 若死者处于恋人绑定中, 给其 partner 排一个 deathDelayTicks 后的连死任务 (spec "一方死另一方
     * 3 秒后死"), 并立即解绑 (双向)。partner 的连死走 setHealth(0) (若届时仍在线), 是 magic 之外的强制结算。
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
        long now = server.getTickCount();
        double revive = TarotCombatState.consumeDeathContract(player.getUUID(), now);
        if (revive >= 0.0D) {
            event.setCanceled(true);
            player.setHealth((float) Math.min(revive, player.getMaxHealth()));
            player.clearFire();
            return; // 契约救命: 未真死, 不触发绑定连死。
        }

        // 恋人绑定连死 (spec 恋人闪耀): 死者有未过期绑定则给 partner 排延迟连死任务, 并双向解绑。
        UUID partner = TarotCombatState.bondPartner(player.getUUID(), now);
        if (partner != null) {
            int delay = TarotCombatState.bondDeathDelay(player.getUUID());
            ServerPlayer partnerPlayer = server.getPlayerList().getPlayer(partner);
            TarotCombatState.clearBond(player.getUUID()); // 双向解绑 (避免连死再反向触发)。
            if (partnerPlayer != null && delay > 0) {
                // 延迟连死: 经调度器在 delay ticks 后对 partner setHealth(0); partner 此时已无绑定, 不会再连锁。
                TarotRuntime.scheduler().scheduleOnce(partnerPlayer, delay, p -> p.setHealth(0.0F));
            }
        }
    }
}
