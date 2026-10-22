# NEST · 高校宿舍智能分配与可解释决策支持系统

> **Near-stable Explainable Student-housing maTching**（安居）
> 厦门大学嘉庚学院 · 大学生创新创业训练计划项目

把「宿舍分配」从**按学号顺序排房**改造成一个可求解、可解释、可申诉的匹配问题：
在容量约束下求**近稳定**室友匹配，对每一个分配结果给出四维解释，并为不服的学生提供**反事实**申诉通道。
系统同时承载一套完整的学工业务平台（48 个页面），宿舍分配是其中的算法内核。

| 项 | 值 |
|---|---|
| 后端 | Spring Boot 3.2.3 · MyBatis 3.0.3 · MySQL · Java 17 |
| 前端 | 48 个静态页面 · 零框架 · 零 CDN · daisyUI 4 + Tailwind（本地构建） |
| 规模 | 292 个主源文件 / 27 Controller / 36 Service / 62 Mapper / 61 份 Mapper XML |
| 测试 | `mvn test` → **1,649 通过 / 0 失败** |
| 部署形态 | 校园内网离线可用 · PWA 可安装 · 手机 H5 适配 |

---

## 一、它解决什么问题

传统做法是按学号、按班级顺序排房，结果是**室友关系全靠运气**，且学生无处申诉。

NEST 把问题形式化为「**带容量的近稳定室友匹配**」：

- **输入**：学生（性别、专业、年级、作息、生活习惯、志愿）+ 房间（楼栋、容量、年级/特殊需求限制）
- **约束**：性别分楼、容量不超、年级不混住、特殊需求专用房 —— 全部是**可关闭的开关**，不写死在代码里
- **目标**：最大化房内两两相容度，同时压住「木桶效应」（最差的那一对）与志愿违背
- **输出**：一份分配方案 + 指标 + 每个学生的四维解释 + 零阻塞对

### 求解管线

```
硬约束校验
  → 块分解：按「性别 × 楼栋类型(普通/特殊) × 专业大类 × 年级」切块，块间并行（无锁）
    → 块内：志愿簇预分组 → 最难的人优先贪心构造
      → 局部搜索（含木桶守卫，每轮扫邻域移动）
        → 阻塞对修复（容忍极小目标损失的局部交换）
  → 全局违规复核 → 兜底补齐 → 四维解释预生成
```

求解器标识：`nest-block-greedy-ls-repair-v1`（`src/main/java/com/umi/nest/dorm/solver/NestSolver.java`）

### 三项创新点

1. **容量约束下「近稳定」的可计算定义**
   零阻塞对在带容量时是 NP-hard，所以不宣称「稳定」，而是**最小化阻塞对数量**，并用 CP-SAT 在小规模上求出上界来**量化 gap**（`mvn -Pcpsat` 构建后的 `OptimalJudge`）。

2. **可解释 + 反事实申诉**
   每个学生四层解释：① 相容度逐维贡献 ② 命中/未命中哪些约束 ③ 志愿层 ④ 对比层（"换一种分法会差多少"）。
   申诉不是"驳回/同意"二选一，而是**反事实重解**：满足你的要求要牺牲谁，代价多大。三级结构（房间级毫秒 / 块级秒 / 跨块几十秒），采纳只改相关房间、不重跑全局。

3. **块分解**
   按硬属性切块并行，是万级规模**唯一跑得动的办法**；块内亲密度天然更高。

### 指标（一键分配的 8 张指标卡）

硬约束违规（恒为 0） · 未分配人数（应为 0） · 均值相容度 · **最差那一对** · 房内方差 · 志愿满足率 · **阻塞对数** · 运行耗时

展开"诊断信息"还能看到每一级的耗时与动作次数：`greedyMsTotal` / `localSearchMsTotal` / `localSearchAcceptedMoves` / `blockingRepairsApplied` / `blockCount` / `smallBlocks` / `blocksByPool` / `occupancy` …

> **指标基准**：合成数据带**隐真值**（存在 `dorm_ground_truth`，求解器读不到），指标是「真值基准」；
> 导入真实名单没有隐真值，指标降级为「模型预测值」，接口会返回 `metricBasisWarning` **主动声明**。
> 真实数据路径下我们**不宣称**"相容度提升了 X%"，只做模型侧自检。

---

## 二、AI 辅助分配

不是"让大模型排房"（那既不可复现也不可解释），而是**用大模型把非结构化素材变成算法能吃的特征**：

学生的一句自述 → 11 维性格/作息特征 → 喂给相容度函数。

| 环节 | 做法 |
|---|---|
| 素材 | 自述 / 聊天记录 / 文章 / 辅导员观察（**录入时即带授权开关**） |
| 提取 | `EnsembleExtractor`：规则提取 + 大模型提取**融合** |
| 融合规则 | 问卷作答**永远优先** → 两路都有值时取高置信度（差 <0.10 取均值）→ 只有一路就用那一路 |
| 采用 | 低于 `apply.min-confidence`（默认 0.35）**不回写**特征表，保留原值 |
| 可验证 | 「提取器评测」：反写真特征 → 只喂文字 → 比 MAE / Pearson / Spearman |

**离线降级是设计的一部分**：
`nest.psyche.llm.api-key` 为空或服务器不通外网时，`PsycheProperties.llmReady()` 为 false，整个模块自动退化为**纯本地规则提取**；连得上但调用失败时，`LlmExtractor` 兜底返回规则结果并**保持 `method=RULE` 不伪装**。系统不崩、不静默降级成假数据。

---

## 三、学工业务平台

除宿舍分配外，平台覆盖学生工作全流程（48 页 / 27 Controller）：

| 域 | 模块 |
|---|---|
| 宿舍 | 智能分配 · 房间视图 · 四维解释 · 申诉处理 · 公平性审计 · 补采建议 · 心理特征提取 · 学生门户 |
| 日常 | 请假离校（含外出/晚归）· 学生档案 · 辅导员名册 · 综合素质测评（德智体美劳）· 综测排名 |
| 奖惩资助 | 评奖评优 · 困难认定（含异议复议）· 生源地助学贷款 · 勤工助学 |
| 成长 | 第二课堂成绩单 · 志愿服务 · 学生社团年审 · 校园文化 |
| 政工 | 学生党建（五阶段台账 + 党校 + 公示）· 心理健康（普查 / 关注 / 转介 / 预约）· 社区管理 |
| 平台 | 公告 · 邀请码 · 用户管理 · 权限矩阵 · 数据级授权 · 审计日志 · 验证码 |

**权限有两层**：框架层粗筛 `ApiRoleGuardInterceptor`（22 条路由规则，默认拒绝）+ 服务层数据级校验（辅导员只能操作自己名下的学生，否则 403）。

---

## 四、快速开始

### 环境要求

- JDK 17+
- Maven 3.9+
- MySQL 8.0+（开发用 9.5.0 验证）
- Node.js 18+（**仅重建 CSS 时需要**，运行时不依赖）

### 1. 初始化数据库

建库 `nest`，字符集 `utf8mb4`。

> ⚠️ **`sql/` 目录里同时躺着已下线教学模块的旧建表脚本**（`course_tables.sql`、`quiz_tables.sql`、`materials.sql` 等）。
> 执行它们会把 11 张已删除的教学表建回来。**下面没列到的 `.sql` 一律不要执行。**

按顺序导入（**顺序不能颠倒**，后者依赖前者的表）：

```
sql/nest_init.sql                基础表 + 邀请码种子
sql/nest_verify_code.sql         验证码
sql/nest_audit.sql               审计日志 + 登录失败锁定列
sql/nest_dorm.sql                宿舍分配 10 张表
sql/nest_psyche.sql              心理特征表
sql/nest_psyche_v2.sql           特征表补列 + 评测留档
sql/nest_appeal_v3.sql           申诉跨块试算列
sql/nest_xuegong.sql             学工域 7 张表
sql/nest_xuegong_seed.sql        综测指标种子
sql/nest_p8_teaching_offline.sql 公告改学工口径 + DROP 11 张教学表
sql/nest_workstudy.sql           勤工助学 4 张表
sql/nest_aid_review.sql          困难认定 + 级别口径迁移
sql/nest_honor.sql               评奖评优 3 张表
sql/nest_rank.sql                综测排名 5 张表
sql/nest_psyche_work.sql         心理健康 8 张表
sql/nest_community.sql           社区管理 7 张表
sql/nest_party.sql               学生党建 7 张表
sql/nest_volunteer.sql           志愿服务 5 张表
sql/nest_club.sql                学生社团 5 张表
sql/nest_secondclass.sql         第二课堂 3 张表
sql/nest_loan.sql                生源地贷款 1 张表
```

### 2. 配置

复制 `src/main/resources/application-example.properties` 为 `application.properties`（该文件已被 `.gitignore` 忽略），至少改这三项：

```properties
spring.datasource.url=jdbc:mysql://<主机>:3306/nest?useSSL=false&serverTimezone=Asia/Shanghai&characterEncoding=utf8&allowPublicKeyRetrieval=true
spring.datasource.username=<用户名>
spring.datasource.password=<密码>

# 验证码哈希盐 —— 默认值是公开的，必须换（验证码本体不落库，只存 HMAC）
nest.verify.pepper=<至少 32 位随机串>

# 头像落盘目录
app.upload.dir=<部署目录>/uploads/avatars
```

**大模型凭据不要写进上面这个文件**（它会进版本库）。放在同目录的 `config/local.properties`（已 gitignore）：

```properties
nest.psyche.llm.api-key=sk-xxxxxxxx
```

不配也能跑 —— 见上文「离线降级」。

### 3. 构建与运行

```bash
# 开发运行
mvn -B -ntp spring-boot:run

# 打包（跳过测试）
mvn -B -ntp clean package -DskipTests
java -jar target/nest-0.0.1-SNAPSHOT.jar

# 含 CP-SAT 最优性校验（需要 OR-Tools，仅小规模）
mvn -Pcpsat -B -ntp clean package
```

打开 <http://localhost:8080/>。

### 4. 演示数据（生产环境不要执行）

```bash
# 方式一：一键脚本（复位 → 按序加载 12 个 demo 文件 → 自检）
powershell -ExecutionPolicy Bypass -File tools/demo-seed.ps1

# 方式二：手工按序导入
sql/nest_seed.sql                管理员 / 教师 / 学生账号，密码统一 123456
sql/nest_xuegong_demo.sql        学工档案 + 辅导员分派
sql/nest_xuegong_eval_demo.sql   综测自评
# …其余 *_demo.sql 按 docs/给学校的部署说明.md §3 清单
sql/nest_demo_reset.sql          复位脚本（幂等，只删 E2E / 探针残留）
```

**演示账号**（密码均为 `123456`）：

| 账号 | 角色 |
|---|---|
| `admin` | 管理员 |
| `zhangsan` / `lisi` | 辅导员（各自名下学生不同） |
| `wangxm` / `zhaoxh` / `chendw` | 学生 |

> 演示宿舍分配请走「**生成合成数据**」而不是「手工导入名单」——
> 只有合成数据带隐真值，指标才是真值基准，否则会弹出 `metricBasisWarning` 声明指标降级。

---

## 五、测试与质量门禁

```bash
# 单元 / 集成测试（1,649 项）
mvn -B -ntp test

# 页面契约与静态检查
node tools/check-dom-ids.mjs       src/main/resources/static   # id 引用一致性
node tools/check-hidden-display.mjs src/main/resources/static  # hidden/display 冲突
node tools/check-mapper-columns.mjs                            # entity 字段 ↔ mapper XML 可读性
node tools/check-inline-js.mjs     <页面文件>                    # 内联脚本语法（逐页）
powershell -File tools/check-motion.ps1                        # 动效 8 项
powershell -File tools/check-unify.ps1                         # 视觉统一 26 页
powershell -File tools/check-redesign.ps1                      # 页面结构 48 页
powershell -File tools/mobile-audit.ps1 -Widths 375,768        # 移动端溢出与触控目标

# 改动 CSS 后必须重建（前端零 CDN，样式全部本地构建）
powershell -File tools/webbuild/build-web-assets.ps1
powershell -File tools/webbuild/verify-css.ps1                 # 期望输出 ALL HIT
```

---

## 六、目录结构

```
src/main/java/com/umi/nest/
├── dorm/            ★ 宿舍分配算法内核
│   ├── solver/      块分解 · 贪心构造 · 局部搜索 · 阻塞对修复 · 稳定性审计
│   ├── compat/      相容度函数（接口 + 规则实现 + 学习型基线）
│   ├── explain/     四维解释 · 申诉 · 跨块反事实
│   ├── psyche/      AI 辅助：素材 → 特征（规则 + LLM 融合）
│   ├── eval/        评测：基线对比 · CP-SAT 最优上界 · 信息价值实验
│   └── web/         REST 接口
├── controller/      学工业务 27 个 Controller
├── service/ mapper/ entity/ dto/   业务分层
├── config/          权限拦截器、安全配置等
└── verify/ wechat/ ai/             验证码、微信、教学模块遗留 AI 客户端

src/main/resources/static/          48 个页面 + 8 个脚本 + 本地 CSS（零框架）
src/test/java/                      36 个测试类
sql/                                建表与演示数据（注意旧脚本陷阱）
tools/                              门禁脚本 + CSS 构建链
docs/                               22 份设计/部署/答辩文档
```

---

## 七、文档索引

| 想了解 | 看这里 |
|---|---|
| 部署到学校 | `docs/给学校的部署说明.md`（1,295 行，含数据库初始化逐条清单、配置项核对表、HTTPS/PWA、已知限制） |
| 算法设计与论证 | `docs/superpowers/specs/2026-09-27-nest-dorm-assignment-design.md` |
| 创新点与文献对照 | `docs/创新点-不确定感知的宿舍分配.md` · `docs/宿舍分配算法-文献对照.md` · `docs/大创/创新点与文献差异化.md` |
| 给老师演示 | `docs/结题材料/网页演示方案.md`（网页版，12 分钟五幕）· `docs/结题材料/演示脚本.md`（CLI 版） |
| 老师自己试用 | `docs/老师试用剧本.md`（三条路线，约 25 分钟） |
| 前端设计系统 | `docs/前端设计系统说明.md` |
| 手机端 H5 | `docs/手机端H5适配交接.md`（含真机验证清单与 12 条坑） |
| 权限与授权 | `docs/权限与数据级授权审计.md` |
| 答辩 / 结题 | `docs/大创/答辩大纲.md` · `docs/大创/结题报告.md` · `docs/结题材料/答辩提纲.md` |

---

## 八、已知限制（如实列出）

1. **真实数据 = 0**：演示与评测全部使用合成数据（带隐真值）。真实数据没有隐真值，相容度指标只能是模型预测值，接口会主动声明，不能与合成数据的真值指标并排比较。
2. **AI 依赖公网**：大模型走 `api.deepseek.com`，校园内网不通时模块自动降级为纯规则提取（功能可用，语义理解能力下降）。
3. **无短信 / 邮件网关**：验证码接口返回 `delivered=false`，码是真的但不投递。实现 `VerificationCodeSender` 并标 `@Primary` 即可接入。
4. **权限是接口级粗筛**：`SecurityConfig` 对请求 `permitAll`，实际拦截靠 `ApiRoleGuardInterceptor` 的 22 条路由规则 + 服务层数据级校验，不是方法级细粒度鉴权。
5. **申诉是局部重解**：界面如实显示"在我找过的范围内不能满足"，不是全局重解。
6. **CP-SAT 最优校验只覆盖小规模**：OR-Tools 裁判需 `mvn -Pcpsat` 构建，用于量化与最优解的 gap。
7. **心理量表是占位**：SCL-90 / SDS 的题项、常模、划界值等属专业内容，**不臆造**，只留结构与流程。
8. **第二课堂学分阈值留空**：学校未定，建表刻意不预置任何数字，三列可为 NULL。
9. **微信登录未配置**：`appid` / `secret` 为空，相关能力未启用。

本项目由课堂教学管理系统改造而来，教学模块已于 P8 下线（`sql/nest_p8_teaching_offline.sql` 会 DROP 那 11 张表）。`课设报告.md` 属于该阶段的历史文档。

---

## 九、第三方组件

前端依赖**全部本地化**（内网断网可用），不使用任何 CDN：

| 组件 | 许可 |
|---|---|
| daisyUI 4.12.24 · Tailwind CSS 3.4 | MIT |
| Open Props · Radix Colors | MIT |
| Instrument Serif | SIL OFL 1.1 |
| Lenis · tsParticles · Splitting · embla-carousel · @formkit/auto-animate | MIT |
| paper-design/shaders | Apache-2.0 |
| OR-Tools（CP-SAT，可选 profile） | Apache-2.0 |

明确**未使用**：GSAP（非 OSI 许可）、typed.js / TypeIt / ScrollReveal（GPL）、ReactBits（附加限制）、Origin UI（AGPL）、three.js。
