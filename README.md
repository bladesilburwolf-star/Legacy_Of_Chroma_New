# Neondromeda New

Neondromeda New is a browser-based neon action-exploration prototype built around a custom Three.js scene system, retro arcade styling, and a Python launcher for local web serving. The project mixes overworld traversal, dungeon rooms, procedural generation experiments, and arcade-style mini-games into one cyberpunk exploration sandbox.

## Current branch

This repository is currently on the `alpha4` branch. The intent is to keep this as the working baseline for the next round of cleanup, testing, and feature integration while removing stale AI branches from the Git workflow.

## Overview

The project is intentionally a static front-end game prototype, not a packaged app. It runs from a local HTTP server and loads HTML/JavaScript pages that construct a 3D scene, spawn enemies, trigger events, and swap between sectors and dungeon spaces.

Key areas of the project include:

- `index.html` for the central hub or world launcher
- sector pages such as `road_1.html`, `road_2.html`, `south_sector.html`, and `secret_factory.html`
- dungeon pages such as `d_1.html`, `d_3.html`, `d_4.html`, `d_5.html`, `d_6.html`, `d_7.html`, and `d_8.html`
- arcade pages under `arcade_room_1.html` and `arcade_room_2.html`
- engine modules under `src/engine/Three_js_system/`
- art, sprites, textures, and HUD assets under `assets/`
- notes and session logs under `logs/`

## Stack

- HTML / CSS / JavaScript
- Three.js (r128 via CDN)
- Python 3 launcher server (`main.py`)
- Optional PyAudio-based audio monitoring and legacy audio hooks
- Asset-heavy procedural and sprite-driven world generation

## Repository structure

```text
.
├── README.md
├── main.py
├── run.sh
├── index.html
├── road_1.html
├── road_2.html
├── south_sector.html
├── d_1.html
├── d_3.html
├── d_4.html
├── d_5.html
├── d_6.html
├── d_7.html
├── d_8.html
├── arcade_room_1.html
├── arcade_room_2.html
├── assets/
├── src/
├── arcade_1/
├── arcade_2/
├── logs/
├── ENGINE_CORE.txt
├── NEONDROMEDA_NEW_STRUCTURE.txt
├── favicon.ico
└── ...
```

## Running locally

From the repository root:

```bash
python3 main.py
```

Or:

```bash
./run.sh
```

This starts a local HTTP server on port 8000 and opens the game in the browser. If the port is busy:

```bash
fuser -k 8000/tcp
```

## Controls

- `WASD` / arrow keys: move
- `Space`: attack
- `E`: interact / reveal secrets / open triggers
- `Esc`: exit or return to the hub

## Notes and current goals

This prototype is still in a build-out phase. Current priorities are typically:

- verify page stability and scene loading
- reduce duplicate code between pages
- clean up collision / floor / camera behavior
- improve procedural dungeon generation quality
- keep notes and handoff logs in sync so AI sessions can continue without losing context

## Troubleshooting

- Use a local HTTP server; opening HTML files directly may fail or behave inconsistently.
- If audio backend warnings appear, try `SDL_AUDIODRIVER=dummy` when launching the Python server.
- If a page is visually glitchy or missing geometry, check the logs and confirm the matching page and module versions are being served.
- If procedural or generated rooms tear or clip, review texture filtering and collider setup on the relevant generator module.

## Status

This repository is best treated as an experimental game/prototype codebase with evolving systems and multiple parallel AI experiment branches. The goal for `alpha4` is to keep a clean working branch and continue improvements one step at a time with notes and changelogs preserved in the repo.
