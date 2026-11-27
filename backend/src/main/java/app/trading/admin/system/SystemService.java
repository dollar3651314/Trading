package app.trading.admin.system;

import app.trading.admin.security.AdminContext;
import app.trading.admin.security.Audit;
import app.trading.admin.security.IpRanges;
import app.trading.admin.security.Menus;
import app.trading.admin.security.Permission;
import app.trading.common.config.ConfigKey;
import app.trading.common.config.SystemConfig;
import app.trading.common.error.BizException;
import app.trading.common.error.ValidationException;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 菜单、权限点字典、系统配置（后台方案第 2 节、G3、G7）。 */
@Service
class SystemService {

  /** 字段和原型 MenuNode 一致 */
  record MenuNode(String key, String path, String labelKey, String permission, int order, boolean visible, List<MenuNode> children) {}

  /** 字段和原型 PermissionDef 一致 */
  record PermissionView(String code, String module, boolean highRisk) {}

  /** 字段和原型 SystemConfig 一致 */
  record ConfigView(String key, String value, String unit, String group, String updatedAt) {}

  private final SystemMapper mapper;
  private final SystemConfig config;
  private final Audit audit;
  private final Clock clock;

  /**
   * TB-37 资金配置的下限和上限（第 4 批 4c）：白名单生效 ≥ 24 小时、冷静期 ≥ 2 小时、每天提现累计上限 ≤ 30,000（TG 每天额度的上限 2,000 在
   * {@link ConfigKey} 里）。端到端测试（profile e2e，E2eGuard 保证只在本机和 CI）不受下限限制，主流程要把冷静期、生效时间设成 0
   */
  static final Map<ConfigKey, java.math.BigDecimal> FLOORS = Map.of(ConfigKey.WHITELIST_EFFECTIVE_HOURS, java.math.BigDecimal.valueOf(24),
      ConfigKey.WITHDRAW_COOLING_HOURS, java.math.BigDecimal.valueOf(2));
  static final Map<ConfigKey, java.math.BigDecimal> CEILINGS = Map.of(ConfigKey.WITHDRAW_DAILY_LIMIT, java.math.BigDecimal.valueOf(30000));
  /** 改了要发告警邮件的资金配置（TB-37：改值发告警；告警收件地址只在服务器 FX_ALERT_EMAILS） */
  static final java.util.Set<String> FUNDS_GROUPS = java.util.Set.of("withdraw", "telegram");

  private final boolean e2e;
  private final app.trading.notification.api.MailApi mail;
  private final app.trading.common.health.AlertProperties alerts;
  private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(SystemService.class);

  SystemService(SystemMapper mapper, SystemConfig config, Audit audit, Clock clock, org.springframework.core.env.Environment env,
      app.trading.notification.api.MailApi mail, app.trading.common.health.AlertProperties alerts) {
    this.e2e = env.acceptsProfiles(org.springframework.core.env.Profiles.of(app.trading.common.e2e.E2eGuard.PROFILE));
    this.mail = mail;
    this.alerts = alerts;
    this.mapper = mapper;
    this.config = config;
    this.audit = audit;
    this.clock = clock;
  }

  /** TB-37：资金配置改值发告警邮件（OPS_ALERT；收件人为空只记日志，写邮件任务失败不影响保存） */
  private void fundsAlert(AdminContext actor, ConfigKey k, String old, String v, String reason) {
    String detail = k.key + "：" + (old == null ? "（默认）" : old) + " → " + v + "；操作人 " + actor.username() + "；原因：" + reason;
    log.warn("资金配置已修改：{}", detail);
    Map<String, String> p = Map.of("itemZh", "资金配置已修改：" + k.key, "itemEn", "Funds setting changed: " + k.key, "detail",
        detail + "。如果不是你们安排的修改，请立即改回并检查后台账号。", "since", LocalDateTime.now(clock).toInstant(java.time.ZoneOffset.UTC).toString());
    for (String email : alerts.recipients()) {
      try {
        mail.send(email, "OPS_ALERT", p);
      } catch (RuntimeException e) {
        log.error("资金配置修改的告警邮件写入发送任务失败", e);
      }
    }
  }

  // ---------- 菜单 ----------

  /** 全部菜单（菜单管理页）：默认顺序 10、20、30……，后台可以改顺序和是否显示 */
  @Transactional(readOnly = true)
  List<MenuNode> menus() {
    Map<String, SystemMapper.MenuRow> saved = mapper.menus().stream().collect(Collectors.toMap(m -> m.menuKey, Function.identity()));
    return nodes(Menus.TREE, saved);
  }

  /** 当前管理员能看到的菜单：显示的、有权限的叶子；分组下一个都看不到时整个分组不显示（AC-32） */
  List<MenuNode> visibleFor(AdminContext ctx) {
    return filter(menus(), ctx);
  }

  private static List<MenuNode> filter(List<MenuNode> nodes, AdminContext ctx) {
    List<MenuNode> out = new ArrayList<>();
    for (MenuNode n : nodes) {
      if (!n.visible()) continue;
      if (n.children().isEmpty()) {
        if (n.permission() != null && Permission.of(n.permission()).map(ctx::has).orElse(false)) out.add(n);
      } else {
        List<MenuNode> kids = filter(n.children(), ctx);
        if (!kids.isEmpty()) out.add(new MenuNode(n.key(), n.path(), n.labelKey(), n.permission(), n.order(), true, kids));
      }
    }
    return out;
  }

  private static List<MenuNode> nodes(List<Menus.Item> items, Map<String, SystemMapper.MenuRow> saved) {
    List<MenuNode> out = new ArrayList<>();
    for (int i = 0; i < items.size(); i++) {
      Menus.Item it = items.get(i);
      SystemMapper.MenuRow s = saved.get(it.key());
      out.add(new MenuNode(it.key(), it.path(), it.labelKey(), it.permission() == null ? null : it.permission().code, s == null ? (i + 1) * 10 : s.sortOrder,
          s == null || s.visible, nodes(it.children(), saved)));
    }
    out.sort(Comparator.comparingInt(MenuNode::order));
    return out;
  }

  private static boolean exists(List<Menus.Item> items, String key) {
    for (Menus.Item it : items) if (it.key().equals(key) || exists(it.children(), key)) return true;
    return false;
  }

  @Transactional
  void updateMenu(String key, Integer order, Boolean visible) {
    audit.run("menu.update", key, false, null, () -> {
      if (!exists(Menus.TREE, key)) throw new BizException("NOT_FOUND", "菜单不存在");
      if (order == null || order < 0 || order > 100_000) throw new ValidationException(List.of(new ValidationException.Field("order", "invalid")));
      MenuNode before = find(menus(), key);
      boolean v = visible == null ? before.visible() : visible;
      mapper.saveMenu(key, order, v);
      return Audit.Change.of(null, Map.of("order", before.order(), "visible", before.visible()), Map.of("order", order, "visible", v));
    });
  }

  private static MenuNode find(List<MenuNode> nodes, String key) {
    for (MenuNode n : nodes) {
      if (n.key().equals(key)) return n;
      MenuNode c = find(n.children(), key);
      if (c != null) return c;
    }
    return null;
  }

  // ---------- 权限点 ----------

  List<PermissionView> permissions() {
    return Arrays.stream(Permission.values()).map(p -> new PermissionView(p.code, p.module, p.highRisk)).toList();
  }

  // ---------- 系统配置 ----------

  List<ConfigView> configs() {
    return config.list().stream().filter(e -> e.key().configPage).map(e -> new ConfigView(e.key().key, e.value(), e.key().unit.api(), e.key().group, AdminUserService.time(e.updatedAt()))).toList();
  }

  /**
   * 改系统配置（高危，必须填原因）。值按单位校验；充值确认数不能低于现在的默认值；
   * IP 白名单每一项都要是合法的 IP 或网段，并且<b>必须包含当前操作人的 IP</b>，防止把自己锁在外面（G3）。
   */
  @Transactional
  ConfigView updateConfig(AdminContext actor, String key, String value, String reason) {
    String r = Audit.requireReason(reason);
    return audit.run("config.update", key, true, r, () -> {
      ConfigKey k = ConfigKey.of(key).filter(x -> x.configPage).orElseThrow(() -> new BizException("NOT_FOUND", "配置项不存在"));
      String v = k.normalize(value).orElseThrow(() -> new ValidationException(List.of(new ValidationException.Field("value", "invalid"))));
      if (!e2e && FLOORS.containsKey(k) && new java.math.BigDecimal(v).compareTo(FLOORS.get(k)) < 0) {
        throw new ValidationException(List.of(new ValidationException.Field("value", "belowFloor")));
      }
      if (!e2e && CEILINGS.containsKey(k) && new java.math.BigDecimal(v).compareTo(CEILINGS.get(k)) > 0) {
        throw new ValidationException(List.of(new ValidationException.Field("value", "aboveCeiling")));
      }
      // 第 4 批（冻结、找回流程）上线前不能对所有人开放开户（TG 机器人第 2 批审查 🟡2；第 4 批合并时把 ConfigKey.SIGNUP_ALL_ALLOWED 改成 true）
      if (k == ConfigKey.TELEGRAM_SIGNUP_ALLOW_IDS && "*".equals(v) && !ConfigKey.SIGNUP_ALL_ALLOWED) {
        throw new BizException("SIGNUP_ALL_LOCKED", "冻结和找回流程上线前不能对所有人开放开户");
      }
      if (k == ConfigKey.ADMIN_IP_WHITELIST && !v.isEmpty()) {
        IpRanges ranges = IpRanges.parse(v).orElseThrow(() -> new ValidationException(List.of(new ValidationException.Field("value", "invalid"))));
        if (!ranges.contains(actor.ip())) throw new BizException("WHITELIST_EXCLUDES_SELF", "白名单不包含你当前的 IP，保存后你会无法访问后台");
      }
      String old = config.set(k, v, actor.adminId(), LocalDateTime.now(clock));
      if (FUNDS_GROUPS.contains(k.group) && !v.equals(old)) fundsAlert(actor, k, old, v, r);
      ConfigView view = configs().stream().filter(c -> c.key().equals(k.key)).findFirst().orElseThrow();
      return Audit.Change.of(view, Map.of("value", old == null ? "" : old), Map.of("value", v));
    });
  }
}
