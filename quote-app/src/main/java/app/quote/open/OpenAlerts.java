package app.quote.open;

import app.quote.health.AlertCheck;
import app.quote.license.LicenseRegistry;
import app.quote.license.QuoteLicense;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * 报价对外的提醒（进健康检查的 alerts 和内部心跳，trading-app 收到后照样给告警邮箱发邮件，同一项 30 分钟内不重复）：
 * <ul>
 *   <li>{@code quote.usage80}：有 Key 今天的 REST 请求数到了每日限额的 80%（需求 03 页第三节、原型 P9）；</li>
 *   <li>{@code quote.license_expiry}：有授权 30 天内到期（需求 03 页第四节"到期前 30 天邮件提醒运营"）。到期当天起自动按没授权处理（{@link LicenseRegistry}）。</li>
 * </ul>
 */
@Component
class OpenAlerts implements AlertCheck {

  static final int EXPIRY_DAYS = 30;

  private final Usage usage;
  private final LicenseRegistry licenses;
  private final Clock clock;

  OpenAlerts(Usage usage, LicenseRegistry licenses, Clock clock) {
    this.usage = usage;
    this.licenses = licenses;
    this.clock = clock;
  }

  @Override
  public List<Item> evaluate() {
    List<Item> out = new ArrayList<>();
    // 没有 Key、没有登记授权时不需要检查，返回空（和其他告警项一样）
    Map<String, Long> over = usage.over80();
    if (usage.hasActiveKeys()) out.add(new Item("quote.usage80", "报价 Key 用量到 80%", "Quote API key usage at 80%", over.isEmpty(),
        over.isEmpty() ? "正常" : "这些 Key 今天的 REST 请求数已到每日限额的 80%：" + over.keySet() + "（只计量不收费；超了只拒超出的请求）"));
    if (licenses.all().isEmpty()) return out;
    List<String> soon = expiringSoon(licenses, LocalDate.ofInstant(clock.instant(), ZoneOffset.UTC));
    out.add(new Item("quote.license_expiry", "报价授权快到期", "Quote license expiring", soon.isEmpty(),
        soon.isEmpty() ? "正常" : EXPIRY_DAYS + " 天内到期的授权：" + String.join("；", soon) + "。到期当天起按没授权处理，续签后在后台续登记"));
    return out;
  }

  /** 30 天内（含今天）到期的授权，例如"alltick × XAUUSD 交易用途 2026-12-05 到期（还剩 18 天）" */
  static List<String> expiringSoon(LicenseRegistry licenses, LocalDate today) {
    List<String> out = new ArrayList<>();
    for (QuoteLicense l : licenses.all()) {
      for (QuoteLicense.Use u : QuoteLicense.Use.values()) {
        QuoteLicense.Grant g = l.grant(u);
        if (!g.granted() || g.until() == null) continue;
        long left = ChronoUnit.DAYS.between(today, g.until());
        if (left >= 0 && left <= EXPIRY_DAYS) out.add(l.feed() + " × " + l.symbol() + " " + useName(u) + " " + g.until() + " 到期（还剩 " + left + " 天）");
      }
    }
    return out;
  }

  static String useName(QuoteLicense.Use u) {
    return switch (u) {
      case TRADING -> "交易用途";
      case DISPLAY -> "登录后展示";
      case RESALE -> "转售";
    };
  }
}
