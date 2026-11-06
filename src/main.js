import { createApp } from 'vue';
import { createPinia } from 'pinia';
import piniaPersistedstate from 'pinia-plugin-persistedstate';
import App from './App.vue';
import router from './router/router';
import { formatMilliseconds, getCover, applyColorTheme, setTheme } from '../src/utils/utils';
import ModalPlugin from './plugins/ModalPlugin';
import MessagePlugin from './plugins/MessagePlugin';
import i18n from './utils/i18n';
import '@/assets/themes/dark.scss';
import { registerSW } from 'virtual:pwa-register'

const app = createApp(App);
const pinia = createPinia();
pinia.use(piniaPersistedstate);
app.config.globalProperties.$getCover = getCover;
app.config.globalProperties.$formatMilliseconds = formatMilliseconds;
app.config.globalProperties.$applyColorTheme = applyColorTheme;
app.config.globalProperties.$setTheme = setTheme;
app.config.errorHandler = (err, vm, info) => {
  console.error(`全局捕获异常: ${info}`, err);
};
app.config.warnHandler = (msg, vm, trace) => {
  console.warn(`全局捕获警告: ${msg}`, trace);
};
window.addEventListener('unhandledrejection', event => {
  console.error('未处理的 Promise 拒绝:', event.reason);
  // window.$modal.alert('系统错误');
});

// Capacitor（Android/iOS）里绝不能使用 Service Worker。
// SW 的预缓存会跨 APK 更新继续提供上一次构建的 bundle，导致新代码永远到不了设备。
const isNativeShell = () => {
  const cap = window.Capacitor;
  if (!cap) return false;
  if (typeof cap.isNativePlatform === 'function') return cap.isNativePlatform();
  return cap.platform === 'android' || cap.platform === 'ios';
};

// 已经装过 SW 的设备，光“不再注册”没用：旧 SW 还在控制页面，必须主动摘掉。
const clearStaleServiceWorker = async () => {
  const FLAG = 'native-sw-cleanup-v1';
  if (localStorage.getItem(FLAG) === 'done') return;
  const wasControlled = !!navigator.serviceWorker?.controller;
  try {
    const registrations = await navigator.serviceWorker.getRegistrations();
    await Promise.all(registrations.map((r) => r.unregister()));
    if (window.caches) {
      const keys = await caches.keys();
      await Promise.all(keys.map((k) => caches.delete(k)));
    }
  } catch (error) {
    console.warn('清理旧 Service Worker 失败:', error);
  }
  localStorage.setItem(FLAG, 'done');
  // 旧 SW 仍在控制当前文档，重载一次才能拿到 APK 内的新资源；flag 已写入，不会循环
  if (wasControlled) location.reload();
};

if (!window.electron && !import.meta.env.DEV) {
  if (isNativeShell()) {
    void clearStaleServiceWorker();
  } else {
    registerSW({
      onNeedRefresh() {
        console.log('有新内容可用，请刷新页面')
      },
      onOfflineReady() {
        console.log('应用已准备好离线工作')
      }
    })
  }
}

app.use(pinia);
app.use(router);
app.use(i18n);
app.use(ModalPlugin);
app.use(MessagePlugin);

app.mount('#app');