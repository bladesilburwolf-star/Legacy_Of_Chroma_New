/**
 * CameraControlsV3 -- orbit camera
 * --------------------------------
 * Arrow keys: LEFT/RIGHT yaw, UP/DOWN zoom.
 * Optional drag still works if enableDrag is true.
 *
 *   const camCtrl = CameraControlsV3.create(camera, renderer.domElement, {
 *     distance: 200, height: 95, minDistance: 100, maxDistance: 420
 *   });
 *   camCtrl.update(player.mesh.position);
 */
(function (global) {
    function create(camera, domElement, options) {
        options = options || {};

        const state = {
            yaw: options.yaw || 0,
            distance: options.distance || 170,
            height: options.height || 90,
            minDistance: options.minDistance || 90,
            maxDistance: options.maxDistance || 320,
            dragging: false,
            lastX: 0
        };

        const YAW_SPEED = options.yawSpeed != null ? options.yawSpeed : 0.005;
        const KEY_YAW = options.keyYawSpeed != null ? options.keyYawSpeed : 0.045;
        const KEY_ZOOM = options.keyZoomSpeed != null ? options.keyZoomSpeed : 4.5;
        const ZOOM_SPEED = options.zoomSpeed != null ? options.zoomSpeed : 0.08;
        const enableDrag = options.enableDrag === true; // default off — arrows primary

        const keys = { left: false, right: false, up: false, down: false };

        function onKeyDown(e) {
            if (e.code === 'ArrowLeft') { keys.left = true; e.preventDefault(); }
            if (e.code === 'ArrowRight') { keys.right = true; e.preventDefault(); }
            if (e.code === 'ArrowUp') { keys.up = true; e.preventDefault(); }
            if (e.code === 'ArrowDown') { keys.down = true; e.preventDefault(); }
        }
        function onKeyUp(e) {
            if (e.code === 'ArrowLeft') keys.left = false;
            if (e.code === 'ArrowRight') keys.right = false;
            if (e.code === 'ArrowUp') keys.up = false;
            if (e.code === 'ArrowDown') keys.down = false;
        }
        window.addEventListener('keydown', onKeyDown);
        window.addEventListener('keyup', onKeyUp);

        function onPointerDown(e) {
            if (!enableDrag) return;
            state.dragging = true;
            state.lastX = e.clientX;
        }
        function onPointerMove(e) {
            if (!enableDrag || !state.dragging) return;
            const dx = e.clientX - state.lastX;
            state.lastX = e.clientX;
            state.yaw -= dx * YAW_SPEED;
        }
        function onPointerUp() { state.dragging = false; }
        function onWheel(e) {
            e.preventDefault();
            state.distance += e.deltaY * ZOOM_SPEED;
            if (state.distance < state.minDistance) state.distance = state.minDistance;
            if (state.distance > state.maxDistance) state.distance = state.maxDistance;
        }

        if (enableDrag) {
            domElement.addEventListener('pointerdown', onPointerDown);
            window.addEventListener('pointermove', onPointerMove);
            window.addEventListener('pointerup', onPointerUp);
        }
        domElement.addEventListener('wheel', onWheel, { passive: false });

        return {
            state: state,
            update: function (targetPos) {
                if (keys.left) state.yaw += KEY_YAW;
                if (keys.right) state.yaw -= KEY_YAW;
                if (keys.up) {
                    state.distance -= KEY_ZOOM;
                    if (state.distance < state.minDistance) state.distance = state.minDistance;
                }
                if (keys.down) {
                    state.distance += KEY_ZOOM;
                    if (state.distance > state.maxDistance) state.distance = state.maxDistance;
                }
                const x = targetPos.x + Math.sin(state.yaw) * state.distance;
                const z = targetPos.z + Math.cos(state.yaw) * state.distance;
                camera.position.set(x, targetPos.y + state.height, z);
                camera.lookAt(targetPos.x, targetPos.y + 15, targetPos.z);
            },
            dispose: function () {
                window.removeEventListener('keydown', onKeyDown);
                window.removeEventListener('keyup', onKeyUp);
                if (enableDrag) {
                    domElement.removeEventListener('pointerdown', onPointerDown);
                    window.removeEventListener('pointermove', onPointerMove);
                    window.removeEventListener('pointerup', onPointerUp);
                }
                domElement.removeEventListener('wheel', onWheel);
            }
        };
    }

    global.CameraControlsV3 = { create: create };
})(typeof window !== 'undefined' ? window : this);
