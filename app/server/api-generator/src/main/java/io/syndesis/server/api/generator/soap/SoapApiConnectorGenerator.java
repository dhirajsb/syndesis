package io.syndesis.server.api.generator.soap;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;
import javax.wsdl.Definition;
import javax.xml.namespace.QName;

import org.w3c.dom.Element;

import io.syndesis.common.model.action.ActionsSummary;
import io.syndesis.common.model.action.ConnectorAction;
import io.syndesis.common.model.api.APISummary;
import io.syndesis.common.model.connection.ConfigurationProperty;
import io.syndesis.common.model.connection.Connector;
import io.syndesis.common.model.connection.ConnectorSettings;
import io.syndesis.common.model.connection.ConnectorTemplate;
import io.syndesis.server.api.generator.ConnectorGenerator;
import io.syndesis.server.api.generator.soap.parser.ParserException;
import io.syndesis.server.api.generator.soap.parser.SoapApiModelParser;

/**
 * Generates SOAP API Connector.
 */
public class SoapApiConnectorGenerator extends ConnectorGenerator {

    static final String SPECIFICATION_PROPERTY = "specification";
    static final String SERVICE_NAME_PROPERTY = "serviceName";
    static final String PORT_NAME_PROPERTY = "portName";
    static final String ADDRESS_PROPERTY = "address";
    static final String USERNAME_PROPERTY = "username";
    static final String PASSWORD_PROPERTY = "password";

    private static final String SERVICES_PROPERTY = "services";
    private static final String PORTS_PROPERTY = "ports";

    // thread local API model to avoid re-parsing the WSDL.
    private static final ThreadLocal<SoapApiModelInfo> localModelInfo = new ThreadLocal<>();

    public SoapApiConnectorGenerator(Connector baseConnector) {
        super(baseConnector);
    }

    @Override
    public Connector generate(ConnectorTemplate connectorTemplate, ConnectorSettings connectorSettings) {
        try {
            final SoapApiModelInfo modelInfo = getModelInfo(connectorSettings);
            return createConnector(connectorTemplate, connectorSettings, modelInfo);
        } finally {
            releaseModelInfo();
        }
    }

    @SuppressWarnings("unchecked")
    private Connector createConnector(ConnectorTemplate connectorTemplate, ConnectorSettings connectorSettings,
                                      SoapApiModelInfo modelInfo) {

        final Definition definition = modelInfo.getModel().
                orElseThrow(() -> new IllegalArgumentException("Unable to parse WSDL, or missing SOAP Service and Port in specification"));

        // get service and port
        final Map<String, String> configuredProperties = connectorSettings.getConfiguredProperties();
        final QName serviceName = getService(modelInfo, configuredProperties)
                .orElseThrow(() -> new IllegalArgumentException("Missing property " + SERVICE_NAME_PROPERTY));
        final String portName = getPortName(modelInfo, configuredProperties)
                .orElseThrow(() -> new IllegalArgumentException("Missing property " + PORT_NAME_PROPERTY));

        // add actions
        try {

            final Connector configuredConnector = configuredConnector(connectorTemplate, connectorSettings);

            final List<ConnectorAction> actions = SoapApiModelParser.parseActions(definition,
                    serviceName, new QName(serviceName.getNamespaceURI(), portName),
                    configuredConnector.getId().get());
            final ActionsSummary actionsSummary = SoapApiModelParser.parseActionsSummary(definition, serviceName, portName);

            final Connector.Builder builder = new Connector.Builder()
                    .createFrom(configuredConnector)
                    .addAllActions(actions)
                    .actionsSummary(actionsSummary);

            return builder.build();

        } catch (ParserException e) {
            throw new IllegalArgumentException("Error getting actions from WSDL: " + e.getMessage(), e);
        }
    }

    @Override
    public APISummary info(ConnectorTemplate connectorTemplate, ConnectorSettings connectorSettings) {
        try {
            final SoapApiModelInfo modelInfo = getModelInfo(connectorSettings);
            return getApiSummary(connectorTemplate, connectorSettings, modelInfo);
        } finally {
            releaseModelInfo();
        }
    }

    @Override
    protected String determineConnectorDescription(ConnectorTemplate connectorTemplate,
                                                   ConnectorSettings connectorSettings) {

        final String defaultDescription = "Web Services Connector";
        final Optional<Definition> definition = getModelInfo(connectorSettings).getModel();

        return definition.map(d -> {
            final Element docElement = d.getDocumentationElement();
            return docElement != null ? docElement.getTextContent() :
                    defaultDescription + " for service " + d.getQName();
        }).orElse(defaultDescription);
    }

    @Override
    protected String determineConnectorName(ConnectorTemplate connectorTemplate,
                                                   ConnectorSettings connectorSettings) {
        final String defaultName = super.determineConnectorName(connectorTemplate, connectorSettings);
        return getModelInfo(connectorSettings).getModel()
                .map(d -> d.getQName().getLocalPart())
                .orElse(defaultName);
    }

    private APISummary getApiSummary(ConnectorTemplate connectorTemplate, ConnectorSettings connectorSettings,
                                     SoapApiModelInfo modelInfo) {

        final Map<String, String> configuredProperties = connectorSettings.getConfiguredProperties();

        // create connector from template and connectorSettings, and add connector properties and parse warnings and errors
        final Connector connector = configuredConnector(connectorTemplate, connectorSettings);
        final APISummary.Builder summaryBuilder = APISummary.Builder.createFrom(connector)
                .putAllConfiguredProperties(connector.getConfiguredProperties())
                .warnings(modelInfo.getWarnings())
                .errors(modelInfo.getErrors());

        modelInfo.getModel().ifPresent(definition -> {

            // add list of services and ports
            // NOTE configured properties doesn't allow non-String values so we use a json string instead
            summaryBuilder.putConfiguredProperty(SERVICES_PROPERTY,
                    toListValue(modelInfo.getServices().stream()
                            .map(QName::toString)
                            .collect(Collectors.toList())));
            summaryBuilder.putConfiguredProperty(PORTS_PROPERTY, toMapValue(modelInfo.getPorts()));

            // default or user selected service and port names
            final QName serviceName = modelInfo.getDefaultService().map(s -> {
                    summaryBuilder.putConfiguredProperty(SERVICE_NAME_PROPERTY, s.toString());
                    return s;
                }).orElse(configuredProperties.containsKey(SERVICE_NAME_PROPERTY) ?
                    QName.valueOf(configuredProperties.get(SERVICE_NAME_PROPERTY)) : null);

            final String portName = modelInfo.getDefaultPort().map(p -> {
                    summaryBuilder.putConfiguredProperty(PORT_NAME_PROPERTY, p);
                    return p;
                }).orElse(configuredProperties.get(PORT_NAME_PROPERTY));

            // parse PortType if service and port are provided
            if (serviceName != null && portName != null) {
                // get actions from WSDL operations
                try {
                    summaryBuilder.actionsSummary(SoapApiModelParser.parseActionsSummary(definition, serviceName, portName));
                } catch (ParserException e) {
                    summaryBuilder.addError(e.toViolation());
                }
            }
        });

        return summaryBuilder.build();
    }

    private Connector configuredConnector(ConnectorTemplate connectorTemplate, ConnectorSettings connectorSettings) {
        // copy all configuration properties, might have to change for supporting different WSDL versions
        final Connector.Builder builder = new Connector.Builder()
                .createFrom(baseConnectorFrom(connectorTemplate, connectorSettings));

        // add configuration properties
        final Map<String, ConfigurationProperty> connectorProperties = connectorTemplate.getConnectorProperties();
        builder.putProperty(ADDRESS_PROPERTY, connectorProperties.get(ADDRESS_PROPERTY));
        builder.putProperty(USERNAME_PROPERTY, connectorProperties.get(USERNAME_PROPERTY));
        builder.putProperty(PASSWORD_PROPERTY, connectorProperties.get(PASSWORD_PROPERTY));

        final SoapApiModelInfo modelInfo = getModelInfo(connectorSettings);

        // set configured properties
        final Map<String, String> configuredProperties = connectorSettings.getConfiguredProperties();
        builder.putConfiguredProperty(SPECIFICATION_PROPERTY,
                modelInfo.getResolvedSpecification().orElse(configuredProperties.get(SPECIFICATION_PROPERTY)));
        getService(modelInfo, configuredProperties)
                .ifPresent(s -> builder.putConfiguredProperty(SERVICE_NAME_PROPERTY, s.toString()));
        getPortName(modelInfo, configuredProperties)
                .ifPresent(p -> builder.putConfiguredProperty(PORT_NAME_PROPERTY, p));

        // if present, set default address from WSDL
        modelInfo.getDefaultAddress().ifPresent(a -> {
            builder.putConfiguredProperty(ADDRESS_PROPERTY, a);
            builder.putProperty(ADDRESS_PROPERTY,
                    new ConfigurationProperty.Builder().createFrom(connectorProperties.get(ADDRESS_PROPERTY))
                    .defaultValue(a)
                    .build());
        });

        return builder.build();
    }

    private Optional<QName> getService(SoapApiModelInfo modelInfo, Map<String, String> configuredProperties) {
        return configuredProperties.containsKey(SERVICE_NAME_PROPERTY) ?
                Optional.of(QName.valueOf(configuredProperties.get(SERVICE_NAME_PROPERTY))) : modelInfo.getDefaultService();
    }

    private Optional<String> getPortName(SoapApiModelInfo modelInfo, Map<String, String> configuredProperties) {
        return configuredProperties.containsKey(PORT_NAME_PROPERTY) ?
                Optional.ofNullable(configuredProperties.get(PORT_NAME_PROPERTY)) : modelInfo.getDefaultPort();
    }

    // get cached TLS model info, or create one the first time
    private SoapApiModelInfo getModelInfo(ConnectorSettings connectorSettings) {
        // check TLS first
        if (localModelInfo.get() == null) {
            final String specification = getSpecification(connectorSettings);
            localModelInfo.set(SoapApiModelParser.parseSoapAPI(specification));
        }
        return localModelInfo.get();
    }

    // release cached TLS model info
    private void releaseModelInfo() {
        localModelInfo.set(null);
    }

    private String getSpecification(ConnectorSettings connectorSettings) {
        final String specification = connectorSettings.getConfiguredProperties().get(SPECIFICATION_PROPERTY);
        if (specification == null) {
            throw new IllegalArgumentException("Missing configured property 'specification'");
        }
        return specification;
    }

    // return a json representation of map of ports
    private String toMapValue(Map<QName, List<String>> ports) {
        StringBuilder builder = new StringBuilder("{");
        AtomicBoolean first = new AtomicBoolean(true);
        ports.forEach((s, p) -> {
            builder.append(first.get() ? '"' : ",\"" );
            builder.append(s);
            builder.append("\":");
            builder.append(toListValue(p));
            first.set(false);
        });
        builder.append('}');
        return builder.toString();
    }

    // return a json represenation of list of strings
    private String toListValue(List<String> stringList) {
        StringBuilder builder = new StringBuilder("[");
        AtomicBoolean first = new AtomicBoolean(true);
        stringList.forEach(s -> {
            builder.append(first.get() ? '"' : ",\"");
            builder.append(s);
            builder.append('"');
            first.set(false);
        });
        builder.append(']');
        return builder.toString();
    }

}
