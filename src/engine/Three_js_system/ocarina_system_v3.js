/**
 * OcarinaSystemV3 — whistle / music note song list
 * ------------------------------------------------
 * Zelda-style song menu. First song: Sun's Song (toggle day ↔ night).
 *
 *   const ocarina = OcarinaSystemV3.create({
 *     getHour: () => sky.getHour(),
 *     setHour: (h) => { sky.setHour(h); clock.setHour(h); },
 *     isNight: () => clock.isNight(),
 *     hasItem: () => inventory.has('musicnote'),
 *     onSong: (id, info) => {},
 *     playSfx: (url) => audio.sfx(url)
 *   });
 *
 *   // O key opens menu (if player has musicnote)
 *   ocarina.open();
 *   ocarina.update(dt); // optional for note playback timers
 */
(function (global) {
    const SONGS = [
        {
            id: 'suns_song',
            name: "Sun's Song",
            notes: '→ ↓ ↑ → ↓ ↑',
            hint: 'Turn night to day, or day to night.',
            unlocked: true
        },
        {
            id: 'zeldas_lullaby',
            name: "Zelda's Lullaby",
            notes: '← ↑ → ← ↑ →',
            hint: '??? (locked)',
            unlocked: false
        },
        {
            id: 'song_of_storms',
            name: 'Song of Storms',
            notes: 'A ↓ ↑ A ↓ ↑',
            hint: '??? (locked)',
            unlocked: false
        },
        {
            id: 'eponas_song',
            name: "Epona's Song",
            notes: '↑ ← → ↑ ← →',
            hint: '??? (locked)',
            unlocked: false
        }
    ];

    function playToneSequence(freqs, step) {
        step = step != null ? step : 0.16;
        try {
            const ctx = new (global.AudioContext || global.webkitAudioContext)();
            freqs.forEach(function (freq, i) {
                const o = ctx.createOscillator();
                const g = ctx.createGain();
                o.type = 'triangle';
                o.frequency.value = freq;
                g.gain.setValueAtTime(0.0001, ctx.currentTime + i * step);
                g.gain.exponentialRampToValueAtTime(0.1, ctx.currentTime + i * step + 0.02);
                g.gain.exponentialRampToValueAtTime(0.0001, ctx.currentTime + i * step + step * 0.9);
                o.connect(g);
                g.connect(ctx.destination);
                o.start(ctx.currentTime + i * step);
                o.stop(ctx.currentTime + i * step + step);
            });
        } catch (e) {}
    }

    function create(options) {
        options = options || {};
        let open = false;
        let selected = 0;
        let cooldown = 0;

        // DOM panel
        const panel = document.createElement('div');
        panel.id = 'ocarina-panel';
        panel.style.cssText = [
            'display:none', 'position:fixed', 'left:50%', 'top:50%',
            'transform:translate(-50%,-50%)', 'z-index:80',
            'background:rgba(8,4,20,0.94)', 'border:2px solid #c8a0ff',
            'padding:14px 18px', 'min-width:320px', 'max-width:420px',
            'font-family:monospace', 'color:#e8d0ff',
            'box-shadow:0 0 24px rgba(150,80,220,0.45)'
        ].join(';');
        panel.innerHTML =
            '<div style="letter-spacing:0.12em;color:#f0d0ff;margin-bottom:8px;">♪ OCARINA — SONG LIST</div>' +
            '<div id="ocarina-list"></div>' +
            '<div id="ocarina-hint" style="margin-top:10px;font-size:11px;color:#a090c0;border-top:1px solid #503080;padding-top:8px;"></div>' +
            '<div style="margin-top:8px;font-size:10px;color:#7060a0;">↑↓ select · Enter / Space play · Esc close</div>';
        document.body.appendChild(panel);
        const listEl = panel.querySelector('#ocarina-list');
        const hintEl = panel.querySelector('#ocarina-hint');

        function unlockedSongs() {
            return SONGS.filter(function (s) {
                return s.unlocked || (options.isUnlocked && options.isUnlocked(s.id));
            });
        }

        function paint() {
            const songs = SONGS;
            listEl.innerHTML = '';
            songs.forEach(function (s, i) {
                const row = document.createElement('div');
                const active = i === selected;
                const lock = !s.unlocked && !(options.isUnlocked && options.isUnlocked(s.id));
                row.style.cssText = 'padding:6px 8px;margin:2px 0;cursor:pointer;' +
                    (active ? 'background:rgba(120,60,200,0.45);border:1px solid #c080ff;' : 'border:1px solid transparent;') +
                    (lock ? 'opacity:0.45;' : '');
                row.textContent = (active ? '▸ ' : '  ') + s.name + (lock ? ' 🔒' : '') +
                    (s.notes ? '   [' + s.notes + ']' : '');
                row.addEventListener('click', function () {
                    selected = i;
                    paint();
                    if (!lock) playSelected();
                });
                listEl.appendChild(row);
            });
            const cur = songs[selected];
            hintEl.textContent = cur ? cur.hint : '';
        }

        function playSelected() {
            if (cooldown > 0) return;
            const song = SONGS[selected];
            if (!song) return;
            if (!song.unlocked && !(options.isUnlocked && options.isUnlocked(song.id))) {
                if (options.onMessage) options.onMessage('You have not learned this song yet.');
                return;
            }
            cooldown = 1.2;
            executeSong(song.id);
        }

        function executeSong(id) {
            if (options.playSfx) {
                try { options.playSfx('assets/fanfare/whistle.wav'); } catch (e) {}
            }

            if (id === 'suns_song') {
                // Classic: night → morning, day → night
                playToneSequence([392, 523.25, 659.25, 392, 523.25, 659.25], 0.14);
                let hour = options.getHour ? options.getHour() : 12;
                const night = options.isNight ? options.isNight() : (hour >= 19 || hour < 5);
                const target = night ? 8.0 : 22.0; // morning vs deep night
                if (options.setHour) options.setHour(target);
                const msg = night
                    ? "Sun's Song — the sun rises!"
                    : "Sun's Song — night falls!";
                if (options.onMessage) options.onMessage(msg);
                if (options.onSong) options.onSong(id, { fromNight: night, hour: target });
                return;
            }

            // Locked / future songs — just a soft arpeggio
            playToneSequence([440, 494, 523], 0.15);
            if (options.onMessage) options.onMessage(SONGS.find(function (s) { return s.id === id; }).name + ' (not yet taught)');
            if (options.onSong) options.onSong(id, {});
        }

        const api = {
            SONGS: SONGS,
            isOpen: function () { return open; },
            open: function () {
                if (options.hasItem && !options.hasItem()) {
                    if (options.onMessage) options.onMessage('You need the Ocarina (Music Note).');
                    return false;
                }
                open = true;
                panel.style.display = 'block';
                paint();
                return true;
            },
            close: function () {
                open = false;
                panel.style.display = 'none';
            },
            toggle: function () {
                if (open) api.close();
                else api.open();
            },
            play: function (songId) {
                executeSong(songId || 'suns_song');
            },
            unlock: function (songId) {
                const s = SONGS.find(function (x) { return x.id === songId; });
                if (s) s.unlocked = true;
                if (open) paint();
            },
            handleKey: function (e) {
                if (!open) return false;
                if (e.code === 'Escape') { api.close(); return true; }
                if (e.code === 'ArrowUp' || e.code === 'KeyW') {
                    selected = (selected - 1 + SONGS.length) % SONGS.length;
                    paint();
                    return true;
                }
                if (e.code === 'ArrowDown' || e.code === 'KeyS') {
                    selected = (selected + 1) % SONGS.length;
                    paint();
                    return true;
                }
                if (e.code === 'Enter' || e.code === 'Space') {
                    playSelected();
                    return true;
                }
                return false;
            },
            update: function (dt) {
                if (cooldown > 0) cooldown -= dt || 0.016;
            }
        };
        return api;
    }

    global.OcarinaSystemV3 = { create: create, SONGS: SONGS };
})(typeof window !== 'undefined' ? window : this);
