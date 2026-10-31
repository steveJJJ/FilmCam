import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.geom.Ellipse2D;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.io.File;

/**
 * 生成各密度启动图标 PNG（mipmap-mdpi..xxxhdpi）。
 * 与 res/drawable/ic_launcher_foreground.xml 同构图：柯达黄底 + 倾斜 35mm 胶片条。
 * 用法: java GenLauncherIcon.java <res 目录>
 */
public class GenLauncherIcon {

    /** 尺寸表：密度名 -> 边长(px) */
    private static final Object[][] SIZES = {
        {"mdpi", 48}, {"hdpi", 72}, {"xhdpi", 96}, {"xxhdpi", 144}, {"xxxhdpi", 192},
    };

    public static void main(String[] args) throws Exception {
        File res = new File(args[0]);
        for (Object[] row : SIZES) {
            String density = (String) row[0];
            int size = (Integer) row[1];
            File dir = new File(res, "mipmap-" + density);
            if (!dir.exists() && !dir.mkdirs()) throw new java.io.IOException("无法创建 " + dir);
            write(draw(size, false), new File(dir, "ic_launcher.png"));
            write(draw(size, true), new File(dir, "ic_launcher_round.png"));
            System.out.println("mipmap-" + density + " " + size + "px");
        }
    }

    private static void write(BufferedImage img, File f) throws Exception {
        ImageIO.write(img, "png", f);
    }

    private static BufferedImage draw(int size, boolean round) {
        BufferedImage img = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);

        // 背景：柯达黄
        g.setColor(new Color(0xF5, 0xC5, 0x18));
        if (round) {
            g.fill(new Ellipse2D.Double(0, 0, size, size));
        } else {
            double r = size * 0.22; // 圆角方形，贴近各 launcher 的 squircle
            g.fill(new RoundRectangle2D.Double(0, 0, size, size, r * 2, r * 2));
        }

        // 胶片条（与 vector 一致：x 20..88 / y 42..66，viewBox 108）
        double s = size / 108.0;
        Graphics2D g2 = (Graphics2D) g.create();
        g2.rotate(Math.toRadians(-10), size / 2.0, size / 2.0);
        g2.setColor(new Color(0x17, 0x17, 0x17));
        g2.fill(new RoundRectangle2D.Double(20 * s, 42 * s, 68 * s, 24 * s, 1.5 * s, 1.5 * s));

        g2.setColor(new Color(0xF5, 0xC5, 0x18));
        // 上下齿孔
        for (int i = 0; i < 7; i++) {
            double x = (24 + i * 9) * s;
            g2.fill(new RoundRectangle2D.Double(x, 44 * s, 5 * s, 4 * s, 0.8 * s, 0.8 * s));
            g2.fill(new RoundRectangle2D.Double(x, 60 * s, 5 * s, 4 * s, 0.8 * s, 0.8 * s));
        }
        // 画格
        for (int i = 0; i < 6; i++) {
            double x = (24 + i * 11) * s;
            g2.fill(new RoundRectangle2D.Double(x, 50 * s, 7 * s, 8 * s, 0.6 * s, 0.6 * s));
        }
        g2.dispose();
        g.dispose();
        return img;
    }
}
