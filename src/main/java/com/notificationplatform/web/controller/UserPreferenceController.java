package com.notificationplatform.web.controller;

import com.notificationplatform.application.preferences.SetUserPreferenceCommand;
import com.notificationplatform.application.preferences.UserPreferenceService;
import com.notificationplatform.web.dto.PreferenceResponse;
import com.notificationplatform.web.dto.SetPreferenceRequest;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/users/{userId}/preferences")
public class UserPreferenceController {

    private final UserPreferenceService userPreferenceService;

    public UserPreferenceController(UserPreferenceService userPreferenceService) {
        this.userPreferenceService = userPreferenceService;
    }

    @GetMapping
    public List<PreferenceResponse> listPreferences(
        @PathVariable String userId,
        @RequestParam UUID productId
    ) {
        return userPreferenceService.listPreferences(productId, userId).stream()
            .map(PreferenceResponse::from)
            .toList();
    }

    @PutMapping
    public PreferenceResponse setPreference(
        @PathVariable String userId,
        @Valid @RequestBody SetPreferenceRequest request
    ) {
        return PreferenceResponse.from(userPreferenceService.setPreference(new SetUserPreferenceCommand(
            request.productId(),
            userId,
            request.category(),
            request.channel(),
            request.enabled()
        )));
    }
}
