import javax.imageio.ImageIO; import java.io.*; import java.awt.image.BufferedImage;
public class Measure {
  public static void main(String[] a) throws Exception {
    BufferedImage im=ImageIO.read(new File("main_screen.png")); int w=im.getWidth(),h=im.getHeight();
    int y0=h*2/3, y1=h-1;
    int minX=w,maxX=-1,minY=h,maxY=-1,ns=0,nc=0;
    for(int y=y0;y<=y1;y++) for(int x=0;x<w;x++){
      int p=im.getRGB(x,y); int r=(p>>16)&255,g=(p>>8)&255,b=p&255;
      boolean silver = Math.abs(r-0xC9)<28 && Math.abs(g-0xC5)<28 && Math.abs(b-0xBC)<34;
      boolean copper = Math.abs(r-0xA3)<45 && Math.abs(g-0x4E)<45 && Math.abs(b-0x32)<45;
      if(silver){ns++; if(x<minX)minX=x; if(x>maxX)maxX=x; if(y<minY)minY=y; if(y>maxY)maxY=y;}
      if(copper)nc++;
    }
    System.out.printf("screen %dx%d  下1/3扫描: silver=%d copper=%d%n", w,h,ns,nc);
    if(maxX>0) System.out.printf("铭牌 bbox x[%d..%d] y[%d..%d]  中心X=%d(屏中=%d) 高=%dpx%n",
      minX,maxX,minY,maxY,(minX+maxX)/2,w/2,maxY-minY);
  }
}
