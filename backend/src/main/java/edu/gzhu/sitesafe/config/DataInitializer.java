package edu.gzhu.sitesafe.config;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.LocalDateTime;

@Component
public class DataInitializer implements ApplicationRunner {
    private final JdbcTemplate jdbc;
    private final PasswordEncoder passwordEncoder;
    private final boolean enabled;

    public DataInitializer(JdbcTemplate jdbc, PasswordEncoder passwordEncoder,
                           @Value("${app.demo-data-enabled:true}") boolean enabled) {
        this.jdbc = jdbc;
        this.passwordEncoder = passwordEncoder;
        this.enabled = enabled;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (!enabled) {
            return;
        }
        Integer count = jdbc.queryForObject("select count(*) from app_user", Integer.class);
        if (count != null && count > 0) {
            return;
        }

        jdbc.update("insert into app_user(username,password_hash,display_name,role,site_scope,enabled) values(?,?,?,?,?,true)",
                "admin", passwordEncoder.encode("Admin@123"), "系统管理员", "ADMIN", "1");
        jdbc.update("insert into app_user(username,password_hash,display_name,role,site_scope,enabled) values(?,?,?,?,?,true)",
                "supervisor", passwordEncoder.encode("Safe@123"), "项目监管员", "SUPERVISOR", "1");
        jdbc.update("insert into app_user(username,password_hash,display_name,role,site_scope,enabled) values(?,?,?,?,?,true)",
                "device", passwordEncoder.encode("Device@123"), "设备管理员", "DEVICE_MANAGER", "1");

        LocalDateTime updated = LocalDateTime.of(2026, 7, 19, 16, 30);
        jdbc.update("insert into site(code,name,address,status,updated_at) values(?,?,?,?,?)",
                "SITE-GZHU-01", "贵州大学东校区实训工地", "贵州省贵阳市花溪区", "ACTIVE", Timestamp.valueOf(updated));

        Object[][] zones = {
                {"ZONE-N", "北区主体", "CONSTRUCTION", 0.08, 0.08, 0.33, 0.30},
                {"ZONE-E", "东区塔楼", "CONSTRUCTION", 0.61, 0.10, 0.27, 0.34},
                {"ZONE-C", "中区主体", "CONSTRUCTION", 0.35, 0.31, 0.31, 0.30},
                {"ZONE-W", "西区基坑", "RISK_CONTROL", 0.05, 0.47, 0.30, 0.27},
                {"ZONE-S", "南区裙房", "CONSTRUCTION", 0.45, 0.63, 0.28, 0.22},
                {"ZONE-SE", "南区在建组团", "CONSTRUCTION", 0.70, 0.55, 0.25, 0.31}
        };
        for (Object[] zone : zones) {
            jdbc.update("insert into zone(site_id,code,name,status,map_x,map_y,map_width,map_height) values(1,?,?,?,?,?,?,?)", zone);
        }

        Object[][] devices = {
                {"TC-001", "1号塔吊", "TOWER_CRANE", 2, "东区塔楼北侧", true, "ONLINE", "{\"maxLoad\":80,\"ratedMoment\":1800}"},
                {"TC-002", "2号塔吊", "TOWER_CRANE", 3, "中区主体西侧", true, "ONLINE", "{\"maxLoad\":70,\"ratedMoment\":1600}"},
                {"EL-001", "1号施工升降机", "ELEVATOR", 3, "中区主体东侧", true, "ONLINE", "{\"ratedLoad\":2000}"},
                {"FM-001", "高支模监测组", "FORMWORK", 1, "北区主体三层", true, "ONLINE", "{\"points\":12}"},
                {"PIT-001", "深基坑监测组", "FOUNDATION_PIT", 4, "西区基坑", true, "OFFLINE", "{\"points\":8}"},
                {"ENV-001", "东区环境监测站", "ENVIRONMENT", 2, "东区入口", true, "ONLINE", "{\"metrics\":[\"pm25\",\"pm10\",\"noise\",\"temperature\",\"humidity\"]}"},
                {"ENV-002", "南区环境监测站", "ENVIRONMENT", 6, "南区围挡", true, "ONLINE", "{\"metrics\":[\"pm25\",\"pm10\",\"noise\",\"temperature\",\"humidity\"]}"},
                {"SP-001", "东区喷淋设备", "SPRINKLER", 2, "东区围挡", true, "ONLINE", "{\"minimumIntervalMinutes\":30}"},
                {"CAM-001", "东区塔吊摄像头", "CAMERA", 2, "东区塔楼", true, "ONLINE", "{}"},
                {"CAM-002", "西区基坑摄像头", "CAMERA", 4, "西区基坑", true, "OFFLINE", "{}"},
                {"ENV-003", "北区环境监测站", "ENVIRONMENT", 1, "北区入口", true, "ONLINE", "{}"},
                {"SP-002", "南区喷淋设备", "SPRINKLER", 6, "南区围挡", false, "OFFLINE", "{\"manualStop\":true}"}
        };
        for (Object[] device : devices) {
            jdbc.update("insert into device(code,name,type,site_id,zone_id,location,enabled,connection_status,last_reported_at,config_json) values(?,?,?,1,?,?,?,?,?,?)",
                    device[0], device[1], device[2], device[3], device[4], device[5], device[6],
                    Timestamp.valueOf(updated.minusMinutes("ONLINE".equals(device[6]) ? 1 : 95)), device[7]);
        }

        jdbc.update("insert into alarm_rule(name,source_type,metric_code,operator,threshold_value,severity,scope_type,scope_id,enabled,suppression_seconds,updated_at) values(?,?,?,?,?,?,?,?,?,?,?)",
                "塔吊风速高值预警", "DEVICE_RULE", "windSpeed", ">", 10.0, "HIGH", "DEVICE", 1, true, 300, Timestamp.valueOf(updated));
        jdbc.update("insert into alarm_rule(name,source_type,metric_code,operator,threshold_value,severity,scope_type,scope_id,enabled,suppression_seconds,updated_at) values(?,?,?,?,?,?,?,?,?,?,?)",
                "塔吊吊重超限", "DEVICE_RULE", "weight", ">", 80.0, "HIGH", "TYPE", 1, true, 300, Timestamp.valueOf(updated));
        jdbc.update("insert into alarm_rule(name,source_type,metric_code,operator,threshold_value,severity,scope_type,scope_id,enabled,suppression_seconds,updated_at) values(?,?,?,?,?,?,?,?,?,?,?)",
                "PM2.5 超标", "ENVIRONMENT_RULE", "pm25", ">", 75.0, "MEDIUM", "SITE", 1, true, 600, Timestamp.valueOf(updated));
        jdbc.update("insert into alarm_rule(name,source_type,metric_code,operator,threshold_value,severity,scope_type,scope_id,enabled,suppression_seconds,updated_at) values(?,?,?,?,?,?,?,?,?,?,?)",
                "施工噪声超标", "ENVIRONMENT_RULE", "noise", ">", 85.0, "MEDIUM", "SITE", 1, true, 600, Timestamp.valueOf(updated));

        seedTelemetry(updated);

        jdbc.update("insert into alarm(code,site_id,zone_id,device_id,rule_id,source_type,severity,title,description,status,first_occurred_at,last_occurred_at,occurrences) values(?,?,?,?,?,?,?,?,?,?,?,?,?)",
                "ALM-20260719-001", 1, 2, 1, 1, "DEVICE_RULE", "HIGH", "1号塔吊风速超过阈值", "风速 11.80 m/s，高于规则阈值 10.00 m/s", "PENDING", Timestamp.valueOf(updated.minusMinutes(18)), Timestamp.valueOf(updated.minusMinutes(4)), 3);
        jdbc.update("insert into alarm(code,site_id,zone_id,device_id,rule_id,source_type,severity,title,description,status,first_occurred_at,last_occurred_at,occurrences) values(?,?,?,?,?,?,?,?,?,?,?,?,?)",
                "ALM-20260719-002", 1, 4, 5, null, "SYSTEM", "MEDIUM", "深基坑监测组离线", "设备最近一次上报距当前统计时点超过 90 分钟", "PROCESSING", Timestamp.valueOf(updated.minusMinutes(94)), Timestamp.valueOf(updated.minusMinutes(94)), 1);
        jdbc.update("insert into alarm(code,site_id,zone_id,device_id,rule_id,source_type,severity,title,description,status,first_occurred_at,last_occurred_at,occurrences) values(?,?,?,?,?,?,?,?,?,?,?,?,?)",
                "ALM-20260719-003", 1, 6, 7, 3, "ENVIRONMENT_RULE", "MEDIUM", "南区 PM2.5 超标", "PM2.5 读数 82.00 μg/m³，高于规则阈值 75.00 μg/m³", "RESOLVED", Timestamp.valueOf(updated.minusHours(3)), Timestamp.valueOf(updated.minusHours(2).minusMinutes(35)), 2);
        jdbc.update("insert into alarm_action(alarm_id,operator_id,action,from_status,to_status,note,created_at) values(?,?,?,?,?,?,?)",
                2, 2, "CONFIRM", "PENDING", "PROCESSING", "已通知设备管理员检查现场网关", Timestamp.valueOf(updated.minusMinutes(80)));

        jdbc.update("insert into camera(code,name,site_id,zone_id,online,stream_url,last_frame_at) values(?,?,?,?,?,?,?)",
                "CAM-001", "东区塔吊摄像头", 1, 2, true, null, Timestamp.valueOf(updated.minusSeconds(20)));
        jdbc.update("insert into camera(code,name,site_id,zone_id,online,stream_url,last_frame_at) values(?,?,?,?,?,?,?)",
                "CAM-002", "西区基坑摄像头", 1, 4, false, null, Timestamp.valueOf(updated.minusHours(2)));
        jdbc.update("insert into camera(code,name,site_id,zone_id,online,stream_url,last_frame_at) values(?,?,?,?,?,?,?)",
                "CAM-003", "南区通道摄像头", 1, 6, true, null, Timestamp.valueOf(updated.minusSeconds(38)));

        jdbc.update("insert into ai_risk(camera_id,site_id,zone_id,risk_type,confidence,model_version,occurred_at,evidence_url,status) values(?,?,?,?,?,?,?,?,?)",
                1, 1, 2, "未佩戴安全帽", 0.9340, "yolo-sitesafe-1.0", Timestamp.valueOf(updated.minusMinutes(42)), null, "PENDING_REVIEW");
        jdbc.update("insert into ai_risk(camera_id,site_id,zone_id,risk_type,confidence,model_version,occurred_at,evidence_url,status,review_note,reviewed_by,reviewed_at) values(?,?,?,?,?,?,?,?,?,?,?,?)",
                3, 1, 6, "吸烟", 0.8810, "yolo-sitesafe-1.0", Timestamp.valueOf(updated.minusHours(2)), null, "CONFIRMED", "现场负责人已确认并完成劝阻", 2, Timestamp.valueOf(updated.minusHours(1).minusMinutes(50)));
        jdbc.update("insert into ai_risk(camera_id,site_id,zone_id,risk_type,confidence,model_version,occurred_at,evidence_url,status,review_note,reviewed_by,reviewed_at) values(?,?,?,?,?,?,?,?,?,?,?,?)",
                1, 1, 2, "未穿反光服", 0.6240, "yolo-sitesafe-1.0", Timestamp.valueOf(updated.minusHours(4)), null, "FALSE_POSITIVE", "遮挡导致误识别", 2, Timestamp.valueOf(updated.minusHours(3).minusMinutes(48)));

        jdbc.update("insert into sprinkler_task(code,site_id,zone_id,trigger_type,reason,status,planned_at,started_at,ended_at,command_id,created_by) values(?,?,?,?,?,?,?,?,?,?,?)",
                "SPT-20260719-001", 1, 6, "RULE", "PM2.5 连续两次超过 75 μg/m³", "EXECUTED", Timestamp.valueOf(updated.minusHours(2).minusMinutes(30)), Timestamp.valueOf(updated.minusHours(2).minusMinutes(29)), Timestamp.valueOf(updated.minusHours(2).minusMinutes(19)), "CMD-SP-001", 2);
        jdbc.update("insert into audit_log(user_id,username,action,object_type,object_id,detail,trace_id,created_at) values(?,?,?,?,?,?,?,?)",
                2, "supervisor", "ALARM_CONFIRM", "ALARM", "2", "已通知设备管理员检查现场网关", "seed-data", Timestamp.valueOf(updated.minusMinutes(80)));
    }

    private void seedTelemetry(LocalDateTime base) {
        double[] wind = {5.8, 6.2, 6.0, 7.4, 8.1, 9.2, 10.6, 11.8, 9.7, 8.9, 7.8, 7.2};
        double[] weight = {24.3, 31.2, 36.7, 42.5, 48.2, 51.6, 60.4, 62.1, 55.8, 44.2, 38.5, 29.1};
        for (int i = 0; i < wind.length; i++) {
            LocalDateTime time = base.minusMinutes((wind.length - 1L - i) * 5);
            insertMetric("SEED-TC1-" + i, 1, "windSpeed", wind[i], "m/s", time);
            insertMetric("SEED-TC1-" + i, 1, "weight", weight[i], "t", time);
            insertMetric("SEED-TC1-" + i, 1, "rotation", 72 + i * 4.5, "°", time);
            insertMetric("SEED-TC1-" + i, 1, "height", 38 + i * 1.2, "m", time);
        }
        double[] pm = {42, 46, 51, 58, 63, 71, 78, 82, 69, 61, 57, 52};
        double[] noise = {68, 71, 74, 76, 82, 84, 87, 83, 79, 75, 72, 70};
        for (int i = 0; i < pm.length; i++) {
            LocalDateTime time = base.minusMinutes((pm.length - 1L - i) * 10);
            insertMetric("SEED-ENV1-" + i, 6, "pm25", pm[i], "μg/m³", time);
            insertMetric("SEED-ENV1-" + i, 6, "noise", noise[i], "dB", time);
            insertMetric("SEED-ENV1-" + i, 6, "temperature", 27.2 + i * 0.12, "°C", time);
            insertMetric("SEED-ENV1-" + i, 6, "humidity", 66.0 - i * 0.3, "%", time);
        }
        insertMetric("SEED-ENV2-LATEST", 7, "pm25", 52, "μg/m³", base.minusMinutes(1));
        insertMetric("SEED-ENV2-LATEST", 7, "noise", 70, "dB", base.minusMinutes(1));
    }

    private void insertMetric(String messageId, long deviceId, String code, double value, String unit, LocalDateTime time) {
        jdbc.update("insert into telemetry(message_id,device_id,metric_code,metric_value,unit,collected_at) values(?,?,?,?,?,?)",
                messageId, deviceId, code, value, unit, Timestamp.valueOf(time));
    }
}
