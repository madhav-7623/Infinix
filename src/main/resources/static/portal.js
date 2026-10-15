(() => {
  const page = document.body.dataset.page;
  let stations = [];
  let bookings = [];
  let selectedStationId = '';
  let stationDraft = null;
  let refreshBusy = false;
  const $ = (id) => document.getElementById(id);
  const esc = (s) => String(s ?? '').replace(/[&<>"']/g, c => ({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]));
  const number = (n, digits = 1) => Number(n || 0).toLocaleString(undefined, {maximumFractionDigits:digits});
  const active = b => ['CHARGING','WAITING_FOR_POWER'].includes(b.status);
  const chargeModes = [
    {value:'ECO',label:'Eco · max 20%',factor:0.20},
    {value:'NORMAL',label:'Medium · max 25%',factor:0.25},
    {value:'HYPER',label:'Hyper · max 55%',factor:0.55}
  ];
  const pill = (b) => {
    const value = (b.status || 'BOOKED').replaceAll('_',' ');
    const cls = b.priority === 'HIGH' ? 'warn' : '';
    return `<span class="pill ${cls}">${esc(value)}</span>`;
  };
  const toast = (message) => {
    const el = $('toast'); if (!el) return;
    el.textContent = message; el.classList.add('show');
    clearTimeout(toast.timer); toast.timer = setTimeout(() => el.classList.remove('show'), 3600);
  };
  async function api(path, options = {}) {
    const apiUrl = window.ChargeSync?.url ? window.ChargeSync.url(path) : path;
    const response = await fetch(apiUrl, {credentials:'include',headers:{'Content-Type':'application/json',...(options.headers || {})},...options});
    if (!response.ok) {
      let message = `Request failed (${response.status})`;
      try { const body = await response.json(); message = body.detail || body.message || message; } catch {}
      throw new Error(message);
    }
    if (response.status === 204) return null;
    return response.json();
  }
  async function refresh() {
    if (refreshBusy) return;
    refreshBusy = true;
    try {
      const [nextStations, nextBookings] = await Promise.all([api('/api/portal/stations'), api('/api/portal/bookings')]);
      stations = nextStations; bookings = nextBookings;
      if (page === 'driver') renderDriverData();
      if (page === 'station') renderStationData(false);
    } catch (error) {
      const targets = page === 'driver' ? ['stationList','driverBookings'] : ['stationStats','stationBookings'];
      for (const id of targets) if ($(id)) $(id).innerHTML = `<div class="empty">Could not reach the ChargeSync service. Start the app using run-windows.bat, then refresh this page.</div>`;
    } finally { refreshBusy = false; }
  }

  function renderDriverData() {
    const selected = $('currentStation')?.value;
    $('currentStation').innerHTML = '<option value="">Choose a station…</option>'
      + stations.map(s => `<option value="${esc(s.id)}">${esc(s.name)} · ${s.availableDocks} open</option>`).join('');
    $('currentStation').value = selected && stations.some(s => s.id === selected) ? selected : '';
    renderSelectedStation();
    $('driverBookings').innerHTML = bookings.length ? bookings.slice().reverse().map(b => `
      <article class="booking"><div class="booking-top"><strong>${esc(b.id)} · ${esc(b.driverName)}</strong>${pill(b)}</div>
      <p>${esc(b.destination)} · ${esc(b.assignedStationName)} / dock ${esc(b.assignedDockId)}${b.redirected ? ` · redirected ${number(b.distanceKm,1)} km` : ''}<br>${esc(b.chargeMode || 'NORMAL')} speed · battery ${number(b.batteryPercent,0)}% → ${number(b.targetSoc,0)}% · ${number(b.allocatedKw)} kW (${number(b.allocatedKw * 1000,0)} W) allocated · ${number(b.departureMinutes,0)} min to departure<br>Estimated charge time at live allocated power: ${b.estimatedMinutes >= 0 ? `${number(b.estimatedMinutes)} min` : 'waiting for power'} · ₹${number(b.estimatedCostInr,2)} remaining energy</p>
      ${active(b) ? `<div class="actions"><button class="btn danger" data-cancel="${esc(b.id)}">Cancel booking</button></div>` : ''}</article>`).join('') : '<div class="empty">Your booking confirmations will appear here.</div>';
    refreshChargeModeOptions();
  }

  function renderSelectedStation() {
    const stationId = $('locationMode')?.value === 'station' ? $('currentStation')?.value : '';
    const station = stations.find(s => s.id === stationId);
    if (!station) {
      $('stationList').innerHTML = '<div class="empty">Choose a station to see its details.</div>';
      return;
    }
    const available = station.availableDocks > 0;
    const openSockets = station.docks.filter(d => d.status === 'AVAILABLE')
      .map(d => `${esc(d.id)} (${({ECO:'Eco',NORMAL:'Medium',HYPER:'Hyper'})[String(d.chargeMode || '').toUpperCase()] || 'Any mode'})`);
    $('stationList').innerHTML = `<article class="station-card"><div><h3>${esc(station.name)} <span class="pill ${available?'':'warn'}">${available ? `${station.availableDocks} socket${station.availableDocks===1?'':'s'} open` : 'Full'}</span></h3><p>${esc(station.area)} · ${number(station.latitude,4)}, ${number(station.longitude,4)}<br>${openSockets.length ? `Available sockets: ${openSockets.join(', ')}` : 'No available sockets'}</p></div><div class="station-stats" style="flex-wrap:wrap;justify-content:flex-end"><span>Station power: ${number(station.allocatedKw)} / ${number(station.maxOutputKw)} kW</span><span>₹18 Eco · ₹21 Medium · ₹25 Hyper /kWh</span></div></article>`;
  }

  function refreshChargeModeOptions() {
    const select = $('chargeMode');
    if (!select) return;
    const selected = select.value;
    const connector = $('connector').value.toUpperCase();
    const vehicleMaxKw = Number($('vehicleMaxKw').value) || 0;
    const selectedStation = $('locationMode').value === 'station'
      ? stations.find(station => station.id === $('currentStation').value)
      : null;
    const stationCanServeMode = (station, mode) => {
      const sharedLoads = station.chargeModeAllocatedKw;
      const modeLoadKw = sharedLoads
        ? Number(sharedLoads[mode.value] || 0)
        : bookings.filter(b => active(b)
          && b.assignedStationId === station.id
          && String(b.chargeMode || 'NORMAL').toUpperCase() === mode.value)
          .reduce((sum, b) => sum + Number(b.allocatedKw || 0), 0);
      const modeRoomKw = Math.max(0, Number(station.maxOutputKw || 120) * mode.factor - modeLoadKw);
      return station.docks.some(d => {
        if (d.status !== 'AVAILABLE'
        || String(d.connector).toUpperCase() !== connector
        ) return false;
        const requestedKw = Math.min(vehicleMaxKw, Number(d.maxKw), Number(station.maxOutputKw) * mode.factor);
        return requestedKw > 0
          && Number(station.availablePowerKw) + 1e-7 >= requestedKw
          && modeRoomKw + 1e-7 >= requestedKw;
      });
    };
    const stationSelectionRequired = $('locationMode').value === 'station';
    const candidateStations = stationSelectionRequired
      ? (selectedStation ? [selectedStation] : [])
      : stations;
    const eligibleStations = new Map(chargeModes.map(mode => [
      mode.value, candidateStations.filter(station => stationCanServeMode(station, mode))
    ]));
    const available = chargeModes.filter(mode => eligibleStations.get(mode.value).length > 0);
    select.innerHTML = available.length
      ? available.map(mode => `<option value="${mode.value}">${mode.label}</option>`).join('')
      : `<option value="">${stationSelectionRequired && !selectedStation ? 'Choose a station first' : 'No mode fits this station’s available power/sockets'}</option>`;
    select.value = available.some(mode => mode.value === selected) ? selected : (available[0]?.value || '');
    $('bookButton').disabled = available.length === 0;
  }

  function setLocationMode() {
    const coordinates = $('locationMode').value === 'coordinates';
    $('stationLocationField').style.display = coordinates ? 'none' : '';
    $('latField').style.display = coordinates ? '' : 'none';
    $('lonField').style.display = coordinates ? '' : 'none';
    $('currentStation').required = !coordinates;
    $('currentLatitude').required = coordinates;
    $('currentLongitude').required = coordinates;
  }
  async function submitBooking(event) {
    event.preventDefault();
    const button = $('bookButton'); button.disabled = true; button.textContent = 'Checking stations…';
    const coordinates = $('locationMode').value === 'coordinates';
    const body = {
      driverName:$('driverName').value.trim(), destination:$('destination').value.trim(),
      connector:$('connector').value, chargeMode:$('chargeMode').value, currentStationId:coordinates ? '' : $('currentStation').value,
      currentLatitude:coordinates ? Number($('currentLatitude').value) : null,
      currentLongitude:coordinates ? Number($('currentLongitude').value) : null,
      departureMinutes:Number($('departureMinutes').value), batteryPercent:Number($('batteryPercent').value),
      batteryCapacityKwh:Number($('batteryCapacityKwh').value), targetSoc:Number($('targetSoc').value),
      vehicleMaxKw:Number($('vehicleMaxKw').value), estimatedTripKm:Number($('estimatedTripKm').value),
      rangeAt100Km:Number($('rangeAt100Km').value)
    };
    try {
      const b = await api('/api/portal/bookings', {method:'POST',body:JSON.stringify(body)});
      const result = $('bookingResult'); result.className = `result show ${b.redirected ? 'warn' : ''}`;
      result.innerHTML = `<h3>Your charging slot is reserved</h3><p>${esc(b.routeMessage)} Destination recorded: ${esc(b.destination)}. Go to <strong>${esc(b.assignedStationName)}</strong>, dock <strong>${esc(b.assignedDockId)}</strong> (${esc(b.connector)}). ${esc(b.chargeMode)} speed selected. ${b.canMakeTrip ? 'The current estimate reaches the trip target before departure.' : 'The estimate may not reach the trip target before departure; power may change as other sessions update.'}</p>
        <div class="metrics"><div class="metric"><span>Live power allocation</span><strong>${number(b.allocatedKw)} kW (${number(b.allocatedKw * 1000,0)} W)</strong></div><div class="metric"><span>Approx. station distance</span><strong>${number(b.distanceKm,1)} km</strong></div><div class="metric"><span>Charge time at live power</span><strong>${b.estimatedMinutes >= 0 ? `${number(b.estimatedMinutes)} min` : 'Waiting'}</strong></div><div class="metric"><span>Price / remaining energy</span><strong>₹${number(b.pricePerKwh,2)} / ₹${number(b.estimatedCostInr,2)}</strong></div></div>
        <p style="margin-top:12px">Trip reserve included: ${number(b.requiredTripSoc,0)}% · target used: ${number(b.targetSoc,0)}% · priority: ${esc(b.priority)} (rank ${b.priorityRank || '—'}).</p>`;
      await refresh();
      result.scrollIntoView({behavior:'smooth',block:'nearest'});
    } catch (error) { toast(error.message); }
    finally { button.disabled = false; button.innerHTML = 'Find a dock and book&nbsp; →'; }
  }

  function stationById(id = selectedStationId) { return stations.find(s => s.id === id); }
  function renderStationData(renderEditor) {
    const select = $('stationSelect');
    if (!select) return;
    const currentValue = select.value && select.value !== '__new' ? select.value : selectedStationId;
    select.innerHTML = stations.map(s => `<option value="${esc(s.id)}">${esc(s.name)} · ${esc(s.area)}</option>`).join('');
    if (stationDraft) select.value = '__new';
    else if (stations.some(s => s.id === currentValue)) { select.value = currentValue; selectedStationId = currentValue; }
    else if (stations.length) { selectedStationId = stations[0].id; select.value = selectedStationId; }
    if (renderEditor) fillStationEditor();
    const station = stationById();
    if (!station) {
      $('stationStats').innerHTML = '<div class="empty">Save a station to publish it to drivers.</div>';
      $('stationBookings').innerHTML = '<div class="empty">No station selected.</div>';
      return;
    }
    const stationBookings = bookings.filter(b => b.assignedStationId === station.id);
    const chargingVehicles = stationBookings.filter(b => b.status === 'CHARGING').length;
    const waitingVehicles = stationBookings.filter(b => b.status === 'WAITING_FOR_POWER').length;
    const deliveredKwh = stationBookings.reduce((sum, b) => sum + Number(b.energyDeliveredKwh || 0), 0);
    const revenue = stationBookings.reduce((sum, b) => sum + Number(b.costInr || 0), 0);
    $('stationStats').innerHTML = `<div class="statcard"><span>Station power in use</span><strong>${number(station.allocatedKw)}<small style="font-size:12px;color:#687b75"> / ${number(station.maxOutputKw)} kW</small></strong></div><div class="statcard"><span>Available station power</span><strong>${number(station.availablePowerKw)}<small style="font-size:12px;color:#687b75"> kW</small></strong></div><div class="statcard"><span>Total bookings</span><strong>${stationBookings.length}</strong></div><div class="statcard"><span>Vehicles charging</span><strong>${chargingVehicles}</strong></div><div class="statcard"><span>Vehicles waiting</span><strong>${waitingVehicles}</strong></div><div class="statcard"><span>Energy delivered</span><strong>${number(deliveredKwh)}<small style="font-size:12px;color:#687b75"> kWh</small></strong></div><div class="statcard"><span>Collected session cost</span><strong>₹${number(revenue,2)}</strong></div>`;
    $('stationBookings').innerHTML = stationBookings.length
      ? stationBookings.slice().reverse().map(b => `<article class="booking"><div class="booking-top"><strong>${esc(b.id)} · ${esc(b.driverName)}</strong>${pill(b)}</div><p>${esc(b.destination)} · dock ${esc(b.assignedDockId)} · ${esc(b.connector)} · ${esc(b.chargeMode || 'NORMAL')} speed<br>Battery ${number(b.batteryPercent,0)}% → ${number(b.targetSoc,0)}% · ${number(b.allocatedKw)} kW · ${number(b.departureMinutes,0)} min to departure<br>Delivered ${number(b.energyDeliveredKwh)} kWh · session cost ₹${number(b.costInr,2)} · est. remaining ₹${number(b.estimatedCostInr,2)}</p>${active(b) ? `<div class="actions"><button class="btn secondary" data-complete="${esc(b.id)}">Complete session</button><button class="btn danger" data-cancel="${esc(b.id)}">Cancel booking</button></div>` : ''}</article>`).join('')
      : '<div class="empty">No bookings at this station yet. New driver bookings will appear here.</div>';
  }
  function fillStationEditor() {
    const station = stationDraft || stationById();
    const fresh = !!stationDraft;
    $('settingsTitle').textContent = fresh ? 'New station details' : 'Station settings';
    $('saveStationButton').textContent = fresh ? 'Publish station' : 'Save station settings';
    $('stationName').value = station?.name || '';
    $('stationArea').value = station?.area || '';
    $('stationLatitude').value = station?.latitude ?? '';
    $('stationLongitude').value = station?.longitude ?? '';
    $('stationMaxOutput').value = station?.maxOutputKw ?? 120;
    $('newDockKw').max = Number(station?.maxOutputKw || 120);
    const docks = station?.docks || [];
    $('dockRows').innerHTML = docks.map(d => {
      const busy = !!d.bookingId;
      const status = busy ? d.status : (d.status || 'AVAILABLE');
      const modeLabel = 'Any mode';
      return `<div class="dock-row" data-dock-id="${esc(d.id)}"><input type="hidden" data-mode value="${esc(d.chargeMode || '')}"><div><strong>${esc(d.id)}</strong><small>${modeLabel} socket · ${busy ? `Booked · ${esc(d.sessionLabel || '')}` : 'Dock status'}</small></div><div><span class="mini-label">Connector</span><select data-connector ${busy?'disabled':''}><option ${d.connector==='CCS2'?'selected':''}>CCS2</option><option ${d.connector==='Type 2'?'selected':''}>Type 2</option><option ${d.connector==='CHADEMO'?'selected':''}>CHAdeMO</option></select></div><div><span class="mini-label">Limit (kW)</span><input data-maxkw type="number" min="1" max="${Number(station?.maxOutputKw || 60)}" value="${Number(d.maxKw)}" ${busy?'disabled':''}></div><div><span class="mini-label">${busy?'Live status':'Availability'}</span>${busy?`<span class="pill ${d.status==='CHARGING'?'':'warn'}">${esc(d.status.replaceAll('_',' '))}</span>`:`<select data-status><option ${status==='AVAILABLE'?'selected':''}>AVAILABLE</option><option ${status==='OCCUPIED'?'selected':''}>OCCUPIED</option><option ${status==='MAINTENANCE'?'selected':''}>MAINTENANCE</option></select>`}</div><div><button type="button" class="btn danger" data-remove-dock="${esc(d.id)}" ${busy?'disabled title="Complete or cancel its booking first"':''}>Remove</button></div></div>`;
    }).join('') || '<div class="empty">No docks added yet.</div>';
    $('hardwareStatus').textContent = station?.hardwareLink || 'Demo dock connector · no physical charger attached.';
  }
  function beginNewStation() {
    stationDraft = {name:'',area:'',latitude:12,longitude:77,maxOutputKw:120,pricePerKwh:18,docks:[
      {id:'DOCK-01',connector:'CCS2',chargeMode:'',maxKw:60,status:'AVAILABLE'},
      {id:'DOCK-02',connector:'CCS2',chargeMode:'',maxKw:60,status:'AVAILABLE'},
      {id:'DOCK-03',connector:'CCS2',chargeMode:'',maxKw:60,status:'AVAILABLE'}
    ]};
    $('stationSelect').value = '__new'; fillStationEditor(); renderStationData(false);
  }
  function collectStationBody() {
    const existing = stationDraft || stationById();
    const docks = [...$('dockRows').querySelectorAll('.dock-row')].map(row => {
      const prev = (existing?.docks || []).find(d => d.id === row.dataset.dockId) || {};
      return {id:row.dataset.dockId,chargeMode:'',connector:row.querySelector('[data-connector]')?.value || prev.connector,
        maxKw:Number(row.querySelector('[data-maxkw]')?.value || prev.maxKw),
        status:row.querySelector('[data-status]')?.value || prev.status || 'AVAILABLE'};
    });
    return {name:$('stationName').value.trim(),area:$('stationArea').value.trim(),latitude:Number($('stationLatitude').value),longitude:Number($('stationLongitude').value),maxOutputKw:Number($('stationMaxOutput').value),pricePerKwh:18,docks};
  }
  async function saveStation(event) {
    event.preventDefault();
    const button = $('saveStationButton'); button.disabled = true;
    try {
      const body = collectStationBody();
      if (body.docks.some(dock => dock.maxKw > body.maxOutputKw)) {
        throw new Error('A socket output cannot exceed the station maximum output.');
      }
      const result = stationDraft
        ? await api('/api/portal/stations',{method:'POST',body:JSON.stringify(body)})
        : await api(`/api/portal/stations/${encodeURIComponent(selectedStationId)}`,{method:'PUT',body:JSON.stringify(body)});
      stationDraft = null; selectedStationId = result.id; await refresh(); fillStationEditor(); toast('Station settings are live in the driver portal.');
    } catch (error) { toast(error.message); }
    finally { button.disabled = false; }
  }
  async function changeDockStatus(stationId, dockId, status) {
    try { await api(`/api/portal/stations/${encodeURIComponent(stationId)}/docks/${encodeURIComponent(dockId)}/status`,{method:'PUT',body:JSON.stringify({status})}); await refresh(); fillStationEditor(); toast(`Dock ${dockId} updated.`); }
    catch (error) { toast(error.message); }
  }
  async function updateBooking(id, action) {
    try { await api(`/api/portal/bookings/${encodeURIComponent(id)}/${action}`,{method:'POST',body:'{}'}); await refresh(); if(page==='station') fillStationEditor(); toast(action==='cancel'?'Booking cancelled.':'Charging session completed.'); }
    catch (error) { toast(error.message); }
  }

  if (page === 'driver') {
    $('locationMode').addEventListener('change',()=>{setLocationMode();refreshChargeModeOptions();renderSelectedStation();});
    $('currentStation').addEventListener('change',()=>{refreshChargeModeOptions();renderSelectedStation();});
    $('connector').addEventListener('change',refreshChargeModeOptions);
    $('vehicleMaxKw').addEventListener('input',refreshChargeModeOptions);
    $('bookingForm').addEventListener('submit',submitBooking);
    $('driverBookings').addEventListener('click',e=>{const b=e.target.closest('[data-cancel]');if(b)updateBooking(b.dataset.cancel,'cancel');});
    setLocationMode(); refresh(); setInterval(refresh,5000);
  }
  if (page === 'station') {
    $('stationSelect').addEventListener('change',e=>{
      if(e.target.value==='__new'){beginNewStation();return;}
      stationDraft=null; selectedStationId=e.target.value; renderStationData(true);
    });
    $('stationForm').addEventListener('submit',saveStation);
    $('stationMaxOutput').addEventListener('input',()=>{$('newDockKw').max=Number($('stationMaxOutput').value)||1;});
    $('addDockButton').addEventListener('click',()=>{
      const existing=stationDraft||stationById(); if(!existing){toast('Choose or add a station first.');return;}
      if(existing.docks.length>=3){toast('Each station can have only 3 sockets. Remove one before adding another.');return;}
      const maxStationKw=Number($('stationMaxOutput').value);
      if(Number($('newDockKw').value)>maxStationKw){toast('Socket output cannot exceed the station maximum output.');return;}
      const id=$('newDockId').value.trim() || `D-${String(existing.docks.length+1).padStart(2,'0')}`;
      if(existing.docks.some(d=>d.id===id)){toast('That dock ID is already in use.');return;}
      existing.docks.push({id,connector:$('newDockConnector').value,maxKw:Number($('newDockKw').value),status:$('newDockStatus').value,bookingId:null});
      $('newDockId').value=''; fillStationEditor();
    });
    $('dockRows').addEventListener('change',e=>{
      const row=e.target.closest('.dock-row'); if(!row||!e.target.matches('[data-status]'))return;
      if(stationDraft){const d=stationDraft.docks.find(x=>x.id===row.dataset.dockId);if(d)d.status=e.target.value;return;}
      changeDockStatus(selectedStationId,row.dataset.dockId,e.target.value);
    });
    $('dockRows').addEventListener('click',e=>{
      const button=e.target.closest('[data-remove-dock]');if(!button)return;
      const station=stationDraft||stationById(); if(station){station.docks=station.docks.filter(d=>d.id!==button.dataset.removeDock);fillStationEditor();}
    });
    $('stationBookings').addEventListener('click',e=>{const cancel=e.target.closest('[data-cancel]');const done=e.target.closest('[data-complete]');if(cancel)updateBooking(cancel.dataset.cancel,'cancel');if(done)updateBooking(done.dataset.complete,'complete');});
    $('advanceButton').addEventListener('click',async()=>{try{await api('/api/portal/advance',{method:'POST',body:'{}'});await refresh();fillStationEditor();toast('Demo sessions advanced by five minutes.');}catch(error){toast(error.message);}});
    refresh().then(()=>renderStationData(true)); setInterval(refresh,5000);
  }
})();
