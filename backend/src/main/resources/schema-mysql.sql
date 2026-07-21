create table if not exists app_user (
    id bigint not null auto_increment,
    username varchar(50) not null,
    password_hash varchar(100) not null,
    display_name varchar(100) not null,
    role varchar(32) not null,
    site_scope varchar(255) not null,
    enabled tinyint(1) not null default 1,
    created_at datetime(3) not null default current_timestamp(3),
    primary key (id),
    unique key uq_app_user_username (username)
) engine=InnoDB default charset=utf8mb4 collate=utf8mb4_0900_ai_ci;

create table if not exists ai_agent_provider_config (
    user_id bigint not null,
    base_url varchar(500) not null,
    model varchar(100) not null,
    encrypted_api_key varchar(2048) null,
    created_at datetime(3) not null,
    updated_at datetime(3) not null,
    primary key (user_id),
    constraint fk_ai_agent_provider_config_user foreign key (user_id)
        references app_user (id) on delete cascade
) engine=InnoDB default charset=utf8mb4 collate=utf8mb4_0900_ai_ci;

create table if not exists site (
    id bigint not null auto_increment,
    code varchar(32) not null,
    name varchar(120) not null,
    address varchar(255) null,
    status varchar(20) not null,
    updated_at datetime(3) not null,
    primary key (id),
    unique key uq_site_code (code)
) engine=InnoDB default charset=utf8mb4 collate=utf8mb4_0900_ai_ci;

create table if not exists zone (
    id bigint not null auto_increment,
    site_id bigint not null,
    code varchar(32) not null,
    name varchar(100) not null,
    status varchar(20) not null,
    map_x decimal(6,3) not null,
    map_y decimal(6,3) not null,
    map_width decimal(6,3) not null,
    map_height decimal(6,3) not null,
    primary key (id),
    unique key uq_zone_code (code),
    key idx_zone_site (site_id),
    constraint fk_zone_site foreign key (site_id) references site (id)
) engine=InnoDB default charset=utf8mb4 collate=utf8mb4_0900_ai_ci;

create table if not exists device (
    id bigint not null auto_increment,
    code varchar(64) not null,
    name varchar(120) not null,
    type varchar(32) not null,
    site_id bigint not null,
    zone_id bigint not null,
    location varchar(255) null,
    enabled tinyint(1) not null default 1,
    connection_status varchar(20) not null,
    last_reported_at datetime(3) null,
    config_json text null,
    primary key (id),
    unique key uq_device_code (code),
    key idx_device_site_type (site_id, type),
    key idx_device_zone (zone_id),
    key idx_device_connection_heartbeat (connection_status, enabled, last_reported_at, id),
    constraint fk_device_site foreign key (site_id) references site (id),
    constraint fk_device_zone foreign key (zone_id) references zone (id)
) engine=InnoDB default charset=utf8mb4 collate=utf8mb4_0900_ai_ci;

create table if not exists telemetry (
    id bigint not null auto_increment,
    message_id varchar(80) not null,
    device_id bigint not null,
    metric_code varchar(64) not null,
    metric_value decimal(18,4) not null,
    unit varchar(24) null,
    collected_at datetime(3) not null,
    created_at datetime(3) not null default current_timestamp(3),
    primary key (id),
    unique key uq_telemetry_message_metric (message_id, metric_code),
    key idx_telemetry_device_time (device_id, collected_at),
    key idx_telemetry_device_metric_time (device_id, metric_code, collected_at, id),
    constraint fk_telemetry_device foreign key (device_id) references device (id)
) engine=InnoDB default charset=utf8mb4 collate=utf8mb4_0900_ai_ci;

create table if not exists alarm_rule (
    id bigint not null auto_increment,
    name varchar(120) not null,
    source_type varchar(32) not null,
    metric_code varchar(64) not null,
    operator varchar(8) not null,
    threshold_value decimal(18,4) not null,
    severity varchar(16) not null,
    scope_type varchar(16) not null,
    scope_id bigint null,
    enabled tinyint(1) not null default 1,
    suppression_seconds int not null default 300,
    updated_at datetime(3) not null,
    primary key (id),
    key idx_alarm_rule_metric_enabled (metric_code, enabled),
    key idx_alarm_rule_scope (scope_type, scope_id, id)
) engine=InnoDB default charset=utf8mb4 collate=utf8mb4_0900_ai_ci;

create table if not exists alarm (
    id bigint not null auto_increment,
    code varchar(64) not null,
    site_id bigint not null,
    zone_id bigint not null,
    device_id bigint null,
    rule_id bigint null,
    source_type varchar(32) not null,
    severity varchar(16) not null,
    title varchar(160) not null,
    description varchar(500) not null,
    status varchar(20) not null,
    first_occurred_at datetime(3) not null,
    last_occurred_at datetime(3) not null,
    occurrences int not null default 1,
    primary key (id),
    unique key uq_alarm_code (code),
    key idx_alarm_site_status_time (site_id, status, last_occurred_at),
    key idx_alarm_site_zone_time (site_id, zone_id, last_occurred_at, id),
    key idx_alarm_device_rule_time (device_id, rule_id, last_occurred_at),
    key idx_alarm_device_source_status (device_id, source_type, status, id),
    key idx_alarm_zone (zone_id),
    key idx_alarm_rule (rule_id),
    constraint fk_alarm_site foreign key (site_id) references site (id),
    constraint fk_alarm_zone foreign key (zone_id) references zone (id),
    constraint fk_alarm_device foreign key (device_id) references device (id),
    constraint fk_alarm_rule foreign key (rule_id) references alarm_rule (id)
) engine=InnoDB default charset=utf8mb4 collate=utf8mb4_0900_ai_ci;

create table if not exists alarm_action (
    id bigint not null auto_increment,
    alarm_id bigint not null,
    operator_id bigint not null,
    action varchar(24) not null,
    from_status varchar(20) not null,
    to_status varchar(20) not null,
    note varchar(500) null,
    created_at datetime(3) not null,
    primary key (id),
    key idx_alarm_action_alarm_time (alarm_id, created_at),
    key idx_alarm_action_operator (operator_id),
    constraint fk_alarm_action_alarm foreign key (alarm_id) references alarm (id),
    constraint fk_alarm_action_operator foreign key (operator_id) references app_user (id)
) engine=InnoDB default charset=utf8mb4 collate=utf8mb4_0900_ai_ci;

create table if not exists camera (
    id bigint not null auto_increment,
    code varchar(64) not null,
    name varchar(120) not null,
    site_id bigint not null,
    zone_id bigint not null,
    online tinyint(1) not null,
    stream_url varchar(500) null,
    last_frame_at datetime(3) null,
    primary key (id),
    unique key uq_camera_code (code),
    key idx_camera_site (site_id),
    key idx_camera_site_code (site_id, code, id),
    key idx_camera_site_zone (site_id, zone_id),
    key idx_camera_zone (zone_id),
    constraint fk_camera_site foreign key (site_id) references site (id),
    constraint fk_camera_zone foreign key (zone_id) references zone (id)
) engine=InnoDB default charset=utf8mb4 collate=utf8mb4_0900_ai_ci;

create table if not exists ai_risk (
    id bigint not null auto_increment,
    camera_id bigint not null,
    site_id bigint not null,
    zone_id bigint not null,
    risk_type varchar(64) not null,
    confidence decimal(6,4) not null,
    model_version varchar(64) not null,
    occurred_at datetime(3) not null,
    evidence_url varchar(500) null,
    status varchar(20) not null,
    review_note varchar(500) null,
    reviewed_by bigint null,
    reviewed_at datetime(3) null,
    primary key (id),
    key idx_ai_risk_site_status_time (site_id, status, occurred_at),
    key idx_ai_risk_camera (camera_id),
    key idx_ai_risk_zone (zone_id),
    key idx_ai_risk_reviewer (reviewed_by),
    constraint fk_ai_risk_camera foreign key (camera_id) references camera (id),
    constraint fk_ai_risk_site foreign key (site_id) references site (id),
    constraint fk_ai_risk_zone foreign key (zone_id) references zone (id),
    constraint fk_ai_risk_reviewer foreign key (reviewed_by) references app_user (id)
) engine=InnoDB default charset=utf8mb4 collate=utf8mb4_0900_ai_ci;

create table if not exists sprinkler_task (
    id bigint not null auto_increment,
    code varchar(64) not null,
    site_id bigint not null,
    zone_id bigint not null,
    trigger_type varchar(24) not null,
    reason varchar(500) not null,
    status varchar(24) not null,
    planned_at datetime(3) not null,
    started_at datetime(3) null,
    ended_at datetime(3) null,
    command_id varchar(64) null,
    failure_reason varchar(500) null,
    created_by bigint not null,
    primary key (id),
    unique key uq_sprinkler_task_code (code),
    key idx_sprinkler_task_site_time (site_id, planned_at),
    key idx_sprinkler_task_site_status_time (site_id, status, planned_at, id),
    key idx_sprinkler_task_site_zone_time (site_id, zone_id, planned_at, id),
    key idx_sprinkler_task_zone (zone_id),
    key idx_sprinkler_task_creator (created_by),
    constraint fk_sprinkler_task_site foreign key (site_id) references site (id),
    constraint fk_sprinkler_task_zone foreign key (zone_id) references zone (id),
    constraint fk_sprinkler_task_creator foreign key (created_by) references app_user (id)
) engine=InnoDB default charset=utf8mb4 collate=utf8mb4_0900_ai_ci;

create table if not exists audit_log (
    id bigint not null auto_increment,
    user_id bigint null,
    username varchar(50) not null,
    action varchar(80) not null,
    object_type varchar(50) not null,
    object_id varchar(80) null,
    detail varchar(1000) null,
    trace_id varchar(64) null,
    created_at datetime(3) not null,
    primary key (id),
    key idx_audit_log_time (created_at),
    key idx_audit_log_user_time (username, created_at),
    key idx_audit_log_object (object_type, object_id)
) engine=InnoDB default charset=utf8mb4 collate=utf8mb4_0900_ai_ci;

create table if not exists ai_agent_conversation (
    id bigint not null auto_increment,
    user_id bigint not null,
    site_id bigint not null,
    title varchar(80) not null,
    created_at datetime(3) not null,
    updated_at datetime(3) not null,
    primary key (id),
    key idx_ai_agent_conversation_owner_time (user_id, site_id, updated_at, id),
    constraint fk_ai_agent_conversation_user foreign key (user_id) references app_user (id),
    constraint fk_ai_agent_conversation_site foreign key (site_id) references site (id)
) engine=InnoDB default charset=utf8mb4 collate=utf8mb4_0900_ai_ci;

create table if not exists ai_agent_message (
    id bigint not null auto_increment,
    conversation_id bigint not null,
    role varchar(16) not null,
    content mediumtext not null,
    provider_mode varchar(32) null,
    provider_model varchar(100) null,
    created_at datetime(3) not null,
    primary key (id),
    key idx_ai_agent_message_conversation_time (conversation_id, created_at, id),
    constraint fk_ai_agent_message_conversation foreign key (conversation_id)
        references ai_agent_conversation (id) on delete cascade
) engine=InnoDB default charset=utf8mb4 collate=utf8mb4_0900_ai_ci;

create table if not exists knowledge_document (
    id bigint not null auto_increment,
    site_id bigint not null,
    title varchar(160) not null,
    category varchar(80) not null,
    source_reference varchar(500) null,
    content mediumtext not null,
    version int not null default 1,
    status varchar(24) not null,
    created_by bigint not null,
    reviewed_by bigint null,
    review_note varchar(500) null,
    created_at datetime(3) not null,
    updated_at datetime(3) not null,
    reviewed_at datetime(3) null,
    primary key (id),
    key idx_knowledge_document_site_status_time (site_id, status, updated_at, id),
    key idx_knowledge_document_creator (created_by),
    key idx_knowledge_document_reviewer (reviewed_by),
    constraint fk_knowledge_document_site foreign key (site_id) references site (id),
    constraint fk_knowledge_document_creator foreign key (created_by) references app_user (id),
    constraint fk_knowledge_document_reviewer foreign key (reviewed_by) references app_user (id)
) engine=InnoDB default charset=utf8mb4 collate=utf8mb4_0900_ai_ci;

create table if not exists report_template (
    id bigint not null auto_increment,
    site_id bigint not null,
    name varchar(120) not null,
    description varchar(500) null,
    body_template mediumtext not null,
    enabled tinyint(1) not null default 1,
    created_by bigint not null,
    updated_by bigint not null,
    created_at datetime(3) not null,
    updated_at datetime(3) not null,
    primary key (id),
    key idx_report_template_site_enabled_time (site_id, enabled, updated_at, id),
    key idx_report_template_creator (created_by),
    key idx_report_template_updater (updated_by),
    constraint fk_report_template_site foreign key (site_id) references site (id),
    constraint fk_report_template_creator foreign key (created_by) references app_user (id),
    constraint fk_report_template_updater foreign key (updated_by) references app_user (id)
) engine=InnoDB default charset=utf8mb4 collate=utf8mb4_0900_ai_ci;

create table if not exists safety_report (
    id bigint not null auto_increment,
    code varchar(64) not null,
    site_id bigint not null,
    template_id bigint null,
    title varchar(160) not null,
    content mediumtext not null,
    status varchar(24) not null,
    created_by bigint not null,
    reviewed_by bigint null,
    review_note varchar(500) null,
    created_at datetime(3) not null,
    updated_at datetime(3) not null,
    reviewed_at datetime(3) null,
    primary key (id),
    unique key uq_safety_report_code (code),
    key idx_safety_report_site_status_time (site_id, status, updated_at, id),
    key idx_safety_report_template (template_id),
    key idx_safety_report_creator (created_by),
    key idx_safety_report_reviewer (reviewed_by),
    constraint fk_safety_report_site foreign key (site_id) references site (id),
    constraint fk_safety_report_template foreign key (template_id) references report_template (id),
    constraint fk_safety_report_creator foreign key (created_by) references app_user (id),
    constraint fk_safety_report_reviewer foreign key (reviewed_by) references app_user (id)
) engine=InnoDB default charset=utf8mb4 collate=utf8mb4_0900_ai_ci;

create table if not exists push_channel (
    id bigint not null auto_increment,
    site_id bigint not null,
    name varchar(120) not null,
    type varchar(24) not null,
    endpoint_url varchar(500) null,
    credential_env_name varchar(100) null,
    enabled tinyint(1) not null default 1,
    created_by bigint not null,
    created_at datetime(3) not null,
    updated_at datetime(3) not null,
    primary key (id),
    key idx_push_channel_site_enabled (site_id, enabled, id),
    key idx_push_channel_creator (created_by),
    constraint fk_push_channel_site foreign key (site_id) references site (id),
    constraint fk_push_channel_creator foreign key (created_by) references app_user (id)
) engine=InnoDB default charset=utf8mb4 collate=utf8mb4_0900_ai_ci;

create table if not exists push_delivery (
    id bigint not null auto_increment,
    report_id bigint not null,
    channel_id bigint not null,
    status varchar(24) not null,
    attempt_count int not null default 0,
    http_status int null,
    error_message varchar(500) null,
    created_by bigint not null,
    created_at datetime(3) not null,
    completed_at datetime(3) null,
    primary key (id),
    key idx_push_delivery_report_time (report_id, created_at, id),
    key idx_push_delivery_channel (channel_id),
    key idx_push_delivery_creator (created_by),
    constraint fk_push_delivery_report foreign key (report_id) references safety_report (id),
    constraint fk_push_delivery_channel foreign key (channel_id) references push_channel (id),
    constraint fk_push_delivery_creator foreign key (created_by) references app_user (id)
) engine=InnoDB default charset=utf8mb4 collate=utf8mb4_0900_ai_ci;
