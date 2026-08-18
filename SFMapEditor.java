import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.border.LineBorder;
import javax.swing.filechooser.FileNameExtensionFilter;
import java.awt.*;
import java.awt.event.*;
import java.awt.image.BufferedImage;
import java.io.*;
import java.util.List;
import java.util.*;

/**
 * SFMAPEDITOR v3.1 - BRUSH-BASED RETRO EDITION
 * ------------------------------------------------------------------------
 * Features:
 *  - Dynamic Image Brush Palette for Walls and Floors
 *  - Direct Visual Canvas Rendering (No abstract T1/F1 numbers)
 *  - GameMaker Style Tabbed Sidebar (Brushes, Entities, Shapes)
 *  - Full Undo/Redo & Map Serialization
 * ------------------------------------------------------------------------
 */
public class SFMapEditor extends JFrame {

    private int mapSize = 24;
    private int cellPx = 24;
    public enum MapType { OUTDOOR, INDOOR, SKY }
    private MapType mapType = MapType.OUTDOOR;
    private int activeFloor = 0;
    private int floorCount = 1;
    private static final int MAX_FLOORS = 8;
    /** Independent layer stacks — true multi-floor (not copies). */
    private int[][][] layerGrids = new int[MAX_FLOORS][][];
    private String[][][] layerWallTex = new String[MAX_FLOORS][][];
    private String[][][] layerFloorTex = new String[MAX_FLOORS][][];
    private int[][][] layerWallH = new int[MAX_FLOORS][][];
    private int[][][] layerGroundH = new int[MAX_FLOORS][][];
    // Per-floor player starts
    private double[] playerStartX = new double[MAX_FLOORS];
    private double[] playerStartY = new double[MAX_FLOORS];
    private double[] playerStartDirX = new double[MAX_FLOORS];
    private double[] playerStartDirY = new double[MAX_FLOORS];
    private boolean[] hasPlayerStart = new boolean[MAX_FLOORS];

    enum Theme {
        MONO_GREEN("Mono-Green", new Color(0, 255, 100), new Color(0, 20, 8), new Color(0, 40, 16)),
        AMBER("Amber", new Color(255, 176, 0), new Color(24, 16, 0), new Color(45, 30, 0)),
        CYAN("Cyan", new Color(0, 230, 255), new Color(0, 18, 26), new Color(0, 42, 55)),
        VIRTUAL_BOY("Virtual Boy", new Color(255, 30, 30), new Color(20, 0, 0), new Color(45, 0, 0));

        final String label;
        final Color fg, bg, panel;
        Theme(String label, Color fg, Color bg, Color panel) {
            this.label = label; this.fg = fg; this.bg = bg; this.panel = panel;
        }
    }
    private Theme theme = Theme.MONO_GREEN;

    static final String[] ENTITY_CATEGORIES = {
            "CHESTSM", "CHESTBG",
            "JAR", "BOX", "SMBOULDER", "BIGBOULDER", "CRACKEDWALL",
            "ENEMY", "BOSS", "STAIRS", "PIT", "TREE", "TORCH", "LIGHT",
            "BIGITEM", "DOOR", "TEXTBOARD"
    };

    static Color legendTint(String cat) {
        if (cat==null) return Color.WHITE;
        String u = cat.toUpperCase();
        if (u.contains("DOOR") || u.contains("LOCK")) return new Color(180, 120, 40);
        return switch (cat.toUpperCase()) {
            case "ENEMY" -> new Color(255, 80, 80);
            case "BOSS" -> new Color(180, 40, 40);
            case "CHESTSM" -> new Color(255, 200, 0);
            case "CHESTBG" -> new Color(210, 150, 0);
            case "JAR" -> new Color(255, 140, 0);
            case "BIGITEM" -> new Color(255, 60, 160);
            case "BOX" -> new Color(200, 60, 40);
            case "SMBOULDER" -> new Color(140, 140, 140);
            case "BIGBOULDER" -> new Color(90, 90, 90);
            case "CRACKEDWALL" -> new Color(180, 160, 120);
            case "TREE" -> new Color(40, 160, 60);
            case "TORCH", "LIGHT" -> new Color(255, 160, 40);
            case "PIT" -> new Color(50, 50, 50);
            case "STAIRS" -> new Color(180, 180, 255);
            case "TEXTBOARD" -> new Color(0, 210, 255);
            case "DOOR" -> new Color(160, 100, 30);
            default -> Color.WHITE;
        };
    }

    static String legendAbbrev(String cat) {
        if (cat==null) return "??";
        String u = cat.toUpperCase();
        if (u.contains("FINALLOCK")) return "FL";
        if (u.contains("BOSSLOCK")) return "BL";
        if (u.contains("DOORLOCK")) return "DL";
        if (u.equals("DOOR") || u.contains("DOOR")) return "DR";
        return switch (u) {
            case "CHESTSM" -> "cs"; case "CHESTBG" -> "CB"; case "ENEMY" -> "EN"; case "BOSS" -> "BO";
            case "STAIRS" -> "ST"; case "PIT" -> "PI"; case "BOX" -> "BX";
            case "JAR" -> "JR"; case "BIGITEM" -> "BI"; case "TEXTBOARD" -> "TX";
            case "SMBOULDER" -> "sb"; case "BIGBOULDER" -> "BB";
            case "CRACKEDWALL" -> "CW"; case "TREE" -> "TR"; case "TORCH", "LIGHT" -> "TO";
            default -> "??";
        };
    }

    // Active Tools & Brush State
    private String currentTool = "WALL"; 
    private String activeWallBrush = "";
    private String activeFloorBrush = "";

    // --- Solarus-style tileset paint (RPG Maker pass) ---
    static class TilePattern {
        String id;
        String ground;       // wall, traversable, deep_water, hole, ...
        int layer;
        int x, y, w, h;      // atlas crop
        BufferedImage image; // cropped tile
        int cellType;        // CHROMA grid cell
    }
    static class SolarusTileset {
        String name;
        File datFile;
        File imageFile;
        BufferedImage atlas;
        final java.util.LinkedHashMap<String, TilePattern> patterns = new java.util.LinkedHashMap<>();
    }
    private final java.util.List<SolarusTileset> loadedTilesets = new ArrayList<>();
    private SolarusTileset activeTileset = null;
    private TilePattern activeTilePattern = null;
    /** When true, painting stamps tileset tile (tool TILESET). */
    private boolean tilesetPaintMode = false;

    /** Paint stamp size in cells (1 = single cell, 3 = 3x3, ...). */
    private int brushSize = 1;
    /** FILL mode: WALL stamps walls; FLOOR stamps floor textures / open cells. */
    private String fillMode = "WALL"; // WALL | FLOOR
    /** Draw enemy patrol + light radius circles on canvas. */
    private boolean showRadiusOverlays = true;
    /** Optional reference image shown beside/below the map. */
    private BufferedImage referenceImage = null;
    private String referenceImagePath = "";
    private JLabel referenceLabel = null;
    private JPanel referencePanel = null;
    private JSplitPane mapRefSplit = null;

    private String selectedSkyboxPath = "";
    /** Map weather — saved as FOG / RAIN headers. */
    private double mapFog = 0.0;      // 0..1 distance fog strength
    private boolean mapRain = false;

    // Saved Brushes List
    private final List<String> wallBrushes = new ArrayList<>();
    private final List<String> floorBrushes = new ArrayList<>();
    // Per-category last directory memory - fixes same-dir spam
    private final Map<String, File> lastDirByCategory = new HashMap<>();
    private final Map<String, File> lastFileByCategory = new HashMap<>();

    // Selection / Shapes
    private Point shapeStartCell = null;
    private Point shapeEndCell = null;
    private boolean isDraggingShape = false;
    private int[][] clipboardGrid = null;
    private Map<Point, Entity> clipboardEntities = null;

    static class Entity {
        String category;
        double x, y;
        String text;
        String assetPath;
        boolean solid;
        String patrolMode = "NONE";
        int floorIndex = 0; // which floor this entity lives on
        double areaRadius = 2.5;
        double lightRadius = 0; // 0 = use defaults for TORCH/LIGHT
        String pathData = "";
        Entity(String category, double x, double y) {
            this.category = category; this.x = x; this.y = y;
            this.solid = defaultSolid(category);
            if (category != null && (category.equalsIgnoreCase("ENEMY") || category.equalsIgnoreCase("BOSS"))) {
                patrolMode = "AREA";
                areaRadius = 2.5;
            }
            if (category != null && category.equalsIgnoreCase("TORCH")) lightRadius = 3.0;
            if (category != null && category.equalsIgnoreCase("LIGHT")) lightRadius = 5.0;
        }
        static boolean defaultSolid(String cat) {
            if (cat == null) return false;
            String u = cat.toUpperCase();
            return u.equals("BOX") || u.equals("CHESTSM") || u.equals("CHESTBG")
                    || u.equals("STAIRS") || u.equals("ENEMY") || u.equals("BOSS")
                    || u.equals("SMBOULDER") || u.equals("BIGBOULDER") || u.equals("CRACKEDWALL")
                    || u.equals("TREE");
        }
    }

    static final Map<String, String> ENTITY_POOL_MAP = new HashMap<>();
    static {
        // true asset folders - matches C:/Chroma Source - Meta/assets/*
        ENTITY_POOL_MAP.put("CHESTSM", "storage");
        ENTITY_POOL_MAP.put("CHESTBG", "storage");
        ENTITY_POOL_MAP.put("ENEMY", "enemy");
        ENTITY_POOL_MAP.put("BOSS", "boss");
        ENTITY_POOL_MAP.put("JAR", "obstacles");
        ENTITY_POOL_MAP.put("BOX", "obstacles");
        ENTITY_POOL_MAP.put("SMBOULDER", "obstacles");
        ENTITY_POOL_MAP.put("BIGBOULDER", "obstacles");
        ENTITY_POOL_MAP.put("CRACKEDWALL", "obstacles");
        ENTITY_POOL_MAP.put("TREE", "obstacles");
        ENTITY_POOL_MAP.put("TORCH", "obstacles");
        ENTITY_POOL_MAP.put("LIGHT", "obstacles");
        ENTITY_POOL_MAP.put("BIGITEM", "items");
        ENTITY_POOL_MAP.put("PIT", "obstacles");
        ENTITY_POOL_MAP.put("STAIRS", "obstacles");
        ENTITY_POOL_MAP.put("DOOR", "doors");
        ENTITY_POOL_MAP.put("DOORLOCK", "doors");
        ENTITY_POOL_MAP.put("BOSSLOCK", "doors");
        ENTITY_POOL_MAP.put("FINALLOCK", "doors");
    }

    private File resolveAssetsDir() {
        String[] candidates = {"assets", "SFRE/assets", System.getProperty("user.dir") + File.separator + "assets"};
        for (String c : candidates) { File f = new File(c); if (f.isDirectory()) return f; }
        return new File("assets");
    }
    private final File assetsDir = resolveAssetsDir();
    private final File texturesDir = new File(assetsDir, "textures");

    private final Map<String, BufferedImage> thumbCache = new HashMap<>();
    private static final BufferedImage NO_IMAGE = new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB);

    /**
     * Dimmed “screenshot” of another floor under the edit layer (Timesplitters / FPSC style).
     * brightness 0..1 — walls/floors drawn darker.
     */
    private void drawFloorGhost(Graphics2D g2, int floorIdx, float brightness) {
        if (floorIdx < 0 || layerGrids[floorIdx] == null) return;
        int[][] g = layerGrids[floorIdx];
        String[][] wt = layerWallTex[floorIdx];
        String[][] ft = layerFloorTex[floorIdx];
        Composite old = g2.getComposite();
        g2.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, Math.max(0.15f, Math.min(1f, brightness))));
        for (int x = 0; x < mapSize; x++) {
            for (int y = 0; y < mapSize; y++) {
                int v = g[x][y];
                int px = x * cellPx, py = y * cellPx;
                String flPath = ft != null ? ft[x][y] : "";
                String wPath = wt != null ? wt[x][y] : "";
                if (v == 0) {
                    BufferedImage flImg = getThumb(flPath);
                    if (flImg != null) g2.drawImage(flImg, px, py, cellPx, cellPx, null);
                    else {
                        g2.setColor(new Color(30, 30, 30));
                        g2.fillRect(px, py, cellPx, cellPx);
                    }
                } else if (v == 1 || v == 2 || v == 6) {
                    BufferedImage wImg = getThumb(wPath);
                    if (wImg != null) g2.drawImage(wImg, px, py, cellPx, cellPx, null);
                    else {
                        g2.setColor(new Color(50, 90, 60));
                        g2.fillRect(px, py, cellPx, cellPx);
                    }
                } else if (v == 3 || v == 7) {
                    g2.setColor(new Color(40, 10, 10));
                    g2.fillRect(px, py, cellPx, cellPx);
                } else if (v == 4) {
                    g2.setColor(new Color(20, 50, 100));
                    g2.fillRect(px, py, cellPx, cellPx);
                } else if (v == 5) {
                    g2.setColor(new Color(55, 55, 55));
                    g2.fillRect(px, py, cellPx, cellPx);
                }
            }
        }
        g2.setComposite(old);
    }

    /** Same rule as engine: skip ShaderMap sidecars (NORM/AO/DISP/SPEC). */
    static boolean isDiffuseTextureName(String n) {
        if (n == null) return false;
        String s = n.toLowerCase();
        int dot = s.lastIndexOf('.');
        String stem = dot > 0 ? s.substring(0, dot) : s;
        String[] reject = {
            "_norm", "_normal", "_nrm",
            "_ao", "_occ", "_occlusion",
            "_disp", "_displacement", "_height", "_bump",
            "_spec", "_specular", "_rough", "_roughness", "_metal", "_metallic",
            "_emis", "_emission", "_gloss"
        };
        for (String r : reject) if (stem.endsWith(r)) return false;
        return true;
    }

    private BufferedImage getThumb(String relPath) {
        if (relPath == null || relPath.isEmpty()) return null;
        if (relPath.startsWith("tileset:")) {
            BufferedImage cachedTs = thumbCache.get(relPath);
            if (cachedTs != null) return cachedTs == NO_IMAGE ? null : cachedTs;
            TilePattern tp = resolveTilesetRef(relPath);
            if (tp != null && tp.image != null) {
                thumbCache.put(relPath, tp.image);
                return tp.image;
            }
            thumbCache.put(relPath, NO_IMAGE);
            return null;
        }
        BufferedImage cached = thumbCache.get(relPath);
        if (cached != null) return cached == NO_IMAGE ? null : cached;
        BufferedImage img = null;
        try {
            File f = new File(assetsDir, relPath);
            if (f.isFile()) img = javax.imageio.ImageIO.read(f);
        } catch (Exception ignored) {}
        thumbCache.put(relPath, img == null ? NO_IMAGE : img);
        return img;
    }

    // Grid layers: Path-based texture tracking
    private int[][] grid = new int[mapSize][mapSize];
    private String[][] wallTextures = new String[mapSize][mapSize];
    private String[][] floorTextures = new String[mapSize][mapSize];
    private int[][] wallHeights = new int[mapSize][mapSize];
    private int[][] groundHeights = new int[mapSize][mapSize];
    private int activeWallHeight = 2;
    private int activeGroundHeight = 2;
    private final Map<Point, Entity> things = new LinkedHashMap<>();
    private final Map<Point, Color> wallTints = new HashMap<>();
    private Color paintTintColor = null;
    private double playerX = mapSize / 2.0, playerY = mapSize / 2.0 - 3;
    private double playerDirX = 0, playerDirY = 1;

    private String mapName = "untitled.map";
    /** Absolute path of last save/load — used by autosave. */
    private File currentMapFile = null;
    private boolean mapDirty = false;
    private javax.swing.Timer editorAutosaveTimer;
    private JLabel status;
    private GridCanvas canvas;
    private LivePreviewPanel livePreview;
    private JTabbedPane sideTabPane;
    private JPanel canvasWrapper;
    private JScrollPane scroll;
    private JSplitPane centerSplit;
    private ChromaOptions options = ChromaOptions.load();

    // ---------- Undo / Redo Snapshot Logic ----------
    private static class Snapshot {
        int size;
        int[][] grid;
        String[][] textures;
        String[][] floors;
        Map<Point, Entity> things;
        Map<Point, Color> tints;
        double px, py, dx, dy;
    }
    private final Deque<Snapshot> undoStack = new ArrayDeque<>();
    private final Deque<Snapshot> redoStack = new ArrayDeque<>();
    private static final int MAX_UNDO = 60;

    private Snapshot snapshot() {
        Snapshot s = new Snapshot();
        s.size = mapSize;
        s.grid = new int[mapSize][mapSize];
        s.textures = new String[mapSize][mapSize];
        s.floors = new String[mapSize][mapSize];
        for (int x = 0; x < mapSize; x++) {
            System.arraycopy(grid[x], 0, s.grid[x], 0, mapSize);
            System.arraycopy(wallTextures[x], 0, s.textures[x], 0, mapSize);
            System.arraycopy(floorTextures[x], 0, s.floors[x], 0, mapSize);
        }
        s.things = new LinkedHashMap<>();
        for (Map.Entry<Point, Entity> en : things.entrySet()) {
            Entity src = en.getValue();
            Entity c = new Entity(src.category, src.x, src.y);
            c.text = src.text; c.assetPath = src.assetPath;
            c.solid = src.solid; c.patrolMode = src.patrolMode;
            c.floorIndex = src.floorIndex; c.areaRadius = src.areaRadius;
            c.pathData = src.pathData;
            s.things.put(en.getKey(), c);
        }
        s.tints = new HashMap<>(wallTints);
        s.px = playerX; s.py = playerY; s.dx = playerDirX; s.dy = playerDirY;
        return s;
    }

    private void restoreSnapshot(Snapshot s) {
        this.mapSize = s.size;
        this.grid = new int[mapSize][mapSize];
        this.wallTextures = new String[mapSize][mapSize];
        this.floorTextures = new String[mapSize][mapSize];
        for (int x = 0; x < mapSize; x++) {
            System.arraycopy(s.grid[x], 0, grid[x], 0, mapSize);
            System.arraycopy(s.textures[x], 0, wallTextures[x], 0, mapSize);
            System.arraycopy(s.floors[x], 0, floorTextures[x], 0, mapSize);
        }
        things.clear(); things.putAll(s.things);
        wallTints.clear(); wallTints.putAll(s.tints);
        playerX = s.px; playerY = s.py; playerDirX = s.dx; playerDirY = s.dy;
    }

    private void pushUndo() {
        undoStack.push(snapshot());
        while (undoStack.size() > MAX_UNDO) undoStack.removeLast();
        redoStack.clear();
        markDirty();
    }

    private void undo() {
        if (undoStack.isEmpty()) { status.setText("  // Nothing to undo"); return; }
        redoStack.push(snapshot());
        restoreSnapshot(undoStack.pop());
        refreshAll();
    }

    private void redo() {
        if (redoStack.isEmpty()) { status.setText("  // Nothing to redo"); return; }
        undoStack.push(snapshot());
        restoreSnapshot(redoStack.pop());
        refreshAll();
    }

    public SFMapEditor() {
        options = ChromaOptions.load();
        setTitle("SFMAPEDITOR v3.2 - Unity Layout + Live Preview");
        setSize(1200, 800);
        setMinimumSize(new Dimension(900, 600));
        setLocationRelativeTo(null);
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);

        newMap(24);

        JPanel root = new JPanel(new BorderLayout(4, 4));
        root.setBackground(theme.bg);
        root.setBorder(new LineBorder(theme.fg, options.highContrast ? 3 : 2));
        setJMenuBar(buildMenuBar());
        root.add(buildToolbar(), BorderLayout.NORTH);

        canvas = new GridCanvas();

        canvasWrapper = new JPanel(new GridBagLayout());
        canvasWrapper.setBackground(theme.bg);
        canvasWrapper.add(canvas);

        scroll = new JScrollPane(canvasWrapper);
        scroll.getViewport().setBackground(theme.bg);
        scroll.setBorder(new LineBorder(theme.fg, 1));
        scroll.getVerticalScrollBar().setUnitIncrement(16);
        scroll.getHorizontalScrollBar().setUnitIncrement(16);

        // Unity-style: map + live 3D preview share the same center pane
        if (options.livePreview) {
            livePreview = new LivePreviewPanel();
            centerSplit = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, scroll, livePreview);
            centerSplit.setResizeWeight(0.55);
            centerSplit.setDividerLocation(620);
            centerSplit.setBorder(null);
            centerSplit.setBackground(theme.bg);
            root.add(centerSplit, BorderLayout.CENTER);
        } else {
            root.add(scroll, BorderLayout.CENTER);
        }

        scanTilesets();
        sideTabPane = buildGameMakerSidebar();
        root.add(sideTabPane, BorderLayout.EAST);

        status = new JLabel(statusText());
        status.setFont(new Font("Monospaced", Font.BOLD, options.uiFontSize));
        status.setForeground(theme.fg);
        status.setBorder(new EmptyBorder(4, 8, 4, 8));
        root.add(status, BorderLayout.SOUTH);

        setContentPane(root);
        bindShortcuts(root);
    }

    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> new SFMapEditor().setVisible(true));
    }

    private String statusText() {
        String toolExtra = "";
        if (currentTool.equals("WALL")) toolExtra = " (Brush: " + (activeWallBrush.isEmpty() ? "Default" : activeWallBrush) + ")";
        else if (currentTool.equals("FLOOR")) toolExtra = " (Brush: " + (activeFloorBrush.isEmpty() ? "Default" : activeFloorBrush) + ")";
        else if (currentTool.equals("TILESET") && activeTilePattern != null)
            toolExtra = " (Tile: " + activeTilePattern.id + " [" + activeTilePattern.ground + "])";
        else if (currentTool.equals("TINT") && paintTintColor != null) {
            toolExtra = String.format(" (#%02X%02X%02X)", paintTintColor.getRed(), paintTintColor.getGreen(), paintTintColor.getBlue());
        }
        String skyExtra = selectedSkyboxPath.isEmpty() ? "" : "  |  SKYBOX: " + selectedSkyboxPath;
        return "  // TOOL: " + currentTool + toolExtra
                + "  |  SIZE: " + mapSize + "x" + mapSize
                + "  |  " + mapType + " | FLOOR: " + (activeFloor + 1) + "/" + floorCount
                + "  |  GRID: " + cellPx + "px | BRUSH: " + brushSize + "x" + brushSize + (currentTool.equals("FILL") ? " | FILL:" + fillMode : "")
                + "  |  MAP: " + mapName
                + "  |  ENT F" + (activeFloor + 1) + ": " + countEntitiesOnFloor(activeFloor)
                + " / ALL: " + things.size()
                + (filterEntitiesToActiveFloor ? " [filter ON]" : "")
                + skyExtra;
    }

    private void newMap(int sz) {
        this.mapSize = sz;
        this.grid = new int[mapSize][mapSize];
        this.wallTextures = new String[mapSize][mapSize];
        this.floorTextures = new String[mapSize][mapSize];
        this.wallHeights = new int[mapSize][mapSize];
        this.groundHeights = new int[mapSize][mapSize];
        // Reset multi-floor layers to new size
        for (int f = 0; f < MAX_FLOORS; f++) {
            layerGrids[f] = null;
            layerWallTex[f] = null;
            layerFloorTex[f] = null;
            layerWallH[f] = null;
            layerGroundH[f] = null;
        }
        activeFloor = 0;
        floorCount = Math.max(1, floorCount);
        undoStack.clear(); redoStack.clear();
        for (int x = 0; x < mapSize; x++) {
            for (int y = 0; y < mapSize; y++) {
                grid[x][y] = (x == 0 || y == 0 || x == mapSize - 1 || y == mapSize - 1) ? 1 : 0;
                wallHeights[x][y] = 2;
                groundHeights[x][y] = 2;
                wallTextures[x][y] = "";
                floorTextures[x][y] = "";
            }
        }
        ensureFloorLayers();
        stashActiveFloor();
        things.clear();
        wallTints.clear();
        for (int f=0; f<MAX_FLOORS; f++) { hasPlayerStart[f]=false; playerStartX[f]=mapSize/2.0; playerStartY[f]=mapSize/2.0-3; playerStartDirX[f]=0; playerStartDirY[f]=1; }
        hasPlayerStart[0]=true;
        playerStartX[0]=mapSize/2.0; playerStartY[0]=mapSize/2.0-3; playerStartDirX[0]=0; playerStartDirY[0]=1;
        playerX = mapSize / 2.0; playerY = mapSize / 2.0 - 3;
        playerDirX = 0; playerDirY = 1;
        mapName = "untitled.map";
        if (canvas != null) canvas.updateCanvasSize();
    }

    private void loadMapDialog() {
        File startDir = new File("assets/maps");
        if (!startDir.isDirectory()) startDir = new File(".");
        JFileChooser ch = new JFileChooser(startDir);
        ch.setFileFilter(new FileNameExtensionFilter("LOCWTTP Map (*.map)", "map"));
        if (ch.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) return;
        try {
            currentMapFile = ch.getSelectedFile();
            loadFrom(ch.getSelectedFile());
            clearDirty();
            mapName = ch.getSelectedFile().getName();
            refreshAll();
        } catch (Exception ex) {
            JOptionPane.showMessageDialog(this, "Load failed: " + ex.getMessage());
        }
    }

    private void loadFrom(File f) throws IOException {
        int readSize = 24;
        List<String> lines = new ArrayList<>();
        try (BufferedReader br = new BufferedReader(new FileReader(f))) {
            String line;
            while ((line = br.readLine()) != null) {
                line = line.trim();
                if (line.startsWith("SIZE ")) {
                    readSize = Integer.parseInt(line.substring(5).trim());
                } else if (line.toUpperCase().startsWith("MAPTYPE ")) {
                    try {
                        String mt = line.substring(8).trim().toUpperCase();
                        if (mt.contains("INDOOR")) {
                            mapType = MapType.INDOOR;
                        } else {
                            mapType = MapType.OUTDOOR;
                            floorCount = 1;
                            activeFloor = 0;
                        }
                    } catch (Exception ignored) {}
                } else if (line.startsWith("FLOORS ")) {
                    try {
                        int req = Integer.parseInt(line.substring(7).trim());
                        if (mapType == MapType.OUTDOOR) floorCount = 1;
                        else floorCount = Math.max(1, Math.min(MAX_FLOORS, req));
                    }
                    catch (Exception ignored) {}
                } else if (line.startsWith("SKYBOX ")) {
                    selectedSkyboxPath = line.substring(7).trim();
                } else if (line.startsWith("FOG ")) {
                    try { mapFog = Double.parseDouble(line.substring(4).trim()); } catch (Exception ignored) {}
                } else if (line.startsWith("RAIN ")) {
                    mapRain = line.toLowerCase().contains("true") || line.contains("1");
                }
                lines.add(line);
            }
        }

        this.mapSize = readSize;
        for (int fi = 0; fi < MAX_FLOORS; fi++) {
            layerGrids[fi] = null; layerWallTex[fi] = null; layerFloorTex[fi] = null;
            layerWallH[fi] = null; layerGroundH[fi] = null;
        }
        ensureFloorLayers();
        int[][] newGrid = new int[mapSize][mapSize];
        String[][] newTex = new String[mapSize][mapSize];
        String[][] newFloors = new String[mapSize][mapSize];
        Map<Point, Entity> newThings = new LinkedHashMap<>();
        Map<Point, Color> newTints = new HashMap<>();
        double px = mapSize / 2.0, py = mapSize / 2.0 - 3, dx = 0, dy = 1;
        boolean inGrid = false, inThings = false, inTb = false, inColors = false, inTex = false, inFloors = false;
        boolean inWallH = false, inGroundH = false;
        int wallHY = 0, groundHY = 0;
        int parseFloor = 0;
        wallHeights = new int[mapSize][mapSize];
        groundHeights = new int[mapSize][mapSize];
        for (int x = 0; x < mapSize; x++) {
            java.util.Arrays.fill(wallHeights[x], 2);
            java.util.Arrays.fill(groundHeights[x], 2);
        }
        int gy = 0, ty = 0, fy = 0;

        // CAT x y ["asset"] [SOLID|NOSOLID] [AREA r | PATH "x,y;x,y"]
        java.util.regex.Pattern thingPattern = java.util.regex.Pattern.compile(
                "^(\\S+)\\s+([\\d.]+)\\s+([\\d.]+)(?:\\s+\"([^\"]*)\")?(?:\\s+(SOLID|NOSOLID))?(?:\\s+AREA\\s+([\\d.]+))?(?:\\s+PATH\\s+\"([^\"]*)\")?(?:\\s+FLOOR\\s+(\\d+))?$",
                java.util.regex.Pattern.CASE_INSENSITIVE);
        java.util.regex.Pattern tbPattern = java.util.regex.Pattern.compile("^([\\d.]+)\\s+([\\d.]+)\\s+([\\d.]+)\\s+([\\d.]+)\\s+([\\d.]+)\\s+(true|false)\\s+\"([^\"]+)\"$");

        for (String line : lines) {
            if (line.isEmpty() || line.startsWith("#") || line.startsWith("SIZE ") || line.startsWith("SKYBOX ")
                    || line.startsWith("FOG ") || line.startsWith("RAIN ") || line.startsWith("FLOORS ")) continue;
            if (line.toUpperCase().startsWith("PLAYER_F")) {
                try {
                    String[] t = line.split("\\s+");
                    String head = t[0].toUpperCase();
                    int fn = Integer.parseInt(head.replaceAll("[^0-9]",""));
                    if (fn>=0 && fn<MAX_FLOORS && t.length>=5) {
                        playerStartX[fn]=Double.parseDouble(t[1]); playerStartY[fn]=Double.parseDouble(t[2]);
                        playerStartDirX[fn]=Double.parseDouble(t[3]); playerStartDirY[fn]=Double.parseDouble(t[4]);
                        hasPlayerStart[fn]=true;
                        if (fn==0) { px=playerStartX[fn]; py=playerStartY[fn]; dx=playerStartDirX[fn]; dy=playerStartDirY[fn]; }
                    }
                } catch (Exception ignored) {}
            } else if (line.startsWith("PLAYER ")) {
                String[] t = line.split("\\s+");
                if (t.length >= 5) {
                    px = Double.parseDouble(t[1]); py = Double.parseDouble(t[2]);
                    dx = Double.parseDouble(t[3]); dy = Double.parseDouble(t[4]);
                    playerStartX[0]=px; playerStartY[0]=py; playerStartDirX[0]=dx; playerStartDirY[0]=dy; hasPlayerStart[0]=true;
                }
            } else if (line.toUpperCase().startsWith("BEGIN_GRID_F")) {
                parseFloor = editorParseFloor(line, "BEGIN_GRID_F");
                inGrid = true; gy = 0;
            } else if (line.toUpperCase().startsWith("END_GRID_F")) {
                inGrid = false;
            } else if (line.equalsIgnoreCase("BEGIN_GRID") || line.equalsIgnoreCase("BEGIN_MAP")) {
                parseFloor = 0; inGrid = true; gy = 0;
            } else if (line.equalsIgnoreCase("END_GRID") || line.equalsIgnoreCase("END_MAP")) {
                inGrid = false;
            } else if (line.toUpperCase().startsWith("BEGIN_TEXTURES_F")) {
                parseFloor = editorParseFloor(line, "BEGIN_TEXTURES_F");
                inTex = true; ty = 0;
            } else if (line.toUpperCase().startsWith("END_TEXTURES_F")) {
                inTex = false;
            } else if (line.equalsIgnoreCase("BEGIN_TEXTURES")) {
                parseFloor = 0; inTex = true; ty = 0;
            } else if (line.equalsIgnoreCase("END_TEXTURES")) {
                inTex = false;
            } else if (line.toUpperCase().startsWith("BEGIN_FLOORS_F")) {
                parseFloor = editorParseFloor(line, "BEGIN_FLOORS_F");
                inFloors = true; fy = 0;
            } else if (line.toUpperCase().startsWith("END_FLOORS_F")) {
                inFloors = false;
            } else if (line.equalsIgnoreCase("BEGIN_FLOORS")) {
                parseFloor = 0; inFloors = true; fy = 0;
            } else if (line.equalsIgnoreCase("END_FLOORS")) {
                inFloors = false;
            } else if (line.toUpperCase().startsWith("BEGIN_WALLHEIGHTS_F")) {
                parseFloor = editorParseFloor(line, "BEGIN_WALLHEIGHTS_F");
                inWallH = true; wallHY = 0;
            } else if (line.toUpperCase().startsWith("END_WALLHEIGHTS_F")) {
                inWallH = false;
            } else if (line.equalsIgnoreCase("BEGIN_WALLHEIGHTS")) {
                parseFloor = 0; inWallH = true; wallHY = 0;
            } else if (line.equalsIgnoreCase("END_WALLHEIGHTS")) {
                inWallH = false;
            } else if (line.toUpperCase().startsWith("BEGIN_GROUNDHEIGHTS_F")) {
                parseFloor = editorParseFloor(line, "BEGIN_GROUNDHEIGHTS_F");
                inGroundH = true; groundHY = 0;
            } else if (line.toUpperCase().startsWith("END_GROUNDHEIGHTS_F")) {
                inGroundH = false;
            } else if (line.equalsIgnoreCase("BEGIN_GROUNDHEIGHTS")) {
                parseFloor = 0; inGroundH = true; groundHY = 0;
            } else if (line.equalsIgnoreCase("END_GROUNDHEIGHTS")) {
                inGroundH = false;
            } else if (line.equalsIgnoreCase("BEGIN_THINGS")) { inThings = true; }
            else if (line.equalsIgnoreCase("END_THINGS")) { inThings = false; }
            else if (line.equalsIgnoreCase("BEGIN_TEXTBOARDS")) { inTb = true; }
            else if (line.equalsIgnoreCase("END_TEXTBOARDS")) { inTb = false; }
            else if (line.equalsIgnoreCase("BEGIN_COLORS")) { inColors = true; }
            else if (line.equalsIgnoreCase("END_COLORS")) { inColors = false; }
            else if (inGrid) {
                String[] t = line.split("\\s+");
                int[][] dest = (parseFloor == 0) ? newGrid : layerGrids[parseFloor];
                for (int x = 0; x < Math.min(t.length, mapSize); x++) {
                    try { dest[x][gy] = Integer.parseInt(t[x]); } catch (Exception ignored) {}
                }
                gy++; if (gy >= mapSize) inGrid = false;
            } else if (inTex) {
                String[] t = line.split("\\s+");
                String[][] dest = (parseFloor == 0) ? newTex : layerWallTex[parseFloor];
                for (int x = 0; x < Math.min(t.length, mapSize); x++) {
                    dest[x][ty] = t[x].equals("0") ? "" : t[x];
                    if (!t[x].equals("0") && !wallBrushes.contains(t[x])) wallBrushes.add(t[x]);
                }
                ty++; if (ty >= mapSize) inTex = false;
            } else if (inFloors) {
                String[] t = line.split("\\s+");
                String[][] dest = (parseFloor == 0) ? newFloors : layerFloorTex[parseFloor];
                for (int x = 0; x < Math.min(t.length, mapSize); x++) {
                    dest[x][fy] = t[x].equals("0") ? "" : t[x];
                    if (!t[x].equals("0") && !floorBrushes.contains(t[x])) floorBrushes.add(t[x]);
                }
                fy++; if (fy >= mapSize) inFloors = false;
            } else if (inWallH) {
                String[] t = line.split("\\s+");
                int[][] dest = (parseFloor == 0) ? wallHeights : layerWallH[parseFloor];
                for (int x = 0; x < Math.min(t.length, mapSize); x++) {
                    try { dest[x][wallHY] = Math.max(1, Math.min(4, Integer.parseInt(t[x]))); }
                    catch (Exception ignored) {}
                }
                wallHY++; if (wallHY >= mapSize) inWallH = false;
            } else if (inGroundH) {
                String[] t = line.split("\\s+");
                int[][] dest = (parseFloor == 0) ? groundHeights : layerGroundH[parseFloor];
                for (int x = 0; x < Math.min(t.length, mapSize); x++) {
                    try { dest[x][groundHY] = Math.max(0, Math.min(3, Integer.parseInt(t[x]))); }
                    catch (Exception ignored) {}
                }
                groundHY++; if (groundHY >= mapSize) inGroundH = false;
            } else if (inColors && line.startsWith("C ")) {
                String[] t = line.split("\\s+");
                if (t.length >= 6) {
                    int cxp = Integer.parseInt(t[1]), cyp = Integer.parseInt(t[2]);
                    int r = Integer.parseInt(t[3]), g = Integer.parseInt(t[4]), b = Integer.parseInt(t[5]);
                    newTints.put(new Point(cxp, cyp), new Color(r, g, b));
                }
            } else if (inThings) {
                var m = thingPattern.matcher(line);
                if (m.find()) {
                    double ex = Double.parseDouble(m.group(2)), ey = Double.parseDouble(m.group(3));
                    Entity e = new Entity(m.group(1).toUpperCase(), ex, ey);
                    e.assetPath = m.group(4);
                    if (m.group(5) != null) {
                        e.solid = m.group(5).equalsIgnoreCase("SOLID");
                    }
                    if (m.group(6) != null) {
                        e.patrolMode = "AREA";
                        e.areaRadius = Double.parseDouble(m.group(6));
                    }
                    if (m.group(7) != null) {
                        e.patrolMode = "PATH";
                        e.pathData = m.group(7);
                    }
                    if (m.group(8) != null) {
                        try { e.floorIndex = Integer.parseInt(m.group(8)); } catch (Exception ignored) {}
                    }
                    newThings.put(cellOf(ex, ey), e);
                }
            } else if (inTb) {
                var m = tbPattern.matcher(line);
                if (m.find()) {
                    double ex = Double.parseDouble(m.group(1)), ey = Double.parseDouble(m.group(2));
                    Entity e = new Entity("TEXTBOARD", ex, ey);
                    e.text = m.group(7).replace("\\n", "\n");
                    newThings.put(cellOf(ex, ey), e);
                }
            }
        }

        this.grid = newGrid;
        this.wallTextures = newTex;
        this.floorTextures = newFloors;
        // Commit floor 0 into layer stack
        for (int x = 0; x < mapSize; x++) {
            System.arraycopy(newGrid[x], 0, layerGrids[0][x], 0, mapSize);
            System.arraycopy(newTex[x], 0, layerWallTex[0][x], 0, mapSize);
            System.arraycopy(newFloors[x], 0, layerFloorTex[0][x], 0, mapSize);
            System.arraycopy(wallHeights[x], 0, layerWallH[0][x], 0, mapSize);
            System.arraycopy(groundHeights[x], 0, layerGroundH[0][x], 0, mapSize);
        }
        activeFloor = 0;
        things.clear(); things.putAll(newThings);
        wallTints.clear(); wallTints.putAll(newTints);
        // active floor 0 player
        if (hasPlayerStart[0]) { px = playerStartX[0]; py = playerStartY[0]; dx = playerStartDirX[0]; dy = playerStartDirY[0]; }
        playerX = px; playerY = py; playerDirX = dx; playerDirY = dy;
        undoStack.clear(); redoStack.clear();
        rebuildSidebar();
        canvas.updateCanvasSize();
    }

    private static int editorParseFloor(String line, String prefix) {
        try {
            String rest = line.trim().substring(prefix.length()).trim();
            return Math.max(0, Math.min(MAX_FLOORS - 1, Integer.parseInt(rest.replaceAll("[^0-9]", ""))));
        } catch (Exception e) {
            return 0;
        }
    }

    private static Point cellOf(double x, double y) { return new Point((int) x, (int) y); }


    private void markDirty() {
        mapDirty = true;
    }

    private void clearDirty() {
        mapDirty = false;
    }

    private void startEditorAutosave() {
        if (editorAutosaveTimer != null) editorAutosaveTimer.stop();
        int sec = 120;
        try {
            if (options == null) options = ChromaOptions.load();
            if (!options.editorAutosave) return;
            sec = Math.max(30, options.editorAutosaveSeconds);
        } catch (Throwable ignored) {}
        editorAutosaveTimer = new javax.swing.Timer(sec * 1000, e -> editorAutosaveTick());
        editorAutosaveTimer.setRepeats(true);
        editorAutosaveTimer.start();
    }

    private void editorAutosaveTick() {
        if (!mapDirty) return;
        try {
            if (options == null) options = ChromaOptions.load();
            if (!options.editorAutosave) return;
        } catch (Throwable ignored) { return; }
        try {
            File target;
            if (currentMapFile != null) {
                target = currentMapFile;
            } else {
                File dir = new File("autosave");
                if (!dir.isDirectory()) dir.mkdirs();
                target = new File(dir, "map_autosave.map");
            }
            saveTo(target);
            if (currentMapFile == null) currentMapFile = target;
            mapName = target.getName();
            clearDirty();
            if (status != null) status.setText("  // AUTOSAVE " + target.getName());
        } catch (Exception ex) {
            if (status != null) status.setText("  // AUTOSAVE FAILED: " + ex.getMessage());
        }
    }

    private void saveMapDialog() {
        JFileChooser ch = new JFileChooser();
        ch.setSelectedFile(new File(mapName.equals("untitled.map") ? "room.map" : mapName));
        ch.setFileFilter(new FileNameExtensionFilter("LOCWTTP Map (*.map)", "map"));
        if (ch.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) return;
        try {
            File out = ch.getSelectedFile();
            if (!out.getName().toLowerCase().endsWith(".map")) out = new File(out.getParentFile(), out.getName() + ".map");
            saveTo(out);
            currentMapFile = out;
            mapName = out.getName();
            clearDirty();
            status.setText("  // SAVED " + out.getAbsolutePath());
        } catch (Exception ex) {
            JOptionPane.showMessageDialog(this, "Save failed: " + ex.getMessage());
        }
    }

    private void saveTo(File f) throws IOException {
        try (PrintWriter w = new PrintWriter(new FileWriter(f))) {
            stashActiveFloor();
            w.println("# SFPAINT MAP v3.4 - True multi-floor");
            w.println("SIZE " + mapSize);
            w.println("MAPTYPE " + mapType);
            w.println("FLOORS " + (mapType==MapType.OUTDOOR?1:Math.max(1, floorCount)));
            if (!selectedSkyboxPath.isEmpty()) {
                w.println("SKYBOX " + selectedSkyboxPath);
            }
            if (mapFog > 0.001) w.printf("FOG %.2f%n", mapFog);
            if (mapRain) w.println("RAIN true");
            // Per-floor player starts
            for (int fi=0; fi<Math.max(1,floorCount); fi++) {
                if (hasPlayerStart[fi]) {
                    w.printf("PLAYER_F%d %.1f %.1f %.1f %.1f%n", fi, playerStartX[fi], playerStartY[fi], playerStartDirX[fi], playerStartDirY[fi]);
                }
            }
            // legacy PLAYER for backwards compat (floor 0)
            if (hasPlayerStart[0]) {
                w.printf("PLAYER %.1f %.1f %.1f %.1f%n", playerStartX[0], playerStartY[0], playerStartDirX[0], playerStartDirY[0]);
            } else {
                w.printf("PLAYER %.1f %.1f %.1f %.1f%n", playerX, playerY, playerDirX, playerDirY);
            }
            ensureFloorLayers();
            // Write each floor as BEGIN_GRID_Fn (floor 0 also as BEGIN_GRID for old loaders)
            for (int fi = 0; fi < Math.max(1, floorCount); fi++) {
                writeFloorBlock(w, fi);
            }
            w.println("BEGIN_COLORS");
            for (Map.Entry<Point, Color> t : wallTints.entrySet()) {
                Color c = t.getValue();
                w.printf("C %d %d %d %d %d%n", t.getKey().x, t.getKey().y, c.getRed(), c.getGreen(), c.getBlue());
            }
            w.println("END_COLORS");
            w.println("BEGIN_THINGS");
            for (Entity e : things.values()) {
                if (!e.category.equals("TEXTBOARD")) {
                    String solidTag = e.solid ? " SOLID" : " NOSOLID";
                    String patrol = "";
                    if (e.patrolMode != null && !e.patrolMode.equals("NONE")) {
                        if (e.patrolMode.equals("AREA")) {
                            patrol = String.format(" AREA %.1f", e.areaRadius);
                        } else if (e.patrolMode.equals("PATH") && e.pathData != null && !e.pathData.isEmpty()) {
                            patrol = " PATH \"" + e.pathData + "\"";
                        }
                    }
                    String floorTag = " FLOOR " + e.floorIndex;
                    if (e.assetPath != null && !e.assetPath.isEmpty()) {
                        w.printf("%s %.1f %.1f \"%s\"%s%s%s%n", e.category, e.x, e.y, e.assetPath, solidTag, patrol, floorTag);
                    } else {
                        w.printf("%s %.1f %.1f%s%s%s%n", e.category, e.x, e.y, solidTag, patrol, floorTag);
                    }
                }
            }
            w.println("END_THINGS");
            w.println("BEGIN_TEXTBOARDS");
            for (Entity e : things.values()) {
                if (e.category.equals("TEXTBOARD")) {
                    String txt = (e.text == null ? "Message" : e.text).replace("\n", "\\n");
                    w.printf("%.1f %.1f 0.5 3.0 1.5 true \"%s\"%n", e.x, e.y, txt);
                }
            }
            w.println("END_TEXTBOARDS");
        }
    }

    /** Serialize one floor layer (grid + textures + heights). */
    private void writeFloorBlock(PrintWriter w, int f) {
        int[][] g = layerGrids[f];
        String[][] wt = layerWallTex[f];
        String[][] ft = layerFloorTex[f];
        int[][] wh = layerWallH[f];
        int[][] gh = layerGroundH[f];
        if (g == null) return;
        if (f == 0) w.println("BEGIN_GRID");
        w.println("BEGIN_GRID_F" + f);
        for (int y = 0; y < mapSize; y++) {
            StringBuilder sb = new StringBuilder();
            for (int x = 0; x < mapSize; x++) {
                if (x > 0) sb.append(' ');
                sb.append(g[x][y]);
            }
            w.println(sb);
        }
        w.println("END_GRID_F" + f);
        if (f == 0) w.println("END_GRID");

        if (f == 0) w.println("BEGIN_TEXTURES");
        w.println("BEGIN_TEXTURES_F" + f);
        for (int y = 0; y < mapSize; y++) {
            StringBuilder sb = new StringBuilder();
            for (int x = 0; x < mapSize; x++) {
                if (x > 0) sb.append(' ');
                String t = wt[x][y];
                sb.append(t == null || t.isEmpty() ? "0" : t);
            }
            w.println(sb);
        }
        w.println("END_TEXTURES_F" + f);
        if (f == 0) w.println("END_TEXTURES");

        if (f == 0) w.println("BEGIN_FLOORS");
        w.println("BEGIN_FLOORS_F" + f);
        for (int y = 0; y < mapSize; y++) {
            StringBuilder sb = new StringBuilder();
            for (int x = 0; x < mapSize; x++) {
                if (x > 0) sb.append(' ');
                String t = ft[x][y];
                sb.append(t == null || t.isEmpty() ? "0" : t);
            }
            w.println(sb);
        }
        w.println("END_FLOORS_F" + f);
        if (f == 0) w.println("END_FLOORS");

        w.println("BEGIN_WALLHEIGHTS_F" + f);
        for (int y = 0; y < mapSize; y++) {
            StringBuilder sb = new StringBuilder();
            for (int x = 0; x < mapSize; x++) {
                if (x > 0) sb.append(' ');
                sb.append(wh != null ? wh[x][y] : 2);
            }
            w.println(sb);
        }
        w.println("END_WALLHEIGHTS_F" + f);

        w.println("BEGIN_GROUNDHEIGHTS_F" + f);
        for (int y = 0; y < mapSize; y++) {
            StringBuilder sb = new StringBuilder();
            for (int x = 0; x < mapSize; x++) {
                if (x > 0) sb.append(' ');
                sb.append(gh != null ? gh[x][y] : 2);
            }
            w.println(sb);
        }
        w.println("END_GROUNDHEIGHTS_F" + f);
    }

    private void playtestEngine() {
        try {
            File tempMap = new File("temp_playtest.map");
            saveTo(tempMap);
            status.setText("  // Executing RUN.bat...");

            ProcessBuilder pb;
            boolean isWindows = System.getProperty("os.name").toLowerCase().contains("win");
            if (isWindows) {
                pb = new ProcessBuilder("cmd.exe", "/c", "RUN.bat");
            } else {
                pb = new ProcessBuilder("./RUN.bat");
            }
            pb.inheritIO();
            pb.start();
        } catch (Exception ex) {
            JOptionPane.showMessageDialog(this, "Could not launch RUN.bat: " + ex.getMessage());
        }
    }

    /** Blender-style top menus — groups file / map / height / view / tools. */
    private JMenuBar buildMenuBar() {
        JMenuBar mb = new JMenuBar();
        mb.setBackground(theme.panel);
        mb.setBorder(new EmptyBorder(2, 4, 2, 4));

        JMenu file = menu("File");
        file.add(item("Load Map…", e -> loadMapDialog()));
        file.add(item("Save Map…", e -> saveMapDialog()));
        file.addSeparator();
        file.add(item("Playtest", e -> playtestEngine()));
        file.addSeparator();
        file.add(item("Options…", e -> {
            if (ChromaOptions.showDialog(this)) {
                options = ChromaOptions.load();
                status.setFont(new Font("Monospaced", Font.BOLD, options.uiFontSize));
                status.setText("  // OPTIONS SAVED");
                if (livePreview != null) livePreview.applyOptions(options);
            }
        }));
        mb.add(file);

        JMenu map = menu("Map");
        map.add(item("New 24×24", e -> { newMap(24); refreshAll(); }));
        map.add(item("New 32×32", e -> { newMap(32); refreshAll(); }));
        map.add(item("New 64×64", e -> { newMap(64); refreshAll(); }));
        map.add(item("New 128×128", e -> { newMap(128); refreshAll(); }));
        map.addSeparator();
        map.add(item("Generate Terrain…", e -> generateTerrainDialog()));
        map.add(item("Skybox…", e -> selectSkyboxDialog()));
        map.addSeparator();
        map.add(item("Map Type: OUTDOOR (1 floor)", e -> { mapType = MapType.OUTDOOR; floorCount=1; activeFloor=0; loadFloorIntoEditBuffer(0); refreshAll(); status.setText("  // MAPTYPE OUTDOOR - Daggerfall terrain, 1 floor"); }));
        map.add(item("Map Type: INDOOR (1-8 floors)", e -> { mapType = MapType.INDOOR; if(floorCount<1) floorCount=1; refreshAll(); status.setText("  // MAPTYPE INDOOR - LTTP dungeons, ceiling, basements"); }));
        map.add(item("Map Type: SKY (Sky Islands, SS style)", e -> { mapType = MapType.SKY; if(floorCount<2) floorCount=2; ensureFloorLayers(); refreshAll(); status.setText("  // MAPTYPE SKY - Skyward Sword islands, skybox + multi-floor drop"); }));
        map.addSeparator();
        map.add(item("Floors: Set Count…", e -> setFloorCountDialog()));
        map.add(item("Floor ↑ (edit upper)", e -> switchFloor(activeFloor + 1)));
        map.add(item("Floor ↓ (edit lower)", e -> switchFloor(activeFloor - 1)));
        mb.add(map);

        JMenu height = menu("Height");
        height.add(item("Wall Height Brush…", e -> pickWallHeight()));
        height.add(item("Ground Height Brush…", e -> pickGroundHeight()));
        height.addSeparator();
        height.add(item("Set All Walls = 2 (normal)", e -> fillAllWallHeights(2)));
        height.add(item("Set All Ground = 2 (default)", e -> fillAllGroundHeights(2)));
        mb.add(height);

        JMenu view = menu("View");
        view.add(item("Zoom +", e -> { cellPx = Math.min(48, cellPx + 2); canvas.updateCanvasSize(); refreshAll(); }));
        view.add(item("Zoom −", e -> { cellPx = Math.max(4, cellPx - 2); canvas.updateCanvasSize(); refreshAll(); }));
        view.add(item("Grid Scale 4px (fine)", e -> { cellPx = 4; canvas.updateCanvasSize(); refreshAll(); }));
        view.add(item("Grid Scale 8px", e -> { cellPx = 8; canvas.updateCanvasSize(); refreshAll(); }));
        view.add(item("Grid Scale 16px", e -> { cellPx = 16; canvas.updateCanvasSize(); refreshAll(); }));
        view.add(item("Grid Scale 24px", e -> { cellPx = 24; canvas.updateCanvasSize(); refreshAll(); }));
        view.addSeparator();
        view.add(item("Load Reference Image…", e -> loadReferenceImage()));
        view.add(item("Clear Reference Image", e -> {
            referenceImage = null; referenceImagePath = "";
            if (referenceLabel != null) { referenceLabel.setIcon(null); referenceLabel.setText("No image"); }
            status.setText("  // REF cleared");
        }));
        view.add(item("Toggle Radius Overlays", e -> {
            showRadiusOverlays = !showRadiusOverlays;
            status.setText("  // RADIUS OVERLAYS " + (showRadiusOverlays ? "ON" : "OFF"));
            if (canvas != null) canvas.repaint();
        }));
        view.add(item("Toggle Live 3D Preview", e -> toggleLivePreview()));
        view.addSeparator();
        view.add(item("Camera Module…", e -> pickCameraModule()));
        view.add(item("Cycle Theme", e -> cycleTheme()));
        mb.add(view);

        JMenu tools = menu("Tools");
        tools.add(item("Fill Wall (flood)", e -> { currentTool = "FILL"; fillMode = "WALL"; status.setText("  // FILL WALL — click region"); }));
        tools.add(item("Fill Floor (flood)", e -> { currentTool = "FILL"; fillMode = "FLOOR"; status.setText("  // FILL FLOOR — click region"); }));
        tools.addSeparator();
        tools.add(item("Terrain Generate…", e -> generateTerrainDialog()));
        tools.add(item("Open Pit tool", e -> { currentTool = "PITOPEN"; status.setText("  // OPEN PIT tool"); }));
        tools.add(item("Updraft tool", e -> { currentTool = "UPDRAFT"; status.setText("  // UPDRAFT tool"); }));
        tools.addSeparator();
        tools.add(item("Brush size 1", e -> { brushSize = 1; refreshStatus(); }));
        tools.add(item("Brush size 3", e -> { brushSize = 3; refreshStatus(); }));
        tools.add(item("Brush size 5", e -> { brushSize = 5; refreshStatus(); }));
        tools.add(item("Brush size 7", e -> { brushSize = 7; refreshStatus(); }));
        tools.add(item("Brush size 9", e -> { brushSize = 9; refreshStatus(); }));
        mb.add(tools);

        JMenu edit = menu("Edit");
        edit.add(item("Undo  Ctrl+Z", e -> undo()));
        edit.add(item("Redo  Ctrl+Y", e -> redo()));
        edit.addSeparator();
        edit.add(item("Sprite Editor", e -> {
            try {
                Class.forName("SpriteEditor").getMethod("launch", java.awt.Window.class)
                        .invoke(null, SFMapEditor.this);
            } catch (Throwable sex) {
                status.setText(" // SpriteEditor unavailable");
            }
        }));
        mb.add(edit);

        return mb;
    }

    private JMenu menu(String title) {
        JMenu m = new JMenu(title);
        m.setFont(new Font("Monospaced", Font.BOLD, options != null ? options.uiFontSize : 12));
        m.setForeground(theme.fg);
        return m;
    }

    private JMenuItem item(String title, ActionListener al) {
        JMenuItem it = new JMenuItem(title);
        it.setFont(new Font("Monospaced", Font.PLAIN, options != null ? options.uiFontSize : 12));
        it.addActionListener(al);
        return it;
    }

    private void pickWallHeight() {
        String v = JOptionPane.showInputDialog(this, "Wall height 1–4 (2=normal):", String.valueOf(activeWallHeight));
        if (v == null) return;
        try {
            activeWallHeight = Math.max(1, Math.min(4, Integer.parseInt(v.trim())));
            currentTool = "WALLH";
            status.setText("  // WALL HEIGHT = " + activeWallHeight);
        } catch (Exception ignored) {}
    }

    private void pickGroundHeight() {
        String v = JOptionPane.showInputDialog(this, "Ground height 0–3 (2=default):", String.valueOf(activeGroundHeight));
        if (v == null) return;
        try {
            activeGroundHeight = Math.max(0, Math.min(3, Integer.parseInt(v.trim())));
            currentTool = "GROUNDH";
            status.setText("  // GROUND HEIGHT = " + activeGroundHeight);
        } catch (Exception ignored) {}
    }

    private void fillAllWallHeights(int v) {
        pushUndo();
        if (wallHeights == null || wallHeights.length != mapSize) wallHeights = new int[mapSize][mapSize];
        for (int x = 0; x < mapSize; x++) java.util.Arrays.fill(wallHeights[x], v);
        status.setText("  // ALL WALL HEIGHTS = " + v);
        refreshAll();
    }

    private void fillAllGroundHeights(int v) {
        pushUndo();
        if (groundHeights == null || groundHeights.length != mapSize) groundHeights = new int[mapSize][mapSize];
        for (int x = 0; x < mapSize; x++) java.util.Arrays.fill(groundHeights[x], v);
        status.setText("  // ALL GROUND HEIGHTS = " + v);
        refreshAll();
    }

    private void pickCameraModule() {
        String[] modes = {"RAYCAST", "SIDE", "TOP"};
        String pick = (String) JOptionPane.showInputDialog(this,
                "Camera module:", "Camera", JOptionPane.QUESTION_MESSAGE, null, modes,
                options != null ? options.cameraMode : "RAYCAST");
        if (pick != null) {
            if (options == null) options = ChromaOptions.load();
            options.cameraMode = pick;
            options.save();
            status.setText("  // CAMERA: " + pick);
        }
    }

    /** Compact toolbar — frequent actions only (Blender-style header strip). */


    private void toggleLivePreview() {
        if (options == null) options = ChromaOptions.load();
        options.livePreview = !options.livePreview;
        try { options.save(); } catch (Exception ignored) {}
        status.setText("  // LIVE PREVIEW " + (options.livePreview ? "ON — restart editor to apply layout" : "OFF — restart editor to apply layout"));
        if (livePreview != null) {
            livePreview.setVisible(options.livePreview);
            if (options.livePreview) livePreview.previewTimer.start();
            else livePreview.previewTimer.stop();
        } else if (options.livePreview) {
            status.setText("  // LIVE PREVIEW enabled — close & reopen editor to show panel");
        }
        revalidate();
        repaint();
    }

    private void loadReferenceImage() {
        JFileChooser ch = new JFileChooser();
        ch.setDialogTitle("Reference image (shown while editing)");
        ch.setFileFilter(new javax.swing.filechooser.FileFilter() {
            public boolean accept(File f) {
                if (f.isDirectory()) return true;
                String n = f.getName().toLowerCase();
                return n.endsWith(".png") || n.endsWith(".jpg") || n.endsWith(".jpeg") || n.endsWith(".gif") || n.endsWith(".bmp");
            }
            public String getDescription() { return "Images"; }
        });
        if (ch.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) return;
        File f = ch.getSelectedFile();
        try {
            BufferedImage img = javax.imageio.ImageIO.read(f);
            if (img == null) {
                status.setText("  // Could not read image");
                return;
            }
            referenceImage = img;
            referenceImagePath = f.getAbsolutePath();
            ensureReferencePanel();
            if (referenceLabel != null) {
                int maxW = 280, maxH = 220;
                double sx = maxW / (double) img.getWidth();
                double sy = maxH / (double) img.getHeight();
                double s = Math.min(1.0, Math.min(sx, sy));
                int dw = Math.max(1, (int) (img.getWidth() * s));
                int dh = Math.max(1, (int) (img.getHeight() * s));
                referenceLabel.setIcon(new ImageIcon(img.getScaledInstance(dw, dh, Image.SCALE_SMOOTH)));
                referenceLabel.setText(f.getName());
            }
            status.setText("  // REF: " + f.getName());
            revalidate();
            repaint();
        } catch (Exception ex) {
            status.setText("  // REF load failed: " + ex.getMessage());
        }
    }

    private void ensureReferencePanel() {
        if (referencePanel != null) return;
        referencePanel = new JPanel(new BorderLayout(4, 4));
        referencePanel.setBackground(theme.panel);
        referencePanel.setBorder(new LineBorder(theme.fg, 1));
        JLabel title = new JLabel(" REFERENCE ");
        title.setFont(new Font("Monospaced", Font.BOLD, 11));
        title.setForeground(theme.fg);
        referencePanel.add(title, BorderLayout.NORTH);
        referenceLabel = new JLabel("No image", SwingConstants.CENTER);
        referenceLabel.setForeground(theme.fg);
        referenceLabel.setVerticalTextPosition(SwingConstants.BOTTOM);
        referenceLabel.setHorizontalTextPosition(SwingConstants.CENTER);
        JScrollPane rsp = new JScrollPane(referenceLabel);
        rsp.setPreferredSize(new Dimension(300, 240));
        rsp.getViewport().setBackground(theme.bg);
        referencePanel.add(rsp, BorderLayout.CENTER);
        JButton clear = themedButton("CLEAR REF", e -> {
            referenceImage = null;
            referenceImagePath = "";
            if (referenceLabel != null) { referenceLabel.setIcon(null); referenceLabel.setText("No image"); }
            status.setText("  // REF cleared");
        });
        referencePanel.add(clear, BorderLayout.SOUTH);

        // Dock under status or east of center — prefer south of center split
        Container content = getContentPane();
        if (content instanceof JPanel root) {
            // Try to place beside map scroll: replace CENTER with split
            Component center = null;
            if (root.getLayout() instanceof BorderLayout) {
                for (Component c : root.getComponents()) {
                    // BorderLayout doesn't expose constraints easily — check known fields
                }
            }
            if (centerSplit != null) {
                // put reference under the map+preview split
                JSplitPane vert = new JSplitPane(JSplitPane.VERTICAL_SPLIT, centerSplit, referencePanel);
                vert.setResizeWeight(0.78);
                vert.setDividerLocation(520);
                vert.setBorder(null);
                root.remove(centerSplit);
                root.add(vert, BorderLayout.CENTER);
                mapRefSplit = vert;
            } else if (scroll != null) {
                JSplitPane vert = new JSplitPane(JSplitPane.VERTICAL_SPLIT, scroll, referencePanel);
                vert.setResizeWeight(0.8);
                vert.setDividerLocation(500);
                root.remove(scroll);
                root.add(vert, BorderLayout.CENTER);
                mapRefSplit = vert;
            } else {
                root.add(referencePanel, BorderLayout.WEST);
            }
            root.revalidate();
        }
    }

    private JPanel buildToolbar() {
        JPanel bar = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 2));
        bar.setBackground(theme.bg);
        // Core only — everything else lives in menus (Tools / View / Height)
        bar.add(themedButton("LOAD", e -> loadMapDialog()));
        bar.add(themedButton("SAVE", e -> saveMapDialog()));
        bar.add(themedButton("PLAY ▶", e -> playtestEngine()));
        bar.add(themedButton("UNDO", e -> undo()));
        bar.add(themedButton("REDO", e -> redo()));
        bar.add(themedButton("ZOOM+", e -> { cellPx = Math.min(48, cellPx + 2); canvas.updateCanvasSize(); refreshAll(); }));
        bar.add(themedButton("ZOOM−", e -> { cellPx = Math.max(4, cellPx - 2); canvas.updateCanvasSize(); refreshAll(); }));
        bar.add(themedButton("BRUSH+", e -> { brushSize = Math.min(9, brushSize + 2); status.setText("  // BRUSH " + brushSize + "x" + brushSize); refreshStatus(); }));
        bar.add(themedButton("BRUSH−", e -> { brushSize = Math.max(1, brushSize - 2); if (brushSize % 2 == 0) brushSize = Math.max(1, brushSize - 1); status.setText("  // BRUSH " + brushSize + "x" + brushSize); refreshStatus(); }));
        bar.add(themedButton("FL ↑", e -> switchFloor(activeFloor + 1)));
        bar.add(themedButton("FL ↓", e -> switchFloor(activeFloor - 1)));
        return bar;
    }

    private void setFloorCountDialog() {
        if (mapType == MapType.OUTDOOR) {
            // outdoor locked
        } else if (false) {
            JOptionPane.showMessageDialog(this, "OUTDOOR maps are locked to 1 floor.\nSwitch to INDOOR to use 1-8 floors.");
            return;
        }
        String v = JOptionPane.showInputDialog(this,
                "Number of floors (1–8, INDOOR only):", String.valueOf(floorCount));
        if (v == null) return;
        try {
            stashActiveFloor();
            floorCount = Math.max(1, Math.min(MAX_FLOORS, Integer.parseInt(v.trim())));
            ensureFloorLayers();
            if (activeFloor >= floorCount) {
                activeFloor = floorCount - 1;
                loadFloorIntoEditBuffer(activeFloor);
            }
            status.setText("  // FLOORS = " + floorCount + " [" + mapType + "] editing F" + (activeFloor + 1));
            refreshAll();
        } catch (Exception ignored) {}
    }

    /** Allocate empty bordered maps for every floor slot. */
    private void ensureFloorLayers() {
        for (int f = 0; f < MAX_FLOORS; f++) {
            if (layerGrids[f] != null && layerGrids[f].length == mapSize) continue;
            layerGrids[f] = new int[mapSize][mapSize];
            layerWallTex[f] = new String[mapSize][mapSize];
            layerFloorTex[f] = new String[mapSize][mapSize];
            layerWallH[f] = new int[mapSize][mapSize];
            layerGroundH[f] = new int[mapSize][mapSize];
            for (int x = 0; x < mapSize; x++) {
                for (int y = 0; y < mapSize; y++) {
                    boolean border = (x == 0 || y == 0 || x == mapSize - 1 || y == mapSize - 1);
                    layerGrids[f][x][y] = border ? 1 : 0;
                    layerWallTex[f][x][y] = "";
                    layerFloorTex[f][x][y] = "";
                    layerWallH[f][x][y] = 2;
                    layerGroundH[f][x][y] = 2;
                }
            }
            if (!hasPlayerStart[f]) {
                playerStartX[f] = mapSize/2.0;
                playerStartY[f] = mapSize/2.0 - 3;
                playerStartDirX[f] = 0;
                playerStartDirY[f] = 1;
            }
        }
    }

    /** Write edit buffer → layer stack for current floor. */
    private void stashActiveFloor() {
        ensureFloorLayers();
        int f = activeFloor;
        if (f < 0 || f >= MAX_FLOORS) return;
        for (int x = 0; x < mapSize; x++) {
            System.arraycopy(grid[x], 0, layerGrids[f][x], 0, mapSize);
            System.arraycopy(wallTextures[x], 0, layerWallTex[f][x], 0, mapSize);
            System.arraycopy(floorTextures[x], 0, layerFloorTex[f][x], 0, mapSize);
            if (wallHeights != null && wallHeights.length == mapSize)
                System.arraycopy(wallHeights[x], 0, layerWallH[f][x], 0, mapSize);
            if (groundHeights != null && groundHeights.length == mapSize)
                System.arraycopy(groundHeights[x], 0, layerGroundH[f][x], 0, mapSize);
        }
    }

    /** Layer stack → edit buffer. */
    private void loadFloorIntoEditBuffer(int f) {
        ensureFloorLayers();
        f = Math.max(0, Math.min(MAX_FLOORS - 1, f));
        for (int x = 0; x < mapSize; x++) {
            System.arraycopy(layerGrids[f][x], 0, grid[x], 0, mapSize);
            System.arraycopy(layerWallTex[f][x], 0, wallTextures[x], 0, mapSize);
            System.arraycopy(layerFloorTex[f][x], 0, floorTextures[x], 0, mapSize);
            if (wallHeights == null || wallHeights.length != mapSize) wallHeights = new int[mapSize][mapSize];
            if (groundHeights == null || groundHeights.length != mapSize) groundHeights = new int[mapSize][mapSize];
            System.arraycopy(layerWallH[f][x], 0, wallHeights[x], 0, mapSize);
            System.arraycopy(layerGroundH[f][x], 0, groundHeights[x], 0, mapSize);
        }
    }

    private void switchFloor(int f) {
        if (f < 0 || f >= floorCount) {
            status.setText("  // Floor out of range (1–" + floorCount + ")");
            return;
        }
        pushUndo();
        stashActiveFloor();
        activeFloor = f;
        loadFloorIntoEditBuffer(activeFloor);
        status.setText("  // EDITING FLOOR " + (activeFloor + 1) + "/" + floorCount);
        refreshAll();
    }

    /** Basic Daggerfall-style terrain: noise walls, open basins, roads, water. */
    private void generateTerrainDialog() {
        String seedStr = JOptionPane.showInputDialog(this, "Terrain seed (integer):", "42");
        if (seedStr == null) return;
        long seed;
        try { seed = Long.parseLong(seedStr.trim()); } catch (Exception e) { seed = 42; }
        pushUndo();
        java.util.Random rng = new java.util.Random(seed);
        // clear interior
        for (int x = 1; x < mapSize - 1; x++) {
            for (int y = 1; y < mapSize - 1; y++) {
                double n = noise2(x * 0.12 + seed * 0.01, y * 0.12, seed);
                if (n > 0.55) {
                    grid[x][y] = 1;
                    wallTextures[x][y] = activeWallBrush.isEmpty() ? "textures/rock.png" : activeWallBrush;
                    floorTextures[x][y] = "";
                } else if (n < 0.18) {
                    grid[x][y] = 4; // water basins
                    floorTextures[x][y] = "floors/water.png";
                    wallTextures[x][y] = "";
                } else {
                    grid[x][y] = 0;
                    floorTextures[x][y] = activeFloorBrush.isEmpty() ? "floors/grass.png" : activeFloorBrush;
                    wallTextures[x][y] = "";
                }
            }
        }
        // border walls
        for (int i = 0; i < mapSize; i++) {
            grid[i][0] = grid[i][mapSize - 1] = grid[0][i] = grid[mapSize - 1][i] = 1;
        }
        // cross roads through center
        int mid = mapSize / 2;
        for (int i = 2; i < mapSize - 2; i++) {
            grid[mid][i] = 5;
            floorTextures[mid][i] = "floors/road.png";
            wallTextures[mid][i] = "";
            grid[i][mid] = 5;
            floorTextures[i][mid] = "floors/road.png";
            wallTextures[i][mid] = "";
        }
        // scatter a few pits
        for (int k = 0; k < mapSize / 8; k++) {
            int x = 2 + rng.nextInt(Math.max(1, mapSize - 4));
            int y = 2 + rng.nextInt(Math.max(1, mapSize - 4));
            if (grid[x][y] == 0) {
                grid[x][y] = 3;
                floorTextures[x][y] = "floors/pit.png";
            }
        }
        playerX = mid + 0.5;
        playerY = mid + 2.5;
        status.setText("  // TERRAIN GENERATED seed=" + seed + " size=" + mapSize);
        refreshAll();
    }

    private static double noise2(double x, double y, long seed) {
        // value noise
        int x0 = (int) Math.floor(x), y0 = (int) Math.floor(y);
        double fx = x - x0, fy = y - y0;
        double v00 = hash(x0, y0, seed), v10 = hash(x0 + 1, y0, seed);
        double v01 = hash(x0, y0 + 1, seed), v11 = hash(x0 + 1, y0 + 1, seed);
        double i1 = v00 * (1 - fx) + v10 * fx;
        double i2 = v01 * (1 - fx) + v11 * fx;
        return i1 * (1 - fy) + i2 * fy;
    }

    private static double hash(int x, int y, long seed) {
        long n = x * 374761393L + y * 668265263L + seed * 1274126177L;
        n = (n ^ (n >> 13)) * 1274126177L;
        return ((n & 0xffff) / 65535.0);
    }

    private void selectSkyboxDialog() {
        File skyDir = new File(texturesDir, "skybox");
        if (!skyDir.isDirectory()) skyDir = assetsDir;
        JFileChooser ch = new JFileChooser(skyDir);
        ch.setDialogTitle("Select Skybox Texture");
        if (ch.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
            selectedSkyboxPath = relativeToAssets(ch.getSelectedFile());
            refreshStatus();
        }
    }

    // ---------- GameMaker Style Tabbed Sidebar ----------

    // ---------- Solarus tileset loader / RPG Maker paint ----------

    /** Map Solarus ground string → CHROMA cell type. */
    static int groundToCell(String ground) {
        if (ground == null) return 0;
        String g = ground.toLowerCase();
        if (g.contains("wall") || g.equals("low_wall") || g.contains("prickle")) return 1;
        if (g.contains("deep_water") || g.contains("shallow_water") || g.equals("water")) return 4;
        if (g.equals("hole") || g.contains("empty")) return 3;
        if (g.contains("ladder") || g.contains("traversable") || g.contains("grass") || g.contains("ice")) return 0;
        if (g.contains("lava")) return 4;
        return 0;
    }

    void scanTilesets() {
        loadedTilesets.clear();
        File root = new File(assetsDir, "tilesets");
        if (!root.isDirectory()) root = new File("assets/tilesets");
        if (!root.isDirectory()) return;
        File[] dirs = root.listFiles(File::isDirectory);
        if (dirs == null) return;
        for (File dir : dirs) {
            File dat = null, img = null;
            File[] files = dir.listFiles();
            if (files == null) continue;
            for (File f : files) {
                String n = f.getName().toLowerCase();
                if (n.endsWith(".dat")) dat = f;
                if (n.endsWith(".tiles.png") || (n.endsWith(".png") && !n.contains("entities"))) img = f;
            }
            // also allow flat: tilesets/name.dat + name.tiles.png
            if (dat == null) {
                File d2 = new File(root, dir.getName() + ".dat");
                if (d2.isFile()) dat = d2;
            }
            if (img == null) {
                File i2 = new File(dir, dir.getName() + ".tiles.png");
                if (i2.isFile()) img = i2;
            }
            if (dat != null && img != null) {
                try {
                    SolarusTileset ts = loadSolarusTileset(dir.getName(), dat, img);
                    if (ts != null && !ts.patterns.isEmpty()) loadedTilesets.add(ts);
                } catch (Exception ex) {
                    System.err.println("[CHROMA] tileset load fail " + dir.getName() + ": " + ex.getMessage());
                }
            }
        }
        // flat files directly under tilesets/
        File[] flat = root.listFiles((d, n) -> n.toLowerCase().endsWith(".dat"));
        if (flat != null) {
            for (File dat : flat) {
                String stem = dat.getName().substring(0, dat.getName().length() - 4);
                File img = new File(root, stem + ".tiles.png");
                if (!img.isFile()) img = new File(root, stem + ".png");
                if (!img.isFile()) continue;
                boolean already = false;
                for (SolarusTileset ts : loadedTilesets) if (ts.name.equals(stem)) already = true;
                if (already) continue;
                try {
                    SolarusTileset ts = loadSolarusTileset(stem, dat, img);
                    if (ts != null && !ts.patterns.isEmpty()) loadedTilesets.add(ts);
                } catch (Exception ignored) {}
            }
        }
        if (!loadedTilesets.isEmpty() && activeTileset == null) activeTileset = loadedTilesets.get(0);
        System.out.println("[CHROMA] Tilesets: " + loadedTilesets.size());
    }

    SolarusTileset loadSolarusTileset(String name, File dat, File img) throws Exception {
        SolarusTileset ts = new SolarusTileset();
        ts.name = name;
        ts.datFile = dat;
        ts.imageFile = img;
        ts.atlas = javax.imageio.ImageIO.read(img);
        if (ts.atlas == null) return null;
        String text = new String(java.nio.file.Files.readAllBytes(dat.toPath()), java.nio.charset.StandardCharsets.UTF_8);
        // Parse tile_pattern { ... } blocks
        java.util.regex.Pattern block = java.util.regex.Pattern.compile(
                "tile_pattern\\s*\\{([^}]*)\\}", java.util.regex.Pattern.CASE_INSENSITIVE | java.util.regex.Pattern.DOTALL);
        java.util.regex.Matcher m = block.matcher(text);
        while (m.find()) {
            String body = m.group(1);
            TilePattern tp = new TilePattern();
            tp.id = extractStr(body, "id");
            tp.ground = extractStr(body, "ground");
            tp.layer = extractInt(body, "default_layer", 0);
            // x/y may be scalar or { a, b, c }
            int[] xs = extractIntOrFirst(body, "x");
            int[] ys = extractIntOrFirst(body, "y");
            tp.x = xs[0]; tp.y = ys[0];
            tp.w = extractInt(body, "width", 8);
            tp.h = extractInt(body, "height", 8);
            if (tp.id == null || tp.w <= 0 || tp.h <= 0) continue;
            // crop (clamp to atlas)
            int cx = Math.max(0, Math.min(ts.atlas.getWidth() - 1, tp.x));
            int cy = Math.max(0, Math.min(ts.atlas.getHeight() - 1, tp.y));
            int cw = Math.min(tp.w, ts.atlas.getWidth() - cx);
            int ch = Math.min(tp.h, ts.atlas.getHeight() - cy);
            if (cw <= 0 || ch <= 0) continue;
            try {
                tp.image = ts.atlas.getSubimage(cx, cy, cw, ch);
            } catch (Exception ex) { continue; }
            tp.cellType = groundToCell(tp.ground);
            ts.patterns.put(tp.id, tp);
        }
        return ts;
    }

    static String extractStr(String body, String key) {
        java.util.regex.Matcher m = java.util.regex.Pattern.compile(
                key + "\\s*=\\s*\"([^\"]*)\"", java.util.regex.Pattern.CASE_INSENSITIVE).matcher(body);
        return m.find() ? m.group(1) : null;
    }
    static int extractInt(String body, String key, int def) {
        java.util.regex.Matcher m = java.util.regex.Pattern.compile(
                key + "\\s*=\\s*(\\d+)", java.util.regex.Pattern.CASE_INSENSITIVE).matcher(body);
        return m.find() ? Integer.parseInt(m.group(1)) : def;
    }
    /** Handles x = 56 or x = { 0, 16, 32 }. Returns first value. */
    static int[] extractIntOrFirst(String body, String key) {
        java.util.regex.Matcher brace = java.util.regex.Pattern.compile(
                key + "\\s*=\\s*\\{\\s*(\\d+)", java.util.regex.Pattern.CASE_INSENSITIVE).matcher(body);
        if (brace.find()) return new int[]{ Integer.parseInt(brace.group(1)) };
        return new int[]{ extractInt(body, key, 0) };
    }

    String tilesetRef(SolarusTileset ts, TilePattern tp) {
        return "tileset:" + ts.name + ":" + tp.id;
    }

    TilePattern resolveTilesetRef(String ref) {
        if (ref == null || !ref.startsWith("tileset:")) return null;
        String[] p = ref.split(":", 3);
        if (p.length < 3) return null;
        for (SolarusTileset ts : loadedTilesets) {
            if (ts.name.equals(p[1])) return ts.patterns.get(p[2]);
        }
        return null;
    }

    private JPanel buildTilesetTab() {
        JPanel p = createTabPanel();
        p.add(legendHeader("TILESETS (Solarus / RM style)"));
        JButton reload = themedButton("RESCAN TILESETS", e -> {
            scanTilesets();
            sideTabPane = buildGameMakerSidebar();
            // replace east sidebar
            java.awt.Container root = getContentPane();
            // find and replace tab pane
            status.setText("  // Tilesets: " + loadedTilesets.size());
            // rebuild UI simply by notifying user to switch tab after rescan rebuild
            rebuildSidebar();
        });
        reload.setAlignmentX(Component.LEFT_ALIGNMENT);
        p.add(reload);
        p.add(Box.createVerticalStrut(4));

        if (loadedTilesets.isEmpty()) {
            JLabel empty = new JLabel("<html><body style='width:210px'>No tilesets found.<br>Place under assets/tilesets/&lt;name&gt;/<br>name.dat + name.tiles.png</body></html>");
            empty.setForeground(theme.fg);
            empty.setFont(new Font("Monospaced", Font.PLAIN, 10));
            empty.setAlignmentX(Component.LEFT_ALIGNMENT);
            p.add(empty);
            p.add(Box.createVerticalGlue());
            return p;
        }

        // Tileset selector
        String[] names = loadedTilesets.stream().map(ts -> ts.name).toArray(String[]::new);
        JComboBox<String> combo = new JComboBox<>(names);
        if (activeTileset != null) combo.setSelectedItem(activeTileset.name);
        combo.setAlignmentX(Component.LEFT_ALIGNMENT);
        combo.setMaximumSize(new Dimension(Integer.MAX_VALUE, 28));
        combo.addActionListener(e -> {
            String sel = (String) combo.getSelectedItem();
            for (SolarusTileset ts : loadedTilesets) if (ts.name.equals(sel)) activeTileset = ts;
            rebuildSidebar();
        });
        p.add(combo);
        p.add(Box.createVerticalStrut(6));

        if (activeTileset == null) activeTileset = loadedTilesets.get(0);

        JLabel info = new JLabel(activeTileset.patterns.size() + " tiles — click to paint");
        info.setFont(new Font("Monospaced", Font.PLAIN, 10));
        info.setForeground(theme.fg);
        info.setAlignmentX(Component.LEFT_ALIGNMENT);
        p.add(info);
        p.add(Box.createVerticalStrut(4));

        // Grid of tile thumbnails
        JPanel gridPanel = new JPanel(new GridLayout(0, 4, 3, 3));
        gridPanel.setBackground(theme.panel);
        gridPanel.setAlignmentX(Component.LEFT_ALIGNMENT);
        for (TilePattern tp : activeTileset.patterns.values()) {
            JLabel cell = new JLabel();
            cell.setOpaque(true);
            cell.setBackground(theme.bg);
            cell.setHorizontalAlignment(SwingConstants.CENTER);
            cell.setPreferredSize(new Dimension(40, 40));
            cell.setToolTipText(tp.id + " [" + tp.ground + "] → cell " + tp.cellType);
            if (tp.image != null) {
                Image scaled = tp.image.getScaledInstance(32, 32, Image.SCALE_FAST);
                cell.setIcon(new ImageIcon(scaled));
            } else {
                cell.setText("?");
                cell.setForeground(theme.fg);
            }
            if (activeTilePattern == tp) {
                cell.setBorder(new LineBorder(Color.YELLOW, 2));
            } else {
                cell.setBorder(new LineBorder(theme.fg.darker(), 1));
            }
            final TilePattern pick = tp;
            cell.setCursor(new Cursor(Cursor.HAND_CURSOR));
            cell.addMouseListener(new MouseAdapter() {
                public void mousePressed(MouseEvent e) {
                    activeTilePattern = pick;
                    activeTileset = activeTileset;
                    currentTool = "TILESET";
                    tilesetPaintMode = true;
                    // also set legacy brushes so non-tileset tools stay coherent
                    if (pick.cellType == 1) {
                        activeWallBrush = tilesetRef(activeTileset, pick);
                    } else {
                        activeFloorBrush = tilesetRef(activeTileset, pick);
                    }
                    status.setText("  // TILE: " + pick.id + " (" + pick.ground + ") cell=" + pick.cellType);
                    rebuildSidebar();
                }
            });
            gridPanel.add(cell);
        }
        p.add(gridPanel);
        p.add(Box.createVerticalGlue());
        return p;
    }

    private void rebuildSidebar() {
        if (sideTabPane == null) return;
        int idx = sideTabPane.getSelectedIndex();
        java.awt.Container parent = sideTabPane.getParent();
        if (parent == null) return;
        parent.remove(sideTabPane);
        sideTabPane = buildGameMakerSidebar();
        if (parent.getLayout() instanceof BorderLayout) {
            parent.add(sideTabPane, BorderLayout.EAST);
        } else {
            parent.add(sideTabPane);
        }
        if (idx >= 0 && idx < sideTabPane.getTabCount()) sideTabPane.setSelectedIndex(idx);
        parent.revalidate();
        parent.repaint();
    }

    private JTabbedPane buildGameMakerSidebar() {
        JTabbedPane tabs = new JTabbedPane();
        tabs.setBackground(theme.panel);
        tabs.setForeground(theme.fg);
        int fs = options != null ? options.uiFontSize : 12;
        tabs.setFont(new Font("Monospaced", Font.BOLD, fs));
        tabs.setPreferredSize(new Dimension(options != null && options.largeButtons ? 300 : 260, 0));

        // Each tab scrolls so large texture lists stay usable
        tabs.addTab("Tileset", wrapSidebarScroll(buildTilesetTab()));
        tabs.addTab("Brushes", wrapSidebarScroll(buildBrushesTab()));
        tabs.addTab("Entities", wrapSidebarScroll(buildEntityTab()));
        tabs.addTab("Doors", wrapSidebarScroll(buildDoorsTab()));
        tabs.addTab("Enemies", wrapSidebarScroll(buildEnemiesTab()));
        tabs.addTab("Lights", wrapSidebarScroll(buildLightsTab()));
        tabs.addTab("Weather", wrapSidebarScroll(buildWeatherTab()));
        tabs.addTab("Shapes", wrapSidebarScroll(buildShapeTab()));

        return tabs;
    }

    /** Archetype catalog — name matches engine applyEnemyArchetype(). */
    private static final String[][] ENEMY_ARCHETYPES = {
            {"slime", "Weak slow slime", "2hp"},
            {"greenblob", "Weak blob", "2hp"},
            {"magmaslime", "Hot contact", "4hp"},
            {"elecslime", "Shock slime", "4hp"},
            {"ghost", "Floaty wander", "3hp"},
            {"shyghost", "Flees player", "3hp"},
            {"elecghost", "Shock ghost", "4hp"},
            {"skull", "Mid fighter", "4hp"},
            {"elecskull", "Shock skull", "4hp"},
            {"soldier", "Tough mid", "5hp"},
            {"hardsoldier", "Drops boss key once", "8hp"},
            {"cactus", "Stationary thorns", "5hp"},
            {"centipede", "Fast path", "4hp"},
            {"worm1", "Fast crawl", "4hp"},
            {"jellyfish", "Wide float", "3hp"},
            {"eyeball", "Wide watch", "3hp"},
            {"poisonshroom", "Slow fungus", "3hp"},
    };

    private JPanel buildEnemiesTab() {
        JPanel p = createTabPanel();
        p.add(legendHeader("ARCHETYPES (click = place)"));
        JLabel hint = new JLabel("<html><body style='width:200px'>Picks matching PNG from assets/enemy. Then set AREA/PATH.</body></html>");
        hint.setFont(new Font("Monospaced", Font.PLAIN, 10));
        hint.setForeground(theme.fg.darker());
        hint.setAlignmentX(Component.LEFT_ALIGNMENT);
        p.add(hint);
        p.add(Box.createVerticalStrut(4));

        File enemyDir = new File(assetsDir, "enemy");
        for (String[] arch : ENEMY_ARCHETYPES) {
            String stem = arch[0];
            File png = new File(enemyDir, stem + ".png");
            if (!png.isFile()) {
                // try any file containing stem
                File[] all = enemyDir.isDirectory() ? enemyDir.listFiles() : null;
                if (all != null) {
                    for (File f : all) {
                        if (f.getName().toLowerCase().contains(stem) && f.getName().toLowerCase().endsWith(".png")) {
                            png = f; break;
                        }
                    }
                }
            }
            final File spriteFile = png.isFile() ? png : null;
            JPanel row = new JPanel(new BorderLayout(4, 0));
            row.setBackground(theme.panel);
            row.setAlignmentX(Component.LEFT_ALIGNMENT);
            row.setMaximumSize(new Dimension(Integer.MAX_VALUE, 36));
            row.setCursor(new Cursor(Cursor.HAND_CURSOR));
            if (spriteFile != null) {
                BufferedImage th = getThumb("enemy/" + spriteFile.getName());
                if (th != null) {
                    JLabel ic = new JLabel(new ImageIcon(th.getScaledInstance(28, 28, Image.SCALE_SMOOTH)));
                    row.add(ic, BorderLayout.WEST);
                }
            }
            JLabel lbl = new JLabel(stem.toUpperCase() + "  —  " + arch[1] + " (" + arch[2] + ")");
            lbl.setFont(new Font("Monospaced", Font.PLAIN, 10));
            lbl.setForeground(spriteFile != null ? theme.fg : Color.GRAY);
            row.add(lbl, BorderLayout.CENTER);
            row.setBorder(new LineBorder(theme.bg, 1));
            row.addMouseListener(new MouseAdapter() {
                public void mousePressed(MouseEvent e) {
                    currentTool = "ENEMY";
                    if (spriteFile != null) {
                        // stash preferred asset for next place
                        pendingEnemyAsset = relativeToAssets(spriteFile);
                    } else {
                        pendingEnemyAsset = null;
                    }
                    status.setText("  // ENEMY ARCHETYPE: " + stem + " — click map to place");
                }
            });
            p.add(row);
            p.add(Box.createVerticalStrut(2));
        }
        p.add(Box.createVerticalGlue());
        return p;
    }

    private String pendingEnemyAsset = null;

    private JPanel buildLightsTab() {
        JPanel p = createTabPanel();
        p.add(legendHeader("LIGHT SOURCES"));
        JLabel tip = new JLabel("<html><body style='width:200px'>TORCH soft glow. LIGHT stronger radius. Saved as things.</body></html>");
        tip.setFont(new Font("Monospaced", Font.PLAIN, 10));
        tip.setForeground(theme.fg);
        tip.setAlignmentX(Component.LEFT_ALIGNMENT);
        p.add(tip);
        p.add(Box.createVerticalStrut(6));
        p.add(legendRow("TORCH", legendTint("TORCH"), "TO"));
        p.add(legendRow("LIGHT", new Color(255, 230, 120), "LI"));
        p.add(Box.createVerticalStrut(8));
        p.add(legendHeader("DEFAULT RADIUS"));
        JLabel rHint = new JLabel("Torch~3  Light~5 (engine)");
        rHint.setFont(new Font("Monospaced", Font.PLAIN, 10));
        rHint.setForeground(theme.fg);
        rHint.setAlignmentX(Component.LEFT_ALIGNMENT);
        p.add(rHint);
        p.add(Box.createVerticalGlue());
        return p;
    }

    private JPanel buildWeatherTab() {
        JPanel p = createTabPanel();
        p.add(legendHeader("MAP WEATHER"));
        JLabel fogLbl = new JLabel("Fog strength: " + String.format("%.2f", mapFog));
        fogLbl.setFont(new Font("Monospaced", Font.BOLD, 11));
        fogLbl.setForeground(theme.fg);
        fogLbl.setAlignmentX(Component.LEFT_ALIGNMENT);
        p.add(fogLbl);
        JSlider fogSlider = new JSlider(0, 100, (int) (mapFog * 100));
        fogSlider.setAlignmentX(Component.LEFT_ALIGNMENT);
        fogSlider.setMaximumSize(new Dimension(Integer.MAX_VALUE, 40));
        fogSlider.setBackground(theme.panel);
        fogSlider.addChangeListener(e -> {
            mapFog = fogSlider.getValue() / 100.0;
            fogLbl.setText("Fog strength: " + String.format("%.2f", mapFog));
            refreshStatus();
        });
        p.add(fogSlider);
        p.add(Box.createVerticalStrut(10));
        JCheckBox rainBox = new JCheckBox("Rain overlay", mapRain);
        rainBox.setFont(new Font("Monospaced", Font.BOLD, 12));
        rainBox.setForeground(theme.fg);
        rainBox.setBackground(theme.panel);
        rainBox.setAlignmentX(Component.LEFT_ALIGNMENT);
        rainBox.addActionListener(e -> { mapRain = rainBox.isSelected(); refreshStatus(); });
        p.add(rainBox);
        p.add(Box.createVerticalStrut(10));
        JLabel note = new JLabel("<html><body style='width:200px'>Saved into .map as FOG and RAIN. Engine applies on load.</body></html>");
        note.setFont(new Font("Monospaced", Font.PLAIN, 10));
        note.setForeground(theme.fg);
        note.setAlignmentX(Component.LEFT_ALIGNMENT);
        p.add(note);
        p.add(Box.createVerticalGlue());
        return p;
    }

    /** Vertical scroll for sidebar content — shows all brushes / entities. */
    private JScrollPane wrapSidebarScroll(JPanel content) {
        // Prefer top-aligned content inside scroll view
        JPanel holder = new JPanel(new BorderLayout());
        holder.setBackground(theme.panel);
        holder.add(content, BorderLayout.NORTH);

        JScrollPane sp = new JScrollPane(holder);
        sp.setBorder(null);
        sp.getViewport().setBackground(theme.panel);
        sp.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        sp.setVerticalScrollBarPolicy(ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED);
        sp.getVerticalScrollBar().setUnitIncrement(16);
        sp.getVerticalScrollBar().setBlockIncrement(64);
        // Wider scrollbar for accessibility / older mice
        sp.getVerticalScrollBar().setPreferredSize(new Dimension(14, 0));
        return sp;
    }

    private JPanel buildBrushesTab() {
        JPanel p = createTabPanel();
        
        // Wall Brushes
        p.add(legendHeader("WALL BRUSHES"));
        JButton addWallBtn = themedButton("+ ADD WALL BRUSH", e -> pickAndAddBrush(true));
        addWallBtn.setAlignmentX(Component.LEFT_ALIGNMENT);
        p.add(addWallBtn);
        p.add(Box.createVerticalStrut(4));
        p.add(buildBrushPaletteGrid(wallBrushes, true));

        p.add(Box.createVerticalStrut(10));

        // Floor Brushes
        p.add(legendHeader("FLOOR BRUSHES"));
        JButton addFloorBtn = themedButton("+ ADD FLOOR BRUSH", e -> pickAndAddBrush(false));
        addFloorBtn.setAlignmentX(Component.LEFT_ALIGNMENT);
        p.add(addFloorBtn);
        p.add(Box.createVerticalStrut(4));
        p.add(buildBrushPaletteGrid(floorBrushes, false));

        p.add(Box.createVerticalStrut(10));
        p.add(legendHeader("WALL TINT"));
        p.add(wallTintRow());
        p.add(Box.createVerticalGlue());
        return p;
    }

    private JPanel buildBrushPaletteGrid(List<String> brushes, boolean isWall) {
        JPanel panel = new JPanel(new GridLayout(0, 3, 4, 4));
        panel.setBackground(theme.panel);
        panel.setAlignmentX(Component.LEFT_ALIGNMENT);
        // Let BoxLayout honor preferred height so parent scroll works
        int rows = 1 + (brushes.size() + 2) / 3; // default tile + brushes
        panel.setMaximumSize(new Dimension(Integer.MAX_VALUE, rows * 56));
        panel.setPreferredSize(new Dimension(220, rows * 56));

        // Default erase/solid swatch
        JPanel defaultTile = new JPanel(new BorderLayout());
        defaultTile.setPreferredSize(new Dimension(50, 50));
        defaultTile.setBackground(theme.bg);
        defaultTile.setBorder(new LineBorder(theme.fg, 1));
        JLabel defLbl = new JLabel("DEFAULT", SwingConstants.CENTER);
        defLbl.setFont(new Font("Monospaced", Font.BOLD, 9));
        defLbl.setForeground(theme.fg);
        defaultTile.add(defLbl, BorderLayout.CENTER);
        defaultTile.setCursor(new Cursor(Cursor.HAND_CURSOR));
        defaultTile.addMouseListener(new MouseAdapter() {
            public void mousePressed(MouseEvent e) {
                if (isWall) { currentTool = "WALL"; activeWallBrush = ""; }
                else { currentTool = "FLOOR"; activeFloorBrush = ""; }
                refreshStatus();
            }
        });
        panel.add(defaultTile);

        // Image Brushes
        for (String relPath : brushes) {
            JPanel tile = new JPanel(new BorderLayout());
            tile.setPreferredSize(new Dimension(50, 50));
            tile.setBackground(theme.bg);
            tile.setBorder(new LineBorder(theme.fg, 1));

            BufferedImage img = getThumb(relPath);
            if (img != null) {
                JLabel imgLbl = new JLabel(new ImageIcon(img.getScaledInstance(46, 46, Image.SCALE_SMOOTH)));
                tile.add(imgLbl, BorderLayout.CENTER);
            }
            tile.setCursor(new Cursor(Cursor.HAND_CURSOR));
            tile.addMouseListener(new MouseAdapter() {
                public void mousePressed(MouseEvent e) {
                    if (isWall) { currentTool = "WALL"; activeWallBrush = relPath; }
                    else { currentTool = "FLOOR"; activeFloorBrush = relPath; }
                    refreshStatus();
                }
            });
            panel.add(tile);
        }
        return panel;
    }

    private void pickAndAddBrush(boolean isWall) {
        JFileChooser ch = new JFileChooser(texturesDir.isDirectory() ? texturesDir : assetsDir);
        ch.setFileFilter(new javax.swing.filechooser.FileFilter() {
            public boolean accept(File f) {
                if (f.isDirectory()) return true;
                String n = f.getName().toLowerCase();
                if (!(n.endsWith(".png") || n.endsWith(".jpg") || n.endsWith(".jpeg") || n.endsWith(".gif"))) return false;
                return isDiffuseTextureName(n);
            }
            public String getDescription() { return "Diffuse textures (skips NORM/AO/DISP/SPEC)"; }
        });
        ch.setDialogTitle(isWall ? "Select Wall Texture Image" : "Select Floor Texture Image");
        ch.setFileFilter(new FileNameExtensionFilter("Images (png, jpg)", "png", "jpg", "jpeg"));
        if (ch.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
            String rel = relativeToAssets(ch.getSelectedFile());
            if (rel != null && !rel.isEmpty()) {
                if (isWall) {
                    if (!wallBrushes.contains(rel)) wallBrushes.add(rel);
                    activeWallBrush = rel;
                    currentTool = "WALL";
                } else {
                    if (!floorBrushes.contains(rel)) floorBrushes.add(rel);
                    activeFloorBrush = rel;
                    currentTool = "FLOOR";
                }
                rebuildSidebar();
                refreshStatus();
            }
        }
    }

    /** When true, canvas only draws / selects entities on activeFloor (ghosts still optional). */
    private boolean filterEntitiesToActiveFloor = true;

    private int countEntitiesOnFloor(int f) {
        int n = 0;
        for (Entity e : things.values()) if (e.floorIndex == f) n++;
        return n;
    }

    private JPanel buildEntityTab() {
        JPanel p = createTabPanel();

        // --- Per-floor filter / badge (checklist #13) ---
        p.add(legendHeader("FLOOR FILTER"));
        JLabel floorBadge = new JLabel("Editing F" + (activeFloor + 1) + "/" + floorCount
                + "  ·  entities here: " + countEntitiesOnFloor(activeFloor)
                + "  ·  total: " + things.size());
        floorBadge.setFont(new Font("Monospaced", Font.PLAIN, 11));
        floorBadge.setForeground(theme.fg);
        floorBadge.setAlignmentX(Component.LEFT_ALIGNMENT);
        floorBadge.setName("floorBadge");
        p.add(floorBadge);

        JCheckBox filterBox = new JCheckBox("Show only this floor", filterEntitiesToActiveFloor);
        filterBox.setOpaque(false);
        filterBox.setForeground(theme.fg);
        filterBox.setFont(new Font("Monospaced", Font.PLAIN, 11));
        filterBox.setAlignmentX(Component.LEFT_ALIGNMENT);
        filterBox.addActionListener(e -> {
            filterEntitiesToActiveFloor = filterBox.isSelected();
            if (canvas != null) canvas.repaint();
            refreshStatus();
        });
        p.add(filterBox);

        JButton reassignBtn = new JButton("Set selected / all visible → F" + (activeFloor + 1));
        reassignBtn.setFont(new Font("Monospaced", Font.PLAIN, 10));
        reassignBtn.setAlignmentX(Component.LEFT_ALIGNMENT);
        reassignBtn.addActionListener(e -> {
            pushUndo();
            int n = 0;
            for (Entity ent : things.values()) {
                // reassign everything currently visible under filter, or all if filter off
                if (!filterEntitiesToActiveFloor || ent.floorIndex == activeFloor) {
                    // when filter is on and they match, no-op; when filter off, assign all to active
                    if (!filterEntitiesToActiveFloor) {
                        ent.floorIndex = activeFloor;
                        n++;
                    }
                }
            }
            if (!filterEntitiesToActiveFloor) {
                status.setText("  // Reassigned " + n + " entities → floor " + (activeFloor + 1));
            } else {
                // Offer explicit "move all on other floors here" via confirm
                int moved = 0;
                for (Entity ent : things.values()) {
                    if (ent.floorIndex != activeFloor) {
                        ent.floorIndex = activeFloor;
                        moved++;
                    }
                }
                status.setText("  // Moved " + moved + " entities onto floor " + (activeFloor + 1));
            }
            floorBadge.setText("Editing F" + (activeFloor + 1) + "/" + floorCount
                    + "  ·  entities here: " + countEntitiesOnFloor(activeFloor)
                    + "  ·  total: " + things.size());
            if (canvas != null) canvas.repaint();
        });
        p.add(reassignBtn);
        p.add(Box.createVerticalStrut(6));

        p.add(legendHeader("PLAYER SPAWN"));
        p.add(legendRow("PLAYER", theme.fg, "P>"));
        p.add(Box.createVerticalStrut(4));
        p.add(legendHeader("CHESTS (F open)"));
        p.add(legendRow("CHESTSM", legendTint("CHESTSM"), "cs"));
        p.add(legendRow("CHESTBG", legendTint("CHESTBG"), "CB"));
        p.add(Box.createVerticalStrut(4));
        p.add(legendHeader("BREAK / TOSS"));
        p.add(legendRow("JAR", legendTint("JAR"), "JR"));
        p.add(legendRow("BOX", legendTint("BOX"), "BX"));
        p.add(legendRow("SMBOULDER", legendTint("SMBOULDER"), "sb"));
        p.add(Box.createVerticalStrut(4));
        p.add(legendHeader("BOMB ONLY"));
        p.add(legendRow("BIGBOULDER", legendTint("BIGBOULDER"), "BB"));
        p.add(legendRow("CRACKEDWALL", legendTint("CRACKEDWALL"), "CW"));
        p.add(Box.createVerticalStrut(4));
        p.add(legendHeader("ACTORS / WORLD"));
        p.add(legendRow("ENEMY", legendTint("ENEMY"), "EN"));
        p.add(legendRow("HOOKTARGET", new Color(220, 180, 80), "HK"));
        // Stairs/doors live on the Doors tab
        p.add(legendRow("TV", new Color(80, 200, 255), "TV"));
        p.add(legendRow("BROWSER", new Color(80, 180, 255), "BR"));
        p.add(legendRow("SCREEN", new Color(100, 160, 255), "SC"));
        p.add(legendRow("PIT", legendTint("PIT"), "PI"));
        p.add(legendRow("TREE", legendTint("TREE"), "TR"));
        p.add(legendRow("TORCH", legendTint("TORCH"), "TO"));
        p.add(Box.createVerticalStrut(4));
        p.add(legendHeader("COLLECT / TEXT"));
        p.add(legendRow("BIGITEM", legendTint("BIGITEM"), "BI"));
        p.add(legendRow("TEXTBOARD", legendTint("TEXTBOARD"), "TX"));
        p.add(Box.createVerticalStrut(4));
        p.add(legendHeader("SPRITE OVERRIDE"));
        p.add(entityTextureRow());
        p.add(Box.createVerticalGlue());
        return p;
    }

    /**
     * Entrances, key-doors, stairs, ladders — clearer for new authors than burying under Entities.
     * Labels match engine lockKind / floor transitions.
     */
    private JPanel buildDoorsTab() {
        JPanel p = createTabPanel();
        p.add(legendHeader("KEY DOORS (F / interact)"));
        p.add(legendRow("SMDOOR", new Color(180, 180, 200), "sD"));   // silver / small key
        p.add(legendRow("BGDOOR", new Color(220, 180, 60), "BD"));    // big / boss-chest key style
        p.add(legendRow("BSDOOR", new Color(200, 80, 80), "bD"));     // boss key
        p.add(legendRow("FBDOOR", new Color(160, 60, 200), "FD"));    // final boss door
        p.add(Box.createVerticalStrut(6));
        p.add(legendHeader("STAIRS (auto floor change)"));
        p.add(legendRow("STAIRSUP", new Color(140, 200, 255), "SU"));
        p.add(legendRow("STAIRSDOWN", new Color(100, 140, 220), "SD"));
        p.add(legendRow("STAIRS", new Color(180, 180, 255), "ST")); // generic cycle
        p.add(Box.createVerticalStrut(6));
        p.add(legendHeader("LADDERS"));
        p.add(legendRow("LADDERUP", new Color(160, 120, 60), "LU"));
        p.add(legendRow("LADDERDOWN", new Color(120, 90, 40), "LD"));
        p.add(Box.createVerticalStrut(6));
        p.add(legendHeader("MAP LINKS"));
        p.add(legendRow("ENTRANCE", new Color(80, 220, 120), "EN"));
        p.add(legendRow("EXIT", new Color(220, 100, 80), "EX"));
        p.add(Box.createVerticalStrut(8));
        JLabel tip = new JLabel("<html><body style='width:200px;color:#9ab'>"
                + "SMDOOR = silver key · BGDOOR = big key · BSDOOR = boss key · FBDOOR = final key<br/>"
                + "STAIRSUP / LADDERUP go to higher floor · DOWN to lower<br/>"
                + "Place while on the floor the player should use them from."
                + "</body></html>");
        tip.setFont(new Font("SansSerif", Font.PLAIN, 11));
        p.add(tip);
        p.add(Box.createVerticalGlue());
        return p;
    }

    private JPanel buildShapeTab() {
        JPanel p = createTabPanel();
        p.add(legendHeader("DRAWING TOOLS"));
        p.add(legendRow("FILL", Color.CYAN, "FL"));
        p.add(legendRow("RECT", Color.ORANGE, "RC"));
        p.add(legendRow("LINE", Color.YELLOW, "LN"));
        p.add(legendRow("COPY/STAMP", Color.MAGENTA, "CP"));
        p.add(Box.createVerticalStrut(8));
        p.add(legendHeader("WORLD CELLS"));
        p.add(legendRow("DOOR", new Color(180, 120, 60), "DR"));     // grid 2
        p.add(legendRow("PITCELL", new Color(30, 30, 30), "PT"));     // grid 3 lethal
        p.add(legendRow("WATER", new Color(40, 120, 220), "WA"));     // grid 4 slows
        p.add(legendRow("ROAD", new Color(90, 90, 90), "RD"));         // grid 5 paths
        p.add(legendRow("FAKEWALL", new Color(140, 100, 160), "FW"));  // grid 6 secret
        p.add(legendRow("FAKEFLOOR", new Color(100, 40, 40), "FF"));   // grid 7 trap
        p.add(Box.createVerticalStrut(8));
        p.add(legendHeader("HEIGHT (depth)"));
        p.add(legendRow("WALLH", new Color(200, 120, 255), "WH"));
        p.add(legendRow("GROUNDH", new Color(120, 200, 255), "GH"));
        JLabel hHint = new JLabel("<html><body style='width:200px'>WallH 1–4 (2=norm). GroundH 0–3 (2=def). Paint after selecting level.</body></html>");
        hHint.setFont(new Font("Monospaced", Font.PLAIN, 10));
        hHint.setForeground(theme.fg);
        hHint.setAlignmentX(Component.LEFT_ALIGNMENT);
        p.add(hHint);
        p.add(Box.createVerticalGlue());
        return p;
    }

    private JPanel createTabPanel() {
        JPanel p = new JPanel();
        p.setLayout(new BoxLayout(p, BoxLayout.Y_AXIS));
        p.setBackground(theme.panel);
        p.setBorder(new EmptyBorder(6, 6, 6, 6));
        return p;
    }

    private JLabel legendHeader(String text) {
        JLabel l = new JLabel(text);
        int fs = options != null ? options.uiFontSize : 12;
        l.setFont(new Font("Monospaced", Font.BOLD, fs));
        l.setForeground(theme.fg);
        l.setBorder(new EmptyBorder(4, 2, 4, 2));
        l.setAlignmentX(Component.LEFT_ALIGNMENT);
        return l;
    }

    private JPanel entityTextureRow() {
        JPanel row = new JPanel(new BorderLayout(4, 0));
        row.setBackground(theme.panel);
        row.setAlignmentX(Component.LEFT_ALIGNMENT);
        row.setMaximumSize(new Dimension(Integer.MAX_VALUE, 26));

        JButton b = new JButton("ASSIGN SPRITE");
        b.setFont(new Font("Monospaced", Font.BOLD, 10));
        b.setForeground(Color.WHITE);
        b.setBackground(new Color(180, 30, 30));
        b.setFocusable(false);
        b.setBorder(new LineBorder(new Color(255, 80, 80), 1));
        b.addActionListener(e -> { currentTool = "ENTITY_TEX"; refreshStatus(); });
        row.add(b, BorderLayout.CENTER);
        return row;
    }

    private JPanel wallTintRow() {
        JPanel row = new JPanel(new BorderLayout(6, 0));
        row.setBackground(theme.panel);
        row.setAlignmentX(Component.LEFT_ALIGNMENT);
        row.setMaximumSize(new Dimension(Integer.MAX_VALUE, 28));
        row.setCursor(new Cursor(Cursor.HAND_CURSOR));

        JPanel sw = new JPanel();
        sw.setBackground(paintTintColor != null ? paintTintColor : Color.DARK_GRAY);
        sw.setPreferredSize(new Dimension(24, 18));
        sw.setBorder(new LineBorder(Color.BLACK, 1));

        JLabel lbl = new JLabel("PICK TINT");
        lbl.setFont(new Font("Monospaced", Font.BOLD, 11));
        lbl.setForeground(theme.fg);

        row.add(sw, BorderLayout.WEST);
        row.add(lbl, BorderLayout.CENTER);
        row.addMouseListener(new MouseAdapter() {
            public void mousePressed(MouseEvent e) {
                Color c = JColorChooser.showDialog(SFMapEditor.this, "Wall Tint Color", paintTintColor != null ? paintTintColor : Color.WHITE);
                if (c != null) { paintTintColor = c; currentTool = "TINT"; sw.setBackground(c); refreshStatus(); }
            }
        });
        return row;
    }

    private JPanel legendRow(String label, Color swatch, String abbrev) {
        JPanel row = new JPanel(new BorderLayout(6, 0));
        row.setBackground(theme.panel);
        row.setBorder(new EmptyBorder(2, 4, 2, 4));
        row.setAlignmentX(Component.LEFT_ALIGNMENT);
        row.setMaximumSize(new Dimension(Integer.MAX_VALUE, 26));
        row.setCursor(new Cursor(Cursor.HAND_CURSOR));

        JPanel sw = new JPanel();
        sw.setBackground(swatch);
        sw.setPreferredSize(new Dimension(20, 16));
        sw.setBorder(new LineBorder(Color.BLACK, 1));

        JLabel lbl = new JLabel(label);
        lbl.setFont(new Font("Monospaced", Font.BOLD, 11));
        lbl.setForeground(theme.fg);

        row.add(sw, BorderLayout.WEST);
        row.add(lbl, BorderLayout.CENTER);

        row.addMouseListener(new MouseAdapter() {
            public void mousePressed(MouseEvent e) { currentTool = label; refreshStatus(); }
            public void mouseEntered(MouseEvent e) { row.setBackground(theme.bg); }
            public void mouseExited(MouseEvent e) { row.setBackground(theme.panel); }
        });
        return row;
    }

    private void cycleTheme() {
        Theme[] all = Theme.values();
        theme = all[(theme.ordinal() + 1) % all.length];
        getContentPane().setBackground(theme.bg);
        if (canvasWrapper != null) canvasWrapper.setBackground(theme.bg);
        if (scroll != null) scroll.getViewport().setBackground(theme.bg);
        status.setForeground(theme.fg);
        rebuildSidebar();
        refreshAll();
    }

        private JButton themedButton(String text, ActionListener al) {
        JButton b = new JButton(text);
        int fs = options != null ? options.uiFontSize : 12;
        b.setFont(new Font("Monospaced", Font.BOLD, fs));
        b.setForeground(theme.fg);
        b.setBackground(theme.bg);
        b.setFocusable(false);
        b.setBorder(new LineBorder(theme.fg, options != null && options.largeButtons ? 2 : 1));
        b.setCursor(new Cursor(Cursor.HAND_CURSOR));
        if (options != null && options.largeButtons) b.setMargin(new Insets(6, 10, 6, 10));
        b.addActionListener(al);
        b.addMouseListener(new MouseAdapter() {
            public void mouseEntered(MouseEvent e) { b.setBackground(theme.panel); }
            public void mouseExited(MouseEvent e) { b.setBackground(theme.bg); }
        });
        return b;
    }

    private void bindShortcuts(JComponent root) {
        InputMap im = root.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW);
        ActionMap am = root.getActionMap();
        im.put(KeyStroke.getKeyStroke("control S"), "save"); am.put("save", simpleAction(e -> saveMapDialog()));
        im.put(KeyStroke.getKeyStroke("control O"), "load"); am.put("load", simpleAction(e -> loadMapDialog()));
        im.put(KeyStroke.getKeyStroke("control N"), "new"); am.put("new", simpleAction(e -> { newMap(24); refreshAll(); }));
        im.put(KeyStroke.getKeyStroke('r'), "rotate"); am.put("rotate", simpleAction(e -> { pushUndo(); rotatePlayerFacing(); refreshAll(); }));
        im.put(KeyStroke.getKeyStroke("control Z"), "undo"); am.put("undo", simpleAction(e -> undo()));
        im.put(KeyStroke.getKeyStroke("control Y"), "redo"); am.put("redo", simpleAction(e -> redo()));
    }

    private Action simpleAction(java.util.function.Consumer<ActionEvent> body) {
        return new AbstractAction() {
            public void actionPerformed(ActionEvent e) { body.accept(e); }
        };
    }

    private void rotatePlayerFacing() {
        double ndx = -playerDirY, ndy = playerDirX;
        playerDirX = Math.round(ndx); playerDirY = Math.round(ndy);
    }

    private String relativeToAssets(File chosen) {
        try {
            File assetsAbs = assetsDir.getAbsoluteFile();
            File chosenAbs = chosen.getAbsoluteFile();
            String assetsPath = assetsAbs.getCanonicalPath();
            String chosenPath = chosenAbs.getCanonicalPath();
            if (chosenPath.startsWith(assetsPath + File.separator)) {
                return chosenPath.substring(assetsPath.length() + 1).replace(File.separatorChar, '/');
            }
        } catch (IOException ignored) {}
        return null;
    }

    private void refreshStatus() { status.setText(statusText()); }
    private void refreshAll() {
        // mapDirty set by callers that mutate
        refreshStatus();
        if (canvas != null) canvas.repaint();
        if (livePreview != null) livePreview.repaint();
    }

    private class GridCanvas extends JPanel {
        GridCanvas() {
            updateCanvasSize();
            setBackground(Color.BLACK);
            setFocusable(true);

            MouseAdapter adapter = new MouseAdapter() {
                @Override
                public void mousePressed(MouseEvent e) {
                    requestFocusInWindow();
                    int cx = e.getX() / cellPx, cy = e.getY() / cellPx;
                    if (cx < 0 || cy < 0 || cx >= mapSize || cy >= mapSize) return;

                    pushUndo();
                    if (SwingUtilities.isRightMouseButton(e)) {
                        eraseAt(cx, cy);
                    } else {
                        if (currentTool.equals("RECT") || currentTool.equals("LINE") || (currentTool.equals("COPY/STAMP") && clipboardGrid == null)) {
                            shapeStartCell = new Point(cx, cy);
                            shapeEndCell = new Point(cx, cy);
                            isDraggingShape = true;
                        } else if (currentTool.equals("COPY/STAMP") && clipboardGrid != null) {
                            pasteClipboard(cx, cy);
                        } else if (currentTool.equals("FILL")) {
                            floodFill(cx, cy, fillMode);
                        } else {
                            applyToolBrush(cx, cy);
                        }
                    }
                    refreshAll();
                }

                @Override
                public void mouseDragged(MouseEvent e) {
                    int cx = e.getX() / cellPx, cy = e.getY() / cellPx;
                    if (cx < 0 || cy < 0 || cx >= mapSize || cy >= mapSize) return;

                    if (isDraggingShape) {
                        shapeEndCell = new Point(cx, cy);
                    } else if (SwingUtilities.isRightMouseButton(e)) {
                        eraseAt(cx, cy);
                    } else if (currentTool.equals("WALL") || currentTool.equals("FLOOR") || currentTool.equals("TINT") || currentTool.equals("TILESET")
                            || currentTool.equals("WATER") || currentTool.equals("ROAD") || currentTool.equals("DOOR")) {
                        applyToolBrush(cx, cy);
                    }
                    refreshAll();
                }

                @Override
                public void mouseReleased(MouseEvent e) {
                    if (isDraggingShape && shapeStartCell != null && shapeEndCell != null) {
                        if (currentTool.equals("RECT")) drawRectangle(shapeStartCell, shapeEndCell, 1);
                        else if (currentTool.equals("LINE")) drawLine(shapeStartCell, shapeEndCell, 1);
                        else if (currentTool.equals("COPY/STAMP")) copyRegion(shapeStartCell, shapeEndCell);
                    }
                    isDraggingShape = false;
                    shapeStartCell = null;
                    shapeEndCell = null;
                    refreshAll();
                }

                @Override
                public void mouseMoved(MouseEvent e) {
                    int cx = e.getX() / cellPx, cy = e.getY() / cellPx;
                    if (cx >= 0 && cy >= 0 && cx < mapSize && cy < mapSize) {
                        Entity ent = things.get(new Point(cx, cy));
                        String extra = (ent != null && ent.assetPath != null) ? "  |  ASSET: " + ent.assetPath : "";
                        status.setText(statusText() + "  |  CURSOR: " + cx + "," + cy + extra);
                    }
                }
            };

            addMouseListener(adapter);
            addMouseMotionListener(adapter);
        }

        public void updateCanvasSize() {
            setPreferredSize(new Dimension(mapSize * cellPx, mapSize * cellPx));
            revalidate();
        }

        private void eraseAt(int cx, int cy) {
            grid[cx][cy] = 0;
            wallTextures[cx][cy] = "";
            floorTextures[cx][cy] = "";
            things.remove(new Point(cx, cy));
            wallTints.remove(new Point(cx, cy));
        }

        private void floodFillWalls(int x, int y, int targetVal, int newVal) {
            floodFill(x, y, "WALL");
        }

        /** Improved fill: WALL mode fills matching cell type with wall brush; FLOOR mode fills matching floor region. */
        private void floodFill(int x, int y, String mode) {
            if (x < 0 || y < 0 || x >= mapSize || y >= mapSize) return;
            if ("FLOOR".equals(fillMode) || "FLOOR".equals(mode)) {
                // Flood by matching floor texture + cell type (open-ish)
                int targetCell = grid[x][y];
                String targetFloor = floorTextures[x][y] == null ? "" : floorTextures[x][y];
                String newFloor = activeFloorBrush == null ? "" : activeFloorBrush;
                if (currentTool.equals("TILESET") && activeTilePattern != null && activeTileset != null) {
                    newFloor = tilesetRef(activeTileset, activeTilePattern);
                }
                boolean[][] seen = new boolean[mapSize][mapSize];
                Queue<Point> q = new LinkedList<>();
                q.add(new Point(x, y));
                int painted = 0;
                while (!q.isEmpty()) {
                    Point p = q.poll();
                    if (p.x < 0 || p.x >= mapSize || p.y < 0 || p.y >= mapSize) continue;
                    if (seen[p.x][p.y]) continue;
                    seen[p.x][p.y] = true;
                    if (grid[p.x][p.y] != targetCell) continue;
                    String fl = floorTextures[p.x][p.y] == null ? "" : floorTextures[p.x][p.y];
                    if (!fl.equals(targetFloor)) continue;
                    // paint floor
                    if (targetCell == 1 || targetCell == 2) {
                        // don't open walls in floor fill unless cell was already open-type
                    } else {
                        grid[p.x][p.y] = targetCell; // keep water/road/open
                    }
                    floorTextures[p.x][p.y] = newFloor;
                    if (targetCell == 0 || targetCell == 4 || targetCell == 5) {
                        wallTextures[p.x][p.y] = "";
                    }
                    painted++;
                    q.add(new Point(p.x + 1, p.y));
                    q.add(new Point(p.x - 1, p.y));
                    q.add(new Point(p.x, p.y + 1));
                    q.add(new Point(p.x, p.y - 1));
                }
                status.setText("  // FLOOR FILL " + painted + " cells → " + (newFloor.isEmpty() ? "(default)" : newFloor));
                return;
            }
            // WALL fill — match cell type, stamp wall brush + cell 1
            int targetVal = grid[x][y];
            String targetWall = wallTextures[x][y] == null ? "" : wallTextures[x][y];
            String newWall = activeWallBrush == null ? "" : activeWallBrush;
            if (currentTool.equals("TILESET") && activeTilePattern != null && activeTileset != null
                    && activeTilePattern.cellType == 1) {
                newWall = tilesetRef(activeTileset, activeTilePattern);
            }
            boolean[][] seen = new boolean[mapSize][mapSize];
            Queue<Point> q = new LinkedList<>();
            q.add(new Point(x, y));
            int painted = 0;
            while (!q.isEmpty()) {
                Point p = q.poll();
                if (p.x < 0 || p.x >= mapSize || p.y < 0 || p.y >= mapSize) continue;
                if (seen[p.x][p.y]) continue;
                seen[p.x][p.y] = true;
                if (grid[p.x][p.y] != targetVal) continue;
                String wt = wallTextures[p.x][p.y] == null ? "" : wallTextures[p.x][p.y];
                if (!wt.equals(targetWall) && targetVal == 1) continue;
                grid[p.x][p.y] = 1;
                wallTextures[p.x][p.y] = newWall;
                painted++;
                q.add(new Point(p.x + 1, p.y));
                q.add(new Point(p.x - 1, p.y));
                q.add(new Point(p.x, p.y + 1));
                q.add(new Point(p.x, p.y - 1));
            }
            status.setText("  // WALL FILL " + painted + " cells");
        }

        private void drawRectangle(Point p1, Point p2, int val) {
            int x1 = Math.min(p1.x, p2.x), x2 = Math.max(p1.x, p2.x);
            int y1 = Math.min(p1.y, p2.y), y2 = Math.max(p1.y, p2.y);
            for (int x = x1; x <= x2; x++) {
                for (int y = y1; y <= y2; y++) {
                    if (x == x1 || x == x2 || y == y1 || y == y2) {
                        grid[x][y] = val;
                        wallTextures[x][y] = activeWallBrush;
                    }
                }
            }
        }

        private void drawLine(Point p1, Point p2, int val) {
            int x1 = p1.x, y1 = p1.y, x2 = p2.x, y2 = p2.y;
            int dx = Math.abs(x2 - x1), dy = Math.abs(y2 - y1);
            int sx = x1 < x2 ? 1 : -1, sy = y1 < y2 ? 1 : -1;
            int err = dx - dy;
            while (true) {
                if (x1 >= 0 && x1 < mapSize && y1 >= 0 && y1 < mapSize) {
                    grid[x1][y1] = val;
                    wallTextures[x1][y1] = activeWallBrush;
                }
                if (x1 == x2 && y1 == y2) break;
                int e2 = 2 * err;
                if (e2 > -dy) { err -= dy; x1 += sx; }
                if (e2 < dx) { err += dx; y1 += sy; }
            }
        }

        private void copyRegion(Point p1, Point p2) {
            int x1 = Math.min(p1.x, p2.x), x2 = Math.max(p1.x, p2.x);
            int y1 = Math.min(p1.y, p2.y), y2 = Math.max(p1.y, p2.y);
            int w = x2 - x1 + 1, h = y2 - y1 + 1;
            clipboardGrid = new int[w][h];
            clipboardEntities = new HashMap<>();

            for (int x = 0; x < w; x++) {
                for (int y = 0; y < h; y++) {
                    clipboardGrid[x][y] = grid[x1 + x][y1 + y];
                    Point srcPt = new Point(x1 + x, y1 + y);
                    if (things.containsKey(srcPt)) {
                        Entity e = things.get(srcPt);
                        clipboardEntities.put(new Point(x, y), new Entity(e.category, e.x, e.y));
                    }
                }
            }
            status.setText("  // Region copied (" + w + "x" + h + "). Click anywhere to stamp.");
        }

        private void pasteClipboard(int destX, int destY) {
            if (clipboardGrid == null) return;
            int w = clipboardGrid.length, h = clipboardGrid[0].length;
            for (int x = 0; x < w; x++) {
                for (int y = 0; y < h; y++) {
                    int targetX = destX + x, targetY = destY + y;
                    if (targetX < mapSize && targetY < mapSize) {
                        grid[targetX][targetY] = clipboardGrid[x][y];
                        Point relPt = new Point(x, y);
                        if (clipboardEntities.containsKey(relPt)) {
                            Entity src = clipboardEntities.get(relPt);
                            things.put(new Point(targetX, targetY), new Entity(src.category, targetX + 0.5, targetY + 0.5));
                        }
                    }
                }
            }
        }

        private void applyToolBrush(int cx, int cy) {
            int r = brushSize / 2;
            for (int dy = -r; dy <= r; dy++) {
                for (int dx = -r; dx <= r; dx++) {
                    int x = cx + dx, y = cy + dy;
                    if (x < 0 || y < 0 || x >= mapSize || y >= mapSize) continue;
                    if (brushSize > 1 && dx * dx + dy * dy > (r + 0.5) * (r + 0.5)) continue;
                    applyTool(x, y);
                }
            }
        }

        private void applyTool(int cx, int cy) {
            switch (currentTool) {
                case "TILESET" -> {
                    if (activeTilePattern == null || activeTileset == null) {
                        status.setText("  // Pick a tile from Tileset tab first");
                        break;
                    }
                    TilePattern tp = activeTilePattern;
                    String ref = tilesetRef(activeTileset, tp);
                    grid[cx][cy] = tp.cellType;
                    if (tp.cellType == 1 || tp.cellType == 2 || tp.cellType == 6) {
                        wallTextures[cx][cy] = ref;
                        // keep floor under walls empty for solid walls
                        if (tp.cellType == 1) floorTextures[cx][cy] = floorTextures[cx][cy] == null ? "" : floorTextures[cx][cy];
                    } else {
                        wallTextures[cx][cy] = "";
                        floorTextures[cx][cy] = ref;
                    }
                }
                case "WALL" -> { 
                    grid[cx][cy] = 1; 
                    wallTextures[cx][cy] = activeWallBrush; 
                }
                case "DOOR" -> {
                    grid[cx][cy] = 2; // closed door — blocks until F/SPACE in engine
                    wallTextures[cx][cy] = activeWallBrush.isEmpty() ? wallTextures[cx][cy] : activeWallBrush;
                }
                case "PITCELL" -> {
                    grid[cx][cy] = 3; // void pit — death / lose life
                    wallTextures[cx][cy] = "";
                    floorTextures[cx][cy] = activeFloorBrush.isEmpty() ? "floors/pit.png" : activeFloorBrush;
                }
                case "PITOPEN" -> {
                    if (mapType == MapType.OUTDOOR) {
            // outdoor locked
        } else if (false) {
                        status.setText("  // OPEN PIT only in INDOOR/SKY - switch type");
                    }
                    grid[cx][cy] = 8;
                    wallTextures[cx][cy] = "";
                    floorTextures[cx][cy] = activeFloorBrush.isEmpty() ? "floors/hole.png" : activeFloorBrush;
                    if (activeFloor == 0) status.setText("  // WARNING: OPEN PIT on F1 no below = death (unless SKY void)");
                    else status.setText("  // OPEN PIT F"+(activeFloor+1)+" -> below (skydiving SS style)");
                }
                case "UPDRAFT" -> {
                    grid[cx][cy] = 9; // updraft launch
                    wallTextures[cx][cy] = "";
                    floorTextures[cx][cy] = activeFloorBrush.isEmpty() ? "floors/updraft.png" : activeFloorBrush;
                    status.setText("  // UPDRAFT F"+(activeFloor+1)+" -> launches to island above (F"+(activeFloor+2)+")");
                }
                case "WATER" -> {
                    grid[cx][cy] = 4; // water — slows unless raft
                    wallTextures[cx][cy] = "";
                    floorTextures[cx][cy] = activeFloorBrush.isEmpty() ? "floors/water.png" : activeFloorBrush;
                }
                case "ROAD" -> {
                    grid[cx][cy] = 5;
                    wallTextures[cx][cy] = "";
                    floorTextures[cx][cy] = activeFloorBrush.isEmpty() ? "floors/road.png" : activeFloorBrush;
                }
                case "FAKEWALL" -> {
                    grid[cx][cy] = 6; // secret passage — renders as wall
                    wallTextures[cx][cy] = activeWallBrush.isEmpty() ? "textures/rock.png" : activeWallBrush;
                    floorTextures[cx][cy] = "";
                }
                case "FAKEFLOOR" -> {
                    grid[cx][cy] = 7; // trap floor — looks open, is pit
                    wallTextures[cx][cy] = "";
                    floorTextures[cx][cy] = activeFloorBrush.isEmpty() ? "floors/grass.png" : activeFloorBrush;
                }
                case "WALLH" -> {
                    if (wallHeights == null || wallHeights.length != mapSize)
                        wallHeights = new int[mapSize][mapSize];
                    wallHeights[cx][cy] = activeWallHeight;
                }
                case "GROUNDH" -> {
                    if (groundHeights == null || groundHeights.length != mapSize)
                        groundHeights = new int[mapSize][mapSize];
                    groundHeights[cx][cy] = activeGroundHeight;
                }
                case "FLOOR" -> {
                    grid[cx][cy] = 0;           // open cell so floor is walkable / visible
                    wallTextures[cx][cy] = "";
                    floorTextures[cx][cy] = activeFloorBrush;
                    wallTints.remove(new Point(cx, cy));
                }
                case "ENTITY_TEX" -> {
                    Entity e = things.get(new Point(cx, cy));
                    if (e != null) {
                        File chosen = pickAssetFile(e.category);
                        if (chosen != null) {
                            String rel = relativeToAssets(chosen);
                            e.assetPath = rel != null ? rel : chosen.getName();
                        }
                    }
                }
                case "TINT" -> {
                    if (grid[cx][cy] > 0) {
                        wallTints.put(new Point(cx, cy), paintTintColor != null ? paintTintColor : Color.WHITE);
                    }
                }
                case "PLAYER" -> {
                    playerX = cx + 0.5; playerY = cy + 0.5;
                    // store per-floor
                    playerStartX[activeFloor] = playerX;
                    playerStartY[activeFloor] = playerY;
                    playerStartDirX[activeFloor] = playerDirX;
                    playerStartDirY[activeFloor] = playerDirY;
                    hasPlayerStart[activeFloor] = true;
                    status.setText("  // PLAYER START F"+(activeFloor+1)+" = "+playerX+","+playerY);
                }
                case "TEXTBOARD" -> {
                    String msg = JOptionPane.showInputDialog(SFMapEditor.this, "Textboard message:", existingTextAt(cx, cy));
                    if (msg != null && !msg.isEmpty()) {
                        Entity e = new Entity("TEXTBOARD", cx + 0.5, cy + 0.5);
                        e.text = msg.replace("\\n", "\n");
                        e.floorIndex = activeFloor;
                        things.put(new Point(cx, cy), e);
                    }
                }
                case "LIGHT" -> {
                    Entity e = new Entity("LIGHT", cx + 0.5, cy + 0.5);
                    e.solid = false;
                    e.floorIndex = activeFloor;
                    File torch = new File(assetsDir, "obstacles/torch1.png");
                    if (torch.isFile()) e.assetPath = relativeToAssets(torch);
                    things.put(new Point(cx, cy), e);
                }
                default -> {
                    File chosen = null;
                    if ((currentTool.equals("ENEMY") || currentTool.equals("BOSS")) && pendingEnemyAsset != null) {
                        File pref = new File(assetsDir, pendingEnemyAsset);
                        if (pref.isFile()) chosen = pref;
                    }
                    if (chosen == null) chosen = pickAssetFile(currentTool);
                    if (chosen != null) {
                        Entity e = new Entity(currentTool, cx + 0.5, cy + 0.5);
                        String rel = relativeToAssets(chosen);
                        e.assetPath = rel != null ? rel : chosen.getName();
                        e.floorIndex = activeFloor;
                        if (currentTool.equals("ENEMY") || currentTool.equals("BOSS")) {
                            configureEnemyPatrol(e);
                        }
                        if (currentTool.equals("TORCH") || currentTool.equals("LIGHT")) {
                            e.solid = false;
                        }
                        things.put(new Point(cx, cy), e);
                    }
                }
            }
        }

        private String existingTextAt(int cx, int cy) {
            Entity e = things.get(new Point(cx, cy));
            return (e != null && e.text != null) ? e.text.replace("\n", "\\n") : "";
        }

        private File pickAssetFile(String category) {
            String pool = ENTITY_POOL_MAP.getOrDefault(category.toUpperCase(), "items");
            File startDir = lastDirByCategory.get(category.toUpperCase());
            if (startDir == null || !startDir.isDirectory()) {
                startDir = new File(assetsDir, pool);
                if (!startDir.isDirectory()) {
                    // try direct folder name match (doors, enemy, boss, etc)
                    File alt = new File(assetsDir, category.toLowerCase());
                    if (alt.isDirectory()) startDir = alt;
                    else if (assetsDir.isDirectory()) startDir = assetsDir;
                    else startDir = new File(System.getProperty("user.home"));
                }
            }
            JFileChooser ch = new JFileChooser(startDir);
            ch.setDialogTitle("Choose sprite for " + category + "  [" + startDir.getName() + "]");
            ch.setFileFilter(new FileNameExtensionFilter("Images (png, jpg)", "png", "jpg", "jpeg"));
            // Show quick shortcuts for related folders
            if (assetsDir.isDirectory()) {
                // Add accessory - if we are picking DOOR, also allow browsing enemy etc via dropdown handled by OS
            }
            int res = ch.showDialog(SFMapEditor.this, "USE SPRITE");
            if (res == JFileChooser.APPROVE_OPTION) {
                File sel = ch.getSelectedFile();
                if (sel != null) {
                    lastDirByCategory.put(category.toUpperCase(), sel.getParentFile());
                    lastFileByCategory.put(category.toUpperCase(), sel);
                    // Special: if category is functional (DOORLOCK, BOSSLOCK) but file is in doors, remember both
                    if (category.toUpperCase().contains("LOCK") || category.equalsIgnoreCase("DOOR")) {
                        lastDirByCategory.put("DOOR", sel.getParentFile());
                        lastDirByCategory.put("DOORLOCK", sel.getParentFile());
                        lastDirByCategory.put("BOSSLOCK", sel.getParentFile());
                        lastDirByCategory.put("FINALLOCK", sel.getParentFile());
                    }
                }
                return sel;
            }
            return null;
        }

        // Smart door picker - function + sprite together (your requested workflow)
        private File pickDoorWithFunction() {
            String[] funcs = {"DOOR (normal)", "DOORLOCK (needs key)", "BOSSLOCK (boss key)", "FINALLOCK (final key)", "FAKEWALL (secret)"};
            String pick = (String) JOptionPane.showInputDialog(SFMapEditor.this,
                    "Door function - sprite is separate for now, this sets behavior:\nPick function, then sprite in next dialog",
                    "Door Function", JOptionPane.QUESTION_MESSAGE, null, funcs, funcs[0]);
            if (pick == null) return null;
            // map to internal handling, but sprite still chosen via normal picker
            if (pick.startsWith("DOORLOCK")) currentTool = "DOORLOCK";
            else if (pick.startsWith("BOSSLOCK")) currentTool = "BOSSLOCK";
            else if (pick.startsWith("FINALLOCK")) currentTool = "FINALLOCK";
            else if (pick.startsWith("FAKEWALL")) currentTool = "FAKEWALL";
            else currentTool = "DOOR";
            return pickAssetFile("doors");
        }

        /** AREA bubble radius or PATH waypoints for enemies. */
        private void configureEnemyPatrol(Entity e) {
            String[] modes = {"AREA (wander bubble)", "PATH (waypoint loop)", "NONE (stand)"};
            String pick = (String) JOptionPane.showInputDialog(SFMapEditor.this,
                    "Patrol mode for this enemy:", "Enemy Patrol",
                    JOptionPane.QUESTION_MESSAGE, null, modes, modes[0]);
            if (pick == null || pick.startsWith("AREA")) {
                e.patrolMode = "AREA";
                String r = JOptionPane.showInputDialog(SFMapEditor.this, "Area radius (tiles):", "2.5");
                try { e.areaRadius = Double.parseDouble(r.trim()); } catch (Exception ex) { e.areaRadius = 2.5; }
            } else if (pick.startsWith("PATH")) {
                e.patrolMode = "PATH";
                String path = JOptionPane.showInputDialog(SFMapEditor.this,
                        "Waypoints as x,y;x,y;... (map coords):",
                        String.format("%.1f,%.1f;%.1f,%.1f", e.x, e.y, e.x + 2, e.y));
                if (path != null && !path.isBlank()) e.pathData = path.trim();
                else { e.patrolMode = "AREA"; e.areaRadius = 2.5; }
            } else {
                e.patrolMode = "NONE";
            }
        }

        @Override
        protected void paintComponent(Graphics g) {
            super.paintComponent(g);
            Graphics2D g2 = (Graphics2D) g;

            // Timesplitters / FPS Creator style: dim ghost of floor below
            if (activeFloor > 0) {
                ensureFloorLayers();
                int below = activeFloor - 1;
                if (layerGrids[below] != null) {
                    drawFloorGhost(g2, below, 0.40f);
                    // soft shadow veil so current floor reads on top
                    g2.setColor(new Color(0, 0, 0, 90));
                    g2.fillRect(0, 0, mapSize * cellPx, mapSize * cellPx);
                }
            }

            for (int x = 0; x < mapSize; x++) {
                for (int y = 0; y < mapSize; y++) {
                    int v = grid[x][y];
                    String flPath = floorTextures[x][y];
                    String wPath = wallTextures[x][y];
                    Color tint = wallTints.get(new Point(x, y));

                    // Open cells: if upper floor, leave ghost visible (light tint only)
                    if (v == 0) {
                        BufferedImage flImg = getThumb(flPath);
                        if (flImg != null) {
                            if (activeFloor > 0) {
                                g2.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 0.55f));
                                g2.drawImage(flImg, x * cellPx, y * cellPx, cellPx, cellPx, null);
                                g2.setComposite(AlphaComposite.SrcOver);
                            } else {
                                g2.drawImage(flImg, x * cellPx, y * cellPx, cellPx, cellPx, null);
                            }
                        } else if (activeFloor == 0) {
                            g2.setColor(new Color(14, 14, 14));
                            g2.fillRect(x * cellPx, y * cellPx, cellPx, cellPx);
                        }
                    } else if (v == 2) {
                        // Closed door — brown frame
                        BufferedImage wImg = getThumb(wPath);
                        if (wImg != null) g2.drawImage(wImg, x * cellPx, y * cellPx, cellPx, cellPx, null);
                        else {
                            g2.setColor(new Color(120, 80, 40));
                            g2.fillRect(x * cellPx, y * cellPx, cellPx, cellPx);
                        }
                        g2.setColor(new Color(255, 200, 80, 160));
                        g2.drawRect(x * cellPx + 2, y * cellPx + 2, cellPx - 5, cellPx - 5);
                        g2.setFont(new Font("Monospaced", Font.BOLD, Math.max(9, cellPx / 3)));
                        g2.setColor(Color.YELLOW);
                        g2.drawString("D", x * cellPx + cellPx / 3, y * cellPx + cellPx * 2 / 3);
                    } else if (v == 3) {
                        // Pit void - death
                        BufferedImage flImg = getThumb(flPath);
                        if (flImg != null) g2.drawImage(flImg, x * cellPx, y * cellPx, cellPx, cellPx, null);
                        else {
                            g2.setColor(new Color(20, 20, 20));
                            g2.fillRect(x * cellPx, y * cellPx, cellPx, cellPx);
                        }
                        g2.setColor(new Color(255, 40, 40, 140));
                        g2.drawRect(x * cellPx + 1, y * cellPx + 1, cellPx - 3, cellPx - 3);
                        g2.setFont(new Font("Monospaced", Font.BOLD, Math.max(9, cellPx / 3)));
                        g2.setColor(Color.RED);
                        g2.drawString("P", x * cellPx + cellPx / 3, y * cellPx + cellPx * 2 / 3);
                    } else if (v == 8) {
                        // Open pit - hole to floor below (basement / sky island)
                        BufferedImage flImg = getThumb(flPath);
                        if (flImg != null) g2.drawImage(flImg, x * cellPx, y * cellPx, cellPx, cellPx, null);
                        else {
                            g2.setColor(new Color(30, 30, 80));
                            g2.fillRect(x * cellPx, y * cellPx, cellPx, cellPx);
                        }
                        // Show ghost of floor below if available
                        if (activeFloor > 0 && layerGrids[activeFloor-1] != null) {
                            int below = activeFloor-1;
                            String belowTex = layerFloorTex[below][x][y];
                            BufferedImage belowImg = getThumb(belowTex);
                            if (belowImg != null) {
                                g2.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 0.5f));
                                g2.drawImage(belowImg, x * cellPx + 4, y * cellPx + 4, cellPx - 8, cellPx - 8, null);
                                g2.setComposite(AlphaComposite.SrcOver);
                            }
                        }
                        g2.setColor(new Color(80, 120, 255, 180));
                        g2.drawRect(x * cellPx + 1, y * cellPx + 1, cellPx - 3, cellPx - 3);
                        g2.setFont(new Font("Monospaced", Font.BOLD, Math.max(8, cellPx / 3)));
                        g2.setColor(new Color(120, 160, 255));
                        g2.drawString("O"+(activeFloor), x * cellPx + 2, y * cellPx + cellPx * 2 / 3);
                    } else if (v == 9) {
                        // Updraft - sky island launch
                        g2.setColor(new Color(120, 220, 255));
                        g2.fillRect(x * cellPx + 2, y * cellPx + 2, cellPx - 4, cellPx - 4);
                        g2.setColor(new Color(255,255,255,200));
                        g2.setFont(new Font("Monospaced", Font.BOLD, Math.max(9, cellPx / 3)));
                        g2.drawString("^", x * cellPx + cellPx/3, y * cellPx + cellPx*2/3);
                    } else if (v == 4) {
                        // Water cell
                        BufferedImage flImg = getThumb(flPath);
                        if (flImg != null) g2.drawImage(flImg, x * cellPx, y * cellPx, cellPx, cellPx, null);
                        else {
                            g2.setColor(new Color(30, 90, 180));
                            g2.fillRect(x * cellPx, y * cellPx, cellPx, cellPx);
                        }
                        g2.setColor(new Color(80, 180, 255, 120));
                        g2.fillRect(x * cellPx + 2, y * cellPx + 2, cellPx - 4, cellPx - 4);
                        g2.setFont(new Font("Monospaced", Font.BOLD, Math.max(9, cellPx / 3)));
                        g2.setColor(Color.CYAN);
                        g2.drawString("W", x * cellPx + cellPx / 3, y * cellPx + cellPx * 2 / 3);
                    } else if (v == 5) {
                        BufferedImage flImg = getThumb(flPath);
                        if (flImg != null) g2.drawImage(flImg, x * cellPx, y * cellPx, cellPx, cellPx, null);
                        else {
                            g2.setColor(new Color(70, 70, 70));
                            g2.fillRect(x * cellPx, y * cellPx, cellPx, cellPx);
                        }
                        g2.setColor(new Color(180, 180, 180, 100));
                        g2.fillRect(x * cellPx + 1, y * cellPx + 1, cellPx - 2, cellPx - 2);
                        g2.setFont(new Font("Monospaced", Font.BOLD, Math.max(9, cellPx / 3)));
                        g2.setColor(Color.LIGHT_GRAY);
                        g2.drawString("R", x * cellPx + cellPx / 3, y * cellPx + cellPx * 2 / 3);
                    } else if (v == 6) {
                        // Fake wall — looks like wall, marker FW
                        BufferedImage wImg = getThumb(wPath);
                        if (wImg != null) g2.drawImage(wImg, x * cellPx, y * cellPx, cellPx, cellPx, null);
                        else {
                            g2.setColor(new Color(100, 80, 120));
                            g2.fillRect(x * cellPx, y * cellPx, cellPx, cellPx);
                        }
                        g2.setColor(new Color(255, 0, 255, 80));
                        g2.fillRect(x * cellPx, y * cellPx, cellPx, cellPx);
                        g2.setFont(new Font("Monospaced", Font.BOLD, Math.max(8, cellPx / 3)));
                        g2.setColor(Color.MAGENTA);
                        g2.drawString("FW", x * cellPx + 2, y * cellPx + cellPx * 2 / 3);
                    } else if (v == 7) {
                        BufferedImage flImg = getThumb(flPath);
                        if (flImg != null) g2.drawImage(flImg, x * cellPx, y * cellPx, cellPx, cellPx, null);
                        else {
                            g2.setColor(new Color(60, 100, 60));
                            g2.fillRect(x * cellPx, y * cellPx, cellPx, cellPx);
                        }
                        g2.setColor(new Color(255, 40, 40, 90));
                        g2.fillRect(x * cellPx, y * cellPx, cellPx, cellPx);
                        g2.setFont(new Font("Monospaced", Font.BOLD, Math.max(8, cellPx / 3)));
                        g2.setColor(Color.RED);
                        g2.drawString("FF", x * cellPx + 2, y * cellPx + cellPx * 2 / 3);
                    }
                    // Layer 2: Wall Image / Flat Tint Preview
                    else {
                        BufferedImage wImg = getThumb(wPath);
                        if (wImg != null) {
                            g2.drawImage(wImg, x * cellPx, y * cellPx, cellPx, cellPx, null);
                            if (tint != null) {
                                g2.setColor(new Color(tint.getRed(), tint.getGreen(), tint.getBlue(), 100));
                                g2.fillRect(x * cellPx, y * cellPx, cellPx, cellPx);
                            }
                        } else {
                            g2.setColor(tint != null ? tint : new Color(80, 160, 100));
                            g2.fillRect(x * cellPx, y * cellPx, cellPx, cellPx);
                        }
                    }

                    // Grid Overlay
                    g2.setColor(new Color(0, 0, 0, 70));
                    g2.drawRect(x * cellPx, y * cellPx, cellPx, cellPx);
                }
            }

            // Floor badge (which level you're editing)
            if (floorCount > 1) {
                g2.setFont(new Font("Monospaced", Font.BOLD, 14));
                String badge = "FLOOR " + (activeFloor + 1) + " / " + floorCount;
                if (activeFloor > 0) badge += "  (ghost F" + activeFloor + " below)";
                int bw = g2.getFontMetrics().stringWidth(badge) + 12;
                g2.setColor(new Color(0, 0, 0, 180));
                g2.fillRoundRect(6, 6, bw, 22, 6, 6);
                g2.setColor(activeFloor > 0 ? new Color(255, 200, 80) : new Color(120, 255, 160));
                g2.drawString(badge, 12, 22);
            }

            // Shape Selection Drag Overlay
            if (isDraggingShape && shapeStartCell != null && shapeEndCell != null) {
                g2.setColor(new Color(255, 255, 0, 120));
                int x1 = Math.min(shapeStartCell.x, shapeEndCell.x) * cellPx;
                int y1 = Math.min(shapeStartCell.y, shapeEndCell.y) * cellPx;
                int w = (Math.abs(shapeStartCell.x - shapeEndCell.x) + 1) * cellPx;
                int h = (Math.abs(shapeStartCell.y - shapeEndCell.y) + 1) * cellPx;
                g2.drawRect(x1, y1, w, h);
            }

            // Enemy patrol + light radius overlays
            if (showRadiusOverlays) {
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                for (Entity e : things.values()) {
                    if (filterEntitiesToActiveFloor && e.floorIndex != activeFloor) continue;
                    if (e.floorIndex != activeFloor && filterEntitiesToActiveFloor) continue;
                    String cat = e.category == null ? "" : e.category.toUpperCase();
                    double rad = 0;
                    Color ring = null;
                    if (cat.equals("ENEMY") || cat.equals("BOSS")) {
                        rad = e.areaRadius > 0 ? e.areaRadius : 2.5;
                        ring = cat.equals("BOSS") ? new Color(255, 80, 80, 90) : new Color(255, 160, 40, 80);
                    } else if (cat.equals("TORCH") || cat.equals("LIGHT")) {
                        rad = e.lightRadius > 0 ? e.lightRadius : (cat.equals("LIGHT") ? 5.0 : 3.0);
                        ring = new Color(255, 220, 100, 70);
                    }
                    if (rad <= 0 || ring == null) continue;
                    int cx = (int) (e.x * cellPx);
                    int cy = (int) (e.y * cellPx);
                    int rr = (int) (rad * cellPx);
                    g2.setColor(ring);
                    g2.fillOval(cx - rr, cy - rr, rr * 2, rr * 2);
                    g2.setColor(new Color(ring.getRed(), ring.getGreen(), ring.getBlue(), 180));
                    g2.drawOval(cx - rr, cy - rr, rr * 2, rr * 2);
                }
            }

            // Layer 3: Entities — filter to active floor when filterEntitiesToActiveFloor is on
            for (Entity e : things.values()) {
                boolean sameFloor = e.floorIndex == activeFloor;
                if (filterEntitiesToActiveFloor && !sameFloor) continue;
                if (sameFloor) {
                    int cx = (int) e.x, cy = (int) e.y;
                    int px = cx * cellPx, py = cy * cellPx;
                    if (!e.category.equalsIgnoreCase("PIT") && !e.category.equalsIgnoreCase("TEXTBOARD")) {
                        g2.setColor(new Color(0, 0, 0, 100));
                        g2.fillOval(px + cellPx / 4, py + cellPx * 2 / 3, cellPx / 2, cellPx / 4);
                    }
                }
            }
            for (Entity e : things.values()) {
                boolean sameFloor = e.floorIndex == activeFloor;
                if (filterEntitiesToActiveFloor && !sameFloor) continue;
                int cx = (int) e.x, cy = (int) e.y;
                int px = cx * cellPx, py = cy * cellPx;
                BufferedImage thumb = e.category.equals("TEXTBOARD") ? null : getThumb(e.assetPath);
                if (!sameFloor) {
                    g2.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 0.18f));
                }
                if (thumb != null) {
                    g2.drawImage(thumb, px + 2, py + 2, cellPx - 4, cellPx - 4, null);
                    g2.setColor(sameFloor ? new Color(255, 50, 50) : new Color(255, 50, 50, 80));
                    g2.drawRect(px + 1, py + 1, cellPx - 3, cellPx - 3);
                } else {
                    Color base = legendTint(e.category);
                    g2.setColor(sameFloor ? base : new Color(base.getRed(), base.getGreen(), base.getBlue(), 80));
                    g2.fillOval(px + 3, py + 3, cellPx - 6, cellPx - 6);
                    g2.setColor(Color.BLACK);
                    g2.drawOval(px + 3, py + 3, cellPx - 6, cellPx - 6);
                }
                if (!sameFloor) {
                    g2.setComposite(AlphaComposite.SrcOver);
                    g2.setFont(new Font("Monospaced", Font.PLAIN, 8));
                    g2.setColor(new Color(200, 200, 200, 150));
                    g2.drawString("F" + (e.floorIndex + 1), px + 2, py + 10);
                }
            }

            // Player Spawn Locations - per floor
            for (int fi=0; fi<floorCount; fi++) {
                if (!hasPlayerStart[fi]) continue;
                int ppx = (int) (playerStartX[fi] * cellPx), ppy = (int) (playerStartY[fi] * cellPx);
                if (fi==activeFloor) {
                    g2.setColor(theme.fg);
                    g2.fillOval(ppx - 5, ppy - 5, 10, 10);
                    g2.drawLine(ppx, ppy, ppx + (int) (playerStartDirX[fi] * 12), ppy + (int) (playerStartDirY[fi] * 12));
                } else {
                    g2.setColor(new Color(theme.fg.getRed(), theme.fg.getGreen(), theme.fg.getBlue(), 60));
                    g2.fillOval(ppx - 4, ppy - 4, 8, 8);
                    g2.setFont(new Font("Monospaced", Font.PLAIN, 8));
                    g2.drawString("P"+(fi+1), ppx+6, ppy);
                }
            }
            // Also show current editing player cursor
            if (!hasPlayerStart[activeFloor]) {
                int ppx = (int) (playerX * cellPx), ppy = (int) (playerY * cellPx);
                g2.setColor(theme.fg);
                g2.fillOval(ppx - 4, ppy - 4, 8, 8);
                g2.drawLine(ppx, ppy, ppx + (int) (playerDirX * 10), ppy + (int) (playerDirY * 10));
            }

            // Grid Border
            g2.setColor(theme.fg);
            g2.drawRect(0, 0, mapSize * cellPx - 1, mapSize * cellPx - 1);
        }
    }

    // -------------------------------------------------------------------------
    // Unity-style live 3D raycast preview (reads editor state directly)
    // -------------------------------------------------------------------------
    class LivePreviewPanel extends JPanel {
        static final int PW = 320, PH = 200;
        final BufferedImage frame = new BufferedImage(PW, PH, BufferedImage.TYPE_INT_RGB);
        final int[] pixels = ((java.awt.image.DataBufferInt) frame.getRaster().getDataBuffer()).getData();
        final double[] zbuf = new double[PW];
        final javax.swing.Timer previewTimer;

        LivePreviewPanel() {
            setBackground(Color.BLACK);
            setPreferredSize(new Dimension(340, 240));
            setMinimumSize(new Dimension(200, 160));
            setBorder(new LineBorder(theme.fg, 1));
            int ms = options != null ? options.previewTimerMs() : 80;
            previewTimer = new javax.swing.Timer(ms, e -> repaint());
            previewTimer.start();
        }

        void applyOptions(ChromaOptions o) {
            if (o == null) return;
            previewTimer.setDelay(o.previewTimerMs());
            if (!o.livePreview) previewTimer.stop();
            else if (!previewTimer.isRunning()) previewTimer.start();
        }

        @Override
        protected void paintComponent(Graphics g) {
            super.paintComponent(g);
            renderPreview();
            Graphics2D g2 = (Graphics2D) g;
            int dw = getWidth(), dh = getHeight();
            g2.drawImage(frame, 0, 0, dw, dh, null);
            g2.setColor(theme.fg);
            g2.setFont(new Font("Monospaced", Font.BOLD, 11));
            g2.drawString("LIVE PREVIEW", 8, 16);
            g2.drawString(String.format("P %.1f,%.1f", playerX, playerY), 8, dh - 8);
        }

        void renderPreview() {
            int skyRGB = theme.bg.getRGB();
            int floorRGB = new Color(theme.bg.getRed() / 3, theme.bg.getGreen() / 3, theme.bg.getBlue() / 3).getRGB();
            for (int y = 0; y < PH / 2; y++)
                for (int x = 0; x < PW; x++) pixels[y * PW + x] = skyRGB;
            for (int y = PH / 2; y < PH; y++)
                for (int x = 0; x < PW; x++) pixels[y * PW + x] = floorRGB;

            double dirX = playerDirX, dirY = playerDirY;
            double planeX = 0.66 * dirY, planeY = -0.66 * dirX;
            double posX = playerX, posY = playerY;

            for (int x = 0; x < PW; x++) {
                double cam = 2.0 * x / PW - 1;
                double rdx = dirX + planeX * cam;
                double rdy = dirY + planeY * cam;
                int mapX = (int) posX, mapY = (int) posY;
                double ddx = (rdx == 0) ? 1e30 : Math.abs(1 / rdx);
                double ddy = (rdy == 0) ? 1e30 : Math.abs(1 / rdy);
                int stepX = rdx < 0 ? -1 : 1, stepY = rdy < 0 ? -1 : 1;
                double sdx = rdx < 0 ? (posX - mapX) * ddx : (mapX + 1.0 - posX) * ddx;
                double sdy = rdy < 0 ? (posY - mapY) * ddy : (mapY + 1.0 - posY) * ddy;
                int hit = 0, hx = mapX, hy = mapY, side = 0;
                for (int i = 0; i < 48; i++) {
                    if (sdx < sdy) { sdx += ddx; mapX += stepX; side = 0; }
                    else { sdy += ddy; mapY += stepY; side = 1; }
                    if (mapX < 0 || mapY < 0 || mapX >= mapSize || mapY >= mapSize) { hit = 1; hx = mapX; hy = mapY; break; }
                    int gc = grid[mapX][mapY];
                    if (gc == 1 || gc == 2) { hit = 1; hx = mapX; hy = mapY; break; }
                }
                double dist = side == 0
                        ? (hx - posX + (1 - stepX) / 2.0) / (rdx == 0 ? 1e-9 : rdx)
                        : (hy - posY + (1 - stepY) / 2.0) / (rdy == 0 ? 1e-9 : rdy);
                dist = Math.max(0.05, Math.abs(dist));
                zbuf[x] = dist;
                int lh = (int) (PH / dist);
                int ds = Math.max(0, -lh / 2 + PH / 2);
                int de = Math.min(PH - 1, lh / 2 + PH / 2);

                Color wallCol = theme.fg;
                String wPath = null;
                if (hx >= 0 && hy >= 0 && hx < mapSize && hy < mapSize) {
                    wPath = wallTextures[hx][hy];
                    Color tint = wallTints.get(new Point(hx, hy));
                    if (tint != null) wallCol = tint;
                }
                BufferedImage wImg = (wPath != null && !wPath.isEmpty()) ? getThumb(wPath) : null;
                double wallX;
                if (side == 0) wallX = posY + dist * rdy; else wallX = posX + dist * rdx;
                wallX -= Math.floor(wallX);
                double shade = (side == 1 ? 0.65 : 1.0) * Math.max(0.25, 1.2 - dist * 0.07);

                for (int y = ds; y <= de; y++) {
                    if (wImg != null) {
                        int tw = wImg.getWidth(), th = wImg.getHeight();
                        int tx = (int) (wallX * tw);
                        if ((side == 0 && rdx > 0) || (side == 1 && rdy < 0)) tx = tw - tx - 1;
                        int ty = ((y - (-lh / 2 + PH / 2)) * th) / Math.max(1, lh);
                        tx = Math.max(0, Math.min(tw - 1, tx));
                        ty = Math.max(0, Math.min(th - 1, ty));
                        Color c = new Color(wImg.getRGB(tx, ty));
                        pixels[y * PW + x] = shadeColor(c, shade).getRGB();
                    } else {
                        pixels[y * PW + x] = shadeColor(wallCol, shade).getRGB();
                    }
                }

                // Floor casting
                double rayDirX0 = dirX - planeX, rayDirY0 = dirY - planeY;
                double rayDirX1 = dirX + planeX, rayDirY1 = dirY + planeY;
                for (int y = de + 1; y < PH; y++) {
                    double rowDist = (0.5 * PH) / (y - PH / 2.0);
                    if (rowDist <= 0) continue;
                    double floorStepX = rowDist * (rayDirX1 - rayDirX0) / PW;
                    double floorStepY = rowDist * (rayDirY1 - rayDirY0) / PW;
                    double floorX = posX + rowDist * rayDirX0 + floorStepX * x;
                    double floorY = posY + rowDist * rayDirY0 + floorStepY * x;
                    int cx = (int) floorX, cy = (int) floorY;
                    if (cx < 0 || cy < 0 || cx >= mapSize || cy >= mapSize) {
                        pixels[y * PW + x] = floorRGB;
                        continue;
                    }
                    String flPath = floorTextures[cx][cy];
                    BufferedImage fImg = (flPath != null && !flPath.isEmpty()) ? getThumb(flPath) : null;
                    double fShade = Math.max(0.2, 1.1 - rowDist * 0.06);
                    if (fImg != null) {
                        int tw = fImg.getWidth(), th = fImg.getHeight();
                        int tx = (int) ((floorX - cx) * tw) % tw; if (tx < 0) tx += tw;
                        int ty = (int) ((floorY - cy) * th) % th; if (ty < 0) ty += th;
                        Color c = new Color(fImg.getRGB(tx, ty));
                        pixels[y * PW + x] = shadeColor(c, fShade).getRGB();
                    } else {
                        pixels[y * PW + x] = shadeColor(theme.bg, 0.35 * fShade).getRGB();
                    }
                }
            }

            // Entity markers — sorted by layer then depth (far → near)
            record EntDraw(Entity e, double dist, int layer) {}
            List<EntDraw> ents = new ArrayList<>();
            for (Entity e : things.values()) {
                int layer = switch (e.category == null ? "" : e.category.toUpperCase()) {
                    case "PIT" -> 0;
                    case "JAR", "BIGITEM", "CHESTSM", "CHESTBG" -> 10;
                    case "BOX", "STAIRS" -> 20;
                    case "ENEMY", "BOSS" -> 30;
                    case "TEXTBOARD" -> 60;
                    default -> 20;
                };
                double d = (e.x - posX) * (e.x - posX) + (e.y - posY) * (e.y - posY);
                ents.add(new EntDraw(e, d, layer));
            }
            ents.sort((a, b) -> {
                int lc = Integer.compare(a.layer, b.layer);
                return lc != 0 ? lc : Double.compare(b.dist, a.dist);
            });
            for (EntDraw ed : ents) {
                Entity e = ed.e;
                double dx = e.x - posX, dy = e.y - posY;
                double invDet = 1.0 / (planeX * dirY - dirX * planeY);
                double transformX = invDet * (dirY * dx - dirX * dy);
                double transformY = invDet * (-planeY * dx + planeX * dy);
                if (transformY <= 0.2) continue;
                int sx = (int) ((PW / 2.0) * (1 + transformX / transformY));
                int sh = Math.abs((int) (PH / transformY));
                int sy0 = Math.max(0, -sh / 2 + PH / 2);
                int sy1 = Math.min(PH - 1, sh / 2 + PH / 2);
                int sw = Math.max(4, sh / 3);
                BufferedImage spr = e.category.equals("TEXTBOARD") ? null : getThumb(e.assetPath);
                Color col = legendTint(e.category);
                for (int s = sx - sw / 2; s <= sx + sw / 2; s++) {
                    if (s < 0 || s >= PW || transformY >= zbuf[s]) continue;
                    boolean wrote = false;
                    for (int yy = sy0; yy <= sy1; yy++) {
                        if (spr != null) {
                            int tx = (s - (sx - sw / 2)) * spr.getWidth() / Math.max(1, sw);
                            int ty = (yy - sy0) * spr.getHeight() / Math.max(1, (sy1 - sy0 + 1));
                            tx = Math.max(0, Math.min(spr.getWidth() - 1, tx));
                            ty = Math.max(0, Math.min(spr.getHeight() - 1, ty));
                            int rgb = spr.getRGB(tx, ty);
                            if (((rgb >> 24) & 0xff) > 20) {
                                pixels[yy * PW + s] = rgb;
                                wrote = true;
                            }
                        } else {
                            pixels[yy * PW + s] = col.getRGB();
                            wrote = true;
                        }
                    }
                    if (wrote) zbuf[s] = transformY;
                }
            }
        }

        Color shadeColor(Color c, double f) {
            return new Color(
                    (int) Math.max(0, Math.min(255, c.getRed() * f)),
                    (int) Math.max(0, Math.min(255, c.getGreen() * f)),
                    (int) Math.max(0, Math.min(255, c.getBlue() * f)));
        }
    }
}
