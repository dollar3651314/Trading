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
 */
@RestController
class WebFreezeController {

  private final WebFreezeService service;

  WebFreezeController(WebFreezeService service) {
    this.service = service;
  }

  /** 要一张验证码；fp 是网页本地生成的浏览器标识 */
  @PostMapping("/api/public/withdraw-freeze/captcha")
  Captchas.Captcha captcha(HttpServletRequest req, @RequestParam(required = false) String fp) {
    return service.captcha(req.getRemoteAddr(), fp);
  }

  record Body(String customerNo, String name, String docLast4, String depositAmount, String depositDate, String captchaId, String captcha, String fp) {}

  @PostMapping("/api/public/withdraw-freeze")
  Map<String, Object> submit(HttpServletRequest req, @RequestBody(required = false) Body b) {
    Body x = b == null ? new Body(null, null, null, null, null, null, null, null) : b;
    String msg = service.submit(req.getRemoteAddr(), x.fp(),
        new WebFreezeService.Form(x.customerNo(), x.name(), x.docLast4(), x.depositAmount(), x.depositDate(), x.captchaId(), x.captcha()));
    return Map.of("message", msg);
  }
}
