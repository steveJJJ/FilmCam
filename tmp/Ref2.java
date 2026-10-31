import javax.imageio.ImageIO; import java.io.*; import java.awt.image.BufferedImage;
public class Ref2 {
  static int lum(int p){return (int)(0.299*((p>>16)&255)+0.587*((p>>8)&255)+0.114*(p&255));}
  public static void main(String[] a) throws Exception {
    BufferedImage im=ImageIO.read(new File(a[0]));
    int x0=Integer.parseInt(a[1]),y0=Integer.parseInt(a[2]),x1=Integer.parseInt(a[3]),y1=Integer.parseInt(a[4]);
    int cols=Integer.parseInt(a[5]);
    double sx=(double)(x1-x0)/cols; double sy=sx*2.0;
    for(double y=y0;y<y1;y+=sy){
      StringBuilder sb=new StringBuilder();
      for(double x=x0;x<x1;x+=sx){
        int n=0,d=0,r=0;
        for(int yy=(int)y; yy<Math.min(y1,(int)(y+sy)); yy++) for(int xx=(int)x; xx<Math.min(x1,(int)(x+sx)); xx++){
          n++; int p=im.getRGB(xx,yy); int c=(p>>16)&255,g=(p>>8)&255,b=p&255;
          if(c>120&&c-g>50&&c-b>50) r++;
          else if(lum(p)<110) d++;
        }
        sb.append(r>n*0.3?'R': d>n*0.45?'#': d>n*0.15?'+':' ');
      }
      System.out.println(sb);
    }
  }
}
