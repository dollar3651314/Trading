/** 提现记录的共用显示（提现页、资金记录页）：每一步的时间。状态名见 withdrawStatus.ts。 */
import { useTranslation } from 'react-i18next';
import type { Withdrawal } from '../../api/types';
import { formatDateTime } from '../../lib/format';

/** 每一步进入的时间（到秒）：提交 → 冷静期结束 → 审核通过 → 等待放款审核 → 已完成 / 已退回 */
export function Timeline({ w }: { w: Withdrawal }) {
  const { t } = useTranslation();
  if (!w.steps || w.steps.length === 0) return null;
  return (
    <ol className="tl" aria-label={t('records.steps')}>
      {w.steps.map((st, i) => (
        <li key={i} className={i === w.steps!.length - 1 ? 'cur' : 'done'}>
          {t(`records.step.${st.status}`)} · <span className="num">{formatDateTime(st.at, { seconds: true })}</span>
        </li>
      ))}
    </ol>
  );
}

