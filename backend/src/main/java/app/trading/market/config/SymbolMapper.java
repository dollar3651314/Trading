package app.trading.market.config;

import app.trading.market.api.SymbolSpec;
import java.time.LocalDate;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** 品种配置的 SQL，见 resources/mapper/market/SymbolMapper.xml。 */
@Mapper
interface SymbolMapper {

  List<SymbolSpec> enabledSymbols();

  List<SessionRow> sessions();

  List<HolidayRow> holidays(@Param("since") java.time.LocalDate since);

  /** 启用的、填了模拟起始价的品种（同步给报价服务） */
  List<SimPriceRow> simStartPrices();

  record SessionRow(String symbol, int dayOfWeek, int openMinute, int closeMinute) {}

  record HolidayRow(String symbol, LocalDate tradeDate) {}

  record SimPriceRow(String code, java.math.BigDecimal simStartPrice) {}
}
