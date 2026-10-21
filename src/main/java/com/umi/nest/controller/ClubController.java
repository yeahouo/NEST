package com.umi.nest.controller;

import com.umi.nest.common.Result;
import com.umi.nest.service.ClubService;
import com.umi.nest.service.CommunityService;
import jakarta.servlet.http.HttpSession;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * 学生社团（学工 P17）：/api/club/**，共 22 条方法级路由。
 *
 * <p>台账与注销 6 + 成员 3 + 年审 5 + 星级 3 + 公示 4 + 统计导出 1 = 22
 * （其中「我的社团」为学生端自助入口）。口径见 target/p17-plan.md §5 与 §11，
 * 以及《学工平台功能对齐说明》S25a（挂靠单位 / 社团公示）与 S25b（业务指导单位 / 星级公示）。</p>
 *
 * <p>返回口径：除 /stats/export（CSV 字节流，失败时回该状态码且空 body）外，
 * 所有接口一律 HTTP 200 + {code,message,data}；业务失败用 Result.error
 * （默认 400，未登录 401 / 越权 403 / 不存在 404），message 是可直接展示给学校看的中文句子。</p>
 *
 * <p>字面量路由（/list、/list/mine、/members、/reviews、/ratings、/publicity、/stats/export）
 * 全部声明在 /{id} **之前**；POST /api/club 与 POST /api/club/ 等价（{@code @PostMapping({"", "/"})}）。</p>
 *
 * <p>登录态沿用会话键 userId / role（与 PartyController、CommunityController 一致）。
 * 权限：年审结果登记、星级登记、公示发布/关闭、台账与成员维护、导出——仅 admin；
 * 年审材料提报与挂靠单位意见——teacher / admin；年审与星级列表——学生可读但只回本人在册社团的行；
 * 公示——任何已登录角色可见（含已关闭）。</p>
 */
@RestController("clubController")
@RequestMapping("/api/club")
public class ClubController {

    @Autowired
    private ClubService clubService;

    // =========================================================================
    // 1. 社团台账与注销（6 条）
    // =========================================================================

    /** 1. 社团列表（GET /api/club/list）。未登录 401；学生按在册社团 scope 只回自己的行。 */
    @GetMapping("/list")
    public Result<?> listClubs(HttpSession session,
                               @RequestParam(name = "category", required = false) String category,
                               @RequestParam(name = "attachUnit", required = false) String attachUnit,
                               @RequestParam(name = "status", required = false) String status,
                               @RequestParam(name = "keyword", required = false) String keyword,
                               @RequestParam(name = "page", required = false) Integer page,
                               @RequestParam(name = "size", required = false) Integer size) {
        return clubService.listClubs(userId(session), role(session), category, attachUnit, status, keyword, page, size);
    }

    /**
     * 2. 我的社团（GET /api/club/list/mine）。字面量路由，必须优先于 /{id}：
     * 返回当前在册（quit_date IS NULL）的社团，行内含 clubId / role / roleText。
     */
    @GetMapping("/list/mine")
    public Result<?> listMyClubs(HttpSession session) {
        return clubService.listMyClubs(userId(session), role(session));
    }

    /** 3. 社团成员列表（GET /api/club/members?clubId=）。不带 clubId 也返回 200。 */
    @GetMapping("/members")
    public Result<?> listMembers(HttpSession session,
                                 @RequestParam(name = "clubId", required = false) Long clubId,
                                 @RequestParam(name = "role", required = false) String memberRole,
                                 @RequestParam(name = "keyword", required = false) String keyword,
                                 @RequestParam(name = "page", required = false) Integer page,
                                 @RequestParam(name = "size", required = false) Integer size) {
        return clubService.listMembers(userId(session), role(session), clubId, memberRole, keyword, page, size);
    }

    /** 4. 年审台账列表（GET /api/club/reviews）。学生可读，但只回本人在册社团的行。 */
    @GetMapping("/reviews")
    public Result<?> listReviews(HttpSession session,
                                 @RequestParam(name = "clubId", required = false) Long clubId,
                                 @RequestParam(name = "schoolYear", required = false) String schoolYear,
                                 @RequestParam(name = "result", required = false) String result,
                                 @RequestParam(name = "keyword", required = false) String keyword,
                                 @RequestParam(name = "page", required = false) Integer page,
                                 @RequestParam(name = "size", required = false) Integer size) {
        return clubService.listReviews(userId(session), role(session), clubId, schoolYear, result, keyword, page, size);
    }

    /** 5. 星级评定列表（GET /api/club/ratings）。学生可读，但只回本人在册社团的行。 */
    @GetMapping("/ratings")
    public Result<?> listRatings(HttpSession session,
                                 @RequestParam(name = "clubId", required = false) Long clubId,
                                 @RequestParam(name = "schoolYear", required = false) String schoolYear,
                                 @RequestParam(name = "starLevel", required = false) String starLevel,
                                 @RequestParam(name = "keyword", required = false) String keyword,
                                 @RequestParam(name = "page", required = false) Integer page,
                                 @RequestParam(name = "size", required = false) Integer size) {
        return clubService.listRatings(userId(session), role(session), clubId, schoolYear, starLevel, keyword, page, size);
    }

    /** 6. 公示列表（GET /api/club/publicity）。任何已登录角色可见（§6：公示刻意不做范围过滤）。 */
    @GetMapping("/publicity")
    public Result<?> listPublicities(HttpSession session,
                                     @RequestParam(name = "publicityType", required = false) String publicityType,
                                     @RequestParam(name = "status", required = false) String status,
                                     @RequestParam(name = "keyword", required = false) String keyword,
                                     @RequestParam(name = "page", required = false) Integer page,
                                     @RequestParam(name = "size", required = false) Integer size) {
        return clubService.listPublicities(userId(session), role(session), publicityType, status, keyword, page, size);
    }

    /** 7. 公示详情（GET /api/club/publicity/{id}）。已关闭的公示仍可查看（§5-28）。 */
    @GetMapping("/publicity/{id}")
    public Result<?> publicityDetail(HttpSession session, @PathVariable("id") Long id) {
        return clubService.publicityDetail(userId(session), role(session), id);
    }

    /** 8. 移除成员（DELETE /api/club/members/{id}）。软删：quit_date=当天（或入参）+ deleted=1，仅 admin。 */
    @DeleteMapping("/members/{id}")
    public Result<?> quitMember(HttpSession session,
                                @PathVariable("id") Long id,
                                @RequestParam(name = "quit_date", required = false) String quitDate,
                                @RequestParam(name = "quitDate", required = false) String quitDateCamel,
                                @RequestBody(required = false) Map<String, Object> body) {
        String date = quitDate != null ? quitDate : quitDateCamel;
        return clubService.quitMember(userId(session), role(session), id, date, null, body);
    }

    /** 9. 添加成员（POST /api/club/members；仅 admin）。同一社团允许先加 member 再加 leader。 */
    @PostMapping("/members")
    public Result<?> addMember(HttpSession session, @RequestBody(required = false) Map<String, Object> body) {
        return clubService.addMember(userId(session), role(session), body);
    }

    /** 10. 提报年审材料（POST /api/club/reviews；teacher / admin）。result 留空（NULL = 未登记）。 */
    @PostMapping("/reviews")
    public Result<?> createReview(HttpSession session, @RequestBody(required = false) Map<String, Object> body) {
        return clubService.createReview(userId(session), role(session), body);
    }

    /** 11. 登记挂靠单位意见（POST /api/club/reviews/{id}/opinion；teacher / admin）。 */
    @PostMapping("/reviews/{id}/opinion")
    public Result<?> writeOpinion(HttpSession session, @PathVariable("id") Long id,
                                  @RequestBody(required = false) Map<String, Object> body) {
        return clubService.writeOpinion(userId(session), role(session), id, body);
    }

    /** 12. 提交年审（POST /api/club/reviews/{id}/submit）。写 submit_time；重复提交 400（§7）。 */
    @PostMapping("/reviews/{id}/submit")
    public Result<?> submitReview(HttpSession session, @PathVariable("id") Long id,
                                  @RequestBody(required = false) Map<String, Object> body) {
        return clubService.submitReview(userId(session), role(session), id, body);
    }

    /** 13. 登记年审结果（POST /api/club/reviews/{id}/result；**仅 admin**）。CAS result IS NULL，登记后冻结。 */
    @PostMapping("/reviews/{id}/result")
    public Result<?> recordResult(HttpSession session, @PathVariable("id") Long id,
                                  @RequestBody(required = false) Map<String, Object> body) {
        return clubService.recordResult(userId(session), role(session), id, body);
    }

    /** 14. 登记星级（POST /api/club/ratings；**仅 admin**）。basisNote 必填，平台不自动评级（§5-27）。 */
    @PostMapping("/ratings")
    public Result<?> createRating(HttpSession session, @RequestBody(required = false) Map<String, Object> body) {
        return clubService.createRating(userId(session), role(session), body);
    }

    /** 15. 修改星级登记（PUT /api/club/ratings/{id}；**仅 admin**）。 */
    @PutMapping("/ratings/{id}")
    public Result<?> updateRating(HttpSession session, @PathVariable("id") Long id,
                                  @RequestBody(required = false) Map<String, Object> body) {
        return clubService.updateRating(userId(session), role(session), id, body);
    }

    /** 16. 发布公示（POST /api/club/publicity；**仅 admin**）。endDate 缺省 = startDate + 4 天（含首尾 5 天）。 */
    @PostMapping("/publicity")
    public Result<?> createPublicity(HttpSession session, @RequestBody(required = false) Map<String, Object> body) {
        return clubService.createPublicity(userId(session), role(session), body);
    }

    /** 17. 关闭公示（POST /api/club/publicity/{id}/close；**仅 admin**）。CAS open→closed，重复关闭 400。 */
    @PostMapping("/publicity/{id}/close")
    public Result<?> closePublicity(HttpSession session, @PathVariable("id") Long id,
                                    @RequestBody(required = false) Map<String, Object> body) {
        return clubService.closePublicity(userId(session), role(session), id, body);
    }

    /**
     * 18. 社团统计导出（GET /api/club/stats/export）。CSV 字节流：UTF-8 带 BOM、
     * Content-Type text/csv;charset=UTF-8、文件名含日期；列 = 社团名称/社团类别/挂靠单位/学年/年审结果/星级/业务指导单位
     * （**不含完整学号与任何联系方式**）。未登录或非 staff → 403 且 **空 body**。
     */
    @GetMapping("/stats/export")
    public ResponseEntity<byte[]> exportStats(HttpSession session,
                                              @RequestParam(name = "schoolYear", required = false) String schoolYear,
                                              @RequestParam(name = "category", required = false) String category,
                                              @RequestParam(name = "status", required = false) String status,
                                              @RequestParam(name = "keyword", required = false) String keyword) {
        CommunityService.CsvExport out = clubService.exportCsv(userId(session), role(session), schoolYear,
                category, status, keyword);
        if (out.errorCode != null) {
            return ResponseEntity.status(out.errorCode).build();
        }
        byte[] bytes = ("\uFEFF" + out.csv).getBytes(StandardCharsets.UTF_8);
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(new MediaType("text", "csv", StandardCharsets.UTF_8));
        headers.set(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + out.filename + "\"");
        headers.setContentLength(bytes.length);
        return new ResponseEntity<>(bytes, headers, HttpStatus.OK);
    }

    // =========================================================================
    // 2. 台账单条读写（4 条，变量路径 {id} 必须声明在全部字面量路由之后）
    // =========================================================================

    /** 19. 社团详情（GET /api/club/{id}）。任何已登录角色可看。 */
    @GetMapping("/{id}")
    public Result<?> clubDetail(HttpSession session, @PathVariable("id") Long id) {
        return clubService.clubDetail(userId(session), role(session), id);
    }

    /** 20. 新建社团（POST /api/club 与 POST /api/club/ 等价；teacher / admin）。 */
    @PostMapping({"", "/"})
    public Result<?> createClub(HttpSession session, @RequestBody(required = false) Map<String, Object> body) {
        return clubService.createClub(userId(session), role(session), body);
    }

    /** 21. 修改社团台账（PUT /api/club/{id}；**仅 admin**）。CAS：active（终态已注销不可再改）。 */
    @PutMapping("/{id}")
    public Result<?> updateClub(HttpSession session, @PathVariable("id") Long id,
                                @RequestBody(required = false) Map<String, Object> body) {
        return clubService.updateClub(userId(session), role(session), id, body);
    }

    /** 22. 注销社团（POST /api/club/{id}/deregister；**仅 admin**）。CAS active→deregistered，重复注销 400。 */
    @PostMapping("/{id}/deregister")
    public Result<?> deregisterClub(HttpSession session, @PathVariable("id") Long id,
                                    @RequestBody(required = false) Map<String, Object> body) {
        return clubService.deregisterClub(userId(session), role(session), id, body);
    }

    // =========================================================================
    // 会话工具（与 PartyController / CommunityController 一致）
    // =========================================================================

    private static Long userId(HttpSession session) {
        Object value = session == null ? null : session.getAttribute("userId");
        if (value instanceof Number) {
            return ((Number) value).longValue();
        }
        if (value != null) {
            try {
                return Long.valueOf(String.valueOf(value));
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    private static String role(HttpSession session) {
        Object value = session == null ? null : session.getAttribute("role");
        return value == null ? null : String.valueOf(value);
    }
}
