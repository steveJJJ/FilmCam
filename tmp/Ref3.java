import javax.imageio.ImageIO; import java.io.*; import java.awt.image.BufferedImage;
public class Ref3 {
  public static void main(String[] a) throws Exception {
    BufferedImage im=ImageIO.read(new File(a[0]));
    int x0=Integer.parseInt(a[1]),y0=Integer.parseInt(a[2]),x1=Integer.parseInt(a[3]),y1=Integer.parseInt(a[4]);
    for(int y=y0;y<y1;y++){
      StringBuilder sb=new StringBuilder();
      for(int x=x0;x<x1;x++){
        int p=im.getRGB(x,y); int r=(p>>16)&255,g=(p>>8)&255,b=p&255;
        int lum=(int)(0.299*r+0.587*g+0.114*b);
        boolean red = r>110 && r-g>40 && r-b>40;
        boolean dark = lum<120 && !red;
        sb.append(red?'#': dark?'.':' ');
      }
      System.out.println(sb);
    }
  }
}
