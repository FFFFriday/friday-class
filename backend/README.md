# 周五课堂 · 后端阅读指南

> 这份文档教你**怎么读这个后端**。不追求面面俱到，只讲「先看什么、后看什么、每个包在干嘛、想改东西该动哪」。
> 项目全貌（功能/技术/配置/未实现清单）见根目录的 `项目看板.md`。

---

## 0. 30 秒看懂这个后端是干嘛的

一句话：**老师传课件 → AI 抽取知识点 → 上课翻页同步 → 学生按页问 AI → 课后自动总结。**

目前已经打通的是前两段的一半：

```
上传 .pptx ──► 拆成一页页 + 抽出文字 ──► 生成网页幻灯片     ✅ 已做（F001）
                    │
                    ▼
              逐页调 AI 抽知识点                              ⬜ 待做（F002）
                    │
                    ▼
        上课翻页广播页码（WebSocket）                          ⬜ 待做（F003）
                    │
                    ▼
        学生按当前页问 AI                                      ⬜ 待做（F004）
                    │
                    ▼
              课后自动生成总结                                 ⬜ 待做（F005）
```

后端对外提供 REST 接口，前端（Vue）调用它。**接口长什么样，由 `docs/api-contract.md` 说了算**——那是前后端之间的合同。

---

## 1. 技术栈（以及每样东西为什么在这）

| 技术 | 用在哪 | 为什么选它 |
|---|---|---|
| **Spring Boot 3.5** | 整个后端框架 | 课程要求 + 生态成熟 |
| **Spring Security + JWT** | 登录、鉴权 | 无状态令牌，不用 Session/Redis |
| **Spring Data JPA** | 数据库读写 | 少写 SQL，实体即表 |
| **MySQL 9.5** | 结构化数据 | 本机已装 |
| **Apache POI** | 解析 `.pptx` | Java 里读 Office 文件的标准库 |
| **Lombok** | 少写 getter/setter | ⚠️ 本机有坑，见第 8 节 |

> **不用 Redis**（项目定调：并发不高，JWT 已解决登录态）、**不用 Neo4j**（「知识图谱」名实分离，实现就是「知识点 + 页码」的普通关联）。

---

## 2. 怎么跑起来

### 前置条件

| 项 | 要求 |
|---|---|
| JDK | 装好即可（本项目用 JDK 25 编译，目标字节码 21） |
| Maven | 3.9+ |
| MySQL | 9.5，已建库 `friday_class`（跑 `db/schema.sql`） |

### 必需的配置（缺一个都起不来）

| 配置 | 放哪 | 说明 |
|---|---|---|
| 数据库密码 | `src/main/resources/application-local.yml` | 该文件**已 gitignore**，不会进仓库 |
| JWT 密钥 | 同上（或环境变量 `JWT_SECRET`） | 至少 32 字节，太短启动就报错 |

`application-local.yml` 的内容长这样：

```yaml
spring:
  datasource:
    password: "你的MySQL密码"
jwt:
  secret: "至少32字节的随机串"
```

> 默认 profile 就是 `local`，所以**不用额外加参数**。

### 启动

```bash
cd backend
mvn spring-boot:run
```

看见 `Started FridayClassApplication` 就成了，服务在 **8081** 端口。

> ⚠️ **8080 被本机 nginx 占着**，所以后端用 8081；前端的代理目标也必须跟着是 8081（见第 6 节）。

### 初始化数据

```bash
# 建教师账号（注册页只能建学生，教师必须预置）
mysql --default-character-set=utf8mb4 -u root -p friday_class < db/seed_teacher.sql
# 预置账号：teacher / teacher123
```

---

## 3. 目录结构与分层

```
backend/src/main/java/com/fridayclass/
├── FridayClassApplication.java   启动类
│
├── controller/     ← 收请求、返回响应。**薄**，不写业务
├── service/        ← 业务逻辑都在这。核心
├── repository/     ← 数据库访问接口（不用写实现，Spring Data 自动生成）
├── entity/         ← 实体类 = 数据库表的 Java 版
├── enums/          ← 状态枚举（角色、课件状态、课堂状态、任务状态）
├── dto/            ← 对外的请求/响应数据结构（不直接把实体暴露给前端）
│
├── security/       ← JWT 签发、解析、认证过滤器
├── config/         ← SecurityConfig（安全过滤链、白名单、CORS）
└── common/         ← 统一响应体、业务异常、全局异常处理
```

### 为什么要分这么多层？

一句话：**每一层只干一件事，改需求时只动一层。**

| 层 | 只负责 | 不该出现 |
|---|---|---|
| Controller | 收参数、调 Service、包响应 | if/else 业务判断、SQL |
| Service | 业务规则、事务、编排 | 直接碰 HttpServletRequest |
| Repository | 查询 | 业务判断 |
| Entity | 表结构映射 | 对外暴露（用 DTO 转换） |
| DTO | 对外数据结构 | 业务逻辑 |

> **反面教材**：如果在 Controller 里直接注入 Repository 写业务，后面加校验、加事务、复用到别处时就会到处复制粘贴。

---

## 4. 一次请求的完整旅程（读代码最好的入口）

### 例子 A：老师上传课件 `POST /api/courseware/upload`

这是目前**最长的一条链路**，跟着它走一遍，能看懂 80% 的代码。

```
① 请求到达
   └─ SecurityConfig 的安全过滤链先过
      ├─ 白名单里没有这个路径 → 要求登录
      ├─ 而且这里就要求 TEACHER 角色（★ 关键，见第 5.4 节）
      └─ JwtAuthenticationFilter 解析 Authorization 头里的令牌
         └─ 查库确认用户存在且未删除 → 把用户信息放进 SecurityContext

② Controller：CoursewareController.upload()
   └─ 只是转手调用 service，自己不写逻辑

③ Service：CoursewareUploadService.upload()
   ├─ 查上传者实体
   ├─ FileStorageService.storeCoursewareFile()   ← 落盘（校验类型/大小/魔数/路径）
   ├─ PptxConverter.extractSlides()              ← POI 逐页抽文字
   └─ 事务开始（★ 只包数据库写入）
      ├─ 存 courseware（拿到 ID）
      ├─ 对每一页：生成 HTML → 写盘 → 建 courseware_page
      └─ 事务结束

④ 返回：ApiResponse{ code, message, data }
   └─ Jackson 把 DTO 转成 JSON
```

**读完这条链，你应该能回答**：文件校验在哪做？解析失败会怎样？为什么事务不包解析？

### 例子 B：登录 `POST /api/auth/login`

```
① 白名单放行（注册/登录不需要令牌）
② AuthController.login() → AuthService.login()
   ├─ 按用户名查未删除用户（查不到和密码错返回同一句话，防用户名枚举）
   ├─ BCrypt 比对密码
   └─ JwtService.generateToken() 签发令牌（含 sub/uid/role/ver）
③ 返回 { token, user }
```

**对比 A 和 B**：A 要令牌（受保护），B 不要（白名单）。这个差别**只在 `SecurityConfig` 里体现**——这就是为什么加接口第一件事是去看它。

---

## 5. 六个关键机制（看懂这些，代码就不难了）

### 5.1 统一响应格式

所有接口都返回同一个壳：

```json
{ "code": 0, "message": "ok", "data": { ... } }
```

- `code = 0` 成功，非 0 失败
- 由 `common/ApiResponse` 提供，Controller 里写 `ApiResponse.ok(data)`

**为什么**：前端只写一次拆包逻辑。

### 5.2 全局异常处理

业务代码里**直接抛异常**，不用自己 try-catch 拼错误响应：

```java
throw new BusinessException("用户名已存在");              // → HTTP 200 + code 400
throw new BusinessException(404, "课件不存在");           // → HTTP 200 + code 404
```

`common/GlobalExceptionHandler` 统一兜住：

| 异常 | 返回 |
|---|---|
| `BusinessException` | HTTP 200 + 自定义 code（前端能读到 message 显示） |
| 参数校验失败 | HTTP 200 + code 400 |
| 认证失败 / 令牌过期 | **HTTP 401**（前端据此清 token 跳登录） |
| 无权限 | **HTTP 403** |
| 请求体不是合法 JSON | HTTP 200 + code 400 |
| 路径参数类型不对 | HTTP 200 + code 400 |
| 上传超限 / 缺文件 | HTTP 200 + code 400 |
| 其他未预期异常 | HTTP 500（只记日志，不泄露堆栈） |

> **注意这个设计取舍**：业务错误走 HTTP 200 是刻意的——前端拦截器按 `code` 判断业务成败，按 HTTP 401 判断要不要重新登录。改动前先看 `frontend/src/api/http.js`。

### 5.3 JWT 认证

```
登录 → JwtService 签发令牌（HS256，密钥从配置注入）
     ↓
后续请求带 Authorization: Bearer <token>
     ↓
JwtAuthenticationFilter 解析 → 查库确认用户还在 → 写入 SecurityContext
```

**令牌里装了什么**：`sub`(用户名)、`uid`、`role`、`ver`(令牌版本)、`iat`/`exp`。

**两个关键设计**：

| 设计 | 作用 |
|---|---|
| 过滤器**每次都查库** | 用户被删/被禁用后，旧令牌立即失效 |
| 令牌里带 `ver`，和库里 `user.token_version` 比对 | **改密码后，此前签发的所有令牌全部作废** |

> 无状态 JWT 本身无法撤销，`token_version` 就是我们的撤销手段。

### 5.4 权限：两道防线

| 防线 | 位置 | 拦谁 |
|---|---|---|
| 第一道 | `SecurityConfig` 白名单 | 没登录的人；以及**上传接口的教师角色** |
| 第二道 | Controller 上的 `@PreAuthorize("hasRole('TEACHER')")` | 纵深防御 |

**⚠️ 为什么上传的教师校验必须放第一道？**

`@PreAuthorize` 是**方法级**的，要等 Controller 方法被调用才生效。而 multipart 的**文件早在更早的阶段就解析并落盘了**。只靠第二道的话，学生用合法令牌狂传大文件，文件先写进磁盘、再被 403 拒绝——**可以用学生账号撑爆磁盘**。

**新增需要登录的接口？** 什么都不用配（默认拒绝）。**要公开某个接口？** 必须显式加进 `SecurityConfig` 白名单，并且**精确到具体路径**，不要用 `/**` 图省事。

### 5.5 软删除

`user` / `courseware` / `class_session` 三张表有 `deleted` 字段。

| 情况 | 做法 |
|---|---|
| 根实体（如 `Courseware`） | 实体上加 `@SQLRestriction("deleted = 0")`，查询自动过滤 |
| **被 join 的实体（如 `User`）** | ❌ **绝不能加** `@SQLRestriction` —— 会把 join 行滤成 NULL，而外键列仍有值，Hibernate 建代理后一访问就抛 `ObjectRetrievalFailureException`。改用 Repository 显式方法名 `findByUsernameAndDeletedFalse` |

> 这条是踩过坑的，改动 `User` 相关查询前务必看一眼 `entity/User.java` 的类注释。

### 5.6 事务边界

**规则：磁盘 IO 和重 CPU 解析，绝不包在事务里。**

原因：事务会占着数据库连接（连接池默认只有 10 根）。POI 解析一份课件要几秒到几十秒，几次并发上传就能占满全部连接，**导致登录、列表等所有接口一起卡死**。

所以 `CoursewareUploadService` 用 `TransactionTemplate` 手动划边界：

```
落盘（事务外）
  → 解析（事务外）
    → 【事务内】只写数据库
```

---

## 6. 数据库与状态机

9 张表，建表脚本 `db/schema.sql`。阅读顺序建议：

```
user ─► courseware ─► courseware_page ─┬─► knowledge_point
                                       └─► preset_question
                    │
                    ├─► ai_parse_task      （解析进度）
                    └─► class_session ─┬─► qa_record （问答记录）
                                       └─► course_summary（课后总结）
```

### 课件状态流转

```
UPLOADED → CONVERTING → CONVERTED → PARSING → PARSED
                            ▲           ▲
                            │           │
                     F001 做完在这    F002 做完在这
                 （页面已建好）    （知识点已抽取）
```

任何环节出错 → `FAILED`。

---

## 7. 想加一个新接口，要动哪些文件

以「加一个『删除课件』接口」为例：

| 步骤 | 文件 | 做什么 |
|---|---|---|
| 1 | `docs/api-contract.md` | **先定契约**（URL、参数、响应），别跳过 |
| 2 | `controller/XxxController.java` | 加方法，收参数、调 Service、包 `ApiResponse` |
| 3 | `service/XxxService.java` | 写业务逻辑，`TransactionTemplate` 或 `@Transactional` 划事务 |
| 4 | `repository/XxxRepository.java` | 需要新查询就加方法（按命名规则写，Spring Data 自动实现） |
| 5 | `dto/` | 需要新的请求/响应结构就加 record |
| 6 | `config/SecurityConfig.java` | **决定这个接口是公开还是需登录**（默认需登录，无需改） |
| 7 | 实测 | 用 curl 打通，别只看编译通过 |

---

## 8. 排错手册

| 现象 | 原因 | 怎么办 |
|---|---|---|
| `Port 8081 was already in use` | 有别的实例在跑（比如 IDE 里还开着） | 关掉那个，或改端口 |
| `JWT 密钥未配置` / `密钥过短` | 没配 `jwt.secret` | 在 `application-local.yml` 补，≥32 字节 |
| `Unsupported character encoding 'utf8mb4'` | JDBC URL 写错了 | 必须写 `characterEncoding=UTF-8`（Java 字符集名，不是 MySQL 字符集名） |
| **编译报「找不到符号 getXxx()」** | **Lombok 注解处理器没运行**（JDK 23+ 起 javac 不再自动扫描 classpath 上的处理器） | `pom.xml` 里 `maven-compiler-plugin` 必须显式配 `annotationProcessorPaths`；**光升 Lombok 版本没用** |
| 查询抛 `ObjectRetrievalFailureException` | 给被 join 的实体（如 `User`）加了 `@SQLRestriction` | 去掉它，改用 `...AndDeletedFalse` 方法名 |
| 中文在数据库里变乱码 | MySQL 客户端按 GBK 解读了 UTF-8 文件 | 脚本顶部加 `SET NAMES utf8mb4;`，或命令行带 `--default-character-set=utf8mb4` |
| 列表 `total` 和实际条数对不上 | join 用了 inner join 而 countQuery 没 join | 改用 `left join fetch` |
| IDE 里满屏红但 `mvn compile` 通过 | IDEA 没把 `backend` 当 Maven 项目导入 | 右键 `backend/pom.xml` → Add as Maven Project |

---

## 9. 建议的阅读顺序

如果你是第一次读这份代码，按这个顺序，**每一步都能跑起来验证**：

| 顺序 | 读什么 | 目标 |
|---|---|---|
| 1 | `docs/api-contract.md` | 先知道**对外长什么样** |
| 2 | `db/schema.sql` | 再知道**数据长什么样** |
| 3 | `entity/` + `enums/` | 表和 Java 的对应关系 |
| 4 | `common/`（3 个文件） | 响应格式和异常怎么统一 |
| 5 | `config/SecurityConfig.java` | **谁能访问什么**（全局视角） |
| 6 | `controller/AuthController` → `service/AuthService` → `security/` | 完整走一遍登录链路 |
| 7 | `controller/CoursewareController` → `service/CoursewareUploadService` → `PptxConverter` | 走一遍最长的链路 |
| 8 | `service/CoursewareService` + `repository/CoursewareRepository` | 看查询与分页 |
| 9 | `docs/ai-agent-design.md` | 下一步要做什么 |

---

## 10. 一句话总结怎么读

> **永远从「一个请求的入口」开始读**（先看 `SecurityConfig` 决定它要不要登录，再看 Controller，然后一路跟着调用链读到 Service、Repository），
> **不要从实体类开始逐个文件读**——那样会迷路。
