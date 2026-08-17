# 油迹 Fuel Track

面向 Web 与 Android 的车辆油耗记录应用。项目使用 Vue 3、TypeScript、Vite 和 Capacitor；Web 使用 SQLite WASM，Android 使用原生 SQLite，共用一套界面与业务代码，并通过 WebDAV 做记录级双向同步。

## 功能

- 多车辆管理，分别记录初始里程与燃油标号
- 加油日期、里程、升数、金额、加油站、满箱状态与备注
- 满箱区间油耗、本月费用、累计费用、记录里程与趋势统计
- WebDAV 连接测试、下载合并和上传备份
- UUID + 更新时间 + 软删除的多设备冲突合并
- 桌面侧栏和 Android/移动 Web 底部导航响应式布局
- 本地 SQLite WASM 数据库，无网络也可录入和查看
- Web 将 SQLite 二进制文件持久化到 IndexedDB；Android 使用系统原生 SQLite 数据库
- 部分加油累计、满箱区间和按里程加权的平均油耗算法
- WebDAV ETag 条件写入与冲突自动重试
- JSON 完整备份、JSON 合并恢复和 CSV 报表导出

## Web 开发

```bash
npm install
npm run dev
```

生产构建：

```bash
npm run build
npm run preview
npm test
```

浏览器直接访问 WebDAV 时，服务端必须允许当前站点的 CORS，并允许 `GET`、`PUT`、`PROPFIND` 与 `Authorization` 请求头。Android WebView 通常不受浏览器跨域策略限制，但仍需要有效的 HTTPS 证书。

## Android

Android 原生工程位于 `android/`。修改前端后同步资源：

```bash
npm run android:sync
npm run android:open
```

也可以在命令行构建 Debug APK：

```powershell
.\android\gradlew.bat -p android assembleDebug
```

APK 生成在 `android/app/build/outputs/apk/debug/app-debug.apk`。

仓库包含 `.github/workflows/android-debug.yml`。推送到 `main` 或手动触发工作流后，会依次运行测试、同步 Capacitor、构建 Debug APK 并上传构建产物。

Android 构建使用 Java 21（Capacitor 7 和原生 SQLite 插件的 Gradle 配置要求 `sourceCompatibility = 21`）。

## 同步格式

WebDAV 中默认保存 `fuel-track.json`。同步先下载云端快照，再按每条车辆和加油记录的 `updatedAt` 合并，最后上传合并结果。删除使用软删除标记同步到其他设备，避免旧设备把已删除记录重新上传。

Web 端使用 SQLite WASM，并将数据库二进制文件保存在 IndexedDB 中。旧版本保存在 `localStorage` 的数据库会在首次启动时自动迁移，确认 IndexedDB 写入成功后才移除旧副本。Android 端通过 `@capacitor-community/sqlite` 使用系统原生 SQLite，数据库名为 `fuel-trackSQLite.db`。两种实现共用相同表结构、约束、软删除和 WebDAV 数据格式。

数据库结构使用版本化迁移管理。Android 初始化时还会启用外键并执行 `PRAGMA quick_check`，写操作和 WebDAV 合并均在原生 SQLite 事务中完成。

WebDAV 用户名和地址保存在当前设备，密码只保留在当前应用会话中，不会长期写入本地存储，也不会写入同步文件。同步文件本身未加密，应使用可信的 HTTPS WebDAV 服务并妥善保护账号。
