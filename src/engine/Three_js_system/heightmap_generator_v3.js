/**
 * HeightmapGeneratorV3 -- procedural / editable heightmap baker
 * -------------------------------------------------------------
 * Produces grayscale height buffers (Float32 0..1 and optional
 * 8-bit ImageData / canvas) for TerrainGeneratorV3 or export.
 *
 * CE1 workflow: generate or paint height → feed TerrainGenerator
 * or download as PNG for the art pipeline.
 *
 * Usage:
 *   const hm = HeightmapGeneratorV3.create({ resolution: 128, seed: 7 });
 *   hm.fbm({ scale: 3.5, octaves: 5 });
 *   hm.flattenCircle(0.5, 0.5, 0.15, 0.1); // spawn plateau
 *   const heights = hm.getFloats();
 *   const canvas = hm.toCanvas();
 *   TerrainGeneratorV3.createFromHeights(scene, heights, { resolution: 128, ... });
 */
(function (global) {
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
            const a = value2(x0, z0), b = value2(x0 + 1, z0);
            const c = value2(x0, z0 + 1), d = value2(x0 + 1, z0 + 1);
            return a + (b - a) * fx + (c - a) * fz + (a - b - c + d) * fx * fz;
        }
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

    function create(options) {
        options = options || {};
        const res = options.resolution || 64;
        const verts = res + 1;
        const seed = options.seed != null ? options.seed : 1;
        const data = new Float32Array(verts * verts);

        function idx(x, z) { return z * verts + x; }

        const api = {
            resolution: res,
            verts: verts,
            seed: seed,

            fill: function (value) {
                data.fill(Math.max(0, Math.min(1, value)));
                return api;
            },

            /** Classic multi-octave value noise */
            fbm: function (opts) {
                opts = opts || {};
                const scale = opts.scale || 3.5;
                const octaves = opts.octaves || 5;
                const noise = makeNoise(seed);
                for (let z = 0; z < verts; z++) {
                    for (let x = 0; x < verts; x++) {
                        const n = noise((x / res) * scale, (z / res) * scale, octaves);
                        data[idx(x, z)] = Math.max(0, Math.min(1, n));
                    }
                }
                return api;
            },

            /** Ridged / mountain-style */
            ridged: function (opts) {
                opts = opts || {};
                const scale = opts.scale || 4;
                const octaves = opts.octaves || 5;
                const noise = makeNoise(seed + 99);
                for (let z = 0; z < verts; z++) {
                    for (let x = 0; x < verts; x++) {
                        let v = 0, amp = 0.5, freq = 1;
                        for (let o = 0; o < octaves; o++) {
                            const n = noise((x / res) * scale * freq, (z / res) * scale * freq, 1);
                            v += (1 - Math.abs(n * 2 - 1)) * amp;
                            amp *= 0.5;
                            freq *= 2;
                        }
                        data[idx(x, z)] = Math.max(0, Math.min(1, v));
                    }
                }
                return api;
            },

            /** Flatten a circular plateau (normalized uv 0..1) */
            flattenCircle: function (cx, cz, radius, height) {
                height = height != null ? height : 0;
                for (let z = 0; z < verts; z++) {
                    for (let x = 0; x < verts; x++) {
                        const u = x / res, v = z / res;
                        const d = Math.sqrt((u - cx) * (u - cx) + (v - cz) * (v - cz));
                        if (d < radius) {
                            const t = d / radius;
                            const w = 1 - t * t * (3 - 2 * t);
                            data[idx(x, z)] = data[idx(x, z)] * (1 - w) + height * w;
                        }
                    }
                }
                return api;
            },

            /** Soft raise / lower brush */
            brush: function (cx, cz, radius, amount) {
                for (let z = 0; z < verts; z++) {
                    for (let x = 0; x < verts; x++) {
                        const u = x / res, v = z / res;
                        const d = Math.sqrt((u - cx) * (u - cx) + (v - cz) * (v - cz));
                        if (d < radius) {
                            const w = 1 - d / radius;
                            data[idx(x, z)] = Math.max(0, Math.min(1, data[idx(x, z)] + amount * w * w));
                        }
                    }
                }
                return api;
            },

            getFloats: function () { return data; },

            /** 8-bit grayscale ImageData */
            toImageData: function () {
                const img = new ImageData(verts, verts);
                for (let i = 0; i < verts * verts; i++) {
                    const g = Math.max(0, Math.min(255, (data[i] * 255) | 0));
                    img.data[i * 4] = g;
                    img.data[i * 4 + 1] = g;
                    img.data[i * 4 + 2] = g;
                    img.data[i * 4 + 3] = 255;
                }
                return img;
            },

            toCanvas: function () {
                const c = document.createElement('canvas');
                c.width = verts;
                c.height = verts;
                c.getContext('2d').putImageData(api.toImageData(), 0, 0);
                return c;
            },

            /** Trigger browser download of PNG */
            downloadPNG: function (filename) {
                const c = api.toCanvas();
                const a = document.createElement('a');
                a.href = c.toDataURL('image/png');
                a.download = filename || 'heightmap.png';
                a.click();
            }
        };

        return api;
    }

    global.HeightmapGeneratorV3 = { create: create };
})(typeof window !== 'undefined' ? window : this);
