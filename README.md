# 简记 JianJi

一款本地保存、无需账号的笔记应用，包含 Android 客户端、Electron 桌面客户端和共享网页界面。当前版本为 **2.0.0**，作者 **@yue**。

## 功能

- 笔记编辑、搜索、图片插入、收藏、置顶与回收站。
- 按文件夹和笔记本整理内容。
- Android 与电脑通过同一局域网配对，手动进行双向同步，无需云服务。
- 扫码配对、电脑端批准连接、TLS 加密传输与证书指纹校验。
- 同步冲突保留副本，合并前保存本地清单备份。

## 项目结构

```text
app/              Android 原生客户端（Java）及资源
web/              桌面共享界面与浏览器交互预览
desktop/          Electron 客户端、同步服务与分发脚本
sync/PROTOCOL.md  两端共用的同步协议
docs/             使用说明
build-apk.sh      macOS 独立 Android 构建脚本
```

## 运行桌面版

需要 Node.js **22.12.0 或更高版本**及 npm。

```bash
git clone https://github.com/jinyue-0525/jianji.git
cd jianji/desktop
npm ci
npm start
```

桌面端直接使用相邻 `web/` 中的共享界面，首次运行为空笔记库。数据保存在 Electron 的当前用户数据目录，可从应用内打开；不会读取浏览器预览中的示例笔记。

现有分发脚本在 macOS 上运行，支持 macOS arm64、macOS x64 和 Windows x64：

```bash
cd desktop
npm ci
npm run package
```

产物默认输出至 `~/Documents/outputs/`。单个目标的命令与签名限制见 [桌面端说明](desktop/README.md)。

## 构建 Android 版

用 Android Studio 打开仓库根目录，按项目的 Android Gradle Plugin 配置完成同步。项目使用 Android SDK 35，最低支持 Android 8.0（API 26）。仓库当前不包含 Gradle Wrapper。

macOS 也可以使用已有的独立脚本。准备 JDK 17、Android SDK Platform 35 和 Build Tools 36.0.0，并设置本机路径：

```bash
export JAVA_HOME="$(/usr/libexec/java_home -v 17)"
export ANDROID_SDK_ROOT="$HOME/Library/Android/sdk"
./build-apk.sh
```

该脚本下载并校验 ZXing 依赖，使用本机开发密钥签名。工作目录和签名目录位于仓库上两级的 `work/`，APK 输出至同一层级的 `outputs/简记-2.0.0.apk`；请保留自己的签名密钥，不要提交到仓库。开发签名仅供本地使用，正式分发应使用自己的发布签名。

## 网页预览

`web/` 使用原生 HTML、CSS 和 JavaScript 模块，可通过任意静态 HTTP 服务打开。例如在已安装 Python 3 时：

```bash
python3 -m http.server 8080 --directory web --bind 127.0.0.1
```

访问 `http://127.0.0.1:8080`。浏览器模式用于交互预览，示例笔记和数据保存在浏览器中，与 Android 和桌面端数据独立；局域网同步需要原生客户端。

## 手机与电脑同步

两端连接同一 Wi-Fi，在电脑“设备同步”中生成二维码，手机进入“电脑同步”扫码，并在电脑批准。同步时保持两端打开，手机处于前台。

详细步骤见 [电脑与手机同步](docs/电脑与手机同步.md)，实现约定见 [同步协议](sync/PROTOCOL.md)。当前不包含云同步、蓝牙同步或后台实时同步；跨设备实际扫码和双向同步仍需真实设备验证。

## 开源许可

本项目原创代码采用 [MIT License](LICENSE)。第三方依赖保留各自许可证：Android 使用的 ZXing Core 为 Apache-2.0，其许可文本保留在 `app/src/main/assets/licenses/zxing-core.txt`；Electron 及 npm 依赖的许可随各自发行包提供。二次分发时请一并保留相关版权与许可声明。

仓库仅提供源码、必要资源与文档，不包含依赖目录、构建产物、签名密钥或用户笔记。
