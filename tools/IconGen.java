import javax.imageio.ImageIO;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.GradientPaint;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.Path2D;
import java.awt.geom.Rectangle2D;
import java.awt.image.BufferedImage;
import java.io.File;

/**
 * 生成 NEST 的 PWA 图标（192 / 512 / maskable 512 / apple-touch 180）。
 *
 * <p>用代码生成而不是塞几个 PNG 进仓库，是因为图标要跟着品牌色走：
 * 哪天 logo 换成别的颜色，重跑一次这个文件就行，不会出现"改了两处漏了第三处"。
 *
 * <p>运行方式（**必须显式指定 -encoding UTF-8**）：
 * <pre>
 * javac -encoding UTF-8 -d target/tools tools/IconGen.java
 * java -cp target/tools IconGen src/main/resources/static/icons
 * </pre>
 * 不要用 {@code java tools/IconGen.java} 那种单文件源码模式：它读源码时用的是
 * 平台默认字符集（中文 Windows 上是 GBK），本文件里的中文字体名与注释会被解坏，
 * 报一串看不懂的「非法字符」。
 *
 * <p>字形画不出「安」时（极少数缺中文字体的环境）会退化成画一个屋顶+门，
 * 而不是抛异常——图标生成失败不该阻塞构建。
 */
public final class IconGen {

    private static final Color BRAND_TOP = new Color(0x0A, 0x6B, 0xD0);
    private static final Color BRAND_BOTTOM = new Color(0x05, 0x3A, 0x78);

    public static void main(String[] args) throws Exception {
        File dir = new File(args.length > 0 ? args[0] : "src/main/resources/static/icons");
        if (!dir.isDirectory() && !dir.mkdirs()) {
            throw new IllegalStateException("无法创建目录：" + dir.getAbsolutePath());
        }
        write(new File(dir, "icon-192.png"), 192, false);
        write(new File(dir, "icon-512.png"), 512, false);
        write(new File(dir, "icon-maskable-512.png"), 512, true);
        write(new File(dir, "apple-touch-icon.png"), 180, true);
        System.out.println("图标已写入 " + dir.getAbsolutePath());
    }

    private static void write(File f, int size, boolean fullBleed) throws Exception {
        BufferedImage img = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);

        g.setPaint(new GradientPaint(0, 0, BRAND_TOP, size, size, BRAND_BOTTOM));
        if (fullBleed) {
            // maskable / iOS 主屏图标：整块铺满，图形留在中间 76% 的安全区里，
            // 这样系统把它裁成圆形或超椭圆时不会切到「安」字。
            g.fillRect(0, 0, size, size);
        } else {
            int r = Math.round(size * 0.22f);
            g.fillRoundRect(0, 0, size, size, r, r);
        }

        if (!drawGlyph(g, size, fullBleed)) {
            drawHouse(g, size);
        }
        g.dispose();
        ImageIO.write(img, "png", f);
    }

    /** @return false 表示这台机器没有任何能画出「安」的字体，调用方改用几何图形。 */
    private static boolean drawGlyph(Graphics2D g, int size, boolean fullBleed) {
        String[] candidates = {"Microsoft YaHei", "微软雅黑", "SimHei", "黑体", "SimSun", "宋体",
                "Noto Sans CJK SC", "Source Han Sans SC", "PingFang SC", "SansSerif"};
        Font base = null;
        for (String name : candidates) {
            Font f = new Font(name, Font.BOLD, 10);
            if (f.canDisplay('安')) {
                base = f;
                break;
            }
        }
        if (base == null) {
            return false;
        }
        // maskable 留安全区，所以字小一点；普通图标可以铺得满一些。
        float ratio = fullBleed ? 0.42f : 0.50f;
        Font font = base.deriveFont(Font.BOLD, size * ratio);
        g.setFont(font);

        FontMetrics fm = g.getFontMetrics();
        Rectangle2D b = fm.getStringBounds("安", g);
        // 用 getStringBounds 的视觉中心对齐，而不是拿行高去减：
        // 汉字在行框里是偏下的，按行高居中会看起来偏低一截。
        int x = Math.round((size - (float) b.getWidth()) / 2f);
        int y = Math.round((size - (float) b.getHeight()) / 2f + (float) fm.getAscent());

        g.setColor(new Color(0, 0, 0, 60));
        g.drawString("安", x + Math.max(1, size / 160), y + Math.max(1, size / 160));
        g.setColor(Color.WHITE);
        g.drawString("安", x, y);
        return true;
    }

    /** 退化的备用图形：一个屋顶加一扇门。看不出来是汉字，但一眼能认出是"房子"。 */
    private static void drawHouse(Graphics2D g, int size) {
        g.setColor(Color.WHITE);
        g.setStroke(new BasicStroke(size * 0.055f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        float pad = size * 0.26f;
        float w = size - pad * 2;
        float roofY = size * 0.46f;

        Path2D roof = new Path2D.Float();
        roof.moveTo(pad - size * 0.03f, roofY);
        roof.lineTo(size / 2f, pad);
        roof.lineTo(size - pad + size * 0.03f, roofY);
        g.draw(roof);

        g.draw(new Rectangle2D.Float(pad + w * 0.16f, roofY, w * 0.68f, size * 0.30f));
    }

    private IconGen() {
    }
}
