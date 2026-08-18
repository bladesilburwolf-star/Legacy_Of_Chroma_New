# Legacy of Chroma

Open-zone Zelda-inspired action adventure rebuilt on **Three.js** + modular **V3** systems.  
Successor direction after Neondromeda / Chromadromeda — same creative DNA, unified overworld and SFPaint dungeons.

**Repo:** `bladesilburwolf-star/Legacy_Of_Chroma_New`

---

## Quick start

```bash
# serve from project root (needed for ES-less script loads + audio)
python3 -m http.server 8000
# open http://localhost:8000/index.html   (overworld)
# open http://localhost:8000/d_1.html     (Tomb / Palace 1 reference dungeon)
```

Requirements: modern browser, local static server (file:// breaks fetch/audio).

---

## Controls (D1 reference)

| Input | Action |
|-------|--------|
| **WASD** | Move |
| **Arrow keys** | Orbit camera |
| **J / B / Space** | Sword attack |
| **1 / 2 / 3** | Equip Wood / Metal / Plasma sword |
| **F** | Interact (chest, door, jar, exit) |
| **Esc** | Return to field (`index.html`) |

First keypress starts dungeon BGM (browser autoplay policy).

---

## Layout

```
index.html              Overworld hub (merged sectors, terrain, shrines…)
d_1.html                Tomb / Eastern-style palace — reference dungeon
d_1boss.html … d_8.html Other dungeon shells
src/engine/Three_js_system/
  room_builder_v3.js
  sprite_system_v3.js
  camera_controls_v3.js
  combat_system_v3.js
  inventory_system_v3.js
  sfmap_loader_v3.js      SFPaint/JSON → walls + billboards
  audio_system_v3.js
  player_state_v3.js
  … terrain, skybox, weather, shrines, options …
assets/
  maps/                   *.json legacy palace/field maps
  tilesets/               Solarus-style (temple_green, ice_*, dungeon_*, …)
  enemy/ + enemies/       Chroma + remake sprites
  storage/ + chests/      Chest closed/open art
  doors/ obstacles/ items/
  music/ fanfare/         BGM + SFX
  player/ inventory/ hud/
```

Legacy Java tools (editor / raycast engine) may ship alongside for map authoring:

- `SFMapEditor.java` — brush map editor  
- `LOCWTTP.java` — classic SFReactor play view  

---

## Dungeon pipeline

1. Author or convert map → `assets/maps/*_legacy.json` (SFPAINT / Chroma Z schema).  
2. Load with `SFMapLoaderV3.load(scene, loader, url, { cellSize, wallHeight, … })`.  
3. Walls merge into solid boxes; things become billboards.  
4. `temple_green.tiles.png` + `.dat` crops supply wall/floor tiles (`block`, `floor.1`, …).  
5. Wire combat enemies + **F** interactables in the room HTML (see `d_1.html`).

### Thing types used in D1

| Type | Behavior |
|------|----------|
| ENEMY | Combat HP by archetype |
| CHESTSM / CHESTBG | Key / metal sword |
| SMDOOR / BGDOOR | Small key / boss key |
| JAR | Break → chance heart |
| BIGITEM | Chromac key / triforce piece |
| STAIRSUP / DOWN | Placeholder (multi-floor next) |

---

## Engine modules (V3)

| Module | Role |
|--------|------|
| **RoomBuilderV3** | Shapes, layers, solid boxes |
| **SpriteSystemV3** | Billboard player/enemies (`FLOOR_Y` aware) |
| **CombatSystemV3** | Move, sword swing, HP, knockback, weapon damage |
| **InventorySystemV3** | Items, equip, keys, catalog + damage |
| **SFMapLoaderV3** | JSON grid → geometry + auto sprite scale |
| **AudioSystemV3** | Loop BGM + one-shot SFX |
| **CameraControlsV3** | Arrow-orbit third person |
| **PlayerStateV3** | localStorage cross-room flags |

---

## License / assets

Project code is yours under the repo `LICENSE.md`.  
Music/tileset packs (e.g. Solarus ALTTP-style, sequenced themes) retain their original licenses — keep attribution when redistributing.

---

## Status

Playable **reference dungeon (D1)** with tileset walls, combat, loot, keys, doors, music.  
Overworld `index.html` continues field systems (terrain, day/night, shrines).  
Multi-floor stairs, full palace set, and field↔dungeon save polish are ongoing.
