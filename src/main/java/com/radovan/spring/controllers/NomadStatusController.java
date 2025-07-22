package com.radovan.spring.controllers;

import com.radovan.spring.services.NomadStatusService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/nomad/status")
public class NomadStatusController {

    @Autowired
    private NomadStatusService nomadStatusService;

    @GetMapping("/{jobId}")
    public Map<String, Object> getJobStatus(@PathVariable("jobId") String jobId) {
        return nomadStatusService.getJobDiagnostics(jobId);
    }
}