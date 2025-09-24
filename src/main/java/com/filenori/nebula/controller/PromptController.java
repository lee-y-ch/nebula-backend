package com.filenori.nebula.controller;


import com.filenori.nebula.service.PromptService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
public class PromptController {
    private final PromptService promptService;


    @GetMapping("/hello")
    public String getHello() {
        return promptService.getText();
    }

}
