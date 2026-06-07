package com.notificationplatform.web.controller;

import com.notificationplatform.application.management.CreateTemplateCommand;
import com.notificationplatform.application.management.TemplateManagementService;
import com.notificationplatform.web.dto.CreateTemplateRequest;
import com.notificationplatform.web.dto.TemplateResponse;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/admin/templates")
public class AdminTemplateController {

    private final TemplateManagementService templateManagementService;

    public AdminTemplateController(TemplateManagementService templateManagementService) {
        this.templateManagementService = templateManagementService;
    }

    @GetMapping
    public List<TemplateResponse> listTemplates(@RequestParam UUID productId) {
        return templateManagementService.listTemplates(productId).stream()
            .map(TemplateResponse::from)
            .toList();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public TemplateResponse createTemplate(@Valid @RequestBody CreateTemplateRequest request) {
        return TemplateResponse.from(templateManagementService.createTemplate(new CreateTemplateCommand(
            request.productId(),
            request.templateKey(),
            request.channel(),
            request.version(),
            request.subject(),
            request.content(),
            request.status()
        )));
    }
}
