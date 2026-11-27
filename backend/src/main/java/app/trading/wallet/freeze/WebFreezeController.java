package app.trading.wallet.freeze;

import app.trading.common.captcha.Captchas;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 网页"申请冻结提现"（第 4 批 4c，不用登录）：要验证码、提交。规则见 {@link WebFreezeService}。按 IP 的通用限流照常（P-05）。
 * 4c 审查 🟡：提交接口每个分支（信息不对、对了冻结、已经冻结、7 天内重复、验证码不对、出错）都至少等 {@code min-response-ms}（默认 800 毫秒）才回应，
 * 不能从响应时间看出信息对不对（冻结、写通知比"信息不对"慢）。最简单的做法：处理完以后补够这个时间
 */
@RestController
class WebFreezeController {

  private final WebFreezeService service;
  private final long minResponseMs;

  WebFreezeController(WebFreezeService service, @org.springframework.beans.factory.annotation.Value("${trading.wallet.freeze.min-response-ms:800}") long minResponseMs) {
    this.service = service;
    this.minResponseMs = minResponseMs;
  }

  /** 补够最短响应时间 */
  void pad(long startNanos) {
    long left = minResponseMs - (System.nanoTime() - startNanos) / 1_000_000;
    if (left <= 0) return;
    try {
      Thread.sleep(left);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }

  /** 要一张验证码；fp 是网页本地生成的浏览器标识 */
  @PostMapping("/api/public/withdraw-freeze/captcha")
  Captchas.Captcha captcha(HttpServletRequest req, @RequestParam(required = false) String fp) {
    return service.captcha(req.getRemoteAddr(), fp);
  }

  record Body(String customerNo, String name, String docLast4, String depositAmount, String depositDate, String captchaId, String captcha, String fp) {}

  @PostMapping("/api/public/withdraw-freeze")
  Map<String, Object> submit(HttpServletRequest req, @RequestBody(required = false) Body b) {
    long start = System.nanoTime();
    try {
      Body x = b == null ? new Body(null, null, null, null, null, null, null, null) : b;
      String msg = service.submit(req.getRemoteAddr(), x.fp(),
          new WebFreezeService.Form(x.customerNo(), x.name(), x.docLast4(), x.depositAmount(), x.depositDate(), x.captchaId(), x.captcha()));
      return Map.of("message", msg);
    } finally {
      pad(start);
    }
  }
}
