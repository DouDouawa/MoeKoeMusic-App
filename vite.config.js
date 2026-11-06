// vite.config.js
import { defineConfig } from 'vite';
import vue from '@vitejs/plugin-vue';
import { VitePWA } from 'vite-plugin-pwa';

export default defineConfig({
  plugins: [
    vue(),
    VitePWA({
      registerType: 'autoUpdate',
      devOptions: {
        enabled: true,
        suppressWarnings: true,
        suppressErrors: true,
        logLevel: 'silent',
        type: 'module'
      },
      workbox: {
        // 缓存策略配置
        runtimeCaching: [
          {
            // 匹配所有API请求
            urlPattern: /^\/api\/.*$/i,
            handler: 'NetworkFirst',
            options: {
              cacheName: 'api-cache',
              expiration: {
                maxEntries: 100,
                maxAgeSeconds: 60 * 60 * 24 * 1
              },
              cacheableResponse: {
                statuses: [0, 200]
              }
            }
          },
          {
            // 匹配所有静态资源
            urlPattern: /\.(?:png|jpg|jpeg|svg|gif|webp|js|css|woff2)$/i,
            handler: 'StaleWhileRevalidate',
            options: {
              cacheName: 'assets-cache',
              expiration: {
                maxEntries: 100,
                maxAgeSeconds: 60 * 60 * 24 * 7
              }
            }
          },
          {
            // 匹配导航请求（HTML）
            urlPattern: /\/$/,
            handler: 'NetworkFirst',
            options: {
              cacheName: 'html-cache',
              expiration: {
                maxEntries: 10,
                maxAgeSeconds: 60 * 60 * 24 // 1天
              }
            }
          }
        ]
      },
      manifest: {
        name: 'MoeKoe 萌音',
        short_name: 'MoeKoe',
        description: '一个高颜值的kugou第三方播放器',
        theme_color: '#FF69B4',
        background_color: '#FFF4F8',
        lang: 'zh-CN',
        display: 'standalone',
        start_url: '/',
        display_override: [
          "window-controls-overlay",
          "standalone",
          "minimal-ui",
          "browser"
        ],
        icons: [
          {
            src: 'assets/mobile-icons/pwa-192.png',
            sizes: '192x192',
            type: 'image/png',
            purpose: 'any'
          },
          {
            src: 'assets/mobile-icons/pwa-512.png',
            sizes: '512x512',
            type: 'image/png',
            purpose: 'any'
          },
          {
            src: 'assets/mobile-icons/maskable-192.png',
            sizes: '192x192',
            type: 'image/png',
            purpose: 'maskable'
          },
          {
            src: 'assets/mobile-icons/maskable-512.png',
            sizes: '512x512',
            type: 'image/png',
            purpose: 'maskable'
          }
        ]
      }
    })
  ],
  clearScreen: false, // prevent vite from clearing api log
  define: {
    __VERSION__: JSON.stringify(process.env.npm_package_version)
  },
  base: '',
  server: {
    host: true,
    port: 8080, // 将开发服务器端口设置为8080，确保与其他服务兼容
    // 真机调试时把 API 也代理到同一个源：设备身份走 Set-Cookie，
    // 跨源会被浏览器直接拦死，所以前端必须和 /api 同源。
    // 开关故意不叫 VITE_* —— vite 会接管 VITE_ 前缀的环境变量，
    // 配置求值时读到的可能已被覆盖。正常开发不设这个变量，行为完全不变。
    proxy: process.env.MOEKOE_DEV_API_PROXY === '1' ? {
      '/api': {
        // 默认指向本机后端。要连线上后端时用 MOEKOE_DEV_API_TARGET 指定，
        // 浏览器只看到同源的 /api，不需要后端放行 CORS。
        target: (process.env.MOEKOE_DEV_API_TARGET ||
          `http://127.0.0.1:${process.env.MOEKOE_DEV_API_PORT || 6521}`).replace(/\/+$/, ''),
        changeOrigin: !!process.env.MOEKOE_DEV_API_TARGET,
        rewrite: path => path.replace(/^\/api/, '') || '/'
      }
    } : undefined
  },
  resolve: {
    alias: {
      '@': '/src', // 设置路径别名
    },
  }
});