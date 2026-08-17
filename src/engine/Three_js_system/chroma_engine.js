(function (global) {
    const thisScript = document.currentScript;
    const baseUrl = thisScript ? thisScript.src.replace(/[^/]*$/, '') : '';

    const CORE_MODULES = [
        'https://cdnjs.cloudflare.com/ajax/libs/three.js/r128/three.min.js',
        'room_builder_v3.js',
        'sprite_system_v3.js',
        'combat_system_v3.js',
        'camera_controls_v3.js',
        'interact_system_v3.js',
        'heightmap_generator_v3.js',  // MUST be before terrain
        'normalmap_generator_v3.js',  // MUST be before terrain
        'terrain_generator_v3.js',    // now safe, deps exist
        'scoreboard_v3.js',
        'skybox_system_v3.js',
        'clock_system_v3.js',
        'night_spawn_v3.js',
        'hp_bar_system_v3.js',
        'inventory_system_v3.js',
        'menu_system.js'
    ];

    function resolvePath(p) {
        return /^https?:\/\//.test(p) ? p : baseUrl + p;
    }

    let loadedCount = 0;
    let ready = false;
    const readyCallbacks = [];

    function onOneLoaded() {
        loadedCount++;
        console.log(`[ChromaEngine] Loaded ${loadedCount}/${CORE_MODULES.length}: ${CORE_MODULES[loadedCount-1]}`);
        if (loadedCount === CORE_MODULES.length) {
            console.log('[ChromaEngine] ALL MODULES READY', CORE_MODULES);
            console.log('[ChromaEngine] Check globals:', {
                THREE: typeof THREE,
                RoomBuilderV3: typeof RoomBuilderV3,
                SpriteSystemV3: typeof SpriteSystemV3,
                HeightmapGeneratorV3: typeof HeightmapGeneratorV3,
                NormalmapGeneratorV3: typeof NormalmapGeneratorV3,
                TerrainGeneratorV3: typeof TerrainGeneratorV3
            });
            ready = true;
            const cbs = readyCallbacks.slice();
            readyCallbacks.length = 0;
            cbs.forEach(cb => cb());
            window.dispatchEvent(new Event('chroma-engine-ready'));
        }
    }

    CORE_MODULES.forEach(path => {
        const s = document.createElement('script');
        s.src = resolvePath(path);
        s.async = false;
        s.onload = onOneLoaded;
        s.onerror = () => {
            console.error('[ChromaEngine] FAILED to load: ' + s.src + ' - CHECK THIS FILE EXISTS');
            // Don't count as loaded if failed - will hang and show error instead of black screen
            statusEl = document.getElementById('status');
            if(statusEl) statusEl.textContent = 'FAILED: ' + path + ' not found';
        };
        document.head.appendChild(s);
    });

    global.ChromaEngine = {
        VERSION: '1.0.1-fixed-order',
        MODULES: CORE_MODULES,
        isReady: () => ready,
        onReady: function (cb) {
            if (ready) cb();
            else readyCallbacks.push(cb);
        }
    };
})(window);
