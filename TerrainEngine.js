import * as THREE from 'three';

export class TerrainEngine {
    constructor(options = {}) {
        this.sizeX = options.sizeX || 128;
        this.sizeZ = options.sizeZ || 128;
        this.segmentsX = options.segmentsX || 64;
        this.segmentsZ = options.segmentsZ || 64;
        this.maxHeight = options.maxHeight || 15;
        this.floorY = options.floorY || 0;
        this.res = options.res || 512; // Splat map resolution

        this.heightMap = new Float32Array((this.segmentsX + 1) * (this.segmentsZ + 1));
        
        // Setup Canvas & Context for Splat Painting
        this.canvas = document.createElement('canvas');
        this.canvas.width = this.res;
        this.canvas.height = this.res;
        this.ctx = this.canvas.getContext('2d');

        this.splatTexture = new THREE.CanvasTexture(this.canvas);
        this.splatTexture.wrapS = THREE.ClampToEdgeWrapping;
        this.splatTexture.wrapT = THREE.ClampToEdgeWrapping;

        this.initCanvas();
        this.mesh = null;
    }

    initCanvas() {
        // Red = Grass (Default Base), Green = Rock, Blue = Dirt, Alpha = Sand
        this.ctx.fillStyle = 'rgba(255, 0, 0, 1.0)';
        this.ctx.fillRect(0, 0, this.res, this.res);
        this.splatTexture.needsUpdate = true;
    }

    // --- TERRAIN GENERATION ---
    generateHeightMap(heightFunction) {
        let idx = 0;
        for (let z = 0; z <= this.segmentsZ; z++) {
            for (let x = 0; x <= this.segmentsX; x++) {
                const nx = x / this.segmentsX;
                const nz = z / this.segmentsZ;
                // Normalize height from 0.0 to 1.0
                const h = heightFunction ? heightFunction(nx, nz) : 0;
                this.heightMap[idx++] = Math.max(0, Math.min(1, h));
            }
        }
    }

    createMesh(textures = {}) {
        const geo = new THREE.PlaneGeometry(
            this.sizeX, 
            this.sizeZ, 
            this.segmentsX, 
            this.segmentsZ
        );
        geo.rotateX(-Math.PI / 2); // Orient horizontal (XZ plane)

        const posAttr = geo.attributes.position;
        let idx = 0;

        for (let z = 0; z <= this.segmentsZ; z++) {
            for (let x = 0; x <= this.segmentsX; x++) {
                const vertIdx = (z * (this.segmentsX + 1) + x) * 3;
                const h = this.heightMap[idx++];
                posAttr.array[vertIdx + 1] = this.floorY + (h * this.maxHeight);
            }
        }

        geo.computeVertexNormals();

        // Custom Shader Material for Texture Splatting
        const mat = new THREE.ShaderMaterial({
            uniforms: {
                uSplatMap: { value: this.splatTexture },
                uTexGrass: { value: textures.grass || null },
                uTexRock:  { value: textures.rock || null },
                uTexDirt:  { value: textures.dirt || null },
                uTexSand:  { value: textures.sand || null },
                uRepeat:   { value: new THREE.Vector2(8, 8) }
            },
            vertexShader: `
                varying vec2 vUv;
                varying vec3 vNormal;
                varying vec3 vWorldPosition;

                void main() {
                    vUv = uv;
                    vNormal = normalize(normalMatrix * normal);
                    vec4 worldPosition = modelMatrix * vec4(position, 1.0);
                    vWorldPosition = worldPosition.xyz;
                    gl_Position = projectionMatrix * viewMatrix * worldPosition;
                }
            `,
            fragmentShader: `
                uniform sampler2D uSplatMap;
                uniform sampler2D uTexGrass;
                uniform sampler2D uTexRock;
                uniform sampler2D uTexDirt;
                uniform sampler2D uTexSand;
                uniform vec2 uRepeat;

                varying vec2 vUv;
                varying vec3 vNormal;
                varying vec3 vWorldPosition;

                void main() {
                    vec4 splat = texture2D(uSplatMap, vUv);
                    vec2 tiledUv = vUv * uRepeat;

                    vec4 colGrass = texture2D(uTexGrass, tiledUv);
                    vec4 colRock  = texture2D(uTexRock, tiledUv);
                    vec4 colDirt  = texture2D(uTexDirt, tiledUv);
                    vec4 colSand  = texture2D(uTexSand, tiledUv);

                    // Normalize splat weights to prevent color bleed or dark spots
                    float totalWeight = splat.r + splat.g + splat.b + splat.a;
                    vec4 weights = (totalWeight > 0.0) ? splat / totalWeight : vec4(1.0, 0.0, 0.0, 0.0);

                    vec4 finalColor = colGrass * weights.r +
                                      colRock  * weights.g +
                                      colDirt  * weights.b +
                                      colSand  * weights.a;

                    // Basic direction lighting
                    vec3 lightDir = normalize(vec3(0.5, 1.0, 0.5));
                    float diff = max(dot(vNormal, lightDir), 0.3);

                    gl_FragColor = vec4(finalColor.rgb * diff, 1.0);
                }
            `,
            side: THREE.DoubleSide
        });

        this.mesh = new THREE.Mesh(geo, mat);
        return this.mesh;
    }

    // --- FIXED SPLAT PAINTING ---
    // Fixes UV inversion and ensures canvas alpha channel works cleanly
    paintBrush(worldX, worldZ, radius = 5.0, channel = 'grass', strength = 0.5) {
        if (!this.mesh) return;

        // Convert World X,Z to UV (0.0 - 1.0)
        const u = (worldX + this.sizeX / 2) / this.sizeX;
        const v = (worldZ + this.sizeZ / 2) / this.sizeZ;

        if (u < 0 || u > 1 || v < 0 || v > 1) return;

        // Canvas Y standard coordinate mapping (no double-inversion)
        const cx = u * this.res;
        const cy = v * this.res;
        const radPx = (radius / this.sizeX) * this.res;

        const grad = this.ctx.createRadialGradient(cx, cy, 0, cx, cy, radPx);
        
        let color = '255, 0, 0'; // Grass
        if (channel === 'rock') color = '0, 255, 0';
        if (channel === 'dirt') color = '0, 0, 255';
        
        if (channel === 'sand') {
            // Sand uses alpha blend channel without zeroing RGB
            this.ctx.globalCompositeOperation = 'source-over';
            grad.addColorStop(0, `rgba(0, 0, 0, ${strength})`);
            grad.addColorStop(1, 'rgba(0, 0, 0, 0)');
        } else {
            this.ctx.globalCompositeOperation = 'source-over';
            grad.addColorStop(0, `rgba(${color}, ${strength})`);
            grad.addColorStop(1, `rgba(${color}, 0)`);
        }

        this.ctx.fillStyle = grad;
        this.ctx.beginPath();
        this.ctx.arc(cx, cy, radPx, 0, Math.PI * 2);
        this.ctx.fill();

        this.splatTexture.needsUpdate = true;
    }

    autoPaintFromHeights() {
        const imgData = this.ctx.createImageData(this.res, this.res);
        const data = imgData.data;

        for (let py = 0; py < this.res; py++) {
            for (let px = 0; px < this.res; px++) {
                const u = px / (this.res - 1);
                const v = py / (this.res - 1);

                const gx = Math.floor(u * this.segmentsX);
                const gz = Math.floor(v * this.segmentsZ);
                const idx = gz * (this.segmentsX + 1) + gx;

                const h = this.heightMap[idx] || 0;
                const pIdx = (py * this.res + px) * 4;

                // Auto-rule mapping by height
                if (h < 0.2) {
                    // Sand
                    data[pIdx] = 0; data[pIdx + 1] = 0; data[pIdx + 2] = 0; data[pIdx + 3] = 255;
                } else if (h < 0.6) {
                    // Grass
                    data[pIdx] = 255; data[pIdx + 1] = 0; data[pIdx + 2] = 0; data[pIdx + 3] = 255;
                } else if (h < 0.85) {
                    // Dirt
                    data[pIdx] = 0; data[pIdx + 1] = 0; data[pIdx + 2] = 255; data[pIdx + 3] = 255;
                } else {
                    // Rock
                    data[pIdx] = 0; data[pIdx + 1] = 255; data[pIdx + 2] = 0; data[pIdx + 3] = 255;
                }
            }
        }

        this.ctx.putImageData(imgData, 0, 0);
        this.splatTexture.needsUpdate = true;
    }

    // --- ACCURATE HEIGHT SAMPLING & COLLISION ---
    // Clamps boundaries correctly and removes duplicate Y floor additions
    getHeight(worldX, worldZ) {
        // Map world space (centered at origin) to grid UV (0.0 to 1.0)
        const u = (worldX + this.sizeX / 2) / this.sizeX;
        const v = (worldZ + this.sizeZ / 2) / this.sizeZ;

        // Clamp inside valid grid range to prevent array lookup bounds crash
        const clampedU = Math.max(0, Math.min(1, u));
        const clampedV = Math.max(0, Math.min(1, v));

        const gx = clampedU * this.segmentsX;
        const gz = clampedV * this.segmentsZ;

        const x0 = Math.floor(gx);
        const z0 = Math.floor(gz);
        const x1 = Math.min(x0 + 1, this.segmentsX);
        const z1 = Math.min(z0 + 1, this.segmentsZ);

        const tx = gx - x0;
        const tz = gz - z0;

        const stride = this.segmentsX + 1;
        const h00 = this.heightMap[z0 * stride + x0] || 0;
        const h10 = this.heightMap[z0 * stride + x1] || 0;
        const h01 = this.heightMap[z1 * stride + x0] || 0;
        const h11 = this.heightMap[z1 * stride + x1] || 0;

        // Bilinear interpolation
        const h0 = h00 * (1 - tx) + h10 * tx;
        const h1 = h01 * (1 - tx) + h11 * tx;
        const normalizedHeight = h0 * (1 - tz) + h1 * tz;

        // Returns absolute world Y position
        return this.floorY + (normalizedHeight * this.maxHeight);
    }

    snapEntity(entity, heightOffset = 0) {
        if (!entity || !entity.position) return;
        const targetY = this.getHeight(entity.position.x, entity.position.z);
        entity.position.y = targetY + heightOffset;
    }
}