-- TG 机器人第 2 批"进入网页"（技术方案 TG机器人-12 页、11 页第 2、2b 节）。
-- ① 会话分两种：WEB（网页登录，闲置 72 小时）和 BOT（机器人按钮一键登录，闲置 30 分钟、最长 12 小时）；共用一张表，冻结等安全事件一起作废
ALTER TABLE account_session
  ADD COLUMN kind VARCHAR(8) NOT NULL DEFAULT 'WEB' COMMENT 'WEB 网页登录 / BOT 从机器人一键登录（TG 规则）' AFTER token_hash,
  ADD COLUMN max_expires_at DATETIME(3) NULL COMMENT '最长到期时间（BOT 会话 12 小时）；WEB 为空' AFTER expires_at;

-- ② 一键登录（login_url）用过的 hash：hash 唯一，INSERT 成功才算第一次用（原子），保留 24 小时后清理
CREATE TABLE account_tg_login_hash (
  hash     CHAR(64)    NOT NULL COMMENT 'Telegram 签名（十六进制）',
  used_at  DATETIME(3) NOT NULL,
  PRIMARY KEY (hash),
  KEY idx_account_tg_login_hash_used (used_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='一键登录用过的 hash（防重放）';

-- ③ 退路：一次性登录链接（绑不上域名时用，FX_TG_LOGIN_MODE=link）。库里只存令牌的哈希；60 秒、只能用一次（UPDATE … WHERE used_at IS NULL 原子）
CREATE TABLE account_tg_link_token (
  token_hash CHAR(64)     NOT NULL,
  user_id    BIGINT       NOT NULL,
  next_path  VARCHAR(200) NOT NULL COMMENT '登录后打开的页面（站内路径）',
  expires_at DATETIME(3)  NOT NULL,
  used_at    DATETIME(3)  NULL,
  created_at DATETIME(3)  NOT NULL,
  PRIMARY KEY (token_hash),
  KEY idx_account_tg_link_token_expires (expires_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='一次性登录链接（一键登录的退路）';

-- ④ 安全提醒：刚在浏览器里登录（只发 Telegram，带「不是我，退出所有网页会话」按钮）
INSERT INTO notify_template (code, audience, kind, name_zh, name_en, title_zh, title_en, body_zh, body_en, variables, channels) VALUES
('LOGIN_NEW_DEVICE', 'USER', 'system', '刚在浏览器里登录（安全提醒）', 'New browser sign-in (security alert)',
  '刚在浏览器里登录', 'New browser sign-in',
  '你的账户刚在浏览器里登录：{device}，IP {ip}。如果不是你本人，请点下面的「不是我，退出所有网页会话」。',
  'Your account just signed in from a browser: {device}, IP {ip}. If this was not you, tap "Not me, sign out all web sessions" below.',
  'device,ip', 'TELEGRAM');
