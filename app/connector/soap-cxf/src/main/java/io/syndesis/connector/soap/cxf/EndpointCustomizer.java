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

import java.util.Map;
import javax.xml.namespace.QName;

import io.syndesis.integration.component.proxy.ComponentProxyComponent;
import io.syndesis.integration.component.proxy.ComponentProxyCustomizer;

public final class EndpointCustomizer implements ComponentProxyCustomizer {

    @Override
    public void customize(final ComponentProxyComponent component, final Map<String, Object> options) {
        consumeOption(options, "serviceName", serviceObject -> {
            final String serviceName = (String) serviceObject;
            final QName service = QName.valueOf(serviceName);

            options.put("serviceName", serviceName);

            consumeOption(options, "portName", portObject -> {
                final QName port = new QName(service.getNamespaceURI(), (String) portObject);

                options.put("portName", port.toString());
            });
        });
    }

}
