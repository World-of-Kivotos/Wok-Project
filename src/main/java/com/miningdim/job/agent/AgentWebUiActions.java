package com.miningdim.job.agent;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.miningdim.champion.AffixDef;
import com.miningdim.champion.MiningChampionData;
import com.miningdim.champion.MiningChampions;
import com.miningdim.job.agent.panel.AgentScanEntry;
import com.miningdim.job.agent.panel.AgentScanIntel;
import com.miningdim.job.agent.panel.AgentScanLive;
import com.miningdim.job.agent.panel.AgentScanSnapshot;
import com.miningdim.job.agent.panel.AgentScanSnapshotBuilder;
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
import java.util.EnumSet;
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
 * 触发入口 (决策 J9): {@code AgentScanMenu} 那条原生面板路径已在本 PR 整条删除 (统一 UI 入口走平板 hub, 不为
 * 特勤单开 ad-hoc 原生入口) —— 扫描与封印的唯一入口是本类的三条 WebUI action ({@code job.agent.scan} /
 * {@code job.agent.seal} / {@code job.agent.state})。
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
 * 扫描脉冲顺带的两件副作用都挂在同一次脉冲上: L8+ 给本人开逐玩家高亮 ({@link AgentScanGlow}, 生命期 = 快照),
 * 以及 8.1 首次发现经验 ({@link AgentDiscoveryXp}, 每 (玩家, 精英) 一次)。
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
     * 预算: 单个目标最坏是 10★ 的 {@code StarRank.STAR_10.maxAffixes()} = 13 条词条 (纯防御的生存池词条不进面板,
     * 实际更少), 每条 JSON 最长约 170 字符 (含 L8 品质格) 约 2.2 KB; 头部约 0.2 KB, L3-L6 数值情报约 0.2 KB,
     * 技能时序最多 4 条 (技能数上限) 约 0.3 KB, L10 实时透视 (含全属性) 约 0.35 KB, 合计单目标约 3.3 KB; 8 个目标
     * 约 27 KB, 加 job.agent.state 其余字段约 1 KB, 仍在 {@code WebUiServerDispatcher.respond} 的 32767 字符收口
     * 之内。收口是保命不是设计, 列表类 action 自带上限; 往目标行里再加字段前先重算这笔账。
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

    /**
     * 把 job.agent.* action 注册进派发器 (由 {@link AgentSystem#register} 调用一次): 本类三条 + 悬赏接取
     * ({@link AgentBountyWebUi})。
     */
    public static void registerAll() {
        WebUiServerDispatcher.register("job.agent.state", STATE);
        WebUiServerDispatcher.register("job.agent.scan", SCAN);
        WebUiServerDispatcher.register("job.agent.seal", SEAL);
        AgentBountyWebUi.registerAll();
    }

    // ============================================================
    // 脉冲记录
    // ============================================================

    /**
     * 一次脉冲扫到的单个目标。
     *
     * 坐标存脉冲当刻的方块坐标而非每次回执现读实体位置: 快照就是快照, 目标跑了不该让面板跟着它走 —— 那等于把
     * 长 CD 的一次性脉冲变成实时追踪器。L9+ 的实时透视也只重读数值、从不重读坐标, 守的是同一条线。
     *
     * entityUuid 用于实时透视与高亮复原目标时核对身份: 网络 id 在实体死亡后会被新实体复用, 只凭它回查会把一只
     * 从没被扫过的新怪的活数值 (或高亮) 发给干员 —— 那就是一次免费的新扫描。
     */
    private record ScanTarget(int networkId, UUID entityUuid, double distanceBlocks, String entityTypeId,
                              String entityNameKey, int posX, int posY, int posZ, AgentScanSnapshot snapshot,
                              BountyKill bountyFacts) {
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
     *
     * L9+ 脉冲的实时透视 (五章"实时透视") 也在这里: 对快照内每个目标按网络 id + UUID 回查, 仍加载且活着的重读
     * 数值 (血 / 吸收 / 叠在你身上的 DoT 层数; L10 另加全部属性), 面板在这种快照有效期内以适中间隔轮询本 action。
     * 这仍是只读: 不补扫新目标 (目标集合冻结在脉冲那一刻)、不重读坐标, 快照一过期实时数据随之消失。
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
        result.addProperty("glowingHighlight",
                pulse != null && AgentScanSnapshotBuilder.highlightsTargets(pulse.agentLevel()));
        // 各情报格的解锁等级 (第四章探测列) 原样发 AgentScanField 表: 面板上"需要 Lv.N"的占位文案据此显示, 前端
        // 不另抄一份等级表 —— 抄了就会在有人调表时与服务端的真裁决分叉 (与 seal.passiveUnlockLevel 同一做法)。
        JsonObject unlockLevels = new JsonObject();
        for (AgentScanField field : AgentScanField.values()) {
            unlockLevels.addProperty(field.name(), field.unlockLevel());
        }
        result.add("scanFieldUnlockLevels", unlockLevels);
        result.add("targets", targetsJson(pulse, sender));

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
        // 悬赏段 (槽位权限 + 本期悬赏板 + 世界 BOSS 讨伐令) 的投影归 AgentBountyWebUi; available 只在运营关掉悬赏时为 false。
        result.add("bounty", AgentBountyWebUi.bountyJson(sender));

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
            result.addProperty("glowingHighlight", false);
            result.addProperty("discoveryXp", 0L);
            result.addProperty("discoveryCount", 0);
            result.add("targets", new JsonArray());
            return GSON.toJson(result);
        }

        // predicate 里直接下判 MiningChampions.isChampion: F024 修完后精英探测已是自研 capability
        // (MiningChampionData, 注册中心 champion/MiningChampions.java), 不再有 compileOnly 隔离约束, 本层可以
        // 直接调用而不用绕道集成层。这道判据只做廉价预筛 —— 收窄 getEntitiesOfClass 的候选表, 换来后面排序 /
        // 遍历规模的坍缩 (L9 半径 448 格外接盒里通常只有 0-3 只盖章精英, 而不是刷怪塔/农场里成百的普通生物);
        // 快照构建 / 分级解密 / 是否可封仍全部在 AgentSealSeam.buildScanSnapshot 背后裁决, 本层不做任何精英
        // 语义之外的判断。
        List<LivingEntity> candidates = level.getEntitiesOfClass(LivingEntity.class,
                sender.getBoundingBox().inflate(radius),
                candidate -> candidate != sender && candidate.isAlive()
                        && MiningChampions.isChampion(candidate));
        candidates.sort(Comparator.comparingDouble((LivingEntity candidate) -> sender.distanceToSqr(candidate)));

        double radiusSqr = (double) radius * (double) radius;
        List<ScanTarget> targets = new ArrayList<>();
        boolean truncated = false;
        long discoveryRawXp = 0L;
        int discoveryCount = 0;
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
                    candidate.getUUID(),
                    Math.sqrt(distanceSqr),
                    EntityType.getKey(candidate.getType()).toString(),
                    candidate.getType().getDescriptionId(),
                    candidate.blockPosition().getX(),
                    candidate.blockPosition().getY(),
                    candidate.blockPosition().getZ(),
                    snapshot,
                    bountyFacts(candidate)));
            // 8.1 首次发现经验: 只算真正进了快照的目标 (被硬上限截掉的没"找到", 不给)。去重 / 召唤物排除 /
            // 持久化口径见 AgentDiscoveryXp; 这里只累加, 循环结束后一次入账。
            long claimed = AgentDiscoveryXp.claim(candidate, MiningChampions.get(candidate).orElse(null),
                    sender.getUUID());
            if (claimed > 0L) {
                discoveryRawXp += claimed;
                discoveryCount++;
            }
        }

        ScanPulse pulse = new ScanPulse(now, agentLevel, cooldownTicks, radius,
                isCrossChunk(agentLevel), truncated, List.copyOf(targets));
        PULSES.put(sender.getUUID(), pulse);

        // L8 逐玩家高亮 (只有本人看得见, 实现见 AgentScanGlow): 生命期与快照同长。低于 L8 的脉冲也要先熄一次旧的
        // —— 正常流程里旧快照必然已到期 (CD 与快照同长), 但跨存档时钟回退等脏记录路径下不能指望这一点。
        boolean glowing = AgentScanSnapshotBuilder.highlightsTargets(agentLevel);
        if (glowing) {
            List<AgentScanGlow.Target> glowTargets = new ArrayList<>(targets.size());
            for (ScanTarget target : targets) {
                glowTargets.add(new AgentScanGlow.Target(target.networkId(), target.entityUuid()));
            }
            AgentScanGlow.start(sender, glowTargets, now, now + cooldownTicks);
        } else {
            AgentScanGlow.clear(sender);
        }

        // 首次发现经验一次入账 (不受入职标志约束, 理由见 AgentDiscoveryXp); 回执报的是经每日衰减折算后
        // 实际入账的有效经验, 与玩家经验条上真涨的数一致。
        long discoveryXp = discoveryRawXp > 0L ? AgentLevels.grantRawXp(sender, discoveryRawXp) : 0L;

        // 刻意不在这里 markActiveAgent。该标志是加强奖励与对精英伤害放大的唯一资格门, 且一经置位永久保留;
        // 置位点只有"接取悬赏成功"与"封印成功"两处。扫描对全员开放且职业等级默认 1 级, 在此置位等于把入职门槛
        // 降成"站在精英旁边点一次按钮", 特勤专属福利就漏给了全服每一个打精英的人 —— 那正是 AgentBountySavedData
        // 立这个标志要防的事。L1 想入职, 接一张日常悬赏即可 (2026-09-30 拍板)。
        result.addProperty("scanOnline", true);
        result.addProperty("truncated", truncated);
        result.addProperty("scanCooldownRemainingTicks", (long) cooldownTicks);
        result.addProperty("snapshotRemainingTicks", (long) cooldownTicks);
        result.addProperty("glowingHighlight", glowing);
        result.addProperty("discoveryXp", discoveryXp);
        result.addProperty("discoveryCount", discoveryCount);
        result.add("targets", targetsJson(pulse, sender));
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
        // 高亮会话与快照同源同长, 一起拨, 否则测试里"快照到期"与"高亮到期"会被拆成两个时刻。
        AgentScanGlow.rewindForTest(playerId, deltaTicks);
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

    /**
     * 快照目标表。数值格 (有效血 / 减伤 / 子弹抗性 / 攻击移速 / 技能时序 / 品质) 直接取快照里构建层已裁决好的值,
     * 未解锁即 JSON null; 实时透视 (live) 对 L9+ 脉冲在此刻重读, 同样经构建层裁决。本层不做任何等级判断。
     *
     * @param viewer 读回执的干员 (实时透视在其当前维度里按网络 id + UUID 回查目标, 并读"叠在你身上"的 DoT 层数)
     */
    private static JsonArray targetsJson(ScanPulse pulse, ServerPlayer viewer) {
        JsonArray array = new JsonArray();
        if (pulse == null) {
            return array;
        }
        boolean positionUnlocked = AgentScanTier.canDecrypt(pulse.agentLevel(), AgentScanField.GLOWING_HIGHLIGHT);
        boolean refreshesLive = AgentScanSnapshotBuilder.refreshesLive(pulse.agentLevel());
        boolean radarUnlocked = AgentScanTier.canDecrypt(pulse.agentLevel(), AgentScanField.BOUNTY_RADAR);
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
            // 悬赏雷达 (第四章 L6): 该目标此刻能不能推进读者已接的悬赏 (或 L8+ 的世界 BOSS 讨伐令)。判据用脉冲当刻
            // 记下的目标事实, 对照的是读者<b>现在</b>的悬赏板 —— 扫完再接的悬赏也会亮。未解锁发 JSON null。
            if (radarUnlocked) {
                json.addProperty("bountyTarget", target.bountyFacts() != null
                        && AgentBountyService.isBountyTarget(viewer, target.bountyFacts()));
            } else {
                json.add("bountyTarget", JsonNull.INSTANCE);
            }
            intelJson(target.snapshot().intel(), json);
            if (refreshesLive) {
                AgentScanLive live = AgentScanSnapshotBuilder.buildLive(pulse.agentLevel(), readLive(viewer, target));
                json.add("live", liveJson(live));
            } else {
                json.add("live", JsonNull.INSTANCE); // L9 以下: 实时透视整格加密。
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
     * 目标对悬赏有意义的事实 (悬赏雷达用), 脉冲当刻记下: 初始星级、本来带的词条池 (仍挂着的 + 正被封印摘走的,
     * 与击杀结算同口径)、是否世界 BOSS。qualified 恒 true —— 雷达回答的是"打它算不算", 入池门槛要到击杀时才知道。
     * 雷达 L6 才解锁, 那时词条早已全解密 (L5), 池集合不泄漏任何面板上看不到的东西。
     */
    private static BountyKill bountyFacts(LivingEntity candidate) {
        MiningChampionData champ = MiningChampions.get(candidate).orElse(null);
        if (champ == null || !champ.isChampion()) {
            return null;
        }
        EnumSet<AffixDef> affixes = EnumSet.noneOf(AffixDef.class);
        affixes.addAll(champ.affixes().keySet());
        affixes.addAll(champ.sealedAffixes().keySet());
        return new BountyKill(champ.star(), BountyKill.poolsOf(affixes), champ.isWorldBoss(), true);
    }

    /**
     * 回查快照目标并重读实时原料。三道门缺一不可: 目标必须在读者<b>当前</b>维度里查得到 (没加载 = 读不到, 不跨维度
     * 找)、UUID 必须与脉冲当刻一致 (网络 id 会被复用)、必须仍是活体。任一不满足返 null, 构建层据此报 tracked=false。
     */
    private static AgentScanSnapshotBuilder.RawLive readLive(ServerPlayer viewer, ScanTarget target) {
        Entity entity = viewer.serverLevel().getEntity(target.networkId());
        if (!(entity instanceof LivingEntity living) || !living.getUUID().equals(target.entityUuid())
                || !living.isAlive()) {
            return null;
        }
        return AgentSealSeam.readLive(viewer, living);
    }

    /**
     * 数值情报格 (L3-L7), 平铺在目标行上。每格未解锁即 JSON null (快照里就是 null; Gson serializeNulls 保证键在)。
     * 技能时序的子项与其它格不同: 子项缺失表示"这条技能没有这个概念" (如瞬发技能没有蓄力), 整组已随 L7 解锁, 故
     * 用缺键而不是 null 表达, 以免与"加密"混淆。
     */
    private static void intelJson(AgentScanIntel intel, JsonObject json) {
        json.addProperty("effectiveHp", intel.effectiveHp());
        json.addProperty("armor", intel.armor());
        json.addProperty("damageReductionPct", intel.damageReductionPct());
        json.addProperty("bulletResistancePct", intel.bulletResistancePct());
        json.addProperty("attackDamage", intel.attackDamage());
        json.addProperty("singleHitPct", intel.singleHitPct());
        json.addProperty("movementSpeed", intel.movementSpeed());
        if (intel.mechanics() == null) {
            json.add("mechanics", JsonNull.INSTANCE);
            return;
        }
        JsonArray mechanics = new JsonArray();
        for (AgentScanIntel.Mechanic mechanic : intel.mechanics()) {
            JsonObject row = new JsonObject();
            row.addProperty("affixId", mechanic.affixId());
            if (mechanic.chargeSeconds() != null) {
                row.addProperty("chargeSeconds", mechanic.chargeSeconds());
            }
            if (mechanic.interruptDamagePerPlayer() != null) {
                row.addProperty("interruptDamagePerPlayer", mechanic.interruptDamagePerPlayer());
            }
            if (mechanic.cooldownSeconds() != null) {
                row.addProperty("cooldownSeconds", mechanic.cooldownSeconds());
            }
            mechanics.add(row);
        }
        json.add("mechanics", mechanics);
    }

    /** 实时透视一格。tracked=false 时其余全为 null (读不到就不发旧值); attributes 仅 L10 非 null。 */
    private static JsonObject liveJson(AgentScanLive live) {
        JsonObject json = new JsonObject();
        json.addProperty("tracked", live.tracked());
        json.addProperty("currentHp", live.currentHp());
        json.addProperty("maxHp", live.maxHp());
        json.addProperty("absorption", live.absorption());
        json.addProperty("frostStacksOnYou", live.frostStacksOnYou());
        json.addProperty("burningStacksOnYou", live.burningStacksOnYou());
        AgentScanLive.Attributes attributes = live.attributes();
        if (attributes == null) {
            json.add("attributes", JsonNull.INSTANCE);
        } else {
            JsonObject attributesJson = new JsonObject();
            attributesJson.addProperty("effectiveHp", attributes.effectiveHp());
            attributesJson.addProperty("armor", attributes.armor());
            attributesJson.addProperty("damageReductionPct", attributes.damageReductionPct());
            attributesJson.addProperty("bulletResistancePct", attributes.bulletResistancePct());
            attributesJson.addProperty("attackDamage", attributes.attackDamage());
            attributesJson.addProperty("singleHitPct", attributes.singleHitPct());
            attributesJson.addProperty("movementSpeed", attributes.movementSpeed());
            json.add("attributes", attributesJson);
        }
        return json;
    }

    /**
     * 一条词条。
     *
     * 未解密条目连 affixId 与 category 都不下发: 快照 record 里它们已在构建层脱敏为空串 (双保险的第一层,
     * 见 {@link com.miningdim.job.agent.panel.AgentScanSnapshotBuilder}), 本层再按 {@code decrypted} 发
     * JSON null 是第二层、更严格的脱敏 —— 未解密条目哪怕真名已在上游变成空串, 这里也不把那个空串当真值
     * 下发, 而是整键置 null。affixId 的线上格式是 {@code AffixDef} 枚举名 (如 {@code BURNING}), 不是
     * {@code namespace:path} 注册名; 把它明码发进 CEF, 等于在开发者工具里给出词条身份, 整条分级解密就白做
     * 了。空串同样不行 (那是一个可以被当成 id 的值), 发 JSON null。
     */
    private static JsonObject entryJson(AgentScanEntry entry) {
        JsonObject json = new JsonObject();
        if (entry.decrypted()) {
            json.addProperty("affixId", entry.affixId());
            json.addProperty("displayKey", entry.displayKey());
            json.addProperty("category", entry.category().name());
            // L8 "全品质表": 构建层未解锁时已置 null, 这里原样发 (null 即加密)。
            json.addProperty("quality", entry.quality());
        } else {
            json.add("affixId", JsonNull.INSTANCE);
            json.add("displayKey", JsonNull.INSTANCE);
            json.add("category", JsonNull.INSTANCE);
            json.add("quality", JsonNull.INSTANCE);
        }
        json.addProperty("decrypted", entry.decrypted());
        json.addProperty("sealable", entry.sealable());
        json.addProperty("sealed", entry.sealed());
        return json;
    }
}
