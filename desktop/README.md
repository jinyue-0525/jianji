# 简记桌面版 2.0.0

作者 @yue。运行 `npm start` 使用相邻 `../web/` 的最终共享界面；桌面启动为空数据，不读取浏览器示例库。

## 本地数据与同步

Electron userData 保存 `installation.json`、`sync-state.json`、`blobs/`、`backups/`。身份固定为 UUID，记录使用向量时钟、SHA-256 图片编号及持久删除墓碑。每次合并前保存本机与传入清单；备份引用的图片不删除。同步偏好与配对凭据不进入笔记清单。

TLS 私钥和配对凭据通过 Electron safeStorage（macOS Keychain / Windows DPAPI）保护。安全存储不可用时禁止配对，不降级明文凭据。服务仅接受私有/链路本地 IPv4，所有同步接口须 Bearer 凭据；配对 token 五分钟过期、仅一次请求且需电脑批准。

`window.jianjiDesktop` 严格采用 `../sync/PROTOCOL.md` 的桥接接口。renderer 必须订阅 onPrepareSync，完成当前草稿保存、锁定编辑后返回 true。main 在收到确认后序列化合并；成功、拒绝、超时均触发 onSyncResult。界面应使用结果 state 重新加载已保存草稿并解锁，失败时保留当前稿件。桌面 Sync 只增加手机轮询 generation，手机需要保持前台。

## 构建分发文件

- `npm run cache:electron`：下载官方 Electron 的 macOS arm64、macOS x64、Windows x64 发行包至用户缓存，使用官方 SHA256 清单校验。
- `npm run stage`：复制最新 `../web/`、后端与 production 依赖到 `.build/app`。
- `npm run package`：在 macOS 使用系统 ditto/codesign 组装三架构分发包，输出至 `~/Documents/outputs/`。
- 单独目标：`npm run package -- darwin-arm64`、`darwin-x64`、`win32-x64`。

打包不依赖 Wine，不执行自动化测试。macOS 为本机 ad-hoc 签名，尚未 Apple 公证；Windows 可移植包未进行 Authenticode 签名。跨设备实际扫码及双向同步仍需真实设备验证。
