/*
 * Copyright 2025 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.groocraft.keycloakform.integration;

import com.groocraft.keycloakform.definition.RealmDefinition;
import com.groocraft.keycloakform.definition.deserialization.Deserialization;

import org.assertj.core.api.SoftAssertions;
import org.junit.jupiter.api.Test;
import org.keycloak.representations.idm.ClientRepresentation;
import org.keycloak.representations.idm.ClientScopeRepresentation;
import org.keycloak.representations.idm.ComponentExportRepresentation;
import org.keycloak.representations.idm.ProtocolMapperRepresentation;
import org.testcontainers.containers.ComposeContainer;
import org.testcontainers.containers.ContainerState;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.io.File;
import java.io.IOException;
import java.time.Duration;
import java.util.Collection;

import static org.testcontainers.shaded.org.awaitility.Awaitility.await;

@Testcontainers
public class FormingIntegrationTest {

    private static final String KEYCLOAK = "keycloak";

    @Container
    private static final ComposeContainer environment = new ComposeContainer(
        new File("src/test/resources/integration/docker-compose.yml"))
        .withEnv("PROJECT_VERSION", "1.0.0-SNAPSHOT")
        .withExposedService(KEYCLOAK, 8080,
            Wait.forLogMessage(".*Running the server.*", 1).withStartupTimeout(Duration.ofMinutes(5)))
        .withLogConsumer(KEYCLOAK, outputFrame -> System.out.println(KEYCLOAK + " service> " + outputFrame.getUtf8String()));

    @Test
    void testExportOfNewlyCreatedIsMatchingDefinition() throws IOException, InterruptedException {
        String[] exportCommand = {"/opt/keycloak/bin/kc.sh", "export", "--file", "/opt/keycloak/data/export/test.json", "--realm", "test"};
        File testExport = new File("src/test/resources/integration/export/test.json");
        File definition = new File("src/test/resources/integration/realms.json");
        ContainerState container = environment.getContainerByServiceName(KEYCLOAK).orElseThrow();

        container.execInContainer(exportCommand);

        RealmDefinition requested = Deserialization
            .getRealmsFromStream(definition.toURI().toURL().openStream()).stream()
            .filter(d -> d.getRealm().equals("test")).findFirst().orElseThrow();

        await().atMost(Duration.ofMinutes(1)).until(testExport::exists);

        RealmDefinition generated = Deserialization
            .getRealmsFromStream(testExport.toURI().toURL().openStream()).getFirst();

        SoftAssertions softly = new SoftAssertions();

        softly.assertThat(generated).usingRecursiveComparison()
            .ignoringFields("syncMode", "clientScopes", "clients", "users", "roles", "defaultDefaultClientScopes",
                "defaultOptionalClientScopes", "keycloakVersion", "components")
            .ignoringCollectionOrder()
            .as("For realm " + requested.getRealm())
            .isEqualTo(requested);

        softly.assertThat(generated.getDefaultDefaultClientScopes())
            .containsExactlyInAnyOrderElementsOf(requested.getDefaultDefaultClientScopes());
        softly.assertThat(generated.getDefaultOptionalClientScopes())
            .containsExactlyInAnyOrderElementsOf(requested.getDefaultOptionalClientScopes());

        //fixing generated config map for generation-based components
        generated.getComponents().values().stream()
            .flatMap(Collection::stream)
            .forEach(component -> {
                if (component.getName().contains("generated")) {
                    component.getConfig().remove("certificate");
                    component.getConfig().remove("privateKey");
                    component.getConfig().remove("kid");
                    component.getConfig().remove("secret");
                }
            });
        requested.getComponents().values().stream()
            .flatMap(Collection::stream)
            .forEach(component -> {
                if (component.getName().contains("generated")) {
                    component.getConfig().remove("certificate");
                    component.getConfig().remove("privateKey");
                    component.getConfig().remove("kid");
                    component.getConfig().remove("secret");
                }
            });

        requested.getComponents().forEach((type, requestedComponents) -> {
            requestedComponents.forEach(requestedComponent -> {
                ComponentExportRepresentation generatedComponent = generated.getComponents().get(type).stream()
                    .filter(c -> c.getId().equals(requestedComponent.getId())).findFirst().orElse(null);
                softly.assertThat(generatedComponent).as("Missing component with id " + requestedComponent.getId()).isNotNull();
                softly.assertThat(generatedComponent).usingRecursiveComparison()
                    .ignoringCollectionOrder()
                    .as("For component " + requestedComponent.getId())
                    .isEqualTo(requestedComponent);
            });
        });

        requested.getClientScopes().forEach(requestedScope -> {

            ClientScopeRepresentation generatedScope = generated.getClientScopes().stream()
                .filter(s -> s.getId().equals(requestedScope.getId())).findFirst().orElse(null);
            softly.assertThat(generatedScope).as("Missing client scope with id " + requestedScope.getId()).isNotNull();
            softly.assertThat(generatedScope).usingRecursiveComparison()
                .ignoringFields("protocolMappers")
                .as("For client scope " + requestedScope.getId())
                .isEqualTo(requestedScope);

            if (requestedScope.getProtocolMappers() != null) {
                requestedScope.getProtocolMappers().forEach(requestedMapper -> {

                    ProtocolMapperRepresentation generatedMapper = generatedScope.getProtocolMappers().stream()
                        .filter(m -> m.getName().equals(requestedMapper.getName())).findFirst().orElse(null);
                    softly.assertThat(generatedMapper)
                        .as("Missing protocol mapper with name " + requestedMapper.getName())
                        .isNotNull();
                    softly.assertThat(generatedMapper).usingRecursiveComparison()
                        .as("For protocol mapper " + requestedMapper.getId())
                        .isEqualTo(requestedMapper);
                });
            }

        });

        requested.getClients().forEach(requestedClient -> {
            ClientRepresentation generatedClient = generated.getClients().stream()
                .filter(c -> c.getClientId().equals(requestedClient.getClientId())).findFirst().orElse(null);
            softly.assertThat(generatedClient).as("Missing client with cid " + requestedClient.getClientId()).isNotNull();
            softly.assertThat(generatedClient).usingRecursiveComparison()
                .ignoringFields("protocolMappers")
                .as("For client " + requestedClient.getClientId())
                .isEqualTo(requestedClient);

            if (requestedClient.getProtocolMappers() != null) {
                requestedClient.getProtocolMappers().forEach(requestedMapper -> {
                    ProtocolMapperRepresentation generatedMapper = generatedClient.getProtocolMappers().stream()
                        .filter(m -> m.getName().equals(requestedMapper.getName())).findFirst().orElse(null);
                    softly.assertThat(generatedMapper)
                        .as("Missing protocol mapper with name " + requestedMapper.getName())
                        .isNotNull();
                    softly.assertThat(generatedMapper).usingRecursiveComparison()
                        .as("For protocol mapper " + requestedMapper.getId())
                        .isEqualTo(requestedMapper);
                });
            }
        });

        softly.assertAll();
    }

    @Test
    void testExportOfUpdatedIsMatchingDefinition() throws IOException, InterruptedException {
        //FIXME
    }

}
