package com.miningdim.job;

import net.minecraft.nbt.CompoundTag;

import java.util.EnumMap;
import java.util.Map;

/**
 * 玩家全职业进度持有者 (JobFramework_Shared_Foundation_DesignSpec 第 2.2 节): 一个
 * {@code EnumMap<JobId,JobProgress>}, 一处遍历 serialize/deserialize/copyFrom。
 *
 * 新职业 = Map 多一个 key, 零结构改动 (第 2.2 节)。本类按需懒建 JobProgress: 首次取某职业进度时
 * 若 Map 无该 key 则建一份默认 (level=1/xp=0) 放入并返回, 保证 {@link #jobProgress(JobId)} 永不返回 null
 * (调用方可直接 grant/读级, 与 capability "未挂载返回 empty" 是两层语义)。
 *
 * 框架 spec 第 2.3 节已落地: 本数据作为 {@code entry.MiningPlayerData} 的内部委派 (单一权威玩家 capability
 * 持本 EnumMap), 不再另挂第二套 job 玩家 capability。本类仍是纯数据 (无世界/事件/Provider 引用), 序列化挂
 * entry capability 的 {@code jobs} 子标签, Clone 复制由 entry 的 PlayerEvent.Clone 经 copyFrom 全量复制。
 *
 * 线程: 玩家进度只在主线程读写 (玩家事件/tick/命令均主线程), 无需并发保护 (与 entry.MiningPlayerData 一致)。
 */
public final class JobData {

    private final Map<JobId, JobProgress> progress = new EnumMap<>(JobId.class);

    /**
     * 取某职业进度, 不存在则懒建默认并放入 (永不返回 null)。grant/读级/读进度统一入口。
     */
    public JobProgress jobProgress(JobId job) {
        return progress.computeIfAbsent(job, k -> new JobProgress());
    }

    /** 全字段拷入本对象 (PlayerEvent.Clone 复制: 全部职业进度跨死亡/换维度保留, 第 2.4 节)。 */
    public void copyFrom(JobData other) {
        progress.clear();
        for (Map.Entry<JobId, JobProgress> e : other.progress.entrySet()) {
            JobProgress copy = new JobProgress();
            copy.copyFrom(e.getValue());
            progress.put(e.getKey(), copy);
        }
    }

    // ---- 持久化: 一处遍历 Map (第 2.2 节) ----
    // 存档形态: 每职业以其小写 id 为子标签键, 值是该职业 JobProgress 的 CompoundTag。
    // deserialize 对缺键职业不建条目 (取用时懒建默认), 旧存档/新职业天然向后兼容。

    public CompoundTag serializeNBT() {
        CompoundTag tag = new CompoundTag();
        for (Map.Entry<JobId, JobProgress> e : progress.entrySet()) {
            tag.put(e.getKey().id(), e.getValue().serializeNBT());
        }
        return tag;
    }

    public void deserializeNBT(CompoundTag tag) {
        progress.clear();
        for (JobId job : JobId.values()) {
            if (tag.contains(job.id())) {
                JobProgress jp = new JobProgress();
                jp.deserializeNBT(tag.getCompound(job.id()));
                progress.put(job, jp);
            }
        }
    }
}
