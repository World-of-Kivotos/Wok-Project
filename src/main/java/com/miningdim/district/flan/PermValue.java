package com.miningdim.district.flan;

/**
 * 一格 Flan 权限的写入值: TRUE / FALSE 对应 mode 1 / 0; UNSET 对应 mode −1 (移除该键)。UNSET 只在"让地块里的全局类
 * 权限跟随自管区"时用 —— 我们管的开关一律显式写真或假, "不设置"会回退到上一层, 正是要堵死的漏。
 */
public enum PermValue {
    TRUE,
    FALSE,
    UNSET;

    public static PermValue of(boolean value) {
        return value ? TRUE : FALSE;
    }
}
