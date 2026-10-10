package io.github.dream07code.testsupport;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.test.context.TestConfiguration;

@TestConfiguration(proxyBeanMethods = false)
@MapperScan(basePackageClasses = PersistenceProbeConfiguration.class, annotationClass = Mapper.class)
public class PersistenceProbeConfiguration {
    @Mapper
    public interface ProbeMapper extends BaseMapper<ProbeEntity> { }

    @TableName("bootstrap_probe")
    public static class ProbeEntity {
        @TableId(type = IdType.INPUT)
        private Long id;
        private String name;

        public Long getId() { return id; }
        public void setId(Long id) { this.id = id; }
        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
    }
}
