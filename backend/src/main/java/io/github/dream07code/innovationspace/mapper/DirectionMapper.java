package io.github.dream07code.innovationspace.mapper;

import java.util.List;
import java.util.Map;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface DirectionMapper {
  @Select("SELECT id,code,name,enabled FROM direction ORDER BY id")
  List<Map<String, Object>> list();

  @Select("SELECT id,code,name,enabled FROM direction WHERE id=#{id}")
  Map<String, Object> find(@Param("id") long id);
}
