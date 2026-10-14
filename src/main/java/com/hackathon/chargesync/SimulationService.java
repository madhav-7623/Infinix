package com.hackathon.chargesync;

import org.springframework.stereotype.Service;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import java.util.*;

@Service
public class SimulationService {
    private SimulationState state = new SimulationState();

    public SimulationService() { reset("crowded", 120); }

    public synchronized SimulationState state() { return state; }

    public synchronized SimulationState reset(String scenario, double limit) {
        state = new SimulationState();
        state.scenario = scenario;
        state.stationLimitKw = Math.max(1, Math.min(120, limit));
        state.vehicles = new ArrayList<>();
        allocateOnly();
        snapshot();
        return state;
    }

    public synchronized SimulationState setLimit(double limit) {
        state.stationLimitKw = Math.max(1, Math.min(120, limit));
        allocateOnly();
        snapshot();
        return state;
    }

    public synchronized SimulationState addVehicle(VehicleRequest request) {
        if (state.vehicles.size() >= 12) throw badRequest("A simulation can include up to 12 vehicles.");
        String id = clean(request == null ? null : request.id, "EV-" + String.format(Locale.ROOT, "%02d", state.vehicles.size() + 1));
        if (state.vehicles.stream().anyMatch(v -> v.id.equalsIgnoreCase(id))) throw badRequest("That vehicle ID is already in use.");
        Vehicle vehicle = new Vehicle(id, 60, 20, 80, 100, state.simulationMinute + 90);
        applyVehicleInput(vehicle, request);
        state.vehicles.add(vehicle);
        allocateOnly();
        snapshot();
        return state;
    }

    public synchronized SimulationState updateVehicle(String id, VehicleRequest request) {
        Vehicle vehicle = state.vehicles.stream().filter(v -> v.id.equalsIgnoreCase(id)).findFirst()
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Vehicle not found."));
        if (vehicle.allocatedKw > 0 && request != null) {
            if (request.chargeMode != null && !vehicle.chargeMode.equalsIgnoreCase(request.chargeMode)) {
                throw badRequest("Charging mode is locked while this vehicle is using its socket. Remove and add it again to change modes.");
            }
            if (request.connector != null && !vehicle.connector.equalsIgnoreCase(request.connector)) {
                throw badRequest("Connector is locked while this vehicle is using its socket.");
            }
            if (request.maxKw != null && request.maxKw + 1e-7 < vehicle.allocatedKw) {
                throw badRequest("Vehicle limit cannot be lowered below the power already allocated to this charging session.");
            }
        }
        applyVehicleInput(vehicle, request);
        allocateOnly();
        snapshot();
        return state;
    }

    public synchronized SimulationState removeVehicle(String id) {
        boolean removed = state.vehicles.removeIf(v -> v.id.equalsIgnoreCase(id));
        if (!removed) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Vehicle not found.");
        allocateOnly();
        snapshot();
        return state;
    }

    private void applyVehicleInput(Vehicle vehicle, VehicleRequest request) {
        if (request == null) throw badRequest("Vehicle details are required.");
        String destination = clean(request.destination, "City center");
        String connector = request.connector == null ? "CCS2" : request.connector.trim().toUpperCase(Locale.ROOT);
        if (!Set.of("CCS2", "TYPE 2", "CHADEMO").contains(connector)) throw badRequest("Choose CCS2, Type 2, or CHAdeMO.");
        String mode = request.chargeMode == null ? "NORMAL" : request.chargeMode.trim().toUpperCase(Locale.ROOT);
        if (!Set.of("ECO", "NORMAL", "HYPER").contains(mode)) throw badRequest("Choose Eco, Normal, or Hyper charging.");
        double capacity = range(request.batteryCapacityKwh, 5, 300, "Battery capacity must be 5–300 kWh.");
        double soc = range(request.soc, 0, 99.9, "Battery level must be 0–99.9%.");
        double target = range(request.targetSoc, 1, 100, "Charge target must be 1–100%.");
        if (target <= soc) throw badRequest("Charge target must be higher than the current battery level.");
        double maxKw = range(request.maxKw, 1, 500, "Vehicle charging limit must be 1–500 kW.");
        int departure = request.departureMinute == null ? state.simulationMinute + 90 : request.departureMinute;
        if (departure < state.simulationMinute + 5 || departure > state.simulationMinute + 1440) {
            throw badRequest("Departure must be 5 minutes to 24 hours from now.");
        }
        vehicle.destination = destination;
        vehicle.connector = connector;
        vehicle.chargeMode = mode;

        vehicle.batteryCapacityKwh = capacity;
        vehicle.soc = soc;
        vehicle.targetSoc = target;
        vehicle.maxKw = maxKw;
        vehicle.departureMinute = departure;
        vehicle.status = "WAITING";
    }

    public synchronized SimulationState start() { state.running = true; return state; }
    public synchronized SimulationState pause() { state.running = false; return state; }

    public synchronized SimulationState tick() {
        final double dtHours = 5.0 / 60.0;
        allocateOnly();
        double sum = state.totalAllocatedKw();
        if (sum > state.stationLimitKw + 1e-6) state.overloadEvents++;
        for (Vehicle v : state.vehicles) {
            double energy = v.allocatedKw * dtHours;
            double need = v.energyNeededKwh();
            energy = Math.min(energy, need);
            if (energy > 0) {
                v.soc = Math.min(v.targetSoc, v.soc + energy / v.batteryCapacityKwh * 100.0);
                v.energyDeliveredKwh += energy;
                v.costInr += energy * pricePerKwhForMode(v.chargeMode);
                v.status = "CHARGING";
            } else if (v.soc >= v.targetSoc - 0.001) {
                v.status = "TARGET REACHED";
            } else if (state.simulationMinute >= v.departureMinute) {
                v.status = "DEPARTED — TARGET MISSED";
            } else {
                v.status = "WAITING";
            }
        }
        state.simulationMinute += 5;
        // Recalculate displayed allocations for the new state.
        allocateOnly();
        snapshot();
        return state;
    }

    private void allocateOnly() {
        List<Vehicle> eligible = state.vehicles.stream()
            .filter(v -> v.energyNeededKwh() > 0.001 && state.simulationMinute < v.departureMinute)
            .toList();
        state.vehicles.stream().filter(v -> !eligible.contains(v)).forEach(v -> v.allocatedKw = 0);

        // Existing sessions keep their allocation when another vehicle is added
        // or edited. Scale only when a changed station limit makes the current
        // total physically impossible.
        double loadKw = eligible.stream().mapToDouble(v -> v.allocatedKw).sum();
        if (loadKw > state.stationLimitKw && loadKw > 0) {
            double scale = state.stationLimitKw / loadKw;
            eligible.forEach(v -> v.allocatedKw *= scale);
        }

        for (String mode : List.of("ECO", "NORMAL", "HYPER")) {
            List<Vehicle> modeSessions = eligible.stream()
                .filter(v -> normalizeMode(v.chargeMode).equals(mode) && v.allocatedKw > 1e-7)
                .toList();
            double modeLoadKw = modeSessions.stream().mapToDouble(v -> v.allocatedKw).sum();
            double modeLimitKw = state.stationLimitKw * chargeModeFactor(mode);
            if (modeLoadKw > modeLimitKw && modeLoadKw > 0) {
                double scale = modeLimitKw / modeLoadKw;
                modeSessions.forEach(v -> v.allocatedKw *= scale);
            }
        }
        loadKw = eligible.stream().mapToDouble(v -> v.allocatedKw).sum();

        Map<String, Double> modeLoads = new HashMap<>();
        eligible.stream().filter(v -> v.allocatedKw > 1e-7)
            .forEach(v -> modeLoads.merge(normalizeMode(v.chargeMode), v.allocatedKw, Double::sum));
        List<Vehicle> waiting = eligible.stream()
            .filter(v -> v.allocatedKw <= 1e-7)
            .sorted(Comparator.comparingDouble(this::urgency).reversed())
            .toList();
        long occupiedSockets = eligible.stream().filter(v -> v.allocatedKw > 1e-7).count();
        for (Vehicle vehicle : waiting) {
            if (occupiedSockets >= 3) break;
            String mode = normalizeMode(vehicle.chargeMode);
            if (!"CCS2".equalsIgnoreCase(vehicle.connector)) continue;
            double modeBudgetKw = state.stationLimitKw * chargeModeFactor(mode);
            double modeRoomKw = Math.max(0, modeBudgetKw - modeLoads.getOrDefault(mode, 0.0));
            double requestedKw = Math.min(vehicle.maxKw, modeBudgetKw);
            double stationRoomKw = Math.max(0, state.stationLimitKw - loadKw);
            if (requestedKw > 0
                    && requestedKw <= modeRoomKw + 1e-7
                    && requestedKw <= stationRoomKw + 1e-7) {
                vehicle.allocatedKw = requestedKw;
                loadKw += requestedKw;
                modeLoads.merge(mode, requestedKw, Double::sum);
                occupiedSockets++;
            }
        }
        for (Vehicle vehicle : state.vehicles) {
            if (vehicle.allocatedKw > 1e-7) vehicle.status = "CHARGING";
            else if (vehicle.soc >= vehicle.targetSoc - 0.001) vehicle.status = "TARGET REACHED";
            else if (state.simulationMinute >= vehicle.departureMinute) vehicle.status = "DEPARTED — TARGET MISSED";
            else vehicle.status = "WAITING";
        }
    }

    private double urgency(Vehicle v) {
        double hoursLeft = Math.max(1.0/60.0, (v.departureMinute - state.simulationMinute) / 60.0);
        return v.energyNeededKwh() / hoursLeft / Math.max(0.1, v.maxKw);
    }

    private double chargeModeFactor(String mode) {
        return switch (mode == null ? "NORMAL" : mode.toUpperCase(Locale.ROOT)) {
            case "ECO" -> 0.20;
            case "HYPER" -> 0.55;
            default -> 0.25;
        };
    }

    private String normalizeMode(String mode) {
        return mode == null ? "NORMAL" : mode.trim().toUpperCase(Locale.ROOT);
    }

    private double pricePerKwhForMode(String mode) {
        return switch (normalizeMode(mode)) {
            case "ECO" -> 18.0;
            case "HYPER" -> 25.0;
            default -> 21.0;
        };
    }

    private double range(Double value, double min, double max, String message) {
        if (value == null || !Double.isFinite(value) || value < min || value > max) throw badRequest(message);
        return value;
    }

    private String clean(String value, String fallback) {
        String text = value == null ? "" : value.trim();
        return text.isEmpty() ? fallback : text.substring(0, Math.min(80, text.length()));
    }

    private ResponseStatusException badRequest(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }

    private void snapshot() {
        Map<String,Object> row = new LinkedHashMap<>();
        row.put("minute", state.simulationMinute);
        row.put("loadKw", Math.round(state.totalAllocatedKw()*100.0)/100.0);
        row.put("energyKwh", Math.round(state.totalEnergyKwh()*100.0)/100.0);
        row.put("costInr", Math.round(state.totalCostInr()*100.0)/100.0);
        state.history.add(row);
        if (state.history.size() > 100) state.history.remove(0);
    }
}
