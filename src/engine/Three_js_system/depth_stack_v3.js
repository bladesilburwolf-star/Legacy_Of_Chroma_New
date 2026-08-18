/**
 * DepthStackV3 -- 3dSen-style layered backdrops for the 2.5D dungeons.
 * -------------------------------------------------------------------
 * The core idea (same one 3dSen uses to turn flat NES frames into
 * pseudo-3D): don't fold one painting onto a single flat wall plane.
 * Tear it into pieces and place EACH piece at its own real distance
 * from the camera. Because they're genuinely separate objects at
 * separate depths, a moving camera gets true parallax from the
 * z-buffer -- near layers slide across the screen faster than far
 * ones. That's the whole trick; everything else here is bookkeeping.
 *
 * Two ways to build a layer stack:
 *   1. From one painted image, sliced into horizontal bands (same
 *      band-texture technique used elsewhere in this project) --
 *      good once real side-view dungeon backdrop art exists.
 *   2. From flat colors, no image needed -- this is what the engine
 *      test scene uses right now, since there's no dungeon art yet
 *      for this style.
 *
 * Lanes vs. depth layers -- these are related but not the same thing:
 * a "lane" (SidescrollPhysicsV3) is a place the PLAYER can stand and
 * fight. A "depth layer" (this module) is background/foreground
 * scenery the player never touches. A dungeon room typically has 2
 * lanes the player can occupy, but several depth layers behind/around
 * them for the parallax to actually read as deep.
 *
 * Usage (color layers, no art needed yet):
 *   const stack = DepthStackV3.buildColorLayers(scene, [
 *     { z: -300, color: 0x0a0a12, width: 900, height: 400 },
 *     { z: -180, color: 0x141420, width: 800, height: 380 },
 *     { z:  -60, color: 0x1e1e30, width: 700, height: 360 },
 *   ]);
 *
 * Usage (real art, once it exists):
 *   const stack = DepthStackV3.buildImageLayers(scene, loader, 'assets/d2/side_view.jpg', [
 *     { v0: 0.0, v1: 0.3, z: -300, widthScale: 1.6 },
 *     { v0: 0.3, v1: 0.7, z: -150, widthScale: 1.2 },
 *     { v0: 0.7, v1: 1.0, z:  -40, widthScale: 1.0 },
 *   ], { baseWidth: 700, height: 380 });
 */
(function (global) {
    function prep(tex) {
        tex.magFilter = THREE.NearestFilter;
        tex.minFilter = THREE.NearestFilter;
        tex.generateMipmaps = false;
        return tex;
    }

    function bandTexture(source, v0, v1, repeatX) {
        const t = source.clone();
        t.wrapS = repeatX && repeatX !== 1 ? THREE.RepeatWrapping : THREE.ClampToEdgeWrapping;
        t.wrapT = THREE.ClampToEdgeWrapping;
        t.repeat.set(repeatX || 1, v1 - v0);
        t.offset.set(0, 1 - v1);
        t.needsUpdate = true;
        prep(t);
        return t;
    }

    function buildColorLayers(scene, layerDefs) {
        const group = new THREE.Group();
        group.name = 'depth-stack-color';
        const meshes = [];
        layerDefs.forEach(function (def) {
            const mat = new THREE.MeshBasicMaterial({ color: def.color, side: THREE.DoubleSide });
            const mesh = new THREE.Mesh(new THREE.PlaneGeometry(def.width, def.height), mat);
            mesh.position.set(def.x || 0, def.y != null ? def.y : def.height * 0.5, def.z);
            group.add(mesh);
            meshes.push(mesh);
        });
        scene.add(group);
        return { group: group, meshes: meshes };
    }

    function buildImageLayers(scene, loader, imagePath, layerDefs, options) {
        options = options || {};
        const baseWidth = options.baseWidth || 700;
        const height = options.height || 380;

        const group = new THREE.Group();
        group.name = 'depth-stack-image';
        const meshes = [];
        scene.add(group);

        loader.load(imagePath, function (tex) {
            prep(tex);
            layerDefs.forEach(function (def) {
                const w = baseWidth * (def.widthScale || 1);
                const bandTex = bandTexture(tex, def.v0, def.v1, 1);
                const mat = new THREE.MeshBasicMaterial({ map: bandTex, side: THREE.DoubleSide });
                const mesh = new THREE.Mesh(new THREE.PlaneGeometry(w, height), mat);
                mesh.position.set(def.x || 0, def.y != null ? def.y : height * 0.5, def.z);
                group.add(mesh);
                meshes.push(mesh);
            });
        });

        return { group: group, meshes: meshes };
    }

    /**
     * Drop a single decorative sprite (torch, rubble, banner) at an
     * explicit depth -- the per-object equivalent of a 3dSen Depth/
     * Layer/ZScale edit. Purely cosmetic, not collidable -- lane
     * platforms (SidescrollPhysicsV3) own actual collision.
     */
    function placeSprite(scene, loader, path, opts) {
        opts = opts || {};
        const mat = new THREE.MeshBasicMaterial({
            transparent: true,
            side: THREE.DoubleSide,
            depthWrite: false
        });
        const w = opts.width || 40;
        const h = opts.height || w;
        const mesh = new THREE.Mesh(new THREE.PlaneGeometry(w, h), mat);
        mesh.position.set(opts.x || 0, opts.y != null ? opts.y : h * 0.5, opts.z || 0);
        loader.load(path, function (tex) {
            prep(tex);
            mat.map = tex;
            mat.needsUpdate = true;
        });
        scene.add(mesh);
        return mesh;
    }

    global.DepthStackV3 = {
        buildColorLayers: buildColorLayers,
        buildImageLayers: buildImageLayers,
        placeSprite: placeSprite
    };
})(window);
