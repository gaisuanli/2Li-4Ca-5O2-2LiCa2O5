package edu.gzhu.sitesafe.service;

import edu.gzhu.sitesafe.common.AppException;
import edu.gzhu.sitesafe.common.PageSpec;
import edu.gzhu.sitesafe.security.SecurityUtil;
import edu.gzhu.sitesafe.security.UserSession;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.simple.SimpleJdbcInsert;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@Service
public class KnowledgeDocumentService {
    private static final Set<String> STATUSES = Set.of(
            "DRAFT", "PENDING_REVIEW", "PUBLISHED", "REJECTED", "ARCHIVED");

    private final JdbcTemplate jdbc;
    private final AuditService audit;

    public KnowledgeDocumentService(JdbcTemplate jdbc, AuditService audit) {
        this.jdbc = jdbc;
        this.audit = audit;
    }

    public Map<String, Object> list(long siteId, String keyword, String requestedStatus,
                                    int page, int pageSize) {
        SecurityUtil.requireSite(siteId);
        StringBuilder where = new StringBuilder(" where k.site_id=? ");
        List<Object> parameters = new ArrayList<>();
        parameters.add(siteId);
        if (keyword != null && !keyword.isBlank()) {
            String term = "%" + keyword.trim().toLowerCase(Locale.ROOT) + "%";
            where.append("and (lower(k.title) like ? or lower(k.category) like ? "
                    + "or lower(coalesce(k.source_reference,'')) like ?) ");
            parameters.add(term);
            parameters.add(term);
            parameters.add(term);
        }
        if (requestedStatus != null && !requestedStatus.isBlank()) {
            String status = normalizeStatus(requestedStatus);
            where.append("and k.status=? ");
            parameters.add(status);
        }
        Long total = jdbc.queryForObject("select count(*) from knowledge_document k" + where,
                Long.class, parameters.toArray());
        PageSpec paging = PageSpec.of(page, pageSize);
        parameters.add(paging.pageSize());
        parameters.add(paging.offset());
        List<Map<String, Object>> items = jdbc.queryForList(
                select() + where + "order by k.updated_at desc,k.id desc limit ? offset ?",
                parameters.toArray());
        return paging.result(items, total == null ? 0L : total);
    }

    @Transactional
    public Map<String, Object> create(long siteId, String title, String category,
                                      String sourceReference, String content) {
        SecurityUtil.requireSite(siteId);
        UserSession user = SecurityUtil.currentUser();
        LocalDateTime now = LocalDateTime.now();
        long id = new SimpleJdbcInsert(jdbc)
                .withTableName("knowledge_document")
                .usingGeneratedKeyColumns("id")
                .usingColumns("site_id", "title", "category", "source_reference", "content", "version",
                        "status", "created_by", "created_at", "updated_at")
                .executeAndReturnKey(new MapSqlParameterSource()
                        .addValue("site_id", siteId)
                        .addValue("title", normalizeRequired(title, "知识标题", 160))
                        .addValue("category", normalizeRequired(category, "知识分类", 80))
                        .addValue("source_reference", normalizeOptional(sourceReference, 500))
                        .addValue("content", normalizeRequired(content, "知识正文", 30000))
                        .addValue("version", 1)
                        .addValue("status", "DRAFT")
                        .addValue("created_by", user.id())
                        .addValue("created_at", Timestamp.valueOf(now))
                        .addValue("updated_at", Timestamp.valueOf(now)))
                .longValue();
        audit.record("KNOWLEDGE_CREATE", "KNOWLEDGE_DOCUMENT", id, "创建知识文档草稿");
        return find(id);
    }

    @Transactional
    public Map<String, Object> update(long id, String title, String category,
                                      String sourceReference, String content) {
        Map<String, Object> current = findForUpdate(id);
        requireStatus(current, Set.of("DRAFT", "REJECTED"), "知识文档当前状态不能编辑");
        jdbc.update("update knowledge_document set title=?,category=?,source_reference=?,content=?,"
                        + "status='DRAFT',reviewed_by=null,review_note=null,reviewed_at=null,updated_at=? where id=?",
                normalizeRequired(title, "知识标题", 160),
                normalizeRequired(category, "知识分类", 80),
                normalizeOptional(sourceReference, 500),
                normalizeRequired(content, "知识正文", 30000),
                Timestamp.valueOf(LocalDateTime.now()), id);
        audit.record("KNOWLEDGE_UPDATE", "KNOWLEDGE_DOCUMENT", id, "更新知识文档草稿");
        return find(id);
    }

    @Transactional
    public Map<String, Object> submit(long id) {
        Map<String, Object> current = findForUpdate(id);
        requireStatus(current, Set.of("DRAFT"), "只有草稿可以提交审核");
        jdbc.update("update knowledge_document set status='PENDING_REVIEW',updated_at=? where id=?",
                Timestamp.valueOf(LocalDateTime.now()), id);
        audit.record("KNOWLEDGE_SUBMIT", "KNOWLEDGE_DOCUMENT", id, "提交知识文档审核");
        return find(id);
    }

    @Transactional
    public Map<String, Object> review(long id, String action, String requestedNote) {
        Map<String, Object> current = findForUpdate(id);
        requireStatus(current, Set.of("PENDING_REVIEW"), "知识文档不在待审核状态");
        String normalizedAction = action == null ? "" : action.trim().toUpperCase(Locale.ROOT);
        if (!Set.of("APPROVE", "REJECT").contains(normalizedAction)) {
            throw new AppException(HttpStatus.BAD_REQUEST, "INVALID_REVIEW_ACTION", "审核操作无效");
        }
        String note = normalizeOptional(requestedNote, 500);
        if ("REJECT".equals(normalizedAction) && note == null) {
            throw new AppException(HttpStatus.BAD_REQUEST, "REVIEW_NOTE_REQUIRED", "驳回时必须填写审核意见");
        }
        String status = "APPROVE".equals(normalizedAction) ? "PUBLISHED" : "REJECTED";
        UserSession reviewer = SecurityUtil.currentUser();
        LocalDateTime now = LocalDateTime.now();
        jdbc.update("update knowledge_document set status=?,"
                        + "reviewed_by=?,review_note=?,reviewed_at=?,updated_at=? where id=?",
                status, reviewer.id(), note, Timestamp.valueOf(now), Timestamp.valueOf(now), id);
        audit.record("KNOWLEDGE_" + normalizedAction, "KNOWLEDGE_DOCUMENT", id,
                status + (note == null ? "" : "；" + note));
        return find(id);
    }

    @Transactional
    public Map<String, Object> archive(long id) {
        Map<String, Object> current = findForUpdate(id);
        requireStatus(current, Set.of("PUBLISHED"), "只有已发布知识可以归档");
        jdbc.update("update knowledge_document set status='ARCHIVED',updated_at=? where id=?",
                Timestamp.valueOf(LocalDateTime.now()), id);
        audit.record("KNOWLEDGE_ARCHIVE", "KNOWLEDGE_DOCUMENT", id, "归档知识文档");
        return find(id);
    }

    public String approvedContext(long siteId, String question) {
        SecurityUtil.requireSite(siteId);
        List<Map<String, Object>> candidates = jdbc.queryForList(
                "select id,title,category,content,source_reference as sourceReference,version "
                        + "from knowledge_document where site_id=? and status='PUBLISHED' "
                        + "order by reviewed_at desc,updated_at desc,id desc limit 40", siteId);
        if (candidates.isEmpty()) return "";
        List<String> terms = searchTerms(question);
        List<ScoredDocument> scored = candidates.stream()
                .map(row -> new ScoredDocument(row, score(row, terms)))
                .filter(item -> item.score() > 0)
                .sorted(Comparator.comparingInt(ScoredDocument::score).reversed()
                        .thenComparing(item -> ((Number) item.row().get("id")).longValue(), Comparator.reverseOrder()))
                .limit(3)
                .toList();
        if (scored.isEmpty()) return "";
        StringBuilder context = new StringBuilder("\n\n经人工审核并已发布的知识库摘录：\n");
        for (ScoredDocument item : scored) {
            Map<String, Object> row = item.row();
            String content = String.valueOf(row.get("content"));
            if (content.length() > 1000) content = content.substring(0, 1000) + "…";
            context.append("- [").append(row.get("category")).append("] ")
                    .append(row.get("title")).append("（版本 ").append(row.get("version")).append("）\n")
                    .append(content).append("\n");
        }
        context.append("上述内容仅来自当前工地已发布文档；草稿、待审、驳回和归档内容未参与检索。");
        return context.toString();
    }

    private Map<String, Object> find(long id) {
        List<Map<String, Object>> rows = jdbc.queryForList(select() + " where k.id=?", id);
        if (rows.isEmpty()) throw notFound();
        Map<String, Object> row = rows.get(0);
        SecurityUtil.requireSite(((Number) row.get("siteId")).longValue());
        return row;
    }

    private Map<String, Object> findForUpdate(long id) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "select id,site_id as siteId,status from knowledge_document where id=? for update", id);
        if (rows.isEmpty()) throw notFound();
        Map<String, Object> row = rows.get(0);
        SecurityUtil.requireSite(((Number) row.get("siteId")).longValue());
        return row;
    }

    private void requireStatus(Map<String, Object> row, Set<String> allowed, String message) {
        if (!allowed.contains(String.valueOf(row.get("status")))) {
            throw new AppException(HttpStatus.CONFLICT, "KNOWLEDGE_STATE_CONFLICT", message);
        }
    }

    private String select() {
        return "select k.id,k.site_id as siteId,k.title,k.category,k.source_reference as sourceReference,"
                + "k.content,k.version,k.status,k.created_by as createdBy,u.display_name as createdByName,"
                + "k.reviewed_by as reviewedBy,r.display_name as reviewedByName,k.review_note as reviewNote,"
                + "k.created_at as createdAt,k.updated_at as updatedAt,k.reviewed_at as reviewedAt "
                + "from knowledge_document k join app_user u on u.id=k.created_by "
                + "left join app_user r on r.id=k.reviewed_by";
    }

    private String normalizeStatus(String value) {
        String status = value.trim().toUpperCase(Locale.ROOT);
        if (!STATUSES.contains(status)) {
            throw new AppException(HttpStatus.BAD_REQUEST, "INVALID_KNOWLEDGE_STATUS", "知识文档状态无效");
        }
        return status;
    }

    private String normalizeRequired(String value, String label, int maximum) {
        String normalized = value == null ? "" : value.trim();
        if (normalized.isEmpty()) {
            throw new AppException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", label + "不能为空");
        }
        if (normalized.length() > maximum) {
            throw new AppException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", label + "不能超过 " + maximum + " 个字符");
        }
        return normalized;
    }

    private String normalizeOptional(String value, int maximum) {
        if (value == null || value.isBlank()) return null;
        String normalized = value.trim();
        if (normalized.length() > maximum) {
            throw new AppException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "字段不能超过 " + maximum + " 个字符");
        }
        return normalized;
    }

    private List<String> searchTerms(String question) {
        if (question == null || question.isBlank()) return List.of();
        String normalized = question.toLowerCase(Locale.ROOT).replaceAll("[\\p{P}\\p{Z}\\s]+", " ").trim();
        LinkedHashSet<String> result = new LinkedHashSet<>();
        for (String term : normalized.split(" ")) if (term.length() >= 2) result.add(term);
        int codePoints = normalized.codePointCount(0, normalized.length());
        for (int i = 0; i + 2 <= codePoints && result.size() < 24; i += 2) {
            int start = normalized.offsetByCodePoints(0, i);
            int end = normalized.offsetByCodePoints(0, Math.min(codePoints, i + 2));
            String term = normalized.substring(start, end).trim();
            if (term.length() >= 2) result.add(term);
        }
        return List.copyOf(result);
    }

    private int score(Map<String, Object> row, List<String> terms) {
        String title = String.valueOf(row.get("title")).toLowerCase(Locale.ROOT);
        String category = String.valueOf(row.get("category")).toLowerCase(Locale.ROOT);
        String content = String.valueOf(row.get("content")).toLowerCase(Locale.ROOT);
        int score = 0;
        for (String term : terms) {
            if (title.contains(term)) score += 6;
            if (category.contains(term)) score += 3;
            if (content.contains(term)) score += 1;
        }
        return score;
    }

    private AppException notFound() {
        return new AppException(HttpStatus.NOT_FOUND, "KNOWLEDGE_NOT_FOUND", "知识文档不存在");
    }

    private record ScoredDocument(Map<String, Object> row, int score) {}
}
