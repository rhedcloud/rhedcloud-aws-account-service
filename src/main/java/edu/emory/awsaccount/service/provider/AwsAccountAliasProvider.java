/*******************************************************************************
 $Source: $
 $Revision: $
 *******************************************************************************/

/******************************************************************************
 This file is part of the Emory AWS Account Service.

 Copyright (C) 2018 Emory University. All rights reserved. 
 ******************************************************************************/

package edu.emory.awsaccount.service.provider;

// Java utilities
import java.util.ArrayList;
import java.util.List;
import java.util.ListIterator;
import java.util.Properties;

// Log4j
import org.apache.log4j.Category;

// OpenEAI foundation
import org.openeai.OpenEaiObject;
import org.openeai.config.AppConfig;
import org.openeai.config.EnterpriseConfigurationObjectException;
import org.openeai.config.EnterpriseFieldException;
import org.openeai.config.PropertyConfig;

import com.amazon.aws.moa.jmsobjects.provisioning.v1_0.AccountAlias;
import com.amazon.aws.moa.objects.resources.v1_0.AccountAliasQuerySpecification;
import com.amazonaws.auth.AWSCredentialsProvider;
import com.amazonaws.auth.AWSStaticCredentialsProvider;
import com.amazonaws.auth.BasicAWSCredentials;
import com.amazonaws.auth.BasicSessionCredentials;
import com.amazonaws.services.identitymanagement.AmazonIdentityManagement;
import com.amazonaws.services.identitymanagement.AmazonIdentityManagementClientBuilder;
import com.amazonaws.services.identitymanagement.model.AmazonIdentityManagementException;
import com.amazonaws.services.identitymanagement.model.CreateAccountAliasRequest;
import com.amazonaws.services.identitymanagement.model.CreateAccountAliasResult;
import com.amazonaws.services.identitymanagement.model.DeleteAccountAliasRequest;
import com.amazonaws.services.identitymanagement.model.DeleteAccountAliasResult;
import com.amazonaws.services.identitymanagement.model.ListAccountAliasesResult;
import com.amazonaws.services.securitytoken.AWSSecurityTokenService;
import com.amazonaws.services.securitytoken.AWSSecurityTokenServiceClient;
import com.amazonaws.services.securitytoken.AWSSecurityTokenServiceClientBuilder;
import com.amazonaws.services.securitytoken.model.AssumeRoleRequest;
import com.amazonaws.services.securitytoken.model.AssumeRoleResult;
import com.amazonaws.services.securitytoken.model.Credentials;

/**
 * An example object provider that maintains an in-memory store of stacks.
 *
 * @author Steve Wheat (swheat@emory.edu)
 * @version 1.0 - 12 July 2018
 *
 */

public class AwsAccountAliasProvider extends OpenEaiObject implements AccountAliasProvider {

    private Category logger = OpenEaiObject.logger;
    private AppConfig m_appConfig;
    private String m_accessKeyId = null;
    private String m_secretKey = null;
    private String m_roleArnPattern = null;
    private int m_roleAssumptionDurationSeconds = 0;
    private String LOGTAG = "[AwsAccountAliasProvider] ";

    /**
     * @see AccountAliasProvider.java
     */
    @Override
    public void init(AppConfig aConfig) throws ProviderException {
        logger.info(LOGTAG + "Initializing...");
        setAppConfig(aConfig);

        // Get the provider properties
        PropertyConfig pConfig = new PropertyConfig();
        try {
            pConfig = (PropertyConfig) aConfig.getObject("AccountAliasProviderProperties");
            Properties props = pConfig.getProperties();
            setProperties(props);
        } catch (EnterpriseConfigurationObjectException eoce) {
            String errMsg = "Error retrieving a PropertyConfig object from " + "AppConfig: The exception is: " + eoce.getMessage();
            logger.error(LOGTAG + errMsg);
            throw new ProviderException(errMsg, eoce);
        }

        // Get the AWS credentials the provider will use
        String accessKeyId = getProperties().getProperty("accessKeyId");
        if (accessKeyId == null || accessKeyId.equals("")) {
            String errMsg = "No base accessKeyId property specified. Can't continue.";
            throw new ProviderException(errMsg);
        }
        setAccessKeyId(accessKeyId);

        String secretKey = getProperties().getProperty("secretKey");
        if (accessKeyId == null || accessKeyId.equals("")) {
            String errMsg = "No base secretKey property specified. Can't continue.";
            throw new ProviderException(errMsg);
        }
        setSecretKey(secretKey);

        String sRoleAssumptionDurationSeconds = getProperties().getProperty("roleAssumptionDurationSeconds");
        if (sRoleAssumptionDurationSeconds == null || sRoleAssumptionDurationSeconds.equals("")) {
            String errMsg = "No base roleAssumptionDurationSeconds property specified. Can't continue.";
            throw new ProviderException(errMsg);
        }
        setRoleAssumptionDuration(Integer.parseInt(sRoleAssumptionDurationSeconds));

        String roleArnPattern = getProperties().getProperty("roleArnPattern");
        if (roleArnPattern == null || roleArnPattern.equals("")) {
            String errMsg = "No base roleArnPattern property specified with which to " + "assume roles in other account. Can't continue.";
            throw new ProviderException(errMsg);
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
            throw new ProviderException(errMsg, aime);
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
     * @see AccountAliasProvider.java
     * 
     *      Note: this implementation queries by AccountId only.
     */
    @Override
    public List<AccountAlias> query(AccountAliasQuerySpecification querySpec) throws ProviderException {

        // If the accountId is null, throw an exception.
        if (querySpec.getAccountId() == null || querySpec.getAccountId().equals("")) {
            String errMsg = "No accountId provided. AccountAlias query requires an accountId.";
            throw new ProviderException(errMsg);
        }

        // Get a configured AccountAlias from AppConfig
        AccountAlias alias = new AccountAlias();
        try {
            alias = (AccountAlias) getAppConfig().getObjectByType(alias.getClass().getName());
        } catch (EnterpriseConfigurationObjectException ecoe) {
            String errMsg = "An error occurred getting an object from AppConfig. " + "The exception is: " + ecoe.getMessage();
            logger.error(LOGTAG + errMsg);
            throw new ProviderException(errMsg, ecoe);
        }

        // Build the IAM client
        AmazonIdentityManagement iam = buildIamClient(querySpec.getAccountId());

        // Query AWS for the AccountAlias
        ListAccountAliasesResult result = null;
        try {
            result = iam.listAccountAliases();
        } catch (AmazonIdentityManagementException aime) {
            String errMsg = "An error occurred querying for the list " + "of account aliases. The exception is: " + aime.getMessage();
            logger.error(LOGTAG + errMsg);
            throw new ProviderException(errMsg, aime);
        }

        // Get the results.
        List<String> aliasList = result.getAccountAliases();

        // Add the results to a alias list.
        List<AccountAlias> accountAliasList = new ArrayList<AccountAlias>();
        ListIterator it = aliasList.listIterator();
        while (it.hasNext()) {
            // Clone the AccountAlias object
            AccountAlias a = null;
            try {
                a = (AccountAlias) alias.clone();
            } catch (CloneNotSupportedException cnse) {
                String errMsg = "An error occurred cloning a message object. " + "The exception is: " + cnse.getMessage();
                logger.error(LOGTAG + errMsg);
                throw new ProviderException(errMsg, cnse);
            }

            // Set the values of AccountAlias
            try {
                a.setAccountId(querySpec.getAccountId());
                a.setName((String) it.next());
            } catch (EnterpriseFieldException efe) {
                String errMsg = "An error occurred setting field values on " + "the object. The exception is: " + efe.getMessage();
                logger.error(LOGTAG + errMsg);
                throw new ProviderException(errMsg, efe);
            }

            accountAliasList.add(a);
        }

        return accountAliasList;
    }

    /**
     * @see AccountAliasProvider.java
     */
    @Override
    public void create(AccountAlias alias) throws ProviderException {

        // Create the account alias request and set values
        CreateAccountAliasRequest createRequest = new CreateAccountAliasRequest();
        createRequest.setAccountAlias(alias.getName());

        // Build the IAM client
        AmazonIdentityManagement iam = buildIamClient(alias.getAccountId());

        // Create the alias
        CreateAccountAliasResult result = null;
        try {
            result = iam.createAccountAlias(createRequest);
        } catch (AmazonIdentityManagementException aime) {
            String errMsg = "An error occurred creating the account alias. " + "The exception is: " + aime.getMessage();
            logger.error(LOGTAG + errMsg);
            throw new ProviderException(errMsg, aime);
        }
    }

    /**
     * @see AccountAliasProvider.java
     */
    @Override
    public void delete(AccountAlias alias) throws ProviderException {

        // Create the account alias request and set values
        DeleteAccountAliasRequest deleteRequest = new DeleteAccountAliasRequest();
        deleteRequest.setAccountAlias(alias.getName());

        // Build the IAM client
        AmazonIdentityManagement iam = buildIamClient(alias.getAccountId());

        // Create the alias
        DeleteAccountAliasResult result = null;
        try {
            result = iam.deleteAccountAlias(deleteRequest);
        } catch (AmazonIdentityManagementException aime) {
            String errMsg = "An error occurred deleting the account alias. " + "The exception is: " + aime.getMessage();
            logger.error(LOGTAG + errMsg);
            throw new ProviderException(errMsg, aime);
        }
    }

    /**
     * 
     * @param AppConfig,
     *            the AppConfig for the provider
     * 
     */
    private void setAppConfig(AppConfig aConfig) {
        m_appConfig = aConfig;
    }

    /**
     * 
     * @return AppConfig, the AppConfig for the provider
     * 
     */
    private AppConfig getAppConfig() {
        return m_appConfig;
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
    private AmazonIdentityManagement buildIamClient(String accountId) {
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
