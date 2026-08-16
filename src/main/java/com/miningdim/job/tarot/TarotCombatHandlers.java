package com.miningdim.job.tarot;

import com.miningdim.combat.PlayerDamageReduction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraftforge.event.entity.living.LivingDeathEvent;
import net.minecraftforge.event.entity.living.LivingAttackEvent;
import net.minecraftforge.event.entity.living.LivingHealEvent;
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

    /**
     * package-private: 仅 {@link TarotSystem} 实例化注册事件。同时在此登记预知减伤源
     * {@link PlayerDamageReduction#register}: 本类是预知窗的唯一消费方 (onLivingHurtVictim 消费窗口 + 暂存),
     * 由它自己登记可以避免把职业内部窗口语义泄露到 {@link TarotSystem} 门面; TarotSystem 只 new 一次 (字段初始化),
     * 注册表 (CopyOnWriteArrayList) 对顺序不敏感。
     */
    TarotCombatHandlers() {
        PlayerDamageReduction.register(new PremonitionReduction());
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
        long now = server.getTickCount();
        if (TarotCombatState.hasWindow(player.getUUID(), TarotCombatState.WindowKind.KNOCKBACK_IMMUNITY, now)
                || TarotCombatState.hasWildOverdrive(player.getUUID(), now)) {
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
     *
     * 女祭司正位预知 (F096): 不再就地 setAmount 施加减伤, 而是暂存到 {@link TarotCombatState} 交
     * {@link PlayerDamageReduction} 在 LOWEST 与凝脂/矿脉抗性/烈酒钝感一起连乘并吃同一个 PLAYER_MAX_REDUCTION
     * 全局帽 (见本类底部 {@link PremonitionReduction})。真·免疫窗 (愚者 setAmount(0), 上面那段) 是另一语义,
     * 不受该帽约束, 保留原样就地归零。
     *
     * <p>复核追加修正: 上面这条 stash 机制只管住了"最终打到玩家身上的真实伤害"要在 LOWEST 统一乘算, 但本
     * handler 剩下的记账/反伤/分摊三段逻辑仍在默认优先级里跑, 若继续读 {@code event.getAmount()} 会读到
     * "预知减伤生效前"的原始值 —— F096 改之前这里是就地 setAmount, 三段逻辑读到的天然已经是预知减伤后的量;
     * 改成 stash 之后若不跟着调整, 相当于把预知的减伤收益从"记账/反伤/队友分摊也按比例少算"退化成"记账/
     * 反伤/队友分摊完全不知道预知生效过", 数值直接翻倍。修法: 把 reduction 提到方法顶层, 三段逻辑各自按
     * {@code event.getAmount() * (1 - reduction)} 算它们自己要用的基数, 但不把这个基数写回 event —— 写回去
     * 会导致 LOWEST 的 PlayerDamageReduction 把 reduction 在同一次受击里乘算两遍 (reduction 本身就是
     * PlayerDamageReduction 注册表里的一个源)。队友分摊仍然只从 event.getAmount() 的原始值里扣
     * distributed (而非从 reduction 调整后的值里扣), 这样受害者剩下的那部分原始伤害在 LOWEST 才被
     * reduction 连同其它减伤源一起乘算一次, 不多不少。
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

        // 女祭司正位预知：仅首次实际受击消费窗口，暂存减伤率交 LOWEST 单点结算连乘 (F096, 不再就地 setAmount)。
        // reduction 同时留在方法作用域内, 供下面记账/反伤/分摊三段各自换算 (复核追加修正, 见类头说明)。
        double reduction = 0.0D;
        if (event.getAmount() > 0.0F) {
            reduction = TarotCombatState.consumePremonitionReduction(victim.getUUID(), now);
            if (reduction > 0.0D) {
                TarotCombatState.stashPremonitionReduction(victim.getUUID(), now, reduction);
                victim.displayClientMessage(Component.translatable(
                        "message.miningdim.tarot.premonition.block", Math.round(reduction * 100.0D)), true);
            }
        }
        float amountAfterPremonition = (float) (event.getAmount() * (1.0D - reduction));

        // 延迟记账冻死窗 (倒吊人闪耀): 本次伤害挂账; 若会致命则把伤害削到 "留 1 滴血" (冻结不死), 否则照常承伤。
        // 挂起账本在窗口结束按 50% 结算 (TarotEffectEngine.settleLedger)。spec "致命伤冻结不死"。
        // 记账值按预知减伤后的量算 (复核追加修正) —— 结算走 setHealth 直接扣血, 不再经过 LOWEST, 记原始值
        // 等于让预知对这条延迟伤害完全失效。lethalGuard 的钳制比较仍用原始 event.getAmount() (保守, 多护一点
        // 不是 bug), 不受这次修正影响。
        if (TarotCombatState.hasLedger(victim.getUUID(), now)) {
            TarotCombatState.recordLedgerDamage(victim.getUUID(), amountAfterPremonition, now);
            float lethalGuard = victim.getHealth() - 1.0F; // 最多扣到剩 1 血 (冻结致命伤)。
            if (event.getAmount() > lethalGuard) {
                event.setAmount(Math.max(0.0F, lethalGuard));
            }
        }

        // 反伤窗 (正义正位): 把本次受到伤害的 percent 回击攻击者, 单次封顶 perHitCap。按预知减伤后的量算
        // (复核追加修正) —— 回击走独立的 attacker.hurt(magic), 不经过受害者的 LOWEST 结算, 用原始值等于
        // 让预知对这一击的反伤完全失效。
        double reflectPct = TarotCombatState.reflectPercent(victim.getUUID(), now);
        if (reflectPct > 0.0D && event.getSource().getEntity() instanceof LivingEntity attacker
                && attacker != victim) {
            double cap = TarotCombatState.reflectPerHitCap(victim.getUUID(), now);
            double reflected = Math.min(amountAfterPremonition * reflectPct, cap);
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

        // 队友分摊 (节制闪耀): distributed 按预知减伤后的量算 (复核追加修正, 队友承伤不该因为受害者自己有
        // 预知窗而翻倍), 但从 event.getAmount() 的原始值里扣 —— 受害者剩下的那部分原始伤害仍要在 LOWEST
        // 被 reduction 连同其它减伤源乘算一次; 若这里改成从 amountAfterPremonition 里扣并写回 event, LOWEST
        // 会把 reduction 在同一次受击里乘两遍。队友直接 setHealth (不经过队友自己的 LOWEST 减伤链), 维持
        // 原有语义不变, 只修正被分摊的基数。
        TarotCombatState.DamageShareSnapshot share = TarotCombatState.damageShare(victim.getUUID(), now);
        if (share != null && share.percent() > 0.0D && event.getAmount() > 0.0F) {
            java.util.List<ServerPlayer> recipients = share.members().stream()
                    .filter(id -> !id.equals(victim.getUUID()))
                    .map(server.getPlayerList()::getPlayer)
                    .filter(java.util.Objects::nonNull)
                    .filter(Player::isAlive)
                    .toList();
            if (!recipients.isEmpty()) {
                float distributed = (float) (amountAfterPremonition * share.percent());
                event.setAmount(Math.max(0.0F, event.getAmount() - distributed));
                float each = distributed / recipients.size();
                for (ServerPlayer recipient : recipients) {
                    recipient.setHealth(Math.max(0.0F, recipient.getHealth() - each));
                }
            }
        }
    }

    /** 隐士闪耀期间不可攻击；覆盖近战、弹射物及标准枪械伤害源。 */
    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public void onLivingAttack(LivingAttackEvent event) {
        if (!(event.getSource().getEntity() instanceof ServerPlayer attacker)) {
            return;
        }
        MinecraftServer server = attacker.getServer();
        if (server != null && TarotCombatState.restricted(attacker.getUUID(),
                TarotCombatState.Restriction.ATTACK_LOCK, server.getTickCount())) {
            event.setCanceled(true);
        }
    }

    /** 星星逆位力竭期间拒绝全部治疗。 */
    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public void onLivingHeal(LivingHealEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }
        MinecraftServer server = player.getServer();
        if (server != null && TarotCombatState.restricted(player.getUUID(),
                TarotCombatState.Restriction.HEALING_BLOCK, server.getTickCount())) {
            event.setAmount(0.0F);
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
        long now = server.getTickCount();
        double healthRatio = attacker.getMaxHealth() <= 0.0F
                ? 1.0D : attacker.getHealth() / attacker.getMaxHealth();
        double pct = Math.max(TarotCombatState.lifestealPercent(attacker.getUUID(), now),
                TarotCombatState.wildOverdriveLifestealPercent(attacker.getUUID(), now, healthRatio));
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

    /**
     * 女祭司正位预知的命名减伤源 (F096): 把 {@link #onLivingHurtVictim} 同 tick 暂存的减伤率交
     * {@link PlayerDamageReduction} 在 LOWEST 单点取走并入连乘, 使预知减伤吃到与其它职业减伤源相同的
     * PLAYER_MAX_REDUCTION 全局帽, 不再绕开单点结算。
     */
    private static final class PremonitionReduction implements PlayerDamageReduction.ReductionSource {

        @Override
        public String name() {
            return "tarot_premonition";
        }

        @Override
        public double rate(Player victim, DamageSource source) {
            MinecraftServer server = victim.getServer();
            if (server == null) {
                return 0.0D; // 非服务端上下文不可能有窗口 (仿本类其它 handler 的 server==null 早退惯例)。
            }
            return TarotCombatState.takePremonitionReduction(victim.getUUID(), server.getTickCount());
        }
    }
}
