package com.umi.nest.mapper;

import com.umi.nest.entity.AidObjection;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 家庭经济困难学生认定 · 公示异议（复议）数据访问（P11）。
 *
 * <p>本接口的全部方法都是 P11 新增，不涉及 P5 的资助申请流程。</p>
 */
@Mapper
public interface AidObjectionMapper {

    /** 学生提交复议。 */
    int insert(AidObjection record);

    AidObjection findById(@Param("id") Long id);

    /** 本人复议记录（倒序）。 */
    List<AidObjection> listMine(@Param("studentId") Long studentId);

    /** 某份认定申请下的全部复议。 */
    List<AidObjection> listByApplication(@Param("applicationId") Long applicationId);

    /**
     * 管理端复议列表：status 为空则全部；scopeCounselorId 非空则只看该辅导员名下学生
     * （依 student_profile.counselor_id，与 P5 收件箱同一口径）。
     */
    List<AidObjection> listByStatus(@Param("status") String status,
                                    @Param("scopeCounselorId") Long scopeCounselorId);

    /** 某份申请是否还有未答复的复议（判定答复后要不要回到公示阶段）。 */
    int countPendingByApplication(@Param("applicationId") Long applicationId);

    /** 答复：写答复内容 + 结论状态 + 答复人 + 答复时间（CAS：只有 pending 能被答复）。 */
    int answer(@Param("id") Long id,
               @Param("answer") String answer,
               @Param("status") String status,
               @Param("answeredBy") Long answeredBy);
}
