package com.postech.foundation;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.*;
import org.bson.Document;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.FindAndReplaceOptions;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class Execution implements Handler {

	private final MongoTemplate db;

	private final MongoFoundation outbox;

	private final ObjectMapper json;

	public Execution(MongoTemplate db, MongoFoundation outbox, ObjectMapper json) {
		this.db = db;
		this.outbox = outbox;
		this.json = json;
	}

	@Transactional
	public void accept(Envelope e) {
		e.validate("execution");
		if (db.exists(Query.query(Criteria.where("_id").is(e.messageId().toString())), "inbox"))
			return;
		db.insert(new Document("_id", e.messageId().toString()), "inbox");
		Work prior = db.findOne(
				Query.query(
						Criteria.where("osId").is(e.osId().toString()).and("attemptId").is(e.attemptId().toString())),
				Work.class);
		if (prior != null)
			prior.context(e);
		switch (e.messageType()) {
			case "execucao.solicitar-diagnostico.v1" -> {
				if (prior != null) {
					if (!prior.status().equals("CANCELADO")
							&& !Objects.equals(prior.request(), e.payload().path("solicitacao").asText()))
						throw new IllegalArgumentException("BUSINESS_KEY_CONFLICT");
					return;
				}
				Work work = Work.diagnostic(e);
				db.insert(work);
				publish(work, "execucao.diagnostico-admitido.v1", e.messageId(), Map.of("trabalhoId", work.id()));
			}
			case "execucao.cancelar-trabalho.v1" -> {
				if (prior == null) {
					Work tombstone = Work.tombstone(e);
					db.insert(tombstone);
					publish(tombstone, "execucao.trabalho-cancelado.v1", e.messageId(),
							Map.of("trabalhoId", tombstone.id()));
					return;
				}
				if (!prior.canCancel()) {
					publish(prior, "execucao.cancelamento-recusado.v1", e.messageId(),
							Map.of("trabalhoId", prior.id(), "codigo", "REPARO_JA_INICIADO"));
					return;
				}
				Work next = prior.cancel(e.producer());
				if (next != prior)
					save(prior, next);
				publish(next, "execucao.trabalho-cancelado.v1", e.messageId(), Map.of("trabalhoId", next.id()));
			}
			case "execucao.enfileirar-reparo.v1" -> {
				if (prior == null)
					throw new IllegalStateException("DIAGNOSTIC_NOT_AVAILABLE");
				if (prior.status().equals("CANCELADO")) {
					publish(prior, "execucao.enfileiramento-recusado.v1", e.messageId(),
							Map.of("trabalhoId", prior.id(), "operationId", e.messageId(), "codigo",
									"ATTEMPT_CANCELLED", "retriavel", false));
					return;
				}
				Work next = prior.enqueue(e);
				if (next != prior) {
					save(prior, next);
					publish(next, "execucao.reparo-enfileirado.v1", e.messageId(), Map.of("trabalhoId", next.id()));
				}
			}
			default -> throw new IllegalArgumentException("UNSUPPORTED_MESSAGE");
		}
	}

	public Work find(String id) {
		Work work = db.findById(id, Work.class);
		if (work == null)
			throw new ResponseStatusException(HttpStatus.NOT_FOUND);
		return work;
	}

	public List<Work> queue(String status, int page) {
		if (!Set
			.of("DIAGNOSTICO_PENDENTE", "EM_DIAGNOSTICO", "DIAGNOSTICO_CONCLUIDO", "REPARO_PENDENTE", "EM_REPARO",
					"CONCLUIDO", "CANCELADO")
			.contains(status) || page < 0 || page > 100000)
			throw new IllegalArgumentException("INVALID_QUEUE");
		return db.find(Query.query(Criteria.where("status").is(status))
			.with(Sort.by("createdAt", "_id"))
			.skip(page * 20L)
			.limit(20), Work.class);
	}

	@Transactional
	public Work change(UUID id, long version, String action, String value, String actor) {
		Work prior = find(id.toString());
		if (prior.version() != version)
			throw new ResponseStatusException(HttpStatus.CONFLICT, "STALE_VERSION");
		Work next = switch (action) {
			case "START_DIAGNOSTIC" -> prior.startDiagnostic(actor);
			case "FINISH_DIAGNOSTIC" -> prior.finishDiagnostic(value, actor);
			case "START_REPAIR" -> prior.startRepair(actor);
			case "FINISH_REPAIR" -> prior.finishRepair(actor);
			case "START_ITEM" -> prior.task(value, false, actor);
			case "FINISH_ITEM" -> prior.task(value, true, actor);
			default -> throw new IllegalArgumentException("INVALID_ACTION");
		};
		save(prior, next);
		String event = switch (action) {
			case "START_DIAGNOSTIC" -> "execucao.diagnostico-iniciado.v1";
			case "FINISH_DIAGNOSTIC" -> "execucao.diagnostico-concluido.v1";
			case "START_REPAIR" -> "execucao.reparo-iniciado.v1";
			case "FINISH_REPAIR" -> "execucao.reparo-concluido.v1";
			default -> null;
		};
		if (event != null)
			publish(next, event, null,
					action.equals("FINISH_DIAGNOSTIC")
							? Map.of("trabalhoId", next.id(), "diagnostico", next.diagnostic())
							: Map.of("trabalhoId", next.id()));
		return next;
	}

	private void save(Work prior, Work next) {
		var changes = new org.springframework.data.mongodb.core.query.Update().set("status", next.status())
			.set("version", next.version())
			.set("diagnostic", next.diagnostic())
			.set("mechanicId", next.mechanicId())
			.set("budgetId", next.budgetId())
			.set("budgetVersion", next.budgetVersion())
			.set("paymentId", next.paymentId())
			.set("tasks", next.tasks())
			.set("history", next.history());
		var saved = db.updateFirst(Query.query(Criteria.where("_id").is(prior.id()).and("version").is(prior.version())),
				changes, Work.class);
		if (saved.getModifiedCount() != 1)
			throw new org.springframework.dao.OptimisticLockingFailureException("CONCURRENT_WORK_CHANGE");
	}

	private void publish(Work work, String type, UUID cause, Object payload) {
		outbox.emit(new Envelope(UUID.randomUUID(), type, 1, Instant.now(), "execucao-service",
				UUID.fromString(work.osId()), UUID.fromString(work.sagaId()), UUID.fromString(work.attemptId()),
				UUID.fromString(work.correlationId()), cause, UUID.fromString(work.id()), work.version(),
				json.valueToTree(payload)));
	}

}
