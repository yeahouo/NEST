package com.umi.nest.mapper;

import com.umi.nest.entity.StudentProfile;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;
import java.util.Map;

/**
 * 学工平台·学生档案 Mapper。
 */
@Mapper
public interface StudentProfileMapper {

    int insert(StudentProfile profile);

    StudentProfile findById(@Param("id") Long id);

    StudentProfile findByUserId(@Param("userId") Long userId);

    List<StudentProfile> list(@Param("counselorId") Long counselorId,
                              @Param("className") String className,
                              @Param("keyword") String keyword,
                              @Param("status") String status,
                              @Param("offset") int offset,
                              @Param("limit") int limit);

    int count(@Param("counselorId") Long counselorId,
              @Param("className") String className,
              @Param("keyword") String keyword,
              @Param("status") String status);

    int update(StudentProfile profile);

    /** 辅导员所带学生数（工作台统计） */
    int countByCounselor(@Param("counselorId") Long counselorId);

    /** 尚未建立学工档案的在读学生（管理员补录用） */
    List<StudentProfile> listMissingProfile(@Param("offset") int offset, @Param("limit") int limit);

    /** 尚未建档的学生总数 */
    int countMissingProfile();

    /** 辅导员工作台·综测未出分：在该学期没有 eval_summary 记录的在读学生 */
    List<StudentProfile> listMissingEval(@Param("term") String term,
                                         @Param("counselorId") Long counselorId,
                                         @Param("offset") int offset,
                                         @Param("limit") int limit);

    int countMissingEval(@Param("term") String term, @Param("counselorId") Long counselorId);

    /** 名册里出现过的班级（筛选下拉用） */
    List<String> listClassNames(@Param("counselorId") Long counselorId);

    /** 按学籍状态分组计数 */
    List<Map<String, Object>> countGroupByStatus(@Param("counselorId") Long counselorId);

    /** 按班级分组计数 */
    List<Map<String, Object>> countGroupByClass(@Param("counselorId") Long counselorId);
}
