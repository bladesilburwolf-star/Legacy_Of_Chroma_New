/**
 * HpBarSystemV3 -- billboard HP bars above heads
 * -----------------------------------------------
 * Uses existing HUD sprites:
 *   Player: assets/hud/1HP.png … 7HP.png
 *   Enemy:  assets/hud/ENEMY1HP.png … ENEMY8HP.png
 *   Boss:   assets/ui/BOSSHP (1).png … (8).png
 *
 * Usage:
 *   const bars = HpBarSystemV3.create(scene, loader);
 *   bars.attach(player.mesh, { type: 'player', max: 6, current: 6 });
 *   bars.attach(enemy.mesh,  { type: 'enemy',  max: 3, current: 3 });
 *   bars.set(enemy.mesh, 2);
 *   // each frame:
 *   bars.update(camera);
 */
(function (global) {
    function create(scene, loader) {
        loader = loader || new THREE.TextureLoader();
        const cache = {};
        const entries = []; // { mesh, bar, type, max, current, offsetY }

        function prep(tex) {
            tex.magFilter = THREE.NearestFilter;
            tex.minFilter = THREE.NearestFilter;
            tex.generateMipmaps = false;
            return tex;
        }

        function pathFor(type, current, max) {
            const c = Math.max(0, Math.min(max, current | 0));
            if (type === 'boss') {
                const i = Math.max(1, Math.min(8, c));
                return 'assets/ui/BOSSHP (' + i + ').png';
            }
            if (type === 'enemy') {
                const i = Math.max(1, Math.min(8, c));
                return 'assets/hud/ENEMY' + i + 'HP.png';
            }
            // player 1..7
            const i = Math.max(1, Math.min(7, c));
            return 'assets/hud/' + i + 'HP.png';
        }

        function getTexture(path) {
            if (cache[path]) return cache[path];
            const tex = prep(loader.load(path));
            cache[path] = tex;
            return tex;
        }

        function makeBar(type, max, current, width, height) {
            const path = pathFor(type, current, max);
            const mat = new THREE.MeshBasicMaterial({
                map: getTexture(path),
                transparent: true,
                depthWrite: false,
                side: THREE.DoubleSide
            });
            const mesh = new THREE.Mesh(
                new THREE.PlaneGeometry(width || 36, height || 10),
                mat
            );
            mesh.renderOrder = 10;
            mesh.name = 'hp-bar';
            return mesh;
        }

        const api = {
            /**
             * Attach bar above a target Object3D (player.mesh or enemy.mesh)
             */
            attach: function (target, opts) {
                opts = opts || {};
                // remove existing
                api.detach(target);
                const type = opts.type || 'enemy';
                const max = opts.max != null ? opts.max : (type === 'player' ? 6 : 3);
                const current = opts.current != null ? opts.current : max;
                const bar = makeBar(type, max, current, opts.width, opts.height);
                const offsetY = opts.offsetY != null ? opts.offsetY : 48;
                scene.add(bar);
                const entry = {
                    target: target,
                    bar: bar,
                    type: type,
                    max: max,
                    current: current,
                    offsetY: offsetY
                };
                entries.push(entry);
                target.userData = target.userData || {};
                target.userData.hpBar = entry;
                return entry;
            },

            detach: function (target) {
                for (let i = entries.length - 1; i >= 0; i--) {
                    if (entries[i].target === target) {
                        scene.remove(entries[i].bar);
                        entries[i].bar.geometry.dispose();
                        entries[i].bar.material.dispose();
                        entries.splice(i, 1);
                    }
                }
                if (target.userData) delete target.userData.hpBar;
            },

            set: function (target, current) {
                const e = target.userData && target.userData.hpBar;
                if (!e) return;
                e.current = Math.max(0, Math.min(e.max, current | 0));
                const path = pathFor(e.type, e.current, e.max);
                e.bar.material.map = getTexture(path);
                e.bar.material.needsUpdate = true;
                e.bar.visible = e.current > 0;
            },

            damage: function (target, amount) {
                const e = target.userData && target.userData.hpBar;
                if (!e) return 0;
                e.current = Math.max(0, e.current - (amount || 1));
                api.set(target, e.current);
                return e.current;
            },

            heal: function (target, amount) {
                const e = target.userData && target.userData.hpBar;
                if (!e) return 0;
                e.current = Math.min(e.max, e.current + (amount || 1));
                api.set(target, e.current);
                return e.current;
            },

            get: function (target) {
                const e = target.userData && target.userData.hpBar;
                return e ? e.current : 0;
            },

            update: function (camera) {
                for (let i = 0; i < entries.length; i++) {
                    const e = entries[i];
                    if (!e.target || !e.target.parent) continue;
                    const p = e.target.position;
                    e.bar.position.set(p.x, p.y + e.offsetY, p.z);
                    e.bar.lookAt(camera.position.x, e.bar.position.y, camera.position.z);
                }
            },

            clear: function () {
                while (entries.length) {
                    api.detach(entries[0].target);
                }
            }
        };

        return api;
    }

    global.HpBarSystemV3 = { create: create };
})(typeof window !== 'undefined' ? window : this);
