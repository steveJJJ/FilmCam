import javax.imageio.ImageIO; import java.io.*; import java.awt.image.BufferedImage;
public class Lcd {
  static int lum(int p){return (int)(0.299*((p>>16)&255)+0.587*((p>>8)&255)+0.114*(p&255));}
  public static void main(String[] a) throws Exception {
    BufferedImage im=ImageIO.read(new File("main_screen.png"));
    int x0=46,y0=168,x1=490,y1=328;
    int cols=150; double sx=(double)(x1-x0)/cols; double sy=sx*2.0;
    for(double y=y0;y<y1;y+=sy){
      StringBuilder sb=new StringBuilder();
      for(double x=x0;x<x1;x+=sx){
        int n=0,d=0;
        for(int yy=(int)y; yy<Math.min(y1,(int)(y+sy)); yy++) for(int xx=(int)x; xx<Math.min(x1,(int)(x+sx)); xx++){
          n++; if(lum(im.getRGB(xx,yy))<110) d++;
        }
        sb.append(d>n*0.45?'#': d>n*0.15?'+':' ');
      }
      System.out.println(sb);
    }
  }
}
