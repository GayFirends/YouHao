# 参与贡献

感谢你愿意帮助改进油迹。提交代码前，请先搜索现有 Issue，避免重复工作；较大的功能建议先创建 Issue 讨论范围和数据兼容性。

## 本地开发

需要 JDK 21（Android Studio 自带的 JBR 即可）与 Android SDK（`compileSdk 37`）。最低支持 Android 8.0（API 26）。

```bash
echo "sdk.dir=/path/to/Android/Sdk" > local.properties
./gradlew :app:assembleDebug
```

提交前运行：

```bash
./gradlew :domain:test :app:testDebugUnitTest
./gradlew :app:lintDebug
```

## 模块边界

- `domain/` 是纯 Kotlin/JVM 模块，**不得**引入任何 Android API。油耗计算、同步合并、备份格式都应放在这里，这样才能在没有设备或模拟器的情况下被单元测试直接覆盖。
- `app/` 承载 Compose 界面、Room、DataStore、OkHttp 与 WorkManager。文件读写、`BuildConfig` 等 Android 专有内容留在这一侧。

## 改动原则

- 保持本地优先：断网时仍能完成核心记录与查询。
- 修改数据库结构时提供版本化迁移，并覆盖旧数据升级测试。
- 修改备份或同步格式时保持旧备份可导入，并同时更新 `domain/src/test/resources/sync-crypto-reference.json` 相关的互操作说明。
- 同步、删除和冲突处理必须考虑多设备与失败重试。
- 界面文案直接使用中文，不引入图标字体依赖；改动时同时检查深色模式。
- 不在 Issue、日志、测试夹具或截图中提交真实账号、密码、车牌和 WebDAV 地址。

## Pull Request

请在 PR 中说明：

1. 解决的问题和用户可见变化；
2. 影响的模块（`domain`、`app` 或两者）；
3. 数据迁移、同步和备份兼容性；
4. 已执行的测试；
5. 界面改动的截图（如有）。

小而聚焦的 PR 更容易审查。不要将无关格式化、依赖升级和功能改动混在同一个提交中。
