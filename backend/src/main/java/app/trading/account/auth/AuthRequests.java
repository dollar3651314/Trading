package app.trading.account.auth;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Past;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;

/**
 * 认证接口的请求体。校验失败的 message 是错误码，所有字段的错误一次返回（C56），前端按错误码显示翻译。
 * 密码规则沿用现有系统：长度 8–64，不限制复杂度（功能地图 A-01）。
 */
final class AuthRequests {
  private AuthRequests() {}

  /** 客户密码：8–64 位，至少包含一个字母和一个数字（前后端一致，负责人 2026-10-16 确认） */
  static final String PASSWORD_MIX = "(?s)(?=.*[A-Za-z])(?=.*\\d).*";

  record SendCode(
      @NotBlank(message = "REQUIRED") @Email(message = "EMAIL_INVALID") @Size(max = 254, message = "EMAIL_INVALID") String email,
      @NotNull(message = "REQUIRED") EmailCodeService.Purpose purpose) {}

  record Register(
      @NotBlank(message = "REQUIRED") @Email(message = "EMAIL_INVALID") @Size(max = 254, message = "EMAIL_INVALID") String email,
      @NotBlank(message = "REQUIRED") @Pattern(regexp = "\\s*\\d{6}\\s*", message = "CODE_FORMAT") String code,
      @NotBlank(message = "REQUIRED") @Size(min = 8, max = 64, message = "PASSWORD_LENGTH") @Pattern(regexp = PASSWORD_MIX, message = "PASSWORD_MIX") String password,
      @NotBlank(message = "REQUIRED") @Size(max = 64, message = "TOO_LONG") String firstName,
      @Size(max = 64, message = "TOO_LONG") String middleName,
      @NotBlank(message = "REQUIRED") @Size(max = 64, message = "TOO_LONG") String lastName,
      @NotNull(message = "REQUIRED") @Past(message = "BIRTH_DATE_INVALID") LocalDate birthDate,
      @NotBlank(message = "REQUIRED") @Pattern(regexp = "[A-Z]{2}", message = "NATIONALITY_INVALID") String nationality) {}

  record Login(@NotBlank(message = "REQUIRED") String email, @NotBlank(message = "REQUIRED") String password) {}

  record ResetPassword(
      @NotBlank(message = "REQUIRED") @Email(message = "EMAIL_INVALID") String email,
      @NotBlank(message = "REQUIRED") @Pattern(regexp = "\\s*\\d{6}\\s*", message = "CODE_FORMAT") String code,
      @NotBlank(message = "REQUIRED") @Size(min = 8, max = 64, message = "PASSWORD_LENGTH") @Pattern(regexp = PASSWORD_MIX, message = "PASSWORD_MIX") String password) {}

  record ChangePassword(
      @NotBlank(message = "REQUIRED") String currentPassword,
      @NotBlank(message = "REQUIRED") @Size(min = 8, max = 64, message = "PASSWORD_LENGTH") @Pattern(regexp = PASSWORD_MIX, message = "PASSWORD_MIX") String newPassword) {}

  /**
   * 登录后返回给前端的信息（令牌在 Cookie 里，不在响应体里）。
   * 机器人开户的客户（纯 Telegram）{@code email} 为空；有 TG 账户的带 {@code telegramUsername}（没有用户名时为空）。
   */
  /**
   * @param fromTelegram 验收反馈 A：这次会话是从机器人进来的（login_url 一键登录 / 一次性链接，会话种类 BOT）。网页据此不显示"添加到主屏幕"引导
   *     （Telegram 内置浏览器里加不了，iOS 内置浏览器的 User-Agent 也靠不住）。登录、注册刚建的网页会话是 false
   */
  record SessionView(String userId, String email, String telegramUsername, boolean fromTelegram) {}
}
