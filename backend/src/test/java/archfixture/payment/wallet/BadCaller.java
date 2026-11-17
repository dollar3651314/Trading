package archfixture.payment.wallet;

import archfixture.payment.payment.topup.Impl;

/** 反例用：钱包模块直接 new 提供方实现（规则要拦住） */
public class BadCaller {
  void run() {
    new Impl().pay();
  }
}
