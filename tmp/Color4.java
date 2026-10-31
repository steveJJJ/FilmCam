import javax.imageio.ImageIO; import java.io.*; import java.awt.image.BufferedImage;
import java.util.*;
public class Color4 {
  public static void main(String[] a) throws Exception {
    BufferedImage im=ImageIO.read(new File(a[0]));
    List<int[]> px=new ArrayList<>();
    for(int y=40;y<95;y++)for(int x=255;x<320;x++){
      int p=im.getRGB(x,y); int r=(p>>16)&255,g=(p>>8)&255,b=p&255;
      if(r>140&&r-g>70&&r-b>70) px.add(new int[]{r,g,b,r-g});
    }
    px.sort((u,v)->v[3]-u[3]);
    int k=Math.min(px.size(), 12);
    long r=0,g=0,b=0;
    for(int i=0;i<k;i++){r+=px.get(i)[0];g+=px.get(i)[1];b+=px.get(i)[2];}
    System.out.printf("top-sat red avg=#%02X%02X%02X (n=%d, top %d)%n", r/k,g/k,b/k,px.size(),k);
  }
}
