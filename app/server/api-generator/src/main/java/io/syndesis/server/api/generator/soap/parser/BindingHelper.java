package io.syndesis.server.api.generator.soap.parser;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;
import javax.jws.soap.SOAPBinding.Style;
import javax.jws.soap.SOAPBinding.Use;
import javax.wsdl.extensions.soap.SOAPBinding;
import javax.xml.namespace.QName;

import org.apache.cxf.binding.soap.model.SoapBindingInfo;
import org.apache.cxf.binding.soap.model.SoapBodyInfo;
import org.apache.cxf.binding.soap.model.SoapHeaderInfo;
import org.apache.cxf.binding.soap.model.SoapOperationInfo;
import org.apache.cxf.service.model.BindingMessageInfo;
import org.apache.cxf.service.model.BindingOperationInfo;
import org.apache.cxf.service.model.MessageInfo;
import org.apache.cxf.service.model.MessagePartInfo;
import org.apache.cxf.service.model.OperationInfo;
import org.apache.cxf.service.model.SchemaInfo;
import org.apache.cxf.staxutils.StaxUtils;
import org.apache.ws.commons.schema.XmlSchema;
import org.apache.ws.commons.schema.XmlSchemaAnnotated;
import org.apache.ws.commons.schema.XmlSchemaAttribute;
import org.apache.ws.commons.schema.XmlSchemaCollection;
import org.apache.ws.commons.schema.XmlSchemaComplexType;
import org.apache.ws.commons.schema.XmlSchemaElement;
import org.apache.ws.commons.schema.XmlSchemaSequence;
import org.apache.ws.commons.schema.XmlSchemaSequenceMember;
import org.apache.ws.commons.schema.XmlSchemaSerializer;
import org.apache.ws.commons.schema.XmlSchemaSimpleContent;
import org.apache.ws.commons.schema.XmlSchemaSimpleContentExtension;
import org.apache.ws.commons.schema.XmlSchemaSimpleType;
import org.apache.ws.commons.schema.XmlSchemaType;
import org.apache.ws.commons.schema.constants.Constants;

import static javax.jws.soap.SOAPBinding.Use.ENCODED;

/**
 * Helper class for working with {@link SOAPBinding} && {@link javax.wsdl.extensions.soap12.SOAP12Binding}.
 */
public class BindingHelper {

    public static final String SOAP_PAYLOAD_ENVELOPE_ELEMENT = "soap-payload-envelope";
    public static final String SOAP_PAYLOAD_HEADER_ELEMENT = "soap-payload-header";
    public static final String SOAP_PAYLOAD_BODY_ELEMENT = "soap-payload-body";
    public static final String XML_SIMPLETYPE_VALUE_SUFFIX = "-xml-simpletype-value";

    private final BindingMessageInfo bindingMessageInfo;
    private final SchemaInfo xmlSchema;
    private final BindingOperationInfo bindingOperation;
    private final Style style;

    private final List<MessagePartInfo> bodyParts;

    private final boolean hasHeaders;
    private final List<MessagePartInfo> headerParts;

    BindingHelper(BindingMessageInfo bindingMessageInfo) throws ParserException {
        this.bindingMessageInfo = bindingMessageInfo;

        final List<SchemaInfo> schemas =
                bindingMessageInfo.getBindingOperation().getBinding().getService().getSchemas();
        // no multiple schemas, WSDLs with no schemas for RPC/literal are accepted
        if (schemas.size() > 1) {
            throw new ParserException("WSDL's with multiple schemas are not supported");
        }
        // find appropriate schema to use
        if (!schemas.isEmpty()) {
            this.xmlSchema = schemas.get(0);
        } else {
            // set empty schema as source schema with same namespace as WSDL
            final String wsdlNamespace = bindingMessageInfo.getMessageInfo().getName().getNamespaceURI();
            final XmlSchemaCollection schemaCollection = new XmlSchemaCollection();
            this.xmlSchema = new SchemaInfo(wsdlNamespace);
            this.xmlSchema.setSchema(new XmlSchema(wsdlNamespace, schemaCollection));
        }

        bindingOperation = bindingMessageInfo.getBindingOperation();

        SoapOperationInfo soapOperationInfo = bindingOperation.getExtensor(SoapOperationInfo.class);
        SoapBindingInfo soapBindingInfo = (SoapBindingInfo) bindingOperation.getBinding();

        // get binding style
        if (soapOperationInfo.getStyle() != null) {
            style = Style.valueOf(soapOperationInfo.getStyle().toUpperCase(Locale.US));
        } else if (soapBindingInfo.getStyle() != null) {
            style = Style.valueOf(soapBindingInfo.getStyle().toUpperCase(Locale.US));
        } else {
            style = Style.DOCUMENT;
        }

        // get body binding
        SoapBodyInfo soapBodyInfo = bindingMessageInfo.getExtensor(SoapBodyInfo.class);
        List<SoapHeaderInfo> soapHeaders = bindingMessageInfo.getExtensors(SoapHeaderInfo.class);
        bodyParts = soapBodyInfo.getParts();

        // get any headers as MessagePartInfos
        hasHeaders = soapHeaders != null && !soapHeaders.isEmpty();
        headerParts = hasHeaders ?
            soapHeaders.stream().map(SoapHeaderInfo::getPart).collect(Collectors.toList()) : null;

        // get required body use
        Use use = Use.valueOf(soapBodyInfo.getUse().toUpperCase(Locale.US));
        if (ENCODED.equals(use)) {
            // TODO could we add support for RPC/encoded messages by setting schema type to any??
            throw new ParserException("Messages with use='encoded' are not supported");
        }
    }

    // get specification from BindingMessageInfo
    public String getSpecification() throws ParserException {

        // target element names for adding namespace attribute,
        // required for handling parts with type=* and namespace=* attributes
        final List<QName> namespaceTargets = new ArrayList<>();

        // generate schema according to style and use combinations
        final XmlSchema generatedSchema = new XmlSchema(xmlSchema.getNamespaceURI(), new XmlSchemaCollection());
        generatedSchema.setElementFormDefault(xmlSchema.getSchema().getElementFormDefault());
        generatedSchema.setAttributeFormDefault(xmlSchema.getSchema().getAttributeFormDefault());

        // extract elements/types from source schema
        final XmlSchemaExtractor schemaExtractor = new XmlSchemaExtractor(generatedSchema, xmlSchema.getSchema());

        // TODO also handle faults for output message, which requires an enhancement in Syndesis

        switch (style) {

        case RPC:
            final OperationInfo operationInfo = bindingOperation.getOperationInfo();
            final QName operationName = operationInfo.getName();
            final QName wrapperElement = bindingMessageInfo.getMessageInfo().getType() == MessageInfo.Type.INPUT ?
                operationName : new QName(operationName.getNamespaceURI(), operationName.getLocalPart() + "Response");
            namespaceTargets.add(wrapperElement);

            createRpcWrapper(generatedSchema, schemaExtractor, namespaceTargets, wrapperElement);
            break;

        case DOCUMENT:
            final boolean topLevel = !hasHeaders && bodyParts.size() == 1;
            final List<XmlSchemaElement> bodyElements = getPartElements(bodyParts, namespaceTargets, schemaExtractor,
                topLevel);

            // if topLevel is true, root element was already added to generated schema as top level element
            if (!topLevel) {
                final List<XmlSchemaElement> headerElements = hasHeaders ?
                    getPartElements(headerParts, namespaceTargets, schemaExtractor, false) : null;
                createPayloadWrapper(generatedSchema, headerElements, bodyElements);
            }

        }

        return getSpecificationString(generatedSchema, schemaExtractor, namespaceTargets);
    }

    private List<XmlSchemaElement> getPartElements(List<MessagePartInfo> bodyParts, List<QName> namespaceTargets,
                                                   XmlSchemaExtractor schemaExtractor, boolean topLevel) throws ParserException {
        final List<XmlSchemaElement> bodyElements = new ArrayList<>();
        for (MessagePartInfo part : bodyParts) {

            final XmlSchemaAnnotated annotated = part.getXmlSchema() != null ? part.getXmlSchema() :
                xmlSchema.getSchema().getTypeByName(part.getTypeQName());

            final QName name = part.getConcreteName();
            final XmlSchemaElement element;
            if (annotated instanceof XmlSchemaElement) {
                // extract element
                element = schemaExtractor.extract((XmlSchemaElement) annotated, topLevel);
            } else if (annotated instanceof XmlSchemaType) {
                // extract type
                element = schemaExtractor.extract(name.getLocalPart(), (XmlSchemaType) annotated, topLevel);
                // handle part namespace by adding as an attribute to this element 'after' copyObjects() call
                namespaceTargets.add(name);
            } else {
                // probably an xsd:* type, create an element with part's typename
                element = new XmlSchemaElement(schemaExtractor.getTargetSchema(), topLevel);
                element.setName(name.getLocalPart());
                element.setSchemaTypeName(part.getTypeQName());
                namespaceTargets.add(name);
            }

            bodyElements.add(element);
        }

        return bodyElements;
    }

    private String getSpecificationString(XmlSchema generatedSchema, XmlSchemaExtractor schemaExtractor, List<QName> namespaceTargets) throws ParserException {
        try {
            // copy source types to target schema
            schemaExtractor.copyObjects();

            // add namespace attributes for generated elements
            resolveNamespaceTargets(generatedSchema, namespaceTargets);

            // serialize schema
            return StaxUtils.toString(generatedSchema.getSchemaDocument());

        } catch (XmlSchemaSerializer.XmlSchemaSerializerException | ParserException e) {
            throw new ParserException(String.format("Error parsing %s for operation %s: %s",
                    bindingMessageInfo.getMessageInfo().getType(),
                    bindingMessageInfo.getBindingOperation().getOperationInfo().getName(),
                    e.getMessage())
                , e);
        }
    }

    private void resolveNamespaceTargets(XmlSchema schema, List<QName> namespaceTargets) {

        // find matching elements in Schema based on name and add namespace as fixed attribute
        for (QName name : namespaceTargets) {

            // TODO skip elements in the schema's namespace
/*
            if (name.getNamespaceURI().equals(schema.getTargetNamespace())) {
                continue;
            }
*/

            final String localPart = name.getLocalPart();
            final XmlSchemaElement element;
            if (schema.getElementByName(localPart) != null) {
                element = schema.getElementByName(localPart);
            } else {
                // traverse elements (upto 3 levels deep) to find a matching element
                final Collection<XmlSchemaElement> elements = schema.getElements().values().stream()
                    .flatMap(e -> getChildren(e).stream())
                    .collect(Collectors.toList());
                element = findChildElementByName(elements, localPart, 3);
            }

            // add an attribute to the element containing the namespace to use in the actual message
            final XmlSchemaAttribute namespaceAttribute = new XmlSchemaAttribute(schema, false);
            namespaceAttribute.setName(XmlSchemaExtractor.SOAP_PAYLOAD_NAMESPACE_ATTRIBUTE);
            namespaceAttribute.setSchemaTypeName(new QName(Constants.URI_2001_SCHEMA_XSD, "anyURI"));
            namespaceAttribute.setFixedValue(name.getNamespaceURI());

            final XmlSchemaType schemaType = element.getSchemaType();
            if (schemaType instanceof XmlSchemaComplexType) {
                ((XmlSchemaComplexType) schemaType).getAttributes().add(namespaceAttribute);
            } else if (schemaType instanceof XmlSchemaSimpleType) {

                // move element simple type inside a wrapper complex type with namespace attribute
                final XmlSchemaComplexType complexType = new XmlSchemaComplexType(schema, false);
                complexType.getAttributes().add(namespaceAttribute);
                final XmlSchemaSequence particle = new XmlSchemaSequence();
                complexType.setParticle(particle);

                // NOTE soap-cxf connector will look for this child element and 'move' it's value up to the parent element
                final XmlSchemaElement wrapper = new XmlSchemaElement(schema, false);
                particle.getItems().add(wrapper);
                wrapper.setName(element.getName() + XML_SIMPLETYPE_VALUE_SUFFIX);
                wrapper.setSchemaType(schemaType);

                element.setSchemaType(complexType);

            } else {

                // element has type=xsd:* type
                final QName typeName = element.getSchemaTypeName();
                // add complexType to element with simpleType restriction and namespace attribute
                final XmlSchemaComplexType complexType = new XmlSchemaComplexType(schema, false);
                complexType.getAttributes().add(namespaceAttribute);
                element.setSchemaType(complexType);
                element.setSchemaTypeName(null);

                final XmlSchemaSimpleContent contentModel = new XmlSchemaSimpleContent();
                complexType.setContentModel(contentModel);

                final XmlSchemaSimpleContentExtension content = new XmlSchemaSimpleContentExtension();
                contentModel.setContent(content);
                content.setBaseTypeName(typeName);

            }
        }
    }

    private XmlSchemaElement findChildElementByName(Collection<XmlSchemaElement> elements, String name, int depth) {
        if (depth == 0) {
            return null;
        }

        // BFS order
        for (XmlSchemaElement element : elements) {
            if (name.equals(element.getName())) {
                return element;
            }
        }

        // check the next depth
        final int nextDepth = depth - 1;
        if (nextDepth > 0) {
            for (XmlSchemaElement element : elements) {
                final List<XmlSchemaElement> children = getChildren(element);
                final XmlSchemaElement childElementByName = findChildElementByName(children, name, nextDepth);
                if (childElementByName != null) {
                    return childElementByName;
                }
            }
        }

        return null;
    }

    private List<XmlSchemaElement> getChildren(XmlSchemaElement element) {
        // get children elements for complex type sequences
        if (element.getSchemaType() instanceof XmlSchemaComplexType) {
            final XmlSchemaComplexType complexType = (XmlSchemaComplexType) element.getSchemaType();
            // we can be certain they'd be sequences, since that's what we use for all wrapper elements
            if (complexType.getParticle() instanceof XmlSchemaSequence) {
                return ((XmlSchemaSequence) complexType.getParticle()).getItems().stream()
                    .filter(i -> i instanceof XmlSchemaElement)
                    .map(i -> (XmlSchemaElement) i)
                    .collect(Collectors.toList());
            }
        }
        return Collections.emptyList();
    }

    private void createRpcWrapper(XmlSchema generatedSchema, XmlSchemaExtractor schemaExtractor,
                                  List<QName> namespaceTargets, QName operationWrapper) throws ParserException {

        final List<XmlSchemaSequenceMember> wrapperElement;
        if (headerParts == null) {
            // no need for a payload wrapper
            wrapperElement = getXmlSchemaElement(generatedSchema, operationWrapper.getLocalPart());
        } else {
            // create payload wrapper
            final List<XmlSchemaSequenceMember> payloadElement = getXmlSchemaElement(generatedSchema, SOAP_PAYLOAD_ENVELOPE_ELEMENT);

            // header wrapper
            final List<XmlSchemaSequenceMember> headerWrapper = getXmlSchemaElement(generatedSchema,
                payloadElement, SOAP_PAYLOAD_HEADER_ELEMENT);
            // add header elements
            headerWrapper.addAll(getPartElements(headerParts, namespaceTargets, schemaExtractor, false));

            // body wrapper
            final List<XmlSchemaSequenceMember> bodyWrapper = getXmlSchemaElement(generatedSchema,
                payloadElement, SOAP_PAYLOAD_BODY_ELEMENT);

            // wrapper element
            wrapperElement = getXmlSchemaElement(generatedSchema, bodyWrapper, operationWrapper.getLocalPart());
        }

        // add bodyParts to wrapper
        wrapperElement.addAll(getPartElements(bodyParts, namespaceTargets, schemaExtractor, false));
    }

    private void createPayloadWrapper(XmlSchema generatedSchema, List<XmlSchemaElement> headerElements,
                                      List<XmlSchemaElement> bodyElements) {
        // wrapper element
        final List<XmlSchemaSequenceMember> wrapperElement = getXmlSchemaElement(generatedSchema, SOAP_PAYLOAD_ENVELOPE_ELEMENT);

        // check if there is a header included
        final List<XmlSchemaSequenceMember> bodyItems;
        if (headerElements != null) {
            final List<XmlSchemaSequenceMember> headers = getXmlSchemaElement(generatedSchema, wrapperElement,
                SOAP_PAYLOAD_HEADER_ELEMENT);

            headers.addAll(headerElements);

            // add body wrapper
            bodyItems = getXmlSchemaElement(generatedSchema, wrapperElement, SOAP_PAYLOAD_BODY_ELEMENT);
        } else {
            bodyItems = wrapperElement;
        }

        bodyItems.addAll(bodyElements);
    }

    private List<XmlSchemaSequenceMember> getXmlSchemaElement(XmlSchema generatedSchema, String name) {
        return getXmlSchemaElement(generatedSchema, null, name, true);
    }

    private List<XmlSchemaSequenceMember> getXmlSchemaElement(XmlSchema generatedSchema,
                                                              List<XmlSchemaSequenceMember> parent, String name) {
        return getXmlSchemaElement(generatedSchema, parent, name, false);
    }

    private List<XmlSchemaSequenceMember> getXmlSchemaElement(XmlSchema generatedSchema,
                                                              List<XmlSchemaSequenceMember> parent, String name,
                                                              boolean topLevel) {
        // element
        final XmlSchemaElement element = new XmlSchemaElement(generatedSchema, topLevel);
        element.setName(name);

        // complex type
        final XmlSchemaComplexType complexType = new XmlSchemaComplexType(generatedSchema, false);
        element.setType(complexType);

        // sequence
        final XmlSchemaSequence wrapperParticle = new XmlSchemaSequence();
        complexType.setParticle(wrapperParticle);

        // need to add new element to a parent sequence?
        if(parent != null) {
            parent.add(element);
        }

        return wrapperParticle.getItems();
    }
}
