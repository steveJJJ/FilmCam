import javax.imageio.ImageIO; import java.io.*; import java.awt.image.BufferedImage;
import java.util.*;
public class Color5 {
  public static void main(String[] a) throws Exception {
    BufferedImage im=ImageIO.read(new File(a[0]));
    List<int[]> px=new ArrayList<>();
    for(int y=40;y<95;y++)for(int x=255;x<320;x++){
      int p=im.getRGB(x,y); int r=(p>>16)&255,g=(p>>8)&255,b=p&255;
      if(r-g>20&&r-b>20) px.add(new int[]{r,g,b,r-g});
    }
    px.sort((u,v)->v[3]-u[3]);
    int k=Math.min(px.size(), 15);
    long r=0,g=0,b=0;
    for(int i=0;i<k;i++){ r+=px.get(i)[0]; g+=px.get(i)[1]; b+=px.get(i)[2]; }
    if(k==0){System.out.println("none");return;}
    System.out.printf("red avg=#%02X%02X%02X (total %d, top %d)%n", r/k,g/k,b/k,px.size(),k);
    for(int i=0;i<Math.min(8,px.size());i++) System.out.printf("  #%02X%02X%02X sat=%d%n", px.get(i)[0],px.get(i)[1],px.get(i)[2],px.get(i)[3]);
  }
}
