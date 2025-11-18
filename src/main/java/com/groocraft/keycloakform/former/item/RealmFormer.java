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

package com.groocraft.keycloakform.former.item;

import com.groocraft.keycloakform.definition.AuthenticationFlowDefinition;
import com.groocraft.keycloakform.definition.AuthenticatorConfigDefinition;
import com.groocraft.keycloakform.definition.ClientDefinition;
import com.groocraft.keycloakform.definition.ClientScopeDefinition;
import com.groocraft.keycloakform.definition.ComponentDefinition;
import com.groocraft.keycloakform.definition.DefinitionMapping;
import com.groocraft.keycloakform.definition.GroupDefinition;
import com.groocraft.keycloakform.definition.IdentityProviderDefinition;
import com.groocraft.keycloakform.definition.IdentityProviderMapperDefinition;
import com.groocraft.keycloakform.definition.RealmDefinition;
import com.groocraft.keycloakform.definition.RequiredActionDefinition;
import com.groocraft.keycloakform.definition.RoleDefinition;
import com.groocraft.keycloakform.definition.RolesDefinition;
import com.groocraft.keycloakform.definition.ScopeDefinitionHelper;
import com.groocraft.keycloakform.former.FormerContext;
import com.groocraft.keycloakform.former.FormersFactory;
import com.groocraft.keycloakform.former.generic.DefaultItemFormer;

import org.keycloak.Config;
import org.keycloak.common.util.MultivaluedHashMap;
import org.keycloak.models.ClientModel;
import org.keycloak.models.ClientScopeModel;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.RealmModel;
import org.keycloak.models.RoleModel;
import org.keycloak.models.utils.KeycloakModelUtils;
import org.keycloak.representations.idm.RoleRepresentation;
import org.keycloak.storage.datastore.DefaultExportImportManager;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import lombok.CustomLog;

/**
 * RealmFormer is responsible for managing the forming lifecycle of realms in a Keycloak instance.
 * It provides functionality to create, retrieve, update, and manage Realm resources
 * following their definitions.
 * This class inherits from {@link DefaultItemFormer} and extends
 * its behavior specifically for {@link RealmModel} (Keycloak realm representation) and
 * {@link RealmDefinition} (realm configuration definition).
 * During the formation of realms, the following operations are handled:
 * - Retrieval: Fetches a realm by its ID or name from the Keycloak session.
 * - Creation: Creates a new realm when it does not already exist.
 * - Update: Updates an existing realm's configuration based on the provided definition.
 * Key responsibilities:
 * - Implements the abstract methods of {@code ItemFormer} specific to realms.
 * - Handles logging and dry-run operations for safer updates.
 * Attributes:
 * - {@code updater}: Instance of {@code RealmUpdater} responsible for updating realm properties.
 *
 * @author Majlanky
 */
@CustomLog
public class RealmFormer extends DefaultItemFormer<RealmModel, RealmDefinition> {

    private final FormersFactory formersFactory;

    public RealmFormer(FormersFactory formersFactory) {
        super(log);
        this.formersFactory = formersFactory;
    }

    @Override
    protected RealmModel getModel(RealmDefinition definition, FormerContext context) {
        return context.getSession().realms().getRealmByName(definition.getRealm());
    }

    @Override
    protected RealmModel create(RealmDefinition definition, FormerContext context) {
        String id = definition.getId() == null ? KeycloakModelUtils.generateId() : definition.getId();
        RealmModel realm = context.getSession().realms().createRealm(id, definition.getRealm());

        realm.setEventsListeners(Collections.singleton("jboss-logging"));

        context.getSession().getKeycloakSessionFactory().publish(new RealmModel.RealmPostCreateEvent() {
            @Override
            public RealmModel getCreatedRealm() {
                return realm;
            }

            @Override
            public KeycloakSession getKeycloakSession() {
                return context.getSession();
            }
        });

        return realm;
    }

    @Override
    protected void update(RealmModel model, RealmDefinition definition, FormerContext context) {
        context.setRealm(model);
        context.setRealmDefinition(definition);

        formersFactory.getForCollectionOf(RequiredActionDefinition.class)
            .form(DefinitionMapping.cast(definition.getRequiredActions()), context, definition.getSyncMode());
        formersFactory.getForCollectionOf(ComponentDefinition.class)
            .form(getComponents(DefinitionMapping.cast(definition.getComponents()), model.getId()), context, definition.getSyncMode());
        formersFactory.getForCollectionOf(AuthenticatorConfigDefinition.class)
            .form(DefinitionMapping.cast(definition.getAuthenticatorConfig()), context, definition.getSyncMode());
        formersFactory.getForCollectionOf(AuthenticationFlowDefinition.class)
            .form(DefinitionMapping.cast(definition.getAuthenticationFlows()), context, definition.getSyncMode());
        formersFactory.getForCollectionOf(RoleDefinition.class)
            .form(DefinitionMapping.cast(definition.getRoles().getRealm()), context, definition.getSyncMode());
        formersFactory.getForCollectionOf(ClientScopeDefinition.class)
            .form(DefinitionMapping.cast(definition.getClientScopes()), context, definition.getSyncMode());
        formersFactory.getForCollectionOf(ClientDefinition.class)
            .form(DefinitionMapping.cast(definition.getClients()), context, definition.getSyncMode());
        formersFactory.getForCollectionOf(IdentityProviderDefinition.class)
            .form(DefinitionMapping.cast(definition.getIdentityProviders()), context, definition.getSyncMode());
        formersFactory.getForCollectionOf(IdentityProviderMapperDefinition.class)
            .form(DefinitionMapping.cast(definition.getIdentityProviderMappers()), context, definition.getSyncMode());
        formersFactory.getForCollectionOf(GroupDefinition.class)
            .form(DefinitionMapping.cast(definition.getGroups()), context, definition.getSyncMode());

        RolesDefinition roles = DefinitionMapping.cast(definition.getRoles());
        formersFactory.getFor(roles).form(roles, context);
        ScopeDefinitionHelper scopeDefinitionHelper = getScopeDefinitionHelper(definition);
        formersFactory.getFor(scopeDefinitionHelper).form(scopeDefinitionHelper, context);

        setDefaultRole(model, definition);
        setMasterAdminClient(model, context);
        processClientScopes(model, definition.getDefaultDefaultClientScopes(), context, true);
        processClientScopes(model, definition.getDefaultOptionalClientScopes(), context, false);

        new DefaultExportImportManager(context.getSession()).updateRealm(definition, model);

        context.setRealm(null);
        context.setRealmDefinition(null);
    }

    private ScopeDefinitionHelper getScopeDefinitionHelper(RealmDefinition definition) {
        return new ScopeDefinitionHelper(definition.getSyncMode(), definition.getScopeMappings(), definition.getClientScopeMappings());
    }

    private List<ComponentDefinition> getComponents(MultivaluedHashMap<String, ComponentDefinition> definitions, String parentId) {
        ArrayList<ComponentDefinition> components = new ArrayList<>();

        for (Map.Entry<String, List<ComponentDefinition>> e : definitions.entrySet()) {
            for (ComponentDefinition d : e.getValue()) {
                d.setParentId(parentId);
                d.setProviderType(e.getKey());
                components.add(d);
                components.addAll(getComponents(DefinitionMapping.cast(d.getSubComponents()), d.getId()));
            }
        }
        return components;
    }

    private void processClientScopes(RealmModel model, List<String> toAssign, FormerContext context, boolean defaultScope) {
        if (toAssign != null) {
            Map<String, ClientScopeModel> namedClientScopes = context.getRealm().getClientScopesStream()
                .collect(Collectors.toMap(ClientScopeModel::getName, cs -> cs));

            model.getDefaultClientScopesStream(defaultScope).forEach(cs -> {
                if (!toAssign.contains(cs.getName())) {
                    model.removeDefaultClientScope(cs);
                } else {
                    toAssign.remove(cs.getName());
                }
            });

            toAssign.forEach(name -> model.addDefaultClientScope(namedClientScopes.get(name), defaultScope));
        }
    }

    private void setDefaultRole(RealmModel realmModel, RealmDefinition definition) {
        if (definition.getDefaultRole() != null) {
            RoleModel currentDefaultRole = realmModel.getDefaultRole();
            RoleRepresentation defaultRoleRepresentation = definition.getDefaultRole();
            if (currentDefaultRole == null || !currentDefaultRole.getId().equals(defaultRoleRepresentation.getId())) {
                realmModel.setDefaultRole(realmModel.getRoleById(defaultRoleRepresentation.getId()));
                log.infof("%s default role was updated: %s >>> %s",
                    getLogIdentifier(definition),
                    currentDefaultRole == null ? null : currentDefaultRole.getId(),
                    defaultRoleRepresentation.getId());
            }
        }
    }

    private void setMasterAdminClient(RealmModel realm, FormerContext context) {
        if (!realm.getName().equals(Config.getAdminRealm()) && realm.getMasterAdminClient() == null) {
            RealmModel adminRealm = context.getSession().realms().getRealmByName(Config.getAdminRealm());
            String adminClientId = KeycloakModelUtils.getMasterRealmAdminManagementClientId(realm.getName());
            ClientModel adminClient = adminRealm.getClientByClientId(adminClientId);
            if (adminClient == null) {
                throw new IllegalStateException(
                    "Realm " + realm.getName() + " does not have master admin client set, but client " + adminClientId
                    + " does not exist in admin realm");
            }
            realm.setMasterAdminClient(adminClient);
        }
    }

    @Override
    protected Class<RealmModel> getKeycloakResourceClass() {
        return RealmModel.class;
    }

    @Override
    protected String getLogIdentifier(RealmDefinition definition) {
        return "Realm " + definition.getRealm() + "(" + definition.getId() + ")";
    }

    @Override
    public Class<RealmDefinition> getDefinitionClass() {
        return RealmDefinition.class;
    }
}
