package com.miningdim.job.engineer;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.util.RandomSource;

/**
 * 纳米校准 QTE 服务端时序权威 (MillenniumEngineer_Mod_DesignSpec 4.2)。全工程最重一处: 扫描条时序 / 绿区
 * 随机落点 / 命中判定全在服务端; 客户端只渲染游标 + 回传 "我点了" tick, 服务端判窗口内才算命中 (服务端权威 C5)。
 *
 * 进度不随时间自动涨 (反挂机): 不点 -> 进度不动; 命中绿区 -> 大幅推进 + 品质命中数 ++; 未中 (点了但游标不在
 * 绿区) -> 进度推一点点 + 品质不涨。绿区每轮 (游标走完一趟翻转方向) 随机重置落点, 防固定连点器。
 *
 * 品质 (累计命中数 qualityHits) 结算影响 (4.2): 产量 (达阈值有概率 +1 板) / 特效概率 (高档起越高越易掷) /
 * 原始经验 (越高该次生产原始经验越高)。本类只维护时序与品质条; 产量/特效/经验结算由 {@link NanoProduction}/
 * {@link NanoRepair} 读 qualityHits。
 *
 * 纯逻辑 + 注入 RandomSource (绿区落点随机), 无世界引用, 可 GameTest 用固定种子断言确定结果。
 */
public final class NanoCalibration {

    /** 游标位置 (逻辑刻度, [0, barWidth))。 */
    private int cursor;
    /** 游标方向 (+1 右移 / -1 左移); 到边界翻转并重随机绿区。 */
    private int direction = 1;
    /** 当前绿区起点 (逻辑刻度); 绿区 = [greenStart, greenStart + greenWidth)。 */
    private int greenStart;
    /** 累计生产进度 (达 progressGoal 即完成一次生产)。 */
    private int progress;
    /** 累计命中绿区次数 (品质条; 越高产量/特效/经验越好)。 */
    private int qualityHits;
    /** 本轮生产是否进行中 (选档后置 true, 取出/换档后由 BE 重置)。 */
    private boolean active;

    public boolean isActive() {
        return active;
    }

    public int cursor() {
        return cursor;
    }

    public int greenStart() {
        return greenStart;
    }

    public int progress() {
        return progress;
    }

    public int qualityHits() {
        return qualityHits;
    }

    /** 当前游标是否落在绿区内 (命中判定核心; 服务端权威)。 */
    public boolean cursorInGreen() {
        int w = EngineerConfig.CALIBRATION_GREEN_WIDTH.get();
        return cursor >= greenStart && cursor < greenStart + w;
    }

    /** 开始一轮生产: 重置进度/品质/游标, 随机首个绿区落点。 */
    public void begin(RandomSource random) {
        this.active = true;
        this.progress = 0;
        this.qualityHits = 0;
        this.cursor = 0;
        this.direction = 1;
        randomizeGreen(random);
    }

    /** 结束本轮 (取出产物 / 换档 / 矿石不足): 清状态。 */
    public void reset() {
        this.active = false;
        this.progress = 0;
        this.qualityHits = 0;
        this.cursor = 0;
        this.direction = 1;
    }

    /**
     * 服务端每 tick 推进游标 (扫描条移动)。到边界翻转方向并重随机绿区落点 (防固定连点器)。
     * 进度不在此涨 —— 进度只由 {@link #onClick} 推进 (反挂机: 不点不动)。
     */
    public void serverTick(RandomSource random) {
        if (!active) {
            return;
        }
        int width = EngineerConfig.CALIBRATION_BAR_WIDTH.get();
        int speed = EngineerConfig.CALIBRATION_CURSOR_SPEED.get();
        cursor += direction * speed;
        if (cursor <= 0) {
            cursor = 0;
            direction = 1;
            randomizeGreen(random);
        } else if (cursor >= width - 1) {
            cursor = width - 1;
            direction = -1;
            randomizeGreen(random);
        }
    }

    /**
     * 处理客户端回传的一次点击 (服务端判窗口内才算命中)。
     * 命中绿区 -> 进度 += hitProgress + qualityHits++; 未中 -> 进度 += missProgress (品质不涨)。
     *
     * @return 本次点击后是否已完成一次生产 (progress >= goal)
     */
    public boolean onClick() {
        if (!active) {
            return false;
        }
        if (cursorInGreen()) {
            progress += EngineerConfig.CALIBRATION_HIT_PROGRESS.get();
            qualityHits++;
        } else {
            progress += EngineerConfig.CALIBRATION_MISS_PROGRESS.get();
        }
        return progress >= EngineerConfig.CALIBRATION_PROGRESS_GOAL.get();
    }

    /** 绿区随机落点 (留出绿区宽度避免越界)。 */
    private void randomizeGreen(RandomSource random) {
        int width = EngineerConfig.CALIBRATION_BAR_WIDTH.get();
        int greenWidth = EngineerConfig.CALIBRATION_GREEN_WIDTH.get();
        int max = Math.max(1, width - greenWidth);
        this.greenStart = random.nextInt(max);
    }

    // ---- 持久化 (BE saveAdditional/load; 防重载丢失进行中生产) ----

    private static final String K_CURSOR = "Cursor";
    private static final String K_DIR = "Dir";
    private static final String K_GREEN = "Green";
    private static final String K_PROGRESS = "Progress";
    private static final String K_QUALITY = "Quality";
    private static final String K_ACTIVE = "Active";

    public CompoundTag serializeNBT() {
        CompoundTag tag = new CompoundTag();
        tag.putInt(K_CURSOR, cursor);
        tag.putInt(K_DIR, direction);
        tag.putInt(K_GREEN, greenStart);
        tag.putInt(K_PROGRESS, progress);
        tag.putInt(K_QUALITY, qualityHits);
        tag.putBoolean(K_ACTIVE, active);
        return tag;
    }

    public void deserializeNBT(CompoundTag tag) {
        this.cursor = tag.getInt(K_CURSOR);
        this.direction = tag.contains(K_DIR) ? tag.getInt(K_DIR) : 1;
        this.greenStart = tag.getInt(K_GREEN);
        this.progress = tag.getInt(K_PROGRESS);
        this.qualityHits = tag.getInt(K_QUALITY);
        this.active = tag.getBoolean(K_ACTIVE);
    }
}
