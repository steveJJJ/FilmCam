import javax.imageio.ImageIO; import java.io.*; import java.awt.image.BufferedImage;
public class Body {
  static int lum(int p){return (int)(0.299*((p>>16)&255)+0.587*((p>>8)&255)+0.114*(p&255));}
  public static void main(String[] a) throws Exception {
    BufferedImage im=ImageIO.read(new File(a[0])); int w=im.getWidth(),h=im.getHeight();
    // 逐行统计暗像素比例，找机身（黑）行区间
    System.out.println("row dark-fraction (每 8px):");
    for(int y=0;y<h;y+=8){
      int d=0,n=0; for(int x=0;x<w;x++){n++; if(lum(im.getRGB(x,y))<70)d++;}
      double f=(double)d/n;
      System.out.printf("%4d %s%.0f%%%n", y, f>0?"":"", f*100);
    }
  }
}
