import type { Withdrawal } from '../../api/types';

/** 状态名：放款中再细分成"等待放款审核 / 放款中"（两道审的第二道，AC-W12） */
export function statusLabel(t: (k: string) => string, w: Withdrawal): string {
  if (w.status === 'broadcasting' && w.payoutStatus) return t(`withdraw.payout.${w.payoutStatus}`);
  return t(`withdraw.status.${w.status}`);
}

