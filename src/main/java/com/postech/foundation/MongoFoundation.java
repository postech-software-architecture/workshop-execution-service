package com.postech.foundation;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.Date;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.bson.Document;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.index.Index;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class MongoFoundation implements Outbox, ApplicationRunner {

	private final MongoTemplate db;

	private final ObjectMapper json;

	private final TraceHeaders traces;

	public MongoFoundation(MongoTemplate db, ObjectMapper json, TraceHeaders traces) {
		this.db = db;
		this.json = json;
		this.traces = traces;
	}

	public void run(ApplicationArguments args) {
		for (String collection : new String[] { "works", "inbox", "outbox" })
			if (!db.collectionExists(collection))
				db.createCollection(collection);
		db.indexOps("works")
			.ensureIndex(new Index().on("osId", Sort.Direction.ASC).on("attemptId", Sort.Direction.ASC).unique());
		db.indexOps("works")
			.ensureIndex(new Index().on("status", Sort.Direction.ASC).on("createdAt", Sort.Direction.ASC));
		db.indexOps("outbox")
			.ensureIndex(new Index().on("published", Sort.Direction.ASC).on("nextAt", Sort.Direction.ASC));
	}

	@Transactional(propagation = org.springframework.transaction.annotation.Propagation.MANDATORY)
	public void emit(Envelope response) {
		try {
			db.insert(new Document("_id", response.messageId().toString())
				.append("body", json.writeValueAsString(response))
				.append("exchange", "workshop.events")
				.append("routingKey", response.messageType())
				.append("traceparent", traces.current())
				.append("published", false)
				.append("attempts", 0)
				.append("nextAt", new Date(0))
				.append("leaseUntil", new Date(0)), "outbox");
		}
		catch (com.fasterxml.jackson.core.JsonProcessingException error) {
			throw new IllegalStateException(error);
		}
	}

	public Optional<Pending> claim() {
		String lease = UUID.randomUUID().toString();
		Query query = Query.query(
				Criteria.where("published").is(false).and("nextAt").lte(new Date()).and("leaseUntil").lte(new Date()))
			.with(Sort.by("nextAt"));
		Document value = db.findAndModify(query,
				new Update().set("lease", lease)
					.set("leaseUntil", Date.from(Instant.now().plusSeconds(20)))
					.inc("attempts", 1),
				FindAndModifyOptions.options().returnNew(true), Document.class, "outbox");
		return value == null ? Optional.empty()
				: Optional.of(new Pending(UUID.fromString(value.getString("_id")), value.getString("body"),
						value.getString("exchange"), value.getString("routingKey"), value.getString("traceparent"),
						UUID.fromString(lease), value.getInteger("attempts")));
	}

	private Query leased(Pending e) {
		return Query.query(Criteria.where("_id").is(e.id().toString()).and("lease").is(e.lease().toString()));
	}

	public void sent(Pending e) {
		db.updateFirst(leased(e), new Update().set("published", true), "outbox");
	}

	public void failed(Pending e) {
		db.updateFirst(leased(e),
				new Update().set("leaseUntil", new Date(0))
					.set("nextAt", Date.from(Instant.now().plusSeconds(Math.min(60, 1L << Math.min(e.attempts(), 6))))),
				"outbox");
	}

}
