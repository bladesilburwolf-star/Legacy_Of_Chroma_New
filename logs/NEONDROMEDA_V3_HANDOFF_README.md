# Neondromeda New - RoomBuilderV3 + SpriteSystemV3 + Arcade Games
Updated: 2026-08-14 by Meta AI session

## Core Modules (Grok built)

### room_builder_v3.js - RoomBuilderV3
Fixed clamp() implementation 2026-05-13 (was stub with empty body)
- FLOOR_Y = -100
- DEFAULT_LAYER_Z: L1 -120, L2 -40, L3 0, L4 65, UI 120
- GEO: Cube (solid walls/platforms SizeZ depth), VCylinder (pipes/lamps), HalfVCylinder (gears/pits negative SizeZ), HalfHCylinder (light shafts/arches), Default (billboard trees/ghosts always faceCamera)
- shape() helper: id, layer, geo, sizeZ, scale {x,y,z}, offset, rot, alpha, solid, color, tags, position
- build(scene, shapes, {layerZ}) returns {group, meshes Map, solidBoxes AABB[], billboards[], layerZ}
- faceBillboards(room, camera) - call every frame for Default geo
- clamp(pos, room) - NOW REAL: expands solidBoxes by PLAYER_R 18, pushes on smallest penetration axis. Previously empty stub in v3.
- PRESETS.hub = empty stub, ignore

### sprite_system_v3.js - SpriteSystemV3
- create(options): textures can be Array (non-directional) or {down,up,left,right} with strings or Textures, width/height, position, layer, animSpeed
- createPlayer(loader, {position, layer}) - hardcoded Neondromeda paths assets/player/SWORDUP.png etc
- createEnemy(texturePathOrList, loader, {position, layer, width, height})
- API: mesh, position (live Vector3), setDirection(dir), setMoving(bool), update(), faceCamera(camera), setTextures(), distanceTo(), dispose() - CALL dispose() when removing (HD6450 target)
- Reads RoomBuilderV3.FLOOR_Y if present

## Load Order (all pages)
```html
<script src="https://cdnjs.cloudflare.com/ajax/libs/three.js/r128/three.min.js"></script>
<script src="room_builder_v3.js"></script>
<script src="sprite_system_v3.js"></script>
```

## Room Status as of this session (Meta AI)

| File | Uses build()? | Shapes | Uses SpriteSystemV3 | Notes |
|---|---|---|---|---|
| index.html (hub) | YES | 4x VCylinder pillars 22x70x22 #00cc66 SizeZ22 solid + center HalfHCylinder ring | YES | Original had shapes, kept |
| road_1.html | **NOW YES** (was FLOOR_Y only) | 2x wall Cube 20x90x24 #1a2f1a solid L2 + 2x VCylinder pillars + end cap Cube #0a1a0a | YES | Patched by Meta AI - corridor to arcade1 |
| road_2.html | **NOW YES** | 2x wall Cube + light shaft HalfHCylinder #55ff88 alpha 0.35 SizeZ200 L1 + pillar | YES | Patched - greener fog |
| arcade_room_1.html | **NOW YES** | 3x cabinet Cube 80x100x40 #ff0055/#00ffff/#ffff00 @ Z -220 + back/side walls Cube #0a0a0a solid | YES | Patched - classic games terminal |
| arcade_room_2.html | **NOW YES** | 2x cab Cube #00ff66 + 2x tree Default billboards #44ff66 L4 + back wall | YES | Patched - forest variant |
| INTO THE PIT (arcade_room_3) | YES - new game | Lamp posts VCylinder #00aaff, pits HalfVCylinder negative SizeZ -30 black + torus rim #44ff00, platforms Cube #006600, trees Default L4, arches HalfHCylinder white | YES mockup | Big one, field 1000x1000, 5 source images |
| CYPAK / CYBER PAC (arcade_room_4) | YES - new game | Yellow cab Cube 100x140x70 #ffcc00 SizeZ70, 21x wall Cube #00ffff SizeZ30 L2 maze from CYPAK MAZE 11, dots 8x8 #aaffff, pellets 16 #00ff88, ghosts Default billboards | YES mockup | Small SECRET_FACTORY size, 600x600, 4 source images |

## Fixed Bugs
- clamp() stub: was `solidBoxes.forEach((b) => {})` empty - now real AABB with PLAYER_R 18, minX/maxX/minZ/maxZ expansion, smallest penetration push
- floorMesh rotation inconsistency: index.html -PI/2 vs others PI/2 - harmless DoubleSide but flagged
- Missing dispose() in clearEnemies() - now calls e.dispose()
- Missing faceBillboards for Default geo - now called every frame
- Missing light + shadow setup - added DirectionalLight + AmbientLight + shadowMap enabled + cast/receive

## Arcade Games Pipeline (for Gemini - smaller games)

Into The Pit was big (Arcade Room 2 main). Rest are SECRET_FACTORY size.

For each new arcade game upload:
1. Main field art (topdown like PIT.jpg or maze like Cypak_Maze_12.jpg)
2. Title art (INTO_THE_PIT_TITLE.jpg style)
3. Interior/variant optional (PITHOUSE.jpg)

Convert with:
- Walls = Cube L2 solid true SizeZ 30-80 color from art
- Pits/holes = HalfVCylinder SizeZ -30 negative depth, not solid, black + colored rim torus
- Lamps/cabs = VCylinder solid true SizeZ 12-22
- Trees/ghosts = Default billboard L4 faceCamera
- Platforms = Cube L2 solid SizeZ 20
- Arches/doors = HalfHCylinder white L3 not solid tags doorway
- Use same clamp + faceBillboards pattern
- Save/Load 3DN JSON button for shapes array

## Git
Repo: bladesilburwolf-star/neondromeda_new (128 MB, pushed via GitKraken)
Patched files ready to add: road_1.html, road_2.html, arcade_room_1.html, arcade_room_2.html, room_builder_v3.js (clamp fixed)
New arcade artifacts: pit_3d_arcade_game, cypak_arcade_game -> should become arcade_room_3.html (INTO_THE_PIT) and arcade_room_4.html (CYPAK)

## Open Tasks (Gemini)
- Apply patched road_1/2/arcade_1/2 to repo (git add + push)
- Define arcade_room_3.html INTO_THE_PIT with full 5 images textures, not just procedural colors
- Define arcade_room_4.html CYPAK with real maze from CYPAK MAZE 11 image parsing
- Wire SECRET_FACTORY.jpg inverted test back as reference for 3dSen Cube/VCylinder extrusion
- Optional: Build 3DVR web browser / Linux Desktop shell (hint from user: floor.jpg desktop, chromac gear window manager, skybox void, ladders = filesystem)
