// Pseudo-code for dynamically spawning 2D text planes in 3D
function create3DTextPlane(x, y, z, width, height, text, isBillboard) {
    // 1. Create off-screen HTML5 Canvas / Texture
    const canvas = document.createElement('canvas');
    canvas.width = 512;
    canvas.height = 256;
    const ctx = canvas.getContext('2d');

    // Draw retro box background & text onto canvas
    ctx.fillStyle = '#000000';
    ctx.fillRect(0, 0, canvas.width, canvas.height);
    ctx.strokeStyle = '#00FF66';
    ctx.lineWidth = 8;
    ctx.strokeRect(4, 4, canvas.width - 8, canvas.height - 8);

    ctx.fillStyle = '#00FF66';
    ctx.font = '24px monospace';
    ctx.textAlign = 'center';
    
    const lines = text.split('\n');
    lines.forEach((line, index) => {
        ctx.fillText(line, canvas.width / 2, 100 + (index * 40));
    });

    // 2. Map Canvas as Texture on a 3D Quad Plane
    const texture = new EngineTexture(canvas);
    const plane = new QuadGeometry(width, height);
    plane.setTexture(texture);
    plane.setPosition(x, z, y); // Map grid coords to 3D space

    if (isBillboard) {
        plane.enableCameraFacing(); // Keeps text facing player
    }

    worldScene.add(plane);
}