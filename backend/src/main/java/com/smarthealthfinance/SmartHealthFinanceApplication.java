package com.smarthealthfinance;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class SmartHealthFinanceApplication {

	public static void main(String[] args) {
		SpringApplication.run(SmartHealthFinanceApplication.class, args);
	}

}
