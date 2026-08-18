package com.miningdim.power.cable;

import com.miningdim.power.grid.VoltageClass;

/** P3 特殊线缆，不占用十二级导体阶梯。 */
public enum SpecialCableMaterial implements CableProfile {

    TUNGSTEN("tungsten_heat_resistant_wire", 1536, 0.85, 300, 528);

    private final String id;
    private final int ratedCapacityFe;
    private final double degradeFloor;
    private final int maxContinuousTemperatureC;
    private final int baseLineResistanceUnits;

    SpecialCableMaterial(String id, int ratedCapacityFe, double degradeFloor,
                         int maxContinuousTemperatureC, int baseLineResistanceUnits) {
        this.id = id;
        this.ratedCapacityFe = ratedCapacityFe;
        this.degradeFloor = degradeFloor;
        this.maxContinuousTemperatureC = maxContinuousTemperatureC;
        this.baseLineResistanceUnits = baseLineResistanceUnits;
    }

    @Override
    public String id() {
        return id;
    }

    @Override
    public String blockId() {
        return id;
    }

    @Override
    public int ratedCapacityFe() {
        return ratedCapacityFe;
    }

    @Override
    public int transientBufferCap() {
        return ratedCapacityFe;
    }

    @Override
    public double degradeFloor() {
        return degradeFloor;
    }

    @Override
    public InsulationGrade insulation() {
        return InsulationGrade.SILICONE;
    }

    @Override
    public int maxContinuousTemperatureC() {
        return maxContinuousTemperatureC;
    }

    @Override
    public ThermalMode thermalMode() {
        return ThermalMode.STANDARD;
    }

    @Override
    public int baseLineResistanceUnits() {
        return baseLineResistanceUnits;
    }

    @Override
    public VoltageClass voltageClass() {
        return VoltageClass.EXTREME;
    }
}
