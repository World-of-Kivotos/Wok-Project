package com.miningdim.power.compat.jade;

import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

import java.util.Locale;

/** 枚举值按稳定翻译键呈现，避免把服务端枚举名直接暴露给客户端。 */
final class PowerJadeText {

    private PowerJadeText() {
    }

    static Component enumValue(String keyPrefix, String enumName) {
        return Component.translatable(keyPrefix + "." + enumName.toLowerCase(Locale.ROOT));
    }

    static Component enumList(String keyPrefix, String serializedNames) {
        if (serializedNames.isEmpty()) {
            return Component.empty();
        }
        String[] names = serializedNames.split(",");
        MutableComponent result = Component.empty();
        for (int index = 0; index < names.length; index++) {
            if (index > 0) {
                result.append(", ");
            }
            result.append(enumValue(keyPrefix, names[index]));
        }
        return result;
    }

    static String oneDecimal(double value) {
        return String.format(Locale.ROOT, "%.1f", value);
    }
}
