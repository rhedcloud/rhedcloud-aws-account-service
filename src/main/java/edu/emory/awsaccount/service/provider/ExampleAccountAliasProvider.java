/*******************************************************************************
 $Source: $
 $Revision: $
 *******************************************************************************/

/******************************************************************************
 This file is part of the Emory AWS Account Service.

 Copyright (C) 2016 Emory University. All rights reserved. 
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
import org.openeai.config.PropertyConfig;

import com.amazon.aws.moa.jmsobjects.provisioning.v1_0.AccountAlias;

//AWS Message Object API (MOA)
import com.amazon.aws.moa.objects.resources.v1_0.AccountAliasQuerySpecification;

/**
 * An example object provider that maintains an in-memory store of stacks.
 *
 * @author Steve Wheat (swheat@emory.edu)
 *
 */
public class ExampleAccountAliasProvider extends OpenEaiObject implements AccountAliasProvider {

    private Category logger = OpenEaiObject.logger;
    private AppConfig m_appConfig;
    private String m_provideReplyUri = null;
    private String m_responseReplyUri = null;
    private long m_stackId = 2646351098L;
    private HashMap<String, AccountAlias> m_stackMap = new HashMap();
    private String LOGTAG = "[ExampleSamlProviderProvider] ";

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
     * @see StackProvider.java
     * 
     *      Note: this implementation queries by StackId.
     */
    @Override
    public List<AccountAlias> query(AccountAliasQuerySpecification querySpec) throws ProviderException {

        // If the StackId is null, throw an exception.
        if (querySpec.getAccountId() == null || querySpec.getAccountId().equals("")) {
            String errMsg = "The StackId is null. The ExampleStackProvider" + "presently only implements query by StackId.";
            throw new ProviderException(errMsg);
        }

        // If there is no match, return null.
        if (m_stackMap.get(querySpec.getAccountId()) == null)
            return null;

        // Otherwise return the Stack from the VPC map
        else {
            List<AccountAlias> stackList = new ArrayList();
            stackList.add(m_stackMap.get(querySpec.getAccountId()));
            return stackList;
        }
    }

    /**
     * @see StackProvider.java
     */
    @Override
    public void create(AccountAlias req) throws ProviderException {

        // Get a configured Stack object from AppConfig
        AccountAlias stack = new AccountAlias();
        try {
            stack = (AccountAlias) m_appConfig.getObjectByType(stack.getClass().getName());
        } catch (EnterpriseConfigurationObjectException ecoe) {
            String errMsg = "An error occurred retrieving an object from " + "AppConfig. The exception is: " + ecoe.getMessage();
            logger.error(LOGTAG + errMsg);
            throw new ProviderException(errMsg, ecoe);
        }

        // Add the Stack to the map.
        m_stackMap.put(req.getAccountId(), req);

    }

    /**
     * @see StackProvider.java
     */
    public void update(AccountAlias stack) throws ProviderException {

        // Replace the object in the map with the same StackId.
        m_stackMap.put(stack.getAccountId(), stack);

        return;
    }

    /**
     * @see StackProvider.java
     */
    @Override
    public void delete(AccountAlias stack) throws ProviderException {

        // Remove the object in the map with the same StackId.
        m_stackMap.remove(stack.getAccountId());

        return;
    }

    /**
     * @param String,
     *            the URI to a provide reply document containing a sample
     *            object.
     *            <P>
     *            This method sets the provide reply URI property
     */
    private void setProvideReplyUri(String provideReplyUri) {
        m_provideReplyUri = provideReplyUri;
    }

    /**
     * @return String, the provide reply document containing a sample object
     *         <P>
     *         This method returns the value of the provide reply URI property
     */
    private String getProvideReplyUri() {
        return m_provideReplyUri;
    }

    /**
     * @param String,
     *            the URI to a response reply document containing a sample
     *            object.
     *            <P>
     *            This method sets the provide reply URI property
     */
    private void setResponseReplyUri(String responseReplyUri) {
        m_responseReplyUri = responseReplyUri;
    }

    /**
     * @return String, the response reply document containing a sample object
     *         <P>
     *         This method returns the value of the response reply URI property
     */
    private String getResponseReplyUri() {
        return m_responseReplyUri;
    }
}
