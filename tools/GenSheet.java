import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * 把 25 支胶卷图标拼成一览图（5 列网格 + 名称标注），输出到项目根目录。
 * 用法: java GenSheet.java <drawable目录> <输出png>
 */
public class GenSheet {
    static final String[] ROLLS = {
        "ic_film_portra400", "ic_film_ektar100", "ic_film_gold200", "ic_film_superia400",
        "ic_film_kodachrome64", "ic_film_eterna", "ic_film_trix400", "ic_film_hp5", "ic_film_cn800",
        "ic_film_portra160", "ic_film_portra800", "ic_film_portra800p1", "ic_film_portra800p2",
        "ic_film_ultramax400", "ic_film_c200", "ic_film_pro400h", "ic_film_tmax100", "ic_film_acros100",
        "ic_film_provia100f", "ic_film_velvia100", "ic_film_ektachrome100", "ic_film_verita200d",
        "ic_film_vision3_50d", "ic_film_vision3_200t", "ic_film_vision3_500t",
    };
    static final String[] NAMES = {
        "PORTRA 400", "EKTAR 100", "GOLD 200", "SUPERIA 400", "KODACHROME 64",
        "ETERNA", "TRI-X 400", "HP5 PLUS", "CN800", "PORTRA 160", "PORTRA 800",
        "PORTRA 800 PUSH 1", "PORTRA 800 PUSH 2", "ULTRAMAX 400", "C200", "PRO 400H",
        "T-MAX 100", "NEOPAN ACROS 100 II", "PROVIA 100F", "VELVIA 100",
        "EKTACHROME 100", "VERITA 200D", "VISION3 50D", "VISION3 200T", "VISION3 500T",
    };

    public static void main(String[] args) throws Exception {
        File dir = new File(args[0]);
        String out = args[1];
        int cell = 256, label = 34, cols = 5;
        int rows = (ROLLS.length + cols - 1) / cols;
        int pad = 12;
        int w = cols * cell + (cols + 1) * pad;
        int h = rows * (cell + label) + (rows + 1) * pad;
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setColor(new Color(0xEB, 0xE8, 0xE1));
        g.fillRect(0, 0, w, h);
        Font font = new Font("Consolas", Font.BOLD, 17);
        for (int i = 0; i < ROLLS.length; i++) {
            BufferedImage src = ImageIO.read(new File(dir, ROLLS[i] + ".png"));
            int col = i % cols, row = i / cols;
            int x = pad + col * (cell + pad);
            int y = pad + row * (cell + label + pad);
            g.drawImage(src, x, y, cell, cell, null);
            g.setColor(new Color(0x33, 0x33, 0x33));
            g.setFont(font);
            FontMetrics fm = g.getFontMetrics();
            String n = NAMES[i];
            g.drawString(n, x + (cell - fm.stringWidth(n)) / 2, y + cell + label - 10);
        }
        g.dispose();
        ImageIO.write(img, "png", new File(out));
        System.out.println("written " + out + " " + w + "x" + h);
    }
}
