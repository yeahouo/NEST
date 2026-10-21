package com.umi.nest.service;

import com.umi.nest.entity.AuditLog;
import com.umi.nest.entity.User;
import com.umi.nest.mapper.AuditLogMapper;
import com.umi.nest.mapper.UserMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 审计日志服务（差距清单 P0-1）。
 *
 * <p>三条设计原则，写在这里以免后来者「顺手」改坏：
 * <ol>
 *   <li><b>审计绝不阻断业务</b>：所有写入都包在 try/catch 里，失败只打日志。
 *       审计表写不进去不应该让教师交不上作业。</li>
 *   <li><b>不落敏感原文</b>：查询串里 password/code/token 之类的值一律写 {@code ***}，
 *       请求体一概不记录（登录接口的请求体就是密码）。</li>
 *   <li><b>只审计写操作</b>：读操作默认不记（见 {@code AuditLogInterceptor}），
 *       否则一张表很快被 GET 请求撑爆，反而查不出真正要紧的事。</li>
 * </ol>
 */
@Service
public class AuditLogService {

    private static final Logger log = LoggerFactory.getLogger(AuditLogService.class);

    /** 事件类型（与 sql/nest_audit.sql 的列注释一致）。 */
    public static final String TYPE_LOGIN = "LOGIN";
    public static final String TYPE_LOGOUT = "LOGOUT";
    public static final String TYPE_REGISTER = "REGISTER";
    public static final String TYPE_PASSWORD_RESET = "PASSWORD_RESET";
    public static final String TYPE_PASSWORD_CHANGE = "PASSWORD_CHANGE";
    public static final String TYPE_ADMIN = "ADMIN";
    public static final String TYPE_DORM = "DORM";
    public static final String TYPE_ANNOUNCEMENT = "ANNOUNCEMENT";
    public static final String TYPE_USER = "USER";
    /** 学工平台：请假 / 离校 / 外出 */
    public static final String TYPE_LEAVE = "LEAVE";

    /** 学生档案（学工 P3）：/api/student/profile、/api/counselor/** */
    public static final String TYPE_PROFILE = "PROFILE";
    /** 学生档案之外的学工业务（P5）：奖励与处分 /api/award-punishment、资助 /api/aid */
    public static final String TYPE_AWARD = "AWARD";
    public static final String TYPE_AID = "AID";

    /** 综合素质测评（P6）：/api/eval/** */
    public static final String TYPE_EVAL = "EVAL";

    /** 勤工助学（P10）：/api/workstudy/** */
    public static final String TYPE_WORKSTUDY = "WORKSTUDY";

    /** 评奖评优申报（P12）：/api/honor/** */
    public static final String TYPE_HONOR = "HONOR";

    /** 综测排名公示与导出（P13）：/api/rank/** */
    public static final String TYPE_RANK = "RANK";

    /** 心理健康（P14）：/api/psyche/**（量表 / 普查 / 关注台账 / 预约咨询 / 转介） */
    public static final String TYPE_PSYCHE = "PSYCHE";

    /** 社区管理（P15）：/api/community/**（检查批次 / 宿舍检查 / 整改 / 晚归 / 违规 / 文明宿舍 / 活动） */
    public static final String TYPE_COMMUNITY = "COMMUNITY";

    /** 学生党建（P16）：/api/party/**（入党申请 / 阶段台账 / 考察与思想汇报 / 党校培训 / 公示 / 材料） */
    public static final String TYPE_PARTY = "PARTY";

    /** 志愿服务（P17）：/api/volunteer/**（项目 / 报名 / 时长 / 黑名单 / 申诉） */
    public static final String TYPE_VOLUNTEER = "VOLUNTEER";

    /** 学生社团（P17）：/api/club/**（社团 / 成员 / 年审 / 星级 / 公示） */
    public static final String TYPE_CLUB = "CLUB";

    /** 第二课堂成绩单（P18）：/api/second-class/**（板块 / 活动 / 申报 / 初审 / 认定 / 汇总导出） */
    public static final String TYPE_SECOND_CLASS = "SECOND_CLASS";

    public static final String TYPE_API = "API";

    /** 查询串里的敏感字段（命中即脱敏）。 */
    private static final List<String> SENSITIVE = List.of(
            "password", "passwd", "pwd", "code", "token", "secret", "pepper", "apikey", "api_key", "signature");

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private static final int MAX_DETAIL = 500;
    private static final int MAX_TARGET = 255;
    private static final int MAX_UA = 255;
    private static final int MAX_IP = 64;

    private final AuditLogMapper auditLogMapper;
    private final UserMapper userMapper;

    /** userId → username 的小缓存：审计写入发生在每个写请求上，不该每次多查一次用户表。 */
    private final Map<Long, String> usernameCache = new ConcurrentHashMap<>();

    public AuditLogService(AuditLogMapper auditLogMapper, UserMapper userMapper) {
        this.auditLogMapper = auditLogMapper;
        this.userMapper = userMapper;
    }

    /** 记录一条审计事件；任何异常都只打日志，不向上抛。 */
    public void record(AuditLog entry) {
        if (entry == null) {
            return;
        }
        try {
            if (entry.getEventTime() == null) {
                entry.setEventTime(LocalDateTime.now());
            }
            if (entry.getEventType() == null) {
                entry.setEventType(TYPE_API);
            }
            if (entry.getEventAction() == null) {
                entry.setEventAction("UNKNOWN");
            }
            if (entry.getSuccess() == null) {
                entry.setSuccess(Boolean.TRUE);
            }
            if (entry.getUsername() == null && entry.getUserId() != null) {
                entry.setUsername(resolveUsername(entry.getUserId()));
            }
            if (entry.getIp() == null) {
                entry.setIp(AuditContext.ip());
            }
            if (entry.getUserAgent() == null) {
                entry.setUserAgent(AuditContext.userAgent());
            }
            entry.setTarget(truncate(entry.getTarget(), MAX_TARGET));
            entry.setDetail(truncate(entry.getDetail(), MAX_DETAIL));
            entry.setUserAgent(truncate(entry.getUserAgent(), MAX_UA));
            entry.setIp(truncate(entry.getIp(), MAX_IP));
            auditLogMapper.insert(entry);
        } catch (Exception e) {
            log.warn("审计日志写入失败（已忽略，不影响业务）：type={} target={} err={}",
                    entry.getEventType(), entry.getTarget(), e.toString());
        }
    }

    /**
     * 登录/注册/重置密码这类「HTTP 200 但业务失败」的事件必须由服务层记录，
     * 因为只有服务层知道真实的成功与否。
     */
    public void recordAuth(String eventType, String action, String username, Long userId,
                           boolean success, String detail) {
        AuditLog e = new AuditLog();
        e.setEventType(eventType);
        e.setEventAction(action);
        e.setUsername(username);
        e.setUserId(userId);
        e.setSuccess(success);
        e.setDetail(detail);
        record(e);
    }

    private String resolveUsername(Long userId) {
        String cached = usernameCache.get(userId);
        if (cached != null) {
            return cached;
        }
        try {
            User u = userMapper.findById(userId);
            if (u != null && u.getUsername() != null) {
                usernameCache.put(userId, u.getUsername());
                return u.getUsername();
            }
        } catch (Exception ignored) {
            // 查询失败就不写用户名，审计不因此失败
        }
        return null;
    }

    /** 管理端分页查询。日期用 {@code yyyy-MM-dd}，to 含当天 23:59:59。 */
    public Map<String, Object> query(String eventType, String username, Boolean success,
                                     String from, String to, int page, int size) {
        int safeSize = Math.max(1, Math.min(size, 200));
        int safePage = Math.max(1, page);
        LocalDateTime f = parseFrom(from);
        LocalDateTime t = parseTo(to);
        long total = auditLogMapper.count(blankToNull(eventType), blankToNull(username), success, f, t);
        List<AuditLog> items = auditLogMapper.query(blankToNull(eventType), blankToNull(username), success,
                f, t, (safePage - 1) * safeSize, safeSize);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("total", total);
        data.put("page", safePage);
        data.put("size", safeSize);
        data.put("items", items);
        return data;
    }

    /** 按事件类型汇总（管理端概览）。 */
    public List<Map<String, Object>> summary(int days) {
        int d = Math.max(1, Math.min(days, 365));
        return auditLogMapper.countByType(LocalDateTime.now().minusDays(d), null);
    }

    /** 导出 CSV（不含 BOM；由控制层补 BOM 以便 Excel 正确识别 UTF-8）。 */
    public String toCsv(List<AuditLog> rows) {
        StringBuilder sb = new StringBuilder();
        sb.append("id,event_time,event_type,event_action,user_id,username,role,target,http_method,ip,"
                + "user_agent,success,status_code,duration_ms,detail\r\n");
        for (AuditLog r : rows) {
            List<Object> cells = new ArrayList<>();
            cells.add(r.getId());
            cells.add(r.getEventTime() == null ? null : r.getEventTime().format(STAMP));
            cells.add(r.getEventType());
            cells.add(r.getEventAction());
            cells.add(r.getUserId());
            cells.add(r.getUsername());
            cells.add(r.getRole());
            cells.add(r.getTarget());
            cells.add(r.getHttpMethod());
            cells.add(r.getIp());
            cells.add(r.getUserAgent());
            cells.add(Boolean.TRUE.equals(r.getSuccess()) ? "成功" : "失败");
            cells.add(r.getStatusCode());
            cells.add(r.getDurationMs());
            cells.add(r.getDetail());
            for (int i = 0; i < cells.size(); i++) {
                if (i > 0) {
                    sb.append(',');
                }
                sb.append(csvCell(cells.get(i)));
            }
            sb.append("\r\n");
        }
        return sb.toString();
    }

    /** 导出用：按同一套筛选条件取最多 50,000 行。 */
    public List<AuditLog> exportRows(String eventType, String username, Boolean success,
                                     String from, String to) {
        return auditLogMapper.query(blankToNull(eventType), blankToNull(username), success,
                parseFrom(from), parseTo(to), 0, 50000);
    }

    /** 留存期清理：删除 N 天前的记录，返回删除行数。 */
    public int purgeBeforeDays(int days) {
        int d = Math.max(1, days);
        int removed = auditLogMapper.deleteBefore(LocalDateTime.now().minusDays(d));
        AuditLog e = new AuditLog();
        e.setEventType(TYPE_ADMIN);
        e.setEventAction("PURGE");
        e.setSuccess(true);
        e.setDetail("清理 " + d + " 天前的审计记录，删除 " + removed + " 行");
        record(e);
        return removed;
    }

    /** 查询串脱敏：命中的敏感字段值换成 ***；请求体一概不记录。 */
    public static String maskQuery(String queryString) {
        if (queryString == null || queryString.isEmpty()) {
            return null;
        }
        StringBuilder out = new StringBuilder();
        for (String kv : queryString.split("&")) {
            if (out.length() > 0) {
                out.append('&');
            }
            int eq = kv.indexOf('=');
            String key = eq < 0 ? kv : kv.substring(0, eq);
            boolean sensitive = SENSITIVE.stream()
                    .anyMatch(s -> key.toLowerCase(Locale.ROOT).contains(s));
            out.append(sensitive && eq >= 0 ? key + "=***" : kv);
            if (out.length() > 300) {
                out.setLength(300);
                out.append("…");
                break;
            }
        }
        return out.toString();
    }

    /** 请求路径 → 事件类型。 */
    public static String typeOfPath(String path) {
        if (path == null) {
            return TYPE_API;
        }
        if (path.startsWith("/api/admin")) {
            return TYPE_ADMIN;
        }
        if (path.startsWith("/api/dorm")) {
            return TYPE_DORM;
        }
        if (path.startsWith("/api/leave")) {
            return TYPE_LEAVE;
        }
        if (path.startsWith("/api/student/profile") || path.startsWith("/api/counselor")) {
            return TYPE_PROFILE;
        }
        if (path.startsWith("/api/award-punishment")) {
            return TYPE_AWARD;
        }
        if (path.startsWith("/api/aid")) {
            return TYPE_AID;
        }
        if (path.startsWith("/api/eval")) {
            return TYPE_EVAL;
        }
        if (path.startsWith("/api/workstudy")) {
            return TYPE_WORKSTUDY;
        }
        if (path.startsWith("/api/honor")) {
            return TYPE_HONOR;
        }
        if (path.startsWith("/api/rank")) {
            return TYPE_RANK;
        }
        if (path.startsWith("/api/psyche")) {
            return TYPE_PSYCHE;
        }
        if (path.startsWith("/api/community")) {
            return TYPE_COMMUNITY;
        }
        if (path.startsWith("/api/party")) {
            return TYPE_PARTY;
        }
        if (path.startsWith("/api/volunteer")) {
            return TYPE_VOLUNTEER;
        }
        if (path.startsWith("/api/club")) {
            return TYPE_CLUB;
        }
        if (path.startsWith("/api/second-class")) {
            return TYPE_SECOND_CLASS;
        }
        if (path.startsWith("/api/announcement")) {
            return TYPE_ANNOUNCEMENT;
        }
        if (path.startsWith("/api/user")) {
            return TYPE_USER;
        }
        return TYPE_API;
    }

    private static String csvCell(Object v) {
        if (v == null) {
            return "";
        }
        String s = String.valueOf(v);
        if (s.indexOf(',') >= 0 || s.indexOf('"') >= 0 || s.indexOf('\n') >= 0 || s.indexOf('\r') >= 0) {
            return '"' + s.replace("\"", "\"\"") + '"';
        }
        return s;
    }

    private static String truncate(String s, int max) {
        if (s == null) {
            return null;
        }
        return s.length() <= max ? s : s.substring(0, max);
    }

    private static String blankToNull(String s) {
        return s == null || s.trim().isEmpty() ? null : s.trim();
    }

    private static LocalDateTime parseFrom(String from) {
        if (blankToNull(from) == null) {
            return null;
        }
        try {
            return LocalDate.parse(from.trim(), DATE).atStartOfDay();
        } catch (Exception e) {
            return null;
        }
    }

    private static LocalDateTime parseTo(String to) {
        if (blankToNull(to) == null) {
            return null;
        }
        try {
            return LocalDate.parse(to.trim(), DATE).atTime(23, 59, 59);
        } catch (Exception e) {
            return null;
        }
    }
}
