/**
 * InventorySystemV3 -- items, ocarina, lantern, keys, equipment
 * -------------------------------------------------------------
 * Zelda-style inventory with:
 *   - collected items map
 *   - equipped slot (sword / lantern / ocarina / bow …)
 *   - lantern light toggle
 *   - ocarina "song" trigger (callback — real audio later)
 *   - optional DOM strip for icons
 *
 * Item ids match asset names under assets/inventory/ where possible.
 *
 * Usage:
 *   const inv = InventorySystemV3.create({
 *     onEquip: (id) => {},
 *     onUse: (id) => {},
 *     onSong: (songId) => {},
 *     lanternLight: pointLight   // THREE.PointLight parented to player
 *   });
 *   inv.give('lantern');
 *   inv.give('musicnote');
 *   inv.equip('lantern');
 *   inv.use();                  // toggle lantern / play note
 */
(function (global) {
    const CATALOG = {
        woodsword:  { name: 'Wood Sword',  icon: 'assets/inventory/WOODSWORD-1.png',  slot: 'weapon', damage: 1 },
        metalsword: { name: 'Metal Sword', icon: 'assets/inventory/METALSWORD.png', slot: 'weapon' },
        plasmasword:{ name: 'Plasma Sword',icon: 'assets/inventory/PLASMASWORD.png',  slot: 'weapon', damage: 3 },
        lantern:    { name: 'Lantern',     icon: 'assets/inventory/LANTERN-1.png',    slot: 'tool', usable: true },
        musicnote:  { name: 'Ocarina',     icon: 'assets/inventory/MUSICNOTE-1.png',  slot: 'tool', usable: true },
        bow:        { name: 'Bow',         icon: 'assets/inventory/BOW UP.png',       slot: 'weapon', damage: 1 },
        bombs:      { name: 'Bombs',       icon: 'assets/inventory/BOMBS.png',        slot: 'tool', usable: true, stack: true },
        smallkey:   { name: 'Small Key',   icon: 'assets/inventory/SMALL KEY.png',    slot: 'key', stack: true },
        bigkey:     { name: 'Big Key',     icon: 'assets/inventory/BIG KEY.png',      slot: 'key' },
        chromackey: { name: 'Chromac Key', icon: 'assets/inventory/CHROMAC KEY.png',  slot: 'key' },
        raft:       { name: 'Raft',        icon: 'assets/inventory/RAFT-1.png',       slot: 'tool' },
        ladder:     { name: 'Ladder',      icon: 'assets/inventory/LADDER-1.png',     slot: 'tool' },
        gauntlet:   { name: 'Gauntlet',    icon: 'assets/inventory/GAUNTLET-1.png',   slot: 'tool' },
        heart:      { name: 'Heart',       icon: 'assets/hud/1HEART.png',             slot: 'consumable', usable: true }
    };

    function create(options) {
        options = options || {};
        const items = Object.create(null); // id -> count
        let equipped = null;
        let lanternOn = false;
        const lanternLight = options.lanternLight || null;

        // Optional HUD strip
        let strip = null;
        if (options.hud !== false) {
            strip = document.createElement('div');
            strip.id = 'inv-strip-v3';
            strip.style.cssText = [
                'position:absolute',
                'bottom:48px',
                'left:50%',
                'transform:translateX(-50%)',
                'z-index:25',
                'display:flex',
                'gap:6px',
                'pointer-events:none',
                'padding:6px 10px',
                'background:rgba(0,0,0,0.55)',
                'border:2px solid #00ff66'
            ].join(';');
            (options.parent || document.body).appendChild(strip);
        }

        function paint() {
            if (!strip) return;
            strip.innerHTML = '';
            Object.keys(items).forEach(function (id) {
                if (!items[id]) return;
                const def = CATALOG[id] || { name: id, icon: null };
                const cell = document.createElement('div');
                cell.style.cssText = 'position:relative;width:40px;height:40px;border:2px solid ' +
                    (equipped === id ? '#ffff00' : '#335533') + ';background:#111;';
                if (def.icon) {
                    const img = document.createElement('img');
                    img.src = def.icon;
                    img.style.cssText = 'width:100%;height:100%;object-fit:contain;image-rendering:pixelated;';
                    cell.appendChild(img);
                }
                if (items[id] > 1) {
                    const n = document.createElement('span');
                    n.textContent = String(items[id]);
                    n.style.cssText = 'position:absolute;right:2px;bottom:0;color:#fff;font:10px monospace;';
                    cell.appendChild(n);
                }
                strip.appendChild(cell);
            });
        }

        function setLantern(on) {
            lanternOn = !!on;
            if (lanternLight) {
                lanternLight.visible = lanternOn;
                lanternLight.intensity = lanternOn ? (options.lanternIntensity || 1.2) : 0;
            }
            if (options.onLantern) options.onLantern(lanternOn);
        }

        const api = {
            CATALOG: CATALOG,

            give: function (id, count) {
                count = count != null ? count : 1;
                id = id.toLowerCase().replace(/\s+/g, '');
                // normalize aliases
                if (id === 'ocarina' || id === 'note') id = 'musicnote';
                if (id === 'sword' || id === 'wood') id = 'woodsword';
                items[id] = (items[id] || 0) + count;
                paint();
                if (options.onGive) options.onGive(id, items[id]);
                return items[id];
            },

            take: function (id, count) {
                count = count != null ? count : 1;
                id = id.toLowerCase();
                if (!items[id]) return false;
                items[id] -= count;
                if (items[id] <= 0) {
                    delete items[id];
                    if (equipped === id) equipped = null;
                }
                paint();
                return true;
            },

            has: function (id) {
                id = id.toLowerCase();
                if (id === 'ocarina') id = 'musicnote';
                return (items[id] || 0) > 0;
            },

            count: function (id) {
                return items[id.toLowerCase()] || 0;
            },

            list: function () {
                return Object.keys(items).filter(function (k) { return items[k] > 0; });
            },

            equip: function (id) {
                id = id.toLowerCase();
                if (id === 'ocarina') id = 'musicnote';
                if (!items[id]) return false;
                equipped = id;
                paint();
                if (options.onEquip) options.onEquip(id);
                return true;
            },

            getEquipped: function () { return equipped; },

            /**
             * Use equipped item (or explicit id)
             * Lantern toggles light; ocarina fires onSong; bombs/hearts consume
             */
            use: function (id) {
                id = (id || equipped || '').toLowerCase();
                if (id === 'ocarina') id = 'musicnote';
                if (!id || !items[id]) return false;

                if (id === 'lantern') {
                    setLantern(!lanternOn);
                    if (options.onUse) options.onUse(id, { lanternOn: lanternOn });
                    return true;
                }
                if (id === 'musicnote') {
                    const songId = options.defaultSong || 'zelda';
                    if (options.onSong) options.onSong(songId);
                    if (options.onUse) options.onUse(id, { song: songId });
                    return true;
                }
                if (id === 'heart') {
                    api.take('heart', 1);
                    if (options.onUse) options.onUse(id, { heal: 1 });
                    return true;
                }
                if (id === 'bombs') {
                    if (api.count('bombs') <= 0) return false;
                    api.take('bombs', 1);
                    if (options.onUse) options.onUse(id, {});
                    return true;
                }
                if (options.onUse) options.onUse(id, {});
                return true;
            },

            isLanternOn: function () { return lanternOn; },
            setLantern: setLantern,

            /** Serialize for save */
            toJSON: function () {
                return { items: Object.assign({}, items), equipped: equipped };
            },
            fromJSON: function (data) {
                Object.keys(items).forEach(function (k) { delete items[k]; });
                if (data && data.items) Object.assign(items, data.items);
                equipped = data && data.equipped || null;
                paint();
            },

            refresh: paint
        };

        // Starting kit optional
        if (options.starter) {
            options.starter.forEach(function (id) { api.give(id); });
        }

        paint();
        return api;
    }

    function weaponDamage(id) {
        const c = CATALOG[id];
        return (c && c.damage) || (id ? 1 : 0);
    }

    global.InventorySystemV3 = {
        create: create,
        CATALOG: CATALOG,
        weaponDamage: weaponDamage
    };
})(typeof window !== 'undefined' ? window : this);
