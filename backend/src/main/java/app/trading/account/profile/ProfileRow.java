package app.trading.account.profile;

import java.time.LocalDate;
import java.time.LocalDateTime;

/** 个人资料（account_profile + 用户表的邮箱和注册时间）。机器人开户的客户没有邮箱、可能还没有资料（这些字段为空）。 */
record ProfileRow(long userId, String email, String firstName, String middleName, String lastName, LocalDate birthDate, String nationality, LocalDateTime registeredAt) {}
