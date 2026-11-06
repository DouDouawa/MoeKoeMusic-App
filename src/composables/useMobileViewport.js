import { ref, onMounted, onUnmounted } from 'vue';

export const MOBILE_QUERY = '(max-width: 768px)';

export function useMobileViewport() {
    const media = typeof window !== 'undefined' ? window.matchMedia(MOBILE_QUERY) : null;
    const isMobile = ref(media?.matches ?? false);
    const update = () => { isMobile.value = media?.matches ?? false; };
    onMounted(() => {
        update();
        media?.addEventListener('change', update);
    });
    onUnmounted(() => media?.removeEventListener('change', update));
    return isMobile;
}
