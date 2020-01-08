package io.syndesis.server.api.generator.soap.parser;

import java.io.StringReader;
import java.util.Arrays;
import java.util.Collection;

import org.apache.cxf.staxutils.StaxUtils;
import org.apache.ws.commons.schema.XmlSchema;
import org.apache.ws.commons.schema.XmlSchemaCollection;
import org.apache.ws.commons.schema.XmlSchemaElement;
import org.apache.ws.commons.schema.XmlSchemaSerializer;
import org.apache.ws.commons.schema.XmlSchemaType;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.xml.sax.InputSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link XmlSchemaExtractor}.
 */
public class XmlSchemaExtractorTest {

    public static final String TEST_NAMESPACE = "http://www.redhat.com/io/syndesis";
    public static final String TEST_SCHEMA = "/soap/parser/test-schema.xsd";
    private static XmlSchema sourceSchema;

    private XmlSchemaExtractor xmlSchemaExtractor;

    @BeforeClass
    public static void setupClass() {
        XmlSchemaCollection collection = new XmlSchemaCollection();
        collection.read(new InputSource(XmlSchemaExtractorTest.class.getResourceAsStream(TEST_SCHEMA)));
        sourceSchema = Arrays.stream(collection.getXmlSchemas())
            .filter(x -> TEST_NAMESPACE.equals(x.getTargetNamespace()))
            .findFirst().orElseThrow(() -> new IllegalStateException("Missing test schema " + TEST_SCHEMA));
    }

    @Before
    public void setup() {
        final XmlSchema targetSchema = new XmlSchema(TEST_NAMESPACE, new XmlSchemaCollection());
        xmlSchemaExtractor = new XmlSchemaExtractor(targetSchema, sourceSchema);
    }

    @Test
    public void extractElements() throws ParserException, XmlSchemaSerializer.XmlSchemaSerializerException {

        // extract all top level elements from source schema
        final Collection<XmlSchemaElement> values = xmlSchemaExtractor.getSourceSchema().getElements().values();
        for (XmlSchemaElement element : values) {
            xmlSchemaExtractor.extract(element, true);
        }
        xmlSchemaExtractor.copyObjects();

        validateSchema(xmlSchemaExtractor.getTargetSchema());
    }

    @Test
    public void extractTypes() throws ParserException, XmlSchemaSerializer.XmlSchemaSerializerException {

        // extract all top level elements from source schema
        final Collection<XmlSchemaType> values = xmlSchemaExtractor.getSourceSchema().getSchemaTypes().values();
        for (XmlSchemaType type : values) {
            xmlSchemaExtractor.extract(type.getName(), type, true);
        }
        xmlSchemaExtractor.copyObjects();

        validateSchema(xmlSchemaExtractor.getTargetSchema());
    }

    private void validateSchema(XmlSchema targetSchema) throws XmlSchemaSerializer.XmlSchemaSerializerException {
        final String schemaString = StaxUtils.toString(targetSchema.getSchemaDocument());
        final XmlSchema schema = new XmlSchemaCollection().read(new StringReader(schemaString));
        assertThat(schema.getElements()).isNotEmpty();
        assertThat(schema.getSchemaTypes()).isEmpty();
    }
}
