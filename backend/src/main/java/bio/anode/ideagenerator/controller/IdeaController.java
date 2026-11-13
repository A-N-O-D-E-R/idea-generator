package bio.anode.ideagenerator.controller;

import static bio.anode.ideagenerator.controller.Requests.body;
import static bio.anode.ideagenerator.controller.Requests.longOrNull;

import bio.anode.ideagenerator.AppError;
import bio.anode.ideagenerator.Project;
import bio.anode.ideagenerator.service.IdeaService;
import bio.anode.ideagenerator.service.JudgeService;
import bio.anode.ideagenerator.service.ProjectService;
import java.nio.file.Path;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ContentDisposition;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;
import tools.jackson.databind.JsonNode;

/** Ideas: seed, expand, combine, judge, medals, projects, announce, spin-offs, the AI Scientist. */
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
class IdeaController {
    private final IdeaService ideas;
    private final JudgeService judge;
    private final ProjectService projects;
    private final CurrentTenant tenant;

    @PostMapping("/boards/{id:\\d+}/seed")
    Object seed(@PathVariable long id, @RequestBody(required = false) byte[] raw) {
        return ideas.seed(tenant.id(), id, body(raw));
    }

    @PostMapping("/boards/{id:\\d+}/combine")
    Object combine(@PathVariable long id, @RequestBody(required = false) byte[] raw) {
        JsonNode b = body(raw);
        return ideas.combine(tenant.id(), id, longOrNull(b.path("a")), longOrNull(b.path("b")));
    }

    @PostMapping("/ideas/{id:\\d+}/expand")
    Object expand(@PathVariable long id, @RequestBody(required = false) byte[] raw) {
        body(raw);
        return ideas.expand(tenant.id(), id);
    }

    @PostMapping("/ideas/{id:\\d+}/judge")
    Object judge(@PathVariable long id, @RequestBody(required = false) byte[] raw) {
        body(raw);
        return judge.judge(tenant.id(), id);
    }

    @PatchMapping("/ideas/{id:\\d+}")
    Object patch(@PathVariable long id, @RequestBody(required = false) byte[] raw) {
        JsonNode b = body(raw);
        if (!b.has("medalOverride")) throw new AppError(400, "bad_request", "Nothing to change");
        return ideas.setMedal(tenant.id(), id, b.get("medalOverride"));
    }

    @DeleteMapping("/ideas/{id:\\d+}")
    Object delete(@PathVariable long id) {
        return ideas.delete(tenant.id(), id);
    }

    @PostMapping("/ideas/{id:\\d+}/project")
    Object project(@PathVariable long id, @RequestBody(required = false) byte[] raw) {
        body(raw);
        return projects.create(tenant.id(), id);
    }

    /** The idea's project folder as a zip (on a shared server the only way to get it). */
    @GetMapping("/ideas/{id:\\d+}/project.zip")
    ResponseEntity<StreamingResponseBody> projectZip(@PathVariable long id) {
        Path dir = projects.dir(tenant.id(), id);
        return ResponseEntity.ok().contentType(MediaType.parseMediaType("application/zip"))
                .header("Content-Disposition", ContentDisposition.attachment().filename(dir.getFileName() + ".zip").build().toString())
                .body(out -> Project.zip(dir, out));
    }

    @PostMapping("/ideas/{id:\\d+}/reveal")
    Object reveal(@PathVariable long id, @RequestBody(required = false) byte[] raw) {
        body(raw);
        return projects.reveal(tenant.id(), id);
    }

    @PostMapping("/ideas/{id:\\d+}/announce")
    Object announce(@PathVariable long id, @RequestBody(required = false) byte[] raw) {
        return ideas.announce(tenant.id(), id, body(raw).path("boardIds"));
    }

    @PostMapping("/ideas/{id:\\d+}/scientist")
    Object scientist(@PathVariable long id, @RequestBody(required = false) byte[] raw) {
        body(raw);
        return ideas.toScientist(tenant.id(), id);
    }

    @PostMapping("/ideas/{id:\\d+}/spinoff")
    Object spinoff(@PathVariable long id, @RequestBody(required = false) byte[] raw) {
        body(raw);
        return ideas.spinoff(tenant.id(), id);
    }
}
