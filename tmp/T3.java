import javax.imageio.ImageIO; import java.io.*; import java.awt.image.BufferedImage;
public class T3 {
  public static void main(String[] a) throws Exception {
    BufferedImage im=ImageIO.read(new File("main_screen.png"));
    int x0=560,y0=1852,x1=678,y1=1912;
    int cols=110; double sx=(double)(x1-x0)/cols; double sy=sx*1.8;
    for(double y=y0;y<y1;y+=sy){
      StringBuilder sb=new StringBuilder();
      for(double x=x0;x<x1;x+=sx){
        int n=0,r=0;
        for(int yy=(int)y; yy<Math.min(y1,(int)(y+sy)); yy++) for(int xx=(int)x; xx<Math.min(x1,(int)(x+sx)); xx++){
          n++; int p=im.getRGB(xx,yy); int c=(p>>16)&255,g=(p>>8)&255,b=p&255;
          if(c>140&&c-g>60&&c-b>60) r++;
        }
        sb.append(r>n*0.4?'#': r>n*0.12?'+':' ');
      }
      System.out.println(sb);
    }
  }
}
