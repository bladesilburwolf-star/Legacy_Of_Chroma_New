/**
 * SpriteSystem V3 – Billboard entity system for Neondromeda
 * ---------------------------------------------------------
 * Companion to room_builder_v3.js
 *
 * Handles animated 2.5D sprites (player, enemies, NPCs, props)
 * with the same nearest-neighbor + layer spirit as RoomBuilder V3.
 *
 * Usage:
 *   const player = SpriteSystem.create({
 *     textures: { down: [...], up: [...], left: [...], right: [...] },
 *     width: 45, height: 45,
 *     position: { x: 0, y: 0, z: 0 },
 *     layer: 'L3'
 *   });
 *   scene.add(player.mesh);
 *
 *   // every frame:
 *   player.setDirection('up');
 *   player.update(delta);
 *   player.faceCamera(camera);
 */
(function (global) {
    const FLOOR_Y = (global.RoomBuilderV3 && global.RoomBuilderV3.FLOOR_Y) || -100;

    const DEFAULT_LAYER_Z = {
        L1: -120,
        L2:  -40,
        L3:    0,
        L4:   65,
        UI:  120
    };

    function prep(tex) {
        if (!tex) return tex;
        tex.magFilter = THREE.NearestFilter;
        tex.minFilter = THREE.NearestFilter;
        tex.generateMipmaps = false;
        return tex;
    }

    /**
     * Create a single animated billboard sprite.
     *
     * options = {
     *   id, name,
     *   textures: { down: [Texture|string], up: [...], left: [...], right: [...] }
     *            or a single array for non-directional sprites
     *   width, height,
     *   position: {x, y, z},
     *   layer: 'L3',
     *   animSpeed: 8,          // frames between advances
     *   solid: false,
     *   tags: '',
     *   loader: THREE.TextureLoader instance (optional)
     * }
     */
    function create(options) {
        options = options || {};
        const loader = options.loader || new THREE.TextureLoader();

        // Normalize textures into { direction: [THREE.Texture, ...] }
        const texMap = {};
        const raw = options.textures || {};

        function loadList(list) {
            if (!Array.isArray(list)) list = [list];
            return list.map(item => {
                if (typeof item === 'string') {
                    return prep(loader.load(item));
                }
                return prep(item);
            });
        }

        if (Array.isArray(raw)) {
            // Non-directional
            texMap.down = loadList(raw);
            texMap.up = texMap.down;
            texMap.left = texMap.down;
            texMap.right = texMap.down;
        } else {
            ['down', 'up', 'left', 'right'].forEach(dir => {
                texMap[dir] = loadList(raw[dir] || raw.down || []);
            });
        }

        const firstTex = texMap.down[0] || null;
        const mat = new THREE.MeshBasicMaterial({
            map: firstTex,
            transparent: true,
            side: THREE.DoubleSide,
            depthWrite: true
        });

        const w = options.width  || 40;
        const h = options.height || 40;
        const mesh = new THREE.Mesh(new THREE.PlaneGeometry(w, h), mat);

        const layer = options.layer || 'L3';
        const layerZ = (options.layerZ && options.layerZ[layer]) || DEFAULT_LAYER_Z[layer] || 0;

        const pos = options.position || { x: 0, y: 0, z: 0 };
        const baseY = options.worldY
            ? (pos.y || 0)
            : ((pos.y || 0) + FLOOR_Y + (h * 0.5));
        mesh.position.set(
            pos.x || 0,
            baseY,
            (pos.z || 0) + layerZ
        );

        mesh.userData = {
            id: options.id || ('spr_' + Math.random().toString(36).slice(2, 8)),
            name: options.name || 'sprite',
            layer: layer,
            solid: !!options.solid,
            tags: options.tags || '',
            isSpriteV3: true
        };

        // Internal state
        let currentDir = 'down';
        let animFrame = 0;
        let animTimer = 0;
        const animSpeed = options.animSpeed || 8;
        let moving = false;

        const api = {
            mesh: mesh,
            material: mat,
            textures: texMap,
            width: w,
            height: h,

            get position() { return mesh.position; },
            setPosition(x, y, z) {
                mesh.position.x = x;
                if (y !== undefined) mesh.position.y = y + FLOOR_Y + (h * 0.5);
                if (z !== undefined) mesh.position.z = z + layerZ;
            },

            setDirection(dir) {
                if (texMap[dir]) currentDir = dir;
            },

            getDirection() { return currentDir; },

            setMoving(isMoving) {
                moving = !!isMoving;
                if (!moving) {
                    animFrame = 0;
                    animTimer = 0;
                    if (texMap[currentDir][0]) {
                        mat.map = texMap[currentDir][0];
                        mat.needsUpdate = true;
                    }
                }
            },

            /**
             * Call every frame. Advances animation when moving.
             */
            update(delta) {
                if (!moving) return;
                animTimer++;
                if (animTimer % animSpeed === 0) {
                    const frames = texMap[currentDir];
                    if (frames && frames.length) {
                        animFrame = (animFrame + 1) % frames.length;
                        mat.map = frames[animFrame];
                        mat.needsUpdate = true;
                    }
                }
            },

            /**
             * Make the billboard face the camera (classic 2.5D)
             */
            faceCamera(camera) {
                mesh.lookAt(camera.position.x, mesh.position.y, camera.position.z);
            },

            /**
             * Simple distance check
             */
            distanceTo(other) {
                if (other.position) return mesh.position.distanceTo(other.position);
                return mesh.position.distanceTo(other);
            },

            /**
             * Swap the entire texture set (useful for state changes)
             */
            setTextures(newTexMap) {
                Object.keys(newTexMap).forEach(dir => {
                    texMap[dir] = loadList(newTexMap[dir]);
                });
                mat.map = texMap[currentDir][0];
                mat.needsUpdate = true;
            },

            dispose() {
                mesh.geometry.dispose();
                mat.dispose();
            }
        };

        // Attach collision-friendly defaults to the mesh/userData
        try {
            mesh.userData = mesh.userData || {};
            mesh.userData.isSpriteV3 = true;
            mesh.userData.collidable = !!options.collidable;
            mesh.userData.radius = options.radius || (w * 0.45);
            mesh.userData.height = h;
        } catch (e) {}

        // Mirror to returned API for convenience
        api.radius = mesh.userData.radius;
        api.height = h;

        return api;
    }

    /**
     * Helper: create a player with the standard Neondromeda paths
     */
    function createPlayer(loader, options) {
        options = options || {};
        return create({
            id: 'player',
            name: 'Player',
            textures: {
                up:    ['assets/player/NORTH.png', 'assets/player/VRMANBACK.png', 'assets/player/VRMANBACK-1.png'],
                down:  ['assets/player/SOUTH.png', 'assets/player/VRMANBACK.png'],
                left:  ['assets/player/WEST.png', 'assets/player/VRMANL.png'],
                right: ['assets/player/EAST.png', 'assets/player/VRMANR.png', 'assets/player/VRMANR-1.png']
            },
            width: 45,
            height: 45,
            position: options.position || { x: 0, y: 0, z: 0 },
            layer: options.layer || 'L3',
            animSpeed: 8,
            loader: loader,
            tags: 'player'
        });
    }

    /**
     * Helper: create an enemy from a single texture path (or list)
     */
    function createEnemy(texturePathOrList, loader, options) {
        options = options || {};
        return create({
            id: options.id || ('enemy_' + Math.random().toString(36).slice(2, 6)),
            name: options.name || 'Enemy',
            textures: Array.isArray(texturePathOrList) ? texturePathOrList : [texturePathOrList],
            width: options.width || 40,
            height: options.height || 40,
            position: options.position || { x: 0, y: 0, z: 0 },
            layer: options.layer || 'L3',
            animSpeed: options.animSpeed || 10,
            loader: loader,
            solid: true,
            tags: 'enemy ' + (options.tags || '')
        });
    }

    // Allow making arbitrary existing meshes "collidable" for the collision system
    function makeCollidable(mesh, opts) {
        opts = opts || {};
        if (!mesh) return mesh;
        mesh.userData = mesh.userData || {};
        mesh.userData.collidable = true;
        mesh.userData.static = !!opts.static; // static objects don't get pushed by collisions
        mesh.userData.radius = opts.radius || (mesh.geometry && mesh.geometry.parameters && mesh.geometry.parameters.width ? mesh.geometry.parameters.width * 0.45 : (opts.width ? opts.width * 0.45 : 18));
        mesh.userData.height = opts.height || (mesh.geometry && mesh.geometry.parameters && mesh.geometry.parameters.height ? mesh.geometry.parameters.height : (opts.height || 40));
        return mesh;
    }

    const SpriteSystemV3 = {
        FLOOR_Y,
        DEFAULT_LAYER_Z,
        create,
        createPlayer,
        createEnemy,
        makeCollidable
    };

    global.SpriteSystemV3 = SpriteSystemV3;
})(window);
