package app.trading.notification.api;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Set;

/** 后台使用的通知操作（页面、权限、二次确认和审计在 S16）。 */
public interface NotifyAdminApi {

  /**
   * 客户的安全类提醒模板（第 6 批 #46 审查）：冻结、提现和提现密码、白名单、找回和换绑、Telegram 关联、应急登录、新设备登录、重设提现密码、验证码。
   * 后台<b>只能改文案</b>：不能停用，改的时候原来文案里有的变量一个都不能少；每次改都给告警邮箱发邮件。机器人按钮（「不是我」「撤销」等）按模板编号在代码里加，
   * 后台改不了
   */
  static boolean securityTemplate(String code) {
    if (code == null) return false;
    return code.startsWith("WITHDRAW_") || code.startsWith("WHITELIST_") || code.startsWith("RECOVERY_") || code.startsWith("PIN_RESET_")
        || java.util.Set.of("ACCOUNT_FROZEN", "TELEGRAM_LINK_CHANGED", "TG_EMERGENCY_LOGIN", "LOGIN_NEW_DEVICE", "EMAIL_CODE").contains(code);
  }

  /** 后台不能停用的客户提醒（#46 复审）：上面的安全提醒，加上追保（MARGIN_CALL）、强平（LIQUIDATED）——这两个只锁停用，文案里的变量可以改 */
  static boolean cannotDisable(String code) {
    return securityTemplate(code) || "MARGIN_CALL".equals(code) || "LIQUIDATED".equals(code);
  }

  /**
   * 手动发送给单个客户（原型"手动发送"，不支持群发）。标题和正文原样发送，不按模板翻译。
   * 推送和邮件同样遵守客户的通知偏好，关闭的渠道记为已跳过。
   */
  void manual(long userId, Set<NotifyChannel> channels, String title, String body, long operatorId);

  /** 一个模板；audience USER / STAFF / ANY，channels 是 INBOX、PUSH、EMAIL 的子集 */
  record Template(String code, String audience, String kind, String nameZh, String nameEn, String titleZh, String bodyZh, String titleEn, String bodyEn,
      List<String> variables, List<NotifyChannel> channels, boolean builtIn, boolean enabled, LocalDateTime updatedAt) {}

  /** 一条通知记录；status PENDING / SENT / FAILED / SKIPPED，source TEMPLATE / MANUAL */
  record Record(long id, Long userId, String recipient, String source, String templateCode, NotifyChannel channel, String title, String status,
      String error, Long operatorId, LocalDateTime createdAt) {}

  record Page<T>(List<T> items, long total) {}

  List<Template> templates();

  /**
   * 改模板文案（为空的字段不改）。正文里只能用模板列出的变量；内置模板不能停用（停用会让出金等流程的通知发不出去）。
   */
  Template updateTemplate(String code, String titleZh, String bodyZh, String titleEn, String bodyEn, Boolean enabled);

  /** channel、status 为空表示不筛选；userIds 不为空时只查这些客户 */
  Page<Record> records(NotifyChannel channel, String status, Collection<Long> userIds, int offset, int limit);
}
