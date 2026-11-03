import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.*;

/**
 * P2 参照样片定量分析。
 *
 * 每张图算：
 *  - 全图平均 RGB、亮度均值/标准差（反差）、p1/p50/p99 亮度（黑位/白位）
 *  - R-B（暖冷轴，对应引擎 y_filter_shift 方向）
 *  - G-(R+B)/2（绿-洋红轴，对应 m_filter_shift 方向）
 *  - 饱和度（HSV S 均值，对应 dir_couplers 色彩浓度）
 *  - 暗部色偏（最暗 10% 像素的 R-B / G 偏移）——扫描仪签名的所在
 *  - 高光色偏（最亮 10% 像素的 R-B / G 偏移）
 * 按胶卷聚合输出均值，另存逐图明细 CSV。
 *
 * 用法: java RefAnalyze <imagesDir>
 */
public class RefAnalyze {
    public static void main(String[] args) throws Exception {
        File dir = new File(args[0]);
        File[] files = dir.listFiles((d, n) -> n.toLowerCase().endsWith(".jpg"));
        if (files == null || files.length == 0) { System.out.println("no jpg"); return; }
        Arrays.sort(files);

        Map<String, List<double[]>> byStock = new TreeMap<>();
        List<String> csv = new ArrayList<>();
        csv.add("file,w,h,n,avgR,avgG,avgB,warmthRB,greenMg,sat,lumMean,lumStd,lumP1,lumP50,lumP99,"
                + "shWarmRB,shGreenMg,hiWarmRB,hiGreenMg");

        for (File f : files) {
            BufferedImage img;
            try { img = ImageIO.read(f); } catch (Exception e) { System.out.println("SKIP " + f.getName()); continue; }
            if (img == null) { System.out.println("SKIP " + f.getName()); continue; }
            int w = img.getWidth(), h = img.getHeight();
            // 中心 80% 区域，避开边框/水印
            int x0 = (int)(w*0.10), y0 = (int)(h*0.10), x1 = (int)(w*0.90), y1 = (int)(h*0.90);
            List<int[]> px = new ArrayList<>((x1-x0)*(y1-y0)/16);
            for (int y = y0; y < y1; y += 2)
                for (int x = x0; x < x1; x += 2) {
                    int c = img.getRGB(x, y);
                    px.add(new int[]{ (c>>16)&255, (c>>8)&255, c&255 });
                }
            int n = px.size();
            // 亮度直方图（0-255）
            int[] hist = new int[256];
            for (int[] p : px) {
                int l = (int)(0.299*p[0] + 0.587*p[1] + 0.114*p[2]);
                hist[l]++;
            }
            int p1t = n/10, p50t = n/2, p99t = (int)(n*0.99), p10lo = n/10;
            int acc = 0, p1 = 0, p50 = 0, p99 = 0, loTh = 0, hiTh = 0;
            boolean p10 = false;
            for (int i = 0; i < 256; i++) {
                acc += hist[i];
                if (!p10 && acc >= p10lo) { loTh = i; p10 = true; }
                if (p1 == 0 && acc >= p1t) p1 = i;
                if (p50 == 0 && acc >= p50t) p50 = i;
                if (p99 == 0 && acc >= p99t) { p99 = i; break; }
            }
            hiTh = p99; // 最亮 10% 近似用 p99 以上
            double sumR = 0, sumG = 0, sumB = 0, sumL = 0, sumL2 = 0, sumS = 0;
            double shR = 0, shG = 0, shB = 0, shN = 0, hiR = 0, hiG = 0, hiB = 0, hiN = 0;
            for (int[] p : px) {
                int l = (int)(0.299*p[0] + 0.587*p[1] + 0.114*p[2]);
                sumR += p[0]; sumG += p[1]; sumB += p[2];
                sumL += l; sumL2 += (double)l*l;
                float[] hsv = java.awt.Color.RGBtoHSB(p[0], p[1], p[2], null);
                sumS += hsv[1];
                if (l <= loTh) { shR += p[0]; shG += p[1]; shB += p[2]; shN++; }
                if (l >= hiTh) { hiR += p[0]; hiG += p[1]; hiB += p[2]; hiN++; }
            }
            double aR = sumR/n, aG = sumG/n, aB = sumB/n;
            double lumMean = sumL/n, lumVar = sumL2/n - lumMean*lumMean;
            double shWarm = shN > 0 ? (shR-shB)/shN : 0;
            double shGreen = shN > 0 ? shG/shN - (shR/shN+shB/shN)/2 : 0;
            double hiWarm = hiN > 0 ? (hiR-hiB)/hiN : 0;
            double hiGreen = hiN > 0 ? hiG/hiN - (hiR/hiN+hiB/hiN)/2 : 0;
            double warmth = aR - aB, greenMg = aG - (aR+aB)/2, sat = sumS/n;
            String stock = f.getName().replaceAll("_.*", "");
            byStock.computeIfAbsent(stock, k -> new ArrayList<>()).add(
                new double[]{warmth, greenMg, sat, lumMean, Math.sqrt(Math.max(0,lumVar)),
                             p1, p50, p99, shWarm, shGreen, hiWarm, hiGreen});
            csv.add(String.format(Locale.US, "%s,%d,%d,%d,%.1f,%.1f,%.1f,%.2f,%.2f,%.3f,%.1f,%.1f,%d,%d,%d,%.2f,%.2f,%.2f,%.2f",
                f.getName(), w, h, n, aR, aG, aB, warmth, greenMg, sat, lumMean, Math.sqrt(Math.max(0,lumVar)),
                p1, p50, p99, shWarm, shGreen, hiWarm, hiGreen));
        }
        java.nio.file.Files.write(new File(dir, "per_image.csv").toPath(), csv);
        // 按胶卷聚合
        System.out.println("stock,n,warmthRB,greenMg,sat,lumMean,lumStd,p1,p99,shWarm,shGreen,hiWarm,hiGreen");
        for (var e : byStock.entrySet()) {
            List<double[]> v = e.getValue();
            double[] m = new double[12], s = new double[12];
            for (double[] d : v) for (int i = 0; i < 12; i++) m[i] += d[i]/v.size();
            for (double[] d : v) for (int i = 0; i < 12; i++) s[i] += (d[i]-m[i])*(d[i]-m[i])/v.size();
            StringBuilder sb = new StringBuilder(e.getKey() + "," + v.size());
            for (int i = 0; i < 12; i++)
                sb.append(String.format(Locale.US, ",%.2f+-%.2f", m[i], Math.sqrt(s[i])));
            System.out.println(sb);
        }
    }
}
