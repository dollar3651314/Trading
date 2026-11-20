/** 品种列表的分类、搜索和自选（行情页、交易页共用），以及涨跌计算。 */
import { useMemo, useState } from 'react';
import type { Quote, SymbolCategory } from '../api/types';
import { useSymbols } from '../app/contexts';
import { cmp, ZERO } from '../lib/decimal';
import { changePercent } from '../lib/trading';
import { useFavorites } from './useFavorites';

export type Category = 'fav' | 'all' | SymbolCategory;
export const CATEGORIES: Category[] = ['fav', 'all', 'fx', 'metal', 'index', 'energy', 'crypto', 'stock'];

/** 分类 + 搜索 + 自选，行情页和交易页共用 */
export function useSymbolFilter(initialCategory: Category) {
  const { list, loading, error, reload } = useSymbols();
  const hot = useMemo(() => list.filter((s) => s.hot).map((s) => s.code), [list]);
  const fav = useFavorites(hot);
  const [q, setQ] = useState('');
  const [selected, setCat] = useState<Category>(initialCategory);
  // 只显示有品种的分类（自选、全部总在）：生产只开加密货币时外汇等标签点进去是空的（生产方案 v1 07 页 R-1）；选中的分类没有品种了就按"全部"
  const cats = useMemo(() => CATEGORIES.filter((c) => c === 'fav' || c === 'all' || list.some((s) => s.category === c)), [list]);
  const cat: Category = cats.includes(selected) ? selected : 'all';
  const filtered = useMemo(() => {
    const k = q.trim().toLowerCase();
    return list.filter(
      (s) =>
        (cat === 'all' || (cat === 'fav' ? fav.list.includes(s.code) : s.category === cat)) &&
        (!k || s.code.toLowerCase().includes(k) || s.nameZh.toLowerCase().includes(k) || s.nameEn.toLowerCase().includes(k)),
    );
  }, [list, q, cat, fav.list]);
  return { list, loading, error, reload, q, setQ, cat, setCat, cats, filtered, fav };
}

export type SymbolFilter = ReturnType<typeof useSymbolFilter>;

export function useChange(q?: Quote) {
  const chg = q ? changePercent(q.open, q.bid) : null;
  return { chg, up: chg !== null && cmp(chg, ZERO) >= 0 };
}

