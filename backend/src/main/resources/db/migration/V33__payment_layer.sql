-- V33（第 1 批）：支付接口层（payment）接 TopUP；整理第 0 批留下的 wallet_* 表。
-- 业务表（白名单、提现单、提现事件、充值记录、挂账、对账问题、自动审批判定）保留原名，现有测试和后台查询照用；
-- 自带钱包的链上部分（地址派生、扫块进度、nonce、广播交易、归集）已经不用，表删掉。

-- 1. 删掉不再使用的表（第 0 批已删代码，只剩表）
DROP TABLE IF EXISTS wallet_sweep_outflow;
DROP TABLE IF EXISTS wallet_sweep_event;
DROP TABLE IF EXISTS wallet_sweep_nonce;
DROP TABLE IF EXISTS wallet_sweep_tx;
DROP TABLE IF EXISTS wallet_sweep_item;
DROP TABLE IF EXISTS wallet_sweep_batch;
DROP TABLE IF EXISTS wallet_address_scheme;
DROP TABLE IF EXISTS wallet_withdraw_tx;
DROP TABLE IF EXISTS wallet_chain_nonce;
DROP TABLE IF EXISTS wallet_chain_cursor;
DROP TABLE IF EXISTS wallet_address;
DELETE FROM common_config WHERE config_key LIKE 'sweep.%' OR config_key LIKE 'hotWallet.%';
DELETE FROM notify_template WHERE code IN ('SWEEP_UNKNOWN_OUTFLOW', 'SWEEP_FAILED', 'SWEEP_STUCK');

-- 2. 充值地址：用户第一次选某条链时才向提供方要（需求 02 第二节）。提供方在生成时绑定：以后切换提供方，老地址照常入账
CREATE TABLE pay_address (
  id          BIGINT       NOT NULL AUTO_INCREMENT,
  user_id     BIGINT       NOT NULL,
  network     VARCHAR(8)   NOT NULL COMMENT 'ERC20 / BSC / TRC20',
  provider    VARCHAR(16)  NOT NULL COMMENT '生成这个地址的提供方：TOPUP / COBO / FIREBLOCKS / 测试替身',
  customer_id VARCHAR(128) NOT NULL COMMENT '提供方那边的客户标识（= Trading 客户号）',
  address     VARCHAR(64)  NOT NULL,
  created_at  DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  UNIQUE KEY uk_pay_address_user (user_id, network),
  KEY idx_pay_address_addr (network, address)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

-- 3. 充值记录改成"提供方回调入账"：按 提供方 + 提供方到账编号 去重（只入账一次）。
--    TopUP 的到账回调没有链上日志序号（TG-5），同一笔交易可能有几次转账，原来的（链、交易、序号）唯一键去掉。
ALTER TABLE wallet_deposit
  DROP INDEX uk_wallet_deposit_tx,
  ADD COLUMN provider     VARCHAR(16)   NOT NULL DEFAULT 'TOPUP' COMMENT '哪个提供方通知的' AFTER chain,
  ADD COLUMN provider_ref VARCHAR(80)   NULL COMMENT '提供方的到账编号（TopUP：回调事件 ID）' AFTER provider,
  ADD COLUMN provider_fee DECIMAL(24,8) NOT NULL DEFAULT 0 COMMENT '提供方收 Trading 的手续费（平台成本，不扣客户）' AFTER amount,
  MODIFY log_index    INT          NOT NULL DEFAULT 0,
  MODIFY block_number BIGINT       NOT NULL DEFAULT 0,
  MODIFY block_hash   VARCHAR(80)  NOT NULL DEFAULT '',
  MODIFY from_address VARCHAR(64)  NOT NULL DEFAULT '',
  ADD UNIQUE KEY uk_wallet_deposit_provider (provider, provider_ref),
  ADD KEY idx_wallet_deposit_tx (chain, tx_hash);

-- 4. 提现交给提供方的付款（一笔提现一条）。防重键 = Trading 提现单号，TopUP 的防重只保留 24 小时（TG-1 补上前的保护见代码）
CREATE TABLE pay_payout (
  id              BIGINT        NOT NULL AUTO_INCREMENT,
  withdrawal_id   BIGINT        NOT NULL COMMENT 'wallet_withdrawal.id',
  user_id         BIGINT        NOT NULL COMMENT '客户号（提供方那边的客户标识）',
  provider        VARCHAR(16)   NOT NULL COMMENT '提交时的提供方；切换提供方后进行中的仍由它付完',
  idem_key        VARCHAR(128)  NOT NULL COMMENT '提交用的防重键（Idempotency-Key），同一笔永远同一个',
  merchant_ref    VARCHAR(64)   NOT NULL COMMENT '提交时带的商户单号（= Trading 提现对外编号）',
  network         VARCHAR(8)    NOT NULL,
  address         VARCHAR(64)   NOT NULL,
  amount          DECIMAL(24,8) NOT NULL COMMENT '交给提供方付的金额 = 提现金额 − 手续费',
  provider_ref    VARCHAR(80)   NULL COMMENT '提供方付款编号（TopUP withdrawal_no）；提交成功后才有',
  status          VARCHAR(16)   NOT NULL COMMENT 'SUBMITTING 提交中（还没拿到编号）/ PENDING 等待放款审核 / APPROVED、BROADCAST 放款中 / COMPLETED / FAILED / REJECTED / CANCELLED / UNKNOWN 查不清（不退款）',
  tx_hash         VARCHAR(100)  NULL,
  reason          VARCHAR(500)  NULL,
  first_submit_at DATETIME(3)   NOT NULL COMMENT '第一次提交的时间：超过 23 小时还没拿到结果就不再自动重提（防重键 24 小时失效）',
  last_submit_at  DATETIME(3)   NULL,
  submit_count    INT           NOT NULL DEFAULT 0,
  last_query_at   DATETIME(3)   NULL,
  updated_at      DATETIME(3)   NOT NULL COMMENT '最近一次状态变化（应用写入）',
  created_at      DATETIME(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  UNIQUE KEY uk_pay_payout_withdrawal (withdrawal_id),
  UNIQUE KEY uk_pay_payout_ref (provider, provider_ref),
  KEY idx_pay_payout_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

-- 5. 收到的回调（验签通过的），按 提供方 + 事件 ID 去重：重复投递只处理一次
CREATE TABLE pay_callback (
  id          BIGINT       NOT NULL AUTO_INCREMENT,
  provider    VARCHAR(16)  NOT NULL,
  event_id    VARCHAR(80)  NOT NULL,
  event_type  VARCHAR(40)  NOT NULL,
  ref         VARCHAR(80)  NULL COMMENT '涉及的单号（付款编号 / 客户号）',
  received_at DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  UNIQUE KEY uk_pay_callback_event (provider, event_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

-- 6. 钱包提供方页的操作记录（切换提供方、网络开关；原因必填，高危操作，TY-3）
CREATE TABLE pay_audit (
  id         BIGINT       NOT NULL AUTO_INCREMENT,
  admin_id   BIGINT       NOT NULL,
  action     VARCHAR(32)  NOT NULL COMMENT 'SWITCH_PROVIDER / NETWORK_ON / NETWORK_OFF',
  detail     VARCHAR(200) NOT NULL,
  reason     VARCHAR(500) NOT NULL,
  created_at DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

-- 7. 当前提供方、三条链的充值开关（默认 TopUP、都开）
INSERT INTO common_config (config_key, value) VALUES
('payment.provider', 'TOPUP'),
('payment.network.ERC20', 'true'),
('payment.network.BSC', 'true'),
('payment.network.TRC20', 'true');

-- 8. 单笔提现上限降到 5,000（TQ-13 = TD-15 A：TopUP 单笔只能付 5,000）
UPDATE common_config SET value = '5000' WHERE config_key = 'withdraw.maxSingle' AND CAST(value AS DECIMAL(24,8)) > 5000;
