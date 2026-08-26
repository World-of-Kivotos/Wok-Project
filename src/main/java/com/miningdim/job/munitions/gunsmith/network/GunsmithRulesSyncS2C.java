package com.miningdim.job.munitions.gunsmith.network;

import com.miningdim.job.munitions.gunsmith.GunsmithComponentRule;
import com.miningdim.job.munitions.gunsmith.GunsmithComponentRules;
import com.miningdim.job.munitions.gunsmith.GunsmithPartVariant;
import com.miningdim.job.munitions.client.GunsmithRulesClient;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.EnumMap;
import java.util.Map;
import java.util.function.Supplier;

/** 服务端在登录及 datapack 热重载后下发完整组件规则快照。 */
public record GunsmithRulesSyncS2C(Map<GunsmithPartVariant, GunsmithComponentRule> rules) {

    public GunsmithRulesSyncS2C {
        rules = Map.copyOf(rules);
    }

    public static void encode(GunsmithRulesSyncS2C message, FriendlyByteBuf buf) {
        buf.writeVarInt(message.rules.size());
        for (GunsmithPartVariant variant : GunsmithPartVariant.values()) {
            GunsmithComponentRule rule = message.rules.get(variant);
            if (rule == null) {
                throw new IllegalArgumentException("Cannot sync missing gunsmith rule: " + variant.id());
            }
            buf.writeEnum(variant);
            rule.encode(buf);
        }
    }

    public static GunsmithRulesSyncS2C decode(FriendlyByteBuf buf) {
        int count = buf.readVarInt();
        if (count != GunsmithPartVariant.values().length) {
            throw new IllegalArgumentException("Invalid gunsmith rule sync count: " + count);
        }
        EnumMap<GunsmithPartVariant, GunsmithComponentRule> rules =
                new EnumMap<>(GunsmithPartVariant.class);
        for (int i = 0; i < count; i++) {
            GunsmithPartVariant variant = buf.readEnum(GunsmithPartVariant.class);
            if (rules.put(variant, GunsmithComponentRule.decode(buf)) != null) {
                throw new IllegalArgumentException("Duplicate gunsmith rule in sync: " + variant.id());
            }
        }
        return new GunsmithRulesSyncS2C(rules);
    }

    public static void handle(GunsmithRulesSyncS2C message,
                              Supplier<NetworkEvent.Context> contextSupplier) {
        NetworkEvent.Context context = contextSupplier.get();
        context.enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
                () -> () -> {
                    GunsmithComponentRules.install(message.rules);
                    GunsmithRulesClient.refreshHeldGun();
                }));
        context.setPacketHandled(true);
    }
}
