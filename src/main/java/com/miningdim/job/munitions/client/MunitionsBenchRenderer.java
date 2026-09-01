package com.miningdim.job.munitions.client;

import com.miningdim.job.munitions.block.MunitionsBenchBlockEntity;
import software.bernie.geckolib.renderer.GeoBlockRenderer;

public final class MunitionsBenchRenderer extends GeoBlockRenderer<MunitionsBenchBlockEntity> {

    public MunitionsBenchRenderer() {
        super(new MunitionsBenchGeoModel());
    }
}
