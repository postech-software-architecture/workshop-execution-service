package com.postech.foundation;

import java.util.*;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/trabalhos")
@PreAuthorize("hasAnyRole('ADMINISTRADOR','MECANICO')")
public class WorkApi {

	private final Execution store;

	public WorkApi(Execution store) {
		this.store = store;
	}

	public record Diagnostic(String diagnostico) {
	}

	@GetMapping("/{id}")
	public Work get(@PathVariable UUID id) {
		return store.find(id.toString());
	}

	@GetMapping
	public List<Work> queue(@RequestParam(defaultValue = "DIAGNOSTICO_PENDENTE") String status,
			@RequestParam(defaultValue = "0") int pagina) {
		return store.queue(status, pagina);
	}

	@PostMapping("/{id}/diagnostico/iniciar")
	public Work startDiagnostic(@PathVariable UUID id, @RequestHeader("If-Match") long version,
			@AuthenticationPrincipal Jwt jwt) {
		return store.change(id, version, "START_DIAGNOSTIC", null, jwt.getSubject());
	}

	@PostMapping("/{id}/diagnostico/concluir")
	public Work finishDiagnostic(@PathVariable UUID id, @RequestHeader("If-Match") long version,
			@RequestBody Diagnostic data, @AuthenticationPrincipal Jwt jwt) {
		return store.change(id, version, "FINISH_DIAGNOSTIC", data.diagnostico(), jwt.getSubject());
	}

	@PostMapping("/{id}/reparo/iniciar")
	public Work startRepair(@PathVariable UUID id, @RequestHeader("If-Match") long version,
			@AuthenticationPrincipal Jwt jwt) {
		return store.change(id, version, "START_REPAIR", null, jwt.getSubject());
	}

	@PostMapping("/{id}/reparo/concluir")
	public Work finishRepair(@PathVariable UUID id, @RequestHeader("If-Match") long version,
			@AuthenticationPrincipal Jwt jwt) {
		return store.change(id, version, "FINISH_REPAIR", null, jwt.getSubject());
	}

	@PostMapping("/{id}/itens/{itemId}/iniciar")
	public Work startItem(@PathVariable UUID id, @PathVariable UUID itemId, @RequestHeader("If-Match") long version,
			@AuthenticationPrincipal Jwt jwt) {
		return store.change(id, version, "START_ITEM", itemId.toString(), jwt.getSubject());
	}

	@PostMapping("/{id}/itens/{itemId}/concluir")
	public Work finishItem(@PathVariable UUID id, @PathVariable UUID itemId, @RequestHeader("If-Match") long version,
			@AuthenticationPrincipal Jwt jwt) {
		return store.change(id, version, "FINISH_ITEM", itemId.toString(), jwt.getSubject());
	}

}
