package app.trading.admin.ops;

import app.trading.admin.security.AdminContext;
import app.trading.admin.security.AdminNames;
import app.trading.admin.security.Audit;
import app.trading.common.error.BizException;
import app.trading.payment.api.PayNetwork;
import app.trading.payment.api.PaymentAdminApi;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

/**
 * 后台"资金 → 钱包提供方"（第 1 批，原型 P7、需求 02 第五节）：各家提供方的接口状态和可用余额、三条充值网络的开关、切换提供方、操作记录。
 * 开关网络、切换提供方是高危操作：原因必填、写审计（TY-3）；两步确认在页面上做。
 */
@Service
class PaymentOps {

  record ProviderView(String code, String name, boolean connected, boolean current, boolean up, String lastOkAt, String lastCallbackAt, String available,
      String balanceAt, String error) {}

  record NetworkView(String network, boolean open) {}

  record AuditView(String id, String admin, String action, String detail, String reason, String at) {}

  record Overview(List<ProviderView> providers, List<NetworkView> networks, List<AuditView> audits) {}

  private final PaymentAdminApi payments;
  private final AdminNames names;
  private final Audit audit;

  PaymentOps(PaymentAdminApi payments, AdminNames names, Audit audit) {
    this.payments = payments;
    this.names = names;
    this.audit = audit;
  }

  static ProviderView view(PaymentAdminApi.Provider p) {
    return new ProviderView(p.code(), p.name(), p.connected(), p.current(), p.up(), iso(p.lastOkAt()), iso(p.lastCallbackAt()), Ops.money(p.available()),
        iso(p.balanceAt()), p.error());
  }

  private static String iso(java.time.Instant t) {
    return t == null ? null : t.truncatedTo(ChronoUnit.SECONDS).toString();
  }

  Overview overview() {
    var audits = payments.audits(50);
    Map<Long, String> who = names.of(audits.stream().map(PaymentAdminApi.Audit::adminId).toList());
    return new Overview(payments.providers().stream().map(PaymentOps::view).toList(),
        payments.networks().stream().map(n -> new NetworkView(n.network().name(), n.open())).toList(),
        audits.stream().map(a -> new AuditView(String.valueOf(a.id()), who.getOrDefault(a.adminId(), String.valueOf(a.adminId())), a.action(), a.detail(), a.reason(),
            Ops.time(a.createdAt()))).toList());
  }

  ProviderView check() {
    return view(payments.check());
  }

  void setNetwork(AdminContext actor, String network, Boolean open, String reason) {
    PayNetwork n = PayNetwork.parse(network);
    if (n == null) throw new BizException("NOT_FOUND", "没有这条网络");
    if (open == null) throw new app.trading.common.error.ValidationException(List.of(new app.trading.common.error.ValidationException.Field("open", "required")));
    String r = Audit.requireReason(reason);
    audit.run(open ? "payment.network_on" : "payment.network_off", n.name(), true, r, () -> {
      boolean before = payments.networks().stream().filter(x -> x.network() == n).findFirst().map(PaymentAdminApi.NetworkSwitch::open).orElse(true);
      payments.setNetwork(n, open, actor.adminId(), r);
      return Audit.Change.of(null, Map.of("open", before), Map.of("open", open));
    });
  }

  void switchProvider(AdminContext actor, String provider, String reason) {
    String r = Audit.requireReason(reason);
    audit.run("payment.switch_provider", String.valueOf(provider), true, r, () -> {
      String before = payments.providers().stream().filter(PaymentAdminApi.Provider::current).map(PaymentAdminApi.Provider::code).findFirst().orElse(null);
      payments.switchProvider(provider, actor.adminId(), r);
      return Audit.Change.of(null, Map.of("provider", String.valueOf(before)), Map.of("provider", provider));
    });
  }
}
