import javax.imageio.ImageIO; import java.io.*; import java.awt.image.BufferedImage;
public class Scan {
  public static void main(String[] a) throws Exception {
    BufferedImage im = ImageIO.read(new File("main_screen.png"));
    int w=im.getWidth(), h=im.getHeight();
    int minY=Integer.MAX_VALUE,maxY=-1,minX=Integer.MAX_VALUE,maxX=-1;
    // silver: light warm gray; copper: reddish
    for(int y=0;y<h/2;y++)for(int x=0;x<w;x++){
      int c=im.getRGB(x,y); int r=(c>>16)&255,g=(c>>8)&255,b=c&255;
      boolean silver = r>150&&g>140&&b>120&&Math.abs(r-g)<40&&r-b>15&&r-b<90;
      boolean copper = r>120&&r<200&&g>50&&g<110&&b<90&&r-g>50;
      if(silver||copper){ if(y<minY)minY=y; if(y>maxY)maxY=y; if(x<minX)minX=x; if(x>maxX)maxX=x; }
    }
    System.out.printf("screen %dx%d  wordmark bbox x[%d..%d] y[%d..%d] centerX=%.0f (screen center=%d)%n",
      w,h,minX,maxX,minY,maxY,(minX+maxX)/2.0,w/2);
  }
}
