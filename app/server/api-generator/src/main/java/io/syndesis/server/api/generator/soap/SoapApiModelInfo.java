package io.syndesis.server.api.generator.soap;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import javax.wsdl.Definition;
import javax.xml.namespace.QName;

import org.immutables.value.Value;

import io.syndesis.common.model.Violation;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;

/**
 * Represents parsed WSDL and validation result.
 */
@Value.Immutable
@JsonDeserialize(builder = SoapApiModelInfo.Builder.class)
public interface SoapApiModelInfo {

    class Builder extends ImmutableSoapApiModelInfo.Builder {
        // make ImmutableSoapApiModelInfo.Builder accessible
    }

    // resolved and condensed WSDL
    Optional<String> getResolvedSpecification();

    // WSDL model if parsing succeeded
    Optional<Definition> getModel();

    // Default Service, if there is only one SOAP Service and Port in WSDL.
    Optional<QName> getDefaultService();

    // Default Port, if there is only one SOAP Port in WSDL.
    Optional<String> getDefaultPort();

    // Default Address, if there is a default Port in WSDL.
    Optional<String> getDefaultAddress();

    // All Services in model
    @Value.Default
    default List<QName> getServices() {
        return Collections.emptyList();
    }

    // All Ports in model
    @Value.Default
    default Map<QName, List<String>> getPorts() {
        return Collections.emptyMap();
    }

    @Value.Default
    default List<Violation> getWarnings() {
        return Collections.emptyList();
    }

    @Value.Default
    default List<Violation> getErrors() {
        return Collections.emptyList();
    }
}
