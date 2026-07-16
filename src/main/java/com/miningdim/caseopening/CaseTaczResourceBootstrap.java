package com.miningdim.caseopening;

import com.miningdim.MiningDim;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

/** Registers the embedded founders-case display/texture gunpack when TaCZ is present. */
public final class CaseTaczResourceBootstrap {

    private static final Logger LOGGER = LoggerFactory.getLogger("miningdim/case_tacz_resources");
    private static final String RESOURCE_MANAGER = "com.tacz.guns.api.resource.ResourceManager";
    private static final String GUNPACK_PATH = "assets/miningdim/custom/miningdim_cases";
    private static volatile boolean registered;

    private CaseTaczResourceBootstrap() {
    }

    public static void registerExportPack() {
        if (registered) {
            return;
        }
        try {
            Class<?> resourceManager = Class.forName(RESOURCE_MANAGER);
            Method method = resourceManager.getMethod("registerExportResource", Class.class, String.class);
            method.invoke(null, MiningDim.class, GUNPACK_PATH);
            registered = true;
            LOGGER.info("[miningdim] registered TaCZ case gunpack export: {}", GUNPACK_PATH);
        } catch (ClassNotFoundException ignored) {
            LOGGER.debug("[miningdim] TaCZ not present; case gunpack export skipped");
        } catch (NoSuchMethodException | IllegalAccessException | InvocationTargetException exception) {
            LOGGER.warn("[miningdim] failed to register TaCZ case gunpack export", exception);
        }
    }

    /** True only after TaCZ accepted the embedded case gunpack export registration. */
    public static boolean isRegistered() {
        return registered;
    }
}
