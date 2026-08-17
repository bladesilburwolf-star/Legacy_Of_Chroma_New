# RoomBuilder V3 + SpriteSystem V3 — Module Reference

Applies to: `index.html`, `road_1.html`, `road_2.html`, `arcade_room_1.html`,
`arcade_room_2.html`. Written for whichever AI picks this up next
(Claude/Gemini/Grok/ChatGPT/Codex) — no assumed context beyond this file.

## Load order (all five pages)

```html
<script src="https://cdnjs.cloudflare.com/ajax/libs/three.js/r128/three.min.js"></script>
<script src="room_builder_v3.js"></script>
<script src="sprite_system_v3.js"></script>
```

`sprite_system_v3.js` reads `RoomBuilderV3.FLOOR_Y` if present (falls back to
`-100` on its own), so load order matters: `room_builder_v3.js` before
`sprite_system_v3.js`.

## room_builder_v3.js — `RoomBuilderV3`

Builds static room geometry from an array of "shape" descriptors instead of
hand-typed `THREE.Mesh` calls. Geo types: `Cube`, `VCylinder`,
`HalfVCylinder`, `HalfHCylinder`, `Default` (flat always-facing-camera
billboard). Shapes are bucketed into Z-depth **layers** (`L1` far bg → `UI`
always on top) via `DEFAULT_LAYER_Z`, overridable per room.

```js
const S = RoomBuilderV3.shape; // helper, fills in sane defaults
const shapes = [
  S({ id:'pillar_ne', layer:'L2', geo:'VCylinder',
      scale:{x:22,y:70,z:22}, sizeZ:22,
      position:{x:160,y:35,z:-160}, color:'#00cc66', solid:true }),
];
const room = RoomBuilderV3.build(scene, shapes, {
  layerZ: { L1:-80, L2:-20, L3:0, L4:40, UI:100 }
});

// every frame:
RoomBuilderV3.faceBillboards(room, camera); // faces any geo:'Default' shapes at the camera
```

**`RoomBuilderV3.clamp(pos, room)` is currently a no-op stub.** The loop
over `room.solidBoxes` exists but the body is empty — `solid:true` cubes
do NOT block player movement yet. Don't assume collision works just
because a shape has `solid:true`. If you're the one implementing real
collision, that's the function to fill in.

`RoomBuilderV3.PRESETS.hub` exists but is an empty stub (`shapes: []`) —
not used by any current page. Ignore it unless you're deliberately
building it out.

## sprite_system_v3.js — `SpriteSystemV3`

Owns per-sprite animation state (directional frame sets, walk-cycle
timing, billboard-facing) so pages don't each reimplement it. It does
**not** own movement, warps, collision, or camera logic — that's still
each page's own game code.

```js
const player = SpriteSystemV3.createPlayer(loader, {
  position: { x:0, y:0, z:0 }, layer:'L3'
});
scene.add(player.mesh);

const enemy = SpriteSystemV3.createEnemy('assets/enemies/SLIME-1-0.png', loader, {
  position: { x:0, y:0, z:-40 }, layer:'L3'
});
scene.add(enemy.mesh);

// every frame:
player.setDirection(dir);   // 'up' | 'down' | 'left' | 'right'
player.setMoving(isMoving); // bool — resets to idle frame when false
player.update();            // advances walk-cycle frame if moving
player.faceCamera(camera);  // billboard lookAt

// movement is manual, page-side:
player.position.x -= moveSpeed; // player.position is the live mesh.position Vector3
```

`enemy` objects from `createEnemy` have the same API as `player` (minus
`createPlayer`'s hardcoded texture paths). Both expose `.dispose()` —
**call it when removing a sprite** (`scene.remove(e.mesh); e.dispose();`)
to free geometry/material on this hardware target (Pentium Dual-Core /
Radeon HD 6450 lightweight target — don't let detached meshes pile up
ungarbage-collected).

## Current state per file (as of this session)

| File | Uses `RoomBuilderV3.build()` for real geometry? | Uses `SpriteSystemV3`? |
|---|---|---|
| `index.html` | **Yes** — 4x `VCylinder` pillars + center ring | Yes |
| `road_1.html` | No — flat floor + sphere skybox only | Yes |
| `road_2.html` | No — flat floor + sphere skybox only | Yes |
| `arcade_room_1.html` | No — flat floor + sphere skybox only | Yes |
| `arcade_room_2.html` | No — flat floor + sphere skybox only | Yes |

All five load `room_builder_v3.js`, but the four non-hub pages currently
only use it for the `FLOOR_Y` constant. That's not a bug — there was no
shape layout defined for those rooms yet. **Open task:** define
`shapes` arrays (arcade cabinets, corridor walls, whatever the art
calls for) for the road/arcade pages, following the `hubShapes` pattern
in `index.html` as the template.

## Known quirks (not fixed, just flagged)

- `arcade_room_1.html` / `arcade_room_2.html` / `road_1.html` /
  `road_2.html` all use `floorMesh.rotation.x = Math.PI / 2`, while
  `index.html` uses `-Math.PI / 2`. No visible bug (materials are
  `DoubleSide`), just an inconsistency if you ever add lighting or
  single-sided materials.
- `RoomBuilderV3.clamp()` — see above, it's a stub.
