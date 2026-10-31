import javax.imageio.ImageIO; import java.io.*; import java.awt.image.BufferedImage;
import java.awt.Graphics2D; import java.awt.RenderingHints; import java.awt.*;
public class Locate {
  static boolean hit(int c){
    int r=(c>>16)&255,g=(c>>8)&255,b=c&255;
    int mx=Math.max(r,Math.max(g,b)), mn=Math.min(r,Math.min(g,b));
    boolean silver = mx>120 && (mx-mn)<70 && r>=g && g>=b;   // 银：亮、低饱和、暖
    boolean copper = r>90 && r-g>35 && r-b>35;               // 铜红
    return silver||copper;
  }
  public static void main(String[] a) throws Exception {
    BufferedImage im = ImageIO.read(new File(a[0]));
    int w=im.getWidth(), h=im.getHeight();
    int cell=Math.max(8, w/110);
    System.out.println("image "+w+"x"+h+"  cell="+cell);
    for(int cy=0; cy<h; cy+=cell*2){
      StringBuilder sb=new StringBuilder();
      for(int cx=0; cx<w; cx+=cell){
        int n=0,tot=0;
        for(int y=cy;y<Math.min(h,cy+cell*2);y++) for(int x=cx;x<Math.min(w,cx+cell);x++){tot++; if(hit(im.getRGB(x,y)))n++;}
        double f= tot>0? (double)n/tot : 0;
        sb.append(f>0.5?'#': f>0.3?'+': f>0.15?':': f>0.06?'.':' ');
      }
      System.out.println(sb);
    }
  }
}
