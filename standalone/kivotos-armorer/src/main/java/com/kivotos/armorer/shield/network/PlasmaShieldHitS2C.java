package com.kivotos.armorer.shield.network;

import com.kivotos.armorer.shield.PlasmaShieldVariant;
import com.kivotos.armorer.shield.PlasmaShieldVisualProfile;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/** Server-authoritative transient feedback for damage actually absorbed by a plasma shield. */
public record PlasmaShieldHitS2C(int entityId,
                                String variantId,
                                float strength,
                                boolean overloaded) {

    public static PlasmaShieldHitS2C inactive() {
        return new PlasmaShieldHitS2C(-1, "", 0.0F, false);
    }

    public static PlasmaShieldHitS2C forHit(int entityId,
                                           PlasmaShieldVariant variant,
                                           double absorbedDamage,
                                           boolean overloaded) {
        if (entityId < 0) {
            throw new IllegalArgumentException("entityId must be non-negative");
        }
        return new PlasmaShieldHitS2C(
                entityId,
                variant.id(),
                PlasmaShieldVisualProfile.strengthForAbsorbedDamage(absorbedDamage),
                overloaded);
    }

    public static void encode(PlasmaShieldHitS2C message, FriendlyByteBuf buffer) {
        PlasmaShieldHitS2C safe = message.sanitized();
        buffer.writeVarInt(safe.entityId);
        buffer.writeUtf(safe.variantId, 32);
        buffer.writeFloat(safe.strength);
        buffer.writeBoolean(safe.overloaded);
    }

    public static PlasmaShieldHitS2C decode(FriendlyByteBuf buffer) {
        return new PlasmaShieldHitS2C(
                buffer.readVarInt(),
                buffer.readUtf(32),
                buffer.readFloat(),
                buffer.readBoolean()).sanitized();
    }

    public static void handle(PlasmaShieldHitS2C message,
                              Supplier<NetworkEvent.Context> contextSupplier) {
        NetworkEvent.Context context = contextSupplier.get();
        PlasmaShieldHitS2C safe = message.sanitized();
        if (safe.active()) {
            context.enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
                    () -> () -> com.kivotos.armorer.shield.client.ClientPlasmaShieldHitEffects.accept(
                            safe.entityId, safe.variantId, safe.strength, safe.overloaded)));
        }
        context.setPacketHandled(true);
    }

    public boolean active() {
        return entityId >= 0 && !variantId.isEmpty() && strength > 0.0F;
    }

    public PlasmaShieldHitS2C sanitized() {
        if (entityId < 0
                || PlasmaShieldVariant.fromId(variantId).isEmpty()
                || !Float.isFinite(strength)
                || strength <= 0.0F) {
            return inactive();
        }
        return new PlasmaShieldHitS2C(
                entityId,
                PlasmaShieldVariant.fromId(variantId).orElseThrow().id(),
                Math.min(1.0F, strength),
                overloaded);
    }
}

