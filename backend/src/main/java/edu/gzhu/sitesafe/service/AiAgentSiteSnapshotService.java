package edu.gzhu.sitesafe.service;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;

@Service
public class AiAgentSiteSnapshotService {
    private static final DateTimeFormatter DISPLAY_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private final JdbcTemplate jdbc;

    public AiAgentSiteSnapshotService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public SiteSnapshot capture(long siteId) {
        String siteName = jdbc.queryForObject("select name from site where id=?", String.class, siteId);
        Map<String, Object> devices = jdbc.queryForMap(
                "select count(*) as total," +
                        "sum(case when enabled=true and connection_status='ONLINE' then 1 else 0 end) as online," +
                        "sum(case when enabled=true and connection_status<>'ONLINE' then 1 else 0 end) as offline " +
                        "from device where site_id=?", siteId);
        Map<String, Object> alarms = jdbc.queryForMap(
                "select sum(case when status in ('PENDING','PROCESSING') then 1 else 0 end) as active," +
                        "sum(case when status in ('PENDING','PROCESSING') and severity='HIGH' then 1 else 0 end) as high " +
                        "from alarm where site_id=?", siteId);
        long zoneCount = number(jdbc.queryForObject("select count(*) from zone where site_id=?", Long.class, siteId));
        long pendingRisks = number(jdbc.queryForObject(
                "select count(*) from ai_risk where site_id=? and status='PENDING_REVIEW'", Long.class, siteId));
        long activeSprinklers = number(jdbc.queryForObject(
                "select count(*) from sprinkler_task where site_id=? and status in ('CREATED','DISPATCHED')",
                Long.class, siteId));
        List<Timestamp> telemetryTimes = jdbc.query(
                "select max(t.collected_at) from telemetry t join device d on d.id=t.device_id where d.site_id=?",
                (rs, rowNum) -> rs.getTimestamp(1), siteId);
        LocalDateTime lastTelemetryAt = telemetryTimes.isEmpty() || telemetryTimes.get(0) == null
                ? null : telemetryTimes.get(0).toLocalDateTime();
        return new SiteSnapshot(
                siteId,
                siteName,
                LocalDateTime.now(java.time.ZoneId.of("Asia/Shanghai")),
                zoneCount,
                number(devices.get("total")),
                number(devices.get("online")),
                number(devices.get("offline")),
                number(alarms.get("active")),
                number(alarms.get("high")),
                pendingRisks,
                activeSprinklers,
                lastTelemetryAt
        );
    }

    public String context(SiteSnapshot snapshot) {
        return "统计时间：" + DISPLAY_TIME.format(snapshot.capturedAt()) + "（Asia/Shanghai）\n"
                + "数据来源口径：当前授权工地 site_id=" + snapshot.siteId()
                + " 的 site、zone、device、alarm、ai_risk、sprinkler_task、telemetry 表实时聚合；"
                + "活动告警仅统计 PENDING/PROCESSING，待复核 AI 风险仅统计 PENDING_REVIEW，"
                + "设备在线/离线仅统计 enabled=true，最近遥测取 collected_at 最大值。\n"
                + "工地：" + snapshot.siteName() + "；区域 " + snapshot.zoneCount() + " 个；设备 "
                + snapshot.deviceTotal() + " 台（在线 " + snapshot.deviceOnline() + "、离线 "
                + snapshot.deviceOffline() + "）；活动告警 " + snapshot.activeAlarms() + " 条（高等级 "
                + snapshot.highAlarms() + "）；待复核 AI 风险 " + snapshot.pendingRisks()
                + " 条；待执行/执行中喷淋 " + snapshot.activeSprinklerTasks() + " 条；最近遥测时间："
                + (snapshot.lastTelemetryAt() == null ? "暂无" : DISPLAY_TIME.format(snapshot.lastTelemetryAt())) + "。";
    }

    public String demoAnswer(SiteSnapshot snapshot, String question) {
        String focus;
        if (containsAny(question, "告警", "报警", "异常")) {
            focus = "当前有 " + snapshot.activeAlarms() + " 条活动告警，其中高等级 "
                    + snapshot.highAlarms() + " 条。建议先在告警中心核对高等级且仍为待处理的记录。";
        } else if (containsAny(question, "设备", "离线", "在线", "塔吊", "升降机")) {
            focus = "当前启用设备中在线 " + snapshot.deviceOnline() + " 台、离线 "
                    + snapshot.deviceOffline() + " 台。建议优先核对离线设备的心跳与供电、网络状态。";
        } else if (containsAny(question, "风险", "AI", "识别", "摄像")) {
            focus = "当前有 " + snapshot.pendingRisks() + " 条 AI 风险等待人工复核。"
                    + "演示模式不会把识别结果直接当作已确认事故。";
        } else if (containsAny(question, "喷淋", "扬尘", "环境")) {
            focus = "当前有 " + snapshot.activeSprinklerTasks() + " 条待执行或执行中的喷淋任务。"
                    + "具体环境指标请以环境监测页面的设备遥测为准。";
        } else {
            focus = "当前工地共有 " + snapshot.deviceTotal() + " 台设备、" + snapshot.activeAlarms()
                    + " 条活动告警和 " + snapshot.pendingRisks() + " 条待复核 AI 风险。";
        }
        return "【演示模式】以下答复由本地规则和当前工地数据库摘要生成，未调用外部大模型，"
                + "不应视为真实模型推理或现场处置结论。\n\n"
                + context(snapshot) + "\n\n"
                + "针对你的问题：" + focus;
    }

    private boolean containsAny(String value, String... terms) {
        String normalized = value.toLowerCase(java.util.Locale.ROOT);
        for (String term : terms) {
            if (normalized.contains(term.toLowerCase(java.util.Locale.ROOT))) return true;
        }
        return false;
    }

    private long number(Object value) {
        return value instanceof Number number ? number.longValue() : 0L;
    }

    public record SiteSnapshot(
            long siteId,
            String siteName,
            LocalDateTime capturedAt,
            long zoneCount,
            long deviceTotal,
            long deviceOnline,
            long deviceOffline,
            long activeAlarms,
            long highAlarms,
            long pendingRisks,
            long activeSprinklerTasks,
            LocalDateTime lastTelemetryAt
    ) {}
}
