package com.postech.foundation;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.context.ApplicationContext;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = { "spring.rabbitmq.host=localhost", "spring.rabbitmq.username=test",
		"spring.rabbitmq.password=test", "spring.rabbitmq.listener.simple.auto-startup=false",
		"foundation.jwt-secret=01234567890123456789012345678901", "foundation.service-token=test",
		"management.tracing.enabled=false", "management.otlp.metrics.export.enabled=false",
		"management.endpoint.health.group.readiness.include=readinessState",
		"spring.data.mongodb.uri=mongodb://localhost:27017/wiring",
		"spring.autoconfigure.exclude=org.springframework.boot.autoconfigure.mongo.MongoAutoConfiguration,org.springframework.boot.autoconfigure.data.mongo.MongoDataAutoConfiguration,org.springframework.boot.autoconfigure.data.mongo.MongoRepositoriesAutoConfiguration" })
@AutoConfigureMockMvc
class ApplicationWiringTest {

	@MockitoBean
	Transport transport;

	@MockitoBean
	MongoFoundation outbox;

	@MockitoBean
	org.springframework.data.mongodb.core.MongoTemplate mongo;

	@MockitoBean
	org.springframework.data.mongodb.MongoDatabaseFactory mongoFactory;

	@Autowired
	ApplicationContext context;

	@Autowired
	MockMvc http;

	@Test
	void singleConsumerAndSecurityChainLoad() {
		assertEquals(1, context.getBeansOfType(org.springframework.security.web.SecurityFilterChain.class).size());
		assertEquals(1, context.getBeansOfType(Handler.class).size());
	}

	@Test
	void generatedApiDescribesCanonicalRoutes() throws Exception {
		String api = http.perform(get("/v3/api-docs"))
			.andExpect(status().isOk())
			.andReturn()
			.getResponse()
			.getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
		var paths = new com.fasterxml.jackson.databind.ObjectMapper().readTree(api).path("paths");
		assertTrue(paths.has("/api/v1/trabalhos/{id}"));
		assertFalse(paths.has("/api/v1/fundacao"));
		java.nio.file.Files.writeString(java.nio.file.Path.of("target/openapi-phase3.json"), api);
		var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
		var generated = mapper.readTree(api);
		var published = mapper
			.readTree(java.nio.file.Files.readString(java.nio.file.Path.of("contracts/openapi.json")));
		var alias = mapper.readTree(java.nio.file.Files.readString(java.nio.file.Path.of("openapi.json")));
		assertEquals(published, alias, "Root OpenAPI must match canonical contracts/openapi.json");
		assertEquals(generated.path("paths"), published.path("paths"), "Published routes diverged; regenerate OpenAPI");
		assertEquals(generated.path("components"), published.path("components"),
				"Published schemas diverged; regenerate OpenAPI");

	}

}
