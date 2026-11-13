package bio.anode.ideagenerator.controller;

import static bio.anode.ideagenerator.controller.Requests.body;

import bio.anode.ideagenerator.Js;
import bio.anode.ideagenerator.service.LeadService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Leads from each board's scouts. */
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
class LeadController {
    private final LeadService leads;
    private final CurrentTenant tenant;

    @PostMapping("/boards/{id:\\d+}/leads/{source:x|research}")
    Object refresh(@PathVariable long id, @PathVariable String source, @RequestBody(required = false) byte[] raw) {
        body(raw);
        return leads.refresh(tenant.id(), id, source);
    }

    @PostMapping("/leads/{id:\\d+}/seed")
    Object seed(@PathVariable long id, @RequestBody(required = false) byte[] raw) {
        return leads.seed(tenant.id(), id, body(raw));
    }

    @DeleteMapping("/leads/{id:\\d+}")
    Object delete(@PathVariable long id) {
        return Js.obj("ok", leads.delete(tenant.id(), id));
    }
}
