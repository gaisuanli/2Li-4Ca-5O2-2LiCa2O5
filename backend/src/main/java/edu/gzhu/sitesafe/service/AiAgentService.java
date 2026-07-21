package edu.gzhu.sitesafe.service;

import edu.gzhu.sitesafe.common.AppException;
import edu.gzhu.sitesafe.common.PageSpec;
import edu.gzhu.sitesafe.config.AiAgentProperties;
import edu.gzhu.sitesafe.security.SecurityUtil;
import edu.gzhu.sitesafe.security.UserSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.simple.SimpleJdbcInsert;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class AiAgentService {
    private static final Logger log = LoggerFactory.getLogger(AiAgentService.class);
    private static final String DEFAULT_TITLE = "新对话";

    private final JdbcTemplate jdbc;
    private final AiAgentProperties properties;
    private final AiAgentProvider provider;
    private final AiAgentSiteSnapshotService snapshots;
    private final AuditService audit;
    private final TransactionTemplate transactions;
    private final AiAgentConversationLocks conversationLocks;
    private final KnowledgeDocumentService knowledgeDocuments;
    private final AiAgentUserProviderConfigService userProviderConfigs;

    public AiAgentService(JdbcTemplate jdbc,
                          AiAgentProperties properties,
                          AiAgentProvider provider,
                          AiAgentSiteSnapshotService snapshots,
                          AuditService audit,
                          PlatformTransactionManager transactionManager,
                          AiAgentConversationLocks conversationLocks,
                          KnowledgeDocumentService knowledgeDocuments,
                          AiAgentUserProviderConfigService userProviderConfigs) {
        this.jdbc = jdbc;
        this.properties = properties;
        this.provider = provider;
        this.snapshots = snapshots;
        this.audit = audit;
        this.transactions = new TransactionTemplate(transactionManager);
        this.conversationLocks = conversationLocks;
        this.knowledgeDocuments = knowledgeDocuments;
        this.userProviderConfigs = userProviderConfigs;
    }

    public Map<String, Object> config() {
        AiAgentProvider.ProviderStatus status = userProviderConfigs.effectiveStatus(
                SecurityUtil.currentUser().id());
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("mode", status.mode().name());
        result.put("model", status.model());
        result.put("available", status.available());
        result.put("maxContentChars", properties.effectiveMaxContentChars());
        return result;
    }

    public Map<String, Object> conversations(long siteId, int page, int pageSize) {
        SecurityUtil.requireSite(siteId);
        UserSession user = SecurityUtil.currentUser();
        PageSpec paging = PageSpec.of(page, pageSize);
        Long total = jdbc.queryForObject(
                "select count(*) from ai_agent_conversation where user_id=? and site_id=?",
                Long.class, user.id(), siteId);
        List<Map<String, Object>> items = jdbc.queryForList(
                conversationSelect()
                        + " where c.user_id=? and c.site_id=? order by c.updated_at desc,c.id desc limit ? offset ?",
                user.id(), siteId, paging.pageSize(), paging.offset());
        return paging.result(items, total == null ? 0L : total);
    }

    @Transactional
    public Map<String, Object> create(long siteId, String requestedTitle) {
        SecurityUtil.requireSite(siteId);
        Long siteCount = jdbc.queryForObject("select count(*) from site where id=?", Long.class, siteId);
        if (siteCount == null || siteCount == 0) {
            throw new AppException(HttpStatus.NOT_FOUND, "SITE_NOT_FOUND", "工地不存在");
        }
        String title = normalizeTitle(requestedTitle);
        UserSession user = SecurityUtil.currentUser();
        LocalDateTime now = LocalDateTime.now();
        long id = new SimpleJdbcInsert(jdbc)
                .withTableName("ai_agent_conversation")
                .usingGeneratedKeyColumns("id")
                .usingColumns("user_id", "site_id", "title", "created_at", "updated_at")
                .executeAndReturnKey(new MapSqlParameterSource()
                        .addValue("user_id", user.id())
                        .addValue("site_id", siteId)
                        .addValue("title", title)
                        .addValue("created_at", Timestamp.valueOf(now))
                        .addValue("updated_at", Timestamp.valueOf(now)))
                .longValue();
        audit.record("AI_AGENT_CONVERSATION_CREATE", "AI_AGENT_CONVERSATION", id,
                "创建 AI Agent 会话");
        return findOwnedConversation(id);
    }

    public Map<String, Object> messages(long conversationId, int page, int pageSize) {
        findOwnedConversation(conversationId);
        PageSpec paging = PageSpec.of(page, pageSize);
        Long total = jdbc.queryForObject("select count(*) from ai_agent_message where conversation_id=?",
                Long.class, conversationId);
        List<Map<String, Object>> newestFirst = jdbc.queryForList(
                messageSelect() + " where conversation_id=? order by created_at desc,id desc limit ? offset ?",
                conversationId, paging.pageSize(), paging.offset());
        List<Map<String, Object>> chronological = new ArrayList<>(newestFirst);
        Collections.reverse(chronological);
        return paging.result(chronological, total == null ? 0L : total);
    }

    public Map<String, Object> send(long conversationId, String requestedContent) {
        String content = normalizeContent(requestedContent);
        // Reject inaccessible ids before they can queue on a shared stripe.
        findOwnedConversation(conversationId);
        return conversationLocks.ordered(conversationId, () -> sendOrdered(conversationId, content));
    }

    private Map<String, Object> sendOrdered(long conversationId, String content) {
        Map<String, Object> conversation = findOwnedConversation(conversationId);
        long siteId = ((Number) conversation.get("siteId")).longValue();
        AiAgentSiteSnapshotService.SiteSnapshot snapshot = snapshots.capture(siteId);
        String siteContext = snapshots.context(snapshot) + knowledgeDocuments.approvedContext(siteId, content);
        List<AiAgentProvider.HistoryMessage> history = history(conversationId);
        AiAgentProvider.ProviderReply reply;
        try {
            long userId = SecurityUtil.currentUser().id();
            reply = provider.answer(
                    userId, content, siteContext, history, snapshot, snapshots,
                    userProviderConfigs.resolveForRequest(userId));
        } catch (AppException ex) {
            if ("AI_AGENT_PROVIDER_ERROR".equals(ex.code())
                    || "AI_AGENT_PROVIDER_TIMEOUT".equals(ex.code())) {
                // These failures have already passed admission control, so the
                // audit cannot be amplified by rejected/busy request floods.
                recordFailedAttempt(conversationId, ex.code());
            }
            throw ex;
        }

        return transactions.execute(status -> persistExchange(conversationId, content, reply));
    }

    private void recordFailedAttempt(long conversationId, String errorCode) {
        try {
            audit.record("AI_AGENT_MESSAGE_FAILED", "AI_AGENT_CONVERSATION", conversationId,
                    "AI Agent 问答失败；code=" + errorCode);
        } catch (RuntimeException auditError) {
            log.warn("Could not persist sanitized AI Agent failure audit ({})",
                    auditError.getClass().getSimpleName());
        }
    }

    private Map<String, Object> persistExchange(long conversationId, String content,
                                                AiAgentProvider.ProviderReply reply) {
        Map<String, Object> conversation = findOwnedConversationForUpdate(conversationId);
        Long priorCount = jdbc.queryForObject(
                "select count(*) from ai_agent_message where conversation_id=?", Long.class, conversationId);
        LocalDateTime now = LocalDateTime.now();
        long userMessageId = insertMessage(conversationId, "USER", content, null, null, now);
        long assistantMessageId = insertMessage(conversationId, "ASSISTANT", reply.content(),
                reply.mode(), reply.model(), now.plusNanos(1_000_000));

        String currentTitle = String.valueOf(conversation.get("title"));
        boolean firstExchange = priorCount == null || priorCount == 0;
        String nextTitle = firstExchange && DEFAULT_TITLE.equals(currentTitle)
                ? titleFromQuestion(content) : currentTitle;
        jdbc.update("update ai_agent_conversation set title=?,updated_at=? where id=?",
                nextTitle, Timestamp.valueOf(now.plusNanos(1_000_000)), conversationId);
        audit.record("AI_AGENT_MESSAGE_SEND", "AI_AGENT_CONVERSATION", conversationId,
                "发送 AI Agent 问答；mode=" + reply.mode() + "；model=" + reply.model());

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("conversation", findOwnedConversation(conversationId));
        result.put("userMessage", findMessage(userMessageId, conversationId));
        result.put("assistantMessage", findMessage(assistantMessageId, conversationId));
        return result;
    }

    private long insertMessage(long conversationId, String role, String content,
                               String mode, String model, LocalDateTime createdAt) {
        return new SimpleJdbcInsert(jdbc)
                .withTableName("ai_agent_message")
                .usingGeneratedKeyColumns("id")
                .usingColumns("conversation_id", "role", "content", "provider_mode", "provider_model", "created_at")
                .executeAndReturnKey(new MapSqlParameterSource()
                        .addValue("conversation_id", conversationId)
                        .addValue("role", role)
                        .addValue("content", content)
                        .addValue("provider_mode", mode)
                        .addValue("provider_model", model)
                        .addValue("created_at", Timestamp.valueOf(createdAt)))
                .longValue();
    }

    private List<AiAgentProvider.HistoryMessage> history(long conversationId) {
        int maximum = properties.effectiveMaxHistoryMessages();
        if (maximum == 0) return List.of();
        List<AiAgentProvider.HistoryMessage> newestFirst = jdbc.query(
                "select role,content from ai_agent_message where conversation_id=? "
                        + "order by created_at desc,id desc limit ?",
                (rs, rowNum) -> new AiAgentProvider.HistoryMessage(rs.getString("role"), rs.getString("content")),
                conversationId, maximum);
        Collections.reverse(newestFirst);
        return newestFirst;
    }

    private Map<String, Object> findOwnedConversation(long id) {
        UserSession user = SecurityUtil.currentUser();
        List<Map<String, Object>> rows = jdbc.queryForList(
                conversationSelect() + " where c.id=? and c.user_id=?", id, user.id());
        if (rows.isEmpty()) {
            throw new AppException(HttpStatus.NOT_FOUND, "AI_CONVERSATION_NOT_FOUND", "AI Agent 会话不存在");
        }
        Map<String, Object> conversation = rows.get(0);
        SecurityUtil.requireSite(((Number) conversation.get("siteId")).longValue());
        return conversation;
    }

    private Map<String, Object> findOwnedConversationForUpdate(long id) {
        UserSession user = SecurityUtil.currentUser();
        List<Map<String, Object>> rows = jdbc.queryForList(
                "select id,site_id as siteId,title from ai_agent_conversation where id=? and user_id=? for update",
                id, user.id());
        if (rows.isEmpty()) {
            throw new AppException(HttpStatus.NOT_FOUND, "AI_CONVERSATION_NOT_FOUND", "AI Agent 会话不存在");
        }
        Map<String, Object> conversation = rows.get(0);
        SecurityUtil.requireSite(((Number) conversation.get("siteId")).longValue());
        return conversation;
    }

    private Map<String, Object> findMessage(long id, long conversationId) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                messageSelect() + " where id=? and conversation_id=?", id, conversationId);
        if (rows.isEmpty()) throw new IllegalStateException("Persisted AI Agent message is missing");
        return rows.get(0);
    }

    private String conversationSelect() {
        return "select c.id,c.site_id as siteId,c.title,c.created_at as createdAt,c.updated_at as updatedAt,"
                + "(select count(*) from ai_agent_message mc where mc.conversation_id=c.id) as messageCount,"
                + "(select substring(ml.content,1,160) from ai_agent_message ml where ml.conversation_id=c.id "
                + "order by ml.created_at desc,ml.id desc limit 1) as lastMessagePreview "
                + "from ai_agent_conversation c";
    }

    private String messageSelect() {
        return "select id,conversation_id as conversationId,role,content,provider_mode as mode,"
                + "provider_model as model,created_at as createdAt from ai_agent_message";
    }

    private String normalizeTitle(String value) {
        if (value == null || value.isBlank()) return DEFAULT_TITLE;
        String normalized = value.trim().replaceAll("\\s+", " ");
        if (normalized.length() > 80) {
            throw new AppException(HttpStatus.BAD_REQUEST, "INVALID_AGENT_TITLE", "会话标题不能超过 80 个字符");
        }
        return normalized;
    }

    private String normalizeContent(String value) {
        if (value == null || value.isBlank()) {
            throw new AppException(HttpStatus.BAD_REQUEST, "INVALID_AGENT_CONTENT", "问题内容不能为空");
        }
        String normalized = value.trim();
        if (normalized.length() > properties.effectiveMaxContentChars()) {
            throw new AppException(HttpStatus.BAD_REQUEST, "INVALID_AGENT_CONTENT",
                    "问题内容不能超过 " + properties.effectiveMaxContentChars() + " 个字符");
        }
        return normalized;
    }

    private String titleFromQuestion(String question) {
        String normalized = question.replaceAll("\\s+", " ").trim();
        int codePoints = normalized.codePointCount(0, normalized.length());
        if (codePoints <= 32) return normalized;
        int end = normalized.offsetByCodePoints(0, 32);
        return normalized.substring(0, end) + "…";
    }
}
