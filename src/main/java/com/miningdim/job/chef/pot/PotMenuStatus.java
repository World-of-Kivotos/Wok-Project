package com.miningdim.job.chef.pot;

/** Values synchronized by the real FD menu; no client world state is consulted. */
public interface PotMenuStatus {
    boolean wok$busy();
    int wok$progress();
    long wok$cost();
}
