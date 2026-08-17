/**
 * OptionsMenuV3 -- OptiFine-style options / performance menu
 * ----------------------------------------------------------
 * Tabs: Video | Quality | Details | Controls
 * Esc / Enter toggles. Settings persist (localStorage).
 *
 *   const opts = OptionsMenuV3.create({
 *     camera, renderer, scene, camCtrl, player,
 *     nightSpawn, overworldProps, // optional perf hooks
 *     onPauseChange, onExit, exitLabel, defaultFov
 *   });
 *   if (!opts.isPaused()) { ... }
 *   opts.update(playerPos);
 *   opts.beginFrame(); // FPS limiter — returns false to skip frame
 */
(function (global) {
    const STORAGE_KEY = 'chroma_options_v2';

    const DEFAULTS = {
        fov: 55,
        pov: 'third',
        showOverlay: true,
        distance: 220,
        height: 110,
        // Video / performance
        renderScale: 1.0,       // 0.5–1.0 pixel ratio multiplier
        maxFps: 60,             // 30 / 60 / 0 unlimited
        fogDensity: 0.00022,
        fogEnabled: true,
        // Quality
        renderDistance: 1.0,    // 0.4–1.2 scales prop culling radius
        entityDistance: 1.0,
        shadows: false,
        antialias: true,
        // Details
        particles: true,
        skyCycle: true,
        billboardProps: true,
        nightSpawns: true,
        showFps: false
    };

    function loadSettings() {
        try {
            const raw = localStorage.getItem(STORAGE_KEY);
            if (raw) return Object.assign({}, DEFAULTS, JSON.parse(raw));
        } catch (e) {}
        // migrate old key once
        try {
            const old = localStorage.getItem('neondromeda_options_v1');
            if (old) return Object.assign({}, DEFAULTS, JSON.parse(old));
        } catch (e) {}
        return Object.assign({}, DEFAULTS);
    }

    function saveSettings(s) {
        try { localStorage.setItem(STORAGE_KEY, JSON.stringify(s)); } catch (e) {}
    }

    const CONTROLS_ROWS = [
        ['WASD', 'Move (Analog)'],
        ['Arrow Keys', 'Camera orbit / zoom'],
        ['B', 'Attack / sword (B)'],
        ['K / Enter', 'Action (A)'],
        ['Space', 'Jump'],
        ['Shift', 'Z-Target / climb'],
        ['Ctrl', 'Shield (R) reserved'],
        ['Q / E', 'Use item / cycle'],
        ['1 2 3', 'Quick equip'],
        ['Enter / Esc', 'Options menu']
    ];

    function injectStyles() {
        if (document.getElementById('options-menu-v3-css')) return;
        const style = document.createElement('style');
        style.id = 'options-menu-v3-css';
        style.textContent = [
            '#options-menu-v3{display:none;position:fixed;inset:0;z-index:200;',
            'background:rgba(0,0,0,0.88);font-family:monospace;color:#d0e8d0;',
            'align-items:center;justify-content:center;}',
            '#options-menu-v3.open{display:flex;}',
            '#options-menu-v3 .panel{background:#0c100c;border:2px solid #4a8a4a;',
            'box-shadow:0 0 20px rgba(0,0,0,0.8);padding:0;max-width:560px;',
            'width:94%;max-height:90vh;display:flex;flex-direction:column;}',
            '#options-menu-v3 .panel-head{padding:12px 16px;border-bottom:2px solid #2a4a2a;',
            'background:#0a1a0a;text-align:center;letter-spacing:0.15em;font-size:15px;',
            'color:#7dff7d;text-shadow:0 0 6px #3a8a3a;}',
            '#options-menu-v3 .tabs{display:flex;border-bottom:1px solid #2a4a2a;background:#081208;}',
            '#options-menu-v3 .tab{flex:1;padding:8px 4px;text-align:center;cursor:pointer;',
            'font-size:11px;color:#6a9a6a;border:none;background:transparent;font-family:monospace;}',
            '#options-menu-v3 .tab:hover{color:#aaffaa;background:#0e1e0e;}',
            '#options-menu-v3 .tab.active{color:#b8ffb8;background:#143014;border-bottom:2px solid #5dff5d;}',
            '#options-menu-v3 .tab-body{padding:12px 16px;overflow:auto;flex:1;}',
            '#options-menu-v3 .tab-pane{display:none;}',
            '#options-menu-v3 .tab-pane.active{display:block;}',
            '#options-menu-v3 h3{margin:10px 0 6px;font-size:11px;color:#6aaa6a;',
            'letter-spacing:0.08em;border-bottom:1px solid #1a3a1a;padding-bottom:3px;}',
            '#options-menu-v3 label.row{display:flex;align-items:center;justify-content:space-between;',
            'gap:10px;margin:6px 0;font-size:12px;}',
            '#options-menu-v3 label.row span.val{color:#c8ff88;min-width:36px;text-align:right;}',
            '#options-menu-v3 input[type=range]{width:140px;accent-color:#5dff5d;}',
            '#options-menu-v3 .btn-row{display:flex;flex-wrap:wrap;gap:8px;margin:8px 0;}',
            '#options-menu-v3 button.chip{background:#122012;border:1px solid #3a6a3a;color:#8fd88f;',
            'padding:6px 10px;cursor:pointer;font-family:monospace;font-size:11px;}',
            '#options-menu-v3 button.chip:hover,#options-menu-v3 button.chip.active{',
            'background:#2a5a2a;color:#d0ffd0;border-color:#6dff6d;}',
            '#options-menu-v3 .opt-row{display:flex;justify-content:space-between;gap:10px;',
            'padding:3px 0;border-bottom:1px solid #152015;font-size:11px;}',
            '#options-menu-v3 .opt-key{color:#c8e878;}',
            '#options-menu-v3 .opt-desc{color:#7aaa7a;text-align:right;}',
            '#options-menu-v3 .foot{padding:10px 16px;border-top:1px solid #2a4a2a;',
            'display:flex;flex-wrap:wrap;gap:8px;justify-content:center;background:#081208;}',
            '#options-menu-v3 button.main{background:#1a3a1a;border:2px solid #4a8a4a;color:#b0ffb0;',
            'padding:8px 16px;cursor:pointer;font-family:monospace;font-size:12px;}',
            '#options-menu-v3 button.main:hover{background:#4a8a4a;color:#061006;}',
            '#options-menu-v3 .hint{width:100%;text-align:center;font-size:10px;color:#4a6a4a;margin-top:4px;}',
            '#controls-overlay-v3{position:fixed;bottom:48px;right:16px;z-index:18;',
            'background:rgba(0,0,0,0.55);border:1px solid #3a6a3a44;padding:8px 10px;',
            'font-family:monospace;font-size:10px;color:#88ffaa;pointer-events:none;line-height:1.45;}',
            '#fps-overlay-v3{position:fixed;top:8px;right:12px;z-index:19;font-family:monospace;',
            'font-size:12px;color:#8f8;background:rgba(0,0,0,0.5);padding:4px 8px;display:none;}'
        ].join('');
        document.head.appendChild(style);
    }

    function create(options) {
        options = options || {};
        const settings = loadSettings();
        if (options.defaultFov != null && !localStorage.getItem(STORAGE_KEY) &&
            !localStorage.getItem('neondromeda_options_v1')) {
            settings.fov = options.defaultFov;
        }

        injectStyles();

        const controlsHtml = CONTROLS_ROWS.map(function (r) {
            return '<div class="opt-row"><span class="opt-key">' + r[0] +
                '</span><span class="opt-desc">' + r[1] + '</span></div>';
        }).join('');

        const root = document.createElement('div');
        root.id = 'options-menu-v3';
        root.innerHTML =
            '<div class="panel">' +
            '<div class="panel-head">VIDEO SETTINGS / OPTIONS</div>' +
            '<div class="tabs">' +
            '<button type="button" class="tab active" data-tab="video">Video</button>' +
            '<button type="button" class="tab" data-tab="quality">Quality</button>' +
            '<button type="button" class="tab" data-tab="details">Details</button>' +
            '<button type="button" class="tab" data-tab="controls">Controls</button>' +
            '</div>' +
            '<div class="tab-body">' +
            // VIDEO
            '<div class="tab-pane active" data-pane="video">' +
            '<h3>Performance</h3>' +
            '<label class="row">Render Scale <span class="val" id="v-rs">' + Math.round(settings.renderScale * 100) + '%</span>' +
            '<input id="opt-render-scale" type="range" min="50" max="100" step="5" value="' + Math.round(settings.renderScale * 100) + '"></label>' +
            '<label class="row">Max FPS <span class="val" id="v-fps">' + (settings.maxFps || '∞') + '</span>' +
            '<input id="opt-max-fps" type="range" min="0" max="144" step="1" value="' + settings.maxFps + '"></label>' +
            '<label class="row">FOV <span class="val" id="opt-fov-val">' + settings.fov + '</span>' +
            '<input id="opt-fov" type="range" min="35" max="90" step="1" value="' + settings.fov + '"></label>' +
            '<h3>Camera</h3>' +
            '<div class="btn-row">' +
            '<button type="button" class="chip" id="opt-pov-third">3rd Person</button>' +
            '<button type="button" class="chip" id="opt-pov-first">1st Person</button>' +
            '</div>' +
            '<h3>Fog</h3>' +
            '<div class="btn-row">' +
            '<button type="button" class="chip" id="opt-fog-on">Fog ON</button>' +
            '<button type="button" class="chip" id="opt-fog-off">Fog OFF</button>' +
            '</div>' +
            '<label class="row">Fog Density <span class="val" id="v-fog">' + settings.fogDensity.toFixed(5) + '</span>' +
            '<input id="opt-fog-density" type="range" min="5" max="80" step="1" value="' + Math.round(settings.fogDensity * 100000) + '"></label>' +
            '</div>' +
            // QUALITY
            '<div class="tab-pane" data-pane="quality">' +
            '<h3>Distance</h3>' +
            '<label class="row">Render Distance <span class="val" id="v-rd">' + Math.round(settings.renderDistance * 100) + '%</span>' +
            '<input id="opt-render-dist" type="range" min="40" max="120" step="5" value="' + Math.round(settings.renderDistance * 100) + '"></label>' +
            '<label class="row">Entity Distance <span class="val" id="v-ed">' + Math.round(settings.entityDistance * 100) + '%</span>' +
            '<input id="opt-entity-dist" type="range" min="40" max="120" step="5" value="' + Math.round(settings.entityDistance * 100) + '"></label>' +
            '<h3>Graphics</h3>' +
            '<div class="btn-row">' +
            '<button type="button" class="chip" id="opt-aa-on">Antialias ON</button>' +
            '<button type="button" class="chip" id="opt-aa-off">Antialias OFF</button>' +
            '</div>' +
            '<div class="btn-row">' +
            '<button type="button" class="chip" id="opt-sh-on">Shadows ON</button>' +
            '<button type="button" class="chip" id="opt-sh-off">Shadows OFF</button>' +
            '</div>' +
            '<p style="font-size:10px;color:#5a7a5a;margin-top:8px;">Shadows are experimental; OFF recommended on large field.</p>' +
            '</div>' +
            // DETAILS
            '<div class="tab-pane" data-pane="details">' +
            '<h3>World</h3>' +
            '<div class="btn-row">' +
            '<button type="button" class="chip" id="opt-sky-on">Sky Cycle ON</button>' +
            '<button type="button" class="chip" id="opt-sky-off">Sky Cycle OFF</button>' +
            '</div>' +
            '<div class="btn-row">' +
            '<button type="button" class="chip" id="opt-night-on">Night Spawns ON</button>' +
            '<button type="button" class="chip" id="opt-night-off">Night Spawns OFF</button>' +
            '</div>' +
            '<div class="btn-row">' +
            '<button type="button" class="chip" id="opt-bill-on">Billboards ON</button>' +
            '<button type="button" class="chip" id="opt-bill-off">Billboards OFF</button>' +
            '</div>' +
            '<div class="btn-row">' +
            '<button type="button" class="chip" id="opt-part-on">Particles ON</button>' +
            '<button type="button" class="chip" id="opt-part-off">Particles OFF</button>' +
            '</div>' +
            '<h3>HUD</h3>' +
            '<div class="btn-row">' +
            '<button type="button" class="chip" id="opt-overlay-toggle">Controls Overlay</button>' +
            '<button type="button" class="chip" id="opt-fps-toggle">FPS Counter</button>' +
            '</div>' +
            '</div>' +
            // CONTROLS
            '<div class="tab-pane" data-pane="controls">' +
            '<h3>OoT / Mupen64 Layout</h3>' +
            controlsHtml +
            '</div>' +
            '</div>' +
            '<div class="foot">' +
            '<button type="button" class="main" id="opt-resume">Done</button>' +
            '<button type="button" class="main" id="opt-defaults">Defaults</button>' +
            (options.onExit ? '<button type="button" class="main" id="opt-exit">' + (options.exitLabel || 'Exit') + '</button>' : '') +
            '<div class="hint">ENTER / ESC toggle · Settings save automatically</div>' +
            '</div>' +
            '</div>';
        document.body.appendChild(root);

        const overlay = document.createElement('div');
        overlay.id = 'controls-overlay-v3';
        overlay.innerHTML = 'WASD move · ARROWS cam<br>B attack · K action · SPACE jump<br>SHIFT Z · ENTER options';
        document.body.appendChild(overlay);

        const fpsEl = document.createElement('div');
        fpsEl.id = 'fps-overlay-v3';
        fpsEl.textContent = 'FPS --';
        document.body.appendChild(fpsEl);

        let paused = false;
        const camera = options.camera;
        const renderer = options.renderer;
        const scene = options.scene;
        const camCtrl = options.camCtrl;

        // FPS limiter state
        let lastFrameTime = 0;
        let fpsAccum = 0, fpsFrames = 0, fpsDisplay = 0;

        function setChip(id, active) {
            const el = root.querySelector('#' + id);
            if (el) el.classList.toggle('active', !!active);
        }

        function applyVideo() {
            if (camera) {
                camera.fov = settings.fov;
                camera.updateProjectionMatrix();
            }
            if (renderer) {
                const pr = Math.min(window.devicePixelRatio || 1, 2) * settings.renderScale;
                renderer.setPixelRatio(Math.max(0.5, pr));
            }
            if (scene && scene.fog) {
                if (settings.fogEnabled) {
                    scene.fog.density = settings.fogDensity;
                } else {
                    scene.fog.density = 0.000001;
                }
            }
            if (camCtrl && camCtrl.state) {
                if (settings.pov === 'first') {
                    camCtrl.state.distance = 2;
                    camCtrl.state.height = 8;
                } else {
                    camCtrl.state.distance = settings.distance || DEFAULTS.distance;
                    camCtrl.state.height = settings.height || DEFAULTS.height;
                }
            }
            const fovVal = root.querySelector('#opt-fov-val');
            if (fovVal) fovVal.textContent = settings.fov;
            const rs = root.querySelector('#v-rs');
            if (rs) rs.textContent = Math.round(settings.renderScale * 100) + '%';
            const fps = root.querySelector('#v-fps');
            if (fps) fps.textContent = settings.maxFps ? settings.maxFps : '∞';
            const fog = root.querySelector('#v-fog');
            if (fog) fog.textContent = settings.fogDensity.toFixed(5);
            setChip('opt-pov-third', settings.pov === 'third');
            setChip('opt-pov-first', settings.pov === 'first');
            setChip('opt-fog-on', settings.fogEnabled);
            setChip('opt-fog-off', !settings.fogEnabled);
        }

        function applyQuality() {
            const rd = root.querySelector('#v-rd');
            if (rd) rd.textContent = Math.round(settings.renderDistance * 100) + '%';
            const ed = root.querySelector('#v-ed');
            if (ed) ed.textContent = Math.round(settings.entityDistance * 100) + '%';
            setChip('opt-aa-on', settings.antialias);
            setChip('opt-aa-off', !settings.antialias);
            setChip('opt-sh-on', settings.shadows);
            setChip('opt-sh-off', !settings.shadows);
            if (renderer) {
                renderer.shadowMap.enabled = !!settings.shadows;
            }
        }

        function applyDetails() {
            setChip('opt-sky-on', settings.skyCycle);
            setChip('opt-sky-off', !settings.skyCycle);
            setChip('opt-night-on', settings.nightSpawns);
            setChip('opt-night-off', !settings.nightSpawns);
            setChip('opt-bill-on', settings.billboardProps);
            setChip('opt-bill-off', !settings.billboardProps);
            setChip('opt-part-on', settings.particles);
            setChip('opt-part-off', !settings.particles);
            setChip('opt-overlay-toggle', settings.showOverlay);
            setChip('opt-fps-toggle', settings.showFps);
            overlay.style.display = settings.showOverlay && !paused ? 'block' : 'none';
            fpsEl.style.display = settings.showFps ? 'block' : 'none';
            // Night spawn hook
            if (options.nightSpawn && typeof options.nightSpawn.setEnabled === 'function') {
                options.nightSpawn.setEnabled(settings.nightSpawns);
            }
        }

        function applyAll() {
            applyVideo();
            applyQuality();
            applyDetails();
            saveSettings(settings);
            if (options.onSettingsChange) options.onSettingsChange(settings);
        }

        function open() {
            paused = true;
            root.classList.add('open');
            applyDetails();
            if (options.onPauseChange) options.onPauseChange(true);
        }
        function close() {
            paused = false;
            root.classList.remove('open');
            applyDetails();
            if (options.onPauseChange) options.onPauseChange(false);
        }
        function toggle() {
            if (paused) close(); else open();
        }

        // Tabs
        root.querySelectorAll('.tab').forEach(function (tab) {
            tab.addEventListener('click', function () {
                root.querySelectorAll('.tab').forEach(function (x) { x.classList.remove('active'); });
                root.querySelectorAll('.tab-pane').forEach(function (x) { x.classList.remove('active'); });
                tab.classList.add('active');
                const pane = root.querySelector('[data-pane="' + tab.getAttribute('data-tab') + '"]');
                if (pane) pane.classList.add('active');
            });
        });

        // Sliders
        root.querySelector('#opt-fov').addEventListener('input', function (e) {
            settings.fov = parseInt(e.target.value, 10);
            applyVideo(); saveSettings(settings);
        });
        root.querySelector('#opt-render-scale').addEventListener('input', function (e) {
            settings.renderScale = parseInt(e.target.value, 10) / 100;
            applyVideo(); saveSettings(settings);
        });
        root.querySelector('#opt-max-fps').addEventListener('input', function (e) {
            settings.maxFps = parseInt(e.target.value, 10);
            applyVideo(); saveSettings(settings);
        });
        root.querySelector('#opt-fog-density').addEventListener('input', function (e) {
            settings.fogDensity = parseInt(e.target.value, 10) / 100000;
            applyVideo(); saveSettings(settings);
        });
        root.querySelector('#opt-render-dist').addEventListener('input', function (e) {
            settings.renderDistance = parseInt(e.target.value, 10) / 100;
            applyQuality(); saveSettings(settings);
            if (options.onSettingsChange) options.onSettingsChange(settings);
        });
        root.querySelector('#opt-entity-dist').addEventListener('input', function (e) {
            settings.entityDistance = parseInt(e.target.value, 10) / 100;
            applyQuality(); saveSettings(settings);
            if (options.onSettingsChange) options.onSettingsChange(settings);
        });

        function bindPair(onId, offId, key, onVal, offVal) {
            root.querySelector('#' + onId).addEventListener('click', function () {
                settings[key] = onVal; applyAll();
            });
            root.querySelector('#' + offId).addEventListener('click', function () {
                settings[key] = offVal; applyAll();
            });
        }
        bindPair('opt-pov-third', 'opt-pov-first', 'pov', 'third', 'first');
        // pov is string — handle manually
        root.querySelector('#opt-pov-third').addEventListener('click', function () {
            settings.pov = 'third'; applyAll();
        });
        root.querySelector('#opt-pov-first').addEventListener('click', function () {
            settings.pov = 'first'; applyAll();
        });
        root.querySelector('#opt-fog-on').addEventListener('click', function () {
            settings.fogEnabled = true; applyAll();
        });
        root.querySelector('#opt-fog-off').addEventListener('click', function () {
            settings.fogEnabled = false; applyAll();
        });
        root.querySelector('#opt-aa-on').addEventListener('click', function () {
            settings.antialias = true; applyAll();
        });
        root.querySelector('#opt-aa-off').addEventListener('click', function () {
            settings.antialias = false; applyAll();
        });
        root.querySelector('#opt-sh-on').addEventListener('click', function () {
            settings.shadows = true; applyAll();
        });
        root.querySelector('#opt-sh-off').addEventListener('click', function () {
            settings.shadows = false; applyAll();
        });
        root.querySelector('#opt-sky-on').addEventListener('click', function () {
            settings.skyCycle = true; applyAll();
        });
        root.querySelector('#opt-sky-off').addEventListener('click', function () {
            settings.skyCycle = false; applyAll();
        });
        root.querySelector('#opt-night-on').addEventListener('click', function () {
            settings.nightSpawns = true; applyAll();
        });
        root.querySelector('#opt-night-off').addEventListener('click', function () {
            settings.nightSpawns = false; applyAll();
        });
        root.querySelector('#opt-bill-on').addEventListener('click', function () {
            settings.billboardProps = true; applyAll();
        });
        root.querySelector('#opt-bill-off').addEventListener('click', function () {
            settings.billboardProps = false; applyAll();
        });
        root.querySelector('#opt-part-on').addEventListener('click', function () {
            settings.particles = true; applyAll();
        });
        root.querySelector('#opt-part-off').addEventListener('click', function () {
            settings.particles = false; applyAll();
        });
        root.querySelector('#opt-overlay-toggle').addEventListener('click', function () {
            settings.showOverlay = !settings.showOverlay; applyAll();
        });
        root.querySelector('#opt-fps-toggle').addEventListener('click', function () {
            settings.showFps = !settings.showFps; applyAll();
        });

        root.querySelector('#opt-resume').addEventListener('click', close);
        root.querySelector('#opt-defaults').addEventListener('click', function () {
            Object.keys(DEFAULTS).forEach(function (k) { settings[k] = DEFAULTS[k]; });
            // sync sliders
            root.querySelector('#opt-fov').value = settings.fov;
            root.querySelector('#opt-render-scale').value = Math.round(settings.renderScale * 100);
            root.querySelector('#opt-max-fps').value = settings.maxFps;
            root.querySelector('#opt-fog-density').value = Math.round(settings.fogDensity * 100000);
            root.querySelector('#opt-render-dist').value = Math.round(settings.renderDistance * 100);
            root.querySelector('#opt-entity-dist').value = Math.round(settings.entityDistance * 100);
            applyAll();
        });
        const exitBtn = root.querySelector('#opt-exit');
        if (exitBtn && options.onExit) {
            exitBtn.addEventListener('click', function () {
                close();
                options.onExit();
            });
        }

        window.addEventListener('keydown', function (e) {
            if (e.target && (e.target.tagName === 'INPUT' || e.target.tagName === 'TEXTAREA')) return;
            if (e.code === 'Escape' || e.code === 'Enter') {
                if (e.code === 'Escape' && options.escExits && !paused) {
                    if (options.onExit) options.onExit();
                    return;
                }
                e.preventDefault();
                toggle();
            }
        });

        applyAll();

        return {
            isPaused: function () { return paused; },
            open: open,
            close: close,
            toggle: toggle,
            getSettings: function () { return Object.assign({}, settings); },

            /** Call at start of animate; returns false if frame should be skipped (FPS cap) */
            beginFrame: function (now) {
                now = now || performance.now();
                if (settings.maxFps && settings.maxFps > 0) {
                    const minDelta = 1000 / settings.maxFps;
                    if (now - lastFrameTime < minDelta) return false;
                }
                const dt = lastFrameTime ? (now - lastFrameTime) : 16;
                lastFrameTime = now;
                fpsFrames++;
                fpsAccum += dt;
                if (fpsAccum >= 500) {
                    fpsDisplay = Math.round((fpsFrames * 1000) / fpsAccum);
                    fpsFrames = 0;
                    fpsAccum = 0;
                    if (settings.showFps) fpsEl.textContent = 'FPS ' + fpsDisplay;
                }
                return true;
            },

            /**
             * After camCtrl.update — first person + prop distance culling
             */
            update: function (targetPos) {
                if (!camera || !targetPos) return;
                if (settings.pov === 'first') {
                    const eyeY = (targetPos.y || 0) + 26;
                    let yaw = 0;
                    if (camCtrl && camCtrl.state) yaw = camCtrl.state.yaw || 0;
                    const dist = 4;
                    camera.position.set(
                        targetPos.x - Math.sin(yaw) * dist,
                        eyeY,
                        targetPos.z - Math.cos(yaw) * dist
                    );
                    camera.lookAt(
                        targetPos.x - Math.sin(yaw) * 80,
                        eyeY,
                        targetPos.z - Math.cos(yaw) * 80
                    );
                }
                // Prop / entity distance culling
                const baseProp = 3200 * settings.renderDistance;
                const baseEnt = 2800 * settings.entityDistance;
                if (options.overworldProps && settings.billboardProps !== false) {
                    const px = targetPos.x, pz = targetPos.z;
                    options.overworldProps.forEach(function (m) {
                        if (!m || !m.position) return;
                        if (m.userData && m.userData.isRoad) return;
                        const dx = m.position.x - px, dz = m.position.z - pz;
                        m.visible = (dx * dx + dz * dz) < (baseProp * baseProp);
                    });
                }
                if (options.entities) {
                    const px = targetPos.x, pz = targetPos.z;
                    options.entities.forEach(function (e) {
                        const mesh = e.mesh || e;
                        if (!mesh || !mesh.position) return;
                        const dx = mesh.position.x - px, dz = mesh.position.z - pz;
                        mesh.visible = (dx * dx + dz * dz) < (baseEnt * baseEnt);
                    });
                }
            },

            shouldUpdateSky: function () { return settings.skyCycle; },
            shouldNightSpawn: function () { return settings.nightSpawns; },
            shouldBillboard: function () { return settings.billboardProps; }
        };
    }

    global.OptionsMenuV3 = { create: create, loadSettings: loadSettings };
})(typeof window !== 'undefined' ? window : this);
