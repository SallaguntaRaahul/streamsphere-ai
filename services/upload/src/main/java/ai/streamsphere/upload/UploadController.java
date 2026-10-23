package ai.streamsphere.upload;

import ai.streamsphere.common.storage.StorageProvider.Part;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/uploads")
public class UploadController {
    public record CreateUpload(@NotNull UUID titleId, @NotBlank @Size(max = 255) String fileName, @NotBlank String contentType,
                               @Min(1) long sizeBytes, @NotBlank @Pattern(regexp = "[a-f0-9]{64}") String sha256) {}
    public record ClientPart(@Min(1) @Max(10000) int partNumber, @NotBlank String etag) {}
    public record CompleteUpload(@NotEmpty @Size(max = 10000) List<@Valid ClientPart> parts) {}
    public record SessionView(UploadSession session, List<Part> uploadedParts) {}

    private final UploadService uploads;
    private final CostGuard cost;

    public UploadController(UploadService uploads, CostGuard cost) {
        this.uploads = uploads;
        this.cost = cost;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public UploadSession create(@Valid @RequestBody CreateUpload r,
                                @RequestHeader(value = "Idempotency-Key", required = false) @Size(max = 100) String key) {
        return uploads.create(new UploadService.CreateRequest(r.titleId(), r.fileName(), r.contentType(), r.sizeBytes(), r.sha256()), key);
    }

    @GetMapping
    public List<UploadSession> mine() { return uploads.mine(); }

    @GetMapping("/{id}")
    public SessionView get(@PathVariable UUID id) { return new SessionView(uploads.get(id), uploads.uploadedParts(id)); }

    @PostMapping("/{id}/parts/{partNumber}/presign")
    public UploadService.PresignedPart presign(@PathVariable UUID id, @PathVariable int partNumber) {
        return uploads.presignPart(id, partNumber);
    }

    @PostMapping("/{id}/complete")
    public UploadSession complete(@PathVariable UUID id, @Valid @RequestBody CompleteUpload r) {
        return uploads.complete(id, r.parts().stream().map(p -> new Part(p.partNumber(), p.etag(), 0)).toList());
    }

    @DeleteMapping("/{id}")
    public UploadSession cancel(@PathVariable UUID id) { return uploads.cancel(id); }

    @GetMapping("/quota")
    public CostGuard.Usage quota() { return cost.usage(); }

    @GetMapping("/admin/cost")
    @PreAuthorize("hasRole('admin')")
    public CostGuard.Usage adminCost() { return cost.usage(); }
}
