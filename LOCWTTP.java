import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.border.LineBorder;
import javax.swing.filechooser.FileNameExtensionFilter;
import java.awt.*;
import java.awt.event.*;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;
import java.io.*;
import java.util.*;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.imageio.ImageIO;
import javax.sound.sampled.*;

/**
 * LOCWTTP Engine - SFReactor v1.0 Textures, Floors & Skybox Edition
 * Features: Raycasting, 3D Textboards, Map Load, Audio Engine, Weapons, HUD,
 *           Floor Casting + Floor Textures, Custom Wall Textures, Custom Skyboxes.
 */
public class LOCWTTP extends JFrame {

    /** Dynamic map size — Daggerfall-scale (default 24, max 128). */
    static int MAP_SIZE = 24;
    static final int MAP_SIZE_MAX = 128;
    static final int MAP_SIZE_MIN = 16;
    /** LTTP-style multi-floor stack (max 8). */
    static final int MAX_FLOORS = 8;
    public enum MapType { OUTDOOR, INDOOR, SKY }
    MapType mapType = MapType.OUTDOOR;
    int floorCount = 1;
    int currentFloor = 0;
    int[][][] floorGrids = new int[MAX_FLOORS][][];
    int[][][] floorTexLayers = new int[MAX_FLOORS][][];
    int[][][] floorFloorTexLayers = new int[MAX_FLOORS][][];
    int[][][] floorWallHLayers = new int[MAX_FLOORS][][];
    int[][][] floorGroundHLayers = new int[MAX_FLOORS][][];
    // Per-floor player starts (LTTP-style)
    double[] playerStartX = new double[MAX_FLOORS];
    double[] playerStartY = new double[MAX_FLOORS];
    double[] playerStartDirX = new double[MAX_FLOORS];
    double[] playerStartDirY = new double[MAX_FLOORS];
    boolean[] hasPlayerStart = new boolean[MAX_FLOORS];
    double floorFade = 0;
    int pendingFloor = -1;
    static final double FOV = 0.66;
    // Loaded from ChromaOptions (Q6600-friendly defaults)
    int RENDER_W = 640;
    int RENDER_H = 400;
    int maxRaySteps = 48;
    ChromaOptions options = ChromaOptions.load();

    public enum HUDPalette {
        MONO_GREEN("Mono-Green", new Color(0, 255, 100), new Color(0, 30, 10), new Color(0, 100, 40)),
        AMBER("Amber", new Color(255, 176, 0), new Color(30, 20, 0), new Color(120, 80, 0)),
        CYAN("Cyan", new Color(0, 230, 255), new Color(0, 25, 35), new Color(0, 100, 120)),
        VIRTUAL_BOY("Virtual Boy", new Color(255, 0, 0), new Color(30, 0, 0), new Color(120, 0, 0));

        public final String name;
        public final Color fg, bg, panel;
        HUDPalette(String name, Color fg, Color bg, Color panel) {
            this.name = name; this.fg = fg; this.bg = bg; this.panel = panel;
        }
    }

    HUDPalette currentPalette = HUDPalette.MONO_GREEN;

    int[][] worldMap = emptyMap();
    int[][] textureMap = new int[MAP_SIZE][MAP_SIZE];
    int[][] floorTextureMap = new int[MAP_SIZE][MAP_SIZE];
    Color[][] wallColors = new Color[MAP_SIZE][MAP_SIZE];
    /** Half-units: 2 = normal height, 1=half, 4=double */
    int[][] wallHeightMap = fillHeight(2);
    /** Ground level 0–3 (2 = default) */
    int[][] groundHeightMap = fillHeight(2);
    int viewDistance = 24;

    private static int[][] fillHeight(int v) {
        int[][] a = new int[MAP_SIZE][MAP_SIZE];
        for (int x = 0; x < MAP_SIZE; x++) java.util.Arrays.fill(a[x], v);
        return a;
    }

    // Texture Management
    final Map<Integer, BufferedImage> loadedWallTextures = new HashMap<>();
    final Map<Integer, BufferedImage> loadedFloorTextures = new HashMap<>();
    final Map<String, Integer> texturePathToId = new HashMap<>();
    final Map<String, Integer> floorPathToId = new HashMap<>();
    int nextTextureId = 1;
    int nextFloorId = 1;
    BufferedImage skyboxImage = null;
    String currentSkyboxPath = "";

    int score = 0, hp = 8, lives = 3, ammo = 50;

    final Color[] ARROW_COLORS = {
            new Color(0, 230, 255),
            new Color(255, 50, 50),
            new Color(50, 255, 100),
            new Color(255, 220, 0),
            new Color(200, 50, 255)
    };
    int currentArrowColorIdx = 0;

    boolean isInventoryOpen = false;
    boolean isPaused = false;
    boolean hasBow = false;
    boolean bowEquipped = false;
    String equippedWeapon = "none"; // none | bow | crossbow | cross_weapon | sword* | bomb | hookshot | fireball
    boolean hasHookshot = false;
    /** Hookshot pull state */
    boolean hookPulling = false;
    double hookTargetX, hookTargetY;
    int hookPullFrames = 0;
    boolean hasWhistle = false;
    int whistleCooldown = 0;
    int lowHpBeepTimer = 0;
    /** Mods loaded from mods/*.jar */
    final List<String> loadedMods = new ArrayList<>();
    /** Simple TV framebuffer (CRT noise / phosphor) for SCREEN props. */
    BufferedImage tvFrameBuffer;
    int tvFrameTick = 0;

    /**
     * First-person weapon animator (Arena / Daggerfall / King's Field style).
     * Single-frame sprites are moved through phases: idle bob → windup → strike → recover.
     */
    enum WepPhase { IDLE, WINDUP, STRIKE, RECOVER }
    WepPhase wepPhase = WepPhase.IDLE;
    int wepFrame = 0;          // frames into current phase
    double wepBob = 0;         // walk bob phase
    boolean wepHitThisSwing = false; // one hit window per swing

    /** Simple inventory slots — id matches asset stem (skey, finalkey, recovery, ...). */
    static class InvItem {
        String id, name;
        BufferedImage icon;
        int count;
        InvItem(String id, String name, BufferedImage icon, int count) {
            this.id = id; this.name = name; this.icon = icon; this.count = count;
        }
    }
    final List<InvItem> inventory = new ArrayList<>();

    BufferedImage inventoryBg, pauseBg;
    BufferedImage bowDown, bowLeft, bowRight, bowUp;
    BufferedImage arrowSprite, boltSprite, fireballSprite, orbSprite, bombParticleSprite;
    BufferedImage crossbowHud, crossWeaponHud, hookshotHud, crossWeaponToss;
    BufferedImage swordHud, bombHud;
    boolean hasCrossbow = false, hasCrossWeapon = false;
    int magicAmmo = 12;
    BufferedImage[][] playerAnim = new BufferedImage[4][8];
    int playerAnimFrames = 0;

    final List<Thing> things = new ArrayList<>();
    final List<TextBoard> textBoards = new ArrayList<>();
    final List<Projectile> projectiles = new ArrayList<>();

    final Map<String, List<File>> spawnPools = new LinkedHashMap<>();
    final Map<String, Boolean> spawnEnabled = new LinkedHashMap<>();
    final List<File> assetObjs = new ArrayList<>();
    final Map<String, BufferedImage> imageCache = new HashMap<>();
    static final String[] SPAWN_CATEGORY_ORDER = {
            "enemy", "boss", "items", "obstacles", "storage"
    };

    // --- AUDIO SYSTEM ---
    final Map<String, File> musicFiles = new HashMap<>();
    final Map<String, File> fanfareFiles = new HashMap<>();
    Clip currentMusicClip = null;
    String currentMusicTrack = "";
    String savedPrePauseTrack = "";

    String mapName = "(none)";
    /** Absolute path of last loaded map — used by save/autosave. */
    String mapPath = "";
    long lastGameAutosaveMs = 0;
    static final String AUTOSAVE_DIR = "saves";
    static final String AUTOSAVE_FILE = "autosave.sav";
    JLabel status;
    ViewPanel view;
    File assetsDir;
    double fogStrength = 0.0;
    boolean rainEnabled = false;
    final java.util.List<double[]> rainDrops = new java.util.ArrayList<>();

    // ---------------------------------------------------------------
    /** Unity / GameMaker style sorting layers — lower draws first (behind). */
    public enum SpriteLayer {
        FLOOR_DECAL(0),   // blood, shadows, pits
        ITEM(10),         // jars, pickups, chests
        PROP(20),         // boulders, boxes, furniture
        ACTOR(30),        // enemies, NPCs, player-like
        PROJECTILE(40),   // arrows, fireballs
        EFFECT(50),       // particles, flashes
        BILLBOARD(60);    // textboards, signs (in-world UI)

        public final int order;
        SpriteLayer(int order) { this.order = order; }

        static SpriteLayer fromLabel(String label) {
            if (label == null) return PROP;
            return switch (label.toUpperCase()) {
                case "PIT" -> FLOOR_DECAL;
                case "JAR", "BIGITEM", "CHESTSM", "CHESTBG" -> ITEM;
                case "BOX", "STAIRS" -> PROP;
                case "ENEMY", "BOSS" -> ACTOR;
                case "TEXTBOARD" -> BILLBOARD;
                default -> PROP;
            };
        }
    }

    static class TextBoard {
        public double x, y, z;
        public double width, height;
        public boolean isBillboard;
        public String text;
        public BufferedImage texture;
        public SpriteLayer layer = SpriteLayer.BILLBOARD;

        public TextBoard(double x, double y, double z, double width, double height, boolean isBillboard, String text) {
            this.x = x; this.y = y; this.z = z;
            this.width = width; this.height = height;
            this.isBillboard = isBillboard; this.text = text;
            this.texture = generateTextTexture(text);
        }

        private BufferedImage generateTextTexture(String text) {
            int texWidth = 512, texHeight = 256;
            BufferedImage img = new BufferedImage(texWidth, texHeight, BufferedImage.TYPE_INT_ARGB);
            Graphics2D g2 = img.createGraphics();
            g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);

            g2.setColor(Color.BLACK);
            g2.fillRect(0, 0, texWidth, texHeight);
            g2.setColor(new Color(0, 255, 102));
            g2.setStroke(new BasicStroke(8));
            g2.drawRect(4, 4, texWidth - 8, texHeight - 8);

            g2.setFont(new Font("Monospaced", Font.BOLD, 26));
            FontMetrics metrics = g2.getFontMetrics();
            String[] lines = text.split("\n");
            int startY = (texHeight / 2) - ((lines.length - 1) * 20);

            for (int i = 0; i < lines.length; i++) {
                String line = lines[i];
                int textX = (texWidth - metrics.stringWidth(line)) / 2;
                int textY = startY + (i * 36);
                g2.setColor(new Color(0, 255, 102));
                g2.drawString(line, textX, textY);
            }
            g2.dispose();
            return img;
        }
    }

    static class Thing {
        double x, y;
        String label;
        BufferedImage sprite;
        Color tint;
        String sourceName;
        SpriteLayer layer;
        /** true = solid (writes z-buffer). false = alpha/fx (reads z only, no write). */
        boolean opaque = true;
        /** Blocks player movement when true. */
        boolean solid = false;
        /** Hit points; <=0 removes the thing. Enemies start with HP. */
        int hp = 1;
        double scaleW = 1.0, scaleH = 1.0; // relative billboard size
        int damageCooldown = 0; // frames before this enemy can hurt player again
        /** Floor index (0-based) — only active when currentFloor matches. -1 = all floors. */
        int floorIndex = 0;

        Thing(double x, double y, String label, BufferedImage sprite, Color tint, String sourceName) {
            this.x = x; this.y = y; this.label = label;
            this.sprite = sprite; this.tint = tint; this.sourceName = sourceName;
            this.layer = SpriteLayer.fromLabel(label);
            // translucent FX / particles don't occlude
            this.opaque = !(label != null && (label.contains("FIRE") || label.contains("PARTICLE")
                    || label.equalsIgnoreCase("JAR")));
            if (label != null && label.equalsIgnoreCase("JAR")) opaque = false;
            applyBehaviorFromLabelAndSource();
        }

        /**
         * Zelda-style prop rules (filename can refine a generic BOX/JAR label):
         * JAR / jar*     — breakable, drops ammo/HP (not a chest)
         * BOX / box*     — breakable, drops ammo/HP
         * SMBOULDER      — solid, tossable (F push), sword breaks
         * BIGBOULDER     — solid, bomb only
         * CRACKEDWALL    — bomb only (opens path)
         * CHESTSM        — F opens → key
         * CHESTBG        — F opens → weapon / big item
         */
        void applyBehaviorFromLabelAndSource() {
            String u = label == null ? "" : label.toUpperCase();
            String n = sourceName == null ? "" : sourceName.toLowerCase();

            if (u.equals("ENEMY") || u.equals("BOSS")) {
                applyEnemyArchetype();
                return;
            }
            if (u.equals("CHESTSM") || u.equals("CHESTBG")) { solid = true; hp = 999; return; }
            if (u.equals("STAIRS")) {
                solid = true; hp = 999;
                return;
            }
            if (u.equals("HOOKTARGET") || u.equals("TARGET") || u.contains("HOOK")) {
                solid = true; hp = 999; opaque = true;
                return;
            }
            if (u.equals("TV") || u.equals("BROWSER") || u.equals("SCREEN") || u.equals("MONITOR")) {
                solid = true; hp = 999; opaque = true;
                scaleW = 1.2; scaleH = 1.0;
                return;
            }
            if (u.equals("PIT")) { solid = false; hp = 999; layer = SpriteLayer.FLOOR_DECAL; return; }
            if (u.equals("JAR")) { solid = false; hp = 1; opaque = false; return; }
            if (u.equals("BOX")) { solid = true; hp = 1; return; }
            if (u.equals("SMBOULDER")) { solid = true; hp = 2; return; }
            if (u.equals("BIGBOULDER") || u.equals("CRACKEDWALL")) { solid = true; hp = 99; return; }
            if (u.equals("BIGITEM")) { solid = false; hp = 1; return; }

            if (n.contains("jar")) { solid = false; hp = 1; opaque = false; label = "JAR"; return; }
            if (n.contains("box")) { solid = true; hp = 1; label = "BOX"; return; }
            if (n.contains("smboulder")) { solid = true; hp = 2; label = "SMBOULDER"; return; }
            if (n.contains("bigboulder")) { solid = true; hp = 99; label = "BIGBOULDER"; return; }
            if (n.contains("cracked")) { solid = true; hp = 99; label = "CRACKEDWALL"; return; }
            if (n.contains("tree")) { solid = true; hp = 999; label = "TREE"; return; }
            if (n.contains("torch")) { solid = false; hp = 999; opaque = false; label = "TORCH"; return; }
            if (u.equals("LIGHT") || u.equals("TORCH")) { solid = false; hp = 999; opaque = false; return; }
            if (n.contains("finallock") || n.contains("bosslock") || n.contains("doorlock")
                    || n.contains("whistlelock") || n.contains("flutelock")) {
                solid = true; hp = 999; return;
            }
            if (u.equals("WARP") || u.equals("WARPPOINT") || n.contains("warppoint")) {
                solid = false; hp = 999; opaque = false;
                return;
            }
        }

        String lockKind() {
            if (sourceName == null && label == null) return null;
            String n = (sourceName == null ? "" : sourceName.toLowerCase())
                    + " " + (label == null ? "" : label.toLowerCase());
            if (n.contains("finallock")) return "final";
            if (n.contains("bosslock")) return "boss";
            if (n.contains("doorlock")) return "door";
            if (n.contains("whistlelock") || n.contains("flutelock") || n.contains("ocarina")) return "whistle";
            return null;
        }

        boolean onCurrentFloor(int floor) {
            return floorIndex < 0 || floorIndex == floor;
        }

        boolean isBreakable() {
            String u = label == null ? "" : label.toUpperCase();
            return u.equals("JAR") || u.equals("BOX") || u.equals("SMBOULDER");
        }

        boolean isBombOnly() {
            String u = label == null ? "" : label.toUpperCase();
            return u.equals("BIGBOULDER") || u.equals("CRACKEDWALL");
        }

        boolean isTossable() {
            String u = label == null ? "" : label.toUpperCase();
            return u.equals("SMBOULDER") || u.equals("JAR") || u.equals("BOX");
        }

        boolean isPickup() {
            if (label == null) return false;
            String u = label.toUpperCase();
            if (u.equals("BIGITEM")) return true;
            if (sourceName == null) return false;
            String n = sourceName.toLowerCase();
            return n.contains("key") || n.contains("recovery") || n.contains("gauntlet")
                    || n.contains("lantern") || n.contains("note") || n.contains("raft")
                    || n.contains("trpiece") || n.contains("sword")
                    || n.contains("bomb") || n.contains("bow")
                    || n.contains("crossbow") || n.contains("cross_weapon") || n.contains("boomerang")
                    || n.contains("hookshot") || n.contains("hook")
                    || n.contains("whistle") || n.contains("flute") || n.contains("ocarina");
        }

        boolean isChest() {
            return label != null && (label.equalsIgnoreCase("CHESTSM") || label.equalsIgnoreCase("CHESTBG"));
        }

        boolean chestOpen = false;
        int tossFrames = 0;
        double tossVX, tossVY;

        // --- Patrol AI ---
        /** NONE | AREA | PATH */
        String patrolMode = "NONE";
        double homeX, homeY;
        double areaRadius = 2.5;
        final java.util.List<double[]> waypoints = new java.util.ArrayList<>();
        int wpIndex = 0;
        double patrolTX = 0, patrolTY = 0;
        int patrolWait = 0;
        double moveSpeed = 0.025;
        int contactDamage = 1;
        boolean dropsBossKey = false;
        boolean stationary = false;
        /** shy ghosts flee when player is close */
        boolean shy = false;
        boolean electric = false;
        /**
         * Boss-tier foe (filename *_boss* / label BOSS). Lives on the main map —
         * Link's Awakening style: ~4–5 hits with the basic sword.
         */
        boolean isBoss = false;

        /**
         * Script from sprite filename (assets/enemy/*.png).
         * *_boss* suffix → LA-style mini-boss on the same map (not a separate arena).
         * hardsoldier → boss key once; slime family weak; ghosts floaty; etc.
         */
        void applyEnemyArchetype() {
            String n = sourceName == null ? "" : sourceName.toLowerCase();
            // strip extension for suffix checks
            int dot = n.lastIndexOf('.');
            if (dot > 0) n = n.substring(0, dot);
            solid = true;
            patrolMode = "AREA";
            areaRadius = 2.5;
            moveSpeed = 0.022;
            contactDamage = 1;
            hp = 3;
            dropsBossKey = false;
            stationary = false;
            shy = false;
            electric = false;
            isBoss = false;

            // --- Base family stats (regular enemies) ---
            if (n.contains("hardsoldier")) {
                hp = 8; contactDamage = 2; moveSpeed = 0.016; areaRadius = 2.0;
                dropsBossKey = true; // only first kill awards key (global flag)
            } else if (n.contains("soldier")) {
                hp = 5; contactDamage = 1; moveSpeed = 0.018; areaRadius = 2.2;
            } else if (n.contains("magmaslime")) {
                hp = 4; contactDamage = 2; moveSpeed = 0.014; areaRadius = 1.8;
            } else if (n.contains("elecslime") || n.contains("elecghost") || n.contains("elecskull")) {
                hp = 4; contactDamage = 2; moveSpeed = 0.024; areaRadius = 3.0; electric = true;
            } else if (n.contains("slime") || n.contains("greenblob")) {
                hp = 2; contactDamage = 1; moveSpeed = 0.012; areaRadius = 1.6;
            } else if (n.contains("shyghost")) {
                hp = 3; contactDamage = 1; moveSpeed = 0.03; areaRadius = 4.0; shy = true; opaque = false;
            } else if (n.contains("ghost")) {
                hp = 3; contactDamage = 1; moveSpeed = 0.028; areaRadius = 3.5; opaque = false;
            } else if (n.contains("skull")) {
                hp = 4; contactDamage = 1; moveSpeed = 0.02; areaRadius = 2.5;
            } else if (n.contains("jellyfish")) {
                hp = 3; contactDamage = 1; moveSpeed = 0.02; areaRadius = 3.0;
            } else if (n.contains("eyeball")) {
                hp = 3; contactDamage = 1; moveSpeed = 0.026; areaRadius = 4.5;
            } else if (n.contains("cactus")) {
                hp = 5; contactDamage = 2; moveSpeed = 0; areaRadius = 0; stationary = true; patrolMode = "NONE";
            } else if (n.contains("poisonshroom") || n.contains("poison")) {
                hp = 3; contactDamage = 1; moveSpeed = 0.01; areaRadius = 1.2;
            } else if (n.contains("centipede") || n.contains("worm")) {
                hp = 4; contactDamage = 1; moveSpeed = 0.032; areaRadius = 2.0;
            }

            // --- Boss tier: filename has _boss (or label BOSS) ---
            // Same map as regular foes. ~4–5 hits with sword1 (dmg 1).
            boolean bossName = n.contains("_boss") || n.endsWith("boss") || n.startsWith("boss_")
                    || n.contains("boss_") || (label != null && label.equalsIgnoreCase("BOSS"));
            if (bossName) {
                isBoss = true;
                if (label == null || label.equalsIgnoreCase("ENEMY")) label = "BOSS";
                // Link's Awakening / early Mario boss feel
                hp = 5;                 // 5 hits basic sword; 3 with sword2; 2 with sword3
                contactDamage = 2;      // stiffer touch damage
                moveSpeed = Math.min(moveSpeed, 0.02);
                if (areaRadius < 3.0) areaRadius = 3.0;
                scaleW = Math.max(scaleW, 1.25);
                scaleH = Math.max(scaleH, 1.25);
                // Named mini-bosses that should drop the dungeon boss key
                if (n.contains("hardsoldier") || n.contains("bosskey") || n.contains("keyboss")) {
                    dropsBossKey = true;
                }
            }
        }
    }

    /** Global: only the first designated key-boss kill drops boss key. */
    static boolean bossKeyDropped = false;

    private void onEnemyKilled(Thing t) {
        markThingDead(t);
        boolean boss = t.isBoss || (t.label != null && t.label.equalsIgnoreCase("BOSS"));
        int pts = boss ? 500 : 100;
        score += pts;
        spawnParticles(t.x, t.y, new Color(255, 90, 60), boss ? 20 : 10);
        playFanfare(boss ? "itemfanfare" : "smchestepen");
        if (t.dropsBossKey && !bossKeyDropped && !hasItem("bkey")) {
            bossKeyDropped = true;
            BufferedImage key = loadFromSub("items", false, "bkey.png", "BKEY.png");
            addToInventory("bkey", "Boss Key", key, 1);
            status.setText(" // BOSS FELLED — BOSS KEY ACQUIRED  +" + pts);
        } else {
            status.setText((boss ? " // BOSS DEFEATED  +" : " // ENEMY DEFEATED  +") + pts);
        }
    }

    static class Particle {
        double x, y, vx, vy;
        int life, maxLife;
        Color color;
        double size;
        BufferedImage sprite;
        Particle(double x, double y, double vx, double vy, int life, Color color, double size) {
            this.x = x; this.y = y; this.vx = vx; this.vy = vy;
            this.life = life; this.maxLife = life; this.color = color; this.size = size;
        }
        Particle(double x, double y, double vx, double vy, int life, Color color, double size, BufferedImage sprite) {
            this(x, y, vx, vy, life, color, size);
            this.sprite = sprite;
        }
    }

    final List<Particle> particles = new ArrayList<>();

    /** Spawns a small burst of billboard particles, capped by options.particleCap. */
    void spawnParticles(double x, double y, Color color, int count) {
        int cap = options != null ? options.particleCap : 64;
        if (cap <= 0) return;
        for (int i = 0; i < count && particles.size() < cap; i++) {
            double ang = Math.random() * Math.PI * 2;
            double spd = 0.01 + Math.random() * 0.03;
            particles.add(new Particle(x, y, Math.cos(ang) * spd, Math.sin(ang) * spd,
                    14 + (int) (Math.random() * 10), color, 0.25 + Math.random() * 0.15));
        }
        // Trim oldest first if we're still over the cap (e.g. cap lowered mid-game)
        while (particles.size() > cap) particles.remove(0);
    }

    void tickParticles() {
        for (int i = particles.size() - 1; i >= 0; i--) {
            Particle pt = particles.get(i);
            pt.x += pt.vx; pt.y += pt.vy;
            pt.life--;
            if (pt.life <= 0) particles.remove(i);
        }
    }

    static class Projectile {
        double x, y, dirX, dirY;
        double speed = 0.28;
        Color color;
        boolean alive = true;
        boolean isHook = false;
        boolean returning = false;
        boolean isBoomerang = false;
        int damage = 1;
        int life = 90;
        double homeX, homeY;
        BufferedImage sprite;
        String kind = "arrow";
        SpriteLayer layer = SpriteLayer.PROJECTILE;

        Projectile(double x, double y, double dirX, double dirY, Color color) {
            this.x = x; this.y = y; this.dirX = dirX; this.dirY = dirY; this.color = color;
        }

        void update(int[][] map) {
            life--;
            if (life <= 0 && !isHook) {
                if (isBoomerang && !returning) {
                    returning = true;
                    life = 90;
                    double dx = homeX - x, dy = homeY - y;
                    double len = Math.hypot(dx, dy);
                    if (len > 0.001) { dirX = dx / len; dirY = dy / len; }
                } else { alive = false; return; }
            }
            double nx = x + dirX * speed;
            double ny = y + dirY * speed;
            int ix = (int) nx, iy = (int) ny;
            if (ix < 0 || iy < 0 || ix >= MAP_SIZE || iy >= MAP_SIZE) {
                if (isBoomerang && !returning) {
                    returning = true; life = 90;
                    double dx = homeX - x, dy = homeY - y;
                    double len = Math.hypot(dx, dy);
                    if (len > 0.001) { dirX = dx / len; dirY = dy / len; }
                } else alive = false;
            } else {
                int c = map[ix][iy];
                if (c == 1 || c == 2) {
                    if (isBoomerang && !returning) {
                        returning = true; life = 90;
                        double dx = homeX - x, dy = homeY - y;
                        double len = Math.hypot(dx, dy);
                        if (len > 0.001) { dirX = dx / len; dirY = dy / len; }
                    } else if (!isHook) alive = false;
                    else { x = nx; y = ny; }
                } else if (c == 6 && !isHook && !isBoomerang) {
                    alive = false;
                } else {
                    x = nx; y = ny;
                }
            }
            if (isBoomerang && returning && Math.hypot(x - homeX, y - homeY) < 0.45) alive = false;
        }
    }

    public LOCWTTP() {
        options = ChromaOptions.load();
        RENDER_W = Math.max(160, (int) (options.renderWidth * options.renderScale));
        RENDER_H = Math.max(100, (int) (options.renderHeight * options.renderScale));
        maxRaySteps = options.maxRaySteps;
        viewDistance = options.viewDistance > 0 ? options.viewDistance : 24;

        setTitle("LOCWTTP Engine - Textured Walls & Skyboxes");
        setSize(1024, 680);
        setMinimumSize(new Dimension(800, 500));
        setLocationRelativeTo(null);
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);

        assetsDir = resolveAssetsDir();
        scanAssets();
        scanAudio();
        scanMods();
        loadCoreSprites();
        loadWallTextures();

        JPanel root = new JPanel(new BorderLayout(4, 4));
        root.setBackground(currentPalette.bg);
        root.setBorder(new LineBorder(currentPalette.fg, 2));
        root.add(buildToolbar(), BorderLayout.NORTH);

        view = new ViewPanel();
        root.add(view, BorderLayout.CENTER);

        status = new JLabel(" // WASD | Click fire | 1 bow 2 sword 3 bomb 4 hook 5 crossbow 6 cross 7 fireball | I inv");
        status.setFont(new Font("Monospaced", Font.BOLD, options.uiFontSize));
        status.setForeground(currentPalette.fg);
        status.setBorder(new EmptyBorder(4, 8, 4, 8));
        root.add(status, BorderLayout.SOUTH);

        setContentPane(root);

        worldMap = emptyMap();
        for (int i = 0; i < MAP_SIZE; i++) {
            worldMap[i][0] = worldMap[i][MAP_SIZE - 1] = 1;
            worldMap[0][i] = worldMap[MAP_SIZE - 1][i] = 1;
        }

        playMusic("main", true);
    }

    private void loadWallTextures() {
        File texDir = new File(assetsDir, "textures");
        if (!texDir.isDirectory()) return;

        File[] files = texDir.listFiles();
        if (files == null) return;

        for (File f : files) {
            if (f.isFile() && isImageFile(f)) {
                registerTexture(f.getName(), f);
            }
        }
    }

    private int registerTexture(String path, File file) {
        if (texturePathToId.containsKey(path)) {
            return texturePathToId.get(path);
        }
        int id = nextTextureId++;
        texturePathToId.put(path, id);
        if (file != null && file.exists()) {
            BufferedImage img = loadImage(file);
            if (img != null) loadedWallTextures.put(id, img);
        }
        return id;
    }

    private int registerFloorTexture(String path, File file) {
        if (floorPathToId.containsKey(path)) {
            return floorPathToId.get(path);
        }
        int id = nextFloorId++;
        floorPathToId.put(path, id);
        if (file != null && file.exists()) {
            BufferedImage img = loadImage(file);
            if (img != null) loadedFloorTextures.put(id, img);
        }
        return id;
    }

    private boolean isImageFile(File f) {
        String n = f.getName().toLowerCase();
        if (!(n.endsWith(".png") || n.endsWith(".jpg") || n.endsWith(".jpeg") || n.endsWith(".gif"))) return false;
        return isDiffuseTextureName(n);
    }

    /** Reject ShaderMap / PBR sidecar maps so they never replace albedo. */
    static boolean isDiffuseTextureName(String n) {
        if (n == null) return false;
        String s = n.toLowerCase();
        // strip extension for suffix checks
        int dot = s.lastIndexOf('.');
        String stem = dot > 0 ? s.substring(0, dot) : s;
        // common ShaderMap / Substance / Unity suffixes
        String[] reject = {
            "_norm", "_normal", "_nrm", "_n",
            "_ao", "_occ", "_occlusion", "_ambientocclusion",
            "_disp", "_displacement", "_height", "_h", "_bump",
            "_spec", "_specular", "_smoothness", "_rough", "_roughness", "_metal", "_metallic",
            "_emis", "_emission", "_glow",
            "_gloss", "_env", "_reflection"
        };
        for (String r : reject) {
            if (stem.endsWith(r)) return false;
            // also grass_NORM style already covered by lowercased stem
        }
        // trailing tokens after underscore that are pure map types
        if (stem.endsWith("_normalmap") || stem.endsWith("_heightmap")) return false;
        return true;
    }

    // --- AUDIO SYSTEM METHODS ---
    /** CRT-style noise on TV billboards (lightweight “screen is on”). */
    private void tickTvFramebuffer() {
        tvFrameTick++;
        if (tvFrameTick % 3 != 0) return; // update every few frames
        int w = 64, h = 48;
        if (tvFrameBuffer == null || tvFrameBuffer.getWidth() != w) {
            tvFrameBuffer = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        }
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int n = (int) (Math.random() * 40);
                // phosphor green scan
                int g = 30 + n + ((y + tvFrameTick) % 8 == 0 ? 40 : 0);
                int r = n / 2;
                int b = n / 3;
                tvFrameBuffer.setRGB(x, y, (r << 16) | (Math.min(255, g) << 8) | b);
            }
        }
        // border
        for (int x = 0; x < w; x++) {
            tvFrameBuffer.setRGB(x, 0, 0x222222);
            tvFrameBuffer.setRGB(x, h - 1, 0x222222);
        }
        for (int y = 0; y < h; y++) {
            tvFrameBuffer.setRGB(0, y, 0x222222);
            tvFrameBuffer.setRGB(w - 1, y, 0x222222);
        }
    }

    /** Scan mods/ for jars — record names; optional ClassLoader for future hooks. */
    private void scanMods() {
        loadedMods.clear();
        File[] roots = { new File("mods"), new File(assetsDir, "mods"), new File("assets/mods") };
        for (File root : roots) {
            if (!root.isDirectory()) continue;
            File[] jars = root.listFiles((d, n) -> n.toLowerCase().endsWith(".jar"));
            if (jars == null) continue;
            for (File jar : jars) {
                loadedMods.add(jar.getName());
                try {
                    java.net.URLClassLoader cl = new java.net.URLClassLoader(
                            new java.net.URL[]{ jar.toURI().toURL() },
                            getClass().getClassLoader());
                    // Convention: mods may ship META-INF/chroma.mod with main class name — optional
                    try (java.util.jar.JarFile jf = new java.util.jar.JarFile(jar)) {
                        java.util.jar.Manifest man = jf.getManifest();
                        if (man != null) {
                            String main = man.getMainAttributes().getValue("Chroma-Mod-Class");
                            if (main != null && !main.isBlank()) {
                                try {
                                    Class<?> c = Class.forName(main, true, cl);
                                    Object inst = c.getDeclaredConstructor().newInstance();
                                    // optional void onLoad(LOCWTTP)
                                    try {
                                        c.getMethod("onLoad", LOCWTTP.class).invoke(inst, this);
                                    } catch (NoSuchMethodException ignored) {}
                                } catch (Throwable ignored) {}
                            }
                        }
                    }
                } catch (Exception ignored) {}
            }
        }
        if (!loadedMods.isEmpty()) {
            System.out.println("[CHROMA] Mods: " + loadedMods);
        }
    }

    private void scanAudio() {
        musicFiles.clear(); fanfareFiles.clear();
        File musicDir = new File(assetsDir, "music");
        if (musicDir.isDirectory()) {
            File[] files = musicDir.listFiles();
            if (files != null) for (File f : files) if (isAudioFile(f)) musicFiles.put(stripExtension(f.getName()).toLowerCase(), f);
        }
        // fanfare/ + sfx/ — both register as short one-shots
        for (String sub : new String[]{"fanfare", "sfx"}) {
            File dir = new File(assetsDir, sub);
            if (!dir.isDirectory()) continue;
            File[] files = dir.listFiles();
            if (files == null) continue;
            for (File f : files) {
                if (!isAudioFile(f)) continue;
                fanfareFiles.put(stripExtension(f.getName()).toLowerCase(), f);
            }
        }
    }

    private boolean isAudioFile(File f) {
        if (!f.isFile()) return false;
        String name = f.getName().toLowerCase();
        return name.endsWith(".wav") || name.endsWith(".mp3") || name.endsWith(".au") || name.endsWith(".aiff");
    }

    private String stripExtension(String fileName) {
        int dot = fileName.lastIndexOf('.');
        return (dot > 0) ? fileName.substring(0, dot) : fileName;
    }

    public void playMusic(String trackKey, boolean loop) {
        String key = trackKey.toLowerCase();
        if (key.equals(currentMusicTrack) && currentMusicClip != null && currentMusicClip.isRunning()) return;
        stopMusic();
        File file = musicFiles.get(key);
        if (file == null) return;
        try {
            AudioInputStream ais = AudioSystem.getAudioInputStream(file);
            Clip clip = AudioSystem.getClip();
            clip.open(ais);
            if (loop) clip.loop(Clip.LOOP_CONTINUOUSLY); else clip.start();
            currentMusicClip = clip; currentMusicTrack = key;
        } catch (Exception ignored) {}
    }

    public void stopMusic() {
        if (currentMusicClip != null) {
            if (currentMusicClip.isRunning()) currentMusicClip.stop();
            currentMusicClip.close(); currentMusicClip = null;
        }
        currentMusicTrack = "";
    }

    public void playFanfare(String fanfareKey) {
        File file = fanfareFiles.get(fanfareKey.toLowerCase());
        if (file == null) return;
        new Thread(() -> {
            try {
                AudioInputStream ais = AudioSystem.getAudioInputStream(file);
                Clip clip = AudioSystem.getClip();
                clip.open(ais); clip.start();
            } catch (Exception ignored) {}
        }).start();
    }

    private void loadCoreSprites() {
        bowDown = loadFromSub("weapons", false, "bow.png", "BOWDOWN.png", "BOW DOWN.png", "bowdown.png");
        bowLeft = loadFromSub("weapons", false, "BOWLEFT.png", "BOW LEFT.png", "bowleft.png");
        bowRight = loadFromSub("weapons", false, "BOWRIGHT.png", "BOW RIGHT.png", "bowright.png");
        bowUp = loadFromSub("weapons", false, "BOWUP-1.png", "BOW UP.png", "BOWUP.png", "bowup.png");
        swordHud = loadFromSub("weapons", false, "sword1.png", "sword2.png", "sword3.png", "sword.png");
        crossbowHud = loadFromSub("weapons", false, "crossbow.png", "crossbow_retracted.png");
        crossWeaponHud = loadFromSub("weapons", false, "cross_weapon.png");
        crossWeaponToss = loadFromSub("weapons", false, "cross_weapon_toss.png", "cross_weapon.png");
        hookshotHud = loadFromSub("weapons", false, "hookshot.png");
        arrowSprite = loadFromSub("particle", false, "arrows_wood.png", "arrow.png");
        if (arrowSprite == null) arrowSprite = loadFromSub("weapons", false, "arrow.png");
        boltSprite = arrowSprite;
        fireballSprite = loadFromSub("particle", false, "FIREBALL-1.png", "fireball.png", "FIREBALL.png");
        orbSprite = loadFromSub("particle", false, "orb.png", "DOT.png", "DOT2.png");
        bombParticleSprite = loadFromSub("particle", false, "bomb.png");
        bombHud = loadFromSub("weapons", false, "bomb.png");
        arrowSprite = loadFromSub("projectile", false, "ARROW RIGHT.png", "ARROW LIGHT RIGHT.png", "ARROW.png", "arrow.png");
        inventoryBg = loadFromSub("UI", false, "INVENTORY SCREEN.png", "INVENTORY.png", "inventory.png");
        pauseBg = loadFromSub("UI", false, "PAUSE SCREEN.png", "PAUSE.png", "pause.png");
        loadPlayerAnimations();
    }

    private void loadPlayerAnimations() {
        playerAnim = new BufferedImage[4][8]; playerAnimFrames = 0;
        File dir = new File(assetsDir, "player");
        if (!dir.isDirectory()) return;
        File[] files = dir.listFiles();
        if (files == null) return;
        Arrays.sort(files, (a, b) -> a.getName().compareToIgnoreCase(b.getName()));
        int[] counts = new int[4];
        for (File f : files) {
            if (!f.isFile()) continue;
            String n = f.getName().toUpperCase();
            if (!(n.endsWith(".PNG") || n.endsWith(".GIF") || n.endsWith(".JPG") || n.endsWith(".JPEG"))) continue;
            int dirIdx = -1;
            if (n.contains("SOUTH")) dirIdx = 0; else if (n.contains("WEST")) dirIdx = 1;
            else if (n.contains("EAST")) dirIdx = 2; else if (n.contains("NORTH")) dirIdx = 3;
            if (dirIdx < 0 || counts[dirIdx] >= 8) continue;
            BufferedImage img = loadImage(f);
            if (img != null) playerAnim[dirIdx][counts[dirIdx]++] = img;
        }
        for (int d = 0; d < 4; d++) playerAnimFrames = Math.max(playerAnimFrames, counts[d]);
    }

    private BufferedImage loadFromSub(String sub, boolean recursive, String... names) {
        File dir = (sub == null || sub.isEmpty()) ? assetsDir : new File(assetsDir, sub);
        if (dir != null && dir.isDirectory()) {
            for (String name : names) {
                File f = new File(dir, name);
                if (f.isFile()) {
                    BufferedImage img = loadImage(f);
                    if (img != null) return img;
                }
            }
        }
        return null;
    }

    private File resolveAssetsDir() {
        String[] candidates = {
                "assets", "SFRE/assets",
                System.getProperty("user.dir") + File.separator + "assets",
                System.getProperty("user.dir") + File.separator + "SFRE" + File.separator + "assets"
        };
        for (String c : candidates) {
            File f = new File(c); if (f.isDirectory()) return f;
        }
        File created = new File("assets");
        if (!created.exists()) created.mkdirs();
        return created;
    }

    private void scanAssets() {
        spawnPools.clear(); assetObjs.clear();
        if (assetsDir == null || !assetsDir.isDirectory()) return;
        for (String cat : SPAWN_CATEGORY_ORDER) {
            List<File> list = new ArrayList<>();
            File dir = new File(assetsDir, cat);
            if (dir.isDirectory()) collectImages(dir, list);
            spawnPools.put(cat, list);
            spawnEnabled.putIfAbsent(cat, !list.isEmpty() && (cat.equals("enemy") || cat.equals("boss") || cat.equals("items")));
        }
        collectObjs(assetsDir, assetObjs);
        File objDir = new File(assetsDir, "obj");
        if (objDir.isDirectory()) collectObjs(objDir, assetObjs);
    }

    private String summarizePools() {
        StringBuilder sb = new StringBuilder();
        for (String cat : SPAWN_CATEGORY_ORDER) {
            List<File> L = spawnPools.getOrDefault(cat, List.of());
            boolean on = Boolean.TRUE.equals(spawnEnabled.get(cat));
            if (sb.length() > 0) sb.append(' ');
            sb.append(cat).append('=').append(L.size()).append(on ? "*" : "");
        }
        return sb.toString();
    }

    private int enabledImageCount() {
        int n = 0;
        for (String cat : SPAWN_CATEGORY_ORDER) {
            if (Boolean.TRUE.equals(spawnEnabled.get(cat))) n += spawnPools.getOrDefault(cat, List.of()).size();
        }
        return n;
    }

    private List<File> enabledImageList() {
        List<File> all = new ArrayList<>();
        for (String cat : SPAWN_CATEGORY_ORDER) {
            if (Boolean.TRUE.equals(spawnEnabled.get(cat))) all.addAll(spawnPools.getOrDefault(cat, List.of()));
        }
        return all;
    }

    private void collectImages(File dir, List<File> out) {
        File[] files = dir.listFiles();
        if (files == null) return;
        for (File f : files) {
            if (f.isFile() && !out.contains(f)) {
                String n = f.getName().toLowerCase();
                if ((n.endsWith(".png") || n.endsWith(".jpg") || n.endsWith(".jpeg") || n.endsWith(".gif")) && isDiffuseTextureName(n)) out.add(f);
            }
        }
    }

    private void collectObjs(File dir, List<File> out) {
        File[] files = dir.listFiles();
        if (files == null) return;
        for (File f : files) if (f.isFile() && f.getName().toLowerCase().endsWith(".obj") && !out.contains(f)) out.add(f);
    }

    private void showSpawnOptions() {
        JPanel form = new JPanel();
        form.setLayout(new BoxLayout(form, BoxLayout.Y_AXIS));
        form.setBorder(new EmptyBorder(8, 12, 8, 12));
        Map<String, JCheckBox> boxes = new LinkedHashMap<>();
        for (String cat : SPAWN_CATEGORY_ORDER) {
            int n = spawnPools.getOrDefault(cat, List.of()).size();
            boolean on = Boolean.TRUE.equals(spawnEnabled.get(cat));
            JCheckBox cb = new JCheckBox(cat.toUpperCase() + "  (" + n + " files)", on);
            cb.setFont(new Font("Monospaced", Font.BOLD, 13)); cb.setEnabled(n > 0);
            boxes.put(cat, cb); form.add(cb); form.add(Box.createVerticalStrut(4));
        }
        int ok = JOptionPane.showConfirmDialog(this, form, "SPAWN OPTIONS", JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
        if (ok != JOptionPane.OK_OPTION) return;
        for (var e : boxes.entrySet()) spawnEnabled.put(e.getKey(), e.getValue().isSelected());
        status.setText(" // SPAWN FILTER  " + summarizePools() + "  enabledImgs=" + enabledImageCount());
    }

    private JPanel buildToolbar() {
        JPanel bar = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 4));
        bar.setBackground(currentPalette.bg);
        bar.add(btn("LOAD MAP", e -> loadMapDialog()));
        bar.add(btn("SAVE GAME", e -> saveGameDialog()));
        bar.add(btn("LOAD GAME", e -> loadGameDialog()));
        bar.add(btn("RANDOMIZE", e -> randomizeThings()));
        bar.add(btn("SPAWN OPTIONS", e -> showSpawnOptions()));
        bar.add(btn("CLEAR THINGS", e -> { things.clear(); status.setText(" // THINGS CLEARED"); view.repaint(); }));
        bar.add(btn("RESCAN ASSETS", e -> { scanAssets(); scanAudio(); loadCoreSprites(); loadWallTextures(); status.setText(" // RESCANNED"); }));
        bar.add(btn("INVENTORY [I]", e -> toggleInventory()));
        bar.add(btn("PAUSE [ESC]", e -> togglePause()));
        bar.add(btn("PALETTE [TAB]", e -> cyclePalette()));
        bar.add(btn("OPTIONS", e -> {
            if (ChromaOptions.showDialog(this)) {
                options = ChromaOptions.load();
                status.setFont(new Font("Monospaced", Font.BOLD, options.uiFontSize));
                status.setText(" // OPTIONS SAVED — restart engine to apply resolution/FPS/RAM");
            }
        }));
        return bar;
    }

    private JButton btn(String text, ActionListener al) {
        JButton b = new JButton(text);
        int fs = options != null ? options.uiFontSize : 12;
        b.setFont(new Font("Monospaced", Font.BOLD, fs));
        b.setForeground(currentPalette.fg);
        b.setBackground(currentPalette.bg);
        b.setFocusable(false);
        b.setBorder(new LineBorder(currentPalette.fg, options != null && options.largeButtons ? 2 : 1));
        b.setCursor(new Cursor(Cursor.HAND_CURSOR));
        b.addActionListener(al);
        if (options != null && options.largeButtons) {
            b.setMargin(new Insets(6, 10, 6, 10));
        }
        return b;
    }

    private void playWhistle() {
        if (!hasWhistle && !hasItem("whistle") && !hasItem("flute") && !hasItem("ocarina")) {
            status.setText(" // NO WHISTLE");
            playFanfare("error");
            return;
        }
        if (whistleCooldown > 0) return;
        hasWhistle = true;
        whistleCooldown = 45;
        playFanfare("whistle");
        status.setText(" // *whistle*");

        // 1) Unlock whistle-locked barriers in range
        for (int i = things.size() - 1; i >= 0; i--) {
            Thing t = things.get(i);
            if (!t.onCurrentFloor(currentFloor)) continue;
            if (!"whistle".equals(t.lockKind())) continue;
            double dx = t.x - view.posX, dy = t.y - view.posY;
            if (dx * dx + dy * dy > 4.0 * 4.0) continue;
            things.remove(i);
            playFanfare("secret");
            status.setText(" // WHISTLE UNLOCKED A PATH");
            score += 30;
        }

        // 2) Reveal nearby fake walls briefly (mark + secret chime once)
        boolean revealed = false;
        int px = (int) view.posX, py = (int) view.posY;
        for (int x = Math.max(0, px - 3); x <= Math.min(MAP_SIZE - 1, px + 3); x++) {
            for (int y = Math.max(0, py - 3); y <= Math.min(MAP_SIZE - 1, py + 3); y++) {
                if (worldMap[x][y] == 6) {
                    // Fake wall stays walkable; optional visual — convert one cell to open path mark
                    revealed = true;
                }
            }
        }
        if (revealed) {
            playFanfare("secret");
            status.setText(" // SECRET WALLS RESPOND TO THE SONG");
        }

        // 3) Warp to nearest WARP on this floor if any
        Thing warp = null;
        double best = 99;
        for (Thing t : things) {
            if (!t.onCurrentFloor(currentFloor)) continue;
            String u = t.label == null ? "" : t.label.toUpperCase();
            String s = t.sourceName == null ? "" : t.sourceName.toLowerCase();
            if (!(u.equals("WARP") || u.equals("WARPPOINT") || s.contains("warp"))) continue;
            double dx = t.x - view.posX, dy = t.y - view.posY;
            double d = Math.sqrt(dx * dx + dy * dy);
            if (d < best && d > 0.5) { best = d; warp = t; }
        }
        if (warp != null && best < 20) {
            view.posX = warp.x;
            view.posY = warp.y;
            playFanfare("warp");
            status.setText(" // WARPED");
        }
    }

    private void toggleInventory() {
        if (isPaused) return;
        isInventoryOpen = !isInventoryOpen;
        if (isInventoryOpen) playFanfare("pause_open");
        else playFanfare("pause_close");
        status.setText(isInventoryOpen ? " // INVENTORY OPEN" : " // GAMEPLAY RESUMED");
        view.repaint();
    }

    private void toggleThirdPerson() {
        try {
            if (options == null) options = ChromaOptions.load();
            options.thirdPerson = !options.thirdPerson;
            options.save();
            status.setText(options.thirdPerson ? " // THIRD PERSON (F5)" : " // FIRST PERSON (F5)");
            view.repaint();
        } catch (Throwable t) {
            status.setText(" // 3RD PERSON TOGGLE FAILED");
        }
    }

    private void togglePause() {
        isPaused = !isPaused;
        if (isPaused) {
            isInventoryOpen = false; savedPrePauseTrack = currentMusicTrack;
            playMusic("pause menu", true); status.setText(" // PAUSED");
        } else {
            if (!savedPrePauseTrack.isEmpty()) playMusic(savedPrePauseTrack, true);
            else playMusic("main", true);
            status.setText(" // RESUMED");
        }
        view.repaint();
    }

    void grantBow() {
        hasBow = true; bowEquipped = true;
        equippedWeapon = "bow";
        addToInventory("bow", "Bow", bowDown != null ? bowDown : loadFromSub("weapons", false, "bow.png"), 1);
        playFanfare("itempickup");
        status.setText(" // BOW ACQUIRED & EQUIPPED");
        if (view != null) view.repaint();
    }

    private String stemOf(String fileName) {
        if (fileName == null) return "";
        String n = fileName;
        int slash = Math.max(n.lastIndexOf('/'), n.lastIndexOf('\\'));
        if (slash >= 0) n = n.substring(slash + 1);
        int dot = n.lastIndexOf('.');
        return (dot > 0 ? n.substring(0, dot) : n).toLowerCase();
    }

    private void addToInventory(String id, String name, BufferedImage icon, int count) {
        for (InvItem it : inventory) {
            if (it.id.equalsIgnoreCase(id)) {
                it.count += count;
                return;
            }
        }
        inventory.add(new InvItem(id.toLowerCase(), name, icon, count));
    }

    private boolean hasItem(String id) {
        for (InvItem it : inventory) if (it.id.equalsIgnoreCase(id) && it.count > 0) return true;
        return false;
    }

    private boolean consumeItem(String id, int n) {
        for (InvItem it : inventory) {
            if (it.id.equalsIgnoreCase(id) && it.count >= n) {
                it.count -= n;
                if (it.count <= 0) inventory.remove(it);
                return true;
            }
        }
        return false;
    }

    private int itemCount(String id) {
        for (InvItem it : inventory) if (it.id.equalsIgnoreCase(id)) return it.count;
        return 0;
    }

    /** Collect a world Thing into inventory (or apply effect). Returns true if removed from world. */
    private boolean pickupThing(Thing t) {
        String stem = stemOf(t.sourceName);
        String lab = t.label == null ? "" : t.label.toUpperCase();

        if (stem.contains("recovery") || stem.equals("recovery")) {
            hp = Math.min(8, hp + 2);
            playFanfare("itempickup");
            status.setText(" // RECOVERED HP → " + hp);
            return true;
        }
        if (stem.contains("skey") || stem.equals("skey")) {
            addToInventory("skey", "Silver Key", t.sprite, 1);
            playFanfare("itempickup");
            status.setText(" // GOT SILVER KEY");
            return true;
        }
        if (stem.contains("bkey") || stem.equals("bkey")) {
            addToInventory("bkey", "Boss Key", t.sprite, 1);
            playFanfare("itempickup");
            status.setText(" // GOT BOSS KEY");
            return true;
        }
        if (stem.contains("finalkey")) {
            addToInventory("finalkey", "Final Key", t.sprite, 1);
            playFanfare("itempickup");
            status.setText(" // GOT FINAL KEY");
            return true;
        }
        if (stem.contains("crossbow")) {
            hasCrossbow = true;
            addToInventory("crossbow", "Crossbow", t.sprite != null ? t.sprite : crossbowHud, 1);
            equippedWeapon = "crossbow"; bowEquipped = false;
            playFanfare("itempickup");
            status.setText(" // GOT CROSSBOW — press 5");
            return true;
        }
        if (stem.contains("cross_weapon") || stem.contains("boomerang") || stem.contains("gale")) {
            hasCrossWeapon = true;
            addToInventory("cross_weapon", "Cross Weapon", t.sprite != null ? t.sprite : crossWeaponHud, 1);
            equippedWeapon = "cross_weapon"; bowEquipped = false;
            playFanfare("itempickup");
            status.setText(" // GOT CROSS WEAPON — press 6");
            return true;
        }
        if (stem.contains("bow")) {
            grantBow();
            return true;
        }
        if (stem.contains("sword")) {
            String id = stem.contains("3") ? "sword3" : stem.contains("2") ? "sword2" : "sword1";
            addToInventory(id, "Sword", t.sprite, 1);
            equippedWeapon = id;
            bowEquipped = false;
            swordHud = t.sprite;
            playFanfare("itempickup");
            status.setText(" // EQUIPPED " + id.toUpperCase());
            return true;
        }
        if (stem.contains("bomb")) {
            addToInventory("bomb", "Bomb", t.sprite, 1);
            bombHud = t.sprite;
            playFanfare("itempickup");
            status.setText(" // GOT BOMB");
            return true;
        }
        if (stem.contains("hookshot") || stem.equals("hook") || stem.contains("hook")) {
            hasHookshot = true;
            addToInventory("hookshot", "Hookshot", t.sprite, 1);
            equippedWeapon = "hookshot";
            bowEquipped = false;
            playFanfare("itempickup");
            status.setText(" // GOT HOOKSHOT — press 4 to equip");
            return true;
        }
        if (stem.contains("whistle") || stem.contains("flute") || stem.contains("ocarina")) {
            hasWhistle = true;
            addToInventory("whistle", "Whistle", t.sprite, 1);
            equippedWeapon = "whistle";
            bowEquipped = false;
            playFanfare("itemfanfare");
            status.setText(" // GOT WHISTLE — press 5 to equip, use to play");
            return true;
        }
        // Generic collectible
        String id = stem.isEmpty() ? lab.toLowerCase() : stem;
        String name = id;
        addToInventory(id, name, t.sprite, 1);
        if (lab.equals("BIGITEM")) score += 50;
        playFanfare("itempickup");
        status.setText(" // PICKED UP " + name.toUpperCase());
        return true;
    }

    /** Break a jar/box/smboulder — grants ammo/HP, removes thing. */
    private void breakThing(Thing t) {
        markThingDead(t);
        playFanfare("shatter");
        String u = t.label == null ? "" : t.label.toUpperCase();
        Color debris = new Color(180, 140, 90);
        if (u.equals("JAR")) {
            ammo = Math.min(99, ammo + 5);
            if (Math.random() < 0.35) hp = Math.min(8, hp + 1);
            score += 10;
            status.setText(" // JAR SMASHED  ammo=" + ammo);
            debris = new Color(210, 120, 40);
        } else if (u.equals("BOX")) {
            ammo = Math.min(99, ammo + 8);
            if (Math.random() < 0.45) hp = Math.min(8, hp + 1);
            score += 15;
            status.setText(" // BOX BROKEN  ammo=" + ammo);
            debris = new Color(160, 110, 60);
        } else if (u.equals("SMBOULDER")) {
            score += 5;
            status.setText(" // BOULDER BROKEN");
            debris = new Color(140, 140, 140);
        } else {
            score += 5;
            status.setText(" // SMASHED");
        }
        spawnParticles(t.x, t.y, debris, 8);
        playFanfare("itempickup");
        t.hp = 0;
    }

    /** Bomb radius destroy — big boulders, cracked walls, also breakables. */
    private void detonateAt(double bx, double by, double radius) {
        spawnParticles(bx, by, new Color(255, 170, 40), 24);
        for (int i = things.size() - 1; i >= 0; i--) {
            Thing t = things.get(i);
            if (t.hp <= 0) continue;
            double dx = t.x - bx, dy = t.y - by;
            if (dx * dx + dy * dy > radius * radius) continue;
            if (t.isBombOnly() || t.isBreakable()) {
                if (t.label != null && t.label.equalsIgnoreCase("CRACKEDWALL")) {
                    // open the map cell under the cracked wall
                    int cx = (int) t.x, cy = (int) t.y;
                    if (cx >= 0 && cy >= 0 && cx < MAP_SIZE && cy < MAP_SIZE) worldMap[cx][cy] = 0;
                    status.setText(" // CRACKED WALL BLASTED");
                } else if (t.isBombOnly()) {
                    status.setText(" // BOULDER BOMBED");
                } else {
                    breakThing(t);
                }
                things.remove(i);
                score += 20;
            } else if (t.label != null && (t.label.equalsIgnoreCase("ENEMY") || t.label.equalsIgnoreCase("BOSS"))) {
                t.hp -= 3;
                if (t.hp <= 0) {
                    things.remove(i);
                    score += t.label.equalsIgnoreCase("BOSS") ? 500 : 100;
                }
            }
        }
        playFanfare("smchestepen");
    }

    private void placeBomb() {
        if (!consumeItem("bomb", 1) && itemCount("bomb") <= 0) {
            // allow if just equipped and count was tracked
            status.setText(" // NO BOMBS");
            return;
        }
        double bx = view.posX + view.dirX * 0.8;
        double by = view.posY + view.dirY * 0.8;
        status.setText(" // BOMB SET");
        playFanfare("bomb_place");
        // short fuse via timer on EDT
        javax.swing.Timer fuse = new javax.swing.Timer(600, e -> { playFanfare("bomb"); detonateAt(bx, by, 1.4); });
        fuse.setRepeats(false);
        fuse.start();
    }

    private void cyclePalette() {
        HUDPalette[] vals = HUDPalette.values();
        currentPalette = vals[(currentPalette.ordinal() + 1) % vals.length];
        status.setForeground(currentPalette.fg);
        status.setText(" // PALETTE: " + currentPalette.name);
        view.repaint();
    }

    private static int[][] emptyMap() { return new int[MAP_SIZE][MAP_SIZE]; }

    private static int parseFloorIndex(String line, String prefix) {
        try {
            String rest = line.trim().substring(prefix.length()).trim();
            int n = Integer.parseInt(rest.replaceAll("[^0-9]", ""));
            return Math.max(0, Math.min(MAX_FLOORS - 1, n));
        } catch (Exception e) {
            return 0;
        }
    }

    private void allocFloorStack() {
        for (int f = 0; f < MAX_FLOORS; f++) {
            floorGrids[f] = emptyMap();
            floorTexLayers[f] = new int[MAP_SIZE][MAP_SIZE];
            floorFloorTexLayers[f] = new int[MAP_SIZE][MAP_SIZE];
            floorWallHLayers[f] = new int[MAP_SIZE][MAP_SIZE];
            floorGroundHLayers[f] = new int[MAP_SIZE][MAP_SIZE];
            for (int x = 0; x < MAP_SIZE; x++) {
                java.util.Arrays.fill(floorWallHLayers[f][x], 2);
                java.util.Arrays.fill(floorGroundHLayers[f][x], 2);
                floorGrids[f][x][0] = floorGrids[f][x][MAP_SIZE - 1] = 1;
                floorGrids[f][0][x] = floorGrids[f][MAP_SIZE - 1][x] = 1;
            }
            // default player starts to center per floor
            playerStartX[f] = MAP_SIZE / 2.0;
            playerStartY[f] = MAP_SIZE / 2.0 - 3;
            playerStartDirX[f] = 0;
            playerStartDirY[f] = 1;
            hasPlayerStart[f] = false;
        }
        bindActiveFloor(0);
    }

    private void bindActiveFloor(int f) {
        currentFloor = Math.max(0, Math.min(floorCount - 1, f));
        worldMap = floorGrids[currentFloor];
        textureMap = floorTexLayers[currentFloor];
        floorTextureMap = floorFloorTexLayers[currentFloor];
        wallHeightMap = floorWallHLayers[currentFloor];
        groundHeightMap = floorGroundHLayers[currentFloor];
    }

    double[] getSpawnForFloor(int f) {
        int ff = Math.max(0, Math.min(floorCount-1, f));
        if (hasPlayerStart[ff]) return new double[]{playerStartX[ff], playerStartY[ff], playerStartDirX[ff], playerStartDirY[ff]};
        // fallback: find safe open cell near default
        double sx = playerStartX[ff], sy = playerStartY[ff];
        if (sx==0 && sy==0) { sx = MAP_SIZE/2.0; sy = MAP_SIZE/2.0-3; }
        double[] safe = findSafeSpawn(floorGrids[ff], sx, sy);
        return new double[]{safe[0], safe[1], playerStartDirX[ff]!=0||playerStartDirY[ff]!=0?playerStartDirX[ff]:0, playerStartDirY[ff]!=0||playerStartDirX[ff]!=0?playerStartDirY[ff]:1};
    }

    static double[] findSafeSpawn(int[][] grid, double cx, double cy) {
        int size = grid.length;
        int icx = (int)cx, icy = (int)cy;
        for (int rad=0; rad<size; rad++) {
            for (int dx=-rad; dx<=rad; dx++) {
                for (int dy=-rad; dy<=rad; dy++) {
                    int x = icx+dx, y = icy+dy;
                    if (x<1||y<1||x>=size-1||y>=size-1) continue;
                    int c = grid[x][y];
                    if (c==0 || c==5) return new double[]{x+0.5, y+0.5}; // open or road
                }
            }
        }
        return new double[]{cx, cy};
    }

    boolean hasCeilingAbove(double x, double y) {
        if (floorCount<=1 || currentFloor>=floorCount-1) return false;
        int up = currentFloor+1;
        if (up>=MAX_FLOORS || floorGrids[up]==null) return false;
        int ix=(int)x, iy=(int)y;
        if (ix<0||iy<0||ix>=MAP_SIZE||iy>=MAP_SIZE) return false;
        int c = floorGrids[up][ix][iy];
        // if upper floor has floor tile where we are, it's overhead
        return c==0 || c==5; // open above means floor above us
    }


    private void changeFloor(int target) {
        if (target < 0 || target >= floorCount || target == currentFloor) return;
        pendingFloor = target;
        floorFade = 0.05;
        playFanfare(target > currentFloor ? "stairs_up" : "stairs_down");
        status.setText(" // MOVING TO FLOOR " + (target + 1) + "/" + floorCount);
    }

    private void tickFloorFade() {
        if (pendingFloor < 0) {
            if (floorFade > 0) floorFade = Math.max(0, floorFade - 0.08);
            return;
        }
        floorFade += 0.12;
        if (floorFade >= 1.0) {
            bindActiveFloor(pendingFloor);
            pendingFloor = -1;
            floorFade = 1.0;
        }
    }

    private void resizeWorld(int newSize) {
        newSize = Math.max(MAP_SIZE_MIN, Math.min(MAP_SIZE_MAX, newSize));
        MAP_SIZE = newSize;
        wallColors = new Color[MAP_SIZE][MAP_SIZE];
        allocFloorStack();
        floorCount = Math.max(1, floorCount);
        bindActiveFloor(0);
    }


    // ---------- Save / Load (item 8) ----------
    File saveDir() {
        File d = new File(AUTOSAVE_DIR);
        if (!d.isDirectory()) d.mkdirs();
        return d;
    }

    File defaultAutosaveFile() {
        return new File(saveDir(), AUTOSAVE_FILE);
    }

    void saveGameDialog() {
        JFileChooser ch = new JFileChooser(saveDir());
        ch.setSelectedFile(new File(saveDir(), "slot1.sav"));
        ch.setFileFilter(new FileNameExtensionFilter("CHROMA Save (*.sav)", "sav"));
        if (ch.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) return;
        File f = ch.getSelectedFile();
        if (!f.getName().toLowerCase().endsWith(".sav"))
            f = new File(f.getParentFile(), f.getName() + ".sav");
        try {
            saveGame(f);
            status.setText(" // SAVED " + f.getName());
            playFanfare("itempickup");
        } catch (Exception ex) {
            status.setText(" // SAVE FAILED: " + ex.getMessage());
        }
    }

    void loadGameDialog() {
        JFileChooser ch = new JFileChooser(saveDir());
        ch.setFileFilter(new FileNameExtensionFilter("CHROMA Save (*.sav)", "sav"));
        if (ch.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) return;
        try {
            loadGame(ch.getSelectedFile());
            status.setText(" // LOADED " + ch.getSelectedFile().getName());
            playFanfare("smchestepen");
        } catch (Exception ex) {
            status.setText(" // LOAD FAILED: " + ex.getMessage());
            JOptionPane.showMessageDialog(this, "Load failed:\n" + ex.getMessage());
        }
    }

    void quickAutosave() {
        if (options == null || !options.gameAutosave || options.gameAutosaveMinutes <= 0) return;
        if (mapPath == null || mapPath.isBlank()) return;
        try {
            saveGame(defaultAutosaveFile());
            lastGameAutosaveMs = System.currentTimeMillis();
            status.setText(" // AUTOSAVE");
        } catch (Exception ignored) {}
    }

    void tickGameAutosave() {
        if (options == null || !options.gameAutosave || options.gameAutosaveMinutes <= 0) return;
        if (isPaused || isInventoryOpen) return;
        long interval = options.gameAutosaveMinutes * 60_000L;
        long now = System.currentTimeMillis();
        if (lastGameAutosaveMs == 0) lastGameAutosaveMs = now;
        if (now - lastGameAutosaveMs >= interval) quickAutosave();
    }

    void saveGame(File f) throws IOException {
        if (view == null) throw new IOException("No view");
        try (PrintWriter w = new PrintWriter(new FileWriter(f))) {
            w.println("CHROMA_SAVE 1");
            w.println("MAP " + (mapPath == null ? "" : mapPath.replace(' ', '\u0001')));
            w.println("MAPNAME " + mapName);
            w.printf("POS %.4f %.4f %.4f %.4f %d%n", view.posX, view.posY, view.dirX, view.dirY, currentFloor);
            w.printf("STATS %d %d %d %d%n", hp, lives, score, ammo);
            w.println("EQUIP " + (equippedWeapon == null ? "none" : equippedWeapon));
            w.println("FLAGS " + (hasBow ? 1 : 0) + " " + (hasHookshot ? 1 : 0) + " "
                    + (hasWhistle ? 1 : 0) + " " + (bossKeyDropped ? 1 : 0));
            // Inventory
            StringBuilder inv = new StringBuilder();
            for (InvItem it : inventory) {
                if (it == null || it.count <= 0) continue;
                if (inv.length() > 0) inv.append(',');
                inv.append(it.id).append(':').append(it.count);
            }
            w.println("INV " + inv);
            // Opened chests + removed enemies/breakables snapshot
            for (Thing th : things) {
                if (th.isChest() && th.chestOpen) {
                    w.printf("CHEST %d %.2f %.2f%n", th.floorIndex, th.x, th.y);
                }
            }
            // Also record entities that were on map at load but are gone — use DEAD tags from a set
            for (String key : deadThingKeys) {
                w.println("DEAD " + key);
            }
        }
        lastGameAutosaveMs = System.currentTimeMillis();
    }

    /** Keys of defeated/removed things: floor:ix:iy:label */
    final java.util.Set<String> deadThingKeys = new java.util.HashSet<>();

    String thingKey(Thing t) {
        return t.floorIndex + ":" + (int) Math.floor(t.x) + ":" + (int) Math.floor(t.y) + ":"
                + (t.label == null ? "?" : t.label.toUpperCase());
    }

    void markThingDead(Thing t) {
        if (t == null) return;
        deadThingKeys.add(thingKey(t));
    }

    void loadGame(File f) throws IOException {
        java.util.List<String> lines = java.nio.file.Files.readAllLines(f.toPath());
        if (lines.isEmpty() || !lines.get(0).startsWith("CHROMA_SAVE"))
            throw new IOException("Not a CHROMA save file");
        String map = "", equip = "none";
        double px = 0, py = 0, dx = 1, dy = 0;
        int floor = 0, nhp = 8, nlives = 3, nscore = 0, nammo = 50;
        int fBow = 0, fHook = 0, fWhistle = 0, fBossKey = 0;
        String invLine = "";
        java.util.List<String> chestLines = new ArrayList<>();
        java.util.List<String> deadLines = new ArrayList<>();
        for (String line : lines) {
            if (line.startsWith("MAP ")) map = line.substring(4).replace('\u0001', ' ').trim();
            else if (line.startsWith("MAPNAME ")) mapName = line.substring(8).trim();
            else if (line.startsWith("POS ")) {
                String[] p = line.substring(4).trim().split("\\s+");
                px = Double.parseDouble(p[0]); py = Double.parseDouble(p[1]);
                dx = Double.parseDouble(p[2]); dy = Double.parseDouble(p[3]);
                floor = Integer.parseInt(p[4]);
            } else if (line.startsWith("STATS ")) {
                String[] p = line.substring(6).trim().split("\\s+");
                nhp = Integer.parseInt(p[0]); nlives = Integer.parseInt(p[1]);
                nscore = Integer.parseInt(p[2]); nammo = Integer.parseInt(p[3]);
            } else if (line.startsWith("EQUIP ")) equip = line.substring(6).trim();
            else if (line.startsWith("FLAGS ")) {
                String[] p = line.substring(6).trim().split("\\s+");
                fBow = Integer.parseInt(p[0]); fHook = Integer.parseInt(p[1]);
                fWhistle = Integer.parseInt(p[2]); fBossKey = Integer.parseInt(p[3]);
            } else if (line.startsWith("INV ")) invLine = line.substring(4).trim();
            else if (line.startsWith("CHEST ")) chestLines.add(line.substring(6).trim());
            else if (line.startsWith("DEAD ")) deadLines.add(line.substring(5).trim());
        }
        if (map != null && !map.isBlank()) {
            File mf = new File(map);
            if (!mf.isFile()) throw new IOException("Map not found: " + map);
            loadMap(mf);
        }
        // Restore player
        if (view != null) {
            view.posX = px; view.posY = py;
            view.dirX = dx; view.dirY = dy;
            view.planeX = FOV * view.dirY;
            view.planeY = -FOV * view.dirX;
        }
        if (floor != currentFloor) { bindActiveFloor(floor); }
        hp = nhp; lives = nlives; score = nscore; ammo = nammo;
        equippedWeapon = equip;
        hasBow = fBow != 0; hasHookshot = fHook != 0; hasWhistle = fWhistle != 0;
        bossKeyDropped = fBossKey != 0;
        // Inventory rebuild
        inventory.clear();
        if (!invLine.isBlank()) {
            for (String part : invLine.split(",")) {
                String[] kv = part.split(":");
                if (kv.length < 2) continue;
                String id = kv[0].trim();
                int cnt = Integer.parseInt(kv[1].trim());
                String pretty = id.substring(0, 1).toUpperCase() + id.substring(1);
                BufferedImage icon = loadFromSub("items", false, id + ".png", id.toUpperCase() + ".png");
                if (icon == null) icon = loadFromSub("weapons", false, id + ".png");
                addToInventory(id, pretty, icon, cnt);
            }
        }
        // Open chests
        for (String cl : chestLines) {
            String[] p = cl.split("\\s+");
            if (p.length < 3) continue;
            int fi = Integer.parseInt(p[0]);
            double cx = Double.parseDouble(p[1]), cy = Double.parseDouble(p[2]);
            for (Thing th : things) {
                if (th.floorIndex != fi) continue;
                if (!th.isChest()) continue;
                if (Math.abs(th.x - cx) < 0.6 && Math.abs(th.y - cy) < 0.6) {
                    th.chestOpen = true;
                    th.solid = true;
                }
            }
        }
        // Remove dead things
        deadThingKeys.clear();
        deadThingKeys.addAll(deadLines);
        for (int i = things.size() - 1; i >= 0; i--) {
            Thing th = things.get(i);
            if (deadThingKeys.contains(thingKey(th))) things.remove(i);
        }
        lastGameAutosaveMs = System.currentTimeMillis();
        if (view != null) view.repaint();
    }

    private void loadMapDialog() {
        File start = new File("assets/maps");
        if (!start.isDirectory()) start = new File(".");
        JFileChooser ch = new JFileChooser(start);
        ch.setFileFilter(new FileNameExtensionFilter("Map File (*.map)", "map"));
        if (ch.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) return;
        try { loadMap(ch.getSelectedFile()); }
        catch (Exception ex) { JOptionPane.showMessageDialog(this, "Load failed: " + ex.getMessage()); }
    }

    private static final Map<String, String> THING_CATEGORY_MAP = new HashMap<>();
    static {
        THING_CATEGORY_MAP.put("CHESTSM", "storage");
        THING_CATEGORY_MAP.put("CHESTBG", "storage");
        THING_CATEGORY_MAP.put("ENEMY", "enemy");
        THING_CATEGORY_MAP.put("BOSS", "boss");
        THING_CATEGORY_MAP.put("JAR", "obstacles");
        THING_CATEGORY_MAP.put("BOX", "obstacles");
        THING_CATEGORY_MAP.put("SMBOULDER", "obstacles");
        THING_CATEGORY_MAP.put("BIGBOULDER", "obstacles");
        THING_CATEGORY_MAP.put("CRACKEDWALL", "obstacles");
        THING_CATEGORY_MAP.put("BIGITEM", "items");
        THING_CATEGORY_MAP.put("PIT", "obstacles");
        THING_CATEGORY_MAP.put("STAIRS", "obstacles");
        THING_CATEGORY_MAP.put("TREE", "obstacles");
        THING_CATEGORY_MAP.put("TORCH", "obstacles");
        THING_CATEGORY_MAP.put("TV", "props");
        THING_CATEGORY_MAP.put("BROWSER", "props");
        THING_CATEGORY_MAP.put("SCREEN", "props");
        THING_CATEGORY_MAP.put("MONITOR", "props");
    }

    void loadMap(File file) throws IOException {
        mapPath = file.getAbsolutePath();
        mapName = file.getName();
        deadThingKeys.clear();
        // First pass: detect SIZE so we can allocate Daggerfall-scale maps
        int detectedSize = MAP_SIZE;
        try (BufferedReader br = new BufferedReader(new FileReader(file))) {
            String line;
            while ((line = br.readLine()) != null) {
                line = line.trim();
                if (line.startsWith("SIZE ")) {
                    try {
                        int sz = Integer.parseInt(line.substring(5).trim());
                        if (sz >= MAP_SIZE_MIN && sz <= MAP_SIZE_MAX) detectedSize = sz;
                    } catch (Exception ignored) {}
                    break;
                }
            }
        }
        MAP_SIZE = detectedSize;
        floorCount = 1;
        currentFloor = 0;
        allocFloorStack();
        int[][] newMap = emptyMap();
        int[][] newTextures = new int[MAP_SIZE][MAP_SIZE];
        int[][] newFloors = new int[MAP_SIZE][MAP_SIZE];
        Color[][] newColors = new Color[MAP_SIZE][MAP_SIZE];
        int[][] newWallH = new int[MAP_SIZE][MAP_SIZE];
        int[][] newGroundH = new int[MAP_SIZE][MAP_SIZE];
        for (int x = 0; x < MAP_SIZE; x++)
            for (int y = 0; y < MAP_SIZE; y++) { newWallH[x][y] = 2; newGroundH[x][y] = 2; }
        double px = MAP_SIZE / 2.0, py = MAP_SIZE / 2.0 - 3, dx = 0, dy = 1;
        boolean inGrid = false, inTex = false, inFloors = false, inColors = false, inTextboards = false, inThings = false;
        boolean inWallH = false, inGroundH = false;
        int gridY = 0, texY = 0, floorY = 0, wallHY = 0, groundHY = 0;
        int parseFloor = 0; // which floor layer current grid/tex block fills
        List<String[]> pendingThings = new ArrayList<>();
        skyboxImage = null;
        fogStrength = 0;
        rainEnabled = false;
        boolean mapSpecifiedFog = false;

        textBoards.clear();
        Pattern tbPattern = Pattern.compile("^([\\d.]+)\\s+([\\d.]+)\\s+([\\d.]+)\\s+([\\d.]+)\\s+([\\d.]+)\\s+(true|false)\\s+\"([^\"]+)\"$");
        Pattern thingPattern = Pattern.compile(
                "^(\\S+)\\s+([\\d.]+)\\s+([\\d.]+)(?:\\s+\"([^\"]*)\")?(?:\\s+(SOLID|NOSOLID))?(?:\\s+AREA\\s+([\\d.]+))?(?:\\s+PATH\\s+\"([^\"]*)\")?(?:\\s+FLOOR\\s+(\\d+))?$",
                Pattern.CASE_INSENSITIVE);

        try (BufferedReader br = new BufferedReader(new FileReader(file))) {
            String line;
            while ((line = br.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith("#")) continue;
                if (line.startsWith("SIZE ")) {
                    // already applied
                } else if (line.startsWith("FLOORS ")) {
                    try {
                        floorCount = Math.max(1, Math.min(MAX_FLOORS, Integer.parseInt(line.substring(7).trim())));
                    } catch (Exception ignored) {}
                } else if (line.startsWith("FOG ")) {
                    try { fogStrength = Double.parseDouble(line.substring(4).trim()); mapSpecifiedFog = true; } catch (Exception ignored) {}
                } else if (line.startsWith("RAIN ")) {
                    rainEnabled = line.toLowerCase().contains("true") || line.contains("1");
                } else if (line.startsWith("SKYBOX ")) {
                    currentSkyboxPath = line.substring(7).trim();
                    File sbFile = new File(assetsDir, currentSkyboxPath);
                    if (sbFile.isFile()) skyboxImage = loadImage(sbFile);
                } else if (line.toUpperCase().startsWith("PLAYER_F")) {
                    try {
                        String[] t = line.split("\\s+");
                        // PLAYER_Fn x y dx dy
                        String head = t[0].toUpperCase(); // PLAYER_F0
                        int fn = Integer.parseInt(head.replaceAll("[^0-9]",""));
                        if (fn>=0 && fn<MAX_FLOORS && t.length>=5) {
                            playerStartX[fn] = Double.parseDouble(t[1]);
                            playerStartY[fn] = Double.parseDouble(t[2]);
                            playerStartDirX[fn] = Double.parseDouble(t[3]);
                            playerStartDirY[fn] = Double.parseDouble(t[4]);
                            hasPlayerStart[fn]=true;
                            if (fn==0) { px = playerStartX[fn]; py = playerStartY[fn]; dx = playerStartDirX[fn]; dy = playerStartDirY[fn]; }
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
                    parseFloor = parseFloorIndex(line, "BEGIN_GRID_F");
                    inGrid = true; gridY = 0;
                } else if (line.toUpperCase().startsWith("END_GRID_F")) {
                    inGrid = false;
                } else if (line.equalsIgnoreCase("BEGIN_GRID") || line.equalsIgnoreCase("BEGIN_MAP")) {
                    parseFloor = 0; inGrid = true; gridY = 0;
                } else if (line.equalsIgnoreCase("END_GRID") || line.equalsIgnoreCase("END_MAP")) {
                    inGrid = false;
                } else if (line.toUpperCase().startsWith("BEGIN_TEXTURES_F")) {
                    parseFloor = parseFloorIndex(line, "BEGIN_TEXTURES_F");
                    inTex = true; texY = 0;
                } else if (line.toUpperCase().startsWith("END_TEXTURES_F")) {
                    inTex = false;
                } else if (line.equalsIgnoreCase("BEGIN_TEXTURES")) {
                    parseFloor = 0; inTex = true; texY = 0;
                } else if (line.equalsIgnoreCase("END_TEXTURES")) {
                    inTex = false;
                } else if (line.toUpperCase().startsWith("BEGIN_FLOORS_F")) {
                    parseFloor = parseFloorIndex(line, "BEGIN_FLOORS_F");
                    inFloors = true; floorY = 0;
                } else if (line.toUpperCase().startsWith("END_FLOORS_F")) {
                    inFloors = false;
                } else if (line.equalsIgnoreCase("BEGIN_FLOORS")) {
                    parseFloor = 0; inFloors = true; floorY = 0;
                } else if (line.equalsIgnoreCase("END_FLOORS")) {
                    inFloors = false;
                } else if (line.toUpperCase().startsWith("BEGIN_WALLHEIGHTS_F")) {
                    parseFloor = parseFloorIndex(line, "BEGIN_WALLHEIGHTS_F");
                    inWallH = true; wallHY = 0;
                } else if (line.toUpperCase().startsWith("END_WALLHEIGHTS_F")) {
                    inWallH = false;
                } else if (line.equalsIgnoreCase("BEGIN_WALLHEIGHTS")) {
                    parseFloor = 0; inWallH = true; wallHY = 0;
                } else if (line.equalsIgnoreCase("END_WALLHEIGHTS")) {
                    inWallH = false;
                } else if (line.toUpperCase().startsWith("BEGIN_GROUNDHEIGHTS_F")) {
                    parseFloor = parseFloorIndex(line, "BEGIN_GROUNDHEIGHTS_F");
                    inGroundH = true; groundHY = 0;
                } else if (line.toUpperCase().startsWith("END_GROUNDHEIGHTS_F")) {
                    inGroundH = false;
                } else if (line.equalsIgnoreCase("BEGIN_GROUNDHEIGHTS")) {
                    parseFloor = 0; inGroundH = true; groundHY = 0;
                } else if (line.equalsIgnoreCase("END_GROUNDHEIGHTS")) {
                    inGroundH = false;
                } else if (line.equalsIgnoreCase("BEGIN_COLORS")) {
                    inColors = true;
                } else if (line.equalsIgnoreCase("END_COLORS")) {
                    inColors = false;
                } else if (line.equalsIgnoreCase("BEGIN_TEXTBOARDS")) {
                    inTextboards = true;
                } else if (line.equalsIgnoreCase("END_TEXTBOARDS")) {
                    inTextboards = false;
                } else if (line.equalsIgnoreCase("BEGIN_THINGS")) {
                    inThings = true;
                } else if (line.equalsIgnoreCase("END_THINGS")) {
                    inThings = false;
                } else if (inGrid) {
                    String[] t = line.split("\\s+");
                    int[][] destGrid = (parseFloor == 0) ? newMap : floorGrids[parseFloor];
                    int[][] destTex = (parseFloor == 0) ? newTextures : floorTexLayers[parseFloor];
                    if (destGrid == null) {
                        floorGrids[parseFloor] = emptyMap();
                        destGrid = floorGrids[parseFloor];
                    }
                    for (int x = 0; x < Math.min(t.length, MAP_SIZE); x++) {
                        String token = t[x];
                        try {
                            destGrid[x][gridY] = Integer.parseInt(token);
                        } catch (NumberFormatException nfe) {
                            destGrid[x][gridY] = 1;
                            File f = new File(assetsDir, token);
                            if (!f.exists()) f = new File(token);
                            if (destTex != null) destTex[x][gridY] = registerTexture(token, f);
                            else newTextures[x][gridY] = registerTexture(token, f);
                        }
                    }
                    gridY++; if (gridY >= MAP_SIZE) inGrid = false;
                } else if (inTex) {
                    String[] t = line.split("\\s+");
                    for (int x = 0; x < Math.min(t.length, MAP_SIZE); x++) {
                        String token = t[x];
                        try {
                            newTextures[x][texY] = Integer.parseInt(token);
                        } catch (NumberFormatException nfe) {
                            File f = new File(assetsDir, token);
                            if (!f.exists()) f = new File(token);
                            newTextures[x][texY] = registerTexture(token, f);
                        }
                    }
                    texY++; if (texY >= MAP_SIZE) inTex = false;
                } else if (inWallH) {
                    String[] t = line.split("\\s+");
                    int[][] dest = (parseFloor == 0) ? newWallH : floorWallHLayers[parseFloor];
                    if (dest == null) {
                        floorWallHLayers[parseFloor] = new int[MAP_SIZE][MAP_SIZE];
                        dest = floorWallHLayers[parseFloor];
                        for (int x = 0; x < MAP_SIZE; x++) java.util.Arrays.fill(dest[x], 2);
                    }
                    for (int x = 0; x < Math.min(t.length, MAP_SIZE); x++) {
                        try {
                            int v = Integer.parseInt(t[x]);
                            dest[x][wallHY] = Math.max(1, Math.min(4, v));
                        } catch (Exception ignored) {}
                    }
                    wallHY++; if (wallHY >= MAP_SIZE) inWallH = false;
                } else if (inGroundH) {
                    String[] t = line.split("\\s+");
                    int[][] dest = (parseFloor == 0) ? newGroundH : floorGroundHLayers[parseFloor];
                    if (dest == null) {
                        floorGroundHLayers[parseFloor] = new int[MAP_SIZE][MAP_SIZE];
                        dest = floorGroundHLayers[parseFloor];
                        for (int x = 0; x < MAP_SIZE; x++) java.util.Arrays.fill(dest[x], 2);
                    }
                    for (int x = 0; x < Math.min(t.length, MAP_SIZE); x++) {
                        try {
                            int v = Integer.parseInt(t[x]);
                            dest[x][groundHY] = Math.max(0, Math.min(3, v));
                        } catch (Exception ignored) {}
                    }
                    groundHY++; if (groundHY >= MAP_SIZE) inGroundH = false;
                } else if (inFloors) {
                    String[] t = line.split("\\s+");
                    for (int x = 0; x < Math.min(t.length, MAP_SIZE); x++) {
                        String token = t[x];
                        if (token.equals("0") || token.isEmpty()) {
                            newFloors[x][floorY] = 0;
                        } else {
                            try {
                                newFloors[x][floorY] = Integer.parseInt(token);
                            } catch (NumberFormatException nfe) {
                                File f = new File(assetsDir, token);
                                if (!f.exists()) f = new File(token);
                                newFloors[x][floorY] = registerFloorTexture(token, f);
                            }
                        }
                    }
                    floorY++; if (floorY >= MAP_SIZE) inFloors = false;
                } else if (inColors && line.startsWith("C ")) {
                    String[] t = line.split("\\s+");
                    if (t.length >= 6) {
                        int x = Integer.parseInt(t[1]), y = Integer.parseInt(t[2]);
                        int r = Integer.parseInt(t[3]), g = Integer.parseInt(t[4]), b = Integer.parseInt(t[5]);
                        if (x >= 0 && y >= 0 && x < MAP_SIZE && y < MAP_SIZE) newColors[x][y] = new Color(r, g, b);
                    }
                } else if (inTextboards) {
                    Matcher matcher = tbPattern.matcher(line);
                    if (matcher.find()) {
                        double tbx = Double.parseDouble(matcher.group(1));
                        double tby = Double.parseDouble(matcher.group(2));
                        double tbz = Double.parseDouble(matcher.group(3));
                        double tbw = Double.parseDouble(matcher.group(4));
                        double tbh = Double.parseDouble(matcher.group(5));
                        boolean bb = Boolean.parseBoolean(matcher.group(6));
                        String txt = matcher.group(7).replace("\\n", "\n");
                        textBoards.add(new TextBoard(tbx, tby, tbz, tbw, tbh, bb, txt));
                    }
                } else if (inThings) {
                    Matcher tm = thingPattern.matcher(line);
                    if (tm.find()) {
                        pendingThings.add(new String[]{
                                tm.group(1), tm.group(2), tm.group(3), tm.group(4), tm.group(5),
                                tm.group(6), tm.group(7), tm.group(8)});
                    }
                }
            }
        }

        // Commit floor 0 from primary buffers; higher floors already filled via GRID_Fn
        floorGrids[0] = newMap;
        floorTexLayers[0] = newTextures;
        floorFloorTexLayers[0] = newFloors;
        floorWallHLayers[0] = newWallH;
        floorGroundHLayers[0] = newGroundH;
        wallColors = newColors;
        // Any missing higher floor → empty bordered room (not a copy of F0)
        for (int f = 1; f < floorCount; f++) {
            if (floorGrids[f] == null) {
                floorGrids[f] = emptyMap();
                for (int i = 0; i < MAP_SIZE; i++) {
                    floorGrids[f][i][0] = floorGrids[f][i][MAP_SIZE - 1] = 1;
                    floorGrids[f][0][i] = floorGrids[f][MAP_SIZE - 1][i] = 1;
                }
            }
            if (floorTexLayers[f] == null) floorTexLayers[f] = new int[MAP_SIZE][MAP_SIZE];
            if (floorFloorTexLayers[f] == null) floorFloorTexLayers[f] = new int[MAP_SIZE][MAP_SIZE];
            if (floorWallHLayers[f] == null) {
                floorWallHLayers[f] = new int[MAP_SIZE][MAP_SIZE];
                for (int x = 0; x < MAP_SIZE; x++) java.util.Arrays.fill(floorWallHLayers[f][x], 2);
            }
            if (floorGroundHLayers[f] == null) {
                floorGroundHLayers[f] = new int[MAP_SIZE][MAP_SIZE];
                for (int x = 0; x < MAP_SIZE; x++) java.util.Arrays.fill(floorGroundHLayers[f][x], 2);
            }
        }
        bindActiveFloor(0);
        if (!mapSpecifiedFog) {
            fogStrength = options.fogEnabled ? 0.5 : 0.0;
        }
        mapName = file.getName();
        // use per-floor start for initial spawn (floor 0)
        if (hasPlayerStart[0]) { px = playerStartX[0]; py = playerStartY[0]; dx = playerStartDirX[0]; dy = playerStartDirY[0]; }
        view.posX = px; view.posY = py; view.dirX = dx; view.dirY = dy;
        // copy into runtime arrays for safety if this is first load
        playerStartX[0]=px; playerStartY[0]=py; playerStartDirX[0]=dx; playerStartDirY[0]=dy; hasPlayerStart[0]=true;
        // reset weather particles on load
        rainDrops.clear();
        if (rainEnabled) {
            for (int i = 0; i < 80; i++) {
                rainDrops.add(new double[]{ Math.random(), Math.random(), 0.01 + Math.random() * 0.03 });
            }
        }
        view.planeX = FOV * view.dirY; view.planeY = -FOV * view.dirX;
        things.clear(); projectiles.clear();
        for (String[] t : pendingThings) {
            try {
                Boolean solidOverride = null;
                if (t.length > 4 && t[4] != null) {
                    if (t[4].equalsIgnoreCase("SOLID")) solidOverride = true;
                    else if (t[4].equalsIgnoreCase("NOSOLID")) solidOverride = false;
                }
                Thing th = spawnAuthoredThing(t[0].toUpperCase(), Double.parseDouble(t[1]), Double.parseDouble(t[2]), t[3], solidOverride);
                if (th != null) {
                    if (t.length > 5 && t[5] != null) {
                        th.patrolMode = "AREA";
                        th.areaRadius = Double.parseDouble(t[5]);
                        th.stationary = false;
                    }
                    if (t.length > 6 && t[6] != null && !t[6].isEmpty()) {
                        th.patrolMode = "PATH";
                        th.waypoints.clear();
                        for (String part : t[6].split(";")) {
                            String[] xy = part.trim().split(",");
                            if (xy.length >= 2) {
                                th.waypoints.add(new double[]{
                                        Double.parseDouble(xy[0].trim()),
                                        Double.parseDouble(xy[1].trim())});
                            }
                        }
                        th.stationary = false;
                    }
                    if (t.length > 7 && t[7] != null) {
                        try { th.floorIndex = Integer.parseInt(t[7]); } catch (Exception ignored) {}
                    } else {
                        th.floorIndex = 0;
                    }
                }
            } catch (Exception ignored) {}
        }
        playMusic("d1", true);
        status.setText(" // LOADED " + mapName + (pendingThings.isEmpty() ? "" : "  things=" + pendingThings.size()));
        view.repaint();
    }

    private void spawnAuthoredThing(String legendCategory, double x, double y, String assetFileName) {
        spawnAuthoredThing(legendCategory, x, y, assetFileName, null);
    }

    private Thing spawnAuthoredThing(String legendCategory, double x, double y, String assetFileName, Boolean solidOverride) {
        String pool = THING_CATEGORY_MAP.getOrDefault(legendCategory, "items");
        List<File> list = spawnPools.getOrDefault(pool, List.of());
        BufferedImage sprite = null;
        String src = legendCategory.toLowerCase();

        if (assetFileName != null && !assetFileName.isEmpty()) {
            File direct = new File(assetsDir, assetFileName);
            if (direct.isFile()) {
                sprite = loadImage(direct); src = assetFileName;
            } else {
                outer:
                for (List<File> l : spawnPools.values()) {
                    for (File f : l) {
                        if (f.getName().equalsIgnoreCase(assetFileName)) { sprite = loadImage(f); src = f.getName(); break outer; }
                    }
                }
            }
        }
        if (sprite == null) {
            for (File f : list) {
                if (f.getName().toLowerCase().contains(legendCategory.toLowerCase())) {
                    sprite = loadImage(f); src = f.getName(); break;
                }
            }
        }
        if (sprite == null && !list.isEmpty()) {
            File f = list.get(0); sprite = loadImage(f); src = f.getName();
        }
        Thing th = new Thing(x, y, legendCategory, sprite, legendTint(legendCategory), src);
        if (solidOverride != null) th.solid = solidOverride;
        th.homeX = x; th.homeY = y;
        things.add(th);
        return th;
    }

    private Color legendTint(String cat) {
        return switch (cat) {
            case "ENEMY" -> new Color(255, 80, 80);
            case "BOSS" -> new Color(255, 0, 0);
            case "CHESTSM", "CHESTBG" -> new Color(255, 200, 0);
            case "JAR" -> new Color(255, 140, 0);
            case "BIGITEM" -> new Color(255, 60, 160);
            case "BOX" -> new Color(200, 60, 40);
            case "SMBOULDER" -> new Color(140, 140, 140);
            case "BIGBOULDER" -> new Color(90, 90, 90);
            case "CRACKEDWALL" -> new Color(180, 160, 120);
            case "TREE" -> new Color(40, 160, 60);
            case "TORCH" -> new Color(255, 160, 40);
            case "PIT" -> new Color(40, 40, 40);
            case "STAIRS" -> new Color(180, 180, 255);
            default -> currentPalette.fg;
        };
    }

    private void randomizeThings() {
        things.clear();
        Random rng = new Random();
        List<Point> open = new ArrayList<>();
        for (int x = 1; x < MAP_SIZE - 1; x++)
            for (int y = 1; y < MAP_SIZE - 1; y++)
                if (worldMap[x][y] == 0) open.add(new Point(x, y));

        if (open.isEmpty()) { status.setText(" // NO OPEN CELLS"); return; }
        List<File> pool = enabledImageList();
        int count = Math.min(14, Math.max(4, open.size() / 6));
        Collections.shuffle(open, rng);

        for (int i = 0; i < count; i++) {
            Point cell = open.get(i);
            double tx = cell.x + 0.5, ty = cell.y + 0.5;
            BufferedImage sprite = null;
            String src = "marker_" + i;
            Color tint = currentPalette.fg;

            if (!pool.isEmpty()) {
                File imgFile = pool.get(rng.nextInt(pool.size()));
                sprite = loadImage(imgFile); src = imgFile.getName(); tint = Color.WHITE;
            }
            String label = src.length() > 14 ? src.substring(0, 12) + ".." : src;
            things.add(new Thing(tx, ty, label, sprite, tint, src));
        }

        status.setText(" // SPAWNED " + things.size() + "  pool=" + pool.size());
        view.repaint();
    }

    private BufferedImage loadImage(File f) {
        String key = f.getAbsolutePath();
        if (imageCache.containsKey(key)) return imageCache.get(key);
        try {
            BufferedImage img = ImageIO.read(f);
            imageCache.put(key, img); return img;
        } catch (Exception e) { return null; }
    }

    // ---------------------------------------------------------------
    class ViewPanel extends JPanel {
        double posX = MAP_SIZE / 2.0, posY = MAP_SIZE / 2.0 - 3;
        double dirX = 0, dirY = 1;
        double planeX = FOV, planeY = 0;
        final double moveSpeed = 0.06, rotSpeed = 0.05;
        double eyeOffset = 0.0;
        double jumpVel = 0.0;
        boolean onGround = true;
        boolean ducking = false;
        final double mouseSens = 0.0035;
        final Set<Integer> keys = new HashSet<>();
        final BufferedImage frame = new BufferedImage(RENDER_W, RENDER_H, BufferedImage.TYPE_INT_RGB);
        final int[] pixels = ((DataBufferInt) frame.getRaster().getDataBuffer()).getData();
        final double[] zbuffer = new double[RENDER_W];
        final javax.swing.Timer loop;

        boolean mouseLook = true;
        int lastMouseX = -1;

        Rectangle pauseContinue = new Rectangle(), pauseQuit = new Rectangle();

        ViewPanel() {
            setBackground(Color.BLACK); setFocusable(true);
            int ms = options != null ? options.engineTimerMs() : 33;
            loop = new javax.swing.Timer(ms, e -> {
                tick();
                repaint();
                if (options != null && options.vsyncEnabled) {
                    try { Toolkit.getDefaultToolkit().sync(); } catch (Exception ignored) {}
                }
            });
            loop.start();

            addKeyListener(new KeyAdapter() {
                public void keyPressed(KeyEvent e) {
                    int c = e.getKeyCode();
                    if (c == KeyEvent.VK_ESCAPE) { togglePause(); return; }
                    if (isPaused) return;
                    if (c == KeyEvent.VK_I) toggleInventory();
                    if (c == KeyEvent.VK_F5) toggleThirdPerson();
                    else if (c == KeyEvent.VK_TAB) cyclePalette();
                    else if (c == KeyEvent.VK_R) randomizeThings();
                    else if (c == KeyEvent.VK_M) { mouseLook = !mouseLook; lastMouseX = -1; }
                    else if (c == KeyEvent.VK_B) { hasBow = true; bowEquipped = !bowEquipped; }
                    else if (c == KeyEvent.VK_F) tryInteract();
                    else if (c == KeyEvent.VK_E) {
                        if (tryUseScreen()) return; // TV / browser mod — else fall through to rotate
                        setKey(c, true);
                        return;
                    }
                    else if (c == KeyEvent.VK_F1) grantBow();
                    else if (c == KeyEvent.VK_1) {
                        if (hasBow || hasItem("bow")) { equippedWeapon = "bow"; bowEquipped = true; status.setText(" // EQUIP BOW"); }
                    }
                    else if (c == KeyEvent.VK_2) {
                        if (hasItem("sword1") || hasItem("sword2") || hasItem("sword3")) {
                            equippedWeapon = hasItem("sword3") ? "sword3" : hasItem("sword2") ? "sword2" : "sword1";
                            bowEquipped = false; status.setText(" // EQUIP " + equippedWeapon.toUpperCase());
                        }
                    }
                    else if (c == KeyEvent.VK_3) { if (hasItem("bomb")) { equippedWeapon = "bomb"; bowEquipped = false; status.setText(" // EQUIP BOMB"); } }
                    else if (c == KeyEvent.VK_5) {
                        if (hasCrossbow || hasItem("crossbow")) { equippedWeapon = "crossbow"; bowEquipped = false; status.setText(" // EQUIP CROSSBOW"); }
                    }
                    else if (c == KeyEvent.VK_6) {
                        if (hasCrossWeapon || hasItem("cross_weapon")) { equippedWeapon = "cross_weapon"; bowEquipped = false; status.setText(" // EQUIP CROSS WEAPON"); }
                    }
                    else if (c == KeyEvent.VK_7) {
                        equippedWeapon = "fireball"; bowEquipped = false; status.setText(" // EQUIP FIREBALL");
                    }
                    else if (c == KeyEvent.VK_4) {
                        if (hasHookshot || hasItem("hookshot") || hasItem("hook")) {
                            hasHookshot = true;
                            equippedWeapon = "hookshot";
                            bowEquipped = false;
                            status.setText(" // EQUIP HOOKSHOT");
                        } else status.setText(" // NO HOOKSHOT — place HOOK / hookshot pickup");
                    }
                    else if (c == KeyEvent.VK_5) {
                        if (hasWhistle || hasItem("whistle") || hasItem("flute") || hasItem("ocarina")) {
                            hasWhistle = true;
                            equippedWeapon = "whistle";
                            bowEquipped = false;
                            status.setText(" // EQUIP WHISTLE — press use/click to play");
                        } else status.setText(" // NO WHISTLE");
                    }
                    else if (c == KeyEvent.VK_G && equippedWeapon.equals("bomb")) placeBomb();
                    else if (c == KeyEvent.VK_SHIFT) primaryAttack(); // Shift = melee/primary
                    else setKey(c, true); // Space=jump, Ctrl=duck (held in tick)
                }
                public void keyReleased(KeyEvent e) { setKey(e.getKeyCode(), false); }
            });

            addMouseListener(new MouseAdapter() {
                public void mousePressed(MouseEvent e) {
                    requestFocusInWindow();
                    if (isPaused) { handlePauseClick(e.getX(), e.getY()); return; }
                    if (isInventoryOpen) return;
                    if (SwingUtilities.isLeftMouseButton(e)) primaryAttack();
                }
                public void mouseEntered(MouseEvent e) { lastMouseX = -1; }
            });

            addMouseMotionListener(new MouseMotionAdapter() {
                public void mouseMoved(MouseEvent e) { handleMouseLook(e); }
                public void mouseDragged(MouseEvent e) { handleMouseLook(e); }
            });
        }

        void handleMouseLook(MouseEvent e) {
            if (!mouseLook || isPaused || isInventoryOpen) { lastMouseX = e.getX(); return; }
            if (lastMouseX < 0) { lastMouseX = e.getX(); return; }
            int dx = e.getX() - lastMouseX; lastMouseX = e.getX();
            if (dx != 0) rotate(dx * mouseSens);
        }

        void handlePauseClick(int mx, int my) {
            if (pauseContinue.contains(mx, my)) togglePause();
            else if (pauseQuit.contains(mx, my)) System.exit(0);
        }

        /** Left-click / primary: sword swing, bow shot, or bomb place — KF/Arena timing. */
        void primaryAttack() {
            if (wepPhase != WepPhase.IDLE && wepPhase != WepPhase.RECOVER) return;
            if (equippedWeapon.equals("bow") || (bowEquipped && hasBow && equippedWeapon.equals("bow"))) {
                shootArrow();
                return;
            }
            if (equippedWeapon.equals("crossbow")) {
                shootCrossbow();
                return;
            }
            if (equippedWeapon.equals("cross_weapon")) {
                throwCrossWeapon();
                return;
            }
            if (equippedWeapon.equals("fireball")) {
                shootFireball();
                return;
            }
            if (equippedWeapon.equals("bomb")) {
                placeBomb();
                return;
            }
            if (equippedWeapon.equals("hookshot") || equippedWeapon.equals("hook")) {
                fireHookshot();
                return;
            }
            if (equippedWeapon.equals("whistle") || equippedWeapon.equals("flute") || equippedWeapon.equals("ocarina")) {
                playWhistle();
                return;
            }
            if (equippedWeapon.startsWith("sword")) {
                startSwordSwing();
                return;
            }
            startSwordSwing();
        }

        void fireHookshot() {
            if (!hasHookshot && !hasItem("hookshot") && !hasItem("hook")) {
                status.setText(" // NO HOOKSHOT");
                return;
            }
            if (hookPulling) return;
            hasHookshot = true;
            wepPhase = WepPhase.STRIKE;
            wepFrame = 0;
            weaponFlash = 10;
            Projectile h = new Projectile(posX, posY, dirX, dirY, new Color(200, 200, 180));
            h.isHook = true;
            h.speed = 0.32;
            projectiles.add(h);
            playFanfare("hookshot");
            status.setText(" // HOOKSHOT!");
        }

        void startHookPull(double tx, double ty) {
            hookPulling = true;
            hookTargetX = tx;
            hookTargetY = ty;
            hookPullFrames = 28;
            status.setText(" // HOOKED — pulling");
        }

        void tickHookPull() {
            if (!hookPulling) return;
            double dx = hookTargetX - posX, dy = hookTargetY - posY;
            double len = Math.sqrt(dx * dx + dy * dy);
            if (len < 0.35 || hookPullFrames <= 0) {
                hookPulling = false;
                hookPullFrames = 0;
                return;
            }
            double step = 0.18;
            double nx = posX + dx / len * step;
            double ny = posY + dy / len * step;
            if (walk(nx, posY)) posX = nx;
            if (walk(posX, ny)) posY = ny;
            // slip through fake walls while pulling
            int ix = (int) posX, iy = (int) posY;
            if (ix >= 0 && iy >= 0 && ix < MAP_SIZE && iy < MAP_SIZE && worldMap[ix][iy] == 6) {
                // already walkable
            }
            hookPullFrames--;
        }

        void startSwordSwing() {
            wepPhase = WepPhase.WINDUP;
            wepFrame = 0;
            wepHitThisSwing = false;
            weaponFlash = 12;
            playFanfare("sword");
        }

        void shootArrow() {
            if (!hasBow || ammo <= 0) return;
            if (!equippedWeapon.equals("bow") && !bowEquipped) return;
            ammo--;
            wepPhase = WepPhase.STRIKE;
            wepFrame = 0;
            weaponFlash = 8;
            Projectile p = new Projectile(posX, posY, dirX, dirY, ARROW_COLORS[currentArrowColorIdx]);
            p.kind = "arrow";
            p.damage = 1;
            p.speed = 0.30;
            p.sprite = arrowSprite;
            p.life = 70;
            projectiles.add(p);
            playFanfare("arrow");
            spawnParticles(posX + dirX * 0.4, posY + dirY * 0.4, new Color(220, 200, 120), 3);
        }

        void shootCrossbow() {
            if ((!hasCrossbow && !hasItem("crossbow")) || ammo <= 0) {
                status.setText(" // CROSSBOW NEEDS AMMO");
                return;
            }
            hasCrossbow = true;
            ammo = Math.max(0, ammo - 2); // heavier bolt
            wepPhase = WepPhase.STRIKE;
            wepFrame = 0;
            weaponFlash = 10;
            Projectile p = new Projectile(posX, posY, dirX, dirY, new Color(180, 200, 220));
            p.kind = "bolt";
            p.damage = 2;
            p.speed = 0.38;
            p.sprite = boltSprite != null ? boltSprite : arrowSprite;
            p.life = 80;
            projectiles.add(p);
            playFanfare("arrow");
            status.setText(" // CROSSBOW BOLT  ammo=" + ammo);
            spawnParticles(posX + dirX * 0.4, posY + dirY * 0.4, new Color(200, 220, 255), 4);
        }

        void throwCrossWeapon() {
            if (!hasCrossWeapon && !hasItem("cross_weapon")) {
                status.setText(" // NO CROSS WEAPON");
                return;
            }
            // one in flight at a time
            for (Projectile q : projectiles) if (q.isBoomerang && q.alive) {
                status.setText(" // CROSS WEAPON OUT");
                return;
            }
            hasCrossWeapon = true;
            wepPhase = WepPhase.STRIKE;
            wepFrame = 0;
            weaponFlash = 10;
            Projectile p = new Projectile(posX, posY, dirX, dirY, new Color(120, 220, 255));
            p.kind = "boomerang";
            p.isBoomerang = true;
            p.damage = 2;
            p.speed = 0.26;
            p.life = 55;
            p.homeX = posX; p.homeY = posY;
            p.sprite = crossWeaponToss != null ? crossWeaponToss : crossWeaponHud;
            projectiles.add(p);
            playFanfare("arrow");
            status.setText(" // CROSS WEAPON THROW");
        }

        void shootFireball() {
            if (magicAmmo <= 0) {
                status.setText(" // OUT OF MAGIC");
                return;
            }
            magicAmmo--;
            wepPhase = WepPhase.STRIKE;
            wepFrame = 0;
            weaponFlash = 12;
            Projectile p = new Projectile(posX, posY, dirX, dirY, new Color(255, 120, 40));
            p.kind = "fireball";
            p.damage = 3;
            p.speed = 0.22;
            p.life = 60;
            p.sprite = fireballSprite != null ? fireballSprite : orbSprite;
            projectiles.add(p);
            playFanfare("arrow");
            status.setText(" // FIREBALL  mp=" + magicAmmo);
            spawnParticles(posX + dirX * 0.5, posY + dirY * 0.5, new Color(255, 100, 30), 6);
        }

        void tickWeapon() {
            // walk bob when moving
            boolean moving = keys.contains(87) || keys.contains(83) || keys.contains(65) || keys.contains(68);
            if (moving && wepPhase == WepPhase.IDLE) wepBob += 0.35;
            else wepBob *= 0.9;

            if (wepPhase == WepPhase.IDLE) return;
            wepFrame++;
            // Phase lengths (frames @ ~30fps) — deliberate KF feel
            int wind = 5, strike = 6, recover = 8;
            if (equippedWeapon.contains("sword3")) { wind = 4; strike = 7; recover = 7; } // faster gold
            if (equippedWeapon.contains("sword1")) { wind = 6; strike = 5; recover = 9; }

            if (wepPhase == WepPhase.WINDUP && wepFrame >= wind) {
                wepPhase = WepPhase.STRIKE;
                wepFrame = 0;
            } else if (wepPhase == WepPhase.STRIKE) {
                // active hit frames 1–4
                if (!wepHitThisSwing && wepFrame >= 1 && wepFrame <= 4) {
                    meleeHitWindow();
                    wepHitThisSwing = true;
                }
                if (wepFrame >= strike) {
                    wepPhase = WepPhase.RECOVER;
                    wepFrame = 0;
                }
            } else if (wepPhase == WepPhase.RECOVER && wepFrame >= recover) {
                wepPhase = WepPhase.IDLE;
                wepFrame = 0;
            }
        }

        /** Arena-style forward cone hit during strike phase. */
        void meleeHitWindow() {
            double reach = 1.15;
            if (equippedWeapon.contains("sword3")) reach = 1.35;
            if (equippedWeapon.contains("sword2")) reach = 1.25;
            double fx = posX + dirX * 0.7, fy = posY + dirY * 0.7;
            for (int i = things.size() - 1; i >= 0; i--) {
                Thing t = things.get(i);
                if (t.hp <= 0) continue;
                double dx = t.x - posX, dy = t.y - posY;
                double dist = Math.sqrt(dx * dx + dy * dy);
                if (dist > reach || dist < 0.05) continue;
                // must be roughly in front (dot with dir)
                double nx = dx / dist, ny = dy / dist;
                double dot = nx * dirX + ny * dirY;
                if (dot < 0.45) continue;
                String lab = t.label == null ? "" : t.label.toUpperCase();
                if (lab.equals("ENEMY") || lab.equals("BOSS")) {
                    int dmg = equippedWeapon.contains("sword3") ? 3 : equippedWeapon.contains("sword2") ? 2 : 1;
                    t.hp -= dmg;
                    spawnParticles(t.x, t.y, new Color(255, 220, 120), 4);
                    status.setText(" // SLASH! " + lab + " hp=" + t.hp);
                    if (t.hp <= 0) {
                        onEnemyKilled(t);
                        things.remove(i);
                    }
                } else if (t.isBreakable()) {
                    breakThing(t);
                    things.remove(i);
                }
            }
        }

        void setKey(int c, boolean d) {
            int id = switch (c) {
                case KeyEvent.VK_W, KeyEvent.VK_UP -> 87;
                case KeyEvent.VK_S, KeyEvent.VK_DOWN -> 83;
                case KeyEvent.VK_A -> 65;
                case KeyEvent.VK_D -> 68;
                case KeyEvent.VK_Q, KeyEvent.VK_LEFT -> 81;
                case KeyEvent.VK_E, KeyEvent.VK_RIGHT -> 69;
                default -> c;
            };
            if (d) keys.add(id); else keys.remove(id);
        }

        void tick() {
            if (isPaused || isInventoryOpen) return;
            double spd = moveSpeed;
            if (ducking) spd *= 0.45;
            if (keys.contains(87)) move(spd);
            if (keys.contains(83)) move(-spd);
            if (keys.contains(65)) strafe(-spd);
            if (keys.contains(68)) strafe(spd);
            if (keys.contains(81)) rotate(-rotSpeed);
            if (keys.contains(69)) rotate(rotSpeed);

            // Jump / duck
            ducking = keys.contains(17); // Ctrl
            if (keys.contains(32) && onGround && !ducking) { // Space
                jumpVel = 0.085;
                onGround = false;
            }
            if (!onGround) {
                eyeOffset += jumpVel;
                jumpVel -= 0.006; // gravity
                if (eyeOffset <= 0) {
                    eyeOffset = 0;
                    jumpVel = 0;
                    onGround = true;
                }
            }
            if (ducking && onGround) eyeOffset = -0.22;
            else if (onGround && !ducking && eyeOffset < 0) eyeOffset = 0;

            // Cell hazards after movement
            PitType pt = getPitTypeAt(posX, posY); if (pt != PitType.NONE) handlePit(pt);
            if (isUpdraftAt(posX, posY)) handleUpdraft();
            tickFloorFade();

            if (weaponFlash > 0) weaponFlash--;
            if (whistleCooldown > 0) whistleCooldown--;
            // Low HP warning beep
            if (hp > 0 && hp <= 2) {
                if (lowHpBeepTimer <= 0) {
                    playFanfare("lowhealth");
                    lowHpBeepTimer = 40;
                } else lowHpBeepTimer--;
            } else lowHpBeepTimer = 0;
            tickWeapon();
            tickEnemyPatrols();
            tickGameAutosave();
            tickTvFramebuffer();
            tickHookPull();
            tickParticles();
            // Projectiles: walls + enemy hits + hook targets
            for (int i = projectiles.size() - 1; i >= 0; i--) {
                Projectile p = projectiles.get(i);
                p.update(worldMap);
                if (!p.alive) { projectiles.remove(i); continue; }
                for (int ti = things.size() - 1; ti >= 0; ti--) {
                    Thing t = things.get(ti);
                    if (t.hp <= 0) continue;
                    String lab = t.label == null ? "" : t.label.toUpperCase();
                    double dx = t.x - p.x, dy = t.y - p.y;
                    if (dx * dx + dy * dy >= 0.4 * 0.4) continue;
                    if (p.isHook && isHookTarget(t)) {
                        p.alive = false;
                        startHookPull(t.x, t.y);
                        break;
                    }
                    if (p.isHook) {
                        // hooks don't smash jars — pass or stop on solid non-target
                        if (t.solid && !t.isBreakable()) { p.alive = false; break; }
                        continue;
                    }
                    if (lab.equals("ENEMY") || lab.equals("BOSS")) {
                        int dmg = Math.max(1, p.damage);
                        t.hp -= dmg;
                        if (!p.isBoomerang) p.alive = false; // boomerang pierces once then returns next frames
                        else if (!p.returning) { p.returning = true; p.life = 90; }
                        spawnParticles(t.x, t.y, new Color(255, 200, 80), 5);
                        if (t.hp <= 0) {
                            onEnemyKilled(t);
                            things.remove(ti);
                        }
                        break;
                    }
                    if (t.isBreakable()) {
                        breakThing(t);
                        things.remove(ti);
                        p.alive = false;
                        break;
                    }
                }
                if (!p.alive) projectiles.remove(i);
            }
            // Toss physics
            for (int ti = things.size() - 1; ti >= 0; ti--) {
                Thing t = things.get(ti);
                if (t.tossFrames <= 0) continue;
                t.tossFrames--;
                double nx = t.x + t.tossVX, ny = t.y + t.tossVY;
                int ix = (int) nx, iy = (int) ny;
                if (ix < 0 || iy < 0 || ix >= MAP_SIZE || iy >= MAP_SIZE || worldMap[ix][iy] != 0) {
                    // hit wall → break if breakable
                    if (t.isBreakable()) { breakThing(t); things.remove(ti); }
                    else { t.tossFrames = 0; t.solid = true; }
                } else {
                    t.x = nx; t.y = ny;
                    // hit enemy while flying
                    for (int ei = things.size() - 1; ei >= 0; ei--) {
                        if (ei == ti) continue;
                        Thing e = things.get(ei);
                        if (e.label == null) continue;
                        String el = e.label.toUpperCase();
                        if (!(el.equals("ENEMY") || el.equals("BOSS"))) continue;
                        double ddx = e.x - t.x, ddy = e.y - t.y;
                        if (ddx * ddx + ddy * ddy < 0.4 * 0.4) {
                            e.hp -= 2;
                            if (t.isBreakable()) { breakThing(t); things.remove(ti); }
                            else { t.tossFrames = 0; t.solid = true; }
                            if (e.hp <= 0) {
                                things.remove(ei);
                                score += el.equals("BOSS") ? 500 : 100;
                            }
                            break;
                        }
                    }
                    if (t.tossFrames == 0) t.solid = t.isBreakable() || t.label.equalsIgnoreCase("SMBOULDER");
                }
            }
            // Contact damage + auto-pickup
            for (int ti = things.size() - 1; ti >= 0; ti--) {
                Thing t = things.get(ti);
                if (t.damageCooldown > 0) t.damageCooldown--;
                if (t.hp <= 0) continue;
                if (!t.onCurrentFloor(currentFloor)) continue;
                String lab = t.label == null ? "" : t.label.toUpperCase();
                double dx = t.x - posX, dy = t.y - posY;
                double d2 = dx * dx + dy * dy;
                if (lab.equals("ENEMY") || lab.equals("BOSS")) {
                    if (d2 < 0.45 * 0.45 && t.damageCooldown <= 0) {
                        int dmg = Math.max(1, t.contactDamage);
                        if (t.electric) dmg = Math.max(dmg, 2);
                        hp = Math.max(0, hp - dmg);
                        t.damageCooldown = t.electric ? 30 : 40;
                        playFanfare("hurt");
                        status.setText(" // HIT! HP " + hp + (t.electric ? " (shock)" : ""));
                        if (hp <= 0) {
                            lives--;
                            hp = 8;
                            double[] sp = getSpawnForFloor(currentFloor);
                            posX = sp[0]; posY = sp[1];
                            dirX = sp[2]; dirY = sp[3];
                            planeX = FOV * dirY; planeY = -FOV * dirX;
                            status.setText(" // YOU DIED — lives left: " + lives);
                            if (lives < 0) {
                                isPaused = true;
                                status.setText(" // GAME OVER");
                            }
                        }
                    }
                } else if (lab.equals("PIT") && d2 < 0.4 * 0.4) {
                    fallInPit();
                } else if (lab.equals("STAIRS") && d2 < 0.5 * 0.5) {
                    // Stairs: cycle floor or use name hint floor2 / up / down
                    int dest = currentFloor;
                    String sn = t.sourceName == null ? "" : t.sourceName.toLowerCase();
                    if (sn.contains("down") || sn.contains("floor1") || sn.contains("b1"))
                        dest = Math.max(0, currentFloor - 1);
                    else if (sn.contains("up") || sn.contains("floor3") || sn.contains("floor2"))
                        dest = Math.min(floorCount - 1, currentFloor + 1);
                    else
                        dest = (currentFloor + 1) % Math.max(1, floorCount);
                    if (dest != currentFloor) changeFloor(dest);
                } else if (t.isPickup() && !t.solid && d2 < 0.55 * 0.55) {
                    if (pickupThing(t)) things.remove(ti);
                } else if (t.isPickup() && d2 < 0.4 * 0.4) {
                    // solid pickups (rare) still collect on touch
                    if (pickupThing(t)) things.remove(ti);
                }
            }
        }

        void move(double a) {
            if (isWaterAt(posX, posY) && !hasItem("raft")) a *= 0.45; // slog without raft
            double nx = posX + dirX * a, ny = posY + dirY * a;
            if (walk(nx, posY)) posX = nx; if (walk(posX, ny)) posY = ny;
        }

        void strafe(double a) {
            if (isWaterAt(posX, posY) && !hasItem("raft")) a *= 0.45;
            double nx = posX + dirY * a, ny = posY - dirX * a;
            if (walk(nx, posY)) posX = nx; if (walk(posX, ny)) posY = ny;
        }

        // Pit types: 3=void death, 7=fake trap, 8=open hole down (basement/sky island fall), 9=updraft/launch up to island
        static final int CELL_OPEN = 0, CELL_WALL = 1, CELL_DOOR = 2, CELL_PIT_VOID = 3, CELL_WATER = 4, CELL_ROAD = 5, CELL_FAKE_WALL = 6, CELL_FAKE_FLOOR = 7, CELL_PIT_OPEN = 8;
        public enum PitType { NONE, VOID, FAKE, OPEN }
        /**
         * Map cells:
         * 0 open · 1 wall · 2 door · 3 pit void (death) · 4 water · 5 road
         * 6 fake wall (renders solid, walkable) · 7 fake floor (looks open, is void) · 8 open hole (see floor below, fall down)
         */
        boolean walk(double x, double y) {
            int ix = (int) x, iy = (int) y;
            if (ix < 0 || iy < 0 || ix >= MAP_SIZE || iy >= MAP_SIZE) return false;
            int cell = worldMap[ix][iy];
            if (cell == 1 || cell == 2) return false; // real wall / closed door, 8/9 are walkable (pits/updrafts)
            // 0,3,4,5,6,7 walkable (6 secret passage, 7 drops)
            final double r = 0.28;
            for (Thing t : things) {
                if (!t.onCurrentFloor(currentFloor)) continue;
                if (!t.solid || t.hp <= 0) continue;
                // Hook targets are solid but don't block if we're mid-pull
                if (hookPulling && isHookTarget(t)) continue;
                double dx = t.x - x, dy = t.y - y;
                if (dx * dx + dy * dy < r * r) return false;
            }
            return true;
        }

        boolean isWaterAt(double x, double y) {
            int ix = (int) x, iy = (int) y;
            if (ix < 0 || iy < 0 || ix >= MAP_SIZE || iy >= MAP_SIZE) return false;
            return worldMap[ix][iy] == 4;
        }

        PitType getPitTypeAt(double x, double y) {
            int ix = (int) x, iy = (int) y;
            if (ix < 0 || iy < 0 || ix >= MAP_SIZE || iy >= MAP_SIZE) return PitType.NONE;
            int c = worldMap[ix][iy];
            if (c==CELL_PIT_VOID) return PitType.VOID;
            if (c==CELL_FAKE_FLOOR) {
                try { if (groundHeightMap[ix][iy] > 2) return PitType.NONE; } catch (Exception ignored) {}
                return PitType.FAKE;
            }
            if (c==CELL_PIT_OPEN) return PitType.OPEN;
            return PitType.NONE;
        }

        boolean isPitAt(double x, double y) {
            return getPitTypeAt(x,y) != PitType.NONE;
        }

        void handlePit(PitType pt) {
            if (pt == PitType.NONE) return;
            if (pt == PitType.OPEN) {
                // Open hole - SKY and INDOOR: skydive down to next walkable floor below (Skyward Sword style)
                if ((mapType == MapType.INDOOR || mapType == MapType.SKY) && currentFloor > 0) {
                    double sx = posX, sy = posY;
                    // Sky islands: keep falling until we find a floor with open cell, not just F-1 (skydiving)
                    int target = -1;
                    for (int f = currentFloor - 1; f >= 0; f--) {
                        try {
                            int ix = (int)sx, iy = (int)sy;
                            if (ix<0||iy<0||ix>=MAP_SIZE||iy>=MAP_SIZE) continue;
                            int c = floorGrids[f][ix][iy];
                            if (c==CELL_OPEN || c==CELL_ROAD || c==CELL_PIT_OPEN || c==CELL_WATER) { target = f; break; }
                        } catch (Exception ignored) {}
                    }
                    if (target >= 0) {
                        // Check walkable
                        try {
                            int ix = (int)sx, iy = (int)sy;
                            int belowCell = floorGrids[target][ix][iy];
                            if (belowCell == CELL_WALL || belowCell == CELL_DOOR) {
                                double[] safe = findSafeSpawn(floorGrids[target], sx, sy);
                                sx = safe[0]; sy = safe[1];
                            }
                        } catch (Exception ignored) {}
                        playFanfare("fall");
                        if (mapType == MapType.SKY) status.setText(" // SKYDIVING FROM F" + (currentFloor+1) + " TO F" + (target+1) + " !!");
                        else status.setText(" // FELL THROUGH OPEN PIT TO F" + (target+1));
                        currentFloor = target;
                        worldMap = floorGrids[currentFloor];
                        textureMap = floorTexLayers[currentFloor];
                        floorTextureMap = floorFloorTexLayers[currentFloor];
                        wallHeightMap = floorWallHLayers[currentFloor];
                        groundHeightMap = floorGroundHLayers[currentFloor];
                        posX = sx; posY = sy;
                        floorFade = 0; pendingFloor = -1;
                        return;
                    }
                    // No floor below found in SKY mode -> if sky, we are over void, death after long fall
                    if (mapType == MapType.SKY) {
                        playFanfare("fall");
                        status.setText(" // FELL OFF SKY ISLAND - no island below!");
                    }
                }
            }
            fallInPit();
        }

        // Sky islands - updraft / launch pad (cell 9) to go up
        boolean isUpdraftAt(double x, double y) {
            int ix = (int)x, iy = (int)y;
            if (ix<0||iy<0||ix>=MAP_SIZE||iy>=MAP_SIZE) return false;
            int c = worldMap[ix][iy];
            return c == 9; // updraft
        }

        void handleUpdraft() {
            if (mapType != MapType.SKY && mapType != MapType.INDOOR) return;
            if (currentFloor >= floorCount -1) return;
            // Find next floor above with open cell
            int target = -1;
            for (int f = currentFloor+1; f<floorCount; f++) {
                try {
                    int ix = (int)posX, iy = (int)posY;
                    int c = floorGrids[f][ix][iy];
                    if (c==CELL_OPEN || c==CELL_ROAD || c==CELL_PIT_OPEN) { target = f; break; }
                } catch (Exception ignored) {}
            }
            if (target >=0) {
                playFanfare("stairs_up");
                status.setText(" // UPDRAFT TO F" + (target+1) + " !!");
                changeFloor(target);
            }
        }



        static boolean isHookTarget(Thing t) {
            if (t == null || t.label == null) return false;
            String u = t.label.toUpperCase();
            if (u.equals("HOOKTARGET") || u.equals("TARGET")) return true;
            String s = t.sourceName == null ? "" : t.sourceName.toLowerCase();
            return s.contains("hook") || s.contains("target") || s.contains("ring");
        }

        void fallInPit() {
            playFanfare("fall");
            lives--;
            status.setText(" // FELL INTO A PIT — lives " + lives);
            hp = 8;
            double[] sp = getSpawnForFloor(currentFloor);
            posX = sp[0]; posY = sp[1];
            dirX = sp[2]; dirY = sp[3];
            planeX = FOV * dirY; planeY = -FOV * dirX;
            if (lives < 0) {
                isPaused = true;
                status.setText(" // GAME OVER");
            }
        }

        /** Enemy AREA bubble wander or PATH waypoint loop (+ shy flee). */
        void tickEnemyPatrols() {
            for (Thing t : things) {
                if (t.hp <= 0) continue;
                if (!t.onCurrentFloor(currentFloor)) continue;
                String lab = t.label == null ? "" : t.label.toUpperCase();
                if (!(lab.equals("ENEMY") || lab.equals("BOSS"))) continue;
                if (t.stationary) continue;
                if (t.patrolMode == null || t.patrolMode.equals("NONE")) continue;
                if (t.homeX == 0 && t.homeY == 0) { t.homeX = t.x; t.homeY = t.y; }

                if (t.patrolWait > 0) { t.patrolWait--; continue; }

                // Shy ghost: flee player when close
                if (t.shy) {
                    double pdx = t.x - posX, pdy = t.y - posY;
                    double pd2 = pdx * pdx + pdy * pdy;
                    if (pd2 < 4.0 && pd2 > 0.01) {
                        double len = Math.sqrt(pd2);
                        t.patrolTX = t.x + pdx / len * 2.0;
                        t.patrolTY = t.y + pdy / len * 2.0;
                    }
                }

                if (t.patrolMode.equals("PATH") && !t.waypoints.isEmpty()) {
                    double[] wp = t.waypoints.get(t.wpIndex % t.waypoints.size());
                    t.patrolTX = wp[0]; t.patrolTY = wp[1];
                } else if (!t.shy || (t.patrolTX == 0 && t.patrolTY == 0)) {
                    double ddx = t.patrolTX - t.x, ddy = t.patrolTY - t.y;
                    if ((t.patrolTX == 0 && t.patrolTY == 0) || ddx * ddx + ddy * ddy < 0.08) {
                        double ang = Math.random() * Math.PI * 2;
                        double rad = Math.random() * t.areaRadius;
                        t.patrolTX = t.homeX + Math.cos(ang) * rad;
                        t.patrolTY = t.homeY + Math.sin(ang) * rad;
                        t.patrolWait = 8 + (int) (Math.random() * 20);
                        continue;
                    }
                }

                double dx = t.patrolTX - t.x, dy = t.patrolTY - t.y;
                double len = Math.sqrt(dx * dx + dy * dy);
                if (len < 0.05) {
                    if (t.patrolMode.equals("PATH") && !t.waypoints.isEmpty()) {
                        t.wpIndex = (t.wpIndex + 1) % t.waypoints.size();
                        t.patrolWait = 10;
                    }
                    continue;
                }
                double sp = t.moveSpeed;
                double nx = t.x + dx / len * sp;
                double ny = t.y + dy / len * sp;
                // don't walk into walls / doors
                int ix = (int) nx, iy = (int) ny;
                if (ix >= 0 && iy >= 0 && ix < MAP_SIZE && iy < MAP_SIZE) {
                    int c = worldMap[ix][iy];
                    if (c == 1 || c == 2) {
                        // bounce: new target
                        t.patrolTX = 0; t.patrolTY = 0;
                        continue;
                    }
                    // stay inside area bubble for AREA mode
                    if (t.patrolMode.equals("AREA")) {
                        double hx = nx - t.homeX, hy = ny - t.homeY;
                        if (hx * hx + hy * hy > t.areaRadius * t.areaRadius) {
                            t.patrolTX = 0; t.patrolTY = 0;
                            continue;
                        }
                    }
                    t.x = nx; t.y = ny;
                }
            }
        }

        /** Sword / melee smash in front — breaks jars, boxes, small boulders. */
        void tryMeleeBreak() {
            double fx = posX + dirX * 0.85, fy = posY + dirY * 0.85;
            for (int i = things.size() - 1; i >= 0; i--) {
                Thing t = things.get(i);
                if (t.hp <= 0 || !t.isBreakable()) continue;
                double dx = t.x - fx, dy = t.y - fy;
                if (dx * dx + dy * dy < 0.55 * 0.55) {
                    breakThing(t);
                    things.remove(i);
                    weaponFlash = 4;
                    return;
                }
            }
            status.setText(" // NOTHING TO BREAK");
        }

        /**
         * Interact in front of player:
         * 1) Key locks  2) Chests  3) Toss props  4) Map doors
         */
        /** True if label is a wall-screen / TV browser mod prop. */
        boolean isScreenProp(Thing t) {
            if (t == null || t.label == null) return false;
            String u = t.label.toUpperCase();
            if (u.equals("TV") || u.equals("BROWSER") || u.equals("SCREEN") || u.equals("MONITOR"))
                return true;
            String s = t.sourceName == null ? "" : t.sourceName.toLowerCase();
            return s.contains("tv") || s.contains("monitor") || s.contains("screen") || s.contains("browser");
        }

        /**
         * Minecraft-style: approach TV/screen, press E → open SFPROTO browser mod.
         * @return true if a screen was used (so E does not also rotate)
         */
        boolean tryUseScreen() {
            double fx = posX + dirX * 1.0, fy = posY + dirY * 1.0;
            Thing best = null;
            double bestD = 1.4;
            for (Thing t : things) {
                if (t.hp <= 0 || !isScreenProp(t)) continue;
                double dx = t.x - posX, dy = t.y - posY;
                double d = Math.sqrt(dx * dx + dy * dy);
                // must be near and roughly in front
                double fdx = t.x - fx, fdy = t.y - fy;
                if (d < bestD && Math.sqrt(fdx * fdx + fdy * fdy) < 1.6) {
                    bestD = d;
                    best = t;
                }
            }
            if (best == null) return false;
            status.setText(" // SCREEN ACTIVATED — loading browser mod");
            openBrowserMod(best);
            return true;
        }

        void openBrowserMod(Thing screen) {
            // Prefer in-process sfproto if on classpath (mods/ or same folder)
            try {
                Class<?> cls = Class.forName("sfproto");
                Object inst = cls.getDeclaredConstructor().newInstance();
                if (inst instanceof java.awt.Window) {
                    java.awt.Window w = (java.awt.Window) inst;
                    w.setVisible(true);
                    if (w instanceof javax.swing.JFrame) {
                        ((javax.swing.JFrame) w).setTitle("SFPROTO // Wall Screen — "
                                + (screen.sourceName != null ? screen.sourceName : screen.label));
                    }
                    status.setText(" // BROWSER MOD ONLINE (in-process)");
                    return;
                }
            } catch (ClassNotFoundException cnf) {
                // fall through to external jar/process
            } catch (Throwable t) {
                status.setText(" // BROWSER MOD ERROR: " + t.getClass().getSimpleName());
            }

            // External: mods/sfproto.jar or sfproto.java compiled class / RUN
            File[] candidates = {
                    new File("mods/sfproto.jar"),
                    new File("assets/mods/sfproto.jar"),
                    new File("sfproto.jar"),
                    new File("mods/SFProto.jar")
            };
            for (File jar : candidates) {
                if (!jar.isFile()) continue;
                try {
                    String javaBin = System.getProperty("java.home") + File.separator + "bin" + File.separator + "java";
                    new ProcessBuilder(javaBin, "-Xmx512m", "-jar", jar.getAbsolutePath())
                            .inheritIO()
                            .start();
                    status.setText(" // BROWSER MOD LAUNCHED: " + jar.getName());
                    return;
                } catch (Exception ex) {
                    status.setText(" // FAILED TO LAUNCH " + jar.getName());
                }
            }
            // Last resort: compile+run sfproto class file beside engine
            try {
                String javaBin = System.getProperty("java.home") + File.separator + "bin" + File.separator + "java";
                new ProcessBuilder(javaBin, "-cp", ".", "sfproto")
                        .directory(new File("."))
                        .inheritIO()
                        .start();
                status.setText(" // BROWSER MOD: java -cp . sfproto");
            } catch (Exception ex) {
                status.setText(" // NO BROWSER MOD — place sfproto.class or mods/sfproto.jar");
                JOptionPane.showMessageDialog(LOCWTTP.this,
                        "Browser mod not found.\n\nPut sfproto.class on the classpath\n"
                                + "or mods/sfproto.jar next to the engine.\n\n"
                                + "Place a TV / BROWSER entity in the map, walk up, press E.",
                        "CHROMA Mod Loader", JOptionPane.INFORMATION_MESSAGE);
            }
        }

        void tryInteract() {
            // Prefer nearby things in facing direction
            double fx = posX + dirX * 0.9, fy = posY + dirY * 0.9;
            Thing best = null;
            double bestD = 1.2;
            for (Thing t : things) {
                if (t.hp <= 0) continue;
                if (!t.onCurrentFloor(currentFloor)) continue;
                double dx = t.x - fx, dy = t.y - fy;
                double d = Math.sqrt(dx * dx + dy * dy);
                if (d < bestD) { bestD = d; best = t; }
            }
            if (best != null && isScreenProp(best)) {
                openBrowserMod(best);
                return;
            }
            if (best != null) {
                String lock = best.lockKind();
                if (lock != null) {
                    if (lock.equals("whistle")) {
                        status.setText(" // SEALED — play the WHISTLE (5 + use)");
                        playFanfare("error");
                        return;
                    }
                    String need = lock.equals("final") ? "finalkey" : lock.equals("boss") ? "bkey" : "skey";
                    String needName = lock.equals("final") ? "FINAL KEY" : lock.equals("boss") ? "BOSS KEY" : "SILVER KEY";
                    if (!hasItem(need)) {
                        status.setText(" // LOCKED — need " + needName);
                        playFanfare("error");
                        return;
                    }
                    consumeItem(need, 1);
                    things.remove(best);
                    playFanfare("door_unlock");
                    score += 25;
                    status.setText(" // " + lock.toUpperCase() + " LOCK OPENED");
                    return;
                }
                if (best.isChest() && !best.chestOpen) {
                    best.chestOpen = true;
                    boolean big = best.label.equalsIgnoreCase("CHESTBG");
                    String openName = big ? "BIGCHESTOPEN.png" : "SMALLCHESTOPEN.png";
                    BufferedImage openSpr = loadFromSub("storage", false, openName, openName.toLowerCase());
                    if (openSpr != null) best.sprite = openSpr;
                    best.solid = true;
                    score += big ? 50 : 30;
                    if (big) {
                        // Big chest: weapon or major item
                        if (!hasBow) {
                            grantBow();
                        } else if (!hasItem("sword1")) {
                            BufferedImage sw = loadFromSub("weapons", false, "sword1.png");
                            addToInventory("sword1", "Sword", sw, 1);
                            equippedWeapon = "sword1"; swordHud = sw; bowEquipped = false;
                            status.setText(" // BIG CHEST → SWORD");
                        } else {
                            BufferedImage bm = loadFromSub("weapons", false, "bomb.png");
                            addToInventory("bomb", "Bomb", bm != null ? bm : bombHud, 3);
                            status.setText(" // BIG CHEST → BOMBS x3");
                        }
                    } else {
                        // Small chest: key
                        BufferedImage key = loadFromSub("items", false, "skey.png", "bkey.png");
                        addToInventory("skey", "Silver Key", key, 1);
                        status.setText(" // SMALL CHEST → SILVER KEY");
                    }
                    playFanfare("smchestepen");
                    return;
                }
                // Toss / push smboulder or jar
                if (best.isTossable()) {
                    best.tossFrames = 18;
                    best.tossVX = dirX * 0.22;
                    best.tossVY = dirY * 0.22;
                    best.solid = false; // flies through briefly
                    status.setText(" // TOSSED " + best.label);
                    return;
                }
            }
            // Grid door cell
            int cx = (int) (posX + dirX * 1.1);
            int cy = (int) (posY + dirY * 1.1);
            if (cx >= 0 && cy >= 0 && cx < MAP_SIZE && cy < MAP_SIZE && worldMap[cx][cy] == 2) {
                worldMap[cx][cy] = 0;
                playFanfare("smchestepen");
                status.setText(" // DOOR OPENED");
            }
        }

        void rotate(double a) {
            double odx = dirX;
            dirX = dirX * Math.cos(a) - dirY * Math.sin(a);
            dirY = odx * Math.sin(a) + dirY * Math.cos(a);
            double opx = planeX;
            planeX = planeX * Math.cos(a) - planeY * Math.sin(a);
            planeY = opx * Math.sin(a) + planeY * Math.cos(a);
        }

        void renderSkybox() {
            // Upper half = sky / roof. Soft ceiling shade under sky for multi-floor feel.
            if (skyboxImage == null) {
                int skyRGB = currentPalette.bg.getRGB();
                for (int y = 0; y < RENDER_H / 2; y++) {
                    for (int x = 0; x < RENDER_W; x++) pixels[y * RENDER_W + x] = skyRGB;
                }
                return;
            }

            double angle = Math.atan2(dirY, dirX);
            if (angle < 0) angle += 2 * Math.PI;
            int skyW = skyboxImage.getWidth();
            int skyH = skyboxImage.getHeight();
            int startX = (int) ((angle / (2 * Math.PI)) * skyW);

            for (int x = 0; x < RENDER_W; x++) {
                int texX = (startX + (x * skyW / RENDER_W)) % skyW;
                if (texX < 0) texX += skyW;
                for (int y = 0; y < RENDER_H / 2; y++) {
                    int texY = (y * skyH) / (RENDER_H / 2);
                    if (texY >= skyH) texY = skyH - 1;
                    pixels[y * RENDER_W + x] = skyboxImage.getRGB(texX, texY);
                }
            }
            // Soft roof tint near horizon when multi-floor (not top exterior)
            if (floorCount > 1 && currentFloor < floorCount - 1) {
                int roof = new Color(20, 20, 28).getRGB();
                for (int y = RENDER_H / 2 - 18; y < RENDER_H / 2; y++) {
                    if (y < 0) continue;
                    double t = (y - (RENDER_H / 2 - 18)) / 18.0;
                    for (int x = 0; x < RENDER_W; x++) {
                        int i = y * RENDER_W + x;
                        int src = pixels[i];
                        int r = (int) (((src >> 16) & 0xff) * (1 - t) + ((roof >> 16) & 0xff) * t);
                        int g = (int) (((src >> 8) & 0xff) * (1 - t) + ((roof >> 8) & 0xff) * t);
                        int b = (int) ((src & 0xff) * (1 - t) + (roof & 0xff) * t);
                        pixels[i] = (r << 16) | (g << 8) | b;
                    }
                }
            }
        }

        void render() {
            String camMode = "RAYCAST";
            try {
                ChromaOptions o = ChromaOptions.load();
                if (o != null && o.cameraMode != null) camMode = o.cameraMode.toUpperCase();
            } catch (Throwable ignored) {}
            if ("SIDE".equals(camMode)) { renderSideScroller(); return; }
            if ("TOP".equals(camMode)) { renderTopDown(); return; }
            renderRaycast();
        }

        double camX, camY;

        boolean cellSolid(int ix, int iy) {
            if (ix < 0 || iy < 0 || ix >= MAP_SIZE || iy >= MAP_SIZE) return true;
            int c = worldMap[ix][iy];
            return c == 1 || c == 2;
        }

        /** Place cam at player, or stepped back for third person without entering walls. */
        void resolveCamera() {
            camX = posX;
            camY = posY;
            if (options == null || !options.thirdPerson) return;
            double bestX = posX, bestY = posY;
            for (double d = 0.1; d <= 1.4; d += 0.1) {
                double nx = posX - dirX * d;
                double ny = posY - dirY * d;
                if (cellSolid((int) nx, (int) ny) || !walk(nx, ny)) break;
                bestX = nx;
                bestY = ny;
            }
            camX = bestX;
            camY = bestY;
        }

        void renderRaycast() {
            renderSkybox();
            resolveCamera();
            boolean tp = options != null && options.thirdPerson
                    && (Math.abs(camX - posX) + Math.abs(camY - posY) > 0.08);

            for (int x = 0; x < RENDER_W; x++) {
                double cam = 2.0 * x / RENDER_W - 1;
                double rdx = dirX + planeX * cam;
                double rdy = dirY + planeY * cam;
                int mapX = (int) camX, mapY = (int) camY;
                double ddx = (rdx == 0) ? 1e30 : Math.abs(1 / rdx);
                double ddy = (rdy == 0) ? 1e30 : Math.abs(1 / rdy);
                int stepX = (rdx < 0) ? -1 : 1, stepY = (rdy < 0) ? -1 : 1;
                double sdx = (rdx < 0) ? (camX - mapX) * ddx : (mapX + 1.0 - camX) * ddx;
                double sdy = (rdy < 0) ? (camY - mapY) * ddy : (mapY + 1.0 - camY) * ddy;

                int hit = 0, hx = mapX, hy = mapY, side = 0;
                int steps = maxRaySteps > 0 ? maxRaySteps : 48;
                for (int i = 0; i < steps; i++) {
                    if (sdx < sdy) { sdx += ddx; mapX += stepX; side = 0; }
                    else { sdy += ddy; mapY += stepY; side = 1; }
                    if (mapX < 0 || mapY < 0 || mapX >= MAP_SIZE || mapY >= MAP_SIZE) {
                        hit = 1; hx = mapX; hy = mapY; break;
                    }
                    int cell = worldMap[mapX][mapY];
                    // 1 wall, 2 door, 6 fake wall — all stop rays (look solid)
                    if (cell == 1 || cell == 2 || cell == 6) { hit = cell == 6 ? 1 : cell; hx = mapX; hy = mapY; break; }
                }

                double dist;
                if (side == 0)
                    dist = (hx - camX + (1 - stepX) / 2.0) / (rdx == 0 ? 1e-9 : rdx);
                else
                    dist = (hy - camY + (1 - stepY) / 2.0) / (rdy == 0 ? 1e-9 : rdy);
                dist = Math.abs(dist);
                if (dist < 0.05) dist = 0.05;
                double maxDist = viewDistance > 0 ? viewDistance : 24;
                if (dist > maxDist || Double.isNaN(dist) || Double.isInfinite(dist)) dist = maxDist;
                zbuffer[x] = dist;

                int safeHx = Math.max(0, Math.min(MAP_SIZE - 1, hx));
                int safeHy = Math.max(0, Math.min(MAP_SIZE - 1, hy));
                // wall height: 1=0.5x, 2=1x, 3=1.5x, 4=2x
                double wh = 1.0;
                try { wh = Math.max(0.25, wallHeightMap[safeHx][safeHy] / 2.0); } catch (Exception ignored) {}
                double gh = 0;
                try { gh = (groundHeightMap[safeHx][safeHy] - 2) * 0.12; } catch (Exception ignored) {}
                int lh = (int) (RENDER_H / dist * wh);
                int mid = RENDER_H / 2 + (int) (gh * RENDER_H) - (int) (eyeOffset * RENDER_H * 0.55);
                int ds = Math.max(0, -lh / 2 + mid);
                int de = Math.min(RENDER_H - 1, lh / 2 + mid);
                // Soft fog beyond view distance * 0.7
                if (dist > maxDist * 0.7) {
                    // skip detailed texturing far away — solid shade only (perf)
                }

                int safeX = Math.max(0, Math.min(MAP_SIZE - 1, hx));
                int safeY = Math.max(0, Math.min(MAP_SIZE - 1, hy));
                int texSlot = textureMap[safeX][safeY];
                BufferedImage wallTex = loadedWallTextures.get(texSlot);

                double wallX;
                if (side == 0) wallX = camY + dist * rdy;
                else wallX = camX + dist * rdx;
                wallX -= Math.floor(wallX);

                double shade = (side == 1 ? 0.7 : 1.0) * Math.max(0.3, 1.3 - dist * 0.06);

                for (int y = ds; y <= de; y++) {
                    if (wallTex != null) {
                        int texX = (int) (wallX * wallTex.getWidth());
                        if ((side == 0 && rdx > 0) || (side == 1 && rdy < 0)) texX = wallTex.getWidth() - texX - 1;
                        int texY = ((y - (-lh / 2 + RENDER_H / 2)) * wallTex.getHeight()) / lh;
                        texX = Math.max(0, Math.min(wallTex.getWidth() - 1, texX));
                        texY = Math.max(0, Math.min(wallTex.getHeight() - 1, texY));
                        Color c = new Color(wallTex.getRGB(texX, texY));
                        pixels[y * RENDER_W + x] = shadeColor(c, shade).getRGB();
                    } else {
                        Color base = (wallColors[safeX][safeY] != null)
                                ? wallColors[safeX][safeY] : ((hit % 2 == 0) ? new Color(0, 80, 35) : new Color(0, 120, 50));
                        pixels[y * RENDER_W + x] = shadeColor(base, shade).getRGB();
                    }
                }

                // Floor casting (from wall bottom to screen bottom)
                double rayDirX0 = dirX - planeX;
                double rayDirY0 = dirY - planeY;
                double rayDirX1 = dirX + planeX;
                double rayDirY1 = dirY + planeY;
                for (int y = de + 1; y < RENDER_H; y++) {
                    double rowDist = (0.5 * RENDER_H) / (y - RENDER_H / 2.0);
                    if (rowDist < 0) continue;
                    double floorStepX = rowDist * (rayDirX1 - rayDirX0) / RENDER_W;
                    double floorStepY = rowDist * (rayDirY1 - rayDirY0) / RENDER_W;
                    double floorX = camX + rowDist * rayDirX0 + floorStepX * x;
                    double floorY = camY + rowDist * rayDirY0 + floorStepY * x;

                    int cellX = (int) floorX;
                    int cellY = (int) floorY;
                    if (cellX < 0 || cellY < 0 || cellX >= MAP_SIZE || cellY >= MAP_SIZE) {
                        pixels[y * RENDER_W + x] = shadeColor(currentPalette.bg, 0.25).getRGB();
                        continue;
                    }
                    int cellType = 0;
                    try { cellType = worldMap[cellX][cellY]; } catch (Exception ignored) {}
                    // Open pit: show floor below - works for INDOOR and SKY (sky island hole sees ground far below)
                    if (cellType == CELL_PIT_OPEN && (mapType == MapType.INDOOR || mapType == MapType.SKY) && currentFloor > 0) {
                        try {
                            int below = currentFloor - 1;
                            int belowTex = floorFloorTexLayers[below][cellX][cellY];
                            BufferedImage belowImg = loadedFloorTextures.get(belowTex);
                            double fShade = Math.max(0.15, 1.0 - rowDist * 0.07);
                            if (belowImg != null) {
                                int tw = belowImg.getWidth(), th = belowImg.getHeight();
                                int tx = (int) ((floorX - cellX) * tw) % tw;
                                int ty = (int) ((floorY - cellY) * th) % th;
                                if (tx < 0) tx += tw; if (ty < 0) ty += th;
                                Color c = new Color(belowImg.getRGB(tx, ty));
                                // Darken to show depth
                                pixels[y * RENDER_W + x] = shadeColor(c, fShade * 0.6).getRGB();
                            } else {
                                // No texture below - dark void but with depth cue
                                pixels[y * RENDER_W + x] = shadeColor(new Color(20,20,30), 0.4).getRGB();
                            }
                            // Draw pit rim shadow
                            continue;
                        } catch (Exception ignored) {}
                    }
                    if (cellType == 9) {
                        // Updraft / sky launch - bright swirl
                        pixels[y * RENDER_W + x] = shadeColor(new Color(120,200,255), Math.max(0.4, 1.0 - rowDist*0.05)).getRGB();
                        continue;
                    }
                    // Void pits - black void
                    if (cellType == CELL_PIT_VOID || cellType == CELL_FAKE_FLOOR) {
                        // Check if it's fake floor - should look like normal floor until close? For now render dark
                        if (cellType == CELL_FAKE_FLOOR) {
                            // Render as normal floor but will kill on touch - trap
                        } else {
                            pixels[y * RENDER_W + x] = new Color(8,8,10).getRGB();
                            continue;
                        }
                    }
                    int fSlot = floorTextureMap[cellX][cellY];
                    BufferedImage floorTex = loadedFloorTextures.get(fSlot);
                    double fShade = Math.max(0.25, 1.2 - rowDist * 0.05);
                    if (floorTex != null) {
                        int tw = floorTex.getWidth(), th = floorTex.getHeight();
                        int tx = (int) ((floorX - cellX) * tw) % tw;
                        int ty = (int) ((floorY - cellY) * th) % th;
                        if (tx < 0) tx += tw;
                        if (ty < 0) ty += th;
                        Color c = new Color(floorTex.getRGB(tx, ty));
                        pixels[y * RENDER_W + x] = shadeColor(c, fShade).getRGB();
                    } else {
                        pixels[y * RENDER_W + x] = shadeColor(currentPalette.bg, 0.3 * fShade).getRGB();
                    }
                }
            }

            // --- Overhead ceiling check - render soft ceiling shadow if floor above ---
            boolean ceilingAbove = hasCeilingAbove(camX, camY);
            if (ceilingAbove) {
                // darken top of screen to hint overhead floor
                for (int y=0; y<RENDER_H/2; y++) {
                    double t = 1.0 - (y / (RENDER_H/2.0));
                    int dark = (int)(t*60);
                    for (int x=0; x<RENDER_W; x++) {
                        int idx = y*RENDER_W+x;
                        int rgb = pixels[idx];
                        int r = Math.max(0, ((rgb>>16)&0xff)-dark);
                        int g = Math.max(0, ((rgb>>8)&0xff)-dark);
                        int b = Math.max(0, (rgb&0xff)-dark);
                        pixels[idx] = (r<<16)|(g<<8)|b;
                    }
                }
            }

            // Layer + depth sort (far → near within each layer, low layer first)
            record DrawItem(double x, double y, BufferedImage img, Color tint,
                            SpriteLayer layer, double dist, boolean opaque, double scaleW, double scaleH) {}
            List<DrawItem> drawList = new ArrayList<>();
            for (Thing t : things) {
                if (!t.onCurrentFloor(currentFloor)) continue;
                BufferedImage spr = t.sprite;
                // TV / screen uses live framebuffer when available
                if (isScreenProp(t) && tvFrameBuffer != null) spr = tvFrameBuffer;
                double d = (t.x - posX) * (t.x - posX) + (t.y - posY) * (t.y - posY);
                drawList.add(new DrawItem(t.x, t.y, spr, t.tint, t.layer, d, t.opaque, t.scaleW, t.scaleH));
            }
            for (Projectile p : projectiles) {
                double d = (p.x - posX) * (p.x - posX) + (p.y - posY) * (p.y - posY);
                BufferedImage ps = p.sprite != null ? p.sprite : arrowSprite;
                double sc = p.isHook ? 0.45 : p.isBoomerang ? 0.6 : "fireball".equals(p.kind) ? 0.55 : 0.5;
                drawList.add(new DrawItem(p.x, p.y, ps, p.color, p.layer, d, ps != null, sc, sc));
            }
            for (Particle pt : particles) {
                double d = (pt.x - posX) * (pt.x - posX) + (pt.y - posY) * (pt.y - posY);
                double fade = Math.max(0.15, pt.life / (double) pt.maxLife);
                Color faded = new Color(pt.color.getRed(), pt.color.getGreen(), pt.color.getBlue(),
                        (int) (255 * fade));
                BufferedImage ps = pt.sprite;
                drawList.add(new DrawItem(pt.x, pt.y, ps, faded, SpriteLayer.EFFECT, d, ps != null, pt.size, pt.size));
            }
            // Textboards join the same pipeline (billboard layer, solid-ish)
            for (TextBoard tb : textBoards) {
                double d = (tb.x - posX) * (tb.x - posX) + (tb.y - posY) * (tb.y - posY);
                drawList.add(new DrawItem(tb.x, tb.y, tb.texture, currentPalette.fg,
                        tb.layer, d, true, tb.width, tb.height));
            }
            drawList.sort((a, b) -> {
                int lc = Integer.compare(a.layer.order, b.layer.order);
                return lc != 0 ? lc : Double.compare(b.dist, a.dist); // far first
            });
            // Render blob shadows for floating/billboard objects first
            for (DrawItem d : drawList) {
                if (d.layer == SpriteLayer.FLOOR_DECAL) continue;
                if (d.dist>viewDistance) continue;
                // floating objects (pickups, enemies, etc) get shadow
                if ((d.layer == SpriteLayer.BILLBOARD || d.layer == SpriteLayer.ITEM || d.layer == SpriteLayer.ACTOR)) {
                    renderBlobShadow(d.x, d.y, d.dist, Math.min(d.scaleW, d.scaleH));
                }
            }
            for (DrawItem d : drawList)
                renderSprite(d.x, d.y, d.img, d.tint, d.opaque, d.scaleW, d.scaleH);

            // Third-person: draw player avatar at true position
            if (tp) {
                BufferedImage body = null;
                try { body = loadFromSub("player", false, "player.png", "hero.png", "link.png"); } catch (Throwable ignored) {}
                renderSprite(posX, posY, body, new Color(80, 255, 120), true, 0.7, 1.0);
            }

            if (fogStrength > 0.01) applyFogAndLights();
        }

        void applyFogAndLights() {
            int fogRGB = currentPalette.bg.getRGB();
            int fr = (fogRGB >> 16) & 0xff, fg = (fogRGB >> 8) & 0xff, fb = fogRGB & 0xff;
            // Collect light positions
            java.util.List<double[]> lights = new java.util.ArrayList<>();
            for (Thing t : things) {
                if (t.label == null) continue;
                String u = t.label.toUpperCase();
                String n = t.sourceName == null ? "" : t.sourceName.toLowerCase();
                if (u.equals("TORCH") || u.equals("LIGHT") || n.contains("torch")) {
                    double r = u.equals("LIGHT") ? 5.0 : 3.0;
                    lights.add(new double[]{t.x, t.y, r});
                }
            }
            for (int x = 0; x < RENDER_W; x++) {
                double dist = zbuffer[x];
                double fogT = Math.min(1.0, (dist / Math.max(4, options.fogDistance)) * fogStrength);
                if (fogT < 0.02 && lights.isEmpty()) continue;
                for (int y = 0; y < RENDER_H; y++) {
                    int idx = y * RENDER_W + x;
                    int rgb = pixels[idx];
                    int r = (rgb >> 16) & 0xff, g = (rgb >> 8) & 0xff, b = rgb & 0xff;
                    if (fogT > 0.02) {
                        r = (int) (r * (1 - fogT) + fr * fogT);
                        g = (int) (g * (1 - fogT) + fg * fogT);
                        b = (int) (b * (1 - fogT) + fb * fogT);
                    }
                    // approximate light: brighten lower FOV near lights (screen-space cheap)
                    if (!lights.isEmpty() && y > RENDER_H / 3) {
                        double boost = 0;
                        // project is costly — use player-relative distance only
                        for (double[] L : lights) {
                            double ldx = L[0] - posX, ldy = L[1] - posY;
                            double ld = Math.sqrt(ldx * ldx + ldy * ldy);
                            if (ld < L[2]) boost = Math.max(boost, (1.0 - ld / L[2]) * 0.35);
                        }
                        if (boost > 0) {
                            r = Math.min(255, (int) (r + (255 - r) * boost));
                            g = Math.min(255, (int) (g + (255 - g) * boost * 0.9));
                            b = Math.min(255, (int) (b + (255 - b) * boost * 0.6));
                        }
                    }
                    pixels[idx] = (r << 16) | (g << 8) | b;
                }
            }
        }

        /**
         * Billboard sprite. Opaque sprites write z-buffer (solid props).
         * Alpha/FX sprites only test z (see-through fire, particles).
         */
        private void renderBlobShadow(double sx, double sy, double dist, double scale) {
            // Project world pos to screen floor position for blob shadow
            double dx = sx - camX, dy = sy - camY;
            double invDet = 1.0 / (planeX * dirY - dirX * planeY);
            double transX = invDet * (dirY * dx - dirX * dy);
            double transY = invDet * (-planeY * dx + planeX * dy);
            if (transY <= 0.2) return;
            int screenX = (int)((RENDER_W/2.0)*(1+transX/transY));
            int shadowSize = Math.max(4, (int)(RENDER_W / transY * 0.25 * scale));
            int shadowY = RENDER_H/2 + (int)(RENDER_H / transY * 0.1);
            // clamp
            for (int yy=-shadowSize/2; yy<shadowSize/2; yy++) {
                int py = shadowY+yy;
                if (py<RENDER_H/2 || py>=RENDER_H) continue;
                for (int xx=-shadowSize/2; xx<shadowSize/2; xx++) {
                    int px = screenX+xx;
                    if (px<0||px>=RENDER_W) continue;
                    double d = (xx*xx+yy*yy) / (double)(shadowSize*shadowSize/4.0);
                    if (d>1) continue;
                    int idx = py*RENDER_W+px;
                    if (transY >= zbuffer[px]) continue; // occluded
                    int rgb = pixels[idx];
                    int r = (int)(((rgb>>16)&0xff)*0.35);
                    int g = (int)(((rgb>>8)&0xff)*0.35);
                    int b = (int)((rgb&0xff)*0.35);
                    pixels[idx] = (r<<16)|(g<<8)|b;
                }
            }
        }

        private void renderSprite(double sx, double sy, BufferedImage img, Color tint,
                                  boolean writeZ, double scaleW, double scaleH) {
            // Billboard relative to camera (supports third person)
            double ox = (camX != 0 || camY != 0) ? camX : posX;
            double oy = (camX != 0 || camY != 0) ? camY : posY;
            // if resolveCamera not called this frame, fall back
            if (Math.abs(camX) < 1e-12 && Math.abs(camY) < 1e-12) { ox = posX; oy = posY; }
            double dx = sx - ox, dy = sy - oy;
            double invDet = 1.0 / (planeX * dirY - dirX * planeY);
            double transformX = invDet * (dirY * dx - dirX * dy);
            double transformY = invDet * (-planeY * dx + planeX * dy);
            if (transformY <= 0.15) return;

            int spriteScreenX = (int) ((RENDER_W / 2.0) * (1 + transformX / transformY));
            int spriteH = Math.abs((int) (RENDER_H / transformY * scaleH));
            int spriteW = Math.abs((int) (RENDER_H / transformY * scaleW));
            if (spriteW < 1) spriteW = 1;
            if (spriteH < 1) spriteH = 1;
            int drawStartY = Math.max(0, -spriteH / 2 + RENDER_H / 2);
            int drawEndY = Math.min(RENDER_H - 1, spriteH / 2 + RENDER_H / 2);
            int drawStartX = Math.max(0, -spriteW / 2 + spriteScreenX);
            int drawEndX = Math.min(RENDER_W - 1, spriteW / 2 + spriteScreenX);

            int placeholderRGB = tint != null ? tint.getRGB() : 0x00FF66;
            for (int stripe = drawStartX; stripe <= drawEndX; stripe++) {
                if (stripe < 0 || stripe >= RENDER_W || transformY >= zbuffer[stripe]) continue;
                boolean wrote = false;
                for (int y = drawStartY; y <= drawEndY; y++) {
                    if (img != null) {
                        int texX = (stripe - (-spriteW / 2 + spriteScreenX)) * img.getWidth() / spriteW;
                        int texY = (y - (-spriteH / 2 + RENDER_H / 2)) * img.getHeight() / spriteH;
                        if (texX < 0 || texY < 0 || texX >= img.getWidth() || texY >= img.getHeight()) continue;
                        int rgb = img.getRGB(texX, texY);
                        int a = (rgb >> 24) & 0xff;
                        if (a > 20) {
                            // simple alpha blend for soft sprites
                            if (!writeZ && a < 250) {
                                int dst = pixels[y * RENDER_W + stripe];
                                int sr = (rgb >> 16) & 0xff, sg = (rgb >> 8) & 0xff, sb = rgb & 0xff;
                                int dr = (dst >> 16) & 0xff, dg = (dst >> 8) & 0xff, db = dst & 0xff;
                                int na = a;
                                int rr = (sr * na + dr * (255 - na)) / 255;
                                int gg = (sg * na + dg * (255 - na)) / 255;
                                int bb = (sb * na + db * (255 - na)) / 255;
                                pixels[y * RENDER_W + stripe] = (rr << 16) | (gg << 8) | bb;
                            } else {
                                pixels[y * RENDER_W + stripe] = rgb;
                            }
                            wrote = true;
                        }
                    } else {
                        pixels[y * RENDER_W + stripe] = placeholderRGB;
                        wrote = true;
                    }
                }
                if (writeZ && wrote) zbuffer[stripe] = transformY;
            }
        }

        Color shadeColor(Color c, double f) {
            return new Color((int) Math.max(0, Math.min(255, c.getRed() * f)),
                    (int) Math.max(0, Math.min(255, c.getGreen() * f)),
                    (int) Math.max(0, Math.min(255, c.getBlue() * f)));
        }

        /**
         * 2.5D side-scroller camera (Platinum Arts / classic platformer slice).
         * X axis = map X, vertical = wall height bands; player centered horizontally.
         */
        void renderSideScroller() {
            java.util.Arrays.fill(pixels, currentPalette.bg.getRGB());
            int groundY = (int) (RENDER_H * 0.72);
            // sky strip
            for (int y = 0; y < groundY; y++) {
                int c = new Color(20, 30, 60).getRGB();
                for (int x = 0; x < RENDER_W; x++) pixels[y * RENDER_W + x] = c;
            }
            // ground strip
            for (int y = groundY; y < RENDER_H; y++) {
                int c = new Color(40, 90, 40).getRGB();
                for (int x = 0; x < RENDER_W; x++) pixels[y * RENDER_W + x] = c;
            }
            double tilesVisible = 16.0;
            double originX = posX - tilesVisible / 2.0;
            for (int sx = 0; sx < RENDER_W; sx++) {
                double mx = originX + (sx / (double) RENDER_W) * tilesVisible;
                int ix = (int) Math.floor(mx);
                int iy = (int) Math.floor(posY);
                if (ix < 0 || iy < 0 || ix >= MAP_SIZE || iy >= MAP_SIZE) continue;
                int cell = worldMap[ix][iy];
                if (cell == 1 || cell == 2) {
                    int wallTop = groundY - RENDER_H / 3;
                    int wallBot = groundY;
                    Color wc = cell == 2 ? new Color(160, 100, 40) : currentPalette.fg;
                    int rgb = wc.getRGB();
                    for (int y = wallTop; y < wallBot; y++) {
                        if (y >= 0 && y < RENDER_H) pixels[y * RENDER_W + sx] = rgb;
                    }
                } else if (cell == 3) {
                    // pit hole
                    for (int y = groundY; y < Math.min(RENDER_H, groundY + 20); y++)
                        pixels[y * RENDER_W + sx] = 0x101010;
                } else if (cell == 4) {
                    for (int y = groundY; y < Math.min(RENDER_H, groundY + 12); y++)
                        pixels[y * RENDER_W + sx] = 0x2060C0;
                }
            }
            // player capsule
            int px = RENDER_W / 2;
            int py = groundY - 18;
            for (int dy = -16; dy < 4; dy++)
                for (int dx = -6; dx <= 6; dx++) {
                    int x = px + dx, y = py + dy;
                    if (x >= 0 && y >= 0 && x < RENDER_W && y < RENDER_H)
                        pixels[y * RENDER_W + x] = 0x40FF80;
                }
            // nearby things as billboards on ground line
            for (Thing t : things) {
                double rel = t.x - originX;
                if (rel < 0 || rel > tilesVisible) continue;
                int sx = (int) ((rel / tilesVisible) * RENDER_W);
                int sy = groundY - 12;
                int rgb = t.tint != null ? t.tint.getRGB() : 0xFF8080;
                for (int dy = -10; dy < 6; dy++)
                    for (int dx = -5; dx <= 5; dx++) {
                        int x = sx + dx, y = sy + dy;
                        if (x >= 0 && y >= 0 && x < RENDER_W && y < RENDER_H)
                            pixels[y * RENDER_W + x] = rgb;
                    }
            }
        }

        /**
         * 2.5D top-down camera — orthographic map slice centered on player.
         */
        void renderTopDown() {
            java.util.Arrays.fill(pixels, 0x101810);
            double tilesVisible = 18.0;
            double cellPx = RENDER_W / tilesVisible;
            double originX = posX - tilesVisible / 2.0;
            double originY = posY - (RENDER_H / cellPx) / 2.0;
            for (int sy = 0; sy < RENDER_H; sy++) {
                for (int sx = 0; sx < RENDER_W; sx++) {
                    int ix = (int) Math.floor(originX + sx / cellPx);
                    int iy = (int) Math.floor(originY + sy / cellPx);
                    if (ix < 0 || iy < 0 || ix >= MAP_SIZE || iy >= MAP_SIZE) continue;
                    int cell = worldMap[ix][iy];
                    int rgb;
                    if (cell == 1) rgb = 0x606060;
                    else if (cell == 2) rgb = 0xA07030;
                    else if (cell == 3) rgb = 0x200000;
                    else if (cell == 4) rgb = 0x2060C0;
                    else rgb = 0x2A4A2A;
                    pixels[sy * RENDER_W + sx] = rgb;
                }
            }
            // things
            for (Thing t : things) {
                int sx = (int) ((t.x - originX) * cellPx);
                int sy = (int) ((t.y - originY) * cellPx);
                int rgb = t.tint != null ? t.tint.getRGB() : 0xFF6060;
                for (int dy = -3; dy <= 3; dy++)
                    for (int dx = -3; dx <= 3; dx++) {
                        int x = sx + dx, y = sy + dy;
                        if (x >= 0 && y >= 0 && x < RENDER_W && y < RENDER_H)
                            pixels[y * RENDER_W + x] = rgb;
                    }
            }
            // player
            int px = RENDER_W / 2, py = RENDER_H / 2;
            for (int dy = -4; dy <= 4; dy++)
                for (int dx = -4; dx <= 4; dx++) {
                    int x = px + dx, y = py + dy;
                    if (x >= 0 && y >= 0 && x < RENDER_W && y < RENDER_H)
                        pixels[y * RENDER_W + x] = 0x40FF80;
                }
            // facing tick
            int fx = px + (int) (dirX * 10), fy = py + (int) (dirY * 10);
            if (fx >= 0 && fy >= 0 && fx < RENDER_W && fy < RENDER_H)
                pixels[fy * RENDER_W + fx] = 0xFFFFFF;
        }

        @Override
        protected void paintComponent(Graphics g) {
            super.paintComponent(g);
            if (!isPaused) render();
            applyShaderPack();
            Graphics2D g2 = (Graphics2D) g;
            g2.drawImage(frame, 0, 0, getWidth(), getHeight(), null);
            if (!isPaused && rainEnabled) renderRain(g2, getWidth(), getHeight());
            // Floor transition fade (gentle black)
            if (floorFade > 0.01) {
                int a = (int) (Math.min(1.0, floorFade) * 220);
                g2.setColor(new Color(0, 0, 0, a));
                g2.fillRect(0, 0, getWidth(), getHeight());
            }

            if (!isPaused && !isInventoryOpen) { renderHUD(g2, getWidth(), getHeight()); renderWeaponHUD(g2, getWidth(), getHeight()); }
            if (isInventoryOpen) renderInventoryOverlay(g2, getWidth(), getHeight());
            if (isPaused) renderPauseOverlay(g2, getWidth(), getHeight());
        }

        /** Software shader packs — post-process pixels[] in place. */
        void applyShaderPack() {
            ChromaOptions o;
            try { o = ChromaOptions.load(); } catch (Throwable t) { return; }
            if (o == null || !o.shaderEnabled) return;
            String pack = o.shaderPack == null ? "none" : o.shaderPack.toLowerCase();
            if (pack.equals("none")) return;
            double t = Math.max(0, Math.min(1, o.shaderIntensity / 100.0));
            if (t <= 0.01) return;

            switch (pack) {
                case "scanlines" -> {
                    for (int y = 0; y < RENDER_H; y += 2) {
                        for (int x = 0; x < RENDER_W; x++) {
                            int i = y * RENDER_W + x;
                            int rgb = pixels[i];
                            int r = (rgb >> 16) & 0xff, g = (rgb >> 8) & 0xff, b = rgb & 0xff;
                            r = (int) (r * (1 - 0.35 * t));
                            g = (int) (g * (1 - 0.35 * t));
                            b = (int) (b * (1 - 0.35 * t));
                            pixels[i] = (r << 16) | (g << 8) | b;
                        }
                    }
                }
                case "crt" -> {
                    for (int y = 0; y < RENDER_H; y++) {
                        double vigY = Math.abs(y - RENDER_H / 2.0) / (RENDER_H / 2.0);
                        for (int x = 0; x < RENDER_W; x++) {
                            int i = y * RENDER_W + x;
                            int rgb = pixels[i];
                            int r = (rgb >> 16) & 0xff, g = (rgb >> 8) & 0xff, b = rgb & 0xff;
                            if ((y & 1) == 0) {
                                r = (int) (r * (1 - 0.25 * t));
                                g = (int) (g * (1 - 0.25 * t));
                                b = (int) (b * (1 - 0.25 * t));
                            }
                            double vigX = Math.abs(x - RENDER_W / 2.0) / (RENDER_W / 2.0);
                            double vig = 1.0 - (vigX * vigX + vigY * vigY) * 0.35 * t;
                            r = (int) Math.max(0, r * vig);
                            g = (int) Math.max(0, g * vig);
                            b = (int) Math.max(0, b * vig);
                            // slight phosphor shift
                            r = Math.min(255, (int) (r + 8 * t));
                            g = Math.min(255, (int) (g + 4 * t));
                            pixels[i] = (r << 16) | (g << 8) | b;
                        }
                    }
                }
                case "noir" -> {
                    for (int i = 0; i < pixels.length; i++) {
                        int rgb = pixels[i];
                        int r = (rgb >> 16) & 0xff, g = (rgb >> 8) & 0xff, b = rgb & 0xff;
                        int gray = (r * 30 + g * 59 + b * 11) / 100;
                        r = (int) (r * (1 - t) + gray * t);
                        g = (int) (g * (1 - t) + gray * t);
                        b = (int) (b * (1 - t) + gray * t);
                        pixels[i] = (r << 16) | (g << 8) | b;
                    }
                }
                case "warm" -> {
                    for (int i = 0; i < pixels.length; i++) {
                        int rgb = pixels[i];
                        int r = (rgb >> 16) & 0xff, g = (rgb >> 8) & 0xff, b = rgb & 0xff;
                        r = Math.min(255, (int) (r + 25 * t));
                        g = Math.min(255, (int) (g + 10 * t));
                        b = Math.max(0, (int) (b - 15 * t));
                        pixels[i] = (r << 16) | (g << 8) | b;
                    }
                }
                case "cool" -> {
                    for (int i = 0; i < pixels.length; i++) {
                        int rgb = pixels[i];
                        int r = (rgb >> 16) & 0xff, g = (rgb >> 8) & 0xff, b = rgb & 0xff;
                        r = Math.max(0, (int) (r - 10 * t));
                        g = Math.min(255, (int) (g + 5 * t));
                        b = Math.min(255, (int) (b + 25 * t));
                        pixels[i] = (r << 16) | (g << 8) | b;
                    }
                }
                case "bloom" -> {
                    // cheap horizontal smear of bright pixels
                    int[] copy = java.util.Arrays.copyOf(pixels, pixels.length);
                    for (int y = 0; y < RENDER_H; y++) {
                        for (int x = 2; x < RENDER_W - 2; x++) {
                            int i = y * RENDER_W + x;
                            int rgb = copy[i];
                            int br = ((rgb >> 16) & 0xff) + ((rgb >> 8) & 0xff) + (rgb & 0xff);
                            if (br < 400) continue;
                            for (int dx = -2; dx <= 2; dx++) {
                                if (dx == 0) continue;
                                int j = i + dx;
                                int d = pixels[j];
                                int r = Math.min(255, ((d >> 16) & 0xff) + (int) ((((rgb >> 16) & 0xff) * t) / 6));
                                int g = Math.min(255, ((d >> 8) & 0xff) + (int) ((((rgb >> 8) & 0xff) * t) / 6));
                                int b = Math.min(255, (d & 0xff) + (int) (((rgb & 0xff) * t) / 6));
                                pixels[j] = (r << 16) | (g << 8) | b;
                            }
                        }
                    }
                }
                default -> {
                    // custom pack folder: optional pack.properties keys ignored for now; treat as mild CRT
                    if (!pack.equals("none")) {
                        for (int y = 0; y < RENDER_H; y += 3) {
                            for (int x = 0; x < RENDER_W; x++) {
                                int i = y * RENDER_W + x;
                                int rgb = pixels[i];
                                int r = (int) (((rgb >> 16) & 0xff) * (1 - 0.15 * t));
                                int g = (int) (((rgb >> 8) & 0xff) * (1 - 0.15 * t));
                                int b = (int) ((rgb & 0xff) * (1 - 0.15 * t));
                                pixels[i] = (r << 16) | (g << 8) | b;
                            }
                        }
                    }
                }
            }
        }

        void renderRain(Graphics2D g2, int w, int h) {
            if (rainDrops.isEmpty()) {
                for (int i = 0; i < 80; i++)
                    rainDrops.add(new double[]{ Math.random(), Math.random(), 0.01 + Math.random() * 0.03 });
            }
            g2.setColor(new Color(180, 200, 255, 140));
            for (double[] d : rainDrops) {
                d[1] += d[2];
                if (d[1] > 1) { d[1] = 0; d[0] = Math.random(); }
                int x = (int) (d[0] * w);
                int y = (int) (d[1] * h);
                g2.drawLine(x, y, x - 1, y + 8);
            }
        }

        int weaponFlash = 0;

        /**
         * First-person weapon view — Daggerfall/Arena style pose offsets.
         * IDLE: bottom-center + walk bob
         * WINDUP: pulls back / up
         * STRIKE: thrusts across / forward
         * RECOVER: settles back
         */
        private void renderWeaponHUD(Graphics2D g2, int w, int h) {
            BufferedImage spr = null;
            boolean bow = equippedWeapon.equals("bow") || (bowEquipped && hasBow && equippedWeapon.equals("bow"));
            if (bow) {
                spr = (wepPhase == WepPhase.STRIKE || weaponFlash > 4) ? (bowUp != null ? bowUp : bowDown) : bowDown;
            } else if (equippedWeapon.equals("crossbow")) {
                spr = crossbowHud != null ? crossbowHud : loadFromSub("weapons", false, "crossbow.png");
            } else if (equippedWeapon.equals("cross_weapon")) {
                spr = (wepPhase == WepPhase.STRIKE) ? (crossWeaponToss != null ? crossWeaponToss : crossWeaponHud) : crossWeaponHud;
            } else if (equippedWeapon.equals("fireball")) {
                spr = fireballSprite != null ? fireballSprite : orbSprite;
            } else if (equippedWeapon.equals("hookshot") || equippedWeapon.equals("hook")) {
                spr = hookshotHud != null ? hookshotHud : loadFromSub("weapons", false, "hookshot.png");
            } else if (equippedWeapon.startsWith("sword")) {
                spr = swordHud;
                if (spr == null) spr = loadFromSub("weapons", false, equippedWeapon + ".png", "sword1.png");
                if (spr != null) swordHud = spr;
            } else if (equippedWeapon.equals("bomb")) {
                spr = bombHud != null ? bombHud : (bombParticleSprite != null ? bombParticleSprite : loadFromSub("weapons", false, "bomb.png"));
            }
            if (spr == null && hasBow) spr = bowDown;
            if (spr == null) return;

            int baseW = Math.min(w / 3, Math.max(64, spr.getWidth() * 2));
            int baseH = baseW * spr.getHeight() / Math.max(1, spr.getWidth());
            // Pose offsets as fractions of screen
            double ox = 0, oy = 0, scale = 1.0;
            double bobY = Math.sin(wepBob) * 6.0;
            double bobX = Math.cos(wepBob * 0.5) * 3.0;

            switch (wepPhase) {
                case IDLE -> {
                    ox = bobX;
                    oy = bobY;
                    // swords rest lower-right like Arena; bow center
                    if (equippedWeapon.startsWith("sword")) { ox += w * 0.12; oy += 8; }
                }
                case WINDUP -> {
                    double t = Math.min(1.0, wepFrame / 5.0);
                    if (bow) {
                        ox = 0; oy = -20 * t; scale = 1.0 - 0.05 * t;
                    } else {
                        // pull back and up (KF windup)
                        ox = w * 0.18 + 10 * t;
                        oy = 20 - 40 * t;
                        scale = 0.95;
                    }
                }
                case STRIKE -> {
                    double t = Math.min(1.0, wepFrame / 5.0);
                    if (bow) {
                        ox = 0; oy = 10; scale = 1.1;
                    } else {
                        // sweep left-center thrust
                        ox = w * 0.18 - w * 0.28 * t;
                        oy = -30 + 10 * t;
                        scale = 1.05 + 0.1 * t;
                    }
                }
                case RECOVER -> {
                    double t = Math.min(1.0, wepFrame / 8.0);
                    if (bow) {
                        ox = 0; oy = 5 * (1 - t);
                    } else {
                        ox = -w * 0.05 + w * 0.17 * t;
                        oy = -15 + 25 * t;
                    }
                    scale = 1.0;
                }
            }

            int drawW = (int) (baseW * scale);
            int drawH = (int) (baseH * scale);
            int x = (int) ((w - drawW) / 2.0 + ox);
            int y = (int) (h - drawH - 12 + oy);
            // slight tilt simulation: shear via AffineTransform for swords mid-swing
            if (equippedWeapon.startsWith("sword") && wepPhase == WepPhase.STRIKE) {
                Graphics2D g22 = (Graphics2D) g2.create();
                double ang = Math.toRadians(-25 + wepFrame * 8);
                g22.rotate(ang, x + drawW / 2.0, y + drawH / 2.0);
                g22.drawImage(spr, x, y, drawW, drawH, null);
                g22.dispose();
            } else {
                g2.drawImage(spr, x, y, drawW, drawH, null);
            }
        }

        private void renderHUD(Graphics2D g2, int w, int h) {
            int hudFs = options != null ? Math.max(11, options.hudFontSize - 1) : 12;
            g2.setColor(currentPalette.panel);
            g2.fillRect(10, 10, w - 20, 32);
            g2.setColor(currentPalette.fg);
            g2.drawRect(10, 10, w - 20, 32);
            g2.setFont(new Font("Monospaced", Font.BOLD, hudFs));
            g2.drawString("SCORE " + String.format("%06d", score), 18, 30);
            // HP pips
            int hx = w / 2 - 50;
            g2.drawString("HP", hx - 26, 30);
            for (int i = 0; i < 8; i++) {
                g2.setColor(i < hp ? new Color(0, 220, 90) : currentPalette.bg.darker());
                g2.fillRect(hx + i * 11, 16, 9, 12);
                g2.setColor(currentPalette.fg);
                g2.drawRect(hx + i * 11, 16, 9, 12);
            }
            g2.drawString("x" + Math.max(0, lives), hx + 95, 30);
            // Keys strip
            int kx = w - 280;
            g2.drawString("K:" + itemCount("skey") + "/" + itemCount("bkey") + "/" + itemCount("finalkey"), kx, 30);
            g2.drawString("AMMO " + String.format("%03d", ammo), w - 110, 30);
            // Equipped weapon icon bottom-left of HUD bar area is handled by weapon HUD
            g2.setFont(new Font("Monospaced", Font.PLAIN, 10));
            g2.drawString("EQ:" + equippedWeapon.toUpperCase(), 18, h - 8);
        }

        private void renderInventoryOverlay(Graphics2D g2, int w, int h) {
            g2.setColor(new Color(0, 0, 0, 220));
            g2.fillRect(0, 0, w, h);
            int panelW = Math.min(640, w - 40), panelH = Math.min(420, h - 40);
            int px = (w - panelW) / 2, py = (h - panelH) / 2;
            g2.setColor(currentPalette.panel);
            g2.fillRect(px, py, panelW, panelH);
            g2.setColor(currentPalette.fg);
            g2.drawRect(px, py, panelW, panelH);
            g2.setFont(new Font("Monospaced", Font.BOLD, 18));
            g2.drawString("INVENTORY  [I]", px + 16, py + 26);
            g2.setFont(new Font("Monospaced", Font.PLAIN, 11));
            g2.drawString("Keys / gear / consumables — 1 Bow  2 Sword  3 Bomb  F5 3rd", px + 16, py + 44);

            // Equipment strip
            int eqY = py + 56;
            g2.setColor(currentPalette.bg);
            g2.fillRect(px + 12, eqY, panelW - 24, 52);
            g2.setColor(currentPalette.fg);
            g2.drawRect(px + 12, eqY, panelW - 24, 52);
            g2.drawString("EQUIPPED: " + equippedWeapon.toUpperCase()
                    + "   HP " + hp + "/8   LIVES " + lives
                    + "   AMMO " + ammo
                    + "   KEYS s:" + itemCount("skey") + " b:" + itemCount("bkey") + " f:" + itemCount("finalkey"),
                    px + 20, eqY + 30);

            int slot = 0;
            int cols = 8;
            int slotSize = 56;
            int startX = px + 16, startY = eqY + 64;
            if (inventory.isEmpty()) {
                g2.drawString("(empty — chests, jars, boxes, world pickups)", px + 20, startY + 20);
            }
            for (InvItem it : inventory) {
                int col = slot % cols, row = slot / cols;
                int sx = startX + col * (slotSize + 8);
                int sy = startY + row * (slotSize + 22);
                boolean eq = it.id != null && it.id.equalsIgnoreCase(equippedWeapon);
                g2.setColor(eq ? new Color(60, 80, 40) : currentPalette.bg);
                g2.fillRect(sx, sy, slotSize, slotSize);
                g2.setColor(eq ? Color.YELLOW : currentPalette.fg);
                g2.drawRect(sx, sy, slotSize, slotSize);
                if (it.icon != null) {
                    g2.drawImage(it.icon, sx + 4, sy + 4, slotSize - 8, slotSize - 8, null);
                }
                g2.setFont(new Font("Monospaced", Font.BOLD, 10));
                g2.setColor(currentPalette.fg);
                String label = it.name.length() > 10 ? it.name.substring(0, 9) + "." : it.name;
                g2.drawString(label, sx, sy + slotSize + 12);
                if (it.count > 1) {
                    g2.setColor(Color.YELLOW);
                    g2.drawString("x" + it.count, sx + slotSize - 18, sy + 12);
                }
                slot++;
            }
            g2.setFont(new Font("Monospaced", Font.PLAIN, 11));
            g2.setColor(currentPalette.fg);
            g2.drawString("Map " + MAP_SIZE + "x" + MAP_SIZE + "  |  Cam " + (options != null ? options.cameraMode : "RAYCAST")
                    + (options != null && options.thirdPerson ? " 3RD" : " 1ST"),
                    px + 16, py + panelH - 12);
        }

        private void renderPauseOverlay(Graphics2D g2, int w, int h) {
            g2.setColor(new Color(0, 0, 0, 200)); g2.fillRect(0, 0, w, h);
            g2.setColor(currentPalette.fg); g2.setFont(new Font("Monospaced", Font.BOLD, 24));
            g2.drawString("PAUSED (Press ESC to resume)", w / 3, h / 2);
        }
    }

    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> {
            LOCWTTP eng = new LOCWTTP();
            eng.setVisible(true);
            // Optional map: CLI arg or -Dchroma.map=path (launcher samples)
            String mapArg = null;
            if (args != null && args.length > 0 && args[0] != null && !args[0].isBlank())
                mapArg = args[0];
            if (mapArg == null) {
                String prop = System.getProperty("chroma.map");
                if (prop != null && !prop.isBlank()) mapArg = prop;
            }
            if (mapArg != null) {
                File mf = new File(mapArg);
                if (mf.isFile()) {
                    try {
                        eng.loadMap(mf);
                        eng.status.setText(" // LOADED " + mf.getName());
                    } catch (Exception ex) {
                        eng.status.setText(" // MAP LOAD FAILED: " + ex.getMessage());
                    }
                } else {
                    eng.status.setText(" // MAP NOT FOUND: " + mapArg);
                }
            }
        });
    }
}