package archfixture.quotegood;

import app.quote.protocol.QuoteFrame;

/** 正例：交易这边只用报价协议（quote-protocol）。 */
public class UseProtocol {
  public boolean fresh(QuoteFrame f) {
    return f.fresh();
  }
}
