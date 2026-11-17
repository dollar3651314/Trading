/**
 * 支付接口层（第 1 批）：钱包提供方可以切换，对上（钱包业务规则、后台）只有 {@link app.trading.payment.api.PaymentApi} 的 7 个动作——
 * 开户、取地址、收到账（回调交给 {@link app.trading.payment.api.PaymentListener}）、代付、查付款、查余额、拉流水（TG-2 前按单号逐笔查）。
 * 对下每家提供方一个适配（{@link app.trading.payment.provider.PaymentProvider}）：第一版只有 TopUP（{@code topup} 包）；
 * Cobo、Fireblocks 只留类名和配置位置，不写实现（TR4）。
 * <p>Trading 不碰链和私钥：地址、到账、放款都由提供方做；客户余额、冻结、审核都在 wallet 模块。
 * <p>模块边界：其他模块只能用 {@code api} 包；提供方实现（{@code provider}、{@code topup}、{@code fake}）只有本模块能用（ArchUnit 强制）。
 */
package app.trading.payment;
