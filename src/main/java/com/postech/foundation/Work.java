package com.postech.foundation;

import java.time.Instant;
import java.util.*;
import org.springframework.data.annotation.Id;

@org.springframework.data.mongodb.core.mapping.Document("works")
public record Work(@Id String id, String osId, String attemptId, String sagaId, String correlationId, String status,
		String request, long version, String diagnostic, String mechanicId, String budgetId, int budgetVersion,
		String paymentId, List<Task> tasks, List<Change> history, Instant createdAt) {
	public record Task(String itemId, String tipo, String descricao, String status, Instant startedAt,
			Instant finishedAt) {
	}

	public record Change(String action, String actor, Instant at) {
	}

	public Work {
		tasks = List.copyOf(tasks);
		history = List.copyOf(history);
	}

	public static Work diagnostic(Envelope e) {
		return new Work(e.payload().path("trabalhoId").asText(), e.osId().toString(), e.attemptId().toString(),
				e.sagaId().toString(), e.correlationId().toString(), "DIAGNOSTICO_PENDENTE",
				e.payload().path("solicitacao").asText(), 1, null, null, null, 0, null, List.of(),
				List.of(new Change("DIAGNOSTICO_ADMITIDO", e.producer(), Instant.now())), Instant.now());
	}

	public static Work tombstone(Envelope e) {
		return new Work(e.payload().path("trabalhoId").asText(), e.osId().toString(), e.attemptId().toString(),
				e.sagaId().toString(), e.correlationId().toString(), "CANCELADO", null, 1, null, null, null, 0, null,
				List.of(), List.of(new Change("CANCELADO", e.producer(), Instant.now())), Instant.now());
	}

	public void context(Envelope e) {
		if (!id.equals(e.payload().path("trabalhoId").asText()) || !osId.equals(e.osId().toString())
				|| !attemptId.equals(e.attemptId().toString()) || !sagaId.equals(e.sagaId().toString()))
			throw new IllegalArgumentException("WORK_CONTEXT_MISMATCH");
	}

	public Work startDiagnostic(String actor) {
		require("DIAGNOSTICO_PENDENTE");
		return change("EM_DIAGNOSTICO", null, actor, budgetId, budgetVersion, paymentId, tasks, "DIAGNOSTICO_INICIADO",
				actor);
	}

	public Work finishDiagnostic(String text, String actor) {
		require("EM_DIAGNOSTICO");
		if (text == null || text.isBlank() || text.length() > 500)
			throw new IllegalArgumentException("INVALID_DIAGNOSTIC");
		return change("DIAGNOSTICO_CONCLUIDO", text.trim(), mechanicId, budgetId, budgetVersion, paymentId, tasks,
				"DIAGNOSTICO_CONCLUIDO", actor);
	}

	public Work enqueue(Envelope e) {
		context(e);
		if (status.equals("CANCELADO"))
			throw new IllegalArgumentException("ATTEMPT_CANCELLED");
		String budget = e.payload().path("orcamentoId").asText(), payment = e.payload().path("pagamentoId").asText();
		int revision = e.payload().path("orcamentoVersao").asInt();
		List<Task> authorized = new ArrayList<>();
		Set<String> ids = new HashSet<>();
		for (var item : e.payload().path("itensAutorizados")) {
			String itemId = item.path("itemId").asText();
			if (!ids.add(itemId))
				throw new IllegalArgumentException("DUPLICATE_ITEM");
			authorized.add(new Task(itemId, item.path("tipo").asText(), item.path("descricao").asText(), "PENDENTE",
					null, null));
		}
		if (authorized.isEmpty())
			throw new IllegalArgumentException("EMPTY_AUTHORIZATION");
		if (budgetId != null) {
			boolean same = budget.equals(budgetId) && payment.equals(paymentId) && revision == budgetVersion
					&& authorized.stream()
						.map(t -> List.of(t.itemId(), t.tipo(), t.descricao()))
						.toList()
						.equals(tasks.stream().map(t -> List.of(t.itemId(), t.tipo(), t.descricao())).toList());
			if (!same)
				throw new IllegalArgumentException("AUTHORIZATION_CONFLICT");
			return this;
		}
		require("DIAGNOSTICO_CONCLUIDO");
		return change("REPARO_PENDENTE", diagnostic, mechanicId, budget, revision, payment, authorized,
				"REPARO_ENFILEIRADO", e.producer());
	}

	public boolean canCancel() {
		return !Set.of("EM_REPARO", "CONCLUIDO").contains(status);
	}

	public Work cancel(String actor) {
		if (!canCancel())
			throw new IllegalArgumentException("REPARO_JA_INICIADO");
		if (status.equals("CANCELADO"))
			return this;
		return change("CANCELADO", diagnostic, mechanicId, budgetId, budgetVersion, paymentId, tasks, "CANCELADO",
				actor);
	}

	public Work startRepair(String actor) {
		require("REPARO_PENDENTE");
		if (paymentId == null || budgetId == null)
			throw new IllegalArgumentException("PAYMENT_AUTHORIZATION_REQUIRED");
		return change("EM_REPARO", diagnostic, actor, budgetId, budgetVersion, paymentId, tasks, "REPARO_INICIADO",
				actor);
	}

	public Work task(String id, boolean finish, String actor) {
		require("EM_REPARO");
		Task target = tasks.stream()
			.filter(t -> t.itemId().equals(id))
			.findFirst()
			.orElseThrow(() -> new IllegalArgumentException("ITEM_NOT_FOUND"));
		if (!target.tipo().equals("SERVICO") || !target.status().equals(finish ? "EM_EXECUCAO" : "PENDENTE"))
			throw new IllegalArgumentException("INVALID_TASK_STATE");
		if (!finish && tasks.stream().anyMatch(t -> t.status().equals("EM_EXECUCAO")))
			throw new IllegalArgumentException("ANOTHER_TASK_IN_PROGRESS");
		var updated = tasks.stream()
			.map(t -> t == target ? new Task(t.itemId(), t.tipo(), t.descricao(), finish ? "FINALIZADO" : "EM_EXECUCAO",
					finish ? t.startedAt() : Instant.now(), finish ? Instant.now() : null) : t)
			.toList();
		return change(status, diagnostic, mechanicId, budgetId, budgetVersion, paymentId, updated,
				finish ? "ITEM_FINALIZADO:" + id : "ITEM_INICIADO:" + id, actor);
	}

	public Work finishRepair(String actor) {
		require("EM_REPARO");
		if (tasks.stream().anyMatch(t -> t.tipo().equals("SERVICO") && !t.status().equals("FINALIZADO")))
			throw new IllegalArgumentException("PENDING_TASKS");
		return change("CONCLUIDO", diagnostic, mechanicId, budgetId, budgetVersion, paymentId, tasks,
				"REPARO_CONCLUIDO", actor);
	}

	private void require(String state) {
		if (!state.equals(status))
			throw new IllegalArgumentException("INVALID_WORK_STATE");
	}

	private Work change(String state, String diagnostic, String mechanic, String budget, int budgetVersion,
			String payment, List<Task> tasks, String action, String actor) {
		var changes = new ArrayList<>(history);
		changes.add(new Change(action, actor, Instant.now()));
		return new Work(id, osId, attemptId, sagaId, correlationId, state, request, version + 1, diagnostic, mechanic,
				budget, budgetVersion, payment, tasks, changes, createdAt);
	}
}
