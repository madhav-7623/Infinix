"""Generate a 24-hour ChargeSync schedule and solve it with OpenDSS."""

from __future__ import annotations

import argparse
import csv
import math
import os
from pathlib import Path
import shutil
import subprocess
import sys


HERE = Path(__file__).resolve().parent
PROFILE_DIR = HERE / "profiles"
OUTPUT_DIR = HERE / "output"
STEP_MINUTES = 5
STEPS = 24 * 60 // STEP_MINUTES
VEHICLE_SPECS = [
    ("EV01", 60, 25, 80, 11, 90),
    ("EV02", 50, 50, 85, 7, 60),
    ("EV03", 75, 65, 90, 11, 180),
    ("EV04", 40, 35, 75, 7, 120),
    ("EV05", 60, 15, 80, 11, 45),
    ("EV06", 45, 70, 95, 7, 150),
]
BASE_HOURLY = [
    0.62, 0.58, 0.56, 0.55, 0.55, 0.58,
    0.67, 0.78, 0.86, 0.92, 0.96, 0.98,
    0.95, 0.92, 0.90, 0.92, 0.97, 1.00,
    0.98, 0.94, 0.88, 0.81, 0.74, 0.67,
]


def make_vehicles(scenario: str) -> list[dict[str, float | int | str]]:
    specs = VEHICLE_SPECS if scenario != "normal" else VEHICLE_SPECS[:4]
    vehicles = [
        {
            "id": name,
            "capacity": capacity,
            "soc": float(soc),
            "target": target,
            "max_kw": max_kw,
            "departure": departure,
        }
        for name, capacity, soc, target, max_kw, departure in specs
    ]
    if scenario == "urgent":
        vehicles[0]["departure"] = 25
        vehicles[1]["departure"] = 35
        vehicles[4]["departure"] = 25
    return vehicles


def energy_needed(vehicle: dict[str, float | int | str]) -> float:
    return max(0.0, (float(vehicle["target"]) - float(vehicle["soc"]))
               * float(vehicle["capacity"]) / 100.0)


def allocate(vehicles: list[dict[str, float | int | str]],
             minute: int, station_limit_kw: float) -> dict[str, float]:
    demand: dict[str, float] = {}
    weights: dict[str, float] = {}
    for vehicle in vehicles:
        if energy_needed(vehicle) <= 0.001 or minute >= int(vehicle["departure"]):
            continue
        hours_left = max(1.0 / 60.0, (int(vehicle["departure"]) - minute) / 60.0)
        max_kw = float(vehicle["max_kw"])
        demand[str(vehicle["id"])] = min(max_kw, energy_needed(vehicle) / hours_left)
        weights[str(vehicle["id"])] = max(
            0.1, energy_needed(vehicle) / hours_left / max(0.1, max_kw)
        )

    allocation = {str(v["id"]): 0.0 for v in vehicles}
    active = set(demand)
    remaining = station_limit_kw
    while remaining > 1e-6 and active:
        total_weight = sum(weights[name] for name in active)
        distributed = 0.0
        capped: list[str] = []
        for name in active:
            share = remaining * weights[name] / total_weight
            room = demand[name] - allocation[name]
            addition = min(room, share)
            allocation[name] += addition
            distributed += addition
            if room <= share + 1e-6:
                capped.append(name)
        remaining -= distributed
        active.difference_update(capped)
        if distributed <= 1e-6:
            break
    return allocation


def write_profile(path: Path, values: list[float]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("w", newline="", encoding="ascii") as file:
        writer = csv.writer(file, lineterminator="\n")
        for value in values:
            writer.writerow([f"{value:.6f}"])


def generate_profiles(scenario: str, station_limit_kw: float) -> tuple[float, float]:
    vehicles = make_vehicles(scenario)
    profiles = {str(v["id"]): [] for v in vehicles}
    delivered_total = 0.0
    peak_ev_kw = 0.0

    for step in range(STEPS):
        minute = step * STEP_MINUTES
        power = allocate(vehicles, minute, station_limit_kw)
        peak_ev_kw = max(peak_ev_kw, sum(power.values()))
        for vehicle in vehicles:
            name = str(vehicle["id"])
            kw = power[name]
            profiles[name].append(kw / float(vehicle["max_kw"]))
            delivered = min(kw * STEP_MINUTES / 60.0, energy_needed(vehicle))
            vehicle["soc"] = min(
                float(vehicle["target"]),
                float(vehicle["soc"]) + delivered / float(vehicle["capacity"]) * 100.0,
            )
            delivered_total += delivered

    # The 24-hour window begins at 17:00 local time so the charging session
    # overlaps the evening feeder peak represented by the base-load profile.
    start_hour = 17
    base = [BASE_HOURLY[(start_hour + step // 12) % 24] for step in range(STEPS)]
    write_profile(PROFILE_DIR / "base.csv", base)
    for name in ("EV01", "EV02", "EV03", "EV04", "EV05", "EV06"):
        values = profiles.get(name, [0.0] * STEPS)
        write_profile(PROFILE_DIR / f"{name.lower()}.csv", values)
    return delivered_total, peak_ev_kw


def find_engine(explicit: str | None) -> Path | None:
    candidates = [explicit, os.environ.get("OPENDSS_EXE"), os.environ.get("OPENDSSCMD")]
    candidates.extend([
        # Prefer OpenDSS's command-line runner for headless simulations.
        r"F:\OpenDSS\x64\OpenDSScmd.exe",
        r"C:\OpenDSS\x64\OpenDSScmd.exe",
        r"C:\Program Files\OpenDSS\x64\OpenDSScmd.exe",
        r"F:\OpenDSS\x64\OpenDSS.exe",
        r"C:\OpenDSS\x64\OpenDSS.exe",
        r"C:\Program Files\OpenDSS\x64\OpenDSS.exe",
        shutil.which("OpenDSS.exe"),
        shutil.which("OpenDSScmd.exe"),
    ])
    for candidate in candidates:
        if candidate and Path(candidate).is_file():
            return Path(candidate)
    return None


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--scenario", choices=("normal", "crowded", "urgent"), default="crowded")
    parser.add_argument("--station-limit", type=float, default=15.0, help="station cap in kW (1-100)")
    parser.add_argument("--engine", help="path to OpenDSScmd.exe or OpenDSS.exe (auto-detected when omitted)")
    args = parser.parse_args()
    if not math.isfinite(args.station_limit) or not 1 <= args.station_limit <= 100:
        parser.error("--station-limit must be between 1 and 100 kW")

    engine = find_engine(args.engine)
    if engine is None:
        print("OpenDSS was not found. Install OpenDSS or pass --engine PATH.", file=sys.stderr)
        return 2

    OUTPUT_DIR.mkdir(exist_ok=True)
    energy_kwh, peak_kw = generate_profiles(args.scenario, args.station_limit)
    try:
        result = subprocess.run(
            [str(engine), str(HERE / "RunHeadless.dss")],
            cwd=HERE,
            text=True,
            capture_output=True,
            timeout=180,
            check=False,
        )
    except (OSError, subprocess.TimeoutExpired) as error:
        print(f"Could not run OpenDSS: {error}", file=sys.stderr)
        return 2

    log_path = OUTPUT_DIR / "opendss-console.txt"
    log_path.write_text(result.stdout + result.stderr, encoding="utf-8")
    print(f"OpenDSS engine: {engine}")
    print(f"Scenario: {args.scenario}; station cap: {args.station_limit:.1f} kW")
    print(f"Peak scheduled EV load: {peak_kw:.2f} kW")
    print(f"Scheduled EV energy over 24-hour horizon: {energy_kwh:.2f} kWh")
    print(f"OpenDSS log: {log_path}")
    monitor_files = sorted(OUTPUT_DIR.glob("*.csv"))
    if monitor_files:
        print("OpenDSS reports:")
        for report in monitor_files:
            print(f"  {report.name}")
    else:
        print("OpenDSS did not create CSV reports. Check the console log and confirm the model loaded.", file=sys.stderr)
    if result.stdout:
        print(result.stdout[-6000:])
    if result.stderr:
        print(result.stderr[-3000:], file=sys.stderr)
    return result.returncode if result.returncode else (0 if monitor_files else 3)


if __name__ == "__main__":
    raise SystemExit(main())
