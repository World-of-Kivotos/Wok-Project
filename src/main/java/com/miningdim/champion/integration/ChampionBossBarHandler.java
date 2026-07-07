package com.miningdim.champion.integration;

import com.miningdim.champion.AffixDef;
import com.miningdim.champion.ChampionBossBarText;
import com.miningdim.champion.MiningChampionData;
import com.miningdim.champion.MiningChampions;
import com.miningdim.champion.StarRank;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.TextColor;
import net.minecraft.server.level.ServerBossEvent;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.BossEvent;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.AABB;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 精英怪自定义 BOSS 血条 (ChampionStarAffix spec 9.7 显示层)。原版 Champions 2.1.10.2 只有"瞄准时自绘 HUD + Jade 提示",
 * 无顶部 BOSS 血条 (那是 Champions Unofficial 分支的功能, 换回原版后没了)。本 handler 自建: 玩家靠近精英怪
 * (&lt;= {@value #VIEW_RANGE} 格) 即在屏幕顶部出 vanilla {@code ServerBossEvent} 血条, 标题显示 名字 + 星级 + 词条名 (中文),
 * 颜色/分段随星级 (见 {@link ChampionBossBarText})。BOSS 条是 vanilla 服务端机制 (addPlayer 即自动同步渲染), 故纯服务端、零客户端代码。
 *
 * 血量: 直接读 vanilla getHealth/getMaxHealth —— 6★+ 的影子血池由 {@link ChampionBloodPoolHandler} 每 tick 镜像进
 * vanilla 血, 故此处一律 vanilla 血/最大血 即得正确分数, 无需另读血池。
 *
 * 数据源: 经 {@link MiningChampions#get} 读自研 {@link MiningChampionData} (星级 star + 词条→品质), 星级色取
 * {@link StarRank#barColorRgb}, 词条名取 {@link AffixDef#displayNameKey}; 不触任何 top.theillusivec4.champions.*
 * (故 dev 亦可加载)。命令召唤 / 自然刷的精英怪都经同一自研 capability 检出, 两种来源一视同仁。
 */
public final class ChampionBossBarHandler {

    /** 扫描节流: 每多少 tick 重算"附近精英怪 + 观察玩家集" + 刷血量 (0.5s; 够顺滑且省全实体遍历)。 */
    private static final int SCAN_INTERVAL_TICKS = 10;

    /** 玩家可见 BOSS 条的距离 (格); 与 champions-client.toml hudRange 同量级。 */
    private static final double VIEW_RANGE = 48.0D;

    /** 精英怪实体 UUID -> 其 BOSS 条 (在册 = 当前至少一个玩家在范围内看)。 */
    private final Map<UUID, ServerBossEvent> bars = new HashMap<>();

    @SubscribeEvent
    public void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        if (event.getServer().getTickCount() % SCAN_INTERVAL_TICKS != 0) {
            return;
        }

        // 1. 收集每只附近精英怪 + 看它的玩家集 (按玩家 AABB 扫实体, 经 Champions capability 检出精英怪)。
        Map<UUID, View> live = new HashMap<>();
        for (ServerLevel level : event.getServer().getAllLevels()) {
            List<ServerPlayer> players = level.players();
            if (players.isEmpty()) {
                continue;
            }
            for (ServerPlayer player : players) {
                AABB box = player.getBoundingBox().inflate(VIEW_RANGE);
                for (LivingEntity entity : level.getEntitiesOfClass(LivingEntity.class, box,
                        e -> e.isAlive() && e != player)) {
                    View view = live.get(entity.getUUID());
                    if (view == null) {
                        view = viewOf(entity);
                        if (view == null) {
                            continue; // 非精英怪。
                        }
                        live.put(entity.getUUID(), view);
                    }
                    view.viewers.add(player);
                }
            }
        }

        // 2. 摘除不再 live 的 BOSS 条 (精英怪死亡/离开所有玩家范围)。
        bars.entrySet().removeIf(e -> {
            if (!live.containsKey(e.getKey())) {
                e.getValue().removeAllPlayers();
                return true;
            }
            return false;
        });

        // 3. 创建/更新 live BOSS 条 + 同步观察玩家集。
        for (Map.Entry<UUID, View> e : live.entrySet()) {
            View view = e.getValue();
            ServerBossEvent bar = bars.get(e.getKey());
            if (bar == null) {
                bar = new ServerBossEvent(view.name, view.barColor, ChampionBossBarText.overlayForTier(view.tier));
                bars.put(e.getKey(), bar);
            } else {
                bar.setName(view.name);
                bar.setColor(view.barColor);
                bar.setOverlay(ChampionBossBarText.overlayForTier(view.tier));
            }
            bar.setProgress(ChampionBossBarText.progress(view.health, view.maxHealth));
            syncViewers(bar, view.viewers);
        }
    }

    /** 服务端停止: 清所有 BOSS 条 (摘玩家 + 清表), 防跨存档/跨重启脏引用。 */
    @SubscribeEvent
    public void onServerStopping(ServerStoppingEvent event) {
        for (ServerBossEvent bar : bars.values()) {
            bar.removeAllPlayers();
        }
        bars.clear();
    }

    /** 把 BOSS 条的玩家集对齐到当前观察者集 (新进的 addPlayer, 走开的 removePlayer)。 */
    private static void syncViewers(ServerBossEvent bar, Set<ServerPlayer> viewers) {
        Set<ServerPlayer> current = new HashSet<>(bar.getPlayers());
        for (ServerPlayer p : viewers) {
            if (!current.contains(p)) {
                bar.addPlayer(p);
            }
        }
        for (ServerPlayer p : current) {
            if (!viewers.contains(p)) {
                bar.removePlayer(p);
            }
        }
    }

    /** 实体若是精英怪 (自研 capability 已盖章, star&gt;=1) 则返回其展示 View, 否则 null。 */
    private static View viewOf(LivingEntity entity) {
        MiningChampionData champ = MiningChampions.get(entity).orElse(null);
        if (champ == null || !champ.isChampion()) {
            return null;
        }
        int tier = champ.star();
        List<Component> affixNames = new ArrayList<>();
        for (AffixDef def : champ.affixes().keySet()) {
            // def 恒有效 (来自自研数据), displayNameKey 恒可渲染, 无需 try/catch 优雅退化。
            affixNames.add(Component.translatable(def.displayNameKey()));
        }
        MutableComponent title = ChampionBossBarText.title(entity.getDisplayName(), tier, affixNames);
        // 文字色 = 星级 signature 色; 条色取最近的离散 BossBarColor —— 与签名粒子统一显示色。
        int rgb = StarRank.ofStar(tier).barColorRgb();
        title = title.withStyle(Style.EMPTY.withColor(TextColor.fromRgb(rgb)));
        BossEvent.BossBarColor barColor = ChampionBossBarText.nearestBossBarColor(rgb);
        return new View(title, tier, barColor, entity.getHealth(), entity.getMaxHealth());
    }

    /** 一只精英怪本 tick 的展示快照: 标题 + 星级 + 当前血/最大血 + 观察玩家集。 */
    private static final class View {
        final Component name;
        final int tier;
        final BossEvent.BossBarColor barColor;
        final double health;
        final double maxHealth;
        final Set<ServerPlayer> viewers = new HashSet<>();

        View(Component name, int tier, BossEvent.BossBarColor barColor, double health, double maxHealth) {
            this.name = name;
            this.tier = tier;
            this.barColor = barColor;
            this.health = health;
            this.maxHealth = maxHealth;
        }
    }
}
