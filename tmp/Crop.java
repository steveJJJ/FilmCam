import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.File;
import javax.imageio.ImageIO;

public class Crop {
    public static void main(String[] a) throws Exception {
        BufferedImage src = ImageIO.read(new File(a[0]));
        int x = Integer.parseInt(a[1]), y = Integer.parseInt(a[2]);
        int w = Integer.parseInt(a[3]), h = Integer.parseInt(a[4]);
        int scale = Integer.parseInt(a[5]);
        BufferedImage out = new BufferedImage(w * scale, h * scale, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = out.createGraphics();
        g.drawImage(src.getSubimage(x, y, w, h), 0, 0, w * scale, h * scale, null);
        g.dispose();
        ImageIO.write(out, "png", new File(a[6]));
        System.out.println("saved " + a[6]);
    }
}
