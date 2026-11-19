import { LEGAL } from './legal';
import { LEGAL_PAGES } from '../lib/route';

const CJK = /[\u3000-\u303f\u4e00-\u9fff\uff00-\uffef]/;
const text = (d: (typeof LEGAL)['terms']['en']) => [d.title, ...d.intro, ...d.sections.flatMap((s) => [s.h, ...s.p])].join('\n');

describe('法律页样板（AC2-08、F2-6）', () => {
  it.each(LEGAL_PAGES)('%s：中英文章节数和每节段落数一致', (p) => {
    const zh = LEGAL[p]['zh-CN'];
    const en = LEGAL[p].en;
    expect(en.sections.map((s) => s.p.length)).toEqual(zh.sections.map((s) => s.p.length));
    expect(en.intro.length).toBe(zh.intro.length);
  });

  it.each(LEGAL_PAGES)('%s：英文没有中文残留', (p) => {
    expect(CJK.test(text(LEGAL[p].en))).toBe(false);
  });

  // Amos 11/19「公司信息、联系邮箱、牌照等 都不写」（生产方案 v1 07 页 R-6）：连"待提供"的占位和联系邮箱也不出现
  it.each(LEGAL_PAGES)('%s：不写牌照、监管、公司主体、联系邮箱，也没有"待提供"的占位', (p) => {
    const zh = text(LEGAL[p]['zh-CN']);
    const en = text(LEGAL[p].en);
    expect(zh).not.toContain('待提供');
    expect(en).not.toMatch(/to be provided/i);
    expect(zh + en).not.toMatch(/[\w.+-]+@[\w-]+\.[\w.]+/);
    expect(zh).not.toMatch(/运营主体|牌照|监管与/);
    expect(en).not.toMatch(/operating entity|licen[cs]e/i);
    expect(zh).not.toMatch(/监管机构|持有.{0,6}牌照|牌照号|有限公司/);
    expect(en).not.toMatch(/regulated by|licensed by|licen[cs]e number/i);
    expect(en).not.toMatch(/ Ltd\b| Limited\b| Inc\./);
    expect(zh + en).not.toMatch(new RegExp('old\\s*brand|fal' + 'con\\s*x|qui' + 'ck\\s*come', 'i'));
  });

  it('写到的事实和系统一致（三条链、最小充值 1 USDT、新地址 24 小时生效）', () => {
    const all = LEGAL_PAGES.map((p) => text(LEGAL[p]['zh-CN'])).join('\n');
    expect(all).toMatch(/ERC20.*TRC20.*BSC/);
    expect(all).toContain('1 USDT');
    expect(all).toContain('24 小时');
  });
});
