import java.awt.image.BufferedImage; import java.io.File; import javax.imageio.ImageIO;
public class Runs {
    public static void main(String[] a) throws Exception {
        BufferedImage im = ImageIO.read(new File(a[0]));
        int row = Integer.parseInt(a[1]);
        boolean in = false; int start = 0;
        System.out.println("row " + row + " non-black runs:");
        for (int x = 0; x < im.getWidth(); x++) {
            int c = im.getRGB(x, row);
            boolean nb = lum(c) > 6;
            if (nb && !in) { in = true; start = x; }
            if (!nb && in) { in = false; if (x - start > 30) System.out.println("   " + start + " .. " + (x-1) + "  (w=" + (x-start) + ")"); }
        }
        if (in) System.out.println("   " + start + " .. " + (im.getWidth()-1) + "  (w=" + (im.getWidth()-start) + ")");
    }
    static double lum(int c){return 0.299*((c>>16)&0xFF)+0.587*((c>>8)&0xFF)+0.114*(c&0xFF);}
}
