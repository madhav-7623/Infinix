package com.hackathon.chargesync;

import java.util.*;

public class SimulationState {
    public double stationLimitKw = 120;
    public int simulationMinute = 0;
    public boolean running = false;
    public long overloadEvents = 0;
    public String scenario = "crowded";
    public List<Vehicle> vehicles = new ArrayList<>();
    public List<Map<String, Object>> history = new ArrayList<>();

    public double totalAllocatedKw() {
        return vehicles.stream().mapToDouble(v -> v.allocatedKw).sum();
    }
    public double totalEnergyKwh() {
        return vehicles.stream().mapToDouble(v -> v.energyDeliveredKwh).sum();
    }
    public double totalCostInr() {
        return vehicles.stream().mapToDouble(v -> v.costInr).sum();
    }
    public long completedVehicles() {
        return vehicles.stream().filter(v -> v.soc >= v.targetSoc - 0.001).count();
    }
    public long missedTargets() {
        return vehicles.stream().filter(v -> simulationMinute >= v.departureMinute && v.soc < v.targetSoc - 0.001).count();
    }
}
