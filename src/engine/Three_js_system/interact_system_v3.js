/**
 * InteractSystemV3 -- interactable world objects (chests, and anything
 * else billboard-shaped that needs an "open/interact" state later:
 * levers, signs, doors).
 * -------------------------------------------------------------------
 * Chests are plain billboard sprites (same rendering approach as
 * SpriteSystemV3 enemies) with a closed/open texture swap and a
 * radius-based interact check, since that's consistent with how
 * everything else in this project already renders 2.5D objects --
 * no new geometry type needed.
 *
 * Usage:
 *   const chest = InteractSystemV3.createChest(loader, {
 *     position: { x: 245, y: 0, z: 215 },
 *     size: 'small',              // 'small' | 'big'
 *     item: 'SMALL KEY',          // just a label passed to onOpen
 *   });
 *   scene.add(chest.mesh);
 *
 *   // every frame:
 *   InteractSystemV3.update(player.mesh.position, [chest], keys.interact, (chest) => {
 *     inventory.smallKeys++;
 *     updateInventoryHud();
 *   });
 *   chest.faceCamera(camera); // billboard, same as enemies
 */
(function (global) {
    const FLOOR_Y = (global.RoomBuilderV3 && global.RoomBuilderV3.FLOOR_Y) || -100;

    const CHEST_TEXTURES = {
        small: {
            closed: 'assets/chests/SMALLCHEST-1.png',
            open: 'assets/chests/SMALLCHESTOPEN.png'
        },
        big: {
            closed: 'assets/chests/BIGCHEST-1.png',
            open: 'assets/chests/BIGCHESTOPEN.png'
        }
    };

    function prep(tex) {
        tex.magFilter = THREE.NearestFilter;
        tex.minFilter = THREE.NearestFilter;
        tex.generateMipmaps = false;
        return tex;
    }

    function createChest(loader, options) {
        options = options || {};
        const size = options.size === 'big' ? 'big' : 'small';
        const paths = CHEST_TEXTURES[size];

        const closedTex = prep(loader.load(paths.closed));
        const openTex = prep(loader.load(paths.open));

        const w = options.width || (size === 'big' ? 50 : 36);
        const h = options.height || (size === 'big' ? 42 : 32);

        const mat = new THREE.MeshBasicMaterial({
            map: closedTex,
            transparent: true,
            side: THREE.DoubleSide,
            depthWrite: true
        });
        const mesh = new THREE.Mesh(new THREE.PlaneGeometry(w, h), mat);
        const pos = options.position || { x: 0, y: 0, z: 0 };
        mesh.position.set(pos.x, (pos.y || 0) + FLOOR_Y + h * 0.5, pos.z);

        let opened = false;

        return {
            mesh: mesh,
            item: options.item || null,
            interactRadius: options.interactRadius || 40,
            get position() { return mesh.position; },
            isOpen: function () { return opened; },
            open: function () {
                if (opened) return false;
                opened = true;
                mat.map = openTex;
                mat.needsUpdate = true;
                return true;
            },
            faceCamera: function (camera) {
                mesh.lookAt(camera.position.x, mesh.position.y, camera.position.z);
            }
        };
    }

    /**
     * Call once per frame. `interactKeyDown` should be a rising-edge
     * boolean (true only on the frame the key was pressed) -- pass
     * your own edge-detected value the same way CombatSystemV3 does
     * internally for its attack key, so holding the key doesn't spam
     * onOpen every frame.
     */
    function update(playerPos, chests, interactKeyDown, onOpen) {
        if (!interactKeyDown || !chests) return;
        for (let i = 0; i < chests.length; i++) {
            const c = chests[i];
            if (c.isOpen()) continue;
            const dx = c.position.x - playerPos.x;
            const dz = c.position.z - playerPos.z;
            if (Math.sqrt(dx * dx + dz * dz) <= c.interactRadius) {
                if (c.open() && onOpen) onOpen(c);
                break; // one chest per keypress
            }
        }
    }

    global.InteractSystemV3 = { createChest: createChest, update: update };
})(window);
