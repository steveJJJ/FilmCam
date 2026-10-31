import javax.imageio.ImageIO; import java.io.*; import java.awt.image.BufferedImage;
public class Crop {
  public static void main(String[] a) throws Exception {
    BufferedImage im=ImageIO.read(new File("main_screen.png"));
    int x0=420,y0=1852,x1=660,y1=1914;
    double sx=(double)(x1-x0)/150; double sy=sx*2.0;
    for(double y=y0;y<y1;y+=sy){
      StringBuilder sb=new StringBuilder();
      for(double x=x0;x<x1;x+=sx){
        int n=0,sv=0,cp=0;
        for(int yy=(int)y; yy<(int)(y+sy); yy++) for(int xx=(int)x; xx<(int)(x+sx); xx++){
          n++; int p=im.getRGB(xx,yy); int r=(p>>16)&255,g=(p>>8)&255,b=p&255;
          if(Math.abs(r-0xC9)<45&&Math.abs(g-0xC5)<45&&Math.abs(b-0xBC)<55&&r>=g&&g>=b) sv++;
          else if(Math.abs(r-0xA3)<60&&Math.abs(g-0x4E)<60&&Math.abs(b-0x32)<60&&r-g>25) cp++;
        }
        sb.append(sv>n*0.35?'#': cp>n*0.30?'R': (sv+cp)>n*0.12?'+':' ');
      }
      System.out.println(sb);
    }
  }
}
