# 学宾接待 · 学校来宾酒店预订系统

学宾接待是基于本项目 `requirements.md`、六份 UML 与 `architecture.md` 实现的 Windows 桌面应用。前端沿用 `UI/` 原型中的深蓝侧栏、紫色交互与角色工作区。程序源码、构建脚本及运行数据集中在 `code/`。

桌面窗口使用 WinForms 和 WebView2，内嵌 **Vue 3 + TypeScript + Vite** 界面。后台使用 **Java 25 LTS + Spring Boot 3.5、Spring Security、JDBC + Flyway**。默认使用 H2 文件数据库，另提供 **MySQL 8.4 / Docker Compose** 配置。

## 快速运行（Windows）

双击项目根目录的“学宾接待”快捷方式启动，也可直接打开桌面程序或启动脚本。程序在独立窗口打开，自动启动后台。关闭窗口时后台随之退出。桌面包内置 Java 运行时，适用于安装了 .NET Framework 4.8 和 Microsoft Edge WebView2 运行时的 64 位 Windows。[桌面程序](code/release/CampusHospitality.exe) · [启动脚本](code/start.cmd) · [WebView2 运行时](https://developer.microsoft.com/en-us/microsoft-edge/webview2/#download-section)

在本项目内启动桌面版或运行 `code/start.ps1` 时，程序默认使用 `code/data/hospitality.mv.db`。把软件带到其他电脑时，请复制完整的 `code/release/`。独立运行的副本默认在自身目录的 `data/` 保存业务库，空库首次启动时生成演示数据。环境变量 `CAMPUS_HOSPITALITY_DATA_DIR` 可指定数据库目录。

从源码打包时，需要 x64 JDK 25、.NET Framework 4.8、Node.js 与 Maven。`setup.ps1` 会把缺少的 Node.js 和 Maven 下载到 `code/.runtime/`。在项目根目录执行以下命令。

```powershell
cd code
.\setup.ps1
.\package-desktop.ps1
```

`package-desktop.ps1` 构建前端和后端，执行后端测试，再打包 Windows 应用。首次打包需下载依赖，后续可复用项目缓存。

## 演示账号

以下账号密码统一为 **`Demo@123456`**，仅由演示模式创建。

| 账号 | 角色 | 范围 |
| --- | --- | --- |
| `reception` | 学校接待人员 · 李明 | 本人负责的来宾与订单 |
| `reception2` | 学校接待人员 · 王婷 | 另一接待范围，用于越权验证 |
| `hotel` | 学府宾馆工作人员 | 学府宾馆房源与接待订单 |
| `hotel2` | 湖畔商务酒店工作人员 | 湖畔商务酒店范围 |
| `admin` | 平台管理员 | 账号、校方资料、全局异常、统计与审计 |
| `guest` | 来宾 · 陈浩 | 本人的脱敏住宿安排 |
| `guest2` | 来宾 · 冯琳 | 本人的脱敏住宿安排 |

内置五家酒店、三种房型、两个接待地点、逐日房源及已确认/已入住/已退房示例。低于70分及尚未审核的酒店保留在管理档案中，不能参与推荐和预订。

## 已实现的工作流程

- **学校接待**：来宾资料与需求录入、房型单选与间数设置、逐晚筛选、综合/价格/距离排序、规则确认及自动预订；订单改订、整单取消、续住确认、特殊改订共同确认、停业替代方案确认。
- **酒店业务**：房型档案、每日价格与学校名额、维修/清洁停用与冲突待处理；逐人入住及补登记、实际间夜登记、晚到保留、人工未入住、正常/提前退房、费用提出与停业登记。
- **平台管理**：酒店资料与学校质量审核、接待地点、核实距离、费用规则版本、账号分配、费用争议处理、具体异常下受控查看完整联系方式、通知失败跟进。
- **通知与统计**：站内通知、持久化邮件任务和重试、双方确认/续住/费用待办；下单/计划/实际三种日期口径、分类费用、状态分项、逐日明细及 CSV 导出。
- **来宾服务**：本人姓名、脱敏电话、酒店地址、日期、房型与实际到店情况；协议价、他人资料和管理记录不返回来宾接口。

接待人员在“房型与间数”中使用下拉框选择一种房型，并填写预订间数；切换房型会替换原选择，调整住宿条件后需重新查询酒店。通知与待办中的关联订单在当前页面以弹窗打开，关闭后保留原来的通知或待办标签。

同一预订操作携带稳定请求标识，超时后可在页头“请求结果”查询。旧页面价格或名额变化且方案仍合格时，需重新确认。任一晚缺房时，整单预订失败，页面按原条件展示可选替代酒店。订单、全部名额、价格规则快照与请求结果同事务提交。失败改订保留原单，停业替代成功后才关闭原单并释放名额。通知发送失败进入待处理，已提交业务继续生效。

酒店需**实际登记已住间夜**，不能仅凭到店人数推算用房。退房或已入住停业前，系统检查已发生日期的实住是否已经核实。示例正在住宿的订单已住4间夜、未来2间夜。

## 开发与测试

管理员在“酒店与学校审核”和“账号与角色”的每一行可执行删除。删除采用逻辑删除，保留历史订单、费用和日志；仍有未完成业务的对象、当前登录账号及仍绑定有效工作人员的酒店不能直接删除。具体规则见[删除功能与需求一致性说明](code/docs/deletion-policy.md)。

以下开发命令在 `code/` 中运行。可在 IDE 中打开 `code/backend/pom.xml`。前端工程位于 `code/frontend/`，开发端口为 5173，`/api` 代理到 8080。`start.ps1` 启动用于开发和测试的后端，可用 `-Port` 指定端口。

```powershell
# 终端一：运行后端（需系统 Maven，或通过 tools/common.ps1 查找）
mvn -f backend/pom.xml spring-boot:run

# 终端二：运行前端（Node.js 22.12+）
cd frontend
npm ci
npm run dev
```

上述 Maven 开发命令默认使用 `code/backend/data/` 中的开发数据库。运行测试前可用 `CAMPUS_HOSPITALITY_DATA_DIR` 为手动启动的服务指定独立的数据库目录。

```powershell
.\test.ps1            # 后端集成测试 + 前端类型检查和构建
.\test.ps1 -Browser   # 另加浏览器测试；先在其他终端运行 start.ps1
```

后端集成测试使用独立内存数据库及可控时钟，覆盖并发最后一间房、重复请求、多明细失败回滚、旧报价、18:00边界、特殊改订、逐人入住、未入住、续住、提前退房、维修冲突、停业分段统计、请求恢复、CSRF 与资料隔离。测试不会清空文件数据库。

Playwright 在 Windows 默认使用已安装的 Microsoft Edge；其他系统可安装 Chromium 后运行。可通过 `E2E_BASE_URL` 调整目标地址，通过 `PLAYWRIGHT_CHANNEL` 选择浏览器。浏览器测试会创建并取消演示订单，同时生成 `code/docs/screenshots/` 下的运行截图，测试目标应使用独立演示数据库。

## MySQL / Docker 运行

在 `code/` 中运行。

```powershell
Copy-Item .env.example .env
# 编辑 .env 中的数据库密码，再启动
docker compose up --build -d
```

页面和 API 仍通过8080访问。MySQL 使用命名卷持久化，启用提交持久化配置，不向宿主机公开数据库端口。

已有 MySQL 实例时，也可直接运行 JAR：

```powershell
$env:DB_URL = 'jdbc:mysql://127.0.0.1:3306/hospitality?useUnicode=true&characterEncoding=UTF-8&serverTimezone=Asia/Shanghai'
$env:DB_USER = 'campus'
$env:DB_PASSWORD = '你的数据库密码'
.\start.ps1
```

Flyway 自动执行 `backend/src/main/resources/db/migration/` 的迁移。默认演示开关为true；正式初始化时设置 `DEMO_DATA=false` 和至少10位的 `BOOTSTRAP_ADMIN_PASSWORD`，空库只生成一个管理员，再由其配置其他资料。既有演示数据库不会因为切换开关自动删除账号。

在 HTTPS 入口后部署时设置 `COOKIE_SECURE=true`。前后端保持同源，服务端会话与 CSRF 校验已启用；不用把登录令牌存入浏览器 localStorage。

## 学校邮件配置

默认不实际发送邮件。停业邮件任务会如实记录配置缺失并进入管理员待处理，不会显示虚构的发送成功。站内通知正常工作。

配置 `MAIL_ENABLED=true`、`SMTP_HOST`、`SMTP_PORT`、`SMTP_USER`、`SMTP_PASSWORD`、`MAIL_FROM` 后，可在“发送失败跟进”中重试。责任人员学校邮箱在管理员账号维护中设置。SMTP 发送位于业务提交之后，任务支持失败退避与进程退出后的租约恢复；邮件可能因发送后结果落库前的故障重复投递，发送成功不等于已读。

## 工程目录

以下路径均相对于 `code/`。

```text
backend/src/main/java/cn/edu/campus/hospitality/
  identity/       登录、四角色、对象权限与字段范围
  masterdata/     校方资料、账号、来宾及演示初始化
  search/         住宿需求、报价快照、筛选和推荐
  inventory/      逐日价格名额、锁定、增量占用与释放
  booking/        订单编排、九态、改订取消和双确认
  stay/           逐人到店、实住、退房、续住与费用
  relocation/     停业、替代新单与原单一致处理
  notification/   持久化投递、通知待办与恢复调度
  reporting/      三种日期口径和逐日统计
  audit/          成功、失败及越权操作日志
  shared/         幂等命令、数据库与协议边界
frontend/src/    Vue 角色工作区及业务页面
deploy/          Docker 构建和 Maven 配置
tools/           开发环境查找及下载辅助
desktop/         Windows 桌面启动程序
data/            桌面业务库、日志和 WebView2 运行数据
release/         Windows 可执行文件及其运行依赖
```

当前规模采用每个模块一个主要服务，接口适配和事务基础能力集中在 `shared`；订单状态机、需求值对象及模块写入边界单独实现，避免为了目录层数增加空类。模块间仍保持同一应用、同一数据源与统一事务。

## 验证范围与后续验收

已执行的构建、测试及界面检查记录见 [验证说明](code/docs/verification.md)。

MySQL Compose 方案、真实学校 SMTP、4核8GB / 50家酒店 / 1万历史订单 / 100人并发的性能验收，以及三类普通故障的完整一小时恢复演练，仍需在相应环境中验证。H2演示与内存集成测试不能代替这些验收，不宣称已经达到全部非功能指标。

架构依据：[架构设计](architecture.md) · [需求清单](requirements.md)。框架接口参考：[Spring Boot](https://docs.spring.io/spring-boot/3.5/)、[Spring Security CSRF](https://docs.spring.io/spring-security/reference/servlet/exploits/csrf.html)、[Vue](https://vuejs.org/guide/introduction.html)、[Vite](https://vite.dev/guide/)、[MySQL锁定读取](https://dev.mysql.com/doc/refman/8.4/en/innodb-locking-reads.html)。
