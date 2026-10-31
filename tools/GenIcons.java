import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.List;
import java.util.ArrayList;

/** One-off generator for film roll label icons (1024x1024 PNG). */
public class GenIcons {
    record Roll(String file, String brand, String body, String l1, String l2) {}

    static Font blackFont, boldFont;

    public static void main(String[] args) throws Exception {
        File ariblk = new File("C:/Windows/Fonts/ariblk.ttf");
        File arialbd = new File("C:/Windows/Fonts/arialbd.ttf");
        blackFont = Font.createFont(Font.TRUETYPE_FONT, ariblk);
        boldFont = Font.createFont(Font.TRUETYPE_FONT, arialbd);

        String outDir = args.length > 0 ? args[0] : ".";

        List<Roll> rolls = new ArrayList<>();
        rolls.add(new Roll("ic_film_c200.png",          "fuji",  "#2E8B57", "C200",          ""));
        rolls.add(new Roll("ic_film_acros100.png",      "fuji",  "#1A1A1A", "NEOPAN ACROS",  "100 II"));
        rolls.add(new Roll("ic_film_pro400h.png",       "fuji",  "#7FCDBB", "PRO",           "400H"));
        rolls.add(new Roll("ic_film_provia100f.png",    "fuji",  "#1E5AA8", "PROVIA",        "100F"));
        rolls.add(new Roll("ic_film_velvia100.png",     "fuji",  "#00695C", "VELVIA",        "100"));
        rolls.add(new Roll("ic_film_ektachrome100.png", "kodak", "#24425C", "EKTACHROME",    "100"));
        rolls.add(new Roll("ic_film_portra160.png",     "kodak", "#7B5FC7", "PORTRA",        "160"));
        rolls.add(new Roll("ic_film_portra800.png",     "kodak", "#5A3FA0", "PORTRA",        "800"));
        rolls.add(new Roll("ic_film_portra800p1.png",   "kodak", "#4A3390", "PORTRA 800",    "PUSH +1"));
        rolls.add(new Roll("ic_film_portra800p2.png",   "kodak", "#3B2A80", "PORTRA 800",    "PUSH +2"));
        rolls.add(new Roll("ic_film_tmax100.png",       "kodak", "#2B2B2B", "T-MAX",         "100"));
        rolls.add(new Roll("ic_film_ultramax400.png",   "kodak", "#C0392B", "ULTRAMAX",      "400"));
        rolls.add(new Roll("ic_film_verita200d.png",    "kodak", "#1A1F2E", "VERITA",        "200D"));
        rolls.add(new Roll("ic_film_vision3_50d.png",   "kodak", "#14181F", "VISION3",       "50D"));
        rolls.add(new Roll("ic_film_vision3_200t.png",  "kodak", "#14181F", "VISION3",       "200T"));
        rolls.add(new Roll("ic_film_vision3_500t.png",  "kodak", "#14181F", "VISION3",       "500T"));

        for (Roll r : rolls) {
            BufferedImage img = new BufferedImage(1024, 1024, BufferedImage.TYPE_INT_RGB);
            Graphics2D g = img.createGraphics();
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_LCD_HRGB);

            int brandH = 300;
            if (r.brand().equals("kodak")) {
                g.setColor(Color.decode("#FFDE00"));
                g.fillRect(0, 0, 1024, brandH);
                Font kf = blackFont.deriveFont(Font.PLAIN, 150f);
                g.setColor(Color.decode("#D40000"));
                drawCentered(g, "Kodak", kf, 1024 / 2, brandH / 2 - 20);
            } else {
                g.setColor(Color.decode("#007B3E"));
                g.fillRect(0, 0, 1024, brandH);
                Font kf = blackFont.deriveFont(Font.PLAIN, 120f);
                g.setColor(Color.WHITE);
                drawCentered(g, "FUJIFILM", kf, 1024 / 2, brandH / 2 - 10);
            }

            g.setColor(Color.decode(r.body()));
            g.fillRect(0, brandH, 1024, 1024 - brandH);
            g.setColor(Color.WHITE);

            if (r.l2().isEmpty()) {
                Font f1 = blackFont.deriveFont(Font.PLAIN, 190f);
                int size = 190;
                Rectangle s1 = measure(g, r.l1(), f1);
                while (s1.width > 940 && size > 40) {
                    size -= 10;
                    f1 = blackFont.deriveFont(Font.PLAIN, (float) size);
                    s1 = measure(g, r.l1(), f1);
                }
                int y = brandH + (1024 - brandH - s1.height) / 2;
                drawCentered(g, r.l1(), f1, 1024 / 2, y + s1.height / 2);
            } else {
                Font f1 = blackFont.deriveFont(Font.PLAIN, 130f);
                int size1 = 130;
                Rectangle s1 = measure(g, r.l1(), f1);
                while (s1.width > 940 && size1 > 40) {
                    size1 -= 8;
                    f1 = blackFont.deriveFont(Font.PLAIN, (float) size1);
                    s1 = measure(g, r.l1(), f1);
                }
                drawCentered(g, r.l1(), f1, 1024 / 2, brandH + 60 + s1.height / 2);

                Font f2 = blackFont.deriveFont(Font.PLAIN, 230f);
                int size2 = 230;
                Rectangle s2 = measure(g, r.l2(), f2);
                while (s2.width > 940 && size2 > 40) {
                    size2 -= 10;
                    f2 = blackFont.deriveFont(Font.PLAIN, (float) size2);
                    s2 = measure(g, r.l2(), f2);
                }
                drawCentered(g, r.l2(), f2, 1024 / 2, brandH + 60 + s1.height + 30 + s2.height / 2);
            }
            g.dispose();
            File out = new File(outDir, r.file());
            ImageIO.write(img, "png", out);
            System.out.println(r.file() + "  " + out.length() / 1024 + "KB");
        }
    }

    static void drawCentered(Graphics2D g, String text, Font f, int cx, int cy) {
        FontMetrics fm = g.getFontMetrics(f);
        int w = fm.stringWidth(text);
        int asc = fm.getAscent();
        int desc = fm.getDescent();
        int x = cx - w / 2;
        int y = cy - (asc + desc) / 2 + asc;
        g.setFont(f);
        g.drawString(text, x, y);
    }

    static Rectangle measure(Graphics2D g, String text, Font f) {
        FontMetrics fm = g.getFontMetrics(f);
        return new Rectangle(0, 0, fm.stringWidth(text), fm.getAscent() + fm.getDescent());
    }
}
