/**
 * RoomBuilder V3 – Advanced 2.5D / 3dSen-inspired engine
 * -------------------------------------------------------
 * Brings the layer + geo + SizeZ system from the V3 builders
 * into a clean, reusable module for playable rooms.
 *
 * Geo types:
 *   Cube          – solid platforms / walls (full SizeZ depth)
 *   VCylinder     – vertical pipes / ladders
 *   HalfVCylinder – gears / rounded vertical pieces (can rotate)
 *   HalfHCylinder – light shafts / horizontal tubes
 *   Default       – flat billboard (always faces camera)
 *
 * Layers (Z bands):
 *   L1  far background
 *   L2  mid background / walls
 *   L3  gameplay
 *   L4  near foreground
 *   UI  always on top
 */
(function (global) {
    const FLOOR_Y = -100;

    // Default layer Z offsets (can be overridden per room)
    const DEFAULT_LAYER_Z = {
        L1: -120,
        L2:  -40,
        L3:    0,
        L4:   65,
        UI:  120
    };

    const GEO_TYPES = ['Cube', 'VCylinder', 'HalfVCylinder', 'HalfHCylinder', 'Default'];

    // ----------------------------------------------------------------
    // Helpers
    // ----------------------------------------------------------------
    function prep(tex) {
        tex.magFilter = THREE.NearestFilter;
        tex.minFilter = THREE.NearestFilter;
        tex.generateMipmaps = false;
        tex.wrapS = THREE.ClampToEdgeWrapping;
        tex.wrapT = THREE.ClampToEdgeWrapping;
        return tex;
    }

    function makeMaterial(color, alpha, emissive) {
        const mat = new THREE.MeshStandardMaterial({
            color: new THREE.Color(color || '#00ff66'),
            transparent: (alpha ?? 1) < 1,
            opacity: alpha ?? 1,
            roughness: 0.55,
            metalness: 0.15,
            side: THREE.DoubleSide
        });
        if (emissive) {
            mat.emissive = new THREE.Color(emissive);
            mat.emissiveIntensity = 0.45;
        }
        return mat;
    }

    function createGeometry(geo, scale, sizeZ) {
        const sx = scale.x || 1;
        const sy = scale.y || 1;
        const sz = sizeZ || scale.z || 1;

        switch (geo) {
            case 'Cube':
                return new THREE.BoxGeometry(sx, sy, sz);
            case 'VCylinder':
                return new THREE.CylinderGeometry(sx * 0.5, sx * 0.5, sy, 12);
            case 'HalfVCylinder':
                return new THREE.CylinderGeometry(sx * 0.5, sx * 0.5, sy, 12, 1, false, 0, Math.PI);
            case 'HalfHCylinder':
                // Horizontal-ish shaft
                const g = new THREE.CylinderGeometry(sy * 0.5, sy * 0.5, sx, 12, 1, false, 0, Math.PI);
                g.rotateZ(Math.PI / 2);
                return g;
            case 'Default':
            default:
                return new THREE.PlaneGeometry(sx, sy);
        }
    }

    // ----------------------------------------------------------------
    // Core builder
    // ----------------------------------------------------------------
    /**
     * Build a room from a list of shape definitions.
     *
     * shape = {
     *   id, name, layer, geo, sizeZ,
     *   scale: {x,y,z}, offset: {x,y,z}, rot: {x,y,z},
     *   alpha, solid, color, tags,
     *   position: {x,y,z}          // world position
     * }
     */
    function buildFromShapes(scene, shapes, options = {}) {
        const layerZ = Object.assign({}, DEFAULT_LAYER_Z, options.layerZ || {});
        const group = new THREE.Group();
        group.name = 'room-v3';

        const meshes = new Map();
        const solidBoxes = [];          // simple AABB list for collision
        const billboards = [];

        shapes.forEach((s) => {
            const geo = createGeometry(s.geo || 'Cube', s.scale || {x:1,y:1,z:1}, s.sizeZ);
            const mat = makeMaterial(s.color, s.alpha, s.emissive ? s.color : null);
            const mesh = new THREE.Mesh(geo, mat);

            const zBase = layerZ[s.layer] ?? 0;
            mesh.position.set(
                (s.position?.x || 0) + (s.offset?.x || 0),
                (s.position?.y || 0) + (s.offset?.y || 0) + FLOOR_Y,
                zBase + (s.position?.z || 0) + (s.offset?.z || 0)
            );

            mesh.rotation.set(
                THREE.MathUtils.degToRad(s.rot?.x || 0),
                THREE.MathUtils.degToRad(s.rot?.y || 0),
                THREE.MathUtils.degToRad(s.rot?.z || 0)
            );

            mesh.castShadow = !!s.solid;
            mesh.receiveShadow = true;
            mesh.userData = {
                id: s.id,
                geo: s.geo,
                layer: s.layer,
                solid: !!s.solid,
                tags: s.tags || ''
            };

            group.add(mesh);
            meshes.set(s.id, mesh);

            if (s.geo === 'Default') {
                billboards.push(mesh);
            }

            if (s.solid && s.geo === 'Cube') {
                const halfX = (s.scale?.x || 1) * 0.5;
                const halfY = (s.scale?.y || 1) * 0.5;
                const halfZ = (s.sizeZ || s.scale?.z || 1) * 0.5;
                solidBoxes.push({
                    mesh,
                    minX: mesh.position.x - halfX,
                    maxX: mesh.position.x + halfX,
                    minY: mesh.position.y - halfY,
                    maxY: mesh.position.y + halfY,
                    minZ: mesh.position.z - halfZ,
                    maxZ: mesh.position.z + halfZ
                });
            }
        });

        scene.add(group);

        return {
            group,
            meshes,
            solidBoxes,
            billboards,
            layerZ,
            options
        };
    }

    // ----------------------------------------------------------------
    // Simple preset rooms (can be expanded)
    // ----------------------------------------------------------------
    const PRESETS = {
        // Minimal empty hub so existing pages don't break
        hub: {
            shapes: [],
            options: {
                layerZ: { L1: -80, L2: -30, L3: 0, L4: 40, UI: 100 }
            }
        }
    };

    // ----------------------------------------------------------------
    // Public API
    // ----------------------------------------------------------------
    const RoomBuilderV3 = {
        FLOOR_Y,
        DEFAULT_LAYER_Z,
        GEO_TYPES,
        PRESETS,

        /**
         * Build from a raw shapes array (the main way to use V3)
         */
        build: function (scene, shapes, options) {
            return buildFromShapes(scene, shapes || [], options);
        },

        /**
         * Build from a named preset (currently only a stub hub)
         */
        buildPreset: function (scene, name) {
            const preset = PRESETS[name];
            if (!preset) throw new Error('RoomBuilderV3: unknown preset ' + name);
            return buildFromShapes(scene, preset.shapes, preset.options);
        },

        /**
         * Make billboards face the camera (call every frame)
         */
        faceBillboards: function (room, camera) {
            if (!room || !room.billboards) return;
            room.billboards.forEach((m) => {
                m.lookAt(camera.position.x, m.position.y, camera.position.z);
            });
        },

        /**
         * Real AABB clamp - fixed from stub (was empty in v3)
         * Expands solidBoxes by player radius and pushes out on smallest penetration
         */
        clamp: function (pos, room) {
            if (!room || !room.solidBoxes || !pos) return;
            const PLAYER_R = 18;
            room.solidBoxes.forEach((b) => {
                const minX = b.minX - PLAYER_R;
                const maxX = b.maxX + PLAYER_R;
                const minZ = b.minZ - PLAYER_R;
                const maxZ = b.maxZ + PLAYER_R;
                if (pos.x < minX || pos.x > maxX || pos.z < minZ || pos.z > maxZ) return;
                // Inside - find smallest penetration
                const penMinX = pos.x - minX;
                const penMaxX = maxX - pos.x;
                const penMinZ = pos.z - minZ;
                const penMaxZ = maxZ - pos.z;
                const minPen = Math.min(penMinX, penMaxX, penMinZ, penMaxZ);
                if (minPen === penMinX) pos.x = minX;
                else if (minPen === penMaxX) pos.x = maxX;
                else if (minPen === penMinZ) pos.z = minZ;
                else pos.z = maxZ;
            });
        },

        /**
         * Helper to create a single shape object
         */
        shape: function (props) {
            return Object.assign({
                id: 's' + Math.random().toString(36).slice(2, 8),
                name: 'shape',
                layer: 'L3',
                geo: 'Cube',
                sizeZ: 20,
                scale: { x: 40, y: 20, z: 20 },
                offset: { x: 0, y: 0, z: 0 },
                rot: { x: 0, y: 0, z: 0 },
                alpha: 1,
                solid: true,
                color: '#00ff66',
                tags: '',
                position: { x: 0, y: 0, z: 0 }
            }, props);
        }
    };

    global.RoomBuilderV3 = RoomBuilderV3;
})(window);
