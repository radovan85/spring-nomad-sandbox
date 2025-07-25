package com.radovan.spring.services;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

@Service
public class NomadJobRegistrationService {

	@Value("${secondary_port}")
	private int secondaryPort;

	@org.springframework.context.event.EventListener(ApplicationReadyEvent.class)
	public void registerWithNomad() {
		if (isNomadEnvironment()) {
			return;
		}

		ExecutorService executor = Executors.newSingleThreadExecutor();
		HttpServer fileServer = null;

		try {
			fileServer = startMiniHttpFileServer(secondaryPort, "target");
			TimeUnit.SECONDS.sleep(2);

			String hostAddress = InetAddress.getLocalHost().getHostAddress();
			String nomadUrl = "http://localhost:4646/v1/jobs";
			String jarFile = "nomad-health-boot-0.0.1-SNAPSHOT.jar";
			String jarUrl = "http://" + hostAddress + ":" + secondaryPort + "/" + jarFile;

			Map<String, Object> check = new LinkedHashMap<>();
			check.put("Name", "ping-check");
			check.put("Type", "http");
			check.put("Path", "/ping");
			check.put("Interval", 30_000_000_000L);
			check.put("Timeout", 10_000_000_000L);
			check.put("PortLabel", "http");

			Map<String, Object> service = new LinkedHashMap<>();
			service.put("Name", "spring-nomad-job");
			service.put("PortLabel", "http");
			service.put("Tags", List.of("spring", "boot", "health"));
			service.put("Checks", List.of(check));

			Map<String, Object> reservedPort = new LinkedHashMap<>();
			reservedPort.put("Label", "http");
			reservedPort.put("Value", 8080);

			Map<String, Object> network = new LinkedHashMap<>();
			network.put("ReservedPorts", List.of(reservedPort));

			Map<String, Object> resources = new LinkedHashMap<>();
			resources.put("CPU", 300);
			resources.put("MemoryMB", 1024);
			resources.put("Networks", List.of(network));

			Map<String, Object> env = new LinkedHashMap<>();
			env.put("NOMAD_SANDBOX_MODE", "true");

			Map<String, Object> config = new LinkedHashMap<>();
			// config.put("command", "powershell.exe");
			// config.put("args", List.of("-c", "Start-Sleep -Seconds 45; Invoke-WebRequest
			// '" + jarUrl + "' -OutFile 'app.jar'; java -jar app.jar --server.port=8080"));
			config.put("command", "cmd.exe");
			config.put("args", List.of("/c", "curl " + jarUrl + " -o app.jar && java -jar app.jar --server.port=8080"));

			Map<String, Object> task = new LinkedHashMap<>();
			task.put("Name", "spring-task");
			task.put("Driver", "raw_exec");
			task.put("Resources", resources);
			task.put("Services", List.of(service));
			task.put("env", env);
			task.put("config", config);
			task.put("Debug", true);

			Map<String, Object> group = new LinkedHashMap<>();
			group.put("Name", "spring-group");
			group.put("Count", 1);
			group.put("Tasks", List.of(task));

			Map<String, Object> constraint = new LinkedHashMap<>();
			constraint.put("LTarget", "${attr.kernel.name}");
			constraint.put("RTarget", "windows");
			constraint.put("Operand", "==");

			Map<String, Object> restart = new LinkedHashMap<>();
			restart.put("Attempts", 3);
			restart.put("Interval", 300_000_000_000L); // 5 min
			restart.put("Delay", 45_000_000_000L); // 45 sec
			restart.put("Mode", "delay");
			group.put("restart", restart);

			Map<String, Object> job = new LinkedHashMap<>();
			job.put("ID", "spring-nomad-job");
			job.put("Name", "spring-nomad-job");
			job.put("Type", "service");
			job.put("Datacenters", List.of("dc1"));
			job.put("TaskGroups", List.of(group));
			job.put("Constraints", List.of(constraint));

			ObjectMapper mapper = new ObjectMapper();
			String jsonBody = mapper.writeValueAsString(Map.of("Job", job));
			System.out.println("📦 JSON payload:\n" + jsonBody);

			HttpHeaders headers = new HttpHeaders();
			headers.setContentType(MediaType.APPLICATION_JSON);
			HttpEntity<String> entity = new HttpEntity<>(jsonBody, headers);

			RestTemplate restTemplate = new RestTemplate();
			ResponseEntity<String> response = restTemplate.exchange(nomadUrl, HttpMethod.POST, entity, String.class);
			System.out.println("✅ Nomad response: " + response.getStatusCode());

			int maxAttempts = 15;
			boolean allocationsFound = false;

			for (int attempt = 1; attempt <= maxAttempts && !allocationsFound; attempt++) {
				System.out.println("🕒 Checking allocations... Attempt " + attempt + "/" + maxAttempts);
				TimeUnit.SECONDS.sleep(10);

				try {
					String allocUrl = "http://localhost:4646/v1/job/spring-nomad-job/allocations";
					String allocJson = restTemplate.getForObject(allocUrl, String.class);

					if (allocJson != null && !allocJson.equals("[]")) {
						allocationsFound = true;
						System.out.println("🎉 Allocations found: " + allocJson);

						String allocIdFound = mapper.readTree(allocJson).get(0).get("ID").asText();
						String allocStatusUrl = "http://localhost:4646/v1/allocation/" + allocIdFound;
						String allocStatus = restTemplate.getForObject(allocStatusUrl, String.class);
						System.out.println("📋 Allocation status: " + allocStatus);
						System.out.println("Application is turning off and working with Nomad");
						if (fileServer != null) {
							fileServer.stop(0);
							System.out.println("🛑 Mini HTTP server stopped.");
						}
						executor.shutdown();
						System.exit(0);
					}
				} catch (Exception e) {
					System.out.println("⚠️ Allocation check failed: " + e.getMessage());
				}
			}

			if (!allocationsFound) {
				System.out.println("❌ No allocations created after " + (maxAttempts * 10) + " seconds");
				System.out.println("🔥 Check Nomad status, node availability, and artifact access.");
			}

		} catch (Exception e) {
			System.err.println("❌ Failed to register Nomad job");
			e.printStackTrace();
		} finally {
			if (fileServer != null) {
				fileServer.stop(0);
				System.out.println("🛑 Mini HTTP server stopped.");
			}
			executor.shutdown();
		}
	}

	private static HttpServer startMiniHttpFileServer(int port, String folder) throws IOException {
		HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);
		server.createContext("/", exchange -> {
			String requestedPath = exchange.getRequestURI().getPath();
			if (requestedPath.startsWith("/"))
				requestedPath = requestedPath.substring(1);
			Path filePath = Paths.get(folder, requestedPath);

			if (Files.exists(filePath) && !Files.isDirectory(filePath)) {
				byte[] bytes = Files.readAllBytes(filePath);
				exchange.sendResponseHeaders(200, bytes.length);
				try (OutputStream os = exchange.getResponseBody()) {
					os.write(bytes);
				}
			} else {
				String notFound = "File not found: " + filePath;
				exchange.sendResponseHeaders(404, notFound.length());
				try (OutputStream os = exchange.getResponseBody()) {
					os.write(notFound.getBytes());
				}
			}
		});
		server.setExecutor(Executors.newSingleThreadExecutor());
		server.start();
		System.out.println("🚀 Mini HTTP server started on port " + port + ", serving from folder: " + folder);
		return server;
	}

	private static boolean isNomadEnvironment() {
		String allocId = System.getenv("NOMAD_SANDBOX_MODE");
		if (allocId != null && !allocId.isEmpty()) {
			System.out.println("🟡 Detected Nomad runtime — allocation ID: " + allocId);
			return true;
		}
		System.out.println("Nomad sandbox mode note detected");
		return false;
	}

}
