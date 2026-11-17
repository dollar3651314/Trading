package app.trading.arch;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.Test;

/**
 * 支付接口层的边界（技术方案 05 页"新加的测试"ArchUnit 一项）：payment 以外的模块只能用 payment.api，不能 import 提供方实现（TopUP、Cobo、Fireblocks、假 TopUP）；
 * payment.api 自己也不依赖实现，换提供方时调用方一行不用改。
 */
class PaymentBoundaryTest {

  static final JavaClasses MAIN = new ClassFileImporter().withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS).importPackages("app.trading");

  @Test
  void payment以外的模块不能import提供方实现() {
    var r = noClasses().that().resideOutsideOfPackage("app.trading.payment..")
        .should().dependOnClassesThat().resideInAnyPackage("app.trading.payment.provider..", "app.trading.payment.topup..", "app.trading.payment.fake..",
            "app.trading.payment.core..")
        .evaluate(MAIN);
    assertThat(r.hasViolation()).as(r.getFailureReport().toString()).isFalse();
  }

  @Test
  void payment的api包不依赖实现() {
    var r = noClasses().that().resideInAPackage("app.trading.payment.api..")
        .should().dependOnClassesThat().resideInAnyPackage("app.trading.payment.provider..", "app.trading.payment.topup..", "app.trading.payment.fake..",
            "app.trading.payment.core..")
        .evaluate(MAIN);
    assertThat(r.hasViolation()).as(r.getFailureReport().toString()).isFalse();
  }

  @Test
  void 规则能拦住_钱包模块直接用TopUP实现的反例() {
    JavaClasses bad = new ClassFileImporter().importPackages("archfixture.payment");
    var r = noClasses().that().resideOutsideOfPackage("archfixture.payment.payment..")
        .should().dependOnClassesThat().resideInAnyPackage("archfixture.payment.payment.topup..")
        .evaluate(bad);
    assertThat(r.hasViolation()).isTrue();
  }
}
