# EV ChargeSync — Hackathon Simulation

A local Java + Spring Boot demo with linked driver and station portals, a grid charging simulator, and OpenDSS feeder results. It uses synthetic data and does not require a database, cloud account, or physical EV.

## Requirements

- Windows 11
- JDK 17 or newer (not just a JRE)
- Apache Maven 3.8 or newer, or Maven Daemon 1.0.6
- Internet access the first time you build, so Maven can download project dependencies

Install a JDK from [Eclipse Temurin](https://adoptium.net/temurin/releases/). For Maven, download the binary ZIP from [Apache Maven](https://maven.apache.org/download.cgi), extract it to a permanent folder such as `C:\Tools\apache-maven`, and add its `bin` subfolder to your Windows `Path`. The supplied `maven-mvnd-1.0.6-windows-amd64.zip` also works: extract the folder somewhere permanent, such as `C:\Tools\maven-mvnd-1.0.6-windows-amd64`, and add its `bin` subfolder to `Path`. Reopen PowerShell after installation.

Verify both are available in PowerShell:

```powershell
java -version
mvn -version
```

## Launch

1. Extract this ZIP to a folder, for example `C:\hackathon\ev-chargesync`.
2. Open that folder in File Explorer.
3. Double-click `run-windows.bat`, or open PowerShell in the folder and run:

   ```powershell
   .\run-windows.bat
   ```

   To start directly from PowerShell instead, run `mvnd spring-boot:run` (or `mvn spring-boot:run` with standard Maven).
4. Wait for Spring Boot to finish starting, then open [http://localhost:8082/login.html](http://localhost:8082/login.html).
5. Keep the terminal window open while demonstrating. Press `Ctrl+C` there to stop the application.

## Deploy the portal to Render

The repository includes a `Dockerfile` and `render.yaml` for a Render web service. In Render, choose **New → Blueprint**,
connect the private `logeshkumaran07-hub/ev-chargesync` repository, and deploy the `render.yaml` blueprint. The app
uses Render's `PORT` and `RENDER_EXTERNAL_URL` values automatically. The blueprint enables the `apple` Spring profile
so secure cross-site cookies are ready for Apple's form-post callback; Apple still appears only after valid credentials
are configured.

After the first deploy, copy the service's exact HTTPS URL and add OAuth credentials under **Environment** in the
Render service dashboard. Set `CHARGESYNC_PUBLIC_BASE_URL` to that exact origin, without a trailing slash. Add these
variables only in Render's dashboard; never put their values in this repository:

| Provider | Environment variables | Registered callback URL |
|---|---|---|
| Google | `GOOGLE_CLIENT_ID`, `GOOGLE_CLIENT_SECRET` | `<service URL>/login/oauth2/code/google` |
| Microsoft Entra ID | `MICROSOFT_CLIENT_ID`, `MICROSOFT_CLIENT_SECRET`, `MICROSOFT_TENANT_ID` | `<service URL>/login/oauth2/code/microsoft` |
| Apple | `APPLE_CLIENT_ID`, `APPLE_CLIENT_SECRET` | `<service URL>/login/oauth2/code/apple` |

Use a Google **Web application** OAuth client, a Microsoft Entra app registration that supports OIDC sign-in, and an
Apple Services ID with **Sign in with Apple** enabled. Apple requires an HTTPS return URL and a current client-secret
JWT signed with its developer key. After saving environment variables, redeploy the service; the provider buttons
appear when the app detects configured credentials. The service URL can be copied from Render after it is created.

This is a public demo deployment, not production hosting. Render's free web services sleep after 15 minutes without
traffic and can take about a minute to wake. The app stores accounts, stations, bookings, and simulation state in
memory, so a restart, sleep, or redeploy resets that data. Do not use real passwords or sensitive customer information.
Move the app to persistent storage and review its authentication and data handling before using it with real users.

## Sign in and linked driver and station portals

The app opens the sign-in page at `http://localhost:8082/login.html`. Choose a **Driver** or **Station operator** account. Separate demo accounts are included:

| Account | Email | Password |
|---|---|---|
| Driver | `driver@chargesync.demo` | `Driver123!` |
| Station operator | `station@chargesync.demo` | `Station123!` |

New users can register with email and password. Station registration creates a private virtual station with its own tariff, output limit, and exactly three universal sockets. Any free compatible socket can serve Eco, Medium, or Hyper. Each station owner account manages one station and sees only that station’s bookings, charging vehicles, energy, and session totals. Drivers see and book across the network. Accounts, stations, bookings, and simulation state are kept in memory for this demo and reset when the app stops.

The **Driver portal** is at `http://localhost:8082/`; the **Station portal** is at `http://localhost:8082/station.html`. Both screens use the same running service, so dock availability, station prices, bookings, and power allocations update across the two portals. A role-aware session keeps each portal's actions separate.

Each station owner account opens its linked station in the station portal. Enter its name, area, coordinates, maximum output and tariff. Each station has exactly three universal sockets; update their connector type, output limit, and status, then save. Set an unbooked socket to available, occupied, or maintenance; active bookings lock the socket until the operator completes or cancels the session. **Advance this station by 5 min** simulates its charging vehicles, updates battery and cost, and releases completed sockets.

In the driver portal, enter the current station or coordinates, destination, connector, departure time, battery and trip range. Choose **Eco**, **Medium** (the Normal API mode), or **Hyper**. The seeded network contains three demo stations, each with three universal sockets. The network has a 120 kW output limit shared across all stations, split into mode budgets of 20% Eco, 25% Medium, and 55% Hyper. A new session receives its full mode allocation only when its mode budget and station/network power are available; otherwise it waits. Existing active allocations stay fixed. The system estimates a trip target with a 15% reserve, reserves an open compatible socket at the selected station, or routes to the nearest station with an open compatible socket and remaining power. All stations use fixed mode rates: ₹18/kWh for Eco, ₹21/kWh for Medium, and ₹25/kWh for Hyper. Session cost is metered from actual allocated power × elapsed time (kWh) × the selected mode's rate.

This version uses virtual charging stations: dock state and charging sessions are simulated in the portal. Seeded stations and their coordinates are synthetic examples. A redirected distance is straight-line distance, not turn-by-turn route guidance. This is a local hackathon demo, not a production authentication service. Do not reuse real account passwords in this demo.

### Optional Google, Microsoft, and Apple sign-in

The sign-in page shows a provider button only after that provider is configured. Keep all client secrets out of source control and set them as environment variables before starting the app. The provider callback URL must exactly match the URL registered with that provider.

For Google, create a Web application OAuth client and set `GOOGLE_CLIENT_ID` and `GOOGLE_CLIENT_SECRET`. Register `http://localhost:8082/login/oauth2/code/google` as an authorized redirect URI for local development.

For Microsoft Entra ID, create a Web application registration and set `MICROSOFT_CLIENT_ID`, `MICROSOFT_CLIENT_SECRET`, and `MICROSOFT_TENANT_ID` to the app's tenant GUID. This build uses a tenant-specific OIDC issuer. Register `http://localhost:8082/login/oauth2/code/microsoft` as its redirect URI.

Apple sign-in is enabled only for an HTTPS deployment. Set `APPLE_CLIENT_ID` to the Apple Services ID, `APPLE_CLIENT_SECRET` to a current client-secret JWT generated with the Apple signing key, and `CHARGESYNC_PUBLIC_BASE_URL` to the public HTTPS origin. Start the app with the `apple` Spring profile (`$env:SPRING_PROFILES_ACTIVE = "apple"` in PowerShell), and register `<your HTTPS origin>/login/oauth2/code/apple` as the Apple return URL. Apple requires an HTTPS redirect URI and uses a cross-site form POST, so Apple sign-in does not work on the default `http://localhost:8082` address.

On Windows PowerShell, configure secrets for the current terminal session before launching, for example:

```powershell
$env:GOOGLE_CLIENT_ID = "YOUR_GOOGLE_CLIENT_ID"
$env:GOOGLE_CLIENT_SECRET = "YOUR_GOOGLE_CLIENT_SECRET"
$env:MICROSOFT_CLIENT_ID = "YOUR_MICROSOFT_CLIENT_ID"
$env:MICROSOFT_CLIENT_SECRET = "YOUR_MICROSOFT_CLIENT_SECRET"
$env:MICROSOFT_TENANT_ID = "YOUR_TENANT_GUID"
```

Then run `.\run-windows.bat` from the project folder. Provider identities are matched by the stable subject ID and associated with a role-aware ChargeSync account. If a provider returns a verified matching email, it can link to that existing role-matched account. Account/station data is in memory and resets when the app stops; use a persistent identity store and production security review before deployment.

The former simulation dashboard remains at `http://localhost:8082/simulation.html`; the OpenDSS results page remains linked from the navigation.

The first launch may take a few minutes while Maven downloads dependencies. This project uses port 8082 because the earlier portal may still be using port 8081.

## Run the OpenDSS feeder simulation

The `dss` folder contains a three-phase feeder, a 63 kVA service transformer, a phase-distributed EV station, and five-minute EV power profiles. It uses the same urgency-weighted allocation rule as the dashboard, then sends the resulting profiles through OpenDSS in daily mode. This adds feeder voltage and transformer-flow calculations to the hackathon demo. The feeder and its load curve are synthetic and are not a utility study model.

To open the feeder model in the installed OpenDSS desktop app, double-click `dss\open-opendss.bat`. It starts `Master.dss`, solves the daily profile, exports monitor and energy-meter CSV files, and leaves the OpenDSS window open so you can inspect the circuit. The launcher checks `F:\OpenDSS\x64\OpenDSS.exe`, `C:\OpenDSS\x64\OpenDSS.exe`, and the standard Program Files location. If OpenDSS is installed elsewhere, open the batch file in Notepad and replace the listed paths with your `OpenDSS.exe` location.

Open `dss\results.html` to view the included run’s actual OpenDSS voltage, current, and transformer time-series. It works offline and is also linked from the browser dashboard. After exporting a newer run into `dss\output`, refresh the report with `py -3 dss\build_results_report.py`.

If Windows asks which app should open a `.dss` file, the `.dss` file association is not set. Open OpenDSS first, then enter `Compile "<full path to the project>\dss\Master.dss"` in its Main Script window and run it with **Ctrl+D**. A successful run should show **Circuit Status: SOLVED** and should not show a missing `BaseShape` warning. OpenDSS executes the command from its main script window; see the [EPRI GUI instructions](https://opendss.epri.com/OpenDSSGUI.html).

For a headless run, install Python 3.10 or newer, then open PowerShell in the project folder and run:

```powershell
py -3 dss\run_opendss.py --scenario crowded --station-limit 15
```

Other options are `--scenario normal` or `--scenario urgent`, and a station limit from 1 to 100 kW. The script prefers OpenDSS's command-line runner (`OpenDSScmd.exe`) and checks the desktop `OpenDSS.exe` as a fallback. If it is installed elsewhere, pass the engine path:

```powershell
py -3 dss\run_opendss.py --engine "C:\OpenDSS\x64\OpenDSScmd.exe" --scenario urgent --station-limit 5
```

OpenDSS exports monitor and energy-meter CSV files to `dss\output` when that folder is writable. Some Windows installations fall back to `%LOCALAPPDATA%\OpenDSS` if the selected data path cannot be written; copy the `ChargeSync_*.CSV` reports from there into `dss\output` to keep them with the project. The headless launcher uses `dss\RunHeadless.dss`, which closes the engine after solving; the GUI launcher runs `dss\Master.dss` without closing the window. Both use the included profile files in `dss\profiles`.

If OpenDSS prints `EInOutError: File access denied` before it processes a DSS file (even for `OpenDSScmd.exe -h`), repair or reinstall OpenDSS and retry. That startup failure is separate from the ChargeSync feeder files.

## Five-minute demonstration

1. Choose **Busy station · 6 vehicles** with the station limit at **120 kW**.
2. Click **Reset**, then **+ 5 minutes** a few times or click **Start**.
3. Show the three universal sockets and mode budgets: Eco 24 kW, Medium 30 kW, Hyper 66 kW at 120 kW total output.
4. Add vehicles in the same mode; they can use any free compatible socket while mode power and station capacity remain, while current charging allocations stay fixed.
5. Lower station capacity and compare station load, battery levels, delivered energy, fixed mode-rate costs, and missed targets.
6. Select **Urgent departures**, reset, and observe which targets cannot be met before departure.

## API

- `GET /api/state`
- `POST /api/start`
- `POST /api/pause`
- `POST /api/tick`
- `POST /api/reset` with JSON such as `{"scenario":"crowded","limit":120}`
- `POST /api/limit` with `{"limit":5}`
- `GET /api/portal/stations`, `POST /api/portal/stations`, and `PUT /api/portal/stations/{stationId}`
- `PUT /api/portal/stations/{stationId}/docks/{dockId}/status`
- `GET /api/portal/bookings`, `POST /api/portal/bookings`, `POST /api/portal/bookings/{bookingId}/cancel`, and `POST /api/portal/bookings/{bookingId}/complete`
- `POST /api/portal/advance` to advance the portal demo by five minutes

## Model assumptions

- Each simulation tick represents five minutes.
- Charging efficiency is assumed to be 100%.
- The simulator has three universal sockets shared by all modes. The station output is divided into Eco 20%, Medium 25%, and Hyper 55% budgets; vehicles receive their full mode allocation when socket and power capacity are available, or wait.
- Existing active allocations remain fixed when vehicles are added or edited. Reducing station capacity can lower allocations to keep the total within the new hard limit.
- Session cost uses fixed rates: ₹18/kWh Eco, ₹21/kWh Medium, and ₹25/kWh Hyper.
- The data is synthetic. This prototype is not a certified EVSE controller; do not connect it to real charging hardware.
