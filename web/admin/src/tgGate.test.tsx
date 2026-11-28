/** 方案 B：仪表盘显示"非 Telegram 打开被拦次数（近 7 天）"和按平台的分布（模拟数据模式） */
import { screen } from '@testing-library/react';
import { Route, Routes } from 'react-router-dom';
import { DashboardPage } from './pages/DashboardPage';
import { renderAs } from './test/render';

describe('仪表盘：非 Telegram 打开被拦次数', () => {
  it('显示近 7 天合计和 iOS / 安卓 / 其他', async () => {
    renderAs(
      <Routes>
        <Route path="/" element={<DashboardPage />} />
      </Routes>,
      { path: '/' },
    );
    const card = await screen.findByTestId('dash-tg-gate');
    expect(card).toHaveTextContent('非 Telegram 打开被拦次数（近 7 天）');
    expect(card).toHaveTextContent('20');
    expect(card).toHaveTextContent('iOS 12 · 安卓 5 · 其他 3');
  });
});
