/**
 * 第 1 批（原型 P1、P2，Amos 11/17 确认）：充值第一次选网络才生成地址、拿不到地址可重试、低于 1 USDT 的提示（AC-W1、AC-W11）；
 * 提现两道审的提示和"等待放款审核"（TY-2、AC-W12）、单笔 5,000（AC-W4）；资金记录合成一页（TY-1）。
 */
import { fireEvent, screen, waitFor, within } from '@testing-library/react';
import { act } from 'react';
import { ApiError } from '../../api/errors';
import { setProtoState } from '../../api/prototype';
import { resetDb } from '../../mocks/db';
import { renderPage } from '../../test/renderApp';

const fail = { next: false };
/** 设了就等它放行再生成地址：让测试看得到"正在生成" */
const hold: { release?: () => void; gate?: Promise<void> } = {};
vi.mock('../../api/wallet', async (orig) => {
  const actual = await orig<typeof import('../../api/wallet')>();
  return {
    ...actual,
    requestDepositAddress: async (...a: Parameters<typeof actual.requestDepositAddress>) => {
      if (hold.gate) await hold.gate;
      if (fail.next) {
        fail.next = false;
        return Promise.reject(new ApiError('walletUnavailable', undefined, 503));
      }
      return actual.requestDepositAddress(...a);
    },
  };
});

import { DepositCard } from './DepositCard';
import Records from './Records';
import Withdraw from './Withdraw';

afterEach(() => {
  act(() => setProtoState({ scenario: 'normal', forceReject: 'none' }));
  resetDb();
});

describe('充值：第一次选网络才生成地址（AC-W1）', () => {
  it('没生成过的网络：先显示"正在生成"，生成后显示地址、"已生成"和低于 1 USDT 不入账的提示；不再写"ERC20 和 BSC 相同"', async () => {
    hold.gate = new Promise((r) => (hold.release = r));
    renderPage(<DepositCard />, '/assets');
    fireEvent.click(screen.getByRole('radio', { name: /BSC/ }));
    expect(await screen.findByText('正在生成 BSC 充值地址…')).toBeInTheDocument();
    expect(screen.getByText('通常 5 秒内显示，请不要关闭页面')).toBeInTheDocument();
    hold.gate = undefined;
    act(() => hold.release!());
    expect(await screen.findByText('低于 1 USDT 的转账不入账、无法找回', {}, { timeout: 4000 })).toBeInTheDocument();
    expect(screen.getByText('到账后会通过站内信、推送、邮件通知你')).toBeInTheDocument();
    expect(within(screen.getByRole('radio', { name: /BSC/ })).getByText('已生成')).toBeInTheDocument();
    expect(screen.queryByText(/相同/)).not.toBeInTheDocument();
    expect(screen.queryByText(/区块确认|个确认/)).not.toBeInTheDocument();
  });

  it('钱包服务不通：写"暂时无法获取充值地址"，可以重试，页面不白屏', async () => {
    fail.next = true;
    renderPage(<DepositCard />, '/assets');
    fireEvent.click(await screen.findByRole('radio', { name: /BSC/ }));
    expect(await screen.findByText('暂时无法获取充值地址，请稍后再试', {}, { timeout: 4000 })).toBeInTheDocument();
    expect(screen.getByText(/已经生成过的网络照常可用/)).toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: '重试' }));
    expect(await screen.findByText('低于 1 USDT 的转账不入账、无法找回', {}, { timeout: 4000 })).toBeInTheDocument();
  });
});

describe('提现：两道审（TD-1 B）', () => {
  it('表单上写明两道审和处理时段（TY-2 两句都在）；记录里"等待放款审核"、"放款中"和付款编号（AC-W12）', async () => {
    renderPage(<Withdraw />, '/assets/withdraw');
    expect(await screen.findByText('提现要过两道审核')).toBeInTheDocument();
    expect(screen.getByText(/处理时间东八区 8:00–23:00/)).toBeInTheDocument();
    expect(screen.getAllByText(/东八区 23:00 后的第二天 8:00 后处理/).length).toBeGreaterThan(0);
    expect(screen.getByText('等待放款审核（人工）')).toBeInTheDocument();
    await waitFor(() => expect(screen.getAllByText('等待放款审核').length).toBeGreaterThan(0));
    expect(screen.getAllByText('放款中').length).toBeGreaterThan(0);
    expect(screen.getByText(/付款编号 WD-20261116-7731/)).toBeInTheDocument();
  });

  it('单笔上限 5,000：输入 5,000.01 提交前就提示（AC-W4）', async () => {
    renderPage(<Withdraw />, '/assets/withdraw');
    const input = await screen.findByLabelText('提现金额');
    fireEvent.change(input, { target: { value: '5000.01' } });
    fireEvent.click(screen.getByRole('button', { name: '提交提现' }));
    expect(await screen.findByText('单笔最多 5,000 USDT')).toBeInTheDocument();
    expect(screen.getByText(/单笔 10 – 5,000/)).toBeInTheDocument();
  });
});

describe('资金记录：充值和提现合成一页（TY-1）', () => {
  it('两种都有；按类型、网络筛选；提现显示每一步的时间；筛选为空时可以清除', async () => {
    renderPage(<Records />, '/assets/records');
    expect(await screen.findByRole('heading', { name: '资金记录' })).toBeInTheDocument();
    await waitFor(() => expect(screen.getAllByText(/到账时间/).length).toBeGreaterThan(0));
    expect(screen.getAllByText('等待放款审核').length).toBeGreaterThan(0);
    expect(screen.getByText(/交给放款（等待放款审核）/)).toBeInTheDocument();
    expect(screen.getByText(/低于 1 USDT 的转账不入账/)).toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: '提现' }));
    expect(screen.queryAllByText(/到账时间/)).toHaveLength(0);
    fireEvent.click(screen.getByRole('button', { name: '充值' }));
    expect(screen.queryAllByText('等待放款审核')).toHaveLength(0);
    fireEvent.click(screen.getByRole('button', { name: 'BSC' }));
    fireEvent.change(screen.getByLabelText('时间'), { target: { value: '7d' } });
    const empty = screen.queryByText('这个筛选条件下没有记录');
    if (empty) {
      fireEvent.click(screen.getByRole('button', { name: '清除筛选' }));
      expect(screen.getAllByText(/到账时间/).length).toBeGreaterThan(0);
    }
  });

  it('没有记录时：引导去充值', async () => {
    act(() => setProtoState({ scenario: 'empty' }));
    renderPage(<Records />, '/assets/records');
    expect(await screen.findByText('还没有资金记录')).toBeInTheDocument();
    expect(screen.getByRole('link', { name: '去充值' })).toBeInTheDocument();
  });
});
