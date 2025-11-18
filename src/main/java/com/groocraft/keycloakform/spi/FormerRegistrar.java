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

package com.groocraft.keycloakform.spi;

import com.groocraft.keycloakform.config.FormerConfig;
import com.groocraft.keycloakform.former.Formers;

import org.keycloak.Config.Scope;
import org.keycloak.exportimport.ImportProvider;
import org.keycloak.exportimport.ImportProviderFactory;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.KeycloakSessionFactory;

import java.util.Map;

/**
 * The FormerRegistrar class is an implementation of the ImportProviderFactory interface.
 * The interface is used because of configuration semantic, otherwise the implementation
 * serves as registrar for {@link FormingInitializer}.
 * Features:
 * - Initializes a FormingInitializer instance using configuration from the application's
 * environment or provided Scope object.
 * - Supports a "dry run" mode, which simulates actions without making any changes.
 * - Specifies the source file for the realm definition via configuration.
 * - Manages registration of the initializer to a KeycloakSessionFactory for handling events.
 * Behavior:
 * - The init method ensures all necessary configurations are provided. It throws an
 * IllegalArgumentException if the required source file is missing.
 * - The postInit method registers the FormingInitializer instance to the session factory,
 * enabling it to handle formation-related events after migration.
 * - The class provides a getId method to uniquely identify it as a provider factory.
 * - The close method performs resource cleanup, though it currently contains no logic.
 *
 * @author Majlanky
 */
public class FormerRegistrar implements ImportProviderFactory {

    private static final String DRY_RUN = "dryRun";
    private static final String SOURCE_FILE = "sourceFile";

    private FormingInitializer initializer;

    public static final String ID = "keycloakform";

    @Override
    public ImportProvider create(KeycloakSession session, Map<String, String> overrides) {
        return null;
    }

    @Override
    public ImportProvider create(KeycloakSession session) {
        return null;
    }

    @Override
    public void init(Scope config) {
        FormerConfig formerConfig = FormerConfig.builder()
            .dryRun(config.getBoolean(DRY_RUN, false))
            .sourceFile(config.get(SOURCE_FILE, ""))
            .build();

        if (formerConfig.getSourceFile().isBlank()) {
            throw new IllegalArgumentException("Property spi-import-keycloakform-registrar-source-file must be provided!");
        }

        initializer = new FormingInitializer(formerConfig, new Formers());

    }

    @Override
    public void postInit(KeycloakSessionFactory factory) {
        factory.register(initializer);
    }

    @Override
    public void close() {

    }

    @Override
    public String getId() {
        return ID;
    }

}
