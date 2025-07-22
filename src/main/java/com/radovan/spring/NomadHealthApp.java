package com.radovan.spring;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.ApplicationContext;
import com.radovan.spring.services.NomadJobRegistrationService;

@SpringBootApplication
public class NomadHealthApp {

    public static void main(String[] args) {
        boolean nomadMode = isNomadEnvironment();
        String selectedPort = nomadMode ? "8080" : "8086";
        System.setProperty("server.port", selectedPort);

        System.out.println("📡 Environment: " + (nomadMode ? "Nomad sandbox" : "Local execution"));
        System.out.println("🔌 Selected port: " + selectedPort);

        ApplicationContext ctx = SpringApplication.run(NomadHealthApp.class, args);

        if (!nomadMode) {
            NomadJobRegistrationService service = ctx.getBean(NomadJobRegistrationService.class);
            service.registerWithNomad();
        }
    }

    private static boolean isNomadEnvironment() {
        String allocId = System.getenv("NOMAD_ALLOC_ID");
        if (allocId != null && !allocId.isEmpty()) {
            System.out.println("🟡 Detected Nomad runtime — allocation ID: " + allocId);
            return true;
        }
        return false;
    }
}
