package io.github.mcpdataguard;
import org.springframework.boot.SpringApplication;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.boot.autoconfigure.SpringBootApplication;
@SpringBootApplication
@EnableScheduling public class GatewayApplication {
 public static void main(String[] args) { SpringApplication.run(GatewayApplication.class, args); }
}