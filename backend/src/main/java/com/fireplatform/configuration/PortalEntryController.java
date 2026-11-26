package com.fireplatform.configuration;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
public class PortalEntryController {
    @GetMapping({"/management", "/management/", "/management/login", "/management/login/",
                 "/customer", "/customer/", "/customer/login", "/customer/login/"})
    public String portal() {
        return "forward:/index.html";
    }
}
