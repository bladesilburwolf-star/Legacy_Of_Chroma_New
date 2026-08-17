(function (global) {
    /**
     * ProceduralRoom
     * ----------------
     * Simple grid-based procedural Daggerfall-style dungeon generator.
     * Produces a carved grid of floor cells and wall cubes placed at
     * cell positions adjacent to corridors/rooms. Intended as a quick
     * reusable generator for dungeon pages (d_*.html) that prefer
     * algorithmic layout over hand-authored masks.
     *
     * API: ProceduralRoom.build(scene, loader, options)
     * Returns: { room, floorMesh, spawn, worldWidth, worldHeight, grid, cellSize }
     *
     * Options:
     *  - seed (number) deterministic seed
     *  - gridW, gridH (cells)
     *  - cellSize (world units per cell)
     *  - wallHeight
     *  - roomAttempts, minRoomSize, maxRoomSize
     */

    function makeRng(seed) {
        let s = seed | 0;
        return function () {
            // xorshift-ish 32-bit deterministic
            s ^= s << 13;
            s ^= s >>> 17;
            s ^= s << 5;
            return ((s >>> 0) / 4294967295);
        };
    }

    function build(scene, loader, options) {
        options = options || {};
        loader = loader || new THREE.TextureLoader();
        const seed = options.seed != null ? options.seed : (Math.random() * 1000000) | 0;
        const rand = makeRng(seed);

        const gridW = options.gridW || 40;
        const gridH = options.gridH || 32;
        const cellSize = options.cellSize || 24;
        const wallHeight = options.wallHeight || 72;
        const roomAttempts = options.roomAttempts || 40;
        const minRoom = options.minRoomSize || 3;
        const maxRoom = options.maxRoomSize || 10;

        // grid: 1 == wall, 0 == carved floor
        const grid = new Uint8Array(gridW * gridH);
        function gi(x, y) { return y * gridW + x; }
        // fill with walls
        for (let i = 0; i < grid.length; i++) grid[i] = 1;

        const rooms = [];
        function carveRoom(cx, cy, rw, rh) {
            const sx = Math.max(1, cx - Math.floor(rw / 2));
            const sy = Math.max(1, cy - Math.floor(rh / 2));
            const ex = Math.min(gridW - 2, sx + rw - 1);
            const ey = Math.min(gridH - 2, sy + rh - 1);
            for (let y = sy; y <= ey; y++) for (let x = sx; x <= ex; x++) grid[gi(x, y)] = 0;
            rooms.push({ x: Math.floor((sx + ex) / 2), y: Math.floor((sy + ey) / 2), w: rw, h: rh });
        }

        // Place random rooms
        for (let i = 0; i < roomAttempts; i++) {
            const rw = minRoom + Math.floor(rand() * (maxRoom - minRoom + 1));
            const rh = minRoom + Math.floor(rand() * (maxRoom - minRoom + 1));
            const cx = 1 + Math.floor(rand() * (gridW - 2));
            const cy = 1 + Math.floor(rand() * (gridH - 2));
            carveRoom(cx, cy, rw, rh);
        }

        // Connect rooms via simple corridor carving (connect centers)
        if (rooms.length >= 2) {
            for (let i = 1; i < rooms.length; i++) {
                const a = rooms[i - 1];
                const b = rooms[i];
                // horizontal then vertical tunnel
                const x0 = a.x, z0 = a.y, x1 = b.x, z1 = b.y;
                const sx = Math.min(x0, x1), ex = Math.max(x0, x1);
                for (let x = sx; x <= ex; x++) grid[gi(x, z0)] = 0;
                const sz = Math.min(z0, z1), ez = Math.max(z0, z1);
                for (let z = sz; z <= ez; z++) grid[gi(x1, z)] = 0;
            }
        }

        // Optional: carve some random corridors
        for (let i = 0; i < gridW * gridH * 0.02; i++) {
            const x = 1 + Math.floor(rand() * (gridW - 2));
            const z = 1 + Math.floor(rand() * (gridH - 2));
            grid[gi(x, z)] = 0;
        }

        // Build floor plane covering carved area
        const worldW = gridW * cellSize;
        const worldH = gridH * cellSize;
        const floorTex = loader.load('assets/textures/dirt1.jpg');
        floorTex.wrapS = floorTex.wrapT = THREE.RepeatWrapping;
        // use linear filtering and mipmaps to reduce tearing on large planes
        floorTex.magFilter = THREE.LinearFilter;
        floorTex.minFilter = THREE.LinearMipMapLinearFilter;
        floorTex.generateMipmaps = true;
        floorTex.repeat.set(Math.max(1, gridW / 8), Math.max(1, gridH / 8));

        // create a container group so generated content can be removed/translated
        const group = new THREE.Group();
        const offsetX = options.offsetX || 0;
        const offsetZ = options.offsetZ || 0;
        group.position.set(offsetX, 0, offsetZ);

        // visual floor (plane) — kept for texture detail
        const floorMesh = new THREE.Mesh(
            new THREE.PlaneGeometry(worldW, worldH, Math.max(1, gridW), Math.max(1, gridH)),
            new THREE.MeshBasicMaterial({ map: floorTex, side: THREE.FrontSide })
        );
        floorMesh.rotation.x = -Math.PI / 2;
        // align plane visually at FLOOR_Y
        floorMesh.position.set(0, RoomBuilderV3.FLOOR_Y, 0);
        group.add(floorMesh);

        // Add an invisible thin box as the actual floor collider so CollisionSystem can detect ground
        const floorHeight = 4;
        const S = global.RoomBuilderV3.shape;
        const floorShape = S({ id: 'proc_floor', layer: 'L3', geo: 'Cube', scale: { x: worldW, y: floorHeight, z: worldH }, position: { x: 0, y: RoomBuilderV3.FLOOR_Y - 2 + floorHeight / 2, z: 0 }, color: '#332a1f', solid: true });
        // will insert into shapes array before build

        // Build walls: place cubes where a cell is wall and has an adjacent carved cell
        const shapes = [];
        for (let z = 0; z < gridH; z++) {
            for (let x = 0; x < gridW; x++) {
                if (grid[gi(x, z)] === 1) {
                    // check adjacency to carved cell
                    const adj = ((x > 0 && grid[gi(x - 1, z)] === 0) || (x < gridW - 1 && grid[gi(x + 1, z)] === 0) || (z > 0 && grid[gi(x, z - 1)] === 0) || (z < gridH - 1 && grid[gi(x, z + 1)] === 0));
                    if (!adj) continue; // only border walls
                    const wx = (x - gridW / 2 + 0.5) * cellSize;
                    const wz = (z - gridH / 2 + 0.5) * cellSize;
                    shapes.push(S({ id: 'w_' + x + '_' + z, layer: 'L2', geo: 'Cube', scale: { x: cellSize, y: wallHeight, z: cellSize }, position: { x: wx, y: wallHeight / 2, z: wz }, color: '#2a2a2a', solid: true }));
                }
            }
        }

        // Slight decorative visual-only planes for corridor murals
        if (options.addVisuals) {
            for (let i = 0; i < Math.min(40, shapes.length); i += Math.max(1, Math.floor(1 + rand() * 4))) {
                const idx = Math.floor(rand() * shapes.length);
                const s = shapes[idx];
                // place a small visual plane in front of this wall
                const vMat = new THREE.MeshBasicMaterial({ map: loader.load('assets/textures/rock1.jpg'), transparent: true, depthWrite: false, side: THREE.DoubleSide });
                const vMesh = new THREE.Mesh(new THREE.PlaneGeometry(cellSize * 0.9, cellSize * 0.9), vMat);
                vMesh.position.set(s.position.x, RoomBuilderV3.FLOOR_Y + 20, s.position.z);
                group.add(vMesh);
            }
        }

        // insert floor collider as the first shape so RoomBuilder creates proper colliders
        shapes.unshift(floorShape);

        // Build room via RoomBuilderV3 into the group
        const room = global.RoomBuilderV3.build(group, shapes, { layerZ: { L1: -80, L2: -20, L3: 0, UI: 100 } });

        // add group to parent scene
        if (scene && scene.add) scene.add(group);

        // Pick a spawn in center of first room carved area (or center of grid)
        let spawn = { x: 0, y: 0, z: 0 };
        if (rooms.length) {
            const r = rooms[0];
            spawn.x = (r.x - gridW / 2 + 0.5) * cellSize + offsetX;
            spawn.z = (r.y - gridH / 2 + 0.5) * cellSize + offsetZ;
        }

        return {
            room: room,
            floorMesh: floorMesh,
            spawn: spawn,
            worldWidth: worldW,
            worldHeight: worldH,
            grid: grid,
            cellSize: cellSize,
            seed: seed,
            container: group
        };
    }

    global.ProceduralRoom = { build: build };
})(this);