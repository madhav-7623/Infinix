package com.hackathon.chargesync;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

/** Shared in-memory service behind the driver and station portals. */
@Service
public class PortalService {
    private static final double TRIP_RESERVE_PERCENT = 15.0;
    private final LinkedHashMap<String, Station> stations = new LinkedHashMap<>();
    private final LinkedHashMap<String, Booking> bookings = new LinkedHashMap<>();

    public PortalService() {
        seedDemoStations();
    }

    public synchronized List<Station> getStations() {
        recalculate();
        return new ArrayList<>(stations.values());
    }

    public synchronized List<Booking> getBookings(String stationId, String driverAccountId) {
        recalculate();
        return bookings.values().stream()
            .filter(b -> !cancellationExpired(b))
            .filter(b -> stationId == null || stationId.isBlank() || stationId.equals(b.assignedStationId))
            .filter(b -> driverAccountId == null || driverAccountId.isBlank() || driverAccountId.equals(b.driverAccountId))
            .sorted(Comparator.comparingInt((Booking b) -> b.priorityRank == 0 ? Integer.MAX_VALUE : b.priorityRank)
                .thenComparing(b -> b.createdAt))
            .collect(Collectors.toList());
    }

    public synchronized Station createStation(StationRequest request) {
        validateStation(request);
        String id = "station-" + UUID.randomUUID().toString().substring(0, 8);
        Station station = new Station();
        station.id = id;
        applyStationConfig(station, request, null);
        stations.put(id, station);
        recalculate();
        return station;
    }

    public synchronized Station updateStation(String id, StationRequest request) {
        validateStation(request);
        Station station = stationOr404(id);
        applyStationConfig(station, request, station.docks);
        recalculate();
        return station;
    }

    public synchronized Dock updateDockStatus(String stationId, String dockId, String requestedStatus) {
        Station station = stationOr404(stationId);
        Dock dock = dockOr404(station, dockId);
        if (dock.bookingId != null) {
            throw conflict("This dock has an active booking. Complete or cancel it before changing dock status.");
        }
        String status = normalizeDockStatus(requestedStatus, false);
        dock.status = status;
        dock.sessionLabel = "OCCUPIED".equals(status) ? "External / station-reported session" : "";
        recalculate();
        return dock;
    }

    public synchronized Booking createBooking(DriverRequest request, String driverAccountId) {
        validateDriver(request);
        recalculate();

        Station originStation = null;
        if (request.currentStationId != null && !request.currentStationId.isBlank()) {
            originStation = stationOr404(request.currentStationId);
        }
        double originLat = request.currentLatitude != null
            ? request.currentLatitude : originStation == null ? Double.NaN : originStation.latitude;
        double originLon = request.currentLongitude != null
            ? request.currentLongitude : originStation == null ? Double.NaN : originStation.longitude;
        if (!Double.isFinite(originLat) || !Double.isFinite(originLon)) {
            throw badRequest("Select your current station or enter both current-location coordinates.");
        }

        String connector = normalizeConnector(request.connector);
        String chargeMode = normalizeChargeMode(request.chargeMode);
        Station selected = null;
        Dock selectedDock = null;
        if (originStation != null) {
            selectedDock = availableDock(originStation, connector, chargeMode, request.vehicleMaxKw);
            if (selectedDock != null) selected = originStation;
        }
        if (selected == null) {
            final double lat = originLat;
            final double lon = originLon;
            List<Station> nearest = stations.values().stream()
                .sorted(Comparator.comparingDouble(s -> distanceKm(lat, lon, s.latitude, s.longitude)))
                .toList();
            for (Station candidate : nearest) {
                Dock dock = availableDock(candidate, connector, chargeMode, request.vehicleMaxKw);
                if (dock != null) {
                    selected = candidate;
                    selectedDock = dock;
                    break;
                }
            }
        }
        if (selected == null || selectedDock == null) {
            throw conflict("No nearby station has an available compatible dock and remaining power.");
        }

        double tripSoc = request.estimatedTripKm > 0
            ? Math.min(100, TRIP_RESERVE_PERCENT + request.estimatedTripKm / request.rangeAt100Km * 100.0)
            : 0;
        double target = Math.max(request.targetSoc, tripSoc);
        if (target <= request.batteryPercent + 0.01) {
            throw badRequest("The current battery already meets the requested trip charge target.");
        }

        Booking booking = new Booking();
        booking.id = "CS-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase(Locale.ROOT);
        booking.driverName = clean(request.driverName, "Driver");
        booking.driverAccountId = driverAccountId;
        booking.destination = clean(request.destination, "Not provided");
        booking.connector = connector;
        booking.chargeMode = chargeMode;
        booking.requestedStationId = originStation == null ? "" : originStation.id;
        booking.assignedStationId = selected.id;
        booking.assignedStationName = selected.name;
        booking.assignedDockId = selectedDock.id;

        booking.tripDistanceKm = request.estimatedTripKm;
        booking.rangeAt100Km = request.rangeAt100Km;
        booking.batteryPercent = request.batteryPercent;
        booking.batteryCapacityKwh = request.batteryCapacityKwh;
        booking.requestedTargetSoc = request.targetSoc;
        booking.requiredTripSoc = round1(tripSoc);
        booking.targetSoc = round1(target);
        booking.vehicleMaxKw = request.vehicleMaxKw;
        booking.departureMinutes = request.departureMinutes;
        booking.originalDepartureMinutes = request.departureMinutes;
        booking.pricePerKwh = pricePerKwhForMode(booking.chargeMode);
        booking.distanceKm = round2(distanceKm(originLat, originLon, selected.latitude, selected.longitude));
        booking.redirected = originStation != null && !originStation.id.equals(selected.id);
        booking.routeMessage = booking.redirected
            ? originStation.name + " is full or has no compatible dock; routed to the nearest station with an open dock."
            : "Booked at your selected station.";
        booking.status = "WAITING_FOR_POWER";
        booking.createdAt = Instant.now().toString();

        bookings.put(booking.id, booking);
        selectedDock.status = "RESERVED";
        selectedDock.bookingId = booking.id;
        selectedDock.sessionLabel = booking.driverName;
        recalculate();
        return booking;
    }

    public synchronized Booking cancelBooking(String id) {
        Booking booking = bookingOr404(id);
        if (isActive(booking)) {
            booking.status = "CANCELLED";
            booking.cancelledAt = Instant.now().toString();
            releaseDock(booking);
            recalculate();
        }
        return booking;
    }

    public synchronized Booking completeBooking(String id) {
        Booking booking = bookingOr404(id);
        if (isActive(booking)) {
            booking.status = "COMPLETED_BY_STATION";
            releaseDock(booking);
            recalculate();
        }
        return booking;
    }

    /** Advances all active demo charging sessions by five minutes. */
    public synchronized List<Booking> advanceFiveMinutes(String stationId) {
        recalculate();
        for (Booking booking : bookings.values()) {
            if (!isActive(booking) || !Objects.equals(stationId, booking.assignedStationId)) continue;
            double delivered = Math.min(booking.allocatedKw * (5.0 / 60.0), remainingKwh(booking));
            if (delivered > 0) {
                booking.energyDeliveredKwh += delivered;
                booking.batteryPercent = Math.min(booking.targetSoc,
                    booking.batteryPercent + delivered / booking.batteryCapacityKwh * 100.0);
                // Meter cost from actual station power (kW) over this five-minute interval (kWh).
                booking.costInr += delivered * booking.pricePerKwh;
            }
            booking.departureMinutes = Math.max(0, booking.departureMinutes - 5);
            if (remainingKwh(booking) <= 0.001) {
                booking.status = "COMPLETED";
                releaseDock(booking);
            } else if (booking.departureMinutes == 0) {
                booking.status = "DEPARTED_TARGET_MISSED";
                releaseDock(booking);
            }
        }
        recalculate();
        return bookings.values().stream()
            .filter(booking -> Objects.equals(stationId, booking.assignedStationId))
            .toList();
    }

    private void seedDemoStations() {
        Station virtual = new Station();
        virtual.id = "virtual";
        virtual.name = "ChargeSync Virtual Power Station";
        virtual.area = "Demo District";
        virtual.latitude = 12.0000;
        virtual.longitude = 77.0000;
        virtual.maxOutputKw = 120;
        virtual.pricePerKwh = 18;
        virtual.docks.add(dock("DOCK-01", "CCS2", 120, "AVAILABLE", ""));
        virtual.docks.add(dock("DOCK-02", "CCS2", 120, "AVAILABLE", ""));
        virtual.docks.add(dock("DOCK-03", "CCS2", 120, "AVAILABLE", ""));
        stations.put(virtual.id, virtual);
        stations.put("virtual-east", demoStation("virtual-east", "ChargeSync East Market Station", 12.0150, 77.0020));
        stations.put("virtual-central", demoStation("virtual-central", "ChargeSync Central Station", 11.9880, 77.0180));
        recalculate();
    }

    private Station demoStation(String id, String name, double latitude, double longitude) {
        Station station = new Station();
        station.id = id;
        station.name = name;
        station.area = "Demo District";
        station.latitude = latitude;
        station.longitude = longitude;
        station.maxOutputKw = 120;
        station.pricePerKwh = 18;
        station.hardwareLink = "VIRTUAL STATION · simulated dock status";
        station.docks.add(dock("DOCK-01", "CCS2", 120, "AVAILABLE", ""));
        station.docks.add(dock("DOCK-02", "CCS2", 120, "AVAILABLE", ""));
        station.docks.add(dock("DOCK-03", "CCS2", 120, "AVAILABLE", ""));
        return station;
    }

    private void applyStationConfig(Station station, StationRequest request, List<Dock> previousDocks) {
        station.name = clean(request.name, "New Charge Station");
        station.area = clean(request.area, "Area not set");
        station.latitude = request.latitude;
        station.longitude = request.longitude;
        station.maxOutputKw = request.maxOutputKw;
        station.pricePerKwh = request.pricePerKwh;
        station.hardwareLink = "VIRTUAL STATION · simulated dock status";

        Map<String, Dock> old = new HashMap<>();
        if (previousDocks != null) previousDocks.forEach(d -> old.put(d.id, d));
        List<DockRequest> requestedDocks = request.docks == null ? new ArrayList<>() : request.docks;
        if (requestedDocks.isEmpty() && previousDocks == null) {
            requestedDocks = new ArrayList<>();
            requestedDocks.add(dockRequest("DOCK-01", request.maxOutputKw));
            requestedDocks.add(dockRequest("DOCK-02", request.maxOutputKw));
            requestedDocks.add(dockRequest("DOCK-03", request.maxOutputKw));
        }
        if (requestedDocks.size() != 3) throw badRequest("Every station must have exactly 3 sockets.");

        Set<String> ids = new HashSet<>();
        List<Dock> next = new ArrayList<>();
        int number = 1;
        for (DockRequest input : requestedDocks) {
            String id = input.id == null || input.id.isBlank()
                ? "D-" + String.format(Locale.ROOT, "%02d", number++) : input.id.trim();
            if (!ids.add(id)) throw badRequest("Dock IDs must be unique within a station.");
            Dock existing = old.get(id);
            Dock dock = existing == null ? new Dock() : existing;
            dock.id = id;
            dock.connector = normalizeConnector(input.connector);
            dock.maxKw = finiteRange(input.maxKw, 1, 500, "Dock output must be between 1 and 500 kW.");
            if (dock.maxKw > station.maxOutputKw) {
                throw badRequest("A socket output cannot exceed the station's maximum output.");
            }
            // Sockets are universal: any charging mode can use the next free compatible socket.
            dock.chargeMode = null;
            if (existing == null) {
                dock.status = normalizeDockStatus(input.status, false);
                dock.bookingId = null;
            } else if (existing.bookingId == null) {
                dock.status = normalizeDockStatus(input.status, false);
            }
            next.add(dock);
        }
        for (Dock existing : old.values()) {
            if (existing.bookingId != null && !ids.contains(existing.id)) {
                throw conflict("Cannot remove dock " + existing.id + " while it has an active booking.");
            }
        }
        station.docks = next;
    }

    private void validateStation(StationRequest request) {
        if (request == null) throw badRequest("Station details are required.");
        if (request.docks == null || request.docks.isEmpty()) {
            request.docks = new ArrayList<>(List.of(
                dockRequest("DOCK-01", request.maxOutputKw),
                dockRequest("DOCK-02", request.maxOutputKw),
                dockRequest("DOCK-03", request.maxOutputKw)));
        }
        if (request.docks.size() != 3) {
            throw badRequest("Every station must have exactly 3 sockets.");
        }
        request.maxOutputKw = finiteRange(request.maxOutputKw, 1, 1000, "Station output must be between 1 and 1000 kW.");
        for (DockRequest dock : request.docks) {
            if (dock.maxKw > request.maxOutputKw) {
                throw badRequest("A socket output cannot exceed the station's maximum output.");
            }
        }
        request.pricePerKwh = finiteRange(request.pricePerKwh, 0, 10000, "Price must be between 0 and 10000 per kWh.");
        request.latitude = finiteRange(request.latitude, -90, 90, "Latitude must be between -90 and 90.");
        request.longitude = finiteRange(request.longitude, -180, 180, "Longitude must be between -180 and 180.");
    }

    private void validateDriver(DriverRequest r) {
        if (r == null) throw badRequest("Booking details are required.");
        if (r.departureMinutes < 5 || r.departureMinutes > 1440) throw badRequest("Departure must be 5 minutes to 24 hours away.");
        r.batteryPercent = finiteRange(r.batteryPercent, 0, 100, "Battery level must be 0–100%.");
        r.batteryCapacityKwh = finiteRange(r.batteryCapacityKwh, 5, 300, "Battery capacity must be 5–300 kWh.");
        r.targetSoc = finiteRange(r.targetSoc, 1, 100, "Charge target must be 1–100%.");
        if (r.targetSoc <= r.batteryPercent) throw badRequest("Charge target must be higher than the current battery level.");
        r.vehicleMaxKw = finiteRange(r.vehicleMaxKw, 1, 500, "Vehicle charging limit must be 1–500 kW.");
        r.estimatedTripKm = finiteRange(r.estimatedTripKm, 0, 5000, "Trip distance must be 0–5000 km.");
        r.rangeAt100Km = finiteRange(r.rangeAt100Km, 1, 2000, "Vehicle range must be 1–2000 km.");
        if (r.currentLatitude != null) finiteRange(r.currentLatitude, -90, 90, "Latitude must be between -90 and 90.");
        if (r.currentLongitude != null) finiteRange(r.currentLongitude, -180, 180, "Longitude must be between -180 and 180.");
        if ((r.currentLatitude == null) != (r.currentLongitude == null)) throw badRequest("Enter both current-location coordinates, or neither.");
        if (r.currentStationId == null && (r.currentLatitude == null || r.currentLongitude == null)) {
            throw badRequest("Select your current station or enter both current-location coordinates.");
        }
    }

    private void recalculate() {
        for (Booking booking : bookings.values()) {
            if (!isActive(booking)) {
                booking.allocatedKw = 0;
                continue;
            }
            updatePriority(booking);
        }

        // Keep power already assigned to charging sessions steady. Only bookings
        // that are waiting for power can consume newly available capacity.
        List<Booking> activeBookings = bookings.values().stream()
            .filter(this::isActive)
            .filter(b -> {
                Station station = stations.get(b.assignedStationId);
                return station != null && dockForBooking(station, b) != null;
            })
            .toList();

        // Enforce hard limits if configuration changes or legacy allocations
        // already exceed them. Under normal booking changes, existing values stay put.
        for (Station station : stations.values()) {
            List<Booking> activeAtStation = activeBookings.stream()
                .filter(b -> station.id.equals(b.assignedStationId))
                .toList();
            double stationLoadKw = activeAtStation.stream().mapToDouble(b -> b.allocatedKw).sum();
            if (stationLoadKw > station.maxOutputKw && stationLoadKw > 0) {
                double scale = station.maxOutputKw / stationLoadKw;
                activeAtStation.forEach(b -> b.allocatedKw *= scale);
            }
        }

        List<Booking> waitingForPower = activeBookings.stream()
            .filter(b -> b.allocatedKw <= 1e-7)
            .sorted(Comparator.comparingDouble((Booking b) -> b.priorityScore).reversed())
            .toList();
        Map<String, Double> modeLoads = new HashMap<>();
        activeBookings.stream().filter(b -> b.allocatedKw > 1e-7)
            .forEach(b -> modeLoads.merge(modeBudgetKey(b.assignedStationId, b.chargeMode), b.allocatedKw, Double::sum));
        for (Booking booking : waitingForPower) {
            Station station = stations.get(booking.assignedStationId);
            Dock dock = dockForBooking(station, booking);
            double stationLoadKw = activeBookings.stream()
                .filter(b -> station.id.equals(b.assignedStationId))
                .mapToDouble(b -> b.allocatedKw).sum();
            double stationRoomKw = Math.max(0, station.maxOutputKw - stationLoadKw);
            String mode = normalizeChargeMode(booking.chargeMode);
            String modeBudgetKey = modeBudgetKey(station.id, mode);
            double modeBudgetKw = station.maxOutputKw * chargeModeFactor(mode);
            double modeRoomKw = Math.max(0, modeBudgetKw - modeLoads.getOrDefault(modeBudgetKey, 0.0));
            double requestedAllocationKw = Math.min(
                Math.min(booking.vehicleMaxKw, dock.maxKw),
                station.maxOutputKw * chargeModeFactor(booking.chargeMode));
            double availableKw = Math.min(stationRoomKw, modeRoomKw);
            // Each station has its own power and mode budgets. Keep same-mode
            // sessions consistent: grant a full allotment or leave the new booking
            // waiting rather than changing active sessions.
            booking.allocatedKw = availableKw + 1e-7 >= requestedAllocationKw
                ? requestedAllocationKw
                : 0;
            modeLoads.merge(modeBudgetKey, booking.allocatedKw, Double::sum);
        }

        for (Station station : stations.values()) {
            List<Booking> active = activeBookings.stream()
                .filter(b -> station.id.equals(b.assignedStationId))
                .toList();
            active.stream().sorted(Comparator.comparingDouble((Booking b) -> b.priorityScore).reversed())
                .forEachOrdered(new java.util.function.Consumer<>() {
                    private int rank = 1;
                    @Override public void accept(Booking b) {
                        b.priorityRank = rank++;
                        b.estimatedMinutes = estimatedMinutesAtAllocatedPower(b);
                        b.estimatedCostInr = round2(remainingKwh(b) * b.pricePerKwh);
                        b.canMakeTrip = b.estimatedMinutes >= 0 && b.estimatedMinutes <= b.departureMinutes;
                        Dock dock = dockForBooking(station, b);
                        if (dock != null) dock.status = b.allocatedKw > 0 ? "CHARGING" : "RESERVED";
                        b.status = b.allocatedKw > 0 ? "CHARGING" : "WAITING_FOR_POWER";
                    }
                });
            for (Dock dock : station.docks) {
                if (dock.bookingId == null && ("CHARGING".equals(dock.status) || "RESERVED".equals(dock.status))) {
                    dock.status = "AVAILABLE";
                }
            }
            station.allocatedKw = round2(active.stream().mapToDouble(b -> b.allocatedKw).sum());
            station.availableDocks = (int) station.docks.stream().filter(d -> "AVAILABLE".equals(d.status)).count();
            station.activeBookings = active.size();
        }

        for (Station station : stations.values()) {
            station.availablePowerKw = round2(Math.max(0, station.maxOutputKw - station.allocatedKw));
            station.networkAllocatedKw = station.allocatedKw;
            station.networkLimitKw = station.maxOutputKw;
            Map<String, Double> modeLoadsForStation = new LinkedHashMap<>();
            modeLoadsForStation.put("ECO", 0.0);
            modeLoadsForStation.put("NORMAL", 0.0);
            modeLoadsForStation.put("HYPER", 0.0);
            bookings.values().stream()
                .filter(this::isActive)
                .filter(booking -> station.id.equals(booking.assignedStationId))
                .filter(booking -> dockForBooking(station, booking) != null)
                .forEach(booking -> modeLoadsForStation.merge(
                    normalizeChargeMode(booking.chargeMode), booking.allocatedKw, Double::sum));
            station.chargeModeAllocatedKw = modeLoadsForStation;
        }
    }

    private String modeBudgetKey(String stationId, String mode) {
        return stationId + ":" + normalizeChargeMode(mode);
    }

    private void updatePriority(Booking b) {
        double energy = remainingKwh(b);
        double hours = Math.max(5.0 / 60.0, b.departureMinutes / 60.0);
        double urgency = energy / hours / Math.max(1, b.vehicleMaxKw);
        b.priorityScore = (1 + Math.min(25, urgency)) * (b.batteryPercent < 20 ? 1.5 : 1);
        b.priority = urgency >= 1.5 || b.batteryPercent < 15 ? "HIGH" : "STANDARD";
    }

    private String normalizeChargeMode(String mode) {
        if (mode == null || mode.isBlank()) return "NORMAL";
        String normalized = mode.trim().toUpperCase(Locale.ROOT);
        if (!Set.of("ECO", "NORMAL", "HYPER").contains(normalized)) {
            throw badRequest("Charging mode must be Eco, Normal, or Hyper.");
        }
        return normalized;
    }

    private double chargeModeFactor(String mode) {
        return switch (mode == null ? "NORMAL" : mode.toUpperCase(Locale.ROOT)) {
            case "ECO" -> 0.20;
            case "HYPER" -> 0.55;
            default -> 0.25;
        };
    }

    private double pricePerKwhForMode(String mode) {
        return switch (mode == null ? "NORMAL" : mode.toUpperCase(Locale.ROOT)) {
            case "ECO" -> 18.0;
            case "HYPER" -> 25.0;
            default -> 21.0;
        };
    }

    private void releaseDock(Booking booking) {
        Station station = stations.get(booking.assignedStationId);
        if (station == null) return;
        Dock dock = station.docks.stream().filter(d -> booking.id.equals(d.bookingId)).findFirst().orElse(null);
        if (dock != null) {
            dock.bookingId = null;
            dock.status = "AVAILABLE";
            dock.sessionLabel = "";
        }
    }

    private Dock availableDock(Station station, String connector, String chargeMode, double vehicleMaxKw) {
        if (station.availablePowerKw <= 1e-7) return null;
        double modeLoadKw = bookings.values().stream()
            .filter(this::isActive)
            .filter(booking -> station.id.equals(booking.assignedStationId))
            .filter(booking -> chargeMode.equalsIgnoreCase(booking.chargeMode))
            .mapToDouble(booking -> booking.allocatedKw)
            .sum();
        double modeRoomKw = Math.max(0, station.maxOutputKw * chargeModeFactor(chargeMode) - modeLoadKw);
        Dock dock = station.docks.stream()
            .filter(d -> "AVAILABLE".equals(d.status) && connector.equalsIgnoreCase(d.connector))
            .filter(d -> {
                double requestedKw = Math.min(Math.min(vehicleMaxKw, d.maxKw), station.maxOutputKw * chargeModeFactor(chargeMode));
                return requestedKw > 0
                    && requestedKw <= station.availablePowerKw + 1e-7
                    && requestedKw <= modeRoomKw + 1e-7;
            })
            .findFirst().orElse(null);
        if (dock == null) return null;
        return dock;
    }

    private Dock dockForBooking(Station station, Booking booking) {
        return station.docks.stream().filter(d -> booking.id.equals(d.bookingId)).findFirst().orElse(null);
    }

    private boolean isActive(Booking b) {
        return "CHARGING".equals(b.status) || "WAITING_FOR_POWER".equals(b.status);
    }

    private boolean cancellationExpired(Booking booking) {
        if (!"CANCELLED".equals(booking.status)) return false;
        String timestamp = booking.cancelledAt == null || booking.cancelledAt.isBlank()
            ? booking.createdAt : booking.cancelledAt;
        if (timestamp == null || timestamp.isBlank()) return true;
        try {
            return Instant.parse(timestamp).plusSeconds(60).isBefore(Instant.now());
        } catch (RuntimeException ignored) {
            return true;
        }
    }

    private double remainingKwh(Booking b) {
        return Math.max(0, (b.targetSoc - b.batteryPercent) * b.batteryCapacityKwh / 100.0);
    }

    private double estimatedMinutesAtAllocatedPower(Booking booking) {
        double allocatedWatts = booking.allocatedKw * 1000.0;
        if (allocatedWatts <= 0) return -1;
        double remainingWattHours = remainingKwh(booking) * 1000.0;
        return round1(remainingWattHours / allocatedWatts * 60.0);
    }

    private Station stationOr404(String id) {
        Station station = stations.get(id);
        if (station == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Station not found.");
        return station;
    }

    private Booking bookingOr404(String id) {
        Booking booking = bookings.get(id);
        if (booking == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Booking not found.");
        return booking;
    }

    private Dock dockOr404(Station station, String id) {
        return station.docks.stream().filter(d -> d.id.equals(id)).findFirst()
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Dock not found."));
    }

    private void validateStationDockFields(DockRequest dock) {
        dock.maxKw = finiteRange(dock.maxKw, 1, 500, "Dock output must be between 1 and 500 kW.");
        dock.connector = normalizeConnector(dock.connector);
    }

    private String normalizeDockStatus(String status, boolean allowCharging) {
        String value = status == null ? "AVAILABLE" : status.trim().toUpperCase(Locale.ROOT);
        if ("AVAILABLE".equals(value) || "OCCUPIED".equals(value) || "MAINTENANCE".equals(value)
            || (allowCharging && ("CHARGING".equals(value) || "RESERVED".equals(value)))) return value;
        throw badRequest("Dock status must be Available, Occupied, or Maintenance.");
    }

    private String normalizeConnector(String connector) {
        String value = clean(connector, "CCS2");
        if (!List.of("CCS2", "TYPE 2", "CHADEMO").contains(value.toUpperCase(Locale.ROOT))) {
            throw badRequest("Connector must be CCS2, Type 2, or CHAdeMO.");
        }
        return value.equalsIgnoreCase("type 2") ? "Type 2" : value.toUpperCase(Locale.ROOT);
    }

    private static Dock dock(String id, String connector, double maxKw, String status, String session) {
        Dock dock = new Dock();
        dock.id = id;
        dock.connector = connector;
        dock.maxKw = maxKw;
        dock.status = status;
        dock.sessionLabel = session;
        return dock;
    }

    private DockRequest dockRequest(String id, double maxKw) {
        DockRequest request = new DockRequest(id, "CCS2", maxKw, "AVAILABLE");
        return request;
    }

    private static String clean(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    private static double finiteRange(double value, double min, double max, String message) {
        if (!Double.isFinite(value) || value < min || value > max) throw badRequest(message);
        return value;
    }

    private static double distanceKm(double lat1, double lon1, double lat2, double lon2) {
        double dLat = Math.toRadians(lat2 - lat1);
        double dLon = Math.toRadians(lon2 - lon1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
            + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
            * Math.sin(dLon / 2) * Math.sin(dLon / 2);
        return 6371.0 * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
    }

    private static double round1(double n) { return Math.round(n * 10.0) / 10.0; }
    private static double round2(double n) { return Math.round(n * 100.0) / 100.0; }
    private static ResponseStatusException badRequest(String message) { return new ResponseStatusException(HttpStatus.BAD_REQUEST, message); }
    private static ResponseStatusException conflict(String message) { return new ResponseStatusException(HttpStatus.CONFLICT, message); }

    public static class Dock {
        public String id;
        public String connector;
        public String chargeMode;
        public double maxKw;
        public String status;
        public String bookingId;
        public String sessionLabel = "";
        public Dock() { }
    }

    public static class Station {
        public String id;
        public String name;
        public String area;
        public double latitude;
        public double longitude;
        public double maxOutputKw;
        public double pricePerKwh;
        public List<Dock> docks = new ArrayList<>();
        public String hardwareLink = "VIRTUAL STATION · simulated dock status";
        public double allocatedKw;
        public double availablePowerKw;
        public double networkAllocatedKw;
        public double networkLimitKw;
        public int availableDocks;
        public int activeBookings;
        public Map<String, Double> chargeModeAllocatedKw = new LinkedHashMap<>();
        public Station() { }
    }

    public static class Booking {
        public String id;
        public String driverAccountId;
        public String driverName;
        public String destination;
        public String connector;
        public String chargeMode = "NORMAL";
        public String requestedStationId;
        public String assignedStationId;
        public String assignedStationName;
        public String assignedDockId;
        public String status;
        public String priority;
        public String routeMessage;
        public String createdAt;
        public String cancelledAt;

        public boolean redirected;
        public boolean canMakeTrip;
        public double tripDistanceKm;
        public double rangeAt100Km;
        public double batteryPercent;
        public double batteryCapacityKwh;
        public double requestedTargetSoc;
        public double requiredTripSoc;
        public double targetSoc;
        public double vehicleMaxKw;
        public int departureMinutes;
        public int originalDepartureMinutes;
        public double pricePerKwh;
        public double distanceKm;
        public double allocatedKw;
        public double priorityScore;
        public int priorityRank;
        public double energyDeliveredKwh;
        public double costInr;
        public double estimatedMinutes;
        public double estimatedCostInr;
        public Booking() { }
    }

    public static class DriverRequest {
        public String driverName;
        public String destination;
        public String connector;
        public String chargeMode;
        public String currentStationId;
        public Double currentLatitude;
        public Double currentLongitude;
        public int departureMinutes;
        public double batteryPercent;
        public double batteryCapacityKwh;
        public double targetSoc;
        public double vehicleMaxKw;
        public double estimatedTripKm;
        public double rangeAt100Km;

        public DriverRequest() { }
    }

    public static class StationRequest {
        public String name;
        public String area;
        public double latitude;
        public double longitude;
        public double maxOutputKw;
        public double pricePerKwh;
        public List<DockRequest> docks = new ArrayList<>();
        public StationRequest() { }
    }

    public static class DockRequest {
        public String id;
        public String connector;
        public String chargeMode;
        public double maxKw;
        public String status;
        public DockRequest() { }
        public DockRequest(String id, String connector, double maxKw, String status) {
            this.id = id; this.connector = connector; this.maxKw = maxKw; this.status = status;
        }
    }
}
