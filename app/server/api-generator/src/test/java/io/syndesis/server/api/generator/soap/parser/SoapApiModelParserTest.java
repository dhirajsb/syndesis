package io.syndesis.server.api.generator.soap.parser;

import java.io.IOException;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

import io.syndesis.server.api.generator.soap.AbstractSoapExampleTest;
import io.syndesis.server.api.generator.soap.SoapApiModelInfo;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Test WSDL model parsing using {@link SoapApiModelParser}.
 */
@RunWith(Parameterized.class)
public class SoapApiModelParserTest extends AbstractSoapExampleTest {

    public SoapApiModelParserTest(String resource) throws IOException, InterruptedException {
        super(resource);
    }

    @Test
    public void parseSoapAPI() throws IOException {
        final SoapApiModelInfo soapApiModelInfo = SoapApiModelParser.parseSoapAPI(this.specification);

        assertThat(soapApiModelInfo).isNotNull();
        assertThat(soapApiModelInfo.getModel()).isNotNull();
    }

}