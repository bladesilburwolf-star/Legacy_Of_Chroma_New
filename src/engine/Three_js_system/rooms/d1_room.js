/**
 * D1Room -- builds the real Dungeon 1 layout for the RoomBuilderV3 /
 * SpriteSystemV3 / CombatSystemV3 Three.js stack.
 * -------------------------------------------------------------------
 * This replaces the earlier placeholder (a flat floor + two arbitrary
 * gray boxes). Wall collision here is NOT guessed or hand-placed --
 * it's decoded directly from assets/d1/D1 WALLS.png, which is a real
 * per-pixel collision mask (opaque stone = wall, transparent = floor).
 *
 * How the mask became the WALL_RECTS below (documented so this is
 * reproducible for D2/D3, or if the art gets updated):
 *   1. Load the PNG, threshold alpha > 128 => solid.
 *   2. Snap to a 20px grid (majority-solid per cell) so hairline
 *      antialiasing doesn't fragment the shapes.
 *   3. Row-by-row run-length encode solid spans, merge vertically
 *      adjacent identical spans into rectangles (skyline merge).
 *   4. Convert each rectangle's pixel bounds to world units at
 *      SCALE = WORLD_W / IMG_W (0.5 -- i.e. 1 source pixel = 0.5
 *      world units), centered on the origin.
 * That produced the 33 WALL_RECTS below. This is a build-time-once
 * extraction, not something this file redoes at runtime (keeps the
 * hot path cheap for the low-end GPU target).
 *
 * Usage:
 *   const d1 = D1Room.build(scene, loader);
 *   scene has the floor + walls added already.
 *   d1.room       -> pass to RoomBuilderV3.clamp(pos, d1.room) and
 *                    RoomBuilderV3.faceBillboards(d1.room, camera)
 *   d1.spawn      -> {x,y,z} a validated open floor tile near the
 *                    dungeon's entrance landmark (the stairway graphic
 *                    baked into the north wall art)
 */
(function (global) {
    const FLOOR_Y = (global.RoomBuilderV3 && global.RoomBuilderV3.FLOOR_Y) || -100;

    // Source art is 1920x1440; world space is scaled 0.5x (960x720),
    // centered on the origin. Every position/scale below is already
    // in world units after that conversion.
    const WORLD_W = 960;
    const WORLD_H = 720;

    // Extracted from assets/d1/D1 WALLS.png -- see file header.
    // {x, z, sx, sz} = center position + full width/depth in world units.
    const WALL_RECTS = [
        { x: 0.0,    z: -345.0, sx: 960.0, sz: 30.0 },
        { x: -75.0,  z: -325.0, sx: 170.0, sz: 10.0 },
        { x: -80.0,  z: -315.0, sx: 160.0, sz: 10.0 },
        { x: -85.0,  z: -305.0, sx: 50.0,  sz: 10.0 },
        { x: 475.0,  z: -295.0, sx: 10.0,  sz: 70.0 },
        { x: 235.0,  z: -250.0, sx: 490.0, sz: 20.0 },
        { x: -155.0, z: -260.0, sx: 10.0,  sz: 100.0 },
        { x: 35.0,   z: -225.0, sx: 90.0,  sz: 30.0 },
        { x: 305.0,  z: -225.0, sx: 350.0, sz: 30.0 },
        { x: -475.0, z: -240.0, sx: 10.0,  sz: 180.0 },
        { x: -315.0, z: -200.0, sx: 10.0,  sz: 120.0 },
        { x: -470.0, z: -145.0, sx: 20.0,  sz: 10.0 },
        { x: -160.0, z: -130.0, sx: 640.0, sz: 20.0 },
        { x: -380.0, z: -105.0, sx: 200.0, sz: 30.0 },
        { x: -30.0,  z: -105.0, sx: 380.0, sz: 30.0 },
        { x: -155.0, z: -35.0,  sx: 10.0,  sz: 110.0 },
        { x: 475.0,  z: -60.0,  sx: 10.0,  sz: 300.0 },
        { x: 315.0,  z: -25.0,  sx: 10.0,  sz: 230.0 },
        { x: 155.0,  z: 35.0,   sx: 10.0,  sz: 110.0 },
        { x: 240.0,  z: 105.0,  sx: 480.0, sz: 30.0 },
        { x: 185.0,  z: 130.0,  sx: 370.0, sz: 20.0 },
        { x: 455.0,  z: 130.0,  sx: 50.0,  sz: 20.0 },
        { x: 475.0,  z: 170.0,  sx: 10.0,  sz: 60.0 },
        { x: -315.0, z: 95.0,   sx: 10.0,  sz: 230.0 },
        { x: -155.0, z: 155.0,  sx: 10.0,  sz: 110.0 },
        { x: -80.0,  z: 225.0,  sx: 480.0, sz: 30.0 },
        { x: 60.0,   z: 250.0,  sx: 200.0, sz: 20.0 },
        { x: -210.0, z: 250.0,  sx: 220.0, sz: 20.0 },
        { x: -475.0, z: 120.0,  sx: 10.0,  sz: 420.0 },
        { x: 315.0,  z: 270.0,  sx: 10.0,  sz: 120.0 },
        { x: -315.0, z: 295.0,  sx: 10.0,  sz: 70.0 },
        { x: 475.0,  z: 310.0,  sx: 10.0,  sz: 40.0 },
        { x: 0.0,    z: 345.0,  sx: 960.0, sz: 30.0 }
    ];

    // Validated-open floor tile just south of the stairway graphic in
    // the north wall art -- see file header step-by-step in the repo
    // commit that added this if the art changes and this needs redoing.
    const SPAWN = { x: -217.5, y: 0, z: -300 };

    const WALL_COLOR = '#585850';
    const WALL_HEIGHT = 70;

    function prep(tex) {
        tex.magFilter = THREE.NearestFilter;
        tex.minFilter = THREE.NearestFilter;
        tex.generateMipmaps = false;
        tex.wrapS = THREE.ClampToEdgeWrapping;
        tex.wrapT = THREE.ClampToEdgeWrapping;
        return tex;
    }

    function build(scene, loader, options) {
        options = options || {};
        loader = loader || new THREE.TextureLoader();

        // ---- Floor (real D1 art, not a placeholder color) ----
        const floorTex = prep(loader.load('assets/d1/D1 FLOORS.jpg'));
        const floorMesh = new THREE.Mesh(
            new THREE.PlaneGeometry(WORLD_W, WORLD_H),
            new THREE.MeshBasicMaterial({ map: floorTex, side: THREE.DoubleSide })
        );
        floorMesh.rotation.x = -Math.PI / 2;
        floorMesh.position.set(0, FLOOR_Y, 0);
        scene.add(floorMesh);

        // ---- Walls: real Cube colliders at the mask-derived rectangles ----
        const S = global.RoomBuilderV3.shape;
        const shapes = WALL_RECTS.map((r, i) => S({
            id: 'd1w' + i,
            layer: 'L2',
            geo: 'Cube',
            scale: { x: r.sx, y: WALL_HEIGHT, z: r.sz },
            sizeZ: r.sz,
            position: { x: r.x, y: WALL_HEIGHT / 2, z: r.z },
            color: WALL_COLOR,
            solid: true
        }));

        // layerZ all zeroed -- these positions are already absolute world
        // coordinates from the pixel extraction, not diorama-layer-relative.
        const room = global.RoomBuilderV3.build(scene, shapes, {
            layerZ: { L1: 0, L2: 0, L3: 0, L4: 0, UI: 0 }
        });

        if (options.addLights) {
            scene.add(new THREE.AmbientLight(0xffffff, 0.7));
            const dir = new THREE.DirectionalLight(0xffffff, 0.5);
            dir.position.set(120, 220, 100);
            scene.add(dir);
        }

        return {
            room: room,
            floorMesh: floorMesh,
            spawn: { x: SPAWN.x, y: SPAWN.y, z: SPAWN.z },
            worldWidth: WORLD_W,
            worldHeight: WORLD_H
        };
    }

    global.D1Room = { build: build, WALL_RECTS: WALL_RECTS, SPAWN: SPAWN };
})(this);
