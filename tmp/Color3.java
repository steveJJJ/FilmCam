import javax.imageio.ImageIO; import java.io.*; import java.awt.image.BufferedImage;
public class Color3 {
  public static void main(String[] a) throws Exception {
    BufferedImage im=ImageIO.read(new File(a[0]));
    long rs=0,gs=0,bs=0,n=0;
    for(int y=40;y<95;y++)for(int x=255;x<320;x++){
      int p=im.getRGB(x,y); int r=(p>>16)&255,g=(p>>8)&255,b=p&255;
      if(r>120&&r-g>50&&r-b>50){rs+=r;gs+=g;bs+=b;n++;}
    }
    System.out.printf("T3 red avg=#%02X%02X%02X n=%d%n", rs/n,gs/n,bs/n,n);
  }
}
