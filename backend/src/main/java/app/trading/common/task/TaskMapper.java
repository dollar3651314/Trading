package app.trading.common.task;

import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** 任务表的 SQL，见 resources/mapper/common/TaskMapper.xml。 */
@Mapper
interface TaskMapper {

  int insert(@Param("taskType") String taskType, @Param("payload") String payload, @Param("delaySeconds") long delaySeconds, @Param("maxAttempts") int maxAttempts);

  long lastInsertId();

  List<Long> lockDue(@Param("taskType") String taskType, @Param("limit") int limit);

  int markRunning(@Param("ids") List<Long> ids, @Param("worker") String worker);

  int markSucceeded(@Param("id") long id);

  int markRetry(@Param("id") long id, @Param("delaySeconds") long delaySeconds, @Param("error") String error);

  int markFailed(@Param("id") long id, @Param("error") String error);

  int recoverStuck(@Param("timeoutSeconds") long timeoutSeconds);

  Task find(@Param("id") long id);

  /** 某类还没完成的任务（待处理、处理中） */
  java.util.List<Task> active(@Param("taskType") String taskType);

  /** 某类最近 seconds 秒内到期并已成功的任务（走 idx_common_task_pick） */
  java.util.List<Task> recentSucceeded(@Param("taskType") String taskType, @Param("seconds") long seconds);

  List<Task> findByIds(@Param("ids") List<Long> ids);

  List<TaskItem> list(@Param("taskType") String taskType, @Param("status") String status, @Param("offset") int offset, @Param("limit") int limit);

  long count(@Param("taskType") String taskType, @Param("status") String status);

  int retry(@Param("id") long id);

  /** 积压：已经到期还没处理的任务数（指标 fx_tasks_backlog，E10） */
  long countDue();
}
