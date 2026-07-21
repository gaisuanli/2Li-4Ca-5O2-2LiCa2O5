# 数据库业务快照

`sitesafe-h2-full.sql` 是当前演示库的 UTF-8 H2 逻辑快照，包含明确列名、表结构、约束以及全部可发布业务记录。`manifest.json` 保存逐表行数和 SQL 文件 SHA-256，便于确认仓库中的数据没有遗漏或被意外修改。

出于安全原因，快照不包含 H2 数据库用户口令，也不允许导出 `ai_agent_provider_config.encrypted_api_key`。应用账号只保留 README 已公开的三个演示账号所对应的 BCrypt 哈希；真实账号、真实凭据和生产数据不得进入公开仓库。二进制 `runtime/*.mv.db`、锁文件和跟踪文件可能含历史页或敏感残留，始终由 `.gitignore` 排除。

重新生成：

```powershell
Set-Location D:\building-agent\platform
.\scripts\export-h2-business-snapshot.ps1
```

脚本只连接当前默认的本地 H2 文件库，并在输出前执行常见密钥模式扫描。只要存在个人 AI 服务商配置记录，导出就会失败；请先在 AI Agent 页面清除这些凭据配置，不要尝试把密文当作普通业务数据提交。

恢复到一个新的 H2 文件库：

```powershell
$h2Jar = Get-ChildItem "$env:USERPROFILE\.m2\repository\com\h2database\h2" -Recurse -Filter 'h2-*.jar' |
  Sort-Object LastWriteTime -Descending | Select-Object -First 1
java -cp $h2Jar.FullName org.h2.tools.RunScript `
  -url 'jdbc:h2:file:./runtime/sitesafe-restored' `
  -user sa `
  -script '.\database\snapshot\sitesafe-h2-full.sql'
```

恢复目标必须是全新的空库。MySQL 8 应使用 `schema-mysql.sql` 和隔离冒烟流程初始化；本快照保留 H2 类型与语法，不应直接导入生产 MySQL。
