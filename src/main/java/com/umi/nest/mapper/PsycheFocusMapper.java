package com.umi.nest.mapper;

import com.umi.nest.entity.PsycheFocus;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;
import java.util.Map;

/**
 * 学工 P14 心理健康 · 关注台账数据访问（psyche_focus）。
 *
 * <p>scope 非空 = teacher 口径（只看名下学生或自己负责的台账）；scope = null = admin（全校）。
 * 状态流转是 CAS：WHERE id=? AND deleted=0 AND status=fromStatus。</p>
 */
@Mapper
public interface PsycheFocusMapper {

    int insert(PsycheFocus focus);

    PsycheFocus findById(@Param("id") Long id);

    List<PsycheFocus> list(@Param("level") String level,
                           @Param("status") String status,
                           @Param("className") String className,
                           @Param("keyword") String keyword,
                           @Param("scope") Long scope,
                           @Param("offset") int offset,
                           @Param("limit") int limit);

    int count(@Param("level") String level,
              @Param("status") String status,
              @Param("className") String className,
              @Param("keyword") String keyword,
              @Param("scope") Long scope);

    /** 学生视角：自己的关注台账（只在 /my 里用来决定是否显示建议卡，不返回台账正文）。 */
    List<PsycheFocus> listByStudent(@Param("studentId") Long studentId);

    int update(@Param("focus") PsycheFocus focus, @Param("fromStatus") String fromStatus);

    /** 登记跟进后把台账从 open 推进到 following（CAS，已是 following/closed 时 0 行）。 */
    int markFollowing(@Param("id") Long id);

    /** [{level, cnt}]，只回聚合数；batchId=null 表示全部批次。 */
    List<Map<String, Object>> countGroupByLevel(@Param("scope") Long scope, @Param("batchId") Long batchId);
}
