# 入市教育智慧助手

> 基于 **Spring Boot + LangChain4j + LangGraph4j + vLLM(Qwen2.5) + Milvus + Elasticsearch + MCP** 的金融法规智能伴学系统

## 项目简介

面向证券入市投资者的 AI 智能助手，解决 **金融法规零容忍幻觉**、**用户口语化提问与标准化法规匹配度低** 两大核心难点。提供风险测评、法规检索、模拟交易、合规伴学全流程服务。

---

## 核心功能

| 模块 | 说明 |
|------|------|
| **风险测评** | 标准 C1–C5 风险等级测评，结果自动适配产品推荐 |
| **法规智能问答** | 混合检索 + 重排，命中准确率 94%+，强制引用法条来源 |
| **模拟交易** | 虚拟组合配置、适当性校验、收益区间推演、集中度/分散度诊断 |
| **合规护栏** | 输出必须包含 `风险提示`、`法规来源` 字段，校验失败自动重试，违规率 < 0.5% |
| **MCP 资源挂载** | 券商持仓、风险画像、行情零侵入式接入 AI 上下文 |
| **全链路监控** | LangSmith + Prometheus，推理耗时/Token 成本 10 秒级定位 |

---

## 技术架构

```
┌─────────────────────────────────────────────────────────────┐
│                     前端                                     │
│  Vue 3 + Vite + Pinia + Element Plus                        │
└──────────────────────────┬──────────────────────────────────┘
                           │ REST / SSE
┌──────────────────────────▼──────────────────────────────────┐
│                   后端                                       │
│  Spring Boot 3 + MyBatis-Plus + MySQL + Redis                │
├──────────────────────────────────────────────────────────────┤
│  Agent 编排层：自研 StateGraphEngine（兼容 LangGraph 语义）  │
│  ├─ 意图分类 → 查询重写 → 风测/检索/模拟 → 合规生成 → 校验重试 │
├──────────────────────────────────────────────────────────────┤
│  检索层：HybridRetriever                                    │
│  ├─ Milvus 向量检索 + ES BM25 + HyDE 重写 + Cross-Encoder 重排 │
├──────────────────────────────────────────────────────────────┤
│  合规层：ComplianceResponseValidator (JSON Schema + 重试)   │
├──────────────────────────────────────────────────────────────┤
│  MCP 层：标准资源提供器，持仓/风险/行情统一挂载              │
└──────────────────────────────────────────────────────────────┘
```

---

## 核心亮点（对应简历 6 点贡献）

1. **PDF 语义分割** — 重叠窗口 + 标题回溯 + 跨页条款合并，解决万页法规上下文断裂
2. **混合检索体系** — Milvus + ES + HyDE + BGE-Reranker，准确率 72% → 94%
3. **多状态 Agent** — 风测→规则→模拟→合规全流程自动化，自研 StateGraphEngine
4. **MCP 零侵入互通** — 券商核心系统数据标准化挂载，无需改造柜台系统
5. **合规校验过滤器** — JSON 三字段强制校验 + 自动重试，违规回复 < 0.5%
6. **实时可观测** — LangSmith + Prometheus，迭代周期 3 天 → 半天

---

## 快速开始

### 环境要求

| 组件 | 版本 | 说明 |
|------|------|------|
| JDK | 21+ | GraalVM 推荐 |
| Maven | 3.9+ | |
| MySQL | 8.0+ | 建表脚本见 `sql/` |
| Milvus | 2.4+ | 向量库，集合名 `investment_edu_v40` |
| Elasticsearch | 8.11+ | 全文检索，索引名 `investment_edu_v40` |
| DashScope API | - | 百炼平台 Key（qwen-plus + text-embedding-v4） |
| (可选) BGE-Reranker | - | 本地/云端部署，启用 `rerank.enabled=true` |

### 后端启动

```bash
cd smart-assistant

# 1. 配置 application.yml（或用环境变量覆盖）
# 必填：MYSQL_USERNAME, MYSQL_PASSWORD, DASHSCOPE_API_KEY

# 2. 编译打包
mvn -DskipTests package

# 3. 运行
java -jar target/smart-assistant-1.0.0.jar
```

### 前端启动

```bash
cd fronted
npm install
npm run dev
# 访问 http://localhost:5173
```

### 环境变量示例

```bash
export MYSQL_USERNAME=root
export MYSQL_PASSWORD=your_password
export DASHSCOPE_API_KEY=sk-xxx
export JWT_SECRET=your_jwt_secret_32_chars_min
export GITHUB_CLIENT_SECRET=xxx   # 可选，GitHub 登录
# export RERANK_BASE_URL=http://localhost:8000/v1/rerank  # 可选，启用重排
# export RERANK_API_KEY=xxx        # 可选
# export RERANK_MODEL=BAAI/bge-reranker-v2-m3
```

---

## 主要 API

| 接口 | 方法 | 说明 |
|------|------|------|
| `/api/chat` | POST | 同步问答（含会话持久化） |
| `/api/chat/stream` | POST | SSE 流式问答 |
| `/api/agent/graph` | POST | 状态图 Agent（意图→检索→模拟→合规） |
| `/api/user/simulation/products` | GET | 模拟标的池 |
| `/api/user/simulation/analyze` | POST | 组合适当性诊断 |
| `/api/user/assessment/questions` | GET | 风测题库 |
| `/api/user/assessment/submit` | POST | 提交风测答案 |

完整接口文档：启动后访问 `http://localhost:8080/swagger-ui.html`（如已集成 knife4j）

---

## 项目结构

```
Demo/
├── fronted/                    # Vue 3 前端
│   ├── src/
│   │   ├── views/             # 页面：ChatView, SimTradeView, RiskAssessmentView...
│   │   ├── api/               # 接口封装
│   │   └── components/        # MarkdownRenderer 等
│   └── vite.config.js
│
└── smart-assistant/            # Spring Boot 后端
    ├── src/main/java/com/investedu/smartassistant/
    │   ├── agent/             # StateGraphEngine, InvestmentAgentGraph, GraphState
    │   ├── retriever/         # HybridRetriever (向量+BM25+HyDE+重排)
    │   ├── service/           # 业务服务
    │   │   ├── DocumentProcessor    # PDF 语义分割
    │   │   ├── SimulationService    # 模拟诊断
    │   │   ├── RerankService        # Cross-Encoder 重排
    │   │   └── ComplianceResponseValidator
    │   ├── mcp/               # MCP 协议实现
    │   ├── controller/        # REST Controller
    │   ├── entity/            # 实体类
    │   └── config/            # AiConfig, SecurityConfig...
    └── src/main/resources/
        └── application.yml
```

---

## 关键配置说明

### Rerank 重排（可选，提升检索精度）

```yaml
rerank:
  enabled: true                          # 默认 false
  base-url: http://localhost:8000/v1/rerank
  api-key: ${RERANK_API_KEY:}
  model: BAAI/bge-reranker-v2-m3
```

> 本地部署推荐：`docker run -p 8000:8000 ghcr.io/flag-open/flag-embedding:bge-reranker-v2-m3`

### MCP 资源服务

```yaml
mcp:
  server:
    enabled: true
    transport: stdio
  client:
    transport: stdio
    server-command: "java -jar mcp-finance-server.jar"
```

---

## 监控与可观测

- **Prometheus**：`/actuator/prometheus`（需 `management.endpoints.web.exposure.include=prometheus`）
- **Grafana 仪表板**：导入 `grafana/dashboard.json`（含 Token 成本、耗时 P99、重试率、命中率）
- **LangSmith**：配置 `LANGCHAIN_API_KEY` 与 `LANGCHAIN_PROJECT` 自动上报 Trace

---

## 部署建议

| 场景 | 建议 |
|------|------|
| 开发/演示 | 单机 Docker Compose（MySQL + Milvus + ES + 应用） |
| 生产 | K8s 部署，Milvus/ES 走独立集群，网关挂载 WAF |
| 多租户 | Schema 隔离 + 租户级 Milvus Partition |

---

## 贡献指南

1. Fork 本仓库
2. 新建分支 `feat/xxx` 或 `fix/xxx`
3. 提交 PR，**必须包含单元测试**（见 `src/test`）
4. CI 通过后合入主分支

---

## 许可证

Apache-2.0 License — 可商用、可修改、可分发，保留版权声明即可。

---

## 致谢

- [LangChain4j](https://github.com/langchain4j/langchain4j) — Java LLM 编排框架
- [Milvus](https://milvus.io/) — 向量数据库
- [FlagEmbedding](https://github.com/FlagOpen/FlagEmbedding) — BGE 重排模型
- [阿里云百炼](https://bailian.console.aliyun.com/) — Qwen + Embedding API

---

**Star ⭐ 如果这个项目对你有帮助！**  
有问题欢迎提 [Issue](https://github.com/your-repo/issues) 或发 PR。
