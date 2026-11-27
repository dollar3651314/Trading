package app.trading.admin.ops;

import app.trading.admin.security.AdminContext;
import app.trading.admin.security.Permission;
import app.trading.admin.security.RequiresPermission;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 后台"找回申请"接口（第 4 批 4b）：全部要 {@code recovery:review}（默认只有超管）。 */
@RestController
class RecoveryAdminController {

  record ApproveBody(String note, String password) {}

  record ReasonBody(String reason) {}

  record KeepBody(String reason, String password) {}

  private final RecoveryOps ops;

  RecoveryAdminController(RecoveryOps ops) {
    this.ops = ops;
  }

  @RequiresPermission(Permission.RECOVERY_REVIEW)
  @GetMapping("/api/admin/recoveries")
  List<RecoveryOps.View> list(@RequestParam(required = false) String status) {
    return ops.list(status);
  }

  @RequiresPermission(Permission.RECOVERY_REVIEW)
  @GetMapping("/api/admin/recoveries/{id}")
  RecoveryOps.Detail detail(@PathVariable long id) {
    return ops.detail(id);
  }

  @RequiresPermission(Permission.RECOVERY_REVIEW)
  @PostMapping("/api/admin/recoveries/{id}/approve")
  RecoveryOps.Detail approve(@PathVariable long id, @RequestBody(required = false) ApproveBody b) {
    return ops.approve(AdminContext.current(), id, b == null ? null : b.note(), b == null ? null : b.password());
  }

  @RequiresPermission(Permission.RECOVERY_REVIEW)
  @PostMapping("/api/admin/recoveries/{id}/reject")
  RecoveryOps.Detail reject(@PathVariable long id, @RequestBody(required = false) ReasonBody b) {
    return ops.reject(AdminContext.current(), id, b == null ? null : b.reason());
  }

  @RequiresPermission(Permission.RECOVERY_REVIEW)
  @PostMapping("/api/admin/recoveries/{id}/revoke")
  RecoveryOps.Detail revoke(@PathVariable long id, @RequestBody(required = false) ReasonBody b) {
    return ops.revoke(AdminContext.current(), id, b == null ? null : b.reason());
  }

  @RequiresPermission(Permission.RECOVERY_REVIEW)
  @PostMapping("/api/admin/recoveries/{id}/keep")
  RecoveryOps.Detail keep(@PathVariable long id, @RequestBody(required = false) KeepBody b) {
    return ops.keep(AdminContext.current(), id, b == null ? null : b.reason(), b == null ? null : b.password());
  }
}
