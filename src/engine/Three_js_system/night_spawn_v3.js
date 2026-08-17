/**
 * NightSpawnV3 -- Ocarina-of-Time style field encounters
 * ------------------------------------------------------
 * At night, enemies periodically spawn on the open field around
 * the player. At day, they despawn (fade/remove). No permanent
 * randomizer panel -- driven only by SkyboxSystem hour.
 *
 * Usage:
 *   const night = NightSpawnV3.create({
 *     scene, loader, player,
 *     enemyPaths: ['assets/enemies/SLIME-1-0.png', ...],
 *     getHour: () => sky.getHour(),
 *     getHeight: (x,z) => terrain.getHeight(x,z),  // optional
 *     maxAlive: 6,
 *     spawnInterval: 4.5,   // seconds between spawn attempts at night
 *     radiusMin: 180,
 *     radiusMax: 420
 *   });
 *
 *   // each frame:
 *   night.update(dt);
 *   night.faceCamera(camera);
 *   // combat can use night.getEnemies()
 */
(function (global) {
    function create(options) {
        options = options || {};
        const scene = options.scene;
        const loader = options.loader || new THREE.TextureLoader();
        const player = options.player;
        const paths = options.enemyPaths || ['assets/enemies/SLIME-1-0.png'];
        const getHour = options.getHour || function () { return 12; };
        const getHeight = options.getHeight || null;
        const maxAlive = options.maxAlive != null ? options.maxAlive : 6;
        const spawnInterval = options.spawnInterval != null ? options.spawnInterval : 4.5;
        const radiusMin = options.radiusMin != null ? options.radiusMin : 160;
        const radiusMax = options.radiusMax != null ? options.radiusMax : 400;
        const floorY = (global.RoomBuilderV3 && RoomBuilderV3.FLOOR_Y) || -100;
        const spriteH = options.spriteHeight || 40;

        const enemies = [];
        let timer = 0;
        let wasNight = false;

        function isNightHour(h) {
            // OOT-ish: night from ~19:00 to ~5:00
            return h >= 19 || h < 5;
        }

        function pickPath() {
            return paths[(Math.random() * paths.length) | 0];
        }

        function spawnOne() {
            if (!player || !global.SpriteSystemV3) return;
            if (enemies.length >= maxAlive) return;

            const ang = Math.random() * Math.PI * 2;
            const dist = radiusMin + Math.random() * (radiusMax - radiusMin);
            const px = player.mesh ? player.mesh.position.x : player.position.x;
            const pz = player.mesh ? player.mesh.position.z : player.position.z;
            const x = px + Math.cos(ang) * dist;
            const z = pz + Math.sin(ang) * dist;

            const e = SpriteSystemV3.createEnemy(pickPath(), loader, {
                id: 'night_' + Math.random().toString(36).slice(2, 7),
                position: { x: x, y: 0, z: z },
                width: options.spriteWidth || 40,
                height: spriteH
            });
            if (getHeight) {
                const hy = getHeight(x, z);
                e.mesh.position.y = hy + spriteH * 0.5;
            }
            e._nightSpawn = true;
            e._fade = 0;
            e.mesh.material.transparent = true;
            e.mesh.material.opacity = 0;
            scene.add(e.mesh);
            enemies.push(e);
        }

        function despawnAll() {
            for (let i = enemies.length - 1; i >= 0; i--) {
                const e = enemies[i];
                scene.remove(e.mesh);
                if (e.dispose) e.dispose();
                enemies.splice(i, 1);
            }
        }

        function fadeOutAll(dt) {
            for (let i = enemies.length - 1; i >= 0; i--) {
                const e = enemies[i];
                e._fade = (e._fade != null ? e._fade : 1) - dt * 1.5;
                e.mesh.material.opacity = Math.max(0, e._fade);
                if (e._fade <= 0) {
                    scene.remove(e.mesh);
                    if (e.dispose) e.dispose();
                    enemies.splice(i, 1);
                }
            }
        }

        function fadeIn(dt) {
            enemies.forEach(function (e) {
                if (e._fade < 1) {
                    e._fade = Math.min(1, (e._fade || 0) + dt * 2);
                    e.mesh.material.opacity = e._fade;
                }
            });
        }

        const api = {
            getEnemies: function () { return enemies; },

            update: function (dt) {
                const h = getHour();
                const night = isNightHour(h);

                if (night && !wasNight) {
                    // Just became night — reset spawn timer
                    timer = spawnInterval * 0.5;
                }
                if (!night && wasNight) {
                    // Dawn — start despawn fade
                    enemies.forEach(function (e) { e._fade = e.mesh.material.opacity; });
                }
                wasNight = night;

                if (night) {
                    timer -= dt;
                    if (timer <= 0) {
                        spawnOne();
                        timer = spawnInterval * (0.7 + Math.random() * 0.6);
                    }
                    fadeIn(dt);
                    // Keep feet on terrain
                    if (getHeight) {
                        enemies.forEach(function (e) {
                            const p = e.mesh.position;
                            p.y = getHeight(p.x, p.z) + spriteH * 0.5;
                        });
                    }
                } else {
                    fadeOutAll(dt);
                }
            },

            faceCamera: function (camera) {
                enemies.forEach(function (e) {
                    if (e.faceCamera) e.faceCamera(camera);
                });
            },

            clear: despawnAll,

            isNight: function () { return isNightHour(getHour()); }
        };

        return api;
    }

    global.NightSpawnV3 = { create: create };
})(typeof window !== 'undefined' ? window : this);
