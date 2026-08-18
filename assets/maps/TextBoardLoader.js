// Inside your map file reader/parser loop
if (line.startsWith("BEGIN_TEXTBOARDS")) {
    inTextboardsBlock = true;
    continue;
}
if (line.startsWith("END_TEXTBOARDS")) {
    inTextboardsBlock = false;
    continue;
}

if (inTextboardsBlock && line.trim() && !line.startsWith("#")) {
    // Parse: x, y, z, width, height, billboard_mode, text
    const matches = line.match(/^([\d.]+)\s+([\d.]+)\s+([\d.]+)\s+([\d.]+)\s+([\d.]+)\s+(true|false)\s+"([^"]+)"$/);
    if (matches) {
        const [_, x, y, z, w, h, billboard, text] = matches;
        create3DTextPlane(
            parseFloat(x), parseFloat(y), parseFloat(z),
            parseFloat(w), parseFloat(h),
            text.replace(/\\n/g, '\n'),
            billboard === 'true'
        );
    }
}