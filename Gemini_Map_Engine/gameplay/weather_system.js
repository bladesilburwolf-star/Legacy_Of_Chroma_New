/**
 * WeatherSystemV3 — Particle-based weather manager (Rain, Snow, Clear)
 * ---------------------------------------------------------------------
 * Features:
 *   - Recycles particles dynamically around target (player/camera).
 *   - Smooth state switching ('clear', 'rain', 'snow').
 *   - Modulates scene ambient lighting and fog dynamically.
 *
 * Usage:
 *   const weather = WeatherSystemV3.create(scene, { count: 2500 });
 *   weather.setMode('rain');
 *   // In animate loop:
 *   weather.update(deltaTime, targetPosition, sceneFog);
 */
(function (global) {
    function create(scene, opts) {
        opts = opts || {};
        const count = opts.count || 2000;
        const areaSize = opts.areaSize || 1500;
        const heightRange = opts.heightRange || 600;

        const geometry = new THREE.BufferGeometry();
        const positions = new Float32Array(count * 3);
        const velocities = new Float32Array(count * 3);

        for (let i = 0; i < count; i++) {
            positions[i * 3]     = (Math.random() - 0.5) * areaSize;
            positions[i * 3 + 1] = Math.random() * heightRange;
            positions[i * 3 + 2] = (Math.random() - 0.5) * areaSize;

            velocities[i * 3]     = 0;
            velocities[i * 3 + 1] = -100; // Default fall speed
            velocities[i * 3 + 2] = 0;
        }

        geometry.setAttribute('position', new THREE.BufferAttribute(positions, 3));

        const material = new THREE.PointsMaterial({
            color: 0xaaaaee,
            size: 2.5,
            transparent: true,
            opacity: 0,
            depthWrite: false
        });

        const points = new THREE.Points(geometry, material);
        if (scene) scene.add(points);

        let currentMode = 'clear'; // 'clear' | 'rain' | 'snow'
        let targetOpacity = 0;

        return {
            points: points,
            setMode: function (mode) {
                currentMode = mode;
                if (mode === 'clear') {
                    targetOpacity = 0;
                } else if (mode === 'rain') {
                    material.color.setHex(0x88aaee);
                    material.size = 2.0;
                    targetOpacity = 0.65;
                } else if (mode === 'snow') {
                    material.color.setHex(0xffffff);
                    material.size = 4.0;
                    targetOpacity = 0.85;
                }
            },

            update: function (dt, targetPos, fog) {
                dt = dt || 0.016;

                // Smoothly fade particle visibility
                if (material.opacity !== targetOpacity) {
                    material.opacity += (targetOpacity - material.opacity) * dt * 2.0;
                }

                if (material.opacity <= 0.01) return;

                const posAttr = geometry.attributes.position;
                const arr = posAttr.array;
                const center = targetPos || { x: 0, y: 0, z: 0 };

                const isSnow = currentMode === 'snow';
                const fallSpeed = isSnow ? 60 : 380;
                const driftX = isSnow ? Math.sin(Date.now() * 0.001) * 15 : -10;

                for (let i = 0; i < count; i++) {
                    let idx = i * 3;

                    arr[idx]     += driftX * dt;
                    arr[idx + 1] -= fallSpeed * dt;

                    // Recenter particle box around the active camera/player position
                    if (arr[idx + 1] < (center.y - 50)) {
                        arr[idx + 1] = center.y + heightRange;
                        arr[idx]     = center.x + (Math.random() - 0.5) * areaSize;
                        arr[idx + 2] = center.z + (Math.random() - 0.5) * areaSize;
                    }
                }

                posAttr.needsUpdate = true;

                // Optional atmospheric adjusting
                if (fog && currentMode !== 'clear') {
                    const targetFogColor = isSnow ? 0xccccdd : 0x445566;
                    fog.color.lerp(new THREE.Color(targetFogColor), dt * 0.5);
                }
            },

            getMode: function () { return currentMode; }
        };
    }

    global.WeatherSystemV3 = { create: create };
})(typeof window !== 'undefined' ? window : this);