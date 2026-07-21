# 实训素材使用审计

## 1. 审计范围与结论

本审计于 2026-07-20 对照以下两类输入完成：

- 原始素材包：`D:\360\贵州大学(花溪东校区)-计科2302-实训资料分享.zip`；
- 开发要求：`D:\building-agent\doc\10_建筑安全智能监控平台_HTML内容开发要点.md`。

审计采用压缩包目录、文件大小、SHA-256、当前源码引用和构建路径进行交叉核验。结论是：当前运行链路直接采用了塔吊遥测 CSV 和 `tdcfv2.glb`；`last.pt` 作为受控的可选模型资源保留但默认不加载；数据库 SQL、Apifox 和字体仅作本地参考；其余旧代码、旧依赖和通信图标没有为了“素材利用率”而强行并入现有实现。

“提供过”不等于“已在运行时使用”。对来源、许可或运行兼容性不明的素材，隔离或不采用是有意的工程决策。

## 2. 逐项采用情况

| 压缩包条目 | 分类 | 当前落点或证据 | 处理结论 |
| --- | --- | --- | --- |
| `tower_crane_data.csv` | 运行时使用 | `device-simulator/data/tower_crane_data.csv`；`device-simulator/server.js` 读取该文件 | 共 20,160 条确定性遥测记录，用于设备模拟器和协议回归。 |
| `3D塔吊模型.zip` | 运行时使用一部分 | `frontend/public/models/tower-crane.glb`；`TowerModel.vue` 通过 `/models/tower-crane.glb` 加载 | 当前文件对应素材中的 `tdcfv2.glb`；实际 GLB 只有通用节点名，已核验并绑定 `Plane002` 回转总成及 `.001/.002/.003` 小车、吊钩、钢丝绳节点。节点不匹配时按通道安全降级。另一份 `tdcf.glb` 未接入。 |
| `last.pt` | 可选运行资源 | `ai-service/models/last.pt`；仅当 `AI_ENABLE_MODEL=true` 时由 AI 适配器尝试加载 | 默认关闭，未被认定为可生产使用的已验证模型；完整边界见[模型资源清单](AI_MODEL_MANIFEST.md)。 |
| `db_build_safe.sql` | 敏感本地参考 | `database/reference/db_build_safe.sql`，由 `.gitignore` 排除 | 只用于字段语义比对，不参与 H2/MySQL 初始化；可能含个人信息、口令或历史样例，禁止提交或打包。 |
| `建筑安全智能监控平台.apifox.json` | 敏感本地参考 | `materials/private-reference/source-api.apifox.json`，整个目录由 `.gitignore` 排除 | 仅用于比对旧接口语义；包含私网地址、口令和电话等示例，当前接口以 `docs/API.md` 为准。 |
| `AlibabaPuHuiTi-2-55-Regular.otf` | 非运行时参考 | `materials/reference/AlibabaPuHuiTi-Regular.otf` | 当前 PC 视觉体系使用系统字体，未声明 `@font-face`；字体已移出 `frontend/public/`，不会随 Vite 静态资源自动发布。原始素材未附字体许可说明。 |
| `Three.js依赖库.zip` | 未采用 | 当前依赖由 `frontend/package.json` 锁定为 npm 包 `three@0.158.0` | 素材中的 r132 静态库不与当前依赖并存，避免版本漂移、重复体积和补丁来源不明。 |
| `设备通信模拟素材.zip` | 未直接采用 | 当前 `device-simulator/` 已实现五台设备轮询 | 多设备协议能力已由可测试的文本控制台与确定性设备档案实现；原素材中的连接/断开 SVG 和标识不影响功能验收，因此未额外复制。 |
| `前端初始代码.zip` | 未直接复用 | 当前 `frontend/` 为独立的 Vue 3 工程 | 素材包含大量 `node_modules`、`dist` 和复制图片；这些生成物与旧页面不作为当前可维护源码或交付依赖。 |
| `服务端初始代码.zip` | 未直接复用 | 当前 `backend/` 为完整 Spring Boot 工程 | 初始包只有零散服务端片段，不能形成当前所需的权限、分页、领域服务、数据库和审计闭环，因此未覆盖现有实现。 |
| `requirements.txt` | 未直接复用 | 当前 `ai-service/requirements.txt` 仅列出显式模型模式需要的最小依赖范围 | 原依赖清单未作为锁定环境使用；默认 AI 适配器不加载模型，启用前仍需形成可复现的依赖锁和验证记录。 |

压缩包中没有可直接对应当前工地总览的“场景图”。`frontend/public/images/site-scene.png` 是项目后续生成的演示底图，不应再描述为来自该 ZIP。原始 ZIP 本身也不复制进项目或构建产物。

## 3. 完整性哈希

以下哈希对应审计时 D 盘项目中的文件。移动 Apifox 和字体仅改变路径，不改变内容。

| 文件 | SHA-256 |
| --- | --- |
| `device-simulator/data/tower_crane_data.csv` | `35931f8fd3b153408f2edcb36ad320b1d7ca6f0c0ac29350e19b082e2b6d3628` |
| `frontend/public/models/tower-crane.glb` | `600e866fe42d59bb780cfb7f0b92f95f3ba6cd4d3b118cc3fb55016c622dd684` |
| `ai-service/models/last.pt` | `ab8db5c8b682503d91b25f4576e0ac95d05abdc91cc64e76f9399ee9d2963519` |
| `database/reference/db_build_safe.sql` | `bdb12d6563f714785df7448fb92893eb1c444b5d2410dda59ef1966dbfc83ed7` |
| `materials/private-reference/source-api.apifox.json` | `e3282990abcd43d43255973429164113be3a518c8ff39c6e1246360849bd1be6` |
| `materials/reference/AlibabaPuHuiTi-Regular.otf` | `af8f7e04c3bab5dba42925225b17fe61955834c5bad03ae344f6db62338a005c` |

可在项目根目录复核单个文件：

```powershell
Get-FileHash -Algorithm SHA256 -LiteralPath .\device-simulator\data\tower_crane_data.csv
```

哈希只能证明文件内容在比较时一致，不能证明模型、代码或数据可信、安全、获得许可或适用于生产。

## 4. 图 19—21 与 AI Agent 范围

开发要点把图 19、图 20、图 21 分别列为 Coze 智能体编排、监测助手问答和安全报告推送的设计参考。这三张图不在用户提供的实训 ZIP 中，不能计为 ZIP 运行时素材；现阶段也不会把参考截图直接放进网页。图 21 的原始参考还可能包含经过模糊处理的个人或邮件信息，不适合作为产品静态资产交付。

本期新增的 AI Agent 是第一阶段能力：提供服务端配置的模型接口、工地范围内的会话与消息记录，以及基于当前工地汇总数据的问答界面。它覆盖图 20 所表达的基本聊天交互方向，但尚未覆盖以下内容：

- Coze 或其他平台上的可视化工作流编排；
- 私有知识库、文档检索或 RAG；
- 安全报告生成、模板管理、导出和人工审核；
- 邮件、短信、企业协作工具等定向推送及送达回执。

这些能力应作为后续独立模块实现，并继续遵循最小权限、人工复核、数据来源标注和敏感信息脱敏要求；不应通过把图 19—21 当作网页图片来伪装完成。

## 5. 交付与复审规则

- `database/reference/*.sql` 和 `materials/private-reference/` 必须保持在版本控制与交付包之外。
- `frontend/public/` 只放实际需要由浏览器加载的静态资源；未接入的字体、截图和旧库不得留在该目录。
- 替换 CSV、GLB 或模型时，应更新本文件的来源、大小、哈希、使用路径和验证结论。
- 启用真实模型或外部大模型 API 前，应分别完成模型安全评审、数据出境/隐私评审、接口超时与配额测试；两类 AI 能力不能相互混称。
