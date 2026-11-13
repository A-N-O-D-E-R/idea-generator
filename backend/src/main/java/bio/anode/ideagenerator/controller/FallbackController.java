package bio.anode.ideagenerator.controller;

import bio.anode.ideagenerator.AppError;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Anything else under /api. */
@RestController
class FallbackController {
    @io.swagger.v3.oas.annotations.Hidden
    @RequestMapping("/api/**")
    Object noRoute(HttpServletRequest req) {
        throw new AppError(404, "not_found", "No route for " + req.getMethod() + " " + req.getRequestURI());
    }
}
