// 手机视口冒烟：用系统 Chrome 打开构建产物，逐页检查
//   1) 有没有横向溢出（这是手机适配最常见的回归）
//   2) 控制台有没有报错（覆盖只有手机才会走到的分支）
//   3) 抽屉导航 / 设置分类抽屉这类只在手机上出现的交互能否打开
// 用法：node scripts/mobile-smoke.mjs [baseUrl]
// 依赖系统已安装的 Chrome（channel: 'chrome'），不下载 Playwright 浏览器。
import { chromium } from '@playwright/test';
import { mkdir } from 'node:fs/promises';
import {
    onboardingGuideGroups,
    ONBOARDING_GUIDE_INTRO_STORAGE_KEY,
    ONBOARDING_GUIDE_VERSION,
    ONBOARDING_GUIDE_STORAGE_PREFIX
} from '../src/config/onboardingGuide.js';

const BASE_URL = process.argv[2] || 'http://127.0.0.1:4173';
const API_HOST = '127.0.0.1:6521';
const SHOT_DIR = '.narrafork/smoke';
const VIEWPORT = { width: 375, height: 812 };

// 未登录也能进的页面（requiresAuth 的路由会被守卫送去 /login，不在这里测）
const ROUTES = [
    ['home', '/#/'],
    ['discover', '/#/discover'],
    ['ranking', '/#/ranking'],
    ['settings', '/#/settings'],
    ['local-music', '/#/LocalMusic'],
    ['cloud-drive', '/#/CloudDrive'],
    ['recognize', '/#/recognize'],
    ['playlist-detail', '/#/playlistDetail?id=test']
];

// 首次运行的两层遮罩（用户条款 + 新手引导）会盖住整页，先置位再进页面。
// 键名由引导配置推导，新增引导分组时这里不需要跟着改。
const SEED_STORAGE = {
    disclaimerAccepted: 'true',
    [ONBOARDING_GUIDE_INTRO_STORAGE_KEY]: ONBOARDING_GUIDE_VERSION,
    ...Object.fromEntries(onboardingGuideGroups.map(guide => [
        guide.storageKey || `${ONBOARDING_GUIDE_STORAGE_PREFIX}${guide.key}:version`,
        guide.version
    ]))
};

const seedFirstRunState = (page) => page.addInitScript((entries) => {
    for (const [key, value] of Object.entries(entries)) localStorage.setItem(key, value);
    // 设置项统一存在一个 JSON 里，预置成"关闭引导"，避免后续版本再弹新引导
    localStorage.setItem('settings', JSON.stringify({ onboardingGuide: 'off', language: 'zh-CN' }));
}, SEED_STORAGE);

const stubApi = async (page) => {
    // 只掐外部静态资源（图标/字体/版本检查），让业务接口自然失败：
    // 页面本来就要能在后端不可达时渲染出空状态，用假数据反而会掩盖真实的崩溃。
    await page.route('**/*', async (route) => {
        const url = route.request().url();
        if (url.startsWith(BASE_URL)) return route.continue();
        return route.abort();
    });
};

// 后端不可达时打印的错误不算回归，只有未捕获异常（页面白屏级）才算
const IGNORED_CONSOLE = /Failed to load resource|Failed to fetch|NetworkError|ERR_FAILED|Network Error|AxiosError|No response received|Failed to register device|获取最新版本号失败|服务器未响应|未处理的 Promise 拒绝/;
const VERBOSE = !!process.env.SMOKE_VERBOSE;

const measure = (page) => page.evaluate(() => {
    const root = document.documentElement;
    const overflow = root.scrollWidth - root.clientWidth;
    // 找出真正越界的元素，便于定位是哪个组件把页面撑宽
    const offenders = [];
    for (const element of document.body.querySelectorAll('*')) {
        const rect = element.getBoundingClientRect();
        if (rect.width === 0 || rect.height === 0) continue;
        const style = getComputedStyle(element);
        if (style.position === 'fixed' || style.visibility === 'hidden' || style.display === 'none') continue;
        if (rect.right > root.clientWidth + 1 || rect.left < -1) {
            offenders.push({
                selector: `${element.tagName.toLowerCase()}${element.className ? `.${String(element.className).trim().split(/\s+/).join('.')}` : ''}`,
                left: Math.round(rect.left),
                right: Math.round(rect.right)
            });
        }
    }
    return { overflow, offenders: offenders.slice(0, 6) };
});

const run = async () => {
    await mkdir(SHOT_DIR, { recursive: true });
    const browser = await chromium.launch({ channel: 'chrome' });
    const results = [];
    const failures = [];

    for (const [name, path] of ROUTES) {
        const context = await browser.newContext({
            viewport: VIEWPORT,
            isMobile: true,
            hasTouch: true,
            deviceScaleFactor: 2
        });
        const page = await context.newPage();
        const errors = [];
        page.on('pageerror', error => {
            const text = `pageerror: ${error.message}`;
            if (VERBOSE) console.log(`[${name}] ${text}`);
            // 后端不可达导致的未捕获拒绝同样是环境噪声，不是页面结构问题
            if (/Network Error|Failed to fetch|NetworkError/.test(error.message)) return;
            errors.push(text);
        });
        page.on('console', message => {
            if (message.type() !== 'error') return;
            const text = message.text();
            if (VERBOSE) console.log(`[${name}] ${text.slice(0, 300).replace(/\s+/g, ' ')}`);
            if (IGNORED_CONSOLE.test(text)) return;
            errors.push(`console: ${text.slice(0, 200)}`);
        });
        await seedFirstRunState(page);
        await stubApi(page);
        let moreMenu = null;
        let cachePanel = null;

        await page.goto(`${BASE_URL}${path}`, { waitUntil: 'domcontentloaded' });
        await page.waitForTimeout(1200);

        const layout = await measure(page);
        await page.screenshot({ path: `${SHOT_DIR}/${name}.png`, fullPage: false });

        // 抽屉导航：只在手机上存在的入口，能开就说明共享层没退化
        let drawerOpened = null;
        if (name === 'home') {
            const trigger = page.locator('.mobile-header-button').first();
            if (await trigger.count()) {
                await trigger.click({ force: true });
                await page.waitForTimeout(400);
                drawerOpened = await page.locator('.mobile-drawer').isVisible().catch(() => false);
                await page.screenshot({ path: `${SHOT_DIR}/${name}-drawer.png` });
                // 抽屉的遮罩会盖住播放器，先收起再验证底栏交互
                await page.locator('.drawer-close').first().click({ force: true }).catch(() => {});
                await page.waitForTimeout(350);
            }
        }
        // 设置页分类抽屉：手柄必须存在且能唤出侧栏
        let settingsDrawer = null;
        if (name === 'settings') {
            const handle = page.locator('.settings-sidebar-handle');
            if (await handle.count()) {
                await handle.click({ force: true });
                await page.waitForTimeout(400);
                settingsDrawer = await page.locator('.settings-sidebar.open').isVisible().catch(() => false);
                await page.screenshot({ path: `${SHOT_DIR}/${name}-sidebar.png` });
            }
            // 抽屉里逐项点开，找到"缓存容量"卡片并进入它的二级面板
            cachePanel = false;
            for (let index = 0; index < await page.locator('.settings-sidebar .sidebar-item').count(); index++) {
                if (!await page.locator('.settings-sidebar.open').count()) {
                    await handle.click({ force: true });
                    await page.waitForTimeout(250);
                }
                await page.locator('.settings-sidebar .sidebar-item').nth(index).click({ force: true });
                await page.waitForTimeout(250);
                const card = page.locator('.setting-card:visible', { hasText: '缓存容量' });
                if (await card.count()) {
                    // 提示条可能正盖在卡片上，force 点击跳过命中检查；失败按打不开处理
                    await card.first().click({ force: true, timeout: 3000 }).catch(() => {});
                    await page.waitForTimeout(350);
                    cachePanel = await page.locator('.cache-limit-panel').isVisible().catch(() => false);
                    await page.screenshot({ path: `${SHOT_DIR}/${name}-cache-panel.png` });
                    break;
                }
            }
        }

        // 播放器"更多"菜单：手机上模式/音量都收在这里，必须能展开且不撑破视口
        if (name === 'home') {
            const trigger = page.locator('.player-more .extra-btn').first();
            if (await trigger.count()) {
                await trigger.click({ force: true });
                await page.waitForTimeout(350);
                moreMenu = await page.locator('.player-menu.more-menu').isVisible().catch(() => false);
                const openLayout = await measure(page);
                if (openLayout.overflow > layout.overflow) layout.offenders.push(...openLayout.offenders);
                layout.overflow = Math.max(layout.overflow, openLayout.overflow);
                await page.screenshot({ path: `${SHOT_DIR}/${name}-more-menu.png` });
            }
        }

        results.push({ name, ...layout, errors, drawerOpened, settingsDrawer, moreMenu, cachePanel });
        if (layout.overflow > 1) failures.push(`${name}: 横向溢出 ${layout.overflow}px ${JSON.stringify(layout.offenders)}`);
        if (errors.length) failures.push(`${name}: 控制台报错 ${errors.join(' | ')}`);
        if (drawerOpened === false) failures.push(`${name}: 抽屉导航打不开`);
        if (settingsDrawer === false) failures.push(`${name}: 设置分类抽屉打不开`);
        if (moreMenu === false) failures.push(`${name}: 播放器"更多"菜单打不开`);
        if (cachePanel === false) failures.push(`${name}: 缓存容量二级面板打不开`);
        await context.close();
    }

    await browser.close();
    for (const item of results) {
        const flags = [item.drawerOpened === true ? 'drawer✓' : '', item.settingsDrawer === true ? 'sidebar✓' : '',
            item.moreMenu === true ? 'more✓' : '', item.cachePanel === true ? 'cachePanel✓' : ''].filter(Boolean).join(' ');
        console.log(`${item.name.padEnd(16)} overflow=${String(item.overflow).padStart(3)}px errors=${item.errors.length} ${flags}`);
    }
    if (failures.length) {
        console.error(`\n失败 ${failures.length} 项：`);
        failures.forEach(item => console.error(`- ${item}`));
        process.exit(1);
    }
    console.log(`\n全部通过：${results.length} 个页面在 ${VIEWPORT.width}×${VIEWPORT.height} 下无横向溢出、无控制台报错。截图见 ${SHOT_DIR}/`);
};

run().catch((error) => {
    console.error('冒烟脚本异常：', error);
    process.exit(1);
});
