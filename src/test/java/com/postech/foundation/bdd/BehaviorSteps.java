package com.postech.foundation.bdd;

import io.cucumber.java.en.*;

public class BehaviorSteps {

	private com.postech.foundation.Work work;

	private boolean blocked;

	@Given("um trabalho cancelado antes da criacao")
	public void given() {
		var id = java.util.UUID.randomUUID();
		var payload = new com.fasterxml.jackson.databind.ObjectMapper().valueToTree(java.util.Map.of("trabalhoId", id));
		work = com.postech.foundation.Work
			.tombstone(new com.postech.foundation.Envelope(id, "execucao.cancelar-trabalho.v1", 1,
					java.time.Instant.now(), "os-service", id, id, id, id, null, id, 1, payload));
	}

	@When("um comando atrasado tenta iniciar o diagnostico")
	public void when() {
		try {
			work.startDiagnostic("mechanic");
		}
		catch (IllegalArgumentException expected) {
			blocked = true;
		}
	}

	@Then("o trabalho permanece cancelado sem nova execucao")
	public void then() {
		org.junit.jupiter.api.Assertions.assertTrue(blocked);
		org.junit.jupiter.api.Assertions.assertEquals("CANCELADO", work.status());
	}

}
