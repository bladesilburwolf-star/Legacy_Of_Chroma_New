(function (global) {
    /**
     * PaintTerrainV3
     * ----------------
     * Simple terrain painter utility that derives vertex colors from
     * height + slope and exposes a soft brush to blend colors at runtime.
     *
     * API (summary):
     *   const p = PaintTerrainV3.create({ resolution: 128, bands: [...] });
     *   p.autoPaintFromHeights(heights);            // build base vertex colors
     *   p.paintBrush(cx, cz, radius, color, strength); // cx/cz in 0..1 UV space
     *   p.applyToMesh(mesh);                        // write colors into mesh.geometry
     *   const canvas = p.toCanvas();                // export current color map
     */

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

    function create(options) {
        options = options || {};
        const res = options.resolution || 64; // grid cells per side
        const verts = res + 1;
        const bands = options.bands || TerrainGeneratorV3 && TerrainGeneratorV3.DEFAULTS ? TerrainGeneratorV3.DEFAULTS.bands : [
            { max: 0.25, color: 0xc2b280 },
            { max: 0.55, color: 0x3d7a3d },
            { max: 0.80, color: 0x6b6b5a },
            { max: 1.01, color: 0xe8e8f0 }
        ];

        // Per-vertex RGB float buffer (0..1)
        const colors = new Float32Array(verts * verts * 3);

        // Optional internal splat weights (RGBA) for exporting a splatmap later
        const splat = new Float32Array(verts * verts * 4);

        function idx(x, z) { return z * verts + x; }

        function computeSlope(heights, x, z) {
            // central differences on height grid (0..1)
            function h(ix, iz) {
                ix = Math.max(0, Math.min(res, ix));
                iz = Math.max(0, Math.min(res, iz));
                return heights[iz * verts + ix];
            }
            const dx = h(x + 1, z) - h(x - 1, z);
            const dz = h(x, z + 1) - h(x, z - 1);
            return Math.sqrt(dx * dx + dz * dz);
        }

        function setVertexColorAtGrid(x, z, rgb) {
            const i = idx(x, z);
            colors[i * 3] = rgb[0];
            colors[i * 3 + 1] = rgb[1];
            colors[i * 3 + 2] = rgb[2];
        }

        function getVertexColorAtGrid(x, z) {
            const i = idx(x, z);
            return [colors[i * 3], colors[i * 3 + 1], colors[i * 3 + 2]];
        }

        function normalizeWeightsAt(i) {
            const a = splat[i * 4];
            const b = splat[i * 4 + 1];
            const c = splat[i * 4 + 2];
            const d = splat[i * 4 + 3];
            const sum = a + b + c + d + 1e-9;
            splat[i * 4] = a / sum;
            splat[i * 4 + 1] = b / sum;
            splat[i * 4 + 2] = c / sum;
            splat[i * 4 + 3] = d / sum;
        }

        const api = {
            resolution: res,
            verts: verts,
            colors: colors,
            splat: splat,
            bands: bands,

            /** Build base vertex colors from a Float32Array height buffer (0..1) */
            autoPaintFromHeights: function (heights, opts) {
                opts = opts || {};
                const slopeInfluence = opts.slopeInfluence != null ? opts.slopeInfluence : 0.9;
                for (let z = 0; z < verts; z++) {
                    for (let x = 0; x < verts; x++) {
                        const h01 = heights[z * verts + x];
                        const slope = computeSlope(heights, x, z);
                        // slope typically small; scale into 0..1 range heuristically
                        const s = Math.min(1, slope * 3.5);
                        // base band color
                        const base = bandColor(h01, bands);
                        // rock color (use middle band or rocks band if present)
                        const rock = bandColor(0.7, bands);
                        // blend base with rock based on slope
                        const out = [
                            base[0] * (1 - s * slopeInfluence) + rock[0] * (s * slopeInfluence),
                            base[1] * (1 - s * slopeInfluence) + rock[1] * (s * slopeInfluence),
                            base[2] * (1 - s * slopeInfluence) + rock[2] * (s * slopeInfluence)
                        ];
                        setVertexColorAtGrid(x, z, out);
                        // initial splat weight: put most weight into the band index matching h01
                        const bandIndex = (function () {
                            for (let i = 0; i < bands.length; i++) if (h01 <= bands[i].max) return i;
                            return bands.length - 1;
                        })();
                        const si = idx(x, z);
                        // map bandIndex into up to 4 channels using simple mapping
                        splat[si * 4 + 0] = bandIndex === 0 ? 1 : 0;
                        splat[si * 4 + 1] = bandIndex === 1 ? 1 : 0;
                        splat[si * 4 + 2] = bandIndex === 2 ? 1 : 0;
                        splat[si * 4 + 3] = bandIndex >= 3 ? 1 : 0;
                        normalizeWeightsAt(si);
                    }
                }
                return api;
            },

            /** Soft brush paint in normalized UV space (0..1). color is [r,g,b] 0..1 */
            paintBrush: function (cx, cz, radius, color, strength) {
                // cx/cz are UV 0..1
                const cr = radius * res;
                const cxI = cx * res;
                const czI = cz * res;
                const minX = Math.max(0, Math.floor(cxI - cr));
                const maxX = Math.min(res, Math.ceil(cxI + cr));
                const minZ = Math.max(0, Math.floor(czI - cr));
                const maxZ = Math.min(res, Math.ceil(czI + cr));
                for (let z = minZ; z <= maxZ; z++) {
                    for (let x = minX; x <= maxX; x++) {
                        const dx = x - cxI;
                        const dz = z - czI;
                        const d = Math.sqrt(dx * dx + dz * dz);
                        if (d > cr) continue;
                        const w = 1 - d / cr; // linear falloff
                        const i = idx(x, z);
                        // lerp existing color toward target color
                        const curR = colors[i * 3];
                        const curG = colors[i * 3 + 1];
                        const curB = colors[i * 3 + 2];
                        const k = strength * w;
                        colors[i * 3] = curR * (1 - k) + color[0] * k;
                        colors[i * 3 + 1] = curG * (1 - k) + color[1] * k;
                        colors[i * 3 + 2] = curB * (1 - k) + color[2] * k;
                        // optionally nudge splat weights toward a closest band by color similarity
                    }
                }
                return api;
            },

            /** Apply current vertex colors into a THREE.Mesh created by TerrainGeneratorV3 */
            applyToMesh: function (mesh) {
                if (!mesh || !mesh.geometry) return api;
                const geom = mesh.geometry;
                // Expect geom.attributes.position.count === verts*verts
                if (!geom.attributes.position) return api;
                const count = geom.attributes.position.count;
                // If geometry already has color attribute, overwrite, else create
                let colorAttr = geom.getAttribute('color');
                if (!colorAttr || colorAttr.count !== count) {
                    colorAttr = new THREE.BufferAttribute(new Float32Array(count * 3), 3);
                    geom.setAttribute('color', colorAttr);
                }
                // The vertex order in TerrainGeneratorV3 was col-major per row
                // Iterate rows/cols to copy colors
                for (let i = 0; i < count; i++) {
                    colorAttr.array[i * 3] = colors[i * 3];
                    colorAttr.array[i * 3 + 1] = colors[i * 3 + 1];
                    colorAttr.array[i * 3 + 2] = colors[i * 3 + 2];
                }
                colorAttr.needsUpdate = true;
                geom.computeVertexNormals();
                // Ensure the material uses vertexColors
                if (mesh.material) {
                    mesh.material.vertexColors = true;
                    mesh.material.needsUpdate = true;
                }
                return api;
            },

            /** Export current color map as an HTMLCanvasElement (verts x verts) */
            toCanvas: function () {
                const c = document.createElement('canvas');
                c.width = verts;
                c.height = verts;
                const ctx = c.getContext('2d');
                const img = ctx.createImageData(verts, verts);
                for (let z = 0; z < verts; z++) {
                    for (let x = 0; x < verts; x++) {
                        const i = idx(x, z);
                        const r = Math.max(0, Math.min(255, (colors[i * 3] * 255) | 0));
                        const g = Math.max(0, Math.min(255, (colors[i * 3 + 1] * 255) | 0));
                        const b = Math.max(0, Math.min(255, (colors[i * 3 + 2] * 255) | 0));
                        const pi = (z * verts + x) * 4;
                        img.data[pi] = r;
                        img.data[pi + 1] = g;
                        img.data[pi + 2] = b;
                        img.data[pi + 3] = 255;
                    }
                }
                ctx.putImageData(img, 0, 0);
                return c;
            },

            /** Convenience: paint then apply in one go */
            paintAndApply: function (mesh, cx, cz, radius, color, strength) {
                api.paintBrush(cx, cz, radius, color, strength);
                api.applyToMesh(mesh);
                return api;
            }
        };

        return api;
    }

    global.PaintTerrainV3 = { create: create };
})(typeof window !== 'undefined' ? window : this);