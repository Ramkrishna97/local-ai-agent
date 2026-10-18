package com.example.newaichatapp.agent.controller;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
@Controller
public class IndexRedirectController {
    @GetMapping("/app") public String app() { return "redirect:/index.html"; }
}
