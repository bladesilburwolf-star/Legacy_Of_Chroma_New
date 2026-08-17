/**
 * WaterSystemV3 — Lightweight animated water plane module
 * ---------------------------------------------------------
 * Features:
 *   - Dual-scrolling normal/water textures for natural flow.
 *   - Procedural wave height movement via custom vertex shader.
 *   - Easy setup for water line height relative to terrain.
 *
 * Usage:
 *   const water = WaterSystemV3.create(scene, loader, {
 *       sizeX: 12000, sizeZ: 14000, waterY: 15
 *   });
 *   // In animate loop:
 *   water.update(deltaTime);
 */
(function (global) {
    const WaterShader = {
        uniforms: {
            uTime: { value: 0.0 },
            uWaterColor: { value: null },
            uDeepColor: { value: null },
            uSunDir: { value: null },
            uNormalMap: { value: null },
            uTiling: { value: 32.0 }
        },
        vertexShader: [
            'varying vec2 vUv;',
            'varying vec3 vWorldPos;',
            'uniform float uTime;',
            'void main() {',
            '  vUv = uv;',
            '  vec3 pos = position;',
            '  // Subtle surface wave displacement',
            '  float wave = sin(pos.x * 0.05 + uTime * 2.0) * cos(pos.z * 0.05 + uTime * 1.5) * 1.2;',
            '  pos.y += wave;',
            '  vec4 worldPosition = modelMatrix * vec4(pos, 1.0);',
            '  vWorldPos = worldPosition.xyz;',
            '  gl_Position = projectionMatrix * modelViewMatrix * vec4(pos, 1.0);',
            '}'
        ].join('\n'),
        fragmentShader: [
            'uniform float uTime;',
            'uniform vec3 uWaterColor;',
            'uniform vec3 uDeepColor;',
            'uniform vec3 uSunDir;',
            'uniform sampler2D uNormalMap;',
            'uniform float uTiling;',
            'varying vec2 vUv;',
            'varying vec3 vWorldPos;',
            'void main() {',
            '  // Dual scrolling UVs for normal map simulation',
            '  vec2 uv1 = vUv * uTiling + vec2(uTime * 0.02, uTime * 0.015);',
            '  vec2 uv2 = vUv * uTiling * 1.5 - vec2(uTime * 0.015, uTime * 0.03);',
            '  vec3 n1 = texture2D(uNormalMap, uv1).rgb * 2.0 - 1.0;',
            '  vec3 n2 = texture2D(uNormalMap, uv2).rgb * 2.0 - 1.0;',
            '  vec3 normal = normalize(n1 + n2);',
            '  ',
            '  float diff = max(dot(normal, normalize(uSunDir)), 0.0);',
            '  vec3 col = mix(uDeepColor, uWaterColor, 0.6) + vec3(0.2) * diff;',
            '  gl_FragColor = vec4(col, 0.78);',
            '}'
        ].join('\n')
    };

    function create(scene, loader, opts) {
        opts = opts || {};
        const sizeX = opts.sizeX || 10000;
        const sizeZ = opts.sizeZ || 10000;
        const waterY = opts.waterY !== undefined ? opts.waterY : 12;

        const geo = new THREE.PlaneGeometry(sizeX, sizeZ, 64, 64);
        geo.rotateX(-Math.PI / 2);

        // Fallback procedural canvas normal map if texture isn't supplied
        let normTex = null;
        if (opts.normalMap) {
            normTex = loader.load(opts.normalMap);
        } else {
            const canvas = document.createElement('canvas');
            canvas.width = canvas.height = 128;
            const ctx = canvas.getContext('2d');
            ctx.fillStyle = 'rgb(128,128,255)';
            ctx.fillRect(0, 0, 128, 128);
            normTex = new THREE.CanvasTexture(canvas);
        }
        normTex.wrapS = normTex.wrapT = THREE.RepeatWrapping;

        const uniforms = {
            uTime: { value: 0 },
            uWaterColor: { value: new THREE.Color(opts.shallowColor || 0x1a8899) },
            uDeepColor: { value: new THREE.Color(opts.deepColor || 0x041828) },
            uSunDir: { value: new THREE.Vector3(0.45, 1.0, 0.25).normalize() },
            uNormalMap: { value: normTex },
            uTiling: { value: opts.tiling || 48.0 }
        };

        const material = new THREE.ShaderMaterial({
            uniforms: uniforms,
            vertexShader: WaterShader.vertexShader,
            fragmentShader: WaterShader.fragmentShader,
            transparent: true,
            side: THREE.DoubleSide
        });

        const mesh = new THREE.Mesh(geo, material);
        mesh.position.y = waterY;
        if (scene) scene.add(mesh);

        return {
            mesh: mesh,
            waterY: waterY,
            update: function (dt) {
                uniforms.uTime.value += dt || 0.016;
            },
            setWaterLevel: function (y) {
                this.waterY = y;
                mesh.position.y = y;
            }
        };
    }

    global.WaterSystemV3 = { create: create };
})(typeof window !== 'undefined' ? window : this);