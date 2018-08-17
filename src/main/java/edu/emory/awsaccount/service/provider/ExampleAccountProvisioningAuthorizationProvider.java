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
import java.util.HashMap;
import java.util.List;
// Log4j
import org.apache.log4j.Category;

// OpenEAI foundation
import org.openeai.OpenEaiObject;
import org.openeai.config.AppConfig;
import org.openeai.config.EnterpriseConfigurationObjectException;
import org.openeai.config.EnterpriseFieldException;
import org.openeai.config.PropertyConfig;
//AWS Message Object API (MOA)
import com.amazon.aws.moa.jmsobjects.cloudformation.v1_0.Stack;
import com.amazon.aws.moa.jmsobjects.provisioning.v1_0.AccountProvisioningAuthorization;
import com.amazon.aws.moa.objects.resources.v1_0.AccountProvisioningAuthorizationQuerySpecification;

/**
 * An example object provider that maintains an in-memory store of stacks.
 *
 * @author Steve Wheat (swheat@emory.edu)
 * @version 1.0 - 13 August 2018
 *
 */

public class ExampleAccountProvisioningAuthorizationProvider extends OpenEaiObject implements AccountProvisioningAuthorizationProvider {

    private Category logger = OpenEaiObject.logger;
    private AppConfig m_appConfig;
    private String m_provideReplyUri = null;
    private String m_responseReplyUri = null;
    private long m_stackId = 2646351098L;
    private HashMap<String, Stack> m_aliasMap = new HashMap();
    private String LOGTAG = "[ExampleAccountProvisioningAuthorizationProvider] ";

    /**
     * @see AccountProvisioningAuthorizationProvider.java
     */
    @Override
    public void init(AppConfig aConfig) throws ProviderException {
        logger.info(LOGTAG + "Initializing...");
        m_appConfig = aConfig;

        // Get the provider properties
        PropertyConfig pConfig = new PropertyConfig();
        try {
            pConfig = (PropertyConfig) aConfig.getObject("AccountProvisioningAuthorizationProviderProperties");
        } catch (EnterpriseConfigurationObjectException eoce) {
            String errMsg = "Error retrieving a PropertyConfig object from " + "AppConfig: The exception is: " + eoce.getMessage();
            logger.error(LOGTAG + errMsg);
            throw new ProviderException(errMsg, eoce);
        }

        logger.info(LOGTAG + pConfig.getProperties().toString());

        logger.info(LOGTAG + "Initialization complete.");
    }

    /**
     * @see AccountProvisioningAuthorizationProvider.java
     * 
     *      Note: this implementation queries by AccountId.
     */
    @Override
    public List<AccountProvisioningAuthorization> query(AccountProvisioningAuthorizationQuerySpecification querySpec) throws ProviderException {

        // Get a configured AccountProvisioningAuthorization from AppConfig
    	AccountProvisioningAuthorization auth = new AccountProvisioningAuthorization();
        try {
            auth = (AccountProvisioningAuthorization) getAppConfig().getObjectByType(auth.getClass().getName());
        } catch (EnterpriseConfigurationObjectException ecoe) {
            String errMsg = "An error occurred getting an object from AppConfig. " + "The exception is: " + ecoe.getMessage();
            logger.error(LOGTAG + errMsg);
            throw new ProviderException(errMsg, ecoe);
        }

        // Set the values of the AccountProvisioningAuthorization
        try {
            auth.setUserId(querySpec.getUserId());
            auth.setIsAuthorized("true");
            auth.setAuthorizedUserDescription("This dummy implementation authorizes all users. A real implementation will provide a description here of who is authorized.");
            
        } catch (EnterpriseFieldException efe) {
            String errMsg = "An error occurred seting field values. " + "The exception is: " + efe.getMessage();
            logger.error(LOGTAG + errMsg);
            throw new ProviderException(errMsg, efe);
        }

        // Add the AccountProvisioningAuthorization to a list.
        List<AccountProvisioningAuthorization> authList = new ArrayList();
        authList.add(auth);
        return authList;

    }

    private AppConfig getAppConfig() {
        return m_appConfig;
    }

}
