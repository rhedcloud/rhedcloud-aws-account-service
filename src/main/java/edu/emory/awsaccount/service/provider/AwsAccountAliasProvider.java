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
import com.amazonaws.services.identitymanagement.AmazonIdentityManagement;
import com.amazonaws.services.identitymanagement.AmazonIdentityManagementClientBuilder;
import com.amazonaws.services.identitymanagement.model.ListAccountAliasesResult;

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
    private AmazonIdentityManagement m_iam = null;
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
        } 
        catch (EnterpriseConfigurationObjectException eoce) {
            String errMsg = "Error retrieving a PropertyConfig object from " + "AppConfig: The exception is: " + eoce.getMessage();
            logger.error(LOGTAG + errMsg);
            throw new ProviderException(errMsg, eoce);
        }
        
        // Get the AWS credentials the provider will use
        String accessKeyId = getProperties().getProperty("accessKeyId");
        String secretKey = getProperties().getProperty("secretKeyId");
        
        // Instantiate a basic credential provider
        BasicAWSCredentials creds = new BasicAWSCredentials(accessKeyId, secretKey);
        AWSStaticCredentialsProvider cp = new AWSStaticCredentialsProvider(creds);
        
        
        // Create the IAM client
        AmazonIdentityManagement iam = AmazonIdentityManagementClientBuilder.standard().withCredentials(cp).build();
        setIamClient(iam);
        
        // Query for the account alias of the master account to confirm all is working
        ListAccountAliasesResult result = iam.listAccountAliases();
        List<String> aliasList = result.getAccountAliases();
        ListIterator it = aliasList.listIterator();
        while (it.hasNext()) {
        	String alias = (String)it.next();
        	logger.info(LOGTAG + "Master account alias is: " + alias);
        } 

        logger.info(LOGTAG + "Initialization complete.");
    }

    /**
     * @see AccountAliasProvider.java
     * 
     *      Note: this implementation queries by AccountId.
     */
    @Override
    public List<AccountAlias> query(AccountAliasQuerySpecification querySpec) throws ProviderException {

        // Get a configured AccountAlias from AppConfig
        AccountAlias alias = new AccountAlias();
        try {
            alias = (AccountAlias) getAppConfig().getObjectByType(alias.getClass().getName());
        } catch (EnterpriseConfigurationObjectException ecoe) {
            String errMsg = "An error occurred getting an object from AppConfig. " + "The exception is: " + ecoe.getMessage();
            logger.error(LOGTAG + errMsg);
            throw new ProviderException(errMsg, ecoe);
        }
        
        // TODO: Assume the appropriate role
        
        // Query AWS for the AccountAlias
        ListAccountAliasesResult result = getIamClient().listAccountAliases();
        List<String> aliasList = result.getAccountAliases();
        
        // Add the results to a list.
        List<AccountAlias> accountAliasList = new ArrayList<AccountAlias>();
        ListIterator it = aliasList.listIterator();
        while (it.hasNext()) {
        	// Clone the AccountAlias object
        	AccountAlias a = null;
        	try {
        		a = (AccountAlias)alias.clone();
        	}
        	catch (CloneNotSupportedException cnse) {
        		String errMsg = "An error occurred cloning a message object. " +
        				"The exception is: " + cnse.getMessage();
        		logger.error(LOGTAG + errMsg);
        		throw new ProviderException(errMsg, cnse);
        	}
        	
        	// Set the values of AccountAlias
        	try {
        		a.setAccountId(querySpec.getAccountId());
        		a.setAppName((String)it.next());
        	}
        	catch (EnterpriseFieldException efe) {
        		String errMsg = "An error occurred setting field values on " +
        				"the object. The exception is: " + efe.getMessage();
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

        throw new ProviderException("Create action not yet implemented.");
    }

    /**
     * @see AccountAliasProvider.java
     */
    @Override
    public void delete(AccountAlias alias) throws ProviderException {

        throw new ProviderException("Create action not yet implemented.");
    }

    private void setAppConfig(AppConfig aConfig) {
    	m_appConfig = aConfig;
    }
    
    private AppConfig getAppConfig() {
        return m_appConfig;
    }

    private void setIamClient(AmazonIdentityManagement iam) {
    	m_iam = iam;
    }
    
    private AmazonIdentityManagement getIamClient() {
        return m_iam;
    }
    
}
