import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.border.LineBorder;
import javax.swing.filechooser.FileNameExtensionFilter;
import java.awt.*;
import java.awt.event.*;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;
import java.io.*;
import java.util.List;
import java.util.*;

/**
 * SFPAINT 3D // RAYCAST ROOM MODULE
 * ----------------------------------------------------------------
 * Companion window to SFPaintGUI.java. Launched when the 2D app's
 * "3D" button is pressed. Renders a classic software raycast room
 * (Wolfenstein-style, no OpenGL / no GPU acceleration required so it
 * stays light on old hardware like a Radeon HD 6450 + Pentium Dual
 * Core). Low-poly .obj models can be loaded, walked around, and
 * painted face-by-face with flat colors, then exported back out as
 * a vertex-colored .obj + .mtl pair.
 *
 * Integration (from SFPaintGUI.java):
 *
 *   btn3D.addActionListener(e -> {
 *       is3DMode = true;
 *       SFPaint3D.launch(SFPaintGUI.this, activeColor);
 *   });
 *
 * Everything below is self-contained: this file can also run
 * standalone via its own main().
 * ----------------------------------------------------------------
 */
public class SFPaint3D extends JFrame {

    // ---------- retro theme system ----------
    enum Theme {
        NEON_GREEN("CHROMAC", new Color(0, 255, 100), new Color(10, 10, 10), new Color(15, 15, 15),
                new Color(0, 140, 60), new Color(0, 90, 40), new Color(6, 30, 14), new Color(3, 15, 7)),
        AMBER("AMBERTERM", new Color(255, 176, 0), new Color(12, 8, 0), new Color(18, 12, 0),
                new Color(180, 120, 0), new Color(120, 80, 0), new Color(35, 22, 0), new Color(18, 11, 0)),
        CYAN("CYBERWAVE", new Color(60, 230, 255), new Color(6, 8, 14), new Color(10, 12, 20),
                new Color(30, 140, 190), new Color(20, 90, 130), new Color(10, 30, 45), new Color(5, 15, 22)),
        MONO("MONOCHROME", new Color(230, 230, 230), new Color(8, 8, 8), new Color(14, 14, 14),
                new Color(140, 140, 140), new Color(90, 90, 90), new Color(28, 28, 28), new Color(14, 14, 14));

        final String label;
        final Color fg, bg, panel, wallA, wallB, floor, ceiling;
        Theme(String label, Color fg, Color bg, Color panel, Color wallA, Color wallB, Color floor, Color ceiling) {
            this.label = label; this.fg = fg; this.bg = bg; this.panel = panel;
            this.wallA = wallA; this.wallB = wallB; this.floor = floor; this.ceiling = ceiling;
        }
    }

    private Theme theme = Theme.NEON_GREEN;
    private Color activeColor;

    // ---------- room map ----------
    // 0 = open floor, 1..4 = wall variants. Square grid, outer ring is solid.
    private static final int MAP_SIZE = 16;
    private final int[][] worldMap = buildDefaultMap();

    private static int[][] buildDefaultMap() {
        int[][] m = new int[MAP_SIZE][MAP_SIZE];
        for (int x = 0; x < MAP_SIZE; x++) {
            for (int y = 0; y < MAP_SIZE; y++) {
                if (x == 0 || y == 0 || x == MAP_SIZE - 1 || y == MAP_SIZE - 1) m[x][y] = 1;
                else m[x][y] = 0;
            }
        }
        // a few interior pillars / accent walls for a gallery feel
        m[4][4] = 2; m[4][11] = 2; m[11][4] = 2; m[11][11] = 2;
        for (int i = 6; i <= 9; i++) m[i][2] = 3;
        return m;
    }

    // ---------- render buffer (internal low-res for retro look + speed) ----------
    private static final int RENDER_W = 320;
    private static final int RENDER_H = 200;
    private static final double FOV_PLANE = 0.66;

    private RaycastPanel panel;
    private JLabel statusBar;
    private JPanel swatchRow;

    private final Mesh mesh = new Mesh();

    public SFPaint3D(Window owner, Color initialColor) {
        this.activeColor = initialColor != null ? initialColor : new Color(0, 255, 100);
        setTitle("SFPAINT 3D // RAYCAST ROOM");
        setSize(1000, 680);
        setMinimumSize(new Dimension(700, 480));
        setLocationRelativeTo(owner);
        setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);

        JPanel root = new JPanel(new BorderLayout(4, 4));
        root.setBackground(theme.bg);
        root.setBorder(new LineBorder(theme.fg, 2));

        root.add(buildTopBar(), BorderLayout.NORTH);

        panel = new RaycastPanel();
        root.add(panel, BorderLayout.CENTER);

        statusBar = new JLabel(helpText());
        statusBar.setFont(new Font("Monospaced", Font.BOLD, 11));
        statusBar.setForeground(theme.fg);
        statusBar.setBorder(new EmptyBorder(4, 8, 4, 8));
        root.add(statusBar, BorderLayout.SOUTH);

        setContentPane(root);
        placeDefaultCube();

        addWindowListener(new WindowAdapter() {
            @Override public void windowClosing(WindowEvent e) { panel.stopLoop(); }
        });

        SwingUtilities.invokeLater(() -> panel.requestFocusInWindow());
    }

    /** Convenience entry point for the 2D app's "3D" button. */
    public static void launch(Window owner, Color initialColor) {
        SFPaint3D f = new SFPaint3D(owner, initialColor);
        f.setVisible(true);
    }

    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> new SFPaint3D(null, new Color(0, 255, 100)).setVisible(true));
    }

    private String helpText() {
        return "  // W A S D move  |  Q/E or ←/→ turn  |  CLICK a model face to paint  |  "
                + "1-8 palette  |  T theme  |  H help  |  ESC close";
    }

    // ---------- top bar ----------
    private JPanel buildTopBar() {
        JPanel bar = new JPanel(new BorderLayout(4, 0));
        bar.setBackground(theme.bg);

        JPanel left = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 4));
        left.setOpaque(false);
        left.add(themedButton("LOAD .OBJ", e -> loadObjDialog()));
        left.add(themedButton("SAVE .OBJ", e -> saveObjDialog()));
        left.add(themedButton("RESET CUBE", e -> placeDefaultCube()));
        left.add(themedButton("THEME", e -> cycleTheme()));
        left.add(themedButton("HELP", e -> JOptionPane.showMessageDialog(this, helpText().replace("  // ", "")
                + "\n\nCLICK anywhere on a loaded model to flat-paint that face with the active color.\n"
                + "Exports write name.obj + name.mtl (per-face color materials)."
                , "SFPAINT 3D HELP", JOptionPane.INFORMATION_MESSAGE)));
        left.add(themedButton("BACK TO 2D", e -> { panel.stopLoop(); dispose(); }));
        bar.add(left, BorderLayout.WEST);

        swatchRow = new JPanel(new FlowLayout(FlowLayout.RIGHT, 3, 4));
        swatchRow.setOpaque(false);
        rebuildSwatches();
        bar.add(swatchRow, BorderLayout.EAST);
        return bar;
    }

    private void rebuildSwatches() {
        swatchRow.removeAll();
        Color[] preset = {
                theme.fg, Color.CYAN, Color.BLUE, Color.MAGENTA, Color.RED,
                Color.ORANGE, Color.YELLOW, Color.WHITE, Color.GRAY, Color.BLACK
        };
        for (Color c : preset) {
            JPanel sw = new JPanel();
            sw.setBackground(c);
            sw.setPreferredSize(new Dimension(20, 20));
            sw.setBorder(new LineBorder(Color.BLACK, 1));
            sw.setCursor(new Cursor(Cursor.HAND_CURSOR));
            sw.addMouseListener(new MouseAdapter() {
                public void mousePressed(MouseEvent e) { activeColor = c; }
            });
            swatchRow.add(sw);
        }
        JButton custom = themedButton("CUSTOM", e -> {
            Color c = JColorChooser.showDialog(this, "Paint Color", activeColor);
            if (c != null) activeColor = c;
        });
        swatchRow.add(custom);
        swatchRow.revalidate();
        swatchRow.repaint();
    }

    private void cycleTheme() {
        Theme[] all = Theme.values();
        theme = all[(theme.ordinal() + 1) % all.length];
        getContentPane().setBackground(theme.bg);
        statusBar.setForeground(theme.fg);
        rebuildSwatches();
        repaint();
    }

    private JButton themedButton(String text, ActionListener al) {
        JButton b = new JButton(text);
        b.setFont(new Font("Monospaced", Font.BOLD, 11));
        b.setForeground(theme.fg);
        b.setBackground(theme.bg);
        b.setFocusable(false);
        b.setBorder(new LineBorder(theme.fg, 1));
        b.setCursor(new Cursor(Cursor.HAND_CURSOR));
        b.addActionListener(al);
        b.addMouseListener(new MouseAdapter() {
            public void mouseEntered(MouseEvent e) { b.setBackground(theme.panel); }
            public void mouseExited(MouseEvent e) { b.setBackground(theme.bg); }
        });
        return b;
    }

    // ---------- default placeholder model ----------
    private void placeDefaultCube() {
        mesh.clear();
        double[][] v = {
                {-0.5, 0, -0.5}, {0.5, 0, -0.5}, {0.5, 0, 0.5}, {-0.5, 0, 0.5},
                {-0.5, 1, -0.5}, {0.5, 1, -0.5}, {0.5, 1, 0.5}, {-0.5, 1, 0.5}
        };
        for (double[] p : v) mesh.verts.add(p);
        int[][] faces = {
                {0, 1, 2}, {0, 2, 3},       // bottom
                {4, 6, 5}, {4, 7, 6},       // top
                {0, 4, 5}, {0, 5, 1},       // front
                {1, 5, 6}, {1, 6, 2},       // right
                {2, 6, 7}, {2, 7, 3},       // back
                {3, 7, 4}, {3, 4, 0}        // left
        };
        for (int[] f : faces) {
            mesh.faces.add(f);
            mesh.faceColors.add(new Color(0, 200, 90));
        }
        mesh.originX = MAP_SIZE / 2.0;
        mesh.originY = MAP_SIZE / 2.0;
        mesh.originZ = 0;
        mesh.scale = 1.4;
        panel.repaint();
    }

    // ---------- OBJ IO ----------
    private void loadObjDialog() {
        JFileChooser chooser = new JFileChooser();
        chooser.setFileFilter(new FileNameExtensionFilter("Wavefront OBJ", "obj"));
        if (chooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
            try {
                Mesh loaded = Mesh.loadObj(chooser.getSelectedFile());
                loaded.originX = MAP_SIZE / 2.0;
                loaded.originY = MAP_SIZE / 2.0;
                loaded.originZ = 0;
                loaded.scale = 1.6;
                mesh.clear();
                mesh.verts.addAll(loaded.verts);
                mesh.faces.addAll(loaded.faces);
                mesh.faceColors.addAll(loaded.faceColors);
                mesh.originX = loaded.originX; mesh.originY = loaded.originY;
                mesh.originZ = loaded.originZ; mesh.scale = loaded.scale;
                statusBar.setText("  // LOADED " + chooser.getSelectedFile().getName()
                        + "  (" + mesh.verts.size() + "v / " + mesh.faces.size() + "f)");
                panel.repaint();
            } catch (Exception ex) {
                JOptionPane.showMessageDialog(this, "OBJ load failed: " + ex.getMessage());
            }
        }
    }

    private void saveObjDialog() {
        if (mesh.verts.isEmpty()) {
            JOptionPane.showMessageDialog(this, "Nothing to save.");
            return;
        }
        JFileChooser chooser = new JFileChooser();
        chooser.setSelectedFile(new File("sfpaint_model.obj"));
        chooser.setFileFilter(new FileNameExtensionFilter("Wavefront OBJ", "obj"));
        if (chooser.showSaveDialog(this) == JFileChooser.APPROVE_OPTION) {
            try {
                File out = chooser.getSelectedFile();
                if (!out.getName().toLowerCase().endsWith(".obj")) out = new File(out.getParentFile(), out.getName() + ".obj");
                mesh.saveObj(out);
                statusBar.setText("  // SAVED: " + out.getAbsolutePath());
            } catch (Exception ex) {
                JOptionPane.showMessageDialog(this, "Save failed: " + ex.getMessage());
            }
        }
    }

    // ================================================================
    //  Low-poly mesh model
    // ================================================================
    private static class Mesh {
        final List<double[]> verts = new ArrayList<>();
        final List<int[]> faces = new ArrayList<>();      // triangle vertex indices (0-based)
        final List<Color> faceColors = new ArrayList<>();
        double originX, originY, originZ;                  // world placement (map coords + height)
        double scale = 1.0;

        void clear() { verts.clear(); faces.clear(); faceColors.clear(); }

        static Mesh loadObj(File file) throws IOException {
            Mesh m = new Mesh();
            List<double[]> rawVerts = new ArrayList<>();
            try (BufferedReader br = new BufferedReader(new FileReader(file))) {
                String line;
                while ((line = br.readLine()) != null) {
                    line = line.trim();
                    if (line.isEmpty() || line.startsWith("#")) continue;
                    String[] tok = line.split("\\s+");
                    if (tok[0].equals("v") && tok.length >= 4) {
                        rawVerts.add(new double[]{
                                Double.parseDouble(tok[1]), Double.parseDouble(tok[2]), Double.parseDouble(tok[3])
                        });
                    } else if (tok[0].equals("f") && tok.length >= 4) {
                        int[] idx = new int[tok.length - 1];
                        for (int i = 1; i < tok.length; i++) {
                            String ref = tok[i].split("/")[0];
                            int vi = Integer.parseInt(ref);
                            if (vi < 0) vi = rawVerts.size() + 1 + vi; // relative index
                            idx[i - 1] = vi - 1;
                        }
                        // fan-triangulate polygons with more than 3 verts
                        for (int i = 1; i < idx.length - 1; i++) {
                            m.faces.add(new int[]{idx[0], idx[i], idx[i + 1]});
                            m.faceColors.add(new Color(0, 200, 90));
                        }
                    }
                }
            }
            if (rawVerts.isEmpty()) throw new IOException("No vertices found in file.");
            // normalize: center on origin, scale so the largest dimension = 1
            double minX = Double.MAX_VALUE, minY = Double.MAX_VALUE, minZ = Double.MAX_VALUE;
            double maxX = -Double.MAX_VALUE, maxY = -Double.MAX_VALUE, maxZ = -Double.MAX_VALUE;
            for (double[] v : rawVerts) {
                minX = Math.min(minX, v[0]); maxX = Math.max(maxX, v[0]);
                minY = Math.min(minY, v[1]); maxY = Math.max(maxY, v[1]);
                minZ = Math.min(minZ, v[2]); maxZ = Math.max(maxZ, v[2]);
            }
            double cx = (minX + maxX) / 2, cz = (minZ + maxZ) / 2;
            double span = Math.max(maxX - minX, Math.max(maxY - minY, maxZ - minZ));
            if (span < 1e-6) span = 1;
            double s = 1.0 / span;
            for (double[] v : rawVerts) {
                m.verts.add(new double[]{(v[0] - cx) * s, (v[1] - minY) * s, (v[2] - cz) * s});
            }
            return m;
        }

        void saveObj(File out) throws IOException {
            File mtlFile = new File(out.getParentFile(), stripExt(out.getName()) + ".mtl");
            Map<Color, String> matNames = new LinkedHashMap<>();
            try (PrintWriter mw = new PrintWriter(new FileWriter(mtlFile))) {
                int n = 0;
                for (Color c : faceColors) {
                    if (!matNames.containsKey(c)) {
                        String name = "mat" + (n++);
                        matNames.put(c, name);
                        mw.printf("newmtl %s%n", name);
                        mw.printf("Kd %.4f %.4f %.4f%n", c.getRed() / 255.0, c.getGreen() / 255.0, c.getBlue() / 255.0);
                        mw.println("illum 1");
                    }
                }
            }
            try (PrintWriter w = new PrintWriter(new FileWriter(out))) {
                w.println("# exported by SFPAINT 3D raycast module");
                w.println("mtllib " + mtlFile.getName());
                for (double[] v : verts) w.printf("v %.6f %.6f %.6f%n", v[0], v[1], v[2]);
                String lastMat = null;
                for (int i = 0; i < faces.size(); i++) {
                    String mat = matNames.get(faceColors.get(i));
                    if (!mat.equals(lastMat)) { w.println("usemtl " + mat); lastMat = mat; }
                    int[] f = faces.get(i);
                    w.printf("f %d %d %d%n", f[0] + 1, f[1] + 1, f[2] + 1);
                }
            }
        }

        private static String stripExt(String name) {
            int dot = name.lastIndexOf('.');
            return dot < 0 ? name : name.substring(0, dot);
        }
    }

    // ================================================================
    //  Raycast viewport
    // ================================================================
    private class RaycastPanel extends JPanel {
        // player state
        double posX = MAP_SIZE / 2.0, posY = MAP_SIZE / 2.0 - 3;
        double dirX = 0, dirY = 1;
        double planeX = FOV_PLANE, planeY = 0;
        final double moveSpeed = 0.055;
        final double rotSpeed = 0.045;

        final Set<Integer> keysDown = new HashSet<>();
        final BufferedImage frame = new BufferedImage(RENDER_W, RENDER_H, BufferedImage.TYPE_INT_RGB);
        final int[] pixels = ((DataBufferInt) frame.getRaster().getDataBuffer()).getData();
        final int[] pickBuffer = new int[RENDER_W * RENDER_H];
        final double[] zbuffer = new double[RENDER_W];
        final javax.swing.Timer loop;

        RaycastPanel() {
            setBackground(theme.panel);
            setFocusable(true);

            loop = new javax.swing.Timer(33, e -> { tick(); repaint(); });
            loop.start();

            addKeyListener(new KeyAdapter() {
                public void keyPressed(KeyEvent e) { onKey(e.getKeyCode(), true); }
                public void keyReleased(KeyEvent e) { onKey(e.getKeyCode(), false); }
            });
            addMouseListener(new MouseAdapter() {
                public void mousePressed(MouseEvent e) {
                    requestFocusInWindow();
                    handlePaintClick(e.getX(), e.getY());
                }
            });
        }

        void stopLoop() { loop.stop(); }

        private void onKey(int code, boolean down) {
            if (down) {
                switch (code) {
                    case KeyEvent.VK_T -> cycleTheme();
                    case KeyEvent.VK_H -> JOptionPane.showMessageDialog(SFPaint3D.this,
                            helpText().replace("  // ", ""), "CONTROLS", JOptionPane.INFORMATION_MESSAGE);
                    case KeyEvent.VK_ESCAPE -> { stopLoop(); dispose(); }
                    default -> {
                        if (code >= KeyEvent.VK_1 && code <= KeyEvent.VK_8) {
                            Color[] preset = {theme.fg, Color.CYAN, Color.BLUE, Color.MAGENTA,
                                    Color.RED, Color.ORANGE, Color.YELLOW, Color.WHITE};
                            activeColor = preset[code - KeyEvent.VK_1];
                        }
                    }
                }
            }
            if (code == KeyEvent.VK_W || code == KeyEvent.VK_UP) setKey(87, down);
            if (code == KeyEvent.VK_S || code == KeyEvent.VK_DOWN) setKey(83, down);
            if (code == KeyEvent.VK_A) setKey(65, down);
            if (code == KeyEvent.VK_D) setKey(68, down);
            if (code == KeyEvent.VK_Q || code == KeyEvent.VK_LEFT) setKey(81, down);
            if (code == KeyEvent.VK_E || code == KeyEvent.VK_RIGHT) setKey(69, down);
        }

        private void setKey(int id, boolean down) {
            if (down) keysDown.add(id); else keysDown.remove(id);
        }

        private void tick() {
            if (keysDown.contains(87)) move(moveSpeed);
            if (keysDown.contains(83)) move(-moveSpeed);
            if (keysDown.contains(65)) strafe(-moveSpeed);
            if (keysDown.contains(68)) strafe(moveSpeed);
            if (keysDown.contains(81)) rotate(-rotSpeed);
            if (keysDown.contains(69)) rotate(rotSpeed);
        }

        private void move(double amt) {
            double nx = posX + dirX * amt, ny = posY + dirY * amt;
            if (worldMap[(int) nx][(int) posY] == 0) posX = nx;
            if (worldMap[(int) posX][(int) ny] == 0) posY = ny;
        }

        private void strafe(double amt) {
            double nx = posX + dirY * amt, ny = posY - dirX * amt;
            if (worldMap[(int) nx][(int) posY] == 0) posX = nx;
            if (worldMap[(int) posX][(int) ny] == 0) posY = ny;
        }

        private void rotate(double a) {
            double oldDirX = dirX;
            dirX = dirX * Math.cos(a) - dirY * Math.sin(a);
            dirY = oldDirX * Math.sin(a) + dirY * Math.cos(a);
            double oldPlaneX = planeX;
            planeX = planeX * Math.cos(a) - planeY * Math.sin(a);
            planeY = oldPlaneX * Math.sin(a) + planeY * Math.cos(a);
        }

        // ---------- rendering ----------
        private void render() {
            Arrays.fill(pickBuffer, -1);
            renderWallsFloorCeil();
            renderMesh();
        }

        private void renderWallsFloorCeil() {
            Color floorC = theme.floor, ceilC = theme.ceiling;
            for (int x = 0; x < RENDER_W; x++) {
                double cameraX = 2.0 * x / RENDER_W - 1;
                double rayDirX = dirX + planeX * cameraX;
                double rayDirY = dirY + planeY * cameraX;

                int mapX = (int) posX, mapY = (int) posY;
                double deltaDistX = (rayDirX == 0) ? 1e30 : Math.abs(1 / rayDirX);
                double deltaDistY = (rayDirY == 0) ? 1e30 : Math.abs(1 / rayDirY);

                int stepX, stepY;
                double sideDistX, sideDistY;
                if (rayDirX < 0) { stepX = -1; sideDistX = (posX - mapX) * deltaDistX; }
                else { stepX = 1; sideDistX = (mapX + 1.0 - posX) * deltaDistX; }
                if (rayDirY < 0) { stepY = -1; sideDistY = (posY - mapY) * deltaDistY; }
                else { stepY = 1; sideDistY = (mapY + 1.0 - posY) * deltaDistY; }

                int side = 0, hitVal = 0;
                for (int i = 0; i < 64; i++) {
                    if (sideDistX < sideDistY) { sideDistX += deltaDistX; mapX += stepX; side = 0; }
                    else { sideDistY += deltaDistY; mapY += stepY; side = 1; }
                    if (mapX < 0 || mapY < 0 || mapX >= MAP_SIZE || mapY >= MAP_SIZE) { hitVal = 1; break; }
                    if (worldMap[mapX][mapY] != 0) { hitVal = worldMap[mapX][mapY]; break; }
                }

                double perpDist = side == 0
                        ? (mapX - posX + (1 - stepX) / 2.0) / (rayDirX == 0 ? 1e-9 : rayDirX)
                        : (mapY - posY + (1 - stepY) / 2.0) / (rayDirY == 0 ? 1e-9 : rayDirY);
                perpDist = Math.max(0.05, Math.abs(perpDist));
                zbuffer[x] = perpDist;

                int lineHeight = (int) (RENDER_H / perpDist);
                int drawStart = Math.max(0, -lineHeight / 2 + RENDER_H / 2);
                int drawEnd = Math.min(RENDER_H - 1, lineHeight / 2 + RENDER_H / 2);

                Color base = (hitVal % 2 == 0) ? theme.wallB : theme.wallA;
                double shade = side == 1 ? 0.7 : 1.0;
                shade *= Math.max(0.35, Math.min(1.0, 1.4 - perpDist * 0.06));
                int wallRGB = shadeColor(base, shade).getRGB();

                for (int y = 0; y < drawStart; y++) pixels[y * RENDER_W + x] = ceilC.getRGB();
                for (int y = drawStart; y <= drawEnd; y++) pixels[y * RENDER_W + x] = wallRGB;
                for (int y = drawEnd + 1; y < RENDER_H; y++) {
                    double rowShade = Math.max(0.25, 1.0 - (y - RENDER_H / 2.0) / (RENDER_H * 0.9));
                    pixels[y * RENDER_W + x] = shadeColor(floorC, rowShade).getRGB();
                }
            }
        }

        private void renderMesh() {
            if (mesh.verts.isEmpty()) return;
            int n = mesh.faces.size();
            Integer[] order = new Integer[n];
            double[] depth = new double[n];
            for (int i = 0; i < n; i++) {
                order[i] = i;
                int[] f = mesh.faces.get(i);
                double sumT = 0; int cnt = 0;
                for (int vi : f) {
                    double[] v = mesh.verts.get(vi);
                    double wx = mesh.originX + v[0] * mesh.scale;
                    double wy = mesh.originY + v[2] * mesh.scale;
                    double dx = wx - posX, dy = wy - posY;
                    double invDet = 1.0 / (planeX * dirY - dirX * planeY);
                    double t = invDet * (-planeY * dx + planeX * dy);
                    sumT += t; cnt++;
                }
                depth[i] = sumT / cnt;
            }
            Arrays.sort(order, (a, b) -> Double.compare(depth[b], depth[a])); // far -> near (painter's algorithm)

            double[] sx = new double[3], sy = new double[3], sz = new double[3];
            for (int oi : order) {
                int[] f = mesh.faces.get(oi);
                boolean visible = true;
                for (int k = 0; k < 3; k++) {
                    double[] v = mesh.verts.get(f[k]);
                    double wx = mesh.originX + v[0] * mesh.scale;
                    double wy = mesh.originY + v[2] * mesh.scale;
                    double wh = mesh.originZ + v[1] * mesh.scale;
                    double dx = wx - posX, dy = wy - posY;
                    double invDet = 1.0 / (planeX * dirY - dirX * planeY);
                    double transformX = invDet * (dirY * dx - dirX * dy);
                    double transformY = invDet * (-planeY * dx + planeX * dy);
                    if (transformY <= 0.05) { visible = false; break; }
                    double lineHeight = RENDER_H / transformY;
                    sx[k] = (RENDER_W / 2.0) * (1 + transformX / transformY);
                    sy[k] = RENDER_H / 2.0 - (wh - 0.5) * lineHeight;
                    sz[k] = transformY;
                }
                if (!visible) continue;
                rasterizeTriangle(sx, sy, sz, mesh.faceColors.get(oi), oi);
            }
        }

        private void rasterizeTriangle(double[] sx, double[] sy, double[] sz, Color color, int faceIndex) {
            int minX = (int) Math.max(0, Math.floor(Math.min(sx[0], Math.min(sx[1], sx[2]))));
            int maxX = (int) Math.min(RENDER_W - 1, Math.ceil(Math.max(sx[0], Math.max(sx[1], sx[2]))));
            int minY = (int) Math.max(0, Math.floor(Math.min(sy[0], Math.min(sy[1], sy[2]))));
            int maxY = (int) Math.min(RENDER_H - 1, Math.ceil(Math.max(sy[0], Math.max(sy[1], sy[2]))));
            if (minX > maxX || minY > maxY) return;

            double denom = (sy[1] - sy[2]) * (sx[0] - sx[2]) + (sx[2] - sx[1]) * (sy[0] - sy[2]);
            if (Math.abs(denom) < 1e-9) return;

            double avgDepth = (sz[0] + sz[1] + sz[2]) / 3.0;
            double shade = Math.max(0.35, Math.min(1.0, 1.3 - avgDepth * 0.05));
            int rgb = shadeColor(color, shade).getRGB();

            for (int py = minY; py <= maxY; py++) {
                for (int px = minX; px <= maxX; px++) {
                    double w0 = ((sy[1] - sy[2]) * (px - sx[2]) + (sx[2] - sx[1]) * (py - sy[2])) / denom;
                    double w1 = ((sy[2] - sy[0]) * (px - sx[2]) + (sx[0] - sx[2]) * (py - sy[2])) / denom;
                    double w2 = 1 - w0 - w1;
                    if (w0 < -0.001 || w1 < -0.001 || w2 < -0.001) continue;
                    double pixDepth = w0 * sz[0] + w1 * sz[1] + w2 * sz[2];
                    if (pixDepth <= 0.05 || pixDepth >= zbuffer[px]) continue;
                    int idx = py * RENDER_W + px;
                    pixels[idx] = rgb;
                    pickBuffer[idx] = faceIndex;
                }
            }
        }

        private Color shadeColor(Color c, double factor) {
            int r = (int) Math.max(0, Math.min(255, c.getRed() * factor));
            int g = (int) Math.max(0, Math.min(255, c.getGreen() * factor));
            int b = (int) Math.max(0, Math.min(255, c.getBlue() * factor));
            return new Color(r, g, b);
        }

        private void handlePaintClick(int mx, int my) {
            if (mesh.faceColors.isEmpty()) return;
            int rw = getWidth(), rh = getHeight();
            if (rw <= 0 || rh <= 0) return;
            int rx = mx * RENDER_W / rw;
            int ry = my * RENDER_H / rh;
            rx = Math.max(0, Math.min(RENDER_W - 1, rx));
            ry = Math.max(0, Math.min(RENDER_H - 1, ry));
            int face = pickBuffer[ry * RENDER_W + rx];
            if (face >= 0) {
                mesh.faceColors.set(face, activeColor);
                statusBar.setText("  // PAINTED FACE #" + face + "  |  ACTIVE: #"
                        + String.format("%02X%02X%02X", activeColor.getRed(), activeColor.getGreen(), activeColor.getBlue()));
                repaint();
            }
        }

        @Override
        protected void paintComponent(Graphics g) {
            super.paintComponent(g);
            render();
            Graphics2D g2 = (Graphics2D) g;
            g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
            g2.drawImage(frame, 0, 0, getWidth(), getHeight(), null);

            // crosshair
            g2.setColor(theme.fg);
            int cx = getWidth() / 2, cy = getHeight() / 2;
            g2.drawLine(cx - 6, cy, cx + 6, cy);
            g2.drawLine(cx, cy - 6, cx, cy + 6);

            g2.setFont(new Font("Monospaced", Font.BOLD, 11));
            g2.drawString("THEME: " + theme.label + "   FACES: " + mesh.faces.size(), 8, 16);
        }
    }
}
