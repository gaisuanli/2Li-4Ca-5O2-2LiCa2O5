# MySQL Workbench 可视化快照

`db_sitesafe_visual` 是从当前运行中的 H2 数据库复制得到的只读展示快照，便于使用 MySQL Workbench 查看表、关系和业务数据。

- 原 H2 文件不会被修改，网页仍连接 H2。
- 快照包含 14 张业务表、4 个辅助视图。
- `sitesafe_visual_overview.sql` 提供数据总览、设备状态、告警闭环、AI 会话和最新遥测查询。
- 该快照不会自动跟随 H2 后续变化；需要时应重新执行受控同步，而不是导入 `database/reference` 下的旧素材 SQL。
