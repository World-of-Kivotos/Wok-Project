package com.miningdim.job;

/**
 * 职业身份权威枚举 (JobFramework_Shared_Foundation_DesignSpec 第 2.1 节, 经 design_mindmap.md 共享地基节
 * 与 SpecialAgent/Munitions 两份新 spec 收编为 7 个)。作为 {@link JobData} 内 EnumMap 的 key。
 *
 * 成员清单依据: design_mindmap.md "共享地基" 节明确列 7 个职业 (矿工/农夫/工程师/塔罗/厨师/特勤干员/军火商);
 * SpecialAgent_Job_DesignSpec 与 Munitions_Job_DesignSpec 均写 "并入 JobFramework 统一 EnumMap"。
 * 框架 spec 第 2.1 节文字 (5 个) 滞后于 mindmap 与两份新 spec, 本枚举以 7 成员为准 (占位前向兼容: 加成员
 * 只是 EnumMap 多一个 key, 零结构改动)。酿酒师 (BREWER) 即据此前向兼容性在原 7 职业尾部追加为第 8 个
 * (完整实现, 非占位; 尾部追加以守 {@link com.miningdim.network.JobSyncS2C} 按 values() 顺序读写的同序契约)。
 *
 * 结婚不是 JobId (框架 spec 第 2.1 节): 它是系统, 数据走 MarriageRegistry, 不进 JobProgress。
 *
 * id 字符串: 源文档未定义小写 id, 此处按 mindmap 职业名直译惯例命名 (miner/farmer/engineer/tarot/chef/
 * agent/munitions), 用于 lang key 命名空间 (框架 spec 第十章 {@code <职业>.<类别>.<名>}) 与 NBT 持久化键。
 */
public enum JobId {

    MINER("miner"),
    FARMER("farmer"),
    ENGINEER("engineer"),
    TAROT("tarot"),
    CHEF("chef"),
    /** 特勤干员 (SpecialAgent): PVE 经济/情报职业, 占位前向兼容。 */
    AGENT("agent"),
    /** 军火商 (Munitions): 弹药制造经济职业, 占位前向兼容。 */
    MUNITIONS("munitions"),
    /** 酿酒师 (Brewer): 至少七天周期的制造职业, 酿酒台酿基酒 + 酒窖箱陈酿年份 + 喝酒增益。尾部追加 (S2C 同序契约)。 */
    BREWER("brewer");

    private final String id;

    JobId(String id) {
        this.id = id;
    }

    /** 小写稳定 id (lang key / NBT 键 / 命令参数用); 与 {@link #name()} 的大写枚举名区分。 */
    public String id() {
        return id;
    }

    /**
     * 按小写 id 反查 JobId; 未知 id 返回 null (调用方据此短路或在边界报错, 不静默掩盖)。
     * 命令解析 (/job info &lt;job&gt;) 与 datapack 键映射用。
     */
    public static JobId byId(String id) {
        for (JobId job : values()) {
            if (job.id.equals(id)) {
                return job;
            }
        }
        return null;
    }
}
