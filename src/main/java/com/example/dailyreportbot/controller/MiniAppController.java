package com.example.dailyreportbot.controller;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
public class MiniAppController {

    @GetMapping({"/miniapp", "/miniapp/"})
    public String miniApp() {
        return "forward:/miniapp/index.html";
    }
}
