package com.miningdim.job.agent;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 特勤悬赏进度 + 周青辉石产出软上限持久层 (SpecialAgent_Job_DesignSpec 10.5 悬赏 + 7.2/十一章青辉石周产门控)。
 * 挂在 overworld 的 DimensionDataStorage, 文件名 {@value #DATA_NAME} (范式对齐 {@link com.miningdim.job.munitions.MunitionsSavedData}:
 * 1.20.1 computeIfAbsent 三参签名, 写后必 setDirty)。
 *
 * 为何 SavedData 而非并入 JobProgress capability (调研范式裁决): 悬赏是"周常世界事实"+ 青辉石周产是"防超发的
 * 世界计数", 非死亡/重生应保留的玩家经验进度态, 语义上属世界放置事实 (与军火台计数同范式), 用 SavedData 更贴切。
 *
 * 三类状态 (按 ownerUUID 键):
 *  (1) 悬赏进度: 玩家已接悬赏的 {@link BountyProgress} (合格击杀计数 + 已发奖标记 + 周期戳); 跨 UTC 翻日/ISO 翻周
 *      由 {@link BountyProgress#rolloverIfStale} 按 {@link AgentClock} 戳重置。本任务交付青辉石周产计数器持久层 +
 *      接口; 具体每玩家多槽悬赏实例的持久化序列化属 b 阶段悬赏面板接线 (留 deferred, 见交付报告)。
 *  (2) 青辉石周产计数: 缺口 A (调研: 全工程无青辉石周产软上限实装, IEconomyService 仅裸 grant(AZURE))。本类维护
 *      每玩家 (本周已发青辉石量 + 周戳); 跨 ISO 周 ({@link AgentClock#currentUtcWeekStamp}) 清零。周常悬赏发青辉石
 *      前先 {@link #tryGrantWeeklyAzure} 门控, 超本周软上限则不发 (防超发; 经济层裸 grant 无此闸)。
 *  (3) 入职标志 activeAgents: "该玩家做过特勤工作"的持久化布尔标志集 (按 UUID)。特勤专属福利 (加强奖励 / 对精英
 *      伤害放大) 仅对此集合内玩家发放。为何不用 IJobService.level/totalXp 作门: 框架 level 对【任何】玩家恒返 1 级
 *      默认 (未挂载新人也返 1), 用等级作门会把特勤专属福利泄漏给全服每个打到精英的玩家。入职标志是直接的"做过工作"
 *      事实, 不存经验 (用户定: 做过工作才吃, 但不存储经验), 玩家真正执行任一特勤活计 (如封印申请成功) 时置位。
 *      非死亡/重生应保留的世界放置事实, 与悬赏/周产同范式落 SavedData (champions-free, 纯 UUID 标志)。
 *
 * 线程: 仅服务端主线程读写。1.20.1 SavedData.Factory 是 1.20.2+ 不可用, 用三参 computeIfAbsent(load,create,name)。
 *
 * champions-free: 本类只持 UUID/long/NBT, 不触 Champions, dev 安全 (周产门控逻辑可 GameTest 直测)。
 */
public final class AgentBountySavedData extends SavedData {

    /** DimensionDataStorage 数据文件名。 */
    public static final String DATA_NAME = "miningdim_agent_bounty";

    /**
     * 每玩家每周青辉石产出软上限 (7.2/十一章: 青辉石仅周常悬赏出且 PvE 绑定, 须防周内超发)。
     * 与 {@code ChampionReward.azureDrop} 量级对齐: 单只 10★ 周常目标 ≈10 青辉石, 一周封顶约相当于完成数个高星
     * 周常悬赏的累计上限 (config 暴露前唯一权威硬值; 一旦 economy config 暴露 agent.azure.weeklyCap 应改读配置)。
     */
    public static final long WEEKLY_AZURE_SOFT_CAP = 50L;

    private static final String K_AZURE = "weeklyAzure";
    private static final String K_UUID = "uuid";
    private static final String K_AMOUNT = "amount";
    private static final String K_WEEK_STAMP = "weekStamp";
    private static final String K_ACTIVE_AGENTS = "activeAgents";

    /** 玩家 UUID -> 本周青辉石产出计数 (含周戳, 跨周清零)。 */
    private final Map<UUID, WeeklyAzure> weeklyAzure = new HashMap<>();

    /** 做过特勤工作的玩家 UUID 集 (入职标志; 一旦置位永久保留, 不随死亡/翻日/翻周清空)。 */
    private final Set<UUID> activeAgents = new HashSet<>();

    /** 单玩家本周青辉石产出计数 (本周已发量 + ISO 周戳; 跨周翻转清零)。 */
    private static final class WeeklyAzure {
        long granted;
        long weekStamp;

        WeeklyAzure(long granted, long weekStamp) {
            this.granted = granted;
            this.weekStamp = weekStamp;
        }
    }

    public AgentBountySavedData() {
    }

    /** 取/建 overworld 的特勤悬赏持久数据。务必传 overworld 的 ServerLevel。 */
    public static AgentBountySavedData get(ServerLevel overworld) {
        return overworld.getDataStorage().computeIfAbsent(
                AgentBountySavedData::load, AgentBountySavedData::new, DATA_NAME);
    }

    /**
     * 周常悬赏发青辉石前门控 (缺口 A 自实现): 当前 ISO 周内该玩家累计产出 + 本次 amount 未超
     * {@link #WEEKLY_AZURE_SOFT_CAP} 才记账放行 (返本次实发量); 超上限则只发到撞顶的剩余额度 (软上限语义: 发到
     * 顶为止, 非整笔拒绝)。跨周自动清零 (按 {@code currentWeekStamp} 与记录周戳比对)。
     *
     * @param playerId        发青辉石的玩家 UUID
     * @param amount          本次拟发青辉石量 (必须 &gt; 0)
     * @param currentWeekStamp 当前 ISO 周戳 ({@link AgentClock#currentUtcWeekStamp})
     * @return 本次周软上限放行的青辉石量 (0 = 本周已撞顶, 不发; &gt;0 = 周门控放行量, 调用方据此再经
     *         grantAzureDaily 并入每人每日青辉石产出硬上限 — 日+周双轴)
     */
    public long tryGrantWeeklyAzure(UUID playerId, long amount, long currentWeekStamp) {
        if (amount <= 0L) {
            throw new IllegalArgumentException("azure grant amount must be > 0, got " + amount);
        }
        WeeklyAzure rec = weeklyAzure.get(playerId);
        if (rec == null || rec.weekStamp != currentWeekStamp) {
            rec = new WeeklyAzure(0L, currentWeekStamp); // 无记录 / 跨周: 本周从 0 起。
            weeklyAzure.put(playerId, rec);
        }
        long remaining = WEEKLY_AZURE_SOFT_CAP - rec.granted;
        if (remaining <= 0L) {
            return 0L; // 本周已撞顶。
        }
        long grantable = Math.min(amount, remaining);
        rec.granted += grantable;
        setDirty();
        return grantable;
    }

    /** 某玩家本周已产青辉石量 (跨周或无记录返 0; 诊断/测试用)。 */
    public long weeklyAzureGranted(UUID playerId, long currentWeekStamp) {
        WeeklyAzure rec = weeklyAzure.get(playerId);
        if (rec == null || rec.weekStamp != currentWeekStamp) {
            return 0L;
        }
        return rec.granted;
    }

    /**
     * 置位某玩家的入职标志 (玩家真正执行任一特勤活计时调用, 如封印申请成功)。一旦置位永久保留 (用户定: 做过工作
     * 才吃福利, 不存经验); 仅首次置位时 {@code setDirty} (幂等, 已置位再调不重复落盘)。
     *
     * @param playerId 执行了特勤活计的玩家 UUID
     * @return 是否为首次置位 (true = 本次新增; false = 该玩家此前已是 activeAgent)
     */
    public boolean markActiveAgent(UUID playerId) {
        if (playerId == null) {
            throw new IllegalArgumentException("playerId must not be null");
        }
        boolean added = activeAgents.add(playerId);
        if (added) {
            setDirty();
        }
        return added;
    }

    /**
     * 某玩家是否做过特勤工作 (入职标志; 特勤专属福利门控)。从未执行过特勤活计的玩家返 false ——
     * 加强奖励 / 对精英伤害放大据此不发, 杜绝按等级默认 1 级泄漏给全服。
     */
    public boolean isActiveAgent(UUID playerId) {
        return activeAgents.contains(playerId);
    }

    @Override
    public CompoundTag save(CompoundTag tag) {
        ListTag list = new ListTag();
        for (Map.Entry<UUID, WeeklyAzure> e : weeklyAzure.entrySet()) {
            CompoundTag entry = new CompoundTag();
            entry.putUUID(K_UUID, e.getKey());
            entry.putLong(K_AMOUNT, e.getValue().granted);
            entry.putLong(K_WEEK_STAMP, e.getValue().weekStamp);
            list.add(entry);
        }
        tag.put(K_AZURE, list);

        ListTag agents = new ListTag();
        for (UUID id : activeAgents) {
            CompoundTag entry = new CompoundTag();
            entry.putUUID(K_UUID, id);
            agents.add(entry);
        }
        tag.put(K_ACTIVE_AGENTS, agents);
        return tag;
    }

    public static AgentBountySavedData load(CompoundTag tag) {
        AgentBountySavedData data = new AgentBountySavedData();
        if (tag.contains(K_AZURE)) {
            ListTag list = tag.getList(K_AZURE, Tag.TAG_COMPOUND);
            for (int i = 0; i < list.size(); i++) {
                CompoundTag entry = list.getCompound(i);
                if (entry.hasUUID(K_UUID)) {
                    data.weeklyAzure.put(entry.getUUID(K_UUID),
                            new WeeklyAzure(entry.getLong(K_AMOUNT), entry.getLong(K_WEEK_STAMP)));
                }
            }
        }
        if (tag.contains(K_ACTIVE_AGENTS)) {
            ListTag agents = tag.getList(K_ACTIVE_AGENTS, Tag.TAG_COMPOUND);
            for (int i = 0; i < agents.size(); i++) {
                CompoundTag entry = agents.getCompound(i);
                if (entry.hasUUID(K_UUID)) {
                    data.activeAgents.add(entry.getUUID(K_UUID));
                }
            }
        }
        return data;
    }
}
