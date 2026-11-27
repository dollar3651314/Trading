package app.trading.account.recovery;

import app.trading.account.api.RecoveryApi;
import app.trading.account.api.WithdrawPinApi.PinInput;
import app.trading.common.ratelimit.UserWriteLimited;
import app.trading.common.web.CurrentUser;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * 客户自己的找回申请（第 4 批 4b）：看申请（自己是被找回的账户）、点了"不是我"以后输提现密码、撤销；没邮箱的申请人工重设提现密码。
 * 申请换绑只在机器人里（新 TG 申请），网页上没有。
 */
@RestController
class RecoveryController {

  /** 给客户看的申请（不含申请人的 TG 编号、审核记录） */
  record MineView(long id, String kind, String status, boolean disputed, boolean disputePinOk, boolean disputeFrozen, String objectionEndsAt,
      String executeAt, boolean revokeRequested, String createdAt) {}

  record ObjectBody(PinInput pin) {}

  private final RecoveryApi recovery;

  RecoveryController(RecoveryApi recovery) {
    this.recovery = recovery;
  }

  @GetMapping("/api/me/recoveries")
  List<MineView> mine() {
    return recovery.mine(CurrentUser.id()).stream()
        .map(v -> new MineView(v.id(), v.kind().toLowerCase(), v.status().toLowerCase(), v.disputed(), v.disputePinOk(), v.disputeFrozen(),
            utc(v.objectionEndsAt()), utc(v.executeAt()), v.revokeRequestedBy() != null, utc(v.createdAt())))
        .toList();
  }

  @UserWriteLimited
  @PostMapping("/api/me/recoveries/{id}/object")
  Map<String, Object> object(@PathVariable long id, @RequestBody(required = false) ObjectBody b) {
    recovery.objectWithPin(CurrentUser.id(), id, b == null ? null : b.pin());
    return Map.of("ok", true);
  }

  @UserWriteLimited
  @PostMapping("/api/me/recoveries/{id}/revoke")
  Map<String, Object> revoke(@PathVariable long id) {
    return Map.of("message", recovery.revokeByOwner(CurrentUser.id(), id));
  }

  /** 没邮箱忘记提现密码：申请人工重设（第 0 节证明 + Amos 审核 + 48 小时等待期） */
  @UserWriteLimited
  @PostMapping("/api/me/withdraw-pin/forgot/manual")
  Map<String, Object> pinReset() {
    return Map.of("id", recovery.applyPinReset(CurrentUser.id()));
  }

  private static String utc(LocalDateTime t) {
    return t == null ? null : t.toInstant(ZoneOffset.UTC).toString();
  }
}
