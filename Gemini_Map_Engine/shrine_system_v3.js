/**
 * ShrineSystemV3 — Zelda 2–style random cave/shrine entrances
 * ------------------------------------------------------------
 * Places rock doors across the overworld. On interact, opens a
 * seed picker UI. Once locked, the shrine always loads that seed
 * into shrine_dungeon.html (procedural side-quest caves).
 *
 *   const shrines = ShrineSystemV3.create(scene, {
 *     S: RoomBuilderV3.shape,
 *     terrain, count: 12, zoneW, zoneL
 *   });
 *   // F/K near door:
 *   shrines.tryInteract(playerPos);
 *   // animate:
 *   shrines.update(camera);
 */
(function (global) {
    const STORAGE_KEY = 'chroma_shrine_seeds_v1';

    function loadLocks() {
        try {
            const raw = localStorage.getItem(STORAGE_KEY);
            if (raw) return JSON.parse(raw);
        } catch (e) {}
        return {};
    }
    function saveLocks(map) {
        try { localStorage.setItem(STORAGE_KEY, JSON.stringify(map)); } catch (e) {}
    }

    function injectUI() {
        if (document.getElementById('shrine-seed-ui')) return;
        const style = document.createElement('style');
        style.id = 'shrine-seed-ui-css';
        style.textContent = [
            '#shrine-seed-ui{display:none;position:fixed;inset:0;z-index:210;',
            'background:rgba(0,0,0,0.85);align-items:center;justify-content:center;',
            'font-family:monospace;color:#c8e878;}',
            '#shrine-seed-ui.open{display:flex;}',
            '#shrine-seed-ui .box{background:#0c140c;border:3px solid #6a9a4a;',
            'padding:20px 24px;max-width:420px;width:92%;text-align:center;}',
            '#shrine-seed-ui h2{margin:0 0 8px;color:#a8ff88;letter-spacing:0.1em;}',
            '#shrine-seed-ui p{font-size:12px;color:#7a9a7a;margin:8px 0;}',
            '#shrine-seed-ui input{width:70%;padding:8px;font-family:monospace;',
            'font-size:16px;background:#061006;border:2px solid #4a7a4a;color:#d0ffb0;',
            'text-align:center;margin:10px 0;}',
            '#shrine-seed-ui button{margin:6px;padding:8px 14px;font-family:monospace;',
            'background:#1a3a1a;border:2px solid #5a8a5a;color:#c8ffc8;cursor:pointer;}',
            '#shrine-seed-ui button:hover{background:#3a6a3a;}'
        ].join('');
        document.head.appendChild(style);

        const ui = document.createElement('div');
        ui.id = 'shrine-seed-ui';
        ui.innerHTML =
            '<div class="box">' +
            '<h2>CAVE SHRINE</h2>' +
            '<p id="shrine-ui-sub">Choose a seed. Once entered, this shrine is locked forever.</p>' +
            '<div><input id="shrine-seed-input" type="text" maxlength="12" placeholder="SEED" /></div>' +
            '<div>' +
            '<button type="button" id="shrine-seed-rand">RANDOM</button>' +
            '<button type="button" id="shrine-seed-enter">ENTER CAVE</button>' +
            '<button type="button" id="shrine-seed-cancel">CANCEL</button>' +
            '</div>' +
            '</div>';
        document.body.appendChild(ui);
    }

    function hashStr(s) {
        let h = 2166136261;
        for (let i = 0; i < s.length; i++) {
            h ^= s.charCodeAt(i);
            h = Math.imul(h, 16777619);
        }
        return h >>> 0;
    }

    function create(scene, opts) {
        opts = opts || {};
        const S = opts.S;
        const terrain = opts.terrain;
        const count = opts.count || 10;
        const zoneW = opts.zoneW || 12000;
        const zoneL = opts.zoneL || 14000;
        const floorY = (opts.floorY != null) ? opts.floorY :
            (global.RoomBuilderV3 && RoomBuilderV3.FLOOR_Y) || 0;

        injectUI();
        const locks = loadLocks();
        const list = [];
        const solidExtra = [];

        // Deterministic shrine sites (not pure random each load)
        const siteRng = (function (seed) {
            let s = seed | 0;
            return function () {
                s ^= s << 13; s ^= s >>> 17; s ^= s << 5;
                return (s >>> 0) / 4294967295;
            };
        })(opts.worldSeed != null ? opts.worldSeed : 0xC0FFEE);

        function gy(x, z) {
            return terrain && terrain.getHeight ? terrain.getHeight(x, z) : floorY;
        }

        function placeOne(id, x, z) {
            const y = gy(x, z);
            const shapes = [];
            if (S) {
                // Deformed rock cluster
                shapes.push(S({
                    id: 'shrine_rock_' + id, layer: 'L2', geo: 'Cube',
                    scale: { x: 55 + siteRng() * 30, y: 40 + siteRng() * 25, z: 40 + siteRng() * 20 },
                    sizeZ: 50,
                    position: { x: x, y: y + 28, z: z - 18 },
                    color: '#4a4a48', solid: true
                }));
                shapes.push(S({
                    id: 'shrine_rock2_' + id, layer: 'L2', geo: 'Cube',
                    scale: { x: 30, y: 55, z: 28 }, sizeZ: 28,
                    position: { x: x - 35, y: y + 30, z: z - 10 },
                    color: '#3a3a38', solid: true
                }));
                shapes.push(S({
                    id: 'shrine_rock3_' + id, layer: 'L2', geo: 'Cube',
                    scale: { x: 28, y: 48, z: 26 }, sizeZ: 26,
                    position: { x: x + 32, y: y + 26, z: z - 8 },
                    color: '#555552', solid: true
                }));
                shapes.push(S({
                    id: 'shrine_door_' + id, layer: 'L3', geo: 'Cube',
                    scale: { x: 28, y: 36, z: 6 }, sizeZ: 6,
                    position: { x: x, y: y + 22, z: z + 8 },
                    color: locks[id] != null ? '#44ff66' : '#ffcc33',
                    solid: false
                }));
                const built = RoomBuilderV3.build(scene, shapes, {
                    layerZ: { L1: -80, L2: -20, L3: 0, L4: 40, UI: 100 }
                });
                if (built.solidBoxes) solidExtra.push.apply(solidExtra, built.solidBoxes);
            }
            // Visible beacon (hard to miss)
            const c = document.createElement('canvas');
            c.width = 128; c.height = 128;
            const ctx = c.getContext('2d');
            ctx.fillStyle = 'rgba(0,0,0,0)';
            ctx.clearRect(0,0,128,128);
            ctx.fillStyle = locks[id] != null ? '#44ff66' : '#ffee44';
            ctx.beginPath(); ctx.arc(64, 64, 40, 0, Math.PI * 2); ctx.fill();
            ctx.fillStyle = '#000';
            ctx.font = 'bold 48px monospace';
            ctx.textAlign = 'center'; ctx.textBaseline = 'middle';
            ctx.fillText('?', 64, 68);
            const bmat = new THREE.MeshBasicMaterial({
                map: new THREE.CanvasTexture(c), transparent: true, side: THREE.DoubleSide, depthWrite: false
            });
            const beacon = new THREE.Mesh(new THREE.PlaneGeometry(40, 40), bmat);
            beacon.position.set(x, y + 70, z + 12);
            beacon.userData.isProp = true;
            beacon.userData.propH = 40;
            beacon.userData.baseX = x;
            beacon.userData.baseZ = z;
            beacon.userData.isShrineBeacon = true;
            scene.add(beacon);
            list.push({ id: id, x: x, z: z, y: y, beacon: beacon });
        }

        // Scatter avoiding exact center spawn
        let placed = 0;
        let attempts = 0;
        while (placed < count && attempts < count * 20) {
            attempts++;
            const x = (siteRng() - 0.5) * zoneW * 0.85;
            const z = (siteRng() - 0.5) * zoneL * 0.85;
            if (Math.hypot(x, z) < 400) continue; // keep start clear
            // Prefer not on extreme peaks only — ok
            placeOne('shrine_' + placed, x, z);
            placed++;
        }

        let activeId = null;
        let onPause = opts.onPause || function () {};

        function openUI(shrine) {
            activeId = shrine.id;
            const ui = document.getElementById('shrine-seed-ui');
            const input = document.getElementById('shrine-seed-input');
            const sub = document.getElementById('shrine-ui-sub');
            const locked = locks[shrine.id];
            if (locked != null) {
                sub.textContent = 'Seed locked: ' + locked + ' — ENTER to delve.';
                input.value = String(locked);
                input.readOnly = true;
            } else {
                sub.textContent = 'Choose a seed. Once entered, this shrine is locked forever.';
                input.value = String((Math.random() * 999999) | 0);
                input.readOnly = false;
            }
            ui.classList.add('open');
            onPause(true);
        }

        function closeUI() {
            document.getElementById('shrine-seed-ui').classList.remove('open');
            activeId = null;
            onPause(false);
        }

        function enterCave() {
            if (!activeId) return;
            const input = document.getElementById('shrine-seed-input');
            let seedStr = (input.value || '').trim();
            if (!seedStr) seedStr = String((Math.random() * 999999) | 0);
            // numeric or hash
            let seedNum = parseInt(seedStr, 10);
            if (isNaN(seedNum)) seedNum = hashStr(seedStr);
            if (locks[activeId] == null) {
                locks[activeId] = seedStr;
                saveLocks(locks);
            } else {
                seedStr = String(locks[activeId]);
                seedNum = parseInt(seedStr, 10);
                if (isNaN(seedNum)) seedNum = hashStr(seedStr);
            }
            const url = 'shrine_dungeon.html?id=' + encodeURIComponent(activeId) +
                '&seed=' + encodeURIComponent(seedNum);
            window.location.href = url;
        }

        document.getElementById('shrine-seed-rand').onclick = function () {
            const input = document.getElementById('shrine-seed-input');
            if (input.readOnly) return;
            input.value = String((Math.random() * 999999) | 0);
        };
        document.getElementById('shrine-seed-enter').onclick = enterCave;
        document.getElementById('shrine-seed-cancel').onclick = closeUI;

        return {
            list: list,
            solidBoxes: solidExtra,
            tryInteract: function (pos) {
                if (!pos) return false;
                for (let i = 0; i < list.length; i++) {
                    const s = list[i];
                    if (Math.abs(pos.x - s.x) < 55 && Math.abs(pos.z - s.z) < 55) {
                        openUI(s);
                        return true;
                    }
                }
                return false;
            },
            isOpen: function () {
                const ui = document.getElementById('shrine-seed-ui');
                return ui && ui.classList.contains('open');
            },
            getLocks: function () { return Object.assign({}, locks); },
            update: function (camera) {
                if (!camera) return;
                for (let i = 0; i < list.length; i++) {
                    const b = list[i].beacon;
                    if (b) b.lookAt(camera.position.x, b.position.y, camera.position.z);
                }
            }
        };
    }

    global.ShrineSystemV3 = { create: create, loadLocks: loadLocks };
})(typeof window !== 'undefined' ? window : this);
