# 发布与兼容政策

- 支持最新稳定版本和前一个次版本。
- 本地数据库、JSON v1 备份和 WebDAV v1 文件保持向前迁移兼容，不允许静默清库。
- 正式支持当前 Chromium 系浏览器与 Android API 23–35；Firefox、Safari 和 iOS 不作为发布阻断平台。
- 发布必须通过类型检查、Lint、格式、覆盖率、桌面/移动 Chromium E2E、依赖高危审计、构建体积预算和 Android Release 构建。
- 每次发布在变更日志中记录数据库 schema、同步格式、最低兼容客户端和不可逆迁移。
