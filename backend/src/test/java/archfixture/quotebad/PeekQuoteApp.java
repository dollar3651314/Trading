package archfixture.quotebad;

import app.quote.pipeline.QuoteProcessor;

/** 反例：交易这边直接用报价服务的内部类（ModuleBoundaryTest 要能拦住）。 */
public class PeekQuoteApp {
  public Object peek(QuoteProcessor p) {
    return p.latest("EURUSD");
  }
}
