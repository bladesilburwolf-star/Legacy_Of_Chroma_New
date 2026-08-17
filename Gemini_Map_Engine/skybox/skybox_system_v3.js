/**
 * SkyboxSystemV3 -- sphere skybox + day/night cycle
 * -------------------------------------------------
 * Uses inverted-sphere equirectangular (or full-frame) textures,
 * same approach as the old monochrome skybox, with a proper
 * time-of-day crossfade between day and night sets.
 *
 * Samples expected under assets/textures/skybox/:
 *   day1.jpg … day5.jpg, night1.jpg … night4.jpg
 *
 * Usage:
 *   const sky = SkyboxSystemV3.create(scene, {
 *     radius: 6000,
 *     dayTextures: [...],
 *     nightTextures: [...],
 *     cycleMinutes: 8,          // full day→night→day real-time minutes
 *     startHour: 10             // 0..24
 *   });
 *
 *   // every frame:
 *   sky.update(dt);
 *   // optional: drive your sun / ambient from sky.getLightFactor()
 */
(function (global) {
    const DEFAULT_DAY = [
        'assets/textures/skybox/day1.jpg',
        'assets/textures/skybox/day2.jpg',
        'assets/textures/skybox/day3.jpg',
        'assets/textures/skybox/day4.jpg',
        'assets/textures/skybox/day5.jpg'
    ];
    const DEFAULT_NIGHT = [
        'assets/textures/skybox/night1.jpg',
        'assets/textures/skybox/night2.jpg',
        'assets/textures/skybox/night3.jpg',
        'assets/textures/skybox/night4.jpg'
    ];

    function prep(tex) {
        tex.magFilter = THREE.LinearFilter;
        tex.minFilter = THREE.LinearFilter;
        tex.generateMipmaps = true;
        return tex;
    }

    function loadTex(loader, url) {
        return new Promise((resolve) => {
            loader.load(
                url,
                (t) => resolve(prep(t)),
                undefined,
                () => {
                    console.warn('[SkyboxSystemV3] missing', url);
                    resolve(null);
                }
            );
        });
    }

    /**
     * options:
     *   radius, dayTextures[], nightTextures[],
     *   cycleMinutes (real minutes for full 24h),
     *   startHour (0-24),
     *   autoCycle (default true),
     *   onHour (optional callback when hour changes)
     */
    function create(scene, options) {
        options = options || {};
        const radius = options.radius || 6000;
        const dayUrls = options.dayTextures || DEFAULT_DAY;
        const nightUrls = options.nightTextures || DEFAULT_NIGHT;
        const cycleMinutes = options.cycleMinutes != null ? options.cycleMinutes : 10;
        const autoCycle = options.autoCycle !== false;
        const loader = options.loader || new THREE.TextureLoader();

        // Two layered spheres for crossfade
        const geo = new THREE.SphereGeometry(radius, 48, 32);
        geo.scale(-1, 1, 1);

        const matA = new THREE.MeshBasicMaterial({
            side: THREE.DoubleSide,
            transparent: true,
            opacity: 1,
            depthWrite: false
        });
        const matB = new THREE.MeshBasicMaterial({
            side: THREE.DoubleSide,
            transparent: true,
            opacity: 0,
            depthWrite: false
        });

        const meshA = new THREE.Mesh(geo, matA);
        const meshB = new THREE.Mesh(geo.clone(), matB);
        meshA.name = 'skybox-a';
        meshB.name = 'skybox-b';
        meshA.renderOrder = -100;
        meshB.renderOrder = -99;
        scene.add(meshA);
        scene.add(meshB);

        // Fog color lerp targets
        const dayFog = new THREE.Color(options.dayFog != null ? options.dayFog : 0x87a0c0);
        const nightFog = new THREE.Color(options.nightFog != null ? options.nightFog : 0x050510);
        const dayAmb = options.dayAmbient != null ? options.dayAmbient : 0.55;
        const nightAmb = options.nightAmbient != null ? options.nightAmbient : 0.12;
        const daySun = options.daySun != null ? options.daySun : 0.95;
        const nightSun = options.nightSun != null ? options.nightSun : 0.08;

        let dayTexs = [];
        let nightTexs = [];
        let ready = false;
        let hour = options.startHour != null ? options.startHour : 12; // 0..24
        let lastReportedHour = Math.floor(hour);

        // Seconds of real time for a full 24h cycle
        const cycleSeconds = Math.max(30, cycleMinutes * 60);

        function isDay(h) {
            // Day roughly 6:00 – 18:00
            return h >= 6 && h < 18;
        }

        function dayAmount(h) {
            // Smooth transition around dawn/dusk
            // 5→7 dawn, 17→19 dusk
            if (h >= 7 && h < 17) return 1;
            if (h >= 19 || h < 5) return 0;
            if (h >= 5 && h < 7) return (h - 5) / 2;      // dawn
            return 1 - (h - 17) / 2;                      // dusk
        }

        function pickVariant(list, h) {
            if (!list.length) return null;
            // Spread variants across the day/night half
            const t = isDay(h) ? (h - 6) / 12 : ((h < 6 ? h + 24 : h) - 18) / 12;
            const i = Math.min(list.length - 1, Math.max(0, Math.floor(t * list.length)));
            return list[i];
        }

        function applyTextures(h) {
            const dayT = pickVariant(dayTexs, h);
            const nightT = pickVariant(nightTexs, h);
            const d = dayAmount(h);
            if (dayT) matA.map = dayT;
            if (nightT) matB.map = nightT;
            matA.opacity = d;
            matB.opacity = 1 - d;
            matA.needsUpdate = true;
            matB.needsUpdate = true;
        }

        const loadPromise = Promise.all([
            Promise.all(dayUrls.map((u) => loadTex(loader, u))),
            Promise.all(nightUrls.map((u) => loadTex(loader, u)))
        ]).then(([d, n]) => {
            dayTexs = d.filter(Boolean);
            nightTexs = n.filter(Boolean);
            if (!dayTexs.length && !nightTexs.length) {
                console.warn('[SkyboxSystemV3] no skybox textures loaded');
            }
            ready = true;
            applyTextures(hour);
            return api;
        });

        const api = {
            meshA: meshA,
            meshB: meshB,
            ready: function () { return ready; },
            whenReady: function () { return loadPromise; },

            /** 0..24 */
            getHour: function () { return hour; },
            setHour: function (h) {
                hour = ((h % 24) + 24) % 24;
                if (ready) applyTextures(hour);
            },

            /** 1 = full day light, 0 = full night */
            getDayAmount: function () { return dayAmount(hour); },

            /** Convenience for ambient intensity */
            getAmbientFactor: function () {
                const d = dayAmount(hour);
                return nightAmb + (dayAmb - nightAmb) * d;
            },

            /** Convenience for sun/directional intensity */
            getSunFactor: function () {
                const d = dayAmount(hour);
                return nightSun + (daySun - nightSun) * d;
            },

            getFogColor: function (out) {
                const d = dayAmount(hour);
                out = out || new THREE.Color();
                out.copy(nightFog).lerp(dayFog, d);
                return out;
            },

            /**
             * Call every frame with dt in seconds.
             * Updates cycle, sky fade, optional lights + fog.
             */
            update: function (dt, opts) {
                opts = opts || {};
                if (autoCycle && ready) {
                    hour += (dt / cycleSeconds) * 24;
                    if (hour >= 24) hour -= 24;
                }
                if (ready) applyTextures(hour);

                const hFloor = Math.floor(hour);
                if (hFloor !== lastReportedHour) {
                    lastReportedHour = hFloor;
                    if (options.onHour) options.onHour(hFloor, hour);
                }

                // Optional scene hooks
                if (opts.ambient) {
                    opts.ambient.intensity = api.getAmbientFactor();
                }
                if (opts.sun) {
                    opts.sun.intensity = api.getSunFactor();
                    // Simple sun orbit: noon = high +Z-ish
                    const ang = ((hour - 6) / 24) * Math.PI * 2;
                    const r = opts.sunRadius || 800;
                    opts.sun.position.set(
                        Math.cos(ang) * r,
                        Math.sin(ang) * r * 0.6 + r * 0.3,
                        Math.sin(ang) * r * 0.2
                    );
                }
                if (opts.fog && opts.fog.color) {
                    api.getFogColor(opts.fog.color);
                }
                if (opts.background && opts.background.isColor) {
                    api.getFogColor(opts.background);
                }

                // Keep sky centered on camera if provided
                if (opts.camera) {
                    meshA.position.copy(opts.camera.position);
                    meshB.position.copy(opts.camera.position);
                }
            },

            dispose: function () {
                scene.remove(meshA);
                scene.remove(meshB);
                geo.dispose();
                matA.dispose();
                matB.dispose();
            }
        };

        return api;
    }

    global.SkyboxSystemV3 = {
        create: create,
        DEFAULT_DAY: DEFAULT_DAY,
        DEFAULT_NIGHT: DEFAULT_NIGHT
    };
})(typeof window !== 'undefined' ? window : this);
