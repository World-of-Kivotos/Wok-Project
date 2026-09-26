package com.miningdim.champion;

import com.miningdim.champion.reward.DamageContribution;
import com.miningdim.core.MiningConstants;
import com.mojang.authlib.GameProfile;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.TextColor;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.GameProfileCache;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.Level;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * 世界 BOSS 的全服公告 (ChampionStarAffix spec 第十章; 服主拍板"出现会提示全服务器玩家")。
 *
 * <p>公告发给全部在线玩家, 不分维度: 聊天行走 {@code PlayerList.broadcastSystemMessage} (同时写进服务端日志),
 * 音效走每名玩家自己的 {@code playNotifySound} —— 声音在玩家本人的位置播放, 离 BOSS 多远、在哪个维度都听得到。
 * 文案全部是翻译键 (前缀 {@value #KEY_PREFIX}), 整行按星级 signature 色着色, 词条名按品质色着色。
 *
 * <ul>
 *   <li>出现: 星级、实体名、维度、坐标、词条名; 凋灵生成音效。</li>
 *   <li>击倒: 星级、实体名、输出前 {@value #TOP_CONTRIBUTORS} 名及其占全部记录伤害的比例 (分母与成就的输出占比
 *       同口径, 离线者与不合格者的伤害也算在内); 挑战完成音效。</li>
 * </ul>
 */
public final class WorldBossBroadcast {

    /** 本类全部文案键的公共前缀 (精英怪模块的聊天消息段)。 */
    public static final String KEY_PREFIX = "message.miningdim.champion.world_boss.";
    public static final String SPAWNED_KEY = KEY_PREFIX + "spawned";
    public static final String DEFEATED_KEY = KEY_PREFIX + "defeated";
    public static final String CONTRIBUTOR_KEY = KEY_PREFIX + "contributor";
    public static final String SEPARATOR_KEY = KEY_PREFIX + "separator";
    public static final String NO_AFFIX_KEY = KEY_PREFIX + "no_affix";

    /** 击倒公告列出的输出名次数。 */
    public static final int TOP_CONTRIBUTORS = 3;

    /** 出现与击倒的提示音。 */
    public static final SoundEvent SPAWN_SOUND = SoundEvents.WITHER_SPAWN;
    public static final SoundEvent DEFEAT_SOUND = SoundEvents.UI_TOAST_CHALLENGE_COMPLETE;

    /** 有中文名的维度; 其余维度 (别的模组加的) 直接显示维度 id。 */
    private static final Map<ResourceKey<Level>, String> DIMENSION_KEYS = Map.of(
            Level.OVERWORLD, KEY_PREFIX + "dimension.overworld",
            Level.NETHER, KEY_PREFIX + "dimension.the_nether",
            Level.END, KEY_PREFIX + "dimension.the_end",
            MiningConstants.MINING_LEVEL, KEY_PREFIX + "dimension.mining");

    private WorldBossBroadcast() {
    }

    /** 世界 BOSS 出现: 全服聊天公告 + 每名玩家一声凋灵生成音效。 */
    public static void announceSpawn(MinecraftServer server, LivingEntity boss, MiningChampionData data) {
        BlockPos pos = boss.blockPosition();
        Component line = Component.translatable(SPAWNED_KEY, starText(data.star()), boss.getDisplayName(),
                        dimensionName(boss.level().dimension()), pos.getX(), pos.getY(), pos.getZ(),
                        affixList(data.affixes()))
                .withStyle(style -> style.withColor(TextColor.fromRgb(StarRank.ofStar(data.star()).barColorRgb())));
        broadcast(server, line, SPAWN_SOUND);
    }

    /**
     * 世界 BOSS 被玩家击倒: 全服聊天公告输出前 {@value #TOP_CONTRIBUTORS} 名 + 每名玩家一声挑战完成音效。
     *
     * @param ledger 这只 BOSS 的全部贡献记录 (死亡时 peek 得到, 非空)
     */
    public static void announceDefeat(MinecraftServer server, LivingEntity boss, MiningChampionData data,
                                      List<DamageContribution> ledger) {
        double total = 0.0D;
        for (DamageContribution contribution : ledger) {
            total += contribution.effectiveDamage();
        }
        MutableComponent ranking = Component.empty();
        List<DamageContribution> top = topContributors(ledger, TOP_CONTRIBUTORS);
        for (int i = 0; i < top.size(); i++) {
            if (i > 0) {
                ranking.append(Component.translatable(SEPARATOR_KEY));
            }
            DamageContribution contribution = top.get(i);
            double share = total > 0.0D ? contribution.effectiveDamage() / total : 0.0D;
            ranking.append(Component.translatable(CONTRIBUTOR_KEY,
                    Component.literal(nameOf(server, contribution.playerId())),
                    Component.literal(String.format(Locale.ROOT, "%.1f%%", share * 100.0D))));
        }
        Component line = Component.translatable(DEFEATED_KEY, starText(data.star()), boss.getDisplayName(), ranking)
                .withStyle(style -> style.withColor(TextColor.fromRgb(StarRank.ofStar(data.star()).barColorRgb())));
        broadcast(server, line, DEFEAT_SOUND);
    }

    /**
     * 按记录伤害从高到低取前 limit 名。排序稳定: 伤害相同时保持账本原序 (首伤 tick 升序、再按 UUID), 结果可复现。
     */
    static List<DamageContribution> topContributors(List<DamageContribution> ledger, int limit) {
        List<DamageContribution> sorted = new ArrayList<>(ledger);
        sorted.sort(Comparator.comparingDouble(DamageContribution::effectiveDamage).reversed());
        return sorted.subList(0, Math.min(limit, sorted.size()));
    }

    private static void broadcast(MinecraftServer server, Component line, SoundEvent sound) {
        server.getPlayerList().broadcastSystemMessage(line, false);
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            player.playNotifySound(sound, SoundSource.MASTER, 1.0F, 1.0F);
        }
    }

    private static Component starText(int star) {
        return Component.literal(star + "★");
    }

    private static Component dimensionName(ResourceKey<Level> dimension) {
        String key = DIMENSION_KEYS.get(dimension);
        return key != null ? Component.translatable(key) : Component.literal(dimension.location().toString());
    }

    /** 词条名按品质色着色, 用本地化的分隔符连接; 没有词条时写"无"。 */
    private static Component affixList(Map<AffixDef, AffixQuality> affixes) {
        if (affixes.isEmpty()) {
            return Component.translatable(NO_AFFIX_KEY);
        }
        MutableComponent list = Component.empty();
        boolean first = true;
        for (Map.Entry<AffixDef, AffixQuality> entry : affixes.entrySet()) {
            if (!first) {
                list.append(Component.translatable(SEPARATOR_KEY));
            }
            first = false;
            int rgb = entry.getValue().displayColor();
            list.append(Component.translatable(entry.getKey().displayNameKey())
                    .withStyle(style -> style.withColor(TextColor.fromRgb(rgb))));
        }
        return list;
    }

    /** 贡献者的名字: 在线取当前档案, 离线查用户缓存, 都查不到时退回 UUID 前 8 位。 */
    private static String nameOf(MinecraftServer server, UUID playerId) {
        ServerPlayer online = server.getPlayerList().getPlayer(playerId);
        if (online != null) {
            return online.getGameProfile().getName();
        }
        GameProfileCache cache = server.getProfileCache();
        Optional<GameProfile> cached = cache == null ? Optional.empty() : cache.get(playerId);
        return cached.map(GameProfile::getName).orElse(playerId.toString().substring(0, 8));
    }
}
