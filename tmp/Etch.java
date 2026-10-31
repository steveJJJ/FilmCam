import javax.imageio.ImageIO; import java.io.*; import java.awt.image.BufferedImage;
public class Etch {
  static int lum(int p){return (int)(0.299*((p>>16)&255)+0.587*((p>>8)&255)+0.114*(p&255));}
  public static void main(String[] a) throws Exception {
    BufferedImage im=ImageIO.read(new File(a[0])); int w=im.getWidth(),h=im.getHeight();
    int R=7; // 局部窗口半径
    int minX=w,maxX=-1,minY=h,maxY=-1;
    boolean[][] m=new boolean[h][w];
    for(int y=R;y<h-R;y++) for(int x=R;x<w-R;x++){
      int v=lum(im.getRGB(x,y)); if(v<85) continue;
      int mn=255; for(int dy=-R;dy<=R;dy+=2) for(int dx=-R;dx<=R;dx+=2){ int u=lum(im.getRGB(x+dx,y+dy)); if(u<mn)mn=u;}
      if(mn<55){ m[y][x]=true; if(x<minX)minX=x; if(x>maxX)maxX=x; if(y<minY)minY=y; if(y>maxY)maxY=y; }
    }
    System.out.println("etched-text bbox x["+minX+".."+maxX+"] y["+minY+".."+maxY+"] of "+w+"x"+h);
    if(maxX<0) return;
    // 输出 bbox 内 ASCII（银=#, 铜/红=R）
    int cols=120; double sx=(double)(maxX-minX+1)/cols; double sy=sx*2.0;
    for(double y=minY;y<=maxY;y+=sy){
      StringBuilder sb=new StringBuilder();
      for(double x=minX;x<=maxX;x+=sx){
        int cnt=0,n=0,red=0;
        for(int yy=(int)y; yy<Math.min(h,(int)(y+sy)); yy++) for(int xx=(int)x; xx<Math.min(w,(int)(x+sx)); xx++){
          n++; if(m[yy][xx]){cnt++; int p=im.getRGB(xx,yy); int r=(p>>16)&255,g=(p>>8)&255,b=p&255; if(r-g>30&&r-b>30)red++;}
        }
        double f=(double)cnt/n;
        sb.append(f<0.12?' ': (red>cnt*0.5?'R': f>0.45?'#': f>0.2?'+':'.'));
      }
      System.out.println(sb);
    }
  }
}
