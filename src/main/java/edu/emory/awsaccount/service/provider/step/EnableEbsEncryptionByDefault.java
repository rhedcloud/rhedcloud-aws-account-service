/*******************************************************************************
 $Source: $
 $Revision: $
 *******************************************************************************/

/******************************************************************************
 This file is part of the Emory AWS Account Service.

 Copyright (C) 2017 Emory University. All rights reserved.
 ******************************************************************************/
package edu.emory.awsaccount.service.provider.step;

import com.amazon.aws.moa.objects.resources.v1_0.Property;
import com.amazonaws.auth.AWSStaticCredentialsProvider;
import com.amazonaws.auth.BasicAWSCredentials;
import com.amazonaws.auth.BasicSessionCredentials;
import com.amazonaws.services.ec2.AmazonEC2Client;
import com.amazonaws.services.ec2.AmazonEC2ClientBuilder;
import com.amazonaws.services.ec2.model.EnableEbsEncryptionByDefaultRequest;
import com.amazonaws.services.securitytoken.AWSSecurityTokenService;
import com.amazonaws.services.securitytoken.AWSSecurityTokenServiceClientBuilder;
import com.amazonaws.services.securitytoken.model.AssumeRoleRequest;
import com.amazonaws.services.securitytoken.model.AssumeRoleResult;
import com.amazonaws.services.securitytoken.model.Credentials;
import edu.emory.awsaccount.service.provider.VirtualPrivateCloudProvisioningProvider;
import org.openeai.config.AppConfig;

import java.util.Arrays;
import java.util.List;
import java.util.Properties;

/**
 * If this is a new account, enable EBS encryption by default.
 * <P>
 *
 * @author Steve Wheat (swheat@emory.edu)
 * @version 1.0 - 4 March 2020
 **/
public class EnableEbsEncryptionByDefault extends AbstractStep implements Step {
    private String m_accessKeyId = null;
    private String m_secretKey = null;
    private String m_roleArnPattern = null;
    private int m_roleAssumptionDurationSeconds = 0;
    private List<String> m_regions = null;

    public void init (String provisioningId, Properties props,
            AppConfig aConfig, VirtualPrivateCloudProvisioningProvider vpcpp)
            throws StepException {

        super.init(provisioningId, props, aConfig, vpcpp);

        String LOGTAG = getStepTag() + "[EnableEbsEncryptionByDefault.init] ";

        // Get custom step properties.
        logger.info(LOGTAG + "Getting custom step properties...");

        // Access key
        String accessKeyId = getProperties().getProperty("accessKeyId", null);
        setAccessKeyId(accessKeyId);
        logger.info(LOGTAG + "accessKeyId is: " + getAccessKeyId());

        // Secret key
        String secretKey = getProperties().getProperty("secretKey", null);
        setSecretKey(secretKey);
        logger.info(LOGTAG + "secretKey is: present");

        // Set the roleArnPattern property
        setRoleArnPattern(getProperties().getProperty("roleArnPattern", null));
        logger.info(LOGTAG + "roleArnPattern property is: " + getRoleArnPattern());

        // Set the roleAssumptionDurationSeconds property
        setRoleAssumptionDurationSeconds(getProperties().getProperty("roleAssumptionDurationSeconds", null));
        logger.info(LOGTAG + "roleAssumptionDurationSeconds is: " + getRoleAssumptionDurationSeconds());

        // Set the list of AWS regions
        String regionString = getProperties().getProperty("regions", null);
        logger.info(LOGTAG + "regions property is: " + regionString);
        if (regionString == null) {
            String errMsg = "No AWS regions provided in the properties. Can't continue.";
            logger.error(LOGTAG + errMsg);
            throw new StepException(errMsg);
        }
        else {
            List<String> regions = Arrays.asList(regionString.split("\\s*,\\s*"));
            setRegions(regions);
            logger.info(LOGTAG + "Regions list is: " + String.join(",", getRegions()));
        }

        logger.info(LOGTAG + "Initialization complete.");
    }

    protected List<Property> run() throws StepException {
        long startTime = System.currentTimeMillis();
        String LOGTAG = getStepTag() + "[EnableEbsEncryptionByDefault.run] ";
        logger.info(LOGTAG + "Begin running the step.");

        boolean encryptionSet = false;

        // Return properties
        addResultProperty("stepExecutionMethod", RUN_EXEC_TYPE);

        // Get some properties from previous steps.
        String allocateNewAccount = getStepPropertyValue("GENERATE_NEW_ACCOUNT", "allocateNewAccount");
        String newAccountId = getStepPropertyValue("GENERATE_NEW_ACCOUNT", "newAccountId");

        boolean allocatedNewAccount = Boolean.parseBoolean(allocateNewAccount) ;
        logger.info(LOGTAG + "allocatedNewAccount: " + allocatedNewAccount);
        logger.info(LOGTAG + "newAccountId: " + newAccountId);

        // If allocateNewAccount is true and newAccountId is not null,
        // set the EBS encryption by default for all configured regions.
        if (allocatedNewAccount && (newAccountId != null && !newAccountId.equals(PROPERTY_VALUE_NOT_AVAILABLE))) {
            logger.info(LOGTAG + "allocatedNewAccount is true and newAccountId " +
                    "is not null. Setting EBS encryption by default.");

            List<String> regions = getRegions();
            for (String region : regions) {
                logger.info(LOGTAG + "Setting EBS encryption by default for region: " + region);

                // Build the EC2 client.
                AmazonEC2Client client = buildAmazonEC2Client(newAccountId, region);

                // Build the request.
                EnableEbsEncryptionByDefaultRequest request = new EnableEbsEncryptionByDefaultRequest();

                try {
                    logger.info(LOGTAG + "Sending the encryption by default request...");
                    long queryStartTime = System.currentTimeMillis();
                    client.enableEbsEncryptionByDefault(request);
                    long queryTime = System.currentTimeMillis() - queryStartTime;
                    logger.info(LOGTAG + "received response to encryption by default request in " + queryTime + " ms.");
                } catch (Exception e) {
                    String errMsg = "An error occurred setting EBS encryption by default. The exception is: " + e.getMessage();
                    logger.error(LOGTAG + errMsg);
                    throw new StepException(errMsg, e);
                }
            }
            encryptionSet = true;
        }
        // If allocateNewAccount is false, log it and add result props.
        else {
            logger.info(LOGTAG + "allocateNewAccount is false. no need to set EBS encryption by default, because it " +
                "already set at the time the account was created.");
            addResultProperty("allocateNewAccount", "false");
        }

        // Update the step.
        if (allocatedNewAccount == false || encryptionSet == true) {
            update(COMPLETED_STATUS, SUCCESS_RESULT);
        }
        else update(COMPLETED_STATUS, FAILURE_RESULT);

        // Log completion time.
        long time = System.currentTimeMillis() - startTime;
        logger.info(LOGTAG + "Step run completed in " + time + "ms.");

        // Return the properties.
        return getResultProperties();
    }

    protected List<Property> simulate() throws StepException {
        long startTime = System.currentTimeMillis();
        String LOGTAG = getStepTag() + "[EnableEbsEncryptionByDefault.simulate] ";
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
        String LOGTAG = getStepTag() + "[EnableEbsEncryptionByDefault.fail] ";
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

        super.rollback();

        String LOGTAG = getStepTag() + "[EnableEbsEncryptionByDefault.rollback] ";

        logger.info(LOGTAG + "Rollback called, but this step has nothing to roll back.");

        update(ROLLBACK_STATUS, SUCCESS_RESULT);

        // Log completion time.
        long time = System.currentTimeMillis() - startTime;
        logger.info(LOGTAG + "Rollback completed in " + time + "ms.");
    }

    private void setAccessKeyId (String accessKeyId) throws StepException {
        if (accessKeyId == null) {
            String errMsg = "accessKeyId property is null. Can't continue.";
            throw new StepException(errMsg);
        }

        m_accessKeyId = accessKeyId;
    }

    private String getAccessKeyId() {
        return m_accessKeyId;
    }

    private void setSecretKey (String secretKey) throws StepException {
        if (secretKey == null) {
            String errMsg = "secretKey property is null. Can't continue.";
            throw new StepException(errMsg);
        }

        m_secretKey = secretKey;
    }

    private String getSecretKey() {
        return m_secretKey;
    }

    private void setRoleArnPattern(String pattern) throws StepException {
        if (pattern == null) {
            String errMsg = "roleArnPattern property is null. Can't assume role in target accounts. Can't continue.";
            throw new StepException(errMsg);
        }

        m_roleArnPattern = pattern;
    }

    private String getRoleArnPattern() {
        return m_roleArnPattern;
    }

    private void setRoleAssumptionDurationSeconds(String seconds) throws StepException {
        if (seconds == null) {
            String errMsg = "roleAssumptionDurationSeconds property is null. Can't continue.";
            throw new StepException(errMsg);
        }

        m_roleAssumptionDurationSeconds = Integer.parseInt(seconds);
    }


    private int getRoleAssumptionDurationSeconds() {
        return m_roleAssumptionDurationSeconds;
    }

    private void setRegions(List<String> regions) {
        m_regions = regions;
    }

    private List<String> getRegions() {
        return m_regions;
    }

    private AmazonEC2Client buildAmazonEC2Client(String accountId, String region) {
        String LOGTAG = getStepTag() + "[buildAmazonEC2Client] ";

        // Build the roleArn of the role to assume from the base ARN and
        // the account number in the query spec.
        logger.info(LOGTAG + "The account targeted by this request is: " + accountId);
        logger.info(LOGTAG + "The region targeted by this request is: " + region);
        logger.info(LOGTAG + "The roleArnPattern is: " + getRoleArnPattern());
        String roleArn = getRoleArnPattern().replace("ACCOUNT_NUMBER", accountId);
        logger.info(LOGTAG + "Role ARN to assume for this request is: " + roleArn);

        // Instantiate a basic credential provider
        BasicAWSCredentials creds = new BasicAWSCredentials(getAccessKeyId(), getSecretKey());
        AWSStaticCredentialsProvider cp = new AWSStaticCredentialsProvider(creds);

        // Create the STS client
        AWSSecurityTokenService sts = AWSSecurityTokenServiceClientBuilder.standard()
                                        .withCredentials(cp)
                                        .withRegion(region)
                                        .build();

        // Assume the appropriate role in the appropriate account.
        AssumeRoleRequest assumeRequest = new AssumeRoleRequest().withRoleArn(roleArn)
            .withDurationSeconds(getRoleAssumptionDurationSeconds())
            .withRoleSessionName("AwsAccountService");

        AssumeRoleResult assumeResult = sts.assumeRole(assumeRequest);
        Credentials credentials = assumeResult.getCredentials();

        // Instantiate a credential provider
        BasicSessionCredentials temporaryCredentials = new BasicSessionCredentials(credentials.getAccessKeyId(), credentials.getSecretAccessKey(), credentials.getSessionToken());
        AWSStaticCredentialsProvider credProvider = new AWSStaticCredentialsProvider(temporaryCredentials);

        // Create the EC2 client
        return (AmazonEC2Client)AmazonEC2ClientBuilder.standard()
                .withCredentials(credProvider)
                .withRegion(region)
                .build();
    }
}
