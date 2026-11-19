package app.trading.payment.core;

import app.trading.common.error.BizException;
import app.trading.common.error.ValidationException;
import app.trading.payment.api.PayNetwork;
import app.trading.payment.api.PaymentAdminApi;
import app.trading.payment.api.PaymentApi;
import app.trading.payment.api.PaymentListener;
import app.trading.payment.api.PayoutState;
import app.trading.payment.provider.PaymentProvider;
import app.trading.payment.topup.TopUpProvider;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 支付接口层的实现：7 个动作、回调、后台钱包提供方页。
 * <ul>
 *   <li>访问提供方（网络）的地方都不在数据库事务里：先在短事务里记下要做什么，提交以后再调用提供方，回来再开短事务记结果（"TopUP 超时不在事务里"）。</li>
 *   <li>提供方在地址生成、付款提交时绑定：切换提供方以后，老地址的到账照常入账、进行中的付款由原提供方付完（AC-W7）。</li>
 *   <li>防重复付款：同一笔提现永远用同一个防重键；TopUP 的防重键只保留 24 小时（TG-1 没补之前），第一次提交 23 小时后还没拿到付款编号就不再自动重提，
 *       记"未知"等人查（不退款）。</li>
 * </ul>
 */
@Service
public class PaymentService implements PaymentApi, PaymentAdminApi {

  private static final Logger log = LoggerFactory.getLogger(PaymentService.class);
  static final String KEY_PROVIDER = "payment.provider";
  static final String KEY_NETWORK = "payment.network.";
  /** 第一次提交以后多久还没拿到付款编号就不再自动重提（TopUP 防重键 24 小时失效，留 1 小时余量） */
  static final Duration RESUBMIT_WINDOW = Duration.ofHours(23);
  /** 回调多久没更新就主动向提供方查一次（AC-W6：恢复后 10 分钟内补上） */
  static final Duration QUERY_AFTER = Duration.ofMinutes(5);
  /** 查询被提供方拒绝（4xx）时后台显示的提示开头 */
  static final String QUERY_REFUSED = "查询失败：提供方拒绝";
  /** 提供方明确说"收款地址不对"：确定没付，可以退款 */
  static final Set<String> REFUSE_CODES = Set.of("invalid_address", "address_not_registered");

  private final PaymentMapper mapper;
  private final ProviderRegistry registry;
  private final ObjectProvider<PaymentListener> listener;
  private final TransactionTemplate tx;
  private final Clock clock;
  private volatile Balance balance;

  PaymentService(PaymentMapper mapper, ProviderRegistry registry, ObjectProvider<PaymentListener> listener, TransactionTemplate tx, Clock clock) {
    this.mapper = mapper;
    this.registry = registry;
    this.listener = listener;
    this.tx = tx;
    this.clock = clock;
  }

  LocalDateTime now() {
    return LocalDateTime.now(clock.withZone(ZoneOffset.UTC));
  }

  /** 提供方那边的客户标识 = Trading 客户号 */
  static String customerId(long userId) {
    return String.valueOf(userId);
  }

  public String currentCode() {
    String c = mapper.config(KEY_PROVIDER);
    return c == null || c.isBlank() ? TopUpProvider.CODE : c;
  }

  PaymentProvider current() {
    return registry.get(currentCode()).filter(PaymentProvider::connected).orElseThrow(() -> new Unavailable("钱包提供方 " + currentCode() + " 没有接入或没有配置", null));
  }

  PaymentProvider provider(String code) {
    return registry.get(code).orElseThrow(() -> new Unavailable("钱包提供方 " + code + " 不存在", null));
  }

  /** 调用提供方，记下成功或失败（健康检查、后台页用）。不能在事务里调用 */
  <T> T call(PaymentProvider p, java.util.function.Supplier<T> f) {
    if (TransactionSynchronizationManager.isActualTransactionActive()) throw new IllegalStateException("不能在数据库事务里访问钱包提供方");
    try {
      T out = f.get();
      registry.ok(p.code(), clock.instant());
      return out;
    } catch (Unavailable e) {
      registry.failed(p.code(), clock.instant(), e.getMessage());
      throw e;
    } catch (PaymentProvider.ProviderError e) {
      // 提供方回了明确的错误：接口是通的
      registry.ok(p.code(), clock.instant());
      throw e;
    }
  }

  // ---------- 1、2 开户、取地址 ----------

  @Override
  public void openAccount(long userId) {
    PaymentProvider p = current();
    call(p, () -> {
      p.ensureCustomer(customerId(userId));
      return null;
    });
  }

  @Override
  public Optional<String> existingAddress(long userId, PayNetwork network) {
    return Optional.ofNullable(mapper.address(userId, network.name())).map(PaymentMapper.AddressRow::address);
  }

  @Override
  public String address(long userId, PayNetwork network) {
    if (!networkOpen(network)) throw new BizException("NETWORK_CLOSED", "这条网络暂时不能充值");
    Optional<String> have = existingAddress(userId, network);
    if (have.isPresent()) return have.get();
    PaymentProvider p = current();
    String cid = customerId(userId);
    String addr;
    try {
      addr = call(p, () -> {
        p.ensureCustomer(cid);
        return p.address(cid, network);
      });
    } catch (PaymentProvider.ProviderError e) {
      log.warn("向 {} 要充值地址失败 user={} network={}：{}", p.code(), userId, network, e.getMessage());
      throw new Unavailable("钱包提供方没有给出地址：" + e.code, e);
    }
    mapper.insertAddress(userId, network.name(), p.code(), cid, addr);
    log.info("生成充值地址 user={} network={} provider={}", userId, network, p.code());
    return mapper.address(userId, network.name()).address();
  }

  boolean networkOpen(PayNetwork n) {
    return !"false".equalsIgnoreCase(mapper.config(KEY_NETWORK + n.name()));
  }

  @Override
  public List<PayNetwork> openNetworks() {
    return java.util.Arrays.stream(PayNetwork.values()).filter(this::networkOpen).toList();
  }

  // ---------- 4、5 代付、查付款 ----------

  @Override
  public Submit payout(long withdrawalId, long withdrawalNo, long userId, PayNetwork network, String address, BigDecimal amount) {
    PaymentMapper.PayoutRow row = mapper.payout(withdrawalId);
    if (row == null) {
      PaymentProvider p;
      try {
        p = current();
      } catch (Unavailable e) {
        return new Retry(e.getMessage());
      }
      PaymentMapper.PayoutRow n = new PaymentMapper.PayoutRow();
      n.withdrawalId = withdrawalId;
      n.userId = userId;
      n.provider = p.code();
      n.idemKey = "trading-wd-" + withdrawalNo;
      n.merchantRef = String.valueOf(withdrawalNo);
      n.network = network.name();
      n.address = address;
      n.amount = amount;
      n.status = PayoutState.SUBMITTING.name();
      n.firstSubmitAt = now();
      mapper.insertPayout(n);
      row = mapper.payout(withdrawalId);
    }
    PayoutState st = PayoutState.valueOf(row.status);
    if (row.providerRef != null || st.terminal()) return st.notPaid() ? new Refused(row.reason == null ? st.name() : row.reason) : new Accepted(row.providerRef, st);
    if (st == PayoutState.UNKNOWN) return new Retry("付款状态未知，等人工核对");
    if (Duration.between(row.firstSubmitAt, now()).compareTo(RESUBMIT_WINDOW) > 0) {
      // TopUP 的防重键 24 小时后失效，再提可能付两次（TG-1）：不再自动重提，记"未知"
      final long id = row.id;
      tx.executeWithoutResult(s -> mapper.savePayout(id, null, PayoutState.UNKNOWN.name(), null, "第一次提交超过 23 小时还没拿到付款编号，不再自动重提，请到提供方后台核对", now()));
      log.error("提现 {} 第一次提交超过 23 小时还没拿到付款编号，转人工核对", withdrawalId);
      return new Retry("超过防重有效期，转人工核对");
    }
    PaymentProvider p = provider(row.provider);
    final PaymentMapper.PayoutRow r = row;
    mapper.submitted(r.id, now());
    PaymentProvider.Submitted got;
    try {
      got = call(p, () -> p.submitPayout(r.idemKey, r.merchantRef, customerId(r.userId), PayNetwork.valueOf(r.network), r.address, r.amount));
    } catch (Unavailable e) {
      log.warn("提现 {} 交给 {} 失败（稍后用同一个防重键重提）：{}", withdrawalId, p.code(), e.getMessage());
      return new Retry(e.getMessage());
    } catch (PaymentProvider.DuplicateRef e) {
      return adopt(p, r, e.existingRef);
    } catch (PaymentProvider.ProviderError e) {
      if (REFUSE_CODES.contains(e.code)) {
        tx.executeWithoutResult(s -> mapper.savePayout(r.id, null, PayoutState.REJECTED.name(), null, "提供方拒收：" + e.code, now()));
        return new Refused("提供方拒收：" + e.code);
      }
      log.error("提现 {} 交给 {} 被拒（不退款，稍后重提）：{}", withdrawalId, p.code(), e.getMessage());
      return new Retry("提供方返回 " + e.code);
    }
    PayoutState after = tx.execute(s -> {
      PaymentMapper.PayoutRow l = mapper.lockPayout(withdrawalId);
      PayoutState cur = PayoutState.valueOf(l.status);
      PayoutState next = advance(cur, got.state()) ? got.state() : cur;
      mapper.savePayout(l.id, got.ref(), next.name(), null, null, now());
      return next;
    });
    log.info("提现 {} 已交 {}，付款编号 {}，状态 {}", withdrawalId, p.code(), got.ref(), after);
    return after.notPaid() ? new Refused(after.name()) : new Accepted(got.ref(), after);
  }

  /**
   * 提交时提供方说"商户单号已经用过"（TopUP v1.1，带原来那笔的付款编号）：多半是 Trading 自己早先那次提交——返回丢了、
   * 防重键又过了 24 小时。查那笔付款，金额、链、收款地址都和这笔提现一样 → 认领它的付款编号，之后照常跟踪状态（不会再建一笔）；
   * 对不上（或者提供方查不到）→ 记"未知"并写明原因，转人工核对，不退款、不重提。查询本身失败 → 稍后重试
   */
  private Submit adopt(PaymentProvider p, PaymentMapper.PayoutRow r, String existingRef) {
    Optional<PaymentProvider.PayoutView> v;
    try {
      v = call(p, () -> p.payout(existingRef));
    } catch (Unavailable | PaymentProvider.ProviderError e) {
      log.warn("提现 {} 的商户单号在 {} 已经用过（付款编号 {}），查这笔付款失败，稍后重试：{}", r.withdrawalId, p.code(), existingRef, e.getMessage());
      return new Retry("商户单号已用过，查原来那笔失败");
    }
    String why = v.isEmpty() ? "提供方查不到这笔付款" : payoutMismatch(r, v.get());
    if (why != null) {
      String reason = "提供方说商户单号 " + r.merchantRef + " 已经被付款 " + existingRef + " 用过，但" + why + "：不是这笔提现的付款，请到提供方后台核对";
      tx.executeWithoutResult(s -> mapper.savePayout(r.id, null, PayoutState.UNKNOWN.name(), null, reason, now()));
      log.error("提现 {}：{}", r.withdrawalId, reason);
      return new Retry("商户单号冲突，转人工核对");
    }
    PaymentProvider.PayoutView got = v.get();
    PayoutState after = tx.execute(s -> {
      PaymentMapper.PayoutRow l = mapper.lockPayout(r.withdrawalId);
      PayoutState cur = PayoutState.valueOf(l.status);
      PayoutState next = advance(cur, got.state()) ? got.state() : cur;
      mapper.savePayout(l.id, existingRef, next.name(), got.txHash(), null, now());
      return next;
    });
    log.warn("提现 {}：商户单号在 {} 已经用过，核对金额、链、地址一致，认领原来的付款 {}（状态 {}）", r.withdrawalId, p.code(), existingRef, after);
    return after.notPaid() ? new Refused(after.name()) : new Accepted(existingRef, after);
  }

  /** 提供方的一笔付款是不是这笔提现的：金额、链、收款地址都一样返回 null，否则返回原因 */
  static String payoutMismatch(PaymentMapper.PayoutRow row, PaymentProvider.PayoutView v) {
    if (v.amount() == null || row.amount.compareTo(v.amount()) != 0) return "金额不一样（这笔 " + row.amount.toPlainString() + "，那笔 " + v.amount() + "）";
    if (v.network() == null || !row.network.equals(v.network().name())) return "链不一样（这笔 " + row.network + "，那笔 " + v.network() + "）";
    if (!sameAddress(PayNetwork.valueOf(row.network), row.address, v.address())) return "收款地址不一样";
    return null;
  }

  /** 收款地址是否相同：TRON 的 Base58 区分大小写；以太坊、BSC 的十六进制地址大小写只是校验写法，不区分 */
  public static boolean sameAddress(PayNetwork network, String a, String b) {
    if (a == null || b == null) return false;
    return network == PayNetwork.TRC20 ? a.equals(b) : a.equalsIgnoreCase(b);
  }

  /** 状态只往前走；"未知"可以被任何确定的状态替换 */
  static boolean advance(PayoutState cur, PayoutState next) {
    if (next == null || next == cur) return false;
    if (cur == PayoutState.UNKNOWN) return next != PayoutState.SUBMITTING;
    if (cur.terminal()) return false;
    if (next == PayoutState.UNKNOWN) return true;
    return next.rank > cur.rank;
  }

  @Override
  public Optional<PayoutState> payoutState(long withdrawalId) {
    PaymentMapper.PayoutRow row = mapper.payout(withdrawalId);
    if (row == null) return Optional.empty();
    PayoutState st = PayoutState.valueOf(row.status);
    if (st.terminal() || row.providerRef == null) return Optional.of(st);
    LocalDateTime last = row.lastQueryAt == null || row.updatedAt.isAfter(row.lastQueryAt) ? row.updatedAt : row.lastQueryAt;
    if (Duration.between(last, now()).compareTo(QUERY_AFTER) < 0) return Optional.of(st);
    try {
      return Optional.of(query(row, false));
    } catch (Unavailable e) {
      log.warn("查提现 {} 的付款状态失败：{}", withdrawalId, e.getMessage());
      return Optional.of(st);
    }
  }

  @Override
  public PayoutState refreshPayout(long withdrawalId) {
    PaymentMapper.PayoutRow row = mapper.payout(withdrawalId);
    if (row == null) throw new BizException("NOT_FOUND", "这笔提现还没有交给钱包提供方");
    if (row.providerRef == null) return PayoutState.valueOf(row.status);
    return query(row, true);
  }

  /**
   * 向提供方查一次。提供方说没有这笔 → 未知（不退款）。notify：查到变化时通知钱包模块（后台"重新查询"）。
   * 提供方拒绝这次查询（4xx，例如 Key 配错）：是我们的配置或请求有问题，不说明这笔付款怎样了 → 状态不变、不退款，记告警和后台提示，下次再查。
   */
  private PayoutState query(PaymentMapper.PayoutRow row, boolean notify) {
    PaymentProvider p = provider(row.provider);
    Optional<PaymentProvider.PayoutView> v;
    try {
      v = call(p, () -> p.payout(row.providerRef));
    } catch (PaymentProvider.ProviderError e) {
      if (e.status >= 400 && e.status < 500) {
        String note = QUERY_REFUSED + "（" + e.status + (e.code == null || e.code.isBlank() ? "" : " " + e.code) + "）";
        log.warn("查提现 {} 的付款状态被 {} 拒绝（{} {}），多半是 Key 或请求配置不对；付款状态不变、不退款，下次再查", row.withdrawalId, p.code(), e.status, e.code);
        mapper.queryRefused(row.id, note, now());
        return PayoutState.valueOf(mapper.payout(row.withdrawalId).status);
      }
      v = null;
    }
    mapper.queried(row.id, now());
    PayoutState got = v == null || v.isEmpty() ? PayoutState.UNKNOWN : v.get().state();
    String txHash = v == null || v.isEmpty() ? null : v.get().txHash();
    String reason = v == null || v.isEmpty() ? "提供方查不到这笔付款" : v.get().reason();
    return tx.execute(s -> applyPayout(row.withdrawalId, got, txHash, reason, notify));
  }

  /** 在事务里：付款状态往前走就记下，notify 时通知钱包模块。返回现在的状态 */
  private PayoutState applyPayout(long withdrawalId, PayoutState got, String txHash, String reason, boolean notify) {
    PaymentMapper.PayoutRow l = mapper.lockPayout(withdrawalId);
    mapper.clearQueryRefused(l.id);
    PayoutState cur = PayoutState.valueOf(l.status);
    if (!advance(cur, got)) return cur;
    mapper.savePayout(l.id, null, got.name(), txHash, reason, now());
    if (notify) listener().onPayout(new PaymentListener.Payout(withdrawalId, l.provider, l.providerRef, got, txHash, reason));
    return got;
  }

  @Override
  public Optional<PayoutInfo> payoutInfo(long withdrawalId) {
    return Optional.ofNullable(mapper.payout(withdrawalId)).map(PaymentService::info);
  }

  @Override
  public List<PayoutInfo> payoutInfos(List<Long> withdrawalIds) {
    if (withdrawalIds == null || withdrawalIds.isEmpty()) return List.of();
    return mapper.payouts(withdrawalIds).stream().map(PaymentService::info).toList();
  }

  static PayoutInfo info(PaymentMapper.PayoutRow r) {
    return new PayoutInfo(r.withdrawalId, r.provider, r.providerRef, PayoutState.valueOf(r.status), r.txHash, r.reason, r.firstSubmitAt.toInstant(ZoneOffset.UTC),
        r.updatedAt == null ? null : r.updatedAt.toInstant(ZoneOffset.UTC));
  }

  // ---------- 6、7 余额、流水 ----------

  @Override
  public Balance balance() {
    Balance b = balance;
    return b == null ? new Balance(currentCode(), null, null, null) : b;
  }

  /** 向当前提供方查一次余额（定时任务、后台"重新检查连接"）；查不到时保留上一次查到的值（R12 看查询时间判断新不新） */
  Balance refreshBalance() {
    PaymentProvider p = current();
    PaymentProvider.BalanceView v = call(p, p::balance);
    Balance b = new Balance(p.code(), v.available(), v.frozen(), clock.instant());
    balance = b;
    return b;
  }

  @Override
  public List<ProviderDeposit> depositsOf(long userId, String provider) {
    PaymentProvider p = provider(provider);
    return call(p, () -> p.deposits(customerId(userId))).stream()
        .map(d -> new ProviderDeposit(d.ref(), d.txHash(), d.network(), d.address(), d.amount(), d.time())).toList();
  }

  @Override
  public Optional<ProviderPayout> lookupPayout(String provider, String providerRef) {
    PaymentProvider p = provider(provider);
    return call(p, () -> p.payout(providerRef)).map(v -> new ProviderPayout(v.ref(), v.state(), v.amount(), v.network(), v.address(), v.txHash()));
  }

  // ---------- 回调 ----------

  /** 回调处理结果：HTTP 状态 */
  public enum CallbackResult { OK, DUPLICATE, IGNORED, BAD_SIGNATURE, UNKNOWN_PROVIDER }

  /**
   * 收到提供方的回调：验签 → 按事件 ID 去重 → 交给钱包模块。去重记录和入账在同一个事务里：处理失败时整个回滚，提供方重试时再处理一次。
   */
  public CallbackResult callback(String providerCode, Map<String, String> headers, String body) {
    Optional<PaymentProvider> p = registry.get(providerCode);
    if (p.isEmpty()) return CallbackResult.UNKNOWN_PROVIDER;
    Optional<PaymentProvider.Event> ev;
    try {
      ev = p.get().parseCallback(headers, body);
    } catch (PaymentProvider.BadSignature e) {
      log.warn("拒收 {} 的回调：{}", providerCode, e.getMessage());
      return CallbackResult.BAD_SIGNATURE;
    }
    if (ev.isEmpty()) return CallbackResult.IGNORED;
    PaymentProvider.Event e = ev.get();
    return tx.execute(s -> {
      String type = switch (e) {
        case PaymentProvider.DepositEvent d -> "deposit";
        case PaymentProvider.PayoutEvent d -> "payout";
        case PaymentProvider.ReversalEvent d -> "reversal";
      };
      String ref = switch (e) {
        case PaymentProvider.DepositEvent d -> d.customerId();
        case PaymentProvider.PayoutEvent d -> d.ref();
        case PaymentProvider.ReversalEvent d -> d.depositRef();
      };
      if (mapper.insertCallback(providerCode, e.eventId(), type, ref) != 1) return CallbackResult.DUPLICATE;
      boolean used = switch (e) {
        case PaymentProvider.DepositEvent d -> deposit(providerCode, d);
        case PaymentProvider.PayoutEvent d -> payoutEvent(providerCode, d);
        case PaymentProvider.ReversalEvent d -> {
          listener().onDepositReversed(providerCode, d.depositRef());
          yield true;
        }
      };
      return used ? CallbackResult.OK : CallbackResult.IGNORED;
    });
  }

  private boolean deposit(String provider, PaymentProvider.DepositEvent d) {
    PaymentMapper.AddressRow a = mapper.addressByValue(d.network().name(), d.address());
    Long user = a != null ? Long.valueOf(a.userId()) : parseUser(d.customerId());
    if (user == null) {
      log.warn("{} 到账回调的客户不是 Trading 的：customer={} tx={}", provider, d.customerId(), d.txHash());
      return false;
    }
    listener().onDeposit(new PaymentListener.Deposit(provider, d.eventId(), user, d.network(), d.address(), d.txHash(), d.amount(), d.fee()));
    return true;
  }

  private static Long parseUser(String customerId) {
    try {
      return customerId != null && customerId.matches("\\d{1,18}") ? Long.valueOf(customerId) : null;
    } catch (NumberFormatException e) {
      return null;
    }
  }

  private boolean payoutEvent(String provider, PaymentProvider.PayoutEvent d) {
    PaymentMapper.PayoutRow row = d.ref() == null ? null : mapper.payoutByRef(provider, d.ref());
    if (row == null && d.merchantRef() != null && !d.merchantRef().isBlank()) row = mapper.payoutByMerchantRef(provider, d.merchantRef());
    if (row == null) {
      log.warn("{} 付款回调找不到对应的提现：ref={} merchantRef={}", provider, d.ref(), d.merchantRef());
      return false;
    }
    // 回调比提交的返回先到：先记下付款编号
    if (row.providerRef == null && d.ref() != null) mapper.savePayout(row.id, d.ref(), row.status, null, null, now());
    applyPayout(row.withdrawalId, d.state(), d.txHash(), d.reason(), true);
    return true;
  }

  private PaymentListener listener() {
    PaymentListener l = listener.getIfAvailable();
    if (l == null) throw new IllegalStateException("没有处理提供方通知的钱包模块");
    return l;
  }

  // ---------- 后台：钱包提供方页 ----------

  @Override
  public List<Provider> providers() {
    String cur = currentCode();
    List<Provider> out = new ArrayList<>();
    for (PaymentProvider p : registry.all()) {
      ProviderRegistry.Health h = registry.health(p.code());
      LocalDateTime cb = mapper.lastCallback(p.code());
      Balance b = p.code().equals(cur) ? balance() : null;
      boolean up = p.connected() && h.failingSince == null;
      out.add(new Provider(p.code(), p.name(), p.connected(), p.code().equals(cur), up, h.lastOk, cb == null ? null : cb.toInstant(ZoneOffset.UTC),
          b == null ? null : b.available(), b == null ? null : b.checkedAt(), h.lastError));
    }
    return out;
  }

  @Override
  public List<NetworkSwitch> networks() {
    return java.util.Arrays.stream(PayNetwork.values()).map(n -> new NetworkSwitch(n, networkOpen(n))).toList();
  }

  private static String reason(String reason) {
    if (reason == null || reason.isBlank()) throw new ValidationException(List.of(new ValidationException.Field("reason", "required")));
    if (reason.length() > 500) throw new ValidationException(List.of(new ValidationException.Field("reason", "tooLong")));
    return reason.trim();
  }

  @Override
  public void setNetwork(PayNetwork network, boolean open, long adminId, String reason) {
    String why = reason(reason);
    tx.executeWithoutResult(s -> {
      mapper.saveConfig(KEY_NETWORK + network.name(), String.valueOf(open));
      mapper.insertAudit(adminId, open ? "NETWORK_ON" : "NETWORK_OFF", network.name(), why);
    });
  }

  @Override
  public void switchProvider(String code, long adminId, String reason) {
    String why = reason(reason);
    PaymentProvider p = registry.get(code).orElseThrow(() -> new BizException("NOT_FOUND", "没有这个提供方"));
    if (!p.connected()) throw new BizException("PROVIDER_NOT_CONNECTED", p.name() + " 还没有接入，不能切换");
    String from = currentCode();
    if (from.equals(code)) throw new BizException("PROVIDER_ALREADY_CURRENT", "已经在用 " + p.name());
    tx.executeWithoutResult(s -> {
      mapper.saveConfig(KEY_PROVIDER, code);
      mapper.insertAudit(adminId, "SWITCH_PROVIDER", from + " → " + code, why);
    });
    balance = null;
    log.warn("钱包提供方从 {} 切换到 {}（admin {}）", from, code, adminId);
  }

  @Override
  public List<Audit> audits(int limit) {
    return mapper.audits(Math.min(Math.max(limit, 1), 200)).stream().map(a -> new Audit(a.id(), a.adminId(), a.action(), a.detail(), a.reason(), a.createdAt())).toList();
  }

  @Override
  public Provider check() {
    try {
      refreshBalance();
    } catch (RuntimeException e) {
      log.warn("检查钱包提供方失败：{}", e.getMessage());
    }
    String cur = currentCode();
    return providers().stream().filter(p -> p.code().equals(cur)).findFirst().orElseThrow();
  }

  /** 当前提供方连续不通多久了（没有不通时为空）；健康检查用 */
  Optional<Instant> failingSince() {
    return Optional.ofNullable(registry.health(currentCode()).failingSince);
  }
}
