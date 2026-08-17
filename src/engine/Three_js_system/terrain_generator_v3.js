/**
 * TerrainGeneratorV3 -- CryEngine 1-style heightmap terrain
 * ----------------------------------------------------------
 * Classic CE1 approach (Far Cry era):
 *   - Fixed grid resolution (power-of-two friendly)
 *   - Height from grayscale heightmap (8-bit image) OR generated noise
 *   - World size in units, max height scale
 *   - getHeight(x, z) bilinear sample for player/collision
 *   - Optional height-band material tints (sand / grass / rock / snow)
 *
 * Not a full CE editor: no hole system, no detail foliage, no 16-bit RAW
 * loader yet -- those can layer on later. The data path is the same:
 * heightmap → vertex Y displacement → runtime height queries.
 *
 * Usage:
 *   const terrain = await TerrainGeneratorV3.create(scene, {
 *     resolution: 128,          // grid cells per side (vertices = res+1)
 *     size: 2560,               // world width & depth (square) OR
 *     sizeX: 2560, sizeZ: 5000, // rectangular zone
 *     maxHeight: 120,           // peak Y offset above floorY
 *     floorY: RoomBuilderV3.FLOOR_Y,
 *     heightmap: 'assets/overworld/height.png', // optional grayscale
 *     texture: 'assets/overworld/OVERWORLD GROUND.jpg',
 *     seed: 42,                 // used if no heightmap (value noise)
 *   });
 *
 *   // every frame:
 *   const y = terrain.getHeight(player.x, player.z);
 *   player.mesh.position.y = y + spriteHalfHeight;
 */
(function (global) {
    const DEFAULTS = {
        resolution: 64,
        size: 1000,
        sizeX: null,
        sizeZ: null,
        maxHeight: 80,
        floorY: -100,
        heightmap: null,
        texture: null,
        seed: 1,
        // CE1-ish height bands (normalized 0..1) for vertex colors
        bands: [
            { max: 0.25, color: 0xc2b280 }, // sand / low
            { max: 0.55, color: 0x3d7a3d }, // grass
            { max: 0.80, color: 0x6b6b5a }, // rock
            { max: 1.01, color: 0xe8e8f0 }  // snow / peak
        ]
    };

    // --- tiny seeded value noise (no external lib) ---
    function makeNoise(seed) {
        let s = seed | 0;
        function rand(n) {
            n = (n ^ s) * 0x27d4eb2d;
            n = (n ^ (n >>> 15)) * (n | 1);
            n = n + ((n ^ (n >>> 7)) << 9);
            n = n ^ (n >>> 14);
            return ((n >>> 0) % 10000) / 10000;
        }
        function smooth(t) { return t * t * (3 - 2 * t); }
        function value2(ix, iz) {
            return rand(ix * 374761393 + iz * 668265263);
        }
        function noise2(x, z) {
            const x0 = Math.floor(x), z0 = Math.floor(z);
            const fx = smooth(x - x0), fz = smooth(z - z0);
            const a = value2(x0, z0);
            const b = value2(x0 + 1, z0);
            const c = value2(x0, z0 + 1);
            const d = value2(x0 + 1, z0 + 1);
            const ab = a + (b - a) * fx;
            const cd = c + (d - c) * fx;
            return ab + (cd - ab) * fz;
        }
        // fbm
        return function fbm(x, z, octaves) {
            let v = 0, amp = 0.5, freq = 1;
            for (let i = 0; i < (octaves || 4); i++) {
                v += noise2(x * freq, z * freq) * amp;
                amp *= 0.5;
                freq *= 2;
            }
            return v;
        };
    }

    function prep(tex) {
        if (!tex) return tex;
        tex.magFilter = THREE.NearestFilter;
        tex.minFilter = THREE.NearestFilter;
        tex.generateMipmaps = false;
        tex.wrapS = THREE.RepeatWrapping;
        tex.wrapT = THREE.RepeatWrapping;
        return tex;
    }

    function bandColor(h01, bands) {
        for (let i = 0; i < bands.length; i++) {
            if (h01 <= bands[i].max) {
                const c = new THREE.Color(bands[i].color);
                return [c.r, c.g, c.b];
            }
        }
        const c = new THREE.Color(bands[bands.length - 1].color);
        return [c.r, c.g, c.b];
    }

    /**
     * Build height array [res+1][res+1] normalized 0..1
     */
    function buildHeightsFromNoise(res, seed) {
        const noise = makeNoise(seed);
        const verts = res + 1;
        const heights = new Float32Array(verts * verts);
        for (let iz = 0; iz < verts; iz++) {
            for (let ix = 0; ix < verts; ix++) {
                const nx = ix / res;
                const nz = iz / res;
                // CE1 starter maps were often gentle — low frequency fbm
                let h = noise(nx * 3.5, nz * 3.5, 5);
                // slight central dip so spawn areas stay flatter
                const cx = nx - 0.5, cz = nz - 0.5;
                const dist = Math.sqrt(cx * cx + cz * cz);
                h *= 0.35 + 0.65 * Math.min(1, dist * 1.8);
                heights[iz * verts + ix] = Math.max(0, Math.min(1, h));
            }
        }
        return heights;
    }

    function buildHeightsFromImage(img, res) {
        const verts = res + 1;
        const canvas = document.createElement('canvas');
        canvas.width = verts;
        canvas.height = verts;
        const ctx = canvas.getContext('2d');
        ctx.drawImage(img, 0, 0, verts, verts);
        const data = ctx.getImageData(0, 0, verts, verts).data;
        const heights = new Float32Array(verts * verts);
        for (let i = 0; i < verts * verts; i++) {
            // CE1 often used luminance; use R channel (grayscale maps)
            heights[i] = data[i * 4] / 255;
        }
        return heights;
    }

    function loadImage(url) {
        return new Promise((resolve, reject) => {
            const img = new Image();
            img.crossOrigin = 'anonymous';
            img.onload = () => resolve(img);
            img.onerror = reject;
            img.src = url;
        });
    }

    function createMesh(heights, res, sizeX, sizeZ, maxHeight, floorY, texture, bands) {
        const verts = res + 1;
        const geo = new THREE.PlaneGeometry(sizeX, sizeZ, res, res);
        geo.rotateX(-Math.PI / 2);

        const pos = geo.attributes.position;
        const colors = new Float32Array(pos.count * 3);

        for (let i = 0; i < pos.count; i++) {
            // PlaneGeometry segments: rows along Z, cols along X
            const col = i % verts;
            const row = (i / verts) | 0;
            const h01 = heights[row * verts + col];
            pos.setY(i, floorY + h01 * maxHeight);
            const rgb = bandColor(h01, bands);
            colors[i * 3] = rgb[0];
            colors[i * 3 + 1] = rgb[1];
            colors[i * 3 + 2] = rgb[2];
        }
        pos.needsUpdate = true;
        geo.setAttribute('color', new THREE.BufferAttribute(colors, 3));
        geo.computeVertexNormals();

        let mat;
        if (texture) {
            const tex = prep(texture);
            tex.repeat.set(sizeX / 256, sizeZ / 256);
            mat = new THREE.MeshBasicMaterial({
                map: tex,
                vertexColors: true,
                side: THREE.DoubleSide
            });
        } else {
            mat = new THREE.MeshBasicMaterial({
                vertexColors: true,
                side: THREE.DoubleSide
            });
        }

        const mesh = new THREE.Mesh(geo, mat);
        mesh.name = 'terrain-v3';
        mesh.receiveShadow = true;
        return mesh;
    }

    /**
     * Bilinear height sample in world XZ → world Y
     */
    function makeGetHeight(heights, res, sizeX, sizeZ, maxHeight, floorY) {
        const verts = res + 1;
        const halfX = sizeX * 0.5;
        const halfZ = sizeZ * 0.5;

        return function getHeight(worldX, worldZ) {
            // map world to 0..res grid
            let u = (worldX + halfX) / sizeX * res;
            let v = (worldZ + halfZ) / sizeZ * res;
            u = Math.max(0, Math.min(res, u));
            v = Math.max(0, Math.min(res, v));

            const x0 = Math.floor(u);
            const z0 = Math.floor(v);
            const x1 = Math.min(res, x0 + 1);
            const z1 = Math.min(res, z0 + 1);
            const fx = u - x0;
            const fz = v - z0;

            const h00 = heights[z0 * verts + x0];
            const h10 = heights[z0 * verts + x1];
            const h01 = heights[z1 * verts + x0];
            const h11 = heights[z1 * verts + x1];

            const h0 = h00 + (h10 - h00) * fx;
            const h1 = h01 + (h11 - h01) * fx;
            const h01b = h0 + (h1 - h0) * fz;

            return floorY + h01b * maxHeight;
        };
    }

    /**
     * Async create -- heightmap URL needs image load
     */
    function create(scene, options) {
        options = Object.assign({}, DEFAULTS, options || {});
        const res = options.resolution;
        const sizeX = options.sizeX != null ? options.sizeX : options.size;
        const sizeZ = options.sizeZ != null ? options.sizeZ : options.size;
        const maxHeight = options.maxHeight;
        const floorY = options.floorY;
        const bands = options.bands;

        function finish(heights, mapTex) {
            const mesh = createMesh(
                heights, res, sizeX, sizeZ, maxHeight, floorY, mapTex, bands
            );
            if (scene) scene.add(mesh);

            const api = {
                mesh: mesh,
                resolution: res,
                sizeX: sizeX,
                sizeZ: sizeZ,
                maxHeight: maxHeight,
                floorY: floorY,
                heights: heights,
                getHeight: makeGetHeight(heights, res, sizeX, sizeZ, maxHeight, floorY),
                /**
                 * Snap a Vector3's Y to terrain (keeps x/z)
                 */
                snap: function (pos, offsetY) {
                    offsetY = offsetY || 0;
                    pos.y = api.getHeight(pos.x, pos.z) + offsetY;
                    return pos;
                },
                dispose: function () {
                    mesh.geometry.dispose();
                    mesh.material.dispose();
                    if (mesh.parent) mesh.parent.remove(mesh);
                }
            };
            return api;
        }

        // Texture load helper
        function loadTex(url) {
            return new Promise((resolve) => {
                if (!url) { resolve(null); return; }
                const loader = new THREE.TextureLoader();
                loader.load(url, (tex) => resolve(prep(tex)), undefined, () => resolve(null));
            });
        }

        // Returns a Promise for heightmap path, or sync-ish for noise
        if (options.heightmap) {
            return Promise.all([
                loadImage(options.heightmap),
                loadTex(options.texture)
            ]).then(([img, tex]) => {
                const heights = buildHeightsFromImage(img, res);
                return finish(heights, tex);
            });
        }

        // Procedural CE1-style base
        return loadTex(options.texture).then((tex) => {
            const heights = buildHeightsFromNoise(res, options.seed);
            return finish(heights, tex);
        });
    }

    /**
     * Sync create when you already have a Float32Array height buffer
     * (e.g. decoded 8-bit RAW elsewhere).
     */
    function createFromHeights(scene, heights, options) {
        options = Object.assign({}, DEFAULTS, options || {});
        const res = options.resolution;
        const sizeX = options.sizeX != null ? options.sizeX : options.size;
        const sizeZ = options.sizeZ != null ? options.sizeZ : options.size;
        const mesh = createMesh(
            heights, res, sizeX, sizeZ,
            options.maxHeight, options.floorY, null, options.bands
        );
        if (options.texture && typeof options.texture !== 'string') {
            mesh.material.map = prep(options.texture);
            mesh.material.needsUpdate = true;
        }
        if (scene) scene.add(mesh);
        return {
            mesh: mesh,
            resolution: res,
            sizeX: sizeX,
            sizeZ: sizeZ,
            maxHeight: options.maxHeight,
            floorY: options.floorY,
            heights: heights,
            getHeight: makeGetHeight(heights, res, sizeX, sizeZ, options.maxHeight, options.floorY),
            snap: function (pos, offsetY) {
                pos.y = this.getHeight(pos.x, pos.z) + (offsetY || 0);
                return pos;
            },
            dispose: function () {
                mesh.geometry.dispose();
                mesh.material.dispose();
                if (mesh.parent) mesh.parent.remove(mesh);
            }
        };
    }

    global.TerrainGeneratorV3 = {
        create: create,
        createFromHeights: createFromHeights,
        DEFAULTS: DEFAULTS
    };
})(typeof window !== 'undefined' ? window : this);
