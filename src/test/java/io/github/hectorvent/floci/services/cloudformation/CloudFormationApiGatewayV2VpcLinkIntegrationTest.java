package io.github.hectorvent.floci.services.cloudformation;

import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.apigatewayv2.ApiGatewayV2Service;
import io.github.hectorvent.floci.services.apigatewayv2.model.Integration;
import io.github.hectorvent.floci.services.apigatewayv2.model.VpcLink;
import io.github.hectorvent.floci.services.cloudformation.model.Stack;
import io.github.hectorvent.floci.services.cloudformation.model.StackResource;
import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.specification.RequestSpecification;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * AWS::ApiGatewayV2::VpcLink through a real stack: Ref and Fn::GetAtt VpcLinkId resolve to the
 * real link, an integration can use it through ConnectionId, a Name change updates the link in
 * place, a SubnetIds change replaces it, and DeleteStack removes it.
 */
@QuarkusTest
class CloudFormationApiGatewayV2VpcLinkIntegrationTest {

    private static final String REGION = "us-east-1";
    private static final String STACK_NAME = "vpclink-stack";

    @Inject
    ApiGatewayV2Service apiGatewayV2Service;

    @Inject
    CloudFormationService cloudFormationService;

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void vpcLinkLifecycleFollowsTheCloudFormationUpdateRules() {
        stackAction("CreateStack", template("first-name", "subnet-aaaa1111"));
        waitForStackStatus("CREATE_COMPLETE");

        Stack created = stack();
        String vpcLinkId = created.getOutputs().get("VpcLinkRef");
        assertEquals(vpcLinkId, created.getOutputs().get("VpcLinkIdAttr"));
        VpcLink link = apiGatewayV2Service.getVpcLink(REGION, vpcLinkId);
        assertEquals("first-name", link.getName());
        assertEquals(List.of("subnet-aaaa1111"), link.getSubnetIds());
        assertEquals(Map.of("Env", "test"), link.getTags());
        Integration integration = integration(created);
        assertEquals("VPC_LINK", integration.getConnectionType());
        assertEquals(vpcLinkId, integration.getConnectionId());

        stackAction("UpdateStack", template("second-name", "subnet-aaaa1111"));
        waitForStackStatus("UPDATE_COMPLETE");

        assertEquals(vpcLinkId, stack().getOutputs().get("VpcLinkRef"), "a Name change keeps the same link");
        assertEquals("second-name", apiGatewayV2Service.getVpcLink(REGION, vpcLinkId).getName());

        stackAction("UpdateStack", template("second-name", "subnet-bbbb2222"));
        waitForStackStatus("UPDATE_COMPLETE");

        Stack replaced = stack();
        String newVpcLinkId = replaced.getOutputs().get("VpcLinkRef");
        assertNotEquals(vpcLinkId, newVpcLinkId, "a SubnetIds change replaces the link");
        assertEquals(List.of("subnet-bbbb2222"), apiGatewayV2Service.getVpcLink(REGION, newVpcLinkId).getSubnetIds());
        assertNotFound(vpcLinkId);
        assertEquals(newVpcLinkId, integration(replaced).getConnectionId());

        stackAction("DeleteStack", null);
        waitForStackStatus("DELETE_COMPLETE");

        assertNotFound(newVpcLinkId);
    }

    private void assertNotFound(String vpcLinkId) {
        AwsException e = assertThrows(AwsException.class, () -> apiGatewayV2Service.getVpcLink(REGION, vpcLinkId));
        assertEquals("NotFoundException", e.getErrorCode());
    }

    private Integration integration(Stack stack) {
        StackResource api = stack.getResources().get("HttpApi");
        StackResource integration = stack.getResources().get("Integration");
        return apiGatewayV2Service.getIntegration(REGION, api.getPhysicalId(), integration.getPhysicalId());
    }

    private Stack stack() {
        return cloudFormationService.describeStacks(STACK_NAME, REGION).getFirst();
    }

    private static void stackAction(String action, String template) {
        RequestSpecification request = given()
                .contentType("application/x-www-form-urlencoded")
                .formParam("Action", action)
                .formParam("StackName", STACK_NAME);
        if (template != null) {
            request.formParam("TemplateBody", template);
        }
        request.when().post("/").then().statusCode(200);
    }

    private void waitForStackStatus(String expected) {
        String status = null;
        for (int attempt = 0; attempt < 200; attempt++) {
            try {
                status = cloudFormationService.describeStacks(STACK_NAME, REGION).getFirst().getStatus();
            } catch (AwsException e) {
                // A deleted stack can no longer be described by name.
                status = "DELETE_COMPLETE";
            }
            if (expected.equals(status)) {
                return;
            }
            try {
                Thread.sleep(25);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError("Interrupted while waiting for stack status", e);
            }
        }
        throw new AssertionError("Stack did not reach " + expected + ", last status " + status);
    }

    private static String template(String linkName, String subnetId) {
        return """
                {"Resources":{
                  "VpcLink":{"Type":"AWS::ApiGatewayV2::VpcLink","Properties":{
                    "Name":"%s","SubnetIds":["%s"],"SecurityGroupIds":["sg-cccc3333"],"Tags":{"Env":"test"}}},
                  "HttpApi":{"Type":"AWS::ApiGatewayV2::Api","Properties":{
                    "Name":"vpclink-api","ProtocolType":"HTTP"}},
                  "Integration":{"Type":"AWS::ApiGatewayV2::Integration","Properties":{
                    "ApiId":{"Ref":"HttpApi"},"IntegrationType":"HTTP_PROXY","IntegrationMethod":"ANY",
                    "IntegrationUri":"arn:aws:elasticloadbalancing:us-east-1:000000000000:listener/app/alb/1/2",
                    "ConnectionType":"VPC_LINK","ConnectionId":{"Ref":"VpcLink"},
                    "PayloadFormatVersion":"1.0"}}},
                 "Outputs":{
                  "VpcLinkRef":{"Value":{"Ref":"VpcLink"}},
                  "VpcLinkIdAttr":{"Value":{"Fn::GetAtt":["VpcLink","VpcLinkId"]}}}}
                """.formatted(linkName, subnetId);
    }
}
