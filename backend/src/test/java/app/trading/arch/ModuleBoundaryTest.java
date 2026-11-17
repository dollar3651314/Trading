package app.trading.arch;

import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.Test;

/** 模块之间只通过对方的 api 包调用。违反就 CI 失败，AI 改代码时不会悄悄把模块搅在一起。 */
class ModuleBoundaryTest {

  @Test
  void 正式代码遵守模块边界() {
    JavaClasses classes = new ClassFileImporter().withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS).importPackages("app.trading");
    var result = ModuleRules.check("app.trading", classes);
    assertThat(result.hasViolation()).as(result.getFailureReport().toString()).isFalse();
  }

  @Test
  void 正式代码不直接用报价服务的类_只用报价协议() {
    JavaClasses classes = new ClassFileImporter().withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS).importPackages("app.trading");
    var result = ModuleRules.checkQuoteApp("app.trading", classes);
    assertThat(result.hasViolation()).as(result.getFailureReport().toString()).isFalse();
    assertThat(classes.stream().anyMatch(c -> c.getDirectDependenciesFromSelf().stream().anyMatch(d -> d.getTargetClass().getPackageName().equals("app.quote.protocol"))))
        .as("交易这边确实在用报价协议（规则不是因为没用到才通过）").isTrue();
  }

  @Test
  void 报价服务规则能拦住直接用quote_app内部类_放过报价协议() {
    var bad = ModuleRules.checkQuoteApp("archfixture.quotebad", new ClassFileImporter().importPackages("archfixture.quotebad"));
    assertThat(bad.hasViolation()).isTrue();
    assertThat(bad.getFailureReport().toString()).contains("archfixture.quotebad.PeekQuoteApp").contains("app.quote.pipeline.QuoteProcessor");
    assertThat(ModuleRules.checkQuoteApp("archfixture.quotegood", new ClassFileImporter().importPackages("archfixture.quotegood")).hasViolation()).isFalse();
  }

  @Test
  void 规则能拦住跨模块直接调用内部类() {
    JavaClasses classes = new ClassFileImporter().importPackages("archfixture.bad");
    var result = ModuleRules.check("archfixture.bad", classes);
    assertThat(result.hasViolation()).isTrue();
    assertThat(result.getFailureReport().toString()).contains("archfixture.bad.trading.PlaceOrder").contains("archfixture.bad.account.internal.AccountRepository");
  }

  @Test
  void 通过对方的api包调用是允许的() {
    JavaClasses classes = new ClassFileImporter().importPackages("archfixture.good");
    assertThat(ModuleRules.check("archfixture.good", classes).hasViolation()).isFalse();
  }
}
