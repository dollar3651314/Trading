/**
 * 官网的整体布局：测试环境标识 → 风险提示条（常驻，W9）→ 导航 → 页面 → 页脚；手机版底部固定"登录 | 免费注册"（W8）。
 * 按宽度 1024 切换电脑、手机两套结构（C64）。
 */
import { useEffect } from 'react';
import { RouterProvider } from './app/router';
import { useRouter } from './app/routerContext';
import { EnvBanner, Footer, HeaderDesktop, HeaderMobile, RiskBar, StickyCta } from './components/Chrome';
import { useIsDesktop } from './hooks/useMediaQuery';
import { useI18n } from './i18n';
import { LangProvider } from './i18n/I18nProvider';
import type { Lang } from './i18n/lang';
import { LEGAL } from './content/legal';
import type { Page } from './lib/route';
import { HomeDesktop, HomeMobile } from './pages/Home';
import { LegalPage } from './pages/Legal';

/** 浏览器标签标题按页面设（测试环境验收 T-3）：首页用完整标题，法律页、不存在的页用"页面名｜Trading" */
function usePageTitle(page: Page) {
  const { t, lang } = useI18n();
  useEffect(() => {
    let name: string | null = null;
    if (page === 'terms' || page === 'privacy' || page === 'risk') name = LEGAL[page][lang].title;
    else if (page !== 'home') name = t('notFound.title');
    document.title = name === null ? t('meta.title') : t('meta.pageTitle', { page: name });
  }, [page, lang, t]);
}

function Shell() {
  const desktop = useIsDesktop();
  const { page } = useRouter();
  const { t } = useI18n();
  usePageTitle(page);
  // 直接打开带锚点的地址（例如 /#faq）时滚到对应区块
  useEffect(() => {
    const id = window.location.hash.slice(1);
    if (id) requestAnimationFrame(() => document.getElementById(id)?.scrollIntoView());
  }, []);
  let body;
  if (page === 'terms' || page === 'privacy' || page === 'risk') body = <LegalPage which={page} />;
  else if (page === 'home') body = desktop ? <HomeDesktop /> : <HomeMobile />;
  else
    body = (
      <main className="legal">
        <div className="wrap">
          <h1>{t('notFound.title')}</h1>
        </div>
      </main>
    );
  return (
    <div className={`site ${desktop ? 'is-desktop' : 'is-mobile'}`} data-layout={desktop ? 'desktop' : 'mobile'}>
      <EnvBanner />
      <RiskBar short={!desktop} />
      {desktop ? <HeaderDesktop /> : <HeaderMobile />}
      {body}
      <Footer />
      {!desktop && <StickyCta />}
    </div>
  );
}

export default function App({ initialLang }: { initialLang?: Lang }) {
  return (
    <LangProvider initial={initialLang}>
      <RouterProvider>
        <Shell />
      </RouterProvider>
    </LangProvider>
  );
}
