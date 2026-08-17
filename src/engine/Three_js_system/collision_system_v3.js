(function (global) {
    /**
     * CollisionSystemV3 -- terrain and entity collision helpers
     * ---------------------------------------------------------
     * Responsibilities:
     *  - Snap sprites to terrain height (using TerrainGeneratorV3.getHeight)
     *  - Enforce room solid AABB collision via RoomBuilderV3.clamp
     *  - Resolve circle-vs-circle overlaps between entities (player/enemies)
     *
     * API:
     *   const cs = CollisionSystemV3.create();
     *   cs.update({ player, enemies, room, terrain });
     */

    function create() {
        function snapToTerrain(entity, terrain, offsetY) {
            if (!entity || !entity.mesh) return;
            offsetY = offsetY || (entity.height ? entity.height * 0.5 : 22.5);
            if (terrain && typeof terrain.getHeight === 'function') {
                const p = entity.mesh.position;
                p.y = terrain.getHeight(p.x, p.z) + offsetY;
            }
        }

        function enforceRoomSolids(entity, room) {
            if (!entity || !entity.mesh || !room) return;
            const pos = entity.mesh.position;
            if (global.RoomBuilderV3 && typeof global.RoomBuilderV3.clamp === 'function') {
                global.RoomBuilderV3.clamp(pos, room);
            }
        }

        function resolveEntityOverlaps(player, enemies) {
            if (!player || !player.mesh || !enemies) return;
            const ppos = player.mesh.position;
            const pr = player.radius || 18;

            for (let i = 0; i < enemies.length; i++) {
                const e = enemies[i];
                if (!e || !e.mesh) continue;
                const opos = e.mesh.position;
                const er = e.radius || 18;
                let dx = opos.x - ppos.x;
                let dz = opos.z - ppos.z;
                let dist = Math.sqrt(dx * dx + dz * dz) || 0.0001;
                const minDist = pr + er;
                if (dist < minDist) {
                    // push enemies away along vector proportional to penetration
                    const pen = (minDist - dist) + 0.1;
                    const nx = dx / dist;
                    const nz = dz / dist;
                    // distribute push: if enemy is static, push player only
                    const enemyIsStatic = !!(e.mesh.userData && e.mesh.userData.static);
                    if (enemyIsStatic) {
                        // push player out
                        ppos.x -= nx * pen;
                        ppos.z -= nz * pen;
                    } else {
                        const enemyPush = pen * 0.9;
                        const playerPush = pen * 0.1;
                        opos.x += nx * enemyPush;
                        opos.z += nz * enemyPush;
                        ppos.x -= nx * playerPush;
                        ppos.z -= nz * playerPush;
                    }
                }
            }
        }

        function resolveEnemyEnemy(enemies) {
            if (!enemies) return;
            for (let i = 0; i < enemies.length; i++) {
                const a = enemies[i];
                if (!a || !a.mesh) continue;
                for (let j = i + 1; j < enemies.length; j++) {
                    const b = enemies[j];
                    if (!b || !b.mesh) continue;
                    const ap = a.mesh.position; const bp = b.mesh.position;
                    let dx = bp.x - ap.x; let dz = bp.z - ap.z;
                    let dist = Math.sqrt(dx * dx + dz * dz) || 0.0001;
                    const minDist = (a.radius || 18) + (b.radius || 18);
                    if (dist < minDist) {
                        // if either is static, push the non-static one
                        const aStatic = !!(a.mesh.userData && a.mesh.userData.static);
                        const bStatic = !!(b.mesh.userData && b.mesh.userData.static);
                        const pen = (minDist - dist) * 0.5 + 0.01;
                        const nx = dx / dist; const nz = dz / dist;
                        if (aStatic && !bStatic) {
                            bp.x += nx * (pen * 2); bp.z += nz * (pen * 2);
                        } else if (bStatic && !aStatic) {
                            ap.x -= nx * (pen * 2); ap.z -= nz * (pen * 2);
                        } else {
                            bp.x += nx * pen; bp.z += nz * pen;
                            ap.x -= nx * pen; ap.z -= nz * pen;
                        }
                    }
                }
            }
        }

        function resolvePlayerProps(player, props) {
            if (!player || !player.mesh || !props) return;
            const ppos = player.mesh.position;
            const pr = player.radius || 18;
            for (let i = 0; i < props.length; i++) {
                const prop = props[i];
                if (!prop || !prop.position && !prop.mesh) continue;
                const m = prop.mesh || prop; // allow raw mesh or sprite wrapper
                const opos = m.position;
                const r = (m.userData && m.userData.radius) || (prop.radius) || 18;
                let dx = opos.x - ppos.x; let dz = opos.z - ppos.z;
                let dist = Math.sqrt(dx*dx + dz*dz) || 0.0001;
                const minDist = pr + r;
                if (dist < minDist) {
                    const pen = (minDist - dist) + 0.05;
                    const nx = dx / dist; const nz = dz / dist;
                    // props are normally static -> push player only
                    ppos.x -= nx * pen; ppos.z -= nz * pen;
                }
            }
        }

        return {
            snapToTerrain: snapToTerrain,
            enforceRoomSolids: enforceRoomSolids,
            resolveEntityOverlaps: resolveEntityOverlaps,
            resolveEnemyEnemy: resolveEnemyEnemy,

            /**
             * Update loop helper. Pass an object with keys:
             * { player, enemies, room, terrain }
             */
            update: function (opts) {
                opts = opts || {};
                const player = opts.player;
                const enemies = opts.enemies || [];
                const room = opts.room;
                const terrain = opts.terrain;

                // snap player to terrain first
                if (player) {
                    if (terrain) snapToTerrain(player, terrain);
                    if (room) enforceRoomSolids(player, room);
                }

                // snap enemies and enforce solids
                for (let i = 0; i < enemies.length; i++) {
                    const e = enemies[i];
                    if (!e || !e.mesh) continue;
                    if (terrain) snapToTerrain(e, terrain, e.height ? e.height * 0.5 : 20);
                    if (room) enforceRoomSolids(e, room);
                }

                // resolve overlaps
                if (player && enemies.length) resolveEntityOverlaps(player, enemies);
                if (opts.props && opts.props.length) resolvePlayerProps(player, opts.props);
                if (enemies.length) resolveEnemyEnemy(enemies);

                // also resolve enemy vs props (push enemies, not props)
                if (opts.props && opts.props.length && enemies.length) {
                    // for each enemy, check props
                    for (let i = 0; i < enemies.length; i++) {
                        const e = enemies[i];
                        if (!e || !e.mesh) continue;
                        const ep = e.mesh.position;
                        const er = e.radius || 18;
                        for (let j = 0; j < opts.props.length; j++) {
                            const prop = opts.props[j];
                            const m = prop.mesh || prop;
                            if (!m) continue;
                            const pp = m.position;
                            const pr = (m.userData && m.userData.radius) || 18;
                            let dx = ep.x - pp.x; let dz = ep.z - pp.z;
                            let dist = Math.sqrt(dx*dx + dz*dz) || 0.0001;
                            const minDist = er + pr;
                            if (dist < minDist) {
                                const pen = (minDist - dist) + 0.05;
                                const nx = dx / dist; const nz = dz / dist;
                                // if prop is static, push enemy away
                                const propStatic = !!(m.userData && m.userData.static);
                                if (propStatic) {
                                    ep.x += nx * pen; ep.z += nz * pen;
                                } else {
                                    // both movable: split
                                    ep.x += nx * (pen * 0.5); ep.z += nz * (pen * 0.5);
                                    pp.x -= nx * (pen * 0.5); pp.z -= nz * (pen * 0.5);
                                }
                            }
                        }
                    }
                }
            }
        };
    }

    global.CollisionSystemV3 = { create: create };
})(window);
