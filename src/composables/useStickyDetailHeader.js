import { computed, nextTick, onBeforeUnmount, onMounted, ref } from 'vue';

const clamp = (value, min = 0, max = 1) => Math.min(max, Math.max(min, value));
const MOBILE_QUERY = '(max-width: 768px)';

// 展开态尺寸随视口切换：320px 屏幕上 200px 封面会把标题挤成一条窄缝，
// 手机改为小封面 + 小标题；折叠态尺寸保持接近桌面，避免黏性头忽高忽低。
const DESKTOP_METRICS = {
    expandedHeight: 220,
    collapsedHeight: 56,
    coverSize: 200,
    collapsedCoverSize: 36,
    titleSize: 36,
    collapsedTitleSize: 20,
    listTitleSize: 24,
    collapsedListTitleSize: 20
};
const MOBILE_METRICS = {
    expandedHeight: 156,
    collapsedHeight: 52,
    coverSize: 104,
    collapsedCoverSize: 32,
    titleSize: 21,
    collapsedTitleSize: 17,
    listTitleSize: 18,
    collapsedListTitleSize: 16
};
const TRACK_HEADER_OVERLAP = 3;
const SPACER_COLLAPSE_OFFSET = 20;
const COLLAPSED_THRESHOLD = 0.86;

export const useStickyDetailHeader = () => {
    const scrollTop = ref(0);
    const listHeaderHeight = ref(0);
    const tabsHeight = ref(0);
    const isMobileViewport = ref(false);
    let scrollContainer = null;
    let listHeaderElement = null;
    let listHeaderObserver = null;
    let tabsElement = null;
    let tabsObserver = null;
    let mobileQuery = null;

    const metrics = computed(() => (isMobileViewport.value ? MOBILE_METRICS : DESKTOP_METRICS));
    const collapseDistance = computed(() => Math.max(1, metrics.value.expandedHeight - metrics.value.collapsedHeight));
    const progress = computed(() => clamp(scrollTop.value / collapseDistance.value));
    const currentCoverSize = computed(() => metrics.value.coverSize - (metrics.value.coverSize - metrics.value.collapsedCoverSize) * progress.value);
    const currentTitleSize = computed(() => metrics.value.titleSize - (metrics.value.titleSize - metrics.value.collapsedTitleSize) * progress.value);
    const currentListTitleSize = computed(() => metrics.value.listTitleSize - (metrics.value.listTitleSize - metrics.value.collapsedListTitleSize) * progress.value);
    const expandedRatio = computed(() => 1 - progress.value);
    const detailsOpacity = computed(() => clamp(1 - progress.value * 1.2));

    const collapsedActionOpacity = computed(() => {
        if (progress.value <= COLLAPSED_THRESHOLD) return 0;
        return clamp((progress.value - COLLAPSED_THRESHOLD) / (1 - COLLAPSED_THRESHOLD));
    });

    const measureListHeader = () => {
        const listHeader = listHeaderElement || document.querySelector('.detail-page .track-list-header');
        listHeaderHeight.value = listHeader?.getBoundingClientRect().height ?? 0;
    };

    const measureTabs = () => {
        const tabs = tabsElement || document.querySelector('.detail-page .local-tabs');
        tabsHeight.value = tabs?.getBoundingClientRect().height ?? 0;
    };

    const syncScroll = () => {
        scrollTop.value = scrollContainer?.scrollTop ?? 0;
    };

    const observeListHeader = () => {
        const nextElement = document.querySelector('.detail-page .track-list-header');
        if (listHeaderElement === nextElement) return;
        listHeaderObserver?.disconnect();
        listHeaderElement = nextElement;
        if (!listHeaderElement) return;
        listHeaderObserver = new ResizeObserver(measureListHeader);
        listHeaderObserver.observe(listHeaderElement);
        measureListHeader();
    };

    const observeTabs = () => {
        const nextElement = document.querySelector('.detail-page .local-tabs');
        if (tabsElement === nextElement) return;
        tabsObserver?.disconnect();
        tabsElement = nextElement;
        if (!tabsElement) return;
        tabsObserver = new ResizeObserver(measureTabs);
        tabsObserver.observe(tabsElement);
        measureTabs();
    };

    const bindScroll = () => {
        const nextContainer = document.querySelector('.app-main-scroll');
        if (scrollContainer === nextContainer) return;
        scrollContainer?.removeEventListener('scroll', syncScroll);
        scrollContainer = nextContainer;
        scrollContainer?.addEventListener('scroll', syncScroll, { passive: true });
        syncScroll();
    };

    const refresh = () => {
        observeListHeader();
        observeTabs();
        bindScroll();
    };

    const syncViewport = () => {
        isMobileViewport.value = mobileQuery?.matches ?? false;
        // 断点切换会改变头部高度，重新量一次列表头，避免黏性偏移错位
        nextTick(() => {
            measureListHeader();
            measureTabs();
        });
    };

    onMounted(() => {
        mobileQuery = window.matchMedia(MOBILE_QUERY);
        syncViewport();
        mobileQuery.addEventListener('change', syncViewport);
        nextTick(refresh);
        requestAnimationFrame(refresh);
        window.addEventListener('resize', refresh);
        window.addEventListener('settings-change', refresh);
        window.addEventListener('sidebar-collapse-change', refresh);
    });

    onBeforeUnmount(() => {
        mobileQuery?.removeEventListener('change', syncViewport);
        scrollContainer?.removeEventListener('scroll', syncScroll);
        listHeaderObserver?.disconnect();
        tabsObserver?.disconnect();
        window.removeEventListener('resize', refresh);
        window.removeEventListener('settings-change', refresh);
        window.removeEventListener('sidebar-collapse-change', refresh);
    });

    // 顶栏是 fixed 的覆盖层，粘性头的 top 必须让开它，否则会钻到顶栏下面（或盖住顶栏）。
    // 基线由 --app-sticky-base 提供：桌面端等于顶栏高度，移动端为 0（滚动容器的
    // padding-top 已经让出同样的距离，这里再加一次就会叠出一整条顶栏的高度）。
    const STICKY_BASE = 'var(--app-sticky-base, 0px)';

    const headerStyle = computed(() => ({
        top: STICKY_BASE,
        height: `${metrics.value.collapsedHeight}px`
    }));

    const spacerStyle = computed(() => ({
        height: `${metrics.value.expandedHeight - metrics.value.collapsedHeight - progress.value * SPACER_COLLAPSE_OFFSET}px`
    }));

    const coverStyle = computed(() => ({
        width: `${currentCoverSize.value}px`,
        height: `${currentCoverSize.value}px`
    }));

    const infoStyle = computed(() => ({
        height: `${currentCoverSize.value}px`,
        overflow: progress.value > 0 ? 'hidden' : 'visible'
    }));

    const titleStyle = computed(() => ({
        fontSize: `${currentTitleSize.value}px`,
        transform: `translateY(${progress.value * 6}px)`
    }));

    const detailsStyle = computed(() => ({
        opacity: detailsOpacity.value,
        pointerEvents: detailsOpacity.value > 0.2 ? 'auto' : 'none'
    }));

    const listTitleStyle = computed(() => ({
        fontSize: `${currentListTitleSize.value}px`,
        margin: `${expandedRatio.value * 0.83}em 0`
    }));

    const collapsedActionsStyle = computed(() => ({
        opacity: collapsedActionOpacity.value,
        transform: `translateY(-50%) translateX(${(1 - collapsedActionOpacity.value) * 12}px)`,
        pointerEvents: collapsedActionOpacity.value > 0.6 ? 'auto' : 'none'
    }));

    const listHeaderStyle = computed(() => ({
        top: `calc(${STICKY_BASE} + ${metrics.value.collapsedHeight + tabsHeight.value}px)`
    }));

    const trackHeaderStyle = computed(() => ({
        top: `calc(${STICKY_BASE} + ${metrics.value.collapsedHeight + tabsHeight.value + listHeaderHeight.value - TRACK_HEADER_OVERLAP}px)`
    }));

    // 标签页贴在折叠状态的详情头正下方，再往上会钻到详情头底下被盖住
    const tabsStyle = computed(() => ({
        top: `calc(${STICKY_BASE} + ${metrics.value.collapsedHeight}px)`
    }));

    return {
        headerStyle,
        spacerStyle,
        coverStyle,
        infoStyle,
        titleStyle,
        detailsStyle,
        listTitleStyle,
        collapsedActionsStyle,
        listHeaderStyle,
        trackHeaderStyle,
        tabsStyle
    };
};
