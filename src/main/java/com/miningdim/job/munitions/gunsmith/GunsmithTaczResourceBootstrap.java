package com.miningdim.job.munitions.gunsmith;

import com.miningdim.MiningDim;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

public final class GunsmithTaczResourceBootstrap {

    private static final Logger LOGGER = LoggerFactory.getLogger("miningdim/gunsmith_tacz_resources");
    private static final String EXPORT_RESOURCE_MANAGER = "com.tacz.guns.api.resource.ResourceManager";
    private static final String GUNPACK_PATH = "assets/miningdim/custom/miningdim_gunsmith";
    private static boolean registered;

    private GunsmithTaczResourceBootstrap() {
    }

    public static void registerExportPack() {
        if (registered) {
            return;
        }
        try {
            Class<?> resourceManager = Class.forName(EXPORT_RESOURCE_MANAGER);
            Method registerExportResource = resourceManager.getMethod(
                    "registerExportResource", Class.class, String.class);
            registerExportResource.invoke(null, MiningDim.class, GUNPACK_PATH);
            registered = true;
            LOGGER.info("[miningdim] registered TaCZ gunsmith gunpack export: {}", GUNPACK_PATH);
        } catch (ClassNotFoundException ignored) {
            LOGGER.debug("[miningdim] TaCZ not present; gunsmith gunpack export skipped");
        } catch (NoSuchMethodException | IllegalAccessException | InvocationTargetException ex) {
            LOGGER.warn("[miningdim] failed to register TaCZ gunsmith gunpack export", ex);
        }
    }
}
