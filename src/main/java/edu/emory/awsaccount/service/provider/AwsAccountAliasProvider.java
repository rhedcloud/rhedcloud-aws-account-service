/*******************************************************************************
 $Source: $
 $Revision: $
 *******************************************************************************/

/******************************************************************************
 This file is part of the Emory AWS Account Service.

 Copyright (C) 2016 Emory University. All rights reserved. 
 ******************************************************************************/

package edu.emory.awsaccount.service.provider;

import java.util.List;

// Log4j
import org.apache.log4j.Category;
// OpenEAI foundation
import org.openeai.OpenEaiObject;
import org.openeai.config.AppConfig;
import org.openeai.jms.consumer.commands.provider.AbstractCrudProvider;
import org.openeai.jms.consumer.commands.provider.ProviderException;

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

public class AwsAccountAliasProvider extends AbstractCrudProvider<AccountAlias, AccountAliasQuerySpecification> {

    private Category logger = OpenEaiObject.logger;
    private AppConfig m_appConfig;

    private String LOGTAG = "[AwsAccountAliasProvider] ";
    private AmazonIdentityManagement amazonIdentityManagement = AmazonIdentityManagementClientBuilder.defaultClient();

    /**
     * @throws ProviderException
     * @see AccountAliasProvider.java
     * 
     *      Note: this implementation queries by StackId.
     */
    @Override
    public List<AccountAlias> query(AccountAliasQuerySpecification querySpec) throws ProviderException {

        // If the StackId is null, throw an exception.
        if (querySpec.getAccountId() == null || querySpec.getAccountId().equals("")) {
            String errMsg = "The StackId is null. The ExampleAccountAliasProvider" + "presently only implements query by StackId.";
            throw new org.openeai.jms.consumer.commands.provider.ProviderException(errMsg);
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
    public void create(AccountAlias req) throws org.openeai.jms.consumer.commands.provider.ProviderException {
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
            throw new org.openeai.jms.consumer.commands.provider.ProviderException(errMsg, ecoe);
        }
    }

    /**
     * @see AccountAliasProvider.java
     */
    @Override
    public void delete(AccountAlias stack) throws org.openeai.jms.consumer.commands.provider.ProviderException {

        DeleteAccountAliasRequest request = new DeleteAccountAliasRequest();
        // TODO: samlProvier to request
        DeleteAccountAliasResult result = amazonIdentityManagement.deleteAccountAlias(request);
        // Replace the object in the map with the same StackId.
        // TODO: check result));

        return;
    }
}
