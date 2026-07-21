package edu.gzhu.sitesafe.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@Component
public class WorkflowDemoInitializer {
    private final JdbcTemplate jdbc;
    private final boolean demoDataEnabled;

    public WorkflowDemoInitializer(JdbcTemplate jdbc,
                                   @Value("${app.demo-data-enabled:true}") boolean demoDataEnabled) {
        this.jdbc = jdbc;
        this.demoDataEnabled = demoDataEnabled;
    }

    @EventListener(ApplicationReadyEvent.class)
    @Transactional
    public void seedGovernanceDefaults() {
        if (!demoDataEnabled) return;
        List<Map<String, Object>> sites = jdbc.queryForList("select id from site order by id");
        List<Long> admins = jdbc.query("select id from app_user where role='ADMIN' and enabled=true order by id",
                (rs, rowNum) -> rs.getLong(1));
        if (sites.isEmpty() || admins.isEmpty()) return;
        long adminId = admins.get(0);
        LocalDateTime now = LocalDateTime.now();
        for (Map<String, Object> site : sites) {
            long siteId = ((Number) site.get("id")).longValue();
            Long templateCount = jdbc.queryForObject(
                    "select count(*) from report_template where site_id=?", Long.class, siteId);
            if (templateCount == null || templateCount == 0) {
                jdbc.update("insert into report_template(site_id,name,description,body_template,enabled,created_by,updated_by,created_at,updated_at) values(?,?,?,?,?,?,?,?,?)",
                        siteId,
                        "安全运行日报",
                        "根据当前工地业务数据生成，须经人工审核后方可推送。",
                        "# {{siteName}} 安全运行报告\n\n"
                                + "生成时间：{{generatedAt}}\n\n"
                                + "## 运行摘要\n\n"
                                + "- 设备总数：{{deviceTotal}}\n"
                                + "- 启用且在线设备：{{onlineDeviceTotal}}\n"
                                + "- 活动告警：{{activeAlarmTotal}}，其中高等级：{{highAlarmTotal}}\n"
                                + "- 待复核视觉风险：{{pendingRiskTotal}}\n"
                                + "- 待执行或执行中喷淋任务：{{pendingSprinklerTotal}}\n\n"
                                + "## 人工审核\n\n"
                                + "请结合现场记录补充处置结论。报告审核通过前不得对外推送。",
                        true, adminId, adminId, Timestamp.valueOf(now), Timestamp.valueOf(now));
            }
            Long channelCount = jdbc.queryForObject(
                    "select count(*) from push_channel where site_id=?", Long.class, siteId);
            if (channelCount == null || channelCount == 0) {
                jdbc.update("insert into push_channel(site_id,name,type,endpoint_url,credential_env_name,enabled,created_by,created_at,updated_at) values(?,?, 'LOG',null,null,true,?,?,?)",
                        siteId, "平台审计记录", adminId, Timestamp.valueOf(now), Timestamp.valueOf(now));
            }
        }
    }
}
