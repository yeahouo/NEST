package com.umi.nest.mapper;

import com.umi.nest.entity.CommunityInspection;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 宿舍检查记录（学工 P15）数据访问。
 *
 * <p>整改闭环的状态列是 rectify_status（不是 status），因此 CAS 写成
 * WHERE id=? AND deleted=0 AND rectify_status=#{fromStatus}，同样是「状态列 + 现值」的 CAS。</p>
 *
 * <p>辅导员范围裁剪：counselorId 非空时只返回该辅导员名下学生所在宿舍的记录
 * （EXISTS student_profile p WHERE p.counselor_id=? AND p.dorm_building=i.building
 * AND p.dorm_room=i.room_no）。</p>
 */
@Mapper
public interface CommunityInspectionMapper {

    int insert(CommunityInspection inspection);

    CommunityInspection findById(@Param("id") Long id);

    CommunityInspection findByBatchRoom(@Param("batchId") Long batchId,
                                        @Param("building") String building,
                                        @Param("roomNo") String roomNo);

    /**
     * 检查记录列表（各入口共用一个查询）。
     *
     * @param publishedOnly true = 排除 draft 批次的记录（学生端只能看已发布的批次）
     * @param rectifiedOnly true = 只看需要整改的记录（整改状态 ≠ none）
     */
    List<CommunityInspection> list(@Param("batchId") Long batchId,
                                   @Param("term") String term,
                                   @Param("building") String building,
                                   @Param("roomNo") String roomNo,
                                   @Param("grade") String grade,
                                   @Param("rectifyStatus") String rectifyStatus,
                                   @Param("source") String source,
                                   @Param("keyword") String keyword,
                                   @Param("counselorId") Long counselorId,
                                   @Param("publishedOnly") boolean publishedOnly,
                                   @Param("rectifiedOnly") boolean rectifiedOnly,
                                   @Param("offset") int offset,
                                   @Param("limit") int limit);

    /** 列表总数（过滤条件与 {@link #list} 一致，沿用同一段 List_Filters）。 */
    int countList(@Param("batchId") Long batchId,
                  @Param("term") String term,
                  @Param("building") String building,
                  @Param("roomNo") String roomNo,
                  @Param("grade") String grade,
                  @Param("rectifyStatus") String rectifyStatus,
                  @Param("source") String source,
                  @Param("keyword") String keyword,
                  @Param("counselorId") Long counselorId,
                  @Param("publishedOnly") boolean publishedOnly,
                  @Param("rectifiedOnly") boolean rectifiedOnly);

    /** 学生提交整改说明：pending/rejected → submitted（CAS 在 rectify_status 上）。 */
    int submitRectify(@Param("id") Long id,
                      @Param("fromStatus") String fromStatus,
                      @Param("note") String note,
                      @Param("at") LocalDateTime at);

    /** 复查：submitted → accepted / rejected，同时写 recheck_result / recheck_by / recheck_at。 */
    int recheck(@Param("id") Long id,
                @Param("fromStatus") String fromStatus,
                @Param("toStatus") String toStatus,
                @Param("result") String result,
                @Param("by") Long by,
                @Param("at") LocalDateTime at);

    /** 逻辑删除（服务层先校验批次仍在检查中/已评分，再执行）。 */
    int logicalDelete(@Param("id") Long id);

    List<Map<String, Object>> countGroupByGrade(@Param("batchId") Long batchId,
                                                @Param("term") String term,
                                                @Param("building") String building,
                                                @Param("counselorId") Long counselorId);

    List<Map<String, Object>> countGroupByRectify(@Param("batchId") Long batchId,
                                                  @Param("term") String term,
                                                  @Param("building") String building,
                                                  @Param("counselorId") Long counselorId);

    Map<String, Object> summary(@Param("batchId") Long batchId,
                                @Param("term") String term,
                                @Param("building") String building,
                                @Param("counselorId") Long counselorId);

    /** 某房间最近一次检查日期（导入时判重/展示用）。 */
    LocalDate latestCheckDate(@Param("building") String building, @Param("roomNo") String roomNo);

    /** 该房间是否属于此辅导员名下学生（辅导员录入检查/查看宿舍的权限边界）。 */
    int countRoomOfCounselor(@Param("counselorId") Long counselorId,
                             @Param("building") String building,
                             @Param("roomNo") String roomNo);

    /** 房间内任一学生的 users.id（登记宿舍长用；无学生档案时为 null）。 */
    Long findRoomLeader(@Param("building") String building, @Param("roomNo") String roomNo);
}
