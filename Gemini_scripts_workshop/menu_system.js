// Build UI overlays dynamically into DOM
(function initUIOverlays() {
    const overlaysHTML = `
        <!-- Pause Screen -->
        <div id="pause-overlay" class="game-overlay">
            <div class="pause-menu-content">
                <div style="font-size: 20px; color: #00ffff; text-shadow: 0 0 8px #00ffff; margin-bottom: 20px;">[ SYSTEM PAUSED ]</div>
                <button class="pause-btn" onclick="togglePause()">RESUME</button>
                <button class="pause-btn" onclick="openInventory()">INVENTORY (I)</button>
                <button class="pause-btn" onclick="openTrophies()">TROPHIES (T)</button>
                <button class="pause-btn" onclick="window.location.href='title.html'">QUIT TO TITLE</button>
            </div>
        </div>

        <!-- Inventory Screen -->
        <div id="inventory-overlay" class="game-overlay">
            <div class="inventory-grid" id="inv-grid-container"></div>
            <button class="pause-btn" style="position: absolute; bottom: 20px; right: 70px; width: 120px;" onclick="closeAllOverlays()">CLOSE</button>
        </div>

        <!-- Trophies Screen -->
        <div id="trophies-overlay" class="game-overlay">
            <div class="trophies-list" id="trophies-container">
                <div class="trophy-row"><span>01. SYSTEM BOOT</span><span>[ COMPLETED ]</span></div>
                <div class="trophy-row"><span>02. ARCADE EXPLORER</span><span>[ IN PROGRESS ]</span></div>
                <div class="trophy-row"><span>03. FACTORY BREAK-IN</span><span>[ LOCKED ]</span></div>
                <div class="trophy-row"><span>04. COLOR RESTORER</span><span>[ LOCKED ]</span></div>
            </div>
            <button class="pause-btn" style="position: absolute; bottom: 20px; right: 80px; width: 120px;" onclick="closeAllOverlays()">CLOSE</button>
        </div>
    `;

    document.body.insertAdjacentHTML('beforeend', overlaysHTML);

    // Build 24 inventory grid cells (6x4 matching background layout)
    const gridContainer = document.getElementById('inv-grid-container');
    for (let i = 0; i < 24; i++) {
        const slot = document.createElement('div');
        slot.className = 'inventory-slot';
        slot.dataset.slotIndex = i;
        gridContainer.appendChild(slot);
    }
})();

// Overlay State Managers
let isPaused = false;

function closeAllOverlays() {
    document.querySelectorAll('.game-overlay').forEach(el => el.style.display = 'none');
    isPaused = false;
}

function togglePause() {
    const pauseEl = document.getElementById('pause-overlay');
    if (pauseEl.style.display === 'block') {
        closeAllOverlays();
    } else {
        closeAllOverlays();
        pauseEl.style.display = 'block';
        isPaused = true;
    }
}

function openInventory() {
    closeAllOverlays();
    document.getElementById('inventory-overlay').style.display = 'block';
    isPaused = true;
}

function openTrophies() {
    closeAllOverlays();
    document.getElementById('trophies-overlay').style.display = 'block';
    isPaused = true;
}

// Global Key Listeners for UI
window.addEventListener('keydown', (e) => {
    const key = e.key.toLowerCase();
    if (key === 'escape' || key === 'p') {
        togglePause();
    } else if (key === 'i') {
        const inv = document.getElementById('inventory-overlay');
        inv.style.display === 'block' ? closeAllOverlays() : openInventory();
    } else if (key === 't') {
        const tro = document.getElementById('trophies-overlay');
        tro.style.display === 'block' ? closeAllOverlays() : openTrophies();
    }
});
