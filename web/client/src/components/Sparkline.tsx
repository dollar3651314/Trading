/**
 * 迷你走势图（今日收盘价折线，颜色跟随今日涨跌）。桌面行情表和手机行情列表共用（TG 机器人第 3 批：手机列表也加走势小图，照原型 trade-ui）。
 * 数据来自 getSparklines（一次取全部品种，缓存键 sparklines，页面间共用）。
 */
export function Sparkline({ closes, up, width = 80, height = 24 }: { closes?: string[]; up: boolean; width?: number; height?: number }) {
  if (!closes || closes.length < 2) return <span className="spark-empty">—</span>;
  const v = closes.map(Number);
  const max = Math.max(...v);
  const min = Math.min(...v);
  const d = v.map((x, i) => `${i ? 'L' : 'M'}${((i * width) / (v.length - 1)).toFixed(1)},${(height - 2 - ((height - 4) * (x - min)) / (max - min || 1)).toFixed(1)}`).join('');
  return (
    <svg className={`spark ${up ? 'up' : 'down'}`} width={width} height={height} viewBox={`0 0 ${width} ${height}`} aria-hidden="true">
      <path d={d} fill="none" stroke="currentColor" strokeWidth="1.5" />
    </svg>
  );
}

