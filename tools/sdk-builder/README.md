# Lawnchair package-specific SDK builder

本地网页按 Android 包名生成包专属 Launcher SDK Bridge。产物包含：

- 可供业务接入的 R8 AAR；
- pre-R8 AAR 与 Kotlin 源文件；
- R8 `mapping.txt`；
- 源类、pre-R8、R8 类与函数对比；
- 业务 ABI 随机化校验报告；
- 可解压使用的本地 Maven 仓库。

随机化范围不是只有 facade 函数。凡是业务源码会继承、实现、构造，或出现在公开函数
参数、返回值、回调中的 Client 类型，都会按应用包名生成 R8 专属类名和函数名。固定的
`LawnchairSdkEntry`、`MinusOneExtension`、`DesktopItemsExtension` 等接口只由 AAR 内部
Adapter 使用，不进入业务接入签名。构建发现任一业务类未改名，或业务函数签名泄露固定
Lawnchair SDK 类型时会直接失败。

网页始终编译当前 Lawnchair checkout。SDK 版本由服务端从
`launcher-core/build.gradle` 的当前发布版本自动读取，业务用户无需输入；该版本用于生成
Maven 坐标及其 `launcher-core` 依赖版本，不会自动切换 Git tag。

启动：

```bash
cd /Users/rickyyao/github/lawnchair
python3 tools/sdk-builder/server.py
```

浏览器打开 <http://127.0.0.1:8765>。

也可以直接验证底层构建：

```bash
./gradlew \
  :launcher-sdk-client:clean \
  :launcher-sdk-client:assemblePreR8 \
  :launcher-sdk-client:assembleRelease \
  -PlauncherClientPackage=com.example.game \
  --no-configuration-cache
```

构建任务串行执行，避免两个包名同时覆盖 Gradle 模块的生成目录。网页只绑定
`127.0.0.1`，不要直接暴露到公网；若要做成团队服务，应在外层增加鉴权、任务队列和
独立构建容器。
