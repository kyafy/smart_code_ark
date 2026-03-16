# smart_code_ark
一个云端的代码生成平台
```markdown
# AI毕业设计代码生成平台架构设计文档

## 1. 系统概述

### 1.1 项目背景
为高校学生提供智能化毕业设计代码生成平台，通过AI技术自动化完成从需求分析到代码生成的全过程。

### 1.2 核心价值
- **效率提升**：将毕业设计开发周期从数月缩短至数小时
- **技术指导**：提供标准化的代码结构和技术选型建议
- **学习辅助**：通过AI机器人系统帮助理解代码逻辑

### 1.3 技术架构总览
```
┌─────────────────────────────────────────────────────────────┐
│                    用户界面层 (Frontend)                     │
│              Web / Mobile / IDE Plugin                      │
└─────────────────────┬───────────────────────────────────────┘
                      │ API Gateway
┌─────────────────────┼───────────────────────────────────────┐
│               接入层 (API Layer)                            │
│        Auth Service  │  Project Service  │  User Service     │
└─────────────────────┼───────────────────────────────────────┘
                      │
┌─────────────────────┼───────────────────────────────────────┐
│               业务层 (Business Layer)                       │
│    Requirement    │  Generation   │  Project Mgmt  │  Billing │
└─────────────────────┼───────────────────────────────────────┘
                      │
┌─────────────────────┼───────────────────────────────────────┐
│              AI编排层 (AI Orchestration)                    │
│       AI Orchestrator                                       │
│    ├── Requirement Parser Agent                             │
│    ├── Architecture Designer Agent                          │
│    ├── Backend Generator Agent                              │
│    ├── Frontend Generator Agent                             │
│    ├── Database Generator Agent                             │
│    ├── Document Generator Agent                             │
│    └── QA Validator Agent                                   │
└─────────────────────┼───────────────────────────────────────┘
                      │
┌─────────────────────┼───────────────────────────────────────┐
│              执行层 (Execution Layer)                       │
│    Task Queue    │  Code Generator   │  LangBot Engine      │
└─────────────────────┼───────────────────────────────────────┘
                      │
┌─────────────────────┼───────────────────────────────────────┐
│              基础设施层 (Infrastructure)                    │
│ PostgreSQL │ Redis │ Qdrant │ Object Storage │ S3/GCS        │
└─────────────────────────────────────────────────────────────┘
```

## 2. 系统架构设计

### 2.1 微服务架构划分

#### 2.1.1 核心服务模块
| 服务名称 | 职责 | 技术栈 |
|---------|------|--------|
| User Service | 用户认证授权 | java |
| Project Service | 项目管理 | java |
| Requirement Service | 需求解析 | java/FastAPI |
| Generation Service | 代码生成 | java/FastAPI |
| Task Service | 任务调度 | java |
| Chat Service | AI机器人 | java/LangChain |
| Billing Service | 计费系统 | java |
| Admin Service | 后台管理 | java |

#### 2.1.2 数据流设计
```
用户需求输入 → 需求解析 → 任务分解 → 并行生成 → 质量检查 → 项目打包
     ↓           ↓          ↓          ↓         ↓          ↓
  NLP处理    DAG编排    Agent调度   Prompt执行  自动测试    ZIP导出
```

### 2.2 数据库设计

#### 2.2.1 用户体系表 (8表)
```sql
-- 用户主表
users: id, email, phone, password_hash, status, created_at, updated_at
user_profiles: id, user_id, nickname, avatar, school, major, tech_stack_pref, created_at
user_roles: id, user_id, role, granted_at
user_sessions: id, user_id, token, expires_at, ip_address, user_agent
email_verifications: id, email, code, used, expires_at
sms_verifications: id, phone, code, used, expires_at
login_logs: id, user_id, ip_address, user_agent, login_time, success
user_settings: id, user_id, settings_json, updated_at
```

#### 2.2.2 项目体系表 (12表)
```sql
projects: id, user_id, name, description, status, created_at, updated_at
project_versions: id, project_id, version, changelog, created_at
project_templates: id, name, tech_stack, description, template_files
project_files: id, project_id, version_id, file_path, content, file_type
project_dependencies: id, project_id, dependency_name, version, type
project_configs: id, project_id, config_key, config_value, scope
project_builds: id, project_id, version_id, status, started_at, finished_at
build_steps: id, build_id, step_name, status, log_output, duration
build_logs: id, build_id, timestamp, level, message
project_shares: id, project_id, share_token, expires_at, view_count
project_ratings: id, project_id, user_id, rating, comment
project_downloads: id, project_id, user_id, download_time, ip_address
```

#### 2.2.3 AI生成体系表 (10表)
```sql
ai_tasks: id, user_id, task_type, input_data, status, priority, created_at
task_dags: id, task_id, dag_json, execution_order
prompt_templates: id, name, category, template_text, version, created_by
prompt_versions: id, template_id, version, content, created_at
prompt_executions: id, template_id, input_vars, output_result, tokens_used, cost
code_generations: id, task_id, file_path, generated_code, language, framework
requirement_analyses: id, task_id, structured_output, confidence_score, raw_response
ai_conversations: id, session_id, user_id, message, response, model_used
conversation_sessions: id, user_id, started_at, ended_at, context_summary
model_usage_stats: id, model_name, tokens_input, tokens_output, cost_usd, date
```

#### 2.2.4 商业体系表 (8表)
```sql
subscriptions: id, user_id, plan_id, status, start_date, end_date, auto_renew
subscription_plans: id, name, price, features, max_projects, max_ai_calls
credits: id, user_id, balance, total_earned, total_spent
credit_transactions: id, user_id, amount, transaction_type, reference_id, description
payments: id, user_id, amount, currency, payment_method, status, paid_at
payment_methods: id, user_id, type, details_encrypted, is_default
invoices: id, user_id, subscription_id, amount, period_start, period_end, status
usage_limits: id, user_id, resource_type, limit_amount, current_usage, reset_date
billing_alerts: id, user_id, alert_type, threshold, last_triggered
```

## 3. 核心技术实现

### 3.1 AI编排引擎设计

#### 3.1.1 Agent架构
```typescript
interface BaseAgent {
  name: string;
  description: string;
  inputSchema: object;
  outputSchema: object;
  execute(input: any): Promise<any>;
  validateOutput(output: any): boolean;
}

class RequirementParserAgent implements BaseAgent {
  async execute(requirement: string): Promise<RequirementAnalysis> {
    const prompt = this.buildPrompt(requirement);
    const response = await this.callLLM(prompt);
    return this.parseResponse(response);
  }
  
  private buildPrompt(requirement: string): string {
    return `
      请将以下毕业设计需求解析为结构化JSON：
      需求：${requirement}
      
      输出格式：
      {
        "project_name": "项目名称",
        "modules": ["模块1", "模块2"],
        "pages": ["页面1", "页面2"],
        "entities": ["实体1", "实体2"],
        "tech_stack": ["技术栈1", "技术栈2"]
      }
    `;
  }
}
```

#### 3.1.2 任务DAG编排
```python
class TaskOrchestrator:
    def __init__(self):
        self.task_queue = TaskQueue()
        self.agent_registry = AgentRegistry()
    
    def create_generation_pipeline(self, requirement: str) -> TaskDAG:
        dag = TaskDAG()
        
        # 阶段1: 需求解析
        requirement_task = Task(
            agent="requirement_parser",
            input={"text": requirement}
        )
        dag.add_task(requirement_task)
        
        # 阶段2: 架构设计
        architecture_task = Task(
            agent="architecture_designer",
            input={"analysis": "{{requirement_task.output}}"},
            dependencies=[requirement_task.id]
        )
        dag.add_task(architecture_task)
        
        # 阶段3: 数据库设计
        database_task = Task(
            agent="database_designer",
            input={"architecture": "{{architecture_task.output}}"},
            dependencies=[architecture_task.id]
        )
        dag.add_task(database_task)
        
        return dag
```

### 3.2 Prompt工程体系

#### 3.2.1 Prompt模板管理
```sql
-- Prompt模板表结构
CREATE TABLE prompt_templates (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name VARCHAR(255) NOT NULL,
    category VARCHAR(100),
    template TEXT NOT NULL,
    version INTEGER DEFAULT 1,
    created_by UUID REFERENCES users(id),
    is_active BOOLEAN DEFAULT true,
    created_at TIMESTAMP DEFAULT NOW(),
    updated_at TIMESTAMP DEFAULT NOW()
);

-- 版本控制表
CREATE TABLE prompt_versions (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    template_id UUID REFERENCES prompt_templates(id),
    version INTEGER NOT NULL,
    content TEXT NOT NULL,
    created_by UUID REFERENCES users(id),
    created_at TIMESTAMP DEFAULT NOW()
);
```

#### 3.2.2 核心Prompt模板示例

##### 后端CRUD生成模板
```prompt
你是一个资深后端开发工程师，请根据以下实体信息生成完整的CRUD代码：

实体信息：
{{entity_info}}

技术栈：{{tech_stack}}

要求：
1. 生成Controller类，包含增删改查接口
2. 生成Service类，实现业务逻辑
3. 生成Repository/DAO类，处理数据访问
4. 生成DTO类，用于数据传输
5. 生成Entity类，对应数据库表
6. 使用标准的RESTful API设计
7. 添加必要的异常处理和参数校验
8. 代码风格符合{{framework}}最佳实践

输出格式：
```java
// Controller代码
@Controller
@RequestMapping("/api/{{entity_name_lower}}")
public class {{EntityName}}Controller {
    // CRUD方法实现
}

// Service代码
@Service
public class {{EntityName}}Service {
    // 业务逻辑实现
}

// 其他相关代码...
```

请严格按照以上要求生成代码。
```

##### 前端页面生成模板
```prompt
你是一个资深前端开发工程师，请根据以下页面需求生成Vue3组件代码：

页面需求：{{page_requirement}}
技术栈：Vue3 + {{ui_framework}}

要求：
1. 使用Composition API风格
2. 实现响应式数据绑定
3. 添加表单验证
4. 集成API调用
5. 使用{{ui_framework}}组件库
6. 添加加载状态和错误处理
7. 代码结构清晰，注释完整

输出格式：
```vue
<template>
  <!-- 页面模板 -->
</template>

<script setup>
// JavaScript逻辑
</script>

<style scoped>
/* 样式 */
</style>
```

请严格按照以上要求生成代码。
```

### 3.3 RAG知识库系统

#### 3.3.1 代码向量化流程
```python
class CodeVectorizer:
    def __init__(self):
        self.embedding_model = SentenceTransformer('all-MiniLM-L6-v2')
        self.vector_db = QdrantClient(host='localhost', port=6333)
    
    def process_code_repository(self, repo_path: str):
        """处理代码仓库，进行向量化"""
        chunks = self.chunk_code_files(repo_path)
        for chunk in chunks:
            embedding = self.embedding_model.encode(chunk.content)
            self.store_vector(chunk.id, embedding, chunk.metadata)
    
    def chunk_code_files(self, repo_path: str) -> List[CodeChunk]:
        """将代码文件切分成小块"""
        chunks = []
        for file_path in self.get_source_files(repo_path):
            with open(file_path, 'r') as f:
                content = f.read()
            
            # 按函数/类进行切分
            function_chunks = self.split_by_functions(content)
            for i, chunk_content in enumerate(function_chunks):
                chunks.append(CodeChunk(
                    id=f"{file_path}_{i}",
                    content=chunk_content,
                    metadata={
                        "file_path": file_path,
                        "language": self.detect_language(file_path),
                        "type": "function"
                    }
                ))
        return chunks
    
    def search_similar_code(self, query: str, limit: int = 5) -> List[SearchResult]:
        """搜索相似代码片段"""
        query_embedding = self.embedding_model.encode(query)
        return self.vector_db.search(
            collection_name="code_knowledge",
            query_vector=query_embedding,
            limit=limit
        )
```

### 3.4 任务队列系统

#### 3.4.1 异步任务处理
```typescript
interface Task {
  id: string;
  type: string;
  payload: any;
  priority: number;
  createdAt: Date;
  maxRetries: number;
  retryCount: number;
}

class TaskQueue {
  private redis: Redis;
  private workerPool: Worker[];
  
  async enqueue(task: Omit<Task, 'id' | 'createdAt'>): Promise<string> {
    const taskId = uuidv4();
    const fullTask: Task = {
      id: taskId,
      ...task,
      createdAt: new Date(),
      retryCount: 0
    };
    
    await this.redis.zadd('task_queue', task.priority, JSON.stringify(fullTask));
    return taskId;
  }
  
  async processTasks(): Promise<void> {
    while (true) {
      const tasks = await this.redis.zrangebyscore(
        'task_queue',
        0,
        Date.now(),
        'LIMIT',
        0,
        10
      );
      
      for (const taskJson of tasks) {
        const task: Task = JSON.parse(taskJson);
        await this.processTask(task);
      }
      
      await sleep(1000); // 1秒轮询间隔
    }
  }
  
  private async processTask(task: Task): Promise<void> {
    try {
      switch (task.type) {
        case 'code_generation':
          await this.handleCodeGeneration(task.payload);
          break;
        case 'requirement_analysis':
          await this.handleRequirementAnalysis(task.payload);
          break;
        // 其他任务类型...
      }
      
      await this.markTaskComplete(task.id);
    } catch (error) {
      if (task.retryCount < task.maxRetries) {
        await this.retryTask(task);
      } else {
        await this.markTaskFailed(task.id, error.message);
      }
    }
  }
}
```

## 4. 部署架构

### 4.1 容器化部署
```dockerfile
# 后端服务Dockerfile
FROM node:18-alpine AS builder
WORKDIR /app
COPY package*.json ./
RUN npm ci --only=production
COPY . .
RUN npm run build

FROM node:18-alpine AS runtime
WORKDIR /app
COPY --from=builder /app/dist ./dist
COPY --from=builder /app/node_modules ./node_modules
EXPOSE 3000
CMD ["node", "dist/main.js"]

# 前端服务Dockerfile
FROM node:18-alpine AS builder
WORKDIR /app
COPY package*.json ./
RUN npm ci
COPY . .
RUN npm run build

FROM nginx:alpine
COPY --from=builder /app/dist /usr/share/nginx/html
COPY nginx.conf /etc/nginx/nginx.conf
EXPOSE 80
```

### 4.2 Kubernetes部署配置
```yaml
# deployment.yaml
apiVersion: apps/v1
kind: Deployment
metadata:
  name: generation-service
spec:
  replicas: 3
  selector:
    matchLabels:
      app: generation-service
  template:
    metadata:
      labels:
        app: generation-service
    spec:
      containers:
      - name: generation-service
        image: ai-platform/generation-service:latest
        ports:
        - containerPort: 3000
        env:
        - name: DATABASE_URL
          valueFrom:
            secretKeyRef:
              name: db-secret
              key: url
        resources:
          requests:
            memory: "256Mi"
            cpu: "250m"
          limits:
            memory: "512Mi"
            cpu: "500m"
        readinessProbe:
          httpGet:
            path: /health
            port: 3000
          initialDelaySeconds: 30
---
apiVersion: v1
kind: Service
metadata:
  name: generation-service
spec:
  selector:
    app: generation-service
  ports:
  - port: 80
    targetPort: 3000
  type: ClusterIP
```

## 5. 性能优化策略

### 5.1 缓存策略
```typescript
class CacheManager {
  private redis: Redis;
  private lruCache: LRUCache<string, any>;
  
  constructor() {
    this.redis = new Redis(process.env.REDIS_URL);
    this.lruCache = new LRUCache({
      max: 1000,
      ttl: 1000 * 60 * 5 // 5分钟
    });
  }
  
  async getCached<T>(key: string, loader: () => Promise<T>): Promise<T> {
    // LRU缓存优先
    const lruValue = this.lruCache.get(key);
    if (lruValue !== undefined) {
      return lruValue as T;
    }
    
    // Redis缓存
    const redisValue = await this.redis.get(key);
    if (redisValue) {
      const parsed = JSON.parse(redisValue);
      this.lruCache.set(key, parsed);
      return parsed;
    }
    
    // 加载数据
    const value = await loader();
    await this.redis.setex(key, 300, JSON.stringify(value)); // 5分钟过期
    this.lruCache.set(key, value);
    return value;
  }
}
```

### 5.2 数据库优化
```sql
-- 关键索引优化
CREATE INDEX idx_users_email ON users(email);
CREATE INDEX idx_projects_user_id ON projects(user_id);
CREATE INDEX idx_project_files_path ON project_files(file_path);
CREATE INDEX idx_ai_tasks_status ON ai_tasks(status);
CREATE INDEX idx_build_steps_build_id ON build_steps(build_id);

-- 分区表优化（按月分区）
CREATE TABLE build_logs_2024_01 PARTITION OF build_logs
FOR VALUES FROM ('2024-01-01') TO ('2024-02-01');
```

## 6. 监控与告警

### 6.1 指标监控
```yaml
# prometheus配置
scrape_configs:
- job_name: 'ai-platform'
  static_configs:
  - targets: ['generation-service:3000', 'user-service:3000']
  metrics_path: '/metrics'
  scrape_interval: 15s

# 关键指标
- http_requests_total{service="generation-service"}
- http_request_duration_seconds{service="generation-service"}
- ai_tokens_consumed_total
- code_generation_success_rate
- task_queue_length
- user_active_count
```

### 6.2 日志聚合
```typescript
// 结构化日志记录
interface LogEntry {
  timestamp: string;
  level: 'info' | 'warn' | 'error';
  service: string;
  operation: string;
  userId?: string;
  projectId?: string;
  duration?: number;
  error?: string;
  metadata?: Record<string, any>;
}

class Logger {
  info(operation: string, data?: Record<string, any>) {
    console.log(JSON.stringify({
      timestamp: new Date().toISOString(),
      level: 'info',
      service: process.env.SERVICE_NAME,
      operation,
      ...data
    }));
  }
  
  error(operation: string, error: Error, data?: Record<string, any>) {
    console.error(JSON.stringify({
      timestamp: new Date().toISOString(),
      level: 'error',
      service: process.env.SERVICE_NAME,
      operation,
      error: error.message,
      stack: error.stack,
      ...data
    }));
  }
}
```

## 7. 安全设计

### 7.1 认证授权
```typescript
class AuthService {
  async generateJWT(user: User): Promise<string> {
    const payload = {
      sub: user.id,
      email: user.email,
      role: user.role,
      exp: Math.floor(Date.now() / 1000) + (60 * 60 * 24) // 24小时过期
    };
    
    return jwt.sign(payload, process.env.JWT_SECRET!, {
      algorithm: 'HS256'
    });
  }
  
  async validateJWT(token: string): Promise<User | null> {
    try {
      const payload = jwt.verify(token, process.env.JWT_SECRET!) as JwtPayload;
      return await this.userService.findById(payload.sub);
    } catch (error) {
      return null;
    }
  }
  
  async hashPassword(password: string): Promise<string> {
    return bcrypt.hash(password, 12);
  }
  
  async verifyPassword(plain: string, hashed: string): Promise<boolean> {
    return bcrypt.compare(plain, hashed);
  }
}
```

### 7.2 数据安全
```typescript
class DataEncryption {
  private readonly algorithm = 'aes-256-gcm';
  
  encrypt(data: string, key: string): EncryptedData {
    const iv = crypto.randomBytes(16);
    const cipher = crypto.createCipherGCM(this.algorithm, Buffer.from(key, 'hex'));
    
    const encrypted = Buffer.concat([
      cipher.update(data, 'utf8'),
      cipher.final()
    ]);
    
    const authTag = cipher.getAuthTag();
    
    return {
      encrypted: encrypted.toString('hex'),
      iv: iv.toString('hex'),
      authTag: authTag.toString('hex')
    };
  }
  
  decrypt(encryptedData: EncryptedData, key: string): string {
    const decipher = crypto.createDecipherGCM(
      this.algorithm,
      Buffer.from(key, 'hex'),
      Buffer.from(encryptedData.iv, 'hex')
    );
    
    decipher.setAuthTag(Buffer.from(encryptedData.authTag, 'hex'));
    
    const decrypted = Buffer.concat([
      decipher.update(encryptedData.encrypted, 'hex'),
      decipher.final()
    ]);
    
    return decrypted.toString('utf8');
  }
}
```

## 8. 成本控制策略

### 8.1 AI成本优化
```typescript
class AICostOptimizer {
  private cache: Map<string, any>;
  
  async executeWithOptimization(prompt: string, model: string): Promise<any> {
    // 1. 检查缓存
    const cacheKey = this.generateCacheKey(prompt, model);
    if (this.cache.has(cacheKey)) {
      return this.cache.get(cacheKey);
    }
    
    // 2. 检查相似请求
    const similarResult = await this.findSimilarRequest(prompt);
    if (similarResult) {
      return similarResult;
    }
    
    // 3. 执行AI调用
    const result = await this.callAI(prompt, model);
    
    // 4. 缓存结果
    this.cache.set(cacheKey, result);
    
    return result;
  }
  
  private generateCacheKey(prompt: string, model: string): string {
    return `${model}:${crypto.createHash('md5').update(prompt).digest('hex')}`;
  }
}
```

## 9. 扩展性设计

### 9.1 插件化架构
```typescript
interface Plugin {
  name: string;
  version: string;
  initialize(config: any): Promise<void>;
  execute(input: any): Promise<any>;
  destroy(): Promise<void>;
}

class PluginManager {
  private plugins: Map<string, Plugin> = new Map();
  
  async loadPlugin(pluginPath: string): Promise<void> {
    const pluginModule = await import(pluginPath);
    const plugin: Plugin = new pluginModule.default();
    
    await plugin.initialize({});
    this.plugins.set(plugin.name, plugin);
  }
  
  async executePlugin(name: string, input: any): Promise<any> {
    const plugin = this.plugins.get(name);
    if (!plugin) {
      throw new Error(`Plugin ${name} not found`);
    }
    
    return plugin.execute(input);
  }
}
```

## 10. 总结

本架构设计充分考虑了系统的可扩展性、安全性、性能和成本控制，能够支撑大规模用户并发使用。通过微服务架构、AI编排引擎、任务队列系统等核心技术组件，实现了从需求解析到代码生成的完整闭环，为用户提供高效、智能的毕业设计代码生成服务。
```