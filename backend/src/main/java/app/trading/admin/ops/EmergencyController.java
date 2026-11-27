package app.trading.admin.ops;

import app.trading.admin.security.AdminContext;
import app.trading.admin.security.Permission;
import app.trading.admin.security.RequiresPermission;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** 后台"应急开关"接口（第 5 批，TB-34）：看要 {@code emergency:view}；开、关另外只限 Amos 的后台账号（{@link EmergencyOps}）。 */
@RestController
class EmergencyController {

  /** on：要打开（true）还是关闭（false）；reason 必填；password 是自己的后台密码 */
  record SetBody(Boolean on, String reason, String password) {}

  private final EmergencyOps ops;

  EmergencyController(EmergencyOps ops) {
    this.ops = ops;
  }

  @RequiresPermission(Permission.EMERGENCY_VIEW)
  @GetMapping("/api/admin/emergency")
  EmergencyOps.View view() {
    return ops.view(AdminContext.current());
  }

  @RequiresPermission(Permission.EMERGENCY_VIEW)
  @PostMapping("/api/admin/emergency")
  EmergencyOps.View set(@RequestBody(required = false) SetBody b) {
    if (b == null || b.on() == null) {
      throw new app.trading.common.error.ValidationException(java.util.List.of(new app.trading.common.error.ValidationException.Field("on", "required")));
    }
    AdminContext a = AdminContext.current();
    ops.set(a, b.on(), b.reason(), b.password());
    return ops.view(a);
  }
}
