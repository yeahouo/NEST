package com.umi.nest.controller;

import com.umi.nest.common.Result;
import com.umi.nest.service.AidReviewService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import jakarta.servlet.http.HttpSession;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 家庭经济困难学生认定（P11）接口。
 *
 * <p>统一约定：HTTP 一律 200；返回体 {@code {code,message,data}}；未登录 code=401；
 * 越权 code=403；参数/状态非法 code=400 且给中文文案。身份取自 session 的 userId / role。</p>
 *
 * <p>学生端：/api/aid/poverty/**、/api/aid/objection、/api/aid/publicity<br>
 * 管理端：/api/aid/review/**（teacher=辅导员限本人名下学生，admin 全校）</p>
 *
 * <p>审计：/api/aid 前缀已由 {@code AuditLogService.typeOfPath} 归入 TYPE_AID，本控制器无需改动审计。</p>
 */
@RestController
@RequestMapping("/api/aid")
public class AidReviewController {

    private final AidReviewService service;

    public AidReviewController(AidReviewService service) {
        this.service = service;
    }

    // =====================================================================
    // 学生端
    // =====================================================================

    /** 1 POST /api/aid/poverty/apply —— 仅 student；同一学年重复提交 400。 */
    @PostMapping("/poverty/apply")
    public Result apply(@RequestBody(required = false) Map<String, Object> body, HttpSession session) {
        Long uid = userId(session);
        if (uid == null) {
            return Result.error(401, "未登录");
        }
        return service.apply(uid, role(session), str(body, "certYear"), str(body, "reason"),
                strList(body, "materials"), str(body, "familyNote"));
    }

    /** 2 GET /api/aid/poverty/mine —— 本人认定申请。 */
    @GetMapping("/poverty/mine")
    public Result mine(HttpSession session) {
        Long uid = userId(session);
        if (uid == null) {
            return Result.error(401, "未登录");
        }
        return service.mine(uid, role(session));
    }

    /** 3 GET /api/aid/poverty/stages —— 七步阶段定义（顺序 + 中文名）。 */
    @GetMapping("/poverty/stages")
    public Result stages() {
        return service.stages();
    }

    /** 4 POST /api/aid/objection —— 仅 student、只能对自己、只在公示期内。 */
    @PostMapping("/objection")
    public Result objection(@RequestBody(required = false) Map<String, Object> body, HttpSession session) {
        Long uid = userId(session);
        if (uid == null) {
            return Result.error(401, "未登录");
        }
        return service.submitObjection(uid, role(session), num(body, "applicationId"),
                str(body, "content"), str(body, "evidence"));
    }

    /** 5 GET /api/aid/objection/mine —— 本人复议记录。 */
    @GetMapping("/objection/mine")
    public Result myObjections(HttpSession session) {
        Long uid = userId(session);
        if (uid == null) {
            return Result.error(401, "未登录");
        }
        return service.myObjections(uid, role(session));
    }

    /**
     * 6 GET /api/aid/publicity —— 公示列表，只露 name/gender/level。
     *
     * <p>要求登录（最初写成匿名可读是错的）：这张表列的是**真实姓名 + 性别 + 认定档次**，
     * 匿名开放等于把全校困难生名单挂到内网任意一台机器上。登录即可读——学生本来就要看公示才能提复议。</p>
     */
    @GetMapping("/publicity")
    public Result publicity(@RequestParam(required = false) String certYear, HttpSession session) {
        if (userId(session) == null) {
            return Result.error(401, "未登录");
        }
        return service.publicity(certYear);
    }

    // =====================================================================
    // 管理端
    // =====================================================================

    /** 7 GET /api/aid/review/list —— 认定申请列表。 */
    @GetMapping("/review/list")
    public Result list(@RequestParam(required = false) String stage,
                       @RequestParam(required = false) String certYear,
                       @RequestParam(required = false) String keyword,
                       @RequestParam(required = false) Integer page,
                       @RequestParam(required = false) Integer size,
                       HttpSession session) {
        Long uid = userId(session);
        if (uid == null) {
            return Result.error(401, "未登录");
        }
        return service.list(uid, role(session), scope(session), stage, certYear, keyword, page, size);
    }

    /** 8 GET /api/aid/review/detail/{id} —— 管理端详情（含证据链）。 */
    @GetMapping("/review/detail/{id}")
    public Result detail(@PathVariable Long id, HttpSession session) {
        Long uid = userId(session);
        if (uid == null) {
            return Result.error(401, "未登录");
        }
        return service.detail(uid, role(session), scope(session), id);
    }

    /** 9 POST /api/aid/review/committee —— 民主评议（committee_note + 结论）。 */
    @PostMapping("/review/committee")
    public Result committee(@RequestBody(required = false) Map<String, Object> body, HttpSession session) {
        Long uid = userId(session);
        if (uid == null) {
            return Result.error(401, "未登录");
        }
        return service.committee(uid, role(session), scope(session), num(body, "applicationId"),
                str(body, "committeeNote"), str(body, "conclusion"));
    }

    /** 10 POST /api/aid/review/level —— 院系工作组审核定级（可勾选十类不能认定）。 */
    @PostMapping("/review/level")
    public Result level(@RequestBody(required = false) Map<String, Object> body, HttpSession session) {
        Long uid = userId(session);
        if (uid == null) {
            return Result.error(401, "未登录");
        }
        return service.level(uid, role(session), scope(session), num(body, "applicationId"),
                str(body, "level"), strList(body, "disqualifyReasons"), str(body, "note"));
    }

    /**
     * 11 POST /api/aid/review/publicity —— 发布公示（默认 5 个工作日）。
     *
     * <p>可选 publicityStart / publicityEnd（仅 admin）：纸质公示先发、系统后补录/回溯。</p>
     */
    @PostMapping("/review/publicity")
    public Result publicity(@RequestBody(required = false) Map<String, Object> body, HttpSession session) {
        Long uid = userId(session);
        if (uid == null) {
            return Result.error(401, "未登录");
        }
        return service.publishPublicity(uid, role(session), scope(session), num(body, "applicationId"),
                str(body, "publicityStart"), str(body, "publicityEnd"));
    }

    /** 12 GET /api/aid/review/objections —— 复议列表。 */
    @GetMapping("/review/objections")
    public Result objections(@RequestParam(required = false) String status, HttpSession session) {
        Long uid = userId(session);
        if (uid == null) {
            return Result.error(401, "未登录");
        }
        return service.objections(uid, role(session), scope(session), status);
    }

    /** 13 POST /api/aid/review/objections/{id}/answer —— 答复复议。 */
    @PostMapping("/review/objections/{id}/answer")
    public Result answerObjection(@PathVariable Long id,
                                  @RequestBody(required = false) Map<String, Object> body,
                                  HttpSession session) {
        Long uid = userId(session);
        if (uid == null) {
            return Result.error(401, "未登录");
        }
        return service.answerObjection(uid, role(session), scope(session), id,
                str(body, "answer"), str(body, "verdict"), str(body, "newLevel"));
    }

    /** 14 POST /api/aid/review/report —— 院系书面评审报告。 */
    @PostMapping("/review/report")
    public Result report(@RequestBody(required = false) Map<String, Object> body, HttpSession session) {
        Long uid = userId(session);
        if (uid == null) {
            return Result.error(401, "未登录");
        }
        return service.report(uid, role(session), scope(session), num(body, "applicationId"),
                str(body, "reportNote"));
    }

    /** 15 POST /api/aid/review/approve —— 资助中心审核 / 领导小组审批。 */
    @PostMapping("/review/approve")
    public Result approve(@RequestBody(required = false) Map<String, Object> body, HttpSession session) {
        Long uid = userId(session);
        if (uid == null) {
            return Result.error(401, "未登录");
        }
        return service.approve(uid, role(session), scope(session), num(body, "applicationId"),
                str(body, "role"), bool(body, "approved"), str(body, "note"),
                strList(body, "disqualifyReasons"));
    }

    /** 16 GET /api/aid/review/recheck —— 学期复查名单。 */
    @GetMapping("/review/recheck")
    public Result recheckList(@RequestParam(required = false) String certYear, HttpSession session) {
        Long uid = userId(session);
        if (uid == null) {
            return Result.error(401, "未登录");
        }
        return service.recheckList(uid, role(session), scope(session), certYear);
    }

    /** 17 POST /api/aid/review/recheck/{id} —— 每学期复查一次 + 随机抽查。 */
    @PostMapping("/review/recheck/{id}")
    public Result recheck(@PathVariable Long id,
                          @RequestBody(required = false) Map<String, Object> body,
                          HttpSession session) {
        Long uid = userId(session);
        if (uid == null) {
            return Result.error(401, "未登录");
        }
        return service.recheck(uid, role(session), scope(session), id,
                str(body, "result"), bool(body, "adjusted"), str(body, "newLevel"), str(body, "note"));
    }

    /**
     * 18 GET /api/aid/review/disqualify-reasons —— 十类不能认定情形。
     *
     * <p>管理端参考表：只在定级弹窗里被辅导员/管理员使用（前端另有内置兜底），
     * 因此要求登录且限 teacher/admin；学生页不读它，给它匿名读的口子没有收益。</p>
     */
    @GetMapping("/review/disqualify-reasons")
    public Result disqualifyReasons(HttpSession session) {
        Long uid = userId(session);
        if (uid == null) {
            return Result.error(401, "未登录");
        }
        String r = role(session);
        if (!"teacher".equals(r) && !"admin".equals(r)) {
            return Result.error(403, "无权限查看不能认定情形");
        }
        return service.disqualifyReasons();
    }

    /** 19 GET /api/aid/review/stats —— 管理端 6 格统计（applied/committee/level/publicity/objection/final）。 */
    @GetMapping("/review/stats")
    public Result stats(@RequestParam(required = false) String certYear, HttpSession session) {
        Long uid = userId(session);
        if (uid == null) {
            return Result.error(401, "未登录");
        }
        return service.stats(uid, role(session), scope(session), certYear);
    }

    // =====================================================================
    // 请求解析（不用 DTO，避免与 P5 的实体/参数类纠缠）
    // =====================================================================

    private static Long userId(HttpSession session) {
        if (session == null) {
            return null;
        }
        Object id = session.getAttribute("userId");
        if (id == null) {
            return null;
        }
        if (id instanceof Number) {
            return ((Number) id).longValue();
        }
        try {
            return Long.valueOf(String.valueOf(id));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String role(HttpSession session) {
        if (session == null) {
            return null;
        }
        Object r = session.getAttribute("role");
        return r == null ? null : String.valueOf(r);
    }

    /** 数据范围（AidService 同款口径：session 里可能带 scope=all）。 */
    private static String scope(HttpSession session) {
        if (session == null) {
            return null;
        }
        Object s = session.getAttribute("scope");
        return s == null ? null : String.valueOf(s);
    }

    private static String str(Map<String, Object> body, String key) {
        if (body == null) {
            return null;
        }
        Object v = body.get(key);
        return v == null ? null : String.valueOf(v);
    }

    private static Long num(Map<String, Object> body, String key) {
        if (body == null) {
            return null;
        }
        Object v = body.get(key);
        if (v == null) {
            return null;
        }
        if (v instanceof Number) {
            return ((Number) v).longValue();
        }
        try {
            return Long.valueOf(String.valueOf(v).trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static Boolean bool(Map<String, Object> body, String key) {
        if (body == null) {
            return null;
        }
        Object v = body.get(key);
        if (v == null) {
            return null;
        }
        if (v instanceof Boolean) {
            return (Boolean) v;
        }
        String s = String.valueOf(v).trim();
        if ("true".equalsIgnoreCase(s) || "1".equals(s)) {
            return Boolean.TRUE;
        }
        if ("false".equalsIgnoreCase(s) || "0".equals(s)) {
            return Boolean.FALSE;
        }
        return null;
    }

    private static List<String> strList(Map<String, Object> body, String key) {
        if (body == null) {
            return null;
        }
        Object v = body.get(key);
        if (v == null) {
            return null;
        }
        List<String> out = new ArrayList<>();
        if (v instanceof Iterable) {
            for (Object item : (Iterable<?>) v) {
                if (item != null) {
                    out.add(String.valueOf(item));
                }
            }
            return out;
        }
        out.add(String.valueOf(v));
        return out;
    }
}
