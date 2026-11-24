package cn.edu.campus.hospitality;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/** Starts the Spring Boot application and enables its background jobs. */
@SpringBootApplication
@EnableScheduling
public class HospitalityApplication {
  /** Runs the hospitality application with the supplied runtime options. */
  public static void main(String[] args) {
    SpringApplication.run(HospitalityApplication.class, args);
  }
}
