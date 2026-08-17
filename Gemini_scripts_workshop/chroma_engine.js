/**
 * ChromaEngine -- single bootstrap for the whole core JS stack.
 * -------------------------------------------------------------------
 * Every room used to need its own hand-typed run of script tags in
 * the exact right order (three.js CDN, room_builder_v3.js,
 * sprite_system_v3.js, combat_system_v3.js, camera_controls_v3.js,
 * interact_system_v3.js) -- easy to get wrong, and that exact class of
 * mistake (wrong order, wrong path, missing file) is what caused the
 * black-screen debugging session on the D1 reference build. This file
 * replaces all of that with one include.
 *
 * Usage -- a room's <head> becomes just:
 *
 *   <script src="src/engine/Three_js_system/chroma_engine.js"></script>
 *   <script src="src/engine/Three_js_system/rooms/d1_room.js"></script>
 *   <!-- d1_room.js only DEFINES D1Room.build(), doesn't call THREE/
 *        RoomBuilderV3 at parse time, so it's safe to load in parallel
 *        with the engine -- see that file's own header. -->
 *
 * Then your page's own game-setup code waits for the engine instead of
 * assuming plain <script> tag order got everything loaded in time:
 *
 *   ChromaEngine.onReady(function () {
 *     const scene = new THREE.Scene();
 *     const d1 = D1Room.build(scene, loader);
 *     // ...rest of the room's game code...
 *   });
 *
 * Module list lives in CORE_MODULES below -- add new core engine
 * pieces there (NOT per-room files like d1_room.js/d2_room.js, those
 * stay as their own explicit <script> tag per room so a room only
 * pulls in the dungeon it actually needs).
 *
 * How loading works: each module is injected as its own <script>
 * element with async=false. Browsers download those in parallel but
 * guarantee they still EXECUTE in the order they were appended, so
 * this is both correctly-ordered and not artificially slow (no
 * waiting for module 1 to fully load before even starting module 2's
 * download).
 */
(function (global) {
    const thisScript = document.currentScript;
    // Everything else in CORE_MODULES is resolved relative to wherever
    // THIS file was loaded from, so it works correctly no matter how
    // deep in the folder tree the including page lives.
    const baseUrl = thisScript ? thisScript.src.replace(/[^/]*$/, '') : '';

    const CORE_MODULES = [
        'https://cdnjs.cloudflare.com/ajax/libs/three.js/r128/three.min.js',
        'room_builder_v3.js',
        'sprite_system_v3.js',
        'combat_system_v3.js',
        'camera_controls_v3.js',
        'interact_system_v3.js',
        'terrain_generator_v3.js',
        'heightmap_generator_v3.js',
        'normalmap_generator_v3.js',
        'scoreboard_v3.js'
    ];

    function resolvePath(p) {
        return /^https?:\/\//.test(p) ? p : baseUrl + p;
    }

    let loadedCount = 0;
    let ready = false;
    const readyCallbacks = [];

    function onOneLoaded() {
        loadedCount++;
        if (loadedCount === CORE_MODULES.length) {
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
        s.async = false; // parallel download, guaranteed execution order
        s.onload = onOneLoaded;
        s.onerror = () => {
            console.error('[ChromaEngine] failed to load: ' + s.src);
            onOneLoaded(); // still counts, so one bad path doesn't hang onReady() forever
        };
        document.head.appendChild(s);
    });

    global.ChromaEngine = {
        VERSION: '1.0.0',
        MODULES: CORE_MODULES,
        isReady: () => ready,
        /**
         * Call with your game-setup code. Runs immediately if the
         * engine's already loaded, otherwise queues until it is.
         */
        onReady: function (cb) {
            if (ready) cb();
            else readyCallbacks.push(cb);
        }
    };
})(window);
