/**
 * ClockSystemV3 -- generic day/night HUD clock (no sprite required)
 * ----------------------------------------------------------------
 * Canvas-drawn digital clock + optional phase label (DAWN/DAY/DUSK/NIGHT).
 * Designed to pair with SkyboxSystemV3 hour values (0..24).
 *
 * Usage:
 *   const clock = ClockSystemV3.create({
 *     parent: document.body,
 *     x: 20, y: 20
 *   });
 *   // every frame or when hour changes:
 *   clock.setHour(sky.getHour());
 */
(function (global) {
    function pad(n) {
        n = Math.floor(n);
        return (n < 10 ? '0' : '') + n;
    }

    function phaseName(hour) {
        if (hour >= 5 && hour < 7) return 'DAWN';
        if (hour >= 7 && hour < 17) return 'DAY';
        if (hour >= 17 && hour < 19) return 'DUSK';
        return 'NIGHT';
    }

    function phaseColor(phase) {
        if (phase === 'DAWN') return '#ffaa66';
        if (phase === 'DAY') return '#00ff66';
        if (phase === 'DUSK') return '#ff6688';
        return '#6688ff';
    }

    function create(options) {
        options = options || {};
        const wrap = document.createElement('div');
        wrap.id = options.id || 'clock-v3';
        wrap.style.cssText = [
            'position:absolute',
            'z-index:25',
            'pointer-events:none',
            'font-family:monospace',
            'text-shadow:0 0 6px currentColor',
            'background:rgba(0,0,0,0.65)',
            'border:2px solid #00ff66',
            'padding:6px 12px',
            'line-height:1.35',
            'top:' + (options.y != null ? options.y : 40) + 'px',
            'right:' + (options.x != null ? options.x : 35) + 'px'
        ].join(';');

        const timeEl = document.createElement('div');
        timeEl.style.cssText = 'font-size:16px;letter-spacing:0.12em;color:#00ff66';
        const phaseEl = document.createElement('div');
        phaseEl.style.cssText = 'font-size:11px;letter-spacing:0.15em;margin-top:2px';
        wrap.appendChild(timeEl);
        wrap.appendChild(phaseEl);

        const parent = options.parent || document.body;
        parent.appendChild(wrap);

        let hour = options.hour != null ? options.hour : 12;

        function paint() {
            const h = Math.floor(hour) % 24;
            const m = Math.floor((hour % 1) * 60);
            timeEl.textContent = pad(h) + ':' + pad(m);
            const phase = phaseName(hour);
            phaseEl.textContent = phase;
            phaseEl.style.color = phaseColor(phase);
            wrap.style.borderColor = phaseColor(phase);
            timeEl.style.color = phaseColor(phase);
        }

        paint();

        return {
            el: wrap,
            setHour: function (h) {
                hour = ((h % 24) + 24) % 24;
                paint();
            },
            getHour: function () { return hour; },
            getPhase: function () { return phaseName(hour); },
            isNight: function () {
                const p = phaseName(hour);
                return p === 'NIGHT' || p === 'DUSK';
            },
            dispose: function () {
                if (wrap.parentNode) wrap.parentNode.removeChild(wrap);
            }
        };
    }

    global.ClockSystemV3 = {
        create: create,
        phaseName: phaseName
    };
})(typeof window !== 'undefined' ? window : this);
