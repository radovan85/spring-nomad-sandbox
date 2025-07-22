package com.radovan.spring.services;


import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.*;

@Service
public class NomadStatusService {

    private static final String NOMAD_URL = "http://localhost:4646/v1";

    public Map<String, Object> getJobDiagnostics(String jobId) {
        RestTemplate rest = new RestTemplate();
        ObjectMapper mapper = new ObjectMapper();
        Map<String, Object> result = new LinkedHashMap<>();

        try {
            // Evaluations for the job
            String evalsJson = rest.getForObject(NOMAD_URL + "/job/" + jobId + "/evaluations", String.class);
            JsonNode evals = mapper.readTree(evalsJson);
            List<Map<String, Object>> evalResults = new ArrayList<>();
            for (JsonNode eval : evals) {
                Map<String, Object> evalMap = new LinkedHashMap<>();
                String evalId = eval.get("ID").asText();
                evalMap.put("EvalID", evalId);
                evalMap.put("Status", eval.get("Status").asText());
                evalMap.put("StatusDescription", eval.path("StatusDescription").asText(""));

                // Get detailed evaluation
                String evalDetailJson = rest.getForObject(NOMAD_URL + "/evaluation/" + evalId, String.class);
                JsonNode evalDetail = mapper.readTree(evalDetailJson);
                evalMap.put("FailedTGAllocs", evalDetail.path("FailedTGAllocs").toString());
                evalMap.put("ClassEligibility", evalDetail.path("ClassEligibility").toString());
                evalResults.add(evalMap);
            }
            result.put("evaluations", evalResults);

            // Allocations for the job
            String allocsJson = rest.getForObject(NOMAD_URL + "/job/" + jobId + "/allocations", String.class);
            JsonNode allocs = mapper.readTree(allocsJson);
            List<Map<String, Object>> allocResults = new ArrayList<>();
            for (JsonNode alloc : allocs) {
                Map<String, Object> allocMap = new LinkedHashMap<>();
                allocMap.put("AllocID", alloc.get("ID").asText());
                allocMap.put("Status", alloc.get("ClientStatus").asText());
                allocMap.put("NodeID", alloc.get("NodeID").asText());
                allocMap.put("TaskStates", alloc.path("TaskStates").toString());
                allocResults.add(allocMap);
            }
            result.put("allocations", allocResults);

        } catch (Exception e) {
            result.put("error", e.getMessage());
        }
        return result;
    }
}