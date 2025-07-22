package com.radovan.spring.services;

import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

@Service
public class AllocationMonitorService {

    private static final String NOMAD_URL = "http://localhost:4646/v1";
    private static final String JOB_ID = "spring-nomad-job";
    private static final String TASK_NAME = "spring-task";

    @EventListener(ApplicationReadyEvent.class)
    public void monitorAllocStatus() {
        try {
            RestTemplate rest = new RestTemplate();
            ObjectMapper mapper = new ObjectMapper();

            // 1. Get job allocations
            String allocUrl = NOMAD_URL + "/job/" + JOB_ID + "/allocations";
            String allocJson = rest.getForObject(allocUrl, String.class);
            JsonNode allocs = mapper.readTree(allocJson);

            if (!allocs.isArray() || allocs.isEmpty()) {
                System.out.println("❌ No allocations found for job: " + JOB_ID);
                return;
            }

            // 2. Process each allocation
            for (JsonNode alloc : allocs) {
                String allocId = alloc.has("ID") ? alloc.get("ID").asText() : "<unknown>";
                String clientStatus = alloc.has("ClientStatus") ? alloc.get("ClientStatus").asText() : "<unknown>";

                System.out.println("\n📦 Allocation ID: " + allocId);
                System.out.println("🟢 Status: " + clientStatus);

                // 3. Get allocation details
                String allocDetailsUrl = NOMAD_URL + "/allocation/" + allocId;
                JsonNode allocDetails = mapper.readTree(rest.getForObject(allocDetailsUrl, String.class));

                // 4. Check task state
                JsonNode taskStates = allocDetails.get("TaskStates");
                if (taskStates != null && taskStates.has(TASK_NAME)) {
                    JsonNode task = taskStates.get(TASK_NAME);
                    String taskState = task.path("State").asText("<unknown>");
                    System.out.println("🔧 Task State: " + taskState);

                    // 5. Task events
                    System.out.println("\n📜 Task Events:");
                    task.path("Events").forEach(event -> {
                        String time = event.path("Time").asText();
                        String type = event.path("Type").asText();
                        String message = event.path("DisplayMessage").asText();
                        System.out.println("⏰ " + time + " | " + type + " → " + message);
                    });
                }

                // 6. Sandbox logs
                try {
                    String logsUrl = NOMAD_URL + "/client/fs/logs/" + allocId + "?task=" + TASK_NAME + "&type=stderr";
                    String logs = rest.getForObject(logsUrl, String.class);
                    System.out.println("\n📛 Logs:\n" + logs);
                } catch (Exception e) {
                    System.out.println("⚠️ Could not retrieve logs: " + e.getMessage());
                }

                // 7. Health check with smart port detection
                try {
                    JsonNode networks = allocDetails.path("Resources").path("Networks").get(0);
                    JsonNode portNode = null;
                    if (networks.has("DynamicPorts") && networks.path("DynamicPorts").isArray() && networks.path("DynamicPorts").size() > 0) {
                        portNode = networks.path("DynamicPorts").get(0).path("Value");
                        System.out.println("🧠 Using dynamic port for health check");
                    } else if (networks.has("ReservedPorts") && networks.path("ReservedPorts").isArray() && networks.path("ReservedPorts").size() > 0) {
                        portNode = networks.path("ReservedPorts").get(0).path("Value");
                        System.out.println("🧱 Using reserved port for health check");
                    }

                    if (portNode != null && portNode.isInt()) {
                        int port = portNode.asInt();
                        String healthUrl = "http://localhost:" + port + "/ping";
                        System.out.println("Health URL: " + healthUrl);
                        String pingResponse = rest.getForObject(healthUrl, String.class);
                        System.out.println("🏓 Health Check: " + pingResponse);
                    } else {
                        System.out.println("❌ No valid port found for health check");
                    }
                } catch (Exception e) {
                    System.out.println("❌ Health check failed: " + e.getMessage());
                }
            }

        } catch (Exception e) {
            System.err.println("❌ Monitoring failed: " + e.getMessage());
            e.printStackTrace();
        }
    }
}
