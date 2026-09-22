package com.github.h3yow.tenantquotagatewayjava.web;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 受保护的示例业务接口：通过限流过滤器后回显请求信息。
 */
@RestController
@RequestMapping("/api")
public class DemoController {

    @GetMapping("/echo")
    public Map<String, Object> echo(HttpServletRequest request) {
        return Map.of(
                "status", "ok",
                "requestId", request.getAttribute("requestId"),
                "tenantId", request.getAttribute("tenantId"));
    }
}
