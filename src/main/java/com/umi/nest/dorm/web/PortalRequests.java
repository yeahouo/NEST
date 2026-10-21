package com.umi.nest.dorm.web;

import java.util.List;
import java.util.Map;

/**
 * 学生端门户的请求体定义。
 *
 * <p>与管理端分开的原因不是形式主义：学生端所有接口的 {@code studentId} 都来自
 * 登录会话，绝不从请求体里读。一旦让前端传 studentId，改一个数字就能看到别人的
 * 宿舍和申诉记录。这两套请求体长得像，但少了 studentId 这个字段本身就是安全边界。
 */
final class PortalRequests {

    private PortalRequests() {
    }

    /** 首次登录时把自己的账号绑到宿舍名单上。 */
    public record Bind(String studentNo) {
    }

    /** 提交一段自述素材。{@code consent} 为 false 时只留档，不参与提取。 */
    public record Text(String kind, String content, Boolean consent) {
    }

    /** 提交量表作答。 */
    public record Answers(Map<String, Object> answers) {
    }

    /** 提起室友诉求。 */
    public record Appeal(List<Long> wantRoomWith, Double costBudget) {
    }

    /**
     * 提交室友志愿。
     *
     * <p>同样没有 studentId —— 加了它就能改别人的志愿。
     * 三个列表都允许为空：全部传空数组是「清空志愿」的合法操作，
     * 在库里与「从没填过」的结果一样（都没有行），不需要额外区分。
     */
    public record Preference(List<Long> wantRoommates, List<Long> avoidRoommates,
                             List<String> buildings) {
    }
}
