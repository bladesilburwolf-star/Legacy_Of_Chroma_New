/**
 * Zelda2CombatV3 -- standing/crouch/jump thrust combat for the 2.5D
 * side-scroll dungeons. Deliberately a fresh, separate system from
 * CombatSystemV3 (that one's the 4-directional top-down sword swing
 * used in D1 and the overworld) -- Zelda II's combat is a horizontal
 * poke with three distinct forms depending on player state, not a
 * swing. Mixed-dungeon-style project: different dungeons, different
 * engines under the hood, sharing nothing but the pattern.
 *
 * Reads player state off a SidescrollPhysicsV3 controller (facing,
 * crouching, grounded, x/y) rather than owning movement itself --
 * these two modules are meant to be used together but stay decoupled.
 *
 * Usage:
 *   const combat = Zelda2CombatV3.create(physics, {
 *     maxHealth: 8,
 *     onDamage: (hp, max) => updateHeartsHud(hp, max),
 *     onDeath: () => showGameOver(),
 *     onKillEnemy: (enemy) => {...}
 *   });
 *
 *   // every frame, after physics.update():
 *   combat.update(dt, keys, enemies); // keys needs { attack }
 */
(function (global) {
    const THRUST_DURATION = 0.18;
    const THRUST_COOLDOWN = 0.16;
    const THRUST_REACH = 30;        // how far in front of the player the hitbox center sits
    const THRUST_RANGE = 20;        // hit-check radius around that point
    const PLAYER_RADIUS = 12;
    const ENEMY_RADIUS = 14;
    const INVULN_DURATION = 0.8;
    const KNOCKBACK = 60;

    // Vertical hitbox offset from player feet (state.y), by attack form.
    // Standing thrust pokes at chest height; crouch thrust is low;
    // jump thrust stabs downward-diagonal (classic Zelda II jump-stab).
    const HITBOX_Y_OFFSET = {
        standing: 20,
        crouch: 6,
        jump: -6
    };

    function create(physics, options) {
        options = options || {};

        const state = {
            health: options.maxHealth != null ? options.maxHealth : 8,
            maxHealth: options.maxHealth != null ? options.maxHealth : 8,
            dead: false,
            attacking: false,
            attackForm: null, // 'standing' | 'crouch' | 'jump'
            attackTimer: 0,
            attackCooldown: 0,
            invulnTimer: 0,
            prevAttackKey: false
        };

        function applyDamageToPlayer(fromX) {
            state.health -= 1;
            state.invulnTimer = INVULN_DURATION;
            const dir = physics.x >= fromX ? 1 : -1;
            physics.applyKnockback(dir * (KNOCKBACK * 0.5), dir * 150);
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
            getAttackForm: function () { return state.attackForm; },

            /**
             * enemies: array of { x, y, lane, radius?, onHit()->bool (return
             * true if killed / should be removed), dead? } -- deliberately a
             * plain-object contract, not tied to SpriteSystemV3, so this can
             * be used with placeholder test geometry before real dungeon
             * enemy art exists for this style.
             */
            update: function (dt, keys, enemies) {
                if (state.dead) return;
                dt = dt || 0.016;

                if (state.attackCooldown > 0) state.attackCooldown -= dt;
                if (state.invulnTimer > 0) state.invulnTimer -= dt;

                const attackKey = !!keys.attack;
                if (attackKey && !state.prevAttackKey && state.attackCooldown <= 0 && !state.attacking) {
                    state.attacking = true;
                    state.attackTimer = THRUST_DURATION;
                    state.attackCooldown = THRUST_COOLDOWN;
                    if (!physics.grounded) state.attackForm = 'jump';
                    else if (physics.crouching) state.attackForm = 'crouch';
                    else state.attackForm = 'standing';
                }
                state.prevAttackKey = attackKey;

                if (state.attacking) {
                    state.attackTimer -= dt;
                    if (state.attackTimer <= 0) {
                        state.attacking = false;
                        state.attackForm = null;
                    } else if (enemies && enemies.length) {
                        const dir = physics.facing === 'right' ? 1 : -1;
                        const hitX = physics.x + dir * THRUST_REACH;
                        const hitY = physics.y + (HITBOX_Y_OFFSET[state.attackForm] || 0);
                        for (let i = enemies.length - 1; i >= 0; i--) {
                            const e = enemies[i];
                            if (!e || e.dead) continue;
                            if (e.lane !== physics.lane) continue;
                            const dx = e.x - hitX;
                            const dy = e.y - hitY;
                            const r = THRUST_RANGE + (e.radius || ENEMY_RADIUS);
                            if (Math.sqrt(dx * dx + dy * dy) <= r) {
                                const killed = e.onHit ? e.onHit(state.attackForm) : true;
                                if (killed) {
                                    e.dead = true;
                                    if (options.onKillEnemy) options.onKillEnemy(e);
                                }
                            }
                        }
                    }
                }

                if (state.invulnTimer <= 0 && enemies && enemies.length) {
                    for (let i = 0; i < enemies.length; i++) {
                        const e = enemies[i];
                        if (!e || e.dead) continue;
                        if (e.lane !== physics.lane) continue;
                        const dx = e.x - physics.x;
                        const dy = e.y - (physics.y + 16);
                        const r = PLAYER_RADIUS + (e.radius || ENEMY_RADIUS);
                        if (Math.sqrt(dx * dx + dy * dy) <= r) {
                            applyDamageToPlayer(e.x);
                            break;
                        }
                    }
                }
            }
        };

        return api;
    }

    global.Zelda2CombatV3 = { create: create };
})(window);
