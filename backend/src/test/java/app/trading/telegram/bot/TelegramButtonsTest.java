package app.trading.telegram.bot;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;

/** 打开网页的按钮（TG 机器人第 2 批）：一键登录模式是 login_url 按钮指向落地页；退路模式是回调按钮 open:路径。 */
class TelegramButtonsTest {

  static TelegramProperties props(String mode) {
    return new TelegramProperties("1:t", "TradingAssistTestBot", "https://api.telegram.org", "https://trade.example.test/", Duration.ofSeconds(50),
        Duration.ofSeconds(1), 25, 100, Duration.ofMinutes(10), 20, mode);
  }

  @Test
  void 一键登录模式_login_url按钮指向落地页_带要打开的页面() {
    InlineKeyboardButton b = TelegramSenderImpl.open(props("login-url"), "持仓", "/positions?pid=12");
    assertThat(b.getLoginUrl().getUrl()).isEqualTo("https://trade.example.test/api/tg/login?tr_next=%2Fpositions%3Fpid%3D12");
    assertThat(b.getCallbackData()).isNull();
    assertThat(b.getUrl()).as("不是普通网址按钮，也不是 web_app（不是 Mini App）").isNull();
    assertThat(b.getWebApp()).isNull();
  }

  @Test
  void 一次性链接模式_默认_普通网址按钮不带令牌_带from_tg() {
    InlineKeyboardButton b = TelegramSenderImpl.open(props("link"), "持仓", "/positions");
    assertThat(b.getUrl()).as("11/28 Amos 定：普通网址按钮，在 Telegram 里打开；7 天内用过网页直接进入").isEqualTo("https://trade.example.test/positions?from=tg");
    assertThat(b.getUrl()).doesNotContain("#t=");
    assertThat(b.getLoginUrl()).isNull();
    assertThat(b.getCallbackData()).isNull();
    assertThat(b.getWebApp()).as("不是 web_app（不是 Mini App）").isNull();
    assertThat(TelegramSenderImpl.open(props("link"), "查看", "/positions?pid=12").getUrl()).isEqualTo("https://trade.example.test/positions?pid=12&from=tg");
  }
}
