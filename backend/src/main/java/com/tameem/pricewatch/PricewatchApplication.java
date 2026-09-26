package com.tameem.pricewatch;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class PricewatchApplication {

	public static void main(String[] args) {
		System.setProperty("jdk.http.auth.tunneling.disabledSchemes", "");
		SpringApplication.run(PricewatchApplication.class, args);
	}

}
