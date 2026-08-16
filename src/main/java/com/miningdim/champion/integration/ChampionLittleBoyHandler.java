package com.miningdim.champion.integration;

import com.miningdim.champion.AffixDef;
import com.miningdim.champion.AffixQuality;
import com.miningdim.champion.ChampionDamageTypes;
import com.miningdim.champion.ChampionDiagnostics;
import com.miningdim.champion.ChampionLittleBoyPlan;
import com.miningdim.champion.MiningChampionData;
import com.miningdim.champion.MiningChampions;
import com.miningdim.champion.bloodpool.BloodPool;
import com.miningdim.champion.bloodpool.BloodPoolRegistry;
import net.minecraft.core.Holder;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageType;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.living.LivingDeathEvent;
import net.minecraftforge.event.entity.living.LivingHurtEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 冠军【技能词条·小男孩 LITTLE_BOY】(Stage2 批4 波2; ChampionStarAffix spec 7.4 一次性核弹) 效果施加 (集成层)。
 * 冠军有效血量占比首次 &lt;= {@link ChampionLittleBoyPlan#TRIGGER_HP_FRACTION} 时起手蓄力
 * {@link ChampionLittleBoyPlan#CHARGE_TICKS}tick (背水核弹, 玩家可预期), 起手即摘词条 (一次性语义, 防重触发);
 * 蓄力期玩家对本怪累计打出打断门槛伤害则失败大爆 (无 AOE), 否则半径 {@link ChampionLittleBoyPlan#BLAST_RADIUS}
 * 内玩家逐个吃 基础% x maxHP x 边缘衰减, 命中后逐个授 2s 免疫缓冲 (红线 3)。数值/门槛/衰减纯逻辑下沉
 * {@link ChampionLittleBoyPlan} (dev GameTest 真验), 本 handler 只做真服侧: 实体检出/血量占比读取/摘词条/蓄力表现/
 * 玩家来源伤害累计/引爆逐玩家 hurt + 缓冲授予。
 *
 * <p>三个事件入口:
 * <ul>
 *   <li>{@link #onServerTick} (END): 每 tick 推进在册蓄力 (光柱粒子 + 打断/引爆判决), 每
 *       {@value #SCAN_INTERVAL_TICKS}tick(1s) 按玩家 AABB 扫近处冠军检出到达背水血线的小男孩冠军起手
 *       (与 {@code ChampionBlinkHandler}/{@code ChampionDeathMarkHandler} 同扫描范式)。</li>
 *   <li>{@link #onChampionHurtDuringCharge} (LOWEST + receiveCanceled): 蓄力期本怪受【玩家直接/弹射】伤害累计进度
 *       (名义入伤口径, 与 {@code ContributionTracker}/{@code ChampionDeathMarkHandler} 采样同口径 —— 见方法注释)。</li>
 *   <li>{@link #onChampionDeath} (LivingDeathEvent): 蓄力期冠军死亡摘状态自然终止 (无引爆)。</li>
 * </ul>
 *
 * <p>状态机 (per-冠军, 单相 CHARGING): 起手建条目 (捕获品质/门槛/引爆 tick) -&gt; 每 tick 光柱 + 周期警告/不祥音 ->
 * 打断达标失败大爆 / 蓄力满引爆 / 冠军死亡自然终止, 三条出口均摘条目。CHARGING 期不追踪玩家 (核弹落点即冠军脚下,
 * 玩家自行躲开半径), 无预兆落点锁定。
 *
 * <p>反泄漏 (与 {@code ChampionBlinkHandler} 同纪律): 实例状态随本 handler 实例存活 (由 {@code ChampionSystem#register}
 * 挂 forgeBus), 冠军死亡摘除 + {@link #advanceCharges} 每 tick 检出实体失败 (despawn/区块卸载/换存档) 即摘条目;
 * 蓄力至多 5s 短命, 无静态账本, 故【无需 onServerStopping reset】(与闪光/命定同, 静态态才需报主线接停服清理)。
 */
public final class ChampionLittleBoyHandler {

    /** 诊断日志: 批4 波2 小男孩真服首验用 (起手/打断/引爆低频不门控; 逐玩家伤害与蓄力受击累计经 shouldTrace 门控)。 */
    private static final Logger LOGGER = LoggerFactory.getLogger("miningdim/champion/skill");

    /** 扫描周期 (tick): 1s 扫一次近玩家冠军检出到达背水血线者起手 (背水核弹, 1s 触发粒度玩家可接受)。 */
    private static final int SCAN_INTERVAL_TICKS = (int) ChampionLittleBoyPlan.TICKS_PER_SECOND;

    /**
     * 到场玩家统计 + 蓄力警告半径 (格; 主线裁定"16 格内存活玩家数" + spec"半径 16 玩家 actionbar 警告"): 起手瞬间此半径内
     * 存活玩家数决定打断门槛 (最少计 1), 蓄力期此半径内玩家收 actionbar 打断提示。
     */
    private static final double NEARBY_PLAYER_RADIUS = 16.0D;

    /** 蓄力光柱高度 (格; spec 9A.6 自脚到天大粒子光柱): 逐 tick 自冠军脚下向上喷 END_ROD 柱。 */
    private static final double PILLAR_HEIGHT = 16.0D;

    /** 光柱粒子步进 (格): 每 {@value #PILLAR_STEP} 格一段, 控每 tick sendParticles 调用数 (一次性核弹, 重粒子可接受)。 */
    private static final double PILLAR_STEP = 1.5D;

    /** 蓄力警告 actionbar 刷新周期 (tick): 每 0.5s 刷一次剩余秒 + 门槛 (actionbar 短命须周期续)。 */
    private static final int WARNING_ACTIONBAR_INTERVAL_TICKS = 10;

    /** 蓄力不祥音周期 (tick): 每 1s 一记 WITHER_SPAWN 高音催促 (与起手一记低音区分)。 */
    private static final int OMINOUS_SOUND_INTERVAL_TICKS = 20;

    /**
     * per-冠军蓄力状态; 冠军死亡摘除 + 每 tick 检出实体失败摘除 (双保险防泄漏)。实例态随 handler 存活, 蓄力至多 5s
     * 短命故无 TTL 清扫 (换存档后残留 UUID 首个 tick 即因 resolve 失败被 {@link #advanceCharges} 摘除, 自愈)。
     */
    private final Map<UUID, ChargeState> chargingByChampion = new HashMap<>();

    /**
     * 每 tick 推进在册蓄力 (光柱/打断/引爆需 tick 精度), 每 1s 扫近玩家冠军起手 (背水血线检出 1s 粒度足够)。
     */
    @SubscribeEvent
    public void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        MinecraftServer server = event.getServer();
        long nowTick = server.overworld().getGameTime();

        // 每 tick: 推进在册蓄力 (无状态直接早退)。
        if (!chargingByChampion.isEmpty()) {
            advanceCharges(server, nowTick);
        }

        // 1s 扫描: 检出到达背水血线的小男孩冠军起手。
        if (server.getTickCount() % SCAN_INTERVAL_TICKS == 0) {
            scanForIgnition(server, nowTick);
        }
    }

    /**
     * 每 tick 推进在册蓄力: 实体消失/死亡 -&gt; 摘除 (自然终止无引爆); 打断达标 -&gt; 失败大爆; 蓄力满 -&gt; 引爆;
     * 蓄力中 -&gt; 逐 tick 光柱 + 周期警告/不祥音。
     */
    private void advanceCharges(MinecraftServer server, long nowTick) {
        Iterator<Map.Entry<UUID, ChargeState>> it = chargingByChampion.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<UUID, ChargeState> entry = it.next();
            ChargeState state = entry.getValue();
            LivingEntity champion = resolve(server, state.dimension, entry.getKey());
            if (champion == null || !champion.isAlive() || !(champion.level() instanceof ServerLevel level)) {
                it.remove(); // 蓄力期死亡/despawn/区块卸载: 自然终止, 无引爆 (removeAffix 已在起手落定)。
                continue;
            }
            if (ChampionLittleBoyPlan.isInterrupted(state.accumulatedPlayerDamage, state.interruptThreshold)) {
                onInterrupted(champion, level, state);
                it.remove();
                continue;
            }
            if (nowTick >= state.chargeEndTick) {
                detonate(champion, level, state);
                it.remove();
                continue;
            }
            // 蓄力中: 光柱每 tick, 警告/不祥音周期。
            emitChargePillar(level, champion);
            if (nowTick % WARNING_ACTIONBAR_INTERVAL_TICKS == 0) {
                warnNearbyPlayers(level, champion, state, nowTick);
            }
            if (nowTick % OMINOUS_SOUND_INTERVAL_TICKS == 0) {
                level.playSound(null, champion.getX(), champion.getY(), champion.getZ(),
                        SoundEvents.WITHER_SPAWN, SoundSource.HOSTILE, 0.7F, 1.4F);
            }
        }
    }

    /**
     * 每秒扫近玩家冠军 (与 {@code ChampionBlinkHandler} 同范式): 按玩家 AABB 扫 + 自研 capability 检出装配小男孩且
     * 有效血量占比达背水血线的冠军起手; 多玩家同看一冠军本轮只结算一次。
     */
    private void scanForIgnition(MinecraftServer server, long nowTick) {
        for (ChampionProximityScanner.Sighting sighting : ChampionProximityScanner.sightings(server)) {
            if (!sighting.entity().isAlive()) {
                continue; // 快照按 tick 复用, 同 tick 更早的 handler 可能已致死: 存活性逐条重查。
            }
            maybeIgnite(sighting.entity(), sighting.level(), nowTick);
        }
    }

    /** 对一只实体 (若是装配小男孩且到达背水血线的本工程冠军) 起手蓄力。 */
    private void maybeIgnite(LivingEntity entity, ServerLevel level, long nowTick) {
        if (chargingByChampion.containsKey(entity.getUUID())) {
            return; // 已在蓄力 (防御; 起手摘词条后本不会再检出)。
        }
        MiningChampionData champ = MiningChampions.get(entity).orElse(null);
        if (champ == null || !champ.isChampion()) {
            return; // 非本工程冠军。
        }
        AffixQuality quality = champ.quality(AffixDef.LITTLE_BOY);
        if (quality == null) {
            return; // 未装配小男孩 / 已摘 (一次性): 不起手。
        }
        if (!ChampionLittleBoyPlan.shouldTrigger(championHpFraction(entity))) {
            return; // 未到背水血线 (>60%)。
        }
        ignite(entity, level, champ, quality, nowTick);
    }

    /**
     * 起手蓄力: 统计起手瞬间半径内存活玩家数 (最少计 1) 定门槛 -&gt; 【立即摘词条】(一次性语义, 打断与否都消耗, 摘前
     * 已捕获品质) -&gt; 建蓄力条目 -&gt; 起手一记低音 WITHER_SPAWN + 首帧警告。
     */
    private void ignite(LivingEntity champion, ServerLevel level, MiningChampionData champ,
                        AffixQuality quality, long nowTick) {
        int nearby = alivePlayersWithin(level, champion, NEARBY_PLAYER_RADIUS).size();
        double threshold = ChampionLittleBoyPlan.interruptThreshold(nearby);
        // 一次性核弹: 起手即摘词条防重触发 (摘前 quality 已捕获入 state; removeAffix 后 quality(LITTLE_BOY)=null)。
        champ.removeAffix(AffixDef.LITTLE_BOY);
        ChargeState state = new ChargeState(level.dimension(),
                nowTick + ChampionLittleBoyPlan.CHARGE_TICKS, quality, threshold);
        chargingByChampion.put(champion.getUUID(), state);

        level.playSound(null, champion.getX(), champion.getY(), champion.getZ(),
                SoundEvents.WITHER_SPAWN, SoundSource.HOSTILE, 1.5F, 0.8F);
        warnNearbyPlayers(level, champion, state, nowTick);

        LOGGER.info("skill-littleboy champion={} IGNITE players={} threshold={} charge={}t tier={}",
                champion.getType().getDescriptionId(), nearby, String.format("%.1f", threshold),
                ChampionLittleBoyPlan.CHARGE_TICKS, quality);
    }

    /**
     * 蓄力期本怪受【玩家直接/弹射】伤害累计打断进度。LOWEST + receiveCanceled: Forge 无 Bukkit 式 MONITOR, LOWEST 即
     * 事件链末端相位; 小男孩恒 7★+ 有血池, {@code ChampionBloodPoolHandler} (亦 LOWEST) 只 cancel 不 setAmount, 故此处
     * {@code event.getAmount()} = 名义入伤 (与 {@code ContributionTracker}/{@code ChampionDeathMarkHandler} 采样同口径)。
     *
     * 用名义入伤而非净伤累计 (对齐全库"有效伤害"口径): 若按净伤 (经血池减伤), 重装甲冠军减伤达 75% 时打断门槛几乎不可达,
     * 违"打出 X 伤害可打断"的可预期反制语义; 名义口径即玩家伤害面板所见的输出, 门槛可达。只计玩家来源
     * ({@code source.getEntity()} 是玩家: 近战=玩家本体, 弹射=射手玩家), 环境/其它怪伤害不计。
     */
    @SubscribeEvent(priority = EventPriority.LOWEST, receiveCanceled = true)
    public void onChampionHurtDuringCharge(LivingHurtEvent event) {
        if (!(event.getSource().getEntity() instanceof Player)) {
            return; // 非玩家直接/弹射来源: 不计打断进度。
        }
        ChargeState state = chargingByChampion.get(event.getEntity().getUUID());
        if (state == null) {
            return; // 受击者非蓄力中小男孩冠军 (绝大多数受击早退)。
        }
        double amount = event.getAmount();
        if (amount <= 0.0D) {
            return;
        }
        state.accumulatedPlayerDamage += amount;
        if (ChampionDiagnostics.shouldTrace(event.getEntity())) {
            LOGGER.info("skill-littleboy champion={} charge-hit +{} accum={} threshold={}",
                    event.getEntity().getType().getDescriptionId(), String.format("%.1f", amount),
                    String.format("%.1f", state.accumulatedPlayerDamage), String.format("%.1f", state.interruptThreshold));
        }
    }

    /** 蓄力期冠军死亡: 摘状态自然终止 (无引爆; removeAffix 已在起手落定, 死亡不复活词条)。 */
    @SubscribeEvent
    public void onChampionDeath(LivingDeathEvent event) {
        chargingByChampion.remove(event.getEntity().getUUID());
    }

    /**
     * 打断: 失败大爆 (烟雾 + poof) + 灭火音 + 半径内玩家 actionbar 提示, 结束 (无 AOE 伤害)。
     */
    private void onInterrupted(LivingEntity champion, ServerLevel level, ChargeState state) {
        level.sendParticles(ParticleTypes.LARGE_SMOKE, champion.getX(), champion.getY() + 1.0D, champion.getZ(),
                60, 1.5D, 1.0D, 1.5D, 0.02D);
        level.sendParticles(ParticleTypes.POOF, champion.getX(), champion.getY() + 1.0D, champion.getZ(),
                40, 1.2D, 1.0D, 1.2D, 0.05D);
        level.playSound(null, champion.getX(), champion.getY(), champion.getZ(),
                SoundEvents.FIRE_EXTINGUISH, SoundSource.HOSTILE, 1.0F, 0.8F);
        Component msg = Component.literal("小男孩已被打断!");
        for (ServerPlayer player : alivePlayersWithin(level, champion, NEARBY_PLAYER_RADIUS)) {
            player.displayClientMessage(msg, true);
        }
        LOGGER.info("skill-littleboy champion={} INTERRUPTED accum={} threshold={}",
                champion.getType().getDescriptionId(), String.format("%.1f", state.accumulatedPlayerDamage),
                String.format("%.1f", state.interruptThreshold));
    }

    /**
     * 引爆: 大爆炸粒子 + 音; 半径内玩家逐个结算 基础% x maxHP x 边缘衰减 (中心满、边缘半), CHAMPION_SKILL_AOE 源
     * (吃护甲、不触近战 rider、可被免疫缓冲拦第二发); 逐玩家【结算完自身伤害后】授 2s 免疫缓冲 (红线 3 防叠杀)。
     * DamageSource 构造照 {@code ChampionDeathMarkHandler.executePlayer} 的 registry Holder 写法。
     */
    private void detonate(LivingEntity champion, ServerLevel level, ChargeState state) {
        Holder<DamageType> aoeType = level.registryAccess()
                .registryOrThrow(Registries.DAMAGE_TYPE)
                .getHolderOrThrow(ChampionDamageTypes.CHAMPION_SKILL_AOE);
        DamageSource source = new DamageSource(aoeType, champion);

        double cx = champion.getX();
        double cy = champion.getY() + 1.0D;
        double cz = champion.getZ();
        level.sendParticles(ParticleTypes.EXPLOSION_EMITTER, cx, cy, cz, 1, 0.0D, 0.0D, 0.0D, 0.0D);
        level.sendParticles(ParticleTypes.EXPLOSION, cx, cy, cz,
                24, ChampionLittleBoyPlan.BLAST_RADIUS * 0.4D, 1.0D, ChampionLittleBoyPlan.BLAST_RADIUS * 0.4D, 0.0D);
        level.playSound(null, cx, cy, cz, SoundEvents.GENERIC_EXPLODE, SoundSource.HOSTILE, 4.0F, 0.9F);

        boolean trace = ChampionDiagnostics.shouldTrace(champion);
        int hitCount = 0;
        for (ServerPlayer player : alivePlayersWithin(level, champion, ChampionLittleBoyPlan.BLAST_RADIUS)) {
            double distance = Math.sqrt(player.distanceToSqr(champion));
            double damage = ChampionLittleBoyPlan.blastDamage(state.quality, player.getMaxHealth(), distance);
            player.hurt(source, (float) damage);
            // 结算完自身伤害后授缓冲 (每玩家本轮只命中一次, hurt 后立即 grant 不影响其它玩家; 窗内首发不续窗, F102)。
            AoeImmunityBuffer.grantIfNotBuffered(player);
            hitCount++;
            if (trace) {
                LOGGER.info("skill-littleboy champion={} DETONATE hit player={} dist={} dmg={}",
                        champion.getType().getDescriptionId(), player.getGameProfile().getName(),
                        String.format("%.1f", distance), String.format("%.1f", damage));
            }
        }
        LOGGER.info("skill-littleboy champion={} DETONATE tier={} hits={}",
                champion.getType().getDescriptionId(), state.quality, hitCount);
    }

    /** 蓄力光柱 (spec 9A.6 原版可见原语): 逐 tick 自冠军脚下向上喷 END_ROD 柱 + 脚底 SOUL_FIRE_FLAME 菌云。 */
    private static void emitChargePillar(ServerLevel level, LivingEntity champion) {
        double x = champion.getX();
        double y = champion.getY();
        double z = champion.getZ();
        for (double dy = 0.0D; dy <= PILLAR_HEIGHT; dy += PILLAR_STEP) {
            level.sendParticles(ParticleTypes.END_ROD, x, y + dy, z, 2, 0.08D, 0.2D, 0.08D, 0.0D);
        }
        level.sendParticles(ParticleTypes.SOUL_FIRE_FLAME, x, y + 0.2D, z, 12, 0.35D, 0.1D, 0.35D, 0.01D);
    }

    /** 蓄力警告 (spec 9A.6 actionbar): 半径内玩家收"打出 X 伤害可打断 + 剩余秒"(X = 门槛, 主线裁定)。 */
    private void warnNearbyPlayers(ServerLevel level, LivingEntity champion, ChargeState state, long nowTick) {
        long remainTicks = Math.max(0L, state.chargeEndTick - nowTick);
        int remainSec = (int) Math.ceil(remainTicks / (double) ChampionLittleBoyPlan.TICKS_PER_SECOND);
        Component msg = Component.literal("小男孩充能中: 打出 " + (long) Math.ceil(state.interruptThreshold)
                + " 伤害可打断! (剩余 " + remainSec + "s)");
        for (ServerPlayer player : alivePlayersWithin(level, champion, NEARBY_PLAYER_RADIUS)) {
            player.displayClientMessage(msg, true);
        }
    }

    /**
     * 冠军有效血量占比 (背水血线判定; spec 6.2 血池权威, 与 {@code ChampionAttackHandler.championHpFraction} 同口径):
     * 6★+ 读影子血池 fraction, 血池缺失 (异常) 回退 vanilla getHealth/getMaxHealth 之比。小男孩恒 7★+ 常态走血池;
     * 该口径为 private 不可跨类复用, 故按同规则本地实现 (纯读, 不改血池)。
     */
    private static double championHpFraction(LivingEntity champion) {
        BloodPool pool = BloodPoolRegistry.get(champion.getUUID());
        if (pool != null) {
            return pool.fraction();
        }
        float max = champion.getMaxHealth();
        if (max <= 0.0F) {
            return 1.0D;
        }
        return champion.getHealth() / max;
    }

    /** 半径内存活服务端玩家 (3D 距离 &lt;= radius): 起手玩家计数 + 蓄力警告 + 引爆结算共用。 */
    private static List<ServerPlayer> alivePlayersWithin(ServerLevel level, LivingEntity center, double radius) {
        List<ServerPlayer> result = new ArrayList<>();
        double radiusSq = radius * radius;
        for (ServerPlayer player : level.players()) {
            if (player.isAlive() && player.distanceToSqr(center) <= radiusSq) {
                result.add(player);
            }
        }
        return result;
    }

    /** 按状态记录的维度检出在册冠军实体 (维度未加载/实体不在返 null; 照闪光 resolve 范式)。 */
    private static LivingEntity resolve(MinecraftServer server, ResourceKey<Level> dimension, UUID id) {
        ServerLevel level = server.getLevel(dimension);
        if (level == null) {
            return null;
        }
        Entity found = level.getEntity(id);
        return found instanceof LivingEntity living ? living : null;
    }

    /**
     * per-冠军小男孩蓄力状态 (单相 CHARGING): 所在维度 (每 tick O(1) 检出实体) + 引爆 tick + 起手捕获的品质
     * (摘词条后 quality(LITTLE_BOY)=null, 引爆读此) + 打断门槛 (起手玩家数定死) + 蓄力期玩家来源累计伤害 (打断判据)。
     */
    private static final class ChargeState {
        private final ResourceKey<Level> dimension;
        private final long chargeEndTick;
        private final AffixQuality quality;
        private final double interruptThreshold;
        private double accumulatedPlayerDamage = 0.0D;

        private ChargeState(ResourceKey<Level> dimension, long chargeEndTick, AffixQuality quality,
                            double interruptThreshold) {
            this.dimension = dimension;
            this.chargeEndTick = chargeEndTick;
            this.quality = quality;
            this.interruptThreshold = interruptThreshold;
        }
    }
}
