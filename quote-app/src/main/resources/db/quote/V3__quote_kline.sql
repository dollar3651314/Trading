-- quote-app V3（第 2 批第二步）：K 线搬到报价服务（技术方案 02 页第 2 节第 4 条"K 线只在 quote-app"）。
-- 表结构和名字和 trading-app 的 market_kline 一样（V4 迁移），只是放在报价服务自己的库里；trading-app 那张表不再写入（还没上线，没有要搬的数据）。
-- 收盘后的 K 线（未收盘的在内存里）。价格用基准中间价，不含加点
CREATE TABLE market_kline (
  symbol    VARCHAR(16)   NOT NULL,
  timeframe VARCHAR(4)    NOT NULL COMMENT '1m / 5m / 15m / 1h / 4h / 1d',
  open_time BIGINT        NOT NULL COMMENT 'UTC 秒',
  open      DECIMAL(24,8) NOT NULL,
  high      DECIMAL(24,8) NOT NULL,
  low       DECIMAL(24,8) NOT NULL,
  close     DECIMAL(24,8) NOT NULL,
  ticks     INT           NOT NULL COMMENT '这根 K 线收到的报价条数（不是成交量）',
  PRIMARY KEY (symbol, timeframe, open_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='K 线';
