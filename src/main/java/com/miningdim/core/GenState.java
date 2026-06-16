package com.miningdim.core;

/**
 * 实例的离线生成与重置状态机 (设计文档 12.1, 此处为全文统一超集)。
 * 12.1 表给出的持久化最小集为 PENDING/GENERATING/READY/RESETTING/FAILED;
 * 任务契约额外要求 READY_FALLBACK (生成降级为可用但非理想) 与 RECYCLED (已回收待清),
 * 故本枚举取并集。子系统持久化时按 name() 存取, 反序列化遇未知名按 FAILED 兜底处理 (调用方决定)。
 *
 * 状态流转 (12.1):
 *   PENDING --工作线程开始体素生成--> GENERATING
 *   GENERATING --三阶段完成, 主线程确认--> READY (或退化 READY_FALLBACK)
 *   GENERATING --异常--> FAILED
 *   READY --重置触发, 实例已清空--> RESETTING
 *   RESETTING --区块删除+重生成完成--> READY
 *   FAILED --运维/自动重试--> PENDING
 *   任意 --空实例 GC 销毁--> RECYCLED
 */
public enum GenState {

    /** 已登记, 尚未提交生成任务。 */
    PENDING,

    /** 工作线程正在跑离线三阶段体素生成。 */
    GENERATING,

    /** 生成完成且可正常传送玩家。 */
    READY,

    /** 生成完成但走了降级路径 (如连通性修复未达理想), 仍可进入。 */
    READY_FALLBACK,

    /** 正在执行 region 级重置 (清区块+重生成)。 */
    RESETTING,

    /** 生成或重置失败, 待运维/自动重试或回收。 */
    FAILED,

    /** 实例已被空实例 GC 销毁, region 已释放, 仅余尾巴待清理。 */
    RECYCLED;

    /** 仅这两个状态可接受玩家传送 (12.1 门控)。 */
    public boolean isEnterable() {
        return this == READY || this == READY_FALLBACK;
    }

    /** 私有实例复用判定中视为"占位有效"的状态 (12.2 allocatePrivate)。 */
    public boolean isAlive() {
        return this == PENDING || this == GENERATING || this == READY
                || this == READY_FALLBACK || this == RESETTING;
    }
}
