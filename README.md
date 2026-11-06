<br />
<p align="center">
    <img src="images/hero.png" alt="hero" width="250" height="250">
  <h2 align="center" style="font-weight: 600">萌音 MoeKoe Music</h2>
  <p align="center">
    MoeKoeMusic 的 Android 端适配喵～
  </p>
  <p align="center">
    <a href="https://github.com/DouDouawa/MoeKoeMusic-"><strong>🌎 本仓库</strong></a>&nbsp;&nbsp;|&nbsp;&nbsp;
    <a href="https://github.com/MoeKoeMusic/MoeKoeMusic/"><strong>⬆️ 原项目</strong></a>
  </p>
</p>

## 📌 这是什么呀

这里是 [MoeKoeMusic](https://github.com/MoeKoeMusic/MoeKoeMusic/) 的一个个人分支喵～本喵只做一件事：**给 Android 端做前端适配**，别的都不碰的说。

原项目是个用 Vue 3 写的酷狗第三方客户端，网页端、Electron 桌面端、Docker 部署样样都有。本喵就在它的前端上面套了层 Capacitor 壳，顺手把 Android 端的播放和缓存能力补齐，再把手机上那些别扭的布局和交互一个一个捋顺喵。

范围这事儿得先讲清楚，免得误会喵：

- **只改前端**。这个仓库里一行服务端代码都没有，原项目的后端本喵既不修改、也不替代。
- **后端还是原作者的**。API 怎么请求、接口怎么约定，全按原项目来，本喵一个字都没动喵。
- 桌面端（Electron）和网页端的能力保持原样，本喵的爪子只伸到 Android 相关的地方。
- 上游那些 Docker 部署文件被本喵叼走了，因为跟 Android 端实在没关系。想用 Docker 部署的话，请回[原项目](https://github.com/MoeKoeMusic/MoeKoeMusic/)那边喵。

后端地址要填哪里？看下面的[后端说明](#-后端说明)就好啦。

## ✨ 本喵都改了什么

### Android 客户端

原项目靠 Electron 做桌面端，本喵另外搭了个 Capacitor 工程，把同一套前端塞进 Android 应用里，还补上了网页端做不到的那些事喵：

- **原生播放服务**：`PlaybackService` + `QueuePlayer` 把播放队列接过去管，切后台、息屏都不带断的喵
- **原生音频缓存**：`AudioCache` / `CacheDb` 管离线缓存和断点续传，再也不用受 WebView 那点存储和内存的气
- **音源解析下沉**：`SourceResolver` 在 Java 层就把多音源解析和切换办妥了
- **PCM 录制**：`PcmRecorder` 负责听歌识曲要用的音频采集
- 最后由 `MoePlaybackPlugin` 把这些本事递给 JS 层用

### 移动端适配

- 加了抽屉导航、手势返回、下拉刷新、页签滑动这些顺手的交互（`useNavigationDrawer`、`drawerGesture`、`usePullToRefresh`、`useTabSwipe`）
- 移动端断点统一到 `768px`。原先 `767px` / `768px` 两套断点并存搞得布局错位，被本喵收拾掉了喵
- 「只有 hover 才看得见」的入口修好啦：收藏区关闭按钮、歌曲播放图标在手机上根本点不着，这哪行喵
- 顶部导航右上角那个三点图标换成了用户头像入口，没登录就显示默认头像
- 音乐库、本地音乐、设置这些页面的栅格和操作区，按手机宽度重排了一遍
- 用户卡片的操作按钮原先被 `content-box` 撑成 50px 的方块，也改成正常胶囊啦

### 播放器

- 新增 `NativeAudioController` 和 `PlaybackIntent`，把原生和网页两条播放链路拢到一处喵
- 歌名太长不滚动、太短又乱滚动的问题都修了
- 设置里切完音质，音质徽章不会立刻变、非得切首歌才刷新——这个也一并修好了喵

## 🛠 技术栈

本喵用的家伙什儿喵：

| 层次 | 技术 |
|---|---|
| 前端 | Vue 3、Vue Router、Pinia、Vite |
| 桌面端 | Electron |
| Android | Capacitor 8、Java、Gradle |
| 样式 | SCSS |
| 国际化 | vue-i18n（简中 / 繁中 / 英 / 日 / 韩 / 俄） |

## 🚀 开发

要 Node.js >= 20 才行的喵。

```sh
pnpm install --frozen-lockfile
```

想同时拉起本地 API 和 Vite 的话：

```sh
pnpm dev
```

只想跑前端、后面接现成的后端：

```sh
pnpm serve
```

构建前端喵：

```sh
pnpm build          # 网页端
pnpm build:client   # Electron 客户端
```

跑一下单元测试：

```sh
pnpm test
```

## 📱 构建 Android 客户端

先备好 JDK 17 或 21 喵（AGP 8.x 是不认 JDK 22+ 的），再加上 Android SDK。

```sh
bash scripts/build-android.sh
```

这个脚本会做三件事，少做一步就会得到一个「能装但内容是旧的」的包，可别偷懒喵：

1. `vite build` 生成 `dist`
2. `cap sync` 把 `dist` 同步进 `android/app/src/main/assets/public`
3. `gradlew assembleDebug` 编译 APK

产物在这里：`android/app/build/outputs/apk/debug/app-debug.apk`。

要装到设备上的话（华为设备会自己帮你把那些弹窗点掉喵）：

```sh
PIN=你的锁屏密码 bash scripts/install-apk.sh
```

## 🔌 后端说明

这个仓库是纯前端喵，**不含任何服务端代码，也不改后端**。后端沿用原作者的实现，接口约定跟本分支没关系的说：

- 后端来源：[MakcRe/KuGouMusicApi](https://github.com/MakcRe/KuGouMusicApi)，就是原项目依赖的那同一套 API
- 默认地址沿用上游写法 `http://127.0.0.1:6521`，见 [`src/utils/apiBaseUrl.js`](src/utils/apiBaseUrl.js)
- 构建时可以用环境变量 `VITE_APP_API_URL` 覆盖掉
- 应用里「设置 → API 地址」也能随时改成你自己的服务地址喵

本地开发的时候，`pnpm dev` 会用 `kugoumusicapi` 这个 npm 包在 `6521` 端口拉起一个 API 服务（就是 `package.json` 里那个 `api` 脚本）。它只是原项目发布的一个依赖，源码不在本仓库，本喵也没对它动过爪子。

一句话：**需要后端就按原项目的方式部署喵**，本喵只负责 Android 这一层。

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

这个项目绝大部分的工作都来自原项目喵，谢谢原作者 [iAJue](https://github.com/iAJue) 和所有贡献者，本喵只是站在他们肩膀上蹭来蹭去而已：

- **原仓库**：https://github.com/MoeKoeMusic/MoeKoeMusic/
- **原项目主页**：https://Music.MoeKoe.cn
- **API 来源**：[MakcRe/KuGouMusicApi](https://github.com/MakcRe/KuGouMusicApi)

原项目的设计灵感来自 [Apple Music](https://music.apple.com)、[YouTube Music](https://music.youtube.com)、[YesPlayMusic](https://github.com/qier222/YesPlayMusic)。

如果你只是想用这个播放器，本喵建议优先用原项目的正式发布版本喵；这个分支主要是给 Android 端的定制需求准备的。

## ⚠️ 免责声明

> 以下为法律声明，与本项目其余内容无关，请严肃阅读喵。

1. 本程序是酷狗第三方客户端，并非酷狗官方。需要更完善的功能请使用官方客户端。
2. 本项目仅供学习研究使用，请尊重版权，禁止用于商业及非法用途。
3. 使用过程中可能产生版权数据，本项目不拥有其所有权。为避免侵权，请在 24 小时内清除使用过程中产生的版权数据。
4. 音乐平台不易，请支持正版。
5. 因使用本项目产生的任何直接或间接损害，由使用者自行承担。
6. 若官方音乐平台认为本项目不妥，可联系更改或移除。

## 📜 开源许可

基于 [GNU General Public License v2.0 (GPL-2.0)](LICENSE) 开源，与原项目保持一致喵。
