package app.trading.admin.ops;

import app.trading.admin.security.AdminContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * "只有 Amos 能操作"的判断（第 5 批应急开关 TB-34、第 6 批暂停受理人工申请 TB-38 共用）：配置 {@code trading.admin.owner-id}（{@code FX_ADMIN_OWNER_ID}）
 * 写死 Amos 后台账号的<b>编号</b>，不写用户名、不靠权限点；还要是超管。没配编号时谁都不能操作。
 */
@Component
class OwnerGuard {

  private static final Logger log = LoggerFactory.getLogger(OwnerGuard.class);

  private final Long ownerId;

  OwnerGuard(@Value("${trading.admin.owner-id:}") String ownerId) {
    this.ownerId = parse(ownerId);
  }

  static Long parse(String v) {
    if (v == null || v.isBlank()) return null;
    String s = v.strip();
    if (!s.matches("[1-9]\\d{0,18}")) {
      log.warn("FX_ADMIN_OWNER_ID 不是后台账号编号（正整数），按没配处理：只限 Amos 的操作谁都不能做");
      return null;
    }
    return Long.valueOf(s);
  }

  boolean configured() {
    return ownerId != null;
  }

  boolean isOwner(AdminContext a) {
    return ownerId != null && a.superAdmin() && a.adminId() == ownerId;
  }
}
