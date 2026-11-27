/**
 * 第 4 批审查 🟡：TG 客户第一次提交 KYC 是安全事件，后端作废了全部网页会话（返回 relogin: true）——页面去提示页，写明回机器人重新授权、打开交易，
 * 不是等下一次请求 401 莫名其妙跳回登录页。
 */
import { fireEvent, screen } from '@testing-library/react';
import { Route, Routes } from 'react-router-dom';
import { renderPage } from '../../test/renderApp';
import TgHint from '../tg/TgHint';
import Kyc from './Kyc';

const submitKyc = vi.fn();
vi.mock('../../api/user', async (orig) => ({
  ...(await orig<typeof import('../../api/user')>()),
  getKyc: async () => ({ status: 'none' }),
  submitKyc: (...a: unknown[]) => submitKyc(...a),
}));

beforeAll(() => {
  URL.createObjectURL ??= () => 'blob:x';
  URL.revokeObjectURL ??= () => undefined;
});

async function fillAndSubmit() {
  renderPage(
    <Routes>
      <Route path="/me/kyc" element={<Kyc />} />
      <Route path="/tg" element={<TgHint />} />
    </Routes>,
    '/me/kyc',
  );
  fireEvent.change(await screen.findByLabelText('证件号'), { target: { value: '11010519491231002X' } });
  for (const slot of ['front', 'back', 'selfie']) {
    fireEvent.change(document.getElementById(`kyc-${slot}`)!, { target: { files: [new File(['x'], `${slot}.png`, { type: 'image/png' })] } });
  }
  fireEvent.click(screen.getByRole('button', { name: '提交审核' }));
}

describe('KYC 提交以后会话被作废', () => {
  it('relogin: true 时去提示页：网页已全部退出，回机器人点「重新授权」再「打开交易」', async () => {
    submitKyc.mockResolvedValue({ status: 'pending', relogin: true });
    await fillAndSubmit();
    expect(await screen.findByTestId('tg-reason')).toHaveTextContent('点「重新授权」，再点「打开交易」');
  });

  it('不是安全事件时留在本页，显示审核中', async () => {
    submitKyc.mockResolvedValue({ status: 'pending', docType: 'idCard', docNumber: '****002X', submittedAt: '2026-11-27T00:00:00Z' });
    await fillAndSubmit();
    await screen.findByText(/审核中/);
    expect(screen.queryByTestId('tg-reason')).not.toBeInTheDocument();
  });
});
