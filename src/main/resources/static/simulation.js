(() => {
  const $ = id => document.getElementById(id);
  const esc = value => String(value ?? '').replace(/[&<>"']/g, c => ({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]));
  const number = value => Number(value || 0).toLocaleString(undefined, {maximumFractionDigits:1});
  let busy = false;

  async function api(path) {
    const response = await fetch(path, {credentials:'same-origin', headers:{'Accept':'application/json'}});
    if (!response.ok) {
      let message = `Request failed (${response.status})`;
      try { const body = await response.json(); message = body.detail || body.message || message; } catch {}
      throw new Error(message);
    }
    return response.json();
  }

  function renderStations(stations) {
    $('liveStations').innerHTML = stations.length ? stations.map(station => {
      const docks = Array.isArray(station.docks) ? station.docks : [];
      const available = Number(station.availableDocks ?? docks.filter(d => d.status === 'AVAILABLE').length);
      const capacity = Number(station.maxOutputKw || 0);
      const load = Number(station.allocatedKw || 0);
      const socketText = docks.length ? `${available} of ${docks.length} sockets available` : `${available} sockets available`;
      return `<article class="live-station"><div><strong>${esc(station.name || 'Station')}</strong><p>${esc(station.area || '')}${station.address ? ` · ${esc(station.address)}` : ''}<br>${socketText}</p></div><div class="station-stats"><span>${number(load)} / ${number(capacity)} kW in use</span></div></article>`;
    }).join('') : '<div class="empty">No stations are available for this account.</div>';
  }

  function renderBookings(bookings) {
    const active = new Map();
    for (const booking of bookings) {
      const status = String(booking.status || '').toUpperCase();
      if (!['CHARGING','WAITING_FOR_POWER'].includes(status)) continue;
      const key = String(booking.id || `${booking.driverName}|${booking.assignedStationId}|${booking.assignedDockId}`);
      if (!active.has(key)) active.set(key, booking);
    }
    const live = [...active.values()];
    $('activeCount').textContent = live.length;
    $('liveBookings').innerHTML = live.length ? live.map(booking => {
      const mode = String(booking.chargeMode || 'NORMAL').replaceAll('_',' ');
      const status = String(booking.status || '').replaceAll('_',' ');
      const power = Number(booking.allocatedKw || 0);
      const battery = Number(booking.batteryPercent || 0);
      return `<article class="live-booking"><div class="booking-top"><strong>${esc(booking.id || 'Live session')} · ${esc(booking.driverName || 'Driver')}</strong><span class="pill ${power > 0 ? '' : 'warn'}">${esc(status)}</span></div><p>${esc(booking.assignedStationName || 'Station')} · socket ${esc(booking.assignedDockId || '—')}<br>${esc(mode)} · ${number(power)} kW allocated · ${number(battery)}% battery</p></article>`;
    }).join('') : '<div class="empty">No vehicles are charging or waiting for power.</div>';
    return live;
  }

  async function refresh() {
    if (busy) return;
    busy = true;
    try {
      const [stations, bookings] = await Promise.all([api('/api/portal/stations'), api('/api/portal/bookings')]);
      const uniqueStations = [...new Map(stations.map(station => [String(station.id), station])).values()];
      renderStations(uniqueStations);
      const live = renderBookings(bookings);
      const used = uniqueStations.reduce((sum, station) => sum + Number(station.allocatedKw || 0), 0);
      const open = uniqueStations.reduce((sum, station) => sum + Number(station.availableDocks || 0), 0);
      $('powerInUse').textContent = `${number(used)} kW`;
      $('availableSockets').textContent = open;
      $('updatedAt').textContent = `Updated ${new Date().toLocaleTimeString()}`;
    } catch (error) {
      $('liveStations').innerHTML = `<div class="empty">Could not load stations: ${esc(error.message)}. Sign in to a driver or station portal and refresh.</div>`;
      $('liveBookings').innerHTML = '<div class="empty">Live vehicle data is unavailable.</div>';
      $('activeCount').textContent = '—';
      $('powerInUse').textContent = '—';
      $('availableSockets').textContent = '—';
      $('updatedAt').textContent = 'Unable to update';
    } finally { busy = false; }
  }

  refresh();
  setInterval(refresh, 5000);
})();
