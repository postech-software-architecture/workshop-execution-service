package com.postech.foundation;

import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.FindAndReplaceOptions;
import org.springframework.data.mongodb.core.query.Query;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class ExecutionTest {

	final MongoTemplate db = mock(MongoTemplate.class);

	final MongoFoundation outbox = mock(MongoFoundation.class);

	final com.fasterxml.jackson.databind.ObjectMapper json = new com.fasterxml.jackson.databind.ObjectMapper();

	final Execution service = new Execution(db, outbox, json);

	final UUID id = UUID.randomUUID();

	Envelope command(String type) {
		return new Envelope(UUID.randomUUID(), type, 1, Instant.now(), "os-service", id, id, id, id, null, id, 1,
				json.valueToTree(type.contains("solicitar") ? Map.of("trabalhoId", id, "solicitacao", "Revisao")
						: Map.of("trabalhoId", id)));
	}

	void writeSucceeds() {
		when(db.updateFirst(any(Query.class), any(org.springframework.data.mongodb.core.query.Update.class),
				eq(Work.class)))
			.thenReturn(com.mongodb.client.result.UpdateResult.acknowledged(1, 1L, null));
	}

	@Test
	void firstDiagnosticCommandCreatesWorkAndAdmissionEvent() {
		service.accept(command("execucao.solicitar-diagnostico.v1"));
		verify(db).insert(any(Work.class));
		verify(outbox).emit(argThat(e -> e.messageType().equals("execucao.diagnostico-admitido.v1")));
	}

	@Test
	void existingDiagnosticWithNewMessageIdDoesNotCreateSecondWork() {
		var work = Work.diagnostic(command("execucao.solicitar-diagnostico.v1"));
		when(db.findOne(any(Query.class), eq(Work.class))).thenReturn(work);
		service.accept(command("execucao.solicitar-diagnostico.v1"));
		verify(db, never()).insert(any(Work.class));
		verifyNoInteractions(outbox);
	}

	@Test
	void technicalApiChangesPersistVersionBeforePublishingDiagnosticProgress() {
		writeSucceeds();
		var pending = Work.diagnostic(command("execucao.solicitar-diagnostico.v1"));
		when(db.findById(id.toString(), Work.class)).thenReturn(pending);
		var started = service.change(id, 1, "START_DIAGNOSTIC", null, "mechanic");
		when(db.findById(id.toString(), Work.class)).thenReturn(started);
		var done = service.change(id, 2, "FINISH_DIAGNOSTIC", "Resultado", "mechanic");
		org.junit.jupiter.api.Assertions.assertEquals("DIAGNOSTICO_CONCLUIDO", done.status());
		verify(outbox).emit(argThat(e -> e.messageType().equals("execucao.diagnostico-iniciado.v1")));
		verify(outbox).emit(argThat(e -> e.payload().path("diagnostico").asText().equals("Resultado")));
	}

	Envelope authorized() {
		var payload = json.valueToTree(Map.of("trabalhoId", id, "orcamentoId", id, "orcamentoVersao", 1, "pagamentoId",
				id, "itensAutorizados", List.of(Map.of("itemId", id, "tipo", "SERVICO", "descricao", "Revisao"))));
		return new Envelope(UUID.randomUUID(), "execucao.enfileirar-reparo.v1", 1, Instant.now(), "os-service", id, id,
				id, id, null, id, 1, payload);
	}

	@Test
	void authorizedRepairRunsThroughServiceAndProducesOnlyGlobalBoundaryEvents() {
		writeSucceeds();
		var work = Work.diagnostic(command("execucao.solicitar-diagnostico.v1"))
			.startDiagnostic("m")
			.finishDiagnostic("Resultado", "m")
			.enqueue(authorized());
		for (var action : List.of("START_REPAIR", "START_ITEM", "FINISH_ITEM", "FINISH_REPAIR")) {
			when(db.findById(id.toString(), Work.class)).thenReturn(work);
			work = service.change(id, work.version(), action, action.contains("ITEM") ? id.toString() : null,
					"mechanic");
		}
		org.junit.jupiter.api.Assertions.assertEquals("CONCLUIDO", work.status());
		verify(outbox, times(2)).emit(any());
	}

	@Test
	void cancellationAfterRepairStartReturnsExplicitRefusalWithoutMutation() {
		var work = Work.diagnostic(command("execucao.solicitar-diagnostico.v1"))
			.startDiagnostic("m")
			.finishDiagnostic("Resultado", "m")
			.enqueue(authorized())
			.startRepair("m");
		when(db.findOne(any(Query.class), eq(Work.class))).thenReturn(work);
		service.accept(command("execucao.cancelar-trabalho.v1"));
		verify(outbox).emit(argThat(e -> e.messageType().equals("execucao.cancelamento-recusado.v1")));
		verify(db, never()).updateFirst(any(Query.class), any(org.springframework.data.mongodb.core.query.Update.class),
				eq(Work.class));
	}

	@Test
	void authorizedAdmissionAndPreStartCancellationArePersistedAndPublished() {
		writeSucceeds();
		var diagnosed = Work.diagnostic(command("execucao.solicitar-diagnostico.v1"))
			.startDiagnostic("m")
			.finishDiagnostic("Resultado", "m");
		when(db.findOne(any(Query.class), eq(Work.class))).thenReturn(diagnosed);
		service.accept(authorized());
		verify(outbox).emit(argThat(e -> e.messageType().equals("execucao.reparo-enfileirado.v1")));
		service.accept(command("execucao.cancelar-trabalho.v1"));
		verify(outbox).emit(argThat(e -> e.messageType().equals("execucao.trabalho-cancelado.v1")));
	}

	@Test
	void queueAndMissingResourceHaveBoundedReadContracts() {
		assertThrows(IllegalArgumentException.class, () -> service.queue("invalid", 0));
		assertThrows(IllegalArgumentException.class, () -> service.queue("CONCLUIDO", -1));
		assertThrows(org.springframework.web.server.ResponseStatusException.class, () -> service.find("missing"));
		when(db.find(any(Query.class), eq(Work.class))).thenReturn(List.of());
		org.junit.jupiter.api.Assertions.assertTrue(service.queue("DIAGNOSTICO_PENDENTE", 1).isEmpty());
	}

	@Test
	void repeatedMessageStopsBeforeMutation() {
		when(db.exists(any(Query.class), eq("inbox"))).thenReturn(true);
		service.accept(command("execucao.solicitar-diagnostico.v1"));
		verify(db, never()).insert(any(Object.class), anyString());
		verifyNoInteractions(outbox);
	}

	@Test
	void lateCreateCannotResurrectTombstone() {
		var tombstone = Work.tombstone(command("execucao.cancelar-trabalho.v1"));
		when(db.findOne(any(Query.class), eq(Work.class))).thenReturn(tombstone);
		service.accept(command("execucao.solicitar-diagnostico.v1"));
		verify(db, never()).insert(any(Work.class));
		verifyNoInteractions(outbox);
	}

	@Test
	void cancelBeforeCreatePersistsTombstoneAndResponse() {
		service.accept(command("execucao.cancelar-trabalho.v1"));
		verify(db).insert(argThat((Work work) -> work.status().equals("CANCELADO")));
		verify(outbox).emit(argThat(e -> e.messageType().equals("execucao.trabalho-cancelado.v1")));
	}

	@Test
	void staleVersionCannotChangeWork() {
		when(db.findById(id.toString(), Work.class))
			.thenReturn(Work.diagnostic(command("execucao.solicitar-diagnostico.v1")));
		assertThrows(org.springframework.web.server.ResponseStatusException.class,
				() -> service.change(id, 99, "START_DIAGNOSTIC", null, "mechanic"));
		verifyNoInteractions(outbox);
	}

	@Test
	void losingConcurrentTransitionCannotPublishEvent() {
		when(db.findById(id.toString(), Work.class))
			.thenReturn(Work.diagnostic(command("execucao.solicitar-diagnostico.v1")));
		when(db.updateFirst(any(Query.class), any(org.springframework.data.mongodb.core.query.Update.class),
				eq(Work.class)))
			.thenReturn(com.mongodb.client.result.UpdateResult.acknowledged(0, 0L, null));
		assertThrows(org.springframework.dao.OptimisticLockingFailureException.class,
				() -> service.change(id, 1, "START_DIAGNOSTIC", null, "mechanic"));
		verifyNoInteractions(outbox);
	}

}
