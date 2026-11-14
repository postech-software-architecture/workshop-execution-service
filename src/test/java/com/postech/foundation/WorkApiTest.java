package com.postech.foundation;

import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class WorkApiTest {

	final UUID id = UUID.randomUUID(), item = UUID.randomUUID();

	final Execution store = mock(Execution.class);

	final Jwt jwt = Jwt.withTokenValue("unit").header("alg", "HS256").subject(UUID.randomUUID().toString()).build();

	final MockMvc http = MockMvcBuilders.standaloneSetup(new WorkApi(store))
		.setCustomArgumentResolvers(new AuthenticationPrincipalArgumentResolver())
		.build();

	@BeforeEach
	void actor() {
		SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt));
	}

	@AfterEach
	void clear() {
		SecurityContextHolder.clearContext();
	}

	@Test
	void missingVersionCannotInvokeTechnicalMutation() throws Exception {
		http.perform(post("/api/v1/trabalhos/" + id + "/reparo/iniciar")).andExpect(status().isBadRequest());
		verifyNoInteractions(store);
	}

	@Test
	void technicalRoutesPreserveAuthenticatedActorAndExpectedVersion() throws Exception {
		var actions = Map.of("diagnostico/iniciar", "START_DIAGNOSTIC", "diagnostico/concluir", "FINISH_DIAGNOSTIC",
				"reparo/iniciar", "START_REPAIR", "reparo/concluir", "FINISH_REPAIR", "itens/" + item + "/iniciar",
				"START_ITEM", "itens/" + item + "/concluir", "FINISH_ITEM");
		for (var action : actions.entrySet()) {
			var request = post("/api/v1/trabalhos/" + id + "/" + action.getKey()).header("If-Match", "7");
			if (action.getValue().equals("FINISH_DIAGNOSTIC"))
				request.contentType("application/json").content("{\"diagnostico\":\"Trocar oleo\"}");
			http.perform(request).andExpect(status().isOk());
			String expected = action.getValue().contains("ITEM") ? item.toString()
					: action.getValue().equals("FINISH_DIAGNOSTIC") ? "Trocar oleo" : null;
			verify(store).change(id, 7, action.getValue(), expected, jwt.getSubject());
		}
	}

	@Test
	void queueHasBoundedDefaultsAndCanonicalLookup() throws Exception {
		when(store.queue(anyString(), anyInt())).thenReturn(List.of());
		http.perform(get("/api/v1/trabalhos")).andExpect(status().isOk());
		verify(store).queue("DIAGNOSTICO_PENDENTE", 0);
		http.perform(get("/api/v1/trabalhos/" + id)).andExpect(status().isOk());
		verify(store).find(id.toString());
	}

}
