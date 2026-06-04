package com.shenghui56.dev.kpiperftool;

import com.shenghui56.dev.kpiperftool.framework.config.PerfToolProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication
@EnableConfigurationProperties(PerfToolProperties.class)
public class KPIPerfToolApplication {

	public static void main(String[] args) {
		SpringApplication.run(KPIPerfToolApplication.class, args);
	}
}
