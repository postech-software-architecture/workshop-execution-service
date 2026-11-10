Feature: Regras de negocio do servico
  Scenario: Comportamento protegido e repetivel
    Given um trabalho cancelado antes da criacao
    When um comando atrasado tenta iniciar o diagnostico
    Then o trabalho permanece cancelado sem nova execucao
