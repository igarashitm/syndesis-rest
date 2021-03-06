/**
 * Copyright (C) 2016 Red Hat, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *         http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.syndesis.rest.v1.handler.connection;

import java.net.URI;
import java.net.URISyntaxException;

import javax.annotation.Nonnull;
import javax.servlet.http.HttpServletRequest;
import javax.validation.Valid;
import javax.validation.constraints.NotNull;
import javax.ws.rs.Consumes;
import javax.ws.rs.POST;
import javax.ws.rs.Produces;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.NewCookie;
import javax.ws.rs.core.Response;

import io.swagger.annotations.Api;
import io.syndesis.credential.AcquisitionFlow;
import io.syndesis.credential.AcquisitionRequest;
import io.syndesis.credential.AcquisitionResponse;
import io.syndesis.credential.AcquisitionResponse.State;
import io.syndesis.credential.CredentialFlowState;
import io.syndesis.credential.Credentials;
import io.syndesis.rest.v1.state.ClientSideState;

import static io.syndesis.rest.v1.util.Urls.apiBase;

@Api(value = "credentials")
public class ConnectionCredentialHandler {

    private final String connectionId;

    private final String connectorId;

    private final Credentials credentials;

    private final ClientSideState state;

    public ConnectionCredentialHandler(@Nonnull final Credentials credentials, @Nonnull final ClientSideState state,
        @Nonnull final String connectionId, @Nonnull final String connectorId) {
        this.credentials = credentials;
        this.state = state;
        this.connectionId = connectionId;
        this.connectorId = connectorId;
    }

    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Response create(@NotNull @Valid final AcquisitionRequest request,
        @Context final HttpServletRequest httpRequest) {

        final AcquisitionFlow acquisitionFlow = credentials.acquire(connectionId, connectorId, apiBase(httpRequest),
            absoluteTo(httpRequest, request.getReturnUrl()));

        final CredentialFlowState flowState = acquisitionFlow.state().get();
        final NewCookie cookie = state.persist(flowState.persistenceKey(), "/credentials/callback", flowState);

        AcquisitionResponse acquisitionResponse = AcquisitionResponse.Builder.from(acquisitionFlow)
            .state(State.Builder.cookie(cookie.toString())).build();

        return Response.accepted().entity(acquisitionResponse).build();
    }

    protected static URI absoluteTo(final HttpServletRequest httpRequest, final URI url) {
        final URI current = URI.create(httpRequest.getRequestURL().toString());

        try {
            return new URI(current.getScheme(), null, current.getHost(), current.getPort(), url.getPath(),
                url.getQuery(), url.getFragment());
        } catch (final URISyntaxException e) {
            throw new IllegalArgumentException(
                "Unable to generate URI based on the current (`" + current + "`) and the return (`" + url + "`) URLs",
                e);
        }
    }

}
