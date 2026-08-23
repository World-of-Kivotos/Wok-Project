package com.miningdim.progression;

/** Process-lifetime access point for the server-wide experience service. */
public final class ExperienceServices {
    private static IExperienceService experienceService;

    private ExperienceServices() {
    }

    public static synchronized void register(IExperienceService service) {
        if (service == null) {
            throw new IllegalArgumentException("Cannot register null IExperienceService");
        }
        if (experienceService != null && experienceService != service) {
            throw new IllegalStateException("IExperienceService is already registered");
        }
        experienceService = service;
    }

    public static IExperienceService experienceService() {
        IExperienceService service = experienceService;
        if (service == null) {
            throw new IllegalStateException(
                    "ExperienceServices: service not registered (check WOK module order)");
        }
        return service;
    }
}
