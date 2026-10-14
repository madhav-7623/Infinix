#!/usr/bin/env python3
"""Build a self-contained OpenDSS results page from the exported CSV reports."""

from __future__ import annotations

import csv
import json
import math
from datetime import datetime
from pathlib import Path


ROOT = Path(__file__).resolve().parent
OUTPUT = ROOT / "output"
STATIC = ROOT.parent / "src" / "main" / "resources" / "static"
NOMINAL_PHASE_VOLTS = 480 / math.sqrt(3)
TRANSFORMER_RATING_KVA = 63.0


def read_rows(path: Path) -> list[dict[str, str]]:
    if not path.is_file():
        raise SystemExit(f"Missing OpenDSS export: {path}\nRun the feeder simulation first.")
    with path.open(newline="", encoding="utf-8-sig") as handle:
        return [
            {str(key).strip().strip('"').strip(): str(value or "").strip().strip('"') for key, value in row.items()}
            for row in csv.DictReader(handle)
        ]


def number(row: dict[str, str], key: str) -> float:
    try:
        return float(row[key])
    except (KeyError, ValueError) as exc:
        raise SystemExit(f"Could not read numeric column {key!r} from OpenDSS export.") from exc


def build_html() -> str:
    voltage_rows = read_rows(OUTPUT / "ChargeSync_Mon_evbusvoltage_1.csv")
    power_rows = read_rows(OUTPUT / "ChargeSync_Mon_transformerpower_1.csv")
    meter_rows = read_rows(OUTPUT / "ChargeSync_EXP_METERS.CSV")
    if not voltage_rows or not power_rows or not meter_rows:
        raise SystemExit("One or more OpenDSS export files contain no data rows.")

    phase_voltage = [
        [number(row, "t(sec)"), number(row, "V1"), number(row, "V2"), number(row, "V3")]
        for row in voltage_rows
    ]
    phase_current = [
        [number(row, "t(sec)"), number(row, "I1"), number(row, "I2"), number(row, "I3")]
        for row in voltage_rows
    ]
    transformer_kva = [
        [
            number(row, "t(sec)"),
            number(row, "S1 (kVA)") + number(row, "S2 (kVA)") + number(row, "S3 (kVA)"),
        ]
        for row in power_rows
    ]

    all_volts = [value for point in phase_voltage for value in point[1:]]
    all_amps = [value for point in phase_current for value in point[1:]]
    peak_kva = max(transformer_kva, key=lambda point: point[1])
    meter = next((row for row in meter_rows if row.get("Meter", "").strip() == "FEEDERMETER"), meter_rows[0])
    sample_minutes = round(number(voltage_rows[0], "t(sec)") / 60)
    generated = datetime.now().astimezone().strftime("%d %b %Y, %H:%M %Z")
    data = json.dumps(
        {"voltage": phase_voltage, "current": phase_current, "transformer": transformer_kva},
        separators=(",", ":"),
    )
    min_volts, max_volts = min(all_volts), max(all_volts)
    peak_kva_pct = peak_kva[1] / TRANSFORMER_RATING_KVA * 100
    hours = peak_kva[0] / 3600
    peak_time = f"{int(hours):02d}:{int(round((hours % 1) * 60)) % 60:02d}"

    return rf'''<!doctype html>
<html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>ChargeSync · OpenDSS results</title>
<style>
:root{{color-scheme:dark;--bg:#0b1220;--panel:#121e32;--line:#263650;--text:#eaf1ff;--muted:#9eb0cc;--teal:#35d0ad;--blue:#7da7ff;--gold:#ffc96b;--purple:#c59aff}}
*{{box-sizing:border-box}}body{{margin:0;background:var(--bg);color:var(--text);font:15px Inter,Segoe UI,Arial,sans-serif}}header{{padding:24px max(18px,calc((100% - 1120px)/2));background:#111c30;border-bottom:1px solid var(--line)}}h1{{font-size:25px;margin:0}}header p{{color:var(--muted);margin:7px 0 0}}main{{max-width:1120px;margin:22px auto;padding:0 18px}}a{{color:#9fd7ff}}.topline{{display:flex;justify-content:space-between;gap:12px;align-items:center;margin-bottom:16px;flex-wrap:wrap}}.badge{{color:#8cf0c9;background:#123b35;border-radius:999px;padding:7px 11px;font-size:12px;font-weight:700}}.subtle{{font-size:12px;color:var(--muted)}}.cards{{display:grid;grid-template-columns:repeat(4,1fr);gap:12px}}.card,.panel{{background:var(--panel);border:1px solid var(--line);border-radius:14px;padding:16px}}.card span{{font-size:12px;color:var(--muted)}}.card strong{{display:block;font-size:23px;margin:8px 0 3px}}.card small{{font-size:12px;color:var(--muted)}}.grid{{display:grid;grid-template-columns:1fr 1fr;gap:14px;margin-top:14px}}.panel h2{{font-size:16px;margin:0 0 4px}}.panel p{{font-size:12px;color:var(--muted);margin:5px 0 12px;line-height:1.5}}svg{{display:block;width:100%;height:auto;overflow:visible}}.legend{{display:flex;gap:14px;flex-wrap:wrap;color:var(--muted);font-size:12px;margin-top:7px}}.key{{display:inline-block;width:9px;height:9px;border-radius:50%;margin-right:5px;background:var(--teal)}}.key.blue{{background:var(--blue)}}.key.gold{{background:var(--gold)}}.key.purple{{background:var(--purple)}}.notes{{margin-top:14px;line-height:1.65;color:var(--muted);font-size:13px}}.notes b{{color:var(--text)}}footer{{margin:18px 0 32px;color:var(--muted);font-size:12px;line-height:1.6}}@media(max-width:820px){{.cards{{grid-template-columns:repeat(2,1fr)}}.grid{{grid-template-columns:1fr}}}}@media(max-width:480px){{.cards{{grid-template-columns:1fr 1fr}}.card{{padding:12px}}.card strong{{font-size:19px}}}}
</style></head><body>
<header><h1>⚡ ChargeSync · OpenDSS feeder results</h1><p>Real exported time-series from the 24-hour EV charging feeder simulation.</p></header>
<main><div class="topline"><span class="badge">✓ SOLVED · DAILY MODE</span><span class="subtle">{len(voltage_rows)} five-minute samples · report built {generated}</span></div>
<section class="cards">
<div class="card"><span>Daily voltage range</span><strong>{min_volts:.1f}–{max_volts:.1f} V</strong><small>{min_volts / NOMINAL_PHASE_VOLTS:.3f}–{max_volts / NOMINAL_PHASE_VOLTS:.3f} pu · phase to neutral</small></div>
<div class="card"><span>Transformer peak</span><strong>{peak_kva[1]:.1f} kVA</strong><small>{peak_kva_pct:.1f}% of 63 kVA · at {peak_time}</small></div>
<div class="card"><span>Energy meter · daily</span><strong>{number(meter, 'kWh'):.0f} kWh</strong><small>Maximum {number(meter, 'Max kW'):.0f} kW · {number(meter, 'Max kVA'):.0f} kVA</small></div>
<div class="card"><span>Peak phase current</span><strong>{max(all_amps):.1f} A</strong><small>EV bus monitor · three phases</small></div>
</section>
<section class="grid">
<article class="panel"><h2>EV bus voltage</h2><p>Three phase-to-neutral readings from the secondary line monitor, normalized to {NOMINAL_PHASE_VOLTS:.1f} V nominal.</p><svg id="voltageChart" viewBox="0 0 760 250" role="img" aria-label="EV bus voltage by phase during the day"></svg><div class="legend"><span><i class="key"></i>Phase 1</span><span><i class="key blue"></i>Phase 2</span><span><i class="key gold"></i>Phase 3</span></div></article>
<article class="panel"><h2>Transformer loading</h2><p>Sum of the three phase kVA readings from the service transformer power monitor.</p><svg id="powerChart" viewBox="0 0 760 250" role="img" aria-label="Transformer apparent power throughout the day"></svg><div class="legend"><span><i class="key purple"></i>Three-phase total</span><span class="subtle">63 kVA nameplate</span></div></article>
</section>
<section class="grid">
<article class="panel"><h2>EV bus phase current</h2><p>Current magnitude from the same 288 samples.</p><svg id="currentChart" viewBox="0 0 760 250" role="img" aria-label="EV bus current by phase during the day"></svg><div class="legend"><span><i class="key"></i>Phase 1</span><span><i class="key blue"></i>Phase 2</span><span><i class="key gold"></i>Phase 3</span></div></article>
<article class="panel notes"><h2>How to read this report</h2><p><b>Voltage base:</b> the model uses a 0.48 kV line-to-line secondary, or 277.1 V phase-to-neutral. The voltage chart shows all phases for every five-minute interval.</p><p><b>Transformer peak:</b> sums S1, S2, and S3 at each sample. Meter values are reported separately from the OpenDSS energy-meter export.</p><p><b>Snapshot versus daily minimum:</b> the OpenDSS GUI summary shows the currently selected solution point. This report’s voltage range covers the complete daily trace, so its minimum can be lower than the GUI’s final-hour snapshot.</p><p><b>Update after another run:</b> with Python 3.10+ installed, export the named monitors and meter to <code>dss\output</code>, then run <code>py -3 dss\build_results_report.py</code>. The page is self-contained and opens offline.</p></article>
</section>
<footer>Source files: ChargeSync_Mon_evbusvoltage_1.csv, ChargeSync_Mon_transformerpower_1.csv, and ChargeSync_EXP_METERS.CSV. Synthetic hackathon feeder; not a utility planning or protection study. Built {generated}.</footer></main>
<script>
const report={data};
const colors=['#35d0ad','#7da7ff','#ffc96b'];
function draw(id, rows, series, min, max, unit, convert=(x)=>x, reference=null){{
 const svg=document.getElementById(id), W=760,H=250,L=62,R=18,T=16,B=34, pw=W-L-R,ph=H-T-B;
 const x=(sec)=>L+sec/86400*pw, y=(v)=>T+(max-v)/(max-min)*ph;
 let out='';
 for(let i=0;i<=4;i++){{let val=min+(max-min)*i/4, yy=y(val);out+=`<line x1="${{L}}" y1="${{yy}}" x2="${{W-R}}" y2="${{yy}}" stroke="#263650"/><text x="${{L-9}}" y="${{yy+4}}" text-anchor="end" fill="#9eb0cc" font-size="11">${{val.toFixed(2)}} ${{unit}}</text>`;}}
 for(let h=0;h<=24;h+=6){{let xx=L+h/24*pw;out+=`<line x1="${{xx}}" y1="${{T}}" x2="${{xx}}" y2="${{H-B}}" stroke="#263650"/><text x="${{xx}}" y="${{H-10}}" text-anchor="middle" fill="#9eb0cc" font-size="11">${{String(h).padStart(2,'0')}}:00</text>`;}}
 if(reference!==null){{let yy=y(reference);out+=`<line x1="${{L}}" y1="${{yy}}" x2="${{W-R}}" y2="${{yy}}" stroke="#8298b9" stroke-dasharray="5 5"/>`;}}
 series.forEach((s)=>{{let points=rows.map(row=>`${{x(row[0])}},${{y(convert(row[s.index]))}}`).join(' ');out+=`<polyline points="${{points}}" fill="none" stroke="${{s.color}}" stroke-width="2.2" stroke-linejoin="round" stroke-linecap="round"/>`;}});
 svg.innerHTML=out;
}}
const pu=(v)=>v/{NOMINAL_PHASE_VOLTS:.10f};
draw('voltageChart',report.voltage,[{{index:1,color:colors[0]}},{{index:2,color:colors[1]}},{{index:3,color:colors[2]}}],0.95,1.01,'pu',pu,1.0);
const kvaMax=Math.max(63,...report.transformer.map(p=>p[1]));
draw('powerChart',report.transformer,[{{index:1,color:'#c59aff'}}],0,Math.ceil(kvaMax/10)*10,'kVA',(v)=>v,63);
const ampsMax=Math.max(...report.current.flatMap(p=>p.slice(1)));
draw('currentChart',report.current,[{{index:1,color:colors[0]}},{{index:2,color:colors[1]}},{{index:3,color:colors[2]}}],0,Math.ceil(ampsMax/10)*10,'A');
</script></body></html>'''


def main() -> None:
    html = build_html()
    ROOT.joinpath("results.html").write_text(html, encoding="utf-8")
    STATIC.mkdir(parents=True, exist_ok=True)
    (STATIC / "opendss-results.html").write_text(html, encoding="utf-8")
    print(f"Updated {ROOT / 'results.html'}")
    print(f"Updated {STATIC / 'opendss-results.html'}")


if __name__ == "__main__":
    main()
