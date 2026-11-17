-- quote-app V4（第 3 批：报价对外）：外部客户、Key、限额、用量计量、对外开放总开关（需求 03 页第三、四节，技术方案 02 页第 4 节）。
-- 只计量不收费（TD-6 A）：没有价格、账单、支付相关的表和字段。
-- 后台操作（建客户、建 / 停 / 轮换 Key、改限额、改默认值、开关总开关）都经 trading-app 后台调内部接口，审计记在 trading-app 的 admin_audit。

-- 品种的中英文名（对外品种列表要用），由 trading-app 同步过来（PUT /internal/catalog）
ALTER TABLE quote_symbol
  ADD COLUMN name_zh VARCHAR(64) NULL COMMENT '中文名' AFTER category,
  ADD COLUMN name_en VARCHAR(64) NULL COMMENT '英文名' AFTER name_zh;

-- 外部客户：后台建（第一版不做自助注册和客户门户，TD-7 A）
CREATE TABLE quote_client (
  id         BIGINT       NOT NULL AUTO_INCREMENT,
  code       VARCHAR(16)  NOT NULL COMMENT '客户编号 QC-0001',
  name       VARCHAR(100) NOT NULL,
  email      VARCHAR(200) NULL COMMENT '联系邮箱',
  status     VARCHAR(16)  NOT NULL DEFAULT 'ACTIVE' COMMENT 'ACTIVE / DISABLED',
  note       VARCHAR(500) NULL,
  created_at DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  UNIQUE KEY uk_quote_client_code (code),
  UNIQUE KEY uk_quote_client_name (name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='报价服务外部客户';

-- Key：Secret 只在创建（和轮换）时返回一次；库里存加密后的 Secret（服务器验签要用，密钥 FX_QUOTE_KEY_ENC_KEY 只在服务器环境变量里），
-- 接口和页面都拿不到明文。限额在建 Key 时从默认值复制一份，之后改默认值不影响已建的 Key。
CREATE TABLE quote_api_key (
  key_id          VARCHAR(40)   NOT NULL COMMENT 'qk_ 开头，公开的 Key ID',
  client_id       BIGINT        NOT NULL,
  kind            VARCHAR(16)   NOT NULL COMMENT 'INTERNAL_TEST（内部测试，不受总开关限制）/ EXTERNAL（外部）',
  status          VARCHAR(16)   NOT NULL DEFAULT 'ACTIVE' COMMENT 'ACTIVE / DISABLED',
  secret_enc      VARBINARY(256) NOT NULL COMMENT 'AES-GCM 加密的 Secret（iv + 密文）',
  symbols         VARCHAR(2000) NULL COMMENT '可用品种，逗号分隔；空 = 全部已授权品种',
  rest_per_second INT           NOT NULL,
  rest_per_day    INT           NOT NULL,
  ws_connections  INT           NOT NULL,
  ws_symbols      INT           NOT NULL COMMENT '每条 WebSocket 连接最多订阅几个品种',
  rotated_from    VARCHAR(40)   NULL COMMENT '轮换出来的新 Key 记下旧 Key',
  created_at      DATETIME(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  disabled_at     DATETIME(3)   NULL,
  last_used_at    DATETIME(3)   NULL,
  PRIMARY KEY (key_id),
  KEY idx_quote_api_key_client (client_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='报价服务 API Key';

-- 全平台设置：对外开放总开关（没有这一行 = 关）、默认限额。总开关只能经后台超管打开（要勾"Amos 已同意"、写原因、审计）
CREATE TABLE quote_open_setting (
  name       VARCHAR(32)  NOT NULL,
  value      VARCHAR(500) NOT NULL,
  updated_by VARCHAR(64)  NULL,
  reason     VARCHAR(500) NULL,
  updated_at DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='报价对外的全平台设置';

-- 用量（按天、按 Key、按接口、按品种）：REST 请求数（被 429 拒的单独记）、WebSocket 推送条数、连接秒数、当天连接数峰值。
-- endpoint 为空串 = WebSocket；symbol 为空串 = 不分品种。保留 13 个月（每天清理一次）。
CREATE TABLE quote_usage_daily (
  day           DATE        NOT NULL COMMENT 'UTC 日期',
  key_id        VARCHAR(40) NOT NULL,
  endpoint      VARCHAR(32) NOT NULL DEFAULT '',
  symbol        VARCHAR(16) NOT NULL DEFAULT '',
  rest_calls    BIGINT      NOT NULL DEFAULT 0,
  rest_rejected BIGINT      NOT NULL DEFAULT 0,
  ws_pushes     BIGINT      NOT NULL DEFAULT 0,
  ws_seconds    BIGINT      NOT NULL DEFAULT 0,
  ws_peak       INT         NOT NULL DEFAULT 0,
  PRIMARY KEY (day, key_id, endpoint, symbol),
  KEY idx_quote_usage_key (key_id, day)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='报价对外用量（只计量不收费）';
