package com.miningdim.job;

/**
 * 玩家职业数据门面 (JobFramework_Shared_Foundation_DesignSpec 第 2.2 节)。capability 拥有者
 * ({@link JobFrameworkSystem}) 提供本接口, 跨子系统经此读写玩家全职业进度, 不 import 实现类
 * {@link JobPlayerData} (模块化铁律 2)。
 *
 * 第 2.2 节明确: 扩单一方法 {@code jobProgress(JobId)} 取代 "每职业一组 getter/setter"; 新职业零结构改动。
 */
public interface IJobPlayerData {

    /** 取某职业进度 (永不返回 null, 缺则懒建默认 level=1/xp=0)。grant/读级/读进度统一入口。 */
    JobProgress jobProgress(JobId job);

    /** 取底层全职业数据 (序列化/Clone 复制专用)。 */
    JobData jobData();
}
