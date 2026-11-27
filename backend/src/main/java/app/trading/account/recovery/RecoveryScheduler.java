package app.trading.account.recovery;

import java.time.LocalDateTime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 找回申请的定时推进（第 4 批 4b，每分钟）：反对期满 → 证明阶段并冻结提现；"不是我"30 分钟没输对提现密码 → 冻结；到执行时间 → 执行。
 * 每份申请各自一个事务，一份出错不影响别的。测试配置关掉（{@code trading.account.recovery.scheduler-enabled=false}），测试里直接调 RecoveryService 的推进方法
 */
@Component
@ConditionalOnProperty(name = "trading.account.recovery.scheduler-enabled", havingValue = "true", matchIfMissing = true)
class RecoveryScheduler {

  private static final Logger log = LoggerFactory.getLogger(RecoveryScheduler.class);
  private final RecoveryService service;
  private final RecoveryMapper mapper;

  RecoveryScheduler(RecoveryService service, RecoveryMapper mapper) {
    this.service = service;
    this.mapper = mapper;
  }

  @Scheduled(fixedDelay = 60_000, initialDelay = 60_000)
  void run() {
    LocalDateTime now = service.now();
    for (Long id : mapper.objectionDue(now)) each(id, () -> service.objectionEnded(id));
    for (Long id : mapper.disputeUnfrozen(now.minus(RecoveryService.DISPUTE_PIN_GRACE))) each(id, () -> service.disputeGraceEnded(id));
    for (Long id : mapper.executeDue(now)) each(id, () -> service.execute(id));
  }

  private static void each(long id, Runnable r) {
    try {
      r.run();
    } catch (RuntimeException e) {
      log.error("找回申请 #{} 定时推进出错", id, e);
    }
  }
}
