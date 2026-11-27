/**
 * 网页"申请冻结提现"（第 4 批 4c，需求文档 TG机器人-08 页第 2 节，TB-27）：不用登录（TG 丢了的人也能用）。
 * 填客户号 + 只有本人知道的一项（做过身份认证的：姓名 + 证件号后 4 位；没做的：最近一笔充值的金额和日期）+ 图形验证码。
 * 不管信息对不对都显示同一句；只有验证码不对单独提示。冻结后只能人工解冻。浏览器标识是本地随机生成的编号（存 localStorage，读不到就不带）。
 */
import { useCallback, useEffect, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { errorKey } from '../../api/errors';
import type { FreezeCaptcha } from '../../api/types';
import { getFreezeCaptcha, requestWithdrawFreeze } from '../../api/wallet';
import { Segmented, TextField } from '../../components/Form';

const FP_KEY = 'fx.freeze.fp';

function fingerprint(): string | undefined {
  try {
    let v = localStorage.getItem(FP_KEY);
    if (!v) {
      v = `fp-${Array.from(crypto.getRandomValues(new Uint8Array(12)), (b) => b.toString(16).padStart(2, '0')).join('')}`;
      localStorage.setItem(FP_KEY, v);
    }
    return v;
  } catch {
    return undefined;
  }
}

type Mode = 'kyc' | 'deposit';

export function FreezeForm() {
  const { t } = useTranslation();
  const [mode, setMode] = useState<Mode>('kyc');
  const [customerNo, setCustomerNo] = useState('');
  const [name, setName] = useState('');
  const [last4, setLast4] = useState('');
  const [amount, setAmount] = useState('');
  const [date, setDate] = useState('');
  const [answer, setAnswer] = useState('');
  const [cap, setCap] = useState<FreezeCaptcha | null>(null);
  const [busy, setBusy] = useState(false);
  const [done, setDone] = useState<string | null>(null);
  const [err, setErr] = useState<string | null>(null);
  const [submitted, setSubmitted] = useState(false);

  const loadCaptcha = useCallback(() => {
    setAnswer('');
    getFreezeCaptcha(fingerprint()).then(setCap, (e) => setErr(t(errorKey(e))));
  }, [t]);

  useEffect(() => {
    loadCaptcha();
  }, [loadCaptcha]);

  const missing = (v: string) => (submitted && !v.trim() ? ['validation.required'] : undefined);
  const ready = customerNo.trim() && answer.trim() && (mode === 'kyc' ? name.trim() && last4.trim() : amount.trim() && date.trim());

  const submit = async () => {
    setSubmitted(true);
    setErr(null);
    if (!ready || !cap) return;
    setBusy(true);
    try {
      const r = await requestWithdrawFreeze({
        customerNo: customerNo.trim(),
        ...(mode === 'kyc' ? { name: name.trim(), docLast4: last4.trim() } : { depositAmount: amount.trim(), depositDate: date.trim() }),
        captchaId: cap.id,
        captcha: answer.trim(),
        fp: fingerprint(),
      });
      setDone(r.message);
    } catch (e) {
      setErr(t(errorKey(e)));
      loadCaptcha();
    } finally {
      setBusy(false);
    }
  };

  if (done) {
    return (
      <div className="notice ok" role="status" data-testid="freeze-done">
        {done}
        <p className="small">{t('freeze.after')}</p>
      </div>
    );
  }

  return (
    <div className="pin-form" data-testid="freeze-form">
      <p className="small">{t('freeze.intro')}</p>
      <TextField label={t('freeze.customerNo')} value={customerNo} onChange={(v) => setCustomerNo(v.replace(/[^\d#]/g, ''))} inputMode="numeric" mono errors={missing(customerNo)} />
      <div className="field">
        <div className="field-label">
          <span>{t('freeze.proof')}</span>
        </div>
        <Segmented
          label={t('freeze.proof')}
          value={mode}
          onChange={setMode}
          options={[
            { value: 'kyc', label: t('freeze.modeKyc') },
            { value: 'deposit', label: t('freeze.modeDeposit') },
          ]}
        />
      </div>
      {mode === 'kyc' ? (
        <>
          <TextField label={t('freeze.name')} value={name} onChange={setName} errors={missing(name)} autoComplete="off" />
          <TextField label={t('freeze.last4')} value={last4} onChange={(v) => setLast4(v.slice(0, 4))} mono errors={missing(last4)} autoComplete="off" />
        </>
      ) : (
        <>
          <TextField label={t('freeze.amount')} value={amount} onChange={(v) => setAmount(v.replace(/[^\d.]/g, ''))} inputMode="decimal" mono suffix="USDT" errors={missing(amount)} />
          <TextField label={t('freeze.date')} value={date} onChange={setDate} type="date" errors={missing(date)} hint={t('freeze.dateHint')} />
        </>
      )}
      <div className="captcha-row">
        {cap ? <img src={`data:image/png;base64,${cap.pngBase64}`} alt={t('freeze.captchaAlt')} className="captcha-img" /> : <span className="captcha-img" aria-busy="true" />}
        <button type="button" className="btn btn-ghost btn-sm" onClick={loadCaptcha}>
          {t('freeze.newCaptcha')}
        </button>
      </div>
      <TextField label={t('freeze.captcha')} value={answer} onChange={(v) => setAnswer(v.toUpperCase())} autoComplete="off" mono errors={missing(answer)} hint={cap?.hard ? t('freeze.hardHint') : undefined} />
      {err && (
        <div className="notice error" role="alert">
          {err}
        </div>
      )}
      <button type="button" className="btn btn-danger btn-block" onClick={submit} disabled={busy}>
        {busy ? t('common.submitting') : t('freeze.submit')}
      </button>
    </div>
  );
}
