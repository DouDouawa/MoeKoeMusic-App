// The setting selector and router deliberately share the same finite set of destinations.
export const STARTUP_PAGES = Object.freeze([
    { value: 'Index', labelKey: 'shou-ye', route: { name: 'Index' } },
    { value: 'Discover', labelKey: 'fa-xian', route: { name: 'Discover' } },
    { value: 'Library', labelKey: 'yin-le-ku', route: { name: 'Library' } },
    { value: 'LocalMusic', labelKey: 'local-music-title', route: { name: 'LocalMusic' } },
    { value: 'CachedMusic', labelKey: 'cached-music-title', route: { name: 'LocalMusic', query: { tab: 'cached' } } },
    { value: 'Ranking', labelKey: 'startup-ranking', route: { name: 'Ranking' } },
    { value: 'Settings', labelKey: 'she-zhi', route: { name: 'Settings' } }
]);

export const readStoredSettings = (storage = globalThis.localStorage) => {
    try {
        const settings = JSON.parse(storage?.getItem('settings') || '{}');
        return settings && typeof settings === 'object' && !Array.isArray(settings) ? settings : {};
    } catch {
        return {};
    }
};

export const normalizeStartupPage = (value) => STARTUP_PAGES.some(page => page.value === value) ? value : 'Index';
export const startupPageOptions = (t) => STARTUP_PAGES.map(page => ({ value: page.value, displayText: t(page.labelKey) }));

export const getStartupRedirect = (to, from, settings = readStoredSettings()) => {
    if (from?.matched?.length || to.path !== '/' || to.hash || Object.keys(to.query || {}).length) return null;
    const page = STARTUP_PAGES.find(item => item.value === normalizeStartupPage(settings.startupPage));
    if (page.value === 'Index') return null;
    return { ...page.route, ...(page.route.query ? { query: { ...page.route.query } } : {}), replace: true };
};

export const getLyricsRedirect = (to, electron = false) => {
    if (electron || to.path !== '/lyrics') return null;
    return { name: 'Index', query: { ...to.query, openLyrics: '1' }, replace: true };
};
