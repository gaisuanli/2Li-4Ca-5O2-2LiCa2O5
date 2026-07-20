# 设备心跳超时与离线告警

## 1. 默认行为

设备离线监测默认关闭（`DEVICE_OFFLINE_MONITOR_ENABLED=false`）。原因是本地 H2 演示库包含固定时间的教学快照，若在没有设备模拟器持续上报时启用扫描，会把这些快照设备立即判定为离线。

接入真实设备、持续运行模拟器或部署到生产数据库后，应显式启用监测。MySQL 环境模板已经设置为启用，部署前仍需结合设备实际上报周期调整超时时间。

## 2. 配置

| 环境变量 | 默认值 | 说明 |
| --- | ---: | --- |
| `DEVICE_OFFLINE_MONITOR_ENABLED` | `false` | 是否运行心跳超时调度；H2 演示保持关闭，MySQL 模板为 `true` |
| `DEVICE_OFFLINE_TIMEOUT_SECONDS` | `300` | 最近上报时间早于当前时间多少秒后判定离线 |
| `DEVICE_OFFLINE_SCAN_INTERVAL_MS` | `30000` | 两次扫描之间的固定延迟，单位毫秒 |
| `DEVICE_OFFLINE_INITIAL_DELAY_MS` | `60000` | 应用启动后的首次扫描延迟，单位毫秒 |
| `DEVICE_OFFLINE_BATCH_SIZE` | `200` | 每轮最多处理的候选设备，允许范围由服务限制为 1～1000 |

建议把超时时间设置为设备正常上报周期的 3～5 倍，并让扫描间隔小于超时时间。启用前先确认数据库时间、应用时区和设备采集时间一致。

## 3. 状态与告警流程

1. 调度器只扫描已启用、当前为 `ONLINE`、且 `last_reported_at` 非空的设备。
2. 服务使用带状态和时间条件的更新语句原子地写入 `OFFLINE`；并发遥测已刷新心跳时，旧候选不会覆盖新状态。
3. 离线告警来源固定为 `SYSTEM_DEVICE_OFFLINE`，等级为 `HIGH`，稳定编号为 `SYS-DEVICE-OFFLINE-<deviceId>`。
4. 同一设备重复扫描不会重复创建告警；设备真正再次从在线转为离线时，原系统告警增加 `occurrences`。已恢复或已关闭的告警会以 `PENDING` 重新打开。
5. 新遥测上报会把设备恢复为 `ONLINE`，但不会用旧采集时间覆盖更新的 `last_reported_at`。对应系统离线告警从 `PENDING/PROCESSING` 自动转为 `RESOLVED`。
6. 状态变化沿用 `device.status.changed`、`alarm.created`、`alarm.updated` 和 `alarm.status.changed` 实时事件；事件均携带 `siteId`，由实时层按工地隔离。

H2 与 MySQL 均包含 `device(connection_status, enabled, last_reported_at, id)` 扫描索引，以及设备、告警来源和状态的查询索引。

## 4. 验证

专项测试：

```powershell
mvn -f backend/pom.xml -Dtest=DeviceOfflineMonitorIntegrationTest test
```

测试覆盖两个工地的归属字段、超时与未超时边界、禁用和从未上报设备、重复扫描幂等、再次离线告警合并，以及遥测恢复在线和告警自动解决。

多实例部署仍建议只让一个实例承担调度，或引入分布式调度锁。当前条件更新能保证单次状态迁移只有一个实例成功，但系统尚未提供完整的分布式调度观测和租约管理。
