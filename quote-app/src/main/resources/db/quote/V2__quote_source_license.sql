-- quote-app V2（第 2 批）：报价源授权登记（技术方案 08 页，需求 03 页第四节，TQ-11 A）。
-- 每个"报价商 × 品种"登记三项：交易用途、登录后展示、转售——各自有没有、授权日期、到期日、备注。不存合同原文和价格。
-- symbol = '*' 表示这家报价商的全部品种；同一家既有 '*' 又有具体品种时，具体品种那一行优先。
-- 生效规则：标记为 1，并且（起始日为空或今天 ≥ 起始日），并且（到期日为空或今天 ≤ 到期日），按 UTC 日期。
-- 现在用到的：生产（报价模式 live）只用"交易用途"已授权的源成交，没有的品种只显示不开仓。登录后展示、转售两项先登记，
-- 第 3 批（对外 Key）和客户端行情按它们控制。后台页面在第 3 批做；现在只有内部接口 /internal/licenses。
CREATE TABLE quote_source_license (
  feed          VARCHAR(16)  NOT NULL COMMENT '报价商：alltick / itick / binance',
  symbol        VARCHAR(16)  NOT NULL COMMENT '平台品种代码；* = 这家的全部品种',
  trading_use   TINYINT      NOT NULL DEFAULT 0 COMMENT '交易用途（拿来做成交价）',
  trading_from  DATE         NULL,
  trading_until DATE         NULL,
  display_use   TINYINT      NOT NULL DEFAULT 0 COMMENT '登录后展示',
  display_from  DATE         NULL,
  display_until DATE         NULL,
  resale        TINYINT      NOT NULL DEFAULT 0 COMMENT '转售（给外部 Key）',
  resale_from   DATE         NULL,
  resale_until  DATE         NULL,
  note          VARCHAR(500) NULL COMMENT '备注（不写合同原文和价格）',
  updated_at    DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (feed, symbol)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='报价源授权登记';
