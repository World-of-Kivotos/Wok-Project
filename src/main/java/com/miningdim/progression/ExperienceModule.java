package com.miningdim.progression;

import com.miningdim.core.Subsystem;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Player;
import net.minecraftforge.eventbus.api.IEventBus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** Public composition root for the WOK server-wide experience system. */
public final class ExperienceModule implements Subsystem {
    public static final String MODULE_ID = "wok-experience";

    private static final Logger LOGGER = LoggerFactory.getLogger("miningdim/experience");

    private final ExperienceRouter router = new ExperienceRouter();

    @Override
    public void register(IEventBus modBus, IEventBus forgeBus) {
        ExperienceServices.register(router);
        LOGGER.info("[miningdim] {} registered (server-wide tracks + source-aware awards)", MODULE_ID);
    }

    @Override
    public String name() {
        return MODULE_ID;
    }

    private static final class ExperienceRouter implements IExperienceService {
        private final Map<ResourceLocation, ExperienceTrackHandler> tracks = new ConcurrentHashMap<>();
        private final Map<ResourceLocation, ResourceLocation> sources = new ConcurrentHashMap<>();

        @Override
        public void registerTrack(ResourceLocation trackId, ExperienceTrackHandler handler) {
            Objects.requireNonNull(trackId, "trackId");
            Objects.requireNonNull(handler, "handler");
            ExperienceTrackHandler existing = tracks.putIfAbsent(trackId, handler);
            if (existing != null && existing != handler) {
                throw new IllegalStateException("Experience track already registered: " + trackId);
            }
        }

        @Override
        public void registerSource(ResourceLocation sourceId, ResourceLocation trackId) {
            Objects.requireNonNull(sourceId, "sourceId");
            requiredTrack(trackId);
            ResourceLocation existing = sources.putIfAbsent(sourceId, trackId);
            if (existing != null && !existing.equals(trackId)) {
                throw new IllegalStateException(
                        "Experience source already belongs to another track: " + sourceId);
            }
        }

        @Override
        public boolean hasTrack(ResourceLocation trackId) {
            return tracks.containsKey(Objects.requireNonNull(trackId, "trackId"));
        }

        @Override
        public boolean hasSource(ResourceLocation sourceId) {
            return sources.containsKey(Objects.requireNonNull(sourceId, "sourceId"));
        }

        @Override
        public Set<ResourceLocation> trackIds() {
            return Set.copyOf(tracks.keySet());
        }

        @Override
        public ExperienceSnapshot snapshot(Player player, ResourceLocation trackId) {
            Objects.requireNonNull(player, "player");
            return requiredTrack(trackId).snapshot(player);
        }

        @Override
        public ExperienceAward award(Player player, ExperienceGrant grant) {
            Objects.requireNonNull(player, "player");
            Objects.requireNonNull(grant, "grant");
            ResourceLocation sourceTrack = sources.get(grant.sourceId());
            if (sourceTrack == null) {
                throw new IllegalStateException(
                        "Experience source is not registered: " + grant.sourceId());
            }
            if (!sourceTrack.equals(grant.trackId())) {
                throw new IllegalStateException("Experience source " + grant.sourceId()
                        + " belongs to " + sourceTrack + ", not " + grant.trackId());
            }
            ExperienceTrackHandler handler = requiredTrack(grant.trackId());
            long effectiveXp = handler.award(player, grant.rawXp());
            if (effectiveXp < 0L) {
                throw new IllegalStateException(
                        "Experience track returned negative effective XP: " + grant.trackId());
            }
            ExperienceSnapshot snapshot = handler.snapshot(player);
            return new ExperienceAward(grant.trackId(), grant.sourceId(), grant.rawXp(),
                    effectiveXp, snapshot);
        }

        private ExperienceTrackHandler requiredTrack(ResourceLocation trackId) {
            ExperienceTrackHandler handler = tracks.get(Objects.requireNonNull(trackId, "trackId"));
            if (handler == null) {
                throw new IllegalStateException("Experience track is not registered: " + trackId);
            }
            return handler;
        }
    }
}
