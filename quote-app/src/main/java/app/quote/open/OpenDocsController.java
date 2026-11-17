package app.quote.open;

import app.quote.QuoteProperties;
import app.quote.feed.QuoteRouter;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 开发者文档 {@code /docs/}（需求 03 页第五节，中文）：对外开放前只在测试环境能看（quote.open.docs=auto 时报价模式 live 的生产不给看，返回 404）。
 * 文档里每个接口的字段表带 {@code data-fields}，测试逐字段和实际返回核对（AC-Q5）。
 */
@RestController
class OpenDocsController {

  static final String DOC = "open-docs/index.html";

  private final boolean enabled;

  OpenDocsController(OpenProperties props, QuoteProperties quote, app.quote.feed.QuoteFeedsProperties feeds) {
    // auto：在用真实报价数据（报价模式 live，或配了任何真实报价源）时不给看（第 3 批审查：不能只看报价模式）；对外开放后由运维明确设 true
    this.enabled = switch (props.docs()) {
      case "true" -> true;
      case "false" -> false;
      default -> !app.quote.license.LicenseRegistry.realData(quote, feeds);
    };
  }

  @GetMapping({"/docs", "/docs/", "/docs/index.html"})
  ResponseEntity<String> docs() throws IOException {
    if (!enabled) return ResponseEntity.notFound().build();
    try (InputStream in = getClass().getClassLoader().getResourceAsStream(DOC)) {
      if (in == null) return ResponseEntity.notFound().build();
      return ResponseEntity.ok().contentType(new MediaType("text", "html", StandardCharsets.UTF_8)).body(new String(in.readAllBytes(), StandardCharsets.UTF_8));
    }
  }
}
