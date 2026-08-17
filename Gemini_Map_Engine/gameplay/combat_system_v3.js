/**
 * CombatSystemV3 -- player movement collision + attack/hit-detection.
 * -------------------------------------------------------------------
 * Third companion module alongside room_builder_v3.js and
 * sprite_system_v3.js. Doesn't replace either -- it drives a
 * SpriteSystemV3 player through a RoomBuilderV3 room:
 *
 *   - Calls RoomBuilderV3.clamp() every frame so `solid:true` shapes
 *     (pillars, walls, cabinets) actually block movement instead of
 *     just looking solid.
 *   - Adds a sword swing (existing SWORDUP/SWORDDOWN/SWORDLEFT/
 *     SWORDRIGHT sprites) on an attack key, with a short reach-check
 *     against a supplied enemies array (SpriteSystemV3 sprites).
 *   - Adds enemy-contact damage to the player: knockback + brief
 *     invulnerability (flicker), simple integer health.
 *   - Movement is camera-relative when you pass a yaw (see update()
 *     below) -- WASD means "relative to where the camera is currently
 *     facing," not fixed world axes. That's what keeps movement and a
 *     free-orbiting camera (CameraControlsV3) from fighting each other:
 *     rotate the camera and "forward" rotates with it, the way a normal
 *     third-person control scheme behaves. Sprite direction (up/down/
 *     left/right) still snaps to the nearest world cardinal internally
 *     since the walk/attack art only comes in 4 facings, but the actual
 *     motion itself is smooth, free-angle, camera-relative movement.
 *
 * Deliberately NOT a physics engine -- circle-vs-circle distance
 * checks on the XZ plane, same weight class as the rest of this
 * project's collision code. No Box2D, no extra render passes.
 *
 * Usage:
 *   const combat = CombatSystemV3.create(scene, loader, player, {
 *     maxHealth: 3,
 *     onDamage: (hp, max) => updateHeartsHud(hp, max),
 *     onDeath: () => showGameOver(),
 *     onKillEnemy: (enemy) => console.log('killed', enemy.mesh.userData),
 *     // Optional -- override if this project's sword frames live
 *     // somewhere other than assets/player/SWORD*.png (path layout
 *     // isn't guaranteed identical across repos/branches):
 *     // swordSprites: { up:[...], down:[...], left:[...], right:[...] }
 *   });
 *
 *   // every frame, instead of hand-rolling movement:
 *   combat.update(dt, keys, room, enemies, camCtrl.state.yaw);
 *   // keys needs a `.attack` boolean alongside w/a/s/d
 *   // cameraYaw is optional -- omit (or pass 0) for a locked camera
 *   // that never rotates, where world axes and screen axes are always
 *   // the same thing anyway (e.g. road_1.html's fixed chase cam).
 */
(function (global) {
    const FLOOR_Y = (global.RoomBuilderV3 && global.RoomBuilderV3.FLOOR_Y) || -100;

    const SWORD_SPRITES = {
        up:    ['assets/player/SWORDUP.png', 'assets/player/SWORDUP-1.png'],
        down:  ['assets/player/SWORDDOWN-1.png'],
        left:  ['assets/player/SWORDLEFT.png', 'assets/player/SWORDLEFT-1.png'],
        right: ['assets/player/SWORDRIGHT.png']
    };

    function prep(tex) {
        tex.magFilter = THREE.NearestFilter;
        tex.minFilter = THREE.NearestFilter;
        tex.generateMipmaps = false;
        return tex;
    }

    function dirVector(dir) {
        if (dir === 'up') return { x: 0, z: -1 };
        if (dir === 'down') return { x: 0, z: 1 };
        if (dir === 'left') return { x: -1, z: 0 };
        if (dir === 'right') return { x: 1, z: 0 };
        return { x: 0, z: 1 };
    }

    function create(scene, loader, player, options) {
        options = options || {};

        const spriteMap = options.swordSprites || SWORD_SPRITES;
        const swordTex = {};
        Object.keys(spriteMap).forEach(dir => {
            swordTex[dir] = spriteMap[dir].map(p => prep(loader.load(p)));
        });

        const MOVE_SPEED       = options.moveSpeed || 3.5;
        const ATTACK_DURATION  = options.attackDuration || 0.22;  // seconds sword is out
        const ATTACK_COOLDOWN  = options.attackCooldown || 0.28;  // seconds before next swing
        const ATTACK_RANGE     = options.attackRange || 46;       // hit-check radius
        const ATTACK_REACH     = options.attackReach || 30;       // how far in front of player the hitbox sits
        const PLAYER_RADIUS    = options.playerRadius || 20;
        const ENEMY_RADIUS     = options.enemyRadius || 22;
        const INVULN_DURATION  = options.invulnDuration || 0.9;
        const KNOCKBACK        = options.knockback || 26;

        const state = {
            health: options.maxHealth != null ? options.maxHealth : 3,
            maxHealth: options.maxHealth != null ? options.maxHealth : 3,
            dead: false,
            attacking: false,
            attackTimer: 0,
            attackCooldown: 0,
            invulnTimer: 0,
            prevAttackKey: false
        };

        function triggerAttack(dir) {
            state.attacking = true;
            state.attackTimer = ATTACK_DURATION;
            state.attackCooldown = ATTACK_COOLDOWN;
        }

        function applyDamageToPlayer(fromX, fromZ) {
            const pos = player.mesh.position;
            state.health -= 1;
            state.invulnTimer = INVULN_DURATION;

            const dx = pos.x - fromX;
            const dz = pos.z - fromZ;
            const dist = Math.sqrt(dx * dx + dz * dz);
            const kx = dist > 0.001 ? dx / dist : 0;
            const kz = dist > 0.001 ? dz / dist : 1;
            pos.x += kx * KNOCKBACK;
            pos.z += kz * KNOCKBACK;

            if (options.onDamage) options.onDamage(Math.max(state.health, 0), state.maxHealth);

            if (state.health <= 0 && !state.dead) {
                state.dead = true;
                if (options.onDeath) options.onDeath();
            }
        }

        const api = {
            getHealth: function () { return state.health; },
            getMaxHealth: function () { return state.maxHealth; },
            isDead: function () { return state.dead; },
            isAttacking: function () { return state.attacking; },

            /**
             * Call once per frame instead of hand-rolling movement.
             * keys needs { w, a, s, d, attack } booleans.
             * room is a RoomBuilderV3 room (from .build()) or null/undefined
             * to skip solid collision (e.g. an empty flat room).
             * enemies is a live array of SpriteSystemV3 enemy sprites --
             * killed enemies are scene.remove()'d, .dispose()'d, and
             * spliced out of the array for you.
             * cameraYaw (radians, same convention as CameraControlsV3's
             * state.yaw) makes WASD move relative to where the camera is
             * currently facing instead of fixed world axes -- omit it
             * (or pass 0) for a locked camera-behind-player setup like
             * road_1.html, where world axes and screen axes never diverge.
             */
            update: function (dt, keys, room, enemies, cameraYaw) {
                if (state.dead) return;
                dt = dt || 0.016;
                cameraYaw = cameraYaw || 0;

                if (state.attackCooldown > 0) state.attackCooldown -= dt;
                if (state.invulnTimer > 0) state.invulnTimer -= dt;

                const pos = player.mesh.position;
                let dir = player.getDirection();
                let moving = false;

                if (!state.attacking) {
                    // Camera-relative input axes: forward is "into the
                    // screen" from the camera's current angle, right is
                    // perpendicular to that -- matches CameraControlsV3's
                    // x = target.x + sin(yaw)*dist, z = target.z + cos(yaw)*dist
                    // convention (camera sits at that offset looking back
                    // at the target, so screen-forward is the negation).
                    const fwdX = -Math.sin(cameraYaw), fwdZ = -Math.cos(cameraYaw);
                    const rightX = Math.cos(cameraYaw), rightZ = -Math.sin(cameraYaw);

                    let inF = 0, inR = 0;
                    if (keys.w) inF += 1;
                    if (keys.s) inF -= 1;
                    if (keys.d) inR += 1;
                    if (keys.a) inR -= 1;

                    if (inF !== 0 || inR !== 0) {
                        moving = true;
                        let mx = fwdX * inF + rightX * inR;
                        let mz = fwdZ * inF + rightZ * inR;
                        const len = Math.sqrt(mx * mx + mz * mz) || 1;
                        mx /= len; mz /= len; // normalize so diagonals aren't faster

                        pos.x += mx * MOVE_SPEED;
                        pos.z += mz * MOVE_SPEED;

                        // Snap the resulting world-space direction to the
                        // nearest cardinal purely for sprite selection --
                        // the SWORD/VRMAN textures only come in 4 facings,
                        // this doesn't affect the smooth free-angle motion.
                        dir = Math.abs(mx) > Math.abs(mz)
                            ? (mx > 0 ? 'right' : 'left')
                            : (mz > 0 ? 'down' : 'up');
                    }
                }

                // Real collision against solid room geometry (pillars/walls/cabinets)
                if (global.RoomBuilderV3 && room) {
                    global.RoomBuilderV3.clamp(pos, room);
                }

                player.setDirection(dir);
                if (!state.attacking) {
                    player.setMoving(moving);
                    player.update();
                }

                // ---- Attack trigger (rising edge -- press, not hold) ----
                if (keys.attack && !state.prevAttackKey && state.attackCooldown <= 0 && !state.attacking) {
                    triggerAttack(dir);
                }
                state.prevAttackKey = !!keys.attack;

                if (state.attacking) {
                    state.attackTimer -= dt;
                    const frames = swordTex[dir] || swordTex.down;
                    const frameIdx = (frames.length > 1 && state.attackTimer < ATTACK_DURATION * 0.5) ? 1 : 0;
                    player.material.map = frames[frameIdx];
                    player.material.needsUpdate = true;

                    if (state.attackTimer <= 0) {
                        state.attacking = false;
                        player.setMoving(false); // snaps material back to idle frame for currentDir
                    } else if (enemies && enemies.length) {
                        const dv = dirVector(dir);
                        const hitX = pos.x + dv.x * ATTACK_REACH;
                        const hitZ = pos.z + dv.z * ATTACK_REACH;
                        for (let i = enemies.length - 1; i >= 0; i--) {
                            const e = enemies[i];
                            const dx = e.mesh.position.x - hitX;
                            const dz = e.mesh.position.z - hitZ;
                            if (Math.sqrt(dx * dx + dz * dz) <= ATTACK_RANGE) {
                                if (options.onEnemyHit) options.onEnemyHit(e);
                                scene.remove(e.mesh);
                                e.dispose();
                                enemies.splice(i, 1);
                                if (options.onKillEnemy) options.onKillEnemy(e);
                            }
                        }
                    }
                }

                // ---- Enemy contact damage (one hit per frame max) ----
                if (state.invulnTimer <= 0 && enemies && enemies.length) {
                    for (let i = 0; i < enemies.length; i++) {
                        const e = enemies[i];
                        const dx = e.mesh.position.x - pos.x;
                        const dz = e.mesh.position.z - pos.z;
                        if (Math.sqrt(dx * dx + dz * dz) <= (PLAYER_RADIUS + ENEMY_RADIUS)) {
                            applyDamageToPlayer(e.mesh.position.x, e.mesh.position.z);
                            if (global.RoomBuilderV3 && room) global.RoomBuilderV3.clamp(pos, room);
                            break;
                        }
                    }
                }

                // Invulnerability flicker
                if (state.invulnTimer > 0) {
                    player.material.opacity = (Math.floor(state.invulnTimer * 20) % 2 === 0) ? 0.35 : 1;
                } else {
                    player.material.opacity = 1;
                }
            }
        };

        return api;
    }

    global.CombatSystemV3 = { create: create };
})(window);
