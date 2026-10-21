package com.umi.nest.mapper;

import com.umi.nest.entity.EvalIndicator;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/** 综测指标。指标表是配置数据，只读 + admin 后续可改 active。 */
@Mapper
public interface EvalIndicatorMapper {

    /** 全部启用的指标，按 sort_order 升序（五维自然分组） */
    List<EvalIndicator> listActive();

    EvalIndicator findById(@Param("id") Long id);

    EvalIndicator findByCode(@Param("code") String code);

    int countActive();
}
