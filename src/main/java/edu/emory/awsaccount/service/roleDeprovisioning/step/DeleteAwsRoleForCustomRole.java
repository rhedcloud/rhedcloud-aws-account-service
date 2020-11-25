/* *****************************************************************************
 This file is part of the RHEDcloud AWS Account Service.

 Copyright 2020 RHEDcloud Foundation. All rights reserved.
 ******************************************************************************/

package edu.emory.awsaccount.service.roleDeprovisioning.step;

import com.amazon.aws.moa.objects.resources.v1_0.Property;
import com.amazon.aws.moa.objects.resources.v1_0.RoleDeprovisioningRequisition;
import com.amazonaws.auth.AWSStaticCredentialsProvider;
import com.amazonaws.auth.BasicAWSCredentials;
import com.amazonaws.auth.BasicSessionCredentials;
import com.amazonaws.services.identitymanagement.AmazonIdentityManagement;
import com.amazonaws.services.identitymanagement.AmazonIdentityManagementClientBuilder;
import com.amazonaws.services.identitymanagement.model.DeleteRoleRequest;
import com.amazonaws.services.securitytoken.AWSSecurityTokenService;
import com.amazonaws.services.securitytoken.AWSSecurityTokenServiceClientBuilder;
import com.amazonaws.services.securitytoken.model.AssumeRoleRequest;
import com.amazonaws.services.securitytoken.model.AssumeRoleResult;
import com.amazonaws.services.securitytoken.model.Credentials;
import edu.emory.awsaccount.service.provider.RoleDeprovisioningProvider;
import org.openeai.config.AppConfig;

import java.util.List;
import java.util.Properties;


/**
 * Delete the AWS IAM role for the custom role.
 */
public class DeleteAwsRoleForCustomRole extends AbstractStep implements Step {
    private String accessKeyId = null;
    private String secretKey = null;
    private String roleArnPattern = null;
    private int roleAssumptionDurationSeconds = 0;

    public void init(String provisioningId, Properties props, AppConfig aConfig, RoleDeprovisioningProvider rpp) throws StepException {
        super.init(provisioningId, props, aConfig, rpp);

        String LOGTAG = getStepTag() + "[DeleteAwsRoleForCustomRole.init] ";

        logger.info(LOGTAG + "Getting custom step properties...");

        setAccessKeyId(getMandatoryStringProperty(LOGTAG, "accessKeyId", false));
        setSecretKey(getMandatoryStringProperty(LOGTAG, "secretKey", true));
        setRoleArnPattern(getMandatoryStringProperty(LOGTAG, "roleArnPattern", false));
        setRoleAssumptionDurationSeconds(getMandatoryIntegerProperty(LOGTAG, "roleAssumptionDurationSeconds", false));

        logger.info(LOGTAG + "Initialization complete.");
    }

    protected List<Property> run() throws StepException {
        long startTime = System.currentTimeMillis();
        String LOGTAG = getStepTag() + "[DeleteAwsRoleForCustomRole.run] ";
        logger.info(LOGTAG + "Begin running the step.");

        addResultProperty(STEP_EXECUTION_METHOD_PROPERTY_KEY, STEP_EXECUTION_METHOD_EXECUTED);

        // the account and custom role name was specified in the requisition
        RoleDeprovisioningRequisition roleDeprovisioningRequisition = getRoleDeprovisioning().getRoleDeprovisioningRequisition();
        String accountId = roleDeprovisioningRequisition.getAccountId();
        String roleName = roleDeprovisioningRequisition.getRoleName();

        try {
            AmazonIdentityManagement iam = buildIamClient(accountId);
            iam.deleteRole(new DeleteRoleRequest().withRoleName(roleName));
        }
        catch (Exception e) {
            String errMsg = "An error occurred deleting the role. The exception is: " + e.getMessage();
            logger.error(LOGTAG + errMsg);
            //throw new StepException(errMsg, e);
        }

        // Update the step.
        update(STEP_STATUS_COMPLETED, STEP_RESULT_SUCCESS);

        // Log completion time.
        long time = System.currentTimeMillis() - startTime;
        logger.info(LOGTAG + "Step run completed in " + time + "ms.");

        // Return the properties.
        return getResultProperties();
    }

    protected List<Property> simulate() throws StepException {
        long startTime = System.currentTimeMillis();
        String LOGTAG = getStepTag() + "[DeleteAwsRoleForCustomRole.simulate] ";
        logger.info(LOGTAG + "Begin step simulation.");

        addResultProperty(STEP_EXECUTION_METHOD_PROPERTY_KEY, STEP_EXECUTION_METHOD_SIMULATED);

        // simulated result properties
        addResultProperty("customRoleArn", "arn:aws:iam::123456789012:role/MyCustomRole");

        // Update the step.
        update(STEP_STATUS_COMPLETED, STEP_RESULT_SUCCESS);

        // Log completion time.
        long time = System.currentTimeMillis() - startTime;
        logger.info(LOGTAG + "Step simulation completed in " + time + "ms.");

        // Return the properties.
        return getResultProperties();
    }

    protected List<Property> fail() throws StepException {
        long startTime = System.currentTimeMillis();
        String LOGTAG = getStepTag() + "[DeleteAwsRoleForCustomRole.fail] ";
        logger.info(LOGTAG + "Begin step failure simulation.");

        addResultProperty(STEP_EXECUTION_METHOD_PROPERTY_KEY, STEP_EXECUTION_METHOD_FAILURE);

        // Update the step.
        update(STEP_STATUS_COMPLETED, STEP_RESULT_FAILURE);

        // Log completion time.
        long time = System.currentTimeMillis() - startTime;
        logger.info(LOGTAG + "Step failure simulation completed in " + time + "ms.");

        // Return the properties.
        return getResultProperties();
    }

    public void rollback() throws StepException {
        super.rollback();
        long startTime = System.currentTimeMillis();
        String LOGTAG = getStepTag() + "[DeleteAwsRoleForCustomRole.rollback] ";
        logger.info(LOGTAG + "Rollback called, but this step has nothing to roll back.");

        // Update the step.
        update(STEP_STATUS_ROLLBACK, STEP_RESULT_SUCCESS);

        // Log completion time.
        long time = System.currentTimeMillis() - startTime;
        logger.info(LOGTAG + "Rollback completed in " + time + "ms.");
    }

    /**
     * Build an AmazonIdentityManagement client connected to the correct account with the correct role
     * @param accountId account
     * @return client
     */
    private AmazonIdentityManagement buildIamClient(String accountId) {
        // Build the roleArn of the role to assume from the base ARN and
        // the account number in the query spec.
        String roleArn = getRoleArnPattern().replace("ACCOUNT_NUMBER", accountId);

        // Instantiate a basic credential provider
        BasicAWSCredentials creds = new BasicAWSCredentials(getAccessKeyId(), getSecretKey());
        AWSStaticCredentialsProvider cp = new AWSStaticCredentialsProvider(creds);

        // Create the STS client
        AWSSecurityTokenService sts = AWSSecurityTokenServiceClientBuilder.standard()
                .withRegion("us-east-1")
                .withCredentials(cp)
                .build();

        // Assume the appropriate role in the appropriate account.
        AssumeRoleRequest assumeRequest = new AssumeRoleRequest()
                .withRoleArn(roleArn)
                .withDurationSeconds(getRoleAssumptionDurationSeconds())
                .withRoleSessionName("AwsAccountService");

        AssumeRoleResult assumeResult = sts.assumeRole(assumeRequest);
        Credentials credentials = assumeResult.getCredentials();

        // Instantiate a credential provider
        BasicSessionCredentials temporaryCredentials = new BasicSessionCredentials(credentials.getAccessKeyId(),
                credentials.getSecretAccessKey(), credentials.getSessionToken());
        AWSStaticCredentialsProvider sessionCreds = new AWSStaticCredentialsProvider(temporaryCredentials);

        // Create the IAM client
        return AmazonIdentityManagementClientBuilder.standard()
                .withRegion("us-east-1")
                .withCredentials(sessionCreds)
                .build();
    }

    private String getAccessKeyId() { return accessKeyId; }
    private void setAccessKeyId(String v) { this.accessKeyId = v; }
    private String getSecretKey() { return secretKey; }
    private void setSecretKey(String v) { this.secretKey = v; }
    private String getRoleArnPattern() { return roleArnPattern; }
    private void setRoleArnPattern(String v) { this.roleArnPattern = v; }
    private int getRoleAssumptionDurationSeconds() { return roleAssumptionDurationSeconds; }
    private void setRoleAssumptionDurationSeconds(int v) { this.roleAssumptionDurationSeconds = v; }
}
