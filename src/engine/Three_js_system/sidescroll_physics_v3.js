/**
 * SidescrollPhysicsV3 -- 2.5D hybrid movement for Zelda II-style dungeons.
 * -------------------------------------------------------------------
 * Different axis convention from the rest of this project on purpose:
 * everywhere else (the overworld, D1's top-down palace), Y is a fixed
 * offset above a flat floor. Here, Y is REAL gravity-driven height --
 * the player can be airborne, fall, land on platforms at different
 * heights. X is the side-scroll axis (left/right). Z is not free
 * movement -- it's a small set of discrete LANES (front lane, back
 * lane, ...), each with its own platform layout, and the player only
 * moves between them at marked transition points (doors), not by
 * holding a direction. This is the "2.5D hybrid" brief: mostly
 * side-view, with doors/passages that sit at a different depth.
 *
 * A "lane" is:
 *   {
 *     z: -40,                              // world Z this lane renders/collides at
 *     platforms: [ {x0,x1,y}, ... ],       // horizontal ledges (top surface at y)
 *     walls: [ {x,y0,y1}, ... ],           // optional vertical blockers
 *     pitY: -400                           // fall below this Y = dead/respawn, per lane
 *   }
 *
 * A "laneDoor" is a marked transition zone:
 *   { x: 120, fromLane: 0, toLane: 1, landX: 120, landY: 20 }
 * Standing within DOOR_RADIUS of x while on fromLane and pressing the
 * interact key shifts the player to toLane, landing at (landX, landY).
 *
 * Usage:
 *   const phys = SidescrollPhysicsV3.create({
 *     startLane: 0, startX: 0, startY: 40
 *   });
 *
 *   // every frame:
 *   phys.update(dt, keys, lanes, laneDoors);
 *   // keys needs { left, right, up, down, jump, interact }
 *   // phys.x / phys.y / phys.lane / phys.facing / phys.crouching /
 *   // phys.grounded / phys.vy are all read live off the returned object.
 */
(function (global) {
    const GRAVITY = -980;          // units/sec^2 -- tuned to feel like Zelda II's floaty-but-snappy jump
    const JUMP_VELOCITY = 340;
    const MOVE_SPEED = 140;        // units/sec, horizontal
    const MOVE_ACCEL = 1600;       // units/sec^2 -- quick but not instant, keeps a little weight
    const AIR_CONTROL = 0.65;      // horizontal accel multiplier while airborne
    const DOOR_RADIUS = 26;
    const GROUND_SNAP_TOLERANCE = 6; // how far below feet a platform can be and still "catch" a fast fall

    function create(options) {
        options = options || {};

        const state = {
            x: options.startX || 0,
            y: options.startY || 0,
            vx: 0,
            vy: 0,
            lane: options.startLane || 0,
            facing: options.facing || 'right',
            crouching: false,
            grounded: false,
            dead: false,
            prevInteract: false,
            nearDoor: null // set each frame if standing in range of a laneDoor
        };

        function findLane(lanes, index) {
            return lanes && lanes[index] ? lanes[index] : null;
        }

        // Highest platform whose span contains x and whose top is at/below
        // (feetY + tolerance) -- i.e. a valid landing surface for this frame.
        function groundBelow(lane, x, feetY) {
            if (!lane || !lane.platforms) return null;
            let best = null;
            for (let i = 0; i < lane.platforms.length; i++) {
                const p = lane.platforms[i];
                if (x < p.x0 || x > p.x1) continue;
                if (p.y > feetY + GROUND_SNAP_TOLERANCE) continue; // above feet, can't land on it this frame
                if (!best || p.y > best.y) best = p;
            }
            return best;
        }

        function wallBlocking(lane, fromX, toX, y) {
            if (!lane || !lane.walls) return null;
            for (let i = 0; i < lane.walls.length; i++) {
                const w = lane.walls[i];
                if (y < w.y0 || y > w.y1) continue;
                if ((fromX < w.x && toX >= w.x) || (fromX > w.x && toX <= w.x)) return w;
            }
            return null;
        }

        const api = {
            get x() { return state.x; },
            get y() { return state.y; },
            get lane() { return state.lane; },
            get facing() { return state.facing; },
            get crouching() { return state.crouching; },
            get grounded() { return state.grounded; },
            get jumping() { return !state.grounded; },
            get vy() { return state.vy; },
            get nearDoor() { return state.nearDoor; },
            isDead: function () { return state.dead; },

            teleport: function (x, y, lane) {
                state.x = x; state.y = y; state.lane = lane;
                state.vx = 0; state.vy = 0; state.grounded = false;
            },

            /**
             * Lightweight horizontal shove -- unlike teleport(), this does
             * NOT touch vy/grounded, so getting hit while standing still
             * doesn't force a fake little "fall" frame. Just nudges x and
             * gives it a decaying vx kick.
             */
            applyKnockback: function (dx, vxKick) {
                state.x += dx;
                state.vx = vxKick != null ? vxKick : (dx > 0 ? 120 : -120);
            },

            /**
             * Call once per frame.
             * keys: { left, right, up, down, jump, interact }
             * lanes: array of lane defs (see file header)
             * laneDoors: array of door defs (see file header)
             */
            update: function (dt, keys, lanes, laneDoors) {
                if (state.dead) return;
                dt = Math.min(dt || 0.016, 0.05);

                const lane = findLane(lanes, state.lane);

                // ---- Crouch (only while grounded) ----
                state.crouching = !!(keys.down && state.grounded);

                // ---- Horizontal input ----
                let inputX = 0;
                if (keys.left) inputX -= 1;
                if (keys.right) inputX += 1;
                if (inputX !== 0) state.facing = inputX > 0 ? 'right' : 'left';

                const accel = MOVE_ACCEL * (state.grounded ? 1 : AIR_CONTROL);
                const targetVX = state.crouching ? 0 : inputX * MOVE_SPEED;
                if (state.vx < targetVX) state.vx = Math.min(state.vx + accel * dt, targetVX);
                else if (state.vx > targetVX) state.vx = Math.max(state.vx - accel * dt, targetVX);

                // ---- Jump ----
                if (keys.jump && state.grounded && !state.crouching) {
                    state.vy = JUMP_VELOCITY;
                    state.grounded = false;
                }

                // ---- Gravity ----
                if (!state.grounded) {
                    state.vy += GRAVITY * dt;
                }

                // ---- Integrate X with wall collision ----
                const prevX = state.x;
                let nextX = state.x + state.vx * dt;
                const wall = wallBlocking(lane, prevX, nextX, state.y + 4);
                if (wall) {
                    nextX = wall.x + (nextX > prevX ? -0.5 : 0.5);
                    state.vx = 0;
                }
                state.x = nextX;

                // ---- Integrate Y with ground collision ----
                const prevY = state.y;
                let nextY = state.y + state.vy * dt;
                const ground = groundBelow(lane, state.x, nextY);
                if (ground && prevY >= ground.y - 0.5 && nextY <= ground.y) {
                    nextY = ground.y;
                    state.vy = 0;
                    state.grounded = true;
                } else if (ground && state.grounded && nextY <= ground.y + GROUND_SNAP_TOLERANCE) {
                    nextY = ground.y;
                    state.grounded = true;
                } else {
                    state.grounded = false;
                }
                state.y = nextY;

                // ---- Pit check ----
                if (lane && lane.pitY != null && state.y < lane.pitY) {
                    state.dead = true;
                    if (options.onFallDeath) options.onFallDeath();
                    return;
                }

                // ---- Lane door proximity + shift ----
                state.nearDoor = null;
                if (laneDoors) {
                    for (let i = 0; i < laneDoors.length; i++) {
                        const d = laneDoors[i];
                        if (d.fromLane !== state.lane) continue;
                        if (Math.abs(state.x - d.x) <= DOOR_RADIUS) {
                            state.nearDoor = d;
                            break;
                        }
                    }
                }
                const interactPressed = !!keys.interact;
                if (interactPressed && !state.prevInteract && state.nearDoor) {
                    const d = state.nearDoor;
                    state.lane = d.toLane;
                    state.x = d.landX != null ? d.landX : state.x;
                    state.y = d.landY != null ? d.landY : state.y;
                    state.vx = 0; state.vy = 0;
                    state.grounded = false;
                    state.nearDoor = null;
                    if (options.onLaneShift) options.onLaneShift(d.fromLane, d.toLane);
                }
                state.prevInteract = interactPressed;
            }
        };

        return api;
    }

    global.SidescrollPhysicsV3 = { create: create };
})(window);
