/** 时间显示：真实接口的 ISO UTC 和模拟数据的 "YYYY-MM-DD HH:mm:ss" 都按 UTC+8 显示 */
import { contact, fullTime, shortTime } from './display';

describe('时间格式化', () => {
  it('ISO UTC 换算成 UTC+8', () => {
    expect(fullTime('2026-10-14T01:12:33Z')).toBe('2026-10-14 09:12:33');
    expect(fullTime('2026-10-14T20:00:00.123456Z')).toBe('2026-10-15 04:00:00');
    expect(shortTime('2026-10-14T01:12:33Z')).toBe('10-14 09:12');
  });

  it('模拟数据的格式原样显示；空值显示破折号', () => {
    expect(fullTime('2026-10-14 09:12:33')).toBe('2026-10-14 09:12:33');
    expect(shortTime('2026-10-14 09:12:33')).toBe('10-14 09:12');
    expect(fullTime(null)).toBe('—');
    expect(shortTime(undefined)).toBe('—');
  });
});

describe('contact（TG 机器人第 1 批：纯 Telegram 客户没有邮箱）', () => {
  it('有邮箱显示邮箱；没有邮箱显示 TG @用户名；都没有显示 TG', () => {
    expect(contact('a@example.com', 'amos')).toBe('a@example.com');
    expect(contact(null, 'amos')).toBe('TG @amos');
    expect(contact(null, null)).toBe('TG');
    expect(contact(undefined)).toBe('TG');
  });
});
