package com.miningdim.job.munitions.client;

import com.miningdim.job.munitions.MunitionsAmmoFactory;
import com.tacz.guns.api.TimelessAPI;
import com.tacz.guns.client.resource.GunDisplayInstance;
import com.tacz.guns.client.resource.index.ClientGunIndex;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

import java.util.Objects;
import java.util.Optional;

public record GunsmithTaczClientData(Component gunName, ResourceLocation hudTexture) {

    public static Optional<GunsmithTaczClientData> find(ResourceLocation gunId) {
        Objects.requireNonNull(gunId, "gunId");
        if (!MunitionsAmmoFactory.isTaczLoaded()) {
            return Optional.empty();
        }
        Optional<ClientGunIndex> gunIndex = TimelessAPI.getClientGunIndex(gunId);
        if (gunIndex.isEmpty()) {
            return Optional.empty();
        }
        GunDisplayInstance display = gunIndex.get().getDefaultDisplay();
        if (display == null || display.getHUDTexture() == null || gunIndex.get().getName() == null) {
            return Optional.empty();
        }
        return Optional.of(new GunsmithTaczClientData(
                Component.translatable(gunIndex.get().getName()), display.getHUDTexture()));
    }
}
