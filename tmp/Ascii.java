import javax.imageio.ImageIO; import java.io.*; import java.awt.image.BufferedImage;
public class Ascii {
  public static void main(String[] a) throws Exception {
    BufferedImage im = ImageIO.read(new File(a[0]));
    int x0=0,y0=0,w=im.getWidth(),h=im.getHeight();
    if(a.length>=6){x0=Integer.parseInt(a[1]);y0=Integer.parseInt(a[2]);w=Integer.parseInt(a[3]);h=Integer.parseInt(a[4]);}
    int cols=Integer.parseInt(a[a.length-1]);
    double sx=(double)w/cols; double sy=sx*2.1;
    int rows=(int)(h/sy);
    char[] ramp=" .:-=+*#%@".toCharArray();
    System.out.println("region ["+x0+","+y0+" "+w+"x"+h+"] cols="+cols+" rows="+rows);
    for(int r=0;r<rows;r++){
      StringBuilder sb=new StringBuilder();
      for(int c=0;c<cols;c++){
        double sum=0; int n=0;
        for(int y=(int)(y0+r*sy); y<Math.min(y0+h,(int)(y0+(r+1)*sy)); y++)
          for(int x=(int)(x0+c*sx); x<Math.min(x0+w,(int)(x0+(c+1)*sx)); x++){
            int p=im.getRGB(x,y); int v=(int)(0.299*((p>>16)&255)+0.587*((p>>8)&255)+0.114*(p&255)); sum+=v; n++;
          }
        double v=n>0?sum/n:0;
        sb.append(ramp[(int)Math.min(9, v/25.6)]);
      }
      System.out.println(sb);
    }
  }
}
