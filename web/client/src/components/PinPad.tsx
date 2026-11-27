/**
 * 提现密码的随机键盘（TG 机器人第 4 批，需求文档 TG机器人-03 页第 6 节、决定清单 TB-5 / TB-14）。
 * 键盘由服务器发（0～9 的位置每次打乱，5 分钟、只能用一次）；按键只在本页记"按了第几个键"，提交时把位置交给服务器换算——
 * 请求、页面里都没有密码本身，同一个位置下次代表别的数字。已输几位用 ● 显示。每次提交以后要换新键盘（resetKey 变了就重新要）。
 */
import { useCallback, useEffect, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { errorKey } from '../api/errors';
import type { PinInput, PinKeypad } from '../api/types';
import { getPinKeypad } from '../api/wallet';
import { FieldErrors } from './Form';

export const PIN_LENGTH = 6;

export function PinPad({ label, onChange, errors, resetKey = 0, id, note = true }: { label: string; onChange: (v: PinInput | null) => void; errors?: string[]; resetKey?: number; id: string; /** 一页有几副键盘时只在最后一副下面写说明 */ note?: boolean }) {
  const { t } = useTranslation();
  const [pad, setPad] = useState<PinKeypad | null>(null);
  const [failed, setFailed] = useState<string | null>(null);
  const [positions, setPositions] = useState<number[]>([]);

  const load = useCallback(() => {
    setPad(null);
    setFailed(null);
    setPositions([]);
    onChange(null);
    getPinKeypad().then(setPad, (e) => setFailed(t(errorKey(e))));
    // onChange 每次渲染都是新的：只在换键盘时调
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [t]);

  useEffect(() => {
    load();
  }, [load, resetKey]);

  const update = (next: number[]) => {
    setPositions(next);
    onChange(pad && next.length === PIN_LENGTH ? { keypadId: pad.keypadId, positions: next } : null);
  };
  const press = (i: number) => {
    if (positions.length < PIN_LENGTH) update([...positions, i]);
  };

  const errId = `${id}-err`;
  return (
    <div className="pinpad" role="group" aria-labelledby={`${id}-label`} aria-describedby={errors?.length ? errId : undefined}>
      <div className="pinpad-head">
        <span id={`${id}-label`}>{label}</span>
        <span className="pinpad-dots" aria-label={t('pin.entered', { n: positions.length, total: PIN_LENGTH })} data-testid={`${id}-dots`}>
          {Array.from({ length: PIN_LENGTH }, (_, i) => (
            <i key={i} className={i < positions.length ? 'on' : ''} />
          ))}
        </span>
      </div>
      {failed ? (
        <div className="notice error" role="alert">
          {failed}{' '}
          <button type="button" className="btn btn-ghost btn-sm" onClick={load}>
            {t('common.retry')}
          </button>
        </div>
      ) : !pad ? (
        <div className="pinpad-grid loading" aria-busy="true" />
      ) : (
        <div className="pinpad-grid">
          {pad.digits.slice(0, 9).map((d, i) => (
            <button key={i} type="button" className="pinpad-key num" onClick={() => press(i)} disabled={positions.length >= PIN_LENGTH}>
              {d}
            </button>
          ))}
          <button type="button" className="pinpad-key fn" onClick={() => update([])}>
            {t('pin.clear')}
          </button>
          <button type="button" className="pinpad-key num" onClick={() => press(9)} disabled={positions.length >= PIN_LENGTH}>
            {pad.digits[9]}
          </button>
          <button type="button" className="pinpad-key fn" onClick={() => update(positions.slice(0, -1))} aria-label={t('pin.backspace')}>
            ⌫
          </button>
        </div>
      )}
      {note && <p className="muted small pinpad-note">{t('pin.note')}</p>}
      <FieldErrors id={errId} errors={errors} />
    </div>
  );
}
