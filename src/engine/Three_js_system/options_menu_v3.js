/**
 * OptionsMenuV3 -- pause / options / controls overlay
 * ----------------------------------------------------
 * Esc or Enter (Start) toggles. FOV slider, 1st/3rd person,
 * controls reference (OoT / Mupen-style). Settings persist.
 *
 *   const opts = OptionsMenuV3.create({
 *     camera, camCtrl, player,
 *     onPauseChange: (paused) => {},
 *     exitLabel: 'Return to Hub',
 *     onExit: () => { location.href = 'index.html'; }
 *   });
 *   // each frame:
 *   if (!opts.isPaused()) { ... gameplay ... }
 *   opts.update(player.mesh.position); // applies 1st-person after camCtrl
 */
(function (global) {
    const STORAGE_KEY = 'neondromeda_options_v1';

    const DEFAULTS = {
        fov: 55,
        pov: 'third', // 'third' | 'first'
        showOverlay: true,
        distance: 220,
        height: 110
    };

    function loadSettings() {
        try {
            const raw = localStorage.getItem(STORAGE_KEY);
            if (raw) return Object.assign({}, DEFAULTS, JSON.parse(raw));
        } catch (e) {}
        return Object.assign({}, DEFAULTS);
    }

    function saveSettings(s) {
        try { localStorage.setItem(STORAGE_KEY, JSON.stringify(s)); } catch (e) {}
    }

    const CONTROLS_HTML = [
        ['WASD', 'Move (Analog)'],
        ['Arrow Keys', 'Camera orbit / zoom (C-buttons)'],
        ['B', 'Attack / sword (B button)'],
        ['K / Enter', 'Action / talk / open (A button)'],
        ['Space', 'Jump'],
        ['Shift', 'Z-Target / climb hold'],
        ['Ctrl', 'Shield (R) — reserved'],
        ['Q', 'Use equipped item'],
        ['E', 'Cycle equipment'],
        ['1 / 2 / 3', 'Quick equip sword / lantern / ocarina'],
        ['Enter / Esc', 'Options / Start'],
        ['Esc (in dungeon)', 'Save & return to hub']
    ].map(function (row) {
        return '<div class="opt-row"><span class="opt-key">' + row[0] +
            '</span><span class="opt-desc">' + row[1] + '</span></div>';
    }).join('');

    function injectStyles() {
        if (document.getElementById('options-menu-v3-css')) return;
        const style = document.createElement('style');
        style.id = 'options-menu-v3-css';
        style.textContent = [
            '#options-menu-v3{display:none;position:fixed;inset:0;z-index:200;',
            'background:rgba(0,0,0,0.82);font-family:monospace;color:#00ff66;',
            'align-items:center;justify-content:center;}',
            '#options-menu-v3.open{display:flex;}',
            '#options-menu-v3 .panel{background:#0a120a;border:3px solid #00ff66;',
            'box-shadow:0 0 24px rgba(0,255,102,0.35);padding:22px 28px;max-width:520px;',
            'width:92%;max-height:88vh;overflow:auto;}',
            '#options-menu-v3 h2{margin:0 0 12px;letter-spacing:0.12em;font-size:18px;',
            'text-shadow:0 0 8px #00ff66;text-align:center;}',
            '#options-menu-v3 h3{margin:16px 0 8px;font-size:13px;color:#88ffaa;}',
            '#options-menu-v3 .opt-row{display:flex;justify-content:space-between;',
            'gap:12px;padding:4px 0;border-bottom:1px solid #00332244;font-size:12px;}',
            '#options-menu-v3 .opt-key{color:#ffff66;min-width:120px;}',
            '#options-menu-v3 .opt-desc{color:#aaffee;text-align:right;}',
            '#options-menu-v3 label{display:flex;align-items:center;justify-content:space-between;',
            'gap:12px;margin:8px 0;font-size:13px;}',
            '#options-menu-v3 input[type=range]{width:160px;}',
            '#options-menu-v3 .btn-row{display:flex;flex-wrap:wrap;gap:10px;margin-top:16px;',
            'justify-content:center;}',
            '#options-menu-v3 button{background:#003322;border:2px solid #00ff66;color:#00ff66;',
            'padding:8px 14px;cursor:pointer;font-family:monospace;font-size:12px;}',
            '#options-menu-v3 button:hover,#options-menu-v3 button.active{background:#00ff66;color:#001a0a;}',
            '#options-menu-v3 .hint{text-align:center;font-size:11px;color:#668866;margin-top:12px;}',
            '#controls-overlay-v3{position:fixed;bottom:48px;right:16px;z-index:18;',
            'background:rgba(0,0,0,0.55);border:1px solid #00ff6644;padding:8px 10px;',
            'font-family:monospace;font-size:10px;color:#88ffaa;pointer-events:none;',
            'line-height:1.45;max-width:200px;}'
        ].join('');
        document.head.appendChild(style);
    }

    function create(options) {
        options = options || {};
        const settings = loadSettings();
        if (options.defaultFov != null && !localStorage.getItem(STORAGE_KEY)) {
            settings.fov = options.defaultFov;
        }

        injectStyles();

        const root = document.createElement('div');
        root.id = 'options-menu-v3';
        root.innerHTML =
            '<div class="panel">' +
            '<h2>OPTIONS</h2>' +
            '<h3>Camera</h3>' +
            '<label>FOV <span id="opt-fov-val">' + settings.fov + '</span>' +
            '<input id="opt-fov" type="range" min="35" max="90" step="1" value="' + settings.fov + '"></label>' +
            '<div class="btn-row">' +
            '<button type="button" id="opt-pov-third">3rd Person</button>' +
            '<button type="button" id="opt-pov-first">1st Person</button>' +
            '</div>' +
            '<h3>Display</h3>' +
            '<div class="btn-row">' +
            '<button type="button" id="opt-overlay-toggle">Controls Overlay: ON</button>' +
            '</div>' +
            '<h3>Controls (OoT / Mupen64)</h3>' +
            '<div id="opt-controls-list">' + CONTROLS_HTML + '</div>' +
            '<div class="btn-row">' +
            '<button type="button" id="opt-resume">RESUME</button>' +
            (options.onExit ? '<button type="button" id="opt-exit">' + (options.exitLabel || 'EXIT') + '</button>' : '') +
            '</div>' +
            '<div class="hint">Press ENTER or ESC to toggle · Settings auto-save</div>' +
            '</div>';
        document.body.appendChild(root);

        const overlay = document.createElement('div');
        overlay.id = 'controls-overlay-v3';
        overlay.innerHTML =
            'WASD move<br>ARROWS cam<br>B attack · K action<br>SPACE jump · SHIFT Z<br>ENTER options';
        document.body.appendChild(overlay);

        let paused = false;
        const camera = options.camera;
        const camCtrl = options.camCtrl;
        const player = options.player;

        function applyFov() {
            if (!camera) return;
            camera.fov = settings.fov;
            camera.updateProjectionMatrix();
        }

        function applyPovButtons() {
            const t = root.querySelector('#opt-pov-third');
            const f = root.querySelector('#opt-pov-first');
            if (t) t.classList.toggle('active', settings.pov === 'third');
            if (f) f.classList.toggle('active', settings.pov === 'first');
        }

        function applyOverlay() {
            overlay.style.display = settings.showOverlay && !paused ? 'block' : 'none';
            const btn = root.querySelector('#opt-overlay-toggle');
            if (btn) btn.textContent = 'Controls Overlay: ' + (settings.showOverlay ? 'ON' : 'OFF');
        }

        function applyAll() {
            applyFov();
            applyPovButtons();
            applyOverlay();
            if (camCtrl && camCtrl.state) {
                if (settings.pov === 'first') {
                    camCtrl.state.distance = 2;
                    camCtrl.state.height = 8;
                } else {
                    camCtrl.state.distance = settings.distance || DEFAULTS.distance;
                    camCtrl.state.height = settings.height || DEFAULTS.height;
                }
            }
            saveSettings(settings);
        }

        function open() {
            paused = true;
            root.classList.add('open');
            applyOverlay();
            if (options.onPauseChange) options.onPauseChange(true);
        }
        function close() {
            paused = false;
            root.classList.remove('open');
            applyOverlay();
            if (options.onPauseChange) options.onPauseChange(false);
        }
        function toggle() {
            if (paused) close(); else open();
        }

        root.querySelector('#opt-fov').addEventListener('input', function (e) {
            settings.fov = parseInt(e.target.value, 10);
            root.querySelector('#opt-fov-val').textContent = settings.fov;
            applyFov();
            saveSettings(settings);
        });
        root.querySelector('#opt-pov-third').addEventListener('click', function () {
            settings.pov = 'third';
            applyAll();
        });
        root.querySelector('#opt-pov-first').addEventListener('click', function () {
            settings.pov = 'first';
            applyAll();
        });
        root.querySelector('#opt-overlay-toggle').addEventListener('click', function () {
            settings.showOverlay = !settings.showOverlay;
            applyAll();
        });
        root.querySelector('#opt-resume').addEventListener('click', close);
        const exitBtn = root.querySelector('#opt-exit');
        if (exitBtn && options.onExit) {
            exitBtn.addEventListener('click', function () {
                close();
                options.onExit();
            });
        }

        // Start / Esc — do not steal when typing in inputs
        window.addEventListener('keydown', function (e) {
            if (e.target && (e.target.tagName === 'INPUT' || e.target.tagName === 'TEXTAREA')) return;
            if (e.code === 'Escape' || e.code === 'Enter') {
                // In dungeons Esc may mean exit — allow options.holdEscExit
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
            /**
             * Call after camCtrl.update(pos) each frame.
             * First-person snaps camera to eye height on player.
             */
            update: function (targetPos) {
                if (!camera || !targetPos) return;
                if (settings.pov === 'first') {
                    const eyeY = (targetPos.y || 0) + 26;
                    // Use cam yaw from camCtrl if present
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
            }
        };
    }

    global.OptionsMenuV3 = { create: create, loadSettings: loadSettings };
})(typeof window !== 'undefined' ? window : this);
