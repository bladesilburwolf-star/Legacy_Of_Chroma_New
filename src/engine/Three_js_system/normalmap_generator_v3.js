/**
 * NormalmapGeneratorV3 -- bake tangent-space normals from height data
 * -------------------------------------------------------------------
 * CE1/detail workflow: heightmap → sobel/central-diff normals →
 * RGB normal map (OpenGL convention: +X right, +Y up, +Z toward camera).
 *
 * Usage:
 *   const nrm = NormalmapGeneratorV3.fromHeights(heightFloats, resolution, {
 *     strength: 2.5
 *   });
 *   const canvas = nrm.toCanvas();
 *   // use as THREE.Texture for MeshStandardMaterial.normalMap later
 */
(function (global) {
    function fromHeights(heights, resolution, options) {
        options = options || {};
        const strength = options.strength != null ? options.strength : 2.0;
        const res = resolution;
        const verts = res + 1;
        // Output same grid size as height verts
        const out = new Uint8ClampedArray(verts * verts * 4);

        function h(x, z) {
            x = Math.max(0, Math.min(res, x));
            z = Math.max(0, Math.min(res, z));
            return heights[z * verts + x];
        }

        for (let z = 0; z < verts; z++) {
            for (let x = 0; x < verts; x++) {
                // Central differences
                const dx = (h(x + 1, z) - h(x - 1, z)) * strength;
                const dz = (h(x, z + 1) - h(x, z - 1)) * strength;
                // Normal ≈ (-dx, 1, -dz)
                let nx = -dx;
                let ny = 1.0;
                let nz = -dz;
                const len = Math.sqrt(nx * nx + ny * ny + nz * nz) || 1;
                nx /= len; ny /= len; nz /= len;
                // Map [-1,1] → [0,255]
                const i = (z * verts + x) * 4;
                out[i]     = ((nx * 0.5 + 0.5) * 255) | 0;
                out[i + 1] = ((ny * 0.5 + 0.5) * 255) | 0;
                out[i + 2] = ((nz * 0.5 + 0.5) * 255) | 0;
                out[i + 3] = 255;
            }
        }

        const api = {
            resolution: res,
            verts: verts,
            data: out,

            toImageData: function () {
                return new ImageData(out, verts, verts);
            },

            toCanvas: function () {
                const c = document.createElement('canvas');
                c.width = verts;
                c.height = verts;
                c.getContext('2d').putImageData(api.toImageData(), 0, 0);
                return c;
            },

            /** THREE.Texture ready for materials (call after THREE is loaded) */
            toTexture: function () {
                const tex = new THREE.CanvasTexture(api.toCanvas());
                tex.magFilter = THREE.LinearFilter;
                tex.minFilter = THREE.LinearFilter;
                tex.generateMipmaps = true;
                return tex;
            },

            downloadPNG: function (filename) {
                const a = document.createElement('a');
                a.href = api.toCanvas().toDataURL('image/png');
                a.download = filename || 'normalmap.png';
                a.click();
            }
        };
        return api;
    }

    /**
     * Convenience: from a HeightmapGeneratorV3 instance
     */
    function fromHeightmap(hm, options) {
        return fromHeights(hm.getFloats(), hm.resolution, options);
    }

    global.NormalmapGeneratorV3 = {
        fromHeights: fromHeights,
        fromHeightmap: fromHeightmap
    };
})(typeof window !== 'undefined' ? window : this);
