package com.umi.nest.dorm.web;

import com.umi.nest.common.LoginRequiredException;
import com.umi.nest.common.Result;
import com.umi.nest.dorm.service.DormPortalService;
import jakarta.servlet.http.HttpSession;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 学生端门户接口。
 *
 * <p><b>所有学生的 studentId 都从 HttpSession 推导，绝不从请求体或 URL 里读。</b>
 * 这不是风格问题：一旦允许前端传 studentId，把 URL 里的数字改一下就能看到
 * 别人的宿舍、别人的室友、别人的申诉记录。管理端接口可以传 id（有管理员登录挡着），
 * 学生端不行。
 *
 * <p>未登录时抛出 {@link LoginRequiredException}（HTTP 401、消息带 {@code LOGIN_REQUIRED} 前缀），
 * 前端据此跳转登录页，而不是把"未登录"和"业务出错"混成同一个提示。
 */
@RestController
@RequestMapping("/api/dorm/portal")
public class DormPortalController {

    private final DormPortalService service;

    public DormPortalController(DormPortalService service) {
        this.service = service;
    }

    // ==================================================================
    // 身份
    // ==================================================================

    @GetMapping("/me")
    public Result<Map<String, Object>> me(HttpSession session) {
        return Result.success(service.me(userId(session)));
    }

    /** 首次登录绑定学号。 */
    @PostMapping("/bind")
    public Result<Map<String, Object>> bind(@RequestBody PortalRequests.Bind req, HttpSession session) {
        return Result.success("绑定成功", service.bind(userId(session), req.studentNo()));
    }

    // ==================================================================
    // 我的分配
    // ==================================================================

    /** 我的房间、室友、解释。 */
    @GetMapping("/allocation")
    public Result<Map<String, Object>> allocation(HttpSession session) {
        return Result.success(service.allocationOf(requireStudent(session)));
    }

    /** 分配规则说明：硬约束有哪些、目标是什么、阻塞对为什么降不到 0。 */
    @GetMapping("/policy")
    public Result<Map<String, Object>> policy(HttpSession session) {
        userId(session);
        return Result.success(service.policy());
    }

    // ==================================================================
    // 我的心理画像
    // ==================================================================

    @GetMapping("/psyche")
    public Result<Map<String, Object>> psyche(HttpSession session) {
        return Result.success(service.psyche(requireStudent(session)));
    }

    @GetMapping("/questionnaire")
    public Result<Map<String, Object>> questionnaire(HttpSession session) {
        return Result.success(service.questionnaire(requireStudent(session)));
    }

    @PostMapping("/answers")
    public Result<Map<String, Object>> answers(@RequestBody PortalRequests.Answers req, HttpSession session) {
        return Result.success("作答已保存", service.submitAnswers(requireStudent(session), req.answers()));
    }

    /** 提交自述。{@code consent=false} 时只留档不参与提取——授权必须是可选项。 */
    @PostMapping("/text")
    public Result<Map<String, Object>> text(@RequestBody PortalRequests.Text req, HttpSession session) {
        Map<String, Object> r = service.submitText(requireStudent(session), req.kind(),
                req.content(), req.consent());
        return Result.success(String.valueOf(r.get("message")), r);
    }

    // ==================================================================
    // 志愿
    // ==================================================================

    /** 看自己填过的志愿，以及每个志愿对象能不能和自己住一间房。 */
    @GetMapping("/preference")
    public Result<Map<String, Object>> preference(HttpSession session) {
        return Result.success(service.preference(requireStudent(session)));
    }

    /** 保存志愿。三个列表都是全量覆盖，"传空数组"就是清空。 */
    @PostMapping("/preference")
    public Result<Map<String, Object>> savePreference(@RequestBody PortalRequests.Preference req,
                                                      HttpSession session) {
        Map<String, Object> r = service.savePreference(requireStudent(session),
                req.wantRoommates(), req.avoidRoommates(), req.buildings());
        return Result.success(String.valueOf(r.get("message")), r);
    }

    /** 按学号或姓名找同学。关键字短于 2 个字符时不返回任何结果。 */
    @GetMapping("/classmates")
    public Result<Map<String, Object>> classmates(@RequestParam(name = "q", required = false) String q,
                                                  HttpSession session) {
        return Result.success(service.searchClassmates(requireStudent(session), q));
    }

    // ==================================================================
    // 申诉
    // ==================================================================

    /** 提起"我想和某人住一起"。返回三级结论之一，并写明搜索范围。 */
    @PostMapping("/appeal")
    public Result<Map<String, Object>> appeal(@RequestBody PortalRequests.Appeal req, HttpSession session) {
        return Result.success("已受理", service.appeal(requireStudent(session),
                req.wantRoomWith(), req.costBudget()));
    }

    @GetMapping("/appeals")
    public Result<List<Map<String, Object>>> appeals(HttpSession session) {
        return Result.success(service.myAppeals(requireStudent(session)));
    }

    // ==================================================================
    // 会话
    // ==================================================================

    private long requireStudent(HttpSession session) {
        long uid = userId(session);
        Long sid = service.studentIdOf(uid);
        if (sid == null) {
            throw new LoginRequiredException("你的账号还没有绑定学号，请先在门户首页输入学号完成绑定。");
        }
        return sid;
    }

    private static long userId(HttpSession session) {
        Object v = session.getAttribute("userId");
        if (v instanceof Number n) {
            return n.longValue();
        }
        throw new LoginRequiredException("未登录，请先登录学生账号。");
    }
}
