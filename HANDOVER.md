# Handover — Legacy of Chroma (Grok session)

**Date:** 2026-08-17  
**Repo:** https://github.com/bladesilburwolf-star/Legacy_Of_Chroma_New  
**Focus this session:** Tomb dungeon **d_1.html** as the golden path for SFPaint → Three.js + combat/loot.

Audience: **Claude** (dungeon palaces / MAP scale-up), **Copilot / Gemini** (field / tools), humans.

---

## What works (verify first)

1. Serve root: `python3 -m http.server 8000`
2. Open `d_1.html`
3. Expect:
   - Green **temple_green** tiled walls/floor (not stretched full atlas)
   - Player on floor (not floating; spawn uses local `y: 0` — SpriteSystem adds `FLOOR_Y`)
   - Enemies visible (billboards promoted to combat entities)
   - **J** sword; **1/2/3** weapon tiers; hearts HUD
   - **F** on small chest → small key; door unlock; jars; big chest → metal sword
   - BGM `assets/music/13 Dank Dungeons.mp3` after first keypress

If something 404s: hard-refresh and confirm paths under `assets/` (see tree). Cleanup may have renamed folders — aliases in `sfmap_loader_v3.js` map old Chroma paths → current tree.

---

## Critical files (push if missing on remote)

```
d_1.html
src/engine/Three_js_system/sfmap_loader_v3.js
src/engine/Three_js_system/combat_system_v3.js
src/engine/Three_js_system/inventory_system_v3.js
src/engine/Three_js_system/audio_system_v3.js
src/engine/Three_js_system/sprite_system_v3.js   # FLOOR_Y / worldY / player paths
assets/maps/lttp_palace1_legacy.json
assets/tilesets/temple_green/temple_green.tiles.png
assets/tilesets/temple_green/temple_green.dat
```

Artifacts from this chat also live under the agent `artifacts/` copy for recovery.

---

## Architecture notes

### Coordinate / floor rule
`SpriteSystemV3.create` positions with:

`y_world = localY + FLOOR_Y + height/2` (unless `worldY: true`)

**Never** pass world-space Y into createPlayer/createEnemy unless `worldY: true`.  
D1 uses `position: { y: 0 }` for player.

### Map loader
- Grid cells: `1/2/6` solid, `0` floor, `3` pit, `4` water  
- Merges solid runs into fewer boxes  
- Things → billboards; `resolveAsset('enemy/slime.png')` → `assets/enemy/slime.png`  
- Fallbacks in `ASSET_ALIASES` for remake folders (`enemies/`, `chests/`)  
- `autoSpriteSize(type, cellSize, imgW, imgH)` for jars/chests/doors/enemies  

### Combat
- `CombatSystemV3.create(scene, loader, player, opts)`  
- Enemies need `{ mesh, material, hp, dispose?, faceCamera? }`  
- `setWeaponDamage(n)` from `InventorySystemV3.weaponDamage(equippedId)`  
- Attack keys: feed `keys.attack` boolean into `combat.update`  

### Loot / keys
- Inventory: `give / take / has / count / equip`  
- Catalog ids: `woodsword`, `metalsword`, `plasmasword`, `smallkey`, `bigkey`, `chromackey`, …  
- D1 interact table is inline in `d_1.html` (`openChest`, `tryDoor`, `breakJar`) — promote to `interact_system_v3.js` when reused across dungeons  

### Audio
- `AudioSystemV3.create()` → `playMusic(url)`, `sfx(url)`, `stopMusic()`  
- Autoplay: start on first user gesture  

---

## For Claude (palaces)

- Source maps: `assets/maps/palace*_legacy.json`, `zelda2_palace1_legacy.json`, tools repo `Chroma_Z_Game_Tools`  
- Scale: field is large (TP-ish); dungeons use `cellSize` ~36–40 in loader opts  
- Copy **d_1.html** pattern: load JSON → promote enemies → combat + interactables → exit warp  
- Tilesets: `tilesets/temple_*`, `dungeon_*`, `ice_*`, `palace_purple` — pair `.dat` UV with `.tiles.png` like temple_green `block` @ 128,272 and `floor.1` @ 208,96  
- Stairs (`STAIRSUP` / `STAIRSDOWN`) exist on maps; multi-floor switch not finished in D1  

## For field / other agents

- `index.html` owns overworld merge, terrain paint, skybox, shrines, weather  
- Tomb door should warp to **`d_1.html`** (not only boss)  
- Player state: `PlayerStateV3` localStorage for cross-HTML continuity  

---

## Known gaps / next

1. **Floor 2** — stairs + `floorIndex` in loader  
2. **Boss room** — `d_1boss.html` still separate; link after BGDOOR + story flags  
3. **Interact module** — lift D1 chest/door/jar logic into shared `interact_system_v3.js`  
4. **Sprite polish** — door/chest art scale; optional vs-depth with walls  
5. **Save** — persist keys/equipment via PlayerState when leaving D1  
6. **index.html** — ensure tomb collision + warp match current D1 entry  

---

## Division of labor (owner intent)

| Area | Owner |
|------|--------|
| Field, mechanics, loader, D1 reference | Grok session (this doc) |
| Palace dungeons from MAP/JSON, larger scale | Claude |
| Tools / converters | Chroma_Z_Game_Tools + Gemini sandbox |
| Repo hygiene | Human + Copilot |

---

## Don’t regress

- Do not pass `spawn.y` from loader straight into `createPlayer` without stripping `FLOOR_Y`  
- Do not stretch entire tileset atlas on walls — always **crop** tile patterns  
- Prefer promoting loaded billboards for enemies over re-fetching broken paths  
- Keep a static server; don’t rely on `file://`  

---

*End handover. Play D1 once after pull, then branch palace work from the same loader API.*
