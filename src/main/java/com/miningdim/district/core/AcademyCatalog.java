package com.miningdim.district.core;

import java.util.List;

/**
 * 开服六校 (服主已拍板; 红冬不开, 主城 DU 不设自管区)。开服时按顺序 INSERT OR IGNORE 进 district_academy,
 * 不覆盖已有行; 学院改名或新增目前只能改这里 (设计文档 TODO 21.8)。
 */
public final class AcademyCatalog {

    private AcademyCatalog() {
    }

    public static final List<Academy> LAUNCH = List.of(
            new Academy("abydos", "阿拜多斯", "阿拜多斯学院", 1),
            new Academy("millennium", "千年", "千年学院", 2),
            new Academy("gehenna", "格赫娜", "格赫娜学院", 3),
            new Academy("trinity", "圣三一", "圣三一学院", 4),
            new Academy("hyakkiyako", "百鬼夜行", "百鬼夜行学院", 5),
            new Academy("wildhunt", "狂猎", "狂猎艺术学院", 6));
}
