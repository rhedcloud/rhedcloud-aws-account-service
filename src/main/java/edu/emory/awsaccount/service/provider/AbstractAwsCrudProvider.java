/*******************************************************************************
 $Source: $
 $Revision: $
 *******************************************************************************/

/******************************************************************************
 This file is part of the Emory AWS Account Service.

 Copyright (C) 2018 Emory University. All rights reserved. 
 ******************************************************************************/

package edu.emory.awsaccount.service.provider;

import java.util.List;
import java.util.ListIterator;

import org.apache.log4j.Logger;
import org.openeai.config.AppConfig;
import org.openeai.jms.consumer.commands.provider.AbstractCrudProvider;

import com.amazonaws.auth.AWSStaticCredentialsProvider;
import com.amazonaws.auth.BasicAWSCredentials;
import com.amazonaws.auth.BasicSessionCredentials;
import com.amazonaws.services.identitymanagement.AmazonIdentityManagement;
import com.amazonaws.services.identitymanagement.AmazonIdentityManagementClientBuilder;
import com.amazonaws.services.identitymanagement.model.AmazonIdentityManagementException;
import com.amazonaws.services.identitymanagement.model.ListAccountAliasesResult;
import com.amazonaws.services.securitytoken.AWSSecurityTokenService;
import com.amazonaws.services.securitytoken.AWSSecurityTokenServiceClientBuilder;
import com.amazonaws.services.securitytoken.model.AssumeRoleRequest;
import com.amazonaws.services.securitytoken.model.AssumeRoleResult;
import com.amazonaws.services.securitytoken.model.Credentials;

/**
 * CrudProviderAmazonIdentityManagement
 *
 * @author Steve Wheat (swheat@emory.edu)
 * @version 1.0 - 12 July 2018
 * @param <M>
 * @param <Q>
 *
 */

public class AbstractAwsCrudProvider<M, Q> extends AbstractCrudProvider<M, Q> {
    private static Logger logger = Logger.getLogger(AbstractAwsCrudProvider.class);
    // private AppConfig m_appConfig;
    private String m_accessKeyId = null;
    private String m_secretKey = null;
    private String m_roleArnPattern = null;
    private int m_roleAssumptionDurationSeconds = 0;
    private String LOGTAG = "[AbstractAwsCrudProvider] ";

    /**
     * @throws org.openeai.jms.consumer.commands.provider.ProviderException
     * @see AccountAliasProvider.java
     */
    @Override
    public void init(AppConfig aConfig) throws org.openeai.jms.consumer.commands.provider.ProviderException {
        super.init(aConfig);
        logger.info(LOGTAG + "Initializing...");
        // Get the AWS credentials the provider will use
        getProperties().list(System.out);
        String accessKeyId = getProperties().getProperty("accessKeyId");
        if (accessKeyId == null || accessKeyId.equals("")) {
            String errMsg = "No base accessKeyId property specified. Can't continue.";
            throw new org.openeai.jms.consumer.commands.provider.ProviderException(errMsg);
        }
        setAccessKeyId(accessKeyId);

        String secretKey = getProperties().getProperty("secretKey");
        if (accessKeyId == null || accessKeyId.equals("")) {
            String errMsg = "No base secretKey property specified. Can't continue.";
            throw new org.openeai.jms.consumer.commands.provider.ProviderException(errMsg);
        }
        setSecretKey(secretKey);

        String sRoleAssumptionDurationSeconds = getProperties().getProperty("roleAssumptionDurationSeconds");
        if (sRoleAssumptionDurationSeconds == null || sRoleAssumptionDurationSeconds.equals("")) {
            String errMsg = "No base roleAssumptionDurationSeconds property specified. Can't continue.";
            throw new org.openeai.jms.consumer.commands.provider.ProviderException(errMsg);
        }
        setRoleAssumptionDuration(Integer.parseInt(sRoleAssumptionDurationSeconds));

        String roleArnPattern = getProperties().getProperty("roleArnPattern");
        if (roleArnPattern == null || roleArnPattern.equals("")) {
            String errMsg = "No base roleArnPattern property specified with which to " + "assume roles in other account. Can't continue.";
            throw new org.openeai.jms.consumer.commands.provider.ProviderException(errMsg);
        }
        setRoleArnPattern(roleArnPattern);

        // Instantiate a basic credential provider
        logger.info(LOGTAG + "Initializing AWS credential provider...");
        BasicAWSCredentials creds = new BasicAWSCredentials(accessKeyId, secretKey);
        AWSStaticCredentialsProvider cp = new AWSStaticCredentialsProvider(creds);

        // Create the IAM client
        logger.info(LOGTAG + "Creating the IAM client...");
        AmazonIdentityManagement iam = AmazonIdentityManagementClientBuilder.standard().withRegion("us-east-1").withCredentials(cp).build();

        // Query for the account alias of the master account to confirm all is
        // working.
        ListAccountAliasesResult result = null;
        try {
            logger.info(LOGTAG + "Querying for account aliases...");
            long startTime = System.currentTimeMillis();
            result = iam.listAccountAliases();
            long time = System.currentTimeMillis() - startTime;
            logger.info(LOGTAG + "Retrieved account alias list in " + time + " ms.");
        } catch (AmazonIdentityManagementException aime) {
            String errMsg = "An error occured querying for a list of account aliases. " + "The exception is: " + aime.getMessage();
            logger.error(LOGTAG + errMsg);
            throw new org.openeai.jms.consumer.commands.provider.ProviderException(errMsg, aime);
        }

        List<String> aliasList = result.getAccountAliases();
        ListIterator it = aliasList.listIterator();
        while (it.hasNext()) {
            String alias = (String) it.next();
            logger.info(LOGTAG + "Master account alias is: " + alias);
        }

        logger.info(LOGTAG + "Initialization complete.");
    }

    /**
     * 
     * @param String,
     *            the AWS access key ID to use in client connections
     * 
     */
    private void setAccessKeyId(String accessKeyId) {
        m_accessKeyId = accessKeyId;
    }

    /**
     * 
     * @return String, the AWS access key ID to use in client connections
     * 
     */
    private String getAccessKeyId() {
        return m_accessKeyId;
    }

    /**
     * 
     * @param String,
     *            the AWS secret key to use in client connections
     * 
     */
    private void setSecretKey(String secretKey) {
        m_secretKey = secretKey;
    }

    /**
     * 
     * @return String, the AWS secret to use in client connections
     * 
     */
    private String getSecretKey() {
        return m_secretKey;
    }

    /**
     * 
     * @param String,
     *            a template or pattern for building the precise role to assume
     *            for cross-account access
     * 
     */
    private void setRoleArnPattern(String pattern) {
        m_roleArnPattern = pattern;
    }

    /**
     * 
     * @return String, a template or pattern for building the precise role to
     *         assume for cross-account access
     * 
     */
    private String getRoleArnPattern() {
        return m_roleArnPattern;
    }

    /**
     * 
     * @param int,
     *            role assumption duration in seconds
     * 
     */
    private void setRoleAssumptionDuration(int seconds) {
        m_roleAssumptionDurationSeconds = seconds;
    }

    /**
     * 
     * @return int, role assumption duration in seconds
     * 
     */
    private int getRoleAssumptionDuration() {
        return m_roleAssumptionDurationSeconds;
    }

    /**
     * 
     * @param String,
     *            accountId
     *            <P>
     *            @return, AmazonIdentityManagement client connected to the
     *            correct account with the correct role
     * 
     */
    protected AmazonIdentityManagement buildIamClient(String accountId) {
        // Build the roleArn of the role to assume from the base ARN and
        // the account number in the query spec.
        logger.info(LOGTAG + "The account targeted by this request is: " + accountId);
        logger.info(LOGTAG + "The roleArnPatter is: " + getRoleArnPattern());
        String roleArn = getRoleArnPattern().replace("ACCOUNT_NUMBER", accountId);
        logger.info(LOGTAG + "Role ARN to assume for this request is: " + roleArn);

        // Instantiate a basic credential provider
        BasicAWSCredentials creds = new BasicAWSCredentials(getAccessKeyId(), getSecretKey());
        AWSStaticCredentialsProvider cp = new AWSStaticCredentialsProvider(creds);

        // Create the STS client
        AWSSecurityTokenService sts = AWSSecurityTokenServiceClientBuilder.standard().withCredentials(cp).build();

        // Assume the appropriate role in the appropriate account.
        AssumeRoleRequest assumeRequest = new AssumeRoleRequest().withRoleArn(roleArn).withDurationSeconds(getRoleAssumptionDuration())
                .withRoleSessionName("AwsAccountService");

        AssumeRoleResult assumeResult = sts.assumeRole(assumeRequest);
        Credentials credentials = assumeResult.getCredentials();

        // Instantiate a credential provider
        BasicSessionCredentials temporaryCredentials = new BasicSessionCredentials(credentials.getAccessKeyId(),
                credentials.getSecretAccessKey(), credentials.getSessionToken());
        AWSStaticCredentialsProvider credProvider = new AWSStaticCredentialsProvider(temporaryCredentials);

        // Create the IAM client
        AmazonIdentityManagement iam = AmazonIdentityManagementClientBuilder.standard().withRegion("us-east-1")
                .withCredentials(credProvider).build();

        return iam;
    }

}
