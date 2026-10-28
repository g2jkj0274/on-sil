package kr.onsil;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class OnSilApplication {
    public static void main(String[] args) {
        SpringApplication.run(OnSilApplication.class, args);
    }
}
