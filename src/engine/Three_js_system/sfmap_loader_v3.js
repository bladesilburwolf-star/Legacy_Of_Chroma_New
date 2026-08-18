/**
 * SFMapLoaderV3 — SFPAINT legacy JSON → RoomBuilderV3 (+ sprite things)
 */
(function (global) {
    /** Map old Chroma Z relative paths → Legacy_Of_Chroma asset paths */
    const ASSET_ALIASES = {
        'enemy/slime.png': 'assets/enemies/SLIME-1.png',
        'enemy/greenblob.png': 'assets/enemies/SLIME-2.png',
        'enemy/magmaslime.png': 'assets/enemies/SLIMEHARD-1.png',
        'enemy/skull.png': 'assets/enemies/NEONSKULL-1.png',
        'enemy/elecskull.png': 'assets/enemies/NEONSKULLHARD.png',
        'enemy/elecghost.png': 'assets/enemies/GHOSTHARDL.png',
        'enemy/soldier.png': 'assets/enemies/SOLDIERL-1.png',
        'enemy/hardsoldier.png': 'assets/enemies/SOLDIERHARDL.png',
        'storage/smallchestclosed.png': 'assets/chests/SMALLCHEST-1.png',
        'storage/bigchestclosed.png': 'assets/chests/BIGCHEST-1.png',
        'storage/bigchestclosed_DISP.png': 'assets/chests/BIGCHEST-1.png',
        'obstacles/jar1.png': 'assets/obstacles/jar1.png',
        'obstacles/jar2.png': 'assets/obstacles/jar2.png',
        'obstacles/box2.png': 'assets/obstacles/box2.png',
        'obstacles/bigboulder.png': 'assets/obstacles/bigboulder.png',
        'obstacles/smboulder.png': 'assets/obstacles/smboulder.png',
        'items/ladder.png': 'assets/items/ladder.png',
        'items/trpiece.png': 'assets/items/trpiece.png',
        'doors/ladder_down.png': 'assets/doors/ladder_down.png',
        'doors/bosslock.png': 'assets/doors/bosslock.png',
        'doors/doorlock.png': 'assets/textures/metal2.jpg'
    };

    function resolveAsset(path) {
        if (!path) return null;
        const p = String(path).replace(/^\.\//, '');
        if (p.indexOf('assets/') === 0) return p;
        // Prefer exact Chroma layout on disk: assets/enemy/, obstacles/, doors/, items/, storage/
        const primary = 'assets/' + p;
        // Aliases only as secondary (used on texture error in applyMap)
        return primary;
    }

    function resolveAssetWithFallback(path) {
        const primary = resolveAsset(path);
        const key = path && path.indexOf('assets/') === 0 ? path.replace(/^assets\//, '') : path;
        if (ASSET_ALIASES[key]) return ASSET_ALIASES[key];
        if (ASSET_ALIASES[path]) return ASSET_ALIASES[path];
        return primary;
    }

    
    /** Crop a region from a tileset atlas into a repeating CanvasTexture */
    function cropTileTexture(loader, atlasUrl, sx, sy, sw, sh, onReady) {
        const canvas = document.createElement('canvas');
        canvas.width = sw;
        canvas.height = sh;
        const ctx = canvas.getContext('2d');
        ctx.imageSmoothingEnabled = false;
        const tex = new THREE.CanvasTexture(canvas);
        tex.wrapS = tex.wrapT = THREE.RepeatWrapping;
        tex.magFilter = THREE.NearestFilter;
        tex.minFilter = THREE.NearestFilter;
        tex.generateMipmaps = false;

        loader.load(atlasUrl, function (imgTex) {
            const img = imgTex.image;
            if (!img) return;
            ctx.clearRect(0, 0, sw, sh);
            ctx.drawImage(img, sx, sy, sw, sh, 0, 0, sw, sh);
            tex.needsUpdate = true;
            if (onReady) onReady(tex);
        }, undefined, function () {
            console.warn('tileset atlas missing', atlasUrl);
        });
        return tex;
    }

    function isSolidCell(v) { return v === 1 || v === 2 || v === 6; }

    function load(scene, loader, mapUrl, opts) {
        opts = opts || {};
        const cellSize = opts.cellSize || 36;
        const wallHeight = opts.wallHeight || 72;
        const floorY = opts.floorY != null ? opts.floorY :
            (global.RoomBuilderV3 && RoomBuilderV3.FLOOR_Y) || 0;
        const floorIndex = opts.floorIndex || 0;
        const wallTexPath = opts.wallTexture || 'assets/tilesets/temple_green/temple_green.tiles.png';
        const floorTexPath = opts.floorTexture || 'assets/tilesets/temple_green/temple_green.tiles.png';

        return fetch(mapUrl).then(function (r) {
            if (!r.ok) throw new Error('map fetch failed ' + mapUrl + ' (' + r.status + ')');
            return r.json();
        }).then(function (data) {
            const floors = data.floors || [];
            const floor = floors[floorIndex] || floors[0] || {};
            const grid = floor.grid || data.grid;
            if (!grid) throw new Error('no grid in map');
            const rows = grid.length;
            const cols = grid[0].length;
            const worldW = cols * cellSize;
            const worldH = rows * cellSize;
            const originX = -worldW * 0.5;
            const originZ = -worldH * 0.5;

            function cellToWorld(cx, cy) {
                return {
                    x: originX + (cx + 0.5) * cellSize,
                    z: originZ + (cy + 0.5) * cellSize
                };
            }

            // Solarus temple_green atlas crops (from temple_green.dat)
            // block = wall 16x16 @ 128,272 · floor.1 = floor 16x16 @ 208,96
            const atlas = wallTexPath;
            const wallTex = cropTileTexture(loader, atlas, 128, 272, 16, 16);
            wallTex.repeat.set(8, 4);
            const floorTex = cropTileTexture(loader, atlas, 208, 96, 16, 16);
            floorTex.repeat.set(cols, rows);

            const S = RoomBuilderV3.shape;
            const shapes = [];

            // Textured floor (use material after build if RoomBuilder only does colors)
            const floorGeo = new THREE.PlaneGeometry(worldW, worldH);
            floorGeo.rotateX(-Math.PI / 2);
            const floorMesh = new THREE.Mesh(
                floorGeo,
                new THREE.MeshStandardMaterial({
                    map: floorTex,
                    roughness: 0.95,
                    metalness: 0.05,
                    color: 0xffffff
                })
            );
            floorMesh.position.y = floorY + 0.5;
            scene.add(floorMesh);

            const visited = new Uint8Array(cols * rows);
            function gi(x, y) { return y * cols + x; }
            function cellAt(x, y) {
                if (y < 0 || y >= rows || x < 0 || x >= cols) return 1;
                return grid[y][x];
            }

            const wallMat = new THREE.MeshStandardMaterial({
                map: wallTex,
                roughness: 0.9,
                metalness: 0.05,
                color: 0xcccccc
            });

            for (let y = 0; y < rows; y++) {
                for (let x = 0; x < cols; x++) {
                    if (visited[gi(x, y)]) continue;
                    if (!isSolidCell(cellAt(x, y))) continue;
                    let x2 = x;
                    while (x2 + 1 < cols && !visited[gi(x2 + 1, y)] && isSolidCell(cellAt(x2 + 1, y))) x2++;
                    let y2 = y, canGrow = true;
                    while (canGrow && y2 + 1 < rows) {
                        for (let tx = x; tx <= x2; tx++) {
                            if (visited[gi(tx, y2 + 1)] || !isSolidCell(cellAt(tx, y2 + 1))) {
                                canGrow = false; break;
                            }
                        }
                        if (canGrow) y2++;
                    }
                    for (let ty = y; ty <= y2; ty++)
                        for (let tx = x; tx <= x2; tx++) visited[gi(tx, ty)] = 1;

                    const sx = (x2 - x + 1) * cellSize;
                    const sz = (y2 - y + 1) * cellSize;
                    const cx0 = originX + x * cellSize;
                    const cz0 = originZ + y * cellSize;
                    const geo = new THREE.BoxGeometry(sx, wallHeight, sz);
                    const mesh = new THREE.Mesh(geo, wallMat);
                    mesh.position.set(cx0 + sx * 0.5, floorY + wallHeight * 0.5, cz0 + sz * 0.5);
                    scene.add(mesh);
                    shapes.push(S({
                        id: 'sf_w_' + x + '_' + y, layer: 'L2', geo: 'Cube',
                        scale: { x: sx, y: wallHeight, z: sz }, sizeZ: sz,
                        position: {
                            x: mesh.position.x,
                            y: mesh.position.y,
                            z: mesh.position.z
                        },
                        color: '#3a4a42', solid: true
                    }));
                }
            }

            // Invisible collision room (RoomBuilder solids)
            const room = RoomBuilderV3.build(scene, shapes.map(function (s) {
                // zero-opacity visual — collision only; textured meshes already drawn
                s.color = '#000000';
                s.solid = true;
                return s;
            }), {
                layerZ: { L1: -80, L2: -20, L3: 0, L4: 40, UI: 100 }
            });
            // Hide the default solid meshes from RoomBuilder (we use textured ones)
            if (room.meshes) {
                room.meshes.forEach(function (m) {
                    if (m.material) {
                        m.material.visible = false;
                        m.visible = false;
                    }
                });
            }
            // Also walk scene children added by build - mark by id prefix
            scene.traverse(function (obj) {
                if (obj.name && obj.name.indexOf('sf_w_') === 0 && obj.material && !obj.userData.keep) {
                    // leave textured; RoomBuilder may use different naming
                }
            });

            let spawn;
            const ps = floor.playerStart || data.player;
            if (ps && ps.x != null) {
                const wp = cellToWorld(Math.floor(ps.x), Math.floor(ps.y));
                spawn = { x: wp.x, y: floorY + 28, z: wp.z };
            } else {
                spawn = { x: 0, y: floorY + 28, z: worldH * 0.35 };
            }

            const things = (data.rawThings || floor.things || []).filter(function (t) {
                return t.floor == null || t.floor === floorIndex;
            });
            const placed = [];
            const billboards = [];

            things.forEach(function (t) {
                const wp = cellToWorld(Math.floor(t.x), Math.floor(t.y));
                const type = (t.type || '').toUpperCase();
                const path = resolveAsset(t.asset);
                const pathFb = resolveAssetWithFallback(t.asset);
                let h = cellSize * 0.7;
                let w = cellSize * 0.55;
                if (type === 'ENEMY' || type === 'BOSS') { h = cellSize * 0.85; w = cellSize * 0.65; }
                if (type.indexOf('CHEST') >= 0) { h = cellSize * 0.45; w = cellSize * 0.5; }
                if (type === 'JAR') { h = cellSize * 0.4; w = cellSize * 0.35; }
                if (type.indexOf('BOULDER') >= 0) { h = cellSize * 0.55; w = cellSize * 0.55; }

                const mat = new THREE.MeshBasicMaterial({
                    transparent: true,
                    side: THREE.DoubleSide,
                    depthWrite: true,
                    color: 0xffffff
                });
                if (path) {
                    mat.map = loader.load(path, function (tex) {
                        tex.magFilter = THREE.NearestFilter;
                        tex.minFilter = THREE.NearestFilter;
                        mat.needsUpdate = true;
                    }, undefined, function () {
                        if (pathFb && pathFb !== path) {
                            mat.map = loader.load(pathFb, function (tex) {
                                tex.magFilter = THREE.NearestFilter;
                                tex.minFilter = THREE.NearestFilter;
                                mat.needsUpdate = true;
                            });
                        } else {
                            mat.color.setHex(type === 'ENEMY' ? 0xcc4444 : 0x888888);
                            mat.transparent = false;
                        }
                    });
                } else {
                    mat.color.setHex(0x888888);
                    mat.transparent = false;
                }

                const mesh = new THREE.Mesh(new THREE.PlaneGeometry(w, h), mat);
                mesh.position.set(wp.x, floorY + h * 0.5, wp.z);
                mesh.userData.thing = t;
                mesh.userData.isBillboard = true;
                scene.add(mesh);
                billboards.push(mesh);
                placed.push(mesh);
            });

            return {
                room: room,
                spawn: spawn,
                solidBoxes: room.solidBoxes || [],
                cellSize: cellSize,
                w: cols,
                h: rows,
                grid: grid,
                things: things,
                placed: placed,
                billboards: billboards,
                cellToWorld: cellToWorld,
                data: data,
                resolveAsset: resolveAsset
            };
        });
    }

    global.SFMapLoaderV3 = { load: load, resolveAsset: resolveAsset, ASSET_ALIASES: ASSET_ALIASES };
})(typeof window !== 'undefined' ? window : this);
