package io.syndesis.server.api.generator.soap;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;

import org.junit.runners.Parameterized;

import io.syndesis.common.util.IOStreams;
import io.syndesis.server.api.generator.soap.parser.SoapApiModelParserTest;

/**
 * Base class for example WSDL driven tests.
 */
public class AbstractSoapExampleTest {

    protected final String specification;

    public AbstractSoapExampleTest(String resource) throws IOException, InterruptedException {
        if (resource.startsWith("http")) {
            // add a slight delay to avoid DDoS behavior on server
            Thread.sleep(500);
            this.specification = resource;
        } else {
            this.specification = IOStreams.readText(SoapApiModelParserTest.class.getResourceAsStream(resource));
        }
    }

    @Parameterized.Parameters(name = "{0}")
    public static List<String> parameters() {
        return Arrays.asList(
                "/soap/HelloWorld.wsdl",
                "/soap/StockQuote.wsdl",
                // ALL WorkDay WSDLs
                "https://community.workday.com/sites/default/files/file-hosting/productionapi/Absence_Management/v33.1/Absence_Management.wsdl",
                "https://community.workday.com/sites/default/files/file-hosting/productionapi/Academic_Advising/v33.1/Academic_Advising.wsdl",
                "https://community.workday.com/sites/default/files/file-hosting/productionapi/Academic_Foundation/v33.1/Academic_Foundation.wsdl",
                "https://community.workday.com/sites/default/files/file-hosting/productionapi/Admissions/v33.1/Admissions.wsdl",
                "https://community.workday.com/sites/default/files/file-hosting/productionapi/Adoption/v33.1/Adoption.wsdl",
                "https://community.workday.com/sites/default/files/file-hosting/productionapi/Benefits_Administration/v33.1/Benefits_Administration.wsdl",
                "https://community.workday.com/sites/default/files/file-hosting/productionapi/Campus_Engagement/v33.1/Campus_Engagement.wsdl",
                "https://community.workday.com/sites/default/files/file-hosting/productionapi/Cash_Management/v33.1/Cash_Management.wsdl",
                "https://community.workday.com/sites/default/files/file-hosting/productionapi/Compensation/v33.1/Compensation.wsdl",
                "https://community.workday.com/sites/default/files/file-hosting/productionapi/Compensation_Review/v33.1/Compensation_Review.wsdl",
                "https://community.workday.com/sites/default/files/file-hosting/productionapi/Dynamic_Document_Generation/v33.1/Dynamic_Document_Generation.wsdl",
                "https://community.workday.com/sites/default/files/file-hosting/productionapi/External_Integrations/v33.1/External_Integrations.wsdl",
                "https://community.workday.com/sites/default/files/file-hosting/productionapi/Financial_Aid/v33.1/Financial_Aid.wsdl",
                "https://community.workday.com/sites/default/files/file-hosting/productionapi/Financial_Management/v33.1/Financial_Management.wsdl",
                "https://community.workday.com/sites/default/files/file-hosting/productionapi/Human_Resources/v33.1/Human_Resources.wsdl",
                "https://community.workday.com/sites/default/files/file-hosting/productionapi/Identity_Management/v33.1/Identity_Management.wsdl",
                "https://community.workday.com/sites/default/files/file-hosting/productionapi/Integrations/v33.1/Integrations.wsdl",
                "https://community.workday.com/sites/default/files/file-hosting/productionapi/Interview_Feedback__Do_Not_Use_/v33.1/Interview_Feedback__Do_Not_Use_.wsdl",
                "https://community.workday.com/sites/default/files/file-hosting/productionapi/Inventory/v33.1/Inventory.wsdl",
                "https://community.workday.com/sites/default/files/file-hosting/productionapi/Learning/v33.1/Learning.wsdl",
                "https://community.workday.com/sites/default/files/file-hosting/productionapi/Notification/v33.1/Notification.wsdl",
                "https://community.workday.com/sites/default/files/file-hosting/productionapi/Payroll/v33.1/Payroll.wsdl",
                "https://community.workday.com/sites/default/files/file-hosting/productionapi/Payroll_CAN/v33.1/Payroll_CAN.wsdl",
                "https://community.workday.com/sites/default/files/file-hosting/productionapi/Payroll_FRA/v33.1/Payroll_FRA.wsdl",
                "https://community.workday.com/sites/default/files/file-hosting/productionapi/Payroll_GBR/v33.1/Payroll_GBR.wsdl",
                "https://community.workday.com/sites/default/files/file-hosting/productionapi/Payroll_Interface/v33.1/Payroll_Interface.wsdl",
                "https://community.workday.com/sites/default/files/file-hosting/productionapi/Performance_Management/v33.1/Performance_Management.wsdl",
                "https://community.workday.com/sites/default/files/file-hosting/productionapi/Professional_Services_Automation/v33.1/Professional_Services_Automation.wsdl",
                "https://community.workday.com/sites/default/files/file-hosting/productionapi/Recruiting/v33.1/Recruiting.wsdl",
                "https://community.workday.com/sites/default/files/file-hosting/productionapi/Resource_Management/v33.1/Resource_Management.wsdl",
                "https://community.workday.com/sites/default/files/file-hosting/productionapi/Revenue_Management/v33.1/Revenue_Management.wsdl",
                "https://community.workday.com/sites/default/files/file-hosting/productionapi/Settlement_Services/v33.1/Settlement_Services.wsdl",
                "https://community.workday.com/sites/default/files/file-hosting/productionapi/Staffing/v33.1/Staffing.wsdl",
                "https://community.workday.com/sites/default/files/file-hosting/productionapi/Student_Core/v33.1/Student_Core.wsdl",
                "https://community.workday.com/sites/default/files/file-hosting/productionapi/Student_Finance/v33.1/Student_Finance.wsdl",
                "https://community.workday.com/sites/default/files/file-hosting/productionapi/Student_Records/v33.1/Student_Records.wsdl",
                "https://community.workday.com/sites/default/files/file-hosting/productionapi/Student_Recruiting/v33.1/Student_Recruiting.wsdl",
                "https://community.workday.com/sites/default/files/file-hosting/productionapi/Talent/v33.1/Talent.wsdl",
                "https://community.workday.com/sites/default/files/file-hosting/productionapi/Tenant_Data_Translation/v33.1/Tenant_Data_Translation.wsdl",
                "https://community.workday.com/sites/default/files/file-hosting/productionapi/Time_Tracking/v33.1/Time_Tracking.wsdl",
                "https://community.workday.com/sites/default/files/file-hosting/productionapi/Workday_Connect/v33.1/Workday_Connect.wsdl",
                "https://community.workday.com/sites/default/files/file-hosting/productionapi/Workday_Extensibility/v33.1/Workday_Extensibility.wsdl",
                "https://community.workday.com/sites/default/files/file-hosting/productionapi/Workforce_Planning/v33.1/Workforce_Planning.wsdl"
        );
    }
}
