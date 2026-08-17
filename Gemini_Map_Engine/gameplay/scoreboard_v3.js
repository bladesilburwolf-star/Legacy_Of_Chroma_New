/**
 * ScoreboardV3 -- lightweight score / lives / timer HUD helper
 * -----------------------------------------------------------
 * Keeps game stats in one place and optionally mirrors them into
 * DOM nodes. Not the old randomizer -- just scoring.
 *
 * Usage:
 *   const board = ScoreboardV3.create({
 *     scoreEl: '#score',
 *     livesEl: '#lives',
 *     highScoreKey: 'neondromeda_hi'
 *   });
 *   board.addScore(100);
 *   board.loseLife();
 *   board.reset();
 */
(function (global) {
    function $(sel) {
        if (!sel) return null;
        if (typeof sel === 'string') return document.querySelector(sel);
        return sel;
    }

    function create(options) {
        options = options || {};
        const state = {
            score: options.score || 0,
            lives: options.lives != null ? options.lives : 3,
            maxLives: options.maxLives != null ? options.maxLives : 3,
            combo: 0,
            time: 0,
            highScore: 0
        };

        const key = options.highScoreKey || null;
        if (key) {
            try {
                state.highScore = parseInt(localStorage.getItem(key) || '0', 10) || 0;
            } catch (e) { /* private mode */ }
        }

        const els = {
            score: $(options.scoreEl),
            lives: $(options.livesEl),
            combo: $(options.comboEl),
            time: $(options.timeEl),
            high: $(options.highScoreEl)
        };

        function paint() {
            if (els.score) els.score.textContent = String(state.score);
            if (els.lives) els.lives.textContent = String(state.lives);
            if (els.combo) els.combo.textContent = String(state.combo);
            if (els.time) {
                const s = Math.floor(state.time);
                const m = (s / 60) | 0;
                const r = s % 60;
                els.time.textContent = m + ':' + (r < 10 ? '0' : '') + r;
            }
            if (els.high) els.high.textContent = String(state.highScore);
            if (options.onUpdate) options.onUpdate(state);
        }

        function saveHigh() {
            if (state.score > state.highScore) {
                state.highScore = state.score;
                if (key) {
                    try { localStorage.setItem(key, String(state.highScore)); } catch (e) {}
                }
            }
        }

        const api = {
            get state() { return state; },
            getScore: function () { return state.score; },
            getLives: function () { return state.lives; },
            getHighScore: function () { return state.highScore; },

            addScore: function (n) {
                state.score += n | 0;
                if (n > 0) state.combo += 1;
                saveHigh();
                paint();
                return state.score;
            },

            setScore: function (n) {
                state.score = n | 0;
                saveHigh();
                paint();
            },

            resetCombo: function () {
                state.combo = 0;
                paint();
            },

            loseLife: function () {
                state.lives = Math.max(0, state.lives - 1);
                state.combo = 0;
                paint();
                return state.lives;
            },

            addLife: function () {
                state.lives = Math.min(state.maxLives, state.lives + 1);
                paint();
            },

            setLives: function (n) {
                state.lives = Math.max(0, Math.min(state.maxLives, n | 0));
                paint();
            },

            /** Call each frame with dt seconds */
            tick: function (dt) {
                state.time += dt || 0;
                if (els.time) paint();
            },

            reset: function (opts) {
                opts = opts || {};
                state.score = opts.score || 0;
                state.lives = opts.lives != null ? opts.lives : state.maxLives;
                state.combo = 0;
                state.time = 0;
                paint();
            },

            refresh: paint
        };

        paint();
        return api;
    }

    global.ScoreboardV3 = { create: create };
})(typeof window !== 'undefined' ? window : this);
