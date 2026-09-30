package com.miningdim.district.service;

import java.util.Objects;
import java.util.UUID;

/**
 * 按名字解析玩家 (设计文档 10.2): 判定"进没进过服", 并给出规范名与 UUID。只有加住户与加朋友用它。
 */
public interface PlayerDirectory {

    /**
     * 解析结果。known = 进过服 (名字取服务端知道的写法); 否则 uuid 是按输入原样算的离线 UUID, name 就是输入。
     */
    record Resolved(UUID uuid, String name, boolean known) {

        public Resolved {
            Objects.requireNonNull(uuid, "uuid");
            Objects.requireNonNull(name, "name");
        }
    }

    /**
     * @param typed 去掉首尾空白后的输入 (调用方已校验格式)
     */
    Resolved resolve(String typed);
}
