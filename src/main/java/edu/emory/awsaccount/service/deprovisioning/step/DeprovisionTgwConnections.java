package edu.emory.awsaccount.service.deprovisioning.step;

import com.amazon.aws.moa.objects.resources.v1_0.Property;
import com.amazonaws.auth.AWSStaticCredentialsProvider;
import com.amazonaws.auth.BasicAWSCredentials;
import com.amazonaws.auth.BasicSessionCredentials;
import com.amazonaws.services.ec2.AmazonEC2Client;
import com.amazonaws.services.ec2.AmazonEC2ClientBuilder;
import com.amazonaws.services.ec2.model.AmazonEC2Exception;
import com.amazonaws.services.ec2.model.DeleteTransitGatewayVpcAttachmentRequest;
import com.amazonaws.services.ec2.model.DescribeTransitGatewayAttachmentsRequest;
import com.amazonaws.services.ec2.model.DescribeTransitGatewayAttachmentsResult;
import com.amazonaws.services.ec2.model.DisableTransitGatewayRouteTablePropagationRequest;
import com.amazonaws.services.ec2.model.DisassociateTransitGatewayRouteTableRequest;
import com.amazonaws.services.ec2.model.Filter;
import com.amazonaws.services.ec2.model.GetTransitGatewayAttachmentPropagationsRequest;
import com.amazonaws.services.ec2.model.GetTransitGatewayAttachmentPropagationsResult;
import com.amazonaws.services.ec2.model.TransitGatewayAttachment;
import com.amazonaws.services.ec2.model.TransitGatewayAttachmentPropagation;
import com.amazonaws.services.securitytoken.AWSSecurityTokenService;
import com.amazonaws.services.securitytoken.AWSSecurityTokenServiceClientBuilder;
import com.amazonaws.services.securitytoken.model.AssumeRoleRequest;
import com.amazonaws.services.securitytoken.model.AssumeRoleResult;
import com.amazonaws.services.securitytoken.model.Credentials;
import edu.emory.awsaccount.service.provider.AccountDeprovisioningProvider;
import org.openeai.config.AppConfig;

import java.util.Arrays;
import java.util.List;
import java.util.Properties;

/**
 * Deprovision TGW connections for all VPCs associated with an account.
 * <br/>
 * This step basically undoes what the AssociateVpcToTgwRouteTable and PropagateVpcCidrToTgwRouteTable steps did.
 *
 * @author Steve Wheat (swheat@emory.edu)
 * @version 1.0 - 21 May 2017
 **/
public class DeprovisionTgwConnections extends AbstractStep implements Step {
    private String accessKeyId = null;
    private String secretKey = null;
    private String roleArnPattern = null;
    private int roleAssumptionDurationSeconds = 0;
    private List<String> regions;

    public void init(String provisioningId, Properties props, AppConfig aConfig, AccountDeprovisioningProvider adp) throws StepException {
        super.init(provisioningId, props, aConfig, adp);

        String LOGTAG = getStepTag() + "[DeprovisionTgwConnections.init] ";

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

        String regionNames = getProperties().getProperty("regions", "");
        regions = Arrays.asList(regionNames.split("\\s*,\\s*"));
        if (regions.isEmpty()) {
            String message = "regions cannot be null or empty";
            logger.error(LOGTAG + message);
            throw new StepException(message);
        }
        logger.info(LOGTAG + "regions is: " + regions);

        logger.info(LOGTAG + "Initialization complete.");
    }

    protected List<Property> run() throws StepException {
        long startTime = System.currentTimeMillis();
        String LOGTAG = getStepTag() + "[DeprovisionTgwConnections.run] ";
        logger.info(LOGTAG + "Begin running the step.");

        addResultProperty("stepExecutionMethod", RUN_EXEC_TYPE);


        // Get the list of VPCs from a previous step.
        String vpcIds = getStepPropertyValue("LIST_VPC_IDS", "tgwVpcIds");

        // If there are no VPCs there is nothing to do and the step is complete.
        if (vpcIds.equals(PROPERTY_VALUE_NOT_AVAILABLE) || vpcIds.equals("none")) {
            logger.info(LOGTAG + "There are no TGW VPCs.");
            // Update the step.
            update(COMPLETED_STATUS, SUCCESS_RESULT);

            // Log completion time.
            long time = System.currentTimeMillis() - startTime;
            logger.info(LOGTAG + "Step run completed in " + time + "ms.");

            // Return the properties.
            return getResultProperties();
        }

        // Get the VPCs as a list.
        List<String> vpcList = Arrays.asList(vpcIds.split("\\s*,\\s*"));
        logger.info(LOGTAG + "There are " + vpcList.size() + " TGW VPCs.");

        // If there are no VPCs in the list there is nothing to do and the step is complete.
        if (vpcList.size() == 0) {
            logger.info(LOGTAG + "There are no TGW VPCs.");
            // Update the step.
            update(COMPLETED_STATUS, SUCCESS_RESULT);

            // Log completion time.
            long time = System.currentTimeMillis() - startTime;
            logger.info(LOGTAG + "Step run completed in " + time + "ms.");

            // Return the properties.
            return getResultProperties();
        }

        String accountId = getAccountDeprovisioning().getAccountDeprovisioningRequisition().getAccountId();

        /*
         * the first step is to figure out what region the VPC is in by looking for the TGW attachment.
         * if a TGW attachment isn't found for the VPC, it is not considered an error because a previous
         * deprovisioning run could have deleted the TGW attachment already.
         *
         * the next steps undo the attachment route table propagations and the association between the
         * attachment from the TGW route table.
         * these steps can fail because a previous deprovisioning run could have deleted the TGW attachment
         * but it doesn't immediately disappear from the list returned by describeTransitGatewayAttachments().
         * generally, if it's already been deleted further operations will result in an IncorrectState exception.
         */
        try {
            for (String vpcId : vpcList) {
                AmazonEC2Client memberEc2Client = null;  // assume role in the linked/member account
                AmazonEC2Client tgwEc2Client;  // assume role in the TGW owner account
                String vpcRegion = null;
                TransitGatewayAttachment tgwAttachment = null;

                /*
                 * figure out what region the VPC is in by looking for the TGW attachment
                 */
                foundRegion:
                for (String region : regions) {
                    memberEc2Client = buildAmazonEC2Client(accountId, region);

                    DescribeTransitGatewayAttachmentsRequest describeTransitGatewayAttachmentsRequest
                            = new DescribeTransitGatewayAttachmentsRequest()
                                    .withFilters(new Filter("resource-type").withValues("vpc"),
                                                new Filter("resource-id").withValues(vpcId));
                    DescribeTransitGatewayAttachmentsResult describeTransitGatewayAttachmentsResult;
                    do {
                        describeTransitGatewayAttachmentsResult = memberEc2Client.describeTransitGatewayAttachments(describeTransitGatewayAttachmentsRequest);
                        // there should only be one VPC attachment that was created by the CFN template
                        if (describeTransitGatewayAttachmentsResult.getTransitGatewayAttachments().size() > 0) {
                            vpcRegion = region;
                            tgwAttachment = describeTransitGatewayAttachmentsResult.getTransitGatewayAttachments().get(0);
                            break foundRegion;
                        }
                        describeTransitGatewayAttachmentsRequest.setNextToken(describeTransitGatewayAttachmentsResult.getNextToken());
                    } while (describeTransitGatewayAttachmentsResult.getNextToken() != null);
                }

                if (vpcRegion == null) {
                    String msg = "Could not find a Transit Gateway VPC attachment for VPC " + vpcId + " in any region";
                    logger.info(LOGTAG + msg);

                    // The step is done. Update the step.
                    update(COMPLETED_STATUS, SUCCESS_RESULT);

                    // Log completion time.
                    long time = System.currentTimeMillis() - startTime;
                    logger.info(LOGTAG + "Step run completed in " + time + "ms.");

                    // Return the properties.
                    return getResultProperties();
                }

                /*
                 * next step is to undo the attachment route table propagations,
                 * by using the EC2 client for the owner of the TGW
                 */
                tgwEc2Client = buildAmazonEC2Client(tgwAttachment.getTransitGatewayOwnerId(), vpcRegion);

                try {
                    GetTransitGatewayAttachmentPropagationsRequest propagationsRequest
                            = new GetTransitGatewayAttachmentPropagationsRequest()
                                    .withTransitGatewayAttachmentId(tgwAttachment.getTransitGatewayAttachmentId());
                    GetTransitGatewayAttachmentPropagationsResult propagationsResult;
                    do {
                        propagationsResult = tgwEc2Client.getTransitGatewayAttachmentPropagations(propagationsRequest);
                        for (TransitGatewayAttachmentPropagation p : propagationsResult.getTransitGatewayAttachmentPropagations()) {
                            DisableTransitGatewayRouteTablePropagationRequest disableTgwRouteTablePropagationRequest
                                    = new DisableTransitGatewayRouteTablePropagationRequest()
                                            .withTransitGatewayAttachmentId(tgwAttachment.getTransitGatewayAttachmentId())
                                            .withTransitGatewayRouteTableId(p.getTransitGatewayRouteTableId());
                            tgwEc2Client.disableTransitGatewayRouteTablePropagation(disableTgwRouteTablePropagationRequest);
                        }
                        propagationsRequest.setNextToken(propagationsResult.getNextToken());
                    } while (propagationsResult.getNextToken() != null);
                }
                catch (AmazonEC2Exception e) {
                    // an IncorrectState exception is not considered an error.  see discussion above.
                    if (!e.getErrorCode().equals("IncorrectState")) {
                        throw e;
                    }
                }


                /*
                 * next step is to undo the association between the attachment from the TGW route table.
                 * it could have been done already in a previous deprovisioning run so be guarded.
                 */
                if (tgwAttachment.getAssociation() != null && tgwAttachment.getAssociation().getTransitGatewayRouteTableId() != null) {
                    try {
                        DisassociateTransitGatewayRouteTableRequest disassociateTgwRouteTableRequest
                                = new DisassociateTransitGatewayRouteTableRequest()
                                        .withTransitGatewayAttachmentId(tgwAttachment.getTransitGatewayAttachmentId())
                                        .withTransitGatewayRouteTableId(tgwAttachment.getAssociation().getTransitGatewayRouteTableId());
                        tgwEc2Client.disassociateTransitGatewayRouteTable(disassociateTgwRouteTableRequest);
                    }
                    catch (AmazonEC2Exception e) {
                        // an IncorrectState exception is not considered an error.  see discussion above.
                        if (!e.getErrorCode().equals("IncorrectState")) {
                            throw e;
                        }
                    }
                }

                /*
                 * final step is to delete the VPC attachment from the TGW.
                 */
                try {
                    DeleteTransitGatewayVpcAttachmentRequest deleteTransitGatewayVpcAttachmentRequest = new DeleteTransitGatewayVpcAttachmentRequest()
                            .withTransitGatewayAttachmentId(tgwAttachment.getTransitGatewayAttachmentId());
                    memberEc2Client.deleteTransitGatewayVpcAttachment(deleteTransitGatewayVpcAttachmentRequest);
                }
                catch (AmazonEC2Exception e) {
                    // an IncorrectState exception is not considered an error.  see discussion above.
                    if (!e.getErrorCode().equals("IncorrectState")) {
                        throw e;
                    }
                }
            }
        }
        catch (Exception e) {
            String errMsg = "An error occurred deprovisioning the TGW. The exception is: " + e.getMessage();
            logger.error(LOGTAG + errMsg);
            throw new StepException(errMsg, e);
        }

        // The step is done. Update the step.
        update(COMPLETED_STATUS, SUCCESS_RESULT);

        // Log completion time.
        long time = System.currentTimeMillis() - startTime;
        logger.info(LOGTAG + "Step run completed in " + time + "ms.");

        // Return the properties.
        return getResultProperties();
    }

    protected List<Property> simulate() throws StepException {
        long startTime = System.currentTimeMillis();
        String LOGTAG = getStepTag() + "[DeprovisionTgwConnections.simulate] ";
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

    protected List<Property> fail() throws StepException {
        long startTime = System.currentTimeMillis();
        String LOGTAG = getStepTag() + "[DeprovisionTgwConnections.fail] ";
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

    public void rollback() throws StepException {
        long startTime = System.currentTimeMillis();
        String LOGTAG = getStepTag() + "[DeprovisiongVpnConnections.rollback] ";

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
        BasicAWSCredentials masterCredentials = new BasicAWSCredentials(accessKeyId, secretKey);
        AWSStaticCredentialsProvider cp = new AWSStaticCredentialsProvider(masterCredentials);

        AWSSecurityTokenService sts = AWSSecurityTokenServiceClientBuilder.standard()
                .withCredentials(cp)
                .withRegion(region)
                .build();

        AssumeRoleRequest assumeRequest = new AssumeRoleRequest()
                .withRoleArn(roleArn)
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
