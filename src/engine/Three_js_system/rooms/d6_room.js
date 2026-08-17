(function (global) {
    /**
     * D6Room -- Shadow Tomb layout for Dungeon 6
     * Provides a build(scene, loader, options) function that constructs
     * a tomb with collidable walls, a roof, steps, and a hidden chamber.
     * Returns { room, floorMesh, spawn, worldWidth, worldHeight, secretTrigger, hiddenRoom, doorPos }
     */

    const FLOOR_Y = (global.RoomBuilderV3 && global.RoomBuilderV3.FLOOR_Y) || -100;
    const WORLD_W = 720; // approximate world footprint
    const WORLD_H = 560;

    const T_W = 360, T_D = 260, T_H = 160;
    const CENTER_Z = -80;

    // Simple wall rectangles (centered + size)
    const WALL_RECTS = [
        { x: 0, z: CENTER_Z - T_D/2 + 6, sx: T_W, sz: 12 }, // north
        { x: 0, z: CENTER_Z + T_D/2 - 6, sx: T_W, sz: 12 }, // south
        { x: -T_W/2 + 6, z: CENTER_Z, sx: 12, sz: T_D },    // west
        { x: T_W/2 - 6, z: CENTER_Z, sx: 12, sz: T_D }      // east
    ];

    const WALL_COLOR = '#1a1a1a';
    const WALL_HEIGHT = T_H;

    function prep(tex) {
        if (!tex) return tex;
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

        // Floor art (use rock/dirt base)
        const floorTex = prep(loader.load('assets/textures/rock1.jpg'));
        const floorMesh = new THREE.Mesh(
            new THREE.PlaneGeometry(WORLD_W, WORLD_H),
            new THREE.MeshBasicMaterial({ map: floorTex, side: THREE.DoubleSide })
        );
        floorMesh.rotation.x = -Math.PI / 2;
        floorMesh.position.set(0, FLOOR_Y - 2, 0);
        scene.add(floorMesh);

        // Build wall colliders from WALL_RECTS
        const S = global.RoomBuilderV3.shape;
        const shapes = WALL_RECTS.map((r, i) => S({
            id: 'd6w' + i,
            layer: 'L2',
            geo: 'Cube',
            scale: { x: r.sx, y: WALL_HEIGHT, z: r.sz },
            sizeZ: r.sz,
            position: { x: r.x, y: WALL_HEIGHT / 2, z: r.z },
            color: WALL_COLOR,
            solid: true
        }));

        // Add some interior pillar colliders for layout (play with hidden passages)
        shapes.push(S({ id: 'd6_p1', layer: 'L2', geo: 'Cube', scale: { x: 18, y: 80, z: 18 }, position: { x: -60, y: 40, z: CENTER_Z - 40 }, color: WALL_COLOR, solid: true }));
        shapes.push(S({ id: 'd6_p2', layer: 'L2', geo: 'Cube', scale: { x: 18, y: 80, z: 18 }, position: { x: 60, y: 40, z: CENTER_Z + 40 }, color: WALL_COLOR, solid: true }));

        const room = global.RoomBuilderV3.build(scene, shapes, { layerZ: { L1: -80, L2: -20, L3: 0, UI: 0 } });

        if (options.addLights) {
            scene.add(new THREE.AmbientLight(0x666666, 0.6));
            const dl = new THREE.DirectionalLight(0xffffff, 0.35);
            dl.position.set(120, 220, 100);
            scene.add(dl);
        }

        // Roof (solid visual slab) built as a cube above walls
        const roof = S({ id: 'd6_roof', layer: 'L2', geo: 'Cube', scale: { x: T_W + 8, y: 28, z: T_D + 8 }, position: { x: 0, y: T_H + 20, z: CENTER_Z }, color: '#050509', solid: true });
        // add to scene as a safety in case RoomBuilder doesn't return it
        try { scene.add(roof); } catch (e) { }

        // Steps build (simple cubes near front)
        const step1 = S({ id: 'd6_step1', layer: 'L3', geo: 'Cube', scale: { x: 120, y: 8, z: 30 }, position: { x: 0, y: 8, z: CENTER_Z + T_D / 2 + 18 }, color: '#1c1c1c', solid: true });
        const step2 = S({ id: 'd6_step2', layer: 'L3', geo: 'Cube', scale: { x: 96, y: 8, z: 22 }, position: { x: 0, y: 16, z: CENTER_Z + T_D / 2 + 40 }, color: '#171717', solid: true });
        const step3 = S({ id: 'd6_step3', layer: 'L3', geo: 'Cube', scale: { x: 72, y: 8, z: 12 }, position: { x: 0, y: 24, z: CENTER_Z + T_D / 2 + 58 }, color: '#121212', solid: true });
        try { scene.add(step1); scene.add(step2); scene.add(step3); } catch (e) {}

        // Visual-only wall decal plane to provide interior murals (non-collidable)
        const wallsTex = prep(loader.load('assets/d1/D1 WALLS.png'));
        const wallsDecal = new THREE.Mesh(new THREE.PlaneGeometry(T_W, T_D), new THREE.MeshBasicMaterial({ map: wallsTex, transparent: true, depthWrite: false, side: THREE.DoubleSide }));
        wallsDecal.rotation.x = -Math.PI / 2; wallsDecal.position.set(0, FLOOR_Y + 1, CENTER_Z); scene.add(wallsDecal);

        // Hidden room group behind south wall (initially invisible)
        const hiddenRoom = new THREE.Group(); hiddenRoom.visible = false;
        const hrFloor = new THREE.Mesh(new THREE.PlaneGeometry(180, 140), new THREE.MeshBasicMaterial({ map: prep(loader.load('assets/textures/dirt1.jpg')), side: THREE.DoubleSide }));
        hrFloor.rotation.x = -Math.PI / 2; hrFloor.position.set(0, FLOOR_Y - 1, CENTER_Z + T_D / 2 + 80); hiddenRoom.add(hrFloor);
        const chestMat = new THREE.MeshBasicMaterial({ map: prep(loader.load('assets/chests/BIGCHEST-1.png')), transparent: true, side: THREE.DoubleSide });
        const chest = new THREE.Mesh(new THREE.PlaneGeometry(56, 48), chestMat); chest.position.set(0, FLOOR_Y + 24, CENTER_Z + T_D / 2 + 80); hiddenRoom.add(chest);
        scene.add(hiddenRoom);

        // Secret trigger definition (world coords)
        const secretTrigger = { x: 0, z: CENTER_Z + T_D / 2 + 40, radius: 36, revealed: false };

        // Door position (visual door plane) - returns position for external use
        const doorPos = { x: 0, y: FLOOR_Y + 60, z: CENTER_Z + T_D / 2 - 12 };

        // Spawn point outside the tomb, near the approach
        const spawn = { x: 0, y: 0, z: CENTER_Z + T_D / 2 + 240 };

        return {
            room: room,
            floorMesh: floorMesh,
            spawn: spawn,
            worldWidth: WORLD_W,
            worldHeight: WORLD_H,
            secretTrigger: secretTrigger,
            hiddenRoom: hiddenRoom,
            doorPos: doorPos
        };
    }

    global.D6Room = { build: build };
})(this);
