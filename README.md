<br />
<p align="center">
    <img src="images/hero.png" alt="hero" width="250" height="250">
  <h2 align="center" style="font-weight: 600">萌音 MoeKoe Music</h2>
  <p align="center">
    MoeKoeMusic 的 Android 端适配
  </p>
  <p align="center">
    <a href="https://github.com/DouDouawa/MoeKoeMusic-"><strong>🌎 本仓库</strong></a>&nbsp;&nbsp;|&nbsp;&nbsp;
    <a href="https://github.com/MoeKoeMusic/MoeKoeMusic/"><strong>⬆️ 原项目</strong></a>
  </p>
</p>

## 📌 这是什么

这是 [MoeKoeMusic](https://github.com/MoeKoeMusic/MoeKoeMusic/) 的一个个人分支，**只做 Android 端的前端适配**。

原项目是一个用 Vue 3 写的酷狗第三方客户端，覆盖网页端、Electron 桌面端和 Docker 部署。本分支在它的前端基础上，把界面装进 Capacitor 壳、补齐 Android 端的播放与缓存能力，并修掉移动端布局与交互问题。

需要强调的是范围：

- **只改前端**。本仓库没有任何服务端代码，也不修改、不替代原项目的后端。
- **后端仍然是原作者的**。API 行为、接口约定完全沿用原项目，本分支不做任何改动。
- 桌面端（Electron）、网页端的能力保持原样，本分支的改动集中在 Android 相关部分。
- 上游的 Docker 部署文件已在本分支移除，因为与 Android 端无关。若需要 Docker 部署，请使用[原项目](https://github.com/MoeKoeMusic/MoeKoeMusic/)。

具体怎么填后端地址，见[后端说明](#-后端说明)。

## ✨ 相对原项目做了什么

### Android 客户端

原项目基于 Electron 做桌面端，本分支新增了 Capacitor 工程，把同一套前端装进 Android 应用，并补上了网页端做不到的部分：

- **原生播放服务**：`PlaybackService` + `QueuePlayer` 接管播放队列，切到后台或息屏后不断播
- **原生音频缓存**：`AudioCache` / `CacheDb` 支持离线缓存与断点续传，不再受 WebView 的存储与内存限制
- **音源解析下沉**：`SourceResolver` 在 Java 层完成多音源解析与切换
- **PCM 录制**：`PcmRecorder` 支持听歌识曲所需的音频采集
- 通过 `MoePlaybackPlugin` 把上述能力暴露给 JS 层

### 移动端适配

- 新增抽屉导航、手势返回、下拉刷新、页签滑动等交互（`useNavigationDrawer`、`drawerGesture`、`usePullToRefresh`、`useTabSwipe`）
- 统一移动端断点到 `768px`，修掉原先 `767px` / `768px` 两套断点并存导致的布局错位
- 修正触屏下「只有 hover 才可见」的入口：收藏区关闭按钮、歌曲播放图标在手机上根本点不到
- 顶部导航右上角的三点图标改为用户头像入口，未登录时显示默认头像
- 音乐库、本地音乐、设置等页面的栅格与操作区按手机宽度重排
- 修复用户卡片操作按钮被 `content-box` 撑成 50px 方块的问题

### 播放器

- 新增 `NativeAudioController` 与 `PlaybackIntent`，统一原生与网页两条播放链路
- 修复歌名过长时不滚动、以及短歌名不应滚动的问题
- 修复在设置中切换音质后，音质徽章不即时刷新（需切歌才更新）的问题

## 🛠 技术栈

| 层次 | 技术 |
|---|---|
| 前端 | Vue 3、Vue Router、Pinia、Vite |
| 桌面端 | Electron |
| Android | Capacitor 8、Java、Gradle |
| 样式 | SCSS |
| 国际化 | vue-i18n（简中 / 繁中 / 英 / 日 / 韩 / 俄） |

## 🚀 开发

需要 Node.js >= 20。

```sh
pnpm install --frozen-lockfile
```

启动开发服务器（会同时拉起本地 API 与 Vite）：

```sh
pnpm dev
```

只跑前端、连已有后端：

```sh
pnpm serve
```

构建前端：

```sh
pnpm build          # 网页端
pnpm build:client   # Electron 客户端
```

运行单元测试：

```sh
pnpm test
```

## 📱 构建 Android 客户端

前置条件：JDK 17 或 21（AGP 8.x 不接受 JDK 22+）、Android SDK。

```sh
bash scripts/build-android.sh
```

这个脚本做三件事，缺一步都会得到「能装但内容过期」的包：

1. `vite build` 生成 `dist`
2. `cap sync` 把 `dist` 同步进 `android/app/src/main/assets/public`
3. `gradlew assembleDebug` 编译 APK

产物在 `android/app/build/outputs/apk/debug/app-debug.apk`。

安装到设备（华为设备会自动处理安装弹窗）：

```sh
PIN=你的锁屏密码 bash scripts/install-apk.sh
```

## 🔌 后端说明

本仓库是纯前端，**不含任何服务端代码，也不修改后端**。后端沿用原作者的实现，接口约定与本分支无关：

- 后端来源：[MakcRe/KuGouMusicApi](https://github.com/MakcRe/KuGouMusicApi)，即原项目所依赖的同一套 API
- 默认地址沿用上游写法 `http://127.0.0.1:6521`，见 [`src/utils/apiBaseUrl.js`](src/utils/apiBaseUrl.js)
- 构建时可用环境变量 `VITE_APP_API_URL` 覆盖
- 应用内「设置 → API 地址」也可以随时改成你自己的服务地址

本地开发时，`pnpm dev` 会用 `kugoumusicapi` 这个 npm 包在 `6521` 端口拉起一个 API 服务（见 `package.json` 的 `api` 脚本）。它只是原项目发布的一个依赖，源码不在本仓库，本分支也没有对它做任何改动。

换句话说：**如果你需要后端，请使用原项目的方式部署**，本分支只负责 Android 端这一层。

## 📂 目录结构

```
src/
├── components/          组件，含 player/ 播放核心与 discover/、search/ 等业务分组
├── composables/         移动端交互（抽屉、手势、下拉刷新、页签滑动）
├── config/              设置项、引导、启动页配置
├── language/            多语言文案
├── layouts/             页面布局
├── router/              路由
├── stores/              Pinia 状态
├── utils/               API 地址、音频缓存、原生播放桥接等
└── views/               页面
android/                 Capacitor Android 工程（Java 原生层）
public/                  静态资源
scripts/                 Android 构建与安装脚本
tests/                   单元测试
```

## 🙏 致谢与原项目

本项目的绝大部分工作来自原项目，感谢原作者 [iAJue](https://github.com/iAJue) 及所有贡献者：

- **原仓库**：https://github.com/MoeKoeMusic/MoeKoeMusic/
- **原项目主页**：https://Music.MoeKoe.cn
- **API 来源**：[MakcRe/KuGouMusicApi](https://github.com/MakcRe/KuGouMusicApi)

原项目的设计灵感来自 [Apple Music](https://music.apple.com)、[YouTube Music](https://music.youtube.com)、[YesPlayMusic](https://github.com/qier222/YesPlayMusic)。

如果你只是想用这个播放器，建议优先使用原项目的正式发布版本；本分支主要面向 Android 端的定制需求。

## ⚠️ 免责声明

1. 本程序是酷狗第三方客户端，并非酷狗官方。需要更完善的功能请使用官方客户端。
2. 本项目仅供学习研究使用，请尊重版权，禁止用于商业及非法用途。
3. 使用过程中可能产生版权数据，本项目不拥有其所有权。为避免侵权，请在 24 小时内清除使用过程中产生的版权数据。
4. 音乐平台不易，请支持正版。
5. 因使用本项目产生的任何直接或间接损害，由使用者自行承担。
6. 若官方音乐平台认为本项目不妥，可联系更改或移除。

## 📜 开源许可

基于 [GNU General Public License v2.0 (GPL-2.0)](LICENSE) 开源，与原项目保持一致。
