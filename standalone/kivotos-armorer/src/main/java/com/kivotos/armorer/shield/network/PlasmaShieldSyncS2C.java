package com.kivotos.armorer.shield.network;

import com.kivotos.armorer.shield.PlasmaShieldVariant;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/** Complete server-authoritative HUD snapshot. */
public record PlasmaShieldSyncS2C(boolean active,
                                  String variantId,
                                  float shield,
                                  float maxShield,
                                  float totalEnergy,
                                  float maxTotalEnergy,
                                  float heat,
                                  float maxHeat,
                                  boolean overheated,
                                  int rechargeDelayTicks) {

    public static PlasmaShieldSyncS2C inactive() {
        return new PlasmaShieldSyncS2C(
                false, "", 0.0F, 1.0F, 0.0F, 1.0F, 0.0F, 1.0F, false, 0);
    }

    public static void encode(PlasmaShieldSyncS2C message, FriendlyByteBuf buffer) {
        PlasmaShieldSyncS2C safe = message.sanitized();
        buffer.writeBoolean(safe.active);
        buffer.writeUtf(safe.variantId, 32);
        buffer.writeFloat(safe.shield);
        buffer.writeFloat(safe.maxShield);
        buffer.writeFloat(safe.totalEnergy);
        buffer.writeFloat(safe.maxTotalEnergy);
        buffer.writeFloat(safe.heat);
        buffer.writeFloat(safe.maxHeat);
        buffer.writeBoolean(safe.overheated);
        buffer.writeVarInt(safe.rechargeDelayTicks);
    }

    public static PlasmaShieldSyncS2C decode(FriendlyByteBuf buffer) {
        return new PlasmaShieldSyncS2C(
                buffer.readBoolean(),
                buffer.readUtf(32),
                buffer.readFloat(),
                buffer.readFloat(),
                buffer.readFloat(),
                buffer.readFloat(),
                buffer.readFloat(),
                buffer.readFloat(),
                buffer.readBoolean(),
                buffer.readVarInt()).sanitized();
    }

    public static void handle(PlasmaShieldSyncS2C message,
                              Supplier<NetworkEvent.Context> contextSupplier) {
        NetworkEvent.Context context = contextSupplier.get();
        PlasmaShieldSyncS2C safe = message.sanitized();
        context.enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
                () -> () -> com.kivotos.armorer.shield.client.ClientPlasmaShieldState.update(
                        safe.active,
                        safe.variantId,
                        safe.shield,
                        safe.maxShield,
                        safe.totalEnergy,
                        safe.maxTotalEnergy,
                        safe.heat,
                        safe.maxHeat,
                        safe.overheated,
                        safe.rechargeDelayTicks)));
        context.setPacketHandled(true);
    }

    public PlasmaShieldSyncS2C sanitized() {
        if (!active || PlasmaShieldVariant.fromId(variantId).isEmpty()) {
            return inactive();
        }
        float safeMaxShield = positiveFinite(maxShield, 1.0F);
        float safeMaxTotalEnergy = positiveFinite(maxTotalEnergy, safeMaxShield);
        safeMaxTotalEnergy = Math.max(safeMaxShield, safeMaxTotalEnergy);
        float safeMaxHeat = positiveFinite(maxHeat, 1.0F);
        float safeTotalEnergy = clampFinite(totalEnergy, 0.0F, safeMaxTotalEnergy, 0.0F);
        float safeShield = Math.min(
                clampFinite(shield, 0.0F, safeMaxShield, 0.0F), safeTotalEnergy);
        return new PlasmaShieldSyncS2C(
                true,
                PlasmaShieldVariant.fromId(variantId).orElseThrow().id(),
                safeShield,
                safeMaxShield,
                safeTotalEnergy,
                safeMaxTotalEnergy,
                clampFinite(heat, 0.0F, safeMaxHeat, 0.0F),
                safeMaxHeat,
                overheated,
                Math.max(0, rechargeDelayTicks));
    }

    private static float positiveFinite(float value, float fallback) {
        return Float.isFinite(value) && value > 0.0F ? value : fallback;
    }

    private static float clampFinite(float value, float minimum, float maximum, float fallback) {
        if (!Float.isFinite(value)) {
            return fallback;
        }
        return Math.max(minimum, Math.min(maximum, value));
    }
}

