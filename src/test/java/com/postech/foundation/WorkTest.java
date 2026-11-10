package com.postech.foundation;

import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class WorkTest {

	final UUID os = UUID.randomUUID(), attempt = UUID.randomUUID(), saga = UUID.randomUUID(),
			workId = UUID.randomUUID();

	final com.fasterxml.jackson.databind.ObjectMapper json = new com.fasterxml.jackson.databind.ObjectMapper();

	Envelope message(String type, Map<String, Object> payload) {
		return new Envelope(UUID.randomUUID(), type, 1, Instant.now(), "os-service", os, saga, attempt, os, null, os, 1,
				json.valueToTree(payload));
	}

	Envelope diagnostic() {
		return message("execucao.solicitar-diagnostico.v1", Map.of("trabalhoId", workId, "solicitacao", "Revisao"));
	}

	Envelope authorization(UUID budget, UUID payment, List<Map<String, Object>> items) {
		return message("execucao.enfileirar-reparo.v1", Map.of("trabalhoId", workId, "orcamentoId", budget,
				"orcamentoVersao", 1, "pagamentoId", payment, "itensAutorizados", items));
	}

	Work diagnosed() {
		return Work.diagnostic(diagnostic()).startDiagnostic("mechanic").finishDiagnostic("Trocar oleo", "mechanic");
	}

	List<Map<String, Object>> items() {
		return List.of(Map.of("itemId", UUID.randomUUID(), "tipo", "SERVICO", "descricao", "Troca de oleo"),
				Map.of("itemId", UUID.randomUUID(), "tipo", "INSUMO", "descricao", "Oleo"));
	}

	@Test
	void diagnosticRequiresStartAndNonblankConclusion() {
		var work = Work.diagnostic(diagnostic());
		assertThrows(IllegalArgumentException.class, () -> work.finishDiagnostic("x", "m"));
		var started = work.startDiagnostic("m");
		assertThrows(IllegalArgumentException.class, () -> started.finishDiagnostic(" ", "m"));
		assertEquals("DIAGNOSTICO_CONCLUIDO", started.finishDiagnostic("Resultado", "m").status());
	}

	@Test
	void repairRequiresPaymentAuthorization() {
		assertThrows(IllegalArgumentException.class, () -> diagnosed().startRepair("m"));
	}

	@Test
	void givenAuthorizedWorkWhenAllServiceItemsCompleteThenRepairCompletes() {
		var work = diagnosed().enqueue(authorization(UUID.randomUUID(), UUID.randomUUID(), items())).startRepair("m");
		assertThrows(IllegalArgumentException.class, () -> work.finishRepair("m"));
		String item = work.tasks().getFirst().itemId();
		var result = work.task(item, false, "m").task(item, true, "m").finishRepair("m");
		assertEquals("CONCLUIDO", result.status());
		assertEquals(8, result.version());
		assertThrows(IllegalArgumentException.class, () -> result.finishRepair("m"));
	}

	@Test
	void cancellationBeforeCreationPreventsDelayedAdmission() {
		var tombstone = Work.tombstone(message("execucao.cancelar-trabalho.v1", Map.of("trabalhoId", workId)));
		assertThrows(IllegalArgumentException.class,
				() -> tombstone.enqueue(authorization(UUID.randomUUID(), UUID.randomUUID(), items())));
		assertThrows(IllegalArgumentException.class, () -> tombstone.startDiagnostic("m"));
	}

	@Test
	void cancellationAndRepairStartAreMutuallyExclusive() {
		var queued = diagnosed().enqueue(authorization(UUID.randomUUID(), UUID.randomUUID(), items()));
		assertThrows(IllegalArgumentException.class, () -> queued.cancel("system").startRepair("m"));
		assertFalse(queued.startRepair("m").canCancel());
		assertThrows(IllegalArgumentException.class, () -> queued.startRepair("m").cancel("system"));
	}

	@Test
	void admissionIsIdempotentButConflictingAuthorizationFails() {
		var command = authorization(UUID.randomUUID(), UUID.randomUUID(), items());
		var queued = diagnosed().enqueue(command);
		assertSame(queued, queued.enqueue(command));
		assertThrows(IllegalArgumentException.class,
				() -> queued.enqueue(authorization(UUID.randomUUID(), UUID.randomUUID(), items())));
	}

	@Test
	void onlyOneServiceCanRunAndPartsHaveNoTaskTransition() {
		var tasks = new ArrayList<>(items());
		tasks.add(Map.of("itemId", UUID.randomUUID(), "tipo", "SERVICO", "descricao", "Revisar freios"));
		var working = diagnosed().enqueue(authorization(UUID.randomUUID(), UUID.randomUUID(), tasks)).startRepair("m");
		var one = working.task(working.tasks().getFirst().itemId(), false, "m");
		assertThrows(IllegalArgumentException.class, () -> one.task(one.tasks().get(2).itemId(), false, "m"));
		assertThrows(IllegalArgumentException.class, () -> one.task(one.tasks().get(1).itemId(), false, "m"));
	}

	@Test
	void wrongAttemptAndDuplicateItemsFail() {
		var command = authorization(UUID.randomUUID(), UUID.randomUUID(), List.of(items().getFirst()));
		var payload = json.convertValue(command.payload(),
				new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {
				});
		payload.put("trabalhoId", UUID.randomUUID());
		assertThrows(IllegalArgumentException.class,
				() -> diagnosed().enqueue(message(command.messageType(), payload)));
		var item = items().getFirst();
		assertThrows(IllegalArgumentException.class,
				() -> diagnosed().enqueue(authorization(UUID.randomUUID(), UUID.randomUUID(), List.of(item, item))));
	}

}
