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

import com.amazon.aws.moa.jmsobjects.cloudformation.v1_0.Stack;
import com.amazon.aws.moa.jmsobjects.provisioning.v1_0.AccountAlias;
import com.amazon.aws.moa.objects.resources.v1_0.AccountAliasQuerySpecification;
import org.apache.log4j.Category;
import org.openeai.OpenEaiObject;
import org.openeai.config.AppConfig;
import org.openeai.config.EnterpriseConfigurationObjectException;
import org.openeai.config.EnterpriseFieldException;
import org.openeai.config.PropertyConfig;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Random;

/**
 * An example object provider that maintains an in-memory store of stacks.
 *
 * @author Steve Wheat (swheat@emory.edu)
 * @version 1.0 - 12 July 2018
 */
public class ExampleAccountAliasProvider extends OpenEaiObject implements AccountAliasProvider {

    private Category logger = OpenEaiObject.logger;
    private AppConfig m_appConfig;
    private String m_provideReplyUri = null;
    private String m_responseReplyUri = null;
    private long m_stackId = 2646351098L;
    private HashMap<String, Stack> m_aliasMap = new HashMap();
    private String LOGTAG = "[ExampleAccountAliasProvider] ";

    /**
     * @see VirtualPrivateCloudProvider.java
     */
    @Override
    public void init(AppConfig aConfig) throws ProviderException {
        logger.info(LOGTAG + "Initializing...");
        m_appConfig = aConfig;

        // Get the provider properties
        PropertyConfig pConfig = new PropertyConfig();
        try {
            pConfig = (PropertyConfig) aConfig.getObject("AccountAliasProviderProperties");
        } catch (EnterpriseConfigurationObjectException eoce) {
            String errMsg = "Error retrieving a PropertyConfig object from " + "AppConfig: The exception is: " + eoce.getMessage();
            logger.error(LOGTAG + errMsg);
            throw new ProviderException(errMsg, eoce);
        }

        logger.info(LOGTAG + pConfig.getProperties().toString());

        logger.info(LOGTAG + "Initialization complete.");
    }

    /**
     * @see AccountAliasProvider.java
     * <p>
     * Note: this implementation queries by AccountId.
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

        // Set the values of the AccountAlias
        try {
            alias.setAccountId(querySpec.getAccountId());
            Random rand = new Random();
            int n = rand.nextInt(50) + 1;
            String name = "screwed-up-emory-account-" + n;
            alias.setName(name);
        } catch (EnterpriseFieldException efe) {
            String errMsg = "An error occurred seting field values. " + "The exception is: " + efe.getMessage();
            logger.error(LOGTAG + errMsg);
            throw new ProviderException(errMsg, efe);
        }

        // Add the AccountAlias to a list.
        List<AccountAlias> aliasList = new ArrayList();
        aliasList.add(alias);
        return aliasList;

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

    private AppConfig getAppConfig() {
        return m_appConfig;
    }

}
