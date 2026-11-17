package app.trading.notification.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/**
 * v2 品牌改名（验收 AC2-12，《v2-需求说明书》第 4.5、8 节）：后端发给客户的文字里没有旧品牌名。
 * <ul>
 *   <li>迁移 V20 在空库上：全部通知模板（标题和正文，中英文）都没有旧品牌名，验证码邮件标题是 Trading；</li>
 *   <li>迁移 V20 在"已有 V14 数据、运营改过模板"的库上：只改仍等于原始默认文字的模板，运营改过的（哪怕只改了大小写）保留原样；</li>
 *   <li>后端代码里写死的字符串没有旧品牌名（配置项前缀 {@code trading.} 这类内部名字除外，客户看不到）。</li>
 * </ul>
 * 迁移测试不启动 Spring，直接用 Flyway 在单独的库（测试库名加 {@code _brand_mig}）上执行，结束后删掉这个库。
 */
class BrandTextTest {

  // 旧品牌名：迁移历史里的占位名 OldBrand，以及前身项目的两个品牌名（拼起来写，免得仓库的品牌检查命中本文件）
  static final Pattern OLD_BRAND = Pattern.compile("(?i)old\\s*brand|fal" + "con\\s*x|qui" + "ck\\s*come");
  static final String DEFAULT_TITLE_ZH = "OldBrand 验证码";

  static String url;
  static String dbName;
  static DriverManagerDataSource admin;

  @BeforeAll
  static void setUp() {
    String base = env("FX_DB_URL", "jdbc:mysql://localhost:3306/trading_test");
    Matcher m = Pattern.compile("^(jdbc:mysql://[^/]+/)([^?]+)(\\?.*)?$").matcher(base);
    assertThat(m.matches()).as("FX_DB_URL 格式：jdbc:mysql://主机:端口/库名[?参数]").isTrue();
    dbName = m.group(2) + "_brand_mig";
    String params = m.group(3) == null ? "?" : m.group(3) + "&";
    url = m.group(1) + dbName + params.replace("createDatabaseIfNotExist=true&", "") + "createDatabaseIfNotExist=true";
    admin = new DriverManagerDataSource(url, env("FX_DB_USERNAME", "root"), System.getenv("FX_DB_PASSWORD"));
  }

  @AfterAll
  static void tearDown() {
    if (admin != null) new JdbcTemplate(admin).execute("DROP DATABASE IF EXISTS `" + dbName + "`");
  }

  static String env(String name, String def) {
    String v = System.getenv(name);
    return v == null || v.isBlank() ? def : v;
  }

  /** 每个用例从空库开始 */
  static Flyway flyway(String target) {
    JdbcTemplate jdbc = new JdbcTemplate(admin);
    // 删掉后下一次连接会自动重建空库（连接串带 createDatabaseIfNotExist=true）
    jdbc.execute("DROP DATABASE IF EXISTS `" + dbName + "`");
    return Flyway.configure().dataSource(admin).locations("classpath:db/migration").target(target).load();
  }

  static Flyway flywayAll() {
    return Flyway.configure().dataSource(admin).locations("classpath:db/migration").load();
  }

  static Map<String, Object> emailCode(JdbcTemplate jdbc) {
    return jdbc.queryForMap("SELECT title_zh, title_en, body_zh, body_en FROM notify_template WHERE code = 'EMAIL_CODE'");
  }

  @Test
  void 空库_全部迁移后通知模板里没有旧品牌名() {
    flyway("latest").migrate();
    JdbcTemplate jdbc = new JdbcTemplate(admin);
    List<Map<String, Object>> rows = jdbc.queryForList("SELECT code, title_zh, title_en, body_zh, body_en FROM notify_template");
    assertThat(rows).hasSizeGreaterThan(5);
    for (Map<String, Object> r : rows) {
      String text = r.get("title_zh") + "\n" + r.get("title_en") + "\n" + r.get("body_zh") + "\n" + r.get("body_en");
      assertThat(OLD_BRAND.matcher(text).find()).as("模板 %s：%s", r.get("code"), text).isFalse();
    }
    Map<String, Object> code = emailCode(jdbc);
    assertThat(code.get("title_zh")).isEqualTo("Trading 验证码");
    assertThat(code.get("title_en")).isEqualTo("Trading verification code");
  }

  @Test
  void 已有数据_没改过的模板改名_运营改过的保留原样() {
    flyway("19").migrate();
    JdbcTemplate jdbc = new JdbcTemplate(admin);
    assertThat(emailCode(jdbc).get("title_zh")).as("V14 写入的原始标题").isEqualTo(DEFAULT_TITLE_ZH);
    // 运营在后台改过验证码模板的中文正文（标题没动）
    jdbc.update("UPDATE notify_template SET body_zh = '验证码 {code}，请在 10 分钟内使用。' WHERE code = 'EMAIL_CODE'");

    flywayAll().migrate();
    Map<String, Object> code = emailCode(jdbc);
    assertThat(code.get("title_zh")).as("运营改过的模板整条不动").isEqualTo(DEFAULT_TITLE_ZH);
    assertThat(code.get("body_zh")).isEqualTo("验证码 {code}，请在 10 分钟内使用。");
    assertThat(code.get("title_en")).isEqualTo("OldBrand verification code");
    assertThat(jdbc.queryForObject("SELECT title_zh FROM notify_template WHERE code = 'DEPOSIT_CREDITED'", String.class))
        .as("其他模板不受影响").isEqualTo("充值已到账");
  }

  @Test
  void 已有数据_运营只改了大小写也算改过() {
    flyway("19").migrate();
    JdbcTemplate jdbc = new JdbcTemplate(admin);
    jdbc.update("UPDATE notify_template SET title_zh = 'OLDBRAND 验证码' WHERE code = 'EMAIL_CODE'");
    flywayAll().migrate();
    assertThat(emailCode(jdbc).get("title_zh")).isEqualTo("OLDBRAND 验证码");
  }

  @Test
  void 已有数据_没改过时升级后改名() {
    flyway("19").migrate();
    JdbcTemplate jdbc = new JdbcTemplate(admin);
    flywayAll().migrate();
    Map<String, Object> code = emailCode(jdbc);
    assertThat(code.get("title_zh")).isEqualTo("Trading 验证码");
    assertThat(code.get("title_en")).isEqualTo("Trading verification code");
    assertThat(code.get("body_zh")).as("正文本来就没有品牌名，不变").isEqualTo("您的验证码是 {code}，10 分钟内有效。请勿告诉任何人。");
  }

  @Test
  void 后端代码里写死的字符串没有旧品牌名() throws IOException {
    Pattern literal = Pattern.compile("\"((?:[^\"\\\\\\n]|\\\\.)*)\"");
    List<String> hits = new ArrayList<>();
    try (Stream<Path> files = Files.walk(Path.of("src/main/java"))) {
      for (Path f : files.filter(p -> p.toString().endsWith(".java")).toList()) {
        String src = Files.readString(f, StandardCharsets.UTF_8);
        Matcher m = literal.matcher(src);
        while (m.find()) {
          // 配置项前缀（trading.xxx）是内部名字，客户看不到；合约充值地址 CREATE2 盐的前缀 trading-deposit 是地址算法的一部分
          // （和合约 contracts/src/DepositSalt.sol 一致，改了所有合约地址都会变，内部文档 第 4.13.1 节），客户也看不到
          String s = m.group(1).replaceAll("trading\\.", "").replace("trading-deposit", "");
          if (OLD_BRAND.matcher(s).find()) hits.add(f + "：" + m.group());
        }
      }
    }
    assertThat(hits).isEmpty();
  }
}
