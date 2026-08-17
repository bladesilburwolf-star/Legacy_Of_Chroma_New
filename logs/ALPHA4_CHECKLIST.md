# Alpha4 Working Checklist

This document is a lightweight handoff file for the `alpha4` branch. The aim is to keep tasks focused, one item at a time, with notes captured so future AI sessions or collaborators can pick up where we left off.

## Current branch

- `alpha4`
- Working baseline: clean branch off `main`

## Current status

- README cleaned and consolidated
- Project structure and working notes are being tracked in logs
- Active work is focused on one improvement at a time rather than broad multi-feature churn

## Checklist

### 1. Stabilize the pages
- [ ] Verify all main HTML pages still load from the local server
- [ ] Confirm the hub and dungeon pages use the correct script/module versions
- [ ] Check for stale references and duplicate page logic

### 2. Dungeon and procedural generation
- [ ] Verify `d_6.html` and procedural dungeon generation still work in the current repo state
- [ ] Review floor collision, wall collision, and texture quality
- [ ] Confirm procedural room spawns and exit paths feel playable

### 3. Collision and movement fixes
- [x] Validate player movement against floor and walls
- [x] Check for sinking or clipping under generated floors
- [x] Confirm enemy collision and movement remain stable

#### D1 geometry pass
- Rebuilt the three-floor stair layout in `d_1.html` to reduce the stair/floor overlap and tighten each landing zone.
- Fixed the global solid-box builder so each collider stores Y bounds and the collision checks respect floor-specific height bands instead of treating every solid as a full-height wall.
- This should keep the player from clipping through the step volumes and prevent the upper/lower stair sections from colliding across floors.

### 4. Audio and launcher hygiene
- [ ] Confirm the launcher behavior is stable without noisy ALSA/JACK warnings
- [ ] Keep static server startup simple and consistent for local playtest
- [ ] Avoid runtime audio crashes when devices are unavailable

### 5. Documentation and handoff
- [ ] Keep README accurate to the current repo state
- [ ] Log each completed task in this folder as a short status note
- [ ] Use the next task list to avoid reworking old AI branches or stale files

## Notes for the next pass

- Work one page or one system at a time.
- Prefer small, tested changes before adding new features.
- Keep the default working branch clean: `alpha4`.
- If a page or module is unstable, record the exact symptom and the likely root cause before editing again.
