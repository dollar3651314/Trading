package app.trading.common.health;

/**
 * 测试环境的测试管理员开没开（做法 07）：健康检查在测试环境（有 {@code env}）时返回 {@code testAdmin: true / false}，验收用例跑之前先核对；
 * 生产不返回这个字段。实现在后台模块（{@code admin.security.TestAdmin}），这里只是接口，公共模块不依赖后台模块。
 */
public interface TestAdminFlag {

  /** 只报是否，不含任何令牌信息 */
  boolean testAdminOn();
}
