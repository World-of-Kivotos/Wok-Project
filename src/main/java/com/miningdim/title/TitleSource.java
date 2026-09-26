package com.miningdim.title;

import java.util.Locale;

/**
 * 称号来源。称号系统只记录来源, 不关心调用方的业务 (Title_System_DesignSpec 第一章第 5 条)。
 * 新增来源时在末尾追加枚举值; 已落库的 {@link #id()} 严禁改名, 否则历史行会读不回来。
 */
public enum TitleSource {
    ACHIEVEMENT,
    POINT_SHOP,
    MARRIAGE,
    EVENT,
    ADMIN;

    /** 写入 title_owned.source 的小写 id (achievement / point_shop / marriage / event / admin)。 */
    public String id() {
        return name().toLowerCase(Locale.ROOT);
    }
}
