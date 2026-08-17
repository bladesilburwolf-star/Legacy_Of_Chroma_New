/**
 * PlayerStateV3 -- cross-page persistent player state
 * ----------------------------------------------------
 * Shared HP, inventory snapshot, flags, and last position
 * via localStorage so hub ↔ dungeon warps keep progress.
 *
 * Usage:
 *   const state = PlayerStateV3.load();
 *   // after inventory create:
 *   state.applyInventory(inventory);
 *   state.applyHp(hpBars, player.mesh);
 *   // on pickup / damage / flag:
 *   state.syncFromInventory(inventory);
 *   state.setHp(current, max);
 *   state.setFlag('d1_cleared', true);
 *   state.save();
 *   // before warp:
 *   PlayerStateV3.checkpoint(inventory, { hp, maxHp, x, y, z, room });
 */
(function (global) {
    const STORAGE_KEY = 'neondromeda_player_v1';
    const VERSION = 1;

    function defaultState() {
        return {
            v: VERSION,
            hp: 6,
            maxHp: 6,
            inventory: { items: {}, equipped: null },
            flags: {},          // e.g. d1_cleared, has_met_oldman, sword_taken
            keys: 0,
            rupees: 0,
            lastRoom: 'index.html',
            lastPos: null,      // { x, y, z, room }
            updatedAt: Date.now()
        };
    }

    function load() {
        let data = defaultState();
        try {
            const raw = global.localStorage && localStorage.getItem(STORAGE_KEY);
            if (raw) {
                const parsed = JSON.parse(raw);
                if (parsed && typeof parsed === 'object') {
                    data = Object.assign(defaultState(), parsed);
                    data.inventory = data.inventory || { items: {}, equipped: null };
                    data.flags = data.flags || {};
                }
            }
        } catch (e) {
            console.warn('PlayerStateV3.load failed', e);
        }
        return apiFrom(data);
    }

    function apiFrom(data) {
        const api = {
            data: data,

            save: function () {
                data.updatedAt = Date.now();
                try {
                    if (global.localStorage) {
                        localStorage.setItem(STORAGE_KEY, JSON.stringify(data));
                    }
                } catch (e) {
                    console.warn('PlayerStateV3.save failed', e);
                }
                return api;
            },

            reset: function () {
                data = defaultState();
                api.data = data;
                try {
                    if (global.localStorage) localStorage.removeItem(STORAGE_KEY);
                } catch (e) {}
                return api;
            },

            // ---- HP ----
            getHp: function () { return data.hp; },
            getMaxHp: function () { return data.maxHp; },
            setHp: function (hp, maxHp) {
                if (maxHp != null) data.maxHp = maxHp;
                data.hp = Math.max(0, Math.min(data.maxHp, hp));
                return api;
            },
            damage: function (n) {
                data.hp = Math.max(0, data.hp - (n || 1));
                return data.hp;
            },
            heal: function (n) {
                data.hp = Math.min(data.maxHp, data.hp + (n || 1));
                return data.hp;
            },
            applyHp: function (hpBars, mesh) {
                if (hpBars && mesh && typeof hpBars.set === 'function') {
                    hpBars.set(mesh, data.hp);
                } else if (hpBars && mesh && typeof hpBars.attach === 'function') {
                    // re-attach with stored values if set missing
                    try {
                        hpBars.attach(mesh, { type: 'player', max: data.maxHp, current: data.hp, offsetY: 52 });
                    } catch (e) {}
                }
                return api;
            },

            // ---- Inventory bridge ----
            applyInventory: function (inventory) {
                if (!inventory || typeof inventory.fromJSON !== 'function') return api;
                inventory.fromJSON(data.inventory || { items: {}, equipped: null });
                return api;
            },
            syncFromInventory: function (inventory) {
                if (!inventory || typeof inventory.toJSON !== 'function') return api;
                data.inventory = inventory.toJSON();
                return api;
            },

            // ---- Flags / keys / rupees ----
            setFlag: function (key, val) {
                data.flags[key] = val != null ? val : true;
                return api;
            },
            getFlag: function (key) {
                return !!data.flags[key];
            },
            setKeys: function (n) { data.keys = n; return api; },
            addKeys: function (n) { data.keys = (data.keys || 0) + (n || 1); return api; },
            getKeys: function () { return data.keys || 0; },
            setRupees: function (n) { data.rupees = n; return api; },
            addRupees: function (n) { data.rupees = (data.rupees || 0) + (n || 1); return api; },
            getRupees: function () { return data.rupees || 0; },

            // ---- Position / room ----
            setRoom: function (room) {
                data.lastRoom = room || data.lastRoom;
                return api;
            },
            setPos: function (x, y, z, room) {
                data.lastPos = { x: x, y: y, z: z, room: room || data.lastRoom };
                if (room) data.lastRoom = room;
                return api;
            },
            getPos: function () { return data.lastPos; },

            /**
             * Snapshot everything before a page warp.
             * PlayerStateV3.checkpoint(inventory, { hp, maxHp, x, y, z, room, flags })
             */
            checkpoint: function (inventory, opts) {
                opts = opts || {};
                if (inventory) api.syncFromInventory(inventory);
                if (opts.hp != null) api.setHp(opts.hp, opts.maxHp);
                if (opts.x != null) api.setPos(opts.x, opts.y || 0, opts.z || 0, opts.room);
                else if (opts.room) api.setRoom(opts.room);
                if (opts.flags) {
                    Object.keys(opts.flags).forEach(function (k) {
                        data.flags[k] = opts.flags[k];
                    });
                }
                return api.save();
            }
        };
        return api;
    }

    /** Static helpers for one-liners */
    function checkpoint(inventory, opts) {
        return load().checkpoint(inventory, opts);
    }

    global.PlayerStateV3 = {
        STORAGE_KEY: STORAGE_KEY,
        load: load,
        checkpoint: checkpoint,
        defaultState: defaultState
    };
})(typeof window !== 'undefined' ? window : this);
