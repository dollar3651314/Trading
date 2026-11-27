package app.trading.admin.ops;

import app.trading.account.api.RecoveryApi;
import app.trading.admin.security.AdminPublic;
import app.trading.admin.security.Audit;
import java.util.Map;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * 告警邮件里的撤销链接（第 6 批，TB-37 ②）：不用登录（Amos 可能进不了后台），只在后台域名上（Nginx 只转 /api/admin/**），IP 白名单照样检查。
 * 链接是 {@code <后台地址>/admin/recovery-revoke#t=<令牌>}：令牌在 # 后面（不进服务器日志），页面读出来后用 POST 先看是哪一份申请（不用掉令牌），
 * 点「撤销」再 POST 一次才撤销（邮件客户端预取链接不会误撤销）。<b>只能撤销</b>：没有批准、维持的入口。令牌 24 小时有效、只能用一次。
 * 撤销写审计（操作人"系统"，目标申请编号）、发告警邮件 OPS_RECOVERY_CANCELLED。
 */
@RestController
class RecoveryRevokeLinkController {

  record TokenBody(String token) {}

  record Peek(boolean valid, Long id, String kind, Long customerId, String status, String executeAt) {}

  record Result(String result) {}

  private final RecoveryApi recovery;
  private final Audit audit;

  RecoveryRevokeLinkController(RecoveryApi recovery, Audit audit) {
    this.recovery = recovery;
    this.audit = audit;
  }

  @AdminPublic
  @PostMapping("/api/admin/public/recovery-revoke/peek")
  ResponseEntity<Peek> peek(@RequestBody(required = false) TokenBody b) {
    Peek p = recovery.peekRevokeLink(b == null ? null : b.token())
        .map(t -> new Peek(true, t.id(), t.kind().toLowerCase(), t.userId(), t.status().toLowerCase(), Ops.time(t.executeAt())))
        .orElse(new Peek(false, null, null, null, null, null));
    return ResponseEntity.ok().header(HttpHeaders.CACHE_CONTROL, "no-store").body(p);
  }

  @AdminPublic
  @PostMapping("/api/admin/public/recovery-revoke")
  ResponseEntity<Result> revoke(@RequestBody(required = false) TokenBody b) {
    String token = b == null ? null : b.token();
    Long id = recovery.peekRevokeLink(token).map(RecoveryApi.RevokeTarget::id).orElse(null);
    RecoveryApi.LinkRevoke r = recovery.revokeByLink(token);
    if (r == RecoveryApi.LinkRevoke.CANCELLED && id != null) {
      audit.recordSystem("recovery.revoke_link", String.valueOf(id), "告警邮件里的撤销链接", Map.of("status", "open"), Map.of("status", "cancelled"));
    }
    return ResponseEntity.ok().header(HttpHeaders.CACHE_CONTROL, "no-store").body(new Result(r.name().toLowerCase()));
  }
}
