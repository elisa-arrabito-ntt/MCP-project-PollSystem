package com.example.pollSystem;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@EnableScheduling
@SpringBootApplication
public class PollSystemApplication {

	public static void main(String[] args) {
        SpringApplication.run(PollSystemApplication.class, args);
	}

}
