import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.File;
import javax.imageio.ImageIO;

public class Ascii {
    static final String RAMP = " .:-=+*#%@";
    public static void main(String[] args) throws Exception {
        BufferedImage src = ImageIO.read(new File(args[0]));
        int x = Integer.parseInt(args[1]), y = Integer.parseInt(args[2]);
        int w = Integer.parseInt(args[3]), h = Integer.parseInt(args[4]);
        int cols = args.length > 5 ? Integer.parseInt(args[5]) : 76;
        BufferedImage crop = src.getSubimage(x, y, w, h);
        int rows = Math.max(4, cols * h / w / 2);
        BufferedImage small = new BufferedImage(cols, rows, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = small.createGraphics();
        g.drawImage(crop, 0, 0, cols, rows, null); g.dispose();
        for (int r = 0; r < rows; r++) {
            StringBuilder sb = new StringBuilder();
            for (int c = 0; c < cols; c++) {
                int rgb = small.getRGB(c, r);
                double lum = (0.299*((rgb>>16)&0xFF) + 0.587*((rgb>>8)&0xFF) + 0.114*(rgb&0xFF)) / 255.0;
                int i = (int) Math.min(RAMP.length()-1, Math.max(0, Math.round(lum * (RAMP.length()-1))));
                sb.append(RAMP.charAt(i));
            }
            System.out.println(sb);
        }
    }
}
