# Issue Report: Live Asset Distribution Dots Not Showing Details

## Problem Summary

In the Dashboard card **Live Asset Distribution**, clicking on a map dot did not show device details (speed, status, battery, officer, timestamp).

## Root Cause

The dots in the Dashboard widget were rendered as visual markers only. There was:
- no click handler on marker dots
- no selected state for a clicked asset
- no detail panel bound to selected telemetry

Note: The dedicated `Live Map` tab already had click-to-detail behavior, but the Dashboard widget did not.

## Resolution Applied

Updated `dashboard.html` Dashboard view to:

1. Add local selected state
- Added `selectedAsset` state in `DashboardView`.

2. Make dots interactive
- Added `cursor-pointer` and `onClick={() => setSelectedAsset(t)}` on each marker dot.

3. Show detail panel on selection
- Added a compact info card in the map area that displays:
  - Device ID
  - Status
  - Speed
  - Battery
  - Officer
  - Timestamp
- Added `Close` action to clear selected asset.

4. Keep served UI in sync
- Synced updated `dashboard.html` with:
  - `device-service/src/main/resources/static/dashboard.html`

## Verification

- Clicking a dot in **Live Asset Distribution** now opens a detail panel.
- Panel shows expected fields including speed and status.
- User-confirmed behavior: "It is working absolutely fine".

## Prevention / Future Guidance

1. Reuse behavior across similar widgets
- If one map (Live Map tab) supports click details, keep Dashboard map behavior consistent.

2. Add UI acceptance checks for interactivity
- Dashboard marker click opens detail panel.
- Panel close action works.
- Values render for normal + SOS + high-speed devices.

3. Keep source and served static copies aligned
- If UI is served from `device-service` static folder, sync changes from root `dashboard.html`.

4. Add regression checklist after UI updates
- Open `http://localhost:8081/dashboard.html`
- Confirm dots render.
- Click 3-5 random markers and verify details.

## Quick Troubleshooting if It Reappears

1. Ensure latest UI is served
```bash
curl -i http://localhost:8081/dashboard.html
```

2. Rebuild/restart device-service
```bash
docker compose up -d --build --force-recreate device-service
```

3. Verify telemetry exists
```bash
curl -sS http://localhost:8083/api/v1/telemetry/count
```

If count is `0`, resolve telemetry pipeline first (`kafka`, `sim-service`, `event-service`).
