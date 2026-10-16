package com.lastseat.booking.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class WebConfig implements WebMvcConfigurer {
    @Override
    public void addCorsMappings(CorsRegistry registry) {
        // The bundled frontend is served from the same origin; CORS is opened so a
        // separately hosted frontend (or k6/Postman) can call the API as well.
        registry.addMapping("/api/**").allowedOrigins("*").allowedMethods("*");
    }
}
