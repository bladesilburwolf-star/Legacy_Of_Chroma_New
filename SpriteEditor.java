import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.border.LineBorder;
import javax.swing.filechooser.FileNameExtensionFilter;
import java.awt.*;
import java.awt.event.*;
import java.awt.image.BufferedImage;
import java.io.File;
import javax.imageio.ImageIO;

/**
 * SpriteEditor — lightweight pixel painter for CHROMA engine sprites.
 * Patterned after SFPaint3D (paint faces) but for 2D ARGB sprites:
 * solid vs alpha, onion-skin, export PNG for use as Thing sprites.
 *
 * Launch: java SpriteEditor
 * Or from editor: SpriteEditor.launch(parent)
 */
public class SpriteEditor extends JFrame {

    static final int MAX_SIZE = 128;
    int sprW = 32, sprH = 32;
    int zoom = 12;
    BufferedImage canvas;
    Color paintColor = new Color(0, 255, 100);
    boolean eraser = false;
    boolean showGrid = true;
    /** When true, brush writes full alpha (solid). When false, uses paintColor alpha. */
    boolean solidMode = true;
    int brushSize = 1;

    CanvasPanel panel;
    JLabel status;

    public SpriteEditor(Window owner) {
        setTitle("CHROMA Sprite Editor — Solid / Alpha");
        setSize(720, 560);
        setLocationRelativeTo(owner);
        setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);

        newCanvas(32, 32);

        JPanel root = new JPanel(new BorderLayout(4, 4));
        root.setBackground(new Color(10, 10, 10));
        root.setBorder(new LineBorder(new Color(0, 255, 100), 2));

        root.add(buildToolbar(), BorderLayout.NORTH);
        panel = new CanvasPanel();
        JScrollPane scroll = new JScrollPane(panel);
        scroll.getViewport().setBackground(new Color(20, 20, 20));
        root.add(scroll, BorderLayout.CENTER);

        status = new JLabel(statusText());
        status.setFont(new Font("Monospaced", Font.BOLD, 11));
        status.setForeground(new Color(0, 255, 100));
        status.setBorder(new EmptyBorder(4, 8, 4, 8));
        root.add(status, BorderLayout.SOUTH);

        setContentPane(root);
    }

    public static void launch(Window owner) {
        new SpriteEditor(owner).setVisible(true);
    }

    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> new SpriteEditor(null).setVisible(true));
    }

    private String statusText() {
        return "  // " + sprW + "x" + sprH
                + "  |  ZOOM " + zoom
                + "  |  BRUSH " + brushSize
                + "  |  " + (solidMode ? "SOLID (opaque z-write)" : "ALPHA (soft blend)")
                + "  |  " + (eraser ? "ERASER" : "PAINT")
                + "  |  LMB paint  RMB erase  G grid";
    }

    private void newCanvas(int w, int h) {
        sprW = Math.max(8, Math.min(MAX_SIZE, w));
        sprH = Math.max(8, Math.min(MAX_SIZE, h));
        canvas = new BufferedImage(sprW, sprH, BufferedImage.TYPE_INT_ARGB);
        // transparent clear
        for (int y = 0; y < sprH; y++)
            for (int x = 0; x < sprW; x++)
                canvas.setRGB(x, y, 0x00000000);
        if (panel != null) { panel.updateSize(); panel.repaint(); }
        if (status != null) status.setText(statusText());
    }

    private JPanel buildToolbar() {
        JPanel bar = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 4));
        bar.setBackground(new Color(10, 10, 10));
        bar.add(btn("NEW 16", e -> newCanvas(16, 16)));
        bar.add(btn("NEW 32", e -> newCanvas(32, 32)));
        bar.add(btn("NEW 64", e -> newCanvas(64, 64)));
        bar.add(btn("LOAD", e -> loadPng()));
        bar.add(btn("SAVE PNG", e -> savePng()));
        bar.add(btn("COLOR", e -> {
            Color c = JColorChooser.showDialog(this, "Paint Color", paintColor);
            if (c != null) paintColor = c;
        }));
        bar.add(btn("SOLID/ALPHA", e -> {
            solidMode = !solidMode;
            status.setText(statusText());
        }));
        bar.add(btn("BRUSH +", e -> { brushSize = Math.min(8, brushSize + 1); status.setText(statusText()); }));
        bar.add(btn("BRUSH -", e -> { brushSize = Math.max(1, brushSize - 1); status.setText(statusText()); }));
        bar.add(btn("ZOOM +", e -> { zoom = Math.min(24, zoom + 2); panel.updateSize(); panel.repaint(); status.setText(statusText()); }));
        bar.add(btn("ZOOM -", e -> { zoom = Math.max(4, zoom - 2); panel.updateSize(); panel.repaint(); status.setText(statusText()); }));
        bar.add(btn("CLEAR", e -> newCanvas(sprW, sprH)));
        return bar;
    }

    private JButton btn(String t, ActionListener al) {
        JButton b = new JButton(t);
        b.setFont(new Font("Monospaced", Font.BOLD, 10));
        b.setForeground(new Color(0, 255, 100));
        b.setBackground(new Color(10, 10, 10));
        b.setFocusable(false);
        b.setBorder(new LineBorder(new Color(0, 255, 100), 1));
        b.addActionListener(al);
        return b;
    }

    private void loadPng() {
        JFileChooser ch = new JFileChooser();
        ch.setFileFilter(new FileNameExtensionFilter("PNG", "png"));
        if (ch.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) return;
        try {
            BufferedImage img = ImageIO.read(ch.getSelectedFile());
            if (img == null) return;
            sprW = Math.min(MAX_SIZE, img.getWidth());
            sprH = Math.min(MAX_SIZE, img.getHeight());
            canvas = new BufferedImage(sprW, sprH, BufferedImage.TYPE_INT_ARGB);
            Graphics2D g = canvas.createGraphics();
            g.drawImage(img, 0, 0, sprW, sprH, null);
            g.dispose();
            panel.updateSize();
            panel.repaint();
            status.setText(statusText() + "  |  LOADED " + ch.getSelectedFile().getName());
        } catch (Exception ex) {
            JOptionPane.showMessageDialog(this, "Load failed: " + ex.getMessage());
        }
    }

    private void savePng() {
        JFileChooser ch = new JFileChooser();
        ch.setSelectedFile(new File("sprite.png"));
        ch.setFileFilter(new FileNameExtensionFilter("PNG", "png"));
        if (ch.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) return;
        try {
            File out = ch.getSelectedFile();
            if (!out.getName().toLowerCase().endsWith(".png"))
                out = new File(out.getParentFile(), out.getName() + ".png");
            ImageIO.write(canvas, "png", out);
            status.setText(statusText() + "  |  SAVED " + out.getName());
        } catch (Exception ex) {
            JOptionPane.showMessageDialog(this, "Save failed: " + ex.getMessage());
        }
    }

    private void paintAt(int cx, int cy, boolean erase) {
        int half = brushSize / 2;
        for (int dy = -half; dy <= half; dy++) {
            for (int dx = -half; dx <= half; dx++) {
                int x = cx + dx, y = cy + dy;
                if (x < 0 || y < 0 || x >= sprW || y >= sprH) continue;
                if (erase) {
                    canvas.setRGB(x, y, 0x00000000);
                } else if (solidMode) {
                    canvas.setRGB(x, y, 0xFF000000 | (paintColor.getRGB() & 0x00FFFFFF));
                } else {
                    // soft alpha ~160
                    int a = 160;
                    int rgb = (a << 24) | (paintColor.getRGB() & 0x00FFFFFF);
                    canvas.setRGB(x, y, rgb);
                }
            }
        }
    }

    class CanvasPanel extends JPanel {
        CanvasPanel() {
            updateSize();
            setBackground(new Color(30, 30, 30));
            MouseAdapter ma = new MouseAdapter() {
                public void mousePressed(MouseEvent e) { stroke(e); }
                public void mouseDragged(MouseEvent e) { stroke(e); }
            };
            addMouseListener(ma);
            addMouseMotionListener(ma);
            addKeyListener(new KeyAdapter() {
                public void keyPressed(KeyEvent e) {
                    if (e.getKeyCode() == KeyEvent.VK_G) { showGrid = !showGrid; repaint(); }
                }
            });
            setFocusable(true);
        }

        void updateSize() {
            setPreferredSize(new Dimension(sprW * zoom + 4, sprH * zoom + 4));
            revalidate();
        }

        void stroke(MouseEvent e) {
            int cx = e.getX() / zoom, cy = e.getY() / zoom;
            paintAt(cx, cy, SwingUtilities.isRightMouseButton(e) || eraser);
            repaint();
        }

        @Override
        protected void paintComponent(Graphics g) {
            super.paintComponent(g);
            // checkerboard for transparency
            for (int y = 0; y < sprH; y++) {
                for (int x = 0; x < sprW; x++) {
                    boolean light = ((x + y) & 1) == 0;
                    g.setColor(light ? new Color(50, 50, 50) : new Color(35, 35, 35));
                    g.fillRect(x * zoom, y * zoom, zoom, zoom);
                }
            }
            g.drawImage(canvas, 0, 0, sprW * zoom, sprH * zoom, null);
            if (showGrid) {
                g.setColor(new Color(0, 255, 100, 40));
                for (int x = 0; x <= sprW; x++) g.drawLine(x * zoom, 0, x * zoom, sprH * zoom);
                for (int y = 0; y <= sprH; y++) g.drawLine(0, y * zoom, sprW * zoom, y * zoom);
            }
        }
    }
}
