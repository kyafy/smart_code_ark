# 学术检索库接入规范 — OpenAlex / Crossref / arXiv

> 版本：v1.0 | 日期：2026-03-21 | 适用模块：`academic_retrieve` Pipeline 步骤

---

## 一、现状与目标

当前系统仅接入 **Semantic Scholar** 单一检索源，存在以下局限：

- 覆盖面有限（约 2 亿条记录），部分冷门学科文献缺失
- 单一来源导致检索结果偏差，无法交叉验证
- 无法获取最新预印本（arXiv）和权威 DOI 元数据（Crossref）

本文档规范 **OpenAlex、Crossref、arXiv** 三个学术检索库的接入方案，目标是实现多源聚合检索，提升文献覆盖率和证据质量。

---

## 二、三库总览对比

| 维度 | OpenAlex | Crossref | arXiv |
|------|----------|----------|-------|
| **数据规模** | ~2.97 亿篇 | ~1.8 亿条记录 | ~240 万篇 |
| **学科覆盖** | 全学科 | 全学科 | STEM 子集（物理/CS/数学等） |
| **内容类型** | 期刊论文/书籍/数据集/学位论文 | 期刊论文/书籍/会议/预印本/数据集 | 仅预印本 |
| **响应格式** | JSON | JSON | XML（Atom 1.0） |
| **认证方式** | API Key（免费申请） | 无需认证（礼貌池需邮箱） | 无需认证 |
| **引用计数** | 有（cited_by_count） | 有（is-referenced-by-count） | 无 |
| **摘要可用性** | 倒排索引格式（需重建） | 经常缺失（取决于出版商） | 始终提供纯文本 |
| **DOI 覆盖** | 有 | 权威来源 | 部分有 |
| **OA 信息** | 有（is_oa 字段） | 无 | 全部开放获取 |
| **深度分页** | Cursor 支持 | Cursor 支持 | Offset（上限 30,000） |
| **免费速率限制** | 基于 Credit（~$1/天） | 5-10 请求/间隔 | 1 请求/3 秒 |

---

## 三、OpenAlex 接入规范

### 3.1 概述

OpenAlex 是 Microsoft Academic Graph 的开源替代品，由 OurResearch 维护。拥有全球最大的开放学术数据集，覆盖全学科，提供丰富的结构化 JSON API。

### 3.2 优势

- **数据量最大**：约 2.97 亿篇文献，其中 9700 万篇开放获取
- **全学科覆盖**：索引 34,217 种活跃 OA 期刊，远超 WoS（6,157）和 Scopus（7,351）
- **元数据丰富**：引用计数、OA 状态、主题分类、机构关联、资助信息
- **API 功能强大**：支持过滤、排序、分组、字段选择、Cursor 深度分页
- **社会科学/人文覆盖好**：相比 WoS/Scopus 在这些领域有更好的覆盖
- **批量数据快照免费下载**

### 3.3 短板

- **摘要为倒排索引格式**（`abstract_inverted_index`），非纯文本，需要客户端重建
- **2025 年 2 月起强制要求 API Key**
- **基于 Credit 的配额系统**：免费额度约 $1/天，重度搜索可能较快耗尽
- **部分记录元数据质量不稳定**（缺少国家、来源类型等信息）
- **不提供全文内容**（仅元数据 + 摘要）

### 3.4 认证与配额

| 项目 | 说明 |
|------|------|
| 注册地址 | https://openalex.org（创建账号后获取 API Key） |
| 认证方式 | 查询参数 `api_key=YOUR_KEY` |
| 免费额度 | 每日约 $1 Credit |
| 单实体获取 | 不限次数（1 Credit/次） |
| 列表+过滤 | 10,000 次调用 / 1M 结果（10 Credit/次） |
| 全文搜索 | 1,000 次调用 / 100K 结果（100 Credit/次） |

### 3.5 API 端点

| 端点 | 用途 |
|------|------|
| `GET /works` | 搜索/列出学术作品 |
| `GET /works/{id}` | 按 OpenAlex ID / DOI / PMID 获取单篇 |
| `GET /authors` | 搜索/列出作者 |
| `GET /autocomplete/{entity}` | 快速自动补全 |

### 3.6 请求参数

| 参数 | 说明 | 示例 |
|------|------|------|
| `api_key` | API Key | `api_key=xxxx` |
| `search` | 全文搜索（标题+摘要+全文） | `search=federated learning` |
| `filter` | 字段过滤 | `filter=publication_year:2024,is_oa:true` |
| `sort` | 排序 | `sort=cited_by_count:desc` |
| `per_page` | 每页结果数（最大 100） | `per_page=20` |
| `page` | 页码 | `page=1` |
| `cursor` | 深度分页（首次用 `cursor=*`） | `cursor=*` |
| `select` | 指定返回字段 | `select=id,doi,display_name,publication_year` |

**过滤语法：**
- AND：`publication_year:2024,is_oa:true`
- OR：`type:article|book`
- NOT：`type:!paratext`
- 范围：`publication_year:2020-2024`
- 比较：`cited_by_count:>100`

### 3.7 响应结构

```json
{
  "meta": {
    "count": 2767891,
    "per_page": 20,
    "page": 1
  },
  "results": [
    {
      "id": "https://openalex.org/W2741809807",
      "doi": "https://doi.org/10.1038/s41586-019-1099-1",
      "display_name": "Paper Title",
      "publication_year": 2024,
      "publication_date": "2024-03-15",
      "type": "article",
      "cited_by_count": 150,
      "is_oa": true,
      "authorships": [
        {
          "author": {
            "id": "https://openalex.org/A1234",
            "display_name": "Author Name"
          },
          "institutions": [
            { "id": "...", "display_name": "University" }
          ]
        }
      ],
      "primary_location": {
        "source": {
          "id": "...",
          "display_name": "Nature"
        }
      },
      "abstract_inverted_index": {
        "This": [0],
        "paper": [1],
        "presents": [2]
      },
      "topics": [...],
      "keywords": [...]
    }
  ]
}
```

### 3.8 摘要重建算法

OpenAlex 的 `abstract_inverted_index` 需要客户端重建为纯文本：

```java
public String reconstructAbstract(Map<String, List<Integer>> invertedIndex) {
    if (invertedIndex == null || invertedIndex.isEmpty()) return "";
    TreeMap<Integer, String> positionMap = new TreeMap<>();
    invertedIndex.forEach((word, positions) -> {
        for (Integer pos : positions) {
            positionMap.put(pos, word);
        }
    });
    return String.join(" ", positionMap.values());
}
```

### 3.9 接入建议

- **推荐用途**：主力检索源，替代/补充 Semantic Scholar
- **环境变量**：`OPENALEX_API_KEY`
- **超时设置**：10 秒
- **每次检索**：`per_page=20`，按 `cited_by_count:desc` 排序
- **字段选择**：使用 `select` 参数减少传输量

### 3.10 官方文档

| 资源 | 地址 |
|------|------|
| API 文档 | https://developers.openalex.org |
| 认证说明 | https://developers.openalex.org/how-to-use-the-api/rate-limits-and-authentication |
| Works 端点 | https://developers.openalex.org/api-entities/works |
| 数据统计 | https://openalex.org/stats |

---

## 四、Crossref 接入规范

### 4.1 概述

Crossref 是全球最大的 DOI 注册机构，管理超过 1.8 亿条学术元数据记录。作为 DOI 的权威来源，其元数据具有最高的规范性和可追溯性。

### 4.2 优势

- **DOI 元数据权威来源**：所有持有 DOI 的学术文献的规范元数据出处
- **无需注册即可使用**：基础访问完全开放，添加邮箱即可进入礼貌池
- **覆盖全学科、全出版类型**：期刊、书籍、会议、预印本、数据集、标准、报告、基金
- **过滤能力强大**：按出版日期、类型、资助者、ORCID、许可证等精细过滤
- **`select` 参数**：可指定返回字段，减少响应体积
- **Cursor 深度分页**：支持超过 10,000 条结果的遍历
- **完全免费且开放**

### 4.3 短板

- **摘要经常缺失**：取决于出版商是否存入，很多记录无摘要
- **速率限制较严格**：公共池仅 5 请求/间隔，礼貌池 10 请求/间隔
- **无引用关系图谱**：仅提供 `is-referenced-by-count` 数值，无引用列表
- **无 OA 状态信息**
- **无全文内容**
- **元数据质量依赖出版商提交**：部分记录字段不完整
- **复杂查询响应较慢**

### 4.4 认证与配额

| 层级 | 认证方式 | 速率限制 | 并发 |
|------|----------|----------|------|
| Public | 无需认证 | 5 请求/间隔 | 1 |
| Polite | 查询参数 `mailto=email@example.com` 或 `User-Agent` 含邮箱 | 10 请求/间隔 | 3 |
| Plus（付费） | Header: `Crossref-Plus-API-Token: Bearer {key}` | 150 请求/间隔 | 无限 |

> 间隔时长由响应头 `X-Rate-Limit-Interval` 指定（单位：秒）。超限返回 HTTP 429。

### 4.5 API 端点

| 端点 | 用途 |
|------|------|
| `GET /works` | 搜索所有文献 |
| `GET /works/{doi}` | 按 DOI 获取元数据 |
| `GET /funders/{id}/works` | 按资助者查文献 |
| `GET /members/{id}/works` | 按出版商查文献 |
| `GET /journals/{issn}/works` | 按期刊 ISSN 查文献 |
| `GET /types` | 有效的文献类型列表 |

### 4.6 请求参数

| 参数 | 说明 | 示例 |
|------|------|------|
| `query` | 自由搜索 | `query=machine learning` |
| `query.author` | 作者搜索 | `query.author=Hinton` |
| `query.bibliographic` | 标题/作者/ISSN/年份 | `query.bibliographic=deep learning` |
| `query.container-title` | 期刊/书名 | `query.container-title=Nature` |
| `query.affiliation` | 机构搜索 | `query.affiliation=MIT` |
| `filter` | 过滤 | `filter=from-pub-date:2020-01-01,has-abstract:true` |
| `rows` | 每页结果数（默认 20） | `rows=20` |
| `offset` | 偏移量（最大 10,000） | `offset=0` |
| `cursor` | 深度分页（首次 `cursor=*`） | `cursor=*` |
| `sort` | 排序字段 | `sort=is-referenced-by-count` |
| `order` | 排序方向 | `order=desc` |
| `select` | 返回字段 | `select=DOI,title,author,abstract` |
| `mailto` | 进入礼貌池 | `mailto=dev@example.com` |

**常用排序字段：** `relevance`、`issued`、`is-referenced-by-count`、`references-count`、`published`

**常用过滤器：**
- `from-pub-date:2020-01-01` — 起始日期
- `until-pub-date:2024-12-31` — 截止日期
- `has-abstract:true` — 仅含摘要的记录
- `type:journal-article` — 文献类型
- `has-orcid:true` — 含作者 ORCID

### 4.7 响应结构

```json
{
  "status": "ok",
  "message-type": "work-list",
  "message": {
    "total-results": 2767891,
    "items-per-page": 20,
    "items": [
      {
        "DOI": "10.1038/s41586-019-1099-1",
        "URL": "http://dx.doi.org/10.1038/s41586-019-1099-1",
        "title": ["Paper Title"],
        "type": "journal-article",
        "author": [
          {
            "given": "First",
            "family": "Last",
            "sequence": "first",
            "affiliation": [{ "name": "University" }]
          }
        ],
        "container-title": ["Nature"],
        "abstract": "<p>Abstract text in HTML...</p>",
        "issued": { "date-parts": [[2024, 3, 15]] },
        "published-print": { "date-parts": [[2024, 3]] },
        "is-referenced-by-count": 150,
        "references-count": 45,
        "publisher": "Springer Nature",
        "subject": ["General Physics and Astronomy"],
        "language": "en",
        "score": 18.5
      }
    ]
  }
}
```

> **注意**：`title` 和 `container-title` 是数组；`abstract` 可能包含 HTML 标签；`issued.date-parts` 是嵌套数组 `[[year, month, day]]`。

### 4.8 接入建议

- **推荐用途**：DOI 补全与元数据权威校验；检索有明确 DOI 的已发表文献
- **环境变量**：`CROSSREF_MAILTO`（进入礼貌池）
- **超时设置**：15 秒（复杂查询可能较慢）
- **必加过滤器**：`has-abstract:true` 确保检索结果有摘要可用
- **摘要处理**：需要去除 HTML 标签（`<p>`, `<jats:p>` 等）

### 4.9 官方文档

| 资源 | 地址 |
|------|------|
| REST API 文档 | https://api.crossref.org/swagger-ui/index.html |
| API 访问与认证 | https://www.crossref.org/documentation/retrieve-metadata/rest-api/access-and-authentication/ |
| GitHub 文档 | https://github.com/CrossRef/rest-api-doc |
| 速率限制公告 | https://www.crossref.org/blog/announcing-changes-to-rest-api-rate-limits/ |

---

## 五、arXiv 接入规范

### 5.1 概述

arXiv 是全球最大的开放获取预印本服务器，由 Cornell University 运营。在物理、计算机科学、数学领域具有不可替代的地位，是获取最新研究成果的首选来源。

### 5.2 优势

- **完全免费且开放**：无需注册、无需 API Key、无需任何认证
- **摘要始终可用**：每篇文献都有完整纯文本摘要
- **最新预印本**：论文在正式发表前数月甚至数年就可获取
- **直接 PDF 链接**：响应中包含 PDF 下载地址
- **CS/AI/ML 领域最权威**：该领域几乎所有重要论文都首发在 arXiv
- **物理、数学领域核心来源**
- **API 简单直观**：单一端点，学习成本低

### 5.3 短板

- **返回 XML（Atom 1.0），非 JSON**：需要额外的 XML 解析逻辑
- **查询能力有限**：无法按引用数过滤、无分面搜索、无字段选择
- **无引用数据**：不提供任何引用计数或引用关系
- **学科覆盖窄**：仅覆盖物理/CS/数学/定量生物/定量金融/统计/电气工程/经济学
- **不覆盖**：生命科学、社会科学、人文、医学、化学、大部分工程学科
- **速率限制**：建议每 3 秒最多 1 次请求
- **分页受限**：单次最多 2,000 条，总量上限 30,000 条
- **仅预印本**：非最终发表版本，可能与正式版有差异

### 5.4 认证与配额

| 项目 | 说明 |
|------|------|
| 认证方式 | 无（完全开放） |
| 速率限制 | 建议每 3 秒不超过 1 次请求 |
| 单次最大结果 | 2,000 条 |
| 总量上限 | 30,000 条 |

### 5.5 API 端点

单一端点：`GET http://export.arxiv.org/api/query`

### 5.6 请求参数

| 参数 | 类型 | 默认值 | 说明 |
|------|------|--------|------|
| `search_query` | string | 无 | 搜索表达式 |
| `id_list` | string | 无 | 逗号分隔的 arXiv ID 列表 |
| `start` | int | 0 | 结果偏移量（0-based） |
| `max_results` | int | 10 | 每次返回数量（最大 2,000） |
| `sortBy` | string | `relevance` | 排序字段：`relevance` / `lastUpdatedDate` / `submittedDate` |
| `sortOrder` | string | `descending` | `ascending` / `descending` |

**搜索字段前缀：**

| 前缀 | 搜索范围 | 示例 |
|------|----------|------|
| `ti` | 标题 | `ti:federated+learning` |
| `au` | 作者 | `au:hinton` |
| `abs` | 摘要 | `abs:privacy+preserving` |
| `cat` | 学科分类 | `cat:cs.AI` |
| `all` | 所有字段 | `all:transformer` |

**布尔运算符：** `AND`、`OR`、`ANDNOT`，支持括号分组。

**示例：**
```
search_query=ti:federated+AND+abs:privacy&start=0&max_results=20&sortBy=submittedDate
```

### 5.7 响应结构（XML Atom 1.0）

```xml
<?xml version="1.0" encoding="UTF-8"?>
<feed xmlns="http://www.w3.org/2005/Atom"
      xmlns:opensearch="http://a9.com/-/spec/opensearch/1.1/"
      xmlns:arxiv="http://arxiv.org/schemas/atom">

  <opensearch:totalResults>1234</opensearch:totalResults>
  <opensearch:startIndex>0</opensearch:startIndex>
  <opensearch:itemsPerPage>20</opensearch:itemsPerPage>

  <entry>
    <id>http://arxiv.org/abs/2401.12345v1</id>
    <title>Paper Title</title>
    <summary>Full abstract text in plain text...</summary>
    <published>2024-01-15T18:00:00Z</published>
    <updated>2024-01-16T09:00:00Z</updated>
    <author>
      <name>Author Name</name>
      <arxiv:affiliation>University</arxiv:affiliation>
    </author>
    <author>
      <name>Second Author</name>
    </author>
    <arxiv:primary_category term="cs.AI" />
    <category term="cs.AI" />
    <category term="cs.LG" />
    <link href="http://arxiv.org/abs/2401.12345v1" rel="alternate" type="text/html" />
    <link href="http://arxiv.org/pdf/2401.12345v1" title="pdf" type="application/pdf" />
    <arxiv:doi>10.1234/example</arxiv:doi>
    <arxiv:journal_ref>Nature 2024</arxiv:journal_ref>
    <arxiv:comment>15 pages, 8 figures</arxiv:comment>
  </entry>
</feed>
```

**关键字段提取映射：**

| XML 元素 | 映射到 PaperSourceItem 字段 |
|----------|---------------------------|
| `<id>` | url（从中提取 arXiv ID 作为 paperId） |
| `<title>` | title |
| `<summary>` | abstractText / evidenceSnippet |
| `<author><name>` | authors |
| `<published>` | year（提取年份） |
| `<arxiv:primary_category term="">` | venue（学科分类） |
| `<link type="text/html">` | url |
| `<arxiv:doi>` | DOI（可选） |

### 5.8 接入建议

- **推荐用途**：CS/AI/ML/物理/数学领域的最新预印本检索
- **学科判断**：仅当用户 discipline 属于 arXiv 覆盖范围时启用
- **XML 解析**：使用 `javax.xml.parsers.DocumentBuilder` 或 Jackson XML
- **速率控制**：请求间隔 ≥ 3 秒，建议使用限流器
- **超时设置**：10 秒
- **环境变量**：无需（免费开放）

### 5.9 arXiv 学科分类映射

| arXiv 分类 | 学科 |
|-----------|------|
| `cs.*` | 计算机科学 |
| `math.*` | 数学 |
| `physics.*` / `hep-*` / `cond-mat` / `astro-ph` | 物理学 |
| `stat.*` | 统计学 |
| `eess.*` | 电气工程与系统科学 |
| `q-bio.*` | 定量生物学 |
| `q-fin.*` | 定量金融 |
| `econ.*` | 经济学 |

### 5.10 官方文档

| 资源 | 地址 |
|------|------|
| API 用户手册 | https://info.arxiv.org/help/api/user-manual.html |
| API 基础 | https://info.arxiv.org/help/api/basics.html |
| 分类体系 | https://arxiv.org/category_taxonomy |

---

## 六、多源聚合策略建议

### 6.1 检索优先级

```
1. OpenAlex    — 主力检索源，全学科覆盖，元数据最丰富
2. arXiv       — CS/物理/数学领域补充，获取最新预印本
3. Crossref    — DOI 元数据补全与校验，获取权威出版信息
4. Semantic Scholar — 现有源保留，作为兜底
```

### 6.2 学科-检索源映射

| 学科 | 推荐检索源 |
|------|-----------|
| 计算机科学 / AI / ML | OpenAlex + arXiv + Semantic Scholar |
| 物理 / 数学 / 统计 | OpenAlex + arXiv |
| 医学 / 生物 / 化学 | OpenAlex + Crossref |
| 社会科学 / 人文 / 法学 | OpenAlex + Crossref |
| 工程 / 材料 / 环境 | OpenAlex + Crossref + Semantic Scholar |
| 经济 / 金融 | OpenAlex + Crossref + arXiv（q-fin） |

### 6.3 去重策略

多源检索结果需要去重，推荐以下优先级进行匹配：

1. **DOI 完全匹配**（最可靠）
2. **标题相似度 > 0.9**（Jaccard 或编辑距离）
3. **arXiv ID 与 DOI 交叉映射**（arXiv 论文发表后通常会获得 DOI）

去重后保留元数据最丰富的记录，缺失字段从其他源补全。

### 6.4 配置项规划

```yaml
smartark:
  paper:
    openalex:
      api-key: ${OPENALEX_API_KEY:}
      timeout-ms: ${OPENALEX_TIMEOUT_MS:10000}
    crossref:
      mailto: ${CROSSREF_MAILTO:}
      timeout-ms: ${CROSSREF_TIMEOUT_MS:15000}
    arxiv:
      timeout-ms: ${ARXIV_TIMEOUT_MS:10000}
      request-interval-ms: ${ARXIV_REQUEST_INTERVAL_MS:3000}
```

---

## 七、接入工作量评估

| 检索库 | 新建文件 | 核心工作量 | 特殊处理 |
|--------|----------|-----------|----------|
| OpenAlex | `OpenAlexService.java` | HTTP 客户端 + JSON 解析 | 摘要倒排索引重建 |
| Crossref | `CrossrefService.java` | HTTP 客户端 + JSON 解析 | HTML 摘要标签清理、日期解析 |
| arXiv | `ArxivService.java` | HTTP 客户端 + XML 解析 | XML→POJO 转换、请求限流器 |
| 聚合层 | `AcademicSearchAggregator.java` | 多源并发调用 + 去重合并 | DOI/标题去重逻辑 |

---

## 八、参考资料

| 来源 | 地址 |
|------|------|
| OpenAlex API 文档 | https://developers.openalex.org |
| OpenAlex 认证说明 | https://developers.openalex.org/how-to-use-the-api/rate-limits-and-authentication |
| Crossref REST API | https://api.crossref.org/swagger-ui/index.html |
| Crossref 访问认证 | https://www.crossref.org/documentation/retrieve-metadata/rest-api/access-and-authentication/ |
| Crossref GitHub 文档 | https://github.com/CrossRef/rest-api-doc |
| arXiv API 手册 | https://info.arxiv.org/help/api/user-manual.html |
| arXiv 分类体系 | https://arxiv.org/category_taxonomy |
| OpenAlex vs Scopus vs WoS 对比 | https://journals.plos.org/plosone/article?id=10.1371/journal.pone.0320347 |
