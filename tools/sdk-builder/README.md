# Lawnchair package-specific SDK builder

本地网页按 Android 包名生成包专属 Launcher SDK Bridge。产物包含：

- 可供业务接入的 R8 AAR；
- pre-R8 AAR 与 Kotlin 源文件；
- R8 `mapping.txt`；
- 源类、pre-R8、R8 类与函数对比；
- 业务 ABI 随机化校验报告；
- 发布到 GitHub Packages 的 Maven 依赖坐标。

随机化范围不是只有 facade 函数。凡是业务源码会继承、实现、构造，或出现在公开函数
参数、返回值、回调中的 Client 类型，都会按应用包名生成 R8 专属类名和函数名。固定的
`LawnchairSdkEntry`、`MinusOneExtension`、`DesktopItemsExtension` 等接口只由 AAR 内部
Adapter 使用，不进入业务接入签名。构建发现任一业务类未改名，或业务函数签名泄露固定
Lawnchair SDK 类型时会直接失败。

网页始终编译当前 Lawnchair checkout。SDK 基线版本由服务端从
`launcher-core/build.gradle` 自动读取，当前从 `0.1` 开始。首次构建时网页会把 Launcher
自有模块发布到同一个 GitHub Packages 仓库；后续发现该基线已存在时直接复用。

发布使用 GitHub Packages Maven Registry。把服务端凭证放在已被 Git 忽略的
`local.properties` 中；网页只读取“服务端凭证已配置”的状态，不接收用户名或 Token，
服务端也不会把真实凭证返回给浏览器：

```properties
github.packages.username=<github-user>
github.packages.token=<classic-pat>
```

发布目标的 owner/repository 可选通过服务端环境变量覆盖：

```bash
export GITHUB_PACKAGES_OWNER=vihuela
export GITHUB_PACKAGES_REPOSITORY=lawnchair-sdk
```

包名输入框带有最近使用下拉，并持久化到浏览器 `localStorage`。用户名和 Token 只存在于
服务端环境变量或 `local.properties`，不进入 HTML、接口响应、构建日志与下载产物。

发布 Token 至少需要 classic PAT 的 `read:packages` 和 `write:packages`。只有管理端确实要
删除已发布版本时才增加 `delete:packages`；GitHub Packages 的 Gradle/Maven Registry
目前要求 classic PAT，因此这个 Token 不能限制到单个仓库。不要把 Token 写进本仓库、
网页源码或接入文档。

输入有效包名后，网页会由服务端读取对应 Maven metadata，在包名输入框旁展示已发布的最新
版本和下一个版本。每个包名都从 `0.1` 开始，再次构建同一包名时自动递增为 `0.2`、
`0.3`。包名本身进入 artifactId，因此不同业务仍然拥有独立坐标：

```kotlin
implementation("com.launcher.generated:com.example.game-launcher-sdk:0.1")
```

构建完成页只展示这一条包专属依赖。`launcher-core`、`launcher-sdk-api`、
`launcher-sdk-runtime` 及其他 Launcher 自有模块全部位于 GitHub Packages，并由 POM
自动传递；业务不需要添加 JitPack 或分别声明这些模块。AndroidX、Compose 等第三方库仍
从业务工程已有的 Google Maven/Maven Central 仓库解析。

启动：

```bash
cd /Users/rickyyao/github/lawnchair
python3 tools/sdk-builder/server.py
```

浏览器打开 <http://127.0.0.1:8765>。网页与 API 使用同源访问；不要从 IDE 静态服务器
直接打开 `static/index.html`。

## GitHub 源码工作区模式

本地开发默认直接使用当前 checkout。要验证部署后的行为，可以让每个构建任务从 GitHub
下载指定分支或 Commit 的源码压缩包：

```bash
SDK_SOURCE_MODE=github \
GITHUB_SOURCE_OWNER=vihuela \
GITHUB_SOURCE_REPOSITORY=lawnchair-sdk \
GITHUB_SOURCE_REF=feature/sdk-15 \
python3 tools/sdk-builder/server.py
```

私有仓库优先通过服务端环境变量 `GITHUB_SOURCE_TOKEN` 读取；未单独配置时，构建任务会用
本次 GitHub Packages Token 读取源码。服务端流式下载压缩包，校验路径穿越、越界链接、
特殊文件、压缩包大小和必要 Gradle 文件后，解压到系统临时目录。编译、R8、GitHub
Packages 发布和报告提取都在该任务工作区内完成；报告复制到服务输出目录后，临时源码会
被删除。

本地自动化测试可以用服务端环境变量 `GITHUB_SOURCE_ARCHIVE_URL` 覆盖下载地址；该地址
不接受网页输入，避免构建服务被用于请求任意网络资源。线上不要配置 `file://` 地址。

## Cloudflare + GitHub Actions 部署

线上网页和 API 位于 `tools/sdk-builder/cloudflare-worker`。Cloudflare Worker 负责静态页面、
包名校验、版本查询、CI 触发与状态结果代理；Android 编译在 GitHub 托管 Runner 中执行，
不依赖开发者电脑。`.github/workflows/launcher_sdk_package.yml` 会 checkout 私有仓库，安装并
校验 Java 21、Android 36.1、Build Tools 36.1.0、Gradle Wrapper、源码文件与磁盘空间，
再构建、校验并发布 GitHub Packages。

网页的“构建记录”通过 `/api/history` 分页读取该工作流的全部 `workflow_dispatch` 记录，
展示任意业务包名的状态、开始时间、耗时、源码 Commit 和 Actions 入口；运行中的任务会
自动刷新，也可以从记录中继续跟踪进度。

Cloudflare 只保存用于触发和读取私有仓库 Actions 的长期凭证，且必须使用 Worker Secret：

```bash
cd tools/sdk-builder/cloudflare-worker
npx wrangler secret put GITHUB_USERNAME
npx wrangler secret put GITHUB_TOKEN
npm run deploy
```

不要把凭证放在 `wrangler.jsonc`、静态 HTML 或普通 `vars` 中。CI 发布使用仓库自动生成且
随任务失效的 `GITHUB_TOKEN`，工作流仅授予 `contents: read`、`packages: write` 和
`actions: read`。网页不会拿到任何 GitHub 凭证；Worker 只读取 CI 内部的 `job.json` 状态结果，
不会向公网转发 AAR、mapping 或源码产物。

线上页面可公开访问。Worker 对包名做白名单式格式校验、同一 IP 30 秒冷却，并将全局并发
构建限制为 3 个；如后续需要更严格的防滥用，可在创建任务前增加 Cloudflare Turnstile。
仅含状态结果的 Actions artifact 保留 1 天；最终 Maven 产物只发布到 GitHub Packages，
并按仓库权限长期保存。

也可以直接验证底层构建：

```bash
./gradlew \
  :launcher-sdk-client:clean \
  :launcher-sdk-client:assemblePreR8 \
  :launcher-sdk-client:assembleRelease \
  -PlauncherClientPackage=com.example.game \
  --no-configuration-cache
```

构建任务串行执行，避免两个包名同时覆盖 Gradle 模块的生成目录。只有 ABI 校验通过后
才会执行 GitHub Packages 发布。网页只绑定
`127.0.0.1`，不要直接暴露到公网；若要做成团队服务，应在外层增加鉴权、任务队列和
独立构建容器。
