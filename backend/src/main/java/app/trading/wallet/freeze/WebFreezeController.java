package app.trading.wallet.freeze;

import app.trading.common.captcha.Captchas;
import app.trading.common.error.RetryLaterException;
import app.trading.common.ratelimit.RateLimiter;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 网页"申请冻结提现"（第 4 批 4c，不用登录）：要验证码、提交。规则见 {@link WebFreezeService}。按 IP 的通用限流照常（P-05），Nginx 另有单独的低限额。
 * <ul>
 *   <li>4c 审查 🟡：提交接口每个分支（信息不对、对了冻结、已经冻结、7 天内重复、验证码不对、出错）都至少等 {@code min-response-ms}（默认 800 毫秒）才回应，
 *       不能从响应时间看出信息对不对。</li>
 *   <li>4c 复审 🔴D1：补时间<b>不占请求线程</b>——处理完以后返回一个 {@link CompletableFuture}，由定时器在 {@code max(0, 最短时间 − 已用时间)} 后完成
 *       （Servlet 异步，请求线程马上还给 Tomcat）。同时等待中的提交最多 {@code max-pending} 个（默认 20），超过的<b>马上</b>回 429 {@code RATE_LIMITED}
 *       （只看并发数，和填的信息无关）。原来 {@code Thread.sleep} 会占住请求线程，不用登录的接口被刷就能占满全部线程、拖垮交易和健康检查。</li>
 * </ul>
 */
@RestController
class WebFreezeController {

  private final WebFreezeService service;
  private final long minResponseMs;
  private final Semaphore pending;

  WebFreezeController(WebFreezeService service, @Value("${trading.wallet.freeze.min-response-ms:800}") long minResponseMs,
      @Value("${trading.wallet.freeze.max-pending:20}") int maxPending) {
    this.service = service;
    this.minResponseMs = minResponseMs;
    this.pending = new Semaphore(Math.max(1, maxPending));
  }

  /** 要一张验证码；fp 是网页本地生成的浏览器标识 */
  @PostMapping("/api/public/withdraw-freeze/captcha")
  Captchas.Captcha captcha(HttpServletRequest req, @RequestParam(required = false) String fp) {
    return service.captcha(req.getRemoteAddr(), fp);
  }

  record Body(String customerNo, String name, String docLast4, String depositAmount, String depositDate, String captchaId, String captcha, String fp) {}

  @PostMapping("/api/public/withdraw-freeze")
  CompletableFuture<Map<String, Object>> submit(HttpServletRequest req, @RequestBody(required = false) Body b) {
    long start = System.nanoTime();
    if (!pending.tryAcquire()) throw new RetryLaterException(RateLimiter.CODE, "请求太频繁，请稍后再试", 5);
    CompletableFuture<Map<String, Object>> out = new CompletableFuture<>();
    out.whenComplete((r, e) -> pending.release());
    Map<String, Object> result = null;
    RuntimeException error = null;
    try {
      Body x = b == null ? new Body(null, null, null, null, null, null, null, null) : b;
      String msg = service.submit(req.getRemoteAddr(), x.fp(),
          new WebFreezeService.Form(x.customerNo(), x.name(), x.docLast4(), x.depositAmount(), x.depositDate(), x.captchaId(), x.captcha()));
      result = Map.of("message", msg);
    } catch (RuntimeException e) {
      error = e;
    } catch (Error fatal) {
      pending.release();
      throw fatal;
    }
    Map<String, Object> r = result;
    RuntimeException e = error;
    Runnable finish = () -> {
      if (e != null) out.completeExceptionally(e);
      else out.complete(r);
    };
    long left = minResponseMs - (System.nanoTime() - start) / 1_000_000;
    if (left <= 0) finish.run();
    else later(left).execute(finish);
    return out;
  }

  /** 到时间后在定时器线程上直接完成（完成只是把请求交回容器，很快） */
  static Executor later(long ms) {
    return CompletableFuture.delayedExecutor(ms, TimeUnit.MILLISECONDS, Runnable::run);
  }

  /** 测试用：现在还能接受几个等待中的提交 */
  int availablePermits() {
    return pending.availablePermits();
  }
}
