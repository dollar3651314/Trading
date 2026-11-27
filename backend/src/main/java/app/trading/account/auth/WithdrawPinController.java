package app.trading.account.auth;

import app.trading.account.api.WithdrawPinApi.PinInput;
import app.trading.common.ratelimit.UserWriteLimited;
import app.trading.common.web.CurrentUser;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * 客户的提现密码（TG 机器人第 4 批，网页里用随机键盘输）：状态、要一个随机键盘、第一次设置、修改、忘记（绑了邮箱的）。
 * 设置、修改、重设都是安全事件：成功后这个账户的网页会话全部作废，客户回机器人「重新授权」后再打开。
 */
@RestController
class WithdrawPinController {

  private final WithdrawPins pins;
  private final AuthService auth;

  WithdrawPinController(WithdrawPins pins, AuthService auth) {
    this.pins = pins;
    this.auth = auth;
  }

  record StatusView(boolean set, String lockedUntil, boolean withdrawFrozen, boolean hasEmail) {}

  record KeypadView(String keypadId, List<Integer> digits) {}

  record SetBody(PinInput pin, PinInput confirm) {}

  record ChangeBody(PinInput old, PinInput pin, PinInput confirm) {}

  record ForgotBody(String code, PinInput pin, PinInput confirm) {}

  record CodeSent(int cooldownSeconds) {}

  /** 第 4 批 4c：提现冻结的来源（小写）、什么时候、自助解冻时间（只有机器人里自己冻结的才有） */
  record FreezeView(String source, String at, String releaseAt) {}

  record UnfreezeBody(PinInput pin) {}

  /** 第 4 批 4c：自己的提现冻结（全部来源） */
  @GetMapping("/api/me/withdraw-freeze")
  List<FreezeView> freezes() {
    return pins.status(CurrentUser.id()).freezes().stream()
        .map(f -> new FreezeView(f.source().toLowerCase(), utc(f.at()), utc(f.releaseAt()))).toList();
  }

  /** 第 4 批 4c：机器人里自己冻结的，输对提现密码后 24 小时自动解冻（别的来源要人工） */
  @UserWriteLimited
  @PostMapping("/api/me/withdraw-freeze/self-unfreeze")
  java.util.Map<String, String> selfUnfreeze(@RequestBody(required = false) UnfreezeBody b) {
    return java.util.Map.of("releaseAt", utc(pins.requestSelfUnfreeze(CurrentUser.id(), b == null ? null : b.pin())));
  }

  private static String utc(java.time.LocalDateTime t) {
    return t == null ? null : t.toInstant(java.time.ZoneOffset.UTC).toString();
  }

  @GetMapping("/api/me/withdraw-pin")
  StatusView status() {
    long uid = CurrentUser.id();
    var s = pins.status(uid);
    return new StatusView(s.set(), s.lockedUntil() == null ? null : s.lockedUntil().toInstant(java.time.ZoneOffset.UTC).toString(), s.withdrawFrozen(),
        auth.user(uid).email() != null);
  }

  @UserWriteLimited
  @PostMapping("/api/me/withdraw-pin/keypad")
  KeypadView keypad() {
    var k = pins.keypad(CurrentUser.id());
    return new KeypadView(k.id(), k.digits());
  }

  @UserWriteLimited
  @PostMapping("/api/me/withdraw-pin")
  ResponseEntity<Void> set(@RequestBody SetBody b) {
    pins.setFirst(CurrentUser.id(), b == null ? null : b.pin(), b == null ? null : b.confirm());
    return ResponseEntity.noContent().build();
  }

  @UserWriteLimited
  @PostMapping("/api/me/withdraw-pin/change")
  ResponseEntity<Void> change(@RequestBody ChangeBody b) {
    pins.change(CurrentUser.id(), b == null ? null : b.old(), b == null ? null : b.pin(), b == null ? null : b.confirm());
    return ResponseEntity.noContent().build();
  }

  @UserWriteLimited
  @PostMapping("/api/me/withdraw-pin/forgot/code")
  CodeSent forgotCode() {
    return new CodeSent(pins.forgotCode(CurrentUser.id()));
  }

  @UserWriteLimited
  @PostMapping("/api/me/withdraw-pin/forgot")
  ResponseEntity<Void> forgot(@RequestBody ForgotBody b) {
    pins.forgotReset(CurrentUser.id(), b == null ? null : b.code(), b == null ? null : b.pin(), b == null ? null : b.confirm());
    return ResponseEntity.noContent().build();
  }
}
