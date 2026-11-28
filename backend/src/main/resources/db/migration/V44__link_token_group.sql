-- TG 机器人验收反馈（循环问题）：机器人发的一整套新菜单里，每个进网页的按钮都带自己的 60 秒一次性令牌；同一套菜单的令牌是一组（group_id），
-- 用掉其中一个，同组别的令牌一起作废（没用的不能留着以后再用）
ALTER TABLE account_tg_link_token ADD COLUMN group_id CHAR(32) NULL COMMENT '同一套菜单的令牌一组；用掉一个，同组的一起作废' AFTER user_id,
  ADD KEY idx_account_tg_link_token_group (group_id);
