package com.miningdim.title;

import com.miningdim.title.network.TitleNetwork;
import com.miningdim.title.store.TitleRepository;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import org.jetbrains.annotations.Nullable;

import java.sql.Connection;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * {@link ITitleService} 的服务端实现。
 *
 * <p>内存缓存只保存在线玩家 (登录加载、登出移出), 按当前规模读写都在主线程同步完成。缓存写穿: 发放、回收、
 * 佩戴先落库再改缓存, 因此停服或崩溃都不会丢数据, 登出时也无需回写。显示路径 ({@link #displayPrefix} /
 * {@link #displayBadge}) 只读缓存, 不查库; 数据库只在登录、发放、回收、佩戴这几个低频点访问。
 *
 * <p>{@link #grantInTransaction} 不往缓存里加称号: 外层事务可能回滚, 此时缓存会多出一个库里没有的称号。
 * 它改为把该玩家的持有集合标记为待重载, 下次读取时从库里重新加载; 若读取发生在外层事务尚未提交时,
 * 结果只返回、不写回缓存, 以免把未提交数据固化进缓存。
 *
 * <p>反过来, grant / revoke / equip 这几个写穿操作在共享连接已开着调用方事务时一律拒绝 (抛
 * IllegalStateException): 它们的写入会并入外层事务, 而改缓存、发提示、刷显示却是立即生效的, 外层一回滚
 * 三者就与库对不上。需要与其他写入同事务的发放走 {@link #grantInTransaction}, 佩戴与回收放到提交之后。
 *
 * <p>全部调用点都在服务端主线程 (命令、登录登出、NameFormat/TabListNameFormat、调用方模块的事件回调),
 * 因此用普通 HashMap。
 */
public final class TitleService implements ITitleService {

    private final TitleRepository repository;
    private final TitleDefinitions definitions;
    private final TitleRenderer renderer;
    private final MinecraftServer server;
    private final Map<UUID, OnlineState> online = new HashMap<>();

    public TitleService(TitleRepository repository, TitleDefinitions definitions, MinecraftServer server) {
        this.repository = Objects.requireNonNull(repository, "repository");
        this.definitions = Objects.requireNonNull(definitions, "definitions");
        this.server = Objects.requireNonNull(server, "server");
        this.renderer = new TitleRenderer(definitions);
    }

    // ---- 发放 / 回收 ----

    @Override
    public GrantResult grant(ServerPlayer player, ResourceLocation titleId, TitleSource source,
                             @Nullable String sourceRef) {
        Objects.requireNonNull(player, "player");
        requireNoOpenTransaction("grant");
        GrantResult result = grantToStore(player.getUUID(), titleId, source, sourceRef);
        if (result == GrantResult.GRANTED) {
            OnlineState state = online.get(player.getUUID());
            if (state != null && state.owned != null) {
                state.owned.add(titleId);
            }
            notifyGranted(player, titleId);
        }
        return result;
    }

    @Override
    public GrantResult grant(UUID player, ResourceLocation titleId, TitleSource source, @Nullable String sourceRef) {
        Objects.requireNonNull(player, "player");
        requireNoOpenTransaction("grant");
        ServerPlayer onlinePlayer = server.getPlayerList().getPlayer(player);
        if (onlinePlayer != null) {
            return grant(onlinePlayer, titleId, source, sourceRef);
        }
        return grantToStore(player, titleId, source, sourceRef);
    }

    private GrantResult grantToStore(UUID player, ResourceLocation titleId, TitleSource source,
                                     @Nullable String sourceRef) {
        Objects.requireNonNull(titleId, "titleId");
        Objects.requireNonNull(source, "source");
        if (!definitions.contains(titleId)) {
            return GrantResult.UNKNOWN_TITLE;
        }
        boolean inserted = repository.insertOwned(player, titleId, source, sourceRef, System.currentTimeMillis());
        return inserted ? GrantResult.GRANTED : GrantResult.ALREADY_OWNED;
    }

    @Override
    public GrantResult grantInTransaction(Connection tx, UUID player, ResourceLocation titleId, TitleSource source,
                                          @Nullable String sourceRef) {
        // 先校验事务: 调用方没开事务是接线错误, 不能被"称号未定义"这种正常拒发结果盖过去。
        repository.requireOpenTransaction(tx);
        Objects.requireNonNull(player, "player");
        Objects.requireNonNull(titleId, "titleId");
        Objects.requireNonNull(source, "source");
        if (!definitions.contains(titleId)) {
            return GrantResult.UNKNOWN_TITLE;
        }
        boolean inserted = repository.insertOwnedInTransaction(tx, player, titleId, source, sourceRef,
                System.currentTimeMillis());
        if (inserted) {
            OnlineState state = online.get(player);
            if (state != null) {
                state.owned = null;
            }
        }
        return inserted ? GrantResult.GRANTED : GrantResult.ALREADY_OWNED;
    }

    @Override
    public void notifyGranted(ServerPlayer player, ResourceLocation titleId) {
        TitleDefinition definition = definitions.get(titleId).orElse(null);
        if (definition == null) {
            return;
        }
        MutableComponent shown = TitleRenderer.renderBadge(definition);
        Component description = definition.description();
        if (description != null) {
            shown = shown.withStyle(style -> style.withHoverEvent(
                    new HoverEvent(HoverEvent.Action.SHOW_TEXT, description)));
        }
        player.sendSystemMessage(Component.translatable("title.miningdim.message.obtained", shown));
    }

    @Override
    public boolean revoke(UUID player, ResourceLocation titleId) {
        Objects.requireNonNull(player, "player");
        Objects.requireNonNull(titleId, "titleId");
        requireNoOpenTransaction("revoke");
        RevokeOutcome outcome = repository.inTransaction(() -> {
            boolean removed = repository.deleteOwned(player, titleId);
            boolean unequipped = repository.equipped(player).filter(titleId::equals).isPresent()
                    && repository.clearEquipped(player);
            return new RevokeOutcome(removed, unequipped);
        });
        OnlineState state = online.get(player);
        if (state != null) {
            if (state.owned != null) {
                state.owned.remove(titleId);
            }
            if (outcome.unequipped()) {
                state.equipped = null;
            }
        }
        if (outcome.unequipped()) {
            ServerPlayer onlinePlayer = server.getPlayerList().getPlayer(player);
            if (onlinePlayer != null) {
                refreshDisplay(onlinePlayer);
            }
        }
        return outcome.removed();
    }

    // ---- 佩戴 ----

    @Override
    public EquipResult equip(ServerPlayer player, @Nullable ResourceLocation titleId) {
        Objects.requireNonNull(player, "player");
        requireNoOpenTransaction("equip");
        UUID uuid = player.getUUID();
        OnlineState state = stateFor(player);
        ResourceLocation current = state != null ? state.equipped : repository.equipped(uuid).orElse(null);

        if (titleId == null) {
            if (current != null) {
                repository.clearEquipped(uuid);
                if (state != null) {
                    state.equipped = null;
                }
                refreshDisplay(player);
            }
            return EquipResult.UNEQUIPPED;
        }
        if (!definitions.contains(titleId)) {
            return EquipResult.UNKNOWN_TITLE;
        }
        if (!owned(uuid).contains(titleId)) {
            return EquipResult.NOT_OWNED;
        }
        if (titleId.equals(current)) {
            return EquipResult.EQUIPPED;
        }
        repository.setEquipped(uuid, titleId);
        if (state != null) {
            state.equipped = titleId;
        }
        refreshDisplay(player);
        return EquipResult.EQUIPPED;
    }

    // ---- 查询 ----

    @Override
    public Set<ResourceLocation> owned(UUID player) {
        OnlineState state = online.get(player);
        if (state != null && state.owned != null) {
            return Collections.unmodifiableSet(new LinkedHashSet<>(state.owned));
        }
        Set<ResourceLocation> stored = repository.owned(player);
        if (state != null && !repository.inOpenTransaction()) {
            state.owned = new LinkedHashSet<>(stored);
        }
        return stored;
    }

    @Override
    public Optional<ResourceLocation> equipped(UUID player) {
        OnlineState state = online.get(player);
        if (state != null) {
            return Optional.ofNullable(state.equipped);
        }
        return repository.equipped(player);
    }

    @Override
    public Optional<TitleDefinition> definition(ResourceLocation titleId) {
        return definitions.get(titleId);
    }

    @Override
    public List<TitleDefinition> definitions() {
        return definitions.all();
    }

    @Override
    public Optional<Component> badge(ResourceLocation titleId) {
        return Optional.ofNullable(renderer.badge(titleId));
    }

    @Override
    @Nullable
    public Component displayPrefix(UUID player) {
        OnlineState state = online.get(player);
        return state == null || state.equipped == null ? null : renderer.prefix(state.equipped);
    }

    @Override
    @Nullable
    public Component displayBadge(UUID player) {
        OnlineState state = online.get(player);
        return state == null || state.equipped == null ? null : renderer.badge(state.equipped);
    }

    // ---- 生命周期 (由 TitleSystem 调用, 不属于对外门面) ----

    /**
     * 登录: 从库里加载该玩家的持有与佩戴进缓存, 然后刷新显示名、Tab 名, 并把名牌称号发给自己与已在看他的人。
     * PlayerList.placeNewPlayer 在触发登录事件之前就已广播过 Tab 条目、其他玩家也已开始追踪他 (那时缓存
     * 还没加载), 所以这里必须补一次刷新与广播。登录事件由 placeNewPlayer 派发, 不会处在任何调用方的事务体内,
     * 这里读到的都是已提交数据。
     */
    public void loadPlayer(ServerPlayer player) {
        UUID uuid = player.getUUID();
        OnlineState state = new OnlineState();
        state.owned = new LinkedHashSet<>(repository.owned(uuid));
        state.equipped = repository.equipped(uuid).orElse(null);
        online.put(uuid, state);
        if (state.equipped != null) {
            refreshDisplay(player);
        }
    }

    /** 登出: 只丢缓存 (写穿缓存, 无需回写)。 */
    public void forgetPlayer(UUID player) {
        online.remove(player);
    }

    /** 数据包重载后: 定义可能增删改, 刷新所有佩戴着称号的在线玩家的三处显示。 */
    public void refreshOnlineDisplays(Collection<ServerPlayer> players) {
        for (ServerPlayer player : players) {
            OnlineState state = online.get(player.getUUID());
            if (state != null && state.equipped != null) {
                refreshDisplay(player);
            }
        }
    }

    /**
     * 队伍变化兜底: 有称号时 Tab 名里烘焙了队伍格式化 (见 TitleSystem#onTabListNameFormat), 客户端不再自己套队伍
     * 样式, 而原版加入/离开/修改队伍都不会触发 Tab 名重算。由 TitleSystem 低频调用, 对佩戴着称号的在线玩家重算
     * 一次 Tab 名; refreshTabListName 只在结果与上次不同时广播, 队伍没变就不发包。只读缓存, 不查库。
     */
    public void refreshTitledTabNames(Collection<ServerPlayer> players) {
        for (ServerPlayer player : players) {
            OnlineState state = online.get(player.getUUID());
            if (state != null && state.equipped != null) {
                player.refreshTabListName();
            }
        }
    }

    /** 当前缓存的在线玩家数 (GameTest 与诊断用)。 */
    int cachedPlayerCount() {
        return online.size();
    }

    /**
     * 写穿操作的前置校验, 理由见类注释: 共享连接上正开着调用方的事务时直接拒绝, 不让立即生效的缓存、提示与显示
     * 跟着一个可能回滚的写入走。
     */
    private void requireNoOpenTransaction(String operation) {
        if (repository.inOpenTransaction()) {
            throw new IllegalStateException("title " + operation + " must not run inside the caller's open transaction"
                    + " (use grantInTransaction there, and equip/revoke after the transaction commits)");
        }
    }

    /**
     * 在线玩家的缓存条目; 该玩家对象若不在玩家列表里 (已登出的陈旧引用), 不为它建缓存, 以免条目在登出后泄漏。
     * 只从 equip 调用, 那里已排除外层事务, 因此这里读到的都是已提交数据, 可以直接写进缓存。
     */
    @Nullable
    private OnlineState stateFor(ServerPlayer player) {
        OnlineState state = online.get(player.getUUID());
        if (state != null || server.getPlayerList().getPlayer(player.getUUID()) != player) {
            return state;
        }
        state = new OnlineState();
        state.owned = new LinkedHashSet<>(repository.owned(player.getUUID()));
        state.equipped = repository.equipped(player.getUUID()).orElse(null);
        online.put(player.getUUID(), state);
        return state;
    }

    /** 三处显示一起刷新: 聊天显示名、Tab 列表名 (原版自动广播)、头顶名牌 (发给观察者与自己)。 */
    private void refreshDisplay(ServerPlayer player) {
        player.refreshDisplayName();
        player.refreshTabListName();
        TitleNetwork.sendToTrackersAndSelf(player, displayBadge(player.getUUID()));
    }

    private record RevokeOutcome(boolean removed, boolean unequipped) {
    }

    /** 在线玩家的缓存: owned 为 null 表示待从库里重载 (见类注释)。 */
    private static final class OnlineState {
        @Nullable
        private Set<ResourceLocation> owned;
        @Nullable
        private ResourceLocation equipped;
    }
}
