-- V6（S8）：改单次数（冻结差额的账本幂等键用"订单号:次数"）、撤单时间；历史记录查询的索引。说明见 内部文档 2.12、第 10 节。
ALTER TABLE trading_order
  ADD COLUMN revision     INT         NOT NULL DEFAULT 0 COMMENT '改单次数' AFTER position_id,
  ADD COLUMN cancelled_at DATETIME(3) NULL COMMENT '撤单时间' AFTER revision;

ALTER TABLE trading_position ADD KEY idx_trading_position_user_closed (user_id, status, closed_at);
