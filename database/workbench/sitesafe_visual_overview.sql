USE db_sitesafe_visual;

-- 01 / 数据总览：快速确认各核心业务模块的数据量。
SELECT `模块`, `记录数`
FROM db_sitesafe_visual.v_data_overview
ORDER BY FIELD(`模块`, '工地', '区域', '设备', '遥测', '告警', 'AI 风险', 'AI 会话', 'AI 消息');

-- 02 / 设备状态：按工地、区域查看设备在线与启用情况。
SELECT
    `工地`,
    `区域`,
    `设备编号`,
    `设备名称`,
    CASE `设备类型`
        WHEN 'TOWER_CRANE' THEN '塔吊'
        WHEN 'ELEVATOR' THEN '施工升降机'
        WHEN 'FORMWORK' THEN '高支模'
        WHEN 'FOUNDATION_PIT' THEN '深基坑'
        WHEN 'ENVIRONMENT' THEN '环境监测'
        WHEN 'SPRINKLER' THEN '喷淋设备'
        WHEN 'CAMERA' THEN '摄像头'
        ELSE '未知设备类型'
    END AS `设备类型`,
    CASE `连接状态` WHEN 'ONLINE' THEN '在线' WHEN 'OFFLINE' THEN '离线' ELSE '未知' END AS `连接状态`,
    CASE `是否启用` WHEN 1 THEN '启用' ELSE '停用' END AS `启用状态`,
    `最近上报`
FROM db_sitesafe_visual.v_device_status
ORDER BY `区域`, `设备编号`;

-- 03 / 告警闭环：查看告警等级、状态、发生次数和最近时间。
SELECT
    `告警编号`,
    `告警标题`,
    `区域`,
    COALESCE(`设备`, '无关联设备') AS `设备`,
    CASE `等级` WHEN 'HIGH' THEN '高' WHEN 'MEDIUM' THEN '中' WHEN 'LOW' THEN '低' ELSE '未知' END AS `等级`,
    CASE `状态`
        WHEN 'PENDING' THEN '待确认'
        WHEN 'PROCESSING' THEN '处理中'
        WHEN 'RESOLVED' THEN '已解决'
        WHEN 'CLOSED' THEN '已关闭'
        ELSE '未知'
    END AS `状态`,
    `发生次数`,
    `最近发生`
FROM db_sitesafe_visual.v_alarm_overview
ORDER BY `最近发生` DESC;

-- 04 / AI Agent 会话：核对会话归属、消息量与最近更新时间。
SELECT `会话ID`, `会话标题`, `用户`, `工地`, `消息数`, `最近更新`
FROM db_sitesafe_visual.v_ai_conversation_overview
ORDER BY `最近更新` DESC;

-- 05 / 最新遥测：每台设备最近采集到的指标数据。
SELECT
    d.name AS `设备`,
    z.name AS `区域`,
    t.metric_code AS `指标编码`,
    t.metric_value AS `指标值`,
    t.unit AS `单位`,
    t.collected_at AS `采集时间`
FROM db_sitesafe_visual.telemetry t
JOIN db_sitesafe_visual.device d ON d.id = t.device_id
JOIN db_sitesafe_visual.zone z ON z.id = d.zone_id
WHERE t.id IN (
    SELECT MAX(latest.id)
    FROM db_sitesafe_visual.telemetry latest
    GROUP BY latest.device_id, latest.metric_code
)
ORDER BY z.name, d.name, t.metric_code;
