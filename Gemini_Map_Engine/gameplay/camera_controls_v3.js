/**
 * CameraControlsV3 -- lightweight orbit camera, no external dependency.
 * -------------------------------------------------------------------
 * Deliberately NOT THREE.OrbitControls (that's a separate script include
 * with a bigger feature surface than this needs -- full 6DOF orbit,
 * damping, touch gestures, etc). This is the minimum for a 2.5D chase
 * cam that the player can look around with: click-drag to orbit
 * horizontally around the target, scroll to zoom, clamped distance.
 *
 * Usage:
 *   const camCtrl = CameraControlsV3.create(camera, renderer.domElement, {
 *     distance: 170, height: 90, minDistance: 90, maxDistance: 320
 *   });
 *
 *   // every frame, after you know where the player is:
 *   camCtrl.update(player.mesh.position);
 */
(function (global) {
    function create(camera, domElement, options) {
        options = options || {};

        const state = {
            yaw: options.yaw || 0,           // radians, 0 = camera behind player looking -Z
            distance: options.distance || 170,
            height: options.height || 90,
            minDistance: options.minDistance || 90,
            maxDistance: options.maxDistance || 320,
            dragging: false,
            lastX: 0
        };

        const YAW_SPEED = options.yawSpeed || 0.006; // radians per pixel dragged
        const ZOOM_SPEED = options.zoomSpeed || 0.15;

        function onPointerDown(e) {
            state.dragging = true;
            state.lastX = e.clientX;
        }
        function onPointerMove(e) {
            if (!state.dragging) return;
            const dx = e.clientX - state.lastX;
            state.lastX = e.clientX;
            state.yaw -= dx * YAW_SPEED;
        }
        function onPointerUp() {
            state.dragging = false;
        }
        function onWheel(e) {
            e.preventDefault();
            state.distance += e.deltaY * ZOOM_SPEED;
            if (state.distance < state.minDistance) state.distance = state.minDistance;
            if (state.distance > state.maxDistance) state.distance = state.maxDistance;
        }

        domElement.addEventListener('pointerdown', onPointerDown);
        window.addEventListener('pointermove', onPointerMove);
        window.addEventListener('pointerup', onPointerUp);
        domElement.addEventListener('wheel', onWheel, { passive: false });

        return {
            state: state,

            /**
             * Call once per frame with the target position (usually the
             * player's mesh.position). Positions and aims the camera.
             */
            update: function (targetPos) {
                const x = targetPos.x + Math.sin(state.yaw) * state.distance;
                const z = targetPos.z + Math.cos(state.yaw) * state.distance;
                camera.position.set(x, targetPos.y + state.height, z);
                camera.lookAt(targetPos.x, targetPos.y + 15, targetPos.z);
            },

            /**
             * Remove all listeners -- call if you tear down the scene
             * without a full page reload (SPA-style room switching).
             */
            dispose: function () {
                domElement.removeEventListener('pointerdown', onPointerDown);
                window.removeEventListener('pointermove', onPointerMove);
                window.removeEventListener('pointerup', onPointerUp);
                domElement.removeEventListener('wheel', onWheel);
            }
        };
    }

    global.CameraControlsV3 = { create: create };
})(window);
