import java.awt.image.BufferedImage; import java.io.File; import javax.imageio.ImageIO;
/** 扫描截图里非黑（有画面）区域的精确边界 */
public class Scan {
    public static void main(String[] a) throws Exception {
        BufferedImage im = ImageIO.read(new File(a[0]));
        int row = Integer.parseInt(a[1]), col = Integer.parseInt(a[2]);
        int l=-1,r=-1,t=-1,b=-1;
        for (int x=0;x<im.getWidth();x++){int c=im.getRGB(x,row); if(lum(c)>12){if(l<0)l=x; r=x;}}
        for (int y=0;y<im.getHeight();y++){int c=im.getRGB(col,y); if(lum(c)>12){if(t<0)t=y; b=y;}}
        System.out.println("image="+im.getWidth()+"x"+im.getHeight());
        System.out.println("row y="+row+" : non-black x from "+l+" to "+r+"  width="+(r-l+1));
        System.out.println("col x="+col+" : non-black y from "+t+" to "+b+"  height="+(b-t+1));
        if (r>l && b>t) System.out.printf("aspect=%.3f%n", (r-l+1.0)/(b-t+1.0));
    }
    static double lum(int c){return 0.299*((c>>16)&0xFF)+0.587*((c>>8)&0xFF)+0.114*(c&0xFF);}
}
