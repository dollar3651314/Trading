-- quote-app V1（第 2 批：报价拆进程）：报价进程自己的品种、交易时段、休市日（库和 trading-app 分开，变量 FX_QUOTE_DB_*）。
-- 只放报价要用的字段：代码、类别、小数位、模拟起始价；交易用的参数（手数、杠杆、加点等）留在 trading-app 的 market_symbol。
-- 第一版 50 个品种和时段照 trading-app 的 V4 迁移；之后 trading-app 后台改品种、时段、休市日、模拟起始价时，
-- 由 trading-app 调 PUT /internal/catalog 整份同步过来（app.quote.catalog.CatalogSync）。

CREATE TABLE quote_symbol (
  code            VARCHAR(16)   NOT NULL,
  category        VARCHAR(8)    NOT NULL COMMENT 'fx / metal / index / energy / crypto / stock',
  digits          INT           NOT NULL COMMENT '报价小数位',
  sim_start_price DECIMAL(24,8) NULL COMMENT '模拟起始价（只在模拟报价源下使用，csv 里没有的品种用它）',
  sort_order      INT           NOT NULL DEFAULT 0,
  updated_at      DATETIME(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='报价品种（只放启用的）';

-- 每周交易时段（UTC），按分钟：open_minute 含、close_minute 不含，0–1440。一天可以有多段
CREATE TABLE quote_session (
  id           BIGINT      NOT NULL AUTO_INCREMENT,
  symbol       VARCHAR(16) NOT NULL,
  day_of_week  TINYINT     NOT NULL COMMENT '1=周一 … 7=周日',
  open_minute  INT         NOT NULL,
  close_minute INT         NOT NULL,
  PRIMARY KEY (id),
  KEY idx_quote_session_symbol (symbol)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='每周交易时段';

-- 全天休市的日期，symbol = '*' 表示所有品种
CREATE TABLE quote_holiday (
  id         BIGINT      NOT NULL AUTO_INCREMENT,
  symbol     VARCHAR(16) NOT NULL,
  trade_date DATE        NOT NULL COMMENT 'UTC 日期',
  PRIMARY KEY (id),
  UNIQUE KEY uk_quote_holiday (symbol, trade_date)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='休市日';

INSERT INTO quote_symbol (code, category, digits, sort_order) VALUES
('EURUSD','fx',5,1),
('GBPUSD','fx',5,2),
('USDJPY','fx',3,3),
('USDCHF','fx',5,4),
('USDCAD','fx',5,5),
('AUDUSD','fx',5,6),
('NZDUSD','fx',5,7),
('EURGBP','fx',5,8),
('EURJPY','fx',3,9),
('GBPJPY','fx',3,10),
('EURCHF','fx',5,11),
('EURAUD','fx',5,12),
('EURCAD','fx',5,13),
('AUDJPY','fx',3,14),
('CADJPY','fx',3,15),
('CHFJPY','fx',3,16),
('AUDCAD','fx',5,17),
('AUDNZD','fx',5,18),
('GBPAUD','fx',5,19),
('GBPCHF','fx',5,20),
('USDCNH','fx',5,21),
('XAUUSD','metal',2,22),
('XAGUSD','metal',3,23),
('US30','index',1,24),
('US500','index',1,25),
('NAS100','index',1,26),
('US2000','index',1,27),
('GER40','index',1,28),
('UK100','index',1,29),
('FRA40','index',1,30),
('JPN225','index',0,31),
('HK50','index',1,32),
('AUS200','index',1,33),
('XTIUSD','energy',2,34),
('XBRUSD','energy',2,35),
('BTCUSD','crypto',1,36),
('ETHUSD','crypto',2,37),
('SOLUSD','crypto',2,38),
('XRPUSD','crypto',4,39),
('BNBUSD','crypto',2,40),
('DOGEUSD','crypto',5,41),
('ADAUSD','crypto',4,42),
('LTCUSD','crypto',2,43),
('DOTUSD','crypto',3,44),
('BCHUSD','crypto',2,45),
('AAPL','stock',2,46),
('MSFT','stock',2,47),
('NVDA','stock',2,48),
('TSLA','stock',2,49),
('AMZN','stock',2,50);

-- 外汇、贵金属、指数、能源：周日 22:00 到周五 21:00；加密货币：全天候；美股：周一到周五 13:30–20:00（和 trading-app V4 一样）
INSERT INTO quote_session (symbol, day_of_week, open_minute, close_minute)
SELECT code, 7, 1320, 1440 FROM quote_symbol WHERE category IN ('fx', 'metal', 'index', 'energy')
UNION ALL SELECT s.code, d.d, 0, 1440 FROM quote_symbol s JOIN (SELECT 1 AS d UNION ALL SELECT 2 UNION ALL SELECT 3 UNION ALL SELECT 4) d WHERE s.category IN ('fx', 'metal', 'index', 'energy')
UNION ALL SELECT code, 5, 0, 1260 FROM quote_symbol WHERE category IN ('fx', 'metal', 'index', 'energy')
UNION ALL SELECT s.code, d.d, 0, 1440 FROM quote_symbol s JOIN (SELECT 1 AS d UNION ALL SELECT 2 UNION ALL SELECT 3 UNION ALL SELECT 4 UNION ALL SELECT 5 UNION ALL SELECT 6 UNION ALL SELECT 7) d WHERE s.category = 'crypto'
UNION ALL SELECT s.code, d.d, 810, 1200 FROM quote_symbol s JOIN (SELECT 1 AS d UNION ALL SELECT 2 UNION ALL SELECT 3 UNION ALL SELECT 4 UNION ALL SELECT 5) d WHERE s.category = 'stock';
