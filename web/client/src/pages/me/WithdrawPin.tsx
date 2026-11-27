/**
 * 提现密码（TG 机器人第 4 批）：第一次设置 / 修改 / 忘记（绑了邮箱的收验证码重设；没邮箱的走人工找回）。全部用随机键盘输入（PinPad）。
 * 设置、修改、重设都是安全事件：成功后这个账户的网页会话全部作废，24 小时（重设是 48 小时）内不能提现；成功后清掉本地会话、去提示页 /tg?r=security（回机器人「重新授权」再「打开交易」）。
 */
import { useState } from "react";
import { useTranslation } from "react-i18next";
import { errorKey, isApiError } from "../../api/errors";
import type { PinInput } from "../../api/types";
import {
  changePin,
  getPinStatus,
  resetPin,
  sendPinResetCode,
  setPin,
} from "../../api/wallet";
import { AsyncView } from "../../components/AsyncView";
import { TextField } from "../../components/Form";
import { PageHeader } from "../../components/Layout";
import { PinPad } from "../../components/PinPad";
import { useAsync } from "../../hooks/useAsync";
import { useSecurityRelogin } from "../../app/useSecurityRelogin";

type Mode = "set" | "change" | "forgot";

function PinForm({
  mode,
  hasEmail,
  onDone,
}: {
  mode: Mode;
  hasEmail: boolean;
  onDone: () => void;
}) {
  const { t } = useTranslation();
  const [old, setOld] = useState<PinInput | null>(null);
  const [pin, setPinV] = useState<PinInput | null>(null);
  const [confirm, setConfirm] = useState<PinInput | null>(null);
  const [code, setCode] = useState("");
  const [sent, setSent] = useState(false);
  const [round, setRound] = useState(0);
  const [busy, setBusy] = useState(false);
  const [err, setErr] = useState<string | null>(null);
  const [fieldErr, setFieldErr] = useState<Record<string, string[]>>({});
  const ready =
    !!pin &&
    !!confirm &&
    (mode !== "change" || !!old) &&
    (mode !== "forgot" || /^\d{6}$/.test(code.trim()));

  if (mode === "forgot" && !hasEmail) {
    return <div className="notice info">{t("pin.forgotNoEmail")}</div>;
  }

  const submit = async () => {
    if (!ready || !pin || !confirm) return;
    setBusy(true);
    setErr(null);
    setFieldErr({});
    try {
      if (mode === "set") await setPin(pin, confirm);
      else if (mode === "change" && old) await changePin(old, pin, confirm);
      else await resetPin(code, pin, confirm);
      onDone();
    } catch (e) {
      if (isApiError(e) && e.fieldErrors) setFieldErr(e.fieldErrors);
      else setErr(t(errorKey(e)));
      // 键盘用过一次就失效：换新的、清空重输
      setRound((r) => r + 1);
    } finally {
      setBusy(false);
    }
  };

  const sendCode = async () => {
    setErr(null);
    try {
      await sendPinResetCode();
      setSent(true);
    } catch (e) {
      setErr(t(errorKey(e)));
    }
  };

  return (
    <div className="pin-form">
      {mode === "forgot" && (
        <TextField
          label={t("pin.emailCode")}
          value={code}
          onChange={setCode}
          inputMode="numeric"
          mono
          maxLength={6}
          addon={
            <button
              type="button"
              className="btn btn-ghost addon-btn"
              onClick={sendCode}
              disabled={sent}
            >
              {sent ? t("pin.codeSent") : t("pin.sendCode")}
            </button>
          }
          errors={fieldErr.code}
        />
      )}
      {mode === "change" && (
        <PinPad
          id="pin-old"
          label={t("pin.old")}
          onChange={setOld}
          resetKey={round}
          errors={fieldErr.old}
          note={false}
        />
      )}
      <PinPad
        id="pin-new"
        label={mode === "set" ? t("pin.new") : t("pin.newPin")}
        onChange={setPinV}
        resetKey={round}
        errors={fieldErr.pin}
        note={false}
      />
      <PinPad
        id="pin-confirm"
        label={t("pin.confirm")}
        onChange={setConfirm}
        resetKey={round}
        errors={fieldErr.confirm}
      />
      <p className="muted small">
        {t(mode === "forgot" ? "pin.ruleReset" : "pin.rule")}
      </p>
      {err && (
        <div className="notice error" role="alert">
          {err}
        </div>
      )}
      <button
        type="button"
        className="btn btn-primary btn-lg btn-block sticky-submit"
        onClick={submit}
        disabled={busy || !ready}
      >
        {busy ? t("common.submitting") : t(`pin.submit.${mode}`)}
      </button>
    </div>
  );
}

export default function WithdrawPin() {
  const { t } = useTranslation();
  const s = useAsync(getPinStatus, "withdraw-pin");
  const [mode, setMode] = useState<Mode | null>(null);
  // 成功以后网页会话已全部作废（安全事件）：去提示页，写明回机器人重新授权、打开交易
  const relogin = useSecurityRelogin();
  return (
    <div className="page page-narrow">
      <PageHeader title={t("pin.title")} back="/me" />
      <section className="card pin-page">
        <AsyncView state={s}>
          {(st) => {
            const m: Mode = mode ?? (st.set ? "change" : "set");
            return (
              <>
                {st.withdrawFrozen && (
                  <div className="notice error">
                    {t("errors.withdrawFrozen")}
                  </div>
                )}
                {st.lockedUntil && (
                  <div className="notice warn">{t("errors.pinLocked")}</div>
                )}
                {st.set && (
                  <div className="seg pin-modes" role="tablist">
                    {(["change", "forgot"] as const).map((x) => (
                      <button
                        key={x}
                        type="button"
                        role="tab"
                        aria-selected={m === x}
                        className={m === x ? "on" : ""}
                        onClick={() => setMode(x)}
                      >
                        {t(`pin.mode.${x}`)}
                      </button>
                    ))}
                  </div>
                )}
                {!st.set && <p className="muted small">{t("pin.setIntro")}</p>}
                <PinForm
                  key={m}
                  mode={m}
                  hasEmail={st.hasEmail}
                  onDone={relogin}
                />
              </>
            );
          }}
        </AsyncView>
      </section>
    </div>
  );
}
