package com.miningdim.job.fisher.soup;

/** Synced Player entity-data bridge used by client break-speed prediction. */
public interface OreSoupPlayerStateAccess {
    int miningdim$getOreSoupState();

    void miningdim$setOreSoupState(int state);
}
