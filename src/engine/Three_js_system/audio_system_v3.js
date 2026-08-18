/**
 * AudioSystemV3 — simple BGM + SFX helper (HTMLAudioElement)
 */
(function (global) {
    function create(options) {
        options = options || {};
        let music = null;
        let currentTrack = '';
        const sfxCache = Object.create(null);
        const musicVol = options.musicVolume != null ? options.musicVolume : 0.4;
        const sfxVol = options.sfxVolume != null ? options.sfxVolume : 0.5;

        const api = {
            playMusic: function (url, opts) {
                opts = opts || {};
                if (!url) return;
                if (currentTrack === url && music && !music.paused) return;
                api.stopMusic();
                try {
                    music = new Audio(url);
                    music.loop = opts.loop !== false;
                    music.volume = opts.volume != null ? opts.volume : musicVol;
                    currentTrack = url;
                    const p = music.play();
                    if (p && p.catch) p.catch(function () {});
                } catch (e) {
                    console.warn('AudioSystemV3 music', e);
                }
            },
            stopMusic: function () {
                if (music) {
                    try { music.pause(); music.src = ''; } catch (e) {}
                }
                music = null;
                currentTrack = '';
            },
            sfx: function (url, opts) {
                opts = opts || {};
                if (!url) return;
                try {
                    let a = sfxCache[url];
                    if (!a || opts.noCache) {
                        a = new Audio(url);
                        if (!opts.noCache) sfxCache[url] = a;
                    }
                    a.volume = opts.volume != null ? opts.volume : sfxVol;
                    a.currentTime = 0;
                    const p = a.play();
                    if (p && p.catch) p.catch(function () {});
                } catch (e) {}
            },
            unlock: function () {
                if (music && music.paused && currentTrack) {
                    music.play().catch(function () {});
                }
            },
            get currentTrack() { return currentTrack; }
        };
        return api;
    }

    global.AudioSystemV3 = { create: create };
})(typeof window !== 'undefined' ? window : this);
