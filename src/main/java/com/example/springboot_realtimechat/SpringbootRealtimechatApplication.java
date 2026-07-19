package com.example.springboot_realtimechat;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.security.autoconfigure.UserDetailsServiceAutoConfiguration;

@SpringBootApplication(exclude = { UserDetailsServiceAutoConfiguration.class })
public class SpringbootRealtimechatApplication {

	public static void main(String[] args) {
		SpringApplication.run(SpringbootRealtimechatApplication.class, args);
	}

}
