import javax.imageio.ImageIO; import java.io.*; import java.awt.image.BufferedImage;
public class Ref {
  static int lum(int p){return (int)(0.299*((p>>16)&255)+0.587*((p>>8)&255)+0.114*(p&255));}
  public static void main(String[] a) throws Exception {
    BufferedImage im=ImageIO.read(new File(a[0])); int w=im.getWidth(),h=im.getHeight();
    int rMinX=w,rMaxX=-1,rMinY=h,rMaxY=-1; long rs=0,gs=0,bs=0,rn=0;
    for(int y=0;y<h;y++)for(int x=0;x<w;x++){
      int p=im.getRGB(x,y); int r=(p>>16)&255,g=(p>>8)&255,b=p&255;
      if(r>120&&r-g>50&&r-b>50){ if(x<rMinX)rMinX=x; if(x>rMaxX)rMaxX=x; if(y<rMinY)rMinY=y; if(y>rMaxY)rMaxY=y; rs+=r;gs+=g;bs+=b;rn++; }
    }
    System.out.printf("RED bbox x[%d..%d] y[%d..%d] avgColor=#%02X%02X%02X n=%d%n", rMinX,rMaxX,rMinY,rMaxY, rs/rn,gs/rn,bs/rn, rn);
    // 暗色（LCD 面板）在左半
    int dMinX=w,dMaxX=-1,dMinY=h,dMaxY=-1;
    for(int y=0;y<h;y++)for(int x=0;x<w/2;x++){
      if(lum(im.getRGB(x,y))<70){ if(x<dMinX)dMinX=x; if(x>dMaxX)dMaxX=x; if(y<dMinY)dMinY=y; if(y>dMaxY)dMaxY=y; }
    }
    System.out.printf("DARK(左半) bbox x[%d..%d] y[%d..%d]%n", dMinX,dMaxX,dMinY,dMaxY);
  }
}
