/**
 * 后台数据接口（页面只能通过这里取数据）。
 * 两种模式（构建变量 VITE_API_MODE，见 ./mode.ts）：
 * - real（默认）：全部调用后端（./real.ts；S18a 登录、系统、客户、KYC、资金、通知，S18b 仪表盘、交易监控、风控、品种与行情、交易参数）；
 * - mock：全部用 src/mocks 的内存实现（原型预览站、单元测试）；只有"切换演示身份"和原型场景是原型独有的。
 * 注释里是真实接口路径（《项目索引》第 2.8 节）。
 *
 * Admin data API. Pages import from here only. "real" mode calls the backend for S18a endpoints; "mock" keeps the prototype.
 */
import * as mock from '../mocks/handlers';
import * as mockQuote from '../mocks/quoteService';
import * as runtime from '../mocks/runtime';
import { IS_MOCK } from './mode';
import * as real from './real';

export type { CurrentAdmin } from './types';
export type { ProtoIdentity } from '../mocks/handlers';
export type { Scenario } from '../mocks/runtime';
export { API_MODE, IS_MOCK } from './mode';
export { onAuthEvent } from './http';

// ---- 认证 ----
/** POST /api/admin/auth/login（令牌只在 HttpOnly Cookie 里，响应不带 token） */
export const login: typeof real.login = IS_MOCK ? mock.login : real.login;
/** POST /api/admin/auth/logout */
export const logout: typeof real.logout = IS_MOCK ? () => Promise.resolve() : real.logout;
/** POST /api/admin/auth/change-password（其他设备退出） */
export const changePassword: typeof real.changePassword = IS_MOCK ? mock.changePassword : real.changePassword;
/** GET /api/admin/me（当前管理员、角色、权限点、可见菜单）。模拟层按 adminId 取演示身份 */
export const getCurrentAdmin = (adminId?: number): ReturnType<typeof real.getCurrentAdmin> =>
  IS_MOCK ? mock.getCurrentAdmin(adminId ?? 1) : real.getCurrentAdmin();
/** 原型专用：切换演示身份（正式版没有这个接口；真实接口模式下调用直接失败，也不会打包模拟层） */
export const switchIdentity: typeof mock.switchIdentity = IS_MOCK ? mock.switchIdentity : () => Promise.reject(new Error('原型专用：真实接口模式没有切换演示身份'));

// ---- 仪表盘 ----
/** GET /api/admin/dashboard */
export const getDashboard: typeof real.getDashboard = IS_MOCK ? mock.getDashboard : real.getDashboard;
/** GET /api/admin/menu-badges */
export const getMenuBadges: typeof real.getMenuBadges = IS_MOCK ? mock.getMenuBadges : real.getMenuBadges;

// ---- 客户 ----
/** GET /api/admin/customers */
export const listCustomers: typeof real.listCustomers = IS_MOCK ? mock.listCustomers : real.listCustomers;
/** GET /api/admin/customers/{id} */
export const getCustomer: typeof real.getCustomer = IS_MOCK ? mock.getCustomer : real.getCustomer;
/** POST /api/admin/customers/{id}/freeze（高危） */
export const setCustomerFrozen: typeof real.setCustomerFrozen = IS_MOCK ? mock.setCustomerFrozen : real.setCustomerFrozen;
/** POST /api/admin/customers/{id}/withdraw-unfreeze（高危，withdraw:unfreeze，第 4 批：人工解除提现冻结，要管理员密码） */
export const unfreezeCustomerWithdraw: typeof real.unfreezeCustomerWithdraw = IS_MOCK ? mock.unfreezeCustomerWithdraw : real.unfreezeCustomerWithdraw;
/** PUT /api/admin/customers/{id}/profile（高危，v2 E1：姓名、出生日期、国籍） */
export const updateCustomerProfile: typeof real.updateCustomerProfile = IS_MOCK ? mock.updateCustomerProfile : real.updateCustomerProfile;
/** GET /api/admin/customers/adjust-limits */
export const getAdjustLimits: typeof real.getAdjustLimits = IS_MOCK ? mock.getAdjustLimits : real.getAdjustLimits;
/** POST /api/admin/customers/{id}/adjust（高危） */
export const adjustBalance: typeof real.adjustBalance = IS_MOCK ? mock.adjustBalance : real.adjustBalance;
/** GET /api/admin/customers/search（发通知时选客户） */
export const searchCustomers: typeof real.searchCustomers = IS_MOCK ? mock.searchCustomers : real.searchCustomers;

// ---- KYC ----
/** GET /api/admin/kyc */
export const listKyc: typeof real.listKyc = IS_MOCK ? mock.listKyc : real.listKyc;
/** GET /api/admin/kyc/{id} */
export const getKyc: typeof real.getKyc = IS_MOCK ? mock.getKyc : real.getKyc;
/** POST /api/admin/kyc/{id}/review */
export const reviewKyc: typeof real.reviewKyc = IS_MOCK ? mock.reviewKyc : real.reviewKyc;
/** GET /api/admin/kyc/{id}/images/{front|back|selfie} 的地址；原型没有照片，返回 null（显示占位图） */
export const kycImageUrl: typeof real.kycImageUrl = IS_MOCK ? () => null : real.kycImageUrl;

// ---- 资金 ----
/** GET /api/admin/deposits */
export const listDeposits: typeof real.listDeposits = IS_MOCK ? mock.listDeposits : real.listDeposits;
/** GET /api/admin/withdrawals */
export const listWithdrawals: typeof real.listWithdrawals = IS_MOCK ? mock.listWithdrawals : real.listWithdrawals;
/** GET /api/admin/withdrawals/summary */
export const getWithdrawalSummary: typeof real.getWithdrawalSummary = IS_MOCK ? mock.getWithdrawalSummary : real.getWithdrawalSummary;
/** GET /api/admin/withdrawals/{id} */
export const getWithdrawal: typeof real.getWithdrawal = IS_MOCK ? mock.getWithdrawal : real.getWithdrawal;
/** POST /api/admin/withdrawals/{id}/approve */
export const approveWithdrawal: typeof real.approveWithdrawal = IS_MOCK ? mock.approveWithdrawal : real.approveWithdrawal;
/** POST /api/admin/withdrawals/{id}/reject */
export const rejectWithdrawal: typeof real.rejectWithdrawal = IS_MOCK ? mock.rejectWithdrawal : real.rejectWithdrawal;
/** POST /api/admin/withdrawals/{id}/emergency-cancel（高危） */
export const emergencyCancelWithdrawal: typeof real.emergencyCancelWithdrawal = IS_MOCK ? mock.emergencyCancelWithdrawal : real.emergencyCancelWithdrawal;
// ---- v2 提现自动审批（内部文档 第 7 节） ----
/** GET /api/admin/withdraw-auto-approve：总开关、全部规则（现在 13 条）、版本 */
export const getAutoApproveSettings: typeof real.getAutoApproveSettings = IS_MOCK ? mock.getAutoApproveSettings : real.getAutoApproveSettings;
/** GET /api/admin/withdraw-auto-approve/changes：最近 20 次修改 */
export const listAutoApproveChanges: typeof real.listAutoApproveChanges = IS_MOCK ? mock.listAutoApproveChanges : real.listAutoApproveChanges;
/** PUT /api/admin/withdraw-auto-approve（高危，withdrawal:auto_rule_edit；版本不对 409 CONFIG_CHANGED） */
export const updateAutoApproveSettings: typeof real.updateAutoApproveSettings = IS_MOCK ? mock.updateAutoApproveSettings : real.updateAutoApproveSettings;
/** POST /api/admin/withdrawals/{id}/requery（第 1 批：向钱包提供方重新查付款状态） */
export const requeryWithdrawal: typeof real.requeryWithdrawal = IS_MOCK ? mock.requeryWithdrawal : real.requeryWithdrawal;
/** GET /api/admin/recon/status、POST /api/admin/recon/run（第 1 批） */
export const getReconStatus: typeof real.getReconStatus = IS_MOCK ? mock.getReconStatus : real.getReconStatus;
export const runRecon: typeof real.runRecon = IS_MOCK ? mock.runRecon : real.runRecon;
/** 第 1 批钱包提供方页：GET /api/admin/payment、POST /payment/check、PUT /payment/networks/{network}、POST /payment/switch（后两个高危） */
export const getPayment: typeof real.getPayment = IS_MOCK ? mock.getPayment : real.getPayment;
export const checkPayment: typeof real.checkPayment = IS_MOCK ? mock.checkPayment : real.checkPayment;
export const setPaymentNetwork: typeof real.setPaymentNetwork = IS_MOCK ? mock.setPaymentNetwork : real.setPaymentNetwork;
export const switchPaymentProvider: typeof real.switchPaymentProvider = IS_MOCK ? mock.switchPaymentProvider : real.switchPaymentProvider;
/** GET /api/admin/recon */
export const listRecon: typeof real.listRecon = IS_MOCK ? mock.listRecon : real.listRecon;
/** GET /api/admin/hot-wallets：热钱包余额和告警下限 */
export const listHotWallets: typeof real.listHotWallets = IS_MOCK ? mock.listHotWallets : real.listHotWallets;
/** POST /api/admin/recon/{id}/resolve */
export const resolveRecon: typeof real.resolveRecon = IS_MOCK ? mock.resolveRecon : real.resolveRecon;
/** GET /api/admin/tasks */
export const listTasks: typeof real.listTasks = IS_MOCK ? mock.listTasks : real.listTasks;
/** POST /api/admin/tasks/{id}/retry */
export const retryTask: typeof real.retryTask = IS_MOCK ? mock.retryTask : real.retryTask;

// ---- 交易监控 ----
/** GET /api/admin/orders */
export const listOrders: typeof real.listOrders = IS_MOCK ? mock.listOrders : real.listOrders;
/** GET /api/admin/positions */
export const listPositions: typeof real.listPositions = IS_MOCK ? mock.listPositions : real.listPositions;
/** GET /api/admin/positions/closed（已平仓记录，部分平仓标出原持仓编号） */
export const listClosedPositions: typeof real.listClosedPositions = IS_MOCK ? mock.listClosedPositions : real.listClosedPositions;
/** POST /api/admin/positions/{id}/liquidate（高危） */
export const liquidatePosition: typeof real.liquidatePosition = IS_MOCK ? mock.liquidatePosition : real.liquidatePosition;
/** GET /api/admin/pending */
export const listPendingOrders: typeof real.listPendingOrders = IS_MOCK ? mock.listPendingOrders : real.listPendingOrders;
/** POST /api/admin/pending/{id}/cancel（高危） */
export const forceCancelPending: typeof real.forceCancelPending = IS_MOCK ? mock.forceCancelPending : real.forceCancelPending;
/** GET /api/admin/trade-symbols */
export const listTradeSymbols: typeof real.listTradeSymbols = IS_MOCK ? mock.listTradeSymbols : real.listTradeSymbols;

// ---- 风控 ----
/** GET /api/admin/risk */
export const getRiskOverview: typeof real.getRiskOverview = IS_MOCK ? mock.getRiskOverview : real.getRiskOverview;
/** POST /api/admin/risk/global-pause（高危） */
export const setGlobalPause: typeof real.setGlobalPause = IS_MOCK ? mock.setGlobalPause : real.setGlobalPause;
/** POST /api/admin/risk/auto-liquidation（高危） */
export const setAutoLiquidation: typeof real.setAutoLiquidation = IS_MOCK ? mock.setAutoLiquidation : real.setAutoLiquidation;
/** POST /api/admin/risk/symbols/{symbol}/pause（高危） */
export const setSymbolPaused: typeof real.setSymbolPaused = IS_MOCK ? mock.setSymbolPaused : real.setSymbolPaused;
/** PUT /api/admin/risk/symbols/{symbol}/threshold（高危） */
export const setSymbolThreshold: typeof real.setSymbolThreshold = IS_MOCK ? mock.setSymbolThreshold : real.setSymbolThreshold;
/** PUT /api/admin/risk/platform-threshold（高危） */
export const setPlatformThreshold: typeof real.setPlatformThreshold = IS_MOCK ? mock.setPlatformThreshold : real.setPlatformThreshold;
/** PUT /api/admin/risk/per-user-limit（高危） */
export const setPerUserLimit: typeof real.setPerUserLimit = IS_MOCK ? mock.setPerUserLimit : real.setPerUserLimit;

// ---- 品种与行情（交易时段只读、休市日按品种，后台方案第 9 节） ----
/** GET /api/admin/symbols */
export const listSymbols: typeof real.listSymbols = IS_MOCK ? mock.listSymbols : real.listSymbols;
/** POST /api/admin/symbols（高危，v2 E3：新增品种，默认暂停开仓） */
export const createSymbol: typeof real.createSymbol = IS_MOCK ? mock.createSymbol : real.createSymbol;
/** PUT /api/admin/symbols/{code}（高危） */
export const updateSymbol: typeof real.updateSymbol = IS_MOCK ? mock.updateSymbol : real.updateSymbol;
/** POST /api/admin/symbols/{code}/enabled（高危） */
export const setSymbolEnabled: typeof real.setSymbolEnabled = IS_MOCK ? mock.setSymbolEnabled : real.setSymbolEnabled;
/** GET /api/admin/sessions（按品种，只读） */
export const listSessions: typeof real.listSessions = IS_MOCK ? mock.listSessions : real.listSessions;
/** GET /api/admin/holidays */
export const listHolidays: typeof real.listHolidays = IS_MOCK ? mock.listHolidays : real.listHolidays;
/** POST /api/admin/holidays */
export const addHoliday: typeof real.addHoliday = IS_MOCK ? mock.addHoliday : real.addHoliday;
/** DELETE /api/admin/holidays/{id} */
export const deleteHoliday: typeof real.deleteHoliday = IS_MOCK ? mock.deleteHoliday : real.deleteHoliday;
/** GET /api/admin/fx-rates */
export const listFxRates: typeof real.listFxRates = IS_MOCK ? mock.listFxRates : real.listFxRates;

// ---- 交易参数（只有追保线和追保通知间隔） ----
/** GET /api/admin/params */
export const getTradingParams: typeof real.getTradingParams = IS_MOCK ? mock.getTradingParams : real.getTradingParams;
/** PUT /api/admin/params（高危） */
export const updateTradingParams: typeof real.updateTradingParams = IS_MOCK ? mock.updateTradingParams : real.updateTradingParams;

// ---- 通知 ----
/** GET /api/admin/notify/templates */
export const listTemplates: typeof real.listTemplates = IS_MOCK ? mock.listTemplates : real.listTemplates;
/** PUT /api/admin/notify/templates/{code} */
export const updateTemplate: typeof real.updateTemplate = IS_MOCK ? mock.updateTemplate : real.updateTemplate;
/** POST /api/admin/notify/send（高危） */
export const sendManualNotification: typeof real.sendManualNotification = IS_MOCK ? mock.sendManualNotification : real.sendManualNotification;
/** GET /api/admin/notify/records */
export const listNotifyRecords: typeof real.listNotifyRecords = IS_MOCK ? mock.listNotifyRecords : real.listNotifyRecords;

// ---- 系统 ----
/** GET /api/admin/admins */
export const listAdmins: typeof real.listAdmins = IS_MOCK ? mock.listAdmins : real.listAdmins;
/** POST /api/admin/admins（高危；返回只显示一次的临时密码） */
export const createAdmin: typeof real.createAdmin = IS_MOCK ? mock.createAdmin : real.createAdmin;
/** PUT /api/admin/admins/{id}（高危） */
export const updateAdmin: typeof real.updateAdmin = IS_MOCK ? mock.updateAdmin : real.updateAdmin;
/** POST /api/admin/admins/{id}/status（高危） */
export const setAdminStatus: typeof real.setAdminStatus = IS_MOCK ? mock.setAdminStatus : real.setAdminStatus;
/** POST /api/admin/admins/{id}/reset-password（高危；返回只显示一次的临时密码） */
export const resetAdminPassword: typeof real.resetAdminPassword = IS_MOCK ? mock.resetAdminPassword : real.resetAdminPassword;
/** GET /api/admin/roles */
export const listRoles: typeof real.listRoles = IS_MOCK ? mock.listRoles : real.listRoles;
/** POST /api/admin/roles | PUT /api/admin/roles/{id}（高危） */
export const saveRole: typeof real.saveRole = IS_MOCK ? mock.saveRole : real.saveRole;
/** DELETE /api/admin/roles/{id}（高危） */
export const deleteRole: typeof real.deleteRole = IS_MOCK ? mock.deleteRole : real.deleteRole;
/** GET /api/admin/menus */
export const listMenus: typeof real.listMenus = IS_MOCK ? mock.listMenus : real.listMenus;
/** PUT /api/admin/menus/{key}（顺序、是否显示） */
export const updateMenu: typeof real.updateMenu = IS_MOCK ? mock.updateMenu : real.updateMenu;
/** GET /api/admin/permissions */
export const listPermissions: typeof real.listPermissions = IS_MOCK ? mock.listPermissions : real.listPermissions;
/** GET /api/admin/audit-logs */
export const listAuditLogs: typeof real.listAuditLogs = IS_MOCK ? mock.listAuditLogs : real.listAuditLogs;
/** GET /api/admin/audit-logs/actions */
export const listAuditActions: typeof real.listAuditActions = IS_MOCK ? mock.listAuditActions : real.listAuditActions;
/** GET /api/admin/configs */
export const listSystemConfigs: typeof real.listSystemConfigs = IS_MOCK ? mock.listSystemConfigs : real.listSystemConfigs;
/** PUT /api/admin/configs/{key}（高危） */
export const updateSystemConfig: typeof real.updateSystemConfig = IS_MOCK ? mock.updateSystemConfig : real.updateSystemConfig;

// ---- 原型场景（只在 mock 模式下有界面入口） ----
// 真实接口模式下恒为"正常数据"、版本号恒为 0；按编译期常量选，real 构建不打包模拟层（vite.config.ts 的 mockFree）
export const proto: Pick<typeof runtime, 'getScenario' | 'setScenario' | 'subscribeScenario' | 'getScenarioVersion'> = IS_MOCK
  ? {
      getScenario: runtime.getScenario,
      setScenario: runtime.setScenario,
      subscribeScenario: runtime.subscribeScenario,
      getScenarioVersion: runtime.getScenarioVersion,
    }
  : {
      getScenario: () => 'normal',
      setScenario: () => {},
      subscribeScenario: () => () => {},
      getScenarioVersion: () => 0,
    };

// ---- 第 3 批：报价服务（原型 P8～P11；数据在报价服务里，后台转发；只计量不收费） ----
/** GET /api/admin/quote-service/overview（外部客户、Key、默认限额、总开关） */
export const getQuoteOverview: typeof real.getQuoteOverview = IS_MOCK ? mockQuote.getQuoteOverview : real.getQuoteOverview;
/** POST /api/admin/quote-service/clients（高危） */
export const createQuoteClient: typeof real.createQuoteClient = IS_MOCK ? mockQuote.createQuoteClient : real.createQuoteClient;
/** POST /api/admin/quote-service/keys（高危；返回里的 Secret 只有这一次） */
export const createQuoteKey: typeof real.createQuoteKey = IS_MOCK ? mockQuote.createQuoteKey : real.createQuoteKey;
/** POST /api/admin/quote-service/keys/{keyId}/rotate（高危；旧 Key 立即停用） */
export const rotateQuoteKey: typeof real.rotateQuoteKey = IS_MOCK ? mockQuote.rotateQuoteKey : real.rotateQuoteKey;
/** POST /api/admin/quote-service/keys/{keyId}/disable（高危） */
export const disableQuoteKey: typeof real.disableQuoteKey = IS_MOCK ? mockQuote.disableQuoteKey : real.disableQuoteKey;
/** PUT /api/admin/quote-service/keys/{keyId}/limits（高危，只改这一个 Key） */
export const setQuoteKeyLimits: typeof real.setQuoteKeyLimits = IS_MOCK ? mockQuote.setQuoteKeyLimits : real.setQuoteKeyLimits;
/** PUT /api/admin/quote-service/defaults（高危，不影响已建 Key） */
export const setQuoteDefaults: typeof real.setQuoteDefaults = IS_MOCK ? mockQuote.setQuoteDefaults : real.setQuoteDefaults;
/** GET /api/admin/quote-service/usage */
export const getQuoteUsage: typeof real.getQuoteUsage = IS_MOCK ? mockQuote.getQuoteUsage : real.getQuoteUsage;
/** GET /api/admin/quote-service/usage/export（CSV 下载地址） */
export const quoteUsageExportUrl: typeof real.quoteUsageExportUrl = IS_MOCK ? mockQuote.quoteUsageExportUrl : real.quoteUsageExportUrl;
/** GET /api/admin/quote-service/licenses */
export const getQuoteLicenses: typeof real.getQuoteLicenses = IS_MOCK ? mockQuote.getQuoteLicenses : real.getQuoteLicenses;
/** PUT /api/admin/quote-service/licenses（高危） */
export const saveQuoteLicense: typeof real.saveQuoteLicense = IS_MOCK ? mockQuote.saveQuoteLicense : real.saveQuoteLicense;
/** PUT /api/admin/quote-service/gate（只有超管；打开要勾"Amos 已同意"） */
export const setQuoteGate: typeof real.setQuoteGate = IS_MOCK ? mockQuote.setQuoteGate : real.setQuoteGate;
/** GET /api/admin/quote-service/sources */
export const getQuoteSources: typeof real.getQuoteSources = IS_MOCK ? mockQuote.getQuoteSources : real.getQuoteSources;

// ---------- 第 4 批 4b：找回申请（权限 recovery:review，默认只有超管）、冻结时暂停的提现 ----------
/** GET /api/admin/recoveries?status= */
export const listRecoveries: typeof real.listRecoveries = IS_MOCK ? mock.listRecoveries : real.listRecoveries;
/** GET /api/admin/recoveries/{id} */
export const getRecovery: typeof real.getRecovery = IS_MOCK ? mock.getRecovery : real.getRecovery;
/** POST /api/admin/recoveries/{id}/approve（高危：核对记录 + 后台密码；告警邮件） */
export const approveRecovery: typeof real.approveRecovery = IS_MOCK ? mock.approveRecovery : real.approveRecovery;
/** POST /api/admin/recoveries/{id}/reject（驳回换绑会封申请人的 TG） */
export const rejectRecovery: typeof real.rejectRecovery = IS_MOCK ? mock.rejectRecovery : real.rejectRecovery;
/** POST /api/admin/recoveries/{id}/revoke（Amos 本人撤销，直接生效） */
export const revokeRecovery: typeof real.revokeRecovery = IS_MOCK ? mock.revokeRecovery : real.revokeRecovery;
/** POST /api/admin/recoveries/{id}/keep（争议路径撤销请求：维持批准，后台密码） */
export const keepRecovery: typeof real.keepRecovery = IS_MOCK ? mock.keepRecovery : real.keepRecovery;
/** POST /api/admin/withdrawals/{id}/release-held（继续放款） */
export const releaseHeldWithdrawal: typeof real.releaseHeldWithdrawal = IS_MOCK ? mock.releaseHeldWithdrawal : real.releaseHeldWithdrawal;
/** POST /api/admin/withdrawals/{id}/cancel-held（取消并退回，高危） */
export const cancelHeldWithdrawal: typeof real.cancelHeldWithdrawal = IS_MOCK ? mock.cancelHeldWithdrawal : real.cancelHeldWithdrawal;
/** POST /api/admin/customers/{id}/telegram（第 4 批 4c：只有超管；高危、后台密码、告警邮件） */
export const changeCustomerTelegram: typeof real.changeCustomerTelegram = IS_MOCK ? mock.changeCustomerTelegram : real.changeCustomerTelegram;

// ---------- 第 5 批：应急开关（TB-34） ----------
/** GET /api/admin/emergency */
export const getEmergency: typeof real.getEmergency = IS_MOCK ? mock.getEmergency : real.getEmergency;
/** POST /api/admin/emergency（只有 Amos 的后台账号；后台密码 + 原因；审计、告警邮件） */
export const setEmergency: typeof real.setEmergency = IS_MOCK ? mock.setEmergency : real.setEmergency;

// ---------- 第 6 批：暂停受理人工申请（TB-38）、告警邮件撤销链接（TB-37） ----------
/** GET /api/admin/recovery-intake */
export const getRecoveryIntake: typeof real.getRecoveryIntake = IS_MOCK ? mock.getRecoveryIntake : real.getRecoveryIntake;
/** POST /api/admin/recovery-intake（只有 Amos 的后台账号；后台密码 + 原因；审计、告警邮件） */
export const setRecoveryIntake: typeof real.setRecoveryIntake = IS_MOCK ? mock.setRecoveryIntake : real.setRecoveryIntake;
/** POST /api/admin/public/recovery-revoke/peek（不用登录） */
export const peekRecoveryRevoke: typeof real.peekRecoveryRevoke = IS_MOCK ? mock.peekRecoveryRevoke : real.peekRecoveryRevoke;
/** POST /api/admin/public/recovery-revoke（不用登录；只能撤销） */
export const revokeRecoveryByLink: typeof real.revokeRecoveryByLink = IS_MOCK ? mock.revokeRecoveryByLink : real.revokeRecoveryByLink;
