package io.syndesis.server.api.generator.soap.parser;

import java.beans.PropertyDescriptor;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import javax.xml.namespace.QName;

import org.apache.commons.beanutils.BeanUtils;
import org.apache.commons.beanutils.PropertyUtils;
import org.apache.ws.commons.schema.XmlSchema;
import org.apache.ws.commons.schema.XmlSchemaAll;
import org.apache.ws.commons.schema.XmlSchemaAny;
import org.apache.ws.commons.schema.XmlSchemaAttribute;
import org.apache.ws.commons.schema.XmlSchemaAttributeGroup;
import org.apache.ws.commons.schema.XmlSchemaAttributeGroupMember;
import org.apache.ws.commons.schema.XmlSchemaAttributeGroupRef;
import org.apache.ws.commons.schema.XmlSchemaAttributeOrGroupRef;
import org.apache.ws.commons.schema.XmlSchemaChoice;
import org.apache.ws.commons.schema.XmlSchemaComplexContent;
import org.apache.ws.commons.schema.XmlSchemaComplexContentExtension;
import org.apache.ws.commons.schema.XmlSchemaComplexContentRestriction;
import org.apache.ws.commons.schema.XmlSchemaComplexType;
import org.apache.ws.commons.schema.XmlSchemaContentModel;
import org.apache.ws.commons.schema.XmlSchemaElement;
import org.apache.ws.commons.schema.XmlSchemaGroup;
import org.apache.ws.commons.schema.XmlSchemaGroupParticle;
import org.apache.ws.commons.schema.XmlSchemaGroupRef;
import org.apache.ws.commons.schema.XmlSchemaItemWithRef;
import org.apache.ws.commons.schema.XmlSchemaParticle;
import org.apache.ws.commons.schema.XmlSchemaSequence;
import org.apache.ws.commons.schema.XmlSchemaSimpleContent;
import org.apache.ws.commons.schema.XmlSchemaSimpleContentExtension;
import org.apache.ws.commons.schema.XmlSchemaSimpleContentRestriction;
import org.apache.ws.commons.schema.XmlSchemaSimpleType;
import org.apache.ws.commons.schema.XmlSchemaSimpleTypeContent;
import org.apache.ws.commons.schema.XmlSchemaSimpleTypeList;
import org.apache.ws.commons.schema.XmlSchemaSimpleTypeRestriction;
import org.apache.ws.commons.schema.XmlSchemaSimpleTypeUnion;
import org.apache.ws.commons.schema.XmlSchemaType;
import org.apache.ws.commons.schema.constants.Constants;
import org.apache.ws.commons.schema.utils.XmlSchemaNamed;
import org.apache.ws.commons.schema.utils.XmlSchemaNamedWithForm;
import org.apache.ws.commons.schema.utils.XmlSchemaObjectBase;

/**
 * Extracts Xml Schema types and elements as top level items,
 * inlines structure with anonymous types
 * and resolves 'ref' and 'type' attributes.
 */
class XmlSchemaExtractor {

    static final String SOAP_PAYLOAD_NAMESPACE_ATTRIBUTE = "soap-payload-namespace";

    // names of properties to exclude from shallow copy
    private static Map<Class<?>, List<String>> blackListedPropertiesMap = new HashMap<>();

    // ordered entries for schema type handlers
    private static Map<Predicate<XmlSchemaObjectBase>, TriConsumer<XmlSchemaExtractor, XmlSchemaObjectBase, XmlSchemaObjectBase>> handlerMap = new LinkedHashMap<>();

    private final XmlSchema targetSchema;
    private final XmlSchema sourceSchema;

    // queue to hold XmlSchemaObjects to be copied
    private final Deque<ObjectPair<? super XmlSchemaObjectBase>> queue;

    // current parent object path
    private Collection<? super XmlSchemaNamed> currentPath;

    static {
        // initialize properties map
        blackListedPropertiesMap.put(XmlSchemaAttribute.class, Arrays.asList("name", "form", "schemaType", "schemaTypeName"));

        blackListedPropertiesMap.put(XmlSchemaSimpleContent.class, Arrays.asList("name", "content"));
        blackListedPropertiesMap.put(XmlSchemaComplexContent.class, Arrays.asList("name", "content"));

        blackListedPropertiesMap.put(XmlSchemaSimpleTypeList.class, Arrays.asList("itemType", "itemTypeName"));
        blackListedPropertiesMap.put(XmlSchemaSimpleTypeRestriction.class, Arrays.asList("baseType", "baseTypeName"));
        blackListedPropertiesMap.put(XmlSchemaSimpleTypeUnion.class, Arrays.asList("baseTypes", "memberTypesSource", "memberTypesQNames"));

        blackListedPropertiesMap.put(XmlSchemaSimpleContentExtension.class, Arrays.asList("attributes", "baseTypeName"));
        blackListedPropertiesMap.put(XmlSchemaSimpleContentRestriction.class, Arrays.asList("attribute", "baseType", "baseTypeName"));
        blackListedPropertiesMap.put(XmlSchemaComplexContentExtension.class, Arrays.asList("attributes", "baseTypeName", "particle"));
        blackListedPropertiesMap.put(XmlSchemaComplexContentRestriction.class, Arrays.asList("attributes", "baseTypeName", "particle"));

        blackListedPropertiesMap.put(XmlSchemaElement.class, Arrays.asList("name", "form", "schemaType", "schemaTypeName"));
        blackListedPropertiesMap.put(XmlSchemaSimpleType.class, Arrays.asList("name", "content"));
        blackListedPropertiesMap.put(XmlSchemaComplexType.class, Arrays.asList("name", "attributes", "contentModel", "particle"));

        blackListedPropertiesMap.put(XmlSchemaAll.class, Collections.singletonList("items"));
        blackListedPropertiesMap.put(XmlSchemaChoice.class, Collections.singletonList("items"));
        blackListedPropertiesMap.put(XmlSchemaSequence.class, Collections.singletonList("items"));

        // initialize handler map
        handlerMap.put(s -> s instanceof XmlSchemaItemWithRef && ((XmlSchemaItemWithRef) s).isRef(),
                (x, t, s) -> x.handleItemWithRefBase(t, (XmlSchemaItemWithRef) s));
        handlerMap.put(s -> s instanceof XmlSchemaAttribute, (x, t, s) -> x.handleAttribute((XmlSchemaAttribute) t,
                (XmlSchemaAttribute) s));
        handlerMap.put(s -> s instanceof XmlSchemaElement, (x, t, s) -> x.handleElement((XmlSchemaElement) t,
                (XmlSchemaElement) s));
        handlerMap.put(s -> s instanceof XmlSchemaSimpleType, (x, t, s) -> x.handleSimpleType((XmlSchemaSimpleType) t
                , (XmlSchemaSimpleType) s));
        handlerMap.put(s -> s instanceof XmlSchemaSimpleTypeList,
                (x, t, s) -> x.handleSimpleTypeList((XmlSchemaSimpleTypeList) t, (XmlSchemaSimpleTypeList) s));
        handlerMap.put(s -> s instanceof XmlSchemaSimpleTypeRestriction,
                (x, t, s) -> x.handleSimpleTypeRestriction((XmlSchemaSimpleTypeRestriction) t,
                        (XmlSchemaSimpleTypeRestriction) s));
        handlerMap.put(s -> s instanceof XmlSchemaSimpleTypeUnion,
                (x, t, s) -> x.handleSimpleTypeUnion((XmlSchemaSimpleTypeUnion) t, (XmlSchemaSimpleTypeUnion) s));
        handlerMap.put(s -> s instanceof XmlSchemaSimpleContentExtension,
                (x, t, s) -> x.handleSimpleContentExtension((XmlSchemaSimpleContentExtension) t,
                        (XmlSchemaSimpleContentExtension) s));
        handlerMap.put(s -> s instanceof XmlSchemaSimpleContentRestriction,
                (x, t, s) -> x.handleSimpleContentRestriction((XmlSchemaSimpleContentRestriction) t,
                        (XmlSchemaSimpleContentRestriction) s));
        handlerMap.put(s -> s instanceof XmlSchemaComplexType,
                (x, t, s) -> x.handleComplexType((XmlSchemaComplexType) t, (XmlSchemaComplexType) s));
        handlerMap.put(s -> s instanceof XmlSchemaContentModel,
                (x, t, s) -> x.handleContentModel((XmlSchemaContentModel) t, (XmlSchemaContentModel) s));
        handlerMap.put(s -> s instanceof XmlSchemaComplexContentExtension,
                (x, t, s) -> x.handleComplexContentExtension((XmlSchemaComplexContentExtension) t,
                        (XmlSchemaComplexContentExtension) s));
        handlerMap.put(s -> s instanceof XmlSchemaComplexContentRestriction,
                (x, t, s) -> x.handleComplexContentRestriction((XmlSchemaComplexContentRestriction) t,
                        (XmlSchemaComplexContentRestriction) s));
        handlerMap.put(s -> s instanceof XmlSchemaGroupParticle,
                (x, t, s) -> x.handleGroupParticle((XmlSchemaGroupParticle) t, (XmlSchemaGroupParticle) s));
        handlerMap.put(s -> s instanceof XmlSchemaAny, (x, t, s) -> {});
    }

    XmlSchemaExtractor(XmlSchema targetSchema, XmlSchema sourceSchema) {
        this.targetSchema = targetSchema;
        this.sourceSchema = sourceSchema;
        this.queue = new ArrayDeque<>();
        // start with an empty current path
        this.currentPath = Collections.emptyList();
    }

    public XmlSchema getTargetSchema() {
        return targetSchema;
    }

    public XmlSchema getSourceSchema() {
        return sourceSchema;
    }

    public XmlSchemaElement extract(XmlSchemaElement element, boolean topLevel) throws ParserException {

        // create new element in target schema
        XmlSchemaElement result = new XmlSchemaElement(targetSchema, topLevel);
        result.setName(element.getName());

        // copy bean properties
        copyXmlSchemaObjectBase(result, element);

        return result;
    }

    public XmlSchemaElement extract(String name, XmlSchemaType type, boolean topLevel) throws ParserException {

        // create new element in target schema
        XmlSchemaElement result = new XmlSchemaElement(targetSchema, topLevel);
        result.setName(name);

        // set element type to the provided type
        if (!isXSDSchemaType(type.getQName())) {
            final XmlSchemaType schemaType = createXmlSchemaObjectBase(type);
            result.setType(schemaType);
        } else {
            result.setSchemaTypeName(type.getQName());
        }

        return result;
    }

    /**
     * Copy all objects from source schema to target schema after calling extract methods to setup objects to extract.
     *
     * @throws ParserException on error.
     */
    public void copyObjects() throws ParserException {
        while (!queue.isEmpty()) {

            // get objects in FIFO insertion order
            final ObjectPair pair = queue.remove();
            final XmlSchemaObjectBase source = pair.getSource();
            final XmlSchemaObjectBase target = pair.getTarget();

            // set current path to the source's node path
            currentPath = pair.getNodePath();

            // handle based on type
            handlerMap.entrySet().stream()
                    .filter(e -> e.getKey().test(source))
                    .findFirst()
                    .map(Map.Entry::getValue)
                    .orElseThrow(() -> new ParserException("Unsupported type " + source.getClass().getName()))
                    .apply(this, target, source);
        }
    }

    @SuppressWarnings("unchecked")
    private <T extends XmlSchemaObjectBase> void copyXmlSchemaObjectBase(T target, T source) throws ParserException {
        // copy non-blacklisted properties to target object
        copyNonBlackListedProperties(target, source);

        // check if named source is already in currentPath
        if (source instanceof XmlSchemaNamed && currentPath.stream().anyMatch(s -> s == source)) {
            throw new ParserException(String.format("Circular reference in schema type %s, %s",
                    currentPath.stream()
                            .map(o -> ((XmlSchemaNamed)o).getQName().toString())
                            .collect(Collectors.joining(" ,")), ((XmlSchemaNamed) source).getQName()));
        }

        // put objects at tail of queue to be handled in copyObjects
        queue.add(new ObjectPair(target, source));
    }

    // copy the referenced item to this item
    private <T extends XmlSchemaObjectBase> void handleItemWithRefBase(T target, XmlSchemaItemWithRef source) throws ParserException {
        final XmlSchemaNamed refTarget = source.getRef().getTarget();
        if (refTarget == null || !refTarget.getParent().getTargetNamespace().equals(sourceSchema.getTargetNamespace())) {
            throw new ParserException("Missing ref target in source schema: " + source.getTargetQName());
        }
        // set name of target from ref,
        // target will always be an XmlSchemaNamed (either XmlSchemaAttribute or XmlSchemaElement)
        ((XmlSchemaNamed)target).setName(refTarget.getName());
        copyXmlSchemaObjectBase(target, refTarget);
    }

    private void handleAttribute(XmlSchemaAttribute target, XmlSchemaAttribute source) throws ParserException {
        handleTypeNameAndType(source.getSchemaTypeName(), source.getSchemaType(),
                target::setSchemaTypeName, target::setSchemaType);
    }

    private void handleElement(XmlSchemaElement target, XmlSchemaElement source) throws ParserException {
        // handle substitution group if present
        if (source.getSubstitutionGroup() != null) {
            // overwrite target with the substitution group
            copyXmlSchemaObjectBase(target, sourceSchema.getElementByName(source.getSubstitutionGroup()));
        } else {
            handleTypeNameAndType(source.getSchemaTypeName(), source.getSchemaType(),
                target::setSchemaTypeName, target::setType);
        }
    }

    private void handleSimpleTypeList(XmlSchemaSimpleTypeList target, XmlSchemaSimpleTypeList source) throws ParserException {
        handleTypeNameAndType(source.getItemTypeName(), source.getItemType(),
                target::setItemTypeName, target::setItemType);
    }

    private void handleSimpleTypeRestriction(XmlSchemaSimpleTypeRestriction target,
                                             XmlSchemaSimpleTypeRestriction source) throws ParserException {
        handleTypeNameAndType(source.getBaseTypeName(), source.getBaseType(),
                target::setBaseTypeName, target::setBaseType);
    }

    private void handleSimpleTypeUnion(XmlSchemaSimpleTypeUnion target, XmlSchemaSimpleTypeUnion source) throws ParserException {

        final List<XmlSchemaSimpleType> targetBaseTypes = target.getBaseTypes();
        final List<QName> targetTypeNames = new ArrayList<>();

        for (XmlSchemaSimpleType baseType : source.getBaseTypes()) {
            targetBaseTypes.add(createXmlSchemaObjectBase(baseType));
        }

        // copy member types by QName
        final QName[] names = source.getMemberTypesQNames();
        for (QName name : names) {
            if (isXSDSchemaType(name)) {
                targetTypeNames.add(name);
            } else {
                final XmlSchemaSimpleType simpleType = (XmlSchemaSimpleType) sourceSchema.getTypeByName(name);
                if (simpleType == null) {
                    throw new ParserException("Missing simple type in source schema: " + name);
                }
                targetBaseTypes.add(createXmlSchemaObjectBase(simpleType));
            }
        }

        if (!targetTypeNames.isEmpty()) {
            target.setMemberTypesQNames(targetTypeNames.toArray(new QName[0]));
        }
    }

    private void handleSimpleType(XmlSchemaSimpleType target, XmlSchemaSimpleType source) throws ParserException {
        // handle content
        final XmlSchemaSimpleTypeContent sourceContent = source.getContent();
        if (sourceContent != null) {
            target.setContent(createXmlSchemaObjectBase(sourceContent));
        }
    }

    private void handleComplexType(XmlSchemaComplexType target, XmlSchemaComplexType source) throws ParserException {

        // copy attributes
        handleAttributesOrGroupRefs(target.getAttributes(), source.getAttributes());

        // handle contentModel
        final XmlSchemaContentModel sourceContentModel = source.getContentModel();
        if (sourceContentModel != null) {
            target.setContentModel(createXmlSchemaObjectBase(sourceContentModel));
        }

        handleParticle(source.getParticle(), target::setParticle);
    }

    // copy content to target model
    private void handleContentModel(XmlSchemaContentModel target, XmlSchemaContentModel source) throws ParserException {
        if (source.getContent() != null) {
            target.setContent(createXmlSchemaObjectBase(source.getContent()));
        } else {
            throw new ParserException("Unexpected NULL content in content model " + source.getClass().getName());
        }
    }

    private void handleSimpleContentExtension(XmlSchemaSimpleContentExtension target,
                                              XmlSchemaSimpleContentExtension source) throws ParserException {
        handleAttributesOrGroupRefs(target.getAttributes(), source.getAttributes());

        // copy baseTypeName
        // TODO handle references to non-XSD simple and complex types
        target.setBaseTypeName(source.getBaseTypeName());
    }

    private void handleSimpleContentRestriction(XmlSchemaSimpleContentRestriction target,
                                                XmlSchemaSimpleContentRestriction source) throws ParserException {
        handleAttributesOrGroupRefs(target.getAttributes(), source.getAttributes());
        // TODO handle references to non-XSD simple and complex types
        handleTypeNameAndType(source.getBaseTypeName(), source.getBaseType(), target::setBaseTypeName, target::setBaseType);
    }

    private void handleComplexContentRestriction(XmlSchemaComplexContentRestriction target,
                                                 XmlSchemaComplexContentRestriction source) throws ParserException {
        handleAttributesOrGroupRefs(target.getAttributes(), source.getAttributes());
        handleParticle(source.getParticle(), target::setParticle);

        // copy baseTypeName
        // TODO handle references to global simple and complex types
        target.setBaseTypeName(source.getBaseTypeName());
    }

    private void handleComplexContentExtension(XmlSchemaComplexContentExtension target,
                                               XmlSchemaComplexContentExtension source) throws ParserException {
        handleAttributesOrGroupRefs(target.getAttributes(), source.getAttributes());
        if (source.getParticle() != null) {
            handleParticle(source.getParticle(), target::setParticle);
        } else {
            // is built-in?
            final QName baseType = source.getBaseTypeName();
            if (isXSDSchemaType(baseType)) {
                target.setBaseTypeName(baseType);
            } else {
                // TODO handle references to global simple and complex types
                final XmlSchemaType sourceType = sourceSchema.getTypeByName(baseType);
                if (sourceType instanceof XmlSchemaComplexType) {
                    final XmlSchemaComplexType complexType = (XmlSchemaComplexType) sourceType;
                    if (complexType.getParticle() != null) {
                        handleParticle(source.getParticle(), target::setParticle);
                        // also add extension attributes
                        handleAttributesOrGroupRefs(target.getAttributes(), complexType.getAttributes());
                    }
                } else {
                    // TODO handle simple types
                    throw new ParserException("Unsupported extension of type " + baseType);
                }
            }
        }
    }

    // copy items from group particle to target
    @SuppressWarnings("unchecked")
    private void handleGroupParticle(XmlSchemaGroupParticle target, XmlSchemaGroupParticle source) throws ParserException {
        final List sourceItems;
        final List targetItems;

        if (source instanceof XmlSchemaAll) {
            sourceItems = ((XmlSchemaAll) source).getItems();
            targetItems = ((XmlSchemaAll) target).getItems();
        } else if (source instanceof XmlSchemaChoice) {
            sourceItems = ((XmlSchemaChoice)source).getItems();
            targetItems = ((XmlSchemaChoice)target).getItems();
        } else if (source instanceof XmlSchemaSequence) {
            sourceItems = ((XmlSchemaSequence)source).getItems();
            targetItems = ((XmlSchemaSequence)target).getItems();
        } else {
            throw new ParserException("Unsupported Group Particle type " + source.getClass().getName());
        }
        for (Object item : sourceItems) {
            if (item instanceof XmlSchemaGroupRef) {
                // get referenced group and add
                final XmlSchemaGroupRef groupRef = (XmlSchemaGroupRef) item;
                if (groupRef.getParticle() != null) {
                    targetItems.add(createXmlSchemaObjectBase(groupRef.getParticle()));
                } else {
                    final QName refName = groupRef.getRefName();
                    final XmlSchemaGroup group = sourceSchema.getGroupByName(refName);
                    if (group == null) {
                        throw new ParserException("Missing group in source schema: " + refName);
                    }
                    targetItems.add(createXmlSchemaObjectBase(group.getParticle()));
                }
            } else {
                targetItems.add(createXmlSchemaObjectBase((XmlSchemaObjectBase) item));
            }
        }
    }

    // copy attributes, target will only contain attributes
    private void handleAttributesOrGroupRefs(List<? super XmlSchemaAttributeOrGroupRef> target,
                                             List<? extends XmlSchemaAttributeOrGroupRef> source) throws ParserException {
        for (XmlSchemaAttributeOrGroupRef attribute : source) {
            if (attribute instanceof XmlSchemaAttributeGroupRef) {
                // copy attributes from group ref
                final XmlSchemaAttributeGroup attributeGroup = ((XmlSchemaAttributeGroupRef) attribute).getRef().getTarget();
                // TODO handle anyAttribute from group
                handleAttributeGroupMembers(target, attributeGroup.getAttributes());
            } else {
                target.add(createXmlSchemaObjectBase(attribute));
            }
        }
    }

    private void handleAttributeGroupMembers(List<? super XmlSchemaAttributeOrGroupRef> target, List<XmlSchemaAttributeGroupMember> source) throws ParserException {
        for (XmlSchemaAttributeGroupMember groupMember : source) {
            if (groupMember instanceof XmlSchemaAttribute) {
                // create copy of source attribute
                target.add(createXmlSchemaObjectBase((XmlSchemaAttribute)groupMember));
            } else if (groupMember instanceof XmlSchemaAttributeGroupRef) {
                // copy and add all attributes from group ref
                final XmlSchemaAttributeGroup attributeGroup = ((XmlSchemaAttributeGroupRef) groupMember).getRef().getTarget();
                handleAttributeGroupMembers(target, attributeGroup.getAttributes());
            } else if (groupMember instanceof XmlSchemaAttributeGroup) {
                // copy and add all attributes from group
                handleAttributeGroupMembers(target, ((XmlSchemaAttributeGroup) groupMember).getAttributes());
            }
        }
    }

    @SuppressWarnings("unchecked")
    private <T extends XmlSchemaType> void handleTypeNameAndType(final QName typeName, final T type,
                                                                 final Consumer<QName> setName,
                                                                 final Consumer<T> setType) throws ParserException {
        T sourceType = type;
        // get type if name is set
        if (typeName != null) {
            if (isXSDSchemaType(typeName)) {
                setName.accept(typeName);
                sourceType = null;
            } else {
                sourceType = (T) sourceSchema.getTypeByName(typeName);
                if (sourceType == null) {
                    throw new ParserException("Missing type in source schema: " + typeName);
                }
            }
        }

        // set type if set, or name is not XSD type
        if (sourceType != null) {
            setType.accept(createXmlSchemaObjectBase(sourceType));
        }
    }

    // copy particle
    private void handleParticle(XmlSchemaParticle particle, Consumer<XmlSchemaParticle> setParticle) throws ParserException {
        if (particle != null) {
            // handle ref particles
            if (particle instanceof XmlSchemaGroupRef) {
                final XmlSchemaGroupRef groupRef = (XmlSchemaGroupRef) particle;
                if (groupRef.getParticle() != null) {
                    setParticle.accept(createXmlSchemaObjectBase(groupRef.getParticle()));
                } else {
                    final QName refName = groupRef.getRefName();
                    final XmlSchemaGroup group = sourceSchema.getGroupByName(refName);
                    if (group == null) {
                        throw new ParserException("Missing group in source schema: " + refName);
                    }
                    setParticle.accept(createXmlSchemaObjectBase(group.getParticle()));
                }
            } else {
                setParticle.accept(createXmlSchemaObjectBase(particle));
            }
        }
    }

    private boolean isXSDSchemaType(QName typeName) {
        return Constants.URI_2001_SCHEMA_XSD.equals(typeName.getNamespaceURI());
    }

    @SuppressWarnings("unchecked")
    private <T extends XmlSchemaObjectBase> T createXmlSchemaObjectBase(T source) throws ParserException {

        // if source is an xsd:* type, return it as result
        if (source instanceof XmlSchemaType) {
            final QName qName = ((XmlSchemaType) source).getQName();
            if (qName != null && isXSDSchemaType(qName)) {
                return source;
            }
        }

        final T target;
        try {

            // is it a plain old XmlSchemaObject?
            if (!(source instanceof XmlSchemaNamed)) {
                final Constructor<?> constructor = source.getClass().getConstructors()[0];
                if (constructor.getParameterCount() == 0) {
                    target = (T) source.getClass().newInstance();
                } else {
                    // some ref types have a constructor that takes an XmlSchema
                    target = (T) constructor.newInstance(targetSchema);
                }
            } else {

                // try getting a ctor with XmlSchema parameter and maybe a Boolean.TYPE
                final Class<? extends XmlSchemaObjectBase> sourceClass = source.getClass();
                final Constructor<?>[] constructors = sourceClass.getConstructors();
                if (constructors.length != 1) {
                    throw new ParserException("Missing required constructor for named type " + source.getClass().getName());
                }
                if (constructors[0].getParameterCount() == 1) {
                    target = (T) constructors[0].newInstance(targetSchema);
                } else {
                    // the boolean parameter is always false, since we 'inline' all references
                    target = (T) constructors[0].newInstance(targetSchema, false);
                }

                // if present, copy the name of XmlSchemaNamed object
                // avoid naming local types, and all generated types MUST be local types
                final String name = ((XmlSchemaNamed) source).getName();
                if (name != null && !(target instanceof XmlSchemaType)) {
                    ((XmlSchemaNamed) target).setName(name);
                    // check if the form also needs to be set
                    if (source instanceof XmlSchemaNamedWithForm) {
                        ((XmlSchemaNamedWithForm) target).setForm(((XmlSchemaNamedWithForm) source).getForm());
                    }
                }

            }

        } catch (InstantiationException | IllegalAccessException | InvocationTargetException e) {
            throw new ParserException(String.format("Error extracting type %s: %s", source.getClass().getName(),
                    e.getMessage()), e);
        }

        // copy source to target using appropriate handlers
        copyXmlSchemaObjectBase(target,  source);

        return target;
    }

    private <T extends XmlSchemaObjectBase> void copyNonBlackListedProperties(T target, T source) throws ParserException {
        // copy all properties excluding black listed properties
        try {
            final List<String> properties = blackListedPropertiesMap.get(source.getClass());
            if (properties == null) {
                BeanUtils.copyProperties(source, target);
            } else {
                // iterate through properties and copy ones not in the black list
                for (PropertyDescriptor origDescriptor : PropertyUtils.getPropertyDescriptors(source)) {
                    final String name = origDescriptor.getName();
                    // ignore 'class' and blacklisted properties
                    if ("class".equals(name) || properties.contains(name)) {
                        continue;
                    }
                    if (PropertyUtils.isReadable(source, name) && PropertyUtils.isWriteable(target, name)) {
                        try {
                            final Object value = PropertyUtils.getSimpleProperty(source, name);
                            BeanUtils.copyProperty(target, name, value);
                        } catch (final NoSuchMethodException e) {
                            // Should not happen
                        }
                    }
                }
            }
        } catch (IllegalAccessException | InvocationTargetException e) {
            throw new ParserException(String.format("Error creating type %s: %s", target.getClass().getName(),
                    e.getMessage()), e);
        }
    }

    @FunctionalInterface
    private interface TriConsumer<X, T, S> {
        void apply(X x, T t, S s) throws ParserException;
    }

    private class ObjectPair<T extends XmlSchemaObjectBase> {

        private final T target;
        private final T source;
        private final List<? super XmlSchemaNamed> nodePath;

        ObjectPair(T target, T source) {
            this.target = target;
            this.source = source;

            nodePath = new ArrayList<>(currentPath);
            if (source instanceof XmlSchemaNamed && ((XmlSchemaNamed) source).isTopLevel()) {
                nodePath.add((XmlSchemaNamed) source);
            }
        }

        public T getTarget() {
            return target;
        }

        public T getSource() {
            return source;
        }

        Collection<? super XmlSchemaNamed> getNodePath() {
            return Collections.unmodifiableCollection(nodePath);
        }
    }
}
