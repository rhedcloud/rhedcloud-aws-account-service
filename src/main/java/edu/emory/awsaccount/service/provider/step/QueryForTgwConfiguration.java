package edu.emory.awsaccount.service.provider.step;

import com.amazon.aws.moa.objects.resources.v1_0.Property;
import com.amazonaws.auth.AWSStaticCredentialsProvider;
import com.amazonaws.auth.BasicAWSCredentials;
import com.amazonaws.auth.BasicSessionCredentials;
import com.amazonaws.services.ec2.AmazonEC2Client;
import com.amazonaws.services.ec2.AmazonEC2ClientBuilder;
import com.amazonaws.services.ec2.model.DescribeTransitGatewayAttachmentsRequest;
import com.amazonaws.services.ec2.model.DescribeTransitGatewayAttachmentsResult;
import com.amazonaws.services.ec2.model.Filter;
import com.amazonaws.services.ec2.model.TransitGatewayAttachment;
import com.amazonaws.services.securitytoken.AWSSecurityTokenService;
import com.amazonaws.services.securitytoken.AWSSecurityTokenServiceClientBuilder;
import com.amazonaws.services.securitytoken.model.AssumeRoleRequest;
import com.amazonaws.services.securitytoken.model.AssumeRoleResult;
import com.amazonaws.services.securitytoken.model.Credentials;
import edu.emory.awsaccount.service.provider.VirtualPrivateCloudProvisioningProvider;
import org.openeai.config.AppConfig;

import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

public class QueryForTgwConfiguration extends AbstractStep implements Step {
    private String accessKeyId = null;
    private String secretKey = null;
    private String roleArnPattern = null;
    private int roleAssumptionDurationSeconds = 0;

    public void init(String provisioningId, Properties props,
                     AppConfig aConfig, VirtualPrivateCloudProvisioningProvider vpcpp)
            throws StepException {

        super.init(provisioningId, props, aConfig, vpcpp);

        String LOGTAG = getStepTag() + "[QueryForTgwConfiguration.init] ";

        // Get custom step properties.
        logger.info(LOGTAG + "Getting custom step properties...");

        accessKeyId = getProperties().getProperty("accessKeyId");
        logger.info(LOGTAG + "accessKeyId is: " + accessKeyId);

        secretKey = getProperties().getProperty("secretKey");
        logger.info(LOGTAG + "secretKey is: present");

        roleArnPattern = getProperties().getProperty("roleArnPattern");
        logger.info(LOGTAG + "roleArnPattern property is: " + roleArnPattern);

        roleAssumptionDurationSeconds = Integer.parseInt(getProperties().getProperty("roleAssumptionDurationSeconds", "30"));
        logger.info(LOGTAG + "roleAssumptionDurationSeconds is: " + roleAssumptionDurationSeconds);

        logger.info(LOGTAG + "Initialization complete.");
    }

    @Override
    protected List<Property> run() throws StepException {
        long startTime = System.currentTimeMillis();
        String LOGTAG = getStepTag() + "[QueryForTgwConfiguration.run] ";
        logger.info(LOGTAG + "Begin running the step.");

        // Return properties
        addResultProperty("stepExecutionMethod", RUN_EXEC_TYPE);

        boolean createVpc = Boolean.parseBoolean(getStepPropertyValue("DETERMINE_VPC_TYPE", "createVpc"));
        String vpcConnectionMethod = getStepPropertyValue("DETERMINE_VPC_CONNECTION_METHOD", "vpcConnectionMethod");

        if (createVpc && vpcConnectionMethod.equals("TGW")) {
            String accountId = getStepPropertyValue("CREATE_VPC_TYPE1_CFN_STACK", "accountId");
            String region = getVirtualPrivateCloudProvisioning().getVirtualPrivateCloudRequisition().getRegion();
            String transitGatewayId = getStepPropertyValue("DETERMINE_VPC_CONNECTION_METHOD", "transitGatewayId");
            String vpcId = getStepPropertyValue("CREATE_VPC_TYPE1_CFN_STACK", "VpcId");

            List<TransitGatewayAttachment> attachments = new ArrayList<>();

            try {
                AmazonEC2Client ec2Client = buildAmazonEC2Client(accountId, region);

                DescribeTransitGatewayAttachmentsRequest describeTransitGatewayAttachmentsRequest
                        = new DescribeTransitGatewayAttachmentsRequest()
                            .withFilters(new Filter("transit-gateway-id").withValues(transitGatewayId),
                                new Filter("resource-id").withValues(vpcId));
                DescribeTransitGatewayAttachmentsResult describeTransitGatewayAttachmentsResult;
                do {
                    describeTransitGatewayAttachmentsResult = ec2Client.describeTransitGatewayAttachments(describeTransitGatewayAttachmentsRequest);
                    attachments.addAll(describeTransitGatewayAttachmentsResult.getTransitGatewayAttachments());
                    describeTransitGatewayAttachmentsRequest.setNextToken(describeTransitGatewayAttachmentsResult.getNextToken());
                } while (describeTransitGatewayAttachmentsResult.getNextToken() != null);
            }
            catch (Exception e) {
                String errMsg = "Error running ec2:DescribeTransitGatewayAttachments. The exception is: " + e.getMessage();
                logger.error(LOGTAG + errMsg);
                throw new StepException(errMsg);
            }

            if (attachments.size() != 1) {
                String errMsg = "Invalid number of results returned from ec2:DescribeTransitGatewayAttachments. " +
                        attachments.size() + " results returned. Expected exactly 1.";
                logger.error(LOGTAG + errMsg);
                throw new StepException(errMsg);
            }

            addResultProperty("transitGatewayAttachmentId", attachments.get(0).getTransitGatewayAttachmentId());
            addResultProperty("transitGatewayAttachmentState", attachments.get(0).getState());
        }
        else {
            logger.info(LOGTAG + "Bypass TGW inspection: not creating VPC or not TGW connectivity");
        }

        // Update the step.
        update(COMPLETED_STATUS, SUCCESS_RESULT);

        // Log completion time.
        long time = System.currentTimeMillis() - startTime;
        logger.info(LOGTAG + "Step run completed in " + time + "ms.");

        // Return the properties.
        return getResultProperties();
    }

    @Override
    protected List<Property> simulate() throws StepException {
        long startTime = System.currentTimeMillis();
        String LOGTAG = getStepTag() + "[QueryForTgwConfiguration.simulate] ";
        logger.info(LOGTAG + "Begin step simulation.");

        // Set return properties.
        addResultProperty("stepExecutionMethod", SIMULATED_EXEC_TYPE);

        // Update the step.
        update(COMPLETED_STATUS, SUCCESS_RESULT);

        // Log completion time.
        long time = System.currentTimeMillis() - startTime;
        logger.info(LOGTAG + "Step simulation completed in " + time + "ms.");

        // Return the properties.
        return getResultProperties();
    }

    @Override
    protected List<Property> fail() throws StepException {
        long startTime = System.currentTimeMillis();
        String LOGTAG = getStepTag() + "[QueryForTgwConfiguration.fail] ";
        logger.info(LOGTAG + "Begin step failure simulation.");

        // Set return properties.
        addResultProperty("stepExecutionMethod", FAILURE_EXEC_TYPE);

        // Update the step.
        update(COMPLETED_STATUS, FAILURE_RESULT);

        // Log completion time.
        long time = System.currentTimeMillis() - startTime;
        logger.info(LOGTAG + "Step failure simulation completed in " + time + "ms.");

        // Return the properties.
        return getResultProperties();
    }

    @Override
    public void rollback() throws StepException {
        long startTime = System.currentTimeMillis();

        super.rollback();

        String LOGTAG = getStepTag() + "[QueryForTgwConfiguration.rollback] ";
        logger.info(LOGTAG + "Rollback called, but this step has nothing to roll back.");

        update(ROLLBACK_STATUS, SUCCESS_RESULT);

        // Log completion time.
        long time = System.currentTimeMillis() - startTime;
        logger.info(LOGTAG + "Rollback completed in " + time + "ms.");
    }

    /**
     * Amazon EC2 client connected to the correct account with the correct role
     */
    private AmazonEC2Client buildAmazonEC2Client(String accountId, String region) {
        String roleArn = roleArnPattern.replace("ACCOUNT_NUMBER", accountId);

        // use the master account credentials to get the STS client
        BasicAWSCredentials creds = new BasicAWSCredentials(accessKeyId, secretKey);
        AWSStaticCredentialsProvider cp = new AWSStaticCredentialsProvider(creds);

        AWSSecurityTokenService sts = AWSSecurityTokenServiceClientBuilder.standard()
                .withCredentials(cp)
                .withRegion(region)
                .build();

        AssumeRoleRequest assumeRequest = new AssumeRoleRequest().withRoleArn(roleArn)
                .withDurationSeconds(roleAssumptionDurationSeconds)
                .withRoleSessionName("AwsAccountService");

        AssumeRoleResult assumeResult = sts.assumeRole(assumeRequest);
        Credentials credentials = assumeResult.getCredentials();

        // now use the session credentials to get the EC2 client
        BasicSessionCredentials sessionCredentials = new BasicSessionCredentials(credentials.getAccessKeyId(),
                credentials.getSecretAccessKey(), credentials.getSessionToken());
        cp = new AWSStaticCredentialsProvider(sessionCredentials);

        return (AmazonEC2Client) AmazonEC2ClientBuilder.standard()
                .withCredentials(cp)
                .withRegion(region)
                .build();
    }
}
