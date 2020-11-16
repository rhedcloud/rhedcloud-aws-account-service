/* *****************************************************************************
 This file is part of the RHEDcloud AWS Account Service.

 Copyright 2020 RHEDcloud Foundation. All rights reserved.
 ******************************************************************************/

package edu.emory.awsaccount.service.roleProvisioning.step;

import com.amazon.aws.moa.objects.resources.v1_0.Property;
import com.amazon.aws.moa.objects.resources.v1_0.RoleProvisioningRequisition;
import com.amazonaws.auth.AWSStaticCredentialsProvider;
import com.amazonaws.auth.BasicAWSCredentials;
import com.amazonaws.auth.BasicSessionCredentials;
import com.amazonaws.services.identitymanagement.AmazonIdentityManagement;
import com.amazonaws.services.identitymanagement.AmazonIdentityManagementClientBuilder;
import com.amazonaws.services.identitymanagement.model.CreateRoleRequest;
import com.amazonaws.services.identitymanagement.model.CreateRoleResult;
import com.amazonaws.services.identitymanagement.model.DeleteRoleRequest;
import com.amazonaws.services.securitytoken.AWSSecurityTokenService;
import com.amazonaws.services.securitytoken.AWSSecurityTokenServiceClientBuilder;
import com.amazonaws.services.securitytoken.model.AssumeRoleRequest;
import com.amazonaws.services.securitytoken.model.AssumeRoleResult;
import com.amazonaws.services.securitytoken.model.Credentials;
import edu.emory.awsaccount.service.provider.RoleProvisioningProvider;
import org.openeai.config.AppConfig;

import java.util.List;
import java.util.Properties;


/**
 * Create the AWS IAM role for the custom role.
 */
public class CreateAwsRoleForCustomRole extends AbstractStep implements Step {
    // taken from https://bitbucket.org/rhedcloud/rhedcloud-aws-rs-account-cfn/src/master/rhedcloud-aws-rs-account-cfn.json
    private static final String ASSUME_ROLE_POLICY_DOCUMENT_TEMPLATE =
            "{" +
            "    \"Version\": \"2012-10-17\"," +
            "    \"Statement\": [" +
            "        {" +
            "            \"Effect\": \"Allow\"," +
            "            \"Principal\": {" +
            "                \"Federated\": \"arn:aws:iam::ACCOUNT_NUMBER:saml-provider/SAML_IDP_NAME\"" +
            "            }," +
            "            \"Action\": \"sts:AssumeRoleWithSAML\"," +
            "            \"Condition\": {" +
            "                \"StringEquals\": {" +
            "                    \"saml:aud\": \"https://signin.aws.amazon.com/saml\"," +
            "                    \"saml:iss\": \"SAML_ISSUER\"" +
            "                }" +
            "            }" +
            "        }" +
            "    ]" +
            "}";

    private String accessKeyId = null;
    private String secretKey = null;
    private String roleArnPattern = null;
    private String rolePath = null;
    private int roleAssumptionDurationSeconds = 0;
    private String samlIdpName = null;
    private String samlIssuer = null;

    public void init (String provisioningId, Properties props, AppConfig aConfig, RoleProvisioningProvider rpp) throws StepException {
        super.init(provisioningId, props, aConfig, rpp);
        
        String LOGTAG = getStepTag() + "[CreateAwsRoleForCustomRole.init] ";

        logger.info(LOGTAG + "Getting custom step properties...");

        setAccessKeyId(getMandatoryStringProperty(LOGTAG, "accessKeyId", false));
        setSecretKey(getMandatoryStringProperty(LOGTAG, "secretKey", true));
        setRolePath(getMandatoryStringProperty(LOGTAG, "rolePath", false));
        setRoleArnPattern(getMandatoryStringProperty(LOGTAG, "roleArnPattern", false));
        setRoleAssumptionDurationSeconds(getMandatoryIntegerProperty(LOGTAG, "roleAssumptionDurationSeconds", false));
        setSamlIdpName(getMandatoryStringProperty(LOGTAG, "samlIdpName", false));
        setSamlIssuer(getMandatoryStringProperty(LOGTAG, "samlIssuer", false));

        logger.info(LOGTAG + "Initialization complete.");
    }

    protected List<Property> run() throws StepException {
        long startTime = System.currentTimeMillis();
        String LOGTAG = getStepTag() + "[CreateAwsRoleForCustomRole.run] ";
        logger.info(LOGTAG + "Begin running the step.");

        addResultProperty(STEP_EXECUTION_METHOD_PROPERTY_KEY, STEP_EXECUTION_METHOD_EXECUTED);

        // the account and custom role name was specified in the requisition
        RoleProvisioningRequisition roleProvisioningRequisition = getRoleProvisioning().getRoleProvisioningRequisition();
        String accountId = roleProvisioningRequisition.getAccountId();
        String roleName = roleProvisioningRequisition.getRoleName();

        CreateRoleResult createRoleResult;
        try {
            AmazonIdentityManagement iam = buildIamClient(accountId);

            String policy = ASSUME_ROLE_POLICY_DOCUMENT_TEMPLATE
                    .replace("ACCOUNT_NUMBER", accountId)
                    .replace("SAML_IDP_NAME", getSamlIdpName())
                    .replace("SAML_ISSUER", getSamlIssuer());

            CreateRoleRequest createRoleRequest = new CreateRoleRequest()
                    .withRoleName(roleName)
                    .withPath("/" + getRolePath() + "/")
                    .withAssumeRolePolicyDocument(policy);
            createRoleResult = iam.createRole(createRoleRequest);
        }
        catch (Exception e) {
            String errMsg = "An error occurred creating the role. The exception is: " + e.getMessage();
            logger.error(LOGTAG + errMsg);
            throw new StepException(errMsg, e);
        }

        // set result properties
        addResultProperty("customRoleArn", createRoleResult.getRole().getArn());

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
        String LOGTAG = getStepTag() + "[CreateAwsRoleForCustomRole.simulate] ";
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
        String LOGTAG = getStepTag() + "[CreateAwsRoleForCustomRole.fail] ";
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
        long startTime = System.currentTimeMillis();
        String LOGTAG = getStepTag() + "[CreateAwsRoleForCustomRole.rollback] ";
        logger.info(LOGTAG + "Rollback called, but this step has nothing to roll back.");

        // the account and custom role name was specified in the requisition
        RoleProvisioningRequisition roleProvisioningRequisition = getRoleProvisioning().getRoleProvisioningRequisition();
        String accountId = roleProvisioningRequisition.getAccountId();
        String roleName = roleProvisioningRequisition.getRoleName();

        try {
            AmazonIdentityManagement iam = buildIamClient(accountId);

            DeleteRoleRequest deleteRoleRequest = new DeleteRoleRequest()
                    .withRoleName(roleName);
            iam.deleteRole(deleteRoleRequest);
        }
        catch (Exception e) {
            String errMsg = "An error occurred creating the role. The exception is: " + e.getMessage();
            logger.error(LOGTAG + errMsg);
            throw new StepException(errMsg, e);
        }

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
    public String getRolePath() { return rolePath; }
    public void setRolePath(String v) { this.rolePath = v; }
    private int getRoleAssumptionDurationSeconds() { return roleAssumptionDurationSeconds; }
    private void setRoleAssumptionDurationSeconds(int v) { this.roleAssumptionDurationSeconds = v; }
    public String getSamlIdpName() { return samlIdpName; }
    public void setSamlIdpName(String v) { this.samlIdpName = v; }
    public String getSamlIssuer() { return samlIssuer; }
    public void setSamlIssuer(String v) { this.samlIssuer = v; }
}
