package pe.jmaromas.api;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class JmAromasApplication {
    public static void main(String[] args) {
        SpringApplication.run(JmAromasApplication.class, args);
    }
}
