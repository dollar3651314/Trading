/**
 * 钱包（第 0 批过渡）：充值入账、提现、提现自动审批、对账的业务规则（S12–S14）。
 * 自带钱包的地址派生、扫链、归集、热钱包签名广播已经去掉，第 1 批换成支付接口层（TopUP）。
 * <p>模块边界（内部文档 3.2，ArchUnit 测试 {@code ModuleBoundaryTest} 强制）：其他模块只能调用本模块 {@code api} 包里的类，
 * 不能访问本模块的其他包和数据表。
 */
package app.trading.wallet;
