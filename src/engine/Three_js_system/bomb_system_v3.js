/**
 * BombSystemV3 — place / fuse / explode (OoT-style)
 * -------------------------------------------------
 *   const bombs = BombSystemV3.create(scene, {
 *     inventory,
 *     getPlayerPos: () => player.position,
 *     onBlast: (x, z, radius) => {},  // destroy boulders, damage enemies
 *     particleTexture: 'assets/particle/…'
 *   });
 *   bombs.tryPlace();           // uses 1 bomb from inventory
 *   bombs.update(dt);
 *   bombs.addDrop(x, z);        // world bomb pickup
 */
(function (global) {
    const FUSE = 2.2;
    const BLAST_R = 70;
    const PLACE_COOLDOWN = 0.45;

    function makeExplosion(scene, x, y, z, opts) {
        opts = opts || {};
        const group = new THREE.Group();
        group.position.set(x, y, z);
        scene.add(group);

        const spheres = [];
        const colors = [0xffaa33, 0xff6622, 0xffee88, 0x884422];
        for (let i = 0; i < 10; i++) {
            const m = new THREE.Mesh(
                new THREE.SphereGeometry(4 + Math.random() * 8, 6, 6),
                new THREE.MeshBasicMaterial({
                    color: colors[i % colors.length],
                    transparent: true,
                    opacity: 0.9
                })
            );
            m.userData.vx = (Math.random() - 0.5) * 120;
            m.userData.vy = 40 + Math.random() * 100;
            m.userData.vz = (Math.random() - 0.5) * 120;
            group.add(m);
            spheres.push(m);
        }

        // Flash ring
        const ring = new THREE.Mesh(
            new THREE.RingGeometry(8, 28, 16),
            new THREE.MeshBasicMaterial({
                color: 0xffcc66,
                transparent: true,
                opacity: 0.8,
                side: THREE.DoubleSide
            })
        );
        ring.rotation.x = -Math.PI / 2;
        group.add(ring);

        let t = 0;
        return {
            group: group,
            update: function (dt) {
                t += dt;
                ring.scale.setScalar(1 + t * 8);
                ring.material.opacity = Math.max(0, 0.8 - t * 1.5);
                spheres.forEach(function (m) {
                    m.position.x += m.userData.vx * dt;
                    m.position.y += m.userData.vy * dt;
                    m.position.z += m.userData.vz * dt;
                    m.userData.vy -= 180 * dt;
                    m.material.opacity = Math.max(0, 0.9 - t * 1.2);
                    m.scale.multiplyScalar(0.98);
                });
                return t < 0.85;
            },
            dispose: function () {
                scene.remove(group);
                group.traverse(function (o) {
                    if (o.geometry) o.geometry.dispose();
                    if (o.material) o.material.dispose();
                });
            }
        };
    }

    function create(scene, options) {
        options = options || {};
        const inventory = options.inventory;
        const active = [];
        const fx = [];
        const drops = [];
        let cooldown = 0;

        function blast(x, z) {
            const y = options.blastY != null ? options.blastY : 20;
            fx.push(makeExplosion(scene, x, y, z));
            if (options.onBlast) options.onBlast(x, z, BLAST_R);
            if (options.playSfx) {
                try { options.playSfx('assets/fanfare/secret.wav'); } catch (e) {}
            }
        }

        const api = {
            BLAST_RADIUS: BLAST_R,
            tryPlace: function () {
                if (cooldown > 0) return false;
                if (!inventory || !inventory.has('bombs') || inventory.count('bombs') <= 0) {
                    if (options.onMessage) options.onMessage('No bombs!');
                    return false;
                }
                const pos = options.getPlayerPos ? options.getPlayerPos() : null;
                if (!pos) return false;
                inventory.take('bombs', 1);
                cooldown = PLACE_COOLDOWN;

                const mesh = new THREE.Mesh(
                    new THREE.SphereGeometry(10, 8, 8),
                    new THREE.MeshStandardMaterial({
                        color: 0x222222,
                        emissive: 0x441100,
                        emissiveIntensity: 0.3,
                        roughness: 0.8
                    })
                );
                // Try bomb sprite
                try {
                    new THREE.TextureLoader().load('assets/inventory/BOMBS.png', function (tex) {
                        tex.magFilter = THREE.NearestFilter;
                        mesh.material = new THREE.MeshBasicMaterial({
                            map: tex, transparent: true, side: THREE.DoubleSide
                        });
                        mesh.geometry = new THREE.PlaneGeometry(22, 22);
                    });
                } catch (e) {}

                mesh.position.set(pos.x, (pos.y || 0) + 8, pos.z);
                scene.add(mesh);
                active.push({ mesh: mesh, fuse: FUSE, x: pos.x, z: pos.z });
                if (options.onMessage) {
                    options.onMessage('Bomb set! (' + inventory.count('bombs') + ' left)');
                }
                return true;
            },

            /** Enemy/chest drop in the world */
            addDrop: function (x, z, count) {
                count = count || 1;
                const mesh = new THREE.Mesh(
                    new THREE.PlaneGeometry(20, 20),
                    new THREE.MeshBasicMaterial({
                        color: 0x333333, transparent: true, side: THREE.DoubleSide
                    })
                );
                try {
                    new THREE.TextureLoader().load('assets/inventory/BOMBS-1.png', function (tex) {
                        tex.magFilter = THREE.NearestFilter;
                        mesh.material.map = tex;
                        mesh.material.color.setHex(0xffffff);
                        mesh.material.needsUpdate = true;
                    });
                } catch (e) {}
                const y = options.blastY != null ? options.blastY : 14;
                mesh.position.set(x, y, z);
                scene.add(mesh);
                drops.push({ mesh: mesh, x: x, z: z, count: count });
            },

            tryPickup: function (px, pz, radius) {
                radius = radius || 36;
                for (let i = drops.length - 1; i >= 0; i--) {
                    const d = drops[i];
                    if (Math.hypot(px - d.x, pz - d.z) < radius) {
                        if (inventory) inventory.give('bombs', d.count);
                        scene.remove(d.mesh);
                        drops.splice(i, 1);
                        if (options.onMessage) {
                            options.onMessage('Got bombs x' + d.count + ' (' + inventory.count('bombs') + ')');
                        }
                        return true;
                    }
                }
                return false;
            },

            update: function (dt) {
                if (cooldown > 0) cooldown -= dt;
                for (let i = active.length - 1; i >= 0; i--) {
                    const b = active[i];
                    b.fuse -= dt;
                    // Blink fuse
                    if (b.mesh.material && b.mesh.material.emissive) {
                        b.mesh.material.emissiveIntensity = 0.3 + 0.7 * Math.abs(Math.sin(b.fuse * 12));
                    } else if (b.mesh.material) {
                        b.mesh.material.opacity = 0.5 + 0.5 * Math.abs(Math.sin(b.fuse * 12));
                    }
                    if (b.fuse <= 0) {
                        scene.remove(b.mesh);
                        active.splice(i, 1);
                        blast(b.x, b.z);
                    }
                }
                for (let i = fx.length - 1; i >= 0; i--) {
                    if (!fx[i].update(dt)) {
                        fx[i].dispose();
                        fx.splice(i, 1);
                    }
                }
                // Billboard drops
                drops.forEach(function (d) {
                    if (options.getCamera) {
                        const c = options.getCamera();
                        if (c) d.mesh.lookAt(c.position.x, d.mesh.position.y, c.position.z);
                    }
                });
            },

            getBlastRadius: function () { return BLAST_R; }
        };
        return api;
    }

    global.BombSystemV3 = { create: create, BLAST_R: BLAST_R };
})(typeof window !== 'undefined' ? window : this);
