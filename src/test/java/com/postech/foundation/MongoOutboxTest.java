package com.postech.foundation;

import java.util.*;
import java.time.Instant;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.bson.Document;
import org.junit.jupiter.api.Test;
import org.springframework.data.mongodb.core.*;
import org.springframework.data.mongodb.core.query.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class MongoOutboxTest {

	final MongoTemplate db = mock(MongoTemplate.class, RETURNS_DEEP_STUBS);

	final TraceHeaders trace = mock(TraceHeaders.class);

	final MongoFoundation outbox = new MongoFoundation(db, new ObjectMapper().findAndRegisterModules(), trace);

	@Test
	void initializationCreatesCollectionsAndBusinessIndexes() {
		when(db.collectionExists(anyString())).thenReturn(false);
		outbox.run(null);
		verify(db).createCollection("works");
		verify(db).createCollection("inbox");
		verify(db).createCollection("outbox");
		verify(db.indexOps("works"), times(2)).ensureIndex(any());
	}

	@Test
	void noPendingPublicationReturnsEmpty() {
		when(db.findAndModify(any(Query.class), any(Update.class), any(FindAndModifyOptions.class), eq(Document.class),
				eq("outbox")))
			.thenReturn(null);
		assertTrue(outbox.claim().isEmpty());
	}

	@Test
	void claimCarriesLeaseBodyAndTraceAndCompletionUsesLease() {
		UUID id = UUID.randomUUID(), lease = UUID.randomUUID();
		var document = new Document("_id", id.toString()).append("body", "{}")
			.append("exchange", "workshop.events")
			.append("routingKey", "event")
			.append("traceparent", "trace")
			.append("lease", lease.toString())
			.append("attempts", 2);
		when(db.findAndModify(any(Query.class), any(Update.class), any(FindAndModifyOptions.class), eq(Document.class),
				eq("outbox")))
			.thenReturn(document);
		var pending = outbox.claim().orElseThrow();
		assertEquals(id, pending.id());
		assertEquals(2, pending.attempts());
		assertEquals("trace", pending.traceparent());
		outbox.sent(pending);
		outbox.failed(pending);
		verify(db, times(2)).updateFirst(argThat(q -> q.getQueryObject().containsKey("lease")), any(Update.class),
				eq("outbox"));
	}

	@Test
	void eventAndTraceAreSerializedForLaterPublication() {
		var id = UUID.randomUUID();
		var e = new Envelope(id, "execucao.diagnostico-admitido.v1", 1, Instant.now(), "execucao-service", id, id, id,
				id, null, id, 1, new ObjectMapper().valueToTree(Map.of("trabalhoId", id)));
		when(trace.current()).thenReturn("trace");
		outbox.emit(e);
		verify(db).insert(argThat((Document d) -> d.getString("body").contains("diagnostico-admitido")
				&& "trace".equals(d.getString("traceparent"))), eq("outbox"));
	}

}
