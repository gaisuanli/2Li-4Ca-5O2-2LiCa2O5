# 接口参考资料说明

当前实现及验收应以 [`docs/API.md`](../API.md) 和实际后端契约为准。

用户提供的原始 Apifox 文件属于旧接口语义参考，其中含私网地址、示例口令和电话等不适合提交的内容，已移至 `materials/private-reference/source-api.apifox.json`。`materials/private-reference/` 由 `.gitignore` 排除，不得打包、提交或直接导入共享 Apifox 工作区。

需要更新接口集合时，应从当前受测 API 重新生成一份经过脱敏、删除凭据并标注版本的文件，而不是覆盖本说明或恢复旧原始文件。
