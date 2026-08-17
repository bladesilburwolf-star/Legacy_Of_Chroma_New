/**
 * PaintTerrainV3 — Unity/CryEngine-style splatmap terrain
 * ---------------------------------------------------------
 * RGBA splat control map blends 4 albedo layers in a shader:
 *   R = grass, G = snow, B = rock, A = sand/path
 *
 *   const painter = PaintTerrainV3.create({ resolution: 512 });
 *   painter.setupMaterial(loader, { grass, snow, rock, sand, tiling });
 *   painter.autoPaintFromHeights(heights); // 0..1 height grid
 *   painter.paintBrush(u, v, radius01, 'rock', 0.7); // UV 0..1
 *   painter.applyToMesh(terrain.mesh);
 */
(function (global) {
    const TerrainSplatShader = {
        uniforms: {
            uSplatMap: { value: null },
            uGrassTex: { value: null },
            uSnowTex: { value: null },
            uRockTex: { value: null },
            uSandTex: { value: null },
            uTiling: { value: 64.0 },
            uSunDir: { value: null },
            uAmbient: { value: null }
        },
        vertexShader: [
            'varying vec2 vUv;',
            'varying vec3 vNormal;',
            'void main() {',
            '  vUv = uv;',
            '  vNormal = normalize(normalMatrix * normal);',
            '  gl_Position = projectionMatrix * modelViewMatrix * vec4(position, 1.0);',
            '}'
        ].join('\n'),
        fragmentShader: [
            'uniform sampler2D uSplatMap;',
            'uniform sampler2D uGrassTex;',
            'uniform sampler2D uSnowTex;',
            'uniform sampler2D uRockTex;',
            'uniform sampler2D uSandTex;',
            'uniform float uTiling;',
            'uniform vec3 uSunDir;',
            'uniform vec3 uAmbient;',
            'varying vec2 vUv;',
            'varying vec3 vNormal;',
            'void main() {',
            '  vec4 w = texture2D(uSplatMap, vUv);',
            '  float t = w.r + w.g + w.b + w.a;',
            '  if (t > 0.001) w /= t; else w = vec4(1.0, 0.0, 0.0, 0.0);',
            '  vec2 tuv = vUv * uTiling;',
            '  vec3 col = texture2D(uGrassTex, tuv).rgb * w.r',
            '           + texture2D(uSnowTex,  tuv).rgb * w.g',
            '           + texture2D(uRockTex,  tuv).rgb * w.b',
            '           + texture2D(uSandTex,  tuv).rgb * w.a;',
            '  float diff = max(dot(normalize(vNormal), normalize(uSunDir)), 0.0);',
            '  vec3 lit = uAmbient + vec3(1.0, 0.95, 0.85) * diff * 0.9;',
            '  gl_FragColor = vec4(col * lit, 1.0);',
            '}'
        ].join('\n')
    };

    function create(opts) {
        opts = opts || {};
        const res = opts.resolution || 512;
        const canvas = document.createElement('canvas');
        canvas.width = res;
        canvas.height = res;
        const ctx = canvas.getContext('2d');
        // base = full grass (R)
        ctx.fillStyle = 'rgba(255,0,0,1)';
        ctx.fillRect(0, 0, res, res);

        const splatTexture = new THREE.CanvasTexture(canvas);
        splatTexture.magFilter = THREE.LinearFilter;
        splatTexture.minFilter = THREE.LinearMipmapLinearFilter;
        splatTexture.generateMipmaps = true;
        splatTexture.needsUpdate = true;

        let material = null;

        function prep(loader, path) {
            const tex = loader.load(path);
            tex.wrapS = THREE.RepeatWrapping;
            tex.wrapT = THREE.RepeatWrapping;
            tex.magFilter = THREE.LinearFilter;
            tex.minFilter = THREE.LinearMipmapLinearFilter;
            return tex;
        }

        const api = {
            canvas: canvas,
            splatTexture: splatTexture,
            res: res,

            setupMaterial: function (loader, options) {
                options = options || {};
                const grass = prep(loader, options.grass || 'assets/textures/grass1.jpg');
                const snow = prep(loader, options.snow || 'assets/textures/stone2.jpg');
                const rock = prep(loader, options.rock || 'assets/textures/rock1.jpg');
                const sand = prep(loader, options.sand || 'assets/textures/sand1.jpg');

                const uniforms = {
                    uSplatMap: { value: splatTexture },
                    uGrassTex: { value: grass },
                    uSnowTex: { value: snow },
                    uRockTex: { value: rock },
                    uSandTex: { value: sand },
                    uTiling: { value: options.tiling != null ? options.tiling : 72.0 },
                    uSunDir: { value: new THREE.Vector3(0.45, 1.0, 0.25).normalize() },
                    uAmbient: { value: new THREE.Color(0x3a4a3a) }
                };

                material = new THREE.ShaderMaterial({
                    uniforms: uniforms,
                    vertexShader: TerrainSplatShader.vertexShader,
                    fragmentShader: TerrainSplatShader.fragmentShader,
                    side: THREE.DoubleSide
                });
                return material;
            },

            /**
             * heights: Float32Array length (n+1)^2 or n*n of values in 0..1
             * (or 0..maxHeight — auto-detect if max > 1.5)
             */
            autoPaintFromHeights: function (heights, paintOpts) {
                paintOpts = paintOpts || {};
                const img = ctx.getImageData(0, 0, res, res);
                const data = img.data;
                const n = heights.length;
                const gridDim = Math.round(Math.sqrt(n));
                let maxH = 0;
                for (let i = 0; i < n; i++) if (heights[i] > maxH) maxH = heights[i];
                const scale = maxH > 1.5 ? (1 / maxH) : 1;

                const snowH = paintOpts.snowHeight != null ? paintOpts.snowHeight : 0.72;
                const rockH = paintOpts.rockHeight != null ? paintOpts.rockHeight : 0.48;
                const sandH = paintOpts.sandHeight != null ? paintOpts.sandHeight : 0.08;

                for (let y = 0; y < res; y++) {
                    for (let x = 0; x < res; x++) {
                        const u = x / (res - 1);
                        const v = y / (res - 1);
                        const gx = Math.min(gridDim - 1, Math.floor(u * (gridDim - 1)));
                        const gy = Math.min(gridDim - 1, Math.floor(v * (gridDim - 1)));
                        // slope from neighbors
                        const gx1 = Math.min(gridDim - 1, gx + 1);
                        const gy1 = Math.min(gridDim - 1, gy + 1);
                        const h00 = (heights[gy * gridDim + gx] || 0) * scale;
                        const h10 = (heights[gy * gridDim + gx1] || 0) * scale;
                        const h01 = (heights[gy1 * gridDim + gx] || 0) * scale;
                        const slope = Math.min(1, Math.abs(h10 - h00) * 8 + Math.abs(h01 - h00) * 8);
                        const h = h00;

                        let r = 1, g = 0, b = 0, a = 0; // grass default
                        if (h >= snowH) {
                            g = 1; r = 0.05; b = 0.05 * slope; a = 0;
                        } else if (h >= rockH || slope > 0.35) {
                            const k = Math.min(1, slope + (h - rockH) * 2);
                            b = 0.55 + 0.45 * k;
                            r = 0.35 * (1 - k);
                            g = 0.1;
                            a = 0;
                        } else if (h <= sandH) {
                            a = 1; r = 0.05; g = 0; b = 0;
                        } else {
                            // mid grass with slight dirt (sand) in valleys
                            r = 0.85;
                            a = 0.15 * (1 - (h - sandH) / Math.max(0.01, rockH - sandH));
                            b = 0.1 * slope;
                        }
                        const sum = r + g + b + a || 1;
                        const i = (y * res + x) * 4;
                        data[i] = Math.round((r / sum) * 255);
                        data[i + 1] = Math.round((g / sum) * 255);
                        data[i + 2] = Math.round((b / sum) * 255);
                        data[i + 3] = Math.round((a / sum) * 255);
                    }
                }
                ctx.putImageData(img, 0, 0);
                splatTexture.needsUpdate = true;
                return api;
            },

            /**
             * UV brush. radius is 0..1 fraction of map (or pixels if > 1).
             * channel: 'grass'|'snow'|'rock'|'sand'
             */
            paintBrush: function (u, v, radius, channel, opacity) {
                opacity = opacity != null ? opacity : 0.65;
                channel = (channel || 'grass').toLowerCase();
                let radPx = radius;
                if (radius <= 1) radPx = radius * res;
                radPx = Math.max(2, radPx);

                // canvas y often flipped vs terrain v
                const cx = u * res;
                const cy = (1 - v) * res;

                const img = ctx.getImageData(0, 0, res, res);
                const data = img.data;
                const r0 = Math.max(0, Math.floor(cx - radPx));
                const r1 = Math.min(res - 1, Math.ceil(cx + radPx));
                const c0 = Math.max(0, Math.floor(cy - radPx));
                const c1 = Math.min(res - 1, Math.ceil(cy + radPx));

                let tr = 0, tg = 0, tb = 0, ta = 0;
                if (channel === 'grass') tr = 1;
                else if (channel === 'snow') tg = 1;
                else if (channel === 'rock') tb = 1;
                else if (channel === 'sand' || channel === 'path' || channel === 'dirt') ta = 1;
                else tr = 1;

                for (let y = c0; y <= c1; y++) {
                    for (let x = r0; x <= r1; x++) {
                        const dx = x - cx, dy = y - cy;
                        const d = Math.sqrt(dx * dx + dy * dy);
                        if (d > radPx) continue;
                        const fall = Math.pow(1 - d / radPx, 1.6) * opacity;
                        const i = (y * res + x) * 4;
                        let r = data[i] / 255;
                        let g = data[i + 1] / 255;
                        let b = data[i + 2] / 255;
                        let a = data[i + 3] / 255;
                        r = r * (1 - fall) + tr * fall;
                        g = g * (1 - fall) + tg * fall;
                        b = b * (1 - fall) + tb * fall;
                        a = a * (1 - fall) + ta * fall;
                        const s = r + g + b + a || 1;
                        data[i] = Math.round((r / s) * 255);
                        data[i + 1] = Math.round((g / s) * 255);
                        data[i + 2] = Math.round((b / s) * 255);
                        data[i + 3] = Math.round((a / s) * 255);
                    }
                }
                ctx.putImageData(img, 0, 0);
                splatTexture.needsUpdate = true;
                return api;
            },

            applyToMesh: function (mesh) {
                if (mesh && material) {
                    if (mesh.material && mesh.material.dispose) {
                        try { mesh.material.dispose(); } catch (e) {}
                    }
                    mesh.material = material;
                    mesh.material.needsUpdate = true;
                }
                return api;
            },

            getMaterial: function () { return material; }
        };

        return api;
    }

    global.PaintTerrainV3 = { create: create };
})(typeof window !== 'undefined' ? window : this);
