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
import java.util.ListIterator;

// Log4j
import org.apache.log4j.Category;

// JDOM
import org.jdom.Document;
import org.jdom.Element;

// OpenEAI foundation
import org.openeai.OpenEaiObject;
import org.openeai.config.AppConfig;
import org.openeai.config.EnterpriseConfigurationObjectException;
import org.openeai.config.EnterpriseFieldException;
import org.openeai.config.PropertyConfig;
import org.openeai.layouts.EnterpriseLayoutException;
import org.openeai.xml.XmlDocumentReader;
import org.openeai.xml.XmlDocumentReaderException;

import com.amazon.aws.moa.jmsobjects.provisioning.v1_0.AccountAlias;
import com.amazon.aws.moa.objects.resources.v1_0.AccountAliasQuerySpecification;

//AWS Message Object API (MOA)
import com.amazonaws.services.identitymanagement.AmazonIdentityManagement;
import com.amazonaws.services.identitymanagement.AmazonIdentityManagementClientBuilder;
import com.amazonaws.services.identitymanagement.model.CreateAccountAliasRequest;
import com.amazonaws.services.identitymanagement.model.CreateAccountAliasResult;
import com.amazonaws.services.identitymanagement.model.DeleteAccountAliasRequest;
import com.amazonaws.services.identitymanagement.model.DeleteAccountAliasResult;
import com.amazonaws.services.identitymanagement.model.ListAccountAliasesRequest;
import com.amazonaws.services.identitymanagement.model.ListAccountAliasesResult;

/**
 *
 * @author Steve Wheat (swheat@emory.edu)
 *
 */
public class AwsAccountAliasProvider extends OpenEaiObject implements AccountAliasProvider {

    private Category logger = OpenEaiObject.logger;
    private AppConfig m_appConfig;
    private String m_provideReplyUri = null;
    private String m_responseReplyUri = null;
    private long m_stackId = 2646351098L;
    private String LOGTAG = "[AwsAccountAliasProvider] ";
    private AmazonIdentityManagement amazonIdentityManagement = AmazonIdentityManagementClientBuilder.defaultClient();

    @Override
    public void init(AppConfig aConfig) throws ProviderException {
        logger.info(LOGTAG + "Initializing...");
        m_appConfig = aConfig;

        // Get the provider properties
        PropertyConfig pConfig = new PropertyConfig();
        try {
            pConfig = (PropertyConfig) aConfig.getObject("AwsAccountAliasProviderProperties");
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
     * 
     *      Note: this implementation queries by StackId.
     */
    @Override
    public List<AccountAlias> query(AccountAliasQuerySpecification querySpec) throws ProviderException {

        // If the StackId is null, throw an exception.
        if (querySpec.getAccountId() == null || querySpec.getAccountId().equals("")) {
            String errMsg = "The StackId is null. The ExampleAccountAliasProvider" + "presently only implements query by StackId.";
            throw new ProviderException(errMsg);
        }

        ListAccountAliasesRequest request = new ListAccountAliasesRequest();
        querySpec.getAccountId();
        // request.s
        // // TODO: querySpec to request
        // ListAccountAliasesResult result =
        // amazonIdentityManagement.listAccountAliases(request);
        // Replace the object in the map with the same StackId.
        // TODO: check result

        return null;
    }

    /**
     * @see AccountAliasProvider.java
     */
    @Override
    public void create(AccountAlias req) throws ProviderException {
        try {
            // samlProvider = (AccountAlias)
            // m_appConfig.getObjectByType(samlProvider.getClass().getName());
            // TODO: map req to request
            CreateAccountAliasRequest request = new CreateAccountAliasRequest().withAccountAlias(req.getName());
            CreateAccountAliasResult result = amazonIdentityManagement.createAccountAlias(request);
            // TODO: check result
        } catch (Exception ecoe) {
            String errMsg = "An error occurred retrieving an object from " + "AppConfig. The exception is: " + ecoe.getMessage();
            logger.error(LOGTAG + errMsg);
            throw new ProviderException(errMsg, ecoe);
        }

        // Add the Stack to the map.
        // m_stackMap.put(req.getAccountId(), req);
    }

    /**
     * @see AccountAliasProvider.java
     */
    public void update(AccountAlias samlProvider) throws ProviderException {
        // TODO
        // use delette and create
        // UpdateAccountAliasRequest request = new UpdateSAMLProviderRequest();
        // // TODO: samlProvier to request
        // UpdateSAMLProviderResult result =
        // amazonIdentityManagement.updateSAMLProvider(request);
        // Replace the object in the map with the same StackId.
        // TODO: check result
        return;
    }

    /**
     * @see AccountAliasProvider.java
     */
    @Override
    public void delete(AccountAlias stack) throws ProviderException {

        DeleteAccountAliasRequest request = new DeleteAccountAliasRequest();
        // TODO: samlProvier to request
        DeleteAccountAliasResult result = amazonIdentityManagement.deleteAccountAlias(request);
        // Replace the object in the map with the same StackId.
        // TODO: check result));

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
