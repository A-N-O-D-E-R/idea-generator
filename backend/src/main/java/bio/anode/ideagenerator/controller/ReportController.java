package bio.anode.ideagenerator.controller;

import static bio.anode.ideagenerator.controller.Requests.MARKDOWN;
import static bio.anode.ideagenerator.controller.Requests.body;
import static bio.anode.ideagenerator.controller.Requests.days;
import static bio.anode.ideagenerator.controller.Requests.lang;

import bio.anode.ideagenerator.Js;
import bio.anode.ideagenerator.service.ReportService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

/** Keyword trends (dashboard) and the report, across all boards. */
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
class ReportController {
    private final ReportService reports;
    private final CurrentTenant tenant;

    @GetMapping("/dashboard")
    Object dashboard(@RequestParam(required = false) String days) {
        return reports.dashboard(tenant.id(), days(days));
    }

    @GetMapping("/keywords/{kw}/ideas")
    Object keywordIdeas(@PathVariable String kw) {
        return Js.obj("ideas", reports.ideasWithKeyword(tenant.id(), kw));
    }

    @GetMapping("/report")
    Object report(@RequestParam(required = false) String days) {
        return reports.report(tenant.id(), days(days));
    }

    @PostMapping("/report/synthesis")
    Object synthesis(@RequestBody(required = false) byte[] raw) {
        JsonNode b = body(raw);
        return reports.synthesize(tenant.id(), days(Js.num(b.path("days"))), lang(b.path("lang")));
    }

    @GetMapping("/report.md")
    ResponseEntity<String> markdown(@RequestParam(required = false) String days, @RequestParam(required = false) String lang) {
        return ResponseEntity.ok().contentType(MARKDOWN).body(reports.markdown(tenant.id(), days(days), lang(lang)));
    }
}
