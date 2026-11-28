/**
 * Telegram 内置浏览器识别诊断页 /tg-check（11/28 Amos：网页只想在 Telegram 里用，先查清楚 iOS / 安卓上 Telegram 的内置浏览器认不认得出）。
 * 不需要登录、不受"第一阶段只走 Telegram"限制，在哪里打开都显示。列出浏览器自己报告的信息 + 服务器看到的几个请求头
 * （GET /api/public/tg-check，不带 Cookie），字号大、长内容自动换行，方便手机截图。
 * 不跟踪、不上报：页面只读，不把这些信息发给服务器；检查 Cookie / localStorage 能不能用时写一个测试值马上删掉。
 */
import { useEffect, useState } from 'react';
import { collectClientInfo, type Row } from './tgCheckLib';

type ServerView = Record<string, string | null>;

export default function TgCheck() {
  const [client] = useState<Row[]>(() => collectClientInfo());
  const [server, setServer] = useState<ServerView | string | null>(null);
  useEffect(() => {
    let alive = true;
    // 不带 Cookie：服务器只回这几个请求头
    fetch('/api/public/tg-check', { credentials: 'omit', cache: 'no-store' })
      .then((r) => (r.ok ? r.json() : Promise.reject(new Error(`HTTP ${r.status}`))))
      .then(
        (v: ServerView) => alive && setServer(v),
        (e: unknown) => alive && setServer(`没取到：${String(e instanceof Error ? e.message : e)}`),
      );
    return () => {
      alive = false;
    };
  }, []);
  return (
    <main className="tg-check" data-testid="tg-check">
      <h1>浏览器环境检查</h1>
      <p className="muted">请整页截图发给我们。这一页不登录、不记录，只显示下面这些信息。</p>
      <h2>浏览器这边看到的</h2>
      <table>
        <tbody>
          {client.map(([k, v]) => (
            <tr key={k}>
              <th scope="row">{k}</th>
              <td data-testid={`c-${k}`}>{v}</td>
            </tr>
          ))}
        </tbody>
      </table>
      <h2>服务器这边看到的请求头</h2>
      {server === null ? (
        <p className="muted">读取中…</p>
      ) : typeof server === 'string' ? (
        <p className="muted" data-testid="s-error">
          {server}
        </p>
      ) : (
        <table>
          <tbody>
            {Object.entries(server).map(([k, v]) => (
              <tr key={k}>
                <th scope="row">{k}</th>
                <td data-testid={`s-${k}`}>{v ?? '（没有）'}</td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
      <p className="muted small">检查时间 {new Date().toLocaleString('zh-CN')}</p>
    </main>
  );
}
