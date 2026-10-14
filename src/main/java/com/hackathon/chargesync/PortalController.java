package com.hackathon.chargesync;

import jakarta.servlet.http.HttpSession;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;
import java.util.Objects;

@RestController
@RequestMapping("/api/portal")
public class PortalController {
    private final PortalService portal;
    private final AuthService auth;

    public PortalController(PortalService portal, AuthService auth) {
        this.portal = portal;
        this.auth = auth;
    }

    @GetMapping("/stations")
    public List<PortalService.Station> stations(HttpSession session) {
        AuthService.Account account = auth.requireAccount(session);
        List<PortalService.Station> stations = portal.getStations();
        if ("STATION".equals(account.role)) return stations.stream()
            .filter(s -> Objects.equals(s.id, account.stationId))
            .map(this::ownerStationView)
            .toList();
        return stations;
    }

    @PostMapping("/stations")
    public PortalService.Station createStation(@RequestBody PortalService.StationRequest request, HttpSession session) {
        AuthService.Account account = auth.requireRole(session, "STATION");
        if (account.stationId != null) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Each station owner account can manage only one station.");
        }
        PortalService.Station station = portal.createStation(request);
        auth.assignStation(account, station.id);
        return ownerStationView(station);
    }

    @PutMapping("/stations/{stationId}")
    public PortalService.Station updateStation(@PathVariable String stationId,
                                               @RequestBody PortalService.StationRequest request,
                                               HttpSession session) {
        AuthService.Account account = auth.requireRole(session, "STATION");
        auth.requireStationOwnership(account, stationId);
        return ownerStationView(portal.updateStation(stationId, request));
    }

    @PutMapping("/stations/{stationId}/docks/{dockId}/status")
    public PortalService.Dock updateDockStatus(@PathVariable String stationId,
                                               @PathVariable String dockId,
                                               @RequestBody Map<String, String> body,
                                               HttpSession session) {
        AuthService.Account account = auth.requireRole(session, "STATION");
        auth.requireStationOwnership(account, stationId);
        return portal.updateDockStatus(stationId, dockId, body.get("status"));
    }

    @GetMapping("/bookings")
    public List<PortalService.Booking> bookings(@RequestParam(required = false) String stationId, HttpSession session) {
        AuthService.Account account = auth.requireAccount(session);
        if ("STATION".equals(account.role)) {
            if (stationId != null && !stationId.isBlank()) auth.requireStationOwnership(account, stationId);
            return portal.getBookings(account.stationId, null);
        }
        return portal.getBookings(null, account.id);
    }

    @PostMapping("/bookings")
    public PortalService.Booking createBooking(@RequestBody PortalService.DriverRequest request, HttpSession session) {
        AuthService.Account account = auth.requireRole(session, "DRIVER");
        return portal.createBooking(request, account.id);
    }

    @PostMapping("/bookings/{bookingId}/cancel")
    public PortalService.Booking cancelBooking(@PathVariable String bookingId, HttpSession session) {
        AuthService.Account account = auth.requireAccount(session);
        requireBookingAccess(bookingId, account);
        return portal.cancelBooking(bookingId);
    }

    @PostMapping("/bookings/{bookingId}/complete")
    public PortalService.Booking completeBooking(@PathVariable String bookingId, HttpSession session) {
        AuthService.Account account = auth.requireRole(session, "STATION");
        requireBookingAccess(bookingId, account);
        return portal.completeBooking(bookingId);
    }

    @PostMapping("/advance")
    public List<PortalService.Booking> advanceFiveMinutes(HttpSession session) {
        AuthService.Account account = auth.requireRole(session, "STATION");
        return portal.advanceFiveMinutes(account.stationId);
    }

    private PortalService.Station ownerStationView(PortalService.Station source) {
        PortalService.Station view = new PortalService.Station();
        view.id = source.id;
        view.name = source.name;
        view.area = source.area;
        view.latitude = source.latitude;
        view.longitude = source.longitude;
        view.maxOutputKw = source.maxOutputKw;
        view.pricePerKwh = source.pricePerKwh;
        view.hardwareLink = source.hardwareLink;
        view.allocatedKw = source.allocatedKw;
        view.availablePowerKw = source.availablePowerKw;
        // Station owners see only their own station's totals.
        view.networkAllocatedKw = source.allocatedKw;
        view.networkLimitKw = source.maxOutputKw;
        view.availableDocks = source.availableDocks;
        view.activeBookings = source.activeBookings;
        for (PortalService.Dock dock : source.docks) {
            PortalService.Dock dockView = new PortalService.Dock();
            dockView.id = dock.id;
            dockView.connector = dock.connector;
            dockView.chargeMode = dock.chargeMode;
            dockView.maxKw = dock.maxKw;
            dockView.status = dock.status;
            dockView.bookingId = dock.bookingId;
            dockView.sessionLabel = dock.sessionLabel;
            view.docks.add(dockView);
        }
        return view;
    }

    private void requireBookingAccess(String bookingId, AuthService.Account account) {
        List<PortalService.Booking> visible = "STATION".equals(account.role)
            ? portal.getBookings(account.stationId, null) : portal.getBookings(null, account.id);
        if (visible.stream().noneMatch(b -> bookingId.equals(b.id))) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "This booking belongs to a different account.");
        }
    }
}
