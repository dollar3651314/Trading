package app.quote.open;

import static org.assertj.core.api.Assertions.assertThat;

import app.quote.support.QuoteAppTest;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 审查 🟡：Key 快照的重新加载竞态（也是 OpenServiceTest.AC_Q2_轮换… 偶发失败的原因）。
 * 用 {@link ApiKeys#beforeSwap(Runnable)} 把"定时重新加载"卡在读完库、换快照之前，确定地复现：以前它读到的旧快照会在改动之后换上去，把改动盖掉。
 */
@QuoteAppTest
class ApiKeysReloadRaceTest {

  @Autowired ApiKeys keys;
  @Autowired JdbcTemplate jdbc;
  @Autowired TransactionTemplate tx;

  @AfterEach
  void clean() {
    keys.beforeSwap(null);
    jdbc.update("DELETE FROM quote_api_key");
    jdbc.update("DELETE FROM quote_client");
    keys.reload();
  }

  String newKey() {
    ApiKeys.Client c = keys.createClient("竞态测试" + System.nanoTime(), null, null);
    return keys.createKey(c.id(), ApiKeys.Kind.EXTERNAL, Set.of()).key().keyId();
  }

  /**
   * 线程 A 做"定时重新加载"，读完库后卡住；这时线程 B 做改动。B 做完（或者已经在等重新加载的锁）后才放 A 换快照。
   * 修复前：B 的改动先换上快照，A 再把旧快照换上去 → 改动丢了。修复后：B 的重新加载要等 A 换完才开始读，最后留下的是新数据。
   */
  void staleScheduledReloadAround(Runnable mutation) throws Exception {
    CountDownLatch readDone = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    AtomicReference<Thread> scheduled = new AtomicReference<>();
    keys.beforeSwap(() -> {
      if (Thread.currentThread() != scheduled.get()) return;
      readDone.countDown();
      try {
        release.await(10, TimeUnit.SECONDS);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
    });
    Thread a = new Thread(keys::reload, "假装定时重新加载");
    scheduled.set(a);
    a.start();
    assertThat(readDone.await(10, TimeUnit.SECONDS)).as("A 读完了库").isTrue();
    AtomicReference<Throwable> failed = new AtomicReference<>();
    Thread b = new Thread(() -> {
      try {
        mutation.run();
      } catch (Throwable t) {
        failed.set(t);
      }
    }, "后台改动");
    b.start();
    long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
    while (b.isAlive() && !keys.waitingForReload(b) && System.nanoTime() < until) Thread.sleep(5);
    release.countDown();
    a.join(10_000);
    b.join(10_000);
    assertThat(failed.get()).isNull();
    keys.beforeSwap(null);
  }

  @Test
  void 定时重新加载卡在中间_改的限额不会被旧快照盖掉() throws Exception {
    String id = newKey();
    staleScheduledReloadAround(() -> keys.setLimits(id, new Limits(7, 777, 1, 5)));
    assertThat(keys.find(id).orElseThrow().limits()).isEqualTo(new Limits(7, 777, 1, 5));
  }

  @Test
  void 定时重新加载卡在中间_轮换后新Key能用_旧Key立即停用() throws Exception {
    String old = newKey();
    AtomicReference<String> neu = new AtomicReference<>();
    staleScheduledReloadAround(() -> neu.set(keys.rotate(old).key().keyId()));
    assertThat(keys.find(neu.get())).as("新 Key 在快照里").isPresent();
    assertThat(keys.secret(neu.get())).as("新 Key 能验签").isPresent();
    assertThat(keys.find(old).orElseThrow().active()).as("旧 Key 已停用").isFalse();
    assertThat(keys.secret(old)).as("旧 Key 不能再验签").isEmpty();
  }

  @Test
  void 定时重新加载卡在中间_停用立即生效() throws Exception {
    String id = newKey();
    staleScheduledReloadAround(() -> keys.disable(id));
    assertThat(keys.secret(id)).isEmpty();
  }

  @Test
  void 轮换以库里的限额为准_不用内存快照() {
    String id = newKey();
    // 库里的限额已经改了、快照还没重新加载（例如改限额的那次重新加载被盖掉了）
    jdbc.update("UPDATE quote_api_key SET rest_per_second = 3, rest_per_day = 333, ws_connections = 1, ws_symbols = 4 WHERE key_id = ?", id);
    assertThat(keys.find(id).orElseThrow().limits()).isEqualTo(Limits.DEFAULT);
    ApiKeys.Created c = keys.rotate(id);
    assertThat(c.key().limits()).isEqualTo(new Limits(3, 333, 1, 4));
    assertThat(keys.find(c.key().keyId()).orElseThrow().limits()).isEqualTo(new Limits(3, 333, 1, 4));
  }

  @Test
  void 外层事务回滚时_快照里没有回滚掉的新Key() {
    String id = newKey();
    AtomicReference<String> neu = new AtomicReference<>();
    tx.executeWithoutResult(s -> {
      neu.set(keys.rotate(id).key().keyId());
      s.setRollbackOnly();
    });
    assertThat(keys.find(neu.get())).as("回滚掉的新 Key").isEmpty();
    assertThat(keys.find(id).orElseThrow().active()).as("旧 Key 没被停用").isTrue();
    keys.reload();
    assertThat(keys.find(id).orElseThrow().active()).isTrue();
  }

  @Test
  void 同一个Key同时轮换两次_只有一次成功() throws Exception {
    String id = newKey();
    CyclicBarrier start = new CyclicBarrier(2);
    List<Object> results = java.util.Collections.synchronizedList(new ArrayList<>());
    List<Thread> ts = new ArrayList<>();
    for (int i = 0; i < 2; i++) {
      Thread t = new Thread(() -> {
        try {
          start.await(10, TimeUnit.SECONDS);
          results.add(keys.rotate(id));
        } catch (IllegalArgumentException e) {
          results.add(e.getMessage());
        } catch (Exception e) {
          results.add(e);
        }
      });
      ts.add(t);
      t.start();
    }
    for (Thread t : ts) t.join(20_000);
    assertThat(results).hasSize(2);
    assertThat(results.stream().filter(r -> r instanceof ApiKeys.Created).count()).as("成功的次数").isEqualTo(1);
    assertThat(results).contains("Key 已停用，不能轮换");
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM quote_api_key WHERE rotated_from = ?", Integer.class, id)).isEqualTo(1);
  }
}
