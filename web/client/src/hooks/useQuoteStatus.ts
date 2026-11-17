/**
 * 第 3 批（原型 P3）：报价是否可用。报价服务中断时后端推 quoteStatus false：交易页顶部"报价暂时中断"、下单按钮灰；恢复后自动恢复，不用刷新。
 */
import { useSyncExternalStore } from 'react';
import { subscribeQuoteStatus } from '../api/market';

let available = true;
const listeners = new Set<() => void>();
let unsub: (() => void) | null = null;

function subscribe(l: () => void): () => void {
  if (!unsub) {
    unsub = subscribeQuoteStatus((v) => {
      available = v;
      listeners.forEach((x) => x());
    });
  }
  listeners.add(l);
  return () => listeners.delete(l);
}

const get = () => available;

export function useQuoteAvailable(): boolean {
  return useSyncExternalStore(subscribe, get, get);
}
