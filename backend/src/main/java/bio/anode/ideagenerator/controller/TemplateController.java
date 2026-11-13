package bio.anode.ideagenerator.controller;

import static bio.anode.ideagenerator.controller.Requests.body;

import bio.anode.ideagenerator.Js;
import bio.anode.ideagenerator.service.TemplateService;
import bio.anode.ideagenerator.service.TemplateService.Draft;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

/** Flow templates. */
@RestController
@RequestMapping("/api/templates")
@RequiredArgsConstructor
class TemplateController {
    private final TemplateService templates;
    private final CurrentTenant tenant;

    private static Draft draft(JsonNode b) {
        return new Draft(Js.str(b.path("name"), 80), Js.str(b.path("system"), 4000), Js.str(b.path("expand"), 4000), Js.str(b.path("combine"), 4000));
    }

    @GetMapping
    Object list() {
        return Js.obj("templates", templates.list(tenant.id()));
    }

    @PostMapping
    Object create(@RequestBody(required = false) byte[] raw) {
        return Js.obj("template", templates.create(tenant.id(), draft(body(raw))));
    }

    @PutMapping("/{id:\\d+}")
    Object update(@PathVariable long id, @RequestBody(required = false) byte[] raw) {
        return Js.obj("template", templates.update(tenant.id(), id, draft(body(raw))));
    }

    @DeleteMapping("/{id:\\d+}")
    Object delete(@PathVariable long id) {
        templates.delete(tenant.id(), id);
        return Js.obj("ok", true);
    }
}
