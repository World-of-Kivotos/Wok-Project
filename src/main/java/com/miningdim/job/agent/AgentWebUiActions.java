package com.miningdim.job.agent;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.miningdim.job.agent.panel.AgentScanEntry;
import com.miningdim.job.agent.panel.AgentScanSnapshot;
import com.miningdim.webui.server.WebUiBusinessException;
import com.miningdim.webui.server.WebUiErrorCodes;
import com.miningdim.webui.server.WebUiPayloads;
import com.miningdim.webui.server.WebUiServerDispatcher;
import com.miningdim.webui.server.WebUiServerDispatcher.WebUiAction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 特勤面板的 job.agent.state / job.agent.scan / job.agent.seal WebUiAction。
 *
 * 服务端权威 (架构铁律 1): 扫描分级解密与封印九态裁决一概不在本层重算 —— 扫描快照走
 * {@link AgentSealSeam#buildScanSnapshot} (集成层读真精英 -> {@code AgentScanSnapshotBuilder} 逐条裁决),
 * 封印走 {@link AgentSealSeam#requestSealResult} (集成层聚合 SealPlan 三门 + SealRegistry 占槽 + 真改)。
 * 本层只负责: 入参校验 -> 脉冲 CD / 半径两道防 X 光门 -> 快照留存 -> JSON 化。
 *
 * 触发入口 (决策 J9): 本类是探测脉冲的<b>第一个</b>真实调用点 —— 在此之前
 * {@code AgentSealSeam.buildScanSnapshot} 与 {@code AgentScanMenu.Provider} 全工程零调用点。键位入口是客户端
 * 改动, 不在本类职责内; 服务端这条路自此可被调用且自洽。
 *
 * 脉冲记录 ({@link ScanPulse}) 一份数据同时承载三件事, 刻意共用同一个 {@code pulseTick}:
 *  1. 脉冲 CD (五章: 主动脉冲带长 CD 60s-&gt;30s 防全图刷新) —— 记录还在, 就还在冷却;
 *  2. 快照有效期 —— 记录还在, 快照里的 targetNetworkId 才可用于封印;
 *  3. 封印的前置门 (六章"探测与封印合一, 未解密的词条点不了")。
 * 二者时长相等是设计决定而非巧合: 快照活得比 CD 长, 玩家就能拿一份过期情报反复封印; 活得比 CD 短, 就会出现
 * "既不能重扫又不能封印"的空窗。故到期即同时释放, 不留死区。
 *
 * 记录只在进程内存 (与 {@link SealRegistry} 的封印 CD 账本同档): 按玩家 UUID 键, 跨死亡/跨重连保留, 服务端重启
 * 归零。跨存档脏读由 {@link #activePulse} 的时钟回退判据自愈 —— 单人重开另一个世界时 gameTime 会倒退, 那条旧记录
 * 会把冷却顶到一个永远到不了的未来。
 *
 * 前端契约 (逐字见交付报告): 三条 action 的回执一律 {@code serializeNulls}, null 是"这一格没解密/没有值"的真值
 * (默认 Gson 会把 null 成员整键丢掉, 前端拿到 undefined 即契约破裂)。时间一律发剩余 tick 不发墙钟 epoch
 * (与 job.miner.* 同口径: 服务端手里只有 game tick)。
 */
public final class AgentWebUiActions {

    /**
     * 本类专用 Gson: 必须 serializeNulls。未解密词条的 affixId/displayKey/category 与未解锁的目标坐标都发
     * JSON null —— 那是"这一格加密"的真值, 整键消失会让前端把它当成契约破裂。
     */
    private static final Gson GSON = new GsonBuilder().serializeNulls().create();

    /** 错误码 params 里标识是哪个主动技能 (SKILL_ON_COOLDOWN 由它区分是矿工探矿还是特勤脉冲)。 */
    private static final String SKILL_TACTICAL_SCAN = "tactical_scan";

    /**
     * 一次脉冲最多下发的目标数 (回执体积硬上限, 非兜底)。
     *
     * 预算: 单个目标最坏是 10★ 的 {@code StarRank.STAR_10.maxAffixes()} = 13 条词条, 每条 JSON 约 180 字符,
     * 加目标头部约 2.5 KB; 8 个目标约 20 KB, 仍在 {@code WebUiServerDispatcher.respond} 的 32767 字符收口之内。
     * 收口是保命不是设计, 列表类 action 自带上限。
     *
     * 包私有: 同包 GameTest 直接断言截断行为, 不在测试里另写一个魔数。
     */
    static final int MAX_SCAN_TARGETS = 8;

    /** 秒 -&gt; tick (原版 20 tick/s); 与 {@link SealRegistry#applySeal} 的窗口/CD 换算同口径。 */
    private static final int TICKS_PER_SECOND = 20;

    /** 玩家 UUID -&gt; 最近一次仍在生命期内的脉冲记录 (CD + 快照 + 封印前置门三合一)。 */
    private static final Map<UUID, ScanPulse> PULSES = new ConcurrentHashMap<>();

    private AgentWebUiActions() {
    }

    /** 把三条 job.agent.* action 注册进派发器 (由 {@link AgentSystem#register} 调用一次)。 */
    public static void registerAll() {
        WebUiServerDispatcher.register("job.agent.state", STATE);
        WebUiServerDispatcher.register("job.agent.scan", SCAN);
        WebUiServerDispatcher.register("job.agent.seal", SEAL);
    }

    // ============================================================
    // 脉冲记录
    // ============================================================

    /**
     * 一次脉冲扫到的单个目标。
     *
     * 坐标存脉冲当刻的方块坐标而非每次回执现读实体位置: 快照就是快照, 目标跑了不该让面板跟着它走 —— 那等于把
     * 长 CD 的一次性脉冲变成实时追踪器。
     */
    private record ScanTarget(int networkId, double distanceBlocks, String entityTypeId, String entityNameKey,
                              int posX, int posY, int posZ, AgentScanSnapshot snapshot) {
    }

    /**
     * 一次脉冲的完整记录。{@code agentLevel} 存脉冲当刻的等级: 解密分级是那一刻算出来的, 事后升级不该让已经
     * 加密的条目凭空解密 (要看得更透就再扫一次, 这正是 CD 的意义)。
     */
    private record ScanPulse(long pulseTick, int agentLevel, int cooldownTicks, int radiusBlocks,
                             boolean crossChunk, boolean truncated, List<ScanTarget> targets) {
    }

    // ============================================================
    // job.agent.state: {} -> 面板一屏所需的只读态 (不推进任何状态)
    // ============================================================

    /**
     * 特勤面板只读态。本 action <b>不烧 CD、不发脉冲、不写快照</b>: 它读当前脉冲记录, 记录已到期则如实报 0 与空表。
     */
    static final WebUiAction STATE = (sender, payload) -> {
        long now = sender.serverLevel().getGameTime();
        int level = AgentLevels.agentLevel(sender);
        ScanPulse pulse = activePulse(sender.getUUID(), now);

        JsonObject result = new JsonObject();
        result.addProperty("level", level);
        // 接缝未绑定 = Champions 未加载, 扫描读不到真精英词条。面板据此显示"扫描离线"而不是一张空的候选表。
        result.addProperty("scanOnline", AgentSealSeam.isBound());
        result.addProperty("scanRadiusBlocks", effectiveScanRadiusBlocks(sender, level));
        result.addProperty("scanCrossChunk", isCrossChunk(level));
        result.addProperty("scanPulseCooldownTicks", pulseCooldownTicks(level));
        // 两个字段按设计恒等 (同一个 pulseTick 派生), 分成两个名字是因为前端要显示的是两句话:
        // "还有多久能再扫" 与 "这份情报还能用多久"。
        result.addProperty("scanCooldownRemainingTicks", remainingTicks(pulse, now));
        result.addProperty("snapshotRemainingTicks", remainingTicks(pulse, now));
        // 与 job.agent.scan 同名同义 (是同一份脉冲记录的两次投影), 前端可用同一个列表组件渲染两处。
        result.addProperty("truncated", pulse != null && pulse.truncated());
        result.add("targets", targetsJson(pulse));

        JsonObject seal = new JsonObject();
        seal.addProperty("passiveUnlockLevel", AgentSkillTable.SEAL_UNLOCK_LEVEL);
        seal.addProperty("mechanicUnlockLevel", AgentSkillTable.MECHANIC_SEAL_UNLOCK_LEVEL);
        seal.addProperty("passiveUnlocked", AgentSkillTable.isPassiveSealUnlocked(level));
        seal.addProperty("mechanicUnlocked", AgentSkillTable.isMechanicSealUnlocked(level));
        // 未解锁封印时是真值 0 (maxSealableStar 的 L<3 分支), 不是缺省填充; 前端不得把它当"无限制"。
        seal.addProperty("maxSealableStar", AgentSkillTable.maxSealableStar(level));
        seal.addProperty("passiveWindowSeconds", AgentSkillTable.sealWindowSeconds(level, SealCategory.PASSIVE));
        seal.addProperty("passiveCooldownSeconds", AgentSkillTable.sealCooldownSeconds(level, SealCategory.PASSIVE));
        seal.addProperty("mechanicWindowSeconds", AgentSkillTable.sealWindowSeconds(level, SealCategory.MECHANIC));
        seal.addProperty("mechanicCooldownSeconds", AgentSkillTable.sealCooldownSeconds(level, SealCategory.MECHANIC));
        seal.addProperty("passiveCooldownRemainingTicks",
                sealCooldownRemainingTicks(sender, SealCategory.PASSIVE, now));
        seal.addProperty("mechanicCooldownRemainingTicks",
                sealCooldownRemainingTicks(sender, SealCategory.MECHANIC, now));
        // 槽容量是 (干员等级 × 目标星级) 的二元函数, 拆成两档发: 普通目标一档, 8★+ 一档 (第二槽还要 L9)。
        seal.addProperty("slotsDefault", AgentSkillTable.sealSlots(level, 1));
        seal.addProperty("slotsVsStar8Plus", AgentSkillTable.sealSlots(level, 8));
        seal.addProperty("secondSlotUnlockLevel", AgentSkillTable.SECOND_SEAL_SLOT_UNLOCK_LEVEL);
        result.add("seal", seal);

        AgentBountySavedData bountyData = AgentBountySavedData.get(sender.server.overworld());
        JsonObject bounty = new JsonObject();
        bounty.addProperty("dailySlots", AgentSkillTable.dailyBountySlots(level));
        bounty.addProperty("weeklySlots", AgentSkillTable.weeklyBountySlots(level));
        bounty.addProperty("weeklyUnlocked", AgentSkillTable.isWeeklyBountyUnlocked(level));
        bounty.addProperty("weeklyUnlockLevel", AgentSkillTable.WEEKLY_BOUNTY_UNLOCK_LEVEL);
        bounty.addProperty("maxBountyStar", AgentSkillTable.maxBountyStar(level));
        bounty.addProperty("worldBossUnlocked", AgentSkillTable.isWorldBossBountyUnlocked(level));
        bounty.addProperty("worldBossUnlockLevel", AgentSkillTable.WORLD_BOSS_BOUNTY_UNLOCK_LEVEL);
        bounty.addProperty("weeklyAzureGranted",
                bountyData.weeklyAzureGranted(sender.getUUID(), AgentClock.currentUtcWeekStamp()));
        bounty.addProperty("weeklyAzureCap", AgentBountySavedData.WEEKLY_AZURE_SOFT_CAP);
        result.add("bounty", bounty);

        result.addProperty("enhancedRewardMultiplier", AgentSkillTable.enhancedRewardMultiplier(level));
        result.addProperty("damageBonusPercent", AgentSkillTable.damageBonusPercent(level));
        // 入职标志: 特勤专属福利 (加强奖励 / 对精英伤害放大) 的真实门, 面板必须显示它, 否则玩家看到一张
        // "×3.0 倍率"的表却一分钱也吃不到。
        result.addProperty("activeAgent", bountyData.isActiveAgent(sender.getUUID()));
        return GSON.toJson(result);
    };

    // ============================================================
    // job.agent.scan: {} -> 一次探测脉冲 (写操作: 烧 CD + 覆盖快照)
    // ============================================================

    /**
     * 战术扫描脉冲。防 X 光的三条硬约束逐条落在本 action 内, 绕开任何一条都是开挂通道:
     *  1. CD 门: {@link AgentSkillTable#scanPulseCdSeconds} (60s-&gt;30s), 冷却中直接拒且不延长既有 CD;
     *  2. 半径门: {@link AgentSkillTable#scanRangeBlocks}, 且是<b>球</b>不是立方体 (与矿工探矿同纪律);
     *  3. 分级解密: 一律由 {@link AgentSealSeam#buildScanSnapshot} 背后的构建器逐条裁决, 本层不碰。
     *
     * 空脉冲 (球内一只盖章精英也没有) 同样烧掉整轮 CD —— 让"扫空"免费重试就等于把 CD 变成"扫到为止"。
     *
     * 入参刻意为空: 不收目标 id、不收半径。给玩家开这两个口子等于把服务端的两道门交给客户端自己填。
     */
    static final WebUiAction SCAN = (sender, payload) -> {
        ServerLevel level = sender.serverLevel();
        long now = level.getGameTime();
        int agentLevel = AgentLevels.agentLevel(sender);
        int radius = effectiveScanRadiusBlocks(sender, agentLevel);
        int cooldownTicks = pulseCooldownTicks(agentLevel);

        ScanPulse active = activePulse(sender.getUUID(), now);
        if (active != null) {
            long remaining = remainingTicks(active, now);
            throw new WebUiBusinessException(WebUiErrorCodes.SKILL_ON_COOLDOWN,
                    "战术扫描脉冲冷却中, 还需 " + remaining + " tick", false,
                    Map.of("skill", SKILL_TACTICAL_SCAN, "remainingTicks", Long.toString(remaining)));
        }

        JsonObject result = new JsonObject();
        result.addProperty("agentLevel", agentLevel);
        result.addProperty("radiusBlocks", radius);
        result.addProperty("crossChunk", isCrossChunk(agentLevel));
        result.addProperty("pulseCooldownTicks", cooldownTicks);

        if (!AgentSealSeam.isBound()) {
            // Champions 未加载: 接缝对每个目标都返 null, 扫也只会得到空表。此时不烧 CD —— 让玩家为一个他无法
            // 影响的离线子系统赔上整轮 30-60 秒, 是把优雅降级变成惩罚。也不写快照 (没有快照可写)。
            result.addProperty("scanOnline", false);
            result.addProperty("truncated", false);
            result.addProperty("scanCooldownRemainingTicks", 0L);
            result.addProperty("snapshotRemainingTicks", 0L);
            result.add("targets", new JsonArray());
            return GSON.toJson(result);
        }

        List<LivingEntity> candidates = level.getEntitiesOfClass(LivingEntity.class,
                sender.getBoundingBox().inflate(radius),
                candidate -> candidate != sender && candidate.isAlive());
        candidates.sort(Comparator.comparingDouble((LivingEntity candidate) -> sender.distanceToSqr(candidate)));

        double radiusSqr = (double) radius * (double) radius;
        List<ScanTarget> targets = new ArrayList<>();
        boolean truncated = false;
        for (LivingEntity candidate : candidates) {
            double distanceSqr = sender.distanceToSqr(candidate);
            if (distanceSqr > radiusSqr) {
                // 已按距离升序: 第一个出球的之后必定全在球外。半径门是球不是立方体 —— 退化成 AABB 后对角线可达
                // radius*sqrt(3), L9 的 448 会泄漏到 775 格外。
                break;
            }
            if (targets.size() >= MAX_SCAN_TARGETS) {
                // 球内仍有没来得及检视的候选 (是不是精英未知), 但回执已达硬上限。
                truncated = true;
                break;
            }
            AgentScanSnapshot snapshot = AgentSealSeam.buildScanSnapshot(sender, candidate);
            if (snapshot == null) {
                continue; // 非本工程盖章精英: 无可扫情报 (集成层判据, 本层不复制)。
            }
            targets.add(new ScanTarget(
                    snapshot.targetNetworkId(),
                    Math.sqrt(distanceSqr),
                    EntityType.getKey(candidate.getType()).toString(),
                    candidate.getType().getDescriptionId(),
                    candidate.blockPosition().getX(),
                    candidate.blockPosition().getY(),
                    candidate.blockPosition().getZ(),
                    snapshot));
        }

        ScanPulse pulse = new ScanPulse(now, agentLevel, cooldownTicks, radius,
                isCrossChunk(agentLevel), truncated, List.copyOf(targets));
        PULSES.put(sender.getUUID(), pulse);

        // 刻意不在这里 markActiveAgent。该标志是加强奖励 (每星 600 信用点) 与对精英伤害放大的唯一资格门,
        // 且一经置位永久保留; 它原本的唯一置位点是"封印成功"(经 SealPlan 要求被动 L3+)。扫描对全员开放且
        // 职业等级默认 1 级, 在此置位等于把入职门槛降成"站在精英旁边点一次按钮", 特勤专属福利就漏给了
        // 全服每一个打精英的人 —— 那正是 AgentBountySavedData 立这个标志要防的事。入职仍只认封印成功。
        result.addProperty("scanOnline", true);
        result.addProperty("truncated", truncated);
        result.addProperty("scanCooldownRemainingTicks", (long) cooldownTicks);
        result.addProperty("snapshotRemainingTicks", (long) cooldownTicks);
        result.add("targets", targetsJson(pulse));
        return GSON.toJson(result);
    };

    // ============================================================
    // job.agent.seal: {targetNetworkId, affixId} -> 九态裁决直转调
    // ============================================================

    /**
     * 封印申请。九态裁决 ({@link AgentSealSeam.SealOutcome}) 一概由接缝给出, 本层<b>不做任何等级/星级/类别/槽位
     * 判断</b> —— 那些门在集成层里已经齐全, 在这里重写一份就等于埋一个迟早与真裁决分叉的影子实现。
     *
     * 本层只加两道接缝管不了的前置门, 且都在转调之前:
     *  1. 快照门: targetNetworkId 必须来自本玩家当前仍有效的脉冲快照 (六章"探测与封印合一"; 没扫过就能封等于
     *     把探测支线整条跳过);
     *  2. 解密门: 该词条在那份快照里必须是已解密的 (六章"未解密的词条点不了")。集成层的 requestSeal 只查词条
     *     可封性, 不查解密态 —— 少了这道门, 客户端直接送一个加密词条的注册名就能封。
     */
    static final WebUiAction SEAL = (sender, payload) -> {
        int targetNetworkId = WebUiPayloads.requiredInt(payload, "targetNetworkId");
        String affixId = WebUiPayloads.requiredString(payload, "affixId");
        if (affixId.isBlank()) {
            throw WebUiPayloads.illegalValue("affixId", affixId, "词条注册名不能为空");
        }

        ServerLevel level = sender.serverLevel();
        long now = level.getGameTime();

        ScanPulse pulse = activePulse(sender.getUUID(), now);
        if (pulse == null) {
            throw WebUiPayloads.illegalValue("targetNetworkId", Integer.toString(targetNetworkId),
                    "没有仍然有效的扫描快照: 封印前必须先做一次战术扫描");
        }
        ScanTarget target = findTarget(pulse, targetNetworkId);
        if (target == null) {
            throw WebUiPayloads.illegalValue("targetNetworkId", Integer.toString(targetNetworkId),
                    "该目标不在本次扫描快照内");
        }
        // 下面两道门共用同一句拒绝文案, 是刻意的, 不是偷懒: 拒绝文案经 businessErrorJson 原样进浏览器,
        // 一旦"目标身上没有这条词条"与"有但尚未解密"文案可分, 客户端就能拿 Champions 那二十来个公开注册名
        // 逐个试探, 二十次请求即在 L1 反推出整张词条表 —— entryJson 把未解密行的 affixId/displayKey/category
        // 打成 null 的脱敏会被完全绕开, 分级解密也就白做了。
        // 合并不损失正常体验: 面板本来就持有每行的 decrypted 标志, "尚未解密"这句话由前端自己讲。
        AgentScanEntry entry = findEntry(target.snapshot(), affixId);
        if (entry == null || !entry.decrypted()) {
            throw WebUiPayloads.illegalValue("affixId", affixId, "该词条当前不可封印");
        }

        // 目标复原与 C2S 键位路径同口径 (AgentSealRequestC2S.handle): 找不到 / 非 LivingEntity = 目标已离场,
        // 回九态里的 NO_TARGET, 不抛 (目标随时可能离区块, 属正常业务分支)。
        Entity resolved = level.getEntity(targetNetworkId);
        AgentSealSeam.SealOutcome outcome = resolved instanceof LivingEntity living
                ? AgentSealSeam.requestSealResult(sender, living, affixId)
                : AgentSealSeam.SealOutcome.NO_TARGET;

        int agentLevel = AgentLevels.agentLevel(sender);
        JsonObject result = new JsonObject();
        result.addProperty("ok", outcome == AgentSealSeam.SealOutcome.OK);
        result.addProperty("outcomeCode", outcome.name());
        result.addProperty("targetNetworkId", targetNetworkId);
        result.addProperty("affixId", affixId);
        result.addProperty("category", entry.category().name());
        // 窗口/CD 取的正是 SealRegistry 占槽时用的同一张表同一对入参, 不另算一份。
        result.addProperty("windowSeconds", AgentSkillTable.sealWindowSeconds(agentLevel, entry.category()));
        result.addProperty("cooldownSeconds", AgentSkillTable.sealCooldownSeconds(agentLevel, entry.category()));
        // 成功后是刚起的 CD, 被 ON_COOLDOWN 拒时是还剩多少 —— 两种情况前端都要显示同一个倒计时。
        result.addProperty("categoryCooldownRemainingTicks",
                sealCooldownRemainingTicks(sender, entry.category(), now));
        return GSON.toJson(result);
    };

    // ============================================================
    // 脉冲生命期
    // ============================================================

    /**
     * 取该玩家当前仍在生命期内的脉冲记录 (到期 / 脏记录就地清除后返 null)。
     *
     * 两个丢弃判据:
     *  - {@code nowTick >= pulseTick + cooldownTicks}: 正常到期 (CD 走完 = 快照同时失效);
     *  - {@code nowTick < pulseTick}: 世界时钟倒退。本表是进程级静态, 单人退出后重开另一个存档时 gameTime 会
     *    回到那个存档自己的刻数, 留着旧记录会把冷却顶到一个永远到不了的未来, 面板从此死在"冷却中"。
     */
    private static ScanPulse activePulse(UUID playerId, long nowTick) {
        ScanPulse pulse = PULSES.get(playerId);
        if (pulse == null) {
            return null;
        }
        if (nowTick < pulse.pulseTick() || nowTick >= pulse.pulseTick() + pulse.cooldownTicks()) {
            PULSES.remove(playerId, pulse);
            return null;
        }
        return pulse;
    }

    /**
     * 仅供同包 GameTest: 把该玩家现有脉冲的时间戳整体往回拨 {@code deltaTicks}, 用来在不真等 30-60 秒的前提下
     * 测到期语义 (脉冲 CD 最短 600 tick, 让测试真跑完是不可行的)。
     *
     * @return 是否真的拨动了 (false = 该玩家当前没有脉冲记录)
     */
    static boolean rewindPulseForTest(UUID playerId, long deltaTicks) {
        ScanPulse pulse = PULSES.get(playerId);
        if (pulse == null) {
            return false;
        }
        PULSES.put(playerId, new ScanPulse(pulse.pulseTick() - deltaTicks, pulse.agentLevel(),
                pulse.cooldownTicks(), pulse.radiusBlocks(), pulse.crossChunk(), pulse.truncated(),
                pulse.targets()));
        return true;
    }

    /** 脉冲剩余生命 tick (0 = 无记录 / 已到期)。非 null 记录必定 &gt; 0 (见 {@link #activePulse} 的到期判据)。 */
    private static long remainingTicks(ScanPulse pulse, long nowTick) {
        if (pulse == null) {
            return 0L;
        }
        return pulse.pulseTick() + pulse.cooldownTicks() - nowTick;
    }

    private static ScanTarget findTarget(ScanPulse pulse, int targetNetworkId) {
        for (ScanTarget target : pulse.targets()) {
            if (target.networkId() == targetNetworkId) {
                return target;
            }
        }
        return null;
    }

    private static AgentScanEntry findEntry(AgentScanSnapshot snapshot, String affixId) {
        for (AgentScanEntry entry : snapshot.entries()) {
            if (entry.affixId().equals(affixId)) {
                return entry;
            }
        }
        return null;
    }

    // ============================================================
    // 查表
    // ============================================================

    private static int pulseCooldownTicks(int level) {
        return AgentSkillTable.scanPulseCdSeconds(level) * TICKS_PER_SECOND;
    }

    private static boolean isCrossChunk(int level) {
        return AgentSkillTable.scanRangeBlocks(level) == AgentSkillTable.SCAN_RANGE_CROSS_CHUNK;
    }

    /**
     * 本次扫描真正生效的半径 (格)。
     *
     * L1-L9 直接是第四章范围列。L10 的表值是 {@link AgentSkillTable#SCAN_RANGE_CROSS_CHUNK} 哨兵 ("跨区块",
     * 不按格数记), 而 AABB 检索必须要一个数, 于是取<b>玩家的实体追踪视界</b> (视距区块 × 16): 视界之外的实体
     * 根本不在服务端的活动实体表里, 再放大只会扫到空气, 这是"跨区块"唯一诚实的物理上界。
     *
     * 再与 L9 的 448 取大值: 服务端视距开得小时 (10 区块 = 160 格) 视界会比 L9 的表值还短, 直接用会让满级干员
     * 的扫描范围反而缩水 —— 曲线必须单调。
     */
    private static int effectiveScanRadiusBlocks(ServerPlayer sender, int level) {
        int table = AgentSkillTable.scanRangeBlocks(level);
        if (table != AgentSkillTable.SCAN_RANGE_CROSS_CHUNK) {
            return table;
        }
        int trackingHorizon = sender.server.getPlayerList().getViewDistance() * 16;
        return Math.max(AgentSkillTable.scanRangeBlocks(AgentSkillTable.MAX_LEVEL - 1), trackingHorizon);
    }

    /**
     * 该干员该词条类别的封印 CD 剩余 tick (0 = 已就绪)。
     *
     * 读的是 {@link SealRegistry} 的活账本而不是自己记一份 —— 键位路径与面板路径共用同一个 CD, 各记一份就等于
     * 开了个后门。无记录时 {@code nextAllowedTick} 返回 0, 差值为负, 夹到 0 是"已就绪"的正确取值域。
     */
    private static long sealCooldownRemainingTicks(ServerPlayer sender, SealCategory category, long nowTick) {
        long nextAllowed = SealRegistry.nextAllowedTick(sender.getUUID(), category);
        return Math.max(0L, nextAllowed - nowTick);
    }

    // ============================================================
    // JSON
    // ============================================================

    private static JsonArray targetsJson(ScanPulse pulse) {
        JsonArray array = new JsonArray();
        if (pulse == null) {
            return array;
        }
        boolean positionUnlocked = AgentScanTier.canDecrypt(pulse.agentLevel(), AgentScanField.GLOWING_HIGHLIGHT);
        for (ScanTarget target : pulse.targets()) {
            JsonObject json = new JsonObject();
            json.addProperty("targetNetworkId", target.networkId());
            json.addProperty("star", target.snapshot().star());
            // 原样发未取整的距离: 显示几位小数是前端的事, 服务端提前四舍五入会让面板永远看不到真实距离。
            json.addProperty("distanceBlocks", target.distanceBlocks());
            json.addProperty("entityTypeId", target.entityTypeId());
            json.addProperty("entityNameKey", target.entityNameKey());
            if (positionUnlocked) {
                JsonObject pos = new JsonObject();
                pos.addProperty("x", target.posX());
                pos.addProperty("y", target.posY());
                pos.addProperty("z", target.posZ());
                json.add("pos", pos);
            } else {
                // 精确坐标是第四章 L8 那一格 (Glowing 高亮, 穿墙可见) 才解密的东西。低级干员拿到坐标等于提前
                // 七级拿到穿墙透视。发 JSON null 而不是 0 —— 0 是一个真实存在的坐标。
                json.add("pos", JsonNull.INSTANCE);
            }
            JsonArray entries = new JsonArray();
            for (AgentScanEntry entry : target.snapshot().entries()) {
                entries.add(entryJson(entry));
            }
            json.add("entries", entries);
            array.add(json);
        }
        return array;
    }

    /**
     * 一条词条。
     *
     * 未解密条目连 affixId 与 category 都不下发: 快照 record 里它们是真值 (S2C 面板靠 {@code decrypted} 自律
     * 不显示), 但"客户端自律"在浏览器里不成立 —— 把 {@code champions:xxx} 发进 CEF, 等于在开发者工具里明码
     * 给出词条身份, 整条分级解密就白做了。空串同样不行 (那是一个可以被当成 id 的值), 发 JSON null。
     */
    private static JsonObject entryJson(AgentScanEntry entry) {
        JsonObject json = new JsonObject();
        if (entry.decrypted()) {
            json.addProperty("affixId", entry.affixId());
            json.addProperty("displayKey", entry.displayKey());
            json.addProperty("category", entry.category().name());
        } else {
            json.add("affixId", JsonNull.INSTANCE);
            json.add("displayKey", JsonNull.INSTANCE);
            json.add("category", JsonNull.INSTANCE);
        }
        json.addProperty("decrypted", entry.decrypted());
        json.addProperty("sealable", entry.sealable());
        json.addProperty("sealed", entry.sealed());
        return json;
    }
}
