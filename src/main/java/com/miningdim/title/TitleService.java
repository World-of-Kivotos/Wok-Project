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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.time.Duration;
import java.util.ArrayList;
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
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/**
 * {@link ITitleService} 的服务端实现。
 *
 * <p>内存缓存只保存在线玩家 (登录加载、登出移出), 按当前规模读写都在主线程同步完成。缓存写穿: 发放、回收、
 * 佩戴先落库再改缓存, 因此停服或崩溃都不会丢数据, 登出时也无需回写。显示路径 ({@link #displayPrefix} /
 * {@link #displayBadge}) 只读缓存, 不查库; 数据库只在登录、发放、回收、佩戴与专属称号 / 赞助资格变更这几个
 * 低频点访问。
 *
 * <p>{@link #grantInTransaction} 不往缓存里加称号: 外层事务可能回滚, 此时缓存会多出一个库里没有的称号。
 * 它改为把该玩家的持有集合标记为待重载, 下次读取时从库里重新加载; 若读取发生在外层事务尚未提交时,
 * 结果只返回、不写回缓存, 以免把未提交数据固化进缓存。
 *
 * <p>反过来, grant / revoke / equip 这几个写穿操作在共享连接已开着调用方事务时一律拒绝 (抛
 * IllegalStateException): 它们的写入会并入外层事务, 而改缓存、发提示、刷显示却是立即生效的, 外层一回滚
 * 三者就与库对不上。需要与其他写入同事务的发放走 {@link #grantInTransaction}, 佩戴与回收放到提交之后。
 * 赞助资格与专属称号的写操作同理。
 *
 * <p>赞助专属称号 (设计文档第十三章): 持有不落 title_owned, 而是每次读取时推导 —— 有专属称号记录且赞助资格
 * 有效即持有。在线玩家的资格与记录随其他称号数据一起缓存, 专属称号的渲染结果也缓存在该玩家的条目里 (定义随
 * 玩家修改而变, 不进 {@link TitleRenderer} 的按 id 缓存)。到期检查在登录、佩戴、提交修改时各做一次, 在线期间
 * 另由 {@link TitleSystem} 每 60 秒调 {@link #checkSponsorExpiry} 只读缓存地巡检一遍: 资格失效时卸下正在佩戴的
 * 专属称号并提示本人, 记录保留; 续期后重新出现在持有集合里, 但不会自动佩戴。时间一律取注入的时钟, 专属称号
 * 的阈值与冷却每次使用时从注入的规则源重新取 (生产环境即实时读配置)。
 *
 * <p>全部调用点都在服务端主线程 (命令、登录登出、NameFormat/TabListNameFormat、调用方模块的事件回调),
 * 因此用普通 HashMap。
 */
public final class TitleService implements ITitleService {

    private static final Logger CUSTOM_AUDIT = LoggerFactory.getLogger("miningdim/title/custom");
    private static final long DAY_MILLIS = Duration.ofDays(1).toMillis();

    private final TitleRepository repository;
    private final TitleDefinitions definitions;
    private final TitleRenderer renderer;
    private final MinecraftServer server;
    private final LongSupplier clock;
    private final Supplier<CustomTitleRules> customRules;
    private final Map<UUID, OnlineState> online = new HashMap<>();

    public TitleService(TitleRepository repository, TitleDefinitions definitions, MinecraftServer server) {
        this(repository, definitions, server, System::currentTimeMillis, CustomTitleRules::fromConfig);
    }

    /**
     * @param clock       当前时间 (毫秒); GameTest 注入可拨动的时钟来验证冷却与到期
     * @param customRules 专属称号的校验阈值与修改冷却, 每次使用时取一次
     */
    public TitleService(TitleRepository repository, TitleDefinitions definitions, MinecraftServer server,
                        LongSupplier clock, Supplier<CustomTitleRules> customRules) {
        this.repository = Objects.requireNonNull(repository, "repository");
        this.definitions = Objects.requireNonNull(definitions, "definitions");
        this.server = Objects.requireNonNull(server, "server");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.customRules = Objects.requireNonNull(customRules, "customRules");
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
        if (CustomTitle.isCustomId(titleId)) {
            return GrantResult.NOT_GRANTABLE;
        }
        if (!definitions.contains(titleId)) {
            return GrantResult.UNKNOWN_TITLE;
        }
        boolean inserted = repository.insertOwned(player, titleId, source, sourceRef, clock.getAsLong());
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
        if (CustomTitle.isCustomId(titleId)) {
            return GrantResult.NOT_GRANTABLE;
        }
        if (!definitions.contains(titleId)) {
            return GrantResult.UNKNOWN_TITLE;
        }
        boolean inserted = repository.insertOwnedInTransaction(tx, player, titleId, source, sourceRef,
                clock.getAsLong());
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
        if (CustomTitle.isCustomId(titleId)) {
            return false;
        }
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
        if (state != null) {
            reconcileSponsorship(player, state);
        }
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
        if (CustomTitle.isCustomId(titleId) && !CustomTitle.idOf(uuid).equals(titleId)) {
            // 别人的专属称号 (以及保留前缀下的任何 id) 一律按未拥有处理, 不去查对方的记录: 查了就能从 NOT_OWNED 与
            // UNKNOWN_TITLE 的区别看出对方有没有专属称号, 对任意 UUID 的试探也都会打到库上。
            return EquipResult.NOT_OWNED;
        }
        if (definition(titleId).isEmpty()) {
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
        Set<ResourceLocation> granted;
        boolean customOwned;
        if (state != null) {
            if (state.owned != null) {
                granted = state.owned;
            } else {
                granted = repository.owned(player);
                if (!repository.inOpenTransaction()) {
                    state.owned = new LinkedHashSet<>(granted);
                }
            }
            customOwned = state.custom != null && sponsorActive(state.sponsor, clock.getAsLong());
        } else {
            granted = repository.owned(player);
            customOwned = repository.customTitle(player).isPresent()
                    && sponsorActive(repository.sponsor(player).orElse(null), clock.getAsLong());
        }
        Set<ResourceLocation> owned = new LinkedHashSet<>();
        if (customOwned) {
            owned.add(CustomTitle.idOf(player));
        }
        owned.addAll(granted);
        return Collections.unmodifiableSet(owned);
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
        if (CustomTitle.isCustomId(titleId)) {
            UUID owner = CustomTitle.ownerOf(titleId);
            CustomTitle custom = owner == null ? null : customTitleOf(owner);
            return custom == null ? Optional.empty() : Optional.of(custom.definition());
        }
        return definitions.get(titleId);
    }

    @Override
    public List<TitleDefinition> definitions() {
        return definitions.all();
    }

    @Override
    public Optional<Component> badge(ResourceLocation titleId) {
        if (CustomTitle.isCustomId(titleId)) {
            return definition(titleId).<Component>map(TitleRenderer::renderBadge);
        }
        return Optional.ofNullable(renderer.badge(titleId));
    }

    @Override
    @Nullable
    public Component displayPrefix(UUID player) {
        OnlineState state = online.get(player);
        TitleRenderer.Rendered rendered = state == null ? null : displayed(state);
        return rendered == null ? null : rendered.prefix();
    }

    @Override
    @Nullable
    public Component displayBadge(UUID player) {
        OnlineState state = online.get(player);
        TitleRenderer.Rendered rendered = state == null ? null : displayed(state);
        return rendered == null ? null : rendered.badge();
    }

    // ---- 赞助资格 ----

    @Override
    public SponsorStatus grantSponsor(UUID player, @Nullable Integer days, String issuer) {
        Objects.requireNonNull(player, "player");
        Objects.requireNonNull(issuer, "issuer");
        if (days != null && days < 1) {
            throw new IllegalArgumentException("sponsorship days must be at least 1, got " + days);
        }
        requireNoOpenTransaction("grantSponsor");
        long now = clock.getAsLong();
        SponsorStatus previous = sponsorOf(player);
        Long expiresAt;
        if (days == null || previous != null && previous.permanent()) {
            // 永久资格不会被带天数的续期降级成限期; 要改成限期须先撤销再发放。
            expiresAt = null;
        } else {
            // 从"当前到期时间与现在中较晚者"往后延长: 没到期的续期顺延, 不吞掉剩余天数。
            long base = previous == null ? now : Math.max(now, previous.expiresAt());
            expiresAt = base + days * DAY_MILLIS;
        }
        repository.upsertSponsor(player, issuer, now, expiresAt);
        SponsorStatus granted = new SponsorStatus(player, issuer, now, expiresAt);
        CUSTOM_AUDIT.info("[miningdim] title sponsor grant: issuer={} target={} targetUuid={} days={} "
                        + "previousExpiresAt={} expiresAt={}", issuer, nameOf(player), player,
                days == null ? "permanent" : days,
                previous == null ? "-" : expiryText(previous.expiresAt()), expiryText(expiresAt));
        OnlineState state = online.get(player);
        if (state != null) {
            state.sponsor = granted;
            state.sponsorActive = true;
        }
        ServerPlayer onlinePlayer = server.getPlayerList().getPlayer(player);
        if (onlinePlayer != null) {
            onlinePlayer.sendSystemMessage(expiresAt == null
                    ? Component.translatable("title.miningdim.sponsor.granted_permanent_notice")
                    : Component.translatable("title.miningdim.sponsor.granted_until_notice",
                    TitleText.time(expiresAt)));
        }
        return granted;
    }

    @Override
    public boolean revokeSponsor(UUID player, String issuer) {
        Objects.requireNonNull(player, "player");
        Objects.requireNonNull(issuer, "issuer");
        requireNoOpenTransaction("revokeSponsor");
        boolean removed = repository.deleteSponsor(player);
        CUSTOM_AUDIT.info("[miningdim] title sponsor revoke: issuer={} target={} targetUuid={} removed={}",
                issuer, nameOf(player), player, removed);
        OnlineState state = online.get(player);
        ServerPlayer onlinePlayer = server.getPlayerList().getPlayer(player);
        if (state != null) {
            state.sponsor = null;
            if (onlinePlayer != null) {
                // 撤销等同到期: 走同一条失效处理 (卸下正在佩戴的专属称号并提示本人, 记录保留)。
                reconcileSponsorship(onlinePlayer, state);
            }
        }
        return removed;
    }

    @Override
    public CustomTitleInfo customTitleInfo(UUID player) {
        Objects.requireNonNull(player, "player");
        long now = clock.getAsLong();
        SponsorStatus sponsor = sponsorOf(player);
        CustomTitle custom = customTitleOf(player);
        return new CustomTitleInfo(player, sponsor, sponsorActive(sponsor, now), custom,
                nextEditAt(custom, now, customRules.get()));
    }

    @Override
    public List<CustomTitleInfo> sponsors() {
        long now = clock.getAsLong();
        CustomTitleRules rules = customRules.get();
        List<CustomTitleInfo> infos = new ArrayList<>();
        for (SponsorStatus sponsor : repository.sponsors()) {
            CustomTitle custom = customTitleOf(sponsor.player());
            infos.add(new CustomTitleInfo(sponsor.player(), sponsor, sponsor.activeAt(now), custom,
                    nextEditAt(custom, now, rules)));
        }
        return Collections.unmodifiableList(infos);
    }

    // ---- 专属称号 ----

    @Override
    public CustomTitleResult previewCustomTitle(UUID player, CustomTitleDraft draft) {
        Objects.requireNonNull(player, "player");
        Objects.requireNonNull(draft, "draft");
        long now = clock.getAsLong();
        if (!sponsorActive(sponsorOf(player), now)) {
            return CustomTitleResult.rejected(CustomTitleResult.Status.NOT_SPONSOR);
        }
        CustomTitleRules rules = customRules.get();
        CustomTitleValidator.Validation validation = CustomTitleValidator.validate(draft, rules, true);
        if (!validation.valid()) {
            return CustomTitleResult.invalid(validation.violations());
        }
        return CustomTitleResult.accepted(CustomTitleResult.Status.VALID, validation.style(),
                nextEditAt(customTitleOf(player), now, rules));
    }

    @Override
    public CustomTitleResult setCustomTitle(ServerPlayer player, CustomTitleDraft draft) {
        Objects.requireNonNull(player, "player");
        Objects.requireNonNull(draft, "draft");
        requireNoOpenTransaction("setCustomTitle");
        UUID uuid = player.getUUID();
        OnlineState state = stateFor(player);
        if (state != null) {
            reconcileSponsorship(player, state);
        }
        long now = clock.getAsLong();
        if (!sponsorActive(sponsorOf(uuid), now)) {
            return CustomTitleResult.rejected(CustomTitleResult.Status.NOT_SPONSOR);
        }
        CustomTitle current = customTitleOf(uuid);
        if (current != null && current.locked()) {
            return CustomTitleResult.rejected(CustomTitleResult.Status.LOCKED);
        }
        CustomTitleRules rules = customRules.get();
        long cooldownEnd = nextEditAt(current, now, rules);
        if (cooldownEnd != 0L) {
            return CustomTitleResult.onCooldown(cooldownEnd);
        }
        CustomTitleValidator.Validation validation = CustomTitleValidator.validate(draft, rules, true);
        if (!validation.valid()) {
            return CustomTitleResult.invalid(validation.violations());
        }
        saveCustomTitle(uuid, current, validation.style(), now, player.getGameProfile().getName(), false);
        return CustomTitleResult.accepted(CustomTitleResult.Status.APPLIED, validation.style(),
                now + rules.editCooldownMillis());
    }

    @Override
    public CustomTitleResult adminSetCustomTitle(UUID player, CustomTitleDraft draft, String issuer) {
        Objects.requireNonNull(player, "player");
        Objects.requireNonNull(draft, "draft");
        Objects.requireNonNull(issuer, "issuer");
        requireNoOpenTransaction("adminSetCustomTitle");
        CustomTitleRules rules = customRules.get();
        CustomTitleValidator.Validation validation = CustomTitleValidator.validate(draft, rules, false);
        if (!validation.valid()) {
            return CustomTitleResult.invalid(validation.violations());
        }
        CustomTitle before = customTitleOf(player);
        // 代设置不动玩家的修改冷却: 沿用原记录的冷却起点, 新记录记为 0。管理员预先备好的记录因此不会挡住玩家自己的
        // 第一次设置; 要禁止玩家再改, 用锁定。
        long updatedAt = before == null ? 0L : before.updatedAt();
        CustomTitle saved = saveCustomTitle(player, before, validation.style(), updatedAt, issuer, true);
        ServerPlayer onlinePlayer = server.getPlayerList().getPlayer(player);
        if (onlinePlayer != null) {
            onlinePlayer.sendSystemMessage(Component.translatable("title.miningdim.custom.admin_set_notice",
                    TitleRenderer.renderBadge(saved.definition())));
        }
        return CustomTitleResult.accepted(CustomTitleResult.Status.APPLIED, validation.style(),
                nextEditAt(saved, clock.getAsLong(), rules));
    }

    @Override
    public CustomAdminResult resetCustomTitle(UUID player, String issuer) {
        Objects.requireNonNull(player, "player");
        Objects.requireNonNull(issuer, "issuer");
        requireNoOpenTransaction("resetCustomTitle");
        CustomTitle current = customTitleOf(player);
        if (current == null) {
            return CustomAdminResult.NO_CUSTOM_TITLE;
        }
        if (current.locked()) {
            return CustomAdminResult.LOCKED;
        }
        ResourceLocation customId = current.id();
        boolean unequipped = repository.inTransaction(() -> {
            repository.deleteCustomTitle(player);
            return repository.equipped(player).filter(customId::equals).isPresent()
                    && repository.clearEquipped(player);
        });
        CUSTOM_AUDIT.info("[miningdim] title custom reset: issuer={} target={} targetUuid={} before={} unequipped={}",
                issuer, nameOf(player), player, describe(current), unequipped);
        OnlineState state = online.get(player);
        if (state != null) {
            state.setCustom(null);
            if (unequipped) {
                state.equipped = null;
            }
        }
        ServerPlayer onlinePlayer = server.getPlayerList().getPlayer(player);
        if (onlinePlayer != null) {
            if (unequipped) {
                refreshDisplay(onlinePlayer);
            }
            onlinePlayer.sendSystemMessage(Component.translatable("title.miningdim.custom.reset_notice"));
        }
        return CustomAdminResult.DONE;
    }

    @Override
    public CustomAdminResult setCustomTitleLocked(UUID player, boolean locked, String issuer) {
        Objects.requireNonNull(player, "player");
        Objects.requireNonNull(issuer, "issuer");
        requireNoOpenTransaction("setCustomTitleLocked");
        CustomTitle current = customTitleOf(player);
        if (current == null) {
            return CustomAdminResult.NO_CUSTOM_TITLE;
        }
        if (current.locked() == locked) {
            return CustomAdminResult.UNCHANGED;
        }
        String lockedBy = locked ? issuer : null;
        repository.setCustomTitleLocked(player, locked, lockedBy);
        CUSTOM_AUDIT.info("[miningdim] title custom {}: issuer={} target={} targetUuid={}",
                locked ? "lock" : "unlock", issuer, nameOf(player), player);
        updateCachedCustom(player, new CustomTitle(player, current.style(), current.updatedAt(), locked, lockedBy));
        return CustomAdminResult.DONE;
    }

    @Override
    public CustomAdminResult clearCustomTitleCooldown(UUID player, String issuer) {
        Objects.requireNonNull(player, "player");
        Objects.requireNonNull(issuer, "issuer");
        requireNoOpenTransaction("clearCustomTitleCooldown");
        CustomTitle current = customTitleOf(player);
        if (current == null) {
            return CustomAdminResult.NO_CUSTOM_TITLE;
        }
        if (nextEditAt(current, clock.getAsLong(), customRules.get()) == 0L) {
            return CustomAdminResult.UNCHANGED;
        }
        // 冷却起点清零: updated_at 的含义就是"冷却从何时起算" (13.6), 置 0 即视为早已冷却完毕。
        repository.setCustomTitleUpdatedAt(player, 0L);
        CUSTOM_AUDIT.info("[miningdim] title custom cooldown cleared: issuer={} target={} targetUuid={} "
                + "previousUpdatedAt={}", issuer, nameOf(player), player, current.updatedAt());
        updateCachedCustom(player, new CustomTitle(player, current.style(), 0L, current.locked(), current.lockedBy()));
        return CustomAdminResult.DONE;
    }

    // ---- 生命周期 (由 TitleSystem 调用, 不属于对外门面) ----

    /**
     * 登录: 从库里加载该玩家的持有、佩戴、赞助资格与专属称号进缓存, 做一次到期检查 (离线期间到期、仍佩戴着
     * 专属称号的, 此时卸下并提示), 然后刷新显示名、Tab 名, 并把名牌称号发给自己与已在看他的人。
     * PlayerList.placeNewPlayer 在触发登录事件之前就已广播过 Tab 条目、其他玩家也已开始追踪他 (那时缓存
     * 还没加载), 所以这里必须补一次刷新与广播。登录事件由 placeNewPlayer 派发, 不会处在任何调用方的事务体内,
     * 这里读到的都是已提交数据。
     */
    public void loadPlayer(ServerPlayer player) {
        UUID uuid = player.getUUID();
        OnlineState state = loadState(uuid);
        online.put(uuid, state);
        reconcileSponsorship(player, state);
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

    /**
     * 在线期间的赞助到期巡检 (13.5): 由 TitleSystem 每 60 秒调用一次, 只读缓存、不查库。处理两类资格已失效的
     * 在线玩家: 上次检查时资格还有效的 (在线期间到期, 卸下并提示一次); 以及仍戴着自己专属称号的 —— 上一次卸下
     * 写库失败 (例如登录时的到期检查撞上 SQLITE_BUSY) 留下的, 这里重试到卸下落库为止。永久资格、资格有效的与
     * 没戴专属称号的非赞助玩家直接跳过; 只有真的卸下时才写库。
     */
    public void checkSponsorExpiry(Collection<ServerPlayer> players) {
        long now = clock.getAsLong();
        for (ServerPlayer player : players) {
            OnlineState state = online.get(player.getUUID());
            if (state != null && !sponsorActive(state.sponsor, now)
                    && (state.sponsorActive || CustomTitle.idOf(player.getUUID()).equals(state.equipped))) {
                reconcileSponsorship(player, state);
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
     * 只从 equip 与 setCustomTitle 调用, 两处都已排除外层事务, 因此这里读到的都是已提交数据, 可以直接写进缓存。
     */
    @Nullable
    private OnlineState stateFor(ServerPlayer player) {
        OnlineState state = online.get(player.getUUID());
        if (state != null || server.getPlayerList().getPlayer(player.getUUID()) != player) {
            return state;
        }
        state = loadState(player.getUUID());
        online.put(player.getUUID(), state);
        return state;
    }

    /** 从库里读出一名玩家的全部称号数据; sponsorActive 留为 false, 由随后的到期检查按当前时间定下来。 */
    private OnlineState loadState(UUID player) {
        OnlineState state = new OnlineState();
        state.owned = new LinkedHashSet<>(repository.owned(player));
        state.equipped = repository.equipped(player).orElse(null);
        state.sponsor = repository.sponsor(player).orElse(null);
        state.setCustom(repository.customTitle(player).orElse(null));
        return state;
    }

    /**
     * 到期检查 (13.5): 资格有效时只记下"有效"; 失效时, 若正佩戴着自己的专属称号就卸下 (写库、刷新三处显示),
     * 并在"刚卸下"或"上次检查时还有效"两种情况下提示本人 —— 后者保证在线期间到期只提示一次, 登录时发现早已
     * 过期而又没佩戴的则不打扰。专属称号记录始终保留。
     */
    private void reconcileSponsorship(ServerPlayer player, OnlineState state) {
        boolean wasActive = state.sponsorActive;
        if (sponsorActive(state.sponsor, clock.getAsLong())) {
            state.sponsorActive = true;
            return;
        }
        UUID uuid = player.getUUID();
        boolean unequipped = CustomTitle.idOf(uuid).equals(state.equipped);
        if (unequipped) {
            repository.clearEquipped(uuid);
            state.equipped = null;
            refreshDisplay(player);
        }
        // 卸下落库之后才记下"已失效": 写库失败抛出时缓存里仍戴着专属称号, 下一轮巡检据此再试一次
        // (见 checkSponsorExpiry)。
        state.sponsorActive = false;
        if (unequipped || wasActive) {
            CUSTOM_AUDIT.info("[miningdim] title sponsorship lapsed: target={} targetUuid={} customUnequipped={}",
                    player.getGameProfile().getName(), uuid, unequipped);
            player.sendSystemMessage(Component.translatable(unequipped
                    ? "title.miningdim.sponsor.lapsed_unequipped"
                    : "title.miningdim.sponsor.lapsed"));
        }
    }

    /**
     * 写入专属称号 (锁定状态沿用旧记录), 同步缓存; 正佩戴着时立即刷新三处显示。
     *
     * @param updatedAt 写入的冷却起点: 玩家自己修改为当前时间, 管理员代设置沿用原值
     */
    private CustomTitle saveCustomTitle(UUID player, @Nullable CustomTitle before, CustomTitleStyle style,
                                        long updatedAt, String actor, boolean byAdmin) {
        repository.saveCustomTitle(player, style, updatedAt);
        CustomTitle saved = new CustomTitle(player, style, updatedAt, before != null && before.locked(),
                before == null ? null : before.lockedBy());
        CUSTOM_AUDIT.info("[miningdim] title custom set: actor={} admin={} target={} targetUuid={} before={} after={}",
                actor, byAdmin, nameOf(player), player, describe(before), describe(saved));
        updateCachedCustom(player, saved);
        OnlineState state = online.get(player);
        ServerPlayer onlinePlayer = server.getPlayerList().getPlayer(player);
        if (state != null && onlinePlayer != null && saved.id().equals(state.equipped)) {
            refreshDisplay(onlinePlayer);
        }
        return saved;
    }

    private void updateCachedCustom(UUID player, @Nullable CustomTitle custom) {
        OnlineState state = online.get(player);
        if (state != null) {
            state.setCustom(custom);
        }
    }

    @Nullable
    private SponsorStatus sponsorOf(UUID player) {
        OnlineState state = online.get(player);
        return state != null ? state.sponsor : repository.sponsor(player).orElse(null);
    }

    @Nullable
    private CustomTitle customTitleOf(UUID player) {
        OnlineState state = online.get(player);
        return state != null ? state.custom : repository.customTitle(player).orElse(null);
    }

    private static boolean sponsorActive(@Nullable SponsorStatus sponsor, long now) {
        return sponsor != null && sponsor.activeAt(now);
    }

    /** 下次可修改的时间; 没有记录或冷却已过为 0。 */
    private static long nextEditAt(@Nullable CustomTitle custom, long now, CustomTitleRules rules) {
        if (custom == null) {
            return 0L;
        }
        long cooldownEnd = custom.updatedAt() + rules.editCooldownMillis();
        return cooldownEnd > now ? cooldownEnd : 0L;
    }

    /** 审计日志里的到期时间: 毫秒时间戳, 永久记为 permanent。 */
    private static String expiryText(@Nullable Long expiresAt) {
        return expiresAt == null ? "permanent" : String.valueOf(expiresAt);
    }

    /** 审计日志里的专属称号: 修改前后的文字与样式。 */
    private static String describe(@Nullable CustomTitle custom) {
        if (custom == null) {
            return "-";
        }
        CustomTitleStyle style = custom.style();
        return "'" + style.text() + "' colors=" + style.colorsText() + " bold=" + style.bold();
    }

    private String nameOf(UUID player) {
        return TitleText.playerName(server, player);
    }

    /** 在线玩家当前应显示的称号; 专属称号只认自己的 (缓存里的记录与佩戴的 id 对得上)。 */
    @Nullable
    private TitleRenderer.Rendered displayed(OnlineState state) {
        ResourceLocation equipped = state.equipped;
        if (equipped == null) {
            return null;
        }
        if (CustomTitle.isCustomId(equipped)) {
            return state.custom != null && equipped.equals(state.custom.id()) ? state.customRendered : null;
        }
        return renderer.rendered(equipped);
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
        /** title_owned 里的持有 (不含推导出来的专属称号)。 */
        @Nullable
        private Set<ResourceLocation> owned;
        @Nullable
        private ResourceLocation equipped;
        @Nullable
        private SponsorStatus sponsor;
        /** 上次到期检查时资格是否有效: 用来识别在线期间"有效 → 失效"这一跳变, 只提示一次。 */
        private boolean sponsorActive;
        @Nullable
        private CustomTitle custom;
        /** 专属称号的渲染结果, 随 custom 一起更新; 显示路径直接读它。 */
        @Nullable
        private TitleRenderer.Rendered customRendered;

        private void setCustom(@Nullable CustomTitle custom) {
            this.custom = custom;
            this.customRendered = custom == null ? null : TitleRenderer.Rendered.of(custom.definition());
        }
    }
}
