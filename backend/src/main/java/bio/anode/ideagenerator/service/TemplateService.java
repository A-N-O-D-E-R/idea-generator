package bio.anode.ideagenerator.service;

import bio.anode.ideagenerator.AppError;
import bio.anode.ideagenerator.model.Board.Template;
import bio.anode.ideagenerator.repository.TemplateRepository;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/** The tenant's flow templates. */
@Service
@RequiredArgsConstructor
public class TemplateService {
    /** What a template is made of. */
    public record Draft(String name, String system, String expand, String combine) {
        void validate() {
            if (name.isEmpty() || system.isEmpty() || expand.isEmpty() || combine.isEmpty()) {
                throw new AppError(400, "bad_request", "A template needs a name and all three prompts");
            }
        }
    }

    private final TemplateRepository templates;

    public List<Template> list(String t) {
        return templates.list(t);
    }

    public Template create(String t, Draft d) {
        d.validate();
        return templates.create(t, d.name(), d.system(), d.expand(), d.combine());
    }

    public Template update(String t, long id, Draft d) {
        d.validate();
        Template updated = templates.update(t, id, d.name(), d.system(), d.expand(), d.combine());
        if (updated == null) throw new AppError(404, "not_found", "Template not found");
        return updated;
    }

    public void delete(String t, long id) {
        if (templates.find(t, id) == null) throw new AppError(404, "not_found", "Template not found");
        if (!templates.delete(t, id)) throw new AppError(400, "last_template", "The last template cannot be deleted");
    }
}
