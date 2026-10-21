package com.umi.nest.mapper;

import com.umi.nest.entity.PsycheInstrument;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 学工 P14 心理健康 · 量表定义数据访问（psyche_instrument）。
 */
@Mapper
public interface PsycheInstrumentMapper {

    int insert(PsycheInstrument instrument);

    PsycheInstrument findById(@Param("id") Long id);

    /** code 唯一（uk_pi_code），重复由服务层转 400。 */
    PsycheInstrument findByCode(@Param("code") String code);

    /** active=1 查启用中的量表；active=null 查全部。 */
    List<PsycheInstrument> list(@Param("active") Integer active);
}
