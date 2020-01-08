/*
 * Copyright (C) 2016 Red Hat, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.syndesis.connector.soap.cxf;

import java.util.function.Function;

import org.apache.camel.Endpoint;

import io.syndesis.integration.component.proxy.ComponentProxyComponent;

public final class SoapCxfProxyComponent extends ComponentProxyComponent {

    private final Function<Endpoint, Endpoint> endpointOverride = Function.identity();

    public SoapCxfProxyComponent(final String componentId, final String componentScheme) {
        super(componentId, componentScheme);
    }

    @Override
    public Endpoint createEndpoint(final String uri) throws Exception {
        final Endpoint endpoint = super.createEndpoint(uri);

        return endpointOverride.apply(endpoint);
    }
}
