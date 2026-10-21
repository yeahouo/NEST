package com.umi.nest.mapper;

import com.umi.nest.entity.SecondClassCategory;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 第二课堂板块（学工 P18，S26）数据访问。
 *
 * <p>{@code required_hours} / {@code yearly_cap_hours} / {@code credit_per_hour} 三列允许 NULL，
 * NULL = 学校未设置（不是 0，也不是默认值）：SQL 不得用 {@code COALESCE} 把 NULL 抹成 0，否则页面会把
 * 「学校还没定」显示成「要求 0 学时」，这是对政策的伪造。</p>
 *
 * <p>板块不做物理删除；停用用 {@code enabled=0}，历史记录仍可读。</p>
 */
@Mapper
public interface SecondClassCategoryMapper {

    /** 启用中的板块（学生申报下拉用），按 sort_no, id 排序。 */
    List<SecondClassCategory> listEnabled();

    /** 全部未删除板块（管理端配置用），含停用。 */
    List<SecondClassCategory> listAll();

    SecondClassCategory selectById(@Param("id") Long id);

    SecondClassCategory selectByCode(@Param("code") String code);

    int insert(SecondClassCategory category);

    /**
     * 更新板块。文字/排序/启停为动态 set；<b>三列学时/学分口径整体覆盖</b>（传 null 即写回 NULL），
     * 因为管理端表单总是三列一起提交，改成动态 set 会让学校无法「取消已设的上限」。
     */
    int update(@Param("c") SecondClassCategory category);

    /** 板块下已有申报条数（删除/停用前置校验用）。 */
    long countRecordsByCategory(@Param("categoryId") Long categoryId);

    /** 板块下活动条数（停用提示用）。 */
    long countActivitiesByCategory(@Param("categoryId") Long categoryId);
}
