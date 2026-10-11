package io.github.dream07code.innovationspace.config;

import org.apache.ibatis.annotations.Mapper;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@MapperScan(basePackages = "io.github.dream07code.innovationspace", annotationClass = Mapper.class)
public class MapperConfiguration { }
