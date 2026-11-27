package app.trading.notification.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 每 2 秒发送到期的邮件任务；每分钟把卡在"处理中"超过 5 分钟的任务收回重发。测试配置里关闭，测试直接调用。 */
@Component
@ConditionalOnProperty(name = "trading.notify.worker-enabled", havingValue = "true", matchIfMissing = true)
class NotifyScheduler {

  private static final Logger log = LoggerFactory.getLogger(NotifyScheduler.class);

  private final NotifyWorker worker;
  private final app.trading.common.task.TaskService tasks;

  private final NotifyProperties props;
  private final java.time.Clock clock;

  NotifyScheduler(NotifyWorker worker, app.trading.common.task.TaskService tasks, NotifyProperties props, java.time.Clock clock) {
    this.worker = worker;
    this.tasks = tasks;
    this.props = props;
    this.clock = clock;
  }

  /** 启动后补发重启前已交给 Telegram 发送队列、还没发出的提醒（{@link NotifyWorker#resumeTelegram}） */
  @org.springframework.context.event.EventListener(org.springframework.boot.context.event.ApplicationReadyEvent.class)
  void resumeTelegram() {
    try {
      worker.resumeTelegram(props.telegramResumeWithin(), java.time.LocalDateTime.now(clock));
    } catch (RuntimeException e) {
      log.error("重启后补发 Telegram 提醒失败", e);
    }
  }

  @Scheduled(fixedDelay = 2_000, initialDelay = 10_000)
  void tick() {
    try {
      worker.drain();
    } catch (RuntimeException e) {
      log.error("发送邮件任务失败", e);
    }
  }

  @Scheduled(fixedDelay = 60_000, initialDelay = 60_000)
  void recover() {
    try {
      int n = tasks.recoverStuck(300);
      if (n > 0) log.warn("收回 {} 个卡住的任务", n);
    } catch (RuntimeException e) {
      log.error("收回卡住的任务失败", e);
    }
  }
}
