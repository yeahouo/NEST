package com.umi.nest.mapper;

import com.umi.nest.entity.RankRow;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;
import java.util.Map;

/**
 * 综测排名快照（学工 P13）数据访问。
 *
 * <p>compute 的写入口只有 deleteByBatch + batchInsert（先删本批次再全量写，同一事务），
 * 读接口（列表 / 明细 / 公示 / 导出 / 统计）都只读快照，不现场算 —— 见 RankService 的说明。</p>
 */
@Mapper
public interface RankRowMapper {

    int deleteByBatch(@Param("batchId") Long batchId);

    int batchInsert(@Param("rows") List<RankRow> rows);

    /** 排名列表（teacher/admin 看全专业；不用辅导员过滤）。 */
    List<RankRow> list(@Param("batchId") Long batchId,
                       @Param("major") String major,
                       @Param("className") String className,
                       @Param("keyword") String keyword,
                       @Param("offset") int offset,
                       @Param("limit") int limit);

    int count(@Param("batchId") Long batchId,
              @Param("major") String major,
              @Param("className") String className,
              @Param("keyword") String keyword);

    RankRow findByBatchAndStudent(@Param("batchId") Long batchId, @Param("studentId") Long studentId);

    /** 整批快照（公示 / 导出 / 统计共用），按专业、专业内名次排序。 */
    List<RankRow> listByBatch(@Param("batchId") Long batchId);

    /** 按专业+年级分组的统计（stats 的 byMajor：各等级名额落位情况）。 */
    List<Map<String, Object>> countGroupByMajorGrade(@Param("batchId") Long batchId);
}
