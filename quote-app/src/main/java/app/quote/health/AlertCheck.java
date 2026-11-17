package app.quote.health;

import java.util.List;

/**
 * 报价进程的业务告警项（报价源断线、切到备源、两家偏差，见 {@code feed.QuoteFeedAlert}），和 trading-app 的告警检查同样的字段。
 * 只进健康检查的 {@code alerts} 和内部 WebSocket 的心跳（trading-app 收到后照样发告警邮件），<b>不影响 status</b>：
 * 外部报价源故障换版本也解决不了，不能让部署回滚。检查必须很快（只看内存里的状态）。
 */
public interface AlertCheck {

  /**
   * @param name 例如 {@code quote.alltick}、{@code quote.failover}、{@code quote.deviation}
   * @param detail 给运维看的说明（没有 token）
   */
  record Item(String name, String titleZh, String titleEn, boolean ok, String detail) {}

  /** 现在的状态；没有配置、不需要检查时返回空列表 */
  List<Item> evaluate();
}
