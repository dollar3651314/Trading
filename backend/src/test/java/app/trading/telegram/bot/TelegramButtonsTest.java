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
  void 退路模式_回调按钮() {
    InlineKeyboardButton b = TelegramSenderImpl.open(props("link"), "持仓", "/positions");
    assertThat(b.getCallbackData()).isEqualTo("open:/positions");
    assertThat(b.getLoginUrl()).isNull();
  }
}
