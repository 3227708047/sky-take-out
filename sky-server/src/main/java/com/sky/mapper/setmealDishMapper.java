package com.sky.mapper;

import org.apache.ibatis.annotations.Mapper;

import java.util.List;

@Mapper
public interface setmealDishMapper {
    List<Long> getSetmealIdsByDishIds(List<Long> dishIds);
}
