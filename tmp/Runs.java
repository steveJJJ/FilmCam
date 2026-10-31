import javax.imageio.ImageIO; import java.io.*; import java.awt.image.BufferedImage;
public class Runs {
  public static void main(String[] a) throws Exception {
    BufferedImage im=ImageIO.read(new File(a[0]));
    int x0=280,x1=320,y0=50,y1=80;
    for(int y=y0;y<y1;y++){
      StringBuilder sb=new StringBuilder(); int run=-1;
      for(int x=x0;x<x1;x++){
        int p=im.getRGB(x,y); int r=(p>>16)&255,g=(p>>8)&255,b=p&255;
        boolean red = r>110 && r-g>40 && r-b>40;
        if(red && run<0){run=x;}
        if(!red && run>=0){ sb.append("[").append(run-x0).append("-").append(x-1-x0).append("]"); run=-1; }
      }
      if(run>=0) sb.append("[").append(run-x0).append("-").append(x1-1-x0).append("]");
      if(sb.length()>0) System.out.printf("y=%d: %s%n", y, sb);
    }
  }
}
