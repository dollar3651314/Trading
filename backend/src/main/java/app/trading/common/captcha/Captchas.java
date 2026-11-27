package app.trading.common.captcha;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import javax.imageio.ImageIO;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * 图形验证码（第 4 批 4c：网页"申请冻结提现"入口）。字是程序里画的点阵（不依赖服务器上的字体），每个字随机偏移、倾斜，加干扰线和噪点；
 * 普通 4 个字，"更难"的 6 个字、干扰更多（同一来源失败多了换更难的）。答案只存哈希在 Redis，5 分钟、只能用一次（核对时就删），不分大小写。
 * 字表去掉了容易看错的（0/O、1/I/L、2/Z、5/S、8/B、G）。
 */
@Component
public class Captchas {

  /** 一个验证码：编号 + PNG 图片（base64） */
  public record Captcha(String id, String pngBase64, boolean hard) {}

  public static final String PREFIX = "fx:captcha:";
  static final Duration TTL = Duration.ofMinutes(5);
  static final String CHARS = "ACDEFHJKMNPRTUVWXY34679";
  private static final SecureRandom RANDOM = new SecureRandom();

  /** 5×7 点阵 */
  static final Map<Character, String[]> GLYPHS = Map.ofEntries(
      Map.entry('A', new String[] {".###.", "#...#", "#...#", "#####", "#...#", "#...#", "#...#"}),
      Map.entry('C', new String[] {".####", "#....", "#....", "#....", "#....", "#....", ".####"}),
      Map.entry('D', new String[] {"####.", "#...#", "#...#", "#...#", "#...#", "#...#", "####."}),
      Map.entry('E', new String[] {"#####", "#....", "#....", "####.", "#....", "#....", "#####"}),
      Map.entry('F', new String[] {"#####", "#....", "#....", "####.", "#....", "#....", "#...."}),
      Map.entry('H', new String[] {"#...#", "#...#", "#...#", "#####", "#...#", "#...#", "#...#"}),
      Map.entry('J', new String[] {"..###", "...#.", "...#.", "...#.", "...#.", "#..#.", ".##.."}),
      Map.entry('K', new String[] {"#...#", "#..#.", "#.#..", "##...", "#.#..", "#..#.", "#...#"}),
      Map.entry('M', new String[] {"#...#", "##.##", "#.#.#", "#.#.#", "#...#", "#...#", "#...#"}),
      Map.entry('N', new String[] {"#...#", "##..#", "#.#.#", "#..##", "#...#", "#...#", "#...#"}),
      Map.entry('P', new String[] {"####.", "#...#", "#...#", "####.", "#....", "#....", "#...."}),
      Map.entry('R', new String[] {"####.", "#...#", "#...#", "####.", "#.#..", "#..#.", "#...#"}),
      Map.entry('T', new String[] {"#####", "..#..", "..#..", "..#..", "..#..", "..#..", "..#.."}),
      Map.entry('U', new String[] {"#...#", "#...#", "#...#", "#...#", "#...#", "#...#", ".###."}),
      Map.entry('V', new String[] {"#...#", "#...#", "#...#", "#...#", "#...#", ".#.#.", "..#.."}),
      Map.entry('W', new String[] {"#...#", "#...#", "#...#", "#.#.#", "#.#.#", "##.##", "#...#"}),
      Map.entry('X', new String[] {"#...#", "#...#", ".#.#.", "..#..", ".#.#.", "#...#", "#...#"}),
      Map.entry('Y', new String[] {"#...#", "#...#", ".#.#.", "..#..", "..#..", "..#..", "..#.."}),
      Map.entry('3', new String[] {"####.", "....#", "....#", ".###.", "....#", "....#", "####."}),
      Map.entry('4', new String[] {"#...#", "#...#", "#...#", "#####", "....#", "....#", "....#"}),
      Map.entry('6', new String[] {".###.", "#....", "#....", "####.", "#...#", "#...#", ".###."}),
      Map.entry('7', new String[] {"#####", "....#", "...#.", "..#..", ".#...", ".#...", ".#..."}),
      Map.entry('9', new String[] {".###.", "#...#", "#...#", ".####", "....#", "....#", ".###."}));

  private final StringRedisTemplate redis;

  public Captchas(StringRedisTemplate redis) {
    this.redis = redis;
  }

  public Captcha create(boolean hard) {
    int n = hard ? 6 : 4;
    StringBuilder answer = new StringBuilder();
    for (int i = 0; i < n; i++) answer.append(CHARS.charAt(RANDOM.nextInt(CHARS.length())));
    String id = UUID.randomUUID().toString().replace("-", "");
    redis.opsForValue().set(PREFIX + id, hash(answer.toString()), TTL);
    return new Captcha(id, Base64.getEncoder().encodeToString(png(answer.toString(), hard)), hard);
  }

  /** 核对并作废（不管对不对都只能用一次） */
  public boolean verify(String id, String answer) {
    if (id == null || answer == null || !id.matches("[0-9a-f]{32}")) return false;
    String h = redis.opsForValue().getAndDelete(PREFIX + id);
    return h != null && h.equals(hash(answer.strip().toUpperCase(Locale.ROOT)));
  }

  /** 答案的哈希（Redis 里只存它）；测试里也用来放一个已知答案 */
  public static String hash(String s) {
    try {
      byte[] d = java.security.MessageDigest.getInstance("SHA-256").digest(("captcha:" + s).getBytes(java.nio.charset.StandardCharsets.UTF_8));
      return java.util.HexFormat.of().formatHex(d);
    } catch (java.security.NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  static byte[] png(String text, boolean hard) {
    int scale = 5;
    int w = 24 + text.length() * 34;
    int h = 64;
    BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
    Graphics2D g = img.createGraphics();
    try {
      g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
      g.setColor(new Color(245, 247, 250));
      g.fillRect(0, 0, w, h);
      for (int i = 0; i < (hard ? 10 : 5); i++) line(g, w, h, 1 + RANDOM.nextInt(2));
      int x = 12;
      for (char c : text.toCharArray()) {
        String[] glyph = GLYPHS.get(c);
        int ox = x + RANDOM.nextInt(6) - 3;
        int oy = 10 + RANDOM.nextInt(10) - 5;
        double shear = (RANDOM.nextDouble() - 0.5) * (hard ? 0.9 : 0.5);
        g.setColor(new Color(20 + RANDOM.nextInt(80), 30 + RANDOM.nextInt(80), 60 + RANDOM.nextInt(100)));
        for (int row = 0; row < glyph.length; row++) {
          for (int col = 0; col < glyph[row].length(); col++) {
            if (glyph[row].charAt(col) != '#') continue;
            int px = ox + col * scale + (int) Math.round(shear * (glyph.length - row) * scale / 2.0);
            g.fillRect(px, oy + row * scale + RANDOM.nextInt(2), scale, scale);
          }
        }
        x += 34;
      }
      for (int i = 0; i < (hard ? 6 : 3); i++) line(g, w, h, 2);
      int dots = hard ? w * h / 18 : w * h / 40;
      for (int i = 0; i < dots; i++) {
        img.setRGB(RANDOM.nextInt(w), RANDOM.nextInt(h), new Color(RANDOM.nextInt(200), RANDOM.nextInt(200), RANDOM.nextInt(200)).getRGB());
      }
    } finally {
      g.dispose();
    }
    try {
      ByteArrayOutputStream out = new ByteArrayOutputStream();
      ImageIO.write(img, "png", out);
      return out.toByteArray();
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  private static void line(Graphics2D g, int w, int h, int width) {
    g.setColor(new Color(100 + RANDOM.nextInt(120), 100 + RANDOM.nextInt(120), 100 + RANDOM.nextInt(120)));
    g.setStroke(new java.awt.BasicStroke(width));
    g.drawLine(RANDOM.nextInt(w), RANDOM.nextInt(h), RANDOM.nextInt(w), RANDOM.nextInt(h));
  }
}
