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
package io.syndesis.server.api.generator.soap;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;

import org.apache.cxf.common.xmlschema.SchemaCollection;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;

import io.syndesis.common.model.DataShape;
import io.syndesis.common.model.DataShapeKinds;
import io.syndesis.common.model.action.Action;
import io.syndesis.common.model.action.ActionsSummary;
import io.syndesis.common.model.action.ConnectorAction;
import io.syndesis.common.model.action.ConnectorDescriptor;
import io.syndesis.common.model.api.APISummary;
import io.syndesis.common.model.connection.ConfigurationProperty;
import io.syndesis.common.model.connection.Connector;
import io.syndesis.common.model.connection.ConnectorSettings;
import io.syndesis.common.util.json.JsonUtils;
import io.syndesis.server.api.generator.ConnectorGenerator;
import io.syndesis.server.api.generator.openapi.TestHelper;
import io.syndesis.server.api.generator.soap.parser.SoapApiModelParser;

import static java.util.Objects.requireNonNull;
import static org.assertj.core.api.Assertions.assertThat;

@RunWith(Parameterized.class)
public class SoapApiConnectorGeneratorExampleTest extends AbstractSoapExampleTest {

    private DocumentBuilderFactory documentBuilderFactory;

    public SoapApiConnectorGeneratorExampleTest(final String resource) throws IOException, InterruptedException {
        super(resource);
        documentBuilderFactory = DocumentBuilderFactory.newInstance();
    }

    @Test
    public void shouldProvideInfo() {

        final APISummary apiSummary = generator().info(SoapConnectorTemplate.SOAP_TEMPLATE, getConnectorSettings());

        assertThat(apiSummary).isNotNull();
        assertThat(apiSummary.getWarnings()).isEmpty();
        assertThat(apiSummary.getErrors()).isEmpty();

        assertThat(apiSummary.getName()).isNotEmpty();
        assertThat(apiSummary.getDescription()).isNotEmpty();

        final Map<String, String> configuredProperties = apiSummary.getConfiguredProperties();
        assertThat(configuredProperties).isNotEmpty();
        assertThat(configuredProperties).containsKey(SoapApiConnectorGenerator.SPECIFICATION_PROPERTY);
        assertThat(configuredProperties).containsKey(SoapApiConnectorGenerator.SERVICE_NAME_PROPERTY);
        assertThat(configuredProperties).containsKey(SoapApiConnectorGenerator.PORT_NAME_PROPERTY);

        final ActionsSummary actionsSummary = apiSummary.getActionsSummary();
        assertThat(actionsSummary).isNotNull();
        assertThat(actionsSummary.getTotalActions()).isGreaterThan(0);
        assertThat(actionsSummary.getActionCountByTags()).isNotEmpty();
    }

    private ConnectorSettings getConnectorSettings() {
        return new ConnectorSettings.Builder()//
                .putConfiguredProperty(SoapApiConnectorGenerator.SPECIFICATION_PROPERTY, specification)//
                .build();
    }

    @Test
    public void shouldGenerateConnector() throws ParserConfigurationException, IOException, SAXException {

        final Connector connector = generator().generate(SoapConnectorTemplate.SOAP_TEMPLATE, getConnectorSettings());

        assertThat(connector).isNotNull();
        assertThat(connector.getName()).isNotEmpty();
        assertThat(connector.getDescription()).isNotEmpty();

        // assert summary
        final Optional<ActionsSummary> actionsSummary = connector.getActionsSummary();
        assertThat(actionsSummary).isPresent();
        assertThat(actionsSummary.get().getTotalActions()).isGreaterThan(0);
        assertThat(actionsSummary.get().getActionCountByTags()).isNotEmpty();

        // assert template properties
        final Map<String, ConfigurationProperty> properties = connector.getProperties();
        assertThat(properties).isNotEmpty();
        assertThat(properties).containsKey(SoapApiConnectorGenerator.ADDRESS_PROPERTY);
        assertThat(properties.get(SoapApiConnectorGenerator.ADDRESS_PROPERTY).getDefaultValue()).isNotNull();
        assertThat(properties).containsKey(SoapApiConnectorGenerator.USERNAME_PROPERTY);
        assertThat(properties).containsKey(SoapApiConnectorGenerator.PASSWORD_PROPERTY);

        // assert configured properties
        final Map<String, String> configuredProperties = connector.getConfiguredProperties();
        assertThat(configuredProperties).isNotEmpty();
        assertThat(configuredProperties).containsKey(SoapApiConnectorGenerator.SERVICE_NAME_PROPERTY);
        assertThat(configuredProperties).containsKey(SoapApiConnectorGenerator.PORT_NAME_PROPERTY);
        assertThat(configuredProperties).containsKey(SoapApiConnectorGenerator.SPECIFICATION_PROPERTY);
        assertThat(configuredProperties).containsKey(SoapApiConnectorGenerator.ADDRESS_PROPERTY);
        assertThat(configuredProperties).containsKey("componentName");

        // assert actions
        assertThat(connector.getActions()).isNotEmpty();
        for (ConnectorAction a : connector.getActions()) {
            assertThat(a.getActionType()).isEqualTo(ConnectorAction.TYPE_CONNECTOR);
            assertThat(a.getName()).isNotEmpty();
            assertThat(a.getDescription()).isNotEmpty();
            assertThat(a.getPattern()).isEqualTo(Action.Pattern.To);

            final ConnectorDescriptor descriptor = a.getDescriptor();
            assertThat(descriptor).isNotNull();
            assertThat(descriptor.getConnectorId()).isEqualTo(connector.getId().get());

            final Map<String, String> actionProperties = descriptor.getConfiguredProperties();
            assertThat(actionProperties).isNotEmpty();
            assertThat(actionProperties).containsEntry(SoapApiModelParser.DEFAULT_OPERATION_NAME_PROPERTY, a.getName());
            assertThat(actionProperties).containsKey(SoapApiModelParser.DEFAULT_OPERATION_NAMESPACE_PROPERTY);
            assertThat(actionProperties).containsEntry(SoapApiModelParser.DATA_FORMAT_PROPERTY,
                    SoapApiModelParser.PAYLOAD_FORMAT);

            // assert input and output data shapes
            final Optional<DataShape> inputDataShape = descriptor.getInputDataShape();
            if (inputDataShape.isPresent()) {
                validateDataShape(inputDataShape.get());
            }
            final Optional<DataShape> outputDataShape = descriptor.getOutputDataShape();
            if (outputDataShape.isPresent()) {
                validateDataShape(outputDataShape.get());
            }
        }
    }

    private void validateDataShape(DataShape inputDataShape) throws ParserConfigurationException, SAXException, IOException {
        assertThat(inputDataShape.getName()).isNotEmpty();
        assertThat(inputDataShape.getDescription()).isNotEmpty();
        assertThat(inputDataShape.getKind()).isEqualTo(DataShapeKinds.XML_SCHEMA);
        final String specification = inputDataShape.getSpecification();
        assertThat(specification).isNotEmpty();

        final SchemaCollection collection = new SchemaCollection();
        final DocumentBuilder documentBuilder = documentBuilderFactory.newDocumentBuilder();
        collection.read(documentBuilder.parse(new InputSource(new StringReader(specification))).getDocumentElement());
    }

    protected ConnectorGenerator generator() {
        try (InputStream stream = SoapApiConnectorGeneratorExampleTest.class.getResourceAsStream("/META-INF/syndesis/connector/soap-cxf.json")) {
            final Connector soapConnector = JsonUtils.readFromStream(stream, Connector.class);

            final AtomicInteger cnt = new AtomicInteger();
            return new SoapApiConnectorGenerator(soapConnector);
        } catch (final IOException e) {
            throw new AssertionError(e);
        }
    }

    public static String resource(final String path) throws IOException {
        final String resource;
        try (final InputStream in = requireNonNull(TestHelper.class.getResourceAsStream(path), path);
             final BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {

            resource = reader.lines().collect(Collectors.joining("\n"));
        }
        return resource;
    }

}
